package com.workflow.orchestrator.document.sax

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Direct unit tests for [DocumentBlockHandler]'s `internal` helpers.
 *
 * The pipeline-level [com.workflow.orchestrator.document.pipeline.TikaXhtmlFormatGapsTest]
 * exercises the full Tika SAX path, but Tika's HtmlParser truncates `data:` URI `src`
 * attributes to just `"data:"` before they reach the handler. That makes the `data:`
 * branch of [DocumentBlockHandler.guessImageMimeFromSrc] effectively uncovered by the
 * pipeline tests. These tests call the helper directly to fill that gap.
 */
class DocumentBlockHandlerHelpersTest {

    private val handler = DocumentBlockHandler()

    @Test
    fun `guessImageMimeFromSrc returns MIME for png extension`() {
        assertEquals("image/png", handler.guessImageMimeFromSrc("https://example.com/path/photo.png"))
    }

    @Test
    fun `guessImageMimeFromSrc strips query string before extension lookup`() {
        assertEquals("image/jpeg", handler.guessImageMimeFromSrc("https://example.com/photo.jpg?v=2"))
    }

    @Test
    fun `guessImageMimeFromSrc extracts MIME from data URI directly`() {
        assertEquals("image/webp", handler.guessImageMimeFromSrc("data:image/webp;base64,aGVsbG8="))
        assertEquals("image/png", handler.guessImageMimeFromSrc("data:image/png;base64,xxx"))
    }

    @Test
    fun `guessImageMimeFromSrc falls back to octet-stream for unknown extensions`() {
        assertEquals("application/octet-stream", handler.guessImageMimeFromSrc("file.xyz"))
        assertEquals("application/octet-stream", handler.guessImageMimeFromSrc(""))
    }
}
