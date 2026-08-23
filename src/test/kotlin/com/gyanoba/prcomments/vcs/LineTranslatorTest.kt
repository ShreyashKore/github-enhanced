package com.gyanoba.prcomments.vcs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The five states of §11.3, exercised over hand-written fragment lists. */
class LineTranslatorTest {

    @Test
    fun `no fragments means the line did not move`() {
        val result = LineTranslator.translate(41, emptyList())
        assertEquals(MappedLineState.EXACT, result.state)
        assertEquals(41, result.line)
    }

    @Test
    fun `a line before every change stays put`() {
        // 10 lines inserted at line 100, far below our target.
        val fragments = listOf(DiffLineRange(100, 100, 100, 110))
        val result = LineTranslator.translate(41, fragments)
        assertEquals(MappedLineState.EXACT, result.state)
        assertEquals(41, result.line)
    }

    @Test
    fun `insertion above shifts the line down`() {
        // 10 blank lines inserted at the top.
        val fragments = listOf(DiffLineRange(0, 0, 0, 10))
        val result = LineTranslator.translate(41, fragments)
        assertEquals(MappedLineState.SHIFTED, result.state)
        assertEquals(51, result.line)
    }

    @Test
    fun `deletion above shifts the line up`() {
        val fragments = listOf(DiffLineRange(0, 5, 0, 0))
        val result = LineTranslator.translate(41, fragments)
        assertEquals(MappedLineState.SHIFTED, result.state)
        assertEquals(36, result.line)
    }

    @Test
    fun `several fragments accumulate their deltas`() {
        val fragments = listOf(
            DiffLineRange(0, 2, 0, 5),    // +3
            DiffLineRange(10, 20, 13, 15), // -8
        )
        val result = LineTranslator.translate(41, fragments)
        assertEquals(MappedLineState.SHIFTED, result.state)
        assertEquals(41 + 3 - 8, result.line)
    }

    @Test
    fun `fragments do not need to be sorted`() {
        val fragments = listOf(
            DiffLineRange(10, 20, 13, 15),
            DiffLineRange(0, 2, 0, 5),
        )
        assertEquals(36, LineTranslator.translate(41, fragments).line)
    }

    @Test
    fun `editing the line itself reports MODIFIED`() {
        val fragments = listOf(DiffLineRange(40, 43, 40, 43))
        val result = LineTranslator.translate(41, fragments)
        assertEquals(MappedLineState.MODIFIED, result.state)
        assertEquals(41, result.line)
    }

    @Test
    fun `MODIFIED clamps into a fragment that shrank`() {
        // Three old lines collapsed into one.
        val fragments = listOf(DiffLineRange(40, 43, 40, 41))
        val result = LineTranslator.translate(42, fragments)
        assertEquals(MappedLineState.MODIFIED, result.state)
        assertEquals(40, result.line)
    }

    @Test
    fun `deleting the line reports DELETED and still points somewhere sane`() {
        val fragments = listOf(DiffLineRange(40, 43, 40, 40))
        val result = LineTranslator.translate(41, fragments)
        assertEquals(MappedLineState.DELETED, result.state)
        assertEquals(40, result.line)
    }

    @Test
    fun `a negative line is never mapped`() {
        val result = LineTranslator.translate(-1, emptyList())
        assertEquals(MappedLineState.UNKNOWN, result.state)
        assertEquals(null, result.line)
    }

    @Test
    fun `mapped lines never go negative`() {
        val fragments = listOf(DiffLineRange(0, 50, 0, 0))
        // Line 60 survives, shifted up by 50.
        assertEquals(10, LineTranslator.translate(60, fragments).line)
    }

    @Test
    fun `navigability follows the state`() {
        assertEquals(true, MappedLine(MappedLineState.SHIFTED, 5).isNavigable)
        assertEquals(true, MappedLine(MappedLineState.MODIFIED, 5).isNavigable)
        assertEquals(false, MappedLine(MappedLineState.DELETED, 5).isNavigable)
        assertEquals(false, MappedLine.fileDeleted().isNavigable)
        assertEquals(false, MappedLine.unknown().isNavigable)
    }
}
