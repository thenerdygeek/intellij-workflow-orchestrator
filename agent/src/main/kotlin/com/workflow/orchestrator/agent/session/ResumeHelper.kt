package com.workflow.orchestrator.agent.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

object ResumeHelper {

    private val json = Json { ignoreUnknownKeys = true }

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

    fun popTrailingUserMessage(apiHistory: List<ApiMessage>): PopResult {
        if (apiHistory.isEmpty()) return PopResult(apiHistory, emptyList())
        val last = apiHistory.last()
        return if (last.role == ApiRole.USER) {
            PopResult(
                trimmedHistory = apiHistory.dropLast(1),
                poppedContent = last.content
            )
        } else {
            PopResult(trimmedHistory = apiHistory, poppedContent = emptyList())
        }
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
    ): String {
        val modeStr = if (mode == "plan") "Plan Mode" else "Act Mode"
        return buildString {
            appendLine("[TASK RESUMPTION] This task was interrupted $agoText. The conversation history has been preserved.")
            appendLine("Mode: $modeStr")
            appendLine("Working directory: $cwd")
            if (!userText.isNullOrBlank()) {
                appendLine()
                appendLine("User message on resume: $userText")
            }
            appendLine()
            appendLine("Continue where you left off. Do not repeat completed work. If you were mid-task, pick up from the last tool result in the conversation history.")
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
