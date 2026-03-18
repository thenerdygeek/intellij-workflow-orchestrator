package com.workflow.orchestrator.agent.api

import com.workflow.orchestrator.agent.api.dto.*
import com.workflow.orchestrator.core.http.AuthInterceptor
import com.workflow.orchestrator.core.http.AuthScheme
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SourcegraphChatClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: SourcegraphChatClient

    // OkHttpClient without RetryInterceptor to avoid retry delays and log.error in tests
    private fun testHttpClient(tokenProvider: () -> String?): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenProvider, AuthScheme.TOKEN))
            .build()
    }

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        val tokenProvider: () -> String? = { "sgp_test-token" }
        client = SourcegraphChatClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            tokenProvider = tokenProvider,
            model = "anthropic/claude-sonnet-4",
            httpClientOverride = testHttpClient(tokenProvider)
        )
    }

    @AfterEach
    fun tearDown() { server.shutdown() }

    @Test
    fun `sendMessage sends correct request format`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"id":"chatcmpl-1","choices":[{"index":0,"message":{"role":"assistant","content":"Hello"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}
        """.trimIndent()))

        val result = client.sendMessage(
            messages = listOf(ChatMessage(role = "user", content = "Hi")),
            tools = null
        )

        assertTrue(result.isSuccess)
        val response = (result as ApiResult.Success).data
        assertEquals("Hello", response.choices.first().message.content)
        assertEquals(15, response.usage?.totalTokens)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.path!!.contains("/chat/completions"))
        assertEquals("token sgp_test-token", request.getHeader("Authorization"))
    }

    @Test
    fun `sendMessage with tools includes tool definitions`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"id":"chatcmpl-2","choices":[{"index":0,"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"/src/Main.kt\"}"}}]},"finish_reason":"tool_calls"}]}
        """.trimIndent()))

        val tools = listOf(ToolDefinition(function = FunctionDefinition(
            name = "read_file",
            description = "Read a file",
            parameters = FunctionParameters(
                properties = mapOf("path" to ParameterProperty(type = "string", description = "File path")),
                required = listOf("path")
            )
        )))

        val result = client.sendMessage(
            messages = listOf(ChatMessage(role = "user", content = "Read Main.kt")),
            tools = tools
        )

        assertTrue(result.isSuccess)
        val response = (result as ApiResult.Success).data
        val toolCall = response.choices.first().message.toolCalls?.first()
        assertNotNull(toolCall)
        assertEquals("read_file", toolCall!!.function.name)
    }

    @Test
    fun `sendMessage handles 429 rate limit`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("Rate limited"))

        val result = client.sendMessage(
            messages = listOf(ChatMessage(role = "user", content = "Hi")),
            tools = null
        )

        assertTrue(result is ApiResult.Error)
        assertEquals(ErrorType.RATE_LIMITED, (result as ApiResult.Error).type)
    }

    @Test
    fun `sendMessage handles 401 unauthorized`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))

        val result = client.sendMessage(
            messages = listOf(ChatMessage(role = "user", content = "Hi")),
            tools = null
        )

        assertTrue(result is ApiResult.Error)
        assertEquals(ErrorType.AUTH_FAILED, (result as ApiResult.Error).type)
    }
}
