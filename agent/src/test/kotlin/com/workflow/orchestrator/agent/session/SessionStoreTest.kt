package com.workflow.orchestrator.agent.session

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SessionStoreTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `save and load roundtrip`() {
        val store = SessionStore(tempDir)
        val session = Session(
            id = "sess-001",
            title = "Fix login bug",
            createdAt = 1000L,
            lastMessageAt = 2000L,
            messageCount = 5,
            status = SessionStatus.ACTIVE,
            totalTokens = 1234
        )

        store.save(session)
        val loaded = store.load("sess-001")

        assertNotNull(loaded)
        assertEquals(session.id, loaded!!.id)
        assertEquals(session.title, loaded.title)
        assertEquals(session.createdAt, loaded.createdAt)
        assertEquals(session.lastMessageAt, loaded.lastMessageAt)
        assertEquals(session.messageCount, loaded.messageCount)
        assertEquals(session.status, loaded.status)
        assertEquals(session.totalTokens, loaded.totalTokens)
    }

    @Test
    fun `list returns sessions sorted by creation time newest first`() {
        val store = SessionStore(tempDir)
        store.save(Session(id = "old", title = "Old", createdAt = 1000L))
        store.save(Session(id = "mid", title = "Mid", createdAt = 2000L))
        store.save(Session(id = "new", title = "New", createdAt = 3000L))

        val list = store.list()

        assertEquals(3, list.size)
        assertEquals("new", list[0].id)
        assertEquals("mid", list[1].id)
        assertEquals("old", list[2].id)
    }

    @Test
    fun `load returns null for missing session`() {
        val store = SessionStore(tempDir)

        val result = store.load("nonexistent")

        assertNull(result)
    }

    @Test
    fun `save same ID twice overwrites`() {
        val store = SessionStore(tempDir)

        store.save(Session(id = "sess-x", title = "Version 1", createdAt = 1000L, messageCount = 1))
        store.save(Session(id = "sess-x", title = "Version 2", createdAt = 1000L, messageCount = 10))

        val loaded = store.load("sess-x")
        assertNotNull(loaded)
        assertEquals("Version 2", loaded!!.title)
        assertEquals(10, loaded.messageCount)

        // list should have only one entry
        assertEquals(1, store.list().size)
    }
}
