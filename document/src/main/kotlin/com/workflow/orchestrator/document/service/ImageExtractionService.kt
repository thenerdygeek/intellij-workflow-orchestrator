package com.workflow.orchestrator.document.service

import com.workflow.orchestrator.core.services.SessionDownloadDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Saves embedded image bytes from extracted documents to a per-session on-disk location
 * and returns the absolute path. Subsequent tasks (P2T2-P2T5) wire this into the
 * DOCX/XLSX/PPTX/HTML extractors so emitted [DocumentBlock.EmbeddedFileRef] blocks carry
 * an actionable path for the `view_image` agent tool.
 *
 * ## Path layout
 *
 * `{downloadsRoot}/document-{sha6OfDocKey}/image-{ordinal}-{sha6OfBytes}.{ext}`
 *
 * - `downloadsRoot` comes from the [downloadDirProvider]; defaults to `SessionDownloadDir.current()`
 *   which returns `{sessionDir}/downloads/` for agent-wrapped calls and `null` otherwise.
 * - `sha6OfDocKey` is the first 6 hex chars of the SHA-256 of the [docKey] passed to [save].
 *   Callers should pass a stable key (e.g. the document's absolute path) so re-extracting
 *   the same doc lands in the same dir.
 * - `ordinal` is incremented per save call WITHIN a single [ImageExtractionService] instance —
 *   it's NOT global. Tests construct a fresh instance per case.
 * - `sha6OfBytes` is the first 6 hex chars of SHA-256 of [bytes]; identical bytes reuse the
 *   same filename. The full filename also embeds the ordinal so order-of-encounter is preserved
 *   for the LLM's reading order, even when bytes hash-collide.
 *
 * ## Idempotency
 *
 * If the target file already exists (same bytes, same doc, same ordinal), [save] is a no-op
 * — it returns the existing path without rewriting. This makes the operation safe under
 * concurrent retries and across cached PDF re-reads.
 *
 * ## Fallback
 *
 * When [downloadDirProvider] returns null (non-agent caller, unit test without
 * `SessionDownloadDir` installed), bytes land under `java.io.tmpdir/workflow-document-images/`
 * with the same sub-path. Matches the `jira.download_attachment` fallback behaviour.
 */
class ImageExtractionService(
    private val downloadDirProvider: suspend () -> Path? = { SessionDownloadDir.current() },
) {

    /**
     * Saves [bytes] to disk and returns the absolute path.
     *
     * @param bytes         Raw image bytes (already validated by the caller — this method does
     *                      not check MIME or size).
     * @param docKey        Stable key identifying the source document; same key → same per-doc dir.
     *                      Usually the absolute source path as a String.
     * @param suggestedName Original filename from the document (used only to derive the extension
     *                      when [mime] doesn't map to a standard extension).
     * @param mime          MIME type; drives the file extension via [mimeToExtension].
     * @return Absolute path of the written file.
     * @throws java.io.IOException on disk write failure.
     */
    suspend fun save(bytes: ByteArray, docKey: String, suggestedName: String, mime: String): Path {
        val root = downloadDirProvider() ?: fallbackRoot()
        val docDir = root.resolve("document-${sha6(docKey.toByteArray())}")
        Files.createDirectories(docDir)
        val ordinal = nextOrdinal(docDir)
        val ext = mimeToExtension(mime, suggestedName)
        val filename = "image-%04d-%s.%s".format(ordinal, sha6(bytes), ext)
        val target = docDir.resolve(filename)
        if (!Files.exists(target)) {
            Files.write(target, bytes)
        }
        return target.toAbsolutePath()
    }

    private fun nextOrdinal(docDir: Path): Int {
        // Count existing image-* files under docDir; ordinal = that count + 1. Atomic enough for
        // single-threaded extraction. Tests construct a fresh service so they don't interact.
        return Files.list(docDir).use { stream ->
            stream.filter { it.fileName.toString().startsWith("image-") }.count().toInt() + 1
        }
    }

    private fun fallbackRoot(): Path {
        val root = Path.of(System.getProperty("java.io.tmpdir"), "workflow-document-images")
        Files.createDirectories(root)
        return root
    }

    private fun sha6(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }.take(6)

    private fun mimeToExtension(mime: String, suggestedName: String): String {
        val mimeExt = when (mime.lowercase()) {
            "image/png" -> "png"
            "image/jpeg", "image/jpg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/bmp" -> "bmp"
            "image/tiff" -> "tiff"
            "image/svg+xml" -> "svg"
            else -> null
        }
        if (mimeExt != null) return mimeExt
        // Fallback to suggestedName's extension if MIME unknown.
        val dot = suggestedName.lastIndexOf('.')
        return if (dot > 0 && dot < suggestedName.length - 1) {
            suggestedName.substring(dot + 1).lowercase().filter { it.isLetterOrDigit() }.take(8)
                .ifEmpty { "bin" }
        } else "bin"
    }
}
