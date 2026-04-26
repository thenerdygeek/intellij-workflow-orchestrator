package com.workflow.orchestrator.core.workflow.ui

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.workflow.PrRef
import com.workflow.orchestrator.core.settings.PluginSettings
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
 * Phase 5 T14 — verifies spec R4: rapid focusPr toggles produce ≤2 visibility transitions.
 * Banner subscribes to `interactionModeFlow` which uses `distinctUntilChanged`, so even
 * 10 alternating focusPr/null calls collapse to at most one Live -> ReadOnly transition
 * (when the activeBranch differs from focusPr.fromBranch).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BannerVisibilityFlickerTest {

    @AfterEach fun teardown() {
        unmockkObject(LatestBuildLookup.Companion)
        unmockkObject(OpenPrLister.Companion)
    }

    @Test fun `rapid focusPr toggle results in at most 2 visibility transitions`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val settings = mockk<PluginSettings>(relaxed = true)
        every { project.getService(PluginSettings::class.java) } returns settings
        every { settings.state.activeTicketId } returns null

        mockkObject(LatestBuildLookup.Companion)
        every { LatestBuildLookup.getInstance() } returns null
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

        // Initial Live emission + at most 1 transition to ReadOnly + back to Live = 3 total
        // BUT distinctUntilChanged collapses repeated values, so at most 3 distinct values.
        // The test asserts the spec-mandated ≤2 transitions invariant (transitions = changes,
        // not absolute emissions). 'transitions.size' counts emissions; subtract 1 for the
        // initial value to get the number of actual transitions.
        val transitionCount = (transitions.size - 1).coerceAtLeast(0)
        assertTrue(transitionCount <= 2, "Banner flickered: $transitionCount transitions, history=$transitions")
    }
}
