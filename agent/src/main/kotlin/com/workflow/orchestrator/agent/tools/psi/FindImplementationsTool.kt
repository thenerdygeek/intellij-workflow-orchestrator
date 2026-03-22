package com.workflow.orchestrator.agent.tools.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.psi.PsiToolUtils
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class FindImplementationsTool : AgentTool {
    override val name = "find_implementations"
    override val description = "Find concrete implementations of an interface method or abstract method. Shows which classes implement a specific method with file locations."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "method" to ParameterProperty(type = "string", description = "Method name to find implementations of"),
            "class_name" to ParameterProperty(type = "string", description = "Optional: fully qualified or simple class/interface name containing the method. Required if method name is ambiguous.")
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
            val scope = GlobalSearchScope.projectScope(project)

            // Find the class containing the method
            val targetClass = if (className != null) {
                PsiToolUtils.findClass(project, className)
                    ?: return@nonBlocking "Class '$className' not found in project."
            } else {
                // Search all project classes for a method with this name
                // Try to find an interface/abstract method first
                val allScope = GlobalSearchScope.allScope(project)
                val facade = com.intellij.psi.JavaPsiFacade.getInstance(project)
                val shortClasses = facade.findClasses("*", scope) // won't work well
                // Use a broader search: find classes with this method from project scope
                null
            }

            if (targetClass == null && className == null) {
                // Without a class name, search all project classes for the method
                return@nonBlocking findMethodAcrossProject(project, methodName, scope)
            }

            val methods = targetClass!!.findMethodsByName(methodName, false)
            if (methods.isEmpty()) {
                return@nonBlocking "No method '$methodName' found in '${targetClass.qualifiedName ?: targetClass.name}'."
            }

            val results = StringBuilder()
            for (psiMethod in methods) {
                val overriders = OverridingMethodsSearch.search(psiMethod, scope, true).findAll()
                if (overriders.isEmpty() && methods.size == 1) {
                    return@nonBlocking "No implementations found for '${targetClass.name}.$methodName'. The method may not be abstract/interface, or has no overrides in project scope."
                }
                if (overriders.isEmpty()) continue

                results.appendLine("Implementations of ${targetClass.qualifiedName ?: targetClass.name}.$methodName(${psiMethod.parameterList.parameters.joinToString(", ") { it.type.presentableText }}):")
                results.appendLine()

                overriders.take(40).forEach { overrider ->
                    val containingClass = overrider.containingClass
                    val file = overrider.containingFile?.virtualFile?.path ?: ""
                    val relativePath = project.basePath?.let { base ->
                        if (file.startsWith(base)) file.removePrefix("$base/") else file
                    } ?: file
                    val doc = PsiDocumentManager.getInstance(project).getDocument(overrider.containingFile)
                    val line = doc?.getLineNumber(overrider.textOffset)?.plus(1) ?: 0
                    val signature = PsiToolUtils.formatMethodSignature(overrider)
                    results.appendLine("  ${containingClass?.name ?: "?"}: $signature")
                    results.appendLine("    at $relativePath:$line")
                }
                if (overriders.size > 40) {
                    results.appendLine("  ... and ${overriders.size - 40} more")
                }
                results.appendLine()
            }

            val output = results.toString().trim()
            if (output.isEmpty()) {
                "No implementations found for '$methodName' in '${targetClass.qualifiedName ?: targetClass.name}'."
            } else {
                output
            }
        }.inSmartMode(project).executeSynchronously()

        return ToolResult(
            content = content,
            summary = "Implementations of '$methodName'",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }

    private fun findMethodAcrossProject(project: Project, methodName: String, scope: GlobalSearchScope): String {
        // Search for methods by name across all classes in project scope
        val allClasses = com.intellij.psi.search.PsiShortNamesCache.getInstance(project)
            .getMethodsByName(methodName, scope)

        if (allClasses.isEmpty()) {
            return "No method '$methodName' found in project. Try providing 'class_name' to narrow the search."
        }

        // Find abstract/interface methods that have overrides
        val abstractMethods = allClasses.filter { method ->
            method.containingClass?.isInterface == true ||
                method.hasModifierProperty(com.intellij.psi.PsiModifier.ABSTRACT)
        }

        val methodsToSearch = abstractMethods.ifEmpty { allClasses.toList() }.take(5)

        val results = StringBuilder()
        for (psiMethod in methodsToSearch) {
            val overriders = OverridingMethodsSearch.search(psiMethod, scope, true).findAll()
            if (overriders.isEmpty()) continue

            val ownerClass = psiMethod.containingClass
            results.appendLine("Implementations of ${ownerClass?.qualifiedName ?: ownerClass?.name ?: "?"}.$methodName:")
            overriders.take(20).forEach { overrider ->
                val containingClass = overrider.containingClass
                val file = overrider.containingFile?.virtualFile?.path ?: ""
                val relativePath = project.basePath?.let { base ->
                    if (file.startsWith(base)) file.removePrefix("$base/") else file
                } ?: file
                val doc = PsiDocumentManager.getInstance(project).getDocument(overrider.containingFile)
                val line = doc?.getLineNumber(overrider.textOffset)?.plus(1) ?: 0
                results.appendLine("  ${containingClass?.name ?: "?"}.${overrider.name} at $relativePath:$line")
            }
            if (overriders.size > 20) {
                results.appendLine("  ... and ${overriders.size - 20} more")
            }
            results.appendLine()
        }

        val output = results.toString().trim()
        return if (output.isEmpty()) {
            "Found '$methodName' in ${allClasses.size} location(s) but no overriding implementations. Provide 'class_name' for more targeted search."
        } else {
            output
        }
    }
}
