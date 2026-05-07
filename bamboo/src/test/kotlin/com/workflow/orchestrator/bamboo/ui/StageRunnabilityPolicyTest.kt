package com.workflow.orchestrator.bamboo.ui

import com.workflow.orchestrator.bamboo.model.BuildStatus
import com.workflow.orchestrator.bamboo.model.StageState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [StageRunnabilityPolicy] (PR 7 audit P1 #2).
 *
 * Five-case matrix on a 3-stage plan:
 *  1. all prior successful + manual stage pending → runnable
 *  2. one prior failed → blocked
 *  3. one prior in-progress (the canonical "in-progress middle stage" case) → blocked
 *  4. one prior pending (still queued) → blocked
 *  5. manual stage itself in-progress → blocked
 *
 * Plus header rows + non-manual auto stages, plus the [firstBlockingStage]
 * tooltip helper.
 */
class StageRunnabilityPolicyTest {

    // Convenience: make a single-job stage with the given status.
    private fun job(stage: String, status: BuildStatus, manual: Boolean = false): StageState =
        StageState(
            name = "$stage-job",
            status = status,
            manual = manual,
            durationMs = null,
            stageName = stage
        )

    private fun header(stage: String): StageState =
        StageState(
            name = "${StageRunnabilityPolicy.HEADER_PREFIX}$stage",
            status = BuildStatus.UNKNOWN,
            manual = false,
            durationMs = null,
            stageName = stage
        )

    @Test
    fun `case 1 - all prior successful and manual stage pending - runnable`() {
        val build = job("Build", BuildStatus.SUCCESS)
        val test = job("Test", BuildStatus.SUCCESS)
        val deploy = job("Deploy", BuildStatus.PENDING, manual = true)
        val stages = listOf(build, test, deploy)

        assertTrue(StageRunnabilityPolicy.isNextRunnable(stages, deploy))
        assertNull(StageRunnabilityPolicy.firstBlockingStage(stages, deploy))
    }

    @Test
    fun `case 2 - one prior failed - blocked`() {
        val build = job("Build", BuildStatus.SUCCESS)
        val test = job("Test", BuildStatus.FAILED)
        val deploy = job("Deploy", BuildStatus.PENDING, manual = true)
        val stages = listOf(build, test, deploy)

        assertFalse(StageRunnabilityPolicy.isNextRunnable(stages, deploy))
        assertEquals("Test", StageRunnabilityPolicy.firstBlockingStage(stages, deploy))
    }

    @Test
    fun `case 3 - one prior in-progress middle stage - blocked`() {
        val build = job("Build", BuildStatus.SUCCESS)
        val test = job("Test", BuildStatus.IN_PROGRESS)
        val deploy = job("Deploy", BuildStatus.PENDING, manual = true)
        val stages = listOf(build, test, deploy)

        assertFalse(StageRunnabilityPolicy.isNextRunnable(stages, deploy))
        assertEquals("Test", StageRunnabilityPolicy.firstBlockingStage(stages, deploy))
    }

    @Test
    fun `case 4 - one prior still pending - blocked`() {
        val build = job("Build", BuildStatus.SUCCESS)
        val test = job("Test", BuildStatus.PENDING)
        val deploy = job("Deploy", BuildStatus.PENDING, manual = true)
        val stages = listOf(build, test, deploy)

        assertFalse(StageRunnabilityPolicy.isNextRunnable(stages, deploy))
        assertEquals("Test", StageRunnabilityPolicy.firstBlockingStage(stages, deploy))
    }

    @Test
    fun `case 5 - manual stage itself in-progress - blocked`() {
        val build = job("Build", BuildStatus.SUCCESS)
        val test = job("Test", BuildStatus.SUCCESS)
        val deploy = job("Deploy", BuildStatus.IN_PROGRESS, manual = true)
        val stages = listOf(build, test, deploy)

        assertFalse(StageRunnabilityPolicy.isNextRunnable(stages, deploy))
        // firstBlockingStage returns null for in-progress targets — the user
        // can't act on it anyway.
        assertNull(StageRunnabilityPolicy.firstBlockingStage(stages, deploy))
    }

    @Test
    fun `header row never runnable`() {
        val stages = listOf(header("Build"), job("Build", BuildStatus.SUCCESS))
        assertFalse(StageRunnabilityPolicy.isNextRunnable(stages, stages[0]))
    }

    @Test
    fun `non-manual stage never runnable`() {
        val build = job("Build", BuildStatus.SUCCESS)
        val test = job("Test", BuildStatus.PENDING, manual = false)
        val stages = listOf(build, test)

        assertFalse(StageRunnabilityPolicy.isNextRunnable(stages, test))
    }

    @Test
    fun `multiple jobs in same prior stage all-pass is runnable`() {
        // Stage "Build" has two jobs (both SUCCESS), Stage "Deploy" is manual.
        val buildA = job("Build", BuildStatus.SUCCESS).copy(name = "Build-A")
        val buildB = job("Build", BuildStatus.SUCCESS).copy(name = "Build-B")
        val deploy = job("Deploy", BuildStatus.PENDING, manual = true)
        val stages = listOf(buildA, buildB, deploy)

        assertTrue(StageRunnabilityPolicy.isNextRunnable(stages, deploy))
    }

    @Test
    fun `multiple jobs in same prior stage one-fail is blocked`() {
        val buildA = job("Build", BuildStatus.SUCCESS).copy(name = "Build-A")
        val buildB = job("Build", BuildStatus.FAILED).copy(name = "Build-B")
        val deploy = job("Deploy", BuildStatus.PENDING, manual = true)
        val stages = listOf(buildA, buildB, deploy)

        assertFalse(StageRunnabilityPolicy.isNextRunnable(stages, deploy))
        assertEquals("Build", StageRunnabilityPolicy.firstBlockingStage(stages, deploy))
    }

    @Test
    fun `first manual stage with no priors is runnable`() {
        val deploy = job("Deploy", BuildStatus.PENDING, manual = true)
        val stages = listOf(deploy)

        assertTrue(StageRunnabilityPolicy.isNextRunnable(stages, deploy))
    }
}
