# Strict Prompt-Coverage Audit — Diff vs. main

**Method:** line-by-line `git diff main..HEAD` against every changed tool file under
`agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/`, cross-checked against
`ToolPromptBuilder`'s actual reads. The earlier audit (2026-05-07-agent-prompt-coverage-audit.md)
made a structural claim ("the builder is reflective"); this audit makes a textual claim
("the metadata IS populated").

## ToolPromptBuilder — what it actually reads

`/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/core/src/main/kotlin/com/workflow/orchestrator/core/ai/ToolPromptBuilder.kt`

| Slot | Line | Field read |
|---|---|---|
| Tool name | 19 | `fn.name` (header `## ${fn.name}`) |
| Tool description | 20 | `fn.description` (escaped) |
| Param name + required flag | 27–28 | `name in requiredSet`, `name`, `prop.description` |
| XML usage stub | 33–35 | `fn.name`, `name` only |

Crucially — **`enumValues` on `ParameterProperty` is NOT read by `ToolPromptBuilder`.**
The DTO defines `@SerialName("enum") val enumValues: List<String>?` at
`core/src/main/kotlin/com/workflow/orchestrator/core/ai/dto/ToolCallModels.kt:30`,
but the builder loops only over `prop.description`. So for the LLM to learn about a
new `action` enum value, **the action must appear in either the tool's main
`description` text or in the `action` parameter's `description` text**. `enumValues`
is dead-loaded into the system prompt. (It is presumably honored elsewhere — likely
client-side argument validation — but not for prompt advertisement.)

Implication: the failure mode "added to dispatch + `enumValues` but not to description"
would still be invisible to the LLM. The verification below therefore checks
**description text** as the load-bearing slot, with `enumValues` as a secondary
consistency check.

## Files changed under `agent/src/main/kotlin/.../tools/`

```
SpringTool.kt             | text-only branch on description (no schema change)
BambooBuildsTool.kt       | +1 param, no new actions
BambooPlansTool.kt        | +1 action (get_projects), +3 params (auto_detect_plan)
BitbucketPrTool.kt        | +5 actions, +1 param
BitbucketRepoTool.kt      | +2 actions, no new params (commit_id pre-existed)
JiraTool.kt               | +3 params, no new actions
SonarTool.kt              | +5 actions, +4 params
CoverageTool.kt           | reflection refactor (no schema change)
JavaRuntimeExecTool.kt    | reflection refactor (no schema change)
```

`agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/` — **no changes.**
`agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ToolRegistry.kt` — **no changes.**
`agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt` registration block — **no new tools.**

So Check D (registry) is vacuously satisfied: no new tool files.

---

## Per-tool verification

### JiraTool — `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/JiraTool.kt`

| Action / Param | Src | Dispatch | enumValues (n/a — not read) | ParameterProperty | Description text | Verdict |
|---|---|---|---|---|---|---|
| `include_remote_links` (param) | new | n/a (read at line 204) | n/a | line 167-170 | top desc line 42 + param desc line 168-169 | OK |
| `include_history` (param) | new | n/a (read at line 205) | n/a | line 171-174 | top desc line 42 + param desc line 172-173 | OK |
| `include_permissions` (param) | new | n/a (read at line 206) | n/a | line 175-178 | top desc line 42 + param desc line 176-177 | OK |

No new actions. Param schema and description both updated.

### BambooBuildsTool — `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BambooBuildsTool.kt`

| Action / Param | Src | Dispatch | enumValues | ParameterProperty | Description text | Verdict |
|---|---|---|---|---|---|---|
| `include_commits` (param) | new | read line 136 | n/a | line 100-103 | top desc line 39 (`get_build(build_key, include_commits?)`) + param desc | OK |

No new actions.

### BambooPlansTool — `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BambooPlansTool.kt`

| Action / Param | Src | Dispatch | enumValues | ParameterProperty | Description text | Verdict |
|---|---|---|---|---|---|---|
| `get_projects` (action) | new | line 159-161 | line 63 | n/a | top desc line 40 (`get_projects() → List all Bamboo projects`) | OK |
| `repo_root` (param) | new | line 229 | n/a | line 104-107 | top desc line 49-51 + param desc | OK |
| `branch_name` (param) | new | line 230 | n/a | line 108-111 | param desc | OK |
| `preferred_master` (param) | new | line 231 | n/a | line 112-115 | param desc | OK |

### BitbucketPrTool — `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BitbucketPrTool.kt`

| Action / Param | Src | Dispatch | enumValues | ParameterProperty | Description text | Verdict |
|---|---|---|---|---|---|---|
| `get_pr_participants` (action) | new | line 211-215 | line 67 | n/a | top desc line 50 | OK |
| `get_blocker_comment_count` (action) | new | line 217-221 | line 67 | n/a | top desc line 51 | OK |
| `get_linked_jira_issues` (action) | new | line 223-227 | line 68 | n/a | top desc line 52 | OK |
| `get_required_builds` (action) | new | line 229-232 | line 68 | n/a | top desc line 53 | OK |
| `get_prs_for_branch` (action) | new | line 234-239 | line 69 | n/a | top desc line 54 | OK |
| `branch_name` (param) | new | line 236 | n/a | line 82 | param desc | OK |

### BitbucketRepoTool — `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BitbucketRepoTool.kt`

| Action / Param | Src | Dispatch | enumValues | ParameterProperty | Description text | Verdict |
|---|---|---|---|---|---|---|
| `get_commit_build_stats` (action) | new | line 122-126 | line 47 | n/a | top desc line 34 | OK |
| `get_commit_pull_requests` (action) | new | line 128-133 | line 47 | n/a | top desc line 35 | OK |

`commit_id` parameter was already declared on `main`. Reused, no metadata gap.

### SonarTool — `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/SonarTool.kt` (read-only inspection)

| Action / Param | Src | Dispatch | enumValues | ParameterProperty | Description text | Verdict |
|---|---|---|---|---|---|---|
| `hotspot_detail` (action) | new (ac0e070e) | line 255-260 | line 80 | n/a | top desc line 64 | OK |
| `issue_facets` (action) | new (ac0e070e) | line 262-271 | line 80 | n/a | top desc line 65 | OK |
| `current_user` (action) | new (ac0e070e) | line 273 | line 81 | n/a | top desc line 66 | OK |
| `quality_gates_list` (action) | new (ac0e070e) | line 275 | line 81 | n/a | top desc line 67 | OK |
| `rule` (action) | new (0159bcfd) | line 294-297 | line 82 | n/a | top desc line 71 | OK |
| `hotspot_key` (param) | new | read in dispatch | n/a | line 132-135 | top desc line 64 + param desc | OK |
| `facets` (param) | new | read in dispatch | n/a | line 136-139 | top desc line 65 + param desc | OK |
| `rule_key` (param) | new | read in dispatch (executeRuleForTest) | n/a | line 144-147 | param desc | OK |
| `include_files` (param) | new | line 218 | n/a | line 148-151 | top desc line 56 (`branches(project_key, include_files?)`) + param desc | OK |

### SpringTool / CoverageTool / JavaRuntimeExecTool

No new actions or parameters. `SpringTool.kt` only flips one description-text branch
based on `includeEndpointActions` (already-existing constructor flag). `CoverageTool.kt`
and `JavaRuntimeExecTool.kt` are reflection refactors only (PATTERNS field → setPatterns
setter); no schema impact.

---

## Per-commit verification

For each gap-fix commit on this branch, we cross-checked that every new dispatch arm and
every new `params["..."]` read has a same-commit metadata edit (description + ParameterProperty
where applicable).

| Commit | Tool | New action | New param | Same-commit metadata? |
|---|---|---|---|---|
| `2abc16c9` | JiraTool | — | `include_remote_links`, `include_history` | YES — both ParameterProperty entries + main description updated |
| `a9885383` | JiraTool | — | `include_permissions` | YES — ParameterProperty + main description |
| `d36006c4` | BambooBuildsTool | — | `include_commits` | YES — ParameterProperty + main description (`get_build(build_key, include_commits?)`) |
| `07a30761` | BitbucketPrTool | `get_prs_for_branch` | `branch_name` | YES — enumValues, top description, ParameterProperty all in same diff |
| `0159bcfd` | SonarTool | `rule` | `rule_key`, `include_files` | YES — enumValues, top description, ParameterProperty all in same diff |
| `5938ae67` | BambooPlansTool | `get_projects` | `repo_root`, `branch_name`, `preferred_master` | YES — enumValues, top description, ParameterProperty all in same diff |
| `508509dd` | (3 tools) | — | — | code-review polish; no schema change |

## Gaps

**Zero schema gaps.** Every new action has a matching `enumValues` entry **and** a top-description
listing. Every new `params["x"]` read has a matching `ParameterProperty` declaration. The
dispatch/metadata ratchet held across all seven session commits.

## Confirmed-clean items

- `JiraTool.get_ticket` 3 new include_* flags — fully wired
- `BambooBuildsTool.get_build` `include_commits` — fully wired
- `BambooPlansTool.get_projects` action + 3 `auto_detect_plan` overload params — fully wired
- `BitbucketPrTool` 5 new actions + `branch_name` param — fully wired
- `BitbucketRepoTool` 2 new commit-centric actions — fully wired
- `SonarTool` 5 new actions + 4 new params — fully wired
- `SpringTool` description branch — no schema change
- `CoverageTool` / `JavaRuntimeExecTool` — reflection refactor, no schema change
- `ide/` — untouched
- `ToolRegistry.kt` and `AgentService.registerAllTools()` registration — untouched (no new tool files)

## Verdict

**0 gaps found.** The earlier audit's claim ("prompt is current") is **correct** — and now
also **line-by-line verified** against the actual diff.

A nuance worth flagging: the earlier audit asserted that `enumValues` propagation to the
prompt was "automatic" via reflection. That was misleading. `ToolPromptBuilder` does NOT
read `enumValues` at all — only descriptions. So the *real* propagation channel for new
actions is the human-authored top-of-tool description string. This branch happens to have
disciplined updates of that description in every relevant commit, so the outcome is fine.
But the structural claim was wrong; the audit's verdict was right by coincidence of good
authoring discipline.

**Recommended action:** none for this branch. For future-proofing, consider one of:
1. Have `ToolPromptBuilder` actually emit `enumValues` (e.g. ` (enum: a, b, c)` after each
   parameter description) — then the structural claim would be true and forgetting to
   update the top description text would not silently hide actions from the LLM.
2. Add a unit test asserting that for every action declared in any tool's `enumValues`,
   that action string appears in the tool's `description`.

## Open questions

- None. The diff fully resolves the audit question.
