package com.workflow.orchestrator.agent.memory.auto

import com.workflow.orchestrator.agent.memory.ArchivalMemory
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class AutoMemoryManagerTest {

    @TempDir lateinit var tempDir: Path
    private lateinit var archival: ArchivalMemory
    private lateinit var manager: AutoMemoryManager

    @BeforeEach
    fun setUp() {
        archival = ArchivalMemory(tempDir.resolve("archival/store.json").toFile())
        manager = AutoMemoryManager(archivalMemory = archival)
    }

    @Test
    fun `retrieves relevant memories for first message`() {
        archival.insert("CORS fix: add allowedOrigins to SecurityConfig", listOf("error", "cors", "spring"))

        val recalled = manager.onSessionStart("Fix the CORS error in SecurityConfig")

        assertNotNull(recalled)
        assertTrue(recalled!!.contains("CORS"))
        assertTrue(recalled.contains("<recalled_memory>"))
    }

    @Test
    fun `returns null when nothing relevant`() {
        val recalled = manager.onSessionStart("Refactor the payment module")
        assertNull(recalled)
    }
}
