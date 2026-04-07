package com.workflow.orchestrator.sonar.service

import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.sonar.api.SonarApiClient
import com.workflow.orchestrator.sonar.api.dto.*
import com.workflow.orchestrator.sonar.model.QualityGateStatus
import com.workflow.orchestrator.sonar.model.SonarState
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SonarDataServiceHotspotsTest {

    private val apiClient = mockk<SonarApiClient>()

    private fun createTestableService(): TestSonarDataServiceWithHotspots {
        return TestSonarDataServiceWithHotspots(apiClient)
    }

    /** Stub all the APIs that refreshWith() calls, with minimal valid responses. */
    private fun stubMinimalResponses() {
        coEvery { apiClient.getQualityGateStatus(any(), any()) } returns ApiResult.Success(
            SonarQualityGateDto("OK", emptyList())
        )
        coEvery { apiClient.getIssuesWithPaging(any(), any(), inNewCodePeriod = any()) } returns ApiResult.Success(
            SonarIssueSearchResult(paging = SonarPagingDto(total = 0), issues = emptyList())
        )
        coEvery { apiClient.getMeasures(any(), any(), any()) } returns ApiResult.Success(emptyList())
        coEvery { apiClient.getBranches(any()) } returns ApiResult.Success(emptyList())
        coEvery { apiClient.getAnalysisTasks(any()) } returns ApiResult.Success(emptyList())
        coEvery { apiClient.getNewCodePeriod(any(), any()) } returns ApiResult.Error(
            ErrorType.NOT_FOUND, "not configured"
        )
        coEvery { apiClient.getProjectMeasures(any(), any()) } returns ApiResult.Success(emptyList())
        coEvery { apiClient.getSecurityHotspots(any(), any()) } returns ApiResult.Success(
            SonarHotspotSearchResult(hotspots = emptyList())
        )
    }

    @Test
    fun `refreshWith fetches security hotspots and stores them in state`() = runTest {
        stubMinimalResponses()
        coEvery { apiClient.getSecurityHotspots(any(), any()) } returns ApiResult.Success(
            SonarHotspotSearchResult(
                hotspots = listOf(
                    SonarHotspotDto(
                        key = "hotspot-1",
                        message = "Use a strong cipher algorithm",
                        component = "proj:key:src/Crypto.kt",
                        securityCategory = "encryption",
                        vulnerabilityProbability = "HIGH",
                        status = "TO_REVIEW",
                        resolution = null,
                        line = 42
                    ),
                    SonarHotspotDto(
                        key = "hotspot-2",
                        message = "Make sure this permission is safe",
                        component = "proj:key:src/Auth.kt",
                        securityCategory = "auth",
                        vulnerabilityProbability = "MEDIUM",
                        status = "REVIEWED",
                        resolution = "SAFE",
                        line = 10
                    )
                )
            )
        )

        val service = createTestableService()
        service.refreshWith(apiClient, "proj:key", "main")

        val state = service.stateFlow.value
        assertEquals(2, state.securityHotspots.size)

        val first = state.securityHotspots[0]
        assertEquals("hotspot-1", first.key)
        assertEquals("Use a strong cipher algorithm", first.message)
        assertEquals("proj:key:src/Crypto.kt", first.component)
        assertEquals(42, first.line)
        assertEquals("encryption", first.securityCategory)
        assertEquals("HIGH", first.probability)
        assertEquals("TO_REVIEW", first.status)
        assertNull(first.resolution)

        val second = state.securityHotspots[1]
        assertEquals("hotspot-2", second.key)
        assertEquals("MEDIUM", second.probability)
        assertEquals("REVIEWED", second.status)
        assertEquals("SAFE", second.resolution)
    }

    @Test
    fun `refreshWith handles hotspot API failure gracefully with empty list`() = runTest {
        stubMinimalResponses()
        coEvery { apiClient.getSecurityHotspots(any(), any()) } returns ApiResult.Error(
            ErrorType.FORBIDDEN, "Developer Edition required"
        )

        val service = createTestableService()
        service.refreshWith(apiClient, "proj:key", "main")

        val state = service.stateFlow.value
        assertTrue(state.securityHotspots.isEmpty())
        // Other data should still be populated
        assertEquals(QualityGateStatus.PASSED, state.qualityGate.status)
    }

    @Test
    fun `refreshWith handles hotspot API exception gracefully with empty list`() = runTest {
        stubMinimalResponses()
        coEvery { apiClient.getSecurityHotspots(any(), any()) } throws RuntimeException("Connection refused")

        val service = createTestableService()
        service.refreshWith(apiClient, "proj:key", "main")

        val state = service.stateFlow.value
        assertTrue(state.securityHotspots.isEmpty())
        assertEquals(QualityGateStatus.PASSED, state.qualityGate.status)
    }

    @Test
    fun `securityHotspots defaults to empty list in SonarState EMPTY`() {
        assertTrue(SonarState.EMPTY.securityHotspots.isEmpty())
    }
}

/**
 * Test double that replicates SonarDataService.refreshWith() logic including
 * the security hotspots fetch, without the @Service project dependency.
 */
private class TestSonarDataServiceWithHotspots(private val apiClient: SonarApiClient) {
    private val _stateFlow = MutableStateFlow(SonarState.EMPTY)
    val stateFlow = _stateFlow

    suspend fun refreshWith(client: SonarApiClient, projectKey: String, branch: String) {
        val gateResult = client.getQualityGateStatus(projectKey, branch)
        val issuesResult = client.getIssuesWithPaging(projectKey, branch)
        val newCodeIssuesResult = client.getIssuesWithPaging(projectKey, branch, inNewCodePeriod = true)
        val measuresResult = client.getMeasures(projectKey, branch, "coverage,line_coverage,branch_coverage,uncovered_lines,uncovered_conditions")

        // Fetch security hotspots (parallel in real service, sequential here for simplicity)
        val hotspotsResult = try {
            client.getSecurityHotspots(projectKey, branch)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }

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
            is ApiResult.Success -> IssueMapper.mapIssues(issuesResult.data.issues, projectKey)
            is ApiResult.Error -> _stateFlow.value.issues
        }

        val fileCoverage = when (measuresResult) {
            is ApiResult.Success -> CoverageMapper.mapMeasures(measuresResult.data)
            is ApiResult.Error -> _stateFlow.value.fileCoverage
        }

        val avgLine = if (fileCoverage.isEmpty()) 0.0 else fileCoverage.values.map { it.lineCoverage }.average()
        val avgBranch = if (fileCoverage.isEmpty()) 0.0 else fileCoverage.values.map { it.branchCoverage }.average()

        // Map security hotspots
        val securityHotspots = when (hotspotsResult) {
            is ApiResult.Success -> hotspotsResult.data.hotspots.map { dto ->
                com.workflow.orchestrator.core.model.sonar.SecurityHotspotData(
                    key = dto.key,
                    message = dto.message,
                    component = dto.component,
                    line = dto.line,
                    securityCategory = dto.securityCategory,
                    probability = dto.vulnerabilityProbability,
                    status = dto.status,
                    resolution = dto.resolution
                )
            }
            is ApiResult.Error -> emptyList()
            null -> emptyList()
        }

        _stateFlow.value = SonarState(
            projectKey = projectKey, branch = branch, qualityGate = qualityGate,
            issues = issues, fileCoverage = fileCoverage,
            overallCoverage = com.workflow.orchestrator.sonar.model.CoverageMetrics(avgLine, avgBranch),
            lastUpdated = java.time.Instant.now(),
            securityHotspots = securityHotspots
        )
    }
}
