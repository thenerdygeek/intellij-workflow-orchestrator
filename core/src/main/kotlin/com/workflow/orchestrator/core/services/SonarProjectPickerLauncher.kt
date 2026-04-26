package com.workflow.orchestrator.core.services

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project

/**
 * Extension point for opening the SonarQube project picker dialog from :core
 * without a compile-time dependency on :sonar.
 *
 * Implemented by [com.workflow.orchestrator.sonar.service.SonarProjectPickerLauncherImpl]
 * in the :sonar module. Called from [AutoDetectOrchestrator] on detection miss
 * and from [RepositoriesConfigurable]'s per-repo "Find" button.
 */
interface SonarProjectPickerLauncher {

    /**
     * Opens the SonarQube project picker dialog so the user can search and select a
     * project key from the server.
     *
     * Must be called on a background thread; the implementation dispatches the dialog
     * itself to the EDT. Returns the selected project key, or null if the user cancels.
     *
     * @param project    The active IntelliJ project.
     * @param currentKey Optional pre-populated search term (e.g. the partially-detected key).
     */
    suspend fun pick(project: Project, currentKey: String?): String?

    companion object {
        val EP_NAME = ExtensionPointName.create<SonarProjectPickerLauncher>(
            "com.workflow.orchestrator.plugin.sonarProjectPickerLauncher"
        )

        /** Returns the first registered implementation, or null if :sonar is not loaded. */
        fun getInstance(): SonarProjectPickerLauncher? = EP_NAME.extensionList.firstOrNull()
    }
}
