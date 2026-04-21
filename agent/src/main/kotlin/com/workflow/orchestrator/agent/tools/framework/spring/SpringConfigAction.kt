package com.workflow.orchestrator.agent.tools.framework.spring

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Properties

internal suspend fun executeConfig(params: JsonObject, project: Project): ToolResult {
    val propertyName = params["property"]?.jsonPrimitive?.content

    return try {
        withContext(Dispatchers.IO) {
            // Pattern-based scan so any user profile matches:
            // application-mydocker.properties, application-staging.yml, etc.
            val configFiles = scanSpringResourceFilesMatching(project) {
                SPRING_CONFIG_FILE_PATTERN.matches(it.name)
            }
            if (configFiles.isEmpty()) {
                return@withContext ToolResult(
                    "No Spring configuration files found (application.properties, application.yml, application.yaml).",
                    "No config files", 10
                )
            }

            val allProperties = mutableMapOf<String, MutableList<ConfigPropertyEntry>>()

            for (entry in configFiles) {
                val extension = entry.file.extension.lowercase()
                when (extension) {
                    "properties" -> parsePropertiesFile(entry.file, entry.relativePath, allProperties)
                    "yml", "yaml" -> parseYamlFile(entry.file, entry.relativePath, allProperties)
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
                summary = if (propertyName != null) "Lookup: $propertyName" else "${allProperties.size} properties from ${configFiles.size} file(s) across ${configFiles.mapNotNull { it.moduleName }.toSet().size.coerceAtLeast(1)} module(s)",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        }
    } catch (e: Exception) {
        ToolResult("Error reading Spring config: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}

private fun parsePropertiesFile(file: File, relativePath: String, target: MutableMap<String, MutableList<ConfigPropertyEntry>>) {
    val props = Properties()
    file.inputStream().use { props.load(it) }
    for ((key, value) in props) {
        val k = key.toString()
        target.getOrPut(k) { mutableListOf() }.add(ConfigPropertyEntry(value.toString(), relativePath))
    }
}

private fun parseYamlFile(file: File, relativePath: String, target: MutableMap<String, MutableList<ConfigPropertyEntry>>) {
    val properties = parseYamlToFlatProperties(file.readText())
    for ((key, value) in properties) {
        target.getOrPut(key) { mutableListOf() }.add(ConfigPropertyEntry(value, relativePath))
    }
}

private fun formatPropertyLookup(propertyName: String, allProperties: Map<String, List<ConfigPropertyEntry>>): String {
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

private fun formatAllProperties(allProperties: Map<String, List<ConfigPropertyEntry>>): String {
    return buildString {
        appendLine("Spring configuration properties (${allProperties.size} total):")
        appendLine()

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

private data class ConfigPropertyEntry(val value: String, val source: String)
