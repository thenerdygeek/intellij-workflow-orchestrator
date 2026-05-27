package com.workflow.orchestrator.document.service

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.nio.file.Path

/** Builds a real multi-page PDF: text on every page, a ruled (lattice) 3x3 table every 10th page. */
object LargePdfFixtureFactory {
    fun create(target: Path, pages: Int): Path {
        PDDocument().use { doc ->
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            for (i in 1..pages) {
                val page = PDPage(PDRectangle.LETTER)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(font, 12f)
                    cs.newLineAtOffset(72f, 720f)
                    cs.showText("Page $i - section heading and body text for extraction testing.")
                    cs.endText()
                    if (i % 10 == 0) {
                        val x0 = 72f; val y0 = 600f; val w = 120f; val h = 30f
                        for (r in 0..3) { cs.moveTo(x0, y0 - r * h); cs.lineTo(x0 + 3 * w, y0 - r * h); cs.stroke() }
                        for (c in 0..3) { cs.moveTo(x0 + c * w, y0); cs.lineTo(x0 + c * w, y0 - 3 * h); cs.stroke() }
                        for (r in 0..2) for (c in 0..2) {
                            cs.beginText(); cs.setFont(font, 9f)
                            cs.newLineAtOffset(x0 + c * w + 4f, y0 - r * h - 18f)
                            cs.showText("r${r}c${c}"); cs.endText()
                        }
                    }
                }
            }
            doc.save(target.toFile())
        }
        return target
    }
}
