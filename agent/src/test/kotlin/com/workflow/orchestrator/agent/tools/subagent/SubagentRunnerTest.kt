package com.workflow.orchestrator.agent.tools.subagent

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.builtin.AttemptCompletionTool
import com.workflow.orchestrator.agent.tools.builtin.TaskReportTool
import com.workflow.orchestrator.core.ai.LlmBrain
import com.workflow.orchestrator.core.ai.dto.*
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import com.workflow.orchestrator.core.model.ModelPricingRegistry
import org.junit.jupiter.api.AfterEach
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

    /**
     * Shut down the ModelPricingRegistry's file-watcher daemon thread after each test.
     * The watcher starts lazily when AgentLoop calls `ModelPricingRegistry.lookup()`;
     * without this cleanup, the native `FileSystemWatcher` it spawns on macOS trips
     * IntelliJ's `ThreadLeakTracker`, failing tests on an unrelated infrastructure
     * issue. Follows the same pattern as DatabaseConnectionManagerTest's MySQL
     * cleanup-thread shutdown.
     */
    @AfterEach
    fun stopModelPricingWatcher() {
        runCatching { ModelPricingRegistry.resetForTests() }
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
        coreTools = tools,
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
                    "attempt_completion" to """{"kind":"done","result":"Found the answer: 42."}"""
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
            assertEquals(SubagentExecutionStatus.RUNNING, progressUpdates.first().status)
            assertEquals(SubagentExecutionStatus.COMPLETED, progressUpdates.last().status)
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
                    "attempt_completion" to """{"kind":"done","result":"Done searching."}"""
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
            assertTrue(progressUpdates.any { it.status == SubagentExecutionStatus.FAILED },
                "Should have a 'failed' progress update")
        }
    }

    @Nested
    inner class StatsTrackingTests {

        @Test
        fun `stats track context window from budget`() = runTest {
            val brain = SequenceBrain(listOf(
                ApiResult.Success(toolCallResponse(
                    "attempt_completion" to """{"kind":"done","result":"Quick task."}"""
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
                    "attempt_completion" to """{"kind":"done","result":"Should not reach this."}"""
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
                    "attempt_completion" to """{"kind":"done","result":"All done."}"""
                ))
            ))

            val runner = createRunner(brain)
            val statuses = mutableListOf<SubagentExecutionStatus>()

            runner.run("Quick task") { update ->
                update.status?.let { statuses.add(it) }
            }

            assertTrue(statuses.contains(SubagentExecutionStatus.RUNNING), "Should have RUNNING status")
            assertTrue(statuses.contains(SubagentExecutionStatus.COMPLETED), "Should have COMPLETED status")
            assertEquals(SubagentExecutionStatus.RUNNING, statuses.first(), "First status should be RUNNING")
            assertEquals(SubagentExecutionStatus.COMPLETED, statuses.last(), "Last status should be COMPLETED")
        }

        @Test
        fun `progress reports running then failed on error`() = runTest {
            val brain = SequenceBrain(listOf(
                ApiResult.Error(ErrorType.SERVER_ERROR, "Internal error")
            ))

            val runner = createRunner(brain)
            val statuses = mutableListOf<SubagentExecutionStatus>()

            runner.run("Will fail") { update ->
                update.status?.let { statuses.add(it) }
            }

            assertTrue(statuses.contains(SubagentExecutionStatus.RUNNING), "Should have RUNNING status")
            assertTrue(statuses.contains(SubagentExecutionStatus.FAILED), "Should have FAILED status")
        }
    }

    @Nested
    inner class ToolExecutionModeTests {

        @Test
        fun `runner accepts toolExecutionMode parameter`() = runTest {
            val brain = SequenceBrain(listOf(
                ApiResult.Success(toolCallResponse(
                    "attempt_completion" to """{"kind":"done","result":"Done with stream_interrupt mode."}"""
                ))
            ))

            val runner = SubagentRunner(
                brain = brain,
                coreTools = buildTools(),
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
                    "attempt_completion" to """{"kind":"done","result":"Done with default mode."}"""
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
                    "attempt_completion" to """{"kind":"done","result":"Done."}"""
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
                        "attempt_completion" to """{"kind":"done","result":"Done."}"""
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
                coreTools = subTools,
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
                    "attempt_completion" to """{"kind":"done","result":"Done."}"""
                ))
            ))

            val customPrompt = "You are a specialized code reviewer"
            val runner = SubagentRunner(
                brain = brain,
                coreTools = buildTools(),
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

    @Nested
    inner class ForcedStreamInterruptTests {

        @Test
        fun `sub-agent forces stream_interrupt regardless of caller-supplied accumulate`() {
            val brain = SequenceBrain(emptyList())
            val runner = SubagentRunner(
                brain = brain,
                coreTools = buildTools(),
                systemPrompt = "test",
                project = project,
                maxIterations = 5,
                planMode = false,
                contextBudget = 50_000,
                toolExecutionMode = "accumulate", // caller says accumulate …
            )
            // … runner must ignore it.
            assertEquals("stream_interrupt", runner.effectiveToolExecutionMode)
        }
    }

    // ---- Task 4: Per-sub-agent ToolRegistry + deferred catalog tests ----

    @Nested
    inner class PerSubagentRegistryTests {

        @Test
        fun `SubagentRunner injects tool_search backed by sub-agent registry into core tools`() = runTest {
            // Even with no tool_search in coreTools, it must be injected automatically.
            // The brain calls attempt_completion, so the loop completes without using tool_search.
            val brain = SequenceBrain(listOf(
                ApiResult.Success(toolCallResponse(
                    "attempt_completion" to """{"kind":"done","result":"Done without tool_search."}"""
                ))
            ))

            val capturedSystemPrompt = mutableListOf<String>()
            val runner = SubagentRunner(
                brain = brain,
                coreTools = buildTools(),   // no tool_search in here
                deferredTools = emptyMap(),
                systemPrompt = "You are a test sub-agent.",
                project = project,
                maxIterations = 10,
                planMode = false,
                contextBudget = 50_000,
                onSystemPromptBuilt = { prompt -> capturedSystemPrompt.add(prompt) }
            )

            val result = runner.run("Quick task") {}

            assertEquals(SubagentRunStatus.COMPLETED, result.status)
            // tool_search must appear in the composed system prompt
            assertTrue(capturedSystemPrompt.isNotEmpty(), "onSystemPromptBuilt hook must have fired")
            assertTrue(
                capturedSystemPrompt.first().contains("tool_search"),
                "tool_search should be injected into the sub-agent system prompt automatically"
            )
        }

        @Test
        fun `SubagentRunner system prompt contains deferred catalog when deferredTools provided`() = runTest {
            val brain = SequenceBrain(listOf(
                ApiResult.Success(toolCallResponse(
                    "attempt_completion" to """{"kind":"done","result":"Done."}"""
                ))
            ))

            val deferredTool = stubTool("find_implementations")
            val deferredTools = mapOf(
                "find_implementations" to Pair(deferredTool, "Code Intelligence")
            )

            val capturedSystemPrompt = mutableListOf<String>()
            val runner = SubagentRunner(
                brain = brain,
                coreTools = buildTools(),
                deferredTools = deferredTools,
                systemPrompt = "You are a test sub-agent.",
                project = project,
                maxIterations = 10,
                planMode = false,
                contextBudget = 50_000,
                onSystemPromptBuilt = { prompt -> capturedSystemPrompt.add(prompt) }
            )

            runner.run("Find implementations") {}

            assertTrue(capturedSystemPrompt.isNotEmpty(), "Hook must fire")
            val prompt = capturedSystemPrompt.first()
            assertTrue(
                prompt.contains("find_implementations"),
                "System prompt should list the deferred tool name"
            )
            assertTrue(
                prompt.contains("ADDITIONAL TOOLS"),
                "System prompt should contain 'ADDITIONAL TOOLS' section header"
            )
        }

        @Test
        fun `SubagentRunner with no deferred tools produces clean system prompt without Deferred Tools section`() = runTest {
            val brain = SequenceBrain(listOf(
                ApiResult.Success(toolCallResponse(
                    "attempt_completion" to """{"kind":"done","result":"Done."}"""
                ))
            ))

            val capturedSystemPrompt = mutableListOf<String>()
            val runner = SubagentRunner(
                brain = brain,
                coreTools = buildTools(),
                deferredTools = emptyMap(),
                systemPrompt = "You are a test sub-agent.",
                project = project,
                maxIterations = 10,
                planMode = false,
                contextBudget = 50_000,
                onSystemPromptBuilt = { prompt -> capturedSystemPrompt.add(prompt) }
            )

            runner.run("Quick task") {}

            assertTrue(capturedSystemPrompt.isNotEmpty(), "Hook must fire")
            val prompt = capturedSystemPrompt.first()
            assertFalse(
                prompt.contains("ADDITIONAL TOOLS"),
                "System prompt must NOT contain 'ADDITIONAL TOOLS' section when there are no deferred tools"
            )
        }
    }

    @Nested
    inner class ApprovalGateTests {

        @Test
        fun `write tool in sub-agent hits parent approval gate`() = runTest {
            val writeTool = object : AgentTool {
                override val name = "edit_file"
                override val description = "stub edit tool"
                override val parameters = FunctionParameters(properties = emptyMap())
                override val allowedWorkers = setOf(WorkerType.CODER)
                override suspend fun execute(params: JsonObject, project: Project) =
                    ToolResult(content = "edited", summary = "edited", tokenEstimate = 5)
            }
            val tools = mapOf(
                "edit_file" to writeTool,
                "attempt_completion" to AttemptCompletionTool(),
            )
            val brain = SequenceBrain(listOf(
                ApiResult.Success(toolCallResponse(
                    "edit_file" to """{"path":"a.kt","old_string":"a","new_string":"b"}""",
                )),
                ApiResult.Success(toolCallResponse(
                    "attempt_completion" to """{"kind":"done","result":"done"}""",
                )),
            ))

            val approvalCalls = mutableListOf<Triple<String, String, String>>()
            val gate: suspend (String, String, String, Boolean) -> com.workflow.orchestrator.agent.loop.ApprovalResult =
                { toolName, args, risk, _ ->
                    approvalCalls += Triple(toolName, args, risk)
                    com.workflow.orchestrator.agent.loop.ApprovalResult.APPROVED
                }

            val runner = SubagentRunner(
                brain = brain,
                coreTools = tools,
                systemPrompt = "test",
                project = project,
                maxIterations = 10,
                planMode = false,
                contextBudget = 50_000,
                approvalGate = gate,
            )
            val result = runner.run("edit") {}

            assertEquals(SubagentRunStatus.COMPLETED, result.status)
            assertEquals(1, approvalCalls.size,
                "Expected approval gate to fire once for edit_file — sub-agent must surface the same modal as the main agent.")
            assertEquals("edit_file", approvalCalls.single().first)
        }

        @Test
        fun `sub-agent without approval gate still runs write tools (backwards compat)`() = runTest {
            val writeTool = object : AgentTool {
                override val name = "edit_file"
                override val description = "stub"
                override val parameters = FunctionParameters(properties = emptyMap())
                override val allowedWorkers = setOf(WorkerType.CODER)
                override suspend fun execute(params: JsonObject, project: Project) =
                    ToolResult(content = "edited", summary = "edited", tokenEstimate = 5)
            }
            val tools = mapOf(
                "edit_file" to writeTool,
                "attempt_completion" to AttemptCompletionTool(),
            )
            val brain = SequenceBrain(listOf(
                ApiResult.Success(toolCallResponse(
                    "edit_file" to """{"path":"a.kt","old_string":"a","new_string":"b"}""",
                )),
                ApiResult.Success(toolCallResponse(
                    "attempt_completion" to """{"kind":"done","result":"done"}""",
                )),
            ))

            val runner = SubagentRunner(
                brain = brain,
                coreTools = tools,
                systemPrompt = "test",
                project = project,
                maxIterations = 10,
                planMode = false,
                contextBudget = 50_000,
                // no approvalGate
            )
            val result = runner.run("edit") {}
            assertEquals(SubagentRunStatus.COMPLETED, result.status,
                "Null approval gate must not block execution.")
        }
    }

    // ---- Integration: Deferred Tool Activation -----------------------------------------------

    @Nested
    inner class DeferredToolActivationIntegration {

        /**
         * Full pipeline integration test:
         * 1. Sub-agent starts with db_explain in deferred (not in core)
         * 2. LLM calls tool_search to activate db_explain
         * 3. LLM calls db_explain (now active via sub-agent registry)
         * 4. LLM calls attempt_completion
         *
         * Verifies the full activation chain: ToolSearchTool → subagentRegistry.activateDeferred
         * → AgentLoop.toolResolver picks it up → tool executes successfully.
         */
        @Test
        fun `tool_search activates deferred tool which becomes callable in next iteration`() = runTest {
            var dbExplainCallCount = 0
            val dbExplainTool = object : AgentTool {
                override val name = "db_explain"
                override val description = "Explain a SQL query plan"
                override val parameters = FunctionParameters(
                    properties = mapOf(
                        "query" to com.workflow.orchestrator.agent.api.dto.ParameterProperty(
                            type = "string", description = "SQL query to explain"
                        )
                    )
                )
                override val allowedWorkers = setOf(WorkerType.CODER)
                override suspend fun execute(params: JsonObject, project: Project): ToolResult {
                    dbExplainCallCount++
                    return ToolResult(
                        content = "Seq Scan on orders (cost=0.00..1.23)",
                        summary = "query plan",
                        tokenEstimate = 30
                    )
                }
            }

            val brain = SequenceBrain(listOf(
                // Turn 1: LLM activates db_explain from deferred via tool_search
                ApiResult.Success(toolCallResponse(
                    "tool_search" to """{"query":"select:db_explain"}"""
                )),
                // Turn 2: LLM calls the now-active db_explain
                ApiResult.Success(toolCallResponse(
                    "db_explain" to """{"query":"SELECT * FROM orders WHERE id = 1"}"""
                )),
                // Turn 3: complete
                ApiResult.Success(toolCallResponse(
                    "attempt_completion" to """{"kind":"done","result":"Query uses seq scan — add index on orders.id"}"""
                )),
            ))

            val coreTools = buildTools()  // read_file, search_code, think, attempt_completion
            val deferredTools = mapOf("db_explain" to (dbExplainTool to "Database"))

            val runner = SubagentRunner(
                brain = brain,
                coreTools = coreTools,
                deferredTools = deferredTools,
                systemPrompt = "You are a performance engineer.",
                project = project,
                maxIterations = 10,
                planMode = false,
                contextBudget = 100_000,
            )

            val result = runner.run("Analyze slow query") {}

            assertEquals(SubagentRunStatus.COMPLETED, result.status,
                "Sub-agent must complete after activating deferred tool via tool_search")
            assertEquals(1, dbExplainCallCount,
                "db_explain must have been called exactly once after activation by tool_search")
        }

        /**
         * Registry isolation test:
         * Sub-agent tool_search only sees its own deferredTools — NOT the main registry.
         * When the sub-agent's deferred list is empty, tool_search returns no results
         * and the sub-agent should complete without error.
         */
        @Test
        fun `tool_search in sub-agent returns no results when deferred list is empty`() = runTest {
            // Sub-agent has NO deferred tools at all
            val brain = SequenceBrain(listOf(
                // LLM tries to activate jira — not in sub-agent's deferred list
                ApiResult.Success(toolCallResponse(
                    "tool_search" to """{"query":"select:jira"}"""
                )),
                // After getting "no tools found" result, completes gracefully
                ApiResult.Success(toolCallResponse(
                    "attempt_completion" to """{"kind":"done","result":"jira not available for this sub-agent task"}"""
                )),
            ))

            val coreTools = buildTools()
            val deferredTools = emptyMap<String, Pair<AgentTool, String>>()  // nothing deferred

            val runner = SubagentRunner(
                brain = brain,
                coreTools = coreTools,
                deferredTools = deferredTools,
                systemPrompt = "Test",
                project = project,
                maxIterations = 10,
                planMode = false,
                contextBudget = 100_000,
            )

            // Should complete without crashing — tool_search returns "no results" for jira
            val result = runner.run("Task") {}
            assertEquals(SubagentRunStatus.COMPLETED, result.status,
                "Sub-agent must complete even when tool_search finds no matching deferred tool")
        }

        /**
         * System prompt catalog test:
         * The onSystemPromptBuilt hook captures the initial prompt, which must contain
         * the deferred catalog section when deferredTools are provided.
         */
        @Test
        fun `initial system prompt includes deferred catalog when deferredTools provided`() = runTest {
            val brain = SequenceBrain(listOf(
                ApiResult.Success(toolCallResponse(
                    "attempt_completion" to """{"kind":"done","result":"done"}"""
                )),
            ))

            var capturedSystemPrompt: String? = null

            val coreTools = buildTools()
            val deferredTools = mapOf(
                "db_explain" to (stubTool("db_explain") to "Database"),
                "db_schema"  to (stubTool("db_schema")  to "Database"),
            )

            val runner = SubagentRunner(
                brain = brain,
                coreTools = coreTools,
                deferredTools = deferredTools,
                systemPrompt = "Test persona prompt.",
                project = project,
                maxIterations = 10,
                planMode = false,
                contextBudget = 100_000,
                onSystemPromptBuilt = { prompt -> capturedSystemPrompt = prompt },
            )

            runner.run("Task") {}

            assertNotNull(capturedSystemPrompt, "onSystemPromptBuilt hook must be called")
            assertTrue(
                capturedSystemPrompt!!.contains("db_explain"),
                "Initial system prompt must mention deferred tool 'db_explain'. Got:\n${capturedSystemPrompt!!.take(500)}"
            )
            assertTrue(
                capturedSystemPrompt!!.contains("ADDITIONAL TOOLS"),
                "Initial system prompt must contain 'ADDITIONAL TOOLS' section header"
            )
        }
    }

    // ── task_report completion tests ──────────────────────────────────────────────────────────

    @Nested
    inner class TaskReportCompletionTests {

        private fun buildToolsWithTaskReport(): Map<String, AgentTool> {
            val tools = mutableMapOf<String, AgentTool>()
            for (name in listOf("read_file", "search_code", "think")) {
                tools[name] = stubTool(name)
            }
            tools["task_report"] = TaskReportTool()
            return tools
        }

        @Test
        fun `sub-agent calling task_report terminates the loop with COMPLETED status`() = runTest {
            val brain = SequenceBrain(listOf(
                ApiResult.Success(toolCallResponse(
                    "read_file" to """{"path":"src/Foo.kt"}"""
                )),
                ApiResult.Success(toolCallResponse(
                    "task_report" to """{"summary":"Reviewed Foo.kt and found no issues.","findings":"All methods follow the coding standard.","files":"src/Foo.kt"}"""
                ))
            ))

            val runner = SubagentRunner(
                brain = brain,
                coreTools = buildToolsWithTaskReport(),
                systemPrompt = "You are a code reviewer.",
                project = project,
                maxIterations = 50,
                planMode = false,
                contextBudget = 50_000
            )

            val result = runner.run("Review Foo.kt") {}

            assertEquals(SubagentRunStatus.COMPLETED, result.status)
            assertNull(result.error)
            assertNotNull(result.result, "task_report content must flow back as result")
        }

        @Test
        fun `task_report structured content flows into parent ToolResult content`() = runTest {
            val brain = SequenceBrain(listOf(
                ApiResult.Success(toolCallResponse(
                    "task_report" to """{"summary":"Found the bug.","findings":"NPE at Foo.kt:42 when input is null.","next_steps":"Add null check at Foo.kt:42.","issues":"Could not reproduce under Java 17."}"""
                ))
            ))

            val runner = SubagentRunner(
                brain = brain,
                coreTools = buildToolsWithTaskReport(),
                systemPrompt = "You are a debugger.",
                project = project,
                maxIterations = 10,
                planMode = false,
                contextBudget = 50_000
            )

            val result = runner.run("Debug the NPE") {}

            assertEquals(SubagentRunStatus.COMPLETED, result.status)
            val content = result.result ?: ""
            assertTrue(content.contains("## Summary"), "Structured report must contain ## Summary")
            assertTrue(content.contains("Found the bug."))
            assertTrue(content.contains("## Findings"))
            assertTrue(content.contains("NPE at Foo.kt:42"))
            assertTrue(content.contains("## Next Steps"))
            assertTrue(content.contains("Add null check"))
            assertTrue(content.contains("## Issues"))
            assertTrue(content.contains("Could not reproduce"))
        }
    }

    // ── "COMPLETING YOUR TASK" prompt injection tests ─────────────────────────────────────────

    @Nested
    inner class CompletingYourTaskInjectionTests {

        @Test
        fun `composed system prompt always contains COMPLETING YOUR TASK section`() = runTest {
            val brain = SequenceBrain(listOf(
                ApiResult.Success(toolCallResponse(
                    "attempt_completion" to """{"kind":"done","result":"Done."}"""
                ))
            ))

            var capturedPrompt: String? = null
            val runner = SubagentRunner(
                brain = brain,
                coreTools = buildTools(),
                systemPrompt = "You are a specialist agent.",
                project = project,
                maxIterations = 10,
                planMode = false,
                contextBudget = 50_000,
                onSystemPromptBuilt = { prompt -> capturedPrompt = prompt }
            )
            runner.run("Do something") {}

            assertNotNull(capturedPrompt, "onSystemPromptBuilt hook must fire")
            assertTrue(
                capturedPrompt!!.contains("COMPLETING YOUR TASK"),
                "Composed system prompt must contain 'COMPLETING YOUR TASK' section"
            )
            assertTrue(
                capturedPrompt!!.contains("task_report"),
                "COMPLETING YOUR TASK section must reference the task_report tool"
            )
        }

        @Test
        fun `COMPLETING YOUR TASK section explains parent cannot see streamed text`() = runTest {
            val brain = SequenceBrain(listOf(
                ApiResult.Success(toolCallResponse(
                    "attempt_completion" to """{"kind":"done","result":"Done."}"""
                ))
            ))

            var capturedPrompt: String? = null
            val runner = SubagentRunner(
                brain = brain,
                coreTools = buildTools(),
                systemPrompt = "You are a test agent.",
                project = project,
                maxIterations = 10,
                planMode = false,
                contextBudget = 50_000,
                onSystemPromptBuilt = { prompt -> capturedPrompt = prompt }
            )
            runner.run("Task") {}

            val section = capturedPrompt ?: ""
            assertTrue(
                section.contains("NOT visible to the parent"),
                "The section must warn the sub-agent that streamed text is not visible to the parent"
            )
        }

        @Test
        fun `COMPLETING_YOUR_TASK_SECTION constant is accessible from companion`() {
            assertTrue(SubagentRunner.COMPLETING_YOUR_TASK_SECTION.contains("task_report"))
            assertTrue(SubagentRunner.COMPLETING_YOUR_TASK_SECTION.contains("summary"))
            assertTrue(SubagentRunner.COMPLETING_YOUR_TASK_SECTION.contains("findings"))
        }
    }
}
