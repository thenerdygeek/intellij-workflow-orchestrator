package com.workflow.orchestrator.cody.service

import com.workflow.orchestrator.cody.protocol.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

class CodyChatServiceTest {

    private val mockServer = mockk<com.workflow.orchestrator.cody.agent.CodyAgentServer>()
    private lateinit var service: TestCodyChatService

    @BeforeEach
    fun setUp() {
        service = TestCodyChatService(mockServer)
    }

    @Test
    fun `generateCommitMessage creates chat and returns assistant response`() = runTest {
        every { mockServer.chatNew() } returns CompletableFuture.completedFuture("chat-001")
        every { mockServer.chatSubmitMessage(any()) } returns CompletableFuture.completedFuture(
            ChatResponse(
                type = "transcript",
                messages = listOf(
                    TranscriptMessage(speaker = "human", text = "Generate commit message"),
                    TranscriptMessage(speaker = "assistant", text = "fix: handle NPE in UserService")
                )
            )
        )

        val result = service.generateCommitMessage("diff --git a/...")
        assertEquals("fix: handle NPE in UserService", result)
        verify { mockServer.chatNew() }
        verify {
            mockServer.chatSubmitMessage(match {
                it.id == "chat-001" && it.message.text.contains("diff")
            })
        }
    }

    @Test
    fun `generateCommitMessage returns null when no assistant response`() = runTest {
        every { mockServer.chatNew() } returns CompletableFuture.completedFuture("chat-002")
        every { mockServer.chatSubmitMessage(any()) } returns CompletableFuture.completedFuture(
            ChatResponse(type = "transcript", messages = emptyList())
        )
        val result = service.generateCommitMessage("diff")
        assertNull(result)
    }

    @Test
    fun `buildCommitMessagePrompt includes diff content`() {
        val prompt = service.buildCommitMessagePromptForTest("--- a/Foo.kt\n+++ b/Foo.kt")
        assertTrue(prompt.contains("--- a/Foo.kt"))
        assertTrue(prompt.contains("conventional commits"))
    }
}

class TestCodyChatService(
    private val server: com.workflow.orchestrator.cody.agent.CodyAgentServer
) {
    suspend fun generateCommitMessage(diff: String): String? {
        val chatId = server.chatNew().get()
        val prompt = buildCommitMessagePromptForTest(diff)
        val response = server.chatSubmitMessage(
            ChatSubmitParams(id = chatId, message = ChatMessage(text = prompt))
        ).get()
        return response.messages.lastOrNull { it.speaker == "assistant" }?.text
    }

    fun buildCommitMessagePromptForTest(diff: String): String =
        """Generate a concise git commit message for this diff.
           |Use conventional commits format (feat/fix/refactor/etc).
           |One line summary, optional body.
           |
           |```diff
           |$diff
           |```""".trimMargin()
}
