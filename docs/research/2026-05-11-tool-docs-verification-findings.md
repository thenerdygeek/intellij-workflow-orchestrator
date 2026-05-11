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

(Batches will be appended below as subagents complete and the dispatcher aggregates results.)
