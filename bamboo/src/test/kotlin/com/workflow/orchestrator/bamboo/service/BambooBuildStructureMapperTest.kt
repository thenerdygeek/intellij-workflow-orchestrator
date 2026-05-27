package com.workflow.orchestrator.bamboo.service

import com.workflow.orchestrator.bamboo.api.dto.BambooJobResultCollection
import com.workflow.orchestrator.bamboo.api.dto.BambooJobResultDto
import com.workflow.orchestrator.bamboo.api.dto.BambooPlanDto
import com.workflow.orchestrator.bamboo.api.dto.BambooResultDto
import com.workflow.orchestrator.bamboo.api.dto.BambooStageCollection
import com.workflow.orchestrator.bamboo.api.dto.BambooStageDto
import com.workflow.orchestrator.bamboo.model.BuildStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BambooBuildStructureMapperTest {
    @Test fun `stage with jobs flattens to per-job StageState carrying buildResultKey`() {
        val dto = BambooResultDto(
            buildNumber = 42, state = "Successful", lifeCycleState = "Finished",
            stages = BambooStageCollection(stage = listOf(
                BambooStageDto(name = "Build", state = "Successful", manual = false,
                    results = BambooJobResultCollection(result = listOf(
                        BambooJobResultDto(buildResultKey = "PROJ-PLAN138-UNIT-42", state = "Successful",
                            lifeCycleState = "Finished", buildDurationInSeconds = 5,
                            plan = BambooPlanDto(key = "PROJ-PLAN138-UNIT", name = "Unit", shortName = "Unit"))
                    )))
            ))
        )
        val state = BambooBuildStructureMapper.toBuildState(dto, planKey = "PROJ-PLAN138", branch = "feature/x")
        assertEquals("PROJ-PLAN138", state.planKey)
        assertEquals(42, state.buildNumber)
        assertEquals(1, state.stages.size)
        assertEquals("Unit", state.stages[0].name)
        assertEquals("PROJ-PLAN138-UNIT-42", state.stages[0].resultKey)
        assertEquals("Build", state.stages[0].stageName)
        assertEquals(BuildStatus.SUCCESS, state.overallStatus)
    }

    @Test fun `stage with no jobs maps to a stage-level StageState with blank resultKey`() {
        val dto = BambooResultDto(buildNumber = 7, state = "Failed",
            stages = BambooStageCollection(stage = listOf(
                BambooStageDto(name = "Deploy", state = "Failed", manual = true))))
        val state = BambooBuildStructureMapper.toBuildState(dto, "PROJ-PLAN138", "feature/x")
        assertEquals(1, state.stages.size)
        assertEquals("Deploy", state.stages[0].name)
        assertTrue(state.stages[0].resultKey.isBlank())
        assertTrue(state.stages[0].manual)
    }

    @Test fun `empty stages maps to empty stage list`() {
        val state = BambooBuildStructureMapper.toBuildState(
            BambooResultDto(buildNumber = 1, state = "Unknown"), "PROJ-PLAN138", "feature/x")
        assertTrue(state.stages.isEmpty())
    }

    private fun job(short: String) = BambooJobResultDto(
        buildResultKey = "PROJ-PLAN138-$short-42", state = "Successful", lifeCycleState = "Finished",
        buildDurationInSeconds = 1, plan = BambooPlanDto(key = "PROJ-PLAN138-$short", name = short, shortName = short),
    )

    @Test fun `jobs are reordered to the plan-defined order when a job order map is supplied`() {
        // Result returns jobs in Bamboo's unstable order: SonarQube, Build Artifacts, OSS.
        val dto = BambooResultDto(
            buildNumber = 42, state = "Successful", lifeCycleState = "Finished",
            stages = BambooStageCollection(stage = listOf(
                BambooStageDto(name = "Build Stage", state = "Successful", manual = false,
                    results = BambooJobResultCollection(result = listOf(
                        job("SonarQube Analysis"), job("Build Artifacts"), job("OSS Analysis"),
                    )))
            ))
        )
        val order = mapOf("Build Stage" to listOf("Build Artifacts", "OSS Analysis", "SonarQube Analysis"))
        val state = BambooBuildStructureMapper.toBuildState(dto, "PROJ-PLAN138", "feature/x", order)
        assertEquals(
            listOf("Build Artifacts", "OSS Analysis", "SonarQube Analysis"),
            state.stages.map { it.name },
        )
    }

    @Test fun `unmatched jobs keep their relative order at the end (stable sort)`() {
        val dto = BambooResultDto(
            buildNumber = 42, state = "Successful", lifeCycleState = "Finished",
            stages = BambooStageCollection(stage = listOf(
                BambooStageDto(name = "Build Stage", state = "Successful", manual = false,
                    results = BambooJobResultCollection(result = listOf(
                        job("Mystery A"), job("Build Artifacts"), job("Mystery B"),
                    )))
            ))
        )
        // Only "Build Artifacts" is in the defined order; the two unknowns keep A-before-B.
        val order = mapOf("Build Stage" to listOf("Build Artifacts"))
        val state = BambooBuildStructureMapper.toBuildState(dto, "PROJ-PLAN138", "feature/x", order)
        assertEquals(listOf("Build Artifacts", "Mystery A", "Mystery B"), state.stages.map { it.name })
    }

    @Test fun `no order map preserves the result's job order`() {
        val dto = BambooResultDto(
            buildNumber = 42, state = "Successful", lifeCycleState = "Finished",
            stages = BambooStageCollection(stage = listOf(
                BambooStageDto(name = "Build Stage", state = "Successful", manual = false,
                    results = BambooJobResultCollection(result = listOf(
                        job("SonarQube Analysis"), job("Build Artifacts"),
                    )))
            ))
        )
        val state = BambooBuildStructureMapper.toBuildState(dto, "PROJ-PLAN138", "feature/x")
        assertEquals(listOf("SonarQube Analysis", "Build Artifacts"), state.stages.map { it.name })
    }
}
