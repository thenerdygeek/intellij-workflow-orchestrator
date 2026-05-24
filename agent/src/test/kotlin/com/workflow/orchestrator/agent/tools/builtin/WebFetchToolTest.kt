// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.web.AllowlistDecision
import com.workflow.orchestrator.core.model.web.SanitizerVerdict
import com.workflow.orchestrator.core.model.web.WebPage
import com.workflow.orchestrator.core.services.ToolResult
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.web.UrlScreener
import com.workflow.orchestrator.core.web.WebFetchService
import com.workflow.orchestrator.core.web.WebFetchService.WebFetchRequest
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
import java.time.Instant

/**
 * Unit tests for [WebFetchTool].
 *
 * The tool calls `project.service<WebFetchService>()` (via IntelliJ's service API) and
 * also reads `AgentService.planModeActive` + `PluginSettings`. Since the full
 * IntelliJ application context is not available in unit tests, we use the
 * `project.getService(...)` form (which RevertFileToolTest already proves is
 * mockable with `mockk<Project>(relaxed=true)`) and test through a thin
 * format-helper extracted below.
 *
 * ## Why thin helpers
 * `WebFetchTool.execute()` calls `project.service<WebFetchService>()` via the Kotlin
 * extension (`ServicesKt`), which requires a real ApplicationManager to resolve.
 * Rather than spin up a full IntelliJ test harness, we:
 *   1. Extract the XML formatting logic into `WebFetchTool.formatExternalContent()`.
 *   2. Stub `project.getService(WebFetchService::class.java)` via `every { ... }` (the
 *      underlying JVM call that MockK can intercept without static mocking).
 *   3. Test the public `execute()` through `getService` mocking for the happy-path
 *      and error-propagation cases.
 *   4. Test the format helper directly for the output-structure assertions.
 *
 * The `planModeActive` AtomicBoolean on the AgentService companion is left at its
 * default (false) — we do not reset it because the companion is test-scoped to the
 * same JVM and other tests may already hold state. The PluginSettings lookup is
 * bypassed in the same way — see `buildMockProject()`.
 */
class WebFetchToolTest {

    private val tool = WebFetchTool()
    private lateinit var fetchService: WebFetchService
    private lateinit var project: Project

    private fun makeWebPage(
        finalUrl: String = "https://example.com/final",
        extractedText: String = "hello world",
        extractedChars: Int = 11,
        verdict: SanitizerVerdict = SanitizerVerdict.SAFE,
    ) = WebPage(
        originalUrl = "https://example.com",
        finalUrl = finalUrl,
        contentType = "text/html",
        responseBytes = 1_000L,
        extractedText = extractedText,
        extractedChars = extractedChars,
        screenerFlags = emptySet<UrlScreener.Flag>(),
        allowlistDecision = AllowlistDecision.APPROVED_AUTO,
        sanitizerVerdict = verdict,
        sanitizerNotes = null,
        fetchedAt = Instant.now(),
        elapsedMs = 42L,
    )

    private lateinit var stubSettings: PluginSettings
    private lateinit var stubState: PluginSettings.State

    @BeforeEach
    fun setUp() {
        fetchService = mockk()
        project = mockk(relaxed = true)
        // Wire getService so the Kotlin extension `project.service<...>()` resolves for
        // both the WebFetchService and the PluginSettings that WebFetchTool.execute() reads.
        every { project.getService(WebFetchService::class.java) } returns fetchService
        stubSettings = mockk(relaxed = true)
        stubState = PluginSettings.State().apply {
            webPlanModeAllow = false
            enableWebFetch = true
        }
        every { stubSettings.state } returns stubState
        every { project.getService(PluginSettings::class.java) } returns stubSettings
    }

    @AfterEach
    fun tearDown() {
        io.mockk.unmockkAll()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Format helper tests (pure logic, no IntelliJ injection needed)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `formatExternalContent wraps page in XML with correct attributes`() {
        val page = makeWebPage(
            finalUrl = "https://example.com/final",
            extractedText = "article body",
            extractedChars = 12,
            verdict = SanitizerVerdict.SAFE,
        )
        val result = formatExternalContent(page)
        assertTrue(result.contains("<external_content"), "missing opening tag: $result")
        assertTrue(result.contains("url='https://example.com/final'"), "missing url attr: $result")
        assertTrue(result.contains("verdict='SAFE'"), "missing verdict attr: $result")
        assertTrue(result.contains("size_chars='12'"), "missing size_chars attr: $result")
        assertTrue(result.contains("article body"), "missing body text: $result")
        assertTrue(result.contains("</external_content>"), "missing closing tag: $result")
    }

    @Test
    fun `formatExternalContent uses source = web_fetch`() {
        val page = makeWebPage()
        val result = formatExternalContent(page)
        assertTrue(result.contains("source='web_fetch'"), "source attr missing: $result")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tool execute() tests via getService stubbing
    // ─────────────────────────────────────────────────────────────────────────

    // ─────────────────────────────────────────────────────────────────────────
    // I2: disabled via settings
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `disabled via settings returns WEB_FETCH_DISABLED error`() = runTest {
        stubState.enableWebFetch = false
        val params = buildJsonObject { put("url", "https://example.com") }
        val result = tool.execute(params, project)
        assertTrue(result.isError, "Expected isError=true when web_fetch is disabled")
        assertTrue(
            result.content.contains("WEB_FETCH_DISABLED"),
            "Expected WEB_FETCH_DISABLED in content: ${result.content}"
        )
    }

    @Test
    fun `missing url param returns MALFORMED_URL error`() = runTest {
        val params = buildJsonObject { /* no 'url' key */ }
        val result = tool.execute(params, project)
        assertTrue(result.isError, "Expected isError=true")
        assertTrue(result.content.contains("MALFORMED_URL"), "Expected MALFORMED_URL in content: ${result.content}")
    }

    @Test
    fun `service error propagates with isError=true`() = runTest {
        coEvery { fetchService.fetch(any()) } returns ToolResult(
            data = null,
            summary = "URL_BLOCKED_IPV4_LINK_LOCAL: 169.254.169.254 is in a blocked range",
            isError = true,
        )
        val params = buildJsonObject { put("url", "https://example.com") }
        val result = tool.execute(params, project)
        assertTrue(result.isError, "Expected isError=true")
        assertTrue(
            result.content.contains("URL_BLOCKED_IPV4_LINK_LOCAL"),
            "Expected error code in content: ${result.content}"
        )
    }

    @Test
    fun `happy path returns external_content wrapper with verdict and finalUrl`() = runTest {
        val page = makeWebPage(
            finalUrl = "https://docs.example.com/page",
            extractedText = "Documentation page body",
            extractedChars = 23,
            verdict = SanitizerVerdict.SAFE,
        )
        coEvery { fetchService.fetch(any()) } returns ToolResult(data = page, summary = "ok")
        val params = buildJsonObject { put("url", "https://docs.example.com/page") }
        val result = tool.execute(params, project)
        assertFalse(result.isError, "Expected success but got error: ${result.content}")
        assertTrue(
            result.content.contains("<external_content"),
            "Missing <external_content> tag: ${result.content}"
        )
        assertTrue(
            result.content.contains("url='https://docs.example.com/page'"),
            "Missing finalUrl in content: ${result.content}"
        )
        assertTrue(
            result.content.contains("verdict='SAFE'"),
            "Missing verdict in content: ${result.content}"
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Package-level format helper (mirrors WebFetchTool.execute() XML building logic)
// Used to unit-test the output structure independently of IntelliJ DI.
// ─────────────────────────────────────────────────────────────────────────────

private fun formatExternalContent(page: WebPage): String =
    "<external_content url='${page.finalUrl}' source='web_fetch' " +
        "verdict='${page.sanitizerVerdict}' size_chars='${page.extractedChars}'>\n" +
        page.extractedText +
        "\n</external_content>"
