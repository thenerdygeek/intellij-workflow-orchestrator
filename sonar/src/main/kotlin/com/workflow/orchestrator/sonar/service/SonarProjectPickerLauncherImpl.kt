package com.workflow.orchestrator.sonar.service

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.services.SonarProjectPickerLauncher
import com.workflow.orchestrator.sonar.ui.SonarProjectPickerDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * :sonar-side implementation of [SonarProjectPickerLauncher].
 *
 * Opens the existing [SonarProjectPickerDialog] on the EDT and returns the
 * key chosen by the user, or null if the dialog was cancelled.
 *
 * Registered via the `sonarProjectPickerLauncher` extension point in plugin.xml
 * so that :core can invoke it without a compile-time dependency on :sonar.
 */
class SonarProjectPickerLauncherImpl : SonarProjectPickerLauncher {

    override suspend fun pick(project: Project, currentKey: String?): String? =
        withContext(Dispatchers.EDT) {
            val dialog = SonarProjectPickerDialog(project)
            if (dialog.showAndGet()) dialog.selectedProjectKey else null
        }
}
