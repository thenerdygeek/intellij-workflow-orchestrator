package com.workflow.orchestrator.document.pipeline

import com.workflow.orchestrator.core.model.DocumentBlock
import com.workflow.orchestrator.document.sax.DocumentBlockHandler
import org.apache.tika.config.TikaConfig
import org.apache.tika.parser.AutoDetectParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.xml.sax.helpers.AttributesImpl

class TikaXhtmlPipelineTest {

    private val pipeline = TikaXhtmlPipeline()

    // ── Helper ────────────────────────────────────────────────────────────────

    private fun stream(name: String) =
        this::class.java.classLoader.getResourceAsStream("fixtures/$name")
            ?: error("Fixture not found on classpath: fixtures/$name")

    // ── 1. CSV → single Table block ───────────────────────────────────────────

    @Test
    fun `data csv extracts exactly one Table with correct headers and three data rows`() {
        val blocks = stream("data.csv").use { pipeline.extract(it, "text/csv") }

        val tables = blocks.filterIsInstance<DocumentBlock.Table>()
        assertEquals(1, tables.size, "Expected exactly one Table block from data.csv")

        val table = tables[0]
        assertEquals(listOf("Name", "Score", "Grade"), table.headers,
            "CSV headers must be [Name, Score, Grade]")
        assertEquals(3, table.rows.size, "CSV must produce 3 data rows")

        // Values are text strings, NOT integers.
        assertEquals(listOf("Alice", "92", "A"), table.rows[0], "Row 0 must be Alice/92/A")
        assertEquals(listOf("Bob", "78", "C"), table.rows[1],   "Row 1 must be Bob/78/C")
        assertEquals(listOf("Carol", "85", "B"), table.rows[2], "Row 2 must be Carol/85/B")
    }

    // ── 2. HTML → ordered Heading/Paragraph/Table blocks ─────────────────────

    @Test
    fun `report html extracts blocks in correct document order`() {
        val blocks = stream("report.html").use { pipeline.extract(it, "text/html") }

        // Filter to only structural blocks we care about.
        val structural = blocks.filter { it !is DocumentBlock.PageMarker }

        // We need: H1, Paragraph, H2, Table, H2, Paragraph (in that order)
        // Tika may emit extra whitespace-only paragraphs — filter those.
        val significant = structural.filter { block ->
            when (block) {
                is DocumentBlock.Paragraph -> block.text.isNotBlank()
                else -> true
            }
        }

        // Positions in the list.
        val h1Index = significant.indexOfFirst { it is DocumentBlock.Heading && (it as DocumentBlock.Heading).level == 1 }
        val introIndex = significant.indexOfFirst { it is DocumentBlock.Paragraph && (it as DocumentBlock.Paragraph).text.contains("quarterly engineering report") }
        val metricsIndex = significant.indexOfFirst { it is DocumentBlock.Heading && (it as DocumentBlock.Heading).level == 2 && (it as DocumentBlock.Heading).text.contains("Metrics") }
        val tableIndex = significant.indexOfFirst { it is DocumentBlock.Table }
        val conclusionH2Index = significant.indexOfFirst { it is DocumentBlock.Heading && (it as DocumentBlock.Heading).level == 2 && (it as DocumentBlock.Heading).text.contains("Conclusion") }
        val conclusionParaIndex = significant.indexOfFirst { it is DocumentBlock.Paragraph && (it as DocumentBlock.Paragraph).text.contains("Performance was within target") }

        assertTrue(h1Index >= 0, "H1 heading must be present")
        assertTrue(introIndex > h1Index, "intro paragraph must follow H1")
        assertTrue(metricsIndex > introIndex, "Metrics H2 must follow intro")
        assertTrue(tableIndex > metricsIndex, "table must follow Metrics H2")
        assertTrue(conclusionH2Index > tableIndex, "Conclusion H2 must follow table")
        assertTrue(conclusionParaIndex > conclusionH2Index, "Conclusion paragraph must follow Conclusion H2")

        // Verify H1 text.
        val h1 = significant[h1Index] as DocumentBlock.Heading
        assertEquals(1, h1.level)
        assertEquals("Q2 Engineering Report", h1.text)

        // Verify intro paragraph text.
        val intro = significant[introIndex] as DocumentBlock.Paragraph
        assertTrue(intro.text.contains("quarterly engineering report"),
            "intro paragraph should contain expected text")

        // Verify Metrics H2.
        val metricsH2 = significant[metricsIndex] as DocumentBlock.Heading
        assertEquals(2, metricsH2.level)
        assertEquals("Metrics", metricsH2.text)

        // Verify table headers and rows.
        val table = significant[tableIndex] as DocumentBlock.Table
        assertEquals(listOf("Metric", "Value"), table.headers,
            "table headers must be [Metric, Value]")
        assertEquals(2, table.rows.size, "table must have 2 data rows")
        assertEquals(listOf("Bugs closed", "42"), table.rows[0])
        assertEquals(listOf("PRs merged", "17"), table.rows[1])

        // Verify Conclusion H2.
        val conclusionH2 = significant[conclusionH2Index] as DocumentBlock.Heading
        assertEquals(2, conclusionH2.level)
        assertEquals("Conclusion", conclusionH2.text)

        // Verify Conclusion paragraph.
        val conclusionPara = significant[conclusionParaIndex] as DocumentBlock.Paragraph
        assertTrue(conclusionPara.text.contains("Performance was within target"),
            "conclusion paragraph must contain expected text")
    }

    // ── 3. RTF → at least one Heading or Paragraph containing expected substrings ──

    @Test
    fun `release-notes rtf produces at least one block containing Release Notes and one with Version 1_0`() {
        val blocks = stream("release-notes.rtf").use { pipeline.extract(it, "application/rtf") }

        assertTrue(blocks.isNotEmpty(), "RTF extraction must produce at least one block")

        val allText = blocks.joinToString(" ") { block ->
            when (block) {
                is DocumentBlock.Heading -> block.text
                is DocumentBlock.Paragraph -> block.text
                else -> ""
            }
        }

        assertTrue(allText.contains("Release Notes"),
            "RTF output must contain 'Release Notes'; got: $allText")
        assertTrue(allText.contains("Version 1.0"),
            "RTF output must contain 'Version 1.0'; got: $allText")
    }

    // ── 4. PDF prose-only (no table assertion) ────────────────────────────────

    @Test
    fun `tabula-multi-column pdf produces at least one Paragraph block`() {
        val blocks = stream("tabula-multi-column.pdf").use {
            pipeline.extract(it, "application/pdf")
        }

        val paragraphs = blocks.filterIsInstance<DocumentBlock.Paragraph>()
        assertTrue(paragraphs.isNotEmpty(),
            "PDF extraction must produce at least one Paragraph block (prose-only Phase 3 test)")
    }

    // ── 5. Parser registry non-empty and no OCR parser registered ────────────

    @Test
    fun `AutoDetectParser has non-empty parsers registry and no OCR parser`() {
        val configStream = this::class.java.classLoader.getResourceAsStream("tika-config.xml")
            ?: error("tika-config.xml not found on classpath")

        val config = configStream.use { TikaConfig(it) }
        val parser = AutoDetectParser(config)

        val parsers = parser.parsers
        assertTrue(parsers.isNotEmpty(),
            "AutoDetectParser.parsers must be non-empty — TIKA-1145 ServiceLoader issue detected if empty")

        // Print the count for the report requirement.
        println("AutoDetectParser(config).parsers.size = ${parsers.size}")

        val ocrParsers = parsers.entries.filter { (_, p) ->
            p.javaClass.name.contains("OCR", ignoreCase = true)
        }
        assertTrue(ocrParsers.isEmpty(),
            "No OCR parser should be registered; found: ${ocrParsers.map { it.value.javaClass.name }}")
    }

    // ── 6. UTF-8 encoding smoke test (CSV) ────────────────────────────────────

    @Test
    fun `data csv round-trips characters correctly (UTF-8 smoke test)`() {
        val blocks = stream("data.csv").use { pipeline.extract(it, "text/csv") }

        val tables = blocks.filterIsInstance<DocumentBlock.Table>()
        assertFalse(tables.isEmpty(), "Must extract at least one Table from data.csv")

        // All expected values from the ASCII fixture must survive the parse.
        val table = tables[0]
        val allCells = (listOf(table.headers) + table.rows).flatten()
        val expected = listOf("Name", "Score", "Grade", "Alice", "92", "A", "Bob", "78", "C", "Carol", "85", "B")
        for (value in expected) {
            assertTrue(value in allCells,
                "Expected cell value '$value' not found in extracted cells: $allCells")
        }
    }

    // ── 7. Regression: CSV detection must NOT misclassify prose with commas ──

    @Test
    fun `prose with commas across multiple lines is NOT misclassified as table when csv detection is off`() {
        // Drive the SAX handler directly to confirm gating at the handler layer.
        // Multiline prose with commas would otherwise satisfy the heuristic's column-count
        // and delimiter checks — exactly the phantom-table failure mode reviewed in Q2.
        val handler = DocumentBlockHandler(csvDetectionEnabled = false)
        handler.startDocument()
        handler.startElement(null, "p", "p", AttributesImpl())
        val prose = "This module handles parsing, validation, and assembly.\n" +
            "Each concern is independent, but they share types."
        handler.characters(prose.toCharArray(), 0, prose.length)
        handler.endElement(null, "p", "p")
        handler.endDocument()

        val tables = handler.blocks.filterIsInstance<DocumentBlock.Table>()
        val paragraphs = handler.blocks.filterIsInstance<DocumentBlock.Paragraph>()
        assertEquals(0, tables.size,
            "Multiline prose with commas must NOT become a Table when csvDetectionEnabled=false")
        assertEquals(1, paragraphs.size,
            "Multiline prose must produce exactly one Paragraph block")
    }

    // ── 9. Numbered-section heading split from body (Bug #13) ─────────────────

    @Test
    fun `numbered section paragraph splits into Heading plus body Paragraph`() {
        val handler = DocumentBlockHandler(csvDetectionEnabled = false)
        handler.startDocument()
        handler.startElement(null, "p", "p", AttributesImpl())
        val text = "1. IntroductionThis specification describes the read_document tool."
        handler.characters(text.toCharArray(), 0, text.length)
        handler.endElement(null, "p", "p")
        handler.endDocument()

        val headings = handler.blocks.filterIsInstance<DocumentBlock.Heading>()
        val paragraphs = handler.blocks.filterIsInstance<DocumentBlock.Paragraph>()
        assertEquals(1, headings.size, "Expected exactly one Heading; got $headings")
        assertEquals(1, headings[0].level, "1. Introduction should be level 1")
        assertEquals("1. Introduction", headings[0].text)
        assertEquals(1, paragraphs.size, "Expected exactly one body Paragraph")
        assertTrue(
            paragraphs[0].text.startsWith("This specification"),
            "Body paragraph must start with 'This specification'; got '${paragraphs[0].text}'",
        )
    }

    @Test
    fun `nested section number derives heading level from segment count`() {
        val handler = DocumentBlockHandler(csvDetectionEnabled = false)
        handler.startDocument()
        handler.startElement(null, "p", "p", AttributesImpl())
        val text = "2.1.3 SubsectionHere is the body content."
        handler.characters(text.toCharArray(), 0, text.length)
        handler.endElement(null, "p", "p")
        handler.endDocument()

        val headings = handler.blocks.filterIsInstance<DocumentBlock.Heading>()
        assertEquals(1, headings.size)
        assertEquals(3, headings[0].level, "2.1.3 should derive level 3 from three segments")
    }

    @Test
    fun `paragraph that starts with digits but is not a section is NOT split`() {
        val handler = DocumentBlockHandler(csvDetectionEnabled = false)
        handler.startDocument()
        handler.startElement(null, "p", "p", AttributesImpl())
        // Plain prose that happens to start with a digit and a capital — must not
        // be misclassified as a heading.
        val text = "100 lines of code remain to review before we ship."
        handler.characters(text.toCharArray(), 0, text.length)
        handler.endElement(null, "p", "p")
        handler.endDocument()

        val headings = handler.blocks.filterIsInstance<DocumentBlock.Heading>()
        val paragraphs = handler.blocks.filterIsInstance<DocumentBlock.Paragraph>()
        assertTrue(headings.isEmpty(), "Plain prose must not produce a Heading; got $headings")
        assertEquals(1, paragraphs.size)
    }

    // ── 11. Bullet-glyph normalisation (Bug #20) ──────────────────────────────

    @Test
    fun `paragraph with multi-line bullet glyphs gets each line rewritten as Markdown bullet`() {
        val handler = DocumentBlockHandler(csvDetectionEnabled = false)
        handler.startDocument()
        handler.startElement(null, "p", "p", AttributesImpl())
        val text = "• First item\n• Second item\n● Third item"
        handler.characters(text.toCharArray(), 0, text.length)
        handler.endElement(null, "p", "p")
        handler.endDocument()

        val paragraph = handler.blocks.filterIsInstance<DocumentBlock.Paragraph>().single()
        assertTrue(
            paragraph.text.contains("- First item"),
            "Bullet glyph should become '- '; got '${paragraph.text}'",
        )
        assertTrue(
            paragraph.text.contains("- Second item") &&
                paragraph.text.contains("- Third item"),
            "All three bullet glyphs should normalize",
        )
        assertFalse(
            paragraph.text.contains("•") || paragraph.text.contains("●"),
            "Original bullet glyphs must be replaced",
        )
    }

    @Test
    fun `paragraph that does not look like a list is left untouched`() {
        val handler = DocumentBlockHandler(csvDetectionEnabled = false)
        handler.startDocument()
        handler.startElement(null, "p", "p", AttributesImpl())
        val text = "Plain prose with no bullet markers anywhere in sight."
        handler.characters(text.toCharArray(), 0, text.length)
        handler.endElement(null, "p", "p")
        handler.endDocument()

        val paragraph = handler.blocks.filterIsInstance<DocumentBlock.Paragraph>().single()
        assertEquals(text, paragraph.text, "Plain prose must not be modified")
    }

    // ── 10. Hyperlink whitespace restoration (Bug #21) ────────────────────────

    @Test
    fun `paragraph with word-glued-to-URL gets a space inserted`() {
        val handler = DocumentBlockHandler(csvDetectionEnabled = false)
        handler.startDocument()
        handler.startElement(null, "p", "p", AttributesImpl())
        val text = "More information found at:https://example.com/foo and also there."
        handler.characters(text.toCharArray(), 0, text.length)
        handler.endElement(null, "p", "p")
        handler.endDocument()

        val paragraph = handler.blocks.filterIsInstance<DocumentBlock.Paragraph>().single()
        assertTrue(
            paragraph.text.contains("at: https://example.com/foo"),
            "Expected ': ' to separate word from URL; got '${paragraph.text}'",
        )
    }

    @Test
    fun `consecutive URLs get separated with a space`() {
        val handler = DocumentBlockHandler(csvDetectionEnabled = false)
        handler.startDocument()
        handler.startElement(null, "p", "p", AttributesImpl())
        val text = "https://nist.gov/onehttps://nist.gov/twohttps://nist.gov/three"
        handler.characters(text.toCharArray(), 0, text.length)
        handler.endElement(null, "p", "p")
        handler.endDocument()

        val paragraph = handler.blocks.filterIsInstance<DocumentBlock.Paragraph>().single()
        // Three URLs should now be separated by spaces.
        val urlCount = "https://".toRegex().findAll(paragraph.text).count()
        assertEquals(3, urlCount, "Should still have 3 URLs")
        val spaceBeforeHttps = " https://".toRegex().findAll(paragraph.text).count()
        assertEquals(2, spaceBeforeHttps, "2 of 3 URLs should be space-prefixed; got '${paragraph.text}'")
    }

    // ── 8. Tika metadata-leak strings are filtered out (Bug #14) ──────────────

    @Test
    fun `extracted markdown does not contain anonymous metadata leak from PDF`() {
        val blocks = stream("spec-with-tables.pdf").use {
            pipeline.extract(it, "application/pdf")
        }
        val paragraphs = blocks.filterIsInstance<DocumentBlock.Paragraph>().map { it.text }
        assertFalse(
            paragraphs.any { it.equals("(anonymous)", ignoreCase = true) },
            "Tika metadata leak \"(anonymous)\" should be filtered before emit; " +
                "got paragraphs: $paragraphs",
        )
    }

    @Test
    fun `multiline csv content IS classified as table when csv detection is on`() {
        // Same content but with the gate flipped — the heuristic should fire.
        val handler = DocumentBlockHandler(csvDetectionEnabled = true)
        handler.startDocument()
        handler.startElement(null, "p", "p", AttributesImpl())
        val csv = "Name,Score\nAlice,92\nBob,78"
        handler.characters(csv.toCharArray(), 0, csv.length)
        handler.endElement(null, "p", "p")
        handler.endDocument()

        val tables = handler.blocks.filterIsInstance<DocumentBlock.Table>()
        assertEquals(1, tables.size, "csvDetectionEnabled=true should detect a 2-column CSV")
        assertEquals(listOf("Name", "Score"), tables[0].headers)
        assertEquals(2, tables[0].rows.size)
    }
}
