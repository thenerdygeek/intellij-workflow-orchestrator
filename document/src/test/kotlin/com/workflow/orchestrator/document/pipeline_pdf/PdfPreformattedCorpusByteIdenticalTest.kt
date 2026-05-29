package com.workflow.orchestrator.document.pipeline_pdf

import com.workflow.orchestrator.core.model.DocumentBlock
import com.workflow.orchestrator.document.pipeline.PdfPipeline
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * SF-2 regression guard (corpus, opt-in) — every PROPORTIONAL-font corpus PDF must extract
 * BYTE-IDENTICAL with SF-2 preformatted handling enabled vs disabled. Proves the conservative
 * monospace gate leaves non-code documents untouched on REAL files: a doc with no monospace lines
 * has no preformatted region, so SF-2 is a no-op. Skips silently when the corpus is absent.
 *
 * rfc7230 is deliberately NOT in this list: it is the all-Courier RFC whose ABNF/pseudo-code SF-2
 * SHOULD fence (so it is expected to CHANGE). The companion assertion below proves SF-2 produces
 * at least one fenced block for rfc7230 — i.e. the feature is actually firing on the target doc.
 */
@Tag("corpus")
class PdfPreformattedCorpusByteIdenticalTest {

    private val inputDir = Paths.get("/tmp/rd-corpus/inputs")

    private fun dump(blocks: List<DocumentBlock>): String = blocks.joinToString("\n") { it.toString() }

    @Test
    fun `proportional-font corpus docs are byte-identical with SF-2 on vs off`() {
        val proportional = listOf(
            "pdf-rich-arxiv-attention.pdf",
            "pdf-rich-fed-scf.pdf",
            "nist-800-63-3.pdf",
            "nist-csf.pdf",
        )
        for (name in proportional) {
            val file = inputDir.resolve(name)
            if (!Files.isRegularFile(file)) {
                println("[sf2-guard] corpus absent — skipping $name"); continue
            }
            val on = PdfPipeline(enablePreformatted = true).extract(file)
            val off = PdfPipeline(enablePreformatted = false).extract(file)
            assertEquals(dump(off), dump(on), "$name changed under SF-2 (expected byte-identical)")
            println("[sf2-guard] $name byte-identical (${on.size} blocks)")
        }
    }

    @Test
    fun `rfc7230 ABNF and pseudo-code are fenced as CodeBlocks under SF-2`() {
        val file = inputDir.resolve("rfc7230.pdf")
        if (!Files.isRegularFile(file)) {
            println("[sf2-guard] corpus absent — skipping rfc7230"); return
        }
        val on = PdfPipeline(enablePreformatted = true).extract(file)
        val off = PdfPipeline(enablePreformatted = false).extract(file)
        val codeBlocks = on.filterIsInstance<DocumentBlock.CodeBlock>()
        assertTrue(codeBlocks.size >= 5,
            "expected several fenced CodeBlocks in rfc7230, found ${codeBlocks.size}")
        // The HTTP-version ABNF production must be fenced with its two lines kept distinct.
        assertTrue(codeBlocks.any { cb -> cb.lines.any { it.contains("HTTP-version") && it.contains("HTTP-name") } },
            "HTTP-version ABNF production not fenced")
        assertTrue(codeBlocks.any { cb -> cb.lines.size >= 2 && cb.lines.any { it.contains("chunked-body") } },
            "chunked-body multi-line ABNF not fenced with line structure")
        // SF-2 must NOT be a no-op on this doc.
        assertTrue(dump(on) != dump(off), "SF-2 did not change rfc7230 (expected fencing)")
        println("[sf2-guard] rfc7230 fenced ${codeBlocks.size} CodeBlocks")
    }
}
