package com.gyanoba.prcomments.ui

import com.gyanoba.prcomments.service.PrCommentsService
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.content.ContentFactory
import javax.swing.JPanel

/**
 * Creates the tool window content and ties auto-refresh to its visibility, so a hidden tool window
 * spends no rate limit (§13.2).
 */
class PrCommentsToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun shouldBeAvailable(project: Project): Boolean = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val service = PrCommentsService.getInstance(project)
        // The Content is created first so it can act as the panel's disposable: everything the panel
        // builds — editors, listeners, alarms, flow collectors — hangs off it (guardrail §14.2).
        val content = ContentFactory.getInstance().createContent(JPanel(), null, false)
        content.component = PrCommentsPanel(project, content)
        content.isCloseable = false
        toolWindow.contentManager.addContent(content)

        project.messageBus.connect(content).subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun stateChanged(toolWindowManager: ToolWindowManager) {
                    val window = toolWindowManager.getToolWindow(ID) ?: return
                    service.setToolWindowVisible(window.isVisible)
                }
            },
        )

        Disposer.register(content) { service.setToolWindowVisible(false) }
        service.setToolWindowVisible(toolWindow.isVisible)
    }

    companion object {
        const val ID: String = "PR Comments"
    }
}
