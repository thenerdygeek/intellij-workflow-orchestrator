# Phase 2B: Handover Engine — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the final `:handover` module that consolidates outputs from all prior phases into a single Handover tab — copyright auto-fix, Cody pre-review, Bitbucket PR creation, Jira closure comments, status transitions, time tracking, QA clipboard, and an optional "Complete Task" macro.

**Architecture:** New `:handover` Gradle module depending ONLY on `:core`. Cross-module data flows through `EventBus` (subscribing to `BuildFinished`, `AutomationTriggered`, `AutomationFinished`, `QualityGateResult`). `HandoverStateService` is the central event accumulator providing reactive `StateFlow<HandoverState>` for UI binding. Two new API clients (`BitbucketApiClient`, `HandoverJiraClient`) follow the established OkHttp + `ApiResult<T>` + `AuthInterceptor` pattern from `BambooApiClient`. All services use the dual-constructor pattern (Project constructor for DI, mock constructor for testing).

**Tech Stack:** Kotlin 2.1.10, IntelliJ Platform SDK 2025.1+, kotlinx.coroutines 1.8, kotlinx.serialization 1.7.3, OkHttp 4.12, JUnit 5, MockK 1.13, Turbine 1.1, MockWebServer

**Spec:** `docs/superpowers/specs/2026-03-12-phase-2b-handover-engine-design.md`

---

## File Structure

### New Files (`:handover` module)

| File | Responsibility |
|------|---------------|
| `handover/build.gradle.kts` | Module build config — depends on `:core` only |
| `handover/src/main/kotlin/.../handover/model/HandoverModels.kt` | Data classes: `HandoverState`, `SuiteResult`, `BuildSummary`, `CopyrightFileEntry`, `ReviewFinding`, `ClipboardPayload`, `MacroStep` |
| `handover/src/main/kotlin/.../handover/api/BitbucketApiClient.kt` | Bitbucket Server REST API v1 — PR creation, duplicate detection |
| `handover/src/main/kotlin/.../handover/api/dto/BitbucketDtos.kt` | DTOs: `BitbucketPrResponse`, `BitbucketLinks`, `BitbucketLink`, `BitbucketPrRequest` |
| `handover/src/main/kotlin/.../handover/api/HandoverJiraClient.kt` | Lightweight Jira client — addComment, logWork, getTransitions, transitionIssue |
| `handover/src/main/kotlin/.../handover/api/dto/JiraDtos.kt` | DTOs: `JiraCommentResponse`, `JiraTransition`, `JiraTransitionsResponse` |
| `handover/src/main/kotlin/.../handover/service/HandoverStateService.kt` | Central event accumulator — subscribes to EventBus, exposes `StateFlow<HandoverState>` |
| `handover/src/main/kotlin/.../handover/service/CopyrightFixService.kt` | Year-range consolidation, missing header injection, VFS file writes |
| `handover/src/main/kotlin/.../handover/service/PrService.kt` | PR creation orchestration — Git remote parsing, Cody description, Bitbucket API |
| `handover/src/main/kotlin/.../handover/service/PreReviewService.kt` | Cody diff analysis for Spring Boot anti-patterns |
| `handover/src/main/kotlin/.../handover/service/JiraClosureService.kt` | Build wiki-markup closure comment from accumulated suite data |
| `handover/src/main/kotlin/.../handover/service/TimeTrackingService.kt` | Jira worklog integration with max 7h validation |
| `handover/src/main/kotlin/.../handover/service/QaClipboardService.kt` | Format docker tags + suite links for clipboard copy |
| `handover/src/main/kotlin/.../handover/service/CompletionMacroService.kt` | Chain selected post-automation actions sequentially |
| `handover/src/main/kotlin/.../handover/ui/HandoverTabProvider.kt` | `WorkflowTabProvider` implementation (5th tab, order=4) |
| `handover/src/main/kotlin/.../handover/ui/HandoverPanel.kt` | Main panel: toolbar + JBSplitter (left context + right detail) |
| `handover/src/main/kotlin/.../handover/ui/HandoverToolbar.kt` | `ActionToolbar` with 7 `AnAction` buttons |
| `handover/src/main/kotlin/.../handover/ui/HandoverContextPanel.kt` | Left sidebar: live context summary bound to `HandoverState` |
| `handover/src/main/kotlin/.../handover/ui/panels/CopyrightPanel.kt` | Copyright fix detail panel — file list + Fix All button |
| `handover/src/main/kotlin/.../handover/ui/panels/PreReviewPanel.kt` | Cody review findings panel — severity list + Fix with Cody |
| `handover/src/main/kotlin/.../handover/ui/panels/PrCreationPanel.kt` | PR creation form — auto-populated fields + Create PR button |
| `handover/src/main/kotlin/.../handover/ui/panels/JiraCommentPanel.kt` | Closure comment preview + Edit + Post Comment button |
| `handover/src/main/kotlin/.../handover/ui/panels/TimeLogPanel.kt` | Hours stepper + date picker + Log Work button |
| `handover/src/main/kotlin/.../handover/ui/panels/QaClipboardPanel.kt` | Formatted text area + Copy All button |
| `handover/src/main/kotlin/.../handover/ui/panels/CompletionMacroPanel.kt` | Checkbox chain + Run Macro button + progress indicator |

> **Path prefix:** `...` = `com/workflow/orchestrator` throughout this plan.

> **Note:** The spec's `listeners/HandoverEventCollector.kt` is intentionally merged into `HandoverStateService`. The accumulator subscribes to EventBus directly in its constructor — a separate listener class would add indirection without benefit. The `listeners/` directory is not created.

### Modified Files

| File | Change |
|------|--------|
| `settings.gradle.kts` | Add `":handover"` to `include()` |
| `build.gradle.kts` | Add `implementation(project(":handover"))` |
| `core/.../core/events/WorkflowEvent.kt` | Add 3 nested data classes: `PullRequestCreated`, `JiraCommentPosted`, `PreReviewFinished` |
| `core/.../core/settings/PluginSettings.kt` | Add 4 new fields: `defaultTargetBranch`, `bitbucketProjectKey`, `bitbucketRepoSlug`, `startWorkTimestamp` |
| `src/main/resources/META-INF/plugin.xml` | Register handover services, tab provider, notification group |

### Test Files

| File | Tests |
|------|-------|
| `handover/src/test/.../handover/model/HandoverModelsTest.kt` | Model construction, defaults, SuiteResult accumulation |
| `handover/src/test/.../handover/api/BitbucketApiClientTest.kt` | MockWebServer: create PR, duplicate detection, auth, errors |
| `handover/src/test/.../handover/api/HandoverJiraClientTest.kt` | MockWebServer: addComment, logWork, getTransitions, transitionIssue |
| `handover/src/test/.../handover/service/CopyrightFixServiceTest.kt` | Year parsing, range consolidation, header injection, edge cases |
| `handover/src/test/.../handover/service/HandoverStateServiceTest.kt` | Event accumulation, state transitions, reset on ticket change |
| `handover/src/test/.../handover/service/JiraClosureServiceTest.kt` | Wiki markup building, edge cases (no suites, partial results) |
| `handover/src/test/.../handover/service/QaClipboardServiceTest.kt` | Clipboard formatting, empty state |
| `handover/src/test/.../handover/service/TimeTrackingServiceTest.kt` | Validation (max 7h, zero hours, future date) |
| `handover/src/test/.../handover/service/PrServiceTest.kt` | Git remote parsing, PR orchestration, duplicate detection |
| `handover/src/test/.../handover/service/CompletionMacroServiceTest.kt` | Chain execution, partial failure, cancellation |

### Test Fixtures

| File | Content |
|------|---------|
| `handover/src/test/resources/fixtures/bitbucket-pr-created.json` | Bitbucket PR creation response |
| `handover/src/test/resources/fixtures/bitbucket-pr-list.json` | Bitbucket PR list response (for duplicate detection) |
| `handover/src/test/resources/fixtures/jira-comment-response.json` | Jira comment creation response |
| `handover/src/test/resources/fixtures/jira-transitions.json` | Jira available transitions response |
| `handover/src/test/resources/fixtures/copyright-template.txt` | Sample copyright.txt template |

---

## Chunk 1: Module Scaffold, Core Models & Cross-Module Extensions

### Task 1: Create `:handover` Module Directory Structure and Build File

**Files:**
- Create: `handover/build.gradle.kts`

- [ ] **Step 1: Create module directories**

```bash
mkdir -p handover/src/main/kotlin/com/workflow/orchestrator/handover/{api/dto,service,model,ui/panels,listeners}
mkdir -p handover/src/test/kotlin/com/workflow/orchestrator/handover/{api,service,model}
mkdir -p handover/src/test/resources/fixtures
```

- [ ] **Step 2: Create build.gradle.kts**

```kotlin
// handover/build.gradle.kts — Submodule for task handover & closure lifecycle.
// Uses the MODULE variant; depends on :core only.

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlinSerialization)
    id("org.jetbrains.intellij.platform.module")
}

kotlin {
    jvmToolchain(providers.gradleProperty("javaVersion").get().toInt())
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
        bundledPlugin("Git4Idea")
    }

    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.turbine)
}

tasks.test {
    useJUnitPlatform()
}
```

- [ ] **Step 3: Register module in settings.gradle.kts**

In `settings.gradle.kts`, add `":handover"` to the `include()` block:

```kotlin
include(
    ":core",
    ":jira",
    ":git-integration",
    ":bamboo",
    ":sonar",
    ":cody",
    ":automation",
    ":handover",
)
```

- [ ] **Step 4: Add module to root build.gradle.kts**

In `build.gradle.kts`, add to the `dependencies` block after the `:automation` line:

```kotlin
implementation(project(":handover"))
```

- [ ] **Step 5: Verify Gradle sync**

Run: `./gradlew :handover:dependencies --configuration implementation`
Expected: Resolves successfully, shows `:core` as dependency.

- [ ] **Step 6: Commit**

```bash
git add handover/ settings.gradle.kts build.gradle.kts
git commit -m "feat(handover): scaffold :handover module with build config"
```

---

### Task 2: Define Core Data Models

**Files:**
- Create: `handover/src/main/kotlin/com/workflow/orchestrator/handover/model/HandoverModels.kt`
- Create: `handover/src/test/kotlin/com/workflow/orchestrator/handover/model/HandoverModelsTest.kt`

- [ ] **Step 1: Write model tests**

```kotlin
package com.workflow.orchestrator.handover.model

import com.workflow.orchestrator.core.events.WorkflowEvent
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class HandoverModelsTest {

    @Test
    fun `HandoverState defaults to empty state`() {
        val state = HandoverState()
        assertEquals("", state.ticketId)
        assertEquals("", state.ticketSummary)
        assertNull(state.prUrl)
        assertFalse(state.prCreated)
        assertNull(state.buildStatus)
        assertNull(state.qualityGatePassed)
        assertTrue(state.suiteResults.isEmpty())
        assertFalse(state.copyrightFixed)
        assertFalse(state.jiraCommentPosted)
        assertFalse(state.jiraTransitioned)
        assertFalse(state.todayWorkLogged)
        assertEquals(0L, state.startWorkTimestamp)
    }

    @Test
    fun `SuiteResult tracks running state with null passed`() {
        val result = SuiteResult(
            suitePlanKey = "PROJ-REGR",
            buildResultKey = "PROJ-REGR-42",
            dockerTagsJson = """{"my-service":"1.2.3"}""",
            passed = null,
            durationMs = null,
            triggeredAt = Instant.now(),
            bambooLink = "https://bamboo.example.com/browse/PROJ-REGR-42"
        )
        assertNull(result.passed)
        assertNull(result.durationMs)
    }

    @Test
    fun `SuiteResult tracks completed state`() {
        val result = SuiteResult(
            suitePlanKey = "PROJ-SMOKE",
            buildResultKey = "PROJ-SMOKE-18",
            dockerTagsJson = """{"my-service":"1.2.3"}""",
            passed = true,
            durationMs = 120_000L,
            triggeredAt = Instant.now(),
            bambooLink = "https://bamboo.example.com/browse/PROJ-SMOKE-18"
        )
        assertTrue(result.passed!!)
        assertEquals(120_000L, result.durationMs)
    }

    @Test
    fun `BuildSummary captures build info`() {
        val summary = BuildSummary(
            buildNumber = 42,
            status = WorkflowEvent.BuildEventStatus.SUCCESS,
            planKey = "PROJ-BUILD"
        )
        assertEquals(42, summary.buildNumber)
        assertEquals(WorkflowEvent.BuildEventStatus.SUCCESS, summary.status)
    }

    @Test
    fun `CopyrightFileEntry tracks file copyright status`() {
        val entry = CopyrightFileEntry(
            filePath = "src/main/java/Foo.java",
            status = CopyrightStatus.YEAR_OUTDATED,
            oldYear = "2025",
            newYear = "2025-2026"
        )
        assertEquals(CopyrightStatus.YEAR_OUTDATED, entry.status)
        assertEquals("2025", entry.oldYear)
        assertEquals("2025-2026", entry.newYear)
    }

    @Test
    fun `ReviewFinding sorts by severity`() {
        val findings = listOf(
            ReviewFinding(FindingSeverity.LOW, "a.kt", 10, "minor", "unused-import"),
            ReviewFinding(FindingSeverity.HIGH, "b.kt", 20, "critical", "missing-transactional"),
            ReviewFinding(FindingSeverity.MEDIUM, "c.kt", 30, "moderate", "unclosed-resource")
        )
        val sorted = findings.sortedBy { it.severity.ordinal }
        assertEquals(FindingSeverity.HIGH, sorted[0].severity)
        assertEquals(FindingSeverity.MEDIUM, sorted[1].severity)
        assertEquals(FindingSeverity.LOW, sorted[2].severity)
    }

    @Test
    fun `MacroStep tracks execution state`() {
        val step = MacroStep(
            id = "jira-comment",
            label = "Post Jira Comment",
            enabled = true,
            status = MacroStepStatus.PENDING
        )
        assertEquals(MacroStepStatus.PENDING, step.status)
        assertTrue(step.enabled)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :handover:test --tests "*.HandoverModelsTest" -v`
Expected: Compilation fails — models not defined yet.

- [ ] **Step 3: Write models**

```kotlin
package com.workflow.orchestrator.handover.model

import com.workflow.orchestrator.core.events.WorkflowEvent
import java.time.Instant

data class HandoverState(
    val ticketId: String = "",
    val ticketSummary: String = "",
    val currentBranch: String = "",

    // PR status
    val prUrl: String? = null,
    val prCreated: Boolean = false,

    // Build status (from BuildFinished events)
    val buildStatus: BuildSummary? = null,

    // Quality gate (from QualityGateResult events)
    val qualityGatePassed: Boolean? = null,

    // Health check (from HealthCheckFinished events)
    val healthCheckPassed: Boolean? = null,

    // Automation suites (accumulated from AutomationTriggered + AutomationFinished)
    val suiteResults: List<SuiteResult> = emptyList(),

    // Action completion tracking
    val copyrightFixed: Boolean = false,
    val jiraCommentPosted: Boolean = false,
    val jiraTransitioned: Boolean = false,
    val todayWorkLogged: Boolean = false,

    // Start work timestamp (from PluginSettings)
    val startWorkTimestamp: Long = 0L
)

data class SuiteResult(
    val suitePlanKey: String,
    val buildResultKey: String,
    val dockerTagsJson: String,
    val passed: Boolean?,
    val durationMs: Long?,
    val triggeredAt: Instant,
    val bambooLink: String
)

data class BuildSummary(
    val buildNumber: Int,
    val status: WorkflowEvent.BuildEventStatus,
    val planKey: String
)

// Copyright models
data class CopyrightFileEntry(
    val filePath: String,
    val status: CopyrightStatus,
    val oldYear: String? = null,
    val newYear: String? = null
)

enum class CopyrightStatus {
    OK,
    YEAR_OUTDATED,
    MISSING_HEADER
}

// Cody pre-review models
data class ReviewFinding(
    val severity: FindingSeverity,
    val filePath: String,
    val lineNumber: Int,
    val message: String,
    val pattern: String
)

enum class FindingSeverity { HIGH, MEDIUM, LOW }

// Macro models
data class MacroStep(
    val id: String,
    val label: String,
    val enabled: Boolean = true,
    val status: MacroStepStatus = MacroStepStatus.PENDING
)

enum class MacroStepStatus { PENDING, RUNNING, SUCCESS, FAILED, SKIPPED }

// Clipboard payload
data class ClipboardPayload(
    val dockerTags: Map<String, String>,
    val suiteLinks: List<SuiteLinkEntry>,
    val ticketIds: List<String>
)

data class SuiteLinkEntry(
    val suiteName: String,
    val passed: Boolean,
    val link: String
)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :handover:test --tests "*.HandoverModelsTest" -v`
Expected: All 7 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add handover/src/
git commit -m "feat(handover): add core data models with tests"
```

---

### Task 3: Add WorkflowEvent Extensions in :core

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/events/WorkflowEvent.kt`

- [ ] **Step 1: Add 3 new event types**

Add inside the `WorkflowEvent` sealed class, after `QueuePositionChanged`:

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

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :core:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/events/WorkflowEvent.kt
git commit -m "feat(core): add PullRequestCreated, JiraCommentPosted, PreReviewFinished events"
```

---

### Task 4: Add PluginSettings Extensions in :core

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/settings/PluginSettings.kt`

- [ ] **Step 1: Add 4 new fields inside `class State : BaseState()`**

Add after the `queueBuildQueuedTimeoutSeconds` field:

```kotlin
        // Phase 2B: Handover settings
        var defaultTargetBranch by string("develop")
        var bitbucketProjectKey by string("")
        var bitbucketRepoSlug by string("")
        var startWorkTimestamp by property(0L)
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :core:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/settings/PluginSettings.kt
git commit -m "feat(core): add handover settings (target branch, bitbucket keys, start timestamp)"
```

---

### Task 5: Create Test Fixtures

**Files:**
- Create: `handover/src/test/resources/fixtures/bitbucket-pr-created.json`
- Create: `handover/src/test/resources/fixtures/bitbucket-pr-list.json`
- Create: `handover/src/test/resources/fixtures/jira-comment-response.json`
- Create: `handover/src/test/resources/fixtures/jira-transitions.json`
- Create: `handover/src/test/resources/fixtures/copyright-template.txt`

- [ ] **Step 1: Create Bitbucket PR created fixture**

```json
{
  "id": 42,
  "title": "PROJ-123: Add login feature",
  "state": "OPEN",
  "links": {
    "self": [
      {
        "href": "https://bitbucket.example.com/projects/PROJ/repos/my-service/pull-requests/42"
      }
    ]
  }
}
```

- [ ] **Step 2: Create Bitbucket PR list fixture**

```json
{
  "size": 1,
  "limit": 25,
  "isLastPage": true,
  "values": [
    {
      "id": 42,
      "title": "PROJ-123: Add login feature",
      "state": "OPEN",
      "links": {
        "self": [
          {
            "href": "https://bitbucket.example.com/projects/PROJ/repos/my-service/pull-requests/42"
          }
        ]
      }
    }
  ]
}
```

- [ ] **Step 3: Create Jira comment response fixture**

```json
{
  "self": "https://jira.example.com/rest/api/2/issue/PROJ-123/comment/10042",
  "id": "10042",
  "body": "h4. Automation Results\n|| Suite || Status || Link ||\n| Regression Suite A | (/) PASS | [View Results|https://bamboo.example.com/browse/RS-42] |",
  "created": "2026-03-12T10:30:00.000+0000"
}
```

- [ ] **Step 4: Create Jira transitions fixture**

```json
{
  "transitions": [
    {
      "id": "21",
      "name": "In Review",
      "to": {
        "id": "4",
        "name": "In Review"
      }
    },
    {
      "id": "31",
      "name": "Done",
      "to": {
        "id": "5",
        "name": "Done"
      }
    }
  ]
}
```

- [ ] **Step 5: Create copyright template fixture**

```
Copyright (c) {year} MyCompany Ltd.
All rights reserved.

This software is proprietary and confidential.
```

- [ ] **Step 6: Commit**

```bash
git add handover/src/test/resources/fixtures/
git commit -m "test(handover): add API response fixtures for Bitbucket, Jira, copyright"
```

---

**End of Chunk 1** — Module scaffold, models, cross-module extensions, and test fixtures are complete.

---

## Chunk 2: API Clients (BitbucketApiClient + HandoverJiraClient)

### Task 6: Implement BitbucketApiClient with TDD

**Files:**
- Create: `handover/src/main/kotlin/com/workflow/orchestrator/handover/api/dto/BitbucketDtos.kt`
- Create: `handover/src/main/kotlin/com/workflow/orchestrator/handover/api/BitbucketApiClient.kt`
- Create: `handover/src/test/kotlin/com/workflow/orchestrator/handover/api/BitbucketApiClientTest.kt`

- [ ] **Step 1: Write DTOs**

```kotlin
package com.workflow.orchestrator.handover.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class BitbucketPrResponse(
    val id: Int,
    val title: String,
    val state: String,
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

@Serializable
data class BitbucketPrListResponse(
    val size: Int,
    val values: List<BitbucketPrResponse>
)

/** Request body for PR creation — not deserialized, only serialized. */
@Serializable
data class BitbucketPrRequest(
    val title: String,
    val description: String,
    val fromRef: BitbucketRef,
    val toRef: BitbucketRef
)

@Serializable
data class BitbucketRef(
    val id: String
)
```

- [ ] **Step 2: Write failing tests**

```kotlin
package com.workflow.orchestrator.handover.api

import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class BitbucketApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: BitbucketApiClient

    private fun fixture(name: String): String =
        javaClass.classLoader.getResource("fixtures/$name")!!.readText()

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = BitbucketApiClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            tokenProvider = { "test-token" }
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `testConnection returns success on 200`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val result = client.testConnection()

        assertTrue(result.isSuccess)
        val recorded = server.takeRequest()
        assertEquals("/rest/api/1.0/users", recorded.path)
        assertEquals("Bearer test-token", recorded.getHeader("Authorization"))
    }

    @Test
    fun `testConnection returns AUTH_FAILED on 401`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = client.testConnection()

        assertTrue(result.isError)
        assertEquals(ErrorType.AUTH_FAILED, (result as ApiResult.Error).type)
    }

    @Test
    fun `createPullRequest sends correct payload and returns PR`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("bitbucket-pr-created.json")))

        val result = client.createPullRequest(
            projectKey = "PROJ",
            repoSlug = "my-service",
            title = "PROJ-123: Add login feature",
            description = "Cody-generated description",
            fromBranch = "feature/PROJ-123-add-login",
            toBranch = "develop"
        )

        assertTrue(result.isSuccess)
        val pr = (result as ApiResult.Success).data
        assertEquals(42, pr.id)
        assertEquals("OPEN", pr.state)
        assertTrue(pr.links.self[0].href.contains("pull-requests/42"))

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals(
            "/rest/api/1.0/projects/PROJ/repos/my-service/pull-requests",
            recorded.path
        )
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("refs/heads/feature/PROJ-123-add-login"))
        assertTrue(body.contains("refs/heads/develop"))
    }

    @Test
    fun `createPullRequest returns error on 409 conflict`() = runTest {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"errors":[{"message":"Already exists"}]}"""))

        val result = client.createPullRequest("PROJ", "my-service", "title", "desc", "branch", "develop")

        assertTrue(result.isError)
        assertEquals(ErrorType.VALIDATION_ERROR, (result as ApiResult.Error).type)
    }

    @Test
    fun `testConnection returns NETWORK_ERROR on IOException`() = runTest {
        server.shutdown()

        val result = client.testConnection()

        assertTrue(result.isError)
        assertEquals(ErrorType.NETWORK_ERROR, (result as ApiResult.Error).type)
    }

    @Test
    fun `createPullRequest returns FORBIDDEN on 403`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))

        val result = client.createPullRequest("PROJ", "repo", "title", "desc", "branch", "develop")

        assertTrue(result.isError)
        assertEquals(ErrorType.FORBIDDEN, (result as ApiResult.Error).type)
    }

    @Test
    fun `getPullRequestsForBranch returns matching PRs`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("bitbucket-pr-list.json")))

        val result = client.getPullRequestsForBranch("PROJ", "my-service", "feature/PROJ-123")

        assertTrue(result.isSuccess)
        val prs = (result as ApiResult.Success).data
        assertEquals(1, prs.size)
        assertEquals(42, prs[0].id)

        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.contains("state=OPEN"))
        assertTrue(recorded.path!!.contains("at=refs/heads/feature/PROJ-123"))
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :handover:test --tests "*.BitbucketApiClientTest" -v`
Expected: Compilation fails — `BitbucketApiClient` not defined.

- [ ] **Step 4: Implement BitbucketApiClient**

```kotlin
package com.workflow.orchestrator.handover.api

import com.workflow.orchestrator.core.http.AuthInterceptor
import com.workflow.orchestrator.core.http.AuthScheme
import com.workflow.orchestrator.core.http.RetryInterceptor
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.handover.api.dto.BitbucketPrListResponse
import com.workflow.orchestrator.handover.api.dto.BitbucketPrRequest
import com.workflow.orchestrator.handover.api.dto.BitbucketPrResponse
import com.workflow.orchestrator.handover.api.dto.BitbucketRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class BitbucketApiClient(
    private val baseUrl: String,
    private val tokenProvider: () -> String?
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(tokenProvider, AuthScheme.BEARER))
            .addInterceptor(RetryInterceptor())
            .build()
    }

    suspend fun testConnection(): ApiResult<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/1.0/users")
                    .get()
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> ApiResult.Success(Unit)
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bitbucket token")
                        403 -> ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient Bitbucket permissions")
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Bitbucket returned ${it.code}")
                    }
                }
            } catch (e: IOException) {
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bitbucket: ${e.message}", e)
            }
        }

    suspend fun createPullRequest(
        projectKey: String,
        repoSlug: String,
        title: String,
        description: String,
        fromBranch: String,
        toBranch: String
    ): ApiResult<BitbucketPrResponse> =
        withContext(Dispatchers.IO) {
            try {
                val payload = BitbucketPrRequest(
                    title = title,
                    description = description,
                    fromRef = BitbucketRef("refs/heads/$fromBranch"),
                    toRef = BitbucketRef("refs/heads/$toBranch")
                )
                val body = json.encodeToString(payload)
                    .toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests")
                    .post(body)
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> {
                            val bodyStr = it.body?.string() ?: ""
                            ApiResult.Success(json.decodeFromString<BitbucketPrResponse>(bodyStr))
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bitbucket token")
                        403 -> ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient Bitbucket permissions")
                        409 -> ApiResult.Error(ErrorType.VALIDATION_ERROR, "PR already exists for this branch")
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Bitbucket returned ${it.code}")
                    }
                }
            } catch (e: IOException) {
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bitbucket: ${e.message}", e)
            }
        }

    suspend fun getPullRequestsForBranch(
        projectKey: String,
        repoSlug: String,
        branchName: String
    ): ApiResult<List<BitbucketPrResponse>> =
        withContext(Dispatchers.IO) {
            try {
                val encodedBranch = URLEncoder.encode("refs/heads/$branchName", "UTF-8")
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests?at=$encodedBranch&state=OPEN")
                    .get()
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> {
                            val bodyStr = it.body?.string() ?: ""
                            val parsed = json.decodeFromString<BitbucketPrListResponse>(bodyStr)
                            ApiResult.Success(parsed.values)
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bitbucket token")
                        403 -> ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient Bitbucket permissions")
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "Repository not found")
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Bitbucket returned ${it.code}")
                    }
                }
            } catch (e: IOException) {
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bitbucket: ${e.message}", e)
            }
        }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :handover:test --tests "*.BitbucketApiClientTest" -v`
Expected: All 5 tests PASS.

- [ ] **Step 6: Commit**

```bash
git add handover/src/
git commit -m "feat(handover): add BitbucketApiClient with PR creation and duplicate detection"
```

---

### Task 7: Implement HandoverJiraClient with TDD

**Files:**
- Create: `handover/src/main/kotlin/com/workflow/orchestrator/handover/api/dto/JiraDtos.kt`
- Create: `handover/src/main/kotlin/com/workflow/orchestrator/handover/api/HandoverJiraClient.kt`
- Create: `handover/src/test/kotlin/com/workflow/orchestrator/handover/api/HandoverJiraClientTest.kt`

- [ ] **Step 1: Write DTOs**

```kotlin
package com.workflow.orchestrator.handover.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class JiraCommentResponse(
    val id: String,
    val body: String,
    val created: String? = null
)

@Serializable
data class JiraTransitionsResponse(
    val transitions: List<JiraTransition>
)

@Serializable
data class JiraTransition(
    val id: String,
    val name: String,
    val to: JiraTransitionTarget? = null
)

@Serializable
data class JiraTransitionTarget(
    val id: String,
    val name: String
)
```

- [ ] **Step 2: Write failing tests**

```kotlin
package com.workflow.orchestrator.handover.api

import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class HandoverJiraClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: HandoverJiraClient

    private fun fixture(name: String): String =
        javaClass.classLoader.getResource("fixtures/$name")!!.readText()

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = HandoverJiraClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            tokenProvider = { "test-token" }
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `addComment posts wiki markup and returns comment`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("jira-comment-response.json")))

        val result = client.addComment("PROJ-123", "h4. Automation Results\n|| Suite || Status ||")

        assertTrue(result.isSuccess)
        val comment = (result as ApiResult.Success).data
        assertEquals("10042", comment.id)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/rest/api/2/issue/PROJ-123/comment", recorded.path)
        assertEquals("Bearer test-token", recorded.getHeader("Authorization"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("Automation Results"))
    }

    @Test
    fun `addComment returns NOT_FOUND on 404`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = client.addComment("INVALID-999", "test")

        assertTrue(result.isError)
        assertEquals(ErrorType.NOT_FOUND, (result as ApiResult.Error).type)
    }

    @Test
    fun `logWork sends correct payload`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))

        val result = client.logWork(
            issueKey = "PROJ-123",
            timeSpentSeconds = 14400,
            comment = "Daily development work",
            started = "2026-03-12T09:00:00.000+0000"
        )

        assertTrue(result.isSuccess)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/rest/api/2/issue/PROJ-123/worklog", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("14400"))
        assertTrue(body.contains("Daily development work"))
        assertTrue(body.contains("2026-03-12"))
    }

    @Test
    fun `logWork without comment omits comment field`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))

        val result = client.logWork(
            issueKey = "PROJ-123",
            timeSpentSeconds = 7200,
            comment = null,
            started = "2026-03-12T09:00:00.000+0000"
        )

        assertTrue(result.isSuccess)
        val body = server.takeRequest().body.readUtf8()
        assertFalse(body.contains("comment"))
    }

    @Test
    fun `logWork returns NOT_FOUND on 404`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = client.logWork("INVALID-999", 3600, null, "2026-03-12T09:00:00.000+0000")

        assertTrue(result.isError)
        assertEquals(ErrorType.NOT_FOUND, (result as ApiResult.Error).type)
    }

    @Test
    fun `logWork returns FORBIDDEN on 403`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))

        val result = client.logWork("PROJ-123", 3600, null, "2026-03-12T09:00:00.000+0000")

        assertTrue(result.isError)
        assertEquals(ErrorType.FORBIDDEN, (result as ApiResult.Error).type)
    }

    @Test
    fun `addComment returns NETWORK_ERROR on IOException`() = runTest {
        server.shutdown()

        val result = client.addComment("PROJ-123", "test")

        assertTrue(result.isError)
        assertEquals(ErrorType.NETWORK_ERROR, (result as ApiResult.Error).type)
    }

    @Test
    fun `getTransitions returns available transitions`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("jira-transitions.json")))

        val result = client.getTransitions("PROJ-123")

        assertTrue(result.isSuccess)
        val transitions = (result as ApiResult.Success).data
        assertEquals(2, transitions.size)
        assertEquals("In Review", transitions[0].name)
        assertEquals("21", transitions[0].id)

        val recorded = server.takeRequest()
        assertEquals("/rest/api/2/issue/PROJ-123/transitions", recorded.path)
    }

    @Test
    fun `transitionIssue sends transition id`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = client.transitionIssue("PROJ-123", "21")

        assertTrue(result.isSuccess)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/rest/api/2/issue/PROJ-123/transitions", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains(""""id":"21""""))
    }

    @Test
    fun `transitionIssue returns AUTH_FAILED on 401`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = client.transitionIssue("PROJ-123", "21")

        assertTrue(result.isError)
        assertEquals(ErrorType.AUTH_FAILED, (result as ApiResult.Error).type)
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :handover:test --tests "*.HandoverJiraClientTest" -v`
Expected: Compilation fails — `HandoverJiraClient` not defined.

- [ ] **Step 4: Implement HandoverJiraClient**

```kotlin
package com.workflow.orchestrator.handover.api

import com.workflow.orchestrator.core.http.AuthInterceptor
import com.workflow.orchestrator.core.http.AuthScheme
import com.workflow.orchestrator.core.http.RetryInterceptor
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.handover.api.dto.JiraCommentResponse
import com.workflow.orchestrator.handover.api.dto.JiraTransition
import com.workflow.orchestrator.handover.api.dto.JiraTransitionsResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class HandoverJiraClient(
    private val baseUrl: String,
    private val tokenProvider: () -> String?
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(tokenProvider, AuthScheme.BEARER))
            .addInterceptor(RetryInterceptor())
            .build()
    }

    suspend fun addComment(issueKey: String, wikiMarkupBody: String): ApiResult<JiraCommentResponse> =
        withContext(Dispatchers.IO) {
            try {
                val payload = buildJsonObject { put("body", wikiMarkupBody) }.toString()
                val body = payload.toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/2/issue/$issueKey/comment")
                    .post(body)
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> {
                            val bodyStr = it.body?.string() ?: ""
                            ApiResult.Success(json.decodeFromString<JiraCommentResponse>(bodyStr))
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Jira token")
                        403 -> ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient Jira permissions")
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "Jira issue not found: $issueKey")
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Jira returned ${it.code}")
                    }
                }
            } catch (e: IOException) {
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Jira: ${e.message}", e)
            }
        }

    suspend fun logWork(
        issueKey: String,
        timeSpentSeconds: Int,
        comment: String?,
        started: String
    ): ApiResult<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val payload = buildJsonObject {
                    put("timeSpentSeconds", timeSpentSeconds)
                    put("started", started)
                    if (comment != null) {
                        put("comment", comment)
                    }
                }.toString()
                val body = payload.toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/2/issue/$issueKey/worklog")
                    .post(body)
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> ApiResult.Success(Unit)
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Jira token")
                        403 -> ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient Jira permissions")
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "Jira issue not found: $issueKey")
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Jira returned ${it.code}")
                    }
                }
            } catch (e: IOException) {
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Jira: ${e.message}", e)
            }
        }

    suspend fun getTransitions(issueKey: String): ApiResult<List<JiraTransition>> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/2/issue/$issueKey/transitions")
                    .get()
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> {
                            val bodyStr = it.body?.string() ?: ""
                            val parsed = json.decodeFromString<JiraTransitionsResponse>(bodyStr)
                            ApiResult.Success(parsed.transitions)
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Jira token")
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "Jira issue not found: $issueKey")
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Jira returned ${it.code}")
                    }
                }
            } catch (e: IOException) {
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Jira: ${e.message}", e)
            }
        }

    suspend fun transitionIssue(issueKey: String, transitionId: String): ApiResult<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val payload = buildJsonObject {
                    putJsonObject("transition") {
                        put("id", transitionId)
                    }
                }.toString()
                val body = payload.toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/2/issue/$issueKey/transitions")
                    .post(body)
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> ApiResult.Success(Unit)
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Jira token")
                        403 -> ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient Jira permissions")
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "Jira issue not found: $issueKey")
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Jira returned ${it.code}")
                    }
                }
            } catch (e: IOException) {
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Jira: ${e.message}", e)
            }
        }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :handover:test --tests "*.HandoverJiraClientTest" -v`
Expected: All 7 tests PASS.

- [ ] **Step 6: Commit**

```bash
git add handover/src/
git commit -m "feat(handover): add HandoverJiraClient with comment, worklog, transitions"
```

---

**End of Chunk 2** — Both API clients (BitbucketApiClient + HandoverJiraClient) are complete with full test coverage.

---

## Chunk 3: Core Services (CopyrightFixService, HandoverStateService, JiraClosureService, QaClipboardService)

### Task 8: Implement CopyrightFixService Year Logic with TDD

**Files:**
- Create: `handover/src/test/kotlin/com/workflow/orchestrator/handover/service/CopyrightFixServiceTest.kt`
- Create: `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/CopyrightFixService.kt`

- [ ] **Step 1: Write failing tests for year parsing and consolidation**

```kotlin
package com.workflow.orchestrator.handover.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CopyrightFixServiceTest {

    private val service = CopyrightFixService()

    // --- Year update logic ---

    @Test
    fun `updateYearInHeader updates single old year to range`() {
        val header = "Copyright (c) 2025 MyCompany Ltd."
        val result = service.updateYearInHeader(header, 2026)
        assertEquals("Copyright (c) 2025-2026 MyCompany Ltd.", result)
    }

    @Test
    fun `updateYearInHeader extends existing range`() {
        val header = "Copyright (c) 2019-2025 MyCompany Ltd."
        val result = service.updateYearInHeader(header, 2026)
        assertEquals("Copyright (c) 2019-2026 MyCompany Ltd.", result)
    }

    @Test
    fun `updateYearInHeader consolidates scattered years`() {
        val header = "Copyright (c) 2018, 2020-2023, 2025 MyCompany Ltd."
        val result = service.updateYearInHeader(header, 2026)
        assertEquals("Copyright (c) 2018-2026 MyCompany Ltd.", result)
    }

    @Test
    fun `updateYearInHeader leaves current year alone`() {
        val header = "Copyright (c) 2026 MyCompany Ltd."
        val result = service.updateYearInHeader(header, 2026)
        assertEquals("Copyright (c) 2026 MyCompany Ltd.", result)
    }

    @Test
    fun `updateYearInHeader leaves current range alone`() {
        val header = "Copyright (c) 2020-2026 MyCompany Ltd."
        val result = service.updateYearInHeader(header, 2026)
        assertEquals("Copyright (c) 2020-2026 MyCompany Ltd.", result)
    }

    @Test
    fun `updateYearInHeader handles no year in header`() {
        val header = "Copyright MyCompany Ltd."
        val result = service.updateYearInHeader(header, 2026)
        assertEquals("Copyright MyCompany Ltd.", result)
    }

    @Test
    fun `updateYearInHeader ignores non-year numbers`() {
        val header = "Copyright (c) 2025 MyCompany Ltd. v3.0"
        val result = service.updateYearInHeader(header, 2026)
        assertEquals("Copyright (c) 2025-2026 MyCompany Ltd. v3.0", result)
    }

    // --- Comment wrapping ---

    @Test
    fun `wrapForLanguage wraps Java with block comment`() {
        val template = "Copyright (c) 2026 MyCompany\nAll rights reserved."
        val result = service.wrapForLanguage(template, "java")
        assertTrue(result.startsWith("/*"))
        assertTrue(result.endsWith("*/"))
        assertTrue(result.contains(" * Copyright (c) 2026 MyCompany"))
    }

    @Test
    fun `wrapForLanguage wraps Kotlin with block comment`() {
        val result = service.wrapForLanguage("Copyright 2026", "kt")
        assertTrue(result.startsWith("/*"))
        assertTrue(result.endsWith("*/"))
    }

    @Test
    fun `wrapForLanguage wraps XML with HTML comment`() {
        val result = service.wrapForLanguage("Copyright 2026", "xml")
        assertTrue(result.startsWith("<!--"))
        assertTrue(result.endsWith("-->"))
    }

    @Test
    fun `wrapForLanguage wraps properties with hash comment`() {
        val result = service.wrapForLanguage("Copyright 2026\nAll rights.", "properties")
        assertTrue(result.contains("# Copyright 2026"))
        assertTrue(result.contains("# All rights."))
    }

    @Test
    fun `wrapForLanguage wraps yaml with hash comment`() {
        val result = service.wrapForLanguage("Copyright 2026", "yml")
        assertTrue(result.startsWith("# Copyright 2026"))
    }

    // --- Template loading ---

    @Test
    fun `prepareHeader replaces year placeholder`() {
        val template = "Copyright (c) {year} MyCompany Ltd.\nAll rights reserved."
        val result = service.prepareHeader(template, 2026)
        assertEquals("Copyright (c) 2026 MyCompany Ltd.\nAll rights reserved.", result)
    }

    // --- Header detection ---

    @Test
    fun `hasCopyrightHeader returns true when header present`() {
        val content = "/*\n * Copyright (c) 2025 MyCompany\n */\npackage com.example;"
        assertTrue(service.hasCopyrightHeader(content))
    }

    @Test
    fun `hasCopyrightHeader returns false when no header`() {
        val content = "package com.example;\n\nclass Foo {}"
        assertFalse(service.hasCopyrightHeader(content))
    }

    // --- Source file filtering ---

    @Test
    fun `isSourceFile returns true for supported extensions`() {
        assertTrue(service.isSourceFile("Foo.java"))
        assertTrue(service.isSourceFile("Bar.kt"))
        assertTrue(service.isSourceFile("build.gradle.kts"))
        assertTrue(service.isSourceFile("pom.xml"))
        assertTrue(service.isSourceFile("app.yml"))
        assertTrue(service.isSourceFile("app.yaml"))
        assertTrue(service.isSourceFile("config.properties"))
    }

    @Test
    fun `isGeneratedPath returns true for build output paths`() {
        assertTrue(service.isGeneratedPath("target/classes/Foo.java"))
        assertTrue(service.isGeneratedPath("build/generated/Source.kt"))
        assertTrue(service.isGeneratedPath(".gradle/caches/file.kt"))
        assertTrue(service.isGeneratedPath("node_modules/pkg/index.js"))
    }

    @Test
    fun `isGeneratedPath returns false for source paths`() {
        assertFalse(service.isGeneratedPath("src/main/java/Foo.java"))
        assertFalse(service.isGeneratedPath("src/test/kotlin/Bar.kt"))
    }

    @Test
    fun `isSourceFile returns false for non-source files`() {
        assertFalse(service.isSourceFile("image.png"))
        assertFalse(service.isSourceFile("data.csv"))
        assertFalse(service.isSourceFile("README.md"))
        assertFalse(service.isSourceFile("app.jar"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :handover:test --tests "*.CopyrightFixServiceTest" -v`
Expected: Compilation fails — `CopyrightFixService` not defined.

- [ ] **Step 3: Implement CopyrightFixService**

```kotlin
package com.workflow.orchestrator.handover.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.handover.model.CopyrightFileEntry
import com.workflow.orchestrator.handover.model.CopyrightStatus
import java.time.Year

@Service(Service.Level.PROJECT)
class CopyrightFixService {

    private var project: Project? = null

    /** IntelliJ DI constructor. */
    constructor(project: Project) {
        this.project = project
    }

    /** Test constructor — no project needed for pure logic tests. */
    constructor()

    private val YEAR_PATTERN = Regex("""\b((?:19|20)\d{2})\b""")
    private val YEAR_RANGE_PATTERN = Regex("""((?:19|20)\d{2})\s*[-–]\s*((?:19|20)\d{2})""")
    private val FULL_YEAR_EXPR = Regex("""(?:(?:19|20)\d{2})(?:\s*[-–,]\s*(?:(?:19|20)\d{2}))*""")

    private val SOURCE_EXTENSIONS = setOf("java", "kt", "kts", "xml", "yaml", "yml", "properties")
    private val GENERATED_PATH_PREFIXES = listOf("target/", "build/", ".gradle/", "node_modules/", ".idea/")

    fun updateYearInHeader(headerText: String, currentYear: Int): String {
        val yearExprMatch = FULL_YEAR_EXPR.find(headerText) ?: return headerText
        val yearExpr = yearExprMatch.value

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
        val replacement = if (minYear == currentYear) "$currentYear" else "$minYear-$currentYear"
        if (yearExpr == replacement) return headerText

        return headerText.replaceRange(yearExprMatch.range, replacement)
    }

    fun wrapForLanguage(template: String, fileExtension: String): String {
        return when (fileExtension) {
            "java", "kt", "kts" -> "/*\n${template.lines().joinToString("\n") { " * $it" }}\n */"
            "xml" -> "<!--\n$template\n-->"
            "properties", "yaml", "yml" -> template.lines().joinToString("\n") { "# $it" }
            else -> template
        }
    }

    fun prepareHeader(template: String, currentYear: Int): String {
        return template.replace("{year}", currentYear.toString())
    }

    fun hasCopyrightHeader(content: String): Boolean {
        val headerRegion = content.lines().take(15).joinToString("\n").lowercase()
        return headerRegion.contains("copyright")
    }

    fun isSourceFile(fileName: String): Boolean {
        val extension = fileName.substringAfterLast('.', "")
        return extension in SOURCE_EXTENSIONS
    }

    fun isGeneratedPath(filePath: String): Boolean {
        return GENERATED_PATH_PREFIXES.any { filePath.startsWith(it) }
    }

    fun analyzeFile(
        filePath: String,
        content: String,
        currentYear: Int = Year.now().value
    ): CopyrightFileEntry {
        if (!hasCopyrightHeader(content)) {
            return CopyrightFileEntry(
                filePath = filePath,
                status = CopyrightStatus.MISSING_HEADER
            )
        }

        val headerRegion = content.lines().take(15).joinToString("\n")
        val updated = updateYearInHeader(headerRegion, currentYear)

        return if (updated == headerRegion) {
            CopyrightFileEntry(filePath = filePath, status = CopyrightStatus.OK)
        } else {
            val yearExprMatch = FULL_YEAR_EXPR.find(headerRegion)
            CopyrightFileEntry(
                filePath = filePath,
                status = CopyrightStatus.YEAR_OUTDATED,
                oldYear = yearExprMatch?.value,
                newYear = if (yearExprMatch != null) {
                    val allYears = mutableSetOf<Int>()
                    YEAR_RANGE_PATTERN.findAll(yearExprMatch.value).forEach { m ->
                        allYears.addAll(m.groupValues[1].toInt()..m.groupValues[2].toInt())
                    }
                    YEAR_PATTERN.findAll(yearExprMatch.value).forEach { m ->
                        allYears.add(m.groupValues[1].toInt())
                    }
                    val min = allYears.min()
                    if (min == currentYear) "$currentYear" else "$min-$currentYear"
                } else null
            )
        }
    }

    companion object {
        fun getInstance(project: Project): CopyrightFixService {
            return project.getService(CopyrightFixService::class.java)
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :handover:test --tests "*.CopyrightFixServiceTest" -v`
Expected: All 18 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add handover/src/
git commit -m "feat(handover): add CopyrightFixService with year consolidation logic"
```

---

### Task 9: Implement HandoverStateService with TDD

**Files:**
- Create: `handover/src/test/kotlin/com/workflow/orchestrator/handover/service/HandoverStateServiceTest.kt`
- Create: `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/HandoverStateService.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
package com.workflow.orchestrator.handover.service

import app.cash.turbine.test
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.handover.model.BuildSummary
import com.workflow.orchestrator.handover.model.HandoverState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class HandoverStateServiceTest {

    private lateinit var eventBus: EventBus
    private lateinit var settings: PluginSettings
    private lateinit var scope: CoroutineScope
    private lateinit var service: HandoverStateService

    @BeforeEach
    fun setUp() {
        eventBus = EventBus()
        settings = mockk(relaxed = true)
        every { settings.state } returns PluginSettings.State().apply {
            activeTicketId = "PROJ-123"
            activeTicketSummary = "Add login feature"
            bambooUrl = "https://bamboo.example.com"
        }
        scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        service = HandoverStateService(eventBus, settings, scope)
    }

    @AfterEach
    fun tearDown() {
        service.dispose()
    }

    @Test
    fun `initial state has ticket info from settings`() = runTest {
        service.stateFlow.test {
            val state = awaitItem()
            assertEquals("PROJ-123", state.ticketId)
            assertEquals("Add login feature", state.ticketSummary)
            assertFalse(state.prCreated)
            assertTrue(state.suiteResults.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `BuildFinished event updates buildStatus`() = runTest {
        service.stateFlow.test {
            skipItems(1) // initial

            eventBus.emit(WorkflowEvent.BuildFinished("PROJ-BUILD", 42, WorkflowEvent.BuildEventStatus.SUCCESS))

            val state = awaitItem()
            assertNotNull(state.buildStatus)
            assertEquals(42, state.buildStatus!!.buildNumber)
            assertEquals(WorkflowEvent.BuildEventStatus.SUCCESS, state.buildStatus!!.status)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `QualityGateResult event updates qualityGatePassed`() = runTest {
        service.stateFlow.test {
            skipItems(1)

            eventBus.emit(WorkflowEvent.QualityGateResult("proj-key", true))

            val state = awaitItem()
            assertTrue(state.qualityGatePassed!!)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `AutomationTriggered adds suite with null passed`() = runTest {
        service.stateFlow.test {
            skipItems(1)

            eventBus.emit(WorkflowEvent.AutomationTriggered(
                suitePlanKey = "PROJ-REGR",
                buildResultKey = "PROJ-REGR-42",
                dockerTagsJson = """{"my-service":"1.2.3"}""",
                triggeredBy = "user"
            ))

            val state = awaitItem()
            assertEquals(1, state.suiteResults.size)
            assertEquals("PROJ-REGR", state.suiteResults[0].suitePlanKey)
            assertNull(state.suiteResults[0].passed)
            assertEquals("https://bamboo.example.com/browse/PROJ-REGR-42", state.suiteResults[0].bambooLink)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `AutomationFinished updates matching suite`() = runTest {
        service.stateFlow.test {
            skipItems(1)

            eventBus.emit(WorkflowEvent.AutomationTriggered(
                suitePlanKey = "PROJ-REGR",
                buildResultKey = "PROJ-REGR-42",
                dockerTagsJson = """{"my-service":"1.2.3"}""",
                triggeredBy = "user"
            ))
            awaitItem() // triggered

            eventBus.emit(WorkflowEvent.AutomationFinished(
                suitePlanKey = "PROJ-REGR",
                buildResultKey = "PROJ-REGR-42",
                passed = true,
                durationMs = 120_000
            ))

            val state = awaitItem()
            assertEquals(1, state.suiteResults.size)
            assertTrue(state.suiteResults[0].passed!!)
            assertEquals(120_000L, state.suiteResults[0].durationMs)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `PullRequestCreated updates PR state`() = runTest {
        service.stateFlow.test {
            skipItems(1)

            eventBus.emit(WorkflowEvent.PullRequestCreated(
                prUrl = "https://bitbucket.example.com/pr/42",
                prNumber = 42,
                ticketId = "PROJ-123"
            ))

            val state = awaitItem()
            assertTrue(state.prCreated)
            assertEquals("https://bitbucket.example.com/pr/42", state.prUrl)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `JiraCommentPosted updates jiraCommentPosted`() = runTest {
        service.stateFlow.test {
            skipItems(1)

            eventBus.emit(WorkflowEvent.JiraCommentPosted(
                ticketId = "PROJ-123",
                commentId = "10042"
            ))

            val state = awaitItem()
            assertTrue(state.jiraCommentPosted)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `multiple suites accumulate correctly`() = runTest {
        service.stateFlow.test {
            skipItems(1)

            eventBus.emit(WorkflowEvent.AutomationTriggered("PROJ-REGR", "PROJ-REGR-42", "{}", "user"))
            awaitItem()
            eventBus.emit(WorkflowEvent.AutomationTriggered("PROJ-SMOKE", "PROJ-SMOKE-18", "{}", "user"))

            val state = awaitItem()
            assertEquals(2, state.suiteResults.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `latest run replaces older run for same suite`() = runTest {
        service.stateFlow.test {
            skipItems(1)

            eventBus.emit(WorkflowEvent.AutomationTriggered("PROJ-REGR", "PROJ-REGR-42", "{}", "user"))
            awaitItem()
            eventBus.emit(WorkflowEvent.AutomationTriggered("PROJ-REGR", "PROJ-REGR-43", "{}", "user"))

            val state = awaitItem()
            assertEquals(1, state.suiteResults.size)
            assertEquals("PROJ-REGR-43", state.suiteResults[0].buildResultKey)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `markCopyrightFixed updates state`() = runTest {
        service.stateFlow.test {
            skipItems(1)

            service.markCopyrightFixed()

            val state = awaitItem()
            assertTrue(state.copyrightFixed)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `markJiraTransitioned updates state`() = runTest {
        service.stateFlow.test {
            skipItems(1)

            service.markJiraTransitioned()

            val state = awaitItem()
            assertTrue(state.jiraTransitioned)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `markWorkLogged updates state`() = runTest {
        service.stateFlow.test {
            skipItems(1)

            service.markWorkLogged()

            val state = awaitItem()
            assertTrue(state.todayWorkLogged)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :handover:test --tests "*.HandoverStateServiceTest" -v`
Expected: Compilation fails — `HandoverStateService` not defined.

- [ ] **Step 3: Implement HandoverStateService**

```kotlin
package com.workflow.orchestrator.handover.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.handover.model.BuildSummary
import com.workflow.orchestrator.handover.model.HandoverState
import com.workflow.orchestrator.handover.model.SuiteResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

@Service(Service.Level.PROJECT)
class HandoverStateService : Disposable {

    private val eventBus: EventBus
    private val settings: PluginSettings
    private val scope: CoroutineScope

    /** IntelliJ DI constructor. */
    constructor(project: Project) {
        this.settings = PluginSettings.getInstance(project)
        this.eventBus = project.getService(EventBus::class.java)
        this.scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        initialize()
    }

    /** Test constructor — allows injecting mocks. */
    constructor(eventBus: EventBus, settings: PluginSettings, scope: CoroutineScope) {
        this.eventBus = eventBus
        this.settings = settings
        this.scope = scope
        initialize()
    }

    private val _stateFlow = MutableStateFlow(HandoverState())
    val stateFlow: StateFlow<HandoverState> = _stateFlow.asStateFlow()

    private fun initialize() {
        // Set initial state from settings
        _stateFlow.value = HandoverState(
            ticketId = settings.state.activeTicketId.orEmpty(),
            ticketSummary = settings.state.activeTicketSummary.orEmpty(),
            startWorkTimestamp = settings.state.startWorkTimestamp
        )

        // Subscribe to EventBus
        scope.launch {
            eventBus.events.collect { event ->
                handleEvent(event)
            }
        }

        // Auto-reset when active ticket changes in settings
        scope.launch {
            var previousTicketId = settings.state.activeTicketId.orEmpty()
            while (true) {
                kotlinx.coroutines.delay(2000)
                val currentTicketId = settings.state.activeTicketId.orEmpty()
                if (currentTicketId != previousTicketId && currentTicketId.isNotEmpty()) {
                    resetForNewTicket(currentTicketId, settings.state.activeTicketSummary.orEmpty())
                    previousTicketId = currentTicketId
                }
            }
        }
    }

    private fun handleEvent(event: WorkflowEvent) {
        val current = _stateFlow.value
        _stateFlow.value = when (event) {
            is WorkflowEvent.BuildFinished -> current.copy(
                buildStatus = BuildSummary(
                    buildNumber = event.buildNumber,
                    status = event.status,
                    planKey = event.planKey
                )
            )

            is WorkflowEvent.QualityGateResult -> current.copy(
                qualityGatePassed = event.passed
            )

            is WorkflowEvent.HealthCheckFinished -> current.copy(
                healthCheckPassed = event.passed
            )

            is WorkflowEvent.AutomationTriggered -> {
                val bambooUrl = settings.state.bambooUrl.orEmpty().trimEnd('/')
                val newSuite = SuiteResult(
                    suitePlanKey = event.suitePlanKey,
                    buildResultKey = event.buildResultKey,
                    dockerTagsJson = event.dockerTagsJson,
                    passed = null,
                    durationMs = null,
                    triggeredAt = Instant.now(),
                    bambooLink = "$bambooUrl/browse/${event.buildResultKey}"
                )
                // Replace existing entry for same suite plan key (latest run wins)
                val updated = current.suiteResults
                    .filter { it.suitePlanKey != event.suitePlanKey } + newSuite
                current.copy(suiteResults = updated)
            }

            is WorkflowEvent.AutomationFinished -> {
                val updated = current.suiteResults.map { suite ->
                    if (suite.buildResultKey == event.buildResultKey) {
                        suite.copy(passed = event.passed, durationMs = event.durationMs)
                    } else suite
                }
                current.copy(suiteResults = updated)
            }

            is WorkflowEvent.PullRequestCreated -> current.copy(
                prUrl = event.prUrl,
                prCreated = true
            )

            is WorkflowEvent.JiraCommentPosted -> current.copy(
                jiraCommentPosted = true
            )

            else -> current // Ignore events we don't care about
        }
    }

    fun markCopyrightFixed() {
        _stateFlow.value = _stateFlow.value.copy(copyrightFixed = true)
    }

    fun markJiraTransitioned() {
        _stateFlow.value = _stateFlow.value.copy(jiraTransitioned = true)
    }

    fun markWorkLogged() {
        _stateFlow.value = _stateFlow.value.copy(todayWorkLogged = true)
    }

    fun resetForNewTicket(ticketId: String, ticketSummary: String) {
        _stateFlow.value = HandoverState(
            ticketId = ticketId,
            ticketSummary = ticketSummary,
            startWorkTimestamp = settings.state.startWorkTimestamp
        )
    }

    override fun dispose() {
        scope.cancel()
    }

    companion object {
        fun getInstance(project: Project): HandoverStateService {
            return project.getService(HandoverStateService::class.java)
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :handover:test --tests "*.HandoverStateServiceTest" -v`
Expected: All 11 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add handover/src/
git commit -m "feat(handover): add HandoverStateService event accumulator with reactive state"
```

---

### Task 10: Implement JiraClosureService with TDD

**Files:**
- Create: `handover/src/test/kotlin/com/workflow/orchestrator/handover/service/JiraClosureServiceTest.kt`
- Create: `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/JiraClosureService.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
package com.workflow.orchestrator.handover.service

import com.workflow.orchestrator.handover.model.SuiteResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class JiraClosureServiceTest {

    private val service = JiraClosureService()

    @Test
    fun `buildClosureComment with passing suites`() {
        val suites = listOf(
            SuiteResult("PROJ-REGR", "PROJ-REGR-42", """{"my-service":"1.2.3-build.42"}""",
                true, 120_000, Instant.now(), "https://bamboo.example.com/browse/PROJ-REGR-42"),
            SuiteResult("PROJ-SMOKE", "PROJ-SMOKE-18", """{"my-service":"1.2.3-build.42"}""",
                true, 60_000, Instant.now(), "https://bamboo.example.com/browse/PROJ-SMOKE-18")
        )

        val comment = service.buildClosureComment(suites)

        assertTrue(comment.contains("h4. Automation Results"))
        assertTrue(comment.contains("|| Suite || Status || Link ||"))
        assertTrue(comment.contains("PROJ-REGR"))
        assertTrue(comment.contains("(/) PASS"))
        assertTrue(comment.contains("[View Results|https://bamboo.example.com/browse/PROJ-REGR-42]"))
        assertTrue(comment.contains("h4. Docker Tags"))
        assertTrue(comment.contains("my-service"))
        assertTrue(comment.contains("1.2.3-build.42"))
    }

    @Test
    fun `buildClosureComment with mixed pass and fail`() {
        val suites = listOf(
            SuiteResult("PROJ-REGR", "PROJ-REGR-42", """{"my-service":"1.2.3"}""",
                true, 120_000, Instant.now(), "https://bamboo.example.com/browse/PROJ-REGR-42"),
            SuiteResult("PROJ-SMOKE", "PROJ-SMOKE-18", """{"my-service":"1.2.3"}""",
                false, 30_000, Instant.now(), "https://bamboo.example.com/browse/PROJ-SMOKE-18")
        )

        val comment = service.buildClosureComment(suites)

        assertTrue(comment.contains("(/) PASS"))
        assertTrue(comment.contains("(x) FAIL"))
    }

    @Test
    fun `buildClosureComment with running suite`() {
        val suites = listOf(
            SuiteResult("PROJ-REGR", "PROJ-REGR-42", """{}""",
                null, null, Instant.now(), "https://bamboo.example.com/browse/PROJ-REGR-42")
        )

        val comment = service.buildClosureComment(suites)

        assertTrue(comment.contains("(?) RUNNING"))
    }

    @Test
    fun `buildClosureComment with empty suites returns empty message`() {
        val comment = service.buildClosureComment(emptyList())
        assertEquals("", comment)
    }

    @Test
    fun `buildClosureComment merges docker tags from multiple suites`() {
        val suites = listOf(
            SuiteResult("PROJ-REGR", "PROJ-REGR-42",
                """{"my-service":"1.2.3","auth-service":"2.0.1"}""",
                true, 120_000, Instant.now(), "https://bamboo.example.com/browse/PROJ-REGR-42"),
            SuiteResult("PROJ-SMOKE", "PROJ-SMOKE-18",
                """{"my-service":"1.2.3"}""",
                true, 60_000, Instant.now(), "https://bamboo.example.com/browse/PROJ-SMOKE-18")
        )

        val comment = service.buildClosureComment(suites)

        assertTrue(comment.contains("my-service"))
        assertTrue(comment.contains("auth-service"))
    }

    @Test
    fun `buildClosureComment handles malformed docker tags gracefully`() {
        val suites = listOf(
            SuiteResult("PROJ-REGR", "PROJ-REGR-42", "not-valid-json",
                true, 120_000, Instant.now(), "https://bamboo.example.com/browse/PROJ-REGR-42")
        )

        val comment = service.buildClosureComment(suites)

        assertTrue(comment.contains("h4. Automation Results"))
        // Should still render suite results even if tags are malformed
        assertTrue(comment.contains("PROJ-REGR"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :handover:test --tests "*.JiraClosureServiceTest" -v`
Expected: Compilation fails.

- [ ] **Step 3: Implement JiraClosureService**

```kotlin
package com.workflow.orchestrator.handover.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.handover.model.SuiteResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

@Service(Service.Level.PROJECT)
class JiraClosureService {

    private var project: Project? = null

    constructor(project: Project) {
        this.project = project
    }

    constructor()

    private val json = Json { ignoreUnknownKeys = true }

    fun buildClosureComment(suiteResults: List<SuiteResult>): String {
        if (suiteResults.isEmpty()) return ""

        val sb = StringBuilder()

        // Suite results table
        sb.appendLine("h4. Automation Results")
        sb.appendLine("|| Suite || Status || Link ||")
        for (suite in suiteResults) {
            val statusIcon = when (suite.passed) {
                true -> "(/) PASS"
                false -> "(x) FAIL"
                null -> "(?) RUNNING"
            }
            sb.appendLine("| ${escapeWikiMarkup(suite.suitePlanKey)} | $statusIcon | [View Results|${suite.bambooLink}] |")
        }

        // Docker tags
        val mergedTags = mutableMapOf<String, String>()
        for (suite in suiteResults) {
            try {
                val parsed = json.decodeFromString<JsonObject>(suite.dockerTagsJson)
                for ((key, value) in parsed) {
                    mergedTags[key] = value.jsonPrimitive.content
                }
            } catch (_: Exception) {
                // Malformed JSON — skip this suite's tags
            }
        }

        if (mergedTags.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("h4. Docker Tags")
            sb.appendLine("{code:json}")
            sb.appendLine(buildJsonString(mergedTags))
            sb.append("{code}")
        }

        return sb.toString()
    }

    private fun escapeWikiMarkup(text: String): String {
        return text.replace("|", "\\|").replace("{", "\\{").replace("}", "\\}")
    }

    private fun buildJsonString(tags: Map<String, String>): String {
        val entries = tags.entries.joinToString(",\n  ") { (k, v) -> "\"$k\": \"$v\"" }
        return "{\n  $entries\n}"
    }

    companion object {
        fun getInstance(project: Project): JiraClosureService {
            return project.getService(JiraClosureService::class.java)
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :handover:test --tests "*.JiraClosureServiceTest" -v`
Expected: All 6 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add handover/src/
git commit -m "feat(handover): add JiraClosureService wiki-markup comment builder"
```

---

### Task 11: Implement QaClipboardService with TDD

**Files:**
- Create: `handover/src/test/kotlin/com/workflow/orchestrator/handover/service/QaClipboardServiceTest.kt`
- Create: `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/QaClipboardService.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
package com.workflow.orchestrator.handover.service

import com.workflow.orchestrator.handover.model.ClipboardPayload
import com.workflow.orchestrator.handover.model.SuiteLinkEntry
import com.workflow.orchestrator.handover.model.SuiteResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class QaClipboardServiceTest {

    private val service = QaClipboardService()

    @Test
    fun `formatForClipboard produces expected output`() {
        val payload = ClipboardPayload(
            dockerTags = mapOf("my-service" to "1.2.3-build.42", "auth-service" to "release-2.0.1"),
            suiteLinks = listOf(
                SuiteLinkEntry("Regression Suite A", true, "https://bamboo.example.com/browse/RS-42"),
                SuiteLinkEntry("Smoke Tests", true, "https://bamboo.example.com/browse/ST-18")
            ),
            ticketIds = listOf("PROJ-123")
        )

        val text = service.formatForClipboard(payload)

        assertTrue(text.contains("Docker Tags:"))
        assertTrue(text.contains("my-service: 1.2.3-build.42"))
        assertTrue(text.contains("auth-service: release-2.0.1"))
        assertTrue(text.contains("Automation Results:"))
        assertTrue(text.contains("Regression Suite A: PASS"))
        assertTrue(text.contains("https://bamboo.example.com/browse/RS-42"))
        assertTrue(text.contains("Tickets: PROJ-123"))
    }

    @Test
    fun `formatForClipboard shows FAIL for failed suites`() {
        val payload = ClipboardPayload(
            dockerTags = mapOf("svc" to "1.0"),
            suiteLinks = listOf(SuiteLinkEntry("Suite A", false, "https://example.com")),
            ticketIds = listOf("PROJ-123")
        )

        val text = service.formatForClipboard(payload)
        assertTrue(text.contains("Suite A: FAIL"))
    }

    @Test
    fun `formatForClipboard with multiple tickets`() {
        val payload = ClipboardPayload(
            dockerTags = mapOf("svc" to "1.0"),
            suiteLinks = emptyList(),
            ticketIds = listOf("PROJ-123", "PROJ-456")
        )

        val text = service.formatForClipboard(payload)
        assertTrue(text.contains("Tickets: PROJ-123, PROJ-456"))
    }

    @Test
    fun `buildPayloadFromSuiteResults extracts docker tags and links`() {
        val suites = listOf(
            SuiteResult("PROJ-REGR", "PROJ-REGR-42",
                """{"my-service":"1.2.3"}""",
                true, 120_000, Instant.now(), "https://bamboo.example.com/browse/PROJ-REGR-42")
        )

        val payload = service.buildPayloadFromSuiteResults(suites, listOf("PROJ-123"))

        assertEquals(1, payload.dockerTags.size)
        assertEquals("1.2.3", payload.dockerTags["my-service"])
        assertEquals(1, payload.suiteLinks.size)
        assertTrue(payload.suiteLinks[0].passed)
    }

    @Test
    fun `buildPayloadFromSuiteResults handles malformed JSON`() {
        val suites = listOf(
            SuiteResult("PROJ-REGR", "PROJ-REGR-42", "bad-json",
                true, 120_000, Instant.now(), "https://bamboo.example.com/browse/PROJ-REGR-42")
        )

        val payload = service.buildPayloadFromSuiteResults(suites, listOf("PROJ-123"))
        assertTrue(payload.dockerTags.isEmpty())
        assertEquals(1, payload.suiteLinks.size)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :handover:test --tests "*.QaClipboardServiceTest" -v`
Expected: Compilation fails.

- [ ] **Step 3: Implement QaClipboardService**

```kotlin
package com.workflow.orchestrator.handover.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.handover.model.ClipboardPayload
import com.workflow.orchestrator.handover.model.SuiteLinkEntry
import com.workflow.orchestrator.handover.model.SuiteResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

@Service(Service.Level.PROJECT)
class QaClipboardService {

    private var project: Project? = null

    constructor(project: Project) {
        this.project = project
    }

    constructor()

    private val json = Json { ignoreUnknownKeys = true }

    fun formatForClipboard(payload: ClipboardPayload): String {
        val sb = StringBuilder()

        if (payload.dockerTags.isNotEmpty()) {
            sb.appendLine("Docker Tags:")
            for ((service, tag) in payload.dockerTags) {
                sb.appendLine("  • $service: $tag")
            }
        }

        if (payload.suiteLinks.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.appendLine()
            sb.appendLine("Automation Results:")
            for (link in payload.suiteLinks) {
                val status = if (link.passed) "PASS" else "FAIL"
                sb.appendLine("  • ${link.suiteName}: $status — ${link.link}")
            }
        }

        if (payload.ticketIds.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.appendLine()
            sb.append("Tickets: ${payload.ticketIds.joinToString(", ")}")
        }

        return sb.toString()
    }

    fun buildPayloadFromSuiteResults(
        suiteResults: List<SuiteResult>,
        ticketIds: List<String>
    ): ClipboardPayload {
        val mergedTags = mutableMapOf<String, String>()
        val suiteLinks = mutableListOf<SuiteLinkEntry>()

        for (suite in suiteResults) {
            // Extract docker tags
            try {
                val parsed = json.decodeFromString<JsonObject>(suite.dockerTagsJson)
                for ((key, value) in parsed) {
                    mergedTags[key] = value.jsonPrimitive.content
                }
            } catch (_: Exception) {
                // Skip malformed JSON
            }

            // Build suite link
            // Only include completed suites in clipboard
            if (suite.passed != null) {
                suiteLinks.add(SuiteLinkEntry(
                    suiteName = suite.suitePlanKey,
                    passed = suite.passed,
                    link = suite.bambooLink
                ))
            }
        }

        return ClipboardPayload(
            dockerTags = mergedTags,
            suiteLinks = suiteLinks,
            ticketIds = ticketIds
        )
    }

    companion object {
        fun getInstance(project: Project): QaClipboardService {
            return project.getService(QaClipboardService::class.java)
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :handover:test --tests "*.QaClipboardServiceTest" -v`
Expected: All 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add handover/src/
git commit -m "feat(handover): add QaClipboardService for formatted docker tag + suite link copy"
```

---

**End of Chunk 3** — Core services (CopyrightFixService, HandoverStateService, JiraClosureService, QaClipboardService) are complete with full TDD coverage.

---

## Chunk 4: Feature Services (TimeTrackingService, PrService, PreReviewService, CompletionMacroService)

### Task 12: Implement TimeTrackingService with TDD

**Files:**
- Create: `handover/src/test/kotlin/com/workflow/orchestrator/handover/service/TimeTrackingServiceTest.kt`
- Create: `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/TimeTrackingService.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
package com.workflow.orchestrator.handover.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TimeTrackingServiceTest {

    private val service = TimeTrackingService()

    @Test
    fun `validateHours accepts valid hours`() {
        assertTrue(service.validateHours(1.0))
        assertTrue(service.validateHours(4.0))
        assertTrue(service.validateHours(7.0))
        assertTrue(service.validateHours(0.5))
    }

    @Test
    fun `validateHours rejects zero`() {
        assertFalse(service.validateHours(0.0))
    }

    @Test
    fun `validateHours rejects negative`() {
        assertFalse(service.validateHours(-1.0))
    }

    @Test
    fun `validateHours clamps to max 7h`() {
        assertFalse(service.validateHours(8.0))
        assertFalse(service.validateHours(7.5))
    }

    @Test
    fun `hoursToSeconds converts correctly`() {
        assertEquals(3600, service.hoursToSeconds(1.0))
        assertEquals(7200, service.hoursToSeconds(2.0))
        assertEquals(1800, service.hoursToSeconds(0.5))
        assertEquals(25200, service.hoursToSeconds(7.0))
    }

    @Test
    fun `formatStartedDate produces ISO 8601`() {
        val result = service.formatStartedDate(2026, 3, 12, 9, 0)
        assertTrue(result.contains("2026-03-12"))
        assertTrue(result.contains("09:00:00"))
    }

    @Test
    fun `clampHours reduces values above 7`() {
        assertEquals(7.0, service.clampHours(8.0))
        assertEquals(7.0, service.clampHours(10.0))
    }

    @Test
    fun `clampHours passes through valid values`() {
        assertEquals(4.0, service.clampHours(4.0))
        assertEquals(0.5, service.clampHours(0.5))
    }

    @Test
    fun `isFutureDate rejects tomorrow`() {
        val tomorrow = java.time.LocalDate.now().plusDays(1)
        assertTrue(service.isFutureDate(tomorrow.year, tomorrow.monthValue, tomorrow.dayOfMonth))
    }

    @Test
    fun `isFutureDate accepts today`() {
        val today = java.time.LocalDate.now()
        assertFalse(service.isFutureDate(today.year, today.monthValue, today.dayOfMonth))
    }

    @Test
    fun `isFutureDate accepts yesterday`() {
        val yesterday = java.time.LocalDate.now().minusDays(1)
        assertFalse(service.isFutureDate(yesterday.year, yesterday.monthValue, yesterday.dayOfMonth))
    }

    @Test
    fun `computeElapsedHours returns correct duration`() {
        val now = System.currentTimeMillis()
        val twoHoursAgo = now - (2 * 3600 * 1000)
        val elapsed = service.computeElapsedHours(twoHoursAgo, now)
        assertEquals(2.0, elapsed, 0.1)
    }

    @Test
    fun `computeElapsedHours returns 0 for zero timestamp`() {
        val elapsed = service.computeElapsedHours(0L, System.currentTimeMillis())
        assertEquals(0.0, elapsed)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :handover:test --tests "*.TimeTrackingServiceTest" -v`
Expected: Compilation fails.

- [ ] **Step 3: Implement TimeTrackingService**

```kotlin
package com.workflow.orchestrator.handover.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Service(Service.Level.PROJECT)
class TimeTrackingService {

    private var project: Project? = null

    constructor(project: Project) {
        this.project = project
    }

    constructor()

    companion object {
        const val MAX_HOURS = 7.0
        private val ISO_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")

        fun getInstance(project: Project): TimeTrackingService {
            return project.getService(TimeTrackingService::class.java)
        }
    }

    fun validateHours(hours: Double): Boolean {
        return hours > 0.0 && hours <= MAX_HOURS
    }

    fun hoursToSeconds(hours: Double): Int {
        return (hours * 3600).toInt()
    }

    fun formatStartedDate(year: Int, month: Int, day: Int, hour: Int, minute: Int): String {
        val dt = LocalDateTime.of(year, month, day, hour, minute, 0)
        return dt.atOffset(ZoneOffset.UTC).format(ISO_FORMAT)
    }

    fun clampHours(hours: Double): Double {
        return hours.coerceAtMost(MAX_HOURS)
    }

    fun isFutureDate(year: Int, month: Int, day: Int): Boolean {
        val date = java.time.LocalDate.of(year, month, day)
        return date.isAfter(java.time.LocalDate.now())
    }

    fun computeElapsedHours(startTimestamp: Long, currentTimestamp: Long): Double {
        if (startTimestamp == 0L) return 0.0
        val diffMs = currentTimestamp - startTimestamp
        return diffMs.toDouble() / (3600.0 * 1000.0)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :handover:test --tests "*.TimeTrackingServiceTest" -v`
Expected: All 13 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add handover/src/
git commit -m "feat(handover): add TimeTrackingService with validation and elapsed time"
```

---

### Task 13: Implement PrService with TDD

**Files:**
- Create: `handover/src/test/kotlin/com/workflow/orchestrator/handover/service/PrServiceTest.kt`
- Create: `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/PrService.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
package com.workflow.orchestrator.handover.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PrServiceTest {

    private val service = PrService()

    // --- Git remote URL parsing ---

    @Test
    fun `parseGitRemote extracts project and repo from SSH URL`() {
        val result = service.parseGitRemote("ssh://git@bitbucket.example.com/PROJ/my-service.git")
        assertNotNull(result)
        assertEquals("PROJ", result!!.first)
        assertEquals("my-service", result.second)
    }

    @Test
    fun `parseGitRemote extracts from HTTPS URL`() {
        val result = service.parseGitRemote("https://bitbucket.example.com/scm/PROJ/my-service.git")
        assertNotNull(result)
        assertEquals("PROJ", result!!.first)
        assertEquals("my-service", result.second)
    }

    @Test
    fun `parseGitRemote extracts from SSH with port`() {
        val result = service.parseGitRemote("ssh://git@bitbucket.example.com:7999/PROJ/my-service.git")
        assertNotNull(result)
        assertEquals("PROJ", result!!.first)
        assertEquals("my-service", result.second)
    }

    @Test
    fun `parseGitRemote extracts from SCP-style URL`() {
        val result = service.parseGitRemote("git@bitbucket.example.com:PROJ/my-service.git")
        assertNotNull(result)
        assertEquals("PROJ", result!!.first)
        assertEquals("my-service", result.second)
    }

    @Test
    fun `parseGitRemote returns null for invalid URL`() {
        val result = service.parseGitRemote("not-a-url")
        assertNull(result)
    }

    @Test
    fun `parseGitRemote strips dot-git suffix`() {
        val result = service.parseGitRemote("ssh://git@bitbucket.example.com/PROJ/my-service.git")
        assertEquals("my-service", result!!.second)
    }

    // --- PR title generation ---

    @Test
    fun `buildPrTitle formats ticket and summary`() {
        val title = service.buildPrTitle("PROJ-123", "Add login feature")
        assertEquals("PROJ-123: Add login feature", title)
    }

    @Test
    fun `buildPrTitle truncates long summaries`() {
        val longSummary = "A".repeat(200)
        val title = service.buildPrTitle("PROJ-123", longSummary)
        assertTrue(title.length <= 120)
    }

    // --- Fallback description ---

    @Test
    fun `buildFallbackDescription includes ticket and branch`() {
        val desc = service.buildFallbackDescription("PROJ-123", "Add login", "feature/PROJ-123-login")
        assertTrue(desc.contains("PROJ-123"))
        assertTrue(desc.contains("Add login"))
        assertTrue(desc.contains("feature/PROJ-123-login"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :handover:test --tests "*.PrServiceTest" -v`
Expected: Compilation fails.

- [ ] **Step 3: Implement PrService**

```kotlin
package com.workflow.orchestrator.handover.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class PrService {

    private var project: Project? = null

    constructor(project: Project) {
        this.project = project
    }

    constructor()

    companion object {
        private const val MAX_TITLE_LENGTH = 120

        // SSH: ssh://git@host(:port)/PROJECT/repo.git
        private val SSH_URL_PATTERN = Regex("""ssh://[^/]+(?::\d+)?/([^/]+)/([^/]+?)(?:\.git)?$""")
        // HTTPS: https://host/scm/PROJECT/repo.git
        private val HTTPS_URL_PATTERN = Regex("""https?://[^/]+/scm/([^/]+)/([^/]+?)(?:\.git)?$""")
        // SCP-style: git@host:PROJECT/repo.git
        private val SCP_URL_PATTERN = Regex("""[^@]+@[^:]+:([^/]+)/([^/]+?)(?:\.git)?$""")

        fun getInstance(project: Project): PrService {
            return project.getService(PrService::class.java)
        }
    }

    fun parseGitRemote(remoteUrl: String): Pair<String, String>? {
        SSH_URL_PATTERN.find(remoteUrl)?.let { match ->
            return Pair(match.groupValues[1], match.groupValues[2])
        }
        HTTPS_URL_PATTERN.find(remoteUrl)?.let { match ->
            return Pair(match.groupValues[1], match.groupValues[2])
        }
        SCP_URL_PATTERN.find(remoteUrl)?.let { match ->
            return Pair(match.groupValues[1], match.groupValues[2])
        }
        return null
    }

    fun buildPrTitle(ticketId: String, ticketSummary: String): String {
        val full = "$ticketId: $ticketSummary"
        return if (full.length > MAX_TITLE_LENGTH) {
            full.take(MAX_TITLE_LENGTH - 3) + "..."
        } else {
            full
        }
    }

    fun buildFallbackDescription(ticketId: String, ticketSummary: String, branchName: String): String {
        return "$ticketId: $ticketSummary\n\nBranch: $branchName"
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :handover:test --tests "*.PrServiceTest" -v`
Expected: All 9 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add handover/src/
git commit -m "feat(handover): add PrService with Git remote parsing and PR title generation"
```

---

### Task 14: Implement PreReviewService with TDD

**Files:**
- Create: `handover/src/test/kotlin/com/workflow/orchestrator/handover/service/PreReviewServiceTest.kt`
- Create: `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/PreReviewService.kt`

> **Note:** Cody Agent JSON-RPC interaction is not unit-testable without the binary. However, `parseFindings()` and `buildReviewPrompt()` are pure logic — fully testable.

- [ ] **Step 1: Write tests for pure logic methods**

```kotlin
package com.workflow.orchestrator.handover.service

import com.workflow.orchestrator.handover.model.FindingSeverity
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PreReviewServiceTest {

    private val service = PreReviewService()

    @Test
    fun `parseFindings extracts structured findings from Cody response`() {
        val response = """
            **HIGH** `UserService.kt:42` — Missing @Transactional on DB write method [missing-transactional]
            **MEDIUM** `ApiController.kt:15` — Unclosed HTTP connection [unclosed-resource]
            **LOW** `Utils.kt:88` — Consider using lazy initialization [unused-import]
        """.trimIndent()

        val findings = service.parseFindings(response)

        assertEquals(3, findings.size)
        assertEquals(FindingSeverity.HIGH, findings[0].severity)
        assertEquals("UserService.kt", findings[0].filePath)
        assertEquals(42, findings[0].lineNumber)
        assertEquals("missing-transactional", findings[0].pattern)
        assertEquals(FindingSeverity.MEDIUM, findings[1].severity)
        assertEquals(FindingSeverity.LOW, findings[2].severity)
    }

    @Test
    fun `parseFindings returns sorted by severity`() {
        val response = """
            **LOW** `a.kt:1` — minor [minor]
            **HIGH** `b.kt:2` — critical [critical]
        """.trimIndent()

        val findings = service.parseFindings(response)

        assertEquals(FindingSeverity.HIGH, findings[0].severity)
        assertEquals(FindingSeverity.LOW, findings[1].severity)
    }

    @Test
    fun `parseFindings returns empty list for no matches`() {
        val findings = service.parseFindings("No issues found in the code.")
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `buildReviewPrompt includes diff content`() {
        val diff = "+fun newMethod() { }"
        val prompt = service.buildReviewPrompt(diff)

        assertTrue(prompt.contains("Spring Boot"))
        assertTrue(prompt.contains(diff))
        assertTrue(prompt.contains("missing-transactional"))
    }

    @Test
    fun `validateDiff returns error for empty diff`() {
        val result = service.validateDiff("")
        assertEquals(PreReviewService.DiffValidation.EMPTY, result)
    }

    @Test
    fun `validateDiff returns warning for large diff`() {
        val largeDiff = "a\n".repeat(11_000)
        val result = service.validateDiff(largeDiff)
        assertEquals(PreReviewService.DiffValidation.TOO_LARGE, result)
    }

    @Test
    fun `validateDiff returns OK for normal diff`() {
        val result = service.validateDiff("+fun foo() {}")
        assertEquals(PreReviewService.DiffValidation.OK, result)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :handover:test --tests "*.PreReviewServiceTest" -v`
Expected: Compilation fails.

- [ ] **Step 3: Create PreReviewService**

```kotlin
package com.workflow.orchestrator.handover.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.handover.model.FindingSeverity
import com.workflow.orchestrator.handover.model.ReviewFinding

@Service(Service.Level.PROJECT)
class PreReviewService {

    private var project: Project? = null

    constructor(project: Project) {
        this.project = project
    }

    constructor()

    /**
     * Parses Cody's text response into structured findings.
     * Cody returns free-text; we look for patterns like:
     * - **HIGH** `file.kt:42` — Missing @Transactional [missing-transactional]
     */
    fun parseFindings(codyResponse: String): List<ReviewFinding> {
        val findings = mutableListOf<ReviewFinding>()
        val pattern = Regex(
            """\*\*(HIGH|MEDIUM|LOW)\*\*\s+`([^:]+):(\d+)`\s*[-–—]\s*(.+?)\s*\[([^\]]+)]"""
        )

        for (match in pattern.findAll(codyResponse)) {
            val severity = when (match.groupValues[1]) {
                "HIGH" -> FindingSeverity.HIGH
                "MEDIUM" -> FindingSeverity.MEDIUM
                else -> FindingSeverity.LOW
            }
            findings.add(ReviewFinding(
                severity = severity,
                filePath = match.groupValues[2],
                lineNumber = match.groupValues[3].toIntOrNull() ?: 0,
                message = match.groupValues[4].trim(),
                pattern = match.groupValues[5]
            ))
        }

        return findings.sortedBy { it.severity.ordinal }
    }

    fun buildReviewPrompt(diff: String): String {
        return """
            |Analyze this Spring Boot code diff for anti-patterns and issues.
            |For each issue found, format as:
            |**SEVERITY** `file:line` — description [pattern-name]
            |
            |Where SEVERITY is HIGH, MEDIUM, or LOW.
            |Pattern names: missing-transactional, unclosed-resource, missing-error-handling, n-plus-one-query, missing-validation
            |
            |Diff:
            |```
            |$diff
            |```
        """.trimMargin()
    }

    enum class DiffValidation { OK, EMPTY, TOO_LARGE }

    fun validateDiff(diff: String): DiffValidation {
        if (diff.isBlank()) return DiffValidation.EMPTY
        if (diff.lines().size > 10_000) return DiffValidation.TOO_LARGE
        return DiffValidation.OK
    }

    companion object {
        fun getInstance(project: Project): PreReviewService {
            return project.getService(PreReviewService::class.java)
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :handover:test --tests "*.PreReviewServiceTest" -v`
Expected: All 7 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add handover/src/
git commit -m "feat(handover): add PreReviewService with response parsing and diff validation"
```

---

### Task 15: Implement CompletionMacroService with TDD

**Files:**
- Create: `handover/src/test/kotlin/com/workflow/orchestrator/handover/service/CompletionMacroServiceTest.kt`
- Create: `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/CompletionMacroService.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
package com.workflow.orchestrator.handover.service

import com.workflow.orchestrator.handover.model.MacroStep
import com.workflow.orchestrator.handover.model.MacroStepStatus
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CompletionMacroServiceTest {

    private val service = CompletionMacroService()

    @Test
    fun `getDefaultSteps returns 4 chainable actions`() {
        val steps = service.getDefaultSteps()
        assertEquals(4, steps.size)
        assertEquals("copyright", steps[0].id)
        assertEquals("jira-comment", steps[1].id)
        assertEquals("jira-transition", steps[2].id)
        assertEquals("time-log", steps[3].id)
    }

    @Test
    fun `all default steps start as PENDING`() {
        val steps = service.getDefaultSteps()
        assertTrue(steps.all { it.status == MacroStepStatus.PENDING })
    }

    @Test
    fun `filterEnabledSteps removes disabled steps`() {
        val steps = listOf(
            MacroStep("a", "Step A", enabled = true),
            MacroStep("b", "Step B", enabled = false),
            MacroStep("c", "Step C", enabled = true)
        )
        val filtered = service.filterEnabledSteps(steps)
        assertEquals(2, filtered.size)
        assertEquals("a", filtered[0].id)
        assertEquals("c", filtered[1].id)
    }

    @Test
    fun `markStepStatus updates specific step`() {
        val steps = listOf(
            MacroStep("a", "Step A"),
            MacroStep("b", "Step B")
        )
        val updated = service.markStepStatus(steps, "a", MacroStepStatus.SUCCESS)
        assertEquals(MacroStepStatus.SUCCESS, updated[0].status)
        assertEquals(MacroStepStatus.PENDING, updated[1].status)
    }

    @Test
    fun `markStepStatus returns original list if id not found`() {
        val steps = listOf(MacroStep("a", "Step A"))
        val updated = service.markStepStatus(steps, "nonexistent", MacroStepStatus.FAILED)
        assertEquals(steps, updated)
    }

    @Test
    fun `executeMacro runs enabled steps and returns results`() = kotlinx.coroutines.test.runTest {
        val steps = listOf(
            MacroStep("a", "Step A", enabled = true),
            MacroStep("b", "Step B", enabled = true)
        )
        val actions = mapOf<String, suspend () -> Boolean>(
            "a" to { true },
            "b" to { true }
        )

        val results = service.executeMacro(steps, actions)

        assertEquals(2, results.size)
        assertEquals(MacroStepStatus.SUCCESS, results[0].status)
        assertEquals(MacroStepStatus.SUCCESS, results[1].status)
    }

    @Test
    fun `executeMacro stops on failure and skips remaining`() = kotlinx.coroutines.test.runTest {
        val steps = listOf(
            MacroStep("a", "Step A", enabled = true),
            MacroStep("b", "Step B", enabled = true),
            MacroStep("c", "Step C", enabled = true)
        )
        val actions = mapOf<String, suspend () -> Boolean>(
            "a" to { true },
            "b" to { false },
            "c" to { true }
        )

        val results = service.executeMacro(steps, actions)

        assertEquals(MacroStepStatus.SUCCESS, results[0].status)
        assertEquals(MacroStepStatus.FAILED, results[1].status)
        assertEquals(MacroStepStatus.SKIPPED, results[2].status)
    }

    @Test
    fun `executeMacro skips disabled steps`() = kotlinx.coroutines.test.runTest {
        val steps = listOf(
            MacroStep("a", "Step A", enabled = true),
            MacroStep("b", "Step B", enabled = false),
            MacroStep("c", "Step C", enabled = true)
        )
        val actions = mapOf<String, suspend () -> Boolean>(
            "a" to { true },
            "c" to { true }
        )

        val results = service.executeMacro(steps, actions)

        assertEquals(MacroStepStatus.SUCCESS, results[0].status)
        assertEquals(MacroStepStatus.SKIPPED, results[1].status)
        assertEquals(MacroStepStatus.SUCCESS, results[2].status)
    }

    @Test
    fun `markRemainingSkipped skips all PENDING steps`() {
        val steps = listOf(
            MacroStep("a", "Step A", status = MacroStepStatus.SUCCESS),
            MacroStep("b", "Step B", status = MacroStepStatus.FAILED),
            MacroStep("c", "Step C", status = MacroStepStatus.PENDING),
            MacroStep("d", "Step D", status = MacroStepStatus.PENDING)
        )
        val updated = service.markRemainingSkipped(steps)
        assertEquals(MacroStepStatus.SUCCESS, updated[0].status)
        assertEquals(MacroStepStatus.FAILED, updated[1].status)
        assertEquals(MacroStepStatus.SKIPPED, updated[2].status)
        assertEquals(MacroStepStatus.SKIPPED, updated[3].status)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :handover:test --tests "*.CompletionMacroServiceTest" -v`
Expected: Compilation fails.

- [ ] **Step 3: Implement CompletionMacroService**

```kotlin
package com.workflow.orchestrator.handover.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.handover.model.MacroStep
import com.workflow.orchestrator.handover.model.MacroStepStatus

@Service(Service.Level.PROJECT)
class CompletionMacroService {

    private var project: Project? = null

    constructor(project: Project) {
        this.project = project
    }

    constructor()

    fun getDefaultSteps(): List<MacroStep> = listOf(
        MacroStep(id = "copyright", label = "Fix Copyright Headers"),
        MacroStep(id = "jira-comment", label = "Post Jira Comment"),
        MacroStep(id = "jira-transition", label = "Transition to In Review"),
        MacroStep(id = "time-log", label = "Log Work")
    )

    fun filterEnabledSteps(steps: List<MacroStep>): List<MacroStep> {
        return steps.filter { it.enabled }
    }

    fun markStepStatus(steps: List<MacroStep>, stepId: String, status: MacroStepStatus): List<MacroStep> {
        return steps.map { step ->
            if (step.id == stepId) step.copy(status = status) else step
        }
    }

    fun markRemainingSkipped(steps: List<MacroStep>): List<MacroStep> {
        return steps.map { step ->
            if (step.status == MacroStepStatus.PENDING) step.copy(status = MacroStepStatus.SKIPPED)
            else step
        }
    }

    /**
     * Execute macro steps sequentially. Each action returns true (success) or false (failure).
     * On failure, remaining steps are marked SKIPPED. Disabled steps are also SKIPPED.
     */
    suspend fun executeMacro(
        steps: List<MacroStep>,
        actions: Map<String, suspend () -> Boolean>
    ): List<MacroStep> {
        val results = steps.toMutableList()
        var failed = false

        for (i in results.indices) {
            val step = results[i]
            if (!step.enabled || failed) {
                results[i] = step.copy(status = MacroStepStatus.SKIPPED)
                continue
            }

            results[i] = step.copy(status = MacroStepStatus.RUNNING)
            val action = actions[step.id]
            val success = try {
                action?.invoke() ?: false
            } catch (_: Exception) {
                false
            }

            results[i] = step.copy(
                status = if (success) MacroStepStatus.SUCCESS else MacroStepStatus.FAILED
            )

            if (!success) {
                failed = true
            }
        }

        return results
    }

    companion object {
        fun getInstance(project: Project): CompletionMacroService {
            return project.getService(CompletionMacroService::class.java)
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :handover:test --tests "*.CompletionMacroServiceTest" -v`
Expected: All 9 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add handover/src/
git commit -m "feat(handover): add CompletionMacroService for chainable post-automation actions"
```

---

**End of Chunk 4** — Feature services (TimeTrackingService, PrService, PreReviewService, CompletionMacroService) are complete.

---

## Chunk 5: UI Components

### Task 16: Implement HandoverTabProvider

**Files:**
- Create: `handover/src/main/kotlin/com/workflow/orchestrator/handover/ui/HandoverTabProvider.kt`

- [ ] **Step 1: Create HandoverTabProvider**

```kotlin
package com.workflow.orchestrator.handover.ui

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.toolwindow.EmptyStatePanel
import com.workflow.orchestrator.core.toolwindow.WorkflowTabProvider
import javax.swing.JComponent

class HandoverTabProvider : WorkflowTabProvider {

    override val tabId: String = "handover"
    override val tabTitle: String = "Handover"
    override val order: Int = 4

    override fun createPanel(project: Project): JComponent {
        val settings = PluginSettings.getInstance(project)
        val hasJira = !settings.state.jiraUrl.isNullOrBlank()
        val hasBitbucket = !settings.state.bitbucketUrl.isNullOrBlank()

        return if (hasJira || hasBitbucket) {
            HandoverPanel(project)
        } else {
            EmptyStatePanel(
                project,
                "No handover services configured.\nConnect Jira and Bitbucket in Settings to get started."
            )
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :handover:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add handover/src/main/kotlin/com/workflow/orchestrator/handover/ui/HandoverTabProvider.kt
git commit -m "feat(handover): add HandoverTabProvider as 5th Workflow tab"
```

---

### Task 17: Implement HandoverToolbar

**Files:**
- Create: `handover/src/main/kotlin/com/workflow/orchestrator/handover/ui/HandoverToolbar.kt`

- [ ] **Step 1: Create HandoverToolbar**

```kotlin
package com.workflow.orchestrator.handover.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import javax.swing.Icon
import javax.swing.JComponent

typealias PanelSwitcher = (String) -> Unit

class HandoverToolbar(private val panelSwitcher: PanelSwitcher) {

    companion object {
        const val PANEL_COPYRIGHT = "copyright"
        const val PANEL_CODY = "cody"
        const val PANEL_PR = "pr"
        const val PANEL_JIRA = "jira"
        const val PANEL_TIME = "time"
        const val PANEL_QA = "qa"
        const val PANEL_MACRO = "macro"
    }

    fun createToolbar(): JComponent {
        val group = DefaultActionGroup().apply {
            add(toolbarAction("Copyright", AllIcons.Nodes.CopyOfFolder, PANEL_COPYRIGHT))
            add(toolbarAction("Cody Review", AllIcons.Actions.Preview, PANEL_CODY))
            add(toolbarAction("Pull Request", AllIcons.Vcs.Branch, PANEL_PR))
            add(toolbarAction("Jira Comment", AllIcons.Toolwindows.ToolWindowMessages, PANEL_JIRA))
            add(toolbarAction("Time Log", AllIcons.Actions.Profile, PANEL_TIME))
            add(toolbarAction("QA Clipboard", AllIcons.Actions.Copy, PANEL_QA))
            add(toolbarAction("Macro", AllIcons.Actions.Lightning, PANEL_MACRO))
        }

        val toolbar = ActionManager.getInstance()
            .createActionToolbar("HandoverToolbar", group, true)
        toolbar.targetComponent = toolbar.component
        return toolbar.component
    }

    private fun toolbarAction(text: String, icon: Icon, panelId: String): AnAction {
        return object : AnAction(text, "Show $text panel", icon) {
            override fun actionPerformed(e: AnActionEvent) {
                panelSwitcher(panelId)
            }
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :handover:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add handover/src/main/kotlin/com/workflow/orchestrator/handover/ui/HandoverToolbar.kt
git commit -m "feat(handover): add HandoverToolbar with 7 action buttons"
```

---

### Task 18: Implement HandoverContextPanel

**Files:**
- Create: `handover/src/main/kotlin/com/workflow/orchestrator/handover/ui/HandoverContextPanel.kt`

- [ ] **Step 1: Create HandoverContextPanel**

```kotlin
package com.workflow.orchestrator.handover.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.handover.model.HandoverState
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.SwingConstants

class HandoverContextPanel : JPanel(BorderLayout()) {

    private val contentPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(8)
    }

    // Section labels — updated reactively
    private val ticketIdLabel = JBLabel("")
    private val ticketSummaryLabel = JBLabel("")
    private val ticketStatusLabel = JBLabel("")
    private val transitionComboBox = com.intellij.openapi.ui.ComboBox<String>()
    private val transitionButton = javax.swing.JButton("Transition")
    private val prStatusLabel = JBLabel("")
    private val buildStatusLabel = JBLabel("")
    private val qualityLabel = JBLabel("")
    private val dockerTagLabel = JBLabel("")
    private val suiteSectionPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    private val actionsSectionPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }

    init {
        buildLayout()
    }

    private fun buildLayout() {
        contentPanel.add(sectionHeader("Current Ticket"))
        contentPanel.add(ticketIdLabel)
        contentPanel.add(ticketSummaryLabel)
        contentPanel.add(ticketStatusLabel)
        val transitionRow = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0)).apply {
            add(transitionComboBox)
            add(transitionButton)
        }
        contentPanel.add(transitionRow)
        contentPanel.add(separator())

        contentPanel.add(sectionHeader("Pull Request"))
        contentPanel.add(prStatusLabel)
        contentPanel.add(separator())

        contentPanel.add(sectionHeader("Bamboo Builds"))
        contentPanel.add(buildStatusLabel)
        contentPanel.add(separator())

        contentPanel.add(sectionHeader("Quality Gate"))
        contentPanel.add(qualityLabel)
        contentPanel.add(separator())

        contentPanel.add(sectionHeader("Docker Tag"))
        contentPanel.add(dockerTagLabel)
        contentPanel.add(separator())

        contentPanel.add(sectionHeader("Automation Suites"))
        contentPanel.add(suiteSectionPanel)
        contentPanel.add(separator())

        contentPanel.add(sectionHeader("Actions Done"))
        contentPanel.add(actionsSectionPanel)

        val scrollPane = JBScrollPane(contentPanel)
        scrollPane.border = JBUI.Borders.empty()
        add(scrollPane, BorderLayout.CENTER)
    }

    fun updateState(state: HandoverState) {
        ticketIdLabel.text = state.ticketId.ifEmpty { "No active ticket" }
        ticketSummaryLabel.text = state.ticketSummary
        ticketStatusLabel.text = if (state.jiraTransitioned) "Status: In Review" else "Status: In Progress"

        prStatusLabel.text = if (state.prCreated) {
            "PR created"
        } else {
            "No PR yet"
        }
        prStatusLabel.icon = if (state.prCreated) AllIcons.General.InspectionsOK else null

        buildStatusLabel.text = state.buildStatus?.let { build ->
            "${build.planKey} #${build.buildNumber} — ${build.status.name}"
        } ?: "No build data"
        buildStatusLabel.icon = when (state.buildStatus?.status) {
            WorkflowEvent.BuildEventStatus.SUCCESS -> AllIcons.General.InspectionsOK
            WorkflowEvent.BuildEventStatus.FAILED -> AllIcons.General.Error
            null -> null
        }

        qualityLabel.text = when (state.qualityGatePassed) {
            true -> "Quality gate: PASSED"
            false -> "Quality gate: FAILED"
            null -> "Quality gate: Unknown"
        }
        qualityLabel.icon = when (state.qualityGatePassed) {
            true -> AllIcons.General.InspectionsOK
            false -> AllIcons.General.Error
            null -> null
        }

        // Docker tags — show first tag from latest suite
        val latestSuite = state.suiteResults.lastOrNull()
        dockerTagLabel.text = latestSuite?.dockerTagsJson?.take(50) ?: "No docker tags"

        // Automation suites
        suiteSectionPanel.removeAll()
        if (state.suiteResults.isEmpty()) {
            suiteSectionPanel.add(JBLabel("No suites run"))
        } else {
            for (suite in state.suiteResults) {
                val icon = when (suite.passed) {
                    true -> AllIcons.General.InspectionsOK
                    false -> AllIcons.General.Error
                    null -> AllIcons.Process.Step_1
                }
                val statusText = when (suite.passed) {
                    true -> "PASS"
                    false -> "FAIL"
                    null -> "running"
                }
                suiteSectionPanel.add(JBLabel("${suite.suitePlanKey}: $statusText", icon, SwingConstants.LEFT))
            }
        }

        // Actions done
        actionsSectionPanel.removeAll()
        actionsSectionPanel.add(actionLabel("Copyright fixed", state.copyrightFixed))
        actionsSectionPanel.add(actionLabel("PR created", state.prCreated))
        actionsSectionPanel.add(actionLabel("Jira comment", state.jiraCommentPosted))
        actionsSectionPanel.add(actionLabel("Jira transitioned", state.jiraTransitioned))
        actionsSectionPanel.add(actionLabel("Time logged", state.todayWorkLogged))

        revalidate()
        repaint()
    }

    fun setTransitions(transitions: List<String>) {
        transitionComboBox.removeAllItems()
        transitions.forEach { transitionComboBox.addItem(it) }
    }

    fun getSelectedTransition(): String? {
        return transitionComboBox.selectedItem as? String
    }

    private fun actionLabel(text: String, done: Boolean): JBLabel {
        val icon = if (done) AllIcons.General.InspectionsOK else AllIcons.RunConfigurations.TestNotRan
        val prefix = if (done) "✓" else "○"
        return JBLabel("$prefix $text", icon, SwingConstants.LEFT)
    }

    private fun sectionHeader(text: String): JBLabel {
        return JBLabel(text).apply {
            font = font.deriveFont(java.awt.Font.BOLD)
            border = JBUI.Borders.emptyTop(4)
        }
    }

    private fun separator(): JSeparator {
        return JSeparator().apply {
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, 1)
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :handover:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add handover/src/main/kotlin/com/workflow/orchestrator/handover/ui/HandoverContextPanel.kt
git commit -m "feat(handover): add HandoverContextPanel with live cross-phase data display"
```

---

### Task 19: Implement Detail Panels (7 panels)

**Files:**
- Create: `handover/src/main/kotlin/com/workflow/orchestrator/handover/ui/panels/CopyrightPanel.kt`
- Create: `handover/src/main/kotlin/com/workflow/orchestrator/handover/ui/panels/PreReviewPanel.kt`
- Create: `handover/src/main/kotlin/com/workflow/orchestrator/handover/ui/panels/PrCreationPanel.kt`
- Create: `handover/src/main/kotlin/com/workflow/orchestrator/handover/ui/panels/JiraCommentPanel.kt`
- Create: `handover/src/main/kotlin/com/workflow/orchestrator/handover/ui/panels/TimeLogPanel.kt`
- Create: `handover/src/main/kotlin/com/workflow/orchestrator/handover/ui/panels/QaClipboardPanel.kt`
- Create: `handover/src/main/kotlin/com/workflow/orchestrator/handover/ui/panels/CompletionMacroPanel.kt`

> **Note:** Each panel is a `JPanel` that composes its service. Full interactivity (button click handlers wiring to suspend functions) uses `runBackgroundableTask`. These panels are verified via `runIde`, not unit tests.

- [ ] **Step 1: Create CopyrightPanel**

```kotlin
package com.workflow.orchestrator.handover.ui.panels

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.handover.model.CopyrightFileEntry
import com.workflow.orchestrator.handover.model.CopyrightStatus
import java.awt.BorderLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JPanel

class CopyrightPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val listModel = DefaultListModel<CopyrightFileEntry>()
    private val fileList = JBList(listModel)
    private val fixAllButton = JButton("Fix All")
    private val rescanButton = JButton("Rescan")

    init {
        border = JBUI.Borders.empty(8)

        val header = JBLabel("Copyright Header Status").apply {
            font = font.deriveFont(java.awt.Font.BOLD, 14f)
        }

        val buttonPanel = JPanel().apply {
            add(fixAllButton)
            add(rescanButton)
        }

        add(header, BorderLayout.NORTH)
        add(JBScrollPane(fileList), BorderLayout.CENTER)
        add(buttonPanel, BorderLayout.SOUTH)
    }

    fun setEntries(entries: List<CopyrightFileEntry>) {
        listModel.clear()
        entries.forEach { listModel.addElement(it) }
    }
}
```

- [ ] **Step 2: Create PreReviewPanel**

```kotlin
package com.workflow.orchestrator.handover.ui.panels

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.handover.model.ReviewFinding
import java.awt.BorderLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JPanel

class PreReviewPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val listModel = DefaultListModel<ReviewFinding>()
    private val findingsList = JBList(listModel)
    private val analyzeButton = JButton("Analyze with Cody")
    private val statusLabel = JBLabel("Click Analyze to run Cody pre-review")

    init {
        border = JBUI.Borders.empty(8)

        val header = JPanel(BorderLayout()).apply {
            add(JBLabel("Cody Pre-Review").apply {
                font = font.deriveFont(java.awt.Font.BOLD, 14f)
            }, BorderLayout.WEST)
            add(analyzeButton, BorderLayout.EAST)
        }

        add(header, BorderLayout.NORTH)
        add(JBScrollPane(findingsList), BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)
    }

    fun setFindings(findings: List<ReviewFinding>) {
        listModel.clear()
        findings.forEach { listModel.addElement(it) }
        statusLabel.text = "${findings.size} finding(s)"
    }
}
```

- [ ] **Step 3: Create PrCreationPanel**

```kotlin
package com.workflow.orchestrator.handover.ui.panels

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JButton
import javax.swing.JPanel

class PrCreationPanel(private val project: Project) : JPanel(BorderLayout()) {

    val titleField = JBTextField()
    val descriptionArea = JBTextArea(8, 40)
    val sourceBranchLabel = JBLabel("")
    val targetBranchLabel = JBLabel("")
    val createButton = JButton("Create PR")
    val regenerateButton = JButton("Regenerate Description")
    val resultLabel = JBLabel("")

    init {
        border = JBUI.Borders.empty(8)

        val formPanel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(4)
        }

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0
        formPanel.add(JBLabel("Title:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        formPanel.add(titleField, gbc)

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0
        formPanel.add(JBLabel("Source:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        formPanel.add(sourceBranchLabel, gbc)

        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.0
        formPanel.add(JBLabel("Target:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        formPanel.add(targetBranchLabel, gbc)

        val buttonPanel = JPanel().apply {
            add(createButton)
            add(regenerateButton)
        }

        add(formPanel, BorderLayout.NORTH)
        add(JBScrollPane(descriptionArea), BorderLayout.CENTER)

        val southPanel = JPanel(BorderLayout()).apply {
            add(buttonPanel, BorderLayout.WEST)
            add(resultLabel, BorderLayout.EAST)
        }
        add(southPanel, BorderLayout.SOUTH)
    }
}
```

- [ ] **Step 4: Create JiraCommentPanel**

```kotlin
package com.workflow.orchestrator.handover.ui.panels

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JPanel

class JiraCommentPanel(private val project: Project) : JPanel(BorderLayout()) {

    val commentPreview = JBTextArea(12, 40).apply {
        isEditable = false
        font = java.awt.Font("Monospaced", java.awt.Font.PLAIN, 12)
    }
    val editButton = JButton("Edit")
    val postButton = JButton("Post Comment")
    val statusLabel = JBLabel("")

    init {
        border = JBUI.Borders.empty(8)

        val header = JBLabel("Jira Closure Comment").apply {
            font = font.deriveFont(java.awt.Font.BOLD, 14f)
        }

        val buttonPanel = JPanel().apply {
            add(editButton)
            add(postButton)
        }

        val southPanel = JPanel(BorderLayout()).apply {
            add(buttonPanel, BorderLayout.WEST)
            add(statusLabel, BorderLayout.EAST)
        }

        add(header, BorderLayout.NORTH)
        add(JBScrollPane(commentPreview), BorderLayout.CENTER)
        add(southPanel, BorderLayout.SOUTH)
    }

    fun setCommentText(text: String) {
        commentPreview.text = text
    }
}
```

- [ ] **Step 5: Create TimeLogPanel**

```kotlin
package com.workflow.orchestrator.handover.ui.panels

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JButton
import javax.swing.JPanel

class TimeLogPanel(private val project: Project) : JPanel(BorderLayout()) {

    val ticketLabel = JBLabel("")
    val dateField = JBTextField(10)
    val hoursField = JBTextField("1.0", 5)
    private val decrementButton = JButton("-").apply {
        addActionListener {
            val current = hoursField.text.toDoubleOrNull() ?: 1.0
            hoursField.text = (current - 0.5).coerceAtLeast(0.5).toString()
        }
    }
    private val incrementButton = JButton("+").apply {
        addActionListener {
            val current = hoursField.text.toDoubleOrNull() ?: 1.0
            hoursField.text = (current + 0.5).coerceAtMost(7.0).toString()
        }
    }
    val commentField = JBTextField()
    val elapsedHintLabel = JBLabel("")
    val logButton = JButton("Log Work")
    val statusLabel = JBLabel("")

    init {
        border = JBUI.Borders.empty(8)

        val header = JBLabel("Time Tracking").apply {
            font = font.deriveFont(java.awt.Font.BOLD, 14f)
        }

        val formPanel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(4)
        }

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0
        formPanel.add(JBLabel("Ticket:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        formPanel.add(ticketLabel, gbc)

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0
        formPanel.add(JBLabel("Date:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        formPanel.add(dateField, gbc)

        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.0
        formPanel.add(JBLabel("Hours (max 7):"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        val hoursStepper = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 2, 0)).apply {
            add(decrementButton)
            add(hoursField)
            add(incrementButton)
        }
        formPanel.add(hoursStepper, gbc)

        gbc.gridx = 0; gbc.gridy = 3; gbc.weightx = 0.0
        formPanel.add(JBLabel("Comment:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        formPanel.add(commentField, gbc)

        gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 2; gbc.weightx = 1.0
        formPanel.add(elapsedHintLabel, gbc)

        val southPanel = JPanel(BorderLayout()).apply {
            add(logButton, BorderLayout.WEST)
            add(statusLabel, BorderLayout.EAST)
        }

        add(header, BorderLayout.NORTH)
        add(formPanel, BorderLayout.CENTER)
        add(southPanel, BorderLayout.SOUTH)
    }
}
```

- [ ] **Step 6: Create QaClipboardPanel**

```kotlin
package com.workflow.orchestrator.handover.ui.panels

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JPanel

class QaClipboardPanel(private val project: Project) : JPanel(BorderLayout()) {

    val textArea = JBTextArea(8, 40).apply {
        isEditable = false
        font = java.awt.Font("Monospaced", java.awt.Font.PLAIN, 12)
    }
    val copyAllButton = JButton("Copy All")
    val addServiceButton = JButton("Add Service")
    val statusLabel = JBLabel("")
    private val tagListPanel = JPanel().apply {
        layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
    }

    init {
        border = JBUI.Borders.empty(8)

        val header = JPanel(BorderLayout()).apply {
            add(JBLabel("QA Handover").apply {
                font = font.deriveFont(java.awt.Font.BOLD, 14f)
            }, BorderLayout.WEST)
            add(addServiceButton, BorderLayout.EAST)
        }

        val buttonPanel = JPanel().apply {
            add(copyAllButton)
        }

        val southPanel = JPanel(BorderLayout()).apply {
            add(buttonPanel, BorderLayout.WEST)
            add(statusLabel, BorderLayout.EAST)
        }

        val centerPanel = JPanel(BorderLayout()).apply {
            add(JBScrollPane(tagListPanel), BorderLayout.NORTH)
            add(JBScrollPane(textArea), BorderLayout.CENTER)
        }

        add(header, BorderLayout.NORTH)
        add(centerPanel, BorderLayout.CENTER)
        add(southPanel, BorderLayout.SOUTH)
    }

    fun setDockerTags(tags: Map<String, String>) {
        tagListPanel.removeAll()
        for ((service, tag) in tags) {
            val row = JPanel(BorderLayout()).apply {
                add(JBLabel("  $service: $tag"), BorderLayout.CENTER)
                val copyBtn = JButton("Copy")
                copyBtn.addActionListener {
                    val content = java.awt.datatransfer.StringSelection("$service:$tag")
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(content, null)
                }
                add(copyBtn, BorderLayout.EAST)
            }
            tagListPanel.add(row)
        }
        tagListPanel.revalidate()
        tagListPanel.repaint()
    }

    fun setFormattedText(text: String) {
        textArea.text = text
    }
}
```

- [ ] **Step 7: Create CompletionMacroPanel**

```kotlin
package com.workflow.orchestrator.handover.ui.panels

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.handover.model.MacroStep
import com.workflow.orchestrator.handover.model.MacroStepStatus
import java.awt.BorderLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JPanel
import javax.swing.SwingConstants

class CompletionMacroPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val stepsPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    val runButton = JButton("Run Macro")
    val statusLabel = JBLabel("")
    private val checkboxes = mutableMapOf<String, JCheckBox>()

    init {
        border = JBUI.Borders.empty(8)

        val header = JBLabel("Complete Task Macro").apply {
            font = font.deriveFont(java.awt.Font.BOLD, 14f)
        }

        val southPanel = JPanel(BorderLayout()).apply {
            add(runButton, BorderLayout.WEST)
            add(statusLabel, BorderLayout.EAST)
        }

        add(header, BorderLayout.NORTH)
        add(stepsPanel, BorderLayout.CENTER)
        add(southPanel, BorderLayout.SOUTH)
    }

    fun setSteps(steps: List<MacroStep>) {
        stepsPanel.removeAll()
        checkboxes.clear()

        for (step in steps) {
            val checkbox = JCheckBox(step.label, step.enabled)
            checkboxes[step.id] = checkbox

            val icon = when (step.status) {
                MacroStepStatus.PENDING -> null
                MacroStepStatus.RUNNING -> AllIcons.Process.Step_1
                MacroStepStatus.SUCCESS -> AllIcons.General.InspectionsOK
                MacroStepStatus.FAILED -> AllIcons.General.Error
                MacroStepStatus.SKIPPED -> AllIcons.RunConfigurations.TestNotRan
            }

            val row = JPanel(BorderLayout()).apply {
                add(checkbox, BorderLayout.WEST)
                if (icon != null) {
                    add(JBLabel(icon), BorderLayout.EAST)
                }
            }
            stepsPanel.add(row)
        }

        stepsPanel.revalidate()
        stepsPanel.repaint()
    }

    fun getEnabledStepIds(): List<String> {
        return checkboxes.filter { it.value.isSelected }.keys.toList()
    }
}
```

- [ ] **Step 8: Verify all panels compile**

Run: `./gradlew :handover:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: Commit**

```bash
git add handover/src/main/kotlin/com/workflow/orchestrator/handover/ui/panels/
git commit -m "feat(handover): add 7 detail panels (copyright, cody, PR, jira, time, QA, macro)"
```

---

### Task 20: Implement HandoverPanel (Main Orchestrator)

**Files:**
- Create: `handover/src/main/kotlin/com/workflow/orchestrator/handover/ui/HandoverPanel.kt`

- [ ] **Step 1: Create HandoverPanel**

```kotlin
package com.workflow.orchestrator.handover.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.JBSplitter
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.handover.service.HandoverStateService
import com.workflow.orchestrator.handover.ui.panels.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.CardLayout
import javax.swing.JPanel

class HandoverPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val stateService = HandoverStateService.getInstance(project)

    // UI components
    private val contextPanel = HandoverContextPanel()
    private val cardLayout = CardLayout()
    private val detailContainer = JPanel(cardLayout)
    private val toolbar: HandoverToolbar

    // Detail panels
    private val copyrightPanel = CopyrightPanel(project)
    private val preReviewPanel = PreReviewPanel(project)
    private val prCreationPanel = PrCreationPanel(project)
    private val jiraCommentPanel = JiraCommentPanel(project)
    private val timeLogPanel = TimeLogPanel(project)
    private val qaClipboardPanel = QaClipboardPanel(project)
    private val completionMacroPanel = CompletionMacroPanel(project)

    init {
        toolbar = HandoverToolbar { panelId -> switchPanel(panelId) }

        // Register detail panels in card layout
        detailContainer.add(copyrightPanel, HandoverToolbar.PANEL_COPYRIGHT)
        detailContainer.add(preReviewPanel, HandoverToolbar.PANEL_CODY)
        detailContainer.add(prCreationPanel, HandoverToolbar.PANEL_PR)
        detailContainer.add(jiraCommentPanel, HandoverToolbar.PANEL_JIRA)
        detailContainer.add(timeLogPanel, HandoverToolbar.PANEL_TIME)
        detailContainer.add(qaClipboardPanel, HandoverToolbar.PANEL_QA)
        detailContainer.add(completionMacroPanel, HandoverToolbar.PANEL_MACRO)

        // Splitter: left context (30%) + right detail (70%)
        val splitter = JBSplitter(false, 0.30f).apply {
            firstComponent = contextPanel
            secondComponent = detailContainer
        }

        add(toolbar.createToolbar(), BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)

        // Bind to state flow
        scope.launch {
            stateService.stateFlow.collect { state ->
                contextPanel.updateState(state)
            }
        }

        // Show copyright panel by default (first in workflow)
        switchPanel(HandoverToolbar.PANEL_COPYRIGHT)
    }

    private fun switchPanel(panelId: String) {
        cardLayout.show(detailContainer, panelId)
    }

    override fun dispose() {
        scope.cancel()
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :handover:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add handover/src/main/kotlin/com/workflow/orchestrator/handover/ui/HandoverPanel.kt
git commit -m "feat(handover): add HandoverPanel with toolbar, context sidebar, and card-based detail panels"
```

---

**End of Chunk 5** — All UI components (tab provider, toolbar, context panel, 7 detail panels, main orchestrator panel) are complete.

---

## Chunk 6: Plugin Wiring & Gate 8 Verification

### Task 21: Register Handover Services and Tab in plugin.xml

**Files:**
- Modify: `src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Add service registrations**

Add the following inside the `<extensions defaultExtensionNs="com.intellij">` block, after existing Phase 2A entries:

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
```

- [ ] **Step 2: Add tab provider**

Add inside the `<extensions defaultExtensionNs="com.workflow.orchestrator">` block:

```xml
    <tabProvider implementation="com.workflow.orchestrator.handover.ui.HandoverTabProvider"/>
```

- [ ] **Step 3: Add notification group**

Add alongside existing notification groups:

```xml
    <notificationGroup id="workflow.handover" displayType="BALLOON"/>
```

- [ ] **Step 4: Verify compilation**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/META-INF/plugin.xml
git commit -m "feat(handover): register services, tab provider, and notification group in plugin.xml"
```

---

### Task 22: Run Full Test Suite

- [ ] **Step 1: Run handover tests**

Run: `./gradlew :handover:test -v`
Expected: All tests PASS (~60 tests across 8 test classes).

- [ ] **Step 2: Run core tests (regression check)**

Run: `./gradlew :core:test`
Expected: All existing tests PASS (no regressions from WorkflowEvent/PluginSettings changes).

- [ ] **Step 3: Commit test results (if any fixtures needed adjustment)**

```bash
# Only if changes were needed
git add -A && git commit -m "fix(handover): adjust tests after integration"
```

---

### Task 23: Verify Plugin Compatibility

- [ ] **Step 1: Run verifyPlugin**

Run: `./gradlew verifyPlugin`
Expected: No API compatibility issues.

- [ ] **Step 2: Build distributable ZIP**

Run: `./gradlew buildPlugin`
Expected: BUILD SUCCESSFUL, ZIP at `build/distributions/`.

- [ ] **Step 3: Run IDE and verify manually**

Run: `./gradlew runIde`

Manual verification checklist:
1. "Workflow" tool window opens with 5 tabs (Sprint, Build, Quality, Automation, **Handover**)
2. Handover tab shows toolbar with 7 buttons
3. Left context panel shows "No active ticket" initially
4. Each toolbar button switches the right detail panel
5. Copyright panel shows "Rescan" and "Fix All" buttons
6. Time log panel has hours spinner (0.5 to 7.0)
7. QA clipboard panel has "Copy All" button
8. Macro panel shows 4 checkboxes
9. No errors in IDE log (`Help > Show Log in Finder`)

- [ ] **Step 4: Commit any manual-verification fixes**

```bash
# Only if UI adjustments were needed
git add -A && git commit -m "fix(handover): UI adjustments from manual runIde verification"
```

---

### Task 24: Final Integration Commit

- [ ] **Step 1: Run full verification**

```bash
./gradlew clean :handover:test :core:test verifyPlugin buildPlugin
```

Expected: All pass, ZIP produced.

- [ ] **Step 2: Create integration commit**

```bash
git add -A
git commit -m "feat(handover): complete Phase 2B Handover Engine module

Implements Gate 8 (End-to-end lifecycle):
- Copyright auto-fix with year-range consolidation
- Cody pre-review for Spring Boot anti-patterns
- One-click Bitbucket PR creation
- Jira closure comment (docker tags + automation suite links)
- Jira status transition (In Progress → In Review)
- Time tracking (daily worklog, max 7h)
- QA clipboard (formatted docker tags + suite links)
- Complete Task macro (chain post-automation actions)
- Handover tab with toolbar + context sidebar + detail panels
- HandoverStateService central event accumulator
- BitbucketApiClient and HandoverJiraClient
- Full TDD coverage (~60 tests)"
```

---

**End of Chunk 6** — Plugin wiring, testing, verification, and final commit complete.

---

## Gate 8 Verification Checklist

After all tasks are complete, verify:

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
