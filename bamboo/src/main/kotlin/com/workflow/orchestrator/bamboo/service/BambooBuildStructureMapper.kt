package com.workflow.orchestrator.bamboo.service

import com.workflow.orchestrator.bamboo.api.dto.BambooResultDto
import com.workflow.orchestrator.bamboo.model.BuildState
import com.workflow.orchestrator.bamboo.model.BuildStatus
import com.workflow.orchestrator.bamboo.model.StageState
import java.time.Instant

/**
 * Single mapper from Bamboo's expanded result DTO (`?expand=stages.stage.results.result`)
 * to the UI [BuildState]. Each job becomes a [StageState] tagged with its parent stage
 * name and its authoritative `buildResultKey` (used verbatim for per-job log fetch).
 *
 * Consolidates the previously-duplicated mappings in BuildMonitorService, BambooServiceImpl,
 * and BuildDashboardPanel.loadHistoricalBuild.
 *
 * [jobOrder] (stageName -> ordered job shortNames, from [BambooPlanJobOrder.fromConfig]) lets
 * the caller restore the plan-defined job order: Bamboo's result endpoint returns a stage's
 * jobs in an UNSTABLE order, so without this the Build tab shows jobs in whatever order the
 * API happened to return them. Stages already come correctly ordered, so only the jobs WITHIN
 * each stage are sorted. Matching is by job shortName; unmatched jobs (or an empty/missing
 * order map) keep their original relative order — the sort is stable, so this can never drop
 * or duplicate a job.
 */
object BambooBuildStructureMapper {
    fun toBuildState(
        dto: BambooResultDto,
        planKey: String,
        branch: String,
        jobOrder: Map<String, List<String>> = emptyMap(),
    ): BuildState {
        val jobs = mutableListOf<StageState>()
        for (stage in dto.stages.stage) {
            val definedOrder = jobOrder[stage.name]
            val jobResults = if (definedOrder.isNullOrEmpty()) {
                stage.results.result
            } else {
                // Stable sort by the job's index in the plan-defined order; unmatched jobs
                // (index -1) sort to the end while keeping their relative order.
                stage.results.result.sortedBy { job ->
                    val name = job.plan?.shortName ?: job.buildResultKey
                    definedOrder.indexOf(name).let { if (it < 0) Int.MAX_VALUE else it }
                }
            }
            if (jobResults.isNotEmpty()) {
                for (job in jobResults) {
                    val jobName = job.plan?.shortName ?: job.buildResultKey
                    jobs.add(StageState(
                        name = jobName,
                        status = BuildStatus.fromBambooState(job.state, job.lifeCycleState),
                        manual = stage.manual,
                        durationMs = job.buildDurationInSeconds * 1000,
                        stageName = stage.name,
                        resultKey = job.buildResultKey,
                    ))
                }
            } else {
                jobs.add(StageState(
                    name = stage.name,
                    status = BuildStatus.fromBambooState(stage.state, stage.lifeCycleState),
                    manual = stage.manual,
                    durationMs = stage.buildDurationInSeconds * 1000,
                    stageName = stage.name,
                    resultKey = "",
                ))
            }
        }
        return BuildState(
            planKey = planKey,
            branch = branch,
            buildNumber = dto.buildNumber,
            stages = jobs,
            overallStatus = BuildStatus.fromBambooState(dto.state, dto.lifeCycleState),
            lastUpdated = Instant.now(),
        )
    }
}
