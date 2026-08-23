package com.gyanoba.prcomments.ui

import com.gyanoba.prcomments.PrCommentsBundle
import com.gyanoba.prcomments.model.ReviewThread
import com.intellij.openapi.Disposable
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.util.ui.components.BorderLayoutPanel
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel

/**
 * The left half of the tool window: the filtered, sorted list of review threads (§9.2).
 *
 * Re-populating preserves the selected thread by node id and the scroll position, which is what
 * makes auto-refresh unobtrusive (§13.3).
 */
class ThreadListPanel(
    parentDisposable: Disposable,
    private val onSelect: (ReviewThread?) -> Unit,
    private val onActivate: (ReviewThread) -> Unit,
    private val onClearFilters: () -> Unit,
) : BorderLayoutPanel() {

    private val model = CollectionListModel<ReviewThread>()
    private var newThreadIds: Set<String> = emptySet()
    private var suppressSelectionEvents = false

    val list: JBList<ReviewThread> = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = ThreadListCellRenderer { newThreadIds }
        addListSelectionListener { event ->
            if (!event.valueIsAdjusting && !suppressSelectionEvents) onSelect(selectedValue)
        }
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) selectedValue?.let(onActivate)
            }
        })
        registerKeyboardAction(
            { selectedValue?.let(onActivate) },
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
            WHEN_ANCESTOR_OF_FOCUSED_COMPONENT,
        )
    }

    init {
        addToCenter(ScrollPaneFactory.createScrollPane(list, true))
        emptyText(PrCommentsBundle.message("list.empty.noThreads"), withClearLink = false)
        com.intellij.openapi.util.Disposer.register(parentDisposable) { model.removeAll() }
    }

    val selected: ReviewThread? get() = list.selectedValue

    /** Replaces the contents, keeping the selected node id and the scroll offset where possible. */
    fun setThreads(threads: List<ReviewThread>, newIds: Set<String>, filtersActive: Boolean, hasAnyThread: Boolean) {
        newThreadIds = newIds
        val previouslySelected = list.selectedValue?.nodeId
        val scroll = (list.parent as? javax.swing.JViewport)?.viewPosition

        suppressSelectionEvents = true
        try {
            model.replaceAll(threads)
            val index = threads.indexOfFirst { it.nodeId == previouslySelected }
            if (index >= 0) list.selectedIndex = index else list.clearSelection()
        } finally {
            suppressSelectionEvents = false
        }

        scroll?.let { position ->
            (list.parent as? javax.swing.JViewport)?.let { viewport ->
                val maxY = (viewport.view.height - viewport.height).coerceAtLeast(0)
                position.y = position.y.coerceAtMost(maxY)
                viewport.viewPosition = position
            }
        }

        emptyText(
            when {
                !hasAnyThread -> PrCommentsBundle.message("list.empty.noThreads")
                else -> PrCommentsBundle.message("list.empty.filtered")
            },
            withClearLink = hasAnyThread && filtersActive,
        )

        // The selection may have vanished with the thread it pointed at.
        if (list.selectedValue?.nodeId != previouslySelected) onSelect(list.selectedValue)
    }

    /**
     * After a thread leaves the list (typically because it was resolved while the Unresolved filter
     * is on), keep the user moving by selecting whatever took its place (§12.3).
     */
    fun selectNear(removedIndex: Int) {
        if (model.isEmpty) return
        val index = removedIndex.coerceIn(0, model.size - 1)
        list.selectedIndex = index
        list.ensureIndexIsVisible(index)
    }

    fun indexOf(nodeId: String): Int = model.items.indexOfFirst { it.nodeId == nodeId }

    private fun emptyText(message: String, withClearLink: Boolean) {
        list.emptyText.clear()
        list.emptyText.text = message
        if (withClearLink) {
            list.emptyText.appendLine(
                PrCommentsBundle.message("filter.clear"),
                SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES,
            ) { onClearFilters() }
        }
    }
}
