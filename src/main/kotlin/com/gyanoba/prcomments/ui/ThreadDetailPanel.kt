package com.gyanoba.prcomments.ui

import com.gyanoba.prcomments.PrCommentsBundle
import com.gyanoba.prcomments.model.DiffSide
import com.gyanoba.prcomments.model.ReviewComment
import com.gyanoba.prcomments.model.ReviewThread
import com.gyanoba.prcomments.service.PrCommentsService
import com.gyanoba.prcomments.service.loadedOrNull
import com.gyanoba.prcomments.vcs.CurrentLineState
import com.gyanoba.prcomments.vcs.DiffHunkParser
import com.gyanoba.prcomments.vcs.HunkLineType
import com.gyanoba.prcomments.vcs.MappedLineState
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.components.BorderLayoutPanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Rectangle
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.SwingConstants

/**
 * The right half of the tool window: full thread, diff-hunk snapshot, current state of the line and
 * the reply box (§9.1, §10, §11.3, §12).
 */
class ThreadDetailPanel(
    private val project: Project,
    private val service: PrCommentsService,
    private val parentDisposable: Disposable,
    private val onNavigate: (ReviewThread) -> Unit,
) : BorderLayoutPanel() {

    private val log = thisLogger()

    private val titleLabel = JBLabel().apply { font = JBUI.Fonts.label().asBold() }
    private val subtitleLabel = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }

    private val resolveButton = JButton().apply {
        addActionListener { thread?.let { service.setResolved(it.nodeId, !it.isResolved) } }
    }
    private val goToLineButton = JButton(PrCommentsBundle.message("detail.goToLine")).apply {
        addActionListener { thread?.let(onNavigate) }
    }
    private val openInBrowserButton = JButton(PrCommentsBundle.message("action.openInBrowser.text")).apply {
        addActionListener { thread?.let { BrowserUtil.browse(it.root.htmlUrl) } }
    }

    private val snapshotTitle = sectionTitle(PrCommentsBundle.message("detail.snapshot.title"))
    private val snapshotPreview = CodePreview(project)
    private val snapshotUnavailable = JBLabel(PrCommentsBundle.message("detail.snapshot.unavailable")).apply {
        foreground = UIUtil.getContextHelpForeground()
        isVisible = false
    }

    private val currentTitle = sectionTitle(PrCommentsBundle.message("detail.current.title"))
    private val currentChip = JBLabel()
    private val currentPreview = CodePreview(project)
    private val currentUnavailable = JBLabel(PrCommentsBundle.message("detail.current.unavailable")).apply {
        foreground = UIUtil.getContextHelpForeground()
        isVisible = false
    }

    private val commentsPanel = JPanel(VerticalLayout(JBUI.scale(8), VerticalLayout.FILL)).apply { isOpaque = false }

    private val replyEditor = ReplyEditor(
        project = project,
        onDraftChanged = { text -> thread?.let { service.setDraft(it.nodeId, text) } },
        onSubmit = { text, alsoResolve -> submitReply(text, alsoResolve) },
    )

    private val emptyLabel = JBLabel(PrCommentsBundle.message("detail.empty"), SwingConstants.CENTER).apply {
        foreground = UIUtil.getContextHelpForeground()
    }

    private val content = ScrollableVerticalPanel().apply {
        border = JBUI.Borders.empty(8)
        add(snapshotTitle)
        add(snapshotPreview)
        add(snapshotUnavailable)
        add(currentTitle)
        add(currentChip)
        add(currentPreview)
        add(currentUnavailable)
        add(commentsPanel)
    }

    private val body = BorderLayoutPanel().apply {
        addToTop(header())
        addToCenter(ScrollPaneFactory.createScrollPane(content, true))
        addToBottom(replyEditor)
        isVisible = false
    }

    private var thread: ReviewThread? = null
    private var currentStateJob: Job? = null

    init {
        addToCenter(body)
        addToTop(emptyLabel)
        Disposer.register(parentDisposable) {
            currentStateJob?.cancel()
            snapshotPreview.clear()
            currentPreview.clear()
        }
    }

    private fun header() = BorderLayoutPanel().apply {
        border = JBUI.Borders.empty(6, 8)
        addToCenter(
            JPanel(VerticalLayout(JBUI.scale(2))).apply {
                isOpaque = false
                add(titleLabel)
                add(subtitleLabel)
            }
        )
        addToRight(
            JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
                isOpaque = false
                add(openInBrowserButton)
                add(goToLineButton)
                add(resolveButton)
            }
        )
    }

    /** Renders [newThread], or clears the pane when it is null. */
    fun show(newThread: ReviewThread?) {
        val previousId = thread?.nodeId
        thread = newThread
        currentStateJob?.cancel()
        currentStateJob = null

        if (newThread == null) {
            body.isVisible = false
            emptyLabel.isVisible = true
            snapshotPreview.clear()
            currentPreview.clear()
            commentsPanel.removeAll()
            revalidate()
            repaint()
            return
        }

        emptyLabel.isVisible = false
        body.isVisible = true

        renderHeader(newThread)
        renderComments(newThread)
        if (previousId != newThread.nodeId) {
            renderSnapshot(newThread)
            replyEditor.text = service.draftFor(newThread.nodeId)
            content.scrollRectToVisible(Rectangle(0, 0, 1, 1))
            loadCurrentState(newThread)
        }
        replyEditor.setBusy(newThread.nodeId in service.busyThreads.value)

        revalidate()
        repaint()
    }

    /** Re-renders only the parts that a mutation can change, without disturbing the reply draft. */
    fun refreshInPlace(updated: ReviewThread) {
        if (thread?.nodeId != updated.nodeId) return
        thread = updated
        renderHeader(updated)
        renderComments(updated)
        replyEditor.setBusy(updated.nodeId in service.busyThreads.value)
        revalidate()
        repaint()
    }

    private fun renderHeader(thread: ReviewThread) {
        titleLabel.text = thread.path + (thread.displayLine?.let { ":$it" } ?: "")
        subtitleLabel.text = buildString {
            append("@").append(thread.root.authorLogin)
            append(" · ").append(DateFormatUtil.formatPrettyDateTime(thread.createdAt.toEpochMilli()))
            if (thread.isOutdated) append(" · ").append(PrCommentsBundle.message("list.row.outdated"))
            thread.resolvedByLogin?.let { append(" · ").append(PrCommentsBundle.message("detail.resolvedBy", it)) }
        }
        resolveButton.text = PrCommentsBundle.message(
            if (thread.isResolved) "action.toggleResolve.unresolve" else "action.toggleResolve.resolve"
        )
        resolveButton.isEnabled = thread.nodeId !in service.busyThreads.value
    }

    private fun renderComments(thread: ReviewThread) {
        commentsPanel.removeAll()
        thread.comments.forEach { commentsPanel.add(commentComponent(it)) }
    }

    private fun commentComponent(comment: ReviewComment): JComponent =
        JPanel(VerticalLayout(JBUI.scale(2), VerticalLayout.FILL)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(4)
            val when0 = if (comment.isPending) {
                PrCommentsBundle.message("detail.comment.pending")
            } else {
                DateFormatUtil.formatPrettyDateTime(comment.createdAt.toEpochMilli())
            }
            add(
                JBLabel(PrCommentsBundle.message("detail.comment.header", comment.authorLogin, when0)).apply {
                    foreground = UIUtil.getContextHelpForeground()
                }
            )
            add(HtmlBodyPane().apply { setMarkdown(comment.bodyMarkdown) })
        }

    /** §10: the diff hunk exactly as the code looked when the comment was written. */
    private fun renderSnapshot(thread: ReviewThread) {
        val parsed = DiffHunkParser.parse(thread.root.diffHunk)
        if (parsed == null) {
            snapshotPreview.clear()
            snapshotPreview.isVisible = false
            snapshotUnavailable.isVisible = true
            return
        }
        snapshotPreview.isVisible = true
        snapshotUnavailable.isVisible = false

        val kinds = parsed.lines.map {
            when (it.type) {
                HunkLineType.ADDED -> PreviewLineKind.ADDED
                HunkLineType.REMOVED -> PreviewLineKind.REMOVED
                HunkLineType.CONTEXT -> PreviewLineKind.NORMAL
            }
        }
        // The hunk belongs to the commit that was reviewed, so `originalLine` — not `currentLine`,
        // which is expressed against the PR head — is the line to point at inside it.
        val side = if (thread.diffSide == DiffSide.LEFT) DiffHunkParser.HunkSide.OLD else DiffHunkParser.HunkSide.NEW
        val commentedLine = thread.originalLine ?: thread.currentLine
        val focus = DiffHunkParser.indexOfLine(parsed, commentedLine, side)
        // A unified fragment interleaves both sides, so each row carries its own number.
        val gutterNumbers = parsed.lines.map { if (side == DiffHunkParser.HunkSide.OLD) it.oldLine else it.newLine }

        snapshotPreview.setContent(
            fileName = thread.path,
            text = parsed.sourceText,
            firstLineNumber = if (side == DiffHunkParser.HunkSide.OLD) parsed.oldStart else parsed.newStart,
            lineKinds = kinds,
            lineNumbers = gutterNumbers,
            focusLine = focus,
        )
    }

    /** §11: where that line is right now, with an honest state chip. */
    private fun loadCurrentState(thread: ReviewThread) {
        currentChip.text = ""
        goToLineButton.isEnabled = false
        currentPreview.clear()
        currentPreview.isVisible = false
        currentUnavailable.isVisible = false

        val loaded = service.state.value.loadedOrNull ?: return
        currentStateJob = service.scope.launch {
            val result = try {
                val repo = service.detectedRepo() ?: return@launch
                service.lineMapper.map(
                    thread = thread,
                    repoRoot = repo.root,
                    prHeadOid = loaded.data.pullRequest.headRefOid,
                    ref = loaded.data.pullRequest.ref,
                    api = service.api,
                )
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.debug("Could not map the current location of a thread", e)
                CurrentLineState.unknown(null)
            }
            withContext(Dispatchers.EDT) {
                if (this@ThreadDetailPanel.thread?.nodeId == thread.nodeId) applyCurrentState(thread, result)
            }
        }
    }

    private fun applyCurrentState(thread: ReviewThread, state: CurrentLineState) {
        val displayLine = state.line?.plus(1)
        val originalLine = state.originalLine?.plus(1) ?: thread.displayLine
        currentChip.text = when (state.state) {
            MappedLineState.EXACT -> PrCommentsBundle.message("detail.current.exact", displayLine ?: "?")
            MappedLineState.SHIFTED ->
                PrCommentsBundle.message("detail.current.shifted", originalLine ?: "?", displayLine ?: "?")

            MappedLineState.MODIFIED -> PrCommentsBundle.message("detail.current.modified", displayLine ?: "?")
            MappedLineState.DELETED -> PrCommentsBundle.message("detail.current.deleted")
            MappedLineState.FILE_DELETED -> PrCommentsBundle.message("detail.current.fileDeleted")
            MappedLineState.UNKNOWN -> PrCommentsBundle.message("detail.current.unknown")
        }
        currentChip.foreground = chipColor(state.state)

        val snippet = state.snippet
        if (snippet == null) {
            currentPreview.clear()
            currentPreview.isVisible = false
            currentUnavailable.isVisible = state.state != MappedLineState.FILE_DELETED
        } else {
            currentUnavailable.isVisible = false
            currentPreview.isVisible = true
            currentPreview.setContent(
                fileName = thread.path,
                text = snippet,
                firstLineNumber = state.snippetFirstLine + 1,
                focusLine = state.line?.minus(state.snippetFirstLine),
            )
        }
        // Never offer navigation we cannot honour: a removed line must not silently jump somewhere
        // plausible-looking (RISKS: "line mapping wrong on rebase-heavy branches").
        goToLineButton.isEnabled = state.isNavigable
        revalidate()
        repaint()
    }

    private fun submitReply(text: String, alsoResolve: Boolean) {
        val target = thread ?: return
        replyEditor.setBusy(true)
        replyEditor.text = ""
        service.reply(target, text, alsoResolve) { restored ->
            // Failure path: hand the draft back so nothing the user typed is lost (§12.2).
            if (thread?.nodeId == target.nodeId) {
                replyEditor.text = restored
                replyEditor.setBusy(false)
            }
        }
    }

    fun setBusy(threadId: String, busy: Boolean) {
        if (thread?.nodeId != threadId) return
        replyEditor.setBusy(busy)
        resolveButton.isEnabled = !busy
    }

    private fun sectionTitle(text: String) = JBLabel(text).apply {
        font = JBUI.Fonts.label().asBold()
        border = JBUI.Borders.empty(6, 0, 2, 0)
    }

    private fun chipColor(state: MappedLineState): Color = when (state) {
        MappedLineState.EXACT -> UIUtil.getContextHelpForeground()
        MappedLineState.SHIFTED -> SHIFTED_COLOR
        MappedLineState.MODIFIED -> MODIFIED_COLOR
        MappedLineState.DELETED, MappedLineState.FILE_DELETED -> DELETED_COLOR
        MappedLineState.UNKNOWN -> UIUtil.getContextHelpForeground()
    }

    /**
     * A vertical stack that fills the viewport width, so [HtmlBodyPane] wraps to the pane instead of
     * forcing a horizontal scrollbar.
     */
    private class ScrollableVerticalPanel :
        JPanel(VerticalLayout(JBUI.scale(4), VerticalLayout.FILL)), Scrollable {

        init {
            isOpaque = false
        }

        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(r: Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)
        override fun getScrollableBlockIncrement(r: Rectangle, orientation: Int, direction: Int) = r.height
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
    }

    companion object {
        private val SHIFTED_COLOR: Color = JBColor(Color(0x2E5FB5), Color(0x6D9DF0))
        private val MODIFIED_COLOR: Color = JBColor(Color(0xA05A00), Color(0xE0A64B))
        private val DELETED_COLOR: Color = JBColor(Color(0xB03030), Color(0xE06C75))
    }
}
