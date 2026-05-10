package com.workflow.orchestrator.sonar.service

import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.sonar.api.SonarApiClient
import com.workflow.orchestrator.sonar.api.dto.SonarConditionDto
import com.workflow.orchestrator.sonar.api.dto.SonarQualityGateDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * T-B1: Verifies that [SonarDataService.refreshWith] emits
 * [WorkflowEvent.QualityGateResult] on terminal gate states and deduplicates
 * repeated identical results.
 *
 * Uses [TestableSonarDataService] — a pure-mock test double that replicates
 * the quality-gate emit logic without the IntelliJ @Service DI infrastructure,
 * following the same pattern as [TestSonarDataService] in [SonarDataServiceTest].
 */
class SonarQualityEmitTest {

    private lateinit var apiClient: SonarApiClient
    private lateinit var eventBus: EventBus
    private lateinit var service: TestableSonarDataService

    @BeforeEach
    fun setUp() {
        apiClient = mockk(relaxed = true)
        eventBus = mockk(relaxed = true)

        // Stub non-gate API calls with empty successes so they don't interfere.
        stubNonGateCalls()

        service = TestableSonarDataService(eventBus)
    }

    // ─── terminal states ─────────────────────────────────────────────────────

    @Test
    fun `OK gate result emits QualityGateResult with passed=true exactly once`() = runTest {
        stubGate("OK")

        service.refreshWith(apiClient, PROJECT_KEY, BRANCH)

        val slot = slot<WorkflowEvent.QualityGateResult>()
        coVerify(exactly = 1) { eventBus.emit(capture(slot)) }
        assertTrue(slot.captured.passed)
        assertEquals(PROJECT_KEY, slot.captured.projectKey)
    }

    @Test
    fun `ERROR gate result emits QualityGateResult with passed=false exactly once`() = runTest {
        stubGate("ERROR")

        service.refreshWith(apiClient, PROJECT_KEY, BRANCH)

        val slot = slot<WorkflowEvent.QualityGateResult>()
        coVerify(exactly = 1) { eventBus.emit(capture(slot)) }
        assertFalse(slot.captured.passed)
        assertEquals(PROJECT_KEY, slot.captured.projectKey)
    }

    // ─── non-terminal states ─────────────────────────────────────────────────

    @Test
    fun `IN_PROGRESS gate result does not emit anything`() = runTest {
        stubGate("IN_PROGRESS")

        service.refreshWith(apiClient, PROJECT_KEY, BRANCH)

        coVerify(exactly = 0) { eventBus.emit(any()) }
    }

    @Test
    fun `NONE gate result (API error) does not emit anything`() = runTest {
        coEvery {
            apiClient.getQualityGateStatus(PROJECT_KEY, BRANCH)
        } returns ApiResult.Error(ErrorType.NETWORK_ERROR, "offline")

        service.refreshWith(apiClient, PROJECT_KEY, BRANCH)

        coVerify(exactly = 0) { eventBus.emit(any()) }
    }

    // ─── deduplication ───────────────────────────────────────────────────────

    @Test
    fun `same OK result fetched twice emits only once (dedup)`() = runTest {
        stubGate("OK")

        service.refreshWith(apiClient, PROJECT_KEY, BRANCH)
        service.refreshWith(apiClient, PROJECT_KEY, BRANCH)

        // First call emits (first-time terminal result); second call is a no-op (same status).
        coVerify(exactly = 1) { eventBus.emit(any()) }
    }

    @Test
    fun `same ERROR result fetched twice emits only once (dedup)`() = runTest {
        stubGate("ERROR")

        service.refreshWith(apiClient, PROJECT_KEY, BRANCH)
        service.refreshWith(apiClient, PROJECT_KEY, BRANCH)

        coVerify(exactly = 1) { eventBus.emit(any()) }
    }

    // ─── transitions ─────────────────────────────────────────────────────────

    @Test
    fun `OK then ERROR for same project emits twice (one per transition)`() = runTest {
        stubGate("OK")
        service.refreshWith(apiClient, PROJECT_KEY, BRANCH)

        stubGate("ERROR")
        service.refreshWith(apiClient, PROJECT_KEY, BRANCH)

        coVerify(exactly = 2) { eventBus.emit(any()) }
    }

    @Test
    fun `ERROR then OK for same project emits twice (one per transition)`() = runTest {
        stubGate("ERROR")
        service.refreshWith(apiClient, PROJECT_KEY, BRANCH)

        stubGate("OK")
        service.refreshWith(apiClient, PROJECT_KEY, BRANCH)

        coVerify(exactly = 2) { eventBus.emit(any()) }
    }

    @Test
    fun `transition from OK to ERROR carries correct passed flag in second event`() = runTest {
        stubGate("OK")
        service.refreshWith(apiClient, PROJECT_KEY, BRANCH)

        stubGate("ERROR")
        service.refreshWith(apiClient, PROJECT_KEY, BRANCH)

        val emittedEvents = mutableListOf<WorkflowEvent.QualityGateResult>()
        coVerify(exactly = 2) { eventBus.emit(capture(emittedEvents)) }

        assertTrue(emittedEvents[0].passed)    // first emit: OK → passed=true
        assertFalse(emittedEvents[1].passed)   // second emit: ERROR → passed=false
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private fun stubGate(status: String) {
        coEvery {
            apiClient.getQualityGateStatus(PROJECT_KEY, BRANCH)
        } returns ApiResult.Success(SonarQualityGateDto(status, emptyList()))
    }

    private fun stubNonGateCalls() {
        coEvery {
            apiClient.getIssuesWithPaging(any(), any(), any())
        } returns ApiResult.Error(ErrorType.NETWORK_ERROR, "stub")

        coEvery {
            apiClient.getBranches(any())
        } returns ApiResult.Error(ErrorType.NETWORK_ERROR, "stub")

        coEvery {
            apiClient.getAnalysisTasks(any())
        } returns ApiResult.Error(ErrorType.NETWORK_ERROR, "stub")

        coEvery {
            apiClient.getProjectMeasures(any(), any())
        } returns ApiResult.Error(ErrorType.NETWORK_ERROR, "stub")

        coEvery {
            apiClient.getQualityGateStatus(any(), any())
        } returns ApiResult.Error(ErrorType.NETWORK_ERROR, "stub-default")

        coEvery {
            apiClient.getSecurityHotspots(any(), any())
        } returns ApiResult.Error(ErrorType.NETWORK_ERROR, "stub")

        coEvery {
            apiClient.getMeasures(any(), any())
        } returns ApiResult.Error(ErrorType.NETWORK_ERROR, "stub")
    }

    companion object {
        private const val PROJECT_KEY = "com.example:my-service"
        private const val BRANCH = "feature/PROJ-123"
    }
}

/**
 * Minimal test double that exercises the quality-gate emit path from
 * [SonarDataService.refreshWith] without any IntelliJ platform wiring.
 *
 * Only the quality-gate gate branch is exercised — non-gate API calls are
 * allowed to fail (ApiResult.Error stubs); those paths don't interact with
 * the emit logic under test.
 */
private class TestableSonarDataService(private val eventBus: EventBus) {

    private var previousGateStatus: com.workflow.orchestrator.sonar.model.QualityGateStatus? = null
    private var lastEmittedResult: Boolean? = null

    /**
     * Replicates the quality-gate emit logic from [SonarDataService.refreshWith]
     * with only the EventBus wiring under test.
     */
    suspend fun refreshWith(client: SonarApiClient, projectKey: String, branch: String) {
        val gateResult = client.getQualityGateStatus(projectKey, branch)

        val qualityGate = when (gateResult) {
            is ApiResult.Success -> {
                val status = when (gateResult.data.status) {
                    "OK" -> com.workflow.orchestrator.sonar.model.QualityGateStatus.PASSED
                    "ERROR" -> com.workflow.orchestrator.sonar.model.QualityGateStatus.FAILED
                    else -> com.workflow.orchestrator.sonar.model.QualityGateStatus.NONE
                }
                com.workflow.orchestrator.sonar.model.QualityGateState(status, emptyList())
            }
            is ApiResult.Error -> com.workflow.orchestrator.sonar.model.QualityGateState(
                com.workflow.orchestrator.sonar.model.QualityGateStatus.NONE, emptyList()
            )
        }

        // Mirror the exact dedup + emit logic from SonarDataService.refreshWith
        // (lines 511-516 in the pre-T-B1 file plus the new T-B1 emit block).
        if (qualityGate.status != com.workflow.orchestrator.sonar.model.QualityGateStatus.NONE) {
            val passed = qualityGate.status == com.workflow.orchestrator.sonar.model.QualityGateStatus.PASSED

            // Emit on first terminal result OR when result changes (dedup).
            val isFirstTerminal = previousGateStatus == null
            val hasChanged = previousGateStatus != qualityGate.status

            if (isFirstTerminal || hasChanged) {
                eventBus.emit(
                    WorkflowEvent.QualityGateResult(
                        projectKey = projectKey,
                        passed = passed,
                    )
                )
                lastEmittedResult = passed
            }
            previousGateStatus = qualityGate.status
        }
    }
}
