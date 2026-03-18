package com.workflow.orchestrator.agent.brain

import com.workflow.orchestrator.agent.api.dto.ChatMessage
import com.workflow.orchestrator.core.http.AuthInterceptor
import com.workflow.orchestrator.core.http.AuthScheme
import com.workflow.orchestrator.core.model.ApiResult
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class OpenAiCompatBrainTest {
    private lateinit var server: MockWebServer
    private lateinit var brain: OpenAiCompatBrain

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        val tokenProvider: () -> String? = { "sgp_test" }
        brain = OpenAiCompatBrain(
            sourcegraphUrl = server.url("/").toString().trimEnd('/'),
            tokenProvider = tokenProvider,
            model = "anthropic/claude-sonnet-4",
            httpClientOverride = OkHttpClient.Builder()
                .addInterceptor(AuthInterceptor(tokenProvider, AuthScheme.TOKEN))
                .build()
        )
    }

    @AfterEach
    fun tearDown() { server.shutdown() }

    @Test
    fun `chat delegates to SourcegraphChatClient`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"id":"1","choices":[{"index":0,"message":{"role":"assistant","content":"Done"},"finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":2,"total_tokens":7}}
        """.trimIndent()))

        val result = brain.chat(
            messages = listOf(ChatMessage(role = "user", content = "Plan this task"))
        )

        assertTrue(result.isSuccess)
        assertEquals("Done", (result as ApiResult.Success).data.choices.first().message.content)
    }

    @Test
    fun `estimateTokens returns consistent estimates`() {
        val tokens = brain.estimateTokens("Hello world")
        assertTrue(tokens > 0)
        assertTrue(tokens < 10)
    }

    @Test
    fun `modelId returns configured model`() {
        assertEquals("anthropic/claude-sonnet-4", brain.modelId)
    }
}
