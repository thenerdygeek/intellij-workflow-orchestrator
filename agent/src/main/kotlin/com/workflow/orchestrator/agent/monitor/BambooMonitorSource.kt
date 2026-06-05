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
 * 1. Call [BambooService.getRunningBuilds] on the resolved chain key.
 * 2. If that returns an error OR an empty list, AND a distinct master plan key exists (i.e.
 *    [branch] is non-null so `chainKey != planKey`), retry [BambooService.getRunningBuilds]
 *    with the master plan key (branch-chain keys can 404 the `includeAllStates` endpoint).
 * 3. If a live build was found, return the one with the highest `buildNumber` (most-recent
 *    in-flight build) so BambooDiff sees Queued / InProgress and the eventual Finished transition.
 * 4. Otherwise fall back to [BambooService.getLatestBuild] (most-recent finished build).
 *    Returns null on error so SmartPoller backs off without resetting the snapshot.
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

        // masterPlanKey is non-null only when chainKey is a branch-chain key (e.g. PROJ-PLAN523);
        // for trunk builds chainKey == planKey so there is no distinct master to retry.
        val masterPlanKey = if (ck != planKey) planKey else null

        pickRunningBuild(ck, masterPlanKey)?.let { return it }

        // No live build — fall back to the most-recent finished build.
        val res = bamboo.getLatestBuild(ck)
        return if (res.isError) null else res.data
    }

    /**
     * Returns the running/queued build with the highest `buildNumber`, or null when none exist.
     *
     * Mirrors the `activeBuildsOrWarning` composite in `BambooBuildsTool`:
     * - Primary attempt on [chainKey].
     * - If that errors OR is empty and [masterPlanKey] is non-null, retry on [masterPlanKey].
     * - Running errors do NOT propagate — a null return lets `fetch()` fall back to
     *   `getLatestBuild` so a transient endpoint failure never breaks the poll.
     */
    internal suspend fun pickRunningBuild(chainKey: String, masterPlanKey: String?): BuildResultData? {
        val primary = bamboo.getRunningBuilds(chainKey)
        if (!primary.isError) {
            val builds = primary.data.orEmpty()
            if (builds.isNotEmpty()) return builds.maxByOrNull { it.buildNumber }
        }

        // masterPlanKey is pre-guaranteed distinct from chainKey by the caller (set only when ck != planKey).
        if (masterPlanKey != null) {
            val secondary = bamboo.getRunningBuilds(masterPlanKey)
            if (!secondary.isError) {
                val builds = secondary.data.orEmpty()
                if (builds.isNotEmpty()) return builds.maxByOrNull { it.buildNumber }
            }
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
}
