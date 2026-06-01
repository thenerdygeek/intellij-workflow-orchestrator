package com.workflow.orchestrator.agent.tools.delegation

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.delegation.DelegationHandle
import com.workflow.orchestrator.agent.delegation.DelegationOutboundService
import com.workflow.orchestrator.agent.session.PerSessionAgentState
import com.workflow.orchestrator.agent.testutil.installReadActionInlineShim
import com.workflow.orchestrator.core.delegation.DelegationMessage
import com.workflow.orchestrator.core.settings.PluginSettings
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * E — the fresh-send success result must narrate HOW the target was chosen:
 *
 *   - suggested_repo provided AND matches targetRepoName   → "(target {repo} as suggested)"
 *   - suggested_repo provided but DIFFERS from targetRepoName → "(you suggested {suggested}; user selected {repo} via the picker)"
 *   - no suggested_repo                                      → "(target {repo} selected via picker)"
 *
 * Tests are fail-before / pass-after (the current code emits none of these notes).
 */
class DelegationSendSuggestedRepoNarrationTest {

    @BeforeEach
    fun setup() { installReadActionInlineShim() }

    @AfterEach
    fun tearDown() { unmockkAll() }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun makeProject(outbound: DelegationOutboundService, sessionId: String = "sess-test"): Project {
        mockkObject(PluginSettings)
        val settings = mockk<PluginSettings>()
        val state = mockk<PluginSettings.State>()
        every { state.enableOutboundCrossIdeDelegation } returns true
        every { settings.state } returns state
        every { PluginSettings.getInstance(any()) } returns settings

        val agentService = mockk<AgentService>(relaxed = true)
        every { agentService.currentSessionState() } returns PerSessionAgentState(sessionId)

        val project = mockk<Project>(relaxed = true)
        every { project.getService(DelegationOutboundService::class.java) } returns outbound
        every { project.getService(AgentService::class.java) } returns agentService
        return project
    }

    private fun outboundReturning(repoName: String): DelegationOutboundService {
        val outbound = mockk<DelegationOutboundService>(relaxed = true)
        val onResultSlot = slot<suspend (DelegationHandle, DelegationMessage.Result) -> Unit>()
        coEvery {
            outbound.send(
                request = any(),
                suggestedRepo = any(),
                delegatorSessionId = any(),
                onResult = capture(onResultSlot),
            )
        } returns DelegationHandle(
            id = "abc12345-0000-0000-0000-000000000000",
            targetProjectPath = "/repos/$repoName",
            targetRepoName = repoName,
        )
        return outbound
    }

    // ── E1: suggested_repo matches targetRepoName → "as suggested" ───────────

    @Test
    fun `send result narrates 'as suggested' when suggested_repo matches targetRepoName`() = runBlocking {
        val outbound = outboundReturning("frontend")
        val project = makeProject(outbound)
        val tool = DelegationTool()

        val result = tool.execute(
            buildJsonObject {
                put("action", JsonPrimitive("send"))
                put("request", JsonPrimitive("Wire the API call"))
                put("suggested_repo", JsonPrimitive("frontend"))
            },
            project,
        )

        assertFalse(result.isError, "send must succeed: ${result.summary}")
        assertTrue(
            result.content.contains("as suggested"),
            "Content must contain 'as suggested' when suggestion matches; got: ${result.content}"
        )
    }

    // ── E2: suggested_repo differs from targetRepoName → "user selected … via picker" ──

    @Test
    fun `send result narrates 'user selected via picker' when suggested_repo differs from targetRepoName`() = runBlocking {
        // User suggested "frontend" but selected "backend" via the picker.
        val outbound = outboundReturning("backend")
        val project = makeProject(outbound)
        val tool = DelegationTool()

        val result = tool.execute(
            buildJsonObject {
                put("action", JsonPrimitive("send"))
                put("request", JsonPrimitive("Implement the endpoint"))
                put("suggested_repo", JsonPrimitive("frontend"))
            },
            project,
        )

        assertFalse(result.isError, "send must succeed: ${result.summary}")
        val content = result.content
        assertTrue(
            content.contains("user selected") && content.contains("via the picker"),
            "Content must contain 'user selected … via the picker' when suggestion differs; got: $content"
        )
        assertTrue(
            content.contains("frontend"),
            "Content must echo the original suggestion 'frontend'; got: $content"
        )
    }

    // ── E3: no suggested_repo → "selected via picker" ────────────────────────

    @Test
    fun `send result narrates 'selected via picker' when no suggested_repo provided`() = runBlocking {
        val outbound = outboundReturning("payments")
        val project = makeProject(outbound)
        val tool = DelegationTool()

        val result = tool.execute(
            buildJsonObject {
                put("action", JsonPrimitive("send"))
                put("request", JsonPrimitive("Add retry logic to the payments service"))
                // No suggested_repo
            },
            project,
        )

        assertFalse(result.isError, "send must succeed: ${result.summary}")
        assertTrue(
            result.content.contains("selected via picker"),
            "Content must contain 'selected via picker' when no suggestion given; got: ${result.content}"
        )
    }

    // ── E4: backward-compat — existing JSON keys untouched ───────────────────

    @Test
    fun `send result still contains handle, status, and repo JSON keys`() = runBlocking {
        val outbound = outboundReturning("auth")
        val project = makeProject(outbound)
        val tool = DelegationTool()

        val result = tool.execute(
            buildJsonObject {
                put("action", JsonPrimitive("send"))
                put("request", JsonPrimitive("Implement JWT refresh"))
            },
            project,
        )

        assertFalse(result.isError)
        assertTrue(result.content.contains("\"handle\":"), "handle key must be present")
        assertTrue(result.content.contains("\"status\":"), "status key must be present")
        assertTrue(result.content.contains("\"repo\":"), "repo key must be present")
    }
}
