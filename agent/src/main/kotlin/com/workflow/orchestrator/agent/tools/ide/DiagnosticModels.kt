package com.workflow.orchestrator.agent.tools.ide

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
 * - [severity] is one of "ERROR", "WARNING", "WEAK_WARNING", "INFO".
 * - [toolId] is the inspection short name (e.g. "UnusedDeclaration") or the
 *   producing subsystem (e.g. "wolf", "daemon", "provider").
 * - [column] is 1-based; -1 means column is unknown.
 * - [category] is the IntelliJ group name when available ("Probable bugs",
 *   "Type hierarchy", …); null otherwise.
 */
@Serializable
data class DiagnosticEntry(
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
 */
const val DIAGNOSTIC_STRUCTURED_DATA_MARKER = "---DIAGNOSTIC-STRUCTURED-DATA---"

private val diagnosticJson = Json { encodeDefaults = true; prettyPrint = false }

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
