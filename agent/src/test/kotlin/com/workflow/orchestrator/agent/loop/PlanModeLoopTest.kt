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
import com.workflow.orchestrator.core.model.ModelPricingRegistry
import io.mockk.mockk
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
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

    @AfterEach
    fun stopModelPricingWatcher() {
        // AgentLoop touches ModelPricingRegistry which starts a FileSystemWatcher;
        // shut it down so macOS ThreadLeakTracker doesn't trip on the watcher
        // thread after the test completes.
        runCatching { ModelPricingRegistry.resetForTests() }
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
        override val isMutating = toolName in AgentLoop.WRITE_TOOLS
        override suspend fun execute(params: JsonObject, project: Project) = result
    }

    private fun buildLoop(
        brain: LlmBrain,
        tools: List<AgentTool>,
        planMode: Boolean = false,
        onPlanResponse: ((String, Boolean, Boolean) -> Unit)? = null,
        userInputChannel: Channel<String>? = null,
        pendingChannelImageRefs: (() -> List<com.workflow.orchestrator.agent.session.ContentBlock.ImageRef>)? = null
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
            userInputChannel = userInputChannel,
            pendingChannelImageRefs = pendingChannelImageRefs
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
                onPlanResponse = { text, explore, _ -> planCallbackFired.add(text to explore) },
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
        fun `image attached to a plan-mode reply reaches the LLM as image parts`() = runTest {
            val channel = Channel<String>(Channel.RENDEZVOUS)

            val brain = sequenceBrain(
                // LLM presents a plan — loop waits for the user's reply
                toolCallResponse("plan_mode_respond" to """{"response":"Here is my plan"}"""),
                // After the user replies (with an image), LLM completes
                toolCallResponse("attempt_completion" to """{"result":"Done"}""")
            )

            val completionTool = fakeTool("attempt_completion", ToolResult(
                content = "Done", summary = "Done", tokenEstimate = 5, isCompletion = true
            ))
            val tools = listOf(PlanModeRespondTool(), completionTool)

            val loop = buildLoop(
                brain, tools,
                planMode = true,
                userInputChannel = channel,
                // The user attached an image to their plan-mode reply.
                pendingChannelImageRefs = {
                    listOf(
                        com.workflow.orchestrator.agent.session.ContentBlock.ImageRef(
                            sha256 = "abc123def456",
                            mime = "image/png",
                            size = 4096L,
                            originalFilename = "diagram.png",
                        )
                    )
                }
            )

            val loopJob = launch { loop.run("Plan the refactor") }
            channel.send("Does this image match what you meant?")
            loopJob.join()

            val carriesImage = contextManager.getMessages().any { it.hasImageParts() }
            assertTrue(
                carriesImage,
                "a plan-mode reply with an attached image must reach the LLM as image parts",
            )
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
                onPlanResponse = { text, explore, _ -> planCallbackFired.add(text to explore) },
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
                onPlanResponse = { _, _, _ -> },
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
                onPlanResponse = { text, _, _ -> planCallbackFired.add(text) },
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
                onPlanResponse = { _, _, _ -> },
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
        fun `plan mode blocks project_structure write actions but allows read actions`() = runTest {
            val channel = Channel<String>(Channel.RENDEZVOUS)
            val executeInvocations = mutableListOf<String>()

            // Fake project_structure meta-tool: mirrors the real isWriteAction override.
            val projectStructureTool: AgentTool = object : AgentTool {
                override val name = "project_structure"
                override val description = "Test project_structure"
                override val parameters = FunctionParameters(properties = emptyMap())
                override val allowedWorkers = setOf(WorkerType.CODER)

                override fun isWriteAction(action: String?): Boolean = action in setOf(
                    "add_source_root", "set_module_dependency", "remove_module_dependency",
                    "set_module_sdk", "set_language_level", "add_content_root",
                    "remove_content_root", "refresh_external_project"
                )

                override suspend fun execute(params: JsonObject, project: Project): ToolResult {
                    val action = params["action"]?.jsonPrimitive?.content ?: "unknown"
                    executeInvocations.add(action)
                    return ToolResult(content = "ok: $action", summary = "ok", tokenEstimate = 5)
                }
            }

            val brain = sequenceBrain(
                // LLM tries a write action (set_module_dependency) — must be blocked
                toolCallResponse("project_structure" to """{"action":"set_module_dependency","module":"app","dependsOn":"core"}"""),
                // LLM uses a read action (module_detail) — must be allowed
                toolCallResponse("project_structure" to """{"action":"module_detail","module":"app"}"""),
                // LLM presents plan — waits for user input
                toolCallResponse("plan_mode_respond" to """{"response":"Project analysis done"}"""),
                // After user approves, completes
                toolCallResponse("attempt_completion" to """{"result":"Done"}""")
            )

            val completionTool = fakeTool("attempt_completion", ToolResult(
                content = "Done", summary = "Done", tokenEstimate = 5, isCompletion = true
            ))

            val tools = listOf(projectStructureTool, PlanModeRespondTool(), completionTool)
            val loop = buildLoop(
                brain, tools,
                planMode = true,
                onPlanResponse = { _, _, _ -> },
                userInputChannel = channel
            )

            val loopJob = launch {
                val result = loop.run("Analyse project structure")
                assertTrue(result is LoopResult.Completed, "loop should complete, got: $result")
            }

            channel.send("Approved.")
            loopJob.join()

            // set_module_dependency is a write action — execute() must NOT have been called for it
            assertFalse(
                "set_module_dependency" in executeInvocations,
                "set_module_dependency must be blocked in plan mode; invocations: $executeInvocations"
            )
            // module_detail is a read action — execute() MUST have been called for it
            assertTrue(
                "module_detail" in executeInvocations,
                "module_detail must be allowed in plan mode; invocations: $executeInvocations"
            )
        }

        @Test
        fun `plan mode blocks runtime_config create_run_config but allows get_run_configurations`() = runTest {
            val channel = Channel<String>(Channel.RENDEZVOUS)
            val executeInvocations = mutableListOf<String>()

            // Fake runtime_config meta-tool: mirrors the real isWriteAction override.
            val runtimeConfigTool: AgentTool = object : AgentTool {
                override val name = "runtime_config"
                override val description = "Test runtime_config"
                override val parameters = FunctionParameters(properties = emptyMap())
                override val allowedWorkers = setOf(WorkerType.CODER)

                override fun isWriteAction(action: String?): Boolean = action in setOf(
                    "create_run_config", "modify_run_config", "delete_run_config"
                )

                override suspend fun execute(params: JsonObject, project: Project): ToolResult {
                    val action = params["action"]?.jsonPrimitive?.content ?: "unknown"
                    executeInvocations.add(action)
                    return ToolResult(content = "ok: $action", summary = "ok", tokenEstimate = 5)
                }
            }

            val brain = sequenceBrain(
                // LLM tries create_run_config (write action) — must be blocked
                toolCallResponse("runtime_config" to """{"action":"create_run_config","name":"MyApp","type":"application"}"""),
                // LLM uses get_run_configurations (read action) — must be allowed
                toolCallResponse("runtime_config" to """{"action":"get_run_configurations"}"""),
                // LLM presents plan — waits for user input
                toolCallResponse("plan_mode_respond" to """{"response":"Run config analysis done"}"""),
                // After user approves, completes
                toolCallResponse("attempt_completion" to """{"result":"Done"}""")
            )

            val completionTool = fakeTool("attempt_completion", ToolResult(
                content = "Done", summary = "Done", tokenEstimate = 5, isCompletion = true
            ))

            val tools = listOf(runtimeConfigTool, PlanModeRespondTool(), completionTool)
            val loop = buildLoop(
                brain, tools,
                planMode = true,
                onPlanResponse = { _, _, _ -> },
                userInputChannel = channel
            )

            val loopJob = launch {
                val result = loop.run("Analyse run configurations")
                assertTrue(result is LoopResult.Completed, "loop should complete, got: $result")
            }

            channel.send("Approved.")
            loopJob.join()

            // create_run_config is a write action — execute() must NOT have been called for it
            assertFalse(
                "create_run_config" in executeInvocations,
                "create_run_config must be blocked in plan mode; invocations: $executeInvocations"
            )
            // get_run_configurations is a read action — execute() MUST have been called for it
            assertTrue(
                "get_run_configurations" in executeInvocations,
                "get_run_configurations must be allowed in plan mode; invocations: $executeInvocations"
            )
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
                onPlanResponse = { _, _, _ -> },
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

        @Test
        fun `two plan_mode_respond calls with append=true — callback receives append flag correctly`() = runTest {
            val channel = Channel<String>(Channel.RENDEZVOUS)
            // Records (text, needsMoreExploration, append) for each callback invocation
            val planCallbacks = mutableListOf<Triple<String, Boolean, Boolean>>()

            val brain = sequenceBrain(
                // First call: full plan, needsMoreExploration=true so loop continues without waiting
                toolCallResponse(
                    "plan_mode_respond" to
                        """{"response":"## Phase 1\nStep 1","needs_more_exploration":true}"""
                ),
                // Second call: continuation with append=true; loop suspends for user input
                toolCallResponse(
                    "plan_mode_respond" to
                        """{"response":"\n## Phase 2\nStep 2","append":true}"""
                ),
                // After user approves, LLM completes
                toolCallResponse(
                    "attempt_completion" to """{"result":"Done"}"""
                )
            )

            val completionTool = fakeTool(
                "attempt_completion",
                ToolResult(content = "Done", summary = "Done", tokenEstimate = 5, isCompletion = true)
            )
            val tools = listOf(PlanModeRespondTool(), completionTool)

            val loop = buildLoop(
                brain, tools,
                planMode = true,
                onPlanResponse = { text, explore, append ->
                    planCallbacks.add(Triple(text, explore, append))
                },
                userInputChannel = channel
            )

            val loopJob = launch {
                loop.run("Plan a two-phase implementation")
            }

            channel.send("Approved")
            loopJob.join()

            assertEquals(2, planCallbacks.size, "callback must fire once per plan_mode_respond call")

            val (text1, explore1, append1) = planCallbacks[0]
            assertTrue(text1.contains("Phase 1"), "first callback carries Phase 1 content")
            assertTrue(explore1, "first call has needs_more_exploration=true")
            assertFalse(append1, "first call must not have append=true")

            val (text2, _, append2) = planCallbacks[1]
            assertTrue(text2.contains("Phase 2"), "second callback carries Phase 2 content")
            assertTrue(append2, "second call must have append=true")
        }
    }
}
