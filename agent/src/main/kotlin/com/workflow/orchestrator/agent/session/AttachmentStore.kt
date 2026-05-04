package com.workflow.orchestrator.agent.session

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Per-session content-addressed image attachment store.
 *
 * Files live at `{sessionDir}/attachments/<sha256>.<ext>`. sha256 dedup is
 * within-session only — the same image attached to 5 different sessions
 * results in 5 copies on disk. This is intentional (see spec §Persistence
 * "GC of orphaned attachments — REVISED for safe v1"): keeping attachments
 * inside the session directory makes `MessageStateHandler.deleteSession()`
 * a safe recursive delete with no orphan-on-delete footgun.
 *
 * Atomic write order (cross-file durability):
 *   1. `store(bytes, mime, name)` writes the attachment first using the
 *      same `.tmp + Files.move(ATOMIC_MOVE, REPLACE_EXISTING)` pattern as
 *      [AtomicFileWriter].
 *   2. ONLY after [store] succeeds, the caller appends the JSON ref via
 *      [MessageStateHandler.addToApiConversationHistory].
 *
 * Failure modes:
 *   - Failure at step 1 → no JSON ref written, no UI corruption.
 *   - Failure at step 2 → leaves an orphan file (benign — no UI corruption,
 *     no crash on next session load). v1 ships without auto-GC; v2 may add a
 *     sweep-on-load that deletes unreferenced files in `attachments/`.
 *
 * Phase 4 of multimodal-agent plan.
 */
class AttachmentStore(private val sessionDir: Path) {

    private val attachmentsDir: Path = sessionDir.resolve("attachments")

    init {
        Files.createDirectories(attachmentsDir)
    }

    /**
     * Writes [bytes] to `attachments/<sha256>.<ext>`. Idempotent: if a file
     * with the same sha256 already exists, returns a ref pointing at it
     * without rewriting. The returned [AttachmentRef] always carries the
     * caller-supplied [originalFilename] and [mime] so the UI can preserve
     * per-attachment metadata even when the on-disk path is shared.
     */
    suspend fun store(
        bytes: ByteArray,
        mime: String,
        originalFilename: String?,
    ): AttachmentRef = withContext(Dispatchers.IO) {
        storeBlocking(bytes, mime, originalFilename)
    }

    /**
     * Synchronous variant of [store] for callers running on threads that
     * have no coroutine context (e.g. CEF's network thread inside
     * [com.workflow.orchestrator.agent.ui.AttachmentUploadHandler]).
     * The work is pure JDK file I/O so a thread switch isn't required —
     * the caller must already be off-EDT.
     */
    fun storeBlocking(
        bytes: ByteArray,
        mime: String,
        originalFilename: String?,
    ): AttachmentRef {
        val sha = sha256(bytes)
        val ext = mimeToExtension(mime)
        val finalPath = pathFor(sha, ext)
        if (!Files.exists(finalPath)) {
            // Same atomic-move pattern as AtomicFileWriter:
            //   write to .tmp, then atomic-move to final.
            val tmpPath = attachmentsDir.resolve(
                "$sha.$ext.tmp.${System.currentTimeMillis()}.${System.nanoTime()}"
            )
            try {
                Files.write(tmpPath, bytes)
                Files.move(
                    tmpPath,
                    finalPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (e: Exception) {
                // Best-effort cleanup on failure
                runCatching { Files.deleteIfExists(tmpPath) }
                throw e
            }
        }
        return AttachmentRef(
            sha256 = sha,
            mime = mime,
            size = bytes.size.toLong(),
            originalFilename = originalFilename,
            onDiskPath = finalPath
        )
    }

    /**
     * Returns the bytes stored under [sha256], or null if no matching file
     * exists in this session's `attachments/` dir.
     */
    suspend fun read(sha256: String): ByteArray? = withContext(Dispatchers.IO) {
        readBlocking(sha256)
    }

    /**
     * Synchronous variant of [read] — see [storeBlocking] for rationale.
     * Caller must be off-EDT.
     */
    fun readBlocking(sha256: String): ByteArray? {
        if (!Files.exists(attachmentsDir)) return null
        val match = Files.list(attachmentsDir).use { stream ->
            stream
                .filter { it.fileName.toString().startsWith("$sha256.") }
                .findFirst()
                .orElse(null)
        }
        return match?.let { Files.readAllBytes(it) }
    }

    /** Returns the absolute path where an attachment with [sha256] and [ext] would (or does) live. */
    fun pathFor(sha256: String, ext: String): Path = attachmentsDir.resolve("$sha256.$ext")

    /**
     * Returns the on-disk extension for [sha256] (e.g. "png", "jpg") or null
     * if no file with that prefix exists. Used by [com.workflow.orchestrator.agent.ui.AttachmentReadHandler]
     * to set Content-Type on `<img src="…/attachments/<sha>">` responses.
     * Synchronous JDK file ops — caller must already be off-EDT.
     */
    fun findExtensionForBlocking(sha256: String): String? {
        if (!Files.exists(attachmentsDir)) return null
        return Files.list(attachmentsDir).use { stream ->
            stream
                .filter { it.fileName.toString().startsWith("$sha256.") }
                .findFirst()
                .orElse(null)
        }?.fileName?.toString()?.substringAfterLast(".", missingDelimiterValue = "")?.takeIf { it.isNotBlank() }
    }

    private fun sha256(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun mimeToExtension(mime: String): String = when (mime.lowercase()) {
        "image/png" -> "png"
        "image/jpeg" -> "jpg"
        "image/jpg" -> "jpg"
        "image/webp" -> "webp"
        "image/heic" -> "heic"
        "image/heif" -> "heif"
        "image/gif" -> "gif"
        else -> "bin"
    }
}

/**
 * Reference returned by [AttachmentStore.store]. The caller writes this
 * (minus [onDiskPath]) into `api_conversation_history.json` as a
 * [ContentBlock.ImageRef] block.
 */
data class AttachmentRef(
    val sha256: String,
    val mime: String,
    val size: Long,
    val originalFilename: String?,
    val onDiskPath: Path,
)
