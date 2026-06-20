package com.workflow.orchestrator.agent.tools.background

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.loop.queue.QueueSourceKind
import com.workflow.orchestrator.agent.loop.queue.QueuedMessage
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Behavioral characterization of [BackgroundCompletionCoordinator] after Task 2.2 —
 * background-process completions now route through the unified queue as [QueueSourceKind.BACKGROUND]
 * items instead of steering-message / persist-and-wake split.
 *
 * The [buildCompletionSystemMessage] companion pin is preserved. The removed
 * [buildAutoResumeSyntheticMessage] assertions are dropped. The test-capturer short-circuit is kept.
 * New assertions verify that [BackgroundCompletionCoordinator.onBackgroundCompletion] calls the
 * injected [enqueue] lambda with the correct [QueuedMessage] fields.
 */
class BackgroundCompletionCoordinatorTest {

    private val project: Project = mockk(relaxed = true)
    private val pool: BackgroundPool = mockk(relaxed = true)

    private lateinit var enqueuedMessages: MutableList<Pair<String, QueuedMessage>>

    @BeforeEach
    fun setup() {
        io.mockk.every { project.getService(BackgroundPool::class.java) } returns pool
        io.mockk.every { pool.addCompletionListener(any()) } returns mockk(relaxed = true)
        enqueuedMessages = mutableListOf()
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

    private fun coordinator() =
        BackgroundCompletionCoordinator(
            project = project,
            enqueue = { sid, msg -> enqueuedMessages.add(sid to msg) },
        )

    // ── buildCompletionSystemMessage pin ────────────────────────────────────────────────

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

    // ── queue-routing assertions (Task 2.2) ─────────────────────────────────────────────

    @Test
    fun `onBackgroundCompletion enqueues a BACKGROUND message with correct fields`() {
        val coordinator = coordinator()
        val ev = event(bgId = "bg-42", sessionId = "s1")

        coordinator.onBackgroundCompletion(ev)

        assertEquals(1, enqueuedMessages.size, "exactly one enqueue call expected")
        val (sid, msg) = enqueuedMessages.single()
        assertEquals("s1", sid, "session id must match the event")
        assertEquals(QueueSourceKind.BACKGROUND, msg.kind)
        assertEquals(ev.bgId, msg.coalesceKey, "coalesceKey must equal event.bgId")
        assertEquals(
            BackgroundCompletionCoordinator.buildCompletionSystemMessage(ev),
            msg.body,
            "body must equal buildCompletionSystemMessage(event)",
        )
    }

    @Test
    fun `enqueued message id starts with bg- prefix and includes bgId`() {
        coordinator().onBackgroundCompletion(event(bgId = "bg-99"))

        val (_, msg) = enqueuedMessages.single()
        assertTrue(msg.id.startsWith("bg-bg-99-"), "id should be 'bg-{bgId}-{nanoTime}': ${msg.id}")
    }

    @Test
    fun `enqueued message meta contains bgId`() {
        coordinator().onBackgroundCompletion(event(bgId = "bg-7"))

        val (_, msg) = enqueuedMessages.single()
        assertEquals("bg-7", msg.meta["bgId"], "meta must carry bgId for coalesce resolution")
    }

    // ── test-capturer short-circuit ──────────────────────────────────────────────────────

    @Test
    fun `installed test capturer intercepts the message and short-circuits enqueue`() {
        val coordinator = coordinator()
        val captured = mutableListOf<String>()
        coordinator.setSteeringCapturerForTest("s3") { captured.add(it) }

        coordinator.onBackgroundCompletion(event(sessionId = "s3"))

        assertEquals(1, captured.size, "capturer must receive the message")
        assertTrue(captured.single().contains("[BACKGROUND COMPLETION]"), captured.single())
        assertTrue(
            enqueuedMessages.isEmpty(),
            "capturer path must short-circuit before enqueue routing",
        )
    }

    @Test
    fun `capturer body equals buildCompletionSystemMessage`() {
        val coordinator = coordinator()
        val ev = event(bgId = "bg-3", sessionId = "s9")
        val captured = mutableListOf<String>()
        coordinator.setSteeringCapturerForTest("s9") { captured.add(it) }

        coordinator.onBackgroundCompletion(ev)

        assertEquals(
            BackgroundCompletionCoordinator.buildCompletionSystemMessage(ev),
            captured.single(),
            "capturer must receive the same body as the queue message would carry",
        )
    }
}
