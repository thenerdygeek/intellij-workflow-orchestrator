package com.workflow.orchestrator.agent.tools.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject

class SpringScheduledTool : AgentTool {
    override val name = "spring_scheduled_tasks"
    override val description = "List @Scheduled methods — cron expressions, fixed rates, fixed delays."
    override val parameters = FunctionParameters(
        properties = emptyMap(),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

        val content: String = try {
            ReadAction.nonBlocking<String> {
                collectScheduledTasks(project)
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
            summary = "Scheduled tasks listed",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }

    private fun collectScheduledTasks(project: Project): String {
        val scope = GlobalSearchScope.projectScope(project)
        val allScope = GlobalSearchScope.allScope(project)
        val facade = JavaPsiFacade.getInstance(project)

        val sb = StringBuilder()

        // 1. Check for @EnableScheduling
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

        // 2. Find @Scheduled methods
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

            // Extract cron
            val cron = extractAnnotationStringValue(annotation, "cron")
            if (cron != null) schedule.add("cron: \"$cron\"")

            // Extract fixedRate
            val fixedRate = extractAnnotationLongValue(annotation, "fixedRate")
            if (fixedRate != null) schedule.add("fixedRate: ${fixedRate}ms")

            // Extract fixedRateString
            val fixedRateString = extractAnnotationStringValue(annotation, "fixedRateString")
            if (fixedRateString != null) schedule.add("fixedRate: \"$fixedRateString\"")

            // Extract fixedDelay
            val fixedDelay = extractAnnotationLongValue(annotation, "fixedDelay")
            if (fixedDelay != null) schedule.add("fixedDelay: ${fixedDelay}ms")

            // Extract fixedDelayString
            val fixedDelayString = extractAnnotationStringValue(annotation, "fixedDelayString")
            if (fixedDelayString != null) schedule.add("fixedDelay: \"$fixedDelayString\"")

            // Extract initialDelay
            val initialDelay = extractAnnotationLongValue(annotation, "initialDelay")
            if (initialDelay != null) schedule.add("initialDelay: ${initialDelay}ms")

            // Extract initialDelayString
            val initialDelayString = extractAnnotationStringValue(annotation, "initialDelayString")
            if (initialDelayString != null) schedule.add("initialDelay: \"$initialDelayString\"")

            val scheduleStr = if (schedule.isNotEmpty()) schedule.joinToString(", ") else "(no schedule params)"
            sb.appendLine("  $className.$methodName() — $scheduleStr")
        }

        return sb.toString().trimEnd()
    }

    private fun extractAnnotationStringValue(
        annotation: com.intellij.psi.PsiAnnotation,
        attributeName: String
    ): String? {
        val value = annotation.findAttributeValue(attributeName) ?: return null
        val text = value.text?.removeSurrounding("\"") ?: return null
        return if (text.isNotBlank() && text != "\"\"" && text != "") text else null
    }

    private fun extractAnnotationLongValue(
        annotation: com.intellij.psi.PsiAnnotation,
        attributeName: String
    ): Long? {
        val value = annotation.findAttributeValue(attributeName) ?: return null
        val text = value.text ?: return null
        val parsed = text.removeSuffix("L").removeSuffix("l").toLongOrNull()
        // Default values for fixedRate/fixedDelay/initialDelay are -1
        return if (parsed != null && parsed >= 0) parsed else null
    }
}
