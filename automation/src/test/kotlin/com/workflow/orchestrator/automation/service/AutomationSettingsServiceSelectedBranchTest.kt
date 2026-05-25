package com.workflow.orchestrator.automation.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for per-suite selected branch key persistence helpers:
 * [AutomationSettingsService.getSuiteSelectedBranch] and
 * [AutomationSettingsService.setSuiteSelectedBranch].
 */
class AutomationSettingsServiceSelectedBranchTest {

    private lateinit var service: AutomationSettingsService

    @BeforeEach
    fun setUp() {
        service = AutomationSettingsService()
    }

    @Test
    fun `setSuiteSelectedBranch then getSuiteSelectedBranch returns the saved value`() {
        service.setSuiteSelectedBranch("PROJ-AUTO", "PROJ-AUTOMATIONTEST336-3")

        val branch = service.getSuiteSelectedBranch("PROJ-AUTO")
        assertEquals("PROJ-AUTOMATIONTEST336-3", branch,
            "getSuiteSelectedBranch must return the branch key set by setSuiteSelectedBranch")
    }

    @Test
    fun `getSuiteSelectedBranch returns null when no branch configured`() {
        service.saveSuiteConfig(AutomationSettingsService.SuiteConfig(
            planKey = "PROJ-AUTO", displayName = "E2E Suite"
        ))

        val branch = service.getSuiteSelectedBranch("PROJ-AUTO")
        assertNull(branch, "getSuiteSelectedBranch must return null when no branch is set")
    }

    @Test
    fun `getSuiteSelectedBranch returns null for unknown plan key`() {
        val branch = service.getSuiteSelectedBranch("UNKNOWN-PLAN")
        assertNull(branch)
    }

    @Test
    fun `blank string is treated as null by setSuiteSelectedBranch`() {
        service.setSuiteSelectedBranch("PROJ-AUTO", "  ")

        val branch = service.getSuiteSelectedBranch("PROJ-AUTO")
        assertNull(branch, "A blank branch key must be stored/returned as null")
    }

    @Test
    fun `empty string is treated as null by setSuiteSelectedBranch`() {
        service.setSuiteSelectedBranch("PROJ-AUTO", "")

        val branch = service.getSuiteSelectedBranch("PROJ-AUTO")
        assertNull(branch, "An empty branch key must be stored/returned as null")
    }

    @Test
    fun `setSuiteSelectedBranch with null clears the saved branch`() {
        service.setSuiteSelectedBranch("PROJ-AUTO", "PROJ-AUTOMATIONTEST336-3")
        assertNotNull(service.getSuiteSelectedBranch("PROJ-AUTO"))

        service.setSuiteSelectedBranch("PROJ-AUTO", null)
        assertNull(service.getSuiteSelectedBranch("PROJ-AUTO"),
            "setSuiteSelectedBranch(null) must clear the saved branch")
    }

    @Test
    fun `setSuiteSelectedBranch creates new suite entry when plan not yet registered`() {
        assertNull(service.getSuiteConfig("NEW-PROJ"))

        service.setSuiteSelectedBranch("NEW-PROJ", "NEW-PROJ-BRANCH-1")

        assertNotNull(service.getSuiteConfig("NEW-PROJ"),
            "setSuiteSelectedBranch must create the SuiteConfig when the plan is not yet registered")
        assertEquals("NEW-PROJ-BRANCH-1", service.getSuiteSelectedBranch("NEW-PROJ"))
    }

    @Test
    fun `setSuiteSelectedBranch preserves other suite config fields`() {
        service.saveSuiteConfig(AutomationSettingsService.SuiteConfig(
            planKey = "PROJ-AUTO",
            displayName = "Integration Suite",
            variables = mutableMapOf("suiteType" to "smoke"),
            enabledStages = mutableListOf("Build"),
            lastModified = 1000L
        ))

        service.setSuiteSelectedBranch("PROJ-AUTO", "PROJ-AUTOMATIONTEST336-7")

        val config = service.getSuiteConfig("PROJ-AUTO")
        assertNotNull(config)
        assertEquals("Integration Suite", config!!.displayName,
            "setSuiteSelectedBranch must not overwrite displayName")
        assertEquals("smoke", config.variables["suiteType"],
            "setSuiteSelectedBranch must not overwrite variables")
        assertEquals(listOf("Build"), config.enabledStages,
            "setSuiteSelectedBranch must not overwrite enabledStages")
    }

    @Test
    fun `setSuiteSelectedBranch overwrites a previously saved branch`() {
        service.setSuiteSelectedBranch("PROJ-AUTO", "PROJ-AUTOMATIONTEST336-3")
        service.setSuiteSelectedBranch("PROJ-AUTO", "PROJ-AUTOMATIONTEST336-7")

        assertEquals("PROJ-AUTOMATIONTEST336-7", service.getSuiteSelectedBranch("PROJ-AUTO"),
            "Second setSuiteSelectedBranch call must overwrite the first")
    }

    @Test
    fun `selectedBranch survives getState and loadState round-trip`() {
        service.setSuiteSelectedBranch("PROJ-AUTO", "PROJ-AUTOMATIONTEST336-3")

        val state = service.state
        val newService = AutomationSettingsService()
        newService.loadState(state)

        assertEquals("PROJ-AUTOMATIONTEST336-3", newService.getSuiteSelectedBranch("PROJ-AUTO"),
            "selectedBranch must survive XML state serialisation round-trip")
    }
}
