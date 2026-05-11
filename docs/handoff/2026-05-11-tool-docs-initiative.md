# Tool Documentation Initiative — Session Handoff (2026-05-11)

> Hand this document to the next session before any agent work resumes. It is self-contained — the next session does not need to ask clarifying questions before reading `git log` and the linked artifacts.

## Project at a glance

- **Repo:** IntelliJ Plugin for Workflow Orchestrator (`/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin`).
- **Branch:** `fix/automation-handover-quality-tabs` (don't create worktrees — per memory `feedback_work_on_current_branch.md`).
- **Plugin version:** ~0.85.8-alpha as of the most recent user commits before this handoff.
- **Initiative goal:** add rich, source-embedded per-tool documentation to every agent tool so the user can audit and drop unused tools. Phase 5 swarm + bug-fix backlog now complete.

## Where the ground truth lives

| Artifact | Path |
|---|---|
| Project memory (full history, auto-loaded by Claude Code) | `~/.claude/projects/-Users-subhankarhalder-Desktop-Programs-scripts-IntelijPlugin/memory/project_tool_documentation_initiative.md` |
| Findings log (committed, batch-by-batch + close-out summary) | `docs/research/2026-05-09-tool-docs-swarm-findings.md` |
| Phase 5 swarm prompt template (one-tool-per-subagent) | `docs/plans/2026-05-09-tool-docs-swarm-prompt.md` |
| DSL schema | `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/docs/ToolDocumentation.kt` |
| DSL builder | `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/docs/ToolDocBuilder.kt` |
| Payload builder (runtime metadata) | `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/docs/ToolDocPayloadBuilder.kt` |
| JCEF FileEditor + Tools menu entry | `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/tooldocs/` |
| Pilot tool docs (reference patterns) | `ReadFileTool.kt` (single-action exemplar), `DebugStepTool.kt` (multi-action exemplar) |
| Long-form narrative MDs | `agent/src/main/resources/tool-docs/*.md` (12 files) |

## What's DONE

### Phase 3 — UI wiring (5 commits)

- `@Serializable` schema + `ToolDocPayloadBuilder` (runtime metadata: tier, schemaTokenCost, planModeBlocked, approvalPolicy, etc. — computed from `AgentLoop.WRITE_TOOLS` / `ApprovalPolicy` / `ToolRegistry`).
- JCEF `ToolDocsEditor` per-tool FileEditor + provider registration in `src/main/resources/META-INF/plugin.xml`.
- React `<ToolDocView>` in `agent/webview/src/components/tool-docs/` mirroring the static preview at `/tmp/tool-docs-preview/`.
- `(i)` glyph + double-click + "View Documentation" button on `ToolTestingPanel`.
- Top-level `Tools → View Tool Documentation` action with searchable JBPopup chooser.

### Phase 5 — Swarm (80/80 tools = 100% coverage, ~110 commits)

- Every tool has `documentation()` with verdict, counterfactual, params, common LLM mistakes, related tools, audit notes.
- 12 long-form narrative MD files for complex tools (run_command, jira, agent, edit_file, render_artifact, debug_step, debug_inspect, runtime_exec, new_task, use_skill, read_file, bitbucket_pr).
- Dispatch pattern that works: `subagent_type: general-purpose, model: sonnet`, 3 parallel max, `sleep $((RANDOM % 3 + 1))` before commit (race mitigation).

### Bug-fix backlog — 13/13 swarm-surfaced defects closed (7 fix commits + 1 retro-test commit)

| Commit | Bugs | Regression tests |
|---|---|---|
| `3918e3d7b` | find_definition + find_references PSI fallback (Java/kotlin hardcoded → `allProviders` iteration) | Added retroactively in `c0cefca44` |
| `8a8712f2d` | revert_file PathValidator bypass + missing VFS refresh | Added retroactively in `c0cefca44` |
| `7a5b679b0` | create_file charset asymmetry + misleading overwrite diff | Added retroactively in `c0cefca44` |
| `25235b6bd` | plan-mode guard recognizes per-action write classification (`AgentTool.isWriteAction(action)` interface method; `ProjectStructureTool` + `RuntimeConfigTool` override) | `PlanModeLoopTest` |
| `2519bb65f` | flask models/forms scan by class-base (db.Model / Model / BaseModel / FlaskForm / Form), not by filename match | `FlaskToolTest$SplitFileRegression` |
| `a1550c500` | debug_breakpoints `remove_breakpoint(breakpoint_id)`; `list_breakpoints` emits id; debug_inspect `drop_frame` description explicit about PC-only rewind | `DebugBreakpointsToolTest`, `DebugInspectToolTest` |
| `c78264e89` | structural_search capability flag (`supportsStructuralSearch()`) + Kotlin file-type resolution via `FileTypeManager.findFileTypeByName("Kotlin")` instead of hardwired `JavaFileType.INSTANCE` | `StructuralSearchToolTest`, `JavaKotlinProviderStructuralSearchTest` |

Plus two hygiene fixes landed during Batch B testing: `ModelPricingRegistry` thread leak in `PlanModeLoopTest` (`@AfterEach` cleanup added); `WriteAction.compute` NPE in `DebugBreakpointsTool` unit tests (wrapper removed since `XBreakpointManager.removeBreakpoint` in 2025.1+ handles thread dispatch internally).

### CLAUDE.md drift — 10/10 fixed (`7eb703cca`)

Corrections applied to `agent/CLAUDE.md` and `DebugBreakpointsTool.kt` KDoc:

- Action-count corrections: `build` 11→26, `sonar` 13→18, `bitbucket_review` 6→12, `bitbucket_pr` 18→19, `bamboo_plans` 8→10.
- "Agent Tool (Subagent Management)" section rewritten — the 5-action LLM API (`spawn`/`run_in_background`/`resume`/`kill`/`send`) never existed in source; replaced with actual params (`description`, `prompt`, `prompt_2..5`, `agent_type`, `model`).
- Removed phantom `EditFileTool.lastEditLineRanges` bullet from "Tool Execution" section — field doesn't exist in source.
- `TaskStatus` enum corrected: `pending`/`in_progress`/`completed`/`deleted` (not `TODO`/`IN_PROGRESS`/`DONE`/`BLOCKED`).
- `task_create` row corrected: params are `subject`/`description`/`activeForm`; dependency edges (`blocks`/`blockedBy`) are added via `task_update` only.
- `DebugBreakpointsTool` class KDoc updated to "7 actions" (predated `start_session` removal).

## What's LEFT TO DO

### 1. Cleanup PRs — 10+ drop/merge candidates (need per-candidate sign-off)

These reshape LLM-facing schemas — accept/defer/reject each one rather than executing wholesale. From highest to lowest value:

- **3-way merge:** `format_code` + `optimize_imports` + `refactor_rename` → `refactor(kind:format|imports|rename)`. ~80% shared scaffolding (DumbService → ReadAction → EDT WriteCommandAction → no-op detect → ToolResult); single-line dispatch difference for the first two. Saves 3 schema slots.
- **Drop `task_list`** — partially redundant with `EnvironmentDetailsBuilder.appendTasks` which already injects `id + status + subject` into every user turn. A 3-line patch adding `owner` + `blockedBy` to that render would let us drop `task_list` entirely. Only unique fields are `owner` and `blockedBy` edges.
- **Merge `send_stdin` ↔ `background_process(action=send_stdin)`** — semantically equivalent. Standalone `send_stdin` has guards (password-prompt detection via `ShellResolver.isLikelyPasswordPrompt`, per-process rate limiting via `AgentSettings.maxStdinPerProcess`) that the action lacks. Migration path: absorb guards into `BackgroundPool`, unify `ProcessRegistry`/`BackgroundPool` id namespaces, then drop standalone.
- **Merge `db_list_databases` → `db_schema` level-0** — natural extension of `db_schema`'s 3-level hierarchy (databases → schemas → tables → columns). Note: NOT mergeable with `db_list_profiles` — that's a pure config read with no network IO, fundamentally different from runtime discovery.
- **Drop `bitbucket_pr` actions:** `get_blocker_comment_count` (superseded by `check_merge_status`), `get_required_builds` (rare repo-level config, set once and never queried), `update_pr_title` (rare housekeeping — titles set at `create_pr` time).
- **Drop `jira` actions:** `get_worklogs` (time-log readback rarely the LLM's job), `get_board_issues` (semantically dominated by `search_tickets` JQL queries). Fold `get_dev_branches` + `get_linked_prs` into `get_ticket(include_dev_status=true)`.
- **Drop `spring.scheduled_tasks` + `spring.event_listeners`** — zero-param convenience wrappers around `annotated_methods(annotation=@Scheduled)` / `annotated_methods(annotation=@EventListener)`.
- **Move `bamboo_plans.get_build_variables` → `bamboo_builds`** — operates on `result_key`, semantically belongs with build observation, not plan config.
- **Collapse `build`'s pip/Poetry/uv triples** → `python_list(manager:pip|poetry|uv)`, `python_outdated(manager)`, `python_lock_status(manager)`. Reduces action count from 26 → 20 with no LLM capability loss.
- **Extract shared `InspectionWalker`** from `run_inspections` + `list_quickfixes` — ~60 LOC of duplicated `profile.getInspectionTools → LocalInspectionToolWrapper → HighlightDisplayKey.find → profile.isToolEnabled → buildVisitor → PsiRecursiveElementWalkingVisitor` scaffolding. Source-only refactor; no LLM schema change.

### 2. `read_document` no-pagination footgun (Batch 26 finding)

Not in the original 13-bug list, but real. `max_chars` is a total extraction cap, not a page size — LLM can't read "next chunk" of a large PDF; it must re-extract from page 1 with a larger cap. Either add an `offset` / page cursor parameter or document the limit explicitly in the LLM-facing `description`. 1 subagent, single-action change.

### 3. Phase 6 verification swarm

Read-only subagents cross-check each tool's `documentation()` claims against the actual source. The user landed ~60 commits on top of the swarm work, so some docs may have drifted. Each verifier reports per-tool: claims that match source, claims that don't match, suggestions for re-author. ~5 batches × 3 subagents.

## Working conventions to preserve

| Convention | Why | Memory source |
|---|---|---|
| `subagent_type: general-purpose, model: sonnet` for swarm work | Mechanical execution; ~30 tools/hr; matches Opus quality | `feedback_sonnet_for_small_tasks.md` |
| 3-parallel cap on subagent dispatches | Locked decision; race mitigation otherwise insufficient | swarm prompt template |
| `sleep $((RANDOM % 3 + 1))` before commit | Reduces parallel-staging collisions | findings log Batch 13 |
| **TDD mandatory for bug fixes** — failing test first, run-it-fails, then fix, then run-it-passes | Tests pin behavior against regression; without them silent-failure bugs creep back | user instruction 2026-05-11 |
| No `--no-verify`, no `--amend`, no Co-Authored-By trailer | | `feedback_no_coauthor.md` |
| Work on the current branch (no worktrees, no branches off main) | | `feedback_work_on_current_branch.md` |
| Always subagent-driven for plan execution | | `feedback_always_subagent.md` |
| Skip subagent code reviews during execution | Token cost not worth it for mechanical work | `feedback_skip_subagent_reviews.md` |
| One commit per tool (Phase 5) / per fix (bugs) | Clean revert granularity; audit trail by-tool | swarm prompt template |
| Read Phase 5 prompt template before dispatching new swarms | Has per-field quality bars + race mitigation language | `docs/plans/2026-05-09-tool-docs-swarm-prompt.md` |
| Save research to files, never conversation-only | | `feedback_preserve_research.md` |

## Recommended next session opening

1. **First action:** `git log --oneline -20` to see current branch head, then read this handoff in full + the close-out section at the bottom of `docs/research/2026-05-09-tool-docs-swarm-findings.md`.
2. **Choose one path** based on user input:
   - **(a)** Phase 6 verification swarm — low risk, catches doc drift first. Recommended before any cleanup PR work so the docs being acted on are known accurate.
   - **(b)** Walk cleanup candidates one-by-one with accept/defer/reject decisions — these RESHAPE LLM tool schemas, so each one needs explicit sign-off rather than batch execution.
   - **(c)** Quick wins like `read_document` pagination — single subagent, ~10 min.
3. **Confirm working tree state** with `git status --short` before dispatching anything. There may be unrelated user work in flight (`BambooBuildsTool.kt`, automation/bamboo/handover files) that should NOT be touched. The swarm prompt's "out of scope" guards exist for this reason — don't relax them.

## Build verification

`./gradlew :agent:compileKotlin` should be BUILD SUCCESSFUL on the current HEAD. If it's not green, something landed during the gap — investigate before dispatching swarm work.

`./gradlew :agent:test --tests "*FlaskToolTest*" --tests "*DebugBreakpointsToolTest*" --tests "*PlanModeLoopTest*" --tests "*StructuralSearchToolTest*"` should all pass (these are the swarm-fix regression tests).

One known pre-existing flake (NOT introduced by this work, confirmed on the unmodified branch): `CallHierarchyCycleDetectionTest.findCallers handles 3-node cycle` times out in headless sandbox. Safe to skip in headless runs.

---

**End of handoff.** All swarm-surfaced findings are captured in the findings log; this document is the executive summary + resumption checklist.
