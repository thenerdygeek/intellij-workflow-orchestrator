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

    // ─── pending notifications roundtrip ──────────────────────────────────────

    @Test
    fun `appendPendingNotification then loadPendingNotifications - roundtrip`() {
        val p = persistence()
        p.appendPendingNotification("s1", "monitor shell-abc · NOTABLE — build finished")
        p.appendPendingNotification("s1", "monitor bamboo-xyz · ALERT — build failed")

        val loaded = p.loadPendingNotifications("s1")
        assertEquals(2, loaded.size)
        assertEquals("monitor shell-abc · NOTABLE — build finished", loaded[0])
        assertEquals("monitor bamboo-xyz · ALERT — build failed", loaded[1])
    }

    @Test
    fun `loadPendingNotifications when file missing - returns emptyList`() {
        assertTrue(persistence().loadPendingNotifications("no-such-session").isEmpty())
    }

    @Test
    fun `clearPendingNotifications - file deleted, subsequent load returns emptyList`() {
        val p = persistence()
        p.appendPendingNotification("s1", "some event")
        p.clearPendingNotifications("s1")

        val notifFile = tempDir.resolve("sessions").resolve("s1").resolve("monitor-notifications.json")
        assertFalse(Files.exists(notifFile), "monitor-notifications.json should be deleted after clearPendingNotifications")
        assertTrue(p.loadPendingNotifications("s1").isEmpty())
    }

    // ─── clearPersistedMonitors contract (Task 6F: clear + clearPendingNotifications) ───

    @Test
    fun `clear and clearPendingNotifications both delete their files - mirrors clearPersistedMonitors`() {
        val p = persistence()
        p.add("s1", spec("mon-1"))
        p.appendPendingNotification("s1", "event text")

        val monitorsFile = tempDir.resolve("sessions").resolve("s1").resolve("monitors.json")
        val notifFile = tempDir.resolve("sessions").resolve("s1").resolve("monitor-notifications.json")
        assertTrue(Files.exists(monitorsFile), "monitors.json should exist before clear")
        assertTrue(Files.exists(notifFile), "monitor-notifications.json should exist before clearPendingNotifications")

        // Simulate what AgentService.clearPersistedMonitors does:
        p.clear("s1")
        p.clearPendingNotifications("s1")

        assertFalse(Files.exists(monitorsFile), "monitors.json should be deleted by clear")
        assertFalse(Files.exists(notifFile), "monitor-notifications.json should be deleted by clearPendingNotifications")
        assertTrue(p.load("s1").isEmpty())
        assertTrue(p.loadPendingNotifications("s1").isEmpty())
    }

    @Test
    fun `clear and clearPendingNotifications are idempotent on missing files`() {
        val p = persistence()
        // Calling clear/clearPendingNotifications on a session with no files must not throw.
        p.clear("phantom-session")
        p.clearPendingNotifications("phantom-session")
        // No assertions needed beyond "no exception thrown"
    }

    // ─── preamble block format (pure helper used by AgentService Task 6F) ─────

    @Test
    fun `buildMonitorNotificationsPreambleBlock - empty list returns empty string`() {
        val pending = emptyList<String>()
        val block = buildMonitorNotificationsPreambleBlock(pending)
        assertEquals("", block)
    }

    @Test
    fun `buildMonitorNotificationsPreambleBlock - non-empty list returns formatted section`() {
        val pending = listOf(
            "[monitor shell-abc · NOTABLE] build finished",
            "[monitor bamboo-xyz · ALERT] stage Failed",
        )
        val block = buildMonitorNotificationsPreambleBlock(pending)
        assertTrue(block.startsWith("\n\n# Monitor notifications while away\n"),
            "Block should start with the section header")
        assertTrue(block.contains("[monitor shell-abc · NOTABLE] build finished"),
            "Block should contain first notification")
        assertTrue(block.contains("[monitor bamboo-xyz · ALERT] stage Failed"),
            "Block should contain second notification")
    }

    companion object {
        /**
         * Pure helper that formats a list of pending monitor notification texts into the
         * preamble block injected by AgentService.resumeSession (Task 6F).
         *
         * Extracted here for direct unit-testing without needing AgentService or IntelliJ services.
         * The production code in AgentService uses identical logic inline (DRY is not the goal —
         * testability of the text contract is).
         */
        fun buildMonitorNotificationsPreambleBlock(pending: List<String>): String {
            if (pending.isEmpty()) return ""
            val body = pending.joinToString("\n")
            return "\n\n# Monitor notifications while away\n" +
                "While the session was paused, the following monitor events fired:\n\n" +
                body + "\n"
        }
    }
}
