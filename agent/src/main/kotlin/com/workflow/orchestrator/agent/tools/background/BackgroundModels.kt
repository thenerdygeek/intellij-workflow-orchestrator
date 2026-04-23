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
