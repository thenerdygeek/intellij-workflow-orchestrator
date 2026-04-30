package com.workflow.orchestrator.document.pipeline_pdf

import com.workflow.orchestrator.core.model.DocumentBlock
import com.workflow.orchestrator.document.assembler.MarkdownAssembler
import com.workflow.orchestrator.document.pipeline.PdfPipeline
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
}
