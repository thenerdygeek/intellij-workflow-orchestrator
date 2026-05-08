# Code Review — Agent Tool Gap Fixes

**Reviewer:** subagent (Opus, read-only)
**Scope:** 6 commits on `fix/automation-handover-quality-tabs` (`2abc16c9` → `5938ae67`)
**Date:** 2026-05-07

## TL;DR
- Spec compliance: **PASS WITH NOTES**
- Quality: **APPROVE WITH NITS**
- Critical issues: 0
- Important issues: 2
- Nits: 6

All 8 audit gaps closed with the EXTEND-vs-NEW-ACTION split exactly as the audit prescribed (5 extends + 3 new actions). Field names verified against the actual model classes in all 6 commits. The `include_dev_status` precedent was followed faithfully and even refactored cleanly to N-way fan-out by the first commit. Two important nits around the `projectKey` derivation in `include_permissions` and the `formatBuildCommits` "author" fallback ordering, both safe defaults but worth a follow-up. Tool-count discipline is fine — all three new actions are genuinely orthogonal.

## Per-commit review

### `2abc16c9` — jira `include_remote_links` + `include_history`
**Spec compliance:** PASS — closes gap #2; refactors the binary `if/else` to N-way fan-out cleanly (foundation for Task 5 and any future include flag).

**Strengths:**
- Faithful copy of the `include_dev_status` precedent: `?.jsonPrimitive?.content?.lowercase() == "true"`, `coroutineScope { async { … } }`, error short-circuit on `ticketResult.isError`, summaries joined with `· `, blocks joined with `\n\n`, `TokenEstimator.estimate(combined)`.
- Field names in `formatRemoteLinks` and `formatTicketHistory` cross-check against `core/model/jira/JiraExtensionModels.kt` — `RemoteLinkData(applicationName/applicationType/title/url)` and `TicketHistoryEntry(actorDisplayName/createdAt/field/oldValue/newValue)` match exactly. The plan's guesses (`fromString`/`toString_`/`changes[]`) were correctly discarded.
- Test seam `executeGetTicketForTest` is `internal suspend`, isolated from production flow (the production path goes via `ServiceLookup.jira(project)` → same private formatters); no production caller exists.
- History formatter caps at 20 entries with explicit "(N more entries)" tail and "(none)" empty-list handling.
- 4 new tests cover: each flag in isolation, omitted-flag-no-fetch, multi-flag fan-out parallelism, all the `coVerify(exactly = 1)` invariants.

**Issues:**
- 🔵 NIT: `formatRemoteLinks` does not cap the list length — relies on the upstream API to be sane. `RemoteLinkData` lists tend to be small (5-10 typical), but a spec-conforming Confluence-heavy ticket could plausibly have 50+. Suggest mirroring the `take(20)` cap from `formatTicketHistory` for consistency. (`JiraTool.kt:744`)
- 🔵 NIT: `formatTicketHistory` line `"• ${entry.createdAt} · ${entry.author} · $change"` (line 757) — `entry.author` doesn't exist on `TicketHistoryEntry`; the implementer correctly uses `entry.actorDisplayName` so this is fine, just flagging the plan's example was wrong and the implementer caught it.

### `a9885383` — jira `include_permissions`
**Spec compliance:** PASS — gap #5 closed; threads cleanly through the Task-1 fan-out.

**Strengths:**
- Single-flag delta into the existing N-way structure: `includePermissions` added to `anyInclude`, deferred constructor + `await()?.let { … }` block follow the same pattern as the existing three feeds.
- `MyPermissionsData` field navigation correct (`permissions: Map<String, PermissionFlag>` with `key`/`name`/`havePermission`/`deprecated`) — formatter filters out deprecated flags, sorts by key, and renders `granted`/`denied` for boolean states. Matches the actual data class at `JiraExtensionModels.kt:11`.
- `executeGetTicketForTest` extended with a defaulted `includePermissions = false` parameter — backward-compatible with the Task-1 test calls.
- Empty-permissions case explicit: `if (perms.permissions.isEmpty()) return "Permissions: (none)"`.

**Issues:**
- 🟡 IMPORTANT: `projectKey = key.substringBefore("-")` is correct for normal `PROJ-1` keys but quietly mis-handles edge cases. `"PROJ"` (no dash) → `"PROJ"` (passed as projectKey, may yield surprising results since `getMyPermissions(projectKey="PROJ")` differs from global `getMyPermissions(projectKey=null)`); `"PROJ-1-2"` → `"PROJ"` (intended); empty string would yield `""`. Note that `ToolValidation.validateJiraKey(key)` runs first (line 202) and rejects malformed keys, so the no-dash case is unreachable in production. The test entry point `executeGetTicketForTest` does NOT call the validator — a test that passes `key = "PROJ"` would hit the misbehavior. Suggest hardening with `key.substringBefore("-").takeIf { it != key } ?: ""` or skipping the fan-out when no dash is present, OR replicating the Jira-key validation inside the test entry point. Pre-merge fix optional; post-merge hardening recommended.
- 🔵 NIT: Permission-key strings rendered verbatim (`TRANSITION_ISSUES`, `ADD_COMMENTS`, …). Fine, but the audit's example summary in the plan suggested `canTransition: true / canComment: true` style. The implementer's choice (raw key) is more truthful — the Jira REST API exposes ~40 keys, not 4 — but consider truncating to the 8-10 most useful keys for token economy on a verbose ticket. (`JiraTool.kt:753-761`)

### `d36006c4` — bamboo `include_commits`
**Spec compliance:** PASS — gap #1 (top-leverage) closed.

**Strengths:**
- Test seam `executeGetBuildForTest` is the production path (the production handler delegates to it). Only one code path, one bug surface.
- `BuildChangeData` field names verified at `core/model/bamboo/BambooModels.kt:157`: `userName`, `fullName`, `comment`, `changesetId`, `commitUrl`, `date`. The implementer correctly uses `c.changesetId.take(8)` (not the plan's guessed `c.sha`), `c.fullName.ifBlank { c.userName }.ifBlank { "—" }` for author display, and `c.comment.lineSequence().firstOrNull()` for the message first-line.
- Formatter caps at 50 commits with explicit "(N more commits)" tail and "(none)" empty-list handling.
- 3 tests cover: success, omitted-flag-no-fetch, empty-list case.

**Issues:**
- 🟡 IMPORTANT: `formatBuildCommits` (line 257) — the `.ifBlank { }` fallback chain `c.fullName.ifBlank { c.userName }.ifBlank { "—" }` is fine for the empty-string case, but `BuildChangeData.userName` and `fullName` are non-nullable `String` per the data class. If Bamboo returns `null` for a missing author (it sometimes does for system commits), the kotlinx.serialization layer would reject the response upstream — meaning this code is safe by construction. However, the deserializer in `:bamboo`'s `BambooServiceImpl` may have a tolerant default that maps null to `""`. Confirm the contract: if either field can be `""`, the chain is correct; if either field could be `null` post-deserialization (e.g. via `String? = null`), the call site would not compile. Spot-check confirms the data class has non-nullable types — so this is safe. Demoting from concern to "verified".

  Real concern: `c.userName` is the Bamboo username (e.g. `jdoe`), while `c.fullName` is the human display name (e.g. `John Doe`). When `fullName` is `""` we fall back to `userName` — fine. But the fallback ORDER is correct (display first, username second, em-dash last) only if the implementer's intent matches. Per the test stub in `BambooBuildsToolTest.kt:46-48`, both fields are populated, so the fallback is untested. Add a test stub with `fullName = ""` to lock in the intended order.
- 🔵 NIT: First-line truncation via `c.comment.lineSequence().firstOrNull() ?: ""` is correct but does not bound the line length. A 2000-char first line would inflate the formatted block. Consider `.take(200)` after `firstOrNull()` for token economy, especially since 50 commits × 2000 chars = 100KB worst case.

### `07a30761` — bitbucket `get_prs_for_branch`
**Spec compliance:** PASS — gap #3 closed; new action justified.

**Strengths:**
- Smallest possible diff: 21 lines added to `BitbucketPrTool.kt`. Reuses existing `repo_name` parameter (no duplicate `ParameterProperty`).
- Action enum updated (count: 18 → 19, hard-asserted by `BitbucketPrToolTest.kt:33`).
- 3 tests cover: success path, repo_name routing, missing-param error.
- Action description added to both KDoc and the inline `description` string — discoverability high.
- The implementer correctly identifies "(branch → PRs) is a different lookup index than (PR-id → PR)" in the commit message — tool-count discipline justified.

**Issues:**
- 🔵 NIT: The `branch_name` schema description ("Required for get_prs_for_branch") would be slightly more useful with a hint about whether refs/heads/ prefix is accepted. The underlying `BitbucketService.getPullRequestsForBranch(branchName, …)` doesn't document; not worth blocking.

### `0159bcfd` — sonar `rule` + `branches` `include_files`
**Spec compliance:** PASS — gaps #4 and #7 closed.

**Strengths:**
- `SonarRuleData` field names verified at `core/model/sonar/SonarRuleData.kt:14`: `ruleKey`, `name`, `description`, `remediation: String?`, `tags: List<String>`. Test stub matches exactly.
- `SonarFileComponent` verified at `SonarModels.kt:295`: `key`, `path`, `name`. Formatter uses `it.path` — correct.
- Two test seams (`executeRuleForTest`, `executeGetBranchesForTest`) match the JiraTool pattern exactly — `internal suspend` with the production handler delegating to them.
- `formatFileComponents` caps at 100 with truncation tail. Empty-list handling: `"Files: (none analyzed)"`.
- Action enum description updated, KDoc count bumped from 13 → 14, parameter `rule_key` and `include_files` added without duplicating any existing param.
- 4 tests cover: rule success, rule missing-param, branches with `include_files=true`, branches without `include_files`.

**Issues:**
- 🔵 NIT: `executeRuleForTest` returns the `ToolValidation.missingParam("rule_key")` error directly when `ruleKey.isNullOrBlank()` — the production handler invokes the test entry point with `params["rule_key"]?.jsonPrimitive?.content` (no `validateNotBlank`). A user passing `"rule_key": ""` would get the missingParam path, which is correct; passing `"rule_key": "   "` (whitespace) also gets it via `isNullOrBlank()`. Behavior matches the JiraTool seam. Fine.
- 🔵 NIT: The `include_files` flag fans out into `service.listFileComponents(projectKey, branch, repoName)` using the `branch` from params — but the existing `branches` action ignores `branch` (it lists all branches). When `include_files=true` is set without `branch`, the file list is for the default branch. This matches the audit's implicit intent ("which files Sonar analyzed for this project") but a user passing only `branch` and not `include_files` will see `branch` ignored. Documenting this in the schema description for `branch` would close the loop. Not blocking.

### `5938ae67` — bamboo `get_projects` + `auto_detect_plan` 5-tier overload
**Spec compliance:** PASS — gaps #6 and #8 closed.

**Strengths:**
- 5-tier signature verified at `core/services/BambooService.kt:83-88`:
  ```
  suspend fun autoDetectPlan(repoRoot: java.nio.file.Path?, remoteUrl: String,
                              branchName: String? = null, preferredMaster: String? = null)
  ```
  Implementer uses **named arguments** in the call site (line 240-244) — `repoRoot = …, remoteUrl = …, branchName = …, preferredMaster = …` — eliminating any positional-mismatch risk. This is exactly what the plan warned to verify.
- Routing condition is correct: `if (repoRoot == null && branchName == null && preferredMaster == null)` → 1-arg legacy path; ANY of the three present → 5-tier path. Matches the plan's spec ("when ANY of … is supplied").
- `Paths.get(repoRoot)` conversion handled correctly (`String → java.nio.file.Path`).
- 1-arg backward compatibility verified by test `auto_detect_plan with no extra args calls 1-arg overload` which asserts `coVerify(exactly = 0) { service.autoDetectPlan(repoRoot = any(), …) }`.
- New `executeWithService(params, service)` test seam refactor — `execute()` now does the IntelliJ service lookup then delegates. Single execution path, no duplication. Sole production caller is `execute()`; no other production code calls `executeWithService` directly.
- 4 new tests cover: `get_projects` success, 1-arg no-extras path, full 5-tier path, partial 5-tier path.
- `ProjectData` verified at `core/model/bamboo/BambooModels.kt:145`: `key`, `name`, `description: String? = null`. Test stub matches.

**Issues:**
- 🔵 NIT: The 5-tier routing handles three optional params but does NOT include `git_remote_url` itself in the trigger condition. This is intentional (the plan's spec explicitly says "when any of repo_root/branch_name/preferred_master is supplied") and consistent with the routing comment. Worth a one-line schema description note: "When any of these three is set, auto_detect_plan uses the richer 5-tier algorithm regardless of the legacy 1-arg form." (already partially in the existing description). Fine as-is.
- 🔵 NIT: Schema description for `repo_root` says "Local repo root path" without specifying a working-directory context (relative vs. absolute). The implementation passes through to `Paths.get(repoRoot)` which resolves against the JVM cwd — usually IntelliJ's process dir, NOT the project basedir. Most agent calls will pass an absolute path so this is fine, but a malformed relative path would fail silently inside the 5-tier detector. Not blocking.

## Cross-cutting findings

### Pattern consistency
All 5 EXTEND tasks follow the `include_dev_status` precedent faithfully. Specifically:
- Boolean parsing: `params["include_*"]?.jsonPrimitive?.content?.lowercase() == "true"` — verbatim across all 5.
- Error short-circuit: only the **primary** call's `.isError` path returns early; secondary feeds' errors are swallowed (since `?.let` skips them when the result is null) — matches the existing precedent. **Note:** secondary feeds that returned `isError=true` are still appended via the `?.let` block because `.let` doesn't gate on `isError`. The existing precedent has the same behavior. Acceptable.
- Summary join: `· ` separator, `summaries.joinToString(" · ")` — verbatim.
- Block join: `\n\n` separator, `blocks.joinToString("\n\n")` — verbatim.
- Token estimate: `TokenEstimator.estimate(combined)` — verbatim.

### Tool-count discipline
3 new actions added (`bitbucket_pr.get_prs_for_branch`, `sonar.rule`, `bamboo_plans.get_projects`). All three are genuinely orthogonal:
- `get_prs_for_branch` — different lookup index (branch → PRs), cannot fold into existing PR-id actions.
- `sonar.rule` — entirely new resource (rule-by-key), not a variant of any existing Sonar action.
- `bamboo_plans.get_projects` — different resource type (projects, not plans).

The audit's tool-count concern is respected. Net change: +3 actions across ~84 existing.

### Test coverage
Each commit ships exactly the tests the plan specifies (success path / omitted-flag / error-path / empty-list as applicable). `coVerify(exactly = 0)` invariants on the omitted-flag tests are present in all relevant cases — correctly proves no extra service calls. No tests were removed or weakened.

Test isolation: 4 commits introduce `internal suspend` test seams (`executeGetTicketForTest`, `executeGetBuildForTest`, `executeRuleForTest` + `executeGetBranchesForTest`, `executeWithService`). All are wired so the production handler delegates to them — single execution path, no diverging behavior. `internal` visibility is correct for test access from the same module.

### Module boundaries
Verified: `agent/build.gradle.kts` declares `implementation(project(":core"))` + `implementation(project(":document"))` (the documented exception). No commit imports from `:jira`, `:bamboo`, `:bitbucket`, `:sonar`, or `:pullrequest` into `:agent`. All cross-module data flows through `core/model/*` types and `core/services/*` interfaces. Pattern compliance: clean.

### Coroutine scope hygiene
Every new fan-out uses `coroutineScope { async { … } }` — structured concurrency. No `GlobalScope`, no `runBlocking`, no `Dispatchers.Unconfined`. `async` blocks await within the same `coroutineScope`. Cancellation propagates correctly.

### `include_*` flags default to false
Verified in all 5 EXTEND tasks. The pattern `?.lowercase() == "true"` returns `false` on null/missing/anything-else, so the default is safe.

### Schema bloat
Spot-checked all 5 modified tools — no `ParameterProperty` is declared twice within the same tool. New params (`include_remote_links`, `include_history`, `include_permissions`, `include_commits`, `branch_name`, `rule_key`, `include_files`, `repo_root`, `preferred_master`) appear exactly once each.

## Recommended follow-ups

1. **Harden `executeGetTicketForTest`** to validate the Jira key when `includePermissions=true` (or extract the projectKey safely with a sentinel on no-dash). Today the production path is safe via `validateJiraKey`, but the test entry point can panic on `"PROJ"` with no dash — a future fuzz test or a misuse pattern would catch this. (`JiraTool.kt:216`, `:799`)
2. **Add a `formatBuildCommits` test stub with `fullName = ""`** to lock in the `.ifBlank { userName }.ifBlank { "—" }` fallback ordering. Today's stub populates both fields. (`BambooBuildsToolTest.kt:46`)
3. **Cap `formatRemoteLinks` at 20 entries** (or whatever sane number) for consistency with `formatTicketHistory`. Token-economy hygiene. (`JiraTool.kt:744`)
4. **Bound `formatBuildCommits` first-line length** with `.take(200)` to prevent a verbose first commit message from inflating the block. (`BambooBuildsTool.kt:259`)
5. **Document the `branch` + `include_files` interaction** on `sonar.branches` — when both are set, `branch` controls the file-list query but the branches list itself is unaffected. (`SonarTool.kt:144`)

## Approved as-is / Request changes

**APPROVE WITH NITS.** All 6 commits are mergeable as-is. The 2 important issues are edge-case hardenings (no-dash Jira key in test entry point; missing test for `fullName=""` fallback) that don't block correctness on the production path. The 6 nits are token-economy and documentation polish. Reviewer's recommendation: ship; track follow-ups 1-5 in the audit memory.

The implementer demonstrated strong field-name verification discipline (every data-class field used in the formatters matches the actual class — none of the plan's guesses were copy-pasted blindly), clean test seams, faithful precedent following, and correct named-argument usage on the `BambooService.autoDetectPlan` 5-tier overload to avoid the parameter-order trap the plan flagged. The N-way fan-out refactor in commit `2abc16c9` was a clean architectural delta that paid off in commit `a9885383`'s 36-line diff.
