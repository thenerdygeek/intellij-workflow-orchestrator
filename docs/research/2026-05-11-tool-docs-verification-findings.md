# Tool Documentation Verification — Findings Log (Phase 6)

Append-only log of subagent findings from the Phase 6 verification swarm. Each batch reports per-tool match/drift/suggestions plus a cross-cutting summary. Findings beyond the verification block itself are captured here so they aren't lost when the subagent context is discarded.

**Plan:** `docs/plans/2026-05-11-tool-docs-verification-swarm-prompt.md`
**Prior phase:** Phase 5 swarm findings at `docs/research/2026-05-09-tool-docs-swarm-findings.md`
**Scope:** 80 tools (verified count); ~5 tools per subagent × 3 parallel ≈ 5-6 batches total.
**Branch:** `fix/automation-handover-quality-tabs` — read-only verification, no source edits.

## Batch assignments

| Batch | Subagent | Tools | Status |
|---|---|---|---|
| B1.1 | recently-touched (capability flags) | `find_definition`, `find_references`, `revert_file`, `create_file`, `read_document` | pending |
| B1.2 | recently-touched (debug + plan-mode guards) | `debug_breakpoints`, `debug_inspect`, `structural_search`, `project_structure`, `runtime_config` | pending |
| B1.3 | recently-touched (frameworks + flask fix) | `flask`, `django`, `fastapi`, `spring`, `build` | pending |
| B2.1 | builtin core (file ops) | `read_file`, `edit_file`, `search_code`, `glob_files`, `run_command` | pending |
| B2.2 | builtin control flow | `attempt_completion`, `task_report`, `agent`, `new_task`, `tool_search` | pending |
| B2.3 | builtin auxiliary | `think`, `current_time`, `ask_followup_question`, `ask_user_input`, `plan_mode_respond` | pending |
| B3.1 | tasks + plan + skills | `task_create`, `task_update`, `task_list`, `task_get`, `use_skill`, `enable_plan_mode`, `discard_plan` | pending |
| B3.2 | PSI navigation | `find_implementations`, `file_structure`, `type_hierarchy`, `call_hierarchy`, `type_inference`, `dataflow_analysis` | pending |
| B3.3 | PSI metadata + diagnostics | `get_method_body`, `get_annotations`, `test_finder`, `read_write_access`, `diagnostics`, `get_build_problems`, `problem_view` | pending |
| B4.1 | ide quality | `format_code`, `optimize_imports`, `refactor_rename`, `run_inspections`, `list_quickfixes` | pending |
| B4.2 | runtime exec | `runtime_exec`, `java_runtime_exec`, `python_runtime_exec`, `coverage` | pending |
| B4.3 | misc | `endpoints`, `changelist_shelve`, `background_process`, `send_stdin`, `render_artifact`, `project_context`, `ai_review` | pending |
| B5.1 | database | `db_list_profiles`, `db_list_databases`, `db_query`, `db_schema`, `db_stats`, `db_explain` | pending |
| B5.2 | integration A | `jira`, `bamboo_builds`, `bamboo_plans` | pending |
| B5.3 | integration B | `sonar`, `bitbucket_pr`, `bitbucket_repo`, `bitbucket_review` | pending |

15 dispatches × 5 batches × 3-parallel = batches B1, B2, B3, B4, B5. B1 prioritizes the 10 tools touched by post-Phase-5 swarm-surfaced bug fixes (highest drift probability).

---

## Batch B1 — 2026-05-11 (recently-touched tools)

Sub-batches B1.1/B1.2/B1.3 dispatched in parallel.

### B1.1 — capability-flag fixes (find_definition, find_references, revert_file, create_file, read_document)

#### `find_definition` — ✅ matches
- All `whatLLMSees` / params / capability-flag observations align with source.
- Verdict + related-tools + sideEffect all check out.

#### `find_references` — ⚠ minor drift
- **Drift:** `params.symbol.whenPresent` still says "hardcoded `JAVA`/`kotlin` provider lookup runs" — but commit `3918e3d7b` replaced that with `registry.allProviders().firstNotNullOfOrNull`. The `observation` blocks correctly describe the fix; the `params` prose contradicts it.
- **Drift:** `downside` references "lines 144-147" but the fallback now lives in `resolveSearchTarget()` at lines 221-224 — stale line numbers.
- **Re-author:** replace the "hardcoded" prose with the allProviders() iteration; replace stale line numbers with the method reference.

#### `revert_file` — ✅ matches
- PathValidator + VFS refresh fixes from commit `8a8712f2d` are explicitly documented.

#### `create_file` — ✅ matches
- Both charset-symmetry and overwrite-diff fixes from commit `7a5b679b0` are documented with source line confirmation.

#### `read_document` — ⚠ minor drift
- **Drift:** flowchart (lines 279-298 of `documentation()`) does NOT model the new `offset` branching: no negative-offset rejection branch, no offset-past-end end-of-document branch, no slice-with-continuation-hint branch. The `params` block and `llmMistake` entries cover offset in text, but the flowchart — first thing rendered in the UI — is stale relative to the post-commit `0bdb145a9` execution graph.
- **Drift (minor clarity):** `offset.whenPresent` doesn't explicitly say the extractor is invoked with budget `offset + max_chars` (the implementation detail readers may want to know).
- **Re-author:** extend flowchart with three new branches reflecting the offset paths; clarify the budget calculation in `whenPresent`.

### B1.2 — plan-mode guards + capability flags (debug_breakpoints, debug_inspect, structural_search, project_structure, runtime_config)

#### `debug_breakpoints` — ⚠ minor drift
- **Drift:** `observation` block (line 766) says *"The KDoc comment on the class says '8 actions covering breakpoint CRUD and session lifecycle initiation'"* — but the KDoc has already been updated (commit `a1550c500` / `7eb703cca`) and now says **"7 actions"**. The observation is itself stale: it describes a problem that has been fixed.
- **Re-author:** replace the stale observation with a note that `start_session` was removed; the role is now `runtime_exec(action=run_config, mode=debug)`.
- **Note (out of scope but flagged):** `DebugInspectTool.kt:925` source comment still references removed `start_debug_session` — for cleanup pass.

#### `debug_inspect` — ✅ matches
- `drop_frame` PC-only-rewind semantics from commit `a1550c500` are fully and correctly documented in `description`, `documentation().actions.drop_frame`, and `llmMistakes`.
- All 9 actions match dispatch.

#### `structural_search` — 🚨 material drift
- **Drift (material):** `llmSeesIt` for `file_type` says *`Language: "java", "kotlin", or "python" (default: tries all available)`* but the actual `ParameterProperty.description` says *`Language: "java" or "kotlin" (default: tries all SSR-capable providers). Python is not supported.`* These are not character-equal — and the doc misleads readers into thinking Python is a schema-valid option when the schema explicitly says it isn't.
- **Re-author:** sync `llmSeesIt` to the actual `ParameterProperty.description` verbatim.
- `supportsStructuralSearch()` capability flag from commit `c78264e89` is well-covered.

#### `project_structure` — ✅ matches
- `isWriteAction()` override (commit `25235b6bd`) correctly enumerated as 8 write actions in `observation` + `verdict.keep`.
- All 14 actions match dispatch.

#### `runtime_config` — ⚠ minor drift
- **Drift:** `delete_run_config.onFailure` quotes the runtime error message verbatim: *"only agent-created configurations (containing [Agent] in name) can be deleted"* — but the actual guard is `startsWith("[Agent]")`. Both the runtime error text and the doc say "containing" while the actual check is "starts with." Source-side bug surfaced by doc verification.
- **Re-author:** fix runtime error text + doc `onFailure` to say "starting with '[Agent]'".

### B1.3 — framework tools (flask, django, fastapi, spring, build)

#### `flask` — 🚨 material drift
- **Drift (material, multiple touch points):** commit `2519bb65f` changed both `models` and `forms` actions from filename-scoped scanning (`models.py`, `forms.py`) to class-base scanning across all `.py` files. The `documentation()` block was NOT updated. Seven distinct claims still describe the old behavior:
  - `models.action.technical(...)` says "Scans files literally named `models.py`"
  - `forms.action.technical(...)` says "Scans files literally named `forms.py` or `form.py`"
  - Two `llmMistake(...)` entries claim filename-only scoping
  - One `downside(...)` says "scope to filename conventions"
  - Two `precondition(...)` blocks claim a specific filename file must exist
  - Two `onFailure` strings diverge from actual error messages (`"No SQLAlchemy model files found in project."` and `"No Flask-WTF form files found in project."`)
- **Re-author:** rewrite all 7 references to reflect class-base scanning via `FILE_CONTAINS_MODEL_PATTERN` / `FILE_CONTAINS_FORM_PATTERN`.
- **Implementation inconsistency noted:** `MODEL_CLASS_PATTERN` uses `(?:db\.Model|Model)` but `FILE_CONTAINS_MODEL_PATTERN` includes `BaseModel` — files containing only `BaseModel` subclasses are file-gated in but their classes aren't extracted. Source-side issue, future cleanup.

#### `django` — ⚠ minor drift
- **Drift:** Class-level KDoc comment (DjangoTool.kt line 34) says *"replacing 13 individual Django analysis tools"* but the dispatcher has 14 actions. The `documentation().observation` self-notes the discrepancy, but the source KDoc was not corrected.
- **Re-author:** fix the KDoc comment to 14 (or drop the count).

#### `fastapi` — ✅ matches
- All 10 actions and contracts match source.

#### `spring` — ⚠ minor drift
- **Drift:** CLAUDE.md line 146 says `spring | 15` but actual source dispatch is 16 actions (added `annotated_methods`). The `documentation()` summary correctly says 16; CLAUDE.md was not updated alongside.
- **Re-author:** update CLAUDE.md to `spring | 16`; optionally add `annotated_methods` to the documentation summary enumeration explicitly.

#### `build` — ✅ matches with stale observation
- All 26 actions match source + CLAUDE.md (post-`7eb703cca` correction).
- **Stale observation:** `observation` at line 331 still says *"CLAUDE.md lists `build` as an 11-action tool"* — that correction has been applied (commit `7eb703cca`); the observation describes a past state.
- **Re-author:** remove the stale observation or rewrite it to acknowledge the correction.

### Batch B1 — totals

**5 tools clean**, **3 minor drifts**, **2 material drifts** across 15 tools.

Patterns observed:
- **Phase-5 fix-but-no-docs-update is the most common pattern** (flask, structural_search llmSeesIt, find_references "hardcoded" prose, debug_breakpoints stale observation, read_document flowchart).
- **CLAUDE.md vs source drift is the secondary pattern** (spring 15→16, build observation describing past state, django KDoc 13→14).
- **One source-side bug surfaced** (runtime_config "containing" vs `startsWith` in the runtime error string).

## Batch B2 — 2026-05-11 (builtin tools)

Sub-batches B2.1/B2.2/B2.3 dispatched in parallel.

### B2.1 — file ops (read_file, edit_file, search_code, glob_files, run_command)

#### `read_file` — ✅ matches
- Phase 5 pilot; full alignment between doc and source. DEFAULT_LIMIT=200, MAX_LINE_CHARS=2000, MAX_FILE_SIZE=10M all match doc claims.

#### `edit_file` — ✅ matches
- Phase 5 pilot; all params, write paths (Document/VFS/I-O), and syntax validation gate match doc.

#### `search_code` — ✅ matches (1 cosmetic gap)
- All matches. Minor: `context_lines.whenPresent` doesn't redundantly mention the `output_mode='content'` precondition that the constraint does — emphasis gap only.

#### `glob_files` — ✅ matches
- `max_results * 2` early-termination, SKIP_DIRS, mtime-DESC sort all confirmed.

#### `run_command` — ⚠ minor drift
- **Drift:** `description` param is marked `optional` in the DSL but `FunctionParameters.required` includes it (in BOTH single-shell and multi-shell schemas). The `whenAbsent` text self-acknowledges the mismatch — authoring oversight.
- **Drift (count):** `BLOCKED_ENV_VARS` source count is 28, doc says "~25". Three off.
- **Re-author:** flip `description` to `required(...)` and remove `whenAbsent`; update BLOCKED_ENV_VARS count to "28".

### B2.2 — control flow (attempt_completion, task_report, agent, new_task, tool_search)

#### `attempt_completion` — ✅ matches
- ORCHESTRATOR-only allowedWorkers and three-kind dispatch (done/review/heads_up) all verified.

#### `task_report` — ✅ matches
- {CODER, REVIEWER, ANALYZER, TOOLER} allowedWorkers (ORCHESTRATOR excluded) and auto-injection logic in SpawnAgentTool both confirmed.

#### `agent` — ⚠ minor drift
- **Drift:** `SpawnAgentTool.documentation().observation` (lines 528-532) still says *"CLAUDE.md describes resume/kill/send/run_in_background actions; one of the two needs to be reconciled"* — but commit `7eb703cca` already reconciled CLAUDE.md (now correctly says "No LLM-callable resume/kill/send"). Stale self-reference.
- **Drift:** `agent.md` narrative (lines 190-194) has the same stale "biggest action item is reconciling docs" paragraph. Action was already done.
- **Re-author:** remove or update both stale references.

#### `new_task` — ✅ matches
- 5-section handoff guard + sessionHandoff return + ORCHESTRATOR allowedWorkers all verified.

#### `tool_search` — ✅ matches
- `select:` prefix dispatch + keyword fallback + max_results=5 default all confirmed.

### B2.3 — auxiliary (think, current_time, ask_followup_question, ask_user_input, plan_mode_respond)

#### `think` — ⚠ minor drift
- **Drift:** `counterfactual` references `MAX_NO_TOOL_NUDGES=4` — that named constant does NOT exist in source. The actual limit is a local `val maxConsecutiveMistakes = 3` at `AgentLoop.kt:676`.
- **Re-author:** replace with "maxConsecutiveMistakes=3 (inline val at AgentLoop.kt:676)".

#### `current_time` — ⚠ minor drift
- **Drift:** Technical summary lists Local/UTC/Day outputs but the source `execute()` (line 68) also emits a fourth field `Timezone: ${now.zone}` (IANA zone ID). Doc undercounts.
- **Re-author:** extend summary to include the IANA timezone field.

#### `ask_followup_question` — ✅ matches
- All 4 params, two execution paths (simple/wizard), 5-min timeout + 10s UI watchdog confirmed.

#### `ask_user_input` — ⚠ minor drift
- **Drift:** `observation` says *"A shared helper (ProcessToolHelpers.monitorAfterWrite) already exists — verify both tools delegate to it and have not drifted"* — but `monitorAfterWrite` does NOT exist. The shared layer is only `collectNewOutput` + `buildIdleContent`; the full monitor loop is inlined separately in `AskUserInputTool` and `SendStdinTool`.
- **Re-author:** rewrite the observation to honestly say the helper doesn't exist yet but extracting one would pin the invariants.
- **Note (audit, source-side):** `sideEffect(AGENT_CONTROL)` arguably belongs in `PROCESS_SPAWN` — the tool writes to a live process's stdin and can kill it. Not a doc-vs-source drift; classification audit item.

#### `plan_mode_respond` — ✅ matches
- All params, boolean coercion path, `userInputChannel.receive()` suspension, schema filter behavior all verified.

### Batch B2 — totals

**8 tools clean**, **6 minor drifts**, **0 material drifts** across 15 tools.

Patterns observed (B2):
- **Phase 5 pilots (read_file, edit_file) remain accurate** — drift is concentrated in non-pilot tools that were authored faster.
- **Stale self-referential observations are appearing** (`agent.observation` claiming CLAUDE.md drift exists when it's been fixed; `ask_user_input.observation` claiming a helper exists when it doesn't). These are notable because they read confidently in the JCEF UI even though they're wrong.
- **Constant references decay** (`MAX_NO_TOOL_NUDGES=4` non-existent, BLOCKED_ENV_VARS 25→28 undercount).
- **Required-vs-optional schema mismatch** in run_command — easy to miss because `whenAbsent` papers over it.

## Batch B3 — 2026-05-11 (tasks + plan + PSI)

Sub-batches B3.1/B3.2/B3.3 dispatched in parallel.

### B3.1 — tasks + plan + skills (7 tools)

- **`task_create`, `task_update`, `task_list`, `use_skill`, `discard_plan`** — ✅ matches. TaskStatus enum (pending/in_progress/completed/deleted) correctly documented per the `7eb703cca` drift sweep. `blocks`/`blockedBy` correctly absent from task_create params.
- **`task_get`** — ⚠ minor drift. `removableParam` audit note claims `task_update` uses `id` as its param — actually it uses `taskId` (same as task_get). Author premise is factually wrong.
- **`enable_plan_mode`** — ⚠ minor drift. Technical summary references the deprecated `enablePlanMode=true` boolean field rather than the canonical `ToolResultType.PlanModeToggle` dispatch path.

### B3.2 — PSI navigation (6 tools)

All six use the correct `allProviders().firstNotNullOfOrNull` or `registry.forFile(psiFile)` pattern — the hardcoded JAVA/kotlin fallback bug is **absent** from this group. Dominant drift class: **stale line-number references** in observation/downside blocks pointing into the doc block itself (e.g., "line 49 of XxxTool.kt") instead of the actual execute() body.

- **`call_hierarchy`** — ✅ matches.
- **`find_implementations`, `file_structure`, `type_hierarchy`, `type_inference`, `dataflow_analysis`** — ⚠ minor drift (stale line refs + one DSL misuse in `dataflow_analysis` where a positive verification statement was placed in `downside()` rather than `observation()`).
- **`type_hierarchy`** also missing spill-to-disk documentation (unlike `call_hierarchy` which documents `spillOrFormat`).

### B3.3 — PSI metadata + diagnostics (7 tools)

The diagnostics family (`diagnostics`, `get_build_problems`, `problem_view`) correctly implements and documents the `isError=false-when-problems-found` contract.

- **`test_finder`, `diagnostics`, `get_build_problems`** — ✅ matches.
- **`get_method_body`, `get_annotations`, `read_write_access`** — ⚠ minor drift (stale line numbers pointing into doc block instead of execute body).
- **`problem_view`** — ⚠ minor drift. `llmMistake` quotes `"Flagged but no details for X"` but actual `ToolResult.content` is `"No detailed problems available for X"` — the doc was quoting the `summary` field instead of the `content` field, but the LLM sees `content`.

### Batch B3 — totals

**9 tools clean**, **11 minor drifts**, **0 material drifts** across 20 tools.

Patterns observed (B3):
- **Stale line-number references is the dominant B3 pattern** — line numbers from before the `documentation()` block was inserted point into the doc block itself, not execute(). 6 of 20 tools affected.
- **Capability-flag fix coverage is excellent** for PSI tools — `allProviders()` pattern is correctly applied across all 6 PSI navigation tools.
- **One audit-note premise was factually wrong** (`task_get` claiming `task_update` uses `id`).
- **One LLM-visible quoted-message-text drift** (`problem_view` quotes the summary instead of the content).
