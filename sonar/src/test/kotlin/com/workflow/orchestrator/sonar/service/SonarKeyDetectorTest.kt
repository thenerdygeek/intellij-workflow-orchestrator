package com.workflow.orchestrator.sonar.service

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.jetbrains.idea.maven.model.MavenId
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Properties

class SonarKeyDetectorTest {

    private val project = mockk<Project>(relaxed = true)
    private val mavenManager = mockk<MavenProjectsManager>(relaxed = true)
    private lateinit var detector: SonarKeyDetector

    @BeforeEach
    fun setup() {
        mockkStatic(MavenProjectsManager::class)
        every { MavenProjectsManager.getInstance(project) } returns mavenManager
        detector = SonarKeyDetector(project)
    }

    @AfterEach
    fun teardown() {
        unmockkStatic(MavenProjectsManager::class)
    }

    @Test
    fun `returns null when project is not mavenized`() {
        every { mavenManager.isMavenizedProject } returns false
        assertNull(detector.detect())
    }

    @Test
    fun `returns sonar projectKey property when set`() {
        val mavenProject = mockk<MavenProject>()
        val props = Properties().apply { setProperty("sonar.projectKey", "my-explicit-key") }
        every { mavenManager.isMavenizedProject } returns true
        every { mavenManager.rootProjects } returns listOf(mavenProject)
        every { mavenProject.properties } returns props

        assertEquals("my-explicit-key", detector.detect())
    }

    @Test
    fun `falls back to groupId-artifactId when sonar property absent`() {
        val mavenProject = mockk<MavenProject>()
        every { mavenManager.isMavenizedProject } returns true
        every { mavenManager.rootProjects } returns listOf(mavenProject)
        every { mavenProject.properties } returns Properties()
        every { mavenProject.mavenId } returns MavenId("com.acme", "my-service", "1.0")

        assertEquals("com.acme:my-service", detector.detect())
    }

    @Test
    fun `returns null when no root projects`() {
        every { mavenManager.isMavenizedProject } returns true
        every { mavenManager.rootProjects } returns emptyList()
        assertNull(detector.detect())
    }

    @Test
    fun `detectForPath returns sonar key from matching maven root`() {
        val mavenProjectA = mockk<MavenProject>()
        val mavenProjectB = mockk<MavenProject>()
        val propsA = Properties().apply { setProperty("sonar.projectKey", "service-a-key") }
        val propsB = Properties().apply { setProperty("sonar.projectKey", "service-b-key") }
        every { mavenManager.isMavenizedProject } returns true
        every { mavenManager.rootProjects } returns listOf(mavenProjectA, mavenProjectB)
        every { mavenProjectA.directory } returns "/projects/service-a"
        every { mavenProjectB.directory } returns "/projects/service-b"
        every { mavenProjectA.properties } returns propsA
        every { mavenProjectB.properties } returns propsB

        assertEquals("service-a-key", detector.detectForPath("/projects/service-a"))
        assertEquals("service-b-key", detector.detectForPath("/projects/service-b"))
    }

    @Test
    fun `detectForPath returns null when no maven root matches the path`() {
        val mavenProject = mockk<MavenProject>()
        every { mavenManager.isMavenizedProject } returns true
        every { mavenManager.rootProjects } returns listOf(mavenProject)
        every { mavenProject.directory } returns "/projects/other"

        assertNull(detector.detectForPath("/projects/missing"))
    }

    @Test
    fun `detectForPath falls back to groupId-artifactId when sonar property absent`() {
        val mavenProject = mockk<MavenProject>()
        every { mavenManager.isMavenizedProject } returns true
        every { mavenManager.rootProjects } returns listOf(mavenProject)
        every { mavenProject.directory } returns "/projects/svc"
        every { mavenProject.properties } returns Properties()
        every { mavenProject.mavenId } returns MavenId("com.acme", "svc", "1.0")

        assertEquals("com.acme:svc", detector.detectForPath("/projects/svc"))
    }
}
