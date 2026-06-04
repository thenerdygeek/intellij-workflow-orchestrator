package com.workflow.orchestrator.agent.monitor

import com.workflow.orchestrator.core.model.bamboo.BuildResultData
import com.workflow.orchestrator.core.model.bamboo.BuildStageData

/** Pure state-transition logic for the Bamboo monitor. Unit-testable with zero IDE. */
object BambooDiff {
    enum class Level { BUILD, STAGE, JOB }

    /** Terminal states — first-poll emits only for these. */
    private val TERMINAL_STATES = setOf("failed", "successful")

    /** Severity for a given build/stage/job state. Failed → ALERT; anything else → NOTABLE. */
    private fun severityForState(state: String): Severity = when (state.lowercase()) {
        "failed" -> Severity.ALERT
        else -> Severity.NOTABLE
    }

    private fun isTerminal(state: String) = state.lowercase() in TERMINAL_STATES

    /**
     * Main entry point.  Delegates to the appropriate sub-differ based on [level].
     */
    fun diff(
        monitorId: String,
        level: Level,
        stageName: String?,
        jobName: String?,
        previous: BuildResultData?,
        current: BuildResultData,
    ): List<MonitorEvent> = when (level) {
        Level.BUILD -> diffBuild(monitorId, previous, current)
        Level.STAGE -> diffStage(monitorId, stageName, previous, current)
        Level.JOB   -> diffJob(monitorId, stageName, jobName, previous, current)
    }

    // -------------------------------------------------------------------------
    // BUILD-level diff
    // -------------------------------------------------------------------------

    private fun diffBuild(
        monitorId: String,
        previous: BuildResultData?,
        current: BuildResultData,
    ): List<MonitorEvent> {
        val changed = previous == null
            || previous.state != current.state
            || previous.lifeCycleState != current.lifeCycleState

        if (!changed) return emptyList()

        // First poll — only emit when already in a terminal state.
        if (previous == null && !isTerminal(current.state)) return emptyList()

        val line = "Bamboo ${current.planKey} #${current.buildNumber}: " +
            "${current.lifeCycleState}/${current.state} " +
            "(tests ${current.testsPassed}P/${current.testsFailed}F)"
        return listOf(MonitorEvent(monitorId, severityForState(current.state), line))
    }

    // -------------------------------------------------------------------------
    // STAGE-level diff
    // -------------------------------------------------------------------------

    private fun diffStage(
        monitorId: String,
        stageName: String?,
        previous: BuildResultData?,
        current: BuildResultData,
    ): List<MonitorEvent> {
        val curStage = current.stages.firstOrNull { it.name.equals(stageName, ignoreCase = true) }
            ?: return emptyList()   // stage not present in current → no event

        val prevStage = previous?.stages?.firstOrNull { it.name.equals(stageName, ignoreCase = true) }

        val changed = prevStage == null || prevStage.state != curStage.state
        if (!changed) return emptyList()

        // First poll — only emit for terminal stage state.
        if (prevStage == null && !isTerminal(curStage.state)) return emptyList()

        val line = "Bamboo stage '${curStage.name}' → ${curStage.state} " +
            "(build ${current.planKey} #${current.buildNumber})"
        return listOf(MonitorEvent(monitorId, severityForState(curStage.state), line))
    }

    // -------------------------------------------------------------------------
    // JOB-level diff
    // -------------------------------------------------------------------------

    private fun diffJob(
        monitorId: String,
        stageName: String?,
        jobName: String?,
        previous: BuildResultData?,
        current: BuildResultData,
    ): List<MonitorEvent> {
        val curStage = current.stages.firstOrNull { it.name.equals(stageName, ignoreCase = true) }
            ?: return emptyList()

        val curJob = curStage.jobs.firstOrNull { it.name.equals(jobName, ignoreCase = true) }
            ?: return emptyList()

        val prevJob = previous
            ?.stages?.firstOrNull { it.name.equals(stageName, ignoreCase = true) }
            ?.jobs?.firstOrNull { it.name.equals(jobName, ignoreCase = true) }

        val changed = prevJob == null || prevJob.state != curJob.state
        if (!changed) return emptyList()

        // First poll — only emit for terminal job state.
        if (prevJob == null && !isTerminal(curJob.state)) return emptyList()

        val line = "Bamboo job '${curJob.name}' in stage '${curStage.name}' → ${curJob.state} " +
            "(build ${current.planKey} #${current.buildNumber})"
        return listOf(MonitorEvent(monitorId, severityForState(curJob.state), line))
    }
}
