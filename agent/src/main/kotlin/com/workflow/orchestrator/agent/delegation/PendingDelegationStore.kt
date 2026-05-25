package com.workflow.orchestrator.agent.delegation

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

@Serializable
data class PendingDelegationRequest(
    val delegatorIde: String,
    val delegatorRepo: String,
    val delegatorSessionId: String,
    val requestPreview: String,
    val nonce: String,
    val createdAt: Long,
)

/** File-backed store of pending cross-IDE delegation requests under [baseDir]/pending-delegation/. */
class PendingDelegationStore(baseDir: Path) {
    private val dir: Path = baseDir.resolve("pending-delegation")
    private val json = Json { ignoreUnknownKeys = true }

    fun write(req: PendingDelegationRequest) {
        Files.createDirectories(dir)
        val tmp = dir.resolve("${req.nonce}.json.tmp")
        val dst = dir.resolve("${req.nonce}.json")
        Files.writeString(tmp, json.encodeToString(PendingDelegationRequest.serializer(), req))
        Files.move(tmp, dst, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    /** Returns non-expired requests; deletes expired ones as a side effect. */
    fun readFresh(ttlMillis: Long): List<PendingDelegationRequest> {
        if (!Files.isDirectory(dir)) return emptyList()
        val now = System.currentTimeMillis()
        val out = mutableListOf<PendingDelegationRequest>()
        Files.newDirectoryStream(dir, "*.json").use { stream ->
            for (p in stream) {
                val req = try { json.decodeFromString(PendingDelegationRequest.serializer(), Files.readString(p)) }
                          catch (_: Exception) { runCatching { Files.deleteIfExists(p) }; continue }
                if (now - req.createdAt > ttlMillis) runCatching { Files.deleteIfExists(p) } else out += req
            }
        }
        return out
    }

    fun markDeclined(nonce: String) {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("$nonce.declined"), "")
    }

    fun isDeclined(nonce: String): Boolean = Files.exists(dir.resolve("$nonce.declined"))

    fun clear(nonce: String) {
        runCatching { Files.deleteIfExists(dir.resolve("$nonce.json")) }
        runCatching { Files.deleteIfExists(dir.resolve("$nonce.declined")) }
    }
}
