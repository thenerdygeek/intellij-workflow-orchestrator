package com.workflow.orchestrator.core.healthcheck

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.healthcheck.checks.HealthCheckContext
import com.workflow.orchestrator.core.settings.PluginSettings
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HealthCheckServiceTest {

    @AfterEach
    fun cleanup() = unmockkAll()

    private fun mockSettingsAndBus(
        enabled: Boolean = true,
        blockingMode: String = "hard",
        skipPattern: String = "",
        compileEnabled: Boolean = true,
        testEnabled: Boolean = true,
        copyrightEnabled: Boolean = true,
        sonarEnabled: Boolean = true,
        copyrightPattern: String = "",
        timeoutSeconds: Int = 300
    ): Pair<Project, EventBus> {
        val project = mockk<Project>()
        val settings = mockk<PluginSettings>()
        val state = mockk<PluginSettings.State>()

        every { state.healthCheckEnabled } returns enabled
        every { state.healthCheckBlockingMode } returns blockingMode
        every { state.healthCheckSkipBranchPattern } returns skipPattern
        every { state.healthCheckCompileEnabled } returns compileEnabled
        every { state.healthCheckTestEnabled } returns testEnabled
        every { state.healthCheckCopyrightEnabled } returns copyrightEnabled
        every { state.healthCheckSonarGateEnabled } returns sonarEnabled
        every { state.copyrightHeaderPattern } returns copyrightPattern
        every { state.healthCheckTimeoutSeconds } returns timeoutSeconds

        mockkObject(PluginSettings)
        every { PluginSettings.getInstance(project) } returns settings
        every { settings.state } returns state

        val eventBus = mockk<EventBus>(relaxed = true)
        coEvery { eventBus.emit(any()) } just runs

        every { project.getService(EventBus::class.java) } returns eventBus

        return Pair(project, eventBus)
    }

    @Test
    fun `skips when health check disabled`() = runTest {
        val (project, _) = mockSettingsAndBus(enabled = false)
        val service = HealthCheckService(project)
        val context = HealthCheckContext(project, emptyList(), "main")
        val result = service.runChecks(context)
        assertTrue(result.skipped)
        assertTrue(result.passed)
    }

    @Test
    fun `skips when branch matches skip pattern`() = runTest {
        val (project, _) = mockSettingsAndBus(skipPattern = "hotfix/.*")
        val service = HealthCheckService(project)
        val context = HealthCheckContext(project, emptyList(), "hotfix/urgent-fix")
        val result = service.runChecks(context)
        assertTrue(result.skipped)
    }

    @Test
    fun `does not skip when branch does not match skip pattern`() = runTest {
        val (project, _) = mockSettingsAndBus(
            skipPattern = "hotfix/.*",
            compileEnabled = false,
            testEnabled = false,
            copyrightEnabled = false,
            sonarEnabled = false
        )
        val service = HealthCheckService(project)
        val context = HealthCheckContext(project, emptyList(), "feature/my-branch")
        val result = service.runChecks(context)
        assertFalse(result.skipped)
        assertTrue(result.passed) // No checks enabled, so all pass
    }

    @Test
    fun `runs only enabled checks`() = runTest {
        val (project, eventBus) = mockSettingsAndBus(
            compileEnabled = false,
            testEnabled = false,
            copyrightEnabled = false,
            sonarEnabled = true
        )
        val service = HealthCheckService(project)
        val context = HealthCheckContext(project, emptyList(), "main")
        val result = service.runChecks(context)

        // Only sonar-gate should run (it returns passed with "no cached data" by default)
        assertEquals(1, result.checkResults.size)
        assertTrue(result.checkResults.containsKey("sonar-gate"))
    }

    @Test
    fun `emits start and finish events`() = runTest {
        val (project, eventBus) = mockSettingsAndBus(
            compileEnabled = false,
            testEnabled = false,
            copyrightEnabled = false,
            sonarEnabled = true
        )
        val service = HealthCheckService(project)
        val context = HealthCheckContext(project, emptyList(), "main")
        service.runChecks(context)

        coVerify {
            eventBus.emit(match { it is WorkflowEvent.HealthCheckStarted })
            eventBus.emit(match { it is WorkflowEvent.HealthCheckFinished })
        }
    }
}
