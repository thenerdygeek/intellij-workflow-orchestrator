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

class MavenTestCheckTest {

    @AfterEach
    fun cleanup() = unmockkAll()

    @Test
    fun `id is maven-test`() {
        assertEquals("maven-test", MavenTestCheck().id)
    }

    @Test
    fun `order is 20`() {
        assertEquals(20, MavenTestCheck().order)
    }

    @Test
    fun `isEnabled reads healthCheckTestEnabled`() {
        val state = mockk<PluginSettings.State>()
        every { state.healthCheckTestEnabled } returns false
        assertFalse(MavenTestCheck().isEnabled(state))
    }

    @Test
    fun `execute returns passed on successful tests`() = runTest {
        val project = mockk<Project>()
        every { project.basePath } returns "/tmp/test"

        val buildService = mockk<MavenBuildService>()
        mockkObject(MavenBuildService)
        every { MavenBuildService.getInstance(project) } returns buildService
        coEvery { buildService.runBuild("test", any()) } returns MavenBuildResult(
            success = true, exitCode = 0, output = "", errors = ""
        )

        val context = HealthCheckContext(project, emptyList(), "msg", "main")
        val result = MavenTestCheck().execute(context)
        assertTrue(result.passed)
    }

    @Test
    fun `execute returns failed on timeout`() = runTest {
        val project = mockk<Project>()
        every { project.basePath } returns "/tmp/test"

        val buildService = mockk<MavenBuildService>()
        mockkObject(MavenBuildService)
        every { MavenBuildService.getInstance(project) } returns buildService
        coEvery { buildService.runBuild("test", any()) } returns MavenBuildResult(
            success = false, exitCode = -1, output = "", errors = "", timedOut = true
        )

        val context = HealthCheckContext(project, emptyList(), "msg", "main")
        val result = MavenTestCheck().execute(context)
        assertFalse(result.passed)
        assertTrue(result.message.contains("timed out"))
    }
}
