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

class GradleDependenciesTool : AgentTool {
    override val name = "gradle_dependencies"
    override val description = "List Gradle dependencies from build.gradle[.kts] files: group:artifact:version with configuration"
    override val parameters = FunctionParameters(
        properties = mapOf(
            "module" to ParameterProperty(type = "string", description = "Optional: module name to inspect (e.g., ':core', 'jira'). If omitted, inspects all modules."),
            "configuration" to ParameterProperty(type = "string", description = "Optional: filter by configuration (implementation, api, testImplementation, compileOnly, runtimeOnly, kapt, ksp, etc.)."),
            "search" to ParameterProperty(type = "string", description = "Optional: filter dependencies by group, artifact, or module name substring.")
        ),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.ORCHESTRATOR, WorkerType.TOOLER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val moduleFilter = params["module"]?.jsonPrimitive?.content?.trimStart(':')
        val configFilter = params["configuration"]?.jsonPrimitive?.content?.lowercase()
        val searchFilter = params["search"]?.jsonPrimitive?.content?.lowercase()
        val basePath = project.basePath
            ?: return ToolResult("Error: project base path not available", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        return try {
            withContext(Dispatchers.IO) {
                val baseDir = File(basePath)
                val buildFiles = findBuildFiles(baseDir, moduleFilter)

                if (buildFiles.isEmpty()) {
                    return@withContext ToolResult(
                        "No Gradle build files found${if (moduleFilter != null) " for module '$moduleFilter'" else ""}.",
                        "No build files", 10
                    )
                }

                val allDeps = mutableListOf<DependencyEntry>()

                for ((moduleLabel, buildFile) in buildFiles) {
                    val deps = parseDependencies(buildFile, moduleLabel)
                    allDeps.addAll(deps)
                }

                // Apply filters
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

                // Group by module then by configuration
                val byModule = filtered.groupBy { it.module }

                val content = buildString {
                    appendLine("Gradle dependencies (${filtered.size} total across ${byModule.size} module(s)):")
                    appendLine()

                    for ((mod, modDeps) in byModule.toSortedMap()) {
                        if (byModule.size > 1) appendLine("[$mod]")

                        val byConfig = modDeps
                            .groupBy { it.configuration }
                            .toSortedMap(compareBy { configOrder(it) })

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

    private fun findBuildFiles(baseDir: File, moduleFilter: String?): List<Pair<String, File>> {
        val result = mutableListOf<Pair<String, File>>()

        if (moduleFilter != null) {
            // Look for a specific module directory
            val moduleDir = File(baseDir, moduleFilter)
            val buildFile = findBuildFile(moduleDir)
            if (buildFile != null) {
                result.add(moduleFilter to buildFile)
            } else {
                // Also check root in case the "module" is root
                val rootBuild = findBuildFile(baseDir)
                if (rootBuild != null) result.add("root" to rootBuild)
            }
        } else {
            // Root build file
            val rootBuild = findBuildFile(baseDir)
            if (rootBuild != null) result.add("root" to rootBuild)

            // Submodules from settings.gradle[.kts]
            val modules = listModules(baseDir)
            for (mod in modules) {
                val modDir = File(baseDir, mod.trimStart(':').replace(':', '/'))
                val buildFile = findBuildFile(modDir)
                if (buildFile != null) result.add(mod.trimStart(':') to buildFile)
            }

            // Fallback: scan first-level subdirectories if no settings file
            if (modules.isEmpty()) {
                baseDir.listFiles()
                    ?.filter { it.isDirectory && !it.name.startsWith(".") && it.name != "build" && it.name != "gradle" }
                    ?.forEach { subDir ->
                        val buildFile = findBuildFile(subDir)
                        if (buildFile != null) result.add(subDir.name to buildFile)
                    }
            }
        }

        return result
    }

    private fun findBuildFile(dir: File): File? {
        val kts = File(dir, "build.gradle.kts")
        if (kts.isFile) return kts
        val groovy = File(dir, "build.gradle")
        if (groovy.isFile) return groovy
        return null
    }

    private fun listModules(baseDir: File): List<String> {
        val settingsKts = File(baseDir, "settings.gradle.kts")
        val settingsGroovy = File(baseDir, "settings.gradle")
        val settingsFile = when {
            settingsKts.isFile -> settingsKts
            settingsGroovy.isFile -> settingsGroovy
            else -> return emptyList()
        }

        val modules = mutableListOf<String>()
        // Match: include(":module") or include ':module'
        val pattern = Regex("""include\s*\(\s*["']([^"']+)["']\s*\)|include\s+['"]([^'"]+)['"]""")
        settingsFile.readLines().forEach { line ->
            pattern.findAll(line).forEach { match ->
                val mod = match.groupValues[1].ifEmpty { match.groupValues[2] }
                if (mod.isNotBlank()) modules.add(mod)
            }
        }
        return modules
    }

    private fun parseDependencies(buildFile: File, moduleLabel: String): List<DependencyEntry> {
        val content = buildFile.readText()
        val entries = mutableListOf<DependencyEntry>()

        // Find the dependencies { } block(s) — may span multiple lines
        val depsBlockPattern = Regex("""dependencies\s*\{([^}]*(?:\{[^}]*\}[^}]*)*)\}""", RegexOption.DOT_MATCHES_ALL)
        val depsBlocks = depsBlockPattern.findAll(content)

        for (block in depsBlocks) {
            val blockContent = block.groupValues[1]
            parseDepLines(blockContent, moduleLabel, entries)
        }

        // If no block found (simple file), scan the whole file
        if (entries.isEmpty()) {
            parseDepLines(content, moduleLabel, entries)
        }

        return entries
    }

    private fun parseDepLines(text: String, moduleLabel: String, entries: MutableList<DependencyEntry>) {
        val lines = text.lines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("//") || trimmed.isBlank()) continue

            val entry = parseSingleDependencyLine(trimmed, moduleLabel) ?: continue
            entries.add(entry)
        }
    }

    private fun parseSingleDependencyLine(line: String, moduleLabel: String): DependencyEntry? {
        // Kotlin DSL: implementation("group:artifact:version")
        val kotlinStringDep = Regex("""^(\w+)\s*\(\s*"([^"]+)"\s*\)""").find(line)
        if (kotlinStringDep != null) {
            val config = kotlinStringDep.groupValues[1]
            val notation = kotlinStringDep.groupValues[2]
            if (isValidConfig(config)) {
                return DependencyEntry(moduleLabel, config, notation)
            }
        }

        // Kotlin DSL single-quote: implementation('group:artifact:version')
        val kotlinSingleQuoteDep = Regex("""^(\w+)\s*\(\s*'([^']+)'\s*\)""").find(line)
        if (kotlinSingleQuoteDep != null) {
            val config = kotlinSingleQuoteDep.groupValues[1]
            val notation = kotlinSingleQuoteDep.groupValues[2]
            if (isValidConfig(config)) {
                return DependencyEntry(moduleLabel, config, notation)
            }
        }

        // Version catalog: implementation(libs.xxx.yyy)
        val versionCatalogDep = Regex("""^(\w+)\s*\(\s*(libs\.[a-zA-Z0-9._-]+)\s*\)""").find(line)
        if (versionCatalogDep != null) {
            val config = versionCatalogDep.groupValues[1]
            val notation = versionCatalogDep.groupValues[2]
            if (isValidConfig(config)) {
                return DependencyEntry(moduleLabel, config, notation)
            }
        }

        // Project dependency: implementation(project(":module"))
        val projectDep = Regex("""^(\w+)\s*\(\s*project\s*\(\s*["']([^"']+)["']\s*\)\s*\)""").find(line)
        if (projectDep != null) {
            val config = projectDep.groupValues[1]
            val mod = projectDep.groupValues[2]
            if (isValidConfig(config)) {
                return DependencyEntry(moduleLabel, config, "project($mod)")
            }
        }

        // Groovy DSL: implementation 'group:artifact:version'
        val groovyDep = Regex("""^(\w+)\s+['"]([^'"]+)['"]""").find(line)
        if (groovyDep != null) {
            val config = groovyDep.groupValues[1]
            val notation = groovyDep.groupValues[2]
            if (isValidConfig(config)) {
                return DependencyEntry(moduleLabel, config, notation)
            }
        }

        // Groovy DSL project dep: implementation project(':module')
        val groovyProjectDep = Regex("""^(\w+)\s+project\s*\(\s*['"]([^'"]+)['"]\s*\)""").find(line)
        if (groovyProjectDep != null) {
            val config = groovyProjectDep.groupValues[1]
            val mod = groovyProjectDep.groupValues[2]
            if (isValidConfig(config)) {
                return DependencyEntry(moduleLabel, config, "project($mod)")
            }
        }

        // group/name/version map syntax: implementation(group: "x", name: "y", version: "z")
        val mapSyntaxDep = Regex("""^(\w+)\s*\(\s*group\s*[=:]\s*["']([^"']+)["']\s*,\s*name\s*[=:]\s*["']([^"']+)["'](?:\s*,\s*version\s*[=:]\s*["']([^"']+)["'])?\s*\)""").find(line)
        if (mapSyntaxDep != null) {
            val config = mapSyntaxDep.groupValues[1]
            val group = mapSyntaxDep.groupValues[2]
            val name = mapSyntaxDep.groupValues[3]
            val version = mapSyntaxDep.groupValues[4]
            if (isValidConfig(config)) {
                val notation = if (version.isNotBlank()) "$group:$name:$version" else "$group:$name"
                return DependencyEntry(moduleLabel, config, notation)
            }
        }

        return null
    }

    private fun isValidConfig(config: String): Boolean {
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

    private fun configOrder(config: String): Int = when (config.lowercase()) {
        "api" -> 0
        "implementation" -> 1
        "compilonly" -> 2
        "runtimeonly" -> 3
        "testimplementation" -> 4
        "testcompileonly" -> 5
        "testruntimeonly" -> 6
        "kapt" -> 7
        "ksp" -> 8
        "annotationprocessor" -> 9
        "classpath" -> 10
        else -> 20
    }

    private data class DependencyEntry(
        val module: String,
        val configuration: String,
        val notation: String
    )
}
