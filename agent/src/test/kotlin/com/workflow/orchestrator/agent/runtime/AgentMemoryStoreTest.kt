package com.workflow.orchestrator.agent.runtime

import com.workflow.orchestrator.core.util.ProjectIdentifier
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AgentMemoryStoreTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var store: AgentMemoryStore

    @BeforeEach
    fun setup() {
        store = AgentMemoryStore(tempDir)
    }

    @Test
    fun `saveMemory creates memory file and updates index`() {
        store.saveMemory("Build Config", "Always use Gradle wrapper for builds")

        val memoryDir = File(ProjectIdentifier.agentDir(tempDir.absolutePath), "memory")
        val topicFile = File(memoryDir, "build-config.md")
        val indexFile = File(memoryDir, "MEMORY.md")

        assertTrue(topicFile.exists())
        assertTrue(indexFile.exists())

        val topicContent = topicFile.readText()
        assertTrue(topicContent.startsWith("# Build Config"))
        assertTrue(topicContent.contains("Always use Gradle wrapper for builds"))

        val indexContent = indexFile.readText()
        assertTrue(indexContent.contains("# Agent Memory"))
        assertTrue(indexContent.contains("[build-config](build-config.md)"))
        assertTrue(indexContent.contains("Always use Gradle wrapper for builds"))
    }

    @Test
    fun `loadMemories returns formatted string for system prompt`() {
        store.saveMemory("Auth Setup", "Use Bearer tokens for all services")
        store.saveMemory("Deploy Notes", "Always tag images before deploy")

        val result = store.loadMemories()

        assertNotNull(result)
        assertTrue(result!!.contains("# Agent Memory"))
        assertTrue(result.contains("Auth Setup"))
        assertTrue(result.contains("Use Bearer tokens for all services"))
        assertTrue(result.contains("Deploy Notes"))
        assertTrue(result.contains("Always tag images before deploy"))
    }

    @Test
    fun `loadMemories returns null when no memories exist`() {
        assertNull(store.loadMemories())
    }

    @Test
    fun `loadMemories respects maxLines limit`() {
        // Create a memory with many lines
        val longContent = (1..50).joinToString("\n") { "Line $it of important content" }
        store.saveMemory("Long Topic", longContent)

        val result = store.loadMemories(maxLines = 10)

        assertNotNull(result)
        val lineCount = result!!.lines().size
        // Should be capped around maxLines (10 lines total across index + content)
        assertTrue(lineCount <= 12, "Expected at most ~12 lines but got $lineCount")
    }

    @Test
    fun `saveMemory overwrites existing memory with same topic`() {
        store.saveMemory("Config", "Version 1 content")
        store.saveMemory("Config", "Version 2 content")

        val memoryDir = File(ProjectIdentifier.agentDir(tempDir.absolutePath), "memory")
        val topicFile = File(memoryDir, "config.md")
        val content = topicFile.readText()

        assertFalse(content.contains("Version 1 content"))
        assertTrue(content.contains("Version 2 content"))
    }

    @Test
    fun `deleteMemory removes file and index entry`() {
        store.saveMemory("Temporary", "This will be deleted")
        store.saveMemory("Permanent", "This stays")

        store.deleteMemory("Temporary")

        val memoryDir = File(ProjectIdentifier.agentDir(tempDir.absolutePath), "memory")
        val deletedFile = File(memoryDir, "temporary.md")
        val indexFile = File(memoryDir, "MEMORY.md")

        assertFalse(deletedFile.exists())

        val indexContent = indexFile.readText()
        assertFalse(indexContent.contains("temporary"))
        assertTrue(indexContent.contains("permanent"))
    }

    @Test
    fun `listMemories returns all topics`() {
        store.saveMemory("Alpha", "First topic")
        store.saveMemory("Beta", "Second topic")
        store.saveMemory("Gamma", "Third topic")

        val topics = store.listMemories()

        assertEquals(3, topics.size)
        assertTrue(topics.containsAll(listOf("alpha", "beta", "gamma")))
    }
}
