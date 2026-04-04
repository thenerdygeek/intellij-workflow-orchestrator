package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.loop.ContextManager
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolRegistry
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
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
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SpawnAgentToolTest {

    private lateinit var project: Project
    private lateinit var registry: ToolRegistry
    private lateinit var tool: SpawnAgentTool

    @BeforeEach
    fun setUp() {
        project = mockk(relaxed = true)
        every { project.name } returns "TestProject"
        every { project.basePath } returns "/tmp/test-project"
        registry = buildTestRegistry()
        tool = SpawnAgentTool(
            brainProvider = { throw IllegalStateException("Brain should not be created in scope tests") },
            toolRegistry = registry,
            project = project
        )
    }

    // ---- Helpers ----

    /** Build a registry with representative tools from all categories. */
    private fun buildTestRegistry(): ToolRegistry {
        val reg = ToolRegistry()
        // Builtin read tools (attempt_completion uses real implementation for isCompletion=true)
        for (name in listOf(
            "read_file", "search_code", "glob_files", "think",
            "project_context", "current_time", "ask_questions", "ask_user_input"
        )) reg.register(stubTool(name))
        reg.register(AttemptCompletionTool())

        // Builtin write tools
        for (name in listOf(
            "edit_file", "create_file", "run_command", "revert_file",
            "kill_process", "send_stdin"
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
            "git_status", "git_diff", "git_log", "git_blame",
            "git_show_file", "git_file_history", "git_show_commit",
            "git_branches", "changelist_shelve", "git_stash_list",
            "git_merge_base", "git"
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

        // The agent tool itself (to verify it's excluded from sub-agent scopes)
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

    private fun paramsWithInt(stringPairs: Map<String, String>, intPairs: Map<String, Int>): JsonObject =
        JsonObject(
            stringPairs.mapValues { (_, v) -> JsonPrimitive(v) as kotlinx.serialization.json.JsonElement } +
            intPairs.mapValues { (_, v) -> JsonPrimitive(v) as kotlinx.serialization.json.JsonElement }
        )

    // ---- Scope Tests ----

    @Nested
    inner class ResearchScopeTests {

        @Test
        fun `research scope includes only read-only tools`() {
            val scoped = tool.resolveScopedTools("research")

            // Verify read tools present
            assertTrue("read_file" in scoped, "research should include read_file")
            assertTrue("search_code" in scoped, "research should include search_code")
            assertTrue("glob_files" in scoped, "research should include glob_files")
            assertTrue("think" in scoped, "research should include think")
            assertTrue("attempt_completion" in scoped, "research should include attempt_completion")
            assertTrue("project_context" in scoped, "research should include project_context")
            assertTrue("current_time" in scoped, "research should include current_time")
            assertTrue("ask_questions" in scoped, "research should include ask_questions")
        }

        @Test
        fun `research scope includes PSI read tools`() {
            val scoped = tool.resolveScopedTools("research")

            for (psiTool in listOf(
                "find_definition", "find_references", "find_implementations",
                "file_structure", "type_hierarchy", "call_hierarchy",
                "type_inference", "get_method_body", "get_annotations",
                "read_write_access", "structural_search", "test_finder",
                "dataflow_analysis"
            )) {
                assertTrue(psiTool in scoped, "research should include PSI tool: $psiTool")
            }
        }

        @Test
        fun `research scope includes VCS read tools`() {
            val scoped = tool.resolveScopedTools("research")

            for (vcsTool in listOf(
                "git_status", "git_diff", "git_log", "git_blame",
                "git_show_file", "git_file_history", "git_show_commit",
                "git_branches", "changelist_shelve", "git_stash_list",
                "git_merge_base"
            )) {
                assertTrue(vcsTool in scoped, "research should include VCS tool: $vcsTool")
            }
        }

        @Test
        fun `research scope excludes write tools`() {
            val scoped = tool.resolveScopedTools("research")

            for (writeTool in listOf(
                "edit_file", "create_file", "run_command", "revert_file",
                "kill_process", "send_stdin"
            )) {
                assertFalse(writeTool in scoped, "research should NOT include write tool: $writeTool")
            }
        }

        @Test
        fun `research scope excludes IDE write tools`() {
            val scoped = tool.resolveScopedTools("research")

            for (ideTool in listOf("format_code", "optimize_imports", "refactor_rename")) {
                assertFalse(ideTool in scoped, "research should NOT include IDE write tool: $ideTool")
            }
        }
    }

    @Nested
    inner class ImplementScopeTests {

        @Test
        fun `implement scope includes write tools`() {
            val scoped = tool.resolveScopedTools("implement")

            for (writeTool in listOf(
                "edit_file", "create_file", "run_command", "revert_file",
                "kill_process", "send_stdin"
            )) {
                assertTrue(writeTool in scoped, "implement should include write tool: $writeTool")
            }
        }

        @Test
        fun `implement scope includes read tools`() {
            val scoped = tool.resolveScopedTools("implement")

            assertTrue("read_file" in scoped, "implement should include read_file")
            assertTrue("search_code" in scoped, "implement should include search_code")
            assertTrue("glob_files" in scoped, "implement should include glob_files")
        }

        @Test
        fun `implement scope includes IDE and runtime tools`() {
            val scoped = tool.resolveScopedTools("implement")

            assertTrue("format_code" in scoped, "implement should include format_code")
            assertTrue("optimize_imports" in scoped, "implement should include optimize_imports")
            assertTrue("runtime_exec" in scoped, "implement should include runtime_exec")
            assertTrue("build" in scoped, "implement should include build")
        }

        @Test
        fun `implement scope excludes integration tools`() {
            val scoped = tool.resolveScopedTools("implement")

            for (intTool in listOf(
                "jira", "bamboo_builds", "bamboo_plans",
                "bitbucket_pr", "bitbucket_repo", "bitbucket_review", "sonar"
            )) {
                assertFalse(intTool in scoped, "implement should NOT include integration tool: $intTool")
            }
        }

        @Test
        fun `implement scope excludes database tools`() {
            val scoped = tool.resolveScopedTools("implement")

            for (dbTool in listOf("db_query", "db_schema", "db_list_profiles")) {
                assertFalse(dbTool in scoped, "implement should NOT include database tool: $dbTool")
            }
        }

        @Test
        fun `implement scope excludes ask_user_input`() {
            val scoped = tool.resolveScopedTools("implement")
            assertFalse("ask_user_input" in scoped, "implement should NOT include ask_user_input")
        }
    }

    @Nested
    inner class ReviewScopeTests {

        @Test
        fun `review scope includes research tools`() {
            val scoped = tool.resolveScopedTools("review")

            assertTrue("read_file" in scoped, "review should include read_file")
            assertTrue("search_code" in scoped, "review should include search_code")
            assertTrue("find_definition" in scoped, "review should include find_definition")
            assertTrue("git_diff" in scoped, "review should include git_diff")
        }

        @Test
        fun `review scope includes diagnostic tools`() {
            val scoped = tool.resolveScopedTools("review")

            for (diagTool in listOf(
                "diagnostics", "run_inspections", "problem_view",
                "list_quickfixes", "coverage"
            )) {
                assertTrue(diagTool in scoped, "review should include diagnostic tool: $diagTool")
            }
        }

        @Test
        fun `review scope excludes write tools`() {
            val scoped = tool.resolveScopedTools("review")

            for (writeTool in listOf(
                "edit_file", "create_file", "run_command", "revert_file",
                "kill_process", "send_stdin"
            )) {
                assertFalse(writeTool in scoped, "review should NOT include write tool: $writeTool")
            }
        }
    }

    // ---- Recursion Prevention ----

    @Nested
    inner class RecursionPreventionTests {

        @Test
        fun `agent tool is never in research scope`() {
            val scoped = tool.resolveScopedTools("research")
            assertFalse("agent" in scoped, "agent tool must NEVER be in research scope")
        }

        @Test
        fun `agent tool is never in implement scope`() {
            val scoped = tool.resolveScopedTools("implement")
            assertFalse("agent" in scoped, "agent tool must NEVER be in implement scope")
        }

        @Test
        fun `agent tool is never in review scope`() {
            val scoped = tool.resolveScopedTools("review")
            assertFalse("agent" in scoped, "agent tool must NEVER be in review scope")
        }

        @Test
        fun `agent tool is excluded from all valid scopes`() {
            for (scope in SpawnAgentTool.VALID_SCOPES) {
                val scoped = tool.resolveScopedTools(scope)
                assertFalse("agent" in scoped, "agent tool must be excluded from scope: $scope")
            }
        }
    }

    // ---- Parameter Validation ----

    @Nested
    inner class ParameterValidationTests {

        @Test
        fun `missing description returns error`() = runTest {
            val result = tool.execute(
                params("prompt" to "Do something"),
                project
            )
            assertTrue(result.isError, "Should return error when description is missing")
            assertTrue(result.content.contains("description", ignoreCase = true))
        }

        @Test
        fun `missing prompt returns error`() = runTest {
            val result = tool.execute(
                params("description" to "Test task"),
                project
            )
            assertTrue(result.isError, "Should return error when prompt is missing")
            assertTrue(result.content.contains("prompt", ignoreCase = true))
        }

        @Test
        fun `invalid scope returns error`() = runTest {
            val result = tool.execute(
                params(
                    "description" to "Test",
                    "prompt" to "Do something",
                    "scope" to "invalid_scope"
                ),
                project
            )
            assertTrue(result.isError, "Should return error for invalid scope")
            assertTrue(result.content.contains("Invalid scope"))
        }
    }

    // ---- Max Iterations Clamping ----

    @Nested
    inner class MaxIterationsTests {

        @Test
        fun `max_iterations below minimum is clamped to 5`() {
            // We can't easily test the clamped value without running the full loop,
            // but we can verify the constants are correct
            assertEquals(5, SpawnAgentTool.MIN_ITERATIONS)
            assertEquals(100, SpawnAgentTool.MAX_ITERATIONS)
        }

        @Test
        fun `coerceIn clamps correctly`() {
            assertEquals(5, 1.coerceIn(SpawnAgentTool.MIN_ITERATIONS, SpawnAgentTool.MAX_ITERATIONS))
            assertEquals(50, 50.coerceIn(SpawnAgentTool.MIN_ITERATIONS, SpawnAgentTool.MAX_ITERATIONS))
            assertEquals(100, 999.coerceIn(SpawnAgentTool.MIN_ITERATIONS, SpawnAgentTool.MAX_ITERATIONS))
            assertEquals(5, (-10).coerceIn(SpawnAgentTool.MIN_ITERATIONS, SpawnAgentTool.MAX_ITERATIONS))
        }
    }

    // ---- Full Sub-agent Loop Tests ----

    @Nested
    inner class SubAgentLoopTests {

        /**
         * A fake LlmBrain that returns pre-configured responses in sequence.
         * Same pattern as AgentLoopTest.SequenceBrain.
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

        @Test
        fun `sub-agent completion flows back as ToolResult`() = runTest {
            val brain = SequenceBrain(listOf(
                ApiResult.Success(toolCallResponse(
                    "read_file" to """{"path":"src/main.kt"}"""
                )),
                ApiResult.Success(toolCallResponse(
                    "attempt_completion" to """{"result":"Found the bug in line 42."}"""
                ))
            ))

            val spawnTool = SpawnAgentTool(
                brainProvider = { brain },
                toolRegistry = registry,
                project = project
            )

            val result = spawnTool.execute(
                params(
                    "description" to "Find bug",
                    "prompt" to "Search for the bug in src/main.kt",
                    "scope" to "research"
                ),
                project
            )

            assertFalse(result.isError, "Sub-agent should complete successfully")
            assertTrue(result.content.contains("Agent: Find bug"), "Result should contain agent description")
            assertTrue(result.content.contains("Found the bug in line 42"), "Result should contain agent output")
            assertTrue(result.summary.contains("research"), "Summary should mention scope")
        }

        @Test
        fun `sub-agent failure flows back as error ToolResult`() = runTest {
            val brain = SequenceBrain(listOf(
                ApiResult.Error(ErrorType.AUTH_FAILED, "Authentication failed")
            ))

            val spawnTool = SpawnAgentTool(
                brainProvider = { brain },
                toolRegistry = registry,
                project = project
            )

            val result = spawnTool.execute(
                params(
                    "description" to "Fix bug",
                    "prompt" to "Fix the bug in UserService.kt",
                    "scope" to "implement"
                ),
                project
            )

            assertTrue(result.isError, "Sub-agent failure should return error ToolResult")
            assertTrue(result.content.contains("Failed"), "Error result should mention failure")
            assertTrue(result.content.contains("Agent: Fix bug"), "Error result should contain agent description")
        }

        @Test
        fun `sub-agent uses fresh context independent of parent`() = runTest {
            // The sub-agent brain should receive messages that only include:
            // 1. The sub-agent system prompt
            // 2. The task prompt
            // NOT any parent conversation history
            var capturedMessages: List<ChatMessage>? = null

            val capturingBrain = object : LlmBrain {
                override val modelId = "capturing-brain"

                override suspend fun chat(
                    messages: List<ChatMessage>,
                    tools: List<ToolDefinition>?,
                    maxTokens: Int?,
                    toolChoice: JsonElement?
                ): ApiResult<ChatCompletionResponse> {
                    throw UnsupportedOperationException()
                }

                override suspend fun chatStream(
                    messages: List<ChatMessage>,
                    tools: List<ToolDefinition>?,
                    maxTokens: Int?,
                    onChunk: suspend (StreamChunk) -> Unit
                ): ApiResult<ChatCompletionResponse> {
                    capturedMessages = messages.toList()
                    // Return completion immediately
                    return ApiResult.Success(toolCallResponse(
                        "attempt_completion" to """{"result":"Done."}"""
                    ))
                }

                override fun estimateTokens(text: String) = text.length / 4
                override fun cancelActiveRequest() {}
            }

            val spawnTool = SpawnAgentTool(
                brainProvider = { capturingBrain },
                toolRegistry = registry,
                project = project
            )

            spawnTool.execute(
                params(
                    "description" to "Research task",
                    "prompt" to "Analyze the codebase structure",
                    "scope" to "research"
                ),
                project
            )

            assertNotNull(capturedMessages, "Brain should have been called")
            // First message: system prompt, Second message: user task
            assertEquals(2, capturedMessages!!.size, "Fresh context should have exactly 2 messages (system + task)")
            assertEquals("system", capturedMessages!![0].role)
            assertEquals("user", capturedMessages!![1].role)
            assertTrue(
                capturedMessages!![1].content!!.contains("Analyze the codebase structure"),
                "User message should be the task prompt"
            )
            assertTrue(
                capturedMessages!![0].content!!.contains("sub-agent"),
                "System prompt should identify as sub-agent"
            )
        }

        @Test
        fun `implement scope sub-agent has write tools available`() = runTest {
            var capturedToolNames: List<String>? = null

            val capturingBrain = object : LlmBrain {
                override val modelId = "capturing-brain"

                override suspend fun chat(
                    messages: List<ChatMessage>,
                    tools: List<ToolDefinition>?,
                    maxTokens: Int?,
                    toolChoice: JsonElement?
                ): ApiResult<ChatCompletionResponse> {
                    throw UnsupportedOperationException()
                }

                override suspend fun chatStream(
                    messages: List<ChatMessage>,
                    tools: List<ToolDefinition>?,
                    maxTokens: Int?,
                    onChunk: suspend (StreamChunk) -> Unit
                ): ApiResult<ChatCompletionResponse> {
                    capturedToolNames = tools?.map { it.function.name }
                    return ApiResult.Success(toolCallResponse(
                        "attempt_completion" to """{"result":"Done."}"""
                    ))
                }

                override fun estimateTokens(text: String) = text.length / 4
                override fun cancelActiveRequest() {}
            }

            val spawnTool = SpawnAgentTool(
                brainProvider = { capturingBrain },
                toolRegistry = registry,
                project = project
            )

            spawnTool.execute(
                params(
                    "description" to "Implement feature",
                    "prompt" to "Add null check to UserService.kt",
                    "scope" to "implement"
                ),
                project
            )

            assertNotNull(capturedToolNames, "Brain should have received tool definitions")
            assertTrue("edit_file" in capturedToolNames!!, "implement scope should have edit_file")
            assertTrue("create_file" in capturedToolNames!!, "implement scope should have create_file")
            assertTrue("run_command" in capturedToolNames!!, "implement scope should have run_command")
            assertFalse("agent" in capturedToolNames!!, "agent tool must not be in sub-agent tools")
            assertFalse("jira" in capturedToolNames!!, "integration tools must not be in sub-agent tools")
        }

        @Test
        fun `default scope is implement when not specified`() = runTest {
            var capturedToolNames: List<String>? = null

            val capturingBrain = object : LlmBrain {
                override val modelId = "capturing-brain"

                override suspend fun chat(
                    messages: List<ChatMessage>,
                    tools: List<ToolDefinition>?,
                    maxTokens: Int?,
                    toolChoice: JsonElement?
                ): ApiResult<ChatCompletionResponse> {
                    throw UnsupportedOperationException()
                }

                override suspend fun chatStream(
                    messages: List<ChatMessage>,
                    tools: List<ToolDefinition>?,
                    maxTokens: Int?,
                    onChunk: suspend (StreamChunk) -> Unit
                ): ApiResult<ChatCompletionResponse> {
                    capturedToolNames = tools?.map { it.function.name }
                    return ApiResult.Success(toolCallResponse(
                        "attempt_completion" to """{"result":"Done."}"""
                    ))
                }

                override fun estimateTokens(text: String) = text.length / 4
                override fun cancelActiveRequest() {}
            }

            val spawnTool = SpawnAgentTool(
                brainProvider = { capturingBrain },
                toolRegistry = registry,
                project = project
            )

            // No scope specified -- should default to "implement"
            spawnTool.execute(
                params(
                    "description" to "Fix something",
                    "prompt" to "Fix the thing"
                ),
                project
            )

            assertNotNull(capturedToolNames)
            assertTrue("edit_file" in capturedToolNames!!, "Default scope should be implement with write tools")
        }
    }

    // ---- Tool Metadata ----

    @Nested
    inner class ToolMetadataTests {

        @Test
        fun `tool name is agent`() {
            assertEquals("agent", tool.name)
        }

        @Test
        fun `required parameters are description and prompt`() {
            assertEquals(listOf("description", "prompt"), tool.parameters.required)
        }

        @Test
        fun `scope parameter has enum values`() {
            val scopeParam = tool.parameters.properties["scope"]
            assertNotNull(scopeParam, "scope parameter should exist")
            assertEquals(
                listOf("research", "implement", "review"),
                scopeParam!!.enumValues,
                "scope should have research/implement/review enum values"
            )
        }

        @Test
        fun `description includes usage guidance`() {
            assertTrue(tool.description.contains("Use this when"), "Description should include when to use")
            assertTrue(tool.description.contains("Do NOT use this when"), "Description should include when not to use")
            assertTrue(tool.description.contains("Tips"), "Description should include tips")
        }
    }
}
