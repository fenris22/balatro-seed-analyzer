package analyzer

/** A problem in a condition or settings as written, reported back to the page or the file's author. */
class SpecError(message: String, val conditionIndex: Int? = null) : Exception(message)

/**
 * Everything a condition can name, grouped the way the web page lists it.
 *
 * [defaultSources] is what the page ticks when an item of that group is first added: the
 * only places that kind of card can come from.
 */
object Catalog {
    class Group(val key: String, val name: String, val items: List<Item>, val defaultSources: Int)

    val groups: List<Group> = listOf(
        Group("common", "Common jokers", Pools.COMMON_JOKERS, Src.SHOP_OR_PACK),
        Group("uncommon", "Uncommon jokers", Pools.UNCOMMON_JOKERS, Src.SHOP_OR_PACK),
        Group("rare", "Rare jokers", Pools.RARE_JOKERS, Src.SHOP_OR_PACK),
        Group("legendary", "Legendary jokers", Pools.LEGENDARY_JOKERS, Src.SOUL),
        Group("tarot", "Tarot cards", Pools.TAROTS, Src.SHOP_OR_PACK),
        Group("planet", "Planet cards", Pools.PLANETS, Src.SHOP_OR_PACK),
        Group("spectral", "Spectral cards", Pools.SPECTRALS.filter { it.id != "RETRY" }.distinctBy { it.id }, Src.PACK),
        Group("special", "The Soul and Black Hole", listOf(Placeholder.THE_SOUL, Placeholder.BLACK_HOLE), Src.PACK),
        Group("voucher", "Vouchers", Pools.VOUCHERS, Src.VOUCHER),
        Group("tag", "Tags", Pools.TAGS, Src.TAG),
        Group("boss", "Boss blinds", Pools.BOSSES, Src.BOSS),
        Group("card", "Playing cards (Standard packs)", Pools.CARDS, Src.PACK),
    )

    val byId: Map<String, Item> = groups.flatMap { it.items }.associateBy { it.id }

    /** Edition choices. "No_Edition" means "must be a base card", not "any". */
    val editions: List<Item> = Pools.EDITIONS

    val sourceNames = linkedMapOf(
        "SHOP" to Src.SHOP, "PACK" to Src.PACK, "SOUL" to Src.SOUL,
        "TAG" to Src.TAG, "VOUCHER" to Src.VOUCHER, "BOSS" to Src.BOSS,
    )

    fun toJson(): Map<String, Any?> = mapOf(
        "groups" to groups.map { g ->
            mapOf(
                "key" to g.key, "name" to g.name,
                "sources" to sourceList(g.defaultSources),
                "items" to g.items.map { mapOf("id" to it.id, "name" to it.displayName) },
            )
        },
        "editions" to editions.map { mapOf("id" to it.id, "name" to it.displayName) },
        "sources" to sourceNames.keys.toList(),
        "vouchers" to Pools.VOUCHERS.map { mapOf("id" to it.id, "name" to it.displayName) },
    )

    fun sourceList(mask: Int): List<String> = sourceNames.filter { mask and it.value != 0 }.keys.toList()
}

// ---------------------------------------------------------------------------
// Reading values out of parsed JSON
// ---------------------------------------------------------------------------

private fun Map<String, Any?>.int(key: String, default: Int?, where: String, idx: Int?): Int? {
    val v = this[key] ?: return default
    return when (v) {
        is Long -> if (v in Int.MIN_VALUE..Int.MAX_VALUE) v.toInt() else throw SpecError("$where: $key is too large", idx)
        is Double -> if (v == Math.rint(v)) v.toInt() else throw SpecError("$where: $key must be a whole number", idx)
        is String -> if (v.isBlank()) default else v.trim().toIntOrNull()
            ?: throw SpecError("$where: $key '$v' is not a whole number", idx)
        else -> throw SpecError("$where: $key must be a number", idx)
    }
}

private fun Map<String, Any?>.bool(key: String, default: Boolean): Boolean = when (val v = this[key]) {
    null -> default
    is Boolean -> v
    is String -> v.equals("true", ignoreCase = true)
    else -> default
}

@Suppress("UNCHECKED_CAST")
fun asObject(v: Any?, what: String): Map<String, Any?> =
    v as? Map<String, Any?> ?: throw SpecError("$what must be a JSON object")

fun asList(v: Any?, what: String): List<Any?> = when (v) {
    null -> emptyList()
    is List<*> -> v
    else -> throw SpecError("$what must be a list")
}

// ---------------------------------------------------------------------------
// Conditions
// ---------------------------------------------------------------------------

/**
 * Builds a [Condition] from its JSON form (what the web page sends and saves):
 *
 *   { "items": ["Blueprint", "Brainstorm"], "count": 1, "required": true,
 *     "anteMin": 1, "anteMax": 2, "slotMin": 1, "slotMax": null,
 *     "sources": ["PACK"], "edition": "Negative", "editionPriority": 10,
 *     "anteTarget": null, "antePriority": 10, "slotTarget": null, "slotPriority": 1,
 *     "label": null }
 *
 * Everything but "items" is optional and defaults as the Condition constructor does.
 * [forceRequired] builds the condition as required whatever it says, for estimating how
 * often it is met on its own.
 */
fun conditionFromJson(raw: Any?, index: Int, forceRequired: Boolean = false): Condition {
    val where = "Condition ${index + 1}"
    val o = raw as? Map<*, *> ?: throw SpecError("$where must be an object", index)
    @Suppress("UNCHECKED_CAST") o as Map<String, Any?>

    val ids = asList(o["items"], "$where items").map { it?.toString() ?: "" }
    if (ids.isEmpty()) throw SpecError("$where has no items: pick at least one card", index)
    val items = ids.map { id -> Catalog.byId[id] ?: throw SpecError("$where: unknown item '$id'", index) }
        .distinctBy { it.id }

    val sourceNames = asList(o["sources"] ?: listOf("SHOP", "PACK"), "$where sources")
    var sources = 0
    for (s in sourceNames) {
        sources = sources or (Catalog.sourceNames[s.toString().uppercase()]
            ?: throw SpecError("$where: unknown source '$s'", index))
    }
    if (sources == 0) throw SpecError("$where has no sources ticked", index)

    val edition = when (val e = o["edition"]?.toString()?.takeIf { it.isNotBlank() && it != "Any" }) {
        null -> null
        "No_Edition" -> Condition.NO_EDITION
        else -> Pools.EDITIONS.firstOrNull { it.id == e } ?: throw SpecError("$where: unknown edition '$e'", index)
    }

    val anteMin = o.int("anteMin", 1, where, index)!!
    val anteMax = o.int("anteMax", 8, where, index)!!
    val slotMin = o.int("slotMin", 1, where, index)!!
    val slotMax = o.int("slotMax", null, where, index) ?: NO_SLOT_LIMIT
    val count = o.int("count", 1, where, index)!!

    if (anteMin < 1) throw SpecError("$where: the first ante must be at least 1", index)
    if (anteMax < anteMin) throw SpecError("$where: the last ante ($anteMax) is before the first ($anteMin)", index)
    if (anteMax > MAX_ANTE) throw SpecError("$where: antes above $MAX_ANTE are not supported", index)
    if (slotMin < 1) throw SpecError("$where: the first slot must be at least 1", index)
    if (slotMax < slotMin) throw SpecError("$where: the last slot ($slotMax) is before the first ($slotMin)", index)
    if (slotMax > NO_SLOT_LIMIT) throw SpecError("$where: the last slot can be at most $NO_SLOT_LIMIT", index)
    if (count < 1) throw SpecError("$where: the count must be at least 1", index)

    val anteTarget = o.int("anteTarget", null, where, index)
    val slotTarget = o.int("slotTarget", null, where, index)
    if (anteTarget != null && anteTarget !in anteMin..anteMax) {
        throw SpecError("$where: the best ante ($anteTarget) must be inside the ante range $anteMin-$anteMax", index)
    }
    if (slotTarget != null && slotTarget !in slotMin..slotMax) {
        throw SpecError("$where: the best slot ($slotTarget) must be inside the slot range", index)
    }
    fun priority(key: String): Int {
        val p = o.int(key, 1, where, index)!!
        if (p < 0 || p > 1000) throw SpecError("$where: $key must be between 0 and 1000", index)
        return p
    }

    return try {
        Condition(
            items = items,
            count = count,
            required = forceRequired || o.bool("required", false),
            anteRange = anteMin..anteMax,
            slotRange = slotMin..slotMax,
            sources = sources,
            editionTarget = edition,
            editionPriority = priority("editionPriority"),
            anteTarget = anteTarget,
            antePriority = priority("antePriority"),
            slotTarget = slotTarget,
            slotPriority = priority("slotPriority"),
            label = o["label"]?.toString()?.trim()?.takeIf { it.isNotEmpty() },
        )
    } catch (e: IllegalArgumentException) {
        throw SpecError("$where: ${e.message}", index)
    }
}

/** Highest ante a condition may reach (endless-mode antes are fine up to here). */
const val MAX_ANTE = 20

fun conditionsFromJson(raw: Any?, forceRequired: Boolean = false): Array<Condition> {
    val list = asList(raw, "conditions")
    if (list.isEmpty()) throw SpecError("Add at least one condition")
    if (list.size > 32) throw SpecError("At most 32 conditions")
    return Array(list.size) { conditionFromJson(list[it], it, forceRequired) }
}

fun ignoredVouchersFromJson(raw: Any?): List<String> {
    val ids = asList(raw, "ignoredVouchers").map { it.toString() }
    val known = Pools.VOUCHERS.map { it.id }.toSet()
    ids.firstOrNull { it !in known }?.let { throw SpecError("unknown voucher '$it' in ignoredVouchers") }
    return ids
}

// ---------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------

/**
 * Applies a settings object (from the page or the file) on top of [base]. Counts accept
 * the same suffixes as the flags: "312m", "4m", "1.5b".
 */
fun applySettings(base: RunOptions, raw: Any?, validate: Boolean = true): RunOptions {
    if (raw == null) return base
    val s = asObject(raw, "settings")
    fun count(key: String, flag: String): Long? {
        val v = s[key] ?: return null
        if (v is String && v.isBlank()) return null
        return try {
            Cli.parseCount(flag, v.toString().removeSuffix(".0"))
        } catch (e: CliError) {
            throw SpecError(e.message ?: "bad value for $key")
        }
    }
    fun int(key: String, flag: String): Int? = count(key, flag)?.let {
        if (it > Int.MAX_VALUE) throw SpecError("$flag is too large") else it.toInt()
    }
    var o = base
    int("maxResults", "--max-results")?.let { o = o.copy(maxResults = it) }
    (s["useGpu"] as? Boolean)?.let { o = o.copy(useGpu = it) }
    count("startIndex", "--start-index")?.let { o = o.copy(startIndex = it) }
    count("endIndex", "--end-index")?.let { o = o.copy(endIndex = it) }
    if (s.containsKey("checkpointEvery")) {
        o = o.copy(checkpointEvery = count("checkpointEvery", "--checkpoint"))
    }
    count("calibrationSeeds", "--calibration-seeds")?.let { o = o.copy(calibrationSeeds = it) }
    int("globalSize", "--global-size")?.let { o = o.copy(globalSize = it) }
    int("localSize", "--local-size")?.let { o = o.copy(localSize = it) }
    int("chunk", "--chunk")?.let { o = o.copy(chunk = it) }
    if (validate) {
        try {
            Cli.notes(o)
        } catch (e: CliError) {
            throw SpecError(e.message ?: "invalid settings")
        }
    }
    return o
}

fun settingsToJson(o: RunOptions): Map<String, Any?> = mapOf(
    "maxResults" to o.maxResults,
    "useGpu" to o.useGpu,
    "startIndex" to o.startIndex,
    "endIndex" to o.endIndex,
    "checkpointEvery" to o.checkpointEvery,
    "calibrationSeeds" to o.calibrationSeeds,
    "globalSize" to o.globalSize,
    "localSize" to o.localSize,
    "chunk" to o.chunk,
)

/**
 * A conditions file, as saved by the web page:
 *
 *   { "conditions": [ ... ], "ignoredVouchers": [ ... ], "settings": { ... } }
 *
 * Only "conditions" is needed. Settings in the file are defaults that flags override.
 */
class ConditionFile(
    val conditions: Array<Condition>,
    val ignoredVouchers: List<String>?,
    val settings: Any?,
) {
    companion object {
        fun load(path: String): ConditionFile {
            val f = java.io.File(path)
            if (!f.isFile) throw CliError("--conditions: no file at ${f.absolutePath}")
            val root = try {
                Json.parse(f.readText())
            } catch (e: Json.ParseError) {
                throw SpecError("${f.name} is not valid JSON: ${e.message}")
            }
            val o = when (root) {
                is Map<*, *> -> asObject(root, "the file")
                is List<*> -> mapOf("conditions" to root)   // a bare list of conditions is fine too
                else -> throw SpecError("${f.name} must hold an object with a \"conditions\" list")
            }
            return ConditionFile(
                conditionsFromJson(o["conditions"]),
                o["ignoredVouchers"]?.let { ignoredVouchersFromJson(it) },
                o["settings"],
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Quick estimate
// ---------------------------------------------------------------------------

/**
 * Samples random seeds on the CPU to estimate how often each condition is met on its own,
 * and how often all the required ones are met together.
 *
 * Each condition is treated as required here, since the question is "how many seeds have
 * this". It is the same scan the search runs, so the rates are exact for the sample; the
 * only uncertainty is sampling noise, which the page shows as a range.
 */
object Estimator {
    class Rate(val sampled: Long, val hits: Long)

    fun estimate(
        conditionsJson: Any?,
        ignoredVouchers: List<String>,
        from: Long,
        to: Long,
        budgetMs: Long,
        maxSeeds: Long = 2_000_000,
    ): Pair<List<Rate>, Rate?> {
        val single = conditionsFromJson(conditionsJson, forceRequired = true)
        val all = conditionsFromJson(conditionsJson)
        val anyRequired = all.any { it.required }
        val n = single.size

        val threads = maxOf(1, Runtime.getRuntime().availableProcessors() - 1)
        val deadline = System.currentTimeMillis() + budgetMs
        val next = java.util.concurrent.atomic.AtomicLong()
        val hits = Array(n + 1) { java.util.concurrent.atomic.AtomicLong() }
        val sampled = java.util.concurrent.atomic.AtomicLong()
        val span = maxOf(1L, to - from)

        class Probe(val conds: Array<Condition>) {
            val state = MatchState(conds)
            val sink = MatchSink(state, conds.maxOf { it.anteMax }, prune = false)
            val analyzer = SeedAnalyzer(
                Detail.forItems(conds.flatMap { it.items }, needEditions = conds.any { it.editionTarget != null }),
                ignoredVouchers,
            )
            val maxAnte = conds.maxOf { it.anteMax }

            /** True/false for met or not; null for a seed that cannot be resolved. */
            fun test(buf: CharArray, len: Int): Boolean? {
                state.reset(detail = false)
                analyzer.reset(buf, len)
                for (ante in 1..maxAnte) {
                    analyzer.scanAnte(ante, SHOP_ITEMS, sink)
                    if (analyzer.aborted) return null
                    state.closeAnte(ante)
                    if (state.dead) return false
                    if (state.isComplete) break
                }
                return state.requirementsMet
            }
        }

        val workers = (0 until threads).map { t ->
            Thread {
                val probes = Array(n) { Probe(arrayOf(single[it])) }
                val combined = if (anyRequired && n > 1) Probe(all) else null
                val rnd = java.util.SplittableRandom(0x5eed_0000L + t)
                val buf = CharArray(16)
                while (System.currentTimeMillis() < deadline && next.getAndIncrement() < maxSeeds) {
                    val idx = from + rnd.nextLong(span)
                    val len = seedForIndex(idx, buf)
                    for (i in 0 until n) if (probes[i].test(buf, len) == true) hits[i].incrementAndGet()
                    if (combined != null && combined.test(buf, len) == true) hits[n].incrementAndGet()
                    sampled.incrementAndGet()
                }
            }.apply { isDaemon = true; name = "estimate-$t"; start() }
        }
        workers.forEach { it.join() }

        val total = sampled.get()
        val rates = (0 until n).map { Rate(total, hits[it].get()) }
        val combined = when {
            !anyRequired -> null
            n == 1 -> rates[0]
            else -> Rate(total, hits[n].get())
        }
        return rates to combined
    }
}
