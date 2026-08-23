package com.gyanoba.prcomments.ui

import com.gyanoba.prcomments.PrCommentsBundle
import com.gyanoba.prcomments.model.ReviewThread
import com.intellij.icons.AllIcons
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Component
import javax.swing.BoxLayout
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

/**
 * Three-line row (§9.2). `ColoredListCellRenderer` renders a single line, so the row is a small
 * panel of three [SimpleColoredComponent]s instead — still platform components, still themed.
 */
class ThreadListCellRenderer(
    private val newThreadIds: () -> Set<String>,
) : JPanel(), ListCellRenderer<ReviewThread> {

    private val firstLine = line()
    private val secondLine = line()
    private val thirdLine = line()

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(4, 6)
        add(firstLine)
        add(secondLine)
        add(thirdLine)
    }

    override fun getListCellRendererComponent(
        list: JList<out ReviewThread>,
        value: ReviewThread?,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ): Component {
        val background = if (selected) list.selectionBackground else list.background
        val foreground = if (selected) list.selectionForeground else list.foreground
        isOpaque = true
        this.background = background
        listOf(firstLine, secondLine, thirdLine).forEach {
            it.clear()
            it.background = background
            it.foreground = foreground
            it.isOpaque = false
        }
        if (value == null) return this

        val grayed = if (selected) SimpleTextAttributes.REGULAR_ATTRIBUTES else SimpleTextAttributes.GRAYED_ATTRIBUTES

        firstLine.icon = if (value.isResolved) AllIcons.Actions.Checked else AllIcons.General.Balloon
        firstLine.append(value.fileName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        value.displayLine?.let { firstLine.append(":$it", grayed) }
        if (value.isOutdated) {
            firstLine.append("  " + PrCommentsBundle.message("list.row.outdated"), grayed)
        }
        if (value.nodeId in newThreadIds()) {
            firstLine.append("  " + PrCommentsBundle.message("list.row.new"), SimpleTextAttributes.LINK_ATTRIBUTES)
        }

        secondLine.append("@" + value.root.authorLogin, grayed)
        secondLine.append(SEPARATOR + DateFormatUtil.formatPrettyDateTime(value.lastActivityAt.toEpochMilli()), grayed)
        secondLine.append(SEPARATOR + replyText(value.replyCount), grayed)
        if (value.isResolved) {
            secondLine.append(SEPARATOR + PrCommentsBundle.message("list.row.resolved"), grayed)
        }

        thirdLine.append(summarize(value.root.bodyMarkdown), grayed)

        return this
    }

    private fun replyText(count: Int): String = when (count) {
        0 -> PrCommentsBundle.message("list.row.replies.none")
        1 -> PrCommentsBundle.message("list.row.replies.one")
        else -> PrCommentsBundle.message("list.row.replies.many", count)
    }

    private fun line() = SimpleColoredComponent().apply {
        isOpaque = false
        ipad = JBUI.emptyInsets()
        font = UIUtil.getListFont()
    }

    companion object {
        private const val SEPARATOR = " · "
        private const val SUMMARY_LENGTH = 90

        /** First line of the body, collapsed and ellipsized to fit one row. */
        fun summarize(body: String): String {
            val firstMeaningful = body.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("```") }
                ?: ""
            val collapsed = firstMeaningful.replace(Regex("\\s+"), " ")
            return if (collapsed.length <= SUMMARY_LENGTH) collapsed else collapsed.take(SUMMARY_LENGTH - 1) + "…"
        }
    }
}
