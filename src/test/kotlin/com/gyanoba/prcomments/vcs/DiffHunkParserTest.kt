package com.gyanoba.prcomments.vcs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Recorded `diffHunk` payloads: pure context, additions, removals and a multi-hunk fragment. */
class DiffHunkParserTest {

    @Test
    fun `null and blank hunks yield nothing`() {
        assertNull(DiffHunkParser.parse(null))
        assertNull(DiffHunkParser.parse(""))
        assertNull(DiffHunkParser.parse("   "))
    }

    @Test
    fun `context-only hunk keeps both numberings in step`() {
        val hunk = """
            @@ -10,4 +10,4 @@ fun main() {
                 val a = 1
                 val b = 2
                 val c = 3
                 val d = 4
        """.trimIndent()
        val parsed = DiffHunkParser.parse(hunk)!!

        assertEquals(10, parsed.oldStart)
        assertEquals(10, parsed.newStart)
        assertEquals(4, parsed.lines.size)
        assertEquals(List(4) { HunkLineType.CONTEXT }, parsed.lines.map { it.type })
        assertEquals(listOf(10, 11, 12, 13), parsed.lines.map { it.oldLine })
        assertEquals(listOf(10, 11, 12, 13), parsed.lines.map { it.newLine })
        assertEquals("    val a = 1\n    val b = 2\n    val c = 3\n    val d = 4", parsed.sourceText)
    }

    @Test
    fun `added lines have no old numbering`() {
        val hunk = "@@ -5,2 +5,4 @@\n context one\n+added one\n+added two\n context two"
        val parsed = DiffHunkParser.parse(hunk)!!

        assertEquals(
            listOf(HunkLineType.CONTEXT, HunkLineType.ADDED, HunkLineType.ADDED, HunkLineType.CONTEXT),
            parsed.lines.map { it.type },
        )
        assertEquals(listOf(5, null, null, 6), parsed.lines.map { it.oldLine })
        assertEquals(listOf(5, 6, 7, 8), parsed.lines.map { it.newLine })
        assertEquals("context one\nadded one\nadded two\ncontext two", parsed.sourceText)
    }

    @Test
    fun `removed lines have no new numbering`() {
        val hunk = "@@ -5,4 +5,2 @@\n context one\n-gone one\n-gone two\n context two"
        val parsed = DiffHunkParser.parse(hunk)!!

        assertEquals(listOf(5, 6, 7, 8), parsed.lines.map { it.oldLine })
        assertEquals(listOf(5, null, null, 6), parsed.lines.map { it.newLine })
    }

    @Test
    fun `a second header restarts the numbering`() {
        val hunk = "@@ -1,2 +1,2 @@\n first\n second\n@@ -50,2 +60,2 @@\n fiftieth\n+brand new"
        val parsed = DiffHunkParser.parse(hunk)!!

        assertEquals(1, parsed.oldStart)
        assertEquals(1, parsed.newStart)
        assertEquals(listOf(1, 2, 50, null), parsed.lines.map { it.oldLine })
        assertEquals(listOf(1, 2, 60, 61), parsed.lines.map { it.newLine })
    }

    @Test
    fun `single-line hunks omit the count`() {
        val parsed = DiffHunkParser.parse("@@ -7 +7 @@\n-old\n+new")!!
        assertEquals(7, parsed.oldStart)
        assertEquals(7, parsed.newStart)
        assertEquals(listOf(HunkLineType.REMOVED, HunkLineType.ADDED), parsed.lines.map { it.type })
    }

    @Test
    fun `the no-newline marker is not source`() {
        val parsed = DiffHunkParser.parse("@@ -1,1 +1,1 @@\n-old\n\\ No newline at end of file\n+new")!!
        assertEquals(listOf("old", "new"), parsed.lines.map { it.text })
    }

    @Test
    fun `content before the first header is ignored`() {
        val parsed = DiffHunkParser.parse("stray text\n@@ -1,1 +1,1 @@\n only")!!
        assertEquals(listOf("only"), parsed.lines.map { it.text })
    }

    @Test
    fun `blank context lines survive even without their leading space`() {
        val parsed = DiffHunkParser.parse("@@ -1,3 +1,3 @@\n a\n\n b")!!
        assertEquals(listOf("a", "", "b"), parsed.lines.map { it.text })
        assertEquals(listOf(1, 2, 3), parsed.lines.map { it.newLine })
    }

    @Test
    fun `indexOfLine finds the commented line on the requested side`() {
        val parsed = DiffHunkParser.parse("@@ -5,4 +5,2 @@\n context one\n-gone one\n-gone two\n context two")!!

        assertEquals(3, DiffHunkParser.indexOfLine(parsed, 6, DiffHunkParser.HunkSide.NEW))
        assertEquals(2, DiffHunkParser.indexOfLine(parsed, 7, DiffHunkParser.HunkSide.OLD))
        assertNull(DiffHunkParser.indexOfLine(parsed, 999, DiffHunkParser.HunkSide.NEW))
        assertNull(DiffHunkParser.indexOfLine(parsed, null, DiffHunkParser.HunkSide.NEW))
    }
}
