package com.workflow.orchestrator.agent.context

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class CoreMemoryTest {

    @TempDir
    lateinit var tempDir: File
    private lateinit var memory: CoreMemory

    @BeforeEach
    fun setup() {
        memory = CoreMemory(File(tempDir, "core-memory.json"), maxSizeChars = 500)
    }

    @Test
    fun `append and render entry`() {
        assertNull(memory.append("build-system", "Gradle with Kotlin DSL"))
        val rendered = memory.render()
        assertNotNull(rendered)
        assertTrue(rendered!!.contains("[build-system]"))
        assertTrue(rendered.contains("Gradle with Kotlin DSL"))
    }

    @Test
    fun `render returns null when empty`() {
        assertNull(memory.render())
    }

    @Test
    fun `append multiple entries`() {
        memory.append("key1", "value1")
        memory.append("key2", "value2")
        assertEquals(2, memory.entryCount())
        val rendered = memory.render()!!
        assertTrue(rendered.contains("[key1]"))
        assertTrue(rendered.contains("[key2]"))
    }

    @Test
    fun `append overwrites existing key`() {
        memory.append("key", "old value")
        memory.append("key", "new value")
        assertEquals(1, memory.entryCount())
        assertTrue(memory.render()!!.contains("new value"))
        assertFalse(memory.render()!!.contains("old value"))
    }

    @Test
    fun `append rejects when full`() {
        // Fill up to near capacity (500 char limit)
        memory.append("big", "x".repeat(480))
        val error = memory.append("another-big-key", "y".repeat(100))
        assertNotNull(error)
        assertTrue(error!!.contains("full"))
    }

    @Test
    fun `replace existing key`() {
        memory.append("key", "old")
        assertNull(memory.replace("key", "new"))
        assertTrue(memory.render()!!.contains("new"))
    }

    @Test
    fun `replace nonexistent key returns error`() {
        val error = memory.replace("missing", "value")
        assertNotNull(error)
        assertTrue(error!!.contains("not found"))
    }

    @Test
    fun `remove entry`() {
        memory.append("key", "value")
        assertTrue(memory.remove("key"))
        assertEquals(0, memory.entryCount())
        assertNull(memory.render())
    }

    @Test
    fun `remove nonexistent returns false`() {
        assertFalse(memory.remove("missing"))
    }

    @Test
    fun `remaining capacity decreases with entries`() {
        val initial = memory.remainingCapacity()
        memory.append("key", "value")
        assertTrue(memory.remainingCapacity() < initial)
    }

    @Test
    fun `persists across instances`() {
        memory.append("persistent", "survives restart")
        val reloaded = CoreMemory(File(tempDir, "core-memory.json"), maxSizeChars = 500)
        val rendered = reloaded.render()
        assertNotNull(rendered)
        assertTrue(rendered!!.contains("survives restart"))
    }

    @Test
    fun `getEntries returns map`() {
        memory.append("a", "1")
        memory.append("b", "2")
        val entries = memory.getEntries()
        assertEquals("1", entries["a"])
        assertEquals("2", entries["b"])
    }

    @Test
    fun `key and value are trimmed and capped`() {
        memory.append("  key  ", "  value  ")
        assertTrue(memory.getEntries().containsKey("key"))
        assertEquals("value", memory.getEntries()["key"])
    }

    @Test
    fun `blank key rejected`() {
        val error = memory.append("", "value")
        assertNotNull(error)
        assertTrue(error!!.contains("blank"))
    }
}
