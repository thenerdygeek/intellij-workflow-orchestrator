package com.workflow.orchestrator.core.ai.prompts

/**
 * Truncates the string to [maxLen] characters. If truncated, appends "..." so callers
 * never need to replicate the cap-plus-ellipsis pattern inline.
 */
internal fun String.truncateTo(maxLen: Int): String =
    if (length > maxLen) take(maxLen) + "..." else this
