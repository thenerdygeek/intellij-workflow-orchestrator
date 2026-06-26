package com.workflow.orchestrator.core.ai.prompts

/**
 * Commit-message generation style. CONVENTIONAL keeps the Conventional-Commits +
 * issue-type-driven shape (the historical default); PLAIN is the de-convention
 * escape hatch for teams that don't use Conventional Commits.
 */
enum class CommitMessageFormat {
    CONVENTIONAL,
    PLAIN;

    companion object {
        /** Maps the [PluginSettings.State.commitMessageFormat] string; unknown/null -> CONVENTIONAL. */
        fun fromSetting(value: String?): CommitMessageFormat =
            if (value?.trim()?.equals("plain", ignoreCase = true) == true) PLAIN else CONVENTIONAL
    }
}
