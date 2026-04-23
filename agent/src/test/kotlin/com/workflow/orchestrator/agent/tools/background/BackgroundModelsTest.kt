package com.workflow.orchestrator.agent.tools.background

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BackgroundModelsTest {
    @Test
    fun `BackgroundCompletionEvent serializes and deserializes round-trip`() {
        val event = BackgroundCompletionEvent(
            bgId = "bg_a1b2c3d4",
            kind = "run_command",
            label = "curl http://localhost:8080/foo",
            sessionId = "session-1",
            exitCode = 0,
            state = BackgroundState.EXITED,
            runtimeMs = 4231,
            tailContent = "hello\nworld\n",
            spillPath = null,
            occurredAt = 1_745_395_200_000
        )
        val json = Json.encodeToString(BackgroundCompletionEvent.serializer(), event)
        val back = Json.decodeFromString(BackgroundCompletionEvent.serializer(), json)
        assertEquals(event, back)
    }
}
