package com.workflow.orchestrator.agent.tools.framework.spring

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.psi.PsiToolUtils
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal suspend fun executeBootAutoConfig(params: JsonObject, project: Project): ToolResult {
    if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

    val filter = params["filter"]?.jsonPrimitive?.contentOrNull
    val projectOnly = params["project_only"]?.jsonPrimitive?.booleanOrNull ?: true

    val content: String = try {
        ReadAction.nonBlocking<String> {
            collectAutoConfigs(project, filter, projectOnly)
        }.inSmartMode(project).executeSynchronously()
    } catch (e: NoClassDefFoundError) {
        return ToolResult("Spring plugin not available.", "No Spring", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    } catch (e: Exception) {
        return ToolResult("Error: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }

    return ToolResult(
        content = content,
        summary = "Spring Boot auto-configurations listed${if (filter != null) " (filter: $filter)" else ""}",
        tokenEstimate = TokenEstimator.estimate(content)
    )
}

private fun collectAutoConfigs(project: Project, filter: String?, projectOnly: Boolean): String {
    val searchScope = if (projectOnly) {
        GlobalSearchScope.projectScope(project)
    } else {
        GlobalSearchScope.allScope(project)
    }
    val allScope = GlobalSearchScope.allScope(project)
    val facade = JavaPsiFacade.getInstance(project)

    val configs = mutableListOf<AutoConfigEntry>()

    val configurationClass = facade.findClass(
        "org.springframework.context.annotation.Configuration",
        allScope
    )
    if (configurationClass != null) {
        val annotatedClasses = AnnotatedElementsSearch.searchPsiClasses(
            configurationClass, searchScope
        ).findAll()
        for (cls in annotatedClasses) {
            val entry = buildAutoConfigEntry(project, cls) ?: continue
            configs.add(entry)
        }
    }

    val autoConfigurationClass = facade.findClass(
        "org.springframework.boot.autoconfigure.AutoConfiguration",
        allScope
    )
    if (autoConfigurationClass != null) {
        val annotatedClasses = AnnotatedElementsSearch.searchPsiClasses(
            autoConfigurationClass, searchScope
        ).findAll()
        for (cls in annotatedClasses) {
            val fqn = cls.qualifiedName ?: continue
            if (configs.none { it.qualifiedName == fqn }) {
                val entry = buildAutoConfigEntry(project, cls) ?: continue
                configs.add(entry)
            }
        }
    }

    if (configs.isEmpty()) {
        val scopeLabel = if (projectOnly) "project" else "classpath"
        return "No @Configuration or @AutoConfiguration classes found in $scopeLabel scope."
    }

    val filtered = if (filter != null) {
        configs.filter { entry ->
            entry.qualifiedName.contains(filter, ignoreCase = true) ||
                entry.conditions.any { it.contains(filter, ignoreCase = true) } ||
                entry.enableAnnotations.any { it.contains(filter, ignoreCase = true) }
        }
    } else {
        configs
    }

    if (filtered.isEmpty()) {
        return "No auto-configuration classes found matching filter '$filter'."
    }

    val sorted = filtered.sortedBy { it.qualifiedName }
    val sb = StringBuilder("Auto-configurations (${sorted.size}):\n")

    for (entry in sorted) {
        sb.appendLine()
        sb.appendLine(entry.qualifiedName)
        for (enable in entry.enableAnnotations) {
            sb.appendLine("  $enable")
        }
        for (cond in entry.conditions) {
            sb.appendLine("  $cond")
        }
        sb.appendLine("  File: ${entry.filePath}")
    }

    if (filtered.size < configs.size) {
        sb.appendLine()
        sb.append("(Showing ${filtered.size} of ${configs.size} total)")
    }

    return sb.toString().trimEnd()
}

private fun buildAutoConfigEntry(project: Project, cls: PsiClass): AutoConfigEntry? {
    val fqn = cls.qualifiedName ?: return null

    val conditions = mutableListOf<String>()
    val enableAnnotations = mutableListOf<String>()

    for (annotation in cls.annotations) {
        val shortName = annotation.qualifiedName?.substringAfterLast('.') ?: continue
        when {
            shortName == "ConditionalOnClass" -> {
                val names = extractAutoConfigClassNames(annotation)
                conditions.add("@ConditionalOnClass(${names.joinToString(", ")})")
            }
            shortName == "ConditionalOnMissingClass" -> {
                val names = extractAutoConfigClassNames(annotation)
                conditions.add("@ConditionalOnMissingClass(${names.joinToString(", ")})")
            }
            shortName == "ConditionalOnBean" -> {
                val names = extractAutoConfigClassNames(annotation)
                conditions.add("@ConditionalOnBean(${names.joinToString(", ")})")
            }
            shortName == "ConditionalOnMissingBean" -> {
                val names = extractAutoConfigClassNames(annotation)
                conditions.add("@ConditionalOnMissingBean(${names.joinToString(", ")})")
            }
            shortName == "ConditionalOnProperty" -> {
                val propDesc = extractAutoConfigPropertyCondition(annotation)
                conditions.add("@ConditionalOnProperty($propDesc)")
            }
            shortName == "ConditionalOnResource" -> {
                val resources = extractAutoConfigStringValues(annotation, "resources")
                    .ifEmpty { extractAutoConfigStringValues(annotation, "value") }
                conditions.add("@ConditionalOnResource(${resources.joinToString(", ")})")
            }
            shortName == "ConditionalOnWebApplication" -> {
                val type = extractAutoConfigStringValue(annotation, "type")
                conditions.add(if (type != null) "@ConditionalOnWebApplication(type=$type)" else "@ConditionalOnWebApplication")
            }
            shortName == "ConditionalOnNotWebApplication" -> {
                conditions.add("@ConditionalOnNotWebApplication")
            }
            shortName == "ConditionalOnExpression" -> {
                val expr = extractAutoConfigStringValue(annotation, "value")
                conditions.add("@ConditionalOnExpression(${expr ?: "..."})")
            }
            shortName == "ConditionalOnJava" -> {
                val range = extractAutoConfigStringValue(annotation, "range")
                val value = extractAutoConfigStringValue(annotation, "value")
                val desc = listOfNotNull(range?.let { "range=$it" }, value?.let { "value=$it" })
                    .joinToString(", ")
                conditions.add("@ConditionalOnJava($desc)")
            }
            shortName == "ConditionalOnSingleCandidate" -> {
                val names = extractAutoConfigClassNames(annotation)
                conditions.add("@ConditionalOnSingleCandidate(${names.joinToString(", ")})")
            }
            shortName == "ConditionalOnCloudPlatform" -> {
                val value = extractAutoConfigStringValue(annotation, "value")
                conditions.add("@ConditionalOnCloudPlatform(${value ?: "..."})")
            }
            shortName.startsWith("Enable") -> {
                enableAnnotations.add("@$shortName")
            }
        }
    }

    val filePath = cls.containingFile?.virtualFile?.path
        ?.let { PsiToolUtils.relativePath(project, it) }
        ?: "(unknown)"

    return AutoConfigEntry(
        qualifiedName = fqn,
        conditions = conditions,
        enableAnnotations = enableAnnotations,
        filePath = filePath
    )
}

private fun extractAutoConfigClassNames(annotation: PsiAnnotation): List<String> {
    val results = mutableListOf<String>()
    for (attrName in listOf("value", "name", "type")) {
        val attrValue = annotation.findAttributeValue(attrName) ?: continue
        val text = attrValue.text ?: continue
        val cleaned = text
            .removeSurrounding("{", "}")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
        for (token in cleaned) {
            val name = token
                .removeSuffix(".class")
                .removeSuffix("::class")
                .removeSuffix("::class.java")
                .removeSurrounding("\"")
                .trim()
            if (name.isNotBlank()) results.add(name)
        }
        if (results.isNotEmpty()) break
    }
    return results.ifEmpty { listOf("...") }
}

private fun extractAutoConfigStringValue(annotation: PsiAnnotation, attr: String): String? {
    val value = annotation.findAttributeValue(attr) ?: return null
    val text = value.text ?: return null
    val stripped = text.removeSurrounding("\"")
    return if (stripped.isNotBlank() && stripped != text) stripped else text.trim()
}

private fun extractAutoConfigStringValues(annotation: PsiAnnotation, attr: String): List<String> {
    val value = annotation.findAttributeValue(attr) ?: return emptyList()
    val text = value.text ?: return emptyList()
    return text
        .removeSurrounding("{", "}")
        .split(",")
        .map { it.trim().removeSurrounding("\"") }
        .filter { it.isNotBlank() }
}

private fun extractAutoConfigPropertyCondition(annotation: PsiAnnotation): String {
    val name = extractAutoConfigStringValues(annotation, "name")
        .ifEmpty { extractAutoConfigStringValues(annotation, "value") }
    val havingValue = extractAutoConfigStringValue(annotation, "havingValue")
    val prefix = extractAutoConfigStringValue(annotation, "prefix")
    val matchIfMissing = extractAutoConfigStringValue(annotation, "matchIfMissing")

    val parts = mutableListOf<String>()
    if (prefix != null && prefix.isNotBlank()) parts.add("prefix=$prefix")
    if (name.isNotEmpty()) parts.add("name=${name.joinToString(", ")}")
    if (havingValue != null && havingValue.isNotBlank()) parts.add("havingValue=$havingValue")
    if (matchIfMissing != null && matchIfMissing != "false") parts.add("matchIfMissing=$matchIfMissing")
    return parts.joinToString(", ").ifBlank { "..." }
}

private data class AutoConfigEntry(
    val qualifiedName: String,
    val conditions: List<String>,
    val enableAnnotations: List<String>,
    val filePath: String
)
