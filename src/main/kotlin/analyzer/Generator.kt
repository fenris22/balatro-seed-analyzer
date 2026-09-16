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
    val packs: List<PackResult>
)

// ---------------------------------------------------------------------------
// Item 3: generate only what the search actually asks about
// ---------------------------------------------------------------------------

/**
 * Which card types the generator should actually produce.
 *
 * Skipping is safe precisely because of how the RNG is keyed: every card type draws
 * from its own stream, so never advancing the tarot stream cannot shift a single joker
 * roll. Anything that feeds back into another stream's *inputs* must still run --
 * see the voucher note in SeedAnalyzer.ante.
 *
 * Search runs with a mask derived from the request list; the handful of seeds that
 * survive get re-generated with FULL so their reports are complete.
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
) {
    companion object {
        val FULL = Detail(true, true, true, true, true, true, true, true)

        private fun ids(vararg pools: List<Item>): HashSet<String> {
            val out = HashSet<String>()
            for (p in pools) for (i in p) out.add(i.id)
            return out
        }

        private val JOKER_IDS = ids(
            Pools.COMMON_JOKERS, Pools.UNCOMMON_JOKERS, Pools.RARE_JOKERS, Pools.LEGENDARY_JOKERS
        )
        private val TAROT_IDS = ids(Pools.TAROTS)
        private val PLANET_IDS = ids(Pools.PLANETS)
        private val SPECTRAL_IDS = ids(Pools.SPECTRALS)
        private val CARD_IDS = ids(Pools.CARDS, Pools.ENHANCEMENTS, Pools.SEALS)
        private val BOSS_IDS = ids(Pools.BOSSES)
        private val TAG_IDS = ids(Pools.TAGS)
        private val EDITION_IDS = ids(Pools.EDITIONS)

        /**
         * Derives the mask from the items being searched for. [needEditions] should be
         * true if any request constrains an edition.
         */
        fun forItems(wanted: Collection<Item>, needEditions: Boolean = false): Detail {
            val w = wanted.map { it.id }.toHashSet()

            // The Soul and Black Hole are not in any pool -- they are substituted into a
            // draw, so wanting one means wanting the draw it can replace.
            val soul = "The_Soul" in w
            val blackHole = "Black_Hole" in w

            return Detail(
                jokers = w.any { it in JOKER_IDS },
                tarots = soul || w.any { it in TAROT_IDS },
                planets = blackHole || w.any { it in PLANET_IDS },
                spectrals = soul || blackHole || w.any { it in SPECTRAL_IDS },
                standardCards = w.any { it in CARD_IDS },
                editions = needEditions || w.any { it in EDITION_IDS },
                bosses = w.any { it in BOSS_IDS },
                tags = w.any { it in TAG_IDS },
            )
        }
    }

    override fun toString() = buildString {
        append("Detail(")
        val on = mutableListOf<String>()
        if (jokers) on.add("jokers"); if (tarots) on.add("tarots"); if (planets) on.add("planets")
        if (spectrals) on.add("spectrals"); if (standardCards) on.add("cards")
        if (editions) on.add("editions"); if (bosses) on.add("bosses"); if (tags) on.add("tags")
        append(if (on.isEmpty()) "nothing" else on.joinToString("+"))
        append(")")
    }
}

/** Shared placeholders, so a skipped draw costs no allocation. */
object Placeholder {
    val ITEM = Item("SKIPPED", "(not generated)")
    val SHOP = ShopItem("Skipped", ITEM)
    val PLAYING_CARD = ShopItem("PlayingCard", Item("PLAYING_CARD", "Playing Card"))
    val CARD = StandardCard(ITEM, null, null, null)
    val THE_SOUL = Item("The_Soul", "The Soul")
    val BLACK_HOLE = Item("Black_Hole", "Black Hole")
}

/**
 * Assumptions (see README): White Stake (no eternal/perishable/rental rolls
 * are interpreted), Red Deck, no Showman (booster packs never show a
 * duplicate card within the same pack, matching base game behavior), every
 * voucher offered is assumed bought immediately (needed for a coherent
 * multi-ante preview, since tier-2 vouchers are only obtainable after their
 * tier-1 pair is redeemed), and nothing is treated as "not yet discovered" -
 * every joker/tag/tarot/planet/spectral/voucher is eligible from the start.
 * The few restrictions that are NOT about discovery but are unconditional
 * base-game rules (which bosses/tags exist before a given ante, and the
 * voucher tier pairing) are still enforced.
 *
 * Reusable: call [reset] with a new seed instead of constructing a new instance,
 * so a worker allocates one analyzer for the whole run.
 */
class SeedAnalyzer(private val detail: Detail = Detail.FULL) {

    companion object {
        private val VOUCHER_INDEX: Map<String, Int> =
            Pools.VOUCHERS.withIndex().associate { (i, v) -> v.id to i }
    }

    private val rng = RngCache()
    private val activeVouchers = HashSet<String>()
    private var generatedFirstPack = false
    private val usedNormalBosses = HashSet<String>()
    private val usedFinisherBosses = HashSet<String>()

    /** Shop rates are constant within an ante, so they are computed once per ante. */
    private val rates = DoubleArray(5)
    private var rateTotal = 0.0

    constructor(seed: String, detail: Detail = Detail.FULL) : this(detail) {
        reset(seed)
    }

    fun reset(seed: String) {
        rng.reset(seed.uppercase())
        activeVouchers.clear()
        usedNormalBosses.clear()
        usedFinisherBosses.clear()
        generatedFirstPack = false
        rateTotal = 0.0
    }

    private fun isVoucherActive(id: String) = id in activeVouchers

    private fun activateVoucher(v: Item) {
        val idx = VOUCHER_INDEX[v.id] ?: -1
        activeVouchers.add(v.id)
        if (idx % 2 == 1) {
            activeVouchers.add(Pools.VOUCHERS[idx - 1].id) // owning tier-2 implies tier-1
        }
    }

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

    private fun nextVoucher(ante: Int, ignored: List<String>): Item {
        fun locked(v: Item): Boolean {
            val idx = VOUCHER_INDEX[v.id] ?: -1
            if (idx % 2 == 1 && Pools.VOUCHERS[idx - 1].id !in activeVouchers) return true
            if (v.id in activeVouchers) return true
            return false
        }
        var resampleNum = 0
        var candidate = rng.randChoice(RngKeys.VOUCHER, 0, ante, 0, Pools.VOUCHERS)
        while (locked(candidate)) {
            resampleNum++
            candidate = rng.randChoice(RngKeys.VOUCHER, 0, ante, resampleNum, Pools.VOUCHERS)
        }
        if (candidate.id !in ignored) activateVoucher(candidate)
        return candidate
    }

    private fun nextTags(ante: Int): List<Item> {
        if (!detail.tags) return emptyList()
        fun locked(t: Item) = t.id in Pools.TAG_ANTE_GATE && ante < 2
        val result = ArrayList<Item>(2)
        repeat(2) {
            var n = 0
            var t = rng.randChoice(RngKeys.TAG, 0, ante, 0, Pools.TAGS)
            while (locked(t)) {
                n++
                t = rng.randChoice(RngKeys.TAG, 0, ante, n, Pools.TAGS)
            }
            result.add(t)
        }
        return result
    }

    /** joker, tarot, planet, playingCard, spectral. Red Deck default. */
    private fun updateShopRates() {
        var tarotRate = 4.0
        var planetRate = 4.0
        if (isVoucherActive("Tarot_Tycoon")) tarotRate = 32.0
        else if (isVoucherActive("Tarot_Merchant")) tarotRate = 9.6
        if (isVoucherActive("Planet_Tycoon")) planetRate = 32.0
        else if (isVoucherActive("Planet_Merchant")) planetRate = 9.6
        rates[0] = 20.0
        rates[1] = tarotRate
        rates[2] = planetRate
        rates[3] = if (isVoucherActive("Magic_Trick")) 4.0 else 0.0
        rates[4] = 0.0
        rateTotal = rates[0] + rates[1] + rates[2] + rates[3] + rates[4]
    }

    private fun jokerRarityPool(rarity: String): List<Item> = when (rarity) {
        "Legendary" -> Pools.LEGENDARY_JOKERS
        "Rare" -> Pools.RARE_JOKERS
        "Uncommon" -> Pools.UNCOMMON_JOKERS
        else -> Pools.COMMON_JOKERS
    }

    private fun nextJokerRarity(ante: Int, srcId: Int): String {
        val v = rng.random(RngKeys.RARITY, srcId, ante, 0)
        return when {
            v > 0.95 -> "Rare"
            v > 0.7 -> "Uncommon"
            else -> "Common"
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

    private fun nextJoker(ante: Int, srcId: Int, excluded: MutableSet<String>? = null): ShopItem {
        val rarity = nextJokerRarity(ante, srcId)
        val pool = jokerRarityPool(rarity)
        val family = when (rarity) {
            "Legendary" -> RngKeys.JOKER4; "Rare" -> RngKeys.JOKER3
            "Uncommon" -> RngKeys.JOKER2; else -> RngKeys.JOKER1
        }
        // Legendary jokers key off a single stream, ignoring source and ante.
        val keySrc = if (rarity == "Legendary") 0 else srcId
        val keyAnte = if (rarity == "Legendary") 0 else ante

        var joker = rng.randChoice(family, keySrc, keyAnte, 0, pool)
        if (excluded != null) {
            var n = 0
            while (joker.id in excluded) {
                n++
                joker = rng.randChoice(family, keySrc, keyAnte, n, pool)
            }
            excluded.add(joker.id)
        }
        return ShopItem("Joker", joker, rarity, nextJokerEdition(ante, srcId))
    }

    private fun nextTarot(ante: Int, srcId: Int, soulable: Boolean, excluded: MutableSet<String>? = null): Item {
        if (soulable && rng.random(RngKeys.SOUL_TAROT, 0, ante, 0) > 0.997) return Placeholder.THE_SOUL
        var item = rng.randChoice(RngKeys.TAROT, srcId, ante, 0, Pools.TAROTS)
        if (excluded != null) {
            var n = 0
            while (item.id in excluded) {
                n++
                item = rng.randChoice(RngKeys.TAROT, srcId, ante, n, Pools.TAROTS)
            }
            excluded.add(item.id)
        }
        return item
    }

    private fun nextPlanet(ante: Int, srcId: Int, soulable: Boolean, excluded: MutableSet<String>? = null): Item {
        if (soulable && rng.random(RngKeys.SOUL_PLANET, 0, ante, 0) > 0.997) return Placeholder.BLACK_HOLE
        var item = rng.randChoice(RngKeys.PLANET, srcId, ante, 0, Pools.PLANETS)
        if (excluded != null) {
            var n = 0
            while (item.id in excluded) {
                n++
                item = rng.randChoice(RngKeys.PLANET, srcId, ante, n, Pools.PLANETS)
            }
            excluded.add(item.id)
        }
        return item
    }

    private fun nextSpectral(ante: Int, srcId: Int, soulable: Boolean, excluded: MutableSet<String>? = null): Item {
        if (soulable) {
            // Two draws from the same stream, as in the original -- both must advance it.
            var forced: Item? = null
            if (rng.random(RngKeys.SOUL_SPECTRAL, 0, ante, 0) > 0.997) forced = Placeholder.THE_SOUL
            if (rng.random(RngKeys.SOUL_SPECTRAL, 0, ante, 0) > 0.997) forced = Placeholder.BLACK_HOLE
            if (forced != null) return forced
        }
        var item = rng.randChoice(RngKeys.SPECTRAL, srcId, ante, 0, Pools.SPECTRALS)
        var n = 0
        while (item.id == "RETRY" || (excluded != null && item.id in excluded)) {
            n++
            item = rng.randChoice(RngKeys.SPECTRAL, srcId, ante, n, Pools.SPECTRALS)
        }
        excluded?.add(item.id)
        return item
    }

    private fun nextShopItem(ante: Int): ShopItem {
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
        // The slot still exists and still counts toward its index; only the contents
        // of an unwanted type are left ungenerated.
        return when (type) {
            0 -> if (detail.jokers) nextJoker(ante, RngKeys.SRC_SHO) else Placeholder.SHOP
            1 -> if (detail.tarots) ShopItem("Tarot", nextTarot(ante, RngKeys.SRC_SHO, soulable = false)) else Placeholder.SHOP
            2 -> if (detail.planets) ShopItem("Planet", nextPlanet(ante, RngKeys.SRC_SHO, soulable = false)) else Placeholder.SHOP
            3 -> Placeholder.PLAYING_CARD
            else -> if (detail.spectrals) ShopItem("Spectral", nextSpectral(ante, RngKeys.SRC_SHO, soulable = false)) else Placeholder.SHOP
        }
    }

    private fun standardEnhancement(ante: Int): Item? {
        if (rng.random(RngKeys.STDSET, 0, ante, 0) <= 0.6) return null
        return rng.randChoice(RngKeys.ENHANCED, 0, ante, 0, Pools.ENHANCEMENTS)
    }

    private fun standardEdition(ante: Int): Item? {
        val v = rng.random(RngKeys.STD_EDITION, 0, ante, 0)
        return when {
            v > 0.988 -> Pools.EDITIONS[3]
            v > 0.96 -> Pools.EDITIONS[2]
            v > 0.92 -> Pools.EDITIONS[1]
            else -> null
        }
    }

    private fun standardSeal(ante: Int): Item? {
        if (rng.random(RngKeys.STDSEAL, 0, ante, 0) <= 0.8) return null
        val v = rng.random(RngKeys.STDSEALTYPE, 0, ante, 0)
        return when {
            v > 0.75 -> Pools.SEALS[1]
            v > 0.5 -> Pools.SEALS[2]
            v > 0.25 -> Pools.SEALS[3]
            else -> Pools.SEALS[4]
        }
    }

    private fun standardCard(ante: Int): StandardCard {
        val enh = standardEnhancement(ante)
        val base = rng.randChoice(RngKeys.FRONTSTA, 0, ante, 0, Pools.CARDS)
        val edition = standardEdition(ante)
        val seal = standardSeal(ante)
        return StandardCard(base, enh, edition, seal)
    }

    private fun nextPackKind(ante: Int): Pools.PackKind {
        if (ante <= 2 && !generatedFirstPack) {
            generatedFirstPack = true
            return Pools.PACK_KINDS.first { it.item.id == "Buffoon_Pack" }
        }
        val poll = rng.random(RngKeys.SHOP_PACK, 0, ante, 0) * Pools.PACK_TOTAL_WEIGHT
        var weight = 0.0
        for (k in Pools.PACK_KINDS) {
            weight += k.weight
            if (weight >= poll) return k
        }
        return Pools.PACK_KINDS.last()
    }

    /**
     * The pack kind is always rolled -- it decides whether this is a Buffoon pack at all,
     * and its stream must stay in step. Only the contents are conditional.
     */
    private fun openPack(ante: Int, kind: Pools.PackKind): PackResult = when (kind.family) {
        "Arcana" -> if (!detail.tarots) PackResult(kind) else {
            val ex = HashSet<String>()
            PackResult(kind, consumables = (1..kind.size).map { nextTarot(ante, RngKeys.SRC_AR1, true, ex) })
        }
        "Celestial" -> if (!detail.planets) PackResult(kind) else {
            val ex = HashSet<String>()
            PackResult(kind, consumables = (1..kind.size).map { nextPlanet(ante, RngKeys.SRC_PL1, true, ex) })
        }
        "Spectral" -> if (!detail.spectrals) PackResult(kind) else {
            val ex = HashSet<String>()
            PackResult(kind, consumables = (1..kind.size).map { nextSpectral(ante, RngKeys.SRC_SPE, true, ex) })
        }
        "Buffoon" -> if (!detail.jokers) PackResult(kind) else {
            val ex = HashSet<String>()
            PackResult(kind, jokers = (1..kind.size).map { nextJoker(ante, RngKeys.SRC_BUF, ex) })
        }
        else -> if (!detail.standardCards) PackResult(kind) else
            PackResult(kind, cards = (1..kind.size).map { standardCard(ante) })
    }

    fun ante(ante: Int, ignoredVouchers: List<String>, numShopItems: Int, numPacks: Int = if (ante == 1) 4 else 6): AnteReport {
        val boss = nextBoss(ante)
        // The voucher is never skippable: it feeds activeVouchers, which sets the shop
        // rates, which move the cdt thresholds for every shop slot in this ante.
        val voucher = nextVoucher(ante, ignoredVouchers)
        val tags = nextTags(ante)
        updateShopRates()
        val shopItems = (1..numShopItems).map { nextShopItem(ante) }
        val packs = (1..numPacks).map { openPack(ante, nextPackKind(ante)) }
        return AnteReport(ante, boss, voucher, tags, shopItems, packs)
    }
}