# SF-1 Two-Column Reading-Order Interleave — Design

**Date:** 2026-05-29
**Module:** `:document` (read_document PDF pipeline)
**Status:** DESIGN ONLY — gates a scope decision, no production code changed.
**Branch:** `feature/cross-ide-delegation` (worktree `.worktrees/cross-ide`, HEAD `6efc2ca22`)
**Stack:** Kotlin, Tika 3.2.3 (prose), Tabula 1.0.5 (tables), PDFBox 3.0.5 (annotations/links/outline), JUnit5.

Covers deferred findings **SF-1** (two-column reading-order interleave), **SF-8** (masthead two-column fusion),
and partially **SF-2** (preformatted fidelity). All three are documented in `PdfPipeline.kt` as "need position data — NOT done here".

---

## 0. TL;DR / scope recommendation up front

**Do NOT do a full PDFBox rewrite of the prose path.** The measured damage is **concentrated in one
true multi-column document** (`pdf-rich-bjs-cv22.pdf`, 50% of pages). The other 8 corpus PDFs are
single-column for prose purposes; their "reading order" complaints are actually **figure/chart overlays**
(arxiv p13 rotated figure labels, fed-scf chart-axis splice) or **wide multi-column tables**
(nist-csf Framework Core) — neither is fixed by a column reorder, and one (the tables) would be made WORSE.

**Recommended: Option (c) Hybrid** — keep the entire Tika prose path unchanged; add a small PDFBox-based
**column detector + region re-segmenter** that activates ONLY on pages a whitespace-valley detector
classifies as genuine multi-column AND that Tabula has NOT claimed as a table. On those pages, replace the
Tika prose for that page with column-ordered prose produced by `PDFTextStripperByArea`. Single-column pages
(the overwhelming majority) flow through the existing, fully-fixed Tika path untouched, so G-1/G-5/G-6/G-9
carry zero regression risk on those pages.

This is a **medium** effort (one detector class + one per-page region extractor + a page-replacement seam in
`PdfPipeline`), not the "large architectural rewrite" the deferral note feared. **SF-2 stays deferred** — it
needs intra-line position data the column path does not surface, and it is rare (rfc7230 ABNF only).

---

## 1. Failure characterization per corpus PDF (measured)

Method: a throwaway PDFBox spike (now deleted) ran `PDFTextStripper` with `setSortByPosition(true)` and a
**whitespace-valley column detector** — histogram glyph x-coverage into 60 bins across page width, measure the
minimum-coverage bin in the center band (35%–65% of width) relative to mean page coverage. A deep valley
(ratio < 0.15) = a vertical white gutter = two columns. Full doc scanned (sampled up to ~40 pages/doc).

| PDF | Pages | % pages classified 2-col | Real layout | SF-1 damage? |
|---|---|---|---|---|
| **pdf-rich-bjs-cv22.pdf** | 34 | **50%** (pp 2,5,6,8,10,11,18–23,…) | **True 2-column** newsletter | **YES — primary victim** |
| nist-csf.pdf | 55 | 36% (pp 31–55) | Single-col prose + **4-col Framework Core TABLE** | NO (false positive — it's a table) |
| pdf-rich-arxiv-attention.pdf | 15 | 0% | 2-col abstract (p1) only; **body is single-col** | NO (p13 garble = rotated figure label) |
| pdf-rich-fed-scf.pdf | 58 | 0% | Single-col | NO (chart-axis splice = figure overlay) |
| nist-800-53r5.pdf | 492 | 0% | Single-col + marginal date/sec sidebars | NO |
| nist-800-63-3.pdf | 76 | 3% (pp 2,40) | Single-col | Marginal |
| nist-800-63b.pdf | 80 | 0% | Single-col | NO |
| rfc7230.pdf | 89 | 4% (pp 85,87 = index) | Single-col; masthead 2-col (p1) | SF-8 masthead only; SF-2 ABNF |

### Smoking-gun evidence (bjs-cv22 p2)

`sortByPosition=TRUE` **still interleaves** left+right columns line-by-line:

```
Findings are based on the National Crime Victimization
Survey (NCVS), a self-report survey administered Victimization estimates   <- right-col header fused in
annually from January 1 to December 31. Annual NCVS Victimizations reflect the total number of times that
estimates are based on the number and characteristics persons or households were victims of crime. There were
```

x-coverage profile (30 bins) shows the gutter clearly: `005899988877752599999998997300` — dip at bins 13–14.

After **region split** at the detected gutter (x≈300), the same page reads in correct column order:

```
Findings are based on the National Crime Victimization
Survey (NCVS), a self-report survey administered
annually from January 1 to December 31. Annual NCVS
estimates are based on the number and characteristics
... [entire left column] ...
   ===[COL BREAK]===
Victimization estimates—The total number of times
that persons or households were victims of crime. ...
```

### Anomalies resolved (these are why a naive rewrite over-engineers)

- **arxiv body (pp4–5)** coverage profile `000006999999888...` — ONE solid band, no center valley → single-column.
  The famous "2-column paper" only has a 2-col *abstract* on p1; prose body is single-col. The p13
  `AInttpenutito-nInVipsuuatli zLataioynes` garble is a **vertically-rotated figure axis label** interleaving
  with caption glyphs — a figure problem, untouched by column logic.
- **nist-csf pp31–55** profile `001111245443888876269897764100` — this is the **Function/Category/Subcategory/
  Informative References 4-column TABLE**, not prose. The valley detector flags it, but **Tabula already owns
  this** as a `Table`. A column reorder here would fight Tabula and corrupt the table. → The detector MUST be
  gated by "Tabula did not claim a table on this page."
- **fed-scf p8** `real15 median net worth surged 37` splice — single-column page; the stray `15` is a **chart
  data-label** rendered over prose. Column logic cannot fix it; it needs figure-bbox exclusion (out of scope).

---

## 2. PDFBox behavior findings (measured spike)

1. **`setSortByPosition(true)` does NOT fix true columns.** PDFBox sorts glyphs y-then-x within a tolerance,
   so two lines at the same y in different columns are emitted left-glyph, right-glyph, left, right … → the
   bjs-cv22 interleave above. This confirms the deferral note's premise. (It DOES help single-column pages
   with marginal sidebars, which is why the NIST specs read acceptably — and why G-1's `isSortByPosition=true`
   in `hardenedPdfConfig` is correct to keep for the Tika path.)

2. **Column detection is reliable via whitespace-valley histogram**, NOT via line-start x-clustering.
   First attempt (cluster line-start x into peaks) produced false positives on indented equations/section
   numbers (arxiv flagged 2-col wrongly). The **glyph-coverage valley** approach (count every glyph's x-span
   into bins, find the center white gutter) cleanly separated: bjs p2 valley ratio **0.03**; arxiv p2 **1.43**,
   fed-scf p8 **1.31**, rfc7230 p5 **0.96**, nist-csf prose p20 **1.15** — an order-of-magnitude gap.
   Threshold ~0.15 works for clean pages.

3. **Per-column extraction via `PDFTextStripperByArea`** (split page into L/R rectangles at the detected gutter
   x, `extractRegions`, read L then R) produces correct reading order. This is the same PDFBox text layer the
   existing G-6 link harvester already uses (`PDFTextStripperByArea` is already imported in
   `PdfMetadataExtractor.kt`), so no new dependency, no font/encoding surprises.

4. **Threshold tuning caveat (measured):** a *fixed* 0.15 ratio against page-mean gives a **false negative** on
   bjs p4 (valley ratio 0.64) because a figure/table straddles the gutter and lifts center coverage. The robust
   metric is the center-band local minimum **relative to the two flanking column bands** (a gutter is low vs.
   its immediate neighbors even when page-mean is dragged up), not relative to page-mean. This is an
   implementation tuning detail, not an architectural blocker — bjs is unambiguously 2-col in every profile.

5. **2 vs N columns:** all true multi-column prose in the corpus is **exactly 2 columns**. The Framework Core
   4-col case is a table (Tabula's job). So the detector only needs a single-gutter / two-region model for v1.

---

## 3. Architecture options + regression analysis

The existing prose path is: `PdfProseExtractor` → `TikaXhtmlPipeline` → `DocumentBlockHandler` (1111-line SAX
handler doing heading detection, TOC dot-leader cleanup, glued-token repair, CSV table parse) → synthetic
per-page Y counters → merged in `PdfPipeline` with Tabula tables / PDFBox annotations, then `spliceLinksIntoProse`
(G-6, matches link display text inside `Paragraph.text`), `demoteProseHeadings` (G-5, gated on outline),
`removeProseDuplicatedByTables`, `stripRepeatedPageChrome`, `rejoinHyphenatedParagraphs` (G-9), `suppressOverlaps`.

### (a) Full replacement — PDFBox position-based prose extractor replaces Tika prose
- **Pros:** real bboxes everywhere; could also fix SF-2; one coordinate system.
- **Cons / regression risk: SEVERE.** All of G-1/G-5/G-6/G-9 + the 1111-line `DocumentBlockHandler` logic ride
  on Tika's XHTML element stream:
  - **G-1** (page attribution via `<div class=page>` interleave) — gone; must reimplement page tracking from
    PDFBox (doable, but re-derives the exact bug G-1 fixed).
  - **G-5** (heuristic heading detection + TOC cleanup in `DocumentBlockHandler.tryEmitStandaloneHeading`) —
    Tika gives `<h1..h6>` and clean `<p>` text; raw `PDFTextStripper` gives undifferentiated lines. Heading
    heuristics would need full reimplementation against font-size/weight from `TextPosition`. High risk.
  - **G-6** link splicing matches link display text against `Paragraph.text`; a different tokenizer/whitespace
    model changes match rates → silently drops or corrupts links.
  - **G-9** glued-token repair, dot-leader TOC cleanup, hyphen rejoin were tuned against Tika's exact output.
  - **CSV/table-prose dedup** keys off `Paragraph` token sets.
  - **Verdict: rejected.** This is the "large rewrite" that re-opens 5 shipped P0/P1 fixes for a problem that
    affects ~1 of 9 corpus docs. Violates "architectural fix" ≠ "rewrite the world."

### (b) Targeted column-reorder layer — detect columns, reorder/segment, feed existing block builder
- Detect columns via PDFBox; for 2-col pages, emit text region-ordered, then push through
  `DocumentBlockHandler` by synthesizing XHTML (or call the handler's paragraph path directly).
- **Pros:** keeps `DocumentBlockHandler` logic.
- **Cons:** to keep G-1/G-5/G-9 you must re-feed the column-ordered text as Tika-shaped XHTML for the whole
  doc, i.e. you replace Tika's *parse* for every page, including single-column pages that work today. That is
  effectively (a) with extra plumbing. Medium-high risk; touches single-column pages unnecessarily.

### (c) Hybrid — Tika for single-column pages; PDFBox column handling ONLY on detected multi-column pages  ★ RECOMMENDED
- New `PdfColumnDetector` (PDFBox): per page, returns `null` (single-col) or a gutter x (2-col), **gated** so it
  returns `null` when Tabula reported a table overlapping that page (kills the nist-csf false positive).
- New `PdfColumnProseExtractor` (PDFBox `PDFTextStripperByArea`): for a 2-col page, produces left-then-right
  ordered text, split into paragraph-sized chunks.
- `PdfProseExtractor`/`PdfPipeline` seam: for each page, if detector says 2-col, **drop the Tika prose blocks for
  that page** and substitute the column-ordered paragraphs (carrying the same synthetic per-page Y so merge,
  chrome strip, dedup, overlap all still work). Single-column pages: **byte-for-byte the current Tika path.**
- **Regression analysis per fix:**
  - **G-1 (page attribution):** UNTOUCHED on single-col pages. On 2-col pages we already know the page number
    (we iterate pages explicitly), so attribution is *more* certain, not less.
  - **G-5 (outline headings + demotion):** Outline headings come from `PdfMetadataExtractor` (PDFBox), wholly
    independent of which prose source a page used — unaffected. Heuristic heading detection
    (`DocumentBlockHandler`) is skipped on the few 2-col pages; acceptable because bjs-cv22 has a PDF outline /
    few standalone headings on 2-col body pages. Risk: a heading on a 2-col page won't get heuristic promotion.
    Mitigation: run the column paragraphs through the same `tryEmitStandaloneHeading` helper (reuse, don't fork)
    OR accept body-text demotion on those pages. Low risk.
  - **G-6 (link splice):** `spliceLinksIntoProse` matches display text inside `Paragraph.text` regardless of how
    the paragraph was produced. As long as the column extractor yields the same glyphs (it uses the SAME PDFBox
    text layer as the link harvester — `PDFTextStripperByArea`), match rate is preserved or improved (column
    order makes the display text contiguous). Low risk; add a corpus assertion on bjs links.
  - **G-9 (glued tokens, hyphen rejoin, chrome strip):** chrome strip + hyphen rejoin run in `PdfPipeline` AFTER
    merge on `Paragraph` blocks — source-agnostic, unaffected. Glued-token/dot-leader repair lives in
    `DocumentBlockHandler` and is skipped on 2-col pages; reuse the same cleanup helpers on the column paragraphs
    (extract them to a shared util) to preserve behavior. Medium-low risk, mechanically containable.
  - **Tables (Tabula):** the detector is GATED by Tabula table presence, so the nist-csf 4-col table and any
    bordered/borderless table page is never column-split. Dedup/overlap unchanged.
- **Cons:** two prose code paths to maintain; the cleanup helpers must be shared to avoid drift.
- **Verdict: recommended** — isolates all risk to the ~1 doc that needs it.

### (d) Custom `PDFTextStripper` subclass overriding article/region processing
- Override `processTextPosition`/`writeString` or use `setArticleStart/End` to honor PDF article threads.
- **Cons:** PDF "article beads" are rarely authored (none in this corpus); overriding the stripper globally
  replaces Tika for ALL pages (same blast radius as (a)). The *region* mechanism of (c) is the useful 80% of
  this idea without the global replacement. Rejected as a standalone; its `PDFTextStripperByArea` usage IS what
  (c) adopts.

---

## 4. Staged implementation plan (TDD-able)

**Fixture strategy (synthetic 2-col PDF):** reuse the pattern in `TaggedPdfFixtureFactory` — author a PDFBox
`PDDocument` with two text blocks at known x-ranges separated by a wide gutter (e.g. left x∈[72,280],
right x∈[320,540]) and a known reading order ("ALPHA BETA GAMMA" left, "DELTA EPSILON ZETA" right). This makes
the detector + extractor assertions deterministic and offline (no corpus dependency in unit tests). A separate
corpus regression test (`@Tag("corpus")`, opt-in) asserts bjs-cv22 p2 reads left-column-then-right.

- **Increment 1 (smallest, highest value, lowest risk) — detector + classification only, NO behavior change.**
  - `PdfColumnDetector.detectGutter(page): Double?` (whitespace-valley histogram, local-minimum-vs-flanks metric).
  - Gate stub returning `null` when a table bbox overlaps the page (wire actual gating in inc. 3).
  - Tests: synthetic 2-col fixture → returns gutter near page center; synthetic 1-col → `null`;
    `@Tag("corpus")` asserts bjs pages 2-col, arxiv/fed-scf/nist-prose 1-col.
  - **Ships value as diagnostics; cannot regress anything (not yet wired into output).**

- **Increment 2 — `PdfColumnProseExtractor.extractColumns(file, page, gutterX): List<String>`** (left-then-right
  paragraphs via `PDFTextStripperByArea`). Test against synthetic fixture: order is ALPHA…GAMMA then DELTA…ZETA.

- **Increment 3 — wire into `PdfProseExtractor`/`PdfPipeline`:** per-page substitution on detected 2-col pages,
  gated by Tabula table overlap (now real). Reuse `DocumentBlockHandler` cleanup helpers (extract to shared util
  in this increment). Corpus assertion: bjs-cv22 reading order correct; **regression guard**: arxiv/fed-scf/all-NIST/
  rfc7230 block output byte-identical to pre-change (snapshot test) — proves single-col path untouched.

- **Increment 4 (optional) — SF-8 masthead:** rfc7230 p1 two-column author/date masthead. Likely falls out of the
  same detector if it triggers on p1; if not, defer (cosmetic, one page).

- **SF-2 (preformatted) — STAYS DEFERRED.** By the time prose reaches the pipeline, original newlines are already
  collapsed; fencing ABNF/code needs intra-line x/y run analysis the column path does not surface. Rare (rfc7230).

---

## 5. Scope recommendation

**Scope to true multi-column detection only (Option c). Not a full rewrite.**

- **Worth doing:** the bjs-cv22 class of document (50% of pages interleaved) is genuinely broken and a real
  user-facing failure for any 2-column report/newsletter. Increment 1+2+3 is a contained, well-tested medium
  effort that fixes it with provably zero impact on the 8 single-column docs (snapshot regression guard).
- **Keep deferred:** SF-2 preformatted (needs different data, rare), figure/chart-overlay splices (fed-scf
  chart axis, arxiv p13 rotated labels — these are NOT column problems and need figure-bbox exclusion, a
  separate effort), and N>2 column prose (none in corpus).
- **Do NOT** replace the Tika prose path wholesale — it would re-open G-1/G-5/G-6/G-9 for a 1-of-9 problem.

The single most important guardrail for whoever implements: **gate the detector by Tabula table presence** so the
nist-csf Framework Core 4-column table is never treated as prose columns, and **snapshot-test the single-column
docs** to prove the hybrid leaves the shipped fixes untouched.

---

## Appendix — spike provenance

Throwaway PDFBox probes (`/tmp/sf1-spike/Spike{,2..6}.java`, compiled against the Gradle-cached PDFBox 3.0.5
+ fontbox + pdfbox-io + commons-logging jars) measured: `sortByPosition` interleave, line-start-x clustering
(rejected), glyph-coverage valley detection (adopted), `PDFTextStripperByArea` region ordering, and a full-corpus
per-page 2-col scan. All spike files were deleted after measurement. No committed production code was modified.
