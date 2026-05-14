package com.workflow.orchestrator.agent.loop

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AgentLoopOutputTruncationTest {

    private lateinit var project: Project
    private lateinit var contextManager: ContextManager

    @BeforeEach
    fun setUp() {
        project = mockk(relaxed = true)
        contextManager = ContextManager(maxInputTokens = 100_000)
    }

    @AfterEach
    fun tearDown() {
        runCatching { ModelPricingRegistry.resetForTests() }
    }

    private class SequenceBrain(
        private val responses: List<ApiResult<ChatCompletionResponse>>,
        toolNames: Set<String> = emptySet()
    ) : LlmBrain {
        override val modelId = "test-model"
        override var toolNameSet: Set<String> = toolNames
        private var idx = 0
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
            if (idx >= responses.size) return ApiResult.Error(ErrorType.SERVER_ERROR, "no more responses")
            return responses[idx++]
        }

        override fun estimateTokens(text: String) = text.length / 4
        override fun cancelActiveRequest() {}
    }

    private fun textResponse(content: String, finishReason: String) = ChatCompletionResponse(
        id = "r-${System.nanoTime()}",
        choices = listOf(Choice(0, ChatMessage("assistant", content), finishReason)),
        usage = UsageInfo(100, 50, 150)
    )

    private fun toolCallResponse(name: String, args: String) = ChatCompletionResponse(
        id = "r-${System.nanoTime()}",
        choices = listOf(
            Choice(
                0,
                ChatMessage(
                    "assistant", null,
                    toolCalls = listOf(ToolCall("call_1", "function", FunctionCall(name, args)))
                ),
                "tool_calls"
            )
        ),
        usage = UsageInfo(100, 20, 120)
    )

    private fun fakeTool(toolName: String, result: ToolResult = ToolResult("ok", "ok", 5)): AgentTool =
        object : AgentTool {
            override val name = toolName
            override val description = "test"
            override val parameters = FunctionParameters(properties = emptyMap())
            override val allowedWorkers = setOf(WorkerType.CODER)
            override suspend fun execute(params: JsonObject, project: Project) = result
        }

    private fun completionTool() = fakeTool(
        "attempt_completion",
        ToolResult("Done", "Done", 5, isCompletion = true)
    )

    private fun buildLoop(
        brain: LlmBrain,
        tools: List<AgentTool>,
        onPlanPartialContent: ((String) -> Unit)? = null,
    ) = AgentLoop(
        brain = brain,
        tools = tools.associateBy { it.name },
        toolDefinitions = tools.map { it.toToolDefinition() },
        contextManager = contextManager,
        project = project,
        maxIterations = 5,
        toolNameProvider = { tools.map { it.name }.toSet() },
        onPlanPartialContent = onPlanPartialContent,
    )

    @Test
    fun `finish_reason=length with plan_mode_respond XML emits append=true nudge`() = runTest {
        // The tool NEVER EXECUTED (XML truncated mid-emission). The loop extracts the emitted
        // <response> prefix via onPlanPartialContent so the caller can pre-populate the
        // accumulator. The nudge then correctly says append=true — the continuation is stitched
        // onto the saved prefix to produce a complete plan.
        val partialPlanXml = "<plan_mode_respond>\n<response>## Phase 1\nStep 1: do something\nStep 2: do"
        val truncated = textResponse(partialPlanXml, "length")
        val recovery = toolCallResponse("attempt_completion", """{"result":"done"}""")

        val loop = buildLoop(
            SequenceBrain(listOf(ApiResult.Success(truncated), ApiResult.Success(recovery))),
            listOf(completionTool())
        )
        loop.run("write a plan")

        val msgs = contextManager.getMessages()
        val assistantIdx = msgs.indexOfFirst { it.role == "assistant" && it.content == partialPlanXml }
        assertTrue(assistantIdx >= 0, "truncated assistant turn must be in context")
        val nudgeMsg = msgs.drop(assistantIdx + 1).firstOrNull { it.role == "user" }

        assertNotNull(nudgeMsg, "nudge message must follow the truncated assistant turn")
        val nudgeText = nudgeMsg!!.content!!
        assertTrue(nudgeText.contains("plan_mode_respond"), "nudge must name plan_mode_respond")
        assertTrue(nudgeText.contains("append=true"), "nudge must instruct append=true")
        assertFalse(
            nudgeText.contains("smaller tool call"),
            "plan_mode_respond nudge must not say 'smaller tool calls'"
        )
    }

    @Test
    fun `finish_reason=length with plan_mode_respond XML fires onPlanPartialContent with emitted prefix`() = runTest {
        // Verify that the extracted <response> content (the plan prefix) is forwarded to the
        // onPlanPartialContent callback so the accumulator can be pre-populated before the
        // append=true continuation arrives.
        val planPrefix = "## Phase 1\nStep 1: do something\nStep 2: do"
        val partialPlanXml = "<plan_mode_respond>\n<response>$planPrefix"
        val truncated = textResponse(partialPlanXml, "length")
        val recovery = toolCallResponse("attempt_completion", """{"result":"done"}""")

        val capturedPartial = mutableListOf<String>()
        val loop = buildLoop(
            SequenceBrain(listOf(ApiResult.Success(truncated), ApiResult.Success(recovery))),
            listOf(completionTool()),
            onPlanPartialContent = { capturedPartial.add(it) }
        )
        loop.run("write a plan")

        assertEquals(1, capturedPartial.size, "onPlanPartialContent must fire exactly once")
        assertEquals(planPrefix, capturedPartial[0], "extracted prefix must match emitted <response> content")
    }

    @Test
    fun `finish_reason=length with partial read_file XML emits smaller-operation nudge`() = runTest {
        val partialToolXml = "Let me read the file. <read_file>\n<path>src/main/kotlin/Foo"
        val truncated = textResponse(partialToolXml, "length")
        val recovery = toolCallResponse("attempt_completion", """{"result":"done"}""")

        val readFileTool = fakeTool("read_file")
        val loop = buildLoop(
            SequenceBrain(listOf(ApiResult.Success(truncated), ApiResult.Success(recovery))),
            listOf(readFileTool, completionTool())
        )
        loop.run("read a file")

        val msgs = contextManager.getMessages()
        val assistantIdx = msgs.indexOfFirst { it.role == "assistant" && it.content == partialToolXml }
        assertTrue(assistantIdx >= 0)
        val nudgeMsg = msgs.drop(assistantIdx + 1).firstOrNull { it.role == "user" }

        assertNotNull(nudgeMsg)
        val nudgeText = nudgeMsg!!.content!!
        assertTrue(nudgeText.contains("smaller"), "generic tool nudge must mention 'smaller'")
        assertFalse(nudgeText.contains("append=true"), "generic tool nudge must not mention append")
        assertFalse(nudgeText.contains("Resume EXACTLY"), "must not use pure-text continuation phrasing")
    }

    @Test
    fun `finish_reason=length with pure text emits resume-exactly nudge`() = runTest {
        val partialProse = "Here is my implementation plan:\n\n## Phase 1\n1. Add SessionId sealed class\n2. Extend Work"
        val truncated = textResponse(partialProse, "length")
        val recovery = toolCallResponse("attempt_completion", """{"result":"done"}""")

        val loop = buildLoop(
            SequenceBrain(listOf(ApiResult.Success(truncated), ApiResult.Success(recovery))),
            listOf(completionTool())
        )
        loop.run("plan the work")

        val msgs = contextManager.getMessages()
        val assistantIdx = msgs.indexOfFirst { it.role == "assistant" && it.content == partialProse }
        assertTrue(assistantIdx >= 0)
        val nudgeMsg = msgs.drop(assistantIdx + 1).firstOrNull { it.role == "user" }

        assertNotNull(nudgeMsg)
        val nudgeText = nudgeMsg!!.content!!
        assertTrue(nudgeText.contains("Resume EXACTLY"), "pure-text nudge must say 'Resume EXACTLY'")
        assertFalse(nudgeText.contains("append=true"), "pure-text nudge must not mention append")
        assertFalse(nudgeText.contains("smaller tool call"), "pure-text nudge must not mention tool calls")
    }

    // ─── Multi-truncation scenarios ───────────────────────────────────────────────

    @Test
    fun `two consecutive plan_mode_respond truncations accumulate both prefixes`() = runTest {
        // Call 1: <plan_mode_respond><response>Part A  ← truncated, tool never ran
        // Call 2: <plan_mode_respond><response>Part B  ← truncated again, tool never ran
        // Call 3: successful append=true continuation
        // Expected: accumulator has "Part A\nPart B" before the final call
        val partA = "## Task 1\nStep 1: write test"
        val partB = "## Task 2\nStep 1: write test"
        val truncated1 = textResponse("<plan_mode_respond>\n<response>$partA", "length")
        val truncated2 = textResponse("<plan_mode_respond>\n<response>$partB", "length")
        val recovery = toolCallResponse("attempt_completion", """{"result":"done"}""")

        val capturedPartials = mutableListOf<String>()
        val loop = buildLoop(
            SequenceBrain(listOf(
                ApiResult.Success(truncated1),
                ApiResult.Success(truncated2),
                ApiResult.Success(recovery)
            )),
            listOf(completionTool()),
            onPlanPartialContent = { capturedPartials.add(it) }
        )
        loop.run("write a plan")

        // Both truncations must have fired the callback
        assertEquals(2, capturedPartials.size, "callback must fire once per truncation")
        assertEquals(partA, capturedPartials[0])
        assertEquals(partB, capturedPartials[1])

        // Both nudge messages in context must say append=true
        val nudges = contextManager.getMessages().filter { it.role == "user" && it.content?.contains("append=true") == true }
        assertEquals(2, nudges.size, "each truncation must produce an append=true nudge")
    }

    @Test
    fun `plan_mode_respond truncated then LLM responds with prose instead — prose gets resume nudge`() = runTest {
        // Call 1: plan_mode_respond truncated mid-XML → append=true nudge issued
        // Call 2: LLM ignores the nudge and responds with pure prose (no tool call)
        //         → prose truncation → resume-exactly nudge issued
        // Call 3: LLM finally calls attempt_completion to end
        val planXmlTruncated = "<plan_mode_respond>\n<response>## Task 1\nHere are the steps"
        val proseTruncated = "Sorry, let me continue. The plan includes the following tasks in order"
        val recovery = toolCallResponse("attempt_completion", """{"result":"done"}""")

        val capturedPartials = mutableListOf<String>()
        val loop = buildLoop(
            SequenceBrain(listOf(
                ApiResult.Success(textResponse(planXmlTruncated, "length")),
                ApiResult.Success(textResponse(proseTruncated, "length")),
                ApiResult.Success(recovery)
            )),
            listOf(completionTool()),
            onPlanPartialContent = { capturedPartials.add(it) }
        )
        loop.run("write a plan")

        // Partial content captured from the first truncation
        assertEquals(1, capturedPartials.size, "only the plan_mode_respond truncation fires the callback")

        val msgs = contextManager.getMessages().filter { it.role == "user" }
        val appendNudge = msgs.firstOrNull { it.content?.contains("append=true") == true }
        val resumeNudge = msgs.firstOrNull { it.content?.contains("Resume EXACTLY") == true }
        assertNotNull(appendNudge, "first nudge must be append=true (plan XML truncated)")
        assertNotNull(resumeNudge, "second nudge must be Resume EXACTLY (pure prose truncated)")
    }

    @Test
    fun `plan_mode_respond truncated before response tag — no partial content callback fired`() = runTest {
        // Truncation happened after <plan_mode_respond> but before <response> was emitted.
        // There is no plan content to extract, so the callback must NOT be called.
        // The nudge still says append=true — when the LLM calls with the full plan,
        // accumulatedPlanText is empty and the fullPlan = planText branch handles it correctly.
        val onlyOpeningTag = "<plan_mode_respond>"
        val recovery = toolCallResponse("attempt_completion", """{"result":"done"}""")

        val capturedPartials = mutableListOf<String>()
        val loop = buildLoop(
            SequenceBrain(listOf(ApiResult.Success(textResponse(onlyOpeningTag, "length")), ApiResult.Success(recovery))),
            listOf(completionTool()),
            onPlanPartialContent = { capturedPartials.add(it) }
        )
        loop.run("write a plan")

        assertTrue(capturedPartials.isEmpty(), "no content to extract — callback must not fire")
        val nudge = contextManager.getMessages().firstOrNull { it.role == "user" && it.content?.contains("plan_mode_respond") == true }
        assertNotNull(nudge, "nudge must still be emitted even without partial content")
        assertTrue(nudge!!.content!!.contains("append=true"), "nudge must still say append=true")
    }

    @Test
    fun `non-plan tool truncation does NOT fire onPlanPartialContent`() = runTest {
        // A read_file truncation must not contaminate the plan accumulator.
        val partialReadFile = "Let me look at this. <read_file>\n<path>src/Foo"
        val recovery = toolCallResponse("attempt_completion", """{"result":"done"}""")

        val capturedPartials = mutableListOf<String>()
        val readFileTool = fakeTool("read_file")
        val loop = buildLoop(
            SequenceBrain(listOf(ApiResult.Success(textResponse(partialReadFile, "length")), ApiResult.Success(recovery))),
            listOf(readFileTool, completionTool()),
            onPlanPartialContent = { capturedPartials.add(it) }
        )
        loop.run("read something")

        assertTrue(capturedPartials.isEmpty(), "read_file truncation must not fire onPlanPartialContent")
    }
}
