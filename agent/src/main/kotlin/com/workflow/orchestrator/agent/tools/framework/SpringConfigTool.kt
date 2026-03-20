package com.workflow.orchestrator.agent.tools.framework

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Properties

class SpringConfigTool : AgentTool {
    override val name = "spring_config"
    override val description = "Read Spring configuration properties from application.properties/yml files. Lists all properties or looks up a specific one."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "property" to ParameterProperty(type = "string", description = "Optional: specific property name to look up (e.g., 'spring.datasource.url'). If omitted, lists all properties.")
        ),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val propertyName = params["property"]?.jsonPrimitive?.content
        val basePath = project.basePath
            ?: return ToolResult("Error: project base path not available", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        return try {
            withContext(Dispatchers.IO) {
                val configFiles = findConfigFiles(File(basePath))
                if (configFiles.isEmpty()) {
                    return@withContext ToolResult(
                        "No Spring configuration files found (application.properties, application.yml, application.yaml).",
                        "No config files", 10
                    )
                }

                val allProperties = mutableMapOf<String, MutableList<PropertyEntry>>()

                for (configFile in configFiles) {
                    val relativePath = configFile.absolutePath.removePrefix("$basePath/")
                    val extension = configFile.extension.lowercase()

                    when (extension) {
                        "properties" -> parsePropertiesFile(configFile, relativePath, allProperties)
                        "yml", "yaml" -> parseYamlFile(configFile, relativePath, allProperties)
                    }
                }

                if (allProperties.isEmpty()) {
                    return@withContext ToolResult("Configuration files found but contain no properties.", "Empty config", 5)
                }

                val content = if (propertyName != null) {
                    formatPropertyLookup(propertyName, allProperties)
                } else {
                    formatAllProperties(allProperties)
                }

                ToolResult(
                    content = content,
                    summary = if (propertyName != null) "Lookup: $propertyName" else "${allProperties.size} properties from ${configFiles.size} file(s)",
                    tokenEstimate = TokenEstimator.estimate(content)
                )
            }
        } catch (e: Exception) {
            ToolResult("Error reading Spring config: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    private fun findConfigFiles(baseDir: File): List<File> {
        val searchDirs = listOf(
            "src/main/resources",
            "src/main/resources/config",
            "src/test/resources"
        )
        val fileNames = listOf(
            "application.properties",
            "application.yml",
            "application.yaml",
            "application-dev.properties",
            "application-dev.yml",
            "application-test.properties",
            "application-test.yml",
            "application-prod.properties",
            "application-prod.yml",
            "bootstrap.properties",
            "bootstrap.yml"
        )

        val found = mutableListOf<File>()

        // Search in standard locations
        for (dir in searchDirs) {
            val resourceDir = File(baseDir, dir)
            if (!resourceDir.isDirectory) continue
            for (fileName in fileNames) {
                val file = File(resourceDir, fileName)
                if (file.isFile) found.add(file)
            }
        }

        // Also search in multi-module subproject standard locations
        baseDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.forEach { subDir ->
            for (dir in searchDirs) {
                val resourceDir = File(subDir, dir)
                if (!resourceDir.isDirectory) continue
                for (fileName in fileNames) {
                    val file = File(resourceDir, fileName)
                    if (file.isFile) found.add(file)
                }
            }
        }

        return found
    }

    private fun parsePropertiesFile(file: File, relativePath: String, target: MutableMap<String, MutableList<PropertyEntry>>) {
        val props = Properties()
        file.inputStream().use { props.load(it) }
        for ((key, value) in props) {
            val k = key.toString()
            target.getOrPut(k) { mutableListOf() }.add(PropertyEntry(value.toString(), relativePath))
        }
    }

    private fun parseYamlFile(file: File, relativePath: String, target: MutableMap<String, MutableList<PropertyEntry>>) {
        // Simple YAML parser for flat/nested properties (no external YAML library dependency)
        val lines = file.readLines()
        val keyStack = mutableListOf<Pair<Int, String>>() // indent level to key segment

        for (line in lines) {
            val trimmed = line.trimEnd()
            if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("---")) continue

            val indent = line.length - line.trimStart().length

            // Pop keys at same or deeper indent level
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
                // Strip quotes from value
                val cleanValue = value.removeSurrounding("\"").removeSurrounding("'")
                target.getOrPut(fullKey) { mutableListOf() }.add(PropertyEntry(cleanValue, relativePath))
            }
        }
    }

    private fun formatPropertyLookup(propertyName: String, allProperties: Map<String, List<PropertyEntry>>): String {
        val exact = allProperties[propertyName]
        if (exact != null) {
            return buildString {
                appendLine("Property: $propertyName")
                exact.forEach { entry ->
                    appendLine("  Value: ${entry.value}")
                    appendLine("  File:  ${entry.source}")
                }
            }
        }

        // Fuzzy match
        val matches = allProperties.filter { (key, _) -> key.contains(propertyName, ignoreCase = true) }
        if (matches.isEmpty()) {
            return "Property '$propertyName' not found in any configuration file."
        }

        return buildString {
            appendLine("Property '$propertyName' not found exactly. Similar properties:")
            matches.entries.take(20).forEach { (key, entries) ->
                entries.forEach { entry ->
                    appendLine("  $key = ${entry.value}  (${entry.source})")
                }
            }
            if (matches.size > 20) appendLine("  ... and ${matches.size - 20} more")
        }
    }

    private fun formatAllProperties(allProperties: Map<String, List<PropertyEntry>>): String {
        return buildString {
            appendLine("Spring configuration properties (${allProperties.size} total):")
            appendLine()

            // Group by source file
            val byFile = mutableMapOf<String, MutableList<Pair<String, String>>>()
            for ((key, entries) in allProperties) {
                for (entry in entries) {
                    byFile.getOrPut(entry.source) { mutableListOf() }.add(key to entry.value)
                }
            }

            for ((file, props) in byFile) {
                appendLine("[$file]")
                props.sortedBy { it.first }.take(100).forEach { (key, value) ->
                    val displayValue = if (value.length > 80) value.take(77) + "..." else value
                    appendLine("  $key = $displayValue")
                }
                if (props.size > 100) appendLine("  ... and ${props.size - 100} more")
                appendLine()
            }
        }
    }

    private data class PropertyEntry(val value: String, val source: String)
}
