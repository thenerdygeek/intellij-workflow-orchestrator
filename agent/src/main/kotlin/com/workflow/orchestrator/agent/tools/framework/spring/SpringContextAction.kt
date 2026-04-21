package com.workflow.orchestrator.agent.tools.framework.spring

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.framework.SpringTool
import com.workflow.orchestrator.agent.tools.psi.PsiToolUtils
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * `spring(action=context, filter?)` — lists every bean the Ultimate Spring
 * plugin's model knows about across all modules. Backed by
 * [SpringModelResolver.allBeans] so stereotype, XML, and `@Bean`-factory
 * beans are all visible through a single enumeration.
 */
internal suspend fun executeContext(params: JsonObject, project: Project): ToolResult {
    if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

    val filter = params["filter"]?.jsonPrimitive?.contentOrNull
    val profileFilter = params["profile"]?.jsonPrimitive?.contentOrNull

    val content: String = ReadAction.nonBlocking<String> {
        collectBeans(project, filter, profileFilter)
    }.inSmartMode(project).executeSynchronously()

    return ToolResult(
        content = content,
        summary = "Spring beans listed (${content.lines().size} entries)",
        tokenEstimate = TokenEstimator.estimate(content)
    )
}

private fun collectBeans(project: Project, filter: String?, profileFilter: String?): String {
    val allBeans = SpringModelResolver.allBeans(project)
    if (allBeans.isEmpty()) {
        return if (SpringModelResolver.getAllModels(project).isEmpty()) {
            SpringTool.SPRING_PLUGIN_MISSING_MSG
        } else {
            "No Spring model found. Is this a Spring project with the Spring plugin enabled?"
        }
    }

    val filteredByProfile = if (profileFilter != null) {
        allBeans.filter { bean ->
            val profile = SpringModelResolver.beanProfile(bean)
            profile != null && profile != "DefaultSpringProfile" &&
                profile.contains(profileFilter, ignoreCase = true)
        }
    } else {
        allBeans
    }

    val filtered = if (filter != null) {
        filteredByProfile.filter { bean ->
            val name = SpringModelResolver.beanName(bean).orEmpty()
            val cls = beanClass(bean)
            val type = (cls?.qualifiedName ?: cls?.name).orEmpty()
            name.contains(filter, ignoreCase = true) || type.contains(filter, ignoreCase = true)
        }
    } else {
        filteredByProfile
    }

    if (filtered.isEmpty()) {
        val filterPart = if (filter != null) " filter='$filter'" else ""
        val profilePart = if (profileFilter != null) " profile='$profileFilter'" else ""
        return "No beans found${if (filter != null || profileFilter != null) " matching$filterPart$profilePart." else "."}"
    }

    val headerParts = buildList {
        if (filter != null) add("filter='$filter'")
        if (profileFilter != null) add("profile='$profileFilter'")
    }
    val header = if (headerParts.isNotEmpty()) " (${filtered.size} entries, ${headerParts.joinToString(", ")})" else " (${filtered.size} entries)"

    val rendered = filtered.take(50).joinToString("\n") { bean -> renderBeanLine(bean) }
    val body = if (filtered.size > 50) {
        "$rendered\n... (${filtered.size - 50} more beans not shown)"
    } else {
        rendered
    }
    return "Spring beans listed$header\n$body"
}

private fun renderBeanLine(bean: Any): String {
    val resolvedClass = beanClass(bean)
    val name = SpringModelResolver.beanName(bean)
        ?: resolvedClass?.name?.replaceFirstChar { it.lowercaseChar() }
        ?: "(unnamed)"
    val type = resolvedClass?.qualifiedName ?: resolvedClass?.name ?: "(unknown type)"
    val scope = SpringModelResolver.beanScope(bean) ?: "singleton"
    val stereotype = stereotypeOf(bean, resolvedClass)
    return "$stereotype $name: $type (scope: $scope)"
}

private fun beanClass(bean: Any): PsiClass? {
    SpringModelResolver.beanClass(bean)?.let { return it }
    val defining = SpringModelResolver.beanDefiningElement(bean)
    if (defining is PsiClass) return defining
    if (defining is com.intellij.psi.PsiMethod) {
        return (defining.returnType as? com.intellij.psi.PsiClassType)?.resolve()
    }
    return null
}

private fun stereotypeOf(bean: Any, resolvedClass: PsiClass?): String {
    val defining = SpringModelResolver.beanDefiningElement(bean)
    if (defining is com.intellij.psi.PsiMethod) return "@Bean"
    val classToInspect = (defining as? PsiClass) ?: resolvedClass
    return detectStereotype(classToInspect)
}

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
