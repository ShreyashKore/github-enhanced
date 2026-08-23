package com.gyanoba.prcomments.vcs

enum class HunkLineType { CONTEXT, ADDED, REMOVED }

data class HunkLine(
    val type: HunkLineType,
    val text: String,
    /** 1-based line number on the left (pre-image) side, or null for added lines. */
    val oldLine: Int?,
    /** 1-based line number on the right (post-image) side, or null for removed lines. */
    val newLine: Int?,
)

data class ParsedHunk(
    val lines: List<HunkLine>,
    /** 1-based first line number on the left side of the first `@@` header. */
    val oldStart: Int,
    /** 1-based first line number on the right side of the first `@@` header. */
    val newStart: Int,
) {
    val sourceText: String get() = lines.joinToString("\n") { it.text }
}

/**
 * Parses the unified-diff fragment GitHub returns as `comment.diffHunk` into plain source text plus
 * a parallel per-line classification (§10). Handles the multi-`@@` case: GitHub occasionally packs
 * more than one hunk into a single `diffHunk`.
 */
object DiffHunkParser {

    private val HEADER = Regex("""^@@+ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@""")

    fun parse(hunk: String?): ParsedHunk? {
        if (hunk.isNullOrBlank()) return null

        val lines = mutableListOf<HunkLine>()
        var oldLine = 0
        var newLine = 0
        var firstOldStart: Int? = null
        var firstNewStart: Int? = null

        for (raw in hunk.lines()) {
            val header = HEADER.find(raw)
            if (header != null) {
                oldLine = header.groupValues[1].toIntOrNull() ?: 1
                newLine = header.groupValues[3].toIntOrNull() ?: 1
                if (firstOldStart == null) firstOldStart = oldLine
                if (firstNewStart == null) firstNewStart = newLine
                continue
            }
            // Content before the first header would have no line numbers to anchor to.
            if (firstOldStart == null) continue
            // "\ No newline at end of file" is a diff annotation, not source.
            if (raw.startsWith("\\")) continue

            when {
                raw.startsWith("+") -> {
                    lines += HunkLine(HunkLineType.ADDED, raw.substring(1), null, newLine)
                    newLine++
                }

                raw.startsWith("-") -> {
                    lines += HunkLine(HunkLineType.REMOVED, raw.substring(1), oldLine, null)
                    oldLine++
                }

                else -> {
                    // A context line starts with a single space; GitHub sometimes trims it on blank lines.
                    val text = if (raw.startsWith(" ")) raw.substring(1) else raw
                    lines += HunkLine(HunkLineType.CONTEXT, text, oldLine, newLine)
                    oldLine++
                    newLine++
                }
            }
        }

        if (lines.isEmpty()) return null
        return ParsedHunk(lines, firstOldStart ?: 1, firstNewStart ?: 1)
    }

    /**
     * Index into [ParsedHunk.lines] of the line the comment was left on, or null when the hunk
     * does not cover it. [side] picks which of the two line numberings [line] refers to.
     */
    fun indexOfLine(hunk: ParsedHunk, line: Int?, side: HunkSide): Int? {
        if (line == null) return null
        val index = hunk.lines.indexOfLast {
            when (side) {
                HunkSide.OLD -> it.oldLine == line
                HunkSide.NEW -> it.newLine == line
            }
        }
        return index.takeIf { it >= 0 }
    }

    enum class HunkSide { OLD, NEW }
}
