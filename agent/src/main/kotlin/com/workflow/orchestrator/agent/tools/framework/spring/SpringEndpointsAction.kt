package com.workflow.orchestrator.agent.tools.framework.spring

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.psi.PsiToolUtils
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal suspend fun executeEndpoints(params: JsonObject, project: Project): ToolResult {
    if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

    val filter = params["filter"]?.jsonPrimitive?.contentOrNull
    val includeParams = params["include_params"]?.jsonPrimitive?.content?.toBoolean() ?: false

    val content = ReadAction.nonBlocking<String> {
        collectEndpoints(project, filter, includeParams)
    }.inSmartMode(project).executeSynchronously()

    return ToolResult(
        content = content,
        summary = "Spring endpoints listed (${content.lines().size} entries)",
        tokenEstimate = TokenEstimator.estimate(content)
    )
}

private fun collectEndpoints(project: Project, filter: String?, includeParams: Boolean = false): String {
    val scope = GlobalSearchScope.projectScope(project)
    val facade = JavaPsiFacade.getInstance(project)

    val controllerClasses = mutableSetOf<PsiClass>()
    for (annotationFqn in SPRING_CONTROLLER_ANNOTATIONS) {
        val annotationClass = facade.findClass(annotationFqn, GlobalSearchScope.allScope(project))
        if (annotationClass != null) {
            controllerClasses.addAll(
                AnnotatedElementsSearch.searchPsiClasses(annotationClass, scope).findAll()
            )
        }
    }

    if (controllerClasses.isEmpty()) {
        return "No @RestController or @Controller classes found in project."
    }

    val endpoints = mutableListOf<EndpointInfo>()
    for (cls in controllerClasses) {
        val classPrefix = extractMappingPath(
            cls.getAnnotation("org.springframework.web.bind.annotation.RequestMapping")
        )
        for (method in cls.methods) {
            for ((annotationFqn, httpMethod) in SPRING_ENDPOINT_MAPPING_ANNOTATIONS) {
                val annotation = method.getAnnotation(annotationFqn) ?: continue
                val methodPath = extractMappingPath(annotation)
                val path = classPrefix + methodPath
                val resolvedMethod = httpMethod
                    ?: extractRequestMethod(annotation)
                    ?: "GET"
                endpoints.add(
                    EndpointInfo(
                        httpMethod = resolvedMethod,
                        path = path.ifBlank { "/" },
                        className = cls.name ?: "(anonymous)",
                        methodName = method.name,
                        methodSignature = formatEndpointMethodParams(method, includeParams)
                    )
                )
            }
        }
    }

    val filtered = if (filter != null) {
        endpoints.filter { ep ->
            ep.path.contains(filter, ignoreCase = true) ||
                ep.httpMethod.contains(filter, ignoreCase = true) ||
                ep.className.contains(filter, ignoreCase = true)
        }
    } else {
        endpoints
    }

    if (filtered.isEmpty()) {
        return "No endpoints found${if (filter != null) " matching '$filter'" else ""}."
    }

    val sorted = filtered.sortedWith(compareBy({ it.path }, { it.httpMethod }))
    return sorted.take(100).joinToString("\n") { ep ->
        "${ep.httpMethod.padEnd(7)} ${ep.path} -> ${ep.className}.${ep.methodName}(${ep.methodSignature})"
    }.let { result ->
        if (sorted.size > 100) {
            "$result\n... (${sorted.size - 100} more endpoints not shown)"
        } else {
            result
        }
    }
}

private fun extractMappingPath(annotation: PsiAnnotation?): String {
    if (annotation == null) return ""
    val value = annotation.findAttributeValue("value")
        ?: annotation.findAttributeValue("path")
    return value?.text
        ?.removeSurrounding("\"")
        ?.removeSurrounding("{", "}")
        ?: ""
}

private fun extractRequestMethod(annotation: PsiAnnotation): String? {
    val method = annotation.findAttributeValue("method") ?: return null
    val text = method.text ?: return null
    return text.replace("RequestMethod.", "").removeSurrounding("{", "}")
}

private fun formatEndpointMethodParams(method: PsiMethod, includeParams: Boolean = false): String {
    return method.parameterList.parameters.joinToString(", ") { p ->
        if (includeParams) {
            val annotations = listOf("PathVariable", "RequestParam", "RequestBody", "RequestHeader")
                .mapNotNull { ann ->
                    p.getAnnotation("org.springframework.web.bind.annotation.$ann")?.let { "@$ann" }
                }
            val prefix = if (annotations.isNotEmpty()) "${annotations.joinToString(" ")} " else ""
            "$prefix${p.type.presentableText} ${p.name}"
        } else {
            p.type.presentableText
        }
    }
}

private data class EndpointInfo(
    val httpMethod: String,
    val path: String,
    val className: String,
    val methodName: String,
    val methodSignature: String
)
