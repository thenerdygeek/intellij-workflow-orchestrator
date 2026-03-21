package com.workflow.orchestrator.agent.tools.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject

class SpringProfilesTool : AgentTool {
    override val name = "spring_profiles"
    override val description = "List Spring profiles — from @Profile annotations on classes/methods, " +
        "application-{profile}.properties/yml files, and spring.profiles.active configuration."
    override val parameters = FunctionParameters(
        properties = emptyMap(),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

        val content: String = try {
            ReadAction.nonBlocking<String> {
                collectProfiles(project)
            }.inSmartMode(project).executeSynchronously()
        } catch (e: NoClassDefFoundError) {
            return ToolResult(
                "Spring plugin not available.",
                "No Spring",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        } catch (e: Exception) {
            return ToolResult(
                "Error: ${e.message}",
                "Error",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
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

        // Profile name -> sources (class/method names and config files)
        val profileSources = mutableMapOf<String, MutableList<String>>()

        // 1. Find @Profile annotations on classes and methods
        val profileAnnotationClass = facade.findClass(
            "org.springframework.context.annotation.Profile",
            GlobalSearchScope.allScope(project)
        )

        if (profileAnnotationClass != null) {
            // Search annotated classes
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

            // Search annotated methods
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

        // 2. Find application-{profile}.properties and application-{profile}.yml files
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

        // 3. Check spring.profiles.active in application.properties
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

        // Also check application.yml for spring.profiles.active
        val appYmlFiles = FilenameIndex.getVirtualFilesByName("application.yml", scope) +
            FilenameIndex.getVirtualFilesByName("application.yaml", scope)
        for (vf in appYmlFiles) {
            val text = VfsUtil.loadText(vf)
            for (line in text.lines()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("active:") && text.contains("profiles:")) {
                    activeProfiles = trimmed.substringAfter("active:").trim()
                }
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

    private fun extractProfileNames(annotation: com.intellij.psi.PsiAnnotation): List<String> {
        val value = annotation.findAttributeValue("value") ?: return emptyList()
        return when (value) {
            is PsiArrayInitializerMemberValue -> {
                value.initializers.mapNotNull { (it as? PsiLiteralExpression)?.value as? String }
            }
            is PsiLiteralExpression -> {
                listOfNotNull(value.value as? String)
            }
            else -> {
                // Handle text-based extraction as fallback
                val text = value.text ?: return emptyList()
                text.removeSurrounding("{", "}")
                    .split(",")
                    .map { it.trim().removeSurrounding("\"") }
                    .filter { it.isNotBlank() }
            }
        }
    }
}
