package com.workflow.orchestrator.agent.runtime

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ContextRotationTest {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `RotationState serializes to JSON`() {
        val state = RotationState(
            goal = "Refactor auth module",
            accomplishments = "Completed steps 1-3: extracted interface, moved to core, updated tests",
            remainingWork = "Steps 4-5: wire new service in agent tools, update system prompt",
            modifiedFiles = listOf("core/AuthService.kt", "agent/tools/AuthTool.kt"),
            guardrails = listOf("Always re-read AuthService.kt before editing"),
            factsSnapshot = listOf("AuthService moved to core/services/", "3 tests updated in agent module")
        )

        val jsonStr = json.encodeToString(RotationState.serializer(), state)
        assertTrue(jsonStr.contains("Refactor auth module"))
        assertTrue(jsonStr.contains("AuthService.kt"))
    }

    @Test
    fun `RotationState round-trips through JSON`() {
        val state = RotationState(
            goal = "Fix build",
            accomplishments = "Found the issue",
            remainingWork = "Apply fix",
            modifiedFiles = listOf("build.gradle.kts"),
            guardrails = emptyList(),
            factsSnapshot = listOf("Build fails on missing dependency")
        )

        val jsonStr = json.encodeToString(RotationState.serializer(), state)
        val loaded = json.decodeFromString(RotationState.serializer(), jsonStr)
        assertEquals(state.goal, loaded.goal)
        assertEquals(state.modifiedFiles, loaded.modifiedFiles)
    }

    @Test
    fun `save and load rotation state from disk`() {
        val state = RotationState(
            goal = "Add caching layer",
            accomplishments = "Designed cache interface",
            remainingWork = "Implement Redis adapter",
            modifiedFiles = listOf("core/Cache.kt"),
            guardrails = listOf("Use suspend functions for cache operations"),
            factsSnapshot = emptyList()
        )

        RotationState.save(state, tempDir)
        val loaded = RotationState.load(tempDir)
        assertNotNull(loaded)
        assertEquals("Add caching layer", loaded!!.goal)
        assertEquals(listOf("core/Cache.kt"), loaded.modifiedFiles)
    }

    @Test
    fun `load returns null when file does not exist`() {
        val loaded = RotationState.load(tempDir)
        assertNull(loaded)
    }

    @Test
    fun `toContextString renders rotated context tag`() {
        val state = RotationState(
            goal = "Fix auth",
            accomplishments = "Extracted service",
            remainingWork = "Wire into agent",
            modifiedFiles = listOf("AuthService.kt"),
            guardrails = listOf("Re-read before edit"),
            factsSnapshot = listOf("Auth uses Bearer tokens")
        )

        val ctx = state.toContextString()
        assertTrue(ctx.contains("<rotated_context>"))
        assertTrue(ctx.contains("Fix auth"))
        assertTrue(ctx.contains("Extracted service"))
        assertTrue(ctx.contains("Wire into agent"))
        assertTrue(ctx.contains("AuthService.kt"))
        assertTrue(ctx.contains("Re-read before edit"))
        assertTrue(ctx.contains("</rotated_context>"))
    }

    @Test
    fun `toContextString truncated to token limit`() {
        val longFacts = (1..500).map { "Fact number $it with extra padding to consume tokens" }
        val state = RotationState(
            goal = "Big task",
            accomplishments = "Done a lot",
            remainingWork = "Still more",
            modifiedFiles = emptyList(),
            guardrails = emptyList(),
            factsSnapshot = longFacts
        )
        val ctx = state.toContextString(maxTokens = 500)
        val factCount = ctx.lines().count { it.startsWith("- Fact number") }
        assertTrue(factCount < 500)
    }
}
