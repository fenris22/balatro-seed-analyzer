package analyzer

import kotlin.math.E
import kotlin.math.PI

/**
 * Leap-ahead tables for the Tausworthe generator behind [luaDraw].
 *
 * Each draw seeds four 64-bit generators and steps each one 11 times. A step is only
 * shifts, masks and XORs, so 11 steps are a fixed linear map over the bits (GF(2)): the
 * result is the XOR of what each set input bit contributes on its own. Splitting a
 * generator's 64 input bits into chunks of [bits] and storing, for every chunk value, the
 * XOR of its bits' contributions turns the 44 dependent steps into 4 * 64 / bits
 * independent table reads XORed together:
 *
 *  - 4 bits:  64 reads, 8 KB (small enough for the kernel to copy into shared memory)
 *  - 8 bits:  32 reads, 64 KB
 *  - 16 bits: 16 reads, 8 MB
 *
 * Only the 52 bits the draw keeps are stored. The layout is [generator][chunk][value].
 * [build] checks the table against [luaDraw] before handing it out, so a table that
 * disagrees never reaches the device.
 */
internal object RngTable {
    val SUPPORTED = listOf(4, 8, 16)
    private const val MASK52 = 0x000FFFFFFFFFFFFFL

    private fun step(gen: Int, z: Long): Long = when (gen) {
        0 -> (((z shl 31) xor z) ushr 45) xor ((z and -2L) shl 18)
        1 -> (((z shl 19) xor z) ushr 30) xor ((z and -64L) shl 28)
        2 -> (((z shl 24) xor z) ushr 48) xor ((z and -512L) shl 7)
        else -> (((z shl 21) xor z) ushr 39) xor ((z and -131072L) shl 8)
    }

    private fun leap(gen: Int, start: Long): Long {
        var z = start
        repeat(11) { z = step(gen, z) }
        return z
    }

    private val cache = HashMap<Int, LongArray>()

    @Synchronized
    fun build(bits: Int): LongArray = cache.getOrPut(bits) {
        require(bits in SUPPORTED) { "RNG table width must be one of $SUPPORTED" }
        val chunks = 64 / bits
        val size = 1 shl bits
        val t = LongArray(4 * chunks * size)
        for (gen in 0 until 4) {
            val column = LongArray(64) { leap(gen, 1L shl it) and MASK52 }
            for (j in 0 until chunks) {
                val base = (gen * chunks + j) * size
                for (v in 1 until size) {
                    t[base + v] = t[base + (v and (v - 1))] xor column[j * bits + Integer.numberOfTrailingZeros(v)]
                }
            }
        }
        val rnd = java.util.SplittableRandom(0x5eed)
        repeat(50_000) {
            val d = rnd.nextDouble()
            check(draw(t, bits, d).toRawBits() == luaDraw(d).toRawBits()) {
                "RNG leap-ahead table ($bits-bit) disagrees with luaDraw at $d"
            }
        }
        t
    }

    /** [luaDraw] computed through the table: the kernel's arithmetic, used by the self-check. */
    fun draw(t: LongArray, bits: Int, d0: Double): Double {
        val chunks = 64 / bits
        val size = 1 shl bits
        val mask = (size - 1).toLong()
        var d = d0
        val z = LongArray(4)
        val floorOf = longArrayOf(2L, 64L, 512L, 131072L)
        for (gen in 0 until 4) {
            d = d * PI; d = d + E
            val u = d.toRawBits()
            z[gen] = if (java.lang.Long.compareUnsigned(u, floorOf[gen]) < 0) u + floorOf[gen] else u
        }
        var r = 0L
        for (gen in 0 until 4) for (j in 0 until chunks) {
            r = r xor t[(gen * chunks + j) * size + ((z[gen] ushr (j * bits)) and mask).toInt()]
        }
        return java.lang.Double.longBitsToDouble((r and MASK52) or 0x3FF0000000000000L) - 1.0
    }
}
