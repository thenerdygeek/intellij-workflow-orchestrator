package com.workflow.orchestrator.handover.model

/**
 * Resolved value for one placeholder (e.g., `{ticket.id}`, `{docker.tagsJson}`).
 *
 * - When available, [value] holds the literal string to substitute and both
 *   [renderForJira] and [renderForEmail] return it unchanged.
 * - When unavailable (e.g., no active ticket, AI service offline), [renderForJira]
 *   returns an em-dash and [renderForEmail] returns a muted italic em-dash so the
 *   surrounding template renders cleanly without an angry "{undefined}" leak.
 *
 * [unavailableReason] is shown in tooltips on the live preview pane.
 */
data class HandoverPlaceholderValue(
    val value: String,
    val isAvailable: Boolean,
    val unavailableReason: String? = null,
) {
    fun renderForJira(): String = if (isAvailable) value else "—"
    fun renderForEmail(): String =
        if (isAvailable) value else "<i style=\"color:#888\">—</i>"

    companion object {
        fun available(value: String): HandoverPlaceholderValue =
            HandoverPlaceholderValue(value = value, isAvailable = true)

        fun unavailable(reason: String): HandoverPlaceholderValue =
            HandoverPlaceholderValue(value = "", isAvailable = false, unavailableReason = reason)
    }
}
