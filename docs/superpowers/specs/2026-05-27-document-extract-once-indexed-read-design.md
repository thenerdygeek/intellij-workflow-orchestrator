# Design: Extract-Once, Indexed Document-Read Pipeline

**Date:** 2026-05-27
**Status:** Draft (awaiting spec review)
**Module(s):** `:core` (interfaces + models), `:document` (impl), `:agent` (`DocumentTool`)

## Problem

`read_document` (`agent/tools/integration/DocumentTool.kt`) re-extracts the **entire**
document on **every** chunk read, then discards everything except a `substring()` slice
(`DocumentTool.kt:390`). `TikaDocumentExtractor.extract()` has no caching — `doExtract`
runs cold every call. For PDFs, `PdfPipeline.extract()` opens the file three times and runs
Tabula lattice on **all** pages regardless of table presence
(`PdfPipeline.kt:87-90`, `:51-56`).

Consequences for a 200-page PDF:
- A 6-chunk read performs ~6 full Tabula+Tika+PDFBox extractions of all 200 pages.
- A single full Tabula pass can itself exceed the 30s `read_document` timeout
  (`DocumentTool.timeoutMs = 30_000L`). On timeout there is no artifact, so the retry
  re-extracts from cold — the failure is self-perpetuating.

This is a **design flaw**, not a tuning problem: extraction (expensive, should happen once
per document version) is wrongly nested inside serving (cheap, per call).

## Goal

Separate **extraction** from **serving** so extraction runs **once ever** per document
content, producing a persisted artifact + structural index; serving becomes a pure,
cheap read over the frozen artifact.

## Locked Decisions

| Decision | Choice |
|---|---|
| Scope | All formats `read_document` handles (PDF, DOCX, XLSX, PPTX, RTF, ODT, EPUB, HTML, CSV) |
| Read cursor | Stable char `offset` into the persisted artifact + semantic anchors (`page`, `section`) resolved via the index. Backward-compatible with today's `offset`/`max_chars`. |
| Extraction trigger | Lazy on first read, single-flight |
| Extraction budget | Background job with its own bounded budget **and** leaner per-page extraction |
| Artifact storage | **Session-scoped** (tier 2), content-hash (SHA-256) keyed → extract-once-*per-session*. Cleaned on session reset. |

> **Revision 2026-05-27:** storage changed from cross-session (tier 3) to **session-scoped**
> (tier 2) per user decision "cache can be for one session." This resolves the plaintext
> cross-session privacy concern and removes cross-project `search_code` leakage. The cost is
> re-extraction when the same document is read in a different session — acceptable, since within
> a session (the common multi-chunk read) extraction still happens exactly once.

## Architecture

Two layers with a clean seam:

- **Extraction layer** → produces a persisted **artifact** (`content.md`) + **index**
  (`index.json`) keyed by content hash, within the current session's cache dir. Runs once per
  session per document content.
- **Serving layer** → resolves a cursor against the index and slices the artifact. Never
  touches Tika / Tabula / PDFBox.

### Module layering (respects `:agent → :core` only)

```
:core      DocumentArtifactService (interface) + models
              ↑ impl                              ↑ consumed by
:document  TikaDocumentArtifactService          :agent  DocumentTool
           (wraps existing TikaDocumentExtractor)
```

### `:core` — interfaces + models

**`ToolResult` disambiguation (review C3):** two `ToolResult` types exist —
`com.workflow.orchestrator.core.services.ToolResult<T>` (generic, returned by all `:core`
service interfaces, incl. today's `DocumentExtractor.extract()`) and
`com.workflow.orchestrator.agent.tools.ToolResult` (non-generic, the tool-execution result
`AgentLoop` consumes). The `:core` interface returns the **generic core** type; the `:agent`
`DocumentTool` maps it into the agent `ToolResult` at the tool boundary — exactly as
`DocumentTool.execute()` already converts `core.services.ToolResult<DocumentContent>` today.

```kotlin
// core/services/DocumentArtifactService.kt
import com.workflow.orchestrator.core.services.ToolResult   // the GENERIC core type
interface DocumentArtifactService {
    /** High-level entry: ensure artifact (single-flight), resolve cursor, return slice. */
    suspend fun read(path: Path, cursor: DocumentCursor, maxChars: Int?): ToolResult<DocumentSlice>
}

// core/model/Document*.kt
sealed interface DocumentCursor {
    data class Offset(val value: Int) : DocumentCursor   // default; value=0 → start
    data class Page(val number: Int) : DocumentCursor    // resolves via index
    data class Section(val heading: String) : DocumentCursor
}

data class DocumentSlice(
    val content: String,
    val startOffset: Int,
    val endOffset: Int,
    val remaining: Int,
    val pageOfStart: Int?,      // for the continuation hint
    val totalPages: Int?,
    val truncated: Boolean,
)

data class DocumentArtifact(
    val contentHash: String,
    val mime: String,
    val contentLength: Int,
    val pageCount: Int?,
    val contentPath: Path,      // content.md
    val indexPath: Path,        // index.json
)

data class DocumentIndex(
    val pages: List<Anchor>,    // pageNumber → charOffset
    val sections: List<Anchor>, // heading → charOffset
) { data class Anchor(val key: String, val offset: Int) }

sealed interface ExtractionStatus {
    object NotStarted : ExtractionStatus
    data class InProgress(val percent: Int?) : ExtractionStatus
    data class Ready(val artifact: DocumentArtifact) : ExtractionStatus
    data class Failed(val reason: String) : ExtractionStatus
}
```

### `:document` — `TikaDocumentArtifactService` impl

Reuses the existing `TikaDocumentExtractor` / `PdfPipeline` / `OfficePipeline` /
`TikaXhtmlPipeline` / `MarkdownAssembler` for the actual parse. Adds:

- **Content hashing** — streaming SHA-256 over file bytes, memoized in-memory by
  `(absolutePath, mtime, size)` so warm reads don't re-hash. Hash cost ≪ extraction cost.
- **Index building** — the assembler already emits `DocumentBlock.PageMarker`; record each
  marker's char offset during assembly → free page→offset map. Headings (`DocumentBlock`
  heading variants) → section→offset map. Index is a byproduct of one extraction pass.
- **Single-flight** — `ConcurrentHashMap<contentHash, Deferred<DocumentArtifact>>`; concurrent
  reads of the same doc join one extraction. The existing `Semaphore(2)` (OOM guard for
  parallel heavy extractions) is retained. **Entry lifecycle (review I2):** the map entry is
  **removed on completion** (success *or* failure) so the map never accumulates negative
  entries. Negative caching is handled purely by the on-disk `failure.json` (below): before
  launching a new `Deferred`, the service checks for a non-expired `failure.json` and short-
  circuits to `Failed` without re-extracting. This makes the negative cache survive JVM restart
  and keeps the in-memory map bounded by in-flight count.
- **Background job (review I1, I4)** — extraction is `launch`ed on a **session-scoped child
  `SupervisorJob`** derived from the service's injected `cs: CoroutineScope`. The service is
  `@Service(Service.Level.PROJECT)` and takes `cs` per the Phase-4 injected-scope convention
  (no ad-hoc `CoroutineScope(...)` allocation). The child scope is **cancelled on session
  reset** (wired through `SessionDisposableHolder`/`resetForNewChat`), so a new chat tears down
  any in-flight extraction and its now-orphaned session cache. The job runs on `Dispatchers.IO`,
  **not** the read tool's coroutine — a single read timing out never cancels it.
  - **Budget is wall-clock from job creation**, *including* time spent waiting on the
    `Semaphore(2)` permit. `documentExtractionJobTimeoutMs` (default 300_000ms / 5 min) wraps
    the whole job (permit-wait + extraction). If the permit wait alone exhausts the budget, the
    job resolves `Failed(timeout)`. This is distinct from the 30s **serving** timeout
    (`documentTimeoutMs`). Note: when a cold read joins a job queued behind two others, the
    bounded serving-side await (below) is unlikely to succeed and will correctly return the
    non-error "in progress" result.
- **Leaner extraction — lazy lattice Tabula (review C1, corrected)** — gate `PdfTableExtractor`'s
  **lattice** pass on a cheap per-page PDFBox ruling-line scan: pages with no ruling lines skip
  the lattice `SpreadsheetExtractionAlgorithm`. This is correctness-preserving **only for the
  lattice path** — lattice extracts *ruled* tables, and ruled tables have ruling lines, so the
  gate cannot drop a table lattice would have found.
  - **⚠ `enableStreamMode` exclusion:** `PdfTableExtractor` has a stream-mode fallback
    (`BasicExtractionAlgorithm`, `PdfTableExtractor.kt:44-79`) that finds *borderless*
    whitespace-aligned tables which have **no ruling lines**. When `ExtractOptions.enableStreamMode
    == true`, the ruling-line gate is **bypassed entirely** and the page goes through the full
    lattice→stream path unchanged. The gate fires only when `enableStreamMode == false` (the
    default). This prevents silently dropping stream-mode tables.
  - **Not** reducing the triple-file-open: now that extraction is once-per-session, that cost is
    amortized away and not worth the `PDDocument`-state-sharing correctness risk.

### `:agent` — `DocumentTool` rewrite

- Delegates to the `:core` `DocumentArtifactService`; contains no extraction logic.
- Params (backward compatible): `path` (required), `offset` (default 0), `max_chars`
  (optional), **new** `page` (optional), **new** `section` (optional). `page`/`section`
  resolve to an offset via the index; if combined with an explicit non-zero `offset`, the
  semantic anchor wins and the tool notes the resolved offset.
- Richer continuation hint:
  `[... call read_document(offset=204000) — page 47 of 200 ...]`.
- **Doom-loop exemption (review C2):** on a cold read the tool may legitimately return
  "extraction in progress, retry" several times with **identical arguments**. `LoopDetector`
  keys on the canonicalized argument signature (`LoopDetector.kt:84-107`), *not* response text,
  so identical retries would trip `HARD_LIMIT` at 5. Fix: add an `exemptTools: Set<String>`
  constructor param to `LoopDetector` (default empty); `recordToolCall` returns `LoopStatus.OK`
  and resets `consecutiveCount` when `toolName in exemptTools`. `AgentLoop` wires
  `setOf("read_document")`. This is the only viable mitigation — varying the *response* text
  does nothing because the detector never inspects it.

## Storage (tier 2, **session-scoped**, content-hash keyed)

```
~/.workflow-orchestrator/{slug}-{sha6}/agent/sessions/{sessionId}/document-cache/{sha256}/
  content.md     ← assembled markdown artifact (greppable by search_code via tier-2 read allowlist)
  index.json     ← page→offset, section→offset
  meta.json      ← DocumentArtifact descriptor + createdAt  (written LAST = commit sentinel)
  content.md.tmp ← scratch for atomic write (same dir → same partition; see below)
  failure.json   ← (only on failure) reason + timestamp, for negative caching
```

- **Session-scoped (review I5 moot):** lives under `sessions/{sessionId}/` (tier 2), so it is
  removed with the session on new chat / session delete — no cross-session or cross-project
  persistence of document text, and no separate eviction policy needed. The cache naturally
  bounds to one session's documents.
- **Keyed by content hash** → an edited file gets a new hash → new artifact dir automatically.
  No stale-mtime class of bug.
- **Atomic commit (review N1)** — `content.md` and `index.json` are each written to a `.tmp` in
  **the same `{sha256}/` directory** (guaranteeing same-partition `ATOMIC_MOVE`) then moved into
  place; `meta.json` is written **last** as the commit sentinel. Readers treat missing/invalid
  `meta.json` as cold, so a crash mid-write never yields a partial artifact (a leftover
  `content.md` without `meta.json` is simply overwritten on re-extract).
- **Reachability** — tier 2 under `sessions/{id}/` is reachable by
  `PathValidator.resolveAndValidateForRead`, so the agent can also `search_code` the persisted
  `content.md` directly — scoped to the current session only.

## Data Flow

### Warm read (artifact exists)
1. `read(path, cursor, maxChars)` → hash file bytes (memoized) → look up `{sha256}/`.
2. `meta.json` valid → load `index.json`, resolve cursor → offset → slice `content.md`.
3. Return `DocumentSlice` instantly.

### Cold read (no artifact)
1. Check `failure.json` — if present and non-expired, short-circuit to the cached error.
2. Otherwise start (or join) the single-flight background job.
3. Await the job up to `serving-timeout − margin` (~25s of the 30s `documentTimeoutMs` budget).
4. **Job finishes in time** → slice and return.
5. **Job still running** → return **non-error** result:
   `"extraction in progress — call read_document again shortly"`. The job continues on its own
   5-min budget; the next read hits the warm artifact. (Doom-loop-safe via the `read_document`
   exemption above.)

## Error Handling

| Condition | Behavior |
|---|---|
| Encrypted / scanned-no-text / corrupt | Job → `Failed(reason)`; persist `failure.json`; read returns the existing clear error (e.g. "no embedded text layer"). Negative-cached for 1h or until content-hash change. **In-memory single-flight entry removed on failure**; the on-disk `failure.json` is the durable negative cache (survives JVM restart). |
| Job exceeds 5-min budget (incl. `Semaphore(2)` permit wait) | `Failed(timeout)`; read surfaces "document too large/complex to extract within Ns". |
| `offset` ≥ length / negative | Preserve existing `DocumentTool` handling (end-of-document message / validation error). |
| Partial/corrupt cache (crash mid-write) | Detected via missing/invalid `meta.json` sentinel → treated as cold → re-extract. |
| Hash of a huge file | Streaming hash; memoized by `(path, mtime, size)`. Cost ≪ extraction. **Caveat (review I6):** `mtime` has ~1s filesystem granularity; the `size` component of the key catches most same-second edits that change length. Memo is per-service-lifetime, so a cold IDE start re-hashes (one-time, sub-second for typical docs). |

## Backward Compatibility

- Assembled markdown is byte-identical to today **provided lazy-lattice is correctly gated**
  (lattice-only pages: ruled tables still extracted; prose-only pages: nothing to drop;
  stream-mode: gate bypassed per C1). Under that condition existing `offset` semantics carry
  over unchanged. (review N4: this guarantee is contingent on C1 being implemented correctly —
  the `enableStreamMode` test below is what protects it.)
- Existing sessions with persisted `read_document` results are unaffected (results are plain
  text in conversation history; no migration needed).
- Sub-agents / tests that construct `DocumentTool` without the service get a default
  (constructed) `TikaDocumentArtifactService`, preserving today's behavior path.

## Testing

**`:document` (unit, JUnit5 + MockK + `@TempDir`):**
- Cursor resolution: offset (incl. 0, out-of-range, negative), page→offset, section→offset,
  unknown page/section.
- Index building: page markers and headings map to correct char offsets; pageless formats
  (CSV/XLSX) produce empty `pages` and still serve by offset.
- Single-flight: two concurrent `read()` calls for the same content trigger exactly one
  extraction (injected slow extractor + call counter).
- Background-job vs serving-timeout: first read returns "in progress" without cancelling the
  job; second read hits the warm artifact (injected slow extractor).
- Atomic persistence + crash recovery: artifact with no `meta.json` is treated as cold.
- Content-hash keying: editing the file (new bytes) yields a new artifact dir.
- Session cleanup: session reset removes the session's `document-cache/` and cancels any
  in-flight extraction job (child scope cancellation).
- Negative caching: a failing extraction writes `failure.json`, the single-flight map entry is
  removed, and it isn't re-attempted within the TTL; re-attempts after content-hash change.
- Lazy-lattice correctness: a prose-only PDF skips lattice (call counter); a ruled-table PDF
  still emits the table (output assertion) — assembled markdown identical to pre-change.
- **`enableStreamMode=true` (review C1/N3):** a borderless whitespace-aligned-table PDF with
  `enableStreamMode=true` still emits its tables — i.e. the ruling-line gate is bypassed and
  stream-mode tables are NOT dropped. This is the test that protects the backward-compat claim.

**`:agent`:**
- `DocumentTool` delegates to the service (no extraction in the tool); `page`/`section` params
  resolve via index; continuation hint includes page-of-offset.
- Backward compat: `offset`/`max_chars` behavior unchanged against a warm artifact.
- Doom-loop: repeated identical `read_document` "in progress" calls do NOT trip `LoopDetector`
  (exemption wired).

## Settings (per CLAUDE.md: add UI for new config)

`PluginSettings.State`: `documentExtractionJobTimeoutMs` (300_000). Surfaced under Tools →
Workflow Orchestrator → AI Agent. Existing `documentTimeoutMs` remains the **serving** timeout.
(No cache TTL/size settings — session-scoping makes the cache self-cleaning on session reset.)

## Out of Scope (YAGNI)

- Eager prefetch on attach (lazy single-flight is sufficient; revisit if first-read latency
  is a real complaint).
- Block-index addressing (page + section anchors cover the need).
- Triple-file-open reduction (amortized away by extract-once).
- OCR / external converters (pipeline deliberately disables OCR; text-layer path is correct).

## Risk Note

The known **Tabula↔Tika page-mismatch** (binary-document-reader audit bugs #15/#19) interacts
with the page→offset index. `PageMarker` blocks come from the Tika prose path with *synthetic*
Y-coordinates (`PdfPipeline.kt` prose bboxes start at `top=1`), while Tabula tables carry real
PDF coordinates — so after the merge-sort a page marker always precedes that page's table.
Two consequences (review I3):
- A `page=N` anchor resolves to page N's **prose start**, which is correct for prose but a table
  appearing later on the page is reached only by reading forward.
- **Multi-page merged tables are attributed to their *first* page** (`mergeContinuations`
  returns `current.page`), so `page=N` can skip a table whose data spans onto page N from N−1.
This is not a simple off-by-one. Mitigation: `page` anchors are explicitly **best-effort**;
`offset` is the authoritative primitive and the continuation hint always reports the resolved
offset so the LLM can self-correct. Do not block this work on fully resolving the mismatch.
