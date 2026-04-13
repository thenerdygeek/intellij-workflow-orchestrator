package com.workflow.orchestrator.agent.tools.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.ide.LanguageProviderRegistry
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class TypeHierarchyTool(
    private val registry: LanguageProviderRegistry
) : AgentTool {
    override val name = "type_hierarchy"
    override val description = "Get the class hierarchy: supertypes (extends/implements) and subtypes (implementations/subclasses)."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "class_name" to ParameterProperty(type = "string", description = "Fully qualified or simple class name")
        ),
        required = listOf("class_name")
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.REVIEWER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

        val className = params["class_name"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'class_name' parameter required", "Error: missing class_name", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        // Resolve provider: try all registered providers until one finds the symbol
        val allProviders = registry.allProviders()
        if (allProviders.isEmpty()) {
            return ToolResult(
                "Code intelligence not available — no language provider registered",
                "Error: no provider",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        val content = ReadAction.nonBlocking<String> {
            // Try each provider until one finds the symbol
            val (provider, psiClass) = allProviders.firstNotNullOfOrNull { p ->
                p.findSymbol(project, className)?.let { p to it }
            } ?: return@nonBlocking "No class '$className' found in project"

            val result = provider.getTypeHierarchy(psiClass)
                ?: return@nonBlocking "No class '$className' found in project"

            val sb = StringBuilder()
            sb.appendLine("Type hierarchy for ${result.element}:")

            // Supertypes
            sb.appendLine("\nSupertypes:")
            if (result.supertypes.isEmpty()) {
                sb.appendLine("  (none)")
            } else {
                for (entry in result.supertypes) {
                    val file = entry.filePath ?: ""
                    sb.appendLine("  ${entry.qualifiedName}  ($file)")
                }
            }

            // Subtypes
            sb.appendLine("\nSubtypes/Implementations:")
            if (result.subtypes.isEmpty()) {
                sb.appendLine("  (none)")
            } else {
                result.subtypes.take(30).forEach { entry ->
                    val file = entry.filePath ?: ""
                    sb.appendLine("  ${entry.qualifiedName}  ($file)")
                }
                if (result.subtypes.size > 30) {
                    sb.appendLine("  ... (${result.subtypes.size - 30} more)")
                }
            }

            sb.toString()
        }.inSmartMode(project).executeSynchronously()

        return ToolResult(
            content = content,
            summary = "Type hierarchy for '$className'",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }
}
