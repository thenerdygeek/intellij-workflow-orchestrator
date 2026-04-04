package com.workflow.orchestrator.agent.context.events

import com.workflow.orchestrator.agent.api.dto.ChatMessage
import com.workflow.orchestrator.agent.context.ContextManagementConfig
import com.workflow.orchestrator.agent.context.ConversationMemory
import com.workflow.orchestrator.agent.context.EventSourcedContextBridge
import com.workflow.orchestrator.agent.context.condenser.*
import com.workflow.orchestrator.agent.runtime.LoopGuard
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Requirement-driven integration tests for the event-sourced context management system.
 *
 * Each test verifies a REQUIREMENT from the design spec, not implementation details.
 * Assertions are written from the requirement description; production code is only
 * consulted when a test fails.
 */
class ContextManagementRequirementsTest {

    // ── Shared factory helpers ────────────────────────────────────────────────

    private fun makeBridge(
        sessionDir: File? = null,
        config: ContextManagementConfig = ContextManagementConfig.DEFAULT,
        summarizationClient: SummarizationClient? = null
    ): EventSourcedContextBridge = EventSourcedContextBridge.create(
        sessionDir = sessionDir,
        config = config,
        summarizationClient = summarizationClient,
        maxInputTokens = 100_000
    )

    private fun makeStore(sessionDir: File? = null) = EventStore(sessionDir)

    private fun makeView(events: List<Event>) = View.fromEvents(events)

    private fun makeMemory() = ConversationMemory()

    private fun makePipeline(vararg condensers: Condenser) = CondenserPipeline(condensers.toList())

    private fun makeContext(
        view: View,
        tokenUtilization: Double = 0.0,
        effectiveBudget: Int = 100_000
    ) = CondenserContext(
        view = view,
        tokenUtilization = tokenUtilization,
        effectiveBudget = effectiveBudget,
        currentTokens = (tokenUtilization * effectiveBudget).toInt()
    )

    private fun addTo(store: EventStore, event: Event, source: EventSource = EventSource.SYSTEM): Event =
        store.add(event, source)

    // ── Event builder helpers ─────────────────────────────────────────────────

    private fun systemMsg(content: String) = SystemMessageAction(content = content)
    private fun userMsg(content: String) = MessageAction(content = content, source = EventSource.USER)
    private fun agentMsg(content: String) = MessageAction(content = content, source = EventSource.AGENT)
    private fun readAction(path: String, tcId: String, rgId: String = "rg1") =
        FileReadAction(toolCallId = tcId, responseGroupId = rgId, path = path)
    private fun toolResult(tcId: String, content: String = "result", isError: Boolean = false, toolName: String = "read_file") =
        ToolResultObservation(toolCallId = tcId, content = content, isError = isError, toolName = toolName)
    private fun condensationAction(forgottenIds: List<Int>, summary: String? = null, offset: Int? = null) =
        CondensationAction(
            forgottenEventIds = forgottenIds,
            forgottenEventsStartId = null,
            forgottenEventsEndId = null,
            summary = summary,
            summaryOffset = offset
        )
    private fun condensationObs(content: String) = CondensationObservation(content = content)

    // ── Requirement 1: Every message type flows through EventStore ────────────

    @Test
    fun `requirement 1 - every bridge mutation method appends a typed event to EventStore`() {
        val bridge = makeBridge()

        // addSystemMessage
        bridge.addSystemMessage("System warning: context is full.")
        assertEquals(1, bridge.eventStore.size())
        assertTrue(bridge.eventStore.get(0) is SystemMessageAction)

        // addUserMessage
        bridge.addUserMessage("Fix the bug in Foo.kt")
        assertEquals(2, bridge.eventStore.size())
        assertTrue(bridge.eventStore.get(1) is MessageAction)
        assertEquals(EventSource.USER, bridge.eventStore.get(1)!!.source)

        // addAssistantMessage (text response)
        bridge.addAssistantMessage(ChatMessage(role = "assistant", content = "I'll fix that bug."))
        assertEquals(3, bridge.eventStore.size())
        assertTrue(bridge.eventStore.get(2) is MessageAction)
        assertEquals(EventSource.AGENT, bridge.eventStore.get(2)!!.source)

        // addAssistantToolCalls
        bridge.addAssistantToolCalls(ChatMessage(
            role = "assistant",
            content = null,
            toolCalls = listOf(
                com.workflow.orchestrator.agent.api.dto.ToolCall(
                    id = "tc-req1",
                    function = com.workflow.orchestrator.agent.api.dto.FunctionCall(
                        name = "read_file",
                        arguments = """{"path":"Foo.kt"}"""
                    )
                )
            )
        ))
        // Should add at least one ToolAction event
        assertTrue(bridge.eventStore.size() >= 4)
        val toolActionEvent = bridge.eventStore.all().firstOrNull { it is FileReadAction }
        assertNotNull(toolActionEvent, "Expected a FileReadAction event after addAssistantToolCalls")

        // addToolResult
        bridge.addToolResult("tc-req1", "class Foo { }", "File read OK", "read_file")
        val afterToolResult = bridge.eventStore.all().firstOrNull { it is ToolResultObservation && !(it as ToolResultObservation).isError }
        assertNotNull(afterToolResult, "Expected a non-error ToolResultObservation")

        // addToolError
        bridge.addToolError("tc-err", "File not found", "Error: file missing", "read_file")
        val afterToolError = bridge.eventStore.all().firstOrNull { it is ToolResultObservation && (it as ToolResultObservation).isError }
        assertNotNull(afterToolError, "Expected an error ToolResultObservation after addToolError")

        // addSystemPrompt (via separate method, creates a SystemMessageAction)
        val sizeBeforePrompt = bridge.eventStore.size()
        bridge.addSystemPrompt("You are a coding assistant.")
        assertEquals(sizeBeforePrompt + 1, bridge.eventStore.size())
        // The last added system event should be a SystemMessageAction
        val systemEvents = bridge.eventStore.all().filterIsInstance<SystemMessageAction>()
        assertTrue(systemEvents.isNotEmpty())

        // requestCondensation — must add CondensationRequestAction
        bridge.requestCondensation()
        val hasCondensationRequest = bridge.eventStore.all().any { it is CondensationRequestAction }
        assertTrue(hasCondensationRequest, "Expected a CondensationRequestAction event after requestCondensation()")

        // Verify total event count >= 7 (each call above added at least one event)
        assertTrue(bridge.eventStore.size() >= 7,
            "EventStore should have accumulated events from all mutation calls, got ${bridge.eventStore.size()}")
    }

    // ── Requirement 2: Condenser-produced messages include ALL anchor content ─

    @Test
    fun `requirement 2 - getMessagesViaCondenser appends anchor messages from ContextManager`() {
        val bridge = makeBridge()

        // Set up all 6 anchor types in the ContextManager
        bridge.addSystemPrompt("System prompt.")
        bridge.addUserMessage("Do something.")

        // Manually set anchors on the context manager via bridge delegation methods
        bridge.setSkillAnchor(ChatMessage(role = "system", content = "SKILL: systematic-debugging"))
        bridge.setMentionAnchor(ChatMessage(role = "user", content = "MENTION: file mentioned content"))
        bridge.setGuardrailsAnchor(ChatMessage(role = "system", content = "GUARDRAIL: always read before edit"))
        bridge.setPlanAnchor(ChatMessage(role = "system", content = "PLAN: step 1, step 2"))

        // factsAnchor is updated via updateFactsAnchor() — we need a populated facts store
        // The changeLedgerAnchor is set internally; skip it if it requires ChangeLedger
        // (we only test the 4 we can directly set via bridge delegation)

        // Invoke the Phase 2 condenser path
        val outcome = bridge.getMessagesViaCondenser()

        assertTrue(outcome is com.workflow.orchestrator.agent.context.CondenserOutcome.Messages,
            "Expected Messages outcome, got $outcome")

        val messages = (outcome as com.workflow.orchestrator.agent.context.CondenserOutcome.Messages).messages
        val allContent = messages.joinToString("\n") { it.content ?: "" }

        // Each anchor should appear somewhere in the message list
        assertTrue(
            messages.any { it.content?.contains("systematic-debugging") == true },
            "Skill anchor content should appear in condenser messages. All content:\n$allContent"
        )
        assertTrue(
            messages.any { it.content?.contains("MENTION: file mentioned content") == true },
            "Mention anchor content should appear in condenser messages"
        )
        assertTrue(
            messages.any { it.content?.contains("GUARDRAIL: always read before edit") == true },
            "Guardrail anchor content should appear in condenser messages"
        )
        assertTrue(
            messages.any { it.content?.contains("PLAN: step 1") == true },
            "Plan anchor content should appear in condenser messages"
        )
    }

    // ── Requirement 3: Old compression is bypassed when bridge is active ──────

    @Test
    fun `requirement 3 - requestCondensation records CondensationRequestAction and getMessagesViaCondenser handles it`() {
        val bridge = makeBridge()

        bridge.addSystemPrompt("You are an agent.")
        bridge.addUserMessage("Start work.")

        // Call requestCondensation (simulating BudgetEnforcer triggering compression)
        bridge.requestCondensation()

        // Verify CondensationRequestAction is in the event store
        val events = bridge.eventStore.all()
        val hasRequest = events.any { it is CondensationRequestAction }
        assertTrue(hasRequest, "EventStore must contain a CondensationRequestAction after requestCondensation()")

        // getMessagesViaCondenser should still work without error
        val outcome = bridge.getMessagesViaCondenser()
        // It returns either Messages or NeedsCondensation — both are valid; what matters is no exception
        assertNotNull(outcome, "getMessagesViaCondenser() must not return null")

        // The event store size after the request is recorded should be > 2 (system prompt + user msg + request)
        assertTrue(bridge.eventStore.size() >= 3,
            "EventStore should have at least 3 events (prompt + user msg + condensation request)")
    }

    // ── Requirement 4: NEVER_FORGET_TYPES survive condensation ───────────────

    @Test
    fun `requirement 4 - NEVER_FORGET_TYPES are not included in forgottenEventIds after condensation`() {
        // Build an EventStore with 100 events, scattering NEVER_FORGET_TYPES at early/middle/late positions
        val store = makeStore()

        // Events 0–8: user messages / tool actions (forgettable)
        for (i in 0..8) {
            store.add(userMsg("Message $i"), EventSource.USER)
        }
        // Event 9: early FactRecordedAction (NEVER_FORGET)
        val factEarly = store.add(FactRecordedAction(factType = "CODE_PATTERN", path = "/a.kt", content = "early fact"), EventSource.AGENT)

        // Events 10–29: more tool results (forgettable)
        for (i in 10..29) {
            store.add(toolResult("tc$i", "result $i"), EventSource.SYSTEM)
        }
        // Event 30 (approximately): middle PlanUpdatedAction (NEVER_FORGET)
        val planMiddle = store.add(PlanUpdatedAction(planJson = """{"steps":["middle"]}"""), EventSource.AGENT)

        // Events ~31–49: more messages (forgettable)
        for (i in 31..49) {
            store.add(userMsg("User $i"), EventSource.USER)
        }
        // Event ~50: middle GuardrailRecordedAction (NEVER_FORGET)
        val guardrailMiddle = store.add(GuardrailRecordedAction(rule = "never delete without backup"), EventSource.SYSTEM)

        // Events ~51–74: more messages
        for (i in 51..74) {
            store.add(agentMsg("Agent $i"), EventSource.AGENT)
        }
        // Event ~75: late FactRecordedAction (NEVER_FORGET)
        val factLate = store.add(FactRecordedAction(factType = "DISCOVERY", path = null, content = "late fact"), EventSource.AGENT)

        // Pad to approximately 100 events
        val currentSize = store.size()
        for (i in currentSize until 100) {
            store.add(userMsg("Padding $i"), EventSource.USER)
        }

        // Now add a CondensationAction that forgets events 10–80 (a large range)
        // This simulates the condenser having run and forgotten the middle
        val forgottenRange = (10..80).toList()
        store.add(
            CondensationAction(
                forgottenEventIds = forgottenRange,
                forgottenEventsStartId = null,
                forgottenEventsEndId = null,
                summary = "Summary of forgotten events",
                summaryOffset = 2
            ),
            EventSource.SYSTEM
        )

        // Build the View
        val view = View.fromEvents(store.all())

        // NEVER_FORGET events should NOT be in forgottenEventIds
        val neverForgetIds = setOf(factEarly.id, planMiddle.id, guardrailMiddle.id, factLate.id)

        for (id in neverForgetIds) {
            assertFalse(
                id in view.forgottenEventIds,
                "NEVER_FORGET event with id=$id should NOT be in forgottenEventIds. " +
                    "ForgottenIds: ${view.forgottenEventIds}"
            )
        }

        // NEVER_FORGET events should be present in the view's event list
        val viewEventIds = view.events.map { it.id }.toSet()
        for (id in neverForgetIds) {
            assertTrue(
                id in viewEventIds,
                "NEVER_FORGET event with id=$id should be present in the view events. " +
                    "ViewEventIds: $viewEventIds"
            )
        }
    }

    // ── Requirement 5: View.fromEvents() produces correct projection ──────────

    @Test
    fun `requirement 5 - View fromEvents applies two condensation rounds correctly`() {
        // Build a 50-event history with two condensation rounds
        val store = makeStore()

        // Events 0–49: a realistic conversation
        for (i in 0..49) {
            store.add(userMsg("Event $i"), EventSource.USER)
        }

        // Snapshot IDs for the events that will be forgotten
        val round1ForgottenIds = (5..20).toList()
        val round2ForgottenIds = (25..40).toList()

        // Round 1: CondensationAction forgetting events 5-20 with summary at offset 2
        store.add(
            CondensationAction(
                forgottenEventIds = round1ForgottenIds,
                forgottenEventsStartId = null,
                forgottenEventsEndId = null,
                summary = "Round 1",
                summaryOffset = 2
            ),
            EventSource.SYSTEM
        )

        // Round 2: CondensationAction forgetting events 25-40 with summary at offset 2
        store.add(
            CondensationAction(
                forgottenEventIds = round2ForgottenIds,
                forgottenEventsStartId = null,
                forgottenEventsEndId = null,
                summary = "Round 2",
                summaryOffset = 2
            ),
            EventSource.SYSTEM
        )

        val view = View.fromEvents(store.all())

        // Events 5–20 must NOT be in the view
        val viewIds = view.events.map { it.id }.toSet()
        for (id in round1ForgottenIds) {
            assertFalse(id in viewIds, "Event $id (round 1 forgotten) should not appear in view")
        }

        // Events 25–40 must NOT be in the view
        for (id in round2ForgottenIds) {
            assertFalse(id in viewIds, "Event $id (round 2 forgotten) should not appear in view")
        }

        // Only the LAST condensation summary ("Round 2") should appear — not "Round 1"
        val summaryEvents = view.events.filterIsInstance<CondensationObservation>()
        assertTrue(summaryEvents.any { it.content == "Round 2" },
            "Round 2 summary should be present in view")
        assertFalse(summaryEvents.any { it.content == "Round 1" },
            "Round 1 summary should NOT be present in view (last summary wins)")

        // CondensationAction events themselves should also not appear in the view
        val condensationActionEvents = view.events.filterIsInstance<CondensationAction>()
        assertTrue(condensationActionEvents.isEmpty(),
            "CondensationAction events must not appear in the view")
    }

    // ── Requirement 6: ConversationMemory produces Sourcegraph-compatible output

    @Test
    fun `requirement 6 - ConversationMemory produces no system role, starts with user, strict alternation, tool results wrapped`() {
        val store = makeStore()
        val memory = makeMemory()

        // Build a realistic event sequence
        store.add(systemMsg("You are a coding assistant."), EventSource.SYSTEM)       // 0: system
        store.add(userMsg("Read Foo.kt for me."), EventSource.USER)                     // 1: user
        store.add(readAction("Foo.kt", "tc-1", "rg-1"), EventSource.AGENT)            // 2: tool action
        store.add(toolResult("tc-1", "class Foo {}", false, "read_file"), EventSource.SYSTEM) // 3: tool result
        store.add(agentMsg("I've read Foo.kt."), EventSource.AGENT)                    // 4: agent text
        store.add(
            CondensationObservation(content = "Previous context was condensed here."),
            EventSource.SYSTEM
        )                                                                               // 5: condensation obs

        val view = View.fromEvents(store.all())
        val initialUserAction = store.all().firstOrNull { it is MessageAction && it.source == EventSource.USER } as? MessageAction
            ?: MessageAction(content = "Read Foo.kt for me.")
        val messages = memory.processEvents(view.events, initialUserAction, view.forgottenEventIds)

        // No message should have role="system"
        val systemRoleMessages = messages.filter { it.role == "system" }
        assertTrue(systemRoleMessages.isEmpty(),
            "No message should have role=system. Found: $systemRoleMessages")

        // First message must be "user"
        assertTrue(messages.isNotEmpty(), "Message list must not be empty")
        assertEquals("user", messages.first().role,
            "First message must have role=user, got: ${messages.first().role}")

        // No two consecutive messages with same role
        for (i in 0 until messages.size - 1) {
            assertNotEquals(
                messages[i].role, messages[i + 1].role,
                "Consecutive messages at index $i and ${i + 1} have same role '${messages[i].role}'"
            )
        }

        // Tool results must use plain text prefix (not XML tags)
        val toolResultMessages = messages.filter { msg ->
            msg.content?.contains("RESULT of") == true
        }
        assertTrue(toolResultMessages.isNotEmpty(),
            "At least one message should contain 'RESULT of' prefix for tool results")

        // Assistant messages with only tool calls should have zero-width space placeholder if content is null/blank
        val toolCallMessages = messages.filter { it.role == "assistant" && !it.toolCalls.isNullOrEmpty() }
        for (msg in toolCallMessages) {
            assertFalse(msg.content.isNullOrBlank(),
                "Assistant message with tool calls should have zero-width space placeholder, not blank content")
        }
    }

    // ── Requirement 7: SmartPruner is zero-loss (only replaces, never removes) ─

    @Test
    fun `requirement 7 - SmartPrunerCondenser replaces events in view but leaves EventStore untouched`() {
        val store = makeStore()

        // Add 20 events including duplicate file reads
        store.add(userMsg("Please analyze the project."), EventSource.USER)               // 0
        store.add(readAction("Foo.kt", "tc-r1", "rg1"), EventSource.AGENT)               // 1
        store.add(toolResult("tc-r1", "class Foo { val x = 1 }", false, "read_file"), EventSource.SYSTEM)  // 2
        store.add(agentMsg("Foo has a field x."), EventSource.AGENT)                      // 3
        store.add(userMsg("Now edit Foo."), EventSource.USER)                              // 4
        store.add(
            FileEditAction("tc-e1", "rg2", "Foo.kt", "val x = 1", "val x = 2"),
            EventSource.AGENT
        )                                                                                  // 5
        store.add(toolResult("tc-e1", "Edit applied.", false, "edit_file"), EventSource.SYSTEM)  // 6
        store.add(agentMsg("Edit done."), EventSource.AGENT)                               // 7
        // Read Foo.kt again — duplicate read, SmartPruner should replace the older read result
        store.add(readAction("Foo.kt", "tc-r2", "rg3"), EventSource.AGENT)               // 8
        store.add(toolResult("tc-r2", "class Foo { val x = 2 }", false, "read_file"), EventSource.SYSTEM) // 9
        store.add(agentMsg("Foo now has x=2."), EventSource.AGENT)                        // 10

        // Pad to 20 events
        for (i in 11..19) {
            store.add(userMsg("Message $i"), EventSource.USER)
        }

        assertEquals(20, store.size(), "EventStore should have exactly 20 events before pruning")

        // Run SmartPrunerCondenser
        val view = View.fromEvents(store.all())
        val context = makeContext(view, tokenUtilization = 0.1)
        val condenser = SmartPrunerCondenser()
        val result = condenser.condense(context)

        // Result must be CondenserView (SmartPruner never returns Condensation)
        assertTrue(result is CondenserView, "SmartPruner must always return CondenserView")
        val prunedView = (result as CondenserView).view

        // EventStore must still have exactly 20 events with original content
        assertEquals(20, store.size(),
            "EventStore must still have exactly 20 events after SmartPruner ran")

        // The view also has 20 events (SmartPruner replaces, not removes)
        assertEquals(20, prunedView.events.size,
            "SmartPrunerCondenser must not change view size — only replace content. " +
                "Expected 20, got ${prunedView.events.size}")

        // Original EventStore content is untouched — the older read result is still there
        val originalEvent2 = store.get(2)
        assertNotNull(originalEvent2)
        assertTrue(originalEvent2 is ToolResultObservation,
            "Event 2 in EventStore must remain the original ToolResultObservation")
        assertEquals("class Foo { val x = 1 }",
            (originalEvent2 as ToolResultObservation).content,
            "EventStore event 2 content must be unchanged by SmartPruner")

        // The VIEW may have replaced the older read result with a CondensationObservation
        // (SmartPruner deduplicates: tc-r1 result at index 2 in view → replaced because tc-r2 came later)
        // At minimum some event in the view should be a CondensationObservation (the replaced read result)
        val hasReplacementsInView = prunedView.events.any { it is CondensationObservation }
        assertTrue(hasReplacementsInView,
            "SmartPruner should have replaced at least one duplicate read result with a CondensationObservation in the view")
    }

    // ── Requirement 8: Pipeline order matters ────────────────────────────────

    @Test
    fun `requirement 8 - CondenserFactory creates pipeline in correct SmartPruner-ObservationMasking-ConversationWindow-LLMSummarizing order`() {
        // Without LLM client: SmartPruner + ObservationMasking + ConversationWindow (3 stages)
        val configNoLlm = ContextManagementConfig.DEFAULT
        val pipelineNoLlm = CondenserFactory.create(configNoLlm, llmClient = null)
        val condensersNoLlm = pipelineNoLlm.getCondensers()

        assertEquals(3, condensersNoLlm.size,
            "Without LLM client, pipeline should have 3 condensers (SmartPruner+ObsrvMasking+ConvWindow)")
        assertTrue(condensersNoLlm[0] is SmartPrunerCondenser,
            "Stage 0 should be SmartPrunerCondenser, got ${condensersNoLlm[0]::class.simpleName}")
        assertTrue(condensersNoLlm[1] is ObservationMaskingCondenser,
            "Stage 1 should be ObservationMaskingCondenser, got ${condensersNoLlm[1]::class.simpleName}")
        assertTrue(condensersNoLlm[2] is ConversationWindowCondenser,
            "Stage 2 should be ConversationWindowCondenser, got ${condensersNoLlm[2]::class.simpleName}")

        // With LLM client: SmartPruner + ObservationMasking + ConversationWindow + LLMSummarizing (4 stages)
        val mockClient = mockk<SummarizationClient>()
        val pipelineWithLlm = CondenserFactory.create(configNoLlm, llmClient = mockClient)
        val condensersWithLlm = pipelineWithLlm.getCondensers()

        assertEquals(4, condensersWithLlm.size,
            "With LLM client, pipeline should have 4 condensers")
        assertTrue(condensersWithLlm[0] is SmartPrunerCondenser,
            "Stage 0 with LLM should be SmartPrunerCondenser")
        assertTrue(condensersWithLlm[1] is ObservationMaskingCondenser,
            "Stage 1 with LLM should be ObservationMaskingCondenser")
        assertTrue(condensersWithLlm[2] is ConversationWindowCondenser,
            "Stage 2 with LLM should be ConversationWindowCondenser")
        assertTrue(condensersWithLlm[3] is LLMSummarizingCondenser,
            "Stage 3 with LLM should be LLMSummarizingCondenser")

        // Run the no-LLM pipeline on a small view with low token utilization
        // → SmartPruner and ObservationMasking run (they always run), ConversationWindow should NOT condense
        val smallStore = makeStore()
        for (i in 0..5) smallStore.add(userMsg("Msg $i"), EventSource.USER)
        val smallView = View.fromEvents(smallStore.all())
        val lowUtilContext = makeContext(smallView, tokenUtilization = 0.1, effectiveBudget = 100_000)

        val resultLowUtil = pipelineNoLlm.condense(lowUtilContext)

        // Low utilization → pipeline returns CondenserView (no condensation triggered)
        assertTrue(resultLowUtil is CondenserView,
            "Pipeline with low token utilization should return CondenserView, not Condensation")
    }

    // ── Requirement 9: ObservationMasking preserves existing summaries ────────

    @Test
    fun `requirement 9 - ObservationMaskingCondenser does not mask CondensationObservation events`() {
        // Build a view with observations at various positions.
        // Use token-utilization-gated condenser with small windows so that
        // early observations fall into METADATA tier (masked) while recent ones stay FULL.
        // CondensationObservation must NEVER be masked regardless of tier.

        val events = mutableListOf<Event>()
        var id = 0

        // Positions 0-1: ToolResultObservations (will be far from tail → METADATA tier → masked)
        events.add(ToolResultObservation(toolCallId = "tc-0", content = "Old result 0", isError = false, toolName = "read_file", id = id++))
        events.add(ToolResultObservation(toolCallId = "tc-1", content = "Old result 1", isError = false, toolName = "run_command", id = id++))

        // Position 2: Another ToolResultObservation (will be masked)
        events.add(ToolResultObservation(toolCallId = "tc-2", content = "Old result 2", isError = false, toolName = "search_code", id = id++))

        // Position 3: CondensationObservation (existing summary — should NEVER be masked)
        events.add(CondensationObservation(content = "Previous condensation summary content", id = id++))

        // Position 4: Another ToolResultObservation (will be masked)
        events.add(ToolResultObservation(toolCallId = "tc-4", content = "Old result 4", isError = false, toolName = "diagnostics", id = id++))

        // Padding to push early events beyond outer window token threshold
        // Use large content to create token distance
        for (i in 5..14) {
            events.add(ToolResultObservation(
                toolCallId = "tc-$id", content = "x".repeat(2000),
                isError = false, toolName = "read_file", id = id++
            ))
        }

        // Recent events (close to tail → FULL tier)
        for (i in 15..24) {
            events.add(ToolResultObservation(
                toolCallId = "tc-inside-$id", content = "Recent result $id",
                isError = false, toolName = "read_file", id = id++
            ))
        }

        val totalEvents = events.size
        val view = View(events = events)
        // Use token utilization above threshold (0.70 > 0.60) with small windows
        val ctx = makeContext(view, tokenUtilization = 0.70)
        val condenser = ObservationMaskingCondenser(
            threshold = 0.60,
            innerWindowTokens = 500,    // small: recent events stay FULL
            outerWindowTokens = 1_000   // small: early events go to METADATA
        )
        val result = condenser.condense(ctx)

        assertTrue(result is CondenserView,
            "ObservationMaskingCondenser must always return CondenserView")
        val maskedView = (result as CondenserView).view

        // The CondensationObservation at position 3 must NOT be masked
        val eventAtCondensationPos = maskedView.events[3]
        assertTrue(eventAtCondensationPos is CondensationObservation,
            "Event at position 3 (CondensationObservation) must not be masked, " +
                "but it was replaced with: ${eventAtCondensationPos::class.simpleName}")
        assertEquals("Previous condensation summary content",
            (eventAtCondensationPos as CondensationObservation).content,
            "CondensationObservation content must be preserved exactly")

        // ToolResultObservations at early positions should be masked or compressed
        for (pos in listOf(0, 1, 2, 4)) {
            val event = maskedView.events[pos]
            assertFalse(
                event is ToolResultObservation && (event as ToolResultObservation).content.startsWith("Old result"),
                "ToolResultObservation at position $pos should be masked/compressed, " +
                    "but got original content: ${event::class.simpleName}")
        }

        // Recent events near tail should remain as ToolResultObservation
        val lastFew = maskedView.events.takeLast(5)
        val recentToolResults = lastFew.filterIsInstance<ToolResultObservation>()
        assertTrue(recentToolResults.isNotEmpty(),
            "Events near the tail (FULL tier) should remain as ToolResultObservation")
    }

    // ── Requirement 10: JSONL persistence survives IDE crash simulation ────────

    @Test
    fun `requirement 10 - EventStore reloads from JSONL and View reconstructs identical state`(@TempDir tempDir: File) {
        // Session 1: create store, add 30 events, flush to disk
        val sessionDir = File(tempDir, "session-1").also { it.mkdirs() }
        val store1 = EventStore(sessionDir)

        store1.add(systemMsg("System init."), EventSource.SYSTEM)
        store1.add(userMsg("Help me fix the bug."), EventSource.USER)
        for (i in 2..24) {
            store1.add(userMsg("Event $i"), EventSource.USER)
        }
        // Add a CondensationAction to test View reconstruction after reload
        store1.add(
            CondensationAction(
                forgottenEventIds = (2..15).toList(),
                forgottenEventsStartId = null,
                forgottenEventsEndId = null,
                summary = "Condensed events 2-15",
                summaryOffset = 1
            ),
            EventSource.SYSTEM
        )
        // More events after condensation
        for (i in 26..29) {
            store1.add(agentMsg("Post-condensation event $i"), EventSource.AGENT)
        }

        assertEquals(30, store1.size(), "Store1 should have exactly 30 events")

        // Flush to JSONL (IDE "crash" is just: don't use store1 anymore)
        store1.flush()

        // Session 2: create a brand new EventStore from the same directory (simulating IDE restart)
        val store2 = EventStore.loadFromJsonl(sessionDir)

        // Verify: same event count
        assertEquals(store1.size(), store2.size(),
            "Reloaded EventStore must have same event count as original")

        // Verify: same event types in same order
        val events1 = store1.all()
        val events2 = store2.all()
        for (i in events1.indices) {
            assertEquals(events1[i]::class, events2[i]::class,
                "Event at index $i should be ${events1[i]::class.simpleName}, " +
                    "got ${events2[i]::class.simpleName}")
            assertEquals(events1[i].id, events2[i].id,
                "Event at index $i should have id=${events1[i].id}, got ${events2[i].id}")
        }

        // Build Views from both stores
        val view1 = View.fromEvents(store1.all())
        val view2 = View.fromEvents(store2.all())

        // Forgotten IDs must match
        assertEquals(view1.forgottenEventIds, view2.forgottenEventIds,
            "ForgottenEventIds must be identical after reload")

        // Event IDs in view must match (same filtering applies)
        val view1Ids = view1.events.map { it.id }
        val view2Ids = view2.events.map { it.id }
        assertEquals(view1Ids, view2Ids,
            "View event IDs must be identical after EventStore reload")

        // Summary injection must produce the same CondensationObservation
        val summary1 = view1.events.filterIsInstance<CondensationObservation>()
        val summary2 = view2.events.filterIsInstance<CondensationObservation>()
        assertEquals(summary1.size, summary2.size,
            "Views must have same number of CondensationObservation summaries")
        if (summary1.isNotEmpty()) {
            assertEquals(summary1[0].content, summary2[0].content,
                "Summary content must be identical after reload")
        }
    }

    // ── Requirement 11: Token-aware trigger fires at correct utilization ───────

    @Test
    fun `requirement 11 - LLMSummarizingCondenser shouldCondense fires above tokenThreshold 0_75`() {
        val mockClient = mockk<SummarizationClient>()
        coEvery { mockClient.summarize(any()) } returns "LLM summary"

        val condenser = LLMSummarizingCondenser(
            llmClient = mockClient,
            tokenThreshold = 0.75
        )

        // Build a small view (well below maxSize=150) and no unhandled request
        val smallStore = makeStore()
        for (i in 0..5) {
            smallStore.add(userMsg("Message $i"), EventSource.USER)
        }
        val smallView = View.fromEvents(smallStore.all())

        // At 74% utilization → shouldCondense must be FALSE
        val ctxBelow = makeContext(smallView, tokenUtilization = 0.74)
        assertFalse(condenser.shouldCondense(ctxBelow),
            "shouldCondense should return false at 74% utilization (below 0.75 threshold)")

        // At 76% utilization → shouldCondense must be TRUE
        val ctxAbove = makeContext(smallView, tokenUtilization = 0.76)
        assertTrue(condenser.shouldCondense(ctxAbove),
            "shouldCondense should return true at 76% utilization (above 0.75 threshold)")

        // Verify it works regardless of view size — build larger view
        val largerStore = makeStore()
        for (i in 0..30) largerStore.add(userMsg("Msg $i"), EventSource.USER)
        val largerView = View.fromEvents(largerStore.all())

        val ctxBelowLarger = makeContext(largerView, tokenUtilization = 0.74)
        assertFalse(condenser.shouldCondense(ctxBelowLarger),
            "shouldCondense should return false at 74% regardless of view size")

        val ctxAboveLarger = makeContext(largerView, tokenUtilization = 0.76)
        assertTrue(condenser.shouldCondense(ctxAboveLarger),
            "shouldCondense should return true at 76% regardless of view size")
    }

    // ── Requirement 12: Condensation loop detection ───────────────────────────

    @Test
    fun `requirement 12 - LoopGuard isCondensationLooping detects 10+ consecutive condensation events`() {
        // Build 12 events: alternating CondensationObservation and CondensationAction (no real work)
        val allCondensationEvents = mutableListOf<Event>()
        var id = 0
        for (i in 0 until 6) {
            allCondensationEvents.add(CondensationObservation(content = "Summary $i", id = id++))
            allCondensationEvents.add(
                CondensationAction(
                    forgottenEventIds = listOf(i),
                    forgottenEventsStartId = null,
                    forgottenEventsEndId = null,
                    summary = null,
                    summaryOffset = null,
                    id = id++
                )
            )
        }
        assertEquals(12, allCondensationEvents.size,
            "Should have 12 all-condensation events")

        // With threshold=10: 12 consecutive condensation events → should detect loop
        assertTrue(
            LoopGuard.isCondensationLooping(allCondensationEvents, threshold = 10),
            "isCondensationLooping should return true for 12 consecutive condensation events (threshold=10)"
        )

        // Now insert a real work event (MessageAction) in the middle
        val withRealWork = allCondensationEvents.toMutableList()
        val insertPos = 6  // Insert between the two halves
        withRealWork.add(insertPos, MessageAction(content = "Real user work", id = id++))

        // Re-assign IDs to maintain sorted order (for realism)
        // isCondensationLooping scans reversed — the "real work" breaks the consecutive count
        // After re-insertion: last 6 events are condensation, then real work, then first 6 condensation
        // The reversed scan will find ≤6 consecutive condensation events before hitting real work
        assertFalse(
            LoopGuard.isCondensationLooping(withRealWork, threshold = 10),
            "isCondensationLooping should return false when a real work event breaks the condensation streak"
        )

        // Edge case: exactly threshold consecutive condensation events → detects loop
        val exactlyAtThreshold = mutableListOf<Event>()
        var eid = 0
        for (i in 0 until 5) {
            exactlyAtThreshold.add(CondensationObservation(content = "Summary $i", id = eid++))
            exactlyAtThreshold.add(
                CondensationAction(
                    forgottenEventIds = listOf(i),
                    forgottenEventsStartId = null,
                    forgottenEventsEndId = null,
                    summary = null,
                    summaryOffset = null,
                    id = eid++
                )
            )
        }
        assertEquals(10, exactlyAtThreshold.size)
        assertTrue(
            LoopGuard.isCondensationLooping(exactlyAtThreshold, threshold = 10),
            "isCondensationLooping should return true when count reaches exactly the threshold"
        )

        // Exactly one below threshold (9 events) → no loop
        val nineConsecutive = exactlyAtThreshold.dropLast(1)
        assertEquals(9, nineConsecutive.size)
        assertFalse(
            LoopGuard.isCondensationLooping(nineConsecutive, threshold = 10),
            "isCondensationLooping should return false for 9 consecutive condensation events (threshold=10)"
        )
    }
}
