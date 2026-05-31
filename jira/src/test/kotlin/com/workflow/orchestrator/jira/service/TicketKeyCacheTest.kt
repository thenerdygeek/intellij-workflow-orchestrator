package com.workflow.orchestrator.jira.service

import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.jira.api.JiraApiClient
import com.workflow.orchestrator.jira.api.dto.TicketKeyInfo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [TicketKeyCache].
 *
 * JIRA-COV-4: TicketKeyCache has zero tests. Covers the testable paths that do NOT
 * require a running IntelliJ platform (validateAndCache and evictIfNeeded).
 *
 * Note: [TicketKeyCache.extractKeys] calls `ConnectionSettings.getInstance()` which
 * requires the IntelliJ application service container. That method is therefore tested
 * indirectly via validateAndCache (which calls getUnvalidated, which calls the cache
 * directly) rather than calling extractKeys from these unit tests.
 */
class TicketKeyCacheTest {

    private lateinit var cache: TicketKeyCache
    private lateinit var apiClient: JiraApiClient

    @BeforeEach
    fun setUp() {
        // TicketKeyCache can be instantiated directly; no @Service resolution needed.
        cache = TicketKeyCache()
        apiClient = mockk()
    }

    // ── validateAndCache: success path ────────────────────────────────────



    // ── validateAndCache: API error path ──────────────────────────────────

    @Test
    fun `validateAndCache leaves cache empty when API returns an error`() = runTest {
        coEvery { apiClient.validateTicketKeys(any()) } returns ApiResult.Error(
            ErrorType.NETWORK_ERROR, "Cannot reach Jira"
        )

        cache.validateAndCache(apiClient, setOf("PROJ-1"))

        assertFalse(cache.isValidated("PROJ-1"),
            "A key must NOT be cached when the API call returned an error")
        assertNull(cache.get("PROJ-1"),
            "cache.get must return null for a key whose validation API call failed")
    }

    @Test
    fun `validateAndCache does not throw when API returns an error`() = runTest {
        coEvery { apiClient.validateTicketKeys(any()) } returns ApiResult.Error(
            ErrorType.SERVER_ERROR, "500 Internal Server Error"
        )

        // Must complete without throwing
        cache.validateAndCache(apiClient, setOf("PROJ-1"))
    }

    // ── validateAndCache: short-circuit paths ────────────────────────────

    @Test
    fun `validateAndCache skips API call when all keys are already validated`() = runTest {
        coEvery { apiClient.validateTicketKeys(any()) } returns ApiResult.Success(
            mapOf("PROJ-1" to TicketKeyInfo(key = "PROJ-1", summary = "S", status = "Open"))
        )

        // First call — populates cache
        cache.validateAndCache(apiClient, setOf("PROJ-1"))
        // Second call — all keys already validated, must not make another API call
        cache.validateAndCache(apiClient, setOf("PROJ-1"))

        coVerify(exactly = 1) { apiClient.validateTicketKeys(any()) }
    }

    @Test
    fun `validateAndCache skips API call for empty key set`() = runTest {
        cache.validateAndCache(apiClient, emptySet())

        coVerify(exactly = 0) { apiClient.validateTicketKeys(any()) }
    }

    // ── evictIfNeeded: LRU eviction at MAX_SIZE ───────────────────────────

    @Test
    fun `cache stays at MAX_SIZE after overfill via repeated validateAndCache calls`() = runTest {
        // MAX_SIZE = 500. We add 501 entries in one batch to trigger eviction.
        val keys = (1..501).map { "PROJ-$it" }.toSet()
        val validMap = keys.associate { key ->
            key to TicketKeyInfo(key = key, summary = "Summary of $key", status = "Open")
        }
        coEvery { apiClient.validateTicketKeys(any()) } returns ApiResult.Success(validMap)

        cache.validateAndCache(apiClient, keys)

        // After eviction, the cache must have exactly MAX_SIZE (500) entries.
        // We verify via the public surface: exactly 500 keys must be validated.
        val validatedCount = keys.count { cache.isValidated(it) }
        assertEquals(500, validatedCount,
            "After adding 501 entries, evictIfNeeded must trim cache to MAX_SIZE=500; got $validatedCount validated entries")
    }

    // ── clear ─────────────────────────────────────────────────────────────

    @Test
    fun `clear removes all cached entries`() = runTest {
        coEvery { apiClient.validateTicketKeys(any()) } returns ApiResult.Success(
            mapOf("PROJ-1" to TicketKeyInfo(key = "PROJ-1", summary = "S", status = "Open"))
        )
        cache.validateAndCache(apiClient, setOf("PROJ-1"))
        assertTrue(cache.isValidated("PROJ-1"), "Key must be cached before clear()")

        cache.clear()

        assertFalse(cache.isValidated("PROJ-1"), "Key must not be present after clear()")
        assertNull(cache.get("PROJ-1"), "get() must return null after clear()")
    }
}
