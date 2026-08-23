package com.gyanoba.prcomments.ui

import com.gyanoba.prcomments.PrCommentsBundle
import com.gyanoba.prcomments.actions.CopySelectedForAiAction
import com.gyanoba.prcomments.actions.CopySelectedLinksAction
import com.gyanoba.prcomments.actions.OpenInBrowserAction
import com.gyanoba.prcomments.actions.OpenSelectedInBrowserAction
import com.gyanoba.prcomments.actions.REFRESH_SHORTCUT
import com.gyanoba.prcomments.actions.RefreshAction
import com.gyanoba.prcomments.actions.ResolveSelectedThreadsAction
import com.gyanoba.prcomments.actions.SetPrNumberAction
import com.gyanoba.prcomments.actions.SettingsAction
import com.gyanoba.prcomments.actions.UnresolveSelectedThreadsAction
import com.gyanoba.prcomments.model.ReviewThread
import com.gyanoba.prcomments.model.ThreadQuery
import com.gyanoba.prcomments.service.PrCommentsService
import com.gyanoba.prcomments.service.ViewState
import com.gyanoba.prcomments.service.loadedOrNull
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.Wrapper
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.components.BorderLayoutPanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Root of the tool window (§9.1): header, filter bar, and the list/detail splitter.
 *
 * All rendering happens on the EDT; the panel only ever reads state that the service has already
 * assembled off the EDT.
 */
class PrCommentsPanel(
    private val project: Project,
    private val parentDisposable: Disposable,
) : BorderLayoutPanel(), UiDataProvider {

    private val log = thisLogger()
    private val service = PrCommentsService.getInstance(project)

    private val headerLabel = JBLabel().apply { font = JBUI.Fonts.label().asBold() }
    private val headerDetail = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }

    private val detailPanel = ThreadDetailPanel(project, service, parentDisposable, ::navigateTo)

    private val listPanel = ThreadListPanel(
        parentDisposable = parentDisposable,
        onSelectionChanged = ::onSelectionChanged,
        onActivate = ::navigateTo,
        onClearFilters = { service.clearFilters() },
    )

    private val filterToolbar = FilterToolbar(
        parentDisposable = parentDisposable,
        filterProvider = { service.filter.value },
        sortProvider = { service.sort.value },
        authorsProvider = { ThreadQuery.authorsOf(service.state.value.loadedOrNull?.threads.orEmpty()) },
        onFilterChange = { transform -> service.updateFilter(transform) },
        onSortChange = { transform -> service.updateSort(transform) },
    )

    private val emptyStatePanel = EmptyStatePanel(
        onRefresh = { service.refresh(force = true) },
        onSetPrNumber = ::promptForPrNumber,
        onOpenSettings = { SettingsAction.openSettings(project) },
        onChoosePr = { candidate ->
            val branch = (service.state.value as? ViewState.ChoosePr)?.branch
            service.pinPullRequest(branch, candidate.number)
        },
    )

    private val splitter = OnePixelSplitter(false, SPLITTER_KEY, 0.4f).apply {
        firstComponent = listPanel
        secondComponent = detailPanel
    }

    private val mainPanel = BorderLayoutPanel().apply {
        addToTop(filterToolbar)
        addToCenter(splitter)
    }

    /** Swaps between [mainPanel] and [emptyStatePanel]; BorderLayout cannot do this by itself. */
    private val centerSlot = Wrapper(mainPanel)

    init {
        addToTop(header())
        addToCenter(centerSlot)
        installContextMenu()
        collectState()
        filterToolbar.syncFrom(service.filter.value)
    }

    override fun uiDataSnapshot(sink: DataSink) {
        sink[PrCommentsDataKeys.PANEL] = this
        listPanel.selected?.let { sink[PrCommentsDataKeys.SELECTED_THREAD] = it }
        sink[PrCommentsDataKeys.SELECTED_THREADS] = listPanel.selectedThreads
    }

    private fun header(): JPanel = BorderLayoutPanel().apply {
        border = JBUI.Borders.empty(4, 8)
        addToCenter(
            BorderLayoutPanel().apply {
                isOpaque = false
                addToTop(headerLabel)
                addToBottom(headerDetail)
            }
        )
        val refresh = RefreshAction()
        // AnAction.shortcutSet is @ApiStatus.Internal; registering against the component is the
        // public equivalent, and scopes F5 to this tool window instead of the global keymap.
        refresh.registerCustomShortcutSet(REFRESH_SHORTCUT, this@PrCommentsPanel, parentDisposable)
        val group = DefaultActionGroup(
            refresh,
            OpenInBrowserAction(),
            SetPrNumberAction(),
            SettingsAction(),
        )
        val toolbar = ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.TOOLWINDOW_TITLE, group, true)
        toolbar.targetComponent = this@PrCommentsPanel
        addToRight(toolbar.component)
    }

    private fun installContextMenu() {
        val copyForAi = CopySelectedForAiAction()
        // Ctrl/Cmd+C has no other meaning on this list (nothing renders as copyable text), so it's
        // free to double as the shortcut for the AI-prompt copy (§multi-select).
        copyForAi.registerCustomShortcutSet(CommonShortcuts.getCopy(), listPanel.list, parentDisposable)
        val group = DefaultActionGroup(
            ResolveSelectedThreadsAction(),
            UnresolveSelectedThreadsAction(),
            Separator.getInstance(),
            OpenSelectedInBrowserAction(),
            CopySelectedLinksAction(),
            Separator.getInstance(),
            copyForAi,
        )
        PopupHandler.installPopupMenu(listPanel.list, group, ActionPlaces.TOOLWINDOW_POPUP)
    }

    /** One collector for view state, filter and sort, so any of them re-renders the list. */
    private fun collectState() {
        val job = service.scope.launch(Dispatchers.EDT) {
            combine(service.state, service.filter, service.sort) { state, filter, sort ->
                Triple(state, filter, sort)
            }.collect { (state, _, _) ->
                render(state)
            }
        }
        val busyJob = service.scope.launch(Dispatchers.EDT) {
            service.busyThreads.collect { busy ->
                listPanel.selected?.let { detailPanel.setBusy(it.nodeId, it.nodeId in busy) }
            }
        }
        Disposer.register(parentDisposable) {
            job.cancel()
            busyJob.cancel()
        }
    }

    private fun render(state: ViewState) {
        val loaded = state.loadedOrNull
        renderHeader(state, loaded)

        val emptyMessage = emptyMessageFor(state)
        if (loaded == null && emptyMessage != null) {
            emptyStatePanel.show(emptyMessage, state)
            showInCenter(emptyStatePanel)
            return
        }
        showInCenter(mainPanel)

        if (loaded == null) {
            listPanel.setThreads(emptyList(), emptySet(), filtersActive = false, hasAnyThread = false)
            return
        }

        val previousSelection = listPanel.selected?.nodeId
        val previousIndex = previousSelection?.let { listPanel.indexOf(it) } ?: -1
        val visible = ThreadQuery.apply(loaded.threads, service.filter.value, service.sort.value, loaded.viewerLogin)
        listPanel.setThreads(
            threads = visible,
            newIds = loaded.newThreadIds,
            filtersActive = !service.filter.value.isEmpty,
            hasAnyThread = loaded.threads.isNotEmpty(),
        )

        val stillVisible = previousSelection != null && visible.any { it.nodeId == previousSelection }
        if (previousSelection != null && !stillVisible && previousIndex >= 0) {
            // The selected thread was filtered out — typically it was just resolved (§12.3).
            listPanel.selectNear(previousIndex)
        } else if (stillVisible) {
            visible.firstOrNull { it.nodeId == previousSelection }?.let(detailPanel::refreshInPlace)
        }
        filterToolbar.syncFrom(service.filter.value)
        revalidate()
        repaint()
    }

    private fun showInCenter(component: JComponent) {
        if (centerSlot.targetComponent === component) return
        centerSlot.setContent(component)
        centerSlot.revalidate()
        centerSlot.repaint()
    }

    private fun renderHeader(state: ViewState, loaded: ViewState.Loaded?) {
        when {
            loaded != null -> {
                val pr = loaded.data.pullRequest
                headerLabel.text = PrCommentsBundle.message(
                    "header.title", pr.ref.repo.slug, pr.ref.number, pr.title,
                )
                headerDetail.text = PrCommentsBundle.message(
                    "header.fetchedAt",
                    DateFormatUtil.formatPrettyDateTime(loaded.data.fetchedAt.toEpochMilli()),
                )
            }

            state is ViewState.Loading -> {
                headerLabel.text = PrCommentsBundle.message("header.loading")
                headerDetail.text = ""
            }

            else -> {
                headerLabel.text = ""
                headerDetail.text = ""
            }
        }
    }

    private fun emptyMessageFor(state: ViewState): String? = when (state) {
        is ViewState.NoRepo -> PrCommentsBundle.message("header.noRepo")
        is ViewState.NoToken -> PrCommentsBundle.message("header.noToken")
        is ViewState.DetachedHead -> PrCommentsBundle.message("header.detachedHead")
        is ViewState.NoPr -> PrCommentsBundle.message("header.noPr", state.branch)
        is ViewState.ChoosePr -> PrCommentsBundle.message("dialog.choosePr.message")
        is ViewState.Error -> state.error.displayMessage
        is ViewState.Loading -> PrCommentsBundle.message("header.loading")
        is ViewState.Idle -> PrCommentsBundle.message("header.loading")
        is ViewState.Loaded -> null
    }

    private fun onSelectionChanged(threads: List<ReviewThread>) {
        when (threads.size) {
            0 -> detailPanel.show(null)
            1 -> {
                val thread = threads.single()
                detailPanel.show(thread)
                service.markThreadSeen(thread.nodeId)
            }

            else -> detailPanel.showMultiSelected(threads.size)
        }
    }

    /** F9: jump the editor to the drift-corrected location of the commented line. */
    private fun navigateTo(thread: ReviewThread) {
        service.scope.launch {
            val target = try {
                val repo = service.detectedRepo() ?: return@launch
                val loaded = service.state.value.loadedOrNull ?: return@launch
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
                log.debug("Navigation mapping failed", e)
                null
            }
            withContext(Dispatchers.EDT) {
                val file = target?.file
                val line = target?.line
                if (target != null && target.isNavigable && file != null && line != null) {
                    OpenFileDescriptor(project, file, line, 0).navigate(true)
                } else {
                    // Nothing trustworthy to navigate to; GitHub still has the original context.
                    com.intellij.ide.BrowserUtil.browse(thread.root.htmlUrl)
                }
            }
        }
    }

    fun promptForPrNumber() {
        val state = service.state.value
        val branch = when (state) {
            is ViewState.NoPr -> state.branch
            is ViewState.ChoosePr -> state.branch
            is ViewState.Loaded -> state.branch
            else -> ""
        }
        val entered = com.intellij.openapi.ui.Messages.showInputDialog(
            project,
            PrCommentsBundle.message("dialog.setPrNumber.message", branch),
            PrCommentsBundle.message("dialog.setPrNumber.title"),
            null,
        ) ?: return
        val number = entered.trim().removePrefix("#").toIntOrNull()
        if (number == null || number <= 0) {
            com.intellij.openapi.ui.Messages.showErrorDialog(
                project,
                PrCommentsBundle.message("dialog.setPrNumber.invalid"),
                PrCommentsBundle.message("dialog.setPrNumber.title"),
            )
            return
        }
        service.pinPullRequest(branch.ifEmpty { null }, number)
    }

    companion object {
        private const val SPLITTER_KEY = "PrComments.splitter"
    }
}
