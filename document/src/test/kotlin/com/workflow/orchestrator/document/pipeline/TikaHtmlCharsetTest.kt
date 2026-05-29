package com.workflow.orchestrator.document.pipeline

import com.workflow.orchestrator.core.model.DocumentBlock
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.charset.Charset

/**
 * SF-6 regression: HTML must be decoded with its DECLARED charset (UTF-8 by default for
 * HTML), not statistically guessed as Latin-1/CP1252.
 *
 * Before the fix the only configured encoding detector was UniversalEncodingDetector
 * (statistical), so a sparse-multibyte UTF-8 HTML page was mis-decoded as windows-1252,
 * producing mojibake (`Español` → `EspaÃ±ol`, `Türkçe` → `TÃ¼rkÃ§e`, `–` → `â€"`).
 */
class TikaHtmlCharsetTest {

    private val pipeline = TikaXhtmlPipeline()

    /** All extracted text concatenated, for substring assertions. */
    private fun textOf(blocks: List<DocumentBlock>): String =
        blocks.joinToString("\n") { b ->
            when (b) {
                is DocumentBlock.Heading -> b.text
                is DocumentBlock.Paragraph -> b.text
                is DocumentBlock.ListBlock -> b.items.joinToString("\n")
                is DocumentBlock.Table ->
                    (b.headers + b.rows.flatten()).joinToString("\n")
                else -> ""
            }
        }

    private val sample =
        "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>t</title></head>" +
            "<body><p>Español Türkçe São Tomé — \$10–20 trillion</p></body></html>"

    @Test
    fun `utf-8 html with declared charset yields correct glyphs not mojibake`() {
        val bytes = sample.toByteArray(Charsets.UTF_8)
        val blocks = ByteArrayInputStream(bytes).use { pipeline.extract(it, "text/html") }
        val text = textOf(blocks)

        assertTrue(text.contains("Español"), "Expected 'Español' in: $text")
        assertTrue(text.contains("Türkçe"), "Expected 'Türkçe' in: $text")
        assertTrue(text.contains("São Tomé"), "Expected 'São Tomé' in: $text")
        assertTrue(text.contains("–") || text.contains("–"), "Expected en-dash in: $text")

        // Mojibake markers must be absent.
        assertFalse(text.contains("EspaÃ"), "mojibake 'EspaÃ' present in: $text")
        assertFalse(text.contains("Ã¼"), "mojibake 'Ã¼' present in: $text")
        assertFalse(text.contains("â€"), "mojibake 'â€' present in: $text")
    }

    @Test
    fun `utf-8 html WITHOUT a meta charset still defaults to utf-8 not latin-1`() {
        // No <meta charset>: HTML default per spec is UTF-8. This MUST use a large ASCII
        // prefix before the first multibyte glyph — that is precisely the layout (a long
        // Wikipedia page whose language/place names sit deep in the body) that fooled the
        // statistical UniversalEncodingDetector into guessing windows-1252. A small fixture
        // does NOT reproduce the bug, so the prefix is load-bearing for this regression.
        val asciiPrefix = "Lorem ipsum dolor sit amet consectetur. ".repeat(800) // ~32 KB ASCII
        val noMeta =
            "<!DOCTYPE html><html><head><title>t</title></head>" +
                "<body><p>$asciiPrefix</p>" +
                "<p>Español Türkçe São Tomé — \$10–20 trillion</p></body></html>"
        val bytes = noMeta.toByteArray(Charsets.UTF_8)
        val blocks = ByteArrayInputStream(bytes).use { pipeline.extract(it, "text/html") }
        val text = textOf(blocks)

        assertTrue(text.contains("Español"), "Expected 'Español' in tail of: ${text.takeLast(120)}")
        assertTrue(text.contains("Türkçe"), "Expected 'Türkçe' in tail of: ${text.takeLast(120)}")
        assertTrue(text.contains("São Tomé"), "Expected 'São Tomé' in tail of: ${text.takeLast(120)}")
        assertFalse(text.contains("EspaÃ"), "mojibake 'EspaÃ' present in tail: ${text.takeLast(120)}")
        assertFalse(text.contains("â€"), "mojibake 'â€' present in tail: ${text.takeLast(120)}")
    }

    @Test
    fun `non-html format with sparse multibyte is left to the statistical detector and decodes utf-8`() {
        // Guard the prior behaviour: HtmlDefaultUtf8EncodingDetector must be a no-op for
        // non-HTML, so CSV still resolves through UniversalEncodingDetector.
        val csv = "name,city\nJosé,Malmö\nÅsa,Tromsø\n".toByteArray(Charsets.UTF_8)
        val blocks = ByteArrayInputStream(csv).use { pipeline.extract(it, "text/csv") }
        val text = textOf(blocks)
        assertTrue(text.contains("Malmö"), "Expected 'Malmö' in: $text")
        assertTrue(text.contains("José"), "Expected 'José' in: $text")
        assertFalse(text.contains("MalmÃ"), "mojibake present in CSV: $text")
    }

    @Test
    fun `html declaring a non-utf8 charset is honored`() {
        // The bytes are encoded as ISO-8859-1 and the document DECLARES iso-8859-1.
        // Honoring the declared charset must decode 'Español' correctly (not as UTF-8,
        // which would itself mojibake the 0xF1 byte).
        val html =
            "<!DOCTYPE html><html><head><meta charset=\"iso-8859-1\"></head>" +
                "<body><p>Espanol n-tilde: ñ</p></body></html>"
        val bytes = html.toByteArray(Charset.forName("ISO-8859-1"))
        val blocks = ByteArrayInputStream(bytes).use { pipeline.extract(it, "text/html") }
        val text = textOf(blocks)

        assertTrue(text.contains("ñ"), "Expected ñ (U+00F1) decoded via declared iso-8859-1 in: $text")
        assertFalse(text.contains("�"), "replacement char present (bad decode) in: $text")
    }
}
