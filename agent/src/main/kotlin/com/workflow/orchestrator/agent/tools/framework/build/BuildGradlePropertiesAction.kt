package com.workflow.orchestrator.agent.tools.framework.build

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Properties

private data class GradlePropertySource(
    val label: String,
    val properties: Map<String, String>
)

internal suspend fun executeGradleProperties(params: JsonObject, project: Project): ToolResult {
    val searchFilter = params["search"]?.jsonPrimitive?.content?.lowercase()
    val basePath = project.basePath
        ?: return ToolResult("Error: project base path not available", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

    return try {
        withContext(Dispatchers.IO) {
            val baseDir = File(basePath)
            val sources = mutableListOf<GradlePropertySource>()

            collectGradleProperties(baseDir, "gradle.properties", sources)

            val modules = listGradleModules(baseDir)
            for (mod in modules) {
                val modDir = File(baseDir, mod.trimStart(':').replace(':', '/'))
                collectGradleProperties(modDir, "${mod.trimStart(':')}/gradle.properties", sources)
            }

            if (modules.isEmpty()) {
                baseDir.listFiles()
                    ?.filter { it.isDirectory && !it.name.startsWith(".") && it.name != "build" && it.name != "gradle" }
                    ?.forEach { subDir ->
                        collectGradleProperties(subDir, "${subDir.name}/gradle.properties", sources)
                    }
            }

            val versionCatalog = File(baseDir, "gradle/libs.versions.toml")
            if (versionCatalog.isFile) {
                val catalogSource = parseVersionCatalog(versionCatalog)
                if (catalogSource != null) sources.add(catalogSource)
            }

            if (sources.isEmpty()) {
                return@withContext ToolResult(
                    "No gradle.properties or libs.versions.toml found in project.",
                    "No property files", 10
                )
            }

            val filteredSources = sources.map { source ->
                if (searchFilter != null) {
                    val filtered = source.properties.filter { (key, value) ->
                        key.lowercase().contains(searchFilter) || value.lowercase().contains(searchFilter)
                    }
                    source.copy(properties = filtered)
                } else {
                    source
                }
            }.filter { it.properties.isNotEmpty() }

            val totalCount = filteredSources.sumOf { it.properties.size }

            if (totalCount == 0) {
                return@withContext ToolResult(
                    "No properties found matching '${searchFilter}'.",
                    "No matches", 5
                )
            }

            val content = buildString {
                appendLine("Gradle properties ($totalCount total across ${filteredSources.size} source(s)):")
                appendLine()

                for (source in filteredSources) {
                    appendLine("[${source.label}]")
                    source.properties.entries
                        .sortedBy { it.key }
                        .forEach { (key, value) ->
                            val displayValue = if (value.length > 100) value.take(97) + "..." else value
                            appendLine("  $key = $displayValue")
                        }
                    appendLine()
                }
            }

            ToolResult(
                content = content.trimEnd(),
                summary = "$totalCount properties from ${filteredSources.size} file(s)",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        }
    } catch (e: Exception) {
        ToolResult("Error reading Gradle properties: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}

private fun collectGradleProperties(dir: File, label: String, sources: MutableList<GradlePropertySource>) {
    val file = File(dir, "gradle.properties")
    if (!file.isFile) return

    val props = Properties()
    file.inputStream().use { props.load(it) }

    if (props.isNotEmpty()) {
        val map = props.entries.associate { it.key.toString() to it.value.toString() }
        sources.add(GradlePropertySource(label, map))
    }
}

private fun parseVersionCatalog(tomlFile: File): GradlePropertySource? {
    val lines = tomlFile.readLines()
    val versions = mutableMapOf<String, String>()

    var inVersionsSection = false

    for (line in lines) {
        val trimmed = line.trim()

        if (trimmed.startsWith("[")) {
            inVersionsSection = trimmed == "[versions]"
            continue
        }

        if (!inVersionsSection) continue
        if (trimmed.isBlank() || trimmed.startsWith("#")) continue

        val eqIndex = trimmed.indexOf('=')
        if (eqIndex < 0) continue

        val key = trimmed.substring(0, eqIndex).trim()
        val rawValue = trimmed.substring(eqIndex + 1).trim()

        val value = when {
            rawValue.startsWith('"') && rawValue.endsWith('"') ->
                rawValue.removeSurrounding("\"")
            rawValue.startsWith('\'') && rawValue.endsWith('\'') ->
                rawValue.removeSurrounding("'")
            rawValue.startsWith("{") -> {
                val requirePattern = Regex("""require\s*=\s*["']([^"']+)["']""")
                val refPattern = Regex("""ref\s*=\s*["']([^"']+)["']""")
                requirePattern.find(rawValue)?.groupValues?.get(1)
                    ?: refPattern.find(rawValue)?.groupValues?.get(1)
                    ?: rawValue
            }
            else -> rawValue
        }

        if (key.isNotBlank() && value.isNotBlank()) {
            versions[key] = value
        }
    }

    if (versions.isEmpty()) return null
    return GradlePropertySource("gradle/libs.versions.toml [versions]", versions)
}
