package analyzer

import org.jocl.*
import org.jocl.CL.*

/**
 * GPU search driver.
 *
 * The kernel is a filter and the CPU is the reporter. The device generates, matches and
 * scores, and writes nothing but (seedOffset, score) for the seeds that pass every
 * required condition and clear the cutoff. The host then re-runs each hit through the
 * Kotlin analyzer, which both builds the report and checks the device's score. That
 * cross-check is the safety net: a silently wrong kernel produces plausible results for
 * the wrong seeds, and nothing else would catch it.
 *
 * Nothing here is sized by the seed count. Each work item walks a strided slice of the
 * chunk, so the state buffer is nStreams x globalSize x 8 bytes however many seeds a
 * chunk holds.
 */
object ClSearch {

    const val MAX_KEY_LEN = 32
    /**
     * Ceiling on distinct key lengths, which sizes the kernel's per-work-item prefix cache.
     *
     * The count grows with the highest ante searched, because a two-digit ante makes every
     * key one character longer -- an ante-12 search needs noticeably more slots than an
     * ante-6 one. 32 is the hard limit: havePrefix is a uint bitmask. The kernel is
     * compiled with the actual count, not this ceiling, so raising it costs nothing.
     */
    const val MAX_LEN_SLOTS = 32

    /** Work items per compute unit. The state buffer scales with this, nothing else. */
    const val GLOBAL_SIZE = 8192
    const val LOCAL_SIZE = 256L

    /**
     * Seeds per kernel launch.
     *
     * Larger is better now that work is pulled from a shared counter rather than split by
     * a fixed stride: a big chunk amortises the launch, the per-seed state clear and the
     * host round-trip over more work, and there is no longer a ragged tail to pay for it.
     * Lower this only if you are on a GPU driving a display, where a launch over about two
     * seconds trips the watchdog and resets the driver.
     */
    const val CHUNK = 64_000_000

    /** Per-launch hit capacity. */
    const val MAX_HITS = 1 shl 18

    /**
     * How many of each chunk's hits are re-run on the CPU and checked against the device.
     *
     * A kernel that has diverged is wrong on essentially every seed, so a sample catches it
     * in the first chunk. Verifying all of them instead costs more host time than the
     * device spent finding them, and it runs between launches with the card idle.
     */
    const val VERIFY_PER_CHUNK = 64

    /**
     * Counts are packed 4 bits per condition into one ulong on the device, so these two
     * limits are structural rather than arbitrary. Both have comfortable headroom.
     */
    const val MAX_CONDITIONS = 16
    const val MAX_COUNT = 15

    private const val BUILD_OPTIONS = "-cl-std=CL1.2"

    class Unsupported(msg: String) : Exception(msg)

    // --- condition encoding ---------------------------------------------------

    private const val TAROT_TAG = 4
    private const val SPECIAL_TAG = 5
    private const val SPECTRAL_TAG = 6

    /**
     * Item code shared by host and device. Jokers carry their rarity (0-3) in the high
     * half; tarots, spectrals and the two substitution cards get tags above that, so one
     * int compare on the device distinguishes everything it can generate.
     */
    private fun itemCode(item: Item): Int {
        PoolArr.COMMON_JOKERS.indexOfFirst { it.id == item.id }.let { if (it >= 0) return (R_COMMON shl 16) or it }
        PoolArr.UNCOMMON_JOKERS.indexOfFirst { it.id == item.id }.let { if (it >= 0) return (R_UNCOMMON shl 16) or it }
        PoolArr.RARE_JOKERS.indexOfFirst { it.id == item.id }.let { if (it >= 0) return (R_RARE shl 16) or it }
        PoolArr.LEGENDARY_JOKERS.indexOfFirst { it.id == item.id }.let { if (it >= 0) return (R_LEGENDARY shl 16) or it }
        PoolArr.TAROTS.indexOfFirst { it.id == item.id }.let { if (it >= 0) return (TAROT_TAG shl 16) or it }
        PoolArr.SPECTRALS.indexOfFirst { it.id == item.id }.let { if (it >= 0) return (SPECTRAL_TAG shl 16) or it }
        if (item.id == "The_Soul") return (SPECIAL_TAG shl 16)
        if (item.id == "Black_Hole") return (SPECIAL_TAG shl 16) or 1
        throw Unsupported("${item.displayName} is outside what the kernel generates")
    }

    /** -1 any, 0 none, 1..4 matching Pools.EDITIONS[1..4]. */
    private fun editionCode(c: Condition): Int {
        val target = c.editionTarget ?: return -1
        if (target.id == Condition.NO_EDITION.id) return 0
        for (i in 1..4) if (Pools.EDITIONS[i].id == target.id) return i
        throw Unsupported("unknown edition ${target.displayName}")
    }

    private fun packFamilyCode(family: String): Int = when (family) {
        "Buffoon" -> 0; "Arcana" -> 1; "Celestial" -> 2; "Spectral" -> 3; else -> 4
    }

    /**
     * The kernel reproduces the CPU filter pass only for the joker / tarot / spectral /
     * edition / Soul subset. Anything else stays on the CPU rather than quietly producing
     * different results.
     *
     * Sources matter as much as card types here: the kernel generates vouchers but never
     * offers them, and generates no tags or bosses at all, so a condition sourced from any
     * of those must fall back even if the card types it wants are supported.
     */
    fun supports(detail: Detail, conditions: Array<Condition>): Boolean {
        if (detail.planets || detail.standardCards || detail.bosses || detail.tags) return false
        if (conditions.size > MAX_CONDITIONS) return false
        if (conditions.any { it.count > MAX_COUNT }) return false
        val deviceSources = Src.SHOP or Src.PACK or Src.SOUL
        if (conditions.any { it.sources and deviceSources.inv() != 0 }) return false
        return true
    }

    /** Explains a refusal, so a silent fallback never looks like a mystery. */
    fun whyUnsupported(detail: Detail, conditions: Array<Condition>): String? {
        if (detail.planets) return "planets are not generated on the device"
        if (detail.standardCards) return "standard cards are not generated on the device"
        if (detail.bosses) return "bosses are not generated on the device"
        if (detail.tags) return "tags are not generated on the device"
        if (conditions.size > MAX_CONDITIONS) return "${conditions.size} conditions exceeds the $MAX_CONDITIONS the packed counts allow"
        conditions.firstOrNull { it.count > MAX_COUNT }?.let { return "count ${it.count} exceeds the $MAX_COUNT the packed counts allow ($it)" }
        val deviceSources = Src.SHOP or Src.PACK or Src.SOUL
        conditions.firstOrNull { it.sources and deviceSources.inv() != 0 }
            ?.let { return "source ${Src.describe(it.sources)} is not offered on the device ($it)" }
        return null
    }

    // --- reachable stream table ----------------------------------------------

    /**
     * Enumerates every (family, source, ante, resample) the kernel can reach and compacts
     * it. The cartesian id space is 41k entries; the reachable set for a joker search is a
     * couple of hundred, which is what makes the per-work-item state affordable.
     */
    private class Streams(
        maxAnte: Int, wantEditions: Boolean, wantSouls: Boolean, wantTarots: Boolean, wantSpectrals: Boolean,
    ) {
        val ids = ArrayList<Int>()
        val keys = ArrayList<String>()
        val remap = IntArray(RngKeys.STREAM_COUNT) { -1 }

        /**
         * [maxResample] is how many resample slots this stream can actually need.
         *
         * Most families need exactly one: cdt, shop_pack, rarity, edition and the soul
         * rolls are never resampled at all, and reserving 16 slots each inflated the table
         * -- and therefore the state buffer and the per-seed NaN fill -- by roughly 3x.
         * Duplicate-suppression resamples inside a pack are bounded by the pack size, so 8
         * is generous; only the voucher, which re-rolls while locked, needs the full range.
         *
         * Under-provisioning is safe rather than wrong: a draw past the cap finds no entry
         * in the remap, flags the seed, and the host redoes it exactly on the CPU.
         */
        private fun add(f: Int, s: Int, a: Int, maxResample: Int = 1) {
            for (r in 0 until maxResample) {
                val id = RngKeys.streamId(f, s, a, r)
                if (remap[id] >= 0) continue
                remap[id] = ids.size
                ids.add(id)
                keys.add(RngKeys.keyFor(f, s, a, r))
            }
        }

        /**
         * anteEnd[a] is one past the last stream belonging to ante a.
         *
         * The table is built ante-major, so every ante's streams are contiguous. That lets
         * the kernel clear only the slice for the ante it is about to run instead of the
         * whole table per seed -- which matters a lot when the highest ante searched is 12
         * but most seeds are killed in ante 1 or 2.
         */
        val anteEnd = IntArray(maxAnte + 1)

        init {
            val packDedup = 8
            for (a in 1..maxAnte) {
                add(RngKeys.VOUCHER, 0, a, RngKeys.MAX_RESAMPLE)
                add(RngKeys.CDT, 0, a)
                add(RngKeys.SHOP_PACK, 0, a)

                // Shop jokers are never deduplicated; Buffoon-pack jokers are.
                add(RngKeys.RARITY, RngKeys.SRC_SHO, a)
                add(RngKeys.RARITY, RngKeys.SRC_BUF, a)
                if (wantEditions) {
                    add(RngKeys.EDITION, RngKeys.SRC_SHO, a)
                    add(RngKeys.EDITION, RngKeys.SRC_BUF, a)
                }
                for (fam in intArrayOf(RngKeys.JOKER1, RngKeys.JOKER2, RngKeys.JOKER3)) {
                    add(fam, RngKeys.SRC_SHO, a)
                    add(fam, RngKeys.SRC_BUF, a, packDedup)
                }

                if (wantTarots) {
                    add(RngKeys.TAROT, RngKeys.SRC_AR1, a, packDedup)
                    add(RngKeys.TAROT, RngKeys.SRC_SHO, a)
                    add(RngKeys.SOUL_TAROT, 0, a)
                }
                if (wantSpectrals) {
                    // Spectrals only ever come from packs: the shop's spectral rate is a
                    // hard 0 on this deck, so no SRC_SHO stream is ever reached.
                    add(RngKeys.SPECTRAL, RngKeys.SRC_SPE, a, packDedup)
                    add(RngKeys.SOUL_SPECTRAL, 0, a)
                }
                if (wantSouls) {
                    add(RngKeys.SOUL_TAROT, 0, a)
                    add(RngKeys.SOUL_PLANET, 0, a)
                    add(RngKeys.SOUL_SPECTRAL, 0, a)
                    if (wantEditions) add(RngKeys.EDITION, RngKeys.SRC_SOU, a)
                }
                anteEnd[a] = ids.size
            }
            // Streams with no ante of their own (the Soul's legendary queue) live past the
            // last ante's slice and are cleared once per seed.
            if (wantSouls) add(RngKeys.JOKER4, 0, 0, packDedup)
        }

        /** Distinct key lengths actually in use; the kernel sizes its prefix cache to this. */
        var lenSlotCount = 0
            private set

        val lenSlots: IntArray = run {
            val distinct = keys.map { it.length }.distinct().sorted()
            require(distinct.size <= MAX_LEN_SLOTS) {
                "search uses ${distinct.size} distinct key lengths, kernel allows $MAX_LEN_SLOTS"
            }
            require(distinct.size <= 32) { "prefix slots no longer fit a 32-bit mask" }
            lenSlotCount = distinct.size
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
        return String(buf, 0, maxOf(0, buf.size - 1))
    }

    private fun readComputeUnits(device: cl_device_id): Int {
        val cus = IntArray(1)
        clGetDeviceInfo(device, CL_DEVICE_MAX_COMPUTE_UNITS, 4L, Pointer.to(cus), null)
        return cus[0]
    }

    /**
     * Scratch is the number to watch. Anything above zero means a private array spilled to
     * device memory, and the spill is reserved for every resident work item on the card --
     * which is why a kernel that behaves on a 64-CU consumer part can cap out VRAM and
     * crawl on a 304-CU accelerator.
     */
    private fun printKernelInfo(kernel: cl_kernel, device: cl_device_id) {
        val priv = LongArray(1)
        clGetKernelWorkGroupInfo(kernel, device, CL_KERNEL_PRIVATE_MEM_SIZE,
            Sizeof.cl_ulong.toLong(), Pointer.to(priv), null)
        val local = LongArray(1)
        clGetKernelWorkGroupInfo(kernel, device, CL_KERNEL_LOCAL_MEM_SIZE,
            Sizeof.cl_ulong.toLong(), Pointer.to(local), null)
        val mult = LongArray(1)
        clGetKernelWorkGroupInfo(kernel, device, CL_KERNEL_PREFERRED_WORK_GROUP_SIZE_MULTIPLE,
            Sizeof.size_t.toLong(), Pointer.to(mult), null)
        println("scratch/work-item: ${priv[0]} B | LDS/group: ${local[0]} B | preferred wg multiple: ${mult[0]}")
    }

    /**
     * Devices too small to be worth a share of the work.
     *
     * An integrated GPU shows up alongside the discrete one and reports a CU count of 1 or
     * 2. It is not merely slow in proportion to that -- its per-CU throughput on this
     * workload is far worse than a discrete card's -- so including it costs more in
     * coordination than it contributes.
     */
    const val MIN_COMPUTE_UNITS = 4

    /**
     * Every usable fp64 GPU, from a single platform.
     *
     * A Linux box with ROCm usually has two OpenCL platforms -- ROCm's own and Mesa's
     * rusticl -- and both enumerate the same physical cards. Walking every platform
     * therefore reports each GPU once per platform, which looks like twice as many GPUs as
     * exist and would run two contexts against each card. Picking the single platform that
     * exposes the most usable devices gives each card exactly once.
     */
    fun listDevices(verbose: Boolean = false): List<Pair<cl_platform_id, cl_device_id>> {
        val numPlatforms = IntArray(1)
        clGetPlatformIDs(0, null, numPlatforms)
        if (numPlatforms[0] == 0) return emptyList()
        val platforms = arrayOfNulls<cl_platform_id>(numPlatforms[0])
        clGetPlatformIDs(platforms.size, platforms, null)

        var best: List<Pair<cl_platform_id, cl_device_id>> = emptyList()
        var bestName = ""

        for (p in platforms) {
            val n = IntArray(1)
            try { clGetDeviceIDs(p, CL_DEVICE_TYPE_GPU, 0, null, n) } catch (e: CLException) { continue }
            if (n[0] == 0) continue
            val devices = arrayOfNulls<cl_device_id>(n[0])
            clGetDeviceIDs(p, CL_DEVICE_TYPE_GPU, n[0], devices, null)

            val usable = ArrayList<Pair<cl_platform_id, cl_device_id>>()
            for (d in devices) {
                val dev = d!!
                if (!deviceInfo(dev, CL_DEVICE_EXTENSIONS).contains("cl_khr_fp64")) continue
                val cus = readComputeUnits(dev)
                if (cus < MIN_COMPUTE_UNITS) {
                    if (verbose) println("  skipping ${deviceInfo(dev, CL_DEVICE_NAME).trim()} ($cus CUs, below MIN_COMPUTE_UNITS)")
                    continue
                }
                usable.add(p!! to dev)
            }
            if (verbose) {
                println("  platform ${platformName(p!!)}: ${usable.size} usable GPU(s)")
            }
            if (usable.size > best.size) { best = usable; bestName = platformName(p!!) }
        }
        if (verbose && best.isNotEmpty()) println("  using platform $bestName")
        return best
    }

    private fun platformName(p: cl_platform_id): String {
        val size = LongArray(1)
        clGetPlatformInfo(p, CL_PLATFORM_NAME, 0, null, size)
        val buf = ByteArray(size[0].toInt())
        clGetPlatformInfo(p, CL_PLATFORM_NAME, buf.size.toLong(), Pointer.to(buf), null)
        return String(buf, 0, maxOf(0, buf.size - 1)).trim()
    }

    private fun pickDevice(): Pair<cl_platform_id, cl_device_id> =
        listDevices().firstOrNull() ?: throw Unsupported("no OpenCL device with cl_khr_fp64")

    private fun loadSource(): String =
        ClSearch::class.java.getResourceAsStream("/search.cl")?.bufferedReader()?.readText()
            ?: java.io.File("opencl/search.cl").takeIf { it.exists() }?.readText()
            ?: error("search.cl not found on the classpath or at opencl/search.cl")

    private fun buildDefines(
        conditions: Array<Condition>,
        detail: Detail,
        maxSearchAnte: Int,
        shopItems: Int,
        streams: Streams,
    ): String = buildString {
        fun d(name: String, value: Any) = appendLine("#define $name $value")
        d("NUM_CONDS", conditions.size)
        d("MAX_SEARCH_ANTE", maxSearchAnte)
        d("SHOP_ITEMS", shopItems)
        require(PoolArr.VOUCHERS.size <= 64) { "voucher pool no longer fits a 64-bit active mask" }
        d("NUM_VOUCHERS", PoolArr.VOUCHERS.size)
        d("NUM_PACK_KINDS", PoolArr.PACK_KINDS.size)
        // Locale.ROOT: a comma decimal separator would emit invalid C, or worse a silently
        // truncated pack weight.
        d("PACK_TOTAL_WEIGHT", String.format(java.util.Locale.ROOT, "%.17g", Pools.PACK_TOTAL_WEIGHT))
        d("BUFFOON_PACK_INDEX", PoolArr.PACK_KINDS.indexOfFirst { it.item.id == "Buffoon_Pack" })
        d("POOL_N_COMMON", PoolArr.COMMON_JOKERS.size)
        d("POOL_N_UNCOMMON", PoolArr.UNCOMMON_JOKERS.size)
        d("POOL_N_RARE", PoolArr.RARE_JOKERS.size)
        d("POOL_N_LEGENDARY", PoolArr.LEGENDARY_JOKERS.size)
        d("MAX_RESAMPLE", RngKeys.MAX_RESAMPLE)
        d("MAX_KEY_LEN", MAX_KEY_LEN)
        // The real count, not the ceiling: this sizes a dynamically indexed private array,
        // so every unused slot is scratch reserved for every resident work item.
        d("MAX_LEN_SLOTS", streams.lenSlotCount)
        d("WANT_JOKERS", if (detail.jokers) 1 else 0)
        d("WANT_EDITIONS", if (detail.editions) 1 else 0)
        d("WANT_TAROTS", if (detail.tarots) 1 else 0)
        d("POOL_N_TAROTS", PoolArr.TAROTS.size)
        d("WANT_SPECTRALS", if (detail.spectrals) 1 else 0)
        d("POOL_N_SPECTRALS", PoolArr.SPECTRALS.size)
        require(PoolArr.SPECTRALS.size <= 64) { "spectral pool no longer fits a 64-bit RETRY mask" }
        var retryMask = 0L
        for (i in PoolArr.SPECTRALS.indices) if (PoolArr.SPECTRALS[i].id == "RETRY") retryMask = retryMask or (1L shl i)
        d("SPECTRAL_RETRY_MASK", "${java.lang.Long.toUnsignedString(retryMask)}UL")
        d("WANT_SOULS", if (detail.souls) 1 else 0)
        d("WANT_SOUL_JOKERS", if (detail.soulJokers) 1 else 0)
        d("V_TAROT_TYCOON", PoolArr.V_TAROT_TYCOON)
        d("V_TAROT_MERCHANT", PoolArr.V_TAROT_MERCHANT)
        d("V_PLANET_TYCOON", PoolArr.V_PLANET_TYCOON)
        d("V_PLANET_MERCHANT", PoolArr.V_PLANET_MERCHANT)
        d("V_MAGIC_TRICK", PoolArr.V_MAGIC_TRICK)
        appendLine("// ${streams.ids.size} reachable streams")
    }

    /**
     * Hands out blocks of seeds to whoever asks next.
     *
     * Replaces splitting the range up front. A static split has to guess relative device
     * speed, and compute-unit count is a poor proxy -- an integrated GPU with a tenth the
     * CUs is far more than ten times slower on this workload, so it finishes its share
     * hours after everything else. Pulling blocks on demand means a slow device simply
     * takes fewer of them, and no estimate is needed.
     */
    class SeedDispenser(private val start: Long, val total: Long, private val block: Long = CHUNK.toLong()) {
        private val next = java.util.concurrent.atomic.AtomicLong(start)
        private val finished = java.util.concurrent.atomic.AtomicLong()
        private val end = start + total

        /**
         * Blocks handed out but not yet finished.
         *
         * Needed for [watermark]: with several devices pulling blocks, they finish out of
         * order, so "how far have we got" is not the same as "how many seeds are done".
         */
        private val inFlight = java.util.concurrent.ConcurrentSkipListSet<Long>()

        /** Base index of the next block, or -1 when the range is exhausted. */
        fun take(): Long {
            val b = next.getAndAdd(block)
            if (b >= end) return -1L
            inFlight.add(b)
            return b
        }

        fun sizeOf(base: Long): Int = minOf(block, end - base).toInt()

        /** Records a finished block and returns the running total across all devices. */
        fun complete(base: Long, n: Int): Long {
            inFlight.remove(base)
            return finished.addAndGet(n.toLong())
        }

        /**
         * The lowest index not yet fully searched -- a safe point to resume from.
         *
         * Everything below the oldest in-flight block is finished, so restarting there
         * re-scans at most the blocks that were still running. Resuming from "seeds done"
         * instead would silently skip a block that a slower device had not finished.
         */
        fun watermark(): Long = inFlight.firstOrNull() ?: minOf(next.get(), end)
    }

    // --- the run --------------------------------------------------------------

    /**
     * Runs [seedCount] seeds from [startIndex], calling [onHit] for each verified hit.
     *
     * [tolerateHitOverflow] is for calibration passes, where the hit buffer filling up
     * just truncates a sample rather than losing results.
     */
    fun run(
        conditions: Array<Condition>,
        detail: Detail,
        maxSearchAnte: Int,
        shopItems: Int,
        ignoredVouchers: List<String>,
        startIndex: Long,
        seedCount: Long,
        cutoffOf: () -> Double,
        tolerateHitOverflow: Boolean = false,
        quiet: Boolean = false,
        deviceOverride: Pair<cl_platform_id, cl_device_id>? = null,
        label: String = "",
        /**
         * Where punted seeds go. When runMulti supplies one, it is a live background pool
         * shared by every device and drained as the search runs; left null, run() makes its
         * own and resolves everything at the end.
         */
        puntSink: PuntResolver? = null,
        /** Shared across devices by runMulti; run() makes its own when given none. */
        dispenser: SeedDispenser? = null,
        /** Called after every chunk with (seeds searched so far, safe resume index). */
        onChunk: ((Long, Long) -> Unit)? = null,
        onHit: (Long, Double) -> Unit,
    ) {
        whyUnsupported(detail, conditions)?.let { throw Unsupported(it) }

        setExceptionsEnabled(true)
        val streams = Streams(maxSearchAnte, detail.editions, detail.souls, detail.tarots, detail.spectrals)
        val nStreams = streams.ids.size

        val (platform, device) = deviceOverride ?: pickDevice()
        val maxComputeUnits = readComputeUnits(device)
        val globalSize = GLOBAL_SIZE.toLong() * maxComputeUnits

        if (!quiet) {
            println("${label}GPU: ${deviceInfo(device, CL_DEVICE_NAME).trim()} (${maxComputeUnits} CUs)")
            println("Streams: $nStreams, prefix slots ${streams.lenSlotCount}, " +
                    "state buffer ${nStreams.toLong() * globalSize * 8 / (1 shl 20)} MB")
        }

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

        // Condition tables. The OR sets are flattened into one array with per-condition
        // offsets, so the device needs no ragged indexing.
        val itemsFlat = ArrayList<Int>()
        val itemOfs = IntArray(conditions.size)
        val itemCnt = IntArray(conditions.size)
        for (i in conditions.indices) {
            itemOfs[i] = itemsFlat.size
            for (item in conditions[i].items) itemsFlat.add(itemCode(item))
            itemCnt[i] = conditions[i].items.size
        }

        val condCount = IntArray(conditions.size) { conditions[it].count }
        val condRequired = IntArray(conditions.size) { if (conditions[it].required) 1 else 0 }
        val condAnteMin = IntArray(conditions.size) { conditions[it].anteMin }
        val condAnteMax = IntArray(conditions.size) { conditions[it].anteMax }
        val condSlotMin = IntArray(conditions.size) { conditions[it].slotMin }
        val condSlotMax = IntArray(conditions.size) { conditions[it].slotMax }
        val condSources = IntArray(conditions.size) { conditions[it].sources }
        val condEdition = IntArray(conditions.size) { editionCode(conditions[it]) }
        val condSlotTarget = IntArray(conditions.size) { conditions[it].slotTarget }
        val condSlotPriority = IntArray(conditions.size) { conditions[it].slotPriority }
        val condAnteTarget = IntArray(conditions.size) { conditions[it].anteTarget }
        val condAntePriority = IntArray(conditions.size) { conditions[it].antePriority }
        val condEditionScore = DoubleArray(conditions.size) { conditions[it].maxEditionScore }

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

        // Order here must match the kernel's parameter list exactly.
        val args = ArrayList<cl_mem>()
        args.add(keep(roBytes(keyChars)))
        args.add(keep(roBytes(keyLens)))
        args.add(keep(roBytes(keySlots)))
        args.add(keep(roInts(itemsFlat.toIntArray())))
        args.add(keep(roInts(itemOfs)))
        args.add(keep(roInts(itemCnt)))
        args.add(keep(roInts(condCount)))
        args.add(keep(roInts(condRequired)))
        args.add(keep(roInts(condAnteMin)))
        args.add(keep(roInts(condAnteMax)))
        args.add(keep(roInts(condSlotMin)))
        args.add(keep(roInts(condSlotMax)))
        args.add(keep(roInts(condSources)))
        args.add(keep(roInts(condEdition)))
        args.add(keep(roInts(condSlotTarget)))
        args.add(keep(roInts(condSlotPriority)))
        args.add(keep(roInts(condAnteTarget)))
        args.add(keep(roInts(condAntePriority)))
        args.add(keep(roDoubles(condEditionScore)))
        args.add(keep(roInts(packFamily)))
        args.add(keep(roInts(packSize)))
        args.add(keep(roDoubles(PoolArr.PACK_CUM)))
        args.add(keep(roBytes(voucherIgnored)))
        args.add(keep(roInts(streams.remap)))
        args.add(keep(roInts(streams.anteEnd)))

        val bState = keep(clCreateBuffer(context, CL_MEM_READ_WRITE,
            nStreams.toLong() * globalSize * Sizeof.cl_double, null, null))
        val bHitIndex = keep(clCreateBuffer(context, CL_MEM_READ_WRITE,
            (MAX_HITS * Sizeof.cl_int).toLong(), null, null))
        val bHitScore = keep(clCreateBuffer(context, CL_MEM_READ_WRITE,
            (MAX_HITS * Sizeof.cl_double).toLong(), null, null))
        val bHitCount = keep(clCreateBuffer(context, CL_MEM_READ_WRITE,
            (2 * Sizeof.cl_int).toLong(), null, null))
        val bOverflow = keep(clCreateBuffer(context, CL_MEM_READ_WRITE,
            (MAX_HITS * Sizeof.cl_int).toLong(), null, null))
        /** The shared seed counter work items pull from. Reset to 0 before every launch. */
        val bWork = keep(clCreateBuffer(context, CL_MEM_READ_WRITE,
            Sizeof.cl_uint.toLong(), null, null))
        args.add(bState); args.add(bHitIndex); args.add(bHitScore); args.add(bHitCount)
        args.add(bOverflow); args.add(bWork)

        val source = buildDefines(conditions, detail, maxSearchAnte, shopItems, streams) + "\n" + loadSource()
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
        if (!quiet) printKernelInfo(kernel, device)

        // CPU mirror, used to verify every hit and to redo anything the device punted.
        val cpuState = MatchState(conditions)
        val cpuSink = MatchSink(cpuState, maxSearchAnte, prune = false)
        val cpuAnalyzer = SeedAnalyzer(detail, ignoredVouchers)
        val seedBuf = CharArray(16)

        /** NaN means the seed cannot be resolved at all; -inf means it fails a requirement. */
        fun cpuScore(index: Long): Double {
            cpuState.reset(detail = false)
            val len = seedForIndex(index, seedBuf)
            cpuAnalyzer.reset(seedBuf, len)
            for (ante in 1..maxSearchAnte) {
                cpuAnalyzer.scanAnte(ante, shopItems, cpuSink)
                if (cpuAnalyzer.aborted) return Double.NaN
                if (cpuState.isComplete) break
            }
            return if (cpuState.requirementsMet) cpuState.total else Double.NEGATIVE_INFINITY
        }

        val hitIndex = IntArray(MAX_HITS)
        val hitScore = DoubleArray(MAX_HITS)
        val overflowIdx = IntArray(MAX_HITS)
        val counts = IntArray(2)

        var done = 0L
        var totalHits = 0L
        var totalOverflow = 0L
        var verified = 0L
        var kernelTotalNs = 0L
        var hostTotalNs = 0L

        // Either the pool runMulti handed us, or a private one we own and shut down here.
        val ownPunts = puntSink == null
        val punts = puntSink ?: PuntResolver(
            conditions, detail, maxSearchAnte, shopItems, ignoredVouchers, cutoffOf, onHit, quiet)
        val started = System.nanoTime()

        val work = dispenser ?: SeedDispenser(startIndex, seedCount)

        while (true) {
            val base = work.take()
            if (base < 0) break
            val chunk = work.sizeOf(base)

            clEnqueueWriteBuffer(queue, bHitCount, CL_TRUE, 0, (2 * Sizeof.cl_int).toLong(),
                Pointer.to(intArrayOf(0, 0)), 0, null, null)
            // Hand the work queue back to seed 0 of this chunk.
            clEnqueueWriteBuffer(queue, bWork, CL_TRUE, 0, Sizeof.cl_uint.toLong(),
                Pointer.to(intArrayOf(0)), 0, null, null)

            var a = 0
            for (b in args) clSetKernelArg(kernel, a++, Sizeof.cl_mem.toLong(), Pointer.to(b))
            clSetKernelArg(kernel, a++, Sizeof.cl_ulong.toLong(), Pointer.to(longArrayOf(base)))
            clSetKernelArg(kernel, a++, Sizeof.cl_int.toLong(), Pointer.to(intArrayOf(chunk)))
            clSetKernelArg(kernel, a++, Sizeof.cl_int.toLong(), Pointer.to(intArrayOf(nStreams)))
            clSetKernelArg(kernel, a++, Sizeof.cl_double.toLong(), Pointer.to(doubleArrayOf(cutoffOf())))
            clSetKernelArg(kernel, a, Sizeof.cl_int.toLong(), Pointer.to(intArrayOf(MAX_HITS)))

            val tKernel = System.nanoTime()
            clEnqueueNDRangeKernel(queue, kernel, 1, null,
                longArrayOf(globalSize), longArrayOf(LOCAL_SIZE), 0, null, null)
            clFinish(queue)
            val kernelNs = System.nanoTime() - tKernel
            val tHost = System.nanoTime()

            clEnqueueReadBuffer(queue, bHitCount, CL_TRUE, 0, (2 * Sizeof.cl_int).toLong(),
                Pointer.to(counts), 0, null, null)
            val nHits = minOf(counts[0], MAX_HITS)
            val nOver = minOf(counts[1], MAX_HITS)

            if (counts[0] > MAX_HITS) {
                val msg = "${counts[0]} hits in one chunk exceeded the $MAX_HITS buffer " +
                        "(${"%.2f".format(100.0 * counts[0] / chunk)}% of seeds matching)"
                check(tolerateHitOverflow) {
                    "$msg, so ${counts[0] - MAX_HITS} seeds were dropped. This is a threshold " +
                            "problem, not a buffer problem: raise the cutoff, or tighten a required condition."
                }
                if (!quiet) println("  (calibration sample truncated: $msg)")
            }

            if (nHits > 0) {
                clEnqueueReadBuffer(queue, bHitIndex, CL_TRUE, 0, (nHits * Sizeof.cl_int).toLong(),
                    Pointer.to(hitIndex), 0, null, null)
                clEnqueueReadBuffer(queue, bHitScore, CL_TRUE, 0, (nHits * Sizeof.cl_double).toLong(),
                    Pointer.to(hitScore), 0, null, null)
                for (i in 0 until nHits) {
                    val idx = base + hitIndex[i]

                    // Verification is sampled, not exhaustive.
                    //
                    // Re-running a seed on the CPU costs far more than the device spent
                    // finding it, and it happens between launches with the whole card
                    // waiting. Checking the first VERIFY_PER_CHUNK hits keeps the safety
                    // net -- a kernel that has gone wrong is wrong on essentially every
                    // seed, so it is caught in the first chunk -- without paying for it on
                    // every hit of a long run.
                    if (i < VERIFY_PER_CHUNK) {
                        val host = cpuScore(idx)
                        if (host.isNaN()) {
                            if (!quiet) println("WARNING: seed ${seedForIndex(idx)} is unresolvable on the CPU; dropped")
                            continue
                        }
                        check(host == hitScore[i]) {
                            "device and host disagree on seed ${seedForIndex(idx)} (index $idx): " +
                                    "device ${hitScore[i]}, host $host"
                        }
                        verified++
                    }
                    onHit(idx, hitScore[i])
                }
                totalHits += nHits
            }

            // Seeds the device could not finish -- a resample past MAX_RESAMPLE, or a
            // stream outside the compacted table.
            //
            // Only their indices are collected here. Re-running them costs a full CPU scan
            // each, and at a couple of thousand per chunk that was the entire host cost and
            // the whole card sat idle through it. They are redone in parallel after the
            // search instead; at a few thousandths of a percent of seeds, nothing depends
            // on them landing before the run ends.
            if (nOver > 0) {
                clEnqueueReadBuffer(queue, bOverflow, CL_TRUE, 0, (nOver * Sizeof.cl_int).toLong(),
                    Pointer.to(overflowIdx), 0, null, null)
                for (i in 0 until nOver) punts.submit(base + overflowIdx[i])
                totalOverflow += nOver
            }

            done += chunk
            val globalDone = work.complete(base, chunk)
            onChunk?.invoke(globalDone, work.watermark())
            val hostNs = System.nanoTime() - tHost
            kernelTotalNs += kernelNs
            hostTotalNs += hostNs
            if (!quiet) {
                val elapsed = (System.nanoTime() - started) / 1e9
                // kernel vs host is the number that matters when occupancy sags: if host
                // is a meaningful share, the device is idling while this thread works, and
                // the fix is to do less here or to overlap it with the next launch.
                // backlog is the punt queue's depth: if it climbs steadily the resolver
                // pool cannot keep up and punts are outrunning the CPU.
                // "done" is this device's own progress; globalDone is every device's.
                println((label + "%,d / %,d seeds | %,.0f seeds/s | %,d hits (%,d verified) " +
                        "| %,d punted (%,d queued) | kernel %,d ms, host %,d ms | cutoff %s")
                    .format(globalDone, work.total, done / elapsed, totalHits, verified, totalOverflow,
                        punts.backlog(), kernelNs / 1_000_000, hostNs / 1_000_000, fmtCutoff(cutoffOf())))
            }
        }

        if (ownPunts) punts.shutdown()

        if (!quiet) {
            val k = kernelTotalNs / 1_000_000
            val h = hostTotalNs / 1_000_000
            val pct = if (k + h > 0) 100.0 * h / (k + h) else 0.0
            println(label + "Totals: kernel %,d ms, host %,d ms (host is %.1f%% of wall time)".format(k, h, pct))
        }

        bufs.forEach { clReleaseMemObject(it) }
        clReleaseKernel(kernel)
        clReleaseProgram(program)
        clReleaseCommandQueue(queue)
        clReleaseContext(context)
    }

    /**
     * Resolves punted seeds on CPU threads while the search runs.
     *
     * A punt is a seed whose resample chain ran past what the compacted stream table
     * covers, so the device gives up and the host has to redo it exactly. At a few
     * thousandths of a percent that is still a couple of thousand per chunk, and each one
     * is a full CPU scan -- doing them inline made the host the bottleneck and left the
     * card idle for a fifth of every chunk.
     *
     * Here they go into a bounded queue that a pool of threads drains continuously. The
     * queue is bounded so a pathological punt rate applies backpressure instead of eating
     * the heap; in normal operation it never fills.
     *
     * If the punt count is a large fraction of a percent rather than a few thousandths, the
     * cause is the resample caps in Streams.add -- raising packDedup trades a larger stream
     * table for fewer punts.
     */
    class PuntResolver(
        private val conditions: Array<Condition>,
        private val detail: Detail,
        private val maxSearchAnte: Int,
        private val shopItems: Int,
        private val ignoredVouchers: List<String>,
        private val cutoffOf: () -> Double,
        private val onHit: (Long, Double) -> Unit,
        private val quiet: Boolean,
        threadCount: Int = maxOf(1, Runtime.getRuntime().availableProcessors() - 1),
    ) {
        private val queue = java.util.concurrent.LinkedBlockingQueue<Long>(1 shl 16)
        private val done = java.util.concurrent.atomic.AtomicLong()
        private val unresolvable = java.util.concurrent.atomic.AtomicLong()
        @Volatile private var stopping = false

        /** Sentinel: Long.MIN_VALUE is not a valid seed index. */
        private val poison = Long.MIN_VALUE

        private val threads = (0 until threadCount).map {
            Thread {
                // Each thread owns its analyzer and match state; they are stateful and
                // reused per seed, which is the whole reason a scan is cheap.
                val state = MatchState(conditions)
                val sink = MatchSink(state, maxSearchAnte, prune = false)
                val analyzer = SeedAnalyzer(detail, ignoredVouchers)
                val buf = CharArray(16)

                while (true) {
                    val idx = queue.take()
                    if (idx == poison) break

                    state.reset(detail = false)
                    val len = seedForIndex(idx, buf)
                    analyzer.reset(buf, len)
                    var aborted = false
                    for (ante in 1..maxSearchAnte) {
                        analyzer.scanAnte(ante, shopItems, sink)
                        if (analyzer.aborted) { aborted = true; break }
                        if (state.isComplete) break
                    }
                    done.incrementAndGet()
                    if (aborted) { unresolvable.incrementAndGet(); continue }
                    if (!state.requirementsMet) continue
                    if (state.total > cutoffOf()) onHit(idx, state.total)
                }
            }.apply { isDaemon = true; name = "punt-resolver-$it"; start() }
        }

        fun submit(seedIndex: Long) {
            if (stopping) return
            queue.put(seedIndex)
        }

        /** Drains the queue, stops the threads, and reports. */
        fun shutdown() {
            stopping = true
            repeat(threads.size) { queue.put(poison) }
            threads.forEach { it.join() }
            if (!quiet && done.get() > 0) {
                println("Punt resolver: ${"%,d".format(done.get())} seeds redone on CPU" +
                        if (unresolvable.get() > 0) ", ${unresolvable.get()} had no terminating draw and were dropped" else "")
            }
        }

        /** How far behind the queue is, for the progress line. */
        fun backlog(): Int = queue.size
    }

    // --- multiple devices -------------------------------------------------------

    /**
     * Runs the search across every usable GPU, one host thread per device.
     *
     * Devices share one [SeedDispenser] rather than getting a pre-assigned slice, so a
     * fast card simply pulls more blocks and no relative-speed estimate is needed. They
     * also share the punt resolver, the cutoff and the results heap -- all thread-safe --
     * so a hit found on one device raises the bar for the others mid-run.
     */
    fun runMulti(
        conditions: Array<Condition>,
        detail: Detail,
        maxSearchAnte: Int,
        shopItems: Int,
        ignoredVouchers: List<String>,
        startIndex: Long,
        seedCount: Long,
        cutoffOf: () -> Double,
        devices: List<Pair<cl_platform_id, cl_device_id>>? = null,
        onChunk: ((Long, Long) -> Unit)? = null,
        onHit: (Long, Double) -> Unit,
    ) {
        println("Scanning for GPUs:")
        val found = devices ?: listDevices(verbose = true)
        if (found.isEmpty()) throw Unsupported("no usable OpenCL device with cl_khr_fp64")

        if (found.size == 1) {
            run(conditions, detail, maxSearchAnte, shopItems, ignoredVouchers,
                startIndex, seedCount, cutoffOf, onChunk = onChunk, onHit = onHit)
            return
        }

        println("Using ${found.size} GPUs, pulling ${"%,d".format(CHUNK)}-seed blocks on demand:")
        found.forEachIndexed { i, d ->
            println("  [gpu$i] ${deviceInfo(d.second, CL_DEVICE_NAME).trim()} (${readComputeUnits(d.second)} CUs)")
        }

        val work = SeedDispenser(startIndex, seedCount)

        // One shared resolver: punts are rare, and a pool per device would oversubscribe
        // the CPU for no gain.
        val punts = PuntResolver(conditions, detail, maxSearchAnte, shopItems,
            ignoredVouchers, cutoffOf, onHit, quiet = false)

        val failures = java.util.Collections.synchronizedList(ArrayList<Throwable>())
        val threads = found.mapIndexed { i, dev ->
            Thread {
                try {
                    run(conditions, detail, maxSearchAnte, shopItems, ignoredVouchers,
                        startIndex, seedCount, cutoffOf, deviceOverride = dev, label = "[gpu$i] ",
                        puntSink = punts, dispenser = work, onChunk = onChunk, onHit = onHit)
                } catch (t: Throwable) {
                    failures.add(t)
                    println("[gpu$i] FAILED: ${t.message}")
                }
            }.apply { name = "gpu-$i"; start() }
        }
        threads.forEach { it.join() }
        punts.shutdown()

        // A device dying silently would look like a finished search that scanned a
        // fraction of the range, which is worse than a crash. The others keep going -- the
        // dispenser reassigns their blocks -- but the run must not report success.
        if (failures.isNotEmpty()) throw failures.first()
    }

}