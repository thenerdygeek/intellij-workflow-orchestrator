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
 */
object BambooBuildStructureMapper {
    fun toBuildState(dto: BambooResultDto, planKey: String, branch: String): BuildState {
        val jobs = mutableListOf<StageState>()
        for (stage in dto.stages.stage) {
            val jobResults = stage.results.result
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
