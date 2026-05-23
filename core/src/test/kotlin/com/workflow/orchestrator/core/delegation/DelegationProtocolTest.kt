package com.workflow.orchestrator.core.delegation

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels

class DelegationProtocolTest {
    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

    @Test
    fun `Ping round-trips`() {
        val msg: DelegationMessage = DelegationMessage.Ping(projectPath = "/foo/bar")
        val encoded = json.encodeToString(msg)
        val decoded = json.decodeFromString<DelegationMessage>(encoded)
        assertEquals(msg, decoded)
    }

    @Test
    fun `Pong carries responder project path`() {
        val pong = DelegationMessage.Pong(projectPath = "/users/me/frontend")
        val encoded = json.encodeToString<DelegationMessage>(pong)
        assertTrue(encoded.contains("frontend"))
        val decoded = json.decodeFromString<DelegationMessage>(encoded) as DelegationMessage.Pong
        assertEquals("/users/me/frontend", decoded.projectPath)
    }

    @Test
    fun `Connect carries briefing and delegator identity`() {
        val msg = DelegationMessage.Connect(
            delegatorIde = "ide-1234",
            delegatorRepo = "backend-api",
            delegatorSessionId = "sess-abc",
            request = "Please implement createUser endpoint",
        )
        val encoded = json.encodeToString<DelegationMessage>(msg)
        val decoded = json.decodeFromString<DelegationMessage>(encoded) as DelegationMessage.Connect
        assertEquals("Please implement createUser endpoint", decoded.request)
        assertEquals("backend-api", decoded.delegatorRepo)
    }

    @Test
    fun `AcceptResult round-trips`() {
        val accepted: DelegationMessage = DelegationMessage.AcceptResult(accepted = true)
        val rejected: DelegationMessage = DelegationMessage.AcceptResult(accepted = false, reason = "user_rejected")
        val a = json.decodeFromString<DelegationMessage>(json.encodeToString(accepted)) as DelegationMessage.AcceptResult
        val r = json.decodeFromString<DelegationMessage>(json.encodeToString(rejected)) as DelegationMessage.AcceptResult
        assertTrue(a.accepted)
        assertFalse(r.accepted)
        assertEquals("user_rejected", r.reason)
    }

    @Test
    fun `Result COMPLETED round-trips with files and branch`() {
        val msg: DelegationMessage = DelegationMessage.Result(
            status = DelegationMessage.ResultStatus.COMPLETED,
            summary = "added thing",
            filesChanged = listOf("src/a.ts", "src/b.ts"),
            branch = "feature/x",
            commit = "abc123",
            durationSeconds = 42,
        )
        val decoded = json.decodeFromString<DelegationMessage>(json.encodeToString(msg)) as DelegationMessage.Result
        assertEquals(DelegationMessage.ResultStatus.COMPLETED, decoded.status)
        assertEquals(listOf("src/a.ts", "src/b.ts"), decoded.filesChanged)
        assertEquals("feature/x", decoded.branch)
    }

    @Test
    fun `length-prefixed framing round-trips a message`() {
        val out = ByteArrayOutputStream()
        val msg: DelegationMessage = DelegationMessage.Ping("/foo")
        DelegationFraming.writeFramed(Channels.newChannel(out), msg, json)
        val bytes = out.toByteArray()
        val prefix = ByteBuffer.wrap(bytes, 0, 4).int
        assertEquals(bytes.size - 4, prefix)
        val readBack = DelegationFraming.readFramed(Channels.newChannel(ByteArrayInputStream(bytes)), json)
        assertEquals(msg, readBack)
    }

    @Test
    fun `oversized frame is rejected`() {
        val bigPrefix = ByteBuffer.allocate(4).putInt(20 * 1024 * 1024).array()
        assertThrows<DelegationFraming.FrameSizeExceeded> {
            DelegationFraming.readFramed(Channels.newChannel(ByteArrayInputStream(bigPrefix)), json)
        }
    }

    @Test
    fun `unknown fields are ignored on decode`() {
        // Encode a Ping then sneak in an unknown field — decode must still work.
        val encoded = json.encodeToString<DelegationMessage>(DelegationMessage.Ping("/x"))
        val withExtra = encoded.replace("\"projectPath\":\"/x\"", "\"projectPath\":\"/x\",\"futureField\":\"hello\"")
        val decoded = json.decodeFromString<DelegationMessage>(withExtra)
        assertTrue(decoded is DelegationMessage.Ping)
    }

    @Test
    fun `Question round-trips`() {
        val msg: DelegationMessage = DelegationMessage.Question(
            questionId = "q-1",
            text = "Which API version should I target?",
            options = listOf("v1", "v2"),
        )
        val decoded = json.decodeFromString<DelegationMessage>(json.encodeToString(msg)) as DelegationMessage.Question
        assertEquals("q-1", decoded.questionId)
        assertEquals("Which API version should I target?", decoded.text)
        assertEquals(listOf("v1", "v2"), decoded.options)
    }

    @Test
    fun `Answer round-trips`() {
        val msg: DelegationMessage = DelegationMessage.Answer(questionId = "q-1", text = "v2")
        val decoded = json.decodeFromString<DelegationMessage>(json.encodeToString(msg)) as DelegationMessage.Answer
        assertEquals("q-1", decoded.questionId)
        assertEquals("v2", decoded.text)
    }

    @Test
    fun `AnswerCanceled round-trips`() {
        val msg: DelegationMessage = DelegationMessage.AnswerCanceled(
            questionId = "q-1",
            reason = "answered_locally",
        )
        val decoded = json.decodeFromString<DelegationMessage>(json.encodeToString(msg)) as DelegationMessage.AnswerCanceled
        assertEquals("q-1", decoded.questionId)
        assertEquals("answered_locally", decoded.reason)
    }

    @Test
    fun `Heartbeat round-trips with sessionId`() {
        val msg: DelegationMessage = DelegationMessage.Heartbeat(sessionId = "sess-xyz")
        val encoded = json.encodeToString(msg)
        assertTrue(encoded.contains("sess-xyz"))
        val decoded = json.decodeFromString<DelegationMessage>(encoded) as DelegationMessage.Heartbeat
        assertEquals("sess-xyz", decoded.sessionId)
    }

    @Test
    fun `FetchTranscript carries sessionId and requestId`() {
        val msg: DelegationMessage = DelegationMessage.FetchTranscript(
            sessionId = "sess-abc",
            requestId = "req-001",
        )
        val encoded = json.encodeToString(msg)
        val decoded = json.decodeFromString<DelegationMessage>(encoded) as DelegationMessage.FetchTranscript
        assertEquals("sess-abc", decoded.sessionId)
        assertEquals("req-001", decoded.requestId)
    }

    @Test
    fun `FetchTranscriptReply ok carries transcriptPath`() {
        val msg: DelegationMessage = DelegationMessage.FetchTranscriptReply(
            requestId = "req-001",
            status = "ok",
            transcriptPath = "/tmp/transcript-export.json",
        )
        val decoded = json.decodeFromString<DelegationMessage>(json.encodeToString(msg))
            as DelegationMessage.FetchTranscriptReply
        assertEquals("ok", decoded.status)
        assertEquals("/tmp/transcript-export.json", decoded.transcriptPath)
        assertNull(decoded.error)
    }

    @Test
    fun `FetchTranscriptReply not_found carries error message`() {
        val msg: DelegationMessage = DelegationMessage.FetchTranscriptReply(
            requestId = "req-002",
            status = "not_found",
            transcriptPath = null,
            error = "session sess-gone not in index",
        )
        val decoded = json.decodeFromString<DelegationMessage>(json.encodeToString(msg))
            as DelegationMessage.FetchTranscriptReply
        assertEquals("not_found", decoded.status)
        assertNull(decoded.transcriptPath)
        assertEquals("session sess-gone not in index", decoded.error)
    }

    @Test
    fun `ChannelResume round-trips with sessionId and lastSeenState`() {
        val msg: DelegationMessage = DelegationMessage.ChannelResume(
            sessionId = "sess-abc",
            lastSeenState = "RUNNING",
        )
        val decoded = json.decodeFromString<DelegationMessage>(json.encodeToString(msg))
            as DelegationMessage.ChannelResume
        assertEquals("sess-abc", decoded.sessionId)
        assertEquals("RUNNING", decoded.lastSeenState)
    }

    @Test
    fun `ChannelResumed round-trips with currentState`() {
        val msg: DelegationMessage = DelegationMessage.ChannelResumed(
            sessionId = "sess-abc",
            currentState = "AWAITING_ANSWER",
        )
        val decoded = json.decodeFromString<DelegationMessage>(json.encodeToString(msg))
            as DelegationMessage.ChannelResumed
        assertEquals("AWAITING_ANSWER", decoded.currentState)
    }

    @Test
    fun `SessionClosed carries closeReason and optional summary`() {
        val withSummary: DelegationMessage = DelegationMessage.SessionClosed(
            sessionId = "sess-a",
            closeReason = "completed",
            summary = "All endpoints implemented.",
        )
        val noSummary: DelegationMessage = DelegationMessage.SessionClosed(
            sessionId = "sess-b",
            closeReason = "canceled",
            summary = null,
        )
        val a = json.decodeFromString<DelegationMessage>(json.encodeToString(withSummary))
            as DelegationMessage.SessionClosed
        val b = json.decodeFromString<DelegationMessage>(json.encodeToString(noSummary))
            as DelegationMessage.SessionClosed
        assertEquals("All endpoints implemented.", a.summary)
        assertNull(b.summary)
        assertEquals("canceled", b.closeReason)
    }

    @Test
    fun `SessionNotFound round-trips`() {
        val msg: DelegationMessage = DelegationMessage.SessionNotFound(sessionId = "sess-gone")
        val decoded = json.decodeFromString<DelegationMessage>(json.encodeToString(msg))
            as DelegationMessage.SessionNotFound
        assertEquals("sess-gone", decoded.sessionId)
    }

    @Test
    fun `UserTurn carries sessionId and text`() {
        val msg: DelegationMessage = DelegationMessage.UserTurn(
            sessionId = "sess-x",
            text = "Please also add a Dockerfile.",
        )
        val decoded = json.decodeFromString<DelegationMessage>(json.encodeToString(msg))
            as DelegationMessage.UserTurn
        assertEquals("sess-x", decoded.sessionId)
        assertEquals("Please also add a Dockerfile.", decoded.text)
    }

    @Test
    fun `AcceptResult carries optional bSessionId for resume`() {
        val accepted: DelegationMessage = DelegationMessage.AcceptResult(
            accepted = true,
            bSessionId = "sess-b-uuid",
        )
        val rejected: DelegationMessage = DelegationMessage.AcceptResult(
            accepted = false,
            reason = "user_rejected",
        )
        val a = json.decodeFromString<DelegationMessage>(json.encodeToString(accepted))
            as DelegationMessage.AcceptResult
        val r = json.decodeFromString<DelegationMessage>(json.encodeToString(rejected))
            as DelegationMessage.AcceptResult
        assertEquals("sess-b-uuid", a.bSessionId)
        assertEquals(true, a.accepted)
        assertNull(r.bSessionId)
        assertEquals("user_rejected", r.reason)
    }
}
