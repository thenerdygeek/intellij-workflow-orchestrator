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

    @Test
    fun `BackgroundProcessSnapshot round-trips with null and non-null exitCode`() {
        val running = BackgroundProcessSnapshot(
            bgId = "bg_r",
            kind = "run_command",
            label = "sleep 60",
            state = BackgroundState.RUNNING,
            startedAt = 1_700_000_000_000,
            exitCode = null,
            outputBytes = 0,
            runtimeMs = 5000,
        )
        val exited = running.copy(
            state = BackgroundState.EXITED,
            exitCode = 137,
            outputBytes = 4096,
            runtimeMs = 12_345,
        )
        listOf(running, exited).forEach { original ->
            val json = Json.encodeToString(BackgroundProcessSnapshot.serializer(), original)
            val back = Json.decodeFromString(BackgroundProcessSnapshot.serializer(), json)
            assertEquals(original, back)
        }
    }
}
