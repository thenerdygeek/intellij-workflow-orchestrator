package com.workflow.orchestrator.core.ai

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.core.workflow.TicketContext

/**
 * Outcome of an AI text-generation call. Distinguishes context-window overflow from other
 * failures so callers can implement a retry-with-smaller-prompt strategy without parsing
 * error message strings.
 */
sealed class TextGenerationOutcome {
    data class Success(val text: String) : TextGenerationOutcome()
    /** The provider rejected the prompt because it exceeded the model's context window. */
    object ContextOverflow : TextGenerationOutcome()
    /** Any other failure (auth, network, timeout, parse). [message] is logged, not user-facing. */
    data class Other(val errorType: ErrorType?, val message: String) : TextGenerationOutcome()
}

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
     * When [onPartial] is non-null, the implementation streams partial accumulated text via
     * the callback so the caller can show progress in the UI. The final return value is the
     * fully generated description; callers should only commit that to UI as the authoritative
     * result. Default implementation falls back to single-prompt generateText.
     *
     * [diffStat] is the `git diff --stat` summary; the prompt builder always includes it so
     * the model sees the full PR surface area even when the body diff is truncated.
     */
    suspend fun generatePrDescription(
        project: Project,
        diff: String,
        commitMessages: List<String>,
        contextFilePaths: List<String> = emptyList(),
        tickets: List<TicketContext> = emptyList(),
        sourceBranch: String = "",
        targetBranch: String = "",
        diffStat: String = "",
        onPartial: (suspend (String) -> Unit)? = null
    ): String? = null  // Default: not supported, caller falls back

    /**
     * Same as [generatePrDescription] but returns a typed outcome so callers can detect
     * [TextGenerationOutcome.ContextOverflow] and retry with a smaller [diffCap].
     */
    suspend fun generatePrDescriptionTyped(
        project: Project,
        diff: String,
        commitMessages: List<String>,
        contextFilePaths: List<String> = emptyList(),
        tickets: List<TicketContext> = emptyList(),
        sourceBranch: String = "",
        targetBranch: String = "",
        diffStat: String = "",
        diffCap: Int? = null,
        onPartial: (suspend (String) -> Unit)? = null
    ): TextGenerationOutcome = TextGenerationOutcome.Other(null, "not implemented")

    /**
     * Generate a concise single-line PR title from ticket context and commit messages.
     * When [onPartial] is non-null, the implementation streams the in-progress title (always
     * the first non-blank line of the accumulated text). Default returns null; implementors opt in.
     */
    suspend fun generatePrTitle(
        project: Project,
        ticket: TicketContext,
        commitMessages: List<String> = emptyList(),
        onPartial: (suspend (String) -> Unit)? = null
    ): String? = null

    companion object {
        val EP_NAME = ExtensionPointName.create<TextGenerationService>(
            "com.workflow.orchestrator.textGenerationService"
        )

        fun getInstance(): TextGenerationService? =
            EP_NAME.extensionList.firstOrNull()
    }
}
