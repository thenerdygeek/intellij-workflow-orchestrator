/**
 * Binary document extraction module for the Workflow Orchestrator plugin.
 *
 * Provides [com.workflow.orchestrator.core.api.DocumentExtractor] implementations that
 * convert PDFs, Office documents (DOCX, XLSX, PPTX), RTF, ODT, EPUB, HTML, and CSV files
 * into a structured Markdown representation suitable for LLM consumption.
 *
 * Pipeline:
 * - MIME detection via Apache Tika 3.x
 * - PDF tables via Tabula-java (lattice mode by default)
 * - PDF prose via Tika XHTML + PDFBox `sortByPosition=true`
 * - Office tables via Apache POI direct APIs (cell-perfect, zero heuristics)
 * - Other formats via Tika XHTML SAX handler
 * - Unified Markdown assembly via `MarkdownAssembler`
 *
 * This module depends only on `:core`; it is never imported by other feature modules directly.
 * The agent accesses document extraction through the core `DocumentExtractor` interface.
 */
package com.workflow.orchestrator.document
