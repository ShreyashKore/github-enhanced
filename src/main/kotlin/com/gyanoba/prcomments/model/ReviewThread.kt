package com.gyanoba.prcomments.model

import java.time.Instant

enum class DiffSide { LEFT, RIGHT }

data class ReviewComment(
    val nodeId: String,
    /** REST id. `0` for a provisional comment that has not been acknowledged by GitHub yet. */
    val databaseId: Long,
    val authorLogin: String,
    val avatarUrl: String?,
    val bodyMarkdown: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val htmlUrl: String,
    val diffHunk: String?,
    val originalCommitOid: String?,
    val isReply: Boolean,
    /** True while an optimistic reply is still in flight (see §12.2). */
    val isPending: Boolean = false,
)

data class ReviewThread(
    val nodeId: String,
    val path: String,
    val isResolved: Boolean,
    val isOutdated: Boolean,
    val resolvedByLogin: String?,
    /** GraphQL `line`: position in the PR head. Null once the thread is outdated. */
    val currentLine: Int?,
    val startLine: Int?,
    /** GraphQL `originalLine`: position in the commit that was actually commented on. */
    val originalLine: Int?,
    val originalStartLine: Int?,
    val diffSide: DiffSide,
    /** Root comment first, then replies in chronological order. Never empty. */
    val comments: List<ReviewComment>,
) {
    init {
        require(comments.isNotEmpty()) { "A review thread always has at least its root comment" }
    }

    val root: ReviewComment get() = comments.first()
    val replyCount: Int get() = comments.size - 1
    val createdAt: Instant get() = root.createdAt
    val lastActivityAt: Instant get() = comments.maxOf { it.updatedAt }

    /** Best line number to show in the list: PR-head position, falling back to the original one. */
    val displayLine: Int? get() = currentLine ?: originalLine

    val fileName: String get() = path.substringAfterLast('/')

    fun withComments(newComments: List<ReviewComment>): ReviewThread = copy(comments = newComments)
}
