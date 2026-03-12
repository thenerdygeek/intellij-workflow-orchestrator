package com.workflow.orchestrator.jira.workflow

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.workflow.WorkflowIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

data class TransitionChoice(val id: String, val name: String) {
    override fun toString(): String = name
}

object DisambiguationHelper {

    fun parseDisambiguationError(error: ApiResult.Error): List<TransitionChoice>? {
        if (!error.message.startsWith("DISAMBIGUATE:")) return null
        return error.message.removePrefix("DISAMBIGUATE:")
            .split("|")
            .mapNotNull { entry ->
                val parts = entry.split("::")
                if (parts.size == 2) TransitionChoice(parts[0], parts[1]) else null
            }
    }

    suspend fun showDisambiguationPopup(
        project: Project,
        intent: WorkflowIntent,
        choices: List<TransitionChoice>
    ): TransitionChoice? = withContext(Dispatchers.EDT) {
        suspendCancellableCoroutine { cont ->
            val step = object : BaseListPopupStep<TransitionChoice>(
                "Select transition for '${intent.displayName}'", choices
            ) {
                override fun onChosen(selectedValue: TransitionChoice, finalChoice: Boolean): PopupStep<*>? {
                    cont.resume(selectedValue)
                    return FINAL_CHOICE
                }
            }
            JBPopupFactory.getInstance().createListPopup(step).showCenteredInCurrentWindow(project)
        }
    }

    fun saveLearnedMapping(
        project: Project,
        intent: WorkflowIntent,
        choice: TransitionChoice,
        projectKey: String
    ) {
        val settings = PluginSettings.getInstance(project)
        val store = TransitionMappingStore()
        store.loadFromJson(settings.state.workflowMappings ?: "")
        store.saveMapping(
            TransitionMapping(intent.name, choice.name, projectKey, null, "learned")
        )
        settings.state.workflowMappings = store.toJson()
    }
}
