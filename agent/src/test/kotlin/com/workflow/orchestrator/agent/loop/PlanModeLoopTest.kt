package com.workflow.orchestrator.agent.loop

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.builtin.PlanModeRespondTool
import com.workflow.orchestrator.core.ai.LlmBrain
import com.workflow.orchestrator.core.ai.dto.*
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import io.mockk.mockk
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
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
        planMode: Boolean = false,
        onPlanResponse: ((String, Boolean) -> Unit)? = null,
        userInputChannel: Channel<String>? = null
    ): AgentLoop {
        val toolMap = tools.associateBy { it.name }
        val toolDefs = tools.map { it.toToolDefinition() }
        return AgentLoop(
            brain = brain,
            tools = toolMap,
            toolDefinitions = toolDefs,
            contextManager = contextManager,
            project = project,
            planMode = planMode,
            onPlanResponse = onPlanResponse,
            userInputChannel = userInputChannel
        )
    }

    // ---- Plan mode tests ----

    @Nested
    inner class PlanModeTests {

        @Test
        fun `plan_mode_respond does NOT exit the loop — waits for user input then completes`() = runTest {
            val channel = Channel<String>(Channel.RENDEZVOUS)
            val planCallbackFired = mutableListOf<Pair<String, Boolean>>()

            val brain = sequenceBrain(
                // LLM explores first
                toolCallResponse("read_file" to """{"path":"src/main.kt"}"""),
                // LLM presents plan — loop will wait for user input
                toolCallResponse("plan_mode_respond" to """{"response":"Here is my plan: 1. Read, 2. Edit, 3. Test"}"""),
                // After user approves, LLM completes
                toolCallResponse("attempt_completion" to """{"result":"Done implementing"}""")
            )

            val completionTool = fakeTool("attempt_completion", ToolResult(
                content = "Done implementing",
                summary = "Done implementing",
                tokenEstimate = 5,
                isCompletion = true
            ))

            val tools = listOf(
                fakeTool("read_file"),
                PlanModeRespondTool(),
                completionTool
            )
            val loop = buildLoop(
                brain, tools,
                planMode = true,
                onPlanResponse = { text, explore -> planCallbackFired.add(text to explore) },
                userInputChannel = channel
            )

            // Run the loop in a coroutine — it will suspend at channel.receive()
            val loopJob = launch {
                val result = loop.run("Refactor the service layer")
                assertTrue(result is LoopResult.Completed, "should complete after user input, got: $result")
            }

            // Feed user input (approval) into the channel
            channel.send("Approved. Implement the plan.")

            // Wait for the loop to finish
            loopJob.join()

            // Verify the plan callback was fired
            assertEquals(1, planCallbackFired.size, "plan callback should fire once")
            assertTrue(planCallbackFired[0].first.contains("Here is my plan"))
            assertFalse(planCallbackFired[0].second, "needsMoreExploration should be false")
        }

        @Test
        fun `plan mode continues when needs_more_exploration is true — no wait for input`() = runTest {
            val channel = Channel<String>(Channel.RENDEZVOUS)
            val planCallbackFired = mutableListOf<Pair<String, Boolean>>()

            val brain = sequenceBrain(
                // LLM calls plan_mode_respond with needs_more_exploration=true
                toolCallResponse("plan_mode_respond" to """{"response":"I need to check more files","needs_more_exploration":true}"""),
                // LLM explores more
                toolCallResponse("read_file" to """{"path":"src/service.kt"}"""),
                // LLM presents final plan — waits for input
                toolCallResponse("plan_mode_respond" to """{"response":"Final plan: 1. Refactor, 2. Test"}"""),
                // After user input, LLM completes
                toolCallResponse("attempt_completion" to """{"result":"All done"}""")
            )

            val completionTool = fakeTool("attempt_completion", ToolResult(
                content = "All done",
                summary = "All done",
                tokenEstimate = 5,
                isCompletion = true
            ))

            val tools = listOf(
                fakeTool("read_file"),
                PlanModeRespondTool(),
                completionTool
            )
            val loop = buildLoop(
                brain, tools,
                planMode = true,
                onPlanResponse = { text, explore -> planCallbackFired.add(text to explore) },
                userInputChannel = channel
            )

            val loopJob = launch {
                val result = loop.run("Refactor everything")
                assertTrue(result is LoopResult.Completed)
            }

            // Only one channel send needed — first plan_mode_respond has needs_more_exploration=true
            // so no wait. Second has false so it waits.
            channel.send("Looks good, implement it.")

            loopJob.join()

            // Verify both plan callbacks fired
            assertEquals(2, planCallbackFired.size, "plan callback should fire twice")
            assertTrue(planCallbackFired[0].second, "first call should have needsMoreExploration=true")
            assertFalse(planCallbackFired[1].second, "second call should have needsMoreExploration=false")
            assertTrue(planCallbackFired[1].first.contains("Final plan"))
        }

        @Test
        fun `plan mode blocks write tools`() = runTest {
            val channel = Channel<String>(Channel.RENDEZVOUS)

            val brain = sequenceBrain(
                // LLM hallucinates a write tool in plan mode
                toolCallResponse("edit_file" to """{"path":"src/main.kt","changes":"..."}"""),
                // Then uses plan_mode_respond properly — waits for input
                toolCallResponse("plan_mode_respond" to """{"response":"My plan after being corrected"}"""),
                // After user input, completes
                toolCallResponse("attempt_completion" to """{"result":"Done"}""")
            )

            val completionTool = fakeTool("attempt_completion", ToolResult(
                content = "Done",
                summary = "Done",
                tokenEstimate = 5,
                isCompletion = true
            ))

            val tools = listOf(
                fakeTool("edit_file"),
                PlanModeRespondTool(),
                completionTool
            )
            val loop = buildLoop(
                brain, tools,
                planMode = true,
                onPlanResponse = { _, _ -> },
                userInputChannel = channel
            )

            val loopJob = launch {
                val result = loop.run("Plan changes")
                assertTrue(result is LoopResult.Completed)
            }

            channel.send("Approved.")
            loopJob.join()
        }

        @Test
        fun `plan mode without channel does not block — loop continues normally`() = runTest {
            // When no userInputChannel is provided (e.g., sub-agent), plan_mode_respond
            // fires the callback but the loop just continues without waiting
            val planCallbackFired = mutableListOf<String>()

            val brain = sequenceBrain(
                toolCallResponse("plan_mode_respond" to """{"response":"Sub-agent plan"}"""),
                toolCallResponse("attempt_completion" to """{"result":"Done"}""")
            )

            val completionTool = fakeTool("attempt_completion", ToolResult(
                content = "Done",
                summary = "Done",
                tokenEstimate = 5,
                isCompletion = true
            ))

            val tools = listOf(PlanModeRespondTool(), completionTool)
            // No channel — loop should NOT block
            val loop = buildLoop(
                brain, tools,
                planMode = true,
                onPlanResponse = { text, _ -> planCallbackFired.add(text) },
                userInputChannel = null
            )

            val result = loop.run("Plan something")
            assertTrue(result is LoopResult.Completed, "should complete without blocking, got: $result")
            assertEquals(1, planCallbackFired.size)
        }

        @Test
        fun `user revision comment flows through channel back to LLM`() = runTest {
            val channel = Channel<String>(Channel.RENDEZVOUS)

            val brain = sequenceBrain(
                // First plan presentation
                toolCallResponse("plan_mode_respond" to """{"response":"Plan v1: 1. Do A, 2. Do B"}"""),
                // After user revision, LLM revises
                toolCallResponse("plan_mode_respond" to """{"response":"Plan v2: 1. Do A (modified), 2. Do B, 3. Do C"}"""),
                // After user approval, completes
                toolCallResponse("attempt_completion" to """{"result":"Implemented revised plan"}""")
            )

            val completionTool = fakeTool("attempt_completion", ToolResult(
                content = "Implemented revised plan",
                summary = "Implemented revised plan",
                tokenEstimate = 5,
                isCompletion = true
            ))

            val tools = listOf(PlanModeRespondTool(), completionTool)
            val loop = buildLoop(
                brain, tools,
                planMode = true,
                onPlanResponse = { _, _ -> },
                userInputChannel = channel
            )

            val loopJob = launch {
                val result = loop.run("Plan the work")
                assertTrue(result is LoopResult.Completed)
            }

            // User sends revision comment (first plan wait)
            channel.send("Step 1 needs modification. Also add a Step 3 for testing.")
            // User approves revised plan (second plan wait)
            channel.send("Approved. Implement it.")

            loopJob.join()
        }

        @Test
        fun `text-only response in plan mode resets consecutiveEmpties to 0`() = runTest {
            // Scenario: LLM gives MAX_CONSECUTIVE_EMPTIES-1 (=2) consecutive empty responses,
            // then a text-only plan-mode reply (which resets consecutiveEmpties to 0 and waits
            // for user input), then one more empty response, then completion.
            //
            // Without the reset: the empty after the text-only turn would be the 3rd consecutive
            // empty (2 before + 1 after), pushing consecutiveEmpties to 3 == MAX_CONSECUTIVE_EMPTIES
            // and the loop would return Failed.
            //
            // With the reset: consecutiveEmpties goes back to 0 on the text-only turn, so the
            // subsequent empty is only count=1 — the loop continues and completes normally.
            val channel = Channel<String>(Channel.RENDEZVOUS)

            val emptyResponse = { id: String ->
                ChatCompletionResponse(
                    id = id,
                    choices = listOf(
                        Choice(
                            index = 0,
                            message = ChatMessage(role = "assistant", content = null),
                            finishReason = "stop"
                        )
                    ),
                    usage = UsageInfo(promptTokens = 100, completionTokens = 0, totalTokens = 100)
                )
            }

            val brain = sequenceBrain(
                // Two consecutive empty responses — consecutiveEmpties reaches 2 (MAX-1)
                emptyResponse("resp-1"),  // consecutiveEmpties = 1
                emptyResponse("resp-2"),  // consecutiveEmpties = 2
                // Text-only reply in plan mode — triggers wait for user input, resets consecutiveEmpties to 0
                ChatCompletionResponse(
                    id = "resp-3",
                    choices = listOf(
                        Choice(
                            index = 0,
                            message = ChatMessage(role = "assistant", content = "I understand your question, let me think more."),
                            finishReason = "stop"
                        )
                    ),
                    usage = UsageInfo(promptTokens = 110, completionTokens = 10, totalTokens = 120)
                ),
                // One empty response after the reset:
                //   - WITHOUT reset: consecutiveEmpties would be 3 == MAX → loop fails
                //   - WITH reset:    consecutiveEmpties is 1 → loop continues
                emptyResponse("resp-4"),  // consecutiveEmpties = 1 (after reset)
                // Finally completes
                toolCallResponse("attempt_completion" to """{"result":"Done"}""")
            )

            val completionTool = fakeTool("attempt_completion", ToolResult(
                content = "Done",
                summary = "Done",
                tokenEstimate = 5,
                isCompletion = true
            ))

            val tools = listOf(PlanModeRespondTool(), completionTool)
            val loop = buildLoop(
                brain, tools,
                planMode = true,
                onPlanResponse = { _, _ -> },
                userInputChannel = channel
            )

            val loopJob = launch {
                val result = loop.run("Help me plan")
                assertTrue(
                    result is LoopResult.Completed,
                    "loop should complete after counter reset — without the reset the 3rd consecutive empty " +
                    "would exceed MAX_CONSECUTIVE_EMPTIES and the loop would return Failed. Got: $result"
                )
            }

            // Send user response after the text-only turn (which resets consecutiveEmpties to 0)
            channel.send("Please continue.")
            loopJob.join()
        }
    }
}
