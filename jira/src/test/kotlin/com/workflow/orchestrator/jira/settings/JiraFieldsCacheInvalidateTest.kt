package com.workflow.orchestrator.jira.settings

import com.intellij.testFramework.LoggedErrorProcessorEnabler
import com.workflow.orchestrator.jira.api.JiraApiClient
import com.workflow.orchestrator.jira.service.JiraServiceImpl
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Verifies that [JiraServiceImpl.invalidateFieldsCache] forces the next
 * `getFields()` call to bypass the in-memory 5-min cache so the settings
 * UI's "Refresh fields" button reflects newly-added Jira custom fields
 * without waiting for cache expiry. (R-ADD-2.)
 */
@ExtendWith(LoggedErrorProcessorEnabler.DoNoRethrowErrors::class)
class JiraFieldsCacheInvalidateTest {

    private val project = mockk<com.intellij.openapi.project.Project>(relaxed = true)
    private lateinit var server: MockWebServer
    private lateinit var apiClient: JiraApiClient
    private lateinit var service: JiraServiceImpl

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        apiClient = JiraApiClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            tokenProvider = { "test-token" }
        )
        service = JiraServiceImpl(project).also { it.testClient = apiClient }
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `invalidateFieldsCache forces a fresh HTTP fetch on the next getFields call`() = runTest {
        // First & second response bodies.  Without invalidation, the second `getFields`
        // would hit the cache and never enqueue a request — so we'd see only one.
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """[{"id":"summary","name":"Summary","custom":false,"schema":{"type":"string"}}]"""
            )
        )
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """[{"id":"customfield_10001","name":"Acceptance Criteria","custom":true,"schema":{"type":"string"}}]"""
            )
        )

        val first = service.getFields()
        assertFalse(first.isError)
        assertEquals(1, server.requestCount, "First call should issue one HTTP request.")

        service.invalidateFieldsCache()
        val second = service.getFields()

        assertFalse(second.isError)
        assertEquals(2, server.requestCount,
            "Second call must hit MockWebServer again after invalidateFieldsCache(); got ${server.requestCount}.")
        // Cache eviction must surface the *new* response, not the stale first one.
        assertEquals(1, second.data!!.size)
        assertEquals("Acceptance Criteria", second.data!![0].name)
    }
}
