package com.workflow.orchestrator.core.model

/**
 * Extraction parameters passed to [com.workflow.orchestrator.core.api.DocumentExtractor.extract].
 *
 * All fields have safe defaults so callers that only need standard behaviour can use
 * `ExtractOptions()` without specifying anything.
 *
 * Settings are **read per-call** inside the extractor implementation so that mid-session
 * changes to `PluginSettings` (e.g. the user increasing `documentMaxChars` in the IDE
 * settings UI) take effect on the next extraction without an IDE restart. This mirrors
 * the `HttpClientFactory.timeoutsFromSettings()` pattern used elsewhere in `:core`.
 *
 * @param maxChars          Maximum number of characters to include in the assembled Markdown.
 *                          When the extracted content exceeds this budget the output is
 *                          truncated and [com.workflow.orchestrator.core.model.DocumentContent.truncated]
 *                          is set to `true`. `null` means: use `PluginSettings.documentMaxChars`
 *                          at call time (the default). Must be positive when explicitly set.
 * @param timeoutMs         Wall-clock timeout for the entire extraction operation in
 *                          milliseconds. When exceeded, `ToolResult.Failure` is returned
 *                          with a timeout message. Must be positive. Default: 30 000 ms.
 * @param includeEmbedded   When `true`, the pipeline attempts to enumerate embedded files
 *                          (images, OLE attachments) and emit [DocumentBlock.EmbeddedFileRef]
 *                          blocks for them. `false` in v1 (no embedded content is extracted).
 * @param enableStreamMode  When `true`, the Tabula PDF table extractor falls back to
 *                          stream (whitespace-alignment) mode when lattice mode finds no
 *                          tables on a page. **Off by default** because stream mode
 *                          false-positives on multi-column prose pages, producing phantom
 *                          tables. Should only be enabled when the document is known to use
 *                          whitespace-aligned tables without visible ruling lines.
 */
data class ExtractOptions(
    val maxChars: Int? = null,
    val timeoutMs: Long = 30_000L,
    val includeEmbedded: Boolean = false,
    val enableStreamMode: Boolean = false,
) {
    init {
        require(maxChars == null || maxChars > 0) {
            "maxChars must be positive when set, was $maxChars"
        }
        require(timeoutMs > 0) {
            "timeoutMs must be positive, was $timeoutMs"
        }
    }
}
