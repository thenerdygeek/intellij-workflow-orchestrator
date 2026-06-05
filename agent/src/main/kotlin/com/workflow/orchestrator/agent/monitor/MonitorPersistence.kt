package com.workflow.orchestrator.agent.monitor

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.agent.session.AtomicFileWriter
import kotlinx.serialization.builtins.ListSerializer
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
}
