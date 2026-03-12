# Configurable Values & Intent-Based Workflow Engine — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract 20 hardcoded values into user-configurable settings and build an intent-based Jira workflow engine that replaces hardcoded status strings with dynamic transition resolution.

**Architecture:** The core change is a 5-layer intent-based workflow engine (WorkflowIntent → IntentResolver → GuardChain → FieldResolver → TransitionExecutor) that dynamically discovers Jira transitions at runtime. All other changes are simple settings extractions where hardcoded constants become `PluginSettings` fields read at runtime. A shared `HttpClientFactory` (already exists) gets wired to configurable timeouts.

**Tech Stack:** Kotlin 2.1.10, IntelliJ Platform 2025.1, kotlinx.serialization, OkHttp, IntelliJ UI DSL v2, MockWebServer (tests)

**Spec:** `docs/superpowers/specs/2026-03-12-configurable-values-workflow-engine-design.md`

---

## File Structure

### New Files

| File | Responsibility |
|---|---|
| `core/src/main/kotlin/com/workflow/orchestrator/core/workflow/WorkflowIntent.kt` | Enum of semantic intents (START_WORK, SUBMIT_FOR_REVIEW, CLOSE, REOPEN, BLOCK) |
| `core/src/main/kotlin/com/workflow/orchestrator/core/workflow/TransitionGuard.kt` | Guard interface + GuardResult sealed class |
| `core/src/main/kotlin/com/workflow/orchestrator/core/settings/AdvancedConfigurable.kt` | Advanced settings UI page |
| `jira/src/main/kotlin/com/workflow/orchestrator/jira/workflow/TransitionMappingStore.kt` | JSON-backed persistence of intent→transition mappings |
| `jira/src/main/kotlin/com/workflow/orchestrator/jira/workflow/IntentResolver.kt` | 6-step resolution: explicit → learned → API → name → category → disambiguate |
| `jira/src/main/kotlin/com/workflow/orchestrator/jira/workflow/TransitionExecutor.kt` | Builds POST body with fields/comment/worklog, executes transition |
| `jira/src/main/kotlin/com/workflow/orchestrator/jira/workflow/DisambiguationHelper.kt` | EDT popup for multi-match transition selection + learned mapping persistence |
| `jira/src/main/kotlin/com/workflow/orchestrator/jira/settings/WorkflowMappingConfigurable.kt` | Workflow Mapping settings UI page |
| `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/CoverageThresholds.kt` | Shared threshold→color utility (replaces 3x duplicated logic) |

### New Test Files

| File | Tests |
|---|---|
| `jira/src/test/kotlin/com/workflow/orchestrator/jira/workflow/TransitionMappingStoreTest.kt` | Save/retrieve/clear/round-trip |
| `jira/src/test/kotlin/com/workflow/orchestrator/jira/workflow/IntentResolverTest.kt` | All 6 resolution steps + error cases |
| `jira/src/test/kotlin/com/workflow/orchestrator/jira/workflow/TransitionExecutorTest.kt` | Payload building for various field combos |
| `jira/src/test/resources/fixtures/jira-transitions-with-fields.json` | Fixture: transitions with hasScreen + fields metadata |
| `sonar/src/test/kotlin/com/workflow/orchestrator/sonar/ui/CoverageThresholdsTest.kt` | Threshold→color mapping |
| `handover/src/test/kotlin/com/workflow/orchestrator/handover/service/PrServiceTemplateTest.kt` | PR title template rendering |

### Key Modified Files

| File | What Changes |
|---|---|
| `core/.../settings/PluginSettings.kt` | +20 new fields |
| `core/.../http/HttpClientFactory.kt` | Read timeouts from PluginSettings |
| `jira/.../api/dto/JiraDtos.kt` | Extend JiraTransition with fields metadata DTOs |
| `jira/.../api/JiraApiClient.kt` | expandFields param, enhanced transitionIssue(), board type param |
| `jira/.../service/BranchingService.kt` | Use IntentResolver instead of hardcoded "In Progress" |
| `handover/.../model/HandoverModels.kt` | Add currentStatusName to HandoverState |
| `handover/.../service/PrService.kt` | Configurable title format + reviewers |
| `handover/.../api/dto/BitbucketDtos.kt` | Add reviewers to BitbucketPrRequest |
| `sonar/.../ui/CoverageTreeDecorator.kt` | Use CoverageThresholds utility |
| `sonar/.../ui/OverviewPanel.kt` | Use CoverageThresholds utility |
| `automation/.../service/TagBuilderService.kt` | Configurable variable name + case-insensitive fix |
| `automation/.../service/TagHistoryService.kt` | Wire tagHistoryMaxEntries from settings |
| `handover/.../service/HandoverStateService.kt` | Populate currentStatusName from Jira transitions |
| `sonar/.../service/CoverageMapper.kt` | Handle new_coverage and new_branch_coverage metrics |

---

## Chunk 1: Core Foundation (Settings + WorkflowIntent + Guards)

### Task 1: Add 20 new settings fields to PluginSettings

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/settings/PluginSettings.kt`

- [ ] **Step 1: Add all 20 new fields to PluginSettings.State**

Add these fields inside the `State` class, after the existing `startWorkTimestamp` field (line 81):

```kotlin
// --- Configurable values (Phase: Config Extraction) ---

// Jira workflow mapping (serialized JSON of TransitionMapping list)
var workflowMappings by string("")

// Jira board type filter ("scrum", "kanban", or "" for all)
var jiraBoardType by string("scrum")

// Plugin guards (transition prerequisites)
var guardBuildPassedBeforeReview by property(false)
var guardCopyrightBeforeClose by property(false)
var guardCoverageBeforeReview by property(false)
var guardAutomationBeforeClose by property(false)

// Coverage thresholds (percentage)
var coverageHighThreshold by property(80.0)
var coverageMediumThreshold by property(50.0)

// HTTP timeouts (seconds) — applied to all API clients via HttpClientFactory
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

// Cody AI
var maxDiffLinesForReview by property(10000)

// SonarQube
var sonarMetricKeys by string("coverage,line_coverage,branch_coverage,uncovered_lines,uncovered_conditions,new_coverage,new_branch_coverage")

// Automation
var tagHistoryMaxEntries by property(5)
var bambooBuildVariableName by string("dockerTagsAsJson")
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :core:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/settings/PluginSettings.kt
git commit -m "feat(settings): add 20 new configurable value fields to PluginSettings"
```

---

### Task 2: Create WorkflowIntent enum

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/workflow/WorkflowIntent.kt`

- [ ] **Step 1: Create the enum**

```kotlin
package com.workflow.orchestrator.core.workflow

enum class WorkflowIntent(
    val displayName: String,
    val defaultNames: List<String>,
    val targetCategory: String?
) {
    START_WORK(
        displayName = "Start Work",
        defaultNames = listOf(
            "In Progress", "Start Progress", "Begin Work",
            "Start Development", "Begin Development"
        ),
        targetCategory = "indeterminate"
    ),
    SUBMIT_FOR_REVIEW(
        displayName = "Submit for Review",
        defaultNames = listOf(
            "In Review", "Submit for Review", "Ready for Review",
            "Code Review", "Peer Review"
        ),
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
        targetCategory = null
    )
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :core:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/workflow/WorkflowIntent.kt
git commit -m "feat(core): add WorkflowIntent enum for semantic transition mapping"
```

---

### Task 3: Create TransitionGuard interface

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/workflow/TransitionGuard.kt`

- [ ] **Step 1: Create guard interface and result types**

```kotlin
package com.workflow.orchestrator.core.workflow

import com.intellij.openapi.project.Project

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

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :core:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/workflow/TransitionGuard.kt
git commit -m "feat(core): add TransitionGuard interface and GuardResult sealed class"
```

---

### Task 4: Wire HTTP timeouts from settings to all API clients

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/http/HttpClientFactory.kt`
- Modify: `jira/src/main/kotlin/com/workflow/orchestrator/jira/api/JiraApiClient.kt`
- Modify: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/api/BambooApiClient.kt`
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/api/SonarApiClient.kt`
- Modify: `automation/src/main/kotlin/com/workflow/orchestrator/automation/api/NexusApiClient.kt` (if exists)

The `HttpClientFactory` already accepts `connectTimeoutSeconds` and `readTimeoutSeconds` as constructor params. The change is at the **call sites** — each API client constructs its own `OkHttpClient` with hardcoded timeouts.

- [ ] **Step 1: Add a convenience factory method that reads from PluginSettings**

In `HttpClientFactory.kt`, add a companion factory method:

```kotlin
companion object {
    fun fromSettings(project: Project, tokenProvider: (ServiceType) -> String?): HttpClientFactory {
        val settings = PluginSettings.getInstance(project)
        return HttpClientFactory(
            tokenProvider = tokenProvider,
            connectTimeoutSeconds = settings.state.httpConnectTimeoutSeconds.toLong(),
            readTimeoutSeconds = settings.state.httpReadTimeoutSeconds.toLong()
        )
    }
}
```

- [ ] **Step 2: Update JiraApiClient to use settings-based timeouts**

In `JiraApiClient.kt` (lines 26-32), replace the hardcoded `OkHttpClient` construction:

```kotlin
// Before:
private val client = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()

// After:
private val client: OkHttpClient

init {
    val settings = com.workflow.orchestrator.core.settings.PluginSettings.getInstance(project)
    client = OkHttpClient.Builder()
        .connectTimeout(settings.state.httpConnectTimeoutSeconds.toLong(), TimeUnit.SECONDS)
        .readTimeout(settings.state.httpReadTimeoutSeconds.toLong(), TimeUnit.SECONDS)
        .build()
}
```

Apply the same pattern to `BambooApiClient`, `SonarApiClient`, and `NexusApiClient` if they have similar hardcoded timeout construction.

- [ ] **Step 3: Run tests**

Run: `./gradlew :core:test && ./gradlew :jira:test && ./gradlew :bamboo:test && ./gradlew :sonar:test`
Expected: All pass

- [ ] **Step 4: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/http/HttpClientFactory.kt \
       jira/src/main/kotlin/com/workflow/orchestrator/jira/api/JiraApiClient.kt \
       bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/api/BambooApiClient.kt \
       sonar/src/main/kotlin/com/workflow/orchestrator/sonar/api/SonarApiClient.kt
git commit -m "feat(core): wire configurable HTTP timeouts from settings to all API clients"
```

---

### Verification: Chunk 1

Run: `./gradlew :core:compileKotlin`
Expected: BUILD SUCCESSFUL — 20 new settings fields, WorkflowIntent enum, TransitionGuard interface all compile.

---

## Chunk 2: Jira Workflow Engine (DTOs + MappingStore + Resolver + Executor)

### Task 5: Extend JiraTransition DTO with field metadata

**Files:**
- Modify: `jira/src/main/kotlin/com/workflow/orchestrator/jira/api/dto/JiraDtos.kt`

- [ ] **Step 1: Add new DTOs and extend JiraTransition**

Replace the existing `JiraTransition` data class (lines 130-135) and add new DTOs after it:

```kotlin
@Serializable
data class JiraTransition(
    val id: String,
    val name: String,
    val to: JiraStatus,
    val hasScreen: Boolean = false,
    val isGlobal: Boolean = false,
    val isConditional: Boolean = false,
    val fields: Map<String, JiraTransitionFieldMeta>? = null
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

- [ ] **Step 2: Verify existing tests still pass with new optional fields**

Run: `./gradlew :jira:test`
Expected: All existing tests pass. The new fields have defaults so existing JSON fixtures deserialize fine.

- [ ] **Step 3: Commit**

```bash
git add jira/src/main/kotlin/com/workflow/orchestrator/jira/api/dto/JiraDtos.kt
git commit -m "feat(jira): extend JiraTransition DTO with field metadata for transition screens"
```

---

### Task 6: Create test fixture for transitions with fields

**Files:**
- Create: `jira/src/test/resources/fixtures/jira-transitions-with-fields.json`

- [ ] **Step 1: Create the fixture**

```json
{
  "transitions": [
    {
      "id": "21",
      "name": "In Progress",
      "to": {
        "id": "3",
        "name": "In Progress",
        "statusCategory": { "id": 4, "key": "indeterminate", "name": "In Progress" }
      },
      "hasScreen": false,
      "isGlobal": false,
      "isConditional": false,
      "fields": {}
    },
    {
      "id": "31",
      "name": "Code Review",
      "to": {
        "id": "5",
        "name": "In Review",
        "statusCategory": { "id": 4, "key": "indeterminate", "name": "In Progress" }
      },
      "hasScreen": true,
      "isGlobal": false,
      "isConditional": false,
      "fields": {
        "assignee": {
          "required": true,
          "name": "Assignee",
          "schema": { "type": "user", "system": "assignee" },
          "hasDefaultValue": false,
          "autoCompleteUrl": "https://jira.example.com/rest/api/2/user/assignable/search?issueKey=PROJ-123&query="
        }
      }
    },
    {
      "id": "41",
      "name": "Done",
      "to": {
        "id": "6",
        "name": "Done",
        "statusCategory": { "id": 3, "key": "done", "name": "Done" }
      },
      "hasScreen": true,
      "isGlobal": false,
      "isConditional": false,
      "fields": {
        "resolution": {
          "required": true,
          "name": "Resolution",
          "schema": { "type": "resolution", "system": "resolution" },
          "allowedValues": [
            { "id": "1", "name": "Fixed", "description": "A fix for this issue is checked in." },
            { "id": "2", "name": "Won't Fix", "description": "Will not be fixed." }
          ],
          "hasDefaultValue": false
        }
      }
    }
  ]
}
```

- [ ] **Step 2: Commit**

```bash
git add jira/src/test/resources/fixtures/jira-transitions-with-fields.json
git commit -m "test(jira): add fixture for transitions with screen fields metadata"
```

---

### Task 7: Create TransitionMappingStore with tests

**Files:**
- Create: `jira/src/main/kotlin/com/workflow/orchestrator/jira/workflow/TransitionMappingStore.kt`
- Create: `jira/src/test/kotlin/com/workflow/orchestrator/jira/workflow/TransitionMappingStoreTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
package com.workflow.orchestrator.jira.workflow

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TransitionMappingStoreTest {

    private lateinit var store: TransitionMappingStore

    @Before
    fun setUp() {
        store = TransitionMappingStore()
    }

    @Test
    fun `save and retrieve explicit mapping`() {
        val mapping = TransitionMapping(
            intent = "START_WORK",
            transitionName = "Begin Development",
            projectKey = "PROJ",
            issueTypeId = null,
            source = "explicit"
        )
        store.saveMapping(mapping)

        val result = store.getMapping("START_WORK", "PROJ")
        assertNotNull(result)
        assertEquals("Begin Development", result!!.transitionName)
        assertEquals("explicit", result.source)
    }

    @Test
    fun `return null for missing mapping`() {
        val result = store.getMapping("START_WORK", "PROJ")
        assertNull(result)
    }

    @Test
    fun `clear mapping removes it`() {
        store.saveMapping(TransitionMapping("START_WORK", "In Progress", "PROJ", null, "learned"))
        store.clearMapping("START_WORK", "PROJ")
        assertNull(store.getMapping("START_WORK", "PROJ"))
    }

    @Test
    fun `serialize and deserialize round-trip`() {
        store.saveMapping(TransitionMapping("START_WORK", "In Progress", "PROJ", null, "learned"))
        store.saveMapping(TransitionMapping("CLOSE", "Done", "PROJ", null, "explicit"))

        val json = store.toJson()
        val restored = TransitionMappingStore()
        restored.loadFromJson(json)

        assertEquals("In Progress", restored.getMapping("START_WORK", "PROJ")?.transitionName)
        assertEquals("Done", restored.getMapping("CLOSE", "PROJ")?.transitionName)
    }

    @Test
    fun `getAllMappings returns all saved`() {
        store.saveMapping(TransitionMapping("START_WORK", "In Progress", "PROJ", null, "learned"))
        store.saveMapping(TransitionMapping("CLOSE", "Done", "OTHER", null, "explicit"))
        assertEquals(2, store.getAllMappings().size)
    }

    @Test
    fun `issue type specific mapping takes priority`() {
        store.saveMapping(TransitionMapping("START_WORK", "In Progress", "PROJ", null, "learned"))
        store.saveMapping(TransitionMapping("START_WORK", "Begin Bug Fix", "PROJ", "10001", "explicit"))

        val general = store.getMapping("START_WORK", "PROJ")
        assertEquals("In Progress", general?.transitionName)

        val specific = store.getMapping("START_WORK", "PROJ", "10001")
        assertEquals("Begin Bug Fix", specific?.transitionName)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :jira:test --tests "*.TransitionMappingStoreTest"`
Expected: FAIL — class not found

- [ ] **Step 3: Implement TransitionMappingStore**

```kotlin
package com.workflow.orchestrator.jira.workflow

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class TransitionMapping(
    val intent: String,
    val transitionName: String,
    val projectKey: String,
    val issueTypeId: String? = null,
    val source: String
)

class TransitionMappingStore {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val mappings = mutableListOf<TransitionMapping>()

    fun getMapping(intent: String, projectKey: String, issueTypeId: String? = null): TransitionMapping? {
        if (issueTypeId != null) {
            val specific = mappings.find {
                it.intent == intent && it.projectKey == projectKey && it.issueTypeId == issueTypeId
            }
            if (specific != null) return specific
        }
        return mappings.find {
            it.intent == intent && it.projectKey == projectKey && it.issueTypeId == null
        }
    }

    fun saveMapping(mapping: TransitionMapping) {
        mappings.removeAll {
            it.intent == mapping.intent &&
            it.projectKey == mapping.projectKey &&
            it.issueTypeId == mapping.issueTypeId
        }
        mappings.add(mapping)
    }

    fun clearMapping(intent: String, projectKey: String) {
        mappings.removeAll { it.intent == intent && it.projectKey == projectKey }
    }

    fun clearExplicitGlobalMapping(intent: String) {
        mappings.removeAll { it.intent == intent && it.projectKey == "" && it.source == "explicit" }
    }

    fun getAllMappings(): List<TransitionMapping> = mappings.toList()

    fun toJson(): String = json.encodeToString(mappings.toList())

    fun loadFromJson(jsonString: String) {
        if (jsonString.isBlank()) return
        try {
            val loaded = json.decodeFromString<List<TransitionMapping>>(jsonString)
            mappings.clear()
            mappings.addAll(loaded)
        } catch (_: Exception) {
            // Corrupted JSON — start fresh
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :jira:test --tests "*.TransitionMappingStoreTest"`
Expected: All 6 tests PASS

- [ ] **Step 5: Commit**

```bash
git add jira/src/main/kotlin/com/workflow/orchestrator/jira/workflow/TransitionMappingStore.kt \
       jira/src/test/kotlin/com/workflow/orchestrator/jira/workflow/TransitionMappingStoreTest.kt
git commit -m "feat(jira): add TransitionMappingStore with JSON persistence and tests"
```

---

### Task 8: Update JiraApiClient for expanded transitions and enhanced transitionIssue

**Files:**
- Modify: `jira/src/main/kotlin/com/workflow/orchestrator/jira/api/JiraApiClient.kt`

- [ ] **Step 1: Update getTransitions to support field expansion**

Replace the `getTransitions` method (lines 51-53):

```kotlin
suspend fun getTransitions(
    issueKey: String,
    expandFields: Boolean = false
): ApiResult<List<JiraTransition>> {
    val expand = if (expandFields) "?expand=transitions.fields" else ""
    return get<JiraTransitionList>("/rest/api/2/issue/$issueKey/transitions$expand")
        .map { it.transitions }
}
```

- [ ] **Step 2: Update transitionIssue to accept optional fields and comment**

Replace the `transitionIssue` method (lines 55-58):

```kotlin
suspend fun transitionIssue(
    issueKey: String,
    transitionId: String,
    fields: Map<String, Any>? = null,
    comment: String? = null
): ApiResult<Unit> {
    val body = buildTransitionPayload(transitionId, fields, comment)
    return post("/rest/api/2/issue/$issueKey/transitions", body)
}

private fun buildTransitionPayload(
    transitionId: String,
    fields: Map<String, Any>?,
    comment: String?
): String {
    val sb = StringBuilder()
    sb.append("""{"transition":{"id":"$transitionId"}""")

    if (!fields.isNullOrEmpty()) {
        sb.append(""","fields":{""")
        sb.append(fields.entries.joinToString(",") { (k, v) ->
            val valueJson = when (v) {
                is Map<*, *> -> {
                    // Pass through structured values like {"id":"1"} or {"name":"Fixed"}
                    v.entries.joinToString(",", "{", "}") { (mk, mv) ->
                        """"$mk":"$mv""""
                    }
                }
                else -> """{"name":"${v.toString().replace("\"", "\\\"")}"}"""
            }
            """"$k":$valueJson"""
        })
        sb.append("}")
    }

    if (comment != null) {
        val escaped = comment.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        sb.append(""","update":{"comment":[{"add":{"body":"$escaped"}}]}""")
    }

    sb.append("}")
    return sb.toString()
}
```

- [ ] **Step 3: Update getBoards to accept board type parameter**

Replace the `getBoards` method (line 36):

```kotlin
suspend fun getBoards(boardType: String = ""): ApiResult<List<JiraBoard>> {
    val typeFilter = if (boardType.isNotBlank()) "?type=$boardType" else ""
    return get<JiraBoardSearchResult>("/rest/agile/1.0/board$typeFilter").map { it.values }
}
```

- [ ] **Step 4: Verify existing tests pass**

Run: `./gradlew :jira:test`
Expected: All tests pass. The new params have defaults matching old behavior.

- [ ] **Step 5: Commit**

```bash
git add jira/src/main/kotlin/com/workflow/orchestrator/jira/api/JiraApiClient.kt
git commit -m "feat(jira): add field expansion to getTransitions, enhanced transitionIssue with fields/comment"
```

---

### Task 9: Create IntentResolver with tests

**Files:**
- Create: `jira/src/main/kotlin/com/workflow/orchestrator/jira/workflow/IntentResolver.kt`
- Create: `jira/src/test/kotlin/com/workflow/orchestrator/jira/workflow/IntentResolverTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
package com.workflow.orchestrator.jira.workflow

import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.workflow.WorkflowIntent
import com.workflow.orchestrator.jira.api.dto.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class IntentResolverTest {

    private lateinit var mappingStore: TransitionMappingStore

    private val standardTransitions = listOf(
        JiraTransition("21", "In Progress", JiraStatus("3", "In Progress", JiraStatusCategory(4, "indeterminate", "In Progress"))),
        JiraTransition("31", "In Review", JiraStatus("5", "In Review", JiraStatusCategory(4, "indeterminate", "In Progress"))),
        JiraTransition("41", "Done", JiraStatus("6", "Done", JiraStatusCategory(3, "done", "Done")))
    )

    @Before
    fun setUp() {
        mappingStore = TransitionMappingStore()
    }

    @Test
    fun `resolves via explicit mapping when configured`() {
        mappingStore.saveMapping(TransitionMapping("START_WORK", "In Progress", "PROJ", null, "explicit"))
        val result = IntentResolver.resolveFromTransitions(
            WorkflowIntent.START_WORK, standardTransitions, mappingStore, "PROJ"
        )
        assertTrue(result is ApiResult.Success)
        val resolved = (result as ApiResult.Success).data
        assertEquals("21", resolved.transitionId)
        assertEquals(ResolutionMethod.EXPLICIT_MAPPING, resolved.resolution)
    }

    @Test
    fun `resolves via name matching for standard workflow`() {
        val result = IntentResolver.resolveFromTransitions(
            WorkflowIntent.START_WORK, standardTransitions, mappingStore, "PROJ"
        )
        assertTrue(result is ApiResult.Success)
        val resolved = (result as ApiResult.Success).data
        assertEquals("21", resolved.transitionId)
        assertEquals("In Progress", resolved.transitionName)
        assertEquals(ResolutionMethod.NAME_MATCH, resolved.resolution)
    }

    @Test
    fun `resolves via category matching when names differ`() {
        val customTransitions = listOf(
            JiraTransition("99", "Begin Development", JiraStatus("3", "Working", JiraStatusCategory(4, "indeterminate", "In Progress")))
        )
        val result = IntentResolver.resolveFromTransitions(
            WorkflowIntent.START_WORK, customTransitions, mappingStore, "PROJ"
        )
        assertTrue(result is ApiResult.Success)
        val resolved = (result as ApiResult.Success).data
        assertEquals("99", resolved.transitionId)
        assertEquals(ResolutionMethod.CATEGORY_MATCH, resolved.resolution)
    }

    @Test
    fun `returns error when no transitions match`() {
        val doneOnly = listOf(
            JiraTransition("41", "Done", JiraStatus("6", "Done", JiraStatusCategory(3, "done", "Done")))
        )
        val result = IntentResolver.resolveFromTransitions(
            WorkflowIntent.START_WORK, doneOnly, mappingStore, "PROJ"
        )
        assertTrue(result is ApiResult.Error)
    }

    @Test
    fun `returns error for empty transitions`() {
        val result = IntentResolver.resolveFromTransitions(
            WorkflowIntent.START_WORK, emptyList(), mappingStore, "PROJ"
        )
        assertTrue(result is ApiResult.Error)
    }

    @Test
    fun `explicit mapping verified against available transitions`() {
        mappingStore.saveMapping(TransitionMapping("START_WORK", "Nonexistent", "PROJ", null, "explicit"))
        // Falls back to name matching since "Nonexistent" isn't available
        val result = IntentResolver.resolveFromTransitions(
            WorkflowIntent.START_WORK, standardTransitions, mappingStore, "PROJ"
        )
        assertTrue(result is ApiResult.Success)
        val resolved = (result as ApiResult.Success).data
        assertEquals(ResolutionMethod.NAME_MATCH, resolved.resolution)
    }

    @Test
    fun `CLOSE intent resolves Done transition`() {
        val result = IntentResolver.resolveFromTransitions(
            WorkflowIntent.CLOSE, standardTransitions, mappingStore, "PROJ"
        )
        assertTrue(result is ApiResult.Success)
        assertEquals("41", (result as ApiResult.Success).data.transitionId)
    }

    @Test
    fun `returns disambiguation error when multiple category matches`() {
        val ambiguousTransitions = listOf(
            JiraTransition("51", "Complete", JiraStatus("7", "Complete", JiraStatusCategory(3, "done", "Done"))),
            JiraTransition("52", "Resolved", JiraStatus("8", "Resolved", JiraStatusCategory(3, "done", "Done")))
        )
        val result = IntentResolver.resolveFromTransitions(
            WorkflowIntent.CLOSE, ambiguousTransitions, mappingStore, "PROJ"
        )
        assertTrue(result is ApiResult.Error)
        val error = (result as ApiResult.Error)
        assertTrue(error.message.startsWith("DISAMBIGUATE:"))
    }

    @Test
    fun `saves learned mapping after name match`() {
        IntentResolver.resolveFromTransitions(
            WorkflowIntent.START_WORK, standardTransitions, mappingStore, "PROJ"
        )
        val learned = mappingStore.getMapping("START_WORK", "PROJ")
        assertNotNull(learned)
        assertEquals("learned", learned!!.source)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :jira:test --tests "*.IntentResolverTest"`
Expected: FAIL — IntentResolver class not found

- [ ] **Step 3: Implement IntentResolver**

```kotlin
package com.workflow.orchestrator.jira.workflow

import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.core.workflow.WorkflowIntent
import com.workflow.orchestrator.jira.api.dto.JiraTransition

data class ResolvedTransition(
    val transitionId: String,
    val transitionName: String,
    val targetStatusName: String,
    val requiredFields: List<TransitionFieldInfo> = emptyList(),
    val resolution: ResolutionMethod
)

data class TransitionFieldInfo(
    val key: String,
    val name: String,
    val required: Boolean,
    val type: String,
    val allowedValues: List<String> = emptyList(),
    val hasDefaultValue: Boolean = false,
    val autoCompleteUrl: String? = null
)

enum class ResolutionMethod {
    EXPLICIT_MAPPING,
    LEARNED_MAPPING,
    NAME_MATCH,
    CATEGORY_MATCH,
    USER_SELECTED
}

object IntentResolver {

    fun resolveFromTransitions(
        intent: WorkflowIntent,
        transitions: List<JiraTransition>,
        mappingStore: TransitionMappingStore,
        projectKey: String,
        issueTypeId: String? = null
    ): ApiResult<ResolvedTransition> {
        if (transitions.isEmpty()) {
            return ApiResult.Error(
                ErrorType.NOT_FOUND,
                "No transitions available. The issue may already be in a terminal state."
            )
        }

        // Step 1 & 2: Check explicit or learned mapping
        val mapping = mappingStore.getMapping(intent.name, projectKey, issueTypeId)
        if (mapping != null) {
            val matched = transitions.find { it.name.equals(mapping.transitionName, ignoreCase = true) }
            if (matched != null) {
                val method = if (mapping.source == "explicit") ResolutionMethod.EXPLICIT_MAPPING
                             else ResolutionMethod.LEARNED_MAPPING
                return ApiResult.Success(matched.toResolved(method))
            }
            // Mapping exists but transition no longer available — fall through
        }

        // Step 4: Name matching
        for (defaultName in intent.defaultNames) {
            val matched = transitions.find { it.name.equals(defaultName, ignoreCase = true) }
            if (matched != null) {
                mappingStore.saveMapping(
                    TransitionMapping(intent.name, matched.name, projectKey, issueTypeId, "learned")
                )
                return ApiResult.Success(matched.toResolved(ResolutionMethod.NAME_MATCH))
            }
        }

        // Step 5: Category matching
        if (intent.targetCategory != null) {
            val categoryMatches = transitions.filter {
                it.to.statusCategory?.key == intent.targetCategory
            }
            if (categoryMatches.size == 1) {
                val matched = categoryMatches[0]
                mappingStore.saveMapping(
                    TransitionMapping(intent.name, matched.name, projectKey, issueTypeId, "learned")
                )
                return ApiResult.Success(matched.toResolved(ResolutionMethod.CATEGORY_MATCH))
            }
            if (categoryMatches.size > 1) {
                // Step 6: Disambiguation needed — return Ambiguous result
                return ApiResult.Error(
                    ErrorType.VALIDATION,
                    "DISAMBIGUATE:${categoryMatches.joinToString("|") { "${it.id}::${it.name}" }}"
                )
            }
        }

        return ApiResult.Error(
            ErrorType.NOT_FOUND,
            "No transition matches intent '${intent.displayName}'. Configure a mapping in Settings > Workflow Mapping."
        )
    }

    private fun JiraTransition.toResolved(method: ResolutionMethod): ResolvedTransition {
        val fieldInfos = fields?.entries
            ?.filter { it.value.required && !it.value.hasDefaultValue }
            ?.map { (key, meta) ->
                TransitionFieldInfo(
                    key = key,
                    name = meta.name,
                    required = meta.required,
                    type = meta.schema?.type ?: "string",
                    allowedValues = meta.allowedValues?.map { it.name } ?: emptyList(),
                    hasDefaultValue = meta.hasDefaultValue,
                    autoCompleteUrl = meta.autoCompleteUrl
                )
            } ?: emptyList()

        return ResolvedTransition(
            transitionId = id,
            transitionName = name,
            targetStatusName = to.name,
            requiredFields = fieldInfos,
            resolution = method
        )
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :jira:test --tests "*.IntentResolverTest"`
Expected: All 9 tests PASS

- [ ] **Step 5: Commit**

```bash
git add jira/src/main/kotlin/com/workflow/orchestrator/jira/workflow/IntentResolver.kt \
       jira/src/test/kotlin/com/workflow/orchestrator/jira/workflow/IntentResolverTest.kt
git commit -m "feat(jira): add IntentResolver with 6-step transition resolution and tests"
```

---

### Task 9b: Add DisambiguationHelper for multi-match resolution

**Files:**
- Create: `jira/src/main/kotlin/com/workflow/orchestrator/jira/workflow/DisambiguationHelper.kt`

- [ ] **Step 1: Implement the disambiguation helper**

This is the project-level helper that shows a dialog when IntentResolver returns a `DISAMBIGUATE:` error, lets the user pick, and persists the choice as a learned mapping.

```kotlin
package com.workflow.orchestrator.jira.workflow

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.workflow.WorkflowIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

data class TransitionChoice(val id: String, val name: String) {
    override fun toString(): String = name
}

object DisambiguationHelper {

    fun parseDisambiguationError(error: ApiResult.Error): List<TransitionChoice>? {
        if (!error.message.startsWith("DISAMBIGUATE:")) return null
        return error.message.removePrefix("DISAMBIGUATE:")
            .split("|")
            .mapNotNull { entry ->
                val parts = entry.split("::")
                if (parts.size == 2) TransitionChoice(parts[0], parts[1]) else null
            }
    }

    suspend fun showDisambiguationPopup(
        project: Project,
        intent: WorkflowIntent,
        choices: List<TransitionChoice>
    ): TransitionChoice? = withContext(Dispatchers.EDT) {
        suspendCancellableCoroutine { cont ->
            val step = object : BaseListPopupStep<TransitionChoice>(
                "Select transition for '${intent.displayName}'", choices
            ) {
                override fun onChosen(selectedValue: TransitionChoice, finalChoice: Boolean): PopupStep<*>? {
                    cont.resume(selectedValue)
                    return FINAL_CHOICE
                }

                override fun onPopupCancel() {
                    cont.resume(null)
                }
            }
            JBPopupFactory.getInstance().createListPopup(step).showCenteredInCurrentWindow(project)
        }
    }

    fun saveLearnedMapping(
        project: Project,
        intent: WorkflowIntent,
        choice: TransitionChoice,
        projectKey: String
    ) {
        val settings = PluginSettings.getInstance(project)
        val store = TransitionMappingStore()
        store.loadFromJson(settings.state.workflowMappings)
        store.saveMapping(
            TransitionMapping(intent.name, choice.name, projectKey, null, "learned")
        )
        settings.state.workflowMappings = store.toJson()
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :jira:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add jira/src/main/kotlin/com/workflow/orchestrator/jira/workflow/DisambiguationHelper.kt
git commit -m "feat(jira): add DisambiguationHelper for multi-match transition resolution with user popup"
```

---

### Task 10: Create TransitionExecutor with tests

**Files:**
- Create: `jira/src/main/kotlin/com/workflow/orchestrator/jira/workflow/TransitionExecutor.kt`
- Create: `jira/src/test/kotlin/com/workflow/orchestrator/jira/workflow/TransitionExecutorTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
package com.workflow.orchestrator.jira.workflow

import org.junit.Assert.*
import org.junit.Test

class TransitionExecutorTest {

    @Test
    fun `builds minimal payload with transition id only`() {
        val payload = TransitionExecutor.buildPayload("21", null, null)
        assertEquals("""{"transition":{"id":"21"}}""", payload)
    }

    @Test
    fun `builds payload with name-based fields`() {
        val payload = TransitionExecutor.buildPayload(
            "41",
            mapOf("assignee" to "jsmith"),
            null
        )
        assertTrue(payload.contains(""""transition":{"id":"41"}"""))
        assertTrue(payload.contains(""""fields":{"""))
        assertTrue(payload.contains(""""assignee":{"name":"jsmith"}"""))
    }

    @Test
    fun `builds payload with comment`() {
        val payload = TransitionExecutor.buildPayload("21", null, "Test comment")
        assertTrue(payload.contains(""""update":{"comment":[{"add":{"body":"Test comment"}}]}"""))
    }

    @Test
    fun `builds payload with id-based fields`() {
        val payload = TransitionExecutor.buildPayload(
            "41",
            mapOf("resolution" to mapOf("id" to "1")),
            null
        )
        assertTrue(payload.contains(""""resolution":{"id":"1"}"""))
    }

    @Test
    fun `builds payload with fields and comment`() {
        val payload = TransitionExecutor.buildPayload(
            "41",
            mapOf("assignee" to "jsmith"),
            "Closing this"
        )
        assertTrue(payload.contains(""""transition":{"id":"41"}"""))
        assertTrue(payload.contains(""""fields":{"""))
        assertTrue(payload.contains(""""update":{"comment":[{"add":{"body":"Closing this"}}]}"""))
    }

    @Test
    fun `escapes special characters in comment`() {
        val payload = TransitionExecutor.buildPayload("21", null, "Line1\nLine2 with \"quotes\"")
        assertTrue(payload.contains("Line1\\nLine2 with \\\"quotes\\\""))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :jira:test --tests "*.TransitionExecutorTest"`
Expected: FAIL — class not found

- [ ] **Step 3: Implement TransitionExecutor**

```kotlin
package com.workflow.orchestrator.jira.workflow

import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.jira.api.JiraApiClient

class TransitionExecutor(
    private val apiClient: JiraApiClient
) {
    suspend fun execute(
        issueKey: String,
        resolved: ResolvedTransition,
        fieldValues: Map<String, String>? = null,
        comment: String? = null
    ): ApiResult<Unit> {
        return apiClient.transitionIssue(
            issueKey = issueKey,
            transitionId = resolved.transitionId,
            fields = fieldValues,
            comment = comment
        )
    }

    companion object {
        fun buildPayload(
            transitionId: String,
            fields: Map<String, Any>?,
            comment: String?
        ): String {
            val sb = StringBuilder()
            sb.append("""{"transition":{"id":"$transitionId"}""")

            if (!fields.isNullOrEmpty()) {
                sb.append(""","fields":{""")
                sb.append(fields.entries.joinToString(",") { (k, v) ->
                    val valueJson = when (v) {
                        is Map<*, *> -> {
                            v.entries.joinToString(",", "{", "}") { (mk, mv) ->
                                """"$mk":"$mv""""
                            }
                        }
                        else -> {
                            val escaped = v.toString().replace("\\", "\\\\").replace("\"", "\\\"")
                            """{"name":"$escaped"}"""
                        }
                    }
                    """"$k":$valueJson"""
                })
                sb.append("}")
            }

            if (comment != null) {
                val escaped = comment
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                sb.append(""","update":{"comment":[{"add":{"body":"$escaped"}}]}""")
            }

            sb.append("}")
            return sb.toString()
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :jira:test --tests "*.TransitionExecutorTest"`
Expected: All 6 tests PASS

- [ ] **Step 5: Commit**

```bash
git add jira/src/main/kotlin/com/workflow/orchestrator/jira/workflow/TransitionExecutor.kt \
       jira/src/test/kotlin/com/workflow/orchestrator/jira/workflow/TransitionExecutorTest.kt
git commit -m "feat(jira): add TransitionExecutor with payload building and tests"
```

---

### Verification: Chunk 2

Run: `./gradlew :jira:test`
Expected: All tests pass, including new IntentResolver, TransitionMappingStore, and TransitionExecutor tests.

---

## Chunk 3: Wire Consumers (BranchingService, HandoverPanel, CompletionMacro)

### Task 11: Update BranchingService to use IntentResolver

**Files:**
- Modify: `jira/src/main/kotlin/com/workflow/orchestrator/jira/service/BranchingService.kt`

- [ ] **Step 1: Replace transitionToInProgress with intent-based resolution**

Replace the `transitionToInProgress` method (lines 54-67) with:

```kotlin
private suspend fun transitionToInProgress(issueKey: String): ApiResult<Unit> {
    val transitionsResult = apiClient.getTransitions(issueKey, expandFields = true)
    val transitions = when (transitionsResult) {
        is ApiResult.Success -> transitionsResult.data
        is ApiResult.Error -> return transitionsResult
    }

    val projectKey = issueKey.substringBefore("-")
    val mappingStore = TransitionMappingStore().also {
        it.loadFromJson(
            com.workflow.orchestrator.core.settings.PluginSettings.getInstance(project).state.workflowMappings ?: ""
        )
    }

    val resolved = IntentResolver.resolveFromTransitions(
        com.workflow.orchestrator.core.workflow.WorkflowIntent.START_WORK,
        transitions,
        mappingStore,
        projectKey
    )

    when (resolved) {
        is ApiResult.Error -> return resolved
        is ApiResult.Success -> {
            // Persist learned mapping
            val settings = com.workflow.orchestrator.core.settings.PluginSettings.getInstance(project)
            settings.state.workflowMappings = mappingStore.toJson()

            return apiClient.transitionIssue(issueKey, resolved.data.transitionId)
        }
    }
}
```

Add import at top of file:
```kotlin
import com.workflow.orchestrator.jira.workflow.IntentResolver
import com.workflow.orchestrator.jira.workflow.TransitionMappingStore
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :jira:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Run existing tests**

Run: `./gradlew :jira:test`
Expected: All tests pass

- [ ] **Step 4: Commit**

```bash
git add jira/src/main/kotlin/com/workflow/orchestrator/jira/service/BranchingService.kt
git commit -m "refactor(jira): replace hardcoded 'In Progress' with IntentResolver in BranchingService"
```

---

### Task 12: Update HandoverState and HandoverContextPanel

**Files:**
- Modify: `handover/src/main/kotlin/com/workflow/orchestrator/handover/model/HandoverModels.kt`
- Modify: `handover/src/main/kotlin/com/workflow/orchestrator/handover/ui/HandoverContextPanel.kt`

- [ ] **Step 1: Add currentStatusName to HandoverState**

In `HandoverModels.kt`, add a new field to `HandoverState` (after `jiraTransitioned`):

```kotlin
val jiraTransitioned: Boolean = false,
val currentStatusName: String? = null,  // NEW — actual status name from Jira
```

- [ ] **Step 1b: Update HandoverStateService to populate currentStatusName**

Modify `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/HandoverStateService.kt`. After a Jira transition is performed, the service should capture the target status name and set it on the state:

```kotlin
// In the method that performs/records a transition, add:
state = state.copy(currentStatusName = resolvedTransition.targetStatusName)
```

If the service fetches ticket status on refresh, also populate `currentStatusName` from the Jira issue's current status name.

- [ ] **Step 2: Update HandoverContextPanel to use actual status name**

In `HandoverContextPanel.kt`, replace line 88:

```kotlin
// Before:
ticketStatusLabel.text = if (state.jiraTransitioned) "Status: In Review" else "Status: In Progress"

// After:
ticketStatusLabel.text = "Status: ${state.currentStatusName ?: "Unknown"}"
```

- [ ] **Step 3: Update CompletionMacroService step label to use dynamic resolution**

In `CompletionMacroService.kt`, replace line 22 with a dynamic label. The service should read the workflow mapping to show the actual transition name:

```kotlin
// Before:
MacroStep(id = "jira-transition", label = "Transition to In Review"),

// After:
MacroStep(id = "jira-transition", label = getReviewTransitionLabel()),
```

Add a helper method:

```kotlin
private fun getReviewTransitionLabel(): String {
    val proj = project ?: return "Transition to Review"
    val settings = com.workflow.orchestrator.core.settings.PluginSettings.getInstance(proj)
    val store = com.workflow.orchestrator.jira.workflow.TransitionMappingStore()
    store.loadFromJson(settings.state.workflowMappings)
    val mapping = store.getMapping("SUBMIT_FOR_REVIEW", "")
    return if (mapping != null) "Transition to ${mapping.transitionName}" else "Transition to Review"
}
```

Note: `CompletionMacroService` is a project-level `@Service` with a `project` field, so it can access settings. If `getDefaultSteps()` is called from the constructor, defer the label resolution to first access or use lazy initialization.

- [ ] **Step 4: Verify compilation**

Run: `./gradlew :handover:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Run existing tests**

Run: `./gradlew :handover:test`
Expected: All tests pass

- [ ] **Step 6: Commit**

```bash
git add handover/src/main/kotlin/com/workflow/orchestrator/handover/model/HandoverModels.kt \
       handover/src/main/kotlin/com/workflow/orchestrator/handover/ui/HandoverContextPanel.kt \
       handover/src/main/kotlin/com/workflow/orchestrator/handover/service/CompletionMacroService.kt
git commit -m "refactor(handover): replace hardcoded status strings with dynamic currentStatusName"
```

---

### Task 13: Update BranchNameValidator to read max length from settings

**Files:**
- Modify: `jira/src/main/kotlin/com/workflow/orchestrator/jira/service/BranchNameValidator.kt`

- [ ] **Step 1: Make MAX_SUMMARY_LENGTH a parameter**

```kotlin
object BranchNameValidator {

    private val TICKET_PATTERN = Regex("[A-Z][A-Z0-9]+-\\d+")
    private val INVALID_CHARS = Regex("[^a-z0-9/\\-]")

    fun generateBranchName(pattern: String, ticketId: String, summary: String, maxSummaryLength: Int = 50): String {
        val sanitizedSummary = summary
            .lowercase()
            .replace(INVALID_CHARS, "-")
            .replace(Regex("-+"), "-")
            .trim('-')
            .take(maxSummaryLength)
            .trimEnd('-')

        return pattern
            .replace("{ticketId}", ticketId)
            .replace("{summary}", sanitizedSummary)
    }

    fun isValidBranchName(name: String): Boolean {
        if (name.isBlank()) return false
        return TICKET_PATTERN.containsMatchIn(name)
    }
}
```

- [ ] **Step 2: Update caller in BranchingService to pass settings value**

In `BranchingService.kt`, update the `startWork` method's call to `generateBranchName` (around line 19):

```kotlin
val settings = com.workflow.orchestrator.core.settings.PluginSettings.getInstance(project)
val branchName = BranchNameValidator.generateBranchName(
    pattern = branchPattern,
    ticketId = issue.key,
    summary = issue.fields.summary,
    maxSummaryLength = settings.state.branchMaxSummaryLength
)
```

- [ ] **Step 3: Run tests**

Run: `./gradlew :jira:test`
Expected: All tests pass

- [ ] **Step 4: Commit**

```bash
git add jira/src/main/kotlin/com/workflow/orchestrator/jira/service/BranchNameValidator.kt \
       jira/src/main/kotlin/com/workflow/orchestrator/jira/service/BranchingService.kt
git commit -m "feat(jira): make branch name max length configurable via settings"
```

---

### Verification: Chunk 3

Run: `./gradlew :jira:test && ./gradlew :handover:test`
Expected: All tests pass

---

## Chunk 4: Sonar & Bamboo Configurability

### Task 14: Create CoverageThresholds utility and wire consumers

**Files:**
- Create: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/CoverageThresholds.kt`
- Create: `sonar/src/test/kotlin/com/workflow/orchestrator/sonar/ui/CoverageThresholdsTest.kt`
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/CoverageTreeDecorator.kt`
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/OverviewPanel.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package com.workflow.orchestrator.sonar.ui

import org.junit.Assert.*
import org.junit.Test

class CoverageThresholdsTest {

    @Test
    fun `high coverage returns GREEN`() {
        val color = CoverageThresholds.colorForCoverage(90.0, 80.0, 50.0)
        assertEquals(CoverageThresholds.GREEN, color)
    }

    @Test
    fun `medium coverage returns YELLOW`() {
        val color = CoverageThresholds.colorForCoverage(65.0, 80.0, 50.0)
        assertEquals(CoverageThresholds.YELLOW, color)
    }

    @Test
    fun `low coverage returns RED`() {
        val color = CoverageThresholds.colorForCoverage(30.0, 80.0, 50.0)
        assertEquals(CoverageThresholds.RED, color)
    }

    @Test
    fun `exact threshold boundary is GREEN`() {
        val color = CoverageThresholds.colorForCoverage(80.0, 80.0, 50.0)
        assertEquals(CoverageThresholds.GREEN, color)
    }

    @Test
    fun `custom thresholds respected`() {
        val color = CoverageThresholds.colorForCoverage(95.0, 99.0, 90.0)
        assertEquals(CoverageThresholds.YELLOW, color)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :sonar:test --tests "*.CoverageThresholdsTest"`
Expected: FAIL

- [ ] **Step 3: Implement CoverageThresholds**

```kotlin
package com.workflow.orchestrator.sonar.ui

import com.intellij.ui.JBColor
import java.awt.Color

object CoverageThresholds {

    val GREEN = JBColor(Color(46, 160, 67), Color(46, 160, 67))
    val YELLOW = JBColor(Color(212, 160, 32), Color(212, 160, 32))
    val RED = JBColor(Color(255, 68, 68), Color(255, 68, 68))

    fun colorForCoverage(pct: Double, highThreshold: Double, mediumThreshold: Double): JBColor {
        return when {
            pct >= highThreshold -> GREEN
            pct >= mediumThreshold -> YELLOW
            else -> RED
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :sonar:test --tests "*.CoverageThresholdsTest"`
Expected: All 5 tests PASS

- [ ] **Step 5: Update CoverageTreeDecorator to use CoverageThresholds**

In `CoverageTreeDecorator.kt`, replace lines 39-43:

```kotlin
// Before:
val color = when {
    pct >= 80.0 -> JBColor(Color(46, 160, 67), Color(46, 160, 67))
    pct >= 50.0 -> JBColor(Color(212, 160, 32), Color(212, 160, 32))
    else -> JBColor(Color(255, 68, 68), Color(255, 68, 68))
}

// After:
val settings = com.workflow.orchestrator.core.settings.PluginSettings.getInstance(project).state
val color = CoverageThresholds.colorForCoverage(
    pct, settings.coverageHighThreshold, settings.coverageMediumThreshold
)
```

Remove the unused `import java.awt.Color` and `import com.intellij.ui.JBColor` if they become unused (CoverageThresholds handles colors internally).

- [ ] **Step 6: Update OverviewPanel to use CoverageThresholds**

Read `OverviewPanel.kt` fully. Search for all occurrences of `80.0`, `50.0`, `Color(46, 160, 67)`, `Color(212, 160, 32)`, `Color(255, 68, 68)`, and any `when` blocks that select colors based on coverage percentages. Replace each occurrence with:

```kotlin
val settings = com.workflow.orchestrator.core.settings.PluginSettings.getInstance(project).state
val color = CoverageThresholds.colorForCoverage(
    pct, settings.coverageHighThreshold, settings.coverageMediumThreshold
)
```

There are typically 2 occurrences in OverviewPanel: one in the summary section and one in the per-file table renderer. Replace both. Remove any now-unused `Color` or `JBColor` imports.

- [ ] **Step 7: Run all sonar tests**

Run: `./gradlew :sonar:test`
Expected: All tests pass

- [ ] **Step 8: Commit**

```bash
git add sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/CoverageThresholds.kt \
       sonar/src/test/kotlin/com/workflow/orchestrator/sonar/ui/CoverageThresholdsTest.kt \
       sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/CoverageTreeDecorator.kt \
       sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/OverviewPanel.kt
git commit -m "refactor(sonar): extract coverage thresholds to shared configurable utility"
```

---

### Task 15: Make SonarQube metric keys configurable and update CoverageMapper

**Files:**
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/api/SonarApiClient.kt`
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarDataService.kt`
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/CoverageMapper.kt`

- [ ] **Step 1: Update getMeasures to accept metric keys parameter**

Find the method that hardcodes `metricKeys=coverage,line_coverage,...` (around line 68) and replace:

```kotlin
// Before: hardcoded metric keys string
// After: accept as parameter
suspend fun getMeasures(projectKey: String, metricKeys: String): ApiResult<...> {
    return get("...?metricKeys=$metricKeys&...")
}
```

- [ ] **Step 2: Update SonarDataService caller to read from settings**

The caller (SonarDataService) should read `PluginSettings.sonarMetricKeys` and pass it to `getMeasures()`.

- [ ] **Step 3: Update CoverageMapper to handle new metrics**

Read `CoverageMapper.kt` and add mapping logic for the new `new_coverage` and `new_branch_coverage` metrics. These should be mapped to appropriate fields in the coverage model so they can be displayed in the UI.

- [ ] **Step 4: Verify tests pass**

Run: `./gradlew :sonar:test`
Expected: All pass

- [ ] **Step 5: Commit**

```bash
git add sonar/src/main/kotlin/com/workflow/orchestrator/sonar/api/SonarApiClient.kt \
       sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarDataService.kt \
       sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/CoverageMapper.kt
git commit -m "feat(sonar): make metric keys configurable, update CoverageMapper for new metrics"
```

---

### Task 16: Fix default branch fallbacks and TagBuilderService case sensitivity

**Files:**
- Modify: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/BuildDashboardPanel.kt`
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarDataService.kt`
- Modify: `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/TagBuilderService.kt`

- [ ] **Step 1: Fix BuildDashboardPanel default branch**

Find `"master"` fallback (around line 137) and replace:

```kotlin
// Before:
val branch = getCurrentBranch() ?: "master"

// After:
val branch = getCurrentBranch()
    ?: com.workflow.orchestrator.core.settings.PluginSettings.getInstance(project).state.defaultTargetBranch
    ?: "develop"
```

- [ ] **Step 2: Fix SonarDataService default branch**

Find `"main"` fallback (around line 40) and replace similarly with `PluginSettings.defaultTargetBranch`.

- [ ] **Step 3: Fix TagBuilderService case-sensitive state match**

Find the case-sensitive comparisons (around line 54) and fix:

```kotlin
// Before:
val successStages = dto.stages.stage.count { it.state == "Successful" }
val failedStages = dto.stages.stage.count { it.state == "Failed" }

// After:
val successStages = dto.stages.stage.count { it.state.equals("Successful", ignoreCase = true) }
val failedStages = dto.stages.stage.count { it.state.equals("Failed", ignoreCase = true) }
```

- [ ] **Step 4: Make bambooBuildVariableName configurable in TagBuilderService**

Find **both** `"dockerTagsAsJson"` hardcoded strings: line 49 (reading the variable) and line 118 (writing the variable as a key in the build trigger payload). Replace both with the settings value. The TagBuilderService needs a project reference to access PluginSettings. If it doesn't have one, pass the variable name as a parameter from the caller.

```kotlin
// Line 49 (reading):
val variableName = bambooBuildVariableName  // from settings or parameter
val existingTag = buildResult.variables?.get(variableName)

// Line 118 (writing):
result[variableName] = buildJsonPayload(entries)
```

- [ ] **Step 5: Run affected tests**

Run: `./gradlew :bamboo:test && ./gradlew :sonar:test && ./gradlew :automation:test`
Expected: All pass

- [ ] **Step 6: Commit**

```bash
git add bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/BuildDashboardPanel.kt \
       sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarDataService.kt \
       automation/src/main/kotlin/com/workflow/orchestrator/automation/service/TagBuilderService.kt
git commit -m "fix: unify default branch fallbacks, fix case-sensitive state match, configurable build variable name"
```

---

### Task 16b: Wire tagHistoryMaxEntries to TagHistoryService

**Files:**
- Modify: `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/TagHistoryService.kt`

- [ ] **Step 1: Update callers of getHistory() to pass settings value**

In `TagHistoryService.kt`, the `getHistory` method has `limit: Int = 5` as a default parameter (line 124). Find all callers of `getHistory()` (likely in the same service or in `StagingPanel`) and update them to pass the settings value:

```kotlin
val settings = com.workflow.orchestrator.core.settings.PluginSettings.getInstance(project)
val history = tagHistoryService.getHistory(suitePlanKey, limit = settings.state.tagHistoryMaxEntries)
```

If callers are in UI code without direct project access, pass the limit as a parameter from the caller.

- [ ] **Step 2: Run tests**

Run: `./gradlew :automation:test`
Expected: All pass

- [ ] **Step 3: Commit**

```bash
git add automation/src/main/kotlin/com/workflow/orchestrator/automation/service/TagHistoryService.kt
git commit -m "feat(automation): wire tagHistoryMaxEntries setting to TagHistoryService callers"
```

---

### Task 17: Make dockerTagsAsJson configurable in ConflictDetectorService and QueueService

**Files:**
- Modify: `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/ConflictDetectorService.kt`
- Modify: `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/QueueService.kt`

- [ ] **Step 1: Update ConflictDetectorService**

Find `"dockerTagsAsJson"` usage and replace with settings read, following the same pattern as TagBuilderService.

- [ ] **Step 2: Update QueueService**

Find `"dockerTagsAsJson"` usage (around line 269) and replace similarly.

- [ ] **Step 3: Run tests**

Run: `./gradlew :automation:test`
Expected: All pass

- [ ] **Step 4: Commit**

```bash
git add automation/src/main/kotlin/com/workflow/orchestrator/automation/service/ConflictDetectorService.kt \
       automation/src/main/kotlin/com/workflow/orchestrator/automation/service/QueueService.kt
git commit -m "feat(automation): make Bamboo build variable name configurable across all services"
```

---

### Verification: Chunk 4

Run: `./gradlew :sonar:test && ./gradlew :bamboo:test && ./gradlew :automation:test`
Expected: All tests pass

---

## Chunk 5: Handover Configurability (PR, TimeTracking, Cody)

### Task 18: Make PR title format and reviewers configurable

**Files:**
- Modify: `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/PrService.kt`
- Modify: `handover/src/main/kotlin/com/workflow/orchestrator/handover/api/dto/BitbucketDtos.kt`
- Create: `handover/src/test/kotlin/com/workflow/orchestrator/handover/service/PrServiceTemplateTest.kt`

- [ ] **Step 1: Write failing test for PR title template**

```kotlin
package com.workflow.orchestrator.handover.service

import org.junit.Assert.*
import org.junit.Test

class PrServiceTemplateTest {

    @Test
    fun `default format renders correctly`() {
        val title = PrTitleRenderer.render("{ticketId}: {summary}", "PROJ-123", "Fix login bug", "feature/PROJ-123", 120)
        assertEquals("PROJ-123: Fix login bug", title)
    }

    @Test
    fun `custom format with brackets`() {
        val title = PrTitleRenderer.render("[{ticketId}] {summary}", "PROJ-123", "Fix login bug", "feature/PROJ-123", 120)
        assertEquals("[PROJ-123] Fix login bug", title)
    }

    @Test
    fun `truncates to max length`() {
        val longSummary = "A".repeat(200)
        val title = PrTitleRenderer.render("{ticketId}: {summary}", "PROJ-123", longSummary, "branch", 50)
        assertTrue(title.length <= 50)
    }

    @Test
    fun `branch variable substitution`() {
        val title = PrTitleRenderer.render("{ticketId} ({branch})", "PROJ-123", "Fix", "feature/PROJ-123-fix", 120)
        assertEquals("PROJ-123 (feature/PROJ-123-fix)", title)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :handover:test --tests "*.PrServiceTemplateTest"`
Expected: FAIL

- [ ] **Step 3: Add PrTitleRenderer to PrService.kt**

Add a companion object or top-level object in `PrService.kt`:

```kotlin
object PrTitleRenderer {
    fun render(
        format: String,
        ticketId: String,
        summary: String,
        branch: String,
        maxLength: Int
    ): String {
        val rendered = format
            .replace("{ticketId}", ticketId)
            .replace("{branch}", branch)
            .replace("{summary}", summary)

        return if (rendered.length > maxLength) {
            // Truncate summary to fit within maxLength
            val withoutSummary = format
                .replace("{ticketId}", ticketId)
                .replace("{branch}", branch)
                .replace("{summary}", "")
            val availableForSummary = maxLength - withoutSummary.length
            if (availableForSummary > 3) {
                format
                    .replace("{ticketId}", ticketId)
                    .replace("{branch}", branch)
                    .replace("{summary}", summary.take(availableForSummary - 3) + "...")
            } else {
                rendered.take(maxLength)
            }
        } else {
            rendered
        }
    }
}
```

- [ ] **Step 4: Update PrService to use settings for title format**

Update the method that builds the PR title to read `prTitleFormat` and `maxPrTitleLength` from `PluginSettings`.

- [ ] **Step 5: Add BitbucketReviewer DTOs**

In `BitbucketDtos.kt`, add:

```kotlin
@Serializable
data class BitbucketReviewer(
    val user: BitbucketReviewerUser
)

@Serializable
data class BitbucketReviewerUser(
    val name: String
)
```

And add `val reviewers: List<BitbucketReviewer>? = null` to `BitbucketPrRequest`.

- [ ] **Step 6: Update PrService to include reviewers from settings**

Read `prDefaultReviewers` from settings, split by comma, build reviewer list:

```kotlin
val reviewerNames = settings.state.prDefaultReviewers
    ?.split(",")
    ?.map { it.trim() }
    ?.filter { it.isNotBlank() }
    ?: emptyList()

val reviewers = reviewerNames.map { BitbucketReviewer(BitbucketReviewerUser(it)) }
    .takeIf { it.isNotEmpty() }
```

- [ ] **Step 7: Run tests**

Run: `./gradlew :handover:test`
Expected: All pass including new PrServiceTemplateTest

- [ ] **Step 8: Commit**

```bash
git add handover/src/main/kotlin/com/workflow/orchestrator/handover/service/PrService.kt \
       handover/src/main/kotlin/com/workflow/orchestrator/handover/api/dto/BitbucketDtos.kt \
       handover/src/test/kotlin/com/workflow/orchestrator/handover/service/PrServiceTemplateTest.kt
git commit -m "feat(handover): configurable PR title format, max length, and default reviewers"
```

---

### Task 19: Make TimeTrackingService read from settings

**Files:**
- Modify: `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/TimeTrackingService.kt`

- [ ] **Step 1: Replace companion object constant with settings read**

The service currently has `const val MAX_HOURS = 7.0` in the companion object. Since `TimeTrackingService` is a project-level service with a `project` field, it can access `PluginSettings`:

```kotlin
// Replace companion MAX_HOURS with a method:
fun getMaxHours(): Double {
    val proj = project ?: return 7.0
    return com.workflow.orchestrator.core.settings.PluginSettings.getInstance(proj).state.maxWorklogHours
}
```

Update `validateHours` and `clampHours` to use `getMaxHours()`:

```kotlin
fun validateHours(hours: Double): Boolean {
    return hours > 0.0 && hours <= getMaxHours()
}

fun clampHours(hours: Double): Double {
    return hours.coerceAtMost(getMaxHours())
}
```

Keep the companion `MAX_HOURS` for backward compatibility but mark it `@Deprecated`.

- [ ] **Step 2: Run tests**

Run: `./gradlew :handover:test`
Expected: All pass

- [ ] **Step 3: Commit**

```bash
git add handover/src/main/kotlin/com/workflow/orchestrator/handover/service/TimeTrackingService.kt
git commit -m "feat(handover): make max worklog hours configurable via settings"
```

---

### Task 20: Make max diff lines configurable in PreReviewService

**Files:**
- Modify: `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/PreReviewService.kt`

- [ ] **Step 1: Find and replace the hardcoded 10000 line limit**

Read the file to find the exact location, then replace the hardcoded value with a settings read. The service has a `project` field so it can access `PluginSettings.maxDiffLinesForReview`.

- [ ] **Step 2: Run tests**

Run: `./gradlew :handover:test`
Expected: All pass

- [ ] **Step 3: Commit**

```bash
git add handover/src/main/kotlin/com/workflow/orchestrator/handover/service/PreReviewService.kt
git commit -m "feat(handover): make max diff lines for Cody review configurable"
```

---

### Verification: Chunk 5

Run: `./gradlew :handover:test`
Expected: All tests pass

---

## Chunk 6: Settings UI Pages

### Task 21: Create AdvancedConfigurable settings page

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/settings/AdvancedConfigurable.kt`

- [ ] **Step 1: Implement the settings page**

```kotlin
package com.workflow.orchestrator.core.settings

import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.*

class AdvancedConfigurable(private val project: Project) :
    BoundSearchableConfigurable("Advanced", "workflow.orchestrator.advanced") {

    override fun createPanel(): DialogPanel {
        val settings = PluginSettings.getInstance(project)

        return panel {
            group("Network") {
                row("Connect timeout (seconds):") {
                    intTextField(1..300)
                        .bindIntText(settings.state::httpConnectTimeoutSeconds)
                }
                row("Read timeout (seconds):") {
                    intTextField(1..600)
                        .bindIntText(settings.state::httpReadTimeoutSeconds)
                }
            }

            group("Quality Thresholds") {
                row("High coverage — green (%):") {
                    textField()
                        .bindText(
                            { settings.state.coverageHighThreshold.toString() },
                            { settings.state.coverageHighThreshold = it.toDoubleOrNull() ?: 80.0 }
                        )
                }
                row("Medium coverage — yellow (%):") {
                    textField()
                        .bindText(
                            { settings.state.coverageMediumThreshold.toString() },
                            { settings.state.coverageMediumThreshold = it.toDoubleOrNull() ?: 50.0 }
                        )
                }
                row("SonarQube metrics:") {
                    textField()
                        .bindText(settings.state::sonarMetricKeys)
                        .comment("Comma-separated metric keys for API queries")
                }
            }

            group("Time Tracking") {
                row("Max hours per worklog:") {
                    textField()
                        .bindText(
                            { settings.state.maxWorklogHours.toString() },
                            { settings.state.maxWorklogHours = it.toDoubleOrNull() ?: 7.0 }
                        )
                }
                row("Time increment (hours):") {
                    textField()
                        .bindText(
                            { settings.state.worklogIncrementHours.toString() },
                            { settings.state.worklogIncrementHours = it.toDoubleOrNull() ?: 0.5 }
                        )
                }
            }

            group("Branching & PRs") {
                row("Max branch name length:") {
                    intTextField(10..200)
                        .bindIntText(settings.state::branchMaxSummaryLength)
                }
                row("PR title format:") {
                    textField()
                        .bindText(settings.state::prTitleFormat)
                        .comment("Variables: {ticketId}, {summary}, {branch}")
                }
                row("Max PR title length:") {
                    intTextField(20..300)
                        .bindIntText(settings.state::maxPrTitleLength)
                }
                row("Default reviewers:") {
                    textField()
                        .bindText(settings.state::prDefaultReviewers)
                        .comment("Comma-separated Bitbucket usernames")
                }
            }

            group("Cody AI") {
                row("Max diff lines for review:") {
                    intTextField(100..100000)
                        .bindIntText(settings.state::maxDiffLinesForReview)
                }
            }

            group("Automation") {
                row("Tag history entries:") {
                    intTextField(1..50)
                        .bindIntText(settings.state::tagHistoryMaxEntries)
                }
                row("Build variable name:") {
                    textField()
                        .bindText(settings.state::bambooBuildVariableName)
                        .comment("Bamboo build variable containing Docker tag JSON")
                }
            }
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :core:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/settings/AdvancedConfigurable.kt
git commit -m "feat(core): add Advanced settings page with all configurable values"
```

---

### Task 22: Create WorkflowMappingConfigurable settings page

**Files:**
- Create: `jira/src/main/kotlin/com/workflow/orchestrator/jira/settings/WorkflowMappingConfigurable.kt`

- [ ] **Step 1: Implement the settings page**

```kotlin
package com.workflow.orchestrator.jira.settings

import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.*
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.workflow.WorkflowIntent
import com.workflow.orchestrator.jira.workflow.TransitionMapping
import com.workflow.orchestrator.jira.workflow.TransitionMappingStore

class WorkflowMappingConfigurable(private val project: Project) :
    BoundSearchableConfigurable("Workflow Mapping", "workflow.orchestrator.workflow") {

    private val intentFields = mutableMapOf<WorkflowIntent, String>()

    override fun createPanel(): DialogPanel {
        val settings = PluginSettings.getInstance(project)
        val store = TransitionMappingStore()
        store.loadFromJson(settings.state.workflowMappings)

        // Pre-populate fields from stored mappings
        for (intent in WorkflowIntent.entries) {
            val mapping = store.getMapping(intent.name, "")  // Global mapping
            intentFields[intent] = mapping?.transitionName ?: ""
        }

        return panel {
            group("Board Type") {
                row("Board type:") {
                    comboBox(listOf("scrum", "kanban", ""))
                        .bindItem(
                            { settings.state.jiraBoardType ?: "scrum" },
                            { settings.state.jiraBoardType = it ?: "scrum" }
                        )
                        .comment("Filter Jira boards by type. Empty = show all.")
                }
            }

            group("Intent Mappings") {
                row {
                    comment("Map plugin actions to your Jira workflow transitions. Leave blank to auto-detect.")
                }
                for (intent in WorkflowIntent.entries) {
                    row("${intent.displayName}:") {
                        textField()
                            .bindText(
                                { intentFields[intent] ?: "" },
                                { intentFields[intent] = it }
                            )
                            .comment("Auto: ${intent.defaultNames.firstOrNull() ?: "not mapped"}")
                    }
                }
            }

            group("Plugin Guards") {
                row {
                    comment("Block transitions until conditions are met:")
                }
                row {
                    checkBox("Build must pass before Submit for Review")
                        .bindSelected(settings.state::guardBuildPassedBeforeReview)
                }
                row {
                    checkBox("Copyright headers checked before Close")
                        .bindSelected(settings.state::guardCopyrightBeforeClose)
                }
                row {
                    checkBox("Coverage gate must pass before Submit for Review")
                        .bindSelected(settings.state::guardCoverageBeforeReview)
                }
                row {
                    checkBox("All automation suites passed before Close")
                        .bindSelected(settings.state::guardAutomationBeforeClose)
                }
            }
        }
    }

    override fun apply() {
        super.apply()
        // Save intent mappings — load existing learned mappings first to avoid wiping them
        val settings = PluginSettings.getInstance(project)
        val store = TransitionMappingStore()
        store.loadFromJson(settings.state.workflowMappings)
        // Remove old explicit global mappings, then add current ones
        for (intent in WorkflowIntent.entries) {
            store.clearExplicitGlobalMapping(intent.name)
        }
        for ((intent, transitionName) in intentFields) {
            if (transitionName.isNotBlank()) {
                store.saveMapping(
                    TransitionMapping(intent.name, transitionName, "", null, "explicit")
                )
            }
        }
        settings.state.workflowMappings = store.toJson()
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :jira:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add jira/src/main/kotlin/com/workflow/orchestrator/jira/settings/WorkflowMappingConfigurable.kt
git commit -m "feat(jira): add Workflow Mapping settings page with intent mappings and guards"
```

---

### Task 23: Register settings pages in plugin.xml and WorkflowSettingsConfigurable

**Files:**
- Modify: `src/main/resources/META-INF/plugin.xml`
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/settings/WorkflowSettingsConfigurable.kt`

- [ ] **Step 1: Add configurables to plugin.xml**

In `plugin.xml`, after the existing `projectConfigurable` for `workflow.orchestrator` (around line 37), add:

```xml
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

- [ ] **Step 2: Verify plugin structure**

Run: `./gradlew verifyPlugin`
Expected: No errors

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/META-INF/plugin.xml
git commit -m "feat(plugin): register Workflow Mapping and Advanced settings pages"
```

---

### Verification: Chunk 6

Run: `./gradlew verifyPlugin`
Expected: Plugin verification passes — all new configurables resolve correctly.

---

## Chunk 7: Final Verification

### Task 24: Run all module tests sequentially

- [ ] **Step 1: Run all tests**

```bash
./gradlew :core:test && ./gradlew :jira:test && ./gradlew :sonar:test && ./gradlew :handover:test && ./gradlew :automation:test && ./gradlew :bamboo:test
```

Expected: All tests pass across all modules.

- [ ] **Step 2: Verify plugin builds**

```bash
./gradlew verifyPlugin
./gradlew buildPlugin
```

Expected: Both pass, installable ZIP produced.

- [ ] **Step 3: Manual runIde verification**

```bash
./gradlew runIde
```

Check:
1. Settings > Tools > Workflow Orchestrator shows sub-pages: Connections, Health Check, Workflow Mapping, Advanced
2. Workflow Mapping page shows 5 intent fields, board type dropdown, 4 guard checkboxes
3. Advanced page shows all sections: Network, Quality Thresholds, Time Tracking, Branching & PRs, Cody AI, Automation
4. All fields have correct defaults
5. Save settings, restart IDE, verify values persist

- [ ] **Step 4: Final commit if any manual fixes were needed**

```bash
git add -A
git commit -m "fix: address issues found during manual verification"
```

---

## Summary

| Chunk | Tasks | Focus |
|---|---|---|
| 1 | 1-4 | Core foundation: 20 settings fields, WorkflowIntent, TransitionGuard |
| 2 | 5-10 | Jira workflow engine: DTOs, MappingStore, IntentResolver, DisambiguationHelper, TransitionExecutor |
| 3 | 11-13 | Wire consumers: BranchingService, HandoverPanel, BranchNameValidator |
| 4 | 14-17 | Sonar + Bamboo: CoverageThresholds, metric keys, bug fixes |
| 5 | 18-20 | Handover: PR title/reviewers, time tracking, diff limits |
| 6 | 21-23 | Settings UI: AdvancedConfigurable, WorkflowMappingConfigurable, plugin.xml |
| 7 | 24 | Final verification |

**Total: 27 tasks (24 + 3 fix tasks), ~60 steps, 10 new files, 25 modified files**
