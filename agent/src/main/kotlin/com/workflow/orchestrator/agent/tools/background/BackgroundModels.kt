package com.workflow.orchestrator.agent.tools.background

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class BackgroundState {
    @SerialName("running")   RUNNING,
    @SerialName("exited")    EXITED,
    @SerialName("killed")    KILLED,
    @SerialName("timed_out") TIMED_OUT,
}

@Serializable
data class BackgroundCompletionEvent(
    val bgId: String,
    val kind: String,
    val label: String,
    val sessionId: String,
    val exitCode: Int,
    val state: BackgroundState,
    val runtimeMs: Long,
    val tailContent: String,
    val spillPath: String?,
    val occurredAt: Long,
)

/**
 * BUG #2 — a cross-IDE delegation result / clarifying-question nudge persisted for an
 * idle delegator session so it REPLAYS on the next resume instead of being dropped when
 * the auto-wake guard rejects (cooldown / cap / disabled / no-listener / different active
 * session). Unlike [BackgroundCompletionEvent] this is plain text (delegation nudges are
 * already-formatted strings, with no bgId/exitCode/state), so it carries only an [id]
 * (for single-entry consume), the [text], and a timestamp.
 */
@Serializable
data class DelegationNudge(
    val id: String,
    val text: String,
    val occurredAt: Long,
)

@Serializable
data class BackgroundProcessSnapshot(
    val bgId: String,
    val kind: String,
    val label: String,
    val state: BackgroundState,
    val startedAt: Long,
    val exitCode: Int?,
    val outputBytes: Long,
    val runtimeMs: Long,
)
