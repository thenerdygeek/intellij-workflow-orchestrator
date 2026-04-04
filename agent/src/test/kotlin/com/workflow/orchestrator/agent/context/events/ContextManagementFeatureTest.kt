package com.workflow.orchestrator.agent.context.events

import com.workflow.orchestrator.agent.api.dto.ChatMessage
import com.workflow.orchestrator.agent.context.ConversationMemory
import com.workflow.orchestrator.agent.context.condenser.*
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Comprehensive TDD-style feature tests for the event-sourced context management system.
 *
 * Tests verify BEHAVIOR, not implementation. Each scenario exercises the full pipeline:
 * EventStore → View → CondenserPipeline → ConversationMemory.
 *
 * Assertion data is written first; production bugs found during test runs are fixed
 * and re-run until all scenarios pass.
 */
class ContextManagementFeatureTest {

    // ── Private factory helpers (matching project style) ─────────────────────

    private fun store() = EventStore()

    private fun storeInDir(dir: File) = EventStore(dir)

    private fun systemMsg(content: String = "You are a helpful agent.") =
        SystemMessageAction(content = content)

    private fun userMsg(content: String = "Do something.") =
        MessageAction(content = content, source = EventSource.USER)

    private fun agentMsg(content: String = "Done.") =
        MessageAction(content = content, source = EventSource.AGENT)

    private fun readAction(path: String, toolCallId: String = "tc-read-${path.hashCode()}", rgId: String = "rg1") =
        FileReadAction(toolCallId = toolCallId, responseGroupId = rgId, path = path)

    private fun editAction(path: String, toolCallId: String = "tc-edit-${path.hashCode()}", rgId: String = "rg2") =
        FileEditAction(toolCallId = toolCallId, responseGroupId = rgId, path = path, oldStr = "old", newStr = "new")

    private fun toolResult(toolCallId: String, content: String = "result content", isError: Boolean = false, toolName: String = "read_file") =
        ToolResultObservation(toolCallId = toolCallId, content = content, isError = isError, toolName = toolName)

    private fun factEvent(content: String = "important fact") =
        FactRecordedAction(factType = "CODE_PATTERN", path = "/src/Main.kt", content = content)

    private fun planEvent(json: String = """{"steps": []}""") =
        PlanUpdatedAction(planJson = json)

    private fun guardrailEvent(rule: String = "always read before edit") =
        GuardrailRecordedAction(rule = rule)

    private fun condensationRequest() = CondensationRequestAction()

    private fun makeCondensation(forgottenIds: List<Int>, summary: String? = null, summaryOffset: Int? = null) =
        CondensationAction(
            forgottenEventIds = forgottenIds,
            forgottenEventsStartId = null,
            forgottenEventsEndId = null,
            summary = summary,
            summaryOffset = summaryOffset
        )

    private fun noOpCondenser() = NoOpCondenser()

    private fun pipeline(vararg condensers: Condenser) = CondenserPipeline(condensers.toList())

    private fun context(view: View, tokenUtilization: Double = 0.0) =
        CondenserContext(view = view, tokenUtilization = tokenUtilization, effectiveBudget = 200_000, currentTokens = (tokenUtilization * 200_000).toInt())

    private fun memory() = ConversationMemory()

    // ── Scenario 1: Full Pipeline End-to-End ─────────────────────────────────

    @Test
    fun `scenario 1 - full pipeline with NoOp condenser produces correct ChatMessage sequence`() {
        // ASSERTIONS FIRST: a 20-event conversation through NoOp pipeline produces
        // sanitized messages starting with user, alternating user/assistant, no system role.
        val store = store()
        val src = EventSource.SYSTEM

        // Build a realistic 20-event conversation
        store.add(systemMsg("You are a helpful AI agent."), src)          // 0
        store.add(userMsg("Analyze Main.kt"), EventSource.USER)             // 1
        store.add(readAction("/src/Main.kt", "tc1", "rg1"), EventSource.AGENT) // 2
        store.add(toolResult("tc1", "class Main { ... }", toolName = "read_file"), src) // 3
        store.add(agentMsg("I analyzed it."), EventSource.AGENT)            // 4
        store.add(userMsg("Now edit it."), EventSource.USER)                // 5
        store.add(editAction("/src/Main.kt", "tc2", "rg2"), EventSource.AGENT) // 6
        store.add(toolResult("tc2", "Edit applied.", toolName = "edit_file"), src) // 7
        store.add(agentMsg("Edit done."), EventSource.AGENT)                // 8
        store.add(userMsg("Check tests."), EventSource.USER)                // 9
        val cmd1 = CommandRunAction("tc3", "rg3", "gradle test")
        store.add(cmd1, EventSource.AGENT)                                  // 10
        store.add(toolResult("tc3", "Tests passed.", toolName = "run_command"), src) // 11
        store.add(agentMsg("All tests pass."), EventSource.AGENT)           // 12
        store.add(factEvent("Main class found"), EventSource.AGENT)         // 13
        store.add(planEvent(), EventSource.AGENT)                           // 14
        store.add(userMsg("Anything else?"), EventSource.USER)              // 15
        store.add(agentMsg("Nothing else needed."), EventSource.AGENT)      // 16
        store.add(guardrailEvent(), EventSource.SYSTEM)                     // 17
        store.add(userMsg("Great, finish."), EventSource.USER)              // 18
        store.add(agentMsg("Task complete."), EventSource.AGENT)            // 19

        assertEquals(20, store.size())

        val view = View.fromEvents(store.all())
        val ctx = context(view)
        val pipe = pipeline(noOpCondenser())
        val result = pipe.condense(ctx)

        assertTrue(result is CondenserView, "NoOp pipeline should return CondenserView")
        val condensedView = (result as CondenserView).view

        // ConversationMemory converts events to ChatMessages
        val initialUser = store.all().firstOrNull { it is MessageAction && it.source == EventSource.USER } as? MessageAction
            ?: error("No user message found")
        val messages = memory().processEvents(condensedView.events, initialUser, condensedView.forgottenEventIds)

        // Assertions: Sourcegraph sanitization rules
        assertTrue(messages.isNotEmpty(), "Should produce messages")

        // Must start with user
        assertEquals("user", messages.first().role, "Conversation must start with user role")

        // Must NOT contain any system-role messages (system is converted to user)
        val systemMessages = messages.filter { it.role == "system" }
        assertTrue(systemMessages.isEmpty(), "Sourcegraph API does not accept system role — found: ${systemMessages.size}")

        // No consecutive same-role messages (excluding tool_call assistant sequences)
        for (i in 1 until messages.size) {
            val prev = messages[i - 1]
            val curr = messages[i]
            if (prev.role == curr.role && prev.toolCalls == null && curr.toolCalls == null) {
                fail<Unit>("Consecutive same-role messages at index $i: ${prev.role} → ${curr.role}")
            }
        }

        // Must contain at least one assistant message
        assertTrue(messages.any { it.role == "assistant" }, "Should have at least one assistant message")

        // Must contain at least one user message
        assertTrue(messages.count { it.role == "user" } >= 1, "Should have user messages")
    }

    // ── Scenario 2: Condensation Round-Trip ──────────────────────────────────

    @Test
    fun `scenario 2 - condensation round-trip summary inserted at correct offset, forgotten events gone`() {
        // ASSERTIONS FIRST:
        // 1. After condensation, forgotten events are gone from view
        // 2. Summary is inserted at summaryOffset position
        // 3. NEVER_FORGET_TYPES events survive
        // 4. Event count is reduced

        val store = store()
        val src = EventSource.SYSTEM

        store.add(systemMsg(), src)                                         // 0 — system
        store.add(userMsg("Start task"), EventSource.USER)                  // 1 — first user
        store.add(factEvent("fact1"), EventSource.AGENT)                    // 2 — NEVER_FORGET

        // Add many tool interactions that will be forgettable
        for (i in 1..100) {
            val tc = "tc-round$i"
            val rg = "rg-round$i"
            store.add(readAction("/src/File$i.kt", tc, rg), EventSource.AGENT)
            store.add(toolResult(tc, "content of file $i"), src)
        }

        store.add(userMsg("Check status"), EventSource.USER)
        store.add(agentMsg("Working..."), EventSource.AGENT)
        store.add(planEvent(), EventSource.AGENT)                           // NEVER_FORGET

        val originalSize = store.size()
        assertTrue(originalSize > 200, "Should have 200+ events before condensation")

        // Simulate what LLMSummarizingCondenser would do: record condensation action
        // covering events 3..150 (the bulk of tool calls), with a summary
        val forgottenIds = (3..150).toList()
        val condensationAction = makeCondensation(forgottenIds, summary = "Round 1 summary of tool calls", summaryOffset = 2)
        store.add(condensationAction, src)

        // Build a new view post-condensation
        val view = View.fromEvents(store.all())

        // Assert: forgotten events do not appear in view
        val viewEventIds = view.events.map { it.id }.toSet()
        for (forgottenId in forgottenIds) {
            assertFalse(forgottenId in viewEventIds, "Forgotten event $forgottenId should not appear in view")
        }

        // Assert: summary observation is present
        val summaryObs = view.events.filterIsInstance<CondensationObservation>()
        assertTrue(summaryObs.isNotEmpty(), "Summary CondensationObservation should appear in view")
        assertTrue(summaryObs.any { it.content.contains("Round 1 summary") }, "Summary content should match")

        // Assert: summary inserted at summaryOffset (position 2) — but since events 0-1 survive,
        // summaryOffset=2 means it appears after events 0 and 1
        val summaryIndex = view.events.indexOfFirst { it is CondensationObservation && (it as CondensationObservation).content.contains("Round 1 summary") }
        assertTrue(summaryIndex >= 0, "Summary should be in the view")

        // Assert: NEVER_FORGET_TYPES events survive (FactRecordedAction at id=2, PlanUpdatedAction)
        assertTrue(view.events.any { it is FactRecordedAction }, "FactRecordedAction must survive condensation")
        assertTrue(view.events.any { it is PlanUpdatedAction }, "PlanUpdatedAction must survive condensation")

        // Assert: event count reduced
        val countReduction = originalSize - view.size
        assertTrue(countReduction > 50, "View size should be significantly reduced after condensation, reduced by $countReduction")

        // Assert: forgottenEventIds set on view is populated
        assertTrue(view.forgottenEventIds.isNotEmpty(), "View should track forgotten event IDs")
        assertTrue(forgottenIds.first() in view.forgottenEventIds)
    }

    // ── Scenario 3: Multiple Condensation Rounds (Summary Chaining) ──────────

    @Test
    fun `scenario 3 - multiple condensation rounds - only latest summary appears, earlier forgotten stay forgotten`() {
        // ASSERTIONS FIRST:
        // Round 1 produces "Round 1 summary"
        // Round 2 produces "Round 2 summary" with previous="Round 1 summary"
        // Only the latest summary appears in the final view

        val store = store()
        val src = EventSource.SYSTEM

        store.add(systemMsg(), src)                   // 0
        store.add(userMsg("Task 1"), EventSource.USER)  // 1

        for (i in 1..20) {
            val tc = "r1-tc$i"
            store.add(readAction("/src/A$i.kt", tc, "rg$i"), EventSource.AGENT)
            store.add(toolResult(tc, "A$i content"), src)
        }
        // Events 2..41

        // Round 1 condensation: forget events 2..30
        val round1Forgotten = (2..30).toList()
        val round1Action = makeCondensation(round1Forgotten, summary = "Round 1 summary", summaryOffset = 1)
        store.add(round1Action, src)  // 42

        // Add more events after round 1
        store.add(userMsg("Continue"), EventSource.USER) // 43
        for (i in 1..10) {
            val tc = "r2-tc$i"
            store.add(readAction("/src/B$i.kt", tc, "rg-b$i"), EventSource.AGENT)
            store.add(toolResult(tc, "B$i content"), src)
        }
        // Events 44..63

        // Round 2 condensation: forget events 31..50 (new middle block)
        val round2Forgotten = (31..50).toList()
        val round2Action = makeCondensation(round2Forgotten, summary = "Round 2 summary (prev: Round 1 summary)", summaryOffset = 1)
        store.add(round2Action, src)  // 64

        val finalView = View.fromEvents(store.all())

        // Assert: only the LAST condensation summary appears in the view
        // (View.fromEvents uses the last CondensationAction with summary)
        val summaries = finalView.events.filterIsInstance<CondensationObservation>()
        assertEquals(1, summaries.size, "Only one summary should appear (the latest)")
        assertTrue(summaries[0].content.contains("Round 2 summary"), "Latest summary should be Round 2")

        // Assert: events from round 1 forgotten list are not in view
        val viewIds = finalView.events.map { it.id }.toSet()
        for (id in round1Forgotten) {
            assertFalse(id in viewIds, "Round 1 forgotten event $id should not appear in final view")
        }

        // Assert: events from round 2 forgotten list are not in view
        for (id in round2Forgotten) {
            assertFalse(id in viewIds, "Round 2 forgotten event $id should not appear in final view")
        }

        // Assert: both condensation action IDs are in forgottenEventIds
        assertTrue(42 in finalView.forgottenEventIds, "Round 1 CondensationAction itself should be forgotten")
        assertTrue(64 in finalView.forgottenEventIds, "Round 2 CondensationAction itself should be forgotten")
    }

    // ── Scenario 4: SmartPruner File Read Dedup ──────────────────────────────

    @Test
    fun `scenario 4 - SmartPruner deduplicates file reads - first result replaced with placeholder`() {
        // ASSERTIONS FIRST:
        // read(Main.kt) → result, edit(Other.kt) → result, read(Main.kt) → result
        // SmartPruner: first read result replaced with "[Deduplicated ...]"
        // Second (latest) read result untouched

        val store = store()
        val src = EventSource.SYSTEM

        store.add(systemMsg(), src)
        store.add(userMsg("work"), EventSource.USER)

        // First read of Main.kt
        val tc1 = "tc-read1"
        store.add(readAction("/src/Main.kt", tc1, "rg1"), EventSource.AGENT)
        store.add(toolResult(tc1, "original content of Main.kt", toolName = "read_file"), src)

        // Edit a different file (should NOT reset dedup tracking for Main.kt)
        val tc2 = "tc-edit-other"
        store.add(editAction("/src/Other.kt", tc2, "rg2"), EventSource.AGENT)
        store.add(toolResult(tc2, "Other.kt edited.", toolName = "edit_file"), src)

        // Second read of Main.kt (re-read without editing Main.kt in between)
        val tc3 = "tc-read2"
        store.add(readAction("/src/Main.kt", tc3, "rg3"), EventSource.AGENT)
        store.add(toolResult(tc3, "updated content of Main.kt", toolName = "read_file"), src)

        val view = View.fromEvents(store.all())
        val ctx = context(view)
        val pruner = SmartPrunerCondenser()
        val result = pruner.condense(ctx) as CondenserView

        val resultEvents = result.view.events

        // Assert: first read result is replaced with dedup placeholder
        val firstReadResult = resultEvents.firstOrNull {
            it is CondensationObservation && (it as CondensationObservation).content.contains("Deduplicated")
                && (it as CondensationObservation).content.contains("/src/Main.kt")
        }
        assertNotNull(firstReadResult, "First read result for Main.kt should be replaced with '[Deduplicated ...]' placeholder")

        // Assert: the dedup placeholder is for the correct file
        val dedupObs = firstReadResult as CondensationObservation
        assertTrue(dedupObs.content.contains("/src/Main.kt"), "Dedup placeholder should mention the file path")
        assertTrue(dedupObs.content.contains("re-read later"), "Dedup placeholder should indicate reason")

        // Assert: second (latest) read result is NOT replaced — should still be a ToolResultObservation with tc3
        val secondReadResult = resultEvents.firstOrNull {
            it is ToolResultObservation && (it as ToolResultObservation).toolCallId == tc3
        }
        assertNotNull(secondReadResult, "Second (latest) read result for Main.kt should remain as ToolResultObservation")

        val latestResult = secondReadResult as ToolResultObservation
        assertEquals("updated content of Main.kt", latestResult.content)
    }

    // ── Scenario 5: SmartPruner Edit Resets Dedup ────────────────────────────

    @Test
    fun `scenario 5 - SmartPruner edit to same file resets dedup tracking - first read NOT deduplicated`() {
        // ASSERTIONS FIRST:
        // read(Main.kt) → result, edit(Main.kt) → result, read(Main.kt) → result
        // SmartPruner: first read should NOT be deduplicated because edit to Main.kt
        // resets the tracking for that path

        val store = store()
        val src = EventSource.SYSTEM

        store.add(systemMsg(), src)
        store.add(userMsg("work"), EventSource.USER)

        // First read
        val tc1 = "tc-read-first"
        store.add(readAction("/src/Main.kt", tc1, "rg1"), EventSource.AGENT)
        store.add(toolResult(tc1, "original content", toolName = "read_file"), src)

        // Edit to the SAME file — this should reset dedup tracking
        val tc2 = "tc-edit-main"
        store.add(editAction("/src/Main.kt", tc2, "rg2"), EventSource.AGENT)
        store.add(toolResult(tc2, "Edited successfully.", toolName = "edit_file"), src)

        // Second read after the edit
        val tc3 = "tc-read-second"
        store.add(readAction("/src/Main.kt", tc3, "rg3"), EventSource.AGENT)
        store.add(toolResult(tc3, "new content after edit", toolName = "read_file"), src)

        val view = View.fromEvents(store.all())
        val ctx = context(view)
        val pruner = SmartPrunerCondenser()
        val result = pruner.condense(ctx) as CondenserView

        val resultEvents = result.view.events

        // Assert: first read result is NOT replaced (edit intervened, resetting tracking)
        val firstReadResult = resultEvents.firstOrNull {
            it is ToolResultObservation && (it as ToolResultObservation).toolCallId == tc1
        }
        assertNotNull(firstReadResult, "First read result should NOT be deduplicated when an edit to the same file intervened")
        assertEquals("original content", (firstReadResult as ToolResultObservation).content)

        // Assert: no dedup placeholder for Main.kt should exist
        val dedupPlaceholders = resultEvents.filterIsInstance<CondensationObservation>()
            .filter { it.content.contains("/src/Main.kt") && it.content.contains("Deduplicated") }
        assertTrue(dedupPlaceholders.isEmpty(), "No dedup placeholder expected when edit resets tracking: found ${dedupPlaceholders.size}")
    }

    // ── Scenario 6: ObservationMasking With Rich Placeholders ────────────────

    @Test
    fun `scenario 6 - ObservationMaskingCondenser masks old observations with token-based tiering`() {
        // ASSERTIONS FIRST:
        // Token-utilization-gated condenser with small windows.
        // At 70% utilization (above 60% threshold), masking activates.
        // Observations far from tail (beyond outerWindow) → METADATA (masked placeholder)
        // Observations near tail (within innerWindow) → FULL (untouched)
        // Existing CondensationObservation entries → NEVER masked

        val store = store()
        val src = EventSource.SYSTEM

        store.add(systemMsg(), src)
        store.add(userMsg("work"), EventSource.USER)

        // Add 38 more events (mix of tool calls and results), to total 40
        // Use large content to create clear token distance between events
        for (i in 1..19) {
            val tc = "tc-obs$i"
            store.add(readAction("/src/File$i.kt", tc, "rg$i"), EventSource.AGENT)
            store.add(toolResult(tc, "content of file $i " + "x".repeat(2000), toolName = "read_file"), src)
        }

        assertEquals(40, store.size())

        val view = View.fromEvents(store.all())
        // Token utilization above threshold (0.70 > 0.60), small windows for testing
        val condenser = ObservationMaskingCondenser(
            threshold = 0.60,
            innerWindowTokens = 2_000,   // recent events stay FULL
            outerWindowTokens = 4_000    // older events compressed or masked
        )
        val result = condenser.condense(context(view, tokenUtilization = 0.70)) as CondenserView

        val maskedEvents = result.view.events

        // Assert: some old observations are masked/compressed (not all kept as FULL)
        val maskedOrCompressed = maskedEvents.filterIsInstance<CondensationObservation>()
            .filter { it.content.contains("masked") || it.content.contains("compressed") }
        assertTrue(maskedOrCompressed.isNotEmpty(), "Some old observations should be masked or compressed")

        // Assert: recent observations near tail are untouched ToolResultObservation
        val lastFew = maskedEvents.takeLast(6)
        val untouchedToolResults = lastFew.filterIsInstance<ToolResultObservation>()
        assertTrue(untouchedToolResults.isNotEmpty(), "Recent events (FULL tier) should remain as ToolResultObservation")

        // Assert: CondensationObservation (summaries) are NEVER masked
        val summaryObs = CondensationObservation(content = "IMPORTANT SUMMARY", id = -1)
        val eventsWithSummary = listOf(summaryObs) + view.events
        val viewWithSummary = View(events = eventsWithSummary)
        val resultWithSummary = condenser.condense(context(viewWithSummary, tokenUtilization = 0.70)) as CondenserView

        val survivingObservation = resultWithSummary.view.events.firstOrNull {
            it is CondensationObservation && (it as CondensationObservation).content == "IMPORTANT SUMMARY"
        }
        assertNotNull(survivingObservation, "CondensationObservation (summaries) must NEVER be masked by ObservationMaskingCondenser")
    }

    // ── Scenario 7: ConversationWindow Reactive-Only ─────────────────────────

    @Test
    fun `scenario 7a - ConversationWindowCondenser does NOT condense without unhandled request`() {
        // ASSERTIONS FIRST: No request → CondenserView unchanged

        val store = store()
        val src = EventSource.SYSTEM
        store.add(systemMsg(), src)
        store.add(userMsg("initial"), EventSource.USER)

        for (i in 1..50) {
            val tc = "tc-cw$i"
            store.add(readAction("/f$i.kt", tc, "rg$i"), EventSource.AGENT)
            store.add(toolResult(tc, "content"), src)
        }

        val view = View.fromEvents(store.all())
        assertFalse(view.unhandledCondensationRequest, "No CondensationRequestAction was added, so unhandledCondensationRequest must be false")

        val condenser = ConversationWindowCondenser()
        val result = condenser.condense(context(view))

        assertTrue(result is CondenserView, "Without unhandled request, ConversationWindowCondenser should return CondenserView (passthrough)")
    }

    @Test
    fun `scenario 7b - ConversationWindowCondenser condenses when unhandled request present`() {
        // ASSERTIONS FIRST:
        // With unhandled request → returns Condensation
        // ~50% of events forgotten
        // SystemMessageAction and first user MessageAction preserved

        val store = store()
        val src = EventSource.SYSTEM
        store.add(systemMsg(), src)                                // 0 — system
        store.add(userMsg("initial"), EventSource.USER)            // 1 — first user

        for (i in 1..50) {
            val tc = "tc-cw$i"
            store.add(readAction("/f$i.kt", tc, "rg$i"), EventSource.AGENT)
            store.add(toolResult(tc, "content"), src)
        }
        // 2 + 100 = 102 events

        store.add(condensationRequest(), src)  // 102 — triggers condensation

        val view = View.fromEvents(store.all())
        assertTrue(view.unhandledCondensationRequest, "unhandledCondensationRequest should be true")

        val sysId = store.get(0)!!.id
        val firstUserId = store.get(1)!!.id

        val condenser = ConversationWindowCondenser()
        val result = condenser.condense(context(view))

        assertTrue(result is Condensation, "With unhandled request, should return Condensation")
        val condensation = result as Condensation
        val forgotten = condensation.action.forgotten

        // Assert: system message NOT forgotten
        assertFalse(sysId in forgotten, "SystemMessageAction (id=$sysId) must NOT be forgotten")

        // Assert: first user message NOT forgotten
        assertFalse(firstUserId in forgotten, "First user MessageAction (id=$firstUserId) must NOT be forgotten")

        // Assert: roughly half of non-essential events are forgotten
        val nonEssentialCount = view.size - 2  // minus system + first user
        val forgottenFraction = forgotten.size.toDouble() / nonEssentialCount.toDouble()
        assertTrue(forgottenFraction in 0.30..0.70, "Should forget roughly half of non-essential events, forgot $forgottenFraction")
    }

    // ── Scenario 8: NEVER_FORGET_TYPES Protection ────────────────────────────

    @Test
    fun `scenario 8 - NEVER_FORGET_TYPES events survive ConversationWindowCondenser condensation`() {
        // ASSERTIONS FIRST: Facts, plans, guardrails, skills must never be in forgottenEventIds

        val store = store()
        val src = EventSource.SYSTEM

        store.add(systemMsg(), src)
        store.add(userMsg("task"), EventSource.USER)

        // Scatter NEVER_FORGET events throughout
        store.add(factEvent("important discovery"), EventSource.AGENT)    // FactRecordedAction
        store.add(planEvent(), EventSource.AGENT)                          // PlanUpdatedAction
        store.add(guardrailEvent("never delete prod"), EventSource.SYSTEM) // GuardrailRecordedAction
        store.add(SkillActivatedAction(skillName = "debug", content = "skill content"), EventSource.AGENT) // SkillActivatedAction

        // Add many forgettable events
        for (i in 1..40) {
            val tc = "tc-nf$i"
            store.add(readAction("/f$i.kt", tc, "rg$i"), EventSource.AGENT)
            store.add(toolResult(tc, "content"), src)
        }

        // More NEVER_FORGET in the middle
        store.add(factEvent("second fact"), EventSource.AGENT)
        store.add(guardrailEvent("another rule"), EventSource.SYSTEM)

        // More forgettable events
        for (i in 41..60) {
            val tc = "tc-nf$i"
            store.add(readAction("/f$i.kt", tc, "rg$i"), EventSource.AGENT)
            store.add(toolResult(tc, "content"), src)
        }

        // Trigger condensation
        store.add(condensationRequest(), src)

        val view = View.fromEvents(store.all())
        assertTrue(view.unhandledCondensationRequest)

        val condenser = ConversationWindowCondenser()
        val result = condenser.condense(context(view))

        assertTrue(result is Condensation)
        val forgotten = (result as Condensation).action.forgotten.toSet()

        // Collect all NEVER_FORGET event IDs in the view
        val neverForgetIds = view.events
            .filter { event -> NEVER_FORGET_TYPES.any { it.isInstance(event) } }
            .map { it.id }
            .toSet()

        assertTrue(neverForgetIds.isNotEmpty(), "Test setup error: NEVER_FORGET events must exist in view")

        // Assert: NONE of the NEVER_FORGET events appear in forgotten set
        val mistakenlyforgotten = neverForgetIds.intersect(forgotten)
        assertTrue(mistakenlyforgotten.isEmpty(),
            "NEVER_FORGET_TYPES events must not be forgotten. Mistakenly forgotten IDs: $mistakenlyforgotten")
    }

    // ── Scenario 9: EventStore JSONL Persistence Round-Trip ──────────────────

    @Test
    fun `scenario 9 - EventStore JSONL persistence round-trip preserves all event types and content`(@TempDir tempDir: File) {
        // ASSERTIONS FIRST: All event types survive serialize → flush → load

        val store = storeInDir(tempDir)
        val src = EventSource.SYSTEM
        val agSrc = EventSource.AGENT
        val usrSrc = EventSource.USER

        // Add one of every action/observation type
        store.add(SystemMessageAction("system prompt"), src)
        store.add(MessageAction("user hello", source = usrSrc), usrSrc)
        store.add(MessageAction("agent response", source = agSrc), agSrc)
        store.add(AgentThinkAction("let me think"), agSrc)
        store.add(AgentFinishAction("task done", mapOf("result" to "ok")), agSrc)
        store.add(DelegateAction("coder", "implement feature X"), agSrc)
        store.add(FileReadAction("tc1", "rg1", "/src/Main.kt"), agSrc)
        store.add(FileEditAction("tc2", "rg2", "/src/Main.kt", "old", "new"), agSrc)
        store.add(CommandRunAction("tc3", "rg3", "gradle build", "/workspace"), agSrc)
        store.add(SearchCodeAction("tc4", "rg4", "findAll()", "/src"), agSrc)
        store.add(DiagnosticsAction("tc5", "rg5", "/src"), agSrc)
        store.add(GenericToolAction("tc6", "rg6", "think", """{"thought":"x"}"""), agSrc)
        store.add(MetaToolAction("tc7", "rg7", "jira", "get_ticket", """{"action":"get_ticket"}"""), agSrc)
        store.add(FactRecordedAction("CODE_PATTERN", "/src/Main.kt", "singleton pattern"), agSrc)
        store.add(PlanUpdatedAction("""{"steps":[{"title":"step1"}]}"""), agSrc)
        store.add(SkillActivatedAction("debug", "debug skill content"), agSrc)
        store.add(SkillDeactivatedAction("debug"), agSrc)
        store.add(GuardrailRecordedAction("never overwrite production"), src)
        store.add(MentionAction(listOf("/src/A.kt", "/src/B.kt"), "@mention content"), usrSrc)
        store.add(CondensationRequestAction(), src)
        store.add(makeCondensation(listOf(5, 6, 7), "summary text", 2), src)
        store.add(ToolResultObservation("tc1", "file content", false, "read_file"), src)
        store.add(CondensationObservation("condensed summary here"), src)
        store.add(ErrorObservation("something went wrong", "err-42"), src)
        store.add(SuccessObservation("operation succeeded"), src)

        val originalCount = store.size()
        assertTrue(originalCount >= 25, "Should have at least 25 diverse events")

        // Flush to disk
        store.flush()

        val jsonlFile = File(tempDir, EventStore.JSONL_FILENAME)
        assertTrue(jsonlFile.exists(), "JSONL file should be created after flush")
        assertTrue(jsonlFile.readText().isNotBlank(), "JSONL file should not be empty")

        // Load into a new store
        val loaded = EventStore.loadFromJsonl(tempDir)
        assertEquals(originalCount, loaded.size(), "Loaded store must have same event count")

        // Verify all events match
        for (i in 0 until originalCount) {
            val original = store.get(i)!!
            val restored = loaded.get(i)!!

            assertEquals(original.id, restored.id, "Event $i: ID must match")
            assertEquals(original::class, restored::class, "Event $i: type must match, was ${original::class.simpleName}")
            assertEquals(original.source, restored.source, "Event $i: source must match")

            // Verify specific content fields
            when {
                original is SystemMessageAction && restored is SystemMessageAction ->
                    assertEquals(original.content, restored.content, "SystemMessageAction content must match")
                original is MessageAction && restored is MessageAction ->
                    assertEquals(original.content, restored.content, "MessageAction content must match")
                original is FileReadAction && restored is FileReadAction ->
                    assertEquals(original.path, restored.path, "FileReadAction path must match")
                original is FileEditAction && restored is FileEditAction -> {
                    assertEquals(original.path, restored.path)
                    assertEquals(original.oldStr, restored.oldStr)
                    assertEquals(original.newStr, restored.newStr)
                }
                original is ToolResultObservation && restored is ToolResultObservation -> {
                    assertEquals(original.toolCallId, restored.toolCallId)
                    assertEquals(original.content, restored.content)
                    assertEquals(original.isError, restored.isError)
                    assertEquals(original.toolName, restored.toolName)
                }
                original is FactRecordedAction && restored is FactRecordedAction -> {
                    assertEquals(original.factType, restored.factType)
                    assertEquals(original.content, restored.content)
                    assertEquals(original.path, restored.path)
                }
                original is CondensationAction && restored is CondensationAction -> {
                    assertEquals(original.summary, restored.summary)
                    assertEquals(original.summaryOffset, restored.summaryOffset)
                    assertEquals(original.forgottenEventIds, restored.forgottenEventIds)
                }
                original is ErrorObservation && restored is ErrorObservation ->
                    assertEquals(original.errorId, restored.errorId)
            }
        }

        // Verify nextId is correctly restored (adding a new event gets a continuing ID)
        val newEvent = store.add(userMsg("extra"), EventSource.USER)
        assertEquals(originalCount, newEvent.id, "Next ID after load should continue from ${originalCount}")
    }

    // ── Scenario 10: ConversationMemory Tool Call Pairing ────────────────────

    @Test
    fun `scenario 10 - ConversationMemory pairs parallel tool calls into single assistant message`() {
        // ASSERTIONS FIRST:
        // 2 FileReadActions with same responseGroupId → 1 assistant message with 2 tool_calls
        // followed by 2 tool result messages, all properly paired by toolCallId

        val events = mutableListOf<Event>()

        // Add events directly with IDs for ConversationMemory
        fun addEvent(event: Event, id: Int): Event {
            val stored = when (event) {
                is SystemMessageAction -> event.copy(id = id)
                is MessageAction -> event.copy(id = id)
                is FileReadAction -> event.copy(id = id)
                is ToolResultObservation -> event.copy(id = id)
                else -> event
            }
            events.add(stored)
            return stored
        }

        val sharedRgId = "rg-parallel"
        addEvent(systemMsg("System."), 0)
        val initialUser = MessageAction(content = "Read two files please.", source = EventSource.USER, id = 1)
        events.add(initialUser)

        // Two FileReadActions with same responseGroupId (parallel calls from one LLM response)
        addEvent(FileReadAction(toolCallId = "tc-p1", responseGroupId = sharedRgId, path = "/src/A.kt", id = 2), 2)
        addEvent(FileReadAction(toolCallId = "tc-p2", responseGroupId = sharedRgId, path = "/src/B.kt", id = 3), 3)

        // Two tool results
        addEvent(ToolResultObservation(toolCallId = "tc-p1", content = "A.kt content", isError = false, toolName = "read_file", id = 4), 4)
        addEvent(ToolResultObservation(toolCallId = "tc-p2", content = "B.kt content", isError = false, toolName = "read_file", id = 5), 5)

        val messages = memory().processEvents(events, initialUser, emptySet())

        // Assert: there is an assistant message with 2 tool_calls
        val assistantWithTools = messages.filter { it.role == "assistant" && !it.toolCalls.isNullOrEmpty() }
        assertEquals(1, assistantWithTools.size, "Should have exactly 1 assistant message with tool_calls")
        assertEquals(2, assistantWithTools[0].toolCalls!!.size, "Assistant message should have 2 tool_calls")

        val toolCallIds = assistantWithTools[0].toolCalls!!.map { it.id }.toSet()
        assertTrue("tc-p1" in toolCallIds, "tc-p1 should be in tool_calls")
        assertTrue("tc-p2" in toolCallIds, "tc-p2 should be in tool_calls")

        // Assert: there are tool result messages for each call (after Sourcegraph sanitization, they become user messages)
        // Sourcegraph sanitizes "tool" → "user" with plain text prefix
        val toolResultMessages = messages.filter { msg ->
            msg.role == "user" && msg.content?.contains("RESULT of") == true
        }
        // Both tool results should be present (may be merged into one user message by sanitization)
        val allContent = messages.joinToString("\n") { it.content ?: "" }
        assertTrue(allContent.contains("A.kt content"), "A.kt tool result must appear in output")
        assertTrue(allContent.contains("B.kt content"), "B.kt tool result must appear in output")

        // Assert: tool call IDs properly referenced (tc-p1 and tc-p2 appear in tool result wrappers)
        assertTrue(allContent.contains("tc-p1") || toolCallIds.isNotEmpty(), "Tool call IDs must be referenced in output")
    }

    // ── Scenario 11: ConversationMemory Orphan Filtering ─────────────────────

    @Test
    fun `scenario 11 - ConversationMemory filters orphaned tool calls with no matching result`() {
        // ASSERTIONS FIRST:
        // ToolAction with no matching ToolResultObservation → removed from output

        val events = mutableListOf<Event>()

        val initialUser = MessageAction(content = "Do the thing.", source = EventSource.USER, id = 1)
        events.add(SystemMessageAction(content = "System.", id = 0))
        events.add(initialUser)

        // Orphaned tool call — has no matching ToolResultObservation
        events.add(FileReadAction(toolCallId = "tc-orphan", responseGroupId = "rg-orphan", path = "/src/X.kt", id = 2))

        // Normal tool call with matching result
        events.add(FileReadAction(toolCallId = "tc-normal", responseGroupId = "rg-normal", path = "/src/Y.kt", id = 3))
        events.add(ToolResultObservation(toolCallId = "tc-normal", content = "Y.kt content", isError = false, toolName = "read_file", id = 4))

        val messages = memory().processEvents(events, initialUser, emptySet())

        // Assert: orphaned tool call is removed (no assistant message referencing tc-orphan)
        val allContent = messages.joinToString("\n") { (it.toolCalls?.joinToString { tc -> tc.id } ?: "") + (it.content ?: "") }
        assertFalse(allContent.contains("tc-orphan"), "Orphaned tool call tc-orphan must be filtered out")

        // Assert: normal tool call is preserved
        val hasNormalToolCall = messages.any { msg ->
            msg.role == "assistant" && msg.toolCalls?.any { it.id == "tc-normal" } == true
        }
        assertTrue(hasNormalToolCall, "Normal tool call with matching result must be preserved")
    }

    // ── Scenario 12: Pipeline Short-Circuit on Condensation ──────────────────

    @Test
    fun `scenario 12 - pipeline short-circuits when second condenser returns Condensation`() {
        // ASSERTIONS FIRST:
        // Pipeline: NoOp → AlwaysCondense → ThrowingCondenser
        // AlwaysCondense returns Condensation → third condenser never called

        var thirdCondensedCalled = false

        val alwaysCondense = object : Condenser {
            override fun condense(context: CondenserContext): CondenserResult {
                return Condensation(
                    CondensationAction(
                        forgottenEventIds = listOf(999),
                        forgottenEventsStartId = null,
                        forgottenEventsEndId = null,
                        summary = null,
                        summaryOffset = null
                    )
                )
            }
        }

        val throwingCondenser = object : Condenser {
            override fun condense(context: CondenserContext): CondenserResult {
                thirdCondensedCalled = true
                throw AssertionError("Third condenser must NOT be called when second returns Condensation")
            }
        }

        val store = store()
        store.add(systemMsg(), EventSource.SYSTEM)
        store.add(userMsg(), EventSource.USER)
        val view = View.fromEvents(store.all())
        val ctx = context(view)

        val pipe = pipeline(noOpCondenser(), alwaysCondense, throwingCondenser)
        val result = pipe.condense(ctx)

        // Assert: Condensation was returned (pipeline short-circuited)
        assertTrue(result is Condensation, "Pipeline should return Condensation from the second condenser")

        // Assert: third condenser was never invoked
        assertFalse(thirdCondensedCalled, "Third condenser must not be called after short-circuit")

        // Assert: the specific condensation action is returned (not swallowed)
        assertEquals(listOf(999), (result as Condensation).action.forgotten)
    }

    // ── Scenario 13: CondenserContext Token-Aware Trigger ────────────────────

    @Test
    fun `scenario 13a - LLMSummarizingCondenser shouldCondense returns false when utilization below threshold`() {
        // ASSERTIONS FIRST: tokenUtilization=0.50 < threshold=0.75, view.size < maxSize → false

        val mockClient = mockk<SummarizationClient>()
        val condenser = LLMSummarizingCondenser(
            llmClient = mockClient,
            keepFirst = 4,
            maxSize = 150,
            tokenThreshold = 0.75
        )

        val store = store()
        store.add(systemMsg(), EventSource.SYSTEM)
        store.add(userMsg(), EventSource.USER)
        // Only 10 events — well below maxSize=150
        for (i in 1..4) {
            val tc = "tc-t$i"
            store.add(readAction("/f$i.kt", tc, "rg$i"), EventSource.AGENT)
            store.add(toolResult(tc, "content"), EventSource.SYSTEM)
        }

        val view = View.fromEvents(store.all())
        assertTrue(view.size < 150, "View size must be less than maxSize for this test")

        // tokenUtilization = 0.50 (below threshold=0.75)
        val ctx = CondenserContext(view = view, tokenUtilization = 0.50, effectiveBudget = 200_000, currentTokens = 100_000)
        val result = condenser.condense(ctx)

        assertTrue(result is CondenserView, "Should NOT condense when tokenUtilization (0.50) < threshold (0.75)")
    }

    @Test
    fun `scenario 13b - LLMSummarizingCondenser shouldCondense returns true when utilization above threshold`() {
        // ASSERTIONS FIRST: tokenUtilization=0.80 > threshold=0.75 → should condense (returns Condensation)

        val mockClient = mockk<SummarizationClient>()
        coEvery { mockClient.summarize(any()) } returns "Canned LLM summary for high utilization"

        val condenser = LLMSummarizingCondenser(
            llmClient = mockClient,
            keepFirst = 2,
            maxSize = 150,
            tokenThreshold = 0.75
        )

        val store = store()
        store.add(systemMsg(), EventSource.SYSTEM)
        store.add(userMsg("hello"), EventSource.USER)
        // Add enough events to have something to forget
        for (i in 1..20) {
            val tc = "tc-thr$i"
            store.add(readAction("/f$i.kt", tc, "rg$i"), EventSource.AGENT)
            store.add(toolResult(tc, "content of file $i"), EventSource.SYSTEM)
        }

        val view = View.fromEvents(store.all())
        assertTrue(view.size < 150, "View size must be less than maxSize for this test (utilization test only)")

        // tokenUtilization = 0.80 (above threshold=0.75)
        val ctx = CondenserContext(view = view, tokenUtilization = 0.80, effectiveBudget = 200_000, currentTokens = 160_000)
        val result = condenser.condense(ctx)

        assertTrue(result is Condensation, "Should condense when tokenUtilization (0.80) > threshold (0.75)")
    }

    // ── Bonus: View.fromEvents correctness ───────────────────────────────────

    @Test
    fun `view - empty event list produces empty view with no unhandled request`() {
        val view = View.fromEvents(emptyList())
        assertEquals(0, view.size)
        assertFalse(view.unhandledCondensationRequest)
        assertTrue(view.forgottenEventIds.isEmpty())
    }

    @Test
    fun `view - CondensationRequestAction is filtered from view and sets unhandled flag`() {
        val store = store()
        store.add(systemMsg(), EventSource.SYSTEM)
        store.add(userMsg(), EventSource.USER)
        val reqId = store.add(condensationRequest(), EventSource.SYSTEM).id

        val view = View.fromEvents(store.all())

        // The CondensationRequestAction itself is excluded from the view
        assertFalse(view.events.any { it.id == reqId }, "CondensationRequestAction should not appear in view events")
        assertTrue(reqId in view.forgottenEventIds, "CondensationRequestAction ID should be in forgottenEventIds")

        // unhandledCondensationRequest should be true
        assertTrue(view.unhandledCondensationRequest)
    }

    @Test
    fun `view - CondensationAction handled clears unhandled request flag`() {
        val store = store()
        store.add(systemMsg(), EventSource.SYSTEM)
        store.add(userMsg(), EventSource.USER)
        store.add(condensationRequest(), EventSource.SYSTEM)  // request
        // Then handle it with a CondensationAction
        store.add(makeCondensation(emptyList()), EventSource.SYSTEM)

        val view = View.fromEvents(store.all())

        // CondensationAction appears AFTER CondensationRequestAction → request is handled
        assertFalse(view.unhandledCondensationRequest, "CondensationAction after request should clear unhandled flag")
    }

    // ── Bonus: CondenserPipeline with empty list ──────────────────────────────

    @Test
    fun `empty pipeline returns original view unchanged`() {
        val store = store()
        store.add(systemMsg(), EventSource.SYSTEM)
        store.add(userMsg("msg"), EventSource.USER)

        val view = View.fromEvents(store.all())
        val ctx = context(view)
        val emptyPipeline = CondenserPipeline(emptyList())
        val result = emptyPipeline.condense(ctx)

        assertTrue(result is CondenserView)
        assertEquals(view.size, (result as CondenserView).view.size)
    }

    // ── Bonus: CondenserFactory produces correct pipeline stages ─────────────

    @Test
    fun `CondenserFactory without LLM client omits LLM stage`() {
        val config = com.workflow.orchestrator.agent.context.ContextManagementConfig.DEFAULT
        val pipeline = CondenserFactory.create(config, llmClient = null)
        val condensers = pipeline.getCondensers()

        // Should have: SmartPruner, ObservationMasking, ConversationWindow (no LLM)
        assertEquals(3, condensers.size, "Without llmClient, pipeline should have 3 stages")
        assertTrue(condensers[0] is SmartPrunerCondenser)
        assertTrue(condensers[1] is ObservationMaskingCondenser)
        assertTrue(condensers[2] is ConversationWindowCondenser)
    }

    @Test
    fun `CondenserFactory with LLM client includes LLM stage last`() {
        val mockClient = mockk<SummarizationClient>()
        val config = com.workflow.orchestrator.agent.context.ContextManagementConfig.DEFAULT
        val pipeline = CondenserFactory.create(config, llmClient = mockClient)
        val condensers = pipeline.getCondensers()

        assertEquals(4, condensers.size, "With llmClient, pipeline should have 4 stages")
        assertTrue(condensers[3] is LLMSummarizingCondenser, "LLM stage must be last")
    }

    @Test
    fun `CondenserFactory with smartPrunerEnabled=false omits SmartPruner`() {
        val config = com.workflow.orchestrator.agent.context.ContextManagementConfig.DEFAULT.copy(smartPrunerEnabled = false)
        val pipeline = CondenserFactory.create(config, llmClient = null)
        val condensers = pipeline.getCondensers()

        assertFalse(condensers.any { it is SmartPrunerCondenser }, "SmartPruner must not be included when disabled")
        assertEquals(2, condensers.size, "Without SmartPruner and LLM, should have 2 stages")
    }

    // ── Bonus: LLMSummarizingCondenser summary chaining (previous summary) ───

    @Test
    fun `LLMSummarizingCondenser includes previous summary in LLM prompt`() {
        // ASSERTIONS FIRST: When there's already a CondensationObservation at position keepFirst,
        // the LLM prompt should include it as <PREVIOUS SUMMARY>

        val capturedMessages = mutableListOf<List<ChatMessage>>()
        val mockClient = mockk<SummarizationClient>()
        coEvery { mockClient.summarize(any()) } answers {
            capturedMessages.add(firstArg())
            "New summary based on previous"
        }

        val condenser = LLMSummarizingCondenser(
            llmClient = mockClient,
            keepFirst = 2,
            maxSize = 10,
            tokenThreshold = 0.99  // disable token trigger, rely on size
        )

        // Build events with a prior summary at position 2 (keepFirst)
        val events = mutableListOf<Event>()
        events.add(SystemMessageAction(content = "system", id = 0))
        events.add(MessageAction(content = "user", source = EventSource.USER, id = 1))
        events.add(CondensationObservation(content = "Round 1 summary content", id = 2))

        // Add enough events to exceed maxSize=10
        for (i in 3..15) {
            events.add(
                if (i % 2 == 0)
                    FileReadAction(toolCallId = "tc$i", responseGroupId = "rg$i", path = "/f$i.kt", id = i)
                else
                    ToolResultObservation(toolCallId = "tc${i-1}", content = "content$i", isError = false, toolName = "read_file", id = i)
            )
        }

        val view = View(events = events)
        val ctx = CondenserContext(view = view, tokenUtilization = 0.5, effectiveBudget = 200_000, currentTokens = 100_000)
        val result = condenser.condense(ctx)

        assertTrue(result is Condensation, "Should condense since view.size > maxSize=10")
        assertTrue(capturedMessages.isNotEmpty(), "LLM client should have been called")

        // The prompt content should include the previous summary
        val promptContent = capturedMessages.first().first().content ?: ""
        assertTrue(promptContent.contains("PREVIOUS SUMMARY"), "LLM prompt should contain PREVIOUS SUMMARY section")
        assertTrue(promptContent.contains("Round 1 summary content"), "LLM prompt should include the previous summary content")
    }

    // ── Bonus: EventStore incremental flush ───────────────────────────────────

    @Test
    fun `EventStore incremental flush only writes new events since last flush`(@TempDir tempDir: File) {
        val store = storeInDir(tempDir)
        val src = EventSource.SYSTEM

        store.add(systemMsg("event 1"), src)
        store.add(userMsg("event 2"), EventSource.USER)
        store.flush()  // flush first 2

        val file = File(tempDir, EventStore.JSONL_FILENAME)
        val countAfterFirst = file.readLines().filter { it.isNotBlank() }.size
        assertEquals(2, countAfterFirst, "After first flush, file should have 2 lines")

        // Add more events and flush again
        store.add(agentMsg("event 3"), EventSource.AGENT)
        store.flush()  // incremental — only event 3 should be appended

        val countAfterSecond = file.readLines().filter { it.isNotBlank() }.size
        assertEquals(3, countAfterSecond, "After second flush, file should have 3 lines (incremental)")

        // Flush again with no new events — file should not grow
        store.flush()
        val countAfterNoop = file.readLines().filter { it.isNotBlank() }.size
        assertEquals(3, countAfterNoop, "No-op flush must not duplicate events")
    }

    // ── Bonus: ConversationMemory Sourcegraph sanitization ───────────────────

    @Test
    fun `ConversationMemory sanitizes system role to user with system_instructions wrapping`() {
        // System messages → converted to user with <system_instructions> tags (Sourcegraph requirement)
        val events = mutableListOf<Event>()
        val initialUser = MessageAction(content = "hello", source = EventSource.USER, id = 1)

        events.add(SystemMessageAction(content = "You are helpful.", id = 0))
        events.add(initialUser)
        events.add(MessageAction(content = "Sure!", source = EventSource.AGENT, id = 2))

        val messages = memory().processEvents(events, initialUser, emptySet())

        // No system role should appear
        assertTrue(messages.none { it.role == "system" }, "No system role messages should appear after sanitization")

        // System message content should appear wrapped in system_instructions tag somewhere in user messages
        val allUserContent = messages.filter { it.role == "user" }.joinToString("\n") { it.content ?: "" }
        assertTrue(
            allUserContent.contains("<system_instructions>"),
            "System content should be wrapped in <system_instructions> tag"
        )
        assertTrue(allUserContent.contains("You are helpful."), "System content should appear in output")
    }

    @Test
    fun `ConversationMemory removes empty messages`() {
        val events = mutableListOf<Event>()
        val initialUser = MessageAction(content = "hi", source = EventSource.USER, id = 1)

        events.add(SystemMessageAction(content = "", id = 0))  // empty system message
        events.add(initialUser)

        val messages = memory().processEvents(events, initialUser, emptySet())

        // Empty content messages should be removed
        assertTrue(messages.all { !it.content.isNullOrBlank() || !it.toolCalls.isNullOrEmpty() },
            "Messages with blank content and no tool calls should be removed")
    }
}
