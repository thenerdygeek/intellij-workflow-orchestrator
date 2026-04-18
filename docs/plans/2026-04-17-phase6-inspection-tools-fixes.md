# Phase 6 — Inspection Tools Fixes

**Fixes:** Aggregate-count output, DumbService gaps, refactor safety, quick-fix redundancy, deprecated inspection API.
**Audit source:** `docs/research/2026-04-17-inspection-tools-audit.md`.
**Preconditions:** None (can run in parallel with Phases 1, 2, 3, 5).
**Estimated:** 1–2 days. Small-medium complexity.

---

## Context

Six inspection/diagnostics tools share the same anti-patterns as the runtime/test tools: they return prose-formatted aggregate counts instead of structured per-item data, don't guard against IntelliJ's dumb mode (indexing) consistently, and hard-truncate output instead of spilling.

---

## Scope

**In:**
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/RunInspectionsTool.kt`
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/ListQuickFixesTool.kt`
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/ProblemViewTool.kt`
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/SemanticDiagnosticsTool.kt`
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/FormatCodeTool.kt` (validate existing DumbService check)
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/OptimizeImportsTool.kt` (ditto)
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/RefactorRenameTool.kt`

**Out:**
- Background-task progress indicators for long inspections → separate spike.
- Inspection profile editing → not needed.

---

## Task list

### Task 5.1 — Per-item structured data (fixes F1 from audit)

**All four diagnostic tools.**

Currently: return prose with hardcoded result caps (30 or 20 items). LLM can't query specific problems, can't sort by severity, loses data past the cap.

Change:
1. Introduce a shared `DiagnosticEntry` data class in `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/DiagnosticModels.kt`:
   ```kotlin
   data class DiagnosticEntry(
       val file: String,
       val line: Int,
       val column: Int,
       val severity: String,     // ERROR, WARNING, WEAK_WARNING, INFO
       val toolId: String,       // inspection short name
       val description: String,
       val hasQuickFix: Boolean,
       val category: String?     // e.g. "Type hierarchy", "Probable bugs"
   )
   ```
2. Each tool returns a prose summary + the structured list in `.summary` is lossless re: count. The prose is for LLM skim; the structured list is for when the LLM needs per-item drill-down.
3. **For Phase 7 wiring:** ensure the prose + structured list can be serialized separately so the spiller can store the full list while the preview stays terse.

### Task 5.2 — DumbService guards (fixes F3)

- `RunInspectionsTool.execute` — add `if (DumbService.isDumb(project)) return ToolResult("Indexing in progress — inspection deferred. Try again after indexing completes.", ...)`.
- `ListQuickFixesTool.execute` — same.
- `ProblemViewTool.execute` — add; WolfTheProblemSolver's cache may be stale during indexing.
- `SemanticDiagnosticsTool.execute` — verify existing check; the audit noted it "checks but delegates without provider guarantees" — need to trace and confirm.

### Task 5.3 — Refactor rename safety (fixes F4)

**File:** `RefactorRenameTool.kt`.

Currently: no conflict detection. LLM gets "success" for a rename that broke every call site in 40 downstream modules.

Add:
1. Use `RenameProcessor.setPreviewUsages(true)` + `RefactoringFactory.createRename(...).findUsages()`.
2. Before invoking the refactor, collect `UsageInfo[]` and group by module.
3. If usages span > 1 module or include library code, surface a warning in the tool result:
   ```
   Rename of 'foo.Bar#method' will affect:
     - 12 usages in module :core (3 test, 9 production)
     - 4 usages in module :bamboo (all production)
     - 1 usage in external library [BLOCKED — cannot rename library code]

   Proceed? Pass confirm_cross_module=true to apply.
   ```
4. Require the LLM to set `confirm_cross_module=true` (new parameter) for cross-module renames.
5. Absolutely block external library renames (usage's file is in a jar) — return error.

### Task 5.4 — Quick-fix tool optimisation (fixes F6)

**File:** `ListQuickFixesTool.kt`.

Currently: re-runs the entire inspection suite to extract quick-fixes.

Change:
1. Read from `DaemonCodeAnalyzerImpl.getFileHighlightingRanges()` — already-computed HighlightInfo.
2. For each HighlightInfo, walk `fixes` via `HighlightInfo.findRegisteredQuickFix(...)` or `IntentionManager.getInstance().getAvailableActions(editor, psiFile)`.
3. Cache at tool-call-level — within a single `list_quickfixes` invocation, don't re-walk the file per-fix.

### Task 5.5 — Switch to non-deprecated inspection API (fixes F5)

**File:** `RunInspectionsTool.kt`.

Currently: manual `buildVisitor()` walk via `LocalInspectionTool.buildVisitor(holder, isOnTheFly=false)`.

Change to: `LocalInspectionToolWrapper(tool).processFile(file, session)` — the public wrapper handles visitor construction, session setup, and platform upgrades.

### Task 5.6 — `isError` semantics consistency

All four diagnostic tools: verify + document that `isError = true` means "tool execution failed" (e.g. DumbService blocked, file not found), NOT "problems were found in the inspected scope". The existing CLAUDE.md claim for `SemanticDiagnosticsTool` should apply to all four.

Add unit test per tool asserting: when the tool successfully finds 10 errors in a file, `ToolResult.isError == false`.

### Task 5.7 — Tests

Extend `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/ide/` with:
- `DiagnosticEntry` serialization round-trip tests.
- DumbService guard tests (mock `DumbService.isDumb` = true, assert ToolResult matches "indexing deferred" string).
- RefactorRenameTool conflict-detection tests — fixture with cross-module usages, assert the warning fires.
- ListQuickFixesTool performance regression test — construct a fake file with 100 highlights, assert only one inspection pass runs.

---

## Validation

```bash
./gradlew :agent:test --tests "*Inspection*" --tests "*Diagnostic*" --tests "*QuickFix*" --tests "*Rename*" --tests "*ProblemView*"
./gradlew verifyPlugin
```

Manual:
1. DumbService guard — `File | Invalidate Caches | Invalidate and Restart`. Immediately ask agent to `run_inspections`. Before fix: crash or stale cache. After fix: "Indexing in progress".
2. Refactor safety — ask agent to rename a public API method used across modules. Before fix: succeeds, breaks build. After fix: warns, requires confirm flag.

## Exit criteria

- All four diagnostic tools return both prose + structured list.
- All four check `DumbService.isDumb(project)` and short-circuit appropriately.
- RefactorRenameTool blocks library renames and warns on cross-module renames.
- `ListQuickFixesTool` does not re-run full inspection per-fix.
- `RunInspectionsTool` uses `LocalInspectionToolWrapper.processFile` (no deprecated API).

## Follow-ups

- Output spiller wiring → Phase 7.
- Background-task progress for `run_inspections` over a large scope → separate spike.
- Inspection-result caching between tool calls — deferred; current stateless design is simpler.
