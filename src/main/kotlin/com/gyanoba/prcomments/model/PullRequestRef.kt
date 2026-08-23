package com.gyanoba.prcomments.model

import java.time.Instant

/** `owner/name` on a specific GitHub host. */
data class GitHubRepoCoordinates(val host: String, val owner: String, val name: String) {
    val slug: String get() = "$owner/$name"
    override fun toString(): String = "$host/$owner/$name"
}

data class PullRequestRef(val repo: GitHubRepoCoordinates, val number: Int) {
    val slug: String get() = "${repo.slug} #$number"
}

data class PullRequestInfo(
    val ref: PullRequestRef,
    val title: String,
    val url: String,
    /** Head commit of the PR at fetch time; the revision `ReviewThread.currentLine` is expressed against. */
    val headRefOid: String?,
    val baseRefOid: String?,
    val updatedAt: Instant?,
)

/** Everything one refresh produces. */
data class PullRequestThreads(
    val pullRequest: PullRequestInfo,
    val threads: List<ReviewThread>,
    val viewerLogin: String,
    val fetchedAt: Instant,
)

/** A pull request as returned by branch -> PR lookup, before the full fetch. */
data class PullRequestCandidate(val number: Int, val title: String, val headRefOid: String?) {
    override fun toString(): String = "#$number — $title"
}
