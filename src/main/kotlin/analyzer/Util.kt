package analyzer

data class ItemLocation(
    val ante: Int,
    val itemNumber: Int,
    val location: String,
    val item: Item
)

object Util {

    fun jokerInAnte(report: AnteReport, target: Item, limit: Int, edition: Item? = null): List<ItemLocation> {
        val result = mutableListOf<ItemLocation>()
        result.addAll(jokerInShop(report, target, edition, limit))
        result.addAll(jokerInPack(report, target, edition))
        return result
    }

    fun jokerInShop(report: AnteReport, target: Item, edition: Item? = null, limit: Int = Int.MAX_VALUE): List<ItemLocation> {
        val result = mutableListOf<ItemLocation>()
        val items = report.shopItems
        for (index in items.indices) {
            if (index >= limit) break // items are in shop-position order, so we can stop early
            val item = items[index]
            if (item.item === target && (edition == null || item.edition === edition)) {
                result.add(ItemLocation(report.ante, index + 1, "Shop", item.item))
            }
        }
        return result
    }

    fun jokerInPack(report: AnteReport, target: Item, edition: Item? = null): List<ItemLocation> {
        val result = mutableListOf<ItemLocation>()
        val packs = report.packs
        for (index in packs.indices) {
            val pack = packs[index]
            if (pack.kind.family != "Buffoon") continue

            var hasTarget = false
            var hasEdition = edition == null
            for ((_, item, _, edition1) in pack.jokers) {
                if (item === target) hasTarget = true
                if (edition != null && edition1 === edition) hasEdition = true
                if (hasTarget && hasEdition) break
            }
                if (hasTarget && hasEdition) {
                    result.add(ItemLocation(report.ante, index + 1, "Pack", target))
                }
            }
            return result
    }

    fun spectralInPack(report: AnteReport, target: StandardCard): List<Pair<Int, StandardCard>> {
    return report.packs
        .mapIndexed { index, result -> Pair(index, result) }
        .filter { it.second.kind.family == "Spectral" && it.second.cards.contains(target) }
        .map { Pair(it.first, target) }
    }




    private val jokerByDisplayName: Map<String, Item> by lazy { Pools.JOKERS.associateBy { it.displayName } }
    private val editionByDisplayName: Map<String, Item> by lazy { Pools.EDITIONS.associateBy { it.displayName } }
    private val tarotByDisplayName: Map<String, Item> by lazy { Pools.TAROTS.associateBy { it.displayName } }
    private val cardByDisplayName: Map<String, Item> by lazy { Pools.CARDS.associateBy { it.displayName } }

    fun jokerFromDisplayName(displayName: String): Item =
        jokerByDisplayName[displayName] ?: error("Unknown joker: $displayName")

    fun editionFromDisplayName(displayName: String): Item =
        editionByDisplayName[displayName] ?: error("Unknown edition: $displayName")

    fun tarotFromDisplayName(displayName: String): Item =
        tarotByDisplayName[displayName] ?: error("Unknown tarot: $displayName")

    fun cardFromDisplayName(displayName: String): Item =
        cardByDisplayName[displayName] ?: error("Unknown card: $displayName")


    fun List<ShopItem>.mapToItem(): List<Item> = this.map { it.item }
    fun List<StandardCard>.mapToItem(): List<Item> = this.map { it.base }

    //val knownSeed = "5I2A9TS8"
    private const val seedChars = "123456789ABCDEFGHIJKLMNPQRSTUVWXYZ"
    private var currentSeed = ""

    fun nextSeed(): String {
        if (currentSeed.isEmpty()) {
            currentSeed = seedChars[0].toString()
            return currentSeed
        }

        val chars = currentSeed.toCharArray()
        var carry = true
        var i = chars.size - 1

        // Increment characters from right to left
        while (i >= 0 && carry) {
            val charIndex = seedChars.indexOf(chars[i])
            if (charIndex == seedChars.length - 1) {
                // Wrap around to the first character
                chars[i] = seedChars[0]
                carry = true
            } else {
                // Move to the next character in seedChars
                chars[i] = seedChars[charIndex + 1]
                carry = false
            }
            i--
        }

        // If carry persisted past the first position, append a new leading character
        currentSeed = if (carry) {
            "${seedChars[0]}${String(chars)}"
        } else {
            String(chars)
        }

        return currentSeed

    }

    fun printReport(r: AnteReport) {
        println("\n==ANTE ${r.ante}==")
        println("Boss: ${r.boss}")
        println("Voucher: ${r.voucher}")
        println("Tags: ${r.tags.joinToString(", ")}")
        println("Shop Queue:")
        r.shopItems.forEachIndexed { i, s ->
            val edStr = s.edition?.let { it.displayName + " " } ?: ""
            println("  ${i + 1}) $edStr${s.item}")
        }
        println("Packs:")
        r.packs.forEach { p ->
            val contents = when {
                p.jokers.isNotEmpty() -> p.jokers.joinToString(", ") { j ->
                    (j.edition?.let { it.displayName + " " } ?: "") + j.item.displayName
                }
                p.consumables.isNotEmpty() -> p.consumables.joinToString(", ") { it.displayName }
                else -> p.cards.joinToString(", ") { it.toString() }
            }
            println("  ${p.kind.item.displayName}: $contents")
        }
    }

}