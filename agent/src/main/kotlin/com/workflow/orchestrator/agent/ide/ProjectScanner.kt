package com.workflow.orchestrator.agent.ide

import com.intellij.openapi.project.Project
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Scans a project's root directory for marker files and dependency manifests to
 * detect frameworks (Django, FastAPI, Flask) and build tools (Maven, Gradle, pip,
 * Poetry, uv).
 *
 * Spring is intentionally NOT detected here — it is detected via the IntelliJ
 * Spring plugin in [IdeContextDetector].
 */
object ProjectScanner {

    fun detectFrameworks(project: Project): Set<Framework> {
        val basePath = project.basePath ?: return emptySet()
        return detectFrameworksFromPath(Path.of(basePath))
    }

    fun detectBuildTools(project: Project): Set<BuildTool> {
        val basePath = project.basePath ?: return emptySet()
        return detectBuildToolsFromPath(Path.of(basePath))
    }

    fun detectFrameworksFromPath(root: Path): Set<Framework> {
        val frameworks = mutableSetOf<Framework>()
        val depContent = readDependencyContent(root)

        // Django: manage.py + django in dependencies
        if (root.resolve("manage.py").exists() && containsDependency(depContent, "django")) {
            frameworks.add(Framework.DJANGO)
        }

        // FastAPI: fastapi in dependencies
        if (containsDependency(depContent, "fastapi")) {
            frameworks.add(Framework.FASTAPI)
        }

        // Flask: flask in dependencies
        if (containsDependency(depContent, "flask")) {
            frameworks.add(Framework.FLASK)
        }

        // Spring is NOT detected by file scan — it's detected via PluginManager
        // in IdeContextDetector.hasSpringPlugin

        return frameworks
    }

    fun detectBuildToolsFromPath(root: Path): Set<BuildTool> {
        val buildTools = mutableSetOf<BuildTool>()

        // Maven
        if (root.resolve("pom.xml").exists()) {
            buildTools.add(BuildTool.MAVEN)
        }

        // Gradle
        if (root.resolve("build.gradle").exists() || root.resolve("build.gradle.kts").exists()) {
            buildTools.add(BuildTool.GRADLE)
        }

        // pip
        if (root.resolve("requirements.txt").exists()) {
            buildTools.add(BuildTool.PIP)
        }

        // Poetry
        val pyprojectContent = readFileIfExists(root.resolve("pyproject.toml"))
        if (pyprojectContent != null && pyprojectContent.contains("[tool.poetry]")) {
            buildTools.add(BuildTool.POETRY)
        }

        // uv
        if (root.resolve("uv.lock").exists()) {
            buildTools.add(BuildTool.UV)
        } else if (pyprojectContent != null && pyprojectContent.contains("[tool.uv]")) {
            buildTools.add(BuildTool.UV)
        }

        return buildTools
    }

    private fun readDependencyContent(root: Path): String {
        val parts = mutableListOf<String>()
        readFileIfExists(root.resolve("requirements.txt"))?.let { parts.add(it) }
        readFileIfExists(root.resolve("pyproject.toml"))?.let { parts.add(it) }
        return parts.joinToString("\n")
    }

    private fun containsDependency(content: String, name: String): Boolean {
        if (content.isEmpty()) return false
        // Match: django, django==5.1, django>=5.0, "django", 'django', django = "^5.1"
        val pattern = Regex("""(?i)\b$name\b""")
        return pattern.containsMatchIn(content)
    }

    private fun readFileIfExists(path: Path): String? =
        if (path.exists()) {
            try { path.readText() } catch (_: Exception) { null }
        } else null
}
