package com.workflow.orchestrator.agent.ui

import com.workflow.orchestrator.agent.session.AttachmentStore
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Single ingestion path for files acquired on the JVM (FileChooser picker and
 * Swing DropTarget). Classifies each file (image -> vision path; everything
 * else -> file path read on demand), validates size + per-turn caps, gates
 * images behind visual support, stores bytes in the session's attachments dir,
 * and emits chip metadata for the webview. Bytes never cross the JCEF bridge.
 *
 * Per-turn counters live here (picker/drop only); [resetTurn] is called by
 * AgentController on send and on new-chat/clear. The JS paste path enforces its
 * own image cap separately (known minor double-count edge case).
 */
class AttachmentIngestService(
    private val sessionDirProvider: () -> Path?,
    private val settingsProvider: () -> Settings,
    private val onChip: (ChipMeta) -> Unit,
    private val onToast: (String, String) -> Unit,
    private val onFilesAttached: () -> Unit,
    private val storeFactory: (Path) -> AttachmentStore = { AttachmentStore(it) },
) {
    data class Settings(
        val imageEnabled: Boolean,
        val imageMimeWhitelist: Set<String>,
        val imageMaxBytes: Long,
        val fileMaxBytes: Long,
        val imagesPerTurnCap: Int,
        val filesPerTurnCap: Int,
    )

    data class IncomingFile(val name: String, val mime: String, val bytes: ByteArray)

    /** Metadata pushed to the webview to render a chip. */
    data class ChipMeta(
        val sha256: String,
        val mime: String,
        val size: Long,
        val originalFilename: String,
        val kind: String,            // "image" | "file"
        val path: String?,           // absolute session path; non-null only for kind == "file"
    )

    @Volatile private var imagesThisTurn = 0
    @Volatile private var filesThisTurn = 0
    private val ingestedShas = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun resetTurn() { imagesThisTurn = 0; filesThisTurn = 0; ingestedShas.clear() }

    /** Caller must be off-EDT (file I/O). */
    fun ingest(files: List<IncomingFile>) {
        val sessionDir = sessionDirProvider()
        if (sessionDir == null) {
            onToast("Start a chat before attaching files.", "error")
            return
        }
        val s = settingsProvider()
        val store = storeFactory(sessionDir)
        for (f in files) {
            val isImage = f.mime.lowercase() in s.imageMimeWhitelist
            if (isImage) ingestImage(f, s, store) else ingestFile(f, s, store)
        }
    }

    private fun ingestImage(f: IncomingFile, s: Settings, store: AttachmentStore) {
        if (!s.imageEnabled) {
            onToast("Enable visual support in Settings to attach images.", "error"); return
        }
        if (f.bytes.size > s.imageMaxBytes) {
            onToast("Image \"${f.name}\" is too large (max ${mb(s.imageMaxBytes)} MB).", "error"); return
        }
        if (imagesThisTurn >= s.imagesPerTurnCap) {
            onToast("Image limit reached (${s.imagesPerTurnCap} per message).", "error"); return
        }
        val sha = sha256(f.bytes)
        if (!ingestedShas.add(sha)) return   // dedup: same file already attached this turn (G3)
        val ref = try {
            store.storeBlocking(f.bytes, f.mime, f.name)
        } catch (e: Exception) {
            ingestedShas.remove(sha)
            onToast("Couldn't store attachment: ${e.message}", "error"); return
        }
        imagesThisTurn++
        onChip(ChipMeta(ref.sha256, f.mime, f.bytes.size.toLong(), f.name, "image", null))
    }

    private fun ingestFile(f: IncomingFile, s: Settings, store: AttachmentStore) {
        if (f.bytes.size > s.fileMaxBytes) {
            onToast("File \"${f.name}\" is too large (max ${mb(s.fileMaxBytes)} MB).", "error"); return
        }
        if (filesThisTurn >= s.filesPerTurnCap) {
            onToast("File limit reached (${s.filesPerTurnCap} per message).", "error"); return
        }
        val sha = sha256(f.bytes)
        if (!ingestedShas.add(sha)) return   // dedup: same file already attached this turn (G3)
        val path = try {
            store.storeFileBlocking(f.bytes, f.name)
        } catch (e: Exception) {
            ingestedShas.remove(sha)
            onToast("Couldn't store attachment: ${e.message}", "error"); return   // disk-write failure (G2)
        }
        filesThisTurn++
        onChip(ChipMeta(sha, f.mime, f.bytes.size.toLong(), f.name, "file", path.toString()))
        onFilesAttached()
    }

    private fun mb(bytes: Long): Long = bytes / 1_048_576L

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
