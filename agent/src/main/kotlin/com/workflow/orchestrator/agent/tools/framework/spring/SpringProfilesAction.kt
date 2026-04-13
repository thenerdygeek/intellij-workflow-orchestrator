package com.workflow.orchestrator.agent.tools.framework.spring

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.psi.PsiToolUtils
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.serialization.json.JsonObject

internal suspend fun executeProfiles(params: JsonObject, project: Project): ToolResult {
    if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

    val content: String = try {
        ReadAction.nonBlocking<String> {
            collectProfiles(project)
        }.inSmartMode(project).executeSynchronously()
    } catch (e: NoClassDefFoundError) {
        return ToolResult("Spring plugin not available.", "No Spring", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    } catch (e: Exception) {
        return ToolResult("Error: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }

    return ToolResult(
        content = content,
        summary = "Spring profiles listed",
        tokenEstimate = TokenEstimator.estimate(content)
    )
}

private fun collectProfiles(project: Project): String {
    val scope = GlobalSearchScope.projectScope(project)
    val facade = JavaPsiFacade.getInstance(project)

    val profileSources = mutableMapOf<String, MutableList<String>>()

    val profileAnnotationClass = facade.findClass(
        "org.springframework.context.annotation.Profile",
        GlobalSearchScope.allScope(project)
    )

    if (profileAnnotationClass != null) {
        val annotatedClasses = AnnotatedElementsSearch.searchPsiClasses(
            profileAnnotationClass, scope
        ).findAll()

        for (cls in annotatedClasses) {
            val annotation = cls.getAnnotation("org.springframework.context.annotation.Profile")
                ?: continue
            val profiles = extractProfileNames(annotation)
            val className = cls.name ?: "(anonymous)"
            for (profile in profiles) {
                profileSources.getOrPut(profile) { mutableListOf() }
                    .add("@Profile on class: $className")
            }
        }

        val annotatedMethods = AnnotatedElementsSearch.searchPsiMethods(
            profileAnnotationClass, scope
        ).findAll()

        for (method in annotatedMethods) {
            val annotation = method.getAnnotation("org.springframework.context.annotation.Profile")
                ?: continue
            val profiles = extractProfileNames(annotation)
            val className = method.containingClass?.name ?: "(anonymous)"
            val methodName = method.name
            for (profile in profiles) {
                profileSources.getOrPut(profile) { mutableListOf() }
                    .add("@Profile on method: $className.$methodName()")
            }
        }
    }

    val profileConfigPattern = Regex("^application-(.+)\\.(properties|ya?ml)$")
    val allPropertyFiles = FilenameIndex.getAllFilesByExt(project, "properties", scope) +
        FilenameIndex.getAllFilesByExt(project, "yml", scope) +
        FilenameIndex.getAllFilesByExt(project, "yaml", scope)

    for (vf in allPropertyFiles) {
        val match = profileConfigPattern.matchEntire(vf.name) ?: continue
        val profile = match.groupValues[1]
        profileSources.getOrPut(profile) { mutableListOf() }
            .add("config: ${vf.name}")
    }

    var activeProfiles: String? = null
    val appPropsFiles = FilenameIndex.getVirtualFilesByName("application.properties", scope)
    for (vf in appPropsFiles) {
        val text = VfsUtil.loadText(vf)
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("spring.profiles.active=")) {
                activeProfiles = trimmed.substringAfter("spring.profiles.active=").trim()
            }
        }
    }

    val appYmlFiles = FilenameIndex.getVirtualFilesByName("application.yml", scope) +
        FilenameIndex.getVirtualFilesByName("application.yaml", scope)
    for (vf in appYmlFiles) {
        val text = VfsUtil.loadText(vf)
        val properties = parseYamlToFlatProperties(text)
        // Spring Boot 2.x: spring.profiles.active, Spring Boot 3.x: spring.config.activate.on-profile
        val active = properties["spring.profiles.active"]
            ?: properties["spring.config.activate.on-profile"]
        if (!active.isNullOrBlank()) {
            activeProfiles = active
        }
    }

    if (profileSources.isEmpty() && activeProfiles == null) {
        return "No Spring profiles found in project."
    }

    val sb = StringBuilder("Spring Profiles:\n")
    for ((profile, sources) in profileSources.toSortedMap()) {
        val sourceSummary = sources.joinToString("; ")
        sb.appendLine("  $profile — $sourceSummary")
    }
    if (activeProfiles != null) {
        sb.appendLine("Active (application config): $activeProfiles")
    }
    return sb.toString().trimEnd()
}

private fun extractProfileNames(annotation: PsiAnnotation): List<String> {
    val value = annotation.findAttributeValue("value") ?: return emptyList()
    return when (value) {
        is PsiArrayInitializerMemberValue -> {
            value.initializers.mapNotNull { (it as? PsiLiteralExpression)?.value as? String }
        }
        is PsiLiteralExpression -> {
            listOfNotNull(value.value as? String)
        }
        else -> {
            val text = value.text ?: return emptyList()
            text.removeSurrounding("{", "}")
                .split(",")
                .map { it.trim().removeSurrounding("\"") }
                .filter { it.isNotBlank() }
        }
    }
}
