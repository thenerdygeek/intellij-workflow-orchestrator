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

    @Test
    fun `session end prompt preserves first and last messages for long sessions`() {
        // Simulate a long session: initial goal + many middle messages + final state
        val messages = buildList {
            add("user: INITIAL GOAL — fix the authentication bug")
            add("assistant: Let me investigate")
            repeat(50) { add("assistant: middle message $it that should be dropped") }
            add("user: Actually it was a CORS issue, not auth")
            add("assistant: Understood, updating fix")
        }

        val prompt = ExtractionPrompts.sessionEndPrompt(messages, emptyMap())

        // First message (the goal) should be preserved
        assertTrue(prompt.contains("INITIAL GOAL"), "Goal from first message should be preserved")
        // Last message should be preserved
        assertTrue(
            prompt.contains("Updating fix") || prompt.contains("CORS issue"),
            "Recent state from last messages should be preserved"
        )
        // Middle messages should be dropped. With 54 total messages and first 5 + last 35
        // preserved, the dropped indices are 5..18 which correspond to "middle message 3"
        // through "middle message 16". "middle message 10" is solidly in the dropped range.
        assertFalse(
            prompt.contains("middle message 10"),
            "Middle messages should be dropped"
        )
        // There should be an omission marker
        assertTrue(
            prompt.contains("omitted") || prompt.contains("..."),
            "Prompt should indicate omitted messages"
        )
    }
}
