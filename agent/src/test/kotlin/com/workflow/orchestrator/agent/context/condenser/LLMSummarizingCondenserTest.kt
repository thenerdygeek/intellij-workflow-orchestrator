package com.workflow.orchestrator.agent.context.condenser

import com.workflow.orchestrator.agent.api.dto.ChatMessage
import com.workflow.orchestrator.agent.context.events.*
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class LLMSummarizingCondenserTest {

    private val llmClient = mockk<SummarizationClient>()

    private fun condenser(
        keepFirst: Int = 4,
        maxSize: Int = 20,
        tokenThreshold: Double = 0.75,
        maxEventLength: Int = 10_000
    ) = LLMSummarizingCondenser(
        llmClient = llmClient,
        keepFirst = keepFirst,
        maxSize = maxSize,
        tokenThreshold = tokenThreshold,
        maxEventLength = maxEventLength
    )

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun contextOf(
        view: View,
        utilization: Double = 0.5,
        budget: Int = 100_000
    ) = CondenserContext(
        view = view,
        tokenUtilization = utilization,
        effectiveBudget = budget,
        currentTokens = (budget * utilization).toInt()
    )

    private fun message(id: Int, content: String = "message $id", source: EventSource = EventSource.USER) =
        MessageAction(content = content, id = id, source = source)

    private fun systemMessage(id: Int, content: String = "system $id") =
        SystemMessageAction(content = content, id = id)

    private fun toolAction(id: Int, toolName: String = "read_file") = GenericToolAction(
        toolCallId = "tc-$id",
        responseGroupId = "rg-$id",
        toolName = toolName,
        arguments = """{"path": "/src/main.kt"}""",
        id = id
    )

    private fun toolResult(id: Int, content: String = "result $id", toolName: String = "read_file") =
        ToolResultObservation(
            toolCallId = "tc-$id",
            content = content,
            isError = false,
            toolName = toolName,
            id = id
        )

    private fun condensationObs(content: String = "previous summary") =
        CondensationObservation(content = content, id = -1)

    private fun factRecorded(id: Int, content: String = "fact $id") = FactRecordedAction(
        factType = "DISCOVERY",
        path = null,
        content = content,
        id = id
    )

    private fun planUpdated(id: Int) = PlanUpdatedAction(
        planJson = """{"steps": []}""",
        id = id
    )

    // -----------------------------------------------------------------------
    // shouldCondense
    // -----------------------------------------------------------------------

    @Nested
    inner class ShouldCondense {

        @Test
        fun `returns true when tokenUtilization exceeds threshold`() {
            val c = condenser(tokenThreshold = 0.75)
            val view = View(events = listOf(message(1)))
            assertTrue(c.shouldCondense(contextOf(view, utilization = 0.80)))
        }

        @Test
        fun `returns true when view size exceeds maxSize`() {
            val c = condenser(maxSize = 10)
            val events = (1..15).map { message(it) }
            val view = View(events = events)
            assertTrue(c.shouldCondense(contextOf(view, utilization = 0.3)))
        }

        @Test
        fun `returns true when unhandledCondensationRequest is true`() {
            val c = condenser()
            val view = View(
                events = listOf(message(1)),
                unhandledCondensationRequest = true
            )
            assertTrue(c.shouldCondense(contextOf(view, utilization = 0.3)))
        }

        @Test
        fun `returns false when all below thresholds`() {
            val c = condenser(maxSize = 100, tokenThreshold = 0.75)
            val events = (1..5).map { message(it) }
            val view = View(events = events, unhandledCondensationRequest = false)
            assertFalse(c.shouldCondense(contextOf(view, utilization = 0.5)))
        }
    }

    // -----------------------------------------------------------------------
    // getCondensation — structure
    // -----------------------------------------------------------------------

    @Nested
    inner class GetCondensation {

        @Test
        fun `keeps first keepFirst events`() = runTest {
            coEvery { llmClient.summarize(any()) } returns "LLM summary"

            val c = condenser(keepFirst = 3, maxSize = 20)
            // 25 events: head=3, targetSize=10, tail slots=10-3-1=6, forgotten=25-3-6=16
            val events = (1..25).map { message(it) }
            val view = View(events = events)

            val result = c.getCondensation(contextOf(view)) as Condensation
            val forgotten = result.action.forgotten.toSet()

            // First 3 events should NOT be forgotten
            assertFalse(1 in forgotten)
            assertFalse(2 in forgotten)
            assertFalse(3 in forgotten)
        }

        @Test
        fun `keeps tail events`() = runTest {
            coEvery { llmClient.summarize(any()) } returns "LLM summary"

            val c = condenser(keepFirst = 4, maxSize = 20)
            // 25 events: head=4, targetSize=10, tail slots=10-4-1=5
            val events = (1..25).map { message(it) }
            val view = View(events = events)

            val result = c.getCondensation(contextOf(view)) as Condensation
            val forgotten = result.action.forgotten.toSet()

            // Last 5 events should NOT be forgotten
            for (id in 21..25) {
                assertFalse(id in forgotten, "Event $id should be kept as tail")
            }
        }

        @Test
        fun `includes PREVIOUS SUMMARY from existing CondensationObservation`() = runTest {
            val messagesSlot = slot<List<ChatMessage>>()
            coEvery { llmClient.summarize(capture(messagesSlot)) } returns "new summary"

            val c = condenser(keepFirst = 2, maxSize = 10)
            // events[keepFirst=2] is a CondensationObservation
            val events = listOf(
                message(1),
                message(2),
                condensationObs("this is the old summary"),  // at index 2 = keepFirst
                message(100, source = EventSource.AGENT),
                message(101),
                message(102),
                message(103),
                message(104),
                message(105),
                message(106),
                message(107),
                message(108)
            )
            val view = View(events = events)

            c.getCondensation(contextOf(view))

            val promptContent = messagesSlot.captured[0].content!!
            assertTrue(
                promptContent.contains("this is the old summary"),
                "Prompt should include previous summary"
            )
            assertTrue(
                promptContent.contains("<PREVIOUS SUMMARY>"),
                "Prompt should have PREVIOUS SUMMARY tags"
            )
        }

        @Test
        fun `formats forgotten events as EVENT blocks`() = runTest {
            val messagesSlot = slot<List<ChatMessage>>()
            coEvery { llmClient.summarize(capture(messagesSlot)) } returns "summary"

            val c = condenser(keepFirst = 2, maxSize = 10)
            val events = (1..15).map { message(it) }
            val view = View(events = events)

            c.getCondensation(contextOf(view))

            val promptContent = messagesSlot.captured[0].content!!
            assertTrue(promptContent.contains("<EVENT id="), "Should contain EVENT blocks")
            assertTrue(promptContent.contains("</EVENT>"), "Should close EVENT blocks")
        }

        @Test
        fun `calls LLM with correct prompt structure`() = runTest {
            val messagesSlot = slot<List<ChatMessage>>()
            coEvery { llmClient.summarize(capture(messagesSlot)) } returns "summary"

            val c = condenser(keepFirst = 2, maxSize = 10)
            val events = (1..15).map { message(it) }
            val view = View(events = events)

            c.getCondensation(contextOf(view))

            val captured = messagesSlot.captured
            assertEquals(1, captured.size)
            assertEquals("user", captured[0].role)

            val content = captured[0].content!!
            assertTrue(content.contains("<system_instructions>"))
            assertTrue(content.contains("</system_instructions>"))
            assertTrue(content.contains("<PREVIOUS SUMMARY>"))
            assertTrue(content.contains("</PREVIOUS SUMMARY>"))
            assertTrue(content.contains("No events summarized"), "Default when no previous summary")
            assertTrue(content.contains("Now summarize the events using the rules above."))
            assertTrue(content.contains("context-aware state summary"))
        }

        @Test
        fun `returns Condensation with summary and summaryOffset equal to keepFirst`() = runTest {
            coEvery { llmClient.summarize(any()) } returns "LLM generated summary"

            val c = condenser(keepFirst = 4, maxSize = 20)
            val events = (1..25).map { message(it) }
            val view = View(events = events)

            val result = c.getCondensation(contextOf(view)) as Condensation

            assertEquals("LLM generated summary", result.action.summary)
            assertEquals(4, result.action.summaryOffset)
        }

        @Test
        fun `uses range mode for contiguous forgotten IDs`() = runTest {
            coEvery { llmClient.summarize(any()) } returns "summary"

            val c = condenser(keepFirst = 2, maxSize = 14)
            // 14 events, targetSize=7, tail=7-2-1=4, so we keep ids 1,2 (head) + 11,12,13,14 (tail)
            // Forgotten: 3,4,5,6,7,8,9,10 — contiguous
            val events = (1..14).map { message(it) }
            val view = View(events = events)

            val result = c.getCondensation(contextOf(view)) as Condensation

            assertNull(result.action.forgottenEventIds, "Should use range mode")
            assertNotNull(result.action.forgottenEventsStartId)
            assertNotNull(result.action.forgottenEventsEndId)
        }

        @Test
        fun `protects NEVER_FORGET_TYPES from forgotten set`() = runTest {
            coEvery { llmClient.summarize(any()) } returns "summary"

            val c = condenser(keepFirst = 2, maxSize = 10)
            val events = listOf(
                message(1),
                message(2),
                factRecorded(3),           // NEVER_FORGET — should be protected
                message(4),
                planUpdated(5),            // NEVER_FORGET — should be protected
                message(6),
                message(7),
                message(8),
                message(9),
                message(10),
                message(11),
                message(12)
            )
            val view = View(events = events)

            val result = c.getCondensation(contextOf(view)) as Condensation
            val forgotten = result.action.forgotten.toSet()

            assertFalse(3 in forgotten, "FactRecordedAction should be protected")
            assertFalse(5 in forgotten, "PlanUpdatedAction should be protected")
        }

        @Test
        fun `falls back to simple summary on LLM failure`() = runTest {
            coEvery { llmClient.summarize(any()) } returns null

            val c = condenser(keepFirst = 2, maxSize = 10)
            val events = (1..15).map { message(it, content = "content for $it") }
            val view = View(events = events)

            val result = c.getCondensation(contextOf(view)) as Condensation

            assertNotNull(result.action.summary, "Should have fallback summary")
            assertTrue(result.action.summary!!.contains("[Message from"), "Fallback should contain formatted events")
        }

        @Test
        fun `falls back to simple summary on LLM blank response`() = runTest {
            coEvery { llmClient.summarize(any()) } returns "   "

            val c = condenser(keepFirst = 2, maxSize = 10)
            val events = (1..15).map { message(it) }
            val view = View(events = events)

            val result = c.getCondensation(contextOf(view)) as Condensation

            assertNotNull(result.action.summary)
            assertTrue(result.action.summary!!.startsWith("-"), "Fallback summary should be bullet list")
        }

        @Test
        fun `falls back to simple summary on LLM exception`() = runTest {
            coEvery { llmClient.summarize(any()) } throws RuntimeException("API error")

            val c = condenser(keepFirst = 2, maxSize = 10)
            val events = (1..15).map { message(it) }
            val view = View(events = events)

            val result = c.getCondensation(contextOf(view)) as Condensation

            assertNotNull(result.action.summary, "Should have fallback summary on exception")
        }

        @Test
        fun `truncates long events to maxEventLength`() = runTest {
            val messagesSlot = slot<List<ChatMessage>>()
            coEvery { llmClient.summarize(capture(messagesSlot)) } returns "summary"

            val c = condenser(keepFirst = 2, maxSize = 10, maxEventLength = 50)
            val events = listOf(
                message(1),
                message(2),
                message(3, content = "x".repeat(200)),  // Will be in forgotten set
                message(4),
                message(5),
                message(6),
                message(7),
                message(8),
                message(9),
                message(10),
                message(11),
                message(12)
            )
            val view = View(events = events)

            c.getCondensation(contextOf(view))

            val promptContent = messagesSlot.captured[0].content!!
            // The formatted event "[Message from USER] xxx..." exceeds 50 chars
            // so it should be truncated with "..."
            assertFalse(
                promptContent.contains("x".repeat(200)),
                "Long event content should be truncated"
            )
        }

        @Test
        fun `uses explicit IDs for non-contiguous forgotten IDs`() = runTest {
            coEvery { llmClient.summarize(any()) } returns "summary"

            val c = condenser(keepFirst = 2, maxSize = 14)
            // Non-contiguous IDs: insert a NEVER_FORGET event in the middle
            val events = listOf(
                message(1),
                message(2),
                message(3),
                factRecorded(4),  // NEVER_FORGET — creates gap in forgotten IDs
                message(5),
                message(6),
                message(7),
                message(8),
                message(9),
                message(10),
                message(11),
                message(12),
                message(13),
                message(14)
            )
            val view = View(events = events)

            val result = c.getCondensation(contextOf(view)) as Condensation
            val forgotten = result.action.forgotten

            // ID 4 is protected, so forgotten should be non-contiguous
            assertFalse(4 in forgotten)
            assertNotNull(result.action.forgottenEventIds, "Should use explicit IDs for non-contiguous")
            assertNull(result.action.forgottenEventsStartId, "Should not use range mode")
        }

        @Test
        fun `does not include CondensationObservation in forgotten events sent to LLM`() = runTest {
            val messagesSlot = slot<List<ChatMessage>>()
            coEvery { llmClient.summarize(capture(messagesSlot)) } returns "summary"

            val c = condenser(keepFirst = 2, maxSize = 10)
            val events = listOf(
                message(1),
                message(2),
                condensationObs("old summary"),  // Should be excluded from EVENT blocks
                message(100),
                message(101),
                message(102),
                message(103),
                message(104),
                message(105),
                message(106),
                message(107),
                message(108)
            )
            val view = View(events = events)

            c.getCondensation(contextOf(view))

            val promptContent = messagesSlot.captured[0].content!!
            // The condensation observation should not appear as an EVENT block
            assertFalse(
                promptContent.contains("<EVENT id=-1>"),
                "CondensationObservation should not be included in EVENT blocks"
            )
        }

        @Test
        fun `returns no-op condensation when nothing to forget`() = runTest {
            val c = condenser(keepFirst = 5, maxSize = 20)
            // Only 5 events, all in head
            val events = (1..5).map { message(it) }
            val view = View(events = events)

            val result = c.getCondensation(contextOf(view)) as Condensation

            assertTrue(result.action.forgotten.isEmpty())
        }

        @Test
        fun `LLM client is called exactly once`() = runTest {
            coEvery { llmClient.summarize(any()) } returns "summary"

            val c = condenser(keepFirst = 2, maxSize = 10)
            val events = (1..15).map { message(it) }
            val view = View(events = events)

            c.getCondensation(contextOf(view))

            coVerify(exactly = 1) { llmClient.summarize(any()) }
        }

        @Test
        fun `fallback summary truncates to 4K characters`() = runTest {
            coEvery { llmClient.summarize(any()) } returns null

            val c = condenser(keepFirst = 2, maxSize = 10)
            // Create events with very long content to exceed 4K
            val events = (1..50).map { message(it, content = "x".repeat(500)) }
            val view = View(events = events)

            val result = c.getCondensation(contextOf(view)) as Condensation

            assertTrue(
                result.action.summary!!.length <= 4100, // 4096 + "..."
                "Fallback summary should be truncated to ~4K"
            )
            assertTrue(result.action.summary!!.endsWith("..."))
        }
    }
}
