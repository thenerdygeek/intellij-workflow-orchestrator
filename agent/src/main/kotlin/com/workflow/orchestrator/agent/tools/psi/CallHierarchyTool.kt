package com.workflow.orchestrator.agent.tools.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
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

        val content = ReadAction.nonBlocking<String> {
            // Find the method
            val psiMethod = findMethod(project, methodName, className)
                ?: return@nonBlocking "No method '$methodName' found" +
                        (if (className != null) " in class '$className'" else " in project")

            val sb = StringBuilder()
            val qualifiedMethodName = "${psiMethod.containingClass?.name ?: ""}#${psiMethod.name}"
            sb.appendLine("Call hierarchy for $qualifiedMethodName:")

            // Callers: who references this method (with depth support)
            sb.appendLine("\nCallers (who calls this method):")
            val scope = GlobalSearchScope.projectScope(project)
            val topLevelRefs = ReferencesSearch.search(psiMethod, scope).findAll()
            if (topLevelRefs.isEmpty()) {
                sb.appendLine("  (no callers found)")
            } else {
                fun collectCallers(method: PsiMethod, currentDepth: Int, indent: String) {
                    if (currentDepth > maxDepth) return
                    val refs = ReferencesSearch.search(method, scope).findAll()
                    refs.take(if (currentDepth == 1) 30 else 10).forEach { ref ->
                        val element = ref.element
                        val containingMethod = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
                        val callerName = if (containingMethod != null) {
                            "${containingMethod.containingClass?.name ?: ""}#${containingMethod.name}"
                        } else {
                            "(top-level)"
                        }
                        val file = element.containingFile?.virtualFile?.path
                            ?.let { PsiToolUtils.relativePath(project, it) } ?: ""
                        val document = PsiDocumentManager.getInstance(project)
                            .getDocument(element.containingFile)
                        val line = document?.getLineNumber(element.textOffset)?.plus(1) ?: 0
                        sb.appendLine("$indent$callerName  ($file:$line)")
                        if (containingMethod != null && currentDepth < maxDepth) {
                            collectCallers(containingMethod, currentDepth + 1, "$indent  ")
                        }
                    }
                    if (refs.size > (if (currentDepth == 1) 30 else 10)) {
                        sb.appendLine("$indent... (${refs.size - (if (currentDepth == 1) 30 else 10)} more)")
                    }
                }
                collectCallers(psiMethod, 1, "  ")
            }

            // Callees: what methods does this method call
            sb.appendLine("\nCallees (what this method calls):")
            val calleeEntries = mutableListOf<Triple<String, String, Int>>() // name, file, line

            // Java call expressions
            val callExpressions = PsiTreeUtil.findChildrenOfType(psiMethod, PsiMethodCallExpression::class.java)
            for (call in callExpressions) {
                val resolved = call.resolveMethod() ?: continue
                val calleeName = "${resolved.containingClass?.name ?: ""}#${resolved.name}"
                val calleeFile = resolved.containingFile?.virtualFile?.path
                    ?.let { PsiToolUtils.relativePath(project, it) } ?: ""
                val calleeLine = resolved.containingFile
                    ?.let { PsiDocumentManager.getInstance(project).getDocument(it) }
                    ?.getLineNumber(resolved.textOffset)?.plus(1) ?: 0
                calleeEntries.add(Triple(calleeName, calleeFile, calleeLine))
            }

            // Kotlin call expressions (reflection-based, graceful fallback)
            try {
                @Suppress("UNCHECKED_CAST")
                val ktCallClass = Class.forName("org.jetbrains.kotlin.psi.KtCallExpression") as Class<PsiElement>
                val ktCalls = PsiTreeUtil.findChildrenOfType(psiMethod, ktCallClass)
                for (call in ktCalls) {
                    val ref = (call as PsiElement).references.firstOrNull()
                    val resolved = ref?.resolve()
                    if (resolved is PsiMethod) {
                        val calleeName = "${resolved.containingClass?.name ?: ""}#${resolved.name}"
                        val calleeFile = resolved.containingFile?.virtualFile?.path
                            ?.let { PsiToolUtils.relativePath(project, it) } ?: ""
                        val calleeLine = resolved.containingFile
                            ?.let { PsiDocumentManager.getInstance(project).getDocument(it) }
                            ?.getLineNumber(resolved.textOffset)?.plus(1) ?: 0
                        calleeEntries.add(Triple(calleeName, calleeFile, calleeLine))
                    }
                }
            } catch (_: ClassNotFoundException) { /* Kotlin plugin not available */ }

            // Deduplicate by name
            val distinctCallees = calleeEntries.distinctBy { it.first }

            if (distinctCallees.isEmpty()) {
                sb.appendLine("  (no callees found)")
            } else {
                distinctCallees.take(30).forEach { (calleeName, calleeFile, calleeLine) ->
                    sb.appendLine("  $calleeName  ($calleeFile:$calleeLine)")
                }
                if (distinctCallees.size > 30) {
                    sb.appendLine("  ... (${distinctCallees.size - 30} more)")
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
            val psiClass = PsiToolUtils.findClassAnywhere(project, className)
            return psiClass?.methods?.firstOrNull { it.name == methodName }
        }

        // Without class context, search all project classes for the method
        val scope = GlobalSearchScope.projectScope(project)
        val shortNameCache = com.intellij.psi.search.PsiShortNamesCache.getInstance(project)
        val methods = shortNameCache.getMethodsByName(methodName, scope)
        return methods.firstOrNull()
    }
}
