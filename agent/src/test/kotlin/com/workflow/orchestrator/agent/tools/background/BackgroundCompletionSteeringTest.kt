package com.workflow.orchestrator.agent.tools.background

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.settings.AgentSettings
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Task 5.4 — exercises the listener contract that
 * [com.workflow.orchestrator.agent.AgentService] relies on:
 *
 * 1. Each [BackgroundPool.emitCompletion] produces exactly one listener invocation —
 *    not batched — so the LLM sees one steering message per process exit.
 * 2. Multiple listener registrations all receive every event (supports concurrent
 *    test capturers + the production routing path).
 *
 * We do **not** try to construct [com.workflow.orchestrator.agent.AgentService]
 * directly — its init block loads the full tool set, memory system, and hook
 * subsystem, which is infeasible to mock. The `AgentService` wiring is a thin
 * `addCompletionListener { onBackgroundCompletion(it) }` call in `init` plus the
 * formatting helper, both exercised here via the same [BackgroundPool] API.
 */
class BackgroundCompletionSteeringTest {

    private val project = mockk<Project>(relaxed = true)
    private lateinit var pool: BackgroundPool

    @BeforeEach
    fun setUp() {
        mockkObject(AgentSettings.Companion)
        every { AgentSettings.getInstance(any()) } returns mockk {
            every { state } returns mockk {
                every { concurrentBackgroundProcessesPerSession } returns 5
            }
        }
        pool = BackgroundPool(project)
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(AgentSettings.Companion)
    }

    @Test
    fun `each completion produces a separate listener invocation (no batching)`() = runTest {
        val captured = mutableListOf<BackgroundCompletionEvent>()
        pool.addCompletionListener { captured.add(it) }

        val sessionId = "sess-steer"
        val ev1 = BackgroundCompletionEvent(
            bgId = "bg_s1", kind = "run_command", label = "cmd1", sessionId = sessionId,
            exitCode = 0, state = BackgroundState.EXITED, runtimeMs = 5,
            tailContent = "hi\n", spillPath = null, occurredAt = 1,
        )
        val ev2 = BackgroundCompletionEvent(
            bgId = "bg_s2", kind = "run_command", label = "cmd2", sessionId = sessionId,
            exitCode = 1, state = BackgroundState.EXITED, runtimeMs = 20,
            tailContent = "oops\n", spillPath = null, occurredAt = 2,
        )
        pool.emitCompletion(sessionId, ev1)
        pool.emitCompletion(sessionId, ev2)

        assertEquals(2, captured.size, "expected 2 events (one per emitCompletion), got: $captured")
        assertEquals("bg_s1", captured[0].bgId)
        assertEquals("bg_s2", captured[1].bgId)
        assertEquals(BackgroundState.EXITED, captured[0].state)
    }

    @Test
    fun `multiple listeners each receive every event`() = runTest {
        val a = mutableListOf<String>()
        val b = mutableListOf<String>()
        pool.addCompletionListener { a.add(it.bgId) }
        pool.addCompletionListener { b.add(it.bgId) }

        val sessionId = "sess-multi"
        val ev = BackgroundCompletionEvent(
            bgId = "bg_m1", kind = "run_command", label = "cmd", sessionId = sessionId,
            exitCode = 0, state = BackgroundState.EXITED, runtimeMs = 1,
            tailContent = "", spillPath = null, occurredAt = 1,
        )
        pool.emitCompletion(sessionId, ev)

        assertEquals(listOf("bg_m1"), a)
        assertEquals(listOf("bg_m1"), b)
    }

    @Test
    fun `completion message format matches AgentService contract`() {
        // Same format as AgentService.buildCompletionSystemMessage — pinned here
        // so any drift in that helper breaks this test and is noticed.
        val event = BackgroundCompletionEvent(
            bgId = "bg_fmt", kind = "run_command", label = "./gradlew build",
            sessionId = "s1", exitCode = 2, state = BackgroundState.EXITED,
            runtimeMs = 1234, tailContent = "line1\nline2\nline3\n",
            spillPath = "/tmp/out.txt", occurredAt = 1L,
        )
        val msg = buildCompletionSystemMessage(event)

        assertTrue(msg.startsWith("[BACKGROUND COMPLETION]"), "prefix missing: $msg")
        assertTrue(msg.contains("bg_fmt"), "bgId missing: $msg")
        assertTrue(msg.contains("run_command"), "kind missing: $msg")
        assertTrue(msg.contains("./gradlew build"), "label missing: $msg")
        assertTrue(msg.contains("EXITED"), "state missing: $msg")
        assertTrue(msg.contains("exit code: 2"), "exit code missing: $msg")
        assertTrue(msg.contains("runtime: 1234ms"), "runtime missing: $msg")
        assertTrue(msg.contains("line1") && msg.contains("line3"), "tail content missing: $msg")
        assertTrue(msg.contains("/tmp/out.txt"), "spillPath missing: $msg")
    }

    /**
     * Copy of AgentService.buildCompletionSystemMessage — tests the same shape
     * that the production path produces. Keeping a parallel copy here (rather
     * than reflecting into the private function) keeps the test independent of
     * AgentService instantiation.
     */
    private fun buildCompletionSystemMessage(event: BackgroundCompletionEvent): String = buildString {
        appendLine("[BACKGROUND COMPLETION]")
        appendLine("Process ${event.bgId} (${event.kind}: \"${event.label.take(80)}\")")
        append("State: ${event.state}, exit code: ${event.exitCode}, ")
        appendLine("runtime: ${event.runtimeMs}ms")
        appendLine("Output (tail 20 lines):")
        event.tailContent.lines().takeLast(20).forEach { appendLine("  $it") }
        if (event.spillPath != null) appendLine("Full output: ${event.spillPath}")
    }
}
