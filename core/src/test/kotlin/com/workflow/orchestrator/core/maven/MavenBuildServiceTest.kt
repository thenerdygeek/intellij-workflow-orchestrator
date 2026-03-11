package com.workflow.orchestrator.core.maven

import com.intellij.openapi.project.Project
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class MavenBuildServiceTest {

    @Test
    fun `detectMavenExecutable prefers mvnw wrapper`() {
        val project = mockk<Project>()
        val tempDir = createTempDir()
        every { project.basePath } returns tempDir.absolutePath

        val mvnw = File(tempDir, "mvnw")
        mvnw.createNewFile()
        mvnw.setExecutable(true)

        val service = MavenBuildService(project)
        val executable = service.detectMavenExecutable()

        assertEquals(mvnw.absolutePath, executable)
        tempDir.deleteRecursively()
    }

    @Test
    fun `detectMavenExecutable falls back to mvn`() {
        val project = mockk<Project>()
        every { project.basePath } returns "/nonexistent/path"

        val service = MavenBuildService(project)
        val executable = service.detectMavenExecutable()

        // Falls back to "mvn" when no wrapper and no MAVEN_HOME
        assertEquals("mvn", executable)
    }

    @Test
    fun `buildCommandLine creates correct command`() {
        val project = mockk<Project>()
        every { project.basePath } returns "/tmp/test-project"

        val service = MavenBuildService(project)
        val cmd = service.buildCommandLine("clean test", listOf("mod-a", "mod-b"))

        val cmdString = cmd.commandLineString
        assertTrue(cmdString.contains("-pl"))
        assertTrue(cmdString.contains("mod-a,mod-b"))
        assertTrue(cmdString.contains("-am"))
        assertTrue(cmdString.contains("clean"))
        assertTrue(cmdString.contains("test"))
    }
}
