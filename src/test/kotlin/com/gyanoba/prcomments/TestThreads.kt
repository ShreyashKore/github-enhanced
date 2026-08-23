package com.gyanoba.prcomments

import com.gyanoba.prcomments.model.DiffSide
import com.gyanoba.prcomments.model.ReviewComment
import com.gyanoba.prcomments.model.ReviewThread
import java.time.Instant

/** Builders so the pure tests can express intent rather than boilerplate. */
object TestThreads {

    val BASE: Instant = Instant.parse("2026-03-01T10:00:00Z")

    fun comment(
        id: String,
        author: String,
        body: String = "body of $id",
        createdAt: Instant = BASE,
        updatedAt: Instant = createdAt,
        isReply: Boolean = false,
        diffHunk: String? = null,
        databaseId: Long = id.hashCode().toLong() and 0xFFFF,
    ) = ReviewComment(
        nodeId = id,
        databaseId = databaseId,
        authorLogin = author,
        avatarUrl = null,
        bodyMarkdown = body,
        createdAt = createdAt,
        updatedAt = updatedAt,
        htmlUrl = "https://github.com/o/n/pull/1#discussion_r$databaseId",
        diffHunk = diffHunk,
        originalCommitOid = "abc123",
        isReply = isReply,
    )

    fun thread(
        id: String,
        path: String = "src/Foo.kt",
        line: Int? = 42,
        resolved: Boolean = false,
        outdated: Boolean = false,
        author: String = "alice",
        replies: List<Pair<String, String>> = emptyList(),
        createdAt: Instant = BASE,
        replyBodies: List<String> = emptyList(),
    ): ReviewThread {
        val root = comment("$id-root", author, createdAt = createdAt)
        val replyComments = replies.mapIndexed { index, (replyId, replyAuthor) ->
            comment(
                id = replyId,
                author = replyAuthor,
                body = replyBodies.getOrElse(index) { "reply $replyId" },
                createdAt = createdAt.plusSeconds(60L * (index + 1)),
                isReply = true,
            )
        }
        return ReviewThread(
            nodeId = id,
            path = path,
            isResolved = resolved,
            isOutdated = outdated,
            resolvedByLogin = if (resolved) "carol" else null,
            currentLine = line,
            startLine = null,
            originalLine = line,
            originalStartLine = null,
            diffSide = DiffSide.RIGHT,
            comments = listOf(root) + replyComments,
        )
    }
}
