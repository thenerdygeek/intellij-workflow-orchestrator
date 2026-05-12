package com.workflow.orchestrator.document.service

import kotlinx.coroutines.runBlocking
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
    ) = runBlocking {
        val svc = ImageExtractionService(downloadDirProvider = { downloads })
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)  // PNG sig
        val path = svc.save(bytes, docKey = "/source/spec.docx", suggestedName = "tiny.png", mime = "image/png")
        assertTrue(path.isAbsolute)
        assertTrue(Files.exists(path))
        assertEquals(bytes.toList(), Files.readAllBytes(path).toList())
        // Path layout
        assertTrue(path.toString().contains("document-"))
        assertTrue(path.toString().endsWith(".png"))
        // file name follows image-NNNN-XXXXXX.png pattern
        assertTrue(Regex("""image-\d{4}-[0-9a-f]{6}\.png""").containsMatchIn(path.fileName.toString()))
    }

    @Test
    fun `same docKey produces a stable per-doc directory across saves`(
        @TempDir downloads: Path,
    ) = runBlocking {
        val svc = ImageExtractionService(downloadDirProvider = { downloads })
        val a = svc.save(byteArrayOf(1, 2, 3), docKey = "spec.docx", suggestedName = "a.png", mime = "image/png")
        val b = svc.save(byteArrayOf(4, 5, 6), docKey = "spec.docx", suggestedName = "b.png", mime = "image/png")
        assertEquals(a.parent, b.parent, "Both images for the same doc share a per-doc directory")
    }

    @Test
    fun `different docKeys land in different directories`(
        @TempDir downloads: Path,
    ) = runBlocking {
        val svc = ImageExtractionService(downloadDirProvider = { downloads })
        val a = svc.save(byteArrayOf(1, 2, 3), docKey = "doc1.docx", suggestedName = "x.png", mime = "image/png")
        val b = svc.save(byteArrayOf(1, 2, 3), docKey = "doc2.docx", suggestedName = "x.png", mime = "image/png")
        assertNotEquals(a.parent, b.parent, "Different docKeys must produce different per-doc dirs")
    }

    @Test
    fun `ordinal increments per save within one service instance`(
        @TempDir downloads: Path,
    ) = runBlocking {
        val svc = ImageExtractionService(downloadDirProvider = { downloads })
        val a = svc.save(byteArrayOf(1), docKey = "d.docx", suggestedName = "a.png", mime = "image/png")
        val b = svc.save(byteArrayOf(2), docKey = "d.docx", suggestedName = "b.png", mime = "image/png")
        val ordinalA = Regex("""image-(\d{4})""").find(a.fileName.toString())!!.groupValues[1].toInt()
        val ordinalB = Regex("""image-(\d{4})""").find(b.fileName.toString())!!.groupValues[1].toInt()
        assertEquals(ordinalA + 1, ordinalB, "ordinal should monotonically increase")
    }

    @Test
    fun `null downloadDirProvider falls back to java io tmpdir`() = runBlocking {
        val svc = ImageExtractionService(downloadDirProvider = { null })
        val path = svc.save(byteArrayOf(1, 2, 3), docKey = "d.docx", suggestedName = "x.png", mime = "image/png")
        assertTrue(path.toString().contains(System.getProperty("java.io.tmpdir")))
        assertTrue(Files.exists(path))
        Files.deleteIfExists(path)
    }

    @Test
    fun `mime to extension picks png webp jpg gif from MIME, falls back to suggestedName extension otherwise`(
        @TempDir downloads: Path,
    ) = runBlocking {
        val svc = ImageExtractionService(downloadDirProvider = { downloads })
        val png = svc.save(byteArrayOf(1), "d", "a.dat", "image/png")
        val jpg = svc.save(byteArrayOf(2), "d", "a.dat", "image/jpeg")
        val webp = svc.save(byteArrayOf(3), "d", "a.dat", "image/webp")
        val unknown = svc.save(byteArrayOf(4), "d", "a.tiff", "application/octet-stream")
        assertTrue(png.toString().endsWith(".png"))
        assertTrue(jpg.toString().endsWith(".jpg"))
        assertTrue(webp.toString().endsWith(".webp"))
        assertTrue(unknown.toString().endsWith(".tiff"), "Unknown MIME falls back to suggestedName ext")
    }
}
