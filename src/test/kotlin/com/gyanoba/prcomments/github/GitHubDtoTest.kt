package com.gyanoba.prcomments.github

import com.gyanoba.prcomments.model.DiffSide
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Deserialization against recorded responses (tokens redacted). CI never touches the network.
 */
class GitHubDtoTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/$name")) { "missing fixture $name" }
            .bufferedReader().use { it.readText() }

    private fun threadsResponse(): GraphQlResponse<ThreadsQueryData> =
        json.decodeFromString(fixture("reviewThreads.json"))

    @Test
    fun `parses the review threads response`() {
        val response = threadsResponse()
        assertTrue(response.errors.isEmpty())

        val data = checkNotNull(response.data)
        assertEquals("octocat", data.viewer?.login)

        val pr = checkNotNull(data.repository?.pullRequest)
        assertEquals(123, pr.number)
        assertEquals("Add rate limiting to the ingest worker", pr.title)
        assertEquals("5f1c3b2a9d4e6f708192a3b4c5d6e7f809a1b2c3", pr.headRefOid)
        assertEquals("2026-03-02T09:15:44Z", pr.updatedAt)
        assertEquals(false, pr.reviewThreads.pageInfo.hasNextPage)
        // The fixture deliberately contains a null node, as GitHub sends for inaccessible entries.
        assertEquals(3, pr.reviewThreads.nodes.size)
    }

    @Test
    fun `maps DTOs onto the domain model`() {
        val pr = checkNotNull(threadsResponse().data?.repository?.pullRequest)
        val threads = pr.reviewThreads.nodes.filterNotNull().mapNotNull { it.toModel() }

        assertEquals(2, threads.size)

        val open = threads.first()
        assertEquals("PRRT_kwDOAAAA1s4AAQID", open.nodeId)
        assertEquals("src/main/kotlin/Ingest.kt", open.path)
        assertEquals(false, open.isResolved)
        assertEquals(42, open.currentLine)
        assertEquals(DiffSide.RIGHT, open.diffSide)
        assertEquals(1, open.replyCount)
        assertEquals("alice", open.root.authorLogin)
        assertEquals(1234567890L, open.root.databaseId)
        assertEquals(Instant.parse("2026-03-01T10:00:00Z"), open.createdAt)
        assertEquals(Instant.parse("2026-03-01T11:31:00Z"), open.lastActivityAt)
        assertTrue(open.root.diffHunk!!.startsWith("@@ -39,5 +39,6 @@"))
        assertEquals(false, open.root.isReply)
        assertEquals(true, open.comments[1].isReply)
        assertNull(open.resolvedByLogin)
    }

    @Test
    fun `handles a resolved outdated thread with a deleted author`() {
        val pr = checkNotNull(threadsResponse().data?.repository?.pullRequest)
        val outdated = pr.reviewThreads.nodes.filterNotNull().mapNotNull { it.toModel() }[1]

        assertEquals(true, outdated.isResolved)
        assertEquals(true, outdated.isOutdated)
        assertEquals("bob", outdated.resolvedByLogin)
        assertNull(outdated.currentLine)
        assertEquals(7, outdated.originalLine)
        assertEquals(DiffSide.LEFT, outdated.diffSide)
        // A null GraphQL author means the account was deleted.
        assertEquals("ghost", outdated.root.authorLogin)
    }

    @Test
    fun `surfaces GraphQL errors even on HTTP 200`() {
        val response: GraphQlResponse<ThreadsQueryData> =
            json.decodeFromString(fixture("reviewThreadsErrors.json"))

        assertNull(response.data)
        assertEquals(1, response.errors.size)
        assertTrue(response.errors.first().message.contains("Could not resolve"))
    }

    @Test
    fun `parses the REST reply response into a model comment`() {
        val dto: RestReviewCommentDto = json.decodeFromString(fixture("replyResponse.json"))
        val comment = dto.toModel()

        assertEquals(1234567899L, comment.databaseId)
        assertEquals("PRRC_kwDOAAAA1s5AAQIZ", comment.nodeId)
        assertEquals("octocat", comment.authorLogin)
        assertEquals("Sounds good, pushing a fix now.", comment.bodyMarkdown)
        assertEquals(Instant.parse("2026-03-02T09:15:44Z"), comment.createdAt)
        assertTrue(comment.isReply)
        assertEquals(false, comment.isPending)
    }

    @Test
    fun `unknown fields do not break parsing`() {
        // `pull_request_review_id` is in the fixture but not in the DTO, and must be ignored.
        val dto: RestReviewCommentDto = json.decodeFromString(fixture("replyResponse.json"))
        assertEquals("octocat", dto.user?.login)
    }

    @Test
    fun `a thread with no readable comments is dropped rather than crashing`() {
        val empty = ReviewThreadDto(id = "PRRT_empty", path = "a.kt")
        assertNull(empty.toModel())
    }

    @Test
    fun `endpoints are derived from the host`() {
        val dotCom = GitHubEndpoint.of("github.com", "", "")
        assertEquals("https://api.github.com", dotCom.restBaseUrl)
        assertEquals("https://api.github.com/graphql", dotCom.graphQlUrl)

        val enterprise = GitHubEndpoint.of("git.example.com", "", "")
        assertEquals("https://git.example.com/api/v3", enterprise.restBaseUrl)
        assertEquals("https://git.example.com/api/graphql", enterprise.graphQlUrl)

        val overridden = GitHubEndpoint.of("github.com", "https://proxy/api/", "https://proxy/graphql")
        assertEquals("https://proxy/api", overridden.restBaseUrl)
        assertEquals("https://proxy/graphql", overridden.graphQlUrl)
    }
}
