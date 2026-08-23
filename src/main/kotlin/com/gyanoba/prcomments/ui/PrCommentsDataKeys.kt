package com.gyanoba.prcomments.ui

import com.gyanoba.prcomments.model.ReviewThread
import com.intellij.openapi.actionSystem.DataKey

/** Data the tool window exposes to actions in its toolbar and context menu. */
object PrCommentsDataKeys {
    @JvmField
    val SELECTED_THREAD: DataKey<ReviewThread> = DataKey.create("PrComments.selectedThread")

    /** Every row currently selected in the list — empty, one, or many (§multi-select). */
    @JvmField
    val SELECTED_THREADS: DataKey<List<ReviewThread>> = DataKey.create("PrComments.selectedThreads")

    @JvmField
    val PANEL: DataKey<PrCommentsPanel> = DataKey.create("PrComments.panel")
}
