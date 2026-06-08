package com.workflow.orchestrator.agent.session

import com.workflow.orchestrator.agent.tools.background.BackgroundCompletionEvent
import com.workflow.orchestrator.agent.tools.background.DelegationNudge
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

object ResumeHelper {

    private val json = Json { ignoreUnknownKeys = true }

    /** Max chars of a background-process label shown in the resume preamble. */
    private const val COMPLETION_LABEL_MAX = 80

    /** Max trailing output lines of a background completion shown in the resume preamble. */
    private const val COMPLETION_TAIL_LINES = 5

    data class PopResult(
        val trimmedHistory: List<ApiMessage>,
        val poppedContent: List<ContentBlock>,
    )

    fun trimResumeMessages(messages: List<UiMessage>): List<UiMessage> {
        val result = messages.toMutableList()

        // Remove trailing resume_task / resume_completed_task messages
        while (result.isNotEmpty()) {
            val last = result.last()
            if (last.ask == UiAsk.RESUME_TASK || last.ask == UiAsk.RESUME_COMPLETED_TASK) {
                result.removeAt(result.lastIndex)
            } else {
                break
            }
        }

        // Remove last api_req_started if it has no cost/cancelReason
        if (result.isNotEmpty()) {
            val lastIdx = result.indexOfLast { it.say == UiSay.API_REQ_STARTED }
            if (lastIdx >= 0 && lastIdx == result.lastIndex) {
                val textJson = result[lastIdx].text ?: "{}"
                if (!hasCostOrCancel(textJson)) {
                    result.removeAt(lastIdx)
                }
            }
        }

        return result
    }

    private fun hasCostOrCancel(textJson: String): Boolean {
        return try {
            val obj = json.decodeFromString<JsonObject>(textJson)
            obj.containsKey("cost") && obj["cost"]?.jsonPrimitive?.content != "null" ||
                obj.containsKey("cancelReason")
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Pop the trailing user message if the API history ends on a USER turn.
     *
     * After the XML-in-content migration, tool-result blocks are persisted as
     * USER role with [ContentBlock.ToolResult] blocks (the "tool" role is an
     * OpenAI-compat convention; our on-disk shape coerces it to USER).  If a
     * session was interrupted after the tool result was already written, the
     * trailing USER message contains only tool-result content — not a real user
     * prompt.  Popping it is correct: the AgentLoop will re-execute the tool
     * call on resume rather than replaying a stale result, and it prevents the
     * LLM from seeing an orphaned tool-result block without a matching
     * tool-use in the immediately preceding assistant turn.
     *
     * The broader resume-time cleanup (pruneTrailingEmptyAssistants,
     * collapseLastCompletionToolPair in MessageStateHandler) handles
     * complementary cases; this function is responsible only for the trailing
     * USER turn.
     */
    fun popTrailingUserMessage(apiHistory: List<ApiMessage>): PopResult {
        if (apiHistory.isEmpty()) return PopResult(apiHistory, emptyList())
        val last = apiHistory.last()
        if (last.role != ApiRole.USER) {
            return PopResult(trimmedHistory = apiHistory, poppedContent = emptyList())
        }
        // Always pop the trailing USER message, regardless of whether its content
        // is a real user prompt or a tool-result-only block.  Both cases represent
        // an incomplete turn that the loop must rebuild from scratch on resume.
        return PopResult(
            trimmedHistory = apiHistory.dropLast(1),
            poppedContent = last.content
        )
    }

    fun determineResumeAskType(trimmedUiMessages: List<UiMessage>): UiAsk {
        val lastNonResume = trimmedUiMessages.lastOrNull {
            it.ask != UiAsk.RESUME_TASK && it.ask != UiAsk.RESUME_COMPLETED_TASK
        }
        return if (lastNonResume?.ask == UiAsk.COMPLETION_RESULT) {
            UiAsk.RESUME_COMPLETED_TASK
        } else {
            UiAsk.RESUME_TASK
        }
    }

    fun buildTaskResumptionPreamble(
        mode: String,
        agoText: String,
        cwd: String,
        userText: String? = null,
        wasPreviouslyCompleted: Boolean = false,
    ): String {
        val modeStr = if (mode == "plan") "Plan Mode" else "Act Mode"
        return buildString {
            if (wasPreviouslyCompleted) {
                appendLine("[TASK CONTINUATION] The previous task in this conversation was completed $agoText. The conversation history has been preserved and the user is sending a new message — treat it as the start of a new turn, not as a resumption of unfinished work.")
            } else {
                appendLine("[TASK RESUMPTION] This task was interrupted $agoText. The conversation history has been preserved.")
            }
            appendLine("Mode: $modeStr")
            appendLine("Working directory: $cwd")
            if (!userText.isNullOrBlank()) {
                appendLine()
                appendLine("User message on resume: $userText")
            }
            appendLine()
            if (wasPreviouslyCompleted) {
                appendLine("Address the user's new message. Do not redo prior completed work unless the user asks for changes.")
            } else {
                appendLine("Continue where you left off. Do not repeat completed work. If you were mid-task, pick up from the last tool result in the conversation history.")
            }
        }
    }

    /**
     * Format the "background completions delivered on resume" section that is spliced into
     * the [TASK RESUMPTION] preamble. Pure: given the persisted [pending] events, returns the
     * section text to append (with its own leading blank lines), or "" when there are none so
     * the caller appends nothing. The caller owns the side-effecting load + per-event consume.
     */
    fun formatBackgroundCompletionsSection(pending: List<BackgroundCompletionEvent>): String {
        if (pending.isEmpty()) return ""
        val body = pending.joinToString("\n\n") { ev ->
            "- ${ev.bgId} (${ev.kind}: \"${ev.label.take(COMPLETION_LABEL_MAX)}\") — " +
                "exit ${ev.exitCode}, ${ev.state}, ${ev.runtimeMs}ms\n  " +
                ev.tailContent.lines().takeLast(COMPLETION_TAIL_LINES).joinToString("\n  ")
        }
        return "\n\n[BACKGROUND COMPLETIONS — delivered on resume]\n" +
            "While the session was paused, these background processes completed:\n\n" +
            body + "\n"
    }

    /**
     * Format the "cross-IDE delegation results delivered on resume" section. Pure: given the
     * persisted [pendingNudges], returns the section text to append, or "" when there are none.
     * The caller owns the side-effecting load + per-nudge consume.
     */
    fun formatDelegationNudgesSection(pendingNudges: List<DelegationNudge>): String {
        if (pendingNudges.isEmpty()) return ""
        val body = pendingNudges.joinToString("\n\n---\n\n") { it.text }
        return "\n\n[DELEGATION RESULTS — delivered on resume]\n" +
            "While the session was paused, these cross-IDE delegation results/questions " +
            "arrived. Decide whether each needs action; if a question is included, answer " +
            "it via delegation(action=\"answer\"):\n\n" + body + "\n"
    }

    /**
     * Format the "monitor notifications while away" section. Pure: given the persisted
     * [pendingNotifications] lines, returns the section text to append, or "" when there are
     * none. The caller owns the side-effecting load + clear.
     */
    fun formatMonitorNotificationsSection(pendingNotifications: List<String>): String {
        if (pendingNotifications.isEmpty()) return ""
        val body = pendingNotifications.joinToString("\n")
        return "\n\n# Monitor notifications while away\n" +
            "While the session was paused, the following monitor events fired:\n\n" +
            body + "\n"
    }

    data class TrailingEmptyDropResult(
        val history: List<ApiMessage>,
        val droppedCount: Int,
    )

    /**
     * Drop trailing empty/blank-assistant turns left by pre-guard sessions, mirroring
     * `ContextManager.pruneTrailingEmptyAssistants` / `MessageStateHandler.pruneTrailingEmptyAssistants`.
     * Pure: returns the trimmed history + how many turns were dropped (the caller logs). An assistant
     * turn is "empty" when its content list is empty or every block is blank [ContentBlock.Text].
     * Stops at the first non-empty turn from the end, so an empty turn followed by a real one survives.
     */
    fun dropTrailingEmptyAssistants(history: List<ApiMessage>): TrailingEmptyDropResult {
        val trimmed = history.dropLastWhile { msg ->
            msg.role == ApiRole.ASSISTANT &&
                (msg.content.isEmpty() || msg.content.all { it is ContentBlock.Text && it.text.isBlank() })
        }
        return TrailingEmptyDropResult(trimmed, history.size - trimmed.size)
    }

    data class DialectRedactionResult(
        val history: List<ApiMessage>,
        val redactedCount: Int,
    )

    /**
     * Redact incompatible-format tool-call XML (Anthropic `<invoke>`, Hermes `<tool_call>{json}`, etc.)
     * inline on assistant turns before the history is seeded into the resumed session. Pure: delegates
     * each assistant text block to [DialectDriftDetector.redactDialectMarkers] and returns the rewritten
     * history + the count of changed turns (the caller logs + raises the one-shot drift flag when > 0).
     * Unchanged turns (user role, non-text blocks, no dialect) are returned as the SAME instances so the
     * caller can cheaply detect "nothing changed".
     */
    fun redactDialectDriftInHistory(history: List<ApiMessage>): DialectRedactionResult {
        val redacted = history.map { msg ->
            if (msg.role != ApiRole.ASSISTANT) return@map msg
            var changed = false
            val newContent = msg.content.map { block ->
                if (block !is ContentBlock.Text) return@map block
                val r = DialectDriftDetector.redactDialectMarkers(block.text)
                if (r.modified) {
                    changed = true
                    ContentBlock.Text(r.text)
                } else {
                    block
                }
            }
            if (changed) msg.copy(content = newContent) else msg
        }
        val redactedCount = history.zip(redacted).count { (orig, new) -> orig !== new }
        return DialectRedactionResult(redacted, redactedCount)
    }

    /**
     * Build the chat note shown when a `TASK_RESUME` hook cancels a resume. Pure. When the user
     * typed a follow-up alongside the resume ([userText]), it is quoted back so the cancellation
     * isn't silently swallowing their input (mirrors the fix shape from 56906e668 — completed-task
     * resume drop). A null/blank [reason] omits the `": …"` clause; a null/blank [userText] omits
     * the quoted-message block. Each line of [userText] is prefixed with `> `.
     */
    fun buildResumeCancelledNote(reason: String?, userText: String?): String = buildString {
        append("Resume cancelled by TASK_RESUME hook")
        if (!reason.isNullOrBlank()) append(": $reason")
        append('.')
        if (!userText.isNullOrBlank()) {
            append("\n\nYour message was not sent:\n> ")
            append(userText.lineSequence().joinToString("\n> "))
        }
    }

    fun formatTimeAgo(lastActivityTs: Long): String {
        val diffMs = System.currentTimeMillis() - lastActivityTs
        val seconds = diffMs / 1000
        return when {
            seconds < 60 -> "${seconds}s ago"
            seconds < 3600 -> "${seconds / 60} minutes ago"
            seconds < 86400 -> "${seconds / 3600} hours ago"
            else -> "${seconds / 86400} days ago"
        }
    }
}
