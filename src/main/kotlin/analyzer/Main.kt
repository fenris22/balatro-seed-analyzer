package analyzer

import analyzer.Util.printReport
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.system.exitProcess

// ---------------------------------------------------------------------------
// Tunables
// ---------------------------------------------------------------------------

/**
 * Only the best this-many seeds are retained, so memory stays flat over a long run.
 * Set from --max-results at startup; the default is in main().
 */
var maxResults = 200

/** Seed indices claimed per atomic operation (CPU path). */
const val SEED_BATCH = 512

/**
 * Split the range across every fp64 GPU on the machine.
 *
 * Set false to pin the search to the first device, which is what you want when something
 * is misbehaving and you need one device's numbers in isolation.
 */
const val USE_ALL_GPUS = true

// --- checkpointing ---

/** Where checkpoints are written. Overwritten each time, not appended. */
const val RESULTS_FILE = "results.txt"

/** How many of the top seeds each checkpoint prints to the console. */
const val CHECKPOINT_PRINT_TOP = 5

// --- threshold calibration ---

/** How many times the calibration sample is repeated, to smooth out a lucky stretch. */
const val CALIBRATION_ROUNDS = 2

/**
 * How many results the projected run should produce. Set well above maxResults: the
 * heap discards the excess, and aiming too tight risks a cutoff that starves it.
 */
val calibrationTargetHits: Int get() = maxResults * 20

// ---------------------------------------------------------------------------
// Seeds
// ---------------------------------------------------------------------------


/**
 * Seed alphabet: 1-9 and A-Z, 35 characters. Generated seeds never contain 0 or O, but a
 * hand-typed 0 becomes O in game, so seeds with O are reachable and are searched too.
 *
 * This is the only copy: ClSearch passes it to the kernel as a define, so host and device
 * cannot disagree. Changing it moves every seed to a new index.
 */
const val SEED_CHARS = "123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
const val SEED_BASE = 35L

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

/** Inverse of [seedForIndex]. */
fun indexForSeed(seed: String): Long {
    val s = seed.uppercase()
    var offset = 0L
    var block = SEED_BASE
    for (l in 1 until s.length) { offset += block; block *= SEED_BASE }
    var n = 0L
    for (i in s.indices) n = n * SEED_BASE + SEED_CHARS.indexOf(s[i])
    return offset + n
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
        // Nothing unmet can take a shop card from here on: the rest of the shop is dead
        // weight. Exact, so it applies to verification scans too.
        if (!state.shopCanMatch(ante, slot)) return false
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
private val topResults = java.util.PriorityQueue<SeedResult>(compareBy { it.score })

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

/** Empties the results and reopens the cutoff, ready for a new run. */
fun resetResults() {
    resultsLock.lock()
    try {
        topResults.clear()
        cutoff = Double.NEGATIVE_INFINITY
    } finally {
        resultsLock.unlock()
    }
}

/** Cheap by design: a volatile compare, and a heap insert only when it passes. */
fun record(seed: String, score: Double) {
    RunStatus.hits.increment()
    if (score <= cutoff) return
    resultsLock.lock()
    try {
        if (score <= cutoff) return
        topResults.add(SeedResult(seed, score))
        if (topResults.size > maxResults) {
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
 * Runs once, on at most maxResults seeds, so it can afford to be as slow as it likes.
 */
internal fun hydrate(
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
    stages: List<PrefilterStage> = emptyList(),
) {
    private val runners = stages.map { StageRunner(it, conditions, ignoredVouchers) }
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

        // Cheap, exact prefilters first, most selective per unit of work first.
        for (r in runners) if (!r.passes(seedBuf, len)) { localKilled++; return }

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
        // than on the hit path. At maxResults seeds it is a blink.
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
fun describeConditions(conditions: Array<Condition>, maxSearchAnte: Int): List<String> {
    val out = ArrayList<String>()
    val maxPossible = conditions.sumOf { it.maxScore }
    out.add("Conditions (searching antes 1..$maxSearchAnte, max score ${"%.0f".format(maxPossible)}):")
    for (c in conditions) {
        val share = if (maxPossible > 0) 100.0 * c.maxScore / maxPossible else 0.0
        out.add("  $c")
        out.add("      up to ${"%.0f".format(c.maxScore)} pts (${"%.0f".format(share)}%), " +
                "best at ante ${c.anteTarget} slot ${c.slotTarget}")
    }
    val required = conditions.filter { it.required }
    if (required.isEmpty()) {
        out.add("  No required conditions: every seed is scanned to the end of its window.")
        out.add("  Marking even one condition as required is the single biggest speedup available.")
    } else {
        val earliest = required.minOf { it.anteMax }
        out.add("  ${required.size} required; earliest kill after ante $earliest.")
    }
    return out
}

/**
 * Flags conditions that can never match, which is otherwise a silent zero-result run.
 * Returns one message per problem, keyed by the condition's position.
 */
fun unsatisfiableReasons(conditions: Array<Condition>): List<Pair<Int, String>> {
    val legendaryIds = Pools.LEGENDARY_JOKERS.map { it.id }.toHashSet()
    val consumableIds = (Pools.TAROTS + Pools.PLANETS + Pools.SPECTRALS).map { it.id }.toHashSet() +
            setOf("The_Soul", "Black_Hole")
    val jokerIds = (Pools.COMMON_JOKERS + Pools.UNCOMMON_JOKERS + Pools.RARE_JOKERS).map { it.id }.toHashSet()
    val tagIds = Pools.TAGS.map { it.id }.toHashSet()
    val voucherIds = Pools.VOUCHERS.map { it.id }.toHashSet()
    val bossIds = Pools.BOSSES.map { it.id }.toHashSet()

    val out = ArrayList<Pair<Int, String>>()
    for ((i, c) in conditions.withIndex()) {
        for (item in c.items) {
            val id = item.id
            val name = item.displayName
            if (id in legendaryIds && c.sources and Src.SOUL == 0) {
                out.add(i to "$name only comes from a Soul, so it needs the Soul source.")
            }
            if (id in jokerIds && c.sources and Src.SHOP_OR_PACK == 0) {
                out.add(i to "$name only appears in a shop or a Buffoon pack.")
            }
            if (id in consumableIds && c.sources and Src.SHOP_OR_PACK == 0) {
                out.add(i to "$name only appears in a shop or a pack.")
            }
            if (id in tagIds && c.sources and Src.TAG == 0) {
                out.add(i to "$name is a tag, so it needs the Tag source.")
            }
            if (id in voucherIds && c.sources and Src.VOUCHER == 0) {
                out.add(i to "$name is a voucher, so it needs the Voucher source.")
            }
            if (id in bossIds && c.sources and Src.BOSS == 0) {
                out.add(i to "$name is a boss blind, so it needs the Boss source.")
            }
            if (id in consumableIds && c.editionTarget != null && c.editionTarget.id != Condition.NO_EDITION.id) {
                out.add(i to "$name is a consumable, and consumables never have an edition.")
            }
            if (id in tagIds && c.anteMax < 2 && id in Pools.TAG_ANTE_GATE) {
                out.add(i to "$name cannot appear before ante 2.")
            }
        }
    }
    return out
}

fun warnUnsatisfiable(conditions: Array<Condition>): List<String> =
    unsatisfiableReasons(conditions).map { (i, msg) -> "${conditions[i]} can never match -- $msg" }

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

/** Vouchers skipped when neither the conditions file nor the web page says otherwise. */
val DEFAULT_IGNORED_VOUCHERS = listOf("Planet_Merchant", "Magic_Trick", "Tarot_Merchant")

/**
 * With --conditions FILE the search runs straight away, headless, and exits when done:
 * that is the mode for a server or a VM. With --examine SEED one seed is printed. Anything
 * else starts the web page.
 */
fun main(args: Array<String>) {
    // -----------------------------------------------------------------------
    // Defaults. Every one of these can be overridden by a flag; run with --help
    // for the list. Edit them here to change what a run with no flags does.
    // In web mode they are what the settings panel starts with.
    // -----------------------------------------------------------------------
    val defaults = RunOptions(
        maxResults = 200,                 // --max-results
        useGpu = true,                    // --disable-gpu turns this off
        startIndex = 0L,                  // --start-index
        endIndex = 312_000_000L,          // --end-index
        checkpointEvery = null,           // --checkpoint   (null = one tenth of the range)
        calibrationSeeds = 4_000_000L,    // --calibration-seeds
        globalSize = 4096,                // --global-size  (work items per compute unit)
        localSize = 64,                   // --local-size
        chunk = 4_000_000,                // --chunk        (seeds per GPU launch)
    )

    val file: ConditionFile?
    val opts: RunOptions
    try {
        // The file's own settings sit between the built-in defaults and the flags, so a
        // flag always wins.
        file = Cli.conditionsPath(args)?.let { ConditionFile.load(it) }
        val base = file?.let { applySettings(defaults, it.settings, validate = false) } ?: defaults
        opts = Cli.parse(args, base) ?: return      // null: --help was shown
    } catch (e: CliError) {
        println("error: ${e.message}")
        println("Run with --help to see every flag.")
        exitProcess(2)
    } catch (e: SpecError) {
        println("error in the conditions file: ${e.message}")
        exitProcess(2)
    }

    val ignoredVouchers = file?.ignoredVouchers ?: DEFAULT_IGNORED_VOUCHERS

    opts.examine?.let { examineSeed(it, ignoredVouchers) }

    if (file == null) {
        WebServer.start(opts, DEFAULT_IGNORED_VOUCHERS)
        return      // the server's threads keep the program running
    }

    runSearch(SearchConfig(file.conditions, ignoredVouchers, opts))
    exitProcess(0)
}

fun fmtCutoff(v: Double): String = if (v == Double.NEGATIVE_INFINITY) "open" else "%.0f".format(v)

@OptIn(ExperimentalAtomicApi::class)
internal fun printResults(shopItems: Int, start: Long) {
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

/**
 * Prints antes 1-8 of one seed in full: boss, voucher, tags, the first 20 shop slots, every
 * pack, and the legendary each Soul hands out. Run with --examine SEED.
 *
 * One analyzer for the whole run, seeded once: vouchers, bosses and the Soul queue carry
 * over from ante to ante, so a fresh analyzer per ante would print the wrong thing from
 * ante 2 on.
 */
private fun examineSeed(seed: String, ignoredVouchers: List<String>) {
    val analyzer = SeedAnalyzer(seed.uppercase(), Detail.FULL, ignoredVouchers)
    println("Seed ${seed.uppercase()} (index ${"%,d".format(indexForSeed(seed))}), " +
            "skipping vouchers: ${ignoredVouchers.joinToString(", ").ifEmpty { "none" }}")
    for (ante in 1..8) printReport(analyzer.ante(ante, 20))
    exitProcess(0)
}
