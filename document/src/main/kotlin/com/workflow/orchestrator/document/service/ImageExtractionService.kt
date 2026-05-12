package com.workflow.orchestrator.document.service

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
 * `{downloadsRoot}/document-{sha6OfDocKey}/image-{sha6OfBytes}.{ext}`
 *
 * - `downloadsRoot` is pre-resolved by the caller and passed at construction. See
 *   [downloadsRoot] for the rationale (non-suspend visitor chain).
 * - `sha6OfDocKey` is the first 6 hex chars of the SHA-256 of the `docKey` passed to [save].
 *   Callers should pass a stable key (e.g. the document's absolute path) so re-extracting
 *   the same doc lands in the same dir.
 * - `sha6OfBytes` is the first 6 hex chars of SHA-256 of the bytes. The filename is
 *   **purely content-addressed** — no ordinal — so identical bytes within one doc share
 *   storage (the promised idempotency). Reading order is conveyed by the surrounding
 *   `DocumentBlock` sequence, not by the path itself.
 *
 * ## Idempotency
 *
 * If the target file already exists (same bytes, same doc), [save] is a no-op — it returns
 * the existing path without rewriting. Safe under concurrent retries and across cached
 * PDF re-reads. Two `save()` calls with the same bytes within one doc produce the SAME path.
 *
 * ## Fallback
 *
 * When [downloadsRoot] is null (non-agent caller, unit test without `SessionDownloadDir`
 * installed), bytes land under `java.io.tmpdir/workflow-document-images/` with the same
 * sub-path. Matches the `jira.download_attachment` fallback behaviour.
 */
class ImageExtractionService(
    /**
     * Pre-resolved downloads root. Caller (typically `TikaDocumentExtractor` at the suspend
     * boundary) calls `SessionDownloadDir.current()` once and constructs this service with
     * the result. Null falls back to `java.io.tmpdir/workflow-document-images/`.
     *
     * Pre-resolved at construction so the service's [save] stays non-suspend, allowing it
     * to be called from inside the non-suspend `ParagraphVisitor.visit()` chain (DOCX/PPTX
     * visitors, Tika XHTML SAX handler, etc.) without illegal `runBlocking`.
     */
    private val downloadsRoot: Path?,
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
    fun save(bytes: ByteArray, docKey: String, suggestedName: String, mime: String): Path {
        val root = downloadsRoot ?: fallbackRoot()
        val docDir = root.resolve("document-${sha6(docKey.toByteArray())}")
        Files.createDirectories(docDir)
        val ext = mimeToExtension(mime, suggestedName)
        val filename = "image-${sha6(bytes)}.$ext"
        val target = docDir.resolve(filename)
        if (!Files.exists(target)) {
            Files.write(target, bytes)
        }
        return target.toAbsolutePath()
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
