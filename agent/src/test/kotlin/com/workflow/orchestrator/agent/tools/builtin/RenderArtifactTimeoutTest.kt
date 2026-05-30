package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.ArtifactRenderResult
import com.workflow.orchestrator.core.settings.PluginSettings
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Verifies that `render_artifact` reads its render budget from the configurable
 * [PluginSettings.State.artifactRenderTimeoutSeconds] (2026-05-30) and clamps it to the
 * [ArtifactResultRegistry.MIN_TIMEOUT_SECONDS]..[ArtifactResultRegistry.MAX_TIMEOUT_SECONDS]
 * range before passing it to the registry. The base [RenderArtifactToolTest] exercises the
 * defensive fallback (no PluginSettings) instead.
 */
class RenderArtifactTimeoutTest {

    private val tool = RenderArtifactTool()

    @AfterEach fun tearDown() { unmockkAll() }

    private fun runWithSettingSeconds(seconds: Int): Long {
        val project = mockk<Project>(relaxed = true)
        val registry = mockk<ArtifactResultRegistry>()
        val timeoutSlot = slot<Long>()
        coEvery { registry.renderAndAwait(any(), capture(timeoutSlot)) } returns
            ArtifactRenderResult.Skipped("test")
        every { project.getService(ArtifactResultRegistry::class.java) } returns registry

        mockkObject(PluginSettings.Companion)
        val settings = mockk<PluginSettings>(relaxed = true)
        val state = PluginSettings.State().apply { artifactRenderTimeoutSeconds = seconds }
        every { settings.state } returns state
        every { PluginSettings.getInstance(any()) } returns settings

        val params = buildJsonObject {
            put("title", "T")
            put("source", "export default function App() { return <div/>; }")
        }
        runTest { tool.execute(params, project) }
        return timeoutSlot.captured
    }

    @Test
    fun `passes the configured timeout through to the registry`() {
        assertEquals(120_000L, runWithSettingSeconds(120))
    }

    @Test
    fun `clamps an over-large timeout to the max`() {
        assertEquals(
            ArtifactResultRegistry.MAX_TIMEOUT_SECONDS * 1000L,
            runWithSettingSeconds(100_000)
        )
    }

    @Test
    fun `clamps a too-small timeout to the min`() {
        assertEquals(
            ArtifactResultRegistry.MIN_TIMEOUT_SECONDS * 1000L,
            runWithSettingSeconds(1)
        )
    }
}
