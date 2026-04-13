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

class CallHierarchyTool(
    private val registry: LanguageProviderRegistry
) : AgentTool {
    override val name = "call_hierarchy"
    override val description = "Get callers (who calls this method) and callees (what this method calls)."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "method" to ParameterProperty(type = "string", description = "Method name to analyze"),
            "class_name" to ParameterProperty(type = "string", description = "Optional class name containing the method, for disambiguation"),
            "depth" to ParameterProperty(type = "integer", description = "How many levels deep to trace callers (default: 1, max: 3). depth=2 shows callers of callers.")
        ),
        required = listOf("method")
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.REVIEWER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

        val methodName = params["method"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'method' parameter required", "Error: missing method", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val className = params["class_name"]?.jsonPrimitive?.content
        val maxDepth = (params["depth"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1).coerceIn(1, 3)

        // Resolve provider (symbol-based — not file-based, so use language ID)
        val provider = registry.forLanguageId("JAVA") ?: registry.forLanguageId("kotlin")
            ?: return ToolResult(
                "Code intelligence not available — no language provider registered",
                "Error: no provider",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val content = ReadAction.nonBlocking<String> {
            // Find the method via provider
            val symbolName = if (className != null) "$className#$methodName" else methodName
            val psiMethod = provider.findSymbol(project, symbolName)
                ?: return@nonBlocking "No method '$methodName' found" +
                        (if (className != null) " in class '$className'" else " in project")

            val qualifiedMethodName = (psiMethod as? com.intellij.psi.PsiMethod)?.let {
                "${it.containingClass?.name ?: ""}#${it.name}"
            } ?: methodName

            val sb = StringBuilder()
            sb.appendLine("Call hierarchy for $qualifiedMethodName:")

            // Callers: delegate to provider
            sb.appendLine("\nCallers (who calls this method):")
            val scope = GlobalSearchScope.projectScope(project)
            val callers = provider.findCallers(psiMethod, maxDepth, scope)
            if (callers.isEmpty()) {
                sb.appendLine("  (no callers found)")
            } else {
                callers.take(30).forEach { caller ->
                    val indent = "  " + "  ".repeat(caller.depth - 1)
                    sb.appendLine("$indent${caller.name}  (${caller.filePath}:${caller.line})")
                }
                if (callers.size > 30) {
                    sb.appendLine("  ... (${callers.size - 30} more)")
                }
            }

            // Callees: delegate to provider
            sb.appendLine("\nCallees (what this method calls):")
            val callees = provider.findCallees(psiMethod)
            if (callees.isEmpty()) {
                sb.appendLine("  (no callees found)")
            } else {
                callees.take(30).forEach { callee ->
                    sb.appendLine("  ${callee.name}  (${callee.filePath ?: ""}:${callee.line ?: 0})")
                }
                if (callees.size > 30) {
                    sb.appendLine("  ... (${callees.size - 30} more)")
                }
            }

            sb.toString()
        }.inSmartMode(project).executeSynchronously()

        return ToolResult(
            content = content,
            summary = "Call hierarchy for '$methodName'",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }
}
