package analyzer

data class ItemLocation(
    val ante: Int,
    val itemNumber: Int,
    val location: String,
    val item: Item
)

object Util {

    private val jokerByDisplayName: Map<String, Item> by lazy { Pools.JOKERS.associateBy { it.displayName } }
    private val editionByDisplayName: Map<String, Item> by lazy { Pools.EDITIONS.associateBy { it.displayName } }
    private val tarotByDisplayName: Map<String, Item> by lazy { Pools.TAROTS.associateBy { it.displayName } }
    private val cardByDisplayName: Map<String, Item> by lazy { Pools.CARDS.associateBy { it.displayName } }
    private val spectralByDisplayName: Map<String, Item> by lazy { Pools.SPECTRALS.associateBy { it.displayName } }


    fun jokerFromDisplayName(displayName: String): Item =
        jokerByDisplayName[displayName] ?: error("Unknown joker: $displayName")

    fun editionFromDisplayName(displayName: String): Item =
        editionByDisplayName[displayName] ?: error("Unknown edition: $displayName")

    fun tarotFromDisplayName(displayName: String): Item =
        tarotByDisplayName[displayName] ?: error("Unknown tarot: $displayName")

    fun cardFromDisplayName(displayName: String): Item =
        cardByDisplayName[displayName] ?: error("Unknown card: $displayName")

    fun spectralFromDisplayName(displayName: String): Item =
        spectralByDisplayName[displayName] ?: error("Unknown spectral: $displayName")




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

    //val knownSeed = "5I2A9TS8"
    /**
     * RequestSpec(jokerFromDisplayName("Blueprint"), slotTarget = 1, anteTarget = 1),
     * RequestSpec(jokerFromDisplayName("Baron"), slotTarget = 2, anteTarget = 1),
     * RequestSpec(jokerFromDisplayName("Mime"), slotTarget = 3, anteTarget = 1),
     * RequestSpec(jokerFromDisplayName("Blueprint"), editionTarget = editionFromDisplayName("Negative"), slotTarget = 1, anteTarget = 1),
     * RequestSpec(jokerFromDisplayName("Brainstorm"), editionTarget = editionFromDisplayName("Foil"), slotTarget = 2, anteTarget = 1),
     * RequestSpec(jokerFromDisplayName("Blueprint"), editionTarget = editionFromDisplayName("Negative"), slotTarget = 1, anteTarget = 1),
     * RequestSpec(Item("The_Soul", "The Soul"), slotTarget = 1, anteTarget = 1, antePriority = 10),
     * RequestSpec(jokerFromDisplayName("Perkeo"), slotTarget = 1, slotPriority = 10, anteTarget = 1, antePriority = 10)
     * RequestSpec(jokerFromDisplayName("Perkeo"), editionTarget = editionFromDisplayName("Negative"), slotTarget = 1, slotPriority = 10, anteTarget = 1, antePriority = 10)
     * RequestSpec(Item("The_Soul", "The Soul"), slotTarget = 1, anteTarget = 1, antePriority = 10),
     *

    6 results in the first 10_000_000 seeds
    RequestSpec(jokerFromDisplayName("Blueprint"), editionTarget = editionFromDisplayName("Negative"), slotTarget = 2, anteTarget = 1),
    RequestSpec(jokerFromDisplayName("Blueprint"), editionTarget = editionFromDisplayName("Negative"), slotTarget = 2, anteTarget = 2),

    RequestSpec(tarotFromDisplayName("The Hermit"), slotTarget = 1, anteTarget = 1),
    RequestSpec(tarotFromDisplayName("Temperance"), slotTarget = 1, anteTarget = 1),
    RequestSpec(tarotFromDisplayName("The Fool"), slotTarget = 2, anteTarget = 1),
    RequestSpec(tarotFromDisplayName("The Fool"), slotTarget = 1, anteTarget = 1),

    RequestSpec(jokerFromDisplayName("Perkeo"), editionTarget = editionFromDisplayName("Negative"), slotTarget = 1, slotPriority = 20, anteTarget = 2, antePriority = 10) ,
    RequestSpec(Item("The_Soul", "The Soul"), slotTarget = 1, anteTarget = 2, antePriority = 10),
    RequestSpec(tarotFromDisplayName("Temperance"), slotTarget = 1, slotPriority = 5, anteTarget = 2, antePriority = 5),
    RequestSpec(jokerFromDisplayName("Blueprint"), editionTarget = editionFromDisplayName("Negative"), slotTarget = 1, slotPriority = 3, anteTarget = 2, antePriority = 5),
    RequestSpec(jokerFromDisplayName("Blueprint"), slotTarget = 1, slotPriority = 3, anteTarget = 2, antePriority = 5),


    RequestSpec(jokerFromDisplayName("Perkeo"), editionTarget = editionFromDisplayName("Negative"), slotTarget = 1, slotPriority = 20, anteTarget = 2, antePriority = 10) ,
    RequestSpec(Item("The_Soul", "The Soul"), slotTarget = 1, anteTarget = 2, antePriority = 10),
    RequestSpec(tarotFromDisplayName("Temperance"), slotTarget = 3, slotPriority = 5, anteTarget = 2, antePriority = 5),
    RequestSpec(jokerFromDisplayName("Blueprint"), editionTarget = editionFromDisplayName("Negative"), slotTarget = 1, slotPriority = 4, anteTarget = 2, antePriority = 3),
    RequestSpec(jokerFromDisplayName("Blueprint"), slotTarget = 1, slotPriority = 4, anteTarget = 2, antePriority = 3),
    RequestSpec(jokerFromDisplayName("Showman"), slotTarget = 1, slotPriority = 8, anteTarget = 1, antePriority = 10),

     OLD CONDITIONS, NEW CONDITIONS:




    // A Turtle Bean in ante 8, in the shop or a pack. Closer to slot 1 scores higher.
    Condition(
    jokerFromDisplayName("Turtle Bean"),
    required = true,
    anteRange = 8..8,
    slotRange = 1..50,
    sources = Src.SHOP_OR_PACK,
    slotPriority = 6,
    ),



    // A Negative Tag anywhere in antes 2-8. Not required, so it is pure bonus score.
    Condition(
    Pools.TAGS.first { it.id == "Negative_Tag" },
    anteRange = 2..8,
    sources = Src.TAG,
    antePriority = 2,
    ),





     */
}