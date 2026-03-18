package com.workflow.orchestrator.agent.runtime

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.*
import com.workflow.orchestrator.agent.brain.LlmBrain
import com.workflow.orchestrator.agent.context.ContextManager
import com.workflow.orchestrator.agent.orchestrator.AgentProgress
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SingleAgentSessionTest {

    private lateinit var session: SingleAgentSession
    private lateinit var brain: LlmBrain
    private lateinit var contextManager: ContextManager
    private lateinit var project: Project

    @BeforeEach
    fun setup() {
        session = SingleAgentSession(maxIterations = 5)
        brain = mockk()
        contextManager = mockk(relaxed = true)
        project = mockk()

        // Default: getMessages returns whatever was added
        every { contextManager.getMessages() } returns listOf(
            ChatMessage(role = "system", content = "You are an AI coding assistant"),
            ChatMessage(role = "user", content = "Do something")
        )
        every { contextManager.currentTokens } returns 1000
        every { contextManager.remainingBudget() } returns 149_000

        // chatStream falls back to chat() via NotImplementedError catch
        coEvery { brain.chatStream(any(), any(), any(), any()) } throws NotImplementedError("test fallback")
    }

    @Test
    fun `simple task completes in single session`() = runTest {
        coEvery { brain.chat(any(), any(), any(), any()) } returns ApiResult.Success(
            chatResponse("Task completed successfully. I fixed the bug in UserService.kt.")
        )

        val result = session.execute(
            task = "Fix the bug in UserService",
            tools = emptyMap(),
            toolDefinitions = emptyList(),
            brain = brain,
            contextManager = contextManager,
            project = project
        )

        assertTrue(result is SingleAgentResult.Completed, "Expected Completed, got $result")
        val completed = result as SingleAgentResult.Completed
        assertTrue(completed.content.contains("Task completed successfully"))
        assertEquals(150, completed.tokensUsed)
        assertTrue(completed.artifacts.isEmpty())
    }

    @Test
    fun `tool calls execute and results feed back`() = runTest {
        val mockTool = mockk<AgentTool>()
        coEvery { mockTool.execute(any(), any()) } returns ToolResult(
            content = "File contents: class UserService { fun create() {} }",
            summary = "Read UserService.kt",
            tokenEstimate = 15,
            artifacts = listOf("UserService.kt")
        )

        // First call: LLM requests a tool call
        val toolCallResponse = ChatCompletionResponse(
            id = "resp-1",
            choices = listOf(
                Choice(
                    index = 0,
                    message = ChatMessage(
                        role = "assistant",
                        content = null,
                        toolCalls = listOf(
                            ToolCall(
                                id = "call-1",
                                function = FunctionCall(
                                    name = "read_file",
                                    arguments = """{"path": "UserService.kt"}"""
                                )
                            )
                        )
                    ),
                    finishReason = "tool_calls"
                )
            ),
            usage = UsageInfo(promptTokens = 50, completionTokens = 30, totalTokens = 80)
        )

        // Second call: LLM returns final response
        val finalResponse = chatResponse("I've analyzed the file. The service looks correct.")

        coEvery { brain.chat(any(), any(), any(), any()) } returnsMany listOf(
            ApiResult.Success(toolCallResponse),
            ApiResult.Success(finalResponse)
        )

        val result = session.execute(
            task = "Analyze UserService.kt",
            tools = mapOf("read_file" to mockTool),
            toolDefinitions = listOf(
                ToolDefinition(
                    function = FunctionDefinition(
                        name = "read_file",
                        description = "Read a file",
                        parameters = FunctionParameters(properties = emptyMap())
                    )
                )
            ),
            brain = brain,
            contextManager = contextManager,
            project = project
        )

        assertTrue(result is SingleAgentResult.Completed)
        val completed = result as SingleAgentResult.Completed
        assertTrue(completed.content.contains("analyzed the file"))
        assertEquals(230, completed.tokensUsed) // 80 + 150
        assertEquals(listOf("UserService.kt"), completed.artifacts)

        // Verify tool was executed
        coVerify { mockTool.execute(any(), project) }
        // Verify tool result was added to context
        verify { contextManager.addToolResult("call-1", any(), any()) }
    }

    @Test
    fun `budget escalation when context exceeds threshold`() = runTest {
        // Simulate context already at critical level (over 60%)
        every { contextManager.currentTokens } returns 100_000
        every { contextManager.remainingBudget() } returns 50_000

        val result = session.execute(
            task = "Complex refactoring task",
            tools = emptyMap(),
            toolDefinitions = emptyList(),
            brain = brain,
            contextManager = contextManager,
            project = project
        )

        assertTrue(result is SingleAgentResult.EscalateToOrchestrated, "Expected escalation, got $result")
        val escalation = result as SingleAgentResult.EscalateToOrchestrated
        assertTrue(escalation.reason.contains("budget"))
    }

    @Test
    fun `OutputValidator called on final content`() = runTest {
        // Return content that triggers OutputValidator (a private key header)
        coEvery { brain.chat(any(), any(), any(), any()) } returns ApiResult.Success(
            chatResponse("Here is the result. -----BEGIN RSA PRIVATE KEY----- secret data")
        )

        val result = session.execute(
            task = "Some task",
            tools = emptyMap(),
            toolDefinitions = emptyList(),
            brain = brain,
            contextManager = contextManager,
            project = project
        )

        // Should still complete (validator logs warnings, doesn't block)
        assertTrue(result is SingleAgentResult.Completed)
        val completed = result as SingleAgentResult.Completed
        assertTrue(completed.content.contains("PRIVATE KEY"))
    }

    @Test
    fun `max iterations respected`() = runTest {
        // Brain always returns tool calls, never a final response
        val infiniteToolCall = ChatCompletionResponse(
            id = "resp",
            choices = listOf(
                Choice(
                    index = 0,
                    message = ChatMessage(
                        role = "assistant",
                        content = null,
                        toolCalls = listOf(
                            ToolCall(
                                id = "call",
                                function = FunctionCall(name = "unknown", arguments = "{}")
                            )
                        )
                    ),
                    finishReason = "tool_calls"
                )
            ),
            usage = UsageInfo(promptTokens = 50, completionTokens = 10, totalTokens = 60)
        )

        coEvery { brain.chat(any(), any(), any(), any()) } returns ApiResult.Success(infiniteToolCall)

        val result = session.execute(
            task = "Infinite task",
            tools = emptyMap(),
            toolDefinitions = emptyList(),
            brain = brain,
            contextManager = contextManager,
            project = project
        )

        assertTrue(result is SingleAgentResult.Failed, "Expected Failed, got $result")
        val failed = result as SingleAgentResult.Failed
        assertTrue(failed.error.contains("maximum iterations"))
    }

    @Test
    fun `brain error returns Failed`() = runTest {
        coEvery { brain.chat(any(), any(), any(), any()) } returns ApiResult.Error(
            type = ErrorType.NETWORK_ERROR,
            message = "Connection refused"
        )

        val result = session.execute(
            task = "Some task",
            tools = emptyMap(),
            toolDefinitions = emptyList(),
            brain = brain,
            contextManager = contextManager,
            project = project
        )

        assertTrue(result is SingleAgentResult.Failed, "Expected Failed, got $result")
        val failed = result as SingleAgentResult.Failed
        assertTrue(failed.error.contains("Connection refused"))
    }

    @Test
    fun `progress callback fires for each iteration`() = runTest {
        coEvery { brain.chat(any(), any(), any(), any()) } returns ApiResult.Success(
            chatResponse("Done")
        )

        val progressUpdates = mutableListOf<AgentProgress>()
        session.execute(
            task = "Quick task",
            tools = emptyMap(),
            toolDefinitions = emptyList(),
            brain = brain,
            contextManager = contextManager,
            project = project,
            onProgress = { progressUpdates.add(it) }
        )

        assertTrue(progressUpdates.any { it.step.contains("Thinking") })
        assertTrue(progressUpdates.any { it.step.contains("completed") })
    }

    @Test
    fun `unknown tool includes available tools in error message`() = runTest {
        val mockTool = mockk<AgentTool>()
        coEvery { mockTool.execute(any(), any()) } returns ToolResult(
            content = "result", summary = "summary", tokenEstimate = 5
        )

        val toolCallResponse = ChatCompletionResponse(
            id = "resp-1",
            choices = listOf(
                Choice(
                    index = 0,
                    message = ChatMessage(
                        role = "assistant",
                        content = null,
                        toolCalls = listOf(
                            ToolCall(
                                id = "call-1",
                                function = FunctionCall(name = "nonexistent_tool", arguments = "{}")
                            )
                        )
                    ),
                    finishReason = "tool_calls"
                )
            ),
            usage = UsageInfo(promptTokens = 50, completionTokens = 10, totalTokens = 60)
        )

        val finalResponse = chatResponse("OK, using available tools instead.")

        coEvery { brain.chat(any(), any(), any(), any()) } returnsMany listOf(
            ApiResult.Success(toolCallResponse),
            ApiResult.Success(finalResponse)
        )

        session.execute(
            task = "Task",
            tools = mapOf("read_file" to mockTool),
            toolDefinitions = emptyList(),
            brain = brain,
            contextManager = contextManager,
            project = project
        )

        // Verify error message includes available tools
        verify {
            contextManager.addToolResult(
                "call-1",
                match { it.contains("read_file") && it.contains("not found") },
                any()
            )
        }
    }

    @Test
    fun `tool execution exception is handled gracefully`() = runTest {
        val failingTool = mockk<AgentTool>()
        coEvery { failingTool.execute(any(), any()) } throws RuntimeException("Disk full")

        val toolCallResponse = ChatCompletionResponse(
            id = "resp-1",
            choices = listOf(
                Choice(
                    index = 0,
                    message = ChatMessage(
                        role = "assistant",
                        content = null,
                        toolCalls = listOf(
                            ToolCall(
                                id = "call-1",
                                function = FunctionCall(
                                    name = "write_file",
                                    arguments = """{"path": "out.txt", "content": "data"}"""
                                )
                            )
                        )
                    ),
                    finishReason = "tool_calls"
                )
            ),
            usage = UsageInfo(promptTokens = 50, completionTokens = 10, totalTokens = 60)
        )

        val finalResponse = chatResponse("Write failed but I handled it.")

        coEvery { brain.chat(any(), any(), any(), any()) } returnsMany listOf(
            ApiResult.Success(toolCallResponse),
            ApiResult.Success(finalResponse)
        )

        val result = session.execute(
            task = "Write a file",
            tools = mapOf("write_file" to failingTool),
            toolDefinitions = emptyList(),
            brain = brain,
            contextManager = contextManager,
            project = project
        )

        assertTrue(result is SingleAgentResult.Completed)
        verify {
            contextManager.addToolResult(
                "call-1",
                match { it.contains("Disk full") },
                any()
            )
        }
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
