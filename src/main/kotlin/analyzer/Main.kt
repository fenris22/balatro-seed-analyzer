package analyzer

import analyzer.Util.cardFromDisplayName
import analyzer.Util.mapToItem
import analyzer.Util.nextSeed
import executor.Task
import executor.TaskManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// ---------------------------------------------------------------------------
// Tunables
// ---------------------------------------------------------------------------

const val THRESHOLD = 200.0

/** Only the best MAX_RESULTS seeds are retained, so memory stays flat over a long run. */
const val MAX_RESULTS = 200

/** Seeds handed to a worker per channel message. */
const val SEED_BATCH = 512

// ---------------------------------------------------------------------------
// Scoring
// ---------------------------------------------------------------------------

/**
 * floor(10 * exp(-0.5 * d)) evaluated once at startup.
 *
 * The curve reaches 0 at d = 5 and stays there, so five entries cover the entire
 * domain exactly -- no exp() or floor() in the hot path, and it tells you
 * something useful: anything more than 4 off target contributes nothing at all.
 */
private val DECAY = doubleArrayOf(10.0, 6.0, 3.0, 2.0, 1.0)

private fun decay(distance: Int): Double =
    if (distance < DECAY.size) DECAY[distance] else 0.0

/** Sentinel for "no target", so the fields can stay primitive instead of boxing Int?. */
const val UNSET = Int.MIN_VALUE

/**
 * An immutable search condition. Built once at startup and shared by every worker;
 * nothing here is mutated during a run.
 */
class RequestSpec(
    val item: Item,
    val editionTarget: String? = null,
    val editionPriority: Int = 1,
    val anteTarget: Int = UNSET,
    val antePriority: Int = 1,
    val slotTarget: Int = UNSET,
    val slotPriority: Int = 1,
) {
    val hasAnte = anteTarget != UNSET
    val hasSlot = slotTarget != UNSET

    /** Compared instead of the whole Item: one String equality, not two. */
    val itemId: String = item.id

    val maxAnteScore = if (hasAnte) 10.0 * antePriority else 0.0
    val maxSlotScore = if (hasSlot) 10.0 * slotPriority else 0.0
    val maxScore = maxAnteScore + maxSlotScore

    fun scoreAt(ante: Int, slot: Int): Double {
        var s = 0.0
        if (hasSlot) {
            s += if (slot <= slotTarget) maxSlotScore else decay(slot - slotTarget) * slotPriority
        }
        if (hasAnte) {
            s += decay(abs(ante - anteTarget)) * antePriority
        }
        return s
    }

    /**
     * Best score this request could still earn if its first match lands at [ante] or later.
     * Slot is unconstrained by the ante, but the ante penalty can only grow once we are
     * past the target, which is what makes the bound tighten as the search progresses.
     */
    fun bestFrom(ante: Int): Double =
        maxSlotScore + if (hasAnte) decay(max(0, ante - anteTarget)) * antePriority else 0.0

    override fun toString() = "${item}@ante=$anteTarget,slot=$slotTarget"
}

/** A matched request, built only when a seed actually gets stored. */
data class Match(
    val spec: RequestSpec,
    val ante: Int,
    val slot: Int,
    val location: String,
    val score: Double,
) {
    override fun toString() = "${spec.item} ($location a$ante #$slot = $score)"
}

// ---------------------------------------------------------------------------
// Per-worker match state
// ---------------------------------------------------------------------------

/**
 * Mutable matching state for one seed, stored in parallel primitive arrays.
 *
 * One instance is owned by each worker coroutine and reset between seeds, so a full
 * run allocates this once per core rather than once per seed.
 */
class MatchState(private val specs: Array<RequestSpec>) {
    private val n = specs.size
    private val ids = Array(n) { specs[it].itemId }
    private val checked = BooleanArray(n)
    private val ante = IntArray(n)
    private val slot = IntArray(n)
    private val location = arrayOfNulls<String>(n)
    private val score = DoubleArray(n)

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

    /** Upper bound on the final total assuming nothing else matches before [ante]. */
    fun upperBound(ante: Int): Double {
        var bound = total
        for (i in 0 until n) if (!checked[i]) bound += specs[i].bestFrom(ante)
        return bound
    }

    /**
     * Offer one found item to the first unmatched request that wants it.
     *
     * This is the same pairing the original produced -- it iterated requests and pulled
     * the first matching item, this iterates items and finds the first matching request,
     * and since matching is by item equality alone both are first-come-first-served and
     * yield an identical set of pairs. Flipping the loop just avoids building a
     * throwaway ItemLocation list for every candidate.
     */
    fun offer(candidate: Item, a: Int, s: Int, where: String): Boolean {
        val cid = candidate.id
        for (i in 0 until n) {
            if (checked[i] || ids[i] != cid) continue
            checked[i] = true
            ante[i] = a
            slot[i] = s
            location[i] = where
            val sc = specs[i].scoreAt(a, s)
            score[i] = sc
            total += sc
            remaining--
            return true
        }
        return false
    }

    /** Run one ante report through every source, in order. */
    fun consume(report: AnteReport, sources: Array<ItemSource>) {
        for (source in sources) {
            if (remaining == 0) return
            source.feedInto(report, this)
        }
    }

    /** Snapshot for storage. Called only on a hit, so the allocation here is free. */
    fun snapshot(): List<Match> = List(n) { i ->
        Match(specs[i], ante[i], slot[i], location[i] ?: "?", score[i])
    }
}

// ---------------------------------------------------------------------------
// Item sources
// ---------------------------------------------------------------------------

/** Source labels. Compile-time constants, so storing one is a reference write. */
object Source {
    const val SHOP = "Shop"
    const val PACK = "Pack"
}

/**
 * Pushes the candidates from one ante report into [sink], in the order they should be
 * matched. Implementations should stop early once `sink.isComplete`.
 *
 * Taking the sink as a parameter rather than returning a list is what keeps this
 * allocation-free: the sink is the worker's long-lived MatchState, so adding a source
 * costs nothing per seed.
 */
fun interface ItemSource {
    fun feedInto(report: AnteReport, sink: MatchState)
}

val PackJokers = ItemSource { report, sink ->
    val ante = report.ante
    var slot = 0
    for ((kind, jokers) in report.packs) {
        if (kind.family != "Buffoon") continue
        for (k in jokers.indices) {
            slot++
            if (sink.isComplete) return@ItemSource
            sink.offer(jokers[k].item, ante, slot, Source.PACK)
        }
    }
}

val PackItems = ItemSource { report, sink ->
    val ante = report.ante
    var slot = 0
    for ((kind, jokers, consumables, cards) in report.packs) {
        val items = when (kind.family) {
            "Arcana" -> consumables
            "Celestial" -> consumables
            "Standard" -> cards.mapToItem()
            "Buffoon" -> jokers.mapToItem()
            "Spectral" -> consumables
            else -> return@ItemSource
        }
        for (k in items.indices) {
            slot++
            if (sink.isComplete) return@ItemSource
            sink.offer(items[k], ante, slot, Source.PACK)
        }
    }
}

val ShopItems = ItemSource { report, sink ->
    val ante = report.ante
    val shop = report.shopItems
    for (k in shop.indices) {
        if (sink.isComplete) return@ItemSource
        sink.offer(shop[k].item, ante, k + 1, Source.SHOP)
    }
}

/**
 * Adapter for a fetcher you already have, e.g. `listSource("Voucher") { getVouchers(it) }`.
 *
 * Handy for trying a new card type without rewriting the fetcher, but it rebuilds that
 * list for every ante of every seed -- once a source earns its place in a long run,
 * port it to the loop form above.
 */
fun listSource(label: String, fetch: (AnteReport) -> List<ItemLocation>) =
    ItemSource { report, sink ->
        for (found in fetch(report)) {
            if (sink.isComplete) return@ItemSource
            sink.offer(found.item, found.ante, found.itemNumber, label)
        }
    }

// ---------------------------------------------------------------------------
// Result collection
// ---------------------------------------------------------------------------

data class SeedResult(val seed: String, val score: Double, val matches: List<Match>, val reports: List<AnteReport>)

private val resultsLock = Mutex()

/** Min-heap: the weakest retained result is always at the head. */
private val topResults = java.util.PriorityQueue<SeedResult>(MAX_RESULTS + 1, compareBy { it.score })

/**
 * Lock-free fast reject. Once the heap is full this rises above THRESHOLD, so the
 * overwhelming majority of near-miss seeds never touch the mutex at all.
 */
@Volatile
private var cutoff = THRESHOLD

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
// Worker
// ---------------------------------------------------------------------------

@OptIn(ExperimentalAtomicApi::class)
val processedSeedsCounter = AtomicLong(0)

class Worker(
    specs: Array<RequestSpec>,
    private val sources: Array<ItemSource>,
    private val maxSearchAnte: Int,
    private val detail: Detail,
    private val ignoredVouchers: List<String>,
    private val shopItems: Int,
) {
    private val state = MatchState(specs)
    private val reports = ArrayList<AnteReport>(maxSearchAnte)

    /** Reused across every seed this worker sees; reset() replaces the constructor. */
    private val analyzer = SeedAnalyzer(detail)

    /** Built on first hit only, to re-generate the parts the filter pass skipped. */
    private var fullAnalyzer: SeedAnalyzer? = null

    suspend fun analyze(seed: String) {
        state.reset()
        reports.clear()
        analyzer.reset(seed)

        var antesRun = 0
        for (ante in 1..maxSearchAnte) {
            val report = analyzer.ante(ante, ignoredVouchers, shopItems)
            antesRun = ante
            reports.add(report)
            state.consume(report, sources)

            if (state.isComplete) break

            // Nothing reachable from here can clear the bar, so skip the remaining antes.
            if (state.upperBound(ante + 1) <= cutoff) return
        }

        if (!state.isComplete) return
        if (state.total <= cutoff) return

        record(seed, state.total, state.snapshot(), fullReports(seed, antesRun))
    }

    /**
     * The filter pass deliberately leaves unrequested card types ungenerated, so a
     * stored report has to be rebuilt at full detail. This runs on a handful of seeds
     * out of millions, so its cost is irrelevant.
     */
    private fun fullReports(seed: String, antes: Int): List<AnteReport> {
        if (detail === Detail.FULL) return ArrayList(reports)
        val a = fullAnalyzer ?: SeedAnalyzer(Detail.FULL).also { fullAnalyzer = it }
        a.reset(seed)
        return (1..antes).map { a.ante(it, ignoredVouchers, shopItems) }
    }
}

// ---------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------

@OptIn(ExperimentalAtomicApi::class)
suspend fun main() {
    val start = System.currentTimeMillis()

    val maxAnte = 4
    val shopItems = 50
    val seedsToCount = 10_000_000
    val ignoredVouchers = listOf("Planet_Merchant", "Magic_Trick")

    // Item Requests
    val specs = arrayOf(
        RequestSpec(cardFromDisplayName("2 of Clubs"), anteTarget = 3, slotTarget = 1, slotPriority = 100),
    )

    // Which parts of each ante report get searched, in match order.
    // Add a card type here, e.g. listSource("Voucher") { getVouchers(it) }.
    val sources = arrayOf(PackItems, ShopItems)

    // Everything no request mentions is left ungenerated during the search.
    val detail = Detail.forItems(
        specs.map { it.item },
        needEditions = specs.any { it.editionTarget != null }
    )

    // Same rule as before: a request with an ante target is searched two antes past it.
    // Note this ignores maxAnte entirely unless some request has no ante target.
    val maxSearchAnte = specs.maxOf { if (it.hasAnte) it.anteTarget + 2 else maxAnte }

    val maxPossible = specs.sumOf { it.maxScore }
    println("Max possible score: $maxPossible, threshold: $THRESHOLD, searching antes 1..$maxSearchAnte")
    println("Generating: $detail")

    val workerCount = Runtime.getRuntime().availableProcessors()

    TaskManager.submit(Task {
        // Batched so 10M seeds cost ~20k channel operations instead of 10M.
        val channel = Channel<Array<String>>(capacity = workerCount * 4)

        val producer = launch(Dispatchers.Default) {
            var sent = 0
            while (sent < seedsToCount) {
                val size = min(SEED_BATCH, seedsToCount - sent)
                channel.send(Array(size) { nextSeed() })
                sent += size
            }
            channel.close()
        }

        val workers = List(workerCount) {
            launch(Dispatchers.Default) {
                val worker = Worker(specs, sources, maxSearchAnte, detail, ignoredVouchers, shopItems)
                for (batch in channel) {
                    for (seed in batch) {
                        worker.analyze(seed)
                    }
                    // One atomic op per batch rather than per seed: that counter was a
                    // contended cache line being bounced between every core.
                    val now = processedSeedsCounter.addAndFetch(batch.size.toLong())
                    if (now / 100_000 != (now - batch.size) / 100_000) {
                        println("--- Progress: Processed ${now / 100_000 * 100_000} seeds so far ---")
                    }
                }
            }
        }

        producer.join()
        workers.joinAll()
    }).join()

    val sorted = topResults.sortedByDescending { it.score }
    println("Top ${sorted.size} of $seedsToCount seeds:")
    for (r in sorted.take(10)) {
        println("${r.seed} (${r.score}): ${r.matches}")
    }
    println("Took ${System.currentTimeMillis() - start} ms")
}