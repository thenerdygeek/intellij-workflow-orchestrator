package com.workflow.orchestrator.agent.ui

import com.workflow.orchestrator.agent.loop.queue.QueuedMessage
import com.workflow.orchestrator.agent.loop.queue.QueueSourceKind
import com.workflow.orchestrator.agent.session.AsyncEventCardData
import com.workflow.orchestrator.agent.session.AsyncEventKind
import com.workflow.orchestrator.agent.session.AsyncEventStatus
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AsyncEventResumeSynthesisTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun card(id: String) = AsyncEventCardData(
        id = id,
        kind = AsyncEventKind.BACKGROUND,
        sourceId = "bg-1",
        label = "Test",
        status = AsyncEventStatus.SUCCESS,
        summary = "done",
        details = "details",
        timestamp = 1000L,
    )

    private fun msgWithCard(id: String, card: AsyncEventCardData, kind: QueueSourceKind = QueueSourceKind.BACKGROUND) =
        QueuedMessage(
            id = id,
            kind = kind,
            body = "body",
            timestamp = 1000L,
            priority = 50,
            meta = mapOf("card" to json.encodeToString(card)),
        )

    private fun msgWithoutCard(id: String, kind: QueueSourceKind = QueueSourceKind.BACKGROUND) =
        QueuedMessage(
            id = id,
            kind = kind,
            body = "body",
            timestamp = 1000L,
            priority = 50,
            meta = emptyMap(),
        )

    @Test
    fun `items with meta card produce cards`() {
        val c = card("bg-123-456")
        val msg = msgWithCard("q1", c)
        val result = AsyncEventResumeSynthesis.cardsToAppend(listOf(msg), emptySet())
        assertEquals(1, result.size)
        assertEquals("bg-123-456", result[0].id)
    }

    @Test
    fun `id already in existingIds is skipped`() {
        val c = card("bg-123-456")
        val msg = msgWithCard("q1", c)
        val result = AsyncEventResumeSynthesis.cardsToAppend(listOf(msg), setOf("bg-123-456"))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `item without meta card is ignored`() {
        val msg = msgWithoutCard("q1")
        val result = AsyncEventResumeSynthesis.cardsToAppend(listOf(msg), emptySet())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `malformed JSON in meta card is skipped`() {
        val msg = QueuedMessage(
            id = "q1",
            kind = QueueSourceKind.BACKGROUND,
            body = "body",
            timestamp = 1000L,
            priority = 50,
            meta = mapOf("card" to "not-valid-json{{{"),
        )
        val result = AsyncEventResumeSynthesis.cardsToAppend(listOf(msg), emptySet())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `multiple items some with cards some without — only card ones returned and not in existingIds`() {
        val c1 = card("id-1")
        val c2 = card("id-2")
        val c3 = card("id-3")
        val items = listOf(
            msgWithCard("q1", c1),
            msgWithoutCard("q2"),
            msgWithCard("q3", c2),
            msgWithCard("q4", c3),
        )
        val result = AsyncEventResumeSynthesis.cardsToAppend(items, setOf("id-2"))
        assertEquals(2, result.size)
        assertEquals(listOf("id-1", "id-3"), result.map { it.id })
    }

    @Test
    fun `monitor kind items also work`() {
        val c = card("mon-123-456").copy(kind = AsyncEventKind.MONITOR)
        val msg = msgWithCard("q1", c, kind = QueueSourceKind.MONITOR)
        val result = AsyncEventResumeSynthesis.cardsToAppend(listOf(msg), emptySet())
        assertEquals(1, result.size)
    }
}
