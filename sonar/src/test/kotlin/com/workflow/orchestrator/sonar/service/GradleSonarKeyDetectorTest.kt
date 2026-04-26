package com.workflow.orchestrator.sonar.service

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.jetbrains.plugins.gradle.model.ExternalProject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Pure-mock tests for [GradleSonarKeyDetector].
 *
 * Because [ExternalProject] is an interface with no complex constructor requirements,
 * and [ExternalProjectDataCache] construction ties to a real [Project], we inject a
 * fake [GradleSonarKeyDetector.GradleSonarDataSource] instead of mocking static methods.
 * This is the thin-abstraction deviation noted in the Phase B spec.
 */
class GradleSonarKeyDetectorTest {

    private val project = mockk<Project>(relaxed = true)

    // ── helpers ──────────────────────────────────────────────────────

    /**
     * Builds a minimal fake [GradleSonarDataSource] that returns [rootProjects] for
     * every call to [GradleSonarKeyDetector.GradleSonarDataSource.rootExternalProject].
     * We supply multiple linkedPaths so the detector loops over all of them.
     */
    private fun fakeDataSource(
        linkedPaths: Collection<String>,
        rootProjectsByPath: Map<String, ExternalProject>
    ): GradleSonarKeyDetector.GradleSonarDataSource =
        object : GradleSonarKeyDetector.GradleSonarDataSource {
            override fun linkedProjectPaths(project: Project): Collection<String> = linkedPaths
            override fun rootExternalProject(project: Project, rootProjectPath: String): ExternalProject? =
                rootProjectsByPath[rootProjectPath]
        }

    private fun mockExternalProject(
        name: String,
        group: String,
        path: String,
        dir: File,
        children: Map<String, ExternalProject> = emptyMap()
    ): ExternalProject = mockk<ExternalProject>(relaxed = true) {
        every { this@mockk.name } returns name
        every { this@mockk.group } returns group
        every { this@mockk.path } returns path
        every { this@mockk.projectDir } returns dir
        every { this@mockk.childProjects } returns children
    }

    @BeforeEach
    fun setUp() {
        // Reset data source to prevent cross-test contamination
        GradleSonarKeyDetector.dataSource = GradleSonarKeyDetector.DefaultGradleSonarDataSource
    }

    @AfterEach
    fun tearDown() {
        GradleSonarKeyDetector.dataSource = GradleSonarKeyDetector.DefaultGradleSonarDataSource
    }

    // ── Tier 2.5a: explicit sonar.projectKey from gradle.properties ──

    @Test
    fun `single project with explicit sonar projectKey in gradle properties returns it`(@TempDir root: Path) {
        Files.writeString(root.resolve("gradle.properties"), "sonar.projectKey=explicit-key\nother.prop=foo\n")
        val rootDir = root.toFile()
        val ep = mockExternalProject("my-service", "com.acme", ":", rootDir)

        GradleSonarKeyDetector.dataSource = fakeDataSource(
            linkedPaths = listOf(rootDir.absolutePath),
            rootProjectsByPath = mapOf(rootDir.absolutePath to ep)
        )

        assertEquals("explicit-key", GradleSonarKeyDetector.detect(project, rootDir.absolutePath))
    }

    @Test
    fun `subproject with explicit sonar projectKey override returns it`(@TempDir root: Path) {
        val subDir = root.resolve("service-b").also { Files.createDirectories(it) }
        Files.writeString(subDir.resolve("gradle.properties"), "sonar.projectKey=sub-explicit-key\n")

        val rootDir = root.toFile()
        val subEp = mockExternalProject("service-b", "com.acme", ":service-b", subDir.toFile())
        val rootEp = mockExternalProject("root-project", "com.acme", ":", rootDir, mapOf("service-b" to subEp))

        GradleSonarKeyDetector.dataSource = fakeDataSource(
            linkedPaths = listOf(rootDir.absolutePath),
            rootProjectsByPath = mapOf(rootDir.absolutePath to rootEp)
        )

        assertEquals("sub-explicit-key", GradleSonarKeyDetector.detect(project, subDir.toAbsolutePath().toString()))
    }

    // ── Tier 2.5b: key synthesis ──────────────────────────────────────

    @Test
    fun `single project without sonar projectKey synthesizes group colon name`(@TempDir root: Path) {
        val rootDir = root.toFile()
        val ep = mockExternalProject("my-service", "com.acme", ":", rootDir)

        GradleSonarKeyDetector.dataSource = fakeDataSource(
            linkedPaths = listOf(rootDir.absolutePath),
            rootProjectsByPath = mapOf(rootDir.absolutePath to ep)
        )

        assertEquals("com.acme:my-service", GradleSonarKeyDetector.detect(project, rootDir.absolutePath))
    }

    @Test
    fun `single project without group synthesizes plain name`(@TempDir root: Path) {
        val rootDir = root.toFile()
        val ep = mockExternalProject("my-service", "", ":", rootDir)

        GradleSonarKeyDetector.dataSource = fakeDataSource(
            linkedPaths = listOf(rootDir.absolutePath),
            rootProjectsByPath = mapOf(rootDir.absolutePath to ep)
        )

        assertEquals("my-service", GradleSonarKeyDetector.detect(project, rootDir.absolutePath))
    }

    @Test
    fun `subproject synthesizes rootKey colon gradle path`(@TempDir root: Path) {
        val subDir = root.resolve("api").also { Files.createDirectories(it) }
        // No gradle.properties in subDir -> synthesis path

        val rootDir = root.toFile()
        val subEp = mockExternalProject("api", "com.acme", ":api", subDir.toFile())
        val rootEp = mockExternalProject("platform", "com.acme", ":", rootDir, mapOf("api" to subEp))

        GradleSonarKeyDetector.dataSource = fakeDataSource(
            linkedPaths = listOf(rootDir.absolutePath),
            rootProjectsByPath = mapOf(rootDir.absolutePath to rootEp)
        )

        assertEquals("com.acme:platform:api", GradleSonarKeyDetector.detect(project, subDir.toAbsolutePath().toString()))
    }

    @Test
    fun `subproject with blank group synthesizes rootKey colon path without group`(@TempDir root: Path) {
        val subDir = root.resolve("core").also { Files.createDirectories(it) }

        val rootDir = root.toFile()
        val subEp = mockExternalProject("core", "", ":core", subDir.toFile())
        val rootEp = mockExternalProject("platform", "", ":", rootDir, mapOf("core" to subEp))

        GradleSonarKeyDetector.dataSource = fakeDataSource(
            linkedPaths = listOf(rootDir.absolutePath),
            rootProjectsByPath = mapOf(rootDir.absolutePath to rootEp)
        )

        assertEquals("platform:core", GradleSonarKeyDetector.detect(project, subDir.toAbsolutePath().toString()))
    }

    // ── Null / no-match paths ─────────────────────────────────────────

    @Test
    fun `returns null when no Gradle projects linked`(@TempDir root: Path) {
        GradleSonarKeyDetector.dataSource = fakeDataSource(
            linkedPaths = emptyList(),
            rootProjectsByPath = emptyMap()
        )

        assertNull(GradleSonarKeyDetector.detect(project, root.toAbsolutePath().toString()))
    }

    @Test
    fun `returns null when target path does not match any module`(@TempDir root: Path) {
        val rootDir = root.toFile()
        val ep = mockExternalProject("service", "com.acme", ":", rootDir)

        GradleSonarKeyDetector.dataSource = fakeDataSource(
            linkedPaths = listOf(rootDir.absolutePath),
            rootProjectsByPath = mapOf(rootDir.absolutePath to ep)
        )

        val unrelated = root.resolve("unrelated").also { Files.createDirectories(it) }
        assertNull(GradleSonarKeyDetector.detect(project, unrelated.toAbsolutePath().toString()))
    }

    @Test
    fun `returns null when linked path has no cached ExternalProject`(@TempDir root: Path) {
        val rootDir = root.toFile()

        GradleSonarKeyDetector.dataSource = fakeDataSource(
            linkedPaths = listOf(rootDir.absolutePath),
            rootProjectsByPath = emptyMap()          // no cached data
        )

        assertNull(GradleSonarKeyDetector.detect(project, rootDir.absolutePath))
    }

    @Test
    fun `returns null when gradle properties file is absent`(@TempDir root: Path) {
        // No gradle.properties -> no explicit key; synthesis used instead (not null)
        // This test verifies readSonarKeyFromGradleProperties returns null when file absent
        val rootDir = root.toFile()
        val ep = mockExternalProject("svc", "com.example", ":", rootDir)
        assertNull(GradleSonarKeyDetector.readSonarKeyFromGradleProperties(ep))
    }

    // ── synthesizeKey unit tests ──────────────────────────────────────

    @Test
    fun `synthesizeKey returns rootKey for root project`(@TempDir root: Path) {
        val rootDir = root.toFile()
        val rootEp = mockExternalProject("project", "org.example", ":", rootDir)
        val key = GradleSonarKeyDetector.synthesizeKey(rootEp, rootEp)
        assertEquals("org.example:project", key)
    }

    @Test
    fun `synthesizeKey returns rootKey plus gradlePath for subproject`(@TempDir root: Path) {
        val subDir = root.resolve("sub").also { Files.createDirectories(it) }.toFile()
        val rootDir = root.toFile()
        val rootEp = mockExternalProject("root", "org.example", ":", rootDir)
        val subEp = mockExternalProject("sub", "org.example", ":sub", subDir)
        val key = GradleSonarKeyDetector.synthesizeKey(rootEp, subEp)
        assertEquals("org.example:root:sub", key)
    }

    // ── deep-nested subproject (`:lib:core`) — guards path-sourcing logic ────

    @Test
    fun `synthesizeKey for deeply nested subproject preserves full gradle path`(@TempDir root: Path) {
        val deepDir = root.resolve("lib/core").also { Files.createDirectories(it) }.toFile()
        val rootDir = root.toFile()
        val rootEp = mockExternalProject("platform", "com.acme", ":", rootDir)
        val deepEp = mockExternalProject("core", "com.acme", ":lib:core", deepDir)
        val key = GradleSonarKeyDetector.synthesizeKey(rootEp, deepEp)
        assertEquals("com.acme:platform:lib:core", key)
    }

    @Test
    fun `detect resolves deeply nested subproject via tree walk`(@TempDir root: Path) {
        // Tree: root -> lib (intermediate) -> core (leaf, target)
        val coreDir = root.resolve("lib/core").also { Files.createDirectories(it) }.toFile()
        val libDir = root.resolve("lib").toFile()
        val rootDir = root.toFile()

        val coreEp = mockExternalProject("core", "com.acme", ":lib:core", coreDir)
        val libEp = mockExternalProject("lib", "com.acme", ":lib", libDir, mapOf("core" to coreEp))
        val rootEp = mockExternalProject("platform", "com.acme", ":", rootDir, mapOf("lib" to libEp))

        GradleSonarKeyDetector.dataSource = fakeDataSource(
            linkedPaths = listOf(rootDir.absolutePath),
            rootProjectsByPath = mapOf(rootDir.absolutePath to rootEp)
        )

        assertEquals("com.acme:platform:lib:core", GradleSonarKeyDetector.detect(project, coreDir.absolutePath))
    }
}
