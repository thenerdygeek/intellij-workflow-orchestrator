package com.workflow.orchestrator.agent.tools.delegation

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.delegation.DelegationOutboundService
import com.workflow.orchestrator.agent.delegation.DelegationStatusResult
import com.workflow.orchestrator.agent.testutil.installReadActionInlineShim
import com.workflow.orchestrator.core.settings.PluginSettings
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests the `status` action wired into [DelegationTool] (2026-05-30 — the agent asked for a
 * way to check whether a delegation is still running without a full transcript round-trip).
 */
class DelegationStatusActionTest {

    private val outbound = mockk<DelegationOutboundService>(relaxed = true)
    private val project = mockk<Project>(relaxed = true)
    private val tool = DelegationTool()

    @BeforeEach fun setup() {
        installReadActionInlineShim()
        mockkObject(PluginSettings.Companion)
        val settings = mockk<PluginSettings>(relaxed = true)
        val state = mockk<PluginSettings.State>(relaxed = true)
        every { state.enableOutboundCrossIdeDelegation } returns true
        every { settings.state } returns state
        every { PluginSettings.getInstance(any()) } returns settings
        every { project.getService(DelegationOutboundService::class.java) } returns outbound
    }

    @AfterEach fun tearDown() { unmockkAll() }

    private fun statusParams(handle: String): JsonObject = buildJsonObject {
        put("action", JsonPrimitive("status"))
        put("handle", JsonPrimitive(handle))
    }

    @Test
    fun `status reports an active delegation`() = runBlocking {
        every { outbound.statusOf("h1") } returns DelegationStatusResult.Active("RUNNING", "backend")
        val result = tool.execute(statusParams("h1"), project)
        assertFalse(result.isError)
        assertTrue(result.content.contains("\"status\":\"active\""))
        assertTrue(result.content.contains("RUNNING"))
        assertTrue(result.summary.contains("backend"))
    }

    @Test
    fun `status reports a closed delegation and points at fetch_transcript`() = runBlocking {
        every { outbound.statusOf("h2") } returns DelegationStatusResult.Closed("RUNNING", "frontend", 123L)
        val result = tool.execute(statusParams("h2"), project)
        assertFalse(result.isError)
        assertTrue(result.content.contains("\"status\":\"closed\""))
        assertTrue(result.content.contains("fetch_transcript"))
    }

    @Test
    fun `status errors with DelegationHandleNotFound for an unknown handle`() = runBlocking {
        every { outbound.statusOf("ghost") } returns DelegationStatusResult.Unknown
        val result = tool.execute(statusParams("ghost"), project)
        assertTrue(result.isError)
        assertTrue(result.summary.contains("DelegationHandleNotFound") || result.content.contains("DelegationHandleNotFound"))
    }

    @Test
    fun `status requires a handle`() = runBlocking {
        val params = buildJsonObject { put("action", JsonPrimitive("status")) }
        val result = tool.execute(params, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("handle"))
    }
}
