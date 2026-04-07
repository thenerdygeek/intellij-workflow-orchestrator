package com.workflow.orchestrator.agent.tools.framework.spring

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.psi.PsiToolUtils
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.serialization.json.JsonObject

internal suspend fun executeScheduledTasks(params: JsonObject, project: Project): ToolResult {
    if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

    val content: String = try {
        ReadAction.nonBlocking<String> {
            collectScheduledTasks(project)
        }.inSmartMode(project).executeSynchronously()
    } catch (e: NoClassDefFoundError) {
        return ToolResult("Spring plugin not available.", "No Spring", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    } catch (e: Exception) {
        return ToolResult("Error: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }

    return ToolResult(
        content = content,
        summary = "Scheduled tasks listed",
        tokenEstimate = TokenEstimator.estimate(content)
    )
}

private fun collectScheduledTasks(project: Project): String {
    val scope = GlobalSearchScope.projectScope(project)
    val allScope = GlobalSearchScope.allScope(project)
    val facade = JavaPsiFacade.getInstance(project)

    val sb = StringBuilder()

    val enableSchedulingClass = facade.findClass(
        "org.springframework.scheduling.annotation.EnableScheduling",
        allScope
    )
    if (enableSchedulingClass != null) {
        val enabledClasses = AnnotatedElementsSearch.searchPsiClasses(
            enableSchedulingClass, scope
        ).findAll()
        if (enabledClasses.isNotEmpty()) {
            val classNames = enabledClasses.mapNotNull { it.name }.joinToString(", ")
            sb.appendLine("@EnableScheduling: enabled ($classNames)")
        } else {
            sb.appendLine("@EnableScheduling: not found in project classes")
        }
    } else {
        sb.appendLine("@EnableScheduling: annotation not on classpath")
    }

    val scheduledClass = facade.findClass(
        "org.springframework.scheduling.annotation.Scheduled",
        allScope
    )

    if (scheduledClass == null) {
        sb.appendLine("@Scheduled annotation not found on classpath.")
        return sb.toString().trimEnd()
    }

    val scheduledMethods = AnnotatedElementsSearch.searchPsiMethods(
        scheduledClass, scope
    ).findAll()

    if (scheduledMethods.isEmpty()) {
        sb.appendLine("Scheduled Tasks: none found")
        return sb.toString().trimEnd()
    }

    sb.appendLine("Scheduled Tasks (${scheduledMethods.size}):")

    for (method in scheduledMethods.sortedBy { "${it.containingClass?.name}.${it.name}" }) {
        val annotation = method.getAnnotation(
            "org.springframework.scheduling.annotation.Scheduled"
        ) ?: continue

        val className = method.containingClass?.name ?: "(anonymous)"
        val methodName = method.name

        val schedule = mutableListOf<String>()

        val cron = extractScheduledAnnotationStringValue(annotation, "cron")
        if (cron != null) schedule.add("cron: \"$cron\"")

        val fixedRate = extractScheduledAnnotationLongValue(annotation, "fixedRate")
        if (fixedRate != null) schedule.add("fixedRate: ${fixedRate}ms")

        val fixedRateString = extractScheduledAnnotationStringValue(annotation, "fixedRateString")
        if (fixedRateString != null) schedule.add("fixedRate: \"$fixedRateString\"")

        val fixedDelay = extractScheduledAnnotationLongValue(annotation, "fixedDelay")
        if (fixedDelay != null) schedule.add("fixedDelay: ${fixedDelay}ms")

        val fixedDelayString = extractScheduledAnnotationStringValue(annotation, "fixedDelayString")
        if (fixedDelayString != null) schedule.add("fixedDelay: \"$fixedDelayString\"")

        val initialDelay = extractScheduledAnnotationLongValue(annotation, "initialDelay")
        if (initialDelay != null) schedule.add("initialDelay: ${initialDelay}ms")

        val initialDelayString = extractScheduledAnnotationStringValue(annotation, "initialDelayString")
        if (initialDelayString != null) schedule.add("initialDelay: \"$initialDelayString\"")

        val scheduleStr = if (schedule.isNotEmpty()) schedule.joinToString(", ") else "(no schedule params)"
        sb.appendLine("  $className.$methodName() — $scheduleStr")
    }

    return sb.toString().trimEnd()
}

private fun extractScheduledAnnotationStringValue(
    annotation: PsiAnnotation,
    attributeName: String
): String? {
    val value = annotation.findAttributeValue(attributeName) ?: return null
    val text = value.text?.removeSurrounding("\"") ?: return null
    return if (text.isNotBlank() && text != "\"\"" && text != "") text else null
}

private fun extractScheduledAnnotationLongValue(
    annotation: PsiAnnotation,
    attributeName: String
): Long? {
    val value = annotation.findAttributeValue(attributeName) ?: return null
    val text = value.text ?: return null
    val parsed = text.removeSuffix("L").removeSuffix("l").toLongOrNull()
    return if (parsed != null && parsed >= 0) parsed else null
}
