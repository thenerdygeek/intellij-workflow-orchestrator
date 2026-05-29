package com.workflow.orchestrator.document.pipeline_pdf

import com.workflow.orchestrator.core.model.DocumentBlock
import com.workflow.orchestrator.document.pipeline.PdfPipeline
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * SF-1 regression guard (corpus, opt-in) — every single-column corpus PDF must extract
 * BYTE-IDENTICAL with SF-1 column reorder enabled vs disabled. Proves the hybrid leaves the
 * single-column Tika path untouched on REAL documents (not just the synthetic fixture). Skips
 * silently when the corpus is absent.
 */
@Tag("corpus")
class PdfSingleColCorpusByteIdenticalTest {

    private val inputDir = Paths.get("/tmp/rd-corpus/inputs")

    private fun dump(blocks: List<DocumentBlock>): String = blocks.joinToString("\n") { it.toString() }

    @Test
    fun `single-column corpus docs are byte-identical with SF-1 on vs off`() {
        val singleCol = listOf(
            "pdf-rich-arxiv-attention.pdf",
            "pdf-rich-fed-scf.pdf",
            "nist-800-63-3.pdf",
            "rfc7230.pdf",
            // nist-csf: its valley-detected "2-column" pages are really the 4-column Framework
            // Core TABLE — the Tabula-presence gate must keep them byte-identical (NOT split).
            "nist-csf.pdf",
        )
        for (name in singleCol) {
            val file = inputDir.resolve(name)
            if (!Files.isRegularFile(file)) {
                println("[sf1-guard] corpus absent — skipping $name"); continue
            }
            val on = PdfPipeline(enableColumnReorder = true).extract(file)
            val off = PdfPipeline(enableColumnReorder = false).extract(file)
            assertEquals(dump(off), dump(on), "$name changed under SF-1 (expected byte-identical)")
            println("[sf1-guard] $name byte-identical (${on.size} blocks)")
        }
    }
}
