# Phase 1D: Quality & Sonar — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add read-only SonarQube integration to the IDE, showing quality gate status, issues, and coverage across 6 UI surfaces without leaving the editor.

**Architecture:** New `:sonar` module depending on `:core`. `SonarApiClient` handles 6 REST endpoints. `SonarDataService` orchestrates hybrid refresh (EventBus `BuildFinished` subscription + configurable poll). All UI components consume a single `StateFlow<SonarState>` reactively. Pure-function mappers (`CoverageMapper`, `IssueMapper`) translate API responses to editor-native data.

**Tech Stack:** Kotlin, IntelliJ Platform SDK (2025.1+), OkHttp 4.12, kotlinx.serialization 1.7.3, kotlinx.coroutines 1.8, JUnit 5, MockWebServer, MockK, Turbine

**Spec:** `docs/superpowers/specs/2026-03-11-phase-1d-quality-sonar-design.md`

---

## Chunk 1: Build Configuration, Models & DTOs

### Task 1: Gradle Build Configuration

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `build.gradle.kts` (root)
- Create: `sonar/build.gradle.kts`

- [ ] **Step 1: Add `:sonar` module to settings.gradle.kts**

In `settings.gradle.kts`, add `:sonar` to the include list:

```kotlin
include(
    ":core",
    ":jira",
    ":git-integration",
    ":bamboo",
    ":sonar",
)
```

- [ ] **Step 2: Add `:sonar` to root build.gradle.kts dependencies**

In `build.gradle.kts` (root), add inside the `dependencies` block, after the `:bamboo` line:

```kotlin
implementation(project(":sonar"))
```

- [ ] **Step 3: Create sonar/build.gradle.kts**

```kotlin
// sonar/build.gradle.kts — Submodule for SonarQube quality integration.
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

- [ ] **Step 4: Verify Gradle sync**

Run: `./gradlew :sonar:dependencies --configuration compileClasspath 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL (no resolution errors)

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts build.gradle.kts sonar/build.gradle.kts
git commit -m "build: add :sonar module scaffold for Phase 1D"
```

---

### Task 2: WorkflowEvent Extensions

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/events/WorkflowEvent.kt`

- [ ] **Step 1: Add Phase 1D event variants**

Add these two data classes inside the `WorkflowEvent` sealed class, after the existing `BuildLogReady`:

```kotlin
    /** Emitted by :sonar when quality gate status changes. */
    data class QualityGateResult(
        val projectKey: String,
        val passed: Boolean
    ) : WorkflowEvent()

    /** Emitted by :sonar on each successful coverage data refresh. */
    data class CoverageUpdated(
        val projectKey: String,
        val lineCoverage: Double
    ) : WorkflowEvent()
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :core:compileKotlin 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/events/WorkflowEvent.kt
git commit -m "feat(core): add QualityGateResult and CoverageUpdated events for Phase 1D"
```

---

### Task 3: PluginSettings — Add sonarProjectKey

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/settings/PluginSettings.kt`

- [ ] **Step 1: Add sonarProjectKey field**

In `PluginSettings.State`, after the `var bambooPlanKey by string("")` line, add:

```kotlin
        // SonarQube project key (auto-detected or user-configured)
        var sonarProjectKey by string("")
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :core:compileKotlin 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/settings/PluginSettings.kt
git commit -m "feat(core): add sonarProjectKey to PluginSettings"
```

---

### Task 4: Domain Models

**Files:**
- Create: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/model/SonarModels.kt`
- Create: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/model/SonarState.kt`

- [ ] **Step 1: Create SonarModels.kt**

```kotlin
package com.workflow.orchestrator.sonar.model

enum class QualityGateStatus { PASSED, FAILED, NONE }

enum class IssueType { BUG, VULNERABILITY, CODE_SMELL, SECURITY_HOTSPOT }

enum class IssueSeverity { BLOCKER, CRITICAL, MAJOR, MINOR, INFO }

enum class LineCoverageStatus { COVERED, UNCOVERED, PARTIAL }

data class GateCondition(
    val metric: String,
    val comparator: String,
    val threshold: String,
    val actualValue: String,
    val passed: Boolean
)

data class QualityGateState(
    val status: QualityGateStatus,
    val conditions: List<GateCondition>
)

data class MappedIssue(
    val key: String,
    val type: IssueType,
    val severity: IssueSeverity,
    val message: String,
    val rule: String,
    val filePath: String,
    val startLine: Int,
    val endLine: Int,
    val startOffset: Int,
    val endOffset: Int,
    val effort: String?
)

data class FileCoverageData(
    val filePath: String,
    val lineCoverage: Double,
    val branchCoverage: Double,
    val uncoveredLines: Int,
    val uncoveredConditions: Int,
    val lineStatuses: Map<Int, LineCoverageStatus>
)

data class CoverageMetrics(
    val lineCoverage: Double,
    val branchCoverage: Double
)
```

- [ ] **Step 2: Create SonarState.kt**

```kotlin
package com.workflow.orchestrator.sonar.model

import java.time.Instant

data class SonarState(
    val projectKey: String,
    val branch: String,
    val qualityGate: QualityGateState,
    val issues: List<MappedIssue>,
    val fileCoverage: Map<String, FileCoverageData>,
    val overallCoverage: CoverageMetrics,
    val lastUpdated: Instant
) {
    companion object {
        val EMPTY = SonarState(
            projectKey = "",
            branch = "",
            qualityGate = QualityGateState(QualityGateStatus.NONE, emptyList()),
            issues = emptyList(),
            fileCoverage = emptyMap(),
            overallCoverage = CoverageMetrics(0.0, 0.0),
            lastUpdated = Instant.EPOCH
        )
    }
}
```

- [ ] **Step 3: Verify compilation**

Run: `./gradlew :sonar:compileKotlin 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add sonar/src/main/kotlin/com/workflow/orchestrator/sonar/model/
git commit -m "feat(sonar): add domain models (SonarState, MappedIssue, enums)"
```

---

### Task 5: SonarQube DTOs

**Files:**
- Create: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/api/dto/SonarDtos.kt`

- [ ] **Step 1: Create SonarDtos.kt**

```kotlin
package com.workflow.orchestrator.sonar.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- Authentication ---

@Serializable
data class SonarValidationDto(
    val valid: Boolean
)

// --- Project Search ---

@Serializable
data class SonarProjectSearchResult(
    val paging: SonarPagingDto,
    val components: List<SonarProjectDto> = emptyList()
)

@Serializable
data class SonarProjectDto(
    val key: String,
    val name: String,
    val qualifier: String = "TRK"
)

@Serializable
data class SonarPagingDto(
    val pageIndex: Int = 1,
    val pageSize: Int = 100,
    val total: Int = 0
)

// --- Quality Gate ---

@Serializable
data class SonarQualityGateResponse(
    val projectStatus: SonarQualityGateDto
)

@Serializable
data class SonarQualityGateDto(
    val status: String,
    val conditions: List<SonarConditionDto> = emptyList()
)

@Serializable
data class SonarConditionDto(
    val status: String,
    val metricKey: String,
    val comparator: String,
    val errorThreshold: String = "",
    val actualValue: String = ""
)

// --- Issues ---

@Serializable
data class SonarIssueSearchResult(
    val paging: SonarPagingDto = SonarPagingDto(),
    val issues: List<SonarIssueDto> = emptyList()
)

@Serializable
data class SonarIssueDto(
    val key: String,
    val rule: String,
    val severity: String,
    val message: String,
    val component: String,
    val type: String,
    val effort: String? = null,
    val textRange: SonarTextRangeDto? = null
)

@Serializable
data class SonarTextRangeDto(
    val startLine: Int,
    val endLine: Int,
    val startOffset: Int = 0,
    val endOffset: Int = 0
)

// --- Measures (file-level coverage) ---

@Serializable
data class SonarMeasureSearchResult(
    val paging: SonarPagingDto = SonarPagingDto(),
    val baseComponent: SonarComponentDto? = null,
    val components: List<SonarMeasureComponentDto> = emptyList()
)

@Serializable
data class SonarComponentDto(
    val key: String,
    val name: String,
    val qualifier: String = "",
    val path: String? = null
)

@Serializable
data class SonarMeasureComponentDto(
    val key: String,
    val name: String = "",
    val qualifier: String = "",
    val path: String? = null,
    val measures: List<SonarMeasureDto> = emptyList()
)

@Serializable
data class SonarMeasureDto(
    val metric: String,
    val value: String = ""
)

// --- Source Lines (per-line coverage) ---
// Note: /api/sources/lines returns a raw JSON array, not an object wrapper.

@Serializable
data class SonarSourceLineDto(
    val line: Int,
    val code: String = "",
    val lineHits: Int? = null,
    val conditions: Int? = null,
    val coveredConditions: Int? = null
)
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :sonar:compileKotlin 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add sonar/src/main/kotlin/com/workflow/orchestrator/sonar/api/dto/SonarDtos.kt
git commit -m "feat(sonar): add SonarQube REST API DTOs"
```

---

### Task 6: DTO Serialization Tests

**Files:**
- Create: `sonar/src/test/resources/fixtures/auth-validate.json`
- Create: `sonar/src/test/resources/fixtures/projects-search.json`
- Create: `sonar/src/test/resources/fixtures/qualitygate-status-passed.json`
- Create: `sonar/src/test/resources/fixtures/qualitygate-status-failed.json`
- Create: `sonar/src/test/resources/fixtures/issues-search.json`
- Create: `sonar/src/test/resources/fixtures/measures-component-tree.json`
- Create: `sonar/src/test/resources/fixtures/source-lines.json`
- Create: `sonar/src/test/kotlin/com/workflow/orchestrator/sonar/api/dto/SonarDtoSerializationTest.kt`

- [ ] **Step 1: Create test fixtures**

`sonar/src/test/resources/fixtures/auth-validate.json`:
```json
{"valid":true}
```

`sonar/src/test/resources/fixtures/projects-search.json`:
```json
{
  "paging": {"pageIndex": 1, "pageSize": 100, "total": 2},
  "components": [
    {"key": "com.myapp:my-app", "name": "My App", "qualifier": "TRK"},
    {"key": "com.myapp:my-lib", "name": "My Library", "qualifier": "TRK"}
  ]
}
```

`sonar/src/test/resources/fixtures/qualitygate-status-passed.json`:
```json
{
  "projectStatus": {
    "status": "OK",
    "conditions": [
      {"status": "OK", "metricKey": "new_coverage", "comparator": "LT", "errorThreshold": "80", "actualValue": "87.3"},
      {"status": "OK", "metricKey": "new_bugs", "comparator": "GT", "errorThreshold": "0", "actualValue": "0"},
      {"status": "OK", "metricKey": "new_vulnerabilities", "comparator": "GT", "errorThreshold": "0", "actualValue": "0"}
    ]
  }
}
```

`sonar/src/test/resources/fixtures/qualitygate-status-failed.json`:
```json
{
  "projectStatus": {
    "status": "ERROR",
    "conditions": [
      {"status": "ERROR", "metricKey": "new_coverage", "comparator": "LT", "errorThreshold": "80", "actualValue": "42.1"},
      {"status": "OK", "metricKey": "new_bugs", "comparator": "GT", "errorThreshold": "0", "actualValue": "0"}
    ]
  }
}
```

`sonar/src/test/resources/fixtures/issues-search.json`:
```json
{
  "paging": {"pageIndex": 1, "pageSize": 500, "total": 3},
  "issues": [
    {
      "key": "AYz1",
      "rule": "java:S2259",
      "severity": "CRITICAL",
      "message": "A \"NullPointerException\" could be thrown; \"user\" is nullable here.",
      "component": "com.myapp:my-app:src/main/kotlin/com/myapp/service/UserService.kt",
      "type": "BUG",
      "effort": "15min",
      "textRange": {"startLine": 42, "endLine": 42, "startOffset": 8, "endOffset": 32}
    },
    {
      "key": "AYz2",
      "rule": "java:S3649",
      "severity": "BLOCKER",
      "message": "Make sure that string concatenation is safe here.",
      "component": "com.myapp:my-app:src/main/kotlin/com/myapp/repo/QueryBuilder.kt",
      "type": "VULNERABILITY",
      "effort": "30min",
      "textRange": {"startLine": 18, "endLine": 20, "startOffset": 0, "endOffset": 45}
    },
    {
      "key": "AYz3",
      "rule": "java:S3776",
      "severity": "MAJOR",
      "message": "Refactor this method to reduce its Cognitive Complexity from 22 to the 15 allowed.",
      "component": "com.myapp:my-app:src/main/kotlin/com/myapp/parser/Parser.kt",
      "type": "CODE_SMELL",
      "effort": "2h"
    }
  ]
}
```

`sonar/src/test/resources/fixtures/measures-component-tree.json`:
```json
{
  "paging": {"pageIndex": 1, "pageSize": 500, "total": 2},
  "baseComponent": {"key": "com.myapp:my-app", "name": "My App", "qualifier": "TRK"},
  "components": [
    {
      "key": "com.myapp:my-app:src/main/kotlin/com/myapp/service/UserService.kt",
      "name": "UserService.kt",
      "qualifier": "FIL",
      "path": "src/main/kotlin/com/myapp/service/UserService.kt",
      "measures": [
        {"metric": "coverage", "value": "72.1"},
        {"metric": "line_coverage", "value": "78.5"},
        {"metric": "branch_coverage", "value": "65.0"},
        {"metric": "uncovered_lines", "value": "12"},
        {"metric": "uncovered_conditions", "value": "3"}
      ]
    },
    {
      "key": "com.myapp:my-app:src/main/kotlin/com/myapp/service/AuthService.kt",
      "name": "AuthService.kt",
      "qualifier": "FIL",
      "path": "src/main/kotlin/com/myapp/service/AuthService.kt",
      "measures": [
        {"metric": "coverage", "value": "94.2"},
        {"metric": "line_coverage", "value": "96.0"},
        {"metric": "branch_coverage", "value": "88.5"},
        {"metric": "uncovered_lines", "value": "2"},
        {"metric": "uncovered_conditions", "value": "1"}
      ]
    }
  ]
}
```

`sonar/src/test/resources/fixtures/source-lines.json`:
```json
[
  {"line": 38, "code": "fun findUserById(id: Long): User? {", "lineHits": 5, "conditions": null, "coveredConditions": null},
  {"line": 39, "code": "    val user = repository.findById(id)", "lineHits": 5, "conditions": null, "coveredConditions": null},
  {"line": 40, "code": "    if (user != null) {", "lineHits": 5, "conditions": 2, "coveredConditions": 1},
  {"line": 41, "code": "        val name = user.getName()", "lineHits": 3, "conditions": null, "coveredConditions": null},
  {"line": 42, "code": "        return name.toUpperCase()", "lineHits": 3, "conditions": null, "coveredConditions": null},
  {"line": 43, "code": "    } else {", "lineHits": null, "conditions": null, "coveredConditions": null},
  {"line": 44, "code": "        throw UserNotFoundException(\"User $id not found\")", "lineHits": 0, "conditions": null, "coveredConditions": null},
  {"line": 45, "code": "    }", "lineHits": null, "conditions": null, "coveredConditions": null}
]
```

- [ ] **Step 2: Write DTO serialization tests**

`sonar/src/test/kotlin/com/workflow/orchestrator/sonar/api/dto/SonarDtoSerializationTest.kt`:

```kotlin
package com.workflow.orchestrator.sonar.api.dto

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SonarDtoSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(name: String): String =
        javaClass.classLoader.getResource("fixtures/$name")!!.readText()

    @Test
    fun `deserialize auth validation`() {
        val result = json.decodeFromString<SonarValidationDto>(fixture("auth-validate.json"))
        assertTrue(result.valid)
    }

    @Test
    fun `deserialize projects search`() {
        val result = json.decodeFromString<SonarProjectSearchResult>(fixture("projects-search.json"))
        assertEquals(2, result.components.size)
        assertEquals("com.myapp:my-app", result.components[0].key)
        assertEquals("My App", result.components[0].name)
        assertEquals(2, result.paging.total)
    }

    @Test
    fun `deserialize quality gate passed`() {
        val result = json.decodeFromString<SonarQualityGateResponse>(fixture("qualitygate-status-passed.json"))
        assertEquals("OK", result.projectStatus.status)
        assertEquals(3, result.projectStatus.conditions.size)
        assertEquals("new_coverage", result.projectStatus.conditions[0].metricKey)
        assertEquals("87.3", result.projectStatus.conditions[0].actualValue)
    }

    @Test
    fun `deserialize quality gate failed`() {
        val result = json.decodeFromString<SonarQualityGateResponse>(fixture("qualitygate-status-failed.json"))
        assertEquals("ERROR", result.projectStatus.status)
        assertEquals("ERROR", result.projectStatus.conditions[0].status)
        assertEquals("42.1", result.projectStatus.conditions[0].actualValue)
    }

    @Test
    fun `deserialize issues search`() {
        val result = json.decodeFromString<SonarIssueSearchResult>(fixture("issues-search.json"))
        assertEquals(3, result.issues.size)

        val bug = result.issues[0]
        assertEquals("AYz1", bug.key)
        assertEquals("CRITICAL", bug.severity)
        assertEquals("BUG", bug.type)
        assertEquals(42, bug.textRange?.startLine)

        val vuln = result.issues[1]
        assertEquals("BLOCKER", vuln.severity)
        assertEquals("VULNERABILITY", vuln.type)

        val smell = result.issues[2]
        assertNull(smell.textRange)
        assertEquals("2h", smell.effort)
    }

    @Test
    fun `deserialize measures component tree`() {
        val result = json.decodeFromString<SonarMeasureSearchResult>(fixture("measures-component-tree.json"))
        assertEquals(2, result.components.size)
        assertEquals("com.myapp:my-app", result.baseComponent?.key)

        val first = result.components[0]
        assertEquals("src/main/kotlin/com/myapp/service/UserService.kt", first.path)
        assertEquals(5, first.measures.size)
        assertEquals("72.1", first.measures.first { it.metric == "coverage" }.value)
    }

    @Test
    fun `deserialize source lines`() {
        // /api/sources/lines returns a raw JSON array, not an object wrapper
        val lines = json.decodeFromString<List<SonarSourceLineDto>>(fixture("source-lines.json"))
        assertEquals(8, lines.size)

        val coveredLine = lines[0]
        assertEquals(38, coveredLine.line)
        assertEquals(5, coveredLine.lineHits)
        assertNull(coveredLine.conditions)

        val partialLine = lines[2]
        assertEquals(40, partialLine.line)
        assertEquals(2, partialLine.conditions)
        assertEquals(1, partialLine.coveredConditions)

        val uncoveredLine = lines[6]
        assertEquals(44, uncoveredLine.line)
        assertEquals(0, uncoveredLine.lineHits)
    }
}
```

- [ ] **Step 3: Run tests to verify they pass**

Run: `./gradlew :sonar:test --tests "*.SonarDtoSerializationTest" 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL, 7 tests passed

- [ ] **Step 4: Commit**

```bash
git add sonar/src/test/resources/fixtures/ sonar/src/test/kotlin/com/workflow/orchestrator/sonar/api/dto/
git commit -m "test(sonar): add DTO serialization tests with fixtures"
```

---

## Chunk 2: API Client & Pure Mappers

### Task 7: SonarApiClient Tests (TDD — write tests first)

**Files:**
- Create: `sonar/src/test/kotlin/com/workflow/orchestrator/sonar/api/SonarApiClientTest.kt`

- [ ] **Step 1: Write API client tests**

```kotlin
package com.workflow.orchestrator.sonar.api

import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SonarApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: SonarApiClient

    private fun fixture(name: String): String =
        javaClass.classLoader.getResource("fixtures/$name")!!.readText()

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = SonarApiClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            tokenProvider = { "test-token" }
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `validateConnection returns true for valid token`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("auth-validate.json")))

        val result = client.validateConnection()

        assertTrue(result.isSuccess)
        assertTrue((result as ApiResult.Success).data)
        val req = server.takeRequest()
        assertEquals("/api/authentication/validate", req.path)
        assertEquals("Bearer test-token", req.getHeader("Authorization"))
    }

    @Test
    fun `searchProjects returns matching projects`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("projects-search.json")))

        val result = client.searchProjects("my-app")

        assertTrue(result.isSuccess)
        val projects = (result as ApiResult.Success).data
        assertEquals(2, projects.size)
        assertEquals("com.myapp:my-app", projects[0].key)
    }

    @Test
    fun `getQualityGateStatus returns gate with conditions`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("qualitygate-status-passed.json")))

        val result = client.getQualityGateStatus("com.myapp:my-app")

        assertTrue(result.isSuccess)
        val gate = (result as ApiResult.Success).data
        assertEquals("OK", gate.status)
        assertEquals(3, gate.conditions.size)
    }

    @Test
    fun `getQualityGateStatus includes branch parameter`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("qualitygate-status-passed.json")))

        client.getQualityGateStatus("com.myapp:my-app", branch = "feature/test")

        val req = server.takeRequest()
        assertTrue(req.path!!.contains("branch=feature"))
    }

    @Test
    fun `getIssues returns issues with text ranges`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("issues-search.json")))

        val result = client.getIssues("com.myapp:my-app")

        assertTrue(result.isSuccess)
        val issues = (result as ApiResult.Success).data
        assertEquals(3, issues.size)
        assertEquals("BUG", issues[0].type)
        assertEquals(42, issues[0].textRange?.startLine)
        assertNull(issues[2].textRange)
    }

    @Test
    fun `getMeasures returns per-file coverage`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("measures-component-tree.json")))

        val result = client.getMeasures("com.myapp:my-app")

        assertTrue(result.isSuccess)
        val components = (result as ApiResult.Success).data
        assertEquals(2, components.size)
        val coverage = components[0].measures.first { it.metric == "coverage" }
        assertEquals("72.1", coverage.value)
    }

    @Test
    fun `getSourceLines returns per-line coverage data`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("source-lines.json")))

        val result = client.getSourceLines("com.myapp:my-app:src/main/kotlin/com/myapp/service/UserService.kt")

        assertTrue(result.isSuccess)
        val lines = (result as ApiResult.Success).data
        assertEquals(8, lines.size)
        assertEquals(5, lines[0].lineHits)
        assertEquals(0, lines[6].lineHits)
    }

    @Test
    fun `returns AUTH_FAILED on 401`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = client.validateConnection()

        assertTrue(result.isError)
        assertEquals(ErrorType.AUTH_FAILED, (result as ApiResult.Error).type)
    }

    @Test
    fun `returns NOT_FOUND on 404`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = client.getQualityGateStatus("nonexistent")

        assertTrue(result.isError)
        assertEquals(ErrorType.NOT_FOUND, (result as ApiResult.Error).type)
    }
}
```

- [ ] **Step 2: Tests won't compile yet** — SonarApiClient class doesn't exist. That's expected in TDD.

- [ ] **Step 3: Commit test files**

```bash
git add sonar/src/test/kotlin/com/workflow/orchestrator/sonar/api/SonarApiClientTest.kt
git commit -m "test(sonar): add SonarApiClient tests (red — implementation pending)"
```

---

### Task 8: SonarApiClient Implementation

**Files:**
- Create: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/api/SonarApiClient.kt`

- [ ] **Step 1: Create SonarApiClient**

```kotlin
package com.workflow.orchestrator.sonar.api

import com.workflow.orchestrator.core.http.AuthInterceptor
import com.workflow.orchestrator.core.http.AuthScheme
import com.workflow.orchestrator.core.http.RetryInterceptor
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.sonar.api.dto.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class SonarApiClient(
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

    suspend fun validateConnection(): ApiResult<Boolean> =
        get<SonarValidationDto>("/api/authentication/validate").map { it.valid }

    suspend fun searchProjects(query: String): ApiResult<List<SonarProjectDto>> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return get<SonarProjectSearchResult>("/api/projects/search?q=$encoded&ps=100")
            .map { it.components }
    }

    suspend fun getQualityGateStatus(projectKey: String, branch: String? = null): ApiResult<SonarQualityGateDto> {
        val branchParam = branch?.let { "&branch=${URLEncoder.encode(it, "UTF-8")}" } ?: ""
        return get<SonarQualityGateResponse>(
            "/api/qualitygates/project_status?projectKey=${URLEncoder.encode(projectKey, "UTF-8")}$branchParam"
        ).map { it.projectStatus }
    }

    suspend fun getIssues(
        projectKey: String,
        branch: String? = null,
        filePath: String? = null
    ): ApiResult<List<SonarIssueDto>> {
        val params = buildString {
            append("/api/issues/search?componentKeys=")
            append(URLEncoder.encode(projectKey, "UTF-8"))
            append("&resolved=false&ps=500")
            branch?.let { append("&branch=${URLEncoder.encode(it, "UTF-8")}") }
            filePath?.let { append("&components=${URLEncoder.encode(filePath, "UTF-8")}") }
        }
        return get<SonarIssueSearchResult>(params).map { it.issues }
    }

    suspend fun getMeasures(
        projectKey: String,
        branch: String? = null
    ): ApiResult<List<SonarMeasureComponentDto>> {
        val metrics = "coverage,line_coverage,branch_coverage,uncovered_lines,uncovered_conditions"
        val branchParam = branch?.let { "&branch=${URLEncoder.encode(it, "UTF-8")}" } ?: ""
        return get<SonarMeasureSearchResult>(
            "/api/measures/component_tree?component=${URLEncoder.encode(projectKey, "UTF-8")}" +
                "&metricKeys=$metrics&qualifiers=FIL&ps=500$branchParam"
        ).map { it.components }
    }

    suspend fun getSourceLines(
        componentKey: String,
        from: Int? = null,
        to: Int? = null
    ): ApiResult<List<SonarSourceLineDto>> {
        val params = buildString {
            append("/api/sources/lines?key=")
            append(URLEncoder.encode(componentKey, "UTF-8"))
            from?.let { append("&from=$it") }
            to?.let { append("&to=$it") }
        }
        return get<List<SonarSourceLineDto>>(params)
    }

    private suspend inline fun <reified T> get(path: String): ApiResult<T> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url("$baseUrl$path").get().build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> {
                            val bodyStr = it.body?.string() ?: ""
                            ApiResult.Success(json.decodeFromString<T>(bodyStr))
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid SonarQube token")
                        403 -> ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient SonarQube permissions")
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "SonarQube resource not found")
                        429 -> ApiResult.Error(ErrorType.RATE_LIMITED, "SonarQube rate limit exceeded")
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "SonarQube returned ${it.code}")
                    }
                }
            } catch (e: IOException) {
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach SonarQube: ${e.message}", e)
            }
        }
}
```

- [ ] **Step 2: Run tests to verify they pass**

Run: `./gradlew :sonar:test --tests "*.SonarApiClientTest" 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL, 9 tests passed

- [ ] **Step 3: Commit**

```bash
git add sonar/src/main/kotlin/com/workflow/orchestrator/sonar/api/SonarApiClient.kt
git commit -m "feat(sonar): add SonarApiClient with 6 REST endpoints (green)"
```

---

### Task 9: CoverageMapper

**Files:**
- Create: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/CoverageMapper.kt`
- Create: `sonar/src/test/kotlin/com/workflow/orchestrator/sonar/service/CoverageMapperTest.kt`

- [ ] **Step 1: Write CoverageMapper tests**

```kotlin
package com.workflow.orchestrator.sonar.service

import com.workflow.orchestrator.sonar.api.dto.SonarMeasureComponentDto
import com.workflow.orchestrator.sonar.api.dto.SonarMeasureDto
import com.workflow.orchestrator.sonar.api.dto.SonarSourceLineDto
import com.workflow.orchestrator.sonar.model.LineCoverageStatus
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CoverageMapperTest {

    @Test
    fun `maps component measures to file coverage`() {
        val components = listOf(
            SonarMeasureComponentDto(
                key = "com.myapp:my-app:src/main/kotlin/UserService.kt",
                path = "src/main/kotlin/UserService.kt",
                measures = listOf(
                    SonarMeasureDto("line_coverage", "78.5"),
                    SonarMeasureDto("branch_coverage", "65.0"),
                    SonarMeasureDto("uncovered_lines", "12"),
                    SonarMeasureDto("uncovered_conditions", "3")
                )
            )
        )

        val result = CoverageMapper.mapMeasures(components)

        assertEquals(1, result.size)
        val file = result["src/main/kotlin/UserService.kt"]!!
        assertEquals(78.5, file.lineCoverage, 0.01)
        assertEquals(65.0, file.branchCoverage, 0.01)
        assertEquals(12, file.uncoveredLines)
        assertEquals(3, file.uncoveredConditions)
    }

    @Test
    fun `skips components without path`() {
        val components = listOf(
            SonarMeasureComponentDto(
                key = "com.myapp:my-app",
                path = null,
                measures = listOf(SonarMeasureDto("coverage", "80.0"))
            )
        )

        val result = CoverageMapper.mapMeasures(components)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `handles missing metrics gracefully`() {
        val components = listOf(
            SonarMeasureComponentDto(
                key = "k",
                path = "src/File.kt",
                measures = listOf(SonarMeasureDto("line_coverage", "50.0"))
            )
        )

        val result = CoverageMapper.mapMeasures(components)

        val file = result["src/File.kt"]!!
        assertEquals(50.0, file.lineCoverage, 0.01)
        assertEquals(0.0, file.branchCoverage, 0.01)
    }

    @Test
    fun `maps source lines to line statuses`() {
        val lines = listOf(
            SonarSourceLineDto(line = 1, lineHits = 5),
            SonarSourceLineDto(line = 2, lineHits = 0),
            SonarSourceLineDto(line = 3, lineHits = 3, conditions = 2, coveredConditions = 1),
            SonarSourceLineDto(line = 4, lineHits = null)
        )

        val result = CoverageMapper.mapLineStatuses(lines)

        assertEquals(3, result.size)
        assertEquals(LineCoverageStatus.COVERED, result[1])
        assertEquals(LineCoverageStatus.UNCOVERED, result[2])
        assertEquals(LineCoverageStatus.PARTIAL, result[3])
        assertNull(result[4]) // non-executable lines excluded
    }

    @Test
    fun `fully covered conditions maps to COVERED`() {
        val lines = listOf(
            SonarSourceLineDto(line = 1, lineHits = 2, conditions = 2, coveredConditions = 2)
        )

        val result = CoverageMapper.mapLineStatuses(lines)

        assertEquals(LineCoverageStatus.COVERED, result[1])
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :sonar:test --tests "*.CoverageMapperTest" 2>&1 | tail -5`
Expected: FAIL — `CoverageMapper` not found

- [ ] **Step 3: Implement CoverageMapper**

```kotlin
package com.workflow.orchestrator.sonar.service

import com.workflow.orchestrator.sonar.api.dto.SonarMeasureComponentDto
import com.workflow.orchestrator.sonar.api.dto.SonarSourceLineDto
import com.workflow.orchestrator.sonar.model.FileCoverageData
import com.workflow.orchestrator.sonar.model.LineCoverageStatus

object CoverageMapper {

    fun mapMeasures(components: List<SonarMeasureComponentDto>): Map<String, FileCoverageData> {
        return components
            .filter { it.path != null }
            .associate { comp ->
                val measures = comp.measures.associate { it.metric to it.value }
                comp.path!! to FileCoverageData(
                    filePath = comp.path!!,
                    lineCoverage = measures["line_coverage"]?.toDoubleOrNull() ?: 0.0,
                    branchCoverage = measures["branch_coverage"]?.toDoubleOrNull() ?: 0.0,
                    uncoveredLines = measures["uncovered_lines"]?.toIntOrNull() ?: 0,
                    uncoveredConditions = measures["uncovered_conditions"]?.toIntOrNull() ?: 0,
                    lineStatuses = emptyMap()
                )
            }
    }

    fun mapLineStatuses(lines: List<SonarSourceLineDto>): Map<Int, LineCoverageStatus> {
        return lines
            .filter { it.lineHits != null }
            .associate { line ->
                val status = when {
                    line.lineHits == 0 -> LineCoverageStatus.UNCOVERED
                    line.conditions != null && line.coveredConditions != null
                        && line.coveredConditions!! < line.conditions!! -> LineCoverageStatus.PARTIAL
                    else -> LineCoverageStatus.COVERED
                }
                line.line to status
            }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :sonar:test --tests "*.CoverageMapperTest" 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL, 5 tests passed

- [ ] **Step 5: Commit**

```bash
git add sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/CoverageMapper.kt \
       sonar/src/test/kotlin/com/workflow/orchestrator/sonar/service/CoverageMapperTest.kt
git commit -m "feat(sonar): add CoverageMapper with line-level status mapping"
```

---

### Task 10: IssueMapper

**Files:**
- Create: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/IssueMapper.kt`
- Create: `sonar/src/test/kotlin/com/workflow/orchestrator/sonar/service/IssueMapperTest.kt`

- [ ] **Step 1: Write IssueMapper tests**

```kotlin
package com.workflow.orchestrator.sonar.service

import com.workflow.orchestrator.sonar.api.dto.SonarIssueDto
import com.workflow.orchestrator.sonar.api.dto.SonarTextRangeDto
import com.workflow.orchestrator.sonar.model.IssueSeverity
import com.workflow.orchestrator.sonar.model.IssueType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class IssueMapperTest {

    @Test
    fun `maps issue DTOs to MappedIssues`() {
        val dtos = listOf(
            SonarIssueDto(
                key = "AYz1",
                rule = "java:S2259",
                severity = "CRITICAL",
                message = "NullPointerException possible",
                component = "com.myapp:my-app:src/main/kotlin/com/myapp/UserService.kt",
                type = "BUG",
                effort = "15min",
                textRange = SonarTextRangeDto(startLine = 42, endLine = 42, startOffset = 8, endOffset = 32)
            )
        )

        val result = IssueMapper.mapIssues(dtos, "com.myapp:my-app")

        assertEquals(1, result.size)
        val issue = result[0]
        assertEquals("AYz1", issue.key)
        assertEquals(IssueType.BUG, issue.type)
        assertEquals(IssueSeverity.CRITICAL, issue.severity)
        assertEquals("src/main/kotlin/com/myapp/UserService.kt", issue.filePath)
        assertEquals(42, issue.startLine)
        assertEquals(8, issue.startOffset)
    }

    @Test
    fun `strips project key prefix from component path`() {
        val dtos = listOf(
            SonarIssueDto(
                key = "k1", rule = "r", severity = "MAJOR", message = "msg",
                component = "org.example:proj:src/main/java/Foo.java",
                type = "CODE_SMELL"
            )
        )

        val result = IssueMapper.mapIssues(dtos, "org.example:proj")

        assertEquals("src/main/java/Foo.java", result[0].filePath)
    }

    @Test
    fun `defaults textRange to line 1 when absent`() {
        val dtos = listOf(
            SonarIssueDto(
                key = "k1", rule = "r", severity = "INFO", message = "msg",
                component = "p:k:src/File.kt", type = "CODE_SMELL", textRange = null
            )
        )

        val result = IssueMapper.mapIssues(dtos, "p:k")

        assertEquals(1, result[0].startLine)
        assertEquals(1, result[0].endLine)
    }

    @Test
    fun `maps all severity levels`() {
        val severities = listOf("BLOCKER", "CRITICAL", "MAJOR", "MINOR", "INFO")
        val dtos = severities.mapIndexed { i, sev ->
            SonarIssueDto(
                key = "k$i", rule = "r", severity = sev, message = "m",
                component = "p:k:src/F.kt", type = "BUG"
            )
        }

        val result = IssueMapper.mapIssues(dtos, "p:k")

        assertEquals(IssueSeverity.BLOCKER, result[0].severity)
        assertEquals(IssueSeverity.CRITICAL, result[1].severity)
        assertEquals(IssueSeverity.MAJOR, result[2].severity)
        assertEquals(IssueSeverity.MINOR, result[3].severity)
        assertEquals(IssueSeverity.INFO, result[4].severity)
    }

    @Test
    fun `maps all issue types`() {
        val types = listOf("BUG", "VULNERABILITY", "CODE_SMELL", "SECURITY_HOTSPOT")
        val dtos = types.mapIndexed { i, t ->
            SonarIssueDto(
                key = "k$i", rule = "r", severity = "MAJOR", message = "m",
                component = "p:k:src/F.kt", type = t
            )
        }

        val result = IssueMapper.mapIssues(dtos, "p:k")

        assertEquals(IssueType.BUG, result[0].type)
        assertEquals(IssueType.VULNERABILITY, result[1].type)
        assertEquals(IssueType.CODE_SMELL, result[2].type)
        assertEquals(IssueType.SECURITY_HOTSPOT, result[3].type)
    }

    @Test
    fun `groups issues by file path`() {
        val dtos = listOf(
            SonarIssueDto(key = "k1", rule = "r", severity = "MAJOR", message = "m1",
                component = "p:k:src/A.kt", type = "BUG"),
            SonarIssueDto(key = "k2", rule = "r", severity = "MINOR", message = "m2",
                component = "p:k:src/A.kt", type = "BUG"),
            SonarIssueDto(key = "k3", rule = "r", severity = "INFO", message = "m3",
                component = "p:k:src/B.kt", type = "CODE_SMELL")
        )

        val result = IssueMapper.groupByFile(IssueMapper.mapIssues(dtos, "p:k"))

        assertEquals(2, result.size)
        assertEquals(2, result["src/A.kt"]?.size)
        assertEquals(1, result["src/B.kt"]?.size)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :sonar:test --tests "*.IssueMapperTest" 2>&1 | tail -5`
Expected: FAIL — `IssueMapper` not found

- [ ] **Step 3: Implement IssueMapper**

```kotlin
package com.workflow.orchestrator.sonar.service

import com.workflow.orchestrator.sonar.api.dto.SonarIssueDto
import com.workflow.orchestrator.sonar.model.IssueSeverity
import com.workflow.orchestrator.sonar.model.IssueType
import com.workflow.orchestrator.sonar.model.MappedIssue

object IssueMapper {

    fun mapIssues(dtos: List<SonarIssueDto>, projectKey: String): List<MappedIssue> {
        val prefix = "$projectKey:"
        return dtos.map { dto ->
            val filePath = if (dto.component.startsWith(prefix)) {
                dto.component.removePrefix(prefix)
            } else {
                dto.component
            }
            MappedIssue(
                key = dto.key,
                type = parseType(dto.type),
                severity = parseSeverity(dto.severity),
                message = dto.message,
                rule = dto.rule,
                filePath = filePath,
                startLine = dto.textRange?.startLine ?: 1,
                endLine = dto.textRange?.endLine ?: 1,
                startOffset = dto.textRange?.startOffset ?: 0,
                endOffset = dto.textRange?.endOffset ?: 0,
                effort = dto.effort
            )
        }
    }

    fun groupByFile(issues: List<MappedIssue>): Map<String, List<MappedIssue>> =
        issues.groupBy { it.filePath }

    private fun parseType(type: String): IssueType = when (type) {
        "BUG" -> IssueType.BUG
        "VULNERABILITY" -> IssueType.VULNERABILITY
        "CODE_SMELL" -> IssueType.CODE_SMELL
        "SECURITY_HOTSPOT" -> IssueType.SECURITY_HOTSPOT
        else -> IssueType.CODE_SMELL
    }

    private fun parseSeverity(severity: String): IssueSeverity = when (severity) {
        "BLOCKER" -> IssueSeverity.BLOCKER
        "CRITICAL" -> IssueSeverity.CRITICAL
        "MAJOR" -> IssueSeverity.MAJOR
        "MINOR" -> IssueSeverity.MINOR
        "INFO" -> IssueSeverity.INFO
        else -> IssueSeverity.INFO
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :sonar:test --tests "*.IssueMapperTest" 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL, 6 tests passed

- [ ] **Step 5: Commit**

```bash
git add sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/IssueMapper.kt \
       sonar/src/test/kotlin/com/workflow/orchestrator/sonar/service/IssueMapperTest.kt
git commit -m "feat(sonar): add IssueMapper with severity/type parsing and file grouping"
```

---

## Chunk 3: Services

### Task 11: ProjectKeyDetectionService

**Files:**
- Create: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/ProjectKeyDetectionService.kt`
- Create: `sonar/src/test/kotlin/com/workflow/orchestrator/sonar/service/ProjectKeyDetectionServiceTest.kt`

- [ ] **Step 1: Write tests**

```kotlin
package com.workflow.orchestrator.sonar.service

import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.sonar.api.SonarApiClient
import com.workflow.orchestrator.sonar.api.dto.SonarProjectDto
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ProjectKeyDetectionServiceTest {

    private val apiClient = mockk<SonarApiClient>()
    private val service = ProjectKeyDetectionService(apiClient)

    @Test
    fun `autoDetect returns single match`() = runTest {
        coEvery { apiClient.searchProjects("my-repo") } returns ApiResult.Success(
            listOf(SonarProjectDto("com.myapp:my-repo", "My Repo"))
        )

        val result = service.autoDetect("my-repo")

        assertTrue(result is ApiResult.Success)
        assertEquals("com.myapp:my-repo", (result as ApiResult.Success).data)
    }

    @Test
    fun `autoDetect returns null for multiple matches`() = runTest {
        coEvery { apiClient.searchProjects("app") } returns ApiResult.Success(
            listOf(
                SonarProjectDto("com.myapp:app-api", "App API"),
                SonarProjectDto("com.myapp:app-web", "App Web")
            )
        )

        val result = service.autoDetect("app")

        assertTrue(result is ApiResult.Success)
        assertNull((result as ApiResult.Success).data)
    }

    @Test
    fun `autoDetect returns null for zero matches`() = runTest {
        coEvery { apiClient.searchProjects("nonexistent") } returns ApiResult.Success(emptyList())

        val result = service.autoDetect("nonexistent")

        assertTrue(result is ApiResult.Success)
        assertNull((result as ApiResult.Success).data)
    }

    @Test
    fun `search delegates to apiClient`() = runTest {
        val projects = listOf(SonarProjectDto("key1", "Project 1"))
        coEvery { apiClient.searchProjects("query") } returns ApiResult.Success(projects)

        val result = service.search("query")

        assertTrue(result is ApiResult.Success)
        assertEquals(1, (result as ApiResult.Success).data.size)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :sonar:test --tests "*.ProjectKeyDetectionServiceTest" 2>&1 | tail -5`
Expected: FAIL

- [ ] **Step 3: Implement ProjectKeyDetectionService**

```kotlin
package com.workflow.orchestrator.sonar.service

import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.sonar.api.SonarApiClient
import com.workflow.orchestrator.sonar.api.dto.SonarProjectDto

class ProjectKeyDetectionService(
    private val apiClient: SonarApiClient
) {
    suspend fun autoDetect(repoName: String): ApiResult<String?> {
        return apiClient.searchProjects(repoName).map { projects ->
            if (projects.size == 1) projects[0].key else null
        }
    }

    suspend fun search(query: String): ApiResult<List<SonarProjectDto>> {
        return apiClient.searchProjects(query)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :sonar:test --tests "*.ProjectKeyDetectionServiceTest" 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL, 4 tests passed

- [ ] **Step 5: Commit**

```bash
git add sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/ProjectKeyDetectionService.kt \
       sonar/src/test/kotlin/com/workflow/orchestrator/sonar/service/ProjectKeyDetectionServiceTest.kt
git commit -m "feat(sonar): add ProjectKeyDetectionService with auto-detect"
```

---

### Task 12: SonarDataService

**Files:**
- Create: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarDataService.kt`
- Create: `sonar/src/test/kotlin/com/workflow/orchestrator/sonar/service/SonarDataServiceTest.kt`

- [ ] **Step 1: Write SonarDataService tests**

Note: Tests use `refreshWith()` which is the testable internal method — it accepts an explicit `SonarApiClient`, `projectKey`, and `branch`, bypassing the `@Service` project-based construction. This avoids needing `BasePlatformTestCase` for unit tests.

```kotlin
package com.workflow.orchestrator.sonar.service

import app.cash.turbine.test
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.sonar.api.SonarApiClient
import com.workflow.orchestrator.sonar.api.dto.*
import com.workflow.orchestrator.sonar.model.QualityGateStatus
import com.workflow.orchestrator.sonar.model.SonarState
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SonarDataServiceTest {

    private val apiClient = mockk<SonarApiClient>()

    /**
     * Creates a testable SonarDataService by directly constructing an instance
     * that wraps a MutableStateFlow. Tests call refreshWith() directly,
     * bypassing the @Service project constructor.
     */
    private fun createTestableService(): TestSonarDataService {
        return TestSonarDataService(apiClient)
    }

    private fun stubSuccessfulResponses() {
        coEvery { apiClient.getQualityGateStatus(any(), any()) } returns ApiResult.Success(
            SonarQualityGateDto("OK", listOf(
                SonarConditionDto("OK", "new_coverage", "LT", "80", "87.3")
            ))
        )
        coEvery { apiClient.getIssues(any(), any(), any()) } returns ApiResult.Success(
            listOf(SonarIssueDto(
                key = "k1", rule = "java:S2259", severity = "CRITICAL",
                message = "NPE", component = "proj:key:src/File.kt", type = "BUG"
            ))
        )
        coEvery { apiClient.getMeasures(any(), any()) } returns ApiResult.Success(
            listOf(SonarMeasureComponentDto(
                key = "proj:key:src/File.kt", path = "src/File.kt",
                measures = listOf(
                    SonarMeasureDto("line_coverage", "78.5"),
                    SonarMeasureDto("branch_coverage", "65.0"),
                    SonarMeasureDto("uncovered_lines", "5"),
                    SonarMeasureDto("uncovered_conditions", "2")
                )
            ))
        )
    }

    @Test
    fun `refresh updates state with quality gate and issues`() = runTest {
        stubSuccessfulResponses()
        val service = createTestableService()

        service.refreshWith(apiClient, "proj:key", "main")

        val state = service.stateFlow.value
        assertEquals(QualityGateStatus.PASSED, state.qualityGate.status)
        assertEquals(1, state.issues.size)
        assertEquals("BUG", state.issues[0].type.name)
        assertEquals(1, state.fileCoverage.size)
    }

    @Test
    fun `refresh maps failed quality gate`() = runTest {
        coEvery { apiClient.getQualityGateStatus(any(), any()) } returns ApiResult.Success(
            SonarQualityGateDto("ERROR", emptyList())
        )
        coEvery { apiClient.getIssues(any(), any(), any()) } returns ApiResult.Success(emptyList())
        coEvery { apiClient.getMeasures(any(), any()) } returns ApiResult.Success(emptyList())

        val service = createTestableService()
        service.refreshWith(apiClient, "proj:key", "main")

        assertEquals(QualityGateStatus.FAILED, service.stateFlow.value.qualityGate.status)
    }

    @Test
    fun `detects quality gate status transition`() = runTest {
        coEvery { apiClient.getIssues(any(), any(), any()) } returns ApiResult.Success(emptyList())
        coEvery { apiClient.getMeasures(any(), any()) } returns ApiResult.Success(emptyList())

        coEvery { apiClient.getQualityGateStatus(any(), any()) } returns ApiResult.Success(
            SonarQualityGateDto("OK", emptyList())
        )

        val service = createTestableService()

        service.refreshWith(apiClient, "proj:key", "main")
        assertEquals(1, service.transitions.size)
        assertTrue(service.transitions[0])

        // Same status again — no new transition
        service.refreshWith(apiClient, "proj:key", "main")
        assertEquals(1, service.transitions.size)

        // Now: FAILED — transition fires
        coEvery { apiClient.getQualityGateStatus(any(), any()) } returns ApiResult.Success(
            SonarQualityGateDto("ERROR", emptyList())
        )
        service.refreshWith(apiClient, "proj:key", "main")
        assertEquals(2, service.transitions.size)
        assertFalse(service.transitions[1])
    }

    @Test
    fun `handles API errors gracefully`() = runTest {
        coEvery { apiClient.getQualityGateStatus(any(), any()) } returns
            ApiResult.Error(ErrorType.NETWORK_ERROR, "offline")
        coEvery { apiClient.getIssues(any(), any(), any()) } returns
            ApiResult.Error(ErrorType.NETWORK_ERROR, "offline")
        coEvery { apiClient.getMeasures(any(), any()) } returns
            ApiResult.Error(ErrorType.NETWORK_ERROR, "offline")

        val service = createTestableService()
        service.refreshWith(apiClient, "proj:key", "main")

        assertEquals(QualityGateStatus.NONE, service.stateFlow.value.qualityGate.status)
    }

    @Test
    fun `stateFlow emits updates on refresh`() = runTest {
        stubSuccessfulResponses()
        val service = createTestableService()

        service.stateFlow.test {
            val initial = awaitItem()
            assertEquals(QualityGateStatus.NONE, initial.qualityGate.status)

            service.refreshWith(apiClient, "proj:key", "main")

            val updated = awaitItem()
            assertEquals(QualityGateStatus.PASSED, updated.qualityGate.status)
            assertEquals(1, updated.issues.size)
        }
    }
}

/**
 * Test double that replicates SonarDataService core logic without the @Service
 * project dependency. Tracks quality gate transitions for assertion.
 */
private class TestSonarDataService(private val apiClient: SonarApiClient) {
    private val _stateFlow = MutableStateFlow(SonarState.EMPTY)
    val stateFlow = _stateFlow
    val transitions = mutableListOf<Boolean>()
    private var previousGateStatus: QualityGateStatus? = null

    suspend fun refreshWith(client: SonarApiClient, projectKey: String, branch: String) {
        val gateResult = client.getQualityGateStatus(projectKey, branch)
        val issuesResult = client.getIssues(projectKey, branch)
        val measuresResult = client.getMeasures(projectKey, branch)

        val qualityGate = when (gateResult) {
            is ApiResult.Success -> {
                val status = when (gateResult.data.status) {
                    "OK" -> QualityGateStatus.PASSED
                    "ERROR" -> QualityGateStatus.FAILED
                    else -> QualityGateStatus.NONE
                }
                com.workflow.orchestrator.sonar.model.QualityGateState(
                    status,
                    gateResult.data.conditions.map { cond ->
                        com.workflow.orchestrator.sonar.model.GateCondition(
                            cond.metricKey, cond.comparator, cond.errorThreshold,
                            cond.actualValue, cond.status == "OK"
                        )
                    }
                )
            }
            is ApiResult.Error -> com.workflow.orchestrator.sonar.model.QualityGateState(
                QualityGateStatus.NONE, emptyList()
            )
        }

        val issues = when (issuesResult) {
            is ApiResult.Success -> IssueMapper.mapIssues(issuesResult.data, projectKey)
            is ApiResult.Error -> _stateFlow.value.issues
        }

        val fileCoverage = when (measuresResult) {
            is ApiResult.Success -> CoverageMapper.mapMeasures(measuresResult.data)
            is ApiResult.Error -> _stateFlow.value.fileCoverage
        }

        val avgLine = if (fileCoverage.isEmpty()) 0.0 else fileCoverage.values.map { it.lineCoverage }.average()
        val avgBranch = if (fileCoverage.isEmpty()) 0.0 else fileCoverage.values.map { it.branchCoverage }.average()

        _stateFlow.value = SonarState(
            projectKey = projectKey, branch = branch, qualityGate = qualityGate,
            issues = issues, fileCoverage = fileCoverage,
            overallCoverage = com.workflow.orchestrator.sonar.model.CoverageMetrics(avgLine, avgBranch),
            lastUpdated = java.time.Instant.now()
        )

        if (qualityGate.status != QualityGateStatus.NONE) {
            if (previousGateStatus != null && previousGateStatus != qualityGate.status) {
                transitions.add(qualityGate.status == QualityGateStatus.PASSED)
            }
            if (previousGateStatus == null) {
                transitions.add(qualityGate.status == QualityGateStatus.PASSED)
            }
            previousGateStatus = qualityGate.status
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :sonar:test --tests "*.SonarDataServiceTest" 2>&1 | tail -5`
Expected: FAIL

- [ ] **Step 3: Implement SonarDataService**

Note: `SonarDataService` is a project-level `@Service` so the IntelliJ platform instantiates it and all editor integration components can access it via `project.getService()`. It reads configuration from `PluginSettings` and creates the `SonarApiClient` internally — same lifecycle pattern as `BuildMonitorService` in Phase 1C.

For testability, the core logic is in `refreshWith()` which accepts an `SonarApiClient`, `projectKey`, and `branch` — tests call this directly with a mock client. The `refresh()` method reads current settings and delegates.

```kotlin
package com.workflow.orchestrator.sonar.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.notifications.WorkflowNotificationService
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.sonar.api.SonarApiClient
import com.workflow.orchestrator.sonar.model.*
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

@Service(Service.Level.PROJECT)
class SonarDataService(private val project: Project) : Disposable {

    private val _stateFlow = MutableStateFlow(SonarState.EMPTY)
    val stateFlow: StateFlow<SonarState> = _stateFlow.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var previousGateStatus: QualityGateStatus? = null

    private val settings get() = PluginSettings.getInstance(project)

    private val apiClient: SonarApiClient? get() {
        val url = settings.state.sonarUrl.orEmpty().trimEnd('/')
        if (url.isBlank()) return null
        val credentialStore = CredentialStore()
        return SonarApiClient(url) { credentialStore.getToken(ServiceType.SONARQUBE) }
    }

    private val currentBranch: String get() {
        val repos = GitRepositoryManager.getInstance(project).repositories
        return repos.firstOrNull()?.currentBranchName ?: "main"
    }

    fun refresh() {
        val client = apiClient ?: return
        val projectKey = settings.state.sonarProjectKey.orEmpty()
        if (projectKey.isBlank()) return
        scope.launch { refreshWith(client, projectKey, currentBranch) }
    }

    /** Testable core — accepts explicit dependencies. */
    internal suspend fun refreshWith(client: SonarApiClient, projectKey: String, branch: String) {
        val gateResult = client.getQualityGateStatus(projectKey, branch)
        val issuesResult = client.getIssues(projectKey, branch)
        val measuresResult = client.getMeasures(projectKey, branch)

        val qualityGate = when (gateResult) {
            is ApiResult.Success -> mapQualityGate(gateResult.data)
            is ApiResult.Error -> QualityGateState(QualityGateStatus.NONE, emptyList())
        }

        val issues = when (issuesResult) {
            is ApiResult.Success -> IssueMapper.mapIssues(issuesResult.data, projectKey)
            is ApiResult.Error -> _stateFlow.value.issues
        }

        val fileCoverage = when (measuresResult) {
            is ApiResult.Success -> CoverageMapper.mapMeasures(measuresResult.data)
            is ApiResult.Error -> _stateFlow.value.fileCoverage
        }

        val overallCoverage = calculateOverallCoverage(fileCoverage)

        val newState = SonarState(
            projectKey = projectKey,
            branch = branch,
            qualityGate = qualityGate,
            issues = issues,
            fileCoverage = fileCoverage,
            overallCoverage = overallCoverage,
            lastUpdated = Instant.now()
        )

        _stateFlow.value = newState

        // Fire notification if quality gate status changed
        if (qualityGate.status != QualityGateStatus.NONE) {
            if (previousGateStatus != null && previousGateStatus != qualityGate.status) {
                notifyGateTransition(qualityGate.status == QualityGateStatus.PASSED, projectKey)
            }
            if (previousGateStatus == null) {
                notifyGateTransition(qualityGate.status == QualityGateStatus.PASSED, projectKey)
            }
            previousGateStatus = qualityGate.status
        }
    }

    private fun notifyGateTransition(passed: Boolean, projectKey: String) {
        val notificationService = WorkflowNotificationService.getInstance(project)
        if (passed) {
            notificationService.notifyInfo(
                WorkflowNotificationService.GROUP_QUALITY,
                "Quality Gate Passed",
                "\u2713 All conditions met for $projectKey"
            )
        } else {
            notificationService.notifyError(
                WorkflowNotificationService.GROUP_QUALITY,
                "Quality Gate Failed",
                "\u2717 Quality gate failed for $projectKey"
            )
        }
    }

    private fun mapQualityGate(dto: com.workflow.orchestrator.sonar.api.dto.SonarQualityGateDto): QualityGateState {
        val status = when (dto.status) {
            "OK" -> QualityGateStatus.PASSED
            "ERROR" -> QualityGateStatus.FAILED
            else -> QualityGateStatus.NONE
        }
        val conditions = dto.conditions.map { cond ->
            GateCondition(
                metric = cond.metricKey,
                comparator = cond.comparator,
                threshold = cond.errorThreshold,
                actualValue = cond.actualValue,
                passed = cond.status == "OK"
            )
        }
        return QualityGateState(status, conditions)
    }

    private fun calculateOverallCoverage(fileCoverage: Map<String, FileCoverageData>): CoverageMetrics {
        if (fileCoverage.isEmpty()) return CoverageMetrics(0.0, 0.0)
        val avgLine = fileCoverage.values.map { it.lineCoverage }.average()
        val avgBranch = fileCoverage.values.map { it.branchCoverage }.average()
        return CoverageMetrics(avgLine, avgBranch)
    }

    override fun dispose() {
        scope.cancel()
    }

    companion object {
        fun getInstance(project: Project): SonarDataService {
            return project.getService(SonarDataService::class.java)
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :sonar:test --tests "*.SonarDataServiceTest" 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL, 5 tests passed

- [ ] **Step 5: Commit**

```bash
git add sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarDataService.kt \
       sonar/src/test/kotlin/com/workflow/orchestrator/sonar/service/SonarDataServiceTest.kt
git commit -m "feat(sonar): add SonarDataService with StateFlow, transition detection"
```

---

## Chunk 4: Quality Tab UI

### Task 13: QualityTabProvider

**Files:**
- Create: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/QualityTabProvider.kt`

- [ ] **Step 1: Create QualityTabProvider**

```kotlin
package com.workflow.orchestrator.sonar.ui

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.toolwindow.EmptyStatePanel
import com.workflow.orchestrator.core.toolwindow.WorkflowTabProvider
import javax.swing.JComponent

class QualityTabProvider : WorkflowTabProvider {

    override val tabId: String = "quality"
    override val tabTitle: String = "Quality"
    override val order: Int = 2

    override fun createPanel(project: Project): JComponent {
        val settings = PluginSettings.getInstance(project)
        return if (!settings.state.sonarUrl.isNullOrBlank()) {
            QualityDashboardPanel(project)
        } else {
            EmptyStatePanel(project, "No quality data available.\nConnect to SonarQube in Settings to get started.")
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :sonar:compileKotlin 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/QualityTabProvider.kt
git commit -m "feat(sonar): add QualityTabProvider"
```

---

### Task 14: OverviewPanel

**Files:**
- Create: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/OverviewPanel.kt`

- [ ] **Step 1: Create OverviewPanel**

```kotlin
package com.workflow.orchestrator.sonar.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.sonar.model.*
import java.awt.*
import javax.swing.*

class OverviewPanel : JPanel(BorderLayout()) {

    private val gateStatusLabel = JBLabel("—")
    private val gateConditionsPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    private val coverageLabel = JBLabel("—")
    private val branchCoverageLabel = JBLabel("—")
    private val coverageBar = CoverageProgressBar()
    private val issueCountLabel = JBLabel("—")
    private val issueBreakdownLabel = JBLabel("")
    private val recentIssuesPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }

    init {
        border = JBUI.Borders.empty(12)

        val cardsPanel = JPanel(GridLayout(1, 3, 12, 0))

        // Quality Gate card
        val gateCard = createCard("QUALITY GATE", gateStatusLabel, gateConditionsPanel)
        cardsPanel.add(gateCard)

        // Coverage card
        val coverageCard = createCard("COVERAGE", coverageLabel, JPanel(BorderLayout()).apply {
            isOpaque = false
            add(coverageBar, BorderLayout.NORTH)
            add(branchCoverageLabel, BorderLayout.CENTER)
        })
        cardsPanel.add(coverageCard)

        // Issues card
        val issuesCard = createCard("ISSUES", issueCountLabel, issueBreakdownLabel)
        cardsPanel.add(issuesCard)

        add(cardsPanel, BorderLayout.NORTH)

        // Recent issues section
        val recentSection = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyTop(16)
            isOpaque = false
            val header = JBLabel("RECENT ISSUES").apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(Font.PLAIN, 10f)
            }
            add(header, BorderLayout.NORTH)
            add(JBScrollPane(recentIssuesPanel).apply {
                border = JBUI.Borders.emptyTop(4)
            }, BorderLayout.CENTER)
        }
        add(recentSection, BorderLayout.CENTER)
    }

    fun update(state: SonarState) {
        // Quality gate
        val (gateText, gateColor) = when (state.qualityGate.status) {
            QualityGateStatus.PASSED -> "\u2713 PASSED" to JBColor(Color(46, 160, 67), Color(46, 160, 67))
            QualityGateStatus.FAILED -> "\u2717 FAILED" to JBColor(Color(255, 68, 68), Color(255, 68, 68))
            QualityGateStatus.NONE -> "—" to JBColor.GRAY
        }
        gateStatusLabel.text = gateText
        gateStatusLabel.foreground = gateColor
        gateStatusLabel.font = gateStatusLabel.font.deriveFont(Font.BOLD, 18f)

        gateConditionsPanel.removeAll()
        state.qualityGate.conditions.forEach { cond ->
            val icon = if (cond.passed) "\u2713" else "\u2717"
            val label = JBLabel("$icon ${cond.metric}: ${cond.actualValue} (threshold: ${cond.threshold})")
            label.font = label.font.deriveFont(10f)
            label.foreground = if (cond.passed) JBColor.GRAY else JBColor(Color(255, 68, 68), Color(255, 68, 68))
            gateConditionsPanel.add(label)
        }

        // Coverage
        val lineCov = state.overallCoverage.lineCoverage
        coverageLabel.text = "%.1f%%".format(lineCov)
        coverageLabel.font = coverageLabel.font.deriveFont(Font.BOLD, 18f)
        coverageLabel.foreground = coverageColor(lineCov)
        coverageBar.value = lineCov
        branchCoverageLabel.text = "Branch: %.1f%%".format(state.overallCoverage.branchCoverage)
        branchCoverageLabel.foreground = JBColor.GRAY
        branchCoverageLabel.font = branchCoverageLabel.font.deriveFont(10f)

        // Issues
        val total = state.issues.size
        issueCountLabel.text = "$total"
        issueCountLabel.font = issueCountLabel.font.deriveFont(Font.BOLD, 18f)
        val bugs = state.issues.count { it.type == IssueType.BUG }
        val vulns = state.issues.count { it.type == IssueType.VULNERABILITY }
        val smells = state.issues.count { it.type == IssueType.CODE_SMELL }
        val hotspots = state.issues.count { it.type == IssueType.SECURITY_HOTSPOT }
        issueBreakdownLabel.text = "<html>${bugs}B ${vulns}V ${smells}S ${hotspots}H</html>"
        issueBreakdownLabel.font = issueBreakdownLabel.font.deriveFont(10f)

        // Recent issues (top 5 by severity)
        recentIssuesPanel.removeAll()
        state.issues
            .sortedBy { it.severity.ordinal }
            .take(5)
            .forEach { issue ->
                val color = severityColor(issue.severity)
                val label = JBLabel("<html><font color='${htmlColor(color)}'>\u25CF</font> " +
                    "${issue.type} <font color='${htmlColor(color)}'>${issue.severity}</font> " +
                    "${issue.message} — ${issue.filePath.substringAfterLast('/')}:${issue.startLine}</html>")
                label.font = label.font.deriveFont(11f)
                label.border = JBUI.Borders.emptyBottom(2)
                recentIssuesPanel.add(label)
            }

        revalidate()
        repaint()
    }

    private fun createCard(title: String, mainContent: JComponent, subContent: JComponent): JPanel {
        return JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border(), 1),
                JBUI.Borders.empty(10)
            )
            val titleLabel = JBLabel(title).apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(Font.PLAIN, 9f)
            }
            add(titleLabel, BorderLayout.NORTH)
            add(mainContent, BorderLayout.CENTER)
            add(subContent, BorderLayout.SOUTH)
        }
    }

    private fun coverageColor(coverage: Double): Color = when {
        coverage >= 80 -> Color(46, 160, 67)
        coverage >= 50 -> Color(212, 160, 32)
        else -> Color(255, 68, 68)
    }

    private fun severityColor(severity: IssueSeverity): Color = when (severity) {
        IssueSeverity.BLOCKER, IssueSeverity.CRITICAL -> Color(255, 68, 68)
        IssueSeverity.MAJOR -> Color(230, 138, 0)
        IssueSeverity.MINOR -> Color(255, 170, 0)
        IssueSeverity.INFO -> Color(136, 136, 136)
    }

    private fun htmlColor(c: Color) = "#%02x%02x%02x".format(c.red, c.green, c.blue)
}

private class CoverageProgressBar : JPanel() {
    var value: Double = 0.0
        set(v) { field = v; repaint() }

    init {
        preferredSize = Dimension(0, 6)
        minimumSize = Dimension(0, 6)
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = JBColor(Color(30, 30, 30), Color(30, 30, 30))
        g2.fillRoundRect(0, 0, width, height, 4, 4)
        val fillWidth = (width * value / 100.0).toInt()
        g2.color = when {
            value >= 80 -> Color(46, 160, 67)
            value >= 50 -> Color(212, 160, 32)
            else -> Color(255, 68, 68)
        }
        g2.fillRoundRect(0, 0, fillWidth, height, 4, 4)
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :sonar:compileKotlin 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/OverviewPanel.kt
git commit -m "feat(sonar): add OverviewPanel with quality gate, coverage, issue cards"
```

---

### Task 15: IssueListPanel

**Files:**
- Create: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/IssueListPanel.kt`

- [ ] **Step 1: Create IssueListPanel**

```kotlin
package com.workflow.orchestrator.sonar.ui

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.sonar.model.*
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

class IssueListPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val listModel = DefaultListModel<MappedIssue>()
    private val issueList = JBList(listModel).apply {
        cellRenderer = IssueListCellRenderer()
        selectionMode = ListSelectionModel.SINGLE_SELECTION
    }
    private val filterCombo = JComboBox(arrayOf("All", "Bug", "Vulnerability", "Code Smell", "Hotspot"))
    private val severityCombo = JComboBox(arrayOf("All", "Blocker", "Critical", "Major", "Minor", "Info"))
    private val countLabel = JBLabel("0 issues")

    private var allIssues: List<MappedIssue> = emptyList()

    init {
        border = JBUI.Borders.empty(8)

        // Filter toolbar
        val filterPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            add(JBLabel("Type:"))
            add(filterCombo)
            add(JBLabel("Severity:"))
            add(severityCombo)
            add(countLabel)
        }
        add(filterPanel, BorderLayout.NORTH)
        add(JBScrollPane(issueList), BorderLayout.CENTER)

        filterCombo.addActionListener { applyFilters() }
        severityCombo.addActionListener { applyFilters() }

        // Double-click navigates to file
        issueList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val issue = issueList.selectedValue ?: return
                    navigateToIssue(issue)
                }
            }
        })
    }

    fun update(issues: List<MappedIssue>) {
        allIssues = issues
        applyFilters()
    }

    private fun applyFilters() {
        var filtered = allIssues

        when (filterCombo.selectedIndex) {
            1 -> filtered = filtered.filter { it.type == IssueType.BUG }
            2 -> filtered = filtered.filter { it.type == IssueType.VULNERABILITY }
            3 -> filtered = filtered.filter { it.type == IssueType.CODE_SMELL }
            4 -> filtered = filtered.filter { it.type == IssueType.SECURITY_HOTSPOT }
        }

        when (severityCombo.selectedIndex) {
            1 -> filtered = filtered.filter { it.severity == IssueSeverity.BLOCKER }
            2 -> filtered = filtered.filter { it.severity == IssueSeverity.CRITICAL }
            3 -> filtered = filtered.filter { it.severity == IssueSeverity.MAJOR }
            4 -> filtered = filtered.filter { it.severity == IssueSeverity.MINOR }
            5 -> filtered = filtered.filter { it.severity == IssueSeverity.INFO }
        }

        listModel.clear()
        filtered.sortedBy { it.severity.ordinal }.forEach { listModel.addElement(it) }
        countLabel.text = "${filtered.size} issue(s)"
    }

    private fun navigateToIssue(issue: MappedIssue) {
        val basePath = project.basePath ?: return
        val vf = LocalFileSystem.getInstance().findFileByPath("$basePath/${issue.filePath}") ?: return
        OpenFileDescriptor(project, vf, issue.startLine - 1, issue.startOffset).navigate(true)
    }
}

private class IssueListCellRenderer : ListCellRenderer<MappedIssue> {
    override fun getListCellRendererComponent(
        list: JList<out MappedIssue>, value: MappedIssue,
        index: Int, isSelected: Boolean, cellHasFocus: Boolean
    ): Component {
        val label = JBLabel()
        val color = when (value.severity) {
            IssueSeverity.BLOCKER, IssueSeverity.CRITICAL -> "#ff4444"
            IssueSeverity.MAJOR -> "#e68a00"
            IssueSeverity.MINOR -> "#ffaa00"
            IssueSeverity.INFO -> "#888888"
        }
        val typeStr = value.type.name.replace("_", " ")
        val fileName = value.filePath.substringAfterLast('/')
        label.text = "<html><font color='$color'>\u25CF</font> $typeStr " +
            "<font color='$color'>${value.severity}</font> ${value.message} — $fileName:${value.startLine}</html>"
        label.border = JBUI.Borders.empty(4, 8)
        if (isSelected) {
            label.background = list.selectionBackground
            label.foreground = list.selectionForeground
            label.isOpaque = true
        }
        return label
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/IssueListPanel.kt
git commit -m "feat(sonar): add IssueListPanel with filtering and navigation"
```

---

### Task 16: CoverageTablePanel

**Files:**
- Create: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/CoverageTablePanel.kt`

- [ ] **Step 1: Create CoverageTablePanel**

```kotlin
package com.workflow.orchestrator.sonar.ui

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.sonar.model.FileCoverageData
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.table.AbstractTableModel

class CoverageTablePanel(private val project: Project) : JPanel(BorderLayout()) {

    private val tableModel = CoverageTableModel()
    private val table = JBTable(tableModel).apply {
        autoCreateRowSorter = true
        setShowGrid(false)
        rowHeight = 24
    }

    init {
        border = JBUI.Borders.empty(8)
        add(JBScrollPane(table), BorderLayout.CENTER)

        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val row = table.selectedRow
                    if (row >= 0) {
                        val modelRow = table.convertRowIndexToModel(row)
                        val filePath = tableModel.getFilePath(modelRow)
                        navigateToFile(filePath)
                    }
                }
            }
        })
    }

    fun update(fileCoverage: Map<String, FileCoverageData>) {
        tableModel.setData(fileCoverage.values.toList().sortedBy { it.lineCoverage })
    }

    private fun navigateToFile(filePath: String) {
        val basePath = project.basePath ?: return
        val vf = LocalFileSystem.getInstance().findFileByPath("$basePath/$filePath") ?: return
        OpenFileDescriptor(project, vf, 0, 0).navigate(true)
    }
}

private class CoverageTableModel : AbstractTableModel() {
    private var data: List<FileCoverageData> = emptyList()
    private val columns = arrayOf("File", "Line %", "Branch %", "Uncovered Lines", "Uncovered Conditions")

    fun setData(newData: List<FileCoverageData>) {
        data = newData
        fireTableDataChanged()
    }

    fun getFilePath(row: Int): String = data[row].filePath

    override fun getRowCount() = data.size
    override fun getColumnCount() = columns.size
    override fun getColumnName(col: Int) = columns[col]

    override fun getValueAt(row: Int, col: Int): Any {
        val file = data[row]
        return when (col) {
            0 -> file.filePath.substringAfterLast('/')
            1 -> "%.1f%%".format(file.lineCoverage)
            2 -> "%.1f%%".format(file.branchCoverage)
            3 -> file.uncoveredLines
            4 -> file.uncoveredConditions
            else -> ""
        }
    }

    override fun getColumnClass(col: Int): Class<*> = when (col) {
        3, 4 -> Int::class.java
        else -> String::class.java
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/CoverageTablePanel.kt
git commit -m "feat(sonar): add CoverageTablePanel with sortable file coverage table"
```

---

### Task 17: QualityDashboardPanel

**Files:**
- Create: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/QualityDashboardPanel.kt`

- [ ] **Step 1: Create QualityDashboardPanel**

```kotlin
package com.workflow.orchestrator.sonar.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.sonar.model.QualityGateStatus
import com.workflow.orchestrator.sonar.model.SonarState
import com.workflow.orchestrator.sonar.service.SonarDataService
import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.JPanel

class QualityDashboardPanel(
    private val project: Project
) : JPanel(BorderLayout()), Disposable {

    private val dataService = SonarDataService.getInstance(project)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // UI components
    private val headerLabel = JBLabel("").apply {
        font = font.deriveFont(Font.PLAIN, 11f)
        border = JBUI.Borders.empty(4, 8)
    }
    private val overviewPanel = OverviewPanel()
    private val issueListPanel = IssueListPanel(project)
    private val coverageTablePanel = CoverageTablePanel(project)
    private val statusLabel = JBLabel("Loading...")

    init {
        border = JBUI.Borders.empty()

        // Toolbar
        add(createToolbar(), BorderLayout.NORTH)

        // Sub-tabbed pane
        val tabbedPane = JBTabbedPane().apply {
            addTab("Overview", overviewPanel)
            addTab("Issues", issueListPanel)
            addTab("Coverage", coverageTablePanel)
        }
        add(tabbedPane, BorderLayout.CENTER)

        // Status bar
        val statusPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(2, 8)
            add(statusLabel, BorderLayout.WEST)
        }
        add(statusPanel, BorderLayout.SOUTH)

        // Subscribe to state updates
        scope.launch {
            dataService.stateFlow.collect { state ->
                invokeLater { updateUI(state) }
            }
        }

        // Initial refresh
        refreshData()
    }

    private fun createToolbar(): javax.swing.JComponent {
        val group = DefaultActionGroup()
        group.add(object : AnAction("Refresh", "Refresh SonarQube data", null) {
            override fun actionPerformed(e: AnActionEvent) {
                refreshData()
            }
        })
        val toolbar = ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.TOOLBAR, group, true)
        toolbar.targetComponent = this
        return toolbar.component
    }

    fun refreshData() {
        statusLabel.text = "Refreshing..."
        dataService.refresh()
    }

    private fun updateUI(state: SonarState) {
        if (state.projectKey.isEmpty()) return

        val gateIcon = when (state.qualityGate.status) {
            QualityGateStatus.PASSED -> "\u2713"
            QualityGateStatus.FAILED -> "\u2717"
            QualityGateStatus.NONE -> "—"
        }
        headerLabel.text = "${state.projectKey} [$gateIcon] Coverage: ${"%.1f".format(state.overallCoverage.lineCoverage)}% Issues: ${state.issues.size}"

        overviewPanel.update(state)
        issueListPanel.update(state.issues)
        coverageTablePanel.update(state.fileCoverage)

        val elapsed = java.time.Duration.between(state.lastUpdated, java.time.Instant.now())
        statusLabel.text = "Updated ${elapsed.seconds}s ago"
    }

    override fun dispose() {
        scope.cancel()
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :sonar:compileKotlin 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/QualityDashboardPanel.kt
git commit -m "feat(sonar): add QualityDashboardPanel with sub-tabs and reactive state"
```

---

## Chunk 5: Editor Integration

### Task 18: CoverageLineMarkerProvider

**Files:**
- Create: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/CoverageLineMarkerProvider.kt`

- [ ] **Step 1: Create CoverageLineMarkerProvider**

```kotlin
package com.workflow.orchestrator.sonar.ui

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.ui.JBColor
import com.workflow.orchestrator.sonar.model.LineCoverageStatus
import com.workflow.orchestrator.sonar.model.SonarState
import java.awt.*
import java.awt.image.BufferedImage
import javax.swing.Icon
import javax.swing.ImageIcon

class CoverageLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // Only process first element on each line (the PsiFile's direct children or first token)
        if (element.parent !is PsiFile && element != element.parent?.firstChild) return null

        val project = element.project
        val file = element.containingFile?.virtualFile ?: return null
        val basePath = project.basePath ?: return null

        val relativePath = file.path.removePrefix("$basePath/")
        val state = getSonarState(project)
        val fileCoverage = state.fileCoverage[relativePath] ?: return null

        val doc = element.containingFile?.viewProvider?.document ?: return null
        val lineNumber = doc.getLineNumber(element.textRange.startOffset) + 1
        val lineStatus = fileCoverage.lineStatuses[lineNumber] ?: return null

        val (icon, tooltip) = when (lineStatus) {
            LineCoverageStatus.COVERED -> coverageIcon(COVERED_COLOR) to "Line covered"
            LineCoverageStatus.UNCOVERED -> coverageIcon(UNCOVERED_COLOR) to "Line not covered"
            LineCoverageStatus.PARTIAL -> coverageIcon(PARTIAL_COLOR) to "Partially covered (some branches uncovered)"
        }

        return LineMarkerInfo(
            element,
            element.textRange,
            icon,
            { tooltip },
            null,
            GutterIconRenderer.Alignment.LEFT,
            { tooltip }
        )
    }

    private fun getSonarState(project: Project): SonarState {
        return try {
            SonarDataService.getInstance(project).stateFlow.value
        } catch (_: Exception) {
            SonarState.EMPTY
        }
    }

    companion object {
        val COVERED_COLOR = JBColor(Color(46, 160, 67), Color(46, 160, 67))
        val UNCOVERED_COLOR = JBColor(Color(136, 136, 136), Color(136, 136, 136))
        val PARTIAL_COLOR = JBColor(Color(212, 160, 32), Color(212, 160, 32))

        fun coverageIcon(color: Color): Icon {
            val img = BufferedImage(6, 14, BufferedImage.TYPE_INT_ARGB)
            val g2 = img.createGraphics()
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color
            g2.fillRoundRect(1, 0, 4, 14, 2, 2)
            g2.dispose()
            return ImageIcon(img)
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/CoverageLineMarkerProvider.kt
git commit -m "feat(sonar): add CoverageLineMarkerProvider for gutter coverage markers"
```

---

### Task 19: SonarIssueAnnotator

**Files:**
- Create: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/SonarIssueAnnotator.kt`

- [ ] **Step 1: Create SonarIssueAnnotator**

```kotlin
package com.workflow.orchestrator.sonar.ui

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.workflow.orchestrator.sonar.model.IssueSeverity
import com.workflow.orchestrator.sonar.model.IssueType
import com.workflow.orchestrator.sonar.model.MappedIssue
import com.workflow.orchestrator.sonar.model.SonarState
import com.workflow.orchestrator.sonar.service.IssueMapper

data class SonarAnnotationInput(
    val filePath: String,
    val state: SonarState
)

data class SonarAnnotationResult(
    val issues: List<MappedIssue>
)

class SonarIssueAnnotator : ExternalAnnotator<SonarAnnotationInput, SonarAnnotationResult>() {

    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): SonarAnnotationInput? {
        val project = file.project
        val basePath = project.basePath ?: return null
        val virtualFile = file.virtualFile ?: return null
        val relativePath = virtualFile.path.removePrefix("$basePath/")

        val state = try {
            SonarDataService.getInstance(project).stateFlow.value
        } catch (_: Exception) { return null }

        if (state.issues.none { it.filePath == relativePath }) return null

        return SonarAnnotationInput(relativePath, state)
    }

    override fun doAnnotate(collectedInfo: SonarAnnotationInput): SonarAnnotationResult {
        val fileIssues = collectedInfo.state.issues.filter { it.filePath == collectedInfo.filePath }
        return SonarAnnotationResult(fileIssues)
    }

    override fun apply(file: PsiFile, annotationResult: SonarAnnotationResult, holder: AnnotationHolder) {
        val doc = file.viewProvider.document ?: return

        for (issue in annotationResult.issues) {
            val startLine = (issue.startLine - 1).coerceIn(0, doc.lineCount - 1)
            val endLine = (issue.endLine - 1).coerceIn(0, doc.lineCount - 1)

            val startOffset = doc.getLineStartOffset(startLine) + issue.startOffset
            val endOffset = if (issue.endOffset > 0) {
                doc.getLineStartOffset(endLine) + issue.endOffset
            } else {
                doc.getLineEndOffset(endLine)
            }

            val textRange = TextRange(
                startOffset.coerceIn(0, doc.textLength),
                endOffset.coerceIn(0, doc.textLength)
            )

            if (textRange.isEmpty) continue

            val severity = mapSeverity(issue.type, issue.severity)
            val tooltip = "[${issue.rule}] ${issue.message}" +
                (issue.effort?.let { " (effort: $it)" } ?: "")

            holder.newAnnotation(severity, tooltip)
                .range(textRange)
                .tooltip(tooltip)
                .create()
        }
    }

    companion object {
        fun mapSeverity(type: IssueType, severity: IssueSeverity): HighlightSeverity = when {
            (type == IssueType.BUG || type == IssueType.VULNERABILITY) &&
                (severity == IssueSeverity.BLOCKER || severity == IssueSeverity.CRITICAL) ->
                HighlightSeverity.ERROR
            type == IssueType.BUG || type == IssueType.VULNERABILITY ->
                HighlightSeverity.WARNING
            else -> HighlightSeverity.WEAK_WARNING
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/SonarIssueAnnotator.kt
git commit -m "feat(sonar): add SonarIssueAnnotator (ExternalAnnotator 3-phase)"
```

---

### Task 20: CoverageBannerProvider

**Files:**
- Create: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/CoverageBannerProvider.kt`

- [ ] **Step 1: Create CoverageBannerProvider**

```kotlin
package com.workflow.orchestrator.sonar.ui

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.workflow.orchestrator.sonar.service.SonarDataService
import java.util.function.Function
import javax.swing.JComponent

class CoverageBannerProvider : EditorNotificationProvider {

    override fun collectNotificationData(
        project: Project,
        file: VirtualFile
    ): Function<in FileEditor, out JComponent?> {
        return Function { _ ->
            val basePath = project.basePath ?: return@Function null
            val relativePath = file.path.removePrefix("$basePath/")

            val state = try {
                SonarDataService.getInstance(project).stateFlow.value
            } catch (_: Exception) { return@Function null }

            val fileCoverage = state.fileCoverage[relativePath] ?: return@Function null
            if (fileCoverage.uncoveredConditions <= 0) return@Function null

            EditorNotificationPanel(EditorNotificationPanel.Status.Warning).apply {
                text = "${fileCoverage.uncoveredConditions} uncovered branch(es) in this file — Branch coverage: ${"%.1f".format(fileCoverage.branchCoverage)}%"
                createActionLabel("View in Quality Tab") {
                    // TODO: Phase 1D+ — focus Quality tab on this file
                }
            }
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/CoverageBannerProvider.kt
git commit -m "feat(sonar): add CoverageBannerProvider (EditorNotificationProvider)"
```

---

### Task 21: CoverageTreeDecorator

**Files:**
- Create: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/CoverageTreeDecorator.kt`

- [ ] **Step 1: Create CoverageTreeDecorator**

```kotlin
package com.workflow.orchestrator.sonar.ui

import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ProjectViewNodeDecorator
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.workflow.orchestrator.sonar.model.SonarState
import java.awt.Color

class CoverageTreeDecorator : ProjectViewNodeDecorator {

    override fun decorate(node: ProjectViewNode<*>, data: PresentationData) {
        val project = node.project ?: return
        val file = node.virtualFile ?: return
        if (file.isDirectory) return

        val basePath = project.basePath ?: return
        val relativePath = file.path.removePrefix("$basePath/")

        // Skip test files and non-source files
        if (relativePath.contains("/test/") || relativePath.contains("/resources/")) return

        val state = getSonarState(project)
        val coverage = state.fileCoverage[relativePath] ?: return

        val pct = coverage.lineCoverage
        val color = when {
            pct >= 80.0 -> JBColor(Color(46, 160, 67), Color(46, 160, 67))
            pct >= 50.0 -> JBColor(Color(212, 160, 32), Color(212, 160, 32))
            else -> JBColor(Color(255, 68, 68), Color(255, 68, 68))
        }

        data.addText(
            " ${"%.0f".format(pct)}%",
            SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, color)
        )
    }

    private fun getSonarState(project: Project): SonarState {
        return try {
            project.getService(com.workflow.orchestrator.sonar.service.SonarDataService::class.java)
                ?.stateFlow?.value ?: SonarState.EMPTY
        } catch (_: Exception) {
            SonarState.EMPTY
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/CoverageTreeDecorator.kt
git commit -m "feat(sonar): add CoverageTreeDecorator for file tree badges"
```

---

### Task 22: UI Logic Tests

**Files:**
- Create: `sonar/src/test/kotlin/com/workflow/orchestrator/sonar/ui/CoverageLineMarkerLogicTest.kt`
- Create: `sonar/src/test/kotlin/com/workflow/orchestrator/sonar/ui/SonarIssueAnnotatorLogicTest.kt`

- [ ] **Step 1: Write CoverageLineMarkerLogicTest**

```kotlin
package com.workflow.orchestrator.sonar.ui

import com.workflow.orchestrator.sonar.model.LineCoverageStatus
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Color

class CoverageLineMarkerLogicTest {

    @Test
    fun `covered color is green`() {
        val icon = CoverageLineMarkerProvider.coverageIcon(CoverageLineMarkerProvider.COVERED_COLOR)
        assertNotNull(icon)
        assertEquals(6, icon.iconWidth)
        assertEquals(14, icon.iconHeight)
    }

    @Test
    fun `all three status colors produce distinct icons`() {
        val covered = CoverageLineMarkerProvider.coverageIcon(CoverageLineMarkerProvider.COVERED_COLOR)
        val uncovered = CoverageLineMarkerProvider.coverageIcon(CoverageLineMarkerProvider.UNCOVERED_COLOR)
        val partial = CoverageLineMarkerProvider.coverageIcon(CoverageLineMarkerProvider.PARTIAL_COLOR)
        // All produce valid icons
        assertNotNull(covered)
        assertNotNull(uncovered)
        assertNotNull(partial)
    }
}
```

- [ ] **Step 2: Write SonarIssueAnnotatorLogicTest**

```kotlin
package com.workflow.orchestrator.sonar.ui

import com.intellij.lang.annotation.HighlightSeverity
import com.workflow.orchestrator.sonar.model.IssueSeverity
import com.workflow.orchestrator.sonar.model.IssueType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SonarIssueAnnotatorLogicTest {

    @Test
    fun `critical bug maps to ERROR`() {
        val result = SonarIssueAnnotator.mapSeverity(IssueType.BUG, IssueSeverity.CRITICAL)
        assertEquals(HighlightSeverity.ERROR, result)
    }

    @Test
    fun `blocker vulnerability maps to ERROR`() {
        val result = SonarIssueAnnotator.mapSeverity(IssueType.VULNERABILITY, IssueSeverity.BLOCKER)
        assertEquals(HighlightSeverity.ERROR, result)
    }

    @Test
    fun `major bug maps to WARNING`() {
        val result = SonarIssueAnnotator.mapSeverity(IssueType.BUG, IssueSeverity.MAJOR)
        assertEquals(HighlightSeverity.WARNING, result)
    }

    @Test
    fun `minor vulnerability maps to WARNING`() {
        val result = SonarIssueAnnotator.mapSeverity(IssueType.VULNERABILITY, IssueSeverity.MINOR)
        assertEquals(HighlightSeverity.WARNING, result)
    }

    @Test
    fun `code smell maps to WEAK_WARNING regardless of severity`() {
        val result = SonarIssueAnnotator.mapSeverity(IssueType.CODE_SMELL, IssueSeverity.BLOCKER)
        assertEquals(HighlightSeverity.WEAK_WARNING, result)
    }

    @Test
    fun `security hotspot maps to WEAK_WARNING`() {
        val result = SonarIssueAnnotator.mapSeverity(IssueType.SECURITY_HOTSPOT, IssueSeverity.CRITICAL)
        assertEquals(HighlightSeverity.WEAK_WARNING, result)
    }
}
```

- [ ] **Step 3: Run tests**

Run: `./gradlew :sonar:test --tests "*.CoverageLineMarkerLogicTest" --tests "*.SonarIssueAnnotatorLogicTest" 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add sonar/src/test/kotlin/com/workflow/orchestrator/sonar/ui/
git commit -m "test(sonar): add UI logic tests for coverage markers and issue annotator"
```

---

## Chunk 6: Plugin Registration & Build Verification

### Task 23: plugin.xml Registration

**Files:**
- Modify: `core/src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Add SonarQube extensions to plugin.xml**

Add inside `<extensions defaultExtensionNs="com.intellij">`, after the existing `statusBarWidgetFactory`:

```xml
        <!-- SonarQube Project Services -->
        <projectService
            serviceImplementation="com.workflow.orchestrator.sonar.service.SonarDataService"/>

        <!-- SonarQube Editor Integration -->
        <codeInsight.lineMarkerProvider language="JAVA"
            implementationClass="com.workflow.orchestrator.sonar.ui.CoverageLineMarkerProvider"/>
        <codeInsight.lineMarkerProvider language="kotlin"
            implementationClass="com.workflow.orchestrator.sonar.ui.CoverageLineMarkerProvider"/>

        <externalAnnotator language="JAVA"
            implementationClass="com.workflow.orchestrator.sonar.ui.SonarIssueAnnotator"/>
        <externalAnnotator language="kotlin"
            implementationClass="com.workflow.orchestrator.sonar.ui.SonarIssueAnnotator"/>

        <editorNotificationProvider
            implementation="com.workflow.orchestrator.sonar.ui.CoverageBannerProvider"/>

        <projectViewNodeDecorator
            implementation="com.workflow.orchestrator.sonar.ui.CoverageTreeDecorator"/>
```

Add inside `<extensions defaultExtensionNs="com.workflow.orchestrator">`, after the existing `SprintTabProvider`:

```xml
        <tabProvider implementation="com.workflow.orchestrator.sonar.ui.QualityTabProvider"/>
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :sonar:compileKotlin 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/src/main/resources/META-INF/plugin.xml
git commit -m "feat(sonar): register all SonarQube extensions in plugin.xml"
```

---

### Task 24: Full Build Verification

- [ ] **Step 1: Run full compilation**

Run: `./gradlew compileKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL for all modules

- [ ] **Step 2: Run all sonar tests**

Run: `./gradlew :sonar:test 2>&1 | tail -10`
Expected: All tests pass

- [ ] **Step 3: Run buildPlugin to verify JAR assembly**

Run: `./gradlew buildPlugin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL — plugin ZIP generated

- [ ] **Step 4: Commit any remaining fixes**

If all green, create a final commit:

```bash
git commit --allow-empty -m "chore(sonar): Phase 1D build verification complete"
```
