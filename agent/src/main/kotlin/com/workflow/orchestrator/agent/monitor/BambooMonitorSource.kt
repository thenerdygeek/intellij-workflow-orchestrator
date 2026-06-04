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
        val res = bamboo.getLatestBuild(ck)
        return if (res.isError) null else res.data
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
