package com.workflow.orchestrator.agent.memory.auto

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ExtractionPromptsTest {

    @Test
    fun `session end prompt includes conversation snippet`() {
        val messages = listOf(
            "user: Fix the CORS error in SecurityConfig",
            "assistant: I'll read the file first...",
            "user: don't add wildcard origins, use specific domains"
        )
        val coreMemory = mapOf("user" to "Backend developer", "project" to "", "patterns" to "")

        val prompt = ExtractionPrompts.sessionEndPrompt(messages, coreMemory)

        assertTrue(prompt.contains("CORS error"))
        assertTrue(prompt.contains("don't add wildcard"))
        assertTrue(prompt.contains("Backend developer"))
        assertTrue(prompt.contains("\"core_memory_updates\""))
        assertTrue(prompt.contains("\"archival_inserts\""))
    }

    @Test
    fun `session end prompt truncates long conversations`() {
        val messages = (1..100).map { "user: Message number $it with some content padding here" }
        val prompt = ExtractionPrompts.sessionEndPrompt(messages, emptyMap())

        assertTrue(prompt.length < 15_000, "Prompt should be bounded, got ${prompt.length}")
    }

    @Test
    fun `session end prompt explains what to extract`() {
        val prompt = ExtractionPrompts.sessionEndPrompt(
            listOf("user: Fix the bug", "assistant: Done"),
            emptyMap()
        )
        assertTrue(prompt.contains("correction"))
        assertTrue(prompt.contains("pattern"))
        assertTrue(prompt.contains("decision"))
    }

    @Test
    fun `system message is concise`() {
        val sys = ExtractionPrompts.EXTRACTION_SYSTEM_MESSAGE
        assertTrue(sys.length < 500, "System message should be short for cheap model")
        assertTrue(sys.contains("JSON"))
    }
}
