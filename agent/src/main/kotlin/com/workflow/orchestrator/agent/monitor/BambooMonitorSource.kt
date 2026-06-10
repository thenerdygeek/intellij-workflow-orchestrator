package com.workflow.orchestrator.agent.monitor

import com.workflow.orchestrator.core.model.bamboo.BuildResultData
import com.workflow.orchestrator.core.services.BambooService
import kotlinx.coroutines.CoroutineScope

/**
 * Polls Bamboo for the latest build of a plan (or plan branch) and emits [MonitorEvent]s
 * when the build's state transitions.  Supports three granularity levels:
 *
 * - [BambooDiff.Level.BUILD]  — watch the overall build state.
 * - [BambooDiff.Level.STAGE]  — watch a specific stage (matched by [stageName], case-insensitive).
 * - [BambooDiff.Level.JOB]    — watch a specific job inside a stage.
 *
 * The [chainKey] is resolved lazily: when [branch] is null the [planKey] itself is used as the
 * chain key; when [branch] is non-null the first matching `PlanBranchData.name` or `.shortName`
 * (case-insensitive) from [BambooService.getPlanBranches] is used.
 *
 * ## Composite poll (running-first, finished-fallback)
 *
 * Bamboo's `/result/{key}/latest` endpoint returns ONLY the most-recent FINISHED build — a build
 * that is currently queued or in progress is invisible to it.  To let the monitor observe the full
 * Queued → InProgress → Finished lifecycle, `fetch()` uses the following strategy:
 *
 * 1. Call [BambooService.getRunningBuilds] on the resolved chain key.  Branch builds live under
 *    the branch plan key (the resolved [chainKey], e.g. `PROJ-PLAN42`) — this is the only key
 *    queried; there is no master-plan-key fallback.
 * 2. If a live build was found, return the one with the highest `buildNumber` (most-recent
 *    in-flight build) so BambooDiff sees Queued / InProgress and the eventual Finished transition.
 * 3. Otherwise (error or empty) fall back to [BambooService.getLatestBuild] on the SAME
 *    [chainKey] (most-recent finished build).  Returns null on error so SmartPoller backs off
 *    without resetting the snapshot.
 */
class BambooMonitorSource(
    monitorId: String,
    description: String,
    private val bamboo: BambooService,
    private val planKey: String,
    private val branch: String?,
    private val level: BambooDiff.Level,
    private val stageName: String?,
    private val jobName: String?,
    cs: CoroutineScope,
) : PollingSource<BuildResultData>(monitorId, description, cs) {

    @Volatile private var chainKey: String? = null

    override suspend fun fetch(): BuildResultData? {
        val ck = chainKey ?: resolveChainKey()?.also { chainKey = it } ?: return null

        pickRunningBuild(ck)?.let { return it }

        // No live build — fall back to the most-recent finished build on the SAME chainKey.
        val res = bamboo.getLatestBuild(ck)
        return if (res.isError) null else res.data
    }

    /**
     * Returns the running/queued build with the highest `buildNumber`, or null when none exist.
     *
     * Mirrors the `activeBuildsOrWarning` composite in `BambooBuildsTool`: queries
     * [BambooService.getRunningBuilds] on [chainKey] once.  Branch builds live under the branch
     * plan key ([chainKey]) — there is no master-plan-key fallback.  Running errors do NOT
     * propagate — a null return lets `fetch()` fall back to `getLatestBuild` so a transient
     * endpoint failure never breaks the poll.
     */
    internal suspend fun pickRunningBuild(chainKey: String): BuildResultData? {
        val result = bamboo.getRunningBuilds(chainKey)
        if (!result.isError) {
            val builds = result.data.orEmpty()
            if (builds.isNotEmpty()) return builds.maxByOrNull { it.buildNumber }
        }
        return null
    }

    private suspend fun resolveChainKey(): String? {
        if (branch == null) return planKey
        val res = bamboo.getPlanBranches(planKey)
        if (res.isError) return null
        return res.data?.firstOrNull {
            it.name.equals(branch, ignoreCase = true) || it.shortName.equals(branch, ignoreCase = true)
        }?.key
    }

    override fun diff(previous: BuildResultData?, current: BuildResultData): List<MonitorEvent> =
        BambooDiff.diff(monitorId, level, stageName, jobName, previous, current)

    /**
     * P1-8 terminal auto-stop: a build whose overall state is Successful/Failed (the same
     * terminal notion [BambooDiff] uses) can no longer change — [PollingSource.pollOnce]
     * stops this source after observing the transition into that state. Stage/job-level
     * monitors also key off the OVERALL build state: once the build is finished, no stage
     * or job can transition any further.
     */
    override fun isTerminal(current: BuildResultData): Boolean =
        BambooDiff.isTerminalState(current.state)
}
