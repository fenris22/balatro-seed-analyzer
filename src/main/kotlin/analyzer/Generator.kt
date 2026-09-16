package analyzer

data class ShopItem(val kind: String, val item: Item, val rarity: String? = null, val edition: Item? = null)

data class StandardCard(val base: Item, val enhancement: Item?, val edition: Item?, val seal: Item?) {
    override fun toString(): String {
        val parts = mutableListOf<String>()
        seal?.let { parts.add(it.displayName) }
        edition?.let { parts.add(it.displayName) }
        enhancement?.let { parts.add(it.displayName) }
        parts.add(base.displayName)
        return parts.joinToString(" ")
    }
}

data class PackResult(
    val kind: Pools.PackKind,
    val jokers: List<ShopItem> = emptyList(),
    val consumables: List<Item> = emptyList(),
    val cards: List<StandardCard> = emptyList()
)

data class AnteReport(
    val ante: Int,
    val boss: Item,
    val voucher: Item,
    val tags: List<Item>,
    val shopItems: List<ShopItem>,
    val packs: List<PackResult>,
    /** Legendary jokers the Souls found in this ante would hand out, in order. */
    val soulJokers: List<Item> = emptyList(),
)

// Rarity codes. Small ints so the joker path indexes tables instead of comparing Strings.
const val R_COMMON = 0
const val R_UNCOMMON = 1
const val R_RARE = 2
const val R_LEGENDARY = 3

val RARITY_NAMES = arrayOf("Common", "Uncommon", "Rare", "Legendary")

/** Pools as arrays, built once. Every draw indexes one of these. */
object PoolArr {    val COMMON_JOKERS = Pools.COMMON_JOKERS.toTypedArray()
    val UNCOMMON_JOKERS = Pools.UNCOMMON_JOKERS.toTypedArray()
    val RARE_JOKERS = Pools.RARE_JOKERS.toTypedArray()
    val LEGENDARY_JOKERS = Pools.LEGENDARY_JOKERS.toTypedArray()
    val TAROTS = Pools.TAROTS.toTypedArray()
    val PLANETS = Pools.PLANETS.toTypedArray()
    val SPECTRALS = Pools.SPECTRALS.toTypedArray()
    val VOUCHERS = Pools.VOUCHERS.toTypedArray()
    val TAGS = Pools.TAGS.toTypedArray()
    val ENHANCEMENTS = Pools.ENHANCEMENTS.toTypedArray()
    val CARDS = Pools.CARDS.toTypedArray()
    val PACK_KINDS = Pools.PACK_KINDS.toTypedArray()
    val BUFFOON_PACK = PACK_KINDS.first { it.item.id == "Buffoon_Pack" }
    val PACK_CUM = DoubleArray(PACK_KINDS.size).also { out ->
        var w = 0.0
        for (i in PACK_KINDS.indices) { w += PACK_KINDS[i].weight; out[i] = w }
    }

    /** Vouchers whose presence changes the shop rates, resolved to indices once. */
    val V_TAROT_TYCOON = Pools.VOUCHERS.indexOfFirst { it.id == "Tarot_Tycoon" }
    val V_TAROT_MERCHANT = Pools.VOUCHERS.indexOfFirst { it.id == "Tarot_Merchant" }
    val V_PLANET_TYCOON = Pools.VOUCHERS.indexOfFirst { it.id == "Planet_Tycoon" }
    val V_PLANET_MERCHANT = Pools.VOUCHERS.indexOfFirst { it.id == "Planet_Merchant" }
    val V_MAGIC_TRICK = Pools.VOUCHERS.indexOfFirst { it.id == "Magic_Trick" }

    val TAG_GATED = BooleanArray(Pools.TAGS.size) { Pools.TAGS[it].id in Pools.TAG_ANTE_GATE }
}

/** Indexed by rarity code. */
val JOKER_POOLS = arrayOf(
    PoolArr.COMMON_JOKERS, PoolArr.UNCOMMON_JOKERS, PoolArr.RARE_JOKERS, PoolArr.LEGENDARY_JOKERS
)

val JOKER_FAMILIES = intArrayOf(
    RngKeys.JOKER1, RngKeys.JOKER2, RngKeys.JOKER3, RngKeys.JOKER4
)

// ---------------------------------------------------------------------------
// Streaming scan
// ---------------------------------------------------------------------------

abstract class ScanSink {
    /** Consulted before each shop slot. False stops the scan of this seed immediately. */
    open fun wantMoreShop(ante: Int, slot: Int): Boolean = true

    open fun onBoss(ante: Int, item: Item) {}
    open fun onVoucher(ante: Int, item: Item) {}
    open fun onTag(ante: Int, index: Int, item: Item) {}
    open fun onPackKind(ante: Int, index: Int, kind: Pools.PackKind) {}
    open fun onPackJoker(ante: Int, slot: Int, item: Item, rarity: String, edition: Item?) {}
    open fun onPackConsumable(ante: Int, slot: Int, family: String, item: Item) {}
    open fun onPackCard(ante: Int, slot: Int, card: StandardCard) {}
    open fun onShopItem(ante: Int, slot: Int, kind: String, item: Item, rarity: String?, edition: Item?) {}

    /**
     * The legendary joker a Soul hands out. [soulIndex] is 1 for the first Soul in the
     * run, 2 for the second and so on, which is the ordinal that matters: the Joker4
     * stream is a single run-long queue, not per-ante.
     */
    open fun onSoulJoker(ante: Int, soulIndex: Int, item: Item, edition: Item?) {}
}

/** Collects a scan back into an AnteReport. Used for the handful of seeds that are kept. */
class ReportSink : ScanSink() {
    private var boss: Item = Placeholder.ITEM
    private var voucher: Item = Placeholder.ITEM
    private val tags = ArrayList<Item>(2)
    private val shop = ArrayList<ShopItem>(64)
    private val packs = ArrayList<PackResult>(8)
    private val souls = ArrayList<Item>(2)

    private var kind: Pools.PackKind? = null
    private var jokers = ArrayList<ShopItem>(4)
    private var consumables = ArrayList<Item>(5)
    private var cards = ArrayList<StandardCard>(5)

    fun begin() {
        boss = Placeholder.ITEM
        voucher = Placeholder.ITEM
        tags.clear(); shop.clear(); packs.clear(); souls.clear()
        kind = null
        jokers = ArrayList(4); consumables = ArrayList(5); cards = ArrayList(5)
    }

    private fun flushPack() {
        val k = kind ?: return
        packs.add(PackResult(k, jokers, consumables, cards))
        jokers = ArrayList(4); consumables = ArrayList(5); cards = ArrayList(5)
    }

    fun finish(ante: Int): AnteReport {
        flushPack()
        kind = null
        return AnteReport(ante, boss, voucher, ArrayList(tags), ArrayList(shop), ArrayList(packs), ArrayList(souls))
    }

    override fun onBoss(ante: Int, item: Item) { boss = item }
    override fun onVoucher(ante: Int, item: Item) { voucher = item }
    override fun onTag(ante: Int, index: Int, item: Item) { tags.add(item) }

    override fun onPackKind(ante: Int, index: Int, kind: Pools.PackKind) {
        flushPack()
        this.kind = kind
    }

    override fun onPackJoker(ante: Int, slot: Int, item: Item, rarity: String, edition: Item?) {
        jokers.add(ShopItem("Joker", item, rarity, edition))
    }

    override fun onPackConsumable(ante: Int, slot: Int, family: String, item: Item) { consumables.add(item) }
    override fun onPackCard(ante: Int, slot: Int, card: StandardCard) { cards.add(card) }
    override fun onSoulJoker(ante: Int, soulIndex: Int, item: Item, edition: Item?) {
        souls.add(if (edition == null) item else Item(item.id, "${edition.displayName} ${item.displayName}"))
    }

    override fun onShopItem(ante: Int, slot: Int, kind: String, item: Item, rarity: String?, edition: Item?) {
        shop.add(ShopItem(kind, item, rarity, edition))
    }
}

// ---------------------------------------------------------------------------
// Detail mask
// ---------------------------------------------------------------------------

/**
 * Which card types the generator should actually produce.
 *
 * Skipping is safe because of how the RNG is keyed: every card type draws from its own
 * stream, so never advancing the tarot stream cannot shift a single joker roll. The one
 * thing that is never skippable is the voucher, which feeds the shop rates.
 *
 * [souls] and [soulJokers] are separate on purpose. Detecting a Soul costs one roll on
 * the soul stream per consumable slot; resolving the legendary joker it hands out costs
 * a draw on the Joker4 stream, and that only happens on the rare slots where a Soul
 * actually appeared.
 */
class Detail(
    val jokers: Boolean,
    val tarots: Boolean,
    val planets: Boolean,
    val spectrals: Boolean,
    val standardCards: Boolean,
    val editions: Boolean,
    val bosses: Boolean,
    val tags: Boolean,
    val souls: Boolean = false,
    val soulJokers: Boolean = false,
) {
    /** True if Arcana/Celestial/Spectral packs need opening at all. */
    val needsConsumablePacks = tarots || planets || spectrals || souls

    companion object {
        val FULL = Detail(true, true, true, true, true, true, true, true, souls = true, soulJokers = true)

        private fun ids(vararg pools: List<Item>): HashSet<String> {
            val out = HashSet<String>()
            for (p in pools) for (i in p) out.add(i.id)
            return out
        }

        /**
         * Legendary jokers are deliberately excluded: they never appear in a shop or a
         * Buffoon pack, only from a Soul, so asking for Perkeo must not switch on the
         * whole joker generator.
         */
        private val JOKER_IDS = ids(Pools.COMMON_JOKERS, Pools.UNCOMMON_JOKERS, Pools.RARE_JOKERS)
        private val LEGENDARY_IDS = ids(Pools.LEGENDARY_JOKERS)
        private val TAROT_IDS = ids(Pools.TAROTS)
        private val PLANET_IDS = ids(Pools.PLANETS)
        private val SPECTRAL_IDS = ids(Pools.SPECTRALS)
        private val CARD_IDS = ids(Pools.CARDS, Pools.ENHANCEMENTS, Pools.SEALS)
        private val BOSS_IDS = ids(Pools.BOSSES)
        private val TAG_IDS = ids(Pools.TAGS)
        private val EDITION_IDS = ids(Pools.EDITIONS)

        fun forItems(wanted: Collection<Item>, needEditions: Boolean = false): Detail {
            val w = wanted.map { it.id }.toHashSet()
            val legendary = w.any { it in LEGENDARY_IDS }
            // The Soul and Black Hole are not in any pool -- they are substituted into a
            // consumable draw, so wanting one means wanting the substitution roll.
            val soulItself = "The_Soul" in w
            val blackHole = "Black_Hole" in w

            return Detail(
                jokers = w.any { it in JOKER_IDS },
                tarots = w.any { it in TAROT_IDS },
                planets = w.any { it in PLANET_IDS },
                spectrals = w.any { it in SPECTRAL_IDS },
                standardCards = w.any { it in CARD_IDS },
                editions = needEditions || w.any { it in EDITION_IDS },
                bosses = w.any { it in BOSS_IDS },
                tags = w.any { it in TAG_IDS },
                souls = legendary || soulItself || blackHole,
                soulJokers = legendary,
            )
        }
    }

    override fun toString() = buildString {
        append("Detail(")
        val on = mutableListOf<String>()
        if (jokers) on.add("jokers"); if (tarots) on.add("tarots"); if (planets) on.add("planets")
        if (spectrals) on.add("spectrals"); if (standardCards) on.add("cards")
        if (editions) on.add("editions"); if (bosses) on.add("bosses"); if (tags) on.add("tags")
        if (souls) on.add("souls"); if (soulJokers) on.add("soulJokers")
        append(if (on.isEmpty()) "nothing" else on.joinToString("+"))
        append(")")
    }
}

/** Shared placeholders, so a skipped draw costs no allocation. */
object Placeholder {
    val ITEM = Item("SKIPPED", "(not generated)")
    val PLAYING_CARD = Item("PLAYING_CARD", "Playing Card")
    val THE_SOUL = Item("The_Soul", "The Soul")
    val BLACK_HOLE = Item("Black_Hole", "Black Hole")
}

/**
 * Assumptions (see README): White Stake, Red Deck, no Showman, every voucher offered is
 * assumed bought immediately (except for vouchers in [ignoredVouchers]), nothing treated as "not yet discovered".
 *
 * Reusable: call [reset] with a new seed instead of constructing a new instance.
 */
class SeedAnalyzer(
    private val detail: Detail = Detail.FULL,
    ignoredVouchers: List<String> = emptyList(),
) {
    private val rng = RngCache()

    /** Voucher state as flags by pool index -- no String hashing on a per-ante path. */
    private val voucherActive = BooleanArray(PoolArr.VOUCHERS.size)
    private val voucherIgnored = BooleanArray(PoolArr.VOUCHERS.size).also { arr ->
        for (i in PoolArr.VOUCHERS.indices) if (PoolArr.VOUCHERS[i].id in ignoredVouchers) arr[i] = true
    }

    private var generatedFirstPack = false
    private val usedNormalBosses = HashSet<String>()
    private val usedFinisherBosses = HashSet<String>()

    /** Shop rates are constant within an ante, so they are computed once per ante. */
    private val rates = DoubleArray(5)
    private var rateTotal = 0.0

    /**
     * Per-pack duplicate suppression. Pool entries are singletons, so identity comparison
     * over a tiny array beats hashing the id String on every draw.
     */
    private val excluded = arrayOfNulls<Item>(8)
    private var excludedCount = 0

    /** Legendary jokers already handed out this run, and how many Souls have appeared. */
    private val legendaryTaken = BooleanArray(PoolArr.LEGENDARY_JOKERS.size)
    private var soulCount = 0
    private var soulEdition: Item? = null

    /** Output of drawJoker, to avoid allocating a ShopItem per joker on the filter path. */
    private var jokerItem: Item = Placeholder.ITEM
    private var jokerRarity: Int = R_COMMON
    private var jokerEdition: Item? = null

    private val reportSink = ReportSink()

    constructor(seed: String, detail: Detail = Detail.FULL, ignoredVouchers: List<String> = emptyList())
            : this(detail, ignoredVouchers) {
        reset(seed)
    }

    fun reset(seed: String) {
        rng.reset(seed)
        resetState()
    }

    /** [chars] must already be uppercase. */
    fun reset(chars: CharArray, len: Int) {
        rng.reset(chars, len)
        resetState()
    }

    private fun resetState() {
        java.util.Arrays.fill(voucherActive, false)
        java.util.Arrays.fill(legendaryTaken, false)
        usedNormalBosses.clear()
        usedFinisherBosses.clear()
        generatedFirstPack = false
        rateTotal = 0.0
        soulCount = 0
        excludedCount = 0
    }

    private fun excludeClear() { excludedCount = 0 }

    private fun isExcluded(item: Item): Boolean {
        for (i in 0 until excludedCount) if (excluded[i] === item) return true
        return false
    }

    private fun exclude(item: Item) {
        if (excludedCount < excluded.size) excluded[excludedCount++] = item
    }

    // --- vouchers ----------------------------------------------------------

    private fun voucherLocked(idx: Int): Boolean {
        if (idx % 2 == 1 && !voucherActive[idx - 1]) return true // tier-2 needs its tier-1
        return voucherActive[idx]
    }

    private fun nextVoucher(ante: Int): Item {
        val n = PoolArr.VOUCHERS.size
        var resample = 0
        var idx = rng.randIndex(RngKeys.VOUCHER, 0, ante, 0, n)
        while (voucherLocked(idx)) {
            resample++
            idx = rng.randIndex(RngKeys.VOUCHER, 0, ante, resample, n)
        }
        if (!voucherIgnored[idx]) {
            voucherActive[idx] = true
            if (idx % 2 == 1) voucherActive[idx - 1] = true // owning tier-2 implies tier-1
        }
        return PoolArr.VOUCHERS[idx]
    }

    private fun updateShopRates() {
        var tarotRate = 4.0
        var planetRate = 4.0
        if (voucherActive[PoolArr.V_TAROT_TYCOON]) tarotRate = 32.0
        else if (voucherActive[PoolArr.V_TAROT_MERCHANT]) tarotRate = 9.6
        if (voucherActive[PoolArr.V_PLANET_TYCOON]) planetRate = 32.0
        else if (voucherActive[PoolArr.V_PLANET_MERCHANT]) planetRate = 9.6
        rates[0] = 20.0
        rates[1] = tarotRate
        rates[2] = planetRate
        rates[3] = if (voucherActive[PoolArr.V_MAGIC_TRICK]) 4.0 else 0.0
        rates[4] = 0.0
        rateTotal = rates[0] + rates[1] + rates[2] + rates[3] + rates[4]
    }

    // --- bosses and tags ---------------------------------------------------

    private fun nextBoss(ante: Int): Item {
        if (!detail.bosses) return Placeholder.ITEM
        val isFinisherAnte = ante % 8 == 0
        val fullPool = if (isFinisherAnte) Pools.FINISHER_BOSS_ITEMS else Pools.NORMAL_BOSSES
        val used = if (isFinisherAnte) usedFinisherBosses else usedNormalBosses
        fun available() = fullPool.filter {
            it.id !in used && (Pools.BOSS_ANTE_GATE[it.id] ?: 1) <= ante
        }
        var avail = available()
        if (avail.isEmpty()) {
            used.clear()
            avail = available()
        }
        val chosen = avail[rng.randIndex(RngKeys.BOSS, 0, 0, 0, avail.size)]
        used.add(chosen.id)
        return chosen
    }

    // --- jokers ------------------------------------------------------------

    /**
     * Rarity as a small int rather than a String.
     *
     * drawJoker runs for every shop joker and every Buffoon-pack joker, and the old code
     * did three String comparisons per draw to pick the pool, the stream family and the
     * key shape. The int indexes tables instead; the name is only materialised when a
     * sink actually wants it.
     */
    private fun nextJokerRarity(ante: Int, srcId: Int): Int {
        val v = rng.random(RngKeys.RARITY, srcId, ante, 0)
        return when {
            v > 0.95 -> R_RARE
            v > 0.7 -> R_UNCOMMON
            else -> R_COMMON
        }
    }

    private fun nextJokerEdition(ante: Int, srcId: Int): Item? {
        if (!detail.editions) return null
        val poll = rng.random(RngKeys.EDITION, srcId, ante, 0)
        return when {
            poll > 0.997 -> Pools.EDITIONS[4]
            poll > 0.994 -> Pools.EDITIONS[3]
            poll > 0.98 -> Pools.EDITIONS[2]
            poll > 0.96 -> Pools.EDITIONS[1]
            else -> null
        }
    }

    /** Writes into jokerItem / jokerRarity / jokerEdition rather than returning an object. */
    private fun drawJoker(ante: Int, srcId: Int, useExcluded: Boolean) {
        val rarity = nextJokerRarity(ante, srcId)
        val pool = JOKER_POOLS[rarity]
        val family = JOKER_FAMILIES[rarity]
        // Legendary jokers key off a single run-long stream, ignoring source and ante.
        val legendary = rarity == R_LEGENDARY
        val keySrc = if (legendary) 0 else srcId
        val keyAnte = if (legendary) 0 else ante

        var joker = rng.randChoice(family, keySrc, keyAnte, 0, pool)
        if (useExcluded) {
            var n = 0
            while (isExcluded(joker)) {
                n++
                joker = rng.randChoice(family, keySrc, keyAnte, n, pool)
            }
            exclude(joker)
        }
        jokerItem = joker
        jokerRarity = rarity
        jokerEdition = nextJokerEdition(ante, srcId)
    }

    /**
     * The legendary joker the next Soul hands out.
     *
     * The Joker4 stream carries no source or ante, so it is one queue for the whole run:
     * the first Soul consumed takes the first entry, the second takes the next, and so
     * on. Without Showman a joker you already hold cannot come up again, which is what
     * the resample loop models -- the same shape the original code used for legendary
     * draws. Verify that against Immolate if exact duplicate handling matters to you;
     * it only bites on a seed with two or more Souls.
     *
     * Editions on Soul jokers are not modelled. If you need to search for a Negative
     * Perkeo, the edition roll goes here, and you will need its key confirmed first.
     */
    private fun nextSoulJoker(ante: Int): Item {
        val pool = PoolArr.LEGENDARY_JOKERS
        var n = 0
        var idx = rng.randIndex(RngKeys.JOKER4, 0, 0, 0, pool.size)
        while (legendaryTaken[idx]) {
            n++
            idx = rng.randIndex(RngKeys.JOKER4, 0, 0, n, pool.size)
        }
        legendaryTaken[idx] = true
        soulEdition = soulJokerEdition(ante)
        return pool[idx]
    }

    /**
     * Edition for a Soul joker.
     *
     * Balatro builds the legendary joker with key_append "sou", so this rolls on
     * "edisou<ante>" -- its own stream, shared with nothing, which is why switching it on
     * cannot shift any other result. The thresholds are the same as for any other joker.
     *
     * Worth confirming against Immolate before you trust a Negative Perkeo hit: the pool
     * draw above uses a bare "Joker4" key with no source or ante, which is the convention
     * this codebase already carried, and the two ought to agree about whether the append
     * applies. If Immolate appends there too, both this and nextSoulJoker need the source
     * and ante added.
     */
    private fun soulJokerEdition(ante: Int): Item? {
        if (!detail.editions) return null
        val poll = rng.random(RngKeys.EDITION, RngKeys.SRC_SOU, ante, 0)
        return when {
            poll > 0.997 -> Pools.EDITIONS[4]
            poll > 0.994 -> Pools.EDITIONS[3]
            poll > 0.98 -> Pools.EDITIONS[2]
            poll > 0.96 -> Pools.EDITIONS[1]
            else -> null
        }
    }

    /** Called for each Soul as it is found, in encounter order. */
    private fun onSoulFound(ante: Int, sink: ScanSink) {
        soulCount++
        if (detail.soulJokers) {
            val joker = nextSoulJoker(ante)
            sink.onSoulJoker(ante, soulCount, joker, soulEdition)
        }
    }

    // --- consumables -------------------------------------------------------

    private fun nextTarot(ante: Int, srcId: Int, soulable: Boolean, useExcluded: Boolean): Item {
        if (soulable && rng.random(RngKeys.SOUL_TAROT, 0, ante, 0) > 0.997) return Placeholder.THE_SOUL
        var item = rng.randChoice(RngKeys.TAROT, srcId, ante, 0, PoolArr.TAROTS)
        if (useExcluded) {
            var n = 0
            while (isExcluded(item)) {
                n++
                item = rng.randChoice(RngKeys.TAROT, srcId, ante, n, PoolArr.TAROTS)
            }
            exclude(item)
        }
        return item
    }

    private fun nextPlanet(ante: Int, srcId: Int, soulable: Boolean, useExcluded: Boolean): Item {
        if (soulable && rng.random(RngKeys.SOUL_PLANET, 0, ante, 0) > 0.997) return Placeholder.BLACK_HOLE
        var item = rng.randChoice(RngKeys.PLANET, srcId, ante, 0, PoolArr.PLANETS)
        if (useExcluded) {
            var n = 0
            while (isExcluded(item)) {
                n++
                item = rng.randChoice(RngKeys.PLANET, srcId, ante, n, PoolArr.PLANETS)
            }
            exclude(item)
        }
        return item
    }

    /** The two soul rolls, in order. Black Hole wins if both hit, as in the original. */
    private fun spectralSubstitution(ante: Int): Item? {
        var forced: Item? = null
        if (rng.random(RngKeys.SOUL_SPECTRAL, 0, ante, 0) > 0.997) forced = Placeholder.THE_SOUL
        if (rng.random(RngKeys.SOUL_SPECTRAL, 0, ante, 0) > 0.997) forced = Placeholder.BLACK_HOLE
        return forced
    }

    private fun nextSpectral(ante: Int, srcId: Int, soulable: Boolean, useExcluded: Boolean): Item {
        if (soulable) spectralSubstitution(ante)?.let { return it }
        var item = rng.randChoice(RngKeys.SPECTRAL, srcId, ante, 0, PoolArr.SPECTRALS)
        var n = 0
        while (item.id == "RETRY" || (useExcluded && isExcluded(item))) {
            n++
            item = rng.randChoice(RngKeys.SPECTRAL, srcId, ante, n, PoolArr.SPECTRALS)
        }
        if (useExcluded) exclude(item)
        return item
    }

    // --- standard cards ----------------------------------------------------

    private fun standardCard(ante: Int): StandardCard {
        val enh = if (rng.random(RngKeys.STDSET, 0, ante, 0) <= 0.6) null
        else rng.randChoice(RngKeys.ENHANCED, 0, ante, 0, PoolArr.ENHANCEMENTS)

        val base = rng.randChoice(RngKeys.FRONTSTA, 0, ante, 0, PoolArr.CARDS)

        val v = rng.random(RngKeys.STD_EDITION, 0, ante, 0)
        val edition = when {
            v > 0.988 -> Pools.EDITIONS[3]
            v > 0.96 -> Pools.EDITIONS[2]
            v > 0.92 -> Pools.EDITIONS[1]
            else -> null
        }

        val seal = if (rng.random(RngKeys.STDSEAL, 0, ante, 0) <= 0.8) null else {
            val s = rng.random(RngKeys.STDSEALTYPE, 0, ante, 0)
            when {
                s > 0.75 -> Pools.SEALS[1]
                s > 0.5 -> Pools.SEALS[2]
                s > 0.25 -> Pools.SEALS[3]
                else -> Pools.SEALS[4]
            }
        }
        return StandardCard(base, enh, edition, seal)
    }

    // --- packs -------------------------------------------------------------

    /**
     * Cumulative weights are precomputed, so this is a scan over a DoubleArray rather
     * than re-summing PackKind.weight through a field load on every pack of every ante.
     * The accumulation order is unchanged, so the partial sums are bit-identical.
     */
    private fun nextPackKind(ante: Int): Pools.PackKind {
        if (ante <= 2 && !generatedFirstPack) {
            generatedFirstPack = true
            return PoolArr.BUFFOON_PACK
        }
        val poll = rng.random(RngKeys.SHOP_PACK, 0, ante, 0) * Pools.PACK_TOTAL_WEIGHT
        val cum = PoolArr.PACK_CUM
        for (i in cum.indices) if (cum[i] >= poll) return PoolArr.PACK_KINDS[i]
        return PoolArr.PACK_KINDS[PoolArr.PACK_KINDS.size - 1]
    }

    /**
     * Souls-only Arcana: the substitution roll lives on its own stream (SOUL_TAROT),
     * separate from the tarot choice (TAROT), so when no tarot is being searched for we
     * can roll the first and never touch the second. That is one draw per slot instead
     * of one plus a choice plus any duplicate resamples.
     *
     * Note the asymmetry: when tarots *are* wanted the soul roll is mandatory, because a
     * slot that turns into a Soul does not draw a tarot, so skipping it would desync the
     * tarot stream.
     */
    private fun scanArcana(ante: Int, size: Int, sink: ScanSink) {
        if (detail.tarots) {
            for (i in 0 until size) {
                val item = nextTarot(ante, RngKeys.SRC_AR1, soulable = true, useExcluded = true)
                sink.onPackConsumable(ante, i + 1, "Arcana", item)
                if (item === Placeholder.THE_SOUL) onSoulFound(ante, sink)
            }
        } else if (detail.souls) {
            for (i in 0 until size) {
                if (rng.random(RngKeys.SOUL_TAROT, 0, ante, 0) > 0.997) {
                    sink.onPackConsumable(ante, i + 1, "Arcana", Placeholder.THE_SOUL)
                    onSoulFound(ante, sink)
                }
            }
        }
    }

    private fun scanCelestial(ante: Int, size: Int, sink: ScanSink) {
        if (detail.planets) {
            for (i in 0 until size) {
                sink.onPackConsumable(ante, i + 1, "Celestial", nextPlanet(ante, RngKeys.SRC_PL1, true, true))
            }
        } else if (detail.souls) {
            // Celestial packs can only substitute Black Hole, never a Soul.
            for (i in 0 until size) {
                if (rng.random(RngKeys.SOUL_PLANET, 0, ante, 0) > 0.997) {
                    sink.onPackConsumable(ante, i + 1, "Celestial", Placeholder.BLACK_HOLE)
                }
            }
        }
    }

    private fun scanSpectral(ante: Int, size: Int, sink: ScanSink) {
        if (detail.spectrals) {
            for (i in 0 until size) {
                val item = nextSpectral(ante, RngKeys.SRC_SPE, soulable = true, useExcluded = true)
                sink.onPackConsumable(ante, i + 1, "Spectral", item)
                if (item === Placeholder.THE_SOUL) onSoulFound(ante, sink)
            }
        } else if (detail.souls) {
            for (i in 0 until size) {
                val forced = spectralSubstitution(ante) ?: continue
                sink.onPackConsumable(ante, i + 1, "Spectral", forced)
                if (forced === Placeholder.THE_SOUL) onSoulFound(ante, sink)
            }
        }
    }

    // --- the scan ----------------------------------------------------------

    /**
     * Generates one ante, pushing everything into [sink] as it goes.
     *
     * Packs are emitted before the shop even though the original generated the shop
     * first. That reordering is free -- every card type draws from its own keyed stream,
     * so no value depends on when it is drawn relative to another type -- and it puts the
     * expensive part last, where [ScanSink.wantMoreShop] can cut it short.
     *
     * Returns the number of shop slots actually generated, for instrumentation.
     */
    fun scanAnte(
        ante: Int,
        numShopItems: Int,
        sink: ScanSink,
        numPacks: Int = if (ante == 1) 4 else 6,
    ): Int {
        val boss = nextBoss(ante)
        if (detail.bosses) sink.onBoss(ante, boss)

        // Never skippable: it feeds voucherActive, which sets the shop rates, which move
        // the cdt thresholds for every shop slot in this ante and every later one.
        sink.onVoucher(ante, nextVoucher(ante))

        if (detail.tags) {
            val n = PoolArr.TAGS.size
            repeat(2) { which ->
                var r = 0
                var idx = rng.randIndex(RngKeys.TAG, 0, ante, 0, n)
                while (PoolArr.TAG_GATED[idx] && ante < 2) {
                    r++
                    idx = rng.randIndex(RngKeys.TAG, 0, ante, r, n)
                }
                sink.onTag(ante, which, PoolArr.TAGS[idx])
            }
        }

        updateShopRates()

        var jokerSlot = 0
        for (p in 0 until numPacks) {
            val kind = nextPackKind(ante)
            sink.onPackKind(ante, p, kind)
            excludeClear()
            when (kind.family) {
                "Buffoon" -> if (detail.jokers) {
                    for (i in 0 until kind.size) {
                        drawJoker(ante, RngKeys.SRC_BUF, useExcluded = true)
                        jokerSlot++
                        sink.onPackJoker(ante, jokerSlot, jokerItem, RARITY_NAMES[jokerRarity], jokerEdition)
                    }
                }
                "Arcana" -> scanArcana(ante, kind.size, sink)
                "Celestial" -> scanCelestial(ante, kind.size, sink)
                "Spectral" -> scanSpectral(ante, kind.size, sink)
                else -> if (detail.standardCards) {
                    for (i in 0 until kind.size) sink.onPackCard(ante, i + 1, standardCard(ante))
                }
            }
        }

        var generated = 0
        for (slot in 1..numShopItems) {
            if (!sink.wantMoreShop(ante, slot)) break
            generated++

            var roll = rng.random(RngKeys.CDT, 0, ante, 0) * rateTotal
            val type: Int
            if (roll < rates[0]) type = 0
            else {
                roll -= rates[0]
                if (roll < rates[1]) type = 1
                else {
                    roll -= rates[1]
                    if (roll < rates[2]) type = 2
                    else {
                        roll -= rates[2]
                        type = if (roll < rates[3]) 3 else 4
                    }
                }
            }

            when (type) {
                0 -> if (detail.jokers) {
                    drawJoker(ante, RngKeys.SRC_SHO, useExcluded = false)
                    sink.onShopItem(ante, slot, "Joker", jokerItem, RARITY_NAMES[jokerRarity], jokerEdition)
                } else sink.onShopItem(ante, slot, "Skipped", Placeholder.ITEM, null, null)
                1 -> if (detail.tarots) {
                    sink.onShopItem(ante, slot, "Tarot", nextTarot(ante, RngKeys.SRC_SHO, false, false), null, null)
                } else sink.onShopItem(ante, slot, "Skipped", Placeholder.ITEM, null, null)
                2 -> if (detail.planets) {
                    sink.onShopItem(ante, slot, "Planet", nextPlanet(ante, RngKeys.SRC_SHO, false, false), null, null)
                } else sink.onShopItem(ante, slot, "Skipped", Placeholder.ITEM, null, null)
                3 -> sink.onShopItem(ante, slot, "PlayingCard", Placeholder.PLAYING_CARD, null, null)
                else -> if (detail.spectrals) {
                    sink.onShopItem(ante, slot, "Spectral", nextSpectral(ante, RngKeys.SRC_SHO, false, false), null, null)
                } else sink.onShopItem(ante, slot, "Skipped", Placeholder.ITEM, null, null)
            }
        }
        return generated
    }

    /** Full-detail report, for the seeds that survive the filter. */
    fun ante(ante: Int, numShopItems: Int, numPacks: Int = if (ante == 1) 4 else 6): AnteReport {
        reportSink.begin()
        scanAnte(ante, numShopItems, reportSink, numPacks)
        return reportSink.finish(ante)
    }
}