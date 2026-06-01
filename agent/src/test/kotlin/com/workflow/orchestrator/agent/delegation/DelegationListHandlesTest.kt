package com.workflow.orchestrator.agent.delegation

import com.intellij.openapi.project.Project
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.channels.SocketChannel

/**
 * PART 1 — service-level coverage for [DelegationOutboundService.handlesForSession], the
 * read-only accessor that lets IDE-A enumerate the delegation handles it currently holds
 * for a given delegator session (active + closed-but-retained), so it can recover lost
 * handles and correlate them. Backs the `delegation(action="list_handles")` tool action.
 */
class DelegationListHandlesTest {

    @AfterEach fun tearDown() { unmockkAll() }

    private fun newService(): DelegationOutboundService {
        val project = mockk<Project>(relaxed = true)
        val cs = CoroutineScope(SupervisorJob())
        return DelegationOutboundService(project, cs)
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

    /** Seed a closed-but-retained handle via the private RetainedHandle constructor + map. */
    private fun seedRetained(
        svc: DelegationOutboundService,
        handleId: String,
        delegatorSessionId: String,
        bSessionId: String,
        targetPath: String,
        repoName: String,
        lastState: String,
    ) {
        val retainedCls = Class.forName(
            "com.workflow.orchestrator.agent.delegation.DelegationOutboundService\$RetainedHandle"
        )
        val ctor = retainedCls.declaredConstructors.first().apply { isAccessible = true }
        // RetainedHandle(delegatorSessionId, bSessionId, targetProjectPath, repoName, lastState, capturedAt)
        val instance = ctor.newInstance(
            delegatorSessionId, bSessionId, targetPath, repoName, lastState, System.currentTimeMillis()
        )
        val f = DelegationOutboundService::class.java.getDeclaredField("retainedHandles").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (f.get(svc) as java.util.concurrent.ConcurrentHashMap<String, Any>)[handleId] = instance
    }

    private fun seedActiveHandle(
        svc: DelegationOutboundService,
        handleId: String,
        sessionId: String,
        repoName: String,
        targetPath: String,
        bSessionId: String,
        lastState: String,
    ) {
        seedChannel(svc, handleId)
        seedMap(svc, "handleToSessionId", handleId, sessionId)
        seedMap(svc, "handleToRepoName", handleId, repoName)
        seedMap(svc, "handleToTargetPath", handleId, targetPath)
        seedMap(svc, "handleToBSessionId", handleId, bSessionId)
        seedMap(svc, "handleToLastSeenState", handleId, lastState)
    }

    @Test
    fun `handlesForSession returns active handles with repo, path, bSessionId and lastState`() {
        val svc = newService()
        seedActiveHandle(svc, "h1", "sessA", "backend", "/repos/backend", "b-sess-1", "RUNNING")

        val summaries = svc.handlesForSession("sessA")
        assertEquals(1, summaries.size)
        val s = summaries.single()
        assertEquals("h1", s.handleId)
        assertEquals("backend", s.targetRepoName)
        assertEquals("/repos/backend", s.targetProjectPath)
        assertEquals("b-sess-1", s.bSessionId)
        assertEquals("RUNNING", s.lastState)
    }

    @Test
    fun `handlesForSession includes closed-but-retained handles for the session`() {
        val svc = newService()
        seedActiveHandle(svc, "h-active", "sessA", "backend", "/repos/backend", "b-1", "RUNNING")
        seedRetained(svc, "h-closed", "sessA", "b-2", "/repos/frontend", "frontend", "COMPLETED")

        val summaries = svc.handlesForSession("sessA").associateBy { it.handleId }
        assertEquals(2, summaries.size)
        assertEquals("RUNNING", summaries["h-active"]!!.lastState)
        val closed = summaries["h-closed"]!!
        assertEquals("frontend", closed.targetRepoName)
        assertEquals("/repos/frontend", closed.targetProjectPath)
        assertEquals("b-2", closed.bSessionId)
        assertEquals("COMPLETED", closed.lastState)
    }

    @Test
    fun `handlesForSession excludes handles belonging to another session`() {
        val svc = newService()
        seedActiveHandle(svc, "h-mine", "sessA", "backend", "/repos/backend", "b-1", "RUNNING")
        seedActiveHandle(svc, "h-other", "sessB", "frontend", "/repos/frontend", "b-2", "RUNNING")
        seedRetained(svc, "h-other-closed", "sessB", "b-3", "/repos/other", "other", "FAILED")

        val summaries = svc.handlesForSession("sessA")
        assertEquals(1, summaries.size)
        assertEquals("h-mine", summaries.single().handleId)
    }

    @Test
    fun `handlesForSession returns empty list for a session with no delegations`() {
        val svc = newService()
        assertTrue(svc.handlesForSession("nobody").isEmpty())
    }

    @Test
    fun `lastState of an active handle comes from the SSOT and defaults to RUNNING`() {
        val svc = newService()
        // Seed an active handle WITHOUT a last-seen state — handleState() defaults to RUNNING.
        seedChannel(svc, "h-nostate")
        seedMap(svc, "handleToSessionId", "h-nostate", "sessA")
        seedMap(svc, "handleToRepoName", "h-nostate", "backend")
        seedMap(svc, "handleToTargetPath", "h-nostate", "/repos/backend")
        seedMap(svc, "handleToBSessionId", "h-nostate", "b-1")

        val s = svc.handlesForSession("sessA").single()
        assertEquals("RUNNING", s.lastState)
    }

    @Test
    fun `bSessionId may be null when the handle never recorded one`() {
        val svc = newService()
        // A retained handle always has a bSessionId, but a partially-seeded active handle may not.
        seedChannel(svc, "h-partial")
        seedMap(svc, "handleToSessionId", "h-partial", "sessA")
        seedMap(svc, "handleToRepoName", "h-partial", "backend")
        seedMap(svc, "handleToTargetPath", "h-partial", "/repos/backend")

        val s = svc.handlesForSession("sessA").single()
        assertNull(s.bSessionId)
    }
}
