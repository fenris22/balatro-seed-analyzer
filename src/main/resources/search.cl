// Balatro seed search: generation, matching and scoring on device.
//
// The host prepends a block of #defines (see ClSearch.buildDefines) describing the
// conditions, the pools and the Detail mask, so unused branches are compiled out rather
// than branched over.
//
// SCOPE: jokers, editions, tarots, planets, spectrals, vouchers, skip tags, boss blinds,
// pack kinds, and Souls (including the legendary joker queue). Playing cards (Standard
// packs) are NOT generated here -- a search needing those falls back to the CPU path, which
// produces identical results more slowly. The device never approximates.
//
// Anything the device cannot decide -- a resample past MAX_RESAMPLE, an unmapped stream --
// is punted to the host by flagging the seed into the overflow list.
//
// FP_CONTRACT OFF and no fast-math. One contracted fma in lua_draw's lane seeding shifts
// every result by an ulp, which lands you on a different seed's output with no error.

#pragma OPENCL EXTENSION cl_khr_fp64 : enable
#pragma OPENCL FP_CONTRACT OFF

// `inline` is only a hint, and it matters more than usual here: the Rng struct is passed
// by pointer to every draw, so if a call is left out of line the struct's address escapes
// and the whole thing -- including the dynamically indexed prefix cache -- is demoted to
// per-thread local memory. On Nvidia that turns every field access into a memory round
// trip, which shows up as full "utilization" at a quarter of the power budget.
#if FORCE_INLINE_ON
#define FORCE_INLINE __attribute__((always_inline)) inline
#else
#define FORCE_INLINE inline
#endif

/*
 * Where the per-work-item prefix cache lives.
 *
 * It is indexed by a value only known at runtime, and a dynamically indexed private array
 * is not a register file -- it is per-thread global memory (scratch on AMD, local on
 * Nvidia). At MAX_LEN_SLOTS doubles that was the bulk of this kernel's scratch, and every
 * hit on it was a memory round trip.
 *
 * Shared memory fixes that, but only where there is enough of it: an H100 SM has 228 KB
 * and can host several groups, while a CDNA3 compute unit has 64 KB total, so a 256-thread
 * group would monopolise it and collapse occupancy. The host therefore picks per vendor --
 * see ClSearch.buildDefines -- and USE_LOCAL_PREFIX 0 keeps the old private array.
 *
 * The stride is forced odd so consecutive work items land on different shared-memory
 * banks; an even stride would serialise a whole warp's accesses.
 */
#define PREFIX_STRIDE (MAX_LEN_SLOTS | 1)

#if USE_LOCAL_PREFIX
#define PREFIX_SPACE __local
#else
#define PREFIX_SPACE
#endif

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
#define SRC_AR2  7      // Spectral cards Omen Globe puts into Arcana packs
#define SOURCE_COUNT 8

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
#define F_OMEN_GLOBE 23

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
#define PLANET_BASE (7 << 16)
#define VOUCHER_BASE (8 << 16)
#define TAG_BASE (9 << 16)       // skip tags
#define BOSS_BASE (10 << 16)     // index into the full boss list

// Source bits, matching analyzer.Src. A condition's mask says where it will accept a card
// from, which is how "in the SHOP only, not in a pack" is expressed.
#define SB_SHOP 1
#define SB_PACK 2
#define SB_SOUL 4
#define SB_TAG 8
#define SB_VOUCHER 16
#define SB_BOSS 32

__constant double DECAY[5] = { 10.0, 6.0, 3.0, 2.0, 1.0 };
FORCE_INLINE double decay_at(int d) { return (d < 5) ? DECAY[d] : 0.0; }

FORCE_INLINE double frac_d(double x) { return x - floor(x); }

// poll_edition from the game, in its exact arithmetic: 4 Negative, 3 Polychrome, 2 Holo,
// 1 Foil, 0 none. rate is 1, or 2 with Hone, 4 with Glow Up; Negative ignores it.
FORCE_INLINE int poll_edition(double poll, double rate, double mod, int noNeg) {
    if (!noNeg && poll > 1.0 - 0.003 * mod) return 4;
    if (poll > 1.0 - 0.006 * rate * mod) return 3;
    if (poll > 1.0 - 0.02 * rate * mod) return 2;
    if (poll > 1.0 - 0.04 * rate * mod) return 1;
    return 0;
}
// Rounds to 13 decimals exactly as the game's string.format("%.13f") does. x * 1e13 is
// itself rounded, but fma recovers its exact error, so the half-way test is made on the
// true product. The old floor(x * 1e13 + 0.5) got about 1 draw in 2,800 wrong. Mirrors
// round13 in Rng.kt; fma is an explicit call, so FP_CONTRACT OFF does not affect it.
FORCE_INLINE double round13(double x) {
    const double p = x * 1e13;
    const double err = fma(x, 1e13, -p);
    const double fl = floor(p);
    const double side = (p - fl - 0.5) + err;
    const double k = (side > 0.0) ? fl + 1.0 : ((side < 0.0) ? fl : fl + fmod(fl, 2.0));
    return k / 1e13;
}

FORCE_INLINE double lua_draw(double d0) {
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

// From Main.kt's SEED_CHARS via the host defines, so host and device always agree.
__constant uchar SEED_ALPHABET[SEED_BASE] = SEED_ALPHABET_INIT;

// Packed into two ulongs rather than a uchar[16]. A dynamically indexed private array is
// scratch -- device memory -- and the seed is read once per character on every stream
// initialisation, so it was among the hottest things living there.
FORCE_INLINE int seed_for_index(ulong index, ulong *lo, ulong *hi) {
    ulong n = index;
    int len = 1;
    ulong block = SEED_BASE;
    while (n >= block) { n -= block; len++; block *= SEED_BASE; }
    ulong l = 0UL, h = 0UL;
    for (int i = len - 1; i >= 0; i--) {
        ulong ch = (ulong)SEED_ALPHABET[n % SEED_BASE];
        if (i < 8) l |= ch << (8 * i); else h |= ch << (8 * (i - 8));
        n /= SEED_BASE;
    }
    *lo = l; *hi = h;
    return len;
}

// --- rng context -------------------------------------------------------------

typedef struct {
    __global double *state;      // [compactStream * gsize + gid], memory-resident streams only
    __global const int *remap;   // cartesian stream id -> compact index, -1 if absent
    int gsize;
    int gid;
    ulong seedLo, seedHi;
    int seedLen;
    double hashedSeed;
    uint havePrefix;             // bitmask over the prefix slots
    int overflow;                // set when the device cannot answer; host redoes the seed
} Rng;

FORCE_INLINE uint seed_char(const Rng *g, int i) {
    return (uint)((i < 8 ? (g->seedLo >> (8 * i)) : (g->seedHi >> (8 * (i - 8)))) & 0xFFUL);
}

FORCE_INLINE double seed_prefix(const Rng *g, int keyLen) {
    double num = 1.0;
    for (int i = g->seedLen - 1; i >= 0; i--) {
        num = frac_d(PSEUDOHASH_K / num * (double)seed_char(g, i) * PI_D + PI_D * (double)(keyLen + i + 1));
    }
    return num;
}

FORCE_INLINE int stream_id(int f, int s, int a, int r) {
    return ((f * SOURCE_COUNT + s) << 8) | (a << 4) | r;
}

// First-touch value of a stream: pseudohash(key, seed), with the seed half cached.
FORCE_INLINE double init_stream(Rng *g,
                                PREFIX_SPACE double *prefix,
                                __constant const uchar *keyChars,
                                __constant const uchar *keyLens,
                                __constant const uchar *keySlots,
                                int cid)
{
    const int keyLen = keyLens[cid];
    const int slot = keySlots[cid];
    double startv;
    if (g->havePrefix & (1u << slot)) startv = prefix[slot];
    else {
        startv = seed_prefix(g, keyLen);
        prefix[slot] = startv;
        g->havePrefix |= (1u << slot);
    }

    __constant const uchar *k = keyChars + (size_t)cid * MAX_KEY_LEN;
    double num = startv;
    for (int i = keyLen - 1; i >= 0; i--) {
        num = frac_d(PSEUDOHASH_K / num * (double)k[i] * PI_D + PI_D * (double)(i + 1));
    }
    return num;
}

// A draw on a memory-resident stream.
FORCE_INLINE double rnd(Rng *g,
                  PREFIX_SPACE double *prefix,
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
    if (isnan(st)) st = init_stream(g, prefix, keyChars, keyLens, keySlots, cid);
    const double advanced = round13(frac_d(st * 1.72431234 + 2.134453429141));
    g->state[addr] = advanced;
    return lua_draw((advanced + g->hashedSeed) / 2.0);
}

// A draw on a register-resident stream: *st is a private variable that starts as NaN.
// Same arithmetic as rnd(), minus the global load and store.
FORCE_INLINE double rnd_reg(Rng *g,
                  PREFIX_SPACE double *prefix,
                  __constant const uchar *keyChars,
                  __constant const uchar *keyLens,
                  __constant const uchar *keySlots,
                  int cid, double *st)
{
    double s = *st;
    if (isnan(s)) {
        if (cid < 0) { g->overflow = 1; return 0.0; }
        s = init_stream(g, prefix, keyChars, keyLens, keySlots, cid);
    }
    const double advanced = round13(frac_d(s * 1.72431234 + 2.134453429141));
    *st = advanced;
    return lua_draw((advanced + g->hashedSeed) / 2.0);
}

#define RND(f, s, a, r) rnd(&g, prefix, keyChars, keyLens, keySlots, (f), (s), (a), (r))
#define RNDR(cid, stp)  rnd_reg(&g, prefix, keyChars, keyLens, keySlots, (cid), (stp))

FORCE_INLINE int rnd_index(double v, int bound) { return (int)(v * (double)bound); }

// --- shop streams (register resident) ----------------------------------------
//
// SHOP_CID[ante * NUM_SHOP_STREAMS + SS_*] is the compact id of that ante's shop stream,
// -1 if the search does not generate it. Must match ClSearch.Streams.

#define SS_CDT 0
#define SS_RARITY 1
#define SS_JOKER1 2      // +1 uncommon (Joker2), +2 rare (Joker3)
#define SS_EDITION 5
#define SS_TAROT 6
#define SS_PLANET 7

__constant int SHOP_CID[(MAX_SEARCH_ANTE + 1) * NUM_SHOP_STREAMS] = SHOP_CID_INIT;

// --- passes: prefilter stages, then the full scan ----------------------------
//
// A pass generates some subset of the pack contents and counts some subset of the
// conditions. Stages (from SearchPlanner) generate only what their conditions need, skip
// the voucher and the shop, and only ever kill a seed. The full pass is the original scan.
// All passes share one copy of the pack code, gated by these flags, so the kernel does not
// grow a second copy of it.

#define ST_JOKERS       1
#define ST_EDITIONS     2
#define ST_SOULS        4
#define ST_SOUL_JOKERS  8
#define ST_TAROTS       16
#define ST_SPECTRALS    32
#define ST_VOUCHERS     64   // this stage must track vouchers (Omen Globe, Telescope, Hone)
#define ST_PLANETS      128

#define FULL_FLAGS ((WANT_JOKERS ? ST_JOKERS : 0) | (WANT_EDITIONS ? ST_EDITIONS : 0) \
                  | (WANT_SOULS ? ST_SOULS : 0) | (WANT_SOUL_JOKERS ? ST_SOUL_JOKERS : 0) \
                  | (WANT_TAROTS ? ST_TAROTS : 0) | (WANT_SPECTRALS ? ST_SPECTRALS : 0) \
                  | (WANT_PLANETS ? ST_PLANETS : 0))

#if WANT_BOSSES
// Boss pools in the host's order, as indices into the full boss list (the item code), and
// the first ante each normal boss can appear in. Finisher bosses (every 8th ante) have no gate.
__constant int NORMAL_BOSS_CODE[NUM_NORMAL_BOSSES] = NORMAL_BOSS_CODE_INIT;
__constant int NORMAL_BOSS_GATE[NUM_NORMAL_BOSSES] = NORMAL_BOSS_GATE_INIT;
__constant int FINISHER_BOSS_CODE[NUM_FINISHER_BOSSES] = FINISHER_BOSS_CODE_INIT;
#endif

#define ALL_MASK ((1u << NUM_CONDS) - 1u)

#if NUM_STAGES > 0
__constant int  STAGE_MAX_ANTE[NUM_STAGES] = STAGE_MAX_ANTE_INIT;
__constant int  STAGE_FLAGS[NUM_STAGES]    = STAGE_FLAGS_INIT;
__constant uint STAGE_MASK[NUM_STAGES]     = STAGE_MASK_INIT;
#endif

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
    int unmet;     // conditions (in this pass's mask) not yet at their count
    int dead;      // a required condition's window closed unsatisfied
    double total;
} Match;

FORCE_INLINE double score_at(__constant const int *slotTarget, __constant const int *slotPriority,
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

FORCE_INLINE double best_per_match_from(__constant const int *anteMin, __constant const int *anteMax,
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

// Offer one card to every condition in this pass's mask whose window accepts it.
//
// No early break: a Negative Blueprint legitimately counts toward both a "5 blueprints"
// condition and an "at least 1 negative" condition.
#define OFFER(code_, edition_, ante_, slot_, src_)                                 \
    do {                                                                           \
        const int _c = (code_); const int _e = (edition_);                         \
        const int _a = (ante_); const int _s = (slot_); const int _sb = (src_);    \
        for (int _i = 0; _i < NUM_CONDS; _i++) {                                   \
            if (!((passMask >> _i) & 1u)) continue;                                \
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
// Full pass only. See MatchState.upperBound for the reasoning.
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

// True if some unmet condition can still take a shop card at `slot_` or later in this
// ante's shop. When it is false the rest of the shop cannot change the result, so it is
// not generated -- in particular a whole shop is skipped in any ante where no condition
// accepts shop cards (#1). Mirrors MatchState.shopCanMatch on the host.
#define SHOP_WANTED(ante_, slot_, out_)                                            \
    do {                                                                           \
        int _w = 0;                                                                \
        for (int _i = 0; _i < NUM_CONDS; _i++) {                                   \
            if (CNT(_i) >= condCount[_i]) continue;                                \
            if (!(condSources[_i] & SB_SHOP)) continue;                            \
            if ((ante_) < condAnteMin[_i] || (ante_) > condAnteMax[_i]) continue;  \
            if ((slot_) > condSlotMax[_i]) continue;                               \
            _w = 1; break;                                                         \
        }                                                                          \
        (out_) = _w;                                                               \
    } while (0)

// The slot-level requirement kill, valid only inside the shop loop.
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

// The decisive prune, for the conditions in this pass's mask. No cutoff, no score
// arithmetic: once a required condition's last allowed ante is behind us and it is still
// short, the seed cannot ever qualify.
#define CLOSE_ANTE(ante_)                                                          \
    do {                                                                           \
        for (int _i = 0; _i < NUM_CONDS; _i++) {                                   \
            if (!((passMask >> _i) & 1u)) continue;                                \
            if (!condRequired[_i]) continue;                                       \
            if (CNT(_i) >= condCount[_i]) continue;                                \
            if ((ante_) + 1 > condAnteMax[_i]) { m.dead = 1; break; }              \
        }                                                                          \
    } while (0)

#if WANT_SOUL_JOKERS
// The Joker4 stream carries no source or ante, so it is one queue for the whole run.
// Showman is assumed never held for Souls: a legendary already taken is rerolled on
// "Joker4_resample2", "_resample3", ... A Soul once all five are held has nothing left to
// roll, so the seed is punted to the host, which lists it at the end of the run. The
// edition is rolled on its own "sou" stream.
#define SOUL_FOUND(ante_)                                                          \
    do {                                                                           \
        soulCount++;                                                               \
        if (passFlags & ST_SOUL_JOKERS) {                                          \
            int _li = rnd_index(RND(F_JOKER4, 0, 0, 0), POOL_N_LEGENDARY);         \
            if (legendaryTaken == (1u << POOL_N_LEGENDARY) - 1u) { g.overflow = 1; } \
            int _ln = 0;                                                           \
            while (!g.overflow && ((legendaryTaken >> _li) & 1u)) {                \
                _ln++;                                                             \
                if (_ln >= MAX_RESAMPLE) { g.overflow = 1; break; }                \
                _li = rnd_index(RND(F_JOKER4, 0, 0, _ln), POOL_N_LEGENDARY);       \
            }                                                                      \
            if (!g.overflow) {                                                     \
                legendaryTaken |= (1u << _li);                                     \
                int _sed = 0;                                                      \
                if (WANT_EDITIONS && (passFlags & ST_EDITIONS)) {                  \
                    _sed = poll_edition(RND(F_EDITION, SRC_SOU, (ante_), 0), edRate, 1.0, 0); \
                }                                                                  \
                OFFER(ITEM_CODE(R_LEGENDARY, _li), _sed, (ante_), soulCount, SB_SOUL); \
            }                                                                      \
        }                                                                          \
    } while (0)
#else
#define SOUL_FOUND(ante_) do { } while (0)
#endif

// One soulable Spectral card in a pack slot: a Spectral pack's own card (SRC_SPE), or one
// Omen Globe put into an Arcana pack (SRC_AR2). The two substitution rolls come first
// (Black Hole wins when both hit); only if neither fires, and spectrals are wanted, is the
// card itself drawn. The Soul and Black Hole sit in the spectral pool but are always
// unavailable there, so landing on one ("RETRY") rerolls -- the one resample left.
#define SPECTRAL_SLOT(ante_, src_, slot_)                                          \
    do {                                                                           \
        int _isSoul = 0, _isBH = 0;                                                \
        if (RND(F_SOUL_SPECTRAL, 0, (ante_), 0) > 0.997) _isSoul = 1;              \
        if (RND(F_SOUL_SPECTRAL, 0, (ante_), 0) > 0.997) { _isSoul = 0; _isBH = 1; } \
        if (_isSoul) { OFFER(CODE_SOUL, 0, (ante_), (slot_), SB_PACK); SOUL_FOUND(ante_); } \
        else if (_isBH) { OFFER(CODE_BLACK_HOLE, 0, (ante_), (slot_), SB_PACK); }  \
        else if (WANT_SPECTRALS && (passFlags & ST_SPECTRALS)) {                   \
            int _idx = rnd_index(RND(F_SPECTRAL, (src_), (ante_), 0), POOL_N_SPECTRALS); \
            int _n = 0;                                                            \
            while ((SPECTRAL_RETRY_MASK >> _idx) & 1UL) {                          \
                _n++;                                                              \
                if (_n >= MAX_RESAMPLE) { g.overflow = 1; break; }                 \
                _idx = rnd_index(RND(F_SPECTRAL, (src_), (ante_), _n), POOL_N_SPECTRALS); \
            }                                                                      \
            if (!g.overflow) OFFER(SPECTRAL_BASE | _idx, 0, (ante_), (slot_), SB_PACK); \
        }                                                                          \
    } while (0)

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
    __constant const int    *anteEnd,        // one past the last stream of each ante
    __global double         *state,
    __global int            *hitIndex,         // seed offsets within this chunk
    __global double         *hitScore,
    __global int            *hitCount,         // [0] hits, [1] overflows
    __global int            *overflowIndex,
    __global uint           *workCounter,     // zeroed by the host before each launch
    const ulong baseIndex,
    const int chunkSize,
    const int nStreams,                        // memory-resident streams only
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

    // With the cutoff still open the score bound can never prune (scores are >= 0), so the
    // bound arithmetic is skipped entirely until calibration or results raise the bar.
    const int useBound = (cutoff > -HUGE_VAL);

    // Work is pulled from a shared counter, one atomic per work GROUP. See the history in
    // the previous revision: per-item grabs serialise on multi-die parts.
    //
    // Each lane takes every lsz'th seed of the group's grab. The grab is at least one seed
    // per lane, so a --local-size above 512 does not leave lanes idle.
    const uint lid = get_local_id(0);
    const uint lsz = get_local_size(0);
    const uint grabPerGroup = max(512u, lsz);

    __local uint lBase;

#if USE_LOCAL_PREFIX
    __local double prefixPool[WG_SIZE * PREFIX_STRIDE];
    __local double *prefix = prefixPool + lid * PREFIX_STRIDE;
#else
    double prefix[PREFIX_STRIDE];
#endif

    for (;;) {
        barrier(CLK_LOCAL_MEM_FENCE);
        if (lid == 0) lBase = atomic_add(workCounter, grabPerGroup);
        barrier(CLK_LOCAL_MEM_FENCE);

        const uint grabbed = lBase;
        if (grabbed >= (uint)chunkSize) break;
        const uint grabEnd = min(grabbed + grabPerGroup, (uint)chunkSize);

    for (uint seedOff = grabbed + lid; seedOff < grabEnd; seedOff += lsz) {

        g.seedLen = seed_for_index(baseIndex + (ulong)seedOff, &g.seedLo, &g.seedHi);

        g.havePrefix = 0u;
        g.hashedSeed = seed_prefix(&g, 0);   // identical to pseudohash(seed)


        Match m;
        ulong voucherActive;
#if WANT_SOUL_JOKERS
        uint legendaryTaken;   // legendaries handed out so far; later Souls reroll these
        int soulCount;
#endif
        int generatedFirstPack;
        int abandoned;
        int killed = 0;
#if WANT_BOSSES
        uint usedNormal, usedFinisher;   // bosses already seen; the pool refills when empty
#endif

        // Passes 0..NUM_STAGES-1 are prefilter stages; pass NUM_STAGES is the full scan.
        for (int pass = 0; pass <= NUM_STAGES; pass++) {
            const int isFull = (pass == NUM_STAGES);
            int passFlags, lastAnte;
            uint passMask;
#if NUM_STAGES > 0
            if (!isFull) {
                passFlags = STAGE_FLAGS[pass];
                lastAnte = STAGE_MAX_ANTE[pass];
                passMask = STAGE_MASK[pass];
            } else
#endif
            {
                passFlags = FULL_FLAGS;
                lastAnte = MAX_SEARCH_ANTE;
                passMask = ALL_MASK;
            }

            // Fresh generator state for every pass. Streams with no ante of their own (the
            // Soul's legendary queue) are cleared here; each ante's slice as it starts.
            for (int i = anteEnd[MAX_SEARCH_ANTE]; i < nStreams; i++) state[(size_t)i * gsize + gid] = NAN;
            g.overflow = 0;
            m.found = 0UL;
            m.unmet = popcount(passMask);
            m.dead = 0;
            m.total = 0.0;
            voucherActive = 0UL;
#if WANT_SOUL_JOKERS
            legendaryTaken = 0u;
            soulCount = 0;
#endif
            generatedFirstPack = 0;
            abandoned = 0;
#if WANT_BOSSES
            usedNormal = 0u;
            usedFinisher = 0u;
#endif

            for (int ante = 1; ante <= lastAnte && !abandoned && !g.overflow; ante++) {

                for (int i = anteEnd[ante - 1]; i < anteEnd[ante]; i++) {
                    state[(size_t)i * gsize + gid] = NAN;
                }

                // ---- boss blind (full pass only). One run-long stream; the choice is among
                // bosses not yet seen and allowed this early. Mirrors SeedAnalyzer.nextBoss. ----
#if WANT_BOSSES
                if (isFull) {
                    const int fin = (ante % 8) == 0;
                    const int n = fin ? NUM_FINISHER_BOSSES : NUM_NORMAL_BOSSES;
                    uint used = fin ? usedFinisher : usedNormal;
                    int avail = 0;
                    for (int i = 0; i < n; i++) {
                        if (!((used >> i) & 1u) && (fin || NORMAL_BOSS_GATE[i] <= ante)) avail++;
                    }
                    if (avail == 0) {
                        used = 0u;
                        for (int i = 0; i < n; i++) if (fin || NORMAL_BOSS_GATE[i] <= ante) avail++;
                    }
                    int k = rnd_index(RND(F_BOSS, 0, 0, 0), avail);
                    int pick = 0;
                    for (int i = 0; i < n; i++) {
                        if (((used >> i) & 1u) || !(fin || NORMAL_BOSS_GATE[i] <= ante)) continue;
                        if (k == 0) { pick = i; break; }
                        k--;
                    }
                    used |= (1u << pick);
                    if (fin) usedFinisher = used; else usedNormal = used;
                    const int code = fin ? FINISHER_BOSS_CODE[pick] : NORMAL_BOSS_CODE[pick];
                    OFFER(BOSS_BASE | code, 0, ante, 1, SB_BOSS);
                    if (g.overflow) break;
                }
#endif

                // Rates as they stand before this ante's voucher: the ante's first shop deals
                // its opening cards (the first entryShop slots) before the voucher can be
                // bought. Everything after, packs included, uses the rates with it.
                double pRate0 = 20.0;
                double pRate1 = ((voucherActive >> V_TAROT_TYCOON) & 1UL) ? 32.0 : (((voucherActive >> V_TAROT_MERCHANT) & 1UL) ? 9.6 : 4.0);
                double pRate2 = ((voucherActive >> V_PLANET_TYCOON) & 1UL) ? 32.0 : (((voucherActive >> V_PLANET_MERCHANT) & 1UL) ? 9.6 : 4.0);
                double pRate3 = ((voucherActive >> V_MAGIC_TRICK) & 1UL) ? 4.0 : 0.0;
                const double pEdRate = ((voucherActive >> V_GLOW_UP) & 1UL) ? 4.0 : (((voucherActive >> V_HONE) & 1UL) ? 2.0 : 1.0);
                const int entryShop = 2 + (int)((voucherActive >> V_OVERSTOCK) & 1UL) + (int)((voucherActive >> V_OVERSTOCK_PLUS) & 1UL);

                // ---- voucher. Always in the full pass (it sets the shop rates); in a stage
                // only when that stage's packs depend on it (Omen Globe, Telescope, Hone). ----
                if (isFull || (passFlags & ST_VOUCHERS)) {
                    int vIdx;
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
#if OFFER_VOUCHERS
                    // Offered whether or not it is one the player skips: it still shows up.
                    if (isFull) OFFER(VOUCHER_BASE | vIdx, 0, ante, 1, SB_VOUCHER);
#endif
                }

                // ---- skip tags (full pass only): two per ante on one stream. In ante 1 a
                // tag that cannot appear yet is rerolled on Tag1_resampleN. ----
#if WANT_TAGS
                if (isFull) {
                    for (int w = 0; w < 2 && !g.overflow; w++) {
                        int r = 0;
                        int tIdx = rnd_index(RND(F_TAG, 0, ante, 0), NUM_TAGS);
                        while (ante < 2 && ((TAG_GATE_MASK >> tIdx) & 1u)) {
                            r++;
                            if (r >= MAX_RESAMPLE) { g.overflow = 1; break; }
                            tIdx = rnd_index(RND(F_TAG, 0, ante, r), NUM_TAGS);
                        }
                        if (!g.overflow) OFFER(TAG_BASE | tIdx, 0, ante, w + 1, SB_TAG);
                    }
                    if (g.overflow) break;
                }
#endif

                const double rate0 = 20.0;
                const double rate1 = ((voucherActive >> V_TAROT_TYCOON) & 1UL) ? 32.0 : (((voucherActive >> V_TAROT_MERCHANT) & 1UL) ? 9.6 : 4.0);
                const double rate2 = ((voucherActive >> V_PLANET_TYCOON) & 1UL) ? 32.0 : (((voucherActive >> V_PLANET_MERCHANT) & 1UL) ? 9.6 : 4.0);
                const double rate3 = ((voucherActive >> V_MAGIC_TRICK) & 1UL) ? 4.0 : 0.0;
                const double edRate = ((voucherActive >> V_GLOW_UP) & 1UL) ? 4.0 : (((voucherActive >> V_HONE) & 1UL) ? 2.0 : 1.0);
                const int omenGlobe = (int)((voucherActive >> V_OMEN_GLOBE) & 1UL);
                const int telescope = (int)((voucherActive >> V_TELESCOPE) & 1UL);

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
                        // No duplicate prevention (the player may hold Showman).
                        if (passFlags & ST_JOKERS) {
                        for (int c = 0; c < size && !g.overflow; c++) {
                            const double rv = RND(F_RARITY, SRC_BUF, ante, 0);
                            const int rarity = (rv > 0.95) ? R_RARE : ((rv > 0.7) ? R_UNCOMMON : R_COMMON);
                            const int poolN = (rarity == R_RARE) ? POOL_N_RARE
                                            : ((rarity == R_UNCOMMON) ? POOL_N_UNCOMMON : POOL_N_COMMON);
                            const int fam = (rarity == R_RARE) ? F_JOKER3
                                          : ((rarity == R_UNCOMMON) ? F_JOKER2 : F_JOKER1);
                            const int code = ITEM_CODE(rarity, rnd_index(RND(fam, SRC_BUF, ante, 0), poolN));

                            int edition = 0;
#if WANT_EDITIONS
                            if (passFlags & ST_EDITIONS) {
                                edition = poll_edition(RND(F_EDITION, SRC_BUF, ante, 0), edRate, 1.0, 0);
                            }
#endif
                            jokerSlot++;
                            OFFER(code, edition, ante, jokerSlot, SB_PACK);
                        }
                        }
#endif
                    }
                    else if (family == PACK_ARCANA) {
#if WANT_TAROTS || WANT_SOULS || WANT_SPECTRALS
                        if (passFlags & (ST_TAROTS | ST_SOULS | ST_SPECTRALS)) {
                        for (int c = 0; c < size && !g.overflow; c++) {
                            // Omen Globe: above 0.8 on its run-long stream, the slot is a
                            // soulable Spectral card instead of a Tarot.
                            if (omenGlobe && RND(F_OMEN_GLOBE, 0, 0, 0) > 0.8) {
                                SPECTRAL_SLOT(ante, SRC_AR2, c + 1);
                                continue;
                            }
#if WANT_TAROTS
                            // The soul roll stays mandatory when tarots are wanted: a slot that
                            // turns into a Soul draws no tarot, so skipping it would desync
                            // the tarot stream.
                            if (passFlags & ST_TAROTS) {
                                if (RND(F_SOUL_TAROT, 0, ante, 0) > 0.997) {
                                    OFFER(CODE_SOUL, 0, ante, c + 1, SB_PACK);
                                    SOUL_FOUND(ante);
                                } else {
                                    const int tIdx = rnd_index(RND(F_TAROT, SRC_AR1, ante, 0), POOL_N_TAROTS);
                                    OFFER(TAROT_BASE | tIdx, 0, ante, c + 1, SB_PACK);
                                }
                                continue;
                            }
#endif
                            if (passFlags & ST_SOULS) {
                                if (RND(F_SOUL_TAROT, 0, ante, 0) > 0.997) {
                                    OFFER(CODE_SOUL, 0, ante, c + 1, SB_PACK);
                                    SOUL_FOUND(ante);
                                }
                            }
                        }
                        }
#endif
                    }
#if WANT_SOULS || WANT_SPECTRALS || WANT_PLANETS
                    else if (family == PACK_CELESTIAL) {
#if WANT_PLANETS
                        // Each card rolls for Black Hole first; only if that misses is a
                        // planet drawn. With Telescope the first card is the planet for your
                        // most-played hand: forced, it draws nothing and matches nothing.
                        if (passFlags & ST_PLANETS) {
                            for (int c = telescope ? 1 : 0; c < size && !g.overflow; c++) {
                                if (RND(F_SOUL_PLANET, 0, ante, 0) > 0.997) {
                                    OFFER(CODE_BLACK_HOLE, 0, ante, c + 1, SB_PACK);
                                } else {
                                    const int pIdx = rnd_index(RND(F_PLANET, SRC_PL1, ante, 0), POOL_N_PLANETS);
                                    OFFER(PLANET_BASE | pIdx, 0, ante, c + 1, SB_PACK);
                                }
                            }
                        } else
#endif
                        {
#if WANT_SOULS
                        // Celestial packs can only substitute Black Hole, never a Soul. With
                        // Telescope the first card is a forced planet and rolls nothing.
                        if (passFlags & ST_SOULS) {
                            for (int c = telescope ? 1 : 0; c < size && !g.overflow; c++) {
                                if (RND(F_SOUL_PLANET, 0, ante, 0) > 0.997) {
                                    OFFER(CODE_BLACK_HOLE, 0, ante, c + 1, SB_PACK);
                                }
                            }
                        }
#endif
                        }
                    }
                    else if (family == PACK_SPECTRAL) {
                        if (passFlags & (ST_SOULS | ST_SPECTRALS)) {
                            for (int c = 0; c < size && !g.overflow; c++) {
                                SPECTRAL_SLOT(ante, SRC_SPE, c + 1);
                            }
                        }
                    }
#endif
                }

                if (g.overflow) break;

                // ---- shop (full pass only) ----
                //
                // Generated only up to the last slot any unmet condition could still use,
                // and not at all in an ante where nothing accepts shop cards (#1). Its
                // streams live in registers for the length of the loop (#3).
                if (isFull) {
                    double sCdt = NAN, sRar = NAN, sJ1 = NAN, sJ2 = NAN, sJ3 = NAN, sEdi = NAN, sTar = NAN, sPla = NAN;
                    __constant const int *scid = SHOP_CID + ante * NUM_SHOP_STREAMS;

                    for (int slot = 1; slot <= SHOP_ITEMS; slot++) {
                        if (m.unmet == 0) break;

                        {
                            int wanted;
                            SHOP_WANTED(ante, slot, wanted);
                            if (!wanted) break;
                        }

                        CLOSE_SLOT(ante, slot);
                        if (m.dead) break;

                        if (useBound) {
                            double bound;
                            UPPER_BOUND(ante, slot, bound);
                            if (bound <= cutoff) { abandoned = 1; break; }
                        }

                        // The ante's first shop deals its opening cards before its voucher
                        // can be bought, so those slots use the rates from before it.
                        const int early = slot <= entryShop;
                        const double r0 = early ? pRate0 : rate0;
                        const double r1 = early ? pRate1 : rate1;
                        const double r2 = early ? pRate2 : rate2;
                        const double r3 = early ? pRate3 : rate3;
                        const double slotEdRate = early ? pEdRate : edRate;

                        // The game's own test, taking the types in order: polled is at or
                        // below the running total of rates.
                        const double c0 = r0, c1 = c0 + r1, c2 = c1 + r2, c3 = c2 + r3;
                        const double polled = RNDR(scid[SS_CDT], &sCdt) * (r0 + r1 + r2 + r3 + 0.0);
                        const int type = (polled <= c0) ? 0 : ((polled <= c1) ? 1 : ((polled <= c2) ? 2 : ((polled <= c3) ? 3 : 4)));

                        if (type == 0) {
#if WANT_JOKERS
                            const double rv = RNDR(scid[SS_RARITY], &sRar);
                            const int rarity = (rv > 0.95) ? R_RARE : ((rv > 0.7) ? R_UNCOMMON : R_COMMON);
                            const int poolN = (rarity == R_RARE) ? POOL_N_RARE
                                            : ((rarity == R_UNCOMMON) ? POOL_N_UNCOMMON : POOL_N_COMMON);
                            // Pick the rarity's stream by value, draw, and put it back. Selects
                            // rather than a pointer into an array, so all three stay registers.
                            double sj = (rarity == R_RARE) ? sJ3 : ((rarity == R_UNCOMMON) ? sJ2 : sJ1);
                            const int idx = rnd_index(RNDR(scid[SS_JOKER1 + rarity], &sj), poolN);
                            if (rarity == R_RARE) sJ3 = sj;
                            else if (rarity == R_UNCOMMON) sJ2 = sj;
                            else sJ1 = sj;
                            const int code = ITEM_CODE(rarity, idx);

                            int edition = 0;
#if WANT_EDITIONS
                            edition = poll_edition(RNDR(scid[SS_EDITION], &sEdi), slotEdRate, 1.0, 0);
#endif
                            OFFER(code, edition, ante, slot, SB_SHOP);
#endif
                        }
#if WANT_TAROTS
                        else if (type == 1) {
                            // Shop tarots are not soulable, so this is just the choice draw.
                            const int tIdx = rnd_index(RNDR(scid[SS_TAROT], &sTar), POOL_N_TAROTS);
                            OFFER(TAROT_BASE | tIdx, 0, ante, slot, SB_SHOP);
                        }
#endif
#if WANT_PLANETS
                        else if (type == 2) {
                            // Shop planets are not soulable either.
                            const int pIdx = rnd_index(RNDR(scid[SS_PLANET], &sPla), POOL_N_PLANETS);
                            OFFER(PLANET_BASE | pIdx, 0, ante, slot, SB_SHOP);
                        }
#endif
                        // Remaining shop types draw nothing here: the playing card never had
                        // a draw, and the shop spectral rate is a hard 0 on this deck.

                        if (g.overflow) break;
                    }
                    // Keeps the compiler quiet about streams this search never draws.
                    (void)sEdi; (void)sTar; (void)sRar; (void)sJ1; (void)sJ2; (void)sJ3; (void)sPla;
                }

                if (g.overflow) break;
                if (m.dead) break;

                CLOSE_ANTE(ante);
                if (m.dead) break;

                if (m.unmet == 0) break;

                if (isFull && useBound && ante < MAX_SEARCH_ANTE) {
                    double bound;
                    UPPER_BOUND(ante + 1, 1, bound);
                    if (bound <= cutoff) abandoned = 1;
                }
            }

            if (!isFull) {
                // A stage only kills. If it could not decide (overflow), the next pass does.
                if (!g.overflow && m.dead) { killed = 1; break; }
            }
        }

        if (killed) continue;

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
