package com.workflow.orchestrator.agent.loop.queue

import com.workflow.orchestrator.agent.session.AtomicFileWriter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Atomic per-session persistence of the pending unified queue.
 * File: sessions/{sessionId}/pending_queue.json. Mirrors BackgroundPersistence's atomic write.
 * [save] writes the full durable list; an empty list deletes the file.
 */
class QueuePersistence(private val sessionsBaseDir: Path) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val serializer = ListSerializer(QueuedMessage.serializer())

    private fun file(sessionId: String): Path =
        sessionsBaseDir.resolve("sessions").resolve(sessionId).also { Files.createDirectories(it) }
            .resolve("pending_queue.json")

    fun load(sessionId: String): List<QueuedMessage> {
        val f = file(sessionId)
        if (!Files.exists(f)) return emptyList()
        return runCatching { json.decodeFromString(serializer, Files.readString(f)) }.getOrDefault(emptyList())
    }

    fun save(sessionId: String, items: List<QueuedMessage>) {
        val target = file(sessionId)
        if (items.isEmpty()) {
            Files.deleteIfExists(target)
            return
        }
        val tmp = Files.createTempFile(target.parent, target.fileName.toString(), ".tmp")
        Files.writeString(tmp, json.encodeToString(serializer, items))
        AtomicFileWriter.applyOwnerOnlyPerms(tmp)
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
