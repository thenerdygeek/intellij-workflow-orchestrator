package com.workflow.orchestrator.agent.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AttachmentMimeTypesTest {

    @Test
    fun `maps image extensions to image mime types`() {
        assertEquals("image/png", AttachmentMimeTypes.fromExtension("png"))
        assertEquals("image/jpeg", AttachmentMimeTypes.fromExtension("jpg"))
        assertEquals("image/jpeg", AttachmentMimeTypes.fromExtension("jpeg"))
        assertEquals("image/webp", AttachmentMimeTypes.fromExtension("webp"))
        assertEquals("image/gif", AttachmentMimeTypes.fromExtension("gif"))
    }

    @Test
    fun `is case-insensitive on the extension`() {
        assertEquals("image/jpeg", AttachmentMimeTypes.fromExtension("JPG"))
        assertEquals("application/pdf", AttachmentMimeTypes.fromExtension("PDF"))
    }

    @Test
    fun `maps office and document extensions`() {
        assertEquals("application/pdf", AttachmentMimeTypes.fromExtension("pdf"))
        assertEquals(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            AttachmentMimeTypes.fromExtension("docx"),
        )
        assertEquals(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            AttachmentMimeTypes.fromExtension("xlsx"),
        )
        assertEquals("text/markdown", AttachmentMimeTypes.fromExtension("md"))
        assertEquals("application/epub+zip", AttachmentMimeTypes.fromExtension("epub"))
    }

    @Test
    fun `unknown or empty extension falls back to octet-stream`() {
        assertEquals("application/octet-stream", AttachmentMimeTypes.fromExtension("xyz"))
        assertEquals("application/octet-stream", AttachmentMimeTypes.fromExtension(""))
    }
}
