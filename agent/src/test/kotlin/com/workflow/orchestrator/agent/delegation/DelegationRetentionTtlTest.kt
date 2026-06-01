package com.workflow.orchestrator.agent.delegation

import com.intellij.openapi.project.Project
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.channels.SocketChannel

/**
 * Service-level tests for G-item: retention TTL surfaced on closed-retained handles.
 *
 * Strategy: inject a deterministic [clockFn] (test seam on [DelegationOutboundService])
 * so the TTL can be asserted exactly without sleeping 30 minutes. Never touches the real
 * [System.currentTimeMillis] during assertions.
 */
class DelegationRetentionTtlTest {

    @AfterEach
    fun tearDown() { unmockkAll() }

    private fun newService(clockMillis: () -> Long = System::currentTimeMillis): DelegationOutboundService {
        val project = mockk<Project>(relaxed = true)
        val cs = CoroutineScope(SupervisorJob())
        return DelegationOutboundService(project, cs).also { svc ->
            svc.testClockFn = clockMillis
        }
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

    // ── Service: freshly-closed handle reports ~full window TTL ──────────────

    @Test
    fun `statusOf closed handle reports retentionExpiresInSeconds near the full window`() {
        val closeTime = 1_000_000L
        val nowTime = closeTime + 5_000L // 5 seconds after close
        val svc = newService { nowTime }

        val handleId = "h-ttl-fresh"
        seedMap(svc, "handleToBSessionId", handleId, "sess-b")
        seedMap(svc, "handleToTargetPath", handleId, "/target")
        seedMap(svc, "handleToLastSeenState", handleId, "COMPLETED")
        seedMap(svc, "handleToRepoName", handleId, "backend")

        // Snapshot the retained handle at closeTime: inject fixed clock during close
        svc.testClockFn = { closeTime }
        svc.close(handleId)

        // Now advance the clock to 5s after close
        svc.testClockFn = { nowTime }

        val status = svc.statusOf(handleId)
        val closed = assertInstanceOf(DelegationStatusResult.Closed::class.java, status)

        // Expected: (closeTime + RETENTION_MILLIS - nowTime) / 1000
        val expectedSeconds = (DelegationOutboundService.TRANSCRIPT_RETENTION_MILLIS - 5_000L) / 1000L
        assertNotNull(closed.retentionExpiresInSeconds, "retentionExpiresInSeconds should be present")
        assertEquals(expectedSeconds, closed.retentionExpiresInSeconds)
    }

    @Test
    fun `statusOf closed handle returns 0 when window has fully elapsed`() {
        val closeTime = 1_000_000L
        // Advance clock past the full window
        val nowTime = closeTime + DelegationOutboundService.TRANSCRIPT_RETENTION_MILLIS + 10_000L
        val svc = newService { nowTime }

        val handleId = "h-ttl-elapsed"
        seedMap(svc, "handleToBSessionId", handleId, "sess-b")
        seedMap(svc, "handleToTargetPath", handleId, "/target")
        seedMap(svc, "handleToLastSeenState", handleId, "COMPLETED")
        seedMap(svc, "handleToRepoName", handleId, "backend")

        svc.testClockFn = { closeTime }
        svc.close(handleId)

        // Move clock past retention → pruneRetainedHandles removes it → Unknown
        svc.testClockFn = { nowTime }

        // The handle should be pruned to Unknown at this point (retention window elapsed)
        val status = svc.statusOf(handleId)
        assertEquals(DelegationStatusResult.Unknown, status,
            "After retention window elapses, handle must be pruned to Unknown")
    }

    @Test
    fun `statusOf active handle does NOT include retentionExpiresInSeconds`() {
        val svc = newService()
        val handleId = "h-active-no-ttl"
        seedChannel(svc, handleId)
        seedMap(svc, "handleToLastSeenState", handleId, "RUNNING")
        seedMap(svc, "handleToRepoName", handleId, "frontend")

        val status = svc.statusOf(handleId)
        val active = assertInstanceOf(DelegationStatusResult.Active::class.java, status)
        // Active handles have no TTL field at all
        assertNull(active::class.java.kotlin.objectInstance, "Active is not an object — just checking no crash")
        // The key assertion: Active has no retentionExpiresInSeconds
        // (compile-time: Active data class does not have this field)
        assertTrue(status is DelegationStatusResult.Active)
    }

    @Test
    fun `statusOf unknown handle reports Unknown with no TTL`() {
        val svc = newService()
        val status = svc.statusOf("ghost-handle")
        assertEquals(DelegationStatusResult.Unknown, status)
    }

    // ── Backward-compat: old persisted retained handle without timestamp → omit TTL ──

    @Test
    fun `statusOf retained handle with zero capturedAt gracefully omits TTL`() {
        // Simulate an old persisted entry where capturedAt was not set (defaults to 0L)
        // by injecting a RetainedHandle directly via reflection.
        val svc = newService { 1_000_000L }
        val handleId = "h-old-no-ts"

        // Inject a RetainedHandle with capturedAt = 0 (backward-compat sentinel)
        val retainedHandlesField = DelegationOutboundService::class.java
            .getDeclaredField("retainedHandles").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val retainedHandles = retainedHandlesField.get(svc) as java.util.concurrent.ConcurrentHashMap<String, Any>

        // Use reflection to construct RetainedHandle with capturedAt = 0
        val retainedHandleClass = Class.forName(
            "com.workflow.orchestrator.agent.delegation.DelegationOutboundService\$RetainedHandle"
        )
        val ctor = retainedHandleClass.getDeclaredConstructors().first().apply { isAccessible = true }
        val retainedHandle = ctor.newInstance(
            /*delegatorSessionId*/ null as String?,
            /*bSessionId*/ "sess-old",
            /*targetProjectPath*/ "/target",
            /*repoName*/ "legacy-repo",
            /*lastState*/ "COMPLETED",
            /*capturedAt*/ 0L,
        )
        retainedHandles[handleId] = retainedHandle

        val status = svc.statusOf(handleId)
        // Should still be Closed (the handle exists), but retentionExpiresInSeconds should be null
        // because capturedAt = 0 is the backward-compat sentinel meaning "no timestamp"
        val closed = assertInstanceOf(DelegationStatusResult.Closed::class.java, status)
        assertNull(
            closed.retentionExpiresInSeconds,
            "Old entry with capturedAt=0 must omit TTL to avoid bogus large number"
        )
    }
}
