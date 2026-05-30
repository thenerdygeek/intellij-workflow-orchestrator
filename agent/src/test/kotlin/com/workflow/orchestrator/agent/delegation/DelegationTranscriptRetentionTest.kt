package com.workflow.orchestrator.agent.delegation

import com.intellij.openapi.project.Project
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for the 2026-05-30 transcript-retention rework of [DelegationOutboundService]:
 * fetch_transcript now reads IDE-B's history directly off the shared filesystem (no IPC
 * round-trip), works AFTER the channel closes via a retained snapshot, and the new
 * [DelegationOutboundService.statusOf] reports active/closed/unknown.
 *
 * Root causes fixed (reported by a live agent): "no conversation history on disk" (the
 * old IPC path sent IDE-A's session id to IDE-B) and "handle_not_found" 83 s after a
 * COMPLETED delegation (the handle was torn down the instant the result arrived).
 */
class DelegationTranscriptRetentionTest {

    @AfterEach fun tearDown() { unmockkAll() }

    private fun newService(): Pair<DelegationOutboundService, Project> {
        val project = mockk<Project>(relaxed = true)
        val cs = CoroutineScope(SupervisorJob())
        return DelegationOutboundService(project, cs) to project
    }

    private fun seedMap(svc: DelegationOutboundService, field: String, key: String, value: String) {
        val f = DelegationOutboundService::class.java.getDeclaredField(field).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (f.get(svc) as java.util.concurrent.ConcurrentHashMap<String, String>)[key] = value
    }

    private fun seedChannel(svc: DelegationOutboundService, handleId: String) {
        val f = DelegationOutboundService::class.java.getDeclaredField("activeChannels").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (f.get(svc) as java.util.concurrent.ConcurrentHashMap<String, SocketChannel>)[handleId] = mockk(relaxed = true)
    }

    private fun seedRemoteHistory(remoteAgentDir: Path, bSessionId: String, content: String): Path {
        val sessionDir = remoteAgentDir.resolve("sessions/$bSessionId")
        Files.createDirectories(sessionDir)
        val history = sessionDir.resolve("api_conversation_history.json")
        Files.writeString(history, content)
        return history
    }

    // ── fetch_transcript: live handle ─────────────────────────────────────────

    @Test
    fun `fetchTranscript reads IDE-B history directly and copies it locally`(
        @TempDir remote: Path,
        @TempDir local: Path,
    ) {
        val (svc, _) = newService()
        val handleId = "h-live"
        seedMap(svc, "handleToBSessionId", handleId, "sess-b")
        seedMap(svc, "handleToTargetPath", handleId, "/whatever/target")
        svc.testRemoteAgentDirResolver = { remote }
        svc.testLocalCacheDir = local
        seedRemoteHistory(remote, "sess-b", """[{"role":"user","content":"hi"}]""")

        val result = runBlocking { svc.fetchTranscript(handleId) }

        val ok = assertInstanceOf(FetchTranscriptResult.Ok::class.java, result)
        val copied = Path.of(ok.transcriptPath)
        assertTrue(Files.exists(copied), "transcript should be copied into the local cache dir")
        assertEquals("""[{"role":"user","content":"hi"}]""", Files.readString(copied))
    }

    // ── fetch_transcript: AFTER completion (handle closed) ────────────────────

    @Test
    fun `fetchTranscript still works after close via retained snapshot`(
        @TempDir remote: Path,
        @TempDir local: Path,
    ) {
        val (svc, _) = newService()
        val handleId = "h-closed"
        seedMap(svc, "handleToBSessionId", handleId, "sess-b2")
        seedMap(svc, "handleToTargetPath", handleId, "/whatever/target2")
        svc.testRemoteAgentDirResolver = { remote }
        svc.testLocalCacheDir = local
        seedRemoteHistory(remote, "sess-b2", "[]")

        // Simulate completion: the reader loop's finally tears the handle down.
        svc.close(handleId)

        val result = runBlocking { svc.fetchTranscript(handleId) }
        assertInstanceOf(FetchTranscriptResult.Ok::class.java, result)
    }

    @Test
    fun `fetchTranscript returns handle_not_found for an unknown handle`() {
        val (svc, _) = newService()
        val result = runBlocking { svc.fetchTranscript("nope") }
        val nf = assertInstanceOf(FetchTranscriptResult.NotFound::class.java, result)
        assertEquals("handle_not_found", nf.reason)
    }

    @Test
    fun `fetchTranscript reports missing history rather than a stale session id`(
        @TempDir remote: Path,
    ) {
        val (svc, _) = newService()
        val handleId = "h-nohist"
        seedMap(svc, "handleToBSessionId", handleId, "sess-missing")
        seedMap(svc, "handleToTargetPath", handleId, "/whatever")
        svc.testRemoteAgentDirResolver = { remote } // no history file created

        val result = runBlocking { svc.fetchTranscript(handleId) }
        val nf = assertInstanceOf(FetchTranscriptResult.NotFound::class.java, result)
        assertTrue(nf.reason.contains("no conversation history"), "got: ${nf.reason}")
    }

    // ── status ────────────────────────────────────────────────────────────────

    @Test
    fun `statusOf reports Active for an open handle`() {
        val (svc, _) = newService()
        val handleId = "h-active"
        seedChannel(svc, handleId)
        seedMap(svc, "handleToLastSeenState", handleId, "RUNNING")
        seedMap(svc, "handleToRepoName", handleId, "backend")

        val status = svc.statusOf(handleId)
        val active = assertInstanceOf(DelegationStatusResult.Active::class.java, status)
        assertEquals("RUNNING", active.state)
        assertEquals("backend", active.repoName)
    }

    @Test
    fun `statusOf reports Closed after the handle closes`() {
        val (svc, _) = newService()
        val handleId = "h-done"
        seedMap(svc, "handleToBSessionId", handleId, "sess-b3")
        seedMap(svc, "handleToTargetPath", handleId, "/x")
        seedMap(svc, "handleToLastSeenState", handleId, "RUNNING")
        seedMap(svc, "handleToRepoName", handleId, "frontend")

        svc.close(handleId)

        val status = svc.statusOf(handleId)
        val closed = assertInstanceOf(DelegationStatusResult.Closed::class.java, status)
        assertEquals("RUNNING", closed.lastState)
        assertEquals("frontend", closed.repoName)
    }

    @Test
    fun `statusOf reports Unknown for an unseen handle`() {
        val (svc, _) = newService()
        assertEquals(DelegationStatusResult.Unknown, svc.statusOf("ghost"))
    }
}
