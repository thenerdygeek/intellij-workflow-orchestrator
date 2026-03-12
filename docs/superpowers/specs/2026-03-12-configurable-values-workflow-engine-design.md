# Configurable Values & Intent-Based Workflow Engine — Design Specification

> **Scope:** Cross-cutting extraction of 12 hardcoded values into user-configurable settings, centered on a new intent-based Jira workflow engine that replaces hardcoded status strings
> **Modules affected:** `:core` (settings), `:jira` (workflow engine, API client), `:handover` (closure, time tracking, macro, UI), `:sonar` (coverage thresholds), `:automation` (queue limits)
> **Backward compatibility:** All new settings have defaults that match current hardcoded values — zero-config upgrade for existing users

---

## 1. Overview

The plugin currently hardcodes 12 categories of values that vary across enterprise teams: Jira workflow states, coverage thresholds, HTTP timeouts, queue limits, and more. This spec covers extracting all 12 into `PluginSettings` with a settings UI, with the centerpiece being an **intent-based workflow engine** that replaces hardcoded Jira status strings ("In Progress", "In Review") with a dynamic resolution system that works with any Jira workflow.

### 1.1 Problem Statement

**Jira workflow states** are the highest-impact issue. The plugin hardcodes `"In Progress"` in `BranchingService.kt:62` and `"In Review"` in `HandoverContextPanel.kt:88` and `CompletionMacroService.kt:22`. Enterprise Jira instances commonly use custom status names ("Begin Development", "Code Review", "QA Ready") that don't match these strings. The plugin's `statusCategory` fallback helps but is insufficient — multiple transitions can share the same category, leading to wrong-transition selection.

**Other hardcoded values** (coverage thresholds, timeouts, queue limits) are simpler extractions but still block teams with different infrastructure or quality standards.

### 1.2 Design Philosophy

- **Zero-config for standard workflows:** Default values match Jira's standard workflow. Teams with standard setups never touch settings.
- **Dynamic discovery over static configuration:** The workflow engine queries Jira's transitions API at runtime rather than requiring users to pre-configure the full workflow graph.
- **Learn-and-remember:** When the engine resolves an intent to a specific transition, it persists the mapping. Subsequent resolutions are instant.
- **Graceful disambiguation:** When multiple transitions match, the plugin presents a chooser and remembers the user's choice.

---

## 2. Intent-Based Workflow Engine

### 2.1 Why Not a Static State Machine?

Jira Server's REST API does **not** expose the full workflow graph. The `GET /rest/api/2/issue/{key}/transitions` endpoint returns only transitions available from the issue's **current** status for the **current** user (`JRASERVER-66295` is unresolved). Additionally:

- Different issue types in the same project can have entirely different workflows
- Workflow admins can modify transitions at any time
- Custom workflows vary wildly across enterprises

A static state machine (e.g., KStateMachine) would require the user to manually configure every state and transition — a configuration burden that defeats the purpose of the plugin.

### 2.2 Architecture

```
User Action  →  WorkflowIntent  →  IntentResolver  →  GuardChain  →  FieldResolver  →  TransitionExecutor
"Start Work"    START_WORK         resolves to          checks          discovers         POST /transitions
                                   transition ID        prerequisites   required fields   with field values
```

#### Layer 1 — Semantic Intents (`:core`)

```kotlin
// core/src/main/kotlin/.../workflow/WorkflowIntent.kt
enum class WorkflowIntent(
    val displayName: String,
    val defaultNames: List<String>,
    val targetCategory: String?
) {
    START_WORK(
        displayName = "Start Work",
        defaultNames = listOf("In Progress", "Start Progress", "Begin Work",
                              "Start Development", "Begin Development"),
        targetCategory = "indeterminate"
    ),
    SUBMIT_FOR_REVIEW(
        displayName = "Submit for Review",
        defaultNames = listOf("In Review", "Submit for Review", "Ready for Review",
                              "Code Review", "Peer Review"),
        targetCategory = "indeterminate"
    ),
    CLOSE(
        displayName = "Close",
        defaultNames = listOf("Done", "Closed", "Resolved", "Complete", "Finished"),
        targetCategory = "done"
    ),
    REOPEN(
        displayName = "Reopen",
        defaultNames = listOf("Reopen", "Re-open", "Back to Open", "Backlog"),
        targetCategory = "new"
    ),
    BLOCK(
        displayName = "Block",
        defaultNames = listOf("Blocked", "On Hold", "Impediment"),
        targetCategory = null  // No universal category for blocked
    )
}
```

Intents are defined in `:core` so any module can reference them via the event bus without depending on `:jira`.

#### Layer 2 — Intent Resolver (`:jira`)

```kotlin
// jira/src/main/kotlin/.../workflow/IntentResolver.kt

data class ResolvedTransition(
    val transitionId: String,
    val transitionName: String,
    val targetStatusName: String,
    val requiredFields: List<TransitionField>,
    val resolution: ResolutionMethod
)

enum class ResolutionMethod {
    EXPLICIT_MAPPING,   // User configured this mapping in settings
    LEARNED_MAPPING,    // Auto-learned from a previous disambiguation
    NAME_MATCH,         // Matched against intent's defaultNames list
    CATEGORY_MATCH,     // Matched against intent's targetCategory
    USER_SELECTED       // User just picked from disambiguation dialog
}

class IntentResolver(
    private val project: Project,
    private val apiClient: JiraApiClient,
    private val mappingStore: TransitionMappingStore
) {
    suspend fun resolve(
        intent: WorkflowIntent,
        issueKey: String
    ): ApiResult<ResolvedTransition>
}
```

**Resolution strategy** (evaluated in order, first match wins):

1. **Explicit mapping** — check `TransitionMappingStore` for a user-configured mapping for this intent + project key (+ optional issue type)
2. **Learned mapping** — check `TransitionMappingStore` for a previously auto-learned mapping
3. **Name matching** — call `GET /issue/{key}/transitions?expand=transitions.fields`, iterate `intent.defaultNames`, find first case-insensitive match against available transition names
4. **Category matching** — from same API response, find transition whose `to.statusCategory.key` matches `intent.targetCategory`. If exactly one match, use it. If multiple matches, proceed to step 5.
5. **Disambiguation** — present a dialog listing available matching transitions, let user pick. Persist choice as a learned mapping.

If no transitions match at all, return `ApiResult.Error` with a descriptive message suggesting the user configure a mapping in settings.

#### Layer 3 — Guard Chain (`:core` + `:jira`)

Two types of guards that run **before** a transition executes:

**A. Jira Field Guards** (auto-discovered from API):

When `GET /issue/{key}/transitions?expand=transitions.fields` returns a transition with `hasScreen: true`, the `fields` map contains required field metadata:

```kotlin
// jira/src/main/kotlin/.../workflow/TransitionField.kt
@Serializable
data class TransitionField(
    val key: String,               // e.g., "resolution", "assignee"
    val name: String,              // e.g., "Resolution", "Assignee"
    val required: Boolean,
    val type: String,              // e.g., "resolution", "user", "array"
    val allowedValues: List<FieldValue>? = null,
    val hasDefaultValue: Boolean = false,
    val autoCompleteUrl: String? = null
)

@Serializable
data class FieldValue(
    val id: String,
    val name: String
)
```

When a transition has required fields without defaults:
- For fields with a single `allowedValues` entry → auto-select it
- For fields with multiple `allowedValues` (e.g., resolution: Fixed/Won't Fix/Duplicate) → show picker dialog
- For user fields (e.g., assignee) → show typeahead using `autoCompleteUrl`

**B. Plugin Guards** (configurable in settings):

```kotlin
// core/src/main/kotlin/.../workflow/TransitionGuard.kt
interface TransitionGuard {
    val id: String
    val description: String
    val applicableIntents: Set<WorkflowIntent>
    suspend fun evaluate(project: Project, issueKey: String): GuardResult
}

sealed class GuardResult {
    object Passed : GuardResult()
    data class Failed(val reason: String, val canOverride: Boolean = false) : GuardResult()
}
```

Built-in plugin guards (each is a toggle in settings):

| Guard ID | Description | Applicable Intents | Default |
|---|---|---|---|
| `build-passed` | Latest Bamboo build must be green | `SUBMIT_FOR_REVIEW`, `CLOSE` | Off |
| `copyright-checked` | Copyright headers must pass | `CLOSE` | Off |
| `coverage-gate` | SonarQube quality gate must pass | `SUBMIT_FOR_REVIEW` | Off |
| `automation-passed` | All automation suites must pass | `CLOSE` | Off |

Guards are evaluated before the transition executes. If any guard fails with `canOverride = true`, the user sees a warning with an "Override" button. If `canOverride = false`, the transition is blocked.

#### Layer 4 — Transition Executor (`:jira`)

```kotlin
// jira/src/main/kotlin/.../workflow/TransitionExecutor.kt
class TransitionExecutor(
    private val apiClient: JiraApiClient
) {
    suspend fun execute(
        issueKey: String,
        resolved: ResolvedTransition,
        fieldValues: Map<String, Any> = emptyMap(),
        comment: String? = null,
        worklog: WorklogEntry? = null
    ): ApiResult<Unit>
}
```

The executor builds the POST body with:
- `transition.id` — from resolved transition
- `fields` — from user-provided field values (resolution, assignee, etc.)
- `update.comment` — optional closure comment (Jira wiki markup)
- `update.worklog` — optional time logging

#### Layer 5 — Transition Mapping Store (`:jira`)

```kotlin
// jira/src/main/kotlin/.../workflow/TransitionMappingStore.kt
@Serializable
data class TransitionMapping(
    val intent: String,           // WorkflowIntent.name
    val transitionName: String,   // Matched Jira transition name
    val projectKey: String,       // Jira project key
    val issueTypeId: String? = null,  // Optional: per-issue-type mapping
    val source: String            // "explicit" or "learned"
)
```

Stored in `PluginSettings.State` as a serialized JSON string (`var workflowMappings by string("")`). The store provides:
- `getMapping(intent, projectKey, issueTypeId?): TransitionMapping?`
- `saveMapping(mapping: TransitionMapping)`
- `clearMapping(intent, projectKey)`
- `getAllMappings(): List<TransitionMapping>`

### 2.3 Enhanced DTOs

The existing `JiraTransition` DTO must be extended to support field metadata from `?expand=transitions.fields`:

```kotlin
// jira/src/main/kotlin/.../api/dto/JiraDtos.kt — additions

@Serializable
data class JiraTransition(
    val id: String,
    val name: String,
    val to: JiraStatus,
    val hasScreen: Boolean = false,
    val isGlobal: Boolean = false,
    val isConditional: Boolean = false,
    val fields: Map<String, JiraTransitionFieldMeta>? = null  // NEW
)

@Serializable
data class JiraTransitionFieldMeta(
    val required: Boolean = false,
    val name: String = "",
    val schema: JiraFieldSchema? = null,
    val allowedValues: List<JiraFieldAllowedValue>? = null,
    val hasDefaultValue: Boolean = false,
    val autoCompleteUrl: String? = null
)

@Serializable
data class JiraFieldSchema(
    val type: String,
    val system: String? = null,
    val custom: String? = null,
    val items: String? = null
)

@Serializable
data class JiraFieldAllowedValue(
    val id: String,
    val name: String,
    val description: String? = null
)
```

### 2.4 API Client Changes

`JiraApiClient.getTransitions()` must be updated to use `?expand=transitions.fields`:

```kotlin
// Current:
suspend fun getTransitions(issueKey: String): ApiResult<List<JiraTransition>> =
    get<JiraTransitionList>("/rest/api/2/issue/$issueKey/transitions")
        .map { it.transitions }

// New:
suspend fun getTransitions(
    issueKey: String,
    expandFields: Boolean = false
): ApiResult<List<JiraTransition>> {
    val expand = if (expandFields) "?expand=transitions.fields" else ""
    return get<JiraTransitionList>("/rest/api/2/issue/$issueKey/transitions$expand")
        .map { it.transitions }
}
```

`transitionIssue()` must accept optional fields and update blocks:

```kotlin
// New:
suspend fun transitionIssue(
    issueKey: String,
    transitionId: String,
    fields: Map<String, Any>? = null,
    comment: String? = null
): ApiResult<Unit> {
    val body = buildTransitionPayload(transitionId, fields, comment)
    return post("/rest/api/2/issue/$issueKey/transitions", body)
}
```

### 2.5 Consumer Changes

**BranchingService** — replace `transitionToInProgress()`:

```kotlin
// Before:
private suspend fun transitionToInProgress(issueKey: String): ApiResult<Unit> {
    val transitions = apiClient.getTransitions(issueKey)...
    val inProgressTransition = transitions.find {
        it.name.equals("In Progress", ignoreCase = true) ||
        it.to.statusCategory?.key == "indeterminate"
    }
    return apiClient.transitionIssue(issueKey, inProgressTransition.id)
}

// After:
private suspend fun transitionToInProgress(issueKey: String): ApiResult<Unit> {
    val resolver = IntentResolver.getInstance(project)
    val resolved = resolver.resolve(WorkflowIntent.START_WORK, issueKey)
    if (resolved is ApiResult.Error) return resolved
    return TransitionExecutor(apiClient).execute(issueKey, resolved.data)
}
```

**HandoverContextPanel** — replace hardcoded status display:

```kotlin
// Before:
ticketStatusLabel.text = if (state.jiraTransitioned) "Status: In Review" else "Status: In Progress"

// After:
ticketStatusLabel.text = "Status: ${state.currentStatusName ?: "Unknown"}"
```

This requires `HandoverState` to carry the actual status name fetched from Jira after transition.

**CompletionMacroService** — replace hardcoded step label:

```kotlin
// Before:
MacroStep(id = "jira-transition", label = "Transition to In Review")

// After: use settings-resolved label
val reviewLabel = mappingStore.getMapping(SUBMIT_FOR_REVIEW, projectKey)
    ?.transitionName ?: WorkflowIntent.SUBMIT_FOR_REVIEW.displayName
MacroStep(id = "jira-transition", label = "Transition to $reviewLabel")
```

### 2.6 Board Type Filter

The hardcoded `"type=scrum"` filter in `JiraApiClient.getBoards()` is a separate issue from the workflow engine. This becomes a settings dropdown:

```kotlin
// PluginSettings.State — new field:
var jiraBoardType by string("scrum")  // "scrum", "kanban", or "" (all)
```

`JiraApiClient`:
```kotlin
// Before:
suspend fun getBoards(): ApiResult<List<JiraBoard>> =
    get<JiraBoardSearchResult>("/rest/agile/1.0/board?type=scrum").map { it.values }

// After:
suspend fun getBoards(boardType: String = ""): ApiResult<List<JiraBoard>> {
    val typeFilter = if (boardType.isNotBlank()) "?type=$boardType" else ""
    return get<JiraBoardSearchResult>("/rest/agile/1.0/board$typeFilter").map { it.values }
}
```

---

## 3. Other Configurable Values (Non-Workflow)

### 3.1 Coverage Thresholds

**Current:** `80.0` and `50.0` hardcoded in `CoverageTreeDecorator.kt:39-42`

**New settings fields in `PluginSettings.State`:**

```kotlin
var coverageHighThreshold by property(80.0)   // Green threshold
var coverageMediumThreshold by property(50.0) // Yellow threshold (below = red)
```

**Files to update:**
- `sonar/src/.../ui/CoverageTreeDecorator.kt` — read thresholds from settings
- `sonar/src/.../ui/CoverageBannerProvider.kt` — use `coverageHighThreshold` for banner display

### 3.2 HTTP Timeouts

**Current:** 10s connect, 30s read hardcoded in 6 API clients

**New settings fields:**

```kotlin
var httpConnectTimeoutSeconds by property(10)
var httpReadTimeoutSeconds by property(30)
```

**Files to update:** All API clients (`JiraApiClient`, `BambooApiClient`, `SonarApiClient`, `BitbucketApiClient`, `DockerRegistryClient`, `HandoverJiraClient`) should read these values when constructing `OkHttpClient`. Since clients are constructed with `by lazy`, the settings are read once at first use.

**Implementation approach:** Create a shared `HttpClientFactory` in `:core` that reads timeout settings and produces configured `OkHttpClient.Builder` instances. This avoids duplicating the settings read across 6 clients.

### 3.3 Time Tracking Limits

**Current:** `MAX_HOURS = 7.0` hardcoded in `TimeTrackingService.kt:21`, increment step `0.5` implicit in UI

**New settings fields:**

```kotlin
var maxWorklogHours by property(7.0)
var worklogIncrementHours by property(0.5)
```

**Files to update:**
- `handover/src/.../service/TimeTrackingService.kt` — read `maxWorklogHours` from settings instead of using companion object constant
- Handover UI time spinner — use `worklogIncrementHours` as step size

### 3.4 Max Diff Size for Cody Review

**Current:** `10_000` lines hardcoded in `PreReviewService.kt`

**New settings field:**

```kotlin
var maxDiffLinesForReview by property(10000)
```

### 3.5 Max Queue Slots

**Current:** `3` hardcoded in `QueueService.kt`

**New settings field:**

```kotlin
var maxConcurrentQueueSlots by property(3)
```

### 3.6 Branch Name Max Length

**Current:** `MAX_SUMMARY_LENGTH = 50` hardcoded in `BranchNameValidator.kt`

**New settings field:**

```kotlin
var branchMaxSummaryLength by property(50)
```

### 3.7 Tag History Count

**Current:** `5` hardcoded in `TagHistoryService.kt`

**New settings field:**

```kotlin
var tagHistoryMaxEntries by property(5)
```

### 3.8 Bamboo Build Variable Name

**Current:** `"dockerTagsAsJson"` hardcoded in `TagBuilderService.kt:49`. If CI uses a different variable name, the entire baseline loading silently fails.

**New settings field:**

```kotlin
var bambooBuildVariableName by string("dockerTagsAsJson")
```

**Files to update:**
- `automation/src/.../service/TagBuilderService.kt` — read variable name from settings instead of hardcoded string

### 3.9 SonarQube Metric Keys

**Current:** `"coverage,line_coverage,branch_coverage,uncovered_lines,uncovered_conditions"` hardcoded in `SonarApiClient.kt:68`. Missing `new_coverage` and `new_branch_coverage` for new code analysis (required by CLAUDE.md quality thresholds: 100% new code, 95% new branch).

**New settings field:**

```kotlin
var sonarMetricKeys by string("coverage,line_coverage,branch_coverage,uncovered_lines,uncovered_conditions,new_coverage,new_branch_coverage")
```

**Files to update:**
- `sonar/src/.../api/SonarApiClient.kt` — use settings value in the `metricKeys=` query parameter
- `sonar/src/.../service/CoverageMapper.kt` — handle the new metric keys in mapping logic

### 3.10 PR Title Format & Max Length

**Current:** PR title is built as `"$ticketId: $summary"` with a hardcoded 120-character max in `PrService.kt:20,47-54`. Teams use different conventions (`[PROJ-123] Summary`, `PROJ-123 - Summary`, etc.).

**New settings fields:**

```kotlin
var prTitleFormat by string("{ticketId}: {summary}")  // Supports {ticketId}, {summary}, {branch}
var maxPrTitleLength by property(120)
```

**Template variables:**
- `{ticketId}` — active Jira ticket key (e.g., `PROJ-123`)
- `{summary}` — ticket summary text (truncated to fit max length)
- `{branch}` — current git branch name

**Files to update:**
- `handover/src/.../service/PrService.kt` — use template format from settings, apply max length

### 3.11 PR Default Reviewers

**Current:** PR creation sends no reviewers. The `BitbucketPrRequest` DTO has no `reviewers` field. This is a missing feature, not just a hardcoded value.

**New settings field:**

```kotlin
var prDefaultReviewers by string("")  // Comma-separated Bitbucket usernames
```

**Changes required:**
- `handover/src/.../api/dto/BitbucketDtos.kt` — add `reviewers` field to `BitbucketPrRequest`:
  ```kotlin
  @Serializable
  data class BitbucketPrRequest(
      val title: String,
      val description: String,
      val fromRef: BitbucketRef,
      val toRef: BitbucketRef,
      val reviewers: List<BitbucketReviewer>? = null  // NEW
  )

  @Serializable
  data class BitbucketReviewer(
      val user: BitbucketUser
  )

  @Serializable
  data class BitbucketUser(
      val name: String  // Bitbucket username
  )
  ```
- `handover/src/.../service/PrService.kt` — parse comma-separated reviewers from settings, build reviewer list
- `handover/src/.../api/BitbucketApiClient.kt` — include reviewers in POST body

---

## 3B. Bug Fixes (Discovered During Analysis)

### 3B.1 Case-Sensitive State Match in TagBuilderService

**Bug:** `TagBuilderService.kt:54` uses `it.state == "Successful"` (case-sensitive), while `BuildState.kt:14` uses `state.equals("Successful", ignoreCase = true)`. If the Bamboo API returns lowercase, scoring breaks.

**Fix:** Use `equals(ignoreCase = true)` consistently:

```kotlin
// Before:
val successStages = dto.stages.stage.count { it.state == "Successful" }
val failedStages = dto.stages.stage.count { it.state == "Failed" }

// After:
val successStages = dto.stages.stage.count { it.state.equals("Successful", ignoreCase = true) }
val failedStages = dto.stages.stage.count { it.state.equals("Failed", ignoreCase = true) }
```

### 3B.2 Conflicting Default Branch Fallbacks

**Bug:** `BuildDashboardPanel.kt:137` falls back to `"master"` while `SonarDataService.kt:40` falls back to `"main"`. Different modules assume different default branches.

**Fix:** Unify to a single source of truth:
1. Primary: read default branch from git repository config (`GitRepositoryManager`)
2. Fallback: use `PluginSettings.defaultTargetBranch` (already exists, defaults to `"develop"`)

Both files should use the same resolution logic rather than hardcoded strings.

### 3B.3 Coverage Threshold Duplication (3 Copies)

**Bug:** Coverage thresholds `80.0` and `50.0` are duplicated in `CoverageTreeDecorator.kt:39-42`, `OverviewPanel.kt:142-144`, and `OverviewPanel.kt:175-177` with identical RGB values. Any change requires updating 3 places.

**Fix:** Extract to a shared `CoverageThresholds` utility in `:sonar` that reads from `PluginSettings`:

```kotlin
// sonar/src/.../ui/CoverageThresholds.kt
object CoverageThresholds {
    fun colorForCoverage(project: Project, pct: Double): JBColor {
        val settings = PluginSettings.getInstance(project).state
        return when {
            pct >= settings.coverageHighThreshold -> GREEN
            pct >= settings.coverageMediumThreshold -> YELLOW
            else -> RED
        }
    }

    private val GREEN = JBColor(Color(46, 160, 67), Color(46, 160, 67))
    private val YELLOW = JBColor(Color(212, 160, 32), Color(212, 160, 32))
    private val RED = JBColor(Color(255, 68, 68), Color(255, 68, 68))
}
```

---

## 4. Settings UI Changes

### 4.1 New Settings Sub-Pages

Add two new sub-pages to the existing `WorkflowSettingsConfigurable` composite:

**A. Workflow Mapping** (`workflow.orchestrator.workflow`)

```
┌─ Workflow Mapping ──────────────────────────────────────────┐
│                                                              │
│  ┌─ Board Type ───────────────────────────────────────────┐ │
│  │ Board type:  [Scrum ▼]  (Scrum / Kanban / All)        │ │
│  └────────────────────────────────────────────────────────┘ │
│                                                              │
│  ┌─ Intent Mappings ──────────────────────────────────────┐ │
│  │ These map plugin actions to your Jira workflow          │ │
│  │ transitions. Leave blank to auto-detect.               │ │
│  │                                                        │ │
│  │ Start Work        → [________________] (auto: In Progress)│
│  │ Submit for Review  → [________________] (auto: In Review) │
│  │ Close             → [________________] (auto: Done)      │
│  │ Reopen            → [________________] (auto: Reopen)    │
│  │ Block             → [________________] (not mapped)      │
│  └────────────────────────────────────────────────────────┘ │
│                                                              │
│  ┌─ Plugin Guards ────────────────────────────────────────┐ │
│  │ Block transitions until conditions are met:            │ │
│  │                                                        │ │
│  │ ☐ Build must pass before Submit for Review             │ │
│  │ ☐ Copyright headers checked before Close               │ │
│  │ ☐ Coverage gate must pass before Submit for Review     │ │
│  │ ☐ All automation suites passed before Close            │ │
│  └────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────┘
```

Intent mapping text fields accept a Jira transition name. When blank, the auto-detection strategy runs. When filled, the intent resolver uses explicit mapping (step 1).

**B. Advanced** (`workflow.orchestrator.advanced`)

```
┌─ Advanced ──────────────────────────────────────────────────┐
│                                                              │
│  ┌─ Network ──────────────────────────────────────────────┐ │
│  │ Connect timeout (seconds):  [10  ]                     │ │
│  │ Read timeout (seconds):     [30  ]                     │ │
│  └────────────────────────────────────────────────────────┘ │
│                                                              │
│  ┌─ Quality Thresholds ──────────────────────────────────┐  │
│  │ High coverage (green):     [80  ] %                    │ │
│  │ Medium coverage (yellow):  [50  ] %                    │ │
│  │ SonarQube metrics:         [coverage,line_coverage,...] │ │
│  └────────────────────────────────────────────────────────┘ │
│                                                              │
│  ┌─ Time Tracking ────────────────────────────────────────┐ │
│  │ Max hours per worklog:     [7.0 ]                      │ │
│  │ Time increment (hours):    [0.5 ]                      │ │
│  └────────────────────────────────────────────────────────┘ │
│                                                              │
│  ┌─ Branching & PRs ─────────────────────────────────────┐  │
│  │ Max branch name length:    [50  ]                      │ │
│  │ PR title format:           [{ticketId}: {summary}    ] │ │
│  │ Max PR title length:       [120 ]                      │ │
│  │ Default reviewers:         [________________         ] │ │
│  │   (comma-separated Bitbucket usernames)                │ │
│  └────────────────────────────────────────────────────────┘ │
│                                                              │
│  ┌─ Cody AI ──────────────────────────────────────────────┐ │
│  │ Max diff lines for review: [10000]                     │ │
│  └────────────────────────────────────────────────────────┘ │
│                                                              │
│  ┌─ Automation ───────────────────────────────────────────┐ │
│  │ Max concurrent queue slots: [3  ]                      │ │
│  │ Tag history entries:        [5  ]                      │ │
│  │ Build variable name:        [dockerTagsAsJson        ] │ │
│  └────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────┘
```

### 4.2 Plugin.xml Registration

```xml
<!-- Under WorkflowSettingsConfigurable children -->
<projectConfigurable
    parentId="workflow.orchestrator"
    instance="com.workflow.orchestrator.jira.settings.WorkflowMappingConfigurable"
    id="workflow.orchestrator.workflow"
    displayName="Workflow Mapping"
    nonDefaultProject="true"/>

<projectConfigurable
    parentId="workflow.orchestrator"
    instance="com.workflow.orchestrator.core.settings.AdvancedConfigurable"
    id="workflow.orchestrator.advanced"
    displayName="Advanced"
    nonDefaultProject="true"/>
```

---

## 5. Module Placement Summary

| Component | Module | Package |
|---|---|---|
| `WorkflowIntent` enum | `:core` | `core.workflow` |
| `TransitionGuard` interface + `GuardResult` | `:core` | `core.workflow` |
| `IntentResolver` | `:jira` | `jira.workflow` |
| `TransitionExecutor` | `:jira` | `jira.workflow` |
| `TransitionMappingStore` | `:jira` | `jira.workflow` |
| `TransitionField`, `FieldValue` DTOs | `:jira` | `jira.api.dto` |
| Extended `JiraTransition` DTO | `:jira` | `jira.api.dto` |
| `WorkflowMappingConfigurable` | `:jira` | `jira.settings` |
| `AdvancedConfigurable` | `:core` | `core.settings` |
| `HttpClientFactory` | `:core` | `core.http` |
| New settings fields | `:core` | `core.settings` |
| Guard implementations | `:jira`, `:bamboo`, `:sonar` (via event bus) | `*.workflow` |
| `CoverageThresholds` shared utility | `:sonar` | `sonar.ui` |
| `BitbucketReviewer`/`BitbucketUser` DTOs | `:handover` | `handover.api.dto` |

---

## 6. Data Flow Examples

### 6.1 "Start Work" — Happy Path (Standard Workflow)

```
1. User clicks "Start Work" on PROJ-123
2. BranchingService calls IntentResolver.resolve(START_WORK, "PROJ-123")
3. IntentResolver checks TransitionMappingStore → no mapping found
4. IntentResolver calls JiraApiClient.getTransitions("PROJ-123", expandFields=true)
5. API returns: [{id:"21", name:"In Progress", to:{statusCategory:{key:"indeterminate"}}, fields:{}}]
6. IntentResolver tries name matching: "In Progress" matches intent.defaultNames[0]
7. IntentResolver saves learned mapping: START_WORK → "In Progress" for project PROJ
8. IntentResolver returns ResolvedTransition(id="21", name="In Progress", fields=[], resolution=NAME_MATCH)
9. GuardChain evaluates plugin guards → no guards enabled for START_WORK → all pass
10. TransitionExecutor calls POST /issue/PROJ-123/transitions {transition:{id:"21"}}
11. Success → BranchingService creates branch and sets active ticket
```

### 6.2 "Submit for Review" — Custom Workflow with Required Fields

```
1. User clicks "Submit for Review" in Handover panel
2. IntentResolver.resolve(SUBMIT_FOR_REVIEW, "PROJ-123")
3. TransitionMappingStore → no mapping
4. API returns: [{id:"31", name:"Code Review", to:{...}, hasScreen:true,
                  fields:{assignee:{required:true, autoCompleteUrl:"..."}}}]
5. Name matching: "Code Review" matches intent.defaultNames[3]
6. ResolvedTransition has requiredFields: [TransitionField(key="assignee", required=true)]
7. GuardChain: "build-passed" guard enabled → checks latest Bamboo build → PASSED
8. FieldResolver detects required "assignee" field → shows typeahead dialog
9. User selects reviewer "john.doe"
10. TransitionExecutor calls POST /transitions {transition:{id:"31"}, fields:{assignee:{name:"john.doe"}}}
11. Learned mapping saved: SUBMIT_FOR_REVIEW → "Code Review" for project PROJ
```

### 6.3 "Close" — Disambiguation (Multiple Matches)

```
1. JiraClosureService calls IntentResolver.resolve(CLOSE, "PROJ-123")
2. API returns: [{id:"41", name:"Done", to:{statusCategory:{key:"done"}}},
                 {id:"42", name:"Resolved", to:{statusCategory:{key:"done"}}}]
3. Name matching: "Done" matches → but also "Resolved" matches
4. IntentResolver presents disambiguation dialog: "Multiple transitions match 'Close'. Which one?"
   - ○ Done
   - ● Resolved
5. User selects "Resolved"
6. Learned mapping saved: CLOSE → "Resolved" for project PROJ
7. Next time: step 3 finds learned mapping, skips to execution
```

---

## 7. Complete Settings Field Summary

All new fields added to `PluginSettings.State`:

```kotlin
// Workflow mapping (serialized JSON)
var workflowMappings by string("")

// Board type
var jiraBoardType by string("scrum")

// Plugin guards (toggles)
var guardBuildPassedBeforeReview by property(false)
var guardCopyrightBeforeClose by property(false)
var guardCoverageBeforeReview by property(false)
var guardAutomationBeforeClose by property(false)

// Coverage thresholds
var coverageHighThreshold by property(80.0)
var coverageMediumThreshold by property(50.0)

// HTTP timeouts
var httpConnectTimeoutSeconds by property(10)
var httpReadTimeoutSeconds by property(30)

// Time tracking
var maxWorklogHours by property(7.0)
var worklogIncrementHours by property(0.5)

// Branching & PRs
var branchMaxSummaryLength by property(50)
var prTitleFormat by string("{ticketId}: {summary}")
var maxPrTitleLength by property(120)
var prDefaultReviewers by string("")

// Cody review
var maxDiffLinesForReview by property(10000)

// SonarQube
var sonarMetricKeys by string("coverage,line_coverage,branch_coverage,uncovered_lines,uncovered_conditions,new_coverage,new_branch_coverage")

// Automation
var maxConcurrentQueueSlots by property(3)
var tagHistoryMaxEntries by property(5)
var bambooBuildVariableName by string("dockerTagsAsJson")
```

Total: **22 new fields** (all with defaults matching current hardcoded values, plus `new_coverage`/`new_branch_coverage` added to sonar metrics default).

---

## 8. Testing Strategy

| Component | Test Approach |
|---|---|
| `IntentResolver` resolution logic | Unit test with mock `JiraApiClient` returning fixture transitions — test all 5 resolution steps |
| `TransitionMappingStore` persistence | Unit test: save, retrieve, clear mappings; verify JSON serialization round-trip |
| `TransitionExecutor` payload building | Unit test: verify POST body structure for various field combinations |
| Guard evaluation | Unit test each guard independently with mocked service state |
| `CoverageThresholds` shared utility | Unit test: verify correct color returned for various pct + threshold combos |
| PR title template rendering | Unit test: verify `{ticketId}`, `{summary}`, `{branch}` substitution + truncation |
| PR reviewer serialization | Unit test: verify `BitbucketPrRequest` JSON includes `reviewers` array |
| `TagBuilderService` case-insensitive fix | Unit test: verify `"successful"` (lowercase) scores correctly |
| SonarQube metric keys passthrough | Unit test: verify custom metric keys appear in API query string |
| `HttpClientFactory` timeout propagation | Unit test: verify factory reads settings and applies to `OkHttpClient.Builder` |
| Settings persistence (22 fields) | `BasePlatformTestCase`: set all new values, reload, verify round-trip |
| Settings UI (3 pages) | Manual `runIde` verification for Connections, Workflow Mapping, Advanced |
| End-to-end workflow | Integration test with `MockWebServer`: resolve intent → execute transition → verify API call |

---

## 9. Migration Path

Existing users upgrading the plugin:

1. All new settings have defaults matching current behavior — **no action required**
2. If user had a working "Start Work" flow with "In Progress", the name-matching step finds it automatically
3. If user had custom Jira workflows that happened to work via `statusCategory` fallback, the category-matching step preserves that behavior
4. The only user-visible change: if disambiguation is needed, user sees a one-time dialog and never again

---

## 10. Files Changed Summary

### New Files

| File | Module | Purpose |
|---|---|---|
| `core/.../workflow/WorkflowIntent.kt` | `:core` | Intent enum |
| `core/.../workflow/TransitionGuard.kt` | `:core` | Guard interface + GuardResult |
| `core/.../settings/AdvancedConfigurable.kt` | `:core` | Advanced settings UI |
| `core/.../http/HttpClientFactory.kt` | `:core` | Shared HTTP client builder with configurable timeouts |
| `jira/.../workflow/IntentResolver.kt` | `:jira` | Intent-to-transition resolution |
| `jira/.../workflow/TransitionExecutor.kt` | `:jira` | Transition execution with fields |
| `jira/.../workflow/TransitionMappingStore.kt` | `:jira` | Persisted intent→transition mappings |
| `jira/.../settings/WorkflowMappingConfigurable.kt` | `:jira` | Workflow mapping settings UI |
| `sonar/.../ui/CoverageThresholds.kt` | `:sonar` | Shared threshold→color utility (eliminates 3x duplication) |

### Modified Files

| File | Module | Change |
|---|---|---|
| `core/.../settings/PluginSettings.kt` | `:core` | Add 22 new settings fields |
| `core/.../settings/WorkflowSettingsConfigurable.kt` | `:core` | Register new sub-pages |
| `jira/.../api/dto/JiraDtos.kt` | `:jira` | Extend `JiraTransition` with fields metadata |
| `jira/.../api/JiraApiClient.kt` | `:jira` | Add `expandFields` param, update `transitionIssue()`, parameterize board type |
| `jira/.../service/BranchingService.kt` | `:jira` | Use `IntentResolver` instead of hardcoded name |
| `handover/.../service/CompletionMacroService.kt` | `:handover` | Dynamic step label from mapping store |
| `handover/.../ui/HandoverContextPanel.kt` | `:handover` | Display actual status name instead of hardcoded |
| `handover/.../service/HandoverStateService.kt` | `:handover` | Add `currentStatusName` to state |
| `handover/.../service/TimeTrackingService.kt` | `:handover` | Read max hours from settings |
| `handover/.../service/PrService.kt` | `:handover` | Configurable PR title format, max length, template variables |
| `handover/.../api/dto/BitbucketDtos.kt` | `:handover` | Add `reviewers` field to `BitbucketPrRequest` |
| `handover/.../api/BitbucketApiClient.kt` | `:handover` | Include reviewers in PR creation POST body |
| `sonar/.../ui/CoverageTreeDecorator.kt` | `:sonar` | Use shared `CoverageThresholds` utility |
| `sonar/.../ui/OverviewPanel.kt` | `:sonar` | Use shared `CoverageThresholds` utility (removes 2 duplicate copies) |
| `sonar/.../ui/CoverageBannerProvider.kt` | `:sonar` | Use shared `CoverageThresholds` utility |
| `sonar/.../api/SonarApiClient.kt` | `:sonar` | Read metric keys from settings |
| `sonar/.../service/CoverageMapper.kt` | `:sonar` | Handle new `new_coverage`/`new_branch_coverage` metrics |
| `automation/.../service/TagBuilderService.kt` | `:automation` | Read variable name from settings; fix case-sensitive state match |
| `bamboo/.../ui/BuildDashboardPanel.kt` | `:bamboo` | Use git repo default branch instead of hardcoded `"master"` |
| `sonar/.../service/SonarDataService.kt` | `:sonar` | Use git repo default branch instead of hardcoded `"main"` |
| `src/main/resources/META-INF/plugin.xml` | root | Register new configurables |
| All 7 API clients | various | Use `HttpClientFactory` for timeouts |
