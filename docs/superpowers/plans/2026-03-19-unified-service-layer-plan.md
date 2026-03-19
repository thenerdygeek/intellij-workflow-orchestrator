# Plan: Unified Service Layer — Shared Tools for UI Tabs and AI Agent

## Context

The Workflow Orchestrator plugin has 6 feature modules (`:jira`, `:bamboo`, `:sonar`, `:pullrequest`, `:automation`, `:handover`) with their own API clients. The `:agent` module duplicates these clients with its own HTTP calls to the same APIs. This plan eliminates the duplication by creating a shared service layer in `:core`.

**Current state:**
- `:jira` has `JiraApiClient` with `getIssue()`, `transitionIssue()`, `addComment()`, `logWork()`, etc.
- `:agent` has `JiraGetTicketTool`, `JiraTransitionTool`, `JiraCommentTool` — each building their own OkHttpClient and making raw HTTP calls to the same Jira endpoints
- Same duplication for Bamboo, SonarQube, Bitbucket
- 6 integration tools in `:agent` duplicate what already exists in feature modules

**Goal:** One service implementation, two consumers (UI tabs + AI agent), zero duplication.

**Branch:** `main` (this refactor affects `:core` and all feature modules, not the agent branch)

---

## Architecture

### The ToolResult<T> Pattern

Every service method returns a `ToolResult<T>` with two views of the same data:

```kotlin
// In :core
@Serializable
data class ToolResult<T>(
    /** Typed structured data — consumed by UI panels and programmatic code. */
    val data: T,

    /** LLM-optimized plain text summary — consumed by the AI agent's context window.
     *  Plain text is ~60% fewer tokens than JSON and matches LLM training data patterns.
     *  (Research: Anthropic recommends "high-signal information" in tool results.) */
    val summary: String,

    /** Whether this result represents an error. */
    val isError: Boolean = false,

    /** Optional hint for the AI agent about what to do next. */
    val hint: String? = null,

    /** Token estimate for the summary text. */
    val tokenEstimate: Int = 0
)
```

**Why two fields instead of just JSON:**
- UI panels need typed `data` for rendering (deserialize into view models, no parsing)
- The LLM needs concise `summary` text (45 tokens vs 120 tokens for same info, matches training data)
- This matches MCP's `structuredContent` + `content` pattern and Vercel AI SDK's `execute` + `toModelOutput`

### Service Interfaces in `:core`

```
:core
  ├── services/
  │   ├── JiraService.kt           ← Interface
  │   ├── BambooService.kt         ← Interface
  │   ├── SonarService.kt          ← Interface
  │   ├── BitbucketService.kt      ← Interface
  │   └── ToolResult.kt            ← The generic result type
  │
  ├── model/
  │   ├── jira/
  │   │   ├── JiraTicket.kt        ← Shared DTOs (moved from :jira)
  │   │   ├── JiraTransition.kt
  │   │   └── JiraComment.kt
  │   ├── bamboo/
  │   │   ├── BuildResult.kt       ← Shared DTOs (moved from :bamboo)
  │   │   └── BuildStage.kt
  │   ├── sonar/
  │   │   ├── SonarIssue.kt        ← Shared DTOs (moved from :sonar)
  │   │   └── QualityGateStatus.kt
  │   └── bitbucket/
  │       └── PullRequest.kt       ← Shared DTOs (moved from :pullrequest)
```

### Implementation Flow

```
:jira module                          :agent module
  ├── JiraServiceImpl                   ├── JiraGetTicketTool
  │   implements JiraService            │   val svc = project.getService(JiraService::class.java)
  │   registered via plugin.xml         │   val result = svc.getTicket(key)
  │   uses existing JiraApiClient       │   return AgentToolResult(content = result.summary)
  │                                     │
  ├── ui/SprintPanel                    ├── JiraTransitionTool
  │   val result = svc.getTicket(key)   │   val result = svc.transition(key, id)
  │   renderTable(result.data)          │   return AgentToolResult(content = result.summary)
```

---

## Step-by-Step Implementation

### Step 1: Create ToolResult<T> in `:core`

**File:** `core/src/main/kotlin/com/workflow/orchestrator/core/services/ToolResult.kt`

```kotlin
package com.workflow.orchestrator.core.services

import kotlinx.serialization.Serializable

/**
 * Universal result type for service operations.
 * Serves both UI panels (via typed data) and AI agent (via text summary).
 */
@Serializable
data class ToolResult<T>(
    val data: T,
    val summary: String,
    val isError: Boolean = false,
    val hint: String? = null,
    val tokenEstimate: Int = 0
) {
    companion object {
        fun <T> success(data: T, summary: String, hint: String? = null): ToolResult<T> =
            ToolResult(data = data, summary = summary, hint = hint)

        fun error(summary: String, hint: String? = null): ToolResult<Unit> =
            ToolResult(data = Unit, summary = summary, isError = true, hint = hint)
    }
}
```

**Note:** This is a NEW `ToolResult` in `:core/services/`. The existing `ToolResult` in `:agent/tools/` is agent-specific (has `content`, `artifacts`, `tokenEstimate`). They will coexist — the agent's tool execution layer converts from `core.ToolResult<T>` to `agent.ToolResult`.

---

### Step 2: Create Service Interfaces in `:core`

**File:** `core/src/main/kotlin/com/workflow/orchestrator/core/services/JiraService.kt`

```kotlin
package com.workflow.orchestrator.core.services

import com.workflow.orchestrator.core.model.jira.*

/**
 * Jira operations used by both UI panels and AI agent.
 * Implementations registered as project-level services by :jira module.
 */
interface JiraService {
    /** Get ticket details. */
    suspend fun getTicket(key: String): ToolResult<JiraTicketData>

    /** Get available transitions for a ticket. */
    suspend fun getTransitions(key: String): ToolResult<List<JiraTransitionData>>

    /** Transition a ticket to a new status. */
    suspend fun transition(key: String, transitionId: String): ToolResult<Unit>

    /** Add a comment to a ticket. */
    suspend fun addComment(key: String, body: String): ToolResult<Unit>

    /** Log work on a ticket. */
    suspend fun logWork(key: String, timeSpent: String, comment: String?): ToolResult<Unit>

    /** Test the Jira connection. */
    suspend fun testConnection(): ToolResult<Unit>
}
```

**File:** `core/src/main/kotlin/com/workflow/orchestrator/core/services/BambooService.kt`

```kotlin
package com.workflow.orchestrator.core.services

import com.workflow.orchestrator.core.model.bamboo.*

interface BambooService {
    /** Get latest build result for a plan. */
    suspend fun getLatestBuild(planKey: String): ToolResult<BuildResultData>

    /** Get a specific build result with stages. */
    suspend fun getBuild(buildKey: String): ToolResult<BuildResultData>

    /** Trigger a build with optional variables. */
    suspend fun triggerBuild(planKey: String, variables: Map<String, String> = emptyMap()): ToolResult<BuildTriggerData>

    /** Test the Bamboo connection. */
    suspend fun testConnection(): ToolResult<Unit>
}
```

**File:** `core/src/main/kotlin/com/workflow/orchestrator/core/services/SonarService.kt`

```kotlin
package com.workflow.orchestrator.core.services

import com.workflow.orchestrator.core.model.sonar.*

interface SonarService {
    /** Get open issues for a project, optionally filtered by file. */
    suspend fun getIssues(projectKey: String, filePath: String? = null): ToolResult<List<SonarIssueData>>

    /** Get quality gate status. */
    suspend fun getQualityGateStatus(projectKey: String): ToolResult<QualityGateData>

    /** Get coverage metrics. */
    suspend fun getCoverage(projectKey: String): ToolResult<CoverageData>

    /** Test the SonarQube connection. */
    suspend fun testConnection(): ToolResult<Unit>
}
```

**File:** `core/src/main/kotlin/com/workflow/orchestrator/core/services/BitbucketService.kt`

```kotlin
package com.workflow.orchestrator.core.services

import com.workflow.orchestrator.core.model.bitbucket.*

interface BitbucketService {
    /** Create a pull request. */
    suspend fun createPullRequest(
        title: String, description: String,
        fromBranch: String, toBranch: String
    ): ToolResult<PullRequestData>

    /** Test the Bitbucket connection. */
    suspend fun testConnection(): ToolResult<Unit>
}
```

---

### Step 3: Create Shared DTOs in `:core`

Move/create lightweight data classes in `:core/model/`. These should be simple `@Serializable` data classes, NOT the full API response DTOs. They are the **domain model** — only the fields that UI and agent both need.

**File:** `core/src/main/kotlin/com/workflow/orchestrator/core/model/jira/JiraModels.kt`

```kotlin
package com.workflow.orchestrator.core.model.jira

import kotlinx.serialization.Serializable

@Serializable
data class JiraTicketData(
    val key: String,
    val summary: String,
    val status: String,
    val assignee: String?,
    val type: String,
    val priority: String?,
    val description: String?,
    val labels: List<String> = emptyList(),
    val transitions: List<JiraTransitionData> = emptyList()
)

@Serializable
data class JiraTransitionData(
    val id: String,
    val name: String,
    val toStatus: String
)
```

**File:** `core/src/main/kotlin/com/workflow/orchestrator/core/model/bamboo/BambooModels.kt`

```kotlin
package com.workflow.orchestrator.core.model.bamboo

import kotlinx.serialization.Serializable

@Serializable
data class BuildResultData(
    val planKey: String,
    val buildNumber: Int,
    val state: String,          // "Successful", "Failed", "Unknown"
    val durationSeconds: Long,
    val stages: List<BuildStageData> = emptyList(),
    val testsPassed: Int = 0,
    val testsFailed: Int = 0,
    val testsSkipped: Int = 0
)

@Serializable
data class BuildStageData(
    val name: String,
    val state: String,
    val durationSeconds: Long
)

@Serializable
data class BuildTriggerData(
    val buildKey: String,
    val buildNumber: Int,
    val link: String
)
```

**File:** `core/src/main/kotlin/com/workflow/orchestrator/core/model/sonar/SonarModels.kt`

```kotlin
package com.workflow.orchestrator.core.model.sonar

import kotlinx.serialization.Serializable

@Serializable
data class SonarIssueData(
    val key: String,
    val rule: String,
    val severity: String,       // "BLOCKER", "CRITICAL", "MAJOR", "MINOR", "INFO"
    val message: String,
    val component: String,
    val line: Int?,
    val status: String,
    val type: String            // "BUG", "VULNERABILITY", "CODE_SMELL"
)

@Serializable
data class QualityGateData(
    val status: String,         // "OK", "ERROR"
    val conditions: List<QualityCondition> = emptyList()
)

@Serializable
data class QualityCondition(
    val metric: String,
    val operator: String,
    val value: String,
    val status: String
)

@Serializable
data class CoverageData(
    val lineCoverage: Double,
    val branchCoverage: Double,
    val totalLines: Int,
    val coveredLines: Int
)
```

**File:** `core/src/main/kotlin/com/workflow/orchestrator/core/model/bitbucket/BitbucketModels.kt`

```kotlin
package com.workflow.orchestrator.core.model.bitbucket

import kotlinx.serialization.Serializable

@Serializable
data class PullRequestData(
    val id: Int,
    val title: String,
    val state: String,
    val fromBranch: String,
    val toBranch: String,
    val link: String,
    val authorName: String?
)
```

---

### Step 4: Implement Service in Each Feature Module

Each feature module implements its service interface using its existing API client. The key change: wrap the existing response DTOs into the shared `:core` model + build a text summary.

**Example:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/service/JiraServiceImpl.kt`

```kotlin
package com.workflow.orchestrator.jira.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.jira.*
import com.workflow.orchestrator.core.services.JiraService
import com.workflow.orchestrator.core.services.ToolResult
import com.workflow.orchestrator.jira.api.JiraApiClient

@Service(Service.Level.PROJECT)
class JiraServiceImpl(private val project: Project) : JiraService {

    private val client: JiraApiClient by lazy {
        // Use existing client construction from the :jira module
        JiraApiClient.create(project)
    }

    override suspend fun getTicket(key: String): ToolResult<JiraTicketData> {
        val result = client.getIssue(key)
        return result.fold(
            onSuccess = { issue ->
                val data = JiraTicketData(
                    key = issue.key,
                    summary = issue.fields.summary,
                    status = issue.fields.status.name,
                    assignee = issue.fields.assignee?.displayName,
                    type = issue.fields.issuetype.name,
                    priority = issue.fields.priority?.name,
                    description = issue.fields.description?.take(500),
                    labels = issue.fields.labels ?: emptyList()
                )
                ToolResult.success(
                    data = data,
                    summary = "$key: ${data.summary}\nStatus: ${data.status} | Type: ${data.type} | Assignee: ${data.assignee ?: "Unassigned"}\nPriority: ${data.priority ?: "None"}"
                )
            },
            onError = { error ->
                ToolResult(
                    data = JiraTicketData(key = key, summary = "", status = "ERROR", assignee = null, type = "", priority = null, description = null),
                    summary = "Error fetching $key: ${error.message}",
                    isError = true,
                    hint = "Check Jira connection in Settings."
                )
            }
        )
    }

    override suspend fun transition(key: String, transitionId: String): ToolResult<Unit> {
        val result = client.transitionIssue(key, transitionId)
        return result.fold(
            onSuccess = {
                ToolResult.success(Unit, "Transitioned $key with transition ID $transitionId")
            },
            onError = { error ->
                ToolResult(
                    data = Unit,
                    summary = "Error transitioning $key: ${error.message}",
                    isError = true,
                    hint = "Use jira_get_ticket to see available transitions."
                )
            }
        )
    }

    // ... addComment, logWork, testConnection follow same pattern
}
```

### Step 5: Register Services in plugin.xml

Each feature module registers its service implementation. The interfaces are in `:core` so any module can look them up.

**In the main `plugin.xml` (or the feature module's config file):**

```xml
<!-- Jira service (implemented by :jira, interface in :core) -->
<projectService
    serviceInterface="com.workflow.orchestrator.core.services.JiraService"
    serviceImplementation="com.workflow.orchestrator.jira.service.JiraServiceImpl"/>

<!-- Bamboo service -->
<projectService
    serviceInterface="com.workflow.orchestrator.core.services.BambooService"
    serviceImplementation="com.workflow.orchestrator.bamboo.service.BambooServiceImpl"/>

<!-- Sonar service -->
<projectService
    serviceInterface="com.workflow.orchestrator.core.services.SonarService"
    serviceImplementation="com.workflow.orchestrator.sonar.service.SonarServiceImpl"/>

<!-- Bitbucket service -->
<projectService
    serviceInterface="com.workflow.orchestrator.core.services.BitbucketService"
    serviceImplementation="com.workflow.orchestrator.pullrequest.service.BitbucketServiceImpl"/>
```

### Step 6: Update Feature Module UI to Use New Service

The existing UI panels switch from calling `JiraApiClient` directly to calling `JiraService`. They access `result.data` for typed data.

**Before (in `:jira`):**
```kotlin
val apiResult = jiraApiClient.getIssue(key)
when (apiResult) {
    is ApiResult.Success -> renderIssue(apiResult.data)
    is ApiResult.Error -> showError(apiResult.message)
}
```

**After:**
```kotlin
val result = project.getService(JiraService::class.java).getTicket(key)
if (result.isError) {
    showError(result.summary)
} else {
    renderIssue(result.data)  // JiraTicketData — typed, ready to render
}
```

The API client stays in the feature module as an implementation detail of `JiraServiceImpl`. No client code is moved or deleted.

### Step 7: Update Agent Tools to Use Services (done on agent branch)

Once Steps 1-6 are on `main` and the agent branch is rebased, the agent tools become thin wrappers:

**`agent/tools/integration/JiraGetTicketTool.kt` (after refactor):**

```kotlin
class JiraGetTicketTool : AgentTool {
    override val name = "jira_get_ticket"
    override val description = "Get Jira ticket details: summary, status, assignee, type, priority."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "key" to ParameterProperty(type = "string", description = "Jira ticket key (e.g., PROJ-123)")
        ),
        required = listOf("key")
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): AgentToolResult {
        val key = params["key"]?.jsonPrimitive?.content
            ?: return AgentToolResult("Error: 'key' required", "Error", 5, isError = true)

        val jiraService = project.getServiceIfAvailable(JiraService::class.java)
            ?: return AgentToolResult(
                "Jira not configured. Set up Jira connection in Settings.",
                "Jira not available", 5, isError = true
            )

        val result = jiraService.getTicket(key)
        return AgentToolResult(
            content = result.summary,
            summary = result.summary.lines().first(),
            tokenEstimate = TokenEstimator.estimate(result.summary),
            isError = result.isError
        )
    }
}
```

---

## Migration Checklist

### Phase 1: Core Infrastructure (no breaking changes)
- [ ] Create `core/services/ToolResult.kt`
- [ ] Create service interfaces: `JiraService`, `BambooService`, `SonarService`, `BitbucketService`
- [ ] Create shared DTOs in `core/model/jira/`, `core/model/bamboo/`, `core/model/sonar/`, `core/model/bitbucket/`
- [ ] Verify `:core` compiles

### Phase 2: Implement Services (one module at a time)
- [ ] `:jira` — Create `JiraServiceImpl`, register in plugin.xml
- [ ] `:bamboo` — Create `BambooServiceImpl`, register in plugin.xml
- [ ] `:sonar` — Create `SonarServiceImpl`, register in plugin.xml
- [ ] `:pullrequest` — Create `BitbucketServiceImpl`, register in plugin.xml
- [ ] All feature modules compile and pass existing tests

### Phase 3: Wire UI Panels to Services
- [ ] `:jira` UI panels use `JiraService` instead of `JiraApiClient` directly
- [ ] `:bamboo` UI panels use `BambooService`
- [ ] `:sonar` UI panels use `SonarService`
- [ ] `:pullrequest` UI panels use `BitbucketService`
- [ ] All existing tests pass
- [ ] Manual verification in `runIde` — all 6 tabs still work

### Phase 4: Verify (run before merging)
```bash
./gradlew test          # All module tests pass
./gradlew verifyPlugin  # API compatibility
./gradlew runIde        # Manual: all tabs work, test connection works
```

---

## What NOT to Change

- **Don't delete existing API clients.** `JiraApiClient`, `BambooApiClient`, etc. stay as implementation details of their service impls. They are battle-tested and well-tested.
- **Don't move API response DTOs.** The raw JSON response types (`JiraIssueResponse`, etc.) stay in feature modules. Only the simplified shared domain models go in `:core`.
- **Don't touch the `:agent` module.** This refactor is on `main`. Agent tools will be updated after rebasing.
- **Don't change API endpoints or auth.** The HTTP layer is unchanged — only the layer above it (service methods) is new.
- **Don't break existing tests.** All changes are additive until Phase 3, which replaces direct client calls with service calls.

---

## Files Created/Modified Summary

### New Files (~15)
```
core/src/main/kotlin/.../core/services/ToolResult.kt
core/src/main/kotlin/.../core/services/JiraService.kt
core/src/main/kotlin/.../core/services/BambooService.kt
core/src/main/kotlin/.../core/services/SonarService.kt
core/src/main/kotlin/.../core/services/BitbucketService.kt
core/src/main/kotlin/.../core/model/jira/JiraModels.kt
core/src/main/kotlin/.../core/model/bamboo/BambooModels.kt
core/src/main/kotlin/.../core/model/sonar/SonarModels.kt
core/src/main/kotlin/.../core/model/bitbucket/BitbucketModels.kt
jira/src/main/kotlin/.../jira/service/JiraServiceImpl.kt
bamboo/src/main/kotlin/.../bamboo/service/BambooServiceImpl.kt
sonar/src/main/kotlin/.../sonar/service/SonarServiceImpl.kt
pullrequest/src/main/kotlin/.../pullrequest/service/BitbucketServiceImpl.kt
```

### Modified Files (~10-15)
```
src/main/resources/META-INF/plugin.xml  (service registrations)
jira/src/main/kotlin/.../jira/ui/*.kt   (use JiraService instead of JiraApiClient)
bamboo/src/main/kotlin/.../bamboo/ui/*.kt
sonar/src/main/kotlin/.../sonar/ui/*.kt
pullrequest/src/main/kotlin/.../pullrequest/ui/*.kt
```

### NOT Modified
```
agent/**                    (updated later on agent branch after rebase)
core/src/main/kotlin/.../core/http/**    (HTTP infrastructure unchanged)
core/src/main/kotlin/.../core/auth/**    (Auth unchanged)
*/api/*ApiClient.kt         (Existing clients stay as-is, used by ServiceImpl)
```

---

## Key Design Decisions

1. **Service interfaces in `:core`, implementations in feature modules.** This is the standard IntelliJ platform pattern for cross-module services.

2. **`ToolResult<T>` has both `data` and `summary`.** The LLM gets `summary` (plain text, token-efficient). The UI gets `data` (typed, renderable). Based on MCP spec and Anthropic's recommendations.

3. **Shared DTOs are simplified domain models, not raw API responses.** `JiraTicketData` has the 8 fields both consumers need, not the 50+ fields in the raw Jira API response.

4. **Existing API clients are NOT moved.** They stay as implementation details. Only the service layer is new.

5. **No `getService()` in `:agent` tools yet.** This plan only covers `main` branch. Agent tools will be updated on the agent branch after rebasing onto the refactored `main`.

---

## Research References

- **MCP structuredContent + content pattern:** https://modelcontextprotocol.io/specification/2025-11-25/server/tools
- **Anthropic tool best practices:** https://www.anthropic.com/engineering/writing-tools-for-agents
- **Vercel AI SDK execute + toModelOutput:** https://ai-sdk.dev/docs/ai-sdk-core/tools-and-tool-calling
- **Full research:** `docs/superpowers/research/2026-03-19-tool-response-format-research.md`
