package com.workflow.orchestrator.agent.tools.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.ide.LanguageProviderRegistry
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

class StructuralSearchTool(
    private val registry: LanguageProviderRegistry
) : AgentTool {
    override val name = "structural_search"
    override val description = "Search for code patterns using structural search syntax. " +
        "More powerful than regex — matches code structure semantically. " +
        "Use \$var\$ for template variables. " +
        "Example: 'System.out.println(\$arg\$)' finds all println calls. Java and Kotlin supported."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "pattern" to ParameterProperty(
                type = "string",
                description = "SSR pattern with \$var\$ template variables"
            ),
            "file_type" to ParameterProperty(
                type = "string",
                description = "Language: \"java\" or \"kotlin\" (default: \"java\")"
            ),
            "scope" to ParameterProperty(
                type = "string",
                description = "Search scope: \"project\" (default) or a module name"
            ),
            "max_results" to ParameterProperty(
                type = "integer",
                description = "Maximum number of results to return (default: 20)"
            )
        ),
        required = listOf("pattern")
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.REVIEWER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val pattern = params["pattern"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Error: 'pattern' parameter is required",
                "Error: missing pattern", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )

        if (pattern.isBlank()) {
            return ToolResult(
                "Error: 'pattern' must not be blank",
                "Error: blank pattern", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        val scopeName = params["scope"]?.jsonPrimitive?.content ?: "project"
        val maxResults = try {
            params["max_results"]?.jsonPrimitive?.int ?: 20
        } catch (_: Exception) { 20 }

        if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

        // Resolve provider (structural search is project-wide, use language ID)
        val provider = registry.forLanguageId("JAVA") ?: registry.forLanguageId("kotlin")
            ?: return ToolResult(
                "Code intelligence not available — no language provider registered",
                "Error: no provider",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val content = try {
            val results = ReadAction.nonBlocking<List<com.workflow.orchestrator.agent.ide.StructuralMatchInfo>?> {
                val scope = resolveScope(project, scopeName)
                provider.structuralSearch(project, pattern, scope)
            }.inSmartMode(project).executeSynchronously()

            if (results == null) {
                "Error: structural search failed — provider returned null"
            } else if (results.isEmpty()) {
                "No matches found for pattern: $pattern"
            } else {
                val shown = results.take(maxResults)
                val sb = StringBuilder()
                sb.appendLine("Found ${results.size} match${if (results.size != 1) "es" else ""} for pattern: $pattern")
                sb.appendLine()
                shown.forEachIndexed { index, match ->
                    sb.appendLine("${index + 1}. ${match.filePath}:${match.line}")
                    sb.appendLine("   ${match.matchedText.take(100)}")
                    if (index < shown.size - 1) sb.appendLine()
                }
                if (results.size > maxResults) {
                    sb.appendLine("\n... (${results.size - maxResults} more)")
                }
                sb.toString().trimEnd()
            }
        } catch (e: Exception) {
            return ToolResult(
                "Error: structural search failed — ${e.message}",
                "Error: search failed", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        val isError = content.startsWith("Error:")
        return ToolResult(
            content = content,
            summary = if (isError) content else "Structural search completed for: $pattern",
            tokenEstimate = TokenEstimator.estimate(content),
            isError = isError
        )
    }

    private fun resolveScope(project: Project, scopeName: String): GlobalSearchScope {
        if (scopeName == "project" || scopeName.isBlank()) {
            return GlobalSearchScope.projectScope(project)
        }
        // Try to find a module with this name
        val moduleManager = com.intellij.openapi.module.ModuleManager.getInstance(project)
        val module = moduleManager.findModuleByName(scopeName)
        return if (module != null) {
            GlobalSearchScope.moduleScope(module)
        } else {
            // Fall back to project scope
            GlobalSearchScope.projectScope(project)
        }
    }
}
