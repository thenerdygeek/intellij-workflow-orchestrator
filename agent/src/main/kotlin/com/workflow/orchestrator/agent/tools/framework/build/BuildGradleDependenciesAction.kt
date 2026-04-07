package com.workflow.orchestrator.agent.tools.framework.build

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

private data class GradleDependencyEntry(
    val module: String,
    val configuration: String,
    val notation: String
)

internal suspend fun executeGradleDependencies(params: JsonObject, project: Project): ToolResult {
    val moduleFilter = params["module"]?.jsonPrimitive?.content?.trimStart(':')
    val configFilter = params["configuration"]?.jsonPrimitive?.content?.lowercase()
    val searchFilter = params["search"]?.jsonPrimitive?.content?.lowercase()
    val basePath = project.basePath
        ?: return ToolResult("Error: project base path not available", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

    return try {
        withContext(Dispatchers.IO) {
            val baseDir = File(basePath)
            val buildFiles = findGradleBuildFiles(baseDir, moduleFilter)

            if (buildFiles.isEmpty()) {
                return@withContext ToolResult(
                    "No Gradle build files found${if (moduleFilter != null) " for module '$moduleFilter'" else ""}.",
                    "No build files", 10
                )
            }

            val allDeps = mutableListOf<GradleDependencyEntry>()

            for ((moduleLabel, buildFile) in buildFiles) {
                val deps = parseGradleDependencies(buildFile, moduleLabel)
                allDeps.addAll(deps)
            }

            val filtered = allDeps.filter { dep ->
                val matchesConfig = configFilter == null || dep.configuration.lowercase() == configFilter
                val matchesSearch = searchFilter == null ||
                    dep.notation.lowercase().contains(searchFilter) ||
                    dep.configuration.lowercase().contains(searchFilter)
                matchesConfig && matchesSearch
            }

            if (filtered.isEmpty()) {
                val filterDesc = buildString {
                    if (configFilter != null) append(" configuration=$configFilter")
                    if (searchFilter != null) append(" search=$searchFilter")
                }
                return@withContext ToolResult("No dependencies found matching:$filterDesc", "No matches", 5)
            }

            val byModule = filtered.groupBy { it.module }

            val content = buildString {
                appendLine("Gradle dependencies (${filtered.size} total across ${byModule.size} module(s)):")
                appendLine()

                for ((mod, modDeps) in byModule.toSortedMap()) {
                    if (byModule.size > 1) appendLine("[$mod]")

                    val byConfig = modDeps
                        .groupBy { it.configuration }
                        .toSortedMap(compareBy { gradleConfigOrder(it) })

                    for ((config, deps) in byConfig) {
                        appendLine("$config (${deps.size}):")
                        for (dep in deps.sortedBy { it.notation }) {
                            appendLine("  ${dep.notation}")
                        }
                        appendLine()
                    }
                }
            }

            ToolResult(
                content = content.trimEnd(),
                summary = "${filtered.size} dependencies",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        }
    } catch (e: Exception) {
        ToolResult("Error reading Gradle dependencies: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}

private fun gradleConfigOrder(config: String): Int = when (config.lowercase()) {
    "api" -> 0; "implementation" -> 1; "compilonly" -> 2; "runtimeonly" -> 3
    "testimplementation" -> 4; "testcompileonly" -> 5; "testruntimeonly" -> 6
    "kapt" -> 7; "ksp" -> 8; "annotationprocessor" -> 9; "classpath" -> 10
    else -> 20
}

private fun parseGradleDependencies(buildFile: File, moduleLabel: String): List<GradleDependencyEntry> {
    val content = buildFile.readText()
    val entries = mutableListOf<GradleDependencyEntry>()

    val depsBlockPattern = Regex("""dependencies\s*\{([^}]*(?:\{[^}]*\}[^}]*)*)\}""", RegexOption.DOT_MATCHES_ALL)
    val depsBlocks = depsBlockPattern.findAll(content)

    for (block in depsBlocks) {
        val blockContent = block.groupValues[1]
        parseGradleDepLines(blockContent, moduleLabel, entries)
    }

    if (entries.isEmpty()) {
        parseGradleDepLines(content, moduleLabel, entries)
    }

    return entries
}

private fun parseGradleDepLines(text: String, moduleLabel: String, entries: MutableList<GradleDependencyEntry>) {
    val lines = text.lines()
    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.startsWith("//") || trimmed.isBlank()) continue
        val entry = parseSingleGradleDependencyLine(trimmed, moduleLabel) ?: continue
        entries.add(entry)
    }
}

private fun parseSingleGradleDependencyLine(line: String, moduleLabel: String): GradleDependencyEntry? {
    // Kotlin DSL: implementation("group:artifact:version")
    val kotlinStringDep = Regex("""^(\w+)\s*\(\s*"([^"]+)"\s*\)""").find(line)
    if (kotlinStringDep != null) {
        val config = kotlinStringDep.groupValues[1]
        val notation = kotlinStringDep.groupValues[2]
        if (isValidGradleConfig(config)) return GradleDependencyEntry(moduleLabel, config, notation)
    }

    // Kotlin DSL single-quote
    val kotlinSingleQuoteDep = Regex("""^(\w+)\s*\(\s*'([^']+)'\s*\)""").find(line)
    if (kotlinSingleQuoteDep != null) {
        val config = kotlinSingleQuoteDep.groupValues[1]
        val notation = kotlinSingleQuoteDep.groupValues[2]
        if (isValidGradleConfig(config)) return GradleDependencyEntry(moduleLabel, config, notation)
    }

    // Version catalog: implementation(libs.xxx.yyy)
    val versionCatalogDep = Regex("""^(\w+)\s*\(\s*(libs\.[a-zA-Z0-9._-]+)\s*\)""").find(line)
    if (versionCatalogDep != null) {
        val config = versionCatalogDep.groupValues[1]
        val notation = versionCatalogDep.groupValues[2]
        if (isValidGradleConfig(config)) return GradleDependencyEntry(moduleLabel, config, notation)
    }

    // Project dependency: implementation(project(":module"))
    val projectDep = Regex("""^(\w+)\s*\(\s*project\s*\(\s*["']([^"']+)["']\s*\)\s*\)""").find(line)
    if (projectDep != null) {
        val config = projectDep.groupValues[1]
        val mod = projectDep.groupValues[2]
        if (isValidGradleConfig(config)) return GradleDependencyEntry(moduleLabel, config, "project($mod)")
    }

    // Groovy DSL: implementation 'group:artifact:version'
    val groovyDep = Regex("""^(\w+)\s+['"]([^'"]+)['"]""").find(line)
    if (groovyDep != null) {
        val config = groovyDep.groupValues[1]
        val notation = groovyDep.groupValues[2]
        if (isValidGradleConfig(config)) return GradleDependencyEntry(moduleLabel, config, notation)
    }

    // Groovy DSL project dep
    val groovyProjectDep = Regex("""^(\w+)\s+project\s*\(\s*['"]([^'"]+)['"]\s*\)""").find(line)
    if (groovyProjectDep != null) {
        val config = groovyProjectDep.groupValues[1]
        val mod = groovyProjectDep.groupValues[2]
        if (isValidGradleConfig(config)) return GradleDependencyEntry(moduleLabel, config, "project($mod)")
    }

    // Map syntax: implementation(group: "x", name: "y", version: "z")
    val mapSyntaxDep = Regex("""^(\w+)\s*\(\s*group\s*[=:]\s*["']([^"']+)["']\s*,\s*name\s*[=:]\s*["']([^"']+)["'](?:\s*,\s*version\s*[=:]\s*["']([^"']+)["'])?\s*\)""").find(line)
    if (mapSyntaxDep != null) {
        val config = mapSyntaxDep.groupValues[1]
        val group = mapSyntaxDep.groupValues[2]
        val name = mapSyntaxDep.groupValues[3]
        val version = mapSyntaxDep.groupValues[4]
        if (isValidGradleConfig(config)) {
            val notation = if (version.isNotBlank()) "$group:$name:$version" else "$group:$name"
            return GradleDependencyEntry(moduleLabel, config, notation)
        }
    }

    return null
}

private fun isValidGradleConfig(config: String): Boolean {
    val validConfigs = setOf(
        "implementation", "api", "compileOnly", "runtimeOnly",
        "testImplementation", "testCompileOnly", "testRuntimeOnly",
        "annotationProcessor", "kapt", "ksp",
        "debugImplementation", "releaseImplementation",
        "androidTestImplementation", "classpath",
        "compile", "testCompile", "provided"
    )
    return config in validConfigs || config.endsWith("Implementation") || config.endsWith("Api")
}
