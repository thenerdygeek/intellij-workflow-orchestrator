package com.workflow.orchestrator.agent.tools.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class SpringEndpointsTool : AgentTool {
    override val name = "spring_endpoints"
    override val description = "List all HTTP endpoints in the Spring project: method (GET/POST/etc), path, " +
        "handler class and method. Scans @RestController and @Controller classes."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "filter" to ParameterProperty(
                type = "string",
                description = "Optional: filter by path pattern or HTTP method. E.g., '/api/users' or 'POST'."
            ),
            "include_params" to ParameterProperty(
                type = "boolean",
                description = "If true, show handler method parameters with @PathVariable/@RequestParam/@RequestBody annotations (default: false)"
            )
        ),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.ORCHESTRATOR)

    private val mappingAnnotations = mapOf(
        "org.springframework.web.bind.annotation.RequestMapping" to null,
        "org.springframework.web.bind.annotation.GetMapping" to "GET",
        "org.springframework.web.bind.annotation.PostMapping" to "POST",
        "org.springframework.web.bind.annotation.PutMapping" to "PUT",
        "org.springframework.web.bind.annotation.DeleteMapping" to "DELETE",
        "org.springframework.web.bind.annotation.PatchMapping" to "PATCH"
    )

    private val controllerAnnotations = listOf(
        "org.springframework.web.bind.annotation.RestController",
        "org.springframework.stereotype.Controller"
    )

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
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
        for (annotationFqn in controllerAnnotations) {
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
                for ((annotationFqn, httpMethod) in mappingAnnotations) {
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
                            methodSignature = formatMethodParams(method, includeParams)
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
        // Handle RequestMethod.GET, RequestMethod.POST etc.
        return text.replace("RequestMethod.", "").removeSurrounding("{", "}")
    }

    private fun formatMethodParams(method: PsiMethod, includeParams: Boolean = false): String {
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
}
