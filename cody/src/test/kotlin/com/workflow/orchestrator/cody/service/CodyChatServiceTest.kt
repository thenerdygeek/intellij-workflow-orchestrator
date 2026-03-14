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
        every { mockServer.chatNew(any()) } returns CompletableFuture.completedFuture("chat-001")
        every { mockServer.chatSubmitMessage(any()) } returns CompletableFuture.completedFuture(
            ChatResponse(
                type = "transcript",
                messages = listOf(
                    TranscriptMessage(speaker = "human", text = "Generate commit message"),
                    TranscriptMessage(speaker = "assistant", text = "fix: handle NPE in UserService")
                )
            )
        )

        val result = service.generateCommitMessage("Generate a commit message for these changes")
        assertEquals("fix: handle NPE in UserService", result)
        verify { mockServer.chatNew(any()) }
        verify {
            mockServer.chatSubmitMessage(match {
                it.id == "chat-001" && it.message.text.contains("Generate a commit message")
            })
        }
    }

    @Test
    fun `generateCommitMessage returns null when no assistant response`() = runTest {
        every { mockServer.chatNew(any()) } returns CompletableFuture.completedFuture("chat-002")
        every { mockServer.chatSubmitMessage(any()) } returns CompletableFuture.completedFuture(
            ChatResponse(type = "transcript", messages = emptyList())
        )
        val result = service.generateCommitMessage("Generate a commit message")
        assertNull(result)
    }

    @Test
    fun `generateCommitMessage sends context items with ranges`() = runTest {
        every { mockServer.chatNew(any()) } returns CompletableFuture.completedFuture("chat-003")
        every { mockServer.chatSubmitMessage(any()) } returns CompletableFuture.completedFuture(
            ChatResponse(
                type = "transcript",
                messages = listOf(
                    TranscriptMessage(speaker = "assistant", text = "feat: add new endpoint")
                )
            )
        )

        val contextItems = listOf(
            ContextFile.fromPath(
                "/src/main/kotlin/Foo.kt",
                Range(start = Position(line = 10, character = 0), end = Position(line = 25, character = 0))
            ),
            ContextFile.fromPath("/src/main/kotlin/Bar.kt")
        )
        val result = service.generateCommitMessage("Generate a commit message", contextItems)
        assertEquals("feat: add new endpoint", result)
        verify {
            mockServer.chatSubmitMessage(match {
                it.message.contextItems.size == 2 &&
                    it.message.contextItems[0].uri.fsPath == "/src/main/kotlin/Foo.kt" &&
                    it.message.contextItems[0].range != null &&
                    it.message.contextItems[0].range!!.start.line == 10 &&
                    it.message.contextItems[1].uri.fsPath == "/src/main/kotlin/Bar.kt" &&
                    it.message.contextItems[1].range == null
            })
        }
    }
}

/**
 * Test helper that mirrors CodyChatService without the IntelliJ Project dependency.
 */
class TestCodyChatService(
    private val server: com.workflow.orchestrator.cody.agent.CodyAgentServer
) {
    suspend fun generateCommitMessage(
        prompt: String,
        contextItems: List<ContextFile> = emptyList()
    ): String? {
        val chatId = server.chatNew().get()
        val response = server.chatSubmitMessage(
            ChatSubmitParams(
                id = chatId,
                message = ChatMessage(
                    text = prompt,
                    contextItems = contextItems
                )
            )
        ).get()
        return response.messages.lastOrNull { it.speaker == "assistant" }?.text
    }
}
