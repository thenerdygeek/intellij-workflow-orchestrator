package com.workflow.orchestrator.agent.tools

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CompletionDataTest {

    // Use the same Json configuration as other round-trip tests in this module
    // (ignoreUnknownKeys for forward-compat, encodeDefaults=true so null fields appear
    // consistently regardless of whether they carry a non-null value).
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `round-trip JSON serialisation preserves all fields`() {
        val original = CompletionData(
            kind = CompletionKind.HEADS_UP,
            result = "x",
            verifyHow = "y",
            discovery = "z"
        )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<CompletionData>(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `enum serialises as lowercase snake_case`() {
        val data = CompletionData(kind = CompletionKind.HEADS_UP, result = "x")
        val encoded = json.encodeToString(data)
        assertTrue(encoded.contains("\"heads_up\""), "Expected 'heads_up' in JSON, got: $encoded")
        assertFalse(encoded.contains("\"HEADS_UP\""), "Must not contain uppercase 'HEADS_UP', got: $encoded")
    }

    @Test
    fun `null optional fields encoded according to project JSON convention`() {
        // The module uses encodeDefaults = true (MessageStateHandler, SessionMigrator, etc.),
        // so null optional fields ARE included in the output as explicit null values.
        val data = CompletionData(kind = CompletionKind.DONE, result = "done")
        val encoded = json.encodeToString(data)
        // With encodeDefaults = true, null fields appear as "verifyHow":null,"discovery":null
        assertTrue(encoded.contains("\"verifyHow\""), "verifyHow key must appear in encoded JSON")
        assertTrue(encoded.contains("\"discovery\""), "discovery key must appear in encoded JSON")
        // The values should be null (not omitted, not some other value)
        val decoded = json.decodeFromString<CompletionData>(encoded)
        assertEquals(null, decoded.verifyHow)
        assertEquals(null, decoded.discovery)
    }
}
