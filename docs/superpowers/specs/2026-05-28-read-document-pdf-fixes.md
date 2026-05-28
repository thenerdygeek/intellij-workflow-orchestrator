# read_document — PDF extraction fixes (section nav, borderless tables, image MIME)

Date: 2026-05-28
Status: in progress (TDD, subagent-driven, sequential — all in `:document` + small `:core`/`:agent` edits)

## Why (empirical root-cause evidence)

Ran the indexed pipeline (`DocumentArtifactStore.extractAndPersist`) against complex spec PDFs
(NIST SP 800-63-3, 800-63B, 800-53r5, CSF, RFC 7230) and compared to independent ground truth
(subagent read the raw PDFs). Probe: `document/src/test/kotlin/.../probe/IndexedExtractionProbe.kt`
(opt-in; no-ops without `/tmp/rd-probe/inputs`). Ground truth: `/tmp/rd-probe/ground-truth.md`.

Results (section anchors built vs real headings):

| PDF | real headings | anchors built | usable | notes |
|---|--:|--:|--|--|
| 800-63B | 56 | 3 | ~1 | 2 of 3 anchors are paragraph bodies |
| 800-63-3 | 47 | 6 | ~2 | 4 of 6 anchors are paragraph bodies |
| RFC 7230 | many | 0 | 0 | zero anchors |
| 800-53r5 | (492pp) | 378 | many/polluted | Abstract paragraph + "Quick link…" mis-tagged |
| CSF | — | 23 | mostly ok | — |

Confirmed failures:
1. **Section nav broken + silent.** Heading detection relies on Tika's unreliable font `<h*>` guess
   + `DocumentBlockHandler.NUMBERED_HEADING_BODY` (only fires for a *numbered* heading *glued* to
   its body). Standalone/unnumbered headings, the TOC (extracts as one run-on paragraph), and
   headings drawn as vector graphics get no anchor. On a miss, `DocumentArtifactStore.slice:149`
   does `offsetForSection(h) ?: 0` and `SessionDocumentArtifactService.serve()` returns
   `"Read N chars (offset=0…)"` — content identical to a top read, **no signal the section missed**,
   and no way to discover valid section names. LLM slug guesses (`fetch-product-metadata`) can't
   substring-match real heading text (`Fetch Product Metadata`).
2. **Borderless tables dropped.** `PdfTableExtractor(enableStreamMode=false)` is the default
   everywhere, so Tabula runs lattice-only. Whitespace-aligned tables (the norm in specs, e.g.
   800-63B Table 4-1 `Requirement | AAL1 | AAL2 | AAL3`) produce **only the caption** — the grid
   vanishes. Phantom guards (`isLikelyPhantomTable`) already exist.
3. **Image MIME mismatch + fragment noise.** All 8 `.jpg` outputs contained PNG bytes;
   `ImageExtractionService.save:69` derives the extension from the caller-declared MIME, and the
   `[image:…] (image/jpeg)` marker carries that wrong MIME → vision path may reject → "blank image".
   Plus 69 "images" for 80 pages because glyphs/section-numbers (e.g. a 62×39 "4.1") extract as
   tiny RGBA fragments, so `view_image` on a marker is often a meaningless sliver.

## Tasks (sequential — same module)

### Task 1 — Section navigation (Opus)
Files: `document/.../sax/DocumentBlockHandler.kt`, `core/.../model/DocumentArtifactModels.kt`
(`DocumentIndex.offsetForSection`), `core/.../model/DocumentArtifactModels.kt` (`DocumentSlice`),
`agent/.../tools/integration/SessionDocumentArtifactService.kt` (`serve`),
`agent/.../tools/integration/DocumentTool.kt` (surface).

Requirements:
- **Heading coverage:** detect standalone heading lines (short line, no terminal sentence
  punctuation, Title-Case or ALL-CAPS, typically followed by a blank line / new block) in addition
  to the existing numbered-glued split. Make the numbered split ALSO fire for a numbered heading on
  its own line (empty body). Conservative — must not turn ordinary prose paragraphs into headings.
- **Normalized matching:** `offsetForSection` must match after (a) case-fold, (b) stripping a
  leading section number (`^\d+(\.\d+)*\.?\s+`), (c) collapsing non-alphanumerics so a kebab/slug
  guess (`fetch-product-metadata`) matches `Fetch Product Metadata`. Keep exact→normalized→substring
  precedence; first match wins.
- **Explicit miss + discoverability:** when a `section=` value resolves to nothing, the tool must
  NOT silently serve offset 0 — return a clear message listing the nearest/available section anchors
  (or "no reliable section anchors; use page=N (M pages)"). Every successful read_document response
  should let the LLM discover navigable anchors (e.g. a compact "Sections: …" footer or on first read).
- TDD against repo fixture `nist-cybersecurity-framework.pdf` (numbered + unnumbered headings like
  "Executive Summary", "Appendix A: Framework Core") and a small synthetic PDF if needed.

### Task 2 — Borderless tables (Opus)
Files: `document/.../pdf/PdfTableExtractor.kt`, `document/.../pipeline/PdfPipeline.kt`,
`document/.../service/TikaDocumentExtractor.kt`.

Requirements:
- Enable stream-mode as a **per-page fallback when lattice finds nothing** (the `tables.isEmpty()`
  branch already exists) so whitespace-aligned tables are captured. Strengthen phantom guards so
  multi-column PROSE pages don't sprout phantom tables (the documented risk): e.g. require ≥2 columns
  AND ≥2 data rows AND consistent column count AND a minimum filled-cell ratio; reject single-column
  or ragged "tables".
- TDD: a borderless-table PDF must yield a pipe table with the right headers/rows; a multi-column
  prose PDF (`tabula-multi-column.pdf`) must yield NO phantom table. Add a small synthetic
  borderless-table fixture (PDFBox) if no repo fixture has one.

### Task 3 — Image MIME sniff + fragment filter (Sonnet)
Files: `document/.../service/ImageExtractionService.kt` + callers
(`poi/visitor/ImageExtractionVisitor.kt`, `pdf/PdfMetadataExtractor.kt`, `poi/XlsxTableExtractor.kt`,
`poi/PptxExtractor.kt`) and the `EmbeddedFileRef.mimeType` they set.

Requirements:
- **Sniff real format from magic bytes** (PNG 89 50 4E 47, JPEG FF D8 FF, GIF, WEBP/RIFF, BMP, TIFF)
  and use it for BOTH the file extension AND the mime that flows into `EmbeddedFileRef.mimeType` /
  the `[image:…] (mime)` marker. Declared MIME is only a fallback when sniffing is inconclusive.
- **Filter fragment images:** skip images whose decoded dimensions are below a threshold
  (e.g. width<32 OR height<32, tune against evidence) so glyph/section-number slivers don't become
  `[image:]` markers. Real figures (e.g. the 301×301 illustration) must survive.
- TDD: PNG bytes written with declared `image/jpeg` → file is `.png` and marker says `image/png`;
  a 20×20 fragment is dropped; a 301×301 image is kept.

## Verification
After each task, re-run `:document` tests. After all: re-run the probe against `/tmp/rd-probe/inputs`
and compare to `/tmp/rd-probe/ground-truth.md` (section anchor coverage up, Table 4-1 grid present,
no `.jpg`-with-PNG-bytes, fragment count down).
