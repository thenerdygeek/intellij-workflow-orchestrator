# Binary Document Reader Tool — Research & Recommendation

**Status:** Research / pre-implementation hand-off
**Author:** Research session 2026-04-30
**Scope:** Adding an agent tool that can read non-code, non-plaintext files (PDF, DOC/DOCX, PPT/PPTX, XLS/XLSX, RTF, ODT, EPUB, optionally OCR-scanned images).
**Audience:** Developer implementing the tool in `:agent` (or a future feature module).
**Branch context:** Researched on `refactor/cleanup-perf-caching`. Implementation will land on a separate feature branch.

---

## 1. Executive Summary

Today the agent's `read_file` tool (`agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/ReadFileTool.kt:31-35`) hard-rejects 17 binary extensions including `pdf`, `doc`, `docx`, `xls`, `xlsx`, `ppt`, `pptx`. The Jira meta-tool can already `download_attachment` (file path returned for binary content; see `JiraServiceImpl.kt:684-733`), but the agent has no way to turn that downloaded file into text the LLM can reason about. The same gap exists for files the user drags into a session, files referenced in PR diffs, and files surfaced by build artifacts.

After surveying the JVM ecosystem and four leading agent IDEs (Cline, Continue.dev, OpenHands, Cursor), the recommendation is:

> **Build a single new agent tool, `read_document`, backed by Apache Tika 3.x's `tika-parsers-standard-package`. Tika is the universal path that works across every supported LLM transport (Anthropic direct, Sourcegraph Cody Enterprise, future OpenAI proxy). Native PDF passthrough is documented as a v2 *Anthropic-direct-only* optimization, gated behind transport capability detection — it must NOT be assumed to work on Sourcegraph.**

This wins on every axis that matters here:

- **Breadth in one dependency.** Tika delegates internally to PDFBox (PDF), POI (DOC/XLS/PPT/DOCX/XLSX/PPTX), and bundled parsers for RTF/ODT/EPUB/HTML/CSV — covering every realistic format with a unified API.
- **Transport-agnostic.** Plain extracted text fits the existing message-building pipeline regardless of which LLM transport is active. Critically, this means it works on Sourcegraph Cody Enterprise — the plugin's primary transport — where native PDF document blocks are unverified and likely unsupported (`reference_sourcegraph_image_transport` confirms only image content blocks are probe-validated; the Internal OpenAPI inventory has no documented PDF document schema).
- **License clean.** Tika, PDFBox, POI, docx4j are all Apache-2.0; Aspose (the proprietary alternative) is per-deployment-licensed and disqualifying for a freely distributed plugin.
- **Differentiation vs peer agents.** Cline, Continue.dev, and OpenHands all *lack* native binary-document reading and rely on user-installed MCP servers or external conversion. Building this in-process gives the plugin a real edge on enterprise workflows (Jira PDFs, requirement documents, Confluence exports).

The estimated dependency cost is roughly 35–55 MB of JARs added to the plugin distribution (dominated by POI's bundled XmlBeans/SchemaBeans). That is large but not unprecedented — it is the same order of magnitude as the JCEF runtime already shipped. A "lean" alternative using only PDFBox + the POI-OOXML subset (~12 MB) is documented below if size pressure increases.

The rest of this document compares all four candidate paths, calls out IntelliJ Platform classloader gotchas, and sketches a concrete integration path with file references into the existing codebase.

---

## 2. Problem Framing

The agent reads files for three distinct downstream purposes, and the right answer differs slightly per purpose:

1. **Code-adjacent context** — design docs, requirement specs, RFCs, READMEs in DOCX, technical PDFs. The agent only needs the **prose text** to reason, summarize, or quote. Tables and figures are nice-to-have, not load-bearing.
2. **Structured data** — XLSX bug logs, CSV exports, an OPA-style decision matrix in a spreadsheet. The agent needs **cell-level fidelity** (rows, columns, sheet names) and ideally typed values.
3. **Visual / layout-heavy documents** — UX specs, architecture diagrams embedded in PDFs, scanned contracts, slide decks where the speaker notes and visual layout both matter. Plain text loses too much; **the model itself should see the rendering**.

Conflating these into one extraction pipeline is the trap most generic "PDF reader" tools fall into. Tika's plain-text output handles category 1 cleanly. Category 2 needs a structured extractor (Tika+POI's row-based output, or a thin wrapper that emits TSV/Markdown tables). Category 3 is best served by **not extracting at all** and instead passing bytes through to a vision-capable model.

The implementation should expose this as one user-facing tool (`read_document`) with an optional `mode` parameter or auto-detection, rather than three separate tools — one tool keeps the agent's prompt budget clean and follows the existing meta-tool pattern (`JiraTool`'s `action` enum).

---

## 3. Decision Matrix — Four Candidate Paths

| | A. Apache Tika 3.x | B. Native model PDF | C. PDFBox + POI direct | D. External MCP server |
|---|---|---|---|---|
| **Format coverage** | ~1,400 MIME types; PDF/Office/RTF/ODT/EPUB/HTML | PDF only (other formats: not natively supported) | PDF + Office only | Depends on chosen server (most: PDF only) |
| **License** | Apache-2.0 | N/A (API call) | Apache-2.0 | Varies (often MIT) |
| **Layout/table fidelity** | Plain text + structural events; tables flattened | Excellent (model sees rendering) | Same as Tika (Tika delegates here) | Varies; usually plain text |
| **Footprint added to plugin** | ~35–55 MB JARs | 0 (uses existing HTTP) | ~12 MB JARs | 0 (out-of-process) |
| **Threading** | Synchronous; needs Dispatchers.IO + per-tool timeout | Async; uses existing OkHttp client | Same as Tika | IPC; complexity of process management |
| **Token economy** | Cheap (extract once, cache) | Expensive (PDF bytes count as image tokens × 100 pages max) | Cheap | Cheap |
| **Works offline** | Yes | No (requires API roundtrip) | Yes | Depends |
| **Transport support** | Yes — works on any transport (Anthropic direct, Sourcegraph Cody, future OpenAI proxy) | **Anthropic direct only.** Sourcegraph Cody Enterprise has NO documented `document` block support — only image blocks are probe-confirmed. OpenAI partial. | Yes | Yes |
| **IntelliJ classloader risk** | Medium (transitive deps) | None | Low | None (out-of-process) |
| **Maintenance** | Single Apache release cadence | Anthropic API contract + per-transport capability matrix | Two Apache releases to track | External server lifecycle |
| **Best for** | Default path: any document, any transport | Future v2 enhancement, Anthropic-direct only, capability-gated | Lean build; explicit POI feature use | Bypass JVM dep cost entirely |

**The recommendation is A as the universal path. B is deferred to v2 and must be capability-gated** — only emit a `document` content block when the active transport has been probe-confirmed to support it. Default behaviour for every transport, including Sourcegraph (the plugin's primary one), is Tika extraction. D (the Cline/Continue approach) is rejected — see §6.

---

## 4. Path A — Apache Tika 3.x (Recommended Default)

### 4.1 Why Tika

Apache Tika is the de facto JVM document-extraction library and is the upstream that Solr, Elasticsearch ingest, Lucidworks Fusion, Apache Nutch, and most enterprise content platforms use. The architecture is deliberately a *facade*: Tika's `AutoDetectParser` reads the input stream, identifies the MIME type, and dispatches to the appropriate concrete parser:

- `PDFParser` → uses Apache PDFBox 3.x
- `OfficeParser` (legacy `.doc/.xls/.ppt`) → uses Apache POI HSSF/HSLF/HWPF
- `OOXMLParser` (modern `.docx/.xlsx/.pptx`) → uses Apache POI OOXML
- `RTFParser`, `OpenDocumentParser`, `EpubParser`, `HtmlParser`, `TextAndCSVParser`, etc. → bundled

This means a single dependency replaces what would otherwise be 5–6 separate library integrations and keeps the agent's tool surface to one `read_document` action regardless of input format.

### 4.2 Module structure

Tika 3.x breaks parsers into three tiers (Apache Tika docs):

| Artifact | Contents | Notes |
|---|---|---|
| `tika-core` | MIME detection only, no parsers | ~1 MB; insufficient by itself |
| `tika-parsers-standard-package` | Core + PDFBox + POI + RTF/ODT/EPUB/HTML/CSV; no native code, no network | **The right choice for this plugin** |
| `tika-parsers-extended` | Adds OCR (Tesseract), web fetchers, native bridges | Adds ~100+ MB; defer to v2 |
| `tika-parsers-ml` | Adds DL4J, language ID models | Massive; not needed |

The standard package is what the plugin should depend on. It does not pull in any plugin-incompatible native code, does not initiate network requests on its own, and is fully reproducible from Maven Central.

### 4.3 Minimal Kotlin usage

The embedded API is intentionally small. From the Tika 3.2.3 docs:

```kotlin
import org.apache.tika.parser.AutoDetectParser
import org.apache.tika.parser.ParseContext
import org.apache.tika.metadata.Metadata
import org.apache.tika.sax.BodyContentHandler

suspend fun extract(file: Path, charLimit: Int = 200_000): ExtractResult = withContext(Dispatchers.IO) {
    val parser = AutoDetectParser()
    val handler = BodyContentHandler(charLimit) // -1 = unlimited; we cap to protect prompt budget
    val metadata = Metadata().apply { set(Metadata.RESOURCE_NAME_KEY, file.fileName.toString()) }
    val context = ParseContext()
    Files.newInputStream(file).use { stream ->
        parser.parse(stream, handler, metadata, context)
    }
    ExtractResult(
        text = handler.toString(),
        contentType = metadata.get(Metadata.CONTENT_TYPE),
        title = metadata.get(Metadata.TITLE),
        author = metadata.get(Metadata.AUTHOR),
        pageCount = metadata.get("xmpTPg:NPages")?.toIntOrNull(),
    )
}
```

Three things to call out here:

1. **`BodyContentHandler(charLimit)`** caps extraction at a fixed character budget. Without it, a 500-page legal PDF will dump megabytes of text into the prompt. The plugin's existing `read_file` uses 10 MB / 200-line limits; mirror that policy here (e.g., 200 K characters ≈ 50 K tokens, well under the 200 K context window).
2. **`ParseContext`** is where embedded-document handling, OCR, and language detection are configured. Default behavior is "extract embedded files inline as text" which is what we want.
3. **The whole call is synchronous CPU+IO.** Wrap in `Dispatchers.IO`, use `withTimeoutOrNull(30_000)` like the agent's other tools, and treat malformed PDFs as recoverable errors (return a `ToolResult.Failure` rather than throwing).

### 4.4 What Tika does badly

Tika's plain-text output **flattens tables**. A multi-cell XLSX row like `| Bug ID | Severity | Status |` comes out as `BUG-1\tHIGH\tOPEN`, which loses column headers when the agent reads it sequentially. For spreadsheet-heavy use cases, the right fix is a separate `read_spreadsheet` extractor that uses POI's `XSSFWorkbook` directly and emits Markdown tables with column headers. This is a v2 enhancement; the v1 should ship Tika's flat output and document the caveat in the tool description.

PDF tables are even worse — Tika returns text in reading order, but two-column PDFs are notorious for interleaving. For PDFs specifically, **route to Path B (native model PDF) when available**.

---

## 5. Path B — Native Model PDF Support (Deferred v2, Anthropic-Direct Only)

**Important caveat up front:** this path is **not** part of the v1 recommendation. It is documented here so the v1 architecture leaves room for it without forcing a redesign later. The plugin's primary LLM transport is Sourcegraph Cody Enterprise, and per `reference_sourcegraph_image_transport` in MEMORY only image content blocks are probe-confirmed (`/.api/completions/stream` v8/v2 with `speaker`/`maxTokensToSample`, all 6 Claude 4.5 vision models passing 24/24). The Sourcegraph Internal OpenAPI inventory contains no `document` block schema. Until that path is empirically probed, **assume PDF document blocks DO NOT work on Sourcegraph** and route every PDF through Tika regardless of size.

The Anthropic Claude API (direct, not via Sourcegraph) supports PDFs as a first-class content block:

```json
{
  "type": "document",
  "source": {
    "type": "base64",
    "media_type": "application/pdf",
    "data": "<base64-encoded-bytes>"
  }
}
```

Limits as of 2026: **32 MB max file size, 100 pages max**, supported on Claude 3.5 and later including the active `claude-opus-4-7` family (Claude API docs). The Files API beta also accepts PDFs uploaded once and referenced by ID, which is ideal for prompt caching.

The model receives both the rasterized page images and the embedded text layer. This means:

- **Tables, equations, charts, and figures are preserved** because the model literally sees the page.
- **Token cost is high.** A 100-page PDF burns roughly 1500–2500 tokens *per page* in image tokens. That is the trade-off for fidelity.
- **It's Anthropic-only today.** The plugin already supports Sourcegraph Cody Enterprise as an alternate transport (see `reference_sourcegraph_image_transport` in MEMORY.md — all 6 Claude 4.5 vision models pass 24/24 image tests via the Sourcegraph route). PDF support there is unverified and likely not yet wired; to be probed before the hybrid path can claim universal coverage.

### 5.1 Capability-gated routing (v2 design, NOT v1)

If/when this is enabled, the routing decision lives inside the tool layer:

```
val transport = activeTransportCapabilities()      // Anthropic direct? Sourcegraph? OpenAI?
if file.extension == "pdf"
    and transport.supportsNativePdfDocumentBlock   // FALSE today for Sourcegraph until probed
    and file.size <= 32 MB
    and (page_count == null or page_count <= 100)
then
    return DocumentContentBlock(base64(file))   # let the model see it (Anthropic direct only)
else
    return TextContentBlock(tika.extract(file))   # the universal v1 path
```

`transport.supportsNativePdfDocumentBlock` must default to `false` for any transport that has not been probe-validated, exactly the way image-vision support was empirically validated for Sourcegraph (24/24 image tests). No transport gets opted in by inference — only by probe.

### 5.2 Why this is v2, not v1

Three reasons B cannot replace A *and* should not ship in v1:
1. **Sourcegraph (primary transport) is unverified.** Shipping native-PDF as default would silently degrade or fail on Sourcegraph, which is the dominant configuration. Tika-only in v1 means consistent behaviour for every user from day one.
2. PDFs are only one of seven formats. Word, Excel, PowerPoint have no native pass-through anywhere.
3. Page count over 100 or size over 32 MB happens routinely with engineering specs; the Tika fallback is mandatory regardless.

**Concrete v1 behaviour:** every PDF goes through Tika. No `document` block is ever emitted. The transport-capability hook lands as a stub returning `false` so v2 can flip it on per-transport after probing.

---

## 6. Path D — External MCP Server (Why Not to Follow Cline)

Cline, Continue.dev, and OpenHands all explicitly punt on binary document reading and rely on user-installed MCP servers for it (Cline GitHub Discussion #883; Cline docs on MCP servers). The community pattern is `pdf-reader-mcp` or similar standalone Node/Python services that expose `read_pdf` as an MCP tool.

This is a reasonable choice for them because (a) VSCode extensions ship a JS runtime, not a JVM, so they would have to bundle Python or a WASM PDF parser; (b) MCP gives them a marketplace dynamic. Neither rationale applies here:

- **The plugin already runs on a JVM.** Tika is a `gradle.kts` line away. Adding an external service means a second runtime, a second install path, and a second failure mode for users who already had to install the IDEA plugin.
- **The plugin already has an in-process integration story** (`AgentTool` interface, `ToolRegistry` 3-tier, `ToolResult<T>`). Building `read_document` as a first-class agent tool keeps the UX consistent with `read_file` and `download_attachment`.
- **Cross-platform support.** The MEMORY note `user_platform.md` confirms the user develops on macOS and tests on Windows. Bundling a Python MCP server doubles the QA matrix.

The plugin should ship `read_document` in-process. MCP support remains a future option for users who want to plug in their own custom extractor (e.g., a domain-specific contract-parsing service), but it is not the primary path.

---

## 7. How Peer Agent Platforms Compare

| Platform | PDF reading | DOCX/XLSX | Image OCR | Approach |
|---|---|---|---|---|
| **Cline** (VSCode) | Via MCP only; no built-in | Via MCP only | None native | "Markdownify"-style external conversion recommended in docs |
| **Continue.dev** | None native; MCP via tool calls | None native | None | Tool-call interface; user provides extractor |
| **OpenHands SDK** | None in standard tools (FileEditorTool, TerminalTool) | None | None | Built-ins are code-only |
| **Cursor** | Read via @ mention; closed-source extractor | Same | Image vision via Claude | Proprietary; uses upstream model PDF support |
| **Aider** | Excludes binaries from repo map; manual user pre-conversion | Same | None | Out of scope for terminal coder |
| **LangChain / LlamaIndex** (frameworks) | Multiple loaders: PyPDFLoader, PyMuPDFLoader, UnstructuredPDFLoader | UnstructuredWordDocumentLoader | Tesseract-backed | Pluggable loader abstraction |

The Python ecosystem (LangChain, LlamaIndex) treats this as a solved problem with a `DocumentLoader` interface and ~6 implementations to choose from per format. The JVM equivalent is essentially "use Tika or write your own" — there is no widely-adopted JVM equivalent of `unstructured.io`.

**Strategic takeaway:** building first-class binary-document reading puts the plugin meaningfully ahead of every JetBrains-targeted competitor today. Cursor is close, but proprietary; the open-source field is wide open.

---

## 8. IntelliJ Platform Integration Concerns

### 8.1 Classloader hygiene

IntelliJ's `PluginClassLoader` linearization (JetBrains plugin SDK docs on Plugin Dependencies) means that if the plugin pulls in a transitive dependency that another installed plugin also bundles at a different version, the parent classloader resolves to a single version and one of the two plugins gets a `NoSuchMethodError` at runtime. POI is a notorious offender because some other JetBrains plugins (notably the Database tools and Big Data plugin) also bundle POI transitively at a *different* version.

**Mitigation:**
1. Use Gradle's `implementation` configuration (not `compileOnly`) so the JARs are packaged into the plugin distribution.
2. Add `<depends optional="true">` for any IntelliJ plugin that bundles POI, and gate Tika initialization on its absence — *or* shade the Tika dependency tree into a relocated package using the Gradle Shadow plugin. The latter is heavyweight but eliminates collision risk entirely.
3. Verify with `./gradlew verifyPlugin` and the Plugin Verifier action against several IntelliJ versions; classloader collisions surface as `ClassCastException` or `LinkageError` in the verifier output.

The build-cache trap from `CLAUDE.md` ("suspend signature changes") is a related JVM linkage issue and a reminder that this kind of bug is not theoretical on this codebase.

### 8.2 Plugin distribution size

Current plugin ZIP size (per `gradle.properties` releases) is in the low tens of MB. Tika-parsers-standard-package adds roughly:

- `tika-core`: ~1 MB
- `tika-parsers-standard`: ~3 MB
- `pdfbox` + `fontbox` + `pdfbox-io`: ~9 MB
- `poi` + `poi-ooxml` + `poi-ooxml-lite`: ~8 MB
- `xmlbeans`: ~3 MB
- `xerces`/`xalan`: ~5 MB
- Misc commons-* + log4j-api shim: ~4 MB

Net add ≈ 35–55 MB depending on Gradle's deduplication. For comparison, JCEF is ~70 MB on macOS aarch64, so this is in the same ballpark and not unprecedented. If size becomes a hard concern, **Path C** (PDFBox + POI direct, dropping Tika's facade and unused parsers) shaves it to ~12 MB at the cost of explicit per-format integration code.

### 8.3 Threading

Tika parsing is **CPU-bound and synchronous**. The agent's existing pattern (`HttpClientFactory.timeoutsFromSettings()`, `withTimeoutOrNull` per tool, `Dispatchers.IO` for blocking work) maps cleanly:

- Run `parser.parse()` inside `withContext(Dispatchers.IO)`.
- Apply a 30-second per-tool timeout (longer than typical 120-second `run_command` is unnecessary; documents that take >30s to parse are pathological).
- Catch `TikaException`, `IOException`, `OutOfMemoryError` (yes, really — malformed PDFs can hit the 256 MB plugin heap) and return `ToolResult.Failure` with a useful error message, never throw out of the tool.

### 8.4 Memory safety

A weaponized PDF or zip-bomb DOCX can exhaust the heap. Tika has built-in protections (`SecureContentHandler` for entity expansion, configurable max embedded depth). Configure these in a shared `TikaConfig` singleton owned by `:core/services/`:

```kotlin
val config = TikaConfig::class.java
    .getResourceAsStream("/tika-config.xml")
    .use { TikaConfig(it) }
```

The XML config sets `maxStringLength`, `maxEmbeddedDocs`, and disables network-fetching parsers explicitly. Ship a `tika-config.xml` resource file with the plugin.

---

## 9. Recommended Architecture

Following the plugin's "core interface → ToolResult → feature impl → agent tool wrapper" rule (`CLAUDE.md` Service Architecture section):

```
:core
  api/DocumentExtractor.kt              ← interface, returns ToolResult<DocumentContent>
  model/DocumentContent.kt              ← data class: text, mime, pageCount, metadata, attachmentBytes?
  resources/tika-config.xml             ← hardened limits, no network, no OCR

:document  (NEW feature module, OR fold into :core if size is small)
  service/TikaDocumentExtractor.kt      ← AutoDetectParser, BodyContentHandler, dispatchers
  service/NativePdfPassthrough.kt       ← bytes-to-base64 + media-type tag
  service/HybridDocumentExtractor.kt    ← decides Tika vs native by file size + active model
  build.gradle.kts                      ← tika-parsers-standard-package dep

:agent
  tools/integration/DocumentTool.kt     ← AgentTool wrapper, deferred-tier registration
  AgentTool description: short prose +  ← see § 9.2 below
    parameter matrix
```

The `:core` interface is `DocumentExtractor.extract(path: Path, options: ExtractOptions): ToolResult<DocumentContent>`. The hybrid implementation lives in the new module (or in `:core` if we don't want a 10th module). The agent tool is **deferred-tier** (per `ToolRegistry`), discovered via `tool_search` so it does not eat prompt budget on sessions that never touch documents.

### 9.1 ExtractOptions parameter sketch

```kotlin
data class ExtractOptions(
    val maxChars: Int = 200_000,          // ≈ 50K tokens, sane default
    val preferNativeForPdf: Boolean = true, // Path B opt-in
    val includeMetadata: Boolean = true,
    val extractEmbedded: Boolean = false,   // images-in-DOCX off by default
    val timeoutMs: Long = 30_000,
)
```

### 9.2 Tool description (LLM-facing prose)

Match the existing style (`ReadFileTool`, `JiraTool`):

> Read text content and metadata from a non-plaintext document file (PDF, Word, Excel, PowerPoint, RTF, ODT, EPUB). Use this when you need to examine documents that `read_file` rejects — for example, a requirement spec, a downloaded Jira attachment, a PR design doc, a build artifact. Returns extracted plain text plus structural metadata (title, author, page count). For PDFs on Anthropic models, you may receive the rendered document directly so you can see tables and figures. Cap is 200K characters; very large documents are truncated. Embedded images are not extracted by default.

### 9.3 Where it plugs into existing flows

- **Jira download_attachment** (`JiraTool.kt:82`, `JiraServiceImpl.kt:684-733`): when the returned `AttachmentContentData.filePath` points at a `.pdf/.docx/.xlsx`, the agent has no good next step today. With `read_document`, the natural pattern is `download_attachment` → `read_document(path)`. Optional follow-up: extend `download_attachment` to auto-extract for known document MIMEs, returning text inline.
- **Bamboo `download_artifact`** (a memory note flags this as a missing meta-tool action): same pattern — download then read. Build artifacts are often DOC/PDF compliance reports.
- **PR review** (the shipped `:pullrequest` PR Review Workflow per memory `project_pr_review_workflow.md`): if a PR includes a binary doc as part of the diff (rare but happens for design specs), `read_document` lets the AI Review tab analyze it.
- **User-dragged files via the chat UI**: the JCEF chat (`HistoryView` React) already handles file upload UI; the message-building pipeline can route document drops to `read_document` automatically.

---

## 10. Roadmap

**v1 (recommended initial implementation) — Tika-only, transport-agnostic:**
- `read_document` tool, deferred-tier, Tika standard package
- **Every** PDF goes through Tika extraction (no native passthrough yet)
- Wired to Jira `download_attachment` follow-up flow
- Caps: 200K chars text, 30s timeout
- Hardened `tika-config.xml`
- Land `TransportCapabilities.supportsNativePdfDocumentBlock` as a stub returning `false` so v2 can flip it on without an architectural change

**v2 (after v1 lands):**
- Probe Anthropic direct (likely already known to work) and Sourcegraph Cody Enterprise for `document`-block PDF support — same empirical methodology used for the image-vision probes (24/24 fixtures)
- Flip `TransportCapabilities` per-transport based on probe results; route PDFs natively only where probe-confirmed
- `read_spreadsheet` action with Markdown-table output (POI direct)
- OCR opt-in via `tika-parsers-extended` (Tesseract) — gate behind a setting because of the +100 MB cost
- Embedded-image extraction for slide decks (PPTX → image refs the agent can then describe)

**v3 (speculative):**
- Streaming extraction for very large docs (Tika 3 supports `XHTMLContentHandler` for incremental output)
- Citation tracking (page-N from PDF) so the agent can reference "page 12, table 3" — Tika emits `<div class="page">` markers in XHTML mode that can be parsed for this

---

## 11. Risks & Open Questions

1. **POI version conflicts with other JetBrains plugins.** Mitigation: shade or `verifyPlugin` discipline. *Risk: medium; impact: high (plugin breakage).*
2. **OOM on weaponized inputs.** Mitigation: `maxStringLength` in tika-config + the existing 256 MB heap headroom. *Risk: low; impact: medium (single-session crash, recoverable).*
3. **Sourcegraph Cody PDF transport.** Unprobed. v1 sidesteps this entirely by using Tika for all PDFs on all transports; v2 gates native passthrough behind an empirical probe per transport (mirroring the image-vision probe methodology that produced 24/24 fixtures).
4. **Plugin size growth.** 35–55 MB add. *Mitigation if pressured*: Path C (PDFBox + POI-OOXML only).
5. **Tika parser bugs.** Tika has a long history of zero-day input handling bugs (see Apache Tika CVEs). Run with the hardened config and treat parsing as untrusted.

---

## 12. Sources

**Apache Tika (primary)**
- [Apache Tika 3.2.3 Getting Started](https://tika.apache.org/3.2.3/gettingstarted.html)
- [Apache Tika Supported Document Formats (2.0)](https://tika.apache.org/2.0.0/formats.html)
- [tika-parsers-standard-package on Maven Central](https://central.sonatype.com/artifact/org.apache.tika/tika-parsers-standard-package)
- [Apache Tika GitHub](https://github.com/apache/tika)
- [Apache Tika License](https://tika.apache.org/license.html) (Apache-2.0)
- [Apache Tika 3.1.1 PDF Parser DeepWiki](https://deepwiki.com/apache/tika/3.1.1-pdf-parser)
- [Lucidworks: Content Extraction with Tika](https://lucidworks.com/blog/content-extraction-with-tika)
- [Baeldung: Content Analysis with Apache Tika](https://www.baeldung.com/apache-tika)

**Apache PDFBox / POI**
- [Apache PDFBox License (ASL 2.0)](https://docs.oracle.com/cloud/latest/big-data-discovery-cloud/BDDLG/rl_apache_license_for_pdfbox.htm)
- [PDFParser via PDFBox in Tika](https://cwiki.apache.org/confluence/pages/viewpage.action?pageId=109454066)

**Anthropic native PDF**
- [Anthropic Claude API: PDF support](https://platform.claude.com/docs/en/build-with-claude/pdf-support)
- [Anthropic Files API](https://docs.claude.com/en/docs/build-with-claude/files)
- [Anthropic cookbook: pdf_upload_summarization](https://github.com/anthropics/anthropic-cookbook/blob/main/misc/pdf_upload_summarization.ipynb)
- [Towards Data Science: Anthropic PDF Processing API intro](https://towardsdatascience.com/introducing-the-new-anthropic-pdf-processing-api-0010657f595f/)

**Peer agent platforms**
- [Cline GitHub](https://github.com/cline/cline)
- [Cline Discussion #883: PDF / RAG support](https://github.com/cline/cline/discussions/883)
- [Cline MCP server marketplace blog](https://cline.bot/blog/supercharge-your-cline-workflow-7-essential-mcp-servers)
- [Continue.dev: How Agent Mode Works](https://docs.continue.dev/ide-extensions/agent/how-it-works)
- [Continue.dev: Quick Start](https://docs.continue.dev/ide-extensions/agent/quick-start)
- [OpenHands Software Agent SDK](https://docs.openhands.dev/sdk)
- [LangChain UnstructuredPDFLoader](https://docs.langchain.com/oss/python/integrations/document_loaders/unstructured_pdfloader)
- [LangChain document_loaders reference](https://reference.langchain.com/python/langchain-community/document_loaders)
- [LangChain PDF loader source](https://api.python.langchain.com/en/latest/_modules/langchain_community/document_loaders/pdf.html)

**Licensing & alternatives**
- [Aspose Pricing & Licensing](https://purchase.aspose.com/pricing/) (commercial; rejected)
- [Aspose vs Docx4j feature comparison](https://blog.aspose.com/total/compare-aspose-for-java-features-and-usage-with-docx4j/)
- [Apache Software Foundation Licenses overview](https://www.apache.org/licenses/)

**IntelliJ Platform**
- [JetBrains: Plugin Dependencies](https://plugins.jetbrains.com/docs/intellij/plugin-dependencies.html)
- [JetBrains forum: Classloader problems](https://intellij-support.jetbrains.com/hc/en-us/community/posts/206794635-Classloader-problems-)
- [JetBrains forum: Plugin dependency / class loading problem](https://intellij-support.jetbrains.com/hc/en-us/community/posts/206003909-Plugin-dependency-class-loading-problem)
- [JetBrains forum: JAR version conflicts in JPS plugin](https://intellij-support.jetbrains.com/hc/en-us/community/posts/207294885-How-to-fix-jar-version-conflicts-in-JPS-plugin)

---

*End of research document. Implementation can be planned against §9 with the v1 scope from §10.*
