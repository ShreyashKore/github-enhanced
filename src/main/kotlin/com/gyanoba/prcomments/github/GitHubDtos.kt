package com.gyanoba.prcomments.github

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------------------------
// GraphQL envelope
// ---------------------------------------------------------------------------------------------

@Serializable
data class GraphQlResponse<T>(
    val data: T? = null,
    val errors: List<GraphQlErrorDto> = emptyList(),
)

@Serializable
data class GraphQlErrorDto(
    val message: String = "",
    val type: String? = null,
)

@Serializable
data class PageInfoDto(
    val hasNextPage: Boolean = false,
    val endCursor: String? = null,
)

@Serializable
data class ActorDto(
    val login: String = "",
    val avatarUrl: String? = null,
    val url: String? = null,
)

@Serializable
data class NodeIdDto(val id: String = "")

@Serializable
data class CommitDto(val oid: String? = null)

// ---------------------------------------------------------------------------------------------
// Threads query
// ---------------------------------------------------------------------------------------------

@Serializable
data class ThreadsQueryData(
    val viewer: ActorDto? = null,
    val repository: ThreadsRepositoryDto? = null,
)

@Serializable
data class ThreadsRepositoryDto(val pullRequest: ThreadsPullRequestDto? = null)

@Serializable
data class ThreadsPullRequestDto(
    val number: Int = 0,
    val title: String = "",
    val url: String = "",
    val updatedAt: String? = null,
    val headRefOid: String? = null,
    val baseRefOid: String? = null,
    val reviewThreads: ReviewThreadConnectionDto = ReviewThreadConnectionDto(),
)

@Serializable
data class ReviewThreadConnectionDto(
    val pageInfo: PageInfoDto = PageInfoDto(),
    val nodes: List<ReviewThreadDto?> = emptyList(),
)

@Serializable
data class ReviewThreadDto(
    val id: String = "",
    val isResolved: Boolean = false,
    val isOutdated: Boolean = false,
    val path: String = "",
    val line: Int? = null,
    val startLine: Int? = null,
    val originalLine: Int? = null,
    val originalStartLine: Int? = null,
    val diffSide: String? = null,
    val resolvedBy: ActorDto? = null,
    val comments: ReviewCommentConnectionDto = ReviewCommentConnectionDto(),
)

@Serializable
data class ReviewCommentConnectionDto(
    val pageInfo: PageInfoDto = PageInfoDto(),
    val nodes: List<ReviewCommentDto?> = emptyList(),
)

@Serializable
data class ReviewCommentDto(
    val id: String = "",
    val databaseId: Long? = null,
    val body: String = "",
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val url: String = "",
    val diffHunk: String? = null,
    val author: ActorDto? = null,
    val originalCommit: CommitDto? = null,
    val replyTo: NodeIdDto? = null,
)

// ---------------------------------------------------------------------------------------------
// Lightweight queries
// ---------------------------------------------------------------------------------------------

@Serializable
data class PrUpdatedAtQueryData(val repository: PrUpdatedAtRepositoryDto? = null)

@Serializable
data class PrUpdatedAtRepositoryDto(val pullRequest: PrUpdatedAtDto? = null)

@Serializable
data class PrUpdatedAtDto(val number: Int = 0, val updatedAt: String? = null)

@Serializable
data class BranchPrsQueryData(val repository: BranchPrsRepositoryDto? = null)

@Serializable
data class BranchPrsRepositoryDto(val ref: BranchRefDto? = null)

@Serializable
data class BranchRefDto(val associatedPullRequests: AssociatedPrConnectionDto = AssociatedPrConnectionDto())

@Serializable
data class AssociatedPrConnectionDto(val nodes: List<AssociatedPrDto?> = emptyList())

@Serializable
data class AssociatedPrDto(
    val number: Int = 0,
    val title: String = "",
    val headRefOid: String? = null,
)

// ---------------------------------------------------------------------------------------------
// Mutations
// ---------------------------------------------------------------------------------------------

@Serializable
data class ResolveMutationData(
    val resolveReviewThread: ThreadPayloadDto? = null,
    val unresolveReviewThread: ThreadPayloadDto? = null,
) {
    val thread: ThreadStateDto? get() = (resolveReviewThread ?: unresolveReviewThread)?.thread
}

@Serializable
data class ThreadPayloadDto(val thread: ThreadStateDto? = null)

@Serializable
data class ThreadStateDto(val id: String = "", val isResolved: Boolean = false)

// ---------------------------------------------------------------------------------------------
// REST
// ---------------------------------------------------------------------------------------------

@Serializable
data class RestUserDto(
    val login: String = "",
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

/** `POST /pulls/{n}/comments/{id}/replies` response, and `GET /user` for the connection test. */
@Serializable
data class RestReviewCommentDto(
    val id: Long = 0,
    @SerialName("node_id") val nodeId: String = "",
    val body: String = "",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("html_url") val htmlUrl: String = "",
    @SerialName("diff_hunk") val diffHunk: String? = null,
    @SerialName("original_commit_id") val originalCommitId: String? = null,
    @SerialName("in_reply_to_id") val inReplyToId: Long? = null,
    val user: RestUserDto? = null,
)

@Serializable
data class RestErrorDto(val message: String = "", @SerialName("documentation_url") val docUrl: String? = null)

/** `GET /repos/{o}/{n}/contents/{path}?ref={sha}` — the fallback source for §11.2 stage B. */
@Serializable
data class RestFileContentDto(
    val content: String? = null,
    val encoding: String? = null,
    val size: Long = 0,
)
