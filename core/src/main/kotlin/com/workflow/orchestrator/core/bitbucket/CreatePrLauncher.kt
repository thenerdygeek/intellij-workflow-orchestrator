package com.workflow.orchestrator.core.bitbucket

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope

/**
 * Extension point for cross-module PR creation delegation.
 * Implemented by the :pullrequest module (Phase 6); consumed by :handover and the agent
 * without a compile-time dependency on :pullrequest.
 *
 * [launch] is called on the EDT or a coroutine dispatcher chosen by the caller.
 * The implementation is responsible for collecting all required inputs (tickets,
 * diff, reviewers) and posting the PR. The [onCreated] callback fires on the EDT
 * once the PR is successfully created.
 */
interface CreatePrLauncher {

    /**
     * Opens the PR creation flow for the current project.
     *
     * @param project  The active IntelliJ project.
     * @param scope    Coroutine scope for async work inside the launcher.
     * @param onCreated Callback with the created PR's URL and numeric ID.
     */
    fun launch(
        project: Project,
        scope: CoroutineScope,
        onCreated: (prUrl: String, prId: Int) -> Unit
    )

    companion object {
        val EP_NAME = ExtensionPointName.create<CreatePrLauncher>(
            "com.workflow.orchestrator.createPrLauncher"
        )

        /** Returns the first registered implementation, or null if none is registered yet. */
        fun getInstance(): CreatePrLauncher? = EP_NAME.extensionList.firstOrNull()
    }
}
