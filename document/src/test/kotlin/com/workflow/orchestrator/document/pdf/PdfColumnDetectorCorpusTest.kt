package com.workflow.orchestrator.document.pdf

import org.apache.pdfbox.Loader
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * SF-1 — opt-in corpus assertions for [PdfColumnDetector]. Skips silently when the corpus is
 * absent (so it is safe to leave in tree). The corpus lives at `/tmp/rd-corpus/inputs`.
 *
 * Run with: `./gradlew :document:test --tests "*PdfColumnDetectorCorpusTest*"`
 *
 * Asserts the design's measured classification:
 * - `pdf-rich-bjs-cv22.pdf` — a true 2-column newsletter: a healthy fraction of body pages
 *   (the design measured ~50%) detect a gutter.
 * - `pdf-rich-arxiv-attention.pdf`, `pdf-rich-fed-scf.pdf`, `nist-800-63-3.pdf` — single-column
 *   prose: essentially NO body pages detect a gutter (the conservative bias).
 */
@Tag("corpus")
class PdfColumnDetectorCorpusTest {

    private val inputDir = Paths.get("/tmp/rd-corpus/inputs")
    private val detector = PdfColumnDetector()

    private fun twoColPageFraction(name: String): Double? {
        val file = inputDir.resolve(name)
        if (!Files.isRegularFile(file)) return null
        Loader.loadPDF(file.toFile()).use { doc ->
            val total = doc.numberOfPages
            var twoCol = 0
            for (i in 0 until total) {
                if (detector.detectGutter(doc.getPage(i)) != null) twoCol++
            }
            println("[col-detect] $name: $twoCol/$total pages two-column")
            return twoCol.toDouble() / total
        }
    }

    @Test
    fun `bjs-cv22 is detected as substantially two-column`() {
        val frac = twoColPageFraction("pdf-rich-bjs-cv22.pdf") ?: run {
            println("[col-detect] corpus absent — skipping bjs assertion"); return
        }
        assertTrue(frac >= 0.30, "bjs-cv22 two-column fraction $frac too low (expected ≥0.30)")
    }

    @Test
    fun `single-column corpus docs are not over-classified as two-column`() {
        for (name in listOf("pdf-rich-arxiv-attention.pdf", "pdf-rich-fed-scf.pdf", "nist-800-63-3.pdf")) {
            val frac = twoColPageFraction(name)
            if (frac == null) {
                println("[col-detect] corpus absent — skipping $name")
                continue
            }
            assertTrue(frac <= 0.10, "$name two-column fraction $frac too high (false positives)")
        }
    }
}
