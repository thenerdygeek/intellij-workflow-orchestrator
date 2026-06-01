package com.workflow.orchestrator.bamboo.service

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.bamboo.api.dto.BambooPlanDto
import com.workflow.orchestrator.bamboo.api.dto.BambooSearchEntity
import com.workflow.orchestrator.core.model.ApiResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * Regression tests for the projectKey derivation bug in [BambooServiceImpl.getPlans]
 * and [BambooServiceImpl.searchPlans].
 *
 * Previously those methods derived the project key by calling
 * `dto.key.substringBefore("-")`, which returned only the first hyphen-separated
 * segment. For organisations with hyphenated project keys (e.g. `MY-PROJ`) the
 * full plan key looks like `MY-PROJ-AUTOTESTS` and the old code returned `"MY"`
 * instead of `"MY-PROJ"`.
 *
 * The fix uses the canonical `projectKey` field from the DTO, with the substring
 * split kept only as a defensive fallback for older Bamboo versions that omit it.
 */
class BambooServiceImplGetPlansProjectKeyTest {

    private val mockProject = mockk<Project>(relaxed = true)
    private val mockClient = mockk<BambooApiClient>()
    private val service = BambooServiceImpl(mockProject).also {
        it.testClientOverride = mockClient
    }

    // ── getPlans() ──────────────────────────────────────────────────────────────

    @Test
    fun `getPlans uses DTO projectKey for hyphenated project key`() = runTest {
        // Plan key MY-PROJ-AUTOTESTS: project is MY-PROJ, plan short is AUTOTESTS.
        // Old substringBefore("-") would produce "MY"; the fix must produce "MY-PROJ".
        val dto = BambooPlanDto(
            key = "MY-PROJ-AUTOTESTS",
            name = "Auto Tests",
            projectKey = "MY-PROJ"
        )
        coEvery { mockClient.getPlans() } returns ApiResult.Success(listOf(dto))

        val result = service.getPlans()

        assertFalse(result.isError, "Expected success, got: ${result.summary}")
        val plans = result.data!!
        assertEquals(1, plans.size)
        assertEquals("MY-PROJ", plans[0].projectKey,
            "projectKey must be MY-PROJ (from DTO), not MY (from bad substringBefore split)")
    }

    @Test
    fun `getPlans falls back to substringBefore when DTO projectKey is blank`() = runTest {
        // Older Bamboo versions may omit projectKey. The substring fallback is the
        // defensive path and is correct for simple (non-hyphenated) project keys.
        val dto = BambooPlanDto(
            key = "SIMPLE-PLAN",
            name = "Simple Plan",
            projectKey = ""   // blank → trigger fallback
        )
        coEvery { mockClient.getPlans() } returns ApiResult.Success(listOf(dto))

        val result = service.getPlans()

        assertFalse(result.isError, "Expected success, got: ${result.summary}")
        assertEquals("SIMPLE", result.data!![0].projectKey,
            "Fallback substringBefore should return SIMPLE for a simple (non-hyphenated) key")
    }

    @Test
    fun `getPlans preserves other PlanData fields`() = runTest {
        val dto = BambooPlanDto(
            key = "MY-PROJ-BUILD",
            name = "Build Pipeline",
            shortName = "Build",
            enabled = false,
            projectKey = "MY-PROJ"
        )
        coEvery { mockClient.getPlans() } returns ApiResult.Success(listOf(dto))

        val result = service.getPlans()

        assertFalse(result.isError)
        val plan = result.data!![0]
        assertEquals("MY-PROJ-BUILD", plan.key)
        assertEquals("Build Pipeline", plan.name)
        assertEquals("Build", plan.shortName)
        assertEquals(false, plan.enabled)
        assertEquals("MY-PROJ", plan.projectKey)
    }

    // ── searchPlans() ───────────────────────────────────────────────────────────

    @Test
    fun `searchPlans uses DTO projectKey for hyphenated project key`() = runTest {
        // Bamboo search API returns projectKey on each searchEntity.
        val entity = BambooSearchEntity(
            key = "MY-PROJ-AUTOTESTS",
            planName = "Auto Tests",
            projectName = "My Project",
            projectKey = "MY-PROJ"
        )
        coEvery { mockClient.searchPlans("auto") } returns ApiResult.Success(listOf(entity))

        val result = service.searchPlans("auto")

        assertFalse(result.isError, "Expected success, got: ${result.summary}")
        val plans = result.data!!
        assertEquals(1, plans.size)
        assertEquals("MY-PROJ", plans[0].projectKey,
            "projectKey must be MY-PROJ (from search entity), not MY (from bad substringBefore split)")
        assertEquals("My Project", plans[0].projectName)
    }

    @Test
    fun `searchPlans falls back to substringBefore when entity projectKey is blank`() = runTest {
        val entity = BambooSearchEntity(
            key = "SIMPLE-PLAN",
            planName = "Simple Plan",
            projectName = "Simple",
            projectKey = ""   // blank → trigger fallback
        )
        coEvery { mockClient.searchPlans("simple") } returns ApiResult.Success(listOf(entity))

        val result = service.searchPlans("simple")

        assertFalse(result.isError, "Expected success, got: ${result.summary}")
        assertEquals("SIMPLE", result.data!![0].projectKey,
            "Fallback substringBefore should return SIMPLE for a simple (non-hyphenated) key")
    }
}
