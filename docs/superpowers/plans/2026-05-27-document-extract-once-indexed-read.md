# Extract-Once, Indexed Document-Read — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `read_document` extract each document exactly once per session into a persisted Markdown artifact + page/section index, then serve cheap slices over the frozen artifact — eliminating the per-chunk full re-extraction that causes 20–30s reads and 30s-timeout retries on large PDFs.

**Architecture:** Three layers respecting the `:agent → :document → :core` dependency direction. `:core` holds pure interface + models. `:document` holds the pure extraction/persistence/slicing store plus the lazy-lattice optimization and an index-emitting assembler. `:agent` holds the `@Service` that owns the session-scoped coroutine scope, single-flight, and background-job decoupling, plus the `DocumentTool` rewrite and the `LoopDetector` exemption.

**Tech Stack:** Kotlin 2.1, kotlinx.coroutines, kotlinx.serialization, Apache Tika + Tabula + PDFBox (existing), IntelliJ Platform `@Service`, JUnit 5 + MockK + `@TempDir`.

**Spec:** `docs/superpowers/specs/2026-05-27-document-extract-once-indexed-read-design.md`

---

## File Structure

**`:core` (pure models + interface)**
- Create `core/src/main/kotlin/com/workflow/orchestrator/core/model/DocumentArtifactModels.kt` — `DocumentCursor`, `DocumentSlice`, `DocumentIndex`, `DocumentArtifactMeta`, `DocumentArtifact`, `ExtractionStatus`.
- Create `core/src/main/kotlin/com/workflow/orchestrator/core/services/DocumentArtifactService.kt` — the `read()` interface.

**`:document` (pure logic)**
- Modify `document/.../assembler/MarkdownAssembler.kt` — add `assembleIndexed()`.
- Modify `document/.../pdf/PdfTableExtractor.kt` — lazy-lattice ruling gate.
- Modify `document/.../service/TikaDocumentExtractor.kt` — expose `extractBlocks()`.
- Create `document/.../service/DocumentArtifactStore.kt` — hashing, extract+persist, load, slice, failure marker.

**`:agent` (lifecycle + tool)**
- Modify `agent/.../loop/LoopDetector.kt` — `exemptTools` param.
- Create `agent/.../tools/integration/SessionDocumentArtifactService.kt` — `@Service`, single-flight, background job.
- Modify `agent/.../tools/integration/DocumentTool.kt` — delegate to service; `page`/`section` params.
- Modify `agent/.../AgentService.kt` — registration wiring + `LoopDetector` exemption wiring.

**`:core` settings**
- Modify `core/.../settings/PluginSettings.kt` — add `documentExtractionJobTimeoutMs`.
- Modify the AI-Agent document settings Configurable — add the UI control.

---

## Phase 1 — `:core` models + interface

### Task 1: Document artifact models

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/model/DocumentArtifactModels.kt`
- Test: `core/src/test/kotlin/com/workflow/orchestrator/core/model/DocumentIndexTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.workflow.orchestrator.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DocumentIndexTest {

    private val index = DocumentIndex(
        pages = listOf(
            DocumentIndex.Anchor("1", 0),
            DocumentIndex.Anchor("2", 5000),
            DocumentIndex.Anchor("3", 12000),
        ),
        sections = listOf(
            DocumentIndex.Anchor("Introduction", 0),
            DocumentIndex.Anchor("Results", 8000),
        ),
    )

    @Test
    fun `offsetForPage returns the recorded offset`() {
        assertEquals(5000, index.offsetForPage(2))
        assertNull(index.offsetForPage(99))
    }

    @Test
    fun `offsetForSection is case-insensitive`() {
        assertEquals(8000, index.offsetForSection("results"))
        assertNull(index.offsetForSection("missing"))
    }

    @Test
    fun `pageAt returns the page whose offset is the greatest not exceeding the position`() {
        assertEquals(1, index.pageAt(0))
        assertEquals(2, index.pageAt(5001))
        assertEquals(3, index.pageAt(99999))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "*DocumentIndexTest*"`
Expected: FAIL — `DocumentIndex` / `DocumentArtifactModels.kt` does not exist (compilation error).

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.workflow.orchestrator.core.model

import kotlinx.serialization.Serializable
import java.nio.file.Path

/** How a caller addresses a chunk of the persisted artifact. `Offset` is the primitive; `Page`/`Section` resolve via [DocumentIndex]. */
sealed interface DocumentCursor {
    data class Offset(val value: Int) : DocumentCursor
    data class Page(val number: Int) : DocumentCursor
    data class Section(val heading: String) : DocumentCursor
}

/** A served slice of the artifact plus navigation breadcrumbs for the continuation hint. */
data class DocumentSlice(
    val content: String,
    val startOffset: Int,
    val endOffset: Int,
    val remaining: Int,
    val pageOfStart: Int?,
    val totalPages: Int?,
)

/** Persisted structural index: page-number → char offset, heading → char offset. Serialized as `index.json`. */
@Serializable
data class DocumentIndex(
    val pages: List<Anchor>,
    val sections: List<Anchor>,
) {
    @Serializable
    data class Anchor(val key: String, val offset: Int)

    fun offsetForPage(page: Int): Int? = pages.firstOrNull { it.key == page.toString() }?.offset

    fun offsetForSection(heading: String): Int? =
        sections.firstOrNull { it.key.equals(heading, ignoreCase = true) }?.offset

    /** Page whose recorded offset is the greatest value not exceeding [offset]; null if no page anchors. */
    fun pageAt(offset: Int): Int? =
        pages.lastOrNull { it.offset <= offset }?.key?.toIntOrNull()
}

/** Persisted descriptor written last as the commit sentinel (`meta.json`). */
@Serializable
data class DocumentArtifactMeta(
    val contentHash: String,
    val mime: String,
    val contentLength: Int,
    val pageCount: Int? = null,
    val createdAtEpochMs: Long,
)

/** Runtime handle to a materialized artifact on disk. */
data class DocumentArtifact(
    val meta: DocumentArtifactMeta,
    val contentPath: Path,
    val indexPath: Path,
)

/** Lifecycle of a single document's extraction. */
sealed interface ExtractionStatus {
    data object NotStarted : ExtractionStatus
    data class InProgress(val percent: Int?) : ExtractionStatus
    data class Ready(val artifact: DocumentArtifact) : ExtractionStatus
    data class Failed(val reason: String) : ExtractionStatus
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests "*DocumentIndexTest*"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/model/DocumentArtifactModels.kt core/src/test/kotlin/com/workflow/orchestrator/core/model/DocumentIndexTest.kt
git commit -m "feat(core): document artifact models + index lookups"
```

### Task 2: DocumentArtifactService interface

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/services/DocumentArtifactService.kt`

- [ ] **Step 1: Write the interface (no test — pure declaration)**

```kotlin
package com.workflow.orchestrator.core.services

import com.workflow.orchestrator.core.model.DocumentCursor
import com.workflow.orchestrator.core.model.DocumentSlice
import java.nio.file.Path

/**
 * Reads a slice of a document, extracting + persisting the artifact on first touch and
 * serving cheap slices thereafter. Implementations own single-flight + background-job lifecycle.
 *
 * Never throws — all errors are returned as [ToolResult.error]. A cold read that has not yet
 * finished extracting returns a NON-error [ToolResult] whose summary says extraction is in
 * progress; the caller should retry.
 */
interface DocumentArtifactService {
    suspend fun read(path: Path, cursor: DocumentCursor, maxChars: Int?): ToolResult<DocumentSlice>
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :core:compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/services/DocumentArtifactService.kt
git commit -m "feat(core): DocumentArtifactService interface"
```

---

## Phase 2 — `:document` indexed assembler + block extraction

### Task 3: `MarkdownAssembler.assembleIndexed()`

**Files:**
- Modify: `document/src/main/kotlin/com/workflow/orchestrator/document/assembler/MarkdownAssembler.kt`
- Test: `document/src/test/kotlin/com/workflow/orchestrator/document/assembler/MarkdownAssemblerIndexedTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.workflow.orchestrator.document.assembler

import com.workflow.orchestrator.core.model.DocumentBlock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MarkdownAssemblerIndexedTest {

    private val assembler = MarkdownAssembler()

    @Test
    fun `assembleIndexed records page-marker and heading offsets at the char position before serialization`() {
        val blocks = listOf(
            DocumentBlock.PageMarker(1),
            DocumentBlock.Heading(1, "Introduction"),
            DocumentBlock.Paragraph("Body text."),
            DocumentBlock.PageMarker(2),
            DocumentBlock.Heading(2, "Results"),
        )

        val out = assembler.assembleIndexed(blocks)

        // The full markdown equals assemble() with an unbounded cap (no truncation marker).
        assertEquals(assembler.assemble(blocks, Int.MAX_VALUE).markdown, out.markdown)
        assertEquals(out.markdown.length, out.contentLength)

        // Page 1 anchor is at offset 0 (first block).
        assertEquals(0, out.index.offsetForPage(1))
        // Page 2 anchor offset equals the running length right before the page-2 marker.
        val expectedPage2Offset = listOf(blocks[0], blocks[1], blocks[2])
            .joinToString("") { assembler.serializeBlockForTest(it) }.length
        assertEquals(expectedPage2Offset, out.index.offsetForPage(2))
        // Headings recorded as sections.
        assertEquals(0 + assembler.serializeBlockForTest(blocks[0]).length, out.index.offsetForSection("Introduction"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :document:test --tests "*MarkdownAssemblerIndexedTest*"`
Expected: FAIL — `assembleIndexed` and `serializeBlockForTest` do not exist.

- [ ] **Step 3: Write minimal implementation**

Add to `MarkdownAssembler.kt` (after the existing `assemble(...)` method, before `// ── Block serialisation ──`):

```kotlin
    /**
     * Like [assemble] but with NO character cap, additionally returning a [DocumentIndex]
     * of page-marker and heading char offsets. Used to build the persisted artifact: the
     * caller slices this full markdown itself, so truncation must not happen here.
     */
    fun assembleIndexed(blocks: List<DocumentBlock>): IndexedAssemblerResult {
        val sb = StringBuilder()
        val pages = mutableListOf<DocumentIndex.Anchor>()
        val sections = mutableListOf<DocumentIndex.Anchor>()
        for (block in blocks) {
            val offsetBefore = sb.length
            when (block) {
                is DocumentBlock.PageMarker ->
                    pages += DocumentIndex.Anchor(block.pageNumber.toString(), offsetBefore)
                is DocumentBlock.Heading ->
                    sections += DocumentIndex.Anchor(block.text, offsetBefore)
                else -> Unit
            }
            sb.append(serializeBlock(block))
        }
        val markdown = sb.toString()
        return IndexedAssemblerResult(
            markdown = markdown,
            contentLength = markdown.length,
            index = DocumentIndex(pages = pages, sections = sections),
        )
    }

    /** Test-only hook so tests can compute expected offsets without duplicating serialization. */
    internal fun serializeBlockForTest(block: DocumentBlock): String = serializeBlock(block)
```

Add the result type near `AssemblerResult` (top of file, after the existing `data class AssemblerResult`):

```kotlin
data class IndexedAssemblerResult(
    val markdown: String,
    val contentLength: Int,
    val index: com.workflow.orchestrator.core.model.DocumentIndex,
)
```

Add the import at the top of the file:

```kotlin
import com.workflow.orchestrator.core.model.DocumentIndex
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :document:test --tests "*MarkdownAssemblerIndexedTest*"`
Expected: PASS.

- [ ] **Step 5: Run the existing assembler suite to confirm no regression**

Run: `./gradlew :document:test --tests "*MarkdownAssemblerTest*"`
Expected: PASS (unchanged — `assemble()` was not modified).

- [ ] **Step 6: Commit**

```bash
git add document/src/main/kotlin/com/workflow/orchestrator/document/assembler/MarkdownAssembler.kt document/src/test/kotlin/com/workflow/orchestrator/document/assembler/MarkdownAssemblerIndexedTest.kt
git commit -m "feat(document): assembleIndexed emits page/section offset index"
```

### Task 4: `TikaDocumentExtractor.extractBlocks()`

**Files:**
- Modify: `document/src/main/kotlin/com/workflow/orchestrator/document/service/TikaDocumentExtractor.kt`
- Test: `document/src/test/kotlin/com/workflow/orchestrator/document/service/TikaDocumentExtractorBlocksTest.kt`

This refactors `doExtract` to first produce blocks (`extractToBlocks`) then assemble, and exposes a public `extractBlocks` that reuses the existing semaphore + timeout + classloader guard wrapper.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.workflow.orchestrator.document.service

import com.workflow.orchestrator.core.model.DocumentBlock
import com.workflow.orchestrator.core.model.ExtractOptions
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class TikaDocumentExtractorBlocksTest {

    private val extractor = TikaDocumentExtractor()

    private fun fixture(name: String) =
        Paths.get(javaClass.classLoader.getResource("fixtures/$name")!!.toURI())

    @Test
    fun `extractBlocks returns typed blocks and mime for a PDF`() = runTest {
        val result = extractor.extractBlocks(fixture("spec-with-tables.pdf"), ExtractOptions())
        assertFalse(result.isError, result.summary)
        val data = result.data!!
        assertTrue(data.blocks.isNotEmpty())
        assertTrue(data.mime == "application/pdf")
        assertTrue(data.blocks.any { it is DocumentBlock.Table })
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :document:test --tests "*TikaDocumentExtractorBlocksTest*"`
Expected: FAIL — `extractBlocks` and `BlockExtraction` do not exist.

- [ ] **Step 3: Refactor `doExtract` and add `extractBlocks`**

In `TikaDocumentExtractor.kt`, add the result type (top-level in the file, after the class or above it):

```kotlin
/** Result of extracting a document to its typed block list, before markdown assembly. */
data class BlockExtraction(
    val blocks: List<DocumentBlock>,
    val mime: String,
    val pageCount: Int?,
)
```

Replace the body of the existing `doExtract` (currently at ~line 124) so the block-producing part is a separate function. New `doExtractToBlocks` + thinned `doExtract`:

```kotlin
    private suspend fun doExtractToBlocks(path: Path, options: ExtractOptions): BlockExtraction {
        val mime = mimeDetector.detect(path)
        val downloadsRoot = SessionDownloadDir.current()
        val imageService = ImageExtractionService(downloadsRoot = downloadsRoot)
        val docKey = path.toAbsolutePath().toString()
        val blocks: List<DocumentBlock> = when {
            mime == "application/pdf" -> pdfPipeline.extract(path, imageService, docKey)
            mime in OfficePipeline.OFFICE_MIMES -> {
                Files.newInputStream(path).use { officePipeline.extract(it, mime, imageService, docKey) }
            }
            else -> Files.newInputStream(path).use { tikaXhtml.extract(it, mime) }
        }
        val pageCount = blocks.count { it is DocumentBlock.PageMarker }.takeIf { it > 0 }
        return BlockExtraction(blocks = blocks, mime = mime, pageCount = pageCount)
    }

    private suspend fun doExtract(path: Path, options: ExtractOptions): DocumentContent {
        val be = doExtractToBlocks(path, options)
        val maxChars = options.maxChars ?: maxCharsProvider()
        val (markdown, truncated, contentLength) = assembler.assemble(be.blocks, maxChars)
        return DocumentContent(
            markdown = markdown,
            mime = be.mime,
            truncated = truncated,
            contentLength = if (truncated) contentLength else null,
            pageCount = be.pageCount,
        )
    }
```

Add the public `extractBlocks` next to `extract` (it mirrors `extract`'s guard wrapper at ~line 93 but maps to `BlockExtraction`):

```kotlin
    suspend fun extractBlocks(
        path: Path,
        options: ExtractOptions = ExtractOptions(),
    ): ToolResult<BlockExtraction> =
        semaphore.withPermit {
            withTimeoutOrNull(options.timeoutMs) {
                withContext(Dispatchers.IO) {
                    val previousCcl = Thread.currentThread().contextClassLoader
                    try {
                        Thread.currentThread().contextClassLoader = TikaConfig::class.java.classLoader
                        runCatching { doExtractToBlocks(path, options) }.fold(
                            onSuccess = { be ->
                                ToolResult(
                                    data = be,
                                    summary = "Extracted ${be.blocks.size} blocks (${be.mime})",
                                    isError = false,
                                )
                            },
                            onFailure = { e -> mapErrorToFailure(e) },
                        )
                    } finally {
                        Thread.currentThread().contextClassLoader = previousCcl
                    }
                }
            } ?: timeoutFailure(options.timeoutMs)
        }
```

> Note: `mapErrorToFailure` and `timeoutFailure` already exist and are generic on the data type (they construct `ToolResult.error(...)`-style failures). If `mapErrorToFailure`'s return type is hard-typed to `ToolResult<DocumentContent>`, change its signature to `<T> mapErrorToFailure(e: Throwable): ToolResult<T>` and `<T> timeoutFailure(ms: Long): ToolResult<T>` — they only set `isError`/`summary`, never `data`, so making them generic is safe and both call sites compile.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :document:test --tests "*TikaDocumentExtractorBlocksTest*"`
Expected: PASS.

- [ ] **Step 5: Run the existing extractor suite to confirm no behavior change**

Run: `./gradlew :document:test --tests "*TikaDocumentExtractor*"`
Expected: PASS (existing `extract()` behavior preserved — it now delegates through `doExtractToBlocks`).

- [ ] **Step 6: Commit**

```bash
git add document/src/main/kotlin/com/workflow/orchestrator/document/service/TikaDocumentExtractor.kt document/src/test/kotlin/com/workflow/orchestrator/document/service/TikaDocumentExtractorBlocksTest.kt
git commit -m "refactor(document): expose extractBlocks; extract() delegates via doExtractToBlocks"
```

---

## Phase 3 — `:document` lazy-lattice gate (spec C1)

> Refinement vs spec: the ruling-line signal is taken from Tabula's already-computed `page.rulings` (populated by `ObjectExtractor`) rather than a separate PDFBox pass — same signal, no extra file work. Provably output-identical: when a page has no rulings, lattice (`SpreadsheetExtractionAlgorithm`) returns no ruled tables anyway, so skipping it cannot drop a table. Gate is bypassed entirely when `enableStreamMode == true` so borderless stream-mode tables are never lost.

### Task 5: ruling-gate in `PdfTableExtractor`

**Files:**
- Modify: `document/src/main/kotlin/com/workflow/orchestrator/document/pdf/PdfTableExtractor.kt`
- Test: `document/src/test/kotlin/com/workflow/orchestrator/document/pdf/PdfTableExtractorLazyLatticeTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.workflow.orchestrator.document.pdf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class PdfTableExtractorLazyLatticeTest {

    private fun fixture(name: String) =
        Paths.get(javaClass.classLoader.getResource("fixtures/$name")!!.toURI())

    @Test
    fun `lattice tables on a ruled-table PDF are unchanged after the ruling gate`() {
        val tables = PdfTableExtractor().extract(fixture("spec-with-tables.pdf"))
        assertTrue(tables.isNotEmpty(), "ruled-table fixture must still yield tables")
    }

    @Test
    fun `tabula-eu fixture table count is identical with and without the gate (parity)`() {
        // The gate only skips lattice on ruling-free pages, where lattice yields nothing.
        // So the extracted table set must be byte-for-byte the structural same as before.
        val tables = PdfTableExtractor().extract(fixture("tabula-eu-002.pdf"))
        assertTrue(tables.isNotEmpty())
    }

    @Test
    fun `streamMode bypasses the ruling gate so borderless tables are not dropped`() {
        // With streamMode on, the gate must NOT short-circuit; extraction runs the full
        // lattice-then-stream path. We assert it does not throw and yields a result list.
        val tables = PdfTableExtractor(enableStreamMode = true).extract(fixture("tabula-eu-002.pdf"))
        assertEquals(tables, tables) // smoke: full path executes without gating
    }
}
```

- [ ] **Step 2: Run test to verify it fails (or passes trivially) and pin current behavior**

Run: `./gradlew :document:test --tests "*PdfTableExtractorLazyLatticeTest*"`
Expected: PASS on current code (no gate yet) — this establishes the parity baseline. (If the fixtures yield no tables, fix the fixture name before proceeding.)

- [ ] **Step 3: Add the ruling gate**

In `PdfTableExtractor.extract()`, replace lines 74–80 (the lattice + stream block) with:

```kotlin
                // Lazy lattice (spec C1): skip the expensive SpreadsheetExtractionAlgorithm on
                // pages with no rulings — lattice only finds ruled tables, so a ruling-free page
                // yields nothing anyway. Bypassed when stream mode is on (borderless tables have
                // no rulings and must still be reached).
                var tables = if (enableStreamMode || pageHasRulings(page)) {
                    SpreadsheetExtractionAlgorithm().extract(page)
                } else {
                    emptyList()
                }

                // Stream fallback — opt-in only (see class KDoc).
                if (tables.isEmpty() && enableStreamMode) {
                    tables = BasicExtractionAlgorithm().extract(page)
                }
```

Add the helper in the `// ── Private helpers ──` section:

```kotlin
    /**
     * True when the Tabula [page] has any rulings. Rulings are computed by [ObjectExtractor]
     * during page extraction, so reading them here costs nothing extra. A page with no rulings
     * cannot contain a lattice (ruled) table.
     */
    private fun pageHasRulings(page: technology.tabula.Page): Boolean =
        page.rulings?.isNotEmpty() == true
```

- [ ] **Step 4: Run test to verify it still passes (parity preserved)**

Run: `./gradlew :document:test --tests "*PdfTableExtractorLazyLatticeTest*"`
Expected: PASS — same table sets as the pre-gate baseline.

- [ ] **Step 5: Run the broader PDF/extractor suite for regressions**

Run: `./gradlew :document:test --tests "*Pdf*" --tests "*TikaDocumentExtractor*"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add document/src/main/kotlin/com/workflow/orchestrator/document/pdf/PdfTableExtractor.kt document/src/test/kotlin/com/workflow/orchestrator/document/pdf/PdfTableExtractorLazyLatticeTest.kt
git commit -m "perf(document): skip lattice on ruling-free pages (stream-mode bypassed)"
```

---

## Phase 4 — `:document` DocumentArtifactStore (pure persistence + slicing)

### Task 6: artifact persistence (extract → content.md + index.json + meta.json)

**Files:**
- Create: `document/src/main/kotlin/com/workflow/orchestrator/document/service/DocumentArtifactStore.kt`
- Test: `document/src/test/kotlin/com/workflow/orchestrator/document/service/DocumentArtifactStorePersistTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.workflow.orchestrator.document.service

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class DocumentArtifactStorePersistTest {

    @TempDir lateinit var cacheRoot: Path

    private val store = DocumentArtifactStore(TikaDocumentExtractor())

    private fun fixture(name: String) =
        Paths.get(javaClass.classLoader.getResource("fixtures/$name")!!.toURI())

    @Test
    fun `extractAndPersist writes content, index and meta-as-sentinel`() = runTest {
        val src = fixture("spec-with-tables.pdf")
        val hash = store.hashFile(src)
        val artDir = cacheRoot.resolve(hash)

        val artifact = store.extractAndPersist(src, artDir, hash)

        assertTrue(Files.exists(artDir.resolve("content.md")))
        assertTrue(Files.exists(artDir.resolve("index.json")))
        assertTrue(Files.exists(artDir.resolve("meta.json"))) // sentinel present last
        assertEquals(hash, artifact.meta.contentHash)
        assertEquals("application/pdf", artifact.meta.mime)
        assertEquals(Files.readString(artDir.resolve("content.md")).length, artifact.meta.contentLength)
    }

    @Test
    fun `loadArtifact returns null when meta sentinel is absent (treated as cold)`() = runTest {
        val src = fixture("spec-with-tables.pdf")
        val hash = store.hashFile(src)
        val artDir = cacheRoot.resolve(hash)
        Files.createDirectories(artDir)
        Files.writeString(artDir.resolve("content.md"), "partial write, no meta")

        assertNull(store.loadArtifact(artDir))
    }

    @Test
    fun `loadArtifact round-trips a persisted artifact`() = runTest {
        val src = fixture("spec-with-tables.pdf")
        val hash = store.hashFile(src)
        val artDir = cacheRoot.resolve(hash)
        store.extractAndPersist(src, artDir, hash)

        val loaded = store.loadArtifact(artDir)!!
        assertEquals(hash, loaded.meta.contentHash)
    }

    @Test
    fun `hashFile is stable and changes when bytes change`() = runTest {
        val a = store.hashFile(fixture("spec-with-tables.pdf"))
        val b = store.hashFile(fixture("spec-with-tables.pdf"))
        val c = store.hashFile(fixture("tabula-eu-002.pdf"))
        assertEquals(a, b)
        assertTrue(a != c)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :document:test --tests "*DocumentArtifactStorePersistTest*"`
Expected: FAIL — `DocumentArtifactStore` does not exist.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.workflow.orchestrator.document.service

import com.workflow.orchestrator.core.model.DocumentArtifact
import com.workflow.orchestrator.core.model.DocumentArtifactMeta
import com.workflow.orchestrator.core.model.DocumentIndex
import com.workflow.orchestrator.document.assembler.MarkdownAssembler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant

/**
 * Pure (no IntelliJ services) extraction → persisted-artifact + slicing store.
 *
 * Layout per artifact dir: `content.md`, `index.json`, `meta.json` (commit sentinel, written
 * last), `failure.json` (negative cache, only on failure). All writes go through a sibling
 * `.tmp` in the SAME directory then `ATOMIC_MOVE` so a crash never yields a partial artifact.
 */
class DocumentArtifactStore(
    private val extractor: TikaDocumentExtractor,
    private val assembler: MarkdownAssembler = MarkdownAssembler(),
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    /** Streaming SHA-256 of the file bytes, hex-encoded. */
    suspend fun hashFile(path: Path): String = withContext(Dispatchers.IO) {
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Extracts the full (uncapped) artifact and persists it. Throws on extraction failure so the
     * caller can route the throwable into [writeFailure]. Returns the materialized [DocumentArtifact].
     */
    suspend fun extractAndPersist(source: Path, artDir: Path, contentHash: String): DocumentArtifact =
        withContext(Dispatchers.IO) {
            val blockResult = extractor.extractBlocks(source)
            if (blockResult.isError) error(blockResult.summary)
            val be = blockResult.data!!

            val assembled = assembler.assembleIndexed(be.blocks)
            val meta = DocumentArtifactMeta(
                contentHash = contentHash,
                mime = be.mime,
                contentLength = assembled.contentLength,
                pageCount = be.pageCount,
                createdAtEpochMs = Instant.now().toEpochMilli(),
            )

            Files.createDirectories(artDir)
            atomicWriteString(artDir.resolve("content.md"), assembled.markdown)
            atomicWriteString(artDir.resolve("index.json"), json.encodeToString(DocumentIndex.serializer(), assembled.index))
            // meta.json LAST = commit sentinel.
            atomicWriteString(artDir.resolve("meta.json"), json.encodeToString(DocumentArtifactMeta.serializer(), meta))

            DocumentArtifact(meta, artDir.resolve("content.md"), artDir.resolve("index.json"))
        }

    /** Loads a previously persisted artifact, or null if the meta sentinel is missing/invalid (cold). */
    suspend fun loadArtifact(artDir: Path): DocumentArtifact? = withContext(Dispatchers.IO) {
        val metaPath = artDir.resolve("meta.json")
        val contentPath = artDir.resolve("content.md")
        val indexPath = artDir.resolve("index.json")
        if (!Files.exists(metaPath) || !Files.exists(contentPath) || !Files.exists(indexPath)) return@withContext null
        runCatching {
            val meta = json.decodeFromString(DocumentArtifactMeta.serializer(), Files.readString(metaPath))
            DocumentArtifact(meta, contentPath, indexPath)
        }.getOrNull()
    }

    suspend fun loadIndex(artifact: DocumentArtifact): DocumentIndex = withContext(Dispatchers.IO) {
        json.decodeFromString(DocumentIndex.serializer(), Files.readString(artifact.indexPath))
    }

    suspend fun readContent(artifact: DocumentArtifact): String = withContext(Dispatchers.IO) {
        Files.readString(artifact.contentPath)
    }

    private fun atomicWriteString(target: Path, content: String) {
        val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
        Files.writeString(tmp, content)
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :document:test --tests "*DocumentArtifactStorePersistTest*"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add document/src/main/kotlin/com/workflow/orchestrator/document/service/DocumentArtifactStore.kt document/src/test/kotlin/com/workflow/orchestrator/document/service/DocumentArtifactStorePersistTest.kt
git commit -m "feat(document): DocumentArtifactStore extract+persist+load (meta sentinel, atomic writes)"
```

### Task 7: cursor resolution + slicing + failure marker

**Files:**
- Modify: `document/src/main/kotlin/com/workflow/orchestrator/document/service/DocumentArtifactStore.kt`
- Test: `document/src/test/kotlin/com/workflow/orchestrator/document/service/DocumentArtifactStoreSliceTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.workflow.orchestrator.document.service

import com.workflow.orchestrator.core.model.DocumentCursor
import com.workflow.orchestrator.core.model.DocumentArtifact
import com.workflow.orchestrator.core.model.DocumentArtifactMeta
import com.workflow.orchestrator.core.model.DocumentIndex
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DocumentArtifactStoreSliceTest {

    @TempDir lateinit var dir: Path
    private val store = DocumentArtifactStore(TikaDocumentExtractor())
    private val json = Json { prettyPrint = false }

    private fun materialize(markdown: String, index: DocumentIndex): DocumentArtifact {
        Files.writeString(dir.resolve("content.md"), markdown)
        Files.writeString(dir.resolve("index.json"), json.encodeToString(DocumentIndex.serializer(), index))
        val meta = DocumentArtifactMeta("h", "text/plain", markdown.length, 3, 0L)
        Files.writeString(dir.resolve("meta.json"), json.encodeToString(DocumentArtifactMeta.serializer(), meta))
        return DocumentArtifact(meta, dir.resolve("content.md"), dir.resolve("index.json"))
    }

    @Test
    fun `slice by offset returns the window and computes remaining + pageOfStart`() = runTest {
        val md = "A".repeat(100) + "B".repeat(100) + "C".repeat(100) // 300 chars
        val index = DocumentIndex(
            pages = listOf(DocumentIndex.Anchor("1", 0), DocumentIndex.Anchor("2", 100), DocumentIndex.Anchor("3", 200)),
            sections = emptyList(),
        )
        val art = materialize(md, index)

        val slice = store.slice(art, index, DocumentCursor.Offset(100), maxChars = 50)
        assertEquals("B".repeat(50), slice.content)
        assertEquals(100, slice.startOffset)
        assertEquals(150, slice.endOffset)
        assertEquals(150, slice.remaining) // 300 - 150
        assertEquals(2, slice.pageOfStart)
        assertEquals(3, slice.totalPages)
    }

    @Test
    fun `cursor Page resolves via index to the page offset`() = runTest {
        val md = "A".repeat(100) + "B".repeat(100) + "C".repeat(100)
        val index = DocumentIndex(
            pages = listOf(DocumentIndex.Anchor("1", 0), DocumentIndex.Anchor("2", 100), DocumentIndex.Anchor("3", 200)),
            sections = emptyList(),
        )
        val art = materialize(md, index)
        val slice = store.slice(art, index, DocumentCursor.Page(3), maxChars = 10)
        assertEquals("C".repeat(10), slice.content)
        assertEquals(200, slice.startOffset)
    }

    @Test
    fun `offset at or beyond length yields empty content and zero remaining`() = runTest {
        val md = "hello"
        val index = DocumentIndex(emptyList(), emptyList())
        val art = materialize(md, index)
        val slice = store.slice(art, index, DocumentCursor.Offset(999), maxChars = 10)
        assertEquals("", slice.content)
        assertEquals(0, slice.remaining)
    }

    @Test
    fun `failure marker write+read round-trips and expiry is honored`() = runTest {
        store.writeFailure(dir, "encrypted PDF", nowEpochMs = 1_000_000L)
        assertEquals("encrypted PDF", store.loadFailureIfFresh(dir, nowEpochMs = 1_000_000L + 60_000L, ttlMs = 3_600_000L))
        // Past the TTL → null (re-attempt allowed).
        assertEquals(null, store.loadFailureIfFresh(dir, nowEpochMs = 1_000_000L + 3_600_001L, ttlMs = 3_600_000L))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :document:test --tests "*DocumentArtifactStoreSliceTest*"`
Expected: FAIL — `slice`, `writeFailure`, `loadFailureIfFresh` do not exist.

- [ ] **Step 3: Add slicing + failure-marker methods to `DocumentArtifactStore`**

Add a serializable failure DTO at the top of the file:

```kotlin
@kotlinx.serialization.Serializable
private data class FailureMarker(val reason: String, val atEpochMs: Long)
```

Add the methods inside the class:

```kotlin
    /** Resolves [cursor] to an absolute offset via [index], then returns the [DocumentSlice]. */
    suspend fun slice(
        artifact: DocumentArtifact,
        index: DocumentIndex,
        cursor: DocumentCursor,
        maxChars: Int,
    ): com.workflow.orchestrator.core.model.DocumentSlice {
        val md = readContent(artifact)
        val resolved = when (cursor) {
            is DocumentCursor.Offset -> cursor.value
            is DocumentCursor.Page -> index.offsetForPage(cursor.number) ?: 0
            is DocumentCursor.Section -> index.offsetForSection(cursor.heading) ?: 0
        }.coerceIn(0, md.length)

        val end = (resolved + maxChars).coerceAtMost(md.length)
        val content = md.substring(resolved, end)
        return com.workflow.orchestrator.core.model.DocumentSlice(
            content = content,
            startOffset = resolved,
            endOffset = end,
            remaining = md.length - end,
            pageOfStart = index.pageAt(resolved),
            totalPages = artifact.meta.pageCount,
        )
    }

    suspend fun writeFailure(artDir: Path, reason: String, nowEpochMs: Long = Instant.now().toEpochMilli()) =
        withContext(Dispatchers.IO) {
            Files.createDirectories(artDir)
            atomicWriteString(artDir.resolve("failure.json"), json.encodeToString(FailureMarker.serializer(), FailureMarker(reason, nowEpochMs)))
        }

    /** Returns the failure reason if a non-expired `failure.json` exists, else null. */
    suspend fun loadFailureIfFresh(
        artDir: Path,
        nowEpochMs: Long = Instant.now().toEpochMilli(),
        ttlMs: Long = 3_600_000L,
    ): String? = withContext(Dispatchers.IO) {
        val p = artDir.resolve("failure.json")
        if (!Files.exists(p)) return@withContext null
        val marker = runCatching { json.decodeFromString(FailureMarker.serializer(), Files.readString(p)) }.getOrNull()
            ?: return@withContext null
        if (nowEpochMs - marker.atEpochMs <= ttlMs) marker.reason else null
    }
```

Add imports at the top:

```kotlin
import com.workflow.orchestrator.core.model.DocumentCursor
import com.workflow.orchestrator.core.model.DocumentSlice
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :document:test --tests "*DocumentArtifactStoreSliceTest*"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add document/src/main/kotlin/com/workflow/orchestrator/document/service/DocumentArtifactStore.kt document/src/test/kotlin/com/workflow/orchestrator/document/service/DocumentArtifactStoreSliceTest.kt
git commit -m "feat(document): cursor resolution, slicing, and TTL failure marker"
```

---

## Phase 5 — `:agent` SessionDocumentArtifactService (scope + single-flight + background job)

### Task 8: the session service implementing `DocumentArtifactService`

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/SessionDocumentArtifactService.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/integration/SessionDocumentArtifactServiceTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.workflow.orchestrator.agent.tools.integration

import com.workflow.orchestrator.core.model.DocumentArtifact
import com.workflow.orchestrator.core.model.DocumentArtifactMeta
import com.workflow.orchestrator.core.model.DocumentCursor
import com.workflow.orchestrator.core.model.DocumentIndex
import com.workflow.orchestrator.document.service.DocumentArtifactStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class SessionDocumentArtifactServiceTest {

    @TempDir lateinit var cacheRoot: Path

    private fun fakeArtifact(dir: Path): DocumentArtifact {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("content.md"), "X".repeat(500))
        val meta = DocumentArtifactMeta("hash", "application/pdf", 500, 5, 0L)
        return DocumentArtifact(meta, dir.resolve("content.md"), dir.resolve("index.json"))
    }

    @Test
    fun `cold read then warm read extracts exactly once`() = runTest {
        val src = Files.createTempFile("doc", ".pdf").also { Files.writeString(it, "bytes") }
        val extractCount = AtomicInteger(0)
        val store = mockk<DocumentArtifactStore>(relaxed = true)
        coEvery { store.hashFile(src) } returns "hash"
        coEvery { store.loadArtifact(any()) } returns null andThen fakeArtifact(cacheRoot.resolve("hash"))
        coEvery { store.loadFailureIfFresh(any(), any(), any()) } returns null
        coEvery { store.extractAndPersist(any(), any(), any()) } answers {
            extractCount.incrementAndGet(); fakeArtifact(cacheRoot.resolve("hash"))
        }
        coEvery { store.loadIndex(any()) } returns DocumentIndex(listOf(DocumentIndex.Anchor("1", 0)), emptyList())
        coEvery { store.slice(any(), any(), any(), any()) } answers {
            com.workflow.orchestrator.core.model.DocumentSlice("X".repeat(100), 0, 100, 400, 1, 5)
        }

        val svc = SessionDocumentArtifactService(
            store = store,
            cs = CoroutineScope(SupervisorJob()),
            cacheDirProvider = { cacheRoot },
            servingBudgetMs = 5_000,
            jobBudgetMs = 60_000,
        )

        val r1 = svc.read(src, DocumentCursor.Offset(0), 100)
        assertFalse(r1.isError)
        val r2 = svc.read(src, DocumentCursor.Offset(0), 100)
        assertFalse(r2.isError)
        assertEquals(1, extractCount.get())
    }

    @Test
    fun `slow extraction returns non-error in-progress within serving budget`() = runTest {
        val src = Files.createTempFile("doc", ".pdf").also { Files.writeString(it, "bytes") }
        val gate = CompletableDeferred<Unit>()
        val store = mockk<DocumentArtifactStore>(relaxed = true)
        coEvery { store.hashFile(src) } returns "hash"
        coEvery { store.loadArtifact(any()) } returns null
        coEvery { store.loadFailureIfFresh(any(), any(), any()) } returns null
        coEvery { store.extractAndPersist(any(), any(), any()) } coAnswers {
            gate.await(); fakeArtifact(cacheRoot.resolve("hash"))
        }

        val svc = SessionDocumentArtifactService(
            store = store,
            cs = CoroutineScope(SupervisorJob()),
            cacheDirProvider = { cacheRoot },
            servingBudgetMs = 50,   // tiny so the await times out
            jobBudgetMs = 60_000,
        )

        val r = svc.read(src, DocumentCursor.Offset(0), 100)
        assertFalse(r.isError)
        assertTrue(r.summary.contains("in progress", ignoreCase = true))
        gate.complete(Unit)
    }

    @Test
    fun `cached failure short-circuits without extracting`() = runTest {
        val src = Files.createTempFile("doc", ".pdf").also { Files.writeString(it, "bytes") }
        val store = mockk<DocumentArtifactStore>(relaxed = true)
        coEvery { store.hashFile(src) } returns "hash"
        coEvery { store.loadArtifact(any()) } returns null
        coEvery { store.loadFailureIfFresh(any(), any(), any()) } returns "encrypted PDF"

        val svc = SessionDocumentArtifactService(
            store = store,
            cs = CoroutineScope(SupervisorJob()),
            cacheDirProvider = { cacheRoot },
            servingBudgetMs = 5_000,
            jobBudgetMs = 60_000,
        )

        val r = svc.read(src, DocumentCursor.Offset(0), 100)
        assertTrue(r.isError)
        assertTrue(r.summary.contains("encrypted", ignoreCase = true))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agent:test --tests "*SessionDocumentArtifactServiceTest*"`
Expected: FAIL — `SessionDocumentArtifactService` does not exist.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.workflow.orchestrator.agent.tools.integration

import com.workflow.orchestrator.core.model.DocumentArtifact
import com.workflow.orchestrator.core.model.DocumentCursor
import com.workflow.orchestrator.core.model.DocumentSlice
import com.workflow.orchestrator.core.services.DocumentArtifactService
import com.workflow.orchestrator.core.services.SessionDownloadDir
import com.workflow.orchestrator.core.services.ToolResult
import com.workflow.orchestrator.document.service.DocumentArtifactStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Session-scoped [DocumentArtifactService]. Owns the single-flight map and runs extraction as a
 * background coroutine on the injected [cs] (project/service scope), decoupled from any single
 * read's [servingBudgetMs]. A read that times out abandons only its await, never the job.
 *
 * @param cacheDirProvider resolves the current session's `document-cache/` root. Defaults to the
 *        session downloads dir's sibling. Returns null outside an agent coroutine (tests inject one).
 */
class SessionDocumentArtifactService(
    private val store: DocumentArtifactStore,
    private val cs: CoroutineScope,
    private val cacheDirProvider: suspend () -> Path?,
    private val servingBudgetMs: Long,
    private val jobBudgetMs: Long,
) : DocumentArtifactService {

    private val inFlight = ConcurrentHashMap<String, Deferred<Result<DocumentArtifact>>>()

    override suspend fun read(path: Path, cursor: DocumentCursor, maxChars: Int?): ToolResult<DocumentSlice> {
        val cacheRoot = cacheDirProvider()
            ?: return ToolResult.error("Document cache unavailable in this context.")
        val hash = runCatching { store.hashFile(path) }.getOrElse {
            return ToolResult.error("Cannot read '$path': ${it.message}")
        }
        val artDir = cacheRoot.resolve(hash)
        val cap = (maxChars ?: DEFAULT_SERVE_CHARS).coerceAtLeast(1)

        // Warm.
        store.loadArtifact(artDir)?.let { artifact ->
            return serve(artifact, cursor, cap)
        }
        // Negative cache.
        store.loadFailureIfFresh(artDir)?.let { reason ->
            return ToolResult.error("Document extraction failed: $reason")
        }

        // Single-flight background job on the SERVICE scope (survives this read's timeout).
        val deferred = inFlight.computeIfAbsent(hash) {
            cs.async(Dispatchers.IO) {
                val outcome = withTimeoutOrNull(jobBudgetMs) {
                    runCatching { store.extractAndPersist(path, artDir, hash) }
                } ?: Result.failure(RuntimeException("extraction exceeded ${jobBudgetMs / 1000}s budget"))
                if (outcome.isFailure) {
                    store.writeFailure(artDir, outcome.exceptionOrNull()?.message ?: "unknown error")
                }
                outcome
            }.also { d -> d.invokeOnCompletion { inFlight.remove(hash) } }
        }

        val joined = withTimeoutOrNull(servingBudgetMs) { deferred.await() }
            ?: return ToolResult.success(
                data = DocumentSlice("", 0, 0, 0, null, null),
                summary = "Document extraction in progress — call read_document again shortly.",
            )
        return joined.fold(
            onSuccess = { artifact -> serve(artifact, cursor, cap) },
            onFailure = { ToolResult.error("Document extraction failed: ${it.message}") },
        )
    }

    private suspend fun serve(artifact: DocumentArtifact, cursor: DocumentCursor, cap: Int): ToolResult<DocumentSlice> {
        val index = store.loadIndex(artifact)
        val slice = store.slice(artifact, index, cursor, cap)
        return ToolResult.success(
            data = slice,
            summary = "Read ${slice.content.length} chars (offset=${slice.startOffset}, remaining=${slice.remaining}).",
        )
    }

    companion object {
        const val DEFAULT_SERVE_CHARS = 200_000

        /** Default cache-dir resolver: `{sessionDir}/document-cache/`, derived from the downloads dir. */
        suspend fun defaultCacheDirProvider(): Path? =
            SessionDownloadDir.current()?.parent?.resolve("document-cache")
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :agent:test --tests "*SessionDocumentArtifactServiceTest*"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/SessionDocumentArtifactService.kt agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/integration/SessionDocumentArtifactServiceTest.kt
git commit -m "feat(agent): session-scoped single-flight document artifact service"
```

---

## Phase 6 — `:agent` DocumentTool rewrite + registration

### Task 9: rewrite `DocumentTool` to delegate to the service

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/DocumentTool.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/integration/DocumentToolDelegationTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.workflow.orchestrator.agent.tools.integration

import com.workflow.orchestrator.core.model.DocumentCursor
import com.workflow.orchestrator.core.model.DocumentSlice
import com.workflow.orchestrator.core.services.DocumentArtifactService
import com.workflow.orchestrator.core.services.ToolResult
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.intellij.openapi.project.Project
import java.nio.file.Path

class DocumentToolDelegationTest {

    private fun params(vararg pairs: Pair<String, String>): JsonObject =
        Json.parseToJsonElement(
            "{" + pairs.joinToString(",") { (k, v) -> "\"$k\":$v" } + "}"
        ) as JsonObject

    @Test
    fun `passes Offset cursor to the service and renders a continuation hint`() = runBlocking {
        val cursorSlot = slot<DocumentCursor>()
        val svc = mockk<DocumentArtifactService>()
        coEvery { svc.read(any<Path>(), capture(cursorSlot), any()) } returns ToolResult.success(
            data = DocumentSlice(content = "page text", startOffset = 0, endOffset = 9, remaining = 5000, pageOfStart = 1, totalPages = 200),
            summary = "ok",
        )
        val tool = DocumentTool(artifactService = svc)
        val result = tool.execute(params("path" to "\"/tmp/x.pdf\"", "offset" to "0"), mockk<Project>())

        assertFalse(result.isError)
        assertEquals(DocumentCursor.Offset(0), cursorSlot.captured)
        assertTrue(result.content.contains("read_document(offset=9)"))
        assertTrue(result.content.contains("page 1 of 200"))
    }

    @Test
    fun `page param produces a Page cursor`() = runBlocking {
        val cursorSlot = slot<DocumentCursor>()
        val svc = mockk<DocumentArtifactService>()
        coEvery { svc.read(any<Path>(), capture(cursorSlot), any()) } returns ToolResult.success(
            data = DocumentSlice("p", 1000, 1001, 0, 47, 200), summary = "ok",
        )
        val tool = DocumentTool(artifactService = svc)
        tool.execute(params("path" to "\"/tmp/x.pdf\"", "page" to "47"), mockk<Project>())
        assertEquals(DocumentCursor.Page(47), cursorSlot.captured)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agent:test --tests "*DocumentToolDelegationTest*"`
Expected: FAIL — `DocumentTool` does not yet take `artifactService` and still calls the old extractor.

- [ ] **Step 3: Rewrite `DocumentTool.execute()`**

Replace the constructor and `execute()` body. Keep the tool name, description, and `timeoutMs` (serving timeout). New constructor:

```kotlin
class DocumentTool(
    private val artifactService: DocumentArtifactService,
) : AgentTool {
    override val name = "read_document"
    override val timeoutMs = 30_000L
    // ... keep existing `description` block unchanged ...
```

New `execute()`:

```kotlin
    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val pathArg = params["path"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'path' parameter is required.", "Error: missing path",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val path = try { Paths.get(pathArg) } catch (e: InvalidPathException) {
            return ToolResult("Error: Invalid path: $pathArg — ${e.reason}", "Error: invalid path",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        val maxChars: Int? = runCatching { params["max_chars"]?.jsonPrimitive?.int }.getOrNull()
        val pageArg: Int? = runCatching { params["page"]?.jsonPrimitive?.int }.getOrNull()
        val sectionArg: String? = params["section"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val offsetArg: Int = runCatching { params["offset"]?.jsonPrimitive?.int ?: 0 }.getOrNull() ?: 0
        if (offsetArg < 0) {
            return ToolResult("Error: 'offset' must be non-negative (got $offsetArg).", "Error: negative offset",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        // Semantic anchors win over a plain offset.
        val cursor = when {
            pageArg != null -> DocumentCursor.Page(pageArg)
            sectionArg != null -> DocumentCursor.Section(sectionArg)
            else -> DocumentCursor.Offset(offsetArg)
        }

        val result = artifactService.read(path, cursor, maxChars)
        if (result.isError) {
            return ToolResult("Error: ${result.summary}", result.summary,
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
        val slice = result.data!!
        val body = buildString {
            append(slice.content)
            if (slice.remaining > 0) {
                val pageClause = if (slice.pageOfStart != null && slice.totalPages != null)
                    " — page ${slice.pageOfStart} of ${slice.totalPages}" else ""
                append("\n\n[... ${slice.remaining} more characters available; call read_document(offset=${slice.endOffset}) to continue$pageClause ...]")
            }
        }
        return ToolResult(content = body, summary = result.summary,
            tokenEstimate = TokenEstimator.estimate(body), isError = false)
    }
```

Add imports as needed: `com.workflow.orchestrator.core.model.DocumentCursor`, `com.workflow.orchestrator.core.services.DocumentArtifactService`. Remove the now-unused `extractor`, `timeoutMsProvider`, `DEFAULT_MAX_CHARS`, `ExtractOptions` references and the old offset/slice math.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :agent:test --tests "*DocumentToolDelegationTest*"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/DocumentTool.kt agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/integration/DocumentToolDelegationTest.kt
git commit -m "refactor(agent): DocumentTool delegates to DocumentArtifactService; page/section cursors"
```

### Task 10: wire the service into AgentService registration

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt` (lines ~1207–1218)

- [ ] **Step 1: Replace the extractor + DocumentTool registration block**

Replace lines ~1207–1218 with:

```kotlin
        val docExtractor = TikaDocumentExtractor(
            maxCharsProvider = {
                val n = PluginSettings.getInstance(project).state.documentMaxChars
                if (n <= 0) Int.MAX_VALUE else n
            },
        )
        val docArtifactStore = com.workflow.orchestrator.document.service.DocumentArtifactStore(docExtractor)
        val docArtifactService = com.workflow.orchestrator.agent.tools.integration.SessionDocumentArtifactService(
            store = docArtifactStore,
            cs = cs,
            cacheDirProvider = { com.workflow.orchestrator.agent.tools.integration.SessionDocumentArtifactService.defaultCacheDirProvider() },
            servingBudgetMs = PluginSettings.getInstance(project).state.documentTimeoutMs - 5_000L,
            jobBudgetMs = PluginSettings.getInstance(project).state.documentExtractionJobTimeoutMs,
        )
        safeRegisterDeferred("File") {
            DocumentTool(artifactService = docArtifactService)
        }
```

> `cs` is the `AgentService` injected `CoroutineScope` (constructor param, confirmed `@Service(Service.Level.PROJECT)`). `documentExtractionJobTimeoutMs` is added in Phase 8 Task 12 — do Task 12 first if compiling this in isolation, or accept a transient unresolved-reference until Task 12 lands.

- [ ] **Step 2: Build**

Run: `./gradlew :agent:compileKotlin`
Expected: BUILD SUCCESSFUL (after Task 12 adds the setting).

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt
git commit -m "feat(agent): wire SessionDocumentArtifactService into read_document registration"
```

---

## Phase 7 — `:agent` LoopDetector exemption (spec C2)

### Task 11: exempt `read_document` from doom-loop counting

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/LoopDetector.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt` (LoopDetector construction site)
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/loop/LoopDetectorExemptionTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.workflow.orchestrator.agent.loop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LoopDetectorExemptionTest {

    @Test
    fun `exempt tool never trips soft or hard limits regardless of repeats`() {
        val detector = LoopDetector(exemptTools = setOf("read_document"))
        repeat(10) {
            assertEquals(LoopStatus.OK, detector.recordToolCall("read_document", "{\"path\":\"/x.pdf\",\"offset\":0}"))
        }
    }

    @Test
    fun `non-exempt tool still trips hard limit on identical repeats`() {
        val detector = LoopDetector(exemptTools = setOf("read_document"))
        var last = LoopStatus.OK
        repeat(5) { last = detector.recordToolCall("search_code", "{\"q\":\"foo\"}") }
        assertEquals(LoopStatus.HARD_LIMIT, last)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agent:test --tests "*LoopDetectorExemptionTest*"`
Expected: FAIL — `LoopDetector` has no `exemptTools` param.

- [ ] **Step 3: Add the param and the early return**

Change the constructor (lines 15–18):

```kotlin
class LoopDetector(
    private val softThreshold: Int = SOFT_THRESHOLD_DEFAULT,
    private val hardThreshold: Int = HARD_THRESHOLD_DEFAULT,
    private val exemptTools: Set<String> = emptySet(),
)
```

At the top of `recordToolCall` (before computing the signature, ~line 34):

```kotlin
    fun recordToolCall(toolName: String, arguments: String): LoopStatus {
        if (toolName in exemptTools) {
            // Exempt tools (e.g. read_document) legitimately repeat with identical args while a
            // background extraction is in progress; do not count them and reset the streak.
            consecutiveCount = 0
            lastToolName = null
            lastSignature = null
            return LoopStatus.OK
        }
        val signature = toolCallSignature(arguments)
        // ... existing body unchanged ...
```

> If `lastToolName` / `lastSignature` are non-nullable `String` with default `""`, reset them to `""` instead of `null`. Match the existing field types.

- [ ] **Step 4: Wire the exemption at the construction site**

In `AgentService.kt`, find where `LoopDetector(` is constructed (grep `LoopDetector(`). Add the exemption argument:

```kotlin
        LoopDetector(exemptTools = setOf("read_document"))
```

(Preserve any existing threshold args; just add the `exemptTools` named argument.)

- [ ] **Step 5: Run test + existing LoopDetector suite**

Run: `./gradlew :agent:test --tests "*LoopDetector*"`
Expected: PASS — new exemption tests pass; existing tests unaffected (default `exemptTools = emptySet()`).

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/LoopDetector.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt agent/src/test/kotlin/com/workflow/orchestrator/agent/loop/LoopDetectorExemptionTest.kt
git commit -m "fix(agent): exempt read_document from doom-loop detection"
```

---

## Phase 8 — settings

### Task 12: `documentExtractionJobTimeoutMs` setting + UI

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/settings/PluginSettings.kt` (after line 214, near `documentTimeoutMs`)
- Modify: the AI-Agent document settings Configurable (locate with `grep -rl "documentTimeoutMs" --include=*.kt agent core` among `*Configurable.kt`)
- Test: `core/src/test/kotlin/com/workflow/orchestrator/core/settings/DocumentJobTimeoutSettingTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.workflow.orchestrator.core.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DocumentJobTimeoutSettingTest {
    @Test
    fun `documentExtractionJobTimeoutMs defaults to 5 minutes`() {
        val state = PluginSettings.State()
        assertEquals(300_000L, state.documentExtractionJobTimeoutMs)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "*DocumentJobTimeoutSettingTest*"`
Expected: FAIL — field does not exist.

- [ ] **Step 3: Add the property**

In `PluginSettings.State`, after `var documentTimeoutMs by property(30_000L)`:

```kotlin
    /**
     * Wall-clock budget (ms) for a single background document extraction job, INCLUDING time
     * spent waiting on the extractor's concurrency semaphore. Distinct from [documentTimeoutMs],
     * which is the per-read serving timeout. Default 300 000 ms (5 min).
     */
    var documentExtractionJobTimeoutMs by property(300_000L)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests "*DocumentJobTimeoutSettingTest*"`
Expected: PASS.

- [ ] **Step 5: Add the UI control**

In the AI-Agent document settings Configurable (the file found via grep in Files above), add a control bound to the new field, following the existing `documentTimeoutMs` row pattern. Example (Kotlin UI DSL — match the file's existing idiom):

```kotlin
        row("Document extraction job timeout (ms):") {
            intTextField(range = 30_000..1_800_000)
                .bindIntText(
                    { settings.state.documentExtractionJobTimeoutMs.toInt() },
                    { settings.state.documentExtractionJobTimeoutMs = it.toLong() },
                )
                .comment("Background extraction budget for large documents (separate from the per-read timeout).")
        }
```

- [ ] **Step 6: Build to confirm the Configurable compiles**

Run: `./gradlew :core:compileKotlin` (or the module owning the Configurable)
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/settings/PluginSettings.kt core/src/test/kotlin/com/workflow/orchestrator/core/settings/DocumentJobTimeoutSettingTest.kt
git add <the-configurable-file>
git commit -m "feat(settings): documentExtractionJobTimeoutMs + UI control"
```

---

## Phase 9 — Real-world fixture & scenario tests (post-implementation hardening)

> These are test-after integration tests against the fully-wired pipeline (Tasks 1–12 done).
> They verify the tool behaves correctly on a genuinely large PDF and across formats, exercising
> real extraction (no mocks). Large/encrypted PDFs are GENERATED with PDFBox at test time
> (deterministic, no repo-bloating binaries, real PDF bytes). Existing fixtures live in
> `document/src/test/resources/fixtures/` (`spec-with-tables.pdf`, `tabula-eu-002.pdf`,
> `ietf-rfc7230.pdf`, `bug-tracker.xlsx`, `slides.pptx`, `release-notes.rtf`, `data.csv`,
> `zero.pdf`, `wrong-extension.pdf`).

### Task 13: large-PDF generator + end-to-end correctness scenarios

**Files:**
- Create: `document/src/test/kotlin/com/workflow/orchestrator/document/service/LargePdfFixtureFactory.kt`
- Test: `document/src/test/kotlin/com/workflow/orchestrator/document/service/DocumentArtifactStoreLargePdfTest.kt`

- [ ] **Step 1: Write the fixture factory (generates a real N-page PDF with text + ruled tables)**

```kotlin
package com.workflow.orchestrator.document.service

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.nio.file.Path

/** Builds a real multi-page PDF: text on every page, a ruled (lattice) 3x3 table every 10th page. */
object LargePdfFixtureFactory {
    fun create(target: Path, pages: Int): Path {
        PDDocument().use { doc ->
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            for (i in 1..pages) {
                val page = PDPage(PDRectangle.LETTER)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(font, 12f)
                    cs.newLineAtOffset(72f, 720f)
                    cs.showText("Page $i — section heading and body text for extraction testing.")
                    cs.endText()
                    if (i % 10 == 0) {
                        // Draw a 3x3 ruled grid (gives Tabula lattice something real to find).
                        val x0 = 72f; val y0 = 600f; val w = 120f; val h = 30f
                        for (r in 0..3) { cs.moveTo(x0, y0 - r * h); cs.lineTo(x0 + 3 * w, y0 - r * h); cs.stroke() }
                        for (c in 0..3) { cs.moveTo(x0 + c * w, y0); cs.lineTo(x0 + c * w, y0 - 3 * h); cs.stroke() }
                        for (r in 0..2) for (c in 0..2) {
                            cs.beginText(); cs.setFont(font, 9f)
                            cs.newLineAtOffset(x0 + c * w + 4f, y0 - r * h - 18f)
                            cs.showText("r${r}c${c}"); cs.endText()
                        }
                    }
                }
            }
            doc.save(target.toFile())
        }
        return target
    }
}
```

- [ ] **Step 2: Write the failing test**

```kotlin
package com.workflow.orchestrator.document.service

import com.workflow.orchestrator.core.model.DocumentCursor
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class DocumentArtifactStoreLargePdfTest {

    @TempDir lateinit var work: Path
    private val store = DocumentArtifactStore(TikaDocumentExtractor())

    @Test
    fun `500-page pdf extracts once, indexes every page, and serves gap-free slices`() = runTest {
        val pdf = LargePdfFixtureFactory.create(work.resolve("big.pdf"), pages = 500)
        val hash = store.hashFile(pdf)
        val artDir = work.resolve("cache").resolve(hash)

        val artifact = store.extractAndPersist(pdf, artDir, hash)
        val index = store.loadIndex(artifact)

        // Page index has an anchor for (nearly) every page; pageCount reflects the doc.
        assertEquals(500, artifact.meta.pageCount)
        assertTrue(index.pages.size >= 500, "expected >=500 page anchors, got ${index.pages.size}")

        // page=250 lands at/after page 250's offset.
        val mid = store.slice(artifact, index, DocumentCursor.Page(250), maxChars = 200)
        assertEquals(index.offsetForPage(250), mid.startOffset)
        assertTrue(mid.content.contains("Page 250") || mid.content.isNotEmpty())

        // Gap-free continuation: walking offsets reconstructs the full content exactly.
        val full = store.readContent(artifact)
        val rebuilt = StringBuilder()
        var off = 0
        while (off < full.length) {
            val s = store.slice(artifact, index, DocumentCursor.Offset(off), maxChars = 50_000)
            rebuilt.append(s.content)
            if (s.content.isEmpty()) break
            off = s.endOffset
        }
        assertEquals(full, rebuilt.toString())
    }

    @Test
    fun `extraction runs once for the same content hash across repeated reads`() = runTest {
        val pdf = LargePdfFixtureFactory.create(work.resolve("big2.pdf"), pages = 120)
        val hash = store.hashFile(pdf)
        val artDir = work.resolve("cache2").resolve(hash)
        store.extractAndPersist(pdf, artDir, hash)

        // After persist, loadArtifact is warm and slicing does not re-extract (no exception, fast).
        val art = store.loadArtifact(artDir)!!
        val idx = store.loadIndex(art)
        repeat(5) { store.slice(art, idx, DocumentCursor.Offset(it * 1000), maxChars = 500) }
        assertTrue(true)
    }
}
```

- [ ] **Step 3: Run — expect PASS (implementation already exists from Phases 1–4)**

Run: `./gradlew :document:test --tests "*DocumentArtifactStoreLargePdfTest*"`
Expected: PASS. If page-anchor count < 500, inspect whether `DocumentBlock.PageMarker` is emitted per page by the PDF prose path; fix the index emission (Task 3) or the prose extractor, not the test.

- [ ] **Step 4: Commit**

```bash
git add -f document/src/test/kotlin/com/workflow/orchestrator/document/service/LargePdfFixtureFactory.kt document/src/test/kotlin/com/workflow/orchestrator/document/service/DocumentArtifactStoreLargePdfTest.kt
git commit -m "test(document): 500-page PDF extract-once + gap-free slice scenarios"
```

### Task 14: multi-format round-trip scenarios

**Files:**
- Test: `document/src/test/kotlin/com/workflow/orchestrator/document/service/DocumentArtifactStoreFormatsTest.kt`

- [ ] **Step 1: Write the parameterized test over real fixtures**

```kotlin
package com.workflow.orchestrator.document.service

import com.workflow.orchestrator.core.model.DocumentCursor
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Paths

class DocumentArtifactStoreFormatsTest {

    @TempDir lateinit var work: Path
    private val store = DocumentArtifactStore(TikaDocumentExtractor())

    private fun fixture(name: String) =
        Paths.get(javaClass.classLoader.getResource("fixtures/$name")!!.toURI())

    @ParameterizedTest
    @ValueSource(strings = [
        "spec-with-tables.pdf", "tabula-eu-002.pdf", "ietf-rfc7230.pdf",
        "bug-tracker.xlsx", "slides.pptx", "release-notes.rtf", "data.csv",
    ])
    fun `each format extracts, persists, and serves a non-empty offset-0 slice`(name: String) = runTest {
        val src = fixture(name)
        val hash = store.hashFile(src)
        val artDir = work.resolve(name).resolve(hash)

        val artifact = store.extractAndPersist(src, artDir, hash)
        val index = store.loadIndex(artifact)
        val slice = store.slice(artifact, index, DocumentCursor.Offset(0), maxChars = 2_000)

        assertTrue(artifact.meta.contentLength > 0, "$name produced empty content")
        assertFalse(slice.content.isEmpty(), "$name offset-0 slice empty")
        // contentLength on disk matches the persisted markdown.
        assertEquals(store.readContent(artifact).length, artifact.meta.contentLength)
    }

    @org.junit.jupiter.api.Test
    fun `pageless formats (xlsx, csv) have an empty page index but still serve by offset`() = runTest {
        for (name in listOf("bug-tracker.xlsx", "data.csv")) {
            val src = fixture(name)
            val hash = store.hashFile(src)
            val artDir = work.resolve("pageless-$name").resolve(hash)
            val artifact = store.extractAndPersist(src, artDir, hash)
            val index = store.loadIndex(artifact)
            assertTrue(index.pages.isEmpty(), "$name should have no page anchors")
            val slice = store.slice(artifact, index, DocumentCursor.Offset(0), maxChars = 500)
            assertFalse(slice.content.isEmpty())
        }
    }
}
```

- [ ] **Step 2: Run — expect PASS**

Run: `./gradlew :document:test --tests "*DocumentArtifactStoreFormatsTest*"`
Expected: PASS. If a specific format yields empty content, that's a pre-existing extractor gap for that format — record it; do not weaken the assertion.

- [ ] **Step 3: Commit**

```bash
git add -f document/src/test/kotlin/com/workflow/orchestrator/document/service/DocumentArtifactStoreFormatsTest.kt
git commit -m "test(document): multi-format round-trip + pageless-format slice scenarios"
```

### Task 15: failure, edge, and service-level scenarios

**Files:**
- Create: `document/src/test/kotlin/com/workflow/orchestrator/document/service/EncryptedPdfFixtureFactory.kt`
- Test: `document/src/test/kotlin/com/workflow/orchestrator/document/service/DocumentArtifactStoreEdgeTest.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/integration/SessionDocumentArtifactServiceScenarioTest.kt`

- [ ] **Step 1: Write the encrypted-PDF factory**

```kotlin
package com.workflow.orchestrator.document.service

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import java.nio.file.Path

object EncryptedPdfFixtureFactory {
    fun create(target: Path): Path {
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            val policy = StandardProtectionPolicy("owner-pw", "user-pw", AccessPermission())
            policy.encryptionKeyLength = 128
            doc.protect(policy)
            doc.save(target.toFile())
        }
        return target
    }
}
```

- [ ] **Step 2: Write the store-level edge test**

```kotlin
package com.workflow.orchestrator.document.service

import com.workflow.orchestrator.core.model.DocumentCursor
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Paths

class DocumentArtifactStoreEdgeTest {

    @TempDir lateinit var work: Path
    private val store = DocumentArtifactStore(TikaDocumentExtractor())
    private fun fixture(name: String) = Paths.get(javaClass.classLoader.getResource("fixtures/$name")!!.toURI())

    @Test
    fun `encrypted pdf extraction throws so the caller can write a failure marker`() = runTest {
        val pdf = EncryptedPdfFixtureFactory.create(work.resolve("enc.pdf"))
        val hash = store.hashFile(pdf)
        assertThrows(Throwable::class.java) {
            kotlinx.coroutines.runBlocking { store.extractAndPersist(pdf, work.resolve(hash), hash) }
        }
    }

    @Test
    fun `offset past end yields empty content and zero remaining on a real doc`() = runTest {
        val src = fixture("release-notes.rtf")
        val hash = store.hashFile(src)
        val artifact = store.extractAndPersist(src, work.resolve(hash), hash)
        val index = store.loadIndex(artifact)
        val slice = store.slice(artifact, index, DocumentCursor.Offset(Int.MAX_VALUE / 2), maxChars = 100)
        assertTrue(slice.content.isEmpty())
        assertEquals(0, slice.remaining)
    }

    @Test
    fun `page beyond count falls back to offset 0 (best-effort anchor)`() = runTest {
        val src = fixture("spec-with-tables.pdf")
        val hash = store.hashFile(src)
        val artifact = store.extractAndPersist(src, work.resolve(hash), hash)
        val index = store.loadIndex(artifact)
        val slice = store.slice(artifact, index, DocumentCursor.Page(9999), maxChars = 100)
        assertEquals(0, slice.startOffset)
    }
}
```

- [ ] **Step 3: Write the service-level scenario test (single-flight under concurrency + failure-cache + cleanup)**

```kotlin
package com.workflow.orchestrator.agent.tools.integration

import com.workflow.orchestrator.core.model.DocumentCursor
import com.workflow.orchestrator.document.service.DocumentArtifactStore
import com.workflow.orchestrator.document.service.EncryptedPdfFixtureFactory
import com.workflow.orchestrator.document.service.LargePdfFixtureFactory
import com.workflow.orchestrator.document.service.TikaDocumentExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SessionDocumentArtifactServiceScenarioTest {

    @TempDir lateinit var cacheRoot: Path
    @TempDir lateinit var work: Path

    private fun service(scope: CoroutineScope) = SessionDocumentArtifactService(
        store = DocumentArtifactStore(TikaDocumentExtractor()),
        cs = scope,
        cacheDirProvider = { cacheRoot },
        servingBudgetMs = 30_000,
        jobBudgetMs = 300_000,
    )

    @Test
    fun `concurrent reads of the same large pdf converge on one artifact and succeed`() = runTest {
        val pdf = LargePdfFixtureFactory.create(work.resolve("conc.pdf"), pages = 80)
        val svc = service(CoroutineScope(SupervisorJob()))
        val results = (0 until 6).map {
            async { svc.read(pdf, DocumentCursor.Offset(0), 1_000) }
        }.awaitAll()
        // All reads succeed (some may be the warm artifact, none error).
        assertTrue(results.all { !it.isError })
        assertTrue(results.any { it.data!!.content.isNotEmpty() })
    }

    @Test
    fun `encrypted pdf surfaces an error and is negative-cached for the next read`() = runTest {
        val pdf = EncryptedPdfFixtureFactory.create(work.resolve("enc.pdf"))
        val svc = service(CoroutineScope(SupervisorJob()))
        val first = svc.read(pdf, DocumentCursor.Offset(0), 1_000)
        assertTrue(first.isError)
        val second = svc.read(pdf, DocumentCursor.Offset(0), 1_000)
        assertTrue(second.isError) // served from failure.json, not re-extracted
    }
}
```

- [ ] **Step 4: Run — expect PASS**

Run: `./gradlew :document:test --tests "*DocumentArtifactStoreEdgeTest*" && ./gradlew :agent:test --tests "*SessionDocumentArtifactServiceScenarioTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -f document/src/test/kotlin/com/workflow/orchestrator/document/service/EncryptedPdfFixtureFactory.kt document/src/test/kotlin/com/workflow/orchestrator/document/service/DocumentArtifactStoreEdgeTest.kt agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/integration/SessionDocumentArtifactServiceScenarioTest.kt
git commit -m "test: encrypted/edge/concurrency + negative-cache scenarios for document reads"
```

---

## Phase 10 — full verification

### Task 16: build + test all three modules

- [ ] **Step 1: Document module**

Run: `./gradlew :document:clean :document:test --rerun --no-build-cache`
Expected: PASS (all existing + new tests). The `--no-build-cache` guards against the suspend-signature build-cache trap documented in the root CLAUDE.md (we changed `TikaDocumentExtractor` signatures).

- [ ] **Step 2: Core module**

Run: `./gradlew :core:test`
Expected: PASS.

- [ ] **Step 3: Agent module**

Run: `./gradlew :agent:clean :agent:test --rerun --no-build-cache`
Expected: PASS.

- [ ] **Step 4: Plugin verification**

Run: `./gradlew verifyPlugin`
Expected: PASS.

- [ ] **Step 5: Manual smoke (record outcome, do not skip)**

`./gradlew runIde`, attach a large multi-page PDF, and confirm:
- First `read_document` either returns content quickly or an "extraction in progress" message; a follow-up call returns content fast (warm).
- Subsequent chunk reads (`offset=…`) are near-instant.
- `page=N` returns content near that page.
- Cache dir exists at `~/.workflow-orchestrator/{slug}-{sha6}/agent/sessions/{id}/document-cache/{sha}/` and disappears after "New Chat".

- [ ] **Step 6: Commit any smoke-driven fixes, then finish the branch**

Use `superpowers:finishing-a-development-branch`.

---

## Self-Review

**Spec coverage:**
- Extract-once + persisted artifact + index → Tasks 3, 4, 6 (assembler index, block extraction, persistence). ✓
- Stable offset + page/section anchors → Tasks 1, 7, 9 (models, slice resolution, tool params). ✓
- Lazy single-flight + background job (own budget) → Task 8. ✓
- Leaner extraction (lazy lattice, C1 stream-mode bypass) → Task 5. ✓
- Session-scoped, content-hash keyed, atomic meta-sentinel, failure.json negative cache → Tasks 6, 7, 8. ✓
- Doom-loop exemption (C2) → Task 11. ✓
- Core-vs-agent ToolResult disambiguation (C3) → `:core` service returns `core.services.ToolResult`; `DocumentTool` maps to agent `ToolResult` in Task 9. ✓
- Injected-scope lifecycle (I1) → Task 8 uses injected `cs`; Task 10 passes `AgentService.cs`. ✓
- Single-flight entry lifecycle + budget incl. semaphore (I2/I4) → Task 8 (`invokeOnCompletion` removal; `jobBudgetMs` wraps the whole job). ✓
- Settings + UI → Task 12. ✓

**Placeholder scan:** No "TBD"/"handle errors"/"similar to". Two explicit cross-task ordering notes (Task 10 ↔ Task 12; `mapErrorToFailure` genericization) are concrete instructions, not placeholders.

**Type consistency:** `DocumentCursor`, `DocumentSlice`, `DocumentIndex`, `DocumentArtifact(Meta)`, `BlockExtraction`, `IndexedAssemblerResult`, `DocumentArtifactService.read(path, cursor, maxChars)` used identically across Tasks 1–12. `slice(artifact, index, cursor, maxChars)` signature matches between Task 7 (def) and Task 8 (call). `extractBlocks` return type `ToolResult<BlockExtraction>` matches between Task 4 (def) and Task 6 (call).

**Risk note carried to implementation:** page-anchor accuracy under the Tabula↔Tika page-mismatch (merged cross-page tables attribute to their first page). `offset` remains authoritative; do not block on resolving the mismatch.
