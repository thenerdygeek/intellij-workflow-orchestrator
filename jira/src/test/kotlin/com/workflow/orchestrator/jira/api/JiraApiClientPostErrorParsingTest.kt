package com.workflow.orchestrator.jira.api

import com.intellij.testFramework.LoggedErrorProcessorEnabler
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Verifies the 2026-05-07 audit fix: `parseJiraErrorMessage` was previously only
 * invoked from `transitionIssue`, so `addComment` and `postWorklog` returned
 * unhelpful raw "HTTP 400" / "Jira returned 400" messages while the response body
 * carried the actual reason (e.g. `errors.timeSpent: "is required"`). PR 1 lifts
 * `parseJiraErrorMessage` into the shared `post()` helper so every Jira write
 * gets the same actionable error mapping.
 */
@ExtendWith(LoggedErrorProcessorEnabler.DoNoRethrowErrors::class)
class JiraApiClientPostErrorParsingTest {

    private lateinit var server: MockWebServer
    private lateinit var client: JiraApiClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = JiraApiClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            tokenProvider = { "test-token" }
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `postWorklog 400 surfaces field-level Jira error message`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("""{"errorMessages":[],"errors":{"timeSpent":"Worklog timeSpent is required"}}""")
        )

        val result = client.postWorklog("ABC-1", timeSpent = "")

        assertTrue(result is ApiResult.Error, "Expected error; got: $result")
        val error = result as ApiResult.Error
        assertEquals(ErrorType.VALIDATION_ERROR, error.type)
        assertTrue(
            error.message.contains("timeSpent", ignoreCase = true) &&
                error.message.contains("is required", ignoreCase = true),
            "Expected parsed field error in message; got: ${error.message}"
        )
    }

    @Test
    fun `addComment 400 surfaces parsed errorMessages`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("""{"errorMessages":["Comment body cannot be empty"],"errors":{}}""")
        )

        val result = client.addComment("ABC-1", body = "")

        assertTrue(result is ApiResult.Error, "Expected error; got: $result")
        val error = result as ApiResult.Error
        assertEquals(ErrorType.VALIDATION_ERROR, error.type)
        assertTrue(
            error.message.contains("Comment body cannot be empty"),
            "Expected parsed errorMessages in message; got: ${error.message}"
        )
    }

    @Test
    fun `postWorklog success on 201 returns Success`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"100"}"""))

        val result = client.postWorklog("ABC-1", timeSpent = "1h")

        assertTrue(result is ApiResult.Success, "Expected success; got: $result")
    }

    @Test
    fun `addComment 5xx body parses Jira error structure when present`() = runTest {
        // RetryInterceptor (3 retries on 5xx) means we must enqueue all 4 attempts.
        // Each one returns the same 5xx Jira error body so the final mapping is
        // exercised.
        repeat(4) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(500)
                    .setBody("""{"errorMessages":["Internal Jira hiccup"],"errors":{}}""")
            )
        }

        val result = client.addComment("ABC-1", body = "hello")

        assertTrue(result is ApiResult.Error)
        val error = result as ApiResult.Error
        assertEquals(ErrorType.SERVER_ERROR, error.type)
        assertTrue(
            error.message.contains("Internal Jira hiccup"),
            "Expected parsed Jira message on 5xx; got: ${error.message}"
        )
    }

    @Test
    fun `addComment 400 with non-JSON body falls back to generic message`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("not json at all")
        )

        val result = client.addComment("ABC-1", body = "x")

        assertTrue(result is ApiResult.Error)
        val error = result as ApiResult.Error
        assertEquals(ErrorType.VALIDATION_ERROR, error.type)
        // parseJiraErrorMessage returns null on parse failure → fallback to "Bad request (400)"
        assertTrue(
            error.message.contains("400") || error.message.contains("Bad request", ignoreCase = true),
            "Expected fallback message; got: ${error.message}"
        )
    }
}
