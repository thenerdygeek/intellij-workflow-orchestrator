package com.workflow.orchestrator.agent.context

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class WorkingSetTest {

    @Test
    fun `recordRead tracks file with line count`() {
        val ws = WorkingSet(maxFiles = 5)
        ws.recordRead("/src/Main.kt", 100, "package com.example")

        assertEquals(1, ws.size)
        val summary = ws.getSummary()
        assertTrue(summary.contains("Main.kt"))
        assertTrue(summary.contains("100 lines"))
    }

    @Test
    fun `recordEdit marks file as edited`() {
        val ws = WorkingSet(maxFiles = 5)
        ws.recordRead("/src/Main.kt", 100)
        ws.recordEdit("/src/Main.kt")

        val summary = ws.getSummary()
        assertTrue(summary.contains("[EDITED]"))
    }

    @Test
    fun `recordEdit on unknown file creates entry`() {
        val ws = WorkingSet(maxFiles = 5)
        ws.recordEdit("/src/New.kt")

        assertEquals(1, ws.size)
        val edited = ws.getEditedFiles()
        assertEquals(listOf("/src/New.kt"), edited)
    }

    @Test
    fun `eviction removes oldest entries when over limit`() {
        val ws = WorkingSet(maxFiles = 3)
        ws.recordRead("/a.kt", 10)
        ws.recordRead("/b.kt", 20)
        ws.recordRead("/c.kt", 30)
        ws.recordRead("/d.kt", 40) // should evict /a.kt

        assertEquals(3, ws.size)
        val summary = ws.getSummary()
        assertFalse(summary.contains("a.kt"))
        assertTrue(summary.contains("d.kt"))
    }

    @Test
    fun `LRU access order promotes recently accessed files`() {
        val ws = WorkingSet(maxFiles = 3)
        ws.recordRead("/a.kt", 10)
        ws.recordRead("/b.kt", 20)
        ws.recordRead("/c.kt", 30)

        // Access /a.kt again — promotes it to most recent
        ws.recordRead("/a.kt", 10)

        // Adding /d.kt should evict /b.kt (least recently used), not /a.kt
        ws.recordRead("/d.kt", 40)

        assertEquals(3, ws.size)
        val summary = ws.getSummary()
        assertTrue(summary.contains("a.kt"))
        assertFalse(summary.contains("b.kt"))
    }

    @Test
    fun `getSummary is empty when no files tracked`() {
        val ws = WorkingSet()
        assertEquals("", ws.getSummary())
    }

    @Test
    fun `getEditedFiles returns only edited paths`() {
        val ws = WorkingSet()
        ws.recordRead("/read.kt", 50)
        ws.recordRead("/edited.kt", 30)
        ws.recordEdit("/edited.kt")
        ws.recordEdit("/new-edit.kt")

        val edited = ws.getEditedFiles()
        assertEquals(2, edited.size)
        assertTrue(edited.contains("/edited.kt"))
        assertTrue(edited.contains("/new-edit.kt"))
        assertFalse(edited.contains("/read.kt"))
    }

    @Test
    fun `clear removes all entries`() {
        val ws = WorkingSet()
        ws.recordRead("/a.kt", 10)
        ws.recordRead("/b.kt", 20)

        ws.clear()
        assertTrue(ws.isEmpty())
        assertEquals(0, ws.size)
    }

    @Test
    fun `preview is truncated to 500 chars`() {
        val ws = WorkingSet()
        val longPreview = "x".repeat(1000)
        ws.recordRead("/a.kt", 10, longPreview)

        val files = ws.getFiles()
        assertEquals(500, files.first().preview.length)
    }

    @Test
    fun `recordRead preserves edited flag`() {
        val ws = WorkingSet()
        ws.recordEdit("/a.kt")
        ws.recordRead("/a.kt", 50)

        val files = ws.getFiles()
        assertTrue(files.first().wasEdited)
    }
}
