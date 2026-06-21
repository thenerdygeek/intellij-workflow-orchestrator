package com.workflow.orchestrator.agent.loop.queue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Test
import java.io.File
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

    // B4: a corrupt pending_queue.json must not throw — durable queued work is dropped to empty
    // rather than crashing resume. (Resilience was already present via getOrDefault; pinned here.)
    @Test
    fun `load returns empty on a corrupt file instead of throwing`(@TempDir dir: Path) {
        val file = dir.resolve("sessions").resolve("s1").also { Files.createDirectories(it) }
            .resolve("pending_queue.json")
        Files.writeString(file, "{ not valid json ]]")
        assertEquals(emptyList<QueuedMessage>(), QueuePersistence(dir).load("s1"))
    }

    // B4: dropping durable queued work must NOT be silent — the corrupt-file path must log so an
    // operator can see why a user's queued-while-idle items vanished. Source-contract pin because
    // the log line is observability-only (no return-value change to drive behaviorally).
    @Test
    fun `load logs when it drops a corrupt file`() {
        val src = File("src/main/kotlin/com/workflow/orchestrator/agent/loop/queue/QueuePersistence.kt").readText()
        val loadBody = src.substringAfter("fun load(sessionId: String)").substringBefore("fun save(")
        assertTrue(
            loadBody.contains("onFailure") && Regex("""log\.warn|LOG\.warn""").containsMatchIn(loadBody),
            "QueuePersistence.load must log (log.warn in .onFailure) when it drops a corrupt file: $loadBody",
        )
    }
}
