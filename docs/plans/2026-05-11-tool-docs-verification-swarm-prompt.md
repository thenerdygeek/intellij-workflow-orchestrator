# Tool Documentation Verification Swarm — Subagent Prompt Template

**Status:** READY (Phase 6 of the Tool Documentation Initiative; follows the Phase 5 swarm prompt at `docs/plans/2026-05-09-tool-docs-swarm-prompt.md`).
**Subagent type:** `general-purpose`, model `sonnet`.
**Branch:** `fix/automation-handover-quality-tabs` (already checked out — do NOT create worktrees).
**Read-only.** Subagents make NO source edits and NO commits. They report findings; the dispatcher aggregates per-batch findings into `docs/research/2026-05-11-tool-docs-verification-findings.md` and commits between batches.

---

## How to dispatch

Each subagent verifies a small group of tools (5-6 typical). Replace `{BATCH_LABEL}` with a batch identifier (e.g. `B1`, `B2`) and `{TOOL_LIST}` with the comma-separated tool names + their source paths. Then send the entire prompt below as the subagent's task — nothing else.

Cap at **3 in-flight subagents at a time** (locked decision, same as Phase 5). Wait for each batch of 3 to return before launching the next batch.

---

## The prompt

````
You are verifying that the `documentation()` block of each named agent tool still accurately describes the actual source code. The Phase 5 swarm authored these blocks; ~60 commits have landed since, and some claims may have drifted. Your job is to surface discrepancies — NOT to fix them.

## Target

Batch label: `{BATCH_LABEL}`

Tools to verify (NAME → SOURCE FILE):

{TOOL_LIST}

## Required reading (in order)

1. The DSL schema (so you know what fields exist):
   - `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/docs/ToolDocumentation.kt`
   - `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/docs/ToolDocBuilder.kt`
2. For EACH tool in your batch, read in this order:
   - The tool source file in full (description constant, parameters, execute body, helper methods it calls).
   - The `documentation()` block at the bottom of the same file.
   - Any narrative MD file referenced via `narrativeResource` (e.g. `agent/src/main/resources/tool-docs/{NAME}.md`).
3. Anything the tool calls into for actual logic (helpers in the same package) — to verify claims about failure modes, side-effects, or behavior.

## Per-tool verification checklist

For EACH tool, walk through this checklist and record findings. Be specific — name the line, the symbol, or the source method. Vague concerns are useless.

### 1. `whatLLMSees` ≡ `description`

The string passed to `whatLLMSees(...)` must be character-equal to the tool's `description` property (after both are `.trimIndent()`-ed). Any divergence is a verification failure — it means the LLM-facing schema and the doc claim about it have diverged.

**How to check:** copy both strings, run them through a diff in your head (or `git grep -A1 whatLLMSees`). Pay special attention to documents recently edited (`read_document` was just touched in commit `0bdb145a9` — the description was extended).

### 2. Action counts (meta-tools only)

If the tool has an `actions(...)` block in its `documentation()`, count the entries and compare against:
- The action enum / when-dispatch in `execute()`
- The CLAUDE.md claim if any (see `agent/CLAUDE.md` for tables like "build | 26 actions")

**Common drift:** an action was added or removed in source, but `documentation()` wasn't updated. Or CLAUDE.md hasn't been updated even though `documentation()` is correct.

### 3. Parameter shape

For each `required(...)` / `optional(...)` call in `params { ... }`, verify the param:
- Exists in `FunctionParameters.properties` with matching type
- Has the same `required` status (yes/no) as the doc claims
- The doc's `llmSeesIt(...)` matches the param's `description` field (character-equal, like `whatLLMSees`)

### 4. References to runtime behavior

The `commonLLMMistakes`, `downsides`, and `observation` blocks make claims about runtime behavior. Spot-check the most concrete claims:
- Line number references (e.g. "AgentLoop.kt:955") — verify the line still exists and still says what's claimed
- Method/class references (e.g. "PathValidator.resolveAndValidateForRead") — verify these still exist with the claimed signature
- Error-code references (e.g. "STOP_FAILED", "TIMEOUT_WAITING_FOR_READY") — verify they're still emitted from the cited paths

### 5. Related-tools references

For each `related(toolName, ...)` call, verify `toolName` still exists as a registered tool (cross-check against `AgentService.registerAllTools()`).

### 6. Verdict pulse-check

`verdict { keep(...) or drop(...) }` — verify at least one is set with a real `VerdictSeverity`. Both-null is a flag.

If the verdict body cites a counterfactual or alternative tool, verify those still hold (e.g. "Without find_definition the LLM would fall back to search_code" — verify search_code still exists and the failure mode the verdict claims still happens).

### 7. Capability flags (deep checks for swarm-fix territory)

Some tools were patched after Phase 5 with capability flags or per-action write classification. If your tool's `documentation()` predates the fix, the docs may not mention the new gating:
- `find_definition` / `find_references` — `LanguageIntelligenceProvider.allProviders()` iteration (commit `3918e3d7b`)
- `revert_file` — PathValidator gate + VFS refresh (commit `8a8712f2d`)
- `create_file` — charset symmetry + accurate overwrite diff (commit `7a5b679b0`)
- `project_structure` / `runtime_config` — per-action `isWriteAction()` (commit `25235b6bd`)
- `flask` — class-base scan (commit `2519bb65f`)
- `debug_breakpoints` — `breakpoint_id` removal, `list_breakpoints` emits id (commit `a1550c500`)
- `debug_inspect` — `drop_frame` PC-only-rewind semantics (commit `a1550c500`)
- `structural_search` — `supportsStructuralSearch()` capability flag (commit `c78264e89`)
- `read_document` — new `offset` parameter (commit `0bdb145a9`)

If your tool is in this list, check whether the doc covers the fix.

## Output format

Return a single Markdown block per tool, ready to paste into the findings log. Format:

```markdown
### `{tool_name}` — verification result

**Source file:** `{path}`
**Verdict:** ✅ matches | ⚠ minor drift | 🚨 material drift

#### Match
- (claim that still holds, very brief — one bullet per checked item)

#### Drift (if any)
- **{specific claim from documentation()}** — what the doc says vs. what the source now says vs. line/symbol reference.

#### Suggested re-author hints (if drift found)
- (concrete change the next author should make — one line per suggestion)

#### Notes (optional)
- (anything surprising you noticed adjacent to the tool but not strictly in scope)
```

After all tools in your batch, write a 2-3 sentence cross-cutting summary block:

```markdown
### Batch {BATCH_LABEL} cross-cutting summary

(Patterns observed across this batch — e.g. "Three of five tools missed the post-Phase-5 capability flag." Plus any process notes.)
```

## Out of scope

- DO NOT modify source code. This is a read-only verification pass.
- DO NOT commit. You don't need write access to git; the dispatcher commits findings.
- DO NOT touch tools other than those in your batch. If you find issues in adjacent tools, note them in the "Notes (optional)" block of your closest in-batch tool.
- DO NOT re-grade the verdict (keep/drop). That's Phase 7 cleanup PR territory. Your job is "does this claim still hold?", not "is this still the right verdict?".
- DO NOT touch the in-flight uncommitted files (`AgentService.kt`, `AtomicFileWriter.kt`, `AgentController.kt`, `UserInputChannelCancellationTest.kt`, `AtomicFileWriterRetryTest.kt`). These are unrelated user work.

## Done = ?

You report back with:

- ONE Markdown block per tool (see format above), formatted ready-to-paste.
- ONE cross-cutting summary block.
- An overall verdict line: `BATCH {BATCH_LABEL} — N tools verified, M material drifts found, K minor drifts found`.

Do NOT summarize back in conversational prose; the per-tool blocks ARE your output. The dispatcher will paste them into the findings log verbatim.
````

---

## Out-of-band notes (not for the subagent — for the dispatcher)

- **Verification budget per subagent:** ~5 tools is the sweet spot. More than 8 risks rushed checks; fewer than 4 wastes the dispatch overhead.
- **Group by package for context locality** (matches Phase 5 batching). Don't mix a PSI tool with an integration tool — they need different context windows.
- **Capture cross-batch insights** in the findings log's batch summary blocks. The Phase 5 "real bugs surfaced" pattern (13 defects in close-out) was only possible because cross-cutting observations were aggregated.
- **No commits from subagents.** Dispatcher writes findings, commits per batch with format `docs(agent): tool-docs verification batch {N} findings`.

## Tools to verify — 80 across these groups

(Group labels match Phase 5 batching for context locality. Exact tool-to-batch assignment lives in the findings log header.)

- **builtin/** (24): `edit_file`, `create_file`, `search_code`, `glob_files`, `run_command`, `revert_file`, `attempt_completion`, `task_report`, `think`, `ask_followup_question`, `plan_mode_respond`, `enable_plan_mode`, `use_skill`, `new_task`, `render_artifact`, `tool_search`, `agent`, `task_create`, `task_update`, `task_list`, `task_get`, `find_definition`, `find_references`, `diagnostics`, plus `read_file`, `read_document`, `ai_review`, `ask_user_input`, `background_process`, `send_stdin`, `current_time`, `discard_plan`, `project_context` (33 total under builtin/)
- **debug/** (3): `debug_inspect`, `debug_breakpoints`, `debug_step`
- **runtime/** (5): `runtime_exec`, `java_runtime_exec`, `python_runtime_exec`, `runtime_config`, `coverage`
- **framework/** (5): `spring` (via `endpoints` tool too), `django`, `fastapi`, `flask`, `build` + `endpoints`
- **ide/** (~17): `format_code`, `optimize_imports`, `refactor_rename`, `run_inspections`, `problem_view`, `list_quickfixes`, `find_implementations`, `file_structure`, `type_hierarchy`, `call_hierarchy`, `type_inference`, `dataflow_analysis`, `get_method_body`, `get_annotations`, `test_finder`, `structural_search`, `read_write_access`, `get_build_problems`
- **project/** (1): `project_structure`
- **vcs/** (1): `changelist_shelve`
- **database/** (6): `db_list_profiles`, `db_list_databases`, `db_query`, `db_schema`, `db_stats`, `db_explain`
- **integration/** (7): `jira`, `bamboo_builds`, `bamboo_plans`, `sonar`, `bitbucket_pr`, `bitbucket_repo`, `bitbucket_review`

Batch assignments are decided per-dispatch — keep groups coherent (all-PSI, all-integration, etc.) to maximize subagent context efficiency.
