package com.workflow.orchestrator.core.healthcheck.checks

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.maven.MavenBuildResult
import com.workflow.orchestrator.core.maven.MavenBuildService
import com.workflow.orchestrator.core.settings.PluginSettings
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MavenCompileCheckTest {

    @AfterEach
    fun cleanup() = unmockkAll()

    @Test
    fun `id is maven-compile`() {
        assertEquals("maven-compile", MavenCompileCheck().id)
    }

    @Test
    fun `order is 10`() {
        assertEquals(10, MavenCompileCheck().order)
    }

    @Test
    fun `isEnabled reads healthCheckCompileEnabled`() {
        val state = mockk<PluginSettings.State>()
        every { state.healthCheckCompileEnabled } returns false
        assertFalse(MavenCompileCheck().isEnabled(state))

        every { state.healthCheckCompileEnabled } returns true
        assertTrue(MavenCompileCheck().isEnabled(state))
    }

    @Test
    fun `execute returns passed on successful build`() = runTest {
        val project = mockk<Project>()
        every { project.basePath } returns "/tmp/test"

        val buildService = mockk<MavenBuildService>()
        mockkObject(MavenBuildService)
        every { MavenBuildService.getInstance(project) } returns buildService
        coEvery { buildService.runBuild("compile", any()) } returns MavenBuildResult(
            success = true, exitCode = 0, output = "", errors = ""
        )

        val context = HealthCheckContext(project, emptyList(), "msg", "main")
        val result = MavenCompileCheck().execute(context)
        assertTrue(result.passed)
    }

    @Test
    fun `execute returns failed on build failure`() = runTest {
        val project = mockk<Project>()
        every { project.basePath } returns "/tmp/test"

        val buildService = mockk<MavenBuildService>()
        mockkObject(MavenBuildService)
        every { MavenBuildService.getInstance(project) } returns buildService
        coEvery { buildService.runBuild("compile", any()) } returns MavenBuildResult(
            success = false, exitCode = 1, output = "", errors = "[ERROR] Failed to compile"
        )

        val context = HealthCheckContext(project, emptyList(), "msg", "main")
        val result = MavenCompileCheck().execute(context)
        assertFalse(result.passed)
        assertTrue(result.message.contains("exit code 1"))
    }
}
