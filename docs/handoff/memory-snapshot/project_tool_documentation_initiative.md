---
name: Tool Documentation Initiative (in progress)
description: Multi-session feature to add rich, dynamic per-tool documentation to the agent — surfaced via an (i) button on the tools panel, opens as a JCEF-rendered editor tab. Goal is to help user decide which tools/actions to drop.
type: project
originSessionId: 78be8a00-00ce-4de6-ad56-c7411dc4c02d
---
# Tool Documentation Initiative

**Branch**: `fix/automation-handover-quality-tabs` (current — work alongside ongoing PR work, separate commits)
**Started**: 2026-05-08
**Goal**: Verbose, dynamic, source-embedded documentation for every agent tool/action so user can audit and drop unused ones.

## Decisions (locked)
- **Doc source**: Hybrid — Kotlin DSL on the tool class for structured fields (params, verdicts, action specs); optional Markdown resource at `agent/src/main/resources/tool-docs/<name>.md` for long prose narratives loaded lazily.
- **Strategy**: Pilot 2 tools end-to-end (`read_file` simple, `debug_step` multi-action) → user reviews quality → dispatch subagent swarm (3-parallel max) for remaining ~77 tools.
- **UI surface**: New editor tab per tool via `LightVirtualFile` + custom `FileEditor` + JCEF browser. Reuses editor real estate; supports cross-tool comparison via multiple tabs.
- **Visual richness**: Mermaid flowcharts for action flows + auto-generated param tables + Recharts bar/pie for cross-tool stats + a "Compare Tools" page with side-by-side and action-overlap heatmap (duplication candidates).

## Architecture (locked)
- New: `ToolDocumentation` data class + nested `ActionDoc`, `ParamDoc`, `Verdict`, `ToolExample`. Lives in `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/docs/`.
- New: Kotlin DSL builder `toolDoc { ... }` (type-safe).
- New: `documentation(): ToolDocumentation?` added to `AgentTool` interface, default null (backwards compatible).
- New: Markdown resource loader for narrativeFromResource(name) — loads from classpath `/tool-docs/<name>.md`.
- New: `AgentController.buildToolDocsJson()` parallel to existing `buildToolsJson()`.
- New: `ToolDocsFileEditor` + `ToolDocsVirtualFile` (or a JCEF FileEditor pattern; check existing JCEF panels first).
- New: React component `ToolDocsView` rendered inside the FileEditor's JCEF browser.
- Wire `(i)` button on tools panel → bridge → opens editor tab.

## Out of scope for v1
- Auto-extracting docs from existing description strings (we want hand-authored where possible — though subagents will draft from source).
- LLM-side injection of rich docs (kept Kotlin-side; LLM still gets terse `description`).
- Editing docs from the UI (read-only viewer).

## Files (key references)
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/AgentTool.kt` — interface
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ToolRegistry.kt` — registry
- `core/src/main/kotlin/com/workflow/orchestrator/core/ai/dto/ToolCallModels.kt` — schema DTOs (`ParameterProperty`, etc.)
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt` — has `buildToolsJson()` + `showToolsPanel()`
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentDashboardPanel.kt` — JCEF host
- `agent/webview/src/stores/chatStore.ts` — has `toolsPanelData` (no UI yet — perfect insertion point)
- `agent/webview/src/bridge/jcef-bridge.ts` — bridge functions

## Tool inventory (from recon 2026-05-08)
- 79 total: ~28 core + ~51 deferred
- Multi-action meta-tools: `runtime_exec` (5), `java_runtime_exec` (3), `python_runtime_exec` (2), `debug_breakpoints` (7), `debug_step` (10), `debug_inspect` (9), `spring` (15+), `build` (11), `jira` (17), `bamboo_builds` (11), `bamboo_plans` (8), `sonar` (13), `bitbucket_pr` (18), `bitbucket_repo` (8), `bitbucket_review` (6), `background_process` (5), and more.
- Total actions across meta-tools: ~150+

## Phases
1. **Foundation** (this session): data classes + DSL + resource loader + interface change
2. **Pilot** (this session): read_file + debug_step fully documented
3. **UI** (this session): JCEF editor tab + React doc viewer + bridge + (i) button
4. **Verify pilot** (this session): Playwright check, user review
5. **Swarm extract** (next session): subagents document remaining ~77 tools (3-parallel)
6. **Verify extract** (next session): verification subagents check claims against source
7. **Comparison view** (next session): cross-tool heatmap + drop-candidate page
8. **Final pass** (next session): polish + commits

## Status updates (append below as work progresses)
- 2026-05-08 — Recon complete; decisions locked via AskUserQuestion; this memory created.
- 2026-05-08 — **Phase 1 (Foundation) + Phase 2 (Pilot doc data) DONE + COMMITTED.** Five commits on `fix/automation-handover-quality-tabs`:
  - `7b0f43498` — DSL infrastructure + AgentTool.documentation() hook
  - `020cb55c0` — read_file pilot v1 (DSL + narrative MD)
  - `b0bb3fafd` — debug_step pilot v1 (10 actions via DSL + narrative MD)
  - `6e24da530` — DSL extension: SideEffectKind (6 kinds incl. AGENT_CONTROL), counterfactual, commonLLMMistakes, AutoDerivedMetadata
  - `14e36ab54` — pilot backfill with new fields
  Compiles green via `./gradlew :agent:compileKotlin --rerun-tasks`. Files in commit set:
  - `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/docs/ToolDocumentation.kt` — data classes (ToolDocumentation, ActionDoc, ParamDoc, Verdict, AuditNote, RelatedTool, etc.)
  - `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/docs/ToolDocBuilder.kt` — type-safe Kotlin DSL (`toolDoc { ... }` with ActionsBuilder/ParamGroupBuilder/VerdictBuilder/etc., `@DslMarker ToolDocDsl`)
  - `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/docs/NarrativeLoader.kt` — classpath MD loader for `/tool-docs/<name>.md`
  - `agent/src/main/resources/tool-docs/read_file.md` — long-form narrative for read_file
  - `agent/src/main/resources/tool-docs/debug_step.md` — long-form narrative for debug_step
  Files modified (uncommitted):
  - `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/AgentTool.kt` — added `fun documentation(): ToolDocumentation? = null` default
  - `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/ReadFileTool.kt` — full DSL doc + narrative pointer
  - `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugStepTool.kt` — full DSL doc covering all 10 actions + narrative pointer
- 2026-05-08 — **Phase 3-7 STILL TODO.** Bridge endpoint (`buildToolDocsJson` in AgentController — must compute the AutoDerivedMetadata payload from ToolRegistry tier, ToolRegistrationFilter conditions, AgentLoop.WRITE_TOOLS membership, AgentTool.allowedWorkers/timeoutMs/outputConfig, plus TokenEstimator on the ToolDefinition for schemaTokenCost), JCEF FileEditor (`ToolDocsVirtualFile` + provider + plugin.xml registration — copy from `ToolTestingEditorTab` shape but JCEF-backed), React `<ToolDocView>` component (rendered inside the JCEF browser), `(i)` button on `ToolTestingPanel.kt` per row, Playwright E2E verify, subagent swarm for remaining ~77 tools, Compare Tools view + heatmap with side-effect filter.

- 2026-05-08 — **Compile blocker (NOT my work):** `:agent:compileKotlin` currently fails with 61 errors in `AiReviewTool.kt`, `ProjectContextTool.kt`, etc. — caused by user's separate unstaged change to `core/src/main/kotlin/com/workflow/orchestrator/core/services/ToolResult.kt` flipping `val data: T` → `val data: T?`. My doc-DSL code compiles syntactically clean and runs cleanly in the Playwright preview at `http://localhost:8765/` (served from `/tmp/tool-docs-preview/`).

- 2026-05-09 — **Compile blocker RESOLVED.** User landed `fce96d1df fix(core): make ToolResult.data nullable to reflect error state` along with all the call-site fixes in unrelated tools. `./gradlew :agent:compileKotlin --rerun-tasks` is now BUILD SUCCESSFUL — only pre-existing deprecation warnings remain. All 5 of my doc-initiative commits compile cleanly against current `main`'s descendant. Phase 3 onward is unblocked.

- 2026-05-09 — **Phase 3 (UI wiring) DONE.** Build green — `:agent:compileKotlin` SUCCESSFUL, `cd agent/webview && npm run build` SUCCESSFUL. Files added/modified (uncommitted, on `fix/automation-handover-quality-tabs`):
  - `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/docs/ToolDocumentation.kt` — added `@Serializable` to all data classes + enums (ToolDocumentation, SideEffectKind, AutoDerivedMetadata, ToolSummary, ActionDoc, ParamGroup, ParamDoc, RejectedParam, FailureMode, ToolExample, Verdict, VerdictReason, VerdictSeverity, AuditNote, AuditKind, RelatedTool, Relationship). Appended `ToolDocPayload` wire-format class flattening doc + metadata + narrative.
  - `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/docs/ToolDocPayloadBuilder.kt` — NEW. `build(toolName, registry)` composes `ToolDocPayload` from runtime state. Computes `AutoDerivedMetadata` (tier from `ToolRegistry.getActiveTools()` ∪ `getDeferredCatalog()`, schemaTokenCost via `Json.encodeToString(ToolDefinition)` + `estimateTokens`, planModeBlocked from `AgentLoop.WRITE_TOOLS`, approvalPolicy from `ApprovalPolicy.forTool`, timeoutClass from `tool.timeoutMs`, outputCap from `tool.outputConfig.maxChars`). Loads narrative MD via `NarrativeLoader`. `buildJson()` convenience encodes via `Json { encodeDefaults = true }`.
  - `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/tooldocs/ToolDocsEditor.kt` — NEW. `ToolDocsFileType` + `ToolDocsVirtualFile(project, toolName)` + `ToolDocsEditor` (JCEF) + `ToolDocsEditorProvider`. Loads `tool-docs.html` via `WorkflowAgentSchemeRegistrar.ensureRegistered()`. Polls for `window.renderToolDoc` to be registered (50ms × 60 attempts) then injects payload + theme vars. Per-tool `equals/hashCode` so `FileEditorManager.openFile` deduplicates by `(project, toolName)`. Static helper `ToolDocsEditor.open(project, toolName)`.
  - `src/main/resources/META-INF/plugin.xml` — added `<fileEditorProvider implementation="...ToolDocsEditorProvider"/>` after the existing ToolTestingEditorProvider line.
  - `agent/webview/tool-docs.html` — NEW entry HTML. Loads `/src/tool-docs.tsx` as ES module.
  - `agent/webview/src/tool-docs.tsx` — NEW. Mounts `<ToolDocView>`, registers `window.applyTheme` and `window.renderToolDoc` globals, handles error payload shape `{toolName, error}` from Kotlin's fallback path.
  - `agent/webview/src/components/tool-docs/ToolDocView.tsx` — NEW. Faithfully ports the static preview at `/tmp/tool-docs-preview/` minus the cross-tool sidebar (the IDE editor tab strip replaces it). Renders: header (tier/sideEffect/actions badges + summary tabs technical|plain), capability strip, what-LLM-sees, mermaid flowchart (via npm-bundled mermaid), single-action-params OR per-action collapsible cards, tool-level verdict + counterfactual, common LLM mistakes, audit notes, related tools, downsides, narrative (react-markdown + remark-gfm). Mermaid renders client-side via `mermaid.render()` in useEffect; theme variables drop through CSS vars set by Kotlin.
  - `agent/webview/vite.config.ts` — added `'tool-docs': resolve(__dirname, 'tool-docs.html')` to rollup inputs.
  - `agent/src/main/kotlin/com/workflow/orchestrator/agent/testing/ToolTestingPanel.kt` — three access paths to docs: (1) ⓘ glyph appended to row HTML when `tool.documentation() != null`, (2) double-click anywhere on a row opens `ToolDocsEditor`, (3) "View Documentation" button next to Execute/Clear/Copy in middle pane. Imports `ToolDocsEditor`.

  Build artifacts: `agent/src/main/resources/webview/dist/tool-docs.html` + `assets/tool-docs-9cw1qcQJ.js` (22.63 kB / gzip 5.61 kB).

- 2026-05-09 — **Phase 3 NEXT STEPS for user/next session:** (1) Run the IDE locally via `./gradlew runIde`, open Tools → Agent Tool Testing, select `read_file`, click "View Documentation" — verify the JCEF tab renders the full preview layout. Repeat with `debug_step` to verify multi-action rendering. (2) Phase 4 verification with Playwright against the static preview is moot now that Kotlin → JCEF path is live; Playwright should target the rendered IDE flow if used at all. (3) Commit current uncommitted work as 4 separate commits for clean history: `feat(agent): tool-docs serializable schema + payload builder`, `feat(agent): tool-docs JCEF FileEditor`, `feat(agent): tool-docs React UI + webview entry`, `feat(agent): wire tool-docs (i) button + double-click on ToolTestingPanel`. (4) Phase 5 swarm (~77 tools) is still gated on user review of the rendered pilots.

- 2026-05-09 — **Top-level menu entry + Phase 5 prompt template DONE.** Two more uncommitted bits added on `fix/automation-handover-quality-tabs`:
  - `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/tooldocs/OpenToolDocsAction.kt` — NEW `Tools → View Tool Documentation` action. Pops a JBPopup chooser of tools that have `documentation() != null` (filtered, Core-first, alphabetical, with speed-search), opens `ToolDocsEditor` on pick. Direct two-click access from menu bar.
  - `src/main/resources/META-INF/plugin.xml` — registered `Workflow.ViewToolDocumentation` action under ToolsMenu after `Workflow.OpenToolTesting`.
  - `docs/plans/2026-05-09-tool-docs-swarm-prompt.md` — NEW. Self-contained subagent prompt template for Phase 5 (one tool per subagent, 3 in parallel). Includes required reading list, quality bar table per field, per-action quality bar for meta-tools, style notes lifted from pilots, verification + commit format. Out-of-scope guards prevent subagents from touching schema or runtime behavior. Includes ~77-tool inventory grouped by package for batch dispatch.
  - Suggested commit split bumps to 5: prior 4 + `feat(agent): top-level Tools → View Tool Documentation entry + Phase 5 prompt template`.

- 2026-05-09/10 — **Phase 5 swarm IN PROGRESS — paused at 54/79 tools (~68% complete).** All 5 commit splits + 19 swarm batches landed on `fix/automation-handover-quality-tabs`. Append-only findings log at `docs/research/2026-05-09-tool-docs-swarm-findings.md` (committed each batch).

  **Batch dispatch pattern that works (locked in):**
  - **`subagent_type: general-purpose`, `model: sonnet`** (per `feedback_sonnet_for_small_tasks.md` — well-planned mechanical execution).
  - **3 in parallel max** per the locked Phase 5 decision.
  - **Race mitigation in dispatch prompt:** `sleep $((RANDOM % 3 + 1))` before `git commit` + post-commit `git log -1 --stat -- <file>` check. Rate dropped from 1-in-6 batches to 0-in-12 after this language landed.
  - **Per-batch wall time:** ~5-7 min with sonnet. Throughput: ~30 tools/hr.

  **Tools documented (54 of 79):** `read_file` + `debug_step` (pilots). Then 19 swarm batches: think, current_time, attempt_completion (B1) | glob_files, find_definition, tool_search (B2) | edit_file, create_file, revert_file (B3) | find_references, call_hierarchy, type_hierarchy (B4) | search_code, diagnostics, find_implementations (B5) | task_create, task_update, task_list (B6) | format_code, optimize_imports, task_get (B7) | plan_mode_respond, enable_plan_mode, use_skill (B8) | task_report, new_task, agent (B9) | ask_followup_question, render_artifact, background_process (B10) | run_command, runtime_exec, coverage (B11) | db_query, db_schema, db_list_databases (B12) | jira, db_explain, changelist_shelve (B13) | bitbucket_pr, bitbucket_repo, bitbucket_review (B14) | sonar, bamboo_builds, bamboo_plans (B15) | spring, build, project_structure (B16) | django, fastapi, flask (B17) | debug_inspect, debug_breakpoints, send_stdin (B18) | file_structure, type_inference, dataflow_analysis (B19).

  **Tools REMAINING (25 of 79):** get_method_body, get_annotations, test_finder (Batch 20 was dispatched but interrupted — these PSI tools may be partially modified in the working tree, NOT committed); structural_search, read_write_access, refactor_rename (FILE_WRITE), run_inspections, problem_view, list_quickfixes, ask_user_input, project_context, runtime_config (4 actions), java_runtime_exec (3 actions), python_runtime_exec (2 actions), db_list_profiles, db_stats. Plus a long tail of small/utility tools — re-derive exact list from `ToolRegistry` at next dispatch.

  **Findings log highlights (`docs/research/2026-05-09-tool-docs-swarm-findings.md`):**
  - **9 real bugs surfaced** by the swarm (none would have been caught by tests):
    1. `find_definition` Java/kotlin hardcoded fallback breaks pure-Python projects
    2. `find_references` same bug pattern at `:151-155`
    3. `revert_file` bypasses PathValidator entirely (security regression)
    4. `revert_file` no VFS/Document refresh after git checkout
    5. `create_file` charset asymmetry (VFS uses VirtualFile.charset, fallback hardcodes UTF_8)
    6. `create_file` `overwrite=true` shows misleading approval diff
    7. `project_structure` 8 write actions NOT in `AgentLoop.WRITE_TOOLS` (plan-mode bypassable)
    8. `flask.models` / `flask.forms` filename hardcoding (silent empty results for split-models projects)
    9. `debug_breakpoints.remove_breakpoint` cannot remove exception breakpoints (silent "No breakpoint found")
  - **Plus 3 sharp-edge findings:** `drop_frame` rewinds PC only (state NOT undone); `db_query` prefix-only check lets `WITH ... INSERT` slip the first gate (caught by layered defenses); `send_stdin.isLikelyPasswordPrompt` keyword-heuristic false-positives.
  - **6+ concrete drop / merge candidates:** `format_code`+`optimize_imports` → `transform(kind:imports|format|both)` (one-line dispatch difference); `task_list` is partially redundant with `EnvironmentDetailsBuilder.appendTasks`; `send_stdin` ↔ `background_process(action=send_stdin)`; `db_list_databases` ↔ `db_schema` (level-0 hierarchy); `bitbucket_pr` action drops (`get_blocker_comment_count`, `get_required_builds`, `update_pr_title`); `jira` action drops (`get_worklogs`, `get_board_issues`, `get_dev_branches`+`get_linked_prs`); `spring.scheduled_tasks`+`event_listeners` (convenience wrappers around `annotated_methods`); `bamboo_plans.get_build_variables` belongs in `bamboo_builds`; `build`'s pip/Poetry/uv triples → unified `python_*(manager)`.
  - **🚨 CLAUDE.md drift count: 10 instances.** Major undercounts: `build` 26 actions (CLAUDE.md said 11), `sonar` 18 (said 13), `bitbucket_review` 12 (said 6), `bitbucket_pr` 19 (said 18), `bamboo_plans` 10 (said 8). Also: `agent` (SpawnAgentTool) doesn't have the 5-actions API CLAUDE.md describes; `edit_file.lastEditLineRanges` doesn't exist; missing BLOCKED status; debug_breakpoints KDoc still mentions removed `start_session`. **CLAUDE.md audit is a real backlog item.**
  - **Doc length records:** `bitbucket_pr` 982 lines + 176 narrative; `sonar` 1124 lines; `build` 933 lines; `bitbucket_review` 966 lines; `agent` (SpawnAgentTool) 406 lines + 194 narrative.

  **PSI bug audit COMPLETE** (Batch 19): 8 PSI tools surveyed; bug isolated to `find_definition` + `find_references`. The other 6 (`call_hierarchy`, `type_hierarchy`, `find_implementations`, `file_structure`, `type_inference`, `dataflow_analysis`) all use the correct `registry.forFile(psiFile)` pattern. **Single PR can fix both bugged tools** — `find_implementations` is the canonical reference shape.

- 2026-05-10 — **PAUSED for break.** Resume by re-dispatching Batch 20 (`get_method_body`, `get_annotations`, `test_finder` — all PSI single-action; verify they don't have the find_definition fallback bug pattern). Working-tree may have stale modifications on those 3 PSI files from the interrupted Batch 20 attempt — verify with `git diff <file>` before re-dispatching; if non-empty and unwanted, `git checkout -- <file>` to reset, then re-dispatch fresh. Subsequent batches: structural_search/read_write_access/refactor_rename; run_inspections/problem_view/list_quickfixes; runtime_config/java_runtime_exec/python_runtime_exec; ask_user_input/project_context/db_list_profiles+db_stats. ~25 tools / ~9 batches / ~1 hour wall time remaining.

- 2026-05-11 — **🎉 PHASE 5 SWARM CLOSE-OUT — 80/80 tools documented (100% coverage).** All `documentation()` blocks landed on `fix/automation-handover-quality-tabs`. Batches 20-26 ran through Sonnet model; race rate held at 2/26 with sleep mitigation. Full findings live at `docs/research/2026-05-09-tool-docs-swarm-findings.md` (Batch-by-batch + close-out summary section at the bottom).
  
  **What's queued for next sessions:**
  1. **Phase 6 verification swarm** — dispatch read-only subagents to cross-check each `documentation()` block against source. Per-tool verifier flags claims that don't match source.
  2. **Bug-fix PR (13 real defects).** Two CRITICAL: `revert_file` bypasses PathValidator (security regression), `create_file` charset asymmetry (non-UTF-8 projects write different bytes). Plus: `find_definition`/`find_references` Java/kotlin fallback, `project_structure`/`runtime_config` write-actions-bypass-WRITE_TOOLS, `flask.models`/`forms` filename hardcoding, `debug_breakpoints.remove_breakpoint` exception-bp silent failure, `debug_inspect.drop_frame` PC-only rewind footgun, `structural_search` Python misroute + JavaKotlinProvider Kotlin file-type hardwire.
  3. **Cleanup PR (10+ drop/merge candidates).** Highest-value: `format_code` + `optimize_imports` + `refactor_rename` → `refactor(kind)` 3-way merge (~80% shared scaffold); `task_list` fold-into-EnvironmentDetailsBuilder; `send_stdin`↔`background_process` merge; `db_list_databases`↔`db_schema` level-0; jira drops (get_worklogs, get_board_issues, etc.); bitbucket_pr drops; spring convenience-wrapper drops; bamboo_plans.get_build_variables relocation.
  4. **CLAUDE.md audit sweep** — 10 documented drift instances. Largest: `build` is 26 actions vs CLAUDE.md's 11 (Python ecosystem additions never doc-updated). Also: agent tool's described 5-action API doesn't exist, edit_file.lastEditLineRanges doesn't exist, missing BLOCKED task status, debug_breakpoints KDoc still mentions removed start_session.
  5. **Compare Tools view** — JSON aggregate consumption for cross-tool heatmap + drop-candidate page (originally Phase 7 of this initiative).
  
  **Total swarm output:** ~17K lines of `documentation()` + ~2.5K lines of narrative MD + ~110 commits across 3 sessions.

## Next-session resumption checklist

1. Read this memory file first.
2. Confirm pilot foundation is committed (or commit it).
3. Continue Phase 3 — UI wiring. Recommended order:
   - `(i)` icon column added to `ToolTestingPanel.kt` (existing Swing panel) + click handler that opens `ToolDocsVirtualFile`.
   - New JCEF-based FileEditor following the `AgentChatEditorTab` / `AgentVisualizationTab` pattern at `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/`. Register in `src/main/resources/META-INF/plugin.xml` (~line 408 area).
   - JCEF browser loads HTML that bootstraps a React app from `agent/src/main/resources/webview/dist/` — extend the existing webview bundle with a new entry point (`tool-docs.html` + `tool-docs.tsx`) or render in-place if a separate bundle is overkill.
   - Bridge: a single JCEF query named `_loadToolDoc(toolName)` returning the JSON-serialized `ToolDocumentation` for a tool. JSON serialization should use `kotlinx.serialization` with `@Serializable` annotations on the data classes (currently plain `data class` — add the annotations).
4. After UI is working, dispatch subagent swarm (3-parallel) for remaining ~77 tools, each subagent reading the existing tool source and emitting `documentation()` blocks following the `read_file` / `debug_step` pattern. Each subagent gets one tool, must compile its work, must commit on the same branch.
5. Verify swarm output: dispatch verification subagents to cross-check each doc against the source code. Errors get reported back with proof.
6. Build the cross-tool Compare view using the JSON aggregate.

## Key reference paths (to avoid re-recon)
- ToolRegistry: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ToolRegistry.kt`
- AgentTool interface: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/AgentTool.kt` — `documentation()` is at line ~96
- Existing tool testing harness (Swing): `agent/src/main/kotlin/com/workflow/orchestrator/agent/testing/ToolTestingPanel.kt`
- FileEditor pattern reference: `agent/src/main/kotlin/com/workflow/orchestrator/agent/testing/ToolTestingEditorTab.kt` (Swing) and `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentChatEditorTab.kt` (JCEF)
- Plugin.xml registrations: `src/main/resources/META-INF/plugin.xml` line ~408 (fileEditorProvider) + ~467 (OpenToolTestingAction)
- Kotlinx-serialization-json is already on the classpath via existing tools — `kotlinx.serialization.json.JsonObject` is imported throughout

## Why
User wants to audit/drop unused tools. With 79 tools + 150+ actions, this is now a real maintenance concern. Documentation must live next to the code (so it doesn't rot), surface well (visual + searchable), and include explicit keep/drop verdicts at tool *and* action level.

## How to apply
When resuming this work in a future session, read this memory first; pick up at the current Phase. Update this file's "Status updates" with each meaningful checkpoint and bump phase when transitioning.
