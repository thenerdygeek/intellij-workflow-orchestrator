package com.workflow.orchestrator.core.delegation

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel

/**
 * Wire messages for cross-IDE delegation IPC.
 *
 * Transport: length-prefixed JSON over Unix Domain Sockets. Each frame is
 * `[4-byte big-endian length][JSON bytes]`. Frame size cap: 10 MiB.
 *
 * Polymorphic via kotlinx.serialization's `classDiscriminator = "type"` (set by callers
 * when configuring [Json]). Forward compatibility: receivers ignore unknown fields
 * (`Json { ignoreUnknownKeys = true }`).
 *
 * Spec: docs/superpowers/specs/2026-05-22-cross-ide-agent-delegation-design.md §6 / §7.
 */
@Serializable
sealed class DelegationMessage {

    /** Liveness probe sent by the outbound side. */
    @Serializable
    data class Ping(val projectPath: String) : DelegationMessage()

    /** Response to Ping. Includes the responder's project path so the prober can confirm match. */
    @Serializable
    data class Pong(val projectPath: String) : DelegationMessage()

    /** First-contact delegation. Carries the full briefing and delegator identity. */
    @Serializable
    data class Connect(
        val delegatorIde: String,
        val delegatorRepo: String,
        val delegatorSessionId: String,
        val request: String,
    ) : DelegationMessage()

    /** Receiver's verdict on an incoming [Connect]. */
    @Serializable
    data class AcceptResult(val accepted: Boolean, val reason: String? = null) : DelegationMessage()

    /**
     * Terminal result from Agent-B back to Agent-A.
     * MVP: only COMPLETED / CANCELED / REJECTED / FAILED.
     */
    @Serializable
    data class Result(
        val status: ResultStatus,
        val summary: String = "",
        val filesChanged: List<String> = emptyList(),
        val branch: String? = null,
        val commit: String? = null,
        val durationSeconds: Long = 0,
        val reason: String? = null,
    ) : DelegationMessage()

    @Serializable
    enum class ResultStatus { COMPLETED, CANCELED, REJECTED, FAILED }

    /** IDE-B → IDE-A. Agent-B has raised a clarifying question; loop is paused. */
    @Serializable
    data class Question(
        /** Unique ID for this question — used to correlate the eventual Answer. */
        val questionId: String,
        val text: String,
        /** Free-form options the LLM may have suggested (UI hint; empty if not applicable). */
        val options: List<String> = emptyList(),
    ) : DelegationMessage()

    /** IDE-A → IDE-B. Carries the answer text for a previously-sent Question. */
    @Serializable
    data class Answer(
        val questionId: String,
        val text: String,
    ) : DelegationMessage()

    /**
     * IDE-B → IDE-A. The pending question was answered locally (the IDE-B human
     * typed an answer in the session tab). IDE-A must rescind any pending
     * confirmation UI for this questionId.
     */
    @Serializable
    data class AnswerCanceled(
        val questionId: String,
        val reason: String = "answered_locally",
    ) : DelegationMessage()

    /**
     * IDE-B → IDE-A. Liveness signal. Emitted every 60 s by [HeartbeatScheduler] while the
     * delegated session is in any non-terminal state. The outbound side resets its
     * `lastSeenAt` on receipt and resets the idle timer.
     *
     * Plan 3 spec §4.1.
     */
    @Serializable
    data class Heartbeat(val sessionId: String) : DelegationMessage()

    /**
     * IDE-A → IDE-B. Request the on-disk transcript for a delegated session.
     * [requestId] is a UUID echoed back in [FetchTranscriptReply] so concurrent
     * requests can be correlated.
     *
     * Plan 3 spec §4.1.
     */
    @Serializable
    data class FetchTranscript(
        val sessionId: String,
        val requestId: String,
    ) : DelegationMessage()

    /**
     * IDE-B → IDE-A. Response to a [FetchTranscript].
     * - `status="ok"` — session exists, transcript serialized to disk, [transcriptPath] populated.
     * - `status="not_found"` — sessionId is not in IDE-B's sessions index (pruned or never existed).
     * - `status="expired"` — session exists but transcript export has been cleaned up (reserved
     *   for future TTL behavior; v1 keeps exports for the life of the session).
     *
     * Plan 3 spec §4.1.
     */
    @Serializable
    data class FetchTranscriptReply(
        val requestId: String,
        val status: String,
        val transcriptPath: String? = null,
        val error: String? = null,
    ) : DelegationMessage()
}

/** Helpers for length-prefixed JSON framing over NIO channels. */
object DelegationFraming {
    const val MAX_FRAME_BYTES = 10 * 1024 * 1024 // 10 MiB

    class FrameSizeExceeded(size: Int) :
        IOException("frame size $size exceeds max $MAX_FRAME_BYTES")

    fun writeFramed(channel: WritableByteChannel, msg: DelegationMessage, json: Json) {
        val payload = json.encodeToString(msg).toByteArray(Charsets.UTF_8)
        if (payload.size > MAX_FRAME_BYTES) throw FrameSizeExceeded(payload.size)
        val buf = ByteBuffer.allocate(4 + payload.size)
        buf.putInt(payload.size)
        buf.put(payload)
        buf.flip()
        while (buf.hasRemaining()) channel.write(buf)
    }

    fun readFramed(channel: ReadableByteChannel, json: Json): DelegationMessage {
        val lenBuf = ByteBuffer.allocate(4)
        readFully(channel, lenBuf)
        lenBuf.flip()
        val size = lenBuf.int
        if (size <= 0 || size > MAX_FRAME_BYTES) throw FrameSizeExceeded(size)
        val payload = ByteBuffer.allocate(size)
        readFully(channel, payload)
        payload.flip()
        val bytes = ByteArray(size).also { payload.get(it) }
        return json.decodeFromString(String(bytes, Charsets.UTF_8))
    }

    private fun readFully(channel: ReadableByteChannel, buf: ByteBuffer) {
        while (buf.hasRemaining()) {
            val n = channel.read(buf)
            if (n < 0) throw IOException("unexpected EOF")
        }
    }
}
