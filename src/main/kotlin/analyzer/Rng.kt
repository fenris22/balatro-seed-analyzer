package analyzer

import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap
import kotlin.math.PI
import kotlin.math.floor

private const val E = 2.7182818284590452354
private const val PSEUDOHASH_K = 1.1239285023

fun frac(x: Double): Double = x - floor(x)

fun round13(x: Double): Double {
    val power = 1e13
    return floor(x * power + 0.5) / power
}

/** Original single-string form -- only used once per seed (hashedSeed), not perf-critical. */
fun pseudohash(s: String): Double {
    var num = 1.0
    for (i in s.length - 1 downTo 0) {
        val c = s[i].code.toDouble()
        num = frac(PSEUDOHASH_K / num * c * PI + PI * (i + 1))
    }
    return num
}

/**
 * Reference two-argument form, kept for tests. The hot path uses RngCache's split
 * version instead (see seedPrefix / hashKey below), which is arithmetically identical.
 */
fun pseudohash(key: String, seed: String): Double {
    var num = 1.0
    for (i in seed.length - 1 downTo 0) {
        num = frac(PSEUDOHASH_K / num * seed[i].code.toDouble() * PI + PI * (key.length + i + 1))
    }
    for (i in key.length - 1 downTo 0) {
        num = frac(PSEUDOHASH_K / num * key[i].code.toDouble() * PI + PI * (i + 1))
    }
    return num
}

// ---------------------------------------------------------------------------
// Item 1: allocation-free RNG draw
// ---------------------------------------------------------------------------

/**
 * The whole of `LuaRandom.seeded(d).nextDouble()` collapsed into one function over
 * four local Longs.
 *
 * Two observations make this safe. First, every call site took exactly one draw
 * (random -> nextDouble, randInt -> nextULong -> nextDouble), so the 10 warmup
 * rounds plus the one live round are just 11 rounds of the same mixer and the
 * object never needed to outlive the call. Second, nothing here escapes, so the
 * LongArray(4) and the LuaRandom wrapper were pure garbage -- roughly 6-7 billion
 * of each over a 10M-seed run, which escape analysis may or may not have removed.
 *
 * Bit-identical to the original: `shr` on ULong is a logical shift, which is
 * `ushr` on Long; the masks (-1L shl k) are folded to constants; the unsigned
 * `u < m` comparison is preserved via compareUnsigned even though d is always
 * large enough that it can never fire.
 *
 * This maps 1:1 onto an OpenCL device function -- four private `ulong`s, no memory
 * traffic at all.
 */
fun luaDraw(d0: Double): Double {
    var d = d0

    d = d * PI; d = d + E
    var u = d.toRawBits()
    val s0 = if (java.lang.Long.compareUnsigned(u, 2L) < 0) u + 2L else u

    d = d * PI; d = d + E
    u = d.toRawBits()
    val s1 = if (java.lang.Long.compareUnsigned(u, 64L) < 0) u + 64L else u

    d = d * PI; d = d + E
    u = d.toRawBits()
    val s2 = if (java.lang.Long.compareUnsigned(u, 512L) < 0) u + 512L else u

    d = d * PI; d = d + E
    u = d.toRawBits()
    val s3 = if (java.lang.Long.compareUnsigned(u, 131072L) < 0) u + 131072L else u

    var z0 = s0; var z1 = s1; var z2 = s2; var z3 = s3
    var r = 0L

    // 10 warmup rounds + 1 live round; only the last result is consumed.
    var n = 0
    while (n < 11) {
        var z = z0
        z = (((z shl 31) xor z) ushr 45) xor ((z and -2L) shl 18)
        z0 = z
        r = z

        z = z1
        z = (((z shl 19) xor z) ushr 30) xor ((z and -64L) shl 28)
        z1 = z
        r = r xor z

        z = z2
        z = (((z shl 24) xor z) ushr 48) xor ((z and -512L) shl 7)
        z2 = z
        r = r xor z

        z = z3
        z = (((z shl 21) xor z) ushr 39) xor ((z and -131072L) shl 8)
        z3 = z
        r = r xor z

        n++
    }

    val bits = (r and 4503599627370495L) or 4607182418800017408L
    return Double.fromBits(bits) - 1.0
}

// ---------------------------------------------------------------------------
// Item 4 (part 1): dense stream identifiers
// ---------------------------------------------------------------------------

/**
 * Every RNG draw belongs to a *stream* identified by (family, source, ante, resample),
 * and a stream is just a double that gets advanced on each use. Packing that tuple into
 * a dense integer replaces the Long2Double hash map with a flat array index, and gives
 * the GPU port its memory layout for free: state[streamId] per work-item.
 *
 * Layout: family(5b) | source(3b) | ante(4b) | resample(4b), so ante <= 15 and
 * resample <= 15 take the fast path. Resample counts above that are handled by a
 * small overflow map -- the resample loops are unbounded in principle (a voucher can
 * keep rolling locked), and at 0.5^17 per draw it happens a few hundred times in a
 * 10M-seed run, which is rare enough not to matter and too common to crash on.
 */
object RngKeys {
    const val BOSS = 0; const val VOUCHER = 1; const val TAG = 2
    const val RARITY = 3; const val EDITION = 4
    const val JOKER1 = 5; const val JOKER2 = 6; const val JOKER3 = 7; const val JOKER4 = 8
    const val SOUL_TAROT = 9; const val TAROT = 10
    const val SOUL_PLANET = 11; const val PLANET = 12
    const val SOUL_SPECTRAL = 13; const val SPECTRAL = 14
    const val CDT = 15; const val STDSET = 16; const val ENHANCED = 17
    const val STD_EDITION = 18; const val STDSEAL = 19; const val STDSEALTYPE = 20
    const val FRONTSTA = 21; const val SHOP_PACK = 22
    const val FAMILY_COUNT = 23

    const val SRC_NONE = 0; const val SRC_SHO = 1; const val SRC_BUF = 2
    const val SRC_AR1 = 3; const val SRC_PL1 = 4; const val SRC_SPE = 5

    /**
     * The Soul's own source. Balatro creates the legendary joker with key_append "sou",
     * so its edition rolls on "edisou<ante>" -- a stream nothing else touches, which is
     * why adding it cannot disturb any existing result.
     */
    const val SRC_SOU = 6
    const val SOURCE_COUNT = 7

    const val MAX_ANTE = 15
    const val MAX_RESAMPLE = 16

    /** Total addressable streams; the per-worker state array is this long. */
    const val STREAM_COUNT = FAMILY_COUNT * SOURCE_COUNT * (MAX_ANTE + 1) * MAX_RESAMPLE

    val SOURCE_NAMES = arrayOf("", "sho", "buf", "ar1", "pl1", "spe", "sou")

    fun sourceId(source: String): Int = when (source) {
        "sho" -> SRC_SHO; "buf" -> SRC_BUF; "ar1" -> SRC_AR1
        "pl1" -> SRC_PL1; "spe" -> SRC_SPE; "sou" -> SRC_SOU
        else -> SRC_NONE
    }

    fun streamId(family: Int, source: Int, ante: Int, resample: Int): Int =
        ((family * SOURCE_COUNT + source) shl 8) or (ante shl 4) or resample

    fun longKey(family: Int, source: Int, ante: Int, resample: Int): Long =
        (family.toLong() shl 48) or (source.toLong() shl 32) or (ante.toLong() shl 16) or resample.toLong()

    /**
     * Reconstructs the Lua key string for a stream. Previously these were built by a
     * lambda at each call site and allocated on every cache miss (100+ Strings per
     * seed); now each one is built at most once per JVM and cached in KEY_TABLE.
     *
     * This is also the function the OpenCL host uses to build the key-byte table it
     * uploads to constant memory, so the device never formats a string.
     */
    fun keyFor(family: Int, source: Int, ante: Int, resample: Int): String {
        val s = SOURCE_NAMES[source]
        val rs = if (resample == 0) "" else "_resample${resample + 1}"
        return when (family) {
            BOSS -> "boss"
            VOUCHER -> "Voucher$ante$rs"
            TAG -> "Tag$ante$rs"
            RARITY -> "rarity$ante$s"
            EDITION -> "edi$s$ante"
            JOKER1 -> "Joker1$s$ante$rs"
            JOKER2 -> "Joker2$s$ante$rs"
            JOKER3 -> "Joker3$s$ante$rs"
            JOKER4 -> "Joker4$rs"
            SOUL_TAROT -> "soul_Tarot$ante"
            TAROT -> "Tarot$s$ante$rs"
            SOUL_PLANET -> "soul_Planet$ante"
            PLANET -> "Planet$s$ante$rs"
            SOUL_SPECTRAL -> "soul_Spectral$ante"
            SPECTRAL -> "Spectral$s$ante$rs"
            CDT -> "cdt$ante"
            STDSET -> "stdset$ante"
            ENHANCED -> "Enhancedsta$ante"
            STD_EDITION -> "standard_edition$ante"
            STDSEAL -> "stdseal$ante"
            STDSEALTYPE -> "stdsealtype$ante"
            FRONTSTA -> "frontsta$ante"
            SHOP_PACK -> "shop_pack$ante"
            else -> throw IllegalArgumentException("unknown family $family")
        }
    }

    /**
     * Lazily populated, shared by all workers. Races are benign: two threads may build
     * the same String, and String's fields are final so either is safely published.
     */
    private val KEY_TABLE = arrayOfNulls<String>(STREAM_COUNT)

    fun key(streamId: Int): String {
        KEY_TABLE[streamId]?.let { return it }
        val resample = streamId and 15
        val ante = (streamId ushr 4) and 15
        val fs = streamId ushr 8
        val built = keyFor(fs / SOURCE_COUNT, fs % SOURCE_COUNT, ante, resample)
        KEY_TABLE[streamId] = built
        return built
    }

    /** Longest key we index a seed-prefix slot for; anything longer falls back. */
    const val MAX_KEY_LEN = 48
}

// ---------------------------------------------------------------------------
// Items 2 + 4: stream state and the split pseudohash
// ---------------------------------------------------------------------------

/**
 * One per worker, reset between seeds rather than reallocated.
 *
 * Two things changed from the hash-map version:
 *
 *  - State lives in a flat DoubleArray indexed by stream id. Only ~400 of the 35k
 *    slots are ever touched for a 3-ante search, so the working set stays L1-resident
 *    despite the array's size, and reset walks a touched-list instead of clearing.
 *
 *  - pseudohash(key, seed) is split. Its first loop walks the seed's characters and
 *    depends on the key only through `key.length`, so it is computed once per
 *    (seed, length) and reused. That removes 8 FP64 divisions from every one of the
 *    100+ cache misses per seed, at the cost of a handful up front.
 */
class RngCache {
    /**
     * The seed as characters rather than a String: at 10M seeds the String and its
     * backing array are 20M allocations that exist only to be read 8 characters at a
     * time. The worker owns one buffer and refills it.
     */
    private var seedChars = CharArray(16)
    private var seedLen = 0

    var hashedSeed: Double = 0.0
        private set

    private val state = DoubleArray(RngKeys.STREAM_COUNT) { Double.NaN }
    private val touched = IntArray(RngKeys.STREAM_COUNT)
    private var touchedCount = 0

    /** Only for resample counts past MAX_RESAMPLE. Almost always empty. */
    private val overflow = Long2DoubleOpenHashMap(8).apply { defaultReturnValue(Double.NaN) }

    /** seedPrefix result per key length, NaN = not yet computed for this seed. */
    private val prefix = DoubleArray(RngKeys.MAX_KEY_LEN + 1) { Double.NaN }

    constructor()
    constructor(seed: String) {
        reset(seed)
    }

    /** Primitive form: [chars] must already be uppercase. */
    fun reset(chars: CharArray, len: Int) {
        for (i in 0 until touchedCount) state[touched[i]] = Double.NaN
        touchedCount = 0
        if (!overflow.isEmpty()) overflow.clear()
        java.util.Arrays.fill(prefix, Double.NaN)
        if (seedChars.size < len) seedChars = CharArray(len)
        if (chars !== seedChars) System.arraycopy(chars, 0, seedChars, 0, len)
        seedLen = len
        hashedSeed = selfHash()
    }

    fun reset(seed: String) {
        val n = seed.length
        if (seedChars.size < n) seedChars = CharArray(n)
        for (i in 0 until n) seedChars[i] = seed[i].uppercaseChar()
        reset(seedChars, n)
    }

    /** pseudohash(seed), over the character buffer. */
    private fun selfHash(): Double {
        var num = 1.0
        for (i in seedLen - 1 downTo 0) {
            num = frac(PSEUDOHASH_K / num * seedChars[i].code.toDouble() * PI + PI * (i + 1))
        }
        return num
    }

    /** First loop of pseudohash: seed characters only, parameterised by key length. */
    private fun seedPrefix(keyLen: Int): Double {
        var num = 1.0
        for (i in seedLen - 1 downTo 0) {
            num = frac(PSEUDOHASH_K / num * seedChars[i].code.toDouble() * PI + PI * (keyLen + i + 1))
        }
        return num
    }

    /** Second loop: key characters, starting from the cached prefix. */
    private fun hashKey(key: String): Double {
        val len = key.length
        var num: Double
        if (len <= RngKeys.MAX_KEY_LEN) {
            num = prefix[len]
            if (num.isNaN()) {
                num = seedPrefix(len)
                prefix[len] = num
            }
        } else {
            num = seedPrefix(len)
        }
        for (i in len - 1 downTo 0) {
            num = frac(PSEUDOHASH_K / num * key[i].code.toDouble() * PI + PI * (i + 1))
        }
        return num
    }

    private fun advance(current: Double): Double =
        round13(frac(current * 1.72431234 + 2.134453429141))

    fun nodeValue(family: Int, source: Int, ante: Int, resample: Int): Double {
        if (resample < RngKeys.MAX_RESAMPLE && ante <= RngKeys.MAX_ANTE) {
            val id = RngKeys.streamId(family, source, ante, resample)
            var s = state[id]
            if (s.isNaN()) {
                s = hashKey(RngKeys.key(id))
                touched[touchedCount++] = id
            }
            val advanced = advance(s)
            state[id] = advanced
            return (advanced + hashedSeed) / 2.0
        }
        val lk = RngKeys.longKey(family, source, ante, resample)
        var s = overflow.get(lk)
        if (s.isNaN()) s = hashKey(RngKeys.keyFor(family, source, ante, resample))
        val advanced = advance(s)
        overflow.put(lk, advanced)
        return (advanced + hashedSeed) / 2.0
    }

    fun random(family: Int, source: Int, ante: Int, resample: Int): Double =
        luaDraw(nodeValue(family, source, ante, resample))

    /** Uniform index in [0, bound). Replaces randInt(1..n) - 1 with no ULong round-trip. */
    fun randIndex(family: Int, source: Int, ante: Int, resample: Int, bound: Int): Int =
        (random(family, source, ante, resample) * bound).toInt()

    fun <T> randChoice(family: Int, source: Int, ante: Int, resample: Int, pool: List<T>): T =
        pool[randIndex(family, source, ante, resample, pool.size)]

    /**
     * Array form. Pools are fixed at startup, and an Array load is a raw aaload where
     * List.get is an interface call that has to stay monomorphic to be cheap. This runs
     * on every single draw, so it is worth not leaving to the JIT.
     */
    fun randChoice(family: Int, source: Int, ante: Int, resample: Int, pool: Array<Item>): Item =
        pool[randIndex(family, source, ante, resample, pool.size)]
}