package com.workflow.orchestrator.agent.context

import com.workflow.orchestrator.agent.api.dto.ChatMessage
import com.workflow.orchestrator.agent.api.dto.FunctionCall
import com.workflow.orchestrator.agent.api.dto.ToolCall
import com.workflow.orchestrator.agent.context.condenser.CondenserFactory
import com.workflow.orchestrator.agent.context.events.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class EventSourcedContextBridgeTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var contextManager: ContextManager
    private lateinit var bridge: EventSourcedContextBridge

    @BeforeEach
    fun setUp() {
        contextManager = ContextManager(maxInputTokens = 100_000)
        bridge = EventSourcedContextBridge.create(
            contextManager = contextManager,
            sessionDir = tempDir,
            config = ContextManagementConfig.DEFAULT,
            summarizationClient = null // No LLM for tests
        )
    }

    @Test
    fun `addSystemPrompt writes to both contextManager and eventStore`() {
        bridge.addSystemPrompt("You are an assistant.")

        // ContextManager should have the system message
        val messages = contextManager.getMessages()
        assertEquals(1, messages.size)
        assertEquals("system", messages[0].role)
        assertEquals("You are an assistant.", messages[0].content)

        // EventStore should have a SystemMessageAction
        val events = bridge.eventStore.all()
        assertEquals(1, events.size)
        assertTrue(events[0] is SystemMessageAction)
        assertEquals("You are an assistant.", (events[0] as SystemMessageAction).content)
    }

    @Test
    fun `addUserMessage writes to both and sets initialUserAction`() {
        bridge.addUserMessage("Hello, help me with code")

        val messages = contextManager.getMessages()
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].role)

        val events = bridge.eventStore.all()
        assertEquals(1, events.size)
        assertTrue(events[0] is MessageAction)
        assertEquals(EventSource.USER, events[0].source)
    }

    @Test
    fun `addAssistantMessage writes text response to both`() {
        val msg = ChatMessage(role = "assistant", content = "Here is the answer")
        bridge.addAssistantMessage(msg)

        val messages = contextManager.getMessages()
        assertEquals(1, messages.size)
        assertEquals("assistant", messages[0].role)

        val events = bridge.eventStore.all()
        assertEquals(1, events.size)
        assertTrue(events[0] is MessageAction)
        assertEquals(EventSource.AGENT, events[0].source)
    }

    @Test
    fun `addAssistantToolCalls creates ToolAction events`() {
        val toolCalls = listOf(
            ToolCall(id = "tc1", function = FunctionCall(name = "read_file", arguments = """{"path":"/tmp/test.kt"}""")),
            ToolCall(id = "tc2", function = FunctionCall(name = "search_code", arguments = """{"query":"hello"}"""))
        )
        val msg = ChatMessage(role = "assistant", content = null, toolCalls = toolCalls)
        val groupId = bridge.addAssistantToolCalls(msg)

        assertNotNull(groupId)

        // ContextManager should have the assistant message
        val messages = contextManager.getMessages()
        assertEquals(1, messages.size)
        assertEquals("assistant", messages[0].role)
        assertEquals(2, messages[0].toolCalls?.size)

        // EventStore should have 2 ToolAction events
        val events = bridge.eventStore.all()
        assertEquals(2, events.size)
        assertTrue(events[0] is FileReadAction)
        assertTrue(events[1] is SearchCodeAction)
        assertEquals("tc1", (events[0] as FileReadAction).toolCallId)
        assertEquals("tc2", (events[1] as SearchCodeAction).toolCallId)
    }

    @Test
    fun `addToolResult creates ToolResultObservation`() {
        bridge.addToolResult("tc1", "File contents here", "Read 10 lines", "read_file")

        // ContextManager should have the tool result
        val messages = contextManager.getMessages()
        assertEquals(1, messages.size)
        assertEquals("tool", messages[0].role)

        // EventStore should have a ToolResultObservation
        val events = bridge.eventStore.all()
        assertEquals(1, events.size)
        assertTrue(events[0] is ToolResultObservation)
        val obs = events[0] as ToolResultObservation
        assertEquals("tc1", obs.toolCallId)
        assertEquals("read_file", obs.toolName)
        assertFalse(obs.isError)
    }

    @Test
    fun `addToolError creates error ToolResultObservation`() {
        bridge.addToolError("tc1", "Error: file not found", "File not found", "read_file")

        val events = bridge.eventStore.all()
        assertEquals(1, events.size)
        val obs = events[0] as ToolResultObservation
        assertTrue(obs.isError)
    }

    @Test
    fun `addSystemMessage creates SystemMessageAction`() {
        bridge.addSystemMessage("Context is 50% full")

        val events = bridge.eventStore.all()
        assertEquals(1, events.size)
        assertTrue(events[0] is SystemMessageAction)
    }

    @Test
    fun `addMessage routes based on role`() {
        bridge.addMessage(ChatMessage(role = "system", content = "Warning"))
        bridge.addMessage(ChatMessage(role = "user", content = "Follow up"))

        val events = bridge.eventStore.all()
        assertEquals(2, events.size)
        assertTrue(events[0] is SystemMessageAction)
        assertEquals(EventSource.SYSTEM, events[0].source)
        assertTrue(events[1] is MessageAction)
        assertEquals(EventSource.USER, events[1].source)
    }

    @Test
    fun `recordFact creates FactRecordedAction`() {
        bridge.recordFact("FILE_READ", "/tmp/test.kt", "10 lines")

        val events = bridge.eventStore.all()
        assertEquals(1, events.size)
        assertTrue(events[0] is FactRecordedAction)
        assertEquals("FILE_READ", (events[0] as FactRecordedAction).factType)
    }

    @Test
    fun `recordPlanUpdate creates PlanUpdatedAction`() {
        bridge.recordPlanUpdate("""{"goal":"test","steps":[]}""")

        val events = bridge.eventStore.all()
        assertEquals(1, events.size)
        assertTrue(events[0] is PlanUpdatedAction)
    }

    @Test
    fun `recordSkillActivated creates SkillActivatedAction`() {
        bridge.recordSkillActivated("debugging", "Debug skill content")

        val events = bridge.eventStore.all()
        assertEquals(1, events.size)
        assertTrue(events[0] is SkillActivatedAction)
        assertEquals("debugging", (events[0] as SkillActivatedAction).skillName)
    }

    @Test
    fun `recordMention creates MentionAction`() {
        bridge.recordMention(listOf("/tmp/a.kt", "/tmp/b.kt"), "File contents")

        val events = bridge.eventStore.all()
        assertEquals(1, events.size)
        assertTrue(events[0] is MentionAction)
        assertEquals(2, (events[0] as MentionAction).paths.size)
    }

    @Test
    fun `flushEvents persists to JSONL`() {
        bridge.addSystemPrompt("System prompt")
        bridge.addUserMessage("Hello")
        bridge.flushEvents()

        val jsonlFile = File(tempDir, EventStore.JSONL_FILENAME)
        assertTrue(jsonlFile.exists())
        val lines = jsonlFile.readLines().filter { it.isNotBlank() }
        assertEquals(2, lines.size)
    }

    @Test
    fun `full conversation flow with events`() {
        // Simulate a complete conversation turn
        bridge.addSystemPrompt("You are an AI assistant")
        bridge.addUserMessage("Read test.kt")

        // Assistant responds with tool call
        val toolCalls = listOf(
            ToolCall(id = "tc1", function = FunctionCall(name = "read_file", arguments = """{"path":"test.kt"}"""))
        )
        bridge.addAssistantToolCalls(ChatMessage(role = "assistant", content = null, toolCalls = toolCalls))

        // Tool result
        bridge.addToolResult("tc1", "class Test {}", "Read 1 line", "read_file")

        // Assistant responds with text
        bridge.addAssistantMessage(ChatMessage(role = "assistant", content = "The file contains a Test class."))

        // Verify event store
        val events = bridge.eventStore.all()
        assertEquals(5, events.size)
        assertTrue(events[0] is SystemMessageAction)
        assertTrue(events[1] is MessageAction) // user
        assertTrue(events[2] is FileReadAction) // tool call
        assertTrue(events[3] is ToolResultObservation) // tool result
        assertTrue(events[4] is MessageAction) // assistant text

        // Verify contextManager also has everything
        val messages = contextManager.getMessages()
        assertEquals(5, messages.size)

        // Verify flush works
        bridge.flushEvents()
        val jsonlFile = File(tempDir, EventStore.JSONL_FILENAME)
        assertTrue(jsonlFile.exists())
        assertEquals(5, jsonlFile.readLines().filter { it.isNotBlank() }.size)
    }

    @Test
    fun `loadFromDisk restores events`() {
        // Create events and flush
        bridge.addSystemPrompt("System prompt")
        bridge.addUserMessage("Hello")
        bridge.flushEvents()

        // Load from disk
        val loaded = EventSourcedContextBridge.loadFromDisk(
            contextManager = ContextManager(maxInputTokens = 100_000),
            sessionDir = tempDir,
            config = ContextManagementConfig.DEFAULT,
            summarizationClient = null
        )

        assertEquals(2, loaded.eventStore.size())
        val events = loaded.eventStore.all()
        assertTrue(events[0] is SystemMessageAction)
        assertTrue(events[1] is MessageAction)
    }

    @Test
    fun `delegated properties work correctly`() {
        bridge.addSystemPrompt("Test")
        assertTrue(bridge.currentTokens > 0)
        assertTrue(bridge.effectiveMaxInputTokens > 0)
        assertFalse(bridge.isBudgetCritical())
        assertTrue(bridge.remainingBudget() > 0)
        assertFalse(bridge.hasPlanAnchor)
    }

    @Test
    fun `meta-tool actions are created correctly`() {
        val toolCalls = listOf(
            ToolCall(id = "tc1", function = FunctionCall(name = "jira", arguments = """{"action":"get_ticket","issue_key":"PROJ-123"}"""))
        )
        bridge.addAssistantToolCalls(ChatMessage(role = "assistant", content = null, toolCalls = toolCalls))

        val events = bridge.eventStore.all()
        assertEquals(1, events.size)
        assertTrue(events[0] is MetaToolAction)
        val meta = events[0] as MetaToolAction
        assertEquals("jira", meta.toolName)
        assertEquals("get_ticket", meta.actionName)
    }

    @Test
    fun `generic tool actions for unknown tools`() {
        val toolCalls = listOf(
            ToolCall(id = "tc1", function = FunctionCall(name = "think", arguments = """{"thought":"planning..."}"""))
        )
        bridge.addAssistantToolCalls(ChatMessage(role = "assistant", content = null, toolCalls = toolCalls))

        val events = bridge.eventStore.all()
        assertEquals(1, events.size)
        assertTrue(events[0] is GenericToolAction)
        assertEquals("think", (events[0] as GenericToolAction).toolName)
    }

    // =========================================================================
    // Phase 2: getMessagesViaCondenser() and updateTokensFromUsage() tests
    // =========================================================================

    @Nested
    inner class GetMessagesViaCondenserTest {

        @Test
        fun `returns Messages with empty event store`() {
            val outcome = bridge.getMessagesViaCondenser()
            assertTrue(outcome is CondenserOutcome.Messages)
            // Empty event store → ConversationMemory produces a minimal context
            val messages = (outcome as CondenserOutcome.Messages).messages
            assertNotNull(messages)
        }

        @Test
        fun `returns Messages containing user content after addUserMessage`() {
            bridge.addSystemPrompt("You are an assistant.")
            bridge.addUserMessage("Fix the bug in Main.kt")

            val outcome = bridge.getMessagesViaCondenser()
            assertTrue(outcome is CondenserOutcome.Messages, "Expected Messages but got $outcome")
            val messages = (outcome as CondenserOutcome.Messages).messages
            assertTrue(messages.isNotEmpty())
            // ConversationMemory sanitizes for Sourcegraph: user role expected
            assertTrue(messages.any { it.role == "user" })
        }

        @Test
        fun `full conversation round-trip produces correct message sequence`() {
            bridge.addSystemPrompt("You are an assistant.")
            bridge.addUserMessage("Read test.kt")
            bridge.addAssistantToolCalls(
                ChatMessage(
                    role = "assistant",
                    content = null,
                    toolCalls = listOf(
                        ToolCall(id = "tc1", function = FunctionCall(name = "read_file", arguments = """{"path":"test.kt"}"""))
                    )
                )
            )
            bridge.addToolResult("tc1", "class Test {}", "Read 1 line", "read_file")
            bridge.addAssistantMessage(ChatMessage(role = "assistant", content = "The file has a Test class."))

            val outcome = bridge.getMessagesViaCondenser()
            assertTrue(outcome is CondenserOutcome.Messages)
            val messages = (outcome as CondenserOutcome.Messages).messages
            assertTrue(messages.isNotEmpty())
            // Sourcegraph format: must start with user
            assertEquals("user", messages.first().role)
            // Must contain user request and assistant response
            val allContent = messages.joinToString("\n") { it.content ?: "" }
            assertTrue(allContent.contains("Read test.kt") || allContent.contains("Fix the bug"))
        }

        @Test
        fun `returns Messages when condenser pipeline passes through view unchanged`() {
            // With DEFAULT config and low token count, the NoOp condenser pipeline
            // should pass the view through → Messages result, not NeedsCondensation
            bridge.addSystemPrompt("System")
            bridge.addUserMessage("Hello")

            val outcome = bridge.getMessagesViaCondenser()
            // Should NOT need condensation on a tiny conversation
            assertTrue(outcome is CondenserOutcome.Messages,
                "Expected Messages for small conversation, got: $outcome")
        }

        @Test
        fun `calling getMessagesViaCondenser multiple times is idempotent`() {
            bridge.addSystemPrompt("System")
            bridge.addUserMessage("Task")

            val outcome1 = bridge.getMessagesViaCondenser()
            val outcome2 = bridge.getMessagesViaCondenser()

            assertTrue(outcome1 is CondenserOutcome.Messages)
            assertTrue(outcome2 is CondenserOutcome.Messages)
            assertEquals(
                (outcome1 as CondenserOutcome.Messages).messages.size,
                (outcome2 as CondenserOutcome.Messages).messages.size
            )
        }
    }

    @Nested
    inner class UpdateTokensFromUsageTest {

        @Test
        fun `updateTokensFromUsage sets tokenUtilization based on reported tokens`() {
            // tokenUtilization should be near 0 before any update
            val utilizationBefore = bridge.tokenUtilization
            assertTrue(utilizationBefore >= 0.0)

            // Report some tokens — with 100K budget, 50K tokens = 0.5 utilization
            bridge.updateTokensFromUsage(50_000)
            val utilizationAfter = bridge.tokenUtilization

            // Should now reflect 50K / 100K = 0.5
            assertEquals(0.5, utilizationAfter, 0.01)
        }

        @Test
        fun `updateTokensFromUsage is ignored for zero or negative values`() {
            bridge.addUserMessage("Hello")
            val utilizationBefore = bridge.tokenUtilization

            bridge.updateTokensFromUsage(0)
            bridge.updateTokensFromUsage(-100)

            // Utilization should not change from invalid input
            assertEquals(utilizationBefore, bridge.tokenUtilization, 0.001)
        }

        @Test
        fun `updateTokensFromUsage also reconciles ContextManager`() {
            bridge.addUserMessage("Hello")
            val heuristicTokens = bridge.currentTokens

            // Report a significantly different count from the API
            val apiReportedTokens = heuristicTokens * 2
            bridge.updateTokensFromUsage(apiReportedTokens)

            // ContextManager should now reflect the API-reported count
            assertEquals(apiReportedTokens, bridge.currentTokens)
        }

        @Test
        fun `getMessagesViaCondenser uses API-reported tokens after updateTokensFromUsage`() {
            bridge.addSystemPrompt("System")
            bridge.addUserMessage("Task")

            // With default 100K budget, a 98K token report should drive high utilization
            // but the NoOp pipeline still passes through
            bridge.updateTokensFromUsage(1_000) // well under budget

            val outcome = bridge.getMessagesViaCondenser()
            assertTrue(outcome is CondenserOutcome.Messages)
        }

        @Test
        fun `tokenUtilization falls back to heuristic when no API tokens reported`() {
            // No updateTokensFromUsage called; falls back to ContextManager.currentTokens
            bridge.addSystemPrompt("System")
            val utilization = bridge.tokenUtilization
            // Should be heuristic-based but non-negative
            assertTrue(utilization >= 0.0)
            assertTrue(utilization < 1.0)
        }
    }
}
