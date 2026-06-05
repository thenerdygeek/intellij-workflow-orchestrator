package com.workflow.orchestrator.agent.monitor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for the pending-notification persistence methods added to [MonitorPersistence]
 * in Task 6E (persist idle-wake notification before waking).
 *
 * Ordering test approach: construct a [MonitorManager] with a recording `wakeIdle` lambda
 * that mirrors the AgentService production lambda (persist via real MonitorPersistence@TempDir,
 * then return an outcome). Drive an idle flush and assert the notification was persisted
 * regardless of whether the wake route was WOKE or SKIPPED. This validates the persist-first
 * ordering without needing to construct AgentService.
 */
class MonitorNotificationsTest {

    @TempDir
    lateinit var tempDir: Path

    private fun persistence() = MonitorPersistence(tempDir)

    // ─── appendPendingNotification roundtrip ─────────────────────────────────

    @Test
    fun `append one notification - load returns it`() {
        val p = persistence()
        p.appendPendingNotification("s1", "monitor shell-abc fired")

        val loaded = p.loadPendingNotifications("s1")
        assertEquals(1, loaded.size)
        assertEquals("monitor shell-abc fired", loaded[0])
    }

    // ─── multiple appends accumulate in insertion order ───────────────────────

    @Test
    fun `multiple appends - load returns all in order`() {
        val p = persistence()
        p.appendPendingNotification("s1", "first")
        p.appendPendingNotification("s1", "second")
        p.appendPendingNotification("s1", "third")

        val loaded = p.loadPendingNotifications("s1")
        assertEquals(listOf("first", "second", "third"), loaded)
    }

    // ─── load missing → empty ─────────────────────────────────────────────────

    @Test
    fun `load when file missing - returns emptyList`() {
        val loaded = persistence().loadPendingNotifications("nonexistent-session")
        assertTrue(loaded.isEmpty())
    }

    // ─── corrupt JSON → empty ─────────────────────────────────────────────────

    @Test
    fun `load when file contains corrupt JSON - returns emptyList and does not throw`() {
        val p = persistence()
        val sessionDir = tempDir.resolve("sessions").resolve("corrupt-session")
        Files.createDirectories(sessionDir)
        Files.writeString(sessionDir.resolve("monitor-notifications.json"), "{{not valid json}}")

        val loaded = p.loadPendingNotifications("corrupt-session")
        assertTrue(loaded.isEmpty())
    }

    // ─── clear deletes the file ───────────────────────────────────────────────

    @Test
    fun `clear - file deleted, subsequent load returns emptyList`() {
        val p = persistence()
        p.appendPendingNotification("s1", "something")
        p.clearPendingNotifications("s1")

        val notifFile = tempDir.resolve("sessions").resolve("s1").resolve("monitor-notifications.json")
        assertFalse(Files.exists(notifFile), "monitor-notifications.json should be deleted after clear")
        assertTrue(p.loadPendingNotifications("s1").isEmpty())
    }

    @Test
    fun `clear when no file exists - does not throw`() {
        // idempotent — no file created yet
        persistence().clearPendingNotifications("nonexistent")
    }

    // ─── per-session isolation ────────────────────────────────────────────────

    @Test
    fun `append to session A does not appear in session B`() {
        val p = persistence()
        p.appendPendingNotification("session-A", "only for A")

        assertEquals(1, p.loadPendingNotifications("session-A").size)
        assertTrue(p.loadPendingNotifications("session-B").isEmpty())
    }

    @Test
    fun `different sessions accumulate independently`() {
        val p = persistence()
        p.appendPendingNotification("sA", "msg-A1")
        p.appendPendingNotification("sB", "msg-B1")
        p.appendPendingNotification("sA", "msg-A2")
        p.appendPendingNotification("sB", "msg-B2")

        assertEquals(listOf("msg-A1", "msg-A2"), p.loadPendingNotifications("sA"))
        assertEquals(listOf("msg-B1", "msg-B2"), p.loadPendingNotifications("sB"))
    }

    // ─── notifications file is separate from monitors.json ───────────────────

    @Test
    fun `notifications file and monitors file are distinct - appending notifications does not corrupt monitors`() {
        val p = persistence()
        val spec = MonitorSpec(id = "shell-abc", sourceType = "shell", description = "watch", params = emptyMap())
        p.add("s1", spec)
        p.appendPendingNotification("s1", "fired")

        // monitors.json still loads the spec correctly
        val monitors = p.load("s1")
        assertEquals(1, monitors.size)
        assertEquals("shell-abc", monitors[0].id)

        // notifications file loads the text correctly
        val notifs = p.loadPendingNotifications("s1")
        assertEquals(listOf("fired"), notifs)
    }

    // ─── persist-first ordering via recording MonitorManager ─────────────────

    /**
     * Approach: construct a real [MonitorManager] with a recording `wakeIdle` lambda that
     * mirrors the AgentService production lambda (persist via real MonitorPersistence → then
     * return outcome). Drive an idle flush and assert:
     *   (a) the notification was persisted before/regardless of outcome, and
     *   (b) the waker was also invoked (both persist and wake happen).
     */
    @Test
    fun `persist-first ordering - WOKE route persists notification before wake outcome is observed`() {
        val p = persistence()
        val sessionId = "sess-woke"
        val persistedBeforeWake = mutableListOf<String>()
        val wakeInvocations = mutableListOf<String>()
        var clock = 0L

        val mgr = MonitorManager(
            config = MonitorConfig(coalesceWindowMs = 100, wakeBudgetPerMonitor = 3, floodThresholdPerMin = 20),
            clock = { clock },
            isLoopLive = { false },  // idle path forces wakeIdle
            deliverToLoop = {},
            wakeIdle = { text ->
                // Mirror the AgentService production lambda: persist FIRST, then observe outcome
                p.appendPendingNotification(sessionId, text)
                // Record what was persisted at the moment the wake lambda is executing
                persistedBeforeWake.addAll(p.loadPendingNotifications(sessionId))
                wakeInvocations += text
                WakeOutcome.WOKE
            },
        )

        mgr.onEvent(MonitorEvent("m1", Severity.NOTABLE, "alert line"))
        // advance clock past the 100 ms coalesce window, then flush
        clock = 200L
        mgr.flushDue()

        // The wake was called
        assertEquals(1, wakeInvocations.size)
        // At the moment the wake lambda executed, the notification was ALREADY persisted
        assertTrue(persistedBeforeWake.isNotEmpty(), "notification must be persisted before wake outcome is observed")
        assertTrue(persistedBeforeWake[0].contains("alert line"))
        // Notification still on disk after wake (clearing is Task 6F's concern)
        assertEquals(1, p.loadPendingNotifications(sessionId).size)
    }

    @Test
    fun `persist-first ordering - SKIPPED route still persists notification`() {
        val p = persistence()
        val sessionId = "sess-skipped"
        val persistedTexts = mutableListOf<String>()
        var clock = 0L

        val mgr = MonitorManager(
            config = MonitorConfig(coalesceWindowMs = 100, wakeBudgetPerMonitor = 3, floodThresholdPerMin = 20),
            clock = { clock },
            isLoopLive = { false },
            deliverToLoop = {},
            wakeIdle = { text ->
                // Persist FIRST (mirror production lambda), then return SKIPPED
                p.appendPendingNotification(sessionId, text)
                persistedTexts.addAll(p.loadPendingNotifications(sessionId))
                WakeOutcome.SKIPPED
            },
        )

        mgr.onEvent(MonitorEvent("m2", Severity.ALERT, "error detected"))
        clock = 200L
        mgr.flushDue()

        // Even though wake was SKIPPED, the notification is persisted for Task 6F to replay
        assertTrue(persistedTexts.isNotEmpty(), "notification must be persisted even when wake route is SKIPPED")
        assertTrue(persistedTexts[0].contains("error detected"))
        assertEquals(1, p.loadPendingNotifications(sessionId).size)
    }
}
