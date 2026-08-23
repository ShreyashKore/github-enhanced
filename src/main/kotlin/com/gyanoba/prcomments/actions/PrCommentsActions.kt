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
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import java.awt.datatransfer.StringSelection
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

private fun selectedThreads(e: AnActionEvent): List<ReviewThread> =
    e.getData(PrCommentsDataKeys.SELECTED_THREADS).orEmpty()

/** Resolves every unresolved thread in the selection (§multi-select). */
class ResolveSelectedThreadsAction : DumbAwareAction(
    PrCommentsBundle.lazyMessage("action.toggleResolve.resolve"),
    PrCommentsBundle.lazyMessage("action.resolveSelected.description"),
    AllIcons.Actions.Checked,
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val threads = selectedThreads(e)
        e.presentation.isEnabled = e.project != null && threads.any { !it.isResolved }
        e.presentation.text = if (threads.size <= 1) {
            PrCommentsBundle.message("action.toggleResolve.resolve")
        } else {
            PrCommentsBundle.message("action.resolveSelected.text", threads.size)
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = PrCommentsService.getInstance(project)
        selectedThreads(e).filterNot { it.isResolved }.forEach { service.setResolved(it.nodeId, true) }
    }
}

/** Unresolves every resolved thread in the selection (§multi-select). */
class UnresolveSelectedThreadsAction : DumbAwareAction(
    PrCommentsBundle.lazyMessage("action.toggleResolve.unresolve"),
    PrCommentsBundle.lazyMessage("action.unresolveSelected.description"),
    AllIcons.Actions.Rollback,
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val threads = selectedThreads(e)
        e.presentation.isEnabled = e.project != null && threads.any { it.isResolved }
        e.presentation.text = if (threads.size <= 1) {
            PrCommentsBundle.message("action.toggleResolve.unresolve")
        } else {
            PrCommentsBundle.message("action.unresolveSelected.text", threads.size)
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = PrCommentsService.getInstance(project)
        selectedThreads(e).filter { it.isResolved }.forEach { service.setResolved(it.nodeId, false) }
    }
}

/** Opens every selected thread's comment on github.com, one browser tab each (§multi-select). */
class OpenSelectedInBrowserAction : DumbAwareAction(
    PrCommentsBundle.lazyMessage("action.openInBrowser.text"),
    PrCommentsBundle.lazyMessage("action.openSelectedInBrowser.description"),
    AllIcons.General.Web,
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val threads = selectedThreads(e)
        e.presentation.isEnabled = threads.isNotEmpty()
        e.presentation.text = if (threads.size <= 1) {
            PrCommentsBundle.message("action.openInBrowser.text")
        } else {
            PrCommentsBundle.message("action.openSelectedInBrowser.text", threads.size)
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        selectedThreads(e).forEach { BrowserUtil.browse(it.root.htmlUrl) }
    }
}

/** Copies the GitHub URL of each selected thread, one per line (§multi-select). */
class CopySelectedLinksAction : DumbAwareAction(
    PrCommentsBundle.lazyMessage("action.copyLinks.text"),
    PrCommentsBundle.lazyMessage("action.copyLinks.description"),
    AllIcons.Actions.Copy,
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = selectedThreads(e).isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val links = selectedThreads(e).joinToString("\n") { it.root.htmlUrl }
        CopyPasteManager.getInstance().setContents(StringSelection(links))
    }
}

/**
 * Copies the selected thread(s) as a compact prompt an AI coding assistant can act on directly —
 * see [AiPromptFormatter] for the format (§multi-select).
 */
class CopySelectedForAiAction : DumbAwareAction(
    PrCommentsBundle.lazyMessage("action.copyForAi.text"),
    PrCommentsBundle.lazyMessage("action.copyForAi.description"),
    AllIcons.Actions.Copy,
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = selectedThreads(e).isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val threads = selectedThreads(e)
        if (threads.isEmpty()) return
        CopyPasteManager.getInstance().setContents(StringSelection(AiPromptFormatter.format(threads)))
    }
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
