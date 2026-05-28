package com.workflow.orchestrator.document.poi.visitor

import com.workflow.orchestrator.core.model.DocumentBlock
import com.workflow.orchestrator.document.service.ImageExtractionService
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph

/**
 * Emits [DocumentBlock.EmbeddedFileRef] blocks for each embedded picture inside a paragraph's
 * runs. Picture bytes are written to disk via [imageService] and the resulting absolute
 * path is carried on the emitted block so the LLM has an actionable file path for the
 * `view_image` agent tool.
 *
 * ## Per-image flow
 *
 * For each `XWPFPicture` in `paragraph.runs[*].embeddedPictures`:
 * 1. Read the picture's `XWPFPictureData` for filename + MIME + raw bytes.
 * 2. Check `packagePart.size`: if larger than [maxBytesPerImage] (default 25 MB), emit
 *    `EmbeddedFileRef(name, mime, path=null)` so the LLM still knows the image exists,
 *    but skip the disk write (PoiHardening caps allocations at 50 MB).
 * 3. Otherwise, call [imageService.save] with `(bytes, docKey, suggestedName=filename, mime)`
 *    and emit `EmbeddedFileRef(name=filename, mime, path=savedPath.toString())`.
 *
 * If `paragraph.runs` is empty or no run has embedded pictures, returns empty list.
 *
 * @param imageService     Service that owns per-doc directories and per-bytes hashing.
 * @param docKey           Stable per-document key (typically the source path). The service
 *                         uses this to pick a per-doc subdirectory; reusing the same key
 *                         across saves within one extraction call deduplicates identical
 *                         image bytes.
 * @param maxBytesPerImage Soft cap above which the image is NOT written to disk (a
 *                         placeholder `EmbeddedFileRef(path=null)` is emitted instead).
 *                         Default 25 MB.
 */
class ImageExtractionVisitor(
    private val imageService: ImageExtractionService,
    private val docKey: String,
    private val maxBytesPerImage: Long = 25L * 1024 * 1024,
) : ParagraphVisitor {

    init {
        // readNBytes((maxBytesPerImage + 1).toInt()) Int-clamps; values >= Int.MAX_VALUE would
        // silently truncate and let oversized streams slip through. Fail fast instead — 25 MB
        // default is fine, anyone bumping past 2 GB is doing something wrong.
        require(maxBytesPerImage < Int.MAX_VALUE.toLong()) {
            "maxBytesPerImage must fit in Int (< 2 GB); got $maxBytesPerImage"
        }
    }

    override fun visit(paragraph: XWPFParagraph, doc: XWPFDocument): List<DocumentBlock> {
        val pictures = paragraph.runs.flatMap { it.embeddedPictures.orEmpty() }
        if (pictures.isEmpty()) return emptyList()

        return pictures.mapNotNull { picture ->
            val pictureData = picture.pictureData ?: return@mapNotNull null
            val name = pictureData.fileName ?: "image"
            val mime = pictureMime(pictureData)

            val partSize = try { pictureData.packagePart.size } catch (_: Exception) { -1L }
            if (partSize > 0 && partSize > maxBytesPerImage) {
                // partSize is known and exceeds the cap — skip disk write, emit placeholder.
                return@mapNotNull DocumentBlock.EmbeddedFileRef(name = name, mimeType = mime, path = null)
            }
            // Either size is unknown (-1 from PackagePart.getSize() — always -1 for OOXML parts)
            // or within cap. Read directly from the package part's stream to bypass
            // XWPFPictureData.getData()'s IOUtils.toByteArrayWithMaxLength check (which uses
            // getMaxImageSize()=100MB, conflicting with PoiHardening's 50MB override and throwing
            // RecordFormatException even for small images).
            // Limit the read to maxBytesPerImage+1 bytes so oversized streams are detected.
            val bytes: ByteArray
            try {
                bytes = pictureData.packagePart.inputStream.use { stream ->
                    val limited = stream.readNBytes((maxBytesPerImage + 1).toInt())
                    if (limited.size > maxBytesPerImage) {
                        // Stream turned out larger than cap — emit placeholder, do not save.
                        return@mapNotNull DocumentBlock.EmbeddedFileRef(name = name, mimeType = mime, path = null)
                    }
                    limited
                }
            } catch (_: Exception) {
                return@mapNotNull null
            }
            if (bytes.isEmpty()) return@mapNotNull null
            // saveImage sniffs the real MIME from magic bytes (fixes MIME mismatch where POI
            // declares image/jpeg but the embedded bytes are PNG) and filters out fragment
            // glyphs whose decoded dimensions are below the 32px threshold. Returns null for
            // fragments — skip the block entirely so the LLM doesn't see meaningless slivers.
            val saveResult = try {
                imageService.saveImage(bytes, docKey, name, mime)
            } catch (_: Exception) {
                // Disk write failed (rare); fall back to no-path placeholder so the LLM still
                // sees that an image existed at this point in the document.
                return@mapNotNull DocumentBlock.EmbeddedFileRef(name = name, mimeType = mime, path = null)
            }
            // null from saveImage = fragment filter — drop entirely.
            saveResult ?: return@mapNotNull null
            DocumentBlock.EmbeddedFileRef(name = name, mimeType = saveResult.mimeType, path = saveResult.path.toString())
        }
    }

    /**
     * Maps POI's [org.apache.poi.common.usermodel.PictureType] enum to a MIME string.
     * Uses `getPictureTypeEnum()` (available since POI 5.x) for a clean enum-based dispatch.
     *
     * For types not present in POI 5.4.1's `PictureType` enum (notably WebP — no
     * `PictureType.WEBP` constant exists in this POI version), falls back to
     * `XWPFPictureData.suggestFileExtension()` so newer formats embedded into a DOCX still
     * resolve to a sensible MIME. Vector/proprietary formats (WMF/EMF/PICT/WPG/EPS/WDP) and
     * truly unknown types degrade to `application/octet-stream` — the LLM can't render those
     * via `view_image`, but the placeholder still tells it an image existed.
     */
    private fun pictureMime(pictureData: org.apache.poi.xwpf.usermodel.XWPFPictureData): String {
        return when (pictureData.pictureTypeEnum) {
            org.apache.poi.common.usermodel.PictureType.PNG -> "image/png"
            org.apache.poi.common.usermodel.PictureType.JPEG,
            org.apache.poi.common.usermodel.PictureType.CMYKJPEG -> "image/jpeg"
            org.apache.poi.common.usermodel.PictureType.GIF -> "image/gif"
            org.apache.poi.common.usermodel.PictureType.BMP,
            org.apache.poi.common.usermodel.PictureType.DIB -> "image/bmp"
            org.apache.poi.common.usermodel.PictureType.TIFF -> "image/tiff"
            org.apache.poi.common.usermodel.PictureType.SVG -> "image/svg+xml"
            else -> {
                // Fall back to suggested filename extension for newer types (e.g. WebP — POI
                // 5.4.1 has no PictureType.WEBP). Vector/legacy formats end up here too.
                val ext = pictureData.suggestFileExtension()?.lowercase()
                when (ext) {
                    "webp" -> "image/webp"
                    "svg" -> "image/svg+xml"
                    else -> "application/octet-stream"
                }
            }
        }
    }
}
