package com.workflow.orchestrator.agent.delegation

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * One persisted outbound delegation handle entry.
 *
 * Plan 4 spec §3.2.
 */
@Serializable
data class PersistentHandleEntry(
    val handleId: String,
    val targetProjectPath: String,
    val targetRepoName: String,
    val bSessionId: String,
    val lastSeenState: String,
    val createdAt: Long,
)

/**
 * On-disk envelope so the file is self-describing.
 */
@Serializable
private data class HandleStoreFile(
    val schemaVersion: Int,
    val handles: List<PersistentHandleEntry>,
)

/**
 * Atomic per-session JSON persistence for outbound delegation handles.
 * Stored at `{sessionDir}/delegation-handles.json` with sibling `.tmp` for
 * the write-then-rename safety pattern used elsewhere in the codebase.
 *
 * Plan 4 spec §3.2.
 */
class PersistentHandleStore(private val sessionDir: Path) {

    private val mainFile = sessionDir.resolve(FILE_NAME)
    private val tmpFile = sessionDir.resolve("$FILE_NAME.tmp")
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): List<PersistentHandleEntry> {
        if (!Files.exists(mainFile)) return emptyList()
        return try {
            val text = Files.readString(mainFile)
            val envelope = json.decodeFromString<HandleStoreFile>(text)
            if (envelope.schemaVersion != SCHEMA_VERSION) {
                LOG.warn(
                    "PersistentHandleStore: unknown schemaVersion ${envelope.schemaVersion} " +
                        "in ${mainFile.toAbsolutePath()} — returning empty"
                )
                emptyList()
            } else {
                envelope.handles
            }
        } catch (e: Exception) {
            LOG.warn("PersistentHandleStore: failed to parse ${mainFile.toAbsolutePath()}", e)
            emptyList()
        }
    }

    fun save(handles: List<PersistentHandleEntry>) {
        Files.createDirectories(sessionDir)
        val payload = json.encodeToString(HandleStoreFile(SCHEMA_VERSION, handles))
        Files.writeString(tmpFile, payload)
        Files.move(tmpFile, mainFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        private const val FILE_NAME = "delegation-handles.json"
        const val SCHEMA_VERSION = 1
        private val LOG = Logger.getInstance(PersistentHandleStore::class.java)
    }
}
