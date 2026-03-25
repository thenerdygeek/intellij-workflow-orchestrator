package com.workflow.orchestrator.agent.tools.framework

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class MavenDependencyTreeTool : AgentTool {
    override val name = "maven_dependency_tree"
    override val description = "Show the full transitive dependency tree from Maven. Shows version conflicts, dependency path, and which version was selected."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "module" to ParameterProperty(
                type = "string",
                description = "Optional: module name to inspect. If omitted, uses the root/first Maven project."
            ),
            "artifact" to ParameterProperty(
                type = "string",
                description = "Optional: filter tree to show only paths containing this artifact (groupId, artifactId, or groupId:artifactId substring)."
            )
        ),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.ORCHESTRATOR, WorkerType.TOOLER)

    private val maxDepth = 5

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        return try {
            val moduleFilter = params["module"]?.jsonPrimitive?.content
            val artifactFilter = params["artifact"]?.jsonPrimitive?.content?.lowercase()

            val manager = MavenUtils.getMavenManager(project)
                ?: return MavenUtils.noMavenError()

            val mavenProjects = MavenUtils.getMavenProjects(manager)
            if (mavenProjects.isEmpty()) {
                return ToolResult(
                    "No Maven projects found.",
                    "No Maven projects",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
            }

            val targetProject = MavenUtils.findMavenProject(mavenProjects, manager, moduleFilter)
                ?: return ToolResult(
                    "Module '${moduleFilter}' not found. Available: ${MavenUtils.getProjectNames(mavenProjects)}",
                    "Module not found",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )

            val projectName = MavenUtils.getDisplayName(targetProject)

            // Attempt to get the full dependency tree via reflection
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
            ToolResult(
                "Error building dependency tree: ${e.message}",
                "Error",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }

    /**
     * Attempt to retrieve the full dependency tree via reflection.
     * Returns null if getDependencyTree() is not available on this Maven plugin version.
     */
    private fun getDependencyTree(mavenProject: Any): List<Any>? {
        return try {
            @Suppress("UNCHECKED_CAST")
            mavenProject.javaClass.getMethod("getDependencyTree").invoke(mavenProject) as? List<Any>
        } catch (_: NoSuchMethodException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Build the formatted tree output from MavenArtifactNode objects.
     */
    private fun buildTreeOutput(
        projectName: String,
        rootNodes: List<Any>,
        artifactFilter: String?
    ): String {
        return buildString {
            val filterDesc = if (artifactFilter != null) " (filtered: $artifactFilter)" else ""
            appendLine("Dependency tree for $projectName$filterDesc:")
            appendLine()

            if (artifactFilter != null) {
                // Only render paths that contain a matching node
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

    /**
     * Recursively render a MavenArtifactNode with indentation.
     */
    private fun renderNode(node: Any, indent: String, sb: StringBuilder, depth: Int) {
        val coords = getNodeCoordinates(node) ?: return
        sb.appendLine("$indent$coords")

        if (depth >= maxDepth) {
            val children = getChildNodes(node)
            if (children.isNotEmpty()) {
                sb.appendLine("$indent  [${children.size} more dependencies — max depth $maxDepth reached]")
            }
            return
        }

        val children = getChildNodes(node)
        for (child in children) {
            renderNode(child, "$indent  ", sb, depth + 1)
        }
    }

    /**
     * Collect lines for nodes that match the artifact filter (or whose subtree contains a match).
     */
    private fun collectFilteredLines(
        node: Any,
        filter: String,
        indent: String,
        result: MutableList<String>,
        depth: Int
    ): Boolean {
        val coords = getNodeCoordinates(node) ?: return false
        val nodeMatches = coords.lowercase().contains(filter)

        val children = if (depth < maxDepth) getChildNodes(node) else emptyList()
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

    /**
     * Format a single node's artifact as "groupId:artifactId:version [scope]".
     */
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
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Retrieve child dependency nodes for a given node via reflection.
     */
    private fun getChildNodes(node: Any): List<Any> {
        return try {
            @Suppress("UNCHECKED_CAST")
            node.javaClass.getMethod("getDependencies").invoke(node) as? List<Any> ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Fallback: getDependencyTree() is unavailable — list direct dependencies with a note.
     */
    private fun buildFallbackOutput(
        projectName: String,
        mavenProject: Any,
        artifactFilter: String?
    ): String {
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
}
