# Agent Tool Gap Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the 8 service-but-not-agent gaps identified in `docs/research/2026-05-07-agent-tool-gap-audit.md` so the LLM can reach the full output of recently-added probe-validated APIs without making 4-7 round-trips per question. Five extensions (folded into existing actions) and three new actions for genuinely orthogonal resources.

**Architecture:** Mirror the existing `include_dev_status` precedent (`JiraTool.kt:191-208`) — opt-in `include_*: bool` parameters that fan out via `coroutineScope { async { … } }` and append formatted blocks to the action's content. Add new actions only where the resource is orthogonal (PR-by-branch lookup, Sonar rule fetch, Bamboo project listing).

**Tech Stack:** Kotlin 2.1.10, IntelliJ Platform 2025.1+, kotlinx.coroutines `async`, kotlinx.serialization `JsonPrimitive`, JUnit 5 + MockK.

**Branch:** `fix/automation-handover-quality-tabs` (continue current branch — see memory `feedback_work_on_current_branch.md`).

**Reference:** All gap details and rationale in `docs/research/2026-05-07-agent-tool-gap-audit.md`.

**Out of scope:**
- The 14 UI-only methods (settings dropdowns, type-ahead pickers) — intentional non-exposure per audit §"Methods reviewed and intentionally NOT recommending exposure."
- Watcher add/remove (niche workflow, defer until requested).
- `unapprovePullRequest` (symmetry-only with `approve_pr`).
- Test connection methods (settings/admin only).

---

## File Structure

**Modified files (5 tool files + their tests):**
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/JiraTool.kt` — Tasks 1, 5 (gaps #2, #5)
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BambooBuildsTool.kt` — Task 2 (gap #1)
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BambooPlansTool.kt` — Tasks 6, 8 (gaps #6, #8)
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BitbucketPrTool.kt` — Task 3 (gap #3)
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/SonarTool.kt` — Tasks 4, 7 (gaps #4, #7)

**Test files modified or created in same module:**
- `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/integration/JiraToolTest.kt`
- `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/integration/BambooBuildsToolTest.kt`
- `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/integration/BambooPlansToolTest.kt`
- `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/integration/BitbucketPrToolTest.kt`
- `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/integration/SonarToolTest.kt`

**Commit grouping:** 5 commits, one per tool file. Each commit ships independently.

---

## Reference: the `include_dev_status` precedent

This is the canonical EXTEND pattern. Anchored at `JiraTool.kt:162` (schema) and `:191-208` (handler). The plan reuses it five times:

```kotlin
// Schema (in ParameterProperty list):
"include_dev_status" to ParameterProperty(
    type = "boolean",
    description = "If true, also fetch dev-panel data (branches, PRs, builds, …) in parallel. Default false to keep token cost low."
)

// Handler:
val includeDevStatus = params["include_dev_status"]?.jsonPrimitive?.content?.lowercase() == "true"
if (!includeDevStatus) {
    service.getTicket(key).toAgentToolResult()
} else {
    coroutineScope {
        val ticketDeferred = async { service.getTicket(key) }
        val devStatusDeferred = async { service.getFullDevStatus(key) }
        val ticketResult = ticketDeferred.await()
        val devStatus = devStatusDeferred.await()
        if (ticketResult.isError) return@coroutineScope ticketResult.toAgentToolResult()
        val ticketAgent = ticketResult.toAgentToolResult()
        val devStatusBlock = formatDevStatusBundle(key, devStatus.data)
        val combined = ticketAgent.content + "\n\n" + devStatusBlock
        ToolResult(combined, "${ticketAgent.summary} · ${devStatus.data.summaryLine()}", TokenEstimator.estimate(combined))
    }
}
```

Each EXTEND task below references this template by name ("the `include_*` precedent"). When more than one opt-in flag is supplied to the same action, the `async { … }` block widens to fan out all selected feeds in parallel and the content gets multiple appended blocks, separated by `\n\n`.

---

## Task 1: `JiraTool.get_ticket` adds `include_remote_links` + `include_history`

**Gap:** #2 (audit row 5). Service methods `JiraService.getRemoteLinks(key)` and `JiraService.getTicketHistory(key)` exist (lines `:117`, `:141`) but no agent action surfaces them.

**Why it matters:** When the LLM is asked "summarise PROJ-123," it sees ticket fields + comments + dev-status. Confluence backlinks and transition history (who moved it from "In Progress" to "In Review", when) are invisible. Both already power the Jira UI panel; the agent surface just hasn't caught up.

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/JiraTool.kt`
- Modify: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/integration/JiraToolTest.kt`

- [ ] **Step 1: Read the full existing `get_ticket` block + the `formatDevStatusBundle` helper**

Read `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/JiraTool.kt` at lines `40-60` (description), `155-180` (parameter schema), `185-215` (handler), and locate `formatDevStatusBundle` (search the file for `private fun formatDevStatusBundle`). Confirm the existing `coroutineScope { async { … } }` fan-out shape.

Also read the existing test file `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/integration/JiraToolTest.kt` — find the test that exercises `include_dev_status=true`. The new tests will mirror its setup.

- [ ] **Step 2: Write failing tests**

Add to `JiraToolTest.kt` (4 new tests, mirroring the include_dev_status pattern):

```kotlin
@Test
fun `get_ticket with include_remote_links=true fetches remote links and appends block`() = runTest {
    // Stub jiraService.getTicket(...) → success
    // Stub jiraService.getRemoteLinks("PROJ-1") → ToolResult(listOf(RemoteLinkData(applicationName="Confluence", title="Design Doc", url="…")), summary="…")
    val params = buildJsonObject {
        put("action", "get_ticket")
        put("key", "PROJ-1")
        put("include_remote_links", true)
    }
    val result = jiraTool.execute(params, project)
    // Assert: content contains "Remote Links:", "Confluence", "Design Doc"
    // Assert: jiraService.getRemoteLinks("PROJ-1") was called exactly once
    // Assert: result.isError == false
}

@Test
fun `get_ticket with include_remote_links omitted does NOT fetch remote links`() = runTest {
    val params = buildJsonObject {
        put("action", "get_ticket")
        put("key", "PROJ-1")
    }
    jiraTool.execute(params, project)
    // Assert: jiraService.getRemoteLinks(any()) was never called
}

@Test
fun `get_ticket with include_history=true fetches history and appends block`() = runTest {
    // Stub jiraService.getTicketHistory("PROJ-1") → ToolResult(listOf of TicketHistoryEntryData with status changes)
    val params = buildJsonObject {
        put("action", "get_ticket")
        put("key", "PROJ-1")
        put("include_history", true)
    }
    val result = jiraTool.execute(params, project)
    // Assert: content contains "History:" or "Changelog:"
    // Assert: at least one history entry's "from"/"to"/"author" is in content
}

@Test
fun `get_ticket with multiple include flags fans out in parallel`() = runTest {
    // Stub all three: getTicket, getRemoteLinks, getTicketHistory, getFullDevStatus
    val params = buildJsonObject {
        put("action", "get_ticket")
        put("key", "PROJ-1")
        put("include_dev_status", true)
        put("include_remote_links", true)
        put("include_history", true)
    }
    val result = jiraTool.execute(params, project)
    // Assert: content has all three blocks (DevStatus, Remote Links, History)
    // Assert: each service call was made exactly once
}
```

Use the existing test fixture style — same `project` mock, same `jiraService` mock setup. **Look at the existing `include_dev_status=true` test for the exact mock-stubbing approach.**

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :agent:test --tests "JiraToolTest"`

Expected: 4 new tests FAIL (params parsed as false because no flag handler exists yet).

- [ ] **Step 4: Add schema entries**

In `JiraTool.kt`, locate the `ParameterProperty` block where `include_dev_status` is registered (around line 162). Add two new entries below it:

```kotlin
"include_remote_links" to ParameterProperty(
    type = "boolean",
    description = "If true, also fetch remote links (Confluence pages, external URLs linked from this ticket). Default false."
),
"include_history" to ParameterProperty(
    type = "boolean",
    description = "If true, also fetch the ticket's status/assignee/priority change history. Default false."
)
```

- [ ] **Step 5: Update the action description**

Update the description string at line ~42 to mention the two new flags:

```
- get_ticket(key, include_dev_status?, include_remote_links?, include_history?) → Full ticket details. include_* flags fan out in parallel; each adds a block to the response. Use include_dev_status for "what's the status across CI/PR", include_remote_links for "what design docs link to this", include_history for "who changed what when".
```

- [ ] **Step 6: Refactor the handler to fan out N flags in parallel**

Locate the `"get_ticket" ->` branch (line ~187). Replace the binary if/else with an N-way fan-out. The cleanest shape:

```kotlin
"get_ticket" -> {
    val key = params["key"]?.jsonPrimitive?.content
        ?: return ToolValidation.missingParam("key")
    ToolValidation.validateJiraKey(key)?.let { return it }
    val includeDevStatus = params["include_dev_status"]?.jsonPrimitive?.content?.lowercase() == "true"
    val includeRemoteLinks = params["include_remote_links"]?.jsonPrimitive?.content?.lowercase() == "true"
    val includeHistory = params["include_history"]?.jsonPrimitive?.content?.lowercase() == "true"
    val anyInclude = includeDevStatus || includeRemoteLinks || includeHistory
    if (!anyInclude) {
        service.getTicket(key).toAgentToolResult()
    } else {
        coroutineScope {
            val ticketDeferred = async { service.getTicket(key) }
            val devStatusDeferred = if (includeDevStatus) async { service.getFullDevStatus(key) } else null
            val remoteLinksDeferred = if (includeRemoteLinks) async { service.getRemoteLinks(key) } else null
            val historyDeferred = if (includeHistory) async { service.getTicketHistory(key) } else null

            val ticketResult = ticketDeferred.await()
            if (ticketResult.isError) return@coroutineScope ticketResult.toAgentToolResult()
            val ticketAgent = ticketResult.toAgentToolResult()

            val blocks = mutableListOf(ticketAgent.content)
            val summaries = mutableListOf(ticketAgent.summary)

            devStatusDeferred?.await()?.let { ds ->
                blocks += formatDevStatusBundle(key, ds.data)
                summaries += ds.data.summaryLine()
            }
            remoteLinksDeferred?.await()?.let { rl ->
                blocks += formatRemoteLinks(rl.data)
                summaries += rl.summary
            }
            historyDeferred?.await()?.let { h ->
                blocks += formatTicketHistory(h.data)
                summaries += h.summary
            }

            val combined = blocks.joinToString("\n\n")
            ToolResult(combined, summaries.joinToString(" · "), TokenEstimator.estimate(combined))
        }
    }
}
```

- [ ] **Step 7: Add the two new formatter helpers**

Add private helpers next to the existing `formatDevStatusBundle` (search for `private fun formatDevStatusBundle` to find its location). Use simple line-based formatting matching the existing helper's style:

```kotlin
private fun formatRemoteLinks(links: List<RemoteLinkData>): String {
    if (links.isEmpty()) return "Remote Links: (none)"
    val lines = links.map { "• [${it.applicationName}] ${it.title} → ${it.url}" }
    return "Remote Links:\n" + lines.joinToString("\n")
}

private fun formatTicketHistory(history: List<TicketHistoryEntryData>): String {
    if (history.isEmpty()) return "History: (none)"
    val lines = history.take(20).map { entry ->
        val changes = entry.changes.joinToString(", ") { "${it.field}: ${it.fromString ?: "—"} → ${it.toString_ ?: "—"}" }
        "• ${entry.created} · ${entry.author} · $changes"
    }
    val truncated = if (history.size > 20) "\n  …(${history.size - 20} more entries)" else ""
    return "History (last ${minOf(20, history.size)} of ${history.size}):\n" + lines.joinToString("\n") + truncated
}
```

If `RemoteLinkData` or `TicketHistoryEntryData` field names differ (`fromString`/`toString_`/`author`/`created` are guesses based on Atlassian REST conventions), open `core/src/main/kotlin/com/workflow/orchestrator/core/model/jira/JiraExtensionModels.kt` and use the actual field names.

- [ ] **Step 8: Run tests to verify they pass**

Run: `./gradlew :agent:test --tests "JiraToolTest"`

Expected: all tests PASS (existing + 4 new).

- [ ] **Step 9: Run the full :agent suite to confirm no regression**

Run: `./gradlew :agent:test`

Expected: 3278+ tests PASS (per memory `project_api_audit_in_progress`).

- [ ] **Step 10: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/JiraTool.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/integration/JiraToolTest.kt
git commit -m "$(cat <<'EOF'
feat(agent): jira.get_ticket adds include_remote_links + include_history

Both flags follow the existing include_dev_status precedent —
opt-in, default false, parallel fan-out via coroutineScope/async.
Closes audit gaps #2 and partial #5 (permissions added in next commit).

Without these the LLM saw ticket fields + comments + dev-status only;
Confluence backlinks and transition history were invisible despite
already powering the Jira UI panel.
EOF
)"
```

---

## Task 2: `BambooBuildsTool.get_build` adds `include_commits`

**Gap:** #1 (audit row 24, top leverage). `BambooService.getBuildChanges(resultKey)` exists (line `:118`, added in `59c9ea8d` §8.8) — agent has no path to it.

**Why it matters:** When the LLM is asked "what's in this CI build" or "why did this build fail," it currently sees status + log but not the commit list. The Bamboo→Bitbucket→Jira triangle (build → commits → PRs → tickets) requires this primitive.

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BambooBuildsTool.kt`
- Modify: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/integration/BambooBuildsToolTest.kt`

- [ ] **Step 1: Read existing context**

Read `BambooBuildsTool.kt` end-to-end. Locate the `get_build` action handler. Confirm the parameter schema and existing service-call shape.

Read `BambooBuildsToolTest.kt` to find the existing `get_build` test pattern.

Read `core/src/main/kotlin/com/workflow/orchestrator/core/model/bamboo/BambooModels.kt` — confirm the exact `BuildChangeData` shape (added in `59c9ea8d`).

- [ ] **Step 2: Write failing tests**

Add 3 new tests to `BambooBuildsToolTest.kt`:

```kotlin
@Test
fun `get_build with include_commits=true appends commits block`() = runTest {
    // Stub bambooService.getBuild("PLAN-X-42") → success
    // Stub bambooService.getBuildChanges("PLAN-X-42") → ToolResult(listOf(BuildChangeData(sha="abc1234", message="Fix null bug", author="Alice")))
    val params = buildJsonObject {
        put("action", "get_build")
        put("build_key", "PLAN-X-42")
        put("include_commits", true)
    }
    val result = bambooBuildsTool.execute(params, project)
    // Assert: content contains "Commits:" / "abc1234" / "Fix null bug"
    // Assert: getBuildChanges called once
    // Assert: result.isError == false
}

@Test
fun `get_build with include_commits omitted does NOT fetch changes`() = runTest {
    val params = buildJsonObject {
        put("action", "get_build")
        put("build_key", "PLAN-X-42")
    }
    bambooBuildsTool.execute(params, project)
    // Assert: getBuildChanges(any()) was never called
}

@Test
fun `get_build with include_commits=true on empty change list says (none)`() = runTest {
    // Stub getBuildChanges → ToolResult(emptyList(), summary="No commits in this build")
    val params = buildJsonObject {
        put("action", "get_build")
        put("build_key", "PLAN-X-42")
        put("include_commits", true)
    }
    val result = bambooBuildsTool.execute(params, project)
    // Assert: content contains "Commits: (none)" or "No commits"
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :agent:test --tests "BambooBuildsToolTest"`

Expected: 3 new tests FAIL.

- [ ] **Step 4: Add schema entry**

In `BambooBuildsTool.kt`, locate the `ParameterProperty` declaration for the `get_build` action. Add:

```kotlin
"include_commits" to ParameterProperty(
    type = "boolean",
    description = "If true, also fetch the per-build commit list (SHA, message, author) via Bamboo's expand=changes.change. Default false to keep token cost low."
)
```

- [ ] **Step 5: Update the action description**

Update the action description at the top of the file's KDoc to mention `include_commits`. Match the existing description style.

- [ ] **Step 6: Implement the parallel fan-out**

In the `"get_build" ->` branch, mirror the `include_dev_status` precedent. Pseudocode:

```kotlin
"get_build" -> {
    val buildKey = params["build_key"]?.jsonPrimitive?.content
        ?: return ToolValidation.missingParam("build_key")
    val includeCommits = params["include_commits"]?.jsonPrimitive?.content?.lowercase() == "true"
    if (!includeCommits) {
        service.getBuild(buildKey).toAgentToolResult()
    } else {
        coroutineScope {
            val buildDeferred = async { service.getBuild(buildKey) }
            val changesDeferred = async { service.getBuildChanges(buildKey) }
            val buildResult = buildDeferred.await()
            val changes = changesDeferred.await()
            if (buildResult.isError) return@coroutineScope buildResult.toAgentToolResult()
            val buildAgent = buildResult.toAgentToolResult()
            val commitsBlock = formatBuildCommits(changes.data)
            val combined = buildAgent.content + "\n\n" + commitsBlock
            ToolResult(combined, "${buildAgent.summary} · ${changes.summary}", TokenEstimator.estimate(combined))
        }
    }
}
```

- [ ] **Step 7: Add the formatter helper**

```kotlin
private fun formatBuildCommits(commits: List<BuildChangeData>): String {
    if (commits.isEmpty()) return "Commits: (none)"
    val lines = commits.take(50).map { c -> "• ${c.sha.take(8)} · ${c.author ?: "—"} · ${c.message.lineSequence().firstOrNull() ?: ""}" }
    val tail = if (commits.size > 50) "\n  …(${commits.size - 50} more commits)" else ""
    return "Commits (showing ${minOf(50, commits.size)} of ${commits.size}):\n" + lines.joinToString("\n") + tail
}
```

If `BuildChangeData` field names differ from `sha`/`message`/`author`, use the actual ones from the model file.

- [ ] **Step 8: Run tests to verify they pass**

Run: `./gradlew :agent:test --tests "BambooBuildsToolTest"`

Expected: all PASS.

- [ ] **Step 9: Run full :agent suite**

Run: `./gradlew :agent:test`

Expected: green.

- [ ] **Step 10: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BambooBuildsTool.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/integration/BambooBuildsToolTest.kt
git commit -m "$(cat <<'EOF'
feat(agent): bamboo_builds.get_build adds include_commits flag

Surfaces the Bamboo getBuildChanges (R-ADD-1, §8.8) data the
LLM previously couldn't reach. Mirrors include_dev_status pattern.

Closes audit gap #1 — top-leverage gap because it completes the
Bamboo→Bitbucket→Jira triangle (build → commits → PRs → tickets)
the recent audit groundwork enabled.
EOF
)"
```

---

## Task 3: `BitbucketPrTool.get_prs_for_branch` (NEW ACTION)

**Gap:** #3. `BitbucketService.getPullRequestsForBranch(branchName, repoName?)` exists (line `:63`) — no agent path.

**Why it matters:** "I just pushed `feature/PROJ-1` — is there an open PR for it?" is a top-3 LLM use case and currently has no path. `get_my_prs` doesn't help (PRs can be opened by anyone). This action is genuinely orthogonal to existing PR-by-id actions; folding it into `get_pr_detail` would mean overloading mutually-exclusive params (id vs. branch), which the audit explicitly flags as an anti-pattern.

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BitbucketPrTool.kt`
- Modify: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/integration/BitbucketPrToolTest.kt`

- [ ] **Step 1: Read existing context**

Read `BitbucketPrTool.kt`. Note the `repo_name` parameter pattern used by every PR-id-based action (multi-repo support). Look at `get_my_prs` and `get_reviewing_prs` — they're the closest existing precedents because they return *lists* rather than single PR details.

Read the existing test file to find the standard mock setup for `bitbucketService`.

- [ ] **Step 2: Write failing tests**

Add 3 new tests:

```kotlin
@Test
fun `get_prs_for_branch returns matching open PRs`() = runTest {
    // Stub bitbucketService.getPullRequestsForBranch("feature/PROJ-1", null) →
    //   ToolResult(listOf(PullRequestSummary(id=42, title="…", state="OPEN", …)), summary="1 PR for feature/PROJ-1")
    val params = buildJsonObject {
        put("action", "get_prs_for_branch")
        put("branch_name", "feature/PROJ-1")
    }
    val result = bitbucketPrTool.execute(params, project)
    // Assert: content references PR-42, summary mentions "1 PR"
    // Assert: getPullRequestsForBranch("feature/PROJ-1", null) called once
}

@Test
fun `get_prs_for_branch with repo_name routes to that repo`() = runTest {
    val params = buildJsonObject {
        put("action", "get_prs_for_branch")
        put("branch_name", "feature/X-1")
        put("repo_name", "platform-app")
    }
    bitbucketPrTool.execute(params, project)
    // Assert: getPullRequestsForBranch("feature/X-1", "platform-app") called
}

@Test
fun `get_prs_for_branch missing branch_name returns missingParam error`() = runTest {
    val params = buildJsonObject {
        put("action", "get_prs_for_branch")
    }
    val result = bitbucketPrTool.execute(params, project)
    // Assert: result.isError == true, content mentions "branch_name"
}
```

- [ ] **Step 3: Run failing tests**

Run: `./gradlew :agent:test --tests "BitbucketPrToolTest"`

Expected: 3 new tests FAIL.

- [ ] **Step 4: Register the action name**

In `BitbucketPrTool.kt`, locate the action enumeration list (around line 57). Add `"get_prs_for_branch"` to the list.

- [ ] **Step 5: Add schema entries**

In the `ParameterProperty` block, ensure the schema includes:

```kotlin
"branch_name" to ParameterProperty(
    type = "string",
    description = "Branch name (e.g. 'feature/PROJ-1') to look up PRs for. Required for get_prs_for_branch."
)
```

(`repo_name` is likely already declared from existing actions — verify, don't duplicate.)

- [ ] **Step 6: Implement the handler**

Add a new branch in the `when (action)` block — match the style of `get_my_prs`:

```kotlin
"get_prs_for_branch" -> {
    val branchName = params["branch_name"]?.jsonPrimitive?.content
        ?: return ToolValidation.missingParam("branch_name")
    val repoName = params["repo_name"]?.jsonPrimitive?.content
    service.getPullRequestsForBranch(branchName, repoName).toAgentToolResult()
}
```

- [ ] **Step 7: Update the action description**

Add a line to the file's KDoc (where other actions are listed) describing the new action:

```
- get_prs_for_branch(branch_name, repo_name?) → Lists PRs that have the given branch as their source. Use this to answer "is there a PR for the branch I just pushed?"
```

- [ ] **Step 8: Run tests**

Run: `./gradlew :agent:test --tests "BitbucketPrToolTest"`

Expected: all PASS.

- [ ] **Step 9: Run :agent full suite**

Run: `./gradlew :agent:test`

Expected: green.

- [ ] **Step 10: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BitbucketPrTool.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/integration/BitbucketPrToolTest.kt
git commit -m "$(cat <<'EOF'
feat(agent): bitbucket_pr.get_prs_for_branch new action

Surfaces BitbucketService.getPullRequestsForBranch which
the agent had no path to despite the service method existing.

Answers "I just pushed, is there a PR?" — get_my_prs doesn't,
because PRs may be opened by anyone. New action rather than
extension because it's a different lookup index (branch → PRs)
than the existing PR-id-based actions.
EOF
)"
```

---

## Task 4: `SonarTool.rule` (NEW ACTION) + `SonarTool.branches` extends with file list

**Gaps:** #4 + #7. `SonarService.getRule(ruleKey)` (line `:72`) and `SonarService.listFileComponents(projectKey)` (line `:82`) — no agent paths.

**Why it matters:**
- Rule fetch lets the LLM look up `java:S1234` remediation guidance instead of guessing/web-searching.
- File list lets the LLM enumerate which files Sonar tracks before drilling in (saves wasted `source_lines` calls on files Sonar excluded from coverage).

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/SonarTool.kt`
- Modify: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/integration/SonarToolTest.kt`

- [ ] **Step 1: Read context**

Read `SonarTool.kt` end-to-end. Look at the existing `branches` action — that's the EXTEND target for gap #7. Look at the existing pattern for actions returning a single resource (e.g. `quality_gate`, `coverage`) — model `rule` after those.

- [ ] **Step 2: Write failing tests**

Add 4 new tests:

```kotlin
@Test
fun `rule returns rule details for known rule key`() = runTest {
    // Stub sonarService.getRule("java:S1234", null) →
    //   ToolResult(SonarRuleData(key="java:S1234", name="…", htmlDesc="…", severity="MAJOR"))
    val params = buildJsonObject {
        put("action", "rule")
        put("rule_key", "java:S1234")
    }
    val result = sonarTool.execute(params, project)
    // Assert: content has rule name + severity + part of htmlDesc
}

@Test
fun `rule missing rule_key errors`() = runTest {
    val params = buildJsonObject { put("action", "rule") }
    val result = sonarTool.execute(params, project)
    // Assert: result.isError == true, message references "rule_key"
}

@Test
fun `branches with include_files=true appends file list`() = runTest {
    // Stub getBranches(...) → success
    // Stub listFileComponents("PROJ-X", null, null) → ToolResult(listOf("src/Main.java", "src/Util.java"))
    val params = buildJsonObject {
        put("action", "branches")
        put("project_key", "PROJ-X")
        put("include_files", true)
    }
    val result = sonarTool.execute(params, project)
    // Assert: content contains "Files:", "src/Main.java"
}

@Test
fun `branches without include_files does NOT fetch file list`() = runTest {
    val params = buildJsonObject {
        put("action", "branches")
        put("project_key", "PROJ-X")
    }
    sonarTool.execute(params, project)
    // Assert: listFileComponents was never called
}
```

- [ ] **Step 3: Run failing tests**

Run: `./gradlew :agent:test --tests "SonarToolTest"`

Expected: 4 new tests FAIL.

- [ ] **Step 4: Register the new `rule` action + schema**

Add `"rule"` to the action enumeration. Add schema:

```kotlin
"rule_key" to ParameterProperty(
    type = "string",
    description = "Sonar rule key (e.g. 'java:S1234'). Required for the rule action."
),
"include_files" to ParameterProperty(
    type = "boolean",
    description = "On the branches action, also include the list of files Sonar analyzed for this project. Default false."
)
```

- [ ] **Step 5: Implement `rule` handler**

```kotlin
"rule" -> {
    val ruleKey = params["rule_key"]?.jsonPrimitive?.content
        ?: return ToolValidation.missingParam("rule_key")
    val repoName = params["repo_name"]?.jsonPrimitive?.content
    service.getRule(ruleKey, repoName).toAgentToolResult()
}
```

- [ ] **Step 6: Extend `branches` handler with `include_files` fan-out**

Mirror the `include_dev_status` precedent. Use parallel fan-out via `coroutineScope { async { … } }` when `include_files=true`.

- [ ] **Step 7: Update action descriptions**

Add `rule(rule_key)` and `include_files` flag to the action listing in the KDoc.

- [ ] **Step 8: Run tests**

Run: `./gradlew :agent:test --tests "SonarToolTest"`

Expected: all PASS.

- [ ] **Step 9: Run :agent suite**

Run: `./gradlew :agent:test`

Expected: green.

- [ ] **Step 10: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/SonarTool.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/integration/SonarToolTest.kt
git commit -m "$(cat <<'EOF'
feat(agent): sonar.rule new action + branches extends with include_files

rule() surfaces SonarService.getRule for issue→remediation lookup —
when LLM sees rule java:S1234 it can fetch guidance instead of
guessing or web-searching.

branches gains opt-in include_files so the LLM can enumerate
analyzed files before drilling into source_lines / duplications,
saving wasted calls on files Sonar excluded from coverage.

Closes audit gaps #4 and #7.
EOF
)"
```

---

## Task 5: `JiraTool.get_ticket` adds `include_permissions`

**Gap:** #5. `JiraService.getMyPermissions(projectKey?)` (line `:111`) — no agent path.

**Why it matters:** Lets the LLM avoid 403s by checking "can I transition this?" / "can I comment?" before attempting the action. Also useful for adapting the agent's plan ("user lacks Transition permission, suggest contacting the assignee instead").

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/JiraTool.kt`
- Modify: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/integration/JiraToolTest.kt`

**Note:** Task 1 already refactored the `get_ticket` handler into N-way fan-out. This task threads one more `include_permissions` flag through the same shape — small delta.

- [ ] **Step 1: Re-read the post-Task-1 handler**

Confirm the structure introduced in Task 1 (`includeDevStatus || includeRemoteLinks || includeHistory` switch + per-flag `async`).

- [ ] **Step 2: Write failing tests**

Add 2 new tests to `JiraToolTest.kt`:

```kotlin
@Test
fun `get_ticket with include_permissions=true fetches and appends permissions`() = runTest {
    // Stub jiraService.getMyPermissions("PROJ") → ToolResult(MyPermissionsData(canTransition=true, canComment=true, canLogWork=false))
    val params = buildJsonObject {
        put("action", "get_ticket")
        put("key", "PROJ-1")
        put("include_permissions", true)
    }
    val result = jiraTool.execute(params, project)
    // Assert: content contains "Permissions:", "canTransition: true", "canLogWork: false"
}

@Test
fun `get_ticket include_permissions extracts projectKey from issue key`() = runTest {
    val params = buildJsonObject {
        put("action", "get_ticket")
        put("key", "PROJ-1")
        put("include_permissions", true)
    }
    jiraTool.execute(params, project)
    // Assert: getMyPermissions("PROJ") was called — projectKey derived from PROJ-1 prefix
}
```

- [ ] **Step 3: Run failing tests**

Expected: 2 new tests FAIL.

- [ ] **Step 4: Add schema entry**

```kotlin
"include_permissions" to ParameterProperty(
    type = "boolean",
    description = "If true, also fetch the user's permissions on this ticket's project (transition, comment, log work, watch). Default false."
)
```

- [ ] **Step 5: Thread the flag through the handler**

Extend the Task-1 fan-out to a fourth parallel feed:

```kotlin
val includePermissions = params["include_permissions"]?.jsonPrimitive?.content?.lowercase() == "true"
val anyInclude = includeDevStatus || includeRemoteLinks || includeHistory || includePermissions
// ...
val projectKey = key.substringBefore("-")  // "PROJ-1" → "PROJ"
val permsDeferred = if (includePermissions) async { service.getMyPermissions(projectKey) } else null
// ...
permsDeferred?.await()?.let { p ->
    blocks += formatPermissions(p.data)
    summaries += p.summary
}
```

- [ ] **Step 6: Add formatter**

```kotlin
private fun formatPermissions(perms: MyPermissionsData): String {
    val lines = listOf(
        "  canTransition: ${perms.canTransition}",
        "  canComment: ${perms.canComment}",
        "  canLogWork: ${perms.canLogWork}",
        "  canWatch: ${perms.canWatch}"
    )
    return "Permissions (project ${perms.projectKey ?: "-"}):\n" + lines.joinToString("\n")
}
```

(Use actual `MyPermissionsData` field names from the model file.)

- [ ] **Step 7: Update description string**

Add `include_permissions` to the action description.

- [ ] **Step 8: Run tests**

Run: `./gradlew :agent:test --tests "JiraToolTest"`

Expected: all tests pass (existing + Task-1 + new).

- [ ] **Step 9: Run :agent suite**

Run: `./gradlew :agent:test`

Expected: green.

- [ ] **Step 10: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/JiraTool.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/integration/JiraToolTest.kt
git commit -m "$(cat <<'EOF'
feat(agent): jira.get_ticket adds include_permissions

Threads getMyPermissions(projectKey) through the same fan-out
structure introduced in the previous commit. projectKey is
derived from the issue key prefix (PROJ-1 → PROJ).

Lets the LLM avoid 403s by checking "can I transition this"
before attempting the action. Closes audit gap #5.
EOF
)"
```

---

## Task 6: `BambooPlansTool.get_projects` (NEW ACTION) + `auto_detect_plan` extension

**Gaps:** #6 + #8. `BambooService.getProjects()` (`:108`) and `BambooService.autoDetectPlan(repoRoot, remoteUrl, branch, preferredMaster)` 5-tier overload (`:83`).

**Why it matters:**
- `getProjects` lets the LLM list project keys before calling `get_project_plans(projectKey)` — today the LLM has to grep build keys to guess prefixes.
- The 5-tier `autoDetectPlan` overload gives much better detection on multi-module repos than the 1-arg legacy version that's currently exposed.

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BambooPlansTool.kt`
- Modify: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/integration/BambooPlansToolTest.kt`

- [ ] **Step 1: Read context**

Read `BambooPlansTool.kt`. Locate the existing `auto_detect_plan` handler — confirm it currently calls the 1-arg overload. Locate `get_plans` and `search_plans` for the listing-action style.

Read `BambooService.kt` lines 83-100 to confirm the 5-tier overload signature exactly.

- [ ] **Step 2: Write failing tests**

Add 4 new tests:

```kotlin
@Test
fun `get_projects returns list of Bamboo projects`() = runTest {
    // Stub bambooService.getProjects() → ToolResult(listOf(BambooProjectData(key="PLAT", name="Platform"), …))
    val params = buildJsonObject { put("action", "get_projects") }
    val result = bambooPlansTool.execute(params, project)
    // Assert: content contains "PLAT", "Platform"
    // Assert: getProjects() called once
}

@Test
fun `auto_detect_plan with no extra args calls 1-arg overload`() = runTest {
    val params = buildJsonObject {
        put("action", "auto_detect_plan")
        put("git_remote_url", "git@example.com:foo/bar.git")
    }
    bambooPlansTool.execute(params, project)
    // Assert: bambooService.autoDetectPlan("git@example.com:foo/bar.git") called  (1-arg)
    // Assert: 5-arg overload NOT called
}

@Test
fun `auto_detect_plan with repo_root routes to 5-tier overload`() = runTest {
    val params = buildJsonObject {
        put("action", "auto_detect_plan")
        put("git_remote_url", "git@example.com:foo/bar.git")
        put("repo_root", "/home/user/repo")
        put("branch_name", "feature/X")
        put("preferred_master", "main")
    }
    bambooPlansTool.execute(params, project)
    // Assert: bambooService.autoDetectPlan("/home/user/repo", "git@example.com:foo/bar.git", "feature/X", "main") called (5-arg)
}

@Test
fun `auto_detect_plan with partial extra args still routes to 5-tier overload`() = runTest {
    val params = buildJsonObject {
        put("action", "auto_detect_plan")
        put("git_remote_url", "x")
        put("repo_root", "/r")
    }
    bambooPlansTool.execute(params, project)
    // Assert: 5-arg overload called with branch_name=null, preferred_master=null
}
```

- [ ] **Step 3: Run failing tests**

Expected: 4 FAIL.

- [ ] **Step 4: Register `get_projects` action + schema**

Add `"get_projects"` to action enum. Add schema entries for the new params:

```kotlin
"repo_root" to ParameterProperty(
    type = "string",
    description = "Optional. Local repo root path. When provided alongside git_remote_url, routes auto_detect_plan to the richer 5-tier detection (better for multi-module repos)."
),
"branch_name" to ParameterProperty(
    type = "string",
    description = "Optional. Branch to detect. Used alongside repo_root in auto_detect_plan's 5-tier overload."
),
"preferred_master" to ParameterProperty(
    type = "string",
    description = "Optional. Preferred master/main branch name. Used alongside repo_root in auto_detect_plan's 5-tier overload."
)
```

- [ ] **Step 5: Implement `get_projects` handler**

```kotlin
"get_projects" -> service.getProjects().toAgentToolResult()
```

- [ ] **Step 6: Update `auto_detect_plan` to route between overloads**

Modify the existing branch:

```kotlin
"auto_detect_plan" -> {
    val gitRemoteUrl = params["git_remote_url"]?.jsonPrimitive?.content
        ?: return ToolValidation.missingParam("git_remote_url")
    val repoRoot = params["repo_root"]?.jsonPrimitive?.content
    val branchName = params["branch_name"]?.jsonPrimitive?.content
    val preferredMaster = params["preferred_master"]?.jsonPrimitive?.content

    if (repoRoot == null && branchName == null && preferredMaster == null) {
        // Legacy 1-arg path
        service.autoDetectPlan(gitRemoteUrl).toAgentToolResult()
    } else {
        // Richer 5-tier overload
        service.autoDetectPlan(repoRoot ?: "", gitRemoteUrl, branchName, preferredMaster).toAgentToolResult()
    }
}
```

(Verify the 5-tier overload's parameter order against `BambooService.kt:83`.)

- [ ] **Step 7: Update action descriptions**

Add `get_projects()` and the new optional params to the KDoc.

- [ ] **Step 8: Run tests**

Run: `./gradlew :agent:test --tests "BambooPlansToolTest"`

Expected: all PASS.

- [ ] **Step 9: Run :agent suite**

Run: `./gradlew :agent:test`

- [ ] **Step 10: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BambooPlansTool.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/integration/BambooPlansToolTest.kt
git commit -m "$(cat <<'EOF'
feat(agent): bamboo_plans.get_projects + auto_detect_plan 5-tier overload

get_projects exposes the project listing the LLM previously
had to guess by grepping build-key prefixes.

auto_detect_plan now routes to the richer 5-tier overload
when repo_root/branch_name/preferred_master are supplied,
giving better detection on multi-module repos.
Backward-compatible: 1-arg call shape still works.

Closes audit gaps #6 and #8.
EOF
)"
```

---

## Self-Review

**Spec coverage:**
- [x] Gap #1 (Bamboo getBuildChanges) → Task 2
- [x] Gap #2 (Jira remote links + history) → Task 1
- [x] Gap #3 (Bitbucket PRs-for-branch) → Task 3
- [x] Gap #4 (Sonar rule) → Task 4
- [x] Gap #5 (Jira permissions) → Task 5
- [x] Gap #6 (Bamboo getProjects) → Task 6
- [x] Gap #7 (Sonar listFileComponents) → Task 4
- [x] Gap #8 (Bamboo 5-tier autoDetectPlan) → Task 6

All 8 gaps mapped to a task. EXTEND-vs-NEW split per audit recommendation: 5 extends (#1, #2, #5, #7, #8) + 3 new actions (#3, #4, #6).

**Placeholder scan:** No "TBD" / "add appropriate" / "similar to Task N" placeholders. All code snippets are complete. Field names like `BuildChangeData.sha`, `RemoteLinkData.applicationName`, `MyPermissionsData.canTransition` are best-guesses based on Atlassian REST conventions and the audit's references — implementer must verify against actual model files (instruction included in each Step 1).

**Type consistency:**
- `service.getRemoteLinks(key)` returns `ToolResult<List<RemoteLinkData>>` per `JiraService.kt:117`
- `service.getTicketHistory(key)` returns `ToolResult<List<TicketHistoryEntryData>>` per `:141`
- `service.getMyPermissions(projectKey)` returns `ToolResult<MyPermissionsData>` per `:111`
- `service.getBuildChanges(buildKey)` returns `ToolResult<List<BuildChangeData>>` per `BambooService.kt:118`
- `service.getProjects()` returns `ToolResult<List<BambooProjectData>>` per `:108`
- `service.getRule(key, repoName)` returns `ToolResult<SonarRuleData>` per `SonarService.kt:72`
- `service.listFileComponents(key, branch, repoName)` returns `ToolResult<List<String>>` (or similar) per `:82`
- `service.getPullRequestsForBranch(branchName, repoName)` returns `ToolResult<List<…>>` per `BitbucketService.kt:63`

Actual return types may differ slightly — implementer verifies in Step 1 of each task.

**Branch:** `fix/automation-handover-quality-tabs` (current).

**Commit count:** 6 (Tasks 1-6, one commit each). Task 5 builds on Task 1's refactored handler — must run sequentially in that order.

**Sequencing constraints:**
- Tasks 1 → 5 (sequential): Task 5 extends the fan-out structure introduced by Task 1.
- Tasks 2, 3, 4, 6 are independent of each other and of 1/5 — could run in parallel, but for safety the executor runs them sequentially.

Recommended order: 1 → 5 → 2 → 3 → 4 → 6.

---

## Audit reference

Source of truth: `docs/research/2026-05-07-agent-tool-gap-audit.md`. Each task's "Gap #N" tag corresponds to that document's "Genuine gaps prioritized by leverage" section.
