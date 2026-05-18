package com.workflow.orchestrator.agent.checkpoint

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SessionCheckpointStoreTest {

    @Test
    fun `beginUserMessage creates msg dir with meta json containing userText and ts`(@TempDir tmp: java.nio.file.Path) {
        val store = SessionCheckpointStore(sessionDir = tmp.toFile())
        store.beginUserMessage(messageTs = 12345L, userText = "fix the bug")

        val msgDir = File(tmp.toFile(), "checkpoints/msg-12345")
        assertTrue(msgDir.isDirectory, "msg-12345 dir should exist")

        val meta = store.listMessageCheckpoints()
        assertEquals(1, meta.size)
        assertEquals(12345L, meta[0].messageTs)
        assertEquals("fix the bug", meta[0].userText)
    }

    @Test
    fun `listMessageCheckpoints returns sorted by ts ascending`(@TempDir tmp: java.nio.file.Path) {
        val store = SessionCheckpointStore(sessionDir = tmp.toFile())
        store.beginUserMessage(300L, "third")
        store.beginUserMessage(100L, "first")
        store.beginUserMessage(200L, "second")

        val sorted = store.listMessageCheckpoints().map { it.messageTs }
        assertEquals(listOf(100L, 200L, 300L), sorted)
    }
}
