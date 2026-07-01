---
name: Binary document reader v1 (SHIPPED on refactor/cleanup-perf-caching)
description: read_document agent tool — Tika XHTML + Tabula-java + POI direct → Markdown. Cell-perfect on spec PDFs, multi-page table merge, prose-vs-table dedup. Shipped via subagent-driven dev with per-phase code review on 2026-04-30.
type: project
originSessionId: 149d9b4c-9656-4b7e-bb36-07527eadb9c6
---
**SHIPPED v1** on `refactor/cleanup-perf-caching` 2026-04-30. Tool name: `read_document`. Registered as deferred-tier in AgentService; discoverable via tool_search.

**In-tree artifacts:**
- Research: `docs/architecture/binary-document-reader-research.md`
- v1 plan (post-review): `docs/architecture/binary-document-reader-implementation-plan.md`
- Adversarial review: `docs/architecture/binary-document-reader-plan-review.md`
- Module: `:document` (new). Source 17 Kotlin files / 10 test Kotlin files. 78 :document tests + 16 DocumentToolTest + 16 JiraToolDownloadAttachmentTest + 11 PluginSettingsDocumentFieldsTest + 19 DocumentBlockTest/ExtractOptionsTest = ~140 tests.

**Architecture:** core interface (`DocumentExtractor`) → `:document` impl (`TikaDocumentExtractor`) → agent wrapper (`DocumentTool`). All accuracy-critical:
- PDF: Tabula-java lattice (stream-mode opt-in) + Tika XHTML for prose, bbox-aware merge with multi-page continuation merge, prose-vs-table dedup pass.
- Office: POI direct (`XWPFDocument.bodyElements`, `XSSFWorkbook` with `Sheet.iterator()` — never `drop(1)`, `XMLSlideShow`). Cell-perfect.
- Other: Tika `XHTMLContentHandler` + custom `DocumentBlockHandler` SAX. CSV detection MIME-gated to avoid phantom tables on prose with commas.
- Output: Markdown via `MarkdownAssembler` with truncation policy (never split mid-table).

**Defenses applied:**
- POI XXE hardening at extractor init (`IOUtils.setByteArrayMaxOverride`, `ZipSecureFile` limits) — `PoiHardening.applyOnce()`.
- Tika config: `loadErrorHandler=THROW`, OCR + external parsers excluded.
- Init-time assertion: parser registry non-empty (TIKA-1145 defense) and zero OCR parsers — fails-fast on misconfig.
- Concurrency: global `Semaphore(2)` + per-call POI/PDFBox/Tabula instantiation (NOT thread-safe per shared instance).
- Classloader: `Thread.contextClassLoader` wrap + force PDFBox 3.0.5 via `constraints { strictly }` + Tabula's PDFBox 2.0.24 excluded transitively.
- `OutOfMemoryError` mapped explicitly with `System.gc()` before failure.

**Wired:**
- Settings: `documentMaxChars`, `documentTimeoutMs`, `documentEnableStreamMode`, `documentOcrEnabled` (greyed v2). `documentMaxChars` + `documentTimeoutMs` are wired per-call via providers (mirror `HttpClientFactory.timeoutsFromSettings`); UI in `AgentAdvancedConfigurable`.
- Jira `download_attachment` appends `Hint: This file is a [PDF/document/spreadsheet/presentation]. You can extract its text by calling read_document with path="…"` for matching MIMEs.

**Plugin distribution:** 87 MB (was ~40-50 MB pre-`:document`; +45-50 MB matches post-review estimate).

**v2 backlog:**
- Tabula `enableStreamMode` per-call wiring (currently constructor-level only; PdfTableExtractor refactor needed).
- OCR via `tika-parsers-extended` (Tesseract); +100 MB; gated behind setting.
- JCEF drag-drop auto-route for document MIMEs in chat input.
- Sourcegraph Cody Enterprise PDF document-block probe (per `reference_sourcegraph_image_transport` 24/24 methodology). Until probed, `TransportCapabilities.supportsNativePdfDocumentBlock` stays false and every PDF goes through Tika.
- Manual integration test against IDEA Ultimate with Database + Big Data plugins enabled (acceptance criterion not yet exercised; verifyPlugin alone is insufficient).
- Real bbox y-coordinates from PDFBox `PDFTextStripper` (replace synthetic prose top values for in-flow dedup precision).

**Probe results** (`/tmp/read-document-probe/` — gitignored):
- spec-with-tables.pdf: 3 ruled tables, all cells correct (FR-001/MUST/Approved … TC-3/Pass/Pending). Prose-table dedup verified.
- multi-page-table.pdf: continuation merge works, single table with all 40 rows.
- bug-tracker.xlsx: merged-cell Q1 in both A2 and A3 ✓; COUNTIF(CRITICAL) → 1 ✓.
- design-doc.docx: tables interleaved between paragraphs in document order.
- ietf-rfc7230.pdf (89 pp), nist-cybersecurity-framework.pdf (55 pp): real-world specs parse without crash.
- tabula-encrypted.pdf: clean error "PDF is password-protected; v1 does not support encrypted PDFs."
- corrupt.pdf, zero.pdf: clean error path.
