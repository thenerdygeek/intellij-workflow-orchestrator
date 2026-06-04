package com.workflow.orchestrator.agent.monitor

/** Importance of a monitor event. Drives wake eligibility (design spec §4). */
enum class Severity { INFO, NOTABLE, ALERT }

/**
 * A normalized event produced by a [MonitorSource]. [line] is the human-readable
 * one-liner shown in the conversation; [payload] carries optional structured detail
 * for future domain sources.
 */
data class MonitorEvent(
    val monitorId: String,
    val severity: Severity,
    val line: String,
    val payload: Any? = null,
) {
    /** INFO never spends wake budget on its own; NOTABLE/ALERT may wake an idle loop. */
    val wakeEligible: Boolean get() = severity != Severity.INFO

    fun formatLine(): String = "[monitor $monitorId · ${severity.name}] $line"
}
