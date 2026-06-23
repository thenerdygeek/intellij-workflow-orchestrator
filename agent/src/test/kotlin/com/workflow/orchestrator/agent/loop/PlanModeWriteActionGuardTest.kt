package com.workflow.orchestrator.agent.loop

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.builtin.PlanModeRespondTool
import com.workflow.orchestrator.agent.tools.project.ProjectStructureTool
import com.workflow.orchestrator.agent.tools.runtime.RuntimeConfigTool
import com.workflow.orchestrator.core.ai.LlmBrain
import com.workflow.orchestrator.core.ai.dto.*
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.core.model.ModelPricingRegistry
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Regression test for the plan-mode write-action guard bypass in per-action meta-tools.
 *
 * Bug (Batches 16 + 25 of the Phase 5 swarm):
 * - `project_structure` has 8 write actions that mutate the IDE project model.
 * - `runtime_config` has 3 write actions (create/modify/delete_run_config).
 * - None were in [AgentLoop.WRITE_TOOLS], so the execution guard in plan mode
 *   would silently let them through.
 *
 * Fix:
 * - Added [AgentTool.isWriteAction] interface method with default=false.
 * - [ProjectStructureTool] and [RuntimeConfigTool] override it to return true
 *   for their mutating actions.
 * - The plan-mode guard now checks:
 *   `toolName in WRITE_TOOLS || tool.isWriteAction(action)`.
 */
class PlanModeWriteActionGuardTest {

    // The agent loop lazily starts a ModelPricingRegistry watcher thread when computing cost;
    // reset it after each test so the IntelliJ ThreadLeakTracker doesn't flag the leak.
    @AfterEach
    fun stopModelPricingWatcher() {
        runCatching { ModelPricingRegistry.resetForTests() }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Tier 1 — isWriteAction contract on the tools themselves
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    inner class ProjectStructureToolWriteActions {

        private val tool = ProjectStructureTool()

        @Test
        fun `set_module_dependency is a write action`() {
            assertTrue(tool.isWriteAction("set_module_dependency"),
                "set_module_dependency mutates the module model — must be classified as write")
        }

        @Test
        fun `remove_module_dependency is a write action`() {
            assertTrue(tool.isWriteAction("remove_module_dependency"))
        }

        @Test
        fun `add_source_root is a write action`() {
            assertTrue(tool.isWriteAction("add_source_root"))
        }

        @Test
        fun `set_module_sdk is a write action`() {
            assertTrue(tool.isWriteAction("set_module_sdk"))
        }

        @Test
        fun `set_language_level is a write action`() {
            assertTrue(tool.isWriteAction("set_language_level"))
        }

        @Test
        fun `add_content_root is a write action`() {
            assertTrue(tool.isWriteAction("add_content_root"))
        }

        @Test
        fun `remove_content_root is a write action`() {
            assertTrue(tool.isWriteAction("remove_content_root"))
        }

        @Test
        fun `refresh_external_project is a write action`() {
            assertTrue(tool.isWriteAction("refresh_external_project"))
        }

        @Test
        fun `module_detail is NOT a write action`() {
            assertFalse(tool.isWriteAction("module_detail"),
                "module_detail is a pure read — must NOT be classified as write")
        }

        @Test
        fun `resolve_file is NOT a write action`() {
            assertFalse(tool.isWriteAction("resolve_file"))
        }

        @Test
        fun `topology is NOT a write action`() {
            assertFalse(tool.isWriteAction("topology"))
        }

        @Test
        fun `list_sdks is NOT a write action`() {
            assertFalse(tool.isWriteAction("list_sdks"))
        }

        @Test
        fun `list_libraries is NOT a write action`() {
            assertFalse(tool.isWriteAction("list_libraries"))
        }

        @Test
        fun `list_facets is NOT a write action`() {
            assertFalse(tool.isWriteAction("list_facets"))
        }

        @Test
        fun `null action is NOT a write action`() {
            assertFalse(tool.isWriteAction(null))
        }
    }

    @Nested
    inner class RuntimeConfigToolWriteActions {

        private val tool = RuntimeConfigTool()

        @Test
        fun `create_run_config is a write action`() {
            assertTrue(tool.isWriteAction("create_run_config"),
                "create_run_config mutates RunManager — must be classified as write")
        }

        @Test
        fun `modify_run_config is a write action`() {
            assertTrue(tool.isWriteAction("modify_run_config"))
        }

        @Test
        fun `delete_run_config is a write action`() {
            assertTrue(tool.isWriteAction("delete_run_config"))
        }

        @Test
        fun `get_run_configurations is NOT a write action`() {
            assertFalse(tool.isWriteAction("get_run_configurations"),
                "get_run_configurations is read-only — must NOT be classified as write")
        }

        @Test
        fun `null action is NOT a write action`() {
            assertFalse(tool.isWriteAction(null))
        }
    }

    @Nested
    inner class DefaultImplementation {

        @Test
        fun `default isWriteAction returns false for any action`() {
            val simpleTool: AgentTool = object : AgentTool {
                override val name = "simple_tool"
                override val description = "A simple tool"
                override val parameters = FunctionParameters(properties = emptyMap())
                override val allowedWorkers = setOf(WorkerType.CODER)
                override suspend fun execute(params: JsonObject, project: Project): ToolResult =
                    ToolResult(content = "ok", summary = "ok", tokenEstimate = 5)
            }
            assertFalse(simpleTool.isWriteAction("anything"),
                "Default implementation must return false — existing tools are unaffected")
            assertFalse(simpleTool.isWriteAction(null))
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Tier 2 — End-to-end plan-mode guard via AgentLoop
    // ──────────────────────────────────────────────────────────────────────

    private class SequenceBrain(private val responses: List<ApiResult<ChatCompletionResponse>>) : LlmBrain {
        override val modelId: String = "test-model"
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
                return ApiResult.Error(ErrorType.SERVER_ERROR, "No more scripted responses (call #$callIndex)")
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

    private fun buildLoop(
        brain: LlmBrain,
        tools: List<AgentTool>,
        planMode: Boolean = false,
        planModeProvider: (() -> Boolean)? = null,
    ): AgentLoop {
        val toolMap = tools.associateBy { it.name }
        val toolDefs = tools.map { it.toToolDefinition() }
        return AgentLoop(
            brain = brain,
            tools = toolMap,
            toolDefinitions = toolDefs,
            contextManager = ContextManager(maxInputTokens = 100_000),
            project = mockk(relaxed = true),
            planMode = planMode,
            planModeProvider = planModeProvider,
        )
    }

    private fun fakeEditFile(executed: MutableList<String>): AgentTool = object : AgentTool {
        override val name = "edit_file"
        override val description = "Fake edit_file"
        override val parameters = FunctionParameters(properties = emptyMap())
        override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.ORCHESTRATOR)
        override val isMutating = true
        override suspend fun execute(params: JsonObject, project: Project): ToolResult {
            executed.add("edit_file")
            return ToolResult(content = "ok", summary = "ok", tokenEstimate = 5)
        }
    }

    private fun fakePlanModeRespond(): AgentTool = object : AgentTool {
        override val name = "plan_mode_respond"
        override val description = "Fake plan_mode_respond"
        override val parameters = FunctionParameters(properties = emptyMap())
        override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR)
        override suspend fun execute(params: JsonObject, project: Project): ToolResult =
            ToolResult(content = "plan", summary = "plan", tokenEstimate = 5,
                isPlanResponse = true, type = com.workflow.orchestrator.agent.tools.ToolResultType.PlanResponse(false))
    }

    private fun fakeCompletion(): AgentTool = object : AgentTool {
        override val name = "attempt_completion"
        override val description = "Fake completion"
        override val parameters = FunctionParameters(properties = emptyMap())
        override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR)
        override suspend fun execute(params: JsonObject, project: Project): ToolResult =
            ToolResult(content = "done", summary = "done", tokenEstimate = 5,
                isCompletion = true, type = com.workflow.orchestrator.agent.tools.ToolResultType.Completion)
    }

    @Test
    fun `project_structure write action set_module_dependency is BLOCKED in plan mode`() = runTest {
        // LLM tries to call project_structure with action=set_module_dependency in plan mode —
        // this must be blocked. After the block, LLM calls attempt_completion.
        val writeCallArgs = """{"action":"set_module_dependency","module":"core","dependsOn":"agent","scope":"compile"}"""
        val brain = SequenceBrain(
            listOf(
                // LLM hallucinates a write action in plan mode
                ApiResult.Success(toolCallResponse("project_structure" to writeCallArgs)),
                // After error feedback, LLM completes
                ApiResult.Success(toolCallResponse("attempt_completion" to """{"result":"Done"}""")),
            )
        )

        val toolExecuted = mutableListOf<String>()
        val projectStructureTool = object : AgentTool by ProjectStructureTool() {
            override suspend fun execute(params: JsonObject, project: Project): ToolResult {
                toolExecuted.add("project_structure.set_module_dependency")
                return ToolResult(content = "ok", summary = "ok", tokenEstimate = 5)
            }
        }

        val loop = buildLoop(
            brain = brain,
            tools = listOf(projectStructureTool, fakeCompletion()),
            planMode = true,
        )

        val result = loop.run("Plan some changes")

        assertTrue(result is LoopResult.Completed, "loop should complete after block, got: $result")
        assertTrue(toolExecuted.isEmpty(),
            "project_structure.set_module_dependency must NOT execute in plan mode, but got: $toolExecuted")
    }

    @Test
    fun `project_structure read action module_detail is ALLOWED in plan mode`() = runTest {
        val readCallArgs = """{"action":"module_detail","module":"core"}"""
        val brain = SequenceBrain(
            listOf(
                ApiResult.Success(toolCallResponse("project_structure" to readCallArgs)),
                ApiResult.Success(toolCallResponse("attempt_completion" to """{"result":"Done"}""")),
            )
        )

        val toolExecuted = mutableListOf<String>()
        val projectStructureTool = object : AgentTool by ProjectStructureTool() {
            override suspend fun execute(params: JsonObject, project: Project): ToolResult {
                toolExecuted.add("project_structure.module_detail")
                return ToolResult(content = "module info", summary = "module info", tokenEstimate = 5)
            }
        }

        val loop = buildLoop(
            brain = brain,
            tools = listOf(projectStructureTool, fakeCompletion()),
            planMode = true,
        )

        val result = loop.run("Analyze the project structure")

        assertTrue(result is LoopResult.Completed, "loop should complete, got: $result")
        assertEquals(listOf("project_structure.module_detail"), toolExecuted,
            "module_detail is a read action — it MUST execute in plan mode")
    }

    @Test
    fun `runtime_config write action create_run_config is BLOCKED in plan mode`() = runTest {
        val writeCallArgs = """{"action":"create_run_config","name":"MyApp","type":"application","main_class":"com.example.Main"}"""
        val brain = SequenceBrain(
            listOf(
                ApiResult.Success(toolCallResponse("runtime_config" to writeCallArgs)),
                ApiResult.Success(toolCallResponse("attempt_completion" to """{"result":"Done"}""")),
            )
        )

        val toolExecuted = mutableListOf<String>()
        val runtimeConfigTool = object : AgentTool by RuntimeConfigTool() {
            override suspend fun execute(params: JsonObject, project: Project): ToolResult {
                toolExecuted.add("runtime_config.create_run_config")
                return ToolResult(content = "ok", summary = "ok", tokenEstimate = 5)
            }
        }

        val loop = buildLoop(
            brain = brain,
            tools = listOf(runtimeConfigTool, fakeCompletion()),
            planMode = true,
        )

        val result = loop.run("Plan a run config")

        assertTrue(result is LoopResult.Completed, "loop should complete after block, got: $result")
        assertTrue(toolExecuted.isEmpty(),
            "runtime_config.create_run_config must NOT execute in plan mode, but got: $toolExecuted")
    }

    // ──────────────────────────────────────────────────────────────────────
    // Tier 3 — the guard must use the LIVE plan-mode state, not a stale snapshot
    // Regression: the loop is built in plan mode (snapshot=true); the user approves
    // the plan (live state → ACT). environment_details + schema filter read the live
    // state (ACT) and offer edit_file, but the execution guard read the construction-
    // time snapshot (PLAN) → "edit_file is blocked in plan mode" while env says ACT MODE.
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `edit_file is ALLOWED when snapshot is plan but live state is act (post-approval)`() = runTest {
        val brain = SequenceBrain(
            listOf(
                ApiResult.Success(toolCallResponse("edit_file" to """{"path":"X.kt"}""")),
                ApiResult.Success(toolCallResponse("attempt_completion" to """{"result":"Done"}""")),
            )
        )
        val executed = mutableListOf<String>()
        val loop = buildLoop(
            brain = brain,
            tools = listOf(fakeEditFile(executed), fakeCompletion()),
            planMode = true, // snapshot captured while in plan mode
            planModeProvider = { false }, // live state: user approved → ACT
        )

        val result = loop.run("Implement the plan")

        assertTrue(result is LoopResult.Completed, "loop should complete, got: $result")
        assertEquals(
            listOf("edit_file"),
            executed,
            "edit_file MUST execute once live state is ACT, even if the loop's snapshot was PLAN",
        )
    }

    @Test
    fun `edit_file is BLOCKED when live state is plan regardless of snapshot`() = runTest {
        val brain = SequenceBrain(
            listOf(
                ApiResult.Success(toolCallResponse("edit_file" to """{"path":"X.kt"}""")),
                ApiResult.Success(toolCallResponse("attempt_completion" to """{"result":"Done"}""")),
            )
        )
        val executed = mutableListOf<String>()
        val loop = buildLoop(
            brain = brain,
            tools = listOf(fakeEditFile(executed), fakeCompletion()),
            planMode = false, // snapshot says act...
            planModeProvider = { true }, // ...but live state is PLAN
        )

        val result = loop.run("Plan only")

        assertTrue(result is LoopResult.Completed, "loop should complete after block, got: $result")
        assertTrue(executed.isEmpty(), "edit_file must be blocked when the LIVE state is plan mode, got: $executed")
    }

    @Test
    fun `runtime_config read action get_run_configurations is ALLOWED in plan mode`() = runTest {
        val readCallArgs = """{"action":"get_run_configurations"}"""
        val brain = SequenceBrain(
            listOf(
                ApiResult.Success(toolCallResponse("runtime_config" to readCallArgs)),
                ApiResult.Success(toolCallResponse("attempt_completion" to """{"result":"Done"}""")),
            )
        )

        val toolExecuted = mutableListOf<String>()
        val runtimeConfigTool = object : AgentTool by RuntimeConfigTool() {
            override suspend fun execute(params: JsonObject, project: Project): ToolResult {
                toolExecuted.add("runtime_config.get_run_configurations")
                return ToolResult(content = "configs", summary = "configs", tokenEstimate = 5)
            }
        }

        val loop = buildLoop(
            brain = brain,
            tools = listOf(runtimeConfigTool, fakeCompletion()),
            planMode = true,
        )

        val result = loop.run("List the run configurations")

        assertTrue(result is LoopResult.Completed, "loop should complete, got: $result")
        assertEquals(listOf("runtime_config.get_run_configurations"), toolExecuted,
            "get_run_configurations is a read action — it MUST execute in plan mode")
    }
}
