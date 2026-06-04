package com.workflow.orchestrator.agent.prompt

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.testutil.installReadActionInlineShim
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class EnvironmentDetailsBuilderTest {

    private val project = mockk<Project>(relaxed = true)

    @BeforeEach
    fun setUp() {
        installReadActionInlineShim()
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `branch and target branch appear in output when provided`() = runTest {
        val result = EnvironmentDetailsBuilder.build(
            project = project,
            planModeEnabled = false,
            contextManager = null,
            defaultTargetBranch = "develop",
            repoBranches = listOf("services/order" to "feature/my-feature")
        )
        assertTrue(result.contains("feature/my-feature"), "current branch must appear")
        assertTrue(result.contains("default target: develop"), "target branch must appear")
    }

    @Test
    fun `branch section omitted when repoBranches is empty`() = runTest {
        val result = EnvironmentDetailsBuilder.build(
            project = project,
            planModeEnabled = false,
            contextManager = null,
            defaultTargetBranch = null,
            repoBranches = emptyList()
        )
        assertFalse(result.contains("# Current Branch"), "branch section must not appear when empty")
        assertFalse(result.contains("# Branches"), "branches block must not appear when empty")
    }

    @Test
    fun `target branch omitted gracefully when only branch is provided`() = runTest {
        val result = EnvironmentDetailsBuilder.build(
            project = project,
            planModeEnabled = false,
            contextManager = null,
            defaultTargetBranch = null,
            repoBranches = listOf("repo" to "main")
        )
        assertTrue(result.contains("main"))
        assertFalse(result.contains("target:"), "target label must not appear when defaultTargetBranch is null")
    }

    @Test
    fun `output is wrapped in environment_details tags`() = runTest {
        val result = EnvironmentDetailsBuilder.build(
            project = project,
            planModeEnabled = false,
            contextManager = null
        )
        assertTrue(result.startsWith("<environment_details>"))
        assertTrue(result.endsWith("</environment_details>"))
    }

    // ---- Actively Running Processes section (pure renderer) ----

    @Test
    fun `running processes section is empty when no processes`() {
        assertEquals("", EnvironmentDetailsBuilder.renderRunningProcessesSection(emptyList()))
    }

    @Test
    fun `running process renders bgId, command label, and new output delta`() {
        val section = EnvironmentDetailsBuilder.renderRunningProcessesSection(
            listOf(
                RunningProcessInfo(
                    bgId = "bg_a1b2c3d4",
                    label = "npm run dev",
                    runtimeMs = 134_000,
                    newOutput = "Compiled successfully\nListening on :3000",
                )
            )
        )
        assertTrue(section.startsWith("# Actively Running Processes"), "section header present")
        assertTrue(section.contains("bg_a1b2c3d4"), "bgId present")
        assertTrue(section.contains("npm run dev"), "command label present")
        assertTrue(section.contains("2m 14s"), "humanized runtime present")
        assertTrue(section.contains("Listening on :3000"), "new output delta present")
        assertTrue(section.contains("new output"), "new-output marker present")
    }

    @Test
    fun `running process with no new output shows a no-new-output marker`() {
        val section = EnvironmentDetailsBuilder.renderRunningProcessesSection(
            listOf(RunningProcessInfo("bg_x", "gradle build", 8_000, ""))
        )
        assertTrue(section.contains("bg_x"))
        assertTrue(section.contains("gradle build"))
        assertTrue(section.contains("no new output"), "blank delta must render the no-new-output marker")
        assertFalse(section.contains("new output:\n"), "must not emit an empty output block")
    }

    @Test
    fun `single-repo project keeps the unlabelled branch form`() = runTest {
        val result = EnvironmentDetailsBuilder.build(
            project = project,
            planModeEnabled = false,
            contextManager = null,
            defaultTargetBranch = "main",
            repoBranches = listOf("services/order" to "feature/foo")
        )
        // Single-repo: bare "<branch> (default target: <target>)" — no label prefix, no "# Branches"
        assertTrue(result.contains("# Current Branch"), "single-repo uses '# Current Branch' header")
        assertFalse(result.contains("# Branches"), "single-repo must not use '# Branches' header")
        assertTrue(result.contains("feature/foo (default target: main)"))
        assertFalse(result.contains("services/order:"), "single-repo must not prefix the branch with the label")
    }

    @Test
    fun `multi-repo project lists all repos as a flat per-repo branch list with no primary`() = runTest {
        val result = EnvironmentDetailsBuilder.build(
            project = project,
            planModeEnabled = false,
            contextManager = null,
            defaultTargetBranch = "main",
            repoBranches = listOf(
                "services/order" to "feature/ORDER-42",
                "services/payment" to "develop",
                "common" to "main"
            )
        )
        assertTrue(result.contains("# Branches"), "multi-repo block must use '# Branches' header")
        assertFalse(result.contains("# Current Branch"), "multi-repo must not use single-repo header")
        assertTrue(result.contains("- services/order: feature/ORDER-42"), "first repo + branch must appear")
        assertTrue(result.contains("- services/payment: develop"), "second repo + branch must appear")
        assertTrue(result.contains("- common: main"), "third repo + branch must appear")
        // No "primary" framing — the word should not appear anywhere in the output.
        assertFalse(
            result.contains("Other repositories", ignoreCase = true),
            "no primary/other-repos framing in the new flat list"
        )
        assertFalse(
            result.contains("primary", ignoreCase = true),
            "no 'primary' wording in the new flat list"
        )
    }
}
