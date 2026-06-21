package com.workflow.orchestrator.agent.ui

import com.workflow.orchestrator.agent.monitor.Severity
import com.workflow.orchestrator.agent.session.AsyncEventCardData
import com.workflow.orchestrator.agent.session.AsyncEventKind
import com.workflow.orchestrator.agent.session.AsyncEventStatus
import com.workflow.orchestrator.agent.tools.background.BackgroundCompletionEvent
import com.workflow.orchestrator.agent.tools.background.BackgroundState
import kotlinx.serialization.json.Json

/** Pure mapping of producer events → [AsyncEventCardData] timeline cards. */
object AsyncEventCardPresenter {

    private const val LABEL_MAX = 80

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * JSON-encode a card for transport in `QueuedMessage.meta["card"]`. Single encode path for all
     * producers; symmetric with the decode in [AsyncEventResumeSynthesis].
     */
    fun encodeCard(card: AsyncEventCardData): String = json.encodeToString(AsyncEventCardData.serializer(), card)

    fun fromBackground(e: BackgroundCompletionEvent): AsyncEventCardData {
        val status = if (e.state == BackgroundState.EXITED && e.exitCode == 0)
            AsyncEventStatus.SUCCESS else AsyncEventStatus.FAILURE
        val stateLabel = when (e.state) {
            BackgroundState.EXITED -> if (e.exitCode == 0) "exit 0" else "exit ${e.exitCode}"
            BackgroundState.KILLED -> "killed"
            BackgroundState.TIMED_OUT -> "timed out"
            BackgroundState.RUNNING -> "finished"
        }
        return AsyncEventCardData(
            id = "bg-${e.bgId}-${e.occurredAt}",
            kind = AsyncEventKind.BACKGROUND,
            sourceId = e.bgId,
            label = e.label.take(LABEL_MAX),
            status = status,
            summary = "$stateLabel · ${e.runtimeMs / 1000}s",
            details = e.tailContent,
            timestamp = e.occurredAt,
            spillPath = e.spillPath,
        )
    }

    /**
     * Card for a backgrounded *tool* completion. Distinct from [fromBackground] (which maps an OS-process
     * [BackgroundCompletionEvent]); a backgrounded tool delivers a [com.workflow.orchestrator.agent.tools.ToolResult].
     * Centralizes the `bg-{id}-{ts}` id format + SUCCESS/FAILURE mapping here, alongside the other producers.
     */
    fun fromToolResult(
        toolCallId: String,
        toolName: String,
        isError: Boolean,
        summary: String,
        details: String,
        spillPath: String?,
        occurredAt: Long,
    ): AsyncEventCardData = AsyncEventCardData(
        id = "bg-$toolCallId-$occurredAt",
        kind = AsyncEventKind.BACKGROUND,
        sourceId = toolCallId,
        label = toolName.take(LABEL_MAX),
        status = if (isError) AsyncEventStatus.FAILURE else AsyncEventStatus.SUCCESS,
        summary = summary,
        details = details,
        timestamp = occurredAt,
        spillPath = spillPath,
    )

    fun fromMonitor(monitorId: String, severity: Severity, text: String, ts: Long): AsyncEventCardData {
        val status = when (severity) {
            Severity.ALERT -> AsyncEventStatus.ALERT
            else -> AsyncEventStatus.NOTABLE
        }
        val lines = text.lines()
        val lineCount = lines.count { it.isNotBlank() }
        return AsyncEventCardData(
            id = "mon-$monitorId-$ts",
            kind = AsyncEventKind.MONITOR,
            sourceId = monitorId,
            label = monitorId,
            status = status,
            summary = if (lineCount <= 1) lines.firstOrNull()?.take(LABEL_MAX) ?: "" else "$lineCount events",
            details = text,
            timestamp = ts,
            spillPath = null,
        )
    }
}
