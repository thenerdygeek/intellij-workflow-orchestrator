package com.workflow.orchestrator.agent.delegation

import com.workflow.orchestrator.core.delegation.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class PlanTwoE2ETest {
    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

    @Test
    fun `question answer round-trip happy path`(@TempDir tmp: Path) = runBlocking {
        val socketPath = tmp.resolve("q.sock")
        coroutineScope {
            // Side B: server that, on Accept, sends a Question, reads back an Answer,
            // then sends the Result.
            val server = DelegationServer(
                socketPath = socketPath,
                projectPath = "/projects/B",
                onConnect = { _, replyWith, readMessage, closeChannel ->
                    replyWith(DelegationMessage.AcceptResult(accepted = true))
                    replyWith(
                        DelegationMessage.Question(
                            questionId = "q-1",
                            text = "Which API version should I target?",
                            options = listOf("v1", "v2"),
                        )
                    )
                    val ans = readMessage() as DelegationMessage.Answer
                    assertEquals("q-1", ans.questionId)
                    replyWith(
                        DelegationMessage.Result(
                            status = DelegationMessage.ResultStatus.COMPLETED,
                            summary = "Targeted ${ans.text}",
                            durationSeconds = 1,
                        )
                    )
                    closeChannel()
                },
                scope = this,
            )
            server.start()
            try {
                val (channel, ack) = DelegationClient.connectAndAwaitAccept(
                    socketPath,
                    DelegationMessage.Connect("A", "b", "s", "go"),
                )!!
                assertTrue(ack.accepted)
                val q = DelegationFraming.readFramed(channel, json) as DelegationMessage.Question
                assertEquals("q-1", q.questionId)
                DelegationFraming.writeFramed(
                    channel,
                    DelegationMessage.Answer(questionId = "q-1", text = "v2"),
                    json,
                )
                val result = withTimeout(2_000) {
                    DelegationFraming.readFramed(channel, json)
                } as DelegationMessage.Result
                assertEquals(DelegationMessage.ResultStatus.COMPLETED, result.status)
                assertEquals("Targeted v2", result.summary)
                channel.close()
            } finally {
                server.stop()
            }
        }
    }

    @Test
    fun `local short-circuit sends AnswerCanceled before Result`(@TempDir tmp: Path) = runBlocking {
        val socketPath = tmp.resolve("sc.sock")
        coroutineScope {
            val server = DelegationServer(
                socketPath = socketPath,
                projectPath = "/projects/B",
                onConnect = { _, replyWith, _readMessage, closeChannel ->
                    replyWith(DelegationMessage.AcceptResult(accepted = true))
                    replyWith(
                        DelegationMessage.Question(
                            questionId = "q-2",
                            text = "Local-answer test",
                        )
                    )
                    // Simulate IDE-B human answering locally — skip reading Answer.
                    replyWith(
                        DelegationMessage.AnswerCanceled(
                            questionId = "q-2",
                            reason = "answered_locally",
                        )
                    )
                    replyWith(
                        DelegationMessage.Result(
                            status = DelegationMessage.ResultStatus.COMPLETED,
                            summary = "Done, used local answer",
                            durationSeconds = 1,
                        )
                    )
                    closeChannel()
                },
                scope = this,
            )
            server.start()
            try {
                val (channel, _) = DelegationClient.connectAndAwaitAccept(
                    socketPath,
                    DelegationMessage.Connect("A", "b", "s", "go"),
                )!!
                val q = DelegationFraming.readFramed(channel, json) as DelegationMessage.Question
                assertEquals("q-2", q.questionId)
                val canceled = DelegationFraming.readFramed(channel, json) as DelegationMessage.AnswerCanceled
                assertEquals("q-2", canceled.questionId)
                assertEquals("answered_locally", canceled.reason)
                val result = DelegationFraming.readFramed(channel, json) as DelegationMessage.Result
                assertEquals(DelegationMessage.ResultStatus.COMPLETED, result.status)
                channel.close()
            } finally {
                server.stop()
            }
        }
    }

    @Test
    fun `PendingQuestionToken — exactly one of two concurrent answers wins`() {
        val token = PendingQuestionToken()
        val deferred = kotlinx.coroutines.CompletableDeferred<String>()
        token.armIfClear("q-3", deferred)
        val localWon = token.tryResolve("q-3", "from-local")
        val remoteWon = token.tryResolve("q-3", "from-remote")
        assertTrue(localWon xor remoteWon, "exactly one tryResolve must win")
    }
}
