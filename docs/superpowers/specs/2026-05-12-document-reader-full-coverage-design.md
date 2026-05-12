# Document Reader — Full Coverage Design

**Date:** 2026-05-12
**Status:** Draft (awaiting user sign-off)
**Author:** brainstormed via Claude Code session
**Branch:** `fix/automation-handover-quality-tabs` (work starts here; per-phase branches per the team's flow)

## Background

The `:document` module powers the agent's `read_document` tool — the only path the LLM has into PDF, DOCX, XLSX, PPTX, RTF, ODT, EPUB, HTML, and CSV. The current extractors produce a `List<DocumentBlock>` from a sealed hierarchy of 5 variants (`Heading`, `Paragraph`, `Table`, `PageMarker`, `EmbeddedFileRef`) which `MarkdownAssembler` renders to the Markdown the LLM consumes.

A characterization survey (see `document/src/test/kotlin/.../*FormatGapsTest.kt`, 25 tests, all currently green) confirms the model and the extractors silently drop a wide range of structured content:

- **Comments** in all four formats (PDF annotation metadata, DOCX review, XLSX cell, PPTX slide)
- **Tracked changes** in DOCX (final-view text survives; insertions/deletions don't)
- **Embedded images** everywhere — PDF (`isExtractInlineImages=false`), DOCX (`XWPFRun.embeddedPictures` never read), XLSX (no `XSSFDrawing` walk), PPTX (`when` branch has no `XSLFPictureShape` arm), HTML (`<img>` content dropped)
- **Hyperlinks** in every format (display text survives; URL lost)
- **Lists** (bulleted/numbered) — flattened to paragraphs
- **Custom heading styles** — `Title`, `Subtitle`, `Quote` not detected in DOCX
- **Footnotes & endnotes** in DOCX
- **Headers/footers** in DOCX
- **PPTX group shapes** — children silently invisible
- **DOCX vertical-merge cells** — continuation rows blank
- **Charts, SmartArt, math equations** across DOCX/XLSX/PPTX
- **PDF bookmarks, AcroForm fields, document properties, embedded attachments**
- **XLSX hidden sheets** — extracted with no flag (potential data-leak surface)

The deliberate "body-only" iteration in each POI extractor (chosen for paragraph↔table interleaving fidelity) is what causes most of these gaps. PDF is the odd one out: Tika's `extractAnnotationText=true` default means annotation text leaks into the prose stream as untyped paragraphs with no author/anchor metadata.

This is the full-coverage design to close every gap above.

## Goals

1. Every gap currently pinned by `*FormatGapsTest.kt` becomes a positive coverage test.
2. The LLM gets typed, predictable Markdown that distinguishes comments, lists, footnotes, and image references from prose.
3. Embedded images are extracted to disk and surfaced as `[image: <path>]` references; the LLM uses a new `view_image(path)` tool to load any image it cares about into its vision context, reusing the existing `AttachmentStore` / `ToolResult.imageRefs` / `BrainRouter` multimodal pipeline. **No auto-attach of every image.**
4. Every change ships as a small, independently-mergeable PR; CI green/red count visibly tracks progress.

## Non-goals

- **No rendering pipeline.** Charts become data tables (POI exposes series data); SmartArt becomes flat lists with indent markers; math becomes LaTeX/plain text. No SVG, no PNG synthesis.
- **No OCR.** Scanned PDFs, image-only screenshots, and PDF image XObjects without a text layer remain text-less. Tika's `tika-parser-ocr-module` stays excluded from the bundle.
- **No DOC/XLS/PPT (legacy binary OLE2) coverage.** Tika handles them today via `tika-parser-microsoft-module`; the gap survey did not include them and full coverage of legacy formats is deferred. POI's `poi-scratchpad` stays in the bundle only because Tika's microsoft-module hard-references it (build.gradle.kts:117).
- **No inline-run formatting (bold/italic/underline/strike/color).** The LLM rarely needs run-level styling; surfacing it would require a `Run` system inside `Paragraph` that the assembler would have to serialize. Out of scope.
- **No DocumentBlock.List variant with structural nesting.** Lists are flat; nested items use indent characters inside item strings.

## Design

### Section 1 — Model changes (`:core`)

Extend `DocumentBlock`:

```kotlin
sealed class DocumentBlock {
    // Unchanged
    data class Heading(val level: Int, val text: String) : DocumentBlock()
    data class Paragraph(val text: String) : DocumentBlock()
    data class Table(val headers: List<String>, val rows: List<List<String>>, val caption: String? = null) : DocumentBlock()
    data class PageMarker(val pageNumber: Int) : DocumentBlock()

    // Extended: optional on-disk path for images extracted into the session
    data class EmbeddedFileRef(
        val name: String,
        val mimeType: String,
        val path: String? = null,            // NEW — absolute path under {sessionDir}/downloads/document-{sha6}/
    ) : DocumentBlock()

    // NEW
    data class Comment(
        val author: String?,
        val anchorText: String?,
        val text: String,
        val kind: Kind,
    ) : DocumentBlock() {
        enum class Kind { REVIEW, TRACKED_INSERTION, TRACKED_DELETION, PDF_ANNOTATION }
    }

    data class ListBlock(
        val ordered: Boolean,
        val items: List<String>,            // single level; nested encoded with leading "  " inside item
    ) : DocumentBlock()

    data class Footnote(
        val marker: String,
        val text: String,
    ) : DocumentBlock()

    data class KeyValueGroup(
        val title: String,
        val pairs: List<Pair<String, String>>,
    ) : DocumentBlock()
}
```

Rationale:

- Tracked changes reuse `Comment` because semantically they're "Jane suggests this change" — same shape.
- PDF form fields, bookmarks, doc properties, XLSX defined names all share a flat-key:value shape → `KeyValueGroup`.
- Hyperlinks are NOT a new variant. The URL is preserved as a parenthetical suffix in the existing `Paragraph.text`: `the spec (https://x)`. Pragmatic; no inline-runs system needed.

### Section 2 — Per-format extractor changes

Each extractor walks an expanded set of collections.

**DOCX (`DocxTableExtractor`)** — 7 new emission sites:

| Source POI API | Emits | Notes |
|---|---|---|
| `doc.headerFooterPolicy` (default header & footer) | `Paragraph` prefixed `> Header:` / `> Footer:` once at start of doc | Per-page repetition NOT emitted; one block, deduplicated |
| `doc.comments.comments` | `Comment(REVIEW, author, anchorText, text)` inline after the paragraph it anchors to | Anchor offset from `w:commentRangeStart`/`End` IDs |
| `doc.footnotes.footnotes` + `doc.endnotes.endnotes` | `Footnote(marker, text)` collected and emitted at end of body | DOCX footnotes are page-anchored; we batch to doc-end for LLM simplicity |
| `XWPFParagraph` w/ `w:ins`/`w:del` runs | `Comment(TRACKED_INSERTION/DELETION, author, anchorText, text)` inline | Final-view text still surfaces as the Paragraph |
| `XWPFParagraph.numId != null` (list item) | Accumulator → `ListBlock` when run of consecutive list items breaks | Reads `numbering.xml` for ordered vs bulleted; nested levels join with `  ` indent inside item |
| `XWPFRun.embeddedPictures` | `EmbeddedFileRef(name, mime, path)` after `ImageExtractionService.save(...)` | Picture bytes via `XWPFPictureData.data` |
| `XWPFParagraph.style` regex extended | `Heading(1)` for `Title`; `Heading(2)` for `Subtitle`; `Heading(3)` for `Quote` | Style ID map kept tight |

**XLSX (`XlsxTableExtractor`)** — 4 new emission sites:

| Source POI API | Emits | Notes |
|---|---|---|
| `sheet.sheetVisibility != VISIBLE` | Heading text → `(hidden) <sheetName>` | Per the brainstorm decision (extract but flag) |
| `Cell.cellComment` | `Comment(REVIEW, author, anchorText="<cellRef>", text)` immediately after the Table | Cell ref like "B7" gives the LLM a coordinate |
| `XSSFDrawing.shapes` filtered to `Picture` | `EmbeddedFileRef(name, mime, path)` after the Table | Bytes via `XSSFPictureData.data` |
| `wb.allNames` (defined names) | `KeyValueGroup(title="Defined names", pairs)` once at top of output | Useful context for any formula the LLM sees later |

**PPTX (`PptxExtractor`)** — 4 new emission sites + group-shape recursion:

| Source POI API | Emits | Notes |
|---|---|---|
| `slide.comments` | `Comment(REVIEW, author, anchorText=null, text)` after the slide's content | PPTX comments are slide-level, not text-anchored |
| `XSLFPictureShape` in `slide.shapes` (NEW `when` arm) | `EmbeddedFileRef(name, mime, path)` | Bytes via `XSLFPictureShape.pictureData.data` |
| `XSLFGroupShape` (NEW `when` arm) | Recurse into `.shapes` | Fixes the "text inside a group is invisible" gap |
| `XSLFChart` (NEW `when` arm) | `Table(headers=axis labels, rows=series data, caption=chart title)` | Charts ARE extractable as data tables — POI exposes `XSLFChart.chartSeries` |

**PDF (`PdfPipeline` + `PdfProseExtractor` + new `PdfMetadataExtractor`)**:

- `hardenedPdfConfig().isExtractAnnotationText` flips to `false` so Tika no longer leaks annotation prose into the paragraph stream.
- A new `PdfMetadataExtractor` pulls annotations, bookmarks, AcroForm, doc properties, and image XObjects via PDFBox directly.

| Source PDFBox API | Emits | Notes |
|---|---|---|
| `PDPage.annotations.filterIsInstance<PDAnnotationMarkup>()` | `Comment(PDF_ANNOTATION, author=titlePopup, anchorText=annotated text, text=contents)` | Replaces today's "untyped Paragraph leak" |
| `PDDocumentOutline` (bookmarks) | `KeyValueGroup(title="Bookmarks", pairs=[(title, "p.<num>")])` once at top | TOC for the LLM |
| `PDAcroForm.fields` | `KeyValueGroup(title="Form fields", pairs)` once at end | Skipped if form is empty |
| `PDDocumentCatalog.documentInformation` | `KeyValueGroup(title="Document properties", pairs)` once at top | Gated on ≥1 non-blank field |
| `PDEmbeddedFilesNameTreeNode` (attachments) | `EmbeddedFileRef(name, mime, path)` after saving attachment bytes | PDFs can carry XLSX/DOCX inside |
| Image XObjects via `PDResources.xobjectNames` | `EmbeddedFileRef(name, mime, path)` | We walk image XObjects ourselves so we control where bytes land; Tika-side image dumping stays off |

**Tika XHTML path (HTML/RTF/ODT/EPUB)** — `DocumentBlockHandler` gains:

- `<ul>`/`<ol>` state machine → `ListBlock`
- `<a href="…">` → URL preserved as `(<href>)` postfix on the inner text
- `<img src="…" alt="…">` → `EmbeddedFileRef(name=alt-or-src, mime=guess-from-extension, path=null)`. For RTF/ODT/EPUB, wire Tika's `EmbeddedDocumentExtractor` ParseContext element so embedded image bytes flow to `ImageExtractionService`.

**Math equations** (DOCX OMML): Phase 5a emits the plain-text fallback Word stores alongside OMML (no new dep). Phase 5b adds LaTeX via the `omml2mml` XSLT (small, bundleable). Separate PRs.

**SmartArt** (DOCX/PPTX): no rendering. SmartArt text-tree → flat `ListBlock` with indent levels encoded as leading `  ` inside each item. Visual relationships (arrows, hierarchy types) lost — acceptable degradation per the "no rendering" non-goal.

### Section 3 — Image extraction subsystem (`:document` + `:agent`)

New service `ImageExtractionService` in `:document`:

```kotlin
class ImageExtractionService(
    private val downloadDirProvider: () -> Path? = { SessionDownloadDir.current() },
) {
    fun save(bytes: ByteArray, suggestedName: String, mime: String): Path
}
```

- Saves to `{sessionDir}/downloads/document-{sha6OfDocPath}/image-{ordinal}-{sha6OfBytes}.{ext}`.
- Doc-level directory keyed off the source document path's sha6.
- Per-image name uses content hash so duplicate images inside one doc share a path (no wasted bytes).
- `SessionDownloadDir.current() == null` (UI handler, tests) → falls back to `java.io.tmpdir`. Matches the `jira.download_attachment` pattern.

New agent tool `view_image` in `:agent`:

```kotlin
class ViewImageTool : AgentTool {
    override val name = "view_image"
    override val description = """
        Load an image file into your vision context. Use this when read_document
        surfaces an `[image: <path>]` reference and you need to see the image
        content (figures, screenshots, diagrams).

        Path must be under the current session's downloads/ directory — paths from
        outside the session are rejected for safety.
    """.trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf("path" to ParameterProperty("string", "Absolute path …")),
        required = listOf("path"),
    )
    override val timeoutMs = 5_000L
    override val allowedWorkers = setOf(ORCHESTRATOR, CODER, REVIEWER, ANALYZER)

    override suspend fun execute(args: JsonObject, project: Project): ToolResult { … }
}
```

- Registered as **deferred** (LLM only needs it after a read_document that surfaced image paths).
- Validation: path must be canonical, under `{sessionDir}/downloads/`, must be a supported image MIME (png/jpeg/webp/gif).
- Effect: writes bytes into the per-session `AttachmentStore`, returns `ToolResult` with `imageRefs=[AttachmentRef(...)]`. The existing `BrainRouter` routes the next assistant turn through `/.api/completions/stream`, so the LLM literally sees the image.
- ≈ 60 LOC + ≈ 30 LOC of tests.

### Section 4 — MarkdownAssembler serialization

`MarkdownAssembler.serializeBlock` gains one `when` arm per new variant. Deterministic output:

| Block | Markdown |
|---|---|
| `Comment(REVIEW, author="Jane", anchor="80ms", text=…)` | `> **Comment by Jane** (anchor: "80ms"):\n> <text>\n\n` |
| `Comment(TRACKED_INSERTION, author="Tom", anchor=null, text="proposed text")` | `> **Tom proposes inserting**: proposed text\n\n` |
| `Comment(TRACKED_DELETION, author="Tom", anchor="old text", text=null)` | `> **Tom proposes deleting**: "old text"\n\n` |
| `Comment(PDF_ANNOTATION, author=null, anchor="paragraph quote", text=…)` | `> **PDF annotation** (on: "<anchor>"):\n> <text>\n\n` |
| `ListBlock(ordered=false, items=…)` | `- item1\n- item2\n\n` |
| `ListBlock(ordered=true, items=…)` | `1. item1\n2. item2\n\n` |
| `Footnote(marker="1", text=…)` | `[^1]: <text>\n` (collected into a contiguous final block) |
| `KeyValueGroup(title="Bookmarks", pairs=…)` | `**Bookmarks**\n- title (p.12)\n- …\n\n` |
| `EmbeddedFileRef(name, mime, path != null)` | `[image: <path>] (<mime>)\n\n` |
| `EmbeddedFileRef(name, mime, path == null)` | `[embedded: <name> (<mime>) — not extracted]\n\n` (current behavior) |

Footnotes are bucketed: the assembler collects them during the first pass and emits one contiguous final block. If the character budget is exhausted before footnotes serialize, the truncation marker says so explicitly.

### Section 5 — Testing strategy

Four layers, mirroring the existing `:document` test structure:

1. **Per-extractor synthetic-fixture tests.** Extend the existing `*FormatGapsTest.kt` files. Each gap test flips from "feature is dropped" to "feature is extracted as `Comment(…)` / `ListBlock(…)` / `EmbeddedFileRef(…, path=…)`." Drives TDD: turn the gap test green by implementing the emission.
2. **MarkdownAssembler unit tests.** Pure-Kotlin, zero IntelliJ deps. Build a `List<DocumentBlock>` with every new variant and assert exact Markdown output character-for-character. Locks in the serialization contract independent of any extractor bug.
3. **End-to-end via `TikaDocumentExtractor`.** Extend `TikaDocumentExtractorTest` with one fixture per major feature: a DOCX with comments, a PDF with bookmarks + form fields, a DOCX with images round-tripping through `view_image`.
4. **`view_image` agent-tool test.** Single test calls `ViewImageTool.execute()` with a session-dir path and asserts `ToolResult.imageRefs.size == 1` + right MIME.

The 25 existing characterization tests serve as the regression net — they flip from green to red as gaps are closed; the per-phase PR makes them green again with positive assertions.

### Section 6 — Phase plan + dependencies

Six phases, each a shippable PR:

| Phase | Scope | LOC | Blocks |
|---|---|---|---|
| **0. Model + assembler** | Add 5 new `DocumentBlock` variants; teach `MarkdownAssembler` every new arm; add `path` field to `EmbeddedFileRef`; lock serialization via assembler unit tests. **No extractor changes yet.** | ~200 | All later phases |
| **1. Comments (all formats)** | DOCX `XWPFComments`, XLSX `Cell.cellComment`, PPTX `slide.comments`, PDF `PDAnnotationMarkup`. Plus DOCX tracked changes (`w:ins`/`w:del` → `Comment(TRACKED_*)`). | ~400 | Phase 0 |
| **2. Image extraction + `view_image`** | `ImageExtractionService` in `:document`; emission wired in DOCX/XLSX/PPTX/Tika-XHTML extractors (PDF image XObjects land in Phase 4 alongside other PDF metadata); `ViewImageTool` in `:agent`; `read_document` description updated. | ~300 | Phase 0 |
| **3. Structure (lists, headings, headers/footers, group recursion, merges)** | DOCX numbering map → `ListBlock`; HTML `<ul>`/`<ol>` → `ListBlock`; DOCX Title/Subtitle/Quote → `Heading`; DOCX `headerFooterPolicy` → top-of-doc prefix; PPTX `XSLFGroupShape` recursion; DOCX vertical-merge cells; XLSX hidden-sheet `(hidden)` heading prefix. | ~350 | Phase 0 |
| **4. PDF metadata channels + XLSX defined names** | bookmarks, AcroForm, doc properties, PDF embedded attachments → `KeyValueGroup`/`EmbeddedFileRef`; XLSX `allNames` → `KeyValueGroup`. | ~250 | Phase 0 + Phase 2 |
| **5. Rich content** | Phase 5a: charts in DOCX/XLSX/PPTX (`Table`), SmartArt in DOCX/PPTX (`ListBlock`), DOCX/XLSX/PPTX OMML math plain-text fallback, hyperlinks across all formats including Tika-XHTML's `<a href>` (URL postfix on Paragraph), DOCX footnotes/endnotes (`Footnote`). Phase 5b: OMML→LaTeX via XSLT (separate PR). | ~500 | Phase 0 |

**Total ≈ 2000 LOC across 6-7 PRs.**

Phase 0 is the keystone. After it lands, Phases 1, 2, 3, 4 are independent and can ship in parallel via subagents. Phase 5a depends on Phase 0; Phase 5b depends on Phase 5a.

## Risks & mitigations

- **POI API surface drift.** POI 5.4.1 is the current version; some new emission sites (e.g. `XWPFOMath`) live in newer minors. Mitigation: each extractor's new code is in a try/catch that degrades to "no emission" rather than failing the whole document. Pin the POI version explicitly in `build.gradle.kts` and add a version-assertion test.
- **DOCX comment-anchor offsets are fragile.** `w:commentRangeStart`/`End` IDs can span multiple runs and even paragraphs. Mitigation: if the anchor span resolves to multiple paragraphs, emit the comment after the LAST anchored paragraph and use a synthetic anchor of `"<first paragraph quote first 60 chars>…"`.
- **PDF annotation extraction order.** PDFBox returns annotations per-page in z-order, not reading order. Mitigation: sort by annotation rectangle's `(page, top, left)` before emission.
- **Image-bytes memory pressure.** A DOCX with a 50 MB embedded PNG would OOM under PoiHardening's `IOUtils.setByteArrayMaxOverride(50_000_000)` even on the read path. Mitigation: stream image bytes to disk via `Files.copy(inputStream, path)` instead of materializing to `ByteArray`; bump `setMaxImageSize` per-doc only as high as a configurable ceiling (default 25 MB; setting in `PluginSettings.documentMaxImageSize`).
- **`view_image` path validation.** A malicious LLM-emitted path could request files outside the session. Mitigation: `PathValidator.resolveAndValidateForRead` with the session's downloads/ as the only allowed root; canonical-path check; MIME-type allowlist (png/jpeg/webp/gif).
- **Tika annotation flag flip.** Setting `isExtractAnnotationText=false` could regress PDFs where Tika was the only annotation source. Mitigation: the new `PdfMetadataExtractor` is the SOLE annotation source; tests run on both annotated and non-annotated fixtures to confirm parity.

## Open questions (resolved during brainstorm)

| Question | Resolved decision |
|---|---|
| Scope (P0 vs full vs single) | **Full coverage** |
| Model approach (new variants vs prefixes) | **Extend `DocumentBlock` with new variants** |
| Image-handling strategy | **Path-based with on-demand `view_image` tool**; reuses existing AttachmentStore/imageRefs/BrainRouter |
| Comment placement (inline vs sidecar) | **Inline at anchor point** |
| XLSX hidden-sheet policy | **Extract but flag** (`(hidden)` prefix on the sheet heading) |
| Charts, SmartArt, math (with no rendering pipeline) | **Charts → Table (data), SmartArt → ListBlock (text only), math → plain-text Phase 5a then LaTeX Phase 5b** |
| Tracked changes representation | **`Comment(TRACKED_INSERTION/DELETION)` — same model as review comments** |
| Inline formatting (bold/italic/runs) | **Out of scope — not worth the model churn** |
| Hyperlink representation | **URL postfix on existing Paragraph text** — `"the spec (https://x)"` |

## Acceptance criteria

- Every `*FormatGapsTest.kt` test that currently asserts "feature dropped" has a sibling positive test asserting the feature is now extracted in the correct variant.
- `./gradlew :document:test` is green (currently 117 tests; will be ≈ 200 by end of Phase 5).
- `./gradlew :agent:test` is green (new `ViewImageToolTest` joins the suite).
- `./gradlew verifyPlugin buildPlugin` is green.
- One round-trip integration test demonstrates: `read_document` on a DOCX with an embedded screenshot → `[image: /…/image-0-abc.png]` in markdown → LLM-style call to `view_image(path)` → `ToolResult.imageRefs[0]` non-null → BrainRouter test confirms the next LLM call routes through `/stream`.

## Out of scope (deferred, not in this design)

- OCR (Tika OCR plugin re-enable + Tesseract native dep).
- Legacy binary OLE2 (.doc/.xls/.ppt) coverage beyond what Tika already provides.
- Inline-run formatting (`Run` system inside Paragraph).
- DocumentBlock.NestedList variant (current flat ListBlock with indent strings is sufficient).
- LaTeX → MathML rendering (Phase 5b emits LaTeX delimiters; rendering is the consumer's problem).
- PDF image-XObject OCR even when text-layer is absent.
