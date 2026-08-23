package com.gyanoba.prcomments.ui

import com.gyanoba.prcomments.PrCommentsBundle
import com.gyanoba.prcomments.model.ReplyState
import com.gyanoba.prcomments.model.SortKey
import com.gyanoba.prcomments.model.SortOrder
import com.gyanoba.prcomments.model.ThreadFilter
import com.gyanoba.prcomments.model.ThreadSort
import com.gyanoba.prcomments.model.TriState
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import javax.swing.JComponent
import javax.swing.event.DocumentEvent

/**
 * Native-looking filter bar (§9.3): an [ActionToolbar] of combo boxes and toggles, plus a debounced
 * [SearchTextField]. Every change is local — filtering never triggers a network call.
 */
class FilterToolbar(
    private val parentDisposable: Disposable,
    private val filterProvider: () -> ThreadFilter,
    private val sortProvider: () -> ThreadSort,
    private val authorsProvider: () -> List<String>,
    private val onFilterChange: ((ThreadFilter) -> ThreadFilter) -> Unit,
    private val onSortChange: ((ThreadSort) -> ThreadSort) -> Unit,
) : BorderLayoutPanel() {

    private val searchField = debouncedField("filter.search.hint") { query ->
        onFilterChange { it.copy(textQuery = query) }
    }

    private val pathField = debouncedField("filter.path.hint") { query ->
        onFilterChange { it.copy(pathQuery = query) }
    }

    /** Both text filters debounce by 200 ms so typing does not re-filter on every keystroke (§9.3). */
    private fun debouncedField(hintKey: String, onChange: (String) -> Unit) =
        SearchTextField(false).apply {
            // One alarm per field: typing in one must not cancel the other's pending update.
            val debounce = Alarm(Alarm.ThreadToUse.SWING_THREAD, parentDisposable)
            textEditor.emptyText.text = PrCommentsBundle.message(hintKey)
            addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) {
                    debounce.cancelAllRequests()
                    debounce.addRequest({ onChange(text.trim()) }, SEARCH_DEBOUNCE_MS)
                }
            })
        }

    private val toolbar: ActionToolbar = ActionManager.getInstance()
        .createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, buildActions(), true)
        .apply { targetComponent = this@FilterToolbar }

    init {
        border = JBUI.Borders.empty(2, 4)
        addToLeft(toolbar.component)
        addToCenter(
            javax.swing.JPanel(java.awt.GridLayout(1, 2, JBUI.scale(4), 0)).apply {
                isOpaque = false
                add(searchField)
                add(pathField)
            }
        )
    }

    /** Pushes externally restored state (e.g. from settings) into the widgets. */
    fun syncFrom(filter: ThreadFilter) {
        if (searchField.text != filter.textQuery) searchField.text = filter.textQuery
        if (pathField.text != filter.pathQuery) pathField.text = filter.pathQuery
        toolbar.updateActionsAsync()
    }

    private fun buildActions() = DefaultActionGroup().apply {
        add(ResolutionAction())
        add(ReplyStateAction())
        add(AuthorAction())
        add(OutdatedAction())
        addSeparator()
        add(SortAction())
        add(SortOrderAction())
    }

    // -- combo boxes ---------------------------------------------------------------------------

    private inner class ResolutionAction : ComboBoxAction(), DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.text = when (filterProvider().resolution) {
                TriState.ALL -> PrCommentsBundle.message("filter.resolution.all")
                TriState.RESOLVED -> PrCommentsBundle.message("filter.resolution.resolved")
                TriState.UNRESOLVED -> PrCommentsBundle.message("filter.resolution.unresolved")
            }
        }

        override fun createPopupActionGroup(button: JComponent, context: com.intellij.openapi.actionSystem.DataContext) =
            DefaultActionGroup(
                TriState.entries.map { value ->
                    choice(
                        when (value) {
                            TriState.ALL -> PrCommentsBundle.message("filter.resolution.all")
                            TriState.RESOLVED -> PrCommentsBundle.message("filter.resolution.resolved")
                            TriState.UNRESOLVED -> PrCommentsBundle.message("filter.resolution.unresolved")
                        },
                        selected = filterProvider().resolution == value,
                    ) { onFilterChange { current -> current.copy(resolution = value) } }
                }
            )
    }

    private inner class ReplyStateAction : ComboBoxAction(), DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.text = label(filterProvider().replyState)
        }

        override fun createPopupActionGroup(button: JComponent, context: com.intellij.openapi.actionSystem.DataContext) =
            DefaultActionGroup(
                ReplyState.entries.map { value ->
                    choice(label(value), selected = filterProvider().replyState == value) {
                        onFilterChange { current -> current.copy(replyState = value) }
                    }
                }
            )

        private fun label(state: ReplyState) = when (state) {
            ReplyState.ALL -> PrCommentsBundle.message("filter.replies.all")
            ReplyState.REPLIED -> PrCommentsBundle.message("filter.replies.replied")
            ReplyState.NOT_REPLIED -> PrCommentsBundle.message("filter.replies.notReplied")
            ReplyState.REPLIED_BY_ME -> PrCommentsBundle.message("filter.replies.repliedByMe")
            ReplyState.AWAITING_ME -> PrCommentsBundle.message("filter.replies.awaitingMe")
        }
    }

    private inner class AuthorAction : ComboBoxAction(), DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            val authors = filterProvider().authors
            e.presentation.text = when {
                authors.isEmpty() -> PrCommentsBundle.message("filter.author.all")
                authors.size == 1 -> PrCommentsBundle.message("filter.author.title") + ": " + authors.first()
                else -> PrCommentsBundle.message("filter.author.title") + ": " + authors.size
            }
        }

        override fun createPopupActionGroup(button: JComponent, context: com.intellij.openapi.actionSystem.DataContext) =
            DefaultActionGroup().apply {
                add(choice(PrCommentsBundle.message("filter.author.all"), filterProvider().authors.isEmpty()) {
                    onFilterChange { it.copy(authors = emptySet()) }
                })
                addSeparator()
                // Multiple authors compose with OR, so these are toggles rather than a radio group.
                authorsProvider().forEach { author ->
                    add(choice(author, author in filterProvider().authors) {
                        onFilterChange { current ->
                            val next = if (author in current.authors) current.authors - author
                            else current.authors + author
                            current.copy(authors = next)
                        }
                    })
                }
            }
    }

    private inner class SortAction : ComboBoxAction(), DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.text = PrCommentsBundle.message("filter.sort.title") + ": " + label(sortProvider().key)
        }

        override fun createPopupActionGroup(button: JComponent, context: com.intellij.openapi.actionSystem.DataContext) =
            DefaultActionGroup(
                SortKey.entries.map { key ->
                    choice(label(key), selected = sortProvider().key == key) {
                        onSortChange { it.copy(key = key) }
                    }
                }
            )

        private fun label(key: SortKey) = when (key) {
            SortKey.CREATED -> PrCommentsBundle.message("filter.sort.created")
            SortKey.LAST_ACTIVITY -> PrCommentsBundle.message("filter.sort.lastActivity")
            SortKey.FILE_PATH -> PrCommentsBundle.message("filter.sort.filePath")
            SortKey.LINE -> PrCommentsBundle.message("filter.sort.line")
        }
    }

    // -- toggles -------------------------------------------------------------------------------

    private inner class OutdatedAction : DumbAwareToggleAction(PrCommentsBundle.message("filter.outdated.show")) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent) = filterProvider().includeOutdated
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            onFilterChange { it.copy(includeOutdated = state) }
        }
    }

    private inner class SortOrderAction : DumbAwareAction() {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            val ascending = sortProvider().order == SortOrder.ASC
            e.presentation.icon = if (ascending) AllIcons.General.ArrowUp else AllIcons.General.ArrowDown
            e.presentation.text = PrCommentsBundle.message(
                if (ascending) "filter.sort.ascending" else "filter.sort.descending"
            )
        }

        override fun actionPerformed(e: AnActionEvent) {
            onSortChange {
                it.copy(order = if (it.order == SortOrder.ASC) SortOrder.DESC else SortOrder.ASC)
            }
        }
    }

    private fun choice(text: String, selected: Boolean, action: () -> Unit) =
        object : DumbAwareToggleAction(text) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent) = selected
            override fun setSelected(e: AnActionEvent, state: Boolean) = action()
        }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 200
    }
}
