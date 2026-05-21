# Tool feedback fix bundle — Design (2026-05-21)

**Branch:** `bugfix`
**Scope:** 7 items derived from agent self-reported tool friction. Verified against current code via 4 parallel read-only Explore agents on 2026-05-21.

## Triage origin

The user pasted ~14 agent-self-reported tool issues. Read-only verification produced:

- **4 confirmed code bugs** (A1–A4) — fix required
- **2 presentation/UX fixes** (B1–B2) — small code changes
- **1 diagnostic addition** (E1) — converts a future ambiguous report into definitive evidence
- **3 misdiagnosed** (streaming-preview / search_code-missing-file / grep-case-sensitivity) — defer to a system-prompt pass, not in this bundle
- **1 cannot-locate** (Windows `nul` file) — no agent-source culprit found, deferred to Windows-side investigation

This spec covers the 7 items in the first three categories. The deferred items are tracked in the index notes at the end.

## Goals

1. Close 4 verified bugs that cause agent friction and wasted iterations.
2. Add 2 small presentation fixes (Sonar labeling, RunCommand submodule hint) that turn agent retries into one-shot successes.
3. Add 1 diagnostic so the remaining `run_maven_goal` mystery becomes triagable on next reproduction.
4. Land as one logical bundle on `bugfix`, one commit per item, single PR to main.

## Non-goals

- System-prompt rewrites (the 3 misdiagnosed items will be handled separately).
- Windows-specific bug hunting (the `nul` file). External evidence-gathering required first.
- Behavior changes to the streaming-edit preview itself.
- Auto-resolving `plan_key` from `project_context` inside BambooBuildsTool (adds inter-tool coupling — fix the error message first, revisit only if still problematic).

## Land order (smallest blast radius → largest)

| # | Item | Files touched | Risk |
|---|------|---------------|------|
| 1 | A4 — PSI dumb-mode guard removal | 3 PSI tool files (1 line each) | Low |
| 2 | A3 — Bamboo `plan_key` error msg | 1 file, 2 lines | Trivial |
| 3 | E1 — `run_maven_goal` param-log | 1 file, 2 lines | Trivial |
| 4 | B2 — RunCommand submodule hint | 1 file, ~10 lines | Low |
| 5 | B1 — SonarTool section labels | 1 file (+ `:core` read) | Low after verify |
| 6 | A1 — DbQuery `output_file` honored | 1–2 files, ~15 lines | Low |
| 7 | A2 — Coverage package filter | 1 file, ~50 lines + tests | Medium (auto-derive logic) |

Each commit references the item code (e.g., `fix(agent): A4 — let PSI tools wait for smart mode`).

## Item designs

### A4 — PSI tools: remove eager `isDumb` guard

**Files:**
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/psi/FindDefinitionTool.kt:95`
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/psi/FindReferencesTool.kt:111`
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/psi/FindImplementationsTool.kt:94`

**Root cause:** Each tool starts with `if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()`. The next block uses `ReadAction.nonBlocking { … }.inSmartMode(project).executeSynchronously()` — which would have awaited smart mode automatically. The eager guard makes the wait dead code.

**Fix:** Delete the eager guard line in all three tools.

**Pre-check:** Confirm these tools are dispatched on `Dispatchers.IO` (not EDT) in the agent loop, otherwise `executeSynchronously()` could block UI. Search the AgentLoop/ToolRegistry dispatch path; if EDT is in scope, wrap the call in `withContext(Dispatchers.IO)` instead.

**Test:** `agent/src/test/kotlin/.../FindDefinitionToolTest.kt` — mock `DumbService.isDumb` true → false; assert tool returns resolved symbol rather than the dumb-mode error.

### A3 — BambooBuildsTool `plan_key` error message

**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BambooBuildsTool.kt:931, 951`

**Root cause:** Generic `ToolValidation.missingParam("plan_key")` → `"'plan_key' parameter required"` with no discovery hint. The plan key is available in `ProjectContextTool` output (`bambooPlanKey`).

**Fix:** Replace both call sites with:

```kotlin
return ToolValidation.errorResult(
    "'plan_key' parameter required. Call project_context(action=get_project) " +
    "to find the value under 'Bamboo Plan Key', or configure it in Settings > " +
    "Workflow Orchestrator > Connections > Bamboo."
)
```

**Test:** `BambooBuildsToolTest` — call `recent_builds` without `plan_key`; assert the error message contains both `"project_context"` and `"Settings"`.

### E1 — `run_maven_goal` blank-check diagnostic

**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/RunMavenGoalAction.kt:67–72`

**Root cause:** Agent reported "goals is blank" with valid input; verification found the schema/impl match correctly and the blank-check is intentional. Without seeing what `params` actually contained, the cause is unknowable.

**Fix:** Add a `LOG.warn` before the error return:

```kotlin
val goals = params["goals"]?.jsonPrimitive?.content ?: ""
if (goals.isBlank()) {
    LOG.warn("run_maven_goal: goals blank. Received param keys: ${params.keys}")
    return buildPreflightError(
        CATEGORY_INVALID_ARGS,
        "goals is blank — Maven CLI silently produces BUILD SUCCESS for an empty goal list..."
    )
}
```

**Test:** `RunMavenGoalActionTest` — invoke with empty params; capture the IntelliJ logger via existing test infrastructure; assert log line contains `"Received param keys"`.

### B2 — RunCommandTool submodule hint

**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/RunCommandTool.kt`

**Root cause:** When the user is at the parent repo root and runs `git diff <submodule-relative-path>`, git fails with `"fatal: not a git repository"` because the submodule is a nested repo. The tool surfaces the raw stderr with no hint about cwd.

**Fix:** After process exit, classify the result. If `command.startsWith("git ")` and stderr or stdout contains `"fatal: not a git repository"`, append a hint to the formatted tool result:

```
Hint: this command ran from <workingDir>. If the target path is inside a git
submodule, set working_dir to the submodule's root (e.g., working_dir="sample-common-core").
```

The hint should be a separate line clearly marked (e.g., prefixed with `Hint:`) so the LLM can parse it.

**Test:** `RunCommandToolTest` — feed a fake process result with that stderr; assert tool output contains "submodule" and "working_dir".

### B1 — SonarTool section labeling

**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/SonarTool.kt:327–399`

**Hypothesis (to verify):** `branch_quality_report` aggregates `/qualitygates/project_status` (which evaluates new-code-period gates) AND `/measures/component` (which returns overall coverage). When new code is empty (e.g., on a stale branch), `new_coverage` is `0.0 ERROR` while `lineCoverage` is `100.0%` — both technically correct, but the output doesn't say which is which.

**Pre-step:** Read `:core/services/SonarService.kt` (or whatever class owns the Sonar HTTP calls — locate via `grep -r "qualitygates/project_status" core/`) to confirm the period parameters. If the hypothesis holds, fix as below. If reality differs, revisit before coding.

**Fix:** Rename the output section headings:
- `"Quality Gate"` → `"Quality Gate [NEW CODE]"`
- `"Coverage Summary"` → `"Coverage Summary [OVERALL]"`

Add a one-line legend after the heading block:

```
Note: Quality Gate evaluates the new-code period only. Coverage Summary
reflects overall project coverage. Discrepancies are expected when the
branch contains no new code.
```

**Test:** Snapshot test on the formatted output for a fixture that triggers the contradiction; assert both labels appear.

### A1 — DbQueryTool `output_file=true`

**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/database/DbQueryTool.kt:184–236`
**Possibly also:** the `ToolOutputSpiller` primitive (verify location during impl).

**Root cause:** Schema declares `output_file` (lines 133–139), but `execute()` never reads it. All results go through `spillOrFormat(raw, project)`, which only auto-spills above the 30 KB threshold. Sub-30 KB results truncate inline regardless of the flag.

**Fix:** Two parts.

1. Add a `forceSpill: Boolean = false` parameter to the `spillOrFormat`/`ToolOutputSpiller` primitive. When true, write to disk and return preview-with-path regardless of content size.
2. In `DbQueryTool.execute()`, extract `params["output_file"]?.jsonPrimitive?.booleanOrNull == true` and pass it as `forceSpill`.

**Why the primitive change vs. local fix:** the same flag is implicitly useful for other tools (`bamboo`, `sonar`, `jira` already use the spiller). Centralizing the bypass keeps the contract uniform.

**Test:** `DbQueryToolTest` — small result (< 30 KB) with `output_file=true`; assert response contains a file path, the file exists on disk, the file content equals the full result, and the inline preview is non-empty.

### A2 — CoverageTool package filter

**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/CoverageTool.kt:889–902`

**Root cause:** Iterates all `ProjectData.getClasses()` entries — includes CGLIB proxies, ByteBuddy synthetics, Mockito classes, every transitive dependency. No filter.

**Fix:** Three-layer filter resolution in precedence order.

1. **Explicit `package_filter` param** — comma-separated prefix list (e.g., `"com.example,com.other"`). When present, classes are kept iff any prefix matches via `className.startsWith(prefix + ".") || className == prefix`. Wins over all auto-derivation.

2. **Auto-derive from build files** — if no explicit param:
   - Maven: read root `pom.xml` for `<groupId>`.
   - Gradle: read `build.gradle` / `build.gradle.kts` / `settings.gradle(.kts)` for the `group = "..."` declaration.
   - Multi-module: use the parent groupId (broader is fine — still excludes Mockito).
   - Apply as a single-prefix filter.

3. **Source-tree fallback** — if neither build file yields a group, walk `src/main/java/` and `src/main/kotlin/`. Find the deepest single-child directory chain (heuristic for top-level package). Use that path as the prefix.

4. **No-filter degradation** — if all three layers fail (no pom, no gradle, no src tree), return unfiltered results with `LOG.warn("CoverageTool: no package filter derivable; returning all classes")` and a note in the tool result so the agent can spot it.

**Caveats documented in code comments:**
- Multi-module projects with disparate per-module groupIds may show partial misses for sibling modules. Acceptable for v1; revisit if reported.
- The filter is applied AFTER the JaCoCo iteration starts (no way to skip class instrumentation), so this is a presentation filter, not a performance optimization.

**Tests:**
- `CoverageToolTest` — mock pom with `<groupId>com.example</groupId>`; assert auto-derive picks `com.example` and excludes `org.mockito.*`, `net.bytebuddy.*`, `com.sun.proxy.*`.
- Explicit `package_filter` param; assert it overrides auto-derive.
- Empty project (no pom, no gradle, no src tree); assert unfiltered + warning log.
- Multi-module pom; assert parent groupId is used.

## Testing strategy

- Each item has ≥1 unit test in `agent/src/test/kotlin/...`.
- A1, A2, A4 also get integration-style tests via the existing tool-execution harness (look for `*ToolIntegrationTest.kt` patterns).
- B2, A3, E1 are message-only; light unit tests suffice.
- After each item: `./gradlew :agent:test --tests <new test>`.
- After full bundle: `./gradlew :agent:test verifyPlugin`.

## Risk register

| Risk | Mitigation |
|------|-----------|
| A4 — `executeSynchronously()` on EDT could freeze UI | Pre-check dispatch path; wrap in `withContext(Dispatchers.IO)` if needed. |
| A4 — Tools might block forever if indexing never finishes | `inSmartMode()` does not enforce a timeout. Add `withTimeoutOrNull(30_000)` around the read action if soak testing shows hangs. |
| B1 — Hypothesis about new-code vs overall is wrong | Verify before coding by reading `:core` Sonar service. Don't ship the labels without that confirmation. |
| A1 — `forceSpill` semantics affect other tools using `spillOrFormat` | Default value `false` keeps existing behavior; only DbQueryTool calls it with `true` in this bundle. |
| A2 — Auto-derive from pom/gradle may misfire in monorepos or custom layouts | Fallback to source-tree walk, then unfiltered with warning. Explicit `package_filter` always available as escape hatch. |

## Deferred items (not in this bundle)

- **C1 — `edit_file` streaming-preview vs git-status confusion.** Streaming preview deliberately keeps the real file untouched until end-of-stream. Fix is a system-prompt note about ordering; tracked separately so this bundle doesn't touch the prompt builder.
- **C2 — `search_code` on agent-data-dir paths.** PathValidator already permits these (`PathValidator.kt:179–184`). The "Search path not found" error fires when the file physically doesn't exist. Minor fix: split the error into "validation OK, file missing" vs "path not allowed". Defer.
- **C3 — `bamboo grep_pattern` case-sensitivity.** Grep impl uses `containsMatchIn` correctly. Likely cause is case-sensitivity mismatch (`SNAPSHOT` vs `snapshot`). Fix is a system-prompt doc about the `(?i)` flag. Defer.
- **D1 — Windows `nul` file.** No agent-source code produces `>nul` or `File("nul")`. Most likely culprit: a user hook script in `.agent-hooks.json` or unrelated IDE subsystem. Recommend logging on the Windows machine before any code change.

## Success criteria

- All 7 items land on `bugfix` as discrete commits.
- `./gradlew :agent:test verifyPlugin` passes locally on macOS.
- A user-side smoke test confirms:
  - `find_definition` succeeds during a triggered reindex (close + reopen project).
  - `bamboo_builds recent_builds` without `plan_key` returns the new error message.
  - `db_query` with `output_file=true` on a small result returns a file path that exists with full content.
  - `coverage run_with_coverage` returns a filtered result without ByteBuddy/Mockito.
- Single PR opened to `main`; reviewed and merged.
