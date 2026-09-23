package analyzer

/**
 * Prefilter planning: runs cheap, selective checks before the full scan.
 *
 * Every card type draws from its own keyed RNG stream, and packs never read a shop stream
 * or the voucher. So a required condition that only accepts PACK and/or SOUL cards can be
 * decided by generating packs alone -- no voucher, no shops, and only the pack contents that
 * condition cares about. A "Negative Blueprint in an ante 1-2 Buffoon pack" check needs
 * nothing but the Buffoon draws for two antes.
 *
 * A stage is exact. It only ever rejects a seed that the full scan would also reject, because
 * it produces the same pack offers, in the same order and at the same slots, for the
 * conditions it checks. If it cannot decide (a resample past the device cap), the seed falls
 * through to the full scan, which handles it as before. Survivors are scanned in full, so
 * scores are unchanged.
 */
class PrefilterStage(
    /** Indices into the conditions array. All are required, and none accept SHOP. */
    val condIndex: IntArray,
    /** Last ante this stage scans: the latest window close among its conditions. */
    val maxAnte: Int,
    /** Which pack contents to generate. Always a subset of the search's own Detail. */
    val detail: Detail,
    /** Estimated fraction of seeds that survive this stage alone (Laplace-smoothed). */
    val passRate: Double,
    /** Estimated work per seed, in draw-equivalents. */
    val cost: Double,
) {
    /**
     * True if this stage's pack contents depend on which vouchers are owned, so the GPU
     * stage has to generate vouchers too (about one draw per ante). Soul, tarot and
     * spectral contents do (Omen Globe, Telescope); so do editions other than Negative
     * (Hone and Glow Up). A Buffoon-only stage looking for Negatives does not.
     */
    fun needsVouchers(conditions: Array<Condition>): Boolean {
        if (detail.souls || detail.tarots || detail.spectrals || detail.planets) return true
        if (!detail.editions) return false
        return condIndex.any { i ->
            val t = conditions[i].editionTarget
            t != null && t.id != "Negative"
        }
    }

    /** Lower is better: the standard ordering for independent filters. */
    val rank: Double get() = cost / (1.0 - passRate)

    fun describe(conditions: Array<Condition>): String =
        condIndex.joinToString(" + ") { conditions[it].displayName } +
                " | antes 1-$maxAnte | $detail | pass ~${"%.2g".format(passRate)}, cost ~${"%.1f".format(cost)}"
}

/** Runs one stage on the CPU. Reused per seed, so it allocates once. */
class StageRunner(stage: PrefilterStage, conditions: Array<Condition>, ignoredVouchers: List<String>) {
    private val maxAnte = stage.maxAnte
    private val state = MatchState(Array(stage.condIndex.size) { conditions[stage.condIndex[it]] })
    private val sink = MatchSink(state, maxAnte, prune = false)
    val analyzer = SeedAnalyzer(stage.detail, ignoredVouchers)

    /** False means the full scan would reject this seed. [chars] must be uppercase. */
    fun passes(chars: CharArray, len: Int): Boolean {
        state.reset(detail = false)
        analyzer.reset(chars, len)
        for (ante in 1..maxAnte) {
            // Zero shop items: packs only. (The voucher is still drawn, which is harmless.)
            analyzer.scanAnte(ante, 0, sink)
            if (analyzer.aborted) return true   // undecidable here; let the full scan decide
            state.closeAnte(ante)
            if (state.dead) return false
            if (state.isComplete) return true
        }
        return true
    }
}

object SearchPlanner {

    /** The kernel's stage tables are fixed at 4. More would rarely help anyway. */
    const val MAX_STAGES = 4

    /** Seeds sampled to estimate each stage's pass rate and cost. */
    const val SAMPLE_SEEDS = 20_000

    /** A stage that passes more than this share of seeds is not worth running. */
    const val MAX_PASS_RATE = 0.5

    /** A stream init costs a key hash (about 10 FP64 divides) plus a draw; weight it so. */
    private const val INIT_WEIGHT = 4.0

    private fun prefilterable(c: Condition): Boolean =
        c.required &&
                c.sources and Src.SHOP == 0 &&
                c.sources and (Src.PACK or Src.SOUL).inv() == 0

    /** Generate only what this condition needs, from the sources it accepts. */
    private fun stageDetail(c: Condition): Detail {
        val d = Detail.forItems(c.items, needEditions = c.editionTarget != null)
        val pack = c.sources and Src.PACK != 0
        val soul = c.sources and Src.SOUL != 0
        return Detail(
            jokers = pack && d.jokers,
            tarots = pack && d.tarots,
            planets = false,
            spectrals = pack && d.spectrals,
            standardCards = false,
            editions = d.editions,
            bosses = false,
            tags = false,
            souls = d.souls,
            soulJokers = soul && d.soulJokers,
        )
    }

    private fun signature(d: Detail) = d.toString()

    /**
     * Picks the prefilter stages and puts them in their best order.
     *
     * 1. Candidates are the required conditions that accept only PACK and/or SOUL cards.
     * 2. Conditions that need the same pack contents share a stage. One scan then checks
     *    them all, and it still stops as soon as any of their windows closes unmet.
     * 3. Each candidate stage runs on [SAMPLE_SEEDS] seeds on the CPU, which measures its
     *    pass rate and its cost in RNG draws.
     * 4. Stages are sorted by cost / (1 - passRate): cheap stages that reject the most
     *    seeds go first. Stages that pass too many seeds are dropped.
     *
     * Returns an empty list when nothing qualifies. The search then runs exactly as before.
     */
    fun plan(
        conditions: Array<Condition>,
        ignoredVouchers: List<String>,
        sampleStart: Long,
        verbose: Boolean = true,
    ): List<PrefilterStage> {
        val groups = LinkedHashMap<String, MutableList<Int>>()
        val details = HashMap<String, Detail>()
        for (i in conditions.indices) {
            val c = conditions[i]
            if (!prefilterable(c)) continue
            val d = stageDetail(c)
            val key = signature(d)
            groups.getOrPut(key) { ArrayList() }.add(i)
            details[key] = d
        }
        if (groups.isEmpty()) {
            if (verbose) println("Prefilter: no required pack/Soul-only conditions, so no stages.")
            return emptyList()
        }

        val buf = CharArray(16)
        val candidates = ArrayList<PrefilterStage>()
        for ((key, idx) in groups) {
            val detail = details.getValue(key)
            val maxAnte = idx.maxOf { conditions[it].anteMax }
            val probe = PrefilterStage(idx.toIntArray(), maxAnte, detail, 0.0, 0.0)
            val runner = StageRunner(probe, conditions, ignoredVouchers)
            var passed = 0
            val rng = runner.analyzer
            val d0 = rng.rngDraws; val i0 = rng.rngInits
            for (k in 0 until SAMPLE_SEEDS) {
                val len = seedForIndex(sampleStart + k, buf)
                if (runner.passes(buf, len)) passed++
            }
            val draws = (rng.rngDraws - d0).toDouble()
            val inits = (rng.rngInits - i0).toDouble()
            val passRate = (passed + 1.0) / (SAMPLE_SEEDS + 2.0)
            val cost = (draws + INIT_WEIGHT * inits) / SAMPLE_SEEDS
            candidates.add(PrefilterStage(idx.toIntArray(), maxAnte, detail, passRate, cost))
        }

        val chosen = candidates
            .filter { it.passRate <= MAX_PASS_RATE }
            .sortedBy { it.rank }
            .take(MAX_STAGES)

        if (verbose) {
            println("Prefilter stages (sampled ${"%,d".format(SAMPLE_SEEDS)} seeds, best first):")
            if (chosen.isEmpty()) println("  none selective enough to be worth running")
            chosen.forEachIndexed { n, s -> println("  ${n + 1}. ${s.describe(conditions)}") }
            for (s in candidates) if (s !in chosen) println("  skipped: ${s.describe(conditions)}")
        }
        return chosen
    }

    /** Stage Detail must never exceed what the kernel was compiled to generate. */
    fun clampTo(stage: PrefilterStage, global: Detail): Detail {
        val d = stage.detail
        return Detail(
            d.jokers && global.jokers, d.tarots && global.tarots, false,
            d.spectrals && global.spectrals, false, d.editions && global.editions, false, false,
            d.souls && global.souls, d.soulJokers && global.soulJokers,
        )
    }
}