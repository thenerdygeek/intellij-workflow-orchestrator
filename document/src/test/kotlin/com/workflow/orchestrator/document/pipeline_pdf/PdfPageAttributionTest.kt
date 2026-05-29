package com.workflow.orchestrator.document.pipeline_pdf

import com.workflow.orchestrator.core.model.DocumentBlock
import com.workflow.orchestrator.document.assembler.MarkdownAssembler
import com.workflow.orchestrator.document.pipeline.PdfPipeline
import com.workflow.orchestrator.document.service.TaggedPdfFixtureFactory
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Regression test for finding NAV-1 / G-1: PDF page anchors collapsed almost all body text
 * under `page 1`, leaving pages 2..N as empty, adjacent markers.
 *
 * Root cause: [com.workflow.orchestrator.document.pipeline.hardenedPdfConfig] enabled
 * `extractMarkedContent`, which makes Tika emit the entire reading-order text as flat `<p>`
 * elements FIRST, then dumps all `<div class="page">` boundaries (empty) at the tail. The
 * [com.workflow.orchestrator.document.pdf.PdfProseExtractor] therefore never advances
 * `currentPage` past 1 while consuming prose, so ~99% of prose is attributed to page 1.
 *
 * These tests build a real multi-page PDF (one distinct, page-numbered line per page) and assert
 * that prose is distributed across page anchors: each page's text is reachable AFTER its own page
 * marker and BEFORE the next, and the per-page anchor offsets are not all crammed together.
 */
class PdfPageAttributionTest {

    private val pipeline = PdfPipeline()
    private val assembler = MarkdownAssembler()

    @Test
    fun `prose is distributed across page anchors, not collapsed under page 1`(@TempDir tmp: Path) {
        val pages = 8
        // A TAGGED (marked-content) PDF — the shape produced by Word/Acrobat PDFMaker on which
        // the page-1 collapse reproduces. A plain non-tagged PDF pages correctly even with the
        // bug present, so it does NOT exercise the regression.
        val pdf = TaggedPdfFixtureFactory.create(tmp.resolve("tagged.pdf"), pages)

        val blocks: List<DocumentBlock> = pipeline.extract(pdf)
        val indexed = assembler.assembleIndexed(blocks)
        val md = indexed.markdown
        val pageAnchors = indexed.index.pages.associate { it.key.toInt() to it.offset }

        // Sanity: we have an anchor for (most) pages.
        assertTrue(pageAnchors.size >= pages - 1,
            "expected ~$pages page anchors, got ${pageAnchors.size}: $pageAnchors")

        // The defect signature: page-1 anchor would span almost the whole doc while later
        // anchors are crammed <20 chars apart. Assert the OPPOSITE — page 1 must NOT hold the
        // bulk of the document.
        val page1Off = pageAnchors[1] ?: 0
        val page2Off = pageAnchors[2] ?: error("no page-2 anchor: $pageAnchors")
        val span1to2 = page2Off - page1Off
        assertTrue(span1to2 < md.length / 2,
            "page-1 anchor span ($span1to2) holds >=50% of the ${md.length}-char body — prose collapsed under page 1. anchors=$pageAnchors")

        // Each page K's distinctive text ("Page K - section heading...") must be reachable via
        // page K's anchor: it appears AT OR AFTER page K's offset and BEFORE page K+1's offset.
        for (k in 2..pages) {
            val needle = "Page$k marker alpha"
            val at = md.indexOf(needle)
            if (at < 0) continue // text extraction may merge/skip; only assert positively-found text
            val anchorK = pageAnchors[k]
            val anchorNext = pageAnchors[k + 1] ?: md.length
            assertTrue(anchorK != null && at >= anchorK && at < anchorNext,
                "page-$k text found at $at but its anchor is $anchorK (next ${pageAnchors[k + 1]}). " +
                    "Text is not under its own page marker.")
        }

        // Later page anchors must be meaningfully separated (each page has real text), not the
        // ~18-char empty-marker clustering the bug produced.
        val gaps = (2 until pages).mapNotNull { k ->
            val a = pageAnchors[k]; val b = pageAnchors[k + 1]
            if (a != null && b != null) b - a else null
        }
        val tinyGaps = gaps.count { it < 20 }
        assertTrue(tinyGaps <= 1,
            "most page anchors are <20 chars apart ($tinyGaps tiny gaps of ${gaps.size}) — pages are empty markers. gaps=$gaps")
    }
}
