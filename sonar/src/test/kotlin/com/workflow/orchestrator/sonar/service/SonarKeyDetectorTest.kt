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
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
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

    // ──────────────────────────────────────────────────────────────────
    // detect() — legacy single-project entry point
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `returns null when project is not mavenized`() {
        every { mavenManager.isMavenizedProject } returns false
        // basePath is null by default on the relaxed mock, so tiers 0/1 skip.
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
    fun `detect returns scanner-work key from project basePath`(@TempDir root: Path) {
        val scannerWork = root.resolve(".scannerwork")
        Files.createDirectories(scannerWork)
        Files.writeString(scannerWork.resolve("report-task.txt"), "projectKey=basepath-scanner-key\n")

        every { project.basePath } returns root.toString()

        assertEquals("basepath-scanner-key", detector.detect())
    }

    @Test
    fun `detect returns sonar-project-properties key from project basePath`(@TempDir root: Path) {
        Files.writeString(root.resolve("sonar-project.properties"), "sonar.projectKey=basepath-props-key\n")

        every { project.basePath } returns root.toString()
        // No Maven configured — tiers 0/1 must fire before falling through to Maven.
        every { mavenManager.isMavenizedProject } returns false

        assertEquals("basepath-props-key", detector.detect())
    }

    // ──────────────────────────────────────────────────────────────────
    // detectForPath() — Tier 2 Maven (pure-mock, updated to use .projects)
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `detectForPath returns sonar key from matching maven project`() {
        val mavenProjectA = mockk<MavenProject>()
        val mavenProjectB = mockk<MavenProject>()
        val propsA = Properties().apply { setProperty("sonar.projectKey", "service-a-key") }
        val propsB = Properties().apply { setProperty("sonar.projectKey", "service-b-key") }
        every { mavenManager.isMavenizedProject } returns true
        // detectForPath now walks .projects (all modules), not rootProjects
        every { mavenManager.projects } returns listOf(mavenProjectA, mavenProjectB)
        every { mavenProjectA.directory } returns "/projects/service-a"
        every { mavenProjectB.directory } returns "/projects/service-b"
        every { mavenProjectA.properties } returns propsA
        every { mavenProjectB.properties } returns propsB

        assertEquals("service-a-key", detector.detectForPath("/projects/service-a"))
        assertEquals("service-b-key", detector.detectForPath("/projects/service-b"))
    }

    @Test
    fun `detectForPath returns null when no maven project matches the path`() {
        val mavenProject = mockk<MavenProject>()
        every { mavenManager.isMavenizedProject } returns true
        every { mavenManager.projects } returns listOf(mavenProject)
        every { mavenProject.directory } returns "/projects/other"

        assertNull(detector.detectForPath("/projects/missing"))
    }

    @Test
    fun `detectForPath falls back to groupId-artifactId when sonar property absent`() {
        val mavenProject = mockk<MavenProject>()
        every { mavenManager.isMavenizedProject } returns true
        every { mavenManager.projects } returns listOf(mavenProject)
        every { mavenProject.directory } returns "/projects/svc"
        every { mavenProject.properties } returns Properties()
        every { mavenProject.mavenId } returns MavenId("com.acme", "svc", "1.0")

        assertEquals("com.acme:svc", detector.detectForPath("/projects/svc"))
    }

    // ──────────────────────────────────────────────────────────────────
    // detectForPath() — Tier 0: .scannerwork/report-task.txt
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `detectForPath returns scanner-work key when report-task-txt exists`(@TempDir root: Path) {
        val scannerWork = root.resolve(".scannerwork")
        Files.createDirectories(scannerWork)
        Files.writeString(
            scannerWork.resolve("report-task.txt"),
            "projectKey=scanner-key\nserverUrl=http://sonar.example.com\n"
        )

        assertEquals("scanner-key", detector.detectForPath(root.toString()))
    }

    @Test
    fun `detectForPath prefers scanner-work over sonar-project-properties`(@TempDir root: Path) {
        // Tier 0 — .scannerwork/report-task.txt
        val scannerWork = root.resolve(".scannerwork")
        Files.createDirectories(scannerWork)
        Files.writeString(scannerWork.resolve("report-task.txt"), "projectKey=scanner-key\n")

        // Tier 1 — sonar-project.properties
        Files.writeString(root.resolve("sonar-project.properties"), "sonar.projectKey=props-key\n")

        // Tier 0 must win
        assertEquals("scanner-key", detector.detectForPath(root.toString()))
    }

    @Test
    fun `detectForPath returns sonar-project-properties key when no scanner-work`(@TempDir root: Path) {
        Files.writeString(root.resolve("sonar-project.properties"), "sonar.projectKey=props-key\n")

        assertEquals("props-key", detector.detectForPath(root.toString()))
    }

    // ──────────────────────────────────────────────────────────────────
    // detectForPath() — Tier 2: submodule sonar.projectKey override
    //   (regression guard for the rootProjects bug)
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `detectForPath finds submodule sonar-projectKey override regression for rootProjects bug`(
        @TempDir rootDir: Path
    ) {
        // Root module directory
        val rootModDir = rootDir.resolve("root-module")
        Files.createDirectories(rootModDir)

        // Submodule directory
        val subModDir = rootDir.resolve("sub-module")
        Files.createDirectories(subModDir)

        val rootMavenProject = mockk<MavenProject>()
        val subMavenProject = mockk<MavenProject>()

        val rootProps = Properties().apply { setProperty("sonar.projectKey", "root-key") }
        val subProps = Properties().apply { setProperty("sonar.projectKey", "submodule-override-key") }

        every { mavenManager.isMavenizedProject } returns true
        // Both root AND submodule visible via .projects (the fix)
        every { mavenManager.projects } returns listOf(rootMavenProject, subMavenProject)

        every { rootMavenProject.directory } returns rootModDir.toAbsolutePath().toString()
        every { subMavenProject.directory } returns subModDir.toAbsolutePath().toString()
        every { rootMavenProject.properties } returns rootProps
        every { subMavenProject.properties } returns subProps

        // Querying the submodule path should return the submodule's override key
        assertEquals("submodule-override-key", detector.detectForPath(subModDir.toAbsolutePath().toString()))
        // Querying the root path should return the root key
        assertEquals("root-key", detector.detectForPath(rootModDir.toAbsolutePath().toString()))
    }

    // ──────────────────────────────────────────────────────────────────
    // detectForPath() — multiple sonar-project.properties warning (primary key returned)
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `detectForPath returns primary key when multiple sonar-project-properties files exist`(
        @TempDir root: Path
    ) {
        // Primary at repo root
        Files.writeString(root.resolve("sonar-project.properties"), "sonar.projectKey=primary-key\n")

        // Additional in a subdirectory (within maxDepth=2)
        val sub = root.resolve("subproject")
        Files.createDirectories(sub)
        Files.writeString(sub.resolve("sonar-project.properties"), "sonar.projectKey=secondary-key\n")

        // Primary key must be returned; warning about extra file is logged (not asserted here)
        assertEquals("primary-key", detector.detectForPath(root.toString()))
    }

    // ──────────────────────────────────────────────────────────────────
    // detectForPath() — Tier 2.5: Gradle model (waterfall integration)
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `detectForPath falls through Maven to Gradle when Maven returns null`(@TempDir root: Path) {
        // Tier 0 and 1 produce nothing (no files).
        // Tier 2: non-mavenized project.
        every { mavenManager.isMavenizedProject } returns false

        // Tier 2.5: inject a fake data source that returns a known key.
        GradleSonarKeyDetector.dataSource = object : GradleSonarKeyDetector.GradleSonarDataSource {
            override fun linkedProjectPaths(p: com.intellij.openapi.project.Project) =
                listOf(root.toAbsolutePath().toString())

            override fun rootExternalProject(
                p: com.intellij.openapi.project.Project,
                rootProjectPath: String
            ): org.jetbrains.plugins.gradle.model.ExternalProject {
                val ep = mockk<org.jetbrains.plugins.gradle.model.ExternalProject>(relaxed = true)
                every { ep.name } returns "gradle-service"
                every { ep.group } returns "com.example"
                every { ep.path } returns ":"
                every { ep.projectDir } returns root.toFile()
                every { ep.childProjects } returns emptyMap()
                return ep
            }
        }

        try {
            // Tier 2.5 synthesizes "com.example:gradle-service" (no gradle.properties)
            assertEquals("com.example:gradle-service", detector.detectForPath(root.toAbsolutePath().toString()))
        } finally {
            GradleSonarKeyDetector.dataSource = GradleSonarKeyDetector.DefaultGradleSonarDataSource
        }
    }

    @Test
    fun `detect from basePath falls through to Gradle when Maven returns null`(@TempDir root: Path) {
        every { project.basePath } returns root.toAbsolutePath().toString()
        // Tier 2: non-mavenized.
        every { mavenManager.isMavenizedProject } returns false

        GradleSonarKeyDetector.dataSource = object : GradleSonarKeyDetector.GradleSonarDataSource {
            override fun linkedProjectPaths(p: com.intellij.openapi.project.Project) =
                listOf(root.toAbsolutePath().toString())
            override fun rootExternalProject(
                p: com.intellij.openapi.project.Project,
                rootProjectPath: String
            ): org.jetbrains.plugins.gradle.model.ExternalProject {
                val ep = mockk<org.jetbrains.plugins.gradle.model.ExternalProject>(relaxed = true)
                every { ep.name } returns "basepath-gradle"
                every { ep.group } returns "com.example"
                every { ep.path } returns ":"
                every { ep.projectDir } returns root.toFile()
                every { ep.childProjects } returns emptyMap()
                return ep
            }
        }

        try {
            assertEquals("com.example:basepath-gradle", detector.detect())
        } finally {
            GradleSonarKeyDetector.dataSource = GradleSonarKeyDetector.DefaultGradleSonarDataSource
        }
    }

    @Test
    fun `detectForPath returns Maven key and does not reach Gradle tier`(@TempDir root: Path) {
        // Tier 2 matches — Gradle tier must not be consulted (data source would throw).
        val mavenProject = mockk<MavenProject>()
        val props = Properties().apply { setProperty("sonar.projectKey", "maven-wins") }
        every { mavenManager.isMavenizedProject } returns true
        every { mavenManager.projects } returns listOf(mavenProject)
        every { mavenProject.directory } returns root.toAbsolutePath().toString()
        every { mavenProject.properties } returns props

        // Data source throws if consulted — guards that Gradle tier is NOT reached.
        GradleSonarKeyDetector.dataSource = object : GradleSonarKeyDetector.GradleSonarDataSource {
            override fun linkedProjectPaths(p: com.intellij.openapi.project.Project): Collection<String> =
                throw AssertionError("Gradle tier must not be reached when Maven matched")
            override fun rootExternalProject(p: com.intellij.openapi.project.Project, rootProjectPath: String) =
                throw AssertionError("Gradle tier must not be reached when Maven matched")
        }

        try {
            assertEquals("maven-wins", detector.detectForPath(root.toAbsolutePath().toString()))
        } finally {
            GradleSonarKeyDetector.dataSource = GradleSonarKeyDetector.DefaultGradleSonarDataSource
        }
    }
}
