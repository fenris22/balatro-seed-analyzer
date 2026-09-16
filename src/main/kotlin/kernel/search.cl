// Balatro seed search: generation, matching and scoring on device.
//
// The host prepends a block of #defines (see ClSearch.buildDefines) describing the
// request list, the pools and the Detail mask, so unused branches are compiled out
// rather than branched over.
//
// SCOPE: jokers, editions, vouchers, pack kinds, and Souls (including the legendary
// joker queue). Tarots, planets, spectrals, standard cards, bosses and tags are NOT
// generated here -- a search needing those falls back to the CPU path. That is the same
// set the Detail mask already switches off for a joker search, so the device reproduces
// exactly what the CPU filter pass does, and nothing more.
//
// Anything the device cannot decide -- a resample past MAX_RESAMPLE, an unmapped stream --
// is punted to the host by flagging the seed into the overflow list. Rare enough not to
// matter, too common to ignore.
//
// FP_CONTRACT OFF and no fast-math. See rng.cl.

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
// half; tarots and the two substitution cards get their own tags above that.
#define TAROT_BASE (4 << 16)
#define CODE_SOUL ((5 << 16) | 0)
#define CODE_BLACK_HOLE ((5 << 16) | 1)
#define F_TAROT_SRC_AR1 SRC_AR1

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
// Seeds are derived on device from their index, so nothing is uploaded per seed.
// This is the same enumeration Util.nextSeed walks: all length-1 seeds, then all
// length-2, and so on, over a 34-character alphabet with no 0 and no O.

__constant uchar SEED_ALPHABET[34] = {
    '1','2','3','4','5','6','7','8','9',
    'A','B','C','D','E','F','G','H','I','J','K','L','M','N','P','Q','R','S','T','U','V','W','X','Y','Z'
};

inline int seed_for_index(ulong index, uchar *out) {
    ulong n = index;
    int len = 1;
    ulong block = 34UL;
    while (n >= block) { n -= block; len++; block *= 34UL; }
    for (int i = len - 1; i >= 0; i--) { out[i] = SEED_ALPHABET[n % 34UL]; n /= 34UL; }
    return len;
}

// --- rng context -------------------------------------------------------------

typedef struct {
    __global double *state;      // [compactStream * gsize + gid]
    __global const int *remap;   // cartesian stream id -> compact index, -1 if absent
    int gsize;
    int gid;
    int nStreams;
    uchar seed[16];
    int seedLen;
    double hashedSeed;
    double prefix[MAX_LEN_SLOTS];
    char havePrefix[MAX_LEN_SLOTS];
    int overflow;                // set when the device cannot answer; host redoes the seed
} Rng;

inline double self_hash(const Rng *g) {
    double num = 1.0;
    for (int i = g->seedLen - 1; i >= 0; i--) {
        num = frac_d(PSEUDOHASH_K / num * (double)g->seed[i] * PI_D + PI_D * (double)(i + 1));
    }
    return num;
}

inline double seed_prefix(const Rng *g, int keyLen) {
    double num = 1.0;
    for (int i = g->seedLen - 1; i >= 0; i--) {
        num = frac_d(PSEUDOHASH_K / num * (double)g->seed[i] * PI_D + PI_D * (double)(keyLen + i + 1));
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
        double start;
        if (g->havePrefix[slot]) start = g->prefix[slot];
        else {
            start = seed_prefix(g, keyLen);
            g->prefix[slot] = start;
            g->havePrefix[slot] = 1;
        }
        __constant const uchar *k = keyChars + (size_t)cid * MAX_KEY_LEN;
        double num = start;
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

typedef struct {
    char matched[NUM_SPECS];
    int mAnte[NUM_SPECS];
    int mSlot[NUM_SPECS];
    int mEdition[NUM_SPECS];
    int remaining;
    double total;
} Match;

inline double slot_score(__constant const int *slotTarget,
                         __constant const int *slotPriority, int i, int slot)
{
    const int t = slotTarget[i];
    if (t < 0) return 0.0;
    if (slot <= t) return 10.0 * (double)slotPriority[i];
    return decay_at(slot - t) * (double)slotPriority[i];
}

inline double ante_score(__constant const int *anteTarget,
                         __constant const int *antePriority, int i, int ante)
{
    const int t = anteTarget[i];
    if (t < 0) return 0.0;
    return decay_at((int)abs(ante - t)) * (double)antePriority[i];
}

inline double best_ante_from(__constant const int *anteTarget,
                             __constant const int *antePriority, int i, int ante)
{
    const int t = anteTarget[i];
    if (t < 0) return 0.0;
    const int d = (ante > t) ? (ante - t) : 0;
    return decay_at(d) * (double)antePriority[i];
}


// --- matching helpers (macros: they touch the kernel's own locals) -------------

// A candidate that fails one request's edition gate falls through to the next request,
// so a plain joker can still satisfy a plain request sitting behind an edition-gated one.
#define OFFER(code_, edition_, ante_, slot_)                                       \
    do {                                                                           \
        const int _code = (code_); const int _ed = (edition_);                     \
        for (int _i = 0; _i < NUM_SPECS; _i++) {                                   \
            if (m.matched[_i]) continue;                                           \
            if (specItem[_i] != _code) continue;                                   \
            const int _gate = specEdition[_i];                                     \
            if (_gate >= 0 && _gate != _ed) continue;                              \
            m.matched[_i] = 1;                                                     \
            m.mAnte[_i] = (ante_);                                                 \
            m.mSlot[_i] = (slot_);                                                 \
            m.mEdition[_i] = _ed;                                                  \
            m.total += slot_score(specSlotTarget, specSlotPriority, _i, (slot_))   \
                     + ante_score(specAnteTarget, specAntePriority, _i, (ante_))   \
                     + specEditionScore[_i];                                       \
            m.remaining--;                                                         \
            break;                                                                 \
        }                                                                          \
    } while (0)

#if WANT_SOUL_JOKERS
// The Joker4 stream carries no source or ante, so it is one queue for the whole run.
// The edition is rolled on its own "sou" stream -- see the note in SeedAnalyzer.
#define SOUL_FOUND(ante_)                                                          \
    do {                                                                           \
        soulCount++;                                                               \
        int _li = rnd_index(RND(F_JOKER4, 0, 0, 0), POOL_N_LEGENDARY);             \
        int _ln = 0;                                                               \
        while (legendaryTaken[_li]) {                                              \
            _ln++;                                                                 \
            if (_ln >= MAX_RESAMPLE) { g.overflow = 1; break; }                    \
            _li = rnd_index(RND(F_JOKER4, 0, 0, _ln), POOL_N_LEGENDARY);           \
        }                                                                          \
        if (!g.overflow) {                                                         \
            legendaryTaken[_li] = 1;                                               \
            int _sed = 0;                                                          \
            if (WANT_EDITIONS) {                                                   \
                const double _ev = RND(F_EDITION, SRC_SOU, (ante_), 0);            \
                _sed = (_ev > 0.997) ? 4 : ((_ev > 0.994) ? 3                      \
                     : ((_ev > 0.98) ? 2 : ((_ev > 0.96) ? 1 : 0)));               \
            }                                                                      \
            OFFER(ITEM_CODE(R_LEGENDARY, _li), _sed, (ante_), soulCount);          \
        }                                                                          \
    } while (0)
#else
#define SOUL_FOUND(ante_) do { } while (0)
#endif

// --- the kernel --------------------------------------------------------------

__kernel void search(
    __constant const uchar *keyChars,
    __constant const uchar *keyLens,
    __constant const uchar *keySlots,
    __constant const int   *specItem,        // (rarity<<16)|poolIndex
    __constant const int   *specEdition,     // -1 any, 0 none, 1..4 a specific edition
    __constant const int   *specSlotTarget,  // -1 when unset
    __constant const int   *specSlotPriority,
    __constant const int   *specAnteTarget,  // -1 when unset
    __constant const int   *specAntePriority,
    __constant const double *specEditionScore,
    __constant const int   *packFamily,
    __constant const int   *packSize,
    __constant const double *packCum,
    __constant const uchar *voucherIgnored,
    __global const int     *remap,
    __global double        *state,
    __global int           *hitIndex,        // seed offsets within this chunk
    __global double        *hitScore,
    __global int           *hitCount,        // [0] hits, [1] overflows
    __global int           *overflowIndex,
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
    g.nStreams = nStreams;

    // Each work item walks a strided slice of the chunk, so the state buffer is sized by
    // the launch width rather than by how many seeds the chunk holds.
    for (int seedOff = gid; seedOff < chunkSize; seedOff += gsize) {

        for (int i = 0; i < nStreams; i++) state[(size_t)i * gsize + gid] = NAN;
        for (int i = 0; i < MAX_LEN_SLOTS; i++) g.havePrefix[i] = 0;
        g.overflow = 0;
        g.seedLen = seed_for_index(baseIndex + (ulong)seedOff, g.seed);
        g.hashedSeed = self_hash(&g);

        Match m;
        m.remaining = NUM_SPECS;
        m.total = 0.0;
        for (int i = 0; i < NUM_SPECS; i++) m.matched[i] = 0;

        char voucherActive[NUM_VOUCHERS];
        for (int i = 0; i < NUM_VOUCHERS; i++) voucherActive[i] = 0;

#if WANT_SOUL_JOKERS
        char legendaryTaken[POOL_N_LEGENDARY];
        for (int i = 0; i < POOL_N_LEGENDARY; i++) legendaryTaken[i] = 0;
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
                while (((vIdx & 1) && !voucherActive[vIdx - 1]) || voucherActive[vIdx]) {
                    resample++;
                    if (resample >= MAX_RESAMPLE) { g.overflow = 1; break; }
                    vIdx = rnd_index(RND(F_VOUCHER, 0, ante, resample), NUM_VOUCHERS);
                }
                if (g.overflow) break;
                if (!voucherIgnored[vIdx]) {
                    voucherActive[vIdx] = 1;
                    if (vIdx & 1) voucherActive[vIdx - 1] = 1;
                }
            }

            // ---- shop rates ----
            double rate0 = 20.0;
            double rate1 = voucherActive[V_TAROT_TYCOON] ? 32.0 : (voucherActive[V_TAROT_MERCHANT] ? 9.6 : 4.0);
            double rate2 = voucherActive[V_PLANET_TYCOON] ? 32.0 : (voucherActive[V_PLANET_MERCHANT] ? 9.6 : 4.0);
            double rate3 = voucherActive[V_MAGIC_TRICK] ? 4.0 : 0.0;
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
                        // rarity
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
                        OFFER(code, edition, ante, jokerSlot);
                    }
#endif
                }
                else if (family == PACK_ARCANA) {
#if WANT_TAROTS
                    // Full Arcana generation. The soul roll stays mandatory here even
                    // though it is on its own stream: a slot that turns into a Soul does
                    // not draw a tarot, so skipping it would desync the tarot stream.
                    int tex[8];
                    int nTex = 0;
                    for (int c = 0; c < size && !g.overflow; c++) {
                        if (RND(F_SOUL_TAROT, 0, ante, 0) > 0.997) {
                            OFFER(CODE_SOUL, 0, ante, c + 1);
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
                        OFFER(code, 0, ante, c + 1);
                    }
#elif WANT_SOULS
                    // Souls-only: one roll per slot on the substitution stream, and the
                    // tarot choice is never made.
                    for (int c = 0; c < size && !g.overflow; c++) {
                        if (RND(F_SOUL_TAROT, 0, ante, 0) > 0.997) {
                            OFFER(CODE_SOUL, 0, ante, c + 1);
                            SOUL_FOUND(ante);
                        }
                    }
#endif
                }
#if WANT_SOULS
                else if (family == PACK_CELESTIAL) {
                    // Celestial packs can only substitute Black Hole, never a Soul.
                    for (int c = 0; c < size && !g.overflow; c++) {
                        if (RND(F_SOUL_PLANET, 0, ante, 0) > 0.997) {
                            OFFER(CODE_BLACK_HOLE, 0, ante, c + 1);
                        }
                    }
                }
                else if (family == PACK_SPECTRAL) {
                    for (int c = 0; c < size && !g.overflow; c++) {
                        int isSoul = 0;
                        int isBlackHole = 0;
                        if (RND(F_SOUL_SPECTRAL, 0, ante, 0) > 0.997) isSoul = 1;
                        if (RND(F_SOUL_SPECTRAL, 0, ante, 0) > 0.997) { isSoul = 0; isBlackHole = 1; }
                        if (isSoul) {
                            OFFER(CODE_SOUL, 0, ante, c + 1);
                            SOUL_FOUND(ante);
                        } else if (isBlackHole) {
                            OFFER(CODE_BLACK_HOLE, 0, ante, c + 1);
                        }
                    }
                }
#endif
            }

            if (g.overflow) break;

            // ---- shop ----
            for (int slot = 1; slot <= SHOP_ITEMS; slot++) {
                if (m.remaining == 0) break;
                {
                    double bound = m.total;
                    for (int i = 0; i < NUM_SPECS; i++) {
                        if (m.matched[i]) continue;
                        const double here = slot_score(specSlotTarget, specSlotPriority, i, slot)
                                          + ante_score(specAnteTarget, specAntePriority, i, ante);
                        double later = 0.0;
                        if (ante < MAX_SEARCH_ANTE) {
                            const int st = specSlotTarget[i];
                            const double maxSlot = (st < 0) ? 0.0 : 10.0 * (double)specSlotPriority[i];
                            later = maxSlot + best_ante_from(specAnteTarget, specAntePriority, i, ante + 1);
                        }
                        bound += fmax(here, later) + specEditionScore[i];
                    }
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
                    OFFER(code, edition, ante, slot);
#endif
                }
#if WANT_TAROTS
                else if (type == 1) {
                    // Shop tarots are not soulable, so this is just the choice draw.
                    const int tIdx = rnd_index(RND(F_TAROT, SRC_SHO, ante, 0), POOL_N_TAROTS);
                    OFFER(TAROT_BASE | tIdx, 0, ante, slot);
                }
#endif
                // Remaining shop types are planet / spectral (not generated, exactly as the
                // CPU filter pass leaves them when Detail has them off) and the playing
                // card, which draws nothing.

                if (g.overflow) break;
            }

            if (g.overflow) break;
            if (m.remaining == 0) break;

            // Same check the CPU makes between antes: upperBound(ante + 1, slot 1).
            if (ante < MAX_SEARCH_ANTE) {
                const int next = ante + 1;
                double bound = m.total;
                for (int i = 0; i < NUM_SPECS; i++) {
                    if (m.matched[i]) continue;
                    const double here = slot_score(specSlotTarget, specSlotPriority, i, 1)
                                      + ante_score(specAnteTarget, specAntePriority, i, next);
                    double later = 0.0;
                    if (next < MAX_SEARCH_ANTE) {
                        const int st = specSlotTarget[i];
                        const double maxSlot = (st < 0) ? 0.0 : 10.0 * (double)specSlotPriority[i];
                        later = maxSlot + best_ante_from(specAnteTarget, specAntePriority, i, next + 1);
                    }
                    bound += fmax(here, later) + specEditionScore[i];
                }
                if (bound <= cutoff) abandoned = 1;
            }
        }

        if (g.overflow) {
            const int slot = atomic_inc(&hitCount[1]);
            if (slot < maxHits) overflowIndex[slot] = seedOff;
            continue;
        }

        if (m.remaining == 0 && m.total > cutoff) {
            const int slot = atomic_inc(&hitCount[0]);
            if (slot < maxHits) {
                hitIndex[slot] = seedOff;
                hitScore[slot] = m.total;
            }
        }
    }
}
