package com.workflow.orchestrator.core.healthcheck

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.workflow.orchestrator.core.settings.PluginSettings
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HealthCheckCheckinHandlerFactoryTest {

    @AfterEach
    fun cleanup() = unmockkAll()

    @Test
    fun `beforeCheckin returns COMMIT when health check disabled`() {
        val project = mockk<Project>()
        val panel = mockk<CheckinProjectPanel>()
        every { panel.project } returns project

        val settings = mockk<PluginSettings>()
        val state = mockk<PluginSettings.State>()
        every { state.healthCheckEnabled } returns false
        every { state.healthCheckBlockingMode } returns "hard"
        mockkObject(PluginSettings)
        every { PluginSettings.getInstance(project) } returns settings
        every { settings.state } returns state

        val handler = HealthCheckCheckinHandler(panel)
        assertEquals(CheckinHandler.ReturnResult.COMMIT, handler.beforeCheckin())
    }

    @Test
    fun `beforeCheckin returns COMMIT when mode is off`() {
        val project = mockk<Project>()
        val panel = mockk<CheckinProjectPanel>()
        every { panel.project } returns project

        val settings = mockk<PluginSettings>()
        val state = mockk<PluginSettings.State>()
        every { state.healthCheckEnabled } returns true
        every { state.healthCheckBlockingMode } returns "off"
        mockkObject(PluginSettings)
        every { PluginSettings.getInstance(project) } returns settings
        every { settings.state } returns state

        val handler = HealthCheckCheckinHandler(panel)
        assertEquals(CheckinHandler.ReturnResult.COMMIT, handler.beforeCheckin())
    }
}
