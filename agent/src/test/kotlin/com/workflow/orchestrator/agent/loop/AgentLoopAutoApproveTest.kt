package com.workflow.orchestrator.agent.loop

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.observability.AgentFileLogger
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.core.ai.LlmBrain
import com.workflow.orchestrator.core.ai.dto.ChatCompletionResponse
import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.ai.dto.Choice
import com.workflow.orchestrator.core.ai.dto.FunctionCall
import com.workflow.orchestrator.core.ai.dto.StreamChunk
import com.workflow.orchestrator.core.ai.dto.ToolCall
import com.workflow.orchestrator.core.ai.dto.ToolDefinition
import com.workflow.orchestrator.core.ai.dto.UsageInfo
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.core.model.ModelPricingRegistry
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Behavioural contract for the run_command auto-approve SKIP in [AgentLoop]
 * (Part A toggle + Part B session prefix allowlist). Mirrors the harness in
 * [AgentLoopMemoryApprovalTest]: mock Project, sequence brain, approvalGate recorder,
 * onToolCall capture.
 */
class AgentLoopAutoApproveTest {

    @AfterEach
    fun stopModelPricingWatcher() { runCatching { ModelPricingRegistry.resetForTests() } }

    private fun toolCallResponse(toolName: String, args: String): ChatCompletionResponse =
        ChatCompletionResponse(
            id = "resp-${System.nanoTime()}",
            choices = listOf(
                Choice(
                    index = 0,
                    message = ChatMessage(
                        role = "assistant",
                        content = null,
                        toolCalls = listOf(
                            ToolCall(
                                id = "call_${System.nanoTime()}",
                                type = "function",
                                function = FunctionCall(name = toolName, arguments = args),
                            )
                        )
                    ),
                    finishReason = "tool_calls"
                )
            ),
            usage = UsageInfo(promptTokens = 100, completionTokens = 10, totalTokens = 110)
        )

    private class SequenceBrain(private val responses: List<ApiResult<ChatCompletionResponse>>) : LlmBrain {
        override val modelId = "test-model"
        val callIndex = AtomicInteger(0)
        override suspend fun chat(
            messages: List<ChatMessage>,
            tools: List<ToolDefinition>?,
            maxTokens: Int?,
            toolChoice: JsonElement?,
        ) = throw UnsupportedOperationException()
        override suspend fun chatStream(
            messages: List<ChatMessage>,
            tools: List<ToolDefinition>?,
            maxTokens: Int?,
            onChunk: suspend (StreamChunk) -> Unit,
        ): ApiResult<ChatCompletionResponse> {
            val i = callIndex.getAndIncrement()
            return if (i >= responses.size) ApiResult.Error(ErrorType.SERVER_ERROR, "no more") else responses[i]
        }
        override fun estimateTokens(text: String) = text.length / 4
        override fun cancelActiveRequest() = Unit
    }

    private fun tool(toolName: String, completion: Boolean = false): AgentTool = object : AgentTool {
        override val name = toolName
        override val description = "test $toolName"
        override val parameters = FunctionParameters(properties = emptyMap())
        override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.ORCHESTRATOR)
        override suspend fun execute(params: JsonObject, project: Project): ToolResult =
            ToolResult(content = "ok", summary = "ok", tokenEstimate = 5, isCompletion = completion)
    }

    /** Records every approval-gate invocation. */
    private class GateRecorder {
        val calls = mutableListOf<Pair<String, Boolean>>() // (toolName, allowSessionApproval)

        /** riskLevel string passed as the third arg to the gate — non-null only for prompted calls. */
        val riskLevels = mutableListOf<String>()
        val gate: suspend (String, String, String, Boolean) -> ApprovalResult = { name, _, riskLevel, allowSession ->
            calls.add(name to allowSession)
            riskLevels.add(riskLevel)
            ApprovalResult.APPROVED
        }
    }

    private fun buildLoop(
        brain: LlmBrain,
        tools: List<AgentTool>,
        recorder: GateRecorder,
        progress: MutableList<ToolCallProgress>,
        autoApproveSafeCommands: Boolean,
        sessionCommandAllowlist: SessionCommandAllowlist = SessionCommandAllowlist(),
        fileLogger: AgentFileLogger? = null,
    ): AgentLoop = AgentLoop(
        brain = brain,
        tools = tools.associateBy { it.name },
        toolDefinitions = tools.map { it.toToolDefinition() },
        contextManager = ContextManager(maxInputTokens = 100_000),
        project = mockk<Project>(relaxed = true),
        maxIterations = 10,
        approvalGate = recorder.gate,
        onToolCall = { p -> if (p.result.isEmpty() && p.durationMs == 0L) progress.add(p) },
        fileLogger = fileLogger,
        autoApproveSafeCommands = autoApproveSafeCommands,
        sessionCommandAllowlist = sessionCommandAllowlist,
    )

    private fun commandThenComplete(command: String) = SequenceBrain(
        listOf(
            ApiResult.Success(toolCallResponse("run_command", """{"command":"$command"}""")),
            ApiResult.Success(toolCallResponse("attempt_completion", """{"kind":"done","result":"done"}""")),
        )
    )

    private fun tools() = listOf(tool("run_command"), tool("attempt_completion", completion = true))

    /** Capture the START (RUNNING) ToolCallProgress for the given tool. */
    private fun startCardFor(progress: List<ToolCallProgress>, toolName: String): ToolCallProgress? =
        progress.firstOrNull { it.toolName == toolName }

    @Test
    fun `toggle ON plus safe run_command auto-approves without prompting`() = runTest {
        val recorder = GateRecorder()
        val progress = mutableListOf<ToolCallProgress>()
        val loop = buildLoop(
            brain = commandThenComplete("ls -la"),
            tools = tools(),
            recorder = recorder,
            progress = progress,
            autoApproveSafeCommands = true,
        )
        loop.run("go")

        assertEquals(0, recorder.calls.size) {
            "Safe command with toggle ON must NOT hit the approval gate; got ${recorder.calls}"
        }
        val card = startCardFor(progress, "run_command")
        assertNotNull(card) { "Expected a RUNNING ToolCallProgress for run_command" }
        assertTrue(card!!.autoApproved) { "autoApproved must be true on the auto-approved path" }
        assertEquals("safe", card.autoApproveReason)
    }

    @Test
    fun `toggle ON plus risky run_command still prompts`() = runTest {
        val recorder = GateRecorder()
        val progress = mutableListOf<ToolCallProgress>()
        val loop = buildLoop(
            brain = commandThenComplete("git push"),
            tools = tools(),
            recorder = recorder,
            progress = progress,
            autoApproveSafeCommands = true,
        )
        loop.run("go")

        assertEquals(1, recorder.calls.size) {
            "Risky command must hit the approval gate exactly once; got ${recorder.calls}"
        }
        assertEquals("run_command", recorder.calls[0].first)
        val card = startCardFor(progress, "run_command")
        assertNotNull(card)
        assertEquals(false, card!!.autoApproved) { "Prompted command is not auto-approved" }
        assertNull(card.autoApproveReason)
    }

    @Test
    fun `toggle OFF plus session-allowlisted prefix auto-approves`() = runTest {
        val recorder = GateRecorder()
        val progress = mutableListOf<ToolCallProgress>()
        val allowlist = SessionCommandAllowlist().apply { approve("git pull") }
        val loop = buildLoop(
            brain = commandThenComplete("git pull origin"),
            tools = tools(),
            recorder = recorder,
            progress = progress,
            autoApproveSafeCommands = false,
            sessionCommandAllowlist = allowlist,
        )
        loop.run("go")

        assertEquals(0, recorder.calls.size) {
            "Session-allowlisted prefix must NOT hit the gate even with toggle OFF; got ${recorder.calls}"
        }
        val card = startCardFor(progress, "run_command")
        assertNotNull(card)
        assertTrue(card!!.autoApproved)
        assertEquals("session rule: git pull", card.autoApproveReason)
    }

    @Test
    fun `toggle ON plus safe-but-redirected run_command prompts (structural guard)`() = runTest {
        val recorder = GateRecorder()
        val progress = mutableListOf<ToolCallProgress>()
        // git status is SAFE, but the redirect makes it structurally non-auto-approvable.
        val loop = buildLoop(
            brain = commandThenComplete("git status > out"),
            tools = tools(),
            recorder = recorder,
            progress = progress,
            autoApproveSafeCommands = true,
        )
        loop.run("go")

        assertEquals(1, recorder.calls.size) {
            "A redirected command must hit the gate despite being SAFE (structural guard); got ${recorder.calls}"
        }
        assertEquals("run_command", recorder.calls[0].first)
        val card = startCardFor(progress, "run_command")
        assertNotNull(card)
        assertEquals(false, card!!.autoApproved)
        assertNull(card.autoApproveReason)
    }

    @Test
    fun `auto-approved path still logs the tool call`() = runTest {
        val recorder = GateRecorder()
        val progress = mutableListOf<ToolCallProgress>()
        val logger = mockk<AgentFileLogger>(relaxed = true)
        val loop = buildLoop(
            brain = commandThenComplete("ls -la"),
            tools = tools(),
            recorder = recorder,
            progress = progress,
            autoApproveSafeCommands = true,
            fileLogger = logger,
        )
        loop.run("go")

        assertEquals(0, recorder.calls.size) { "Sanity: this is the auto-approved path" }
        // Logging must NOT be bypassed by the skip — the post-execution success log fires.
        verify(atLeast = 1) {
            logger.logToolCall(
                sessionId = any(),
                toolName = "run_command",
                durationMs = any(),
                isError = any(),
                args = any(),
                errorMessage = any(),
                tokenEstimate = any(),
            )
        }
    }

    @Test
    fun `toggle OFF plus safe run_command plus empty allowlist invokes gate with autoApproved false`() = runTest {
        val recorder = GateRecorder()
        val progress = mutableListOf<ToolCallProgress>()
        val loop = buildLoop(
            brain = commandThenComplete("ls -la"),
            tools = tools(),
            recorder = recorder,
            progress = progress,
            autoApproveSafeCommands = false,
            sessionCommandAllowlist = SessionCommandAllowlist(),
        )
        loop.run("go")

        assertEquals(1, recorder.calls.size) {
            "Toggle OFF with empty allowlist must hit the approval gate exactly once; got ${recorder.calls}"
        }
        assertEquals("run_command", recorder.calls[0].first)
        val card = startCardFor(progress, "run_command")
        assertNotNull(card) { "Expected a RUNNING ToolCallProgress for run_command" }
        assertEquals(false, card!!.autoApproved) { "Gate-prompted command must NOT be auto-approved" }
        assertNull(card.autoApproveReason) { "autoApproveReason must be null on the gate path" }
    }

    // ── New behavioral coverage ────────────────────────────────────────────────

    @Test
    fun `Part B fires even with toggle ON for a risky command`() = runTest {
        // toggle ON + session-allowlisted "git pull" + RISKY command "git pull origin"
        // DANGEROUS check passes (not DANGEROUS), isAutoApprovable passes, Part A misses (RISKY ≠ SAFE),
        // coveringPrefixes finds "git pull" covers "git pull origin" → Skip via SessionRule.
        val recorder = GateRecorder()
        val progress = mutableListOf<ToolCallProgress>()
        val allowlist = SessionCommandAllowlist().apply { approve("git pull") }
        val loop = buildLoop(
            brain = commandThenComplete("git pull origin"),
            tools = tools(),
            recorder = recorder,
            progress = progress,
            autoApproveSafeCommands = true,
            sessionCommandAllowlist = allowlist,
        )
        loop.run("go")

        assertEquals(0, recorder.calls.size) {
            "Session-allowlisted risky command with toggle ON must NOT hit the gate; got ${recorder.calls}"
        }
        val card = startCardFor(progress, "run_command")
        assertNotNull(card) { "Expected a RUNNING ToolCallProgress for run_command" }
        assertTrue(card!!.autoApproved) { "autoApproved must be true on the session-rule path" }
        assertEquals("session rule: git pull", card.autoApproveReason)
    }

    @Test
    fun `same-instance SessionCommandAllowlist flows through to the loop`() = runTest {
        // Construct ONE SessionCommandAllowlist, call approve() on it, pass that same
        // instance into buildLoop — proves the loop reads the instance it is handed.
        val recorder = GateRecorder()
        val progress = mutableListOf<ToolCallProgress>()
        val allowlist = SessionCommandAllowlist()
        allowlist.approve("git add")
        val loop = buildLoop(
            brain = commandThenComplete("git add Foo.kt"),
            tools = tools(),
            recorder = recorder,
            progress = progress,
            autoApproveSafeCommands = false,
            sessionCommandAllowlist = allowlist,
        )
        loop.run("go")

        assertEquals(0, recorder.calls.size) {
            "Instance-approved prefix must skip the gate; got ${recorder.calls}"
        }
        val card = startCardFor(progress, "run_command")
        assertNotNull(card)
        assertTrue(card!!.autoApproved)
        assertEquals("session rule: git add", card.autoApproveReason)
    }

    @Test
    fun `DANGEROUS command with session-covered prefix still prompts`() = runTest {
        // Even if a prefix that matches is in the allowlist, DANGEROUS commands
        // are rejected at the very first check in CommandApprovalDecision.evaluate.
        val recorder = GateRecorder()
        val progress = mutableListOf<ToolCallProgress>()
        val allowlist = SessionCommandAllowlist().apply { approve("rm") }
        val loop = buildLoop(
            brain = commandThenComplete("rm -rf /tmp/x"),
            tools = tools(),
            recorder = recorder,
            progress = progress,
            autoApproveSafeCommands = true,
            sessionCommandAllowlist = allowlist,
        )
        loop.run("go")

        assertEquals(1, recorder.calls.size) {
            "DANGEROUS command must always hit the gate (short-circuit before prefix check); got ${recorder.calls}"
        }
        assertEquals("run_command", recorder.calls[0].first)
        val card = startCardFor(progress, "run_command")
        assertNotNull(card)
        assertEquals(false, card!!.autoApproved)
        assertNull(card.autoApproveReason)
    }

    @Test
    fun `compound command with both sub-commands covered auto-approves`() = runTest {
        val recorder = GateRecorder()
        val progress = mutableListOf<ToolCallProgress>()
        val allowlist = SessionCommandAllowlist().apply {
            approve("git add")
            approve("git status")
        }
        val loop = buildLoop(
            brain = commandThenComplete("git add . && git status"),
            tools = tools(),
            recorder = recorder,
            progress = progress,
            autoApproveSafeCommands = false,
            sessionCommandAllowlist = allowlist,
        )
        loop.run("go")

        assertEquals(0, recorder.calls.size) {
            "Compound command with every sub covered must skip the gate; got ${recorder.calls}"
        }
        val card = startCardFor(progress, "run_command")
        assertNotNull(card)
        assertTrue(card!!.autoApproved)
        assertNotNull(card.autoApproveReason) { "autoApproveReason must be set for session-rule skip" }
    }

    @Test
    fun `compound command with one uncovered sub-command prompts`() = runTest {
        // "git add ." is covered; "rm -rf x" is DANGEROUS → the whole command is
        // classified DANGEROUS and CommandApprovalDecision short-circuits to Prompt.
        val recorder = GateRecorder()
        val progress = mutableListOf<ToolCallProgress>()
        val allowlist = SessionCommandAllowlist().apply { approve("git add") }
        val loop = buildLoop(
            brain = commandThenComplete("git add . && rm -rf x"),
            tools = tools(),
            recorder = recorder,
            progress = progress,
            autoApproveSafeCommands = true,
            sessionCommandAllowlist = allowlist,
        )
        loop.run("go")

        assertEquals(1, recorder.calls.size) {
            "Compound with an uncovered/DANGEROUS sub-command must hit the gate; got ${recorder.calls}"
        }
        assertEquals("run_command", recorder.calls[0].first)
        val card = startCardFor(progress, "run_command")
        assertNotNull(card)
        assertEquals(false, card!!.autoApproved)
        assertNull(card.autoApproveReason)
    }

    @Test
    fun `redirect on a covered prefix still prompts (structural guard)`() = runTest {
        // "git status > out" has a redirect operator: CommandShape.isAutoApprovable returns false.
        // CommandApprovalDecision short-circuits to Prompt before checking the allowlist.
        val recorder = GateRecorder()
        val progress = mutableListOf<ToolCallProgress>()
        val allowlist = SessionCommandAllowlist().apply { approve("git status") }
        val loop = buildLoop(
            brain = commandThenComplete("git status > out"),
            tools = tools(),
            recorder = recorder,
            progress = progress,
            autoApproveSafeCommands = true,
            sessionCommandAllowlist = allowlist,
        )
        loop.run("go")

        assertEquals(1, recorder.calls.size) {
            "Redirect makes the command non-auto-approvable — must hit the gate; got ${recorder.calls}"
        }
        assertEquals("run_command", recorder.calls[0].first)
        val card = startCardFor(progress, "run_command")
        assertNotNull(card)
        assertEquals(false, card!!.autoApproved)
        assertNull(card.autoApproveReason)
    }

    @Test
    fun `riskLevel is passed correctly to the gate for RISKY and DANGEROUS commands`() = runTest {
        // RISKY: "git push" (toggle OFF, empty allowlist) → gate called with riskLevel "medium".
        val riskyRecorder = GateRecorder()
        val riskyProgress = mutableListOf<ToolCallProgress>()
        buildLoop(
            brain = commandThenComplete("git push"),
            tools = tools(),
            recorder = riskyRecorder,
            progress = riskyProgress,
            autoApproveSafeCommands = false,
        ).run("go")
        assertEquals(1, riskyRecorder.calls.size) { "Sanity: RISKY command must hit gate" }
        assertEquals("medium", riskyRecorder.riskLevels[0]) {
            "RISKY → riskLabel 'medium'; got ${riskyRecorder.riskLevels[0]}"
        }

        // DANGEROUS: "rm -rf /tmp/x" (toggle OFF, empty allowlist) → gate called with riskLevel "high".
        val dangerRecorder = GateRecorder()
        val dangerProgress = mutableListOf<ToolCallProgress>()
        buildLoop(
            brain = commandThenComplete("rm -rf /tmp/x"),
            tools = tools(),
            recorder = dangerRecorder,
            progress = dangerProgress,
            autoApproveSafeCommands = false,
        ).run("go")
        assertEquals(1, dangerRecorder.calls.size) { "Sanity: DANGEROUS command must hit gate" }
        assertEquals("high", dangerRecorder.riskLevels[0]) {
            "DANGEROUS → riskLabel 'high'; got ${dangerRecorder.riskLevels[0]}"
        }
    }
}
