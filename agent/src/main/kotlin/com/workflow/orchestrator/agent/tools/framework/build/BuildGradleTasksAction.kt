package com.workflow.orchestrator.agent.tools.framework.build

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

private data class GradleTaskEntry(
    val module: String,
    val name: String,
    val type: String?,
    val description: String?
)

internal suspend fun executeGradleTasks(params: JsonObject, project: Project): ToolResult {
    val moduleFilter = params["module"]?.jsonPrimitive?.content?.trimStart(':')
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

            val allTasks = mutableListOf<GradleTaskEntry>()

            for ((moduleLabel, buildFile) in buildFiles) {
                val tasks = parseGradleTasks(buildFile, moduleLabel)
                allTasks.addAll(tasks)
            }

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

private fun parseGradleTasks(buildFile: File, moduleLabel: String): List<GradleTaskEntry> {
    val content = buildFile.readText()
    val tasks = mutableListOf<GradleTaskEntry>()

    // Kotlin DSL: tasks.register<Type>("name") { ... }
    val registerTyped = Regex("""tasks\.register\s*<\s*(\w+)\s*>\s*\(\s*["'](\w+)["']""")
    registerTyped.findAll(content).forEach { match ->
        val type = match.groupValues[1]
        val name = match.groupValues[2]
        val description = extractGradleTaskDescription(content, match.range.last)
        tasks.add(GradleTaskEntry(moduleLabel, name, type, description))
    }

    // Kotlin DSL: tasks.register("name") { ... }
    val registerUntyped = Regex("""tasks\.register\s*\(\s*["'](\w+)["']\s*\)""")
    registerUntyped.findAll(content).forEach { match ->
        val name = match.groupValues[1]
        if (tasks.none { it.name == name && it.module == moduleLabel }) {
            val description = extractGradleTaskDescription(content, match.range.last)
            tasks.add(GradleTaskEntry(moduleLabel, name, null, description))
        }
    }

    // Kotlin DSL: val name by tasks.registering(Type::class) { ... }
    val registeringBy = Regex("""val\s+(\w+)\s+by\s+tasks\.registering\s*\(\s*(\w+)::class""")
    registeringBy.findAll(content).forEach { match ->
        val name = match.groupValues[1]
        val type = match.groupValues[2]
        if (tasks.none { it.name == name && it.module == moduleLabel }) {
            tasks.add(GradleTaskEntry(moduleLabel, name, type, null))
        }
    }

    // Kotlin DSL: val name by tasks.registering { ... }
    val registeringByUntyped = Regex("""val\s+(\w+)\s+by\s+tasks\.registering\s*\{""")
    registeringByUntyped.findAll(content).forEach { match ->
        val name = match.groupValues[1]
        if (tasks.none { it.name == name && it.module == moduleLabel }) {
            tasks.add(GradleTaskEntry(moduleLabel, name, null, null))
        }
    }

    // Groovy DSL: task("name") { ... }
    val groovyTaskQuoted = Regex("""task\s*\(\s*["'](\w+)["']\s*\)""")
    groovyTaskQuoted.findAll(content).forEach { match ->
        val name = match.groupValues[1]
        if (tasks.none { it.name == name && it.module == moduleLabel }) {
            val description = extractGradleTaskDescription(content, match.range.last)
            tasks.add(GradleTaskEntry(moduleLabel, name, null, description))
        }
    }

    // Groovy DSL: task taskName { ... } or task taskName(type: SomeType) { ... }
    val groovyTaskSimple = Regex("""^task\s+(\w+)(?:\s*\(type:\s*(\w+)\))?""", RegexOption.MULTILINE)
    groovyTaskSimple.findAll(content).forEach { match ->
        val name = match.groupValues[1]
        val type = match.groupValues[2].ifBlank { null }
        if (tasks.none { it.name == name && it.module == moduleLabel }) {
            tasks.add(GradleTaskEntry(moduleLabel, name, type, null))
        }
    }

    // Groovy DSL: task taskName(type: SomeType) — alternate
    val groovyTaskType = Regex("""task\s+(\w+)\s*\(\s*type\s*:\s*(\w+)\s*\)""")
    groovyTaskType.findAll(content).forEach { match ->
        val name = match.groupValues[1]
        val type = match.groupValues[2]
        if (tasks.none { it.name == name && it.module == moduleLabel }) {
            tasks.add(GradleTaskEntry(moduleLabel, name, type, null))
        }
    }

    return tasks
}

private fun extractGradleTaskDescription(content: String, startPos: Int): String? {
    val window = content.substring(minOf(startPos, content.length), minOf(startPos + 500, content.length))
    val descPattern = Regex("""description\s*=\s*["']([^"']+)["']""")
    return descPattern.find(window)?.groupValues?.get(1)
}
