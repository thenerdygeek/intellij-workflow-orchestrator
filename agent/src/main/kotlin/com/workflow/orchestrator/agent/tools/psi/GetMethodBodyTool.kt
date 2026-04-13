package com.workflow.orchestrator.agent.tools.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.ide.LanguageProviderRegistry
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class GetMethodBodyTool(
    private val registry: LanguageProviderRegistry
) : AgentTool {
    override val name = "get_method_body"
    override val description =
        "Get the full source code of a specific method including annotations, signature, and body. " +
        "More targeted than read_file — no need to know line numbers."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "method" to ParameterProperty(type = "string", description = "Method name to retrieve"),
            "class_name" to ParameterProperty(type = "string", description = "Class containing the method"),
            "context_lines" to ParameterProperty(
                type = "integer",
                description = "Lines of context before/after the method (default: 0, max: 5)"
            )
        ),
        required = listOf("method", "class_name")
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.CODER, WorkerType.REVIEWER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

        val methodName = params["method"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Error: 'method' parameter required",
                "Error: missing method",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        val className = params["class_name"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Error: 'class_name' parameter required",
                "Error: missing class_name",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        val contextLines = (params["context_lines"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0).coerceIn(0, 5)

        // Resolve provider (symbol-based — not file-based, so use language ID)
        val provider = registry.forLanguageId("JAVA") ?: registry.forLanguageId("kotlin")
            ?: return ToolResult(
                "Code intelligence not available — no language provider registered",
                "Error: no provider",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val content = ReadAction.nonBlocking<String> {
            // Find the class via provider
            val psiClass = provider.findSymbol(project, className) as? com.intellij.psi.PsiClass
                ?: return@nonBlocking "Error: Class '$className' not found in project. " +
                        "Check the class name spelling or provide the fully qualified name."

            // Try direct (non-inherited) methods first
            var methods = psiClass.findMethodsByName(methodName, false).toList()

            // If not found in own class, check inherited
            val foundInherited = if (methods.isEmpty()) {
                val inheritedMethods = psiClass.findMethodsByName(methodName, true).toList()
                if (inheritedMethods.isNotEmpty()) {
                    methods = inheritedMethods
                    true
                } else {
                    false
                }
            } else {
                false
            }

            if (methods.isEmpty()) {
                val available = psiClass.methods.take(20).joinToString(", ") { it.name }
                val availableMsg = if (available.isNotEmpty()) "\nAvailable methods: $available" else ""
                return@nonBlocking "Error: Method '$methodName' not found in class '$className'.$availableMsg"
            }

            val inheritedHint = if (foundInherited) {
                val declaringClass = methods.first().containingClass?.qualifiedName ?: methods.first().containingClass?.name
                "\nNote: '$methodName' is inherited from '$declaringClass', not declared directly in '$className'.\n"
            } else {
                ""
            }

            val overloadsToShow = methods.take(3)
            val hiddenCount = methods.size - overloadsToShow.size

            val sb = StringBuilder()
            if (inheritedHint.isNotEmpty()) sb.append(inheritedHint)

            overloadsToShow.forEachIndexed { index, method ->
                if (overloadsToShow.size > 1) {
                    sb.appendLine("(overload #${index + 1})")
                }

                // Delegate body extraction to the provider
                val bodyResult = provider.getBody(method, contextLines)
                if (bodyResult == null) {
                    val filePath = method.containingFile?.virtualFile?.path
                        ?.let { PsiToolUtils.relativePath(project, it) } ?: "unknown"
                    sb.appendLine("// Source unavailable for ${method.name} in $filePath")
                    if (index < overloadsToShow.size - 1) sb.appendLine("---")
                    return@forEachIndexed
                }

                val filePath = method.containingFile?.virtualFile?.path
                    ?.let { PsiToolUtils.relativePath(project, it) } ?: "unknown"
                sb.appendLine("// $filePath")
                sb.appendLine(bodyResult.source)

                if (index < overloadsToShow.size - 1) {
                    sb.appendLine("---")
                }
            }

            if (hiddenCount > 0) {
                sb.appendLine("... ($hiddenCount more overload(s) not shown)")
            }

            sb.toString().trimEnd()
        }.inSmartMode(project).executeSynchronously()

        return ToolResult(
            content = content,
            summary = "Method body of '$className#$methodName'",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }
}
