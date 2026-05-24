// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.web.SearchHit
import com.workflow.orchestrator.core.services.ToolResult
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.web.UrlScreener
import com.workflow.orchestrator.core.web.WebSearchService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [WebSearchTool].
 *
 * The tool resolves WebSearchService via `ServiceLookup.webSearch(project)` which
 * calls `project.getService(WebSearchService::class.java)` — interceptable via
 * `mockk<Project>(relaxed=true)`.
 *
 * `AgentService.planModeActive` defaults to false in the JVM; PluginSettings is not
 * read when the service lookup returns null (the "not configured" early-exit) or when
 * plan mode is off — so the only thing we need to mock is the service itself.
 *
 * See WebFetchToolTest for the rationale on this mocking approach.
 */
class WebSearchToolTest {

    private val tool = WebSearchTool()
    private lateinit var searchService: WebSearchService
    private lateinit var project: Project

    private fun makeHit(
        title: String = "Example Title",
        url: String = "https://example.com",
        snippet: String = "A useful snippet",
        provider: String = "SearXNG",
        rank: Int = 0,
        flags: Set<UrlScreener.Flag> = emptySet(),
    ) = SearchHit(
        title = title,
        url = url,
        snippet = snippet,
        provider = provider,
        rank = rank,
        screenerFlags = flags,
    )

    private lateinit var stubSettings: PluginSettings
    private lateinit var stubState: PluginSettings.State

    @BeforeEach
    fun setUp() {
        searchService = mockk()
        project = mockk(relaxed = true)
        // Wire getService so `project.service<WebSearchService>()` and
        // `project.service<PluginSettings>()` both resolve (WebSearchTool reads both).
        every { project.getService(WebSearchService::class.java) } returns searchService
        stubSettings = mockk(relaxed = true)
        stubState = PluginSettings.State().apply {
            webPlanModeAllow = false
            enableWebSearch = true
        }
        every { stubSettings.state } returns stubState
        every { project.getService(PluginSettings::class.java) } returns stubSettings
    }

    @AfterEach
    fun tearDown() {
        io.mockk.unmockkAll()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // I2: disabled via settings
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `disabled via settings returns error if invoked directly (defense in depth)`() = runTest {
        // NOTE: When enableWebSearch=false, the tool is NOT registered in ToolRegistry and is
        // therefore unreachable via the normal ReAct loop. This test exercises the belt-and-suspenders
        // guard inside execute() — the safety net for the narrow race where settings change
        // mid-iteration before reregisterConditionalTools fires.
        stubState.enableWebSearch = false
        val params = buildJsonObject { put("query", "kotlin coroutines") }
        val result = tool.execute(params, project)
        assertTrue(result.isError, "Expected isError=true when web_search is disabled")
        assertTrue(
            result.content.contains("WEB_SEARCH_DISABLED"),
            "Expected WEB_SEARCH_DISABLED in content: ${result.content}"
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Missing required param
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `missing query param returns MALFORMED_QUERY error`() = runTest {
        val params = buildJsonObject { /* no 'query' key */ }
        val result = tool.execute(params, project)
        assertTrue(result.isError, "Expected isError=true")
        assertTrue(
            result.content.contains("MALFORMED_QUERY"),
            "Expected MALFORMED_QUERY in content: ${result.content}"
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Happy path
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `happy path wraps results in external_search with correct fields`() = runTest {
        val hits = listOf(
            makeHit(title = "Kotlin Docs", url = "https://kotlinlang.org", snippet = "Official docs", provider = "SearXNG", rank = 0),
        )
        coEvery { searchService.search(any()) } returns ToolResult(data = hits, summary = "1 results")
        val params = buildJsonObject { put("query", "kotlin coroutines") }
        val result = tool.execute(params, project)
        assertFalse(result.isError, "Expected success: ${result.content}")
        assertTrue(result.content.contains("<external_search"), "Missing <external_search>: ${result.content}")
        assertTrue(result.content.contains("query='kotlin coroutines'"), "Missing query attr: ${result.content}")
        assertTrue(result.content.contains("provider='SearXNG'"), "Missing provider attr: ${result.content}")
        assertTrue(result.content.contains("[1] Kotlin Docs — https://kotlinlang.org"), "Missing result line: ${result.content}")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Service error propagation
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `service NO_PROVIDER_CONFIGURED error propagates as isError=true`() = runTest {
        coEvery { searchService.search(any()) } returns ToolResult(
            data = null,
            summary = "NO_PROVIDER_CONFIGURED: no search provider set up",
            isError = true,
        )
        val params = buildJsonObject { put("query", "test") }
        val result = tool.execute(params, project)
        assertTrue(result.isError, "Expected isError=true")
        assertTrue(
            result.content.contains("NO_PROVIDER_CONFIGURED"),
            "Expected error code in content: ${result.content}"
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Screener flags appear inline
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `screener flags appear inline in formatted output`() = runTest {
        val hits = listOf(
            makeHit(
                title = "Short URL",
                url = "https://bit.ly/abc123",
                snippet = "Shortened link",
                provider = "SearXNG",
                rank = 0,
                flags = setOf(UrlScreener.Flag.SHORTENER),
            ),
        )
        coEvery { searchService.search(any()) } returns ToolResult(data = hits, summary = "1 results")
        val params = buildJsonObject { put("query", "link test") }
        val result = tool.execute(params, project)
        assertFalse(result.isError, "Expected success: ${result.content}")
        assertTrue(
            result.content.contains("[SHORTENER]"),
            "Expected [SHORTENER] flag in output: ${result.content}"
        )
    }
}
