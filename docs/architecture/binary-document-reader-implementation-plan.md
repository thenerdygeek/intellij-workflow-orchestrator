# Binary Document Reader — v1 Implementation Plan (Accuracy-First)

**Companion to:** `binary-document-reader-research.md`
**Adversarial review:** `binary-document-reader-plan-review.md` (applied as of 2026-04-30 patch)
**Status:** Implementation-ready plan (post-review patches applied)
**Date:** 2026-04-30
**Scope change vs first draft:** v1 was originally Tika `BodyContentHandler` only. Research confirmed that path **flattens tables**, which is unacceptable when ingesting requirement specs and design docs. This plan is the revised, accuracy-first v1.
**Post-review patches:** PDFBox version conflict (Tabula 1.0.5 ships with PDFBox 2.0.24 transitively, not 3.x) explicitly resolved via Gradle exclude+constraint; bbox-aware splice merged into v1 (was v2); Tabula stream-mode disabled by default (lattice-only); risk register expanded with TIKA-1145 ServiceLoader bug, log4j collision, POI thread-safety, JPEG2000 native dep; effort 7→9 days.

---

## 1. Why the First v1 Plan Is Insufficient

Two independent confirmations from the Apache project itself made the original "Tika `BodyContentHandler` only" plan a non-starter for spec documents:

1. **Apache Tika wiki, on PDF tables:** *"In many PDFs, tables are often not stored as tables — a human can easily see tables, but all that is stored in the PDF is text chunks and coordinates on a page, and Tika does not currently apply advanced computation to extract table structure from a PDF."* (Apache Tika `PDFParserConfig` documentation.)
2. **`BodyContentHandler` discards structure.** It emits prose text with whitespace separators. A `| Bug | Severity | Status |` row collapses into `BUG-1\tHIGH\tOPEN` with the column-header context lost two lines earlier in the stream.

For requirement specs — where decisions hinge on *which row is in which column* — that is a correctness bug, not a quality nice-to-have.

Three things change the picture:

- **`XHTMLContentHandler`** (same Tika library) emits `<table><tr><td>` markup with cells correctly grouped. Table topology is preserved.
- **Tabula-java** (Apache-2.0, MIT-licensed components) is the canonical JVM table extractor. It uses Apache PDFBox under the hood — same parser Tika already pulls in — so it composes cleanly. Lattice mode handles ruled tables (most engineering specs); Stream mode handles whitespace-aligned tables.
- **Apache POI** has *direct* table APIs (`XWPFDocument.getTables()` for DOCX, `XSSFWorkbook` for XLSX cells). Going through POI directly gives 100% table fidelity for Office formats — no extraction heuristics involved, the structure is in the file.

The accuracy ceiling for a JVM-only solution without bringing in deep-learning models is essentially: **Tika XHTML (prose + non-PDF tables) + Tabula-java (PDF tables) + POI direct (Office tables) → unified Markdown output**. Docling/TATR-class transformer models score higher on adversarial table benchmarks but require Python and an order of magnitude more deps; correctly out of scope for an IntelliJ plugin.

---

## 2. Accuracy Bar

| Document class | Required v1 fidelity | Strategy |
|---|---|---|
| Requirement spec PDF with ruled tables | **Cell-perfect** (every value in correct row/column) | Tabula lattice → Markdown table |
| Spec PDF with whitespace-aligned tables | Best-effort row/column with explicit caveat in tool output | Tabula stream → Markdown table; flag if confidence low |
| Multi-column PDF prose (architecture docs, RFCs) | Correct reading order | PDFBox `sortByPosition=true` via Tika XHTML |
| DOCX with tables | **Cell-perfect** (no extraction; structural read) | POI `XWPFDocument.getTables()` direct |
| XLSX | **Cell-perfect** including sheet names + headers | POI `XSSFWorkbook` direct → Markdown per sheet |
| PPTX with content | Slide text + speaker notes + table content | POI `XSLFSlideShow` direct |
| Scanned image PDF (no embedded text) | Out of scope for v1; explicit error message | Defer to v2 with Tesseract |
| RTF / ODT / EPUB / HTML / CSV | Prose accurate; tables preserved as Markdown when present | Tika XHTML |

The unifying output format is **Markdown**. LLMs read Markdown tables natively, line-counting works, and prompt-budget accounting is straightforward. No HTML, no JSON tree.

---

## 3. Pipeline Overview

```
                       ┌─────────────────────────────┐
   File path ──────────│  1. MIME detection (Tika)   │
                       └─────────────┬───────────────┘
                                     │
            ┌────────────────────────┼────────────────────────┐
            │                        │                        │
       PDF MIME                 Office MIME              Other (RTF/ODT/EPUB/HTML/CSV)
            │                        │                        │
            ▼                        ▼                        ▼
   ┌────────────────┐      ┌───────────────────┐      ┌────────────────┐
   │ 2a. PdfPipeline │      │ 2b. OfficePipeline│      │ 2c. TikaXhtml  │
   │  - Tabula tables│      │  - POI direct     │      │     Pipeline   │
   │  - Tika XHTML   │      │  - Sheet/Table API│      │                │
   │    for prose    │      │                   │      │                │
   └────────┬───────┘      └─────────┬─────────┘      └────────┬───────┘
            │                        │                        │
            └────────────────────────┼────────────────────────┘
                                     │
                                     ▼
                       ┌─────────────────────────────┐
                       │ 3. MarkdownAssembler        │
                       │  - Headings (#, ##)         │
                       │  - Tables (| ... |)         │
                       │  - Page markers (PDFs)      │
                       │  - Truncation marker if     │
                       │    over budget              │
                       └─────────────┬───────────────┘
                                     │
                                     ▼
                       ┌─────────────────────────────┐
                       │ 4. ToolResult<DocumentContent>│
                       └─────────────────────────────┘
```

The branching happens once, deterministically, at MIME-detection time. Each branch produces an **intermediate structured representation** (a sequence of typed `DocumentBlock` records: `Heading`, `Paragraph`, `Table`, `PageMarker`, `EmbeddedFileRef`). The Markdown assembler is shared. This means accuracy improvements per format are isolated — you can refine PDF extraction without breaking DOCX.

---

## 4. Library Choices (Final)

| Concern | Library | Coordinate | Apache-2.0 | Why |
|---|---|---|---|---|
| MIME detection + dispatch | Apache Tika | `org.apache.tika:tika-core:3.x` | ✓ | One-call detection; bundled with parsers |
| PDF prose, multi-column reading order | Tika + PDFBox via XHTML | `org.apache.tika:tika-parsers-standard-package:3.x` | ✓ | `sortByPosition=true`; `<p>` for paragraphs |
| **PDF tables** | **Tabula-java** | `technology.tabula:tabula:1.0.5` | MIT | Lattice + stream algorithms; uses PDFBox; canonical JVM choice |
| DOCX tables + prose | POI XWPF (direct API) | `org.apache.poi:poi-ooxml:5.x` | ✓ | `XWPFDocument.getTables()`; loss-free |
| XLSX | POI XSSF (direct API) | `org.apache.poi:poi-ooxml:5.x` | ✓ | `XSSFWorkbook` cell-level; sheet names |
| PPTX | POI XSLF (direct API) | `org.apache.poi:poi-ooxml:5.x` | ✓ | Slide text + notes + tables |
| Legacy DOC/XLS/PPT | POI HWPF/HSSF/HSLF (via Tika) | (transitively included) | ✓ | Tika dispatches; lower fidelity than OOXML, acceptable |
| RTF/ODT/EPUB/HTML/CSV | Tika XHTML | (in `tika-parsers-standard-package`) | ✓ | XHTML preserves structure |

**Total dependency footprint:** **45–70 MB** (Tika+PDFBox+POI+Tabula). Earlier estimate of 35–55 MB assumed Tabula shared PDFBox with Tika; review confirmed Tabula 1.0.5 ships with PDFBox 2.0.24 transitively, so we exclude it and force PDFBox 3.0.5 — Tabula then adds itself + jts-core + commons-csv + bouncycastle (~10 MB).

**Explicit no:** Aspose (commercial), Docling/TATR (Python+ML), iText (AGPL/commercial dual-license — both terms incompatible with the plugin), GroupDocs (commercial).

**CRITICAL DEPENDENCY CONSTRAINT (post-review):** Tabula 1.0.5 ships PDFBox **2.0.24** transitively (verified via Maven Central `tabula-1.0.5.pom`). Tika 3.2.3 declares PDFBox **3.0.5**. These two versions cannot coexist in the same classloader. Required Gradle wiring:

```kotlin
implementation("technology.tabula:tabula:1.0.5") {
    exclude(group = "org.apache.pdfbox")  // strip PDFBox 2.0.24
}
implementation("org.apache.tika:tika-parsers-standard-package:3.2.3")
implementation("org.apache.poi:poi-ooxml:5.4.1")

constraints {
    implementation("org.apache.pdfbox:pdfbox") { version { strictly("3.0.5") } }
    implementation("org.apache.pdfbox:fontbox") { version { strictly("3.0.5") } }
}
```

Add a runtime sanity test in `:document` test source: open a `PDDocument` via Tabula's classes and assert the resolved class is from `pdfbox-3.0.5.jar`. If Tabula 1.0.5's compiled bytecode incompatibly calls PDFBox 2.x APIs that don't exist in 3.x, fall back to vendoring Tabula `master` HEAD (commit `2cdf3b4`, 2025-03-19, already on PDFBox 3.0.4) as a local Gradle source dep.

---

## 5. Module Structure

Following the plugin's existing rule (`CLAUDE.md` Service Architecture): *"core interface → `ToolResult<T>` → feature impl → agent tool wrapper"*.

```
:core
  api/
    DocumentExtractor.kt              ← interface
    TransportCapabilities.kt          ← stub w/ supportsNativePdfDocumentBlock = false
  model/
    DocumentContent.kt                ← data class (markdown, mime, metadata, blocks?)
    DocumentBlock.kt                  ← sealed: Heading, Paragraph, Table, PageMarker, EmbeddedFileRef
    ExtractOptions.kt                 ← maxChars, timeoutMs, includeEmbedded, etc.
  resources/
    tika-config.xml                   ← hardened (no network, no native, sortByPosition=true)

:document   (NEW module, depends on :core only)
  build.gradle.kts                    ← Tika + Tabula + POI deps
  service/
    TikaDocumentExtractor.kt          ← entry point; MIME-based dispatch
    pipeline/
      PdfPipeline.kt                  ← Tabula tables + Tika XHTML prose merger
      OfficePipeline.kt               ← POI direct dispatch (DOCX/XLSX/PPTX)
      TikaXhtmlPipeline.kt            ← XHTML SAX → DocumentBlock list
    assembler/
      MarkdownAssembler.kt            ← DocumentBlock list → markdown string with budget enforcement
    sax/
      DocumentBlockHandler.kt         ← SAX ContentHandler producing DocumentBlock events
    poi/
      DocxTableExtractor.kt           ← XWPFDocument → DocumentBlock
      XlsxTableExtractor.kt           ← XSSFWorkbook → DocumentBlock per sheet
      PptxExtractor.kt                ← XSLFSlideShow → DocumentBlock per slide
    pdf/
      PdfTableExtractor.kt            ← Tabula lattice + stream wrapper
      PdfProseExtractor.kt            ← Tika PDFParser w/ XHTML handler

:agent
  tools/integration/
    DocumentTool.kt                   ← AgentTool, deferred-tier registration
```

`:document` is a new feature module per `CLAUDE.md`'s 9-module pattern. If the team prefers to avoid a new module for v1, fold the contents into `:core/services/` — the layering rule still holds because `:core` is the dependency floor anyway.

---

## 6. Step-by-Step Implementation

Estimated **4–5 days** of focused work. Steps are ordered so each one is independently testable.

### Step 1 — Core types (½ day)

Create `:core/api/DocumentExtractor.kt`:

```kotlin
interface DocumentExtractor {
    suspend fun extract(path: Path, options: ExtractOptions = ExtractOptions()): ToolResult<DocumentContent>
}
```

Create the `DocumentBlock` sealed class hierarchy:

```kotlin
sealed class DocumentBlock {
    data class Heading(val level: Int, val text: String) : DocumentBlock()
    data class Paragraph(val text: String) : DocumentBlock()
    data class Table(val headers: List<String>, val rows: List<List<String>>, val caption: String? = null) : DocumentBlock()
    data class PageMarker(val pageNumber: Int) : DocumentBlock()
    data class EmbeddedFileRef(val name: String, val mimeType: String) : DocumentBlock()
}
```

`DocumentContent` carries `markdown: String`, `mime: String`, `pageCount: Int?`, `title: String?`, `author: String?`, `truncated: Boolean`, plus an optional `blocks: List<DocumentBlock>` for callers that want structured access.

Land `TransportCapabilities.supportsNativePdfDocumentBlock = false` as a stub for v2.

**Tests:** type-only, no parsers yet.

### Step 2 — Markdown assembler (½ day)

`MarkdownAssembler.assemble(blocks: List<DocumentBlock>, maxChars: Int): Pair<String, Boolean>`. The `Boolean` is `truncated`.

Deterministic conversion rules:
- `Heading(1, "Foo")` → `# Foo\n\n`
- `Paragraph(text)` → `text\n\n`
- `Table(headers, rows)` → standard Markdown pipe table; escape `|` in cell content; pad column widths for human readability of the raw markdown
- `PageMarker(n)` → `<!-- page: n -->\n` (HTML comment so it doesn't render but is searchable)
- `EmbeddedFileRef` → `[embedded: name (mime)]\n`

Truncation policy: assemble blocks in order; once the running char count would exceed `maxChars`, append `\n\n*[Document truncated at N characters of M total]*` and return `(markdown, true)`. Never truncate mid-table.

**Tests:** every block type, table-with-pipes-in-cells, truncation at exact boundary, multi-page PDF marker placement.

### Step 3 — Tika XHTML pipeline (1 day)

`TikaXhtmlPipeline` for the "everything that isn't PDF or Office" case (RTF, ODT, EPUB, HTML, CSV, plain text) and as a *prose backbone* for PDF.

Implementation:

```kotlin
class TikaXhtmlPipeline {
    fun extract(stream: InputStream, mime: String): List<DocumentBlock> {
        val parser = AutoDetectParser()
        val handler = DocumentBlockHandler()  // custom SAX handler
        val metadata = Metadata().apply { set(Metadata.CONTENT_TYPE, mime) }
        val context = ParseContext().apply {
            set(PDFParserConfig::class.java, hardenedPdfConfig())
        }
        parser.parse(stream, handler, metadata, context)
        return handler.blocks
    }
}
```

`DocumentBlockHandler extends ContentHandler`: maintain a stack as XHTML opens/closes; on `</h1>`–`</h6>` emit `Heading`; on `</p>` emit `Paragraph`; on `<table>` open, start collecting rows; on `</table>` flush as `DocumentBlock.Table`; on `<div class="page">` emit `PageMarker`. Tika emits page markers in PDF mode when configured; for non-PDF, no page markers are emitted.

`hardenedPdfConfig()`:
- `setSortByPosition(true)` — multi-column reading order
- `setExtractMarkedContent(true)` — preserve marked tables when present
- `setOcrStrategy(OCRStrategy.NO_OCR)` — never invoke OCR in v1
- `setExtractInlineImages(false)` — embedded images not extracted in v1
- `setMaxMainMemoryBytes(50_000_000)` — bound memory per file
- network: disabled by tika-config.xml

**Tests:** RTF with table, ODT with headings + lists, EPUB chapter structure, CSV → single Table block. PDF tests deferred to step 5 (where they get the dedicated table extractor).

### Step 4 — POI Office pipeline (1 day)

Three extractors, each producing `List<DocumentBlock>`:

**`XlsxTableExtractor`:**
```kotlin
fun extract(stream: InputStream): List<DocumentBlock> {
    val blocks = mutableListOf<DocumentBlock>()
    XSSFWorkbook(stream).use { wb ->
        for (sheet in wb) {
            blocks += DocumentBlock.Heading(2, sheet.sheetName)
            val rowIter = sheet.iterator()  // streaming — does NOT materialize all rows
            if (!rowIter.hasNext()) continue
            val headers = rowIter.next().map { cellAsString(it) }
            val rows = mutableListOf<List<String>>()
            var rowsRead = 0
            while (rowIter.hasNext() && rowsRead < MAX_ROWS_PER_SHEET) {
                val row = rowIter.next()
                rows += headers.indices.map { cellAsString(row.getCell(it)) }
                rowsRead++
            }
            blocks += DocumentBlock.Table(headers, rows)
            if (rowIter.hasNext()) {
                blocks += DocumentBlock.Paragraph("_(truncated at $MAX_ROWS_PER_SHEET rows)_")
            }
        }
    }
    return blocks
}
```
Notes:
- **Use `Sheet.iterator()` — never `sheet.drop(1)` or `sheet.toList()`** (review finding: those materialize 100K rows into a `List` just to skip 1).
- For multi-million-row XLSX, switch to `XSSFReader` SAX-based streaming. v1 caps at `MAX_ROWS_PER_SHEET = 50_000` per sheet; oversize gets a truncation paragraph.
- Handle merged cells by repeating the merged value; formulas via `FormulaEvaluator.evaluate()`; dates via `DateUtil.isCellDateFormatted()`.
- **POI XXE hardening:** invoke `OPCPackage.open(stream, PackageAccess.READ)` with `IOUtils.setByteArrayMaxOverride(50_000_000)` set globally at extractor init. Tika's `tika-config.xml` does NOT cover POI's direct XmlBeans path — POI's own hardening must be configured separately.
- **POI is NOT thread-safe** per shared `XSSFWorkbook` — never cache; always per-call instantiation. Per POI FAQ: "Accessing the same document in multiple threads will not work."

**`DocxTableExtractor`:** walk `XWPFDocument.bodyElements` in document order. For each element: `XWPFParagraph` → emit `Paragraph` (or `Heading` if style is `Heading 1`/`Heading 2`/`Heading 3`); `XWPFTable` → emit `Table` with headers from row 0. This preserves prose order around tables, which is exactly what spec docs need.

**`PptxExtractor`:** for each slide in `XMLSlideShow`: emit `Heading(2, "Slide N: $title")`, then `Paragraph` per text frame, then any `XSLFTable` → `Table`, then speaker notes as `Paragraph` prefixed with `> Notes: `.

**Tests:** XLSX with formulas, XLSX with merged cells, DOCX with tables interleaved between paragraphs, DOCX with hyperlinks, PPTX with speaker notes.

### Step 5 — PDF pipeline with Tabula tables + bbox-aware merge (1.5 days)

This is the accuracy-critical step. **Post-review change: bbox-aware merge is now in v1, not v2.** Page-bucket splice produced documented failure modes (multi-page tables disconnected; alternating prose/table on one page becomes "all prose then all tables" — destroys "see Table 1 above" references). The bbox merge is ~30 lines and corrects the most common spec-doc layout.

```kotlin
class PdfPipeline(
    private val tableExtractor: PdfTableExtractor,
    private val proseExtractor: PdfProseExtractor,  // bbox-annotated prose
) {
    fun extract(file: Path): List<DocumentBlock> {
        val tables: List<PositionedBlock<DocumentBlock.Table>> = tableExtractor.extract(file)
        val proseBlocks: List<PositionedBlock<DocumentBlock>> = proseExtractor.extract(file)

        // Each PositionedBlock carries (page, top, bottom, block).
        // Sort by (page, top) for in-flow order.
        val merged = (tables + proseBlocks).sortedWith(compareBy({ it.page }, { it.top }))

        // Suppress phantom Tabula tables: if a Table block's bbox overlaps an
        // already-emitted Paragraph by >70%, drop it (v1 lattice-only mostly avoids
        // this; defensive against any Tabula misclassification).
        return suppressOverlaps(merged).map { it.block }
    }
}
```

`PdfTableExtractor` — **lattice only by default**:
```kotlin
class PdfTableExtractor(private val useStreamFallback: Boolean = false) {
    fun extract(file: Path): List<PositionedBlock<DocumentBlock.Table>> {
        // Per-call instantiation — Tabula/PDFBox are NOT thread-safe per shared instance.
        Loader.loadPDF(file.toFile()).use { document ->
            val extractor = ObjectExtractor(document)
            val pages: PageIterator = extractor.extract()
            val out = mutableListOf<PositionedBlock<DocumentBlock.Table>>()
            while (pages.hasNext()) {
                val page = pages.next()
                val tables = SpreadsheetExtractionAlgorithm().extract(page)
                // Stream mode is OFF by default (review finding: it false-positives
                // on multi-column prose). Behind a setting; default false.
                if (tables.isEmpty() && useStreamFallback) {
                    BasicExtractionAlgorithm().extract(page)  // explicit opt-in only
                } else tables
                tables.forEach { t ->
                    out += PositionedBlock(
                        page = page.pageNumber,
                        top = t.top.toDouble(),
                        bottom = (t.top + t.height).toDouble(),
                        block = t.toDocumentBlockTable(),
                    )
                }
            }
            // Continuation detection: if Table(page=N+1, top<50) immediately follows
            // Table(page=N, bottom>pageHeight-50) AND headers match (or N+1 has no
            // header row), merge rows into a single Table block.
            return mergeContinuations(out)
        }
    }
}
```

Key Tabula API surface: `technology.tabula.ObjectExtractor`, `technology.tabula.PageIterator`, `technology.tabula.extractors.SpreadsheetExtractionAlgorithm` (lattice). `BasicExtractionAlgorithm` (stream) is **opt-in via `documentEnableStreamMode` setting, default false**.

Why lattice-only by default (review finding): stream mode classifies 3+ aligned text blocks as a "table," which routinely produces phantom tables on multi-column prose pages. For spec docs with ruled tables (the dominant case) lattice is sufficient and the false-positive rate drops to ~0.

When falling back is unavoidable for a fixture, the merged output gets an HTML-comment marker `<!-- table-flow:approximate -->` before the table block so downstream consumers (and the LLM) know the placement may not be exact.

**Failure-mode coverage:** the bbox-aware merge resolves: (a) alternating prose/table on one page (correct interleaving), (b) multi-page table continuation (joined into one Table block with shared headers), (c) phantom Tabula tables on multi-column prose (suppressed by overlap detection), (d) page with 3 tables (each lands at correct y-position relative to surrounding prose).

**Tests (the make-or-break suite):**
- **Fixture A:** A 5-page requirements spec PDF with 3 ruled tables → assert all 3 tables extracted, headers correct, every cell value present.
- **Fixture B:** A 2-column whitespace-aligned table → stream mode must produce row count ±0 and column count exact.
- **Fixture C:** Multi-column prose PDF (architecture doc) → reading order verified by checking sentence boundaries are not interleaved.
- **Fixture D:** Page with mixed prose + table → prose appears before table; table appears in correct page section.
- **Fixture E:** Encrypted PDF → graceful failure with `ToolResult.Failure`, not a crash.
- **Fixture F:** Scanned-image PDF (no text layer) → `ToolResult.Failure` with message *"PDF contains no extractable text. v1 does not support OCR; install Tesseract via v2 settings."*

### Step 6 — Wiring (1 day, was ½ — review uplift)

`TikaDocumentExtractor` is the entry point and dispatch. **Three review-driven additions:** (1) global `Semaphore(2)` to bound concurrent extraction memory, (2) ContextClassLoader wrap to avoid the Tika ServiceLoader trap (TIKA-1145), (3) per-call settings read so mid-session changes apply.

```kotlin
class TikaDocumentExtractor(
    private val pdfPipeline: PdfPipeline,
    private val officePipeline: OfficePipeline,
    private val tikaXhtml: TikaXhtmlPipeline,
    private val assembler: MarkdownAssembler,
    private val settings: PluginSettings,
) : DocumentExtractor {
    // Global concurrency cap — IDEA's default heap is 750MB-2GB; two concurrent
    // 100-page PDFs can hit 1GB+ resident. Bound at 2 across the whole agent.
    private val semaphore = Semaphore(2)

    init {
        // TIKA-1145 workaround: assert at construction that parsers are discovered.
        // PluginClassLoader's META-INF/services scanning differs from the system
        // classloader; if Tika finds nothing, every extraction silently returns "".
        val parsers = AutoDetectParser().parsers
        require(parsers.isNotEmpty()) {
            "Tika parser ServiceLoader returned empty under PluginClassLoader. " +
            "Check META-INF/services/org.apache.tika.parser.Parser is on classpath."
        }
        // Defense in depth: assert no OCR parser is registered — even though
        // tika-config.xml excludes Tesseract, the ocr-module is on the classpath
        // (~5MB) and a misconfigured TikaConfig can silently re-enable it.
        require(parsers.none { it.javaClass.simpleName.contains("OCR", ignoreCase = true) }) {
            "OCR parser unexpectedly registered; tika-config.xml may be malformed."
        }
    }

    override suspend fun extract(path: Path, options: ExtractOptions): ToolResult<DocumentContent> =
        semaphore.withPermit {
            withTimeoutOrNull(options.timeoutMs) {
                withContext(Dispatchers.IO) {
                    val previousCcl = Thread.currentThread().contextClassLoader
                    try {
                        // TIKA-1145: ensure Tika finds its parsers via plugin classloader.
                        Thread.currentThread().contextClassLoader = TikaConfig::class.java.classLoader
                        runCatching {
                            // Per-call: read max chars from settings so mid-session changes apply.
                            // Mirrors HttpClientFactory.timeoutsFromSettings() pattern.
                            val maxChars = options.maxChars ?: settings.documentMaxChars
                            val mime = detect(path)
                            val blocks = when {
                                mime == "application/pdf" -> pdfPipeline.extract(path)
                                mime in OFFICE_MIMES -> officePipeline.extract(path, mime)
                                else -> Files.newInputStream(path).use { tikaXhtml.extract(it, mime) }
                            }
                            val (md, truncated) = assembler.assemble(blocks, maxChars)
                            DocumentContent(markdown = md, mime = mime, truncated = truncated, /* ... */)
                        }.fold(
                            onSuccess = { ToolResult.Success(it, summary = summarize(it)) },
                            onFailure = { mapErrorToFailure(it) },
                        )
                    } finally {
                        Thread.currentThread().contextClassLoader = previousCcl
                    }
                }
            } ?: ToolResult.Failure("Document extraction timed out after ${options.timeoutMs}ms")
        }
}
```

Error catalog (full mapping, each with user-facing message — never a stack trace):
- `EncryptedDocumentException` / `PasswordRequiredException` → "Document is password-protected; v1 does not support encrypted documents."
- `OutOfMemoryError` → caught explicitly (not just via `runCatching` on `Throwable`); calls `System.gc()`, returns `ToolResult.Failure("Document too large to process within heap budget; reduce max_chars or use a smaller subset.")`. Note: JVM may still be unstable after OOM — accepted limitation.
- `TikaException` → "Tika could not parse this document: $message"
- `OfficeXmlFileException` → "File appears to be modern Office (.docx/.xlsx/.pptx) but has wrong extension."
- `OldFileFormatException` → "File is in legacy Office 97-2003 format with content unsupported in v1."
- `IOException` (corrupted bytes) → "File appears corrupt or truncated."
- `MalformedByteSequenceException` → "Document encoding could not be determined."
- Unknown → "Document extraction failed: ${type}: ${message}"

### Step 7 — Agent tool wrapper (½ day)

`agent/tools/integration/DocumentTool.kt`:

```kotlin
class DocumentTool(private val extractor: DocumentExtractor) : AgentTool {
    override val name = "read_document"
    override val description = """
        Read text content and metadata from a non-plaintext document file. Supports PDF,
        Word (DOC/DOCX), Excel (XLS/XLSX), PowerPoint (PPT/PPTX), RTF, ODT, EPUB, HTML, CSV.
        Use this when you need to examine a document that read_file rejects — for example a
        requirements spec, a downloaded Jira attachment, or a build-report artifact.

        Returns Markdown. Tables in the source are preserved as Markdown tables, with
        column headers, so you can reason about row/column relationships accurately.
        Multi-column PDFs are read in correct order.

        For scanned image PDFs (no embedded text), this tool returns an error — OCR is
        not available in v1. The cap is 200 K characters; very long documents are
        truncated with a marker.
    """.trimIndent()
    override val parameters = listOf(
        ParameterProperty(name = "path", required = true, description = "Absolute path to the document file"),
        ParameterProperty(name = "max_chars", required = false, description = "Override max extracted characters (default 200000)"),
    )
    override val timeoutMs = 30_000L
    override val outputConfig = OutputConfig.spillIfOver(30_000)  // ToolOutputSpiller per existing pattern

    override suspend fun execute(args: Map<String, Any?>): ToolResult<*> {
        val path = Paths.get(args.getValue("path") as String)
        val maxChars = (args["max_chars"] as? Number)?.toInt() ?: 200_000
        return extractor.extract(path, ExtractOptions(maxChars = maxChars))
    }
}
```

Register **deferred-tier** in `ToolRegistry` so it doesn't burn prompt tokens on sessions that never read documents. `tool_search` activates it on demand. Category: `"file"`.

### Step 8 — Settings + plumbing (½ day)

Per memory `feedback_settings_ui`: any new `PluginSettings` field needs UI.

Add to `PluginSettings`:
- `documentMaxChars: Int = 200_000`
- `documentTimeoutMs: Long = 30_000`
- `documentEnableStreamMode: Boolean = false` — Tabula stream-mode fallback for whitespace tables. Off by default to avoid false-positive tables on multi-column prose.
- `documentOcrEnabled: Boolean = false` (stub for v2; greyed out with "Coming soon" tooltip)

**Settings are read per-call**, never constructor-cached. Mirrors `HttpClientFactory.timeoutsFromSettings()` (per `core/CLAUDE.md` patterns) so mid-session changes apply without IDE restart.

Add a "Documents" group to the existing Tools > Workflow Orchestrator settings page.

### Step 9 — Cross-cutting wires (½ day)

- **Jira:** when `JiraTool.action == "download_attachment"` returns a path with a document MIME, surface the result with a hint string `"You can pass this path to read_document to extract its content."` This is a prompt-engineering tweak, not a behavioural change.
- **Bamboo `download_artifact`:** same pattern when that lands.
- **Drag-and-drop in JCEF chat:** route document MIMEs through `read_document` automatically before the user hits Send. Webview bridge call: `_extractDocument(path)`.

---

## 7. Hardened `tika-config.xml`

Lives at `:core/src/main/resources/tika-config.xml`. Loaded once into a singleton `TikaConfig` at extractor construction:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<properties>
    <parsers>
        <parser class="org.apache.tika.parser.DefaultParser">
            <parser-exclude class="org.apache.tika.parser.external.ExternalParser"/>
            <parser-exclude class="org.apache.tika.parser.ocr.TesseractOCRParser"/>
            <parser-exclude class="org.apache.tika.parser.ocr.TesseractOCRParserConfig"/>
        </parser>
    </parsers>
    <service-loader dynamic="false" loadErrorHandler="THROW"/>
    <encodingDetectors>
        <encodingDetector class="org.apache.tika.parser.txt.UniversalEncodingDetector"/>
    </encodingDetectors>
</properties>
```

**`loadErrorHandler="THROW"`**: review finding — `WARN` silently hides ServiceLoader bugs (TIKA-1145). `THROW` surfaces them at construction time, where the init-block assertion in `TikaDocumentExtractor` catches and converts to a clear plugin-init error.

**Note on OCR module:** `tika-parsers-standard-package` 3.x **does** include `tika-parser-ocr-module` on the classpath (~5 MB JAR). `parser-exclude` removes Tesseract from the parser registry, but the JAR is still present. The init-time assertion in `TikaDocumentExtractor` (Step 6) verifies no OCR parser is registered as a defense-in-depth check.

Plus programmatic limits in `PDFParserConfig`:
- `setMaxMainMemoryBytes(50_000_000)` — bounds PDFBox scratch buffer (NOT total retained `PDDocument` state; that's bounded by the global semaphore in Step 6)
- `setExtractMarkedContent(true)`
- `setSortByPosition(true)`
- `setOcrStrategy(NO_OCR)`

Plus POI-side hardening (NOT covered by tika-config; POI's `XSSFWorkbook` invokes XmlBeans directly):
- `IOUtils.setByteArrayMaxOverride(50_000_000)` set globally at extractor init
- `ZipSecureFile.setMaxEntrySize(100L * 1024 * 1024)` — prevent zip bomb
- `ZipSecureFile.setMinInflateRatio(0.001)` — same

---

## 8. Test Plan

**Unit tests** (per step above): each pipeline tested in isolation with a small fixture matrix.

**Fixture corpus** committed under `:document/src/test/resources/fixtures/`:
- `requirements-spec.pdf` — real-world style with ruled tables (3 tables, 2 cols of prose)
- `architecture-doc.pdf` — multi-column, no tables, 10 pages
- `whitespace-table.pdf` — stream-mode test
- `encrypted.pdf` — error-path test
- `scanned.pdf` — error-path test (no text layer)
- `bug-tracker.xlsx` — 3 sheets with formulas, merged cells, dates
- `design-doc.docx` — interleaved prose + 2 tables + bullet list
- `slides.pptx` — 8 slides, 2 with speaker notes, 1 with a table
- `release-notes.rtf`, `manual.epub`, `report.html`, `data.csv`

**Accuracy tests** (the v1 acceptance bar):
1. For `requirements-spec.pdf`: every cell value from a hand-curated reference must appear in the output Markdown table at the correct row/column. No tolerance for swapped columns.
2. For `bug-tracker.xlsx`: every cell from sheet 1 round-trips exactly.
3. For `design-doc.docx`: tables appear *between* the correct paragraphs (document order preserved).
4. For `architecture-doc.pdf`: a randomly chosen sentence from page 5 column 2 must be intact (no interleaving with column 1).

**Performance tests:**
- 100-page PDF: must complete under 30 s on the CI machine.
- 50K-row XLSX: must complete under 30 s OR truncate cleanly with a marker.

**Robustness tests:**
- Each fixture also tested when (a) corrupted (truncated bytes), (b) wrong extension, (c) zero bytes.

---

## 9. Acceptance Criteria

A v1 PR is ready to merge when:

- [ ] All 12 supported MIME types parse end-to-end into Markdown
- [ ] All 4 accuracy tests pass on the fixture corpus
- [ ] All 3 robustness tests pass (corrupt, wrong-ext, zero-byte each return `ToolResult.Failure` with a useful message — never crash)
- [ ] **Init-time assertion verifies Tika parser registry is non-empty AND contains zero OCR parsers** (catches TIKA-1145 and misconfigured tika-config)
- [ ] **PDFBox version sanity test passes**: a `PDDocument` opened from Tabula's classpath resolves to PDFBox 3.0.5 bytecode (not 2.0.24)
- [ ] `./gradlew verifyPlugin` passes against IDEA 2025.1+ (per `gradle.properties`)
- [ ] **Manual integration test passes against IDEA Ultimate with Database Tools + Big Data Tools plugins enabled** (verifyPlugin alone is insufficient for runtime classloader collisions)
- [ ] Plugin distribution ZIP delta documented in PR description (expected: +45–70 MB)
- [ ] No regressions in `:agent` test suite
- [ ] Settings UI exposes `max_chars`, `timeout`, `enableStreamMode` (default off); OCR toggle is greyed out for v1
- [ ] **Settings are read per-call, not constructor-cached** (verified by mid-test setting change taking effect on next extraction)
- [ ] `tika-config.xml` ships in resources; OCR and external parsers are excluded; `loadErrorHandler="THROW"`
- [ ] Tool description in deferred-tier registry; `tool_search` finds it via "pdf" / "document" / "spec" / "doc" / "xlsx"
- [ ] `download_attachment` follow-up tested end-to-end on a real Jira PDF
- [ ] Memory `project_binary_document_reader_research.md` updated to "SHIPPED v1; v2 pending"

---

## 10. Risk Register (post-review)

| # | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| 1 | **POI/XmlBeans version collision with JetBrains Database/Big Data plugins** | High | High (plugin breakage / silent LinkageError) | Integration-test against IDEA Ultimate **with Database Tools and Big Data Tools plugins enabled** (not just `verifyPlugin`, which only catches API-level binary incompatibility — it does NOT simulate runtime classloader hierarchies). If LinkageError observed, fall back to `UrlClassLoader.build()` delegating to system classloader, per [SpecificLanguages MPS pattern](https://specificlanguages.com/posts/2022-03/15-apache-poi-classloader-hell/). Shadow + Plugin v2 is third-tier (no documented integration). |
| 2 | **Tabula 1.0.5 ships PDFBox 2.0.24 transitively, conflicting with Tika's PDFBox 3.0.5** | High | High (compile/runtime LinkageError) | Gradle `exclude(group = "org.apache.pdfbox")` from Tabula + `constraints { strictly("3.0.5") }`. Runtime test asserts a `PDDocument` opened from Tabula's classpath resolves to PDFBox 3.0.5. If 1.0.5 bytecode breaks against PDFBox 3.x APIs, vendor Tabula `master` HEAD (commit `2cdf3b4`) as local source dep. |
| 3 | **TIKA-1145: Tika ServiceLoader fails inside PluginClassLoader** | High | High (silent empty extracts) | `Thread.currentThread().contextClassLoader = TikaConfig::class.java.classLoader` wrap around every parse; init-time assertion that `parsers.isNotEmpty()`; `loadErrorHandler="THROW"` in tika-config.xml. |
| 4 | **POI/PDFBox/Tabula NOT thread-safe per shared instance** | Medium (agent fires parallel sub-agents per `reference_cline_subagent_task_delegation`) | High (state corruption) | Per-call instantiation only; never cache `XSSFWorkbook`/`Loader.loadPDF()`/`ObjectExtractor`. Add per-path coroutine `Mutex` if needed; call `ThreadLocalUtils.clearAllThreadLocals()` after each extraction (POI 5.2.4+). |
| 5 | **Memory pressure on default 750MB-2GB heap** | Medium | High (OOM) | Global `Semaphore(2)` in `TikaDocumentExtractor` bounds concurrent extraction. `setMaxMainMemoryBytes(50MB)` on PDFBox scratch. Worst-case 100-page PDF can produce 100-300MB resident `PDDocument`. |
| 6 | **OCR module on classpath despite parser-exclude** | Medium | Low (unexpected behaviour if config malformed) | Init-time assertion in `TikaDocumentExtractor`: `parsers.none { it.javaClass.simpleName.contains("OCR") }`. JAR remains (~5MB) but cannot fire. |
| 7 | **log4j-api/slf4j shim collision with IntelliJ Platform** | Medium | Medium (LinkageError) | Plugin codebase already aware: `build.gradle.kts` comment "Bundling a second SLF4J causes LinkageError due to plugin classloader isolation." Exclude `log4j-api`/`log4j-core` transitively from Tika/POI; route logs through platform-provided slf4j. |
| 8 | **OOM on weaponized PDF / zip-bomb DOCX** | Low | Medium | PDFBox `setMaxMainMemoryBytes(50MB)` + POI `ZipSecureFile.setMaxEntrySize` + `setMinInflateRatio(0.001)`. `OutOfMemoryError` caught explicitly (not just `Throwable`); JVM may still be unstable after — accepted limitation. |
| 9 | **XmlBeans XXE against POI's direct XmlBeans path** | Low | Medium | `tika-config.xml` does NOT cover POI's direct path. Add `IOUtils.setByteArrayMaxOverride(50_000_000)` globally at extractor init. CVE-2021-23926 fixed in 5.x; XmlBeans 5.x has no current public CVE. |
| 10 | **Tabula misclassifies prose paragraphs as tables (stream mode)** | High when stream enabled | Medium (phantom tables) | Stream mode is **off by default** in v1; `documentEnableStreamMode` setting opt-in. Lattice-only on default settings. Bbox-aware merge suppresses phantom tables that overlap prose by >70%. |
| 11 | **Multi-page table not joined by Tabula** | High on real spec PDFs | Medium (split tables, second missing headers) | `mergeContinuations(out)` step in `PdfTableExtractor`: if Table(N+1) starts near top AND Table(N) ends near bottom AND headers match (or N+1 has no header row), merge rows. |
| 12 | **PDF with JPEG2000 images** | Low (rare for spec docs) | Low | Tika and PDFBox exclude `jai-imageio-jpeg2000` from production package for legal reasons. Affected PDFs throw `IOException`, caught in `runCatching`. Documented v1 limitation. |
| 13 | **Multi-column PDF reading order still wrong despite `sortByPosition`** | Low | Medium | `sortByPosition=true` is documented fix; if a fixture fails, accept as known v1 limitation and document it. |
| 14 | **Spec PDFs with rotated text / forms / annotations** | Medium | Low (degraded but not wrong) | Document as known limitation; v2 can use PDFBox `PDAcroForm` API. |
| 15 | **XHTMLContentHandler emits unexpected structure for some Tika parsers** | Medium | Medium | `DocumentBlockHandler` defensive: unknown elements treated as paragraphs; not assertions. |
| 16 | **Plugin ZIP > 100 MB total** | Medium | Medium | Measure first; if hit, drop Tika facade and cherry-pick parsers from `tika-parsers-standard` (saves ~10–15MB). Last resort: drop Tika XHTML for non-PDF non-Office and use POI/PDFBox direct. |

---

## 11. v2 Roadmap (Not v1, But Plan Around)

Already-stubbed seams that v2 will use:
- `TransportCapabilities.supportsNativePdfDocumentBlock` flips to `true` per-transport after the empirical probe (matches the 24/24 image-vision probe methodology already used for Sourcegraph).
- `ExtractOptions.includeEmbedded` gets honored: PPTX images become `EmbeddedFileRef` blocks; the Markdown carries `[image: slide-3-fig-1.png]` placeholders the agent can describe via vision tool.
- `ocrStrategy` settings field flips to `AUTO` and pulls in `tika-parsers-extended` (Tesseract). Big-distribution-size cost; gated behind a setting.
- Bbox-coordinate-aware splicing in `PdfPipeline` so tables appear at exact in-flow position, not end-of-page.
- Citation tracking: `DocumentBlock.PageMarker` is already in v1; v2 can have the agent reference *"§3.2, page 12, table 1"*.

---

## 12. Effort Estimate (post-review)

| Step | Days |
|---|---|
| 1. Core types | 0.5 |
| 2. Markdown assembler | 0.5 |
| 3. Tika XHTML pipeline | 1.0 |
| 4. POI Office pipeline | 1.0 |
| 5. PDF pipeline + Tabula + bbox merge | 1.5 |
| 6. Wiring (extractor + dispatch + classloader fix + semaphore) | 1.0 |
| 7. Agent tool wrapper | 0.5 |
| 8. Settings + UI | 0.5 |
| 9. Cross-cutting wires | 0.5 |
| 10. Fixture authoring + accuracy tests | 0.5 |
| **Buffer for classloader integration testing (Database + Big Data plugins)** | **1.5** |
| **Total** | **~9 days** focused |

Realistic calendar: **2-3 weeks** with normal context-switching. Review-driven uplift from 7→9 days is concentrated in (a) classloader integration testing under realistic IDEA Ultimate config, (b) bbox-aware merge in v1, (c) classloader-context wrap and init-time assertions.

---

## 13. Sources

- [Apache Tika `PDFParserConfig` source (GitHub)](https://github.com/apache/tika/blob/master/tika-parsers/src/main/java/org/apache/tika/parser/pdf/PDFParserConfig.java)
- [Apache Tika `XHTMLContentHandler` API](https://tika.apache.org/2.9.0/api/org/apache/tika/sax/XHTMLContentHandler.html)
- [Tika DeepWiki: HTML and XML Parsers](https://deepwiki.com/apache/tika/3.1.2-html-and-xml-parsers)
- [Tabula-java GitHub](https://github.com/tabulapdf/tabula-java)
- [Tabula extraction algorithms (lattice vs stream)](https://github.com/tabulapdf/tabula-java#extraction-algorithms)
- [Camelot wiki: Comparison with other PDF Table Extraction libraries](https://github.com/camelot-dev/camelot/wiki/Comparison-with-other-PDF-Table-Extraction-libraries-and-tools)
- [Apache POI XWPF Quick Guide](https://poi.apache.org/components/document/quick-guide-xwpf.html)
- [Apache POI XSSF/HSSF Spreadsheet Quick Guide](https://poi.apache.org/components/spreadsheet/quick-guide.html)
- [arXiv 2410.09871: A Comparative Study of PDF Parsing Tools](https://arxiv.org/html/2410.09871v1)
- [arXiv 2511.16134: Benchmarking Table Extraction from Heterogeneous Scientific Documents](https://arxiv.org/html/2511.16134v1)
- [Unstract 2026: Best Python Libraries to Extract Tables From PDF](https://unstract.com/blog/extract-tables-from-pdf-python/)

---

*End of v1 implementation plan. Implementer should start with Step 1 and merge incrementally per step where possible.*
