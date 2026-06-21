package com.workflow.orchestrator.agent.tools.background

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.agent.session.AtomicFileWriter
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Atomic per-session persistence of background completion events.
 *
 * File layout (under [sessionsBaseDir]):
 *   sessions/{sessionId}/background/pending_completions.json
 *
 * Writes are atomic via tmp-file + ATOMIC_MOVE so a crash mid-write cannot
 * leave a half-written list. Callers are expected to serialize mutations per
 * session externally if concurrent writers are possible.
 */
class BackgroundPersistence(private val sessionsBaseDir: Path) {

    private val json = Json { prettyPrint = false }
    private val log = Logger.getInstance(BackgroundPersistence::class.java)

    private fun sessionDir(sessionId: String): Path =
        sessionsBaseDir.resolve("sessions").resolve(sessionId).resolve("background").also {
            Files.createDirectories(it)
        }

    private fun pendingFile(sessionId: String): Path =
        sessionDir(sessionId).resolve("pending_completions.json")

    fun appendCompletion(sessionId: String, event: BackgroundCompletionEvent) {
        val file = pendingFile(sessionId)
        val existing = if (Files.exists(file)) loadPendingCompletions(sessionId) else emptyList()
        writeAtomic(file, existing + event)
    }

    fun consumeCompletion(sessionId: String, bgId: String) {
        val file = pendingFile(sessionId)
        if (!Files.exists(file)) return
        val filtered = loadPendingCompletions(sessionId).filter { it.bgId != bgId }
        writeAtomic(file, filtered)
    }

    fun loadPendingCompletions(sessionId: String): List<BackgroundCompletionEvent> {
        val file = pendingFile(sessionId)
        if (!Files.exists(file)) return emptyList()
        // B4: a corrupt file must not throw out of load — that would wedge BOTH appendCompletion
        // (new completions can't persist) and consumeCompletion (the corrupt file can't be drained
        // or repaired). Self-heal to empty and log loudly (mirrors MonitorPersistence).
        return runCatching {
            json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(BackgroundCompletionEvent.serializer()),
                Files.readString(file)
            )
        }.getOrElse { e ->
            log.warn("BackgroundPersistence: dropping corrupt pending_completions.json for session $sessionId", e)
            emptyList()
        }
    }

    private fun writeAtomic(target: Path, events: List<BackgroundCompletionEvent>) {
        val tmp = Files.createTempFile(target.parent, target.fileName.toString(), ".tmp")
        Files.writeString(
            tmp,
            json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(BackgroundCompletionEvent.serializer()),
                events
            )
        )
        // Owner-only perms before the move so the persisted file is rw------- (E2 policy
        // consistency — queued incidental P4 Q2). No-op on Windows.
        AtomicFileWriter.applyOwnerOnlyPerms(tmp)
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
