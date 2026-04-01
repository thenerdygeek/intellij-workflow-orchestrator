package com.workflow.orchestrator.agent.runtime

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.*
import com.workflow.orchestrator.agent.brain.LlmBrain
import com.workflow.orchestrator.agent.context.EventSourcedContextBridge
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
    private lateinit var bridge: EventSourcedContextBridge
    private lateinit var project: Project

    @BeforeEach
    fun setup() {
        session = SingleAgentSession(maxIterations = 5)
        brain = mockk()
        bridge = mockk(relaxed = true)
        project = mockk()

        // Default: getMessages returns whatever was added
        every { bridge.getMessages() } returns listOf(
            ChatMessage(role = "system", content = "You are an AI coding assistant"),
            ChatMessage(role = "user", content = "Do something")
        )
        every { bridge.currentTokens } returns 1000
        every { bridge.remainingBudget() } returns 149_000

        // chatStream falls back to chat() via NotImplementedError catch
        coEvery { brain.chatStream(any(), any(), any(), any()) } throws NotImplementedError("test fallback")
    }

    @Test
    fun `simple task completes in single session`() = runTest {
        // First response triggers nudge (no tool calls), second response passes gatekeeper
        coEvery { brain.chat(any(), any(), any(), any()) } returns ApiResult.Success(
            chatResponse("Task completed successfully. I fixed the bug in UserService.kt.")
        )

        val result = session.execute(
            task = "Fix the bug in UserService",
            tools = emptyMap(),
            toolDefinitions = emptyList(),
            brain = brain,
            bridge = bridge,
            project = project
        )

        assertTrue(result is SingleAgentResult.Completed, "Expected Completed, got $result")
        val completed = result as SingleAgentResult.Completed
        assertTrue(completed.content.contains("Task completed successfully"))
        // Multiple LLM calls: confused-response detection + nudge + gatekeeper pass
        assertTrue(completed.tokensUsed > 0)
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

        // Second call: LLM returns final response (triggers nudge for attempt_completion since >100 chars)
        // Third call: LLM repeats — passes gatekeeper (no plan, no unverified edits)
        val finalResponse = chatResponse("I've analyzed the file thoroughly. The UserService class looks correct and follows the expected patterns. No changes are needed at this time.")

        coEvery { brain.chat(any(), any(), any(), any()) } returnsMany listOf(
            ApiResult.Success(toolCallResponse),
            ApiResult.Success(finalResponse),
            ApiResult.Success(finalResponse) // second no-tool response passes gatekeeper
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
            bridge = bridge,
            project = project
        )

        assertTrue(result is SingleAgentResult.Completed)
        val completed = result as SingleAgentResult.Completed
        assertTrue(completed.content.contains("analyzed the file"))
        // 80 (tool call) + N*150 (text responses with nudge/gatekeeper)
        assertTrue(completed.tokensUsed >= 380, "Expected at least 380 tokens, got ${completed.tokensUsed}")
        assertEquals(listOf("UserService.kt"), completed.artifacts)

        // Verify tool was executed
        coVerify { mockTool.execute(any(), project) }
        // Verify tool result was added to context
        verify { bridge.addToolResult("call-1", any(), any(), any()) }
    }

    @Test
    fun `budget terminate when context exceeds 90 percent`() = runTest {
        // Simulate context already at terminal level (over 95%)
        // effectiveBudget = currentTokens + remainingBudget = 146_000 + 4_000 = 150_000
        // utilization = 146_000 / 150_000 = 97.3% → TERMINATE (threshold is 95%)
        every { bridge.currentTokens } returns 146_000
        every { bridge.remainingBudget() } returns 4_000
        every { bridge.effectiveMaxInputTokens } returns 150_000

        val result = session.execute(
            task = "Complex refactoring task",
            tools = emptyMap(),
            toolDefinitions = emptyList(),
            brain = brain,
            bridge = bridge,
            project = project
        )

        assertTrue(result is SingleAgentResult.Failed, "Expected Failed, got $result")
        val failed = result as SingleAgentResult.Failed
        assertTrue(failed.error.contains("budget exhausted", ignoreCase = true))
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
            bridge = bridge,
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
            bridge = bridge,
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
            bridge = bridge,
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
            bridge = bridge,
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
            bridge = bridge,
            project = project
        )

        // Verify error message includes available tools
        verify {
            bridge.addToolError(
                "call-1",
                match { it.contains("read_file") && it.contains("not found") },
                any(),
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
            bridge = bridge,
            project = project
        )

        assertTrue(result is SingleAgentResult.Completed)
        verify {
            bridge.addToolError(
                "call-1",
                match { it.contains("Disk full") },
                any(),
                any()
            )
        }
    }

    // --- Step 2: System prompt parameter ---

    @Test
    fun `uses provided system prompt instead of hardcoded one`() = runTest {
        coEvery { brain.chat(any(), any(), any(), any()) } returns ApiResult.Success(
            chatResponse("Done")
        )

        val customPrompt = "You are a custom assistant with repo map context."
        session.execute(
            task = "Do something",
            tools = emptyMap(),
            toolDefinitions = emptyList(),
            brain = brain,
            bridge = bridge,
            project = project,
            systemPrompt = customPrompt
        )

        // Verify the custom system prompt was added to context
        verify {
            bridge.addSystemPrompt(customPrompt)
        }
    }

    // --- Step 3: LoopGuard integration ---

    @Test
    fun `LoopGuard injects messages after tool execution`() = runTest {
        val mockTool = mockk<AgentTool>()
        coEvery { mockTool.execute(any(), any()) } returns ToolResult(
            content = "result", summary = "read file", tokenEstimate = 5
        )

        // Same tool call 3 times (triggers loop detection) + final response
        val toolCallResponse = ChatCompletionResponse(
            id = "resp",
            choices = listOf(Choice(
                index = 0,
                message = ChatMessage(
                    role = "assistant", content = null,
                    toolCalls = listOf(ToolCall(
                        id = "call-1",
                        function = FunctionCall(name = "read_file", arguments = """{"path": "same.kt"}""")
                    ))
                ),
                finishReason = "tool_calls"
            )),
            usage = UsageInfo(promptTokens = 50, completionTokens = 10, totalTokens = 60)
        )

        val finalResponse = chatResponse("OK, trying different approach.")

        // 3 identical tool calls then final response
        coEvery { brain.chat(any(), any(), any(), any()) } returnsMany listOf(
            ApiResult.Success(toolCallResponse),
            ApiResult.Success(toolCallResponse),
            ApiResult.Success(toolCallResponse),
            ApiResult.Success(finalResponse)
        )

        session.execute(
            task = "Fix file",
            tools = mapOf("read_file" to mockTool),
            toolDefinitions = emptyList(),
            brain = brain,
            bridge = bridge,
            project = project
        )

        // LoopGuard should have skipped execution and returned a doom loop tool result
        // checkDoomLoop detects re-reads first ("already read"), then doom loops on 3rd identical call
        verify(atLeast = 1) {
            bridge.addToolError(any(), match { it.contains("already read") || it.contains("same arguments") }, any(), any())
        }
    }

    // --- Step 7: Rate limit retry ---

    @Test
    fun `retries on rate limit then succeeds`() = runTest {
        // First two calls: rate limited. Third: success.
        coEvery { brain.chat(any(), any(), any(), any()) } returnsMany listOf(
            ApiResult.Error(ErrorType.RATE_LIMITED, "Rate limited"),
            ApiResult.Error(ErrorType.RATE_LIMITED, "Rate limited"),
            ApiResult.Success(chatResponse("Done after retry"))
        )

        val result = session.execute(
            task = "Task",
            tools = emptyMap(),
            toolDefinitions = emptyList(),
            brain = brain,
            bridge = bridge,
            project = project
        )

        assertTrue(result is SingleAgentResult.Completed, "Expected Completed after retry, got $result")
    }

    @Test
    fun `retries on server error 5xx then succeeds`() = runTest {
        // First call: server error. Second: success.
        coEvery { brain.chat(any(), any(), any(), any()) } returnsMany listOf(
            ApiResult.Error(ErrorType.SERVER_ERROR, "Internal server error"),
            ApiResult.Success(chatResponse("Done after 5xx retry"))
        )

        val result = session.execute(
            task = "Task",
            tools = emptyMap(),
            toolDefinitions = emptyList(),
            brain = brain,
            bridge = bridge,
            project = project
        )

        assertTrue(result is SingleAgentResult.Completed, "Expected Completed after 5xx retry, got $result")
        val completed = result as SingleAgentResult.Completed
        assertTrue(completed.content.contains("Done after 5xx retry"))
    }

    @Test
    fun `fails after exhausting all retries`() = runTest {
        // All calls: rate limited
        coEvery { brain.chat(any(), any(), any(), any()) } returns ApiResult.Error(
            ErrorType.RATE_LIMITED, "Rate limited"
        )

        val result = session.execute(
            task = "Task",
            tools = emptyMap(),
            toolDefinitions = emptyList(),
            brain = brain,
            bridge = bridge,
            project = project
        )

        assertTrue(result is SingleAgentResult.Failed, "Expected Failed after exhausting retries, got $result")
        val failed = result as SingleAgentResult.Failed
        assertTrue(failed.error.contains("Rate limited") || failed.error.contains("retries"))
    }

    // --- Step 8: Event logging ---

    @Test
    fun `event log records session lifecycle`() = runTest {
        coEvery { brain.chat(any(), any(), any(), any()) } returns ApiResult.Success(
            chatResponse("Done")
        )

        val eventLog = AgentEventLog("test-session", java.io.File(System.getProperty("java.io.tmpdir"), "test-session-lifecycle"))

        session.execute(
            task = "Test task",
            tools = emptyMap(),
            toolDefinitions = emptyList(),
            brain = brain,
            bridge = bridge,
            project = project,
            eventLog = eventLog
        )

        val events = eventLog.getEvents()
        assertTrue(events.any { it.type == AgentEventType.SESSION_STARTED }, "Should log SESSION_STARTED")
        assertTrue(events.any { it.type == AgentEventType.SESSION_COMPLETED }, "Should log SESSION_COMPLETED")
    }

    @Test
    fun `event log records tool calls`() = runTest {
        val mockTool = mockk<AgentTool>()
        coEvery { mockTool.execute(any(), any()) } returns ToolResult(
            content = "file content", summary = "read file", tokenEstimate = 5
        )

        val toolCallResponse = ChatCompletionResponse(
            id = "resp-1",
            choices = listOf(Choice(
                index = 0,
                message = ChatMessage(
                    role = "assistant", content = null,
                    toolCalls = listOf(ToolCall(
                        id = "call-1",
                        function = FunctionCall(name = "read_file", arguments = """{"path": "test.kt"}""")
                    ))
                ),
                finishReason = "tool_calls"
            )),
            usage = UsageInfo(promptTokens = 50, completionTokens = 10, totalTokens = 60)
        )

        coEvery { brain.chat(any(), any(), any(), any()) } returnsMany listOf(
            ApiResult.Success(toolCallResponse),
            ApiResult.Success(chatResponse("Done"))
        )

        val eventLog = AgentEventLog("test-session", java.io.File(System.getProperty("java.io.tmpdir"), "test-session-tools"))

        session.execute(
            task = "Read a file",
            tools = mapOf("read_file" to mockTool),
            toolDefinitions = emptyList(),
            brain = brain,
            bridge = bridge,
            project = project,
            eventLog = eventLog
        )

        val events = eventLog.getEvents()
        assertTrue(events.any { it.type == AgentEventType.TOOL_CALLED }, "Should log TOOL_CALLED")
        assertTrue(events.any { it.type == AgentEventType.TOOL_SUCCEEDED }, "Should log TOOL_SUCCEEDED")
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
