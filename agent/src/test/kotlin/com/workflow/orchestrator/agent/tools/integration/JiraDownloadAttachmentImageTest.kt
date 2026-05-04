package com.workflow.orchestrator.agent.tools.integration

import com.workflow.orchestrator.agent.session.AttachmentRef
import com.workflow.orchestrator.agent.session.AttachmentStore
import com.workflow.orchestrator.agent.tool.SessionAttachmentAccess
import com.workflow.orchestrator.core.model.jira.AttachmentContentData
import com.workflow.orchestrator.core.services.JiraService
import com.workflow.orchestrator.core.services.ToolResult as CoreToolResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Phase 4 of multimodal-agent plan — verifies that download_attachment auto-loads
 * image bytes from the temp file into the active session's AttachmentStore and
 * surfaces an `imageRefs` list on the agent ToolResult, while non-image MIMEs and
 * disabled-setting cases stay text-only.
 */
class JiraDownloadAttachmentImageTest {

    private val tool = JiraTool()

    private fun pngBytes(): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4
    )

    private fun pdfBytes(): ByteArray = "%PDF-1.4\nfake".toByteArray()

    private fun makeAttachmentResult(
        mime: String?,
        filename: String,
        bytes: ByteArray,
        tempDir: Path,
    ): CoreToolResult<AttachmentContentData> {
        val tmp = Files.createTempFile(tempDir, "test-attach-", "")
        Files.write(tmp, bytes)
        return CoreToolResult(
            data = AttachmentContentData(
                filename = filename,
                mimeType = mime,
                sizeBytes = bytes.size.toLong(),
                content = null,
                filePath = tmp.toString(),
                attachmentId = "10001"
            ),
            summary = "Downloaded $filename"
        )
    }

    @Test
    fun `download_attachment with image mime stores bytes and returns imageRefs`(@TempDir tempDir: Path) = runTest {
        val service = mockk<JiraService>()
        val core = makeAttachmentResult("image/png", "ss.png", pngBytes(), tempDir)
        coEvery { service.downloadAttachment("PROJ-1", "10001") } returns core

        val sessionDir = Files.createDirectory(tempDir.resolve("session-a"))
        val store = AttachmentStore(sessionDir)
        val policy = JiraTool.AutoLoadPolicy(
            enabled = true,
            mimeWhitelist = setOf("image/png", "image/jpeg", "image/webp", "image/gif")
        )

        val result = withContext(SessionAttachmentAccess(store)) {
            tool.executeDownloadAttachmentForTest(
                key = "PROJ-1",
                attachmentId = "10001",
                service = service,
                policy = policy
            )
        }

        assertFalse(result.isError)
        assertEquals(1, result.imageRefs.size, "exactly one imageRef expected")
        val ref = result.imageRefs[0]
        assertEquals("image/png", ref.mime)
        assertEquals(pngBytes().size.toLong(), ref.size)
        assertEquals("ss.png", ref.originalFilename)
        // Verify store actually has the bytes (round-trip read)
        val read = store.read(ref.sha256)
        assertNotNull(read)
        assertTrue(pngBytes().contentEquals(read!!))
    }

    @Test
    fun `download_attachment with non-image mime returns no imageRefs`(@TempDir tempDir: Path) = runTest {
        val service = mockk<JiraService>()
        val core = makeAttachmentResult("application/pdf", "spec.pdf", pdfBytes(), tempDir)
        coEvery { service.downloadAttachment("PROJ-2", "10002") } returns core

        val sessionDir = Files.createDirectory(tempDir.resolve("session-b"))
        val store = AttachmentStore(sessionDir)
        val policy = JiraTool.AutoLoadPolicy(
            enabled = true,
            mimeWhitelist = setOf("image/png", "image/jpeg", "image/webp", "image/gif")
        )

        val result = withContext(SessionAttachmentAccess(store)) {
            tool.executeDownloadAttachmentForTest(
                key = "PROJ-2",
                attachmentId = "10002",
                service = service,
                policy = policy
            )
        }

        assertFalse(result.isError)
        assertTrue(result.imageRefs.isEmpty(), "PDF must not produce imageRefs")
    }

    @Test
    fun `setting disabled means imageRefs stays empty even for image attachments`(@TempDir tempDir: Path) = runTest {
        val service = mockk<JiraService>()
        val core = makeAttachmentResult("image/png", "ss.png", pngBytes(), tempDir)
        coEvery { service.downloadAttachment("PROJ-3", "10003") } returns core

        val sessionDir = Files.createDirectory(tempDir.resolve("session-c"))
        val store = AttachmentStore(sessionDir)
        val policy = JiraTool.AutoLoadPolicy(
            enabled = false,
            mimeWhitelist = setOf("image/png", "image/jpeg", "image/webp", "image/gif")
        )

        val result = withContext(SessionAttachmentAccess(store)) {
            tool.executeDownloadAttachmentForTest(
                key = "PROJ-3",
                attachmentId = "10003",
                service = service,
                policy = policy
            )
        }

        assertFalse(result.isError)
        assertTrue(result.imageRefs.isEmpty(), "disabled toggle must short-circuit auto-load")
    }

    @Test
    fun `mime not in whitelist means imageRefs stays empty`(@TempDir tempDir: Path) = runTest {
        val service = mockk<JiraService>()
        // image/heic is in the user-paste whitelist but not the tool default
        val core = makeAttachmentResult("image/heic", "photo.heic", pngBytes(), tempDir)
        coEvery { service.downloadAttachment("PROJ-4", "10004") } returns core

        val sessionDir = Files.createDirectory(tempDir.resolve("session-d"))
        val store = AttachmentStore(sessionDir)
        val policy = JiraTool.AutoLoadPolicy(
            enabled = true,
            mimeWhitelist = setOf("image/png", "image/jpeg", "image/webp", "image/gif")
        )

        val result = withContext(SessionAttachmentAccess(store)) {
            tool.executeDownloadAttachmentForTest(
                key = "PROJ-4",
                attachmentId = "10004",
                service = service,
                policy = policy
            )
        }

        assertFalse(result.isError)
        assertTrue(result.imageRefs.isEmpty(), "non-whitelisted MIME must not auto-load")
    }
}
