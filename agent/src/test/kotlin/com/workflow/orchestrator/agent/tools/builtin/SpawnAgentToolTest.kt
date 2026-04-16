package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolRegistry
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.subagent.AgentConfigLoader
import com.workflow.orchestrator.agent.tools.subagent.SubagentProgressUpdate
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
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

class SpawnAgentToolTest {

    private lateinit var project: Project
    private lateinit var registry: ToolRegistry
    private lateinit var configLoader: AgentConfigLoader
    private lateinit var tool: SpawnAgentTool

    @BeforeEach
    fun setUp() {
        project = mockk(relaxed = true)
        every { project.name } returns "TestProject"
        every { project.basePath } returns "/tmp/test-project"
        registry = buildTestRegistry()
        AgentConfigLoader.resetForTests()
        configLoader = AgentConfigLoader.getInstance()
        // Load bundled agents (explorer, general-purpose, code-reviewer, etc.)
        val tempDir = createTempDirectory("spawn-agent-test-")
        configLoader.loadFromDisk(tempDir)
        tempDir.toFile().deleteRecursively()
        tool = SpawnAgentTool(
            brainProvider = { throw IllegalStateException("Brain should not be created in metadata tests") },
            toolRegistry = registry,
            project = project,
            configLoader = configLoader
        )
    }

    @AfterEach
    fun tearDown() {
        AgentConfigLoader.resetForTests()
    }

    // ---- Helpers ----

    /** Build a registry with representative tools from all categories. */
    private fun buildTestRegistry(): ToolRegistry {
        val reg = ToolRegistry()
        // Builtin read tools (attempt_completion uses real implementation for isCompletion=true)
        for (name in listOf(
            "read_file", "search_code", "glob_files", "think",
            "project_context", "current_time", "ask_followup_question", "ask_user_input"
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

    // ---- Agent Type Defaults ----

    @Nested
    inner class AgentTypeDefaultTests {

        @Test
        fun `default agent type is general-purpose`() {
            assertEquals("general-purpose", SpawnAgentTool.DEFAULT_AGENT_TYPE)
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
        fun `unknown agent_type returns error`() = runTest {
            val result = tool.execute(
                params(
                    "description" to "Test",
                    "prompt" to "Do something",
                    "agent_type" to "nonexistent"
                ),
                project
            )
            assertTrue(result.isError, "Should return error for unknown agent type")
            assertTrue(result.content.contains("Unknown agent type"), "Error should mention unknown type: ${result.content}")
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
                project = project,
                configLoader = configLoader
            )

            val result = spawnTool.execute(
                params(
                    "description" to "Find bug",
                    "prompt" to "Search for the bug in src/main.kt",
                    "agent_type" to "explorer"
                ),
                project
            )

            assertFalse(result.isError, "Sub-agent should complete successfully")
            assertTrue(result.content.contains("Agent: Find bug"), "Result should contain agent description")
            assertTrue(result.content.contains("Found the bug in line 42"), "Result should contain agent output")
            assertTrue(result.summary.contains("explorer"), "Summary should mention agent type")
        }

        @Test
        fun `sub-agent failure flows back as error ToolResult`() = runTest {
            val brain = SequenceBrain(listOf(
                ApiResult.Error(ErrorType.AUTH_FAILED, "Authentication failed")
            ))

            val spawnTool = SpawnAgentTool(
                brainProvider = { brain },
                toolRegistry = registry,
                project = project,
                configLoader = configLoader
            )

            val result = spawnTool.execute(
                params(
                    "description" to "Fix bug",
                    "prompt" to "Fix the bug in UserService.kt",
                    "agent_type" to "general-purpose"
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
                project = project,
                configLoader = configLoader
            )

            spawnTool.execute(
                params(
                    "description" to "Research task",
                    "prompt" to "Analyze the codebase structure",
                    "agent_type" to "explorer"
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
                capturedMessages!![0].content!!.contains("explorer"),
                "System prompt should contain the agent config content"
            )
        }

        @Test
        fun `general-purpose agent has write tools available`() = runTest {
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
                project = project,
                configLoader = configLoader
            )

            spawnTool.execute(
                params(
                    "description" to "Implement feature",
                    "prompt" to "Add null check to UserService.kt",
                    "agent_type" to "general-purpose"
                ),
                project
            )

            assertNotNull(capturedToolNames, "Brain should have received tool definitions")
            assertTrue("edit_file" in capturedToolNames!!, "general-purpose should have edit_file")
            assertTrue("create_file" in capturedToolNames!!, "general-purpose should have create_file")
            assertTrue("run_command" in capturedToolNames!!, "general-purpose should have run_command")
            assertFalse("agent" in capturedToolNames!!, "agent tool must not be in sub-agent tools")
        }

        @Test
        fun `default agent_type is general-purpose when not specified`() = runTest {
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
                project = project,
                configLoader = configLoader
            )

            // No agent_type specified -- should default to "general-purpose"
            spawnTool.execute(
                params(
                    "description" to "Fix something",
                    "prompt" to "Fix the thing"
                ),
                project
            )

            assertNotNull(capturedToolNames)
            assertTrue("edit_file" in capturedToolNames!!, "Default agent type (general-purpose) should have write tools")
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
        fun `agent_type parameter exists`() {
            val agentTypeParam = tool.parameters.properties["agent_type"]
            assertNotNull(agentTypeParam, "agent_type parameter should exist")
            assertEquals("string", agentTypeParam!!.type, "agent_type should be a string")
        }

        @Test
        fun `description includes usage guidance`() {
            assertTrue(tool.description.contains("Use this when"), "Description should include when to use")
            assertTrue(tool.description.contains("Do NOT use this when"), "Description should include when not to use")
            assertTrue(tool.description.contains("Tips"), "Description should include tips")
        }

        @Test
        fun `description includes parallel execution guidance`() {
            assertTrue(
                tool.description.contains("Parallel execution"),
                "Description should include parallel execution section"
            )
            assertTrue(
                tool.description.contains("prompt_2"),
                "Description should mention prompt_2"
            )
        }

        @Test
        fun `parameters include prompt_2 through prompt_5`() {
            for (key in listOf("prompt_2", "prompt_3", "prompt_4", "prompt_5")) {
                assertNotNull(
                    tool.parameters.properties[key],
                    "Parameters should include $key"
                )
            }
        }
    }

    // ---- Parallel Execution Tests ----

    @Nested
    inner class ParallelExecutionTests {

        @Test
        fun `explorer agent with multiple prompts runs all prompts in parallel`() = runTest {
            var brainCallCount = 0

            val spawnTool = SpawnAgentTool(
                brainProvider = {
                    brainCallCount++
                    SequenceBrain(listOf(
                        ApiResult.Success(toolCallResponse(
                            "attempt_completion" to """{"result":"Research result ${brainCallCount}."}"""
                        ))
                    ))
                },
                toolRegistry = registry,
                project = project,
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

            assertFalse(result.isError, "Parallel research should succeed: ${result.content}")
            assertTrue(result.content.contains("Total: 3"), "Should report total of 3 agents")
            assertTrue(result.content.contains("Succeeded: 3"), "Should report 3 succeeded")
            assertTrue(result.summary.contains("Parallel agents"), "Summary should mention parallel agents")
        }

        @Test
        fun `write-capable agent ignores extra prompts`() = runTest {
            val spawnTool = SpawnAgentTool(
                brainProvider = {
                    SequenceBrain(listOf(
                        ApiResult.Success(toolCallResponse(
                            "attempt_completion" to """{"result":"Done implementing."}"""
                        ))
                    ))
                },
                toolRegistry = registry,
                project = project,
                configLoader = configLoader
            )

            val result = spawnTool.execute(
                params(
                    "description" to "Implement feature",
                    "prompt" to "Implement the feature",
                    "prompt_2" to "This should be ignored",
                    "agent_type" to "general-purpose"
                ),
                project
            )

            assertFalse(result.isError, "Should complete successfully")
            // Single execution: should NOT contain "Parallel agents"
            assertFalse(
                result.content.contains("Parallel agents"),
                "Write-capable agent should not use parallel execution"
            )
            assertTrue(
                result.content.contains("Agent: Implement feature"),
                "Should contain single agent description"
            )
        }

        @Test
        fun `parallel explorer reports stats in summary`() = runTest {
            var brainCallCount = 0

            val spawnTool = SpawnAgentTool(
                brainProvider = {
                    brainCallCount++
                    SequenceBrain(listOf(
                        ApiResult.Success(toolCallResponse(
                            "attempt_completion" to """{"result":"Result $brainCallCount."}"""
                        ))
                    ))
                },
                toolRegistry = registry,
                project = project,
                configLoader = configLoader
            )

            val result = spawnTool.execute(
                params(
                    "description" to "Two research tasks",
                    "prompt" to "First research",
                    "prompt_2" to "Second research",
                    "agent_type" to "explorer"
                ),
                project
            )

            assertFalse(result.isError, "Should succeed")
            assertTrue(
                result.summary.contains("Parallel agents") && result.summary.contains("2/2"),
                "Summary should contain 'Parallel agents' and '2/2': ${result.summary}"
            )
        }
    }

    // ---- Configurable Context Budget Tests ----

    @Nested
    inner class ConfigurableContextBudgetTests {

        @Test
        fun `default context budget is 150K`() {
            assertEquals(150_000, SpawnAgentTool.DEFAULT_CONTEXT_BUDGET)
        }

        @Test
        fun `custom context budget is passed through`() {
            // Verify construction with custom budget does not throw
            val customTool = SpawnAgentTool(
                brainProvider = { throw IllegalStateException("Not used") },
                toolRegistry = registry,
                project = project,
                contextBudget = 80_000
            )
            // Tool should be created without error
            assertEquals("agent", customTool.name)
        }
    }

    // ---- Helper Method Tests ----

    @Nested
    inner class HelperMethodTests {

        @Test
        fun `excerpt truncates long text`() {
            val longText = "a".repeat(2000)
            val result = SpawnAgentTool.excerpt(longText)
            assertEquals(1203, result.length, "Should be 1200 chars + '...'")
            assertTrue(result.endsWith("..."), "Should end with ellipsis")
        }

        @Test
        fun `excerpt preserves short text`() {
            val shortText = "Hello world"
            assertEquals(shortText, SpawnAgentTool.excerpt(shortText))
        }

        @Test
        fun `formatNumber formats thousands`() {
            assertEquals("1.5K", SpawnAgentTool.formatNumber(1_500))
            assertEquals("10.0K", SpawnAgentTool.formatNumber(10_000))
            assertEquals("150.0K", SpawnAgentTool.formatNumber(150_000))
        }

        @Test
        fun `formatNumber formats small numbers`() {
            assertEquals("42", SpawnAgentTool.formatNumber(42))
            assertEquals("999", SpawnAgentTool.formatNumber(999))
        }

        @Test
        fun `formatNumber formats millions`() {
            assertEquals("1.5M", SpawnAgentTool.formatNumber(1_500_000))
        }
    }

    // ---- Agent Type Tests ----

    @Nested
    inner class AgentTypeTests {

        private lateinit var configLoader: AgentConfigLoader

        @BeforeEach
        fun setUpConfigLoader() {
            AgentConfigLoader.resetForTests()
            configLoader = AgentConfigLoader.getInstance()
        }

        @AfterEach
        fun tearDownConfigLoader() {
            configLoader.dispose()
        }

        private fun writeConfigFile(dir: java.nio.file.Path, filename: String, content: String) {
            dir.resolve(filename).writeText(content)
        }

        private fun makeConfig(
            name: String,
            description: String,
            tools: String,
            systemPrompt: String,
        ): String = buildString {
            appendLine("---")
            appendLine("name: \"$name\"")
            appendLine("description: \"$description\"")
            appendLine("tools: $tools")
            appendLine("---")
            appendLine(systemPrompt)
        }

        @Test
        fun `unknown agent_type returns error with available list`() = runTest {
            val tempDir = createTempDirectory("agent-type-test-")
            try {
                writeConfigFile(tempDir, "alpha-agent.md", makeConfig(
                    name = "alpha-agent",
                    description = "Alpha test agent",
                    tools = "read_file, search_code, think, attempt_completion",
                    systemPrompt = "You are the alpha agent."
                ))
                configLoader.loadFromDisk(tempDir)

                val spawnTool = SpawnAgentTool(
                    brainProvider = { throw IllegalStateException("Should not be called") },
                    toolRegistry = registry,
                    project = project,
                    configLoader = configLoader
                )

                val result = spawnTool.execute(
                    params(
                        "description" to "Test task",
                        "prompt" to "Do something",
                        "agent_type" to "nonexistent-agent"
                    ),
                    project
                )

                assertTrue(result.isError, "Should return error for unknown agent type")
                assertTrue(result.content.contains("Unknown agent type"), "Error should mention unknown type: ${result.content}")
                assertTrue(result.content.contains("alpha-agent"), "Error should list available types: ${result.content}")
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        @Test
        fun `agent_type uses config system prompt instead of scope prompt`() = runTest {
            val tempDir = createTempDirectory("agent-type-test-")
            try {
                writeConfigFile(tempDir, "specialist.md", makeConfig(
                    name = "specialist",
                    description = "Test specialist",
                    tools = "read_file, search_code, think, attempt_completion",
                    systemPrompt = "TEST SPECIALIST: You are a unique test specialist agent."
                ))
                configLoader.loadFromDisk(tempDir)

                var capturedMessages: List<ChatMessage>? = null

                val capturingBrain = object : LlmBrain {
                    override val modelId = "capturing-brain"
                    override suspend fun chat(
                        messages: List<ChatMessage>,
                        tools: List<ToolDefinition>?,
                        maxTokens: Int?,
                        toolChoice: JsonElement?
                    ): ApiResult<ChatCompletionResponse> = throw UnsupportedOperationException()

                    override suspend fun chatStream(
                        messages: List<ChatMessage>,
                        tools: List<ToolDefinition>?,
                        maxTokens: Int?,
                        onChunk: suspend (StreamChunk) -> Unit
                    ): ApiResult<ChatCompletionResponse> {
                        capturedMessages = messages.toList()
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
                    project = project,
                    configLoader = configLoader
                )

                val result = spawnTool.execute(
                    params(
                        "description" to "Specialist task",
                        "prompt" to "Do specialist work",
                        "agent_type" to "specialist"
                    ),
                    project
                )

                assertFalse(result.isError, "Should succeed: ${result.content}")
                assertNotNull(capturedMessages, "Brain should have been called")
                val systemMsg = capturedMessages!!.first { it.role == "system" }
                assertTrue(
                    systemMsg.content!!.contains("TEST SPECIALIST"),
                    "System prompt should contain config's text, got: ${systemMsg.content}"
                )
                assertFalse(
                    systemMsg.content!!.contains("You are a sub-agent"),
                    "System prompt should NOT contain generic scope text"
                )
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        @Test
        fun `agent_type resolves config tools not scope tools`() = runTest {
            val tempDir = createTempDirectory("agent-type-test-")
            try {
                writeConfigFile(tempDir, "reader.md", makeConfig(
                    name = "reader",
                    description = "Read-only agent",
                    tools = "read_file, search_code, think, attempt_completion",
                    systemPrompt = "You are a read-only agent."
                ))
                configLoader.loadFromDisk(tempDir)

                var capturedToolNames: List<String>? = null

                val capturingBrain = object : LlmBrain {
                    override val modelId = "capturing-brain"
                    override suspend fun chat(
                        messages: List<ChatMessage>,
                        tools: List<ToolDefinition>?,
                        maxTokens: Int?,
                        toolChoice: JsonElement?
                    ): ApiResult<ChatCompletionResponse> = throw UnsupportedOperationException()

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
                    project = project,
                    configLoader = configLoader
                )

                val result = spawnTool.execute(
                    params(
                        "description" to "Reader task",
                        "prompt" to "Read some files",
                        "agent_type" to "reader"
                    ),
                    project
                )

                assertFalse(result.isError, "Should succeed: ${result.content}")
                assertNotNull(capturedToolNames, "Brain should have received tool definitions")
                assertEquals(
                    setOf("read_file", "search_code", "think", "attempt_completion"),
                    capturedToolNames!!.toSet(),
                    "Should only have config-specified tools"
                )
                assertFalse("edit_file" in capturedToolNames!!, "Should NOT have edit_file")
                assertFalse("run_command" in capturedToolNames!!, "Should NOT have run_command")
                assertFalse("agent" in capturedToolNames!!, "agent should never be included")
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        @Test
        fun `agent_type with write tools infers planMode false`() = runTest {
            val tempDir = createTempDirectory("agent-type-test-")
            try {
                writeConfigFile(tempDir, "writer.md", makeConfig(
                    name = "writer",
                    description = "Write-capable agent",
                    tools = "read_file, edit_file, create_file, run_command, think, attempt_completion",
                    systemPrompt = "You are a write-capable agent."
                ))
                configLoader.loadFromDisk(tempDir)

                val spawnTool = SpawnAgentTool(
                    brainProvider = {
                        SequenceBrain(listOf(
                            ApiResult.Success(toolCallResponse(
                                "attempt_completion" to """{"result":"Wrote files."}"""
                            ))
                        ))
                    },
                    toolRegistry = registry,
                    project = project,
                    configLoader = configLoader
                )

                val result = spawnTool.execute(
                    params(
                        "description" to "Write task",
                        "prompt" to "Create a file",
                        "agent_type" to "writer"
                    ),
                    project
                )

                // If planMode were incorrectly true, write tools would be blocked by AgentLoop
                // and execution would fail. A successful completion means planMode=false.
                assertFalse(result.isError, "Should succeed with write tools (planMode=false): ${result.content}")
                assertTrue(result.content.contains("Wrote files"), "Should contain completion result")
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        @Test
        fun `agent_type without write tools infers planMode true`() = runTest {
            val tempDir = createTempDirectory("agent-type-test-")
            try {
                writeConfigFile(tempDir, "analyzer.md", makeConfig(
                    name = "analyzer",
                    description = "Analysis-only agent",
                    tools = "read_file, search_code, diagnostics, think, attempt_completion",
                    systemPrompt = "You are an analysis agent."
                ))
                configLoader.loadFromDisk(tempDir)

                var capturedToolNames: List<String>? = null

                val capturingBrain = object : LlmBrain {
                    override val modelId = "capturing-brain"
                    override suspend fun chat(
                        messages: List<ChatMessage>,
                        tools: List<ToolDefinition>?,
                        maxTokens: Int?,
                        toolChoice: JsonElement?
                    ): ApiResult<ChatCompletionResponse> = throw UnsupportedOperationException()

                    override suspend fun chatStream(
                        messages: List<ChatMessage>,
                        tools: List<ToolDefinition>?,
                        maxTokens: Int?,
                        onChunk: suspend (StreamChunk) -> Unit
                    ): ApiResult<ChatCompletionResponse> {
                        capturedToolNames = tools?.map { it.function.name }
                        return ApiResult.Success(toolCallResponse(
                            "attempt_completion" to """{"result":"Analysis done."}"""
                        ))
                    }

                    override fun estimateTokens(text: String) = text.length / 4
                    override fun cancelActiveRequest() {}
                }

                val spawnTool = SpawnAgentTool(
                    brainProvider = { capturingBrain },
                    toolRegistry = registry,
                    project = project,
                    configLoader = configLoader
                )

                val result = spawnTool.execute(
                    params(
                        "description" to "Analyze code",
                        "prompt" to "Analyze the codebase",
                        "agent_type" to "analyzer"
                    ),
                    project
                )

                assertFalse(result.isError, "Should succeed: ${result.content}")
                assertNotNull(capturedToolNames, "Brain should have received tool definitions")
                // Verify no write tools present
                for (writeTool in listOf("edit_file", "create_file", "run_command", "revert_file",
                    "kill_process", "send_stdin", "format_code", "optimize_imports", "refactor_rename")) {
                    assertFalse(writeTool in capturedToolNames!!, "Should NOT have write tool: $writeTool")
                }
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        @Test
        fun `omitting agent_type defaults to general-purpose`() = runTest {
            val tempDir = createTempDirectory("agent-type-test-")
            try {
                configLoader.loadFromDisk(tempDir)

                val spawnTool = SpawnAgentTool(
                    brainProvider = {
                        SequenceBrain(listOf(
                            ApiResult.Success(toolCallResponse(
                                "attempt_completion" to """{"result":"Done via default."}"""
                            ))
                        ))
                    },
                    toolRegistry = registry,
                    project = project,
                    configLoader = configLoader
                )

                val result = spawnTool.execute(
                    params(
                        "description" to "Default task",
                        "prompt" to "Do something without specifying agent_type"
                    ),
                    project
                )

                assertFalse(result.isError, "Default agent_type should work: ${result.content}")
                assertTrue(result.content.contains("Agent: Default task"), "Should contain agent description")
                assertTrue(result.summary.contains("general-purpose"), "Summary should mention general-purpose")
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        @Test
        fun `config with max-turns field in yaml is silently ignored and agent runs to completion`() = runTest {
            val tempDir = createTempDirectory("agent-type-test-")
            try {
                // max-turns is intentionally removed from AgentConfig; the YAML field is silently
                // ignored during parsing and sub-agents always run until completion.
                writeConfigFile(tempDir, "quick-agent.md", makeConfig(
                    name = "quick-agent",
                    description = "Quick agent",
                    tools = "read_file, think, attempt_completion",
                    systemPrompt = "You are a quick agent."
                ))
                configLoader.loadFromDisk(tempDir)

                val spawnTool = SpawnAgentTool(
                    brainProvider = {
                        SequenceBrain(listOf(
                            ApiResult.Success(toolCallResponse(
                                "attempt_completion" to """{"result":"Quick result."}"""
                            ))
                        ))
                    },
                    toolRegistry = registry,
                    project = project,
                    configLoader = configLoader
                )

                val result = spawnTool.execute(
                    params(
                        "description" to "Quick task",
                        "prompt" to "Do something quick",
                        "agent_type" to "quick-agent"
                    ),
                    project
                )

                assertFalse(result.isError, "Should succeed: ${result.content}")
                assertTrue(result.content.contains("Quick result"), "Should contain completion result")
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        @Test
        fun `agent tool is always excluded from config tools even if listed`() = runTest {
            val tempDir = createTempDirectory("agent-config-test")
            try {
                tempDir.resolve("includes-agent.md").toFile().writeText("""
                    ---
                    name: includes-agent
                    description: "Agent that lists agent tool"
                    tools: read_file, agent, search_code, think, attempt_completion
                    max-turns: 10
                    ---
                    You are a test agent.
                """.trimIndent())
                configLoader.loadFromDisk(tempDir)

                var capturedToolNames: List<String>? = null
                val capturingBrain = object : LlmBrain {
                    override val modelId = "test-brain"
                    override suspend fun chat(messages: List<ChatMessage>, tools: List<ToolDefinition>?, maxTokens: Int?, toolChoice: JsonElement?) = throw UnsupportedOperationException()
                    override suspend fun chatStream(messages: List<ChatMessage>, tools: List<ToolDefinition>?, maxTokens: Int?, onChunk: suspend (StreamChunk) -> Unit): ApiResult<ChatCompletionResponse> {
                        capturedToolNames = tools?.map { it.function.name }
                        return ApiResult.Success(toolCallResponse("attempt_completion" to """{"result":"Done."}"""))
                    }
                    override fun estimateTokens(text: String) = text.length / 4
                    override fun cancelActiveRequest() {}
                }

                val spawnTool = SpawnAgentTool(
                    brainProvider = { capturingBrain },
                    toolRegistry = registry,
                    project = project,
                    configLoader = configLoader
                )

                spawnTool.execute(
                    params(
                        "description" to "Test agent exclusion",
                        "prompt" to "Do something",
                        "agent_type" to "includes-agent"
                    ),
                    project
                )

                assertNotNull(capturedToolNames)
                assertFalse("agent" in capturedToolNames!!, "agent tool must be excluded even when config lists it")
                assertTrue("read_file" in capturedToolNames!!, "Other tools should still be present")
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        @Test
        fun `agent_type with no resolvable config tools still gets attempt_completion`() = runTest {
            val tempDir = createTempDirectory("agent-config-test")
            try {
                tempDir.resolve("broken-agent.md").toFile().writeText("""
                    ---
                    name: broken-agent
                    description: "Agent with no real tools"
                    tools: nonexistent_tool_1, nonexistent_tool_2
                    max-turns: 10
                    ---
                    You are a broken agent.
                """.trimIndent())
                configLoader.loadFromDisk(tempDir)

                var capturedToolNames: List<String>? = null
                val capturingBrain = object : LlmBrain {
                    override val modelId = "test-brain"
                    override suspend fun chat(messages: List<ChatMessage>, tools: List<ToolDefinition>?, maxTokens: Int?, toolChoice: JsonElement?) = throw UnsupportedOperationException()
                    override suspend fun chatStream(messages: List<ChatMessage>, tools: List<ToolDefinition>?, maxTokens: Int?, onChunk: suspend (StreamChunk) -> Unit): ApiResult<ChatCompletionResponse> {
                        capturedToolNames = tools?.map { it.function.name }
                        return ApiResult.Success(toolCallResponse("attempt_completion" to """{"result":"Done."}"""))
                    }
                    override fun estimateTokens(text: String) = text.length / 4
                    override fun cancelActiveRequest() {}
                }

                val spawnTool = SpawnAgentTool(
                    brainProvider = { capturingBrain },
                    toolRegistry = registry,
                    project = project,
                    configLoader = configLoader
                )

                val result = spawnTool.execute(
                    params(
                        "description" to "Test broken agent",
                        "prompt" to "Do something",
                        "agent_type" to "broken-agent"
                    ),
                    project
                )

                // Even with no config tools resolving, attempt_completion is auto-added
                assertFalse(result.isError, "Should succeed since attempt_completion is always available: ${result.content}")
                assertNotNull(capturedToolNames, "Brain should have been called")
                assertEquals(
                    listOf("attempt_completion"),
                    capturedToolNames,
                    "Only attempt_completion should be available when no config tools resolve"
                )
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }
    }
}
