package com.gyanoba.prcomments.ui

import com.gyanoba.prcomments.PrCommentsBundle
import com.gyanoba.prcomments.model.PullRequestCandidate
import com.gyanoba.prcomments.service.ViewState
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.components.BorderLayoutPanel
import java.awt.FlowLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * What the tool window shows when there is nothing to list: no repo, no token, no PR, or an error.
 * Always offers the action that would fix it — never a bare stack trace or a red balloon (§6).
 */
class EmptyStatePanel(
    private val onRefresh: () -> Unit,
    private val onSetPrNumber: () -> Unit,
    private val onOpenSettings: () -> Unit,
    private val onChoosePr: (PullRequestCandidate) -> Unit,
) : BorderLayoutPanel() {

    private val message = JBLabel("", SwingConstants.CENTER).apply {
        foreground = UIUtil.getContextHelpForeground()
    }

    private val actions = JPanel(VerticalLayout(JBUI.scale(4))).apply {
        isOpaque = false
    }

    init {
        border = JBUI.Borders.empty(24, 16)
        addToTop(
            JPanel(VerticalLayout(JBUI.scale(8))).apply {
                isOpaque = false
                add(message)
                add(actions)
            }
        )
    }

    fun show(text: String, state: ViewState) {
        message.text = "<html><center>" + MarkdownRenderer.escape(text) + "</center></html>"
        actions.removeAll()
        when (state) {
            is ViewState.NoToken -> actions.add(link("action.setToken.text", onOpenSettings))
            is ViewState.ChoosePr -> {
                // More than one open PR points at this branch, so let the user say which (§6.2).
                actions.add(prPicker(state.candidates))
                actions.add(link("action.setPrNumber.text", onSetPrNumber))
            }

            is ViewState.NoPr, is ViewState.DetachedHead ->
                actions.add(link("action.setPrNumber.text", onSetPrNumber))

            is ViewState.Error -> {
                actions.add(link("action.refresh.text", onRefresh))
                actions.add(link("action.settings.text", onOpenSettings))
            }

            else -> Unit
        }
        if (state !is ViewState.Loading && state !is ViewState.Idle && state !is ViewState.Error) {
            actions.add(link("action.refresh.text", onRefresh))
        }
        revalidate()
        repaint()
    }

    private fun link(key: String, action: () -> Unit) = ActionLink(PrCommentsBundle.message(key)) { action() }

    private fun prPicker(candidates: List<PullRequestCandidate>): JComponent =
        JPanel(FlowLayout(FlowLayout.CENTER, JBUI.scale(4), 0)).apply {
            isOpaque = false
            val combo = ComboBox(candidates.toTypedArray())
            add(JBLabel(PrCommentsBundle.message("dialog.choosePr.title")))
            add(combo)
            add(
                ActionLink(PrCommentsBundle.message("action.refresh.text")) {
                    (combo.selectedItem as? PullRequestCandidate)?.let(onChoosePr)
                }
            )
        }
}
