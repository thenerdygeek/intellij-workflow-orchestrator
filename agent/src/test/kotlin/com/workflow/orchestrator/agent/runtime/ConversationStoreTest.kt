package com.workflow.orchestrator.agent.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class ConversationStoreTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var store: ConversationStore
    private val sessionId = "test-session-123"

    @BeforeEach
    fun setUp() {
        store = ConversationStore(sessionId, baseDir = tempDir.toFile())
    }

    @Test
    fun `saveMessage creates directory and appends to JSONL`() {
        val msg = PersistedMessage(role = "user", content = "Hello world")
        store.saveMessage(msg)

        val file = File(tempDir.toFile(), "$sessionId/messages.jsonl")
        assertTrue(file.exists())
        val lines = file.readLines().filter { it.isNotBlank() }
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("\"role\":\"user\""))
        assertTrue(lines[0].contains("Hello world"))
    }

    @Test
    fun `saveMessage appends multiple messages as separate lines`() {
        store.saveMessage(PersistedMessage(role = "user", content = "First"))
        store.saveMessage(PersistedMessage(role = "assistant", content = "Second"))
        store.saveMessage(PersistedMessage(role = "user", content = "Third"))

        val file = File(tempDir.toFile(), "$sessionId/messages.jsonl")
        val lines = file.readLines().filter { it.isNotBlank() }
        assertEquals(3, lines.size)
    }

    @Test
    fun `loadMessages reads all lines from JSONL`() {
        store.saveMessage(PersistedMessage(role = "user", content = "Hello"))
        store.saveMessage(PersistedMessage(role = "assistant", content = "Hi there"))
        store.saveMessage(PersistedMessage(role = "tool", content = "result", toolCallId = "tc_1"))

        val messages = store.loadMessages()
        assertEquals(3, messages.size)
        assertEquals("user", messages[0].role)
        assertEquals("Hello", messages[0].content)
        assertEquals("assistant", messages[1].role)
        assertEquals("Hi there", messages[1].content)
        assertEquals("tool", messages[2].role)
        assertEquals("tc_1", messages[2].toolCallId)
    }

    @Test
    fun `loadMessages returns empty list when file does not exist`() {
        val messages = store.loadMessages()
        assertTrue(messages.isEmpty())
    }

    @Test
    fun `loadMessages skips corrupted lines`() {
        val dir = File(tempDir.toFile(), sessionId)
        dir.mkdirs()
        val file = File(dir, "messages.jsonl")
        file.writeText("""
            {"role":"user","content":"Good line","timestamp":1000}
            {corrupted json line
            {"role":"assistant","content":"Also good","timestamp":2000}
        """.trimIndent() + "\n")

        val messages = store.loadMessages()
        assertEquals(2, messages.size)
        assertEquals("Good line", messages[0].content)
        assertEquals("Also good", messages[1].content)
    }

    @Test
    fun `saveMessage persists tool calls`() {
        val msg = PersistedMessage(
            role = "assistant",
            toolCalls = listOf(
                PersistedToolCall(id = "tc_1", name = "read_file", arguments = """{"path":"/foo.kt"}""")
            )
        )
        store.saveMessage(msg)

        val loaded = store.loadMessages()
        assertEquals(1, loaded.size)
        assertEquals("assistant", loaded[0].role)
        assertNull(loaded[0].content)
        assertNotNull(loaded[0].toolCalls)
        assertEquals(1, loaded[0].toolCalls!!.size)
        assertEquals("tc_1", loaded[0].toolCalls!![0].id)
        assertEquals("read_file", loaded[0].toolCalls!![0].name)
        assertEquals("""{"path":"/foo.kt"}""", loaded[0].toolCalls!![0].arguments)
    }

    @Test
    fun `saveMetadata and loadMetadata round-trip`() {
        val metadata = SessionMetadata(
            sessionId = sessionId,
            projectName = "MyProject",
            projectPath = "/path/to/project",
            title = "Fix the bug in AuthService",
            model = "anthropic::2024-10-22::claude-sonnet-4-20250514",
            createdAt = 1000L,
            lastMessageAt = 2000L,
            messageCount = 5,
            status = "active",
            totalTokens = 12345
        )
        store.saveMetadata(metadata)

        val loaded = store.loadMetadata()
        assertNotNull(loaded)
        assertEquals(sessionId, loaded!!.sessionId)
        assertEquals("MyProject", loaded.projectName)
        assertEquals("/path/to/project", loaded.projectPath)
        assertEquals("Fix the bug in AuthService", loaded.title)
        assertEquals("anthropic::2024-10-22::claude-sonnet-4-20250514", loaded.model)
        assertEquals(1000L, loaded.createdAt)
        assertEquals(2000L, loaded.lastMessageAt)
        assertEquals(5, loaded.messageCount)
        assertEquals("active", loaded.status)
        assertEquals(12345, loaded.totalTokens)
    }

    @Test
    fun `loadMetadata returns null when file does not exist`() {
        assertNull(store.loadMetadata())
    }

    @Test
    fun `saveMetadata overwrites existing metadata`() {
        val meta1 = SessionMetadata(
            sessionId = sessionId, projectName = "P", projectPath = "/p",
            title = "Old", model = "m", createdAt = 1, lastMessageAt = 1,
            messageCount = 1, status = "active"
        )
        store.saveMetadata(meta1)

        val meta2 = meta1.copy(title = "New", messageCount = 10, status = "completed")
        store.saveMetadata(meta2)

        val loaded = store.loadMetadata()
        assertEquals("New", loaded!!.title)
        assertEquals(10, loaded.messageCount)
        assertEquals("completed", loaded.status)
    }

    @Test
    fun `listSessionIds returns session directories with metadata`() {
        // Create two valid sessions
        val store1 = ConversationStore("session-a", baseDir = tempDir.toFile())
        store1.saveMetadata(SessionMetadata(
            "session-a", "P", "/p", "Title A", "m", 1, 1, 1, "active"
        ))

        val store2 = ConversationStore("session-b", baseDir = tempDir.toFile())
        store2.saveMetadata(SessionMetadata(
            "session-b", "P", "/p", "Title B", "m", 2, 2, 2, "completed"
        ))

        // Create a directory without metadata (should be excluded)
        File(tempDir.toFile(), "orphan-dir").mkdirs()

        val ids = ConversationStore.listSessionIds(baseDir = tempDir.toFile())
        assertEquals(2, ids.size)
        assertTrue(ids.contains("session-a"))
        assertTrue(ids.contains("session-b"))
        assertFalse(ids.contains("orphan-dir"))
    }

    @Test
    fun `listSessionIds returns empty when no sessions exist`() {
        val ids = ConversationStore.listSessionIds(baseDir = tempDir.toFile())
        assertTrue(ids.isEmpty())
    }

    @Test
    fun `deleteSession removes directory`() {
        store.saveMessage(PersistedMessage(role = "user", content = "test"))
        store.saveMetadata(SessionMetadata(
            sessionId, "P", "/p", "T", "m", 1, 1, 1, "active"
        ))

        val dir = File(tempDir.toFile(), sessionId)
        assertTrue(dir.exists())

        ConversationStore.deleteSession(sessionId, baseDir = tempDir.toFile())
        assertFalse(dir.exists())
    }

    @Test
    fun `deleteSession is no-op when session does not exist`() {
        // Should not throw
        ConversationStore.deleteSession("nonexistent", baseDir = tempDir.toFile())
    }

    @Test
    fun `timestamp is auto-populated`() {
        val before = System.currentTimeMillis()
        store.saveMessage(PersistedMessage(role = "user", content = "test"))
        val after = System.currentTimeMillis()

        val loaded = store.loadMessages()
        assertTrue(loaded[0].timestamp in before..after)
    }
}
