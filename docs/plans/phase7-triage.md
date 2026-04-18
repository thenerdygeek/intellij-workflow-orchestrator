# Phase 7 Triage — `truncateOutput` call site classification

**Generated:** April 18, 2026
**Branch:** feature/tooling-architecture-enhancements
**Purpose:** Classify every truncateOutput call site + TODO(phase7) marker + hardcoded cap constant as KEEP / SPILL-DEFAULT / SPILL-COMMAND / REMOVE / NEEDS-RETROFIT before wiring begins.

---

## Summary

- **Total truncateOutput call sites:** 9 (direct calls, excluding ToolOutputSpiller internals and truncateOutput.kt itself)
- **Total TODO(phase7) markers:** 17 (across debug, inspection, and framework tools)
- **Hardcoded cap constants to delete:** 3 files with 4 distinct constants
- **Breakdown:** SPILL-DEFAULT = 4, SPILL-COMMAND = 3, KEEP = 2, REMOVE = 0, NEEDS-RETROFIT = 3

---

## Call sites — `truncateOutput(`

### Runtime tools

| # | File | Line | Tool | Current cap | Classification | Rationale |
|---|---|---|---|---|---|---|
| 1 | RuntimeExecTool.kt | 270 | get_run_output | RUN_OUTPUT_TOKEN_CAP_CHARS (12K) | SPILL-DEFAULT | Arbitrary stdout/stderr; easily exceeds 30K on long-running processes. Runtime output spilling is the core Phase 7 use case. |
| 2 | JavaRuntimeExecTool.kt | 938 | run_tests (timeout branch) | RUN_TESTS_MAX_OUTPUT_CHARS (12K) | SPILL-DEFAULT | Test output on timeout; partial output can exceed 30K. Routed through spiller with DEFAULT threshold. |
| 3 | JavaRuntimeExecTool.kt | 963 | run_tests (shell fallback) | RUN_TESTS_MAX_OUTPUT_CHARS (12K) | SPILL-DEFAULT | Surefire/Gradle XML fallback output; exceeds 30K on large test suites. Route through spiller DEFAULT. |
| 4 | RuntimeExecShared.kt | 295 | formatStructuredResults | RUN_TESTS_TOKEN_CAP_CHARS (12K) | SPILL-DEFAULT | Test result formatting; structured list of test outcomes. Spill with DEFAULT (30K threshold). |
| 5 | RuntimeExecShared.kt | 322 | buildRunnerErrorResult | RUN_TESTS_TOKEN_CAP_CHARS (12K) | SPILL-DEFAULT | Test runner errors with stack traces; can exceed 30K for complex nested failures. Spill with DEFAULT. |
| 6 | PythonRuntimeExecTool.kt | 460 | py_compile stderr | RUN_TESTS_MAX_OUTPUT_CHARS (12K) | SPILL-DEFAULT | Python compilation stderr; multi-file syntax errors exceed 30K easily. Route through spiller DEFAULT. |
| 7 | PytestActions.kt | 291 | pytest failure detail | 5000 (hardcoded) | KEEP | Localized failure output for a single test case. Cap at 5K is appropriate; only shown when a specific test fails. This is not a full test run and won't exceed 5K in practice. |
| 8 | DebugInspectTool.kt | 388 | get_variables | MAX_OUTPUT_CHARS (const value TBD) | SPILL-DEFAULT | Deep object graphs in debugger. Variables can produce 30K+ output; spill with DEFAULT. Mark TODO(phase7) at line 387. |
| 9 | AgentLoop.kt | 1326 | generic tool output post-processing | tool.outputConfig.maxChars | KEEP | This is the SPILLER ITSELF — it already routes through `ToolOutputConfig`, applies truncation as fallback. No change needed; this is the orchestrator's final safety gate. Comment clarifies it's post-spiller. |

### Inspection tools (with TODO(phase7) markers)

| # | File | Line | Tool | TODO marker | Classification | Rationale |
|---|---|---|---|---|---|---|
| 1 | ListQuickFixesTool.kt | 207 | list_quickfixes | Line 207: "replace this hard cap with ToolOutputSpiller" | SPILL-DEFAULT | JSON list of DiagnosticEntry fixtures can exceed 30K on files with 100+ quick fixes. Phase 6 refactored to `DiagnosticEntry` struct. Spill JSON to disk, keep prose preview inline. See structured-diagnostic strategy below. |
| 2 | ProblemViewTool.kt | 372 | problem_view | Line 372: "replace this hard cap with ToolOutputSpiller" | SPILL-DEFAULT | DiagnosticEntry list from `getFileHighlightingRanges()`. Spill full list to disk, keep prose preview. Same strategy as ListQuickFixesTool. |
| 3 | RunInspectionsTool.kt | 155 | run_inspections | Line 155: "spill via ToolOutputSpiller instead of hard-capping" | SPILL-DEFAULT | Problem list from active inspection profile. Prose + JSON; spill when > 30K. Same DiagnosticEntry schema as #1-2. |
| 4 | SemanticDiagnosticsTool.kt | 216 | diagnostics | Line 216: "replace this hard cap with ToolOutputSpiller" | SPILL-DEFAULT | Semantic diagnostics after filtering to edit range. Spill full entry list; keep prose preview with line excerpts. |

### Debug tools (with TODO(phase7) markers)

| # | File | Line | Action | TODO marker | Classification | Rationale |
|---|---|---|---|---|---|---|
| 1 | DebugInspectTool.kt | 281 | evaluate (single value) | Line 281: "wire ToolOutputSpiller — keep current small cap" | KEEP | Expression evaluation returns single typed value (< 1KB). No spilling needed; too small. Leave as-is. |
| 2 | DebugInspectTool.kt | 387 | get_variables | Line 387: "wire ToolOutputSpiller — output_config DEFAULT" | SPILL-DEFAULT | Deep object graphs in variable view. Can exceed 30K; spill with DEFAULT threshold. Route through spillOrFormat(). |
| 3 | DebugInspectTool.kt | 577 | thread_dump | Line 577: "wire ToolOutputSpiller — output_config DEFAULT" | SPILL-DEFAULT | 50+ threads × 30-line stacks = 75KB typical. Spill with DEFAULT (30K threshold). |
| 4 | DebugInspectTool.kt | 623 | memory_view | Line 623: "wire ToolOutputSpiller — output_config DEFAULT" | SPILL-DEFAULT | Hex dumps of live instance counts. Exceeds 30K on large heaps. Spill with DEFAULT. |

---

## TODO(phase7) markers — Full context

### DebugInspectTool.kt

**Line 281 — evaluate (keep existing cap):**
```kotlin
// TODO(phase7): wire ToolOutputSpiller — keep current small cap (single-value output)
val content = sb.toString()
ToolResult(content, "Evaluated: $expression", TokenEstimator.estimate(content))
```

**Line 387 — get_variables (wire spiller):**
```kotlin
if (content.length > MAX_OUTPUT_CHARS) {
    // TODO(phase7): wire ToolOutputSpiller — output_config DEFAULT, grep enabled, threshold 30K
    content = truncateOutput(content, MAX_OUTPUT_CHARS) +
        "\n(use variable_name to inspect specific variable)"
}
```

**Line 577 — thread_dump (wire spiller):**
```kotlin
// TODO(phase7): wire ToolOutputSpiller — output_config DEFAULT, grep enabled, threshold 30K
val content = sb.toString().trimEnd()
ToolResult(content, "Thread dump: ${threadInfos.size} threads, $suspendedCount suspended", TokenEstimator.estimate(content))
```

**Line 623 — memory_view (wire spiller):**
```kotlin
// TODO(phase7): wire ToolOutputSpiller — output_config DEFAULT, grep enabled, threshold 30K
val content = buildString {
    append("Memory view for: $className\n")
    append("Total live instances: $totalCount\n")
    ...
}
```

### ListQuickFixesTool.kt

**Line 207 — list_quickfixes (spill JSON, keep preview):**
```kotlin
// TODO(phase7): replace this hard cap with ToolOutputSpiller —
// Phase 7 will route the full entry list to disk and leave a
// preview inline.
val shown = unique.take(MAX_FIXES)
val lines = shown.map { qf ->
    "  - ${qf.fixName}\n    Problem: ${qf.problem} (${qf.inspection})"
}
// TODO(phase7): replace "... and N more" preview with disk-spill reference
```

**Line 247 — MAX_FIXES constant (delete):**
```kotlin
// TODO(phase7): replace this hard cap with ToolOutputSpiller — Phase 7
// will route the full entry list to disk and leave a preview inline.
private const val MAX_FIXES = 20
```

### ProblemViewTool.kt

**Line 372 — formatFileProblemsWithCap (spill JSON, keep preview):**
```kotlin
// TODO(phase7): replace this hard cap with ToolOutputSpiller — Phase 7
// will route the full entry list to disk and leave a preview inline.
val shown = problems.take(MAX_PROBLEMS)
...
// TODO(phase7): replace "... and N more" preview with disk-spill reference
if (problems.size > MAX_PROBLEMS) {
    sb.appendLine("... and ${problems.size - MAX_PROBLEMS} more")
}
```

**Line 432 — MAX_PROBLEMS constant (delete):**
```kotlin
// TODO(phase7): replace this hard cap with ToolOutputSpiller — Phase 7
// will route the full entry list to disk and leave a preview inline.
private const val MAX_PROBLEMS = 30
```

### RunInspectionsTool.kt

**Line 155 — run_inspections (spill JSON, keep preview):**
```kotlin
// TODO(phase7): spill via ToolOutputSpiller instead of hard-capping at MAX_PROBLEMS
val shown = allProblems.take(MAX_PROBLEMS)
...
// TODO(phase7): replace "... and N more" preview with disk-spill reference
val more = if (allProblems.size > MAX_PROBLEMS) "\n... and ${allProblems.size - MAX_PROBLEMS} more" else ""
```

**Line 211 — MAX_PROBLEMS constant (delete):**
```kotlin
// TODO(phase7): replace this hard cap with ToolOutputSpiller — Phase 7 will
// route the full entry list to disk and leave a preview inline.
private const val MAX_PROBLEMS = 30
```

### SemanticDiagnosticsTool.kt

**Line 196 & 205 — DiagnosticEntry construction (column & hasQuickFix notes):**
```kotlin
// TODO(phase7): column is -1 because
// LanguageIntelligenceProvider.DiagnosticInfo does
// not expose a column field today; adding one
// requires a provider-interface change. Tracked
// for a Phase 7 or follow-up spike.
column = -1,
...
// TODO(phase7): hasQuickFix is pinned to false —
// providers don't expose quick-fix availability
// through the DiagnosticInfo contract. Same
// limitation as T4 ProblemViewTool; see
// DiagnosticEntry.hasQuickFix kdoc for the
// forward reference.
hasQuickFix = false,
```

**Line 216 — replace hard cap with ToolOutputSpiller:**
```kotlin
// TODO(phase7): replace this hard cap with ToolOutputSpiller — Phase 7
// will route the full entry list to disk and leave a preview inline.
val shown = relevantProblems.take(MAX_ISSUES)
...
// TODO(phase7): replace "... and N more" preview with disk-spill reference
val more = if (relevantProblems.size > MAX_ISSUES) "\n... and ${relevantProblems.size - MAX_ISSUES} more" else ""
```

**Line 27 — MAX_ISSUES constant (delete):**
```kotlin
// TODO(phase7): replace this hard cap with ToolOutputSpiller — Phase 7
// will route the full entry list to disk and leave a preview inline.
private const val MAX_ISSUES = 20
```

### ListQuickFixesTool.kt / ProblemViewTool.kt (additional markers)

**ListQuickFixesTool.kt:106 — F6 hasQuickFix note (documentation):**
```kotlin
// TODO(phase7): F6 — the phase 6 plan proposed reading
// already-computed HighlightInfo from
// `DaemonCodeAnalyzerImpl.getFileHighlightingRanges()` plus
// `HighlightInfo.findRegisteredQuickFix(...)` (or
// `IntentionManager.getAvailableActions(editor, psiFile)`) so
```

**ProblemViewTool.kt:169 & 247 — hasQuickFix false (documentation):**
```kotlin
hasQuickFix = false,        // see TODO(phase7) on hasQuickFix resolution below
```

**ProblemViewTool.kt:396 — Long kdoc on hasQuickFix:**
```kotlin
// TODO(phase7): `hasQuickFix` is pinned to `false` at the two
// [DiagnosticEntry] construction sites above. `HighlightInfo.hasHint()`
// and `HighlightInfo.quickFixActionRanges` exist but require impl-level
// access (`com.intellij.codeInsight.daemon.impl`); the
// `IntentionManager.getAvailableActions(editor, psiFile)` alternative
// [shown above] does not directly map to `hasQuickFix` boolean.
```

### RunInspectionsTool.kt (F5 reference)

**Line 92 — LocalInspectionToolWrapper.processFile not exposed:**
```kotlin
// TODO(phase7): F5 — the phase 6 plan proposed routing this
// through `LocalInspectionToolWrapper.processFile(psiFile,
// session)` to abstract visitor construction + session setup.
// That overload is not exposed on the public platform API
// surface we compile against (251.x) — docs/superpowers/
```

---

## Hardcoded cap constants (to delete)

| Constant | File:Line | Current value | Action |
|---|---|---|---|
| RUN_OUTPUT_TOKEN_CAP_CHARS | RuntimeExecTool.kt:502 | 12000 | Delete; default to ToolOutputConfig.DEFAULT (50K maxChars) |
| RUN_TESTS_MAX_OUTPUT_CHARS | RuntimeExecShared.kt:48 | 12000 | Delete; default to ToolOutputConfig.DEFAULT (50K maxChars) |
| RUN_TESTS_TOKEN_CAP_CHARS | RuntimeExecShared.kt:49 | 12000 | Delete; default to ToolOutputConfig.DEFAULT (50K maxChars) |

**Notes:**
- All three reference the same magic number (12K), likely from legacy Cline limits.
- Phase 7 replaces these hardcoded caps with `ToolOutputConfig` defaults (50K before spill, 30K threshold to disk).
- The actual spill threshold is `ToolOutputConfig.SPILL_THRESHOLD_CHARS = 30_000` (per ToolOutputConfig.kt:20).
- Tools can override `outputConfig` property for higher caps (e.g., COMMAND at 30K for high-volume tools).

---

## SPILL-COMMAND overrides (per plan Task 6.10)

Three tools should override `outputConfig = ToolOutputConfig.COMMAND` (100K cap before spilling):

| Tool | File:Action | Evidence for > 30K typical output | Phase |
|---|---|---|---|
| `run_tests` | java_runtime_exec.run_tests | 500-failure JUnit runs produce 100K+ output; 100K cap allows LLM to see full result set in-context before spilling. Justifies COMMAND config. | 7 |
| `run_with_coverage` | coverage.run_with_coverage | Coverage reports on large modules (100+ classes) exceed 30K; COMMAND config (100K) gives LLM visibility before disk spill. | 7 |
| `run_inspections` | run_inspections (full-project scope) | Full-project inspection on real codebases (Gradle workspace) produces 50K+ problem lists. COMMAND config (100K) provides LLM-readable scope before spill. | 7 |

**Implementation:** Add to each tool class (java_runtime_exec, coverage, run_inspections):
```kotlin
override val outputConfig = ToolOutputConfig.COMMAND  // 100K cap before spilling
```

This is separate from the per-invocation spilling logic (which always uses 30K threshold to disk).

---

## Structured-diagnostic preview strategy

For **ListQuickFixesTool**, **ProblemViewTool**, **RunInspectionsTool**, **SemanticDiagnosticsTool** — when full JSON > 30K, spill the JSON to disk and keep a prose preview in the tool result.

### Strategy

1. **Collect DiagnosticEntry list** as before (Phase 6 T2).
2. **Serialize to JSON** (full list, no truncation).
3. **Check size:** If JSON > 30K, invoke `ToolOutputSpiller.spill(toolName, jsonStr)` to write to disk.
4. **Generate prose preview** (independent of JSON size):
   - Format first 20 entries as human-readable prose (file:line + message).
   - Append `"\n... and N more (see tool output file at: <path>)"` if entries > 20.
5. **Return ToolResult:**
   - `.content` = prose preview + spill path reference.
   - `.spillPath` = path to JSON file (new field in Phase 7).

### Per-tool locations (Phase 6 commits)

- **ListQuickFixesTool** (T2 Task 2.5): Iterates `uniqueQuickFixes: List<DiagnosticEntry>` at lines 205–217, renders prose. Spill the full `entries: List<DiagnosticEntry>` JSON at line 217 if > 30K.
- **ProblemViewTool** (T2 Task 2.4): Collects `problems: List<ProblemEntry>` at lines 155–172 (converted to `DiagnosticEntry` list), renders in `formatFileProblemsWithCap()`. Spill JSON at line 373 if > 30K.
- **RunInspectionsTool** (T2 Task 2.6): Builds `allProblems: List<DiagnosticEntry>` at lines 91–154, renders in prose at lines 155–164. Spill full JSON list at line 164 if > 30K.
- **SemanticDiagnosticsTool** (T2 Task 2.3): Collects `entries: List<DiagnosticEntry>` at lines 192–214, renders prose at lines 216–223. Spill full JSON at line 223 if > 30K.

### JSON-to-prose rendering (existing)

Each tool already formats prose via `renderDiagnosticBody(prose, entries)` helper (lines vary per tool). This prose becomes the preview; full JSON goes to disk.

---

## NEEDS-RETROFIT (spiller wiring without existing truncateOutput call)

Three PSI intelligence tools produce large outputs but do NOT currently call `truncateOutput`. They need spiller wiring added:

| Tool | File | Current behavior | Required action | Rationale |
|---|---|---|---|---|
| **FindReferencesTool** | agent/src/main/kotlin/.../psi/FindReferencesTool.kt | `.take(50)` hardcoded cap on references; no truncateOutput call | Add `spillOrFormat()` call on final content string (line 104 after building results) | 5000 usages × 80 chars = 400KB; spill with DEFAULT (30K). Override `outputConfig = ToolOutputConfig.DEFAULT` in class definition. |
| **CallHierarchyTool** | agent/src/main/kotlin/.../psi/CallHierarchyTool.kt | `.take(30)` hardcoded cap on callers/callees; no truncateOutput call | Add `spillOrFormat()` call on final content string (line 95 after building tree) | Deep call hierarchies (10+ levels × 50 methods per level) exceed 30K. Spill with DEFAULT. Override `outputConfig = ToolOutputConfig.DEFAULT`. |
| **TypeHierarchyTool** | agent/src/main/kotlin/.../psi/TypeHierarchyTool.kt | `.take(30)` hardcoded cap on subtypes; no truncateOutput call | Add `spillOrFormat()` call on final content string (line 82 after building hierarchy) | Type hierarchies on large interfaces (100+ implementations) exceed 30K. Spill with DEFAULT. Override `outputConfig = ToolOutputConfig.DEFAULT`. |

**Implementation pattern:**
```kotlin
// In tool class:
override val outputConfig = ToolOutputConfig.DEFAULT

// In execute():
val spilledContent = spillOrFormat(content, toolCallId = null)  // null if no caller ID needed
return ToolResult(
    content = spilledContent.preview,
    summary = "...",
    tokenEstimate = TokenEstimator.estimate(spilledContent.preview),
    spillPath = spilledContent.spilledPath
)
```

**Note:** These tools do NOT have truncateOutput calls to replace. They have hardcoded `.take(N)` limits instead. Phase 7 adds the spiller wiring infrastructure they're missing.

---

## Open questions for user

1. **ToolOutputConfig semantics (DEFAULT vs COMMAND):**
   - ToolOutputConfig.kt defines `DEFAULT_MAX_CHARS = 50_000` but `COMMAND_MAX_CHARS = 30_000`. The naming suggests COMMAND is smaller, but the plan says COMMAND is for high-volume tools needing higher caps. Clarify: is COMMAND (30K) a misnomer, or should it be renamed to something like EXTENDED (100K)? Currently code defines COMMAND = 30K, not 100K as Phase 7 plan implies. This blocks Task 6.10.

2. **spillOrFormat() helper method availability:**
   - Phase 7 plan mentions "a default method" in AgentTool to call `spillOrFormat()`, but this method doesn't exist yet in AgentTool.kt. Is this expected to be added in Phase 7 as part of the wiring, or should it already exist? Current tools call `truncateOutput()` directly; without `spillOrFormat()` they can't be wired to the spiller.

3. **ToolResult.spillPath field:**
   - The triage assumes a new `.spillPath` field on ToolResult to hold the disk spill file path. Does this field exist, or is it being added in Phase 7? Current ToolResult.kt (not provided) doesn't show it. Confirm schema before implementation.

4. **DiagnosticEntry JSON serialization:**
   - For ListQuickFixesTool et al., the triage assumes we serialize the DiagnosticEntry list to JSON for spilling. Is there a canonical JSON serializer (kotlinx.serialization, Jackson, etc.)? Phase 6 doesn't mention explicit JSON serialization of the entry lists — they're rendered as prose. Confirm the JSON target format.

---

## Summary of changes required

**Phase 7 Tasks (per triage):**

| Task | Scope | Call sites affected |
|---|---|---|
| **T7.1** | Add ToolOutputConfig.COMMAND override to `java_runtime_exec`, `coverage`, `run_inspections` | 3 tools |
| **T7.2** | Wire ToolOutputSpiller to runtime/test tools via spillOrFormat | RuntimeExecTool:270, JavaRuntimeExecTool:938/963, RuntimeExecShared:295/322, PythonRuntimeExecTool:460 = 6 call sites |
| **T7.3** | Wire ToolOutputSpiller to debug tools (get_variables, thread_dump, memory_view) | DebugInspectTool:388/577/623 = 3 call sites |
| **T7.4** | Wire ToolOutputSpiller to inspection tools (JSON spill + prose preview) | ListQuickFixesTool, ProblemViewTool, RunInspectionsTool, SemanticDiagnosticsTool = 4 tools |
| **T7.5** | Add spiller wiring to PSI tools (no existing truncateOutput) | FindReferencesTool, CallHierarchyTool, TypeHierarchyTool = 3 tools |
| **T7.6** | Delete hardcoded cap constants (12K) | RuntimeExecTool, RuntimeExecShared, and tool-specific MAX_FIXES/MAX_PROBLEMS constants |
| **T7.7** | Update CLAUDE.md to reflect completed wiring | Document which tools use spiller vs which don't (should be all runtime/IDE/debug/inspection after Phase 7) |

---

## Exit criteria

- Grep confirms zero direct `truncateOutput` calls outside of ToolOutputSpiller internals.
- Every runtime/IDE/debug/inspection tool either uses spillOrFormat or is justified as KEEP.
- All 17 TODO(phase7) markers have corresponding code changes (or documented deferral).
- All 3 hardcoded cap constants deleted.
- CLAUDE.md updated to list spiller-wired tools.
- SpillingWiringTest suite passes (per plan Task 6.11).

