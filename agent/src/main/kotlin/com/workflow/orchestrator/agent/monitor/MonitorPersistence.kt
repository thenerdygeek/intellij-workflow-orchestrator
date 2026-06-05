package com.workflow.orchestrator.agent.monitor

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.agent.session.AtomicFileWriter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Atomic per-session persistence of active monitor specs.
 *
 * File layout (under [agentDir]):
 *   sessions/{sessionId}/monitors.json
 *
 * Writes are atomic via tmp-file + ATOMIC_MOVE so a crash mid-write cannot leave a
 * half-written list. Callers are expected to serialize mutations per session externally
 * if concurrent writers are possible.
 *
 * All public methods wrap their bodies in runCatching so a persistence failure never
 * propagates to the caller. [load] returns emptyList() on any error.
 */
class MonitorPersistence(private val agentDir: Path) {

    private val log = Logger.getInstance(MonitorPersistence::class.java)

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private fun monitorsFile(sessionId: String): Path {
        val dir = agentDir.resolve("sessions").resolve(sessionId)
        Files.createDirectories(dir)
        return dir.resolve("monitors.json")
    }

    /** Load all persisted [MonitorSpec]s for [sessionId]. Returns emptyList on missing file or parse error. */
    fun load(sessionId: String): List<MonitorSpec> {
        return runCatching {
            val file = monitorsFile(sessionId)
            if (!Files.exists(file)) return emptyList()
            json.decodeFromString(ListSerializer(MonitorSpec.serializer()), Files.readString(file))
        }.getOrElse { e ->
            log.warn("MonitorPersistence.load failed for session $sessionId", e)
            emptyList()
        }
    }

    /**
     * Add or replace [spec] (replace-by-id: remove any existing entry with the same id, then append).
     * Atomically writes the result.
     */
    fun add(sessionId: String, spec: MonitorSpec) {
        runCatching {
            val existing = load(sessionId)
            val updated = existing.filter { it.id != spec.id } + spec
            writeAtomic(monitorsFile(sessionId), updated)
        }.onFailure { e ->
            log.warn("MonitorPersistence.add failed for session $sessionId id=${spec.id}", e)
        }
    }

    /**
     * Remove the monitor with [id] from [sessionId]'s list.
     * If the result is empty, deletes monitors.json (Files.deleteIfExists).
     */
    fun remove(sessionId: String, id: String) {
        runCatching {
            val file = monitorsFile(sessionId)
            val updated = load(sessionId).filter { it.id != id }
            if (updated.isEmpty()) {
                Files.deleteIfExists(file)
            } else {
                writeAtomic(file, updated)
            }
        }.onFailure { e ->
            log.warn("MonitorPersistence.remove failed for session $sessionId id=$id", e)
        }
    }

    /** Delete monitors.json for [sessionId] (idempotent). */
    fun clear(sessionId: String) {
        runCatching {
            Files.deleteIfExists(monitorsFile(sessionId))
        }.onFailure { e ->
            log.warn("MonitorPersistence.clear failed for session $sessionId", e)
        }
    }

    private fun writeAtomic(target: Path, specs: List<MonitorSpec>) {
        val tmp = Files.createTempFile(target.parent, target.fileName.toString(), ".tmp")
        Files.writeString(tmp, json.encodeToString(ListSerializer(MonitorSpec.serializer()), specs))
        // Owner-only perms before the move (E2 policy consistency — mirrors BackgroundPersistence).
        AtomicFileWriter.applyOwnerOnlyPerms(tmp)
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    // ─── Pending notification persistence (Task 6E) ──────────────────────────────────────────────

    private fun notificationsFile(sessionId: String): Path {
        val dir = agentDir.resolve("sessions").resolve(sessionId)
        Files.createDirectories(dir)
        return dir.resolve("monitor-notifications.json")
    }

    /**
     * Append [text] to the pending notifications list for [sessionId].
     *
     * Called BEFORE waking the idle session so the notification survives even when the
     * wake is rejected (SKIP_GUARD / DEFER — e.g. global guard hit or another session
     * active). Mirrors [BackgroundPersistence.appendCompletion]'s persist-first pattern.
     *
     * Writes are atomic via tmp-file + ATOMIC_MOVE. runCatching-wrapped; a persistence
     * failure is logged but never propagated.
     */
    fun appendPendingNotification(sessionId: String, text: String) {
        runCatching {
            val file = notificationsFile(sessionId)
            val existing = loadPendingNotifications(sessionId)
            writeAtomicNotifications(file, existing + text)
        }.onFailure { e ->
            log.warn("MonitorPersistence.appendPendingNotification failed for session $sessionId", e)
        }
    }

    /**
     * Load all pending notification texts for [sessionId].
     * Returns emptyList() when the file is absent or contains corrupt JSON.
     */
    fun loadPendingNotifications(sessionId: String): List<String> {
        return runCatching {
            val file = notificationsFile(sessionId)
            if (!Files.exists(file)) return emptyList()
            json.decodeFromString(
                ListSerializer(String.serializer()),
                Files.readString(file)
            )
        }.getOrElse { e ->
            log.warn("MonitorPersistence.loadPendingNotifications failed for session $sessionId", e)
            emptyList()
        }
    }

    /**
     * Delete the pending notifications file for [sessionId] (idempotent).
     * Called by Task 6F after draining notifications on resume.
     */
    fun clearPendingNotifications(sessionId: String) {
        runCatching {
            Files.deleteIfExists(notificationsFile(sessionId))
        }.onFailure { e ->
            log.warn("MonitorPersistence.clearPendingNotifications failed for session $sessionId", e)
        }
    }

    private fun writeAtomicNotifications(target: Path, texts: List<String>) {
        val tmp = Files.createTempFile(target.parent, target.fileName.toString(), ".tmp")
        Files.writeString(
            tmp,
            json.encodeToString(
                ListSerializer(String.serializer()),
                texts
            )
        )
        AtomicFileWriter.applyOwnerOnlyPerms(tmp)
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
