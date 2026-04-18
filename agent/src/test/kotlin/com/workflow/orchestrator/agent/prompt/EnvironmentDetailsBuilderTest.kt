package com.workflow.orchestrator.agent.prompt

import com.intellij.openapi.project.Project
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EnvironmentDetailsBuilderTest {

    private val project = mockk<Project>(relaxed = true)

    @Test
    fun `branch and target branch appear in output when provided`() {
        val result = EnvironmentDetailsBuilder.build(
            project = project,
            planModeEnabled = false,
            contextManager = null,
            currentBranch = "feature/my-feature",
            defaultTargetBranch = "develop"
        )
        assertTrue(result.contains("feature/my-feature"), "current branch must appear")
        assertTrue(result.contains("target: develop"), "target branch must appear in parens")
    }

    @Test
    fun `branch section omitted when currentBranch is null`() {
        val result = EnvironmentDetailsBuilder.build(
            project = project,
            planModeEnabled = false,
            contextManager = null,
            currentBranch = null,
            defaultTargetBranch = null
        )
        assertFalse(result.contains("# Current Branch"), "branch section must not appear when null")
    }

    @Test
    fun `target branch omitted gracefully when only branch is provided`() {
        val result = EnvironmentDetailsBuilder.build(
            project = project,
            planModeEnabled = false,
            contextManager = null,
            currentBranch = "main",
            defaultTargetBranch = null
        )
        assertTrue(result.contains("main"))
        assertFalse(result.contains("target:"), "target label must not appear when defaultTargetBranch is null")
    }

    @Test
    fun `output is wrapped in environment_details tags`() {
        val result = EnvironmentDetailsBuilder.build(
            project = project,
            planModeEnabled = false,
            contextManager = null
        )
        assertTrue(result.startsWith("<environment_details>"))
        assertTrue(result.endsWith("</environment_details>"))
    }
}
