package com.workflow.orchestrator.agent.loop.queue

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class QueuedMessageTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `QueuedMessage round-trips through JSON preserving all fields`() {
        val msg = QueuedMessage(
            id = "bg-1",
            kind = QueueSourceKind.BACKGROUND,
            body = "[BACKGROUND COMPLETION] exit 0",
            timestamp = 1717_000_000_000L,
            priority = 50,
            coalesceKey = "bgid-7",
            meta = mapOf("bgId" to "bgid-7"),
        )
        val encoded = json.encodeToString(QueuedMessage.serializer(), msg)
        val decoded = json.decodeFromString(QueuedMessage.serializer(), encoded)
        assertEquals(msg, decoded)
    }

    @Test
    fun `list of QueuedMessage serializes`() {
        val list = listOf(
            QueuedMessage("u1", QueueSourceKind.USER, "hi", 1L, 100, null, emptyMap()),
            QueuedMessage("m1", QueueSourceKind.MONITOR, "alert", 2L, 30, "mon-3", emptyMap()),
        )
        val ser = ListSerializer(QueuedMessage.serializer())
        assertEquals(list, json.decodeFromString(ser, json.encodeToString(ser, list)))
    }
}
