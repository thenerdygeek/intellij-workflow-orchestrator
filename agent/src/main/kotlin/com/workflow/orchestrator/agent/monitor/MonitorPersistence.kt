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
 * Atomic per-session persistence of active monitor specs and (legacy-read-only) pending
 * notification texts.
 *
 * File layout (under [agentDir]):
 *   sessions/{sessionId}/monitors.json            — active MonitorSpec list (read/write)
 *   sessions/{sessionId}/monitor-notifications.json — one-release legacy reader (Task 2.5);
 *                                                      write path removed in Task 2.4 (notifications
 *                                                      are now persisted via UnifiedMessageQueue).
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

    // ─── Pending notification persistence — READER ONLY (Task 2.4 / Task 2.5) ─────────────────────
    //
    // The WRITER half (appendPendingNotification + writeAtomicNotifications) was removed in
    // Task 2.4: notifications are now persisted durably via UnifiedMessageQueue (kind=MONITOR,
    // durable=true). This reader half is kept for one-release legacy compatibility (Task 2.5)
    // so that sessions persisted before the Task 2.4 migration can still drain their
    // monitor-notifications.json file on resume.

    private fun notificationsFile(sessionId: String): Path {
        val dir = agentDir.resolve("sessions").resolve(sessionId)
        Files.createDirectories(dir)
        return dir.resolve("monitor-notifications.json")
    }

    /**
     * Load all pending notification texts for [sessionId] from the legacy
     * `monitor-notifications.json` file.
     *
     * Returns emptyList() when the file is absent or contains corrupt JSON. The file will be
     * absent for any session created after Task 2.4; this method is retained for the one-release
     * legacy resume reader in Task 2.5.
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
     * Called by Task 6F / clearPersistedMonitors after draining notifications on resume.
     */
    fun clearPendingNotifications(sessionId: String) {
        runCatching {
            Files.deleteIfExists(notificationsFile(sessionId))
        }.onFailure { e ->
            log.warn("MonitorPersistence.clearPendingNotifications failed for session $sessionId", e)
        }
    }
}
