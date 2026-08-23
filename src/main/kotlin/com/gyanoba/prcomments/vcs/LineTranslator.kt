package com.gyanoba.prcomments.vcs

/** One changed region, in 0-based half-open line ranges over the old and new texts. */
data class DiffLineRange(val start1: Int, val end1: Int, val start2: Int, val end2: Int)

enum class MappedLineState {
    /** The line is at the same place in both texts. */
    EXACT,

    /** Unchanged content, but edits above moved it. */
    SHIFTED,

    /** The line itself sits inside a changed region. */
    MODIFIED,

    /** The region containing the line was deleted outright. */
    DELETED,

    /** The file is gone from the working tree. */
    FILE_DELETED,

    /** We could not establish a mapping (content unavailable, diff too big, …). */
    UNKNOWN,
}

/** [line] is 0-based in the new text, or null when there is nothing to point at. */
data class MappedLine(val state: MappedLineState, val line: Int?) {
    val isNavigable: Boolean
        get() = line != null && state != MappedLineState.DELETED && state != MappedLineState.FILE_DELETED

    companion object {
        fun unknown(): MappedLine = MappedLine(MappedLineState.UNKNOWN, null)
        fun fileDeleted(): MappedLine = MappedLine(MappedLineState.FILE_DELETED, null)
    }
}

/**
 * Pure line-drift arithmetic (§11.2 step 3). Kept free of platform types so it can be unit tested
 * without an IDE fixture; [LineMapper] adapts `ComparisonManager` fragments into [DiffLineRange]s.
 */
object LineTranslator {

    /**
     * Maps a 0-based [line] in the old text to the new text, given the changed regions between them.
     * [fragments] need not be sorted.
     */
    fun translate(line: Int, fragments: List<DiffLineRange>): MappedLine {
        if (line < 0) return MappedLine.unknown()
        var delta = 0
        for (fragment in fragments.sortedBy { it.start1 }) {
            if (line < fragment.start1) {
                // Before this fragment and after every earlier one: pure shift.
                return shifted(line + delta, delta)
            }
            if (line < fragment.end1) {
                // Inside a changed region.
                if (fragment.end2 == fragment.start2) {
                    return MappedLine(MappedLineState.DELETED, fragment.start2)
                }
                val offsetInFragment = (line - fragment.start1).coerceAtMost(fragment.end2 - fragment.start2 - 1)
                return MappedLine(MappedLineState.MODIFIED, fragment.start2 + offsetInFragment)
            }
            delta += (fragment.end2 - fragment.start2) - (fragment.end1 - fragment.start1)
        }
        return shifted(line + delta, delta)
    }

    private fun shifted(mapped: Int, delta: Int): MappedLine =
        MappedLine(if (delta == 0) MappedLineState.EXACT else MappedLineState.SHIFTED, mapped.coerceAtLeast(0))
}
