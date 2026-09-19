// Balatro seed search: generation, matching and scoring on device.
//
// The host prepends a block of #defines (see ClSearch.buildDefines) describing the
// conditions, the pools and the Detail mask, so unused branches are compiled out rather
// than branched over.
//
// SCOPE: jokers, editions, tarots, spectrals, vouchers, pack kinds, and Souls (including
// the legendary joker queue). Planets, standard cards, bosses and tags are NOT generated
// here -- a search needing those falls back to the CPU path, which produces identical
// results more slowly. The device never approximates.
//
// Anything the device cannot decide -- a resample past MAX_RESAMPLE, an unmapped stream --
// is punted to the host by flagging the seed into the overflow list.
//
// FP_CONTRACT OFF and no fast-math. One contracted fma in lua_draw's lane seeding shifts
// every result by an ulp, which lands you on a different seed's output with no error.

#pragma OPENCL EXTENSION cl_khr_fp64 : enable
#pragma OPENCL FP_CONTRACT OFF

#define PI_D 3.14159265358979323846
#define E_D  2.7182818284590452354
#define PSEUDOHASH_K 1.1239285023

#define SRC_NONE 0
#define SRC_SHO  1
#define SRC_BUF  2
#define SRC_AR1  3
#define SRC_PL1  4
#define SRC_SPE  5
#define SRC_SOU  6
#define SOURCE_COUNT 7

#define F_BOSS 0
#define F_VOUCHER 1
#define F_TAG 2
#define F_RARITY 3
#define F_EDITION 4
#define F_JOKER1 5
#define F_JOKER2 6
#define F_JOKER3 7
#define F_JOKER4 8
#define F_SOUL_TAROT 9
#define F_TAROT 10
#define F_SOUL_PLANET 11
#define F_PLANET 12
#define F_SOUL_SPECTRAL 13
#define F_SPECTRAL 14
#define F_CDT 15
#define F_SHOP_PACK 22

#define PACK_BUFFOON 0
#define PACK_ARCANA 1
#define PACK_CELESTIAL 2
#define PACK_SPECTRAL 3
#define PACK_STANDARD 4

#define R_COMMON 0
#define R_UNCOMMON 1
#define R_RARE 2
#define R_LEGENDARY 3

#define ITEM_CODE(rarity, idx) (((rarity) << 16) | (idx))

// Item codes share one namespace with the host. Jokers use their rarity (0-3) in the high
// half; tarots, the two substitution cards and spectrals get their own tags above that.
#define TAROT_BASE (4 << 16)
#define CODE_SOUL ((5 << 16) | 0)
#define CODE_BLACK_HOLE ((5 << 16) | 1)
#define SPECTRAL_BASE (6 << 16)

// Source bits, matching analyzer.Src. A condition's mask says where it will accept a card
// from, which is how "in the SHOP only, not in a pack" is expressed.
#define SB_SHOP 1
#define SB_PACK 2
#define SB_SOUL 4

__constant double DECAY[5] = { 10.0, 6.0, 3.0, 2.0, 1.0 };
inline double decay_at(int d) { return (d < 5) ? DECAY[d] : 0.0; }

inline double frac_d(double x) { return x - floor(x); }
inline double round13(double x) { return floor(x * 1e13 + 0.5) / 1e13; }

inline double lua_draw(double d0) {
    double d = d0;
    ulong u;
    d = d * PI_D; d = d + E_D; u = as_ulong(d);
    ulong z0 = (u < 2UL) ? u + 2UL : u;
    d = d * PI_D; d = d + E_D; u = as_ulong(d);
    ulong z1 = (u < 64UL) ? u + 64UL : u;
    d = d * PI_D; d = d + E_D; u = as_ulong(d);
    ulong z2 = (u < 512UL) ? u + 512UL : u;
    d = d * PI_D; d = d + E_D; u = as_ulong(d);
    ulong z3 = (u < 131072UL) ? u + 131072UL : u;

    ulong r = 0;
    for (int n = 0; n < 11; n++) {
        ulong z;
        z = z0; z = (((z << 31) ^ z) >> 45) ^ ((z & 0xFFFFFFFFFFFFFFFEUL) << 18); z0 = z; r = z;
        z = z1; z = (((z << 19) ^ z) >> 30) ^ ((z & 0xFFFFFFFFFFFFFFC0UL) << 28); z1 = z; r ^= z;
        z = z2; z = (((z << 24) ^ z) >> 48) ^ ((z & 0xFFFFFFFFFFFFFE00UL) << 7);  z2 = z; r ^= z;
        z = z3; z = (((z << 21) ^ z) >> 39) ^ ((z & 0xFFFFFFFFFFFE0000UL) << 8);  z3 = z; r ^= z;
    }
    ulong bits = (r & 0x000FFFFFFFFFFFFFUL) | 0x3FF0000000000000UL;
    return as_double(bits) - 1.0;
}

// --- seed handling -----------------------------------------------------------

__constant uchar SEED_ALPHABET[34] = {
    '1','2','3','4','5','6','7','8','9',
    'A','B','C','D','E','F','G','H','I','J','K','L','M','N','P','Q','R','S','T','U','V','W','X','Y','Z'
};

// Packed into two ulongs rather than a uchar[16]. A dynamically indexed private array is
// scratch -- device memory -- and the seed is read once per character on every stream
// initialisation, so it was among the hottest things living there.
inline int seed_for_index(ulong index, ulong *lo, ulong *hi) {
    ulong n = index;
    int len = 1;
    ulong block = 34UL;
    while (n >= block) { n -= block; len++; block *= 34UL; }
    ulong l = 0UL, h = 0UL;
    for (int i = len - 1; i >= 0; i--) {
        ulong ch = (ulong)SEED_ALPHABET[n % 34UL];
        if (i < 8) l |= ch << (8 * i); else h |= ch << (8 * (i - 8));
        n /= 34UL;
    }
    *lo = l; *hi = h;
    return len;
}

// --- rng context -------------------------------------------------------------

typedef struct {
    __global double *state;      // [compactStream * gsize + gid]
    __global const int *remap;   // cartesian stream id -> compact index, -1 if absent
    int gsize;
    int gid;
    ulong seedLo, seedHi;
    int seedLen;
    double hashedSeed;
    double prefix[MAX_LEN_SLOTS];
    uint havePrefix;             // bitmask over the prefix slots
    int overflow;                // set when the device cannot answer; host redoes the seed
} Rng;

inline uint seed_char(const Rng *g, int i) {
    return (uint)((i < 8 ? (g->seedLo >> (8 * i)) : (g->seedHi >> (8 * (i - 8)))) & 0xFFUL);
}

inline double self_hash(const Rng *g) {
    double num = 1.0;
    for (int i = g->seedLen - 1; i >= 0; i--) {
        num = frac_d(PSEUDOHASH_K / num * (double)seed_char(g, i) * PI_D + PI_D * (double)(i + 1));
    }
    return num;
}

inline double seed_prefix(const Rng *g, int keyLen) {
    double num = 1.0;
    for (int i = g->seedLen - 1; i >= 0; i--) {
        num = frac_d(PSEUDOHASH_K / num * (double)seed_char(g, i) * PI_D + PI_D * (double)(keyLen + i + 1));
    }
    return num;
}

inline int stream_id(int f, int s, int a, int r) {
    return ((f * SOURCE_COUNT + s) << 8) | (a << 4) | r;
}

inline double rnd(Rng *g,
                  __constant const uchar *keyChars,
                  __constant const uchar *keyLens,
                  __constant const uchar *keySlots,
                  int f, int s, int a, int r)
{
    if (r >= MAX_RESAMPLE || a > 15) { g->overflow = 1; return 0.0; }
    const int cid = g->remap[stream_id(f, s, a, r)];
    if (cid < 0) { g->overflow = 1; return 0.0; }

    const size_t addr = (size_t)cid * g->gsize + g->gid;
    double st = g->state[addr];
    if (isnan(st)) {
        const int keyLen = keyLens[cid];
        const int slot = keySlots[cid];
        double startv;
        if (g->havePrefix & (1u << slot)) startv = g->prefix[slot];
        else {
            startv = seed_prefix(g, keyLen);
            g->prefix[slot] = startv;
            g->havePrefix |= (1u << slot);
        }
        __constant const uchar *k = keyChars + (size_t)cid * MAX_KEY_LEN;
        double num = startv;
        for (int i = keyLen - 1; i >= 0; i--) {
            num = frac_d(PSEUDOHASH_K / num * (double)k[i] * PI_D + PI_D * (double)(i + 1));
        }
        st = num;
    }
    const double advanced = round13(frac_d(st * 1.72431234 + 2.134453429141));
    g->state[addr] = advanced;
    return lua_draw((advanced + g->hashedSeed) / 2.0);
}

#define RND(f, s, a, r) rnd(&g, keyChars, keyLens, keySlots, (f), (s), (a), (r))

inline int rnd_index(double v, int bound) { return (int)(v * (double)bound); }

// --- matching ----------------------------------------------------------------
//
// Counts are packed 4 bits per condition into one ulong, so up to 16 conditions each
// needing up to 15 cards fit in a register. The alternative -- an int array indexed by
// condition -- is a dynamically indexed private array, which on AMD means scratch, which
// means device memory on every single offer. The host enforces both limits.

#define CNT(i)     ((int)((m.found >> (4 * (i))) & 0xFUL))
#define CNT_INC(i) (m.found += (1UL << (4 * (i))))

typedef struct {
    ulong found;   // 4 bits per condition
    int unmet;     // conditions not yet at their count
    int dead;      // a required condition's window closed unsatisfied
    double total;
} Match;

inline double score_at(__constant const int *slotTarget, __constant const int *slotPriority,
                       __constant const int *anteTarget, __constant const int *antePriority,
                       __constant const double *editionScore,
                       int i, int ante, int slot)
{
    const double s = (slot <= slotTarget[i])
        ? 10.0 * (double)slotPriority[i]
        : decay_at(slot - slotTarget[i]) * (double)slotPriority[i];
    const double a = decay_at((int)abs(ante - anteTarget[i])) * (double)antePriority[i];
    return s + a + editionScore[i];
}

// Best a single further match could score once the scan has reached `ante`. Zero once the
// window has closed, which is what makes an unmet condition collapse the bound at exactly
// the moment it becomes unsatisfiable.
inline double best_per_match_from(__constant const int *anteMin, __constant const int *anteMax,
                                  __constant const int *anteTarget, __constant const int *antePriority,
                                  __constant const int *slotPriority,
                                  __constant const double *editionScore,
                                  int i, int ante)
{
    if (ante > anteMax[i]) return 0.0;
    const int eff = (ante < anteMin[i]) ? anteMin[i] : ante;
    const double a = decay_at((int)abs(eff - anteTarget[i])) * (double)antePriority[i];
    return 10.0 * (double)slotPriority[i] + a + editionScore[i];
}

// Offer one card to every condition whose window accepts it.
//
// No early break, unlike the old first-match-wins loop: a Negative Blueprint legitimately
// counts toward both a "5 blueprints" condition and an "at least 1 negative" condition,
// which is exactly what those two express when written together.
#define OFFER(code_, edition_, ante_, slot_, src_)                                 \
    do {                                                                           \
        const int _c = (code_); const int _e = (edition_);                         \
        const int _a = (ante_); const int _s = (slot_); const int _sb = (src_);    \
        for (int _i = 0; _i < NUM_CONDS; _i++) {                                   \
            if (CNT(_i) >= condCount[_i]) continue;                                \
            if (!(condSources[_i] & _sb)) continue;                                \
            if (_a < condAnteMin[_i] || _a > condAnteMax[_i]) continue;            \
            if (_s < condSlotMin[_i] || _s > condSlotMax[_i]) continue;            \
            const int _g = condEdition[_i];                                        \
            if (_g >= 0 && _g != _e) continue;                                     \
            int _hit = 0;                                                          \
            const int _o = condItemOfs[_i];                                        \
            const int _n = condItemCnt[_i];                                        \
            for (int _k = 0; _k < _n; _k++)                                        \
                if (condItems[_o + _k] == _c) { _hit = 1; break; }                 \
            if (!_hit) continue;                                                   \
            m.total += score_at(condSlotTarget, condSlotPriority, condAnteTarget,  \
                                condAntePriority, condEditionScore, _i, _a, _s);   \
            CNT_INC(_i);                                                           \
            if (CNT(_i) == condCount[_i]) m.unmet--;                               \
        }                                                                          \
    } while (0)

// Upper bound on the final total, given the scan has reached shop slot `slot_` of `ante_`.
//
// Two cases per outstanding condition: wait for a later ante, where slot 1 is available
// again but the ante penalty is at least one step worse, or finish here, where the slot
// penalty is already locked in. Taking the better of the two is what makes slot pruning
// do nothing until the later antes are themselves hopeless -- and everything once they are.
//
// Deliberately loose in one respect: every outstanding match is charged the same best
// score, ignoring that they must land in distinct slots. Tightening that buys little now
// that the required-window kill carries the pruning, and a loose-but-correct bound never
// drops a seed it should have kept.
#define UPPER_BOUND(ante_, slot_, out_)                                            \
    do {                                                                           \
        double _b = m.total;                                                       \
        const int _a = (ante_); const int _s = (slot_);                            \
        for (int _i = 0; _i < NUM_CONDS; _i++) {                                   \
            const int _missing = condCount[_i] - CNT(_i);                          \
            if (_missing <= 0) continue;                                           \
            double _here = 0.0;                                                    \
            if (_a <= condAnteMax[_i] && _s <= condSlotMax[_i]) {                  \
                const double _ss = (_s <= condSlotTarget[_i])                      \
                    ? 10.0 * (double)condSlotPriority[_i]                          \
                    : decay_at(_s - condSlotTarget[_i]) * (double)condSlotPriority[_i]; \
                const double _aa = decay_at((int)abs(_a - condAnteTarget[_i]))     \
                    * (double)condAntePriority[_i];                                \
                _here = _ss + _aa + condEditionScore[_i];                          \
            }                                                                      \
            double _later = 0.0;                                                   \
            if (_a < condAnteMax[_i]) {                                            \
                _later = best_per_match_from(condAnteMin, condAnteMax,             \
                        condAnteTarget, condAntePriority, condSlotPriority,        \
                        condEditionScore, _i, _a + 1);                             \
            }                                                                      \
            _b += (double)_missing * fmax(_here, _later);                          \
        }                                                                          \
        (out_) = _b;                                                               \
    } while (0)

// The slot-level requirement kill, valid only inside the shop loop.
//
// Packs are generated before the shop, so by the time we are here every pack and Soul this
// ante had to offer has already been seen. A pack-only condition in its last allowed ante
// is therefore dead the moment the shop starts, and a shop condition dies the moment the
// slot passes its ceiling.
#define CLOSE_SLOT(ante_, slot_)                                                   \
    do {                                                                           \
        for (int _i = 0; _i < NUM_CONDS; _i++) {                                   \
            if (!condRequired[_i]) continue;                                       \
            if (CNT(_i) >= condCount[_i]) continue;                                \
            if ((ante_) > condAnteMax[_i]) { m.dead = 1; break; }                  \
            if ((ante_) < condAnteMax[_i]) continue;                               \
            if (!(condSources[_i] & SB_SHOP)) { m.dead = 1; break; }               \
            if ((slot_) > condSlotMax[_i]) { m.dead = 1; break; }                  \
        }                                                                          \
    } while (0)

// The decisive prune. No cutoff, no score arithmetic: the instant a required condition's
// last allowed ante is behind us and it is still short, the seed cannot ever qualify.
#define CLOSE_ANTE(ante_)                                                          \
    do {                                                                           \
        for (int _i = 0; _i < NUM_CONDS; _i++) {                                   \
            if (!condRequired[_i]) continue;                                       \
            if (CNT(_i) >= condCount[_i]) continue;                                \
            if ((ante_) + 1 > condAnteMax[_i]) { m.dead = 1; break; }              \
        }                                                                          \
    } while (0)

#if WANT_SOUL_JOKERS
// The Joker4 stream carries no source or ante, so it is one queue for the whole run. The
// edition is rolled on its own "sou" stream.
#define SOUL_FOUND(ante_)                                                          \
    do {                                                                           \
        soulCount++;                                                               \
        int _li = rnd_index(RND(F_JOKER4, 0, 0, 0), POOL_N_LEGENDARY);             \
        int _ln = 0;                                                               \
        while (legendaryTaken & (1u << _li)) {                                     \
            _ln++;                                                                 \
            if (_ln >= MAX_RESAMPLE) { g.overflow = 1; break; }                    \
            _li = rnd_index(RND(F_JOKER4, 0, 0, _ln), POOL_N_LEGENDARY);           \
        }                                                                          \
        if (!g.overflow) {                                                         \
            legendaryTaken |= (1u << _li);                                         \
            int _sed = 0;                                                          \
            if (WANT_EDITIONS) {                                                   \
                const double _ev = RND(F_EDITION, SRC_SOU, (ante_), 0);            \
                _sed = (_ev > 0.997) ? 4 : ((_ev > 0.994) ? 3                      \
                     : ((_ev > 0.98) ? 2 : ((_ev > 0.96) ? 1 : 0)));               \
            }                                                                      \
            OFFER(ITEM_CODE(R_LEGENDARY, _li), _sed, (ante_), soulCount, SB_SOUL); \
        }                                                                          \
    } while (0)
#else
#define SOUL_FOUND(ante_) do { } while (0)
#endif

// A Spectral pack slot: the two substitution rolls first (Black Hole wins when both hit),
// and only if neither fires does the slot draw an actual spectral. RETRY entries in the
// pool are re-rolled, which is why the host ships their positions as a bitmask.
#if WANT_SPECTRALS
#define SCAN_SPECTRAL_PACK(ante_, size_)                                           \
    do {                                                                           \
        int sex[8]; int nSex = 0;                                                  \
        for (int c = 0; c < (size_) && !g.overflow; c++) {                         \
            int isSoul = 0, isBH = 0;                                              \
            if (RND(F_SOUL_SPECTRAL, 0, (ante_), 0) > 0.997) isSoul = 1;           \
            if (RND(F_SOUL_SPECTRAL, 0, (ante_), 0) > 0.997) { isSoul = 0; isBH = 1; } \
            if (isSoul) { OFFER(CODE_SOUL, 0, (ante_), c + 1, SB_PACK); SOUL_FOUND(ante_); continue; } \
            if (isBH) { OFFER(CODE_BLACK_HOLE, 0, (ante_), c + 1, SB_PACK); continue; } \
            int idx = rnd_index(RND(F_SPECTRAL, SRC_SPE, (ante_), 0), POOL_N_SPECTRALS); \
            int n = 0; int bad = 1;                                                \
            while (bad) {                                                          \
                bad = ((SPECTRAL_RETRY_MASK >> idx) & 1UL) ? 1 : 0;                \
                if (!bad) for (int e = 0; e < nSex; e++) if (sex[e] == idx) { bad = 1; break; } \
                if (!bad) break;                                                   \
                n++;                                                               \
                if (n >= MAX_RESAMPLE) { g.overflow = 1; break; }                  \
                idx = rnd_index(RND(F_SPECTRAL, SRC_SPE, (ante_), n), POOL_N_SPECTRALS); \
            }                                                                      \
            if (g.overflow) break;                                                 \
            if (nSex < 8) sex[nSex++] = idx;                                       \
            OFFER(SPECTRAL_BASE | idx, 0, (ante_), c + 1, SB_PACK);                \
        }                                                                          \
    } while (0)
#elif WANT_SOULS
// Souls-only: the substitution rolls are on their own stream, so the spectral choice
// never has to be made.
#define SCAN_SPECTRAL_PACK(ante_, size_)                                           \
    do {                                                                           \
        for (int c = 0; c < (size_) && !g.overflow; c++) {                         \
            int isSoul = 0, isBH = 0;                                              \
            if (RND(F_SOUL_SPECTRAL, 0, (ante_), 0) > 0.997) isSoul = 1;           \
            if (RND(F_SOUL_SPECTRAL, 0, (ante_), 0) > 0.997) { isSoul = 0; isBH = 1; } \
            if (isSoul) { OFFER(CODE_SOUL, 0, (ante_), c + 1, SB_PACK); SOUL_FOUND(ante_); } \
            else if (isBH) { OFFER(CODE_BLACK_HOLE, 0, (ante_), c + 1, SB_PACK); } \
        }                                                                          \
    } while (0)
#else
#define SCAN_SPECTRAL_PACK(ante_, size_) do { } while (0)
#endif

// --- the kernel --------------------------------------------------------------

__kernel void search(
    __constant const uchar  *keyChars,
    __constant const uchar  *keyLens,
    __constant const uchar  *keySlots,
    __constant const int    *condItems,        // flat OR sets, indexed by ofs/cnt
    __constant const int    *condItemOfs,
    __constant const int    *condItemCnt,
    __constant const int    *condCount,        // how many cards this condition needs
    __constant const int    *condRequired,     // 1 = seed dies if unmet when the window shuts
    __constant const int    *condAnteMin,
    __constant const int    *condAnteMax,
    __constant const int    *condSlotMin,
    __constant const int    *condSlotMax,
    __constant const int    *condSources,      // bitmask of SB_*
    __constant const int    *condEdition,      // -1 any, 0 none, 1..4 a specific edition
    __constant const int    *condSlotTarget,
    __constant const int    *condSlotPriority,
    __constant const int    *condAnteTarget,
    __constant const int    *condAntePriority,
    __constant const double *condEditionScore,
    __constant const int    *packFamily,
    __constant const int    *packSize,
    __constant const double *packCum,
    __constant const uchar  *voucherIgnored,
    __global const int      *remap,
    __global double         *state,
    __global int            *hitIndex,         // seed offsets within this chunk
    __global double         *hitScore,
    __global int            *hitCount,         // [0] hits, [1] overflows
    __global int            *overflowIndex,
    __global uint           *workCounter,     // zeroed by the host before each launch
    const ulong baseIndex,
    const int chunkSize,
    const int nStreams,
    const double cutoff,
    const int maxHits)
{
    const int gid = get_global_id(0);
    const int gsize = get_global_size(0);

    Rng g;
    g.state = state;
    g.remap = remap;
    g.gsize = gsize;
    g.gid = gid;

    // Work is pulled from a shared counter rather than split by a fixed stride.
    //
    // Cost per seed is wildly uneven once required conditions are in play: most seeds die
    // in ante 1 or 2 and a few survive to the last ante. A static split leaves a minority
    // of work items grinding through survivors while the rest of the card drains, which
    // shows up as occupancy sagging to roughly half and then to nothing at the tail of
    // every launch. Grabbing SEED_GRAB seeds at a time keeps every compute unit fed until
    // the chunk is genuinely finished.
    //
    // The state buffer is still indexed by gid, so it is sized by the launch width and the
    // access pattern stays coalesced no matter which seeds a work item ends up with.
    // One atomic per work GROUP, not per work item.
    //
    // A per-item grab puts every work item on the card onto a single global address. On a
    // multi-die part those atomics cross the fabric and serialise, and with a couple of
    // million work items that costs more than the imbalance it was fixing. Here one lane
    // per group does the atomic and shares the result through local memory, which divides
    // the atomic traffic by the group size.
    //
    // Every item in the group reads the same base, so the break below is uniform across
    // the group and the barriers are never divergent.
    #define GRAB_PER_GROUP 512

    const uint lid = get_local_id(0);
    const uint lsz = get_local_size(0);
    __local uint lBase;

    for (;;) {
        // Ensures everyone has read lBase from the previous round before it is overwritten.
        barrier(CLK_LOCAL_MEM_FENCE);
        if (lid == 0) lBase = atomic_add(workCounter, GRAB_PER_GROUP);
        barrier(CLK_LOCAL_MEM_FENCE);

        const uint grabbed = lBase;
        if (grabbed >= (uint)chunkSize) break;
        const uint grabEnd = min(grabbed + GRAB_PER_GROUP, (uint)chunkSize);

    for (uint seedOff = grabbed + lid; seedOff < grabEnd; seedOff += lsz) {

        for (int i = 0; i < nStreams; i++) state[(size_t)i * gsize + gid] = NAN;
        g.havePrefix = 0u;
        g.overflow = 0;
        g.seedLen = seed_for_index(baseIndex + (ulong)seedOff, &g.seedLo, &g.seedHi);
        g.hashedSeed = self_hash(&g);

        Match m;
        m.found = 0UL;
        m.unmet = NUM_CONDS;
        m.dead = 0;
        m.total = 0.0;

        // Bitmask, not an array: NUM_VOUCHERS is under 64 and every access is by a
        // computed index.
        ulong voucherActive = 0UL;

#if WANT_SOUL_JOKERS
        uint legendaryTaken = 0u;
        int soulCount = 0;
#endif

        int generatedFirstPack = 0;
        int abandoned = 0;

        for (int ante = 1; ante <= MAX_SEARCH_ANTE && !abandoned && !g.overflow; ante++) {

            // ---- voucher (never skippable: it sets the shop rates) ----
            int vIdx;
            {
                int resample = 0;
                vIdx = rnd_index(RND(F_VOUCHER, 0, ante, 0), NUM_VOUCHERS);
                while (((vIdx & 1) && !((voucherActive >> (vIdx - 1)) & 1UL)) || ((voucherActive >> vIdx) & 1UL)) {
                    resample++;
                    if (resample >= MAX_RESAMPLE) { g.overflow = 1; break; }
                    vIdx = rnd_index(RND(F_VOUCHER, 0, ante, resample), NUM_VOUCHERS);
                }
                if (g.overflow) break;
                if (!voucherIgnored[vIdx]) {
                    voucherActive |= (1UL << vIdx);
                    if (vIdx & 1) voucherActive |= (1UL << (vIdx - 1));
                }
            }

            // ---- shop rates ----
            double rate0 = 20.0;
            double rate1 = ((voucherActive >> V_TAROT_TYCOON) & 1UL) ? 32.0 : (((voucherActive >> V_TAROT_MERCHANT) & 1UL) ? 9.6 : 4.0);
            double rate2 = ((voucherActive >> V_PLANET_TYCOON) & 1UL) ? 32.0 : (((voucherActive >> V_PLANET_MERCHANT) & 1UL) ? 9.6 : 4.0);
            double rate3 = ((voucherActive >> V_MAGIC_TRICK) & 1UL) ? 4.0 : 0.0;
            double rateTotal = rate0 + rate1 + rate2 + rate3;

            // ---- packs ----
            const int numPacks = (ante == 1) ? 4 : 6;
            int jokerSlot = 0;

            for (int p = 0; p < numPacks && !g.overflow; p++) {
                int kind;
                if (ante <= 2 && !generatedFirstPack) {
                    generatedFirstPack = 1;
                    kind = BUFFOON_PACK_INDEX;
                } else {
                    const double poll = RND(F_SHOP_PACK, 0, ante, 0) * PACK_TOTAL_WEIGHT;
                    kind = NUM_PACK_KINDS - 1;
                    for (int i = 0; i < NUM_PACK_KINDS; i++) {
                        if (packCum[i] >= poll) { kind = i; break; }
                    }
                }

                const int family = packFamily[kind];
                const int size = packSize[kind];

                if (family == PACK_BUFFOON) {
#if WANT_JOKERS
                    int excluded[8];
                    int nExcluded = 0;
                    for (int c = 0; c < size && !g.overflow; c++) {
                        const double rv = RND(F_RARITY, SRC_BUF, ante, 0);
                        const int rarity = (rv > 0.95) ? R_RARE : ((rv > 0.7) ? R_UNCOMMON : R_COMMON);
                        const int poolN = (rarity == R_RARE) ? POOL_N_RARE
                                        : ((rarity == R_UNCOMMON) ? POOL_N_UNCOMMON : POOL_N_COMMON);
                        const int fam = (rarity == R_RARE) ? F_JOKER3
                                      : ((rarity == R_UNCOMMON) ? F_JOKER2 : F_JOKER1);

                        int idx = rnd_index(RND(fam, SRC_BUF, ante, 0), poolN);
                        int code = ITEM_CODE(rarity, idx);
                        int n = 0;
                        int dup = 1;
                        while (dup && !g.overflow) {
                            dup = 0;
                            for (int e = 0; e < nExcluded; e++) if (excluded[e] == code) { dup = 1; break; }
                            if (!dup) break;
                            n++;
                            if (n >= MAX_RESAMPLE) { g.overflow = 1; break; }
                            idx = rnd_index(RND(fam, SRC_BUF, ante, n), poolN);
                            code = ITEM_CODE(rarity, idx);
                        }
                        if (g.overflow) break;
                        if (nExcluded < 8) excluded[nExcluded++] = code;

                        int edition = 0;
#if WANT_EDITIONS
                        {
                            const double ev = RND(F_EDITION, SRC_BUF, ante, 0);
                            edition = (ev > 0.997) ? 4 : ((ev > 0.994) ? 3 : ((ev > 0.98) ? 2 : ((ev > 0.96) ? 1 : 0)));
                        }
#endif
                        jokerSlot++;
                        OFFER(code, edition, ante, jokerSlot, SB_PACK);
                    }
#endif
                }
                else if (family == PACK_ARCANA) {
#if WANT_TAROTS
                    // The soul roll stays mandatory here even though it is on its own
                    // stream: a slot that turns into a Soul does not draw a tarot, so
                    // skipping it would desync the tarot stream.
                    int tex[8];
                    int nTex = 0;
                    for (int c = 0; c < size && !g.overflow; c++) {
                        if (RND(F_SOUL_TAROT, 0, ante, 0) > 0.997) {
                            OFFER(CODE_SOUL, 0, ante, c + 1, SB_PACK);
                            SOUL_FOUND(ante);
                            continue;
                        }
                        int idx = rnd_index(RND(F_TAROT, SRC_AR1, ante, 0), POOL_N_TAROTS);
                        int code = TAROT_BASE | idx;
                        int n = 0;
                        int dup = 1;
                        while (dup) {
                            dup = 0;
                            for (int e = 0; e < nTex; e++) if (tex[e] == code) { dup = 1; break; }
                            if (!dup) break;
                            n++;
                            if (n >= MAX_RESAMPLE) { g.overflow = 1; break; }
                            idx = rnd_index(RND(F_TAROT, SRC_AR1, ante, n), POOL_N_TAROTS);
                            code = TAROT_BASE | idx;
                        }
                        if (g.overflow) break;
                        if (nTex < 8) tex[nTex++] = code;
                        OFFER(code, 0, ante, c + 1, SB_PACK);
                    }
#elif WANT_SOULS
                    for (int c = 0; c < size && !g.overflow; c++) {
                        if (RND(F_SOUL_TAROT, 0, ante, 0) > 0.997) {
                            OFFER(CODE_SOUL, 0, ante, c + 1, SB_PACK);
                            SOUL_FOUND(ante);
                        }
                    }
#endif
                }
#if WANT_SOULS || WANT_SPECTRALS
                else if (family == PACK_CELESTIAL) {
#if WANT_SOULS
                    // Celestial packs can only substitute Black Hole, never a Soul.
                    for (int c = 0; c < size && !g.overflow; c++) {
                        if (RND(F_SOUL_PLANET, 0, ante, 0) > 0.997) {
                            OFFER(CODE_BLACK_HOLE, 0, ante, c + 1, SB_PACK);
                        }
                    }
#endif
                }
                else if (family == PACK_SPECTRAL) {
                    SCAN_SPECTRAL_PACK(ante, size);
                }
#endif
            }

            if (g.overflow) break;

            // ---- shop ----
            for (int slot = 1; slot <= SHOP_ITEMS; slot++) {
                if (m.unmet == 0) break;

                CLOSE_SLOT(ante, slot);
                if (m.dead) break;

                {
                    double bound;
                    UPPER_BOUND(ante, slot, bound);
                    if (bound <= cutoff) { abandoned = 1; break; }
                }

                double roll = RND(F_CDT, 0, ante, 0) * rateTotal;
                int type;
                if (roll < rate0) type = 0;
                else {
                    roll -= rate0;
                    if (roll < rate1) type = 1;
                    else {
                        roll -= rate1;
                        if (roll < rate2) type = 2;
                        else { roll -= rate2; type = (roll < rate3) ? 3 : 4; }
                    }
                }

                if (type == 0) {
#if WANT_JOKERS
                    const double rv = RND(F_RARITY, SRC_SHO, ante, 0);
                    const int rarity = (rv > 0.95) ? R_RARE : ((rv > 0.7) ? R_UNCOMMON : R_COMMON);
                    const int poolN = (rarity == R_RARE) ? POOL_N_RARE
                                    : ((rarity == R_UNCOMMON) ? POOL_N_UNCOMMON : POOL_N_COMMON);
                    const int fam = (rarity == R_RARE) ? F_JOKER3
                                  : ((rarity == R_UNCOMMON) ? F_JOKER2 : F_JOKER1);
                    const int idx = rnd_index(RND(fam, SRC_SHO, ante, 0), poolN);
                    const int code = ITEM_CODE(rarity, idx);

                    int edition = 0;
#if WANT_EDITIONS
                    {
                        const double ev = RND(F_EDITION, SRC_SHO, ante, 0);
                        edition = (ev > 0.997) ? 4 : ((ev > 0.994) ? 3 : ((ev > 0.98) ? 2 : ((ev > 0.96) ? 1 : 0)));
                    }
#endif
                    OFFER(code, edition, ante, slot, SB_SHOP);
#endif
                }
#if WANT_TAROTS
                else if (type == 1) {
                    // Shop tarots are not soulable, so this is just the choice draw.
                    const int tIdx = rnd_index(RND(F_TAROT, SRC_SHO, ante, 0), POOL_N_TAROTS);
                    OFFER(TAROT_BASE | tIdx, 0, ante, slot, SB_SHOP);
                }
#endif
                // Remaining shop types draw nothing here. The planet is simply not
                // generated (exactly as the CPU filter pass leaves it when Detail has it
                // off), the playing card never had a draw, and the shop spectral slot is
                // unreachable: its rate is a hard 0 on this deck, so the cdt cascade can
                // never land on it.

                if (g.overflow) break;
            }

            if (g.overflow) break;
            if (m.dead) break;

            // The required-window kill, before the score bound: it is cheaper and it does
            // not depend on the cutoff being well tuned.
            CLOSE_ANTE(ante);
            if (m.dead) break;

            if (m.unmet == 0) break;

            if (ante < MAX_SEARCH_ANTE) {
                double bound;
                UPPER_BOUND(ante + 1, 1, bound);
                if (bound <= cutoff) abandoned = 1;
            }
        }

        if (g.overflow) {
            const int slot = atomic_inc(&hitCount[1]);
            if (slot < maxHits) overflowIndex[slot] = (int)seedOff;
            continue;
        }

        if (m.dead) continue;

        // Every required condition satisfied?
        int ok = 1;
        for (int i = 0; i < NUM_CONDS; i++) {
            if (condRequired[i] && CNT(i) < condCount[i]) { ok = 0; break; }
        }
        if (!ok) continue;

        if (m.total > cutoff) {
            const int slot = atomic_inc(&hitCount[0]);
            if (slot < maxHits) {
                hitIndex[slot] = (int)seedOff;
                hitScore[slot] = m.total;
            }
        }
    }   // end of this grab's seeds
    }   // end of the work-pulling loop
}
