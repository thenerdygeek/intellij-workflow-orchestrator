package com.workflow.orchestrator.agent.monitor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class MonitorPersistenceTest {

    @TempDir
    lateinit var tempDir: Path

    private fun persistence() = MonitorPersistence(tempDir)

    private fun spec(
        id: String,
        sourceType: String = "shell",
        description: String = "test",
        params: Map<String, String> = emptyMap(),
    ) = MonitorSpec(id = id, sourceType = sourceType, description = description, params = params)

    // ─── roundtrip ────────────────────────────────────────────────────────────

    @Test
    fun `add then load - roundtrip with all fields preserved including params map`() {
        val p = persistence()
        val spec = spec(
            id = "shell-abc123",
            sourceType = "shell",
            description = "watch logs",
            params = mapOf("command" to "tail -f app.log", "filter" to "ERROR"),
        )
        p.add("s1", spec)

        val loaded = p.load("s1")
        assertEquals(1, loaded.size)
        assertEquals(spec, loaded[0])
    }

    // ─── replace-by-id ────────────────────────────────────────────────────────

    @Test
    fun `add same id twice - load returns ONE entry with the latest params`() {
        val p = persistence()
        p.add("s1", spec("mon-1", params = mapOf("command" to "original")))
        p.add("s1", spec("mon-1", params = mapOf("command" to "updated")))

        val loaded = p.load("s1")
        assertEquals(1, loaded.size)
        assertEquals("updated", loaded[0].params["command"])
    }

    // ─── add two, remove one ──────────────────────────────────────────────────

    @Test
    fun `add two different ids - load returns both then remove one - load returns the other`() {
        val p = persistence()
        p.add("s1", spec("mon-a"))
        p.add("s1", spec("mon-b"))

        assertEquals(setOf("mon-a", "mon-b"), p.load("s1").map { it.id }.toSet())

        p.remove("s1", "mon-a")

        val remaining = p.load("s1")
        assertEquals(1, remaining.size)
        assertEquals("mon-b", remaining[0].id)
    }

    // ─── remove last - file deleted ───────────────────────────────────────────

    @Test
    fun `remove the last remaining - monitors-json file no longer exists`() {
        val p = persistence()
        p.add("s1", spec("only-one"))
        p.remove("s1", "only-one")

        val monitorsFile = tempDir.resolve("sessions").resolve("s1").resolve("monitors.json")
        assertFalse(Files.exists(monitorsFile), "monitors.json should be deleted when the list is empty")
    }

    // ─── load when file missing ───────────────────────────────────────────────

    @Test
    fun `load when file missing - returns emptyList`() {
        val loaded = persistence().load("nonexistent-session")
        assertTrue(loaded.isEmpty())
    }

    // ─── corrupt JSON - no throw ──────────────────────────────────────────────

    @Test
    fun `load when file contains corrupt JSON - returns emptyList and does not throw`() {
        val p = persistence()
        // Create the directory and write garbage directly
        val sessionDir = tempDir.resolve("sessions").resolve("corrupt-session")
        Files.createDirectories(sessionDir)
        Files.writeString(sessionDir.resolve("monitors.json"), "{{not valid json}}")

        val loaded = p.load("corrupt-session")
        assertTrue(loaded.isEmpty())
    }

    // ─── clear ────────────────────────────────────────────────────────────────

    @Test
    fun `clear - file deleted, subsequent load returns emptyList`() {
        val p = persistence()
        p.add("s1", spec("mon-x"))
        p.clear("s1")

        val monitorsFile = tempDir.resolve("sessions").resolve("s1").resolve("monitors.json")
        assertFalse(Files.exists(monitorsFile))
        assertTrue(p.load("s1").isEmpty())
    }

    // ─── per-session isolation ────────────────────────────────────────────────

    @Test
    fun `add to session A does not appear in session B load`() {
        val p = persistence()
        p.add("session-A", spec("mon-1"))

        val loadedA = p.load("session-A")
        val loadedB = p.load("session-B")

        assertEquals(1, loadedA.size)
        assertTrue(loadedB.isEmpty())
    }
}
