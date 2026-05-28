# HANDOFF — read_document PDF extraction fixes

**Date:** 2026-05-29
**Worktree (work ALL gradle here):** `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/cross-ide`
**Branch:** cross-ide delegation branch (this worktree). Changes are UNCOMMITTED in the working tree.
**Design spec:** `docs/superpowers/specs/2026-05-28-read-document-pdf-fixes.md`
**Empirical evidence:** `/tmp/rd-probe/ground-truth.md` (subagent's raw-PDF read), `/tmp/rd-probe/out/REPORT.md` + per-pdf `sections.txt`/`content.md`.

---

## The problem (user report → root-caused)

`read_document` was confusing the agent on spec PDFs. Investigated with systematic-debugging,
ran the **indexed** extraction pipeline against complex real PDFs (NIST SP 800-63-3, 800-63B,
800-53r5 @ 6MB/492pp, NIST CSF, RFC 7230) and compared against independent ground truth (a
subagent read the raw PDFs). Three confirmed, separate root causes:

1. **`section=` navigation broken + silent.** Heading anchors are built only from
   `DocumentBlock.Heading`, which came from Tika's unreliable font `<h*>` guess + a regex that
   only fired for a *numbered heading glued to its body*. Result: 800-63B built 3 anchors for 56
   real headings; RFC 7230 built 0. On a miss, `DocumentArtifactStore.slice` did
   `offsetForSection(h) ?: 0` and the tool served the document top with **no signal** — identical
   to a from-the-top read. LLM slug guesses (`fetch-product-metadata`) couldn't match real heading
   text (`Fetch Product Metadata`).
2. **Borderless tables dropped.** `PdfTableExtractor(enableStreamMode=false)` everywhere → Tabula
   lattice-only. Whitespace-aligned spec tables (e.g. 800-63B **Table 4-1** `Requirement | AAL1 |
   AAL2 | AAL3`) extracted as **only the caption** — the grid vanished.
3. **Image MIME mismatch + fragment noise.** All 8 `.jpg` outputs contained PNG bytes;
   `ImageExtractionService.save` trusts the caller-declared MIME for the extension AND the
   `[image:…] (image/jpeg)` marker → vision path can reject → "blank image". Also 69 "images" for
   80 pages because glyphs/section-numbers (a 62×39 "4.1") extract as tiny RGBA fragments.

User decisions: **fix all four** (Section nav FULL + Section-miss feedback + Borderless tables +
Image MIME/fragments). Approach: **go straight to TDD**. Standing prefs in play: subagent-driven,
**skip reviewer subagents** (implementer only), **sequential** dispatch (same module = same tree;
no parallel), foreground agents, no commits until asked, no Co-Authored-By.

---

## STATUS

### ✅ Task 1 — Section navigation — DONE & VERIFIED GREEN
Implemented by an Opus subagent via TDD. (The subagent's final report was lost to an API
ConnectionRefused at the very end, but the work landed and tests pass — verified independently.)

**Tests (verified by me, separate gradle runs):**
- `./gradlew :document:test` → **BUILD SUCCESSFUL**
- `./gradlew :core:test` → **BUILD SUCCESSFUL**
- `./gradlew :agent:test --tests "*DocumentTool*" --tests "*SessionDocumentArtifactService*"` → **BUILD SUCCESSFUL**
- ⚠️ Do NOT run `:core:test` and `:document:test` in ONE gradle invocation — fails with a Gradle
  implicit-dependency error (`:document:prepareTestSandbox` not declared as input of `:core:test`).
  This is a pre-existing build-graph quirk, NOT a test failure. Run them separately.

**What changed (all uncommitted):**
- `document/.../sax/DocumentBlockHandler.kt` (+169) — new `tryEmitStandaloneHeading()` catches
  (a) standalone numbered headings on their own line (empty body — the old split needed a body),
  and (b) standalone unnumbered Title-Case/ALL-CAPS headings ("Abstract", "Executive Summary",
  "Appendix A: …"). Conservative guards: single-line, ≤80 chars, ≤9 words, no terminal sentence
  punctuation, rejects list-item shapes, rejects tokens with digits/camelCase (table-row/version
  noise). Helpers: `isCleanHeadingWord`, `isTitleCaseOrAllCaps`, `TITLE_CASE_MINOR_WORDS`.
- `core/.../model/DocumentArtifactModels.kt` (+61) — `offsetForSection` now 3-tier: exact →
  number-stripped+alphanumeric-normalized-equal → substring (so slug/number-stripped guesses
  match). New file-private `normalizeSectionKey()`. `DocumentSlice` gains
  `availableSections: List<String>` + `sectionMatched: Boolean?` (both defaulted — backward compat).
- `document/.../service/DocumentArtifactStore.kt` (+27) — `slice()` populates `sectionMatched`
  (explicit hit/miss, never confuses fallback-0 with a real heading at 0) + `availableSections`
  (capped at `MAX_AVAILABLE_SECTIONS=30`).
- `agent/.../tools/integration/DocumentTool.kt` (+63) — renders a "Section '…' not found. Available
  sections: …" banner on a miss (or "no reliable anchors; navigate with page=N" when none), and a
  token-frugal `[Sections: a | b | c]` discoverability hint on normal reads with `remaining>0`.
  Updated `section` param docs. NOTE: `SessionDocumentArtifactService.serve()` (main) was NOT
  touched — the new `DocumentSlice` fields flow through its `.copy(content=…)` unchanged; only its
  TEST got +28 lines.
- Tests added/updated: `core/.../DocumentIndexTest.kt` (+84), `document/.../DocumentArtifactStoreSliceTest.kt`
  (+50), `document/.../sax/DocumentBlockHandlerHeadingDetectionTest.kt` (NEW),
  `document/.../pipeline_pdf/PdfRealExtractionGroundTruthTest.kt` (+33, asserts CSF anchors),
  `document/.../pipeline_pdf/PdfPipelineTest.kt` (+19), `agent/.../DocumentToolTest.kt` (+109),
  `agent/.../SessionDocumentArtifactServiceTest.kt` (+28).
- **Scope spillover (intentional, by the subagent):** `document/.../pipeline/PdfPipeline.kt` (+22/−10)
  — the new heading promotion could turn a Tika-leaked flat table-header row ("Metric Bound Measured")
  into a false `Heading`. The subagent extended the existing prose-vs-table dedup to ALSO drop
  `Heading` blocks whose tokens are all present in table cells, so false anchors never pollute the
  index. Reasonable; confirm it doesn't over-drop real headings during final review.

### ✅ Task 2 — Borderless tables — DONE & VERIFIED GREEN (2026-05-29, Opus subagent)
Implemented via TDD. **What changed (all uncommitted):**
- `document/.../pdf/PdfTableExtractor.kt` (~55) — reordered the extract loop so the lattice phantom
  filter (`isLikelyPhantomTable`) runs BEFORE the stream-fallback decision (so `tables.isEmpty()` is
  true on a chart-only page and the fallback can fire); added per-page stream fallback
  (`BasicExtractionAlgorithm`) gated behind `enableStreamMode`; new `isStreamPhantomTable()` applied
  ONLY to stream candidates (lattice behavior unchanged) — rejects unless ≥2 cols, ≥2 data rows,
  rectangular rows, **no ragged tail** (each row fills ≥ ceil(cols×0.7)), and global filled-ratio ≥0.7.
  The ragged-tail check is the decisive prose-vs-grid discriminator.
- `document/.../pipeline/PdfPipeline.kt` (2 lines) — constructor default `enableStreamMode = true` + KDoc.
  Task 1's `removeProseDuplicatedByTables` Heading-dedup is fully intact (confirmed via diff).
- `document/.../service/TikaDocumentExtractor.kt` — no change needed (defaults `pdfPipeline = PdfPipeline()`).
- New test `document/.../pdf/PdfTableExtractorStreamModeTest.kt` (~170, 3 tests): borderless dropped by
  lattice-only / recovered by stream with correct headers `[Req,AAL1,AAL2,AAL3]`+3 rows / multi-column
  prose → 0 phantom tables. Synthetic borderless fixture built in-memory via PDFBox; phantom case uses
  existing `tabula-multi-column.pdf`.
- **Verified:** full `:document:test` BUILD SUCCESSFUL; probe `800-63-3 content.md` now contains AAL
  pipe-grids that were previously caption-only.
- **Concern (carry forward):** the 0.7 ratio + ragged-tail rule is fixture-validated, not field-validated;
  a genuinely sparse real borderless table (compatibility matrix with many empty cells) could be
  over-dropped. Validate on a real spec corpus (runIde smoke) before trusting in production.

### ✅ Task 3 — Image MIME sniff + fragment filter — DONE & VERIFIED GREEN (2026-05-29, Sonnet subagent)
Implemented via TDD. **What changed (all uncommitted):**
- `document/.../service/ImageExtractionService.kt` (+160) — pure companion `sniffImageMime(bytes): String?`
  (PNG/JPEG/GIF87a/GIF89a/WEBP-RIFF/BMP/TIFF-LE+BE, null on unknown); new `saveImage(...)` returning
  `data class SaveResult(path, mimeType)` — sniffs MIME (declared = fallback), derives extension from
  sniffed type, decodes dims via `ImageIO.read()` and returns null if width<32 OR height<32 (fragment),
  saves anyway if dims undecodable (SVG/OLE/corrupt). Old `save()` retained for non-image attachments.
- All 4 image callers migrated to `saveImage()` (null → skip): `poi/visitor/ImageExtractionVisitor.kt`,
  `pdf/PdfMetadataExtractor.kt` (XObject path), `poi/XlsxTableExtractor.kt`, `poi/PptxExtractor.kt`.
  PdfMetadataExtractor's embedded-ATTACHMENTS path keeps `save()` + `sniffImageMime()` (dimension filter
  must NOT suppress non-image attachments like PDFs/ZIPs) — intentional, documented.
- New test `document/.../service/ImageMimeSniffAndFilterTest.kt` (+290, 21 tests): all 8 magic-byte sigs,
  null on unknown, real ImageIO PNG/JPEG, PNG-declared-jpeg→`.png`+`image/png`, 20×20 dropped, 301×301
  kept, 32×32 boundary kept, 200×20 / 20×200 dropped, declared-MIME fallback, idempotency.
- Test fixtures bumped 16×16→32×32 in DocxExtractorFormatGapsTest / XlsxExtractorFormatGapsTest /
  PptxExtractorFormatGapsTest / PdfPipelineTest so legit fixtures survive the fragment filter.
- **Verified:** `:document:test` 304 tests BUILD SUCCESSFUL; probe re-run wrote 16 images, ALL `.png`
  with PNG magic bytes (zero `.jpg`-with-PNG-bytes), same basenames as the prior run's wrong `.jpg`s —
  i.e. the sniff corrected the extension/MIME; image count down (fragment filter working).
- **Note:** 32px floor KEEPS the 62×39 section-number case (spec said "do NOT set it so high you'd drop
  62×39 if uncertain") — only obvious slivers (20×20) drop. A stricter threshold is a future tuning pass.

---

## ✅ ALL THREE TASKS DONE & VERIFIED (2026-05-29). Final gate green.
- `:document:test` (incl. probe) BUILD SUCCESSFUL · `:core:test` SUCCESSFUL · `:agent` DocumentTool/
  SessionDocumentArtifactService tests SUCCESSFUL · `verifyPlugin` SUCCESSFUL (IU-251/252/253).
- Probe vs ground truth: section anchors 800-63B 3→131, 800-63-3 6→83, RFC7230 0→95, 800-53r5 378→456,
  CSF 23→33; exact-hit ✅ + slug-hit ✅ everywhere. `natural-hit ❌` is a PROBE ARTIFACT, not a gap —
  the probe's chosen realKey is now an UNNUMBERED first anchor (Abstract/Exec-Summary, thanks to Task 1),
  so its number-strip is a no-op and its own code returns false (`if (natural == it) false`). Number-strip
  matching itself is unit-tested green in `DocumentIndexTest`.
- **Still UNCOMMITTED** — awaiting user's commit decision. Plan: `tdd/SKILL.md` (+18, unrelated RED-reason
  guidance) gets its OWN commit, separate from the PDF-fix changeset (user decision 2026-05-29).
- Two unrelated untracked specs in tree (`2026-05-28-handover-checks-data-fix-design.md`) — leave alone.

---

## How to verify the whole thing at the end
1. Per-task: run the module test commands above (separately, never `:core`+`:document` together).
2. End-to-end: re-run the probe and diff against ground truth:
   `./gradlew :document:test --tests "*IndexedExtractionProbe*" --rerun-tasks` then read
   `/tmp/rd-probe/out/REPORT.md` (section-anchor coverage UP, slug/natural hits ✅), grep the
   per-pdf `content.md` for Table 4-1's grid (Task 2), and check
   `/var/folders/.../workflow-document-images/` has no `.jpg`-with-PNG-bytes + fewer tiny fragments (Task 3).
3. `./gradlew verifyPlugin` before any release.

## Important repo facts / gotchas
- `:agent → :core`, `:document → :core`. NEVER make `:core`/`:document` depend on `:agent`.
- `:document` tests: JUnit5 + `runBlocking`, **no MockK, no kotlinx-serialization test plugin** — keep pure.
- `:agent` tests: JUnit5 + MockK.
- NEVER `runBlocking` in `main/` sources (pre-commit hook blocks it) — use `runBlockingCancellable`.
- Architecture rule (CLAUDE.md): core interface → `ToolResult<T>` → feature impl → agent tool wrapper.
- Probe is opt-in and no-ops without `/tmp/rd-probe/inputs`; safe to leave in tree.
- New probe file: `document/src/test/kotlin/.../probe/IndexedExtractionProbe.kt` (uncommitted, untracked).
- Two unrelated untracked specs exist in the tree (`2026-05-28-handover-checks-data-fix-design.md`) —
  not part of this work; leave alone.
- The TDD skill file `agent/src/main/resources/skills/tdd/SKILL.md` shows as modified (+18) — the
  subagent appears to have touched it; review whether that change is wanted or should be reverted.

## Suggested next action for the new session
Use **subagent-driven-development** (skip reviewers per user pref), dispatch **Task 2 (Opus)**
first, then **Task 3 (Sonnet)**, sequentially. Provide each subagent the spec section + the files
list + the repo gotchas above. After both, run the probe + `verifyPlugin`, then ask the user before
committing.
