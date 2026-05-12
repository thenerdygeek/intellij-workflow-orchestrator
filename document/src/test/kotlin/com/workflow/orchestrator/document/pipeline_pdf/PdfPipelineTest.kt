package com.workflow.orchestrator.document.pipeline_pdf

import com.workflow.orchestrator.core.model.DocumentBlock
import com.workflow.orchestrator.document.assembler.MarkdownAssembler
import com.workflow.orchestrator.document.pipeline.PdfPipeline
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Integration tests for [PdfPipeline]: Tabula table extraction merged with Tika XHTML prose.
 *
 * Tests 9–13 per the Phase 5 specification.
 */
class PdfPipelineTest {

    private val pipeline = PdfPipeline()

    private fun fixture(name: String) =
        Paths.get(javaClass.classLoader.getResource("fixtures/$name")!!.toURI())

    // ── 9. spec-with-tables: heading separated from body (Bug #13) ────────────

    @Test
    fun `numbered section heading in PDF emits as Heading block separate from body`() {
        val blocks = pipeline.extract(fixture("spec-with-tables.pdf"))
        val headings = blocks.filterIsInstance<DocumentBlock.Heading>().map { it.text }
        assertTrue(
            headings.any { it.contains("Introduction", ignoreCase = true) },
            "Section 1 (Introduction) should be a Heading, not a Paragraph; got headings=$headings",
        )
        val paragraphs = blocks.filterIsInstance<DocumentBlock.Paragraph>().map { it.text }
        assertFalse(
            paragraphs.any { it.startsWith("1. Introduction") },
            "No paragraph should start with the section number — that means the heading was glued; " +
                "got paragraphs starting with digits=" +
                paragraphs.filter { it.firstOrNull()?.isDigit() == true },
        )
    }

    // ── 9b. spec-with-tables: prose ordering around tables ───────────────────

    @Test
    fun `spec-with-tables pipeline FR section text appears before FR Matrix table`() {
        val blocks = pipeline.extract(fixture("spec-with-tables.pdf"))

        // Find a text block (Heading or Paragraph) containing "Functional Requirements"
        // that appears before the FR Matrix table.
        val frTextIndex = blocks.indexOfFirst { block ->
            when (block) {
                is DocumentBlock.Heading -> block.text.contains("Functional Requirements", ignoreCase = true)
                is DocumentBlock.Paragraph -> block.text.contains("Functional Requirements", ignoreCase = true)
                else -> false
            }
        }
        val frTableIndex = blocks.indexOfFirst { block ->
            block is DocumentBlock.Table && block.headers == listOf("ReqId", "Priority", "Status")
        }

        assertTrue(frTextIndex >= 0, "A block containing 'Functional Requirements' must be present")
        assertTrue(frTableIndex >= 0, "The FR Matrix table must be present in the pipeline output")
        assertTrue(
            frTextIndex < frTableIndex,
            "The 'Functional Requirements' section text (index $frTextIndex) must appear " +
                "before the FR Matrix table (index $frTableIndex)",
        )
    }

    @Test
    fun `spec-with-tables pipeline Acceptance section text appears before Acceptance table`() {
        val blocks = pipeline.extract(fixture("spec-with-tables.pdf"))

        val acceptTextIndex = blocks.indexOfFirst { block ->
            when (block) {
                is DocumentBlock.Heading -> block.text.contains("Acceptance", ignoreCase = true)
                is DocumentBlock.Paragraph -> block.text.contains("Acceptance", ignoreCase = true)
                else -> false
            }
        }
        val acceptTableIndex = blocks.indexOfFirst { block ->
            block is DocumentBlock.Table && block.headers == listOf("Test", "Expected", "Actual")
        }

        assertTrue(acceptTextIndex >= 0, "A block containing 'Acceptance' must be present")
        assertTrue(acceptTableIndex >= 0, "The Acceptance table must be present")
        assertTrue(
            acceptTextIndex < acceptTableIndex,
            "The 'Acceptance' section text (index $acceptTextIndex) must appear " +
                "before the Acceptance table (index $acceptTableIndex)",
        )
    }

    @Test
    fun `spec-with-tables pipeline page 2 content comes after page 1 content`() {
        val blocks = pipeline.extract(fixture("spec-with-tables.pdf"))

        // Page 2 is signalled by a PageMarker(2). Content from page 2 (Acceptance table)
        // must appear after content from page 1 (FR and NFR tables).
        val page2MarkerIndex = blocks.indexOfFirst { it is DocumentBlock.PageMarker && (it as DocumentBlock.PageMarker).pageNumber == 2 }
        val frTableIndex = blocks.indexOfFirst { block ->
            block is DocumentBlock.Table && block.headers == listOf("ReqId", "Priority", "Status")
        }
        val acceptTableIndex = blocks.indexOfFirst { block ->
            block is DocumentBlock.Table && block.headers == listOf("Test", "Expected", "Actual")
        }

        assertTrue(page2MarkerIndex >= 0, "PageMarker(2) must be present in the output")
        assertTrue(frTableIndex >= 0, "FR Matrix table must be present")
        assertTrue(acceptTableIndex >= 0, "Acceptance table must be present")
        assertTrue(
            frTableIndex < acceptTableIndex,
            "Page 1 FR table (index $frTableIndex) must precede page 2 Acceptance table (index $acceptTableIndex)",
        )
    }

    // ── 10. multi-page-table: exactly 1 Table block in pipeline output ────────

    @Test
    fun `multi-page table continuation does not leave duplicate row text on page 2`() {
        val blocks = pipeline.extract(fixture("multi-page-table.pdf"))
        val markdown = MarkdownAssembler().assemble(blocks, 200_000).first
        val occurrencesBug032 = "BUG-032".toRegex().findAll(markdown).count()
        assertEquals(
            1,
            occurrencesBug032,
            "BUG-032 should appear once in the merged table, not twice — a second occurrence " +
                "indicates page-2 rows leaked through dedup as flat prose. Markdown:\n$markdown",
        )
    }

    @Test
    fun `multi-page-table full pipeline produces exactly 1 Table block`() {
        val blocks = pipeline.extract(fixture("multi-page-table.pdf"))

        val tables = blocks.filterIsInstance<DocumentBlock.Table>()
        assertEquals(
            1,
            tables.size,
            "multi-page-table.pdf must yield exactly 1 Table in the final pipeline output; " +
                "got ${tables.size}. Continuation merge may not be working.",
        )

        val table = tables[0]
        assertEquals(
            listOf("BugId", "Severity", "Status"),
            table.headers,
            "Merged table headers must be [BugId, Severity, Status]",
        )
        assertEquals(40, table.rows.size, "Merged table must have all 40 data rows")
    }

    // ── 11. ietf-rfc7230: smoke test (no crash, >100 blocks) ─────────────────

    @Test
    fun `ietf-rfc7230 full pipeline does not crash and returns more than 100 blocks`() {
        val blocks = pipeline.extract(fixture("ietf-rfc7230.pdf"))
        assertTrue(
            blocks.size > 100,
            "89-page RFC PDF must return > 100 blocks; got ${blocks.size}",
        )
    }

    // ── 12. nist-cybersecurity-framework: smoke + page-order regression ──────

    @Test
    fun `nist-cybersecurity-framework full pipeline does not crash`() {
        val blocks = pipeline.extract(fixture("nist-cybersecurity-framework.pdf"))
        assertTrue(blocks.isNotEmpty(), "NIST CSF PDF must return at least one block")
    }

    @Test
    fun `nist-cybersecurity-framework page markers appear in monotonically ascending order`() {
        // Bug #17 regression: original symptom was page markers reported as 31, 1, scattered.
        // With chrome stripping (Bug #18) and dedup fixes (Bug #12) the underlying sort is
        // clean. Lock that in: every page marker must have a number greater than the previous.
        val blocks = pipeline.extract(fixture("nist-cybersecurity-framework.pdf"))
        val pageNumbers = blocks
            .filterIsInstance<DocumentBlock.PageMarker>()
            .map { it.pageNumber }
        assertTrue(pageNumbers.size >= 2, "Long PDF must have multiple page markers")
        val outOfOrder = pageNumbers.zipWithNext().count { (a, b) -> b <= a }
        assertEquals(
            0,
            outOfOrder,
            "Page markers must increase strictly; got ${pageNumbers.take(20)}…",
        )
    }

    // ── 14. nist: repeated page-footer chrome is stripped (Bug #18) ───────────

    @Test
    fun `repeated page-footer text is stripped on long PDFs`() {
        val blocks = pipeline.extract(fixture("nist-cybersecurity-framework.pdf"))
        val paragraphs = blocks.filterIsInstance<DocumentBlock.Paragraph>().map { it.text }
        val maxRepeats = paragraphs.groupingBy { it }.eachCount().values.max()
        assertTrue(
            maxRepeats < 10,
            "No paragraph should repeat 10+ times after footer-chrome stripping; max was $maxRepeats",
        )
    }

    @Test
    fun `repeated footer chrome with per-page page-number suffix is also stripped`() {
        val blocks = pipeline.extract(fixture("nist-cybersecurity-framework.pdf"))
        val paragraphs = blocks.filterIsInstance<DocumentBlock.Paragraph>().map { it.text }
        // The NIST footer URL is "...NIST.CSWP.04162018 N" where N varies per page.
        // Without normalised dedup, ~55 distinct strings remain; with it, virtually none.
        val nistFooterCount = paragraphs.count { it.contains("NIST.CSWP.04162018") }
        assertTrue(
            nistFooterCount < 5,
            "Per-page-number variants of the NIST footer should be stripped; got $nistFooterCount",
        )
    }

    // ── 15. tabula-eu-002: pipeline runs without crashing ─────────────────────

    @Test
    fun `tabula-eu-002 full pipeline does not crash`() {
        // Note: tables 5 and 6 in this fixture are emitted as flat prose, not Markdown
        // tables (Bug #15, deferred to P2). Once Bug #15 lands this test should also
        // assert tables.isNotEmpty(). For now we only verify the pipeline runs cleanly.
        val blocks = pipeline.extract(fixture("tabula-eu-002.pdf"))
        assertTrue(blocks.isNotEmpty(), "Pipeline must produce at least one block")
    }

    // ── P1T5. PDF markup annotation emits DocumentBlock.Comment(PDF_ANNOTATION) ─

    @Test
    fun `pdf with sticky-note annotation emits DocumentBlock Comment PDF_ANNOTATION`() {
        // Build a minimal PDF in-memory with one page and one PDAnnotationText (sticky note).
        val pdfBytes = run {
            val doc = org.apache.pdfbox.pdmodel.PDDocument()
            val page = org.apache.pdfbox.pdmodel.PDPage()
            doc.addPage(page)

            // Minimal text content stream so PdfProseExtractor has something to parse.
            val contentStream = org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)
            contentStream.beginText()
            contentStream.setFont(
                org.apache.pdfbox.pdmodel.font.PDType1Font(
                    org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA,
                ),
                12f,
            )
            contentStream.newLineAtOffset(100f, 700f)
            contentStream.showText("Body text on the page.")
            contentStream.endText()
            contentStream.close()

            // Add a sticky-note annotation (PDAnnotationText extends PDAnnotationMarkup).
            val annotation = org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText()
            annotation.contents = "Confirm this benchmark."
            annotation.titlePopup = "Jane"
            annotation.rectangle = org.apache.pdfbox.pdmodel.common.PDRectangle(120f, 695f, 20f, 20f)
            page.annotations.add(annotation)

            val out = java.io.ByteArrayOutputStream()
            doc.save(out)
            doc.close()
            out.toByteArray()
        }

        // PDFBox requires random-access file I/O, so write to a temp file.
        val tempFile = java.nio.file.Files.createTempFile("p1t5-pdf-", ".pdf")
        java.nio.file.Files.write(tempFile, pdfBytes)
        try {
            val blocks = PdfPipeline().extract(tempFile)

            val annotationComment = blocks
                .filterIsInstance<DocumentBlock.Comment>()
                .firstOrNull { it.kind == DocumentBlock.Comment.Kind.PDF_ANNOTATION }

            assertNotNull(
                annotationComment,
                "Expected a PDF_ANNOTATION Comment in the merged output; blocks=${blocks.map { it::class.simpleName }}",
            )
            assertEquals("Jane", annotationComment!!.author)
            assertEquals("Confirm this benchmark.", annotationComment.text)
        } finally {
            java.nio.file.Files.deleteIfExists(tempFile)
        }
    }

    @Test
    fun `pdf annotation with no titlePopup emits Comment with null author`() {
        val pdfBytes = run {
            val doc = org.apache.pdfbox.pdmodel.PDDocument()
            val page = org.apache.pdfbox.pdmodel.PDPage()
            doc.addPage(page)

            val cs = org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)
            cs.beginText()
            cs.setFont(
                org.apache.pdfbox.pdmodel.font.PDType1Font(
                    org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA,
                ),
                12f,
            )
            cs.newLineAtOffset(100f, 700f)
            cs.showText("Body.")
            cs.endText()
            cs.close()

            val annotation = org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText()
            annotation.contents = "Anonymous note."
            // intentionally NOT setting titlePopup
            annotation.rectangle = org.apache.pdfbox.pdmodel.common.PDRectangle(120f, 695f, 20f, 20f)
            page.annotations.add(annotation)

            val out = java.io.ByteArrayOutputStream()
            doc.save(out); doc.close()
            out.toByteArray()
        }

        val tempFile = java.nio.file.Files.createTempFile("p1t5-pdf-anon-", ".pdf")
        java.nio.file.Files.write(tempFile, pdfBytes)
        try {
            val pipeline = PdfPipeline()
            val blocks = pipeline.extract(tempFile)
            val c = blocks.filterIsInstance<DocumentBlock.Comment>()
                .firstOrNull { it.kind == DocumentBlock.Comment.Kind.PDF_ANNOTATION }
            assertNotNull(c, "Expected a PDF_ANNOTATION Comment even when titlePopup is null")
            assertNull(c!!.author, "Anonymous PDF annotation should produce author=null")
            assertEquals("Anonymous note.", c.text)
        } finally {
            java.nio.file.Files.deleteIfExists(tempFile)
        }
    }

    // ── P4T1. PDF metadata channels: document properties, bookmarks, AcroForm ──

    @Test
    fun `pdf with document properties emits KeyValueGroup with Title Author Subject fields`() {
        val pdfBytes = run {
            val doc = org.apache.pdfbox.pdmodel.PDDocument()
            doc.addPage(org.apache.pdfbox.pdmodel.PDPage())
            val info = doc.documentInformation
            info.title = "My Spec"
            info.author = "Jane"
            info.subject = "Q4 plan"
            val out = java.io.ByteArrayOutputStream()
            doc.save(out); doc.close()
            out.toByteArray()
        }
        val tempFile = java.nio.file.Files.createTempFile("p4t1-props-", ".pdf")
        java.nio.file.Files.write(tempFile, pdfBytes)
        try {
            val blocks = PdfPipeline().extract(tempFile)
            val kvg = blocks.filterIsInstance<DocumentBlock.KeyValueGroup>()
                .firstOrNull { it.title == "Document properties" }
            assertNotNull(kvg, "Expected Document properties KeyValueGroup")
            val pairsMap = kvg!!.pairs.toMap()
            assertEquals("My Spec", pairsMap["Title"])
            assertEquals("Jane", pairsMap["Author"])
            assertEquals("Q4 plan", pairsMap["Subject"])
        } finally {
            java.nio.file.Files.deleteIfExists(tempFile)
        }
    }

    @Test
    fun `pdf with no doc-info fields emits no Document properties KeyValueGroup`() {
        val pdfBytes = run {
            val doc = org.apache.pdfbox.pdmodel.PDDocument()
            doc.addPage(org.apache.pdfbox.pdmodel.PDPage())
            // Do NOT touch documentInformation
            val out = java.io.ByteArrayOutputStream()
            doc.save(out); doc.close()
            out.toByteArray()
        }
        val tempFile = java.nio.file.Files.createTempFile("p4t1-noprops-", ".pdf")
        java.nio.file.Files.write(tempFile, pdfBytes)
        try {
            val blocks = PdfPipeline().extract(tempFile)
            val kvg = blocks.filterIsInstance<DocumentBlock.KeyValueGroup>()
                .firstOrNull { it.title == "Document properties" }
            // PDFBox sometimes auto-populates Producer/CreationDate — accept either no kvg, or
            // a kvg whose pairs only contain Producer/Created (auto-set fields).
            if (kvg != null) {
                val userFields = kvg.pairs.filterNot { it.first in setOf("Producer", "Created", "Modified") }
                assertTrue(
                    userFields.isEmpty(),
                    "Expected only auto-populated fields when no user-supplied info; got: ${kvg.pairs}",
                )
            }
        } finally {
            java.nio.file.Files.deleteIfExists(tempFile)
        }
    }

    @Test
    fun `pdf with bookmarks emits a Bookmarks KeyValueGroup mapping titles to page labels`() {
        val pdfBytes = run {
            val doc = org.apache.pdfbox.pdmodel.PDDocument()
            val page1 = org.apache.pdfbox.pdmodel.PDPage(); doc.addPage(page1)
            val page2 = org.apache.pdfbox.pdmodel.PDPage(); doc.addPage(page2)

            val outline =
                org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline()
            doc.documentCatalog.documentOutline = outline

            val intro =
                org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem()
            intro.title = "Introduction"
            val introDest =
                org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination()
            introDest.page = page1
            intro.destination = introDest
            outline.addLast(intro)

            val details =
                org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem()
            details.title = "Details"
            val detailsDest =
                org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination()
            detailsDest.page = page2
            details.destination = detailsDest
            outline.addLast(details)

            val out = java.io.ByteArrayOutputStream()
            doc.save(out); doc.close()
            out.toByteArray()
        }
        val tempFile = java.nio.file.Files.createTempFile("p4t1-bookmarks-", ".pdf")
        java.nio.file.Files.write(tempFile, pdfBytes)
        try {
            val blocks = PdfPipeline().extract(tempFile)
            val kvg = blocks.filterIsInstance<DocumentBlock.KeyValueGroup>()
                .firstOrNull { it.title == "Bookmarks" }
            assertNotNull(kvg, "Expected Bookmarks KeyValueGroup")
            val pairsMap = kvg!!.pairs.toMap()
            assertEquals("p.1", pairsMap["Introduction"])
            assertEquals("p.2", pairsMap["Details"])
        } finally {
            java.nio.file.Files.deleteIfExists(tempFile)
        }
    }

    @Test
    fun `multi-page pdf with annotations on different pages — each uses its own page mediaBox`() {
        val pdfBytes = run {
            val doc = org.apache.pdfbox.pdmodel.PDDocument()
            // Page 1: letter size, annotation near top
            val p1 = org.apache.pdfbox.pdmodel.PDPage()
            doc.addPage(p1)
            val a1 = org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText()
            a1.contents = "page-1-note"
            a1.titlePopup = "Alice"
            a1.rectangle = org.apache.pdfbox.pdmodel.common.PDRectangle(100f, 750f, 20f, 20f)
            p1.annotations.add(a1)

            val p2 = org.apache.pdfbox.pdmodel.PDPage()
            doc.addPage(p2)
            val a2 = org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText()
            a2.contents = "page-2-note"
            a2.titlePopup = "Bob"
            a2.rectangle = org.apache.pdfbox.pdmodel.common.PDRectangle(100f, 750f, 20f, 20f)
            p2.annotations.add(a2)

            val out = java.io.ByteArrayOutputStream()
            doc.save(out); doc.close()
            out.toByteArray()
        }

        val tempFile = java.nio.file.Files.createTempFile("p1t5-pdf-multi-", ".pdf")
        java.nio.file.Files.write(tempFile, pdfBytes)
        try {
            val pipeline = PdfPipeline()
            val blocks = pipeline.extract(tempFile)
            val annotations = blocks.filterIsInstance<DocumentBlock.Comment>()
                .filter { it.kind == DocumentBlock.Comment.Kind.PDF_ANNOTATION }
            assertEquals(2, annotations.size, "Expected one annotation per page")
            val authors = annotations.map { it.author }
            assertTrue(
                "Alice" in authors && "Bob" in authors,
                "Both authors should appear; got $authors",
            )
        } finally {
            java.nio.file.Files.deleteIfExists(tempFile)
        }
    }
}
