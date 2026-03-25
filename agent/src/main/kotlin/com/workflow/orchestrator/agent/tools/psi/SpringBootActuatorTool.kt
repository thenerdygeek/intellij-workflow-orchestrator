package com.workflow.orchestrator.agent.tools.psi

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.framework.MavenUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.Properties

class SpringBootActuatorTool : AgentTool {
    override val name = "spring_boot_actuator"
    override val description = "Analyze Spring Boot Actuator setup: detect actuator dependency, list configured endpoints, exposure settings, management port, base-path."
    override val parameters = FunctionParameters(properties = emptyMap(), required = emptyList())
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.ORCHESTRATOR)

    // All default actuator endpoints with their descriptions
    private val defaultEndpoints = listOf(
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

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val basePath = project.basePath
            ?: return ToolResult(
                "Error: project base path not available",
                "Error",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        return try {
            withContext(Dispatchers.IO) {
                analyzeActuator(project, File(basePath))
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

    private fun analyzeActuator(project: Project, baseDir: File): ToolResult {
        // --- 1. Dependency check ---
        val actuatorDetected = checkActuatorDependency(project, baseDir)

        // --- 2. Read management.* properties ---
        val mgmtProps = readManagementProperties(baseDir)

        val basePath = mgmtProps["management.endpoints.web.base-path"]
            ?: mgmtProps["management.server.base-path"]
            ?: "/actuator"

        val port = mgmtProps["management.server.port"]

        val includeRaw = mgmtProps["management.endpoints.web.exposure.include"] ?: ""
        val excludeRaw = mgmtProps["management.endpoints.web.exposure.exclude"] ?: ""

        val includeSet: Set<String> = if (includeRaw.isBlank()) {
            setOf("health", "info") // Spring Boot default
        } else {
            includeRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        }
        val excludeSet: Set<String> = excludeRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

        val showDetails = mgmtProps["management.endpoint.health.show-details"]

        // --- 3. Build output ---
        val content = buildString {
            appendLine("Spring Boot Actuator:")
            appendLine()

            if (actuatorDetected) {
                appendLine("✓ spring-boot-starter-actuator detected")
            } else {
                appendLine("✗ spring-boot-starter-actuator NOT detected")
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
            for ((endpointId, desc) in defaultEndpoints) {
                val exposed = (exposedAll || includeSet.contains(endpointId)) && !excludeSet.contains(endpointId)
                val marker = if (exposed) "✓" else "·"
                val paddedId = endpointId.padEnd(16)
                appendLine("  $marker $paddedId — $desc")
            }

            if (showDetails != null) {
                appendLine()
                appendLine("Health endpoint:")
                appendLine("  Show details: $showDetails")
            }

            // Extra management properties found
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
            defaultEndpoints.count { !excludeSet.contains(it.first) }
        } else {
            includeSet.count { !excludeSet.contains(it) }
        }

        return ToolResult(
            content = content,
            summary = if (actuatorDetected) "Actuator detected, $exposedCount endpoints exposed" else "Actuator not detected",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }

    /** Check Maven dependencies or Gradle build files for spring-boot-starter-actuator */
    private fun checkActuatorDependency(project: Project, baseDir: File): Boolean {
        // Maven check via MavenUtils (reflection-based, safe if Maven not present)
        try {
            val manager = MavenUtils.getMavenManager(project)
            if (manager != null) {
                val mavenProjects = MavenUtils.getMavenProjects(manager)
                for (mavenProject in mavenProjects) {
                    val deps = MavenUtils.getDependencies(mavenProject)
                    if (deps.any { it.artifactId == "spring-boot-starter-actuator" }) {
                        return true
                    }
                }
                // Maven is present but actuator not found — return false without file fallback
                if (mavenProjects.isNotEmpty()) return false
            }
        } catch (_: Exception) { /* fall through to file-based check */ }

        // Gradle/file-based fallback: search build files for actuator string
        val buildFileNames = listOf("build.gradle", "build.gradle.kts", "pom.xml")
        val searchRoots = mutableListOf(baseDir)
        baseDir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.let { searchRoots.addAll(it) }

        for (root in searchRoots) {
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

    /**
     * Read management.* properties from application.properties / application.yml
     * in src/main/resources/ (and sub-module equivalents).
     */
    private fun readManagementProperties(baseDir: File): Map<String, String> {
        val result = mutableMapOf<String, String>()

        val searchDirs = listOf(
            "src/main/resources",
            "src/main/resources/config"
        )
        val fileNames = listOf(
            "application.properties",
            "application.yml",
            "application.yaml"
        )

        val roots = mutableListOf(baseDir)
        baseDir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.let { roots.addAll(it) }

        for (root in roots) {
            for (dir in searchDirs) {
                val resourceDir = File(root, dir)
                if (!resourceDir.isDirectory) continue
                for (fileName in fileNames) {
                    val file = File(resourceDir, fileName)
                    if (!file.isFile) continue
                    when (file.extension.lowercase()) {
                        "properties" -> readPropertiesManagement(file, result)
                        "yml", "yaml" -> readYamlManagement(file, result)
                    }
                }
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

    /**
     * Simple YAML parser — extracts only management.* leaf values.
     * Uses the same flat-key approach as SpringConfigTool.
     */
    private fun readYamlManagement(file: File, target: MutableMap<String, String>) {
        val lines = file.readLines()
        val keyStack = mutableListOf<Pair<Int, String>>() // indent -> key segment

        for (line in lines) {
            val trimmed = line.trimEnd()
            if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("---")) continue

            val indent = line.length - line.trimStart().length

            while (keyStack.isNotEmpty() && keyStack.last().first >= indent) {
                keyStack.removeAt(keyStack.size - 1)
            }

            val colonIndex = trimmed.indexOf(':')
            if (colonIndex < 0) continue

            val key = trimmed.substring(0, colonIndex).trim()
            val value = trimmed.substring(colonIndex + 1).trim()

            keyStack.add(indent to key)

            if (value.isNotEmpty() && !value.startsWith("#")) {
                val fullKey = keyStack.joinToString(".") { it.second }
                if (fullKey.startsWith("management.")) {
                    val cleanValue = value.removeSurrounding("\"").removeSurrounding("'")
                    target[fullKey] = cleanValue
                }
            }
        }
    }
}
