package com.workflow.orchestrator.automation.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for per-suite default stages (Phase H, H3 + H7).
 *
 * Covers:
 *  - setSuiteDefaultStages + getSuiteDefaultStages round-trip
 *  - getSuiteDefaultStages with null currentPlanStages (returns raw saved set)
 *  - Stale-stage filter: partial intersection returns only matching stages
 *  - Stale-stage filter: empty intersection returns null (all stages stale)
 *  - Null default (no default configured) returns null
 *  - Clearing default (set to null) works correctly
 *  - round-trip through getState / loadState preserves defaultStages
 *
 * See `docs/architecture/automation-stage-picker-c-faithful-plan.md` Phase H3 + H7.
 */
class AutomationSettingsServiceDefaultStagesTest {

    private lateinit var service: AutomationSettingsService

    @BeforeEach
    fun setUp() {
        service = AutomationSettingsService()
    }

    // ==================== setSuiteDefaultStages + getSuiteDefaultStages round-trip ====================

    @Test
    fun `setSuiteDefaultStages and getSuiteDefaultStages round-trip for new suite`() {
        service.setSuiteDefaultStages("PROJ-AUTO", setOf("Build", "Deploy"))

        val stages = service.getSuiteDefaultStages("PROJ-AUTO", currentPlanStages = null)
        assertNotNull(stages)
        assertEquals(setOf("Build", "Deploy"), stages)
    }

    @Test
    fun `setSuiteDefaultStages preserves other suite config fields`() {
        service.saveSuiteConfig(AutomationSettingsService.SuiteConfig(
            planKey = "PROJ-AUTO",
            displayName = "E2E Suite",
            variables = mutableMapOf("suiteType" to "regression"),
            enabledStages = mutableListOf("QA"),
            serviceNameMapping = null,
            lastModified = 1000L
        ))

        service.setSuiteDefaultStages("PROJ-AUTO", setOf("Build", "Test"))

        val config = service.getSuiteConfig("PROJ-AUTO")
        assertNotNull(config)
        assertEquals("E2E Suite", config!!.displayName)
        assertEquals("regression", config.variables["suiteType"])
        assertEquals(listOf("QA"), config.enabledStages)
        assertEquals(listOf("Build", "Test").sorted(), config.defaultStages?.sorted())
    }

    @Test
    fun `getSuiteDefaultStages returns null when no default configured`() {
        service.saveSuiteConfig(AutomationSettingsService.SuiteConfig(
            planKey = "PROJ-AUTO", displayName = "E2E", lastModified = 1000L
        ))

        val stages = service.getSuiteDefaultStages("PROJ-AUTO", currentPlanStages = null)
        assertNull(stages)
    }

    @Test
    fun `getSuiteDefaultStages returns null for unknown plan`() {
        val stages = service.getSuiteDefaultStages("UNKNOWN-PLAN", currentPlanStages = null)
        assertNull(stages)
    }

    @Test
    fun `clearing default stages (set to null) makes getSuiteDefaultStages return null`() {
        service.setSuiteDefaultStages("PROJ-AUTO", setOf("Build"))
        assertNotNull(service.getSuiteDefaultStages("PROJ-AUTO", null))

        service.setSuiteDefaultStages("PROJ-AUTO", null)
        assertNull(service.getSuiteDefaultStages("PROJ-AUTO", null))
    }

    // ==================== Null currentPlanStages (no stale filtering) ====================

    @Test
    fun `getSuiteDefaultStages with null currentPlanStages returns raw saved set without filtering`() {
        service.setSuiteDefaultStages("PROJ-AUTO", setOf("Build", "Deploy", "Test"))

        val stages = service.getSuiteDefaultStages("PROJ-AUTO", currentPlanStages = null)
        assertEquals(setOf("Build", "Deploy", "Test"), stages)
    }

    // ==================== Stale-stage filter (H7) ====================

    @Test
    fun `stale filter returns intersection when partial overlap`() {
        // Saved: {Build, Deploy} — current plan has {Build, Test} — Deploy was renamed
        service.setSuiteDefaultStages("PROJ-AUTO", setOf("Build", "Deploy"))

        val stages = service.getSuiteDefaultStages(
            "PROJ-AUTO",
            currentPlanStages = setOf("Build", "Test")
        )
        assertEquals(setOf("Build"), stages, "Should return intersection only")
    }

    @Test
    fun `stale filter returns null when empty intersection (all stages stale)`() {
        // Saved: {X, Y} — current plan has {A, B} — all stages renamed
        service.setSuiteDefaultStages("PROJ-AUTO", setOf("X", "Y"))

        val stages = service.getSuiteDefaultStages(
            "PROJ-AUTO",
            currentPlanStages = setOf("A", "B")
        )
        assertNull(stages, "All saved stages are stale — should return null")
    }

    @Test
    fun `stale filter returns full set when all saved stages still exist`() {
        // Saved: {Build, Deploy} — current plan has {Build, Test, Deploy}
        service.setSuiteDefaultStages("PROJ-AUTO", setOf("Build", "Deploy"))

        val stages = service.getSuiteDefaultStages(
            "PROJ-AUTO",
            currentPlanStages = setOf("Build", "Test", "Deploy")
        )
        assertEquals(setOf("Build", "Deploy"), stages, "All saved stages are valid")
    }

    @Test
    fun `stale filter handles single stage that still exists`() {
        service.setSuiteDefaultStages("PROJ-AUTO", setOf("Build"))

        val stages = service.getSuiteDefaultStages(
            "PROJ-AUTO",
            currentPlanStages = setOf("Build", "Test")
        )
        assertEquals(setOf("Build"), stages)
    }

    @Test
    fun `stale filter with exact match returns full set`() {
        service.setSuiteDefaultStages("PROJ-AUTO", setOf("Build", "Test"))

        val stages = service.getSuiteDefaultStages(
            "PROJ-AUTO",
            currentPlanStages = setOf("Build", "Test")
        )
        assertEquals(setOf("Build", "Test"), stages)
    }

    // ==================== Round-trip through getState / loadState ====================

    @Test
    fun `defaultStages survives getState and loadState round-trip`() {
        service.setSuiteDefaultStages("PROJ-AUTO", setOf("Build", "Deploy"))

        val state = service.state
        val newService = AutomationSettingsService()
        newService.loadState(state)

        val stages = newService.getSuiteDefaultStages("PROJ-AUTO", currentPlanStages = null)
        assertEquals(setOf("Build", "Deploy"), stages)
    }

    @Test
    fun `null defaultStages survives getState and loadState round-trip`() {
        service.saveSuiteConfig(AutomationSettingsService.SuiteConfig(
            planKey = "PROJ-AUTO", displayName = "E2E", lastModified = 1000L,
            defaultStages = null
        ))

        val state = service.state
        val newService = AutomationSettingsService()
        newService.loadState(state)

        assertNull(newService.getSuiteDefaultStages("PROJ-AUTO", null))
    }

    @Test
    fun `setSuiteDefaultStages creates new suite when plan not yet registered`() {
        assertNull(service.getSuiteConfig("NEW-PROJ"))

        service.setSuiteDefaultStages("NEW-PROJ", setOf("Build"))

        assertNotNull(service.getSuiteConfig("NEW-PROJ"))
        assertEquals(setOf("Build"), service.getSuiteDefaultStages("NEW-PROJ", null))
    }

    @Test
    fun `setSuiteDefaultStages overwrites previous defaultStages`() {
        service.setSuiteDefaultStages("PROJ-AUTO", setOf("Build", "Deploy"))
        service.setSuiteDefaultStages("PROJ-AUTO", setOf("Test"))

        val stages = service.getSuiteDefaultStages("PROJ-AUTO", null)
        assertEquals(setOf("Test"), stages)
    }
}
