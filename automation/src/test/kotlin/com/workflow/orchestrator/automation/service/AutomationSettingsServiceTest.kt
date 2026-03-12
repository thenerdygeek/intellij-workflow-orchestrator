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
}
