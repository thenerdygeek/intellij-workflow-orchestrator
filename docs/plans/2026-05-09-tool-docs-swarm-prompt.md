# Tool Documentation Swarm — Subagent Prompt Template

**Status:** READY (Phase 5 of the Tool Documentation Initiative — `~/.claude/projects/.../memory/project_tool_documentation_initiative.md`).
**Used:** dispatched once per tool via `subagent_type: general-purpose`, 3 in parallel.
**Branch:** `fix/automation-handover-quality-tabs`. All subagents commit on this branch.
**Pre-flight:** the two pilots (`read_file`, `debug_step`) must be visually verified by the user before this prompt is dispatched.

---

## How to dispatch

Replace `{TOOL_NAME}` with the target tool's registered name (e.g. `edit_file`, `jira`, `bamboo_builds`). Replace `{TOOL_FILE_PATH}` with the absolute path to the `*.kt` file containing that tool's class. Then send the entire prompt below as the subagent's task — nothing else.

Example dispatch:

```
Agent({
  description: "Document edit_file via DSL",
  subagent_type: "general-purpose",
  prompt: <the full prompt below, with {TOOL_NAME}=edit_file and {TOOL_FILE_PATH}=agent/.../EditFileTool.kt>
})
```

Cap at **3 in-flight subagents at a time** (per the locked decision in the project memory). Wait for each batch of 3 to finish-and-commit before launching the next batch.

---

## The prompt

````
You are documenting a single agent tool by adding a `documentation()` override that returns a `ToolDocumentation` instance built via the `toolDoc { ... }` DSL. This is hand-authored work — do not paraphrase from the LLM-facing `description`. Quality matters because users will use these docs to decide which tools to drop.

## Target

- Tool name: `{TOOL_NAME}`
- Tool source file: `{TOOL_FILE_PATH}`
- Branch: `fix/automation-handover-quality-tabs` (already checked out — do NOT create worktrees)

## Required reading (in order)

1. The DSL schema and field semantics:
   - `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/docs/ToolDocumentation.kt`
   - `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/docs/ToolDocBuilder.kt`
2. The two reference pilots — match their density and tone:
   - `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/ReadFileTool.kt` (single-action exemplar)
   - `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugStepTool.kt` (multi-action exemplar)
   - And their narratives: `agent/src/main/resources/tool-docs/read_file.md` and `debug_step.md`
3. The target tool's source: `{TOOL_FILE_PATH}` — read it fully, including the `description` constant and all parameter definitions.
4. Anything the tool calls into for actual logic — e.g. helpers in the same package — to ground your failure-mode and downsides claims in real code.

## What to write

Add a `documentation(): ToolDocumentation = toolDoc("{TOOL_NAME}") { ... }` override to the tool class. Required fields and quality bar:

| Field | Required? | Quality bar |
|---|---|---|
| `summary.technical` | yes | One sentence engineer-facing. Concise, jargon ok. |
| `summary.plain` | yes | One sentence with an analogy a non-engineer would understand. |
| `whatLLMSees` | yes | EXACTLY the `description` constant from the tool source. Copy verbatim — do not edit, summarize, or improve it. |
| `sideEffect` | yes | One of READ_ONLY / AGENT_CONTROL / FILE_WRITE / PROCESS_SPAWN / NETWORK / IDE_MUTATION. Pick the broadest the tool can produce. |
| `actions` (multi-action only) | yes if meta-tool | One ActionDoc per action enum value. Be exhaustive — if the tool dispatches on 11 actions, document all 11. |
| `singleActionParams` (single-action only) | yes if no `actions` | One ParamDoc per parameter, marking required vs optional from the FunctionParameters. |
| `toolVerdict` | yes | At least `keep` OR `drop` set, with a real `severity` (STRONG / NORMAL / WEAK). "No verdict" is a flag — do not leave both null. |
| `counterfactual` | strongly preferred | 1-3 sentences: what would the LLM do without this tool? Forces the drop-decision to be concrete. |
| `commonLLMMistakes` | encouraged (≥1 if observable) | Patterns the LLM gets wrong. Distinct from `downsides` (gotchas of the tool itself). E.g. "LLM forgets to call `get_state` first". |
| `downsides` | encouraged (≥1 if real) | Tool-side gotchas — known limits, edge cases. |
| `relatedTools` | encouraged | At least 1 alternative or complement if obvious. |
| `auditNotes` | optional | Merge opportunities, removable params, deprecations. |
| `flowchart` | optional | Mermaid source for an overall flow diagram (only if the tool has non-trivial control flow worth visualizing). |
| `narrativeResource` | optional | Set when long prose adds value beyond the structured fields. If set, also create `agent/src/main/resources/tool-docs/{TOOL_NAME}.md`. |

### Per-action quality bar (multi-action only)

Each ActionDoc must have:

- `description.technical` + `description.plain` (two registers)
- `whenLLMUses` — plain-English statement of the LLM's typical motivation for picking this action
- `requiredParams` / `optionalParams` — ALL params relevant to this action; add `rejectedParams` for params the tool surface exposes that THIS action ignores
- `preconditions` — runtime conditions that must hold (e.g. "debug session must be paused")
- `onSuccess` — what the LLM and user get back
- `onFailure` — at least 1 documented failure mode with `condition` + `response`
- `examples` — at least 1 concrete invocation
- `verdict` — keep/drop opinion at the action granularity (different actions in the same tool can have different verdicts)

## Style notes (lifted from the pilots)

- **Be specific.** "Returns an error" is bad. "Returns `EXITED_BEFORE_READY` when the JVM crashes during startup; the LLM should inspect tail output before retrying" is good.
- **Be concrete in `commonLLMMistakes`.** Each item must name an observable failure pattern, not a vague concern. If you cannot think of any concrete mistakes, leave the list empty rather than padding with generic advice.
- **The `counterfactual` is the most valuable field for drop decisions.** Treat it as the question "if I deleted this tool tomorrow, what would the LLM use instead, and how much worse would the next session be?"
- **Verdict severity:**
  - STRONG = high-confidence, would defend in a review meeting
  - NORMAL = leaning, would change mind on new evidence
  - WEAK = best-guess, would happily defer to someone with more data
- **Two registers, no overlap.** The `plain` register should NOT be a summary of the `technical` one — it should be the same idea aimed at a non-engineer (analogy welcome).

## Verification (must complete before commit)

1. Run `./gradlew :agent:compileKotlin` — must be BUILD SUCCESSFUL. If you used `--rerun-tasks`, re-run without it to confirm the cache is consistent.
2. Verify the `whatLLMSees` field literally matches the source `description` constant (string-equal). Use a `diff` or a careful re-read.
3. If you set `narrativeResource`, verify the `.md` file exists at the expected path and is non-empty.
4. (Optional but recommended) Open the tool docs UI in the IDE if convenient: Tools → View Tool Documentation → pick `{TOOL_NAME}`. Confirm the JCEF tab renders without console errors.

## Commit

ONE commit on `fix/automation-handover-quality-tabs`. Format:

```
feat(agent): document {TOOL_NAME} via DSL ({N} actions)

- {one-line summary of the tool's purpose in plain language}
- {one-line on the verdict — keep, drop, or mixed, with severity}
- {one-line on the most surprising thing in the docs, e.g. "3 actions silently ignore the `path` param"}
```

For single-action tools, drop the `({N} actions)` suffix.

DO NOT use `--no-verify`. DO NOT skip hooks. DO NOT amend prior commits.

## Out of scope

- Do NOT modify `description`, `parameters`, `execute`, or any runtime behavior of the tool. Documentation is hand-authored alongside the existing code, never replacing it.
- Do NOT add `@Serializable` annotations or change the schema — Phase 3 already locked the schema.
- Do NOT attempt to render the docs in the IDE as part of verification — assume the JCEF rendering path works (it was verified in Phase 4 with the pilots). Your job is to author the DSL block correctly and prove it compiles.
- Do NOT touch tools other than `{TOOL_NAME}`. If you find issues in adjacent tools while reading source, note them in your final report (not in the commit).

## Done = ?

You report back with:

- The commit SHA you created.
- The line count of the `documentation()` block you added (rough quality signal).
- A 2-3 sentence summary of the tool's verdict and any surprising findings.
- Any blockers (e.g. tool source unintelligible, schema mismatch) — but only after you've genuinely tried.
````

---

## Out-of-band notes (not for the subagent — for the dispatcher)

- The subagent should NOT need access to runtime metadata (tier, schemaTokenCost, etc.) — that's computed by `ToolDocPayloadBuilder` at render time, never authored.
- After all batches finish, run `./gradlew :agent:test --rerun-tasks` once to make sure no test broke. Tests don't currently assert against `documentation()`, so this is just safety.
- Then dispatch Phase 6 verification subagents (one per tool, read-only) to cross-check the committed docs against the actual tool source. Verification subagent prompt: TODO (write at start of Phase 6).
- The Compare Tools view (Phase 7) consumes the JSON aggregate of all `ToolDocumentation` blocks, which is why uniformity matters — every doc must populate the required fields or the comparison heatmap has holes.

## Tools targeted by Phase 5

Approximately 77 tools across these categories. Group by package to maximize subagent context locality (one batch per category):

- **builtin/** — `edit_file`, `create_file`, `search_code`, `glob_files`, `run_command`, `revert_file`, `attempt_completion`, `task_report`, `think`, `ask_followup_question`, `plan_mode_respond`, `enable_plan_mode`, `use_skill`, `new_task`, `render_artifact`, `tool_search`, `agent`, `task_create`, `task_update`, `task_list`, `task_get`, `find_definition`, `find_references`, `diagnostics`
- **background/** — `background_process`, `send_stdin`
- **debug/** — `debug_inspect`, `debug_breakpoints` (debug_step is already done)
- **runtime/** — `runtime_exec`, `java_runtime_exec`, `python_runtime_exec`, `runtime_config`, `coverage`
- **framework/** — `spring`, `django`, `fastapi`, `flask`, `build`
- **ide/** — `format_code`, `optimize_imports`, `refactor_rename`, `run_inspections`, `problem_view`, `list_quickfixes`, `find_implementations`, `file_structure`, `type_hierarchy`, `call_hierarchy`, `type_inference`, `dataflow_analysis`, `get_method_body`, `get_annotations`, `test_finder`, `structural_search`, `read_write_access`, `project_structure`, `current_time`, `ask_user_input`, `project_context`
- **vcs/** — `changelist_shelve`
- **database/** — `db_list_profiles`, `db_list_databases`, `db_query`, `db_schema`, `db_stats`, `db_explain`
- **integration/** — `jira`, `bamboo_builds`, `bamboo_plans`, `sonar`, `bitbucket_pr`, `bitbucket_repo`, `bitbucket_review`

Exact list to be re-derived from `ToolRegistry` at dispatch time — packages above are best-effort grouping for context loading, not authoritative inventory.
