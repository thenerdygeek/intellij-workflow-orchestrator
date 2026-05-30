package com.workflow.orchestrator.agent.tools.background

import com.workflow.orchestrator.agent.session.AtomicFileWriter
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * BUG #2 — atomic per-session persistence of cross-IDE delegation result/question nudges
 * that arrive while the delegator session's loop is idle.
 *
 * Mirrors [BackgroundPersistence] (the background-process completion store) so both
 * async-completion paths persist-before-auto-wake and REPLAY on the next resume rather
 * than being silently dropped on a guard rejection. Shares the per-session
 * `sessions/{sessionId}/background/` directory; the nudges live in a sibling file
 * `pending_delegation_nudges.json`.
 *
 * File layout (under [sessionsBaseDir]):
 *   sessions/{sessionId}/background/pending_delegation_nudges.json
 *
 * Writes are atomic via tmp-file + ATOMIC_MOVE so a crash mid-write cannot leave a
 * half-written list. Callers serialize mutations per session externally.
 */
class DelegationNudgePersistence(private val sessionsBaseDir: Path) {

    private val json = Json { prettyPrint = false }

    private fun sessionDir(sessionId: String): Path =
        sessionsBaseDir.resolve("sessions").resolve(sessionId).resolve("background").also {
            Files.createDirectories(it)
        }

    private fun pendingFile(sessionId: String): Path =
        sessionDir(sessionId).resolve("pending_delegation_nudges.json")

    /** Append a nudge, returning the generated id (so a caller can [consumeNudge] it). */
    fun appendNudge(sessionId: String, text: String): String {
        val nudge = DelegationNudge(
            id = UUID.randomUUID().toString(),
            text = text,
            occurredAt = System.currentTimeMillis(),
        )
        val file = pendingFile(sessionId)
        val existing = if (Files.exists(file)) loadPendingNudges(sessionId) else emptyList()
        writeAtomic(file, existing + nudge)
        return nudge.id
    }

    fun consumeNudge(sessionId: String, id: String) {
        val file = pendingFile(sessionId)
        if (!Files.exists(file)) return
        val filtered = loadPendingNudges(sessionId).filter { it.id != id }
        writeAtomic(file, filtered)
    }

    fun loadPendingNudges(sessionId: String): List<DelegationNudge> {
        val file = pendingFile(sessionId)
        if (!Files.exists(file)) return emptyList()
        return json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(DelegationNudge.serializer()),
            Files.readString(file)
        )
    }

    private fun writeAtomic(target: Path, nudges: List<DelegationNudge>) {
        val tmp = Files.createTempFile(target.parent, target.fileName.toString(), ".tmp")
        Files.writeString(
            tmp,
            json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(DelegationNudge.serializer()),
                nudges
            )
        )
        // Owner-only perms before the move (E2 policy consistency, mirrors BackgroundPersistence).
        AtomicFileWriter.applyOwnerOnlyPerms(tmp)
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
