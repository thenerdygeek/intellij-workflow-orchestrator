package com.workflow.orchestrator.automation.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AutomationSettingsServiceTest {

    private lateinit var service: AutomationSettingsService

    @BeforeEach
    fun setUp() {
        service = AutomationSettingsService()
    }

    @Test
    fun `saveSuiteConfig persists and retrieves config`() {
        val config = AutomationSettingsService.SuiteConfig(
            planKey = "PROJ-AUTO",
            displayName = "E2E Regression",
            variables = mutableMapOf("suiteType" to "regression", "featureFlag" to "true"),
            enabledStages = mutableListOf("QA Automation"),
            serviceNameMapping = mutableMapOf("intellij-workflow" to "service-auth"),
            lastModified = System.currentTimeMillis()
        )

        service.saveSuiteConfig(config)

        val retrieved = service.getSuiteConfig("PROJ-AUTO")
        assertNotNull(retrieved)
        assertEquals("E2E Regression", retrieved!!.displayName)
        assertEquals("regression", retrieved.variables["suiteType"])
        assertEquals(listOf("QA Automation"), retrieved.enabledStages)
    }

    @Test
    fun `getSuiteConfig returns null for unknown plan`() {
        assertNull(service.getSuiteConfig("UNKNOWN-PLAN"))
    }

    @Test
    fun `getAllSuites returns all saved configs`() {
        service.saveSuiteConfig(
            AutomationSettingsService.SuiteConfig(
                planKey = "PROJ-AUTO1", displayName = "Suite 1",
                variables = mutableMapOf(), enabledStages = mutableListOf(),
                serviceNameMapping = null, lastModified = 1000
            )
        )
        service.saveSuiteConfig(
            AutomationSettingsService.SuiteConfig(
                planKey = "PROJ-AUTO2", displayName = "Suite 2",
                variables = mutableMapOf(), enabledStages = mutableListOf(),
                serviceNameMapping = null, lastModified = 2000
            )
        )

        val all = service.getAllSuites()
        assertEquals(2, all.size)
    }

    @Test
    fun `saveSuiteConfig overwrites existing config`() {
        val config1 = AutomationSettingsService.SuiteConfig(
            planKey = "PROJ-AUTO", displayName = "Old Name",
            variables = mutableMapOf(), enabledStages = mutableListOf(),
            serviceNameMapping = null, lastModified = 1000
        )
        service.saveSuiteConfig(config1)

        val config2 = config1.copy(displayName = "New Name", lastModified = 2000)
        service.saveSuiteConfig(config2)

        val retrieved = service.getSuiteConfig("PROJ-AUTO")
        assertEquals("New Name", retrieved!!.displayName)
        assertEquals(1, service.getAllSuites().size)
    }

    @Test
    fun `getState and loadState round-trip`() {
        val config = AutomationSettingsService.SuiteConfig(
            planKey = "PROJ-AUTO", displayName = "Test Suite",
            variables = mutableMapOf("key" to "value"), enabledStages = mutableListOf("Stage1"),
            serviceNameMapping = null, lastModified = 1000
        )
        service.saveSuiteConfig(config)

        val state = service.state
        val newService = AutomationSettingsService()
        newService.loadState(state)

        val retrieved = newService.getSuiteConfig("PROJ-AUTO")
        assertNotNull(retrieved)
        assertEquals("Test Suite", retrieved!!.displayName)
    }

    // A-P0-2 regression: applying Settings must not wipe per-suite variables

    /**
     * Simulates the A-P0-2 bug scenario:
     * 1. User has a suite with variables {A=1, B=2} already persisted.
     * 2. The apply() diff-logic (as fixed) updates only displayName / lastModified.
     * 3. Assert variables are still {A=1, B=2} after the operation.
     */
    @Test
    fun `apply-style diff preserves per-suite variables`() {
        // Seed existing config with variables
        val existing = AutomationSettingsService.SuiteConfig(
            planKey = "PROJ-AUTO",
            displayName = "E2E Suite",
            variables = mutableMapOf("A" to "1", "B" to "2"),
            enabledStages = mutableListOf("QA Automation"),
            serviceNameMapping = null,
            lastModified = 1000L
        )
        service.saveSuiteConfig(existing)

        // Simulate apply(): preserve variables, update only displayName + lastModified
        val existingConfig = service.getSuiteConfig("PROJ-AUTO")!!
        service.saveSuiteConfig(
            existingConfig.copy(
                displayName = "E2E Suite (renamed)",
                lastModified = System.currentTimeMillis()
            )
        )

        // Assert variables survived
        val after = service.getSuiteConfig("PROJ-AUTO")!!
        assertEquals("E2E Suite (renamed)", after.displayName)
        assertEquals("1", after.variables["A"])
        assertEquals("2", after.variables["B"])
        assertEquals(listOf("QA Automation"), after.enabledStages)
    }

    @Test
    fun `apply-style diff removes deleted suite but preserves others`() {
        service.saveSuiteConfig(AutomationSettingsService.SuiteConfig(
            planKey = "PROJ-A", displayName = "Suite A",
            variables = mutableMapOf("x" to "y"), enabledStages = mutableListOf(),
            serviceNameMapping = null, lastModified = 1000L
        ))
        service.saveSuiteConfig(AutomationSettingsService.SuiteConfig(
            planKey = "PROJ-B", displayName = "Suite B",
            variables = mutableMapOf("m" to "n"), enabledStages = mutableListOf(),
            serviceNameMapping = null, lastModified = 1000L
        ))

        // User deleted PROJ-B via ✕ — apply() diff removes it
        service.state.suites.remove("PROJ-B")

        assertNotNull(service.getSuiteConfig("PROJ-A"))
        assertNull(service.getSuiteConfig("PROJ-B"))
        // PROJ-A variables intact
        assertEquals("y", service.getSuiteConfig("PROJ-A")!!.variables["x"])
    }

    // PR 7 #7: per-suite free-form extras

    @Test
    fun `setExtraVariables persists for new suite`() {
        service.setExtraVariables(
            "PROJ-NEW",
            mapOf("featureFlag" to "true", "timeoutMs" to "30000")
        )

        val cfg = service.getSuiteConfig("PROJ-NEW")
        assertNotNull(cfg)
        assertEquals("true", cfg!!.extraVariables["featureFlag"])
        assertEquals("30000", cfg.extraVariables["timeoutMs"])
        // Defaults: displayName falls back to planKey, variables map is empty.
        assertEquals("PROJ-NEW", cfg.displayName)
        assertTrue(cfg.variables.isEmpty())
    }

    @Test
    fun `setExtraVariables preserves plan-scoped variables on existing suite`() {
        service.saveSuiteConfig(AutomationSettingsService.SuiteConfig(
            planKey = "PROJ-AUTO", displayName = "E2E",
            variables = mutableMapOf("suiteType" to "regression"),
            enabledStages = mutableListOf(),
            serviceNameMapping = null,
            lastModified = 1000L
        ))

        service.setExtraVariables("PROJ-AUTO", mapOf("featureFlag" to "true"))

        val cfg = service.getSuiteConfig("PROJ-AUTO")!!
        // Plan-scoped variables survive the extras update.
        assertEquals("regression", cfg.variables["suiteType"])
        // Extras land where expected.
        assertEquals("true", cfg.extraVariables["featureFlag"])
        // displayName is unchanged.
        assertEquals("E2E", cfg.displayName)
    }

    @Test
    fun `getExtraVariables returns empty for unknown plan`() {
        assertTrue(service.getExtraVariables("UNKNOWN").isEmpty())
    }

    @Test
    fun `extras round-trip through getState and loadState`() {
        service.setExtraVariables("PROJ-AUTO", mapOf("a" to "1", "b" to "2"))

        val state = service.state
        val newService = AutomationSettingsService()
        newService.loadState(state)

        val extras = newService.getExtraVariables("PROJ-AUTO")
        assertEquals("1", extras["a"])
        assertEquals("2", extras["b"])
    }

    @Test
    fun `setExtraVariables overwrites existing extras`() {
        service.setExtraVariables("PROJ-AUTO", mapOf("a" to "1", "b" to "2"))
        service.setExtraVariables("PROJ-AUTO", mapOf("c" to "3"))

        val extras = service.getExtraVariables("PROJ-AUTO")
        assertEquals(1, extras.size)
        assertEquals("3", extras["c"])
        assertNull(extras["a"])
    }
}
