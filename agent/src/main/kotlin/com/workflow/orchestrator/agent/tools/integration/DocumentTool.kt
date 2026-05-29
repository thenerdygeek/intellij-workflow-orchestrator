package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolOutputConfig
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.docs.Relationship
import com.workflow.orchestrator.agent.tools.docs.SideEffectKind
import com.workflow.orchestrator.agent.tools.docs.ToolDocumentation
import com.workflow.orchestrator.agent.tools.docs.VerdictSeverity
import com.workflow.orchestrator.agent.tools.docs.toolDoc
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.core.services.DocumentArtifactService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.InvalidPathException
import java.nio.file.Paths

/**
 * Agent tool that delegates to [DocumentArtifactService] in the deferred tier.
 *
 * Registered as a deferred tool so it is discoverable via `tool_search` but does not burn
 * prompt tokens on every iteration. The LLM uses `read_file` for plain text; this tool
 * handles the binary/structured formats that `read_file` rejects.
 *
 * Registered in [com.workflow.orchestrator.agent.AgentService.registerAllTools] under the
 * "File" category.
 */
class DocumentTool(
    private val artifactService: DocumentArtifactService,
    /**
     * Per-call tool timeout forwarded to [AgentLoop]'s `withTimeoutOrNull` gate.
     * Must exceed the extraction job budget ([SessionDocumentArtifactService.jobBudgetMs])
     * so AgentLoop does not kill a blocking [read_document] call before extraction finishes.
     * Defaults to 360 000 ms (6 minutes), which safely brackets the default 300 s job budget
     * plus any overhead.
     */
    override val timeoutMs: Long = 360_000L,
) : AgentTool {

    override val name = "read_document"

    override val description = """
        Read text content from a non-plaintext document file. Supports PDF, Word (DOC/DOCX),
        Excel (XLS/XLSX), PowerPoint (PPT/PPTX), RTF, ODT, EPUB, HTML, CSV.

        Use this when read_file rejects the file by extension. Common cases: a Jira PDF
        attachment, a downloaded build artifact, a requirements spec, a design doc, an
        engineering report, a bug-tracker spreadsheet.

        Returns Markdown with tables preserved as Markdown pipe tables (so you can reason
        about row/column relationships accurately). Multi-column PDFs are read in correct
        order. PDF tables are extracted via Tabula's lattice algorithm and merged across
        page boundaries. Office documents are read directly via Apache POI for cell-perfect
        accuracy.

        For scanned image PDFs without an embedded text layer, this tool returns an error.
        OCR is not available in v1.

        Default cap is 200 K characters; oversized documents are truncated with a marker.
        max_chars is a total output cap, not a page cursor — re-calling read_document with
        the same path always returns the same prefix. To read subsequent chunks of a long
        document, pass `offset` (number of characters to skip from the start of the
        extracted Markdown); the response ends with a continuation hint telling you the
        next offset to use.

        Embedded images surface as `[image: <path>] (<mime>)` markers in the output.
        To load any image into your vision context, call `view_image(path)`.

        To FIND content instead of reading sequentially, pass `search="your query"`. The tool then
        returns ranked matching snippets — each with its page, section, and character offset — instead
        of a content slice. Use the reported offset/page/section to read more there with a follow-up
        offset=/page=/section= call. `search` is its own mode: it overrides offset/page/section.
    """.trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "path" to ParameterProperty(
                type = "string",
                description = "Absolute path to the document file. Must be readable.",
            ),
            "max_chars" to ParameterProperty(
                type = "integer",
                description = "Total characters returned in this call (default 200000). " +
                    "Not a page cursor — use `offset` to read subsequent chunks of a longer document.",
            ),
            "offset" to ParameterProperty(
                type = "integer",
                description = "Number of characters to skip from the start of the extracted Markdown " +
                    "(default 0). Pair with max_chars to paginate through documents longer than the cap. " +
                    "The response ends with `[... N more characters available; call read_document(offset=X) " +
                    "to continue ...]` whenever there is more content past the slice.",
            ),
            "page" to ParameterProperty(
                type = "integer",
                description = "Optional. Jump to this 1-based page number (PDF/PPTX). Resolved via the " +
                    "document index; offset remains authoritative. Mutually exclusive with offset/section.",
            ),
            "section" to ParameterProperty(
                type = "string",
                description = "Optional. Jump to the heading with this text. Matching is case-insensitive and " +
                    "tolerant: a leading section number is ignored ('Digital Identity Model' matches " +
                    "'4 Digital Identity Model') and punctuation/casing is normalized ('fetch-product-metadata' " +
                    "matches 'Fetch Product Metadata'). If no heading matches, the tool says so and lists the " +
                    "available section names. Mutually exclusive with offset/page.",
            ),
            "search" to ParameterProperty(
                type = "string",
                description = "Optional. FIND content instead of reading a slice. Returns ranked matching " +
                    "snippets — each with its page, section, and character offset — instead of a content " +
                    "window. Case-insensitive; all whitespace-separated terms must appear (a single term is a " +
                    "plain substring search); a phrase (terms adjacent, in order) ranks highest. Capped at the " +
                    "top ~15 hits with the true total reported. SEARCH IS ITS OWN MODE: when present it overrides " +
                    "offset/page/section. Use the offset/page/section from a hit to then read more there.",
            ),
        ),
        required = listOf("path"),
    )

    override val allowedWorkers = setOf(
        WorkerType.ORCHESTRATOR,
        WorkerType.CODER,
        WorkerType.REVIEWER,
        WorkerType.ANALYZER,
        WorkerType.TOOLER,
    )

    /** Spill to disk when output exceeds 30K chars (matches expected size for big docs). */
    override val outputConfig = ToolOutputConfig(maxChars = ToolOutputConfig.COMMAND_MAX_CHARS)

    override fun documentation(): ToolDocumentation = toolDoc("read_document") {
        summary {
            technical(
                "Extract Markdown text from binary/structured document files (PDF, DOCX, XLSX, PPTX, RTF, ODT, EPUB, HTML, CSV) " +
                    "via Apache Tika/POI; PDF tables extracted via Tabula lattice algorithm; returns Markdown with pipe tables; " +
                    "200K char cap with truncation marker; scanned image PDFs unsupported (no OCR)."
            )
            plain(
                "Like read_file, but for binary documents — hand this tool a PDF or Word file and it converts the content to " +
                    "readable Markdown (tables and all), the same way a document viewer shows you a nicely laid-out page instead " +
                    "of raw bytes."
            )
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.READ_ONLY)
        counterfactual(
            "Without read_document the LLM cannot open binary documents at all — read_file explicitly rejects PDF, DOC, DOCX, " +
                "XLS, XLSX, and similar extensions and returns a binary-file error. The only alternative is run_command with " +
                "`pdftotext`/`pandoc`/`unzip`, which (a) depends on those tools being installed on the host machine, " +
                "(b) produces unstructured plain text that loses table layout, and (c) bypasses the 200K safety cap, " +
                "potentially flooding the context with hundreds of KB of raw extraction output."
        )
        llmMistake(
            "Calls read_document on a plain-text file (e.g. a `.txt` or `.csv` opened directly). read_document can handle CSV " +
                "and HTML, but for files read_file already supports the line-number-prefixed output of read_file is strictly " +
                "better for subsequent edit_file references. The LLM should reserve read_document for formats read_file " +
                "explicitly rejects."
        )
        llmMistake(
            "Passes a relative path and gets an InvalidPathException because DocumentTool does not resolve relative paths " +
                "through the project root the way read_file does. Always supply an absolute path."
        )
        llmMistake(
            "Tries to use max_chars=10000 to 'page' through a large PDF and re-calls with the same path expecting " +
                "the next chunk. max_chars is a total output cap, not a page cursor — re-calling without `offset` " +
                "always returns the same prefix. To paginate, capture the `offset=N` value from the continuation hint " +
                "(`[... 12345 more characters available; call read_document(offset=200000) to continue ...]`) and pass " +
                "it on the next call."
        )
        llmMistake(
            "Calls read_document on a scanned image PDF (no embedded text layer) and interprets the empty or near-empty " +
                "result as an empty document. The tool returns an explicit error for scanned-image PDFs in v1; the LLM " +
                "should surface that error to the user instead of proceeding on an empty result."
        )
        params {
            required("path", "string") {
                llmSeesIt("Absolute path to the document file. Must be readable.")
                humanReadable(
                    "The full filesystem path to the document — e.g. `/Users/me/Downloads/spec.pdf`. " +
                        "Unlike read_file, relative paths are NOT resolved against the project root; they will throw " +
                        "an InvalidPathException. Always use an absolute path."
                )
                whenPresent(
                    "The path is parsed, the file is located and opened by TikaDocumentExtractor, and the " +
                        "extracted Markdown is returned."
                )
                constraint("must be an absolute path — relative paths throw InvalidPathException before any I/O")
                constraint("file must be readable by the JVM process — permissions errors are surfaced as an extraction error")
                example("/Users/me/Downloads/requirements.pdf")
                example("/home/dev/project/docs/design.docx")
                example("/tmp/build-report.xlsx")
            }
            optional("max_chars", "integer") {
                llmSeesIt(
                    "Total characters returned in this call (default 200000). " +
                        "Not a page cursor — use `offset` to read subsequent chunks of a longer document."
                )
                humanReadable(
                    "A hard cap on how many characters of Markdown are returned in this call. This is a total " +
                        "output cap, NOT a page cursor — re-calling read_document with the same path always returns " +
                        "the same prefix. To read past the cap, pair it with `offset`. Lowering max_chars keeps " +
                        "the response token-efficient for large documents; raising it (up to ~500K is reasonable) " +
                        "gives more coverage in a single response."
                )
                whenPresent("Extraction halts and inserts a `[... truncated at N chars ...]` marker when this limit is reached.")
                whenAbsent("Defaults to 200 000 characters — roughly 60-80 pages of dense text or ~50K tokens.")
                constraint("must be a positive integer; very small values (< 500) may produce a truncation marker before any content arrives")
                example("50000")
                example("500000")
            }
            optional("offset", "integer") {
                llmSeesIt(
                    "Number of characters to skip from the start of the extracted Markdown (default 0). " +
                        "Pair with max_chars to paginate through documents longer than the cap. " +
                        "The response ends with `[... N more characters available; call read_document(offset=X) " +
                        "to continue ...]` whenever there is more content past the slice."
                )
                humanReadable(
                    "The continuation cursor for paging through a long document. On the first call you typically " +
                        "omit it; if the response ends with a `[... N more characters available; call " +
                        "read_document(offset=X) ...]` hint, capture X and pass it as `offset` on the next call " +
                        "to read the next slice. The slice is a pure string offset into the extracted Markdown " +
                        "(not a PDF page number) — the underlying extractor still re-parses the whole document, " +
                        "but the LLM sees a fresh window of content rather than the same prefix."
                )
                whenPresent(
                    "Extraction yields the full Markdown up to offset+max_chars, then this tool slices " +
                        "[offset, offset+max_chars) and appends a continuation hint if any content remains."
                )
                whenAbsent("Defaults to 0 — returns the first max_chars characters from the document.")
                constraint("must be non-negative; negative offsets return an error without re-extracting")
                constraint(
                    "when offset >= extracted document length, returns an `[offset N is at or beyond extracted " +
                        "document length M; end of document reached, no more content to read]` message — NOT an " +
                        "error. The LLM should stop paging at that point."
                )
                example("0")
                example("200000")
            }
            optional("page", "integer") {
                llmSeesIt(
                    "Optional. Jump to this 1-based page number (PDF/PPTX). Resolved via the document index; " +
                        "offset remains authoritative. Mutually exclusive with offset/section."
                )
                humanReadable(
                    "A convenience anchor that lets the LLM jump directly to a known page number rather than " +
                        "computing a character offset. Use page=N when you know which page to read (e.g. page 5 " +
                        "of a requirements spec). The service resolves the page to a character offset via the " +
                        "document index and returns a slice starting at that position. Mutually exclusive with " +
                        "`offset` and `section` — if more than one is supplied, `page` wins over `offset`, " +
                        "`section` wins over `page`."
                )
                whenPresent("The document index resolves the page number to a character offset; extraction starts there.")
                whenAbsent("Falls back to the `section` param, then `offset`, then 0.")
                constraint("must be a positive integer (1-based); page 0 or negative is treated as absent")
                constraint("not all formats expose page boundaries — if the index lacks page data, the tool falls back to offset=0")
                example("1")
                example("5")
            }
            optional("section", "string") {
                llmSeesIt(
                    "Optional. Jump to the heading with this text. Matching is case-insensitive and tolerant: a " +
                        "leading section number is ignored ('Digital Identity Model' matches '4 Digital Identity " +
                        "Model') and punctuation/casing is normalized ('fetch-product-metadata' matches 'Fetch " +
                        "Product Metadata'). If no heading matches, the tool says so and lists the available " +
                        "section names. Mutually exclusive with offset/page."
                )
                humanReadable(
                    "A convenience anchor that lets the LLM jump directly to a named section (heading) in the " +
                        "document rather than computing a character offset. Matching precedence: exact " +
                        "(case-insensitive) → number-stripped/normalized-equal → substring; the first match wins. " +
                        "Mutually exclusive with `offset` and `page` — `section` takes the highest priority when " +
                        "multiple cursor params are supplied."
                )
                whenPresent("The document index resolves the first matching heading to a character offset.")
                whenAbsent("Falls back to the `page` param, then `offset`, then 0.")
                constraint("matching precedence is exact → number-stripped/normalized → substring; first match wins")
                constraint(
                    "if no heading matches, the tool does NOT silently serve offset 0 — it returns the document " +
                        "start prefixed with a 'Section not found' notice that lists the available section names " +
                        "(or, when the document has no reliable anchors, advises navigating by page=N)"
                )
                example("Introduction")
                example("Functional Requirements")
            }
            optional("search", "string") {
                llmSeesIt(
                    "Optional. FIND content instead of reading a slice. Returns ranked matching snippets — " +
                        "each with its page, section, and character offset — instead of a content window. " +
                        "Case-insensitive; all whitespace-separated terms must appear (a single term is a plain " +
                        "substring search); a phrase (terms adjacent, in order) ranks highest. Capped at the top " +
                        "~15 hits with the true total reported. SEARCH IS ITS OWN MODE: when present it overrides " +
                        "offset/page/section. Use the offset/page/section from a hit to then read more there."
                )
                humanReadable(
                    "Full-text search across the extracted document. Instead of a content slice you get back a " +
                        "ranked list of snippets, each annotated with `[page N · §Section · offset X]` so you can " +
                        "immediately re-call read_document with that offset/page/section to read the surrounding " +
                        "passage. Think of it as Ctrl-F over the whole document with relevance ranking and " +
                        "navigation breadcrumbs baked in. Matching default: case-insensitive, every space-separated " +
                        "term must be present in a snippet window; an exact adjacent phrase ranks above scattered " +
                        "hits; ties break by document order."
                )
                whenPresent(
                    "The tool searches the extracted Markdown and returns up to ~15 ranked matches (with the true " +
                        "total hit count) — NOT a content slice. offset/page/section are ignored for this call."
                )
                whenAbsent("The tool reads a content slice as usual (offset/page/section/max_chars apply).")
                constraint(
                    "search is mutually exclusive with the slice cursors — when search is non-blank it WINS and " +
                        "offset/page/section/max_chars are ignored"
                )
                constraint("blank/whitespace-only search is treated as absent (the slice path runs instead)")
                constraint(
                    "plain text, not regex — special characters are matched literally; multiple words are an " +
                        "all-terms-must-appear query, not a regex alternation"
                )
                constraint(
                    "if there are zero matches the tool says so (NOT an error) and lists the available section " +
                        "names so you can navigate by section= instead"
                )
                example("AAL2 reauthentication")
                example("error budget")
                example("itemOptions")
            }
        }
        verdict {
            keep(
                "Uniquely necessary: the only way for the LLM to read binary office documents and PDFs without shelling out " +
                    "to host tools that may not be installed. read_file explicitly rejects these formats, and the table-preserving " +
                    "Markdown output is meaningfully better than raw pdftotext/pandoc for structured analysis tasks " +
                    "(e.g. reading a Jira PDF export, a requirements spec, or a test-results spreadsheet).",
                VerdictSeverity.STRONG,
            )
        }
        related(
            "read_file",
            Relationship.ALTERNATIVE,
            "Use read_file for plain-text files (source code, configs, logs, Markdown). Use read_document when " +
                "read_file returns a binary-file error — they handle complementary format sets with no overlap in the happy path.",
        )
        related(
            "search_code",
            Relationship.COMPLEMENT,
            "After read_document extracts Markdown, use search_code on the spilled output file to find specific " +
                "sections without re-extracting the whole document.",
        )
        related(
            "run_command",
            Relationship.ALTERNATIVE,
            "Fallback if read_document fails or the host has pdftotext/pandoc installed and structured Markdown is not " +
                "required — but run_command has no safety cap and no table preservation.",
        )
        downside(
            "Tabula↔Tika page-mismatch (known edge case, v1.1): when a PDF mixes Tabula-extracted table pages with " +
                "Tika-extracted text pages, the interleaving order can differ from the visual page order in a PDF viewer. " +
                "Text content is complete, but a table on page 4 might appear after the text from page 5 in the Markdown output. " +
                "Accepted limitation until a unified page-cursor is added in v2."
        )
        downside(
            "Scanned image PDFs (no embedded text layer) return an error — OCR is not available in v1. " +
                "The LLM must surface this to the user rather than proceeding on empty output."
        )
        downside(
            "Extraction fidelity varies by format: DOCX/XLSX via Apache POI gives cell-perfect accuracy; " +
                "older binary .doc/.xls formats go through Tika's HWPF/HSSF path which may lose some formatting. " +
                "Complex PDF layouts (multi-column academic papers, rotated text, watermarks) may produce " +
                "garbled or reordered text even when the text layer is present."
        )
        downside(
            "Large documents (> 200K chars, ~60 pages) are truncated at the max_chars boundary on the first call. " +
                "The `offset` parameter provides a continuation cursor — the response ends with a hint like " +
                "`[... N more characters available; call read_document(offset=X) to continue ...]` so the LLM can " +
                "page through the rest. Under the hood every call still re-parses the whole document via Tika " +
                "(there is no streaming-resume capability), but the LLM sees a fresh content window each time " +
                "instead of an identical prefix."
        )
        downside(
            "Extraction runs under a configurable wall-clock budget (PluginSettings.documentExtractionJobTimeoutMs, " +
                "default 5 minutes / 300 s) — a several-hundred-page PDF with dense Tabula lattice extraction can " +
                "approach or exceed it on a slow JVM. NOTE: lowering max_chars does NOT speed extraction up — the whole " +
                "document is always extracted before slicing, so max_chars only bounds the returned window. A genuine " +
                "timeout means the document is too large/complex for the budget; raise documentExtractionJobTimeoutMs in " +
                "Settings (Tools > Workflow Orchestrator > AI Agent) rather than retrying with a smaller max_chars. " +
                "A timeout is cached for ~1 hour, so an immediate identical retry returns the cached failure without re-extracting."
        )
        observation(
            "read_document is registered in the deferred tier (loaded via tool_search), which is correct — " +
                "the tool is niche enough that burning prompt tokens on it every iteration would be wasteful. " +
                "However, the system prompt for file-heavy tasks should mention it in the deferred catalog entry " +
                "so the LLM knows to search for it when read_file rejects a binary."
        )
        observation(
            "The 'document' category name in AgentService.registerAllTools is distinct from the tool name 'read_document'. " +
                "If a tool_search query is 'document' the tool is found; if the query is 'read_document' it is also found. " +
                "No action required — both aliases work."
        )
        flowchart(
            """
            flowchart TD
                A[LLM calls read_document] --> B{path param present?}
                B -- no --> X1[Return 'path required' error]
                B -- yes --> C{Path parseable?}
                C -- no --> X2[Return InvalidPathException error]
                C -- yes --> D[Resolve cursor: page/section/offset]
                D --> E[DocumentArtifactService.read]
                E --> F{Service error?}
                F -- yes --> X3[Return service error summary]
                F -- no --> G{remaining > 0?}
                G -- yes --> H[Append continuation hint with offset + page info]
                G -- no --> I[Return full slice content]
                H --> K[ToolResult with Markdown + TokenEstimator]
                I --> K
            """
        )
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val pathArg = params["path"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'path' parameter is required.", "Error: missing path",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val path = try { Paths.get(pathArg) } catch (e: InvalidPathException) {
            return ToolResult("Error: Invalid path: $pathArg — ${e.reason}", "Error: invalid path",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        // Search is its own MODE: when a non-blank `search` is present it WINS over offset/page/section
        // and returns ranked matching snippets instead of a content slice (G-10).
        val searchArg: String? = params["search"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        if (searchArg != null) {
            return executeSearch(path, searchArg)
        }

        val maxChars: Int? = runCatching { params["max_chars"]?.jsonPrimitive?.int }.getOrNull()
        val pageArg: Int? = runCatching { params["page"]?.jsonPrimitive?.int }.getOrNull()
        val sectionArg: String? = params["section"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val offsetArg: Int = runCatching { params["offset"]?.jsonPrimitive?.int ?: 0 }.getOrNull() ?: 0
        if (offsetArg < 0) {
            return ToolResult("Error: 'offset' must be non-negative (got $offsetArg).", "Error: negative offset",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        val cursor = when {
            pageArg != null -> com.workflow.orchestrator.core.model.DocumentCursor.Page(pageArg)
            sectionArg != null -> com.workflow.orchestrator.core.model.DocumentCursor.Section(sectionArg)
            else -> com.workflow.orchestrator.core.model.DocumentCursor.Offset(offsetArg)
        }

        val result = artifactService.read(path, cursor, maxChars)
        if (result.isError) {
            return ToolResult("Error: ${result.summary}", result.summary,
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
        val slice = result.data!!

        // Requirement C: a section= that resolved to nothing must NOT be served as a silent
        // top-of-document read. Surface an explicit miss with the valid navigation targets so the
        // LLM can correct itself instead of silently reasoning over the wrong content.
        val sectionMissBanner: String? = if (slice.sectionMatched == false) {
            if (slice.availableSections.isEmpty()) {
                val pages = slice.totalPages
                val pageHint = if (pages != null) "navigate with page=N ($pages pages)" else "navigate with offset=N"
                "[Section '$sectionArg' not found — no reliable section anchors in this document; $pageHint. " +
                    "Showing the document start below.]"
            } else {
                "[Section '$sectionArg' not found. Available sections: ${renderSectionList(slice.availableSections)}. " +
                    "Re-call with section=<one of these> (or page=N). Showing the document start below.]"
            }
        } else null

        val body = if (slice.content.isEmpty()) {
            // No content to show (extraction in progress, end-of-document, or empty result).
            // The LLM is fed `content`, not `summary`, so the explanation/instruction must go here.
            if (sectionMissBanner != null) "$sectionMissBanner\n\n${result.summary}" else result.summary
        } else buildString {
            if (sectionMissBanner != null) append(sectionMissBanner).append("\n\n")
            append(slice.content)
            if (slice.remaining > 0) {
                val pageClause = if (slice.pageOfStart != null && slice.totalPages != null)
                    " — page ${slice.pageOfStart} of ${slice.totalPages}" else ""
                append("\n\n[... ${slice.remaining} more characters available; call read_document(offset=${slice.endOffset}) to continue$pageClause ...]")
            }
            // Discoverability: surface valid section names on a normal (non-miss) read when there
            // is more to read, so the LLM learns it can jump with section=. Token-frugal one-liner.
            if (sectionMissBanner == null && slice.remaining > 0 && slice.availableSections.isNotEmpty()) {
                append("\n\n[Sections: ${renderSectionList(slice.availableSections)}]")
            }
        }
        return ToolResult(content = body, summary = result.summary,
            tokenEstimate = TokenEstimator.estimate(body), isError = false)
    }

    /**
     * Search mode (G-10): delegate to [DocumentArtifactService.search] and render the ranked match
     * list. A no-match search is a non-error result that surfaces the available section names so the
     * LLM can fall back to section navigation, mirroring the slice-path miss banner.
     */
    private suspend fun executeSearch(path: java.nio.file.Path, query: String): ToolResult {
        val result = artifactService.search(path, query, contextChars = null, resultCap = null)
        if (result.isError) {
            return ToolResult("Error: ${result.summary}", result.summary,
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
        val data = result.data!!
        val body = buildString {
            if (data.matches.isEmpty()) {
                append("No matches for \"${data.query}\".")
                if (data.availableSections.isNotEmpty()) {
                    append(" Available sections: ${renderSectionList(data.availableSections)}. ")
                    append("Try search= with different terms, or navigate by section=<one of these>.")
                } else {
                    append(" Try search= with different or fewer terms.")
                }
            } else {
                val shown = data.matches.size
                val header = if (data.totalHits > shown)
                    "$shown of ${data.totalHits} matches for \"${data.query}\" (ranked; capped at ${data.resultCap}):"
                else
                    "$shown match${if (shown == 1) "" else "es"} for \"${data.query}\":"
                append(header)
                data.matches.forEachIndexed { i, m ->
                    val page = m.page?.let { "page $it" } ?: "page ?"
                    val section = m.section?.let { " · §$it" } ?: ""
                    append("\n${i + 1}. [$page$section · offset ${m.offset}] ${m.snippet}")
                }
            }
        }
        return ToolResult(content = body, summary = result.summary,
            tokenEstimate = TokenEstimator.estimate(body), isError = false)
    }

    /** Joins section labels into a compact pipe-separated hint, capped to keep the output frugal. */
    private fun renderSectionList(sections: List<String>): String {
        val shown = sections.take(MAX_SECTIONS_IN_HINT)
        val suffix = if (sections.size > shown.size) " | …" else ""
        return shown.joinToString(" | ") + suffix
    }

    private companion object {
        /** Cap on the number of section labels rendered inline in a hint/miss message. */
        const val MAX_SECTIONS_IN_HINT = 30
    }
}
