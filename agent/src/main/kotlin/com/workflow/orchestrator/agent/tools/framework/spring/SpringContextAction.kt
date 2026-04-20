package com.workflow.orchestrator.agent.tools.framework.spring

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.framework.SpringTool
import com.workflow.orchestrator.agent.tools.psi.PsiToolUtils
import com.workflow.orchestrator.core.ai.TokenEstimator
import java.lang.reflect.InvocationTargetException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal suspend fun executeContext(params: JsonObject, project: Project): ToolResult {
    if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

    val filter = params["filter"]?.jsonPrimitive?.contentOrNull

    val content: String = try {
        ReadAction.nonBlocking<String> {
            collectBeans(project, filter)
        }.inSmartMode(project).executeSynchronously()
    } catch (e: NoClassDefFoundError) {
        SpringTool.SPRING_PLUGIN_MISSING_MSG
    } catch (e: ClassNotFoundException) {
        SpringTool.SPRING_PLUGIN_MISSING_MSG
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
        val springManagerClass = Class.forName("com.intellij.spring.SpringManager")
        val getInstance = springManagerClass.getMethod("getInstance", Project::class.java)
        val springManager = getInstance.invoke(null, project)

        // SpringManager.getAllModels takes a Module (not a Project) — iterate
        // every project module and union the resulting SpringModels.
        val getAllModels = springManagerClass.getMethod("getAllModels", Module::class.java)

        val modules = ModuleManager.getInstance(project).modules
        if (modules.isEmpty()) {
            return "No modules found in this project."
        }

        val allBeans = mutableListOf<Any>()
        for (module in modules) {
            val models = getAllModels.invoke(springManager, module) as? Collection<Any> ?: continue
            for (model in models) {
                val beans = model.javaClass.getMethod("getAllCommonBeans").invoke(model) as? Collection<Any> ?: continue
                allBeans.addAll(beans)
            }
        }

        if (allBeans.isEmpty()) {
            return "No Spring model found. Is this a Spring project with the Spring plugin enabled?"
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
        SpringTool.SPRING_PLUGIN_MISSING_MSG
    } catch (e: ClassNotFoundException) {
        SpringTool.SPRING_PLUGIN_MISSING_MSG
    } catch (e: NoSuchMethodException) {
        "Error: Spring plugin API has drifted — '${e.message}' not found on this IDE version. " +
            "The plugin may need an update."
    } catch (e: InvocationTargetException) {
        val cause = e.targetException ?: e
        "Error accessing Spring model: ${cause::class.simpleName}: ${cause.message}"
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

internal fun detectStereotype(psiClass: PsiClass?): String {
    if (psiClass == null) return "@Bean"
    return when {
        psiClass.getAnnotation("org.springframework.stereotype.Service") != null -> "@Service"
        psiClass.getAnnotation("org.springframework.stereotype.Repository") != null -> "@Repository"
        psiClass.getAnnotation("org.springframework.web.bind.annotation.RestController") != null -> "@RestController"
        psiClass.getAnnotation("org.springframework.stereotype.Controller") != null -> "@Controller"
        psiClass.getAnnotation("org.springframework.stereotype.Component") != null -> "@Component"
        psiClass.getAnnotation("org.springframework.context.annotation.Configuration") != null -> "@Configuration"
        else -> "@Bean"
    }
}
