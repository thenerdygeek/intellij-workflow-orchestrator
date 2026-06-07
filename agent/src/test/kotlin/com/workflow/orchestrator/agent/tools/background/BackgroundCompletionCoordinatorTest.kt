package com.workflow.orchestrator.agent.tools.background

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.loop.AgentLoop
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Behavioral characterization of [BackgroundCompletionCoordinator] — the background-process
 * completion routing + auto-resume message builders extracted out of `AgentService` (Phase 3
 * cut F). Previously this lived on the `@Service` god-class and could only be exercised through
 * the `BackgroundPool` listener seam; pulling it into a plain injectable class makes both the
 * routing decision and the synthetic-message FORMAT directly assertable (the latter is the
 * `AutoWakeSyntheticMessageFormatTest` the old KDoc promised but never had).
 *
 * The shared auto-wake substrate (the single `IdleSessionWaker`, its guard state, the
 * controller listener) deliberately stays on `AgentService` and is injected here as the
 * [autoWake] lambda, so monitor / background / delegation wakes keep sharing one guard.
 */
class BackgroundCompletionCoordinatorTest {

    @TempDir
    lateinit var agentDir: Path

    private val project: Project = mockk(relaxed = true)
    private val pool: BackgroundPool = mockk(relaxed = true)

    private lateinit var wakeCalls: MutableList<Triple<String, String, String>>

    @BeforeEach
    fun setup() {
        io.mockk.every { project.getService(BackgroundPool::class.java) } returns pool
        io.mockk.every { pool.addCompletionListener(any()) } returns mockk(relaxed = true)
        wakeCalls = mutableListOf()
    }

    @AfterEach
    fun teardown() {
        io.mockk.unmockkAll()
    }

    private fun event(
        bgId: String = "bg-1",
        sessionId: String = "s1",
        exitCode: Int = 0,
        tail: String = "all good",
        spill: String? = null,
    ) = BackgroundCompletionEvent(
        bgId = bgId,
        kind = "shell",
        label = "long build",
        sessionId = sessionId,
        exitCode = exitCode,
        state = BackgroundState.EXITED,
        runtimeMs = 1234,
        tailContent = tail,
        spillPath = spill,
        occurredAt = 0L,
    )

    private fun coordinator(activeLoopForSession: (String) -> AgentLoop? = { null }) =
        BackgroundCompletionCoordinator(
            project = project,
            agentDirProvider = { agentDir.toFile() },
            activeLoopForSession = activeLoopForSession,
            autoWake = { sid, text, source -> wakeCalls.add(Triple(sid, text, source)) },
        )

    @Test
    fun `buildCompletionSystemMessage carries the marker, ids, and tail output`() {
        val msg = BackgroundCompletionCoordinator.buildCompletionSystemMessage(
            event(bgId = "bg-7", tail = "line-A\nline-B", spill = "/tmp/out.txt"),
        )
        assertTrue(msg.contains("[BACKGROUND COMPLETION]"), msg)
        assertTrue(msg.contains("bg-7"), msg)
        assertTrue(msg.contains("line-B"), msg)
        assertTrue(msg.contains("/tmp/out.txt"), "spill path should be surfaced: $msg")
    }

    @Test
    fun `buildAutoResumeSyntheticMessage carries the auto-resumed marker and completion guidance`() {
        val msg = BackgroundCompletionCoordinator.buildAutoResumeSyntheticMessage(event())
        assertTrue(msg.contains("[BACKGROUND COMPLETION — AUTO-RESUMED]"), msg)
        assertTrue(msg.contains("attempt_completion"), "should instruct the LLM how to finish: $msg")
    }

    @Test
    fun `live loop receives the completion as a steering message and no auto-wake fires`() {
        val loop = mockk<AgentLoop>(relaxed = true)
        val coordinator = coordinator(activeLoopForSession = { sid -> if (sid == "s1") loop else null })

        coordinator.onBackgroundCompletion(event(sessionId = "s1"))

        verify { loop.enqueueSteeringMessage(match { it.contains("[BACKGROUND COMPLETION]") }) }
        assertTrue(wakeCalls.isEmpty(), "a live loop must not trigger the idle auto-wake path")
    }

    @Test
    fun `idle session persists the completion and triggers a guarded auto-wake`() {
        val coordinator = coordinator(activeLoopForSession = { null })

        coordinator.onBackgroundCompletion(event(bgId = "bg-9", sessionId = "s2"))

        assertEquals(1, wakeCalls.size, "idle path must call autoWake exactly once")
        val (sid, text, source) = wakeCalls.single()
        assertEquals("s2", sid)
        assertTrue(text.contains("[BACKGROUND COMPLETION — AUTO-RESUMED]"), text)
        assertTrue(source.contains("bg-9"), "wake source should identify the process: $source")

        val persisted = BackgroundPersistence(agentDir).loadPendingCompletions("s2")
        assertEquals(1, persisted.size, "completion must be persisted first so it survives a guard-rejected wake")
    }

    @Test
    fun `installed test capturer intercepts the message and short-circuits routing`() {
        val coordinator = coordinator(activeLoopForSession = { mockk(relaxed = true) })
        val captured = mutableListOf<String>()
        coordinator.setSteeringCapturerForTest("s3") { captured.add(it) }

        coordinator.onBackgroundCompletion(event(sessionId = "s3"))

        assertEquals(1, captured.size, "capturer must receive the message")
        assertTrue(captured.single().contains("[BACKGROUND COMPLETION]"), captured.single())
        assertTrue(wakeCalls.isEmpty(), "capturer path must short-circuit before live-loop / auto-wake routing")
    }
}
