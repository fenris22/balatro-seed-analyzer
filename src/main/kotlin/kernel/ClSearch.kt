package analyzer

import org.jocl.*
import org.jocl.CL.*

/**
 * GPU search driver.
 *
 * Shape of the thing: the kernel is a filter and the CPU is the reporter. The device
 * generates, matches and scores, and writes nothing but (seedOffset, score) for the
 * handful of seeds that clear the cutoff. The host then re-runs each hit through the
 * Kotlin analyzer -- which both builds the report and checks the device's score. That
 * cross-check is the safety net: if the two disagree the run stops, because a silently
 * wrong kernel produces plausible results for the wrong seeds.
 *
 * Why this does not run out of memory the way ClRng.verify did at 100M seeds: nothing
 * here is sized by the seed count. ClRng materialises every draw of every seed on the
 * Java heap by design, because that is what a bit-comparison needs -- 100M seeds times
 * ~200 draws is 160 GB, so it dies, and would have died on the device buffer first. The
 * real search allocates by *launch width*, not by how many seeds a chunk holds: each work
 * item walks a strided slice of the chunk, so the state buffer is nStreams x globalSize x
 * 8 bytes regardless. Keep GLOBAL_SIZE where it is and it stays around 100-300 MB.
 */
object ClSearch {

    const val MAX_KEY_LEN = 32
    const val MAX_LEN_SLOTS = 16

    /** Work items in flight. The state buffer scales with this, nothing else. */
    const val GLOBAL_SIZE = 65536
    const val LOCAL_SIZE = 64L

    /** Seeds per kernel launch. Bounded to stay well under the Windows TDR watchdog. */
    const val CHUNK = 4_000_000

    /** Per-launch hit capacity. Overflow is detected and reported, never silently dropped. */
    const val MAX_HITS = 1 shl 16

    private const val BUILD_OPTIONS = "-cl-std=CL1.2"

    class Unsupported(msg: String) : Exception(msg)

    // --- spec encoding --------------------------------------------------------

    /**
     * Item code shared by host and device. Jokers carry their rarity (0-3) in the high
     * half; tarots and the two substitution cards get tags above that, so one int compare
     * on the device distinguishes everything it can generate.
     */
    private const val TAROT_TAG = 4
    private const val SPECIAL_TAG = 5

    private fun itemCode(item: Item): Int {
        PoolArr.COMMON_JOKERS.indexOfFirst { it.id == item.id }.let { if (it >= 0) return (R_COMMON shl 16) or it }
        PoolArr.UNCOMMON_JOKERS.indexOfFirst { it.id == item.id }.let { if (it >= 0) return (R_UNCOMMON shl 16) or it }
        PoolArr.RARE_JOKERS.indexOfFirst { it.id == item.id }.let { if (it >= 0) return (R_RARE shl 16) or it }
        PoolArr.LEGENDARY_JOKERS.indexOfFirst { it.id == item.id }.let { if (it >= 0) return (R_LEGENDARY shl 16) or it }
        PoolArr.TAROTS.indexOfFirst { it.id == item.id }.let { if (it >= 0) return (TAROT_TAG shl 16) or it }
        if (item.id == "The_Soul") return (SPECIAL_TAG shl 16)
        if (item.id == "Black_Hole") return (SPECIAL_TAG shl 16) or 1
        throw Unsupported("${item.displayName} is outside what the kernel generates")
    }

    /** -1 any, 0 none, 1..4 matching Pools.EDITIONS[1..4]. */
    private fun editionCode(spec: RequestSpec): Int {
        val target = spec.editionTarget ?: return -1
        if (target.id == RequestSpec.NO_EDITION.id) return 0
        for (i in 1..4) if (Pools.EDITIONS[i].id == target.id) return i
        throw Unsupported("unknown edition ${target.displayName}")
    }

    private fun packFamilyCode(family: String): Int = when (family) {
        "Buffoon" -> 0; "Arcana" -> 1; "Celestial" -> 2; "Spectral" -> 3; else -> 4
    }

    /**
     * The kernel reproduces the CPU filter pass only for the joker / edition / soul subset.
     * Anything else must stay on the CPU rather than quietly produce different results.
     */
    fun supports(detail: Detail): Boolean =
        !detail.planets && !detail.spectrals &&
                !detail.standardCards && !detail.bosses && !detail.tags

    // --- reachable stream table ----------------------------------------------

    /**
     * Enumerates every (family, source, ante, resample) the kernel can reach and compacts
     * it. The cartesian id space is 41k entries; the reachable set for a three-ante joker
     * search is a couple of hundred, which is what makes the per-work-item state affordable.
     */
    private class Streams(maxAnte: Int, wantEditions: Boolean, wantSouls: Boolean, wantTarots: Boolean) {
        val ids = ArrayList<Int>()
        val keys = ArrayList<String>()
        val remap = IntArray(RngKeys.STREAM_COUNT) { -1 }

        private fun add(f: Int, s: Int, a: Int) {
            for (r in 0 until RngKeys.MAX_RESAMPLE) {
                val id = RngKeys.streamId(f, s, a, r)
                if (remap[id] >= 0) continue
                remap[id] = ids.size
                ids.add(id)
                keys.add(RngKeys.keyFor(f, s, a, r))
            }
        }

        init {
            for (a in 1..maxAnte) {
                add(RngKeys.VOUCHER, 0, a)
                add(RngKeys.CDT, 0, a)
                add(RngKeys.SHOP_PACK, 0, a)
                for (src in intArrayOf(RngKeys.SRC_SHO, RngKeys.SRC_BUF)) {
                    add(RngKeys.RARITY, src, a)
                    if (wantEditions) add(RngKeys.EDITION, src, a)
                    add(RngKeys.JOKER1, src, a)
                    add(RngKeys.JOKER2, src, a)
                    add(RngKeys.JOKER3, src, a)
                }
                if (wantTarots) {
                    add(RngKeys.TAROT, RngKeys.SRC_AR1, a)
                    add(RngKeys.TAROT, RngKeys.SRC_SHO, a)
                    add(RngKeys.SOUL_TAROT, 0, a)
                }
                if (wantSouls) {
                    add(RngKeys.SOUL_TAROT, 0, a)
                    add(RngKeys.SOUL_PLANET, 0, a)
                    add(RngKeys.SOUL_SPECTRAL, 0, a)
                    if (wantEditions) add(RngKeys.EDITION, RngKeys.SRC_SOU, a)
                }
            }
            if (wantSouls) add(RngKeys.JOKER4, 0, 0)
        }

        val lenSlots: IntArray = run {
            val distinct = keys.map { it.length }.distinct().sorted()
            require(distinct.size <= MAX_LEN_SLOTS) {
                "search uses ${distinct.size} distinct key lengths, kernel allows $MAX_LEN_SLOTS"
            }
            IntArray(keys.size) { distinct.indexOf(keys[it].length) }
        }

        init {
            keys.forEach { require(it.length <= MAX_KEY_LEN) { "key too long: $it" } }
        }
    }

    // --- device plumbing ------------------------------------------------------

    private fun deviceInfo(device: cl_device_id, param: Int): String {
        val size = LongArray(1)
        clGetDeviceInfo(device, param, 0, null, size)
        val buf = ByteArray(size[0].toInt())
        clGetDeviceInfo(device, param, buf.size.toLong(), Pointer.to(buf), null)

        val cus = IntArray(1)
        clGetDeviceInfo(device, CL_DEVICE_MAX_COMPUTE_UNITS, 4, Pointer.to(cus), null)
        println("compute units: ${cus[0]}")

        return String(buf, 0, maxOf(0, buf.size - 1))
    }

    private fun pickDevice(): Pair<cl_platform_id, cl_device_id> {
        val numPlatforms = IntArray(1)
        clGetPlatformIDs(0, null, numPlatforms)
        require(numPlatforms[0] > 0) { "no OpenCL platforms" }
        val platforms = arrayOfNulls<cl_platform_id>(numPlatforms[0])
        clGetPlatformIDs(platforms.size, platforms, null)
        for (type in longArrayOf(CL_DEVICE_TYPE_GPU, CL_DEVICE_TYPE_ALL)) {
            for (p in platforms) {
                val n = IntArray(1)
                try { clGetDeviceIDs(p, type, 0, null, n) } catch (e: CLException) { continue }
                if (n[0] == 0) continue
                val devices = arrayOfNulls<cl_device_id>(n[0])
                clGetDeviceIDs(p, type, n[0], devices, null)
                for (d in devices) {
                    if (deviceInfo(d!!, CL_DEVICE_EXTENSIONS).contains("cl_khr_fp64")) return p!! to d
                }
            }
        }
        throw Unsupported("no OpenCL device with cl_khr_fp64")
    }

    private fun loadSource(): String =
        ClSearch::class.java.getResourceAsStream("/search.cl")?.bufferedReader()?.readText()
            ?: java.io.File("/search.cl").takeIf { it.exists() }?.readText()
            ?: error("search.cl not found on the classpath or at opencl/search.cl")

    private fun buildDefines(
        specs: Array<RequestSpec>,
        detail: Detail,
        maxSearchAnte: Int,
        shopItems: Int,
        streams: Streams,
    ): String = buildString {
        fun d(name: String, value: Any) = appendLine("#define $name $value")
        d("NUM_SPECS", specs.size)
        d("MAX_SEARCH_ANTE", maxSearchAnte)
        d("SHOP_ITEMS", shopItems)
        d("NUM_VOUCHERS", PoolArr.VOUCHERS.size)
        d("NUM_PACK_KINDS", PoolArr.PACK_KINDS.size)
        // Locale.ROOT: a comma decimal separator would emit invalid C and the kernel
        // would build with a silently wrong pack weight.
        d("PACK_TOTAL_WEIGHT", String.format(java.util.Locale.ROOT, "%.17g", Pools.PACK_TOTAL_WEIGHT))
        d("BUFFOON_PACK_INDEX", PoolArr.PACK_KINDS.indexOfFirst { it.item.id == "Buffoon_Pack" })
        d("POOL_N_COMMON", PoolArr.COMMON_JOKERS.size)
        d("POOL_N_UNCOMMON", PoolArr.UNCOMMON_JOKERS.size)
        d("POOL_N_RARE", PoolArr.RARE_JOKERS.size)
        d("POOL_N_LEGENDARY", PoolArr.LEGENDARY_JOKERS.size)
        d("MAX_RESAMPLE", RngKeys.MAX_RESAMPLE)
        d("MAX_KEY_LEN", MAX_KEY_LEN)
        d("MAX_LEN_SLOTS", MAX_LEN_SLOTS)
        d("WANT_JOKERS", if (detail.jokers) 1 else 0)
        d("WANT_EDITIONS", if (detail.editions) 1 else 0)
        d("WANT_TAROTS", if (detail.tarots) 1 else 0)
        d("POOL_N_TAROTS", PoolArr.TAROTS.size)
        d("WANT_SOULS", if (detail.souls) 1 else 0)
        d("WANT_SOUL_JOKERS", if (detail.soulJokers) 1 else 0)
        d("V_TAROT_TYCOON", PoolArr.V_TAROT_TYCOON)
        d("V_TAROT_MERCHANT", PoolArr.V_TAROT_MERCHANT)
        d("V_PLANET_TYCOON", PoolArr.V_PLANET_TYCOON)
        d("V_PLANET_MERCHANT", PoolArr.V_PLANET_MERCHANT)
        d("V_MAGIC_TRICK", PoolArr.V_MAGIC_TRICK)
        appendLine("// ${streams.ids.size} reachable streams")
    }

    // --- the run --------------------------------------------------------------

    class Hit(val index: Long, val deviceScore: Double)

    /**
     * Runs [seedCount] seeds from [startIndex] and returns the hits, verified on CPU.
     *
     * [onHit] is called for each verified hit with the seed index and its CPU-computed
     * score; returning the score from both sides is what makes the disagreement check
     * possible.
     */
    fun run(
        specs: Array<RequestSpec>,
        detail: Detail,
        maxSearchAnte: Int,
        shopItems: Int,
        ignoredVouchers: List<String>,
        startIndex: Long,
        seedCount: Long,
        cutoffOf: () -> Double,
        onHit: (Long, Double) -> Unit,
    ) {
        if (!supports(detail)) throw Unsupported("kernel covers jokers, tarots, editions and Souls only, got $detail")

        setExceptionsEnabled(true)
        val streams = Streams(maxSearchAnte, detail.editions, detail.souls, detail.tarots)
        val nStreams = streams.ids.size

        val (platform, device) = pickDevice()
        println("GPU: ${deviceInfo(device, CL_DEVICE_NAME).trim()}")
        println("Streams: $nStreams, state buffer ${nStreams.toLong() * GLOBAL_SIZE * 8 / (1 shl 20)} MB")

        val props = cl_context_properties().apply { addProperty(CL_CONTEXT_PLATFORM.toLong(), platform) }
        val context = clCreateContext(props, 1, arrayOf(device), null, null, null)
        val queue = clCreateCommandQueue(context, device, 0, null)

        // --- constant tables ---
        val keyChars = ByteArray(nStreams * MAX_KEY_LEN)
        val keyLens = ByteArray(nStreams)
        val keySlots = ByteArray(nStreams)
        for (i in 0 until nStreams) {
            val k = streams.keys[i]
            for (j in k.indices) keyChars[i * MAX_KEY_LEN + j] = k[j].code.toByte()
            keyLens[i] = k.length.toByte()
            keySlots[i] = streams.lenSlots[i].toByte()
        }

        val specItem = IntArray(specs.size) { itemCode(specs[it].item) }
        val specEdition = IntArray(specs.size) { editionCode(specs[it]) }
        val specSlotTarget = IntArray(specs.size) { if (specs[it].hasSlot) specs[it].slotTarget else -1 }
        val specSlotPriority = IntArray(specs.size) { specs[it].slotPriority }
        val specAnteTarget = IntArray(specs.size) { if (specs[it].hasAnte) specs[it].anteTarget else -1 }
        val specAntePriority = IntArray(specs.size) { specs[it].antePriority }
        val specEditionScore = DoubleArray(specs.size) { specs[it].maxEditionScore }

        val packFamily = IntArray(PoolArr.PACK_KINDS.size) { packFamilyCode(PoolArr.PACK_KINDS[it].family) }
        val packSize = IntArray(PoolArr.PACK_KINDS.size) { PoolArr.PACK_KINDS[it].size }
        val voucherIgnored = ByteArray(PoolArr.VOUCHERS.size) {
            if (PoolArr.VOUCHERS[it].id in ignoredVouchers) 1 else 0
        }

        fun roBytes(a: ByteArray) = clCreateBuffer(context, CL_MEM_READ_ONLY or CL_MEM_COPY_HOST_PTR,
            a.size.toLong(), Pointer.to(a), null)
        fun roInts(a: IntArray) = clCreateBuffer(context, CL_MEM_READ_ONLY or CL_MEM_COPY_HOST_PTR,
            (a.size * Sizeof.cl_int).toLong(), Pointer.to(a), null)
        fun roDoubles(a: DoubleArray) = clCreateBuffer(context, CL_MEM_READ_ONLY or CL_MEM_COPY_HOST_PTR,
            (a.size * Sizeof.cl_double).toLong(), Pointer.to(a), null)

        val bufs = ArrayList<cl_mem>()
        fun keep(m: cl_mem): cl_mem { bufs.add(m); return m }

        val bKeyChars = keep(roBytes(keyChars))
        val bKeyLens = keep(roBytes(keyLens))
        val bKeySlots = keep(roBytes(keySlots))
        val bSpecItem = keep(roInts(specItem))
        val bSpecEdition = keep(roInts(specEdition))
        val bSpecSlotT = keep(roInts(specSlotTarget))
        val bSpecSlotP = keep(roInts(specSlotPriority))
        val bSpecAnteT = keep(roInts(specAnteTarget))
        val bSpecAnteP = keep(roInts(specAntePriority))
        val bSpecEdScore = keep(roDoubles(specEditionScore))
        val bPackFamily = keep(roInts(packFamily))
        val bPackSize = keep(roInts(packSize))
        val bPackCum = keep(roDoubles(PoolArr.PACK_CUM))
        val bVoucherIgn = keep(roBytes(voucherIgnored))
        val bRemap = keep(roInts(streams.remap))
        val bState = keep(clCreateBuffer(context, CL_MEM_READ_WRITE,
            nStreams.toLong() * GLOBAL_SIZE * Sizeof.cl_double, null, null))
        val bHitIndex = keep(clCreateBuffer(context, CL_MEM_READ_WRITE,
            (MAX_HITS * Sizeof.cl_int).toLong(), null, null))
        val bHitScore = keep(clCreateBuffer(context, CL_MEM_READ_WRITE,
            (MAX_HITS * Sizeof.cl_double).toLong(), null, null))
        val bOverflow = keep(clCreateBuffer(context, CL_MEM_READ_WRITE,
            (MAX_HITS * Sizeof.cl_int).toLong(), null, null))
        val bHitCount = keep(clCreateBuffer(context, CL_MEM_READ_WRITE,
            (2 * Sizeof.cl_int).toLong(), null, null))

        val source = buildDefines(specs, detail, maxSearchAnte, shopItems, streams) + "\n" + loadSource()
        val program = clCreateProgramWithSource(context, 1, arrayOf(source), null, null)
        try {
            clBuildProgram(program, 0, null, BUILD_OPTIONS, null, null)
        } catch (e: CLException) {
            val size = LongArray(1)
            clGetProgramBuildInfo(program, device, CL_PROGRAM_BUILD_LOG, 0, null, size)
            val log = ByteArray(size[0].toInt())
            clGetProgramBuildInfo(program, device, CL_PROGRAM_BUILD_LOG, log.size.toLong(), Pointer.to(log), null)
            println(String(log))
            throw e
        }
        val kernel = clCreateKernel(program, "search", null)

        // CPU mirror, used to verify every hit and to redo anything the device punted.
        val cpuState = MatchState(specs)
        val cpuSink = MatchSink(cpuState, maxSearchAnte, prune = false)
        val cpuAnalyzer = SeedAnalyzer(detail, ignoredVouchers)
        val seedBuf = CharArray(16)

        fun cpuScore(index: Long): Double {
            cpuState.reset()
            val len = seedForIndex(index, seedBuf)
            cpuAnalyzer.reset(seedBuf, len)
            for (ante in 1..maxSearchAnte) {
                cpuAnalyzer.scanAnte(ante, shopItems, cpuSink)
                if (cpuState.isComplete) break
            }
            return if (cpuState.isComplete) cpuState.total else Double.NEGATIVE_INFINITY
        }

        val hitIndex = IntArray(MAX_HITS)
        val hitScore = DoubleArray(MAX_HITS)
        val overflowIdx = IntArray(MAX_HITS)
        val counts = IntArray(2)

        var done = 0L
        var totalHits = 0L
        var totalOverflow = 0L
        val started = System.nanoTime()

        while (done < seedCount) {
            val chunk = minOf(CHUNK.toLong(), seedCount - done).toInt()
            val base = startIndex + done

            clEnqueueWriteBuffer(queue, bHitCount, CL_TRUE, 0, (2 * Sizeof.cl_int).toLong(),
                Pointer.to(intArrayOf(0, 0)), 0, null, null)

            var a = 0
            for (b in listOf(bKeyChars, bKeyLens, bKeySlots, bSpecItem, bSpecEdition, bSpecSlotT,
                bSpecSlotP, bSpecAnteT, bSpecAnteP, bSpecEdScore, bPackFamily, bPackSize, bPackCum,
                bVoucherIgn, bRemap, bState, bHitIndex, bHitScore, bHitCount, bOverflow)) {
                clSetKernelArg(kernel, a++, Sizeof.cl_mem.toLong(), Pointer.to(b))
            }
            clSetKernelArg(kernel, a++, Sizeof.cl_ulong.toLong(), Pointer.to(longArrayOf(base)))
            clSetKernelArg(kernel, a++, Sizeof.cl_int.toLong(), Pointer.to(intArrayOf(chunk)))
            clSetKernelArg(kernel, a++, Sizeof.cl_int.toLong(), Pointer.to(intArrayOf(nStreams)))
            clSetKernelArg(kernel, a++, Sizeof.cl_double.toLong(), Pointer.to(doubleArrayOf(cutoffOf())))
            clSetKernelArg(kernel, a, Sizeof.cl_int.toLong(), Pointer.to(intArrayOf(MAX_HITS)))

            clEnqueueNDRangeKernel(queue, kernel, 1, null,
                longArrayOf(GLOBAL_SIZE.toLong()), longArrayOf(LOCAL_SIZE), 0, null, null)
            clFinish(queue)

            clEnqueueReadBuffer(queue, bHitCount, CL_TRUE, 0, (2 * Sizeof.cl_int).toLong(),
                Pointer.to(counts), 0, null, null)
            val nHits = minOf(counts[0], MAX_HITS)
            val nOver = minOf(counts[1], MAX_HITS)
            check(counts[0] <= MAX_HITS) {
                "${counts[0]} hits in one chunk exceeded the $MAX_HITS buffer, so ${counts[0] - MAX_HITS} " +
                        "seeds were dropped. This is a threshold problem, not a buffer problem: at " +
                        "${"%.1f".format(100.0 * counts[0] / chunk)}% of seeds matching, the cutoff is too low to " +
                        "be selecting anything. Raise THRESHOLD_FRACTION."
            }

            if (nHits > 0) {
                clEnqueueReadBuffer(queue, bHitIndex, CL_TRUE, 0, (nHits * Sizeof.cl_int).toLong(),
                    Pointer.to(hitIndex), 0, null, null)
                clEnqueueReadBuffer(queue, bHitScore, CL_TRUE, 0, (nHits * Sizeof.cl_double).toLong(),
                    Pointer.to(hitScore), 0, null, null)
                for (i in 0 until nHits) {
                    val idx = base + hitIndex[i]
                    val host = cpuScore(idx)
                    check(host == hitScore[i]) {
                        "device and host disagree on seed ${seedForIndex(idx)} (index $idx): " +
                                "device ${hitScore[i]}, host $host"
                    }
                    onHit(idx, host)
                }
                totalHits += nHits
            }

            // Seeds the device could not finish -- a resample past MAX_RESAMPLE, or a stream
            // outside the compacted table. Redone exactly, on the CPU.
            if (nOver > 0) {
                clEnqueueReadBuffer(queue, bOverflow, CL_TRUE, 0, (nOver * Sizeof.cl_int).toLong(),
                    Pointer.to(overflowIdx), 0, null, null)
                for (i in 0 until nOver) {
                    val idx = base + overflowIdx[i]
                    val host = cpuScore(idx)
                    if (host > cutoffOf()) onHit(idx, host)
                }
                totalOverflow += nOver
            }

            done += chunk
            val elapsed = (System.nanoTime() - started) / 1e9
            println("%,d / %,d seeds | %,.0f seeds/s | %,d hits | %,d punted to CPU | cutoff %.0f"
                .format(done, seedCount, done / elapsed, totalHits, totalOverflow, cutoffOf()))
        }

        bufs.forEach { clReleaseMemObject(it) }
        clReleaseKernel(kernel)
        clReleaseProgram(program)
        clReleaseCommandQueue(queue)
        clReleaseContext(context)
    }
}