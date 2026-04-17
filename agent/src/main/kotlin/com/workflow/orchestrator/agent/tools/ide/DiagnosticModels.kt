package com.workflow.orchestrator.agent.tools.ide

import com.intellij.codeInspection.ProblemHighlightType
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Structured per-item diagnostic data returned by inspection / problem / diagnostics tools.
 *
 * Intentionally a plain serializable data class with no IntelliJ PSI references,
 * so Phase 7's ToolOutputSpiller can serialize the full list to disk while the prose
 * preview is returned inline to the LLM.
 *
 * - [file] is an **absolute filesystem path**, sourced from `VirtualFile.path`.
 *   All T2–T6 tools MUST use this same representation. Do **not** substitute
 *   project-relative paths, `VirtualFile.presentableUrl`, VFS-style URIs, or any
 *   other path kind — Phase 7's link-back (chip click → navigateToFile) keys off
 *   this contract, and silent divergence between tools breaks cross-tool linking.
 * - [severity] is one of "ERROR", "WARNING", "WEAK_WARNING", "INFO".
 * - [toolId] is the inspection short name (e.g. "UnusedDeclaration") or the
 *   producing subsystem (e.g. "wolf", "daemon", "provider").
 * - [column] is 1-based; -1 means column is unknown.
 * - [category] is the IntelliJ group name when available ("Probable bugs",
 *   "Type hierarchy", …); null otherwise.
 *
 * Schema evolution: fields may be ADDED with defaults (readers with
 * `ignoreUnknownKeys = true` will accept the old format). Removing or renaming a
 * field is a breaking change that requires migrating Phase 7's spiller consumers.
 */
@Serializable
data class DiagnosticEntry(
    /**
     * Absolute filesystem path, sourced from `VirtualFile.path`. Do not use
     * project-relative or `presentableUrl` values — cross-tool consistency is
     * required for Phase 7 link-back.
     */
    val file: String,
    val line: Int,
    val column: Int,
    val severity: String,
    val toolId: String,
    val description: String,
    val hasQuickFix: Boolean,
    val category: String? = null,
)

/**
 * Delimiter marking where prose ends and the JSON-serialized structured list begins.
 * Phase 7's spiller grep's for this to route prose→preview, JSON→disk.
 * DO NOT change without updating ToolOutputSpiller consumers.
 *
 * Readers decode with `ignoreUnknownKeys = true`, so additive field changes are safe.
 */
const val DIAGNOSTIC_STRUCTURED_DATA_MARKER = "---DIAGNOSTIC-STRUCTURED-DATA---"

private val diagnosticJson = Json {
    encodeDefaults = true
    prettyPrint = false
    ignoreUnknownKeys = true
}

/**
 * Compose a tool result body as `prose + marker + JSON-encoded entries`.
 * Tools MUST use this so the Phase 7 spiller can split the two halves reliably.
 */
fun renderDiagnosticBody(prose: String, entries: List<DiagnosticEntry>): String {
    if (entries.isEmpty()) return prose
    val json = diagnosticJson.encodeToString(entries)
    return "$prose\n$DIAGNOSTIC_STRUCTURED_DATA_MARKER\n$json"
}

/**
 * Canonicalise a [ProblemHighlightType] into the shared [DiagnosticEntry.severity]
 * vocabulary (`"ERROR" | "WARNING" | "WEAK_WARNING" | "INFO"`).
 *
 * ## Contract
 * - Every [ProblemHighlightType] value maps to one of the four strings above.
 *   A `DiagnosticModelsTest` assertion iterates `ProblemHighlightType.values()`
 *   and pins this invariant — new enum values landed by upstream IntelliJ will
 *   fall through to the `else` branch and surface as `"INFO"`, which is a
 *   conservative default that keeps Phase 7 consumers stable.
 * - Phase 7 consumers (filters, sorts, UI grouping) depend on this canonical
 *   vocabulary. If you need a finer distinction, add a new case here; do NOT
 *   leak raw `ProblemHighlightType.name` values (e.g. `GENERIC_ERROR_OR_WARNING`,
 *   `LIKE_UNUSED_SYMBOL`, `INFORMATION`) into `DiagnosticEntry.severity`.
 *
 * ## Grouping
 *
 * This mapping matches the prior behaviour of
 * `RunInspectionsTool.mapHighlightType` (T2) verbatim: both tools previously
 * used the three-bucket scheme ERROR / WARNING / INFO, with WEAK_WARNING,
 * INFORMATION, LIKE_*, etc. all collapsed into INFO. T3 (`ListQuickFixesTool`)
 * previously emitted the raw enum name and has now been migrated onto this
 * shared mapper so both tools emit byte-identical severity values for the
 * same [ProblemHighlightType] input.
 *
 * Note: the [DiagnosticEntry.severity] kdoc advertises `"WEAK_WARNING"` as
 * part of the vocabulary, but T2 never emitted it and T3 does not emit it
 * either after this migration. If a future tool (T4 `ProblemViewTool`,
 * T5 `SemanticDiagnosticsTool`) needs the WEAK_WARNING distinction, add a
 * case here rather than forking a local mapper — that keeps cross-tool
 * consistency intact.
 */
fun normalizeSeverity(type: ProblemHighlightType): String {
    return when (type) {
        ProblemHighlightType.ERROR,
        ProblemHighlightType.GENERIC_ERROR -> "ERROR"
        ProblemHighlightType.WARNING,
        ProblemHighlightType.GENERIC_ERROR_OR_WARNING -> "WARNING"
        else -> "INFO"
    }
}

/**
 * Parse a body produced by [renderDiagnosticBody]. Returns (prose, entries) — entries
 * empty if marker is absent. Useful for tests and for Phase 7 spiller.
 */
fun parseDiagnosticBody(body: String): Pair<String, List<DiagnosticEntry>> {
    val idx = body.indexOf("\n$DIAGNOSTIC_STRUCTURED_DATA_MARKER\n")
    if (idx < 0) return body to emptyList()
    val prose = body.substring(0, idx)
    val jsonPart = body.substring(idx + "\n$DIAGNOSTIC_STRUCTURED_DATA_MARKER\n".length)
    val entries = runCatching {
        diagnosticJson.decodeFromString<List<DiagnosticEntry>>(jsonPart)
    }.getOrDefault(emptyList())
    return prose to entries
}
