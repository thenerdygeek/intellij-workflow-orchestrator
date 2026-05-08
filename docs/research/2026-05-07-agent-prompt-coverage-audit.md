# Agent Prompt Coverage Audit — 2026-05-07

Branch `fix/automation-handover-quality-tabs`, HEAD `508509dd`.
Read-only audit: did the recent agent-tool changes (Jira / Bitbucket /
Bamboo / Sonar audits + gap fixes) propagate into the system prompt the
LLM actually sees?

## How the prompt is built (verdict on the architecture question)

- **Mode:** **automatic / fully-reflective.** No hand-curated tool list
  exists in the prompt template.
- **Builder location:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/prompt/SystemPrompt.kt`
  (composer, `SystemPrompt.build()` at line 30) +
  `core/src/main/kotlin/com/workflow/orchestrator/core/ai/ToolPromptBuilder.kt`
  (per-tool renderer, `build(tools)` at line 13). The composer just emits
  Section 6c verbatim from whatever `toolDefinitionsMarkdown: String?` it
  receives (`SystemPrompt.kt:131-134`); the actual tool catalog comes from
  `ToolPromptBuilder.build()`, which loops over `List<ToolDefinition>` and
  prints each tool's `function.name`, `function.description`, and the
  `properties` map of its `FunctionParameters` schema.
- **Refresh trigger:** rebuilt **once per LLM call** by
  `AgentService.toolDefsMarkdownProvider` (`AgentService.kt:1715`) —
  `dynamic provider rebuilds system prompt when tool set changes`
  (per `agent/CLAUDE.md` "AgentService"). So any new action / parameter is
  picked up at the start of the next iteration.
- **Implication:** **adding an action to a tool's `enumValues` list +
  adding a `ParameterProperty` entry + updating the tool's `description`
  block automatically propagates into the prompt.** No separate prompt
  file to update. The risk is **code-level:** if a developer adds
  `when (action) { "new_action" -> … }` to the `execute()` body but
  forgets to update the tool's `description` text *and* `enumValues`,
  the LLM never learns the action exists. Section 6c is the single
  source of truth, but it is only as accurate as each tool's own
  `description`/`enumValues`/`ParameterProperty`.

## Snapshot

- Saved at: `docs/research/2026-05-07-agent-prompt-snapshot.md`
- Total token estimate: **~ 11,000 tokens** at session start with all
  four backends configured (~6,200 static + ~4,800 for ~30 core tool
  XML schemas + integration tools).
- The 11 sections (per `agent/CLAUDE.md` "System Prompt Structure"):
  Agent Role, Task Management, Editing Files, Act vs Plan Mode,
  Capabilities, Skills (optional), Deferred Tool Catalog (optional),
  **Tool Definitions (XML)**, Rules, System Info, Objective, Memory,
  User Instructions (optional). Section 6c "Tool Definitions" is the
  load-bearing one for this audit.

## Inventory of recent agent-tool changes

Range: `git log --since="2026-04-15" -- agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/`.
Source-session column: "this" = the audit-and-gap-fix work this session
landed on the branch; "earlier" = pre-audit work (e.g. Bitbucket review
comments expansion, dev-status, jira download_attachment); "parallel" =
authored from another Claude session (the parallel `:sonar` session per
`memory/project_sonar_parallel_session.md`).

| Commit | Tool | Action / Param | Type | Source session |
|---|---|---|---|---|
| `2abc16c9` | `JiraTool` | `include_remote_links`, `include_history` on `get_ticket` | new flags | this |
| `a9885383` | `JiraTool` | `include_permissions` on `get_ticket` | new flag | this |
| `d36006c4` | `BambooBuildsTool` | `include_commits` on `get_build` | new flag | this |
| `07a30761` | `BitbucketPrTool` | `get_prs_for_branch` | new action | this |
| `0159bcfd` | `SonarTool` | `rule` action; `include_files` on `branches` | new action + flag | this |
| `5938ae67` | `BambooPlansTool` | `get_projects` action; `repo_root`, `branch_name`, `preferred_master` on `auto_detect_plan` | new action + 3 new params | this |
| `508509dd` | (no agent surface change) | description polish only | hardening | this |
| `b9ed7cbe` | `BitbucketPrTool`, `BitbucketRepoTool` | `get_pr_participants`, `get_blocker_comment_count`, `get_linked_jira_issues`, `get_required_builds` (PR); `get_commit_build_stats`, `get_commit_pull_requests` (Repo) | 4+2 new actions | earlier (same branch) |
| `4cb723a3` | `BambooBuildsTool` | `get_build_log` description note about job-level resultKey | description widen (no schema change) | earlier |
| `a68e7f29` | `JiraTool` | `download_attachment` auto-loads images into AttachmentStore | behavior (no schema) | earlier |
| `b99587df` | `JiraTool` | folded `get_dev_status` into `get_ticket` via `include_dev_status` | refactor (replaced earlier action) | earlier |
| `db78884f` | `JiraTool` | `get_dev_status` (now removed by `b99587df`) | new action then deleted | earlier |
| `99d5f7c6` | `BitbucketReviewTool` | `resolve_comment`, `reopen_comment` | 2 new actions | earlier |
| `9a3bdb92` | `BitbucketReviewTool` | `delete_comment` | new action | earlier |
| `e3a3b234` | `BitbucketReviewTool` | `edit_comment` (with `STALE_VERSION` surface) | new action | earlier |
| `948f0983` | `BitbucketReviewTool` | `get_comment` | new action | earlier |
| `390e4c63` | `BitbucketReviewTool` | `list_comments` (with `only_open?`, `only_inline?`) | new action + 2 params | earlier |
| `22ea0928` | `BitbucketPrTool` | `merge_pr` `strategy` description fix (DC-valid ids) | description fix (no schema) | earlier |
| `5570fe59` | `JiraTool` | `transition.fields` + `MissingFields` retry-pattern docs | new param + behavior | earlier |
| `ac0e070e` | `SonarTool` | adopt 25.x endpoints + impacts/cleanCodeAttribute guidance | description widen (no schema add) | parallel `:sonar` session |
| `ccea3752` | `SonarTool` | Clean Code taxonomy on issues | description widen | parallel `:sonar` session |
| `a3be4144` | `SonarTool` | `local_analysis` 0.83.13-beta — protected-branch redirect, multi-module keys | description widen + behavior | parallel `:sonar` session |
| **AUDIT-NO-OP** | — | `56d0dd0` (Jira API audit), `59c9ea8d` (Bamboo audit) | service-only, **no agent-surface change** in the same commit | this |

Note on the two audit commits the prompt explicitly called out:

- **`56d0dd0` (Jira "feat(jira): unify HTTP funnel and adopt validated endpoints")** — `git show --name-only` shows it touched `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/MentionSearchProvider.kt` only. Zero changes to `agent/.../tools/integration/JiraTool.kt`. The R-ADD features (R-ADD-1 permissions, R-ADD-2 fields discovery, etc.) landed in `:core` services + UI; agent surfacing came later via the dedicated gap-fix commits `2abc16c9` / `a9885383`.
- **`59c9ea8d` (Bamboo "adopt validated 10.2 endpoints")** — touched zero `agent/` files. The `getBuildChanges` service method existed from this commit, but the agent had no path to it until `d36006c4` added the `include_commits` flag.

## Cross-reference: prompt coverage

For each change, "in prompt?" means: does the **rendered Section 6c**
(per `ToolPromptBuilder`) actually expose the change to the LLM? Since
the renderer is fully reflective, the answer reduces to: was the tool's
`description` / `enumValues` / `parameters.properties` updated in the
same commit?

| Change | In prompt? | Evidence | Action needed |
|---|---|---|---|
| `jira.get_ticket include_remote_links` | yes | `JiraTool.kt:42` description lists it; `:167-170` ParameterProperty | none |
| `jira.get_ticket include_history` | yes | `JiraTool.kt:42` description; `:171-174` ParameterProperty | none |
| `jira.get_ticket include_permissions` | yes | `JiraTool.kt:42` description; `:175-178` ParameterProperty | none |
| `bamboo_builds.get_build include_commits` | yes | `BambooBuildsTool.kt:39` description; `:101-104` ParameterProperty | none |
| `bitbucket_pr.get_prs_for_branch` | yes | `BitbucketPrTool.kt:54,69-70` (description + enumValues); `:82` `branch_name` ParameterProperty | none |
| `sonar.rule` (new action) | yes | `SonarTool.kt:55,81` (description + enumValues); `:144-147` `rule_key` ParameterProperty | none |
| `sonar.branches include_files` | yes | `SonarTool.kt:54` description; `:148-151` ParameterProperty; `:102` `branch` description cross-references the interaction | none |
| `bamboo_plans.get_projects` | yes | `BambooPlansTool.kt:40,63` (description + enumValues) | none |
| `bamboo_plans.auto_detect_plan repo_root / branch_name / preferred_master` | yes | `BambooPlansTool.kt:49-52` (description) and `:104-115` (3 ParameterProperty entries) | none |
| Bitbucket DC `bitbucket_pr.get_pr_participants` | yes | `BitbucketPrTool.kt:50,67` | none |
| Bitbucket DC `bitbucket_pr.get_blocker_comment_count` | yes | `BitbucketPrTool.kt:51,67` | none |
| Bitbucket DC `bitbucket_pr.get_linked_jira_issues` | yes | `BitbucketPrTool.kt:52,68` | none |
| Bitbucket DC `bitbucket_pr.get_required_builds` | yes | `BitbucketPrTool.kt:53,68` | none |
| Bitbucket DC `bitbucket_repo.get_commit_build_stats` | yes | `BitbucketRepoTool.kt:34,47` | none |
| Bitbucket DC `bitbucket_repo.get_commit_pull_requests` | yes | `BitbucketRepoTool.kt:35,47` | none |
| BitbucketReview `list_comments` / `get_comment` / `edit_comment` / `delete_comment` / `resolve_comment` / `reopen_comment` | yes | `BitbucketReviewTool.kt:37-42` (description) + matching `enumValues` block in the same file | none |
| Sonar `local_analysis` 25.x notes (protected-branch redirect, multi-module) | yes | `SonarTool.kt:66` description; `branch` cross-reference at `:102` | none |
| Sonar `issues` impacts / cleanCodeAttribute / cleanCodeAttributeCategory guidance | yes | `SonarTool.kt:49` description | none |
| `bamboo_builds.get_build_log` job-level resultKey acceptance | yes | `BambooBuildsTool.kt:41` description widens to mention job-level keys | none |
| `jira.transition fields` + MissingFields pattern | yes | `JiraTool.kt:44-53` description block + `:93-101` `fields` ParameterProperty + Section 7 Rules block at `SystemPrompt.kt:673-685` | none |

## Gaps (items not advertised in prompt)

**Zero schema-level gaps.** Every audit-driven addition (new tools, new
actions, new params, new flags) since 2026-04-15 is reflected in its
tool's `description` + `enumValues` + `ParameterProperty`. Because
`ToolPromptBuilder` is fully reflective, that means every addition is
in the prompt.

Two minor cosmetic findings (not gaps in advertising — the LLM still
sees the right thing — but worth noting):

1. **`BitbucketRepoTool` class KDoc says "6 actions"; schema lists 8.**
   `agent/.../BitbucketRepoTool.kt:18` ("6 actions: get_branches, …,
   list_repos"). The `description` field (which the LLM reads) was
   updated at `b9ed7cbe` to include all 8. The class KDoc above the
   class declaration was not updated. Affects readability of the source
   file only — does **not** affect the prompt.

2. **`BambooPlansTool` class KDoc says "10 plan-oriented actions";
   `enumValues` lists 10**, but the description block (which the LLM
   reads) calls out only 10 also. Verified consistent. False alarm —
   noted here only because the audit commit message at `5938ae67` says
   "8 actions" and the class KDoc says "10". The actual enum has 10
   members, the description has 10. Cosmetic stale comment only.

## What's safe (items correctly advertised)

- All 4 newly-added gap-fix actions/flags (Jira, Bamboo Builds, Bitbucket
  PR, Sonar) — these were specifically called out in the audit prompt
  and all check out.
- All 6 Bitbucket DC adoption actions — checked individually against
  the Bitbucket request memo (`b9ed7cbe`).
- All 6 BitbucketReview comment-lifecycle additions — checked.
- All 5 Bamboo Plans `auto_detect_plan` overload params + the new
  `get_projects` action.
- The 17-action Jira surface (after `b99587df`'s consolidation of
  `get_dev_status` into the `include_dev_status` flag).
- The Sonar 25.x descriptive widenings from the parallel session
  (`ac0e070e`, `ccea3752`, `a3be4144`) — these update the description
  text only, no schema additions, and the description is what the LLM
  reads.

## Recommended actions

**Architecture is automatic — there is no prompt file to update.** No
follow-up commits are required. The "advertising gap" hypothesis the
prompt was concerned about (LLM unaware of newly-wired tools) is **not
present** for any of the 2026-04-15 → 2026-05-07 changes inspected.

If anything is on the to-do list, it is small and developer-hygiene only:

1. **Optional cosmetic cleanup (low priority).** Update the class-level
   KDoc on `BitbucketRepoTool` (line 18) from "6 actions" to "8 actions"
   to match the schema. Not visible to the LLM. ~1 line edit.
2. **Optional safety net (medium priority).** Add a unit test that
   parses each integration tool's `description` block, extracts every
   `name(args)` line, and asserts every name appears in the tool's
   `enumValues` list and vice versa. Today this contract is enforced
   by code review only — a developer who forgets to update the
   description after adding a `when (action) { "new" -> … }` branch
   would not see a CI failure, only a silent loss of LLM
   discoverability. The tests I'd write live alongside `JiraToolGetTicketTest.kt`,
   `BambooBuildsToolTest.kt`, `BitbucketPrToolTest.kt`, `BambooPlansToolTest.kt`,
   `SonarToolTest.kt` — each gets one new
   `actions in description match enumValues` test, ~30 lines per tool.
3. **Snapshot extension (medium priority).** The existing
   `SystemPromptIdeContextTest` snapshots only Sections 1–11 minus
   Section 6c (because 6c needs a runtime tool list). Consider adding
   a separate `IntegrationToolPromptSnapshotTest` that wires up the
   integration tools' `definition()` outputs through `ToolPromptBuilder.build()`
   and pins the rendered XML — this would catch regressions in the
   integration tools' description fields the same way the existing
   snapshots catch regressions in the static body. ~80 lines + 7 golden
   files.

None of the above are blockers. The audit's core question — "does the
LLM actually know these tools exist?" — has a clear yes for every
change in the inventory.

## Open questions

- **None of architectural significance.** The reflective design means
  the question reduces to "is the tool's own metadata up to date?",
  and the answer is yes for every change inspected.
- One thing I could not resolve from code-reading alone: whether the
  parallel `:sonar` session has any in-flight commits that haven't yet
  landed on this branch. The audit checks the commits **on the branch**
  (`fix/automation-handover-quality-tabs`); if the parallel session is
  preparing additional Sonar tool actions that aren't merged yet, those
  are by definition out of scope for this audit. Per the memory rule
  `project_sonar_parallel_session.md` I did not inspect the parallel
  session's working state.
