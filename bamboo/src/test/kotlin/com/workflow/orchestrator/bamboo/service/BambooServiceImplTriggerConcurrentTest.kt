package com.workflow.orchestrator.bamboo.service

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regression: triggering a build while one is already running used to surface
 * Bamboo's raw "max 1 concurrent build is possible" body paired with the generic
 * "Check Bamboo connection in Settings." hint (the 400 maps to SERVER_ERROR, which
 * fell through to the catch-all hint). That combination confused the agent.
 *
 * [BambooServiceImpl.triggerBuild] now detects the already-running/concurrent
 * condition and returns an explicit, actionable summary + hint.
 */
class BambooServiceImplTriggerConcurrentTest {

    private val mockProject = mockk<Project>(relaxed = true)
    private val mockClient = mockk<BambooApiClient>()
    private val service = BambooServiceImpl(mockProject).also {
        it.testClientOverride = mockClient
    }

    @Test
    fun `triggerBuild surfaces actionable hint when a build is already running`() = runTest {
        coEvery {
            mockClient.queueBuildWithStageSelection("PROJ-PLAN", emptyMap(), null)
        } returns ApiResult.Error(
            ErrorType.SERVER_ERROR,
            "Server returned 400: max 1 concurrent build is possible"
        )

        val result = service.triggerBuild("PROJ-PLAN", emptyMap(), null)

        assertTrue(result.isError)
        assertTrue(
            result.summary.contains("already running", ignoreCase = true) ||
                result.summary.contains("concurrent", ignoreCase = true),
            "summary must explain the concurrent-build condition, got: ${result.summary}"
        )
        val hint = result.hint.orEmpty()
        assertTrue(
            hint.contains("concurrent", ignoreCase = true),
            "hint must explain the concurrent-build limit, got: $hint"
        )
        assertTrue(
            hint.contains("get_running_builds") || hint.contains("stop_build"),
            "hint must point to a remediation action, got: $hint"
        )
        assertFalse(
            hint.equals("Check Bamboo connection in Settings.", ignoreCase = true),
            "must NOT fall through to the generic connection hint"
        )
    }

    @Test
    fun `triggerBuild keeps generic hint for an unrelated server error`() = runTest {
        coEvery {
            mockClient.queueBuildWithStageSelection("PROJ-PLAN", emptyMap(), null)
        } returns ApiResult.Error(ErrorType.SERVER_ERROR, "Server returned 500: internal error")

        val result = service.triggerBuild("PROJ-PLAN", emptyMap(), null)

        assertTrue(result.isError)
        assertFalse(
            result.hint.orEmpty().contains("concurrent", ignoreCase = true),
            "unrelated errors must not claim a concurrent-build condition"
        )
    }
}
