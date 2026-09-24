package analyzer

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import kotlin.system.exitProcess

/**
 * Keeps the last lines printed to the console, so the web page can show the same log.
 * Each line gets a sequence number; the page asks for "everything after N".
 */
object LogBuffer {
    private const val KEEP = 3000
    private val lines = ArrayDeque<String>()
    private var nextSeq = 0L

    @Synchronized
    fun add(line: String) {
        lines.addLast(line)
        nextSeq++
        if (lines.size > KEEP) lines.removeFirst()
    }

    /** Lines after [after], and the sequence number to ask from next time. */
    @Synchronized
    fun since(after: Long): Pair<List<String>, Long> {
        val first = nextSeq - lines.size
        // A number past the end means the page outlived an earlier run of the program.
        val from = if (after > nextSeq) first else maxOf(after, first)
        val skip = (from - first).toInt()
        return lines.drop(skip).take(1000) to (from + minOf(1000, lines.size - skip))
    }

    /** Sends everything written to [original] to this buffer too, line by line. */
    fun tee(original: PrintStream): PrintStream {
        val out = object : OutputStream() {
            private val buf = ByteArrayOutputStream()
            override fun write(b: Int) {
                original.write(b)
                synchronized(buf) {
                    if (b == '\n'.code) flushLine() else buf.write(b)
                }
            }
            override fun write(b: ByteArray, off: Int, len: Int) {
                original.write(b, off, len)
                synchronized(buf) {
                    for (i in off until off + len) {
                        if (b[i] == '\n'.code.toByte()) flushLine() else buf.write(b[i].toInt())
                    }
                }
            }
            override fun flush() = original.flush()
            private fun flushLine() {
                add(buf.toString(original.charset()).trimEnd('\r'))
                buf.reset()
            }
        }
        return PrintStream(out, true, original.charset())
    }
}

/**
 * The web page: a small HTTP server on this computer, using the JDK's built-in server so
 * the bundle needs nothing extra.
 *
 * Only one search runs at a time. The page polls /api/status about once a second.
 *
 * Requests are only accepted when they are addressed to this computer by name (so a web
 * site cannot reach the server by pointing a domain name at 127.0.0.1), and POSTs must be
 * JSON, which a browser will not send cross-site without asking first. Together those keep
 * other web pages from starting or stopping searches.
 */
object WebServer {

    private lateinit var launchDefaults: RunOptions
    private lateinit var defaultIgnored: List<String>
    @Volatile private var gpuNames: List<String>? = null
    @Volatile private var gpuError: String? = null
    private val hydrateLock = Any()
    private var bindHost = "127.0.0.1"
    private var port = 0

    fun start(defaults: RunOptions, ignoredVouchers: List<String>) {
        System.setOut(LogBuffer.tee(System.out))
        System.setErr(LogBuffer.tee(System.err))
        launchDefaults = defaults
        defaultIgnored = ignoredVouchers
        bindHost = defaults.host

        val server = bind(defaults.host, defaults.port)
        port = server.address.port
        server.executor = java.util.concurrent.Executors.newFixedThreadPool(6) { r ->
            Thread(r, "web").apply { isDaemon = true }
        }
        server.createContext("/") { ex -> handle(ex) }
        server.start()

        // Probing for GPUs loads the OpenCL driver, which can take a second or two. Doing it
        // in the background lets the page open straight away.
        Thread {
            try {
                gpuNames = ClSearch.deviceNames()
            } catch (t: Throwable) {
                gpuError = if (t is LinkageError) "no OpenCL driver found" else (t.message ?: t.toString())
                gpuNames = emptyList()
            }
        }.apply { isDaemon = true; start() }

        val shownHost = if (defaults.host == "0.0.0.0" || defaults.host == "::") "localhost" else defaults.host
        val url = "http://$shownHost:$port/"
        println()
        println("Seed finder web page: $url")
        if (defaults.host != "127.0.0.1" && defaults.host != "localhost") {
            println("  Listening on ${defaults.host}: other computers can open it too, with no password.")
        }
        println("  Keep this window open while you use it. Close it (or press Ctrl+C) to quit.")
        println("  To search without the web page, save your conditions to a file on the page and run")
        println("  with --conditions FILE.")
        if (defaults.openBrowser && !openBrowser(url)) {
            println("  (Could not open a browser here. Open the address above yourself. On a server, forward")
            println("   the port first, for example: ssh -L $port:localhost:$port user@server)")
        }
        println()

        // The server's threads are daemons, so park here until the page asks to quit.
        val latch = java.util.concurrent.CountDownLatch(1)
        latch.await()
    }

    /** Binds to [port], or the next free port above it if that one is taken. */
    private fun bind(host: String, port: Int): HttpServer {
        var last: Exception? = null
        for (p in port until minOf(65536, port + 20)) {
            try {
                return HttpServer.create(InetSocketAddress(host, p), 0)
            } catch (e: java.net.BindException) {
                last = e
            }
        }
        throw CliError("could not listen on $host ports $port-${port + 19}: ${last?.message}")
    }

    private fun openBrowser(url: String): Boolean {
        val os = System.getProperty("os.name").lowercase()
        val cmd = when {
            os.contains("win") -> listOf("rundll32", "url.dll,FileProtocolHandler", url)
            os.contains("mac") -> listOf("open", url)
            else -> {
                if (System.getenv("DISPLAY").isNullOrEmpty() && System.getenv("WAYLAND_DISPLAY").isNullOrEmpty()) {
                    return false
                }
                listOf("xdg-open", url)
            }
        }
        return try {
            ProcessBuilder(cmd)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            true
        } catch (e: Exception) {
            false
        }
    }

    // -----------------------------------------------------------------------
    // Request handling
    // -----------------------------------------------------------------------

    private class HttpError(val code: Int, message: String) : Exception(message)

    private fun handle(ex: HttpExchange) {
        try {
            checkHost(ex)
            val path = ex.requestURI.path
            val method = ex.requestMethod
            if (method == "POST") {
                val ct = ex.requestHeaders.getFirst("Content-Type") ?: ""
                if (!ct.startsWith("application/json")) throw HttpError(415, "POST bodies must be JSON")
            } else if (method != "GET") {
                throw HttpError(405, "method not allowed")
            }
            when ("$method $path") {
                "GET /", "GET /index.html" -> sendPage(ex)
                "GET /api/info" -> sendJson(ex, info())
                "GET /api/status" -> sendJson(ex, status(ex.requestURI))
                "GET /api/results.txt" -> sendResultsFile(ex)
                "GET /favicon.ico" -> send(ex, 204, "image/x-icon", ByteArray(0))
                "POST /api/check" -> sendJson(ex, check(body(ex)))
                "POST /api/estimate" -> sendJson(ex, estimate(body(ex)))
                "POST /api/start" -> sendJson(ex, startRun(body(ex)))
                "POST /api/stop" -> {
                    if (RunStatus.running) {
                        SearchControl.stopRequested = true
                        println("Stop requested from the web page; finishing the current block...")
                    }
                    sendJson(ex, mapOf("ok" to true))
                }
                "POST /api/seed" -> sendJson(ex, seedDetail(body(ex)))
                "POST /api/quit" -> {
                    sendJson(ex, mapOf("ok" to true))
                    Thread {
                        Thread.sleep(300)
                        println("Quit from the web page.")
                        exitProcess(0)   // the shutdown hook saves a run still in progress
                    }.start()
                }
                else -> throw HttpError(404, "not found: $path")
            }
        } catch (e: HttpError) {
            sendError(ex, e.code, e.message ?: "error")
        } catch (e: SpecError) {
            sendJson(ex, mapOf("error" to e.message, "conditionIndex" to e.conditionIndex), 400)
        } catch (e: CliError) {
            sendJson(ex, mapOf("error" to e.message), 400)
        } catch (e: Json.ParseError) {
            sendError(ex, 400, "bad JSON: ${e.message}")
        } catch (e: Throwable) {
            e.printStackTrace()
            sendError(ex, 500, e.toString())
        } finally {
            ex.close()
        }
    }

    /** Refuses requests addressed to any other name, which is what DNS rebinding relies on. */
    private fun checkHost(ex: HttpExchange) {
        if (bindHost != "127.0.0.1" && bindHost != "localhost" && bindHost != "::1") return
        val host = (ex.requestHeaders.getFirst("Host") ?: "").lowercase()
        val name = if (host.startsWith("[")) host.substringBefore("]") + "]" else host.substringBefore(":")
        if (name !in setOf("localhost", "127.0.0.1", "[::1]")) throw HttpError(403, "forbidden host")
    }

    private fun body(ex: HttpExchange): Map<String, Any?> {
        val text = ex.requestBody.readNBytes(4 shl 20).toString(UTF_8)
        return asObject(Json.parse(text.ifBlank { "{}" }), "request")
    }

    private fun send(ex: HttpExchange, code: Int, type: String, bytes: ByteArray, extra: Map<String, String> = emptyMap()) {
        ex.responseHeaders.add("Content-Type", type)
        ex.responseHeaders.add("Cache-Control", "no-store")
        ex.responseHeaders.add("X-Content-Type-Options", "nosniff")
        for ((k, v) in extra) ex.responseHeaders.add(k, v)
        ex.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
    }

    private fun sendJson(ex: HttpExchange, v: Any?, code: Int = 200) =
        send(ex, code, "application/json; charset=utf-8", Json.write(v).toByteArray(UTF_8))

    private fun sendError(ex: HttpExchange, code: Int, msg: String) =
        sendJson(ex, mapOf("error" to msg), code)

    private fun sendPage(ex: HttpExchange) {
        val bytes = WebServer::class.java.getResourceAsStream("/web/index.html")?.use { it.readBytes() }
            ?: java.io.File("web/index.html").takeIf { it.isFile }?.readBytes()
            ?: throw HttpError(500, "index.html is missing from the program")
        send(ex, 200, "text/html; charset=utf-8", bytes,
            mapOf("Content-Security-Policy" to "default-src 'self'; style-src 'self' 'unsafe-inline'; " +
                    "script-src 'self' 'unsafe-inline'; img-src 'self' data:"))
    }

    private fun sendResultsFile(ex: HttpExchange) {
        val f = java.io.File(RESULTS_FILE)
        if (!f.isFile) throw HttpError(404, "no results saved yet")
        send(ex, 200, "text/plain; charset=utf-8", f.readBytes(),
            mapOf("Content-Disposition" to "attachment; filename=\"results.txt\""))
    }

    // -----------------------------------------------------------------------
    // Endpoints
    // -----------------------------------------------------------------------

    private fun info(): Map<String, Any?> = mapOf(
        "catalog" to Catalog.toJson(),
        "settings" to settingsToJson(launchDefaults),
        "ignoredVouchers" to defaultIgnored,
        "totalSeeds" to Cli.TOTAL_SEEDS,
        "seedChars" to SEED_CHARS,
        "maxAnte" to MAX_ANTE,
        "noSlotLimit" to NO_SLOT_LIMIT,
        "gpuMaxConditions" to ClSearch.MAX_CONDITIONS,
        "gpuMaxCount" to ClSearch.MAX_COUNT,
        "maxResultsLimit" to Cli.MAX_RESULTS_LIMIT,
        "cpuThreads" to Runtime.getRuntime().availableProcessors(),
        "resultsFile" to java.io.File(RESULTS_FILE).absolutePath,
        "gpus" to gpuNames,
        "gpuError" to gpuError,
    )

    /**
     * Checks conditions and settings as they are typed. Every condition is parsed on its
     * own so the page can mark each bad one, not just the first.
     */
    private fun check(req: Map<String, Any?>): Map<String, Any?> {
        val list = asList(req["conditions"], "conditions")
        val errors = ArrayList<Map<String, Any?>>()
        val parsed = ArrayList<Condition>()
        for ((i, c) in list.withIndex()) {
            try {
                parsed.add(conditionFromJson(c, i))
            } catch (e: SpecError) {
                errors.add(mapOf("index" to i, "message" to e.message))
            }
        }
        if (list.isEmpty()) errors.add(mapOf("index" to null, "message" to "Add at least one condition."))

        var settingsError: String? = null
        var settingsNotes: List<String> = emptyList()
        var rangeSize: Long? = null
        try {
            val o = applySettings(launchDefaults, req["settings"], validate = false)
            settingsNotes = Cli.notes(o)
            rangeSize = o.seedsToCount
        } catch (e: SpecError) {
            settingsError = e.message
        } catch (e: CliError) {
            settingsError = e.message
        }
        try {
            ignoredVouchersFromJson(req["ignoredVouchers"])
        } catch (e: SpecError) {
            settingsError = e.message
        }

        val out = linkedMapOf<String, Any?>(
            "errors" to errors,
            "settingsError" to settingsError,
            "settingsNotes" to settingsNotes,
            "rangeSize" to rangeSize,
        )
        if (errors.isEmpty() && parsed.isNotEmpty()) {
            val conds = parsed.toTypedArray()
            val detail = Detail.forItems(conds.flatMap { it.items }, needEditions = conds.any { it.editionTarget != null })
            val maxAnte = conds.maxOf { it.anteMax }
            out["warnings"] = unsatisfiableReasons(conds).map { (i, m) -> mapOf("index" to i, "message" to m) }
            out["gpuReason"] = ClSearch.whyUnsupported(detail, conds)
            out["maxScore"] = conds.sumOf { it.maxScore }
            out["maxPerCondition"] = conds.map { it.maxScore }
            out["describe"] = describeConditions(conds, maxAnte)
            out["generating"] = detail.toString()
            out["requiredCount"] = conds.count { it.required }
        }
        return out
    }

    private fun estimate(req: Map<String, Any?>): Map<String, Any?> {
        val o = applySettings(launchDefaults, req["settings"], validate = false)
        val ignored = ignoredVouchersFromJson(req["ignoredVouchers"] ?: defaultIgnored)
        val budget = ((req["budgetMs"] as? Number)?.toLong() ?: 3000L).coerceIn(500L, 20_000L)
        val (rates, combined) = Estimator.estimate(req["conditions"], ignored, o.startIndex, o.endIndex, budget)
        return mapOf(
            "rangeSize" to o.seedsToCount,
            "perCondition" to rates.map { mapOf("sampled" to it.sampled, "hits" to it.hits) },
            "combined" to combined?.let { mapOf("sampled" to it.sampled, "hits" to it.hits) },
        )
    }

    private fun startRun(req: Map<String, Any?>): Map<String, Any?> {
        synchronized(this) {
            if (RunStatus.running) throw HttpError(409, "a search is already running")
            val conditions = conditionsFromJson(req["conditions"])
            val ignored = ignoredVouchersFromJson(req["ignoredVouchers"] ?: defaultIgnored)
            val o = applySettings(launchDefaults, req["settings"])
            val notes = Cli.notes(o)
            if (conditions.size > 32) throw SpecError("At most 32 conditions")
            RunStatus.phase = "planning"   // claims the slot before the thread starts
            Thread {
                try {
                    runSearch(SearchConfig(conditions, ignored, o))
                } catch (t: Throwable) {
                    println("Search failed: $t")
                    t.printStackTrace()
                    RunStatus.message = t.message ?: t.toString()
                    RunStatus.finishedAtMs = System.currentTimeMillis()
                    RunStatus.phase = "failed"
                }
            }.apply { name = "search"; start() }
            return mapOf("ok" to true, "notes" to notes)
        }
    }

    private fun status(uri: URI): Map<String, Any?> {
        val q = (uri.rawQuery ?: "").split('&').mapNotNull {
            val kv = it.split('=', limit = 2)
            if (kv.size == 2) kv[0] to kv[1] else null
        }.toMap()
        val logAfter = q["log"]?.toLongOrNull() ?: 0L
        val want = (q["results"]?.toIntOrNull() ?: 100).coerceIn(0, Cli.MAX_RESULTS_LIMIT)

        val results = snapshotResults()
        val shown = results.take(want)
        // Fill in the "what matched" line for results that do not have it yet. One CPU
        // rescan per new result; the lock stops two page polls doing the same work.
        synchronized(hydrateLock) { hydrateSummaries(shown) }

        val (lines, nextLog) = LogBuffer.since(logAfter)
        val now = System.currentTimeMillis()
        val s = RunStatus
        val end = if (s.finishedAtMs > 0) s.finishedAtMs else now
        return linkedMapOf(
            "phase" to s.phase,
            "running" to s.running,
            "message" to s.message,
            "runId" to s.runId,
            "elapsedMs" to if (s.startedAtMs > 0) end - s.startedAtMs else 0L,
            "startIndex" to s.startIndex,
            "total" to s.total,
            "searched" to s.searched,
            "resumeIndex" to s.resumeIndex,
            "speed" to if (s.running) s.speed() else 0.0,
            "averageSpeed" to if (s.startedAtMs > 0 && end > s.startedAtMs) s.searched * 1000.0 / (end - s.startedAtMs) else 0.0,
            "device" to s.device,
            "gpuNote" to s.gpuNote,
            "cutoff" to cutoff.takeIf { it != Double.NEGATIVE_INFINITY },
            "hits" to s.hits.sum(),
            "hasRequired" to s.hasRequired,
            "sampleSeeds" to s.sampleSeeds,
            "sampleHits" to s.sampleHits,
            "calibrationSeeds" to s.calibrationSeeds,
            "calibrationHits" to s.calibrationHits,
            "maxResults" to maxResults,
            "resultsHeld" to results.size,
            "results" to shown.map { r ->
                mapOf(
                    "seed" to r.seed,
                    "score" to r.score,
                    "summary" to r.summary,
                    "matches" to r.matches.map { it.toString() },
                )
            },
            "log" to lines,
            "logNext" to nextLog,
            "gpus" to gpuNames,
            "gpuError" to gpuError,
        )
    }

    /** One seed in full: what the given conditions match, and antes 1-8 (or further). */
    private fun seedDetail(req: Map<String, Any?>): Map<String, Any?> {
        val seed = (req["seed"] ?: "").toString().trim().uppercase().replace('0', 'O')
        if (seed.isEmpty() || seed.length > 8 || seed.any { it !in SEED_CHARS }) {
            throw HttpError(400, "'$seed' is not a seed (1-8 characters from $SEED_CHARS)")
        }
        val ignored = ignoredVouchersFromJson(req["ignoredVouchers"] ?: defaultIgnored)
        val shopShown = ((req["shopItems"] as? Number)?.toInt() ?: 20).coerceIn(1, SHOP_ITEMS)

        var conditionsError: String? = null
        val conds: Array<Condition>? = try {
            req["conditions"]?.let { conditionsFromJson(it) }
        } catch (e: SpecError) {
            conditionsError = e.message; null
        }

        val out = linkedMapOf<String, Any?>("seed" to seed, "index" to indexForSeed(seed))
        var antes = 8
        if (conds != null) {
            val state = MatchState(conds)
            val maxAnte = conds.maxOf { it.anteMax }
            antes = maxOf(antes, maxAnte)
            val sink = MatchSink(state, maxAnte, prune = false)
            val detail = Detail.forItems(conds.flatMap { it.items }, needEditions = conds.any { it.editionTarget != null })
            val a = SeedAnalyzer(detail, ignored)
            state.reset(detail = true)
            a.reset(seed)
            var aborted = false
            for (ante in 1..maxAnte) {
                a.scanAnte(ante, SHOP_ITEMS, sink)
                if (a.aborted) { aborted = true; break }
                if (state.isComplete) break
            }
            out["score"] = state.total
            out["requirementsMet"] = state.requirementsMet && !aborted
            out["unresolvable"] = aborted
            out["summary"] = state.summary()
            out["matches"] = state.snapshot().map {
                mapOf(
                    "condition" to it.condition.displayName,
                    "item" to it.item.displayName,
                    "edition" to it.edition?.displayName,
                    "ante" to it.ante, "slot" to it.slot, "source" to it.source, "score" to it.score,
                )
            }
        }
        out["conditionsError"] = conditionsError

        val full = SeedAnalyzer(seed, Detail.FULL, ignored)
        out["antes"] = (1..antes).map { n ->
            val r = full.ante(n, shopShown)
            mapOf(
                "ante" to r.ante,
                "boss" to r.boss.displayName,
                "voucher" to r.voucher.displayName,
                "tags" to r.tags.map { it.displayName },
                "shop" to r.shopItems.map { s ->
                    mapOf("name" to s.item.displayName, "edition" to s.edition?.displayName, "kind" to s.kind)
                },
                "packs" to r.packs.map { p ->
                    val contents = when {
                        p.jokers.isNotEmpty() -> p.jokers.map { j -> (j.edition?.let { it.displayName + " " } ?: "") + j.item.displayName }
                        p.consumables.isNotEmpty() -> p.consumables.map { it.displayName }
                        else -> p.cards.map { it.toString() }
                    }
                    mapOf("name" to p.kind.item.displayName, "family" to p.kind.family, "contents" to contents)
                },
                "souls" to r.soulJokers.map { it.displayName },
            )
        }
        return out
    }
}
