# Phase 2A: Automation Orchestrator — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an in-IDE automation suite orchestrator that replaces the manual Bamboo "Run Customized" workflow — smart Docker tag payload construction, queue management with auto-trigger, drift/conflict detection, and run history.

**Architecture:** New `:automation` Gradle module depending on `:core` and `:bamboo`. APP-level persistence (`AutomationSettingsService`) for suite configs that survive across all projects/IDEs. PROJECT-level SQLite for queue state and tag history. Plain-class `DockerRegistryClient` (not a service) instantiated by services, matching the `BambooApiClient` pattern in `BuildMonitorService`. Cross-module communication via `EventBus` (`SharedFlow` in `:core`). Queue polling uses `CoroutineScope` with `SupervisorJob` tied to project lifecycle.

**Tech Stack:** Kotlin 2.1.10, IntelliJ Platform SDK 2025.1+, kotlinx.coroutines 1.8, kotlinx.serialization 1.7.3, OkHttp 4.12, SQLite JDBC 3.45, JUnit 5, MockK 1.13, Turbine 1.1, MockWebServer

**Spec:** `docs/superpowers/specs/2026-03-11-phase-2a-automation-orchestrator-design.md`

---

## File Structure

### New Files (`:automation` module)

| File | Responsibility |
|------|---------------|
| `automation/build.gradle.kts` | Module build config — depends on `:core` and `:bamboo` |
| `automation/src/main/kotlin/.../automation/model/AutomationModels.kt` | Data classes: `TagEntry`, `BaselineRun`, `QueueEntry`, `QueueEntryStatus`, `SuiteConfig`, `CurrentRepoContext`, enums |
| `automation/src/main/kotlin/.../automation/model/DockerRegistryDtos.kt` | DTOs for Docker Registry v2 API responses |
| `automation/src/main/kotlin/.../automation/api/DockerRegistryClient.kt` | Docker Registry v2 client — token auth, tag listing, validation, caching |
| `automation/src/main/kotlin/.../automation/service/AutomationSettingsService.kt` | APP-level `PersistentStateComponent` for suite configs |
| `automation/src/main/kotlin/.../automation/service/TagHistoryService.kt` | PROJECT-level SQLite for queue entries and run history |
| `automation/src/main/kotlin/.../automation/service/TagBuilderService.kt` | Baseline scoring, tag replacement, payload construction |
| `automation/src/main/kotlin/.../automation/service/DriftDetectorService.kt` | Tag staleness detection via Docker Registry |
| `automation/src/main/kotlin/.../automation/service/ConflictDetectorService.kt` | Running build overlap detection via Bamboo API |
| `automation/src/main/kotlin/.../automation/service/QueueService.kt` | Local queue, polling, auto-trigger, race condition handling |
| `automation/src/main/kotlin/.../automation/service/QueueRecoveryStartupActivity.kt` | `postStartupActivity` for IDE restart recovery |
| `automation/src/main/kotlin/.../automation/ui/AutomationTabProvider.kt` | `WorkflowTabProvider` implementation |
| `automation/src/main/kotlin/.../automation/ui/AutomationPanel.kt` | Main panel orchestrating sub-panels |
| `automation/src/main/kotlin/.../automation/ui/TagStagingPanel.kt` | JBTable with inline tag editing |
| `automation/src/main/kotlin/.../automation/ui/SuiteConfigPanel.kt` | Variables dropdown + stage checkboxes |
| `automation/src/main/kotlin/.../automation/ui/QueueStatusPanel.kt` | Live status, queue position, actions |
| `automation/src/main/kotlin/.../automation/ui/AutomationStatusBarWidgetFactory.kt` | Status bar widget for queue-at-a-glance |

> **Path prefix:** `...` = `com/workflow/orchestrator` throughout this plan.

### Modified Files

| File | Change |
|------|--------|
| `settings.gradle.kts` | Add `":automation"` to `include()` |
| `build.gradle.kts` | Add `implementation(project(":automation"))` |
| `core/.../core/events/WorkflowEvent.kt` | Add 3 nested data classes: `AutomationTriggered`, `AutomationFinished`, `QueuePositionChanged` |
| `core/.../core/settings/PluginSettings.kt` | Add 7 new fields for Docker Registry and queue settings |
| `bamboo/.../bamboo/api/BambooApiClient.kt` | Add `delete` private method + 4 new public methods |
| `bamboo/.../bamboo/api/dto/BambooDtos.kt` | Add `BambooBuildStatusDto` and variable extraction DTOs |
| `core/src/main/resources/META-INF/plugin.xml` | Register automation services, tab provider, status bar, startup activity, notification group |

### Test Files

| File | Tests |
|------|-------|
| `automation/src/test/.../automation/model/AutomationModelsTest.kt` | Model construction, enum coverage |
| `automation/src/test/.../automation/api/DockerRegistryClientTest.kt` | MockWebServer: auth handshake, tag listing, pagination, errors |
| `automation/src/test/.../automation/service/AutomationSettingsServiceTest.kt` | Persistence, cross-suite isolation |
| `automation/src/test/.../automation/service/TagHistoryServiceTest.kt` | SQLite CRUD, limit enforcement, schema migration |
| `automation/src/test/.../automation/service/TagBuilderServiceTest.kt` | Baseline scoring, tag replacement, payload construction |
| `automation/src/test/.../automation/service/DriftDetectorServiceTest.kt` | Staleness detection, semver comparison |
| `automation/src/test/.../automation/service/ConflictDetectorServiceTest.kt` | Overlap detection, JSON parsing |
| `automation/src/test/.../automation/service/QueueServiceTest.kt` | Enqueue, cancel, state transitions, polling, race conditions |
| `bamboo/src/test/.../bamboo/api/BambooApiClientTest.kt` | Existing file — add tests for new methods |
| `core/src/test/.../core/events/EventBusTest.kt` | Existing file — add tests for new event types |

---

## Chunk 1: Module Scaffold & Core Models

### Task 1: Create `:automation` Module Directory Structure and Build File

**Files:**
- Create: `automation/build.gradle.kts`

- [ ] **Step 1: Create module directories**

```bash
mkdir -p automation/src/main/kotlin/com/workflow/orchestrator/automation/{api,service,model,ui}
mkdir -p automation/src/test/kotlin/com/workflow/orchestrator/automation/{api,service,model}
mkdir -p automation/src/test/resources/fixtures
```

- [ ] **Step 2: Create `automation/build.gradle.kts`**

```kotlin
// automation/build.gradle.kts — Submodule for automation suite orchestration.
// Uses the MODULE variant; depends on :core and :bamboo.

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
        bundledPlugins(providers.gradleProperty("platformBundledPlugins").map {
            it.split(',').map(String::trim).filter(String::isNotEmpty)
        })
    }

    implementation(project(":core"))
    implementation(project(":bamboo"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.sqlite.jdbc)

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

- [ ] **Step 3: Register module in `settings.gradle.kts`**

In `settings.gradle.kts`, add `":automation"` to the `include()` block:

```kotlin
include(
    ":core",
    ":jira",
    ":git-integration",
    ":bamboo",
    ":sonar",
    ":cody",
    ":automation",
)
```

- [ ] **Step 4: Add `:automation` to root `build.gradle.kts` composition**

In `build.gradle.kts`, add to the `dependencies` block alongside existing submodule imports (after `implementation(project(":cody"))`):

```kotlin
implementation(project(":automation"))
```

- [ ] **Step 5: Verify Gradle sync**

Run: `./gradlew :automation:classes -x verifyPluginStructure`
Expected: BUILD SUCCESSFUL (module compiles with no source files)

- [ ] **Step 6: Commit**

```bash
git add automation/build.gradle.kts settings.gradle.kts build.gradle.kts
git commit -m "feat(automation): scaffold :automation module with build config"
```

---

### Task 2: Create Automation Domain Models

**Files:**
- Create: `automation/src/main/kotlin/com/workflow/orchestrator/automation/model/AutomationModels.kt`
- Create: `automation/src/test/kotlin/com/workflow/orchestrator/automation/model/AutomationModelsTest.kt`

- [ ] **Step 1: Write model tests**

Create `automation/src/test/kotlin/com/workflow/orchestrator/automation/model/AutomationModelsTest.kt`:

```kotlin
package com.workflow.orchestrator.automation.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class AutomationModelsTest {

    @Test
    fun `TagEntry tracks source and registry status`() {
        val entry = TagEntry(
            serviceName = "service-auth",
            currentTag = "feature/PROJ-123-a1b2c3d",
            latestReleaseTag = "2.4.0",
            source = TagSource.AUTO_DETECTED,
            registryStatus = RegistryStatus.VALID,
            isDrift = true,
            isCurrentRepo = true
        )
        assertEquals("service-auth", entry.serviceName)
        assertTrue(entry.isDrift)
        assertTrue(entry.isCurrentRepo)
        assertEquals(TagSource.AUTO_DETECTED, entry.source)
    }

    @Test
    fun `BaselineRun score calculation from constructor`() {
        val run = BaselineRun(
            buildNumber = 847,
            resultKey = "PROJ-AUTO-847",
            dockerTags = mapOf("auth" to "2.4.0", "payments" to "2.3.1"),
            releaseTagCount = 14,
            totalServices = 14,
            successfulStages = 3,
            failedStages = 0,
            triggeredAt = Instant.now(),
            score = 155 // (14*10) + (3*5) - (0*20)
        )
        assertEquals(847, run.buildNumber)
        assertEquals(155, run.score)
        assertEquals(2, run.dockerTags.size)
    }

    @Test
    fun `QueueEntry default status is WAITING_LOCAL`() {
        val entry = QueueEntry(
            id = "uuid-1",
            suitePlanKey = "PROJ-AUTO",
            dockerTagsPayload = """{"auth":"2.4.0"}""",
            variables = mapOf("suiteType" to "regression"),
            stages = listOf("QA Automation"),
            enqueuedAt = Instant.now(),
            status = QueueEntryStatus.WAITING_LOCAL,
            bambooResultKey = null
        )
        assertEquals(QueueEntryStatus.WAITING_LOCAL, entry.status)
        assertNull(entry.bambooResultKey)
    }

    @Test
    fun `QueueEntryStatus has all expected states`() {
        val statuses = QueueEntryStatus.entries
        assertTrue(statuses.contains(QueueEntryStatus.WAITING_LOCAL))
        assertTrue(statuses.contains(QueueEntryStatus.TRIGGERING))
        assertTrue(statuses.contains(QueueEntryStatus.QUEUED_ON_BAMBOO))
        assertTrue(statuses.contains(QueueEntryStatus.RUNNING))
        assertTrue(statuses.contains(QueueEntryStatus.COMPLETED))
        assertTrue(statuses.contains(QueueEntryStatus.FAILED_TO_TRIGGER))
        assertTrue(statuses.contains(QueueEntryStatus.TAG_INVALID))
        assertTrue(statuses.contains(QueueEntryStatus.PLAN_UNAVAILABLE))
        assertTrue(statuses.contains(QueueEntryStatus.STALE))
        assertTrue(statuses.contains(QueueEntryStatus.CANCELLED))
        assertEquals(10, statuses.size)
    }

    @Test
    fun `CurrentRepoContext captures detection source`() {
        val ctx = CurrentRepoContext(
            serviceName = "service-auth",
            branchName = "feature/PROJ-123",
            featureBranchTag = "feature/PROJ-123-a1b2c3d",
            detectedFrom = DetectionSource.PROJECT_NAME
        )
        assertEquals(DetectionSource.PROJECT_NAME, ctx.detectedFrom)
        assertEquals("feature/PROJ-123-a1b2c3d", ctx.featureBranchTag)
    }

    @Test
    fun `DriftResult marks stale when versions differ`() {
        val result = DriftResult(
            serviceName = "service-payments",
            currentTag = "2.3.1",
            latestReleaseTag = "2.4.0",
            isStale = true
        )
        assertTrue(result.isStale)
    }

    @Test
    fun `Conflict captures overlap details`() {
        val conflict = Conflict(
            serviceName = "service-auth",
            yourTag = "feature/PROJ-123-abc",
            otherTag = "2.4.0",
            triggeredBy = "dev-jones",
            buildNumber = 848,
            isRunning = true
        )
        assertTrue(conflict.isRunning)
        assertEquals("dev-jones", conflict.triggeredBy)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :automation:test --tests "com.workflow.orchestrator.automation.model.AutomationModelsTest" -x verifyPluginStructure`
Expected: FAIL — unresolved references to all model classes.

- [ ] **Step 3: Create the model file**

Create `automation/src/main/kotlin/com/workflow/orchestrator/automation/model/AutomationModels.kt`:

```kotlin
package com.workflow.orchestrator.automation.model

import java.time.Instant

data class TagEntry(
    val serviceName: String,
    val currentTag: String,
    val latestReleaseTag: String?,
    val source: TagSource,
    val registryStatus: RegistryStatus,
    val isDrift: Boolean,
    val isCurrentRepo: Boolean
)

enum class TagSource { BASELINE, USER_EDIT, AUTO_DETECTED }

enum class RegistryStatus { VALID, NOT_FOUND, CHECKING, UNKNOWN, ERROR }

data class BaselineRun(
    val buildNumber: Int,
    val resultKey: String,
    val dockerTags: Map<String, String>,
    val releaseTagCount: Int,
    val totalServices: Int,
    val successfulStages: Int,
    val failedStages: Int,
    val triggeredAt: Instant,
    val score: Int
)

data class QueueEntry(
    val id: String,
    val suitePlanKey: String,
    val dockerTagsPayload: String,
    val variables: Map<String, String>,
    val stages: List<String>,
    val enqueuedAt: Instant,
    val status: QueueEntryStatus,
    val bambooResultKey: String?,
    val errorMessage: String? = null
)

enum class QueueEntryStatus {
    WAITING_LOCAL,
    TRIGGERING,
    QUEUED_ON_BAMBOO,
    RUNNING,
    COMPLETED,
    FAILED_TO_TRIGGER,
    TAG_INVALID,
    PLAN_UNAVAILABLE,
    STALE,
    CANCELLED
}

data class CurrentRepoContext(
    val serviceName: String,
    val branchName: String,
    val featureBranchTag: String?,
    val detectedFrom: DetectionSource
)

enum class DetectionSource { PROJECT_NAME, SETTINGS_MAPPING, GIT_BRANCH }

data class DriftResult(
    val serviceName: String,
    val currentTag: String,
    val latestReleaseTag: String,
    val isStale: Boolean
)

data class Conflict(
    val serviceName: String,
    val yourTag: String,
    val otherTag: String,
    val triggeredBy: String,
    val buildNumber: Int,
    val isRunning: Boolean
)

data class HistoryEntry(
    val id: String,
    val suitePlanKey: String,
    val dockerTagsJson: String,
    val variables: Map<String, String>,
    val stages: List<String>,
    val triggeredAt: Instant,
    val buildResultKey: String?,
    val buildPassed: Boolean?
)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :automation:test --tests "com.workflow.orchestrator.automation.model.AutomationModelsTest" -x verifyPluginStructure`
Expected: PASS — all 7 tests green.

- [ ] **Step 5: Commit**

```bash
git add automation/src/main/kotlin/com/workflow/orchestrator/automation/model/AutomationModels.kt \
     automation/src/test/kotlin/com/workflow/orchestrator/automation/model/AutomationModelsTest.kt
git commit -m "feat(automation): add domain models — TagEntry, QueueEntry, BaselineRun, enums"
```

---

### Task 3: Create Docker Registry DTOs

**Files:**
- Create: `automation/src/main/kotlin/com/workflow/orchestrator/automation/model/DockerRegistryDtos.kt`

- [ ] **Step 1: Create the DTO file**

Create `automation/src/main/kotlin/com/workflow/orchestrator/automation/model/DockerRegistryDtos.kt`:

```kotlin
package com.workflow.orchestrator.automation.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Response from GET /v2/{name}/tags/list */
@Serializable
data class DockerTagListResponse(
    val name: String = "",
    val tags: List<String>? = null
)

/** Response from token auth endpoint parsed from WWW-Authenticate header */
@Serializable
data class DockerAuthTokenResponse(
    val token: String = "",
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("expires_in") val expiresIn: Int = 300,
    @SerialName("issued_at") val issuedAt: String = ""
) {
    /** Docker Registry may return token in either field */
    fun effectiveToken(): String = token.ifEmpty { accessToken }
}

/** Parsed from WWW-Authenticate header: Bearer realm="...",service="...",scope="..." */
data class DockerAuthChallenge(
    val realm: String,
    val service: String,
    val scope: String
)
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :automation:classes -x verifyPluginStructure`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add automation/src/main/kotlin/com/workflow/orchestrator/automation/model/DockerRegistryDtos.kt
git commit -m "feat(automation): add Docker Registry v2 API DTOs"
```

---

### Task 4: Add Automation Events to WorkflowEvent

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/events/WorkflowEvent.kt`
- Modify: `core/src/test/kotlin/com/workflow/orchestrator/core/events/EventBusTest.kt`

- [ ] **Step 1: Write failing tests for new event types**

Add to `core/src/test/kotlin/com/workflow/orchestrator/core/events/EventBusTest.kt`:

```kotlin
@Test
fun `emit delivers AutomationTriggered event`() = runTest {
    val bus = EventBus()
    val event = WorkflowEvent.AutomationTriggered(
        suitePlanKey = "PROJ-AUTO",
        buildResultKey = "PROJ-AUTO-847",
        dockerTagsJson = """{"auth":"2.4.0"}""",
        triggeredBy = "auto-queue"
    )

    bus.events.test {
        bus.emit(event)
        val received = awaitItem()
        assertTrue(received is WorkflowEvent.AutomationTriggered)
        val triggered = received as WorkflowEvent.AutomationTriggered
        assertEquals("PROJ-AUTO", triggered.suitePlanKey)
        assertEquals("auto-queue", triggered.triggeredBy)
        cancelAndIgnoreRemainingEvents()
    }
}

@Test
fun `emit delivers AutomationFinished event`() = runTest {
    val bus = EventBus()
    val event = WorkflowEvent.AutomationFinished(
        suitePlanKey = "PROJ-AUTO",
        buildResultKey = "PROJ-AUTO-847",
        passed = true,
        durationMs = 720000
    )

    bus.events.test {
        bus.emit(event)
        val received = awaitItem()
        assertTrue(received is WorkflowEvent.AutomationFinished)
        val finished = received as WorkflowEvent.AutomationFinished
        assertTrue(finished.passed)
        assertEquals(720000, finished.durationMs)
        cancelAndIgnoreRemainingEvents()
    }
}

@Test
fun `emit delivers QueuePositionChanged event`() = runTest {
    val bus = EventBus()
    val event = WorkflowEvent.QueuePositionChanged(
        suitePlanKey = "PROJ-AUTO",
        position = 2,
        estimatedWaitMs = 480000
    )

    bus.events.test {
        bus.emit(event)
        val received = awaitItem()
        assertTrue(received is WorkflowEvent.QueuePositionChanged)
        val changed = received as WorkflowEvent.QueuePositionChanged
        assertEquals(2, changed.position)
        assertEquals(480000, changed.estimatedWaitMs)
        cancelAndIgnoreRemainingEvents()
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :core:test --tests "com.workflow.orchestrator.core.events.EventBusTest" -x verifyPluginStructure`
Expected: FAIL — `AutomationTriggered`, `AutomationFinished`, `QueuePositionChanged` are unresolved references.

- [ ] **Step 3: Add events to WorkflowEvent sealed class**

In `core/src/main/kotlin/com/workflow/orchestrator/core/events/WorkflowEvent.kt`, add before the closing `}` of the sealed class (after `HealthCheckFinished`):

```kotlin
    /** Emitted by :automation when a build is triggered (manual or auto-queue). */
    data class AutomationTriggered(
        val suitePlanKey: String,
        val buildResultKey: String,
        val dockerTagsJson: String,
        val triggeredBy: String
    ) : WorkflowEvent()

    /** Emitted by :automation when a triggered build completes. */
    data class AutomationFinished(
        val suitePlanKey: String,
        val buildResultKey: String,
        val passed: Boolean,
        val durationMs: Long
    ) : WorkflowEvent()

    /** Emitted by :automation when queue position changes. */
    data class QueuePositionChanged(
        val suitePlanKey: String,
        val position: Int,
        val estimatedWaitMs: Long?
    ) : WorkflowEvent()
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :core:test --tests "com.workflow.orchestrator.core.events.EventBusTest" -x verifyPluginStructure`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/events/WorkflowEvent.kt \
     core/src/test/kotlin/com/workflow/orchestrator/core/events/EventBusTest.kt
git commit -m "feat(core): add AutomationTriggered, AutomationFinished, QueuePositionChanged events"
```

---

### Task 5: Add Automation Settings to PluginSettings

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/settings/PluginSettings.kt`

- [ ] **Step 1: Add new settings fields**

In `core/src/main/kotlin/com/workflow/orchestrator/core/settings/PluginSettings.kt`, add inside the `State` class (after `var copyrightHeaderPattern`):

```kotlin
        // Automation & Docker Registry settings
        var dockerRegistryUrl by string("")
        var dockerRegistryCaPath by string("")
        var queueActivePollingIntervalSeconds by property(15)
        var queueAutoTriggerEnabled by property(true)
        var tagValidationOnTrigger by property(true)
        var queueMaxDepthPerSuite by property(10)
        var queueBuildQueuedTimeoutSeconds by property(720)
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :core:classes -x verifyPluginStructure`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/settings/PluginSettings.kt
git commit -m "feat(core): add Docker Registry and queue settings to PluginSettings"
```

---

## Chunk 2: BambooApiClient Extensions

### Task 6: Add New DTOs for Build Status and Variables

**Files:**
- Modify: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/api/dto/BambooDtos.kt`

- [ ] **Step 1: Add new DTOs**

In `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/api/dto/BambooDtos.kt`, add at the end of the file:

```kotlin
// --- Build Status DTOs (Phase 2A: Automation) ---

/** Wraps result list for running/queued builds query */
@Serializable
data class BambooBuildStatusResponse(
    val results: BambooResultCollection = BambooResultCollection()
)

/** Wraps variables attached to a specific build result */
@Serializable
data class BambooBuildVariablesResponse(
    val key: String = "",
    val buildNumber: Int = 0,
    val variables: BambooVariableCollection = BambooVariableCollection()
)
```

> **Note:** `BambooBuildStatusResponse` reuses the existing `BambooResultCollection` which contains `List<BambooResultDto>`. The `getRunningAndQueuedBuilds` method filters client-side by `lifeCycleState`. `BambooBuildVariablesResponse` reuses the existing `BambooVariableCollection`.

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :bamboo:classes -x verifyPluginStructure`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/api/dto/BambooDtos.kt
git commit -m "feat(bamboo): add BambooBuildStatusResponse and BambooBuildVariablesResponse DTOs"
```

---

### Task 7: Add `delete` Private Method to BambooApiClient

**Files:**
- Modify: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/api/BambooApiClient.kt`

- [ ] **Step 1: Add the `delete` method**

In `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/api/BambooApiClient.kt`, add after the `post` method (before the closing `}` of the class):

```kotlin
    private suspend fun delete(path: String): ApiResult<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url("$baseUrl$path").delete()
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> ApiResult.Success(Unit)
                        204 -> ApiResult.Success(Unit)
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bamboo token")
                        403 -> ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient Bamboo permissions")
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "Bamboo resource not found")
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Bamboo returned ${it.code}")
                    }
                }
            } catch (e: IOException) {
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bamboo: ${e.message}", e)
            }
        }
```

> **Note:** The `delete` method needs an `import okhttp3.internal.EMPTY_REQUEST` or just use `.delete()` with no body — OkHttp 4.x `delete()` with no arguments sends an empty body by default.

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :bamboo:classes -x verifyPluginStructure`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/api/BambooApiClient.kt
git commit -m "feat(bamboo): add delete HTTP method to BambooApiClient"
```

---

### Task 8: Add New Public API Methods to BambooApiClient

**Files:**
- Modify: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/api/BambooApiClient.kt`

- [ ] **Step 1: Write failing tests for the new methods**

Add to `bamboo/src/test/kotlin/com/workflow/orchestrator/bamboo/api/BambooApiClientTest.kt`:

```kotlin
@Test
fun `getRunningAndQueuedBuilds returns filtered active builds`() = runTest {
    server.enqueue(MockResponse().setBody(fixture("build-status-list.json")))

    val result = client.getRunningAndQueuedBuilds("PROJ-AUTO")

    assertTrue(result.isSuccess)
    val builds = (result as ApiResult.Success).data
    // Fixture has 3 results: 1 InProgress, 1 Queued, 1 Finished
    // Method filters to only InProgress + Queued
    assertEquals(2, builds.size)
    assertTrue(builds.all { it.lifeCycleState in listOf("InProgress", "Queued", "Pending") })

    val recorded = server.takeRequest()
    assertTrue(recorded.path!!.contains("/rest/api/latest/result/PROJ-AUTO"))
    assertTrue(recorded.path!!.contains("includeAllStates=true"))
}

@Test
fun `getBuildVariables returns variable map from build result`() = runTest {
    server.enqueue(MockResponse().setBody(fixture("build-variables.json")))

    val result = client.getBuildVariables("PROJ-AUTO-847")

    assertTrue(result.isSuccess)
    val vars = (result as ApiResult.Success).data
    assertEquals("regression", vars["suiteType"])
    assertTrue(vars.containsKey("dockerTagsAsJson"))

    val recorded = server.takeRequest()
    assertTrue(recorded.path!!.contains("/rest/api/latest/result/PROJ-AUTO-847"))
    assertTrue(recorded.path!!.contains("expand=variables"))
}

@Test
fun `getRecentResults returns last N build results`() = runTest {
    server.enqueue(MockResponse().setBody(fixture("recent-results.json")))

    val result = client.getRecentResults("PROJ-AUTO", maxResults = 5)

    assertTrue(result.isSuccess)
    val results = (result as ApiResult.Success).data
    assertTrue(results.size <= 5)

    val recorded = server.takeRequest()
    assertTrue(recorded.path!!.contains("/rest/api/latest/result/PROJ-AUTO"))
    assertTrue(recorded.path!!.contains("max-results=5"))
}

@Test
fun `cancelBuild sends DELETE and returns success`() = runTest {
    server.enqueue(MockResponse().setResponseCode(204))

    val result = client.cancelBuild("PROJ-AUTO-847")

    assertTrue(result.isSuccess)

    val recorded = server.takeRequest()
    assertEquals("DELETE", recorded.method)
    assertTrue(recorded.path!!.contains("/rest/api/latest/queue/PROJ-AUTO-847"))
}

@Test
fun `cancelBuild returns error on 404`() = runTest {
    server.enqueue(MockResponse().setResponseCode(404))

    val result = client.cancelBuild("PROJ-AUTO-999")

    assertTrue(result is ApiResult.Error)
    assertEquals(ErrorType.NOT_FOUND, (result as ApiResult.Error).type)
}
```

- [ ] **Step 2: Create test fixtures**

Create `bamboo/src/test/resources/fixtures/build-status-list.json`:

```json
{
  "results": {
    "size": 3,
    "result": [
      {
        "key": "PROJ-AUTO-849",
        "buildNumber": 849,
        "state": "Unknown",
        "lifeCycleState": "InProgress",
        "buildDurationInSeconds": 0,
        "buildRelativeTime": "a few seconds ago",
        "stages": { "size": 0, "stage": [] }
      },
      {
        "key": "PROJ-AUTO-848",
        "buildNumber": 848,
        "state": "Unknown",
        "lifeCycleState": "Queued",
        "buildDurationInSeconds": 0,
        "buildRelativeTime": "1 minute ago",
        "stages": { "size": 0, "stage": [] }
      },
      {
        "key": "PROJ-AUTO-847",
        "buildNumber": 847,
        "state": "Successful",
        "lifeCycleState": "Finished",
        "buildDurationInSeconds": 720,
        "buildRelativeTime": "15 minutes ago",
        "stages": { "size": 0, "stage": [] }
      }
    ]
  }
}
```

Create `bamboo/src/test/resources/fixtures/build-variables.json`:

```json
{
  "key": "PROJ-AUTO-847",
  "buildNumber": 847,
  "variables": {
    "size": 2,
    "variable": [
      { "name": "suiteType", "value": "regression" },
      { "name": "dockerTagsAsJson", "value": "{\"auth\":\"2.4.0\",\"payments\":\"2.3.1\"}" }
    ]
  }
}
```

Create `bamboo/src/test/resources/fixtures/recent-results.json`:

```json
{
  "results": {
    "size": 3,
    "result": [
      {
        "key": "PROJ-AUTO-849",
        "buildNumber": 849,
        "state": "Successful",
        "lifeCycleState": "Finished",
        "buildDurationInSeconds": 700,
        "buildRelativeTime": "5 minutes ago",
        "stages": {
          "size": 2,
          "stage": [
            { "name": "Build", "state": "Successful", "lifeCycleState": "Finished", "manual": false, "buildDurationInSeconds": 300 },
            { "name": "QA", "state": "Successful", "lifeCycleState": "Finished", "manual": false, "buildDurationInSeconds": 400 }
          ]
        }
      },
      {
        "key": "PROJ-AUTO-848",
        "buildNumber": 848,
        "state": "Failed",
        "lifeCycleState": "Finished",
        "buildDurationInSeconds": 650,
        "buildRelativeTime": "20 minutes ago",
        "stages": {
          "size": 2,
          "stage": [
            { "name": "Build", "state": "Successful", "lifeCycleState": "Finished", "manual": false, "buildDurationInSeconds": 300 },
            { "name": "QA", "state": "Failed", "lifeCycleState": "Finished", "manual": false, "buildDurationInSeconds": 350 }
          ]
        }
      },
      {
        "key": "PROJ-AUTO-847",
        "buildNumber": 847,
        "state": "Successful",
        "lifeCycleState": "Finished",
        "buildDurationInSeconds": 720,
        "buildRelativeTime": "35 minutes ago",
        "stages": {
          "size": 2,
          "stage": [
            { "name": "Build", "state": "Successful", "lifeCycleState": "Finished", "manual": false, "buildDurationInSeconds": 320 },
            { "name": "QA", "state": "Successful", "lifeCycleState": "Finished", "manual": false, "buildDurationInSeconds": 400 }
          ]
        }
      }
    ]
  }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :bamboo:test --tests "com.workflow.orchestrator.bamboo.api.BambooApiClientTest" -x verifyPluginStructure`
Expected: FAIL — `getRunningAndQueuedBuilds`, `getBuildVariables`, `getRecentResults`, `cancelBuild` are unresolved.

- [ ] **Step 4: Implement the new methods**

In `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/api/BambooApiClient.kt`, add the imports at the top:

```kotlin
import com.workflow.orchestrator.bamboo.api.dto.BambooBuildStatusResponse
import com.workflow.orchestrator.bamboo.api.dto.BambooBuildVariablesResponse
```

Add these public methods after `triggerBuild()`:

```kotlin
    suspend fun getRunningAndQueuedBuilds(planKey: String): ApiResult<List<BambooResultDto>> {
        return get<BambooBuildStatusResponse>(
            "/rest/api/latest/result/$planKey?includeAllStates=true&max-results=5"
        ).map { response ->
            response.results.result.filter { dto ->
                dto.lifeCycleState in listOf("InProgress", "Queued", "Pending")
            }
        }
    }

    suspend fun getBuildVariables(resultKey: String): ApiResult<Map<String, String>> {
        return get<BambooBuildVariablesResponse>(
            "/rest/api/latest/result/$resultKey?expand=variables"
        ).map { response ->
            response.variables.variable.associate { it.name to it.value }
        }
    }

    suspend fun getRecentResults(
        planKey: String,
        maxResults: Int = 10
    ): ApiResult<List<BambooResultDto>> {
        return get<BambooBuildStatusResponse>(
            "/rest/api/latest/result/$planKey?max-results=$maxResults&expand=stages.stage,variables"
        ).map { it.results.result }
    }

    suspend fun cancelBuild(resultKey: String): ApiResult<Unit> {
        return delete("/rest/api/latest/queue/$resultKey")
    }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :bamboo:test --tests "com.workflow.orchestrator.bamboo.api.BambooApiClientTest" -x verifyPluginStructure`
Expected: PASS — all new and existing tests green.

- [ ] **Step 6: Commit**

```bash
git add bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/api/BambooApiClient.kt \
     bamboo/src/test/kotlin/com/workflow/orchestrator/bamboo/api/BambooApiClientTest.kt \
     bamboo/src/test/resources/fixtures/build-status-list.json \
     bamboo/src/test/resources/fixtures/build-variables.json \
     bamboo/src/test/resources/fixtures/recent-results.json
git commit -m "feat(bamboo): add getRunningAndQueuedBuilds, getBuildVariables, getRecentResults, cancelBuild"
```

---

## Chunk 3: Docker Registry Client

### Task 9: Create DockerRegistryClient with Token Auth and Tag Existence Check

**Files:**
- Create: `automation/src/main/kotlin/com/workflow/orchestrator/automation/api/DockerRegistryClient.kt`
- Create: `automation/src/test/kotlin/com/workflow/orchestrator/automation/api/DockerRegistryClientTest.kt`
- Create: `automation/src/test/resources/fixtures/docker-tags-list.json`
- Create: `automation/src/test/resources/fixtures/docker-auth-token.json`

- [ ] **Step 1: Create test fixtures**

Create `automation/src/test/resources/fixtures/docker-tags-list.json`:

```json
{
  "name": "service-auth",
  "tags": ["2.3.0", "2.3.1", "2.4.0", "feature-PROJ-123-a1b2c3d", "1.0.0"]
}
```

Create `automation/src/test/resources/fixtures/docker-auth-token.json`:

```json
{
  "token": "test-bearer-token-123",
  "access_token": "",
  "expires_in": 300,
  "issued_at": "2026-03-11T10:00:00Z"
}
```

- [ ] **Step 2: Write tests for tag existence and auth handshake**

Create `automation/src/test/kotlin/com/workflow/orchestrator/automation/api/DockerRegistryClientTest.kt`:

```kotlin
package com.workflow.orchestrator.automation.api

import com.workflow.orchestrator.core.model.ApiResult
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DockerRegistryClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: DockerRegistryClient

    private fun fixture(name: String): String =
        javaClass.classLoader.getResource("fixtures/$name")!!.readText()

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = DockerRegistryClient(
            registryUrl = server.url("/").toString().trimEnd('/'),
            tokenProvider = { "test-basic-token" }
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `tagExists returns true when HEAD returns 200`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        val result = client.tagExists("service-auth", "2.4.0")

        assertTrue(result.isSuccess)
        assertTrue((result as ApiResult.Success).data)

        val recorded = server.takeRequest()
        assertEquals("HEAD", recorded.method)
        assertTrue(recorded.path!!.contains("/v2/service-auth/manifests/2.4.0"))
    }

    @Test
    fun `tagExists returns false when HEAD returns 404`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = client.tagExists("service-auth", "nonexistent")

        assertTrue(result.isSuccess)
        assertFalse((result as ApiResult.Success).data)
    }

    @Test
    fun `listTags returns parsed tag list`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("docker-tags-list.json")))

        val result = client.listTags("service-auth")

        assertTrue(result.isSuccess)
        val tags = (result as ApiResult.Success).data
        assertEquals(5, tags.size)
        assertTrue(tags.contains("2.4.0"))
        assertTrue(tags.contains("feature-PROJ-123-a1b2c3d"))
    }

    @Test
    fun `listTags handles empty tags`() = runTest {
        server.enqueue(MockResponse().setBody("""{"name":"service-new","tags":null}"""))

        val result = client.listTags("service-new")

        assertTrue(result.isSuccess)
        val tags = (result as ApiResult.Success).data
        assertTrue(tags.isEmpty())
    }

    @Test
    fun `auth handshake on 401 with WWW-Authenticate header`() = runTest {
        // First request returns 401 with auth challenge
        val authUrl = server.url("/token").toString()
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader(
                    "WWW-Authenticate",
                    """Bearer realm="$authUrl",service="registry.example.com",scope="repository:service-auth:pull""""
                )
        )
        // Token endpoint returns bearer token
        server.enqueue(MockResponse().setBody(fixture("docker-auth-token.json")))
        // Retry original request with bearer token
        server.enqueue(MockResponse().setBody(fixture("docker-tags-list.json")))

        val result = client.listTags("service-auth")

        assertTrue(result.isSuccess)
        val tags = (result as ApiResult.Success).data
        assertEquals(5, tags.size)

        // Verify 3 requests: original, token, retry
        assertEquals(3, server.requestCount)
        val retryRequest = server.takeRequest() // first 401
        server.takeRequest() // token request
        val finalRequest = server.takeRequest() // retry with token
        assertTrue(finalRequest.getHeader("Authorization")!!.startsWith("Bearer "))
    }

    @Test
    fun `getLatestReleaseTag returns highest semver tag`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("docker-tags-list.json")))

        val result = client.getLatestReleaseTag("service-auth")

        assertTrue(result.isSuccess)
        assertEquals("2.4.0", (result as ApiResult.Success).data)
    }

    @Test
    fun `getLatestReleaseTag returns null when no release tags exist`() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"name":"service-auth","tags":["feature-abc","develop-xyz"]}""")
        )

        val result = client.getLatestReleaseTag("service-auth")

        assertTrue(result.isSuccess)
        assertNull((result as ApiResult.Success).data)
    }

    @Test
    fun `tagExists handles auth handshake transparently`() = runTest {
        val authUrl = server.url("/token").toString()
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader(
                    "WWW-Authenticate",
                    """Bearer realm="$authUrl",service="registry.example.com",scope="repository:service-auth:pull""""
                )
        )
        server.enqueue(MockResponse().setBody(fixture("docker-auth-token.json")))
        server.enqueue(MockResponse().setResponseCode(200))

        val result = client.tagExists("service-auth", "2.4.0")

        assertTrue(result.isSuccess)
        assertTrue((result as ApiResult.Success).data)
    }

    @Test
    fun `listTags follows pagination Link header`() = runTest {
        // First page with Link header
        server.enqueue(
            MockResponse()
                .setBody("""{"name":"service-auth","tags":["1.0.0","1.1.0"]}""")
                .setHeader("Link", """</v2/service-auth/tags/list?n=2&last=1.1.0>; rel="next"""")
        )
        // Second page without Link header (last page)
        server.enqueue(
            MockResponse()
                .setBody("""{"name":"service-auth","tags":["2.0.0"]}""")
        )

        val result = client.listTags("service-auth")

        assertTrue(result.isSuccess)
        val tags = (result as ApiResult.Success).data
        assertEquals(3, tags.size)
        assertTrue(tags.containsAll(listOf("1.0.0", "1.1.0", "2.0.0")))
    }

    @Test
    fun `handles network error gracefully`() = runTest {
        server.shutdown() // Force connection failure

        val result = client.tagExists("service-auth", "2.4.0")

        assertTrue(result is ApiResult.Error)
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :automation:test --tests "com.workflow.orchestrator.automation.api.DockerRegistryClientTest" -x verifyPluginStructure`
Expected: FAIL — `DockerRegistryClient` class not found.

- [ ] **Step 4: Implement DockerRegistryClient**

Create `automation/src/main/kotlin/com/workflow/orchestrator/automation/api/DockerRegistryClient.kt`:

```kotlin
package com.workflow.orchestrator.automation.api

import com.workflow.orchestrator.automation.model.DockerAuthChallenge
import com.workflow.orchestrator.automation.model.DockerAuthTokenResponse
import com.workflow.orchestrator.automation.model.DockerTagListResponse
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Docker Registry v2 API client.
 * @param registryUrl Base URL of the Docker Registry (e.g., "https://registry.example.com")
 * @param tokenProvider Returns a base64-encoded Basic auth token for the registry
 *                      (format: base64("username:password") or base64("token:")).
 *                      Used for initial auth to the token endpoint, NOT for registry API calls.
 */
class DockerRegistryClient(
    private val registryUrl: String,
    private val tokenProvider: () -> String?
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // Token cache: scope → (token, expiresAtMs)
    private val tokenCache = ConcurrentHashMap<String, Pair<String, Long>>()

    // Tag cache: serviceName → (tags, expiresAtMs)
    private val tagCache = ConcurrentHashMap<String, Pair<List<String>, Long>>()
    private val tagCacheTtlMs = 5 * 60 * 1000L // 5 minutes

    suspend fun tagExists(serviceName: String, tag: String): ApiResult<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$registryUrl/v2/$serviceName/manifests/$tag")
                    .head()
                    .header("Accept", "application/vnd.docker.distribution.manifest.v2+json")
                    .build()

                executeWithAuth(request) { response ->
                    when (response.code) {
                        in 200..299 -> ApiResult.Success(true)
                        404 -> ApiResult.Success(false)
                        else -> ApiResult.Error(
                            ErrorType.SERVER_ERROR,
                            "Registry returned ${response.code}"
                        )
                    }
                }
            } catch (e: IOException) {
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Docker Registry: ${e.message}", e)
            }
        }

    suspend fun listTags(serviceName: String): ApiResult<List<String>> =
        withContext(Dispatchers.IO) {
            // Check cache
            val cached = tagCache[serviceName]
            if (cached != null && System.currentTimeMillis() < cached.second) {
                return@withContext ApiResult.Success(cached.first)
            }

            try {
                val allTags = mutableListOf<String>()
                var path = "/v2/$serviceName/tags/list?n=100"
                var pageCount = 0
                val maxPages = 50

                while (path != null && pageCount < maxPages) {
                    val request = Request.Builder()
                        .url("$registryUrl$path")
                        .get()
                        .header("Accept", "application/json")
                        .build()

                    val pageResult = executeWithAuth(request) { response ->
                        when (response.code) {
                            in 200..299 -> {
                                val body = response.body?.string() ?: ""
                                val tagList = json.decodeFromString<DockerTagListResponse>(body)
                                val tags = tagList.tags ?: emptyList()
                                allTags.addAll(tags)

                                // Check for pagination Link header
                                val linkHeader = response.header("Link")
                                path = parseLinkHeader(linkHeader)

                                ApiResult.Success(tags)
                            }
                            404 -> {
                                path = null
                                ApiResult.Success(emptyList())
                            }
                            else -> {
                                path = null
                                ApiResult.Error<List<String>>(
                                    ErrorType.SERVER_ERROR,
                                    "Registry returned ${response.code}"
                                )
                            }
                        }
                    }

                    if (pageResult is ApiResult.Error) {
                        return@withContext pageResult as ApiResult<List<String>>
                    }
                    pageCount++
                }

                // Cache the result
                tagCache[serviceName] = allTags.toList() to
                    (System.currentTimeMillis() + tagCacheTtlMs)

                ApiResult.Success(allTags.toList())
            } catch (e: IOException) {
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Docker Registry: ${e.message}", e)
            }
        }

    suspend fun getLatestReleaseTag(serviceName: String): ApiResult<String?> {
        return when (val result = listTags(serviceName)) {
            is ApiResult.Success -> {
                val semverPattern = Regex("""^\d+\.\d+\.\d+.*$""")
                val releaseTags = result.data.filter { semverPattern.matches(it) }
                val sorted = releaseTags.sortedWith(SemverComparator)
                ApiResult.Success(sorted.lastOrNull())
            }
            is ApiResult.Error -> ApiResult.Error(result.type, result.message, result.cause)
        }
    }

    /**
     * Executes a request with Docker Registry v2 token authentication.
     * On 401, parses WWW-Authenticate, fetches a bearer token, and retries.
     */
    private fun <T> executeWithAuth(
        request: Request,
        handler: (okhttp3.Response) -> ApiResult<T>
    ): ApiResult<T> {
        val response = httpClient.newCall(request).execute()
        return response.use {
            if (it.code == 401) {
                val challenge = parseWwwAuthenticate(it.header("WWW-Authenticate"))
                if (challenge != null) {
                    val token = fetchBearerToken(challenge)
                    if (token != null) {
                        val retryRequest = request.newBuilder()
                            .header("Authorization", "Bearer $token")
                            .build()
                        val retryResponse = httpClient.newCall(retryRequest).execute()
                        return retryResponse.use { retry -> handler(retry) }
                    }
                }
                ApiResult.Error(ErrorType.AUTH_FAILED, "Docker Registry authentication failed")
            } else {
                handler(it)
            }
        }
    }

    internal fun parseWwwAuthenticate(header: String?): DockerAuthChallenge? {
        if (header == null || !header.startsWith("Bearer ")) return null
        val params = header.removePrefix("Bearer ").split(",").associate { part ->
            val (key, value) = part.trim().split("=", limit = 2)
            key.trim() to value.trim().removeSurrounding("\"")
        }
        val realm = params["realm"] ?: return null
        val service = params["service"] ?: ""
        val scope = params["scope"] ?: ""
        return DockerAuthChallenge(realm, service, scope)
    }

    private fun fetchBearerToken(challenge: DockerAuthChallenge): String? {
        // Check cache
        val cacheKey = "${challenge.service}:${challenge.scope}"
        val cached = tokenCache[cacheKey]
        if (cached != null && System.currentTimeMillis() < cached.second) {
            return cached.first
        }

        val url = buildString {
            append(challenge.realm)
            append("?service=${challenge.service}")
            if (challenge.scope.isNotEmpty()) {
                append("&scope=${challenge.scope}")
            }
        }

        val request = Request.Builder().url(url).get().build()
        val basicToken = tokenProvider()
        val authRequest = if (basicToken != null) {
            request.newBuilder()
                .header("Authorization", "Basic $basicToken")
                .build()
        } else {
            request
        }

        return try {
            val response = httpClient.newCall(authRequest).execute()
            response.use {
                if (it.isSuccessful) {
                    val body = it.body?.string() ?: return null
                    val tokenResponse = json.decodeFromString<DockerAuthTokenResponse>(body)
                    val token = tokenResponse.effectiveToken()

                    // Cache at 80% of TTL
                    val expiresAtMs = System.currentTimeMillis() +
                        (tokenResponse.expiresIn * 800L)
                    tokenCache[cacheKey] = token to expiresAtMs

                    token
                } else {
                    null
                }
            }
        } catch (e: IOException) {
            null
        }
    }

    private fun parseLinkHeader(header: String?): String? {
        if (header == null) return null
        // Format: </v2/name/tags/list?n=100&last=tag>; rel="next"
        val match = Regex("""<([^>]+)>;\s*rel="next"""").find(header) ?: return null
        return match.groupValues[1]
    }

    /** Compares semver strings (major.minor.patch) */
    internal object SemverComparator : Comparator<String> {
        override fun compare(a: String, b: String): Int {
            val aParts = a.split(".").mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
            val bParts = b.split(".").mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
            for (i in 0 until maxOf(aParts.size, bParts.size)) {
                val aVal = aParts.getOrElse(i) { 0 }
                val bVal = bParts.getOrElse(i) { 0 }
                if (aVal != bVal) return aVal.compareTo(bVal)
            }
            return 0
        }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :automation:test --tests "com.workflow.orchestrator.automation.api.DockerRegistryClientTest" -x verifyPluginStructure`
Expected: PASS — all tests green.

- [ ] **Step 6: Commit**

```bash
git add automation/src/main/kotlin/com/workflow/orchestrator/automation/api/DockerRegistryClient.kt \
     automation/src/test/kotlin/com/workflow/orchestrator/automation/api/DockerRegistryClientTest.kt \
     automation/src/test/resources/fixtures/docker-tags-list.json \
     automation/src/test/resources/fixtures/docker-auth-token.json
git commit -m "feat(automation): implement DockerRegistryClient with token auth, pagination, semver sorting"
```

---

## Chunk 4: Settings & History Services

### Task 10: Create AutomationSettingsService (APP-level Persistence)

**Files:**
- Create: `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/AutomationSettingsService.kt`
- Create: `automation/src/test/kotlin/com/workflow/orchestrator/automation/service/AutomationSettingsServiceTest.kt`

> **Why `PersistentStateComponent` and not `SimplePersistentStateComponent`:** The `State` contains nested `Map<String, SuiteConfig>` with `List` fields — `BaseState` delegates only support flat primitive properties, not complex nested structures. See spec Section 7.1.

- [ ] **Step 1: Write tests**

Create `automation/src/test/kotlin/com/workflow/orchestrator/automation/service/AutomationSettingsServiceTest.kt`:

```kotlin
package com.workflow.orchestrator.automation.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AutomationSettingsServiceTest {

    private lateinit var service: AutomationSettingsService

    @BeforeEach
    fun setUp() {
        service = AutomationSettingsService()
    }

    @Test
    fun `saveSuiteConfig persists and retrieves config`() {
        val config = AutomationSettingsService.SuiteConfig(
            planKey = "PROJ-AUTO",
            displayName = "E2E Regression",
            variables = mapOf("suiteType" to "regression", "featureFlag" to "true"),
            enabledStages = listOf("QA Automation"),
            serviceNameMapping = mapOf("intellij-workflow" to "service-auth"),
            lastModified = System.currentTimeMillis()
        )

        service.saveSuiteConfig(config)

        val retrieved = service.getSuiteConfig("PROJ-AUTO")
        assertNotNull(retrieved)
        assertEquals("E2E Regression", retrieved!!.displayName)
        assertEquals("regression", retrieved.variables["suiteType"])
        assertEquals(listOf("QA Automation"), retrieved.enabledStages)
    }

    @Test
    fun `getSuiteConfig returns null for unknown plan`() {
        assertNull(service.getSuiteConfig("UNKNOWN-PLAN"))
    }

    @Test
    fun `getAllSuites returns all saved configs`() {
        service.saveSuiteConfig(
            AutomationSettingsService.SuiteConfig(
                planKey = "PROJ-AUTO1",
                displayName = "Suite 1",
                variables = emptyMap(),
                enabledStages = emptyList(),
                serviceNameMapping = null,
                lastModified = 1000
            )
        )
        service.saveSuiteConfig(
            AutomationSettingsService.SuiteConfig(
                planKey = "PROJ-AUTO2",
                displayName = "Suite 2",
                variables = emptyMap(),
                enabledStages = emptyList(),
                serviceNameMapping = null,
                lastModified = 2000
            )
        )

        val all = service.getAllSuites()
        assertEquals(2, all.size)
    }

    @Test
    fun `saveSuiteConfig overwrites existing config`() {
        val config1 = AutomationSettingsService.SuiteConfig(
            planKey = "PROJ-AUTO",
            displayName = "Old Name",
            variables = emptyMap(),
            enabledStages = emptyList(),
            serviceNameMapping = null,
            lastModified = 1000
        )
        service.saveSuiteConfig(config1)

        val config2 = config1.copy(displayName = "New Name", lastModified = 2000)
        service.saveSuiteConfig(config2)

        val retrieved = service.getSuiteConfig("PROJ-AUTO")
        assertEquals("New Name", retrieved!!.displayName)
        assertEquals(1, service.getAllSuites().size)
    }

    @Test
    fun `getState and loadState round-trip`() {
        val config = AutomationSettingsService.SuiteConfig(
            planKey = "PROJ-AUTO",
            displayName = "Test Suite",
            variables = mapOf("key" to "value"),
            enabledStages = listOf("Stage1"),
            serviceNameMapping = null,
            lastModified = 1000
        )
        service.saveSuiteConfig(config)

        val state = service.state
        val newService = AutomationSettingsService()
        newService.loadState(state)

        val retrieved = newService.getSuiteConfig("PROJ-AUTO")
        assertNotNull(retrieved)
        assertEquals("Test Suite", retrieved!!.displayName)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :automation:test --tests "com.workflow.orchestrator.automation.service.AutomationSettingsServiceTest" -x verifyPluginStructure`
Expected: FAIL — `AutomationSettingsService` not found.

- [ ] **Step 3: Implement AutomationSettingsService**

Create `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/AutomationSettingsService.kt`:

```kotlin
package com.workflow.orchestrator.automation.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.APP)
@State(
    name = "AutomationSuiteSettings",
    storages = [Storage("workflowAutomationSuites.xml")]
)
class AutomationSettingsService : PersistentStateComponent<AutomationSettingsService.SettingsState> {

    data class SuiteConfig(
        val planKey: String = "",
        val displayName: String = "",
        val variables: Map<String, String> = emptyMap(),
        val enabledStages: List<String> = emptyList(),
        val serviceNameMapping: Map<String, String>? = null,
        val lastModified: Long = 0
    )

    data class SettingsState(
        var suites: MutableMap<String, SuiteConfig> = mutableMapOf()
    )

    private var myState = SettingsState()

    override fun getState(): SettingsState = myState

    override fun loadState(state: SettingsState) {
        myState = state
    }

    fun getSuiteConfig(planKey: String): SuiteConfig? = myState.suites[planKey]

    fun saveSuiteConfig(config: SuiteConfig) {
        myState.suites[config.planKey] = config
    }

    fun getAllSuites(): List<SuiteConfig> = myState.suites.values.toList()

    companion object {
        fun getInstance(): AutomationSettingsService =
            ApplicationManager.getApplication().getService(AutomationSettingsService::class.java)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :automation:test --tests "com.workflow.orchestrator.automation.service.AutomationSettingsServiceTest" -x verifyPluginStructure`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add automation/src/main/kotlin/com/workflow/orchestrator/automation/service/AutomationSettingsService.kt \
     automation/src/test/kotlin/com/workflow/orchestrator/automation/service/AutomationSettingsServiceTest.kt
git commit -m "feat(automation): add AutomationSettingsService with APP-level persistence"
```

---

### Task 11: Create TagHistoryService with SQLite Persistence

**Files:**
- Create: `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/TagHistoryService.kt`
- Create: `automation/src/test/kotlin/com/workflow/orchestrator/automation/service/TagHistoryServiceTest.kt`

- [ ] **Step 1: Write tests**

Create `automation/src/test/kotlin/com/workflow/orchestrator/automation/service/TagHistoryServiceTest.kt`:

```kotlin
package com.workflow.orchestrator.automation.service

import com.workflow.orchestrator.automation.model.HistoryEntry
import com.workflow.orchestrator.automation.model.QueueEntry
import com.workflow.orchestrator.automation.model.QueueEntryStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant

class TagHistoryServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var service: TagHistoryService

    @BeforeEach
    fun setUp() {
        service = TagHistoryService(tempDir.resolve("automation.db").toString())
    }

    @AfterEach
    fun tearDown() {
        service.close()
    }

    // --- History tests ---

    @Test
    fun `saveHistory and getHistory round-trip`() {
        val entry = HistoryEntry(
            id = "hist-1",
            suitePlanKey = "PROJ-AUTO",
            dockerTagsJson = """{"auth":"2.4.0"}""",
            variables = mapOf("suiteType" to "regression"),
            stages = listOf("QA Automation"),
            triggeredAt = Instant.now(),
            buildResultKey = "PROJ-AUTO-847",
            buildPassed = true
        )
        service.saveHistory(entry)

        val history = service.getHistory("PROJ-AUTO")
        assertEquals(1, history.size)
        assertEquals("hist-1", history[0].id)
        assertEquals("regression", history[0].variables["suiteType"])
        assertTrue(history[0].buildPassed!!)
    }

    @Test
    fun `getHistory limits to 5 entries per suite`() {
        for (i in 1..8) {
            service.saveHistory(
                HistoryEntry(
                    id = "hist-$i",
                    suitePlanKey = "PROJ-AUTO",
                    dockerTagsJson = """{"auth":"$i.0.0"}""",
                    variables = emptyMap(),
                    stages = emptyList(),
                    triggeredAt = Instant.ofEpochSecond(i.toLong() * 1000),
                    buildResultKey = null,
                    buildPassed = null
                )
            )
        }

        val history = service.getHistory("PROJ-AUTO", limit = 5)
        assertEquals(5, history.size)
        // Should return the most recent 5
        assertEquals("hist-8", history[0].id)
    }

    @Test
    fun `getHistory separates suites`() {
        service.saveHistory(
            HistoryEntry("h1", "SUITE-A", "{}", emptyMap(), emptyList(), Instant.now(), null, null)
        )
        service.saveHistory(
            HistoryEntry("h2", "SUITE-B", "{}", emptyMap(), emptyList(), Instant.now(), null, null)
        )

        assertEquals(1, service.getHistory("SUITE-A").size)
        assertEquals(1, service.getHistory("SUITE-B").size)
    }

    // --- Queue persistence tests ---

    @Test
    fun `saveQueueEntry and getActiveQueueEntries round-trip`() {
        val entry = QueueEntry(
            id = "q-1",
            suitePlanKey = "PROJ-AUTO",
            dockerTagsPayload = """{"auth":"2.4.0"}""",
            variables = mapOf("suiteType" to "regression"),
            stages = listOf("QA Automation"),
            enqueuedAt = Instant.now(),
            status = QueueEntryStatus.WAITING_LOCAL,
            bambooResultKey = null
        )
        service.saveQueueEntry(entry, sequenceOrder = 1)

        val active = service.getActiveQueueEntries()
        assertEquals(1, active.size)
        assertEquals("q-1", active[0].id)
        assertEquals(QueueEntryStatus.WAITING_LOCAL, active[0].status)
    }

    @Test
    fun `updateQueueEntryStatus changes status and result key`() {
        val entry = QueueEntry(
            id = "q-1",
            suitePlanKey = "PROJ-AUTO",
            dockerTagsPayload = "{}",
            variables = emptyMap(),
            stages = emptyList(),
            enqueuedAt = Instant.now(),
            status = QueueEntryStatus.WAITING_LOCAL,
            bambooResultKey = null
        )
        service.saveQueueEntry(entry, sequenceOrder = 1)

        service.updateQueueEntryStatus("q-1", QueueEntryStatus.RUNNING, bambooResultKey = "PROJ-AUTO-849")

        val active = service.getActiveQueueEntries()
        assertEquals(1, active.size)
        assertEquals(QueueEntryStatus.RUNNING, active[0].status)
        assertEquals("PROJ-AUTO-849", active[0].bambooResultKey)
    }

    @Test
    fun `getActiveQueueEntries excludes terminal statuses`() {
        service.saveQueueEntry(
            QueueEntry("q-1", "P", "{}", emptyMap(), emptyList(), Instant.now(), QueueEntryStatus.WAITING_LOCAL, null),
            sequenceOrder = 1
        )
        service.saveQueueEntry(
            QueueEntry("q-2", "P", "{}", emptyMap(), emptyList(), Instant.now(), QueueEntryStatus.COMPLETED, null),
            sequenceOrder = 2
        )
        service.saveQueueEntry(
            QueueEntry("q-3", "P", "{}", emptyMap(), emptyList(), Instant.now(), QueueEntryStatus.CANCELLED, null),
            sequenceOrder = 3
        )

        val active = service.getActiveQueueEntries()
        assertEquals(1, active.size)
        assertEquals("q-1", active[0].id)
    }

    @Test
    fun `deleteQueueEntry removes entry`() {
        service.saveQueueEntry(
            QueueEntry("q-1", "P", "{}", emptyMap(), emptyList(), Instant.now(), QueueEntryStatus.WAITING_LOCAL, null),
            sequenceOrder = 1
        )

        service.deleteQueueEntry("q-1")

        assertTrue(service.getActiveQueueEntries().isEmpty())
    }

    @Test
    fun `loadAsBaseline returns tag map from history entry`() {
        service.saveHistory(
            HistoryEntry(
                "h1", "PROJ-AUTO",
                """{"auth":"2.4.0","payments":"2.3.1"}""",
                emptyMap(), emptyList(), Instant.now(), null, null
            )
        )

        val tags = service.loadAsBaseline("h1")
        assertEquals(2, tags.size)
        assertEquals("2.4.0", tags["auth"])
        assertEquals("2.3.1", tags["payments"])
    }

    @Test
    fun `loadAsBaseline returns empty map for unknown entry`() {
        assertTrue(service.loadAsBaseline("unknown").isEmpty())
    }

    @Test
    fun `database integrity check passes on fresh DB`() {
        assertTrue(service.integrityCheck())
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :automation:test --tests "com.workflow.orchestrator.automation.service.TagHistoryServiceTest" -x verifyPluginStructure`
Expected: FAIL — `TagHistoryService` not found.

- [ ] **Step 3: Implement TagHistoryService**

Create `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/TagHistoryService.kt`:

```kotlin
package com.workflow.orchestrator.automation.service

import com.workflow.orchestrator.automation.model.HistoryEntry
import com.workflow.orchestrator.automation.model.QueueEntry
import com.workflow.orchestrator.automation.model.QueueEntryStatus
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

/**
 * PROJECT-level SQLite persistence for queue entries and run history.
 * Database path: `<project>/.idea/workflow-orchestrator/automation.db`
 *
 * Constructed with explicit dbPath for testability (in-memory or temp file).
 * The project-level service wrapper passes the real path.
 */
class TagHistoryService(private val dbPath: String) {

    private val json = Json { ignoreUnknownKeys = true }
    private val connection: Connection by lazy {
        val parentDir = File(dbPath).parentFile
        parentDir?.mkdirs()
        val conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        conn.apply {
            createStatement().execute("PRAGMA journal_mode = WAL")
            createStatement().execute("PRAGMA busy_timeout = 5000")
            createStatement().execute("PRAGMA wal_autocheckpoint = 1000")
        }
        initSchema(conn)
        conn
    }

    private fun initSchema(conn: Connection) {
        conn.createStatement().executeUpdate("""
            CREATE TABLE IF NOT EXISTS schema_metadata (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
        """)
        conn.createStatement().executeUpdate("""
            INSERT OR IGNORE INTO schema_metadata VALUES ('schema_version', '1')
        """)
        conn.createStatement().executeUpdate("""
            CREATE TABLE IF NOT EXISTS queue_entries (
                id TEXT PRIMARY KEY,
                suite_plan_key TEXT NOT NULL,
                docker_tags_json TEXT NOT NULL,
                variables_json TEXT NOT NULL,
                stages_json TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'WAITING_LOCAL',
                bamboo_result_key TEXT,
                enqueued_at INTEGER NOT NULL,
                sequence_order INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                error_message TEXT
            )
        """)
        conn.createStatement().executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_queue_suite ON queue_entries(suite_plan_key, sequence_order)
        """)
        conn.createStatement().executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_queue_status ON queue_entries(status)
        """)
        conn.createStatement().executeUpdate("""
            CREATE TABLE IF NOT EXISTS automation_history (
                id TEXT PRIMARY KEY,
                suite_plan_key TEXT NOT NULL,
                docker_tags_json TEXT NOT NULL,
                variables_json TEXT NOT NULL,
                stages_json TEXT NOT NULL,
                triggered_at INTEGER NOT NULL,
                build_result_key TEXT,
                build_passed INTEGER,
                created_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now'))
            )
        """)
        conn.createStatement().executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_history_suite ON automation_history(suite_plan_key, triggered_at DESC)
        """)
    }

    // --- History ---

    fun saveHistory(entry: HistoryEntry) {
        val stmt = connection.prepareStatement("""
            INSERT OR REPLACE INTO automation_history
            (id, suite_plan_key, docker_tags_json, variables_json, stages_json,
             triggered_at, build_result_key, build_passed)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """)
        stmt.setString(1, entry.id)
        stmt.setString(2, entry.suitePlanKey)
        stmt.setString(3, entry.dockerTagsJson)
        stmt.setString(4, json.encodeToString(entry.variables))
        stmt.setString(5, json.encodeToString(entry.stages))
        stmt.setLong(6, entry.triggeredAt.epochSecond)
        stmt.setString(7, entry.buildResultKey)
        if (entry.buildPassed != null) stmt.setInt(8, if (entry.buildPassed) 1 else 0)
        else stmt.setNull(8, java.sql.Types.INTEGER)
        stmt.executeUpdate()
    }

    fun getHistory(suitePlanKey: String, limit: Int = 5): List<HistoryEntry> {
        val stmt = connection.prepareStatement("""
            SELECT * FROM automation_history
            WHERE suite_plan_key = ?
            ORDER BY triggered_at DESC
            LIMIT ?
        """)
        stmt.setString(1, suitePlanKey)
        stmt.setInt(2, limit)
        val rs = stmt.executeQuery()
        val results = mutableListOf<HistoryEntry>()
        while (rs.next()) {
            results.add(
                HistoryEntry(
                    id = rs.getString("id"),
                    suitePlanKey = rs.getString("suite_plan_key"),
                    dockerTagsJson = rs.getString("docker_tags_json"),
                    variables = json.decodeFromString(rs.getString("variables_json")),
                    stages = json.decodeFromString(rs.getString("stages_json")),
                    triggeredAt = Instant.ofEpochSecond(rs.getLong("triggered_at")),
                    buildResultKey = rs.getString("build_result_key"),
                    buildPassed = rs.getObject("build_passed")?.let { (it as Int) == 1 }
                )
            )
        }
        return results
    }

    /**
     * Loads a history entry's docker tags as a Map for use as baseline in the staging table.
     */
    fun loadAsBaseline(entryId: String): Map<String, String> {
        val stmt = connection.prepareStatement(
            "SELECT docker_tags_json FROM automation_history WHERE id = ?"
        )
        stmt.setString(1, entryId)
        val rs = stmt.executeQuery()
        return if (rs.next()) {
            try {
                json.decodeFromString<Map<String, String>>(rs.getString("docker_tags_json"))
            } catch (e: Exception) {
                emptyMap()
            }
        } else {
            emptyMap()
        }
    }

    // --- Queue Persistence ---

    fun saveQueueEntry(entry: QueueEntry, sequenceOrder: Int) {
        val stmt = connection.prepareStatement("""
            INSERT OR REPLACE INTO queue_entries
            (id, suite_plan_key, docker_tags_json, variables_json, stages_json,
             status, bamboo_result_key, enqueued_at, sequence_order, updated_at, error_message)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """)
        stmt.setString(1, entry.id)
        stmt.setString(2, entry.suitePlanKey)
        stmt.setString(3, entry.dockerTagsPayload)
        stmt.setString(4, json.encodeToString(entry.variables))
        stmt.setString(5, json.encodeToString(entry.stages))
        stmt.setString(6, entry.status.name)
        stmt.setString(7, entry.bambooResultKey)
        stmt.setLong(8, entry.enqueuedAt.epochSecond)
        stmt.setInt(9, sequenceOrder)
        stmt.setLong(10, Instant.now().epochSecond)
        stmt.setString(11, entry.errorMessage)
        stmt.executeUpdate()
    }

    fun updateQueueEntryStatus(
        entryId: String,
        status: QueueEntryStatus,
        bambooResultKey: String? = null,
        errorMessage: String? = null
    ) {
        val stmt = if (bambooResultKey != null) {
            connection.prepareStatement("""
                UPDATE queue_entries SET status = ?, bamboo_result_key = ?, updated_at = ?, error_message = ?
                WHERE id = ?
            """).apply {
                setString(1, status.name)
                setString(2, bambooResultKey)
                setLong(3, Instant.now().epochSecond)
                setString(4, errorMessage)
                setString(5, entryId)
            }
        } else {
            connection.prepareStatement("""
                UPDATE queue_entries SET status = ?, updated_at = ?, error_message = ?
                WHERE id = ?
            """).apply {
                setString(1, status.name)
                setLong(2, Instant.now().epochSecond)
                setString(3, errorMessage)
                setString(4, entryId)
            }
        }
        stmt.executeUpdate()
    }

    fun getActiveQueueEntries(): List<QueueEntry> {
        val terminalStatuses = listOf(
            QueueEntryStatus.COMPLETED.name,
            QueueEntryStatus.CANCELLED.name
        )
        val stmt = connection.prepareStatement("""
            SELECT * FROM queue_entries
            WHERE status NOT IN (?, ?)
            ORDER BY sequence_order ASC
        """)
        stmt.setString(1, terminalStatuses[0])
        stmt.setString(2, terminalStatuses[1])
        val rs = stmt.executeQuery()
        val results = mutableListOf<QueueEntry>()
        while (rs.next()) {
            results.add(
                QueueEntry(
                    id = rs.getString("id"),
                    suitePlanKey = rs.getString("suite_plan_key"),
                    dockerTagsPayload = rs.getString("docker_tags_json"),
                    variables = json.decodeFromString(rs.getString("variables_json")),
                    stages = json.decodeFromString(rs.getString("stages_json")),
                    enqueuedAt = Instant.ofEpochSecond(rs.getLong("enqueued_at")),
                    status = QueueEntryStatus.valueOf(rs.getString("status")),
                    bambooResultKey = rs.getString("bamboo_result_key"),
                    errorMessage = rs.getString("error_message")
                )
            )
        }
        return results
    }

    fun deleteQueueEntry(entryId: String) {
        val stmt = connection.prepareStatement("DELETE FROM queue_entries WHERE id = ?")
        stmt.setString(1, entryId)
        stmt.executeUpdate()
    }

    fun integrityCheck(): Boolean {
        val rs = connection.createStatement().executeQuery("PRAGMA quick_check")
        return rs.next() && rs.getString(1) == "ok"
    }

    fun close() {
        if (::connection.isInitialized.not()) return
        try { connection.close() } catch (_: Exception) {}
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :automation:test --tests "com.workflow.orchestrator.automation.service.TagHistoryServiceTest" -x verifyPluginStructure`
Expected: PASS — all 8 tests green.

- [ ] **Step 5: Commit**

```bash
git add automation/src/main/kotlin/com/workflow/orchestrator/automation/service/TagHistoryService.kt \
     automation/src/test/kotlin/com/workflow/orchestrator/automation/service/TagHistoryServiceTest.kt
git commit -m "feat(automation): add TagHistoryService with SQLite for queue persistence and run history"
```

---

## Chunk 5: Tag Builder & Detection Services

### Task 12: Create TagBuilderService — Baseline Scoring and Payload Construction

**Files:**
- Create: `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/TagBuilderService.kt`
- Create: `automation/src/test/kotlin/com/workflow/orchestrator/automation/service/TagBuilderServiceTest.kt`

- [ ] **Step 1: Write tests**

Create `automation/src/test/kotlin/com/workflow/orchestrator/automation/service/TagBuilderServiceTest.kt`:

```kotlin
package com.workflow.orchestrator.automation.service

import com.workflow.orchestrator.automation.model.*
import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.bamboo.api.dto.BambooResultDto
import com.workflow.orchestrator.bamboo.api.dto.BambooStageCollection
import com.workflow.orchestrator.bamboo.api.dto.BambooStageDto
import com.workflow.orchestrator.core.model.ApiResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class TagBuilderServiceTest {

    private lateinit var bambooClient: BambooApiClient
    private lateinit var service: TagBuilderService

    @BeforeEach
    fun setUp() {
        bambooClient = mockk()
        service = TagBuilderService(bambooClient)
    }

    @Test
    fun `scoreAndRankRuns scores runs by release tags and stage results`() = runTest {
        val runs = listOf(
            makeBuildResult(847, "Successful", listOf("Successful", "Successful", "Successful")),
            makeBuildResult(848, "Failed", listOf("Successful", "Failed")),
            makeBuildResult(846, "Successful", listOf("Successful", "Successful"))
        )
        coEvery { bambooClient.getRecentResults("PROJ-AUTO", 10) } returns ApiResult.Success(runs)
        coEvery { bambooClient.getBuildVariables("PROJ-AUTO-847") } returns ApiResult.Success(
            mapOf("dockerTagsAsJson" to """{"auth":"2.4.0","payments":"2.3.1","user":"1.9.0"}""")
        )
        coEvery { bambooClient.getBuildVariables("PROJ-AUTO-848") } returns ApiResult.Success(
            mapOf("dockerTagsAsJson" to """{"auth":"2.4.0","payments":"feature-abc"}""")
        )
        coEvery { bambooClient.getBuildVariables("PROJ-AUTO-846") } returns ApiResult.Success(
            mapOf("dockerTagsAsJson" to """{"auth":"2.3.0","payments":"2.3.1"}""")
        )

        val ranked = service.scoreAndRankRuns("PROJ-AUTO")

        assertTrue(ranked.isNotEmpty())
        // Run 847: 3 release tags * 10 + 3 success * 5 - 0 fail * 20 = 45
        // Run 846: 2 release tags * 10 + 2 success * 5 - 0 fail * 20 = 30
        // Run 848: 1 release tag * 10 + 1 success * 5 - 1 fail * 20 = -5
        assertEquals(847, ranked[0].buildNumber)
        assertTrue(ranked[0].score > ranked[1].score)
    }

    @Test
    fun `loadBaseline returns tag entries from best-scored run`() = runTest {
        val runs = listOf(
            makeBuildResult(847, "Successful", listOf("Successful", "Successful"))
        )
        coEvery { bambooClient.getRecentResults("PROJ-AUTO", 10) } returns ApiResult.Success(runs)
        coEvery { bambooClient.getBuildVariables("PROJ-AUTO-847") } returns ApiResult.Success(
            mapOf("dockerTagsAsJson" to """{"auth":"2.4.0","payments":"2.3.1"}""")
        )

        val entries = service.loadBaseline("PROJ-AUTO")

        assertEquals(2, entries.size)
        assertEquals("auth", entries[0].serviceName)
        assertEquals("2.4.0", entries[0].currentTag)
        assertEquals(TagSource.BASELINE, entries[0].source)
        assertEquals(RegistryStatus.UNKNOWN, entries[0].registryStatus)
    }

    @Test
    fun `replaceCurrentRepoTag swaps tag for matching service`() {
        val entries = listOf(
            TagEntry("auth", "2.4.0", null, TagSource.BASELINE, RegistryStatus.UNKNOWN, false, false),
            TagEntry("payments", "2.3.1", null, TagSource.BASELINE, RegistryStatus.UNKNOWN, false, false)
        )
        val context = CurrentRepoContext(
            serviceName = "auth",
            branchName = "feature/PROJ-123",
            featureBranchTag = "feature-PROJ-123-a1b2c3d",
            detectedFrom = DetectionSource.PROJECT_NAME
        )

        val result = service.replaceCurrentRepoTag(entries, context)

        val authEntry = result.find { it.serviceName == "auth" }!!
        assertEquals("feature-PROJ-123-a1b2c3d", authEntry.currentTag)
        assertEquals(TagSource.AUTO_DETECTED, authEntry.source)
        assertTrue(authEntry.isCurrentRepo)

        val paymentsEntry = result.find { it.serviceName == "payments" }!!
        assertFalse(paymentsEntry.isCurrentRepo)
    }

    @Test
    fun `replaceCurrentRepoTag does nothing when service not found`() {
        val entries = listOf(
            TagEntry("auth", "2.4.0", null, TagSource.BASELINE, RegistryStatus.UNKNOWN, false, false)
        )
        val context = CurrentRepoContext("unknown-service", "main", "tag", DetectionSource.PROJECT_NAME)

        val result = service.replaceCurrentRepoTag(entries, context)

        assertFalse(result.any { it.isCurrentRepo })
    }

    @Test
    fun `buildJsonPayload produces valid JSON`() {
        val entries = listOf(
            TagEntry("auth", "2.4.0", null, TagSource.BASELINE, RegistryStatus.VALID, false, false),
            TagEntry("payments", "2.3.1", null, TagSource.BASELINE, RegistryStatus.VALID, false, false)
        )

        val payload = service.buildJsonPayload(entries)

        assertTrue(payload.contains("\"auth\""))
        assertTrue(payload.contains("\"2.4.0\""))
        assertTrue(payload.contains("\"payments\""))
        assertTrue(payload.contains("\"2.3.1\""))
    }

    @Test
    fun `buildTriggerVariables combines tags and extra vars`() {
        val entries = listOf(
            TagEntry("auth", "2.4.0", null, TagSource.BASELINE, RegistryStatus.VALID, false, false)
        )
        val extraVars = mapOf("suiteType" to "regression", "featureFlag" to "true")

        val vars = service.buildTriggerVariables(entries, extraVars)

        assertTrue(vars.containsKey("dockerTagsAsJson"))
        assertEquals("regression", vars["suiteType"])
        assertEquals("true", vars["featureFlag"])
    }

    @Test
    fun `loadBaseline handles empty results gracefully`() = runTest {
        coEvery { bambooClient.getRecentResults("PROJ-AUTO", 10) } returns ApiResult.Success(emptyList())

        val entries = service.loadBaseline("PROJ-AUTO")

        assertTrue(entries.isEmpty())
    }

    @Test
    fun `loadBaseline handles API error gracefully`() = runTest {
        coEvery { bambooClient.getRecentResults("PROJ-AUTO", 10) } returns
            ApiResult.Error(com.workflow.orchestrator.core.model.ErrorType.NETWORK_ERROR, "timeout")

        val entries = service.loadBaseline("PROJ-AUTO")

        assertTrue(entries.isEmpty())
    }

    private fun makeBuildResult(
        buildNumber: Int,
        state: String,
        stageStates: List<String>
    ): BambooResultDto {
        val stages = stageStates.mapIndexed { i, s ->
            BambooStageDto(
                name = "Stage-$i",
                state = s,
                lifeCycleState = "Finished",
                manual = false,
                buildDurationInSeconds = 300
            )
        }
        return BambooResultDto(
            key = "PROJ-AUTO-$buildNumber",
            buildNumber = buildNumber,
            state = state,
            lifeCycleState = "Finished",
            buildDurationInSeconds = 700,
            buildRelativeTime = "5 min ago",
            stages = BambooStageCollection(size = stages.size, stage = stages)
        )
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :automation:test --tests "com.workflow.orchestrator.automation.service.TagBuilderServiceTest" -x verifyPluginStructure`
Expected: FAIL — `TagBuilderService` not found.

- [ ] **Step 3: Implement TagBuilderService**

Create `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/TagBuilderService.kt`:

```kotlin
package com.workflow.orchestrator.automation.service

import com.workflow.orchestrator.automation.model.*
import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.core.model.ApiResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

class TagBuilderService(
    private val bambooClient: BambooApiClient
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val semverPattern = Regex("""^\d+\.\d+\.\d+.*$""")

    /**
     * Fetches last N builds, scores each, returns ranked list (best first).
     * Score = (releaseTagCount * 10) + (successfulStages * 5) - (failedStages * 20)
     */
    suspend fun scoreAndRankRuns(
        suitePlanKey: String,
        maxResults: Int = 10
    ): List<BaselineRun> {
        val buildsResult = bambooClient.getRecentResults(suitePlanKey, maxResults)
        if (buildsResult !is ApiResult.Success) return emptyList()

        return buildsResult.data.mapNotNull { dto ->
            val varsResult = bambooClient.getBuildVariables(dto.key)
            if (varsResult !is ApiResult.Success) return@mapNotNull null

            val dockerTagsJson = varsResult.data["dockerTagsAsJson"] ?: return@mapNotNull null
            val tags = parseDockerTagsJson(dockerTagsJson)
            if (tags.isEmpty()) return@mapNotNull null

            val releaseCount = tags.values.count { semverPattern.matches(it) }
            val successStages = dto.stages.stage.count { it.state == "Successful" }
            val failedStages = dto.stages.stage.count { it.state == "Failed" }
            val score = (releaseCount * 10) + (successStages * 5) - (failedStages * 20)

            BaselineRun(
                buildNumber = dto.buildNumber,
                resultKey = dto.key,
                dockerTags = tags,
                releaseTagCount = releaseCount,
                totalServices = tags.size,
                successfulStages = successStages,
                failedStages = failedStages,
                triggeredAt = java.time.Instant.now(), // Bamboo doesn't give exact timestamp in list
                score = score
            )
        }.sortedByDescending { it.score }
    }

    /**
     * Loads tag entries from the best-scored run for the given suite.
     */
    suspend fun loadBaseline(suitePlanKey: String): List<TagEntry> {
        val ranked = scoreAndRankRuns(suitePlanKey)
        if (ranked.isEmpty()) return emptyList()

        val best = ranked[0]
        return best.dockerTags.map { (service, tag) ->
            TagEntry(
                serviceName = service,
                currentTag = tag,
                latestReleaseTag = null,
                source = TagSource.BASELINE,
                registryStatus = RegistryStatus.UNKNOWN,
                isDrift = false,
                isCurrentRepo = false
            )
        }
    }

    /**
     * Replaces the tag for the current repo's service with the feature branch tag.
     */
    fun replaceCurrentRepoTag(
        entries: List<TagEntry>,
        context: CurrentRepoContext
    ): List<TagEntry> {
        if (context.featureBranchTag == null) return entries
        return entries.map { entry ->
            if (entry.serviceName == context.serviceName) {
                entry.copy(
                    currentTag = context.featureBranchTag,
                    source = TagSource.AUTO_DETECTED,
                    isCurrentRepo = true
                )
            } else {
                entry
            }
        }
    }

    /**
     * Builds the dockerTagsAsJson JSON string from tag entries.
     */
    fun buildJsonPayload(entries: List<TagEntry>): String {
        val map = entries.associate { it.serviceName to JsonPrimitive(it.currentTag) }
        return JsonObject(map).toString()
    }

    /**
     * Combines dockerTagsAsJson with extra variables for the trigger call.
     */
    fun buildTriggerVariables(
        entries: List<TagEntry>,
        extraVars: Map<String, String>
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        result["dockerTagsAsJson"] = buildJsonPayload(entries)
        result.putAll(extraVars)
        return result
    }

    private fun parseDockerTagsJson(jsonStr: String): Map<String, String> {
        return try {
            val obj = json.decodeFromString<JsonObject>(jsonStr)
            obj.entries.associate { (k, v) -> k to v.jsonPrimitive.content }
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :automation:test --tests "com.workflow.orchestrator.automation.service.TagBuilderServiceTest" -x verifyPluginStructure`
Expected: PASS — all 8 tests green.

- [ ] **Step 5: Commit**

```bash
git add automation/src/main/kotlin/com/workflow/orchestrator/automation/service/TagBuilderService.kt \
     automation/src/test/kotlin/com/workflow/orchestrator/automation/service/TagBuilderServiceTest.kt
git commit -m "feat(automation): add TagBuilderService with baseline scoring and payload construction"
```

---

### Task 13: Create DriftDetectorService

**Files:**
- Create: `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/DriftDetectorService.kt`
- Create: `automation/src/test/kotlin/com/workflow/orchestrator/automation/service/DriftDetectorServiceTest.kt`

- [ ] **Step 1: Write tests**

Create `automation/src/test/kotlin/com/workflow/orchestrator/automation/service/DriftDetectorServiceTest.kt`:

```kotlin
package com.workflow.orchestrator.automation.service

import com.workflow.orchestrator.automation.api.DockerRegistryClient
import com.workflow.orchestrator.automation.model.*
import com.workflow.orchestrator.core.model.ApiResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DriftDetectorServiceTest {

    private lateinit var registryClient: DockerRegistryClient
    private lateinit var service: DriftDetectorService

    @BeforeEach
    fun setUp() {
        registryClient = mockk()
        service = DriftDetectorService(registryClient)
    }

    @Test
    fun `checkDrift detects stale release tags`() = runTest {
        val entries = listOf(
            TagEntry("auth", "2.3.1", null, TagSource.BASELINE, RegistryStatus.VALID, false, false),
            TagEntry("payments", "2.4.0", null, TagSource.BASELINE, RegistryStatus.VALID, false, false)
        )
        coEvery { registryClient.getLatestReleaseTag("auth") } returns ApiResult.Success("2.4.0")
        coEvery { registryClient.getLatestReleaseTag("payments") } returns ApiResult.Success("2.4.0")

        val results = service.checkDrift(entries)

        assertEquals(2, results.size)
        val authDrift = results.find { it.serviceName == "auth" }!!
        assertTrue(authDrift.isStale)
        assertEquals("2.4.0", authDrift.latestReleaseTag)

        val paymentsDrift = results.find { it.serviceName == "payments" }!!
        assertFalse(paymentsDrift.isStale)
    }

    @Test
    fun `checkDrift skips feature branch tags`() = runTest {
        val entries = listOf(
            TagEntry("auth", "feature-PROJ-123-abc", null, TagSource.AUTO_DETECTED, RegistryStatus.VALID, false, true)
        )

        val results = service.checkDrift(entries)

        assertTrue(results.isEmpty()) // Feature branch tags not checked for drift
    }

    @Test
    fun `checkDrift handles registry errors gracefully`() = runTest {
        val entries = listOf(
            TagEntry("auth", "2.3.1", null, TagSource.BASELINE, RegistryStatus.VALID, false, false)
        )
        coEvery { registryClient.getLatestReleaseTag("auth") } returns
            ApiResult.Error(com.workflow.orchestrator.core.model.ErrorType.NETWORK_ERROR, "timeout")

        val results = service.checkDrift(entries)

        assertTrue(results.isEmpty())
    }

    @Test
    fun `checkDrift handles null latest release tag`() = runTest {
        val entries = listOf(
            TagEntry("auth", "2.3.1", null, TagSource.BASELINE, RegistryStatus.VALID, false, false)
        )
        coEvery { registryClient.getLatestReleaseTag("auth") } returns ApiResult.Success(null)

        val results = service.checkDrift(entries)

        assertTrue(results.isEmpty())
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :automation:test --tests "com.workflow.orchestrator.automation.service.DriftDetectorServiceTest" -x verifyPluginStructure`
Expected: FAIL

- [ ] **Step 3: Implement DriftDetectorService**

Create `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/DriftDetectorService.kt`:

```kotlin
package com.workflow.orchestrator.automation.service

import com.workflow.orchestrator.automation.api.DockerRegistryClient
import com.workflow.orchestrator.automation.model.DriftResult
import com.workflow.orchestrator.automation.model.TagEntry
import com.workflow.orchestrator.core.model.ApiResult

class DriftDetectorService(
    private val registryClient: DockerRegistryClient
) {
    private val semverPattern = Regex("""^\d+\.\d+\.\d+.*$""")

    /**
     * Checks whether staged tags are stale compared to the latest release in Docker Registry.
     * Only checks release tags (semver), not feature branch tags.
     */
    suspend fun checkDrift(entries: List<TagEntry>): List<DriftResult> {
        return entries
            .filter { semverPattern.matches(it.currentTag) && !it.isCurrentRepo }
            .mapNotNull { entry ->
                val result = registryClient.getLatestReleaseTag(entry.serviceName)
                if (result is ApiResult.Success && result.data != null) {
                    DriftResult(
                        serviceName = entry.serviceName,
                        currentTag = entry.currentTag,
                        latestReleaseTag = result.data,
                        isStale = entry.currentTag != result.data
                    )
                } else {
                    null
                }
            }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :automation:test --tests "com.workflow.orchestrator.automation.service.DriftDetectorServiceTest" -x verifyPluginStructure`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add automation/src/main/kotlin/com/workflow/orchestrator/automation/service/DriftDetectorService.kt \
     automation/src/test/kotlin/com/workflow/orchestrator/automation/service/DriftDetectorServiceTest.kt
git commit -m "feat(automation): add DriftDetectorService for tag staleness detection"
```

---

### Task 14: Create ConflictDetectorService

**Files:**
- Create: `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/ConflictDetectorService.kt`
- Create: `automation/src/test/kotlin/com/workflow/orchestrator/automation/service/ConflictDetectorServiceTest.kt`

- [ ] **Step 1: Write tests**

Create `automation/src/test/kotlin/com/workflow/orchestrator/automation/service/ConflictDetectorServiceTest.kt`:

```kotlin
package com.workflow.orchestrator.automation.service

import com.workflow.orchestrator.automation.model.Conflict
import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.bamboo.api.dto.BambooResultDto
import com.workflow.orchestrator.bamboo.api.dto.BambooStageCollection
import com.workflow.orchestrator.core.model.ApiResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ConflictDetectorServiceTest {

    private lateinit var bambooClient: BambooApiClient
    private lateinit var service: ConflictDetectorService

    @BeforeEach
    fun setUp() {
        bambooClient = mockk()
        service = ConflictDetectorService(bambooClient)
    }

    @Test
    fun `checkConflicts detects overlapping services`() = runTest {
        val runningBuild = BambooResultDto(
            key = "PROJ-AUTO-849",
            buildNumber = 849,
            state = "Unknown",
            lifeCycleState = "InProgress",
            stages = BambooStageCollection()
        )
        coEvery { bambooClient.getRunningAndQueuedBuilds("PROJ-AUTO") } returns
            ApiResult.Success(listOf(runningBuild))
        coEvery { bambooClient.getBuildVariables("PROJ-AUTO-849") } returns
            ApiResult.Success(mapOf(
                "dockerTagsAsJson" to """{"auth":"2.4.0","payments":"2.3.1"}"""
            ))

        val stagedTags = mapOf("auth" to "feature-abc", "user" to "1.9.0")
        val conflicts = service.checkConflicts("PROJ-AUTO", stagedTags)

        assertEquals(1, conflicts.size)
        assertEquals("auth", conflicts[0].serviceName)
        assertEquals("feature-abc", conflicts[0].yourTag)
        assertEquals("2.4.0", conflicts[0].otherTag)
        assertTrue(conflicts[0].isRunning)
    }

    @Test
    fun `checkConflicts returns empty when no running builds`() = runTest {
        coEvery { bambooClient.getRunningAndQueuedBuilds("PROJ-AUTO") } returns
            ApiResult.Success(emptyList())

        val conflicts = service.checkConflicts("PROJ-AUTO", mapOf("auth" to "2.4.0"))

        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun `checkConflicts returns empty when no overlap`() = runTest {
        val runningBuild = BambooResultDto(
            key = "PROJ-AUTO-849",
            buildNumber = 849,
            state = "Unknown",
            lifeCycleState = "InProgress",
            stages = BambooStageCollection()
        )
        coEvery { bambooClient.getRunningAndQueuedBuilds("PROJ-AUTO") } returns
            ApiResult.Success(listOf(runningBuild))
        coEvery { bambooClient.getBuildVariables("PROJ-AUTO-849") } returns
            ApiResult.Success(mapOf("dockerTagsAsJson" to """{"user":"1.9.0"}"""))

        val conflicts = service.checkConflicts("PROJ-AUTO", mapOf("auth" to "2.4.0"))

        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun `checkConflicts handles API error gracefully`() = runTest {
        coEvery { bambooClient.getRunningAndQueuedBuilds("PROJ-AUTO") } returns
            ApiResult.Error(com.workflow.orchestrator.core.model.ErrorType.NETWORK_ERROR, "timeout")

        val conflicts = service.checkConflicts("PROJ-AUTO", mapOf("auth" to "2.4.0"))

        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun `checkConflicts handles malformed dockerTagsAsJson`() = runTest {
        val runningBuild = BambooResultDto(
            key = "PROJ-AUTO-849",
            buildNumber = 849,
            state = "Unknown",
            lifeCycleState = "InProgress",
            stages = BambooStageCollection()
        )
        coEvery { bambooClient.getRunningAndQueuedBuilds("PROJ-AUTO") } returns
            ApiResult.Success(listOf(runningBuild))
        coEvery { bambooClient.getBuildVariables("PROJ-AUTO-849") } returns
            ApiResult.Success(mapOf("dockerTagsAsJson" to "not-valid-json"))

        val conflicts = service.checkConflicts("PROJ-AUTO", mapOf("auth" to "2.4.0"))

        assertTrue(conflicts.isEmpty())
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :automation:test --tests "com.workflow.orchestrator.automation.service.ConflictDetectorServiceTest" -x verifyPluginStructure`
Expected: FAIL

- [ ] **Step 3: Implement ConflictDetectorService**

Create `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/ConflictDetectorService.kt`:

```kotlin
package com.workflow.orchestrator.automation.service

import com.workflow.orchestrator.automation.model.Conflict
import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.core.model.ApiResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class ConflictDetectorService(
    private val bambooClient: BambooApiClient
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Detects when running/queued builds target overlapping services.
     * Returns warn-only conflicts — user can still trigger.
     */
    suspend fun checkConflicts(
        suitePlanKey: String,
        stagedTags: Map<String, String>
    ): List<Conflict> {
        val buildsResult = bambooClient.getRunningAndQueuedBuilds(suitePlanKey)
        if (buildsResult !is ApiResult.Success) return emptyList()

        val conflicts = mutableListOf<Conflict>()

        for (build in buildsResult.data) {
            val varsResult = bambooClient.getBuildVariables(build.key)
            if (varsResult !is ApiResult.Success) continue

            val dockerTagsJson = varsResult.data["dockerTagsAsJson"] ?: continue
            val otherTags = parseDockerTagsJson(dockerTagsJson)

            for ((service, yourTag) in stagedTags) {
                val otherTag = otherTags[service]
                if (otherTag != null) {
                    conflicts.add(
                        Conflict(
                            serviceName = service,
                            yourTag = yourTag,
                            otherTag = otherTag,
                            triggeredBy = varsResult.data["triggerUser"] ?: "unknown",
                            buildNumber = build.buildNumber,
                            isRunning = build.lifeCycleState == "InProgress"
                        )
                    )
                }
            }
        }

        return conflicts
    }

    private fun parseDockerTagsJson(jsonStr: String): Map<String, String> {
        return try {
            val obj = json.decodeFromString<JsonObject>(jsonStr)
            obj.entries.associate { (k, v) -> k to v.jsonPrimitive.content }
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :automation:test --tests "com.workflow.orchestrator.automation.service.ConflictDetectorServiceTest" -x verifyPluginStructure`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add automation/src/main/kotlin/com/workflow/orchestrator/automation/service/ConflictDetectorService.kt \
     automation/src/test/kotlin/com/workflow/orchestrator/automation/service/ConflictDetectorServiceTest.kt
git commit -m "feat(automation): add ConflictDetectorService for running build overlap detection"
```

---

## Chunk 6: Queue Service & Recovery

### Task 15: Create QueueService — Core Enqueue, Cancel, and State Management

**Files:**
- Create: `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/QueueService.kt`
- Create: `automation/src/test/kotlin/com/workflow/orchestrator/automation/service/QueueServiceTest.kt`

> **Pattern reference:** `BuildMonitorService` — dual constructor (project + test), `CoroutineScope(Dispatchers.IO + SupervisorJob())`, `MutableStateFlow`, polling with `delay()`, `Disposable`.

- [ ] **Step 1: Write tests for enqueue, cancel, and state flow**

Create `automation/src/test/kotlin/com/workflow/orchestrator/automation/service/QueueServiceTest.kt`:

```kotlin
package com.workflow.orchestrator.automation.service

import app.cash.turbine.test
import com.workflow.orchestrator.automation.api.DockerRegistryClient
import com.workflow.orchestrator.automation.model.QueueEntry
import com.workflow.orchestrator.automation.model.QueueEntryStatus
import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.bamboo.api.dto.BambooQueueResponse
import com.workflow.orchestrator.bamboo.api.dto.BambooResultDto
import com.workflow.orchestrator.bamboo.api.dto.BambooStageCollection
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.model.ApiResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class QueueServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var bambooClient: BambooApiClient
    private lateinit var registryClient: DockerRegistryClient
    private lateinit var eventBus: EventBus
    private lateinit var tagHistory: TagHistoryService
    private lateinit var testScope: TestScope
    private lateinit var service: QueueService

    @BeforeEach
    fun setUp() {
        bambooClient = mockk(relaxed = true)
        registryClient = mockk(relaxed = true)
        eventBus = EventBus()
        tagHistory = TagHistoryService(tempDir.resolve("test.db").toString())
        testScope = TestScope(StandardTestDispatcher())

        service = QueueService(
            bambooClient = bambooClient,
            registryClient = registryClient,
            eventBus = eventBus,
            tagHistoryService = tagHistory,
            scope = testScope,
            autoTriggerEnabled = true,
            maxDepthPerSuite = 10,
            tagValidationOnTrigger = true
        )
    }

    @AfterEach
    fun tearDown() {
        tagHistory.close()
        testScope.cancel()
    }

    private fun makeEntry(
        id: String = "q-1",
        planKey: String = "PROJ-AUTO",
        status: QueueEntryStatus = QueueEntryStatus.WAITING_LOCAL
    ) = QueueEntry(
        id = id,
        suitePlanKey = planKey,
        dockerTagsPayload = """{"auth":"2.4.0"}""",
        variables = mapOf("suiteType" to "regression"),
        stages = listOf("QA Automation"),
        enqueuedAt = Instant.now(),
        status = status,
        bambooResultKey = null
    )

    @Test
    fun `enqueue adds entry to state flow`() = testScope.runTest {
        val entry = makeEntry()

        service.enqueue(entry)

        val entries = service.stateFlow.value
        assertEquals(1, entries.size)
        assertEquals("q-1", entries[0].id)
        assertEquals(QueueEntryStatus.WAITING_LOCAL, entries[0].status)
    }

    @Test
    fun `enqueue emits QueuePositionChanged event`() = testScope.runTest {
        eventBus.events.test {
            service.enqueue(makeEntry())

            val event = awaitItem()
            assertTrue(event is WorkflowEvent.QueuePositionChanged)
            val qpc = event as WorkflowEvent.QueuePositionChanged
            assertEquals(0, qpc.position)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `enqueue rejects when max depth exceeded`() = testScope.runTest {
        val smallService = QueueService(
            bambooClient = bambooClient,
            registryClient = registryClient,
            eventBus = eventBus,
            tagHistoryService = tagHistory,
            scope = testScope,
            autoTriggerEnabled = false,
            maxDepthPerSuite = 2,
            tagValidationOnTrigger = false
        )

        smallService.enqueue(makeEntry(id = "q-1"))
        smallService.enqueue(makeEntry(id = "q-2"))
        smallService.enqueue(makeEntry(id = "q-3"))

        // Only 2 should be accepted
        assertEquals(2, smallService.stateFlow.value.size)
    }

    @Test
    fun `cancel removes entry from state`() = testScope.runTest {
        service.enqueue(makeEntry())

        service.cancel("q-1")

        assertTrue(service.stateFlow.value.isEmpty())
    }

    @Test
    fun `cancel calls cancelBuild when entry has bambooResultKey`() = testScope.runTest {
        val entry = makeEntry().copy(
            status = QueueEntryStatus.QUEUED_ON_BAMBOO,
            bambooResultKey = "PROJ-AUTO-849"
        )
        service.enqueue(entry)

        coEvery { bambooClient.cancelBuild("PROJ-AUTO-849") } returns ApiResult.Success(Unit)

        service.cancel("q-1")

        coVerify { bambooClient.cancelBuild("PROJ-AUTO-849") }
    }

    @Test
    fun `getActiveEntries returns only non-terminal entries`() = testScope.runTest {
        service.enqueue(makeEntry(id = "q-1"))
        service.enqueue(makeEntry(id = "q-2"))

        service.cancel("q-1")

        val active = service.getActiveEntries()
        assertEquals(1, active.size)
        assertEquals("q-2", active[0].id)
    }

    @Test
    fun `getQueuePositionForSuite returns correct position`() = testScope.runTest {
        service.enqueue(makeEntry(id = "q-1", planKey = "PROJ-AUTO"))
        service.enqueue(makeEntry(id = "q-2", planKey = "PROJ-AUTO"))

        assertEquals(0, service.getQueuePositionForSuite("PROJ-AUTO", "q-1"))
        assertEquals(1, service.getQueuePositionForSuite("PROJ-AUTO", "q-2"))
    }

    @Test
    fun `triggerNow bypasses queue and triggers immediately`() = testScope.runTest {
        val entry = makeEntry()
        coEvery { registryClient.tagExists(any(), any()) } returns ApiResult.Success(true)
        coEvery { bambooClient.triggerBuild(any(), any(), any()) } returns ApiResult.Success(
            BambooQueueResponse(
                buildResultKey = "PROJ-AUTO-850",
                buildNumber = 850,
                planKey = "PROJ-AUTO"
            )
        )

        val result = service.triggerNow(entry)

        assertTrue(result is ApiResult.Success)
        assertEquals("PROJ-AUTO-850", (result as ApiResult.Success).data)
        coVerify { bambooClient.triggerBuild("PROJ-AUTO", any(), any()) }
    }

    @Test
    fun `payload snapshot is immutable after enqueue`() = testScope.runTest {
        val entry = makeEntry()
        service.enqueue(entry)

        // Even if original was modified, queued entry retains snapshot
        val queued = service.stateFlow.value[0]
        assertEquals("""{"auth":"2.4.0"}""", queued.dockerTagsPayload)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :automation:test --tests "com.workflow.orchestrator.automation.service.QueueServiceTest" -x verifyPluginStructure`
Expected: FAIL — `QueueService` not found.

- [ ] **Step 3: Implement QueueService**

Create `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/QueueService.kt`:

```kotlin
package com.workflow.orchestrator.automation.service

import com.intellij.openapi.Disposable
import com.workflow.orchestrator.automation.api.DockerRegistryClient
import com.workflow.orchestrator.automation.model.QueueEntry
import com.workflow.orchestrator.automation.model.QueueEntryStatus
import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.model.ApiResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class QueueService : Disposable {

    private val bambooClient: BambooApiClient
    private val registryClient: DockerRegistryClient
    private val eventBus: EventBus
    private val tagHistoryService: TagHistoryService
    private val scope: CoroutineScope
    private val autoTriggerEnabled: Boolean
    private val maxDepthPerSuite: Int
    private val tagValidationOnTrigger: Boolean

    /** Test constructor — allows injecting mocks. */
    constructor(
        bambooClient: BambooApiClient,
        registryClient: DockerRegistryClient,
        eventBus: EventBus,
        tagHistoryService: TagHistoryService,
        scope: CoroutineScope,
        autoTriggerEnabled: Boolean = true,
        maxDepthPerSuite: Int = 10,
        tagValidationOnTrigger: Boolean = true
    ) {
        this.bambooClient = bambooClient
        this.registryClient = registryClient
        this.eventBus = eventBus
        this.tagHistoryService = tagHistoryService
        this.scope = scope
        this.autoTriggerEnabled = autoTriggerEnabled
        this.maxDepthPerSuite = maxDepthPerSuite
        this.tagValidationOnTrigger = tagValidationOnTrigger
    }

    private val _stateFlow = MutableStateFlow<List<QueueEntry>>(emptyList())
    val stateFlow: StateFlow<List<QueueEntry>> = _stateFlow.asStateFlow()

    private val mutex = Mutex()
    private val sequenceCounter = AtomicInteger(0)
    private val pollInProgress = AtomicBoolean(false)
    private var pollingJob: Job? = null

    fun enqueue(entry: QueueEntry) {
        scope.launch {
            mutex.withLock {
                val suiteEntries = _stateFlow.value.count { it.suitePlanKey == entry.suitePlanKey }
                if (suiteEntries >= maxDepthPerSuite) return@launch

                val seq = sequenceCounter.incrementAndGet()
                tagHistoryService.saveQueueEntry(entry, seq)

                _stateFlow.value = _stateFlow.value + entry

                val position = _stateFlow.value
                    .filter { it.suitePlanKey == entry.suitePlanKey }
                    .indexOfFirst { it.id == entry.id }

                eventBus.emit(WorkflowEvent.QueuePositionChanged(
                    suitePlanKey = entry.suitePlanKey,
                    position = position,
                    estimatedWaitMs = null
                ))
            }

            if (autoTriggerEnabled) {
                startPollingIfNeeded()
            }
        }
    }

    fun cancel(entryId: String) {
        scope.launch {
            mutex.withLock {
                val entry = _stateFlow.value.find { it.id == entryId } ?: return@launch

                // If queued on Bamboo, cancel the build
                if (entry.bambooResultKey != null &&
                    entry.status in listOf(QueueEntryStatus.QUEUED_ON_BAMBOO)) {
                    bambooClient.cancelBuild(entry.bambooResultKey!!)
                }

                tagHistoryService.updateQueueEntryStatus(entryId, QueueEntryStatus.CANCELLED)
                _stateFlow.value = _stateFlow.value.filter { it.id != entryId }

                eventBus.emit(WorkflowEvent.QueuePositionChanged(
                    suitePlanKey = entry.suitePlanKey,
                    position = -1,
                    estimatedWaitMs = null
                ))
            }
        }
    }

    fun getActiveEntries(): List<QueueEntry> = _stateFlow.value

    fun getQueuePositionForSuite(suitePlanKey: String, entryId: String): Int {
        return _stateFlow.value
            .filter { it.suitePlanKey == suitePlanKey }
            .indexOfFirst { it.id == entryId }
    }

    /**
     * Triggers a build immediately (bypassing auto-trigger queue).
     * Called by "Trigger Now" button.
     */
    suspend fun triggerNow(entry: QueueEntry): ApiResult<String> {
        return mutex.withLock {
            doTrigger(entry)
        }
    }

    // --- Polling & Auto-Trigger ---

    private fun startPollingIfNeeded() {
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch {
            while (true) {
                if (pollInProgress.compareAndSet(false, true)) {
                    try {
                        pollOnce()
                    } finally {
                        pollInProgress.set(false)
                    }
                }
                val hasActive = _stateFlow.value.any {
                    it.status in listOf(QueueEntryStatus.RUNNING, QueueEntryStatus.QUEUED_ON_BAMBOO)
                }
                delay(if (hasActive) 15_000L else 60_000L)

                // Stop polling when no entries remain
                if (_stateFlow.value.isEmpty()) break
            }
            pollingJob = null
        }
    }

    internal suspend fun pollOnce() {
        mutex.withLock {
            val entries = _stateFlow.value.toList()
            val updatedEntries = mutableListOf<QueueEntry>()

            // Group by suite to process FIFO per suite
            val bySuite = entries.groupBy { it.suitePlanKey }

            for ((planKey, suiteEntries) in bySuite) {
                for (entry in suiteEntries) {
                    val updated = when (entry.status) {
                        QueueEntryStatus.WAITING_LOCAL -> handleWaitingLocal(planKey, entry)
                        QueueEntryStatus.QUEUED_ON_BAMBOO,
                        QueueEntryStatus.RUNNING -> handleRunningOrQueued(entry)
                        else -> entry
                    }
                    updatedEntries.add(updated)
                }
            }

            _stateFlow.value = updatedEntries
        }
    }

    private suspend fun handleWaitingLocal(planKey: String, entry: QueueEntry): QueueEntry {
        // Only trigger the oldest WAITING_LOCAL entry per suite
        val oldestWaiting = _stateFlow.value
            .filter { it.suitePlanKey == planKey && it.status == QueueEntryStatus.WAITING_LOCAL }
            .firstOrNull()

        if (oldestWaiting?.id != entry.id) return entry

        // Check if suite is idle
        val runningResult = bambooClient.getRunningAndQueuedBuilds(planKey)
        if (runningResult is ApiResult.Success && runningResult.data.isEmpty()) {
            // Suite is idle — trigger
            val triggerResult = doTrigger(entry)
            return if (triggerResult is ApiResult.Success) {
                entry.copy(
                    status = QueueEntryStatus.QUEUED_ON_BAMBOO,
                    bambooResultKey = triggerResult.data
                )
            } else {
                entry.copy(status = QueueEntryStatus.FAILED_TO_TRIGGER)
            }
        }

        return entry // Suite busy, keep waiting
    }

    private suspend fun handleRunningOrQueued(entry: QueueEntry): QueueEntry {
        val resultKey = entry.bambooResultKey ?: return entry
        val planKey = resultKey.substringBeforeLast("-")

        // Check build status
        val result = bambooClient.getLatestResult(planKey, "")
        if (result !is ApiResult.Success) return entry

        val dto = result.data
        return when (dto.lifeCycleState) {
            "Finished" -> {
                val passed = dto.state == "Successful"
                tagHistoryService.updateQueueEntryStatus(
                    entry.id, QueueEntryStatus.COMPLETED, resultKey
                )
                eventBus.emit(WorkflowEvent.AutomationFinished(
                    suitePlanKey = entry.suitePlanKey,
                    buildResultKey = resultKey,
                    passed = passed,
                    durationMs = dto.buildDurationInSeconds * 1000
                ))
                entry.copy(status = QueueEntryStatus.COMPLETED)
            }
            "InProgress" -> entry.copy(status = QueueEntryStatus.RUNNING)
            else -> entry
        }
    }

    private suspend fun doTrigger(entry: QueueEntry): ApiResult<String> {
        // Pre-trigger tag validation
        if (tagValidationOnTrigger) {
            val tagsValid = validateTags(entry)
            if (!tagsValid) {
                tagHistoryService.updateQueueEntryStatus(entry.id, QueueEntryStatus.TAG_INVALID)
                return ApiResult.Error(
                    com.workflow.orchestrator.core.model.ErrorType.VALIDATION_ERROR,
                    "One or more Docker tags no longer exist in the registry"
                )
            }
        }

        val variables = entry.variables.toMutableMap()
        variables["dockerTagsAsJson"] = entry.dockerTagsPayload

        val result = bambooClient.triggerBuild(entry.suitePlanKey, variables)
        return when (result) {
            is ApiResult.Success -> {
                val buildKey = result.data.buildResultKey
                tagHistoryService.updateQueueEntryStatus(
                    entry.id, QueueEntryStatus.QUEUED_ON_BAMBOO, buildKey
                )
                eventBus.emit(WorkflowEvent.AutomationTriggered(
                    suitePlanKey = entry.suitePlanKey,
                    buildResultKey = buildKey,
                    dockerTagsJson = entry.dockerTagsPayload,
                    triggeredBy = if (autoTriggerEnabled) "auto-queue" else "manual"
                ))
                ApiResult.Success(buildKey)
            }
            is ApiResult.Error -> {
                tagHistoryService.updateQueueEntryStatus(
                    entry.id, QueueEntryStatus.FAILED_TO_TRIGGER,
                    errorMessage = result.message
                )
                ApiResult.Error(result.type, result.message, result.cause)
            }
        }
    }

    private suspend fun validateTags(entry: QueueEntry): Boolean {
        val tags = try {
            val obj = kotlinx.serialization.json.Json.decodeFromString<kotlinx.serialization.json.JsonObject>(entry.dockerTagsPayload)
            obj.entries.associate { (k, v) -> k to v.jsonPrimitive.content }
        } catch (e: Exception) {
            return false
        }

        for ((service, tag) in tags) {
            val result = registryClient.tagExists(service, tag)
            if (result is ApiResult.Success && !result.data) return false
            if (result is ApiResult.Error) return false
        }
        return true
    }

    /**
     * Restores queue state from SQLite after IDE restart.
     * Called by QueueRecoveryStartupActivity.
     */
    fun restoreFromPersistence() {
        scope.launch {
            mutex.withLock {
                val persisted = tagHistoryService.getActiveQueueEntries()
                if (persisted.isNotEmpty()) {
                    _stateFlow.value = persisted
                    startPollingIfNeeded()
                }
            }
        }
    }

    override fun dispose() {
        pollingJob?.cancel()
        scope.cancel()
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :automation:test --tests "com.workflow.orchestrator.automation.service.QueueServiceTest" -x verifyPluginStructure`
Expected: PASS — all 7 tests green.

- [ ] **Step 5: Commit**

```bash
git add automation/src/main/kotlin/com/workflow/orchestrator/automation/service/QueueService.kt \
     automation/src/test/kotlin/com/workflow/orchestrator/automation/service/QueueServiceTest.kt
git commit -m "feat(automation): add QueueService with enqueue, cancel, polling, and auto-trigger"
```

---

### Task 16: Create QueueRecoveryStartupActivity

**Files:**
- Create: `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/QueueRecoveryStartupActivity.kt`

- [ ] **Step 1: Implement startup activity**

Create `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/QueueRecoveryStartupActivity.kt`:

```kotlin
package com.workflow.orchestrator.automation.service

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Runs on IDE startup to recover queue state from SQLite.
 * Reconciles persisted entries with Bamboo API to detect
 * builds that finished while the IDE was closed.
 *
 * Registered as postStartupActivity in plugin.xml.
 */
class QueueRecoveryStartupActivity : ProjectActivity {

    private val log = Logger.getInstance(QueueRecoveryStartupActivity::class.java)

    override suspend fun execute(project: Project) {
        try {
            val queueService = project.getService(QueueService::class.java)
            queueService?.restoreFromPersistence()
            log.info("Queue recovery completed for project: ${project.name}")
        } catch (e: Exception) {
            log.warn("Queue recovery failed: ${e.message}", e)
        }
    }
}
```

> **Note:** The `QueueService` is registered as a project service (see Task 33 plugin.xml). The startup activity simply delegates to `restoreFromPersistence()` which reads SQLite and reconciles with Bamboo.

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :automation:classes -x verifyPluginStructure`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add automation/src/main/kotlin/com/workflow/orchestrator/automation/service/QueueRecoveryStartupActivity.kt
git commit -m "feat(automation): add QueueRecoveryStartupActivity for IDE restart recovery"
```

---

## Chunk 7: UI Components

### Task 17: Create AutomationTabProvider

**Files:**
- Create: `automation/src/main/kotlin/com/workflow/orchestrator/automation/ui/AutomationTabProvider.kt`

- [ ] **Step 1: Implement AutomationTabProvider**

Create `automation/src/main/kotlin/com/workflow/orchestrator/automation/ui/AutomationTabProvider.kt`:

```kotlin
package com.workflow.orchestrator.automation.ui

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.toolwindow.EmptyStatePanel
import com.workflow.orchestrator.core.toolwindow.WorkflowTabProvider
import javax.swing.JComponent

class AutomationTabProvider : WorkflowTabProvider {

    override val tabId: String = TAB_ID
    override val tabTitle: String = TAB_TITLE
    override val order: Int = 3

    companion object {
        const val TAB_ID = "automation"
        const val TAB_TITLE = "Automation"
    }

    override fun createPanel(project: Project): JComponent {
        val settings = PluginSettings.getInstance(project)
        return if (!settings.state.bambooUrl.isNullOrBlank() &&
            settings.state.automationModuleEnabled) {
            AutomationPanel(project)
        } else {
            EmptyStatePanel(
                project,
                "No automation suites configured.\nConnect to Bamboo and configure suites in Settings to get started."
            )
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :automation:classes -x verifyPluginStructure`
Expected: BUILD SUCCESSFUL (AutomationPanel doesn't exist yet — we'll create it next, but the compilation should succeed because it's referenced as a constructor call, and Kotlin will check at link time)

> **Note:** If this fails because `AutomationPanel` doesn't exist yet, proceed to Task 18 first and come back to verify both together.

- [ ] **Step 3: Commit**

```bash
git add automation/src/main/kotlin/com/workflow/orchestrator/automation/ui/AutomationTabProvider.kt
git commit -m "feat(automation): add AutomationTabProvider for Workflow tool window"
```

---

### Task 18: Create AutomationPanel — Main Orchestrating Panel

**Files:**
- Create: `automation/src/main/kotlin/com/workflow/orchestrator/automation/ui/AutomationPanel.kt`

- [ ] **Step 1: Implement AutomationPanel**

Create `automation/src/main/kotlin/com/workflow/orchestrator/automation/ui/AutomationPanel.kt`:

```kotlin
package com.workflow.orchestrator.automation.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.automation.api.DockerRegistryClient
import com.workflow.orchestrator.automation.service.*
import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.PluginSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.awt.BorderLayout
import javax.swing.BoxLayout
import javax.swing.JPanel

/**
 * Main panel for the Automation tab. Orchestrates sub-panels:
 * - TagStagingPanel (service table with inline editing)
 * - SuiteConfigPanel (variables, stages, suite selector)
 * - QueueStatusPanel (live status, queue position, actions)
 */
class AutomationPanel(
    private val project: Project
) : JPanel(BorderLayout()), Disposable {

    private val settings = PluginSettings.getInstance(project)
    private val credentialStore = CredentialStore()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Services
    private val bambooClient: BambooApiClient by lazy {
        BambooApiClient(
            baseUrl = settings.state.bambooUrl.orEmpty().trimEnd('/'),
            tokenProvider = { credentialStore.getToken(ServiceType.BAMBOO) }
        )
    }

    private val registryClient: DockerRegistryClient by lazy {
        val registryUrl = settings.state.dockerRegistryUrl.takeUnless { it.isNullOrBlank() }
            ?: settings.state.nexusUrl.orEmpty()
        DockerRegistryClient(
            registryUrl = registryUrl.trimEnd('/'),
            tokenProvider = { credentialStore.getToken(ServiceType.NEXUS) }
        )
    }

    private val tagBuilderService by lazy { TagBuilderService(bambooClient) }
    private val driftDetectorService by lazy { DriftDetectorService(registryClient) }
    private val conflictDetectorService by lazy { ConflictDetectorService(bambooClient) }

    // Sub-panels
    private val tagStagingPanel: TagStagingPanel
    private val suiteConfigPanel: SuiteConfigPanel
    private val queueStatusPanel: QueueStatusPanel

    init {
        border = JBUI.Borders.empty(4)

        tagStagingPanel = TagStagingPanel(project)
        suiteConfigPanel = SuiteConfigPanel(project)
        queueStatusPanel = QueueStatusPanel(project)

        val contentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(queueStatusPanel)
            add(tagStagingPanel)
            add(suiteConfigPanel)
        }

        add(JBScrollPane(contentPanel), BorderLayout.CENTER)

        Disposer.register(this, tagStagingPanel)
        Disposer.register(this, suiteConfigPanel)
        Disposer.register(this, queueStatusPanel)
    }

    override fun dispose() {
        scope.cancel()
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :automation:classes -x verifyPluginStructure`
Expected: May fail if TagStagingPanel/SuiteConfigPanel/QueueStatusPanel don't exist yet. Proceed to next tasks.

- [ ] **Step 3: Commit** (after Tasks 19-21 complete)

Defer commit until sub-panels exist.

---

### Task 19: Create TagStagingPanel — JBTable with Inline Tag Editing

**Files:**
- Create: `automation/src/main/kotlin/com/workflow/orchestrator/automation/ui/TagStagingPanel.kt`

- [ ] **Step 1: Implement TagStagingPanel**

Create `automation/src/main/kotlin/com/workflow/orchestrator/automation/ui/TagStagingPanel.kt`:

```kotlin
package com.workflow.orchestrator.automation.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.automation.model.RegistryStatus
import com.workflow.orchestrator.automation.model.TagEntry
import com.workflow.orchestrator.automation.model.TagSource
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

class TagStagingPanel(
    private val project: Project
) : JPanel(BorderLayout()), Disposable {

    private val tableModel = TagTableModel()
    private val table = JBTable(tableModel)

    init {
        border = JBUI.Borders.emptyTop(4)

        table.apply {
            setShowGrid(false)
            intercellSpacing = JBUI.size(0, 0)
            rowHeight = JBUI.scale(28)
            columnModel.getColumn(0).preferredWidth = JBUI.scale(150) // Service
            columnModel.getColumn(1).preferredWidth = JBUI.scale(250) // Docker Tag
            columnModel.getColumn(2).preferredWidth = JBUI.scale(100) // Latest
            columnModel.getColumn(3).preferredWidth = JBUI.scale(80)  // Registry
            columnModel.getColumn(4).preferredWidth = JBUI.scale(100) // Status

            // Tag column is editable
            columnModel.getColumn(1).cellEditor = DefaultCellEditor(JTextField())

            // Custom renderers
            setDefaultRenderer(Any::class.java, TagTableCellRenderer())
        }

        add(JBLabel("Docker Tags").apply {
            border = JBUI.Borders.emptyBottom(4)
            font = font.deriveFont(font.size + 1f)
        }, BorderLayout.NORTH)

        add(JScrollPane(table), BorderLayout.CENTER)
    }

    fun setEntries(entries: List<TagEntry>) {
        tableModel.entries = entries
        tableModel.fireTableDataChanged()
    }

    fun getEntries(): List<TagEntry> = tableModel.entries

    override fun dispose() {}

    // --- Table Model ---

    private class TagTableModel : AbstractTableModel() {
        var entries: List<TagEntry> = emptyList()

        private val columns = arrayOf("Service", "Docker Tag", "Latest", "Registry", "Status")

        override fun getRowCount() = entries.size
        override fun getColumnCount() = columns.size
        override fun getColumnName(col: Int) = columns[col]

        override fun getValueAt(row: Int, col: Int): Any {
            val entry = entries[row]
            return when (col) {
                0 -> entry.serviceName
                1 -> entry.currentTag
                2 -> entry.latestReleaseTag ?: ""
                3 -> when (entry.registryStatus) {
                    RegistryStatus.VALID -> "\u2713"
                    RegistryStatus.NOT_FOUND -> "\u2717"
                    RegistryStatus.CHECKING -> "..."
                    RegistryStatus.UNKNOWN -> ""
                    RegistryStatus.ERROR -> "!"
                }
                4 -> when {
                    entry.isCurrentRepo -> "Your branch"
                    entry.isDrift -> "\u26A0 Drift"
                    entry.registryStatus == RegistryStatus.VALID -> "\u2713 OK"
                    entry.registryStatus == RegistryStatus.NOT_FOUND -> "\u2717 Missing"
                    else -> ""
                }
                else -> ""
            }
        }

        override fun isCellEditable(row: Int, col: Int) = col == 1 // Only tag column

        override fun setValueAt(value: Any?, row: Int, col: Int) {
            if (col == 1 && value is String) {
                entries = entries.toMutableList().apply {
                    this[row] = this[row].copy(
                        currentTag = value,
                        source = TagSource.USER_EDIT,
                        registryStatus = RegistryStatus.UNKNOWN
                    )
                }
                fireTableRowsUpdated(row, row)
            }
        }
    }

    // --- Cell Renderer ---

    private class TagTableCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean,
            hasFocus: Boolean, row: Int, col: Int
        ): Component {
            val component = super.getTableCellRendererComponent(
                table, value, isSelected, hasFocus, row, col
            )
            if (!isSelected) {
                val model = table.model as TagTableModel
                if (row < model.entries.size) {
                    val entry = model.entries[row]
                    background = when {
                        entry.isCurrentRepo -> JBColor(0xE8F5E9, 0x1B5E20)
                        entry.isDrift -> JBColor(0xFFF3E0, 0x4E342E)
                        entry.registryStatus == RegistryStatus.NOT_FOUND -> JBColor(0xFFEBEE, 0x4A0000)
                        else -> table.background
                    }
                }
            }
            return component
        }
    }
}
```

- [ ] **Step 2: Commit deferred to Task 21**

---

### Task 20: Create SuiteConfigPanel — Variables and Stage Selection

**Files:**
- Create: `automation/src/main/kotlin/com/workflow/orchestrator/automation/ui/SuiteConfigPanel.kt`

- [ ] **Step 1: Implement SuiteConfigPanel**

Create `automation/src/main/kotlin/com/workflow/orchestrator/automation/ui/SuiteConfigPanel.kt`:

```kotlin
package com.workflow.orchestrator.automation.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.CollapsiblePanel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.*

class SuiteConfigPanel(
    private val project: Project
) : JPanel(BorderLayout()), Disposable {

    private val variablesPanel = JPanel(GridBagLayout())
    private val stagesPanel = JPanel(FlowLayout(FlowLayout.LEFT))
    private val variableRows = mutableListOf<VariableRow>()
    private val stageCheckboxes = mutableListOf<JCheckBox>()

    data class VariableRow(
        val keyCombo: JComboBox<String>,
        val valueField: JBTextField
    )

    init {
        border = JBUI.Borders.emptyTop(8)

        val headerLabel = JBLabel("Variables & Stages").apply {
            font = font.deriveFont(font.size + 1f)
        }

        val contentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)

            // Variables section
            add(JBLabel("Variables:").apply {
                border = JBUI.Borders.emptyBottom(4)
            })
            add(variablesPanel)

            // Stages section
            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(JBLabel("Stages:").apply {
                border = JBUI.Borders.emptyBottom(4)
            })
            add(stagesPanel)
        }

        add(headerLabel, BorderLayout.NORTH)
        add(contentPanel, BorderLayout.CENTER)
    }

    fun setAvailableVariables(keys: List<String>) {
        variablesPanel.removeAll()
        variableRows.clear()

        for (key in keys) {
            addVariableRow(key, "")
        }
        variablesPanel.revalidate()
    }

    fun setVariableValues(vars: Map<String, String>) {
        for (row in variableRows) {
            val key = row.keyCombo.selectedItem as? String ?: continue
            val value = vars[key]
            if (value != null) {
                row.valueField.text = value
            }
        }
    }

    fun getVariables(): Map<String, String> {
        return variableRows.associate { row ->
            val key = row.keyCombo.selectedItem as? String ?: ""
            val value = row.valueField.text ?: ""
            key to value
        }.filter { it.key.isNotEmpty() && it.value.isNotEmpty() }
    }

    fun setStages(stages: List<String>, enabled: List<String> = emptyList()) {
        stagesPanel.removeAll()
        stageCheckboxes.clear()

        for (stage in stages) {
            val checkbox = JCheckBox(stage, enabled.contains(stage))
            stageCheckboxes.add(checkbox)
            stagesPanel.add(checkbox)
        }
        stagesPanel.revalidate()
    }

    fun getEnabledStages(): List<String> {
        return stageCheckboxes.filter { it.isSelected }.map { it.text }
    }

    private fun addVariableRow(key: String, value: String) {
        val keyCombo = JComboBox(arrayOf(key)).apply {
            isEditable = false
            preferredSize = JBUI.size(150, 28)
        }
        val valueField = JBTextField(value).apply {
            preferredSize = JBUI.size(200, 28)
        }

        val gbc = GridBagConstraints().apply {
            gridy = variableRows.size
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(2)
        }

        gbc.gridx = 0; gbc.weightx = 0.3
        variablesPanel.add(keyCombo, gbc)

        gbc.gridx = 1; gbc.weightx = 0.7
        variablesPanel.add(valueField, gbc)

        variableRows.add(VariableRow(keyCombo, valueField))
    }

    override fun dispose() {}
}
```

- [ ] **Step 2: Commit deferred to Task 21**

---

### Task 21: Create QueueStatusPanel and Commit All UI Components

**Files:**
- Create: `automation/src/main/kotlin/com/workflow/orchestrator/automation/ui/QueueStatusPanel.kt`

- [ ] **Step 1: Implement QueueStatusPanel**

Create `automation/src/main/kotlin/com/workflow/orchestrator/automation/ui/QueueStatusPanel.kt`:

```kotlin
package com.workflow.orchestrator.automation.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.automation.model.QueueEntryStatus
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.*

class QueueStatusPanel(
    private val project: Project
) : JPanel(BorderLayout()), Disposable {

    private val statusLabel = JBLabel("Suite Idle")
    private val positionLabel = JBLabel("")
    private val estimateLabel = JBLabel("")
    private val alertLabel = JBLabel("").apply { isVisible = false }

    private val cancelButton = JButton("Cancel").apply { isEnabled = false }
    private val queueButton = JButton("Queue Run")
    private val triggerButton = JButton("Trigger Now \u25B6")

    var onCancel: (() -> Unit)? = null
    var onQueue: (() -> Unit)? = null
    var onTriggerNow: (() -> Unit)? = null

    init {
        border = JBUI.Borders.emptyBottom(8)

        // Live status bar
        val statusBar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            add(JBLabel("\u25CF").apply { foreground = JBColor.GRAY })
            add(statusLabel)
            add(positionLabel)
            add(estimateLabel)
        }

        // Alert bar (conditional)
        alertLabel.apply {
            border = JBUI.Borders.empty(4, 8)
            foreground = JBColor(0xE65100, 0xFFB74D)
        }

        // Action bar
        val actionBar = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            add(cancelButton)
            add(queueButton)
            add(triggerButton)
        }

        cancelButton.addActionListener { onCancel?.invoke() }
        queueButton.addActionListener { onQueue?.invoke() }
        triggerButton.addActionListener { onTriggerNow?.invoke() }

        val topPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(statusBar)
            add(alertLabel)
        }

        add(topPanel, BorderLayout.NORTH)
        add(actionBar, BorderLayout.SOUTH)
    }

    fun updateStatus(
        status: QueueEntryStatus?,
        queuePosition: Int = -1,
        estimatedWaitMs: Long? = null,
        runnerName: String? = null
    ) {
        statusLabel.text = when (status) {
            QueueEntryStatus.WAITING_LOCAL -> "Waiting for suite..."
            QueueEntryStatus.TRIGGERING -> "Triggering..."
            QueueEntryStatus.QUEUED_ON_BAMBOO -> "Queued on Bamboo"
            QueueEntryStatus.RUNNING -> "Running" + (runnerName?.let { " — $it" } ?: "")
            QueueEntryStatus.COMPLETED -> "Completed"
            QueueEntryStatus.FAILED_TO_TRIGGER -> "Failed to trigger"
            QueueEntryStatus.TAG_INVALID -> "Tags invalid"
            null -> "Suite Idle"
            else -> status.name
        }

        positionLabel.text = if (queuePosition >= 0) "Queue: #${queuePosition + 1}" else ""

        estimateLabel.text = if (estimatedWaitMs != null && estimatedWaitMs > 0) {
            val minutes = estimatedWaitMs / 60_000
            "Est. ~${minutes} min"
        } else ""

        cancelButton.isEnabled = status in listOf(
            QueueEntryStatus.WAITING_LOCAL,
            QueueEntryStatus.QUEUED_ON_BAMBOO
        )
    }

    fun showAlert(message: String) {
        alertLabel.text = "\u26A0 $message"
        alertLabel.isVisible = true
    }

    fun hideAlert() {
        alertLabel.isVisible = false
    }

    override fun dispose() {}
}
```

- [ ] **Step 2: Verify all UI components compile together**

Run: `./gradlew :automation:classes -x verifyPluginStructure`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit all UI components**

```bash
git add automation/src/main/kotlin/com/workflow/orchestrator/automation/ui/AutomationTabProvider.kt \
     automation/src/main/kotlin/com/workflow/orchestrator/automation/ui/AutomationPanel.kt \
     automation/src/main/kotlin/com/workflow/orchestrator/automation/ui/TagStagingPanel.kt \
     automation/src/main/kotlin/com/workflow/orchestrator/automation/ui/SuiteConfigPanel.kt \
     automation/src/main/kotlin/com/workflow/orchestrator/automation/ui/QueueStatusPanel.kt
git commit -m "feat(automation): add UI components — AutomationPanel, TagStagingPanel, SuiteConfigPanel, QueueStatusPanel"
```

---

### Task 22: Create AutomationStatusBarWidgetFactory

**Files:**
- Create: `automation/src/main/kotlin/com/workflow/orchestrator/automation/ui/AutomationStatusBarWidgetFactory.kt`

- [ ] **Step 1: Implement status bar widget**

Create `automation/src/main/kotlin/com/workflow/orchestrator/automation/ui/AutomationStatusBarWidgetFactory.kt`:

```kotlin
package com.workflow.orchestrator.automation.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.impl.status.EditorBasedWidget
import com.intellij.util.Consumer
import com.workflow.orchestrator.automation.model.QueueEntryStatus
import com.workflow.orchestrator.automation.service.QueueService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.awt.event.MouseEvent

class AutomationStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = "WorkflowAutomationStatusBar"
    override fun getDisplayName(): String = "Workflow Automation Queue"
    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget {
        return AutomationStatusBarWidget(project)
    }
}

private class AutomationStatusBarWidget(
    project: Project
) : EditorBasedWidget(project), StatusBarWidget.TextPresentation {

    private var text = "\u2713 Suite Idle"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun ID(): String = "WorkflowAutomationStatusBar"

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun getText(): String = text

    override fun getTooltipText(): String = "Automation Suite Queue Status"

    override fun getAlignment(): Float = 0f

    override fun getClickConsumer(): Consumer<MouseEvent>? = Consumer {
        // Open Automation tab in Workflow tool window
        val toolWindow = com.intellij.openapi.wm.ToolWindowManager
            .getInstance(project).getToolWindow("Workflow")
        toolWindow?.show {
            // Activate Automation tab
            val contentManager = toolWindow.contentManager
            val automationTab = contentManager.contents.find {
                it.displayName == AutomationTabProvider.TAB_TITLE
            }
            if (automationTab != null) {
                contentManager.setSelectedContent(automationTab)
            }
        }
    }

    override fun install(statusBar: StatusBar) {
        super.install(statusBar)
        scope.launch {
            try {
                val queueService = project.getService(QueueService::class.java) ?: return@launch
                queueService.stateFlow.collectLatest { entries ->
                    val hasRunning = entries.any { it.status == QueueEntryStatus.RUNNING }
                    val hasQueued = entries.any {
                        it.status in listOf(
                            QueueEntryStatus.WAITING_LOCAL,
                            QueueEntryStatus.QUEUED_ON_BAMBOO
                        )
                    }
                    val queueCount = entries.count {
                        it.status in listOf(
                            QueueEntryStatus.WAITING_LOCAL,
                            QueueEntryStatus.QUEUED_ON_BAMBOO
                        )
                    }

                    text = when {
                        hasRunning -> "\u25B6 Running"
                        hasQueued -> "\uD83D\uDD04 Queue #$queueCount"
                        else -> "\u2713 Suite Idle"
                    }

                    withContext(Dispatchers.Main) {
                        myStatusBar?.updateWidget(ID())
                    }
                }
            } catch (e: CancellationException) {
                // Expected on dispose
            }
        }
    }

    override fun dispose() {
        scope.cancel()
        super.dispose()
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :automation:classes -x verifyPluginStructure`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add automation/src/main/kotlin/com/workflow/orchestrator/automation/ui/AutomationStatusBarWidgetFactory.kt
git commit -m "feat(automation): add AutomationStatusBarWidgetFactory for queue-at-a-glance"
```

---

## Chunk 8: Plugin.xml Wiring & Integration

### Task 23: Register Automation Services and UI in plugin.xml

**Files:**
- Modify: `core/src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Add automation services to `com.intellij` extensions**

In `core/src/main/resources/META-INF/plugin.xml`, add inside `<extensions defaultExtensionNs="com.intellij">` (after the CVE Annotator block, before the closing `</extensions>`):

```xml
        <!-- Automation Module Project Services -->
        <projectService
            serviceImplementation="com.workflow.orchestrator.automation.service.QueueService"/>
        <projectService
            serviceImplementation="com.workflow.orchestrator.automation.service.TagBuilderService"/>
        <projectService
            serviceImplementation="com.workflow.orchestrator.automation.service.DriftDetectorService"/>
        <projectService
            serviceImplementation="com.workflow.orchestrator.automation.service.ConflictDetectorService"/>
        <projectService
            serviceImplementation="com.workflow.orchestrator.automation.service.TagHistoryService"/>

        <!-- App-level (cross-project) -->
        <applicationService
            serviceImplementation="com.workflow.orchestrator.automation.service.AutomationSettingsService"/>

        <!-- Status Bar -->
        <statusBarWidgetFactory id="WorkflowAutomationStatusBar"
            implementation="com.workflow.orchestrator.automation.ui.AutomationStatusBarWidgetFactory"/>

        <!-- Startup Recovery -->
        <postStartupActivity
            implementation="com.workflow.orchestrator.automation.service.QueueRecoveryStartupActivity"/>

        <!-- Notification group for queue state changes -->
        <notificationGroup id="workflow.automation.queue" displayType="BALLOON"/>
```

> **Note:** `TagBuilderService`, `DriftDetectorService`, `ConflictDetectorService` are registered as project services here but their constructors take plain class dependencies (not Project). The project-level service wrapper will need a Project constructor that creates the internal clients. See spec Section 2 note about DockerRegistryClient/BambooApiClient construction matching BuildMonitorService pattern.

> **Important:** These services as currently written take constructor parameters (BambooApiClient, etc.) rather than a `Project` parameter. For IntelliJ DI to work, each needs a `constructor(project: Project)` that creates its dependencies internally, plus the test constructor. This matches the `BuildMonitorService` dual-constructor pattern. **The implementer must add a Project constructor to `TagBuilderService`, `DriftDetectorService`, `ConflictDetectorService`, and `QueueService`** following the `BuildMonitorService` pattern:
>
> ```kotlin
> /** Project service constructor — used by IntelliJ DI. */
> constructor(project: Project) : this(
>     bambooClient = BambooApiClient(
>         baseUrl = PluginSettings.getInstance(project).state.bambooUrl.orEmpty().trimEnd('/'),
>         tokenProvider = { CredentialStore().getToken(ServiceType.BAMBOO) }
>     ),
>     // ... other dependencies
> )
> ```

- [ ] **Step 2: Add automation tab provider to custom extension namespace**

In `core/src/main/resources/META-INF/plugin.xml`, add inside `<extensions defaultExtensionNs="com.workflow.orchestrator">` (after the QualityTabProvider):

```xml
        <tabProvider implementation="com.workflow.orchestrator.automation.ui.AutomationTabProvider"/>
```

- [ ] **Step 3: Verify the full plugin compiles**

Run: `./gradlew classes -x verifyPluginStructure`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add core/src/main/resources/META-INF/plugin.xml
git commit -m "feat(automation): register automation services, tab provider, status bar, startup activity in plugin.xml"
```

---

### Task 24: Add Project Constructors to Services for IntelliJ DI (**CRITICAL — required for plugin.xml DI**)

**Files:**
- Modify: `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/TagBuilderService.kt`
- Modify: `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/DriftDetectorService.kt`
- Modify: `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/ConflictDetectorService.kt`
- Modify: `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/QueueService.kt`
- Modify: `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/TagHistoryService.kt`

> **Pattern:** `BuildMonitorService` uses dual constructors — one for IntelliJ DI (`constructor(project: Project)`) and one for testing (`constructor(apiClient, eventBus, scope, ...)`). Each service needs the same pattern.

- [ ] **Step 1: Add Project constructor to TagBuilderService**

At the top of `TagBuilderService`, add `@Service` annotation and Project constructor:

```kotlin
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.PluginSettings

@Service(Service.Level.PROJECT)
class TagBuilderService : /* existing code */ {

    /** Project service constructor — used by IntelliJ DI. */
    constructor(project: Project) : this(
        bambooClient = BambooApiClient(
            baseUrl = PluginSettings.getInstance(project).state.bambooUrl.orEmpty().trimEnd('/'),
            tokenProvider = { CredentialStore().getToken(ServiceType.BAMBOO) }
        )
    )

    /** Test constructor — allows injecting mocks. */
    constructor(bambooClient: BambooApiClient) {
        this.bambooClient = bambooClient
    }

    // ... rest of existing code, change val to private var for bambooClient
}
```

- [ ] **Step 2: Add Project constructor to DriftDetectorService**

```kotlin
@Service(Service.Level.PROJECT)
class DriftDetectorService : /* existing code */ {

    constructor(project: Project) : this(
        registryClient = DockerRegistryClient(
            registryUrl = (PluginSettings.getInstance(project).state.dockerRegistryUrl
                .takeUnless { it.isNullOrBlank() }
                ?: PluginSettings.getInstance(project).state.nexusUrl.orEmpty()).trimEnd('/'),
            tokenProvider = { CredentialStore().getToken(ServiceType.NEXUS) }
        )
    )

    constructor(registryClient: DockerRegistryClient) {
        this.registryClient = registryClient
    }
}
```

- [ ] **Step 3: Add Project constructor to ConflictDetectorService**

```kotlin
@Service(Service.Level.PROJECT)
class ConflictDetectorService : /* existing code */ {

    constructor(project: Project) : this(
        bambooClient = BambooApiClient(
            baseUrl = PluginSettings.getInstance(project).state.bambooUrl.orEmpty().trimEnd('/'),
            tokenProvider = { CredentialStore().getToken(ServiceType.BAMBOO) }
        )
    )

    constructor(bambooClient: BambooApiClient) {
        this.bambooClient = bambooClient
    }
}
```

- [ ] **Step 4: Add Project constructor to QueueService**

```kotlin
@Service(Service.Level.PROJECT)
class QueueService : Disposable {

    /** Project service constructor — used by IntelliJ DI. */
    constructor(project: Project) {
        val settings = PluginSettings.getInstance(project)
        val credentialStore = CredentialStore()
        this.bambooClient = BambooApiClient(
            baseUrl = settings.state.bambooUrl.orEmpty().trimEnd('/'),
            tokenProvider = { credentialStore.getToken(ServiceType.BAMBOO) }
        )
        val registryUrl = settings.state.dockerRegistryUrl.takeUnless { it.isNullOrBlank() }
            ?: settings.state.nexusUrl.orEmpty()
        this.registryClient = DockerRegistryClient(
            registryUrl = registryUrl.trimEnd('/'),
            tokenProvider = { credentialStore.getToken(ServiceType.NEXUS) }
        )
        this.eventBus = project.getService(EventBus::class.java)
        val dbPath = "${project.basePath}/.idea/workflow-orchestrator/automation.db"
        this.tagHistoryService = TagHistoryService(dbPath)
        this.scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        this.autoTriggerEnabled = settings.state.queueAutoTriggerEnabled
        this.maxDepthPerSuite = settings.state.queueMaxDepthPerSuite
        this.tagValidationOnTrigger = settings.state.tagValidationOnTrigger
    }

    // Test constructor stays as-is
}
```

- [ ] **Step 5: Add Project-wrapping constructor to TagHistoryService**

`TagHistoryService` is special — it takes a `dbPath` string, not a `Project`. For IntelliJ DI, add a Project constructor:

```kotlin
@Service(Service.Level.PROJECT)
class TagHistoryService {

    /** Project service constructor. */
    constructor(project: Project) : this(
        "${project.basePath}/.idea/workflow-orchestrator/automation.db"
    )

    /** Test constructor with explicit path. */
    constructor(dbPath: String) {
        this.dbPath = dbPath
    }

    private val dbPath: String
    // ... rest of existing code
}
```

- [ ] **Step 6: Run all tests to verify nothing broke**

Run: `./gradlew :automation:test -x verifyPluginStructure`
Expected: PASS — all tests still green (test constructors unchanged).

- [ ] **Step 7: Commit**

```bash
git add automation/src/main/kotlin/com/workflow/orchestrator/automation/service/TagBuilderService.kt \
     automation/src/main/kotlin/com/workflow/orchestrator/automation/service/DriftDetectorService.kt \
     automation/src/main/kotlin/com/workflow/orchestrator/automation/service/ConflictDetectorService.kt \
     automation/src/main/kotlin/com/workflow/orchestrator/automation/service/QueueService.kt \
     automation/src/main/kotlin/com/workflow/orchestrator/automation/service/TagHistoryService.kt
git commit -m "feat(automation): add Project constructors for IntelliJ DI (dual-constructor pattern)"
```

---

### Task 25: Final Verification

**Files:** None (verification only)

- [ ] **Step 1: Run all automation module tests**

Run: `./gradlew :automation:test -x verifyPluginStructure`
Expected: PASS — all tests green.

- [ ] **Step 2: Run all core module tests (events, settings)**

Run: `./gradlew :core:test -x verifyPluginStructure`
Expected: PASS

- [ ] **Step 3: Run all bamboo module tests (new API methods)**

Run: `./gradlew :bamboo:test -x verifyPluginStructure`
Expected: PASS

- [ ] **Step 4: Run full plugin verification**

Run: `./gradlew verifyPlugin`
Expected: PASS — no API compatibility issues.

- [ ] **Step 5: Build plugin ZIP**

Run: `./gradlew buildPlugin`
Expected: BUILD SUCCESSFUL — produces installable ZIP in `build/distributions/`.

- [ ] **Step 6: Manual verification in runIde**

Run: `./gradlew runIde`
Verify:
1. Plugin loads without errors in IDE log
2. "Workflow" tool window has "Automation" tab (4th tab, order=3)
3. Automation tab shows empty state when Bamboo URL is not configured
4. When Bamboo URL is configured, Automation tab shows the main panel
5. Status bar shows "Suite Idle" widget
6. Settings > Tools > Workflow Orchestrator shows Docker Registry URL field

- [ ] **Step 7: Final commit with all changes**

```bash
git add -A
git status  # verify no secrets or .idea files
git commit -m "feat(automation): complete Phase 2A Automation Orchestrator module

Implements Gate 7 features:
- Smart dockerTagsAsJson payload builder with baseline scoring
- Docker Registry v2 client with token auth, pagination, caching
- Configuration drift detection and conflict detection
- Queue management with auto-trigger and race condition handling
- SQLite persistence for queue state and run history
- APP-level suite config persistence across all projects
- IDE restart recovery via postStartupActivity
- Full UI: tag staging table, suite config, queue status, status bar widget"
```

---

## Gate 7 Verification Checklist

After completing all tasks, verify each Gate 7 milestone item:

- [ ] Staging panel: service table + tag selector + JSON preview
- [ ] Tag validation (ping Docker Registry before trigger)
- [ ] Diff view (your config vs last successful run)
- [ ] Configuration drift detector ("Update All to Latest")
- [ ] Smart queue (position, wait time, auto-trigger)
- [ ] Conflict detector (overlapping service tags)
- [ ] Last 5 configs persisted in SQLite
