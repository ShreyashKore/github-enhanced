package com.gyanoba.prcomments.ui

/**
 * A deliberately small GitHub-flavoured-Markdown subset renderer.
 *
 * The plugin must not hard-depend on the Markdown plugin (§12.1), and comment bodies only ever use
 * a handful of constructs, so this covers headings, lists, block quotes, fenced and inline code,
 * emphasis, strikethrough, links and rules. Everything is HTML-escaped before any tag is inserted,
 * and link targets are restricted to http/https/mailto so a comment cannot smuggle in a
 * `javascript:` URL.
 */
object MarkdownRenderer {

    private val FENCE = Regex("""^\s*(```+|~~~+)\s*(\S*)\s*$""")
    private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
    private val RULE = Regex("""^\s*(-{3,}|\*{3,}|_{3,})\s*$""")
    private val UNORDERED = Regex("""^\s*[-*+]\s+(.*)$""")
    private val ORDERED = Regex("""^\s*\d+[.)]\s+(.*)$""")
    private val QUOTE = Regex("""^\s*>\s?(.*)$""")

    private val CODE_SPAN = Regex("""(`+)([^`]|[^`].*?[^`])\1""")
    // The target allows one level of balanced parentheses, so both
    // `https://en.wikipedia.org/wiki/Foo_(bar)` and `javascript:alert(1)` are consumed whole.
    private const val LINK_TARGET = """((?:[^()\s]|\([^()\s]*\))+)"""
    private val IMAGE = Regex("""!\[([^\]]*)]\(""" + LINK_TARGET + """(?:\s+"[^"]*")?\)""")
    private val LINK = Regex("""\[([^\]]*)]\(""" + LINK_TARGET + """(?:\s+"[^"]*")?\)""")
    private val BOLD = Regex("""(\*\*|__)(?=\S)(.+?)(?<=\S)\1""")
    private val ITALIC = Regex("""(?<![\w*_])([*_])(?=\S)(.+?)(?<=\S)\1(?![\w*_])""")
    private val STRIKE = Regex("""~~(?=\S)(.+?)(?<=\S)~~""")
    private val BARE_URL = Regex("""(?<![\w"'>=])(https?://[^\s<>"']+[^\s<>"'.,;:!?)])""")

    // Sentinels for stashed code spans: control characters cannot occur in a comment body,
    // and they pass through HTML escaping untouched.
    private const val CODE_OPEN = "\u0001"
    private const val CODE_CLOSE = "\u0002"

    fun toHtml(markdown: String): String {
        val out = StringBuilder()
        val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n")

        var index = 0
        while (index < lines.size) {
            val line = lines[index]

            val fence = FENCE.matchEntire(line)
            if (fence != null) {
                val marker = fence.groupValues[1].first()
                val body = StringBuilder()
                index++
                while (index < lines.size &&
                    FENCE.matchEntire(lines[index])?.groupValues?.get(1)?.startsWith(marker) != true
                ) {
                    body.appendLine(lines[index])
                    index++
                }
                if (index < lines.size) index++ // consume the closing fence
                out.append("<pre><code>").append(escape(body.toString().trimEnd('\n'))).append("</code></pre>")
                continue
            }

            if (line.isBlank()) {
                index++
                continue
            }

            if (RULE.matches(line)) {
                out.append("<hr/>")
                index++
                continue
            }

            val heading = HEADING.matchEntire(line)
            if (heading != null) {
                // Comment bodies are rendered inside a pane, so demote headings a couple of levels.
                val level = (heading.groupValues[1].length + 2).coerceAtMost(6)
                out.append("<h").append(level).append('>')
                    .append(inline(heading.groupValues[2]))
                    .append("</h").append(level).append('>')
                index++
                continue
            }

            if (QUOTE.matches(line)) {
                val body = StringBuilder()
                while (index < lines.size && QUOTE.matches(lines[index])) {
                    body.appendLine(QUOTE.matchEntire(lines[index])!!.groupValues[1])
                    index++
                }
                out.append("<blockquote>").append(toHtml(body.toString())).append("</blockquote>")
                continue
            }

            if (UNORDERED.matches(line) || ORDERED.matches(line)) {
                val ordered = ORDERED.matches(line) && !UNORDERED.matches(line)
                val tag = if (ordered) "ol" else "ul"
                out.append('<').append(tag).append('>')
                while (index < lines.size) {
                    val item = (if (ordered) ORDERED else UNORDERED).matchEntire(lines[index]) ?: break
                    out.append("<li>").append(inline(item.groupValues[1])).append("</li>")
                    index++
                }
                out.append("</").append(tag).append('>')
                continue
            }

            // Paragraph: consume until a blank line or the start of another block.
            val paragraph = StringBuilder()
            while (index < lines.size && lines[index].isNotBlank() && !startsBlock(lines[index])) {
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(lines[index])
                index++
            }
            if (paragraph.isNotEmpty()) {
                out.append("<p>").append(inline(paragraph.toString()).replace("\n", "<br/>")).append("</p>")
            }
        }

        return out.toString()
    }

    private fun startsBlock(line: String): Boolean =
        FENCE.matches(line) || HEADING.matches(line) || RULE.matches(line) ||
            QUOTE.matches(line) || UNORDERED.matches(line) || ORDERED.matches(line)

    /** Escapes, then applies inline constructs. Code spans are stashed so nothing rewrites them. */
    private fun inline(text: String): String {
        val codeSpans = mutableListOf<String>()
        val stashed = CODE_SPAN.replace(text) { match ->
            codeSpans += match.groupValues[2].trim()
            CODE_OPEN + (codeSpans.size - 1) + CODE_CLOSE
        }

        var html = escape(stashed)
        html = IMAGE.replace(html) { match ->
            val alt = match.groupValues[1]
            val src = sanitizeUrl(match.groupValues[2])
            if (src == null) alt else """<img src="$src" alt="$alt"/>"""
        }
        html = LINK.replace(html) { match ->
            val label = match.groupValues[1].ifEmpty { match.groupValues[2] }
            val href = sanitizeUrl(match.groupValues[2])
            if (href == null) label else """<a href="$href">$label</a>"""
        }
        html = BOLD.replace(html) { "<b>" + it.groupValues[2] + "</b>" }
        html = ITALIC.replace(html) { "<i>" + it.groupValues[2] + "</i>" }
        html = STRIKE.replace(html) { "<s>" + it.groupValues[1] + "</s>" }
        html = BARE_URL.replace(html) { match ->
            val raw = match.groupValues[1]
            val href = sanitizeUrl(raw)
            if (href == null) raw else """<a href="$href">$raw</a>"""
        }

        codeSpans.forEachIndexed { i, code ->
            html = html.replace(CODE_OPEN + i + CODE_CLOSE, "<code>" + escape(code) + "</code>")
        }
        return html
    }

    /** Null for anything that is not a plain web or mail link. */
    private fun sanitizeUrl(url: String): String? {
        val trimmed = url.trim()
        val lower = trimmed.lowercase()
        val allowed = lower.startsWith("http://") || lower.startsWith("https://") ||
            lower.startsWith("mailto:") || trimmed.startsWith("#")
        if (!allowed) return null
        // The URL sits inside a double-quoted attribute produced after escaping.
        return trimmed.replace("\"", "&quot;")
    }

    fun escape(text: String): String = buildString(text.length) {
        for (ch in text) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> append(ch)
            }
        }
    }
}
