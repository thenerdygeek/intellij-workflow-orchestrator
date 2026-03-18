package com.workflow.orchestrator.agent.tools.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class SpringContextTool : AgentTool {
    override val name = "spring_context"
    override val description = "List all Spring beans in the project: names, types, scopes (@Singleton/@Prototype), " +
        "stereotypes (@Service/@Repository/@Controller/@Component). Requires Spring plugin."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "filter" to ParameterProperty(
                type = "string",
                description = "Optional: filter by bean type or name pattern. E.g., 'Service' to list only services."
            )
        ),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

        val filter = params["filter"]?.jsonPrimitive?.contentOrNull

        val content: String = try {
            ReadAction.nonBlocking<String> {
                collectBeans(project, filter)
            }.inSmartMode(project).executeSynchronously()
        } catch (e: NoClassDefFoundError) {
            SPRING_PLUGIN_MISSING_MSG
        } catch (e: ClassNotFoundException) {
            SPRING_PLUGIN_MISSING_MSG
        }

        return ToolResult(
            content = content,
            summary = "Spring beans listed (${content.lines().size} entries)",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun collectBeans(project: Project, filter: String?): String {
        return try {
            // Use reflection to access Spring plugin APIs (optional dependency — may not be on classpath)
            val springManagerClass = Class.forName("com.intellij.spring.SpringManager")
            val getInstance = springManagerClass.getMethod("getInstance", Project::class.java)
            val springManager = getInstance.invoke(null, project)
            val getAllModels = springManagerClass.getMethod("getAllModels", Project::class.java)
            val models = getAllModels.invoke(springManager, project) as Collection<Any>
            if (models.isEmpty()) return "No Spring model found. Is this a Spring project with the Spring plugin enabled?"

            val allBeans = mutableListOf<Any>()
            for (model in models) {
                val beans = model.javaClass.getMethod("getAllCommonBeans").invoke(model) as Collection<Any>
                allBeans.addAll(beans)
            }

            val filtered = if (filter != null) {
                allBeans.filter { bean ->
                    val name = getBeanStringProperty(bean, "getBeanName") ?: ""
                    val type = getBeanClassQualifiedName(bean) ?: ""
                    name.contains(filter, ignoreCase = true) || type.contains(filter, ignoreCase = true)
                }
            } else {
                allBeans
            }

            if (filtered.isEmpty()) {
                return "No beans found${if (filter != null) " matching '$filter'" else ""}."
            }

            filtered.take(50).joinToString("\n") { bean ->
                val name = getBeanStringProperty(bean, "getBeanName") ?: "(unnamed)"
                val type = getBeanClassQualifiedName(bean) ?: "(unknown type)"
                val scope = getBeanStringProperty(bean, "getBeanScope") ?: "singleton"
                val beanClass = getBeanPsiClass(bean)
                val stereotype = detectStereotype(beanClass)
                "$stereotype $name: $type (scope: $scope)"
            }.let { result ->
                if (filtered.size > 50) {
                    "$result\n... (${filtered.size - 50} more beans not shown)"
                } else {
                    result
                }
            }
        } catch (e: NoClassDefFoundError) {
            SPRING_PLUGIN_MISSING_MSG
        } catch (e: ClassNotFoundException) {
            SPRING_PLUGIN_MISSING_MSG
        } catch (e: Exception) {
            "Error accessing Spring model: ${e.message}. Make sure the Spring plugin is available."
        }
    }

    private fun getBeanStringProperty(bean: Any, methodName: String): String? {
        return try { bean.javaClass.getMethod(methodName).invoke(bean) as? String } catch (_: Exception) { null }
    }

    private fun getBeanPsiClass(bean: Any): PsiClass? {
        return try { bean.javaClass.getMethod("getBeanClass").invoke(bean) as? PsiClass } catch (_: Exception) { null }
    }

    private fun getBeanClassQualifiedName(bean: Any): String? = getBeanPsiClass(bean)?.qualifiedName

    private fun detectStereotype(psiClass: PsiClass?): String {
        if (psiClass == null) return "@Bean"
        return when {
            psiClass.hasAnnotation("org.springframework.stereotype.Service") -> "@Service"
            psiClass.hasAnnotation("org.springframework.stereotype.Repository") -> "@Repository"
            psiClass.hasAnnotation("org.springframework.web.bind.annotation.RestController") -> "@RestController"
            psiClass.hasAnnotation("org.springframework.stereotype.Controller") -> "@Controller"
            psiClass.hasAnnotation("org.springframework.stereotype.Component") -> "@Component"
            psiClass.hasAnnotation("org.springframework.context.annotation.Configuration") -> "@Configuration"
            else -> "@Bean"
        }
    }

    private fun PsiClass.hasAnnotation(fqn: String): Boolean = getAnnotation(fqn) != null

    companion object {
        internal const val SPRING_PLUGIN_MISSING_MSG =
            "Error: Spring plugin is not available. Install 'Spring' plugin in IntelliJ Ultimate to use this tool."
    }
}
