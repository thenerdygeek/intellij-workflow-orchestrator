package com.workflow.orchestrator.jira.api

import com.intellij.testFramework.LoggedErrorProcessorEnabler
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.core.model.jira.FieldValue
import com.workflow.orchestrator.core.model.jira.TransitionInput
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(LoggedErrorProcessorEnabler.DoNoRethrowErrors::class)
class JiraApiClientTransitionIssueTest {

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
    fun `transitionIssue posts serializer body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        val input = TransitionInput(
            transitionId = "31",
            fieldValues = mapOf("assignee" to FieldValue.UserRef("jdoe")),
            comment = null
        )
        val result = client.transitionIssue("ABC-1", input)

        assertTrue(result.isSuccess, "Expected success but got: $result")

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/rest/api/2/issue/ABC-1/transitions", recorded.path)

        val bodyStr = recorded.body.readUtf8()
        val actual = Json.parseToJsonElement(bodyStr)
        val expected = Json.parseToJsonElement(
            """{"transition":{"id":"31"},"fields":{"assignee":{"name":"jdoe"}}}"""
        )
        assertEquals(expected, actual, "Request body JSON did not match expected shape")
    }

    @Test
    fun `transitionIssue surfaces 400 body as structured error`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("""{"errorMessages":["Field 'assignee' cannot be set"],"errors":{}}""")
        )

        val input = TransitionInput("31", emptyMap(), null)
        val result = client.transitionIssue("ABC-1", input)

        assertTrue(result.isError, "Expected error but got: $result")
        val error = result as ApiResult.Error
        assertEquals(ErrorType.VALIDATION_ERROR, error.type)
        assertTrue(
            error.message.contains("Field 'assignee' cannot be set"),
            "Expected error message to contain field error; got: ${error.message}"
        )
    }
}
