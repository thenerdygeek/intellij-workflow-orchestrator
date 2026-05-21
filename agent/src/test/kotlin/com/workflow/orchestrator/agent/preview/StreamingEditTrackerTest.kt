// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.agent.preview

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.testutil.installReadActionInlineShim
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Behavioural tests for [StreamingEditTracker] — the per-AgentLoop state machine
 * that streams partial `edit_file` diffs into the chat panel while the LLM is
 * still emitting `<new_string>`.
 *
 * The tracker is driven by [StreamingEditTracker.observe] which is called from
 * `AgentLoop.onChunk` once per `ToolUseContent` with `name == "edit_file"`. The
 * tracker decides whether to open a preview, push an update, finalize, or drop
 * the preview (validation failure / cancellation).
 *
 * Coverage:
 *  - Validation gate: incomplete params, missing file, no match, ambiguous match
 *    all silently drop the preview.
 *  - Throttle: updates are coalesced to a configurable tick (default 100ms).
 *  - Identical re-parses: same `new_string` after throttle elapses doesn't refire.
 *  - Finalization: `isPartial=false` flips state and ignores stray re-parses.
 *  - Cancellation: both single-id and bulk variants drop without onUpdate.
 *  - Diff anchoring: emitted diff carries the real file's `@@ -N,` hunk offset
 *    (so Commit 1's filename-hyperlink anchoring still works).
 */
class StreamingEditTrackerTest {

    private lateinit var project: Project

    private val opens = mutableListOf<Triple<String, String, String>>()       // (callId, path, initialDiff)
    private val updates = mutableListOf<Pair<String, String>>()                // (callId, diff)
    private val finalizes = mutableListOf<String>()
    private val cancels = mutableListOf<String>()
    private val clockNow = AtomicLong(0L)

    /**
     * Per-test [Project] mock with [basePath] pointing at the test's `@TempDir`. PathValidator
     * rejects any write path that doesn't resolve under the project base, so the project
     * basePath is set to the per-test tempDir before each test that needs file I/O.
     */
    private fun newProjectFor(basePath: File): Project = mockk<Project>(relaxed = true).also {
        every { it.basePath } returns basePath.absolutePath
    }

    @BeforeEach
    fun setUp() {
        installReadActionInlineShim()
        // Default project mock — tests that touch files override this in their body.
        project = mockk(relaxed = true)
        every { project.basePath } returns null
        opens.clear()
        updates.clear()
        finalizes.clear()
        cancels.clear()
        clockNow.set(0L)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    private fun newTracker(throttleMs: Long = 100L): StreamingEditTracker =
        StreamingEditTracker(
            project = project,
            onOpen = { id, path, diff -> opens.add(Triple(id, path, diff)) },
            onUpdate = { id, diff -> updates.add(id to diff) },
            onFinalize = { id -> finalizes.add(id) },
            onCancel = { id -> cancels.add(id) },
            throttleMs = throttleMs,
            clock = { clockNow.get() },
        )

    /** Write a temp file containing [text] and return its absolute path. */
    private fun tempFile(dir: File, name: String, text: String): String {
        val f = File(dir, name)
        f.writeText(text)
        return f.absolutePath
    }

    @Test
    fun `first observe with old_string present but new_string absent does NOT open preview`() = runTest {
        val tracker = newTracker()
        // Caller has only seen up to "<edit_file><path>x</path><old_string>foo</old_string>"
        // — no <new_string> param key has appeared yet, and isPartial=true.
        val opened = tracker.observe(
            callId = "c1",
            params = mapOf("path" to "/tmp/dummy.txt", "old_string" to "foo"),
            isPartial = true,
        )
        assertFalse(opened, "tracker must wait for new_string before opening")
        assertTrue(opens.isEmpty())
        assertTrue(updates.isEmpty())
    }

    @Test
    fun `first observe with both old_string and new_string param keys opens preview`(@TempDir tempDir: File) = runTest {
        project = newProjectFor(tempDir)
        val path = tempFile(tempDir, "a.txt", "alpha\nfoo\ngamma\n")
        val tracker = newTracker()

        val opened = tracker.observe(
            callId = "c1",
            params = mapOf("path" to path, "old_string" to "foo", "new_string" to ""),
            isPartial = true,
        )

        assertTrue(opened)
        assertEquals(1, opens.size)
        val (id, openedPath, initialDiff) = opens[0]
        assertEquals("c1", id)
        assertEquals(path, openedPath)
        assertTrue(initialDiff.isNotBlank(), "initial diff must not be empty (old_string deleted)")
    }

    @Test
    fun `validation failure when old_string is not in file does NOT open preview`(@TempDir tempDir: File) = runTest {
        project = newProjectFor(tempDir)
        val path = tempFile(tempDir, "a.txt", "alpha\nbeta\ngamma\n")
        val tracker = newTracker()

        val opened = tracker.observe(
            callId = "c1",
            params = mapOf("path" to path, "old_string" to "NOT_IN_FILE", "new_string" to ""),
            isPartial = true,
        )

        assertFalse(opened)
        assertTrue(opens.isEmpty())
    }

    @Test
    fun `validation failure when ambiguous match without replace_all does NOT open preview`(@TempDir tempDir: File) = runTest {
        project = newProjectFor(tempDir)
        val path = tempFile(tempDir, "a.txt", "foo\nbar\nfoo\n")
        val tracker = newTracker()

        val opened = tracker.observe(
            callId = "c1",
            params = mapOf("path" to path, "old_string" to "foo", "new_string" to "X"),
            isPartial = true,
        )

        assertFalse(opened)
        assertTrue(opens.isEmpty())
    }

    @Test
    fun `update calls are throttled to throttleMs`(@TempDir tempDir: File) = runTest {
        project = newProjectFor(tempDir)
        val path = tempFile(tempDir, "a.txt", "alpha\nfoo\ngamma\n")
        val tracker = newTracker(throttleMs = 100L)

        // Open
        clockNow.set(0L)
        tracker.observe(
            callId = "c1",
            params = mapOf("path" to path, "old_string" to "foo", "new_string" to "b"),
            isPartial = true,
        )
        val openedCount = opens.size

        // Feed 10 observes within a 50ms window with throttleMs=100ms.
        for (i in 1..10) {
            clockNow.set((i * 5).toLong())  // 5, 10, 15, … 50
            tracker.observe(
                callId = "c1",
                params = mapOf("path" to path, "old_string" to "foo", "new_string" to "b${"a".repeat(i)}"),
                isPartial = true,
            )
        }

        assertEquals(1, openedCount, "open should fire exactly once")
        assertTrue(updates.size <= 2, "throttle should coalesce 10 observes into ≤2 updates, was ${updates.size}")
    }

    @Test
    fun `update with identical new_string after throttle elapses does not fire onUpdate again`(@TempDir tempDir: File) = runTest {
        project = newProjectFor(tempDir)
        val path = tempFile(tempDir, "a.txt", "alpha\nfoo\ngamma\n")
        val tracker = newTracker(throttleMs = 50L)

        clockNow.set(0L)
        tracker.observe(
            callId = "c1",
            params = mapOf("path" to path, "old_string" to "foo", "new_string" to "X"),
            isPartial = true,
        )

        clockNow.set(200L)
        tracker.observe(
            callId = "c1",
            params = mapOf("path" to path, "old_string" to "foo", "new_string" to "X"),
            isPartial = true,
        )

        clockNow.set(400L)
        tracker.observe(
            callId = "c1",
            params = mapOf("path" to path, "old_string" to "foo", "new_string" to "X"),
            isPartial = true,
        )

        assertEquals(0, updates.size, "identical new_string should not trigger any onUpdate fires")
    }

    @Test
    fun `transition from partial=true to partial=false calls onFinalize and ignores stray re-parses`(
        @TempDir tempDir: File,
    ) = runTest {
        project = newProjectFor(tempDir)
        val path = tempFile(tempDir, "a.txt", "alpha\nfoo\ngamma\n")
        val tracker = newTracker(throttleMs = 1L)

        clockNow.set(0L)
        tracker.observe(
            callId = "c1",
            params = mapOf("path" to path, "old_string" to "foo", "new_string" to "BAR"),
            isPartial = true,
        )

        // Tool just closed.
        clockNow.set(10L)
        tracker.observe(
            callId = "c1",
            params = mapOf("path" to path, "old_string" to "foo", "new_string" to "BAR"),
            isPartial = false,
        )

        assertEquals(listOf("c1"), finalizes, "onFinalize must fire exactly once on close")

        // Stray re-parse after close — must be a no-op (tracker is finalized).
        val finalizedSnapshot = finalizes.size
        val updateSnapshot = updates.size
        clockNow.set(50L)
        tracker.observe(
            callId = "c1",
            params = mapOf("path" to path, "old_string" to "foo", "new_string" to "BAR"),
            isPartial = false,
        )
        assertEquals(finalizedSnapshot, finalizes.size, "stray re-parse must not re-fire finalize")
        assertEquals(updateSnapshot, updates.size, "stray re-parse must not fire update")
    }

    @Test
    fun `cancelAll fires onCancel for every open preview`(@TempDir tempDir: File) = runTest {
        project = newProjectFor(tempDir)
        val pa = tempFile(tempDir, "a.txt", "x foo x")
        val pb = tempFile(tempDir, "b.txt", "y foo y")
        val pc = tempFile(tempDir, "c.txt", "z foo z")
        val tracker = newTracker()

        tracker.observe("c1", mapOf("path" to pa, "old_string" to "foo", "new_string" to "A"), isPartial = true)
        tracker.observe("c2", mapOf("path" to pb, "old_string" to "foo", "new_string" to "B"), isPartial = true)
        tracker.observe("c3", mapOf("path" to pc, "old_string" to "foo", "new_string" to "C"), isPartial = true)

        assertEquals(3, opens.size)

        tracker.cancelAll()

        assertEquals(3, cancels.size)
        assertEquals(setOf("c1", "c2", "c3"), cancels.toSet())
    }

    @Test
    fun `cancel(callId) removes one preview without affecting others`(@TempDir tempDir: File) = runTest {
        project = newProjectFor(tempDir)
        val pa = tempFile(tempDir, "a.txt", "x foo x")
        val pb = tempFile(tempDir, "b.txt", "y foo y")
        val tracker = newTracker(throttleMs = 1L)

        tracker.observe("c1", mapOf("path" to pa, "old_string" to "foo", "new_string" to "A"), isPartial = true)
        tracker.observe("c2", mapOf("path" to pb, "old_string" to "foo", "new_string" to "B"), isPartial = true)

        tracker.cancel("c1")

        assertEquals(listOf("c1"), cancels)

        // c2 still alive — a subsequent update should fire.
        clockNow.set(1_000L)
        tracker.observe("c2", mapOf("path" to pb, "old_string" to "foo", "new_string" to "BB"), isPartial = true)
        assertTrue(updates.any { it.first == "c2" }, "c2 should still receive updates after c1 is cancelled")
        assertFalse(updates.any { it.first == "c1" }, "c1 must NOT receive updates after cancel")
    }

    @Test
    fun `diff sent to onOpen has hunk header anchored to real file offset`(@TempDir tempDir: File) = runTest {
        // File where "TARGET" appears around line 42 — the diff hunk header should contain that offset.
        val sb = StringBuilder()
        for (i in 1..41) sb.append("line $i\n")
        sb.append("TARGET\n")
        for (i in 43..60) sb.append("line $i\n")
        val path = tempFile(tempDir, "big.txt", sb.toString())
        project = newProjectFor(tempDir)

        val tracker = newTracker()
        tracker.observe(
            callId = "c1",
            params = mapOf("path" to path, "old_string" to "TARGET", "new_string" to "REPLACED"),
            isPartial = true,
        )

        assertEquals(1, opens.size)
        val initialDiff = opens[0].third
        // unified diff format: "@@ -<oldStart>,<oldLen> +<newStart>,<newLen> @@"
        // With 3 context lines, the hunk should start around line 39 (42 - 3), well above @@ -1, …
        val hunkLine = initialDiff.lineSequence().firstOrNull { it.startsWith("@@") }
        assertNotNull(hunkLine, "diff must contain a hunk header")
        assertFalse(
            hunkLine!!.startsWith("@@ -1,"),
            "hunk header must reflect real match offset (line ~42), not start at @@ -1, … (was: $hunkLine)"
        )
    }
}
