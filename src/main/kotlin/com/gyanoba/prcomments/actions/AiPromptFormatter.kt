package com.gyanoba.prcomments.actions

import com.gyanoba.prcomments.model.ReviewThread

/**
 * Formats selected review threads as a compact prompt for an AI coding assistant.
 *
 * The output is deliberately narrow: a coding assistant pasted into the same repo can already read
 * the file at `path:line` and see who wrote what in `git blame`, so the diff hunk, author names and
 * GitHub links are all omitted as tokens that inform a human but not a fix. What's left is exactly
 * what the assistant can't get any other way: *where* to look and *what was asked for*, including
 * any reply that narrowed or changed the ask. See the "Copy for AI" section of README.md for the
 * exact shape and the reasoning behind it — keep that doc in sync with this format.
 */
object AiPromptFormatter {

    fun format(threads: List<ReviewThread>): String {
        val intro = if (threads.size == 1) {
            "Fix this GitHub PR review comment:"
        } else {
            "Fix these ${threads.size} GitHub PR review comments:"
        }
        val blocks = threads.joinToString("\n\n", transform = ::formatThread)
        return "$intro\n\n$blocks"
    }

    private fun formatThread(thread: ReviewThread): String {
        val location = thread.path + (thread.displayLine?.let { ":$it" } ?: "")
        val comments = thread.comments.joinToString("\n") { comment ->
            val prefix = if (comment.isReply) "  ↳ " else "- "
            val continuationIndent = " ".repeat(prefix.length)
            comment.bodyMarkdown.trim().lineSequence()
                .mapIndexed { i, line -> if (i == 0) prefix + line else continuationIndent + line }
                .joinToString("\n")
        }
        return "$location\n$comments"
    }
}
