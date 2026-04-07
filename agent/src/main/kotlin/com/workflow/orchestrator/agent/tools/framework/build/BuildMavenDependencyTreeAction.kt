package com.workflow.orchestrator.agent.tools.framework.build

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.framework.MavenUtils
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val MAX_TREE_DEPTH = 5

internal fun executeMavenDependencyTree(params: JsonObject, project: Project): ToolResult {
    return try {
        val moduleFilter = params["module"]?.jsonPrimitive?.content
        val artifactFilter = params["artifact"]?.jsonPrimitive?.content?.lowercase()

        val manager = MavenUtils.getMavenManager(project)
            ?: return MavenUtils.noMavenError()

        val mavenProjects = MavenUtils.getMavenProjects(manager)
        if (mavenProjects.isEmpty()) {
            return ToolResult("No Maven projects found.", "No Maven projects", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        val targetProject = MavenUtils.findMavenProject(mavenProjects, manager, moduleFilter)
            ?: return ToolResult(
                "Module '${moduleFilter}' not found. Available: ${MavenUtils.getProjectNames(mavenProjects)}",
                "Module not found", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )

        val projectName = MavenUtils.getDisplayName(targetProject)

        val treeNodes = getDependencyTree(targetProject)

        val content = if (treeNodes != null) {
            buildTreeOutput(projectName, treeNodes, artifactFilter)
        } else {
            buildFallbackOutput(projectName, targetProject, artifactFilter)
        }

        ToolResult(
            content = content.trimEnd(),
            summary = "Dependency tree for $projectName",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    } catch (e: Exception) {
        ToolResult("Error building dependency tree: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}

private fun getDependencyTree(mavenProject: Any): List<Any>? {
    return try {
        @Suppress("UNCHECKED_CAST")
        mavenProject.javaClass.getMethod("getDependencyTree").invoke(mavenProject) as? List<Any>
    } catch (_: NoSuchMethodException) { null }
    catch (_: Exception) { null }
}

private fun buildTreeOutput(projectName: String, rootNodes: List<Any>, artifactFilter: String?): String {
    return buildString {
        val filterDesc = if (artifactFilter != null) " (filtered: $artifactFilter)" else ""
        appendLine("Dependency tree for $projectName$filterDesc:")
        appendLine()

        if (artifactFilter != null) {
            val matchingLines = mutableListOf<String>()
            for (node in rootNodes) {
                collectFilteredLines(node, artifactFilter, "", matchingLines, 0)
            }
            if (matchingLines.isEmpty()) {
                appendLine("No dependencies matching '$artifactFilter' found.")
            } else {
                matchingLines.forEach { appendLine(it) }
            }
        } else {
            for (node in rootNodes) {
                renderNode(node, "", this, 0)
            }
        }
    }
}

private fun renderNode(node: Any, indent: String, sb: StringBuilder, depth: Int) {
    val coords = getNodeCoordinates(node) ?: return
    sb.appendLine("$indent$coords")

    if (depth >= MAX_TREE_DEPTH) {
        val children = getChildNodes(node)
        if (children.isNotEmpty()) {
            sb.appendLine("$indent  [${children.size} more dependencies — max depth $MAX_TREE_DEPTH reached]")
        }
        return
    }

    val children = getChildNodes(node)
    for (child in children) {
        renderNode(child, "$indent  ", sb, depth + 1)
    }
}

private fun collectFilteredLines(
    node: Any, filter: String, indent: String, result: MutableList<String>, depth: Int
): Boolean {
    val coords = getNodeCoordinates(node) ?: return false
    val nodeMatches = coords.lowercase().contains(filter)

    val children = if (depth < MAX_TREE_DEPTH) getChildNodes(node) else emptyList()
    val childResults = mutableListOf<String>()
    var anyChildMatches = false
    for (child in children) {
        if (collectFilteredLines(child, filter, "$indent  ", childResults, depth + 1)) {
            anyChildMatches = true
        }
    }

    return if (nodeMatches || anyChildMatches) {
        result.add("$indent$coords")
        result.addAll(childResults)
        true
    } else {
        false
    }
}

private fun getNodeCoordinates(node: Any): String? {
    return try {
        val artifact = node.javaClass.getMethod("getArtifact").invoke(node) ?: return null
        val artifactClass = artifact.javaClass
        val groupId = artifactClass.getMethod("getGroupId").invoke(artifact) as? String ?: return null
        val artifactId = artifactClass.getMethod("getArtifactId").invoke(artifact) as? String ?: return null
        val version = artifactClass.getMethod("getVersion").invoke(artifact) as? String ?: ""
        val scope = try {
            artifactClass.getMethod("getScope").invoke(artifact) as? String ?: ""
        } catch (_: Exception) { "" }

        buildString {
            append("$groupId:$artifactId")
            if (version.isNotBlank()) append(":$version")
            if (scope.isNotBlank() && scope != "compile") append(" [$scope]")
        }
    } catch (_: Exception) { null }
}

private fun getChildNodes(node: Any): List<Any> {
    return try {
        @Suppress("UNCHECKED_CAST")
        node.javaClass.getMethod("getDependencies").invoke(node) as? List<Any> ?: emptyList()
    } catch (_: Exception) { emptyList() }
}

private fun buildFallbackOutput(projectName: String, mavenProject: Any, artifactFilter: String?): String {
    val dependencies = MavenUtils.getDependencies(mavenProject)
    val filtered = if (artifactFilter != null) {
        dependencies.filter { dep ->
            dep.groupId.lowercase().contains(artifactFilter) ||
                dep.artifactId.lowercase().contains(artifactFilter)
        }
    } else {
        dependencies
    }

    return buildString {
        appendLine("Dependency tree for $projectName:")
        appendLine()
        appendLine("Note: Full transitive tree unavailable (getDependencyTree() not supported by this Maven plugin version).")
        appendLine("Showing direct dependencies only:")
        appendLine()

        if (filtered.isEmpty()) {
            appendLine("No dependencies found${if (artifactFilter != null) " matching '$artifactFilter'" else ""}.")
        } else {
            for (dep in filtered.sortedBy { "${it.groupId}:${it.artifactId}" }) {
                val version = if (dep.version.isNotBlank()) ":${dep.version}" else ""
                val scopeSuffix = if (dep.scope.isNotBlank() && dep.scope != "compile") " [${dep.scope}]" else ""
                appendLine("${dep.groupId}:${dep.artifactId}$version$scopeSuffix")
            }
        }
    }
}
