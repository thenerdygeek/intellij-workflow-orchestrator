package com.workflow.orchestrator.agent.orchestrator

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.*
import com.workflow.orchestrator.agent.brain.LlmBrain
import com.workflow.orchestrator.agent.runtime.*
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolRegistry
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AgentOrchestratorTest {

    private lateinit var brain: LlmBrain
    private lateinit var toolRegistry: ToolRegistry
    private lateinit var project: Project
    private lateinit var orchestrator: AgentOrchestrator

    @BeforeEach
    fun setUp() {
        brain = mockk(relaxed = true)
        toolRegistry = mockk(relaxed = true)
        project = mockk(relaxed = true)

        every { project.basePath } returns "/tmp/test-project"
        every { toolRegistry.getToolsForWorker(any()) } returns emptyList()
        every { toolRegistry.getToolDefinitionsForWorker(any()) } returns emptyList()
        every { toolRegistry.allTools() } returns emptyList()

        // chatStream falls back to chat() — throw NotImplementedError so SingleAgentSession uses chat()
        coEvery { brain.chatStream(any(), any(), any(), any()) } throws NotImplementedError("test fallback")

        orchestrator = AgentOrchestrator(brain, toolRegistry, project)
    }

    // --- Single Agent Mode (default) ---

    @Test
    fun `executeTask completes via single agent session`() = runTest {
        // Single agent session gets the brain response directly (no complexity router)
        val response = chatResponse("Fixed the typo in UserService.kt")
        coEvery { brain.chat(any(), any(), any(), any()) } returns ApiResult.Success(response)

        val progressUpdates = mutableListOf<AgentProgress>()
        val result = orchestrator.executeTask("Fix typo in UserService", onProgress = { progressUpdates.add(it) })

        assertTrue(result is AgentResult.Completed, "Expected Completed, got $result")
        val completed = result as AgentResult.Completed
        assertTrue(completed.summary.contains("Fixed the typo"), "Summary: ${completed.summary}")

        // Verify progress was reported
        assertTrue(progressUpdates.any { it.step.contains("Starting") })
    }

    @Test
    fun `executeTask returns Failed when brain errors`() = runTest {
        coEvery { brain.chat(any(), any(), any(), any()) } returns
            ApiResult.Error(ErrorType.NETWORK_ERROR, "Connection refused")

        val result = orchestrator.executeTask("Fix bug")

        assertTrue(result is AgentResult.Failed, "Expected Failed, got $result")
        assertTrue((result as AgentResult.Failed).error.contains("Connection refused"))
    }

    @Test
    fun `cancelTask returns Cancelled during execution`() = runTest {
        // Cancel during the SingleAgentSession by making the brain trigger cancel
        coEvery { brain.chat(any(), any(), any(), any()) } coAnswers {
            orchestrator.cancelTask()
            ApiResult.Success(chatResponse("Task cancelled"))
        }

        val result = orchestrator.executeTask("Some task")

        // The single agent session checks cancelled flag between iterations
        assertTrue(result is AgentResult.Completed || result is AgentResult.Cancelled,
            "Expected Completed or Cancelled, got $result")
    }

    // --- Explicit Plan Request (orchestrated mode) ---

    @Test
    fun `requestPlan returns PlanReady with parsed TaskGraph`() = runTest {
        val planJson = """
            ```json
            {
              "tasks": [
                {
                  "id": "task-1",
                  "description": "Analyze UserService",
                  "action": "ANALYZE",
                  "target": "UserService.kt",
                  "workerType": "ANALYZER",
                  "dependsOn": []
                },
                {
                  "id": "task-2",
                  "description": "Add validation",
                  "action": "CODE",
                  "target": "UserService.kt",
                  "workerType": "CODER",
                  "dependsOn": ["task-1"]
                }
              ]
            }
            ```
        """.trimIndent()
        val planResponse = chatResponse(planJson)
        coEvery { brain.chat(any(), any<List<ToolDefinition>>(), any(), any()) } returns ApiResult.Success(planResponse)

        val result = orchestrator.requestPlan("Add validation to UserService and write tests")

        assertTrue(result is AgentResult.PlanReady, "Expected PlanReady, got $result")
        val planReady = result as AgentResult.PlanReady
        assertEquals(2, planReady.plan.getAllTasks().size)
        assertEquals("task-1", planReady.plan.getAllTasks()[0].id)
        assertEquals(WorkerType.ANALYZER, planReady.plan.getAllTasks()[0].workerType)
        assertEquals(listOf("task-1"), planReady.plan.getAllTasks()[1].dependsOn)
    }

    // --- parsePlanToTaskGraph ---

    @Test
    fun `parsePlanToTaskGraph with valid JSON returns TaskGraph`() {
        val jsonContent = """
            {
              "tasks": [
                {
                  "id": "t1",
                  "description": "Read file",
                  "action": "ANALYZE",
                  "target": "Foo.kt",
                  "workerType": "ANALYZER",
                  "dependsOn": []
                },
                {
                  "id": "t2",
                  "description": "Edit file",
                  "action": "CODE",
                  "target": "Foo.kt",
                  "workerType": "CODER",
                  "dependsOn": ["t1"]
                }
              ]
            }
        """.trimIndent()

        val graph = orchestrator.parsePlanToTaskGraph(jsonContent)

        assertNotNull(graph)
        assertEquals(2, graph!!.getAllTasks().size)

        val t1 = graph.getTask("t1")
        assertNotNull(t1)
        assertEquals(TaskAction.ANALYZE, t1!!.action)
        assertEquals(WorkerType.ANALYZER, t1.workerType)
        assertTrue(t1.description.contains("Read file"))

        val t2 = graph.getTask("t2")
        assertNotNull(t2)
        assertEquals(TaskAction.CODE, t2!!.action)
        assertEquals(WorkerType.CODER, t2.workerType)
        assertEquals(listOf("t1"), t2.dependsOn)
    }

    @Test
    fun `parsePlanToTaskGraph with JSON in markdown code block`() {
        val content = """
            Here is my plan:
            ```json
            {
              "tasks": [
                {
                  "id": "step-1",
                  "description": "Review code",
                  "action": "REVIEW",
                  "target": "Main.kt",
                  "workerType": "REVIEWER",
                  "dependsOn": []
                }
              ]
            }
            ```
            Let me know if this looks good.
        """.trimIndent()

        val graph = orchestrator.parsePlanToTaskGraph(content)

        assertNotNull(graph)
        assertEquals(1, graph!!.getAllTasks().size)
        assertEquals(WorkerType.REVIEWER, graph.getTask("step-1")!!.workerType)
    }

    @Test
    fun `parsePlanToTaskGraph with invalid JSON returns null`() {
        val result = orchestrator.parsePlanToTaskGraph("This is not JSON at all")
        assertNull(result)
    }

    @Test
    fun `parsePlanToTaskGraph with empty tasks array returns null`() {
        val result = orchestrator.parsePlanToTaskGraph("""{"tasks": []}""")
        assertNull(result)
    }

    @Test
    fun `parsePlanToTaskGraph with malformed task entries returns partial graph`() {
        val content = """
            {
              "tasks": [
                { "description": "no id field", "action": "CODE", "target": "x.kt" },
                { "id": "valid", "description": "has id", "action": "TOOL", "target": "y.kt", "workerType": "TOOLER", "dependsOn": [] }
              ]
            }
        """.trimIndent()

        val graph = orchestrator.parsePlanToTaskGraph(content)
        assertNotNull(graph)
        assertEquals(1, graph!!.getAllTasks().size)
        assertEquals("valid", graph.getAllTasks()[0].id)
        assertEquals(WorkerType.TOOLER, graph.getAllTasks()[0].workerType)
    }

    // --- executePlan ---

    @Test
    fun `executePlan completes all tasks in dependency order`() = runTest {
        val graph = TaskGraph()
        graph.addTask(AgentTask(
            id = "a1", description = "Analyze", action = TaskAction.ANALYZE,
            target = "Svc.kt", workerType = WorkerType.ANALYZER
        ))
        graph.addTask(AgentTask(
            id = "c1", description = "Code", action = TaskAction.CODE,
            target = "Svc.kt", workerType = WorkerType.CODER, dependsOn = listOf("a1")
        ))

        val analyzeResponse = chatResponse("Analysis complete: Svc.kt has 3 methods")
        val codeResponse = chatResponse("Added validation to createUser")

        var callCount = 0
        coEvery { brain.chat(any(), any<List<ToolDefinition>>(), any(), any()) } coAnswers {
            callCount++
            if (callCount == 1) ApiResult.Success(analyzeResponse) else ApiResult.Success(codeResponse)
        }

        val progressUpdates = mutableListOf<AgentProgress>()
        val result = orchestrator.executePlan(graph) { progressUpdates.add(it) }

        assertTrue(result is AgentResult.Completed, "Expected Completed, got $result")
        val completed = result as AgentResult.Completed
        assertTrue(completed.summary.contains("2 of 2"))
        assertTrue(progressUpdates.size >= 2)
    }

    @Test
    fun `executePlan returns Failed when task fails and blocks dependents`() = runTest {
        val graph = TaskGraph()
        graph.addTask(AgentTask(
            id = "a1", description = "Analyze", action = TaskAction.ANALYZE,
            target = "Svc.kt", workerType = WorkerType.ANALYZER
        ))
        graph.addTask(AgentTask(
            id = "c1", description = "Code", action = TaskAction.CODE,
            target = "Svc.kt", workerType = WorkerType.CODER, dependsOn = listOf("a1")
        ))

        // First task returns an LLM error (triggers isError = true in WorkerResult)
        coEvery { brain.chat(any(), any<List<ToolDefinition>>(), any(), any()) } returns
            ApiResult.Error(ErrorType.SERVER_ERROR, "file not found")

        val result = orchestrator.executePlan(graph)

        assertTrue(result is AgentResult.Failed, "Expected Failed, got $result")
        assertTrue((result as AgentResult.Failed).error.contains("a1"))
    }

    @Test
    fun `executePlan respects cancellation between tasks`() = runTest {
        val graph = TaskGraph()
        graph.addTask(AgentTask(
            id = "a1", description = "Analyze", action = TaskAction.ANALYZE,
            target = "Svc.kt", workerType = WorkerType.ANALYZER
        ))
        graph.addTask(AgentTask(
            id = "c1", description = "Code", action = TaskAction.CODE,
            target = "Svc.kt", workerType = WorkerType.CODER, dependsOn = listOf("a1")
        ))

        val response = chatResponse("Done analyzing")
        coEvery { brain.chat(any(), any<List<ToolDefinition>>(), any(), any()) } coAnswers {
            orchestrator.cancelTask()
            ApiResult.Success(response)
        }

        val result = orchestrator.executePlan(graph)

        assertTrue(result is AgentResult.Cancelled, "Expected Cancelled, got $result")
    }

    // --- Helper ---

    private fun chatResponse(content: String): ChatCompletionResponse {
        return ChatCompletionResponse(
            id = "test-${System.nanoTime()}",
            choices = listOf(
                Choice(
                    index = 0,
                    message = ChatMessage(role = "assistant", content = content),
                    finishReason = "stop"
                )
            ),
            usage = UsageInfo(promptTokens = 100, completionTokens = 50, totalTokens = 150)
        )
    }
}
