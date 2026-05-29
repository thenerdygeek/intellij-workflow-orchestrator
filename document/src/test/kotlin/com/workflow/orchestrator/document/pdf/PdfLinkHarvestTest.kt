package com.workflow.orchestrator.document.pdf

import com.workflow.orchestrator.core.model.DocumentBlock
import com.workflow.orchestrator.document.assembler.MarkdownAssembler
import com.workflow.orchestrator.document.pipeline.PdfPipeline
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files

/**
 * TDD for **G-6 / HX-1 harvest**: PDF hyperlinks (`/URI` external + `/Link` GoTo internal) must
 * survive extraction as Markdown links instead of being dropped.
 *
 * [PdfMetadataExtractor] historically walked only `PDAnnotationMarkup` (sticky notes / highlights),
 * so every `PDAnnotationLink` was invisible — the display text rendered as dead prose with no
 * recoverable target. These tests pin the additive behaviour:
 *
 * - An external `/URI` link over display text → `[display text](https://…)` exactly once,
 *   reading order intact, no duplication, no empty `[]`/`()`.
 * - An internal `/Link` GoTo to a later page → a recoverable internal reference (`(#page-N)` /
 *   `(page N)`), again inline and once.
 */
class PdfLinkHarvestTest {

    /**
     * One-page PDF whose body line is:
     *   "The code is available at OurRepo for download."
     * with a `/URI` link annotation rectangle placed over the word "OurRepo".
     */
    private fun buildPdfWithUriLink(): ByteArray {
        val doc = PDDocument()
        val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
        val page = PDPage(PDRectangle.LETTER)
        doc.addPage(page)

        // Render the body line at y=700, starting x=72. Helvetica 12pt: avg ~6.7 units/char.
        val prefix = "The code is available at "
        val linkWord = "OurRepo"
        PDPageContentStream(doc, page).use { cs ->
            cs.beginText()
            cs.setFont(font, 12f)
            cs.newLineAtOffset(72f, 700f)
            cs.showText("$prefix$linkWord for download.")
            cs.endText()
        }

        // Rectangle over "OurRepo". prefix is 25 chars → ~170 units; linkWord ~ 47 units wide.
        val font12 = 12f
        val prefixWidth = font.getStringWidth(prefix) / 1000f * font12
        val wordWidth = font.getStringWidth(linkWord) / 1000f * font12
        val x0 = 72f + prefixWidth
        val link = PDAnnotationLink().apply {
            rectangle = PDRectangle(x0, 696f, wordWidth, 16f)
            action = PDActionURI().apply { uri = "https://github.com/example/ourrepo" }
        }
        page.annotations.add(link)

        val out = ByteArrayOutputStream()
        doc.save(out)
        doc.close()
        return out.toByteArray()
    }

    /**
     * Three-page PDF. Page 1 body line:
     *   "See AppendixA for the full proof."
     * with a `/Link` GoTo annotation over "AppendixA" whose destination is page 3.
     */
    private fun buildPdfWithGoToLink(): ByteArray {
        val doc = PDDocument()
        val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
        val pages = (0 until 3).map { PDPage(PDRectangle.LETTER).also { doc.addPage(it) } }

        val prefix = "See "
        val linkWord = "AppendixA"
        PDPageContentStream(doc, pages[0]).use { cs ->
            cs.beginText()
            cs.setFont(font, 12f)
            cs.newLineAtOffset(72f, 700f)
            cs.showText("$prefix$linkWord for the full proof.")
            cs.endText()
        }
        // Give page 3 some body so it is a real page.
        PDPageContentStream(doc, pages[2]).use { cs ->
            cs.beginText()
            cs.setFont(font, 12f)
            cs.newLineAtOffset(72f, 700f)
            cs.showText("Appendix A: the full proof of the theorem.")
            cs.endText()
        }

        val prefixWidth = font.getStringWidth(prefix) / 1000f * 12f
        val wordWidth = font.getStringWidth(linkWord) / 1000f * 12f
        val x0 = 72f + prefixWidth
        val link = PDAnnotationLink().apply {
            rectangle = PDRectangle(x0, 696f, wordWidth, 16f)
            action = PDActionGoTo().apply {
                destination = PDPageFitDestination().apply { page = pages[2] }
            }
        }
        pages[0].annotations.add(link)

        val out = ByteArrayOutputStream()
        doc.save(out)
        doc.close()
        return out.toByteArray()
    }

    private fun extractMarkdown(pdfBytes: ByteArray): String {
        val tempFile = Files.createTempFile("link-harvest-", ".pdf")
        Files.write(tempFile, pdfBytes)
        return try {
            val blocks = PdfPipeline().extract(tempFile)
            MarkdownAssembler().assemble(blocks, 200_000).markdown
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    @Test
    fun `external URI link renders as inline markdown link exactly once`() {
        val md = extractMarkdown(buildPdfWithUriLink())

        assertTrue(
            md.contains("[OurRepo](https://github.com/example/ourrepo)"),
            "Expected inline markdown link [OurRepo](https://github.com/example/ourrepo). Markdown:\n$md",
        )
        // Display text appears exactly once (only inside the link, not duplicated as bare prose).
        assertEquals(
            1,
            Regex(Regex.escape("OurRepo")).findAll(md).count(),
            "\"OurRepo\" must appear exactly once. Markdown:\n$md",
        )
        // Surrounding prose intact and in order.
        assertTrue(md.contains("The code is available at"), "Prefix prose lost. Markdown:\n$md")
        assertTrue(md.contains("for download."), "Suffix prose lost. Markdown:\n$md")
        // No empty brackets/parens.
        assertFalse(md.contains("[]") || md.contains("()"), "Empty brackets/parens present. Markdown:\n$md")
    }

    @Test
    fun `internal GoTo link renders as recoverable inline reference once`() {
        val md = extractMarkdown(buildPdfWithGoToLink())

        // The display text must be wrapped in a markdown link whose target makes page 3 recoverable.
        val linkRe = Regex("""\[AppendixA\]\((#page-3|page 3)\)""")
        assertTrue(
            linkRe.containsMatchIn(md),
            "Expected internal reference [AppendixA](#page-3) or (page 3). Markdown:\n$md",
        )
        assertEquals(
            1,
            Regex(Regex.escape("AppendixA")).findAll(md).count(),
            "\"AppendixA\" must appear exactly once. Markdown:\n$md",
        )
        assertTrue(md.contains("See ") && md.contains("for the full proof."), "Surrounding prose lost. Markdown:\n$md")
        assertFalse(md.contains("[]") || md.contains("()"), "Empty brackets/parens present. Markdown:\n$md")
    }
}
