package com.gyanoba.prcomments.github

import com.gyanoba.prcomments.model.DiffSide
import com.gyanoba.prcomments.model.GitHubRepoCoordinates
import com.gyanoba.prcomments.model.PullRequestCandidate
import com.gyanoba.prcomments.model.PullRequestInfo
import com.gyanoba.prcomments.model.PullRequestRef
import com.gyanoba.prcomments.model.PullRequestThreads
import com.gyanoba.prcomments.model.ReviewComment
import com.gyanoba.prcomments.model.ReviewThread
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64

/**
 * High-level GitHub operations in terms of the plugin's own model. All functions suspend and are
 * safe to call from any dispatcher; the transport moves itself to IO.
 */
class GitHubApi(private val client: GitHubClient) {

    private val log = thisLogger()

    val endpoint: GitHubEndpoint get() = client.endpoint

    /** `GET /user` — used by the settings "Test connection" button. */
    suspend fun fetchViewerLogin(): String = client.restGet<RestUserDto>("/user").login

    /** Open PRs whose head is [branch]. Empty when the branch has none. */
    suspend fun findPullRequestsForBranch(
        repo: GitHubRepoCoordinates,
        branch: String,
    ): List<PullRequestCandidate> {
        val data: BranchPrsQueryData = client.graphQl(
            GraphQlQueries.FIND_PRS_FOR_BRANCH,
            buildJsonObject {
                put("owner", repo.owner)
                put("name", repo.name)
                put("branch", branch)
            },
        )
        return data.repository?.ref?.associatedPullRequests?.nodes.orEmpty()
            .filterNotNull()
            .map { PullRequestCandidate(it.number, it.title, it.headRefOid) }
    }

    /** The `updatedAt` guard that lets the auto-refresh timer skip a full fetch (§13.4). */
    suspend fun fetchPullRequestUpdatedAt(ref: PullRequestRef): Instant? {
        val data: PrUpdatedAtQueryData = client.graphQl(
            GraphQlQueries.FETCH_PR_UPDATED_AT,
            buildJsonObject {
                put("owner", ref.repo.owner)
                put("name", ref.repo.name)
                put("number", ref.number)
            },
        )
        return data.repository?.pullRequest?.updatedAt?.toInstantOrNull()
    }

    /** Every review thread on the PR, following `reviewThreads` pagination to exhaustion. */
    suspend fun fetchThreads(ref: PullRequestRef): PullRequestThreads {
        var cursor: String? = null
        var pullRequest: ThreadsPullRequestDto? = null
        var viewerLogin = ""
        val threads = mutableListOf<ReviewThread>()

        for (page in 0 until MAX_THREAD_PAGES) {
            val data: ThreadsQueryData = client.graphQl(
                GraphQlQueries.FETCH_THREADS,
                buildJsonObject {
                    put("owner", ref.repo.owner)
                    put("name", ref.repo.name)
                    put("number", ref.number)
                    if (cursor == null) put("cursor", kotlinx.serialization.json.JsonNull) else put("cursor", cursor)
                },
            )
            viewerLogin = data.viewer?.login.orEmpty().ifEmpty { viewerLogin }
            val pr = data.repository?.pullRequest ?: throw GitHubError.NotFound()
            pullRequest = pr

            pr.reviewThreads.nodes.filterNotNull().mapNotNullTo(threads) { it.toModel() }

            if (!pr.reviewThreads.pageInfo.hasNextPage) break
            cursor = pr.reviewThreads.pageInfo.endCursor ?: break
            if (page == MAX_THREAD_PAGES - 1) {
                log.warn("Stopped paginating review threads for ${ref.slug} after $MAX_THREAD_PAGES pages")
            }
        }

        val pr = pullRequest ?: throw GitHubError.NotFound()
        return PullRequestThreads(
            pullRequest = PullRequestInfo(
                ref = ref,
                title = pr.title,
                url = pr.url,
                headRefOid = pr.headRefOid,
                baseRefOid = pr.baseRefOid,
                updatedAt = pr.updatedAt?.toInstantOrNull(),
            ),
            threads = threads,
            viewerLogin = viewerLogin,
            fetchedAt = Instant.now(),
        )
    }

    /**
     * Replies to a thread over REST (§2): [rootCommentDatabaseId] is the *databaseId* of the
     * thread's root comment, not its GraphQL node id.
     */
    suspend fun replyToThread(
        ref: PullRequestRef,
        rootCommentDatabaseId: Long,
        body: String,
    ): ReviewComment {
        val dto: RestReviewCommentDto = client.restPost(
            "/repos/${ref.repo.owner}/${ref.repo.name}/pulls/${ref.number}/comments/$rootCommentDatabaseId/replies",
            buildJsonObject { put("body", body) },
        )
        return dto.toModel()
    }

    /** Returns the thread's resolved state as GitHub reports it after the mutation. */
    suspend fun setThreadResolved(threadNodeId: String, resolved: Boolean): Boolean {
        val data: ResolveMutationData = client.graphQl(
            if (resolved) GraphQlQueries.RESOLVE_THREAD else GraphQlQueries.UNRESOLVE_THREAD,
            buildJsonObject { put("threadId", threadNodeId) },
        )
        return data.thread?.isResolved ?: resolved
    }

    /**
     * File content at a revision, used when the git object is not available locally (§11.2 step 1).
     * Returns null when the path does not exist at that revision.
     */
    suspend fun fetchFileContent(ref: PullRequestRef, path: String, revision: String): String? {
        val encodedPath = path.split('/').joinToString("/") { URLEncoder.encode(it, StandardCharsets.UTF_8) }
        val query = "?ref=" + URLEncoder.encode(revision, StandardCharsets.UTF_8)
        val dto: RestFileContentDto = try {
            client.restGet("/repos/${ref.repo.owner}/${ref.repo.name}/contents/$encodedPath$query")
        } catch (_: GitHubError.NotFound) {
            return null
        }
        val encoded = dto.content ?: return null
        if (!dto.encoding.equals("base64", ignoreCase = true)) return encoded
        return try {
            String(Base64.getMimeDecoder().decode(encoded), StandardCharsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            log.warn("Could not base64-decode $path@$revision", e)
            null
        }
    }

    companion object {
        /** Runaway guard on `reviewThreads` pagination (§7.2): 20 pages x 50 = 1000 threads. */
        const val MAX_THREAD_PAGES: Int = 20
    }
}

// -------------------------------------------------------------------------------------------------
// DTO -> model
// -------------------------------------------------------------------------------------------------

internal fun String.toInstantOrNull(): Instant? = try {
    Instant.parse(this)
} catch (_: DateTimeParseException) {
    null
}

internal fun ReviewThreadDto.toModel(): ReviewThread? {
    val comments = comments.nodes.filterNotNull().mapNotNull { it.toModel() }
    // A thread with no readable comments is not something the UI can render; drop it.
    if (comments.isEmpty() || id.isEmpty()) return null
    return ReviewThread(
        nodeId = id,
        path = path,
        isResolved = isResolved,
        isOutdated = isOutdated,
        resolvedByLogin = resolvedBy?.login?.takeIf { it.isNotEmpty() },
        currentLine = line,
        startLine = startLine,
        originalLine = originalLine,
        originalStartLine = originalStartLine,
        diffSide = if (diffSide.equals("LEFT", ignoreCase = true)) DiffSide.LEFT else DiffSide.RIGHT,
        comments = comments.sortedBy { it.createdAt },
    )
}

internal fun ReviewCommentDto.toModel(): ReviewComment? {
    if (id.isEmpty()) return null
    val created = createdAt?.toInstantOrNull() ?: Instant.EPOCH
    return ReviewComment(
        nodeId = id,
        databaseId = databaseId ?: 0L,
        authorLogin = author?.login.orEmpty().ifEmpty { GHOST_LOGIN },
        avatarUrl = author?.avatarUrl,
        bodyMarkdown = body,
        createdAt = created,
        updatedAt = updatedAt?.toInstantOrNull() ?: created,
        htmlUrl = url,
        diffHunk = diffHunk?.takeIf { it.isNotBlank() },
        originalCommitOid = originalCommit?.oid,
        isReply = replyTo != null,
    )
}

internal fun RestReviewCommentDto.toModel(): ReviewComment {
    val created = createdAt?.toInstantOrNull() ?: Instant.now()
    return ReviewComment(
        nodeId = nodeId,
        databaseId = id,
        authorLogin = user?.login.orEmpty().ifEmpty { GHOST_LOGIN },
        avatarUrl = user?.avatarUrl,
        bodyMarkdown = body,
        createdAt = created,
        updatedAt = updatedAt?.toInstantOrNull() ?: created,
        htmlUrl = htmlUrl,
        diffHunk = diffHunk?.takeIf { it.isNotBlank() },
        originalCommitOid = originalCommitId,
        isReply = inReplyToId != null,
    )
}

/** GitHub reports deleted accounts as a null author. */
internal const val GHOST_LOGIN: String = "ghost"
