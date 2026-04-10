package com.workflow.orchestrator.agent.memory.auto

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ExtractionModelsTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `ExtractionResult round-trips through JSON`() {
        val result = ExtractionResult(
            coreMemoryUpdates = listOf(
                CoreMemoryUpdate(
                    block = "user",
                    action = UpdateAction.APPEND,
                    content = "Prefers Kotlin idioms"
                ),
                CoreMemoryUpdate(
                    block = "project",
                    action = UpdateAction.REPLACE,
                    oldContent = "Auth migration in progress",
                    content = "Auth migration complete"
                )
            ),
            archivalInserts = listOf(
                ArchivalInsert(
                    content = "CORS error fix: add allowedOrigins to SecurityConfig",
                    tags = listOf("error", "spring", "cors")
                )
            )
        )

        val encoded = json.encodeToString(result)
        val decoded = json.decodeFromString<ExtractionResult>(encoded)

        assertEquals(2, decoded.coreMemoryUpdates.size)
        assertEquals("user", decoded.coreMemoryUpdates[0].block)
        assertEquals(UpdateAction.APPEND, decoded.coreMemoryUpdates[0].action)
        assertEquals("Prefers Kotlin idioms", decoded.coreMemoryUpdates[0].content)

        assertEquals(UpdateAction.REPLACE, decoded.coreMemoryUpdates[1].action)
        assertEquals("Auth migration in progress", decoded.coreMemoryUpdates[1].oldContent)

        assertEquals(1, decoded.archivalInserts.size)
        assertEquals(3, decoded.archivalInserts[0].tags.size)
    }

    @Test
    fun `ExtractionResult handles empty lists`() {
        val result = ExtractionResult(
            coreMemoryUpdates = emptyList(),
            archivalInserts = emptyList()
        )
        val encoded = json.encodeToString(result)
        val decoded = json.decodeFromString<ExtractionResult>(encoded)
        assertTrue(decoded.coreMemoryUpdates.isEmpty())
        assertTrue(decoded.archivalInserts.isEmpty())
    }

    @Test
    fun `ExtractionResult parses from LLM-style JSON with snake_case`() {
        val llmJson = """{"core_memory_updates":[{"block":"patterns","action":"append","content":"Use TDD"}],"archival_inserts":[]}"""
        val decoded = json.decodeFromString<ExtractionResult>(llmJson)
        assertEquals(1, decoded.coreMemoryUpdates.size)
        assertEquals("patterns", decoded.coreMemoryUpdates[0].block)
    }
}
