package com.workflow.orchestrator.agent.tools.framework.spring

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiParameter
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.integration.ToolValidation
import com.workflow.orchestrator.agent.tools.psi.PsiToolUtils
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private val springBeanAnnotations = listOf(
    "org.springframework.stereotype.Service",
    "org.springframework.stereotype.Repository",
    "org.springframework.web.bind.annotation.RestController",
    "org.springframework.stereotype.Controller",
    "org.springframework.stereotype.Component",
    "org.springframework.context.annotation.Configuration"
)

internal suspend fun executeBeanGraph(params: JsonObject, project: Project): ToolResult {
    if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

    val beanName = params["bean_name"]?.jsonPrimitive?.contentOrNull
        ?: return ToolValidation.missingParam("bean_name")

    val content = ReadAction.nonBlocking<String> {
        buildDependencyGraph(project, beanName)
    }.inSmartMode(project).executeSynchronously()

    return ToolResult(
        content = content,
        summary = "Bean dependency graph for '$beanName'",
        tokenEstimate = TokenEstimator.estimate(content)
    )
}

private fun buildDependencyGraph(project: Project, beanName: String): String {
    val psiClass = PsiToolUtils.findClass(project, beanName)
        ?: return "No class '$beanName' found in project."

    val qualifiedName = psiClass.qualifiedName ?: psiClass.name ?: beanName
    val sb = StringBuilder()
    sb.appendLine("Dependency graph for: $qualifiedName")

    val stereotype = detectStereotype(psiClass)
    sb.appendLine("Stereotype: $stereotype")
    sb.appendLine()

    sb.appendLine("Dependencies (what this bean injects):")
    val dependencies = mutableListOf<DependencyInfo>()

    val primaryConstructor = psiClass.constructors.firstOrNull()
    if (primaryConstructor != null) {
        primaryConstructor.parameterList.parameters.forEach { param ->
            dependencies.add(extractDependencyFromParam(param))
        }
    }

    psiClass.fields.forEach { field ->
        if (field.getAnnotation("org.springframework.beans.factory.annotation.Autowired") != null) {
            dependencies.add(extractDependencyFromField(field))
        }
    }

    psiClass.methods.forEach { method ->
        if (method.getAnnotation("org.springframework.beans.factory.annotation.Autowired") != null &&
            method.name.startsWith("set") && method.parameterList.parametersCount > 0
        ) {
            method.parameterList.parameters.forEach { param ->
                dependencies.add(extractDependencyFromParam(param, "setter"))
            }
        }
    }

    if (dependencies.isEmpty()) {
        sb.appendLine("  (no dependencies)")
    } else {
        dependencies.forEach { dep ->
            val qualifier = if (dep.qualifier != null) " @Qualifier(\"${dep.qualifier}\")" else ""
            sb.appendLine("  ${dep.injectionType}: ${dep.typeName} ${dep.name}$qualifier")
        }
    }

    sb.appendLine()

    sb.appendLine("Consumers (what injects this bean):")
    val consumers = findConsumers(project, psiClass)
    if (consumers.isEmpty()) {
        sb.appendLine("  (no consumers found)")
    } else {
        consumers.forEach { consumer ->
            sb.appendLine("  ${consumer.className} (${consumer.injectionType}: ${consumer.fieldOrParamName})")
        }
    }

    return sb.toString()
}

private fun extractDependencyFromParam(param: PsiParameter, type: String = "constructor"): DependencyInfo {
    val qualifier = param.getAnnotation("org.springframework.beans.factory.annotation.Qualifier")
        ?.findAttributeValue("value")?.text?.removeSurrounding("\"")
    return DependencyInfo(
        name = param.name,
        typeName = param.type.presentableText,
        injectionType = type,
        qualifier = qualifier
    )
}

private fun extractDependencyFromField(field: PsiField): DependencyInfo {
    val qualifier = field.getAnnotation("org.springframework.beans.factory.annotation.Qualifier")
        ?.findAttributeValue("value")?.text?.removeSurrounding("\"")
    return DependencyInfo(
        name = field.name,
        typeName = field.type.presentableText,
        injectionType = "field",
        qualifier = qualifier
    )
}

private fun findConsumers(project: Project, targetClass: PsiClass): List<ConsumerInfo> {
    val scope = GlobalSearchScope.projectScope(project)
    val facade = JavaPsiFacade.getInstance(project)
    val consumers = mutableListOf<ConsumerInfo>()
    val targetTypeName = targetClass.qualifiedName ?: return consumers

    val allBeanClasses = mutableSetOf<PsiClass>()
    for (annotationFqn in springBeanAnnotations) {
        val annotationClass = facade.findClass(annotationFqn, GlobalSearchScope.allScope(project))
        if (annotationClass != null) {
            allBeanClasses.addAll(
                AnnotatedElementsSearch.searchPsiClasses(annotationClass, scope).findAll()
            )
        }
    }

    for (cls in allBeanClasses) {
        if (cls == targetClass) continue

        cls.constructors.firstOrNull()?.parameterList?.parameters?.forEach { param ->
            if (param.type.canonicalText == targetTypeName ||
                isAssignableFrom(targetClass, param.type.canonicalText, project)
            ) {
                consumers.add(
                    ConsumerInfo(
                        className = cls.qualifiedName ?: cls.name ?: "unknown",
                        injectionType = "constructor",
                        fieldOrParamName = param.name
                    )
                )
            }
        }

        cls.fields.filter {
            it.getAnnotation("org.springframework.beans.factory.annotation.Autowired") != null
        }.forEach { field ->
            if (field.type.canonicalText == targetTypeName ||
                isAssignableFrom(targetClass, field.type.canonicalText, project)
            ) {
                consumers.add(
                    ConsumerInfo(
                        className = cls.qualifiedName ?: cls.name ?: "unknown",
                        injectionType = "field",
                        fieldOrParamName = field.name
                    )
                )
            }
        }
    }

    return consumers.distinctBy { it.className }.take(30)
}

private fun isAssignableFrom(targetClass: PsiClass, typeFqn: String, project: Project): Boolean {
    val typeClass = JavaPsiFacade.getInstance(project)
        .findClass(typeFqn, GlobalSearchScope.allScope(project))
        ?: return false
    return targetClass.isInheritor(typeClass, true) || typeClass.isInheritor(targetClass, true)
}

private data class DependencyInfo(
    val name: String,
    val typeName: String,
    val injectionType: String,
    val qualifier: String?
)

private data class ConsumerInfo(
    val className: String,
    val injectionType: String,
    val fieldOrParamName: String
)
