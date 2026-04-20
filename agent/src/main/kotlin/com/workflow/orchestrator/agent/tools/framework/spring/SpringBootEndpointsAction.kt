package com.workflow.orchestrator.agent.tools.framework.spring

import com.intellij.codeInsight.AnnotationUtil
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.psi.PsiToolUtils
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

private val bootParamAnnotations = listOf(
    "PathVariable" to "org.springframework.web.bind.annotation.PathVariable",
    "RequestParam" to "org.springframework.web.bind.annotation.RequestParam",
    "RequestBody" to "org.springframework.web.bind.annotation.RequestBody",
    "RequestHeader" to "org.springframework.web.bind.annotation.RequestHeader",
    "Valid" to "javax.validation.Valid",
    "Valid" to "jakarta.validation.Valid",
    "Validated" to "org.springframework.validation.annotation.Validated"
)

internal suspend fun executeBootEndpoints(params: JsonObject, project: Project): ToolResult {
    if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

    val filter = params["filter"]?.jsonPrimitive?.contentOrNull
    val classNameFilter = params["class_name"]?.jsonPrimitive?.contentOrNull

    val content = ReadAction.nonBlocking<String> {
        collectBootEndpoints(project, filter, classNameFilter)
    }.inSmartMode(project).executeSynchronously()

    return ToolResult(
        content = content,
        summary = "Spring Boot endpoints listed (${content.lines().size} lines)",
        tokenEstimate = TokenEstimator.estimate(content)
    )
}

private fun collectBootEndpoints(project: Project, filter: String?, classNameFilter: String?): String {
    val scope = GlobalSearchScope.projectScope(project)
    val facade = JavaPsiFacade.getInstance(project)

    val contextPath = resolveContextPath(project)

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
        val classPrefix = extractBootMappingPath(
            cls.getAnnotation("org.springframework.web.bind.annotation.RequestMapping")
        )
        for (method in cls.methods) {
            for ((annotationFqn, httpMethod) in SPRING_ENDPOINT_MAPPING_ANNOTATIONS) {
                val annotation = method.getAnnotation(annotationFqn) ?: continue
                val methodPath = extractBootMappingPath(annotation)
                val fullPath = buildPath(contextPath, classPrefix, methodPath)
                val resolvedMethod = httpMethod
                    ?: extractBootRequestMethod(annotation)
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

        val propsFile = File("$resourcesRoot/application.properties")
        if (propsFile.exists()) {
            propsFile.readLines()
                .firstOrNull { it.trimStart().startsWith("server.servlet.context-path") }
                ?.substringAfter("=")
                ?.trim()
                ?.let { if (it.isNotBlank()) return it }
        }

        val ymlFile = File("$resourcesRoot/application.yml")
        if (ymlFile.exists()) {
            val properties = parseYamlToFlatProperties(ymlFile.readText())
            val contextPath = properties["server.servlet.context-path"]
            if (!contextPath.isNullOrBlank()) return contextPath
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

private fun extractBootMappingPath(annotation: PsiAnnotation?): String {
    if (annotation == null) return ""

    for (attr in arrayOf("value", "path")) {
        val resolved = AnnotationUtil.getStringAttributeValue(annotation, attr)
        if (!resolved.isNullOrBlank()) return resolved.trim('/')
    }

    val raw = annotation.findAttributeValue("value")
        ?: annotation.findAttributeValue("path")
    if (raw is PsiArrayInitializerMemberValue) {
        val evaluator = JavaPsiFacade.getInstance(annotation.project)
            .constantEvaluationHelper
        for (element in raw.initializers) {
            val v = evaluator.computeConstantExpression(element) as? String
            if (!v.isNullOrBlank()) return v.trim('/')
        }
    }
    return ""
}

private fun extractBootRequestMethod(annotation: PsiAnnotation): String? {
    val attr = annotation.findAttributeValue("method") ?: return null
    return when (attr) {
        is PsiReferenceExpression -> attr.referenceName
        is PsiArrayInitializerMemberValue ->
            attr.initializers.filterIsInstance<PsiReferenceExpression>()
                .firstNotNullOfOrNull { it.referenceName }
        else -> null
    }
}

private fun extractMediaTypes(annotation: PsiAnnotation, attribute: String): String {
    val value = annotation.findAttributeValue(attribute) ?: return ""
    val text = value.text ?: return ""
    return text.removeSurrounding("{", "}").removeSurrounding("\"").trim()
}

private fun formatRichParams(method: PsiMethod): String {
    val parts = method.parameterList.parameters.mapNotNull { p ->
        val annotations = buildList {
            val checked = mutableSetOf<String>()
            for ((shortName, fqn) in bootParamAnnotations) {
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
