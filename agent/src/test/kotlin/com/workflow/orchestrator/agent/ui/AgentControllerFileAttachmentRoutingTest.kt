package com.workflow.orchestrator.agent.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentControllerFileAttachmentRoutingTest {
    @Test fun `splitAttachmentsJson separates images from files`() {
        val json = """
          [{"sha256":"aa","mime":"image/png","size":3,"originalFilename":"a.png","kind":"image"},
           {"sha256":"bb","mime":"application/pdf","size":9,"originalFilename":"s.pdf","kind":"file","path":"/tmp/sess/attachments/files/bb-s.pdf"}]
        """.trimIndent()
        val (images, files) = splitAttachmentsJson(json)
        assertEquals(1, images.size)
        assertEquals("aa", images[0].sha256)
        assertEquals(1, files.size)
        assertEquals("/tmp/sess/attachments/files/bb-s.pdf", files[0].path)
    }

    @Test fun `attachments without kind default to image (back-compat)`() {
        val json = """[{"sha256":"aa","mime":"image/png","size":3,"originalFilename":"a.png"}]"""
        val (images, files) = splitAttachmentsJson(json)
        assertEquals(1, images.size)
        assertEquals(0, files.size)
    }

    @Test fun `composeFileMarker lists each path under an attached_files block`() {
        val marker = composeFileMarker(
            listOf(
                FileAttachment("bb", "application/pdf", 9, "s.pdf", "/tmp/s.pdf"),
                FileAttachment("cc", "text/plain", 4, "n.txt", "/tmp/n.txt"),
            )
        )
        assertTrue(marker.contains("<attached_files>"))
        assertTrue(marker.contains("/tmp/s.pdf"))
        assertTrue(marker.contains("/tmp/n.txt"))
        assertTrue(marker.contains("read_document") && marker.contains("read_file"))
    }

    @Test fun `composeFileMarker is empty for no files`() {
        assertEquals("", composeFileMarker(emptyList()))
    }
}
