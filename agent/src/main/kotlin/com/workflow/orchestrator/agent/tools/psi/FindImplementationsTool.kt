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
import kotlinx.serialization.json.jsonPrimitive

class FindImplementationsTool(
    private val registry: LanguageProviderRegistry
) : AgentTool {
    override val name = "find_implementations"
    override val description = "Find concrete implementations of an interface method or abstract method. Shows which classes implement a specific method with file locations."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "method" to ParameterProperty(type = "string", description = "Method name to find implementations of"),
            "class_name" to ParameterProperty(type = "string", description = "Optional: fully qualified or simple class/interface name containing the method. Required if method name is ambiguous.")
        ),
        required = listOf("method")
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.REVIEWER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

        val methodName = params["method"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'method' parameter required", "Error: missing method", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val className = params["class_name"]?.jsonPrimitive?.content

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
            val scope = GlobalSearchScope.projectScope(project)

            // Find the method element via provider — try each provider until one finds the symbol
            val symbolName = if (className != null) "$className#$methodName" else methodName
            val (provider, element) = allProviders.firstNotNullOfOrNull { p ->
                p.findSymbol(project, symbolName)?.let { p to it }
            } ?: return@nonBlocking if (className != null) {
                    "No method '$methodName' found in class '$className'."
                } else {
                    "No method '$methodName' found in project. Try providing 'class_name' to narrow the search."
                }

            val implementations = provider.findImplementations(element, scope)
            if (implementations.isEmpty()) {
                return@nonBlocking "No implementations found for '$methodName'. The method may not be abstract/interface, or has no overrides in project scope."
            }

            val sb = StringBuilder()
            sb.appendLine("Implementations of $methodName (${implementations.size} found):")
            sb.appendLine()

            implementations.take(40).forEach { impl ->
                sb.appendLine("  ${impl.name}: ${impl.signature}")
                sb.appendLine("    at ${impl.filePath}:${impl.line}")
            }
            if (implementations.size > 40) {
                sb.appendLine("  ... and ${implementations.size - 40} more")
            }

            sb.toString().trim()
        }.inSmartMode(project).executeSynchronously()

        return ToolResult(
            content = content,
            summary = "Implementations of '$methodName'",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }
}
