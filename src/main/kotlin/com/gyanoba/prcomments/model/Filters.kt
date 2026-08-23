package com.gyanoba.prcomments.model

enum class TriState { ALL, RESOLVED, UNRESOLVED }

enum class ReplyState { ALL, REPLIED, NOT_REPLIED, REPLIED_BY_ME, AWAITING_ME }

enum class SortKey { CREATED, LAST_ACTIVITY, FILE_PATH, LINE }

enum class SortOrder { ASC, DESC }

/**
 * Filter predicates compose with AND; multiple selected authors compose with OR (§8.2).
 */
data class ThreadFilter(
    val resolution: TriState = TriState.ALL,
    val replyState: ReplyState = ReplyState.ALL,
    val includeOutdated: Boolean = true,
    val authors: Set<String> = emptySet(),
    val pathQuery: String = "",
    val textQuery: String = "",
) {
    val isEmpty: Boolean
        get() = resolution == TriState.ALL &&
            replyState == ReplyState.ALL &&
            includeOutdated &&
            authors.isEmpty() &&
            pathQuery.isBlank() &&
            textQuery.isBlank()

    fun matches(thread: ReviewThread, viewerLogin: String?): Boolean {
        if (!matchesResolution(thread)) return false
        if (!matchesReplyState(thread, viewerLogin)) return false
        if (!includeOutdated && thread.isOutdated) return false
        if (authors.isNotEmpty() && thread.root.authorLogin !in authors) return false
        if (pathQuery.isNotBlank() && !thread.path.contains(pathQuery, ignoreCase = true)) return false
        if (textQuery.isNotBlank() &&
            thread.comments.none { it.bodyMarkdown.contains(textQuery, ignoreCase = true) }
        ) return false
        return true
    }

    private fun matchesResolution(thread: ReviewThread) = when (resolution) {
        TriState.ALL -> true
        TriState.RESOLVED -> thread.isResolved
        TriState.UNRESOLVED -> !thread.isResolved
    }

    private fun matchesReplyState(thread: ReviewThread, viewerLogin: String?) = when (replyState) {
        ReplyState.ALL -> true
        ReplyState.REPLIED -> thread.comments.size > 1
        ReplyState.NOT_REPLIED -> thread.comments.size == 1
        // Both viewer-relative filters are inert until we know who the viewer is.
        ReplyState.REPLIED_BY_ME ->
            viewerLogin != null && thread.comments.drop(1).any { it.authorLogin == viewerLogin }
        ReplyState.AWAITING_ME ->
            viewerLogin != null && !thread.isResolved && thread.comments.last().authorLogin != viewerLogin
    }
}

data class ThreadSort(val key: SortKey = SortKey.LAST_ACTIVITY, val order: SortOrder = SortOrder.DESC)

object ThreadQuery {

    fun filter(threads: List<ReviewThread>, filter: ThreadFilter, viewerLogin: String?): List<ReviewThread> =
        threads.filter { filter.matches(it, viewerLogin) }

    /** Sorts by the requested key, then always by path and line so the order is stable (§8.3). */
    fun sort(threads: List<ReviewThread>, sort: ThreadSort): List<ReviewThread> {
        val primary: Comparator<ReviewThread> = when (sort.key) {
            SortKey.CREATED -> compareBy { it.createdAt }
            SortKey.LAST_ACTIVITY -> compareBy { it.lastActivityAt }
            SortKey.FILE_PATH -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.path }
            SortKey.LINE -> compareBy { it.displayLine ?: Int.MAX_VALUE }
        }
        val directed = if (sort.order == SortOrder.DESC) primary.reversed() else primary
        return threads.sortedWith(
            directed
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.path }
                .thenBy { it.displayLine ?: Int.MAX_VALUE }
                .thenBy { it.nodeId }
        )
    }

    fun apply(
        threads: List<ReviewThread>,
        filter: ThreadFilter,
        sort: ThreadSort,
        viewerLogin: String?,
    ): List<ReviewThread> = sort(filter(threads, filter, viewerLogin), sort)

    /** Distinct root-comment authors, for populating the author filter. */
    fun authorsOf(threads: List<ReviewThread>): List<String> =
        threads.map { it.root.authorLogin }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
}
