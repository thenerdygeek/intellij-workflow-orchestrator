package com.workflow.orchestrator.core.ai

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project

/**
 * Interface for cross-module AI text generation.
 * Implemented by :cody module, consumed by :bamboo (PR description) without compile-time dependency.
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

    companion object {
        val EP_NAME = ExtensionPointName.create<TextGenerationService>(
            "com.workflow.orchestrator.textGenerationService"
        )

        fun getInstance(): TextGenerationService? =
            EP_NAME.extensionList.firstOrNull()
    }
}
