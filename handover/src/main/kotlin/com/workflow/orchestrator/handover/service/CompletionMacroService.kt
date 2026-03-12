package com.workflow.orchestrator.handover.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.handover.model.MacroStep
import com.workflow.orchestrator.handover.model.MacroStepStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Service(Service.Level.PROJECT)
class CompletionMacroService {

    private val json = Json { ignoreUnknownKeys = true }
    private var project: Project? = null

    constructor(project: Project) {
        this.project = project
    }

    constructor()

    fun getDefaultSteps(): List<MacroStep> = listOf(
        MacroStep(id = "copyright", label = "Fix Copyright Headers"),
        MacroStep(id = "jira-comment", label = "Post Jira Comment"),
        MacroStep(id = "jira-transition", label = getReviewTransitionLabel()),
        MacroStep(id = "time-log", label = "Log Work")
    )

    private fun getReviewTransitionLabel(): String {
        val proj = project ?: return "Transition to Review"
        val settings = PluginSettings.getInstance(proj)
        val mappingsJson = settings.state.workflowMappings ?: return "Transition to Review"
        if (mappingsJson.isBlank()) return "Transition to Review"
        return try {
            val mappings = json.decodeFromString<List<WorkflowMappingEntry>>(mappingsJson)
            val match = mappings.find { it.intent == "SUBMIT_FOR_REVIEW" }
            if (match != null) "Transition to ${match.transitionName}" else "Transition to Review"
        } catch (_: Exception) {
            "Transition to Review"
        }
    }

    @Serializable
    private data class WorkflowMappingEntry(
        val intent: String,
        val transitionName: String,
        val projectKey: String,
        val issueTypeId: String? = null,
        val source: String
    )

    fun filterEnabledSteps(steps: List<MacroStep>): List<MacroStep> {
        return steps.filter { it.enabled }
    }

    fun markStepStatus(steps: List<MacroStep>, stepId: String, status: MacroStepStatus): List<MacroStep> {
        return steps.map { step ->
            if (step.id == stepId) step.copy(status = status) else step
        }
    }

    fun markRemainingSkipped(steps: List<MacroStep>): List<MacroStep> {
        return steps.map { step ->
            if (step.status == MacroStepStatus.PENDING) step.copy(status = MacroStepStatus.SKIPPED)
            else step
        }
    }

    /**
     * Execute macro steps sequentially. Each action returns true (success) or false (failure).
     * On failure, remaining steps are marked SKIPPED. Disabled steps are also SKIPPED.
     */
    suspend fun executeMacro(
        steps: List<MacroStep>,
        actions: Map<String, suspend () -> Boolean>
    ): List<MacroStep> {
        val results = steps.toMutableList()
        var failed = false

        for (i in results.indices) {
            val step = results[i]
            if (!step.enabled || failed) {
                results[i] = step.copy(status = MacroStepStatus.SKIPPED)
                continue
            }

            results[i] = step.copy(status = MacroStepStatus.RUNNING)
            val action = actions[step.id]
            val success = try {
                action?.invoke() ?: false
            } catch (_: Exception) {
                false
            }

            results[i] = step.copy(
                status = if (success) MacroStepStatus.SUCCESS else MacroStepStatus.FAILED
            )

            if (!success) {
                failed = true
            }
        }

        return results
    }

    companion object {
        fun getInstance(project: Project): CompletionMacroService {
            return project.getService(CompletionMacroService::class.java)
        }
    }
}
