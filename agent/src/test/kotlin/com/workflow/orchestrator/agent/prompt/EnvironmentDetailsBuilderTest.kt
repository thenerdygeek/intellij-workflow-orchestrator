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

    @Test
    fun `multi-repo project labels primary branch and lists other repos`() {
        val result = EnvironmentDetailsBuilder.build(
            project = project,
            planModeEnabled = false,
            contextManager = null,
            currentBranch = "feature/ORDER-42",
            defaultTargetBranch = "main",
            primaryRepoLabel = "services/order",
            otherRepoBranches = listOf(
                "services/payment" to "develop",
                "common" to "main"
            )
        )
        assertTrue(
            result.contains("services/order: feature/ORDER-42 (target: main)"),
            "primary repo should be labelled alongside its branch + target"
        )
        assertTrue(result.contains("Other repositories in project:"), "multi-repo header should appear")
        assertTrue(result.contains("- services/payment: develop"), "sibling repo + branch should appear")
        assertTrue(result.contains("- common: main"), "sibling repo + branch should appear")
    }

    @Test
    fun `single-repo rendering is unchanged when otherRepoBranches is empty`() {
        val result = EnvironmentDetailsBuilder.build(
            project = project,
            planModeEnabled = false,
            contextManager = null,
            currentBranch = "feature/foo",
            defaultTargetBranch = "main",
            primaryRepoLabel = "ignored-for-single-repo",
            otherRepoBranches = emptyList()
        )
        // Unchanged: unlabelled "<branch> (target: <target>)" form when only one repo exists.
        assertTrue(result.contains("feature/foo (target: main)"))
        assertFalse(result.contains("ignored-for-single-repo:"))
        assertFalse(result.contains("Other repositories in project:"))
    }
}
