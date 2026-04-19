package com.workflow.orchestrator.agent.ide

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createFile
import kotlin.io.path.writeText
import kotlin.io.path.createDirectories

class ProjectScannerTest {

    @TempDir
    lateinit var projectRoot: Path

    // --- Framework Detection ---

    @Test
    fun `detectFrameworksFromPath finds Django when manage_py and requirements exist`() {
        projectRoot.resolve("manage.py").createFile()
        projectRoot.resolve("requirements.txt").writeText("django==5.1\ncelery==5.4\n")
        val result = ProjectScanner.detectFrameworksFromPath(projectRoot)
        assertTrue(Framework.DJANGO in result)
    }

    @Test
    fun `detectFrameworksFromPath does not detect Django without manage_py`() {
        projectRoot.resolve("requirements.txt").writeText("django==5.1\n")
        val result = ProjectScanner.detectFrameworksFromPath(projectRoot)
        assertFalse(Framework.DJANGO in result)
    }

    @Test
    fun `detectFrameworksFromPath finds FastAPI from requirements_txt`() {
        projectRoot.resolve("requirements.txt").writeText("fastapi==0.115\nuvicorn==0.30\n")
        val result = ProjectScanner.detectFrameworksFromPath(projectRoot)
        assertTrue(Framework.FASTAPI in result)
    }

    @Test
    fun `detectFrameworksFromPath finds Flask from requirements_txt`() {
        projectRoot.resolve("requirements.txt").writeText("flask==3.1\n")
        val result = ProjectScanner.detectFrameworksFromPath(projectRoot)
        assertTrue(Framework.FLASK in result)
    }

    @Test
    fun `detectFrameworksFromPath finds Django from pyproject_toml poetry`() {
        projectRoot.resolve("manage.py").createFile()
        projectRoot.resolve("pyproject.toml").writeText("""
            [tool.poetry.dependencies]
            python = "^3.12"
            django = "^5.1"
        """.trimIndent())
        val result = ProjectScanner.detectFrameworksFromPath(projectRoot)
        assertTrue(Framework.DJANGO in result)
    }

    @Test
    fun `detectFrameworksFromPath finds FastAPI from pyproject_toml uv`() {
        projectRoot.resolve("pyproject.toml").writeText("""
            [project]
            dependencies = ["fastapi>=0.115", "uvicorn"]

            [tool.uv]
            dev-dependencies = ["pytest"]
        """.trimIndent())
        val result = ProjectScanner.detectFrameworksFromPath(projectRoot)
        assertTrue(Framework.FASTAPI in result)
    }

    @Test
    fun `detectFrameworksFromPath finds Spring — always detected by Spring plugin not file scan`() {
        val result = ProjectScanner.detectFrameworksFromPath(projectRoot)
        assertFalse(Framework.SPRING in result)
    }

    @Test
    fun `detectFrameworksFromPath returns empty for empty project`() {
        val result = ProjectScanner.detectFrameworksFromPath(projectRoot)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `detectFrameworksFromPath detects multiple frameworks`() {
        projectRoot.resolve("manage.py").createFile()
        projectRoot.resolve("requirements.txt").writeText("django==5.1\nflask==3.1\n")
        val result = ProjectScanner.detectFrameworksFromPath(projectRoot)
        assertTrue(Framework.DJANGO in result)
        assertTrue(Framework.FLASK in result)
    }

    // --- Build Tool Detection ---

    @Test
    fun `detectBuildToolsFromPath finds Maven from pom_xml`() {
        projectRoot.resolve("pom.xml").createFile()
        val result = ProjectScanner.detectBuildToolsFromPath(projectRoot)
        assertTrue(BuildTool.MAVEN in result)
    }

    @Test
    fun `detectBuildToolsFromPath finds Gradle from build_gradle_kts`() {
        projectRoot.resolve("build.gradle.kts").createFile()
        val result = ProjectScanner.detectBuildToolsFromPath(projectRoot)
        assertTrue(BuildTool.GRADLE in result)
    }

    @Test
    fun `detectBuildToolsFromPath finds Gradle from build_gradle`() {
        projectRoot.resolve("build.gradle").createFile()
        val result = ProjectScanner.detectBuildToolsFromPath(projectRoot)
        assertTrue(BuildTool.GRADLE in result)
    }

    @Test
    fun `detectBuildToolsFromPath finds pip from requirements_txt`() {
        projectRoot.resolve("requirements.txt").createFile()
        val result = ProjectScanner.detectBuildToolsFromPath(projectRoot)
        assertTrue(BuildTool.PIP in result)
    }

    @Test
    fun `detectBuildToolsFromPath finds Poetry from pyproject_toml with poetry section`() {
        projectRoot.resolve("pyproject.toml").writeText("""
            [tool.poetry]
            name = "my-project"
            version = "1.0.0"
        """.trimIndent())
        val result = ProjectScanner.detectBuildToolsFromPath(projectRoot)
        assertTrue(BuildTool.POETRY in result)
    }

    @Test
    fun `detectBuildToolsFromPath finds uv from uv_lock`() {
        projectRoot.resolve("uv.lock").createFile()
        val result = ProjectScanner.detectBuildToolsFromPath(projectRoot)
        assertTrue(BuildTool.UV in result)
    }

    @Test
    fun `detectBuildToolsFromPath finds uv from pyproject_toml with uv section`() {
        projectRoot.resolve("pyproject.toml").writeText("""
            [project]
            name = "my-project"

            [tool.uv]
            dev-dependencies = ["pytest"]
        """.trimIndent())
        val result = ProjectScanner.detectBuildToolsFromPath(projectRoot)
        assertTrue(BuildTool.UV in result)
    }

    @Test
    fun `detectBuildToolsFromPath returns empty for empty project`() {
        val result = ProjectScanner.detectBuildToolsFromPath(projectRoot)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `detectBuildToolsFromPath detects multiple build tools`() {
        projectRoot.resolve("pom.xml").createFile()
        projectRoot.resolve("requirements.txt").createFile()
        val result = ProjectScanner.detectBuildToolsFromPath(projectRoot)
        assertTrue(BuildTool.MAVEN in result)
        assertTrue(BuildTool.PIP in result)
    }

    // --- Multi-Module Detection ---

    @Test
    fun `detectIsMultiModuleFromPath returns true for Gradle settings_gradle with include`() {
        projectRoot.resolve("settings.gradle").writeText("""
            rootProject.name = "my-project"
            include("core", "api", "service")
        """.trimIndent())
        assertTrue(ProjectScanner.detectIsMultiModuleFromPath(projectRoot))
    }

    @Test
    fun `detectIsMultiModuleFromPath returns true for Gradle settings_gradle_kts with include`() {
        projectRoot.resolve("settings.gradle.kts").writeText("""
            rootProject.name = "my-project"
            include("core", "api")
        """.trimIndent())
        assertTrue(ProjectScanner.detectIsMultiModuleFromPath(projectRoot))
    }

    @Test
    fun `detectIsMultiModuleFromPath returns false for settings_gradle without include`() {
        projectRoot.resolve("settings.gradle").writeText("""
            rootProject.name = "single-module"
        """.trimIndent())
        assertFalse(ProjectScanner.detectIsMultiModuleFromPath(projectRoot))
    }

    @Test
    fun `detectIsMultiModuleFromPath returns true for Maven pom with modules section`() {
        projectRoot.resolve("pom.xml").writeText("""
            <project>
              <modules>
                <module>core</module>
                <module>service</module>
              </modules>
            </project>
        """.trimIndent())
        assertTrue(ProjectScanner.detectIsMultiModuleFromPath(projectRoot))
    }

    @Test
    fun `detectIsMultiModuleFromPath returns false for single-module Maven pom`() {
        projectRoot.resolve("pom.xml").writeText("""
            <project>
              <artifactId>single</artifactId>
            </project>
        """.trimIndent())
        assertFalse(ProjectScanner.detectIsMultiModuleFromPath(projectRoot))
    }

    @Test
    fun `detectIsMultiModuleFromPath returns false for empty project`() {
        assertFalse(ProjectScanner.detectIsMultiModuleFromPath(projectRoot))
    }

    @Test
    fun `detectIsMultiModuleFromPath returns false for includeBuild only`() {
        projectRoot.resolve("settings.gradle").writeText("""
            rootProject.name = "composite"
            includeBuild("build-logic")
        """.trimIndent())
        assertFalse(ProjectScanner.detectIsMultiModuleFromPath(projectRoot))
    }

    @Test
    fun `detectIsMultiModuleFromPath returns false for settings_includeBuild only`() {
        projectRoot.resolve("settings.gradle.kts").writeText("""
            rootProject.name = "composite"
            settings.includeBuild("build-logic")
        """.trimIndent())
        assertFalse(ProjectScanner.detectIsMultiModuleFromPath(projectRoot))
    }

    @Test
    fun `detectIsMultiModuleFromPath returns false for gradle_includeBuild only`() {
        projectRoot.resolve("settings.gradle").writeText("""
            rootProject.name = "composite"
            gradle.includeBuild("../shared")
        """.trimIndent())
        assertFalse(ProjectScanner.detectIsMultiModuleFromPath(projectRoot))
    }
}
