package analyzer

import kotlin.math.PI

/**
 * Bit-exactness harness.
 *
 * Everything below `Ref` is the original implementation, untouched: string-keyed cache,
 * the two-loop pseudohash, and the allocating LuaRandom with its separate seeded()/
 * nextDouble(). The new fast path has to agree with it to the last bit on every draw,
 * because a single ULP of drift puts you on a different seed's results without any
 * visible error.
 *
 * Run this after any change to Rng.kt, and again once the OpenCL kernel exists -- the
 * same comparison, with the device's output read back, is what tells you the port is
 * faithful rather than merely fast.
 */
object RngVerify {

    // --- reference implementation (original code) --------------------------

    private class RefLuaRandom private constructor(private val state: LongArray, private var out: Long) {
        companion object {
            private fun randint(state: LongArray): Long {
                var r = 0L
                var z = state[0]
                z = (((z shl 31) xor z) ushr 45) xor ((z and (-1L shl 1)) shl 18)
                r = r xor z; state[0] = z
                z = state[1]
                z = (((z shl 19) xor z) ushr 30) xor ((z and (-1L shl 6)) shl 28)
                r = r xor z; state[1] = z
                z = state[2]
                z = (((z shl 24) xor z) ushr 48) xor ((z and (-1L shl 9)) shl 7)
                r = r xor z; state[2] = z
                z = state[3]
                z = (((z shl 21) xor z) ushr 39) xor ((z and (-1L shl 17)) shl 8)
                r = r xor z; state[3] = z
                return r
            }

            fun seeded(d0: Double): RefLuaRandom {
                val state = LongArray(4)
                var r = 0x11090601u
                var d = d0
                for (i in 0 until 4) {
                    val m = (1u shl (r and 255u).toInt()).toULong()
                    r = r shr 8
                    d *= 3.14159265358979323846
                    d += 2.7182818284590452354
                    var u = d.toRawBits().toULong()
                    if (u < m) u += m
                    state[i] = u.toLong()
                }
                var out = 0L
                repeat(10) { out = randint(state) }
                return RefLuaRandom(state, out)
            }
        }

        fun nextDouble(): Double {
            out = randint(state)
            val bits = (out and 4503599627370495L) or 4607182418800017408L
            return Double.fromBits(bits) - 1.0
        }
    }

    private fun refPseudohash(key: String, seed: String): Double {
        var num = 1.0
        for (i in seed.length - 1 downTo 0) {
            num = frac(1.1239285023 / num * seed[i].code.toDouble() * PI + PI * (key.length + i + 1))
        }
        for (i in key.length - 1 downTo 0) {
            num = frac(1.1239285023 / num * key[i].code.toDouble() * PI + PI * (i + 1))
        }
        return num
    }

    private class RefCache(val seed: String) {
        val hashedSeed = pseudohash(seed)
        val nodes = HashMap<String, Double>()

        fun nodeValue(key: String): Double {
            val state = nodes[key] ?: refPseudohash(key, seed)
            val advanced = round13(frac(state * 1.72431234 + 2.134453429141))
            nodes[key] = advanced
            return (advanced + hashedSeed) / 2.0
        }

        fun random(key: String): Double = RefLuaRandom.seeded(nodeValue(key)).nextDouble()
    }

    // --- the comparison ----------------------------------------------------

    /** A realistic access pattern: repeated draws, mixed streams, resamples, reuse. */
    private fun streamPlan(maxAnte: Int): List<IntArray> {
        val plan = ArrayList<IntArray>()
        for (ante in 1..maxAnte) {
            plan.add(intArrayOf(RngKeys.BOSS, 0, 0, 0))
            for (r in 0..3) plan.add(intArrayOf(RngKeys.VOUCHER, 0, ante, r))
            repeat(2) { for (r in 0..2) plan.add(intArrayOf(RngKeys.TAG, 0, ante, r)) }
            repeat(50) { plan.add(intArrayOf(RngKeys.CDT, 0, ante, 0)) }
            for (src in intArrayOf(RngKeys.SRC_SHO, RngKeys.SRC_BUF)) {
                plan.add(intArrayOf(RngKeys.RARITY, src, ante, 0))
                plan.add(intArrayOf(RngKeys.EDITION, src, ante, 0))
                for (fam in intArrayOf(RngKeys.JOKER1, RngKeys.JOKER2, RngKeys.JOKER3)) {
                    for (r in 0..2) plan.add(intArrayOf(fam, src, ante, r))
                }
            }
            for (r in 0..2) plan.add(intArrayOf(RngKeys.JOKER4, 0, 0, r))
            plan.add(intArrayOf(RngKeys.SOUL_TAROT, 0, ante, 0))
            plan.add(intArrayOf(RngKeys.SOUL_SPECTRAL, 0, ante, 0))
            plan.add(intArrayOf(RngKeys.SOUL_SPECTRAL, 0, ante, 0)) // drawn twice, as in nextSpectral
            for (src in intArrayOf(RngKeys.SRC_SHO, RngKeys.SRC_AR1)) {
                for (r in 0..2) plan.add(intArrayOf(RngKeys.TAROT, src, ante, r))
            }
            for (r in 0..2) plan.add(intArrayOf(RngKeys.PLANET, RngKeys.SRC_PL1, ante, r))
            for (r in 0..2) plan.add(intArrayOf(RngKeys.SPECTRAL, RngKeys.SRC_SPE, ante, r))
            for (fam in intArrayOf(
                RngKeys.STDSET, RngKeys.ENHANCED, RngKeys.STD_EDITION,
                RngKeys.STDSEAL, RngKeys.STDSEALTYPE, RngKeys.FRONTSTA, RngKeys.SHOP_PACK
            )) plan.add(intArrayOf(fam, 0, ante, 0))
            // past MAX_RESAMPLE, exercising the overflow path
            plan.add(intArrayOf(RngKeys.VOUCHER, 0, ante, RngKeys.MAX_RESAMPLE + 1))
        }
        return plan
    }

    private val ALPHABET = "123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"

    private fun seedAt(n: Long): String {
        var v = n
        val sb = StringBuilder()
        repeat(8) { sb.append(ALPHABET[(v % ALPHABET.length).toInt()]); v /= ALPHABET.length }
        return sb.toString()
    }

    fun run(seeds: Int = 500, maxAnte: Int = 3): Boolean {
        val plan = streamPlan(maxAnte)
        val cache = RngCache()
        var checks = 0L

        for (s in 0 until seeds) {
            val seed = seedAt(s.toLong() * 7919L + 13L)
            val ref = RefCache(seed)
            cache.reset(seed)

            for (t in plan) {
                val key = RngKeys.keyFor(t[0], t[1], t[2], t[3])
                val expected = ref.random(key)
                val actual = cache.random(t[0], t[1], t[2], t[3])
                checks++
                if (expected.toRawBits() != actual.toRawBits()) {
                    println("MISMATCH seed=$seed key=$key")
                    println("  expected ${expected.toRawBits().toString(16)} ($expected)")
                    println("  actual   ${actual.toRawBits().toString(16)} ($actual)")
                    return false
                }
            }
        }
        println("OK: $checks draws across $seeds seeds are bit-identical")
        return true
    }
}

fun main() {
    check(RngVerify.run()) { "RNG verification failed" }
}