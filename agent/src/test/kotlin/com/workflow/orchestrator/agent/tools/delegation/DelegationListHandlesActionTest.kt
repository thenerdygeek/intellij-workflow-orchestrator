package com.workflow.orchestrator.agent.tools.delegation

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.delegation.DelegationOutboundService
import com.workflow.orchestrator.agent.delegation.HandleSummary
import com.workflow.orchestrator.agent.session.PerSessionAgentState
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
 * PART 1 — tool-level coverage for `delegation(action="list_handles")`: enumerates the
 * delegation handles IDE-A currently holds for the ACTIVE session (active + retained),
 * returning a compact JSON list plus a short human summary. Empty list → a clear
 * "no active or retained delegations in this session" message. Mirrors
 * [DelegationStatusActionTest]'s setup.
 */
class DelegationListHandlesActionTest {

    private val outbound = mockk<DelegationOutboundService>(relaxed = true)
    private val agentService = mockk<AgentService>(relaxed = true)
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
        every { project.getService(AgentService::class.java) } returns agentService
        // Active session id resolution mirrors handleSend's currentSessionState()?.sessionId.
        every { agentService.currentSessionState() } returns PerSessionAgentState("sessA")
    }

    @AfterEach fun tearDown() { unmockkAll() }

    private fun listHandlesParams(): JsonObject = buildJsonObject {
        put("action", JsonPrimitive("list_handles"))
    }

    @Test
    fun `list_handles returns the JSON list and a summary for the current session`() = runBlocking {
        every { outbound.handlesForSession("sessA") } returns listOf(
            HandleSummary("h1", "backend", "/repos/backend", "b-1", "RUNNING"),
            HandleSummary("h2", "frontend", "/repos/frontend", "b-2", "COMPLETED"),
        )
        val result = tool.execute(listHandlesParams(), project)
        assertFalse(result.isError)
        // JSON list shape.
        assertTrue(result.content.contains("\"handle\":\"h1\""), result.content)
        assertTrue(result.content.contains("\"repo\":\"backend\""))
        assertTrue(result.content.contains("\"project_path\":\"/repos/backend\""))
        assertTrue(result.content.contains("\"last_state\":\"RUNNING\""))
        assertTrue(result.content.contains("\"handle\":\"h2\""))
        assertTrue(result.content.contains("COMPLETED"))
        // Human summary mentions the count.
        assertTrue(result.summary.contains("2"))
    }

    @Test
    fun `list_handles reports a clear no-delegations message for an empty session`() = runBlocking {
        every { outbound.handlesForSession("sessA") } returns emptyList()
        val result = tool.execute(listHandlesParams(), project)
        assertFalse(result.isError)
        assertTrue(
            result.content.contains("no active or retained delegations", ignoreCase = true),
            result.content,
        )
    }

    @Test
    fun `list_handles errors when there is no active session`() = runBlocking {
        every { agentService.currentSessionState() } returns null
        every { outbound.handlesForSession(any()) } returns emptyList()
        val result = tool.execute(listHandlesParams(), project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("session", ignoreCase = true))
    }
}
