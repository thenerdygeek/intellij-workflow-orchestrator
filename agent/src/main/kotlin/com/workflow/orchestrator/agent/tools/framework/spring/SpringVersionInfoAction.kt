package com.workflow.orchestrator.agent.tools.framework.spring

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.framework.MavenUtils
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

// Gradle: Spring Boot plugin version patterns
private val GROOVY_PLUGIN_VERSION_PATTERN = Regex(
    """id\s+['"]org\.springframework\.boot['"]\s+version\s+['"]([^'"]+)['"]"""
)
private val KOTLIN_PLUGIN_VERSION_PATTERN = Regex(
    """id\s*\(\s*"org\.springframework\.boot"\s*\)\s*version\s*"([^"]+)""""
)

// Gradle: dependency patterns (Groovy and Kotlin DSL)
private val GRADLE_DEPENDENCY_PATTERN = Regex(
    """(?:implementation|api|compileOnly|runtimeOnly|testImplementation|annotationProcessor)\s*[\s(]+['"]([^:'"]+):([^:'"]+)(?::([^'"]+))?['"]"""
)

internal suspend fun executeVersionInfo(params: JsonObject, project: Project): ToolResult {
    return try {
        val moduleFilter = params["module"]?.jsonPrimitive?.content

        // Try Maven first
        val manager = MavenUtils.getMavenManager(project)
        if (manager != null) {
            return executeMavenVersionInfo(manager, moduleFilter)
        }

        // Fallback to Gradle
        val basePath = project.basePath
            ?: return ToolResult(
                "Neither Maven nor Gradle build files found. This tool requires a Maven or Gradle project.",
                "No build system", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )

        return executeGradleVersionInfo(basePath)
    } catch (e: Exception) {
        ToolResult("Error detecting versions: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}

private fun executeMavenVersionInfo(manager: Any, moduleFilter: String?): ToolResult {
    val mavenProjects = MavenUtils.getMavenProjects(manager)
    if (mavenProjects.isEmpty()) {
        return ToolResult("No Maven projects found.", "No Maven projects", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }

    if (moduleFilter != null) {
        val targetProject = MavenUtils.findMavenProject(mavenProjects, manager, moduleFilter)
            ?: return ToolResult(
                "Module '${moduleFilter}' not found. Available: ${MavenUtils.getProjectNames(mavenProjects)}",
                "Module not found", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        return renderSingleMavenModule(targetProject)
    }

    return renderAllMavenModules(mavenProjects)
}

private fun renderSingleMavenModule(targetProject: Any): ToolResult {
    val projectName = MavenUtils.getDisplayName(targetProject)
    val projectVersion = MavenUtils.getMavenId(targetProject, "getVersion") ?: "unknown"
    val versions = extractVersionsForMavenProject(targetProject)

    if (versions.isEmpty()) {
        return ToolResult(
            "Project: $projectName ($projectVersion)\nNo recognized framework versions detected from Maven dependencies.",
            "No frameworks", 10
        )
    }

    val content = buildString {
        appendLine("Project: $projectName ($projectVersion)")
        for ((name, version) in versions) {
            appendLine("$name: $version")
        }
    }

    return ToolResult(
        content = content.trimEnd(),
        summary = versions.entries.joinToString(", ") { "${it.key} ${it.value}" },
        tokenEstimate = TokenEstimator.estimate(content)
    )
}

/**
 * Multi-module Maven aggregation. For each detected framework, collapse to a
 * single line if every module that references it agrees on the version; when
 * versions diverge across modules, render one line per version with the
 * originating module list — so a mixed-Spring-Boot monorepo doesn't hide a
 * version mismatch behind the root pom.
 */
private fun renderAllMavenModules(mavenProjects: List<Any>): ToolResult {
    val totalModules = mavenProjects.size
    val frameworkMap = linkedMapOf<String, LinkedHashMap<String, MutableList<String>>>()

    for (project in mavenProjects) {
        val moduleName = MavenUtils.getDisplayName(project)
        val versions = extractVersionsForMavenProject(project)
        for ((framework, version) in versions) {
            frameworkMap.getOrPut(framework) { linkedMapOf() }
                .getOrPut(version) { mutableListOf() }
                .add(moduleName)
        }
    }

    if (frameworkMap.isEmpty()) {
        return ToolResult(
            "No recognized framework versions detected across $totalModules Maven module(s).",
            "No frameworks", 10
        )
    }

    val rootName = MavenUtils.getDisplayName(mavenProjects.first())
    val content = buildString {
        appendLine("Project: $rootName (Maven, $totalModules module${if (totalModules == 1) "" else "s"})")
        val moduleNames = mavenProjects.joinToString(", ") { MavenUtils.getDisplayName(it) }
        appendLine("Modules: $moduleNames")
        appendLine()
        for ((framework, versionMap) in frameworkMap) {
            if (versionMap.size == 1) {
                val (version, modules) = versionMap.entries.first()
                val suffix = if (modules.size == totalModules) "" else " (in: ${modules.joinToString(", ")})"
                appendLine("$framework: $version$suffix")
            } else {
                appendLine("$framework: (diverges across modules)")
                for ((version, modules) in versionMap) {
                    appendLine("  $version — ${modules.joinToString(", ")}")
                }
            }
        }
    }

    val summary = frameworkMap.entries.joinToString(", ") { (framework, versionMap) ->
        if (versionMap.size == 1) "$framework ${versionMap.keys.first()}"
        else "$framework (${versionMap.size} versions)"
    }

    return ToolResult(
        content = content.trimEnd(),
        summary = summary,
        tokenEstimate = TokenEstimator.estimate(content)
    )
}

private fun extractVersionsForMavenProject(mavenProject: Any): Map<String, String> {
    val dependencies = MavenUtils.getDependencies(mavenProject)
    val properties = MavenUtils.getProperties(mavenProject)
    val versions = linkedMapOf<String, String>()

    findVersion(dependencies, "org.springframework.boot", "spring-boot-starter", "spring-boot")?.let {
        versions["Spring Boot"] = it
    }
    if ("Spring Boot" !in versions) {
        getParentVersion(mavenProject, "org.springframework.boot")?.let {
            versions["Spring Boot"] = it
        }
    }

    findVersion(dependencies, "org.springframework", "spring-core", "spring-context", "spring-web")?.let {
        versions["Spring Framework"] = it
    }

    val javaVersion = properties["java.version"]
        ?: properties["maven.compiler.source"]
        ?: properties["maven.compiler.target"]
        ?: properties["maven.compiler.release"]
    if (javaVersion != null) {
        versions["Java"] = javaVersion
    }

    findVersion(dependencies, "org.jetbrains.kotlin", "kotlin-stdlib", "kotlin-stdlib-jdk8", "kotlin-reflect")?.let {
        versions["Kotlin"] = it
    } ?: properties["kotlin.version"]?.let { versions["Kotlin"] = it }

    findVersion(dependencies, "org.junit.jupiter", "junit-jupiter", "junit-jupiter-api")?.let {
        versions["JUnit"] = it
    }

    findVersion(dependencies, "org.hibernate.orm", "hibernate-core")?.let {
        versions["Hibernate"] = it
    } ?: findVersion(dependencies, "org.hibernate", "hibernate-core")?.let {
        versions["Hibernate"] = it
    }

    findVersion(dependencies, "com.fasterxml.jackson.core", "jackson-databind")?.let {
        versions["Jackson"] = it
    }

    findVersion(dependencies, "org.projectlombok", "lombok")?.let {
        versions["Lombok"] = it
    }

    findVersion(dependencies, "org.apache.commons", "commons-lang3")?.let {
        versions["Commons Lang"] = it
    }

    findVersion(dependencies, "ch.qos.logback", "logback-classic")?.let {
        versions["Logback"] = it
    }

    return versions
}

private suspend fun executeGradleVersionInfo(basePath: String): ToolResult = withContext(Dispatchers.IO) {
    val baseDir = File(basePath)

    // Find all Gradle build files
    val buildFiles = baseDir.walkTopDown()
        .filter { it.isFile && (it.name == "build.gradle" || it.name == "build.gradle.kts") }
        .toList()

    if (buildFiles.isEmpty()) {
        return@withContext ToolResult(
            "Neither Maven nor Gradle build files found. This tool requires a Maven or Gradle project.",
            "No build system", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
        )
    }

    var springBootVersion: String? = null
    val starters = mutableSetOf<String>()
    val otherDeps = mutableMapOf<String, String>() // groupId:artifactId -> version

    for (buildFile in buildFiles) {
        val content = buildFile.readText()

        // Extract Spring Boot plugin version
        if (springBootVersion == null) {
            springBootVersion = GROOVY_PLUGIN_VERSION_PATTERN.find(content)?.groupValues?.get(1)
                ?: KOTLIN_PLUGIN_VERSION_PATTERN.find(content)?.groupValues?.get(1)
        }

        // Extract dependencies
        GRADLE_DEPENDENCY_PATTERN.findAll(content).forEach { match ->
            val groupId = match.groupValues[1]
            val artifactId = match.groupValues[2]
            val version = match.groupValues[3] // May be empty if managed by BOM/plugin

            if (groupId == "org.springframework.boot" && artifactId.startsWith("spring-boot-starter")) {
                starters.add(artifactId)
            }

            if (version.isNotBlank()) {
                when {
                    groupId == "org.jetbrains.kotlin" && artifactId.startsWith("kotlin-") ->
                        otherDeps["Kotlin"] = version
                    groupId == "org.junit.jupiter" ->
                        otherDeps["JUnit"] = version
                    groupId == "org.hibernate.orm" && artifactId == "hibernate-core" ->
                        otherDeps["Hibernate"] = version
                    groupId == "org.hibernate" && artifactId == "hibernate-core" && "Hibernate" !in otherDeps ->
                        otherDeps["Hibernate"] = version
                    groupId == "com.fasterxml.jackson.core" && artifactId == "jackson-databind" ->
                        otherDeps["Jackson"] = version
                    groupId == "org.projectlombok" && artifactId == "lombok" ->
                        otherDeps["Lombok"] = version
                }
            }
        }
    }

    // Also check settings.gradle(.kts) for project name
    val projectName = findGradleProjectName(baseDir) ?: baseDir.name

    if (springBootVersion == null && starters.isEmpty() && otherDeps.isEmpty()) {
        return@withContext ToolResult(
            "Project: $projectName (Gradle)\nNo Spring Boot version or Spring dependencies detected in Gradle build files.",
            "No Spring detected", 10
        )
    }

    val content = buildString {
        appendLine("Project: $projectName (Gradle)")

        if (springBootVersion != null) {
            appendLine("Spring Boot: $springBootVersion")
        }

        for ((name, version) in otherDeps) {
            appendLine("$name: $version")
        }

        if (starters.isNotEmpty()) {
            appendLine()
            appendLine("Spring Boot Starters (${starters.size}):")
            for (starter in starters.sorted()) {
                appendLine("  $starter")
            }
        }
    }

    val summary = buildString {
        if (springBootVersion != null) append("Spring Boot $springBootVersion")
        if (starters.isNotEmpty()) {
            if (isNotEmpty()) append(", ")
            append("${starters.size} starters")
        }
        if (otherDeps.isNotEmpty()) {
            if (isNotEmpty()) append(", ")
            append(otherDeps.entries.joinToString(", ") { "${it.key} ${it.value}" })
        }
    }

    ToolResult(
        content = content.trimEnd(),
        summary = summary,
        tokenEstimate = TokenEstimator.estimate(content)
    )
}

private fun findGradleProjectName(baseDir: File): String? {
    val settingsFile = baseDir.resolve("settings.gradle.kts").takeIf { it.exists() }
        ?: baseDir.resolve("settings.gradle").takeIf { it.exists() }
        ?: return null
    val content = settingsFile.readText()
    // rootProject.name = "my-project" or rootProject.name = 'my-project'
    val match = Regex("""rootProject\.name\s*=\s*["']([^"']+)["']""").find(content)
    return match?.groupValues?.get(1)
}

private fun findVersion(dependencies: List<MavenUtils.MavenDependencyInfo>, groupId: String, vararg artifactIds: String): String? {
    for (artifactId in artifactIds) {
        val dep = dependencies.find { it.groupId == groupId && it.artifactId == artifactId && it.version.isNotBlank() }
        if (dep != null) return dep.version
    }
    return dependencies.find { it.groupId == groupId && it.version.isNotBlank() }?.version
}

private fun getParentVersion(mavenProject: Any, parentGroupId: String): String? {
    return try {
        val parentId = mavenProject.javaClass.getMethod("getParentId").invoke(mavenProject) ?: return null
        val groupId = parentId.javaClass.getMethod("getGroupId").invoke(parentId) as? String
        if (groupId == parentGroupId) {
            parentId.javaClass.getMethod("getVersion").invoke(parentId) as? String
        } else null
    } catch (_: Exception) { null }
}
