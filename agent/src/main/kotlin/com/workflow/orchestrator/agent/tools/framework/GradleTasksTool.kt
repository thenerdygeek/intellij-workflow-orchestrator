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

class GradleTasksTool : AgentTool {
    override val name = "gradle_tasks"
    override val description = "List Gradle tasks defined in build.gradle[.kts] files"
    override val parameters = FunctionParameters(
        properties = mapOf(
            "module" to ParameterProperty(type = "string", description = "Optional: module name to inspect (e.g., ':core', 'jira'). If omitted, inspects all modules."),
            "search" to ParameterProperty(type = "string", description = "Optional: filter tasks by name or type substring.")
        ),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.ORCHESTRATOR, WorkerType.TOOLER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val moduleFilter = params["module"]?.jsonPrimitive?.content?.trimStart(':')
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

                val allTasks = mutableListOf<TaskEntry>()

                for ((moduleLabel, buildFile) in buildFiles) {
                    val tasks = parseTasks(buildFile, moduleLabel)
                    allTasks.addAll(tasks)
                }

                // Apply search filter
                val filtered = if (searchFilter != null) {
                    allTasks.filter { task ->
                        task.name.lowercase().contains(searchFilter) ||
                            task.type?.lowercase()?.contains(searchFilter) == true
                    }
                } else {
                    allTasks
                }

                if (filtered.isEmpty()) {
                    val filterDesc = if (searchFilter != null) " matching '$searchFilter'" else ""
                    return@withContext ToolResult("No custom tasks found$filterDesc.", "No tasks", 5)
                }

                val byModule = filtered.groupBy { it.module }

                val content = buildString {
                    appendLine("Gradle tasks (${filtered.size} total across ${byModule.size} module(s)):")
                    appendLine()

                    for ((mod, modTasks) in byModule.toSortedMap()) {
                        if (byModule.size > 1) appendLine("[$mod]")

                        for (task in modTasks.sortedBy { it.name }) {
                            val typeStr = if (task.type != null) " <${task.type}>" else ""
                            val descStr = if (task.description != null) " — ${task.description}" else ""
                            appendLine("  ${task.name}$typeStr$descStr")
                        }
                        appendLine()
                    }
                }

                ToolResult(
                    content = content.trimEnd(),
                    summary = "${filtered.size} tasks",
                    tokenEstimate = TokenEstimator.estimate(content)
                )
            }
        } catch (e: Exception) {
            ToolResult("Error reading Gradle tasks: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    private fun findBuildFiles(baseDir: File, moduleFilter: String?): List<Pair<String, File>> {
        val result = mutableListOf<Pair<String, File>>()

        if (moduleFilter != null) {
            val moduleDir = File(baseDir, moduleFilter)
            val buildFile = findBuildFile(moduleDir)
            if (buildFile != null) {
                result.add(moduleFilter to buildFile)
            } else {
                val rootBuild = findBuildFile(baseDir)
                if (rootBuild != null) result.add("root" to rootBuild)
            }
        } else {
            val rootBuild = findBuildFile(baseDir)
            if (rootBuild != null) result.add("root" to rootBuild)

            val modules = listModules(baseDir)
            for (mod in modules) {
                val modDir = File(baseDir, mod.trimStart(':').replace(':', '/'))
                val buildFile = findBuildFile(modDir)
                if (buildFile != null) result.add(mod.trimStart(':') to buildFile)
            }

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
        val pattern = Regex("""include\s*\(\s*["']([^"']+)["']\s*\)|include\s+['"]([^'"]+)['"]""")
        settingsFile.readLines().forEach { line ->
            pattern.findAll(line).forEach { match ->
                val mod = match.groupValues[1].ifEmpty { match.groupValues[2] }
                if (mod.isNotBlank()) modules.add(mod)
            }
        }
        return modules
    }

    private fun parseTasks(buildFile: File, moduleLabel: String): List<TaskEntry> {
        val content = buildFile.readText()
        val tasks = mutableListOf<TaskEntry>()

        // Kotlin DSL: tasks.register<Type>("name") { description = "..." }
        val registerTyped = Regex("""tasks\.register\s*<\s*(\w+)\s*>\s*\(\s*["'](\w+)["']""")
        registerTyped.findAll(content).forEach { match ->
            val type = match.groupValues[1]
            val name = match.groupValues[2]
            val description = extractTaskDescription(content, match.range.last)
            tasks.add(TaskEntry(moduleLabel, name, type, description))
        }

        // Kotlin DSL: tasks.register("name") { ... }
        val registerUntyped = Regex("""tasks\.register\s*\(\s*["'](\w+)["']\s*\)""")
        registerUntyped.findAll(content).forEach { match ->
            val name = match.groupValues[1]
            // Only add if not already captured with a type
            if (tasks.none { it.name == name && it.module == moduleLabel }) {
                val description = extractTaskDescription(content, match.range.last)
                tasks.add(TaskEntry(moduleLabel, name, null, description))
            }
        }

        // Kotlin DSL: val name by tasks.registering(Type::class) { ... }
        val registeringBy = Regex("""val\s+(\w+)\s+by\s+tasks\.registering\s*\(\s*(\w+)::class""")
        registeringBy.findAll(content).forEach { match ->
            val name = match.groupValues[1]
            val type = match.groupValues[2]
            if (tasks.none { it.name == name && it.module == moduleLabel }) {
                tasks.add(TaskEntry(moduleLabel, name, type, null))
            }
        }

        // Kotlin DSL: val name by tasks.registering { ... }
        val registeringByUntyped = Regex("""val\s+(\w+)\s+by\s+tasks\.registering\s*\{""")
        registeringByUntyped.findAll(content).forEach { match ->
            val name = match.groupValues[1]
            if (tasks.none { it.name == name && it.module == moduleLabel }) {
                tasks.add(TaskEntry(moduleLabel, name, null, null))
            }
        }

        // Groovy DSL: task("name") { ... } or task "name" { ... }
        val groovyTaskQuoted = Regex("""task\s*\(\s*["'](\w+)["']\s*\)""")
        groovyTaskQuoted.findAll(content).forEach { match ->
            val name = match.groupValues[1]
            if (tasks.none { it.name == name && it.module == moduleLabel }) {
                val description = extractTaskDescription(content, match.range.last)
                tasks.add(TaskEntry(moduleLabel, name, null, description))
            }
        }

        // Groovy DSL: task taskName { ... } or task taskName(type: SomeType) { ... }
        val groovyTaskSimple = Regex("""^task\s+(\w+)(?:\s*\(type:\s*(\w+)\))?""", RegexOption.MULTILINE)
        groovyTaskSimple.findAll(content).forEach { match ->
            val name = match.groupValues[1]
            val type = match.groupValues[2].ifBlank { null }
            if (tasks.none { it.name == name && it.module == moduleLabel }) {
                tasks.add(TaskEntry(moduleLabel, name, type, null))
            }
        }

        // Groovy DSL: task taskName(type: SomeType) — alternate
        val groovyTaskType = Regex("""task\s+(\w+)\s*\(\s*type\s*:\s*(\w+)\s*\)""")
        groovyTaskType.findAll(content).forEach { match ->
            val name = match.groupValues[1]
            val type = match.groupValues[2]
            if (tasks.none { it.name == name && it.module == moduleLabel }) {
                tasks.add(TaskEntry(moduleLabel, name, type, null))
            }
        }

        return tasks
    }

    /** Looks ahead in content past the given position to find a description assignment. */
    private fun extractTaskDescription(content: String, startPos: Int): String? {
        val window = content.substring(minOf(startPos, content.length), minOf(startPos + 500, content.length))
        val descPattern = Regex("""description\s*=\s*["']([^"']+)["']""")
        return descPattern.find(window)?.groupValues?.get(1)
    }

    private data class TaskEntry(
        val module: String,
        val name: String,
        val type: String?,
        val description: String?
    )
}
