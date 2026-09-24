package analyzer

import java.math.BigDecimal
import java.math.BigInteger

/**
 * Run settings that can be changed from the command line.
 *
 * The defaults live at the top of main(), not here, so they are easy to find and edit.
 * [checkpointEvery] of null means "one tenth of the range", which can only be worked out
 * once the start and end are known.
 */
data class RunOptions(
    val maxResults: Int,
    val useGpu: Boolean,
    val startIndex: Long,
    val endIndex: Long,
    val checkpointEvery: Long?,
    val calibrationSeeds: Long,
    val globalSize: Int,
    val localSize: Int,
    val chunk: Int,
    /** --examine SEED: print that seed's antes and exit instead of searching. */
    val examine: String? = null,
    /** --conditions FILE: search those conditions headless instead of starting the web page. */
    val conditionsFile: String? = null,
    /** Web page address. 127.0.0.1 keeps it private to this computer. */
    val host: String = "127.0.0.1",
    val port: Int = 7777,
    /** False with --no-browser: start the web page but do not open a browser. */
    val openBrowser: Boolean = true,
) {
    val seedsToCount: Long get() = endIndex - startIndex

    /** The checkpoint interval actually used. 0 disables checkpoints. */
    val resolvedCheckpoint: Long get() = checkpointEvery ?: (seedsToCount / 10)
}

/** A bad flag or value. main() prints the message and exits without starting the search. */
class CliError(message: String) : Exception(message)

object Cli {

    /**
     * Upper limit for --max-results.
     *
     * Every checkpoint rescans each held result on the CPU and builds a full report for it,
     * and the calibration aims for 20x this many hits. Past a few thousand, checkpoints stall
     * the GPU and the results file becomes unreadable. Raise it if you really need more.
     */
    const val MAX_RESULTS_LIMIT = 10_000

    /**
     * Every Balatro seed: 1 to 8 characters from the SEED_CHARS alphabet (35 characters).
     * 35 + 35^2 + ... + 35^8 = 2,318,107,019,760 (about 2.32 trillion).
     */
    val TOTAL_SEEDS: Long = run {
        var total = 0L
        var block = 1L
        repeat(8) { block *= SEED_BASE; total += block }
        total
    }

    /** OpenCL work-group sizes above this are rejected by essentially every device. */
    private const val LOCAL_SIZE_LIMIT = 1024

    private class Flag(val name: String, val arg: String?, val help: String)

    private fun flags(d: RunOptions) = listOf(
        Flag("--max-results", "N", "Best seeds to keep (default ${d.maxResults}, max ${fmt(MAX_RESULTS_LIMIT.toLong())})"),
        Flag("--disable-gpu", null, "Run on the CPU only (the GPU is used by default when it can be)"),
        Flag("--start-index", "N", "First seed index to search (default ${fmt(d.startIndex)})"),
        Flag("--end-index", "N", "Stop before this seed index (default ${fmt(d.endIndex)}, max ${fmt(TOTAL_SEEDS)})"),
        Flag("--checkpoint", "N", "Save results every N seeds; 0 turns checkpoints off " +
                "(default ${d.checkpointEvery?.let { fmt(it) } ?: "one tenth of the range"})"),
        Flag("--calibration-seeds", "N", "Seeds sampled to pick a starting cutoff; 0 skips it (default ${fmt(d.calibrationSeeds)})"),
        Flag("--global-size", "N", "GPU work items per compute unit (default ${d.globalSize}; " +
                "power of 2 from 2048 to 16384 recommended)"),
        Flag("--local-size", "N", "GPU work-group size (default ${d.localSize}; power of 2 from 64 to 256 recommended)"),
        Flag("--chunk", "N", "Seeds per GPU launch (default ${fmt(d.chunk.toLong())}; 2m to 64m recommended)"),
        Flag("--examine", "SEED", "Print antes 1-8 of one seed in full and exit, without searching"),
        Flag("--conditions", "FILE", "Search the conditions in FILE (saved from the web page) without the " +
                "web page, then exit. For servers and VMs"),
        Flag("--port", "N", "Port for the web page (default ${d.port}; the next free one is used if taken)"),
        Flag("--host", "ADDR", "Address the web page listens on (default ${d.host}, this computer only; " +
                "0.0.0.0 lets other computers connect, with no password)"),
        Flag("--no-browser", null, "Start the web page without opening a browser"),
        Flag("--help", null, "Show this list and exit"),
    )

    /** Compact form for help text: 4000000 -> 4m. */
    private fun fmt(v: Long): String = when {
        v != 0L && v % 1_000_000_000_000L == 0L -> "${v / 1_000_000_000_000L}t"
        v != 0L && v % 1_000_000_000L == 0L -> "${v / 1_000_000_000L}b"
        v != 0L && v % 1_000_000L == 0L -> "${v / 1_000_000L}m"
        v != 0L && v % 1_000L == 0L -> "${v / 1_000L}k"
        else -> "%,d".format(v)
    }

    fun printHelp(defaults: RunOptions) {
        println("Balatro seed finder")
        println()
        println("Usage: [flags]   (every flag is optional)")
        println()
        println("With no --conditions file, the finder starts a web page and opens it in your browser.")
        println("Set up the conditions there, or save them to a file and run it headless with --conditions.")
        println()
        println("Numbers accept k (thousand), m (million), b (billion) and t (trillion):")
        println("  4m = 4,000,000   1.5b = 1,500,000,000   250k = 250,000")
        println()
        println("Flags:")
        val fl = flags(defaults)
        val width = fl.maxOf { it.name.length + (it.arg?.length?.plus(1) ?: 0) }
        for (f in fl) {
            val left = if (f.arg != null) "${f.name} ${f.arg}" else f.name
            println("  ${left.padEnd(width)}   ${f.help}")
        }
        println()
        println("Both --flag value and --flag=value work.")
    }

    /**
     * Parses a count like "4m", "1.5b", "250k", "100000" or "100_000".
     * Rejects negatives, fractions that do not come out whole, and anything past Long.
     */
    fun parseCount(flag: String, raw: String): Long {
        val s = raw.trim().replace("_", "").replace(",", "").lowercase()
        val m = Regex("""^(\d+(?:\.\d+)?)([kmbt]?)$""").matchEntire(s)
            ?: throw CliError("$flag: '$raw' is not a number (examples: 500, 250k, 4m, 1.5b)")
        val (num, suffix) = m.destructured
        val mult = when (suffix) {
            "k" -> 1_000L; "m" -> 1_000_000L; "b" -> 1_000_000_000L; "t" -> 1_000_000_000_000L
            else -> 1L
        }
        val value = BigDecimal(num).multiply(BigDecimal.valueOf(mult))
        val whole = try { value.toBigIntegerExact() } catch (e: ArithmeticException) {
            throw CliError("$flag: '$raw' is not a whole number of seeds")
        }
        if (whole > BigInteger.valueOf(Long.MAX_VALUE)) throw CliError("$flag: '$raw' is too large")
        return whole.toLong()
    }

    private fun toInt(flag: String, raw: String): Int {
        val v = parseCount(flag, raw)
        if (v > Int.MAX_VALUE) throw CliError("$flag: '$raw' is too large (max ${"%,d".format(Int.MAX_VALUE)})")
        return v.toInt()
    }

    private fun isPow2(v: Long) = v > 0 && (v and (v - 1)) == 0L

    /**
     * The --conditions value, found before the full parse: the file can carry settings of
     * its own, which become the defaults the other flags override.
     */
    fun conditionsPath(args: Array<String>): String? {
        for ((i, a) in args.withIndex()) {
            if (a.startsWith("--conditions=")) return a.substringAfter('=')
            if (a == "--conditions") {
                if (i + 1 >= args.size || args[i + 1].startsWith("--")) throw CliError("--conditions needs a value")
                return args[i + 1]
            }
        }
        return null
    }

    /**
     * Returns the options, or throws [CliError]. Returns null when --help was given, after
     * printing the help, so the caller can exit without searching.
     */
    fun parse(args: Array<String>, defaults: RunOptions): RunOptions? {
        var o = defaults
        var i = 0
        val known = flags(defaults).map { it.name }.toSet()
        while (i < args.size) {
            val a = args[i]
            if (!a.startsWith("--")) throw CliError("unexpected argument '$a' (flags start with --)")
            val eq = a.indexOf('=')
            val name = if (eq >= 0) a.substring(0, eq) else a
            if (name !in known) throw CliError("unknown flag '$name'")

            if (name == "--help") { printHelp(defaults); return null }
            if (name == "--disable-gpu") {
                if (eq >= 0) throw CliError("--disable-gpu takes no value")
                o = o.copy(useGpu = false); i++; continue
            }
            if (name == "--no-browser") {
                if (eq >= 0) throw CliError("--no-browser takes no value")
                o = o.copy(openBrowser = false); i++; continue
            }

            val value: String = if (eq >= 0) a.substring(eq + 1) else {
                if (i + 1 >= args.size || args[i + 1].startsWith("--")) throw CliError("$name needs a value")
                args[++i]
            }
            o = when (name) {
                "--max-results" -> o.copy(maxResults = toInt(name, value))
                "--start-index" -> o.copy(startIndex = parseCount(name, value))
                "--end-index" -> o.copy(endIndex = parseCount(name, value))
                "--checkpoint" -> o.copy(checkpointEvery = parseCount(name, value))
                "--calibration-seeds" -> o.copy(calibrationSeeds = parseCount(name, value))
                "--global-size" -> o.copy(globalSize = toInt(name, value))
                "--local-size" -> o.copy(localSize = toInt(name, value))
                "--chunk" -> o.copy(chunk = toInt(name, value))
                "--examine" -> {
                    val seed = value.trim().uppercase()
                    if (seed.isEmpty() || seed.length > 8 || seed.any { it !in SEED_CHARS }) {
                        throw CliError("--examine: '$value' is not a seed (1-8 characters from $SEED_CHARS)")
                    }
                    o.copy(examine = seed)
                }
                "--conditions" -> o.copy(conditionsFile = value)
                "--host" -> o.copy(host = value.trim())
                "--port" -> {
                    val p = toInt(name, value)
                    if (p !in 1..65535) throw CliError("--port must be between 1 and 65535")
                    o.copy(port = p)
                }
                else -> throw CliError("unknown flag '$name'")
            }
            i++
        }
        validate(o)
        return o
    }

    /** Hard errors stop the run; recommendations only print a warning. */
    fun validate(o: RunOptions) {
        for (n in notes(o)) println("Note: $n")
    }

    /** Throws for settings that cannot run; returns advice for ones that merely look off. */
    fun notes(o: RunOptions): List<String> {
        if (o.maxResults < 1) throw CliError("--max-results must be at least 1")
        if (o.maxResults > MAX_RESULTS_LIMIT) {
            throw CliError("--max-results ${o.maxResults} is too high (limit ${"%,d".format(MAX_RESULTS_LIMIT)}): " +
                    "every checkpoint rescans each held result on the CPU")
        }
        if (o.endIndex > TOTAL_SEEDS) {
            throw CliError("--end-index ${"%,d".format(o.endIndex)} is past the last seed " +
                    "(there are ${"%,d".format(TOTAL_SEEDS)} seeds of 1-8 characters)")
        }
        if (o.startIndex >= o.endIndex) {
            throw CliError("--start-index ${"%,d".format(o.startIndex)} must be below --end-index ${"%,d".format(o.endIndex)}")
        }
        if (o.localSize < 1 || o.localSize > LOCAL_SIZE_LIMIT) {
            throw CliError("--local-size must be between 1 and $LOCAL_SIZE_LIMIT")
        }
        if (o.globalSize < 1) throw CliError("--global-size must be at least 1")
        if (o.globalSize % o.localSize != 0) {
            throw CliError("--global-size ${o.globalSize} must be a multiple of --local-size ${o.localSize}")
        }
        if (o.chunk < 1) throw CliError("--chunk must be at least 1")

        val out = ArrayList<String>()
        if (!isPow2(o.globalSize.toLong()) || o.globalSize !in 2048..16384) {
            out.add("--global-size ${o.globalSize} is outside the recommended range (a power of 2 from 2048 to 16384).")
        }
        if (!isPow2(o.localSize.toLong()) || o.localSize !in 64..256) {
            out.add("--local-size ${o.localSize} is outside the recommended range (a power of 2 from 64 to 256).")
        }
        if (o.chunk !in 2_000_000..64_000_000) {
            out.add("--chunk ${"%,d".format(o.chunk)} is outside the recommended range (2m to 64m).")
        }
        return out
    }
}
