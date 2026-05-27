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
}
