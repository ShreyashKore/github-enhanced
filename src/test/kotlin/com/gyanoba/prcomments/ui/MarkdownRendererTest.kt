package com.gyanoba.prcomments.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarkdownRendererTest {

    private fun html(markdown: String) = MarkdownRenderer.toHtml(markdown)

    @Test
    fun `plain text becomes a paragraph`() {
        assertEquals("<p>hello</p>", html("hello"))
    }

    @Test
    fun `soft line breaks stay inside one paragraph`() {
        assertEquals("<p>one<br/>two</p>", html("one\ntwo"))
    }

    @Test
    fun `a blank line starts a new paragraph`() {
        assertEquals("<p>one</p><p>two</p>", html("one\n\ntwo"))
    }

    @Test
    fun `emphasis and strikethrough`() {
        assertEquals("<p><b>bold</b></p>", html("**bold**"))
        assertEquals("<p><b>bold</b></p>", html("__bold__"))
        assertEquals("<p><i>italic</i></p>", html("*italic*"))
        assertEquals("<p><s>gone</s></p>", html("~~gone~~"))
    }

    @Test
    fun `snake_case identifiers are not italicised`() {
        assertEquals("<p>some_long_name</p>", html("some_long_name"))
    }

    @Test
    fun `inline code is escaped and never re-parsed`() {
        assertEquals("<p><code>a &lt; b &amp;&amp; c &gt; d</code></p>", html("`a < b && c > d`"))
        // Markdown syntax inside a code span must survive verbatim.
        assertEquals("<p><code>**not bold**</code></p>", html("`**not bold**`"))
    }

    @Test
    fun `fenced code blocks are escaped`() {
        assertEquals(
            "<pre><code>if (a &lt; b) {\n  x()\n}</code></pre>",
            html("```kotlin\nif (a < b) {\n  x()\n}\n```"),
        )
    }

    @Test
    fun `unterminated fences still render`() {
        assertEquals("<pre><code>oops</code></pre>", html("```\noops"))
    }

    @Test
    fun `headings are demoted so they fit inside the pane`() {
        assertEquals("<h3>Title</h3>", html("# Title"))
        assertEquals("<h6>Deep</h6>", html("##### Deep"))
    }

    @Test
    fun `lists`() {
        assertEquals("<ul><li>one</li><li>two</li></ul>", html("- one\n- two"))
        assertEquals("<ol><li>one</li><li>two</li></ol>", html("1. one\n2. two"))
    }

    @Test
    fun `block quotes are rendered recursively`() {
        assertEquals("<blockquote><p>quoted</p></blockquote>", html("> quoted"))
    }

    @Test
    fun `horizontal rules`() {
        assertEquals("<hr/>", html("---"))
    }

    @Test
    fun `links keep their label and target`() {
        assertEquals(
            """<p><a href="https://example.com">docs</a></p>""",
            html("[docs](https://example.com)"),
        )
    }

    @Test
    fun `balanced parentheses in a link target are consumed`() {
        assertEquals(
            """<p><a href="https://en.wikipedia.org/wiki/Foo_(bar)">Foo</a></p>""",
            html("[Foo](https://en.wikipedia.org/wiki/Foo_(bar))"),
        )
    }

    @Test
    fun `bare URLs are linkified`() {
        assertTrue(html("see https://example.com/x for more").contains("""<a href="https://example.com/x">"""))
    }

    @Test
    fun `dangerous link targets are stripped down to their label`() {
        val rendered = html("[click](javascript:alert(1))")
        assertFalse(rendered.contains("javascript:"), rendered)
        assertEquals("<p>click</p>", rendered)
    }

    @Test
    fun `data URLs in images are rejected`() {
        val rendered = html("![x](data:text/html;base64,PHNjcmlwdD4=)")
        assertFalse(rendered.contains("data:"), rendered)
    }

    @Test
    fun `raw HTML in a comment body cannot inject tags`() {
        val rendered = html("<script>alert(1)</script>")
        assertFalse(rendered.contains("<script>"), rendered)
        assertTrue(rendered.contains("&lt;script&gt;"), rendered)
    }

    @Test
    fun `ampersands and quotes are escaped exactly once`() {
        assertEquals("<p>a &amp; b &quot;c&quot;</p>", html("""a & b "c""""))
    }

    @Test
    fun `a realistic review comment renders end to end`() {
        val body = """
            This allocates on **every** tick.

            ```kotlin
            val buffer = ByteArray(4096)
            ```

            Consider hoisting it — see [the docs](https://example.com/perf).
        """.trimIndent()
        val rendered = html(body)

        assertTrue(rendered.startsWith("<p>This allocates on <b>every</b> tick.</p>"))
        assertTrue(rendered.contains("<pre><code>val buffer = ByteArray(4096)</code></pre>"))
        assertTrue(rendered.contains("""<a href="https://example.com/perf">the docs</a>"""))
    }

    @Test
    fun `empty input produces empty output`() {
        assertEquals("", html(""))
        assertEquals("", html("\n\n  \n"))
    }
}
