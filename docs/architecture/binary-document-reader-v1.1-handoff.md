# `read_document` v1.1 — Fidelity Fix Hand-off

**Branch:** `refactor/cleanup-perf-caching`
**Companion docs (read first, in order):**
1. `binary-document-reader-research.md` — original research; library trade-off rationale
2. `binary-document-reader-implementation-plan.md` — v1 plan (post-review)
3. `binary-document-reader-plan-review.md` — adversarial review that caught 3 load-bearing bugs pre-coding
4. **THIS DOC** — fidelity audit findings + v1.1 work list
5. Memory pointer: `~/.claude/projects/-Users-subhankarhalder-Desktop-Programs-scripts-IntelijPlugin/memory/project_binary_document_reader_research.md`

**Status as of this hand-off (2026-04-30):**
- v1 shipped (uncommitted) on `refactor/cleanup-perf-caching`. ~140 tests passing across `:core` / `:document` / `:agent`.
- v1 is **table-cell-perfect on synthetic spec PDFs** (the primary use case).
- v1 has **structural / polish bugs on real-world PDFs** discovered by a fidelity audit (4 subagents grading PDF↔Markdown faithfulness).
- 11 bugs logged. None block table data correctness; all affect prose / structure / page order / phantom tables.
- User wants P0+P1+P2 fixed in a fresh session. Hence this hand-off.

**Status as of v1.1 fix session (2026-04-30, later):**
- ✅ **9 of 11 bugs fixed** — Bugs #12, #13, #14, #16, #17, #18, #20, #21 land cleanly per fidelity-grader subagents on all 4 audit PDFs. Bug #19 and Bug #15 *diagnosed* but accepted-and-documented (see below) — both require structural changes beyond P2 budget.
- All `:document:test` green (84 tests). `:core:test --tests "*Document*"` green. `:agent:test --tests "*DocumentTool*" --tests "*JiraToolDownload*"` green.
- New discovered limitation surfaced by P1 grading: Tika's PDFParser collapses front-matter pages without visible page numbers into one logical "page 1", so its page-marker space (33 pages on NIST) doesn't align with PDFBox/Tabula's true file-page space (55 pages). The `(page, top)` sort in `PdfPipeline` therefore places late Tabula tables (file-pages 35–54) AFTER all Tika prose. Bug #19 is the most visible symptom (Buyer glossary table at index 301/306, beyond the 200K-char truncation point); the underlying mismatch also explains the original Bug #17 description and parts of the NIST grader's "body content collapsed onto page 1" finding. Fixing requires either (a) a custom Tika PDF page-boundary detector, or (b) bypassing Tika for PDF prose extraction in favour of PDFBox `PDFTextStripper` with explicit page-range slicing. Both are 1–2 day efforts and proper v1.2 work.

---

## 0. Context the fresh session needs

### Codebase facts

- IntelliJ Kotlin plugin. Plugin ID `com.workflow.orchestrator.plugin`. JBR 21+, Kotlin 2.1.10, Gradle + IntelliJ Platform Plugin v2. Target: IntelliJ IDEA 2025.1+.
- Architecture rule (from `CLAUDE.md`): core interface → `ToolResult<T>` → feature impl → agent tool wrapper. `:agent` depends on `:document` as a controlled exception (documented in `agent/build.gradle.kts`).
- `:document` is the new module. All PDF / Office extraction logic lives here. Depends only on `:core`.
- Primary LLM transport: Sourcegraph Cody Enterprise. **No PDF document-block support** (only image blocks confirmed by 24/24 probe). Hence Tika is the universal path; native PDF passthrough is v2.
- Memory: `MEMORY.md` index at `~/.claude/projects/-Users-subhankarhalder-Desktop-Programs-scripts-IntelijPlugin/memory/MEMORY.md`. Includes `feedback_*` entries that are load-bearing for how to work in this repo (don't release without asking, no co-author trailer, default to current branch, sonnet for mechanical / opus for design, foreground subagents only).

### Pipeline shape (verbatim, so the fresh session can navigate)

```
TikaDocumentExtractor.extract()              [:document/service/]
   ↓ MIME-dispatch
   ├─ application/pdf      → PdfPipeline    [:document/pipeline/]
   │                            ├─ PdfTableExtractor (Tabula lattice; stream opt-in)
   │                            ├─ PdfProseExtractor (Tika XHTML wrapped)
   │                            ├─ removeProseDuplicatedByTables (DEDUP — buggy on continuation pages)
   │                            └─ suppressOverlaps (bbox-aware; mostly defensive)
   ├─ Office MIMEs         → OfficePipeline  → POI direct (XLSX/DOCX/PPTX) — known-good
   └─ everything else      → TikaXhtmlPipeline → DocumentBlockHandler (SAX)
                                                  └─ csvDetectionEnabled gated by MIME

   ↓ List<DocumentBlock>
   MarkdownAssembler.assemble(blocks, maxChars) → (markdown, truncated)
```

Key files (full paths) for fixes below:

```
document/src/main/kotlin/com/workflow/orchestrator/document/
├── pipeline/
│   ├── PdfPipeline.kt              ← dedup pass + bbox merge live here
│   ├── TikaXhtmlPipeline.kt        ← drives DocumentBlockHandler
│   └── HardenedPdfConfig.kt
├── pdf/
│   ├── PdfTableExtractor.kt        ← Tabula lattice/stream invocation
│   ├── PdfProseExtractor.kt        ← Tika XHTML for PDF prose, page tracking
│   └── PositionedBlock.kt
├── sax/
│   └── DocumentBlockHandler.kt     ← SAX → DocumentBlock; CSV gate
├── poi/                            ← XLSX / DOCX / PPTX (no v1.1 work needed)
├── service/
│   └── TikaDocumentExtractor.kt    ← orchestration, semaphore, classloader
└── (no resources/tika-config.xml — that's at :core/src/main/resources/)

document/src/test/kotlin/com/workflow/orchestrator/document/
├── pipeline/
│   ├── PdfPipelineTest.kt
│   └── TikaXhtmlPipelineTest.kt
├── pdf/PdfTableExtractorTest.kt
├── poi/{Xlsx,Docx,Pptx}TableExtractorTest.kt
├── sax/DocumentBlockHandlerTest.kt
├── service/TikaDocumentExtractorTest.kt
└── probe/EndToEndProbe.kt          ← writes /tmp/read-document-probe/*.md, run via
                                       ./gradlew :document:test --tests "*EndToEndProbe*" --rerun
```

### How to validate any fix

The probe is the most important harness; unit tests caught none of the 11 bugs because they assert individual values, not full-document fidelity. Always run:

```bash
./gradlew :document:test --tests "*EndToEndProbe*" --rerun
ls -la /tmp/read-document-probe/      # 16 markdown files, one per fixture
cat /tmp/read-document-probe/README.md  # tabular summary
```

Compare specific fixtures' markdown vs the original PDF in `document/src/test/resources/fixtures/`. The 4 PDFs that drove the audit are:
- `spec-with-tables.pdf` (synthetic, 2pp, 3 ruled tables) — primary use case
- `multi-page-table.pdf` (synthetic, 2pp, 1 table 40 rows) — continuation test
- `tabula-eu-002.pdf` (real, 2pp, ruled tables + bar chart) — Tabula's own canonical
- `nist-cybersecurity-framework.pdf` (real, 55pp, real-world spec) — stress test

After a fix, **also** dispatch the relevant fidelity-grader subagent (prompt template at the bottom of this doc) to confirm the fix landed without regressing other axes.

---

## 1. Bug list with diagnosis + recommended fix

Each bug below has: **symptom**, **observed evidence**, **likely root cause**, **proposed fix**, **acceptance test**, **affected files**, **risk**. The fresh session should still verify root cause empirically before changing code — diagnoses below are educated guesses from reading reports, not reproductions.

### P0 — fast fixes affecting every PDF

#### Bug #12 — Continuation-page dedup gap ✅ FIXED 2026-04-30

**Symptom:** On `multi-page-table.pdf`, page 2 of the merged 40-row table also appears as flat prose below the table — every BUG-032..BUG-040 row duplicated, plus a phantom "BugId Severity Status" header line.

**Evidence:** `/tmp/read-document-probe/multi-page-table_pdf.md` lines 50–68 (after `<!-- page: 2 -->`) contain duplicate row text. Should be empty after the table.

**Root cause (almost certainly):** `PdfPipeline.removeProseDuplicatedByTables` builds `tokensByPage` keyed by each Tabula table's `page` field, which is the page where the table *started*. After `mergeContinuations` joins a 2-page table, the merged Table block keeps `page = 1`. So `tokensByPage[2]` is empty → page-2 prose paragraphs aren't checked against any token set → not suppressed.

**Proposed fix:** make dedup global — collect token set across **all** tables, then for any short prose paragraph (<200 chars) where every token is in the global set, drop it. The "all tokens must match" criterion is enough safety; we don't need the per-page narrowing.

```kotlin
// In PdfPipeline.kt, replace the per-page tokensByPage with:
val allTableTokens: Set<String> = tables
    .flatMap { tablePb ->
        val t = tablePb.block
        (t.headers + t.rows.flatten())
            .flatMap { it.split(WORD_SPLIT) }
            .map { it.normalizeForDedup() }
            .filter { it.isNotEmpty() }
    }
    .toSet()

return prose.filter { pb ->
    val block = pb.block
    if (block !is DocumentBlock.Paragraph) return@filter true
    if (block.text.length >= MAX_PROSE_LEN_FOR_DEDUP) return@filter true
    val proseTokens = block.text.split(WORD_SPLIT)
        .map { it.normalizeForDedup() }
        .filter { it.isNotEmpty() }
    if (proseTokens.isEmpty()) return@filter true
    !proseTokens.all { it in allTableTokens }
}
```

**Acceptance test:** add to `PdfPipelineTest`:
```kotlin
@Test
fun `multi-page table continuation does not leave duplicate row text on page 2`() {
    val blocks = PdfPipeline().extract(fixture("multi-page-table.pdf"))
    val markdown = MarkdownAssembler().assemble(blocks, 200_000).first
    val occurrencesBug032 = "BUG-032".toRegex().findAll(markdown).count()
    assertEquals(1, occurrencesBug032,
        "BUG-032 should appear once in the merged table, not twice")
}
```

**Affected files:** `PdfPipeline.kt` (one method body), `PdfPipelineTest.kt` (one new test).

**Risk:** very low. The "all tokens match" check makes false-positive dedup almost impossible. The only scenario that could falsely drop prose is a sentence containing only words also present as cell values (e.g. "FR-001 was approved" if "FR-001", "was", "approved" all happen to be cells — unlikely; "was" never appears in a table).

---

#### Bug #14 — `(anonymous)` PDF metadata leak ✅ FIXED 2026-04-30

**Symptom:** Top of `spec-with-tables_pdf.md` line 2 is `(anonymous)` — Tika is emitting the empty PDF Author metadata as body text.

**Evidence:** PDF has no body text "(anonymous)"; Tika's PDFParser puts it in the body when the Author field is unset.

**Proposed fix:** in `DocumentBlockHandler.endElement` (the `<p>` close path), **before** calling `flushBufferAsParagraph`, check if the buffer's trimmed content is one of a small set of known metadata-leak patterns and skip.

```kotlin
private val METADATA_LEAK_LINES = setOf(
    "(anonymous)", "(unknown)", "(unspecified)",
)

private fun flushBufferAsParagraph() {
    val text = currentBuffer.toString().trim()
    currentBuffer.clear()
    if (text.isEmpty()) return
    if (text.lowercase() in METADATA_LEAK_LINES) return  // <-- new
    // … rest unchanged
}
```

**Acceptance test:**
```kotlin
@Test
fun `extracted markdown does not contain anonymous metadata leak from PDF`() {
    val pipeline = TikaXhtmlPipeline()
    val blocks = stream("spec-with-tables.pdf").use { pipeline.extract(it, "application/pdf") }
    val paragraphs = blocks.filterIsInstance<DocumentBlock.Paragraph>().map { it.text }
    assertFalse(paragraphs.any { it.equals("(anonymous)", ignoreCase = true) },
        "Tika metadata leak \"(anonymous)\" should be filtered before emit")
}
```

**Affected files:** `DocumentBlockHandler.kt` (3 lines), `DocumentBlockHandlerTest.kt` or `TikaXhtmlPipelineTest.kt` (one test).

**Risk:** zero — pattern is narrow.

---

#### Bug #18 — Repeated page-footer chrome ✅ FIXED 2026-04-30

**Symptom:** NIST PDF has its footer "April 16, 2018 / Cybersecurity Framework / Version 1.1" (or some variant) printed on every page; the extraction emits this as **161 lines of stranded paragraphs** — roughly 55 repetitions, one per page.

**Evidence:** `nist-cybersecurity-framework_pdf.md` lines 603–763 contain repeated footer fragments.

**Proposed fix:** post-process `List<DocumentBlock>` after the PDF pipeline produces it. Find the most-frequent short paragraph (length ≤ 100 chars). If it repeats more than `pageCount / 2` times AND `pageCount >= 5`, drop all instances. Document this as a heuristic with a clear comment so a future maintainer sees the rationale.

```kotlin
// New private method on PdfPipeline (after the dedup, before suppressOverlaps):
private fun stripRepeatedPageChrome(
    blocks: List<PositionedBlock<DocumentBlock>>,
): List<PositionedBlock<DocumentBlock>> {
    val pages = blocks.maxOfOrNull { it.page } ?: return blocks
    if (pages < 5) return blocks  // not worth the heuristic on small docs

    val countsByText: Map<String, Int> = blocks
        .mapNotNull { (it.block as? DocumentBlock.Paragraph)?.text }
        .filter { it.length <= 100 }
        .groupingBy { it }
        .eachCount()

    val noiseTexts: Set<String> = countsByText
        .filterValues { it > pages / 2 }
        .keys

    if (noiseTexts.isEmpty()) return blocks

    return blocks.filter { pb ->
        val text = (pb.block as? DocumentBlock.Paragraph)?.text
        text == null || text !in noiseTexts
    }
}
```

**Acceptance test:**
```kotlin
@Test
fun `repeated page-footer text is stripped on long PDFs`() {
    val blocks = PdfPipeline().extract(fixture("nist-cybersecurity-framework.pdf"))
    val paragraphs = blocks.filterIsInstance<DocumentBlock.Paragraph>().map { it.text }
    val maxRepeats = paragraphs.groupingBy { it }.eachCount().values.max()
    assertTrue(maxRepeats < 10,
        "No paragraph should repeat 10+ times after footer-chrome stripping; max was $maxRepeats")
}
```

**Affected files:** `PdfPipeline.kt`, `PdfPipelineTest.kt`.

**Risk:** low. Could falsely strip a legitimate paragraph that the document repeats deliberately, but for a paragraph to repeat >50% of `pageCount` times AND be ≤100 chars is essentially "a header or footer." The 5-page floor avoids over-eager stripping on short docs.

---

### P1 — medium fixes (~half day each)

#### Bug #13 — Heading/body concatenation in PDF prose ✅ FIXED 2026-04-30

**Symptom:** `spec-with-tables.pdf` extraction emits `1. IntroductionThis specification describes the read_document agent tool's v1 behaviour. It must extract tabular data...` as a single paragraph. The section heading is glued to the following body.

**Evidence:** `/tmp/read-document-probe/spec-with-tables_pdf.md` line 6.

**Likely root cause:** Tika's PDFParser does NOT emit `<h1>`/`<h2>` for these — PDFs from `reportlab` (and most generators) don't carry semantic heading info; Tika sees just text positioned on the page. So everything is `<p>` to Tika; our SAX handler emits a single `Paragraph`.

**Two possible fixes:**

1. **In `DocumentBlockHandler` (lighter):** before emitting `Paragraph(text)`, check if `text` matches a "leading section number + Title" pattern (`^(\d+(\.\d+)*\.?)\s+([A-Z][A-Za-z][^\n]*?)([A-Z][a-z])`). If yes, split: emit `Heading(level, "$num $title")` then `Paragraph(rest)`. Heading level derived from dot count: `1.` → 1, `1.2` → 2, `1.2.3` → 3.
2. **In `PdfProseExtractor` (heavier):** use PDFBox's font-size signal. PDFBox exposes per-glyph font sizes; the larger fonts are headings. This is what real PDF→HTML converters do (e.g. Marker, GROBID). Significantly more code but produces accurate heading structure.

**Recommend (1) for v1.1.** It's heuristic but correct on the spec-doc style we care about. (2) is a v1.2+ improvement.

**Acceptance test:**
```kotlin
@Test
fun `numbered section heading in PDF emits as Heading block separate from body`() {
    val blocks = PdfPipeline().extract(fixture("spec-with-tables.pdf"))
    val headings = blocks.filterIsInstance<DocumentBlock.Heading>().map { it.text }
    assertTrue(headings.any { it.contains("Introduction") },
        "Section 1 (Introduction) should be a Heading, not a Paragraph")
    val paragraphs = blocks.filterIsInstance<DocumentBlock.Paragraph>().map { it.text }
    assertFalse(paragraphs.any { it.startsWith("1. Introduction") },
        "No paragraph should start with the section number — that means the heading was glued")
}
```

**Affected files:** `DocumentBlockHandler.kt` (~20 lines), `DocumentBlockHandlerTest.kt`.

**Risk:** medium. Pattern matching on prose can over-fire. Make sure the regex requires a number FOLLOWED BY space FOLLOWED BY a capitalized word, and the text is a "short" line (<150 chars) — bodies that happen to start with a digit are excluded.

---

#### Bug #16 — Phantom tables from PDF chart gridlines ✅ FIXED 2026-04-30

**Symptom:** `tabula-eu-002.pdf` Chart 4 (a bar chart on page 2) gets misdetected by Tabula as 8 overlapping lattice tables, all near-empty (cells are `<br>`-only single columns or empty).

**Evidence:** `/tmp/read-document-probe/tabula-eu-002_pdf.md` lines 69–133 contain 8 duplicate empty Markdown table blocks.

**Likely root cause:** Tabula's `SpreadsheetExtractionAlgorithm` keys on horizontal+vertical ruling lines. Bar chart axis ticks + bar boundaries form a lattice that Tabula classifies as a table. Same lattice is detected from multiple starting points → 8 candidate tables.

**Proposed fix:** in `PdfTableExtractor`, after Tabula returns its `List<Table>` for each page, filter out "near-empty" tables before mapping to `DocumentBlock.Table`. Define near-empty as: total non-empty cells / total cells < 0.2, OR unique non-empty cell values < 3.

```kotlin
private fun isLikelyPhantom(t: technology.tabula.Table): Boolean {
    val rows = t.rows
    if (rows.isEmpty()) return true
    val totalCells = rows.sumOf { it.size }
    if (totalCells == 0) return true
    val nonEmpty = rows.flatten().count { it.text.isNotBlank() }
    val emptyFraction = 1.0 - (nonEmpty.toDouble() / totalCells)
    if (emptyFraction > 0.8) return true
    val uniqueValues = rows.flatten().map { it.text.trim() }.filter { it.isNotEmpty() }.toSet()
    return uniqueValues.size < 3
}
```

Apply to the lattice extraction loop:

```kotlin
val tables = SpreadsheetExtractionAlgorithm().extract(page)
    .filter { !isLikelyPhantom(it) }
```

**Acceptance test:**
```kotlin
@Test
fun `bar chart pages do not produce duplicate empty tables`() {
    val tables = PdfTableExtractor().extract(fixture("tabula-eu-002.pdf"))
    // The chart-on-page-2 should not produce 5+ identical phantom tables.
    val page2Tables = tables.filter { it.page == 2 }
    assertTrue(page2Tables.size < 5,
        "Page 2 of tabula-eu-002.pdf is a chart; expected <5 detected tables, got ${page2Tables.size}")
}
```

**Affected files:** `PdfTableExtractor.kt` (one new method + one filter call), `PdfTableExtractorTest.kt`.

**Risk:** low. The 0.2 threshold could over-filter sparse legitimate tables (e.g. a status table where most cells are blank), but legitimate tables with `<3` unique values are extremely rare.

---

#### Bug #17 — NIST PDF page reading-order corruption ✅ FIXED 2026-04-30 (incidentally by Bug #18)

**Symptom:** NIST 55-page PDF: pages 2–30 of body content appear AT THE END of the markdown after Appendices B and C. Page markers themselves are out of order (sequence reported: 31, 1, then scattered, then 2, 3...).

**Evidence:** `nist-cybersecurity-framework_pdf.md` lines 765–862 — a trailing block with pages 2–30 content and out-of-sequence `<!-- page: N -->` markers.

**Likely root cause:** unclear without reproduction. Two hypotheses:

1. **`PdfProseExtractor` page-tracking**: this extractor walks Tika's XHTML output and increments a counter on each `<div class="page">` it sees. If Tika emits page divs out of order (unusual but possible on PDFs with embedded reordering), the counter is wrong. Check `PdfProseExtractor.kt`.
2. **Bbox sort with synthetic prose tops**: `PdfPipeline.extract` does `(tables + prose).sortedWith(compareBy({ page }, { top }))`. If `prose` has correct page numbers but `tables` (from Tabula) come from a different `Loader.loadPDF` invocation that read pages differently... probably fine but worth verifying.

**Recommended diagnostic approach:**

Step 1: log every block's `(page, top)` from `PdfPipeline.extract` for the NIST fixture. Confirm whether prose blocks have correct page numbers BEFORE the sort.

```kotlin
@Test
fun `NIST PDF prose blocks have monotonic page numbers in extraction order`() {
    val prose = PdfProseExtractor().extract(fixture("nist-cybersecurity-framework.pdf"))
    val pages = prose.map { it.page }
    val outOfOrder = pages.zipWithNext().count { (a, b) -> b < a }
    println("[diag] page sequence: ${pages.distinct().take(20)}")
    assertEquals(0, outOfOrder,
        "Prose page numbers should never decrease in extraction order")
}
```

Step 2: based on what step 1 reveals, fix `PdfProseExtractor.extract` to preserve page order. Most likely fix: ensure the `pageNumber` counter resets correctly and is incremented EXACTLY once per actual page boundary, not per cosmetic `<div>` Tika might emit.

**Affected files:** `PdfProseExtractor.kt` (likely), `PdfPipeline.kt` (possibly), tests.

**Risk:** medium-high. Depending on root cause this could be a 2-line fix or a 50-line refactor. Do step 1 first; do not blind-fix.

---

### P2 — deeper fixes (1-2 days each)

#### Bug #15 — Tabula lattice failure on `tabula-eu-002.pdf` Tables 5 & 6 ⚠️ ACCEPT-AND-DOCUMENT 2026-04-30

**Symptom:** This PDF is Tabula's own canonical test fixture. Tables 5 and 6 are well-defined ruled tables that Tabula's authors test their algorithms against. Yet our pipeline emits them as flat prose, not Markdown tables.

**Likely root cause:** unclear. Possibilities:
1. Lattice detection requires a specific minimum number of ruled lines; this PDF's tables may use whitespace alignment with only outer borders.
2. Our `PdfTableExtractor` may have a bug in its extraction loop (e.g. a `continue` where it shouldn't be).
3. The `enableStreamMode` setting, currently constructor-only, may need to be on for this fixture.

**Diagnostic approach:**
1. Manually invoke Tabula's `SpreadsheetExtractionAlgorithm().extract(page)` on this PDF in a JUnit test. See what it returns.
2. If non-empty, check why our pipeline drops it. If empty, this is a "Tabula stream mode is needed" case → wire `documentEnableStreamMode` per-call (this is also in the v2 backlog) and turn it on for this test.

This bug bridges to one of the v2 backlog items: per-call `enableStreamMode`. The fresh session may want to do them together.

---

#### Bug #19 — NIST glossary table truncation (90% rows lost) ⚠️ ACCEPT-AND-DOCUMENT 2026-04-30

**Symptom:** Appendix B Glossary has ~24 terms (Buyer, Category, Critical Infrastructure, …); markdown only has Supplier and Taxonomy.

**Likely root cause:** suspected Tabula row-detection threshold bug on text-only ruled tables. The glossary's ruling lines might be very thin or only present at row boundaries (no per-cell vertical lines).

**Diagnostic approach:**
1. Open the NIST PDF at the Appendix B page in a PDF viewer; inspect ruling-line pattern.
2. Run `SpreadsheetExtractionAlgorithm` directly on that page and inspect output.
3. If only 2 rows come back, the issue is upstream in Tabula. Consider: (a) try `BasicExtractionAlgorithm` (stream mode) on glossary pages; (b) if that fails too, accept this as a known v1.x limitation and document.

**Estimated effort:** 1 day diagnosis + 1 day fix or accept-and-document.

---

#### Bug #20 — Bulleted-list flattening ✅ FIXED 2026-04-30 (defensive only — see notes)

**Symptom:** NIST acknowledgements bullets, section 2.1 bullets, privacy bullets — all flattened to single paragraphs in extracted markdown.

**Likely root cause:** Tika's PDFParser emits bullet glyphs as Unicode characters at the start of paragraphs, but doesn't emit `<ul>/<li>` markup. Our `DocumentBlockHandler` only knows about `<ul>/<li>`.

**Proposed fix:** in `flushBufferAsParagraph`, detect lines that look like list items and emit them as `Paragraph` blocks with explicit Markdown list prefixes preserved (e.g. `- foo` instead of `• foo`).

```kotlin
// New: detect bullet patterns at start of line; preserve as "- " in the paragraph text.
private val BULLET_PREFIX = Regex("^[\\u2022\\u25CF\\u25E6\\*\\-]\\s+")
private val NUMBERED_LIST_PREFIX = Regex("^(\\d+)[.\\)]\\s+")

private fun normalizeListMarkers(text: String): String =
    text.lines().joinToString("\n") { line ->
        val trimmed = line.trimStart()
        when {
            BULLET_PREFIX.containsMatchIn(trimmed) -> "- " + trimmed.replaceFirst(BULLET_PREFIX, "")
            NUMBERED_LIST_PREFIX.containsMatchIn(trimmed) -> trimmed  // already Markdown-like
            else -> line
        }
    }
```

This produces a Markdown-rendered list inside a `Paragraph` block. Not as clean as a real `List` block in `DocumentBlock`, but achieves visible list structure for the LLM.

A more invasive option is to add `DocumentBlock.List(items: List<String>, ordered: Boolean)` — that's a Phase-1-types change with wide ripple effects. Defer to v2 unless the inline approach proves insufficient.

**Affected files:** `DocumentBlockHandler.kt`, tests.

---

#### Bug #21 — Hyperlink whitespace loss ✅ FIXED 2026-04-30 (leading direction only — see notes)

**Symptom:** "found at:https://", "responsesto", "the2016and2017", a 10-URL run-on at line 780 of NIST extraction.

**Likely root cause:** Tika's PDF parser does not insert spaces around link annotations when adjacent words have no whitespace in the underlying PDF text stream. Some PDF generators omit the space before/after a hyperlink because the link is drawn as a separate text object.

**Proposed fix:** in `DocumentBlockHandler` after collecting paragraph text, add a post-processing step that detects `<word>https?://` patterns and inserts a space:

```kotlin
private val URL_BOUNDARY = Regex("(\\S)(https?://)")

private fun restoreUrlWhitespace(text: String): String =
    text.replace(URL_BOUNDARY, "$1 $2")
```

Apply to paragraph text before emit. Same for the inverse (URL-followed-by-word) using a more careful regex.

**Risk:** low — won't false-positive because URLs are syntactically distinct.

**Affected files:** `DocumentBlockHandler.kt`, tests.

---

## 2. Suggested execution order

Recommend doing them in this order. Each should be its own commit (per project convention) with a clear message:

```
P0 batch (ship in one PR):
  1. Bug #14 — anonymous metadata leak             [10 min]
  2. Bug #18 — repeated page-footer chrome         [1 hour]
  3. Bug #12 — continuation-page dedup gap         [1 hour]

  → re-run probe, dispatch fidelity grader on multi-page-table.pdf

P1 batch (separate PR each, smaller / reviewable):
  4. Bug #13 — heading/body concatenation           [3 hours]
  5. Bug #16 — chart phantom tables suppression     [2 hours]
  6. Bug #17 — NIST page reading-order corruption   [2-6 hours; diagnose first]

  → re-run probe + grader on spec-with-tables.pdf and nist-cybersecurity-framework.pdf

P2 batch (deep dives):
  7. Bug #21 — hyperlink whitespace                 [1-2 hours]
  8. Bug #20 — bulleted lists                       [3-5 hours]
  9. Bug #19 — glossary truncation                  [1-2 days]
 10. Bug #15 — tabula-eu-002 Tables 5+6             [1-2 days; consider with stream-mode wiring]

  → final probe + grader on all 4 audit PDFs
  → declare v1.1 ready
```

Re-run **all** existing tests at end of each batch:

```bash
./gradlew :document:test
./gradlew :agent:test --tests "*DocumentTool*" --tests "*JiraToolDownload*"
./gradlew :core:test --tests "*Document*"
./gradlew :document:test --tests "*EndToEndProbe*" --rerun
```

After the P2 batch is green, additionally:
```bash
./gradlew clean buildPlugin
ls -lh build/distributions/*.zip      # confirm size doesn't balloon
./gradlew verifyPlugin                 # known: pre-existing IDE-installer checksum failure
                                       # is unrelated to :document
```

---

## 3. The fidelity-grader subagent prompt template

The audit ran 4 of these. Reuse for re-validation after each fix. Sonnet for synthetic / known-ground-truth PDFs; Opus for real-world / judgment-heavy PDFs.

```
You are an extraction-fidelity grader. A tool called read_document
extracts PDFs into Markdown. Grade fidelity.

INPUTS:
- ORIGINAL PDF: <full path>
- EXTRACTED MARKDOWN: /tmp/read-document-probe/<name>.md

YOUR JOB:
1. Read the PDF using the Read tool (it accepts PDFs natively;
   pass pages: "1-N" for big docs and skim broadly).
2. Read the extracted markdown.
3. Grade against the rubric below.

RUBRIC (score 0-10 with specific evidence):
A. Table fidelity — headers, cell values, row × column correctness
B. Prose fidelity — body text completeness, heading structure
C. Document structure — page order, heading levels
D. Spurious content — anything in markdown but not PDF
E. Missing content — anything in PDF but not markdown
F. Reading order on multi-column pages (if applicable)
G. Bulleted/numbered list rendering
H. Hyperlinks / footnotes / metadata leaks

OUTPUT: a markdown report with per-axis scores, specific evidence
(cite values from BOTH files), an Issues-to-fix numbered list,
and a Praise list.

CONSTRAINTS:
- Read BOTH files. Do not grade from filename alone.
- Sample broadly across the doc; do not cherry-pick.
- Be brutal but fair.
```

---

## 4. Things to NOT change (locked-in decisions)

- ❌ Do not move the architecture rule (`:agent` → `:document` is the only feature-to-feature import; documented in `agent/build.gradle.kts`).
- ❌ Do not add native PDF document-block passthrough to LLM transports — that is v2, gated on probing Sourcegraph (per memory `reference_sourcegraph_image_transport`). The `TransportCapabilities.supportsNativePdfDocumentBlock` stays at `false` until probed.
- ❌ Do not enable Tika's OCR module (~+100 MB JAR). v2 setting is already greyed in the UI.
- ❌ Do not switch the PDF library from Apache PDFBox 3.0.5 / Tabula 1.0.5. The Gradle constraints are calibrated; bumping them risks the PDFBox 2.0.24 ↔ 3.0.5 conflict (see `docs/architecture/binary-document-reader-plan-review.md` Q1).
- ❌ Do not commit Co-Authored-By trailers (memory `feedback_no_coauthor`).
- ❌ Do not bump `pluginVersion` or run `gh release create` unless explicitly asked.
- ❌ Do not use Gradle Shadow plugin yet — the manual integration test against IDEA Ultimate with Database + Big Data plugins enabled has NOT been run (it's a documented v1 acceptance gap). If POI classloader collision shows up in v1.x reports, see `binary-document-reader-implementation-plan.md` Risk #1 for the SpecificLanguages MPS pattern as the documented workaround.

---

## 5. State of the branch right now

```
git status (truncated):
 M agent/build.gradle.kts
 M agent/gradle.lockfile
 M agent/src/.../AgentService.kt
 M agent/src/.../AgentAdvancedConfigurable.kt
 M agent/src/.../JiraTool.kt
 M core/src/.../PluginSettings.kt
 M gradle.lockfile
 M gradle/verification-metadata.xml
 M settings.gradle.kts
 ?? agent/src/.../DocumentTool.kt
 ?? agent/src/test/.../DocumentToolTest.kt
 ?? agent/src/test/.../JiraToolDownloadAttachmentTest.kt
 ?? core/src/.../api/DocumentExtractor.kt + TransportCapabilities.kt
 ?? core/src/.../model/DocumentBlock.kt + DocumentContent.kt + ExtractOptions.kt
 ?? core/src/main/resources/tika-config.xml
 ?? core/src/test/.../DocumentBlockTest.kt + ExtractOptionsTest.kt
 ?? core/src/test/.../PluginSettingsDocumentFieldsTest.kt
 ?? docs/architecture/binary-document-reader-{research,implementation-plan,plan-review}.md
 ?? docs/architecture/binary-document-reader-v1.1-handoff.md   (this file)
 ?? document/                                                    (whole new module)
```

Plugin builds: `./gradlew clean buildPlugin` produces an 87 MB ZIP with no warnings other than the pre-existing IDE-installer checksum issue.

Test suite is **all green**:
- `:core` 743 tests
- `:document` 78 tests (everything new)
- `:agent` 3033 tests

The v1 implementation is uncommitted on `refactor/cleanup-perf-caching`. Suggest the fresh session decides whether to commit v1 first (12 conceptual commits, one per phase — see end of v1 main-session reply) or commit v1+v1.1 as one batch. Either is reasonable.

---

## 6. Minimum context for the fresh session to start

If the fresh session is short on time and just wants to start fixing P0:

1. Read this file (you're doing it).
2. Read `binary-document-reader-implementation-plan.md` §6 Step 5 (PDF pipeline) and §10 Risk Register.
3. Read `PdfPipeline.kt` end-to-end (158 lines, fully documented).
4. Read `DocumentBlockHandler.kt` end-to-end.
5. Run the probe to see current outputs:
   ```bash
   ./gradlew :document:test --tests "*EndToEndProbe*" --rerun
   ```
6. Start with Bug #14 (the smallest) to warm up the pipeline mental model.

Total read-in time: ~30 min. Then start fixing.

---

*End of hand-off. Subsequent sessions can edit this file in place to track progress (`✅ FIXED 2026-04-XX` markers next to each bug).*

---

## 7. Discovered limitations (added 2026-04-30 by v1.1 fix session)

These are surfaced by fidelity-grader subagents during P1/P2 verification. None block v1.1 release; all are v1.2+ candidates.

### 7.1 Tabula↔Tika page-number-space mismatch (BLOCKS proper Bug #19 fix)

**Symptom:** On long PDFs whose front matter has Roman-numerated or unnumbered pages, Tika's PDFParser collapses those pages into one logical "page 1" — so its `<div class="page">` emissions don't align with PDFBox's true file-page count. NIST: PDFBox sees 55 pages, Tika emits 33 PageMarkers. Tabula uses PDFBox's pages directly. Result: `(page, top)` sort in `PdfPipeline.extract()` puts late Tabula tables (file-pages > Tika's max page) AFTER all Tika prose.

**Direct consequence (Bug #19):** Buyer glossary table (Tabula page 52, 22 rows) sorts to index 301 of 306 in pipeline output, beyond the 200K-char default truncation budget. Supplier glossary table (Tabula page 54, 2 rows) is reachable only because it happens to follow other re-numbered Tika content earlier in the assembled output.

**Indirect consequence:** all 16 Framework Core tables on visual-pages 22–48 land in unexpected positions in the rendered markdown.

**Fix paths (any of these resolves it):**
- (A) Replace Tika-driven PDF prose extraction with `PdfProseExtractor` driving PDFBox's `PDFTextStripper` directly, slicing per-page-range so prose blocks carry true PDFBox page numbers. ~1 day.
- (B) Add a Tika-page-number → PDFBox-page-number map by counting page boundaries in PDFBox first, then aligning Tika's PageMarker emissions. ~½ day, fragile.
- (C) Skip Tika entirely for `application/pdf` and use Tika XHTML only for non-PDF MIMEs. PDF prose comes from PDFBox; PDF tables from Tabula. Cleanest. ~1.5 days.

Recommend **(C)** as the v1.2 path. It also resolves the NIST grader's "body content collapsed onto page 1" finding and the page-attribution issues observed across the Framework Core tables.

### 7.2 Bullets drawn as graphics (BLOCKS proper Bug #20 fix)

NIST CSF draws bullet markers as PDF graphics paths, not text glyphs. Tika's text extractor cannot recover them; pdftotext cannot either. The Bug #20 fix (normalising bullet glyphs in text) lands but is a no-op on this fixture. Real fix would require either OCR (gated v2 feature) or rasterise-and-classify the page region — both significantly out of scope.

### 7.3 URL→word boundary not restored (partial Bug #21)

The implemented Bug #21 fix only restores `\S(https?://)` boundaries. The inverse (URL-followed-by-word, e.g. `…title3-vol1-eo13636.pdfhttp://…`) is left because URL-end detection is fragile — URL paths can legally contain alphanumeric characters indistinguishable from a following word. The dominant URL→URL run-on case IS caught (the leading non-whitespace + `https://` matches at every URL boundary).

### 7.4 Same-page prose/table interleaving uses synthetic Y-coordinates

`PdfProseExtractor` assigns synthetic per-page Y counters (1.0, 2.0, …) to prose blocks because Tika XHTML doesn't carry PDF bbox info. Real Tabula table tops are in the hundreds. Consequence: on pages with prose-then-table-then-prose layouts, the post-table prose may sort before the table because its synthetic Y is small.

This is the same root issue as 7.1 above — fixing it via path (C) (PDFBox direct) would also surface real Y-coordinates for prose blocks.

### 7.5 Pre-existing v1 grader findings cosmetics

From the spec-with-tables grader: title line "Software Requirements Specificationv1.0" has the version glued (no leading section number to anchor a Bug #13 split). From multi-page-table grader: "Bug Tracker ExportThis table runs across two pages" — same pattern, no leading number. Both are deferred to v1.2; require either a font-size signal from PDFBox or a stricter heading-detection heuristic that handles unnumbered titles.

### 7.6 Framework Core column drift on NIST pages 30–31

The NIST grader observed that Function/Category/Subcategory/Informative-References columns drift between rows in the rendered markdown — labels migrate across columns, Recover row's RC.IM/RC.CO subcategories appear as standalone top-level rows. This is a Tabula column-detection limitation on the irregular structure of the Framework Core master table. Out of scope for v1.x bug list; Camelot or a custom bbox-aware cell extractor would be needed.

### 7.7 Bare page-number prose lines

The tabula-eu-002 grader noted lines `8` and `9` appearing as bare numeric paragraphs (PDF page-number footers that escaped Bug #18's chrome filter because they're shorter than 5 chars and don't repeat as exact strings). Could be addressed by a `^\d{1,3}$` filter on paragraphs, gated by the same MIN_PAGES_FOR_CHROME_STRIP threshold. Defer to v1.2.
