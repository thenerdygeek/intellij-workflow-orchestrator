# Phase 7 — Wire ToolOutputSpiller Everywhere (Worktree Original Purpose)

**Fixes:** Every runtime/IDE/debug/inspection tool hard-truncates at 12K–5K chars. Large outputs lose data silently. `ToolOutputSpiller` (30K threshold → disk + preview) is wired ONLY into `RunCommandTool`.
**Audit source:** `docs/research/2026-04-17-runtime-test-tool-audit.md` §6, plus all three category audits.
**Preconditions:** ALL of Phases 1, 2, 3, 4, 5, 6 must be landed. This phase is last because every prior phase refactors the code paths the spiller wires into.
**Estimated:** 1 day. Small complexity, high impact — this is the whole point of the worktree.

---

## Context

The `feature/tooling-architecture-enhancements` worktree was created specifically to fix output spilling. The audit found it's still not wired. After the correctness phases land, this phase is mechanical: route every `truncateOutput(content, X)` call through `ToolOutputSpiller.spill(toolName, content)` with a 30K threshold.

`ToolOutputConfig` and `ToolOutputSpiller` already exist in `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/`. The infrastructure is ready; we just don't use it.

---

## Scope

**In:** Every tool that currently calls `truncateOutput(content, N)` directly, across:
- Runtime tools (`runtime_exec`, `java_runtime_exec`, `python_runtime_exec`, `coverage`)
- Debug tools (`debug_inspect.thread_dump`, `debug_inspect.get_variables`, `debug_inspect.memory_view`)
- Inspection tools (`run_inspections`, `list_quickfixes`, `problem_view`, `diagnostics`)
- Database tools (`db_query`, `db_explain`, `db_schema`)
- Framework tools (`spring`, `django`, `fastapi`, `flask`, `build`) — where outputs exceed 30K
- PSI intelligence tools — only those with large outputs (`call_hierarchy`, `type_hierarchy`, `find_references`)

**Out:**
- Tools with inherently small output (e.g. `current_time`, `think`) — no wiring needed.
- Integration tools (jira, bamboo, sonar, bitbucket) — have their own output-size policies; audit separately.

---

## Task list

### Task 6.1 — Audit all `truncateOutput` call sites

**Command:**
```bash
grep -rn "truncateOutput(" agent/src/main/kotlin/ | grep -v "^agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/truncateOutput.kt"
```

For each hit, decide:
- Keep (truly small output, < 5K typical) — leave alone.
- Route through `ToolOutputConfig` + `ToolOutputSpiller` — most cases.

Write the triage into `docs/plans/phase6-triage.md` before touching any code.

### Task 6.2 — `AgentTool.outputConfig` interface

**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/AgentTool.kt`.

Already exists per CLAUDE.md: `outputConfig: ToolOutputConfig` (default `ToolOutputConfig.DEFAULT` = 50K cap).

Action: verify each tool's `outputConfig` override. Audit found most tools DON'T override it — they bypass the config and `truncateOutput` directly.

### Task 6.3 — Standard spill helper in `AgentTool`

**File:** Same.

Add a default method:
```kotlin
suspend fun spillOrFormat(content: String, toolCallId: String?): ToolResultContent {
    val config = outputConfig
    val grep = config.applyGrep(content)  // filter if grep_pattern is set
    val spiller = AgentService.get(project).toolOutputSpiller
    val spilled = spiller.spill(name, grep)
    return ToolResultContent(
        preview = spilled.preview,
        spilledPath = spilled.spilledToFile
    )
}
```

`ToolResultContent` is a new small data class that `ToolResult` absorbs; preview goes into `.content`, spill path goes into a new `.spillPath` field for logging.

### Task 6.4 — Wire runtime tools

Replace in:
- `RuntimeExecTool.kt:270` (`get_run_output`) — `truncateOutput(content, RUN_OUTPUT_TOKEN_CAP_CHARS)` → spillOrFormat.
- `RuntimeExecTool.kt:491` (`get_test_results`) — `truncateOutput(content, TEST_RESULTS_TOKEN_CAP_CHARS)` → spillOrFormat.
- `RuntimeExecShared.kt:167` (`formatStructuredResults`) — same.
- `RuntimeExecShared.kt:194` (`buildRunnerErrorResult`) — same.
- `JavaRuntimeExecTool.kt:746, 760` (`executeWithShell`) — same (note: Phase 1 changes this path to XML parse + format, apply spiller to the final formatted content).
- `PytestActions.kt:201` (`executePytestRun` failure detail) — same.
- `PythonRuntimeExecTool.kt:213` (`executeCompileModule` py_compile stderr) — same.
- `CoverageTool` — `formatCoverageSummary` + `formatFileCoverageDetail` both produce potentially large output; route through spiller.

### Task 6.5 — Wire debug tools (depends on Phase 5)

Replace `truncateOutput` calls in `DebugInspectTool.kt` for:
- `thread_dump` action — 50 threads × 30-line stacks = 75KB typical. Spill.
- `get_variables` action — deep object graphs. Spill.
- `memory_view` action — hex dumps. Spill.
- `evaluate` action — keep current cap (values are usually small).

### Task 6.6 — Wire inspection tools (depends on Phase 6)

Phase 6 introduced `DiagnosticEntry` + structured list. Wire spiller to the serialized JSON representation when the full list exceeds 30K.

### Task 6.7 — Wire DB tool

**File:** `DbQueryTool.kt` (and `DbExplainTool`, `DbSchemaTool`, `DbStatsTool`).

`DbQueryTool` can return Markdown tables at 2MB for wide result sets. Apply:
1. Stream result rows into a `StringBuilder` with a running char count.
2. At 30K chars, switch to spilling — continue streaming to file, append preview to in-memory string.
3. Truncate the in-memory preview to first 20 lines + last 10.

### Task 6.8 — Wire PSI intelligence tools (selective)

Tools producing long lists:
- `FindReferencesTool` — 5000 usages × 80 chars = 400KB. Spill.
- `CallHierarchyTool` — deep trees. Spill.
- `TypeHierarchyTool` — same.

Other PSI tools (`FindDefinitionTool`, `GetMethodBodyTool`, etc.) produce small output; leave.

### Task 6.9 — Remove hardcoded 12K caps

Grep for `12_000`, `12000`, `RUN_TESTS_TOKEN_CAP_CHARS`, `RUN_OUTPUT_TOKEN_CAP_CHARS`, `TEST_RESULTS_TOKEN_CAP_CHARS`, `RUN_TESTS_MAX_OUTPUT_CHARS` — all should be removed (the new 30K threshold comes from `ToolOutputConfig.DEFAULT`).

### Task 6.10 — Extend `ToolOutputConfig` with `outputConfig = COMMAND` for high-output tools

Some tools legitimately output > 30K even after filtering (e.g. `run_tests` for 500-failure builds). For those, override `outputConfig = ToolOutputConfig.COMMAND` which has a 100K cap before spilling (gives the LLM more in-context visibility).

Candidates: `run_tests`, `run_with_coverage`, `run_inspections` (full-project scope).

### Task 6.11 — Unit tests

New file: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/SpillingWiringTest.kt`.

For each tool category, test: given a mock project with a tool that produces 100K of output, assert:
1. `ToolResult.content.length < 3000` (preview only).
2. `ToolResult.spillPath != null`.
3. The file at `spillPath` exists and contains the full 100K.
4. The preview contains the first 20 lines + last 10 lines of the content.

### Task 6.12 — Update CLAUDE.md

**File:** Both root `CLAUDE.md` and `agent/CLAUDE.md`.

Clarify the spilling contract:
- Previously: "Auto-spills large outputs (>30K chars) to {sessionDir}/tool-output/..."
- Now: List which tools use the spiller vs which ones don't (should be all runtime/IDE/debug/inspection tools after Phase 6).

### Task 6.13 — Update system prompt guidance

**File:** `SystemPrompt.kt` — RULES section.

Add/update:
> When a tool result includes `[Output saved to: <path>]`, only the head/tail preview is shown inline. To read the full output, call `read_file` with that path, or use `search_code` with a regex to find specific content.

Already exists partly per CLAUDE.md — verify it's present and covers the full spill file schema.

---

## Validation

```bash
grep -rn "truncateOutput(" agent/src/main/kotlin/ | grep -v "ToolOutputSpiller" | grep -v "truncateOutput.kt"
# Expected: 0 hits (or explicit allowlist for small-output tools)

./gradlew :agent:test --tests "*Spill*"
./gradlew :agent:test  # full suite to catch regressions
./gradlew verifyPlugin
```

Manual: in sandbox IDE, trigger `run_tests` on a module with 500 tests (or simulate via a long-running `run_command`). Verify:
1. Tool result preview is ~1KB.
2. Tool result includes `[Output saved to: ...]` line with a real path.
3. File at that path contains the full 100K+ output.
4. `read_file` on that path works.

## Exit criteria

- Grep confirms zero direct `truncateOutput` calls outside of `ToolOutputSpiller` internals and an explicit small-output allowlist.
- Every runtime/IDE/debug/inspection tool overrides `outputConfig` or uses the default correctly.
- SpillingWiringTest passes for each category.
- CLAUDE.md matches reality.

## Follow-ups

- Retroactive cleanup of old spill files (TTL?) → `~/.workflow-orchestrator/{proj}/tool-output/` should be cleaned per-session.
- Spiller metrics in session dashboard (how many spills, average size) — nice to have.
- Alternative formats for spilled output — JSON Lines for structured tools, gzip for large dumps. Defer until we have size pressure.

## Closing the worktree

After Phase 6 lands:
1. Update `project_tooling_architecture_worktree.md` memory entry → mark status DONE.
2. Verify no regression in all six phases via full test run.
3. Rebase on main (see root CLAUDE.md rebase process).
4. Merge PR.
5. Delete the worktree: `git worktree remove .worktrees/tooling-architecture`.
