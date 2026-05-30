package com.workflow.orchestrator.document

import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.CancellationException

private val SAFE_EXTRACT_LOG = Logger.getInstance("com.workflow.orchestrator.document.SafeExtract")

/**
 * Shared per-unit / per-stage isolation guard for the whole document extraction pipeline.
 *
 * ## Why
 *
 * A document is extracted as many independent UNITS — a page, a table, a prose block, an
 * image/XObject, an annotation, a sheet, a slide, a shape, a paragraph — or as several parallel
 * SUB-EXTRACTORS (Tabula tables, Tika prose, PDFBox metadata) whose results are merged. The
 * robustness contract is: **one malformed unit must yield a partial-but-useful result (skip that
 * unit), never lose the whole document or a sibling stream.** The motivating bug was
 * `SpreadsheetExtractionAlgorithm().extract(page)` throwing `IllegalArgumentException: lines must
 * be orthogonal …` on a skewed-ruling page and aborting the entire document's table extraction.
 *
 * [safeExtract] runs [block] and, on any [Throwable] *other than* cancellation, logs a WARN
 * carrying the [unit] label plus the exception and returns [fallback] so the surrounding per-unit
 * loop / stage-merge continues with the healthy units.
 *
 * ## Visibility, not silence
 *
 * Every guarded failure is logged at WARN with enough context (the [unit] label — caller should
 * pass something locating like `"PDF table page 7"` or `"XLSX sheet 'Summary'"`) so a swallowed
 * unit is diagnosable, never invisible.
 *
 * ## Cancellation safety
 *
 * This helper is invoked from inside `withContext(Dispatchers.IO)` blocks. A bare `runCatching`
 * would catch [CancellationException] and break cooperative cancellation; [safeExtract] therefore
 * **rethrows** [CancellationException] (and its `java.util.concurrent` analogue is a
 * `CancellationException` subtype, so it is covered) before treating anything else as a unit
 * failure.
 *
 * ## Scope discipline (do NOT mask real bugs)
 *
 * Apply this at the per-unit loop body and per-stage boundary ONLY — wrapping the external-parser
 * call or one iteration of a unit loop. Do NOT wrap a whole method whose internal logic errors
 * would then be silently swallowed, and do NOT use it at the OUTER document boundary
 * (`TikaDocumentExtractor` already maps a genuinely-empty/corrupt document to an error result —
 * guarding there would mask a true total failure).
 *
 * Happy path is byte-identical to calling [block] directly: the success value is returned
 * untouched and nothing is logged.
 *
 * ## Document-fatal exceptions ([rethrow])
 *
 * Some throwables are NOT per-unit quirks but whole-document failures that the caller must see as
 * an error rather than a silent empty result — an encrypted PDF, or a file so corrupt/truncated it
 * cannot be opened at all. When a sub-extractor stage is guarded, pass the document-fatal types in
 * [rethrow]; a thrown instance of any of them is re-raised unchanged (it propagates to
 * `TikaDocumentExtractor.mapErrorToFailure`, which turns it into a typed `isError=true` result —
 * "PDF is password-protected", "File appears corrupt", …). Everything else still degrades to
 * [fallback]. This is what keeps the guards from masking a truly-empty/corrupt document.
 *
 * @param unit     A short human-readable label locating the unit/stage for the WARN log.
 * @param fallback Returned when [block] throws a non-cancellation, non-[rethrow] [Throwable]
 *                 (typically [emptyList], the unmodified input, or `null`).
 * @param rethrow  Exception types that are document-fatal and must propagate (default: none).
 * @param block    The per-unit extraction to isolate.
 */
internal inline fun <T> safeExtract(
    unit: String,
    fallback: T,
    rethrow: Array<out Class<out Throwable>> = emptyArray(),
    block: () -> T,
): T =
    try {
        block()
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        if (rethrow.any { it.isInstance(t) }) throw t
        SAFE_EXTRACT_LOG.warn("Document extraction unit failed, skipping: $unit", t)
        fallback
    }
