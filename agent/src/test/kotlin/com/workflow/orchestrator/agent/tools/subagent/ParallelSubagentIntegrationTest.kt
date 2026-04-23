// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.agent.tools.subagent

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolRegistry
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.builtin.AttemptCompletionTool
import com.workflow.orchestrator.agent.tools.builtin.TaskReportTool
import com.workflow.orchestrator.agent.tools.builtin.SpawnAgentTool
import com.workflow.orchestrator.core.ai.LlmBrain
import com.workflow.orchestrator.core.ai.dto.*
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * End-to-end integration tests for the parallel subagent system.
 * Validates that multiple research subagents execute concurrently,
 * report results correctly, and handle partial failures.
 */
class ParallelSubagentIntegrationTest {

    private lateinit var project: Project
    private lateinit var registry: ToolRegistry
    private lateinit var configLoader: AgentConfigLoader

    @BeforeEach
    fun setUp() {
        project = mockk(relaxed = true)
        every { project.name } returns "TestProject"
        every { project.basePath } returns "/tmp/test-project"
        registry = buildTestRegistry()
        AgentConfigLoader.resetForTests()
        configLoader = AgentConfigLoader.getInstance()
        // Load bundled agents (explorer is read-only, needed for parallel tests)
        val tempDir = java.nio.file.Files.createTempDirectory("parallel-subagent-test-")
        configLoader.loadFromDisk(tempDir)
        tempDir.toFile().deleteRecursively()
    }

    @AfterEach
    fun tearDown() {
        AgentConfigLoader.resetForTests()
    }

    // ---- Helpers ----

    private fun buildTestRegistry(): ToolRegistry {
        val reg = ToolRegistry()
        // Builtin read tools
        for (name in listOf(
            "read_file", "search_code", "glob_files", "think",
            "project_context", "current_time", "ask_questions", "ask_user_input"
        )) reg.register(stubTool(name))
        reg.register(AttemptCompletionTool())
        reg.register(TaskReportTool())

        // Builtin write tools
        for (name in listOf(
            "edit_file", "create_file", "run_command", "revert_file",
            "background_process", "send_stdin"
        )) reg.register(stubTool(name))

        // PSI tools
        for (name in listOf(
            "find_definition", "find_references", "find_implementations",
            "file_structure", "type_hierarchy", "call_hierarchy",
            "type_inference", "get_method_body", "get_annotations",
            "read_write_access", "structural_search", "test_finder",
            "dataflow_analysis"
        )) reg.register(stubTool(name))

        // VCS tools
        for (name in listOf(
            "changelist_shelve"
        )) reg.register(stubTool(name))

        // IDE tools
        for (name in listOf(
            "format_code", "optimize_imports", "refactor_rename",
            "diagnostics", "run_inspections", "problem_view", "list_quickfixes"
        )) reg.register(stubTool(name))

        // Database tools
        for (name in listOf("db_query", "db_schema", "db_list_profiles")) {
            reg.register(stubTool(name))
        }

        // Integration tools
        for (name in listOf(
            "jira", "bamboo_builds", "bamboo_plans",
            "bitbucket_pr", "bitbucket_repo", "bitbucket_review", "sonar"
        )) reg.register(stubTool(name))

        // Runtime tools
        for (name in listOf("runtime_exec", "runtime_config", "coverage")) {
            reg.register(stubTool(name))
        }

        // Framework tools
        for (name in listOf("build", "spring")) reg.register(stubTool(name))

        // Config tools
        for (name in listOf("create_run_config", "modify_run_config", "delete_run_config")) {
            reg.register(stubTool(name))
        }

        // Debug tools
        for (name in listOf("debug_step", "debug_inspect", "debug_breakpoints")) {
            reg.register(stubTool(name))
        }

        // Agent tool itself (to verify exclusion)
        reg.register(stubTool("agent"))

        return reg
    }

    private fun stubTool(toolName: String): AgentTool = object : AgentTool {
        override val name = toolName
        override val description = "Stub tool: $toolName"
        override val parameters = FunctionParameters(properties = emptyMap())
        override val allowedWorkers = setOf(WorkerType.CODER)
        override suspend fun execute(params: JsonObject, project: Project) =
            ToolResult(content = "stub:$toolName", summary = "stub", tokenEstimate = 5)
    }

    private fun params(vararg pairs: Pair<String, String>): JsonObject =
        JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })

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

    /**
     * A fake LlmBrain that returns pre-configured responses in sequence.
     * Thread-safe via synchronized callIndex access for parallel tests.
     */
    private inner class SequenceBrain(
        private val responses: List<ApiResult<ChatCompletionResponse>>
    ) : LlmBrain {
        override val modelId: String = "test-sub-agent-model"
        private var callIndex = 0

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
            if (callIndex >= responses.size) {
                return ApiResult.Error(ErrorType.SERVER_ERROR, "No more scripted responses")
            }
            return responses[callIndex++]
        }

        override fun estimateTokens(text: String): Int = text.toByteArray().size / 4
        override fun cancelActiveRequest() {}
    }

    // ---- Test 1: Three parallel research subagents all complete ----

    @Test
    fun `three parallel research subagents all complete`() = runTest {
        val brainCount = AtomicInteger(0)
        val progressUpdates: MutableList<Pair<String, SubagentProgressUpdate>> =
            Collections.synchronizedList(mutableListOf())

        val spawnTool = SpawnAgentTool(
            brainProvider = {
                val idx = brainCount.incrementAndGet()
                SequenceBrain(listOf(
                    ApiResult.Success(toolCallResponse(
                        "read_file" to """{"path":"src/module$idx.kt"}"""
                    )),
                    ApiResult.Success(toolCallResponse(
                        "task_report" to """{"summary":"Result from agent $idx"}"""
                    ))
                ))
            },
            toolRegistry = registry,
            project = project,
            contextBudget = 50_000,
            onSubagentProgress = { agentId, update ->
                progressUpdates.add(agentId to update)
            },
            configLoader = configLoader
        )

        val result = spawnTool.execute(
            params(
                "description" to "Multi-research",
                "prompt" to "Research question 1",
                "prompt_2" to "Research question 2",
                "prompt_3" to "Research question 3",
                "agent_type" to "explorer"
            ),
            project
        )

        // Verify result is not an error
        assertFalse(result.isError, "Parallel research should succeed: ${result.content}")

        // Verify content reports all 3 agents
        assertTrue(result.content.contains("Total: 3"), "Should report total of 3 agents: ${result.content}")
        assertTrue(result.content.contains("Succeeded: 3"), "All 3 should succeed: ${result.content}")

        // Verify 3 brains were created (one per prompt)
        assertEquals(3, brainCount.get(), "Should have created exactly 3 brains")

        // Verify progress callbacks include completed updates
        val completedUpdates = progressUpdates.filter { it.second.status == SubagentExecutionStatus.COMPLETED }
        assertTrue(
            completedUpdates.size >= 1,
            "Progress callbacks should include at least 1 'completed' group update, got: ${progressUpdates.map { it.second.status }}"
        )

        // Verify summary mentions parallel agents
        assertTrue(
            result.summary.contains("Parallel agents") && result.summary.contains("3/3"),
            "Summary should contain 'Parallel agents' and '3/3': ${result.summary}"
        )
    }

    // ---- Test 2: Partial failure in parallel subagents reports mixed results ----

    @Test
    fun `partial failure in parallel subagents reports mixed results`() = runTest {
        val brainCount = AtomicInteger(0)

        val spawnTool = SpawnAgentTool(
            brainProvider = {
                val idx = brainCount.incrementAndGet()
                if (idx == 2) {
                    // Brain #2 returns an API error on first call
                    SequenceBrain(listOf(
                        ApiResult.Error(ErrorType.SERVER_ERROR, "Internal server error")
                    ))
                } else {
                    // Brains #1 and #3 succeed
                    SequenceBrain(listOf(
                        ApiResult.Success(toolCallResponse(
                            "read_file" to """{"path":"src/file$idx.kt"}"""
                        )),
                        ApiResult.Success(toolCallResponse(
                            "task_report" to """{"summary":"Success from agent $idx"}"""
                        ))
                    ))
                }
            },
            toolRegistry = registry,
            project = project,
            contextBudget = 50_000,
            configLoader = configLoader
        )

        val result = spawnTool.execute(
            params(
                "description" to "Mixed-research",
                "prompt" to "Research task 1",
                "prompt_2" to "Research task 2 (will fail)",
                "prompt_3" to "Research task 3",
                "agent_type" to "explorer"
            ),
            project
        )

        // Not all-error: 2 of 3 succeeded so isError should be false
        assertFalse(result.isError, "Should not be all-error since 2/3 succeeded: ${result.content}")

        // Verify content shows both succeeded and failed counts
        assertTrue(result.content.contains("Total: 3"), "Should report total of 3: ${result.content}")
        assertTrue(result.content.contains("Succeeded: 2"), "Should report 2 succeeded: ${result.content}")
        assertTrue(result.content.contains("Failed: 1"), "Should report 1 failed: ${result.content}")

        // Verify summary reflects partial failure
        assertTrue(
            result.summary.contains("2/3"),
            "Summary should contain '2/3' for partial success: ${result.summary}"
        )
    }
}
