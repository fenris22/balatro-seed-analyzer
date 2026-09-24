package analyzer

import executor.Task
import executor.TaskManager
import kotlinx.coroutines.*
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration.Companion.milliseconds

/**
 * Everything one search needs. Built from the command line plus a conditions file, or from
 * the web page; both end up in [runSearch], so the two can never search differently.
 */
class SearchConfig(
    val conditions: Array<Condition>,
    val ignoredVouchers: List<String>,
    val options: RunOptions,
)

/** Asks a running search to stop at the next chunk (GPU) or batch (CPU). */
object SearchControl {
    @Volatile
    var stopRequested = false
}

/**
 * Live view of the current run, for the web page. Written by the search, read by the
 * server; every field is a single volatile value, so a reader never blocks the search.
 */
@OptIn(ExperimentalAtomicApi::class)
object RunStatus {
    /** idle, planning, calibrating, searching, finishing, done, stopped, failed */
    @Volatile var phase = "idle"
    @Volatile var message: String? = null
    @Volatile var runId = 0
    @Volatile var startedAtMs = 0L
    @Volatile var finishedAtMs = 0L
    @Volatile var startIndex = 0L
    @Volatile var total = 0L
    @Volatile var searched = 0L
    @Volatile var resumeIndex = 0L
    @Volatile var device = ""
    @Volatile var gpuNote: String? = null
    @Volatile var hasRequired = true

    /** Calls to record(): every seed that met the requirements and beat the live cutoff. */
    val hits = java.util.concurrent.atomic.LongAdder()

    /**
     * The matching-seed estimate. While the cutoff is open, every seed that meets the
     * required conditions is a hit, so hits / seeds searched is an exact rate. Once the
     * cutoff rises that stops being true, so the sample freezes at that point.
     */
    @Volatile var sampleSeeds = 0L
    @Volatile var sampleHits = 0L

    /** The GPU calibration pass, which always runs with the cutoff open. */
    @Volatile var calibrationSeeds = 0L
    @Volatile var calibrationHits = 0L

    private val window = ArrayDeque<LongArray>()

    fun begin(total: Long, start: Long, hasRequired: Boolean) {
        runId++
        phase = "planning"; message = null
        startedAtMs = System.currentTimeMillis(); finishedAtMs = 0L
        startIndex = start; this.total = total; searched = 0L; resumeIndex = start
        device = ""; gpuNote = null; this.hasRequired = hasRequired
        hits.reset(); sampleSeeds = 0; sampleHits = 0; calibrationSeeds = 0; calibrationHits = 0
        synchronized(window) { window.clear() }
    }

    /** Called after every GPU chunk and every CPU progress tick. */
    fun progress(searchedNow: Long, resume: Long) {
        searched = searchedNow
        resumeIndex = resume
        if (cutoff == Double.NEGATIVE_INFINITY) {
            sampleSeeds = searchedNow
            sampleHits = hits.sum()
        }
        val now = System.currentTimeMillis()
        synchronized(window) {
            window.addLast(longArrayOf(now, searchedNow))
            while (window.size > 2 && now - window.first()[0] > 20_000) window.removeFirst()
        }
    }

    /** Seeds per second over roughly the last 20 seconds. */
    fun speed(): Double = synchronized(window) {
        if (window.size < 2) {
            val el = (System.currentTimeMillis() - startedAtMs) / 1000.0
            return if (el > 0 && searched > 0) searched / el else 0.0
        }
        val a = window.first(); val b = window.last()
        val dt = (b[0] - a[0]) / 1000.0
        if (dt <= 0) 0.0 else (b[1] - a[1]) / dt
    }

    val running: Boolean get() = phase in setOf("planning", "calibrating", "searching", "finishing")
}

/** What the shutdown hook needs to save a run that is interrupted. */
private class ActiveRun(
    val conditions: Array<Condition>,
    val detail: Detail,
    val maxSearchAnte: Int,
    val shopItems: Int,
    val ignoredVouchers: List<String>,
) {
    val lastSearched = java.util.concurrent.atomic.AtomicLong(0)
    val lastProgress = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile var finished = false
}

@Volatile
private var activeRun: ActiveRun? = null

private val hookInstalled = java.util.concurrent.atomic.AtomicBoolean(false)

/**
 * Ctrl-C, SIGTERM, or the machine going down mid-run. Without this the results only ever
 * exist in memory and hours of searching evaporate. Installed once; saves whichever run is
 * active at the time.
 */
private fun installShutdownHook() {
    if (!hookInstalled.compareAndSet(false, true)) return
    Runtime.getRuntime().addShutdownHook(Thread {
        val run = activeRun ?: return@Thread
        if (run.finished) return@Thread
        println()
        checkpoint(run.conditions, run.detail, run.maxSearchAnte, run.shopItems, run.ignoredVouchers,
            run.lastSearched.get(), run.lastProgress.get(), "interrupted")
    })
}

/** Shop slots generated per ante. */
const val SHOP_ITEMS = 100

/**
 * The details a web page or a results file needs, filled in for results that do not have
 * them yet. One filter rescan per seed; cheap enough to run on demand.
 */
fun hydrateSummaries(results: List<SeedResult>) {
    val run = activeRun ?: return
    val todo = results.filter { it.summary.isEmpty() }
    if (todo.isEmpty()) return
    val state = MatchState(run.conditions)
    val sink = MatchSink(state, run.maxSearchAnte, prune = false)
    val filter = SeedAnalyzer(run.detail, run.ignoredVouchers)
    for (r in todo) {
        state.reset(detail = true)
        filter.reset(r.seed)
        for (ante in 1..run.maxSearchAnte) {
            filter.scanAnte(ante, run.shopItems, sink)
            if (filter.aborted) break
            if (state.isComplete) break
        }
        r.matches = state.snapshot()
        r.summary = state.summary()
    }
}

/**
 * Runs one search from start to finish: planning, calibration, the GPU or CPU pass, and
 * the final results. Blocks until done. Returns the results, best first.
 *
 * Only one search may run at a time: results, the cutoff and the counters are shared
 * (they are read on the hot path, where passing them around would cost speed).
 */
@OptIn(ExperimentalAtomicApi::class)
fun runSearch(config: SearchConfig): List<SeedResult> {
    val start = System.currentTimeMillis()
    val opts = config.options
    val conditions = config.conditions
    val ignoredVouchers = config.ignoredVouchers
    val shopItems = SHOP_ITEMS

    // --- fresh shared state ---
    SearchControl.stopRequested = false
    maxResults = opts.maxResults
    resetResults()
    Stats.seeds.store(0); Stats.antes.store(0); Stats.shopRolls.store(0)
    Stats.killedByRequirement.store(0)

    ClSearch.GLOBAL_SIZE = opts.globalSize
    ClSearch.LOCAL_SIZE = opts.localSize.toLong()
    ClSearch.CHUNK = opts.chunk
    val useGpu = opts.useGpu
    val startIndex = opts.startIndex
    val seedsToCount = opts.seedsToCount
    val checkpointEvery = opts.resolvedCheckpoint
    val calibrationSeeds = opts.calibrationSeeds
    val endIndex = startIndex + seedsToCount

    RunStatus.begin(seedsToCount, startIndex, conditions.any { it.required })

    println("Settings: seeds ${"%,d".format(startIndex)} to ${"%,d".format(opts.endIndex)} " +
            "(${"%,d".format(seedsToCount)}), max results $maxResults, " +
            (if (checkpointEvery > 0) "checkpoint every ${"%,d".format(checkpointEvery)}, " else "checkpoints off, ") +
            "calibration ${"%,d".format(calibrationSeeds)}, " +
            (if (useGpu) "GPU global ${opts.globalSize}/CU, local ${opts.localSize}, chunk ${"%,d".format(opts.chunk)}"
            else "CPU only"))
    println("Skipping vouchers: ${ignoredVouchers.joinToString(", ").ifEmpty { "none" }}")

    val detail = Detail.forItems(
        conditions.flatMap { it.items },
        needEditions = conditions.any { it.editionTarget != null },
    )
    val maxSearchAnte = conditions.maxOf { it.anteMax }

    describeConditions(conditions, maxSearchAnte).forEach(::println)
    println("Generating: $detail")
    warnUnsatisfiable(conditions).forEach { println("WARNING: $it") }

    val run = ActiveRun(conditions, detail, maxSearchAnte, shopItems, ignoredVouchers)
    run.lastProgress.set(startIndex)
    activeRun = run
    installShutdownHook()

    fun finish(reason: String, searched: Long, resume: Long): List<SeedResult> {
        RunStatus.phase = "finishing"
        hydrate(snapshotResults(), conditions, detail, maxSearchAnte, shopItems, ignoredVouchers)
        checkpoint(conditions, detail, maxSearchAnte, shopItems, ignoredVouchers, searched, resume, reason)
        run.finished = true
        printResults(shopItems, start)
        RunStatus.searched = searched
        RunStatus.resumeIndex = resume
        RunStatus.finishedAtMs = System.currentTimeMillis()
        RunStatus.phase = if (reason == "stopped") "stopped" else "done"
        if (reason == "stopped") {
            println("Stopped. To carry on later, start again from seed index $resume.")
        }
        return snapshotResults()
    }

    // Orders the cheap pack/Soul-only requirement checks so the most selective per unit of
    // work runs first. Sampled on the CPU; takes well under a second.
    val stages = SearchPlanner.plan(conditions, ignoredVouchers, startIndex)

    // -----------------------------------------------------------------------
    // GPU path
    // -----------------------------------------------------------------------
    if (useGpu) ClSearch.whyUnsupported(detail, conditions)?.let {
        println("GPU not used: $it. Running on the CPU, which produces identical results more slowly.")
        RunStatus.gpuNote = "GPU not used: $it"
    }

    if (useGpu && ClSearch.supports(detail, conditions)) {
        var nextCheckpointAt = checkpointEvery
        val progress: (Long, Long) -> Unit = { searched, resumeIndex ->
            run.lastSearched.set(searched)
            run.lastProgress.set(resumeIndex)
            RunStatus.progress(searched, resumeIndex)
            if (checkpointEvery > 0 && searched >= nextCheckpointAt) {
                // Step past every boundary already crossed, so a chunk larger than the
                // interval does not queue up a run of back-to-back checkpoints.
                while (nextCheckpointAt <= searched) nextCheckpointAt += checkpointEvery
                checkpoint(conditions, detail, maxSearchAnte, shopItems, ignoredVouchers,
                    searched, resumeIndex, "checkpoint")
            }
        }

        try {
            RunStatus.device = "GPU"
            // --- calibration ---
            if (calibrationSeeds > 0 && CALIBRATION_ROUNDS > 0) {
                RunStatus.phase = "calibrating"
                val sample = ArrayList<Double>()
                var sampled = 0L
                for (round in 0 until CALIBRATION_ROUNDS) {
                    if (SearchControl.stopRequested) break
                    // Spread the rounds across the range so one unusual stretch does not
                    // set the bar for the whole run.
                    val from = startIndex + (seedsToCount / CALIBRATION_ROUNDS) * round
                    val n = minOf(calibrationSeeds, endIndex - from)
                    ClSearch.run(
                        conditions, detail, maxSearchAnte, shopItems, ignoredVouchers,
                        from, n,
                        cutoffOf = { Double.NEGATIVE_INFINITY },
                        tolerateHitOverflow = true,
                        quiet = true,
                        stages = stages,
                    ) { _, score -> synchronized(sample) { sample.add(score) } }
                    sampled += n
                    RunStatus.calibrationSeeds = sampled
                    RunStatus.calibrationHits = sample.size.toLong()
                }
                if (SearchControl.stopRequested) return finish("stopped", 0L, startIndex)
                cutoff = calibrateCutoff(sample, sampled, seedsToCount, calibrationTargetHits)
            }

            // --- the run ---
            RunStatus.phase = "searching"
            val onHit: (Long, Double) -> Unit = { index, score ->
                // Deliberately nothing but a heap insert. Anything heavier here runs
                // between kernel launches, with the whole device waiting on it.
                record(seedForIndex(index), score)
            }
            if (USE_ALL_GPUS) {
                ClSearch.runMulti(
                    conditions, detail, maxSearchAnte, shopItems, ignoredVouchers,
                    startIndex, seedsToCount, cutoffOf = { cutoff },
                    stages = stages, onChunk = progress, onHit = onHit)
            } else {
                ClSearch.run(
                    conditions, detail, maxSearchAnte, shopItems, ignoredVouchers,
                    startIndex, seedsToCount, cutoffOf = { cutoff },
                    stages = stages, onChunk = progress, onHit = onHit)
            }
            return if (SearchControl.stopRequested) {
                val searched = run.lastSearched.get()
                Stats.seeds.addAndFetch(searched)
                finish("stopped", searched, run.lastProgress.get())
            } else {
                Stats.seeds.addAndFetch(seedsToCount)
                finish("final", seedsToCount, endIndex)
            }
        } catch (e: Throwable) {
            // Unsupported: no usable device. LinkageError: no OpenCL driver installed at all,
            // so JOCL cannot even load. Either way the CPU gives the same answers, slower.
            if (e !is ClSearch.Unsupported && e !is LinkageError) throw e
            val why = if (e is LinkageError) "no OpenCL driver found" else e.message
            println("GPU unavailable ($why); falling back to the CPU path.")
            RunStatus.gpuNote = "GPU unavailable: $why"
            // Calibration may have been cut short; start the CPU pass from a clean slate.
            resetResults()
        }
    }

    // -----------------------------------------------------------------------
    // CPU path
    // -----------------------------------------------------------------------
    val workerCount = Runtime.getRuntime().availableProcessors()
    RunStatus.device = "CPU ($workerCount threads)"
    RunStatus.phase = "searching"
    Stats.nextIndex.store(startIndex)

    /** Lowest index not yet fully searched: batches finish out of order across threads. */
    val inFlight = java.util.concurrent.ConcurrentSkipListSet<Long>()
    fun watermark(): Long = inFlight.firstOrNull() ?: minOf(Stats.nextIndex.load(), endIndex)

    runBlocking {
        TaskManager.submit(Task {
            // Not on Dispatchers.Default: the workers fill every one of its threads and never
            // suspend, so a monitor there would not run until the search was over.
            val monitor = launch(Dispatchers.IO) {
                var lastSeeds = 0L
                var lastNanos = System.nanoTime()
                var tick = 0
                var nextCheckpointAt = checkpointEvery
                while (isActive) {
                    delay(1000.milliseconds)
                    val seeds = Stats.seeds.load()
                    val resume = watermark()
                    run.lastSearched.set(seeds)
                    run.lastProgress.set(resume)
                    RunStatus.progress(seeds, resume)
                    if (checkpointEvery > 0 && seeds >= nextCheckpointAt) {
                        while (nextCheckpointAt <= seeds) nextCheckpointAt += checkpointEvery
                        checkpoint(conditions, detail, maxSearchAnte, shopItems, ignoredVouchers,
                            seeds, resume, "checkpoint")
                    }
                    if (++tick % 5 != 0) continue
                    val now = System.nanoTime()
                    val dt = (now - lastNanos) / 1e9
                    val killed = Stats.killedByRequirement.load()
                    println(
                        "%,d / %,d seeds | %,.0f seeds/s | %,d killed by requirement | cutoff %s"
                            .format(seeds, seedsToCount, (seeds - lastSeeds) / dt, killed, fmtCutoff(cutoff))
                    )
                    lastSeeds = seeds; lastNanos = now
                }
            }

            val workers = List(workerCount) {
                launch(Dispatchers.Default) {
                    val worker = Worker(conditions, maxSearchAnte, detail, ignoredVouchers, shopItems, stages)
                    while (!SearchControl.stopRequested) {
                        val from = Stats.nextIndex.fetchAndAdd(SEED_BATCH.toLong())
                        if (from >= endIndex) break
                        inFlight.add(from)
                        val to = minOf(from + SEED_BATCH, endIndex)
                        for (i in from until to) worker.analyze(i)
                        worker.flushStats()
                        inFlight.remove(from)
                    }
                }
            }

            workers.joinAll()
            monitor.cancel()
        }).join()
    }

    val searched = Stats.seeds.load()
    RunStatus.progress(searched, watermark())
    return if (SearchControl.stopRequested) finish("stopped", searched, watermark())
    else finish("final", searched, endIndex)
}
