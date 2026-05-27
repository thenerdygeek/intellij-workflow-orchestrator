package com.workflow.orchestrator.automation.service

import com.workflow.orchestrator.core.model.bamboo.BuildJobData
import com.workflow.orchestrator.core.model.bamboo.BuildResultData
import com.workflow.orchestrator.core.model.bamboo.BuildStageData
import com.workflow.orchestrator.core.services.BambooService
import com.workflow.orchestrator.core.services.ToolResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins [TagBuilderService.detectDockerTag]'s REST fallback (cache cold) to scan each JOB's
 * log rather than the chain/plan-level log.
 *
 * Root cause it guards: the old code fetched `getBuildLog(build.buildResultKey)` — the
 * chain/plan-level log, which is empty/404 on this Bamboo — and reported "no tag" even when
 * a job carried it. Bamboo's REST job order is also unstable, so the SonarQube job ("SQAN")
 * can be returned first while the docker tag lives in a different job; detection must try
 * every job and stop at the one with the marker.
 */
class TagDetectionPerJobTest {

    private fun build() = BuildResultData(
        planKey = "PROJ-XYZ",
        buildNumber = 10,
        state = "Successful",
        durationSeconds = 100,
        buildResultKey = "PROJ-XYZ-10",
        stages = listOf(
            BuildStageData(
                name = "Build Stage",
                state = "Successful",
                durationSeconds = 100,
                jobs = listOf(
                    // SQAN first (mirrors the unstable order the user hit); tag is NOT here.
                    BuildJobData(name = "SonarQube Analysis", state = "Successful", durationSeconds = 50, resultKey = "PROJ-XYZ-SQAN-10"),
                    // The job that actually emits the docker tag.
                    BuildJobData(name = "Build Artifacts", state = "Successful", durationSeconds = 50, resultKey = "PROJ-XYZ-JOB1-10"),
                ),
            ),
        ),
    )

    @Test
    fun `REST fallback scans each job log and returns the job carrying the tag`() = runTest {
        val bamboo = mockk<BambooService>()
        coEvery { bamboo.getLatestBuild("PROJ-XYZ") } returns ToolResult.success(build(), "ok")
        coEvery { bamboo.getBuildLog("PROJ-XYZ-SQAN-10") } returns ToolResult.success("sonar output, no tag here\n", "ok")
        coEvery { bamboo.getBuildLog("PROJ-XYZ-JOB1-10") } returns ToolResult.success("Unique Docker Tag : feature-xyz-42\n", "ok")

        // cache cold → REST fallback
        val svc = TagBuilderService(bambooService = bamboo, buildLogCache = null)
        val result = svc.detectDockerTag("PROJ-XYZ")

        assertTrue(result.detected)
        assertEquals("feature-xyz-42", result.tag)
        assertEquals("PROJ-XYZ-JOB1-10", result.buildKey)   // reports the job that had the tag
        // Must never fetch the useless chain/plan-level log.
        coVerify(exactly = 0) { bamboo.getBuildLog("PROJ-XYZ-10") }
    }

    @Test
    fun `REST fallback reports the build key when no job log has the tag`() = runTest {
        val bamboo = mockk<BambooService>()
        coEvery { bamboo.getLatestBuild("PROJ-XYZ") } returns ToolResult.success(build(), "ok")
        coEvery { bamboo.getBuildLog(any()) } returns ToolResult.success("no marker anywhere\n", "ok")

        val svc = TagBuilderService(bambooService = bamboo, buildLogCache = null)
        val result = svc.detectDockerTag("PROJ-XYZ")

        assertFalse(result.detected)
        assertEquals("PROJ-XYZ-10", result.buildKey)
    }
}
