package com.workflow.orchestrator.agent.tools.delegation

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.delegation.DelegationException
import com.workflow.orchestrator.agent.delegation.DelegationHandle
import com.workflow.orchestrator.agent.delegation.DelegationOutboundService
import com.workflow.orchestrator.agent.session.PerSessionAgentState
import com.workflow.orchestrator.agent.testutil.installReadActionInlineShim
import com.workflow.orchestrator.core.settings.PluginSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DelegationSendToolContinueWithTest {

    @BeforeEach
    fun setup() {
        installReadActionInlineShim()
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    private fun makeProject(outbound: DelegationOutboundService, sessionId: String = "sess-a-test"): Project {
        val settings = mockk<PluginSettings>()
        val state = mockk<PluginSettings.State>()
        every { state.enableOutboundCrossIdeDelegation } returns true
        every { settings.state } returns state
        mockkObject(PluginSettings)
        every { PluginSettings.getInstance(any()) } returns settings

        val agentService = mockk<AgentService>(relaxed = true)
        val sessionState = PerSessionAgentState(sessionId)
        every { agentService.currentSessionState() } returns sessionState

        val project = mockk<Project>(relaxed = true)
        every { project.getService(DelegationOutboundService::class.java) } returns outbound
        every { project.getService(AgentService::class.java) } returns agentService
        return project
    }

    @Test
    fun `delegation_send with handle routes through sendContinuation`() = runBlocking {
        val outbound = mockk<DelegationOutboundService>(relaxed = true)
        coEvery {
            outbound.sendContinuation(handleId = "h-x", request = "follow up", delegatorSessionId = any())
        } returns DelegationHandle(
            id = "h-x",
            targetProjectPath = "/repo/b",
            targetRepoName = "frontend",
            lastSeenState = "RUNNING",
        )

        val project = makeProject(outbound)
        val tool = DelegationSendTool()
        val params: JsonObject = buildJsonObject {
            put("handle", JsonPrimitive("h-x"))
            put("request", JsonPrimitive("follow up"))
        }
        val result = tool.execute(params, project)

        assertFalse(result.isError, "expected success: ${result.summary}")
        coVerify(exactly = 1) {
            outbound.sendContinuation(handleId = "h-x", request = "follow up", delegatorSessionId = any())
        }
        coVerify(exactly = 0) { outbound.send(any(), any(), any(), any()) }
    }

    @Test
    fun `delegation_send with handle on dead handle returns DelegationExpired`() = runBlocking {
        val outbound = mockk<DelegationOutboundService>(relaxed = true)
        coEvery {
            outbound.sendContinuation(any(), any(), any())
        } throws DelegationException.Expired("handle_not_found")

        val project = makeProject(outbound)
        val tool = DelegationSendTool()
        val params: JsonObject = buildJsonObject {
            put("handle", JsonPrimitive("h-gone"))
            put("request", JsonPrimitive("anything"))
        }
        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(
            result.summary.contains("Expired") || result.summary.contains("expired"),
            "Expected 'Expired'/'expired' in: ${result.summary}"
        )
    }
}
