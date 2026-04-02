package com.workflow.orchestrator.agent.tools.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class TypeHierarchyTool : AgentTool {
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

        val content = ReadAction.nonBlocking<String> {
            val psiClass = PsiToolUtils.findClassAnywhere(project, className)
                ?: return@nonBlocking "No class '$className' found in project"

            val sb = StringBuilder()
            sb.appendLine("Type hierarchy for ${psiClass.qualifiedName ?: psiClass.name}:")

            // Supertypes (direct and transitive)
            sb.appendLine("\nSupertypes:")
            val visited = mutableSetOf<String>()
            collectSupertypes(project, psiClass, sb, indent = "  ", visited = visited)
            if (visited.isEmpty()) {
                sb.appendLine("  (none)")
            }

            // Subtypes (implementations)
            sb.appendLine("\nSubtypes/Implementations:")
            val scope = GlobalSearchScope.projectScope(project)
            val inheritors = ClassInheritorsSearch.search(psiClass, scope, true).findAll()
            if (inheritors.isEmpty()) {
                sb.appendLine("  (none)")
            } else {
                inheritors.take(30).forEach { inheritor ->
                    val file = PsiToolUtils.relativePath(project, inheritor.containingFile?.virtualFile?.path ?: "")
                    sb.appendLine("  ${inheritor.qualifiedName ?: inheritor.name}  ($file)")
                }
                if (inheritors.size > 30) {
                    sb.appendLine("  ... (${inheritors.size - 30} more)")
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

    private fun collectSupertypes(project: Project, psiClass: PsiClass, sb: StringBuilder, indent: String, visited: MutableSet<String>) {
        for (superType in psiClass.supers) {
            val qName = superType.qualifiedName ?: superType.name ?: continue
            // Skip java.lang.Object
            if (qName == "java.lang.Object") continue
            if (qName in visited) continue
            visited.add(qName)
            val file = PsiToolUtils.relativePath(project, superType.containingFile?.virtualFile?.path ?: "")
            sb.appendLine("$indent$qName  ($file)")
            collectSupertypes(project, superType, sb, "$indent  ", visited)
        }
    }
}
