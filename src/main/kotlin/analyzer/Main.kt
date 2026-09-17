package analyzer

import analyzer.RequestSpec.Companion.NO_EDITION
import analyzer.Util.editionFromDisplayName
import analyzer.Util.jokerFromDisplayName
import analyzer.Util.spectralFromDisplayName
import analyzer.Util.tarotFromDisplayName
import executor.Task
import executor.TaskManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.abs
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds

// ---------------------------------------------------------------------------
// Tunables
// ---------------------------------------------------------------------------

/**
 * Cutoff as a fraction of the best score the request list could possibly produce.
 *
 * This is the single most important number for speed, not just for result quality: the
 * bound checks can only prune when the cutoff is above what an unmatched request could
 * still earn. The startup diagnostic prints the exact fraction at which pruning starts
 * to bite for your request list -- if you set this below it, the search degenerates to
 * generating every ante in full.
 */
const val THRESHOLD_FRACTION = 0.8

/** Only the best MAX_RESULTS seeds are retained, so memory stays flat over a long run. */
const val MAX_RESULTS = 200

/** Seed indices claimed per atomic operation (CPU path). */
const val SEED_BATCH = 512

/**
 * Use the OpenCL kernel when the request list is within what it covers (jokers, editions
 * and Souls). Anything else falls back to the CPU path automatically -- the kernel is
 * never allowed to approximate.
 */
const val USE_GPU = true

// ---------------------------------------------------------------------------
// Seeds
// ---------------------------------------------------------------------------

/** Same 34-character alphabet Util.nextSeed walks (no 0, no O). */
private const val SEED_CHARS = "123456789ABCDEFGHIJKLMNPQRSTUVWXYZ"
private const val SEED_BASE = 34L

/**
 * Writes the n'th seed of Util.nextSeed's sequence into [out], returning its length.
 *
 * Util.nextSeed is a shared odometer: one coroutine owns it, every seed costs a
 * toCharArray, a linear indexOf and a String allocation, and the whole run funnels
 * through it. Deriving the seed from its index means each worker generates its own into
 * a reusable buffer, the producer and channel disappear, nothing is allocated, and a run
 * becomes resumable.
 *
 * Identical sequence: all length-1 seeds, then all length-2, and so on.
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
// Scoring
// ---------------------------------------------------------------------------

/** floor(10 * exp(-0.5 * d)); zero from d = 5 on, so five entries cover the domain. */
private val DECAY = doubleArrayOf(10.0, 6.0, 3.0, 2.0, 1.0)

private fun decay(distance: Int): Double =
    if (distance < DECAY.size) DECAY[distance] else 0.0

/** Sentinel for "no target", so the fields can stay primitive instead of boxing Int?. */
const val UNSET = Int.MIN_VALUE

class RequestSpec(
    val item: Item,
    val editionTarget: Item? = null,
    val editionPriority: Int = 1,
    val anteTarget: Int = UNSET,
    val antePriority: Int = 1,
    val slotTarget: Int = UNSET,
    val slotPriority: Int = 1,
) {
    companion object {
        /**
         * Pass as [editionTarget] to require a *base* joker -- one with no edition at all.
         * Leaving editionTarget null means "any edition is fine", which is not the same
         * thing.
         */
        val NO_EDITION = Item("__no_edition__", "No Edition")
    }

    val hasAnte = anteTarget != UNSET
    val hasSlot = slotTarget != UNSET
    val hasEdition = editionTarget != null
    val itemId: String = item.id
    private val editionTargetId: String? = editionTarget?.id
    private val requiresNoEdition = editionTargetId == NO_EDITION.id

    val maxAnteScore = if (hasAnte) 10.0 * antePriority else 0.0
    val maxSlotScore = if (hasSlot) 10.0 * slotPriority else 0.0

    /**
     * The edition is a gate, not a target: a Perkeo is never a near-miss for a Negative
     * Perkeo, so there is no distance to decay over. Either the candidate carries the
     * requested edition and the request can match it, or it cannot match at all.
     *
     * editionPriority survives as a flat weight, so a Negative Blueprint request can be
     * made worth more than the other requests in the list without changing what it will
     * accept. Since the bonus is awarded exactly when the request matches, it is part of
     * both the score and every bound.
     */
    val maxEditionScore = if (hasEdition) 10.0 * editionPriority else 0.0

    val maxScore = maxAnteScore + maxSlotScore + maxEditionScore

    /** True if [edition] satisfies this request's gate. */
    fun acceptsEdition(edition: Item?): Boolean {
        val target = editionTargetId ?: return true
        if (requiresNoEdition) return edition == null
        return edition != null && edition.id == target
    }

    private fun slotScore(slot: Int): Double = when {
        !hasSlot -> 0.0
        slot <= slotTarget -> maxSlotScore
        else -> decay(slot - slotTarget) * slotPriority
    }

    private fun anteScore(ante: Int): Double =
        if (hasAnte) decay(abs(ante - anteTarget)) * antePriority else 0.0

    /** Best ante component still reachable from [ante] onwards. */
    private fun bestAnteFrom(ante: Int): Double =
        if (hasAnte) decay(max(0, ante - anteTarget)) * antePriority else 0.0

    fun scoreAt(ante: Int, slot: Int): Double = slotScore(slot) + anteScore(ante) + maxEditionScore

    /**
     * Best score still reachable given that scanning has got to (ante, slot) and will run
     * through [maxAnte]: finish in this ante, where the slot penalty is locked in and can
     * only grow, or wait for a later ante, where slot 1 is available again but the ante
     * penalty is at least one step worse.
     *
     * The second branch is why slot pruning does nothing until the later antes are
     * themselves hopeless -- and everything once they are.
     */
    fun bestFrom(ante: Int, slot: Int, maxAnte: Int): Double {
        val here = slotScore(slot) + anteScore(ante)
        val later = if (ante < maxAnte) maxSlotScore + bestAnteFrom(ante + 1) else 0.0
        return max(here, later) + maxEditionScore
    }

    override fun toString(): String {
        val ed = editionTarget?.let { "${it.displayName} " } ?: ""
        return "$ed${item.displayName}@ante=$anteTarget,slot=$slotTarget"
    }
}

data class Match(
    val spec: RequestSpec,
    val ante: Int,
    val slot: Int,
    val location: String,
    val edition: Item?,
    val score: Double,
) {
    override fun toString(): String {
        val ed = edition?.let { "${it.displayName} " } ?: ""
        return "$ed${spec.item.displayName} ($location a$ante #$slot = $score)"
    }
}

// ---------------------------------------------------------------------------
// Match state
// ---------------------------------------------------------------------------

class MatchState(private val specs: Array<RequestSpec>) {
    private val n = specs.size
    private val checked = BooleanArray(n)
    private val ante = IntArray(n)
    private val slot = IntArray(n)
    private val location = arrayOfNulls<String>(n)
    private val matchedEdition = arrayOfNulls<Item>(n)
    private val score = DoubleArray(n)

    /**
     * Item id -> the request indices wanting it. The old loop compared every candidate
     * against every request, so cost grew with the condition count on every card
     * generated, and almost no card matches anything.
     */
    private val byId: HashMap<String, IntArray> = run {
        val tmp = HashMap<String, MutableList<Int>>()
        for (i in 0 until n) tmp.getOrPut(specs[i].itemId) { mutableListOf() }.add(i)
        val out = HashMap<String, IntArray>(tmp.size * 2)
        for ((k, v) in tmp) out[k] = v.toIntArray()
        out
    }

    /**
     * One-byte prefilter in front of the map. Nearly every card generated matches nothing,
     * and this turns that case into a single array load instead of a hash lookup. False
     * positives just fall through to byId, so it stays exact.
     */
    private val maybe = BooleanArray(1024).also { m ->
        for (s in specs) m[s.itemId.hashCode() and 1023] = true
    }

    var remaining = n
        private set
    var total = 0.0
        private set

    val maxTotal: Double = specs.sumOf { it.maxScore }
    val isComplete: Boolean get() = remaining == 0

    fun reset() {
        java.util.Arrays.fill(checked, false)
        remaining = n
        total = 0.0
    }

    /** Upper bound on the final total given that scanning has reached (ante, slot). */
    fun upperBound(ante: Int, slot: Int, maxAnte: Int): Double {
        var bound = total
        for (i in 0 until n) if (!checked[i]) bound += specs[i].bestFrom(ante, slot, maxAnte)
        return bound
    }

    /**
     * A candidate that fails one request's edition gate simply moves on to the next
     * request, so a plain Blueprint can still satisfy a plain-Blueprint request sitting
     * behind a Negative-Blueprint one.
     */
    fun offer(candidate: Item, edition: Item?, a: Int, s: Int, where: String): Boolean {
        val id = candidate.id
        if (!maybe[id.hashCode() and 1023]) return false
        val indices = byId[id] ?: return false
        for (i in indices) {
            if (checked[i]) continue
            if (!specs[i].acceptsEdition(edition)) continue
            checked[i] = true
            ante[i] = a
            slot[i] = s
            location[i] = where
            matchedEdition[i] = edition
            val sc = specs[i].scoreAt(a, s)
            score[i] = sc
            total += sc
            remaining--
            return true
        }
        return false
    }

    fun snapshot(): List<Match> = List(n) { i ->
        Match(specs[i], ante[i], slot[i], location[i] ?: "?", matchedEdition[i], score[i])
    }
}

/** Where a match came from. Compile-time constants, so storing one is a reference write. */
object Source {
    const val SHOP = "Shop"
    const val PACK = "Pack"
    const val SOUL = "Soul"
    const val VOUCHER = "Voucher"
    const val TAG = "Tag"
    const val BOSS = "Boss"
}

/**
 * Feeds a scan into a MatchState and decides when to abandon the seed.
 *
 * Every card type is offered, so a request for a tarot, a voucher or a legendary joker
 * needs no new plumbing: Detail turns its generation on and the matching already works.
 */
class MatchSink(
    val state: MatchState,
    private val maxSearchAnte: Int,
    /**
     * Set false for verification scans.
     *
     * Pruning reads the live cutoff, which rises as results are recorded, so a scan run to
     * check an earlier result would be cut short by a bar that did not exist when that
     * result was produced -- and would then wrongly report no match. Turning pruning off
     * is safe rather than merely lenient: a genuine hit never had its bound fall to or
     * below the cutoff before completing, so an unpruned scan reaches the same matches at
     * the same positions and stops at completion either way.
     */
    private val prune: Boolean = true,
) : ScanSink() {
    override fun wantMoreShop(ante: Int, slot: Int): Boolean {
        if (state.isComplete) return false
        if (!prune) return true
        return state.upperBound(ante, slot, maxSearchAnte) > cutoff
    }

    override fun onShopItem(ante: Int, slot: Int, kind: String, item: Item, rarity: String?, edition: Item?) {
        state.offer(item, edition, ante, slot, Source.SHOP)
    }

    override fun onPackJoker(ante: Int, slot: Int, item: Item, rarity: String, edition: Item?) {
        state.offer(item, edition, ante, slot, Source.PACK)
    }

    /** Consumables carry no edition, so only an editionless request can take one. */
    override fun onPackConsumable(ante: Int, slot: Int, family: String, item: Item) {
        state.offer(item, null, ante, slot, Source.PACK)
    }

    override fun onPackCard(ante: Int, slot: Int, card: StandardCard) {
        state.offer(card.base, card.edition, ante, slot, Source.PACK)
    }

    /**
     * Soul jokers are matched on the Soul's ordinal, not a shop position: slotTarget = 1
     * on a legendary request means "the first Soul of the run gives me this".
     *
     * Soul jokers now carry an edition, rolled on the "sou" stream, so a Negative Perkeo
     * is searchable like any other edition-gated request.
     */
    override fun onSoulJoker(ante: Int, soulIndex: Int, item: Item, edition: Item?) {
        state.offer(item, edition, ante, soulIndex, Source.SOUL)
    }

    override fun onVoucher(ante: Int, item: Item) { state.offer(item, null, ante, 1, Source.VOUCHER) }
    override fun onTag(ante: Int, index: Int, item: Item) { state.offer(item, null, ante, index + 1, Source.TAG) }
    override fun onBoss(ante: Int, item: Item) { state.offer(item, null, ante, 1, Source.BOSS) }
}

// ---------------------------------------------------------------------------
// Result collection
// ---------------------------------------------------------------------------

data class SeedResult(val seed: String, val score: Double, val matches: List<Match>, val reports: List<AnteReport>)

private val resultsLock = Mutex()
private val topResults = java.util.PriorityQueue<SeedResult>(MAX_RESULTS + 1, compareBy { it.score })

/** Read once per shop slot, so it stays a plain volatile read rather than a lock. */
@Volatile
var cutoff = 0.0

private suspend fun record(seed: String, score: Double, matches: List<Match>, reports: List<AnteReport>) {
    if (score <= cutoff) return
    resultsLock.withLock {
        if (score <= cutoff) return
        topResults.add(SeedResult(seed, score, matches, reports))
        if (topResults.size > MAX_RESULTS) {
            topResults.poll()
            cutoff = topResults.peek().score
        }
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
    val nextIndex = AtomicLong(0)
}

// ---------------------------------------------------------------------------
// Worker
// ---------------------------------------------------------------------------

@OptIn(ExperimentalAtomicApi::class)
class Worker(
    specs: Array<RequestSpec>,
    private val maxSearchAnte: Int,
    private val detail: Detail,
    private val ignoredVouchers: List<String>,
    private val shopItems: Int,
) {
    private val state = MatchState(specs)
    private val sink = MatchSink(state, maxSearchAnte)
    private val analyzer = SeedAnalyzer(detail, ignoredVouchers)
    private var fullAnalyzer: SeedAnalyzer? = null

    private val seedBuf = CharArray(16)

    private var localSeeds = 0L
    private var localAntes = 0L
    private var localShopRolls = 0L

    suspend fun analyze(index: Long) {
        val len = seedForIndex(index, seedBuf)
        state.reset()
        analyzer.reset(seedBuf, len)
        localSeeds++

        var antesRun = 0
        for (ante in 1..maxSearchAnte) {
            antesRun = ante
            localAntes++
            localShopRolls += analyzer.scanAnte(ante, shopItems, sink)

            if (state.isComplete) break
            if (ante < maxSearchAnte && state.upperBound(ante + 1, 1, maxSearchAnte) <= cutoff) return
        }

        if (!state.isComplete) return
        if (state.total <= cutoff) return

        val seed = String(seedBuf, 0, len)
        record(seed, state.total, state.snapshot(), fullReports(seed, antesRun))
    }

    fun flushStats() {
        Stats.seeds.addAndFetch(localSeeds); localSeeds = 0
        Stats.antes.addAndFetch(localAntes); localAntes = 0
        Stats.shopRolls.addAndFetch(localShopRolls); localShopRolls = 0
    }

    /**
     * The filter pass leaves unrequested card types ungenerated and stops the shop early,
     * so a stored report has to be rebuilt in full. Runs on a handful of seeds out of
     * millions.
     */
    private fun fullReports(seed: String, antes: Int): List<AnteReport> {
        val a = fullAnalyzer ?: SeedAnalyzer(Detail.FULL, ignoredVouchers).also { fullAnalyzer = it }
        a.reset(seed)
        return (1..antes).map { a.ante(it, shopItems) }
    }
}

// ---------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------

/**
 * Reports the cutoff at which each pruning stage starts to fire, given an empty match
 * state. Worth reading before a long run: if your cutoff sits below these numbers, the
 * corresponding check can never trigger and you are generating everything.
 */
private fun printPruningProfile(specs: Array<RequestSpec>, maxSearchAnte: Int, shopItems: Int) {
    val probe = MatchState(specs)
    val maxPossible = probe.maxTotal
    fun pct(v: Double) = "%.1f%% of max".format(100.0 * v / maxPossible)

    println("Pruning profile (nothing matched):")
    for (a in 1 until maxSearchAnte) {
        val b = probe.upperBound(a + 1, 1, maxSearchAnte)
        println("  abandon after ante $a once cutoff > $b  (${pct(b)})")
    }
    for (slot in intArrayOf(1, 5, 10, 20, shopItems)) {
        val b = probe.upperBound(maxSearchAnte, slot, maxSearchAnte)
        println("  stop ante $maxSearchAnte shop at slot $slot once cutoff > $b  (${pct(b)})")
    }
}

/**
 * Flags requests that can never match, which is otherwise a silent zero-result run.
 */
private fun warnUnsatisfiable(specs: Array<RequestSpec>) {
    for (s in specs) {
        if (!s.hasEdition) continue
        if (Pools.TAROTS.any { it.id == s.itemId } || Pools.PLANETS.any { it.id == s.itemId } ||
            Pools.SPECTRALS.any { it.id == s.itemId }
        ) {
            println("WARNING: $s can never match -- consumables are generated without editions.")
        }
    }
}

@OptIn(ExperimentalAtomicApi::class)
suspend fun main() {
    val start = System.currentTimeMillis()

    val maxAnte = 4
    val shopItems = 50
    val startIndex = 0L
    val seedsToCount = 100_000_000L
    val ignoredVouchers = listOf("Planet_Merchant", "Magic_Trick")

    // Item Requests. A legendary joker here is matched against the Soul queue, where the
    // slot target means "the n'th Soul of the run".
    val specs = arrayOf(
        RequestSpec(jokerFromDisplayName("Perkeo"), editionTarget = editionFromDisplayName("Negative"), slotTarget = 1, slotPriority = 20, anteTarget = 2, antePriority = 10) ,
        RequestSpec(Item("The_Soul", "The Soul"), slotTarget = 1, anteTarget = 2, antePriority = 10),
        RequestSpec(tarotFromDisplayName("Temperance"), slotTarget = 1, slotPriority = 5, anteTarget = 2, antePriority = 5),
        RequestSpec(jokerFromDisplayName("Blueprint"), editionTarget = editionFromDisplayName("Negative"), slotTarget = 1, slotPriority = 3, anteTarget = 2, antePriority = 5),
        RequestSpec(jokerFromDisplayName("Blueprint"), slotTarget = 1, slotPriority = 3, anteTarget = 2, antePriority = 5),
    )

    //todo gpu code for spectrals

    val detail = Detail.forItems(
        specs.map { it.item },
        needEditions = specs.any { it.editionTarget != null }
    )

    val maxSearchAnte = specs.maxOf { if (it.hasAnte) it.anteTarget + 2 else maxAnte }
    val maxPossible = specs.sumOf { it.maxScore }
    cutoff = maxPossible * THRESHOLD_FRACTION

    println("Max possible score: $maxPossible, starting cutoff: $cutoff, antes 1..$maxSearchAnte")
    println("Generating: $detail")
    warnUnsatisfiable(specs)
    printPruningProfile(specs, maxSearchAnte, shopItems)

    Stats.nextIndex.store(startIndex)
    val endIndex = startIndex + seedsToCount
    val workerCount = Runtime.getRuntime().availableProcessors()

    if (USE_GPU && ClSearch.supports(detail)) {
        val reportAnalyzer = SeedAnalyzer(Detail.FULL, ignoredVouchers)
        val verifyState = MatchState(specs)
        val verifySink = MatchSink(verifyState, maxSearchAnte, prune = false)
        val filterAnalyzer = SeedAnalyzer(detail, ignoredVouchers)
        val buf = CharArray(16)

        try {
            ClSearch.run(
                specs, detail, maxSearchAnte, shopItems, ignoredVouchers,
                startIndex, seedsToCount, { cutoff },
            ) { index, score ->
                // Re-run on the CPU to recover the match detail and the full reports. The
                // score was already checked against the device inside ClSearch.
                verifyState.reset()
                val len = seedForIndex(index, buf)
                filterAnalyzer.reset(buf, len)
                var antesRun = 0
                for (ante in 1..maxSearchAnte) {
                    antesRun = ante
                    filterAnalyzer.scanAnte(ante, shopItems, verifySink)
                    if (verifyState.isComplete) break
                }
                val seed = String(buf, 0, len)
                reportAnalyzer.reset(seed)
                val reports = (1..antesRun).map { reportAnalyzer.ante(it, shopItems) }
                runBlocking { record(seed, score, verifyState.snapshot(), reports) }
            }
            Stats.seeds.addAndFetch(seedsToCount)
            printResults(shopItems, start)
            return
        } catch (e: ClSearch.Unsupported) {
            println("GPU unavailable (${e.message}); falling back to the CPU path.")
        }
    }

    TaskManager.submit(Task {
        // Interval throughput, not cumulative. A cumulative average always looks like a
        // slowdown once the early cheap seeds are behind you; this shows the real rate,
        // which is what separates an algorithmic problem from thermal throttling.
        val monitor = launch(Dispatchers.Default) {
            var lastSeeds = 0L
            var lastShop = 0L
            var lastNanos = System.nanoTime()
            while (isActive) {
                delay(5000.milliseconds)
                val now = System.nanoTime()
                val seeds = Stats.seeds.load()
                val shop = Stats.shopRolls.load()
                val dt = (now - lastNanos) / 1e9
                val rate = (seeds - lastSeeds) / dt
                val shopPerSeed = if (seeds > lastSeeds) (shop - lastShop).toDouble() / (seeds - lastSeeds) else 0.0
                println(
                    "%,d seeds | %,.0f seeds/s | %.1f shop rolls/seed | cutoff %.0f"
                        .format(seeds, rate, shopPerSeed, cutoff)
                )
                lastSeeds = seeds; lastShop = shop; lastNanos = now
            }
        }

        val workers = List(workerCount) {
            launch(Dispatchers.Default) {
                val worker = Worker(specs, maxSearchAnte, detail, ignoredVouchers, shopItems)
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

    printResults(shopItems, start)
}

@OptIn(ExperimentalAtomicApi::class)
private fun printResults(shopItems: Int, start: Long) {
    val sorted = topResults.sortedByDescending { it.score }
    println("Top ${sorted.size} of ${Stats.seeds.load()} seeds:")
    for (r in sorted.take(10)) {
        println("${r.seed} (${r.score}): ${r.matches}")
        val souls = r.reports.flatMap { it.soulJokers }
        if (souls.isNotEmpty()) println("    soul queue: ${souls.joinToString(", ")}")
    }
    if (Stats.antes.load() > 0) {
        println(
            "Generated ${Stats.antes.load()} antes, ${Stats.shopRolls.load()} shop rolls " +
                    "(${shopItems * Stats.antes.load()} without pruning)"
        )
    }
    println("Took ${System.currentTimeMillis() - start} ms")
}