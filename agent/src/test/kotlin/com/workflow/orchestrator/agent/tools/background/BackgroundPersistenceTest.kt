package com.workflow.orchestrator.agent.tools.background

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.nio.file.Files

class BackgroundPersistenceTest {

    @Test
    fun `appendCompletion creates and appends atomically`() {
        val dir = Files.createTempDirectory("bgp-")
        val persist = BackgroundPersistence(dir)

        val ev1 = BackgroundCompletionEvent(
            bgId = "bg_p1", kind = "run_command", label = "cmd1", sessionId = "s1",
            exitCode = 0, state = BackgroundState.EXITED, runtimeMs = 10,
            tailContent = "a\n", spillPath = null, occurredAt = 1
        )
        val ev2 = ev1.copy(bgId = "bg_p2", occurredAt = 2)
        persist.appendCompletion("s1", ev1)
        persist.appendCompletion("s1", ev2)

        val loaded = persist.loadPendingCompletions("s1")
        assertEquals(listOf("bg_p1", "bg_p2"), loaded.map { it.bgId })
    }

    @Test
    fun `consume removes entries`() {
        val dir = Files.createTempDirectory("bgp-")
        val persist = BackgroundPersistence(dir)
        val ev = BackgroundCompletionEvent(
            bgId = "bg_x", kind = "run_command", label = "cmd", sessionId = "s1",
            exitCode = 0, state = BackgroundState.EXITED, runtimeMs = 10,
            tailContent = "", spillPath = null, occurredAt = 1
        )
        persist.appendCompletion("s1", ev)
        persist.consumeCompletion("s1", "bg_x")
        assertTrue(persist.loadPendingCompletions("s1").isEmpty())
    }

    @Test
    fun `per-session isolation`() {
        val dir = Files.createTempDirectory("bgp-")
        val persist = BackgroundPersistence(dir)
        val base = BackgroundCompletionEvent(
            bgId = "bg_x", kind = "run_command", label = "cmd", sessionId = "s1",
            exitCode = 0, state = BackgroundState.EXITED, runtimeMs = 10,
            tailContent = "", spillPath = null, occurredAt = 1
        )
        persist.appendCompletion("s1", base)
        persist.appendCompletion("s2", base.copy(sessionId = "s2"))
        assertEquals(1, persist.loadPendingCompletions("s1").size)
        assertEquals(1, persist.loadPendingCompletions("s2").size)
    }
}
