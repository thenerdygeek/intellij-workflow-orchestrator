package com.workflow.orchestrator.document.pipeline_pdf

import com.workflow.orchestrator.core.model.DocumentBlock
import com.workflow.orchestrator.document.assembler.MarkdownAssembler
import com.workflow.orchestrator.document.pipeline.PdfPipeline
import com.workflow.orchestrator.document.service.ColumnLayoutPdfFixtureFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * SF-2 — preformatted/monospace fidelity. A monospace ABNF/code region must be emitted as a fenced
 * code block with its original line breaks PRESERVED (one source line per line), while ordinary
 * prose on the same page stays prose. Mirrors the SF-1 substitution test + byte-identical guard.
 */
class PdfPreformattedSubstitutionTest {

    private fun markdownOf(blocks: List<DocumentBlock>): String =
        MarkdownAssembler().assemble(blocks, maxChars = Int.MAX_VALUE).markdown

    private fun dump(blocks: List<DocumentBlock>): String = blocks.joinToString("\n") { it.toString() }

    @Test
    fun `monospace ABNF block is fenced with line breaks preserved while prose stays prose`(@TempDir dir: Path) {
        val pdf = ColumnLayoutPdfFixtureFactory.createMonospaceCodeBlock(dir.resolve("code.pdf"))
        val blocks = PdfPipeline().extract(pdf)

        // A CodeBlock must have been detected for the monospace ABNF region.
        val code = blocks.filterIsInstance<DocumentBlock.CodeBlock>()
        assertTrue(code.isNotEmpty(), "expected a CodeBlock for the monospace ABNF region; blocks:\n${dump(blocks)}")

        // The ABNF grammar lines must survive as SEPARATE lines (not reflowed onto one line).
        val codeLines = code.flatMap { it.lines }
        assertTrue(codeLines.any { it.contains("HTTP-version") && it.contains("HTTP-name") },
            "first ABNF production missing/merged: $codeLines")
        assertTrue(codeLines.any { it.startsWith("chunk-size") || it.contains("chunk-size    = 1*HEXDIG") },
            "chunk-size production missing/merged: $codeLines")
        // The two distinct productions must be on distinct lines — the run-on bug merges them.
        val httpLine = codeLines.indexOfFirst { it.contains("HTTP-version") }
        val chunkLine = codeLines.indexOfFirst { it.contains("chunk-size") }
        assertTrue(httpLine >= 0 && chunkLine >= 0 && httpLine != chunkLine,
            "ABNF productions collapsed onto one line: $codeLines")

        // The prose must NOT be fenced — it stays a Paragraph carrying its markers.
        val proseText = blocks.filterIsInstance<DocumentBlock.Paragraph>().joinToString(" ") { it.text }
        assertTrue(proseText.contains("PROSEALPHA"), "prose ALPHA marker lost: $proseText")
        assertTrue(proseText.contains("PROSEOMEGA"), "prose OMEGA marker lost: $proseText")
        assertTrue(code.none { it.lines.any { l -> l.contains("PROSEALPHA") } },
            "prose was wrongly fenced as code")

        // The assembled Markdown must render the region inside ``` fences with newlines preserved.
        val md = markdownOf(blocks)
        assertTrue(md.contains("```"), "no fenced code block in markdown:\n$md")
        val fenceStart = md.indexOf("```")
        val fenceEnd = md.indexOf("```", fenceStart + 3)
        assertTrue(fenceEnd > fenceStart, "unterminated fence:\n$md")
        val fenced = md.substring(fenceStart + 3, fenceEnd)
        assertTrue(fenced.contains("HTTP-version") && fenced.contains("chunk-size"),
            "ABNF not inside the fence:\n$md")
        // Inside the fence the two productions are on separate lines.
        assertTrue(fenced.lineSequence().count { it.isNotBlank() } >= 2,
            "fenced block collapsed to one line:\n$md")
    }

    @Test
    fun `single-column prose page is byte-identical with SF-2 enabled vs disabled`(@TempDir dir: Path) {
        val pdf = ColumnLayoutPdfFixtureFactory.createSingleColumn(dir.resolve("single-col.pdf"))
        val withSf2 = PdfPipeline(enablePreformatted = true).extract(pdf)
        val withoutSf2 = PdfPipeline(enablePreformatted = false).extract(pdf)
        assertEquals(dump(withoutSf2), dump(withSf2),
            "SF-2 preformatted handling must not alter ordinary single-column prose output")
    }
}
