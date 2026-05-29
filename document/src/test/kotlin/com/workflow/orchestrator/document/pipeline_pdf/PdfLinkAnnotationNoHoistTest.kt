package com.workflow.orchestrator.document.pipeline_pdf

import com.workflow.orchestrator.core.model.DocumentBlock
import com.workflow.orchestrator.document.assembler.MarkdownAssembler
import com.workflow.orchestrator.document.pipeline.PdfPipeline
import com.workflow.orchestrator.document.pipeline.hardenedPdfConfig
import org.apache.pdfbox.cos.COSArray
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSInteger
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDNumberTreeNode
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkInfo
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files

/**
 * Regression for finding **HX-1**: PDF link-annotation display text was being **hoisted /
 * merged** so that multiple links in a paragraph collapsed onto the first anchor's position —
 * duplicating some text and leaving empty `()` / `[]` brackets elsewhere. In the
 * `nist-800-63b.pdf` normative standard this REVERSED meaning, producing artifacts such as
 * `(Section 5.1.7Section 5.1.1)`, `FIPS 140FIPS 140FIPS 140FIPS 140`, `[RFC 20ISO/ISC 10646] …
 * Unicode []`, and `Memorized Secret ()`.
 *
 * ## Root cause (pinned empirically against the real fixture)
 *
 * The corruption is controlled **solely** by `PDFParserConfig.isExtractMarkedContent`. When that
 * flag is `true`, Tika's PDFParser extracts text per **marked-content / structure region** rather
 * than via the standard `PDFTextStripper` reading order. In a *tagged* PDF whose `/Link`
 * annotations participate in the logical structure tree (a `Link` structure element with an
 * `OBJR` reference to the annotation), the link's display text is emitted **at the structure
 * element's position**. Several links in one paragraph therefore cluster their text at the first
 * anchor and leave the later runs empty — the hoist/duplicate/empty-bracket corruption.
 *
 * With `isExtractMarkedContent = false` (the committed state, see
 * [com.workflow.orchestrator.document.pipeline.HardenedPdfConfig]) Tika falls back to faithful
 * per-page text-stripping, so every reference reads correctly in document order. This was set for
 * finding NAV-1 / G-1 (page attribution) and **also** fixes HX-1 — the same lever governs both.
 *
 * `isExtractAnnotationText` is independent: it only controls whether each annotation's
 * `display-text (href)` is *appended* to the prose; it does NOT cause the hoist. It is also
 * `false` in the committed config (PdfMetadataExtractor is the sole annotation source for typed
 * Comment blocks).
 *
 * The broader feature — harvesting every `/URI` and `/Link` annotation into proper Markdown
 * links pointing at section/table anchors — is deferred to **P1** and is NOT covered here.
 *
 * ## Tests
 *
 * 1. [hardenedPdfConfig pins the marked-content and annotation-text flags off] — a config
 *    contract that fails the moment either lever is re-enabled. This is the direct guard for the
 *    root cause; it is mechanism-true because the corruption is a pure function of these flags.
 * 2. A tagged-PDF behavioral guard: build a tagged PDF with two `/Link`-structured runs and
 *    assert the extracted prose keeps each run's text once, in order, with no merge or empty
 *    brackets — under the committed config.
 */
class PdfLinkAnnotationNoHoistTest {

    // ── 1. Config contract: the exact lever that governs HX-1 ──────────────────

    @Test
    fun `hardenedPdfConfig pins the marked-content and annotation-text flags off`() {
        val cfg = hardenedPdfConfig()
        assertFalse(
            cfg.isExtractMarkedContent,
            "HX-1 regression guard: isExtractMarkedContent MUST stay false. When true, Tika hoists " +
                "tagged-PDF link display text onto the first structure anchor, producing meaning-" +
                "reversing artifacts like \"(Section 5.1.7Section 5.1.1)\" and \"FIPS 140FIPS 140…\".",
        )
        assertFalse(
            cfg.isExtractAnnotationText,
            "isExtractAnnotationText MUST stay false: PdfMetadataExtractor is the sole annotation " +
                "source (typed Comment blocks). Re-enabling re-injects duplicate annotation prose.",
        )
    }

    // ── 2. Behavioral guard on a tagged PDF with structured link runs ──────────

    /**
     * Builds a **tagged** one-page PDF whose body line is
     *
     *     "See Section 5.1.7 and also Section 5.1.1 for memorized secret rules."
     *
     * Each "Section 5.1.x" run is wrapped in its own marked-content region tied to a `Link`
     * structure element with an `OBJR` reference to a `/Link` annotation (the shape that triggers
     * the HX-1 hoist when marked-content extraction is on). Under the committed config
     * (`isExtractMarkedContent = false`) the prose must read faithfully in order.
     */
    private fun buildTaggedPdfWithStructuredLinks(): ByteArray {
        val doc = PDDocument()
        val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)

        doc.documentCatalog.markInfo = PDMarkInfo().apply { isMarked = true }
        val structRoot = PDStructureTreeRoot()
        doc.documentCatalog.structureTreeRoot = structRoot
        val docElem = PDStructureElement("Document", structRoot)
        structRoot.appendKid(docElem)

        val page = PDPage(PDRectangle.LETTER)
        doc.addPage(page)
        page.cosObject.setInt(COSName.STRUCT_PARENTS, 0)

        // Two link annotations, one per "Section 5.1.x" run.
        val link1 = PDAnnotationLink().apply {
            rectangle = PDRectangle(95f, 695f, 70f, 16f)
            action = PDActionURI().apply { uri = "https://example.com/sec-5-1-7" }
        }
        val link2 = PDAnnotationLink().apply {
            rectangle = PDRectangle(210f, 695f, 70f, 16f)
            action = PDActionURI().apply { uri = "https://example.com/sec-5-1-1" }
        }
        page.annotations.add(link1)
        page.annotations.add(link2)

        // Render three marked-content runs (prefix, link1, mid, link2, suffix) so the link text
        // sits inline; each link run carries its own MCID.
        val runs = listOf(
            "See " to false,
            "Section 5.1.7" to true,
            " and also " to false,
            "Section 5.1.1" to true,
            " for memorized secret rules." to false,
        )
        val pageMcidElems = COSArray()
        var mcid = 0
        var x = 72f
        PDPageContentStream(doc, page).use { cs ->
            for ((textRun, isLink) in runs) {
                val tag = if (isLink) COSName.LINK else COSName.getPDFName("Span")
                val props = COSDictionary().apply { setInt(COSName.MCID, mcid) }
                cs.beginMarkedContent(tag, PDPropertyList.create(props))
                cs.beginText()
                cs.setFont(font, 12f)
                cs.newLineAtOffset(x, 700f)
                cs.showText(textRun)
                cs.endText()
                cs.endMarkedContent()

                val elem = PDStructureElement(if (isLink) "Link" else "Span", docElem)
                elem.page = page
                docElem.appendKid(elem)
                elem.cosObject.setItem(COSName.K, COSInteger.get(mcid.toLong()))
                pageMcidElems.add(elem.cosObject)

                mcid++
                x += (textRun.length * 6f)
            }
        }

        val parentTree = PDNumberTreeNode(PDStructureElement::class.java)
        val nums = COSArray().apply {
            add(COSInteger.get(0L))
            add(pageMcidElems)
        }
        parentTree.cosObject.setItem(COSName.NUMS, nums)
        structRoot.parentTree = parentTree
        structRoot.parentTreeNextKey = 1

        val out = ByteArrayOutputStream()
        doc.save(out)
        doc.close()
        return out.toByteArray()
    }

    @Test
    fun `tagged pdf link runs are not hoisted duplicated or left as empty brackets`() {
        val pdfBytes = buildTaggedPdfWithStructuredLinks()
        val tempFile = Files.createTempFile("hx1-tagged-link-", ".pdf")
        Files.write(tempFile, pdfBytes)
        try {
            val blocks = PdfPipeline().extract(tempFile)
            val markdown = MarkdownAssembler().assemble(blocks, 200_000).markdown

            // Each section reference appears exactly once, at its own position.
            assertEquals(
                1,
                Regex(Regex.escape("Section 5.1.7")).findAll(markdown).count(),
                "\"Section 5.1.7\" must appear exactly once (not hoisted/duplicated). Markdown:\n$markdown",
            )
            assertEquals(
                1,
                Regex(Regex.escape("Section 5.1.1")).findAll(markdown).count(),
                "\"Section 5.1.1\" must appear exactly once (not hoisted/duplicated). Markdown:\n$markdown",
            )

            // No merged "…5.1.7Section…" (two distinct links collapsed at one anchor).
            assertFalse(
                markdown.contains("5.1.7Section") || markdown.contains("5.1.1Section"),
                "Section references must not be merged with no separating space. Markdown:\n$markdown",
            )

            // Reading order preserved: 5.1.7 occurs before 5.1.1.
            val idx7 = markdown.indexOf("Section 5.1.7")
            val idx1 = markdown.indexOf("Section 5.1.1")
            assertFalse(
                idx7 < 0 || idx1 < 0 || idx7 > idx1,
                "Section 5.1.7 must read before Section 5.1.1. idx7=$idx7 idx1=$idx1. Markdown:\n$markdown",
            )

            // No empty parens / brackets left by a vanished anchor's text.
            assertFalse(
                markdown.contains("()") || markdown.contains("[]"),
                "No empty () or [] left by a hoisted/emptied anchor. Markdown:\n$markdown",
            )
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }
}
