package com.workflow.orchestrator.document.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ImageExtractionServiceTest {

    @Test
    fun `save writes the bytes and returns an absolute path under the configured downloads dir`(
        @TempDir downloads: Path,
    ) {
        val svc = ImageExtractionService(downloadsRoot = downloads)
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)  // PNG sig
        val path = svc.save(bytes, docKey = "/source/spec.docx", suggestedName = "tiny.png", mime = "image/png")
        assertTrue(path.isAbsolute)
        assertTrue(Files.exists(path))
        assertEquals(bytes.toList(), Files.readAllBytes(path).toList())
        // Path layout
        assertTrue(path.toString().contains("document-"))
        assertTrue(path.toString().endsWith(".png"))
        // file name follows image-XXXXXX.png pattern (content-addressed, no ordinal)
        assertTrue(Regex("""image-[0-9a-f]{6}\.png""").containsMatchIn(path.fileName.toString()))
    }

    @Test
    fun `same docKey produces a stable per-doc directory across saves`(
        @TempDir downloads: Path,
    ) {
        val svc = ImageExtractionService(downloadsRoot = downloads)
        val a = svc.save(byteArrayOf(1, 2, 3), docKey = "spec.docx", suggestedName = "a.png", mime = "image/png")
        val b = svc.save(byteArrayOf(4, 5, 6), docKey = "spec.docx", suggestedName = "b.png", mime = "image/png")
        assertEquals(a.parent, b.parent, "Both images for the same doc share a per-doc directory")
    }

    @Test
    fun `different docKeys land in different directories`(
        @TempDir downloads: Path,
    ) {
        val svc = ImageExtractionService(downloadsRoot = downloads)
        val a = svc.save(byteArrayOf(1, 2, 3), docKey = "doc1.docx", suggestedName = "x.png", mime = "image/png")
        val b = svc.save(byteArrayOf(1, 2, 3), docKey = "doc2.docx", suggestedName = "x.png", mime = "image/png")
        assertNotEquals(a.parent, b.parent, "Different docKeys must produce different per-doc dirs")
    }

    @Test
    fun `save is idempotent — same bytes and docKey produce the same path on second call`(
        @TempDir downloads: Path,
    ) {
        val svc = ImageExtractionService(downloadsRoot = downloads)
        val bytes = byteArrayOf(7, 8, 9)
        val first = svc.save(bytes, docKey = "doc.docx", suggestedName = "x.png", mime = "image/png")
        // Filename is purely content-addressed (image-{sha6OfBytes}.{ext}), so identical bytes
        // within one doc produce the same path — second call short-circuits via Files.exists.
        val second = svc.save(bytes, docKey = "doc.docx", suggestedName = "x.png", mime = "image/png")
        assertEquals(first, second, "Identical bytes within one doc must produce the same path")
    }

    @Test
    fun `null downloadsRoot falls back to java io tmpdir`() {
        val svc = ImageExtractionService(downloadsRoot = null)
        val path = svc.save(byteArrayOf(1, 2, 3), docKey = "d.docx", suggestedName = "x.png", mime = "image/png")
        assertTrue(path.toString().contains(System.getProperty("java.io.tmpdir")))
        assertTrue(Files.exists(path))
        Files.deleteIfExists(path)
    }

    @Test
    fun `mime to extension picks png webp jpg gif from MIME, falls back to suggestedName extension otherwise`(
        @TempDir downloads: Path,
    ) {
        val svc = ImageExtractionService(downloadsRoot = downloads)
        val png = svc.save(byteArrayOf(1), "d", "a.dat", "image/png")
        val jpg = svc.save(byteArrayOf(2), "d", "a.dat", "image/jpeg")
        val webp = svc.save(byteArrayOf(3), "d", "a.dat", "image/webp")
        val unknown = svc.save(byteArrayOf(4), "d", "a.tiff", "application/octet-stream")
        assertTrue(png.toString().endsWith(".png"))
        assertTrue(jpg.toString().endsWith(".jpg"))
        assertTrue(webp.toString().endsWith(".webp"))
        assertTrue(unknown.toString().endsWith(".tiff"), "Unknown MIME falls back to suggestedName ext")
    }

    @Test
    fun `suggestedName with no extension falls back to bin when MIME also unrecognised`(
        @TempDir downloads: Path,
    ) {
        val svc = ImageExtractionService(downloadsRoot = downloads)
        val noExt = svc.save(byteArrayOf(1, 2), "d", "image", "application/octet-stream")
        val trailingDot = svc.save(byteArrayOf(3, 4), "d", "image.", "application/octet-stream")
        assertTrue(noExt.toString().endsWith(".bin"))
        assertTrue(trailingDot.toString().endsWith(".bin"))
    }
}
