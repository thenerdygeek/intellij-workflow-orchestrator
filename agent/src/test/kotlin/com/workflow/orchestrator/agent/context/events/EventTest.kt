package com.workflow.orchestrator.agent.context.events

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class EventTest {

    // --- CondensationAction: explicit IDs ---

    @Test
    fun `CondensationAction with explicit IDs returns correct forgotten list`() {
        val action = CondensationAction(
            forgottenEventIds = listOf(1, 3, 5, 7),
            forgottenEventsStartId = null,
            forgottenEventsEndId = null,
            summary = null,
            summaryOffset = null
        )
        assertEquals(listOf(1, 3, 5, 7), action.forgotten)
    }

    @Test
    fun `CondensationAction with empty explicit IDs returns empty forgotten list`() {
        val action = CondensationAction(
            forgottenEventIds = emptyList(),
            forgottenEventsStartId = null,
            forgottenEventsEndId = null,
            summary = null,
            summaryOffset = null
        )
        assertEquals(emptyList<Int>(), action.forgotten)
    }

    // --- CondensationAction: range ---

    @Test
    fun `CondensationAction with range returns correct forgotten list`() {
        val action = CondensationAction(
            forgottenEventIds = null,
            forgottenEventsStartId = 2,
            forgottenEventsEndId = 6,
            summary = "Summary of events 2-6",
            summaryOffset = 2
        )
        assertEquals(listOf(2, 3, 4, 5, 6), action.forgotten)
    }

    @Test
    fun `CondensationAction with single-element range returns single-element list`() {
        val action = CondensationAction(
            forgottenEventIds = null,
            forgottenEventsStartId = 5,
            forgottenEventsEndId = 5,
            summary = "Summary",
            summaryOffset = 5
        )
        assertEquals(listOf(5), action.forgotten)
    }

    // --- CondensationAction: validation ---

    @Test
    fun `CondensationAction validates summary and summaryOffset must be both set or both null`() {
        val ex1 = assertThrows<IllegalArgumentException> {
            CondensationAction(
                forgottenEventIds = listOf(1, 2),
                forgottenEventsStartId = null,
                forgottenEventsEndId = null,
                summary = "Some summary",
                summaryOffset = null
            ).validate()
        }
        assertTrue(ex1.message!!.contains("summary") || ex1.message!!.contains("summaryOffset"))

        val ex2 = assertThrows<IllegalArgumentException> {
            CondensationAction(
                forgottenEventIds = listOf(1, 2),
                forgottenEventsStartId = null,
                forgottenEventsEndId = null,
                summary = null,
                summaryOffset = 3
            ).validate()
        }
        assertTrue(ex2.message!!.contains("summary") || ex2.message!!.contains("summaryOffset"))
    }

    @Test
    fun `CondensationAction validates exactly one mode - both set throws`() {
        val ex = assertThrows<IllegalArgumentException> {
            CondensationAction(
                forgottenEventIds = listOf(1, 2),
                forgottenEventsStartId = 1,
                forgottenEventsEndId = 3,
                summary = null,
                summaryOffset = null
            ).validate()
        }
        assertTrue(ex.message!!.contains("one of"))
    }

    @Test
    fun `CondensationAction validates exactly one mode - neither set throws`() {
        val ex = assertThrows<IllegalArgumentException> {
            CondensationAction(
                forgottenEventIds = null,
                forgottenEventsStartId = null,
                forgottenEventsEndId = null,
                summary = null,
                summaryOffset = null
            ).validate()
        }
        assertTrue(ex.message!!.contains("one of"))
    }

    @Test
    fun `CondensationAction validates range requires both start and end`() {
        val ex = assertThrows<IllegalArgumentException> {
            CondensationAction(
                forgottenEventIds = null,
                forgottenEventsStartId = 1,
                forgottenEventsEndId = null,
                summary = null,
                summaryOffset = null
            ).validate()
        }
        assertTrue(ex.message != null)
    }

    @Test
    fun `CondensationAction with valid explicit IDs passes validation`() {
        // Should not throw
        CondensationAction(
            forgottenEventIds = listOf(1, 2, 3),
            forgottenEventsStartId = null,
            forgottenEventsEndId = null,
            summary = "A summary",
            summaryOffset = 1
        ).validate()
    }

    @Test
    fun `CondensationAction with valid range passes validation`() {
        // Should not throw
        CondensationAction(
            forgottenEventIds = null,
            forgottenEventsStartId = 3,
            forgottenEventsEndId = 7,
            summary = null,
            summaryOffset = null
        ).validate()
    }

    // --- ToolAction subtypes carry toolCallId and responseGroupId ---

    @Test
    fun `FileReadAction carries toolCallId and responseGroupId`() {
        val action = FileReadAction(
            toolCallId = "call_123",
            responseGroupId = "resp_456",
            path = "/src/Main.kt"
        )
        assertEquals("call_123", action.toolCallId)
        assertEquals("resp_456", action.responseGroupId)
        assertEquals("/src/Main.kt", action.path)
        assertTrue(action is ToolAction)
        assertTrue(action is Action)
        assertTrue(action is Event)
    }

    @Test
    fun `FileEditAction carries toolCallId and responseGroupId`() {
        val action = FileEditAction(
            toolCallId = "call_edit",
            responseGroupId = "resp_edit",
            path = "/src/Main.kt",
            oldStr = "old code",
            newStr = "new code"
        )
        assertEquals("call_edit", action.toolCallId)
        assertEquals("resp_edit", action.responseGroupId)
        assertEquals("/src/Main.kt", action.path)
        assertEquals("old code", action.oldStr)
        assertEquals("new code", action.newStr)
    }

    @Test
    fun `CommandRunAction carries toolCallId and responseGroupId`() {
        val action = CommandRunAction(
            toolCallId = "call_cmd",
            responseGroupId = "resp_cmd",
            command = "ls -la",
            cwd = "/home"
        )
        assertEquals("call_cmd", action.toolCallId)
        assertEquals("resp_cmd", action.responseGroupId)
        assertEquals("ls -la", action.command)
        assertEquals("/home", action.cwd)
    }

    @Test
    fun `SearchCodeAction carries toolCallId and responseGroupId`() {
        val action = SearchCodeAction(
            toolCallId = "call_search",
            responseGroupId = "resp_search",
            query = "fun main",
            path = "/src"
        )
        assertEquals("call_search", action.toolCallId)
        assertEquals("resp_search", action.responseGroupId)
    }

    @Test
    fun `DiagnosticsAction carries toolCallId and responseGroupId`() {
        val action = DiagnosticsAction(
            toolCallId = "call_diag",
            responseGroupId = "resp_diag",
            path = "/src/Main.kt"
        )
        assertEquals("call_diag", action.toolCallId)
        assertEquals("resp_diag", action.responseGroupId)
    }

    @Test
    fun `GenericToolAction carries toolCallId, responseGroupId, and tool details`() {
        val action = GenericToolAction(
            toolCallId = "call_gen",
            responseGroupId = "resp_gen",
            toolName = "custom_tool",
            arguments = """{"key": "value"}"""
        )
        assertEquals("call_gen", action.toolCallId)
        assertEquals("resp_gen", action.responseGroupId)
        assertEquals("custom_tool", action.toolName)
    }

    @Test
    fun `MetaToolAction carries toolCallId, responseGroupId, and meta-tool details`() {
        val action = MetaToolAction(
            toolCallId = "call_meta",
            responseGroupId = "resp_meta",
            toolName = "jira",
            actionName = "get_ticket",
            arguments = """{"key": "PROJ-123"}"""
        )
        assertEquals("call_meta", action.toolCallId)
        assertEquals("resp_meta", action.responseGroupId)
        assertEquals("jira", action.toolName)
        assertEquals("get_ticket", action.actionName)
    }

    // --- NEVER_FORGET_TYPES ---

    @Test
    fun `NEVER_FORGET_TYPES contains all compression-proof types`() {
        assertTrue(FactRecordedAction::class in NEVER_FORGET_TYPES)
        assertTrue(PlanUpdatedAction::class in NEVER_FORGET_TYPES)
        assertTrue(SkillActivatedAction::class in NEVER_FORGET_TYPES)
        assertTrue(GuardrailRecordedAction::class in NEVER_FORGET_TYPES)
        assertTrue(MentionAction::class in NEVER_FORGET_TYPES)
    }

    @Test
    fun `NEVER_FORGET_TYPES excludes non-compression-proof types`() {
        assertFalse(MessageAction::class in NEVER_FORGET_TYPES)
        assertFalse(AgentThinkAction::class in NEVER_FORGET_TYPES)
        assertFalse(AgentFinishAction::class in NEVER_FORGET_TYPES)
        assertFalse(CondensationAction::class in NEVER_FORGET_TYPES)
        assertFalse(FileReadAction::class in NEVER_FORGET_TYPES)
        assertFalse(FileEditAction::class in NEVER_FORGET_TYPES)
        assertFalse(CommandRunAction::class in NEVER_FORGET_TYPES)
        assertFalse(ToolResultObservation::class in NEVER_FORGET_TYPES)
        assertFalse(ErrorObservation::class in NEVER_FORGET_TYPES)
    }

    @Test
    fun `NEVER_FORGET_TYPES has exactly 5 types`() {
        assertEquals(5, NEVER_FORGET_TYPES.size)
    }

    // --- EventSource defaults ---

    @Test
    fun `MessageAction defaults to USER source`() {
        val action = MessageAction(content = "Hello")
        assertEquals(EventSource.USER, action.source)
    }

    @Test
    fun `SystemMessageAction defaults to SYSTEM source`() {
        val action = SystemMessageAction(content = "System init")
        assertEquals(EventSource.SYSTEM, action.source)
    }

    @Test
    fun `AgentThinkAction defaults to AGENT source`() {
        val action = AgentThinkAction(thought = "Thinking...")
        assertEquals(EventSource.AGENT, action.source)
    }

    @Test
    fun `AgentFinishAction defaults to AGENT source`() {
        val action = AgentFinishAction(finalThought = "Done")
        assertEquals(EventSource.AGENT, action.source)
    }

    @Test
    fun `ToolAction subtypes default to AGENT source`() {
        val action = FileReadAction(
            toolCallId = "tc1",
            responseGroupId = "rg1",
            path = "/file.kt"
        )
        assertEquals(EventSource.AGENT, action.source)
    }

    @Test
    fun `ToolResultObservation defaults to SYSTEM source`() {
        val obs = ToolResultObservation(
            toolCallId = "tc1",
            content = "file contents",
            isError = false,
            toolName = "read_file"
        )
        assertEquals(EventSource.SYSTEM, obs.source)
    }

    @Test
    fun `ErrorObservation defaults to SYSTEM source`() {
        val obs = ErrorObservation(content = "Something went wrong")
        assertEquals(EventSource.SYSTEM, obs.source)
    }

    @Test
    fun `CondensationAction defaults to SYSTEM source`() {
        val action = CondensationAction(
            forgottenEventIds = listOf(1),
            forgottenEventsStartId = null,
            forgottenEventsEndId = null,
            summary = null,
            summaryOffset = null
        )
        assertEquals(EventSource.SYSTEM, action.source)
    }

    // --- Default id and timestamp ---

    @Test
    fun `events have default id of -1 and timestamp of EPOCH`() {
        val action = MessageAction(content = "test")
        assertEquals(-1, action.id)
        assertEquals(Instant.EPOCH, action.timestamp)
    }

    // --- Observation content ---

    @Test
    fun `ToolResultObservation carries content and error flag`() {
        val obs = ToolResultObservation(
            toolCallId = "tc1",
            content = "Error: file not found",
            isError = true,
            toolName = "read_file"
        )
        assertEquals("Error: file not found", obs.content)
        assertTrue(obs.isError)
        assertEquals("read_file", obs.toolName)
        assertTrue(obs is Observation)
    }

    @Test
    fun `CondensationObservation carries content`() {
        val obs = CondensationObservation(content = "Summary of compressed events")
        assertEquals("Summary of compressed events", obs.content)
        assertTrue(obs is Observation)
    }

    @Test
    fun `SuccessObservation carries content`() {
        val obs = SuccessObservation(content = "Task completed successfully")
        assertEquals("Task completed successfully", obs.content)
        assertTrue(obs is Observation)
    }

    // --- Data class equality and copy ---

    @Test
    fun `MessageAction supports data class copy for id and timestamp assignment`() {
        val original = MessageAction(content = "Hello", imageUrls = listOf("http://img.png"))
        val assigned = original.copy(id = 42, timestamp = Instant.ofEpochSecond(1000))
        assertEquals(42, assigned.id)
        assertEquals(Instant.ofEpochSecond(1000), assigned.timestamp)
        assertEquals("Hello", assigned.content)
        assertEquals(listOf("http://img.png"), assigned.imageUrls)
    }

    @Test
    fun `DelegateAction has correct fields`() {
        val action = DelegateAction(
            agentType = "coder",
            prompt = "Implement the feature",
            thought = "Need to delegate"
        )
        assertEquals("coder", action.agentType)
        assertEquals("Implement the feature", action.prompt)
        assertEquals("Need to delegate", action.thought)
        assertEquals(EventSource.AGENT, action.source)
    }

    @Test
    fun `CondensationRequestAction has no extra fields`() {
        val action = CondensationRequestAction()
        assertEquals(EventSource.SYSTEM, action.source)
        assertEquals(-1, action.id)
    }
}
