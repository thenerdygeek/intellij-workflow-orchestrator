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

class SpringBootEndpointsTool : AgentTool {
    override val name = "spring_boot_endpoints"
    override val description = "Rich HTTP endpoint discovery for Spring Boot projects. Shows: full resolved URL " +
        "(with context-path), HTTP method, handler class.method, parameter annotations " +
        "(@PathVariable, @RequestParam, @RequestBody, @Valid), return type, consumes/produces media types."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "filter" to ParameterProperty(
                type = "string",
                description = "Optional: filter by path pattern, HTTP method, or class name. E.g., '/api/users', 'POST', or 'UserController'."
            ),
            "class_name" to ParameterProperty(
                type = "string",
                description = "Optional: filter results to a single controller class by simple or fully qualified name."
            )
        ),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.ORCHESTRATOR, WorkerType.CODER)

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

    private val paramAnnotations = listOf(
        "PathVariable" to "org.springframework.web.bind.annotation.PathVariable",
        "RequestParam" to "org.springframework.web.bind.annotation.RequestParam",
        "RequestBody" to "org.springframework.web.bind.annotation.RequestBody",
        "RequestHeader" to "org.springframework.web.bind.annotation.RequestHeader",
        "Valid" to "javax.validation.Valid",
        "Valid" to "jakarta.validation.Valid",
        "Validated" to "org.springframework.validation.annotation.Validated"
    )

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

        val filter = params["filter"]?.jsonPrimitive?.contentOrNull
        val classNameFilter = params["class_name"]?.jsonPrimitive?.contentOrNull

        val content = ReadAction.nonBlocking<String> {
            collectEndpoints(project, filter, classNameFilter)
        }.inSmartMode(project).executeSynchronously()

        return ToolResult(
            content = content,
            summary = "Spring Boot endpoints listed (${content.lines().size} lines)",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }

    private fun collectEndpoints(project: Project, filter: String?, classNameFilter: String?): String {
        val scope = GlobalSearchScope.projectScope(project)
        val facade = JavaPsiFacade.getInstance(project)

        // Resolve context-path from application.properties / application.yml
        val contextPath = resolveContextPath(project)

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

        // Apply class_name filter
        val filteredClasses = if (classNameFilter != null) {
            controllerClasses.filter { cls ->
                cls.name?.contains(classNameFilter, ignoreCase = true) == true ||
                    cls.qualifiedName?.contains(classNameFilter, ignoreCase = true) == true
            }
        } else {
            controllerClasses.toList()
        }

        if (filteredClasses.isEmpty()) {
            return "No controllers found matching class name '$classNameFilter'."
        }

        val endpoints = mutableListOf<RichEndpointInfo>()
        for (cls in filteredClasses) {
            val classPrefix = extractMappingPath(
                cls.getAnnotation("org.springframework.web.bind.annotation.RequestMapping")
            )
            for (method in cls.methods) {
                for ((annotationFqn, httpMethod) in mappingAnnotations) {
                    val annotation = method.getAnnotation(annotationFqn) ?: continue
                    val methodPath = extractMappingPath(annotation)
                    val fullPath = buildPath(contextPath, classPrefix, methodPath)
                    val resolvedMethod = httpMethod
                        ?: extractRequestMethod(annotation)
                        ?: "GET"
                    val consumes = extractMediaTypes(annotation, "consumes")
                    val produces = extractMediaTypes(annotation, "produces")
                    val returnType = method.returnType?.presentableText ?: "void"
                    val paramsSummary = formatRichParams(method)
                    val filePath = cls.containingFile?.virtualFile?.path
                        ?.let { PsiToolUtils.relativePath(project, it) }
                        ?: ""

                    endpoints.add(
                        RichEndpointInfo(
                            httpMethod = resolvedMethod,
                            fullUrl = fullPath,
                            className = cls.name ?: "(anonymous)",
                            methodName = method.name,
                            params = paramsSummary,
                            returnType = returnType,
                            consumes = consumes,
                            produces = produces,
                            filePath = filePath
                        )
                    )
                }
            }
        }

        // Apply general filter (path / method / class name)
        val filtered = if (filter != null) {
            endpoints.filter { ep ->
                ep.fullUrl.contains(filter, ignoreCase = true) ||
                    ep.httpMethod.contains(filter, ignoreCase = true) ||
                    ep.className.contains(filter, ignoreCase = true)
            }
        } else {
            endpoints
        }

        if (filtered.isEmpty()) {
            return "No endpoints found${if (filter != null) " matching '$filter'" else ""}."
        }

        val sorted = filtered.sortedWith(compareBy({ it.fullUrl }, { it.httpMethod }))

        val sb = StringBuilder()
        if (contextPath.isNotBlank()) {
            sb.appendLine("Context path: $contextPath")
        }
        sb.appendLine("Endpoints (${sorted.size}):")
        sb.appendLine()

        val displayed = sorted.take(100)
        for (ep in displayed) {
            sb.appendLine("${ep.httpMethod.padEnd(7)} ${ep.fullUrl}")
            sb.appendLine("  Handler: ${ep.className}.${ep.methodName}()")
            if (ep.params.isNotBlank()) {
                sb.appendLine("  Params:  ${ep.params}")
            }
            sb.appendLine("  Returns: ${ep.returnType}")
            if (ep.consumes.isNotBlank()) {
                sb.appendLine("  Consumes: ${ep.consumes}")
            }
            if (ep.produces.isNotBlank()) {
                sb.appendLine("  Produces: ${ep.produces}")
            }
            if (ep.filePath.isNotBlank()) {
                sb.appendLine("  File:    ${ep.filePath}")
            }
            sb.appendLine()
        }

        if (sorted.size > 100) {
            sb.appendLine("... (${sorted.size - 100} more endpoints not shown)")
        }

        return sb.toString().trimEnd()
    }

    private fun resolveContextPath(project: Project): String {
        return try {
            val basePath = project.basePath ?: return ""
            val resourcesRoot = "$basePath/src/main/resources"

            // Try application.properties first
            val propsFile = java.io.File("$resourcesRoot/application.properties")
            if (propsFile.exists()) {
                propsFile.readLines()
                    .firstOrNull { it.trimStart().startsWith("server.servlet.context-path") }
                    ?.substringAfter("=")
                    ?.trim()
                    ?.let { if (it.isNotBlank()) return it }
            }

            // Try application.yml
            val ymlFile = java.io.File("$resourcesRoot/application.yml")
            if (ymlFile.exists()) {
                val lines = ymlFile.readLines()
                var inServer = false
                var inServlet = false
                for (line in lines) {
                    val trimmed = line.trimStart()
                    when {
                        trimmed.startsWith("server:") -> { inServer = true; inServlet = false }
                        inServer && trimmed.startsWith("servlet:") -> inServlet = true
                        inServer && inServlet && trimmed.startsWith("context-path:") -> {
                            val value = trimmed.substringAfter("context-path:").trim().removeSurrounding("\"").removeSurrounding("'")
                            if (value.isNotBlank()) return value
                        }
                        !trimmed.startsWith(" ") && !trimmed.startsWith("\t") && trimmed.isNotBlank() -> {
                            if (!trimmed.startsWith("server:")) { inServer = false; inServlet = false }
                        }
                    }
                }
            }

            ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun buildPath(contextPath: String, classPrefix: String, methodPath: String): String {
        val segments = listOf(contextPath, classPrefix, methodPath)
            .map { it.trim('/') }
            .filter { it.isNotBlank() }
        return if (segments.isEmpty()) "/" else "/${segments.joinToString("/")}"
    }

    private fun extractMappingPath(annotation: PsiAnnotation?): String {
        if (annotation == null) return ""
        val value = annotation.findAttributeValue("value")
            ?: annotation.findAttributeValue("path")
        return value?.text
            ?.removeSurrounding("\"")
            ?.removeSurrounding("{", "}")
            ?.trim('/')
            ?: ""
    }

    private fun extractRequestMethod(annotation: PsiAnnotation): String? {
        val method = annotation.findAttributeValue("method") ?: return null
        val text = method.text ?: return null
        return text.replace("RequestMethod.", "").removeSurrounding("{", "}").trim()
    }

    private fun extractMediaTypes(annotation: PsiAnnotation, attribute: String): String {
        val value = annotation.findAttributeValue(attribute) ?: return ""
        val text = value.text ?: return ""
        return text.removeSurrounding("{", "}").removeSurrounding("\"").trim()
    }

    private fun formatRichParams(method: PsiMethod): String {
        val parts = method.parameterList.parameters.mapNotNull { p ->
            val annotations = buildList {
                // Check each known param annotation — use shortName to avoid duplicate "Valid" entries
                val checked = mutableSetOf<String>()
                for ((shortName, fqn) in paramAnnotations) {
                    if (checked.contains(shortName)) continue
                    if (p.getAnnotation(fqn) != null) {
                        add("@$shortName")
                        checked.add(shortName)
                    }
                }
            }
            val annotationPrefix = if (annotations.isNotEmpty()) "${annotations.joinToString(" ")} " else ""
            "$annotationPrefix${p.type.presentableText} ${p.name}"
        }
        return parts.joinToString(", ")
    }

    private data class RichEndpointInfo(
        val httpMethod: String,
        val fullUrl: String,
        val className: String,
        val methodName: String,
        val params: String,
        val returnType: String,
        val consumes: String,
        val produces: String,
        val filePath: String
    )
}
