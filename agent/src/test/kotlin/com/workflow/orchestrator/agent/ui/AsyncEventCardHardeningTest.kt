package com.workflow.orchestrator.agent.ui

import com.workflow.orchestrator.agent.loop.queue.QueuedMessage
import com.workflow.orchestrator.agent.loop.queue.QueueSourceKind
import com.workflow.orchestrator.agent.monitor.Severity
import com.workflow.orchestrator.agent.session.AsyncEventCardData
import com.workflow.orchestrator.agent.session.AsyncEventKind
import com.workflow.orchestrator.agent.session.AsyncEventStatus
import com.workflow.orchestrator.agent.tools.background.BackgroundCompletionEvent
import com.workflow.orchestrator.agent.tools.background.BackgroundState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Hardening tests for [AsyncEventCardPresenter] and [AsyncEventResumeSynthesis].
 *
 * These tests cover edge cases NOT already exercised by [AsyncEventCardPresenterTest],
 * [AsyncEventResumeSynthesisTest], [AsyncEventCardDataTest], or related contract tests.
 */
class AsyncEventCardHardeningTest {

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private val json = Json { ignoreUnknownKeys = true }

    private fun bgEvent(
        bgId: String = "bg42",
        state: BackgroundState = BackgroundState.EXITED,
        exitCode: Int = 0,
        label: String = "npm run build",
        occurredAt: Long = 9999L,
        runtimeMs: Long = 3000L,
    ) = BackgroundCompletionEvent(
        bgId = bgId,
        kind = "command",
        label = label,
        sessionId = "sess1",
        exitCode = exitCode,
        state = state,
        runtimeMs = runtimeMs,
        tailContent = "tail output",
        spillPath = null,
        occurredAt = occurredAt,
    )

    private fun queuedMsgWithCard(id: String, card: AsyncEventCardData) = QueuedMessage(
        id = id,
        kind = QueueSourceKind.BACKGROUND,
        body = "body text",
        timestamp = 1000L,
        priority = 50,
        meta = mapOf("card" to json.encodeToString(AsyncEventCardData.serializer(), card)),
    )

    private fun queuedMsgWithoutCard(id: String) = QueuedMessage(
        id = id,
        kind = QueueSourceKind.BACKGROUND,
        body = "body text",
        timestamp = 1000L,
        priority = 50,
        meta = emptyMap(),
    )

    // ─────────────────────────────────────────────────────────────
    // Scenario 1: Background id stability
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `fromBackground called twice on same event produces identical id`() {
        val event = bgEvent()
        val card1 = AsyncEventCardPresenter.fromBackground(event)
        val card2 = AsyncEventCardPresenter.fromBackground(event)
        assertEquals(card1.id, card2.id, "id must be deterministic for the same event")
        assertEquals("bg-bg42-9999", card1.id)
    }

    // ─────────────────────────────────────────────────────────────
    // Scenario 2: All BackgroundState variants → expected status
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `EXITED with exit code 0 → SUCCESS`() {
        val card = AsyncEventCardPresenter.fromBackground(bgEvent(state = BackgroundState.EXITED, exitCode = 0))
        assertEquals(AsyncEventStatus.SUCCESS, card.status)
    }

    @Test
    fun `EXITED with non-zero exit code → FAILURE`() {
        val card = AsyncEventCardPresenter.fromBackground(bgEvent(state = BackgroundState.EXITED, exitCode = 2))
        assertEquals(AsyncEventStatus.FAILURE, card.status)
    }

    @Test
    fun `KILLED → FAILURE`() {
        val card = AsyncEventCardPresenter.fromBackground(bgEvent(state = BackgroundState.KILLED, exitCode = 0))
        assertEquals(AsyncEventStatus.FAILURE, card.status)
    }

    @Test
    fun `TIMED_OUT → FAILURE`() {
        val card = AsyncEventCardPresenter.fromBackground(bgEvent(state = BackgroundState.TIMED_OUT, exitCode = 0))
        assertEquals(AsyncEventStatus.FAILURE, card.status)
    }

    @Test
    fun `RUNNING → FAILURE`() {
        // RUNNING should not produce SUCCESS (only EXITED+0 does)
        val card = AsyncEventCardPresenter.fromBackground(bgEvent(state = BackgroundState.RUNNING, exitCode = 0))
        assertEquals(AsyncEventStatus.FAILURE, card.status)
    }

    // ─────────────────────────────────────────────────────────────
    // Scenario 3: Label truncation at LABEL_MAX (80 chars)
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `label longer than 80 chars is truncated to exactly 80`() {
        val longLabel = "A".repeat(120)
        val card = AsyncEventCardPresenter.fromBackground(bgEvent(label = longLabel))
        assertEquals(80, card.label.length, "label must be truncated to LABEL_MAX=80")
        assertEquals("A".repeat(80), card.label)
    }

    @Test
    fun `label exactly 80 chars is kept as-is`() {
        val label80 = "B".repeat(80)
        val card = AsyncEventCardPresenter.fromBackground(bgEvent(label = label80))
        assertEquals(80, card.label.length)
        assertEquals(label80, card.label)
    }

    @Test
    fun `label shorter than 80 chars is not padded`() {
        val shortLabel = "short"
        val card = AsyncEventCardPresenter.fromBackground(bgEvent(label = shortLabel))
        assertEquals("short", card.label)
    }

    // ─────────────────────────────────────────────────────────────
    // Scenario 4: Monitor severity → status mapping (all three)
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `monitor ALERT severity → ALERT status`() {
        val card = AsyncEventCardPresenter.fromMonitor("m1", Severity.ALERT, "boom", 100L)
        assertEquals(AsyncEventStatus.ALERT, card.status)
    }

    @Test
    fun `monitor NOTABLE severity → NOTABLE status`() {
        val card = AsyncEventCardPresenter.fromMonitor("m1", Severity.NOTABLE, "notice", 100L)
        assertEquals(AsyncEventStatus.NOTABLE, card.status)
    }

    @Test
    fun `monitor INFO severity → NOTABLE status (else branch)`() {
        // INFO is not ALERT → falls into the else branch → should produce NOTABLE
        val card = AsyncEventCardPresenter.fromMonitor("m1", Severity.INFO, "info line", 100L)
        assertEquals(AsyncEventStatus.NOTABLE, card.status)
    }

    // ─────────────────────────────────────────────────────────────
    // Scenario 5: Monitor single vs multi-line summary
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `monitor single-line text → summary is that line (up to LABEL_MAX)`() {
        val text = "Single line event"
        val card = AsyncEventCardPresenter.fromMonitor("m1", Severity.NOTABLE, text, 100L)
        assertEquals(text, card.summary)
        assertEquals(text, card.details)
    }

    @Test
    fun `monitor multi-line text → summary is count-events form and details is full text`() {
        val text = "line one\nline two\nline three"
        val card = AsyncEventCardPresenter.fromMonitor("m1", Severity.NOTABLE, text, 100L)
        // 3 non-blank lines → summary should be "3 events"
        assertEquals("3 events", card.summary)
        assertEquals(text, card.details, "details must retain the full text")
    }

    @Test
    fun `monitor text with blank lines — only non-blank lines count for summary`() {
        // Two non-blank lines with one blank in between
        val text = "event A\n\nevent B"
        val card = AsyncEventCardPresenter.fromMonitor("m1", Severity.NOTABLE, text, 100L)
        // 2 non-blank lines → "2 events" (multi-line path)
        assertEquals("2 events", card.summary)
    }

    @Test
    fun `monitor single-line summary is truncated at 80 chars`() {
        val longLine = "X".repeat(120)
        val card = AsyncEventCardPresenter.fromMonitor("m1", Severity.NOTABLE, longLine, 100L)
        // Single non-blank line → summary is the line truncated to LABEL_MAX
        assertEquals(80, card.summary.length)
        assertEquals("X".repeat(80), card.summary)
    }

    // ─────────────────────────────────────────────────────────────
    // Scenario 6: Round-trip JSON serialization with UPPERCASE enum names
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `AsyncEventCardData round-trips via Json encode-decode`() {
        val card = AsyncEventCardPresenter.fromBackground(bgEvent())
        val encoded = json.encodeToString(AsyncEventCardData.serializer(), card)
        val decoded = json.decodeFromString(AsyncEventCardData.serializer(), encoded)
        assertEquals(card, decoded, "decoded card must equal original")
    }

    @Test
    fun `encoded JSON contains UPPERCASE BACKGROUND and SUCCESS enum names`() {
        val card = AsyncEventCardPresenter.fromBackground(bgEvent(state = BackgroundState.EXITED, exitCode = 0))
        val encoded = json.encodeToString(AsyncEventCardData.serializer(), card)
        assertTrue(encoded.contains("\"BACKGROUND\""), "kind must serialize as BACKGROUND")
        assertTrue(encoded.contains("\"SUCCESS\""), "status must serialize as SUCCESS")
    }

    @Test
    fun `encoded JSON contains UPPERCASE MONITOR and ALERT enum names`() {
        val card = AsyncEventCardPresenter.fromMonitor("m1", Severity.ALERT, "boom", 1L)
        val encoded = json.encodeToString(AsyncEventCardData.serializer(), card)
        assertTrue(encoded.contains("\"MONITOR\""), "kind must serialize as MONITOR")
        assertTrue(encoded.contains("\"ALERT\""), "status must serialize as ALERT")
    }

    @Test
    fun `encoded JSON contains UPPERCASE FAILURE and NOTABLE enum names`() {
        val cardFailure = AsyncEventCardPresenter.fromBackground(bgEvent(state = BackgroundState.KILLED))
        val encodedFailure = json.encodeToString(AsyncEventCardData.serializer(), cardFailure)
        assertTrue(encodedFailure.contains("\"FAILURE\""), "status must serialize as FAILURE")

        val cardNotable = AsyncEventCardPresenter.fromMonitor("m1", Severity.NOTABLE, "note", 1L)
        val encodedNotable = json.encodeToString(AsyncEventCardData.serializer(), cardNotable)
        assertTrue(encodedNotable.contains("\"NOTABLE\""), "status must serialize as NOTABLE")
    }

    // ─────────────────────────────────────────────────────────────
    // Scenario 7: Dedup by id in cardsToAppend
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `item whose card id is in existingIds is skipped`() {
        val card = AsyncEventCardData(
            id = "bg-test-1111",
            kind = AsyncEventKind.BACKGROUND,
            sourceId = "bg1",
            label = "test",
            status = AsyncEventStatus.SUCCESS,
            summary = "exit 0 · 3s",
            details = "tail",
            timestamp = 1111L,
        )
        val msg = queuedMsgWithCard("q1", card)
        val result = AsyncEventResumeSynthesis.cardsToAppend(listOf(msg), setOf("bg-test-1111"))
        assertTrue(result.isEmpty(), "card already in existingIds must be skipped")
    }

    @Test
    fun `item whose card id is NOT in existingIds is returned`() {
        val card = AsyncEventCardData(
            id = "bg-fresh-2222",
            kind = AsyncEventKind.BACKGROUND,
            sourceId = "bg2",
            label = "test",
            status = AsyncEventStatus.SUCCESS,
            summary = "exit 0 · 1s",
            details = "tail",
            timestamp = 2222L,
        )
        val msg = queuedMsgWithCard("q1", card)
        val result = AsyncEventResumeSynthesis.cardsToAppend(listOf(msg), setOf("some-other-id"))
        assertEquals(1, result.size)
        assertEquals("bg-fresh-2222", result[0].id)
    }

    // ─────────────────────────────────────────────────────────────
    // Scenario 8: Mixed batch — with card, without card, malformed JSON
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `mixed batch returns only valid-and-fresh cards without throwing`() {
        val validCard = AsyncEventCardData(
            id = "bg-valid-100",
            kind = AsyncEventKind.BACKGROUND,
            sourceId = "bg1",
            label = "build",
            status = AsyncEventStatus.SUCCESS,
            summary = "exit 0 · 2s",
            details = "done",
            timestamp = 100L,
        )
        val msgWithCard = queuedMsgWithCard("q1", validCard)
        val msgWithoutCard = queuedMsgWithoutCard("q2")
        val msgMalformed = QueuedMessage(
            id = "q3",
            kind = QueueSourceKind.BACKGROUND,
            body = "body",
            timestamp = 1000L,
            priority = 50,
            meta = mapOf("card" to "{{{NOT_VALID_JSON"),
        )

        val result = AsyncEventResumeSynthesis.cardsToAppend(
            listOf(msgWithCard, msgWithoutCard, msgMalformed),
            emptySet(),
        )

        assertEquals(1, result.size, "only the valid card should be returned")
        assertEquals("bg-valid-100", result[0].id)
    }

    // ─────────────────────────────────────────────────────────────
    // Scenario 9: Order preserved
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `multiple valid fresh cards come back in input order`() {
        fun makeCard(id: String) = AsyncEventCardData(
            id = id,
            kind = AsyncEventKind.BACKGROUND,
            sourceId = "bg",
            label = "test",
            status = AsyncEventStatus.SUCCESS,
            summary = "done",
            details = "",
            timestamp = 1L,
        )

        val ids = listOf("card-C", "card-A", "card-B")
        val messages = ids.mapIndexed { i, id -> queuedMsgWithCard("q$i", makeCard(id)) }
        val result = AsyncEventResumeSynthesis.cardsToAppend(messages, emptySet())

        assertEquals(ids, result.map { it.id }, "cards must come back in input order")
    }

    // ─────────────────────────────────────────────────────────────
    // Scenario 10: Empty inputs
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `empty items list returns empty result`() {
        val result = AsyncEventResumeSynthesis.cardsToAppend(emptyList(), emptySet())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `all-deduped items return empty result`() {
        val card = AsyncEventCardData(
            id = "bg-deduped-1",
            kind = AsyncEventKind.BACKGROUND,
            sourceId = "bg",
            label = "test",
            status = AsyncEventStatus.FAILURE,
            summary = "killed",
            details = "",
            timestamp = 1L,
        )
        val msg = queuedMsgWithCard("q1", card)
        val result = AsyncEventResumeSynthesis.cardsToAppend(listOf(msg), setOf("bg-deduped-1"))
        assertTrue(result.isEmpty())
    }

    // ─────────────────────────────────────────────────────────────
    // Scenario 11: Realistic round-trip using AsyncEventCardPresenter as producer
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `realistic round-trip via AsyncEventCardPresenter fromBackground through cardsToAppend`() {
        val event = bgEvent(bgId = "proc99", exitCode = 0, state = BackgroundState.EXITED, occurredAt = 55555L, runtimeMs = 7200L)
        val producedCard = AsyncEventCardPresenter.fromBackground(event)

        // Simulate how BackgroundCompletionCoordinator produces the QueuedMessage
        val encoded = json.encodeToString(AsyncEventCardData.serializer(), producedCard)
        val msg = QueuedMessage(
            id = "queue-msg-1",
            kind = QueueSourceKind.BACKGROUND,
            body = "Background process completed",
            timestamp = 55555L,
            priority = 50,
            meta = mapOf("card" to encoded),
        )

        val result = AsyncEventResumeSynthesis.cardsToAppend(listOf(msg), emptySet())

        assertEquals(1, result.size)
        val reconstructed = result[0]

        // Verify key fields are faithfully reconstructed
        assertEquals(producedCard.id, reconstructed.id, "id must round-trip")
        assertEquals("bg-proc99-55555", reconstructed.id, "id must follow bg-{bgId}-{occurredAt} pattern")
        assertEquals(AsyncEventKind.BACKGROUND, reconstructed.kind)
        assertEquals(AsyncEventStatus.SUCCESS, reconstructed.status)
        assertEquals("proc99", reconstructed.sourceId)
        assertEquals("exit 0 · 7s", reconstructed.summary, "summary must include state label and runtime in seconds")
        assertEquals("tail output", reconstructed.details)
        assertEquals(55555L, reconstructed.timestamp)
        assertNotNull(reconstructed)
    }

    @Test
    fun `realistic round-trip via AsyncEventCardPresenter fromMonitor through cardsToAppend`() {
        val monitorId = "shell-abc123"
        val ts = 77777L
        val text = "error detected in process\nOOM kill signal"
        val producedCard = AsyncEventCardPresenter.fromMonitor(monitorId, Severity.ALERT, text, ts)

        val encoded = json.encodeToString(AsyncEventCardData.serializer(), producedCard)
        val msg = QueuedMessage(
            id = "queue-msg-2",
            kind = QueueSourceKind.MONITOR,
            body = "Monitor event",
            timestamp = ts,
            priority = 50,
            meta = mapOf("card" to encoded),
        )

        val result = AsyncEventResumeSynthesis.cardsToAppend(listOf(msg), emptySet())

        assertEquals(1, result.size)
        val reconstructed = result[0]

        assertEquals("mon-$monitorId-$ts", reconstructed.id)
        assertEquals(AsyncEventKind.MONITOR, reconstructed.kind)
        assertEquals(AsyncEventStatus.ALERT, reconstructed.status)
        assertEquals(monitorId, reconstructed.sourceId)
        assertEquals("2 events", reconstructed.summary, "two non-blank lines → '2 events'")
        assertEquals(text, reconstructed.details, "details must retain full text")
    }
}
