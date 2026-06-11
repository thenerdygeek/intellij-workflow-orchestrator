package com.workflow.orchestrator.agent.tools.ide

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.ui.AgentControllerRegistry
import com.workflow.orchestrator.agent.walkthrough.StepValidation
import com.workflow.orchestrator.agent.walkthrough.WalkthroughFeedback
import com.workflow.orchestrator.agent.walkthrough.WalkthroughServiceApi
import com.workflow.orchestrator.agent.walkthrough.WalkthroughStep
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WalkthroughToolTest {

    private class FakeService : WalkthroughServiceApi {
        val calls = mutableListOf<String>()
        var feedback = WalkthroughFeedback(
            true,
            "Tour \"T\": 1 steps queued (queue complete: no), user is on step 1.",
        )
        override fun startTour(title: String?, steps: List<WalkthroughStep>): WalkthroughFeedback {
            calls += "start:$title:${steps.size}"
            return feedback
        }
        override fun appendSteps(steps: List<WalkthroughStep>): WalkthroughFeedback {
            calls += "append:${steps.size}"
            return feedback
        }
        override fun finishTour(): WalkthroughFeedback {
            calls += "finish"
            return feedback
        }
        override fun deliverAnswer(bodyMarkdown: String): WalkthroughFeedback {
            calls += "answer:$bodyMarkdown"
            return feedback
        }
    }

    private val fakeService = FakeService()
    private val project = mockk<Project>(relaxed = true)

    private fun tool(
        guard: (Project) -> String? = { null },
        validator: suspend (Project, List<WalkthroughStep>) -> StepValidation =
            { _, steps -> StepValidation(steps, emptyList()) },
    ) = WalkthroughTool(
        serviceProvider = { fakeService },
        stepValidator = validator,
        interactiveGuard = guard,
        edtDispatcherOverride = kotlinx.coroutines.Dispatchers.Unconfined,
    )

    private fun params(vararg pairs: Pair<String, String>) =
        JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) })

    private val oneStepJson =
        """[{"file": "A.kt", "start_line": 1, "end_line": 3, "body_md": "hello"}]"""

    @Test
    fun `schema declares all four params and orchestrator-only workers`() {
        val t = tool()
        assertEquals("walkthrough", t.name)
        assertEquals(setOf("action", "title", "steps", "body_md"), t.parameters.properties.keys)
        assertEquals(listOf("action"), t.parameters.required)
        assertEquals(setOf(WorkerType.ORCHESTRATOR), t.allowedWorkers)
        assertTrue(t.description.contains("append"), "description must teach the streaming pattern")
    }

    @Test
    fun `start parses steps-as-string and forwards to the service`() = runTest {
        val result = tool().execute(params("action" to "start", "title" to "T", "steps" to oneStepJson), project)
        assertFalse(result.isError)
        assertEquals("start:T:1", fakeService.calls.single())
        assertTrue(result.content.contains("user is on step 1"))
    }

    @Test
    fun `append and finish and answer dispatch`() = runTest {
        tool().execute(params("action" to "append", "steps" to oneStepJson), project)
        tool().execute(params("action" to "finish"), project)
        tool().execute(params("action" to "answer", "body_md" to "Because."), project)
        assertEquals(listOf("append:1", "finish", "answer:Because."), fakeService.calls)
    }

    @Test
    fun `unknown or missing action is an error`() = runTest {
        assertTrue(tool().execute(params("action" to "dance"), project).isError)
        assertTrue(tool().execute(params(), project).isError)
    }

    @Test
    fun `interactive guard blocks before anything else`() = runTest {
        val result = tool(guard = { "walkthrough requires the interactive agent chat" })
            .execute(params("action" to "start", "steps" to oneStepJson), project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("interactive"))
        assertTrue(fakeService.calls.isEmpty())
    }

    @Test
    fun `all steps invalid is an error and does not reach the service`() = runTest {
        val result = tool(validator = { _, _ -> StepValidation(emptyList(), listOf("step 1: file not found: A.kt")) })
            .execute(params("action" to "start", "steps" to oneStepJson), project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("file not found"))
        assertTrue(fakeService.calls.isEmpty())
    }

    @Test
    fun `partially invalid steps are kept and errors itemized in the result`() = runTest {
        val twoSteps =
            """[{"file": "A.kt", "start_line": 1, "end_line": 3, "body_md": "ok"},
                {"file": "GONE.kt", "start_line": 1, "end_line": 3, "body_md": "gone"}]"""
        val result = tool(validator = { _, steps ->
            StepValidation(steps.filter { it.file == "A.kt" }, listOf("step 2: file not found: GONE.kt"))
        }).execute(params("action" to "append", "steps" to twoSteps), project)
        assertFalse(result.isError)
        assertEquals("append:1", fakeService.calls.single())
        assertTrue(result.content.contains("step 2: file not found"))
    }

    @Test
    fun `answer without body_md is an error`() = runTest {
        assertTrue(tool().execute(params("action" to "answer"), project).isError)
    }

    @Test
    fun `blank steps param on start is a parse error and never reaches the service`() = runTest {
        val result = tool().execute(params("action" to "start", "steps" to ""), project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("JSON array"), result.content)
        assertTrue(fakeService.calls.isEmpty())
    }

    @Test
    fun `append with all-invalid steps preserves the live tour (service untouched)`() = runTest {
        val result = tool(validator = { _, _ -> StepValidation(emptyList(), listOf("step 1: file not found: X.kt")) })
            .execute(params("action" to "append", "steps" to oneStepJson), project)
        assertTrue(result.isError)
        assertTrue(fakeService.calls.isEmpty())
    }

    @Test
    fun `default guard reports missing controller gracefully instead of throwing`() = runTest {
        // Real defaultInteractiveGuard against a registry with no controller attached.
        every { project.getService(AgentControllerRegistry::class.java) } returns AgentControllerRegistry()
        val result = WalkthroughTool(
            serviceProvider = { fakeService },
            edtDispatcherOverride = kotlinx.coroutines.Dispatchers.Unconfined,
        ).execute(params("action" to "start", "steps" to oneStepJson), project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("no controller attached"), result.content)
        assertTrue(fakeService.calls.isEmpty())
    }
}
