package analyzer

import analyzer.Util.editionFromDisplayName
import analyzer.Util.jokerFromDisplayName
import analyzer.Util.tarotFromDisplayName
import executor.Task
import executor.TaskManager
import kotlinx.coroutines.*
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration.Companion.milliseconds

// ---------------------------------------------------------------------------
// Tunables
// ---------------------------------------------------------------------------

/** Only the best MAX_RESULTS seeds are retained, so memory stays flat over a long run. */
const val MAX_RESULTS = 200

/** Seed indices claimed per atomic operation (CPU path). */
const val SEED_BATCH = 512

/** Use the OpenCL kernel when the conditions are within what it covers. */
const val USE_GPU = true

/**
 * Split the range across every fp64 GPU on the machine.
 *
 * Set false to pin the search to the first device, which is what you want when something
 * is misbehaving and you need one device's numbers in isolation.
 */
const val USE_ALL_GPUS = true

// --- checkpointing ---

/**
 * Write the current best seeds every this many seeds searched. 0 disables.
 *
 * A long search is worth interrupting -- results only exist in memory otherwise, and a
 * Ctrl-C or a machine reboot loses hours. Each checkpoint overwrites the file with the
 * full current top list, so the file is always complete rather than an append log to
 * reassemble.
 */
const val CHECKPOINT_EVERY_SEEDS = 100_000_000L

/** Where checkpoints are written. Overwritten each time, not appended. */
const val RESULTS_FILE = "results.txt"

/** How many of the top seeds each checkpoint prints to the console. */
const val CHECKPOINT_PRINT_TOP = 5

// --- threshold calibration ---

/**
 * Seeds sampled before the real run to pick a starting cutoff.
 *
 * The cutoff is self-tuning either way: once MAX_RESULTS results are banked, it rises to
 * the weakest one retained and keeps rising. Calibration only buys the *start* of the run
 * -- without it the first few hundred million seeds are scanned with a cutoff of negative
 * infinity, which means no score pruning at all. With required conditions doing the heavy
 * filtering that matters less than it used to, so this is a modest win, not a critical one.
 */
const val CALIBRATION_SEEDS = 4_000_000L

/** How many times the calibration sample is repeated, to smooth out a lucky stretch. */
const val CALIBRATION_ROUNDS = 2

/**
 * How many results the projected run should produce. Set well above MAX_RESULTS: the
 * heap discards the excess, and aiming too tight risks a cutoff that starves it.
 */
const val CALIBRATION_TARGET_HITS = MAX_RESULTS * 20

// ---------------------------------------------------------------------------
// Seeds
// ---------------------------------------------------------------------------

/** Same 34-character alphabet Util.nextSeed walks (no 0, no O). */
private const val SEED_CHARS = "123456789ABCDEFGHIJKLMNPQRSTUVWXYZ"
private const val SEED_BASE = 34L

/**
 * Writes the n'th seed of the enumeration into [out], returning its length.
 *
 * Deriving the seed from its index means each worker generates its own into a reusable
 * buffer, nothing is allocated, and a run is resumable from any index.
 */
fun seedForIndex(index: Long, out: CharArray): Int {
    var n = index
    var len = 1
    var block = SEED_BASE
    while (n >= block) {
        n -= block
        len++
        block *= SEED_BASE
    }
    for (i in len - 1 downTo 0) {
        out[i] = SEED_CHARS[(n % SEED_BASE).toInt()]
        n /= SEED_BASE
    }
    return len
}

fun seedForIndex(index: Long): String {
    val buf = CharArray(16)
    return String(buf, 0, seedForIndex(index, buf))
}

// ---------------------------------------------------------------------------
// Feeding the generator into the matcher
// ---------------------------------------------------------------------------

/**
 * Feeds a scan into a [MatchState] and decides when to abandon the seed.
 *
 * Every card type is offered with the source it came from, so a condition restricted to
 * "Shop only" or "Soul only" needs no new plumbing here -- the window does the filtering.
 */
class MatchSink(
    val state: MatchState,
    private val maxSearchAnte: Int,
    /**
     * Set false for verification scans.
     *
     * Pruning reads the live cutoff, which rises as results are recorded, so a scan run to
     * re-check an earlier result would be cut short by a bar that did not exist when that
     * result was produced. A genuine hit never had its bound fall to or below the cutoff
     * before completing, so an unpruned scan reaches the same matches at the same
     * positions and stops at completion either way.
     */
    private val prune: Boolean = true,
) : ScanSink() {
    override fun wantMoreShop(ante: Int, slot: Int): Boolean {
        if (state.isComplete) return false
        if (!prune) return true
        // The requirement kill first: it is cheaper than the bound and does not depend on
        // the cutoff being well tuned.
        state.closeSlot(ante, slot)
        if (state.dead) return false
        return state.upperBound(ante, slot) > cutoff
    }

    override fun onShopItem(ante: Int, slot: Int, kind: String, item: Item, rarity: String?, edition: Item?) =
        state.offer(item, edition, ante, slot, Src.SHOP)

    override fun onPackJoker(ante: Int, slot: Int, item: Item, rarity: String, edition: Item?) =
        state.offer(item, edition, ante, slot, Src.PACK)

    /** Consumables carry no edition, so only an editionless condition can take one. */
    override fun onPackConsumable(ante: Int, slot: Int, family: String, item: Item) =
        state.offer(item, null, ante, slot, Src.PACK)

    override fun onPackCard(ante: Int, slot: Int, card: StandardCard) =
        state.offer(card.base, card.edition, ante, slot, Src.PACK)

    /**
     * A Soul's joker is matched on the Soul's ordinal, not a pack position: slot 1 means
     * "the first Soul of the run gives me this".
     */
    override fun onSoulJoker(ante: Int, soulIndex: Int, item: Item, edition: Item?) =
        state.offer(item, edition, ante, soulIndex, Src.SOUL)

    override fun onVoucher(ante: Int, item: Item) = state.offer(item, null, ante, 1, Src.VOUCHER)
    override fun onTag(ante: Int, index: Int, item: Item) = state.offer(item, null, ante, index + 1, Src.TAG)
    override fun onBoss(ante: Int, item: Item) = state.offer(item, null, ante, 1, Src.BOSS)
}

// ---------------------------------------------------------------------------
// Result collection
// ---------------------------------------------------------------------------

/**
 * A retained seed.
 *
 * Only [seed] and [score] are known while the search runs. Everything else is filled in by
 * [hydrate] once the run is over, because building it eagerly was the single most
 * expensive thing on the host: two filter rescans plus a full-detail report for every ante
 * -- generating standard cards, planets, tags and bosses nobody asked for -- on every hit,
 * the overwhelming majority of which the heap then discarded. On a fast device that work
 * ran between kernel launches and left the GPU idling through it.
 */
class SeedResult(val seed: String, val score: Double) {
    var matches: List<Match> = emptyList()
    var summary: String = ""
    var reports: List<AnteReport> = emptyList()
}

/**
 * A plain lock, not a coroutine Mutex.
 *
 * record() is called once per hit from the GPU driver, which is not a coroutine, and
 * wrapping each call in runBlocking to satisfy a suspend signature spun up a dispatcher
 * per hit. The lock is essentially never contended -- the volatile cutoff check in front
 * rejects almost everything before reaching it -- so blocking briefly costs nothing.
 */
private val resultsLock = java.util.concurrent.locks.ReentrantLock()
private val topResults = java.util.PriorityQueue<SeedResult>(MAX_RESULTS + 1, compareBy { it.score })

/**
 * Read once per shop slot, so it stays a plain volatile read rather than a lock.
 *
 * Starts at negative infinity, not at a fraction of the maximum: with required conditions
 * the gate does the filtering, and a guessed starting threshold that is too high silently
 * discards every result. Calibration raises it from evidence, and the heap keeps raising
 * it as results bank.
 */
@Volatile
var cutoff = Double.NEGATIVE_INFINITY

/**
 * A consistent copy of the current best, highest first.
 *
 * Taken under the lock because a checkpoint can fire while devices are still recording.
 */
fun snapshotResults(): List<SeedResult> {
    resultsLock.lock()
    try {
        return topResults.sortedByDescending { it.score }
    } finally {
        resultsLock.unlock()
    }
}

/** Cheap by design: a volatile compare, and a heap insert only when it passes. */
fun record(seed: String, score: Double) {
    if (score <= cutoff) return
    resultsLock.lock()
    try {
        if (score <= cutoff) return
        topResults.add(SeedResult(seed, score))
        if (topResults.size > MAX_RESULTS) {
            topResults.poll()
            cutoff = topResults.peek().score
        }
    } finally {
        resultsLock.unlock()
    }
}

/**
 * Fills in match detail and full reports for the seeds that survived.
 *
 * Runs once, on at most MAX_RESULTS seeds, so it can afford to be as slow as it likes.
 */
private fun hydrate(
    results: List<SeedResult>,
    conditions: Array<Condition>,
    detail: Detail,
    maxSearchAnte: Int,
    shopItems: Int,
    ignoredVouchers: List<String>,
) {
    val state = MatchState(conditions)
    val sink = MatchSink(state, maxSearchAnte, prune = false)
    val filter = SeedAnalyzer(detail, ignoredVouchers)
    val full = SeedAnalyzer(Detail.FULL, ignoredVouchers)

    for (r in results) {
        state.reset(detail = true)
        filter.reset(r.seed)
        var antesRun = 0
        for (ante in 1..maxSearchAnte) {
            antesRun = ante
            filter.scanAnte(ante, shopItems, sink)
            if (filter.aborted) break
            if (state.isComplete) break
        }
        r.matches = state.snapshot()
        r.summary = state.summary()
        full.reset(r.seed)
        r.reports = (1..antesRun).map { full.ante(it, shopItems) }
    }
}

// ---------------------------------------------------------------------------
// Instrumentation
// ---------------------------------------------------------------------------

@OptIn(ExperimentalAtomicApi::class)
object Stats {
    val seeds = AtomicLong(0)
    val antes = AtomicLong(0)
    val shopRolls = AtomicLong(0)
    val killedByRequirement = AtomicLong(0)
    val nextIndex = AtomicLong(0)
}

// ---------------------------------------------------------------------------
// Worker (CPU path)
// ---------------------------------------------------------------------------

@OptIn(ExperimentalAtomicApi::class)
class Worker(
    conditions: Array<Condition>,
    private val maxSearchAnte: Int,
    private val detail: Detail,
    private val ignoredVouchers: List<String>,
    private val shopItems: Int,
) {
    private val state = MatchState(conditions)
    private val sink = MatchSink(state, maxSearchAnte)
    private val analyzer = SeedAnalyzer(detail, ignoredVouchers)

    private val seedBuf = CharArray(16)

    private var localSeeds = 0L
    private var localAntes = 0L
    private var localShopRolls = 0L
    private var localKilled = 0L

    fun analyze(index: Long) {
        val len = seedForIndex(index, seedBuf)
        state.reset(detail = false)
        analyzer.reset(seedBuf, len)
        localSeeds++

        for (ante in 1..maxSearchAnte) {
            localAntes++
            localShopRolls += analyzer.scanAnte(ante, shopItems, sink)

            // A seed whose resample loops cannot terminate is dropped, matching the GPU.
            if (analyzer.aborted) return

            // The decisive prune: a required condition whose window just closed
            // unsatisfied. closeSlot may already have set this from inside the shop.
            state.closeAnte(ante)
            if (state.dead) { localKilled++; return }

            if (state.isComplete) break
            if (ante < maxSearchAnte && state.upperBound(ante + 1) <= cutoff) return
        }

        if (!state.requirementsMet) return
        if (state.total <= cutoff) return

        record(String(seedBuf, 0, len), state.total)
    }

    fun flushStats() {
        Stats.seeds.addAndFetch(localSeeds); localSeeds = 0
        Stats.antes.addAndFetch(localAntes); localAntes = 0
        Stats.shopRolls.addAndFetch(localShopRolls); localShopRolls = 0
        Stats.killedByRequirement.addAndFetch(localKilled); localKilled = 0
    }
}

// ---------------------------------------------------------------------------
// Checkpointing
// ---------------------------------------------------------------------------

/** Set once the run has printed its own final results, so the shutdown hook stays quiet. */
private val finished = java.util.concurrent.atomic.AtomicBoolean(false)

/** Guards against two checkpoints writing the file at once. */
private val checkpointLock = java.util.concurrent.locks.ReentrantLock()

/**
 * Writes the current best seeds to [RESULTS_FILE] and prints a short summary.
 *
 * [resumeIndex] is the dispenser's watermark: the lowest index not yet fully searched.
 * Restarting from it re-scans at most the blocks that were in flight, and never skips one
 * -- which is why it is not simply "seeds searched so far". With several devices pulling
 * blocks they finish out of order, so those two numbers are different.
 */
fun checkpoint(
    conditions: Array<Condition>,
    detail: Detail,
    maxSearchAnte: Int,
    shopItems: Int,
    ignoredVouchers: List<String>,
    seedsSearched: Long,
    resumeIndex: Long,
    reason: String,
) {
    if (!checkpointLock.tryLock()) return
    try {
        val results = snapshotResults()
        val header = "[$reason] ${"%,d".format(seedsSearched)} seeds searched, " +
                "${results.size} result(s) held, resume with startIndex = $resumeIndex"

        if (results.isEmpty()) {
            println("$header -- nothing to save yet")
            return
        }

        // Filling in match detail needs a CPU rescan per seed, so it happens here rather
        // than on the hit path. At MAX_RESULTS seeds it is a blink.
        hydrate(results, conditions, detail, maxSearchAnte, shopItems, ignoredVouchers)

        val text = buildString {
            appendLine(header)
            appendLine("Generated ${java.time.LocalDateTime.now()}")
            appendLine()
            for ((i, r) in results.withIndex()) {
                appendLine("${i + 1}. ${r.seed}  score ${"%.0f".format(r.score)}")
                appendLine("     ${r.summary}")
                for (m in r.matches) appendLine("       $m")
                val souls = r.reports.flatMap { it.soulJokers }
                if (souls.isNotEmpty()) appendLine("       soul queue: ${souls.joinToString(", ")}")
                appendLine()
            }
        }

        try {
            // Write to a temp file and move it into place, so an interrupt mid-write
            // cannot leave a half-written results file where a complete one used to be.
            val target = java.io.File(RESULTS_FILE)
            val tmp = java.io.File("$RESULTS_FILE.tmp")
            tmp.writeText(text)
            tmp.renameTo(target)
        } catch (e: Exception) {
            println("  could not write $RESULTS_FILE: ${e.message}")
        }

        println(header)
        for (r in results.take(CHECKPOINT_PRINT_TOP)) {
            println("    ${r.seed} (${"%.0f".format(r.score)}): ${r.summary}")
        }
        if (results.size > CHECKPOINT_PRINT_TOP) {
            println("    ... ${results.size - CHECKPOINT_PRINT_TOP} more in $RESULTS_FILE")
        }
    } finally {
        checkpointLock.unlock()
    }
}

// ---------------------------------------------------------------------------
// Diagnostics
// ---------------------------------------------------------------------------

/**
 * Prints what each condition will and will not accept, plus the score it can contribute.
 *
 * Worth reading before a long run. Most zero-result runs are a window that excludes the
 * thing being searched for, and the window is easier to check here than to infer from an
 * empty result list six hours later.
 */
private fun describeConditions(conditions: Array<Condition>, maxSearchAnte: Int) {
    val maxPossible = conditions.sumOf { it.maxScore }
    println("Conditions (searching antes 1..$maxSearchAnte, max score ${"%.0f".format(maxPossible)}):")
    for (c in conditions) {
        val share = if (maxPossible > 0) 100.0 * c.maxScore / maxPossible else 0.0
        println("  $c")
        println("      up to ${"%.0f".format(c.maxScore)} pts (${"%.0f".format(share)}%), " +
                "best at ante ${c.anteTarget} slot ${c.slotTarget}")
    }
    val required = conditions.filter { it.required }
    if (required.isEmpty()) {
        println("  No required conditions: every seed is scanned to the end of its window.")
        println("  Marking even one condition as required is the single biggest speedup available.")
    } else {
        val earliest = required.minOf { it.anteMax }
        println("  ${required.size} required; earliest kill after ante $earliest.")
    }
}

/** Flags conditions that can never match, which is otherwise a silent zero-result run. */
private fun warnUnsatisfiable(conditions: Array<Condition>) {
    val legendaryIds = Pools.LEGENDARY_JOKERS.map { it.id }.toHashSet()
    val consumableIds = (Pools.TAROTS + Pools.PLANETS + Pools.SPECTRALS).map { it.id }.toHashSet()
    val jokerIds = (Pools.COMMON_JOKERS + Pools.UNCOMMON_JOKERS + Pools.RARE_JOKERS).map { it.id }.toHashSet()
    val tagIds = Pools.TAGS.map { it.id }.toHashSet()

    for (c in conditions) {
        for (id in c.itemIds) {
            if (id in legendaryIds && c.sources and Src.SOUL == 0) {
                println("WARNING: $c can never match -- legendary jokers only come from a Soul; add Src.SOUL.")
            }
            if (id in jokerIds && c.sources and Src.SHOP_OR_PACK == 0) {
                println("WARNING: $c can never match -- that joker only appears in a shop or a Buffoon pack.")
            }
            if (id in tagIds && c.sources and Src.TAG == 0) {
                println("WARNING: $c can never match -- tags need Src.TAG.")
            }
            if (id in consumableIds && c.editionTarget != null && c.editionTarget.id != Condition.NO_EDITION.id) {
                println("WARNING: $c can never match -- consumables are generated without editions.")
            }
            if (id in tagIds && c.anteMax < 2 && id in Pools.TAG_ANTE_GATE) {
                println("WARNING: $c can never match -- that tag cannot appear before ante 2.")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Threshold calibration
// ---------------------------------------------------------------------------

/**
 * Picks a starting cutoff from a sample of real scores.
 *
 * The projection is deliberately crude -- hit rate in the sample, scaled to the full run --
 * because it does not need to be better than that. Too low simply means the heap does the
 * tuning a little later; too high would discard results, so the estimate is biased
 * downward by taking the score *below* the target rank.
 */
fun calibrateCutoff(scores: MutableList<Double>, sampled: Long, planned: Long, targetHits: Int): Double {
    if (scores.isEmpty()) {
        println("Calibration: no seeds passed the requirements in $sampled sampled. " +
                "Cutoff stays open; the requirements are doing the filtering.")
        return Double.NEGATIVE_INFINITY
    }
    val rate = scores.size.toDouble() / sampled
    val projected = rate * planned
    println("Calibration: ${scores.size} hits in ${"%,d".format(sampled)} sampled " +
            "(1 in ${"%,.0f".format(1.0 / rate)}), projecting ${"%,.0f".format(projected)} over the run.")

    if (projected <= targetHits) {
        println("  Projected total is under the ${"%,d".format(targetHits)} target; cutoff stays open.")
        return Double.NEGATIVE_INFINITY
    }
    scores.sortDescending()
    // Rank in the sample that corresponds to targetHits over the whole run.
    val rank = ((targetHits.toDouble() / projected) * scores.size).toInt().coerceIn(1, scores.size)
    // One below the rank, so the boundary score is kept rather than excluded.
    val chosen = scores[rank - 1] - 1e-9
    println("  Cutoff set to ${"%.0f".format(chosen)} (sample rank $rank of ${scores.size}), " +
            "keeping roughly the top ${"%,d".format(targetHits)}.")
    return chosen
}

// ---------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------

@OptIn(ExperimentalAtomicApi::class)
suspend fun main() {
    val start = System.currentTimeMillis()

    val shopItems = 50
    val startIndex = 0L
    val seedsToCount = 2_300_000_000_000L
    val ignoredVouchers = listOf("Planet_Merchant", "Magic_Trick")

    // -----------------------------------------------------------------------
    // Conditions
    //
    // `required = true` means the seed is discarded if this is not satisfied by the time
    // its ante window closes. The anteRange/slotRange/sources are the hard window; the
    // *Target/*Priority values only decide how well a match inside it scores.
    // -----------------------------------------------------------------------
    val conditions = arrayOf(

        // A negative Perkeo from one of the first three Souls of the run.
        Condition(
            jokerFromDisplayName("Chicot"),
            required = true,
            anteRange = 2..5,
            sources = Src.SOUL,
            editionPriority = 10,
            antePriority = 10,
        ),

        Condition(
            jokerFromDisplayName("Perkeo"),
            required = true,
            anteRange = 1..3,
            sources = Src.SOUL,
            antePriority = 3,
        ),

        Condition(
            listOf(jokerFromDisplayName("Blueprint"),jokerFromDisplayName("Brainstorm")),
            required = true,
            anteRange = 1..2,
            antePriority = 10,
            editionTarget = editionFromDisplayName("Negative"),
            editionPriority = 10,
            sources = Src.PACK,
        ),

        Condition(
            listOf(jokerFromDisplayName("Blueprint"),jokerFromDisplayName("Brainstorm")),
            required = true,
            anteRange = 3..6,
            antePriority = 2,
            editionTarget = editionFromDisplayName("Negative"),
            editionPriority = 10,
            slotRange = 1..50,
            slotPriority = 5,
            sources = Src.SHOP_OR_PACK
        ),

        Condition(
            jokerFromDisplayName("Invisible Joker"),
            anteRange = 4..14,
            count = 6,
            slotRange = 1..150,
            slotPriority = 5,
            sources = Src.SHOP_OR_PACK
        )
    )

    val detail = Detail.forItems(
        conditions.flatMap { it.items },
        needEditions = conditions.any { it.editionTarget != null },
    )

    val maxSearchAnte = conditions.maxOf { it.anteMax }

    describeConditions(conditions, maxSearchAnte)
    println("Generating: $detail")
    warnUnsatisfiable(conditions)

    Stats.nextIndex.store(startIndex)
    val endIndex = startIndex + seedsToCount
    val workerCount = Runtime.getRuntime().availableProcessors()

    // -----------------------------------------------------------------------
    // GPU path
    // -----------------------------------------------------------------------
    if (USE_GPU) ClSearch.whyUnsupported(detail, conditions)?.let {
        println("GPU not used: $it. Running on the CPU, which produces identical results more slowly.")
    }

    if (USE_GPU && ClSearch.supports(detail, conditions)) {

        // Ctrl-C, SIGTERM, or the machine going down mid-run. Without this the results
        // only ever exist in memory and hours of searching evaporate.
        val lastProgress = java.util.concurrent.atomic.AtomicLong(startIndex)
        val lastSearched = java.util.concurrent.atomic.AtomicLong(0)
        Runtime.getRuntime().addShutdownHook(Thread {
            if (finished.get()) return@Thread
            println()
            checkpoint(conditions, detail, maxSearchAnte, shopItems, ignoredVouchers,
                lastSearched.get(), lastProgress.get(), "interrupted")
        })

        var nextCheckpointAt = CHECKPOINT_EVERY_SEEDS
        val progress: (Long, Long) -> Unit = { searched, resumeIndex ->
            lastSearched.set(searched)
            lastProgress.set(resumeIndex)
            if (CHECKPOINT_EVERY_SEEDS > 0 && searched >= nextCheckpointAt) {
                // Step past every boundary already crossed, so a chunk larger than the
                // interval does not queue up a run of back-to-back checkpoints.
                while (nextCheckpointAt <= searched) nextCheckpointAt += CHECKPOINT_EVERY_SEEDS
                checkpoint(conditions, detail, maxSearchAnte, shopItems, ignoredVouchers,
                    searched, resumeIndex, "checkpoint")
            }
        }

        try {
            // --- calibration ---
            if (CALIBRATION_SEEDS > 0 && CALIBRATION_ROUNDS > 0) {
                val sample = ArrayList<Double>()
                var sampled = 0L
                for (round in 0 until CALIBRATION_ROUNDS) {
                    // Spread the rounds across the range so one unusual stretch does not
                    // set the bar for the whole run.
                    val from = startIndex + (seedsToCount / CALIBRATION_ROUNDS) * round
                    ClSearch.run(
                        conditions, detail, maxSearchAnte, shopItems, ignoredVouchers,
                        from, CALIBRATION_SEEDS,
                        cutoffOf = { Double.NEGATIVE_INFINITY },
                        tolerateHitOverflow = true,
                        quiet = true,
                    ) { _, score -> sample.add(score) }
                    sampled += CALIBRATION_SEEDS
                }
                cutoff = calibrateCutoff(sample, sampled, seedsToCount, CALIBRATION_TARGET_HITS)
            }

            // --- the run ---
            val gpuRun: (onHit: (Long, Double) -> Unit) -> Unit = { onHit ->
                if (USE_ALL_GPUS) {
                    ClSearch.runMulti(
                        conditions, detail, maxSearchAnte, shopItems, ignoredVouchers,
                        startIndex, seedsToCount, cutoffOf = { cutoff },
                        onChunk = progress, onHit = onHit)
                } else {
                    ClSearch.run(
                        conditions, detail, maxSearchAnte, shopItems, ignoredVouchers,
                        startIndex, seedsToCount, cutoffOf = { cutoff },
                        onChunk = progress, onHit = onHit)
                }
            }
            gpuRun { index, score ->
                // Deliberately nothing but a heap insert. Anything heavier here runs
                // between kernel launches, with the whole device waiting on it.
                record(seedForIndex(index), score)
            }
            Stats.seeds.addAndFetch(seedsToCount)
            hydrate(snapshotResults(), conditions, detail, maxSearchAnte, shopItems, ignoredVouchers)
            checkpoint(conditions, detail, maxSearchAnte, shopItems, ignoredVouchers,
                seedsToCount, startIndex + seedsToCount, "final")
            finished.set(true)
            printResults(shopItems, start)
            return
        } catch (e: ClSearch.Unsupported) {
            println("GPU unavailable (${e.message}); falling back to the CPU path.")
        }
    }

    // -----------------------------------------------------------------------
    // CPU path
    // -----------------------------------------------------------------------
    TaskManager.submit(Task {
        val monitor = launch(Dispatchers.Default) {
            var lastSeeds = 0L
            var lastNanos = System.nanoTime()
            while (isActive) {
                delay(5000.milliseconds)
                val now = System.nanoTime()
                val seeds = Stats.seeds.load()
                val dt = (now - lastNanos) / 1e9
                val killed = Stats.killedByRequirement.load()
                println(
                    "%,d seeds | %,.0f seeds/s | %,d killed by requirement | cutoff %s"
                        .format(seeds, (seeds - lastSeeds) / dt, killed, fmtCutoff(cutoff))
                )
                lastSeeds = seeds; lastNanos = now
            }
        }

        val workers = List(workerCount) {
            launch(Dispatchers.Default) {
                val worker = Worker(conditions, maxSearchAnte, detail, ignoredVouchers, shopItems)
                while (true) {
                    val from = Stats.nextIndex.fetchAndAdd(SEED_BATCH.toLong())
                    if (from >= endIndex) break
                    val to = minOf(from + SEED_BATCH, endIndex)
                    for (i in from until to) worker.analyze(i)
                    worker.flushStats()
                }
            }
        }

        workers.joinAll()
        monitor.cancel()
    }).join()

    hydrate(snapshotResults(), conditions, detail, maxSearchAnte, shopItems, ignoredVouchers)
    checkpoint(conditions, detail, maxSearchAnte, shopItems, ignoredVouchers,
        Stats.seeds.load(), endIndex, "final")
    finished.set(true)
    printResults(shopItems, start)
}

fun fmtCutoff(v: Double): String = if (v == Double.NEGATIVE_INFINITY) "open" else "%.0f".format(v)

@OptIn(ExperimentalAtomicApi::class)
private fun printResults(shopItems: Int, start: Long) {
    val sorted = topResults.sortedByDescending { it.score }
    println("\nTop ${sorted.size} of ${"%,d".format(Stats.seeds.load())} seeds:")
    for (r in sorted.take(10)) {
        println("${r.seed} (${"%.0f".format(r.score)}): ${r.summary}")
        for (m in r.matches) println("      $m")
    }
    if (Stats.killedByRequirement.load() > 0) {
        println("Killed early by a required condition: ${"%,d".format(Stats.killedByRequirement.load())}")
    }
    if (Stats.antes.load() > 0) {
        println(
            "Generated ${"%,d".format(Stats.antes.load())} antes, " +
                    "${"%,d".format(Stats.shopRolls.load())} shop rolls " +
                    "(${"%,d".format(shopItems * Stats.antes.load())} without pruning)"
        )
    }
    println("Took ${System.currentTimeMillis() - start} ms")
}