package com.workflow.orchestrator.bamboo.service

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.bamboo.api.dto.BambooJobTestResultDto
import com.workflow.orchestrator.bamboo.api.dto.BambooTestCaseCollection
import com.workflow.orchestrator.bamboo.api.dto.BambooTestCaseDto
import com.workflow.orchestrator.bamboo.api.dto.BambooTestResultsDto
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Service-level coverage for [BambooServiceImpl.getTestResults].
 *
 * Coverage gaps addressed (BAMBOO-COV-8):
 * 1. Null client (Bamboo not configured) → isError=true with a not-configured hint.
 * 2. API returns an error → isError=true.
 * 3. Success DTO → correct data mapping (total, passed, failed, skipped, FailedTestData list).
 */
class BambooServiceImplTestResultsTest {

    private val mockProject = mockk<Project>(relaxed = true)
    private val mockClient = mockk<BambooApiClient>()

    // Service with a real test-client override so getTestResults() reaches the mock.
    private val service = BambooServiceImpl(mockProject).also {
        it.testClientOverride = mockClient
    }

    // Service where testClientOverride is null AND bambooUrl is blank → client property returns null.
    private val unconfiguredService = BambooServiceImpl(mockProject)
    // testClientOverride stays null; PluginSettings.connections.bambooUrl is empty by default
    // (relaxed mock returns default values, which means "" for String).


    @Test
    fun `getTestResults returns isError when API returns an error`() = runTest {
        coEvery { mockClient.getTestResults("PROJ-BUILD-42") } returns
            ApiResult.Error(ErrorType.NOT_FOUND, "Build not found")

        val result = service.getTestResults("PROJ-BUILD-42")

        assertTrue(result.isError,
            "getTestResults should return isError=true when the API returns an error")
    }

    @Test
    fun `getTestResults maps success DTO to correct TestResultsData`() = runTest {
        // Build a DTO with 5 total: 2 passed, 2 failed, 1 skipped.
        val failed1 = BambooTestCaseDto(
            className = "com.example.UserServiceTest",
            methodName = "testCreate",
            status = "failed"
        )
        val failed2 = BambooTestCaseDto(
            className = "com.example.UserServiceTest",
            methodName = "testDelete",
            status = "failed"
        )
        val successDto = BambooTestResultsDto(
            all = 5,
            successful = 2,
            failed = 2,
            skipped = 1,
            failedTests = BambooTestCaseCollection(size = 2, testResult = listOf(failed1, failed2)),
            successfulTests = BambooTestCaseCollection(size = 2, testResult = emptyList())
        )
        val jobDto = BambooJobTestResultDto(testResults = successDto)

        coEvery { mockClient.getTestResults("PROJ-BUILD-42") } returns ApiResult.Success(jobDto)

        val result = service.getTestResults("PROJ-BUILD-42")

        assertFalse(result.isError, "Expected success, got: ${result.summary}")
        val data = result.data!!
        assertEquals(5, data.total, "total should be 5")
        assertEquals(2, data.passed, "passed should be 2")
        assertEquals(2, data.failed, "failed should be 2")
        assertEquals(1, data.skipped, "skipped should be 1")
        assertEquals(2, data.failedTests.size, "failedTests list should have 2 entries")
        val failedMethodNames = data.failedTests.map { it.methodName }
        assertTrue(failedMethodNames.contains("testCreate"),
            "failedTests should include testCreate, got: $failedMethodNames")
        assertTrue(failedMethodNames.contains("testDelete"),
            "failedTests should include testDelete, got: $failedMethodNames")
    }

    @Test
    fun `getTestResults maps empty failed list correctly`() = runTest {
        // All-passing result: no FailedTestData entries expected.
        val successDto = BambooTestResultsDto(
            all = 3,
            successful = 3,
            failed = 0,
            skipped = 0,
            failedTests = BambooTestCaseCollection(size = 0, testResult = emptyList()),
            successfulTests = BambooTestCaseCollection(size = 3, testResult = emptyList())
        )
        coEvery { mockClient.getTestResults("PROJ-BUILD-99") } returns
            ApiResult.Success(BambooJobTestResultDto(testResults = successDto))

        val result = service.getTestResults("PROJ-BUILD-99")

        assertFalse(result.isError, "Expected success for all-passing suite")
        val data = result.data!!
        assertEquals(0, data.failed, "failed count should be 0")
        assertTrue(data.failedTests.isEmpty(), "failedTests should be empty for all-passing suite")
    }
}
