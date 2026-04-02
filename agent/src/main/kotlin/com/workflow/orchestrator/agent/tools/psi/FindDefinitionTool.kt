package com.workflow.orchestrator.agent.tools.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class FindDefinitionTool : AgentTool {
    override val name = "find_definition"
    override val description = "Find the declaration/definition location of a class, method, or field."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "symbol" to ParameterProperty(type = "string", description = "Symbol name to find (class FQN, method name, or field name)"),
            "class_name" to ParameterProperty(type = "string", description = "Optional: class name for disambiguation when multiple symbols share the same name")
        ),
        required = listOf("symbol")
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.REVIEWER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

        val symbol = params["symbol"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'symbol' parameter required", "Error: missing symbol", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val classNameHint = params["class_name"]?.jsonPrimitive?.content

        val content = ReadAction.nonBlocking<String> {
            // If class_name hint provided, search within that class first
            if (classNameHint != null) {
                val clazz = PsiToolUtils.findClassAnywhere(project, classNameHint)
                if (clazz != null) {
                    val method = clazz.methods.firstOrNull { it.name == symbol }
                    if (method != null) {
                        return@nonBlocking formatMethodDefinition(project, method)
                    }
                    val field = clazz.fields.firstOrNull { it.name == symbol }
                    if (field != null) {
                        val file = field.containingFile?.virtualFile?.path?.let { PsiToolUtils.relativePath(project, it) } ?: "unknown"
                        val document = field.containingFile?.let { PsiDocumentManager.getInstance(project).getDocument(it) }
                        val line = document?.getLineNumber(field.textOffset)?.plus(1) ?: 0
                        return@nonBlocking "Definition of '${field.containingClass?.qualifiedName ?: ""}#${field.name}':\n  File: $file\n  Line: $line\n  Type: ${field.type.presentableText}"
                    }
                }
            }

            // Try as class first
            val psiClass = PsiToolUtils.findClassAnywhere(project, symbol)
            if (psiClass != null) {
                val file = psiClass.containingFile?.virtualFile?.path?.let { PsiToolUtils.relativePath(project, it) } ?: "unknown"
                val document = psiClass.containingFile?.let {
                    PsiDocumentManager.getInstance(project).getDocument(it)
                }
                val line = document?.getLineNumber(psiClass.textOffset)?.plus(1) ?: 0
                val signature = PsiToolUtils.formatClassSkeleton(psiClass)
                return@nonBlocking "Definition of '${psiClass.qualifiedName ?: psiClass.name}':\n" +
                        "  File: $file\n" +
                        "  Line: $line\n\n$signature"
            }

            // Try as method: search for symbol containing '#' or '.' for method reference
            // e.g., "MyClass#myMethod" or just "myMethod"
            val parts = symbol.split('#', '.')
            if (parts.size == 2) {
                val className = parts[0]
                val methodName = parts[1]
                val clazz = PsiToolUtils.findClassAnywhere(project, className)
                val method = clazz?.methods?.firstOrNull { it.name == methodName }
                if (method != null) {
                    return@nonBlocking formatMethodDefinition(project, method)
                }
            }

            // Bare method/field name fallback via PsiShortNamesCache
            val scope = GlobalSearchScope.projectScope(project)
            val shortNameCache = com.intellij.psi.search.PsiShortNamesCache.getInstance(project)

            // Try as method
            val methods = shortNameCache.getMethodsByName(symbol, scope)
            if (methods.isNotEmpty()) {
                val method = methods.first()
                return@nonBlocking formatMethodDefinition(project, method) +
                    if (methods.size > 1) "\n\n(${methods.size - 1} other method(s) with same name — provide class_name to disambiguate)" else ""
            }

            // Try as field
            val fields = shortNameCache.getFieldsByName(symbol, scope)
            if (fields.isNotEmpty()) {
                val field = fields.first()
                val file = field.containingFile?.virtualFile?.path?.let { PsiToolUtils.relativePath(project, it) } ?: "unknown"
                val document = field.containingFile?.let { PsiDocumentManager.getInstance(project).getDocument(it) }
                val line = document?.getLineNumber(field.textOffset)?.plus(1) ?: 0
                return@nonBlocking "Definition of '${field.containingClass?.qualifiedName ?: ""}#${field.name}':\n  File: $file\n  Line: $line\n  Type: ${field.type.presentableText}"
            }

            "No definition found for '$symbol'"
        }.inSmartMode(project).executeSynchronously()

        return ToolResult(
            content = content,
            summary = "Definition of '$symbol'",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }

    private fun formatMethodDefinition(project: Project, method: PsiMethod): String {
        val file = method.containingFile?.virtualFile?.path?.let { PsiToolUtils.relativePath(project, it) } ?: "unknown"
        val document = method.containingFile?.let {
            PsiDocumentManager.getInstance(project).getDocument(it)
        }
        val line = document?.getLineNumber(method.textOffset)?.plus(1) ?: 0
        val signature = PsiToolUtils.formatMethodSignature(method)
        return "Definition of '${method.containingClass?.qualifiedName ?: ""}#${method.name}':\n" +
                "  File: $file\n" +
                "  Line: $line\n" +
                "  Signature: $signature"
    }
}
