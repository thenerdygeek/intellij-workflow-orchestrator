package com.workflow.orchestrator.agent.tools.framework.spring

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.framework.MavenUtils
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.Properties

private val defaultActuatorEndpoints = listOf(
    "health"         to "Shows application health",
    "info"           to "Application info",
    "metrics"        to "Application metrics",
    "env"            to "Environment properties",
    "beans"          to "All Spring beans",
    "configprops"    to "Configuration properties",
    "mappings"       to "Request mapping paths",
    "loggers"        to "Logger levels",
    "threaddump"     to "Thread dump",
    "heapdump"       to "Heap dump",
    "conditions"     to "Auto-configuration conditions",
    "shutdown"       to "Graceful shutdown (disabled by default)",
    "scheduledtasks" to "Scheduled tasks",
    "caches"         to "Cache managers",
    "prometheus"     to "Prometheus metrics"
)

internal suspend fun executeBootActuator(params: JsonObject, project: Project): ToolResult {
    return try {
        // Maven plugin model access requires a read action, which the
        // coroutine-friendly `readAction { }` builder can only supply from a
        // suspend context — so we do the Maven detection here, before
        // dropping into the non-suspend file-scan path.
        // Result: null = no Maven (fall back to file scan), true/false = Maven answer.
        val mavenActuator: Boolean? = readAction {
            val manager = MavenUtils.getMavenManager(project) ?: return@readAction null
            val mavenProjects = MavenUtils.getMavenProjects(manager)
            if (mavenProjects.isEmpty()) return@readAction null
            for (mavenProject in mavenProjects) {
                val deps = MavenUtils.getDependencies(mavenProject)
                if (deps.any { it.artifactId == "spring-boot-starter-actuator" }) {
                    return@readAction true
                }
            }
            false
        }
        withContext(Dispatchers.IO) {
            analyzeActuator(project, mavenActuator)
        }
    } catch (e: Exception) {
        ToolResult(
            "Error analyzing Spring Boot Actuator: ${e.message}",
            "Error",
            ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )
    }
}

private fun analyzeActuator(project: Project, mavenActuator: Boolean?): ToolResult {
    val actuatorDetected = mavenActuator ?: checkActuatorDependency(project)

    val mgmtProps = readManagementProperties(project)

    val basePath = mgmtProps["management.endpoints.web.base-path"]
        ?: mgmtProps["management.server.base-path"]
        ?: "/actuator"

    val port = mgmtProps["management.server.port"]

    val includeRaw = mgmtProps["management.endpoints.web.exposure.include"] ?: ""
    val excludeRaw = mgmtProps["management.endpoints.web.exposure.exclude"] ?: ""

    val includeSet: Set<String> = if (includeRaw.isBlank()) {
        setOf("health", "info")
    } else {
        includeRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }
    val excludeSet: Set<String> = excludeRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    val showDetails = mgmtProps["management.endpoint.health.show-details"]

    val content = buildString {
        appendLine("Spring Boot Actuator:")
        appendLine()

        if (actuatorDetected) {
            appendLine("OK spring-boot-starter-actuator detected")
        } else {
            appendLine("MISSING spring-boot-starter-actuator NOT detected")
            appendLine("  Add to pom.xml: <artifactId>spring-boot-starter-actuator</artifactId>")
            appendLine("  or build.gradle: implementation 'org.springframework.boot:spring-boot-starter-actuator'")
        }

        appendLine()
        appendLine("Management:")
        appendLine("  Base path: $basePath")
        if (port != null) {
            appendLine("  Port: $port (separate from app)")
        } else {
            appendLine("  Port: same as application")
        }

        appendLine()
        appendLine("Web exposure:")
        appendLine("  Include: ${if (includeSet.contains("*")) "*" else includeSet.sorted().joinToString(",")}")
        appendLine("  Exclude: ${excludeSet.sorted().joinToString(",")}")

        appendLine()
        appendLine("Endpoints:")

        val exposedAll = includeSet.contains("*")
        for ((endpointId, desc) in defaultActuatorEndpoints) {
            val exposed = (exposedAll || includeSet.contains(endpointId)) && !excludeSet.contains(endpointId)
            val marker = if (exposed) "[x]" else "[ ]"
            val paddedId = endpointId.padEnd(16)
            appendLine("  $marker $paddedId — $desc")
        }

        if (showDetails != null) {
            appendLine()
            appendLine("Health endpoint:")
            appendLine("  Show details: $showDetails")
        }

        val extraProps = mgmtProps.filterKeys { key ->
            key != "management.endpoints.web.base-path" &&
                key != "management.server.base-path" &&
                key != "management.server.port" &&
                key != "management.endpoints.web.exposure.include" &&
                key != "management.endpoints.web.exposure.exclude" &&
                key != "management.endpoint.health.show-details" &&
                key.startsWith("management.")
        }
        if (extraProps.isNotEmpty()) {
            appendLine()
            appendLine("Other management properties:")
            for ((key, value) in extraProps.toSortedMap()) {
                appendLine("  $key = $value")
            }
        }
    }.trimEnd()

    val exposedCount = if (includeSet.contains("*")) {
        defaultActuatorEndpoints.count { !excludeSet.contains(it.first) }
    } else {
        includeSet.count { !excludeSet.contains(it) }
    }

    return ToolResult(
        content = content,
        summary = if (actuatorDetected) "Actuator detected, $exposedCount endpoints exposed" else "Actuator not detected",
        tokenEstimate = TokenEstimator.estimate(content)
    )
}

// File-scan fallback used when no Maven project is present. The Maven branch
// has been hoisted to `executeBootActuator` because it must run inside a
// read action (which the suspend-only `readAction { }` builder provides).
private fun checkActuatorDependency(project: Project): Boolean {
    val buildFileNames = listOf("build.gradle", "build.gradle.kts", "pom.xml")
    val roots = collectModuleContentRoots(project)

    for (root in roots) {
        for (buildFileName in buildFileNames) {
            val buildFile = File(root, buildFileName)
            if (buildFile.isFile) {
                val text = buildFile.readText()
                if (text.contains("spring-boot-starter-actuator")) return true
            }
        }
    }

    return false
}

private fun collectModuleContentRoots(project: Project): List<File> {
    // IDE module-model access requires a read action; extract paths inside it
    // and do the filesystem isDirectory check outside.
    val rootPaths: List<String> = ReadAction.nonBlocking<List<String>> {
        val modules = com.intellij.openapi.module.ModuleManager.getInstance(project).modules
        if (modules.isEmpty()) emptyList()
        else modules.flatMap { module ->
            com.intellij.openapi.roots.ModuleRootManager.getInstance(module).contentRoots.map { it.path }
        }
    }.executeSynchronously()

    if (rootPaths.isEmpty()) {
        return listOfNotNull(project.basePath?.let { File(it) })
    }
    val out = linkedSetOf<File>()
    for (path in rootPaths) {
        val f = File(path)
        if (f.isDirectory) out.add(f)
    }
    return out.toList()
}

private fun readManagementProperties(project: Project): Map<String, String> {
    val result = mutableMapOf<String, String>()
    val files = scanSpringResourceFiles(
        project,
        listOf("application.properties", "application.yml", "application.yaml"),
    )

    for (entry in files) {
        when (entry.file.extension.lowercase()) {
            "properties" -> readPropertiesManagement(entry.file, result)
            "yml", "yaml" -> readYamlManagement(entry.file, result)
        }
    }

    return result
}

private fun readPropertiesManagement(file: File, target: MutableMap<String, String>) {
    val props = Properties()
    file.inputStream().use { props.load(it) }
    for ((key, value) in props) {
        val k = key.toString()
        if (k.startsWith("management.")) {
            target[k] = value.toString()
        }
    }
}

private fun readYamlManagement(file: File, target: MutableMap<String, String>) {
    val properties = parseYamlToFlatProperties(file.readText())
    for ((key, value) in properties) {
        if (key.startsWith("management.")) {
            target[key] = value
        }
    }
}
