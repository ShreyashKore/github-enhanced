package com.gyanoba.prcomments.model

import com.gyanoba.prcomments.TestThreads
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.system.measureTimeMillis

/** Covers every predicate in the §8.2 table against a hand-built fixture list. */
class FiltersTest {

    private val viewer = "me"

    // 10 threads spanning every dimension the filters look at.
    private val threads = listOf(
        TestThreads.thread("t1", path = "src/Alpha.kt", line = 10, author = "alice"),
        TestThreads.thread("t2", path = "src/Beta.kt", line = 20, author = "bob", resolved = true),
        TestThreads.thread("t3", path = "src/Alpha.kt", line = 5, author = "alice", outdated = true),
        TestThreads.thread("t4", path = "test/Gamma.kt", line = 1, author = "bob", replies = listOf("r4" to "alice")),
        TestThreads.thread("t5", path = "src/Delta.kt", line = 99, author = "me", replies = listOf("r5" to "me")),
        TestThreads.thread("t6", path = "src/Alpha.kt", line = 30, author = "alice", replies = listOf("r6" to "me")),
        TestThreads.thread(
            "t7", path = "src/Epsilon.kt", line = 7, author = "bob",
            replies = listOf("r7" to "bob"), resolved = true,
        ),
        TestThreads.thread("t8", path = "docs/README.md", line = null, author = "carol"),
        TestThreads.thread(
            "t9", path = "src/Zeta.kt", line = 2, author = "carol",
            replies = listOf("r9" to "me"), replyBodies = listOf("mentions FLUX capacitor"),
        ),
        TestThreads.thread("t10", path = "src/Alpha.kt", line = 60, author = "me", outdated = true, resolved = true),
    )

    private fun ids(filter: ThreadFilter) =
        ThreadQuery.filter(threads, filter, viewer).map { it.nodeId }

    @Test
    fun `default filter keeps everything`() {
        assertEquals(threads.map { it.nodeId }, ids(ThreadFilter()))
    }

    @Test
    fun `resolved and unresolved`() {
        assertEquals(listOf("t2", "t7", "t10"), ids(ThreadFilter(resolution = TriState.RESOLVED)))
        assertEquals(
            listOf("t1", "t3", "t4", "t5", "t6", "t8", "t9"),
            ids(ThreadFilter(resolution = TriState.UNRESOLVED)),
        )
    }

    @Test
    fun `replied and not replied`() {
        assertEquals(listOf("t4", "t5", "t6", "t7", "t9"), ids(ThreadFilter(replyState = ReplyState.REPLIED)))
        assertEquals(listOf("t1", "t2", "t3", "t8", "t10"), ids(ThreadFilter(replyState = ReplyState.NOT_REPLIED)))
    }

    @Test
    fun `replied by me looks only at replies, not the root comment`() {
        // t5's root is mine but so is its reply; t10's root is mine with no reply, so it must not match.
        assertEquals(listOf("t5", "t6", "t9"), ids(ThreadFilter(replyState = ReplyState.REPLIED_BY_ME)))
    }

    @Test
    fun `awaiting my reply is unresolved threads whose last word was not mine`() {
        assertEquals(listOf("t1", "t3", "t4", "t8"), ids(ThreadFilter(replyState = ReplyState.AWAITING_ME)))
    }

    @Test
    fun `viewer-relative filters are inert without a viewer login`() {
        val awaiting = ThreadFilter(replyState = ReplyState.AWAITING_ME)
        assertTrue(ThreadQuery.filter(threads, awaiting, viewerLogin = null).isEmpty())
    }

    @Test
    fun `outdated can be excluded`() {
        assertEquals(
            listOf("t1", "t2", "t4", "t5", "t6", "t7", "t8", "t9"),
            ids(ThreadFilter(includeOutdated = false)),
        )
    }

    @Test
    fun `authors compose with OR and match the root author only`() {
        assertEquals(listOf("t1", "t3", "t6"), ids(ThreadFilter(authors = setOf("alice"))))
        assertEquals(
            listOf("t1", "t3", "t6", "t8", "t9"),
            ids(ThreadFilter(authors = setOf("alice", "carol"))),
        )
    }

    @Test
    fun `path filter is a case-insensitive substring match`() {
        assertEquals(listOf("t1", "t3", "t6", "t10"), ids(ThreadFilter(pathQuery = "alpha")))
        assertEquals(listOf("t4"), ids(ThreadFilter(pathQuery = "TEST/")))
    }

    @Test
    fun `text filter searches every comment body`() {
        assertEquals(listOf("t9"), ids(ThreadFilter(textQuery = "flux capacitor")))
        assertEquals(threads.map { it.nodeId }, ids(ThreadFilter(textQuery = "body of")))
    }

    @Test
    fun `filters compose with AND`() {
        val filter = ThreadFilter(
            resolution = TriState.UNRESOLVED,
            authors = setOf("alice"),
            pathQuery = "src/",
            includeOutdated = false,
        )
        assertEquals(listOf("t1", "t6"), ids(filter))
    }

    @Test
    fun `isEmpty reports whether anything is actually filtering`() {
        assertTrue(ThreadFilter().isEmpty)
        assertTrue(!ThreadFilter(pathQuery = "x").isEmpty)
        assertTrue(!ThreadFilter(includeOutdated = false).isEmpty)
    }

    // -- sorting -------------------------------------------------------------------------------

    @Test
    fun `sorts by file path then line`() {
        val sorted = ThreadQuery.sort(threads, ThreadSort(SortKey.FILE_PATH, SortOrder.ASC))
        assertEquals(
            // docs/ < src/Alpha.kt (by line: 5, 10, 30, 60) < src/Beta.kt < ... < test/Gamma.kt
            listOf("t8", "t3", "t1", "t6", "t10", "t2", "t5", "t7", "t9", "t4"),
            sorted.map { it.nodeId },
        )
    }

    @Test
    fun `last activity descending is the default`() {
        val recent = TestThreads.thread("recent", createdAt = Instant.parse("2026-06-01T10:00:00Z"))
        val sorted = ThreadQuery.sort(threads + recent, ThreadSort())
        assertEquals("recent", sorted.first().nodeId)
    }

    @Test
    fun `secondary ordering keeps equal keys stable`() {
        // Every fixture thread shares a created timestamp, so path-then-line decides the order.
        val sorted = ThreadQuery.sort(threads, ThreadSort(SortKey.CREATED, SortOrder.ASC))
        val byPathThenLine = ThreadQuery.sort(threads, ThreadSort(SortKey.FILE_PATH, SortOrder.ASC))
        assertEquals(byPathThenLine.map { it.nodeId }, sorted.map { it.nodeId })
    }

    @Test
    fun `threads without a line sort last within their file`() {
        val sorted = ThreadQuery.sort(
            listOf(
                TestThreads.thread("noline", path = "a.kt", line = null),
                TestThreads.thread("line1", path = "a.kt", line = 1),
            ),
            ThreadSort(SortKey.LINE, SortOrder.ASC),
        )
        assertEquals(listOf("line1", "noline"), sorted.map { it.nodeId })
    }

    @Test
    fun `filtering and sorting 500 threads is fast`() {
        val many = (1..500).map { index ->
            TestThreads.thread(
                id = "t$index",
                path = "src/pkg${index % 20}/File$index.kt",
                line = index,
                author = listOf("alice", "bob", "me")[index % 3],
                resolved = index % 4 == 0,
                outdated = index % 7 == 0,
                replies = if (index % 2 == 0) listOf("r$index" to "me") else emptyList(),
            )
        }
        val filter = ThreadFilter(
            resolution = TriState.UNRESOLVED,
            replyState = ReplyState.REPLIED_BY_ME,
            includeOutdated = false,
            authors = setOf("alice", "bob"),
            pathQuery = "src/",
            textQuery = "body",
        )
        // Warm up the JIT and the regex-free comparators before measuring.
        repeat(5) { ThreadQuery.apply(many, filter, ThreadSort(), viewer) }
        val elapsed = measureTimeMillis { ThreadQuery.apply(many, filter, ThreadSort(), viewer) }
        assertTrue(elapsed < 10, "filter+sort of 500 threads took ${elapsed}ms, expected <10ms")
    }
}
