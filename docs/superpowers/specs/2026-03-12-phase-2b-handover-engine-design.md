# Phase 2B: Handover Engine — Design Specification

> **Module:** `:handover`
> **Gate:** 8 — End-to-end lifecycle (PRODUCTION-READY)
> **Depends on:** `:core` only (cross-module data via EventBus)
> **Prior art:** Phases 1A–1F (foundation, sprint, build, quality, Cody, health check), Phase 2A (automation orchestrator)

---

## 1. Overview

The Handover Engine is the final module in the Workflow Orchestrator plugin. It consolidates outputs from every prior phase into a single tab where developers perform the administrative work of closing out a task: creating PRs, fixing copyright headers, posting Jira comments with automation results, transitioning ticket status, logging time, and copying docker tags/links for QA handover.

**Design philosophy:** Each action is independent and non-blocking. There is no enforced sequential flow — developers trigger actions when they need them. An optional "Complete Task" macro button chains selected actions for developers who want one-click automation.

---

## 2. Scope

### In Scope (Phase 2B)

| # | Feature | Source |
|---|---------|--------|
| 1 | Copyright header auto-fix (year update + missing header injection) | features.md §5.1 |
| 2 | Cody pre-review (optional Spring Boot diff analysis) | features.md §5.3, CLAUDE.md Gate 8 |
| 3 | One-click PR creation (Bitbucket, Cody-generated description) | features.md §5.3, CLAUDE.md Gate 8 |
| 4 | Jira closure comment (docker tags + automation suite links) | requirement.md §VI, workflow.md §5, features.md §5.2 |
| 5 | Jira status transition ("In Progress" → "In Review") | requirement.md §VI, workflow.md §5 |
| 6 | Time tracking (daily worklog to Jira, max 7h) | features.md §6.4, CLAUDE.md Gate 8 |
| 7 | QA handover clipboard (copy docker tags + automation links) | User workflow (new addition) |
| 8 | Optional "Complete Task" macro button (chains selected actions) | features.md §5.2, user feedback |
| 9 | Handover tab with toolbar + detail panel UI (5th tab) | CLAUDE.md Gate 8 |

### Out of Scope (Deferred)

| Feature | Reason |
|---------|--------|
| Regression Blame & Auto-Triage (features.md §6.2) | Phase 2 advanced feature |
| Developer Productivity Dashboard (features.md §6.5) | Phase 2 advanced feature |
| Build Timeline View (features.md §3.2) | Phase 2 advanced feature |
| Inline Build Error Tracing (features.md §3.4) | Phase 2 advanced feature |
| Cody Epic Summarization (features.md §1.3) | Phase 2 advanced feature |
| Direct email sending (SMTP) | User prefers copy-to-clipboard |

---

## 3. Actual Developer Workflow

The plugin must respect this sequential ordering (established from user's real workflow):

```
1. Developer finishes code
2. Fix copyright headers (changed files only)
3. [Optional] Run Cody pre-review on diff
4. Create PR in Bitbucket → triggers Bamboo builds
5. Wait for Bamboo builds (Artifacts ✓, OSS ✓, Sonar ✓)
6. Take docker tag from successful build artifacts
7. Run 3-4 automation suites (Phase 2A handles this)
8. Wait for all suites to pass (no regressions)
9. Post automation suite links + docker tags as Jira comment
10. Transition Jira ticket status ("In Progress" → "In Review")
11. Copy docker tags + suite links for QA handover (paste into email/Slack)
12. Log work daily (max 7h, variable per ticket)
```

**Key insight:** PR creation happens BEFORE automation because Bamboo builds (which produce docker tags) only trigger after PR is raised. The Handover tab must reflect this natural ordering.

**Key insight:** Actions 2-11 happen at different times, sometimes across days. There is no single "complete task" moment. Time logging is daily, not per-task.

---

## 4. Module Architecture

### 4.1 File Structure

```
:handover
├── api/
│   └── BitbucketApiClient.kt         — Bitbucket Server REST API v1
├── service/
│   ├── CopyrightFixService.kt        — Auto-fix copyright years + inject missing headers
│   ├── PreReviewService.kt           — Cody diff analysis for Spring Boot patterns
│   ├── PrService.kt                  — Bitbucket PR creation orchestration
│   ├── JiraClosureService.kt         — Build + post Jira closure comment
│   ├── TimeTrackingService.kt        — Jira worklog integration
│   ├── QaClipboardService.kt         — Format docker tags + links for clipboard
│   ├── HandoverStateService.kt       — Accumulate suite results, track action status
│   └── CompletionMacroService.kt     — Chain multiple actions into one-click macro
├── model/
│   └── HandoverModels.kt             — PR data, closure comment, time log, clipboard payload
├── ui/
│   ├── HandoverTabProvider.kt        — WorkflowTabProvider implementation (5th tab)
│   ├── HandoverPanel.kt              — Main panel: toolbar + left context + right detail
│   ├── panels/
│   │   ├── CopyrightPanel.kt         — Copyright fix detail panel
│   │   ├── PreReviewPanel.kt         — Cody review findings panel
│   │   ├── PrCreationPanel.kt        — PR creation form panel
│   │   ├── JiraCommentPanel.kt       — Closure comment preview panel
│   │   ├── TimeLogPanel.kt           — Time logging panel
│   │   ├── QaClipboardPanel.kt       — Copy-to-clipboard panel
│   │   └── CompletionMacroPanel.kt   — Macro configuration panel
│   ├── HandoverContextPanel.kt       — Left sidebar: live context summary
│   └── HandoverToolbar.kt            — Top toolbar with action buttons
└── listeners/
    └── HandoverEventCollector.kt      — EventBus subscriber, accumulates cross-phase data
```

### 4.2 Dependency Rule

`:handover` depends ONLY on `:core`. No imports from `:jira`, `:bamboo`, `:sonar`, `:cody`, or `:automation`.

Cross-module data flows through:
- **EventBus** — subscribe to events from other modules
- **PluginSettings** — read service URLs, credentials, configuration
- **CredentialStore** — get PATs for Bitbucket, Jira API calls
- **Project file system** — `copyright.txt` for template, Git for diff/branch info

### 4.3 Service Dependencies

```
HandoverPanel
  ├── HandoverContextPanel (left sidebar)
  │     └── HandoverStateService → accumulates events from all phases
  │           └── EventBus (subscribes to BuildFinished, AutomationTriggered,
  │               AutomationFinished, QualityGateResult, HealthCheckFinished)
  │
  ├── HandoverToolbar → switches detail panels
  │
  └── Detail Panels (right side, one active at a time)
        ├── CopyrightPanel → CopyrightFixService
        │     └── Git diff (changed files) + copyright.txt (template)
        ├── PreReviewPanel → PreReviewService
        │     └── Git diff → Cody Agent chat/submitMessage
        ├── PrCreationPanel → PrService
        │     └── BitbucketApiClient + Cody (description gen)
        ├── JiraCommentPanel → JiraClosureService
        │     └── JiraApiClient.addComment() + HandoverStateService (suite results)
        ├── TimeLogPanel → TimeTrackingService
        │     └── JiraApiClient.logWork()
        ├── QaClipboardPanel → QaClipboardService
        │     └── HandoverStateService (docker tags + suite links)
        └── CompletionMacroPanel → CompletionMacroService
              └── Chains: JiraClosureService + transition + any selected actions
```

---

## 5. Feature Specifications

### 5.1 Copyright Header Auto-Fix

**Purpose:** Ensure all changed/new files have correct copyright headers with current year before PR creation.

**Template source:** Reads `copyright.txt` from the project repository root. This file contains the copyright text template with a `{year}` placeholder.

**Changed file detection:** Uses `git diff develop...HEAD --name-only` to get list of changed files. Only processes source files (`.java`, `.kt`, `.kts`, `.xml`, `.yaml`, `.yml`, `.properties`).

**Year update logic (for files with existing copyright):**

Consolidate all year references in the copyright header into a single range `{earliest}-{currentYear}`:

| Existing | Result (current year = 2026) |
|----------|------------------------------|
| `2025` | `2025-2026` |
| `2019-2025` | `2019-2026` |
| `2018, 2020-2023, 2025` | `2018-2026` |
| `2026` | `2026` (no change) |
| `2020-2026` | `2020-2026` (no change) |

Algorithm:
1. Parse all 4-digit year values from the copyright header region (first 15 lines)
2. Find the minimum year across all parsed values
3. If min year == current year, no change needed
4. If min year < current year, replace entire year expression with `{minYear}-{currentYear}`

**Missing header injection (for new files):**
1. Read `copyright.txt` from project root
2. Replace `{year}` placeholder with current year
3. Prepend to file with appropriate comment wrapping (`/* */` for Java/Kotlin, `<!-- -->` for XML)

**Simplification note:** features.md §5.1 mentions per-module copyright templates. Phase 2B uses a single `copyright.txt` from the project root. Per-module templates can be added later if needed.

**Edge cases:**
- `copyright.txt` doesn't exist → skip copyright fixing, show warning
- File already has current year → no modification
- Developer already added copyright manually → validate year is current, fix if not
- Binary files → skip
- Non-source files → skip (filter by extension)

**UI:** Panel shows list of changed files with status:
- ⚠ Yellow: year needs update (shows old → new)
- ✗ Red: missing header entirely
- ✓ Green: already correct

Buttons: "Fix All", "Rescan"

### 5.2 Cody Pre-Review (Optional)

**Purpose:** AI-powered diff analysis for Spring Boot anti-patterns before PR creation. Non-blocking — developer can trigger anytime or skip entirely.

**Trigger:** Manual "Analyze" button in the Cody Review panel. NOT automatic.

**Diff scope:** `git diff develop...HEAD` — full branch diff for comprehensive review.

**Detection patterns (Spring Boot specific):**
- Missing `@Transactional` on service methods with DB writes
- Unclosed resources (streams, connections)
- Missing error handling on external API calls
- N+1 query patterns in JPA repositories
- Missing input validation on controller endpoints

**Implementation:** Send diff to Cody Agent via `chat/submitMessage` JSON-RPC with a Spring Boot review prompt. Parse response into structured findings.

**Finding model:**
```kotlin
data class ReviewFinding(
    val severity: FindingSeverity,   // HIGH, MEDIUM, LOW
    val filePath: String,
    val lineNumber: Int,
    val message: String,
    val pattern: String              // e.g., "missing-transactional"
)

enum class FindingSeverity { HIGH, MEDIUM, LOW }
```

**UI:** Panel shows findings list sorted by severity. Each finding is clickable → navigates to the file:line in editor. "Re-analyze" button to refresh. "Fix with Cody" button sends finding to Cody's `editCommands/code` for auto-fix.

**Edge cases:**
- Cody Agent not running → show "Cody unavailable" message with retry
- Large diff (>10,000 lines) → warn user, suggest analyzing per-file
- No findings → show "No issues found" success state
- Network timeout → show error, allow retry

### 5.3 One-Click PR Creation (Bitbucket)

**Purpose:** Create a Bitbucket pull request with minimal developer input. Auto-populates everything.

**Auto-populated fields:**
- **Title:** `{ticketId}: {ticketSummary}` (from `ActiveTicketService` via PluginSettings)
- **Description:** Cody-generated from `git diff develop...HEAD` + Jira ticket context. Developer can regenerate or edit before submission.
- **Source branch:** Current Git branch (from Git4Idea API)
- **Target branch:** Configurable default in settings (default: `develop`)
- **Reviewers:** From Bitbucket defaults (not configured in plugin — Bitbucket handles reviewer rules)

**Bitbucket project/repo auto-detection:**
1. Parse Git remote URL (`git remote get-url origin`)
2. Extract project key and repo slug from URL patterns:
   - SSH: `ssh://git@bitbucket.example.com/{project}/{repo}.git`
   - HTTPS: `https://bitbucket.example.com/scm/{project}/{repo}.git`
3. Fallback to settings fields `bitbucketProjectKey` and `bitbucketRepoSlug`

**API:** Bitbucket Server REST API v1
```
POST /rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/pull-requests
Authorization: Bearer <PAT>
Content-Type: application/json

{
  "title": "PROJ-123: Add login feature",
  "description": "Cody-generated description...",
  "fromRef": { "id": "refs/heads/feature/PROJ-123-add-login" },
  "toRef": { "id": "refs/heads/develop" }
}
```

**Response:** Contains `id` (PR number) and `links.self.href` (PR URL).

**Post-creation:** Store PR URL in `HandoverStateService` for display in context panel. Show success notification with clickable link.

**UI:** Panel shows auto-populated fields (read-only except description). "Create PR" button. "Regenerate Description" button. After creation, shows PR URL as clickable link.

**Edge cases:**
- Branch not pushed to origin → show "Push branch first" warning
- PR already exists for this branch → detect via Bitbucket API, show link to existing PR
- Bitbucket URL not configured → show settings link
- No Git remote → show error
- Cody unavailable for description → use simple template fallback: `{ticketId}: {ticketSummary}\n\nBranch: {branch}`

### 5.4 Jira Closure Comment

**Purpose:** Post a structured comment on the Jira ticket with automation suite results, docker tags, and Bamboo links.

**Comment content (wiki markup format):**
```
h4. Automation Results
|| Suite || Status || Link ||
| Regression Suite A | (/) PASS | [View Results|https://bamboo.example.com/browse/RS-42] |
| Regression Suite B | (/) PASS | [View Results|https://bamboo.example.com/browse/RS-43] |
| Smoke Tests | (/) PASS | [View Results|https://bamboo.example.com/browse/ST-18] |

h4. Docker Tags
{code:json}
{
  "my-service": "1.2.3-build.42",
  "auth-service": "release-2.0.1"
}
{code}
```

**Data sources:**
- **Suite results:** Accumulated from `AutomationTriggered` and `AutomationFinished` events by `HandoverStateService`. Each suite contributes: plan key, build result key (→ Bamboo link), passed/failed, docker tags JSON.
- **Docker tags:** From `AutomationTriggered.dockerTagsJson`
- **Bamboo links:** Constructed as `{bambooUrl}/browse/{buildResultKey}`

**API (NEW — extend JiraApiClient):**
```
POST /rest/api/2/issue/{issueKey}/comment
Authorization: Bearer <PAT>
Content-Type: application/json

{
  "body": "<wiki markup content>"
}
```

**UI:** Panel shows comment preview rendered from accumulated suite data. "Edit" button allows manual modification before posting. "Post Comment" button. After posting, shows confirmation.

**Edge cases:**
- No automation suites run yet → show "No automation results to post" with empty state
- Some suites passed, some failed → show all with appropriate status icons
- Jira unreachable → show error, allow retry
- Multiple runs for same suite → use latest result

### 5.5 Jira Status Transition

**Purpose:** Transition Jira ticket from "In Progress" to "In Review" (or other configurable target status).

**Implementation:** Already partially supported by `JiraApiClient.getTransitions()` + `transitionIssue()`. The Handover module wraps this with:
1. Fetch available transitions for current ticket
2. Show dropdown of valid target statuses
3. Execute transition on button click

**UI:** Located in the left context panel, next to the current ticket status display. A dropdown + "Transition" button. Shows current status prominently.

**Edge cases:**
- "In Review" transition not available (wrong workflow state) → show available transitions
- Ticket already in target status → show "Already in {status}" message
- Transition requires fields (e.g., resolution) → show additional field dialog

### 5.6 Time Tracking

**Purpose:** Log work to Jira. Daily, manual, max 7h per day, variable amount per ticket.

**"Start Work" timestamp:**
Phase 2B extends Phase 1B's workflow: when "Start Work" is clicked (branch creation + Jira transition), persist a `startWorkTimestamp` in `PluginSettings`. This serves as a hint for elapsed time calculation but does NOT auto-fill — the user enters their own hours.

**New PluginSettings fields:**
```kotlin
var startWorkTimestamp by property(0L)  // epoch millis, 0 = not set
```

**API (NEW — extend JiraApiClient):**
```
POST /rest/api/2/issue/{issueKey}/worklog
Authorization: Bearer <PAT>
Content-Type: application/json

{
  "timeSpentSeconds": 14400,
  "comment": "Optional work description",
  "started": "2026-03-12T09:00:00.000+0000"
}
```

**UI:** Panel shows:
- Current ticket ID
- Date (defaults to today, can change)
- Hours input (stepper with +/- buttons, max 7h, step 0.5h)
- Elapsed time hint (if startWorkTimestamp set): "You started working X hours ago"
- Optional comment field
- "Log Work" button

**Edge cases:**
- Log 0 hours → disable button
- Log > 7h → clamp to 7h with warning
- No active ticket → show "No active ticket" empty state
- Jira unreachable → show error, allow retry
- Multiple logs per day → allowed (Jira supports multiple worklogs)

### 5.7 QA Handover Clipboard

**Purpose:** Present docker tags and automation suite links in a formatted, copyable layout. Developer copies and pastes into email, Slack, or any communication channel.

**Formatted output:**
```
Docker Tags:
  • my-service: 1.2.3-build.42
  • auth-service: release-2.0.1

Automation Results:
  • Regression Suite A: PASS — https://bamboo.example.com/browse/RS-42
  • Regression Suite B: PASS — https://bamboo.example.com/browse/RS-43
  • Smoke Tests: PASS — https://bamboo.example.com/browse/ST-18

Tickets: PROJ-123, PROJ-456
```

**Data sources:** Same as Jira comment (§5.4) — from `HandoverStateService` accumulated suite results.

**UI:** Panel shows the formatted text in a read-only text area. "Copy All" button copies to system clipboard. "Add Service" button to manually add additional docker tags (for multi-service batching across tickets). Individual "Copy" buttons next to each docker tag for quick single-tag copy.

**Edge cases:**
- No suite results → show empty state "Run automation suites first"
- Manually added tags persist for the session only (not saved to disk)

### 5.8 "Complete Task" Macro

**Purpose:** Optional one-click automation that chains multiple handover actions. For developers who want to automate their repetitive closure workflow.

**Configurable chain:** Developer selects which actions to include:
- [ ] Fix copyright headers
- [ ] Post Jira comment (with suite results)
- [ ] Transition Jira to "In Review"
- [ ] Log work

**Note:** PR creation and Cody pre-review are intentionally excluded from the macro — they happen at a different workflow stage (pre-automation) and require interactive review (description editing, finding inspection). The macro targets post-automation closure actions.

Each action executes sequentially. If any action fails, the chain stops and shows which action failed. Already-completed actions are not re-run.

**UI:** Panel shows checkboxes for each chainable action. "Run Macro" button. Progress indicator shows which step is executing. Results show per-action pass/fail.

**Edge cases:**
- Prerequisite not met (e.g., no suite results for Jira comment) → warn before starting
- Partial failure → show which actions succeeded and which failed
- User cancels mid-chain → stop cleanly, show partial results

---

## 6. New Event Types

Add to `WorkflowEvent.kt` in `:core`:

```kotlin
/** Emitted by :handover when a PR is created via Bitbucket. */
data class PullRequestCreated(
    val prUrl: String,
    val prNumber: Int,
    val ticketId: String
) : WorkflowEvent()

/** Emitted by :handover when a Jira closure comment is posted. */
data class JiraCommentPosted(
    val ticketId: String,
    val commentId: String
) : WorkflowEvent()

/** Emitted by :handover when Cody pre-review completes. */
data class PreReviewFinished(
    val findingsCount: Int,
    val highSeverityCount: Int
) : WorkflowEvent()
```

---

## 7. HandoverStateService — Event Accumulator

The central service that collects cross-phase data for the Handover tab. This is the key integration point.

### 7.1 Responsibilities

- Subscribe to EventBus for events from all phases
- Accumulate automation suite results (multiple suites per ticket)
- Track which handover actions have been completed
- Provide reactive state flows for UI binding

### 7.2 State Model

```kotlin
data class HandoverState(
    // Current ticket context (from PluginSettings)
    val ticketId: String,
    val ticketSummary: String,
    val currentBranch: String,

    // PR status
    val prUrl: String?,
    val prCreated: Boolean,

    // Build status (from BuildFinished events)
    val buildStatus: BuildSummary?,

    // Quality gate (from QualityGateResult events)
    val qualityGatePassed: Boolean?,

    // Automation suites (accumulated from AutomationTriggered + AutomationFinished)
    val suiteResults: List<SuiteResult>,

    // Action completion tracking
    val copyrightFixed: Boolean,
    val jiraCommentPosted: Boolean,
    val jiraTransitioned: Boolean,
    val todayWorkLogged: Boolean,

    // Start work timestamp (from PluginSettings)
    val startWorkTimestamp: Long
)

data class SuiteResult(
    val suitePlanKey: String,
    val buildResultKey: String,
    val dockerTagsJson: String,
    val passed: Boolean?,           // null = still running
    val durationMs: Long?,
    val triggeredAt: Instant,
    val bambooLink: String          // constructed from bambooUrl + buildResultKey
)

data class BuildSummary(
    val buildNumber: Int,
    val status: WorkflowEvent.BuildEventStatus,
    val planKey: String
)
```

### 7.3 Event Subscriptions

| Event | Action |
|-------|--------|
| `BuildFinished` | Update `buildStatus` |
| `QualityGateResult` | Update `qualityGatePassed` |
| `AutomationTriggered` | Add to `suiteResults` with `passed=null` |
| `AutomationFinished` | Update matching suite in `suiteResults` with `passed` and `durationMs` |
| `PullRequestCreated` | Update `prUrl`, set `prCreated=true` |
| `JiraCommentPosted` | Set `jiraCommentPosted=true` |

### 7.4 Lifecycle

- Created as a PROJECT-level service implementing `Disposable`
- Dual constructor pattern (following `BuildMonitorService`):
  - `constructor(project: Project)` — for IntelliJ DI (plugin.xml registration)
  - `constructor(eventBus: EventBus, settings: PluginSettings)` — for unit testing with mocks
- Subscribes to EventBus in constructor; cancels coroutine scope in `dispose()`
- Resets accumulated data when active ticket changes (PluginSettings listener)
- Exposes `stateFlow: StateFlow<HandoverState>` for UI binding
- `SuiteResult.triggeredAt` captured as `Instant.now()` when `AutomationTriggered` event arrives (the event itself has no timestamp field)

**All services in `:handover` must follow the dual constructor pattern** for testability.

---

## 8. BitbucketApiClient

New API client following the established `BambooApiClient` pattern (OkHttp, `ApiResult<T>`, `AuthInterceptor`).

### 8.1 Authentication

Bitbucket Server uses **Bearer** token auth (HTTP Access Token):
```
Authorization: Bearer <PAT>
```

### 8.2 Endpoints

```kotlin
class BitbucketApiClient(
    private val baseUrl: String,
    private val tokenProvider: () -> String?
) {
    /** Test connection. */
    suspend fun testConnection(): ApiResult<Unit>
    // GET /rest/api/1.0/users

    /** Create a pull request. */
    suspend fun createPullRequest(
        projectKey: String,
        repoSlug: String,
        title: String,
        description: String,
        fromBranch: String,
        toBranch: String
    ): ApiResult<BitbucketPrResponse>
    // POST /rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/pull-requests

    /** Get existing PRs for a branch (to detect duplicates). */
    suspend fun getPullRequestsForBranch(
        projectKey: String,
        repoSlug: String,
        branchName: String
    ): ApiResult<List<BitbucketPrResponse>>
    // GET /rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/pull-requests?at=refs/heads/{branch}&state=OPEN
}
```

### 8.3 DTOs

```kotlin
@Serializable
data class BitbucketPrResponse(
    val id: Int,
    val title: String,
    val state: String,                    // OPEN, MERGED, DECLINED
    val links: BitbucketLinks
)

@Serializable
data class BitbucketLinks(
    val self: List<BitbucketLink>
)

@Serializable
data class BitbucketLink(
    val href: String
)
```

---

## 9. JiraApiClient Extensions

Add two new methods to the existing `JiraApiClient` in `:jira` module.

**Wait — dependency rule violation.** The `:handover` module cannot import from `:jira`. Two options:

**Option chosen: Duplicate a lightweight Jira HTTP client in `:handover`.**

Since `:handover` only needs `addComment` and `logWork` (plus the existing `transitionIssue` and `getTransitions`), create a `HandoverJiraClient` in `:handover/api/` that wraps these specific calls using `:core`'s HTTP infrastructure. This maintains the dependency rule while avoiding full client duplication.

Alternatively, the comment/worklog methods could be added to a new `:core` level Jira utility, but since only `:handover` needs them, keeping them local is cleaner.

```kotlin
class HandoverJiraClient(
    private val baseUrl: String,
    private val tokenProvider: () -> String?
) {
    /** Post a wiki-markup comment on a Jira issue. */
    suspend fun addComment(issueKey: String, wikiMarkupBody: String): ApiResult<JiraCommentResponse>
    // POST /rest/api/2/issue/{issueKey}/comment
    // Body: {"body": "<wiki markup>"}

    /** Log work on a Jira issue. */
    suspend fun logWork(
        issueKey: String,
        timeSpentSeconds: Int,
        comment: String?,
        started: String          // ISO 8601 format
    ): ApiResult<Unit>
    // POST /rest/api/2/issue/{issueKey}/worklog
    // Body: {"timeSpentSeconds": N, "comment": "...", "started": "..."}

    /** Get available transitions for an issue. */
    suspend fun getTransitions(issueKey: String): ApiResult<List<JiraTransition>>
    // GET /rest/api/2/issue/{issueKey}/transitions

    /** Execute a transition on an issue. */
    suspend fun transitionIssue(issueKey: String, transitionId: String): ApiResult<Unit>
    // POST /rest/api/2/issue/{issueKey}/transitions
    // Body: {"transition": {"id": "<id>"}}
}

// Note: No testConnection() needed — Jira connectivity is already verified
// via the :jira module's test connection in the Settings page.
```

---

## 10. CopyrightFixService

**Approach note:** CLAUDE.md recommends `com.intellij.copyright.updater` extension point for copyright headers. However, the IntelliJ copyright updater only handles insertion/replacement of entire copyright blocks — it does NOT support the year-range consolidation logic required here (e.g., `2018, 2020-2023, 2025` → `2018-2026`). The copyright updater's `DateInfo` only supports simple current-year insertion. Therefore, `CopyrightFixService` implements custom year-range logic while using IntelliJ VFS APIs for file operations.

### 10.1 Template Loading

```kotlin
fun loadTemplate(project: Project): String? {
    val basePath = project.basePath ?: return null
    val vFile = LocalFileSystem.getInstance().findFileByPath("$basePath/copyright.txt")
    return vFile?.let { String(it.contentsToByteArray(), it.charset) }
}
```

### 10.2 Year Parsing & Update

```kotlin
private val YEAR_PATTERN = Regex("""\b((?:19|20)\d{2})\b""")
private val YEAR_RANGE_PATTERN = Regex("""((?:19|20)\d{2})\s*[-–]\s*((?:19|20)\d{2})""")
// Matches the entire year expression region: "2018, 2020-2023, 2025" or "2019-2025" or "2026"
private val FULL_YEAR_EXPR = Regex("""(?:(?:19|20)\d{2})(?:\s*[-–,]\s*(?:(?:19|20)\d{2}))*""")

fun updateYearInHeader(headerText: String, currentYear: Int): String {
    // 1. Find the first year expression in the header
    val yearExprMatch = FULL_YEAR_EXPR.find(headerText) ?: return headerText
    val yearExpr = yearExprMatch.value

    // 2. Parse all individual years and year ranges from the expression
    val allYears = mutableSetOf<Int>()
    YEAR_RANGE_PATTERN.findAll(yearExpr).forEach { match ->
        val start = match.groupValues[1].toInt()
        val end = match.groupValues[2].toInt()
        allYears.addAll(start..end)
    }
    YEAR_PATTERN.findAll(yearExpr).forEach { match ->
        allYears.add(match.groupValues[1].toInt())
    }

    if (allYears.isEmpty()) return headerText

    val minYear = allYears.min()

    // 3. If already has current year in correct format, no change needed
    val replacement = if (minYear == currentYear) "$currentYear" else "$minYear-$currentYear"
    if (yearExpr == replacement) return headerText

    // 4. Replace the year expression with consolidated range
    return headerText.replaceRange(yearExprMatch.range, replacement)
}
```

### 10.3 Comment Wrapping

```kotlin
fun wrapForLanguage(template: String, fileExtension: String): String {
    return when (fileExtension) {
        "java", "kt", "kts" -> "/*\n${template.lines().joinToString("\n") { " * $it" }}\n */"
        "xml" -> "<!--\n$template\n-->"
        "properties", "yaml", "yml" -> template.lines().joinToString("\n") { "# $it" }
        else -> template
    }
}
```

### 10.4 File Write Operations

All file modifications use `WriteCommandAction.runWriteCommandAction()` on the EDT with IntelliJ's `Document` API, NOT `java.io.File`:

```kotlin
WriteCommandAction.runWriteCommandAction(project) {
    val document = FileDocumentManager.getInstance().getDocument(virtualFile)
    document?.let { doc ->
        // Prepend copyright header or update year range
        doc.setText(updatedContent)
    }
}
```

---

## 11. New PluginSettings Fields

Add inside `class State : BaseState()` in `PluginSettings.kt`:

```kotlin
// Phase 2B: Handover settings (add after existing fields in State class)
var defaultTargetBranch by string("develop")
var bitbucketProjectKey by string("")       // auto-detected from Git remote
var bitbucketRepoSlug by string("")         // auto-detected from Git remote
var startWorkTimestamp by property(0L)       // epoch millis, set by "Start Work"
```

---

## 12. UI Design

### 12.1 Layout: Toolbar + Split Panel

```
┌─────────────────────────────────────────────────────────────────┐
│ [©️ Copyright] [🤖 Cody] [🔀 PR] [💬 Jira] [⏱ Time] [📋 QA] [⚡ Macro] │  ← Toolbar
├──────────────────────┬──────────────────────────────────────────┤
│ Context Summary      │ Active Detail Panel                     │
│                      │                                         │
│ ── Current Ticket    │ (switches based on toolbar selection)   │
│ PROJ-123             │                                         │
│ Add login feature    │                                         │
│ Status: In Progress  │                                         │
│ [Transition ▾]       │                                         │
│                      │                                         │
│ ── Pull Request      │                                         │
│ ✓ PR #42 → develop   │                                         │
│                      │                                         │
│ ── Bamboo Builds     │                                         │
│ ✓ Artifacts (b.42)   │                                         │
│ ✓ OSS Scan           │                                         │
│ ✓ Sonar              │                                         │
│                      │                                         │
│ ── Docker Tag        │                                         │
│ my-service:1.2.3-42  │                                         │
│                      │                                         │
│ ── Automation Suites │                                         │
│ ✓ Suite A  [link]    │                                         │
│ ✓ Suite B  [link]    │                                         │
│ ⏳ Suite C  running   │                                         │
│                      │                                         │
│ ── Actions Done      │                                         │
│ ✓ Copyright fixed    │                                         │
│ ✓ PR created         │                                         │
│ ○ Jira comment       │                                         │
│ ○ Time logged (0h)   │                                         │
└──────────────────────┴──────────────────────────────────────────┘
```

### 12.2 Toolbar Order

Ordered by natural workflow sequence:
1. **©️ Copyright** — fix headers (pre-PR)
2. **🤖 Cody** — pre-review (pre-PR, optional)
3. **🔀 PR** — create pull request
4. **💬 Jira** — post closure comment
5. **⏱ Time** — log work
6. **📋 QA** — copy tags/links for QA
7. **⚡ Macro** — chain actions

### 12.3 Left Context Panel

Always visible. Subscribes to `HandoverStateService.stateFlow`. Updates reactively as events arrive from other modules. The Jira transition dropdown is embedded here (next to ticket status) for quick access.

### 12.4 IntelliJ UI Components

| Element | JetBrains Component |
|---------|-------------------|
| Toolbar buttons | `ActionToolbar` with `AnAction` per button |
| Left/right split | `JBSplitter` (vertical) |
| File list (copyright) | `JBList<CopyrightFileEntry>` |
| Comment preview | `JBTextArea` (read-only, monospace) |
| Hours stepper | `JBTextField` + custom +/- buttons |
| Transition dropdown | `ComboBox<JiraTransition>` |
| Copy button | `JButton` with `CopyPasteManager` |
| Findings list | `JBList<ReviewFinding>` with severity renderer |
| Status icons | `AllIcons.General.InspectionsOK`, `AllIcons.General.Error`, `AllIcons.General.Warning` |

---

## 13. plugin.xml Extensions

```xml
<!-- Phase 2B: Handover Services -->
<projectService
    serviceImplementation="com.workflow.orchestrator.handover.service.HandoverStateService"/>
<projectService
    serviceImplementation="com.workflow.orchestrator.handover.service.CopyrightFixService"/>
<projectService
    serviceImplementation="com.workflow.orchestrator.handover.service.PreReviewService"/>
<projectService
    serviceImplementation="com.workflow.orchestrator.handover.service.PrService"/>
<projectService
    serviceImplementation="com.workflow.orchestrator.handover.service.JiraClosureService"/>
<projectService
    serviceImplementation="com.workflow.orchestrator.handover.service.TimeTrackingService"/>
<projectService
    serviceImplementation="com.workflow.orchestrator.handover.service.QaClipboardService"/>
<projectService
    serviceImplementation="com.workflow.orchestrator.handover.service.CompletionMacroService"/>

<!-- Handover Tab -->
<extensions defaultExtensionNs="com.workflow.orchestrator">
    <tabProvider implementation="com.workflow.orchestrator.handover.ui.HandoverTabProvider"/>
</extensions>

<!-- Notification Group -->
<notificationGroup id="workflow.handover" displayType="BALLOON"/>
```

---

## 14. Testing Strategy

| Component | Test Approach | Framework |
|-----------|--------------|-----------|
| BitbucketApiClient | MockWebServer fixtures | OkHttp MockWebServer + JUnit 5 |
| HandoverJiraClient | MockWebServer fixtures | OkHttp MockWebServer + JUnit 5 |
| CopyrightFixService (year logic) | Pure unit tests | JUnit 5 + assertions |
| CopyrightFixService (file operations) | Temp directory + file I/O | JUnit 5 + TempDir |
| HandoverStateService (event accumulation) | EventBus mock + state verification | MockK + Turbine |
| QaClipboardService (formatting) | Pure unit tests | JUnit 5 |
| JiraClosureService (comment building) | Pure unit tests | JUnit 5 |
| PreReviewService | Mock CodyAgentManager | MockK |
| PrService (orchestration) | Mock BitbucketApiClient + CodyAgentManager | MockK |
| CompletionMacroService (chaining) | Mock individual services | MockK |
| HandoverPanel | Manual `runIde` verification | — |

---

## 15. Edge Cases

### Copyright
1. `copyright.txt` missing from repo → warning banner, copyright panel disabled
2. File with no copyright but is a generated file (build output) → skip by path pattern
3. Copyright header contains non-year numbers (e.g., version "3.0") → only match 4-digit years in 19xx/20xx range
4. Multiple copyright blocks in one file → only process the first one (top 15 lines)
5. File encoding is not UTF-8 → detect encoding, preserve on write

### Bitbucket
6. PR already exists for branch → detect and show link, disable "Create PR"
7. Branch not pushed → show "Push first" warning
8. Merge conflicts in target branch → Bitbucket rejects PR, show error
9. Bitbucket returns 409 (conflict) → already exists, fetch and show link
10. Repository is a fork → detect, use fork's project/repo keys

### Jira
11. Ticket has no available "In Review" transition → show dropdown of what IS available
12. Comment contains special characters → escape for wiki markup
13. Worklog with 0 seconds → prevent submission
14. Worklog date in the future → prevent (Jira rejects this)
15. Ticket closed by someone else → handle 403/404 gracefully

### Automation Data
16. Multiple runs of same suite → use latest result only
17. Suite still running when handover actions triggered → show "running" status, allow comment with partial results
18. No suites run → disable Jira comment and QA clipboard panels (no data)
19. Docker tags JSON malformed → show raw text with warning
20. Suite triggered from different IDE/user → still collected if events arrive

### Cody Pre-Review
21. Cody Agent crashed → show "unavailable" with restart button
22. Review takes >60 seconds → show progress indicator
23. Response is not parseable as findings → show raw response
24. Empty diff → show "no changes to review"

### General
25. Plugin settings change mid-workflow → refresh HandoverStateService
26. IDE restart mid-handover → HandoverStateService resets, user re-triggers needed actions
27. Multiple projects open → each project has independent HandoverStateService
28. Active ticket changes → reset all accumulated state

---

## 16. Notification Group

**Note:** CLAUDE.md specifies 4 notification groups. Phase 2B adds a 5th (`workflow.handover`) because handover actions are user-initiated and distinct from automated events — mixing them into `workflow.automation` would confuse notification preferences.

```
workflow.handover
  ├── PR_CREATED        — "PR #42 created for PROJ-123"
  ├── PR_CREATION_FAILED — "Failed to create PR: {reason}"
  ├── JIRA_COMMENT_POSTED — "Comment posted on PROJ-123"
  ├── WORKLOG_POSTED    — "Logged 4h on PROJ-123"
  ├── COPYRIGHT_FIXED   — "Fixed copyright headers in 3 files"
  └── MACRO_COMPLETED   — "Complete Task macro finished (3/3 actions)"
```

---

## 17. Dependencies Summary

### New Libraries
None — uses existing OkHttp, kotlinx.serialization, and IntelliJ Platform APIs.

### New Gradle Module
`:handover` — follows same pattern as `:bamboo` (see `bamboo/build.gradle.kts`).

### Modified Files in Other Modules
1. `core/src/main/kotlin/.../events/WorkflowEvent.kt` — add 3 new event types
2. `core/src/main/kotlin/.../settings/PluginSettings.kt` — add 4 new fields
3. `src/main/resources/META-INF/plugin.xml` (project root) — register handover services, tab, notifications
4. `settings.gradle.kts` — add `:handover` to includes
5. `build.gradle.kts` — add `:handover` to composed plugin dependencies

---

## 18. Phase 2B Verification Checklist (Gate 8)

- [ ] Handover tab visible as 5th tab in Workflow tool window
- [ ] Copyright fix scans changed files, updates years, injects missing headers from `copyright.txt`
- [ ] Cody pre-review analyzes diff for Spring Boot anti-patterns (optional, non-blocking)
- [ ] PR created in Bitbucket with Cody-generated description
- [ ] Jira closure comment posted with docker tags + automation suite links
- [ ] Jira ticket transitioned to "In Review"
- [ ] Time logged to Jira worklog (max 7h)
- [ ] QA clipboard shows formatted docker tags + suite links with Copy button
- [ ] "Complete Task" macro chains Jira comment + transition + worklog
- [ ] Left context panel shows live data from all prior phases
- [ ] All services use suspend functions on Dispatchers.IO (never block EDT)
- [ ] `./gradlew :handover:test` passes
- [ ] `./gradlew verifyPlugin` passes
- [ ] `./gradlew buildPlugin` produces installable ZIP
- [ ] **PRODUCTION-READY**
