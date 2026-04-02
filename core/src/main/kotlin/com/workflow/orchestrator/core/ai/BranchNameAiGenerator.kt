package com.workflow.orchestrator.core.ai

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project

/**
 * Extension point interface for AI-powered branch name generation.
 * Lives in :core so :jira can consume it without depending on any feature module.
 * :core provides the implementation via SourcegraphBranchNameGenerator.
 */
interface BranchNameAiGenerator {

    /**
     * Generates a short, professional branch name slug from the ticket title and optional description.
     * The returned string should be lowercase, hyphen-separated, and suitable for use in a git branch name.
     * Example: "fix-null-pointer-in-order-service"
     *
     * @param project The current IntelliJ project (needed to access Sourcegraph LLM API)
     * @param ticketKey The Jira ticket key (e.g. "PROJ-123")
     * @param title The ticket summary/title
     * @param description Optional ticket description (lower priority than title)
     * @return The generated branch name slug, or null if generation failed
     */
    suspend fun generateBranchSlug(project: Project, ticketKey: String, title: String, description: String?): String?

    companion object {
        val EP_NAME = ExtensionPointName.create<BranchNameAiGenerator>(
            "com.workflow.orchestrator.branchNameAiGenerator"
        )

        /**
         * Returns the first available generator, or null if none registered.
         */
        fun getInstance(): BranchNameAiGenerator? =
            EP_NAME.extensionList.firstOrNull()
    }
}
