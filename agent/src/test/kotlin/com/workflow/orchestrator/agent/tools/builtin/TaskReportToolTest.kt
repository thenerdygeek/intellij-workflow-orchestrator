package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.ToolResultType
import com.workflow.orchestrator.agent.tools.WorkerType
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TaskReportToolTest {

    private val project = mockk<Project>(relaxed = true)
    private val tool = TaskReportTool()

    @Test
    fun `tool name is task_report`() {
        assertEquals("task_report", tool.name)
    }

    @Test
    fun `summary is the only required parameter`() {
        assertTrue(tool.parameters.required.contains("summary"))
        assertFalse(tool.parameters.required.contains("findings"))
        assertFalse(tool.parameters.required.contains("files"))
        assertFalse(tool.parameters.required.contains("next_steps"))
        assertFalse(tool.parameters.required.contains("issues"))
    }

    @Test
    fun `allowedWorkers contains subagent roles but not ORCHESTRATOR`() {
        assertTrue(tool.allowedWorkers.contains(WorkerType.CODER))
        assertTrue(tool.allowedWorkers.contains(WorkerType.REVIEWER))
        assertTrue(tool.allowedWorkers.contains(WorkerType.ANALYZER))
        assertTrue(tool.allowedWorkers.contains(WorkerType.TOOLER))
        assertFalse(tool.allowedWorkers.contains(WorkerType.ORCHESTRATOR),
            "task_report must not be available to orchestrator — it uses attempt_completion")
    }

    @Test
    fun `returns Completion type so loop exits`() = runTest {
        val result = tool.execute(buildJsonObject {
            put("summary", "Explored the codebase and found the bug.")
        }, project)

        assertFalse(result.isError)
        assertTrue(result.type is ToolResultType.Completion,
            "task_report must return ToolResultType.Completion so AgentLoop terminates")
    }

    @Test
    fun `missing summary returns error`() = runTest {
        val result = tool.execute(buildJsonObject {}, project)
        assertTrue(result.isError)
        assertFalse(result.type is ToolResultType.Completion)
    }

    @Test
    fun `summary only - content contains Summary section`() = runTest {
        val result = tool.execute(buildJsonObject {
            put("summary", "All done.")
        }, project)

        assertTrue(result.content.contains("## Summary"))
        assertTrue(result.content.contains("All done."))
        assertFalse(result.content.contains("## Findings"))
        assertFalse(result.content.contains("## Files"))
        assertFalse(result.content.contains("## Next Steps"))
        assertFalse(result.content.contains("## Issues"))
    }

    @Test
    fun `optional sections present when fields are provided`() = runTest {
        val result = tool.execute(buildJsonObject {
            put("summary", "Analysis complete.")
            put("findings", "Found 3 issues in Foo.kt.")
            put("files", "src/Foo.kt\nsrc/Bar.kt")
            put("next_steps", "Fix the NullPointerException in Foo.kt:42.")
            put("issues", "Could not access the database.")
        }, project)

        assertTrue(result.content.contains("## Summary"))
        assertTrue(result.content.contains("## Findings"))
        assertTrue(result.content.contains("Found 3 issues in Foo.kt."))
        assertTrue(result.content.contains("## Files"))
        assertTrue(result.content.contains("src/Foo.kt"))
        assertTrue(result.content.contains("## Next Steps"))
        assertTrue(result.content.contains("Fix the NullPointerException"))
        assertTrue(result.content.contains("## Issues"))
        assertTrue(result.content.contains("Could not access the database."))
    }

    @Test
    fun `blank optional fields are omitted`() = runTest {
        val result = tool.execute(buildJsonObject {
            put("summary", "Done.")
            put("findings", "   ")   // blank — should be omitted
            put("issues", "")        // empty — should be omitted
        }, project)

        assertFalse(result.content.contains("## Findings"), "Blank findings should be omitted")
        assertFalse(result.content.contains("## Issues"), "Empty issues should be omitted")
    }

    @Test
    fun `summary is echoed in result summary field`() = runTest {
        val result = tool.execute(buildJsonObject {
            put("summary", "Refactored the authentication module.")
        }, project)

        assertTrue(result.summary.contains("Refactored the authentication module."),
            "ToolResult.summary should contain the summary text for notification display")
    }
}
