package com.workflow.orchestrator.core.polling

import com.intellij.openapi.application.ApplicationManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class SmartPollerFocusGateTest {

    @Test
    fun `focused and visible uses base interval times backoff`() {
        assertEquals(
            45_000L,
            SmartPoller.effectiveIntervalMs(
                baseIntervalMs = 30_000, maxIntervalMs = 300_000,
                backoff = 1.5, visible = true, focused = true,
            ),
        )
    }

    @Test
    fun `unfocused multiplies by 4`() {
        assertEquals(
            120_000L,
            SmartPoller.effectiveIntervalMs(
                baseIntervalMs = 30_000, maxIntervalMs = 300_000,
                backoff = 1.0, visible = true, focused = false,
            ),
        )
    }

    @Test
    fun `hidden tab multiplies by 4 even when focused`() {
        assertEquals(
            120_000L,
            SmartPoller.effectiveIntervalMs(
                baseIntervalMs = 30_000, maxIntervalMs = 300_000,
                backoff = 1.0, visible = false, focused = true,
            ),
        )
    }

    @Test
    fun `background interval is capped at maxIntervalMs`() {
        assertEquals(
            300_000L,
            SmartPoller.effectiveIntervalMs(
                baseIntervalMs = 30_000, maxIntervalMs = 300_000,
                backoff = 10.0, visible = false, focused = false,
            ),
        )
    }

    @Test
    fun `defaultIdeFocused returns false when no Application exists`() {
        // Guard against order-dependence: a BasePlatformTestCase in this module
        // (WorkflowContextEditorIntegrationTest) boots a headless Application in the same
        // un-forked test JVM, and ApplicationImpl.isActive() returns TRUE in headless mode.
        // Skip rather than fail when the platform Application is already up.
        assumeTrue(ApplicationManager.getApplication() == null)
        // Unit-test JVM has no IntelliJ Application — must not throw, must be conservative.
        assertFalse(SmartPoller.defaultIdeFocused())
    }

    @Test
    fun `unfocused poller polls 4x slower than focused poller`() = runTest {
        val focusedPolls = AtomicInteger(0)
        val unfocusedPolls = AtomicInteger(0)

        val focused = SmartPoller(
            name = "focused", baseIntervalMs = 30_000, maxIntervalMs = 300_000,
            scope = this, networkProbe = null, ideFocused = { true },
            action = { focusedPolls.incrementAndGet(); false },
        )
        val unfocused = SmartPoller(
            name = "unfocused", baseIntervalMs = 30_000, maxIntervalMs = 300_000,
            scope = this, networkProbe = null, ideFocused = { false },
            action = { unfocusedPolls.incrementAndGet(); false },
        )

        focused.start()
        unfocused.start()
        // Both poll immediately at t=0. Next focused delay ∈ [1.35, 1.65]·base
        // (backoff 1.5 after no-change, ±10% jitter); next unfocused delay ∈ [5.4, 6.6]·base
        // (backoff 1.5 × multiplier 4 ± 10%).
        advanceTimeBy(75_000)
        focused.stop()
        unfocused.stop()

        assertTrue(focusedPolls.get() >= 2, "focused poller should have re-polled (got ${focusedPolls.get()})")
        assertEquals(1, unfocusedPolls.get(), "unfocused poller must still be waiting on its 4x interval")
    }
}
