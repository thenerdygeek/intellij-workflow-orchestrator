package com.workflow.orchestrator.agent.session

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AsyncEventCardDataTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `AsyncEventCardData round-trips and enum names are UPPERCASE`() {
        val card = AsyncEventCardData(
            id = "bg-bg7-100", kind = AsyncEventKind.BACKGROUND, sourceId = "bg7",
            label = "npm run build", status = AsyncEventStatus.SUCCESS,
            summary = "exit 0 · 12s", details = "Done in 12s", timestamp = 100L, spillPath = null,
        )
        val encoded = json.encodeToString(AsyncEventCardData.serializer(), card)
        assertTrue(encoded.contains("\"BACKGROUND\""), "kind must serialize as UPPERCASE name")
        assertTrue(encoded.contains("\"SUCCESS\""), "status must serialize as UPPERCASE name")
        assertEquals(card, json.decodeFromString(AsyncEventCardData.serializer(), encoded))
    }

    @Test
    fun `UiMessage carries asyncEventData and ASYNC_EVENT say`() {
        val m = UiMessage(
            ts = 1L, type = UiMessageType.SAY, say = UiSay.ASYNC_EVENT,
            asyncEventData = AsyncEventCardData(
                "mon-m1-2", AsyncEventKind.MONITOR, "m1", "shell", AsyncEventStatus.ALERT,
                "2 new errors", "err1\nerr2", 2L, null,
            ),
        )
        val round = json.decodeFromString(UiMessage.serializer(), json.encodeToString(UiMessage.serializer(), m))
        assertEquals(UiSay.ASYNC_EVENT, round.say)
        assertEquals("m1", round.asyncEventData?.sourceId)
    }
}
