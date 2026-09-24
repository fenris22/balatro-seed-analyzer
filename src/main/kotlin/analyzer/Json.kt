package analyzer

/**
 * A small JSON reader and writer, enough for the web page and the conditions file.
 *
 * Kept in-house so the bundle needs no extra library: objects come back as
 * LinkedHashMap<String, Any?>, arrays as List<Any?>, whole numbers as Long, other numbers
 * as Double.
 */
object Json {

    class ParseError(message: String) : Exception(message)

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.value()
        p.skipWs()
        if (p.i != text.length) throw p.err("unexpected text after the end")
        return v
    }

    private class Parser(val s: String) {
        var i = 0

        fun err(msg: String): ParseError {
            val line = s.substring(0, minOf(i, s.length)).count { it == '\n' } + 1
            return ParseError("$msg (line $line)")
        }

        fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            if (i >= s.length) throw err("unexpected end of text")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw err("unexpected '$c'")
            }
        }

        fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw err("expected $word")
            i += word.length
            return v
        }

        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                skipWs()
                if (i >= s.length || s[i] != '"') throw err("expected a quoted key")
                val k = str()
                skipWs()
                if (i >= s.length || s[i] != ':') throw err("expected ':'")
                i++
                skipWs()
                m[k] = value()
                skipWs()
                if (i >= s.length) throw err("unexpected end of text")
                if (s[i] == ',') { i++; continue }
                if (s[i] == '}') { i++; return m }
                throw err("expected ',' or '}'")
            }
        }

        fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                skipWs()
                l.add(value())
                skipWs()
                if (i >= s.length) throw err("unexpected end of text")
                if (s[i] == ',') { i++; continue }
                if (s[i] == ']') { i++; return l }
                throw err("expected ',' or ']'")
            }
        }

        fun str(): String {
            val sb = StringBuilder()
            i++
            while (true) {
                if (i >= s.length) throw err("unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) throw err("unterminated string")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw err("bad \\u escape")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> throw err("bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun num(): Any {
            val st = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(st, i)
            if (t.none { it in ".eE" }) t.toLongOrNull()?.let { return it }
            return t.toDoubleOrNull() ?: throw err("bad number '$t'")
        }
    }

    fun write(v: Any?): String = StringBuilder().also { write(v, it) }.toString()

    fun write(v: Any?, sb: StringBuilder) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(v, sb)
            is Boolean -> sb.append(v)
            is Double -> if (v.isNaN() || v.isInfinite()) sb.append("null")
                else if (v == Math.rint(v) && Math.abs(v) < 1e15) sb.append(v.toLong()) else sb.append(v)
            is Float -> write(v.toDouble(), sb)
            is Number -> sb.append(v.toLong())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(k.toString(), sb); sb.append(':'); write(value, sb)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (x in v) { if (!first) sb.append(','); first = false; write(x, sb) }
                sb.append(']')
            }
            is Array<*> -> write(v.asList(), sb)
            is IntArray -> write(v.asList(), sb)
            is LongArray -> write(v.asList(), sb)
            else -> quote(v.toString(), sb)
        }
    }

    private fun quote(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' || c == ' ' || c == ' ' -> sb.append("\\u%04x".format(c.code))
                c == '<' -> sb.append("\\u003c")   // safe to embed in a page
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }
}
