package com.workflow.orchestrator.jira.api

import com.intellij.testFramework.LoggedErrorProcessorEnabler
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.jira.FieldSchema
import com.workflow.orchestrator.core.model.jira.StatusCategory
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(LoggedErrorProcessorEnabler.DoNoRethrowErrors::class)
class JiraApiClientTransitionsTest {

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
    fun `getTransitions sends expand=transitions_fields by default and parses response`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"transitions":[{
                  "id":"31","name":"In Review",
                  "to":{"id":"3","name":"In Review","statusCategory":{"key":"indeterminate"}},
                  "fields":{"assignee":{"required":true,"schema":{"type":"user","system":"assignee"},"name":"Assignee"}}
                }]}
                """.trimIndent()
            ).setResponseCode(200)
        )

        val result = client.getTransitions("ABC-1")

        assertTrue(result.isSuccess, "Expected success but got: $result")
        val transitions = (result as ApiResult.Success).data

        assertEquals(1, transitions.size)
        assertEquals("31", transitions[0].id)
        assertEquals("In Review", transitions[0].name)
        assertEquals(StatusCategory.IN_PROGRESS, transitions[0].toStatus.category)
        assertTrue(transitions[0].hasScreen)

        val fields = transitions[0].fields
        assertEquals(1, fields.size)
        assertEquals("assignee", fields[0].id)
        assertEquals(true, fields[0].required)
        assertEquals(FieldSchema.User(multi = false), fields[0].schema)

        val recorded = server.takeRequest()
        assertTrue(
            recorded.path!!.contains("expand=transitions.fields"),
            "Expected expand=transitions.fields in request path; got: ${recorded.path}"
        )
        assertEquals("Bearer test-token", recorded.getHeader("Authorization"))
    }

    @Test
    fun `getTransitions with expandFields=false omits expand param`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"transitions":[{"id":"21","name":"Start Progress","to":{"id":"3","name":"In Progress","statusCategory":{"key":"indeterminate"}}}]}"""
            ).setResponseCode(200)
        )

        val result = client.getTransitions("ABC-2", expandFields = false)

        assertTrue(result.isSuccess)
        val recorded = server.takeRequest()
        assertEquals("/rest/api/2/issue/ABC-2/transitions", recorded.path)
    }

    @Test
    fun `getTransitions parses transition with no fields as hasScreen=false`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"transitions":[{"id":"21","name":"Done","to":{"id":"5","name":"Done","statusCategory":{"key":"done"}}}]}"""
            ).setResponseCode(200)
        )

        val result = client.getTransitions("ABC-3")

        assertTrue(result.isSuccess)
        val transitions = (result as ApiResult.Success).data
        assertEquals(1, transitions.size)
        assertEquals(false, transitions[0].hasScreen)
        assertTrue(transitions[0].fields.isEmpty())
        assertEquals(StatusCategory.DONE, transitions[0].toStatus.category)
    }

    @Test
    fun `getTransitions returns AUTH_FAILED on 401`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = client.getTransitions("ABC-4")

        assertTrue(result.isError)
        assertEquals(
            com.workflow.orchestrator.core.model.ErrorType.AUTH_FAILED,
            (result as ApiResult.Error).type
        )
    }
}
