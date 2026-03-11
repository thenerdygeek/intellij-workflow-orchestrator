# Phase 1C: Build Monitoring Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Bamboo build monitoring to the IDE — poll build status, display stages, trigger manual stages, and notify on build completion — eliminating the Bamboo web UI for daily CI feedback.

**Architecture:** New `:bamboo` Gradle submodule following the established `:jira` pattern (DTOs → API Client → Service → UI). Also introduces `EventBus` (Kotlin SharedFlow) in `:core` for cross-module communication. `BuildMonitorService` polls Bamboo every 30s, exposes `StateFlow<BuildState>` for reactive UI updates, and emits `BuildFinished` events.

**Tech Stack:** Kotlin 2.1.10, kotlinx.serialization 1.7.3, OkHttp 4.12.0, kotlinx.coroutines 1.8.0, Turbine 1.1.0 (testing), IntelliJ Platform 2025.1, JUnit 5, MockK

**Build prerequisite:** `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`

---

## File Map

### New files in `:core`
| File | Responsibility |
|------|---------------|
| `core/src/main/kotlin/.../core/events/WorkflowEvent.kt` | Sealed event hierarchy |
| `core/src/main/kotlin/.../core/events/EventBus.kt` | SharedFlow-based event dispatcher |
| `core/src/test/kotlin/.../core/events/EventBusTest.kt` | EventBus unit tests |

### New files in `:bamboo`
| File | Responsibility |
|------|---------------|
| `bamboo/build.gradle.kts` | Submodule build config |
| `bamboo/src/main/kotlin/.../bamboo/api/dto/BambooDtos.kt` | Serializable DTOs for Bamboo REST API |
| `bamboo/src/main/kotlin/.../bamboo/api/BambooApiClient.kt` | HTTP client with suspend functions |
| `bamboo/src/main/kotlin/.../bamboo/model/BuildState.kt` | BuildState, StageState, BuildStatus |
| `bamboo/src/main/kotlin/.../bamboo/model/BuildError.kt` | BuildError data class |
| `bamboo/src/main/kotlin/.../bamboo/service/BuildLogParser.kt` | Maven error extraction from logs |
| `bamboo/src/main/kotlin/.../bamboo/service/BuildMonitorService.kt` | Polling, state management, event emission |
| `bamboo/src/main/kotlin/.../bamboo/service/PlanDetectionService.kt` | Auto-detect plan from Git remote + search |
| `bamboo/src/main/kotlin/.../bamboo/ui/BuildTabProvider.kt` | WorkflowTabProvider for Build tab |
| `bamboo/src/main/kotlin/.../bamboo/ui/BuildDashboardPanel.kt` | Master-detail panel with toolbar |
| `bamboo/src/main/kotlin/.../bamboo/ui/StageListPanel.kt` | Stage list with status icons + Run button |
| `bamboo/src/main/kotlin/.../bamboo/ui/StageDetailPanel.kt` | Log viewer + error tab |
| `bamboo/src/main/kotlin/.../bamboo/ui/ManualStageDialog.kt` | DialogWrapper for build variables |
| `bamboo/src/main/kotlin/.../bamboo/ui/BuildStatusBarWidget.kt` | StatusBarWidgetFactory impl |

### Modified files
| File | Change |
|------|--------|
| `settings.gradle.kts` | Add `:bamboo` module |
| `build.gradle.kts` (root) | Add `implementation(project(":bamboo"))` |
| `gradle/libs.versions.toml` | Add Turbine dependency |
| `core/src/main/resources/META-INF/plugin.xml` | Add EventBus service, bamboo services, widget, tab, listener |
| `core/src/main/kotlin/.../core/settings/PluginSettings.kt` | Add `bambooPlanKey` field |

### Test files
| File | What it tests |
|------|--------------|
| `core/src/test/kotlin/.../core/events/EventBusTest.kt` | Emit, subscribe, multiple collectors, buffering |
| `bamboo/src/test/kotlin/.../bamboo/api/BambooApiClientTest.kt` | All endpoints with MockWebServer |
| `bamboo/src/test/kotlin/.../bamboo/service/BuildLogParserTest.kt` | Error extraction from log fixtures |
| `bamboo/src/test/kotlin/.../bamboo/service/BuildMonitorServiceTest.kt` | Polling, state transitions, notifications |
| `bamboo/src/test/kotlin/.../bamboo/service/PlanDetectionServiceTest.kt` | URL normalization, auto-detect logic |

### Test fixtures
| File | Content |
|------|---------|
| `bamboo/src/test/resources/fixtures/plan-list.json` | Bamboo plan list response |
| `bamboo/src/test/resources/fixtures/build-result.json` | Build result with stages |
| `bamboo/src/test/resources/fixtures/build-result-failed.json` | Failed build result |
| `bamboo/src/test/resources/fixtures/plan-variables.json` | Plan variable definitions |
| `bamboo/src/test/resources/fixtures/search-results.json` | Plan search response |
| `bamboo/src/test/resources/fixtures/build-log.txt` | Sample Maven build log with errors |

---

**Path prefix:** All Kotlin files use the package root `com/workflow/orchestrator/`. For brevity, paths below use `...` for this prefix.

---

## Chunk 1: Build Configuration & EventBus Infrastructure

### Task 1: Gradle Configuration

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `build.gradle.kts` (root)
- Create: `bamboo/build.gradle.kts`
- Modify: `gradle/libs.versions.toml`

- [ ] **Step 1: Add Turbine to version catalog**

In `gradle/libs.versions.toml`, add the Turbine version and library entry:

```toml
# Under [versions], add:
turbine = "1.1.0"

# Under [libraries], add:
turbine = { group = "app.cash.turbine", name = "turbine", version.ref = "turbine" }
```

- [ ] **Step 2: Add `:bamboo` to settings.gradle.kts**

```kotlin
// Change the include block to:
include(
    ":core",
    ":jira",
    ":git-integration",
    ":bamboo",
)
```

- [ ] **Step 3: Add `:bamboo` to root build.gradle.kts**

In the `dependencies` block, add after the `:git-integration` line:

```kotlin
implementation(project(":bamboo"))
```

- [ ] **Step 4: Create bamboo/build.gradle.kts**

```kotlin
// bamboo/build.gradle.kts — Submodule for Bamboo build monitoring.
// Uses the MODULE variant; depends on :core.

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

- [ ] **Step 5: Verify build compiles**

Run:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew :bamboo:classes --no-configuration-cache
```

Expected: BUILD SUCCESSFUL (no source files yet, but Gradle resolves dependencies)

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts build.gradle.kts bamboo/build.gradle.kts gradle/libs.versions.toml
git commit -m "build: add :bamboo submodule and Turbine test dependency"
```

---

### Task 2: EventBus — WorkflowEvent Sealed Class

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/events/WorkflowEvent.kt`

- [ ] **Step 1: Create the WorkflowEvent sealed class**

```kotlin
package com.workflow.orchestrator.core.events

/**
 * Sealed hierarchy for cross-module events dispatched through [EventBus].
 * Each phase adds its own subclasses. Only bamboo events exist in Phase 1C.
 */
sealed class WorkflowEvent {

    /** Emitted by :bamboo when a build reaches a terminal state. */
    data class BuildFinished(
        val planKey: String,
        val buildNumber: Int,
        val status: BuildEventStatus
    ) : WorkflowEvent()

    /** Emitted by :bamboo when a build log has been fetched and parsed. */
    data class BuildLogReady(
        val planKey: String,
        val buildNumber: Int,
        val log: String
    ) : WorkflowEvent()

    enum class BuildEventStatus { SUCCESS, FAILED }
}
```

- [ ] **Step 2: Verify compilation**

Run:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew :core:classes --no-configuration-cache
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/events/WorkflowEvent.kt
git commit -m "feat(core): add WorkflowEvent sealed class for cross-module events"
```

---

### Task 3: EventBus — SharedFlow Dispatcher (TDD)

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/events/EventBus.kt`
- Create: `core/src/test/kotlin/com/workflow/orchestrator/core/events/EventBusTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.workflow.orchestrator.core.events

import app.cash.turbine.test
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EventBusTest {

    @Test
    fun `emit delivers event to subscriber`() = runTest {
        val bus = EventBus()
        val event = WorkflowEvent.BuildFinished("PROJ-BUILD", 42, WorkflowEvent.BuildEventStatus.SUCCESS)

        bus.events.test {
            bus.emit(event)
            assertEquals(event, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `emit delivers to multiple subscribers`() = runTest {
        val bus = EventBus()
        val event = WorkflowEvent.BuildFinished("PROJ-BUILD", 1, WorkflowEvent.BuildEventStatus.FAILED)

        // Use two Turbine collectors to verify both receive the event
        val received1 = mutableListOf<WorkflowEvent>()
        val received2 = mutableListOf<WorkflowEvent>()

        val job1 = launch { bus.events.collect { received1.add(it) } }
        val job2 = launch { bus.events.collect { received2.add(it) } }

        // Let collectors subscribe before emitting
        testScheduler.advanceUntilIdle()

        bus.emit(event)
        testScheduler.advanceUntilIdle()

        assertEquals(1, received1.size)
        assertEquals(1, received2.size)
        assertEquals(event, received1[0])
        assertEquals(event, received2[0])

        job1.cancel()
        job2.cancel()
    }

    @Test
    fun `no replay — late subscriber misses past events`() = runTest {
        val bus = EventBus()
        val event = WorkflowEvent.BuildFinished("PROJ-BUILD", 1, WorkflowEvent.BuildEventStatus.SUCCESS)

        bus.emit(event)

        bus.events.test {
            // Should not receive the event emitted before subscription
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew :core:test --tests "com.workflow.orchestrator.core.events.EventBusTest" --no-configuration-cache
```

Expected: FAIL — `EventBus` class does not exist

- [ ] **Step 3: Implement EventBus**

```kotlin
package com.workflow.orchestrator.core.events

import com.intellij.openapi.components.Service
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

@Service(Service.Level.PROJECT)
class EventBus {
    private val _events = MutableSharedFlow<WorkflowEvent>(
        replay = 0,
        extraBufferCapacity = 64
    )
    val events: SharedFlow<WorkflowEvent> = _events.asSharedFlow()

    suspend fun emit(event: WorkflowEvent) {
        _events.emit(event)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew :core:test --tests "com.workflow.orchestrator.core.events.EventBusTest" --no-configuration-cache
```

Expected: 3 tests PASSED

- [ ] **Step 5: Register EventBus in plugin.xml**

In `core/src/main/resources/META-INF/plugin.xml`, add inside the `<extensions defaultExtensionNs="com.intellij">` block, after the existing `projectService` entries:

```xml
        <projectService
            serviceImplementation="com.workflow.orchestrator.core.events.EventBus"/>
```

- [ ] **Step 6: Run all core tests to verify nothing is broken**

Run:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew :core:test --no-configuration-cache
```

Expected: All tests PASSED (30 existing + 3 new = 33)

- [ ] **Step 7: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/events/EventBus.kt \
       core/src/test/kotlin/com/workflow/orchestrator/core/events/EventBusTest.kt \
       core/src/main/resources/META-INF/plugin.xml
git commit -m "feat(core): add EventBus with SharedFlow for cross-module events"
```

---

### Task 4: Add bambooPlanKey to PluginSettings

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/settings/PluginSettings.kt`

- [ ] **Step 1: Add the new field**

In `PluginSettings.State`, add after the `sonarPollIntervalSeconds` line:

```kotlin
        // Bamboo plan key (auto-detected or user-configured)
        var bambooPlanKey by string("")
```

- [ ] **Step 2: Verify compilation**

Run:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew :core:classes --no-configuration-cache
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/settings/PluginSettings.kt
git commit -m "feat(core): add bambooPlanKey to PluginSettings"
```

---

## Chunk 2: Bamboo DTOs & API Client

### Task 5: Bamboo DTOs

**Files:**
- Create: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/api/dto/BambooDtos.kt`

- [ ] **Step 1: Create the DTO file**

```kotlin
package com.workflow.orchestrator.bamboo.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- Plan DTOs ---

@Serializable
data class BambooPlanListResponse(
    val plans: BambooPlanCollection = BambooPlanCollection()
)

@Serializable
data class BambooPlanCollection(
    val size: Int = 0,
    @SerialName("max-result") val maxResult: Int = 25,
    @SerialName("start-index") val startIndex: Int = 0,
    val plan: List<BambooPlanDto> = emptyList()
)

@Serializable
data class BambooPlanDto(
    val key: String,
    val shortKey: String = "",
    val name: String,
    val shortName: String = "",
    val enabled: Boolean = true,
    val type: String = "chain"
)

// --- Branch DTOs ---

@Serializable
data class BambooBranchListResponse(
    val branches: BambooBranchCollection = BambooBranchCollection()
)

@Serializable
data class BambooBranchCollection(
    val size: Int = 0,
    @SerialName("max-result") val maxResult: Int = 25,
    @SerialName("start-index") val startIndex: Int = 0,
    val branch: List<BambooBranchDto> = emptyList()
)

@Serializable
data class BambooBranchDto(
    val key: String,
    val name: String = "",
    val shortName: String = "",
    val enabled: Boolean = true
)

// --- Build Result DTOs ---

@Serializable
data class BambooResultResponse(
    val results: BambooResultCollection = BambooResultCollection()
)

@Serializable
data class BambooResultCollection(
    val size: Int = 0,
    val result: List<BambooResultDto> = emptyList()
)

@Serializable
data class BambooResultDto(
    val key: String = "",
    val buildNumber: Int = 0,
    val state: String = "",               // Successful, Failed, Unknown
    val lifeCycleState: String = "",      // Queued, Pending, InProgress, Finished
    val buildDurationInSeconds: Long = 0,
    val buildRelativeTime: String = "",
    val plan: BambooPlanDto? = null,
    val stages: BambooStageCollection = BambooStageCollection()
)

@Serializable
data class BambooStageCollection(
    val size: Int = 0,
    val stage: List<BambooStageDto> = emptyList()
)

@Serializable
data class BambooStageDto(
    val name: String,
    val state: String = "",               // Successful, Failed, Unknown
    val lifeCycleState: String = "",      // Queued, Pending, InProgress, Finished
    val manual: Boolean = false,
    val buildDurationInSeconds: Long = 0
)

// --- Plan Variables DTOs ---

@Serializable
data class BambooVariableListResponse(
    val variables: BambooVariableCollection = BambooVariableCollection()
)

@Serializable
data class BambooVariableCollection(
    val size: Int = 0,
    val variable: List<BambooPlanVariableDto> = emptyList()
)

@Serializable
data class BambooPlanVariableDto(
    val name: String,
    val value: String = ""
)

// --- Search DTOs ---

@Serializable
data class BambooSearchResponse(
    val size: Int = 0,
    @SerialName("max-result") val maxResult: Int = 25,
    @SerialName("start-index") val startIndex: Int = 0,
    val searchResults: List<BambooSearchResultItem> = emptyList()
)

@Serializable
data class BambooSearchResultItem(
    val searchEntity: BambooSearchEntity
)

@Serializable
data class BambooSearchEntity(
    val key: String,
    val planName: String = "",
    val projectName: String = "",
    val type: String = ""
)

// --- Queue (Trigger) DTOs ---

@Serializable
data class BambooQueueResponse(
    val triggerReason: String = "",
    val buildNumber: Int = 0,
    val buildResultKey: String = "",
    val planKey: String = ""
)
```

- [ ] **Step 2: Verify compilation**

Run:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew :bamboo:classes --no-configuration-cache
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/api/dto/BambooDtos.kt
git commit -m "feat(bamboo): add Bamboo REST API DTOs"
```

---

### Task 6: Bamboo Model Classes

**Files:**
- Create: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/model/BuildState.kt`
- Create: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/model/BuildError.kt`

- [ ] **Step 1: Create BuildState**

```kotlin
package com.workflow.orchestrator.bamboo.model

import java.time.Instant

enum class BuildStatus {
    SUCCESS, FAILED, IN_PROGRESS, PENDING, UNKNOWN;

    companion object {
        /** Maps Bamboo API state strings to BuildStatus. */
        fun fromBambooState(state: String, lifeCycleState: String): BuildStatus = when {
            lifeCycleState.equals("InProgress", ignoreCase = true) -> IN_PROGRESS
            lifeCycleState.equals("Queued", ignoreCase = true) -> PENDING
            lifeCycleState.equals("Pending", ignoreCase = true) -> PENDING
            state.equals("Successful", ignoreCase = true) -> SUCCESS
            state.equals("Failed", ignoreCase = true) -> FAILED
            else -> UNKNOWN
        }
    }
}

data class BuildState(
    val planKey: String,
    val branch: String,
    val buildNumber: Int,
    val stages: List<StageState>,
    val overallStatus: BuildStatus,
    val lastUpdated: Instant
)

data class StageState(
    val name: String,
    val status: BuildStatus,
    val manual: Boolean,
    val durationMs: Long?
)
```

- [ ] **Step 2: Create BuildError**

```kotlin
package com.workflow.orchestrator.bamboo.model

enum class ErrorSeverity { ERROR, WARNING }

data class BuildError(
    val severity: ErrorSeverity,
    val message: String,
    val filePath: String?,
    val lineNumber: Int?
)
```

- [ ] **Step 3: Verify compilation**

Run:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew :bamboo:classes --no-configuration-cache
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/model/BuildState.kt \
       bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/model/BuildError.kt
git commit -m "feat(bamboo): add BuildState and BuildError model classes"
```

---

### Task 7: Test Fixtures

**Files:**
- Create: `bamboo/src/test/resources/fixtures/plan-list.json`
- Create: `bamboo/src/test/resources/fixtures/build-result.json`
- Create: `bamboo/src/test/resources/fixtures/build-result-failed.json`
- Create: `bamboo/src/test/resources/fixtures/plan-variables.json`
- Create: `bamboo/src/test/resources/fixtures/search-results.json`
- Create: `bamboo/src/test/resources/fixtures/build-log.txt`

- [ ] **Step 1: Create plan-list.json**

```json
{
  "plans": {
    "size": 2,
    "max-result": 25,
    "start-index": 0,
    "plan": [
      {
        "key": "PROJ-BUILD",
        "shortKey": "BUILD",
        "name": "My Project - Build",
        "shortName": "Build",
        "enabled": true,
        "type": "chain"
      },
      {
        "key": "PROJ-DEPLOY",
        "shortKey": "DEPLOY",
        "name": "My Project - Deploy",
        "shortName": "Deploy",
        "enabled": true,
        "type": "chain"
      }
    ]
  }
}
```

- [ ] **Step 2: Create build-result.json**

```json
{
  "key": "PROJ-BUILD-42",
  "buildNumber": 42,
  "state": "Successful",
  "lifeCycleState": "Finished",
  "buildDurationInSeconds": 325,
  "buildRelativeTime": "5 minutes ago",
  "stages": {
    "size": 3,
    "stage": [
      {
        "name": "Compile",
        "state": "Successful",
        "lifeCycleState": "Finished",
        "manual": false,
        "buildDurationInSeconds": 120
      },
      {
        "name": "Unit Test",
        "state": "Successful",
        "lifeCycleState": "Finished",
        "manual": false,
        "buildDurationInSeconds": 180
      },
      {
        "name": "Deploy",
        "state": "Unknown",
        "lifeCycleState": "NotBuilt",
        "manual": true,
        "buildDurationInSeconds": 0
      }
    ]
  }
}
```

- [ ] **Step 3: Create build-result-failed.json**

```json
{
  "key": "PROJ-BUILD-43",
  "buildNumber": 43,
  "state": "Failed",
  "lifeCycleState": "Finished",
  "buildDurationInSeconds": 95,
  "buildRelativeTime": "2 minutes ago",
  "stages": {
    "size": 3,
    "stage": [
      {
        "name": "Compile",
        "state": "Successful",
        "lifeCycleState": "Finished",
        "manual": false,
        "buildDurationInSeconds": 90
      },
      {
        "name": "Unit Test",
        "state": "Failed",
        "lifeCycleState": "Finished",
        "manual": false,
        "buildDurationInSeconds": 5
      },
      {
        "name": "Deploy",
        "state": "Unknown",
        "lifeCycleState": "NotBuilt",
        "manual": true,
        "buildDurationInSeconds": 0
      }
    ]
  }
}
```

- [ ] **Step 4: Create plan-variables.json**

```json
{
  "variables": {
    "size": 3,
    "variable": [
      {
        "name": "skipTests",
        "value": "false"
      },
      {
        "name": "deployTarget",
        "value": "staging"
      },
      {
        "name": "cleanBuild",
        "value": "true"
      }
    ]
  }
}
```

- [ ] **Step 5: Create search-results.json**

```json
{
  "size": 2,
  "max-result": 25,
  "start-index": 0,
  "searchResults": [
    {
      "searchEntity": {
        "key": "PROJ-BUILD",
        "planName": "Build",
        "projectName": "My Project",
        "type": "chain"
      }
    },
    {
      "searchEntity": {
        "key": "PROJ-DEPLOY",
        "planName": "Deploy",
        "projectName": "My Project",
        "type": "chain"
      }
    }
  ]
}
```

- [ ] **Step 6: Create build-log.txt**

```
build    11-Mar-2026 10:23:45    [INFO] Scanning for projects...
build    11-Mar-2026 10:23:46    [INFO] Building my-app 1.0-SNAPSHOT
build    11-Mar-2026 10:23:47    [INFO] --- maven-compiler-plugin:3.11.0:compile ---
build    11-Mar-2026 10:24:02    [ERROR] /src/main/java/com/example/UserService.java:[45,12] error: cannot find symbol
build    11-Mar-2026 10:24:02    [ERROR]   symbol:   method getUser()
build    11-Mar-2026 10:24:02    [ERROR]   location: class UserRepository
build    11-Mar-2026 10:24:03    [WARNING] /src/main/java/com/example/Config.java:[12,1] warning: [deprecation] oldMethod() in Legacy has been deprecated
build    11-Mar-2026 10:24:10    [ERROR] /src/test/java/com/example/UserServiceTest.java:[23,8] error: method does not exist
build    11-Mar-2026 10:24:15    [INFO] BUILD FAILURE
build    11-Mar-2026 10:24:15    [INFO] Total time: 30 s
```

- [ ] **Step 7: Commit**

```bash
git add bamboo/src/test/resources/fixtures/
git commit -m "test(bamboo): add Bamboo API test fixtures"
```

---

### Task 8: BambooApiClient (TDD)

**Files:**
- Create: `bamboo/src/test/kotlin/com/workflow/orchestrator/bamboo/api/BambooApiClientTest.kt`
- Create: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/api/BambooApiClient.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.workflow.orchestrator.bamboo.api

import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class BambooApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: BambooApiClient

    private fun fixture(name: String): String =
        javaClass.classLoader.getResource("fixtures/$name")!!.readText()

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = BambooApiClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            tokenProvider = { "test-token" }
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPlans returns parsed plan list`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("plan-list.json")))

        val result = client.getPlans()

        assertTrue(result.isSuccess)
        val plans = (result as ApiResult.Success).data
        assertEquals(2, plans.size)
        assertEquals("PROJ-BUILD", plans[0].key)
        assertEquals("My Project - Build", plans[0].name)

        val recorded = server.takeRequest()
        assertEquals("/rest/api/latest/plan?expand=plans.plan&max-results=100", recorded.path)
        assertEquals("Bearer test-token", recorded.getHeader("Authorization"))
    }

    @Test
    fun `searchPlans returns search results`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("search-results.json")))

        val result = client.searchPlans("Build")

        assertTrue(result.isSuccess)
        val items = (result as ApiResult.Success).data
        assertEquals(2, items.size)
        assertEquals("PROJ-BUILD", items[0].key)

        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.contains("/rest/api/latest/search/plans"))
        assertTrue(recorded.path!!.contains("searchTerm=Build"))
    }

    @Test
    fun `getLatestResult returns build result with stages`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("build-result.json")))

        val result = client.getLatestResult("PROJ-BUILD", "feature/PROJ-123")

        assertTrue(result.isSuccess)
        val build = (result as ApiResult.Success).data
        assertEquals(42, build.buildNumber)
        assertEquals("Successful", build.state)
        assertEquals(3, build.stages.stage.size)
        assertEquals("Compile", build.stages.stage[0].name)
        assertTrue(build.stages.stage[2].manual)

        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.contains("/rest/api/latest/result/PROJ-BUILD/branch/feature%2FPROJ-123"))
    }

    @Test
    fun `getVariables returns plan variables`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("plan-variables.json")))

        val result = client.getVariables("PROJ-BUILD")

        assertTrue(result.isSuccess)
        val vars = (result as ApiResult.Success).data
        assertEquals(3, vars.size)
        assertEquals("skipTests", vars[0].name)
        assertEquals("false", vars[0].value)

        val recorded = server.takeRequest()
        assertEquals("/rest/api/latest/plan/PROJ-BUILD/variable", recorded.path)
    }

    @Test
    fun `triggerBuild sends POST with variables`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"buildResultKey":"PROJ-BUILD-44","buildNumber":44}"""))

        val variables = mapOf("skipTests" to "true", "deployTarget" to "prod")
        val result = client.triggerBuild("PROJ-BUILD", variables, stageName = "Deploy")

        assertTrue(result.isSuccess)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertTrue(recorded.path!!.contains("/rest/api/latest/queue/PROJ-BUILD"))
        assertTrue(recorded.path!!.contains("stage=Deploy"))
        assertTrue(recorded.path!!.contains("executeAllStages=false"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("skipTests"))
        assertTrue(body.contains("true"))
    }

    @Test
    fun `returns AUTH_FAILED on 401`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = client.getPlans()

        assertTrue(result.isError)
        assertEquals(ErrorType.AUTH_FAILED, (result as ApiResult.Error).type)
    }

    @Test
    fun `getBuildLog returns raw log text`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("build-log.txt")))

        val result = client.getBuildLog("PROJ-BUILD-42")

        assertTrue(result.isSuccess)
        val log = (result as ApiResult.Success).data
        assertTrue(log.contains("[ERROR]"))
        assertTrue(log.contains("UserService.java"))

        val recorded = server.takeRequest()
        assertEquals("/rest/api/latest/result/PROJ-BUILD-42?expand=logEntries&max-results=2000", recorded.path)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew :bamboo:test --tests "com.workflow.orchestrator.bamboo.api.BambooApiClientTest" --no-configuration-cache
```

Expected: FAIL — `BambooApiClient` class does not exist

- [ ] **Step 3: Implement BambooApiClient**

```kotlin
package com.workflow.orchestrator.bamboo.api

import com.workflow.orchestrator.bamboo.api.dto.*
import com.workflow.orchestrator.core.http.AuthInterceptor
import com.workflow.orchestrator.core.http.AuthScheme
import com.workflow.orchestrator.core.http.RetryInterceptor
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class BambooApiClient(
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

    suspend fun getPlans(): ApiResult<List<BambooPlanDto>> =
        get<BambooPlanListResponse>("/rest/api/latest/plan?expand=plans.plan&max-results=100")
            .map { it.plans.plan }

    suspend fun searchPlans(query: String): ApiResult<List<BambooSearchEntity>> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return get<BambooSearchResponse>("/rest/api/latest/search/plans?searchTerm=$encoded&fuzzy=true&max-results=25")
            .map { it.searchResults.map { r -> r.searchEntity } }
    }

    suspend fun getPlanSpecs(planKey: String): ApiResult<String> =
        getRaw("/rest/api/latest/plan/$planKey/specs?format=YAML")

    suspend fun getBranches(planKey: String): ApiResult<List<BambooBranchDto>> =
        get<BambooBranchListResponse>("/rest/api/latest/plan/$planKey/branch?max-results=100")
            .map { it.branches.branch }

    suspend fun getLatestResult(planKey: String, branch: String): ApiResult<BambooResultDto> {
        val encodedBranch = URLEncoder.encode(branch, "UTF-8")
        return get("/rest/api/latest/result/$planKey/branch/$encodedBranch/latest?expand=stages.stage")
    }

    suspend fun getBuildLog(resultKey: String): ApiResult<String> =
        getRaw("/rest/api/latest/result/$resultKey?expand=logEntries&max-results=2000")

    suspend fun getVariables(planKey: String): ApiResult<List<BambooPlanVariableDto>> =
        get<BambooVariableListResponse>("/rest/api/latest/plan/$planKey/variable")
            .map { it.variables.variable }

    suspend fun triggerBuild(
        planKey: String,
        variables: Map<String, String> = emptyMap(),
        stageName: String? = null
    ): ApiResult<BambooQueueResponse> {
        val params = buildString {
            if (stageName != null) {
                append("?stage=${URLEncoder.encode(stageName, "UTF-8")}&executeAllStages=false")
            }
        }
        val bodyJson = if (variables.isNotEmpty()) {
            val varEntries = variables.entries.joinToString(",") { (k, v) ->
                """{"name":"$k","value":"$v"}"""
            }
            """{"variables":[$varEntries]}"""
        } else {
            "{}"
        }
        return post("/rest/api/latest/queue/$planKey$params", bodyJson)
    }

    private suspend inline fun <reified T> get(path: String): ApiResult<T> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url("$baseUrl$path").get()
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> {
                            val bodyStr = it.body?.string() ?: ""
                            ApiResult.Success(json.decodeFromString<T>(bodyStr))
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bamboo token")
                        403 -> ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient Bamboo permissions")
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "Bamboo resource not found")
                        429 -> ApiResult.Error(ErrorType.RATE_LIMITED, "Bamboo rate limit exceeded")
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Bamboo returned ${it.code}")
                    }
                }
            } catch (e: IOException) {
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bamboo: ${e.message}", e)
            }
        }

    private suspend fun getRaw(path: String): ApiResult<String> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url("$baseUrl$path").get().build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> ApiResult.Success(it.body?.string() ?: "")
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

    private suspend inline fun <reified T> post(path: String, jsonBody: String): ApiResult<T> =
        withContext(Dispatchers.IO) {
            try {
                val body = jsonBody.toRequestBody("application/json".toMediaType())
                val request = Request.Builder().url("$baseUrl$path").post(body)
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> {
                            val bodyStr = it.body?.string() ?: ""
                            ApiResult.Success(json.decodeFromString<T>(bodyStr))
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bamboo token")
                        403 -> ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient Bamboo permissions")
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Bamboo returned ${it.code}")
                    }
                }
            } catch (e: IOException) {
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bamboo: ${e.message}", e)
            }
        }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew :bamboo:test --tests "com.workflow.orchestrator.bamboo.api.BambooApiClientTest" --no-configuration-cache
```

Expected: 7 tests PASSED

- [ ] **Step 5: Commit**

```bash
git add bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/api/BambooApiClient.kt \
       bamboo/src/test/kotlin/com/workflow/orchestrator/bamboo/api/BambooApiClientTest.kt
git commit -m "feat(bamboo): add BambooApiClient with all REST endpoints"
```

---

## Chunk 3: Services — BuildLogParser, PlanDetectionService, BuildMonitorService

### Task 9: BuildLogParser (TDD)

**Files:**
- Create: `bamboo/src/test/kotlin/com/workflow/orchestrator/bamboo/service/BuildLogParserTest.kt`
- Create: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/BuildLogParser.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.workflow.orchestrator.bamboo.service

import com.workflow.orchestrator.bamboo.model.ErrorSeverity
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BuildLogParserTest {

    private fun fixture(name: String): String =
        javaClass.classLoader.getResource("fixtures/$name")!!.readText()

    @Test
    fun `parses ERROR lines with file path and line number`() {
        val log = fixture("build-log.txt")

        val errors = BuildLogParser.parse(log)

        val compileErrors = errors.filter { it.severity == ErrorSeverity.ERROR }
        assertTrue(compileErrors.isNotEmpty())

        val first = compileErrors[0]
        assertEquals(ErrorSeverity.ERROR, first.severity)
        assertEquals("/src/main/java/com/example/UserService.java", first.filePath)
        assertEquals(45, first.lineNumber)
        assertTrue(first.message.contains("cannot find symbol"))
    }

    @Test
    fun `parses WARNING lines`() {
        val log = fixture("build-log.txt")

        val errors = BuildLogParser.parse(log)

        val warnings = errors.filter { it.severity == ErrorSeverity.WARNING }
        assertTrue(warnings.isNotEmpty())
        assertEquals("/src/main/java/com/example/Config.java", warnings[0].filePath)
        assertEquals(12, warnings[0].lineNumber)
    }

    @Test
    fun `returns empty list for clean log`() {
        val log = """
            build    11-Mar-2026 10:23:45    [INFO] BUILD SUCCESS
            build    11-Mar-2026 10:23:45    [INFO] Total time: 10 s
        """.trimIndent()

        val errors = BuildLogParser.parse(log)

        assertTrue(errors.isEmpty())
    }

    @Test
    fun `handles ERROR lines without file path`() {
        val log = """
            build    11-Mar-2026 10:23:45    [ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin
        """.trimIndent()

        val errors = BuildLogParser.parse(log)

        assertEquals(1, errors.size)
        assertNull(errors[0].filePath)
        assertNull(errors[0].lineNumber)
        assertTrue(errors[0].message.contains("Failed to execute goal"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew :bamboo:test --tests "com.workflow.orchestrator.bamboo.service.BuildLogParserTest" --no-configuration-cache
```

Expected: FAIL — `BuildLogParser` does not exist

- [ ] **Step 3: Implement BuildLogParser**

```kotlin
package com.workflow.orchestrator.bamboo.service

import com.workflow.orchestrator.bamboo.model.BuildError
import com.workflow.orchestrator.bamboo.model.ErrorSeverity

object BuildLogParser {

    // Matches: [ERROR] /path/to/File.java:[lineNum,col] message
    private val FILE_ERROR_PATTERN = Regex(
        """\[ERROR]\s+(/\S+\.(?:java|kt|xml)):\[(\d+),\d+]\s+(?:error:\s+)?(.+)"""
    )

    // Matches: [WARNING] /path/to/File.java:[lineNum,col] warning: message
    private val FILE_WARNING_PATTERN = Regex(
        """\[WARNING]\s+(/\S+\.(?:java|kt|xml)):\[(\d+),\d+]\s+(?:warning:\s+)?(.+)"""
    )

    // Matches: [ERROR] generic message (no file path)
    private val GENERIC_ERROR_PATTERN = Regex(
        """\[ERROR]\s+(?!/\S+\.(?:java|kt|xml))(.+)"""
    )

    fun parse(log: String): List<BuildError> {
        val errors = mutableListOf<BuildError>()

        for (line in log.lines()) {
            FILE_ERROR_PATTERN.find(line)?.let { match ->
                errors.add(
                    BuildError(
                        severity = ErrorSeverity.ERROR,
                        message = match.groupValues[3].trim(),
                        filePath = match.groupValues[1],
                        lineNumber = match.groupValues[2].toIntOrNull()
                    )
                )
                return@let
            } ?: FILE_WARNING_PATTERN.find(line)?.let { match ->
                errors.add(
                    BuildError(
                        severity = ErrorSeverity.WARNING,
                        message = match.groupValues[3].trim(),
                        filePath = match.groupValues[1],
                        lineNumber = match.groupValues[2].toIntOrNull()
                    )
                )
                return@let
            } ?: run {
                GENERIC_ERROR_PATTERN.find(line)?.let { match ->
                    val msg = match.groupValues[1].trim()
                    // Skip continuation lines (e.g., "  symbol:", "  location:")
                    if (!msg.startsWith("symbol:") && !msg.startsWith("location:") && msg.isNotBlank()) {
                        errors.add(
                            BuildError(
                                severity = ErrorSeverity.ERROR,
                                message = msg,
                                filePath = null,
                                lineNumber = null
                            )
                        )
                    }
                }
            }
        }

        return errors
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew :bamboo:test --tests "com.workflow.orchestrator.bamboo.service.BuildLogParserTest" --no-configuration-cache
```

Expected: 4 tests PASSED

- [ ] **Step 5: Commit**

```bash
git add bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/BuildLogParser.kt \
       bamboo/src/test/kotlin/com/workflow/orchestrator/bamboo/service/BuildLogParserTest.kt
git commit -m "feat(bamboo): add BuildLogParser for Maven error extraction"
```

---

### Task 10: PlanDetectionService (TDD)

**Files:**
- Create: `bamboo/src/test/kotlin/com/workflow/orchestrator/bamboo/service/PlanDetectionServiceTest.kt`
- Create: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/PlanDetectionService.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.workflow.orchestrator.bamboo.service

import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.bamboo.api.dto.BambooPlanDto
import com.workflow.orchestrator.bamboo.api.dto.BambooSearchEntity
import com.workflow.orchestrator.core.model.ApiResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PlanDetectionServiceTest {

    private val apiClient = mockk<BambooApiClient>()
    private val service = PlanDetectionService(apiClient)

    @Test
    fun `normalizeUrl strips git suffix and protocol`() {
        assertEquals(
            "bitbucket.org/mycompany/myrepo",
            PlanDetectionService.normalizeRepoUrl("ssh://git@bitbucket.org:mycompany/myrepo.git")
        )
        assertEquals(
            "bitbucket.org/mycompany/myrepo",
            PlanDetectionService.normalizeRepoUrl("https://bitbucket.org/mycompany/myrepo.git")
        )
        assertEquals(
            "bitbucket.org/mycompany/myrepo",
            PlanDetectionService.normalizeRepoUrl("git@bitbucket.org:mycompany/myrepo")
        )
    }

    @Test
    fun `autoDetect returns plan key when single match found`() = runTest {
        val plans = listOf(
            BambooPlanDto(key = "PROJ-BUILD", name = "Build"),
            BambooPlanDto(key = "PROJ-DEPLOY", name = "Deploy")
        )
        coEvery { apiClient.getPlans() } returns ApiResult.Success(plans)
        coEvery { apiClient.getPlanSpecs("PROJ-BUILD") } returns ApiResult.Success(
            """
            repositories:
              - my-repo:
                  type: git
                  url: ssh://git@bitbucket.org:mycompany/myrepo.git
            """.trimIndent()
        )
        coEvery { apiClient.getPlanSpecs("PROJ-DEPLOY") } returns ApiResult.Success(
            """
            repositories:
              - other-repo:
                  type: git
                  url: ssh://git@bitbucket.org:mycompany/other-repo.git
            """.trimIndent()
        )

        val result = service.autoDetect("https://bitbucket.org/mycompany/myrepo.git")

        assertTrue(result.isSuccess)
        assertEquals("PROJ-BUILD", (result as ApiResult.Success).data)
    }

    @Test
    fun `autoDetect returns NOT_FOUND when no match`() = runTest {
        coEvery { apiClient.getPlans() } returns ApiResult.Success(emptyList())

        val result = service.autoDetect("https://bitbucket.org/mycompany/myrepo.git")

        assertTrue(result.isError)
    }

    @Test
    fun `search delegates to api client`() = runTest {
        val entities = listOf(
            BambooSearchEntity(key = "PROJ-BUILD", planName = "Build", projectName = "My Project")
        )
        coEvery { apiClient.searchPlans("Build") } returns ApiResult.Success(entities)

        val result = service.search("Build")

        assertTrue(result.isSuccess)
        assertEquals(1, (result as ApiResult.Success).data.size)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew :bamboo:test --tests "com.workflow.orchestrator.bamboo.service.PlanDetectionServiceTest" --no-configuration-cache
```

Expected: FAIL — `PlanDetectionService` does not exist

- [ ] **Step 3: Implement PlanDetectionService**

```kotlin
package com.workflow.orchestrator.bamboo.service

import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.bamboo.api.dto.BambooSearchEntity
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType

class PlanDetectionService(
    private val apiClient: BambooApiClient
) {

    suspend fun autoDetect(gitRemoteUrl: String): ApiResult<String> {
        val normalizedRemote = normalizeRepoUrl(gitRemoteUrl)

        val plansResult = apiClient.getPlans()
        val plans = plansResult.getOrNull() ?: return ApiResult.Error(
            ErrorType.NETWORK_ERROR,
            "Could not fetch plans for auto-detection"
        )

        val matches = mutableListOf<String>()
        for (plan in plans) {
            val specsResult = apiClient.getPlanSpecs(plan.key)
            val specsYaml = specsResult.getOrNull() ?: continue
            val repoUrls = extractRepoUrls(specsYaml)
            if (repoUrls.any { normalizeRepoUrl(it) == normalizedRemote }) {
                matches.add(plan.key)
            }
        }

        return when {
            matches.size == 1 -> ApiResult.Success(matches[0])
            matches.size > 1 -> ApiResult.Error(
                ErrorType.NOT_FOUND,
                "Multiple plans match this repository: ${matches.joinToString()}"
            )
            else -> ApiResult.Error(
                ErrorType.NOT_FOUND,
                "No Bamboo plan found matching repository: $gitRemoteUrl"
            )
        }
    }

    suspend fun search(query: String): ApiResult<List<BambooSearchEntity>> {
        return apiClient.searchPlans(query)
    }

    companion object {
        private val URL_REGEX = Regex("""url:\s+(.+)""")

        fun normalizeRepoUrl(url: String): String {
            var normalized = url.trim()
            // Remove .git suffix
            normalized = normalized.removeSuffix(".git")
            // Remove protocol (https://, ssh://, git://)
            normalized = normalized.replace(Regex("""^(https?|ssh|git)://"""), "")
            // Convert SSH format: git@host:path -> host/path
            normalized = normalized.replace(Regex("""^git@([^:]+):"""), "$1/")
            // Remove trailing slash
            normalized = normalized.trimEnd('/')
            return normalized
        }

        internal fun extractRepoUrls(specsYaml: String): List<String> {
            return URL_REGEX.findAll(specsYaml).map { it.groupValues[1].trim() }.toList()
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew :bamboo:test --tests "com.workflow.orchestrator.bamboo.service.PlanDetectionServiceTest" --no-configuration-cache
```

Expected: 4 tests PASSED

- [ ] **Step 5: Commit**

```bash
git add bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/PlanDetectionService.kt \
       bamboo/src/test/kotlin/com/workflow/orchestrator/bamboo/service/PlanDetectionServiceTest.kt
git commit -m "feat(bamboo): add PlanDetectionService for auto-detect and search"
```

---

### Task 11: BuildMonitorService (TDD)

**Files:**
- Create: `bamboo/src/test/kotlin/com/workflow/orchestrator/bamboo/service/BuildMonitorServiceTest.kt`
- Create: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/BuildMonitorService.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.workflow.orchestrator.bamboo.service

import app.cash.turbine.test
import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.bamboo.api.dto.BambooResultDto
import com.workflow.orchestrator.bamboo.api.dto.BambooStageCollection
import com.workflow.orchestrator.bamboo.api.dto.BambooStageDto
import com.workflow.orchestrator.bamboo.model.BuildStatus
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.model.ApiResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BuildMonitorServiceTest {

    private val apiClient = mockk<BambooApiClient>()
    private val eventBus = EventBus()

    private fun makeResult(state: String, lifeCycle: String, buildNumber: Int = 42): BambooResultDto {
        return BambooResultDto(
            key = "PROJ-BUILD-$buildNumber",
            buildNumber = buildNumber,
            state = state,
            lifeCycleState = lifeCycle,
            buildDurationInSeconds = 120,
            stages = BambooStageCollection(
                size = 2,
                stage = listOf(
                    BambooStageDto(name = "Compile", state = "Successful", lifeCycleState = "Finished"),
                    BambooStageDto(name = "Test", state = state, lifeCycleState = lifeCycle)
                )
            )
        )
    }

    @Test
    fun `pollOnce updates stateFlow with build result`() = runTest {
        val result = makeResult("Successful", "Finished")
        coEvery { apiClient.getLatestResult("PROJ-BUILD", "main") } returns ApiResult.Success(result)

        val service = BuildMonitorService(apiClient, eventBus, this)

        service.stateFlow.test {
            // Initial state
            assertNull(awaitItem())

            service.pollOnce("PROJ-BUILD", "main")

            val state = awaitItem()
            assertNotNull(state)
            assertEquals("PROJ-BUILD", state!!.planKey)
            assertEquals("main", state.branch)
            assertEquals(BuildStatus.SUCCESS, state.overallStatus)
            assertEquals(2, state.stages.size)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pollOnce emits BuildFinished event on status change`() = runTest {
        val successResult = makeResult("Successful", "Finished")
        coEvery { apiClient.getLatestResult("PROJ-BUILD", "main") } returns ApiResult.Success(successResult)

        val service = BuildMonitorService(apiClient, eventBus, this)

        eventBus.events.test {
            service.pollOnce("PROJ-BUILD", "main")

            val event = awaitItem()
            assertTrue(event is WorkflowEvent.BuildFinished)
            val buildEvent = event as WorkflowEvent.BuildFinished
            assertEquals("PROJ-BUILD", buildEvent.planKey)
            assertEquals(WorkflowEvent.BuildEventStatus.SUCCESS, buildEvent.status)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pollOnce does not emit event when status unchanged`() = runTest {
        val result = makeResult("Successful", "Finished")
        coEvery { apiClient.getLatestResult("PROJ-BUILD", "main") } returns ApiResult.Success(result)

        val service = BuildMonitorService(apiClient, eventBus, this)

        // First poll — should emit
        service.pollOnce("PROJ-BUILD", "main")

        eventBus.events.test {
            // Second poll with same result — should NOT emit
            service.pollOnce("PROJ-BUILD", "main")
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pollOnce emits FAILED event on failed build`() = runTest {
        val failedResult = makeResult("Failed", "Finished")
        coEvery { apiClient.getLatestResult("PROJ-BUILD", "main") } returns ApiResult.Success(failedResult)

        val service = BuildMonitorService(apiClient, eventBus, this)

        eventBus.events.test {
            service.pollOnce("PROJ-BUILD", "main")

            val event = awaitItem() as WorkflowEvent.BuildFinished
            assertEquals(WorkflowEvent.BuildEventStatus.FAILED, event.status)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pollOnce handles API error gracefully`() = runTest {
        coEvery { apiClient.getLatestResult("PROJ-BUILD", "main") } returns
            ApiResult.Error(com.workflow.orchestrator.core.model.ErrorType.NETWORK_ERROR, "Connection refused")

        val service = BuildMonitorService(apiClient, eventBus, this)

        service.stateFlow.test {
            assertNull(awaitItem()) // initial null
            service.pollOnce("PROJ-BUILD", "main")
            // State should remain null on error
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew :bamboo:test --tests "com.workflow.orchestrator.bamboo.service.BuildMonitorServiceTest" --no-configuration-cache
```

Expected: FAIL — `BuildMonitorService` does not exist

- [ ] **Step 3: Implement BuildMonitorService**

```kotlin
package com.workflow.orchestrator.bamboo.service

import com.intellij.openapi.Disposable
import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.bamboo.api.dto.BambooResultDto
import com.workflow.orchestrator.bamboo.model.BuildState
import com.workflow.orchestrator.bamboo.model.BuildStatus
import com.workflow.orchestrator.bamboo.model.StageState
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.notifications.WorkflowNotificationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

class BuildMonitorService(
    private val apiClient: BambooApiClient,
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
    private val notificationService: WorkflowNotificationService? = null
) : Disposable {

    private val _stateFlow = MutableStateFlow<BuildState?>(null)
    val stateFlow: StateFlow<BuildState?> = _stateFlow.asStateFlow()

    private var previousBuildNumber: Int? = null
    private var previousStatus: BuildStatus? = null
    private var pollingJob: Job? = null

    fun startPolling(planKey: String, branch: String, intervalMs: Long = 30_000) {
        stopPolling()
        previousBuildNumber = null
        previousStatus = null
        pollingJob = scope.launch {
            while (true) {
                pollOnce(planKey, branch)
                delay(intervalMs)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    fun switchBranch(planKey: String, newBranch: String, intervalMs: Long = 30_000) {
        _stateFlow.value = null
        startPolling(planKey, newBranch, intervalMs)
    }

    override fun dispose() {
        stopPolling()
        scope.cancel()
    }

    suspend fun pollOnce(planKey: String, branch: String) {
        val result = apiClient.getLatestResult(planKey, branch)
        if (result is ApiResult.Success) {
            val dto = result.data
            val buildState = mapToBuildState(dto, planKey, branch)
            _stateFlow.value = buildState

            // Only emit event and notify on terminal state changes
            val isTerminal = buildState.overallStatus == BuildStatus.SUCCESS ||
                buildState.overallStatus == BuildStatus.FAILED
            val statusChanged = dto.buildNumber != previousBuildNumber ||
                buildState.overallStatus != previousStatus

            if (isTerminal && statusChanged) {
                val eventStatus = when (buildState.overallStatus) {
                    BuildStatus.SUCCESS -> WorkflowEvent.BuildEventStatus.SUCCESS
                    else -> WorkflowEvent.BuildEventStatus.FAILED
                }
                eventBus.emit(
                    WorkflowEvent.BuildFinished(
                        planKey = planKey,
                        buildNumber = dto.buildNumber,
                        status = eventStatus
                    )
                )

                // Send notification
                sendBuildNotification(planKey, dto.buildNumber, buildState.overallStatus)
            }

            previousBuildNumber = dto.buildNumber
            previousStatus = buildState.overallStatus
        }
    }

    private fun sendBuildNotification(planKey: String, buildNumber: Int, status: BuildStatus) {
        notificationService ?: return
        when (status) {
            BuildStatus.SUCCESS -> notificationService.notifyInfo(
                WorkflowNotificationService.GROUP_BUILD,
                "Build Passed",
                "$planKey #$buildNumber completed successfully"
            )
            BuildStatus.FAILED -> notificationService.notifyError(
                WorkflowNotificationService.GROUP_BUILD,
                "Build Failed",
                "$planKey #$buildNumber failed. Click to view details."
            )
            else -> {} // No notification for non-terminal states
        }
    }

    private fun mapToBuildState(dto: BambooResultDto, planKey: String, branch: String): BuildState {
        val stages = dto.stages.stage.map { stage ->
            StageState(
                name = stage.name,
                status = BuildStatus.fromBambooState(stage.state, stage.lifeCycleState),
                manual = stage.manual,
                durationMs = stage.buildDurationInSeconds * 1000
            )
        }
        return BuildState(
            planKey = planKey,
            branch = branch,
            buildNumber = dto.buildNumber,
            stages = stages,
            overallStatus = BuildStatus.fromBambooState(dto.state, dto.lifeCycleState),
            lastUpdated = Instant.now()
        )
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew :bamboo:test --tests "com.workflow.orchestrator.bamboo.service.BuildMonitorServiceTest" --no-configuration-cache
```

Expected: 5 tests PASSED

- [ ] **Step 5: Run all bamboo tests**

Run:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew :bamboo:test --no-configuration-cache
```

Expected: All tests PASSED (7 API + 4 parser + 4 detection + 5 monitor = 20 tests)

- [ ] **Step 6: Commit**

```bash
git add bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/BuildMonitorService.kt \
       bamboo/src/test/kotlin/com/workflow/orchestrator/bamboo/service/BuildMonitorServiceTest.kt
git commit -m "feat(bamboo): add BuildMonitorService with polling and event emission"
```

---

## Chunk 4: UI Components

### Task 12: BuildTabProvider + BuildDashboardPanel

**Files:**
- Create: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/BuildTabProvider.kt`
- Create: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/StageListPanel.kt`
- Create: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/StageDetailPanel.kt`
- Create: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/BuildDashboardPanel.kt`

- [ ] **Step 1: Create BuildTabProvider**

```kotlin
package com.workflow.orchestrator.bamboo.ui

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.toolwindow.EmptyStatePanel
import com.workflow.orchestrator.core.toolwindow.WorkflowTabProvider
import javax.swing.JComponent

class BuildTabProvider : WorkflowTabProvider {

    override val tabId: String = "build"
    override val tabTitle: String = "Build"
    override val order: Int = 1

    override fun createPanel(project: Project): JComponent {
        val settings = PluginSettings.getInstance(project)
        return if (!settings.state.bambooUrl.isNullOrBlank()) {
            BuildDashboardPanel(project)
        } else {
            EmptyStatePanel(project, "No builds found.\nConnect to Bamboo in Settings to get started.")
        }
    }
}
```

- [ ] **Step 2: Create StageListPanel**

```kotlin
package com.workflow.orchestrator.bamboo.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.bamboo.model.BuildStatus
import com.workflow.orchestrator.bamboo.model.StageState
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.*

class StageListPanel : JPanel(BorderLayout()) {

    private val listModel = DefaultListModel<StageState>()
    val stageList = JBList(listModel).apply {
        cellRenderer = StageListCellRenderer()
        selectionMode = ListSelectionModel.SINGLE_SELECTION
    }

    var onRunStage: ((StageState) -> Unit)? = null

    init {
        border = JBUI.Borders.empty()
        add(JBScrollPane(stageList), BorderLayout.CENTER)

        // Double-click on manual stage triggers run
        stageList.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) {
                    val index = stageList.locationToIndex(e.point)
                    if (index >= 0) {
                        val stage = listModel.getElementAt(index)
                        if (stage.manual && stage.status != BuildStatus.IN_PROGRESS) {
                            onRunStage?.invoke(stage)
                        }
                    }
                }
            }
        })
    }

    fun updateStages(stages: List<StageState>) {
        val selectedIndex = stageList.selectedIndex
        listModel.clear()
        stages.forEach { listModel.addElement(it) }
        if (selectedIndex in 0 until listModel.size()) {
            stageList.selectedIndex = selectedIndex
        }
    }

    private inner class StageListCellRenderer : ColoredListCellRenderer<StageState>() {

        override fun customizeCellRenderer(
            list: JList<out StageState>,
            value: StageState?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean
        ) {
            value ?: return
            border = JBUI.Borders.empty(4, 8)

            icon = when (value.status) {
                BuildStatus.SUCCESS -> AllIcons.RunConfigurations.TestPassed
                BuildStatus.FAILED -> AllIcons.RunConfigurations.TestFailed
                BuildStatus.IN_PROGRESS -> AnimatedIcon.Default()
                BuildStatus.PENDING -> AllIcons.RunConfigurations.TestNotRan
                BuildStatus.UNKNOWN -> AllIcons.RunConfigurations.TestNotRan
            }

            append(value.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)

            // Duration
            val duration = value.durationMs?.let { formatDuration(it) } ?: "--"
            append("  $duration", SimpleTextAttributes.GRAYED_ATTRIBUTES)

            // Manual indicator
            if (value.manual && value.status != BuildStatus.IN_PROGRESS) {
                append("  [Run]", SimpleTextAttributes.LINK_ATTRIBUTES)
            }
        }
    }
}

internal fun formatDuration(ms: Long): String {
    if (ms <= 0) return "--"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
}
```

- [ ] **Step 3: Create StageDetailPanel**

```kotlin
package com.workflow.orchestrator.bamboo.ui

import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.bamboo.model.BuildError
import com.workflow.orchestrator.bamboo.model.ErrorSeverity
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import javax.swing.*
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

class StageDetailPanel : JPanel(BorderLayout()) {

    private val logPane = JTextPane().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scaleFontSize(12f).toInt())
        border = JBUI.Borders.empty(8)
    }

    private val errorListModel = DefaultListModel<BuildError>()
    private val errorList = JList(errorListModel).apply {
        cellRenderer = ErrorListCellRenderer()
    }

    private val tabbedPane = JBTabbedPane().apply {
        addTab("Log", JBScrollPane(logPane))
        addTab("Errors", JBScrollPane(errorList))
    }

    init {
        border = JBUI.Borders.empty()
        add(tabbedPane, BorderLayout.CENTER)
    }

    fun showLog(log: String, errors: List<BuildError>) {
        // Log tab with error highlighting
        val doc = logPane.styledDocument
        doc.remove(0, doc.length)
        for (line in log.lines()) {
            val attrs = SimpleAttributeSet()
            when {
                line.contains("[ERROR]") -> {
                    StyleConstants.setForeground(attrs, Color(0xCC, 0x33, 0x33))
                    StyleConstants.setBold(attrs, true)
                }
                line.contains("[WARNING]") -> {
                    StyleConstants.setForeground(attrs, Color(0xCC, 0x99, 0x33))
                }
            }
            doc.insertString(doc.length, line + "\n", attrs)
        }
        logPane.caretPosition = 0

        // Errors tab
        errorListModel.clear()
        errors.forEach { errorListModel.addElement(it) }

        // Switch to Errors tab if there are errors
        if (errors.any { it.severity == ErrorSeverity.ERROR }) {
            tabbedPane.selectedIndex = 1
        }
    }

    fun showEmpty() {
        logPane.text = ""
        errorListModel.clear()
    }

    private class ErrorListCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>, value: Any?, index: Int, selected: Boolean, hasFocus: Boolean
        ): Component {
            super.getListCellRendererComponent(list, value, index, selected, hasFocus)
            val error = value as? BuildError ?: return this
            border = JBUI.Borders.empty(4, 8)

            val prefix = when (error.severity) {
                ErrorSeverity.ERROR -> "ERROR"
                ErrorSeverity.WARNING -> "WARN"
            }
            val location = if (error.filePath != null) {
                val line = error.lineNumber?.let { ":$it" } ?: ""
                " ${error.filePath}$line"
            } else ""

            text = "[$prefix]$location — ${error.message}"

            if (!selected) {
                foreground = when (error.severity) {
                    ErrorSeverity.ERROR -> Color(0xCC, 0x33, 0x33)
                    ErrorSeverity.WARNING -> Color(0xCC, 0x99, 0x33)
                }
            }
            return this
        }
    }
}
```

- [ ] **Step 4: Create BuildDashboardPanel**

```kotlin
package com.workflow.orchestrator.bamboo.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.progress.runBackgroundableTask
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.BranchChangeListener
import com.intellij.ui.JBSplitter
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.bamboo.model.BuildStatus
import com.workflow.orchestrator.bamboo.service.BuildLogParser
import com.workflow.orchestrator.bamboo.service.BuildMonitorService
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.notifications.WorkflowNotificationService
import com.workflow.orchestrator.core.settings.PluginSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import git4idea.repo.GitRepositoryManager
import java.awt.BorderLayout
import javax.swing.JLabel
import javax.swing.JPanel

class BuildDashboardPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val settings = PluginSettings.getInstance(project)
    private val credentialStore = CredentialStore()
    private val apiClient = BambooApiClient(
        baseUrl = settings.state.bambooUrl.orEmpty().trimEnd('/'),
        tokenProvider = { credentialStore.getToken(ServiceType.BAMBOO) }
    )
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val eventBus = project.getService(EventBus::class.java)
    private val notificationService = WorkflowNotificationService.getInstance(project)
    private val monitorService = BuildMonitorService(apiClient, eventBus, scope, notificationService)

    private val stageListPanel = StageListPanel()
    private val stageDetailPanel = StageDetailPanel()

    private val splitter = JBSplitter(false, 0.35f).apply {
        setSplitterProportionKey("workflow.build.splitter")
        firstComponent = stageListPanel
        secondComponent = stageDetailPanel
    }

    private val headerLabel = JLabel("Build: loading...")
    private val statusLabel = JLabel("")

    init {
        border = JBUI.Borders.empty()

        // Toolbar
        val toolbar = createToolbar()
        val headerPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 8)
            add(headerLabel, BorderLayout.WEST)
            add(toolbar, BorderLayout.EAST)
        }
        add(headerPanel, BorderLayout.NORTH)

        // Splitter (master-detail)
        add(splitter, BorderLayout.CENTER)

        // Status bar
        val statusPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(2, 8)
            add(statusLabel, BorderLayout.WEST)
        }
        add(statusPanel, BorderLayout.SOUTH)

        // Selection listener — load log for selected stage
        stageListPanel.stageList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                val selected = stageListPanel.stageList.selectedValue
                if (selected != null && selected.status == BuildStatus.FAILED) {
                    loadBuildLog()
                } else {
                    stageDetailPanel.showEmpty()
                }
            }
        }

        // Manual stage run handler — triggered by double-click on manual stages
        stageListPanel.onRunStage = { stage -> triggerManualStage(stage.name) }

        // Subscribe to branch changes — switch polling when user changes branch
        project.messageBus.connect(this).subscribe(
            BranchChangeListener.VCS_BRANCH_CHANGED,
            BranchChangeListener { _, newBranch ->
                val planKey = settings.state.bambooPlanKey.orEmpty()
                if (planKey.isNotBlank()) {
                    val interval = settings.state.buildPollIntervalSeconds.toLong() * 1000
                    monitorService.switchBranch(planKey, newBranch, interval)
                    invokeLater { headerLabel.text = "Plan: $planKey / $newBranch" }
                }
            }
        )

        // Observe state changes
        scope.launch {
            monitorService.stateFlow.collect { state ->
                invokeLater {
                    if (state != null) {
                        headerLabel.text = "Plan: ${state.planKey} / ${state.branch}  #${state.buildNumber}"
                        stageListPanel.updateStages(state.stages)
                        statusLabel.text = "${state.overallStatus} — ${formatDuration(state.stages.sumOf { it.durationMs ?: 0 })}"
                    }
                }
            }
        }

        // Start polling
        startMonitoring()
    }

    override fun dispose() {
        monitorService.dispose()
    }

    private fun startMonitoring() {
        val planKey = settings.state.bambooPlanKey.orEmpty()
        if (planKey.isBlank()) {
            headerLabel.text = "No Bamboo plan configured. Set plan key in Settings."
            return
        }

        val branch = getCurrentBranch() ?: "master"
        val interval = settings.state.buildPollIntervalSeconds.toLong() * 1000
        headerLabel.text = "Plan: $planKey / $branch"
        monitorService.startPolling(planKey, branch, interval)
    }

    private fun getCurrentBranch(): String? {
        val repos = GitRepositoryManager.getInstance(project).repositories
        return repos.firstOrNull()?.currentBranchName
    }

    private fun createToolbar(): javax.swing.JComponent {
        val group = DefaultActionGroup()

        group.add(object : AnAction("Refresh", "Force poll build status now", null) {
            override fun actionPerformed(e: AnActionEvent) {
                runBackgroundableTask("Polling Build Status", project, false) {
                    val planKey = settings.state.bambooPlanKey.orEmpty()
                    val branch = getCurrentBranch() ?: "master"
                    runBlocking { monitorService.pollOnce(planKey, branch) }
                }
            }
        })

        val actionToolbar = ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.TOOLBAR, group, true)
        actionToolbar.targetComponent = this
        return actionToolbar.component
    }

    private fun loadBuildLog() {
        val state = monitorService.stateFlow.value ?: return
        val resultKey = "${state.planKey}-${state.buildNumber}"

        runBackgroundableTask("Loading Build Log", project, false) {
            val logResult = runBlocking { apiClient.getBuildLog(resultKey) }
            invokeLater {
                when (logResult) {
                    is ApiResult.Success -> {
                        val errors = BuildLogParser.parse(logResult.data)
                        stageDetailPanel.showLog(logResult.data, errors)
                    }
                    is ApiResult.Error -> {
                        stageDetailPanel.showLog("Failed to load log: ${logResult.message}", emptyList())
                    }
                }
            }
        }
    }

    private fun triggerManualStage(stageName: String) {
        val planKey = settings.state.bambooPlanKey.orEmpty()
        ManualStageDialog(project, apiClient, planKey, stageName).show()
    }
}
```

- [ ] **Step 5: Verify compilation**

Run:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew :bamboo:classes --no-configuration-cache
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/BuildTabProvider.kt \
       bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/StageListPanel.kt \
       bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/StageDetailPanel.kt \
       bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/BuildDashboardPanel.kt
git commit -m "feat(bamboo): add Build Dashboard panel with stage list and log viewer"
```

---

### Task 13: ManualStageDialog

**Files:**
- Create: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/ManualStageDialog.kt`

- [ ] **Step 1: Create ManualStageDialog**

```kotlin
package com.workflow.orchestrator.bamboo.ui

import com.intellij.openapi.progress.runBackgroundableTask
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.bamboo.api.dto.BambooPlanVariableDto
import com.workflow.orchestrator.core.model.ApiResult
import kotlinx.coroutines.runBlocking
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JComponent
import javax.swing.JPanel

class ManualStageDialog(
    private val project: Project,
    private val apiClient: BambooApiClient,
    private val planKey: String,
    private val stageName: String
) : DialogWrapper(project) {

    private val variableEditors = mutableMapOf<String, JComponent>()
    private var variables: List<BambooPlanVariableDto> = emptyList()

    init {
        title = "Run Stage: $stageName"
        init()
        // Load variables asynchronously after dialog is shown
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            val result = kotlinx.coroutines.runBlocking { apiClient.getVariables(planKey) }
            if (result is ApiResult.Success) {
                variables = result.data
                com.intellij.openapi.application.invokeLater { rebuildForm() }
            }
        }
    }

    private fun rebuildForm() {
        contentPanel?.removeAll()
        contentPanel?.add(createCenterPanel())
        contentPanel?.revalidate()
        contentPanel?.repaint()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        panel.border = JBUI.Borders.empty(8)

        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.WEST
            insets = JBUI.insets(4)
        }

        variables.forEachIndexed { index, variable ->
            gbc.gridy = index

            // Label
            gbc.gridx = 0
            gbc.weightx = 0.0
            panel.add(JBLabel("${variable.name}:"), gbc)

            // Editor — checkbox for boolean-like values, text field otherwise
            gbc.gridx = 1
            gbc.weightx = 1.0
            val editor: JComponent = if (variable.value in listOf("true", "false")) {
                JBCheckBox().apply {
                    isSelected = variable.value == "true"
                }
            } else {
                JBTextField(variable.value, 20)
            }
            variableEditors[variable.name] = editor
            panel.add(editor, gbc)
        }

        if (variables.isEmpty()) {
            gbc.gridy = 0
            gbc.gridx = 0
            gbc.gridwidth = 2
            panel.add(JBLabel("No build variables configured for this plan."), gbc)
        }

        return panel
    }

    override fun doOKAction() {
        val vars = variableEditors.mapValues { (_, editor) ->
            when (editor) {
                is JBCheckBox -> editor.isSelected.toString()
                is JBTextField -> editor.text
                else -> ""
            }
        }

        runBackgroundableTask("Triggering $stageName", project, false) {
            runBlocking {
                apiClient.triggerBuild(planKey, vars, stageName)
            }
        }

        super.doOKAction()
    }
}
```

- [ ] **Step 2: Verify compilation**

Run:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew :bamboo:classes --no-configuration-cache
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/ManualStageDialog.kt
git commit -m "feat(bamboo): add ManualStageDialog for triggering stages with variables"
```

---

## Chunk 5: Status Bar Widget & Plugin Registration

### Task 14: BuildStatusBarWidget

**Files:**
- Create: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/BuildStatusBarWidget.kt`

- [ ] **Step 1: Create BuildStatusBarWidget**

```kotlin
package com.workflow.orchestrator.bamboo.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.impl.status.EditorBasedWidget
import com.intellij.util.Consumer
import com.workflow.orchestrator.bamboo.model.BuildStatus
import com.workflow.orchestrator.bamboo.model.StageState
import com.workflow.orchestrator.core.settings.PluginSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.awt.event.MouseEvent
import javax.swing.Icon

class BuildStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = "WorkflowBuildStatusBar"
    override fun getDisplayName(): String = "Workflow Build Status"
    override fun isAvailable(project: Project): Boolean {
        return !PluginSettings.getInstance(project).state.bambooUrl.isNullOrBlank()
    }

    override fun createWidget(project: Project): StatusBarWidget {
        return BuildStatusBarWidgetImpl(project)
    }
}

private class BuildStatusBarWidgetImpl(project: Project) :
    EditorBasedWidget(project), StatusBarWidget.TextPresentation {

    private var displayText: String = "Build: --"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun ID(): String = "WorkflowBuildStatusBar"

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun getText(): String = displayText

    override fun getAlignment(): Float = 0f

    override fun getTooltipText(): String = "Build monitoring active"

    override fun getClickConsumer(): Consumer<MouseEvent>? = Consumer {
        // Open the Build tab in the Workflow tool window
        val toolWindow = com.intellij.openapi.wm.ToolWindowManager
            .getInstance(project).getToolWindow("Workflow")
        toolWindow?.show {
            val content = toolWindow.contentManager.findContent("Build")
            if (content != null) {
                toolWindow.contentManager.setSelectedContent(content)
            }
        }
    }

    override fun install(statusBar: StatusBar) {
        super.install(statusBar)
        val eventBus = project.getService(com.workflow.orchestrator.core.events.EventBus::class.java)
        scope.launch {
            eventBus.events.collect { event ->
                if (event is com.workflow.orchestrator.core.events.WorkflowEvent.BuildFinished) {
                    val indicator = when (event.status) {
                        com.workflow.orchestrator.core.events.WorkflowEvent.BuildEventStatus.SUCCESS -> "✓"
                        com.workflow.orchestrator.core.events.WorkflowEvent.BuildEventStatus.FAILED -> "✗"
                    }
                    displayText = "${event.planKey}: $indicator #${event.buildNumber}"
                    statusBar.updateWidget(ID())
                }
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

Run:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew :bamboo:classes --no-configuration-cache
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/BuildStatusBarWidget.kt
git commit -m "feat(bamboo): add Build status bar widget"
```

---

### Task 15: Plugin Registration (plugin.xml)

**Files:**
- Modify: `core/src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Add bamboo extensions to plugin.xml**

In the `<extensions defaultExtensionNs="com.intellij">` block, add after the existing `statusBarWidgetFactory`:

```xml
        <!-- Bamboo Build Status Bar Widget -->
        <statusBarWidgetFactory id="WorkflowBuildStatusBar"
            implementation="com.workflow.orchestrator.bamboo.ui.BuildStatusBarWidgetFactory"/>
```

In the `<extensions defaultExtensionNs="com.workflow.orchestrator">` block, add after the existing `tabProvider`:

```xml
        <tabProvider implementation="com.workflow.orchestrator.bamboo.ui.BuildTabProvider"/>
```

- [ ] **Step 2: Verify full project compiles**

Run:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew classes --no-configuration-cache
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Run all tests**

Run:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew test --no-configuration-cache
```

Expected: All tests PASSED (core: 33 + bamboo: ~20 + jira: existing)

- [ ] **Step 4: Commit**

```bash
git add core/src/main/resources/META-INF/plugin.xml
git commit -m "feat: register bamboo services and widgets in plugin.xml"
```

---

## Chunk 6: Integration Verification

### Task 16: Full Build & Verification

- [ ] **Step 1: Run full project build**

Run:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew clean build --no-configuration-cache
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run plugin verification**

Run:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew verifyPlugin --no-configuration-cache
```

Expected: No verification errors

- [ ] **Step 3: Verify test count**

Run:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew test --no-configuration-cache 2>&1 | tail -5
```

Expected output should show all tests passed with 0 failures.

- [ ] **Step 4: Final commit (if any fixes needed)**

```bash
git add -A
git commit -m "fix: address any issues found during final verification"
```
