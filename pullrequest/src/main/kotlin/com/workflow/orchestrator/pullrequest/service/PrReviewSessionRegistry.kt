package com.workflow.orchestrator.pullrequest.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Service(Service.Level.PROJECT)
class PrReviewSessionRegistry(private val project: Project) {

    @Serializable
    data class Entry(val sessionId: String, val status: String)

    @Serializable
    private data class RegistryFile(val entries: Map<String, Entry>)

    private val lock = ReentrantLock()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun register(prId: String, sessionId: String, status: String) = lock.withLock {
        val current = readFile().toMutableMap()
        current[prId] = Entry(sessionId, status)
        writeFile(current)
    }

    fun updateStatus(prId: String, status: String) = lock.withLock {
        val current = readFile().toMutableMap()
        val existing = current[prId] ?: return@withLock
        current[prId] = existing.copy(status = status)
        writeFile(current)
    }

    fun get(prId: String): Entry? = lock.withLock { readFile()[prId] }

    fun all(): Map<String, Entry> = lock.withLock { readFile().toMap() }

    private fun filePath(): Path {
        val home = System.getProperty("user.home")
        val projectName = project.name
        val projectPath = project.basePath ?: "unknown"
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(projectPath.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(6)
        return Path.of(home, ".workflow-orchestrator", "$projectName-$hash", "agent", "pr-review-sessions.json")
    }

    private fun readFile(): Map<String, Entry> {
        val path = filePath()
        if (!Files.exists(path)) return emptyMap()
        return runCatching {
            json.decodeFromString<RegistryFile>(Files.readString(path)).entries
        }.getOrElse { emptyMap() }
    }

    private fun writeFile(entries: Map<String, Entry>) {
        val path = filePath()
        Files.createDirectories(path.parent)
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.writeString(tmp, json.encodeToString(RegistryFile(entries)))
        Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
