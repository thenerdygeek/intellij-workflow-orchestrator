package com.workflow.orchestrator.handover.ui.tabs

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.handover.model.HandoverState
import com.workflow.orchestrator.handover.model.SuiteResult
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Container
import java.time.Instant
import javax.swing.JLabel

class ChecksTabTest {

    private fun walkLabels(c: Container): List<String> = buildList {
        for (i in 0 until c.componentCount) {
            val child = c.getComponent(i)
            if (child is JLabel) child.text?.let { add(it) }
            if (child is Container) addAll(walkLabels(child))
        }
    }

    private fun fixture(
        copyrightFixed: Boolean = false,
        prCreated: Boolean = false,
        qualityGatePassed: Boolean? = null,
        suiteResults: List<SuiteResult> = emptyList(),
    ): HandoverState = HandoverState(
        ticketId = "AFTER8TE-912",
        ticketSummary = "x",
        currentStatusName = null,
        copyrightFixed = copyrightFixed,
        prCreated = prCreated,
        qualityGatePassed = qualityGatePassed,
        suiteResults = suiteResults,
    )

    @Test
    fun `renders all 8 status rows + 4 checklist items`() {
        val tab = ChecksTab(mockk(relaxed = true))
        tab.updateState(fixture())
        val labels = walkLabels(tab)
        listOf(
            "Copyright headers", "Pull request", "Build", "Quality gate",
            "Suite: API smoke", "Suite: API integration", "Suite: Web E2E", "Docker tags"
        ).forEach { row ->
            assertTrue(labels.any { it.contains(row) }, "missing row: $row, all: $labels")
        }
        listOf("Copyright fixed", "PR created", "Jira comment posted", "Time logged")
            .forEach { row ->
                assertTrue(labels.any { it.contains(row) }, "missing checklist: $row, all: $labels")
            }
    }

    @Test
    fun `quality gate FAILED renders FAILED label`() {
        val tab = ChecksTab(mockk(relaxed = true))
        tab.updateState(fixture(qualityGatePassed = false))
        val labels = walkLabels(tab)
        assertTrue(labels.any { it.contains("FAILED", ignoreCase = true) }, labels.toString())
    }

    @Test
    fun `quality gate PASSED renders PASSED label`() {
        val tab = ChecksTab(mockk(relaxed = true))
        tab.updateState(fixture(qualityGatePassed = true))
        val labels = walkLabels(tab)
        assertTrue(labels.any { it.contains("PASSED", ignoreCase = true) }, labels.toString())
    }

    @Test
    fun `quality gate unknown renders Unknown label`() {
        val tab = ChecksTab(mockk(relaxed = true))
        tab.updateState(fixture(qualityGatePassed = null))
        val labels = walkLabels(tab)
        assertTrue(labels.any { it.contains("Unknown", ignoreCase = true) }, labels.toString())
    }

    @Test
    fun `running suite renders running label`() {
        val tab = ChecksTab(mockk(relaxed = true))
        val running = SuiteResult(
            suitePlanKey = "ORCH-WEB-E2E",
            buildResultKey = "",
            dockerTagsJson = "",
            passed = null,
            durationMs = null,
            triggeredAt = Instant.now(),
            bambooLink = null
        )
        tab.updateState(fixture(suiteResults = listOf(running)))
        val labels = walkLabels(tab)
        assertTrue(labels.any { it.contains("running", ignoreCase = true) }, labels.toString())
    }

    @Test
    fun `failed suite renders FAIL label`() {
        val tab = ChecksTab(mockk(relaxed = true))
        val failed = SuiteResult(
            suitePlanKey = "ORCH-API-SMOKE",
            buildResultKey = "",
            dockerTagsJson = "",
            passed = false,
            durationMs = null,
            triggeredAt = Instant.now(),
            bambooLink = null
        )
        tab.updateState(fixture(suiteResults = listOf(failed)))
        val labels = walkLabels(tab)
        assertTrue(labels.any { it.contains("FAIL", ignoreCase = true) }, labels.toString())
    }

    @Test
    fun `updateState is idempotent — second call replaces first`() {
        val tab = ChecksTab(mockk(relaxed = true))
        tab.updateState(fixture(qualityGatePassed = true))
        tab.updateState(fixture(qualityGatePassed = false))
        val labels = walkLabels(tab)
        assertTrue(labels.any { it.contains("FAILED", ignoreCase = true) }, labels.toString())
        assertFalse(labels.any { it == "PASSED" }, "stale PASSED label after second updateState: $labels")
    }

    @Test
    fun `checklist done items show DONE label`() {
        val tab = ChecksTab(mockk(relaxed = true))
        tab.updateState(fixture(copyrightFixed = true, prCreated = true))
        val labels = walkLabels(tab)
        val doneCount = labels.count { it.equals("DONE", ignoreCase = true) }
        assertTrue(doneCount >= 2, "Expected at least 2 DONE labels, got $doneCount. Labels: $labels")
    }

    @Test
    fun `checklist pending items show PENDING label`() {
        val tab = ChecksTab(mockk(relaxed = true))
        tab.updateState(fixture())
        val labels = walkLabels(tab)
        val pendingCount = labels.count { it.equals("PENDING", ignoreCase = true) }
        assertEquals(4, pendingCount, "Expected 4 PENDING labels for empty fixture, got $pendingCount. Labels: $labels")
    }
}
