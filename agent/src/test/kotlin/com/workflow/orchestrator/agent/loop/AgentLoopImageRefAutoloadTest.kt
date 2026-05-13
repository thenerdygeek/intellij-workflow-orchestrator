package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.core.services.ToolResult as CoreToolResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the production gate ([AgentLoopTestSupport.gateImageRefs]) that
 * AgentLoop uses to decide whether tool-produced image refs flow into the
 * next turn. Both the visual-support master kill switch
 * ([com.workflow.orchestrator.core.settings.PluginSettings.State.enableImageInput])
 * and the tool-autoload sub-flag
 * ([com.workflow.orchestrator.core.settings.PluginSettings.State.enableToolImageAutoload])
 * must be ON for refs to pass through; ANY OFF state suppresses them.
 *
 * Tests call the helper directly so the gate logic is exercised regardless of
 * the AgentLoop machinery around it. The same helper is invoked from
 * [AgentLoop.processToolResult] (see "AgentLoopTestSupport.gateImageRefs" call
 * around line 1850), so test coverage and production behaviour can never drift.
 */
class AgentLoopImageRefAutoloadTest {

    private val nonEmptyImageRefs = listOf(
        CoreToolResult.ImageRefData("sha-img-1", "image/png", 1024, "screenshot.png"),
    )

    private fun toolResultWithImageRefs(refs: List<CoreToolResult.ImageRefData>): CoreToolResult<Unit> =
        CoreToolResult(
            data = Unit,
            summary = "downloaded screenshot.png",
            isError = false,
            imageRefs = refs,
        )

    @Test
    fun `master OFF and autoload OFF produces empty image refs`() {
        val gated = AgentLoopTestSupport.gateImageRefs(
            toolResult = toolResultWithImageRefs(nonEmptyImageRefs),
            masterEnabled = false,
            autoloadEnabled = false,
        )
        assertTrue(gated.isEmpty(), "Both flags OFF must suppress image refs. Got: $gated")
    }

    @Test
    fun `master OFF and autoload ON produces empty image refs`() {
        val gated = AgentLoopTestSupport.gateImageRefs(
            toolResult = toolResultWithImageRefs(nonEmptyImageRefs),
            masterEnabled = false,
            autoloadEnabled = true,
        )
        assertTrue(gated.isEmpty(), "Master OFF must override autoload ON. Got: $gated")
    }

    @Test
    fun `master ON and autoload OFF produces empty image refs`() {
        val gated = AgentLoopTestSupport.gateImageRefs(
            toolResult = toolResultWithImageRefs(nonEmptyImageRefs),
            masterEnabled = true,
            autoloadEnabled = false,
        )
        assertTrue(gated.isEmpty(), "Autoload OFF must suppress refs even when master is ON. Got: $gated")
    }

    @Test
    fun `master ON and autoload ON forwards image refs as ContentBlock ImageRef entries`() {
        val gated = AgentLoopTestSupport.gateImageRefs(
            toolResult = toolResultWithImageRefs(nonEmptyImageRefs),
            masterEnabled = true,
            autoloadEnabled = true,
        )
        assertEquals(1, gated.size, "Both flags ON must forward the single image ref")
        assertEquals("sha-img-1", gated[0].sha256)
        assertEquals("image/png", gated[0].mime)
        assertEquals(1024L, gated[0].size)
        assertEquals("screenshot.png", gated[0].originalFilename)
    }

    @Test
    fun `empty input image refs produces empty output regardless of flag state`() {
        val gated = AgentLoopTestSupport.gateImageRefs(
            toolResult = toolResultWithImageRefs(emptyList()),
            masterEnabled = true,
            autoloadEnabled = true,
        )
        assertTrue(gated.isEmpty(), "No input refs → no output refs even with both flags ON. Got: $gated")
    }
}
