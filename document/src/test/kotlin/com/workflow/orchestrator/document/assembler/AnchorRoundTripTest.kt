package com.workflow.orchestrator.document.assembler

import com.workflow.orchestrator.core.model.DocumentBlock
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * NAV-2 invariant: every section/page anchor offset must index into the EXACT string that
 * becomes `content.md`. For any section anchor `a`, `markdown.substring(a.offset)` must
 * begin with the serialised heading (`"#".repeat(level) + " " + text`).
 *
 * This is the assembler-level (format-agnostic) guard. The same invariant is exercised
 * end-to-end through the persisted artifact in [HtmlOffsetRoundTripTest].
 *
 * Critically, the heading text here contains multibyte glyphs (`Español`, `–`): the round
 * trip must hold on the UTF-16 char stream regardless of how many bytes each glyph occupies,
 * proving the offsets are char-based against the final assembled string (not byte-based and
 * not computed on a pre-normalization stream).
 */
class AnchorRoundTripTest {

    private val assembler = MarkdownAssembler()

    @Test
    fun `every section anchor round-trips onto its serialised heading (multibyte-safe)`() {
        val blocks = listOf(
            DocumentBlock.Heading(1, "Introducción"),
            DocumentBlock.Paragraph("Cuerpo del texto con un guion — largo."),
            DocumentBlock.Heading(2, "Tablé — São Tomé"),
            DocumentBlock.Paragraph("Más texto: Türkçe, Español."),
            DocumentBlock.PageMarker(2),
            DocumentBlock.Heading(2, "References"),
            DocumentBlock.Paragraph("End."),
        )

        val out = assembler.assembleIndexed(blocks)
        val md = out.markdown

        for (anchor in out.index.sections) {
            // The anchor key is the heading text; the serialised form is the heading line.
            val tail = md.substring(anchor.offset)
            assertTrue(
                tail.startsWith("#") && tail.contains(anchor.key),
                "Section anchor '${anchor.key}' @${anchor.offset} did not land on its heading; " +
                    "got: ${tail.take(40)}",
            )
            // Stronger: the heading text begins immediately after the "## " prefix.
            val afterHashes = tail.dropWhile { it == '#' }.removePrefix(" ")
            assertTrue(
                afterHashes.startsWith(anchor.key),
                "Section anchor '${anchor.key}' @${anchor.offset} prefix mismatch; got: ${afterHashes.take(40)}",
            )
        }

        for (anchor in out.index.pages) {
            val tail = md.substring(anchor.offset)
            assertTrue(
                tail.startsWith("<!-- page: ${anchor.key} -->"),
                "Page anchor '${anchor.key}' @${anchor.offset} did not land on its marker; got: ${tail.take(40)}",
            )
        }
    }
}
