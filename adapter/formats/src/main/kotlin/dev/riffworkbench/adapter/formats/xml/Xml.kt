package dev.riffworkbench.adapter.formats.xml

/**
 * Minimal strict XML reader for untrusted score files. No DTD processing, no external or custom entities
 * (only the five predefined ones and numeric references), bounded depth, node count and input size.
 * A DOCTYPE without internal subset is skipped; one with an internal subset is rejected.
 */
class XmlException(message: String) : IllegalArgumentException(message)

sealed interface XmlNode
data class XmlText(val text: String) : XmlNode
class XmlElement(val name: String, val attributes: Map<String, String>, val children: List<XmlNode>) : XmlNode {
    val elements: List<XmlElement> get() = children.filterIsInstance<XmlElement>()
    /** Concatenated direct text content, trimmed. */
    val text: String get() = children.filterIsInstance<XmlText>().joinToString("") { it.text }.trim()
    // First child per name, built on first lookup in wide elements: shared elements referenced many times (GPIF beats/notes)
    // must not cost O(children) per lookup. Benign race: concurrent builders compute the same map.
    @Volatile private var byName: Map<String, XmlElement>? = null
    fun child(name: String): XmlElement? {
        if (children.size <= 16) return children.firstOrNull { it is XmlElement && it.name == name } as XmlElement?
        val index = byName ?: HashMap<String, XmlElement>().also { m -> for (c in children) if (c is XmlElement) m.putIfAbsent(c.name, c); byName = m }
        return index[name]
    }
    fun children(name: String): List<XmlElement> = children.filter { it is XmlElement && it.name == name }.map { it as XmlElement }
    fun attr(name: String): String? = attributes[name]
    /** Descends by element names, e.g. path("Properties", "Property"). */
    fun path(vararg names: String): XmlElement? { var e: XmlElement? = this; for (n in names) e = e?.child(n); return e }
    fun textOf(vararg names: String): String? = path(*names)?.text
    override fun toString() = "<$name ${attributes.entries.joinToString(" ") { "${it.key}=\"${it.value}\"" }}>"
}

object XmlReader {
    const val MAX_DEPTH = 256
    /**
     * Elements plus attributes (≈ 130 B retained each). Our own GP7 export needs ≈ 18.5 per note, so 3 M keeps ≈ 160 000-note
     * scores importable; hostile inputs above that fail early, and the format guards turn a remaining OOM into RESOURCE_LIMIT.
     */
    const val MAX_NODES = 3_000_000
    const val MAX_CHARS = 64 * 1024 * 1024

    fun parse(bytes: ByteArray): XmlElement {
        if (bytes.size > MAX_CHARS) throw XmlException("XML-Datei ist zu groß")
        val text = when {
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            else -> String(bytes, Charsets.UTF_8)
        }
        return parse(text)
    }

    fun parse(text: String): XmlElement = Parser(text).document()

    private class Parser(private val s: String) {
        private var i = 0
        private var nodes = 0

        fun document(): XmlElement {
            if (s.length > MAX_CHARS) throw XmlException("XML-Datei ist zu groß")
            prolog()
            if (i >= s.length || s[i] != '<') throw error("Wurzelelement fehlt")
            val root = element(0)
            misc()
            if (i < s.length) throw error("Daten nach dem Wurzelelement")
            return root
        }

        private fun prolog() {
            while (true) {
                skipSpace()
                when {
                    s.startsWith("<?", i) -> skipPast("?>")
                    s.startsWith("<!--", i) -> skipPast("-->")
                    s.startsWith("<!DOCTYPE", i) -> doctype()
                    else -> return
                }
            }
        }
        private fun misc() {
            while (true) {
                skipSpace()
                when {
                    s.startsWith("<?", i) -> skipPast("?>")
                    s.startsWith("<!--", i) -> skipPast("-->")
                    else -> return
                }
            }
        }
        private fun doctype() {
            var j = i + 9
            var quote = 0.toChar()
            while (j < s.length) {
                val c = s[j]
                if (quote != 0.toChar()) { if (c == quote) quote = 0.toChar() }
                else if (c == '"' || c == '\'') quote = c
                else if (c == '[') throw error("DOCTYPE mit interner Teilmenge wird aus Sicherheitsgründen abgelehnt")
                else if (c == '>') { i = j + 1; return }
                j++
            }
            throw error("Unvollständiger DOCTYPE")
        }

        private fun element(depth: Int): XmlElement {
            if (depth >= MAX_DEPTH) throw error("XML ist zu tief verschachtelt")
            if (++nodes > MAX_NODES) throw error("XML enthält zu viele Knoten")
            expect('<')
            val name = name()
            val attributes = LinkedHashMap<String, String>()
            while (true) {
                skipSpace()
                if (i >= s.length) throw error("Unerwartetes Dateiende im Tag $name")
                val c = s[i]
                if (c == '/') { expect('/'); expect('>'); return XmlElement(name, attributes, emptyList()) }
                if (c == '>') { i++; break }
                val key = name()
                skipSpace(); expect('='); skipSpace()
                if (i >= s.length) throw error("Unvollständiger Attributwert")
                val quote = s[i]
                if (quote != '"' && quote != '\'') throw error("Attributwert ohne Anführungszeichen")
                i++
                val end = s.indexOf(quote, i)
                if (end < 0) throw error("Unvollständiger Attributwert")
                if (++nodes > MAX_NODES) throw error("XML enthält zu viele Knoten")
                if (attributes.put(key, decode(s.substring(i, end))) != null) throw error("Doppeltes Attribut $key")
                i = end + 1
            }
            val children = ArrayList<XmlNode>()
            val text = StringBuilder()
            fun flush() { if (text.isNotEmpty()) { children += XmlText(text.toString()); text.setLength(0) } }
            while (true) {
                if (i >= s.length) throw error("Element $name wird nicht geschlossen")
                if (s[i] == '<') {
                    when {
                        s.startsWith("</", i) -> {
                            i += 2
                            val close = name()
                            if (close != name) throw error("Schließendes Tag </$close> passt nicht zu <$name>")
                            skipSpace(); expect('>')
                            flush()
                            return XmlElement(name, attributes, children)
                        }
                        s.startsWith("<!--", i) -> skipPast("-->")
                        s.startsWith("<![CDATA[", i) -> {
                            val end = s.indexOf("]]>", i + 9)
                            if (end < 0) throw error("Unvollständiger CDATA-Abschnitt")
                            text.append(s, i + 9, end); i = end + 3
                        }
                        s.startsWith("<?", i) -> skipPast("?>")
                        s.startsWith("<!", i) -> throw error("Deklarationen innerhalb von Elementen sind nicht erlaubt")
                        else -> { flush(); children += element(depth + 1) }
                    }
                } else {
                    val next = s.indexOf('<', i).let { if (it < 0) s.length else it }
                    text.append(decode(s.substring(i, next)))
                    i = next
                }
            }
        }

        private fun name(): String {
            val start = i
            while (i < s.length) {
                val c = s[i]
                if (c.isLetterOrDigit() || c == '_' || c == '-' || c == '.' || c == ':') i++ else break
            }
            if (i == start) throw error("Name erwartet")
            return s.substring(start, i)
        }
        private fun decode(raw: String): String {
            if ('&' !in raw) return raw
            val out = StringBuilder(raw.length)
            var k = 0
            while (k < raw.length) {
                val c = raw[k]
                if (c != '&') { out.append(c); k++; continue }
                val end = raw.indexOf(';', k)
                if (end < 0 || end - k > 12) throw error("Ungültige Zeichenreferenz")
                val ref = raw.substring(k + 1, end)
                when {
                    ref == "lt" -> out.append('<'); ref == "gt" -> out.append('>'); ref == "amp" -> out.append('&')
                    ref == "quot" -> out.append('"'); ref == "apos" -> out.append('\'')
                    ref.startsWith("#x") || ref.startsWith("#X") -> out.appendCodePoint(ref.substring(2).toIntOrNull(16)?.takeIf { valid(it) } ?: throw error("Ungültige Zeichenreferenz &$ref;"))
                    ref.startsWith("#") -> out.appendCodePoint(ref.substring(1).toIntOrNull()?.takeIf { valid(it) } ?: throw error("Ungültige Zeichenreferenz &$ref;"))
                    else -> throw error("Unbekannte Entity &$ref; (Entities werden nicht expandiert)")
                }
                k = end + 1
            }
            return out.toString()
        }
        private fun valid(cp: Int) = cp == 0x9 || cp == 0xA || cp == 0xD || cp in 0x20..0xD7FF || cp in 0xE000..0xFFFD || cp in 0x10000..0x10FFFF
        private fun skipSpace() { while (i < s.length && s[i].isWhitespace()) i++ }
        private fun skipPast(end: String) { val k = s.indexOf(end, i); if (k < 0) throw error("Unvollständige Struktur"); i = k + end.length }
        private fun expect(c: Char) { if (i >= s.length || s[i] != c) throw error("'$c' erwartet"); i++ }
        private fun error(message: String): XmlException {
            val line = s.substring(0, minOf(i, s.length)).count { it == '\n' } + 1
            return XmlException("XML-Fehler in Zeile $line: $message")
        }
    }
}

/** Small XML writer with correct escaping; pretty-prints elements, keeps text content verbatim. */
class XmlWriter(private val indent: String = "  ") {
    private val out = StringBuilder()
    private val stack = ArrayList<String>()
    private var openTag = false
    private var hadChildren = ArrayList<Boolean>()

    fun declaration(): XmlWriter { out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"); return this }
    fun raw(text: String): XmlWriter { closePending(); out.append(text); return this }
    fun start(name: String, vararg attributes: Pair<String, Any?>): XmlWriter {
        closePending()
        if (stack.isNotEmpty()) { out.append('\n'); repeat(stack.size) { out.append(indent) }; hadChildren[hadChildren.lastIndex] = true }
        out.append('<').append(name)
        for ((k, v) in attributes) if (v != null) out.append(' ').append(k).append("=\"").append(escape(v.toString(), true)).append('"')
        stack += name; hadChildren += false; openTag = true
        return this
    }
    fun text(value: Any?): XmlWriter { if (value == null) return this; closePending(); out.append(escape(value.toString(), false)); return this }
    fun cdata(value: String): XmlWriter { closePending(); out.append("<![CDATA[").append(xmlSafe(value).replace("]]>", "]]]]><![CDATA[>")).append("]]>"); return this }
    fun end(): XmlWriter {
        val name = stack.removeAt(stack.lastIndex)
        val children = hadChildren.removeAt(hadChildren.lastIndex)
        if (openTag) { out.append("/>"); openTag = false; return this }
        if (children) { out.append('\n'); repeat(stack.size) { out.append(indent) } }
        out.append("</").append(name).append('>')
        return this
    }
    /** Element with only text content. */
    fun leaf(name: String, value: Any?, vararg attributes: Pair<String, Any?>): XmlWriter { start(name, *attributes); text(value); return end() }
    fun build(): String { require(stack.isEmpty()) { "Offene Elemente: $stack" }; return out.append('\n').toString() }
    private fun closePending() { if (openTag) { out.append('>'); openTag = false } }

    companion object {
        fun escape(value: String, attribute: Boolean): String {
            val sb = StringBuilder(value.length + 8)
            var i = 0
            while (i < value.length) {
                val c = value[i]
                when {
                    c == '<' -> sb.append("&lt;"); c == '>' -> sb.append("&gt;"); c == '&' -> sb.append("&amp;")
                    c == '"' -> if (attribute) sb.append("&quot;") else sb.append(c)
                    c == '\n' -> if (attribute) sb.append("&#10;") else sb.append(c)
                    else -> appendXmlChar(value, i, sb).let { i = it }
                }
                i++
            }
            return sb.toString()
        }
        /** Text XML 1.0 can hold: drops C0 controls (except tab/LF/CR), U+FFFE/U+FFFF and unpaired surrogates. */
        fun xmlSafe(value: String): String { val sb = StringBuilder(value.length); var i = 0; while (i < value.length) { i = appendXmlChar(value, i, sb); i++ }; return sb.toString() }
        private fun appendXmlChar(value: String, i: Int, sb: StringBuilder): Int {
            val c = value[i]
            return when {
                c.isHighSurrogate() && i + 1 < value.length && value[i + 1].isLowSurrogate() -> { sb.append(c).append(value[i + 1]); i + 1 }
                c.isSurrogate() || c == '\uFFFE' || c == '\uFFFF' -> i
                c.code < 0x20 && c != '\t' && c != '\n' && c != '\r' -> i
                else -> { sb.append(c); i }
            }
        }
    }
}
