# Phase 1B: Sprint & Branching — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the `:jira` module with Sprint Dashboard, branch creation via GitBrancher, commit auto-prefix, status bar widget, and branch-based ticket detection — enabling daily Jira workflow entirely from the IDE.

**Architecture:** Vertical slice — `:jira` module depends only on `:core`. Jira REST v2 + Agile API via OkHttp, GitBrancher for branch creation, VcsCheckinHandlerFactory for commit prefix, BranchChangeListener for ticket detection. All API calls are `suspend fun` on `Dispatchers.IO`.

**Tech Stack:** Kotlin 2.1.10, IntelliJ Platform 2025.1+, OkHttp 4.12.0, kotlinx-serialization 1.7.3, Git4Idea (bundled), JUnit 5, MockK, MockWebServer

**Spec:** `docs/superpowers/specs/2026-03-11-workflow-orchestrator-plugin-design.md`
**Depends on:** Phase 1A (Foundation) must be complete before starting.

> **Important — Existing Scaffolding:** The project has Gradle scaffolding from a prior session. The version catalog (`gradle/libs.versions.toml`) uses aliases like `kotlinSerialization`, `junit5-api`, `kotlinx.coroutines.core` (not the Phase 1A plan's aliases). The root `build.gradle.kts` uses `intellijIdea()` (not `intellijIdeaCommunity()`). The plugin.xml is at `src/main/resources/META-INF/plugin.xml` (root level). This plan uses the **actual** project aliases. Ensure Phase 1A code is implemented before starting Phase 1B.

---

## File Map

### Root project modifications
| File | Purpose |
|---|---|
| `settings.gradle.kts` | Add `:jira` module |
| `build.gradle.kts` | Add `:jira` composed module + Git4Idea dependency |

### `:core` module modifications
| File | Purpose |
|---|---|
| `core/src/main/resources/META-INF/plugin.xml` | Add Git4Idea dep, :jira services/extensions |
| `core/src/main/resources/messages/WorkflowBundle.properties` | Add Sprint/branching i18n strings |
| `core/src/main/kotlin/.../core/settings/PluginSettings.kt` | Add activeTicketId, jiraBoardId fields |
| `core/src/main/kotlin/.../core/toolwindow/WorkflowTabProvider.kt` | Extension point interface |
| `core/src/main/kotlin/.../core/toolwindow/WorkflowToolWindowFactory.kt` | Use tab providers |

### `:jira` module — production code
| File | Purpose |
|---|---|
| `jira/build.gradle.kts` | Submodule build config |
| `jira/src/main/kotlin/.../jira/api/dto/JiraDtos.kt` | All Jira DTOs (serializable) |
| `jira/src/main/kotlin/.../jira/api/JiraApiClient.kt` | HTTP client for Jira REST + Agile API |
| `jira/src/main/kotlin/.../jira/service/SprintService.kt` | Sprint ticket fetching & caching |
| `jira/src/main/kotlin/.../jira/service/ActiveTicketService.kt` | Active ticket state via StateFlow |
| `jira/src/main/kotlin/.../jira/service/BranchingService.kt` | GitBrancher + Jira transition |
| `jira/src/main/kotlin/.../jira/service/BranchNameValidator.kt` | Branch name pattern + validation |
| `jira/src/main/kotlin/.../jira/service/CommitPrefixService.kt` | Commit message prefix logic |
| `jira/src/main/kotlin/.../jira/ui/SprintTabProvider.kt` | WorkflowTabProvider for Sprint tab |
| `jira/src/main/kotlin/.../jira/ui/SprintDashboardPanel.kt` | Master-detail Sprint tab |
| `jira/src/main/kotlin/.../jira/ui/TicketListCellRenderer.kt` | ColoredListCellRenderer for tickets |
| `jira/src/main/kotlin/.../jira/ui/TicketDetailPanel.kt` | Detail panel |
| `jira/src/main/kotlin/.../jira/ui/TicketStatusBarWidgetFactory.kt` | Status bar widget factory |
| `jira/src/main/kotlin/.../jira/ui/TicketStatusBarWidget.kt` | Text-with-popup status bar widget |
| `jira/src/main/kotlin/.../jira/listeners/CommitMessagePrefixHandlerFactory.kt` | VcsCheckinHandlerFactory |
| `jira/src/main/kotlin/.../jira/listeners/BranchChangeTicketDetector.kt` | BranchChangeListener |

> **Note:** `...` = `com/workflow/orchestrator` throughout this plan.

### `:jira` module — test code
| File | Purpose |
|---|---|
| `jira/src/test/resources/fixtures/jira-boards.json` | Boards API fixture |
| `jira/src/test/resources/fixtures/jira-sprints.json` | Sprints API fixture |
| `jira/src/test/resources/fixtures/jira-sprint-issues.json` | Sprint issues fixture |
| `jira/src/test/resources/fixtures/jira-issue-detail.json` | Single issue with links |
| `jira/src/test/resources/fixtures/jira-transitions.json` | Transitions fixture |
| `jira/src/test/kotlin/.../jira/api/dto/JiraDtoDeserializationTest.kt` | DTO deserialization tests |
| `jira/src/test/kotlin/.../jira/api/JiraApiClientTest.kt` | MockWebServer API tests |
| `jira/src/test/kotlin/.../jira/service/SprintServiceTest.kt` | Sprint logic tests |
| `jira/src/test/kotlin/.../jira/service/ActiveTicketServiceTest.kt` | State tracking tests |
| `jira/src/test/kotlin/.../jira/service/BranchNameValidatorTest.kt` | Branch naming tests |
| `jira/src/test/kotlin/.../jira/service/CommitPrefixServiceTest.kt` | Prefix logic tests |

---

## Chunk 1: Module Scaffolding

### Task 1: Add :jira Gradle module and update root build

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `build.gradle.kts`
- Create: `jira/build.gradle.kts`

- [ ] **Step 1: Create jira directory structure**

Run:
```bash
mkdir -p jira/src/main/kotlin/com/workflow/orchestrator/jira/{api/dto,service,ui,listeners}
mkdir -p jira/src/test/kotlin/com/workflow/orchestrator/jira/{api/dto,service}
mkdir -p jira/src/test/resources/fixtures
```

- [ ] **Step 2: Add :jira to settings.gradle.kts**

In `settings.gradle.kts`, change:
```kotlin
include(":core")
```
to:
```kotlin
include(":core")
include(":jira")
```

- [ ] **Step 3: Create jira/build.gradle.kts**

```kotlin
plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.intelliJPlatformModule)
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
        instrumentationTools()
        bundledPlugin("Git4Idea")
    }

    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.test {
    useJUnitPlatform()
}
```

- [ ] **Step 4: Add :jira composed module to root build.gradle.kts**

In `build.gradle.kts`, update the dependencies block. Change:
```kotlin
    // Compose :core into the plugin JAR
    intellijPlatformPluginComposedModule(implementation(project(":core")))
```
to:
```kotlin
    // Compose modules into the plugin JAR
    intellijPlatformPluginComposedModule(implementation(project(":core")))
    intellijPlatformPluginComposedModule(implementation(project(":jira")))
```

Also add Git4Idea to the root `intellijPlatform` block:
```kotlin
    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion"))
        pluginVerifier()
        zipSigner()
        instrumentationTools()
        bundledPlugin("Git4Idea")
    }
```

- [ ] **Step 5: Verify Gradle sync**

Run:
```bash
./gradlew :jira:classes
```
Expected: BUILD SUCCESSFUL (no source files yet).

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts build.gradle.kts jira/
git commit -m "chore: add :jira Gradle submodule with Git4Idea dependency"
```

---

### Task 2: WorkflowTabProvider extension point + update tool window

**Files:**
- Create: `core/src/main/kotlin/.../core/toolwindow/WorkflowTabProvider.kt`
- Modify: `core/src/main/kotlin/.../core/toolwindow/WorkflowToolWindowFactory.kt`
- Modify: `core/src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Create WorkflowTabProvider interface**

Create `core/src/main/kotlin/com/workflow/orchestrator/core/toolwindow/WorkflowTabProvider.kt`:

```kotlin
package com.workflow.orchestrator.core.toolwindow

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import javax.swing.JComponent

interface WorkflowTabProvider {
    val tabId: String
    val tabTitle: String
    val order: Int
    fun createPanel(project: Project): JComponent

    companion object {
        val EP_NAME = ExtensionPointName.create<WorkflowTabProvider>(
            "com.workflow.orchestrator.tabProvider"
        )
    }
}
```

- [ ] **Step 2: Register extension point in plugin.xml**

In `core/src/main/resources/META-INF/plugin.xml`, add inside `<idea-plugin>` (before `<extensions>`):

```xml
    <extensionPoints>
        <extensionPoint
            qualifiedName="com.workflow.orchestrator.tabProvider"
            interface="com.workflow.orchestrator.core.toolwindow.WorkflowTabProvider"
            dynamic="true"/>
    </extensionPoints>
```

- [ ] **Step 3: Update WorkflowToolWindowFactory to use tab providers**

Replace the entire `WorkflowToolWindowFactory` with:

```kotlin
package com.workflow.orchestrator.core.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class WorkflowToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentManager = toolWindow.contentManager
        val providers = WorkflowTabProvider.EP_NAME.extensionList
            .sortedBy { it.order }
            .associateBy { it.tabTitle }

        val defaultTabs = listOf(
            DefaultTab("Sprint", 0, "No tickets assigned.\nConnect to Jira in Settings to get started."),
            DefaultTab("Build", 1, "No builds found.\nPush your changes to trigger a CI build."),
            DefaultTab("Quality", 2, "No quality data available.\nConnect to SonarQube in Settings."),
            DefaultTab("Automation", 3, "Automation suite not configured.\nSet up Bamboo in Settings."),
            DefaultTab("Handover", 4, "No active task to hand over.\nStart work on a ticket first.")
        )

        defaultTabs.forEach { tab ->
            val provider = providers[tab.title]
            val panel = provider?.createPanel(project)
                ?: EmptyStatePanel(project, tab.emptyMessage)
            val content = ContentFactory.getInstance().createContent(panel, tab.title, false)
            content.isCloseable = false
            contentManager.addContent(content)
        }
    }

    private data class DefaultTab(val title: String, val order: Int, val emptyMessage: String)
}
```

- [ ] **Step 4: Verify compilation**

Run:
```bash
./gradlew :core:classes
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/toolwindow/WorkflowTabProvider.kt
git add core/src/main/kotlin/com/workflow/orchestrator/core/toolwindow/WorkflowToolWindowFactory.kt
git add core/src/main/resources/META-INF/plugin.xml
git commit -m "feat(core): add WorkflowTabProvider extension point for modular tab content"
```

---

## Chunk 2: Jira DTOs & API Client

### Task 3: Jira DTOs with kotlinx.serialization

**Files:**
- Create: `jira/src/main/kotlin/.../jira/api/dto/JiraDtos.kt`

- [ ] **Step 1: Write all Jira DTOs in a single file**

Create `jira/src/main/kotlin/com/workflow/orchestrator/jira/api/dto/JiraDtos.kt`:

```kotlin
package com.workflow.orchestrator.jira.api.dto

import kotlinx.serialization.Serializable

// --- Issue DTOs ---

@Serializable
data class JiraIssue(
    val id: String,
    val key: String,
    val self: String = "",
    val fields: JiraIssueFields
)

@Serializable
data class JiraIssueFields(
    val summary: String,
    val status: JiraStatus,
    val issuetype: JiraIssueType? = null,
    val priority: JiraPriority? = null,
    val assignee: JiraUser? = null,
    val reporter: JiraUser? = null,
    val description: String? = null,
    val created: String? = null,
    val updated: String? = null,
    val issuelinks: List<JiraIssueLink> = emptyList(),
    val sprint: JiraSprintRef? = null
)

@Serializable
data class JiraStatus(
    val id: String? = null,
    val name: String,
    val statusCategory: JiraStatusCategory? = null
)

@Serializable
data class JiraStatusCategory(
    val id: Int? = null,
    val key: String,
    val name: String
)

@Serializable
data class JiraIssueType(
    val id: String? = null,
    val name: String,
    val subtask: Boolean = false
)

@Serializable
data class JiraPriority(
    val id: String? = null,
    val name: String
)

@Serializable
data class JiraUser(
    val displayName: String,
    val emailAddress: String? = null,
    val name: String? = null
)

// --- Issue Links ---

@Serializable
data class JiraIssueLink(
    val id: String? = null,
    val type: JiraIssueLinkType,
    val inwardIssue: JiraLinkedIssue? = null,
    val outwardIssue: JiraLinkedIssue? = null
)

@Serializable
data class JiraIssueLinkType(
    val id: String? = null,
    val name: String,
    val inward: String,
    val outward: String
)

@Serializable
data class JiraLinkedIssue(
    val key: String,
    val fields: JiraLinkedIssueFields
)

@Serializable
data class JiraLinkedIssueFields(
    val summary: String,
    val status: JiraStatus
)

@Serializable
data class JiraSprintRef(
    val id: Int,
    val name: String,
    val state: String
)

// --- Board & Sprint DTOs ---

@Serializable
data class JiraBoard(
    val id: Int,
    val name: String,
    val type: String,
    val location: JiraBoardLocation? = null
)

@Serializable
data class JiraBoardLocation(
    val projectId: Int? = null,
    val projectName: String? = null,
    val projectKey: String? = null
)

@Serializable
data class JiraSprint(
    val id: Int,
    val name: String,
    val state: String,
    val startDate: String? = null,
    val endDate: String? = null,
    val originBoardId: Int? = null
)

// --- Transition DTOs ---

@Serializable
data class JiraTransition(
    val id: String,
    val name: String,
    val to: JiraStatus
)

// --- API Response Wrappers ---

@Serializable
data class JiraBoardSearchResult(
    val maxResults: Int = 50,
    val startAt: Int = 0,
    val total: Int = 0,
    val values: List<JiraBoard> = emptyList()
)

@Serializable
data class JiraSprintSearchResult(
    val maxResults: Int = 50,
    val startAt: Int = 0,
    val values: List<JiraSprint> = emptyList()
)

@Serializable
data class JiraIssueSearchResult(
    val maxResults: Int = 50,
    val startAt: Int = 0,
    val total: Int = 0,
    val issues: List<JiraIssue> = emptyList()
)

@Serializable
data class JiraTransitionList(
    val transitions: List<JiraTransition> = emptyList()
)
```

- [ ] **Step 2: Verify compilation**

Run:
```bash
./gradlew :jira:classes
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add jira/src/main/kotlin/com/workflow/orchestrator/jira/api/dto/JiraDtos.kt
git commit -m "feat(jira): add Jira REST API DTOs with kotlinx.serialization"
```

---

### Task 4: Test fixtures + DTO deserialization tests

**Files:**
- Create: `jira/src/test/resources/fixtures/jira-boards.json`
- Create: `jira/src/test/resources/fixtures/jira-sprints.json`
- Create: `jira/src/test/resources/fixtures/jira-sprint-issues.json`
- Create: `jira/src/test/resources/fixtures/jira-issue-detail.json`
- Create: `jira/src/test/resources/fixtures/jira-transitions.json`
- Create: `jira/src/test/kotlin/.../jira/api/dto/JiraDtoDeserializationTest.kt`

- [ ] **Step 1: Create test fixtures**

Create `jira/src/test/resources/fixtures/jira-boards.json`:
```json
{
  "maxResults": 50,
  "startAt": 0,
  "total": 1,
  "values": [
    {
      "id": 1,
      "name": "My Scrum Board",
      "type": "scrum",
      "location": {
        "projectId": 10001,
        "projectName": "My Project",
        "projectKey": "PROJ"
      }
    }
  ]
}
```

Create `jira/src/test/resources/fixtures/jira-sprints.json`:
```json
{
  "maxResults": 50,
  "startAt": 0,
  "values": [
    {
      "id": 42,
      "name": "Sprint 14",
      "state": "active",
      "startDate": "2026-03-01T10:00:00.000+0000",
      "endDate": "2026-03-15T10:00:00.000+0000",
      "originBoardId": 1
    }
  ]
}
```

Create `jira/src/test/resources/fixtures/jira-sprint-issues.json`:
```json
{
  "maxResults": 50,
  "startAt": 0,
  "total": 2,
  "issues": [
    {
      "id": "10001",
      "key": "PROJ-123",
      "self": "https://jira.example.com/rest/api/2/issue/10001",
      "fields": {
        "summary": "Fix login page redirect",
        "status": {
          "id": "3",
          "name": "In Progress",
          "statusCategory": { "id": 4, "key": "indeterminate", "name": "In Progress" }
        },
        "issuetype": { "id": "10001", "name": "Story", "subtask": false },
        "priority": { "id": "2", "name": "High" },
        "assignee": { "displayName": "John Doe", "emailAddress": "john@example.com", "name": "jdoe" },
        "issuelinks": [
          {
            "id": "12345",
            "type": { "id": "10000", "name": "Blocks", "inward": "is blocked by", "outward": "blocks" },
            "inwardIssue": {
              "key": "TEAM-456",
              "fields": {
                "summary": "Backend API update",
                "status": { "name": "In Progress", "statusCategory": { "id": 4, "key": "indeterminate", "name": "In Progress" } }
              }
            }
          }
        ]
      }
    },
    {
      "id": "10002",
      "key": "PROJ-124",
      "self": "https://jira.example.com/rest/api/2/issue/10002",
      "fields": {
        "summary": "Update API response format",
        "status": {
          "id": "1",
          "name": "To Do",
          "statusCategory": { "id": 2, "key": "new", "name": "To Do" }
        },
        "issuetype": { "id": "10002", "name": "Task", "subtask": false },
        "priority": { "id": "3", "name": "Medium" },
        "assignee": { "displayName": "John Doe", "emailAddress": "john@example.com", "name": "jdoe" },
        "issuelinks": []
      }
    }
  ]
}
```

Create `jira/src/test/resources/fixtures/jira-issue-detail.json`:
```json
{
  "id": "10001",
  "key": "PROJ-123",
  "self": "https://jira.example.com/rest/api/2/issue/10001",
  "fields": {
    "summary": "Fix login page redirect",
    "description": "When users click login, they are not redirected to the dashboard.",
    "status": {
      "id": "3",
      "name": "In Progress",
      "statusCategory": { "id": 4, "key": "indeterminate", "name": "In Progress" }
    },
    "issuetype": { "id": "10001", "name": "Story", "subtask": false },
    "priority": { "id": "2", "name": "High" },
    "assignee": { "displayName": "John Doe", "emailAddress": "john@example.com", "name": "jdoe" },
    "reporter": { "displayName": "Jane Smith", "emailAddress": "jane@example.com", "name": "jsmith" },
    "created": "2026-03-01T10:00:00.000+0000",
    "updated": "2026-03-10T14:30:00.000+0000",
    "issuelinks": [
      {
        "id": "12345",
        "type": { "id": "10000", "name": "Blocks", "inward": "is blocked by", "outward": "blocks" },
        "inwardIssue": {
          "key": "TEAM-456",
          "fields": {
            "summary": "Backend API update",
            "status": { "name": "In Progress", "statusCategory": { "id": 4, "key": "indeterminate", "name": "In Progress" } }
          }
        }
      }
    ],
    "sprint": { "id": 42, "name": "Sprint 14", "state": "active" }
  }
}
```

Create `jira/src/test/resources/fixtures/jira-transitions.json`:
```json
{
  "transitions": [
    {
      "id": "11",
      "name": "To Do",
      "to": { "id": "1", "name": "To Do", "statusCategory": { "id": 2, "key": "new", "name": "To Do" } }
    },
    {
      "id": "21",
      "name": "In Progress",
      "to": { "id": "3", "name": "In Progress", "statusCategory": { "id": 4, "key": "indeterminate", "name": "In Progress" } }
    },
    {
      "id": "31",
      "name": "In Review",
      "to": { "id": "5", "name": "In Review", "statusCategory": { "id": 4, "key": "indeterminate", "name": "In Review" } }
    }
  ]
}
```

- [ ] **Step 2: Write DTO deserialization tests**

Create `jira/src/test/kotlin/com/workflow/orchestrator/jira/api/dto/JiraDtoDeserializationTest.kt`:

```kotlin
package com.workflow.orchestrator.jira.api.dto

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class JiraDtoDeserializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(name: String): String =
        javaClass.classLoader.getResource("fixtures/$name")!!.readText()

    @Test
    fun `deserialize boards response`() {
        val result = json.decodeFromString<JiraBoardSearchResult>(fixture("jira-boards.json"))
        assertEquals(1, result.values.size)
        assertEquals("My Scrum Board", result.values[0].name)
        assertEquals("scrum", result.values[0].type)
        assertEquals("PROJ", result.values[0].location?.projectKey)
    }

    @Test
    fun `deserialize sprints response`() {
        val result = json.decodeFromString<JiraSprintSearchResult>(fixture("jira-sprints.json"))
        assertEquals(1, result.values.size)
        assertEquals("Sprint 14", result.values[0].name)
        assertEquals("active", result.values[0].state)
        assertEquals(42, result.values[0].id)
    }

    @Test
    fun `deserialize sprint issues response`() {
        val result = json.decodeFromString<JiraIssueSearchResult>(fixture("jira-sprint-issues.json"))
        assertEquals(2, result.issues.size)
        assertEquals("PROJ-123", result.issues[0].key)
        assertEquals("Fix login page redirect", result.issues[0].fields.summary)
        assertEquals("In Progress", result.issues[0].fields.status.name)
        assertEquals("High", result.issues[0].fields.priority?.name)
        assertEquals(1, result.issues[0].fields.issuelinks.size)
        assertEquals("TEAM-456", result.issues[0].fields.issuelinks[0].inwardIssue?.key)
    }

    @Test
    fun `deserialize single issue with links`() {
        val issue = json.decodeFromString<JiraIssue>(fixture("jira-issue-detail.json"))
        assertEquals("PROJ-123", issue.key)
        assertEquals("Jane Smith", issue.fields.reporter?.displayName)
        assertNotNull(issue.fields.description)
        assertEquals("Sprint 14", issue.fields.sprint?.name)
    }

    @Test
    fun `deserialize transitions response`() {
        val result = json.decodeFromString<JiraTransitionList>(fixture("jira-transitions.json"))
        assertEquals(3, result.transitions.size)
        assertEquals("In Progress", result.transitions[1].name)
        assertEquals("21", result.transitions[1].id)
    }
}
```

- [ ] **Step 3: Run tests to verify they pass**

Run:
```bash
./gradlew :jira:test --tests "*.dto.JiraDtoDeserializationTest" -v
```
Expected: All 5 tests PASS.

- [ ] **Step 4: Commit**

```bash
git add jira/src/test/resources/fixtures/
git add jira/src/test/kotlin/com/workflow/orchestrator/jira/api/dto/
git commit -m "feat(jira): add Jira API test fixtures and DTO deserialization tests"
```

---

### Task 5: JiraApiClient with MockWebServer tests

**Files:**
- Create: `jira/src/main/kotlin/.../jira/api/JiraApiClient.kt`
- Create: `jira/src/test/kotlin/.../jira/api/JiraApiClientTest.kt`

- [ ] **Step 1: Write the failing test**

Create `jira/src/test/kotlin/com/workflow/orchestrator/jira/api/JiraApiClientTest.kt`:

```kotlin
package com.workflow.orchestrator.jira.api

import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class JiraApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: JiraApiClient

    private fun fixture(name: String): String =
        javaClass.classLoader.getResource("fixtures/$name")!!.readText()

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = JiraApiClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            tokenProvider = { "test-token" }
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getBoards returns parsed boards`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("jira-boards.json")))

        val result = client.getBoards()

        assertTrue(result.isSuccess)
        val boards = (result as ApiResult.Success).data
        assertEquals(1, boards.size)
        assertEquals("My Scrum Board", boards[0].name)

        val recorded = server.takeRequest()
        assertEquals("/rest/agile/1.0/board?type=scrum", recorded.path)
        assertEquals("Bearer test-token", recorded.getHeader("Authorization"))
    }

    @Test
    fun `getActiveSprints returns active sprints for board`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("jira-sprints.json")))

        val result = client.getActiveSprints(boardId = 1)

        assertTrue(result.isSuccess)
        val sprints = (result as ApiResult.Success).data
        assertEquals(1, sprints.size)
        assertEquals("Sprint 14", sprints[0].name)

        val recorded = server.takeRequest()
        assertEquals("/rest/agile/1.0/board/1/sprint?state=active", recorded.path)
    }

    @Test
    fun `getSprintIssues returns assigned issues`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("jira-sprint-issues.json")))

        val result = client.getSprintIssues(sprintId = 42)

        assertTrue(result.isSuccess)
        val issues = (result as ApiResult.Success).data
        assertEquals(2, issues.size)
        assertEquals("PROJ-123", issues[0].key)

        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.startsWith("/rest/agile/1.0/sprint/42/issue"))
        assertTrue(recorded.path!!.contains("assignee"))
    }

    @Test
    fun `getTransitions returns available transitions`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("jira-transitions.json")))

        val result = client.getTransitions("PROJ-123")

        assertTrue(result.isSuccess)
        val transitions = (result as ApiResult.Success).data
        assertEquals(3, transitions.size)
        assertEquals("In Progress", transitions[1].name)

        val recorded = server.takeRequest()
        assertEquals("/rest/api/2/issue/PROJ-123/transitions", recorded.path)
    }

    @Test
    fun `returns AUTH_FAILED on 401`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = client.getBoards()

        assertTrue(result.isError)
        assertEquals(ErrorType.AUTH_FAILED, (result as ApiResult.Error).type)
    }

    @Test
    fun `transitionIssue sends correct POST body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = client.transitionIssue("PROJ-123", "21")

        assertTrue(result.isSuccess)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/rest/api/2/issue/PROJ-123/transitions", recorded.path)
        assertTrue(recorded.body.readUtf8().contains("\"id\":\"21\""))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
./gradlew :jira:test --tests "*.api.JiraApiClientTest" --info
```
Expected: FAIL — class not found.

- [ ] **Step 3: Write JiraApiClient implementation**

Create `jira/src/main/kotlin/com/workflow/orchestrator/jira/api/JiraApiClient.kt`:

```kotlin
package com.workflow.orchestrator.jira.api

import com.workflow.orchestrator.core.http.AuthInterceptor
import com.workflow.orchestrator.core.http.AuthScheme
import com.workflow.orchestrator.core.http.RetryInterceptor
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.jira.api.dto.*
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

class JiraApiClient(
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

    suspend fun getBoards(): ApiResult<List<JiraBoard>> =
        get<JiraBoardSearchResult>("/rest/agile/1.0/board?type=scrum").map { it.values }

    suspend fun getActiveSprints(boardId: Int): ApiResult<List<JiraSprint>> =
        get<JiraSprintSearchResult>("/rest/agile/1.0/board/$boardId/sprint?state=active")
            .map { it.values }

    suspend fun getSprintIssues(sprintId: Int): ApiResult<List<JiraIssue>> {
        val jql = URLEncoder.encode("assignee=currentUser()", "UTF-8")
        return get<JiraIssueSearchResult>("/rest/agile/1.0/sprint/$sprintId/issue?jql=$jql")
            .map { it.issues }
    }

    suspend fun getIssue(key: String): ApiResult<JiraIssue> =
        get("/rest/api/2/issue/$key?expand=issuelinks")

    suspend fun getTransitions(issueKey: String): ApiResult<List<JiraTransition>> =
        get<JiraTransitionList>("/rest/api/2/issue/$issueKey/transitions")
            .map { it.transitions }

    suspend fun transitionIssue(issueKey: String, transitionId: String): ApiResult<Unit> {
        val body = """{"transition":{"id":"$transitionId"}}"""
        return post("/rest/api/2/issue/$issueKey/transitions", body)
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
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Jira token")
                        403 -> ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient Jira permissions")
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "Resource not found")
                        429 -> ApiResult.Error(ErrorType.RATE_LIMITED, "Jira rate limit exceeded")
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Jira returned ${it.code}")
                    }
                }
            } catch (e: IOException) {
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Jira: ${e.message}", e)
            }
        }

    private suspend fun post(path: String, jsonBody: String): ApiResult<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val body = jsonBody.toRequestBody("application/json".toMediaType())
                val request = Request.Builder().url("$baseUrl$path").post(body).build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299, 204 -> ApiResult.Success(Unit)
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Jira token")
                        403 -> ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient Jira permissions")
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Jira returned ${it.code}")
                    }
                }
            } catch (e: IOException) {
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Jira: ${e.message}", e)
            }
        }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run:
```bash
./gradlew :jira:test --tests "*.api.JiraApiClientTest" -v
```
Expected: All 6 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add jira/src/main/kotlin/com/workflow/orchestrator/jira/api/JiraApiClient.kt
git add jira/src/test/kotlin/com/workflow/orchestrator/jira/api/JiraApiClientTest.kt
git commit -m "feat(jira): add JiraApiClient with REST v2 + Agile API support"
```

---

## Chunk 3: Sprint Services

### Task 6: SprintService

**Files:**
- Create: `jira/src/main/kotlin/.../jira/service/SprintService.kt`
- Create: `jira/src/test/kotlin/.../jira/service/SprintServiceTest.kt`

- [ ] **Step 1: Write the failing test**

Create `jira/src/test/kotlin/com/workflow/orchestrator/jira/service/SprintServiceTest.kt`:

```kotlin
package com.workflow.orchestrator.jira.service

import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.jira.api.JiraApiClient
import com.workflow.orchestrator.jira.api.dto.*
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SprintServiceTest {

    private lateinit var apiClient: JiraApiClient
    private lateinit var sprintService: SprintService

    private val testBoard = JiraBoard(id = 1, name = "Board", type = "scrum")
    private val testSprint = JiraSprint(id = 42, name = "Sprint 14", state = "active")
    private val testIssue = JiraIssue(
        id = "10001", key = "PROJ-123",
        fields = JiraIssueFields(
            summary = "Fix login",
            status = JiraStatus(name = "To Do")
        )
    )

    @BeforeEach
    fun setUp() {
        apiClient = mockk()
        sprintService = SprintService(apiClient)
    }

    @Test
    fun `loadSprintIssues returns issues for auto-discovered board and sprint`() = runTest {
        coEvery { apiClient.getBoards() } returns ApiResult.Success(listOf(testBoard))
        coEvery { apiClient.getActiveSprints(1) } returns ApiResult.Success(listOf(testSprint))
        coEvery { apiClient.getSprintIssues(42) } returns ApiResult.Success(listOf(testIssue))

        val result = sprintService.loadSprintIssues()

        assertTrue(result.isSuccess)
        val issues = (result as ApiResult.Success).data
        assertEquals(1, issues.size)
        assertEquals("PROJ-123", issues[0].key)
    }

    @Test
    fun `loadSprintIssues uses configured board ID when available`() = runTest {
        coEvery { apiClient.getActiveSprints(5) } returns ApiResult.Success(listOf(testSprint))
        coEvery { apiClient.getSprintIssues(42) } returns ApiResult.Success(listOf(testIssue))

        val result = sprintService.loadSprintIssues(boardId = 5)

        assertTrue(result.isSuccess)
    }

    @Test
    fun `loadSprintIssues returns error when no boards found`() = runTest {
        coEvery { apiClient.getBoards() } returns ApiResult.Success(emptyList())

        val result = sprintService.loadSprintIssues()

        assertTrue(result.isError)
    }

    @Test
    fun `loadSprintIssues returns error when no active sprint`() = runTest {
        coEvery { apiClient.getBoards() } returns ApiResult.Success(listOf(testBoard))
        coEvery { apiClient.getActiveSprints(1) } returns ApiResult.Success(emptyList())

        val result = sprintService.loadSprintIssues()

        assertTrue(result.isError)
    }

    @Test
    fun `getActiveSprint returns current sprint info`() = runTest {
        coEvery { apiClient.getBoards() } returns ApiResult.Success(listOf(testBoard))
        coEvery { apiClient.getActiveSprints(1) } returns ApiResult.Success(listOf(testSprint))
        coEvery { apiClient.getSprintIssues(42) } returns ApiResult.Success(listOf(testIssue))

        sprintService.loadSprintIssues()

        val sprint = sprintService.activeSprint
        assertNotNull(sprint)
        assertEquals("Sprint 14", sprint?.name)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
./gradlew :jira:test --tests "*.service.SprintServiceTest" --info
```
Expected: FAIL — class not found.

- [ ] **Step 3: Write implementation**

Create `jira/src/main/kotlin/com/workflow/orchestrator/jira/service/SprintService.kt`:

```kotlin
package com.workflow.orchestrator.jira.service

import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.jira.api.JiraApiClient
import com.workflow.orchestrator.jira.api.dto.JiraIssue
import com.workflow.orchestrator.jira.api.dto.JiraSprint

class SprintService(private val apiClient: JiraApiClient) {

    var activeSprint: JiraSprint? = null
        private set

    private var cachedIssues: List<JiraIssue> = emptyList()

    suspend fun loadSprintIssues(boardId: Int? = null): ApiResult<List<JiraIssue>> {
        val resolvedBoardId = boardId ?: discoverBoardId()
            ?: return ApiResult.Error(ErrorType.NOT_FOUND, "No Jira Scrum boards found. Create a board first.")

        val sprintResult = apiClient.getActiveSprints(resolvedBoardId)
        val sprint = when (sprintResult) {
            is ApiResult.Success -> sprintResult.data.firstOrNull()
            is ApiResult.Error -> return sprintResult
        } ?: return ApiResult.Error(ErrorType.NOT_FOUND, "No active sprint found on this board.")

        activeSprint = sprint

        val issuesResult = apiClient.getSprintIssues(sprint.id)
        if (issuesResult is ApiResult.Success) {
            cachedIssues = issuesResult.data
        }
        return issuesResult
    }

    fun getCachedIssues(): List<JiraIssue> = cachedIssues

    private suspend fun discoverBoardId(): Int? {
        val boardsResult = apiClient.getBoards()
        return when (boardsResult) {
            is ApiResult.Success -> boardsResult.data.firstOrNull()?.id
            is ApiResult.Error -> null
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run:
```bash
./gradlew :jira:test --tests "*.service.SprintServiceTest" -v
```
Expected: All 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add jira/src/main/kotlin/com/workflow/orchestrator/jira/service/SprintService.kt
git add jira/src/test/kotlin/com/workflow/orchestrator/jira/service/SprintServiceTest.kt
git commit -m "feat(jira): add SprintService with board auto-discovery"
```

---

### Task 7: ActiveTicketService

**Files:**
- Create: `jira/src/main/kotlin/.../jira/service/ActiveTicketService.kt`
- Create: `jira/src/test/kotlin/.../jira/service/ActiveTicketServiceTest.kt`
- Modify: `core/src/main/kotlin/.../core/settings/PluginSettings.kt`

- [ ] **Step 1: Add activeTicketId to PluginSettings.State**

In `core/src/main/kotlin/com/workflow/orchestrator/core/settings/PluginSettings.kt`, add to `State` class after the existing fields:

```kotlin
        // Active ticket (persisted across restarts)
        var activeTicketId by string("")
        var activeTicketSummary by string("")
        var jiraBoardId by property(0)
```

- [ ] **Step 2: Write the failing test**

Create `jira/src/test/kotlin/com/workflow/orchestrator/jira/service/ActiveTicketServiceTest.kt`:

```kotlin
package com.workflow.orchestrator.jira.service

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ActiveTicketServiceTest {

    @Test
    fun `initially has no active ticket`() {
        val service = ActiveTicketService()
        assertNull(service.activeTicketId)
    }

    @Test
    fun `setActiveTicket updates state`() {
        val service = ActiveTicketService()
        service.setActiveTicket("PROJ-123", "Fix login")
        assertEquals("PROJ-123", service.activeTicketId)
        assertEquals("Fix login", service.activeTicketSummary)
    }

    @Test
    fun `clearActiveTicket resets state`() {
        val service = ActiveTicketService()
        service.setActiveTicket("PROJ-123", "Fix login")
        service.clearActiveTicket()
        assertNull(service.activeTicketId)
        assertNull(service.activeTicketSummary)
    }

    @Test
    fun `activeTicketFlow emits updates`() = runTest {
        val service = ActiveTicketService()
        service.setActiveTicket("PROJ-456", "Update API")
        val state = service.activeTicketFlow.first()
        assertEquals("PROJ-456", state?.ticketId)
    }

    @Test
    fun `extractTicketIdFromBranch parses standard branch names`() {
        assertEquals("PROJ-123", ActiveTicketService.extractTicketIdFromBranch("feature/PROJ-123-login-fix"))
        assertEquals("PROJ-456", ActiveTicketService.extractTicketIdFromBranch("bugfix/PROJ-456-crash"))
        assertEquals("ABC-1", ActiveTicketService.extractTicketIdFromBranch("ABC-1-quick-fix"))
        assertNull(ActiveTicketService.extractTicketIdFromBranch("main"))
        assertNull(ActiveTicketService.extractTicketIdFromBranch("develop"))
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run:
```bash
./gradlew :jira:test --tests "*.service.ActiveTicketServiceTest" --info
```
Expected: FAIL — class not found.

- [ ] **Step 4: Write implementation**

Create `jira/src/main/kotlin/com/workflow/orchestrator/jira/service/ActiveTicketService.kt`:

```kotlin
package com.workflow.orchestrator.jira.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ActiveTicketState(
    val ticketId: String,
    val summary: String
)

class ActiveTicketService {

    private val _activeTicketFlow = MutableStateFlow<ActiveTicketState?>(null)
    val activeTicketFlow: StateFlow<ActiveTicketState?> = _activeTicketFlow.asStateFlow()

    val activeTicketId: String? get() = _activeTicketFlow.value?.ticketId
    val activeTicketSummary: String? get() = _activeTicketFlow.value?.summary

    fun setActiveTicket(ticketId: String, summary: String) {
        _activeTicketFlow.value = ActiveTicketState(ticketId, summary)
    }

    fun clearActiveTicket() {
        _activeTicketFlow.value = null
    }

    companion object {
        private val TICKET_PATTERN = Regex("([A-Z][A-Z0-9]+-\\d+)")

        fun extractTicketIdFromBranch(branchName: String): String? {
            return TICKET_PATTERN.find(branchName)?.groupValues?.get(1)
        }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run:
```bash
./gradlew :jira:test --tests "*.service.ActiveTicketServiceTest" -v
```
Expected: All 5 tests PASS.

- [ ] **Step 6: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/settings/PluginSettings.kt
git add jira/src/main/kotlin/com/workflow/orchestrator/jira/service/ActiveTicketService.kt
git add jira/src/test/kotlin/com/workflow/orchestrator/jira/service/ActiveTicketServiceTest.kt
git commit -m "feat(jira): add ActiveTicketService with StateFlow and branch name parsing"
```

---

### Task 8: BranchNameValidator + CommitPrefixService (TDD)

**Files:**
- Create: `jira/src/main/kotlin/.../jira/service/BranchNameValidator.kt`
- Create: `jira/src/main/kotlin/.../jira/service/CommitPrefixService.kt`
- Create: `jira/src/test/kotlin/.../jira/service/BranchNameValidatorTest.kt`
- Create: `jira/src/test/kotlin/.../jira/service/CommitPrefixServiceTest.kt`

- [ ] **Step 1: Write BranchNameValidator failing tests**

Create `jira/src/test/kotlin/com/workflow/orchestrator/jira/service/BranchNameValidatorTest.kt`:

```kotlin
package com.workflow.orchestrator.jira.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BranchNameValidatorTest {

    @Test
    fun `generates branch name from default pattern`() {
        val name = BranchNameValidator.generateBranchName(
            pattern = "feature/{ticketId}-{summary}",
            ticketId = "PROJ-123",
            summary = "Fix Login Page Redirect"
        )
        assertEquals("feature/PROJ-123-fix-login-page-redirect", name)
    }

    @Test
    fun `generates branch name from bugfix pattern`() {
        val name = BranchNameValidator.generateBranchName(
            pattern = "bugfix/{ticketId}-{summary}",
            ticketId = "PROJ-456",
            summary = "NPE on Startup"
        )
        assertEquals("bugfix/PROJ-456-npe-on-startup", name)
    }

    @Test
    fun `sanitizes special characters from summary`() {
        val name = BranchNameValidator.generateBranchName(
            pattern = "feature/{ticketId}-{summary}",
            ticketId = "PROJ-789",
            summary = "Add @Transactional & fix N+1 query!!"
        )
        assertEquals("feature/PROJ-789-add-transactional-fix-n-1-query", name)
    }

    @Test
    fun `truncates long summaries to 50 chars`() {
        val name = BranchNameValidator.generateBranchName(
            pattern = "feature/{ticketId}-{summary}",
            ticketId = "PROJ-100",
            summary = "This is a very long summary that should be truncated to avoid extremely long branch names"
        )
        assertTrue(name.length <= 80) // pattern prefix + ticketId + truncated summary
        assertFalse(name.endsWith("-"))
    }

    @Test
    fun `isValidBranchName accepts standard patterns`() {
        assertTrue(BranchNameValidator.isValidBranchName("feature/PROJ-123-login-fix"))
        assertTrue(BranchNameValidator.isValidBranchName("bugfix/PROJ-456-crash"))
        assertFalse(BranchNameValidator.isValidBranchName("feature/no ticket id"))
        assertFalse(BranchNameValidator.isValidBranchName(""))
    }
}
```

- [ ] **Step 2: Write CommitPrefixService failing tests**

Create `jira/src/test/kotlin/com/workflow/orchestrator/jira/service/CommitPrefixServiceTest.kt`:

```kotlin
package com.workflow.orchestrator.jira.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CommitPrefixServiceTest {

    @Test
    fun `adds standard prefix when not present`() {
        val result = CommitPrefixService.addPrefix(
            message = "implemented auth logic",
            ticketId = "PROJ-123",
            useConventionalCommits = false
        )
        assertEquals("PROJ-123: implemented auth logic", result)
    }

    @Test
    fun `does not double-prefix when already present`() {
        val result = CommitPrefixService.addPrefix(
            message = "PROJ-123: implemented auth logic",
            ticketId = "PROJ-123",
            useConventionalCommits = false
        )
        assertEquals("PROJ-123: implemented auth logic", result)
    }

    @Test
    fun `adds conventional commit prefix`() {
        val result = CommitPrefixService.addPrefix(
            message = "implemented auth logic",
            ticketId = "PROJ-123",
            useConventionalCommits = true
        )
        assertEquals("feat(PROJ-123): implemented auth logic", result)
    }

    @Test
    fun `preserves existing conventional commit type`() {
        val result = CommitPrefixService.addPrefix(
            message = "fix: resolve null pointer",
            ticketId = "PROJ-123",
            useConventionalCommits = true
        )
        assertEquals("fix(PROJ-123): resolve null pointer", result)
    }

    @Test
    fun `does not modify message when ticket already in conventional format`() {
        val result = CommitPrefixService.addPrefix(
            message = "feat(PROJ-123): implemented auth logic",
            ticketId = "PROJ-123",
            useConventionalCommits = true
        )
        assertEquals("feat(PROJ-123): implemented auth logic", result)
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run:
```bash
./gradlew :jira:test --tests "*.service.BranchNameValidatorTest" --tests "*.service.CommitPrefixServiceTest" --info
```
Expected: FAIL — classes not found.

- [ ] **Step 4: Write BranchNameValidator**

Create `jira/src/main/kotlin/com/workflow/orchestrator/jira/service/BranchNameValidator.kt`:

```kotlin
package com.workflow.orchestrator.jira.service

object BranchNameValidator {

    private val TICKET_PATTERN = Regex("[A-Z][A-Z0-9]+-\\d+")
    private val INVALID_CHARS = Regex("[^a-z0-9/\\-]")
    private const val MAX_SUMMARY_LENGTH = 50

    fun generateBranchName(pattern: String, ticketId: String, summary: String): String {
        val sanitizedSummary = summary
            .lowercase()
            .replace(INVALID_CHARS, "-")
            .replace(Regex("-+"), "-")
            .trim('-')
            .take(MAX_SUMMARY_LENGTH)
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

- [ ] **Step 5: Write CommitPrefixService**

Create `jira/src/main/kotlin/com/workflow/orchestrator/jira/service/CommitPrefixService.kt`:

```kotlin
package com.workflow.orchestrator.jira.service

object CommitPrefixService {

    private val CONVENTIONAL_COMMIT_PATTERN = Regex("^(\\w+)(\\([^)]+\\))?:\\s*")

    fun addPrefix(message: String, ticketId: String, useConventionalCommits: Boolean): String {
        if (message.contains(ticketId)) return message

        return if (useConventionalCommits) {
            addConventionalPrefix(message, ticketId)
        } else {
            addStandardPrefix(message, ticketId)
        }
    }

    private fun addStandardPrefix(message: String, ticketId: String): String {
        return "$ticketId: $message"
    }

    private fun addConventionalPrefix(message: String, ticketId: String): String {
        val match = CONVENTIONAL_COMMIT_PATTERN.find(message)
        return if (match != null) {
            val type = match.groupValues[1]
            val rest = message.removePrefix(match.value)
            "$type($ticketId): $rest"
        } else {
            "feat($ticketId): $message"
        }
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run:
```bash
./gradlew :jira:test --tests "*.service.BranchNameValidatorTest" --tests "*.service.CommitPrefixServiceTest" -v
```
Expected: All 10 tests PASS.

- [ ] **Step 7: Commit**

```bash
git add jira/src/main/kotlin/com/workflow/orchestrator/jira/service/BranchNameValidator.kt
git add jira/src/main/kotlin/com/workflow/orchestrator/jira/service/CommitPrefixService.kt
git add jira/src/test/kotlin/com/workflow/orchestrator/jira/service/BranchNameValidatorTest.kt
git add jira/src/test/kotlin/com/workflow/orchestrator/jira/service/CommitPrefixServiceTest.kt
git commit -m "feat(jira): add BranchNameValidator and CommitPrefixService with TDD"
```

---

## Chunk 4: Sprint Dashboard UI

### Task 9: Sprint Dashboard panels

**Files:**
- Create: `jira/src/main/kotlin/.../jira/ui/TicketListCellRenderer.kt`
- Create: `jira/src/main/kotlin/.../jira/ui/TicketDetailPanel.kt`
- Create: `jira/src/main/kotlin/.../jira/ui/SprintDashboardPanel.kt`

- [ ] **Step 1: Write TicketListCellRenderer**

Create `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/TicketListCellRenderer.kt`:

```kotlin
package com.workflow.orchestrator.jira.ui

import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.workflow.orchestrator.jira.api.dto.JiraIssue
import javax.swing.JList

class TicketListCellRenderer : ColoredListCellRenderer<JiraIssue>() {

    override fun customizeCellRenderer(
        list: JList<out JiraIssue>,
        value: JiraIssue?,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean
    ) {
        if (value == null) return

        // Ticket key in bold
        append("${value.key}  ", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)

        // Summary in regular
        append(value.fields.summary, SimpleTextAttributes.REGULAR_ATTRIBUTES)

        // Status tag
        val statusColor = statusColor(value.fields.status.statusCategory?.key)
        append("  [${value.fields.status.name}]", SimpleTextAttributes(
            SimpleTextAttributes.STYLE_SMALLER, statusColor
        ))

        // Priority indicator
        value.fields.priority?.let {
            append("  ${priorityIcon(it.name)}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }

        // Dependency indicator
        if (value.fields.issuelinks.isNotEmpty()) {
            val blockers = value.fields.issuelinks.count { it.inwardIssue != null }
            if (blockers > 0) {
                append("  !! $blockers blocker(s)", SimpleTextAttributes(
                    SimpleTextAttributes.STYLE_SMALLER, JBColor.RED
                ))
            }
        }
    }

    private fun statusColor(categoryKey: String?): JBColor = when (categoryKey) {
        "new" -> JBColor.GRAY
        "indeterminate" -> JBColor.BLUE
        "done" -> JBColor(0x1B7F37, 0x3FB950) // green in light/dark
        else -> JBColor.GRAY
    }

    private fun priorityIcon(name: String): String = when (name.lowercase()) {
        "highest", "blocker" -> "!!!"
        "high" -> "!!"
        "medium" -> "!"
        "low" -> "-"
        "lowest" -> "--"
        else -> ""
    }
}
```

- [ ] **Step 2: Write TicketDetailPanel**

Create `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/TicketDetailPanel.kt`:

```kotlin
package com.workflow.orchestrator.jira.ui

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.jira.api.dto.JiraIssue
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JPanel
import javax.swing.SwingConstants

class TicketDetailPanel : JPanel(BorderLayout()) {

    private val titleLabel = JBLabel().apply {
        font = font.deriveFont(font.size2D + 2f)
    }
    private val statusLabel = JBLabel()
    private val priorityLabel = JBLabel()
    private val assigneeLabel = JBLabel()
    private val sprintLabel = JBLabel()
    private val descriptionLabel = JBLabel()
    private val dependenciesPanel = JPanel().apply { layout = GridBagLayout() }
    private val emptyLabel = JBLabel("Select a ticket to view details").apply {
        horizontalAlignment = SwingConstants.CENTER
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
    }

    init {
        showEmpty()
    }

    fun showEmpty() {
        removeAll()
        add(emptyLabel, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    fun showIssue(issue: JiraIssue) {
        removeAll()

        val fields = issue.fields
        titleLabel.text = "<html><b>${issue.key}</b>: ${fields.summary}</html>"
        statusLabel.text = "Status: ${fields.status.name}"
        priorityLabel.text = "Priority: ${fields.priority?.name ?: "None"}"
        assigneeLabel.text = "Assignee: ${fields.assignee?.displayName ?: "Unassigned"}"
        sprintLabel.text = "Sprint: ${fields.sprint?.name ?: "None"}"
        descriptionLabel.text = if (fields.description != null) {
            "<html>${fields.description.take(500)}</html>"
        } else {
            "No description."
        }

        val infoPanel = JPanel(GridBagLayout())
        infoPanel.border = JBUI.Borders.empty(8)
        val gbc = GridBagConstraints().apply {
            gridx = 0; fill = GridBagConstraints.HORIZONTAL; weightx = 1.0
            anchor = GridBagConstraints.NORTHWEST
        }

        var row = 0
        listOf(titleLabel, statusLabel, priorityLabel, assigneeLabel, sprintLabel).forEach { label ->
            gbc.gridy = row++
            gbc.insets = JBUI.insets(2, 0)
            infoPanel.add(label, gbc)
        }

        // Dependencies section
        val blockers = fields.issuelinks.filter { it.inwardIssue != null }
        if (blockers.isNotEmpty()) {
            gbc.gridy = row++
            gbc.insets = JBUI.insets(12, 0, 4, 0)
            infoPanel.add(JBLabel("<html><b>Dependencies:</b></html>"), gbc)

            blockers.forEach { link ->
                val linked = link.inwardIssue!!
                gbc.gridy = row++
                gbc.insets = JBUI.insets(2, 8)
                val linkLabel = JBLabel(
                    "- ${link.type.inward} ${linked.key}: ${linked.fields.summary} [${linked.fields.status.name}]"
                )
                infoPanel.add(linkLabel, gbc)
            }
        }

        // Description
        gbc.gridy = row++
        gbc.insets = JBUI.insets(12, 0, 4, 0)
        infoPanel.add(JBLabel("<html><b>Description:</b></html>"), gbc)
        gbc.gridy = row++
        gbc.weighty = 1.0
        gbc.fill = GridBagConstraints.BOTH
        infoPanel.add(descriptionLabel, gbc)

        add(JBScrollPane(infoPanel), BorderLayout.CENTER)
        revalidate()
        repaint()
    }
}
```

- [ ] **Step 3: Write SprintDashboardPanel**

Create `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/SprintDashboardPanel.kt`:

```kotlin
package com.workflow.orchestrator.jira.ui

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.progress.runBackgroundableTask
import com.intellij.openapi.project.Project
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.jira.api.JiraApiClient
import com.workflow.orchestrator.jira.api.dto.JiraIssue
import com.workflow.orchestrator.jira.service.ActiveTicketService
import com.workflow.orchestrator.jira.service.BranchNameValidator
import com.workflow.orchestrator.jira.service.BranchingService
import com.workflow.orchestrator.jira.service.SprintService
import git4idea.branch.GitBrancher
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.runBlocking
import java.awt.BorderLayout
import javax.swing.*

class SprintDashboardPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val settings = PluginSettings.getInstance(project)
    private val credentialStore = CredentialStore()
    private val apiClient = JiraApiClient(
        baseUrl = settings.state.jiraUrl.trimEnd('/'),
        tokenProvider = { credentialStore.getToken(ServiceType.JIRA) }
    )
    private val sprintService = SprintService(apiClient)
    private val activeTicketService = ActiveTicketService()
    private val branchingService = BranchingService(project, apiClient, activeTicketService)

    private val listModel = DefaultListModel<JiraIssue>()
    private val ticketList = JBList(listModel).apply {
        cellRenderer = TicketListCellRenderer()
        selectionMode = ListSelectionModel.SINGLE_SELECTION
    }
    private val detailPanel = TicketDetailPanel()

    private val splitter = JBSplitter(false, 0.4f).apply {
        setSplitterProportionKey("workflow.sprint.splitter")
        firstComponent = JBScrollPane(ticketList)
        secondComponent = detailPanel
    }

    private val statusLabel = JLabel("Loading...")

    init {
        border = JBUI.Borders.empty()

        // Toolbar
        val toolbar = createToolbar()
        add(toolbar, BorderLayout.NORTH)

        // Splitter (master-detail)
        add(splitter, BorderLayout.CENTER)

        // Status bar at bottom
        val statusPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(2, 8)
            add(statusLabel, BorderLayout.WEST)
        }
        add(statusPanel, BorderLayout.SOUTH)

        // Selection listener
        ticketList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                val selected = ticketList.selectedValue
                if (selected != null) {
                    detailPanel.showIssue(selected)
                } else {
                    detailPanel.showEmpty()
                }
            }
        }

        // Initial load
        refreshIssues()
    }

    private fun createToolbar(): JComponent {
        val group = DefaultActionGroup()

        group.add(object : AnAction("Refresh", "Refresh sprint tickets", null) {
            override fun actionPerformed(e: AnActionEvent) {
                refreshIssues()
            }
        })

        group.add(object : AnAction("Start Work", "Create branch and start working on selected ticket", null) {
            override fun actionPerformed(e: AnActionEvent) {
                startWorkOnSelected()
            }

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = ticketList.selectedValue != null
            }
        })

        val actionToolbar = ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.TOOLBAR, group, true)
        actionToolbar.targetComponent = this
        return actionToolbar.component
    }

    fun refreshIssues() {
        statusLabel.text = "Loading sprint tickets..."
        val boardId = settings.state.jiraBoardId.takeIf { it > 0 }

        runBackgroundableTask("Loading Sprint Tickets", project, false) {
            val result = runBlocking { sprintService.loadSprintIssues(boardId) }
            invokeLater {
                when (result) {
                    is ApiResult.Success -> {
                        listModel.clear()
                        result.data.forEach { listModel.addElement(it) }
                        val sprint = sprintService.activeSprint
                        statusLabel.text = if (sprint != null) {
                            "${sprint.name} — ${result.data.size} ticket(s)"
                        } else {
                            "${result.data.size} ticket(s)"
                        }
                    }
                    is ApiResult.Error -> {
                        statusLabel.text = "Error: ${result.message}"
                    }
                }
            }
        }
    }

    private fun startWorkOnSelected() {
        val issue = ticketList.selectedValue ?: return
        val pattern = settings.state.branchPattern

        runBackgroundableTask("Starting Work on ${issue.key}", project, false) {
            val result = runBlocking {
                branchingService.startWork(issue, pattern)
            }
            invokeLater {
                when (result) {
                    is ApiResult.Success -> {
                        statusLabel.text = "Working on ${issue.key}"
                        refreshIssues()
                    }
                    is ApiResult.Error -> {
                        statusLabel.text = "Failed: ${result.message}"
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 4: Verify compilation**

Run:
```bash
./gradlew :jira:classes
```
Expected: BUILD SUCCESSFUL (BranchingService not yet created — will fail; create stub first if needed).

> **Note:** BranchingService is created in Task 10. If the build fails here, create a minimal stub and return to fill it in Task 10.

- [ ] **Step 5: Commit**

```bash
git add jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/TicketListCellRenderer.kt
git add jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/TicketDetailPanel.kt
git add jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/SprintDashboardPanel.kt
git commit -m "feat(jira): add Sprint Dashboard with master-detail layout and ticket list"
```

---

### Task 10: SprintTabProvider + wire into tool window

**Files:**
- Create: `jira/src/main/kotlin/.../jira/ui/SprintTabProvider.kt`
- Modify: `core/src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Write SprintTabProvider**

Create `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/SprintTabProvider.kt`:

```kotlin
package com.workflow.orchestrator.jira.ui

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.toolwindow.EmptyStatePanel
import com.workflow.orchestrator.core.toolwindow.WorkflowTabProvider
import javax.swing.JComponent

class SprintTabProvider : WorkflowTabProvider {

    override val tabId: String = "sprint"
    override val tabTitle: String = "Sprint"
    override val order: Int = 0

    override fun createPanel(project: Project): JComponent {
        val settings = PluginSettings.getInstance(project)
        return if (settings.state.jiraUrl.isNotBlank()) {
            SprintDashboardPanel(project)
        } else {
            EmptyStatePanel(project, "No tickets assigned.\nConnect to Jira in Settings to get started.")
        }
    }
}
```

- [ ] **Step 2: Register SprintTabProvider in plugin.xml**

In `core/src/main/resources/META-INF/plugin.xml`, add inside `<extensions defaultExtensionNs="com.intellij">`:

```xml
        <!-- Jira Sprint Tab Provider -->
        <com.workflow.orchestrator.tabProvider
            implementation="com.workflow.orchestrator.jira.ui.SprintTabProvider"/>
```

> **Note:** Since this uses a custom extension point (`com.workflow.orchestrator.tabProvider`), it must be registered under the custom namespace. Add a new `<extensions>` block:

Actually, register under the plugin's namespace. Add after the existing `<extensions>` blocks:

```xml
    <extensions defaultExtensionNs="com.workflow.orchestrator">
        <tabProvider implementation="com.workflow.orchestrator.jira.ui.SprintTabProvider"/>
    </extensions>
```

- [ ] **Step 3: Add Git4Idea dependency to plugin.xml**

In `core/src/main/resources/META-INF/plugin.xml`, add after the existing `<depends>` lines:

```xml
    <depends>Git4Idea</depends>
```

- [ ] **Step 4: Verify compilation**

Run:
```bash
./gradlew :jira:classes
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/SprintTabProvider.kt
git add core/src/main/resources/META-INF/plugin.xml
git commit -m "feat(jira): register SprintTabProvider and wire Sprint tab into tool window"
```

---

## Chunk 5: Git & VCS Integration

### Task 11: BranchingService + "Start Work"

**Files:**
- Create: `jira/src/main/kotlin/.../jira/service/BranchingService.kt`

- [ ] **Step 1: Write BranchingService**

Create `jira/src/main/kotlin/com/workflow/orchestrator/jira/service/BranchingService.kt`:

```kotlin
package com.workflow.orchestrator.jira.service

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.jira.api.JiraApiClient
import com.workflow.orchestrator.jira.api.dto.JiraIssue
import git4idea.branch.GitBrancher
import git4idea.repo.GitRepositoryManager

class BranchingService(
    private val project: Project,
    private val apiClient: JiraApiClient,
    private val activeTicketService: ActiveTicketService
) {

    suspend fun startWork(issue: JiraIssue, branchPattern: String): ApiResult<String> {
        // 1. Generate branch name
        val branchName = BranchNameValidator.generateBranchName(
            pattern = branchPattern,
            ticketId = issue.key,
            summary = issue.fields.summary
        )

        // 2. Create git branch
        val repositories = GitRepositoryManager.getInstance(project).repositories
        if (repositories.isEmpty()) {
            return ApiResult.Error(ErrorType.NOT_FOUND, "No Git repository found in this project.")
        }

        try {
            GitBrancher.getInstance(project).checkoutNewBranch(branchName, repositories)
        } catch (e: Exception) {
            return ApiResult.Error(
                ErrorType.SERVER_ERROR,
                "Failed to create branch '$branchName': ${e.message}",
                e
            )
        }

        // 3. Transition ticket to "In Progress"
        val transitionResult = transitionToInProgress(issue.key)
        if (transitionResult is ApiResult.Error) {
            // Branch was created, just warn about transition failure
            // Don't fail the whole operation
        }

        // 4. Set active ticket
        activeTicketService.setActiveTicket(issue.key, issue.fields.summary)

        return ApiResult.Success(branchName)
    }

    private suspend fun transitionToInProgress(issueKey: String): ApiResult<Unit> {
        val transitionsResult = apiClient.getTransitions(issueKey)
        val transitions = when (transitionsResult) {
            is ApiResult.Success -> transitionsResult.data
            is ApiResult.Error -> return transitionsResult
        }

        val inProgressTransition = transitions.find {
            it.name.equals("In Progress", ignoreCase = true) ||
            it.to.statusCategory?.key == "indeterminate"
        } ?: return ApiResult.Error(ErrorType.NOT_FOUND, "No 'In Progress' transition available.")

        return apiClient.transitionIssue(issueKey, inProgressTransition.id)
    }
}
```

- [ ] **Step 2: Verify compilation**

Run:
```bash
./gradlew :jira:classes
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add jira/src/main/kotlin/com/workflow/orchestrator/jira/service/BranchingService.kt
git commit -m "feat(jira): add BranchingService with GitBrancher and Jira transition"
```

---

### Task 12: BranchChangeTicketDetector

**Files:**
- Create: `jira/src/main/kotlin/.../jira/listeners/BranchChangeTicketDetector.kt`
- Modify: `core/src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Write BranchChangeTicketDetector**

Create `jira/src/main/kotlin/com/workflow/orchestrator/jira/listeners/BranchChangeTicketDetector.kt`:

```kotlin
package com.workflow.orchestrator.jira.listeners

import com.intellij.dvcs.branch.BranchChangeListener
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.jira.api.JiraApiClient
import com.workflow.orchestrator.jira.service.ActiveTicketService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BranchChangeTicketDetector(private val project: Project) : BranchChangeListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun branchWillChange(branchName: String) {
        // No action needed before branch change
    }

    override fun branchHasChanged(branchName: String) {
        val ticketId = ActiveTicketService.extractTicketIdFromBranch(branchName) ?: return

        val settings = PluginSettings.getInstance(project)
        if (settings.state.jiraUrl.isBlank()) return

        // Update active ticket and persist
        settings.state.activeTicketId = ticketId

        // Fetch ticket summary from Jira in background
        scope.launch {
            val credentialStore = CredentialStore()
            val apiClient = JiraApiClient(
                baseUrl = settings.state.jiraUrl.trimEnd('/'),
                tokenProvider = { credentialStore.getToken(ServiceType.JIRA) }
            )

            val result = apiClient.getIssue(ticketId)
            if (result is ApiResult.Success) {
                settings.state.activeTicketSummary = result.data.fields.summary
            }
        }
    }
}
```

- [ ] **Step 2: Register listener in plugin.xml**

In `core/src/main/resources/META-INF/plugin.xml`, add inside `<idea-plugin>`:

```xml
    <projectListeners>
        <listener class="com.workflow.orchestrator.jira.listeners.BranchChangeTicketDetector"
                  topic="com.intellij.dvcs.branch.BranchChangeListener"/>
    </projectListeners>
```

- [ ] **Step 3: Verify compilation**

Run:
```bash
./gradlew :jira:classes
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add jira/src/main/kotlin/com/workflow/orchestrator/jira/listeners/BranchChangeTicketDetector.kt
git add core/src/main/resources/META-INF/plugin.xml
git commit -m "feat(jira): add BranchChangeListener for auto-detecting active ticket"
```

---

### Task 13: CommitMessagePrefixHandlerFactory

**Files:**
- Create: `jira/src/main/kotlin/.../jira/listeners/CommitMessagePrefixHandlerFactory.kt`
- Modify: `core/src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Write CommitMessagePrefixHandlerFactory**

Create `jira/src/main/kotlin/com/workflow/orchestrator/jira/listeners/CommitMessagePrefixHandlerFactory.kt`:

```kotlin
package com.workflow.orchestrator.jira.listeners

import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.VcsCheckinHandlerFactory
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.jira.service.CommitPrefixService
import git4idea.GitVcs

class CommitMessagePrefixHandlerFactory : VcsCheckinHandlerFactory(GitVcs.getKey()) {

    override fun createVcsHandler(
        panel: CheckinProjectPanel,
        commitContext: CommitContext
    ): CheckinHandler {
        return CommitMessagePrefixHandler(panel)
    }
}

private class CommitMessagePrefixHandler(
    private val panel: CheckinProjectPanel
) : CheckinHandler() {

    override fun beforeCheckin(): ReturnResult {
        val project = panel.project
        val settings = PluginSettings.getInstance(project)
        val ticketId = settings.state.activeTicketId

        if (ticketId.isBlank()) return ReturnResult.COMMIT

        val currentMessage = panel.commitMessage
        if (currentMessage.isNullOrBlank()) return ReturnResult.COMMIT

        val prefixedMessage = CommitPrefixService.addPrefix(
            message = currentMessage,
            ticketId = ticketId,
            useConventionalCommits = settings.state.useConventionalCommits
        )

        if (prefixedMessage != currentMessage) {
            panel.commitMessage = prefixedMessage
        }

        return ReturnResult.COMMIT
    }
}
```

- [ ] **Step 2: Register in plugin.xml**

In `core/src/main/resources/META-INF/plugin.xml`, add inside the main `<extensions defaultExtensionNs="com.intellij">` block:

```xml
        <!-- Commit message auto-prefix -->
        <vcsCheckinHandlerFactory
            implementation="com.workflow.orchestrator.jira.listeners.CommitMessagePrefixHandlerFactory"/>
```

- [ ] **Step 3: Verify compilation**

Run:
```bash
./gradlew :jira:classes
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add jira/src/main/kotlin/com/workflow/orchestrator/jira/listeners/CommitMessagePrefixHandlerFactory.kt
git add core/src/main/resources/META-INF/plugin.xml
git commit -m "feat(jira): add VcsCheckinHandlerFactory for commit message auto-prefix"
```

---

## Chunk 6: Status Bar & Gate 2 Verification

### Task 14: TicketStatusBarWidget

**Files:**
- Create: `jira/src/main/kotlin/.../jira/ui/TicketStatusBarWidget.kt`
- Create: `jira/src/main/kotlin/.../jira/ui/TicketStatusBarWidgetFactory.kt`
- Modify: `core/src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Write TicketStatusBarWidget**

Create `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/TicketStatusBarWidget.kt`:

```kotlin
package com.workflow.orchestrator.jira.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBLabel
import com.intellij.util.Consumer
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.settings.PluginSettings
import git4idea.repo.GitRepositoryManager
import java.awt.Component
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.MouseEvent
import javax.swing.JPanel

class TicketStatusBarWidget(
    private val project: Project
) : StatusBarWidget, StatusBarWidget.TextPresentation {

    companion object {
        const val ID = "WorkflowTicketStatusBar"
    }

    private var statusBar: StatusBar? = null

    override fun ID(): String = ID

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
    }

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun getText(): String {
        val settings = PluginSettings.getInstance(project)
        val ticketId = settings.state.activeTicketId
        return if (ticketId.isNotBlank()) ticketId else "Workflow: Idle"
    }

    override fun getTooltipText(): String {
        val settings = PluginSettings.getInstance(project)
        val ticketId = settings.state.activeTicketId
        val summary = settings.state.activeTicketSummary
        return if (ticketId.isNotBlank()) {
            "$ticketId: $summary"
        } else {
            "No active ticket"
        }
    }

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer { event ->
        showPopup(event.component)
    }

    private fun showPopup(component: Component) {
        val settings = PluginSettings.getInstance(project)
        val ticketId = settings.state.activeTicketId
        val summary = settings.state.activeTicketSummary

        val panel = JPanel(GridBagLayout())
        panel.border = JBUI.Borders.empty(8)
        val gbc = GridBagConstraints().apply {
            gridx = 0; fill = GridBagConstraints.HORIZONTAL; weightx = 1.0
            anchor = GridBagConstraints.NORTHWEST
        }

        var row = 0
        if (ticketId.isNotBlank()) {
            gbc.gridy = row++
            panel.add(JBLabel("<html><b>Active Ticket:</b> $ticketId</html>"), gbc)

            if (summary.isNotBlank()) {
                gbc.gridy = row++
                gbc.insets = JBUI.insets(2, 0)
                panel.add(JBLabel(summary), gbc)
            }

            // Show current branch
            val repos = GitRepositoryManager.getInstance(project).repositories
            val currentBranch = repos.firstOrNull()?.currentBranchName
            if (currentBranch != null) {
                gbc.gridy = row++
                gbc.insets = JBUI.insets(4, 0, 0, 0)
                panel.add(JBLabel("<html><b>Branch:</b> $currentBranch</html>"), gbc)
            }
        } else {
            gbc.gridy = row++
            panel.add(JBLabel("No active ticket"), gbc)
        }

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, null)
            .setTitle("Workflow Status")
            .setMovable(false)
            .setResizable(false)
            .setRequestFocus(true)
            .addListener(object : com.intellij.openapi.ui.popup.JBPopupListener {
                override fun onClosed(event: com.intellij.openapi.ui.popup.LightweightWindowEvent) {}
            })
            .createPopup()

        popup.showUnderneathOf(component)
    }

    fun update() {
        statusBar?.updateWidget(ID)
    }

    override fun dispose() {
        statusBar = null
    }
}
```

- [ ] **Step 2: Write TicketStatusBarWidgetFactory**

Create `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/TicketStatusBarWidgetFactory.kt`:

```kotlin
package com.workflow.orchestrator.jira.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory

class TicketStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = TicketStatusBarWidget.ID

    override fun getDisplayName(): String = "Workflow Ticket Status"

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget {
        return TicketStatusBarWidget(project)
    }

    override fun disposeWidget(widget: StatusBarWidget) {
        Disposer.dispose(widget)
    }
}
```

- [ ] **Step 3: Register in plugin.xml**

In `core/src/main/resources/META-INF/plugin.xml`, add inside `<extensions defaultExtensionNs="com.intellij">`:

```xml
        <!-- Status Bar Widget -->
        <statusBarWidgetFactory id="WorkflowTicketStatusBar"
            implementation="com.workflow.orchestrator.jira.ui.TicketStatusBarWidgetFactory"/>
```

- [ ] **Step 4: Verify compilation**

Run:
```bash
./gradlew :jira:classes
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/TicketStatusBarWidget.kt
git add jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/TicketStatusBarWidgetFactory.kt
git add core/src/main/resources/META-INF/plugin.xml
git commit -m "feat(jira): add ticket status bar widget with text-and-popup"
```

---

### Task 15: Update message bundle + integration verification

**Files:**
- Modify: `core/src/main/resources/messages/WorkflowBundle.properties`

- [ ] **Step 1: Add Phase 1B strings to message bundle**

Add to `core/src/main/resources/messages/WorkflowBundle.properties`:

```properties
# Sprint Dashboard
sprint.refresh=Refresh Sprint
sprint.startWork=Start Work
sprint.startWork.description=Create branch and transition ticket to In Progress
sprint.loading=Loading sprint tickets...
sprint.error=Failed to load sprint: {0}
sprint.noBoards=No Jira Scrum boards found. Create a board first.
sprint.noActiveSprint=No active sprint found on this board.
sprint.ticketCount={0} ticket(s)

# Branching
branching.creating=Creating branch for {0}...
branching.success=Branch created: {0}
branching.failed=Failed to create branch: {0}

# Commit Prefix
commit.prefix.standard={0}: {1}
commit.prefix.conventional=feat({0}): {1}

# Status Bar Widget
statusbar.idle=Workflow: Idle
statusbar.activeTicket={0}
statusbar.tooltip.active={0}: {1}
statusbar.tooltip.idle=No active ticket
statusbar.popup.title=Workflow Status
```

- [ ] **Step 2: Run full test suite**

Run:
```bash
./gradlew :core:test :jira:test
```
Expected: All tests PASS.
- Core: 30 tests (from Phase 1A)
- Jira: ~26 tests (DTOs: 5, API: 6, Sprint: 5, ActiveTicket: 5, BranchName: 5, CommitPrefix: 5 — numbers may vary slightly based on implementation)

- [ ] **Step 3: Run plugin verifier**

Run:
```bash
./gradlew verifyPlugin
```
Expected: No compatibility problems reported.

- [ ] **Step 4: Build plugin**

Run:
```bash
./gradlew buildPlugin
```
Expected: Plugin ZIP created in `build/distributions/`.

- [ ] **Step 5: Test in IDE**

Run:
```bash
./gradlew runIde
```

**Verify Gate 2 checklist:**
1. Sprint Dashboard shows Jira tickets assigned to current user (when Jira is configured)
2. Ticket list shows key, summary, status, priority, and blocker count
3. Clicking a ticket shows detail panel with dependencies
4. "Start Work" creates branch via GitBrancher + transitions Jira to "In Progress"
5. Branch name follows the pattern from Settings (e.g., `feature/PROJ-123-fix-login-page-redirect`)
6. Commit messages auto-prefixed with ticket ID (or conventional commit format)
7. Status bar shows current ticket ID or "Workflow: Idle"
8. Clicking status bar widget opens popup with ticket details + branch
9. Switching branches auto-detects active ticket from branch name

- [ ] **Step 6: Commit**

```bash
git add core/src/main/resources/messages/WorkflowBundle.properties
git commit -m "chore: Phase 1B sprint and branching complete - Gate 2 ready for alpha"
```

---

## Gate 2 Acceptance Criteria

After completing all 15 tasks, the plugin meets these criteria:

- [ ] Sprint Dashboard shows Jira tickets assigned to current user
- [ ] Cross-team dependency view (blocked-by links visible in detail panel)
- [ ] "Start Work" creates branch via GitBrancher + transitions Jira to "In Progress"
- [ ] Branch naming follows configurable pattern with ticket ID
- [ ] Commit messages auto-prefixed with ticket ID (standard + conventional commits)
- [ ] Status bar shows current ticket
- [ ] Switching branches auto-detects active ticket
- [ ] All unit tests pass (`./gradlew :core:test :jira:test`)
- [ ] Plugin verifier passes (`./gradlew verifyPlugin`)
- [ ] Plugin builds (`./gradlew buildPlugin`)
- [ ] Manual verification in `runIde` confirms all features work
- [ ] **START ALPHA TESTING HERE**
