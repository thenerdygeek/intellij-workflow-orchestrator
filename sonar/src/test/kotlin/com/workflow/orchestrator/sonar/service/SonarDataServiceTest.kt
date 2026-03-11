package com.workflow.orchestrator.sonar.service

import app.cash.turbine.test
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.sonar.api.SonarApiClient
import com.workflow.orchestrator.sonar.api.dto.*
import com.workflow.orchestrator.sonar.model.QualityGateStatus
import com.workflow.orchestrator.sonar.model.SonarState
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SonarDataServiceTest {

    private val apiClient = mockk<SonarApiClient>()

    private fun createTestableService(): TestSonarDataService {
        return TestSonarDataService(apiClient)
    }

    private fun stubSuccessfulResponses() {
        coEvery { apiClient.getQualityGateStatus(any(), any()) } returns ApiResult.Success(
            SonarQualityGateDto("OK", listOf(
                SonarConditionDto("OK", "new_coverage", "LT", "80", "87.3")
            ))
        )
        coEvery { apiClient.getIssues(any(), any(), any()) } returns ApiResult.Success(
            listOf(SonarIssueDto(
                key = "k1", rule = "java:S2259", severity = "CRITICAL",
                message = "NPE", component = "proj:key:src/File.kt", type = "BUG"
            ))
        )
        coEvery { apiClient.getMeasures(any(), any()) } returns ApiResult.Success(
            listOf(SonarMeasureComponentDto(
                key = "proj:key:src/File.kt", path = "src/File.kt",
                measures = listOf(
                    SonarMeasureDto("line_coverage", "78.5"),
                    SonarMeasureDto("branch_coverage", "65.0"),
                    SonarMeasureDto("uncovered_lines", "5"),
                    SonarMeasureDto("uncovered_conditions", "2")
                )
            ))
        )
    }

    @Test
    fun `refresh updates state with quality gate and issues`() = runTest {
        stubSuccessfulResponses()
        val service = createTestableService()

        service.refreshWith(apiClient, "proj:key", "main")

        val state = service.stateFlow.value
        assertEquals(QualityGateStatus.PASSED, state.qualityGate.status)
        assertEquals(1, state.issues.size)
        assertEquals("BUG", state.issues[0].type.name)
        assertEquals(1, state.fileCoverage.size)
    }

    @Test
    fun `refresh maps failed quality gate`() = runTest {
        coEvery { apiClient.getQualityGateStatus(any(), any()) } returns ApiResult.Success(
            SonarQualityGateDto("ERROR", emptyList())
        )
        coEvery { apiClient.getIssues(any(), any(), any()) } returns ApiResult.Success(emptyList())
        coEvery { apiClient.getMeasures(any(), any()) } returns ApiResult.Success(emptyList())

        val service = createTestableService()
        service.refreshWith(apiClient, "proj:key", "main")

        assertEquals(QualityGateStatus.FAILED, service.stateFlow.value.qualityGate.status)
    }

    @Test
    fun `detects quality gate status transition`() = runTest {
        coEvery { apiClient.getIssues(any(), any(), any()) } returns ApiResult.Success(emptyList())
        coEvery { apiClient.getMeasures(any(), any()) } returns ApiResult.Success(emptyList())

        coEvery { apiClient.getQualityGateStatus(any(), any()) } returns ApiResult.Success(
            SonarQualityGateDto("OK", emptyList())
        )

        val service = createTestableService()

        service.refreshWith(apiClient, "proj:key", "main")
        assertEquals(1, service.transitions.size)
        assertTrue(service.transitions[0])

        // Same status again — no new transition
        service.refreshWith(apiClient, "proj:key", "main")
        assertEquals(1, service.transitions.size)

        // Now: FAILED — transition fires
        coEvery { apiClient.getQualityGateStatus(any(), any()) } returns ApiResult.Success(
            SonarQualityGateDto("ERROR", emptyList())
        )
        service.refreshWith(apiClient, "proj:key", "main")
        assertEquals(2, service.transitions.size)
        assertFalse(service.transitions[1])
    }

    @Test
    fun `handles API errors gracefully`() = runTest {
        coEvery { apiClient.getQualityGateStatus(any(), any()) } returns
            ApiResult.Error(ErrorType.NETWORK_ERROR, "offline")
        coEvery { apiClient.getIssues(any(), any(), any()) } returns
            ApiResult.Error(ErrorType.NETWORK_ERROR, "offline")
        coEvery { apiClient.getMeasures(any(), any()) } returns
            ApiResult.Error(ErrorType.NETWORK_ERROR, "offline")

        val service = createTestableService()
        service.refreshWith(apiClient, "proj:key", "main")

        assertEquals(QualityGateStatus.NONE, service.stateFlow.value.qualityGate.status)
    }

    @Test
    fun `stateFlow emits updates on refresh`() = runTest {
        stubSuccessfulResponses()
        val service = createTestableService()

        service.stateFlow.test {
            val initial = awaitItem()
            assertEquals(QualityGateStatus.NONE, initial.qualityGate.status)

            service.refreshWith(apiClient, "proj:key", "main")

            val updated = awaitItem()
            assertEquals(QualityGateStatus.PASSED, updated.qualityGate.status)
            assertEquals(1, updated.issues.size)
        }
    }
}

/**
 * Test double that replicates SonarDataService core logic without the @Service
 * project dependency. Tracks quality gate transitions for assertion.
 */
private class TestSonarDataService(private val apiClient: SonarApiClient) {
    private val _stateFlow = MutableStateFlow(SonarState.EMPTY)
    val stateFlow = _stateFlow
    val transitions = mutableListOf<Boolean>()
    private var previousGateStatus: QualityGateStatus? = null

    suspend fun refreshWith(client: SonarApiClient, projectKey: String, branch: String) {
        val gateResult = client.getQualityGateStatus(projectKey, branch)
        val issuesResult = client.getIssues(projectKey, branch)
        val measuresResult = client.getMeasures(projectKey, branch)

        val qualityGate = when (gateResult) {
            is ApiResult.Success -> {
                val status = when (gateResult.data.status) {
                    "OK" -> QualityGateStatus.PASSED
                    "ERROR" -> QualityGateStatus.FAILED
                    else -> QualityGateStatus.NONE
                }
                com.workflow.orchestrator.sonar.model.QualityGateState(
                    status,
                    gateResult.data.conditions.map { cond ->
                        com.workflow.orchestrator.sonar.model.GateCondition(
                            cond.metricKey, cond.comparator, cond.errorThreshold,
                            cond.actualValue, cond.status == "OK"
                        )
                    }
                )
            }
            is ApiResult.Error -> com.workflow.orchestrator.sonar.model.QualityGateState(
                QualityGateStatus.NONE, emptyList()
            )
        }

        val issues = when (issuesResult) {
            is ApiResult.Success -> IssueMapper.mapIssues(issuesResult.data, projectKey)
            is ApiResult.Error -> _stateFlow.value.issues
        }

        val fileCoverage = when (measuresResult) {
            is ApiResult.Success -> CoverageMapper.mapMeasures(measuresResult.data)
            is ApiResult.Error -> _stateFlow.value.fileCoverage
        }

        val avgLine = if (fileCoverage.isEmpty()) 0.0 else fileCoverage.values.map { it.lineCoverage }.average()
        val avgBranch = if (fileCoverage.isEmpty()) 0.0 else fileCoverage.values.map { it.branchCoverage }.average()

        _stateFlow.value = SonarState(
            projectKey = projectKey, branch = branch, qualityGate = qualityGate,
            issues = issues, fileCoverage = fileCoverage,
            overallCoverage = com.workflow.orchestrator.sonar.model.CoverageMetrics(avgLine, avgBranch),
            lastUpdated = java.time.Instant.now()
        )

        if (qualityGate.status != QualityGateStatus.NONE) {
            if (previousGateStatus != null && previousGateStatus != qualityGate.status) {
                transitions.add(qualityGate.status == QualityGateStatus.PASSED)
            }
            if (previousGateStatus == null) {
                transitions.add(qualityGate.status == QualityGateStatus.PASSED)
            }
            previousGateStatus = qualityGate.status
        }
    }
}
