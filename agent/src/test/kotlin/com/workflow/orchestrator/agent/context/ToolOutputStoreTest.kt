package com.workflow.orchestrator.agent.context

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ToolOutputStoreTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `save stores content to disk`() {
        val store = ToolOutputStore(tempDir)
        val path = store.save("call-1", "line 1\nline 2\nline 3")
        assertNotNull(path)
        assertTrue(File(path!!).exists())
        assertEquals("line 1\nline 2\nline 3", File(path).readText())
    }

    @Test
    fun `capContent truncates at 2000 lines`() {
        val store = ToolOutputStore(tempDir)
        val longContent = (1..3000).joinToString("\n") { "line $it" }
        val capped = store.capContent(longContent, "/tmp/output.txt")
        assertTrue(capped.lines().size <= 2005) // 2000 + truncation message
        assertTrue(capped.contains("[Truncated at 2000 lines"))
        assertTrue(capped.contains("Full output saved to: /tmp/output.txt"))
    }

    @Test
    fun `capContent passes through small content`() {
        val store = ToolOutputStore(tempDir)
        val small = "hello world"
        assertEquals(small, store.capContent(small, null))
    }

    @Test
    fun `getPath returns saved path`() {
        val store = ToolOutputStore(tempDir)
        store.save("call-1", "content")
        assertNotNull(store.getPath("call-1"))
        assertNull(store.getPath("nonexistent"))
    }

    @Test
    fun `save with null sessionDir returns null`() {
        val store = ToolOutputStore(null)
        assertNull(store.save("call-1", "content"))
    }

    @Test
    fun `capContent without diskPath omits path hint`() {
        val store = ToolOutputStore(tempDir)
        val longContent = (1..3000).joinToString("\n") { "line $it" }
        val capped = store.capContent(longContent, null)
        assertTrue(capped.contains("[Truncated at 2000 lines"))
        assertFalse(capped.contains("Full output saved to:"))
    }
}
