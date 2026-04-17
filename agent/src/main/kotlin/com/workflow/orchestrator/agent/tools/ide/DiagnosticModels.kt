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
 * - [severity] is one of "ERROR", "WARNING", "INFO". (Reserve "WEAK_WARNING"
 *   for a future vocabulary extension — see the note in [normalizeSeverity].)
 * - [toolId] is the inspection short name (e.g. "UnusedDeclaration") or the
 *   producing subsystem (e.g. "wolf", "daemon", "provider").
 * - [column] is 1-based; -1 means column is unknown. Tools that can
 *   cheaply resolve a real column (T4 `ProblemViewTool`) emit one;
 *   tools whose upstream API only exposes line-level information
 *   (T2 `RunInspectionsTool`, T3 `ListQuickFixesTool`) emit -1.
 *   Phase 7 consumers grouping by `(file, line, column)` must treat
 *   mixed producers with care — do not assume all entries with the
 *   same `(file, line)` collapse.
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
    /**
     * Availability hint — `true` when the producing tool confirms a registered
     * quick fix exists for this diagnostic; `false` when none or unknown.
     * Not authoritative: T4 `ProblemViewTool` stubs to `false` because
     * `HighlightInfo.hasHint()` / quick-fix ranges are impl-level and
     * `IntentionManager.getAvailableActions` needs an Editor. Phase 7
     * consumers filtering on `hasQuickFix == true` receive a lower-bound
     * "definitely has fix" set; `false` means "absent OR unknown."
     */
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

/**
 * Canonical producer subsystem identifiers for [DiagnosticEntry.toolId].
 * Keeps cross-tool `toolId` vocabulary consistent so Phase 7 consumers
 * (chips, group-by, filters) can rely on a closed set. When a specific
 * inspection shortName is available (e.g. "UnusedDeclaration"), prefer
 * that — these constants are for entries produced by the subsystem as
 * a whole.
 */
object DiagnosticSubsystem {
    const val DAEMON = "daemon"        // HighlightInfo via DocumentMarkupModel
    const val WOLF = "wolf"            // WolfTheProblemSolver "flagged but no details" entries
    const val PROVIDER = "provider"    // LanguageIntelligenceProvider.getDiagnostics (T5)
}

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
 * vocabulary (`"ERROR" | "WARNING" | "INFO"`).
 *
 * ## Contract
 * - Every [ProblemHighlightType] value maps to one of the three strings above.
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
 * Note: `"WEAK_WARNING"` is RESERVED but not yet emitted. If a future tool
 * (T4 `ProblemViewTool`, T5 `SemanticDiagnosticsTool`) needs the distinction,
 * promote it to a real case here AND extend `DiagnosticEntry.severity` kdoc,
 * AND update any user-facing severity-filter enum lists on consumer tools
 * (e.g. T2's `severity` parameter schema). Do not fork a local mapper.
 */
fun normalizeSeverity(type: ProblemHighlightType): String {
    return when (type) {
        ProblemHighlightType.ERROR,
        ProblemHighlightType.GENERIC_ERROR -> "ERROR"
        ProblemHighlightType.WARNING,
        ProblemHighlightType.GENERIC_ERROR_OR_WARNING -> "WARNING"
        // Deliberately collapsed to INFO (matches T2's historical grouping).
        // If IntelliJ adds a genuinely distinct severity (e.g. CRITICAL), promote
        // it here rather than letting it silently downgrade — the exhaustive
        // `normalizeSeverity` test only pins vocabulary membership, not grouping.
        ProblemHighlightType.WEAK_WARNING,
        ProblemHighlightType.INFORMATION,
        ProblemHighlightType.LIKE_DEPRECATED,
        ProblemHighlightType.LIKE_MARKED_FOR_REMOVAL,
        ProblemHighlightType.LIKE_UNKNOWN_SYMBOL,
        ProblemHighlightType.LIKE_UNUSED_SYMBOL,
        ProblemHighlightType.POSSIBLE_PROBLEM -> "INFO"
        else -> "INFO" // forward-compat: new IntelliJ enum values land here until promoted.
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
