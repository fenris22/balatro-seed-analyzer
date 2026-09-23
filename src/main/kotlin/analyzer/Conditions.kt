package analyzer

import kotlin.math.abs
import kotlin.math.max
import kotlin.collections.flatten

// ---------------------------------------------------------------------------
// Where a card can come from
// ---------------------------------------------------------------------------

/**
 * Bit per source, so a condition can say "shop only" or "shop or pack" in one int.
 *
 * SOUL is separate from PACK on purpose: a Soul's legendary joker is not a pack slot, it
 * is the n'th Soul of the run, and its "slot" is that ordinal. A condition wanting a
 * legendary must therefore allow SOUL, and one wanting a Buffoon-pack joker must not.
 */
object Src {
    const val SHOP = 1
    const val PACK = 2
    const val SOUL = 4
    const val TAG = 8
    const val VOUCHER = 16
    const val BOSS = 32

    const val ANY = SHOP or PACK or SOUL or TAG or VOUCHER or BOSS
    const val SHOP_OR_PACK = SHOP or PACK

    fun name(bit: Int): String = when (bit) {
        SHOP -> "Shop"; PACK -> "Pack"; SOUL -> "Soul"
        TAG -> "Tag"; VOUCHER -> "Voucher"; BOSS -> "Boss"
        else -> "?"
    }

    fun describe(mask: Int): String {
        if (mask == ANY) return "anywhere"
        val parts = ArrayList<String>(3)
        for (b in intArrayOf(SHOP, PACK, SOUL, TAG, VOUCHER, BOSS)) if (mask and b != 0) parts.add(name(b))
        return parts.joinToString("/")
    }
}

/** No slot ceiling. Large enough to cover any shop or pack index. */
const val NO_SLOT_LIMIT = 4096

// ---------------------------------------------------------------------------
// Scoring curve
// ---------------------------------------------------------------------------

/** floor(10 * exp(-0.5 * d)); zero from d = 5 on, so five entries cover the domain. */
private val DECAY = doubleArrayOf(10.0, 6.0, 3.0, 2.0, 1.0)

internal fun decay(distance: Int): Double =
    if (distance < DECAY.size) DECAY[distance] else 0.0

// ---------------------------------------------------------------------------
// The condition
// ---------------------------------------------------------------------------

/**
 * One search condition.
 *
 * The model has two independent halves, and keeping them apart is what makes this both
 * expressive and fast:
 *
 *  - **The window** ([anteRange], [slotRange], [sources], [editionTarget]) decides what
 *    can match *at all*. A card outside the window is not a near miss, it is not a match;
 *    no score, no partial credit.
 *
 *  - **The targets** ([anteTarget], [slotTarget] and their priorities) decide how *well* a
 *    match inside the window scores. This is where "the closer the bean is to slot 1, the
 *    better" lives.
 *
 * [required] turns the window into a hard gate: if the condition is not satisfied by the
 * time its window closes, the seed is discarded outright. That is a far stronger prune
 * than the score bound -- it does not depend on the cutoff being well tuned, and it fires
 * the moment the last ante in the window ends rather than waiting for the arithmetic to
 * become hopeless.
 *
 * [items] is an OR set: any one of them counts. [count] is how many distinct cards must be
 * found. Together they express "at least 5 Blueprints or Brainstorms".
 *
 * A card is offered to **every** condition it satisfies, not just the first. That is what
 * lets "5 blueprints-or-brainstorms" and "at least 1 of them negative" both be counted by
 * the same Negative Blueprint, which is what those two conditions are meant to say when
 * written together. It also means two conditions naming the same item both fill from the
 * same cards, so "3 Blueprints" is one condition with `count = 3`, not three conditions.
 */
class Condition(
    val items: List<Item>,
    val count: Int = 1,
    val required: Boolean = false,
    val anteRange: IntRange = 1..8,
    val slotRange: IntRange = 1..NO_SLOT_LIMIT,
    val sources: Int = Src.SHOP_OR_PACK,
    val editionTarget: Item? = null,
    val editionPriority: Int = 1,
    anteTarget: Int? = null,
    val antePriority: Int = 1,
    slotTarget: Int? = null,
    val slotPriority: Int = 1,
    val label: String? = null,
) {
    constructor(
        item: Item,
        count: Int = 1,
        required: Boolean = false,
        anteRange: IntRange = 1..8,
        slotRange: IntRange = 1..NO_SLOT_LIMIT,
        sources: Int = Src.SHOP_OR_PACK,
        editionTarget: Item? = null,
        editionPriority: Int = 1,
        anteTarget: Int? = null,
        antePriority: Int = 1,
        slotTarget: Int? = null,
        slotPriority: Int = 1,
        label: String? = null,
    ) : this(
        listOf(item), count, required, anteRange, slotRange, sources, editionTarget,
        editionPriority, anteTarget, antePriority, slotTarget, slotPriority, label
    )

    companion object {
        /**
         * Pass as [editionTarget] to require a *base* card -- one with no edition at all.
         * Leaving editionTarget null means "any edition is fine", which is not the same.
         */
        val NO_EDITION = Item("__no_edition__", "No Edition")
    }

    /** Defaults to the best end of the window, which is what "closer is better" means. */
    val anteTarget: Int = anteTarget ?: anteRange.first
    val slotTarget: Int = slotTarget ?: slotRange.first

    val itemIds: Array<String> = items.map { it.id }.toTypedArray()
    private val editionTargetId: String? = editionTarget?.id
    private val requiresNoEdition = editionTargetId == NO_EDITION.id

    val anteMin = anteRange.first
    val anteMax = anteRange.last
    val slotMin = slotRange.first
    val slotMax = slotRange.last

    val maxSlotScore = 10.0 * slotPriority
    val maxAnteScore = 10.0 * antePriority
    val maxEditionScore = if (editionTarget != null) 10.0 * editionPriority else 0.0

    /** Best a single match can score, used for every bound. */
    val maxPerMatch = maxSlotScore + maxAnteScore + maxEditionScore

    /** Best the whole condition can score. */
    val maxScore = count * maxPerMatch

    init {
        require(count >= 1) { "count must be at least 1: $this" }
        require(items.isNotEmpty()) { "condition has no items: $label" }
        require(anteMin in 1 .. anteMax) { "bad ante range $anteRange in $this" }
        require(slotMin in 1 .. slotMax) { "bad slot range $slotRange in $this" }
        require(sources != 0) { "condition allows no sources: $this" }
    }

    /** True if this card, at this position, is inside the window. */
    fun accepts(itemId: String, edition: Item?, ante: Int, slot: Int, srcBit: Int): Boolean {
        if (sources and srcBit == 0) return false
        if (ante !in anteMin .. anteMax) return false
        if (slot !in slotMin .. slotMax) return false
        if (!acceptsEdition(edition)) return false
        for (id in itemIds) if (id == itemId) return true
        return false
    }

    /**
     * The edition is a gate, not a target: a Perkeo is never a near-miss for a Negative
     * Perkeo, so there is no distance to decay over. editionPriority survives as a flat
     * weight, so an edition-gated condition can be made worth more than its neighbours
     * without changing what it accepts.
     */
    fun acceptsEdition(edition: Item?): Boolean {
        val target = editionTargetId ?: return true
        if (requiresNoEdition) return edition == null
        return edition != null && edition.id == target
    }

    /** Score for one match inside the window. Callers must have checked [accepts] first. */
    fun scoreAt(ante: Int, slot: Int): Double {
        val s = if (slot <= slotTarget) maxSlotScore else decay(slot - slotTarget) * slotPriority
        val a = if (ante == anteTarget) maxAnteScore else decay(abs(ante - anteTarget)) * antePriority
        return s + a + maxEditionScore
    }

    /**
     * Best a single further match could score, given the scan has reached [ante].
     *
     * Zero once the window has closed, which is what makes an unmet required condition
     * collapse the bound to hopeless at exactly the moment it becomes unsatisfiable.
     */
    fun bestPerMatchFrom(ante: Int): Double {
        if (ante > anteMax) return 0.0
        val effective = max(ante, anteMin)
        val a = decay(abs(effective - anteTarget)) * antePriority
        return maxSlotScore + a + maxEditionScore
    }

    /** True once this condition can never be satisfied from [ante] onwards. */
    fun windowClosed(ante: Int): Boolean = ante > anteMax

    /**
     * Best a further match can score if it has to land in *this* ante, at [slot] or later.
     *
     * Separate from [bestPerMatchFrom] because in the last allowed ante the slot penalty
     * is locked in and can only grow, whereas before then the condition can always wait
     * for a fresh ante and get slot 1 back.
     */
    fun bestInAnteFrom(ante: Int, slot: Int): Double {
        if (slot > slotMax) return 0.0
        val s = if (slot <= slotTarget) maxSlotScore else decay(slot - slotTarget) * slotPriority
        val a = decay(abs(ante - anteTarget)) * antePriority
        return s + a + maxEditionScore
    }

    /**
     * True if this condition can no longer be satisfied, given the scan has reached shop
     * slot [slot] of [ante].
     *
     * Only valid from inside the shop loop, and that is the point: packs are generated
     * before the shop, so by the time we are here every pack and Soul this ante had to
     * offer has already been seen. A pack-only condition in its last allowed ante is
     * therefore dead the moment the shop starts, and a shop condition dies the moment the
     * slot passes its ceiling.
     */
    fun unreachableInShop(ante: Int, slot: Int): Boolean {
        if (ante > anteMax) return true
        if (ante < anteMax) return false
        if (sources and Src.SHOP == 0) return true
        return slot > slotMax
    }

    val displayName: String
        get() = label ?: buildString {
            if (count > 1) append("${count}x ")
            editionTarget?.let { append("${it.displayName} ") }
            append(items.joinToString("/") { it.displayName })
        }

    override fun toString(): String = buildString {
        if (required) append("REQUIRE ") else append("score ")
        append(displayName)
        append(" @antes $anteMin-$anteMax")
        if (slotMax < NO_SLOT_LIMIT) append(", slots $slotMin-$slotMax")
        append(", ").append(Src.describe(sources))
    }
}

// ---------------------------------------------------------------------------
// Per-seed matching state
// ---------------------------------------------------------------------------

/**
 * Mutable matching state for one seed.
 *
 * One instance per worker, reset between seeds, so a run allocates this once per thread
 * rather than once per seed.
 */
class MatchState(val conditions: Array<Condition>) {
    private val n = conditions.size
    private val found = IntArray(n)
    private val need = IntArray(n) { conditions[it].count }
    private val requiredIdx = (0 until n).filter { conditions[it].required }.toIntArray()

    /** Recorded only for reporting; the filter itself never reads these. */
    private val hits = Array(n) { ArrayList<Match>(conditions[it].count) }
    private var keepDetail = false

    var total = 0.0
        private set

    /** Conditions not yet at their count. */
    var unmet = n
        private set

    /** Set when a required condition's window closed unsatisfied. */
    var dead = false
        private set

    /**
     * Prefilter in front of the id comparison. Nearly every card generated matches
     * nothing, and this turns that case into one array load. False positives fall through
     * to the real check, so it stays exact.
     */
    private val maybe = BooleanArray(2048).also { m ->
        for (c in conditions) for (id in c.itemIds) m[id.hashCode() and 2047] = true
    }

    val maxTotal: Double = conditions.sumOf { it.maxScore }
    val isComplete: Boolean get() = unmet == 0
    fun satisfied(i: Int): Boolean = found[i] >= need[i]

    /** Every required condition met. A seed is only a result if this is true. */
    val requirementsMet: Boolean
        get() {
            for (i in requiredIdx) if (found[i] < need[i]) return false
            return true
        }

    fun reset(detail: Boolean = false) {
        java.util.Arrays.fill(found, 0)
        total = 0.0
        unmet = n
        dead = false
        keepDetail = detail
        if (detail) for (h in hits) h.clear()
    }

    /**
     * Offer one card to every condition whose window accepts it.
     *
     * No early break: a Negative Blueprint legitimately counts toward both a "5 blueprints"
     * condition and a "at least 1 negative" condition, which is exactly what those two
     * express when written together.
     */
    fun offer(item: Item, edition: Item?, ante: Int, slot: Int, srcBit: Int) {
        val id = item.id
        if (!maybe[id.hashCode() and 2047]) return
        for (i in 0 until n) {
            if (found[i] >= need[i]) continue
            val c = conditions[i]
            if (!c.accepts(id, edition, ante, slot, srcBit)) continue
            val sc = c.scoreAt(ante, slot)
            total += sc
            found[i]++
            if (found[i] == need[i]) unmet--
            if (keepDetail) hits[i].add(Match(c, item, edition, ante, slot, Src.name(srcBit), sc))
        }
    }

    /**
     * Call at the end of each ante. Kills the seed as soon as a required condition can no
     * longer be satisfied.
     *
     * This is the cheap, decisive prune: it needs no cutoff, no score arithmetic, and it
     * fires the instant a window closes rather than waiting for the bound to collapse.
     */
    fun closeAnte(ante: Int) {
        for (i in requiredIdx) {
            if (found[i] >= need[i]) continue
            if (conditions[i].windowClosed(ante + 1)) { dead = true; return }
        }
    }

    /**
     * Kills the seed when a required condition has run out of room inside the current
     * ante's shop. Call from the shop loop; see [Condition.unreachableInShop].
     */
    fun closeSlot(ante: Int, slot: Int) {
        for (i in requiredIdx) {
            if (found[i] >= need[i]) continue
            if (conditions[i].unreachableInShop(ante, slot)) { dead = true; return }
        }
    }

    /**
     * True if some unmet condition could still accept a shop card at [slot] of [ante] or
     * later in this ante's shop.
     *
     * When false, the rest of this shop cannot change the outcome, so it is not generated.
     * This is exact, not a heuristic: shop cards draw only from the shop's own per-ante
     * streams (cdt, rarity/Joker/edi "sho", Tarotsho), which nothing else reads, so
     * skipping them moves no other draw. Slot ceilings only fall as the slot rises, so
     * once this is false for a slot it stays false for the rest of the shop.
     *
     * It does not depend on the cutoff, so it also applies to verification scans.
     */
    fun shopCanMatch(ante: Int, slot: Int): Boolean {
        for (i in 0 until n) {
            if (found[i] >= need[i]) continue
            val c = conditions[i]
            if (c.sources and Src.SHOP == 0) continue
            if (ante < c.anteMin || ante > c.anteMax) continue
            if (slot > c.slotMax) continue
            return true
        }
        return false
    }

    /**
     * Upper bound on the final total, assuming the scan has reached shop slot [slot] of
     * [ante] and nothing further has matched yet.
     *
     * Two cases per outstanding condition: it can wait for a later ante, where slot 1 is
     * available again but the ante penalty is at least one step worse, or it must finish
     * here, where the slot penalty is already locked in. Taking the better of the two is
     * what makes slot pruning do nothing until the later antes are themselves hopeless --
     * and everything once they are.
     *
     * Deliberately loose in one respect: it charges every outstanding match the same best
     * score, ignoring that they must land in distinct slots. Tightening that buys little
     * now that the required-window kill carries the pruning, and a loose-but-correct bound
     * never drops a seed it should have kept.
     */
    fun upperBound(ante: Int, slot: Int = 1): Double {
        var bound = total
        for (i in 0 until n) {
            val missing = need[i] - found[i]
            if (missing <= 0) continue
            val c = conditions[i]
            val here = c.bestInAnteFrom(ante, slot)
            val later = if (ante < c.anteMax) c.bestPerMatchFrom(ante + 1) else 0.0
            bound += missing * max(here, later)
        }
        return bound
    }

    fun snapshot(): List<Match> = hits.asIterable().flatten()

    fun summary(): String = (0 until n).joinToString("; ") { i ->
        "${conditions[i].displayName} ${found[i]}/${need[i]}"
    }
}

/** One matched card, built only for seeds that are kept. */
class Match(
    val condition: Condition,
    val item: Item,
    val edition: Item?,
    val ante: Int,
    val slot: Int,
    val source: String,
    val score: Double,
) {
    override fun toString(): String {
        val ed = edition?.let { "${it.displayName} " } ?: ""
        return "$ed${item.displayName} ($source a$ante #$slot = ${"%.0f".format(score)})"
    }
}