package com.gyanoba.prcomments.ui

import com.gyanoba.prcomments.PrCommentsBundle
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import java.awt.FlowLayout
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingConstants

/**
 * Reply box with the two submit buttons from §12.3. Uses the Markdown file type when the Markdown
 * plugin happens to be installed, but never depends on it.
 */
class ReplyEditor(
    project: Project,
    private val onDraftChanged: (String) -> Unit,
    private val onSubmit: (text: String, alsoResolve: Boolean) -> Unit,
) : BorderLayoutPanel() {

    private var updatingProgrammatically = false

    private val editor: EditorTextField = EditorTextField(
        EditorFactory.getInstance().createDocument(""),
        project,
        markdownFileTypeOrPlainText(),
        false,
        false,
    ).apply {
        setPlaceholder(PrCommentsBundle.message("detail.reply.placeholder"))
        setOneLineMode(false)
        addSettingsProvider { editorEx ->
            editorEx.settings.apply {
                isLineNumbersShown = false
                isLineMarkerAreaShown = false
                isFoldingOutlineShown = false
                isRightMarginShown = false
                isUseSoftWraps = true
                additionalLinesCount = 0
                additionalColumnsCount = 0
            }
            editorEx.setVerticalScrollbarVisible(true)
        }
        addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (!updatingProgrammatically) onDraftChanged(text)
                updateEnabled()
            }
        })
    }

    private val replyButton = JButton(PrCommentsBundle.message("detail.reply.submit")).apply {
        addActionListener { submit(alsoResolve = false) }
    }

    private val replyAndResolveButton = JButton(PrCommentsBundle.message("detail.reply.submitAndResolve")).apply {
        addActionListener { submit(alsoResolve = true) }
    }

    private val hint = JLabel(PrCommentsBundle.message("detail.reply.hint"), SwingConstants.LEFT).apply {
        foreground = com.intellij.util.ui.UIUtil.getContextHelpForeground()
    }

    private var busy = false

    init {
        border = JBUI.Borders.emptyTop(6)
        editor.preferredSize = JBUI.size(0, REPLY_HEIGHT)
        addToCenter(editor)
        addToBottom(
            JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), JBUI.scale(4))).apply {
                isOpaque = false
                add(hint)
                add(replyAndResolveButton)
                add(replyButton)
            }
        )

        // Registered on this component only — never in the global keymap (§12.1).
        object : DumbAwareAction() {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun actionPerformed(e: AnActionEvent) = submit(alsoResolve = false)
        }.registerCustomShortcutSet(SUBMIT_SHORTCUT, this)

        updateEnabled()
    }

    var text: String
        get() = editor.text
        set(value) {
            if (editor.text == value) return
            updatingProgrammatically = true
            try {
                editor.text = value
            } finally {
                updatingProgrammatically = false
            }
            updateEnabled()
        }

    fun setBusy(value: Boolean) {
        busy = value
        hint.text = if (value) {
            PrCommentsBundle.message("detail.reply.sending")
        } else {
            PrCommentsBundle.message("detail.reply.hint")
        }
        updateEnabled()
    }

    private fun submit(alsoResolve: Boolean) {
        val body = editor.text.trim()
        if (body.isEmpty() || busy) return
        onSubmit(body, alsoResolve)
    }

    private fun updateEnabled() {
        val enabled = !busy && editor.text.isNotBlank()
        replyButton.isEnabled = enabled
        replyAndResolveButton.isEnabled = enabled
        editor.isEnabled = !busy
    }

    companion object {
        private const val REPLY_HEIGHT = 72

        private val SUBMIT_SHORTCUT = CustomShortcutSet(
            KeyboardShortcut(
                KeyStroke.getKeyStroke(
                    KeyEvent.VK_ENTER,
                    InputEvent.CTRL_DOWN_MASK,
                ),
                null,
            ),
            KeyboardShortcut(
                KeyStroke.getKeyStroke(
                    KeyEvent.VK_ENTER,
                    InputEvent.META_DOWN_MASK,
                ),
                null,
            ),
        )

        private fun markdownFileTypeOrPlainText() =
            FileTypeManager.getInstance().findFileTypeByName("Markdown") ?: PlainTextFileType.INSTANCE
    }
}
