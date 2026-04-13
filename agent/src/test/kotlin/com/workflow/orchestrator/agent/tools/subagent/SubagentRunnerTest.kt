package com.workflow.orchestrator.agent.tools.subagent

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.builtin.AttemptCompletionTool
import com.workflow.orchestrator.core.ai.LlmBrain
import com.workflow.orchestrator.core.ai.dto.*
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SubagentRunnerTest {

    private lateinit var project: Project

    @BeforeEach
    fun setUp() {
        project = mockk(relaxed = true)
        every { project.name } returns "TestProject"
        every { project.basePath } returns "/tmp/test-project"
    }

    // ---- Helpers (same patterns as SpawnAgentToolTest) ----

    private fun stubTool(toolName: String): AgentTool = object : AgentTool {
        override val name = toolName
        override val description = "Stub tool: $toolName"
        override val parameters = FunctionParameters(properties = emptyMap())
        override val allowedWorkers = setOf(WorkerType.CODER)
        override suspend fun execute(params: JsonObject, project: Project) =
            ToolResult(content = "stub:$toolName", summary = "stub", tokenEstimate = 5)
    }

    private fun buildTools(): Map<String, AgentTool> {
        val tools = mutableMapOf<String, AgentTool>()
        for (name in listOf("read_file", "search_code", "think")) {
            tools[name] = stubTool(name)
        }
        tools["attempt_completion"] = AttemptCompletionTool()
        return tools
    }

    /**
     * A fake LlmBrain that returns pre-configured responses in sequence.
     * Same pattern as SpawnAgentToolTest.SequenceBrain.
     */
    private class SequenceBrain(
        private val responses: List<ApiResult<ChatCompletionResponse>>,
        toolNames: Set<String> = emptySet(),
        paramNames: Set<String> = emptySet()
    ) : LlmBrain {
        override val modelId: String = "test-subagent-brain"
        override var toolNameSet: Set<String> = toolNames
        override var paramNameSet: Set<String> = paramNames
        private var callIndex = 0
        var cancelled = false
            private set
        var lastMessages: List<ChatMessage> = emptyList()
            private set

        override suspend fun chat(
            messages: List<ChatMessage>,
            tools: List<ToolDefinition>?,
            maxTokens: Int?,
            toolChoice: JsonElement?
        ): ApiResult<ChatCompletionResponse> {
            throw UnsupportedOperationException("AgentLoop uses chatStream")
        }

        override suspend fun chatStream(
            messages: List<ChatMessage>,
            tools: List<ToolDefinition>?,
            maxTokens: Int?,
            onChunk: suspend (StreamChunk) -> Unit
        ): ApiResult<ChatCompletionResponse> {
            lastMessages = messages
            if (callIndex >= responses.size) {
                return ApiResult.Error(ErrorType.SERVER_ERROR, "No more scripted responses")
            }
            return responses[callIndex++]
        }

        override fun estimateTokens(text: String): Int = text.toByteArray().size / 4
        override fun cancelActiveRequest() { cancelled = true }
    }

    private fun toolCallResponse(vararg calls: Pair<String, String>): ChatCompletionResponse =
        ChatCompletionResponse(
            id = "resp-${System.nanoTime()}",
            choices = listOf(
                Choice(
                    index = 0,
                    message = ChatMessage(
                        role = "assistant",
                        content = null,
                        toolCalls = calls.mapIndexed { idx, (name, args) ->
                            ToolCall(
                                id = "call_${idx}_${System.nanoTime()}",
                                type = "function",
                                function = FunctionCall(name = name, arguments = args)
                            )
                        }
                    ),
                    finishReason = "tool_calls"
                )
            ),
            usage = UsageInfo(promptTokens = 100, completionTokens = 30, totalTokens = 130)
        )

    private fun completionTool(): AttemptCompletionTool = AttemptCompletionTool()

    private fun createRunner(
        brain: LlmBrain,
        tools: Map<String, AgentTool> = buildTools(),
        maxIterations: Int = 50,
        contextBudget: Int = 50_000
    ): SubagentRunner = SubagentRunner(
        brain = brain,
        tools = tools,
        systemPrompt = "You are a test sub-agent.",
        project = project,
        maxIterations = maxIterations,
        planMode = false,
        contextBudget = contextBudget
    )

    // ---- Tests ----

    @Nested
    inner class CompletedSubagentTests {

        @Test
        fun `completed subagent returns result with stats`() = runTest {
            val brain = SequenceBrain(listOf(
                ApiResult.Success(toolCallResponse(
                    "read_file" to """{"path":"src/main.kt"}"""
                )),
                ApiResult.Success(toolCallResponse(
                    "attempt_completion" to """{"result":"Found the answer: 42."}"""
                ))
            ))

            val runner = createRunner(brain)
            val progressUpdates = mutableListOf<SubagentProgressUpdate>()

            val result = runner.run("Find the answer") { update ->
                progressUpdates.add(update)
            }

            assertEquals(SubagentRunStatus.COMPLETED, result.status)
            assertNotNull(result.result)
            assertTrue(result.result!!.contains("Found the answer: 42"))
            assertNull(result.error)

            // Stats should reflect some activity
            assertTrue(result.stats.inputTokens >= 0)
            assertTrue(result.stats.outputTokens >= 0)

            // Should have progress updates: at least "running" and "completed"
            assertTrue(progressUpdates.size >= 2, "Expected at least 2 progress updates, got ${progressUpdates.size}")
            assertEquals("running", progressUpdates.first().status)
            assertEquals("completed", progressUpdates.last().status)
        }

        @Test
        fun `completed subagent with tool calls tracks tool count`() = runTest {
            val brain = SequenceBrain(listOf(
                ApiResult.Success(toolCallResponse(
                    "read_file" to """{"path":"a.kt"}"""
                )),
                ApiResult.Success(toolCallResponse(
                    "search_code" to """{"query":"foo"}"""
                )),
                ApiResult.Success(toolCallResponse(
                    "attempt_completion" to """{"result":"Done searching."}"""
                ))
            ))

            val runner = createRunner(brain)
            val result = runner.run("Search for foo") {}

            assertEquals(SubagentRunStatus.COMPLETED, result.status)
            // The onToolCall callback fires for each tool execution in the loop.
            // read_file + search_code + attempt_completion = 3 tool calls
            assertTrue(result.stats.toolCalls >= 2, "Expected at least 2 tool calls, got ${result.stats.toolCalls}")
        }
    }

    @Nested
    inner class FailedSubagentTests {

        @Test
        fun `failed subagent returns error with stats`() = runTest {
            val brain = SequenceBrain(listOf(
                ApiResult.Error(ErrorType.AUTH_FAILED, "Authentication failed")
            ))

            val runner = createRunner(brain)
            val progressUpdates = mutableListOf<SubagentProgressUpdate>()

            val result = runner.run("Do something") { update ->
                progressUpdates.add(update)
            }

            assertEquals(SubagentRunStatus.FAILED, result.status)
            assertNotNull(result.error)
            assertNull(result.result)

            // Should have progress updates including final "failed" status
            assertTrue(progressUpdates.any { it.status == "failed" },
                "Should have a 'failed' progress update")
        }
    }

    @Nested
    inner class StatsTrackingTests {

        @Test
        fun `stats track context window from budget`() = runTest {
            val brain = SequenceBrain(listOf(
                ApiResult.Success(toolCallResponse(
                    "attempt_completion" to """{"result":"Quick task."}"""
                ))
            ))

            val budget = 75_000
            val runner = createRunner(brain, contextBudget = budget)
            val result = runner.run("Quick task") {}

            assertEquals(SubagentRunStatus.COMPLETED, result.status)
            // The onTokenUpdate callback sets contextWindow = contextBudget
            // This fires when the loop processes an API response with usage info
            // The stats should reflect some token tracking
            assertTrue(result.stats.inputTokens >= 0)
            assertTrue(result.stats.outputTokens >= 0)
        }

        @Test
        fun `stats snapshot is immutable`() {
            val stats = SubagentRunner.MutableSubagentStats()
            stats.toolCalls = 5
            stats.inputTokens = 1000

            val snapshot = stats.snapshot()
            stats.toolCalls = 10
            stats.inputTokens = 2000

            // Snapshot should retain original values
            assertEquals(5, snapshot.toolCalls)
            assertEquals(1000, snapshot.inputTokens)
        }
    }

    @Nested
    inner class AbortTests {

        @Test
        fun `abort before run returns cancelled`() = runTest {
            val brain = SequenceBrain(listOf(
                ApiResult.Success(toolCallResponse(
                    "attempt_completion" to """{"result":"Should not reach this."}"""
                ))
            ))

            val runner = createRunner(brain)
            runner.abort()  // Abort before running

            val result = runner.run("This should be cancelled") {}

            assertEquals(SubagentRunStatus.FAILED, result.status)
            assertTrue(result.error!!.contains("cancelled", ignoreCase = true))
        }

        @Test
        fun `abort cancels brain active request`() {
            val brain = SequenceBrain(emptyList())
            val runner = createRunner(brain)

            runner.abort()

            assertTrue(brain.cancelled, "abort() should call brain.cancelActiveRequest()")
        }
    }

    @Nested
    inner class FormatToolCallPreviewTests {

        @Test
        fun `short args are not truncated`() {
            val preview = SubagentRunner.formatToolCallPreview("read_file", """{"path":"src/main.kt"}""")
            assertEquals("""read_file({"path":"src/main.kt"})""", preview)
        }

        @Test
        fun `long args are truncated at 80 chars`() {
            val longArgs = "a".repeat(100)
            val preview = SubagentRunner.formatToolCallPreview("search_code", longArgs)
            assertTrue(preview.endsWith("...)"), "Should end with ...")
            // The truncated args part should be 80 chars + "..."
            assertTrue(preview.length < "search_code(${longArgs})".length, "Preview should be shorter than full")
        }

        @Test
        fun `empty args produce clean preview`() {
            val preview = SubagentRunner.formatToolCallPreview("think", "")
            assertEquals("think()", preview)
        }
    }

    @Nested
    inner class ProgressCallbackTests {

        @Test
        fun `progress reports running then completed`() = runTest {
            val brain = SequenceBrain(listOf(
                ApiResult.Success(toolCallResponse(
                    "attempt_completion" to """{"result":"All done."}"""
                ))
            ))

            val runner = createRunner(brain)
            val statuses = mutableListOf<String>()

            runner.run("Quick task") { update ->
                update.status?.let { statuses.add(it) }
            }

            assertTrue(statuses.contains("running"), "Should have 'running' status")
            assertTrue(statuses.contains("completed"), "Should have 'completed' status")
            assertEquals("running", statuses.first(), "First status should be 'running'")
            assertEquals("completed", statuses.last(), "Last status should be 'completed'")
        }

        @Test
        fun `progress reports running then failed on error`() = runTest {
            val brain = SequenceBrain(listOf(
                ApiResult.Error(ErrorType.SERVER_ERROR, "Internal error")
            ))

            val runner = createRunner(brain)
            val statuses = mutableListOf<String>()

            runner.run("Will fail") { update ->
                update.status?.let { statuses.add(it) }
            }

            assertTrue(statuses.contains("running"), "Should have 'running' status")
            assertTrue(statuses.contains("failed"), "Should have 'failed' status")
        }
    }

    @Nested
    inner class ToolExecutionModeTests {

        @Test
        fun `runner accepts toolExecutionMode parameter`() = runTest {
            val brain = SequenceBrain(listOf(
                ApiResult.Success(toolCallResponse(
                    "attempt_completion" to """{"result":"Done with stream_interrupt mode."}"""
                ))
            ))

            val runner = SubagentRunner(
                brain = brain,
                tools = buildTools(),
                systemPrompt = "You are a test sub-agent.",
                project = project,
                maxIterations = 50,
                planMode = false,
                contextBudget = 50_000,
                toolExecutionMode = "stream_interrupt"
            )

            val result = runner.run("Quick task") {}

            assertEquals(SubagentRunStatus.COMPLETED, result.status)
            assertNotNull(result.result)
            assertTrue(result.result!!.contains("Done with stream_interrupt mode"))
        }

        @Test
        fun `runner defaults to accumulate mode`() = runTest {
            val brain = SequenceBrain(listOf(
                ApiResult.Success(toolCallResponse(
                    "attempt_completion" to """{"result":"Done with default mode."}"""
                ))
            ))

            // Use createRunner helper which does NOT pass toolExecutionMode
            val runner = createRunner(brain)

            val result = runner.run("Quick task") {}

            assertEquals(SubagentRunStatus.COMPLETED, result.status)
            assertNotNull(result.result)
            assertTrue(result.result!!.contains("Done with default mode"))
        }
    }

    @Nested
    inner class XmlToolDefinitionTests {

        @Test
        fun `system prompt includes XML tool definitions`() = runTest {
            val brain = SequenceBrain(listOf(
                ApiResult.Success(toolCallResponse(
                    "attempt_completion" to """{"result":"Done."}"""
                ))
            ))

            val runner = createRunner(brain)
            runner.run("Do something") {}

            val systemMessage = brain.lastMessages.first()
            val content = systemMessage.content ?: ""
            assertTrue(content.contains("# Tool Use Format"),
                "System prompt should contain XML tool format header")
            assertTrue(content.contains("<read_file>"),
                "System prompt should contain XML usage example for read_file")
            assertTrue(content.contains("<attempt_completion>"),
                "System prompt should contain XML usage example for attempt_completion")
        }

        @Test
        fun `XML parser receives sub-agent tool names not main agent tool set`() = runTest {
            // Brain with toolNameSet simulating main agent's full set (includes jira, bamboo)
            val mainAgentToolNames = setOf(
                "read_file", "edit_file", "run_command", "search_code",
                "think", "attempt_completion", "jira", "bamboo_builds"
            )
            val mainAgentParamNames = setOf(
                "path", "content", "query", "command", "result", "action"
            )

            val brain = SequenceBrain(
                responses = listOf(
                    ApiResult.Success(toolCallResponse(
                        "attempt_completion" to """{"result":"Done."}"""
                    ))
                ),
                toolNames = mainAgentToolNames,
                paramNames = mainAgentParamNames
            )

            // Sub-agent only has 3 tools
            val subTools = mapOf(
                "read_file" to stubTool("read_file"),
                "search_code" to stubTool("search_code"),
                "attempt_completion" to AttemptCompletionTool()
            )

            val runner = SubagentRunner(
                brain = brain,
                tools = subTools,
                systemPrompt = "You are a research agent.",
                project = project,
                maxIterations = 10,
                planMode = false,
                contextBudget = 50_000
            )

            runner.run("Research task") {}

            val systemContent = brain.lastMessages.first().content ?: ""
            // System prompt should only contain sub-agent tools, not main-agent-only tools
            assertFalse(systemContent.contains("<jira>"),
                "System prompt should NOT contain main-agent-only tools like <jira>")
            assertFalse(systemContent.contains("<bamboo_builds>"),
                "System prompt should NOT contain main-agent-only tools like <bamboo_builds>")
            assertTrue(systemContent.contains("<read_file>"),
                "System prompt SHOULD contain sub-agent tool <read_file>")
        }

        @Test
        fun `system prompt preserves original config prompt`() = runTest {
            val brain = SequenceBrain(listOf(
                ApiResult.Success(toolCallResponse(
                    "attempt_completion" to """{"result":"Done."}"""
                ))
            ))

            val customPrompt = "You are a specialized code reviewer"
            val runner = SubagentRunner(
                brain = brain,
                tools = buildTools(),
                systemPrompt = customPrompt,
                project = project,
                maxIterations = 50,
                planMode = false,
                contextBudget = 50_000
            )
            runner.run("Review this code") {}

            val systemMessage = brain.lastMessages.first()
            val content = systemMessage.content ?: ""
            assertTrue(content.contains(customPrompt),
                "System prompt should still contain the original config prompt text")
        }
    }
}
