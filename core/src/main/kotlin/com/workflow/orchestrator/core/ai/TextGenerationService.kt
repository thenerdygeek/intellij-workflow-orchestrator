package com.workflow.orchestrator.core.ai

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project

/**
 * Interface for cross-module AI text generation.
 * Implemented by :core via SourcegraphTextGenerationService, consumed by :bamboo (PR description) without compile-time dependency.
 */
interface TextGenerationService {

    /**
     * Generate text from a prompt with optional file context.
     * @param project The current project
     * @param prompt Instructions for the AI
     * @param contextFilePaths Absolute file paths to provide as context (higher token budget)
     * @return Generated text, or null if unavailable
     */
    suspend fun generateText(
        project: Project,
        prompt: String,
        contextFilePaths: List<String> = emptyList()
    ): String?

    /**
     * Generate a PR description using a multi-step prompt chain.
     * Default implementation falls back to single-prompt generateText.
     */
    suspend fun generatePrDescription(
        project: Project,
        diff: String,
        commitMessages: List<String>,
        contextFilePaths: List<String> = emptyList(),
        ticketId: String = "",
        ticketSummary: String = "",
        ticketDescription: String = "",
        sourceBranch: String = "",
        targetBranch: String = ""
    ): String? = null  // Default: not supported, caller falls back

    companion object {
        val EP_NAME = ExtensionPointName.create<TextGenerationService>(
            "com.workflow.orchestrator.textGenerationService"
        )

        fun getInstance(): TextGenerationService? =
            EP_NAME.extensionList.firstOrNull()
    }
}
