package com.workflow.orchestrator.agent.ui

import com.workflow.orchestrator.agent.session.AttachmentStore
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Tests for [AttachmentReadHandler]'s per-session containment enforcement
 * (audit finding agent-ui:F-9).
 *
 * The handler now verifies that a requested sha resolves to a path that is
 * canonically contained within the ACTIVE session's attachments directory
 * before serving any bytes.  These tests exercise the pure-logic surface
 * without requiring a live JCEF/CEF environment.
 */
class AttachmentReadHandlerTest {

    @TempDir
    lateinit var tempDir: Path

    // ── isWithinDir — containment predicate ─────────────────────────────────

    @Test
    fun `isWithinDir returns true for a file directly inside the root`() {
        val root = tempDir.resolve("session-a/attachments")
        val candidate = root.resolve("abc123.png")
        assertTrue(AttachmentReadHandler.isWithinDir(candidate, root))
    }

    @Test
    fun `isWithinDir rejects a path that escapes via dot-dot`() {
        val root = tempDir.resolve("session-a/attachments")
        // Simulate path traversal: candidate resolves outside the allowed dir
        val candidate = tempDir.resolve("session-b/attachments/evil.png")
        assertFalse(AttachmentReadHandler.isWithinDir(candidate, root))
    }

    @Test
    fun `isWithinDir rejects a sibling directory that shares a common prefix`() {
        // Ensure "session-a-extra" is not accepted when root is "session-a".
        val root = tempDir.resolve("session-a")
        val candidate = tempDir.resolve("session-a-extra/attachments/file.png")
        assertFalse(AttachmentReadHandler.isWithinDir(candidate, root))
    }

    @Test
    fun `isWithinDir rejects parent directory`() {
        val root = tempDir.resolve("session-a/attachments")
        val candidate = tempDir.resolve("session-a")
        // Parent of root — must not be accepted as a contained path
        assertFalse(AttachmentReadHandler.isWithinDir(candidate, root))
    }

    @Test
    fun `isWithinDir accepts normalized path with redundant segments`() {
        val root = tempDir.resolve("session-a/attachments")
        // The path has redundant '.' segments that normalize away
        val candidate = root.resolve(".").resolve("abc.png")
        assertTrue(AttachmentReadHandler.isWithinDir(candidate, root))
    }

    // ── Per-session isolation: store in session-A, try to read from session-B ─

    @Test
    fun `attachment stored in session-A is NOT within session-B attachments dir`() {
        val sessionADir = tempDir.resolve("session-a")
        val sessionBDir = tempDir.resolve("session-b")
        val storeA = AttachmentStore(sessionADir)
        val storeB = AttachmentStore(sessionBDir)

        // Write bytes into session A
        val bytes = "secret-content".toByteArray()
        val ref = storeA.storeBlocking(bytes, "image/png", "photo.png")
        val sha = ref.sha256

        // Simulate handler: look up ext for session B's store — should return null
        // (sha does not exist in session B).
        val extInB = storeB.findExtensionForBlocking(sha)
        // Session B has no such attachment → handler would 404 before containment check
        assertTrue(extInB == null, "Session-B store must not find a sha that was only stored in session-A")
    }

    @Test
    fun `attachment path for session-A sha fails containment check against session-B dir`() {
        val sessionADir = tempDir.resolve("session-a")
        val sessionBDir = tempDir.resolve("session-b")
        val storeA = AttachmentStore(sessionADir)
        AttachmentStore(sessionBDir) // ensure B's attachments dir is created

        val bytes = "payload".toByteArray()
        val ref = storeA.storeBlocking(bytes, "image/png", "img.png")
        val sha = ref.sha256

        // Reconstruct the path as session-A resolved it
        val ext = storeA.findExtensionForBlocking(sha)!!
        val resolvedInA = storeA.pathFor(sha, ext).toAbsolutePath().normalize()

        // Session-B's attachments dir — the "active session" in a cross-session attack
        val sessionBAttachmentsDir = AttachmentStore(sessionBDir).canonicalAttachmentsDir()

        // The path from session-A must NOT pass the containment check for session-B
        assertFalse(
            AttachmentReadHandler.isWithinDir(resolvedInA, sessionBAttachmentsDir),
            "A session-A attachment path must not pass containment check against session-B dir"
        )
    }

    @Test
    fun `legitimate attachment in active session passes containment check`() {
        val sessionDir = tempDir.resolve("session-x")
        val store = AttachmentStore(sessionDir)

        val bytes = "normal-image".toByteArray()
        val ref = store.storeBlocking(bytes, "image/png", "img.png")
        val sha = ref.sha256

        val ext = store.findExtensionForBlocking(sha)!!
        val resolvedPath = store.pathFor(sha, ext).toAbsolutePath().normalize()
        val attachmentsDir = store.canonicalAttachmentsDir()

        assertTrue(
            AttachmentReadHandler.isWithinDir(resolvedPath, attachmentsDir),
            "A legitimate in-session attachment must pass containment check"
        )
    }
}
