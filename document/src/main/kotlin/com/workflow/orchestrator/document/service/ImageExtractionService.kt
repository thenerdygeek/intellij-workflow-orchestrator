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
 *
 * ## Task 3 additions
 *
 * [saveImage] is the preferred entry point for all image extraction callers. It wraps [save]
 * with two additional guarantees:
 * - **MIME magic-byte sniffing** — [sniffImageMime] reads the first 12 bytes and returns the
 *   true format (PNG/JPEG/GIF/WEBP/BMP/TIFF). When sniffing succeeds, the sniffed MIME
 *   overrides the caller-declared value so the file extension and [SaveResult.mimeType] are
 *   always correct regardless of what the embedding container declared.
 * - **Fragment filter** — images whose decoded pixel dimensions are below
 *   [MIN_IMAGE_DIMENSION_PX] (32 px) in either axis are silently dropped by returning null.
 *   Glyph fragments and section-number thumbnails (e.g. a 62×39 "4.1" rendered by PDFBox)
 *   are suppressed; real diagrams (301×301+) survive. When the bytes cannot be decoded as an
 *   image (e.g. SVG, OLE, or corrupt data), the dimension check is skipped and the bytes are
 *   saved normally so non-raster content is never lost.
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
     * Result of a successful [saveImage] call.
     *
     * @param path      Absolute path of the saved file on disk.
     * @param mimeType  The **effective** MIME type — sniffed from magic bytes when the format
     *                  was detectable, or the caller-declared value as fallback. This is the
     *                  value that callers must propagate into [DocumentBlock.EmbeddedFileRef.mimeType]
     *                  so the vision path receives the correct format hint.
     */
    data class SaveResult(val path: Path, val mimeType: String)

    /**
     * Preferred entry point for image extraction callers (Task 3).
     *
     * Combines magic-byte sniffing (via [sniffImageMime]) and a fragment-dimension filter with
     * the underlying [save] call:
     *
     * 1. Sniff the real MIME from [bytes]. If sniffing succeeds, use the sniffed MIME;
     *    otherwise fall back to [mime].
     * 2. Attempt to decode the image dimensions from [bytes]. If the decoded width or height is
     *    below [MIN_IMAGE_DIMENSION_PX] (32 px), return null — the image is a fragment.
     *    If decoding fails (SVG, OLE, corrupt bytes), skip the dimension check so non-raster
     *    content is never silently dropped.
     * 3. Call [save] with the effective MIME and return a [SaveResult] carrying both the disk
     *    path and the effective MIME.
     *
     * Returns **null** when the image is a fragment (below the dimension threshold). Callers
     * must treat null as "skip this image entirely" — do NOT emit an [EmbeddedFileRef] for it.
     *
     * @param bytes         Raw image bytes.
     * @param docKey        Stable per-document key (same semantics as [save]).
     * @param suggestedName Original filename (fallback extension source when MIME is unrecognised).
     * @param mime          Caller-declared MIME type. Used as fallback when sniffing returns null.
     * @return [SaveResult] on success, or null when the image is filtered out as a fragment.
     * @throws java.io.IOException on disk write failure.
     */
    fun saveImage(bytes: ByteArray, docKey: String, suggestedName: String, mime: String): SaveResult? {
        val effectiveMime = sniffImageMime(bytes) ?: mime

        // Dimension check: try to decode the image; filter out fragments.
        val decoded = try {
            javax.imageio.ImageIO.read(bytes.inputStream())
        } catch (_: Exception) {
            null
        }
        if (decoded != null) {
            // Successfully decoded — apply dimension filter.
            if (decoded.width < MIN_IMAGE_DIMENSION_PX || decoded.height < MIN_IMAGE_DIMENSION_PX) {
                return null  // fragment — drop silently
            }
        }
        // If decoded is null (non-raster format or corrupt bytes), fall through and save anyway.

        val path = save(bytes, docKey, suggestedName, effectiveMime)
        return SaveResult(path = path, mimeType = effectiveMime)
    }

    /**
     * Low-level save: writes [bytes] to disk and returns the absolute path.
     *
     * Callers that extract images should prefer [saveImage] which adds MIME sniffing and
     * fragment filtering. [save] is retained for callers that do not need those guards
     * (e.g. non-image embedded attachments) and for backward compatibility.
     *
     * @param bytes         Raw bytes to persist.
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

    companion object {

        /**
         * Minimum dimension (width or height) in pixels for an image to be kept.
         * Images below this threshold in either axis are fragment glyphs (e.g. section-number
         * thumbnails) and are filtered out by [saveImage]. Value: 32 px.
         *
         * Evidence: the real "4.1" section-number fragment from the PDF probe was 62×39 — so
         * a 32 px floor captures that correctly. The real diagram that must survive was 301×301.
         */
        const val MIN_IMAGE_DIMENSION_PX: Int = 32

        /**
         * Sniffs the image format from the first bytes of [bytes] using well-known magic-byte
         * signatures. Returns the MIME type string when the format is recognised, or null when
         * the bytes do not match any known signature (including empty input).
         *
         * This is a pure function with no I/O — safe to call from any thread, including the
         * non-suspend visitor chain.
         *
         * Recognised formats and their signatures:
         * - PNG: `89 50 4E 47 0D 0A 1A 0A` (8 bytes)
         * - JPEG: `FF D8 FF` (3 bytes)
         * - GIF87a: `47 49 46 38 37 61` (6 bytes literal "GIF87a")
         * - GIF89a: `47 49 46 38 39 61` (6 bytes literal "GIF89a")
         * - WEBP: `52 49 46 46 .. .. .. .. 57 45 42 50` (bytes 0–3 = "RIFF", bytes 8–11 = "WEBP")
         * - BMP: `42 4D` (2 bytes "BM")
         * - TIFF (little-endian): `49 49 2A 00` ("II" + 0x002A)
         * - TIFF (big-endian): `4D 4D 00 2A` ("MM" + 0x002A)
         *
         * @param bytes Raw bytes of the file. At least 12 bytes are needed for WEBP detection;
         *              shorter arrays are checked against the applicable prefixes only.
         * @return MIME type string (e.g. `"image/png"`) or null when no signature matches.
         */
        fun sniffImageMime(bytes: ByteArray): String? {
            if (bytes.isEmpty()) return null
            val b = bytes

            // PNG: 89 50 4E 47 0D 0A 1A 0A
            if (b.size >= 8 &&
                b[0] == 0x89.toByte() && b[1] == 0x50.toByte() && b[2] == 0x4E.toByte() &&
                b[3] == 0x47.toByte() && b[4] == 0x0D.toByte() && b[5] == 0x0A.toByte() &&
                b[6] == 0x1A.toByte() && b[7] == 0x0A.toByte()
            ) return "image/png"

            // JPEG: FF D8 FF
            if (b.size >= 3 &&
                b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()
            ) return "image/jpeg"

            // GIF87a or GIF89a: 6-byte ASCII prefix
            if (b.size >= 6) {
                val prefix = String(b, 0, 6, Charsets.US_ASCII)
                if (prefix == "GIF87a" || prefix == "GIF89a") return "image/gif"
            }

            // WEBP: bytes 0–3 = "RIFF", bytes 8–11 = "WEBP"
            if (b.size >= 12 &&
                b[0] == 0x52.toByte() && b[1] == 0x49.toByte() &&
                b[2] == 0x46.toByte() && b[3] == 0x46.toByte() &&  // "RIFF"
                b[8] == 0x57.toByte() && b[9] == 0x45.toByte() &&
                b[10] == 0x42.toByte() && b[11] == 0x50.toByte()    // "WEBP"
            ) return "image/webp"

            // BMP: 42 4D ("BM")
            if (b.size >= 2 && b[0] == 0x42.toByte() && b[1] == 0x4D.toByte()) return "image/bmp"

            // TIFF little-endian: 49 49 2A 00 ("II" + 0x002A)
            if (b.size >= 4 &&
                b[0] == 0x49.toByte() && b[1] == 0x49.toByte() &&
                b[2] == 0x2A.toByte() && b[3] == 0x00.toByte()
            ) return "image/tiff"

            // TIFF big-endian: 4D 4D 00 2A ("MM" + 0x002A)
            if (b.size >= 4 &&
                b[0] == 0x4D.toByte() && b[1] == 0x4D.toByte() &&
                b[2] == 0x00.toByte() && b[3] == 0x2A.toByte()
            ) return "image/tiff"

            return null
        }
    }
}
