package com.workflow.orchestrator.core.workflow

import app.cash.turbine.test
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.workflow.BuildRef
import com.workflow.orchestrator.core.model.workflow.PrRef
import com.workflow.orchestrator.core.settings.PluginSettings
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Tests for the Phase 5 T7 `focusPr` cascade in [WorkflowContextService]:
 * - mutex-serialized single-merged emission (spec §4.0, §4.4)
 * - cancel-previous via `currentFocusJob` (spec §4.0, §4.1)
 * - EP-driven build lookup via [LatestBuildLookup] (spec §4.1)
 * - chain-key resolution via [ChainKeyResolver] (Phase A)
 */
class FocusPrCascadeTest {

    @AfterEach fun teardown() {
        unmockkObject(LatestBuildLookup.Companion)
        unmockkObject(ChainKeyResolver.Companion)
    }

    private fun setup(
        project: Project,
        build: BuildRef? = null,
        chainKey: String? = "CHAIN-KEY-7",
    ) {
        val settings = mockk<PluginSettings>(relaxed = true)
        every { project.getService(PluginSettings::class.java) } returns settings
        every { settings.state.activeTicketId } returns null

        mockkObject(ChainKeyResolver.Companion)
        val resolver = mockk<ChainKeyResolver>()
        coEvery { resolver.resolveChainKey(any(), any(), any()) } returns chainKey
        every { ChainKeyResolver.getInstance() } returns resolver

        mockkObject(LatestBuildLookup.Companion)
        val lookup = mockk<LatestBuildLookup>()
        coEvery { lookup.fetchLatestBuild(any(), any()) } returns build
        every { LatestBuildLookup.getInstance() } returns lookup
    }

    @Test fun `focusPr emits exactly one new state with focusPr+focusBuild populated`() = runTest {
        val project = mockk<Project>(relaxed = true)
        setup(
            project,
            build = BuildRef("CHAIN-KEY-7", 13, "", null, chainKey = "CHAIN-KEY-7"),
        )
        val service = WorkflowContextService(project, TestScope())
        val pr = PrRef(42, "feat/abc", "main", "r", "PLAN", null)

        service.state.test {
            assertNull(awaitItem().focusPr)  // initial empty
            service.focusPr(pr)
            val next = awaitItem()
            assertEquals(pr, next.focusPr)
            assertEquals(13, next.focusBuild?.buildNumber)
            assertEquals("CHAIN-KEY-7", next.focusBuild?.chainKey)
            // Cascade overwrites BuildRef.branch with PR.fromBranch (EP impl leaves it blank).
            assertEquals("feat/abc", next.focusBuild?.branch)
            cancel()
        }
    }

    @Test fun `focusPr null clears focus chain in single emission`() = runTest {
        val project = mockk<Project>(relaxed = true)
        setup(project)
        val service = WorkflowContextService(project, TestScope())
        service.focusPr(PrRef(42, "f", "m", "r", null, null))
        service.focusPr(null)
        val s = service.state.value
        assertNull(s.focusPr)
        assertNull(s.focusBuild)
        assertNull(s.focusQualityScope)
    }

    @Test fun `rapid focusPr calls — only the last cascade survives`() = runTest {
        val project = mockk<Project>(relaxed = true)
        setup(project)
        val service = WorkflowContextService(project, TestScope())
        listOf(
            PrRef(42, "a", "m", "r", null, null),
            PrRef(43, "b", "m", "r", null, null),
            PrRef(44, "c", "m", "r", null, null),
        ).forEach { service.focusPr(it) }
        assertEquals(44, service.state.value.focusPr?.prId)
    }
}
