package com.workflow.orchestrator.agent.runtime

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.*
import com.workflow.orchestrator.agent.brain.LlmBrain
import com.workflow.orchestrator.agent.context.ContextManager
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class WorkerSessionTest {

    private lateinit var session: WorkerSession
    private lateinit var brain: LlmBrain
    private lateinit var contextManager: ContextManager
    private lateinit var project: Project

    @BeforeEach
    fun setup() {
        session = WorkerSession(maxIterations = 5)
        brain = mockk()
        contextManager = mockk(relaxed = true)
        project = mockk()

        // Default: getMessages returns whatever was added
        every { contextManager.getMessages() } returns listOf(
            ChatMessage(role = "system", content = "You are an analyzer"),
            ChatMessage(role = "user", content = "Analyze this")
        )
    }

    @Test
    fun `execute returns final response when no tool calls`() = runTest {
        coEvery { brain.chat(any(), any(), any(), any()) } returns ApiResult.Success(
            ChatCompletionResponse(
                id = "resp-1",
                choices = listOf(
                    Choice(
                        index = 0,
                        message = ChatMessage(role = "assistant", content = "Analysis complete: no issues found."),
                        finishReason = "stop"
                    )
                ),
                usage = UsageInfo(promptTokens = 100, completionTokens = 20, totalTokens = 120)
            )
        )

        val result = session.execute(
            workerType = WorkerType.ANALYZER,
            systemPrompt = "You are an analyzer",
            task = "Analyze this file",
            tools = emptyMap(),
            toolDefinitions = emptyList(),
            brain = brain,
            contextManager = contextManager,
            project = project
        )

        assertEquals("Analysis complete: no issues found.", result.content)
        assertEquals(120, result.tokensUsed)
        assertTrue(result.artifacts.isEmpty())
        assertFalse(result.isError)
    }

    @Test
    fun `execute handles tool call then final response`() = runTest {
        val mockTool = mockk<AgentTool>()
        coEvery { mockTool.execute(any(), any()) } returns ToolResult(
            content = "File contents: fun main() {}",
            summary = "Read Main.kt",
            tokenEstimate = 10,
            artifacts = listOf("Main.kt")
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
                                    arguments = """{"path": "Main.kt"}"""
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
        val finalResponse = ChatCompletionResponse(
            id = "resp-2",
            choices = listOf(
                Choice(
                    index = 0,
                    message = ChatMessage(role = "assistant", content = "The file looks good."),
                    finishReason = "stop"
                )
            ),
            usage = UsageInfo(promptTokens = 100, completionTokens = 15, totalTokens = 115)
        )

        coEvery { brain.chat(any(), any(), any(), any()) } returnsMany listOf(
            ApiResult.Success(toolCallResponse),
            ApiResult.Success(finalResponse)
        )

        val result = session.execute(
            workerType = WorkerType.ANALYZER,
            systemPrompt = "You are an analyzer",
            task = "Analyze Main.kt",
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

        assertEquals("The file looks good.", result.content)
        assertEquals(195, result.tokensUsed) // 80 + 115
        assertEquals(listOf("Main.kt"), result.artifacts)
        assertFalse(result.isError)

        // Verify tool was executed
        coVerify { mockTool.execute(any(), project) }
        // Verify tool result was added to context
        verify { contextManager.addToolResult("call-1", any(), any()) }
    }

    @Test
    fun `execute handles unknown tool gracefully`() = runTest {
        // LLM calls a tool that doesn't exist
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
                                    name = "nonexistent_tool",
                                    arguments = "{}"
                                )
                            )
                        )
                    ),
                    finishReason = "tool_calls"
                )
            )
        )

        val finalResponse = ChatCompletionResponse(
            id = "resp-2",
            choices = listOf(
                Choice(
                    index = 0,
                    message = ChatMessage(role = "assistant", content = "OK, I'll work without that tool."),
                    finishReason = "stop"
                )
            )
        )

        coEvery { brain.chat(any(), any(), any(), any()) } returnsMany listOf(
            ApiResult.Success(toolCallResponse),
            ApiResult.Success(finalResponse)
        )

        val result = session.execute(
            workerType = WorkerType.ANALYZER,
            systemPrompt = "System",
            task = "Task",
            tools = emptyMap(),
            toolDefinitions = emptyList(),
            brain = brain,
            contextManager = contextManager,
            project = project
        )

        assertEquals("OK, I'll work without that tool.", result.content)
        assertFalse(result.isError) // The session itself completed successfully
        // Verify error was reported to context with available tools info
        verify {
            contextManager.addToolResult(
                "call-1",
                match { it.contains("not available") },
                any()
            )
        }
    }

    @Test
    fun `execute returns error result when brain fails`() = runTest {
        coEvery { brain.chat(any(), any(), any(), any()) } returns ApiResult.Error(
            type = ErrorType.NETWORK_ERROR,
            message = "Connection refused"
        )

        val result = session.execute(
            workerType = WorkerType.ANALYZER,
            systemPrompt = "System",
            task = "Task",
            tools = emptyMap(),
            toolDefinitions = emptyList(),
            brain = brain,
            contextManager = contextManager,
            project = project
        )

        assertTrue(result.content.contains("Connection refused"))
        assertTrue(result.isError)
    }

    @Test
    fun `execute respects max iterations`() = runTest {
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
            )
        )

        coEvery { brain.chat(any(), any(), any(), any()) } returns ApiResult.Success(infiniteToolCall)

        val result = session.execute(
            workerType = WorkerType.CODER,
            systemPrompt = "System",
            task = "Infinite task",
            tools = emptyMap(),
            toolDefinitions = emptyList(),
            brain = brain,
            contextManager = contextManager,
            project = project
        )

        assertTrue(result.content.contains("maximum iterations"))
        assertTrue(result.isError)
    }

    @Test
    fun `execute handles tool execution exception`() = runTest {
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
            )
        )

        val finalResponse = ChatCompletionResponse(
            id = "resp-2",
            choices = listOf(
                Choice(
                    index = 0,
                    message = ChatMessage(role = "assistant", content = "The write failed, but I handled it."),
                    finishReason = "stop"
                )
            )
        )

        coEvery { brain.chat(any(), any(), any(), any()) } returnsMany listOf(
            ApiResult.Success(toolCallResponse),
            ApiResult.Success(finalResponse)
        )

        val result = session.execute(
            workerType = WorkerType.CODER,
            systemPrompt = "System",
            task = "Write a file",
            tools = mapOf("write_file" to failingTool),
            toolDefinitions = emptyList(),
            brain = brain,
            contextManager = contextManager,
            project = project
        )

        assertEquals("The write failed, but I handled it.", result.content)
        assertFalse(result.isError) // Session completed successfully despite tool error
        // Verify error was reported to context
        verify {
            contextManager.addToolResult(
                "call-1",
                match { it.contains("Disk full") },
                any()
            )
        }
    }
}
