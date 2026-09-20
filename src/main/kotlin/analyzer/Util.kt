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



     // A Showman in the first six shop cards of ante 1 or 2, or in a pack.
     Condition(
     jokerFromDisplayName("Showman"),
     required = true,
     anteRange = 1..2,
     slotRange = 1..6,
     sources = Src.SHOP_OR_PACK,
     slotPriority = 8,
     antePriority = 10,
     ),

     // Temperance in the first eight shop cards only -- explicitly not from a pack.
     Condition(
     tarotFromDisplayName("Temperance"),
     required = true,
     anteRange = 1..2,
     slotRange = 1..8,
     sources = Src.SHOP,
     slotPriority = 5,
     ),

     // At least five Blueprints or Brainstorms, antes 2-8, shop or pack.
     // Earlier shop slots and earlier antes score higher.
     Condition(
     listOf(jokerFromDisplayName("Blueprint"), jokerFromDisplayName("Brainstorm")),
     count = 5,
     required = true,
     anteRange = 2..8,
     slotRange = 1..50,
     sources = Src.SHOP_OR_PACK,
     slotPriority = 4,
     antePriority = 3,
     label = "5x Blueprint/Brainstorm",

     ),

     // ...and at least one of them a natural Negative. A Negative Blueprint counts
     // toward both this and the condition above; that is the intended reading.
     Condition(
     listOf(jokerFromDisplayName("Blueprint"), jokerFromDisplayName("Brainstorm")),
     required = true,
     anteRange = 2..8,
     slotRange = 1..50,
     sources = Src.SHOP_OR_PACK,
     editionTarget = editionFromDisplayName("Negative"),
     editionPriority = 20,
     label = "a Negative Blueprint/Brainstorm",
     ),





     Condition(
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
     )


     Possible sources:
     Src.
         SHOP
         PACK
         SOUL
         TAG
         VOUCHER
         BOSS
         ANY
         SHOP_OR_PACK



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


     */
}