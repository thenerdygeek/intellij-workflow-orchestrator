package com.workflow.orchestrator.automation.service

import com.workflow.orchestrator.automation.api.DockerRegistryClient
import com.workflow.orchestrator.automation.model.*
import com.workflow.orchestrator.core.model.ApiResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DriftDetectorServiceTest {

    private lateinit var registryClient: DockerRegistryClient
    private lateinit var service: DriftDetectorService

    @BeforeEach
    fun setUp() {
        registryClient = mockk()
        service = DriftDetectorService(registryClient)
    }

    @Test
    fun `checkDrift detects stale release tags`() = runTest {
        val entries = listOf(
            TagEntry("auth", "2.3.1", null, TagSource.BASELINE, RegistryStatus.VALID, false, false),
            TagEntry("payments", "2.4.0", null, TagSource.BASELINE, RegistryStatus.VALID, false, false)
        )
        coEvery { registryClient.getLatestReleaseTag("auth") } returns ApiResult.Success("2.4.0")
        coEvery { registryClient.getLatestReleaseTag("payments") } returns ApiResult.Success("2.4.0")

        val results = service.checkDrift(entries)

        assertEquals(2, results.size)
        val authDrift = results.find { it.serviceName == "auth" }!!
        assertTrue(authDrift.isStale)
        assertEquals("2.4.0", authDrift.latestReleaseTag)

        val paymentsDrift = results.find { it.serviceName == "payments" }!!
        assertFalse(paymentsDrift.isStale)
    }

    @Test
    fun `checkDrift skips feature branch tags`() = runTest {
        val entries = listOf(
            TagEntry("auth", "feature-PROJ-123-abc", null, TagSource.AUTO_DETECTED, RegistryStatus.VALID, false, true)
        )

        val results = service.checkDrift(entries)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `checkDrift handles registry errors gracefully`() = runTest {
        val entries = listOf(
            TagEntry("auth", "2.3.1", null, TagSource.BASELINE, RegistryStatus.VALID, false, false)
        )
        coEvery { registryClient.getLatestReleaseTag("auth") } returns
            ApiResult.Error(com.workflow.orchestrator.core.model.ErrorType.NETWORK_ERROR, "timeout")

        val results = service.checkDrift(entries)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `checkDrift handles null latest release tag`() = runTest {
        val entries = listOf(
            TagEntry("auth", "2.3.1", null, TagSource.BASELINE, RegistryStatus.VALID, false, false)
        )
        coEvery { registryClient.getLatestReleaseTag("auth") } returns ApiResult.Success(null)

        val results = service.checkDrift(entries)
        assertTrue(results.isEmpty())
    }
}
