package com.workflow.orchestrator.mockserver.sourcegraph

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SourcegraphMockRoutesTest {

    private fun ApplicationTestBuilder.setupSourcegraph(state: SourcegraphState = SourcegraphState()) {
        application {
            install(ContentNegotiation) { json() }
            routing { sourcegraphRoutes(state) }
        }
    }

    @Test
    fun `GET models returns the mock model id`() = testApplication {
        setupSourcegraph()
        val response = client.get("/.api/llm/models")
        assertEquals(HttpStatusCode.OK, response.status)
        val data = Json.parseToJsonElement(response.bodyAsText()).jsonObject["data"]?.jsonArray
        val ids = data?.map { it.jsonObject["id"]?.jsonPrimitive?.content }
        assertEquals(listOf(MOCK_MODEL_ID), ids)
    }

    @Test
    fun `GET supported-models exposes the catalog the plugin parses`() = testApplication {
        setupSourcegraph()
        val response = client.get("/.api/modelconfig/supported-models.json")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        assertEquals(MOCK_MODEL_ID, body["defaultModels"]?.jsonObject?.get("chat")?.jsonPrimitive?.content)
        val model = body["models"]!!.jsonArray.single().jsonObject
        assertEquals(MOCK_MODEL_ID, model["modelRef"]?.jsonPrimitive?.content)
        val capabilities = model["capabilities"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("tools" in capabilities)
        assertTrue("vision" in capabilities)
        assertEquals(
            132_000,
            model["contextWindow"]?.jsonObject?.get("maxInputTokens")?.jsonPrimitive?.int,
        )
    }

    @Test
    fun `POST chat completions streams a parseable OpenAI tool-call sequence`() = testApplication {
        setupSourcegraph()
        val response = client.post("/.api/llm/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"model":"$MOCK_MODEL_ID","stream":true,"messages":[{"role":"user","content":"please read the main file"}]}""",
            )
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val sse = response.bodyAsText()

        // Default scenario (read-and-finish) turn 0 → read_file as XML-in-content + finish + usage.
        assertTrue(sse.contains("data: "), "should be SSE data frames")
        assertTrue(sse.contains("\"role\":\"assistant\""))
        assertTrue(sse.contains("<read_file>"), "tool call must appear as XML in delta.content")
        assertTrue(sse.contains("\"finish_reason\":\"tool_calls\""))
        assertTrue(sse.contains("\"prompt_tokens\":"), "must include a final usage chunk")
        assertTrue(sse.trimEnd().endsWith("[DONE]"), "must end with the DONE sentinel")

        // Each data line (except the sentinel) is a parseable JSON chunk.
        sse.lineSequence()
            .filter { it.startsWith("data: ") && it != "data: [DONE]" }
            .forEach { line ->
                Json.parseToJsonElement(line.removePrefix("data: ")) // throws if malformed
            }
    }

    @Test
    fun `POST completions stream emits Cody frames and event done`() = testApplication {
        setupSourcegraph()
        val response = client.post("/.api/completions/stream?api-version=8") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"model":"$MOCK_MODEL_ID","maxTokensToSample":1000,"messages":[{"speaker":"human","content":[{"type":"text","text":"please read the main file"}]}]}""",
            )
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val sse = response.bodyAsText()

        assertTrue(sse.contains("\"deltaText\""), "must emit Cody deltaText frames")
        assertTrue(sse.contains("<read_file>"), "tool call must appear as XML in deltaText")
        assertTrue(sse.contains("\"stopReason\":\"tool_use\""))
        assertTrue(sse.contains("event: done"), "must end with the courtesy done event")

        // The deltaText payloads are parseable JSON.
        sse.lineSequence()
            .filter { it.startsWith("data: ") }
            .forEach { line -> Json.parseToJsonElement(line.removePrefix("data: ")) }
    }

    @Test
    fun `successive chat requests advance through the scenario to completion`() = testApplication {
        setupSourcegraph()

        // Turn 0 → read_file; the conversation key is the (stable) first user message.
        val first = client.post("/.api/llm/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody("""{"stream":true,"messages":[{"role":"user","content":"[read-and-finish] review"}]}""")
        }.bodyAsText()
        assertTrue(first.contains("<read_file>"))

        // Turn 1 → attempt_completion (latest user message is a tool result, no tag → advances).
        val second = client.post("/.api/llm/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"stream":true,"messages":[{"role":"user","content":"[read-and-finish] review"},{"role":"assistant","content":"<read_file>...</read_file>"},{"role":"user","content":"RESULT of read_file: contents"}]}""",
            )
        }.bodyAsText()
        assertTrue(second.contains("<attempt_completion>"))
    }

    @Test
    fun `a registered custom scenario is streamed over the wire`() = testApplication {
        val state = SourcegraphState()
        // Register + activate a custom scenario (as POST /admin/sourcegraph/scenario/custom would).
        val registered = state.registerCustomScenario(
            """{"name":"wire-custom","turns":[
                 {"text":"poking around","toolCalls":[{"name":"glob_files","arguments":{"pattern":"**/*.kt"}}]},
                 {"toolCalls":[{"name":"attempt_completion","arguments":{"kind":"done","result":"done"}}]}
               ]}""",
        )
        assertTrue(registered is SourcegraphState.RegisterResult.Ok)
        setupSourcegraph(state)

        val sse = client.post("/.api/llm/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody("""{"stream":true,"messages":[{"role":"user","content":"anything"}]}""")
        }.bodyAsText()

        assertTrue(sse.contains("<glob_files>"), "custom turn 0 tool must be streamed")
        assertTrue(sse.contains("<pattern>**/*.kt</pattern>"))
        assertTrue(sse.contains("data: [DONE]"))
    }

    @Test
    fun `stream false returns a single non-streaming JSON object and does not advance the scenario`() = testApplication {
        setupSourcegraph()

        // Out-of-band non-streaming call (title/branch gen): single JSON object, no SSE, no [DONE].
        val resp = client.post("/.api/llm/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody("""{"stream":false,"messages":[{"role":"user","content":"Generate a session title"}]}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.bodyAsText()
        assertFalse(body.contains("data: "), "must NOT be SSE")
        assertFalse(body.contains("[DONE]"))

        val obj = Json.parseToJsonElement(body).jsonObject
        assertEquals("chat.completion", obj["object"]?.jsonPrimitive?.content)
        val choice0 = obj["choices"]!!.jsonArray[0].jsonObject
        val message = choice0["message"]!!.jsonObject
        assertEquals("assistant", message["role"]?.jsonPrimitive?.content)
        assertFalse(message["content"]?.jsonPrimitive?.content.isNullOrBlank())
        assertEquals("stop", choice0["finish_reason"]?.jsonPrimitive?.content)
        assertTrue(obj["usage"]?.jsonObject?.get("total_tokens") != null)

        // The side request did NOT advance the scenario: the next stream:true call is still turn 0.
        val streamed = client.post("/.api/llm/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody("""{"stream":true,"messages":[{"role":"user","content":"Generate a session title"}]}""")
        }.bodyAsText()
        assertTrue(
            streamed.contains("<read_file>"),
            "stream:true is still turn 0 (read_file) — stream:false must not advance the scenario",
        )
    }

    @Test
    fun `stream absent defaults to non-streaming`() = testApplication {
        setupSourcegraph()
        val body = client.post("/.api/llm/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody("""{"messages":[{"role":"user","content":"hi"}]}""")
        }.bodyAsText()
        assertFalse(body.contains("data: "))
        assertEquals("chat.completion", Json.parseToJsonElement(body).jsonObject["object"]?.jsonPrimitive?.content)
    }
}
