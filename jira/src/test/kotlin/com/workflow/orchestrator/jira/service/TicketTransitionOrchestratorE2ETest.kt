package com.workflow.orchestrator.jira.service

import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.TicketTransitioned
import com.workflow.orchestrator.core.model.jira.FieldSchema
import com.workflow.orchestrator.core.model.jira.TransitionError
import com.workflow.orchestrator.core.model.jira.TransitionInput
import com.workflow.orchestrator.jira.api.JiraApiClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * Service-level E2E test: exercises the full HTTP stack
 *   MockWebServer → JiraApiClient → TicketTransitionServiceImpl
 * without requiring a Swing/IntelliJ Application context.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TicketTransitionOrchestratorE2ETest {

    private lateinit var server: MockWebServer
    private lateinit var apiClient: JiraApiClient
    private lateinit var bus: EventBus
    private lateinit var orch: TicketTransitionServiceImpl

    @BeforeEach
    fun setUp() {
        server = MockWebServer().also { it.start() }
        apiClient = buildClientForTest(server)
        bus = EventBus()
        // No parentScope here — the backgroundScope provided per-test below handles cleanup
        // for tests that need event collection; simple tests use the default private scope.
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    // ── Test 1: getAvailableTransitions parses schema end-to-end ─────────────

    @Test
    fun `getAvailableTransitions parses schema end-to-end through the HTTP stack`() =
        runTest(UnconfinedTestDispatcher()) {
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

            orch = TicketTransitionServiceImpl(apiClient, bus, parentScope = backgroundScope)

            val r = orch.getAvailableTransitions("ABC-1")

            assertFalse(r.isError, "Expected success but got: ${r.summary}")
            val list = r.data!!
            assertEquals(1, list.size)
            assertEquals("31", list[0].id)
            assertEquals("In Review", list[0].name)
            assertEquals(FieldSchema.User(multi = false), list[0].fields[0].schema)
            assertTrue(list[0].hasScreen, "Expected hasScreen=true when fields are present")
        }

    // ── Test 2: executeTransition with missing required field returns MissingFields ──

    @Test
    fun `executeTransition with missing required field returns MissingFields and does not POST`() =
        runTest(UnconfinedTestDispatcher()) {
            // GET transitions — transition has a required assignee field
            server.enqueue(
                MockResponse().setBody(
                    """
                    {"transitions":[{"id":"31","name":"Done",
                      "to":{"id":"5","name":"Done","statusCategory":{"key":"done"}},
                      "fields":{"assignee":{"required":true,"schema":{"type":"user","system":"assignee"},"name":"Assignee"}}}]}
                    """.trimIndent()
                ).setResponseCode(200)
            )
            // (Intentionally no further responses — assert no POST is ever made.)

            orch = TicketTransitionServiceImpl(apiClient, bus, parentScope = backgroundScope)

            val r = orch.executeTransition("ABC-1", TransitionInput("31", emptyMap(), null))

            assertTrue(r.isError, "Expected error for missing required field")
            val payload = r.payload as TransitionError.MissingFields
            assertEquals("Assignee", payload.payload.fields.single().name)

            // Verify only the single GET was made (preflight check):
            val first = server.takeRequest()
            assertEquals("GET", first.method)

            // No POST should follow — confirm by timing out the next poll:
            val second = server.takeRequest(200, TimeUnit.MILLISECONDS)
            assertNull(second, "Expected no POST to transitions endpoint for missing-field case")
        }

    // ── Test 3: executeTransition success posts body and emits TicketTransitioned ──

    @Test
    fun `executeTransition success posts serialized body and emits TicketTransitioned event`() =
        runTest(UnconfinedTestDispatcher()) {
            // GET transitions — no required fields, no screen
            server.enqueue(
                MockResponse().setBody(
                    """
                    {"transitions":[{"id":"21","name":"Start Progress",
                      "to":{"id":"3","name":"In Progress","statusCategory":{"key":"indeterminate"}}
                    }]}
                    """.trimIndent()
                ).setResponseCode(200)
            )
            // GET issue — fetchCurrentStatus
            server.enqueue(
                MockResponse().setBody(
                    """
                    {"id":"10001","key":"ABC-1","self":"","fields":{
                      "summary":"Test ticket",
                      "status":{"id":"1","name":"To Do","statusCategory":{"id":2,"key":"new","name":"To Do"}}
                    }}
                    """.trimIndent()
                ).setResponseCode(200)
            )
            // POST transition — 204 No Content
            server.enqueue(MockResponse().setResponseCode(204))

            val captured = mutableListOf<TicketTransitioned>()
            orch = TicketTransitionServiceImpl(apiClient, bus, parentScope = backgroundScope)

            val job = bus.events
                .onEach { if (it is TicketTransitioned) captured += it }
                .launchIn(backgroundScope)

            val r = orch.executeTransition("ABC-1", TransitionInput("21", emptyMap(), "go"))
            assertFalse(r.isError, "Expected success but got: ${r.summary}")

            // Give collector a moment to process the event
            yield()

            assertTrue(captured.isNotEmpty(), "Expected TicketTransitioned event to be emitted")
            assertEquals("In Progress", captured[0].toStatus.name)

            job.cancel()
        }
}

/**
 * Mirror the construction pattern from [JiraApiClientTransitionsTest]:
 * wire the MockWebServer base URL into a real [JiraApiClient].
 */
private fun buildClientForTest(server: MockWebServer): JiraApiClient =
    JiraApiClient(
        baseUrl = server.url("/").toString().trimEnd('/'),
        tokenProvider = { "test-token" }
    )
