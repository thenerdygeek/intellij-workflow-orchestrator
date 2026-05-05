package com.workflow.orchestrator.bamboo.ui

import com.workflow.orchestrator.core.services.ToolResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BuildPlanResolutionPolicyTest {

    @Test
    fun `waterfall hit returns UseDetected with the resolved branch plan key`() {
        val detection = ToolResult(
            data = "PROJ-PLAN138",
            summary = "Auto-detected Bamboo plan: PROJ-PLAN138"
        )
        val resolution = BuildPlanResolutionPolicy.resolve(
            detection = detection,
            configuredMasterKey = "PROJ-PLAN"
        )
        assertEquals(BuildPlanResolutionPolicy.Resolution.UseDetected("PROJ-PLAN138"), resolution)
    }

    @Test
    fun `waterfall miss with configured master falls back to UseConfigured`() {
        val detection = ToolResult(
            data = "",
            summary = "no Bamboo plan auto-detected",
            isError = true
        )
        val resolution = BuildPlanResolutionPolicy.resolve(
            detection = detection,
            configuredMasterKey = "PROJ-PLAN"
        )
        assertEquals(BuildPlanResolutionPolicy.Resolution.UseConfigured("PROJ-PLAN"), resolution)
    }

    @Test
    fun `waterfall miss with no configured master returns NoPlan with actionable hint`() {
        val detection = ToolResult(
            data = "",
            summary = "no Bamboo plan auto-detected",
            isError = true
        )
        val resolution = BuildPlanResolutionPolicy.resolve(
            detection = detection,
            configuredMasterKey = null
        )
        assertTrue(resolution is BuildPlanResolutionPolicy.Resolution.NoPlan)
        val hint = (resolution as BuildPlanResolutionPolicy.Resolution.NoPlan).hintMessage
        assertTrue(hint.contains("No Bamboo build"), "hint should explain the situation: $hint")
        assertTrue(hint.contains("Settings"), "hint should point to Settings: $hint")
    }

    @Test
    fun `waterfall returns blank data with non-error status — treated as miss`() {
        val detection = ToolResult(data = "", summary = "")
        val resolution = BuildPlanResolutionPolicy.resolve(
            detection = detection,
            configuredMasterKey = "PROJ-PLAN"
        )
        assertEquals(BuildPlanResolutionPolicy.Resolution.UseConfigured("PROJ-PLAN"), resolution)
    }

    @Test
    fun `blank configuredMasterKey treated same as null`() {
        val detection = ToolResult(data = "", summary = "miss", isError = true)
        val resolution = BuildPlanResolutionPolicy.resolve(
            detection = detection,
            configuredMasterKey = ""
        )
        assertTrue(resolution is BuildPlanResolutionPolicy.Resolution.NoPlan)
    }
}
