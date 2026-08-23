package com.gyanoba.prcomments.actions

import com.gyanoba.prcomments.PrCommentsBundle
import com.gyanoba.prcomments.model.ReviewThread
import com.gyanoba.prcomments.settings.PrCommentsConfigurable
import com.gyanoba.prcomments.service.PrCommentsService
import com.gyanoba.prcomments.service.loadedOrNull
import com.gyanoba.prcomments.ui.PrCommentsDataKeys
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.ShortcutSet
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import java.awt.event.KeyEvent
import javax.swing.KeyStroke

/** Everything here is [DumbAwareAction]: none of it needs indexes (guardrail §14.3). */

class RefreshAction : DumbAwareAction(
    PrCommentsBundle.lazyMessage("action.refresh.text"),
    PrCommentsBundle.lazyMessage("action.refresh.description"),
    AllIcons.Actions.Refresh,
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        PrCommentsService.getInstance(project).refresh(force = true)
    }
}

class OpenInBrowserAction : DumbAwareAction(
    PrCommentsBundle.lazyMessage("action.openInBrowser.text"),
    PrCommentsBundle.lazyMessage("action.openInBrowser.description"),
    AllIcons.General.Web,
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = urlFor(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        urlFor(e)?.let(BrowserUtil::browse)
    }

    private fun urlFor(e: AnActionEvent): String? {
        e.getData(PrCommentsDataKeys.SELECTED_THREAD)?.let { return it.root.htmlUrl }
        val project = e.project ?: return null
        return PrCommentsService.getInstance(project).state.value.loadedOrNull?.data?.pullRequest?.url
    }
}

/**
 * Resolve/unresolve the selected thread. Bound to a keystroke so a reviewer can work through a PR
 * without reaching for the mouse (§12.3).
 */
class ToggleResolveAction : DumbAwareAction(
    PrCommentsBundle.lazyMessage("action.toggleResolve.resolve"),
    PrCommentsBundle.lazyMessage("action.toggleResolve.description"),
    AllIcons.Actions.Checked,
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val thread = selectedThread(e)
        e.presentation.isEnabled = thread != null && e.project != null
        e.presentation.text = PrCommentsBundle.message(
            if (thread?.isResolved == true) "action.toggleResolve.unresolve" else "action.toggleResolve.resolve"
        )
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val thread = selectedThread(e) ?: return
        PrCommentsService.getInstance(project).setResolved(thread.nodeId, !thread.isResolved)
    }

    private fun selectedThread(e: AnActionEvent): ReviewThread? = e.getData(PrCommentsDataKeys.SELECTED_THREAD)
}

class SetPrNumberAction : DumbAwareAction(
    PrCommentsBundle.lazyMessage("action.setPrNumber.text"),
    PrCommentsBundle.lazyMessage("action.setPrNumber.text"),
    AllIcons.General.Filter,
) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(PrCommentsDataKeys.PANEL) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        e.getData(PrCommentsDataKeys.PANEL)?.promptForPrNumber()
    }
}

/** F5 while the tool window has focus. Registered on the component, never in the global keymap. */
val REFRESH_SHORTCUT: ShortcutSet = CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0))

class SettingsAction : DumbAwareAction(
    PrCommentsBundle.lazyMessage("action.settings.text"),
    PrCommentsBundle.lazyMessage("action.settings.text"),
    AllIcons.General.Settings,
) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let(::openSettings)
    }

    companion object {
        fun openSettings(project: Project) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, PrCommentsConfigurable::class.java)
        }
    }
}
