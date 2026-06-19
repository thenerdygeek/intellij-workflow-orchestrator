package com.workflow.orchestrator.agent.ui

import com.workflow.orchestrator.agent.monitor.Severity
import com.workflow.orchestrator.agent.session.AsyncEventCardData
import com.workflow.orchestrator.agent.session.AsyncEventKind
import com.workflow.orchestrator.agent.session.AsyncEventStatus
import com.workflow.orchestrator.agent.tools.background.BackgroundCompletionEvent
import com.workflow.orchestrator.agent.tools.background.BackgroundState

/** Pure mapping of producer events → [AsyncEventCardData] timeline cards. */
object AsyncEventCardPresenter {

    private const val LABEL_MAX = 80

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

    fun fromMonitor(monitorId: String, severity: Severity, text: String, ts: Long): AsyncEventCardData {
        val status = when (severity) {
            Severity.ALERT -> AsyncEventStatus.ALERT
            else -> AsyncEventStatus.NOTABLE
        }
        val lineCount = text.lines().count { it.isNotBlank() }
        return AsyncEventCardData(
            id = "mon-$monitorId-$ts",
            kind = AsyncEventKind.MONITOR,
            sourceId = monitorId,
            label = monitorId,
            status = status,
            summary = if (lineCount <= 1) text.lines().firstOrNull()?.take(LABEL_MAX) ?: "" else "$lineCount events",
            details = text,
            timestamp = ts,
            spillPath = null,
        )
    }
}
