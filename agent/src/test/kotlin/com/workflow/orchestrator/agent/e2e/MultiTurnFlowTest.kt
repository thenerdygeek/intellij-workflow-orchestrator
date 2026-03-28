package com.workflow.orchestrator.agent.e2e

import com.workflow.orchestrator.agent.api.dto.ChatMessage
import com.workflow.orchestrator.agent.api.dto.ChatCompletionResponse
import com.workflow.orchestrator.agent.api.dto.Choice
import com.workflow.orchestrator.agent.api.dto.FunctionCall
import com.workflow.orchestrator.agent.api.dto.StreamChunk
import com.workflow.orchestrator.agent.api.dto.ToolCall
import com.workflow.orchestrator.agent.api.dto.ToolDefinition
import com.workflow.orchestrator.agent.api.dto.UsageInfo
import com.workflow.orchestrator.agent.brain.LlmBrain
import com.workflow.orchestrator.agent.context.ContextManager
import com.workflow.orchestrator.agent.runtime.AgentMetrics
import com.workflow.orchestrator.agent.runtime.AgentPlan
import com.workflow.orchestrator.agent.runtime.BudgetEnforcer
import com.workflow.orchestrator.agent.runtime.ConversationStore
import com.workflow.orchestrator.agent.runtime.PlanManager
import com.workflow.orchestrator.agent.runtime.PlanPersistence
import com.workflow.orchestrator.agent.runtime.PlanStep
import com.workflow.orchestrator.agent.runtime.PersistedMessage
import com.workflow.orchestrator.agent.runtime.SessionMetadata
import com.workflow.orchestrator.agent.runtime.SessionStatus
import com.workflow.orchestrator.core.model.ApiResult
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Multi-turn integration tests using a mocked LLM brain.
 *
 * Tests verify that context, plans, compression, worker delegation,
 * budget escalation, and circuit breakers work correctly across
 * multiple conversation turns — without needing a real LLM API.
 */
class MultiTurnFlowTest {

    @TempDir
    lateinit var tempDir: File

    /**
     * Mock LLM brain that returns scripted responses in order.
     * Each call to [chat] returns the next response from [responses].
     */
    class ScriptedBrain(
        private val responses: MutableList<ChatCompletionResponse>
    ) : LlmBrain {
        override val modelId: String = "test-model"
        var callCount = 0
            private set
        val receivedMessages = mutableListOf<List<ChatMessage>>()

        override suspend fun chat(
            messages: List<ChatMessage>,
            tools: List<ToolDefinition>?,
            maxTokens: Int?,
            toolChoice: JsonElement?
        ): ApiResult<ChatCompletionResponse> {
            receivedMessages.add(messages.toList())
            callCount++
            return if (responses.isNotEmpty()) {
                ApiResult.Success(responses.removeFirst())
            } else {
                ApiResult.Success(textResponse("No more scripted responses."))
            }
        }

        override suspend fun chatStream(
            messages: List<ChatMessage>,
            tools: List<ToolDefinition>?,
            maxTokens: Int?,
            onChunk: suspend (StreamChunk) -> Unit
        ): ApiResult<ChatCompletionResponse> {
            throw NotImplementedError("Streaming not used in multi-turn tests")
        }

        override fun estimateTokens(text: String): Int = text.length / 4
    }

    // ===== Test 1: Plan approval flow preserves context across turns =====

    @Test
    fun `plan approval flow preserves context across turns`() = runTest {
        val planManager = PlanManager()
        planManager.sessionDir = tempDir

        val contextManager = ContextManager(maxInputTokens = 50_000)

        // Turn 1: User asks for a feature
        val userMessage = "Add a new endpoint to UserService"
        contextManager.addMessage(ChatMessage(role = "system", content = "You are a coding assistant."))
        contextManager.addMessage(ChatMessage(role = "user", content = userMessage))

        // Simulate agent creating a plan
        val plan = AgentPlan(
            goal = "Add GET /api/users/{id}/profile endpoint",
            approach = "Add controller method, service method, and tests",
            steps = listOf(
                PlanStep(id = "step-1", title = "Add UserProfileDto", description = "Create DTO class"),
                PlanStep(id = "step-2", title = "Add service method", description = "Implement getProfile()", dependsOn = listOf("step-1")),
                PlanStep(id = "step-3", title = "Add controller endpoint", description = "Wire the endpoint", dependsOn = listOf("step-2"))
            )
        )
        planManager.submitPlan(plan)

        assertTrue(planManager.hasPlan(), "Plan should be submitted")
        assertFalse(planManager.isPlanApproved(), "Plan should not be approved yet")

        // Simulate agent response mentioning the plan
        contextManager.addMessage(ChatMessage(
            role = "assistant",
            content = "I've created a plan with 3 steps to add the endpoint."
        ))

        // Approve the plan
        planManager.approvePlan()
        assertTrue(planManager.isPlanApproved(), "Plan should be approved after approvePlan()")

        // Turn 2: Follow-up message — context should still have the conversation
        contextManager.addMessage(ChatMessage(role = "user", content = "Now add tests for the endpoint"))

        val messages = contextManager.getMessages()
        assertTrue(messages.size >= 4, "Context should have system + user + assistant + user messages, got ${messages.size}")

        // Verify the original user message is still in context
        val userMessages = messages.filter { it.role == "user" }
        assertTrue(
            userMessages.any { it.content?.contains("Add a new endpoint") == true },
            "Original user message should be preserved in context"
        )
        assertTrue(
            userMessages.any { it.content?.contains("add tests") == true },
            "Follow-up user message should be in context"
        )

        // Verify plan step dependencies
        assertFalse(planManager.areDependenciesMet("step-2"), "step-2 should not be met (step-1 not done)")
        planManager.updateStepStatus("step-1", "done")
        assertTrue(planManager.areDependenciesMet("step-2"), "step-2 should be met after step-1 done")
    }

    // ===== Test 2: Compression preserves plan anchor =====

    @Test
    fun `compression preserves plan anchor`() = runTest {
        // Use a tiny budget to force compression quickly
        val contextManager = ContextManager(maxInputTokens = 2000)

        // Set a plan anchor
        val planContent = "Step 1: Add UserProfileDto\nStep 2: Add service method\nStep 3: Add controller endpoint"
        contextManager.setPlanAnchor(ChatMessage(
            role = "system",
            content = "<active_plan>\n$planContent\n</active_plan>"
        ))

        // Add system prompt
        contextManager.addMessage(ChatMessage(role = "system", content = "You are a coding assistant."))

        // Fill context with tool results to trigger compression
        for (i in 1..30) {
            contextManager.addMessage(ChatMessage(
                role = "assistant",
                content = null,
                toolCalls = listOf(ToolCall(id = "tc_$i", function = FunctionCall(name = "read_file", arguments = """{"path":"file_$i.kt"}""")))
            ))
            contextManager.addMessage(ChatMessage(
                role = "tool",
                content = "<external_data>" + "x".repeat(200) + "</external_data>",
                toolCallId = "tc_$i"
            ))
        }

        // The plan anchor should survive all compressions
        val allMessages = contextManager.getMessages()
        val planAnchorMessages = allMessages.filter {
            it.role == "system" && it.content?.contains("<active_plan>") == true
        }

        assertTrue(
            planAnchorMessages.isNotEmpty(),
            "Plan anchor should survive compression. Messages: ${allMessages.map { "${it.role}: ${it.content?.take(50)}" }}"
        )
        assertTrue(
            planAnchorMessages.any { it.content?.contains("Add UserProfileDto") == true },
            "Plan anchor content should be preserved"
        )
    }

    // ===== Test 3: Worker delegation returns result to main session context =====

    @Test
    fun `worker delegation result returns to main session context`() = runTest {
        val contextManager = ContextManager(maxInputTokens = 50_000)

        // Main session context
        contextManager.addMessage(ChatMessage(role = "system", content = "You are the main orchestrator."))
        contextManager.addMessage(ChatMessage(role = "user", content = "Analyze the authentication module"))

        // Simulate agent calling delegate_task
        contextManager.addMessage(ChatMessage(
            role = "assistant",
            content = null,
            toolCalls = listOf(ToolCall(
                id = "tc_delegate_1",
                function = FunctionCall(
                    name = "agent",
                    arguments = """{"description":"Analyze auth module","prompt":"Trace authentication flow","subagent_type":"explorer"}"""
                )
            ))
        ))

        // Simulate worker result coming back as a tool result
        val workerResult = buildString {
            appendLine("## Authentication Flow Analysis")
            appendLine("1. LoginController.login() receives credentials")
            appendLine("2. AuthService.authenticate() validates against LDAP")
            appendLine("3. JwtTokenProvider.generateToken() creates access token")
            appendLine("4. TokenFilter intercepts all requests for validation")
            appendLine()
            appendLine("Key files:")
            appendLine("- src/main/kotlin/auth/LoginController.kt")
            appendLine("- src/main/kotlin/auth/AuthService.kt")
            appendLine("- src/main/kotlin/auth/JwtTokenProvider.kt")
        }
        contextManager.addToolResult("tc_delegate_1", workerResult, "Authentication flow analyzed")

        // Verify the worker result is in the main context
        val messages = contextManager.getMessages()
        val toolResults = messages.filter { it.role == "tool" }
        assertTrue(toolResults.isNotEmpty(), "Worker result should be in context as a tool result")
        assertTrue(
            toolResults.any { it.content?.contains("Authentication Flow Analysis") == true },
            "Worker result content should be in context"
        )
        assertTrue(
            toolResults.any { it.content?.contains("LoginController.kt") == true },
            "Worker result should contain specific file paths"
        )

        // Verify the main context still has the original user message
        assertTrue(
            messages.any { it.role == "user" && it.content?.contains("authentication module") == true },
            "Original user message should be preserved alongside worker result"
        )
    }

    // ===== Test 4: Budget escalation — correct status progression =====

    @Test
    fun `budget escalation follows correct status progression`() = runTest {
        // Use a large maxInputTokens so ContextManager's own compression (at tMax = 85%)
        // does NOT trigger before we can observe BudgetEnforcer thresholds.
        // BudgetEnforcer effectiveBudget is set independently to 10,000 tokens.
        // Token estimation: 1 token per 3.5 chars, so 10,000 tokens = ~35,000 chars.
        val contextManager = ContextManager(maxInputTokens = 200_000, reservedTokens = 0)
        val enforcer = BudgetEnforcer(contextManager, effectiveBudget = 10_000)

        // Initially OK (0 tokens used)
        assertEquals(BudgetEnforcer.BudgetStatus.OK, enforcer.check(), "Should start at OK")

        // Add content to reach ~82% (8,200 tokens = ~28,700 chars)
        // COMPRESS threshold is 80% = 8,000 tokens
        contextManager.addMessage(ChatMessage(role = "system", content = "You are a coding assistant."))
        contextManager.addMessage(ChatMessage(role = "user", content = "x".repeat(28_700)))

        val afterMediumFill = enforcer.check()
        assertEquals(
            BudgetEnforcer.BudgetStatus.COMPRESS, afterMediumFill,
            "Should be at COMPRESS after ~82% fill (tokens=${contextManager.currentTokens})"
        )

        // Add more to reach ~90%
        contextManager.addMessage(ChatMessage(role = "assistant", content = "y".repeat(3_500)))

        val afterHeavierFill = enforcer.check()
        assertEquals(
            BudgetEnforcer.BudgetStatus.COMPRESS, afterHeavierFill,
            "Should still be COMPRESS at ~90% fill (tokens=${contextManager.currentTokens})"
        )

        // The progression should never go backward without compression
        // (OK < COMPRESS < TERMINATE)
        assertTrue(
            afterHeavierFill.ordinal >= afterMediumFill.ordinal,
            "Budget status should not decrease without compression: $afterMediumFill -> $afterHeavierFill"
        )
    }

    // ===== Test 5: Circuit breaker — warning after 5 consecutive tool failures =====

    @Test
    fun `circuit breaker triggers after 5 consecutive tool failures`() = runTest {
        val metrics = AgentMetrics()

        // Record 4 consecutive failures — should NOT trip circuit breaker
        for (i in 1..4) {
            metrics.recordToolCall("edit_file", durationMs = 100, success = false, tokens = 50)
            assertFalse(
                metrics.isCircuitBroken("edit_file"),
                "Circuit should not be broken after $i failures"
            )
        }
        assertEquals(4, metrics.consecutiveErrors("edit_file"))

        // 5th consecutive failure — should trip circuit breaker
        metrics.recordToolCall("edit_file", durationMs = 100, success = false, tokens = 50)
        assertTrue(
            metrics.isCircuitBroken("edit_file"),
            "Circuit should be broken after ${AgentMetrics.CIRCUIT_BREAKER_THRESHOLD} consecutive failures"
        )
        assertEquals(5, metrics.consecutiveErrors("edit_file"))

        // A success should reset the consecutive error count
        metrics.recordToolCall("edit_file", durationMs = 100, success = true, tokens = 50)
        assertFalse(
            metrics.isCircuitBroken("edit_file"),
            "Circuit should reset after a successful call"
        )
        assertEquals(0, metrics.consecutiveErrors("edit_file"))

        // Other tools should not be affected
        assertFalse(metrics.isCircuitBroken("read_file"), "Unrelated tool should not be broken")

        // Verify the snapshot reflects the metrics
        val snapshot = metrics.snapshot()
        assertEquals(6, snapshot.toolCalls["edit_file"]?.callCount, "Total call count should be 6")
        assertEquals(5, snapshot.toolCalls["edit_file"]?.errorCount, "Error count should be 5")
    }

    // ===== Test 6: Session recovery loads messages and adds recovery injection =====

    @Test
    fun `session recovery loads messages and adds recovery injection`() = runTest {
        val sessionDir = File(tempDir, "test-session-recovery")
        sessionDir.mkdirs()

        // Create a session with messages
        val store = ConversationStore("test-session-recovery", baseDir = tempDir)
        store.saveMessage(PersistedMessage(role = "system", content = "You are a coding assistant."))
        store.saveMessage(PersistedMessage(role = "user", content = "Fix the bug in AuthService"))
        store.saveMessage(PersistedMessage(
            role = "assistant",
            content = "I'll read the file first."
        ))

        store.saveMetadata(SessionMetadata(
            sessionId = "test-session-recovery",
            projectName = "TestProject",
            projectPath = "/tmp/project",
            title = "Fix the bug in AuthService",
            model = "test-model",
            createdAt = 1000L,
            lastMessageAt = 2000L,
            messageCount = 3,
            status = SessionStatus.INTERRUPTED
        ))

        // Load the session
        val recovered = ConversationStore.loadSession(sessionDir)
        assertNotNull(recovered, "Should load the session")
        assertEquals(3, recovered!!.messages.size, "Should have 3 messages")
        assertNull(recovered.compressionSummary, "No compression needed for 3 messages")
        assertNotNull(recovered.metadata)
        assertEquals(SessionStatus.INTERRUPTED, recovered.metadata?.status)
        assertTrue(
            recovered.recoveryMessage.contains("interrupted"),
            "Recovery message should mention the status"
        )
        assertTrue(
            recovered.recoveryMessage.contains("Resume from where you left off"),
            "Recovery message should instruct to resume"
        )
    }

    // ===== Test 7: Session recovery compresses old messages =====

    @Test
    fun `session recovery compresses old messages when count exceeds limit`() = runTest {
        val sessionDir = File(tempDir, "test-session-compress")
        sessionDir.mkdirs()

        val store = ConversationStore("test-session-compress", baseDir = tempDir)

        // Save 30 messages (exceeds the 20-message limit)
        store.saveMessage(PersistedMessage(role = "system", content = "You are a coding assistant."))
        for (i in 1..29) {
            if (i % 2 == 1) {
                store.saveMessage(PersistedMessage(role = "user", content = "User message $i"))
            } else {
                store.saveMessage(PersistedMessage(role = "assistant", content = "Assistant response $i"))
            }
        }

        store.saveMetadata(SessionMetadata(
            sessionId = "test-session-compress",
            projectName = "TestProject",
            projectPath = "/tmp/project",
            title = "Long conversation",
            model = "test-model",
            createdAt = 1000L,
            lastMessageAt = 30000L,
            messageCount = 30,
            status = SessionStatus.ACTIVE
        ))

        val recovered = ConversationStore.loadSession(sessionDir)
        assertNotNull(recovered)
        assertEquals(20, recovered!!.messages.size, "Should keep only last 20 messages")
        assertNotNull(recovered.compressionSummary, "Should have a compression summary for old messages")
        assertTrue(
            recovered.compressionSummary!!.contains("compressed"),
            "Summary should mention compression"
        )
        assertEquals(30, recovered.totalMessageCount, "Should report total original message count")
    }

    // ===== Test 8: Session recovery with plan includes plan progress =====

    @Test
    fun `session recovery includes plan progress in recovery message`() = runTest {
        val sessionDir = File(tempDir, "test-session-plan")
        sessionDir.mkdirs()

        val store = ConversationStore("test-session-plan", baseDir = tempDir)
        store.saveMessage(PersistedMessage(role = "user", content = "Add feature"))
        store.saveMetadata(SessionMetadata(
            sessionId = "test-session-plan",
            projectName = "P",
            projectPath = "/p",
            title = "Add feature",
            model = "m",
            createdAt = 1L,
            lastMessageAt = 2L,
            messageCount = 1,
            status = SessionStatus.INTERRUPTED
        ))

        // Save a plan with some steps done
        val plan = AgentPlan(
            goal = "Add user profile endpoint",
            steps = listOf(
                PlanStep(id = "s1", title = "Create DTO", status = "done"),
                PlanStep(id = "s2", title = "Add service", status = "done"),
                PlanStep(id = "s3", title = "Add controller", status = "pending")
            ),
            approved = true
        )
        PlanPersistence.save(plan, sessionDir)

        val recovered = ConversationStore.loadSession(sessionDir)
        assertNotNull(recovered)
        assertNotNull(recovered!!.plan, "Should load the plan")
        assertEquals(3, recovered.plan!!.steps.size)
        assertTrue(
            recovered.recoveryMessage.contains("2/3 steps completed"),
            "Recovery message should include plan progress: ${recovered.recoveryMessage}"
        )
        assertTrue(
            recovered.recoveryMessage.contains("Add service"),
            "Recovery message should mention last completed step: ${recovered.recoveryMessage}"
        )
    }

    // ===== Test 9: listSessions returns sorted sessions =====

    @Test
    fun `listSessions returns sessions sorted by last message time`() = runTest {
        // Create 3 sessions with different timestamps
        for ((idx, sessionId) in listOf("session-old", "session-mid", "session-new").withIndex()) {
            val store = ConversationStore(sessionId, baseDir = tempDir)
            store.saveMessage(PersistedMessage(role = "user", content = "Hello from $sessionId"))
            store.saveMetadata(SessionMetadata(
                sessionId = sessionId,
                projectName = "P",
                projectPath = "/p",
                title = "Session $sessionId",
                model = "m",
                createdAt = (idx + 1) * 1000L,
                lastMessageAt = (idx + 1) * 1000L,
                messageCount = 1,
                status = SessionStatus.COMPLETED
            ))
        }

        val sessions = ConversationStore.listSessions(baseDir = tempDir)
        assertEquals(3, sessions.size)
        // Should be sorted newest first
        assertEquals("session-new", sessions[0].sessionId)
        assertEquals("session-mid", sessions[1].sessionId)
        assertEquals("session-old", sessions[2].sessionId)

        // Verify metadata is populated
        assertTrue(sessions[0].hasMessages)
        assertEquals(SessionStatus.COMPLETED, sessions[0].status)
    }

    // ===== Test 10: Plan deviation detection works across turns =====

    @Test
    fun `plan deviation detection catches off-plan edits`() = runTest {
        val planManager = PlanManager()

        val plan = AgentPlan(
            goal = "Refactor user module",
            steps = listOf(
                PlanStep(
                    id = "s1",
                    title = "Update UserService",
                    files = listOf("UserService.kt", "UserRepository.kt"),
                    status = "running"
                ),
                PlanStep(
                    id = "s2",
                    title = "Update OrderService",
                    files = listOf("OrderService.kt"),
                    status = "pending",
                    dependsOn = listOf("s1")
                )
            )
        )
        planManager.submitPlan(plan)

        // Editing a file in the current step should not trigger deviation
        val onPlan = planManager.checkDeviation("edit_file", "src/main/kotlin/UserService.kt")
        assertNull(onPlan, "Editing UserService.kt should not trigger deviation warning")

        // Editing a file NOT in the current step should trigger deviation
        val offPlan = planManager.checkDeviation("edit_file", "src/main/kotlin/OrderService.kt")
        assertNotNull(offPlan, "Editing OrderService.kt should trigger deviation warning")
        assertTrue(offPlan!!.contains("Warning"), "Deviation message should contain warning")
        assertTrue(offPlan.contains("OrderService.kt"), "Deviation message should mention the off-plan file")
    }

    // ===== Helpers =====

    companion object {
        fun textResponse(content: String): ChatCompletionResponse {
            return ChatCompletionResponse(
                id = "resp-${System.nanoTime()}",
                choices = listOf(Choice(
                    index = 0,
                    message = ChatMessage(role = "assistant", content = content),
                    finishReason = "stop"
                )),
                usage = UsageInfo(promptTokens = 100, completionTokens = 50, totalTokens = 150)
            )
        }

        fun toolCallResponse(callId: String, toolName: String, args: String): ChatCompletionResponse {
            return ChatCompletionResponse(
                id = "resp-${System.nanoTime()}",
                choices = listOf(Choice(
                    index = 0,
                    message = ChatMessage(
                        role = "assistant",
                        content = null,
                        toolCalls = listOf(ToolCall(
                            id = callId,
                            function = FunctionCall(name = toolName, arguments = args)
                        ))
                    ),
                    finishReason = "tool_calls"
                )),
                usage = UsageInfo(promptTokens = 100, completionTokens = 50, totalTokens = 150)
            )
        }
    }
}
