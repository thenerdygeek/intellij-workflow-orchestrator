package com.workflow.orchestrator.agent.tools.delegation

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.delegation.DelegationOutboundService
import com.workflow.orchestrator.agent.delegation.DelegationWaitOutcome
import com.workflow.orchestrator.agent.testutil.installReadActionInlineShim
import com.workflow.orchestrator.core.delegation.DelegationMessage
import com.workflow.orchestrator.core.settings.PluginSettings
import io.mockk.coEvery
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

/** Formatting + error-mapping coverage for the `delegation(action="wait")` tool handler. */
class DelegationWaitActionTest {

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

    private fun waitParams(handle: String): JsonObject = buildJsonObject {
        put("action", JsonPrimitive("wait"))
        put("handle", JsonPrimitive(handle))
    }

    @Test
    fun `wait returns the result inline when the delegation completes`() = runBlocking {
        coEvery { outbound.awaitResult("h", any()) } returns DelegationWaitOutcome.Completed(
            DelegationMessage.Result(status = DelegationMessage.ResultStatus.COMPLETED, summary = "did the thing"),
            "backend",
        )
        val result = tool.execute(waitParams("h"), project)
        assertFalse(result.isError)
        assertTrue(result.content.contains("DELEGATION RESULT"))
        assertTrue(result.content.contains("did the thing"))
    }

    @Test
    fun `wait surfaces a clarifying question with answer instructions`() = runBlocking {
        coEvery { outbound.awaitResult("h", any()) } returns DelegationWaitOutcome.Question(
            DelegationMessage.Question(questionId = "q1", text = "Which env?", options = listOf("dev", "prod")),
            "backend",
        )
        val result = tool.execute(waitParams("h"), project)
        assertFalse(result.isError)
        assertTrue(result.content.contains("Which env?"))
        assertTrue(result.content.contains("action=\"answer\""))
        assertTrue(result.content.contains("q1"))
    }

    @Test
    fun `wait timeout is not an error and points to auto-delivery`() = runBlocking {
        coEvery { outbound.awaitResult("h", any()) } returns DelegationWaitOutcome.TimedOut("h", "backend")
        val result = tool.execute(waitParams("h"), project)
        assertFalse(result.isError)
        assertTrue(result.content.contains("still running"))
    }

    @Test
    fun `wait on an already-completed handle points to fetch_transcript`() = runBlocking {
        coEvery { outbound.awaitResult("h", any()) } returns DelegationWaitOutcome.NotActive("already_completed")
        val result = tool.execute(waitParams("h"), project)
        assertFalse(result.isError)
        assertTrue(result.content.contains("fetch_transcript"))
    }

    @Test
    fun `wait on an unknown handle errors`() = runBlocking {
        coEvery { outbound.awaitResult("h", any()) } returns DelegationWaitOutcome.NotActive("handle_not_found")
        val result = tool.execute(waitParams("h"), project)
        assertTrue(result.isError)
        assertTrue(result.summary.contains("DelegationHandleNotFound") || result.content.contains("DelegationHandleNotFound"))
    }

    @Test
    fun `wait requires a handle`() = runBlocking {
        val result = tool.execute(buildJsonObject { put("action", JsonPrimitive("wait")) }, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("handle"))
    }
}
