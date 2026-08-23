package com.gyanoba.prcomments.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.LineNumberConverter
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.diff.DiffColors
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import java.awt.Color
import java.awt.Dimension
import javax.swing.Icon

/** How a preview line should be tinted. */
enum class PreviewLineKind { NORMAL, ADDED, REMOVED }

/**
 * A read-only, syntax-highlighted snippet of a file with real absolute line numbers in the gutter.
 *
 * Backing the preview with a real editor is what buys syntax highlighting and the IDE's own diff
 * colours (§10.3). The editor is owned by an [EditorTextField], which releases it on `removeNotify`,
 * so swapping content by replacing the field leaks nothing (guardrail §14.2).
 */
class CodePreview(private val project: Project?) : BorderLayoutPanel() {

    private var field: EditorTextField? = null

    init {
        border = JBUI.Borders.customLine(JBColor.border(), 1)
        isOpaque = false
    }

    fun clear() {
        field?.let { remove(it) }
        field = null
        revalidate()
        repaint()
    }

    /**
     * @param firstLineNumber 1-based absolute line number of the first line of [text]. Used when
     *   [lineNumbers] is empty, i.e. for a contiguous snippet.
     * @param lineNumbers explicit 1-based number per line, for a unified-diff fragment where added
     *   and removed rows interleave and a running count would be wrong. Null entries show no number.
     * @param focusLine index into [text]'s lines of the commented line, or null.
     * @param maxVisibleLines caps the height; longer snippets scroll (§10.6).
     */
    fun setContent(
        fileName: String,
        text: String,
        firstLineNumber: Int,
        lineKinds: List<PreviewLineKind> = emptyList(),
        lineNumbers: List<Int?> = emptyList(),
        focusLine: Int? = null,
        maxVisibleLines: Int = DEFAULT_MAX_VISIBLE_LINES,
    ) {
        clear()

        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(fileName)
        val document = EditorFactory.getInstance().createDocument(text)
        val lineCount = document.lineCount.coerceAtLeast(1)

        val editorField = EditorTextField(document, project, fileType, true, false)
        editorField.setFontInheritedFromLAF(false)
        editorField.addSettingsProvider { editor ->
            editor.settings.apply {
                isLineNumbersShown = true
                isLineMarkerAreaShown = false
                isFoldingOutlineShown = false
                isIndentGuidesShown = false
                isRightMarginShown = false
                isCaretRowShown = false
                isUseSoftWraps = false
                additionalLinesCount = 0
                additionalColumnsCount = 0
            }
            editor.setVerticalScrollbarVisible(lineCount > maxVisibleLines)
            editor.setHorizontalScrollbarVisible(true)
            editor.gutter.setLineNumberConverter(
                if (lineNumbers.isEmpty()) {
                    AbsoluteLineNumbers(firstLineNumber, lineCount)
                } else {
                    ExplicitLineNumbers(lineNumbers)
                }
            )
            editor.gutterComponentEx.setPaintBackground(false)

            applyLineKinds(editor, lineKinds)
            focusLine?.let { applyFocus(editor, it) }

            val visibleLines = lineCount.coerceAtMost(maxVisibleLines)
            val height = visibleLines * editor.lineHeight + JBUI.scale(VERTICAL_PADDING)
            editorField.preferredSize = Dimension(editorField.preferredSize.width, height)
            editorField.revalidate()
        }

        field = editorField
        addToCenter(editorField)
        revalidate()
        repaint()
    }

    private fun applyLineKinds(editor: Editor, kinds: List<PreviewLineKind>) {
        val markup = editor.markupModel
        kinds.forEachIndexed { line, kind ->
            if (line >= editor.document.lineCount) return@forEachIndexed
            val key = when (kind) {
                PreviewLineKind.ADDED -> DiffColors.DIFF_INSERTED
                PreviewLineKind.REMOVED -> DiffColors.DIFF_DELETED
                PreviewLineKind.NORMAL -> null
            } ?: return@forEachIndexed
            markup.addLineHighlighter(key, line, HighlighterLayer.ADDITIONAL_SYNTAX)
        }
    }

    private fun applyFocus(editor: Editor, line: Int) {
        if (line < 0 || line >= editor.document.lineCount) return
        val attributes = TextAttributes().apply {
            effectType = EffectType.BOXED
            effectColor = FOCUS_BORDER
        }
        val highlighter = editor.markupModel.addRangeHighlighter(
            editor.document.getLineStartOffset(line),
            editor.document.getLineEndOffset(line),
            HighlighterLayer.SELECTION - 1,
            attributes,
            HighlighterTargetArea.LINES_IN_RANGE,
        )
        highlighter.gutterIconRenderer = CommentedLineGutterIcon
    }

    /** Renumbers the gutter so it shows real file line numbers rather than 1..n (§10.3). */
    private class AbsoluteLineNumbers(private val firstLineNumber: Int, private val lineCount: Int) :
        LineNumberConverter {

        override fun convert(editor: Editor, lineNumber: Int): Int? = firstLineNumber + lineNumber - 1

        override fun getMaxLineNumber(editor: Editor): Int? = firstLineNumber + lineCount - 1
    }

    /** Per-line numbering for a unified-diff fragment (§10.1): the hunk states each line's number. */
    private class ExplicitLineNumbers(private val numbers: List<Int?>) : LineNumberConverter {

        override fun convert(editor: Editor, lineNumber: Int): Int? = numbers.getOrNull(lineNumber - 1)

        override fun getMaxLineNumber(editor: Editor): Int? = numbers.filterNotNull().maxOrNull()
    }

    private object CommentedLineGutterIcon : GutterIconRenderer() {
        override fun getIcon(): Icon = AllIcons.General.ArrowRight
        override fun equals(other: Any?): Boolean = other === this
        override fun hashCode(): Int = javaClass.hashCode()
    }

    companion object {
        const val DEFAULT_MAX_VISIBLE_LINES: Int = 10
        private const val VERTICAL_PADDING = 4

        private val FOCUS_BORDER: Color = JBColor(Color(0x3574F0), Color(0x548AF7))
    }
}
