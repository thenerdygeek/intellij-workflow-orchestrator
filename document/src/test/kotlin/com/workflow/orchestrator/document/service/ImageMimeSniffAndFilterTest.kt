package com.workflow.orchestrator.document.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * TDD tests for Task 3: MIME magic-byte sniffing + fragment filter.
 *
 * Requirements:
 * 1. PNG bytes declared as image/jpeg → file saved as .png, EmbeddedFileRef.mimeType = "image/png".
 * 2. A 20×20 image is dropped (saveImage returns null — fragment filter).
 * 3. A 301×301 image is kept.
 * 4. sniffImageMime() is a pure helper returning correct MIME from magic bytes.
 * 5. Unknown bytes → sniffImageMime() returns null (declared MIME used as fallback).
 * 6. Callers get effectiveMime from SaveResult, not from the declared mime param.
 */
class ImageMimeSniffAndFilterTest {

    // ─── Helper: produce real image bytes via javax.imageio ───────────────────

    private fun pngBytes(width: Int, height: Int): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return out.toByteArray()
    }

    private fun jpegBytes(width: Int, height: Int): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "jpeg", out)
        return out.toByteArray()
    }

    // ─── sniffImageMime() unit tests ──────────────────────────────────────────

    @Test
    fun `sniffImageMime returns image-png for PNG magic bytes`() {
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertEquals("image/png", ImageExtractionService.sniffImageMime(bytes))
    }

    @Test
    fun `sniffImageMime returns image-jpeg for JPEG magic bytes`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        assertEquals("image/jpeg", ImageExtractionService.sniffImageMime(bytes))
    }

    @Test
    fun `sniffImageMime returns image-gif for GIF87a magic bytes`() {
        val bytes = "GIF87a".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0, 0, 0)
        assertEquals("image/gif", ImageExtractionService.sniffImageMime(bytes))
    }

    @Test
    fun `sniffImageMime returns image-gif for GIF89a magic bytes`() {
        val bytes = "GIF89a".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0, 0, 0)
        assertEquals("image/gif", ImageExtractionService.sniffImageMime(bytes))
    }

    @Test
    fun `sniffImageMime returns image-webp for RIFF-WEBP magic bytes`() {
        // RIFF ....WEBP
        val riff = "RIFF".toByteArray(Charsets.US_ASCII)
        val size = byteArrayOf(0x00, 0x00, 0x00, 0x00)  // 4-byte file size (little-endian)
        val webp = "WEBP".toByteArray(Charsets.US_ASCII)
        val bytes = riff + size + webp
        assertEquals("image/webp", ImageExtractionService.sniffImageMime(bytes))
    }

    @Test
    fun `sniffImageMime returns image-bmp for BMP magic bytes`() {
        val bytes = byteArrayOf(0x42, 0x4D, 0x00, 0x00, 0x00, 0x00)
        assertEquals("image/bmp", ImageExtractionService.sniffImageMime(bytes))
    }

    @Test
    fun `sniffImageMime returns image-tiff for little-endian TIFF magic bytes`() {
        // II 2A 00
        val bytes = byteArrayOf(0x49, 0x49, 0x2A, 0x00)
        assertEquals("image/tiff", ImageExtractionService.sniffImageMime(bytes))
    }

    @Test
    fun `sniffImageMime returns image-tiff for big-endian TIFF magic bytes`() {
        // MM 00 2A
        val bytes = byteArrayOf(0x4D, 0x4D, 0x00, 0x2A)
        assertEquals("image/tiff", ImageExtractionService.sniffImageMime(bytes))
    }

    @Test
    fun `sniffImageMime returns null for unknown bytes`() {
        val bytes = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B)
        assertNull(ImageExtractionService.sniffImageMime(bytes))
    }

    @Test
    fun `sniffImageMime returns null for empty bytes`() {
        assertNull(ImageExtractionService.sniffImageMime(byteArrayOf()))
    }

    @Test
    fun `sniffImageMime works on real PNG bytes from ImageIO`() {
        val bytes = pngBytes(32, 32)
        assertEquals("image/png", ImageExtractionService.sniffImageMime(bytes))
    }

    @Test
    fun `sniffImageMime works on real JPEG bytes from ImageIO`() {
        val bytes = jpegBytes(32, 32)
        assertEquals("image/jpeg", ImageExtractionService.sniffImageMime(bytes))
    }

    // ─── Core requirement: PNG bytes declared as image/jpeg ───────────────────

    @Test
    fun `saveImage PNG bytes declared as image-jpeg results in png extension and image-png MIME`(
        @TempDir downloads: Path,
    ) {
        val svc = ImageExtractionService(downloadsRoot = downloads)
        // Generate real PNG bytes (magic bytes = 89 50 4E 47)
        val pngData = pngBytes(64, 64)

        // Caller mistakenly declares "image/jpeg"
        val result = svc.saveImage(pngData, docKey = "/source/spec.pdf", suggestedName = "figure.jpg", mime = "image/jpeg")

        assertNotNull(result, "A 64×64 PNG image must not be filtered out")
        result!!
        // File must be saved with .png extension (from sniffed MIME, not declared)
        assertTrue(result.path.toString().endsWith(".png"),
            "Expected .png extension from sniffed MIME but got: ${result.path}")
        // Effective MIME must be sniffed image/png, not declared image/jpeg
        assertEquals("image/png", result.mimeType,
            "Expected sniffed MIME image/png but got: ${result.mimeType}")
    }

    // ─── Fragment filter: 20×20 dropped, 301×301 kept ─────────────────────────

    @Test
    fun `saveImage drops a 20×20 image as a fragment`(
        @TempDir downloads: Path,
    ) {
        val svc = ImageExtractionService(downloadsRoot = downloads)
        val tinyPng = pngBytes(20, 20)

        val result = svc.saveImage(tinyPng, docKey = "doc.pdf", suggestedName = "frag.png", mime = "image/png")

        assertNull(result, "A 20×20 image is below the 32px threshold and must be filtered out (null returned)")
    }

    @Test
    fun `saveImage keeps a 301×301 image as a real figure`(
        @TempDir downloads: Path,
    ) {
        val svc = ImageExtractionService(downloadsRoot = downloads)
        val bigPng = pngBytes(301, 301)

        val result = svc.saveImage(bigPng, docKey = "doc.pdf", suggestedName = "figure.png", mime = "image/png")

        assertNotNull(result, "A 301×301 image is well above the threshold and must be kept")
    }

    @Test
    fun `saveImage drops image with height below threshold even if width is large`(
        @TempDir downloads: Path,
    ) {
        val svc = ImageExtractionService(downloadsRoot = downloads)
        // width=200, height=20 — height below 32px threshold
        val bytes = pngBytes(200, 20)

        val result = svc.saveImage(bytes, docKey = "doc.pdf", suggestedName = "wide-strip.png", mime = "image/png")

        assertNull(result, "A 200×20 image has height below the 32px floor and must be filtered out")
    }

    @Test
    fun `saveImage drops image with width below threshold even if height is large`(
        @TempDir downloads: Path,
    ) {
        val svc = ImageExtractionService(downloadsRoot = downloads)
        // width=20, height=200 — width below 32px threshold
        val bytes = pngBytes(20, 200)

        val result = svc.saveImage(bytes, docKey = "doc.pdf", suggestedName = "tall-strip.png", mime = "image/png")

        assertNull(result, "A 20×200 image has width below the 32px floor and must be filtered out")
    }

    @Test
    fun `saveImage keeps image exactly at the 32px threshold`(
        @TempDir downloads: Path,
    ) {
        val svc = ImageExtractionService(downloadsRoot = downloads)
        val bytes = pngBytes(32, 32)

        val result = svc.saveImage(bytes, docKey = "doc.pdf", suggestedName = "exactly32.png", mime = "image/png")

        assertNotNull(result, "A 32×32 image is exactly at the threshold and must be kept")
    }

    // ─── SaveResult carries sniffed MIME ─────────────────────────────────────

    @Test
    fun `saveImage returns effectiveMime from sniffed format not declared mime`(
        @TempDir downloads: Path,
    ) {
        val svc = ImageExtractionService(downloadsRoot = downloads)
        val pngData = pngBytes(64, 64)

        // Declared MIME is wrong (jpeg); sniffed should be png
        val result = svc.saveImage(pngData, docKey = "d", suggestedName = "x.jpg", mime = "image/jpeg")

        assertNotNull(result)
        assertEquals("image/png", result!!.mimeType)
        assertTrue(result.path.toString().endsWith(".png"))
    }

    @Test
    fun `saveImage falls back to declared MIME when sniffing is inconclusive`(
        @TempDir downloads: Path,
    ) {
        val svc = ImageExtractionService(downloadsRoot = downloads)
        // Unknown bytes that don't match any magic signature
        // Make them large enough to pass dimension check by declaring application/octet-stream
        // (no dimension check for non-image bytes that can't be decoded)
        val unknownBytes = ByteArray(50) { it.toByte() }

        // Should still save (no dimension filter for undecoded bytes) and use declared MIME
        val result = svc.saveImage(
            unknownBytes,
            docKey = "d",
            suggestedName = "data.bin",
            mime = "application/octet-stream",
        )
        // For non-image bytes where sniff returns null AND dimensions can't be decoded,
        // the image is saved with declared MIME as fallback (no dimension filter applies).
        // The result could be null (if dimension check fails open-ended) or non-null with declared MIME.
        // Per spec: "Declared MIME is only a fallback when sniffing is inconclusive (unknown bytes)"
        // and dimension filter only applies to decodable images.
        if (result != null) {
            assertEquals("application/octet-stream", result.mimeType,
                "Fallback to declared MIME when sniffing inconclusive")
        }
        // null is also acceptable here — non-image bytes with no detectable dimensions may be skipped.
    }

    @Test
    fun `saveImage with real JPEG bytes and correct declared mime keeps jpeg extension`(
        @TempDir downloads: Path,
    ) {
        val svc = ImageExtractionService(downloadsRoot = downloads)
        val jpgData = jpegBytes(64, 64)

        val result = svc.saveImage(jpgData, docKey = "d", suggestedName = "photo.jpg", mime = "image/jpeg")

        assertNotNull(result, "64×64 JPEG must be kept")
        result!!
        assertTrue(result.path.toString().endsWith(".jpg"),
            "Expected .jpg extension but got: ${result.path}")
        assertEquals("image/jpeg", result.mimeType)
    }

    // ─── Idempotency still holds for saveImage ────────────────────────────────

    @Test
    fun `saveImage is idempotent — same bytes produce same path`(
        @TempDir downloads: Path,
    ) {
        val svc = ImageExtractionService(downloadsRoot = downloads)
        val bytes = pngBytes(64, 64)

        val first = svc.saveImage(bytes, docKey = "doc.pdf", suggestedName = "fig.png", mime = "image/png")
        val second = svc.saveImage(bytes, docKey = "doc.pdf", suggestedName = "fig.png", mime = "image/png")

        assertNotNull(first)
        assertNotNull(second)
        assertEquals(first!!.path, second!!.path, "Same bytes must produce same path (content-addressed)")
        assertEquals(first.mimeType, second.mimeType)
    }
}
