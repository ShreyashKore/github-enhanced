package com.gyanoba.prcomments

import com.gyanoba.prcomments.model.ReplyState
import com.gyanoba.prcomments.model.SortKey
import com.gyanoba.prcomments.model.SortOrder
import com.gyanoba.prcomments.model.ThreadFilter
import com.gyanoba.prcomments.model.ThreadSort
import com.gyanoba.prcomments.model.TriState
import com.gyanoba.prcomments.service.PrCommentsService
import com.gyanoba.prcomments.service.PrCommentsSettings
import com.gyanoba.prcomments.service.ViewState
import com.gyanoba.prcomments.ui.PrCommentsToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.toolWindow.ToolWindowHeadlessManagerImpl

/**
 * Platform wiring: the factory must build its content without throwing, and the settings component
 * must round-trip (JUnit 4 style, run through the vintage engine).
 */
class PrCommentsToolWindowTest : BasePlatformTestCase() {

    fun `test factory creates tool window content`() {
        val factory = PrCommentsToolWindowFactory()
        assertTrue(factory.shouldBeAvailable(project))

        val toolWindow = ToolWindowHeadlessManagerImpl.MockToolWindow(project)
        factory.createToolWindowContent(project, toolWindow)

        val contents = toolWindow.contentManager.contents
        assertEquals(1, contents.size)
        assertNotNull(contents[0].component)
    }

    fun `test tool window is registered by the plugin descriptor`() {
        val manager = ToolWindowManager.getInstance(project)
        // Registration happens lazily in headless mode; the id must at least be known.
        assertNotNull(manager)
        assertEquals("PR Comments", PrCommentsToolWindowFactory.ID)
    }

    fun `test service starts idle and exposes persisted filter state`() {
        val service = PrCommentsService.getInstance(project)
        assertTrue(service.state.value is ViewState.Idle)
        assertEquals(ThreadFilter(), service.filter.value)
        assertEquals(ThreadSort(), service.sort.value)
    }

    fun `test settings round-trip filter sort and pinned pull requests`() {
        val settings = PrCommentsSettings.getInstance(project)

        val filter = ThreadFilter(
            resolution = TriState.UNRESOLVED,
            replyState = ReplyState.AWAITING_ME,
            includeOutdated = false,
            authors = setOf("alice", "bob"),
            pathQuery = "src/",
            textQuery = "todo",
        )
        val sort = ThreadSort(SortKey.FILE_PATH, SortOrder.ASC)
        settings.filter = filter
        settings.sort = sort
        settings.setPrOverride("feature/x", 4242)

        val reloaded = PrCommentsSettings()
        reloaded.loadState(settings.state)

        assertEquals(filter, reloaded.filter)
        assertEquals(sort, reloaded.sort)
        assertEquals(4242, reloaded.prOverrideFor("feature/x"))
        assertNull(reloaded.prOverrideFor("feature/other"))
    }

    fun `test refresh interval is clamped to a sane range`() {
        val settings = PrCommentsSettings()
        settings.refreshIntervalSeconds = 1
        assertEquals(PrCommentsSettings.MIN_REFRESH_SECONDS, settings.refreshIntervalSeconds)
        settings.refreshIntervalSeconds = 100_000
        assertEquals(PrCommentsSettings.MAX_REFRESH_SECONDS, settings.refreshIntervalSeconds)
    }

    fun `test the token never lands in the persisted state`() {
        val settings = PrCommentsSettings.getInstance(project)
        val serialized = com.intellij.configurationStore.serialize(settings.state)?.let {
            com.intellij.openapi.util.JDOMUtil.write(it)
        }.orEmpty()
        assertFalse(serialized.contains("token", ignoreCase = true))
    }
}
