package com.workflow.orchestrator.document.service

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.nio.file.Path

/**
 * Builds deterministic, offline PDFs for the SF-1 two-column reading-order tests.
 *
 * Two shapes:
 * - [createTwoColumn]: a genuine two-column page. The left column occupies x∈[72,280] and the
 *   right column x∈[320,540] with a wide central white gutter (~300pt) and NO glyphs in the
 *   gutter band. Reading order is left-column-then-right-column ("ALPHA…GAMMA" then "DELTA…ZETA").
 *   This mirrors `pdf-rich-bjs-cv22.pdf`'s layout — where `PDFTextStripper(sortByPosition=true)`
 *   interleaves the two columns line-by-line (left-glyph, right-glyph, …).
 * - [createSingleColumn]: a single text block spanning the full body width (x∈[72,540]) with no
 *   central gutter — the overwhelming-majority single-column page that MUST flow through the
 *   unchanged Tika path unchanged.
 *
 * Both are LETTER-size (612×792). Lines are placed at known y-positions so the detector's
 * glyph-coverage histogram is reproducible.
 */
object ColumnLayoutPdfFixtureFactory {

    // Realistic prose columns: many lines of varied length so Tabula's stream-mode phantom-table
    // guard rejects them (a true 2-col prose page is NOT a clean grid), while the whitespace-valley
    // detector still sees the central gutter. The leading token of each column's first/last line is
    // a distinctive marker the tests assert reading order against.
    private val LEFT_LINES = listOf(
        "ALPHA the quick brown fox jumps over the lazy",
        "dog while the autumn sun sets slowly behind",
        "the distant rolling hills and the cold river",
        "flows on past the old stone bridge toward the",
        "sea where the gulls wheel and cry above the",
        "GAMMA harbour walls in the fading evening light",
    )
    private val RIGHT_LINES = listOf(
        "DELTA meanwhile across the wide and shallow",
        "river a grey heron stands perfectly still in",
        "the reeds waiting with infinite patience for a",
        "fish to drift within reach of its long sharp",
        "beak as the first stars begin to appear in the",
        "ZETA darkening sky over the quiet sleeping town",
    )

    /**
     * @param target  Where to write the PDF.
     * @param pages   Number of identical two-column pages.
     */
    fun createTwoColumn(target: Path, pages: Int = 1): Path {
        PDDocument().use { doc ->
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            repeat(pages) {
                val page = PDPage(PDRectangle.LETTER)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    // Left column: x starts at 72.
                    writeColumn(cs, font, LEFT_LINES, x = 72f, topY = 720f)
                    // Right column: x starts at 320 — leaves a wide white gutter ~[280,320]. The
                    // baseline is offset by 7pt and uses a slightly different leading so the two
                    // columns do NOT share row baselines — that is what distinguishes genuine 2-col
                    // prose from a 2-column TABLE (which Tabula's stream mode legitimately claims).
                    writeColumn(cs, font, RIGHT_LINES, x = 320f, topY = 713f, leading = 17f)
                }
            }
            doc.save(target.toFile())
        }
        return target
    }

    fun createSingleColumn(target: Path, pages: Int = 1): Path {
        PDDocument().use { doc ->
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            val lines = listOf(
                "ALPHA the quick brown fox jumps over the lazy dog while the sun",
                "BETA sets slowly behind the distant hills and the river flows on",
                "GAMMA meanwhile a heron stands perfectly still waiting patiently",
            )
            repeat(pages) {
                val page = PDPage(PDRectangle.LETTER)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    // Full-width single column: x starts at 72, glyphs run well past page center.
                    writeColumn(cs, font, lines, x = 72f, topY = 720f, fontSize = 11f)
                }
            }
            doc.save(target.toFile())
        }
        return target
    }

    /**
     * Builds a single-page PDF that mixes a HELVETICA prose paragraph (full-width, justified-ish —
     * the lines reach the right margin) with a COURIER preformatted block (an ABNF grammar whose
     * lines are short and do NOT fill the column) — the SF-2 shape. Returned page is LETTER-size.
     *
     * The prose paragraph is several proportional-font lines that run nearly to the right margin
     * (x ≈ 72 → ~520). The code block is monospace lines that stop well short of the right margin
     * (the longest is ~300pt wide), so the SF-2 detector's "consecutive monospace, column-non-filling"
     * signal fires on the grammar lines and never on the prose.
     *
     * Markers: prose contains "PROSEALPHA"/"PROSEOMEGA"; the code block contains the ABNF lines
     * "HTTP-version" and "chunk-size" so the test can assert they emerge inside a fenced block with
     * their line structure preserved (not reflowed onto one line).
     */
    fun createMonospaceCodeBlock(target: Path): Path {
        PDDocument().use { doc ->
            val helv = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            val courier = PDType1Font(Standard14Fonts.FontName.COURIER)
            val page = PDPage(PDRectangle.LETTER)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                // Prose paragraph: proportional font, lines fill nearly to the right margin (~520).
                val prose = listOf(
                    "PROSEALPHA the version of an HTTP message is indicated by an HTTP-version",
                    "field in the first line of the message and HTTP-version is case sensitive in",
                    "every conforming implementation across the entire protocol stack PROSEOMEGA.",
                )
                writeColumn(cs, helv, prose, x = 72f, topY = 720f, fontSize = 11f, leading = 16f)
                // Monospace ABNF grammar block: short lines that do NOT reach the right margin.
                val code = listOf(
                    "HTTP-version  = HTTP-name \"/\" DIGIT \".\" DIGIT",
                    "HTTP-name     = %x48.54.54.50 ; \"HTTP\"",
                    "chunk-size    = 1*HEXDIG",
                    "chunked-body  = *chunk last-chunk trailer-part CRLF",
                )
                writeColumn(cs, courier, code, x = 72f, topY = 640f, fontSize = 11f, leading = 14f)
            }
            doc.save(target.toFile())
        }
        return target
    }

    private fun writeColumn(
        cs: PDPageContentStream,
        font: PDType1Font,
        lines: List<String>,
        x: Float,
        topY: Float,
        fontSize: Float = 11f,
        leading: Float = 18f,
    ) {
        var y = topY
        for (line in lines) {
            cs.beginText()
            cs.setFont(font, fontSize)
            cs.newLineAtOffset(x, y)
            cs.showText(line)
            cs.endText()
            y -= leading
        }
    }
}
