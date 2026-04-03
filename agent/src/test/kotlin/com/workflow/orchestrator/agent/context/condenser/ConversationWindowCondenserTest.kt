package com.workflow.orchestrator.agent.context.condenser

import com.workflow.orchestrator.agent.context.events.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ConversationWindowCondenserTest {

    private val condenser = ConversationWindowCondenser()

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun contextOf(
        view: View,
        utilization: Double = 0.5
    ) = CondenserContext(
        view = view,
        tokenUtilization = utilization,
        effectiveBudget = 100_000,
        currentTokens = (100_000 * utilization).toInt()
    )

    // -----------------------------------------------------------------------
    // shouldCondense tests
    // -----------------------------------------------------------------------

    @Test
    fun `shouldCondense returns true when unhandledCondensationRequest is true`() {
        val view = View(
            events = listOf(MessageAction(content = "hello", id = 1)),
            unhandledCondensationRequest = true
        )
        assertTrue(condenser.shouldCondense(contextOf(view)))
    }

    @Test
    fun `shouldCondense returns false when below threshold and no request`() {
        val events = (1..1000).map { MessageAction(content = "msg-$it", id = it) }
        val view = View(events = events, unhandledCondensationRequest = false)
        assertFalse(condenser.shouldCondense(contextOf(view, utilization = 0.50)))
    }

    @Test
    fun `shouldCondense returns true when above threshold even without request`() {
        val events = (1..100).map { MessageAction(content = "msg-$it", id = it) }
        val view = View(events = events, unhandledCondensationRequest = false)
        assertTrue(condenser.shouldCondense(contextOf(view, utilization = 0.80)))
    }

    @Test
    fun `shouldCondense returns true at exact threshold boundary`() {
        val events = (1..10).map { MessageAction(content = "msg-$it", id = it) }
        val view = View(events = events, unhandledCondensationRequest = false)
        assertTrue(condenser.shouldCondense(contextOf(view, utilization = 0.75)))
    }

    @Test
    fun `shouldCondense returns false for empty view without request`() {
        val view = View(events = emptyList(), unhandledCondensationRequest = false)
        assertFalse(condenser.shouldCondense(contextOf(view)))
    }

    // -----------------------------------------------------------------------
    // getCondensation — essential event preservation
    // -----------------------------------------------------------------------

    @Test
    fun `getCondensation keeps SystemMessageAction and first user MessageAction`() {
        val sysMsg = SystemMessageAction(content = "system prompt", id = 1)
        val userMsg = MessageAction(content = "user task", id = 2, source = EventSource.USER)
        val agentMsg = MessageAction(content = "response 1", id = 3, source = EventSource.AGENT)
        val toolResult = ToolResultObservation(
            toolCallId = "tc1", content = "result", isError = false, toolName = "read_file", id = 4
        )
        val agentMsg2 = MessageAction(content = "response 2", id = 5, source = EventSource.AGENT)
        val toolResult2 = ToolResultObservation(
            toolCallId = "tc2", content = "result2", isError = false, toolName = "edit_file", id = 6
        )
        val agentMsg3 = MessageAction(content = "response 3", id = 7, source = EventSource.AGENT)
        val toolResult3 = ToolResultObservation(
            toolCallId = "tc3", content = "result3", isError = false, toolName = "search", id = 8
        )

        val view = View(
            events = listOf(sysMsg, userMsg, agentMsg, toolResult, agentMsg2, toolResult2, agentMsg3, toolResult3),
            unhandledCondensationRequest = true
        )

        val result = condenser.getCondensation(contextOf(view))
        val action = result.action

        // Essential events (ids 1,2) must NOT be in the forgotten set
        val forgotten = action.forgotten.toSet()
        assertFalse(1 in forgotten, "SystemMessageAction must not be forgotten")
        assertFalse(2 in forgotten, "First user MessageAction must not be forgotten")
    }

    // -----------------------------------------------------------------------
    // getCondensation — keeps roughly half from tail
    // -----------------------------------------------------------------------

    @Test
    fun `getCondensation keeps roughly half of non-essential events from the tail`() {
        val sysMsg = SystemMessageAction(content = "system", id = 1)
        val userMsg = MessageAction(content = "task", id = 2, source = EventSource.USER)
        // 8 non-essential events (ids 3-10)
        val nonEssential = (3..10).map {
            MessageAction(content = "msg-$it", id = it, source = EventSource.AGENT)
        }

        val view = View(
            events = listOf(sysMsg, userMsg) + nonEssential,
            unhandledCondensationRequest = true
        )

        val result = condenser.getCondensation(contextOf(view))
        val forgotten = result.action.forgotten.toSet()

        // 2 essential + 8 non-essential = 10 total
        // numNonEssential = 8, numRecentToKeep = max(1, 8/2) = 4
        // Should keep 4 from the tail (ids 7,8,9,10) + essentials (1,2)
        // Should forget ids 3,4,5,6
        assertEquals(setOf(3, 4, 5, 6), forgotten)
    }

    // -----------------------------------------------------------------------
    // getCondensation — dangling observations
    // -----------------------------------------------------------------------

    @Test
    fun `getCondensation skips dangling observations at slice boundary`() {
        val sysMsg = SystemMessageAction(content = "system", id = 1)
        val userMsg = MessageAction(content = "task", id = 2, source = EventSource.USER)
        // Non-essential events: mix of actions and observations
        // ids 3-10: action, obs, action, obs, obs, action, obs, action
        val events = listOf(
            sysMsg,
            userMsg,
            MessageAction(content = "a1", id = 3, source = EventSource.AGENT),
            ToolResultObservation(toolCallId = "t1", content = "r1", isError = false, toolName = "read", id = 4),
            MessageAction(content = "a2", id = 5, source = EventSource.AGENT),
            ToolResultObservation(toolCallId = "t2", content = "r2", isError = false, toolName = "edit", id = 6),
            ToolResultObservation(toolCallId = "t3", content = "r3", isError = false, toolName = "search", id = 7),
            MessageAction(content = "a3", id = 8, source = EventSource.AGENT),
            ToolResultObservation(toolCallId = "t4", content = "r4", isError = false, toolName = "run", id = 9),
            MessageAction(content = "a4", id = 10, source = EventSource.AGENT),
        )

        val view = View(events = events, unhandledCondensationRequest = true)
        val result = condenser.getCondensation(contextOf(view))
        val forgotten = result.action.forgotten.toSet()
        val kept = events.map { it.id }.toSet() - forgotten

        // 2 essential + 8 non-essential = 10 total
        // numRecentToKeep = max(1, 8/2) = 4
        // sliceStartIndex = 10 - 4 = 6 → event at index 6 is id=7 (ToolResultObservation)
        // index 7 is id=8 (MessageAction) — first non-Observation
        // So firstValidEventIndex = 7
        // Keep: essentials (1,2) + events from index 7 onward (8,9,10)
        assertTrue(1 in kept)
        assertTrue(2 in kept)
        assertTrue(8 in kept)
        assertTrue(9 in kept)
        assertTrue(10 in kept)
        // The dangling observation at the slice boundary (id=7) should be forgotten
        assertTrue(7 in forgotten, "Dangling observation at slice boundary should be forgotten")
    }

    // -----------------------------------------------------------------------
    // getCondensation — range mode for contiguous IDs
    // -----------------------------------------------------------------------

    @Test
    fun `getCondensation uses range mode when forgotten IDs are contiguous`() {
        val sysMsg = SystemMessageAction(content = "system", id = 1)
        val userMsg = MessageAction(content = "task", id = 2, source = EventSource.USER)
        // 6 non-essential with contiguous IDs
        val nonEssential = (3..8).map {
            MessageAction(content = "msg-$it", id = it, source = EventSource.AGENT)
        }

        val view = View(
            events = listOf(sysMsg, userMsg) + nonEssential,
            unhandledCondensationRequest = true
        )

        val result = condenser.getCondensation(contextOf(view))
        val action = result.action

        // 2 essential + 6 non-essential = 8 total
        // numRecentToKeep = max(1, 6/2) = 3
        // Keep: essentials (1,2) + tail 3 (6,7,8)
        // Forget: 3,4,5 — contiguous
        assertNull(action.forgottenEventIds, "Should use range mode, not explicit IDs")
        assertNotNull(action.forgottenEventsStartId)
        assertNotNull(action.forgottenEventsEndId)
        assertEquals(3, action.forgottenEventsStartId)
        assertEquals(5, action.forgottenEventsEndId)
    }

    // -----------------------------------------------------------------------
    // getCondensation — explicit list mode for non-contiguous IDs
    // -----------------------------------------------------------------------

    @Test
    fun `getCondensation uses explicit list when forgotten IDs are non-contiguous`() {
        val sysMsg = SystemMessageAction(content = "system", id = 1)
        val userMsg = MessageAction(content = "task", id = 2, source = EventSource.USER)
        // Non-contiguous IDs: put a NEVER_FORGET event in the middle of what would be forgotten
        val events = listOf(
            sysMsg,
            userMsg,
            MessageAction(content = "a", id = 3, source = EventSource.AGENT),
            FactRecordedAction(factType = "DISCOVERY", path = null, content = "fact", id = 4), // NEVER_FORGET
            MessageAction(content = "b", id = 5, source = EventSource.AGENT),
            MessageAction(content = "c", id = 6, source = EventSource.AGENT),
            MessageAction(content = "d", id = 7, source = EventSource.AGENT),
            MessageAction(content = "e", id = 8, source = EventSource.AGENT),
        )

        val view = View(events = events, unhandledCondensationRequest = true)
        val result = condenser.getCondensation(contextOf(view))
        val action = result.action

        // 2 essential + 6 non-essential = 8
        // numRecentToKeep = max(1, 6/2) = 3
        // Keep: essentials (1,2) + tail 3 (6,7,8) + NEVER_FORGET (4)
        // Forget: 3,5 — non-contiguous because 4 is protected
        assertNotNull(action.forgottenEventIds, "Should use explicit list for non-contiguous IDs")
        assertNull(action.forgottenEventsStartId)
        assertEquals(listOf(3, 5), action.forgottenEventIds)
    }

    // -----------------------------------------------------------------------
    // getCondensation — only essential events (nothing to forget)
    // -----------------------------------------------------------------------

    @Test
    fun `getCondensation handles view with only essential events`() {
        val sysMsg = SystemMessageAction(content = "system", id = 1)
        val userMsg = MessageAction(content = "task", id = 2, source = EventSource.USER)

        val view = View(
            events = listOf(sysMsg, userMsg),
            unhandledCondensationRequest = true
        )

        val result = condenser.getCondensation(contextOf(view))
        val action = result.action

        // Nothing to forget — no-op condensation
        assertTrue(action.forgotten.isEmpty())
    }

    // -----------------------------------------------------------------------
    // getCondensation — no first user message
    // -----------------------------------------------------------------------

    @Test
    fun `getCondensation returns no-op when no user message exists`() {
        val sysMsg = SystemMessageAction(content = "system", id = 1)
        val agentMsg = MessageAction(content = "agent msg", id = 2, source = EventSource.AGENT)

        val view = View(
            events = listOf(sysMsg, agentMsg),
            unhandledCondensationRequest = true
        )

        val result = condenser.getCondensation(contextOf(view))
        assertTrue(result.action.forgotten.isEmpty())
    }

    // -----------------------------------------------------------------------
    // getCondensation — NEVER_FORGET_TYPES protection
    // -----------------------------------------------------------------------

    @Test
    fun `getCondensation does NOT include NEVER_FORGET_TYPES events in forgotten set`() {
        val sysMsg = SystemMessageAction(content = "system", id = 1)
        val userMsg = MessageAction(content = "task", id = 2, source = EventSource.USER)
        val events = listOf(
            sysMsg,
            userMsg,
            MessageAction(content = "a", id = 3, source = EventSource.AGENT),
            FactRecordedAction(factType = "DISCOVERY", path = null, content = "important fact", id = 4),
            PlanUpdatedAction(planJson = "{}", id = 5),
            GuardrailRecordedAction(rule = "never do X", id = 6),
            MessageAction(content = "b", id = 7, source = EventSource.AGENT),
            MessageAction(content = "c", id = 8, source = EventSource.AGENT),
            MessageAction(content = "d", id = 9, source = EventSource.AGENT),
            MessageAction(content = "e", id = 10, source = EventSource.AGENT),
            MessageAction(content = "f", id = 11, source = EventSource.AGENT),
            MessageAction(content = "g", id = 12, source = EventSource.AGENT),
        )

        val view = View(events = events, unhandledCondensationRequest = true)
        val result = condenser.getCondensation(contextOf(view))
        val forgotten = result.action.forgotten.toSet()

        // NEVER_FORGET_TYPES must never appear in forgotten
        assertFalse(4 in forgotten, "FactRecordedAction must not be forgotten")
        assertFalse(5 in forgotten, "PlanUpdatedAction must not be forgotten")
        assertFalse(6 in forgotten, "GuardrailRecordedAction must not be forgotten")
    }

    @Test
    fun `getCondensation protects SkillActivatedAction and MentionAction`() {
        val sysMsg = SystemMessageAction(content = "system", id = 1)
        val userMsg = MessageAction(content = "task", id = 2, source = EventSource.USER)
        val events = listOf(
            sysMsg,
            userMsg,
            SkillActivatedAction(skillName = "debug", content = "skill content", id = 3),
            MentionAction(paths = listOf("/foo.kt"), content = "file content", id = 4),
            MessageAction(content = "a", id = 5, source = EventSource.AGENT),
            MessageAction(content = "b", id = 6, source = EventSource.AGENT),
            MessageAction(content = "c", id = 7, source = EventSource.AGENT),
            MessageAction(content = "d", id = 8, source = EventSource.AGENT),
            MessageAction(content = "e", id = 9, source = EventSource.AGENT),
            MessageAction(content = "f", id = 10, source = EventSource.AGENT),
        )

        val view = View(events = events, unhandledCondensationRequest = true)
        val result = condenser.getCondensation(contextOf(view))
        val forgotten = result.action.forgotten.toSet()

        assertFalse(3 in forgotten, "SkillActivatedAction must not be forgotten")
        assertFalse(4 in forgotten, "MentionAction must not be forgotten")
    }

    // -----------------------------------------------------------------------
    // Integration: full condense() call
    // -----------------------------------------------------------------------

    @Test
    fun `full condense call with unhandledCondensationRequest returns Condensation`() {
        val sysMsg = SystemMessageAction(content = "system", id = 1)
        val userMsg = MessageAction(content = "task", id = 2, source = EventSource.USER)
        val nonEssential = (3..10).map {
            MessageAction(content = "msg-$it", id = it, source = EventSource.AGENT)
        }

        val view = View(
            events = listOf(sysMsg, userMsg) + nonEssential,
            unhandledCondensationRequest = true
        )

        // Call condense() (the base class method), not getCondensation() directly
        val result = condenser.condense(contextOf(view))

        assertTrue(result is Condensation, "Should return Condensation when request is unhandled")
        val condensation = result as Condensation
        assertTrue(condensation.action.forgotten.isNotEmpty())
        assertNull(condensation.action.summary, "ConversationWindowCondenser never produces a summary")
    }

    @Test
    fun `full condense call without request returns CondenserView`() {
        val sysMsg = SystemMessageAction(content = "system", id = 1)
        val userMsg = MessageAction(content = "task", id = 2, source = EventSource.USER)
        val nonEssential = (3..10).map {
            MessageAction(content = "msg-$it", id = it, source = EventSource.AGENT)
        }

        val view = View(
            events = listOf(sysMsg, userMsg) + nonEssential,
            unhandledCondensationRequest = false
        )

        val result = condenser.condense(contextOf(view))

        assertTrue(result is CondenserView, "Should pass through when no request")
    }

    // -----------------------------------------------------------------------
    // Edge cases
    // -----------------------------------------------------------------------

    @Test
    fun `getCondensation with single non-essential event keeps it`() {
        val sysMsg = SystemMessageAction(content = "system", id = 1)
        val userMsg = MessageAction(content = "task", id = 2, source = EventSource.USER)
        val agentMsg = MessageAction(content = "response", id = 3, source = EventSource.AGENT)

        val view = View(
            events = listOf(sysMsg, userMsg, agentMsg),
            unhandledCondensationRequest = true
        )

        val result = condenser.getCondensation(contextOf(view))

        // numNonEssential = 1, numRecentToKeep = max(1, 1/2) = max(1, 0) = 1
        // All kept, nothing to forget
        assertTrue(result.action.forgotten.isEmpty())
    }

    @Test
    fun `getCondensation with all observations in slice falls back to sliceStartIndex`() {
        val sysMsg = SystemMessageAction(content = "system", id = 1)
        val userMsg = MessageAction(content = "task", id = 2, source = EventSource.USER)
        // First two are non-essential actions, last two are observations
        val events = listOf(
            sysMsg,
            userMsg,
            MessageAction(content = "a", id = 3, source = EventSource.AGENT),
            MessageAction(content = "b", id = 4, source = EventSource.AGENT),
            ToolResultObservation(toolCallId = "t1", content = "r1", isError = false, toolName = "read", id = 5),
            ToolResultObservation(toolCallId = "t2", content = "r2", isError = false, toolName = "edit", id = 6),
        )

        val view = View(events = events, unhandledCondensationRequest = true)
        val result = condenser.getCondensation(contextOf(view))
        val forgotten = result.action.forgotten.toSet()

        // 2 essential + 4 non-essential = 6
        // numRecentToKeep = max(1, 4/2) = 2
        // sliceStartIndex = 6 - 2 = 4 → events[4] = id=5 (Observation), events[5] = id=6 (Observation)
        // All are Observations → firstValidEventIndex goes past end → falls back to sliceStartIndex=4
        // Keep: essentials (1,2) + from index 4 onward (5,6)
        // Forget: 3,4
        assertEquals(setOf(3, 4), forgotten)
    }

    @Test
    fun `getCondensation produces valid CondensationAction`() {
        val sysMsg = SystemMessageAction(content = "system", id = 1)
        val userMsg = MessageAction(content = "task", id = 2, source = EventSource.USER)
        val nonEssential = (3..8).map {
            MessageAction(content = "msg-$it", id = it, source = EventSource.AGENT)
        }

        val view = View(
            events = listOf(sysMsg, userMsg) + nonEssential,
            unhandledCondensationRequest = true
        )

        val result = condenser.getCondensation(contextOf(view))
        // CondensationAction.validate() should not throw
        assertDoesNotThrow { result.action.validate() }
    }
}
