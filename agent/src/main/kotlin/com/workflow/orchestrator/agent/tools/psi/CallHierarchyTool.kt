package com.workflow.orchestrator.agent.tools.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class CallHierarchyTool : AgentTool {
    override val name = "call_hierarchy"
    override val description = "Get callers (who calls this method) and callees (what this method calls)."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "method" to ParameterProperty(type = "string", description = "Method name to analyze"),
            "class_name" to ParameterProperty(type = "string", description = "Optional class name containing the method, for disambiguation")
        ),
        required = listOf("method")
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

        val methodName = params["method"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'method' parameter required", "Error: missing method", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val className = params["class_name"]?.jsonPrimitive?.content

        val content = ReadAction.nonBlocking<String> {
            // Find the method
            val psiMethod = findMethod(project, methodName, className)
                ?: return@nonBlocking "No method '$methodName' found" +
                        (if (className != null) " in class '$className'" else " in project")

            val sb = StringBuilder()
            val qualifiedMethodName = "${psiMethod.containingClass?.name ?: ""}#${psiMethod.name}"
            sb.appendLine("Call hierarchy for $qualifiedMethodName:")

            // Callers: who references this method
            sb.appendLine("\nCallers (who calls this method):")
            val scope = GlobalSearchScope.projectScope(project)
            val references = ReferencesSearch.search(psiMethod, scope).findAll()
            if (references.isEmpty()) {
                sb.appendLine("  (no callers found)")
            } else {
                references.take(30).forEach { ref ->
                    val element = ref.element
                    val containingMethod = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
                    val callerName = if (containingMethod != null) {
                        "${containingMethod.containingClass?.name ?: ""}#${containingMethod.name}"
                    } else {
                        "(top-level)"
                    }
                    val file = element.containingFile?.virtualFile?.path ?: ""
                    val document = PsiDocumentManager.getInstance(project)
                        .getDocument(element.containingFile)
                    val line = document?.getLineNumber(element.textOffset)?.plus(1) ?: 0
                    sb.appendLine("  $callerName  ($file:$line)")
                }
                if (references.size > 30) {
                    sb.appendLine("  ... (${references.size - 30} more)")
                }
            }

            // Callees: what methods does this method call
            sb.appendLine("\nCallees (what this method calls):")
            val callExpressions = PsiTreeUtil.findChildrenOfType(psiMethod, PsiMethodCallExpression::class.java)
            val callees = callExpressions.mapNotNull { call ->
                val resolved = call.resolveMethod() ?: return@mapNotNull null
                val calleeName = "${resolved.containingClass?.name ?: ""}#${resolved.name}"
                calleeName
            }.distinct()

            if (callees.isEmpty()) {
                sb.appendLine("  (no callees found)")
            } else {
                callees.take(30).forEach { callee ->
                    sb.appendLine("  $callee")
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

    private fun findMethod(project: Project, methodName: String, className: String?): PsiMethod? {
        if (className != null) {
            val psiClass = PsiToolUtils.findClass(project, className)
            return psiClass?.methods?.firstOrNull { it.name == methodName }
        }

        // Without class context, search all project classes for the method
        val scope = GlobalSearchScope.projectScope(project)
        val facade = com.intellij.psi.JavaPsiFacade.getInstance(project)
        val shortNameCache = com.intellij.psi.search.PsiShortNamesCache.getInstance(project)
        val methods = shortNameCache.getMethodsByName(methodName, scope)
        return methods.firstOrNull()
    }
}
