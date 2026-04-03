package com.workflow.orchestrator.agent.context.events

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class EventSerializerSteeringTest {

    @Test
    fun `UserSteeringAction serializes and deserializes`() {
        val action = UserSteeringAction(
            content = "Focus on the API layer instead",
            id = 42,
            timestamp = Instant.parse("2026-04-03T10:00:00Z"),
            source = EventSource.USER
        )
        val json = EventSerializer.serialize(action)
        assertTrue(json.contains("\"type\":\"user_steering\""))
        assertTrue(json.contains("Focus on the API layer instead"))

        val restored = EventSerializer.deserialize(json)
        assertTrue(restored is UserSteeringAction)
        val steering = restored as UserSteeringAction
        assertEquals("Focus on the API layer instead", steering.content)
        assertEquals(42, steering.id)
        assertEquals(EventSource.USER, steering.source)
    }

    @Test
    fun `UserSteeringAction is not compression-proof`() {
        // UserSteeringAction should NOT be in NEVER_FORGET_TYPES
        assertFalse(NEVER_FORGET_TYPES.contains(UserSteeringAction::class))
    }

    @Test
    fun `UserSteeringAction roundtrip preserves timestamp`() {
        val ts = Instant.parse("2026-04-03T15:30:00Z")
        val action = UserSteeringAction(content = "test", id = 7, timestamp = ts, source = EventSource.USER)
        val json = EventSerializer.serialize(action)
        val restored = EventSerializer.deserialize(json) as UserSteeringAction
        assertEquals(ts, restored.timestamp)
    }
}
