package com.workflow.orchestrator.core.model

/**
 * The result of successfully extracting content from a binary document file.
 *
 * This is the `T` in `ToolResult<DocumentContent>` returned by
 * [com.workflow.orchestrator.core.api.DocumentExtractor.extract].
 *
 * ## Consumers
 * - **AI agent (primary):** reads [markdown] directly. The Markdown is formatted to
 *   preserve tables as pipe-tables and headings as `#` markers so the LLM can reason
 *   about structure without additional parsing.
 * - **Programmatic callers:** may also read [blocks] if they need typed structural
 *   access (e.g. to count tables, locate a specific heading, or post-process cells).
 *   [blocks] mirrors the intermediate representation used internally by the assembler;
 *   it is `null` unless the extraction pipeline was configured to retain it.
 *
 * @param markdown    Full extracted content formatted as Markdown. Always present on
 *                    a successful extraction, even if [truncated] is `true`.
 * @param mime        MIME type detected for the source file (e.g. `application/pdf`,
 *                    `application/vnd.openxmlformats-officedocument.wordprocessingml.document`).
 * @param pageCount   Number of pages in the source document, when available. `null` for
 *                    formats that have no page concept (e.g. XLSX, CSV).
 * @param title       Document title from embedded metadata, if present.
 * @param author      Document author from embedded metadata, if present.
 * @param truncated   `true` if the Markdown was cut off because the extracted text exceeded
 *                    the configured `maxChars` budget. A truncation marker is appended to
 *                    [markdown] when this flag is `true`.
 * @param blocks      Optional typed block list produced by the extraction pipeline. Present
 *                    only when the assembler is configured to retain intermediate blocks;
 *                    otherwise `null`. Each element is one of [DocumentBlock]'s subclasses.
 */
data class DocumentContent(
    val markdown: String,
    val mime: String,
    val pageCount: Int? = null,
    val title: String? = null,
    val author: String? = null,
    val truncated: Boolean = false,
    val blocks: List<DocumentBlock>? = null,
)
