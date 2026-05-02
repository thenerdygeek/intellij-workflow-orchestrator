package com.workflow.orchestrator.agent.session

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Phase 4 of multimodal-agent plan — content-addressed per-session attachment store.
 *
 * `AttachmentStore` writes user-uploaded image bytes to
 * `{sessionDir}/attachments/<sha256>.<ext>` using the same atomic-move
 * pattern as `AtomicFileWriter`. sha256 dedup is per-session (NOT cross-session)
 * so `MessageStateHandler.deleteSession()` remains a safe recursive delete.
 *
 * Tests in this class were written before the implementation per TDD discipline:
 * the AttachmentStore reference would not compile until the production class landed.
 */
class AttachmentStoreTest {

    @TempDir lateinit var tempDir: Path

    @Test
    fun `store writes file at sha256-named path`() = runBlocking {
        val store = AttachmentStore(tempDir)
        val bytes = "hello".toByteArray()
        val ref = store.store(bytes, "image/png", "screenshot.png")
        assertTrue(Files.exists(ref.onDiskPath))
        assertEquals(64, ref.sha256.length)  // sha256 hex = 64 chars
        assertEquals("image/png", ref.mime)
        assertEquals(bytes.size.toLong(), ref.size)
        assertEquals("screenshot.png", ref.originalFilename)
    }

    @Test
    fun `store dedups identical bytes within session`() = runBlocking {
        val store = AttachmentStore(tempDir)
        val bytes = "same content".toByteArray()
        val ref1 = store.store(bytes, "image/png", "a.png")
        val ref2 = store.store(bytes, "image/png", "b.png")
        assertEquals(ref1.onDiskPath, ref2.onDiskPath)  // same path
        assertEquals(ref1.sha256, ref2.sha256)
        // Filename + size carried separately so caller can preserve original UI metadata
        assertEquals("a.png", ref1.originalFilename)
        assertEquals("b.png", ref2.originalFilename)
    }

    @Test
    fun `read returns bytes for stored ref`() = runBlocking {
        val store = AttachmentStore(tempDir)
        val original = "round trip".toByteArray()
        val ref = store.store(original, "image/png", null)
        val readBack = store.read(ref.sha256)
        assertArrayEquals(original, readBack)
    }

    @Test
    fun `read returns null for unknown sha256`() = runBlocking {
        val store = AttachmentStore(tempDir)
        assertNull(store.read("0".repeat(64)))
    }

    @Test
    fun `pathFor uses extension from caller`() {
        val store = AttachmentStore(tempDir)
        val p = store.pathFor("abc", "png")
        assertTrue(p.toString().endsWith("abc.png"))
    }

    // ── Coverage beyond the plan minimum ──────────────────────────────────

    @Test
    fun `store creates attachments directory on first use`() = runBlocking {
        val freshDir = tempDir.resolve("freshSession")
        Files.createDirectories(freshDir)
        val store = AttachmentStore(freshDir)
        val attachmentsDir = freshDir.resolve("attachments")
        assertTrue(Files.exists(attachmentsDir), "attachments/ should be created on init")
        assertTrue(Files.isDirectory(attachmentsDir))
        // And subsequent store() should still work
        val ref = store.store("payload".toByteArray(), "image/png", null)
        assertTrue(Files.exists(ref.onDiskPath))
    }

    @Test
    fun `store maps known MIME types to stable extensions`() = runBlocking {
        val store = AttachmentStore(tempDir)
        val cases = mapOf(
            "image/png" to "png",
            "image/jpeg" to "jpg",
            "image/webp" to "webp",
            "image/heic" to "heic",
            "image/heif" to "heif",
            "image/gif" to "gif"
        )
        for ((mime, expectedExt) in cases) {
            // Different bytes per case so each gets a unique sha
            val bytes = "payload-$mime".toByteArray()
            val ref = store.store(bytes, mime, null)
            assertTrue(
                ref.onDiskPath.fileName.toString().endsWith(".$expectedExt"),
                "expected .$expectedExt for $mime, got ${ref.onDiskPath.fileName}"
            )
        }
    }

    @Test
    fun `store falls back to bin extension for unknown MIME`() = runBlocking {
        val store = AttachmentStore(tempDir)
        val ref = store.store("data".toByteArray(), "application/x-something", null)
        assertTrue(ref.onDiskPath.fileName.toString().endsWith(".bin"))
    }

    @Test
    fun `different bytes produce different sha and different paths`() = runBlocking {
        val store = AttachmentStore(tempDir)
        val r1 = store.store("alpha".toByteArray(), "image/png", null)
        val r2 = store.store("beta".toByteArray(), "image/png", null)
        assertNotEquals(r1.sha256, r2.sha256)
        assertNotEquals(r1.onDiskPath, r2.onDiskPath)
    }

    @Test
    fun `store with null filename leaves originalFilename null on ref`() = runBlocking {
        val store = AttachmentStore(tempDir)
        val ref = store.store("bytes".toByteArray(), "image/png", null)
        assertEquals(null, ref.originalFilename)
    }

    @Test
    fun `store does not leave temp files in attachments dir`() = runBlocking {
        val store = AttachmentStore(tempDir)
        store.store("payload-1".toByteArray(), "image/png", null)
        store.store("payload-2".toByteArray(), "image/png", null)
        val tmpFiles = Files.list(tempDir.resolve("attachments")).use { stream ->
            stream.filter { it.fileName.toString().contains(".tmp.") }.toList()
        }
        assertTrue(tmpFiles.isEmpty(), "Expected no leftover .tmp files; found: $tmpFiles")
    }

    @Test
    fun `read of empty attachments dir returns null without throwing`() = runBlocking {
        val store = AttachmentStore(tempDir)
        // No store() called — attachments/ exists (created in init) but is empty
        assertNull(store.read("0".repeat(64)))
    }
}
