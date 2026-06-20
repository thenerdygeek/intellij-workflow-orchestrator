package com.workflow.orchestrator.agent.loop.queue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class QueuePersistenceTest {
    private fun item(id: String) = QueuedMessage(id, QueueSourceKind.BACKGROUND, "b", 1L, 50, id, emptyMap())

    @Test
    fun `save then load round-trips`(@TempDir dir: Path) {
        val p = QueuePersistence(dir)
        p.save("s1", listOf(item("a"), item("b")))
        assertEquals(listOf(item("a"), item("b")), p.load("s1"))
    }

    @Test
    fun `load is empty when no file`(@TempDir dir: Path) {
        assertEquals(emptyList<QueuedMessage>(), QueuePersistence(dir).load("missing"))
    }

    @Test
    fun `saving an empty list deletes the file`(@TempDir dir: Path) {
        val p = QueuePersistence(dir)
        p.save("s1", listOf(item("a")))
        val file = dir.resolve("sessions").resolve("s1").resolve("pending_queue.json")
        assertTrue(Files.exists(file))
        p.save("s1", emptyList())
        assertFalse(Files.exists(file))
    }
}
