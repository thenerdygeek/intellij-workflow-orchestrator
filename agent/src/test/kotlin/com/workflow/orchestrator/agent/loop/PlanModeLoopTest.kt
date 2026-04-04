package com.workflow.orchestrator.agent.loop

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.builtin.ActModeRespondTool
import com.workflow.orchestrator.agent.tools.builtin.PlanModeRespondTool
import com.workflow.orchestrator.core.ai.LlmBrain
import com.workflow.orchestrator.core.ai.dto.*
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PlanModeLoopTest {

    private lateinit var project: Project
    private lateinit var contextManager: ContextManager

    @BeforeEach
    fun setUp() {
        project = mockk(relaxed = true)
        contextManager = ContextManager(maxInputTokens = 100_000)
    }

    // ---- Helpers ----

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

    private class SequenceBrain(private val responses: List<ApiResult<ChatCompletionResponse>>) : LlmBrain {
        override val modelId: String = "test-model"
        private var callIndex = 0

        override suspend fun chat(
            messages: List<ChatMessage>,
            tools: List<ToolDefinition>?,
            maxTokens: Int?,
            toolChoice: JsonElement?
        ): ApiResult<ChatCompletionResponse> {
            throw UnsupportedOperationException("AgentLoop uses chatStream, not chat")
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

    private fun sequenceBrain(vararg responses: ChatCompletionResponse): SequenceBrain =
        SequenceBrain(responses.map { ApiResult.Success(it) })

    private fun fakeTool(
        toolName: String,
        result: ToolResult = ToolResult(content = "ok", summary = "ok", tokenEstimate = 5)
    ): AgentTool = object : AgentTool {
        override val name = toolName
        override val description = "Test tool $toolName"
        override val parameters = FunctionParameters(properties = emptyMap())
        override val allowedWorkers = setOf(WorkerType.CODER)
        override suspend fun execute(params: JsonObject, project: Project) = result
    }

    private fun buildLoop(
        brain: LlmBrain,
        tools: List<AgentTool>,
        planMode: Boolean = false
    ): AgentLoop {
        val toolMap = tools.associateBy { it.name }
        val toolDefs = tools.map { it.toToolDefinition() }
        return AgentLoop(
            brain = brain,
            tools = toolMap,
            toolDefinitions = toolDefs,
            contextManager = contextManager,
            project = project,
            planMode = planMode
        )
    }

    // ---- Plan mode tests ----

    @Nested
    inner class PlanModeTests {

        @Test
        fun `plan mode exits when plan_mode_respond called without needs_more_exploration`() = runTest {
            val brain = sequenceBrain(
                toolCallResponse("read_file" to """{"path":"src/main.kt"}"""),
                toolCallResponse("plan_mode_respond" to """{"response":"Here is my plan: 1. Read, 2. Edit, 3. Test"}""")
            )

            val tools = listOf(
                fakeTool("read_file"),
                PlanModeRespondTool()
            )
            val loop = buildLoop(brain, tools, planMode = true)
            val result = loop.run("Refactor the service layer")

            assertTrue(result is LoopResult.PlanPresented, "should return PlanPresented, got: $result")
            val planResult = result as LoopResult.PlanPresented
            assertEquals("Here is my plan: 1. Read, 2. Edit, 3. Test", planResult.plan)
            assertFalse(planResult.needsMoreExploration)
        }

        @Test
        fun `plan mode continues when needs_more_exploration is true`() = runTest {
            val brain = sequenceBrain(
                // First: LLM calls plan_mode_respond with needs_more_exploration=true
                toolCallResponse("plan_mode_respond" to """{"response":"I need to check more files","needs_more_exploration":true}"""),
                // Second: LLM explores more
                toolCallResponse("read_file" to """{"path":"src/service.kt"}"""),
                // Third: LLM presents final plan
                toolCallResponse("plan_mode_respond" to """{"response":"Final plan: 1. Refactor, 2. Test"}""")
            )

            val tools = listOf(
                fakeTool("read_file"),
                PlanModeRespondTool()
            )
            val loop = buildLoop(brain, tools, planMode = true)
            val result = loop.run("Refactor everything")

            assertTrue(result is LoopResult.PlanPresented, "should eventually return PlanPresented")
            val planResult = result as LoopResult.PlanPresented
            assertTrue(planResult.plan.contains("Final plan"))
        }

        @Test
        fun `PlanPresented result contains plan text and iteration count`() = runTest {
            val brain = sequenceBrain(
                toolCallResponse("plan_mode_respond" to """{"response":"Step 1: A, Step 2: B"}""")
            )

            val tools = listOf(PlanModeRespondTool())
            val loop = buildLoop(brain, tools, planMode = true)
            val result = loop.run("Plan the refactoring") as LoopResult.PlanPresented

            assertEquals("Step 1: A, Step 2: B", result.plan)
            assertEquals(1, result.iterations)
            assertTrue(result.tokensUsed > 0)
        }

        @Test
        fun `plan mode blocks write tools`() = runTest {
            val brain = sequenceBrain(
                // LLM hallucinates a write tool in plan mode
                toolCallResponse("edit_file" to """{"path":"src/main.kt","changes":"..."}"""),
                // Then uses plan_mode_respond properly
                toolCallResponse("plan_mode_respond" to """{"response":"My plan after being corrected"}""")
            )

            val tools = listOf(
                fakeTool("edit_file"),
                PlanModeRespondTool()
            )
            val loop = buildLoop(brain, tools, planMode = true)
            val result = loop.run("Plan changes")

            assertTrue(result is LoopResult.PlanPresented)
        }
    }

    // ---- Act mode respond tests ----

    @Nested
    inner class ActModeRespondTests {

        @Test
        fun `act_mode_respond does not end the loop`() = runTest {
            val brain = sequenceBrain(
                toolCallResponse("act_mode_respond" to """{"response":"I found the issue, now fixing..."}"""),
                toolCallResponse("attempt_completion" to """{"result":"Fixed the bug"}""")
            )

            val completionTool = fakeTool("attempt_completion", ToolResult(
                content = "Fixed the bug",
                summary = "Fixed the bug",
                tokenEstimate = 5,
                isCompletion = true
            ))
            val tools = listOf(ActModeRespondTool(), completionTool)
            val loop = buildLoop(brain, tools, planMode = false)
            val result = loop.run("Fix the bug")

            assertTrue(result is LoopResult.Completed, "should complete after act_mode_respond + attempt_completion")
        }

        @Test
        fun `act_mode_respond consecutive call is blocked`() = runTest {
            val brain = sequenceBrain(
                toolCallResponse("act_mode_respond" to """{"response":"Update 1"}"""),
                // Second consecutive act_mode_respond should be blocked, but loop continues
                toolCallResponse("act_mode_respond" to """{"response":"Update 2"}"""),
                toolCallResponse("attempt_completion" to """{"result":"Done"}""")
            )

            val completionTool = fakeTool("attempt_completion", ToolResult(
                content = "Done",
                summary = "Done",
                tokenEstimate = 5,
                isCompletion = true
            ))
            val tools = listOf(ActModeRespondTool(), completionTool)
            val loop = buildLoop(brain, tools, planMode = false)
            val result = loop.run("Do work")

            // Loop should still complete even after the blocked consecutive call
            assertTrue(result is LoopResult.Completed, "should complete despite blocked consecutive call")
        }
    }
}
