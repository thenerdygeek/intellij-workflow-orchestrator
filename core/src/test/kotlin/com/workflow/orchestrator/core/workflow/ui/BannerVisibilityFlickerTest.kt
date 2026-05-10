package com.workflow.orchestrator.core.workflow.ui

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.workflow.PrRef
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.workflow.ChainKeyResolver
import com.workflow.orchestrator.core.workflow.LatestBuildLookup
import com.workflow.orchestrator.core.workflow.OpenPrLister
import com.workflow.orchestrator.core.workflow.WorkflowContextService
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Phase 5 T14 — verifies that the upstream [interactionModeFlow] subscribed to by
 * [ReadOnlyBanner] is a `StateFlow` whose conflation collapses unobserved
 * intermediate values. Under `runTest`'s single-threaded scheduler, 10 alternating
 * `focusPr(pr)`/`focusPr(null)` calls do NOT produce 20 collected emissions — most
 * are dropped because the collector only resumes after `advanceUntilIdle()`, by
 * which time `_state.value` reflects the final write.
 *
 * This is a regression guard against a future maintainer accidentally swapping
 * `stateIn(...)` for `shareIn(replay = N)` in [WorkflowContextService.interactionModeFlow] —
 * such a swap would replay every intermediate value and break the conflation guarantee
 * the banner relies on.
 *
 * NOTE: this test does NOT prove the user-perceptible R4 invariant ("no banner flicker
 * on rapid clicks"). That invariant rests on real EDT dispatch + human click cadence and
 * cannot be validated under a virtual-time scheduler. A real-EDT smoke test belongs in
 * a separate platform-fixture test (deferred).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BannerVisibilityFlickerTest {

    @AfterEach fun teardown() {
        unmockkObject(LatestBuildLookup.Companion)
        unmockkObject(ChainKeyResolver.Companion)
        unmockkObject(OpenPrLister.Companion)
    }

    @Test fun `interactionModeFlow conflates intermediate transitions under StateFlow semantics`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val settings = mockk<PluginSettings>(relaxed = true)
        every { project.getService(PluginSettings::class.java) } returns settings
        every { settings.state.activeTicketId } returns null

        mockkObject(LatestBuildLookup.Companion)
        every { LatestBuildLookup.getInstance() } returns null
        mockkObject(ChainKeyResolver.Companion)
        every { ChainKeyResolver.getInstance() } returns null
        mockkObject(OpenPrLister.Companion)
        every { OpenPrLister.getInstance() } returns null

        val service = WorkflowContextService(project, TestScope(testScheduler))

        // Subscribe to interactionMode and record every transition.
        val transitions = mutableListOf<com.workflow.orchestrator.core.model.workflow.InteractionMode>()
        val collectorJob = backgroundScope.launch {
            service.interactionModeFlow.collect { transitions.add(it) }
        }
        advanceUntilIdle()

        // 10 alternating focus changes; activeBranch is null so any non-null focusPr -> ReadOnly.
        val pr = PrRef(42, "bugfix/xyz", "main", "r", null, null)
        repeat(10) {
            service.focusPr(pr)
            service.focusPr(null)
        }
        advanceUntilIdle()

        collectorJob.cancel()

        // Under runTest's single-threaded scheduler, StateFlow conflation drops intermediate
        // emissions — the collector observes ≤2 transitions (typically 1: Live -> Live again,
        // since the final state matches the initial). This guards the conflation property,
        // not user-perceptible flicker.
        val transitionCount = (transitions.size - 1).coerceAtLeast(0)
        assertTrue(transitionCount <= 2, "Banner flickered: $transitionCount transitions, history=$transitions")
    }
}
