# Mock Server — Adversarial Testing Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a standalone Ktor mock server that simulates Jira, Bamboo, and SonarQube with deliberately divergent data to expose hardcoded assumptions in the plugin.

**Architecture:** A new `:mock-server` Gradle module using Ktor embedded server (Netty). Three mock services run from a single process on separate ports (8180/8280/8380). Each service has its own in-memory mutable state, route definitions, and data factory. A chaos middleware plugin provides toggleable failure injection. No shared DTOs with the plugin — complete independence.

**Tech Stack:** Kotlin 2.1.10, Ktor 3.1.1 (Netty), kotlinx.serialization 1.7.3, kotlinx.coroutines 1.10.1, Logback

**Spec:** `docs/superpowers/specs/2026-03-12-mock-server-adversarial-testing-design.md`

---

## Chunk 1: Module Bootstrap & Config

### Task 1: Create Gradle module and build configuration

**Files:**
- Create: `mock-server/build.gradle.kts`
- Modify: `settings.gradle.kts`
- Create: `mock-server/src/main/resources/logback.xml`

- [ ] **Step 1: Add `:mock-server` to settings.gradle.kts**

Add the module to the include list in `settings.gradle.kts`. It must NOT be added to the root `build.gradle.kts` dependencies — it is standalone.

```kotlin
// In settings.gradle.kts, add to the include() block:
include(
    ":core",
    ":jira",
    ":git-integration",
    ":bamboo",
    ":sonar",
    ":cody",
    ":automation",
    ":mock-server",
)
```

- [ ] **Step 2: Create mock-server/build.gradle.kts**

```kotlin
// mock-server/build.gradle.kts — Standalone adversarial mock server.
// NOT an IntelliJ platform module. Uses Ktor application plugin.

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlinSerialization)
    application
}

application {
    mainClass.set("com.workflow.orchestrator.mockserver.MockServerMainKt")
}

kotlin {
    jvmToolchain(providers.gradleProperty("javaVersion").get().toInt())
}

repositories {
    mavenCentral()
}

val ktorVersion = "3.1.1"

dependencies {
    // Ktor server
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Coroutines (for timed build progression)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.6")

    // Testing
    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.platform.launcher)
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
}

tasks.test {
    useJUnitPlatform()
}
```

- [ ] **Step 3: Create logback.xml**

Create `mock-server/src/main/resources/logback.xml`:

```xml
<configuration>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    <root level="INFO">
        <appender-ref ref="STDOUT" />
    </root>
    <logger name="io.ktor" level="INFO" />
</configuration>
```

- [ ] **Step 4: Verify module compiles**

Run: `./gradlew :mock-server:compileKotlin`
Expected: BUILD SUCCESSFUL (no sources yet, but build config is valid)

- [ ] **Step 5: Commit**

```bash
git add mock-server/build.gradle.kts mock-server/src/main/resources/logback.xml settings.gradle.kts
git commit -m "chore: add :mock-server Gradle module with Ktor dependencies"
```

---

### Task 2: MockConfig and MockServerMain entry point

**Files:**
- Create: `mock-server/src/main/kotlin/com/workflow/orchestrator/mockserver/config/MockConfig.kt`
- Create: `mock-server/src/main/kotlin/com/workflow/orchestrator/mockserver/MockServerMain.kt`

- [ ] **Step 1: Create MockConfig data class**

Create `mock-server/src/main/kotlin/com/workflow/orchestrator/mockserver/config/MockConfig.kt`:

```kotlin
package com.workflow.orchestrator.mockserver.config

data class MockConfig(
    val jiraPort: Int = System.getenv("MOCK_JIRA_PORT")?.toIntOrNull() ?: 8180,
    val bambooPort: Int = System.getenv("MOCK_BAMBOO_PORT")?.toIntOrNull() ?: 8280,
    val sonarPort: Int = System.getenv("MOCK_SONAR_PORT")?.toIntOrNull() ?: 8380,
)
```

- [ ] **Step 2: Create MockServerMain with startup banner**

Create `mock-server/src/main/kotlin/com/workflow/orchestrator/mockserver/MockServerMain.kt`:

```kotlin
package com.workflow.orchestrator.mockserver

import com.workflow.orchestrator.mockserver.config.MockConfig
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*

fun main() {
    val config = MockConfig()
    printBanner(config)

    runBlocking {
        val jobs = listOf(
            launch { startServer("Jira", config.jiraPort) },
            launch { startServer("Bamboo", config.bambooPort) },
            launch { startServer("SonarQube", config.sonarPort) },
        )
        jobs.joinAll()
    }
}

private suspend fun startServer(name: String, port: Int) {
    try {
        embeddedServer(Netty, port = port) {
            routing {
                get("/health") {
                    call.respondText("$name mock is running")
                }
            }
        }.start(wait = true)
    } catch (e: Exception) {
        System.err.println("ERROR: Failed to start $name mock on port $port — ${e.message}")
        System.err.println("Is port $port already in use?")
        throw e
    }
}

private fun printBanner(config: MockConfig) {
    println("""
        |
        |╔══════════════════════════════════════════════════╗
        |║          Workflow Orchestrator Mock Server        ║
        |╠══════════════════════════════════════════════════╣
        |║  Jira      → http://localhost:${config.jiraPort.toString().padEnd(18)}║
        |║  Bamboo    → http://localhost:${config.bambooPort.toString().padEnd(18)}║
        |║  SonarQube → http://localhost:${config.sonarPort.toString().padEnd(18)}║
        |║                                                  ║
        |║  Admin:  GET /__admin/state on any port          ║
        |║  Reset:  POST /__admin/reset on any port         ║
        |╚══════════════════════════════════════════════════╝
    """.trimMargin())
}
```

- [ ] **Step 3: Verify it starts and responds**

Run: `./gradlew :mock-server:run &` (background it), then:
```bash
curl http://localhost:8180/health
curl http://localhost:8280/health
curl http://localhost:8380/health
```
Expected: Each returns `{Service} mock is running`. Kill the process after.

- [ ] **Step 4: Commit**

```bash
git add mock-server/src/main/kotlin/
git commit -m "feat(mock-server): add entry point with 3 Ktor servers and startup banner"
```

---

## Chunk 2: Jira Mock — State, Data Factory, Routes

### Task 3: Jira in-memory state and data factory

**Files:**
- Create: `mock-server/src/main/kotlin/com/workflow/orchestrator/mockserver/jira/JiraState.kt`
- Create: `mock-server/src/main/kotlin/com/workflow/orchestrator/mockserver/jira/JiraDataFactory.kt`
- Create: `mock-server/src/test/kotlin/com/workflow/orchestrator/mockserver/jira/JiraDataFactoryTest.kt`

- [ ] **Step 1: Write JiraState — in-memory state holder**

Create `mock-server/src/main/kotlin/com/workflow/orchestrator/mockserver/jira/JiraState.kt`:

```kotlin
package com.workflow.orchestrator.mockserver.jira

import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class JiraStatus(
    val id: String,
    val name: String,
    val statusCategory: JiraStatusCategory,
)

@Serializable
data class JiraStatusCategory(
    val id: Int,
    val key: String,
    val name: String,
)

@Serializable
data class JiraTransition(
    val id: String,
    val name: String,
    val to: JiraStatus,
    val fields: Map<String, TransitionField> = emptyMap(),
)

@Serializable
data class TransitionField(
    val required: Boolean,
    val name: String = "",
)

@Serializable
data class JiraIssue(
    val key: String,
    val summary: String,
    val status: JiraStatus,
    val issueType: String,
    val assignee: String?,
    val priority: String,
    val issueLinks: List<JiraIssueLink> = emptyList(),
    val comments: MutableList<JiraComment> = mutableListOf(),
    val worklogs: MutableList<JiraWorklog> = mutableListOf(),
)

@Serializable
data class JiraIssueLink(
    val type: String, // "blocked-by", "relates-to"
    val outwardIssue: String?, // issue key
    val inwardIssue: String?, // issue key
)

@Serializable
data class JiraComment(
    val id: String,
    val body: String,
    val author: String,
    val created: String,
)

@Serializable
data class JiraWorklog(
    val id: String,
    val timeSpentSeconds: Long,
    val comment: String,
    val author: String,
    val started: String,
)

@Serializable
data class JiraSprint(
    val id: Int,
    val name: String,
    val state: String, // "active", "closed", "future"
    val boardId: Int,
)

@Serializable
data class JiraBoard(
    val id: Int,
    val name: String,
    val type: String, // "scrum"
)

class JiraState {
    var currentUser = "mock.user"
    var boards: MutableList<JiraBoard> = mutableListOf()
    var sprints: MutableList<JiraSprint> = mutableListOf()
    var issues: ConcurrentHashMap<String, JiraIssue> = ConcurrentHashMap()
    var statuses: List<JiraStatus> = emptyList()
    // Map: fromStatusId -> list of available transitions
    var transitionMap: Map<String, List<JiraTransition>> = emptyMap()

    fun getTransitionsForIssue(issueKey: String): List<JiraTransition> {
        val issue = issues[issueKey] ?: return emptyList()
        return transitionMap[issue.status.id] ?: emptyList()
    }

    fun applyTransition(issueKey: String, transitionId: String): Boolean {
        val issue = issues[issueKey] ?: return false
        val transitions = getTransitionsForIssue(issueKey)
        val transition = transitions.find { it.id == transitionId } ?: return false
        issues[issueKey] = issue.copy(status = transition.to)
        return true
    }

    fun getRequiredFieldsForTransition(issueKey: String, transitionId: String): Map<String, TransitionField> {
        val transitions = getTransitionsForIssue(issueKey)
        val transition = transitions.find { it.id == transitionId } ?: return emptyMap()
        return transition.fields.filter { it.value.required }
    }
}
```

- [ ] **Step 2: Write JiraDataFactory — divergent test data generator**

Create `mock-server/src/main/kotlin/com/workflow/orchestrator/mockserver/jira/JiraDataFactory.kt`:

```kotlin
package com.workflow.orchestrator.mockserver.jira

object JiraDataFactory {

    // --- Divergent statuses (see spec: "Workflow States") ---

    private val STATUS_OPEN = JiraStatus("1", "Open", JiraStatusCategory(1, "new", "To Do"))
    private val STATUS_WIP = JiraStatus("2", "WIP", JiraStatusCategory(2, "in_flight", "In Progress"))
    private val STATUS_PEER_REVIEW = JiraStatus("3", "Peer Review", JiraStatusCategory(2, "in_flight", "In Progress"))
    private val STATUS_QA_TESTING = JiraStatus("4", "QA Testing", JiraStatusCategory(3, "verification", "Verification"))
    private val STATUS_APPROVED = JiraStatus("5", "Approved", JiraStatusCategory(4, "done", "Done"))
    private val STATUS_CLOSED = JiraStatus("6", "Closed", JiraStatusCategory(4, "done", "Done"))
    private val STATUS_BLOCKED = JiraStatus("7", "Blocked", JiraStatusCategory(5, "blocked", "Blocked"))
    private val STATUS_INVESTIGATING = JiraStatus("8", "Investigating", JiraStatusCategory(6, "indeterminate", "In Progress"))

    private val ALL_STATUSES = listOf(
        STATUS_OPEN, STATUS_WIP, STATUS_PEER_REVIEW, STATUS_QA_TESTING,
        STATUS_APPROVED, STATUS_CLOSED, STATUS_BLOCKED, STATUS_INVESTIGATING,
    )

    // --- Transitions with required fields ---

    private val TRANSITIONS = mapOf(
        // From Open
        "1" to listOf(
            JiraTransition("11", "Start Working", STATUS_WIP,
                mapOf("assignee" to TransitionField(required = true, name = "Assignee"))),
            JiraTransition("12", "Block", STATUS_BLOCKED, emptyMap()),
            JiraTransition("18", "Investigate", STATUS_INVESTIGATING, emptyMap()),
        ),
        // From WIP
        "2" to listOf(
            JiraTransition("21", "Move to Peer Review", STATUS_PEER_REVIEW,
                mapOf("comment" to TransitionField(required = true, name = "Review Notes"))),
            JiraTransition("22", "Block", STATUS_BLOCKED, emptyMap()),
            JiraTransition("23", "Reopen", STATUS_OPEN, emptyMap()),
        ),
        // From Peer Review
        "3" to listOf(
            JiraTransition("31", "Send to QA", STATUS_QA_TESTING, emptyMap()),
            JiraTransition("32", "Reject", STATUS_WIP, emptyMap()),
        ),
        // From QA Testing
        "4" to listOf(
            JiraTransition("41", "Approve", STATUS_APPROVED, emptyMap()),
            JiraTransition("42", "Reject", STATUS_WIP,
                mapOf("comment" to TransitionField(required = true, name = "Rejection Reason"))),
        ),
        // From Approved
        "5" to listOf(
            JiraTransition("51", "Close", STATUS_CLOSED, emptyMap()),
        ),
        // From Blocked
        "7" to listOf(
            JiraTransition("71", "Unblock", STATUS_OPEN,
                mapOf("comment" to TransitionField(required = true, name = "Resolution"))),
        ),
        // From Investigating
        "8" to listOf(
            JiraTransition("81", "Start Working", STATUS_WIP, emptyMap()),
            JiraTransition("82", "Block", STATUS_BLOCKED, emptyMap()),
        ),
    )

    fun createDefaultState(): JiraState {
        val state = JiraState()
        state.boards = mutableListOf(JiraBoard(42, "Project Board", "scrum"))
        state.sprints = mutableListOf(
            JiraSprint(7, "Sprint 2026.11", "active", 42),
            JiraSprint(6, "Sprint 2026.10", "closed", 42),
        )
        state.statuses = ALL_STATUSES
        state.transitionMap = TRANSITIONS
        state.issues = createDefaultIssues()
        return state
    }

    private fun createDefaultIssues(): java.util.concurrent.ConcurrentHashMap<String, JiraIssue> {
        val map = java.util.concurrent.ConcurrentHashMap<String, JiraIssue>()
        val issues = listOf(
            JiraIssue("PROJ-101", "Implement user authentication flow", STATUS_OPEN, "Story",
                "mock.user", "High",
                issueLinks = listOf(JiraIssueLink("relates-to", "PROJ-102", null))),
            JiraIssue("PROJ-102", "Fix payment gateway timeout", STATUS_WIP, "Defect",
                "mock.user", "Critical",
                issueLinks = listOf(JiraIssueLink("blocked-by", null, "PROJ-105"))),
            JiraIssue("PROJ-103", "", STATUS_PEER_REVIEW, "Spike", // empty summary — tests null/empty handling
                "mock.user", "Medium"),
            JiraIssue("PROJ-104", "Database migration for audit tables", STATUS_QA_TESTING, "Tech Debt",
                "mock.user", "Low"),
            JiraIssue("PROJ-105", "Evaluate caching strategies", STATUS_INVESTIGATING, "Spike",
                "mock.user", "Medium"),
            JiraIssue("PROJ-106", "Update dependencies to latest", STATUS_BLOCKED, "Tech Debt",
                "mock.user", "Low",
                issueLinks = listOf(JiraIssueLink("blocked-by", null, "EXT-999"))),
        )
        issues.forEach { map[it.key] = it }
        return map
    }

    fun createEmptySprintState(): JiraState {
        val state = createDefaultState()
        state.issues.clear()
        return state
    }

    fun createLargeSprintState(): JiraState {
        val state = createDefaultState()
        val types = listOf("Story", "Defect", "Spike", "Tech Debt")
        val priorities = listOf("Critical", "High", "Medium", "Low")
        for (i in 200..250) {
            val status = ALL_STATUSES[i % ALL_STATUSES.size]
            state.issues["PROJ-$i"] = JiraIssue(
                key = "PROJ-$i",
                summary = "Auto-generated ticket $i for load testing",
                status = status,
                issueType = types[i % types.size],
                assignee = "mock.user",
                priority = priorities[i % priorities.size],
            )
        }
        return state
    }

    fun createNoActiveSprintState(): JiraState {
        val state = createDefaultState()
        state.sprints = mutableListOf(
            JiraSprint(6, "Sprint 2026.10", "closed", 42),
            JiraSprint(8, "Sprint 2026.12", "future", 42),
        )
        return state
    }

    fun createTransitionBlockedState(): JiraState {
        val state = createDefaultState()
        // Override all transitions to require fields
        val blockedTransitions = TRANSITIONS.mapValues { (_, transitions) ->
            transitions.map { t ->
                t.copy(fields = t.fields + mapOf(
                    "comment" to TransitionField(required = true, name = "Justification"),
                    "assignee" to TransitionField(required = true, name = "Assignee"),
                ))
            }
        }
        state.transitionMap = blockedTransitions
        return state
    }

    /**
     * Happy-path: uses standard Jira statuses that the plugin expects.
     * This is the baseline scenario — the plugin SHOULD work correctly with this data.
     */
    fun createHappyPathState(): JiraState {
        val standardOpen = JiraStatus("1", "To Do", JiraStatusCategory(1, "new", "To Do"))
        val standardInProgress = JiraStatus("2", "In Progress", JiraStatusCategory(2, "indeterminate", "In Progress"))
        val standardDone = JiraStatus("3", "Done", JiraStatusCategory(3, "done", "Done"))
        val state = JiraState()
        state.boards = mutableListOf(JiraBoard(42, "Project Board", "scrum"))
        state.sprints = mutableListOf(JiraSprint(7, "Sprint 2026.11", "active", 42))
        state.statuses = listOf(standardOpen, standardInProgress, standardDone)
        state.transitionMap = mapOf(
            "1" to listOf(JiraTransition("11", "In Progress", standardInProgress)),
            "2" to listOf(JiraTransition("21", "Done", standardDone)),
        )
        val map = java.util.concurrent.ConcurrentHashMap<String, JiraIssue>()
        map["PROJ-101"] = JiraIssue("PROJ-101", "Standard ticket", standardOpen, "Story", "mock.user", "High")
        map["PROJ-102"] = JiraIssue("PROJ-102", "Another ticket", standardInProgress, "Bug", "mock.user", "Medium")
        state.issues = map
        return state
    }
}
```

- [ ] **Step 3: Write test for JiraDataFactory**

Create `mock-server/src/test/kotlin/com/workflow/orchestrator/mockserver/jira/JiraDataFactoryTest.kt`:

```kotlin
package com.workflow.orchestrator.mockserver.jira

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class JiraDataFactoryTest {

    @Test
    fun `default state has 6 issues across different statuses`() {
        val state = JiraDataFactory.createDefaultState()
        assertEquals(6, state.issues.size)
        // Verify divergent status names - none should be "In Progress"
        val statusNames = state.issues.values.map { it.status.name }.toSet()
        assertFalse("In Progress" in statusNames, "Should NOT use standard 'In Progress' name")
        assertTrue("WIP" in statusNames, "Should use divergent 'WIP' name")
    }

    @Test
    fun `default state uses divergent category keys`() {
        val state = JiraDataFactory.createDefaultState()
        val categoryKeys = state.statuses.map { it.statusCategory.key }.toSet()
        assertTrue("in_flight" in categoryKeys, "Should include custom 'in_flight' key")
        assertTrue("verification" in categoryKeys, "Should include custom 'verification' key")
        assertTrue("blocked" in categoryKeys, "Should include custom 'blocked' key")
        assertTrue("indeterminate" in categoryKeys, "Should include 'indeterminate' for fallback test")
    }

    @Test
    fun `transitions from Open require assignee`() {
        val state = JiraDataFactory.createDefaultState()
        val transitions = state.getTransitionsForIssue("PROJ-101") // status: Open
        val startWorking = transitions.find { it.name == "Start Working" }
        assertNotNull(startWorking)
        assertTrue(startWorking!!.fields["assignee"]?.required == true)
    }

    @Test
    fun `applyTransition changes issue status`() {
        val state = JiraDataFactory.createDefaultState()
        val before = state.issues["PROJ-101"]!!.status.name
        assertEquals("Open", before)
        state.applyTransition("PROJ-101", "11") // Start Working -> WIP
        val after = state.issues["PROJ-101"]!!.status.name
        assertEquals("WIP", after)
    }

    @Test
    fun `one issue has empty summary for null handling test`() {
        val state = JiraDataFactory.createDefaultState()
        val emptyTitle = state.issues.values.find { it.summary.isEmpty() }
        assertNotNull(emptyTitle, "Should have one issue with empty summary")
    }

    @Test
    fun `issue types diverge from standard Jira`() {
        val state = JiraDataFactory.createDefaultState()
        val types = state.issues.values.map { it.issueType }.toSet()
        assertFalse("Bug" in types, "Should use 'Defect' not 'Bug'")
        assertTrue("Defect" in types)
        assertTrue("Spike" in types)
        assertTrue("Tech Debt" in types)
    }

    @Test
    fun `large sprint has 50+ issues`() {
        val state = JiraDataFactory.createLargeSprintState()
        assertTrue(state.issues.size >= 50)
    }

    @Test
    fun `no-active-sprint has no active sprints`() {
        val state = JiraDataFactory.createNoActiveSprintState()
        val active = state.sprints.filter { it.state == "active" }
        assertTrue(active.isEmpty())
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :mock-server:test`
Expected: All tests PASS

- [ ] **Step 5: Commit**

```bash
git add mock-server/src/
git commit -m "feat(mock-server): add Jira state model and divergent data factory"
```

---

### Task 4: Jira mock routes

**Files:**
- Create: `mock-server/src/main/kotlin/com/workflow/orchestrator/mockserver/jira/JiraMockRoutes.kt`
- Create: `mock-server/src/test/kotlin/com/workflow/orchestrator/mockserver/jira/JiraMockRoutesTest.kt`
- Modify: `mock-server/src/main/kotlin/com/workflow/orchestrator/mockserver/MockServerMain.kt`

- [ ] **Step 1: Write JiraMockRoutes**

Create `mock-server/src/main/kotlin/com/workflow/orchestrator/mockserver/jira/JiraMockRoutes.kt`:

```kotlin
package com.workflow.orchestrator.mockserver.jira

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * CRITICAL: All route functions take a `() -> State` provider lambda, NOT a direct state reference.
 * This is because Ktor calls `jiraRoutes(...)` once at server startup. If we passed `JiraState`
 * directly, route closures would capture that snapshot. When scenario switching replaces the
 * state in the holder, routes would still serve the old state.
 *
 * With a lambda, each request calls stateProvider() to get the CURRENT state from the holder.
 * Called as: jiraRoutes { jiraHolder.state }
 *
 * Every route handler must start with: val state = stateProvider()
 */
fun Route.jiraRoutes(stateProvider: () -> JiraState) {
    val json = Json { prettyPrint = true; encodeDefaults = true }

    // GET /rest/api/2/myself — Test connection
    get("/rest/api/2/myself") {
        val state = stateProvider()
        call.respond(buildJsonObject {
            put("key", state.currentUser)
            put("name", state.currentUser)
            put("displayName", "Mock User")
            put("emailAddress", "mock.user@example.com")
            put("active", true)
        }.toString())
    }

    // NOTE: All subsequent handlers follow the same pattern:
    //   val state = stateProvider()
    // at the top of each handler block. Shown once above, omitted below for brevity.
    // The implementer MUST add this line to every handler.

    // GET /rest/agile/1.0/board?type=scrum — Board discovery
    get("/rest/agile/1.0/board") {
        val state = stateProvider()
        val type = call.request.queryParameters["type"]
        val boards = if (type != null) state.boards.filter { it.type == type } else state.boards
        call.respondText(
            buildJsonObject {
                put("maxResults", 50)
                put("startAt", 0)
                put("isLast", true)
                putJsonArray("values") {
                    boards.forEach { board ->
                        addJsonObject {
                            put("id", board.id)
                            put("name", board.name)
                            put("type", board.type)
                        }
                    }
                }
            }.toString(),
            ContentType.Application.Json
        )
    }

    // GET /rest/agile/1.0/board/{boardId}/sprint?state=active — Sprint discovery
    get("/rest/agile/1.0/board/{boardId}/sprint") {
        val boardId = call.parameters["boardId"]?.toIntOrNull()
        val stateFilter = call.request.queryParameters["state"]
        val sprints = state.sprints
            .filter { boardId == null || it.boardId == boardId }
            .filter { stateFilter == null || it.state == stateFilter }
        call.respondText(
            buildJsonObject {
                put("maxResults", 50)
                put("startAt", 0)
                put("isLast", true)
                putJsonArray("values") {
                    sprints.forEach { sprint ->
                        addJsonObject {
                            put("id", sprint.id)
                            put("name", sprint.name)
                            put("state", sprint.state)
                        }
                    }
                }
            }.toString(),
            ContentType.Application.Json
        )
    }

    // GET /rest/agile/1.0/sprint/{sprintId}/issue — Sprint issues
    get("/rest/agile/1.0/sprint/{sprintId}/issue") {
        val assigneeIssues = state.issues.values
            .filter { it.assignee == state.currentUser }
            .sortedBy { it.key }
        call.respondText(
            buildJsonObject {
                put("maxResults", 200)
                put("startAt", 0)
                put("total", assigneeIssues.size)
                putJsonArray("issues") {
                    assigneeIssues.forEach { issue ->
                        add(issueToJson(issue))
                    }
                }
            }.toString(),
            ContentType.Application.Json
        )
    }

    // GET /rest/api/2/issue/{key} — Issue detail with links
    get("/rest/api/2/issue/{key}") {
        val key = call.parameters["key"]
        val issue = state.issues[key]
        if (issue == null) {
            call.respond(HttpStatusCode.NotFound, """{"errorMessages":["Issue does not exist or you do not have permission."]}""")
            return@get
        }
        call.respondText(issueToJson(issue).toString(), ContentType.Application.Json)
    }

    // GET /rest/api/2/issue/{key}/transitions — Available transitions
    get("/rest/api/2/issue/{key}/transitions") {
        val key = call.parameters["key"]
        val transitions = state.getTransitionsForIssue(key ?: "")
        call.respondText(
            buildJsonObject {
                putJsonArray("transitions") {
                    transitions.forEach { t ->
                        addJsonObject {
                            put("id", t.id)
                            put("name", t.name)
                            putJsonObject("to") {
                                put("id", t.to.id)
                                put("name", t.to.name)
                                putJsonObject("statusCategory") {
                                    put("id", t.to.statusCategory.id)
                                    put("key", t.to.statusCategory.key)
                                    put("name", t.to.statusCategory.name)
                                }
                            }
                            if (t.fields.isNotEmpty()) {
                                putJsonObject("fields") {
                                    t.fields.forEach { (fieldName, field) ->
                                        putJsonObject(fieldName) {
                                            put("required", field.required)
                                            put("name", field.name)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }.toString(),
            ContentType.Application.Json
        )
    }

    // POST /rest/api/2/issue/{key}/transitions — Execute transition
    post("/rest/api/2/issue/{key}/transitions") {
        val key = call.parameters["key"] ?: ""
        val body = call.receiveText()
        val bodyJson = Json.parseToJsonElement(body).jsonObject
        val transitionId = bodyJson["transition"]?.jsonObject?.get("id")?.jsonPrimitive?.content

        if (transitionId == null) {
            call.respond(HttpStatusCode.BadRequest, """{"errorMessages":["Missing transition id"]}""")
            return@post
        }

        // Check required fields
        val requiredFields = state.getRequiredFieldsForTransition(key, transitionId)
        if (requiredFields.isNotEmpty()) {
            // Check if the body provides values for required fields
            val providedFields = bodyJson["fields"]?.jsonObject
            val missingFields = requiredFields.filter { (fieldName, _) ->
                providedFields?.get(fieldName) == null
            }
            if (missingFields.isNotEmpty()) {
                val errors = buildJsonObject {
                    missingFields.forEach { (fieldName, field) ->
                        put(fieldName, "${field.name} is required for this transition")
                    }
                }
                call.respondText(
                    buildJsonObject {
                        putJsonArray("errorMessages") {}
                        put("errors", errors)
                    }.toString(),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest
                )
                return@post
            }
        }

        if (state.applyTransition(key, transitionId)) {
            call.respond(HttpStatusCode.NoContent)
        } else {
            call.respond(HttpStatusCode.BadRequest, """{"errorMessages":["Transition not available"]}""")
        }
    }

    // POST /rest/api/2/issue/{key}/comment — Add comment
    post("/rest/api/2/issue/{key}/comment") {
        val key = call.parameters["key"] ?: ""
        val issue = state.issues[key]
        if (issue == null) {
            call.respond(HttpStatusCode.NotFound)
            return@post
        }
        val body = Json.parseToJsonElement(call.receiveText()).jsonObject
        val commentBody = body["body"]?.jsonPrimitive?.content ?: ""
        val comment = JiraComment(
            id = (issue.comments.size + 1).toString(),
            body = commentBody,
            author = state.currentUser,
            created = "2026-03-12T10:00:00.000+0000",
        )
        issue.comments.add(comment)
        call.respondText(
            buildJsonObject {
                put("id", comment.id)
                put("body", comment.body)
                putJsonObject("author") { put("name", comment.author) }
                put("created", comment.created)
            }.toString(),
            ContentType.Application.Json,
            HttpStatusCode.Created
        )
    }

    // POST /rest/api/2/issue/{key}/worklog — Log time
    post("/rest/api/2/issue/{key}/worklog") {
        val key = call.parameters["key"] ?: ""
        val issue = state.issues[key]
        if (issue == null) {
            call.respond(HttpStatusCode.NotFound)
            return@post
        }
        val body = Json.parseToJsonElement(call.receiveText()).jsonObject
        val worklog = JiraWorklog(
            id = (issue.worklogs.size + 1).toString(),
            timeSpentSeconds = body["timeSpentSeconds"]?.jsonPrimitive?.long ?: 0,
            comment = body["comment"]?.jsonPrimitive?.content ?: "",
            author = state.currentUser,
            started = body["started"]?.jsonPrimitive?.content ?: "2026-03-12T09:00:00.000+0000",
        )
        issue.worklogs.add(worklog)
        call.respond(HttpStatusCode.Created)
    }
}

private fun issueToJson(issue: JiraIssue): JsonObject = buildJsonObject {
    put("key", issue.key)
    put("id", issue.key.hashCode().toString())
    putJsonObject("fields") {
        put("summary", issue.summary)
        putJsonObject("status") {
            put("id", issue.status.id)
            put("name", issue.status.name)
            putJsonObject("statusCategory") {
                put("id", issue.status.statusCategory.id)
                put("key", issue.status.statusCategory.key)
                put("name", issue.status.statusCategory.name)
            }
        }
        putJsonObject("issuetype") {
            put("name", issue.issueType)
        }
        if (issue.assignee != null) {
            putJsonObject("assignee") {
                put("name", issue.assignee)
                put("displayName", "Mock User")
            }
        } else {
            put("assignee", JsonNull)
        }
        putJsonObject("priority") {
            put("name", issue.priority)
        }
        putJsonArray("issuelinks") {
            issue.issueLinks.forEach { link ->
                addJsonObject {
                    putJsonObject("type") {
                        put("name", link.type)
                    }
                    link.outwardIssue?.let {
                        putJsonObject("outwardIssue") { put("key", it) }
                    }
                    link.inwardIssue?.let {
                        putJsonObject("inwardIssue") { put("key", it) }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Write route tests**

Create `mock-server/src/test/kotlin/com/workflow/orchestrator/mockserver/jira/JiraMockRoutesTest.kt`:

```kotlin
package com.workflow.orchestrator.mockserver.jira

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class JiraMockRoutesTest {

    private fun ApplicationTestBuilder.setupJira(state: JiraState = JiraDataFactory.createDefaultState()) {
        application {
            install(ContentNegotiation) { json() }
            routing { jiraRoutes { state } }
        }
    }

    @Test
    fun `GET myself returns current user`() = testApplication {
        setupJira()
        val response = client.get("/rest/api/2/myself")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("mock.user", body["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `GET board returns scrum boards`() = testApplication {
        setupJira()
        val response = client.get("/rest/agile/1.0/board?type=scrum")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val values = body["values"]?.jsonArray
        assertNotNull(values)
        assertTrue(values!!.isNotEmpty())
        assertEquals(42, values[0].jsonObject["id"]?.jsonPrimitive?.int)
    }

    @Test
    fun `GET sprint issues returns assigned tickets with divergent statuses`() = testApplication {
        setupJira()
        val response = client.get("/rest/agile/1.0/sprint/7/issue")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val issues = body["issues"]?.jsonArray ?: fail("no issues array")
        assertEquals(6, issues.size)
        // Verify no issue has standard "In Progress" status
        val statusNames = issues.map {
            it.jsonObject["fields"]?.jsonObject?.get("status")?.jsonObject?.get("name")?.jsonPrimitive?.content
        }
        assertFalse("In Progress" in statusNames)
    }

    @Test
    fun `GET transitions returns fields with requirements`() = testApplication {
        setupJira()
        val response = client.get("/rest/api/2/issue/PROJ-101/transitions")
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val transitions = body["transitions"]?.jsonArray ?: fail("no transitions")
        val startWorking = transitions.find {
            it.jsonObject["name"]?.jsonPrimitive?.content == "Start Working"
        }?.jsonObject
        assertNotNull(startWorking)
        val assigneeField = startWorking!!["fields"]?.jsonObject?.get("assignee")?.jsonObject
        assertTrue(assigneeField?.get("required")?.jsonPrimitive?.boolean == true)
    }

    @Test
    fun `POST transition without required fields returns 400`() = testApplication {
        setupJira()
        val response = client.post("/rest/api/2/issue/PROJ-101/transitions") {
            contentType(ContentType.Application.Json)
            setBody("""{"transition":{"id":"11"}}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val errors = body["errors"]?.jsonObject
        assertNotNull(errors?.get("assignee"))
    }

    @Test
    fun `POST transition with required fields succeeds`() = testApplication {
        setupJira()
        val response = client.post("/rest/api/2/issue/PROJ-101/transitions") {
            contentType(ContentType.Application.Json)
            setBody("""{"transition":{"id":"11"},"fields":{"assignee":{"name":"mock.user"}}}""")
        }
        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `GET nonexistent issue returns 404`() = testApplication {
        setupJira()
        val response = client.get("/rest/api/2/issue/NONEXIST-1")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
```

- [ ] **Step 3: Wire Jira routes into MockServerMain**

Update `MockServerMain.kt` to use Jira routes. Replace the `startServer("Jira", ...)` call:

```kotlin
// In MockServerMain.kt, update the startServer calls to:
launch { startJiraServer(config.jiraPort, jiraState) }
```

This will be done properly in Task 7 when we wire all services together. For now, focus on routes being testable.

- [ ] **Step 4: Run tests**

Run: `./gradlew :mock-server:test`
Expected: All tests PASS

- [ ] **Step 5: Commit**

```bash
git add mock-server/src/
git commit -m "feat(mock-server): add Jira mock routes with transition field validation"
```

---

## Chunk 3: Bamboo Mock — State, Data Factory, Routes

### Task 5: Bamboo state and data factory

**Files:**
- Create: `mock-server/src/main/kotlin/com/workflow/orchestrator/mockserver/bamboo/BambooState.kt`
- Create: `mock-server/src/main/kotlin/com/workflow/orchestrator/mockserver/bamboo/BambooDataFactory.kt`
- Create: `mock-server/src/test/kotlin/com/workflow/orchestrator/mockserver/bamboo/BambooDataFactoryTest.kt`

- [ ] **Step 1: Write BambooState**

Create `mock-server/src/main/kotlin/com/workflow/orchestrator/mockserver/bamboo/BambooState.kt`:

```kotlin
package com.workflow.orchestrator.mockserver.bamboo

import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@Serializable
data class BambooPlan(
    val key: String,
    val shortName: String,
    val name: String,
    val branches: List<BambooBranch> = emptyList(),
    val variables: Map<String, String> = emptyMap(),
)

@Serializable
data class BambooBranch(
    val key: String,
    val shortName: String,
)

@Serializable
data class BambooBuildResult(
    val buildResultKey: String,
    val planKey: String,
    val buildNumber: Int,
    val lifeCycleState: String,  // "Queued", "Running", "Finished", "Cancelled" — divergent!
    val state: String?,          // "Successful", "Failed", "PartiallySuccessful", "Cancelled"
    val stages: List<BambooStage> = emptyList(),
    val logEntries: List<String> = emptyList(),
    val variables: Map<String, String> = emptyMap(),
)

@Serializable
data class BambooStage(
    val name: String,
    val state: String, // "Successful", "Failed", "Unknown"
    val lifeCycleState: String,
)

class BambooState {
    var currentUser = "mock.user"
    var plans: MutableList<BambooPlan> = mutableListOf()
    var builds: ConcurrentHashMap<String, BambooBuildResult> = ConcurrentHashMap()
    private val buildCounter = AtomicInteger(100)
    private val progressionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun triggerBuild(planKey: String, variables: Map<String, String> = emptyMap()): BambooBuildResult {
        val buildNumber = buildCounter.incrementAndGet()
        val buildKey = "$planKey-$buildNumber"
        val build = BambooBuildResult(
            buildResultKey = buildKey,
            planKey = planKey,
            buildNumber = buildNumber,
            lifeCycleState = "Queued",
            state = null,
            variables = variables,
        )
        builds[buildKey] = build
        startBuildProgression(buildKey, planKey)
        return build
    }

    fun cancelBuild(resultKey: String): Boolean {
        val build = builds[resultKey] ?: return false
        if (build.lifeCycleState == "Finished") return false
        builds[resultKey] = build.copy(lifeCycleState = "Cancelled", state = "Cancelled")
        return true
    }

    private fun startBuildProgression(buildKey: String, planKey: String) {
        progressionScope.launch {
            // Queued -> Running after 10s
            delay(10_000)
            val current = builds[buildKey] ?: return@launch
            if (current.lifeCycleState == "Cancelled") return@launch
            builds[buildKey] = current.copy(lifeCycleState = "Running", state = null)

            // Running -> Finished after 30s
            delay(30_000)
            val running = builds[buildKey] ?: return@launch
            if (running.lifeCycleState == "Cancelled") return@launch

            val (finalState, stages) = determineBuildOutcome(planKey)
            builds[buildKey] = running.copy(
                lifeCycleState = "Finished",
                state = finalState,
                stages = stages,
                logEntries = generateBuildLog(planKey, finalState),
            )
        }
    }

    private fun determineBuildOutcome(planKey: String): Pair<String, List<BambooStage>> {
        return when {
            planKey.contains("BUILD") -> "Successful" to listOf(
                BambooStage("Compile & Package", "Successful", "Finished"),
                BambooStage("Security Scan", "Successful", "Finished"),
                BambooStage("Integration Tests", "Successful", "Finished"),
            )
            planKey.contains("TEST") -> "Failed" to listOf(
                BambooStage("Compile & Package", "Successful", "Finished"),
                BambooStage("Security Scan", "Successful", "Finished"),
                BambooStage("Integration Tests", "Failed", "Finished"),
            )
            planKey.contains("SONAR") -> "PartiallySuccessful" to listOf(
                BambooStage("Compile & Package", "Successful", "Finished"),
                BambooStage("Security Scan", "Failed", "Finished"),
                BambooStage("Integration Tests", "Successful", "Finished"),
            )
            else -> "Successful" to emptyList()
        }
    }

    private fun generateBuildLog(planKey: String, state: String): List<String> = listOf(
        "\u001B[34m[INFO]\u001B[0m Scanning for projects...",
        "\u001B[34m[INFO]\u001B[0m Building workflow-service 1.0.0-SNAPSHOT",
        "\u001B[34m[INFO]\u001B[0m --------------------------------",
        "\u001B[34m[INFO]\u001B[0m --- maven-compiler-plugin:3.11.0:compile ---",
        "\u001B[34m[INFO]\u001B[0m Compiling 42 source files to /target/classes",
        if (state == "Failed") "\u001B[31m[ERROR]\u001B[0m Tests run: 128, Failures: 3, Errors: 0, Skipped: 2"
        else "\u001B[34m[INFO]\u001B[0m Tests run: 128, Failures: 0, Errors: 0, Skipped: 0",
        if (state == "Successful") "\u001B[32m[INFO] BUILD SUCCESS\u001B[0m"
        else "\u001B[31m[ERROR] BUILD FAILURE\u001B[0m",
        "\u001B[34m[INFO]\u001B[0m Total time: 2:34 min",
    )

    fun shutdown() {
        progressionScope.cancel()
    }
}
```

- [ ] **Step 2: Write BambooDataFactory**

Create `mock-server/src/main/kotlin/com/workflow/orchestrator/mockserver/bamboo/BambooDataFactory.kt`:

```kotlin
package com.workflow.orchestrator.mockserver.bamboo

import java.util.concurrent.ConcurrentHashMap

object BambooDataFactory {

    fun createDefaultState(): BambooState {
        val state = BambooState()
        state.plans = mutableListOf(
            BambooPlan(
                key = "PROJ-BUILD",
                shortName = "Build",
                name = "Project - Artifact Build",
                branches = listOf(
                    BambooBranch("PROJ-BUILD0", "main"),
                    BambooBranch("PROJ-BUILD1", "feature/PROJ-101"),
                ),
                variables = mapOf("dockerTagsAsJson" to "{}", "skipTests" to "false"),
            ),
            BambooPlan(
                key = "PROJ-TEST",
                shortName = "Test",
                name = "Project - Integration Tests",
                branches = listOf(BambooBranch("PROJ-TEST0", "main")),
            ),
            BambooPlan(
                key = "PROJ-SONAR",
                shortName = "Sonar",
                name = "Project - SonarQube Analysis",
            ),
        )

        // Pre-populate with recent builds using divergent states
        val builds = ConcurrentHashMap<String, BambooBuildResult>()
        builds["PROJ-BUILD-99"] = BambooBuildResult(
            buildResultKey = "PROJ-BUILD-99",
            planKey = "PROJ-BUILD",
            buildNumber = 99,
            lifeCycleState = "Finished",
            state = "Successful",
            stages = listOf(
                BambooStage("Compile & Package", "Successful", "Finished"),
                BambooStage("Security Scan", "Successful", "Finished"),
                BambooStage("Integration Tests", "Successful", "Finished"),
            ),
            logEntries = listOf("[INFO] BUILD SUCCESS"),
        )
        builds["PROJ-TEST-50"] = BambooBuildResult(
            buildResultKey = "PROJ-TEST-50",
            planKey = "PROJ-TEST",
            buildNumber = 50,
            lifeCycleState = "Running",  // Divergent: "Running" not "InProgress"
            state = null,
        )
        builds["PROJ-SONAR-25"] = BambooBuildResult(
            buildResultKey = "PROJ-SONAR-25",
            planKey = "PROJ-SONAR",
            buildNumber = 25,
            lifeCycleState = "Finished",
            state = "PartiallySuccessful",  // Divergent: new state
            stages = listOf(
                BambooStage("Compile & Package", "Successful", "Finished"),
                BambooStage("Security Scan", "Failed", "Finished"),
            ),
        )
        state.builds = builds
        return state
    }

    fun createAllFailingState(): BambooState {
        val state = createDefaultState()
        state.builds.replaceAll { _, build ->
            build.copy(lifeCycleState = "Finished", state = "Failed")
        }
        return state
    }

    fun createBuildProgressionState(): BambooState {
        val state = createDefaultState()
        // Trigger 3 builds at different stages
        state.triggerBuild("PROJ-BUILD")   // Will be Queued
        state.triggerBuild("PROJ-TEST")    // Will be Queued
        state.triggerBuild("PROJ-SONAR")   // Will be Queued
        return state
    }

    /**
     * Happy-path: uses standard Bamboo lifecycle states that the plugin expects.
     */
    fun createHappyPathState(): BambooState {
        val state = BambooState()
        state.plans = mutableListOf(
            BambooPlan("PROJ-BUILD", "Build", "Project - Artifact Build"),
        )
        val builds = java.util.concurrent.ConcurrentHashMap<String, BambooBuildResult>()
        builds["PROJ-BUILD-99"] = BambooBuildResult(
            buildResultKey = "PROJ-BUILD-99", planKey = "PROJ-BUILD", buildNumber = 99,
            lifeCycleState = "InProgress",  // Standard — plugin expects this
            state = null,
        )
        builds["PROJ-BUILD-98"] = BambooBuildResult(
            buildResultKey = "PROJ-BUILD-98", planKey = "PROJ-BUILD", buildNumber = 98,
            lifeCycleState = "Finished", state = "Successful",
        )
        state.builds = builds
        return state
    }
}
```

- [ ] **Step 3: Write tests**

Create `mock-server/src/test/kotlin/com/workflow/orchestrator/mockserver/bamboo/BambooDataFactoryTest.kt`:

```kotlin
package com.workflow.orchestrator.mockserver.bamboo

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class BambooDataFactoryTest {

    @Test
    fun `default state has divergent lifecycle states`() {
        val state = BambooDataFactory.createDefaultState()
        val runningBuild = state.builds["PROJ-TEST-50"]
        assertNotNull(runningBuild)
        assertEquals("Running", runningBuild!!.lifeCycleState, "Should use 'Running' not 'InProgress'")
    }

    @Test
    fun `default state includes PartiallySuccessful build`() {
        val state = BambooDataFactory.createDefaultState()
        val sonarBuild = state.builds["PROJ-SONAR-25"]
        assertEquals("PartiallySuccessful", sonarBuild?.state)
    }

    @Test
    fun `default state has non-standard stage names`() {
        val state = BambooDataFactory.createDefaultState()
        val successBuild = state.builds["PROJ-BUILD-99"]!!
        val stageNames = successBuild.stages.map { it.name }
        assertTrue("Compile & Package" in stageNames)
        assertTrue("Security Scan" in stageNames)
        assertTrue("Integration Tests" in stageNames)
    }

    @Test
    fun `trigger build starts as Queued`() {
        val state = BambooDataFactory.createDefaultState()
        val build = state.triggerBuild("PROJ-BUILD")
        assertEquals("Queued", build.lifeCycleState)
        assertNull(build.state)
        state.shutdown()
    }

    @Test
    fun `cancel build changes state to Cancelled`() {
        val state = BambooDataFactory.createDefaultState()
        val build = state.triggerBuild("PROJ-BUILD")
        assertTrue(state.cancelBuild(build.buildResultKey))
        val cancelled = state.builds[build.buildResultKey]
        assertEquals("Cancelled", cancelled?.lifeCycleState)
        assertEquals("Cancelled", cancelled?.state)
        state.shutdown()
    }

    @Test
    fun `all-failing state has all builds failed`() {
        val state = BambooDataFactory.createAllFailingState()
        state.builds.values.forEach {
            assertEquals("Failed", it.state)
        }
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :mock-server:test`
Expected: All tests PASS

- [ ] **Step 5: Commit**

```bash
git add mock-server/src/
git commit -m "feat(mock-server): add Bamboo state model with build progression and divergent states"
```

---

### Task 6: Bamboo mock routes

**Files:**
- Create: `mock-server/src/main/kotlin/com/workflow/orchestrator/mockserver/bamboo/BambooMockRoutes.kt`
- Create: `mock-server/src/test/kotlin/com/workflow/orchestrator/mockserver/bamboo/BambooMockRoutesTest.kt`

- [ ] **Step 1: Write BambooMockRoutes**

Create `mock-server/src/main/kotlin/com/workflow/orchestrator/mockserver/bamboo/BambooMockRoutes.kt`:

```kotlin
package com.workflow.orchestrator.mockserver.bamboo

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

// Same provider pattern as jiraRoutes — see JiraMockRoutes.kt for explanation.
// Every handler must start with: val state = stateProvider()
fun Route.bambooRoutes(stateProvider: () -> BambooState) {

    // GET /rest/api/latest/currentUser
    get("/rest/api/latest/currentUser") {
        val state = stateProvider()
        call.respondText(
            buildJsonObject {
                put("name", state.currentUser)
                put("fullName", "Mock User")
                put("email", "mock.user@example.com")
            }.toString(),
            ContentType.Application.Json
        )
    }

    // GET /rest/api/latest/plan
    get("/rest/api/latest/plan") {
        call.respondText(
            buildJsonObject {
                putJsonObject("plans") {
                    put("size", state.plans.size)
                    putJsonArray("plan") {
                        state.plans.forEach { plan ->
                            addJsonObject {
                                put("key", plan.key)
                                put("shortName", plan.shortName)
                                put("name", plan.name)
                            }
                        }
                    }
                }
            }.toString(),
            ContentType.Application.Json
        )
    }

    // GET /rest/api/latest/search/plans
    get("/rest/api/latest/search/plans") {
        val searchTerm = call.request.queryParameters["searchTerm"] ?: ""
        val matched = state.plans.filter {
            it.name.contains(searchTerm, ignoreCase = true) ||
            it.key.contains(searchTerm, ignoreCase = true)
        }
        call.respondText(
            buildJsonObject {
                put("size", matched.size)
                putJsonArray("searchResults") {
                    matched.forEach { plan ->
                        addJsonObject {
                            putJsonObject("searchEntity") {
                                put("key", plan.key)
                                put("planName", plan.name)
                            }
                        }
                    }
                }
            }.toString(),
            ContentType.Application.Json
        )
    }

    // GET /rest/api/latest/plan/{key}/branch
    get("/rest/api/latest/plan/{key}/branch") {
        val key = call.parameters["key"] ?: ""
        val plan = state.plans.find { it.key == key }
        val branches = plan?.branches ?: emptyList()
        call.respondText(
            buildJsonObject {
                putJsonObject("branches") {
                    put("size", branches.size)
                    putJsonArray("branch") {
                        branches.forEach { b ->
                            addJsonObject {
                                put("key", b.key)
                                put("shortName", b.shortName)
                            }
                        }
                    }
                }
            }.toString(),
            ContentType.Application.Json
        )
    }

    // GET /rest/api/latest/plan/{key}/variable
    get("/rest/api/latest/plan/{key}/variable") {
        val key = call.parameters["key"] ?: ""
        val plan = state.plans.find { it.key == key }
        val vars = plan?.variables ?: emptyMap()
        call.respondText(
            buildJsonObject {
                putJsonArray("variables") {
                    vars.forEach { (name, value) ->
                        addJsonObject {
                            put("name", name)
                            put("value", value)
                        }
                    }
                }
            }.toString(),
            ContentType.Application.Json
        )
    }

    // GET /rest/api/latest/result/{planKey}/latest
    get("/rest/api/latest/result/{planKey}/latest") {
        val planKey = call.parameters["planKey"] ?: ""
        val latest = state.builds.values
            .filter { it.planKey == planKey }
            .maxByOrNull { it.buildNumber }
        if (latest == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        call.respondText(buildResultToJson(latest).toString(), ContentType.Application.Json)
    }

    // GET /rest/api/latest/result/{buildKey} — specific build, supports ?expand=logEntries,variables
    get("/rest/api/latest/result/{buildKey}") {
        val buildKey = call.parameters["buildKey"] ?: ""
        // Also handle /{planKey} for listing builds
        val build = state.builds[buildKey]
        if (build != null) {
            val expand = call.request.queryParameters["expand"] ?: ""
            call.respondText(
                buildResultToJson(build, expand.contains("logEntries"), expand.contains("variables")).toString(),
                ContentType.Application.Json
            )
            return@get
        }
        // Treat as plan key — list builds for that plan
        val planBuilds = state.builds.values
            .filter { it.planKey == buildKey }
            .sortedByDescending { it.buildNumber }
        call.respondText(
            buildJsonObject {
                putJsonObject("results") {
                    put("size", planBuilds.size)
                    putJsonArray("result") {
                        planBuilds.forEach { b ->
                            add(buildResultToJson(b))
                        }
                    }
                }
            }.toString(),
            ContentType.Application.Json
        )
    }

    // POST /rest/api/latest/queue/{planKey}
    post("/rest/api/latest/queue/{planKey}") {
        val planKey = call.parameters["planKey"] ?: ""
        val plan = state.plans.find { it.key == planKey }
        if (plan == null) {
            call.respond(HttpStatusCode.NotFound, """{"message":"Plan not found"}""")
            return@post
        }
        val bodyText = call.receiveText()
        val variables = if (bodyText.isNotBlank()) {
            try {
                val body = Json.parseToJsonElement(bodyText).jsonObject
                body.entries.associate { it.key to it.value.jsonPrimitive.content }
            } catch (_: Exception) { emptyMap() }
        } else emptyMap()

        val build = state.triggerBuild(planKey, variables)
        call.respondText(
            buildResultToJson(build).toString(),
            ContentType.Application.Json,
            HttpStatusCode.OK
        )
    }

    // DELETE /rest/api/latest/queue/{resultKey}
    delete("/rest/api/latest/queue/{resultKey}") {
        val resultKey = call.parameters["resultKey"] ?: ""
        if (state.cancelBuild(resultKey)) {
            call.respond(HttpStatusCode.NoContent)
        } else {
            call.respond(HttpStatusCode.NotFound)
        }
    }
}

private fun buildResultToJson(
    build: BambooBuildResult,
    includeLog: Boolean = false,
    includeVars: Boolean = false,
): JsonObject = buildJsonObject {
    put("buildResultKey", build.buildResultKey)
    put("key", build.buildResultKey)
    putJsonObject("plan") {
        put("key", build.planKey)
    }
    put("buildNumber", build.buildNumber)
    put("lifeCycleState", build.lifeCycleState)
    build.state?.let { put("state", it) }
    if (build.stages.isNotEmpty()) {
        putJsonObject("stages") {
            put("size", build.stages.size)
            putJsonArray("stage") {
                build.stages.forEach { stage ->
                    addJsonObject {
                        put("name", stage.name)
                        put("state", stage.state)
                        put("lifeCycleState", stage.lifeCycleState)
                    }
                }
            }
        }
    }
    if (includeLog && build.logEntries.isNotEmpty()) {
        putJsonArray("logEntries") {
            build.logEntries.forEach { add(JsonPrimitive(it)) }
        }
    }
    if (includeVars && build.variables.isNotEmpty()) {
        putJsonObject("variables") {
            build.variables.forEach { (k, v) -> put(k, v) }
        }
    }
}
```

- [ ] **Step 2: Write route tests**

Create `mock-server/src/test/kotlin/com/workflow/orchestrator/mockserver/bamboo/BambooMockRoutesTest.kt`:

```kotlin
package com.workflow.orchestrator.mockserver.bamboo

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class BambooMockRoutesTest {

    private fun ApplicationTestBuilder.setupBamboo(state: BambooState = BambooDataFactory.createDefaultState()) {
        application {
            install(ContentNegotiation) { json() }
            routing { bambooRoutes { state } }
        }
    }

    @Test
    fun `GET currentUser returns mock user`() = testApplication {
        setupBamboo()
        val response = client.get("/rest/api/latest/currentUser")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `GET result returns divergent lifeCycleState`() = testApplication {
        setupBamboo()
        val response = client.get("/rest/api/latest/result/PROJ-TEST-50")
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Running", body["lifeCycleState"]?.jsonPrimitive?.content,
            "Should return 'Running' not 'InProgress'")
    }

    @Test
    fun `GET result with expand=logEntries includes log`() = testApplication {
        setupBamboo()
        val response = client.get("/rest/api/latest/result/PROJ-BUILD-99?expand=logEntries")
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertNotNull(body["logEntries"])
    }

    @Test
    fun `POST queue triggers build and returns Queued`() = testApplication {
        val state = BambooDataFactory.createDefaultState()
        setupBamboo(state)
        val response = client.post("/rest/api/latest/queue/PROJ-BUILD")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Queued", body["lifeCycleState"]?.jsonPrimitive?.content)
        state.shutdown()
    }

    @Test
    fun `DELETE queue cancels build`() = testApplication {
        val state = BambooDataFactory.createDefaultState()
        setupBamboo(state)
        val build = state.triggerBuild("PROJ-BUILD")
        val response = client.delete("/rest/api/latest/queue/${build.buildResultKey}")
        assertEquals(HttpStatusCode.NoContent, response.status)
        state.shutdown()
    }

    @Test
    fun `GET plan lists all plans`() = testApplication {
        setupBamboo()
        val response = client.get("/rest/api/latest/plan")
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val plans = body["plans"]?.jsonObject?.get("plan")?.jsonArray
        assertEquals(3, plans?.size)
    }
}
```

- [ ] **Step 3: Run tests**

Run: `./gradlew :mock-server:test`
Expected: All tests PASS

- [ ] **Step 4: Commit**

```bash
git add mock-server/src/
git commit -m "feat(mock-server): add Bamboo mock routes with build progression and divergent states"
```

---

## Chunk 4: SonarQube Mock — State, Data Factory, Routes

### Task 7: SonarQube state, data factory, and routes

**Files:**
- Create: `mock-server/src/main/kotlin/com/workflow/orchestrator/mockserver/sonar/SonarState.kt`
- Create: `mock-server/src/main/kotlin/com/workflow/orchestrator/mockserver/sonar/SonarDataFactory.kt`
- Create: `mock-server/src/main/kotlin/com/workflow/orchestrator/mockserver/sonar/SonarMockRoutes.kt`
- Create: `mock-server/src/test/kotlin/com/workflow/orchestrator/mockserver/sonar/SonarMockRoutesTest.kt`

- [ ] **Step 1: Write SonarState**

Create `mock-server/src/main/kotlin/com/workflow/orchestrator/mockserver/sonar/SonarState.kt`:

```kotlin
package com.workflow.orchestrator.mockserver.sonar

import kotlinx.serialization.Serializable

@Serializable
data class SonarProject(
    val key: String,
    val name: String,
)

@Serializable
data class SonarIssue(
    val key: String,
    val rule: String,
    val severity: String,
    val type: String,
    val message: String,
    val component: String,
    val line: Int?,
    val status: String = "OPEN",
)

@Serializable
data class SonarQualityGate(
    val status: String, // "OK", "ERROR", "WARN" — divergent!
    val conditions: List<SonarCondition>,
)

@Serializable
data class SonarCondition(
    val status: String,
    val metricKey: String,
    val comparator: String,
    val errorThreshold: String? = null,
    val warningThreshold: String? = null,
    val actualValue: String,
)

@Serializable
data class SonarMeasure(
    val component: String,
    val metricKey: String,
    val value: String,
)

@Serializable
data class SonarSourceLine(
    val line: Int,
    val code: String,
    val covered: Boolean? = null,  // null = not coverable, true = covered, false = uncovered
)

class SonarState {
    var authValid: Boolean = true
    var projects: MutableList<SonarProject> = mutableListOf()
    var issues: MutableList<SonarIssue> = mutableListOf()
    var qualityGate: SonarQualityGate? = null
    var measures: MutableList<SonarMeasure> = mutableListOf()
    var sourceLines: Map<String, List<SonarSourceLine>> = emptyMap()
}
```

- [ ] **Step 2: Write SonarDataFactory**

Create `mock-server/src/main/kotlin/com/workflow/orchestrator/mockserver/sonar/SonarDataFactory.kt`:

```kotlin
package com.workflow.orchestrator.mockserver.sonar

object SonarDataFactory {

    fun createDefaultState(): SonarState {
        val state = SonarState()
        state.projects = mutableListOf(SonarProject("com.example:service", "Example Service"))

        // Quality gate with divergent "WARN" status
        state.qualityGate = SonarQualityGate(
            status = "WARN",
            conditions = listOf(
                SonarCondition("OK", "coverage", "LT", errorThreshold = "80", actualValue = "82.3"),
                SonarCondition("WARN", "security_rating", "GT", warningThreshold = "1", actualValue = "3"),
                SonarCondition("ERROR", "new_coverage", "LT", errorThreshold = "80", actualValue = "45.0"),
            ),
        )

        // Metrics — deliberately omit uncovered_conditions, add extras
        state.measures = mutableListOf(
            SonarMeasure("com.example:service", "coverage", "82.3"),
            SonarMeasure("com.example:service", "line_coverage", "85.1"),
            SonarMeasure("com.example:service", "branch_coverage", "71.2"),
            SonarMeasure("com.example:service", "uncovered_lines", "42"),
            // uncovered_conditions deliberately OMITTED
            SonarMeasure("com.example:service", "security_rating", "3.0"),  // extra
            SonarMeasure("com.example:service", "reliability_rating", "1.0"),  // extra
        )

        // Issues with divergent severities and types
        state.issues = mutableListOf(
            SonarIssue("issue-1", "java:S1135", "MAJOR", "CODE_SMELL", "Complete the task associated with this TODO comment.", "com.example:service:src/main/java/Service.java", 42),
            SonarIssue("issue-2", "java:S2259", "BLOCKER", "BUG", "A NullPointerException could be thrown.", "com.example:service:src/main/java/Service.java", 87),
            SonarIssue("issue-3", "java:S5122", "CRITICAL", "VULNERABILITY", "Make sure that enabling CORS is safe here.", "com.example:service:src/main/java/Controller.java", 15),
            SonarIssue("issue-4", "java:S4790", "CRITICAL_SECURITY", "SECURITY_HOTSPOT", "Use a stronger hashing algorithm.", "com.example:service:src/main/java/Crypto.java", 33),  // divergent severity
            SonarIssue("issue-5", "custom:AUDIT1", "MAJOR", "SECURITY_AUDIT", "Manual security review required.", "com.example:service:src/main/java/Auth.java", 12),  // divergent type
            SonarIssue("issue-6", "java:S1192", "MINOR", "CODE_SMELL", "Define a constant instead of duplicating this literal.", "com.example:service:src/main/java/Config.java", 28),
            SonarIssue("issue-7", "java:S106", "INFO", "CODE_SMELL", "Replace this use of System.out or System.err.", "com.example:service:src/main/java/Debug.java", 5),
            SonarIssue("issue-8", "java:S2095", "BLOCKER", "BUG", "Use try-with-resources for this AutoCloseable.", "com.example:service:src/main/java/Dao.java", 91),
            SonarIssue("issue-9", "java:S3776", "CRITICAL", "CODE_SMELL", "Refactor this method to reduce Cognitive Complexity.", "com.example:service:src/main/java/Processor.java", 114),
            SonarIssue("issue-10", "java:S2187", "MAJOR", "CODE_SMELL", "Add some tests to this class.", "com.example:service:src/test/java/ProcessorTest.java", null),
            SonarIssue("issue-11", "java:S5131", "BLOCKER", "VULNERABILITY", "This value is tainted and could lead to XSS.", "com.example:service:src/main/java/TemplateEngine.java", 67),
            SonarIssue("issue-12", "java:S4830", "CRITICAL_SECURITY", "VULNERABILITY", "Disable server certificate validation here.", "com.example:service:src/main/java/HttpClient.java", 44),  // divergent severity again
        )

        return state
    }

    fun createQualityGateWarnState(): SonarState {
        return createDefaultState() // Default already uses WARN
    }

    fun createMetricsMissingState(): SonarState {
        val state = createDefaultState()
        state.measures = mutableListOf(
            SonarMeasure("com.example:service", "coverage", "62.1"),
        )
        return state
    }

    fun createAuthInvalidState(): SonarState {
        val state = createDefaultState()
        state.authValid = false
        return state
    }

    /**
     * Happy-path: uses standard SonarQube values that the plugin expects.
     */
    fun createHappyPathState(): SonarState {
        val state = SonarState()
        state.projects = mutableListOf(SonarProject("com.example:service", "Example Service"))
        state.qualityGate = SonarQualityGate(
            status = "OK",
            conditions = listOf(
                SonarCondition("OK", "coverage", "LT", errorThreshold = "80", actualValue = "92.0"),
            ),
        )
        state.measures = mutableListOf(
            SonarMeasure("com.example:service", "coverage", "92.0"),
            SonarMeasure("com.example:service", "line_coverage", "94.0"),
            SonarMeasure("com.example:service", "branch_coverage", "88.0"),
            SonarMeasure("com.example:service", "uncovered_lines", "8"),
            SonarMeasure("com.example:service", "uncovered_conditions", "3"),
        )
        state.issues = mutableListOf(
            SonarIssue("issue-1", "java:S1135", "MAJOR", "CODE_SMELL", "Complete the task.", "com.example:service:src/main/java/Service.java", 42),
            SonarIssue("issue-2", "java:S2259", "BLOCKER", "BUG", "NPE possible.", "com.example:service:src/main/java/Service.java", 87),
        )
        return state
    }
}
```

- [ ] **Step 3: Write SonarMockRoutes**

Create `mock-server/src/main/kotlin/com/workflow/orchestrator/mockserver/sonar/SonarMockRoutes.kt`:

```kotlin
package com.workflow.orchestrator.mockserver.sonar

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

// Same provider pattern as jiraRoutes — see JiraMockRoutes.kt for explanation.
// Every handler must start with: val state = stateProvider()
fun Route.sonarRoutes(stateProvider: () -> SonarState) {

    // GET /api/authentication/validate
    get("/api/authentication/validate") {
        val state = stateProvider()
        call.respondText(
            buildJsonObject { put("valid", state.authValid) }.toString(),
            ContentType.Application.Json
        )
    }

    // GET /api/projects/search
    get("/api/projects/search") {
        val query = call.request.queryParameters["q"] ?: ""
        val matched = state.projects.filter {
            it.name.contains(query, ignoreCase = true) || it.key.contains(query, ignoreCase = true)
        }
        call.respondText(
            buildJsonObject {
                putJsonObject("paging") {
                    put("pageIndex", 1)
                    put("pageSize", 100)
                    put("total", matched.size)
                }
                putJsonArray("components") {
                    matched.forEach { p ->
                        addJsonObject {
                            put("key", p.key)
                            put("name", p.name)
                            put("qualifier", "TRK")
                        }
                    }
                }
            }.toString(),
            ContentType.Application.Json
        )
    }

    // GET /api/measures/component_tree
    get("/api/measures/component_tree") {
        val componentKey = call.request.queryParameters["component"] ?: ""
        val metricKeys = call.request.queryParameters["metricKeys"]?.split(",") ?: emptyList()
        val matched = state.measures.filter { m ->
            (componentKey.isEmpty() || m.component.startsWith(componentKey)) &&
            (metricKeys.isEmpty() || m.metricKey in metricKeys)
        }
        call.respondText(
            buildJsonObject {
                putJsonObject("baseComponent") {
                    put("key", componentKey)
                    putJsonArray("measures") {
                        matched.forEach { m ->
                            addJsonObject {
                                put("metric", m.metricKey)
                                put("value", m.value)
                            }
                        }
                    }
                }
                putJsonArray("components") {} // empty for simplicity
            }.toString(),
            ContentType.Application.Json
        )
    }

    // GET /api/sources/lines
    get("/api/sources/lines") {
        val componentKey = call.request.queryParameters["key"] ?: ""
        val lines = state.sourceLines[componentKey] ?: emptyList()
        call.respondText(
            buildJsonObject {
                putJsonArray("sources") {
                    lines.forEach { line ->
                        addJsonObject {
                            put("line", line.line)
                            put("code", line.code)
                            line.covered?.let { put("lineHits", if (it) 1 else 0) }
                        }
                    }
                }
            }.toString(),
            ContentType.Application.Json
        )
    }

    // GET /api/issues/search
    get("/api/issues/search") {
        val componentKeys = call.request.queryParameters["componentKeys"]
        val resolved = call.request.queryParameters["resolved"]
        val issues = state.issues.filter { issue ->
            (componentKeys == null || issue.component.startsWith(componentKeys)) &&
            (resolved == null || (resolved == "false" && issue.status == "OPEN"))
        }
        call.respondText(
            buildJsonObject {
                put("total", issues.size)
                put("p", 1)
                put("ps", 100)
                putJsonArray("issues") {
                    issues.forEach { issue ->
                        addJsonObject {
                            put("key", issue.key)
                            put("rule", issue.rule)
                            put("severity", issue.severity)
                            put("type", issue.type)
                            put("message", issue.message)
                            put("component", issue.component)
                            issue.line?.let { put("line", it) }
                            put("status", issue.status)
                        }
                    }
                }
            }.toString(),
            ContentType.Application.Json
        )
    }

    // GET /api/qualitygates/project_status
    get("/api/qualitygates/project_status") {
        val gate = state.qualityGate
        if (gate == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        call.respondText(
            buildJsonObject {
                putJsonObject("projectStatus") {
                    put("status", gate.status)
                    putJsonArray("conditions") {
                        gate.conditions.forEach { cond ->
                            addJsonObject {
                                put("status", cond.status)
                                put("metricKey", cond.metricKey)
                                put("comparator", cond.comparator)
                                cond.errorThreshold?.let { put("errorThreshold", it) }
                                cond.warningThreshold?.let { put("warningThreshold", it) }
                                put("actualValue", cond.actualValue)
                            }
                        }
                    }
                }
            }.toString(),
            ContentType.Application.Json
        )
    }
}
```

- [ ] **Step 4: Write route tests**

Create `mock-server/src/test/kotlin/com/workflow/orchestrator/mockserver/sonar/SonarMockRoutesTest.kt`:

```kotlin
package com.workflow.orchestrator.mockserver.sonar

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class SonarMockRoutesTest {

    private fun ApplicationTestBuilder.setupSonar(state: SonarState = SonarDataFactory.createDefaultState()) {
        application {
            install(ContentNegotiation) { json() }
            routing { sonarRoutes { state } }
        }
    }

    @Test
    fun `GET auth validate returns valid true`() = testApplication {
        setupSonar()
        val response = client.get("/api/authentication/validate")
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["valid"]?.jsonPrimitive?.boolean == true)
    }

    @Test
    fun `auth-invalid scenario returns valid false`() = testApplication {
        setupSonar(SonarDataFactory.createAuthInvalidState())
        val response = client.get("/api/authentication/validate")
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertFalse(body["valid"]?.jsonPrimitive?.boolean ?: true)
    }

    @Test
    fun `GET quality gate returns divergent WARN status`() = testApplication {
        setupSonar()
        val response = client.get("/api/qualitygates/project_status?projectKey=com.example:service")
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val status = body["projectStatus"]?.jsonObject?.get("status")?.jsonPrimitive?.content
        assertEquals("WARN", status, "Should return 'WARN' not just 'OK' or 'ERROR'")
    }

    @Test
    fun `GET issues returns divergent severity CRITICAL_SECURITY`() = testApplication {
        setupSonar()
        val response = client.get("/api/issues/search?resolved=false")
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val severities = body["issues"]?.jsonArray?.map {
            it.jsonObject["severity"]?.jsonPrimitive?.content
        } ?: emptyList()
        assertTrue("CRITICAL_SECURITY" in severities, "Should include divergent severity")
    }

    @Test
    fun `GET issues returns divergent type SECURITY_AUDIT`() = testApplication {
        setupSonar()
        val response = client.get("/api/issues/search?resolved=false")
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val types = body["issues"]?.jsonArray?.map {
            it.jsonObject["type"]?.jsonPrimitive?.content
        } ?: emptyList()
        assertTrue("SECURITY_AUDIT" in types, "Should include divergent issue type")
    }

    @Test
    fun `GET measures omits uncovered_conditions`() = testApplication {
        setupSonar()
        val response = client.get("/api/measures/component_tree?component=com.example:service&metricKeys=coverage,uncovered_conditions")
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val metrics = body["baseComponent"]?.jsonObject?.get("measures")?.jsonArray?.map {
            it.jsonObject["metric"]?.jsonPrimitive?.content
        } ?: emptyList()
        assertTrue("coverage" in metrics)
        assertFalse("uncovered_conditions" in metrics, "Should NOT include uncovered_conditions")
    }

    @Test
    fun `metrics-missing state returns only coverage`() = testApplication {
        setupSonar(SonarDataFactory.createMetricsMissingState())
        val response = client.get("/api/measures/component_tree?component=com.example:service")
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val metrics = body["baseComponent"]?.jsonObject?.get("measures")?.jsonArray
        assertEquals(1, metrics?.size)
    }
}
```

- [ ] **Step 5: Run tests**

Run: `./gradlew :mock-server:test`
Expected: All tests PASS

- [ ] **Step 6: Commit**

```bash
git add mock-server/src/
git commit -m "feat(mock-server): add SonarQube mock with divergent severities, types, and WARN gate"
```

---

## Chunk 5: Chaos Middleware, Admin API, Auth, and Server Wiring

### Task 8: Chaos middleware

**Files:**
- Create: `mock-server/src/main/kotlin/com/workflow/orchestrator/mockserver/chaos/ChaosMiddleware.kt`
- Create: `mock-server/src/test/kotlin/com/workflow/orchestrator/mockserver/chaos/ChaosMiddlewareTest.kt`

- [ ] **Step 1: Write ChaosMiddleware as a Ktor plugin**

Create `mock-server/src/main/kotlin/com/workflow/orchestrator/mockserver/chaos/ChaosMiddleware.kt`:

```kotlin
package com.workflow.orchestrator.mockserver.chaos

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.util.*
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Shared mutable config — passed by reference so admin routes can toggle at runtime.
 * The ChaosPlugin reads from this on every request (not a copy at install time).
 */
class ChaosConfig {
    var enabled: Boolean = false
    var rate: Double = 0.2 // 20% of requests fail
}

/** Attribute key to store the shared ChaosConfig reference on the application. */
val ChaosConfigKey = AttributeKey<ChaosConfig>("ChaosConfig")

val ChaosPlugin = createApplicationPlugin(name = "ChaosPlugin") {
    // Read the shared config from application attributes on every request,
    // NOT from a copy made at install time.
    onCall { call ->
        val config = call.application.attributes.getOrNull(ChaosConfigKey) ?: return@onCall

        // Never affect admin endpoints
        if (call.request.local.uri.startsWith("/__admin")) return@onCall
        if (!config.enabled) return@onCall
        if (Random.nextDouble() > config.rate) return@onCall

        val failureType = selectFailureType()
        when (failureType) {
            ChaosFailure.SLOW_RESPONSE -> {
                delay(Random.nextLong(10_000, 15_000))
                // Let the real handler proceed after delay — do NOT respond here
                return@onCall
            }
            ChaosFailure.TIMEOUT -> {
                delay(35_000) // Exceeds plugin's 30s readTimeout
                call.respondText("Timed out", ContentType.Text.Plain, HttpStatusCode.GatewayTimeout)
            }
            ChaosFailure.MALFORMED_JSON -> {
                call.respondText("""{"partial": "data", "broken":""", ContentType.Application.Json, HttpStatusCode.OK)
            }
            ChaosFailure.HTTP_429 -> {
                call.response.header("Retry-After", "5")
                call.respondText("Too Many Requests", ContentType.Text.Plain, HttpStatusCode.TooManyRequests)
            }
            ChaosFailure.HTTP_500 -> {
                call.respondText("""{"error": "Internal Server Error"}""", ContentType.Application.Json, HttpStatusCode.InternalServerError)
            }
            ChaosFailure.HTTP_503 -> {
                call.respondText("Service Unavailable", ContentType.Text.Plain, HttpStatusCode.ServiceUnavailable)
            }
            ChaosFailure.EMPTY_BODY -> {
                call.respondText("", ContentType.Application.Json, HttpStatusCode.OK)
            }
            ChaosFailure.WRONG_CONTENT_TYPE -> {
                call.respondText("<html>Session expired. Please log in again.</html>", ContentType.Text.Html, HttpStatusCode.OK)
            }
        }
        // For all non-SLOW_RESPONSE types, we've already sent a response.
        // Finish the call to prevent the route handler from also responding.
        call.response.pipeline.finish()
    }
}

enum class ChaosFailure(val weight: Int) {
    MALFORMED_JSON(20),
    SLOW_RESPONSE(15),
    TIMEOUT(5),
    HTTP_429(10),
    HTTP_500(15),
    HTTP_503(15),
    EMPTY_BODY(10),
    WRONG_CONTENT_TYPE(10),
}

private fun selectFailureType(): ChaosFailure {
    val totalWeight = ChaosFailure.entries.sumOf { it.weight }
    var random = Random.nextInt(totalWeight)
    for (failure in ChaosFailure.entries) {
        random -= failure.weight
        if (random < 0) return failure
    }
    return ChaosFailure.HTTP_500 // fallback
}
```

- [ ] **Step 2: Write chaos test**

Create `mock-server/src/test/kotlin/com/workflow/orchestrator/mockserver/chaos/ChaosMiddlewareTest.kt`:

```kotlin
package com.workflow.orchestrator.mockserver.chaos

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ChaosMiddlewareTest {

    private fun ApplicationTestBuilder.setupChaos(enabled: Boolean, rate: Double = 0.2) {
        application {
            val config = ChaosConfig().apply {
                this.enabled = enabled
                this.rate = rate
            }
            attributes.put(ChaosConfigKey, config)
            install(ChaosPlugin)
            routing {
                get("/test") { call.respondText("OK") }
                get("/__admin/test") { call.respondText("admin OK") }
            }
        }
    }

    @Test
    fun `chaos disabled does not affect requests`() = testApplication {
        setupChaos(enabled = false)
        repeat(20) {
            val response = client.get("/test")
            assertEquals("OK", response.bodyAsText())
        }
    }

    @Test
    fun `chaos with rate 1 affects all requests`() = testApplication {
        setupChaos(enabled = true, rate = 1.0)
        val response = client.get("/test")
        // Should not return normal "OK" — could be any chaos failure
        assertNotEquals("OK", response.bodyAsText())
    }

    @Test
    fun `admin endpoints are never affected by chaos`() = testApplication {
        setupChaos(enabled = true, rate = 1.0)
        val response = client.get("/__admin/test")
        assertEquals("admin OK", response.bodyAsText())
    }

    @Test
    fun `admin can toggle chaos at runtime via shared config`() = testApplication {
        val config = ChaosConfig().apply { enabled = false }
        application {
            attributes.put(ChaosConfigKey, config)
            install(ChaosPlugin)
            routing { get("/test") { call.respondText("OK") } }
        }
        // Initially disabled — should work normally
        assertEquals("OK", client.get("/test").bodyAsText())
        // Enable via shared config (simulates admin toggle)
        config.enabled = true
        config.rate = 1.0
        // Now should be affected
        assertNotEquals("OK", client.get("/test").bodyAsText())
    }
}
```

- [ ] **Step 3: Run tests**

Run: `./gradlew :mock-server:test`
Expected: All PASS

- [ ] **Step 4: Commit**

```bash
git add mock-server/src/
git commit -m "feat(mock-server): add chaos middleware with weighted failure types"
```

---

### Task 9: Admin routes and auth middleware

**Files:**
- Create: `mock-server/src/main/kotlin/com/workflow/orchestrator/mockserver/admin/AdminRoutes.kt`
- Create: `mock-server/src/main/kotlin/com/workflow/orchestrator/mockserver/admin/AuthMiddleware.kt`

- [ ] **Step 1: Write AuthMiddleware**

Create `mock-server/src/main/kotlin/com/workflow/orchestrator/mockserver/admin/AuthMiddleware.kt`:

```kotlin
package com.workflow.orchestrator.mockserver.admin

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*

val AuthPlugin = createApplicationPlugin(name = "AuthPlugin") {
    onCall { call ->
        // Skip admin and health endpoints
        val path = call.request.local.uri
        if (path.startsWith("/__admin") || path == "/health") return@onCall

        val authHeader = call.request.headers["Authorization"]
        if (authHeader.isNullOrBlank()) {
            call.respondText(
                """{"message":"Authentication required"}""",
                ContentType.Application.Json,
                HttpStatusCode.Unauthorized
            )
        }
    }
}
```

- [ ] **Step 2: Write AdminRoutes**

Create `mock-server/src/main/kotlin/com/workflow/orchestrator/mockserver/admin/AdminRoutes.kt`:

```kotlin
package com.workflow.orchestrator.mockserver.admin

import com.workflow.orchestrator.mockserver.chaos.ChaosConfig
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

data class RequestLog(
    val method: String,
    val path: String,
    val timestamp: Long,
    val responseCode: Int,
)

class AdminState {
    val requestLog = ArrayDeque<RequestLog>(50)

    fun logRequest(method: String, path: String, responseCode: Int) {
        if (path.startsWith("/__admin")) return
        synchronized(requestLog) {
            if (requestLog.size >= 50) requestLog.removeFirst()
            requestLog.addLast(RequestLog(method, path, System.currentTimeMillis(), responseCode))
        }
    }
}

fun Route.adminRoutes(
    serviceName: String,
    chaosConfig: ChaosConfig,
    adminState: AdminState,
    getStateJson: () -> JsonObject,
    resetState: () -> Unit,
    loadScenario: (String) -> Boolean,
    availableScenarios: List<String>,
) {
    route("/__admin") {
        // GET /__admin/state
        get("/state") {
            call.respondText(getStateJson().toString(), ContentType.Application.Json)
        }

        // POST /__admin/reset
        post("/reset") {
            resetState()
            call.respondText("""{"message":"$serviceName state reset"}""", ContentType.Application.Json)
        }

        // POST /__admin/chaos?enabled=true&rate=0.3
        post("/chaos") {
            val enabled = call.request.queryParameters["enabled"]
            val rate = call.request.queryParameters["rate"]

            if (enabled != null) {
                chaosConfig.enabled = enabled.toBoolean()
            }
            if (rate != null) {
                chaosConfig.rate = rate.toDouble().coerceIn(0.0, 1.0)
            }

            call.respondText(
                buildJsonObject {
                    put("enabled", chaosConfig.enabled)
                    put("rate", chaosConfig.rate)
                }.toString(),
                ContentType.Application.Json
            )
        }

        // POST /__admin/scenario/{name}
        post("/scenario/{name}") {
            val name = call.parameters["name"] ?: ""
            if (loadScenario(name)) {
                call.respondText("""{"message":"Scenario '$name' loaded for $serviceName"}""", ContentType.Application.Json)
            } else {
                call.respondText(
                    """{"error":"Unknown scenario: $name"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound
                )
            }
        }

        // GET /__admin/scenarios
        get("/scenarios") {
            call.respondText(
                buildJsonObject {
                    putJsonArray("scenarios") {
                        availableScenarios.forEach { add(JsonPrimitive(it)) }
                    }
                }.toString(),
                ContentType.Application.Json
            )
        }

        // GET /__admin/requests
        get("/requests") {
            call.respondText(
                buildJsonObject {
                    putJsonArray("requests") {
                        synchronized(adminState.requestLog) {
                            adminState.requestLog.forEach { req ->
                                addJsonObject {
                                    put("method", req.method)
                                    put("path", req.path)
                                    put("timestamp", req.timestamp)
                                    put("responseCode", req.responseCode)
                                }
                            }
                        }
                    }
                }.toString(),
                ContentType.Application.Json
            )
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add mock-server/src/
git commit -m "feat(mock-server): add admin routes and auth middleware"
```

---

### Task 10: Wire everything into MockServerMain

**Files:**
- Modify: `mock-server/src/main/kotlin/com/workflow/orchestrator/mockserver/MockServerMain.kt`
- Create: `mock-server/src/test/kotlin/com/workflow/orchestrator/mockserver/MockServerMainTest.kt`

- [ ] **Step 1: Rewrite MockServerMain to wire all components**

Overwrite `MockServerMain.kt`.

**Key fixes from plan review:**
- Use `StateHolder<T>` wrapper so routes always dereference current state (fixes scenario switching)
- Pass `ChaosConfig` via application attributes (fixes admin chaos toggle)
- Register `ApplicationStopped` event to shut down `BambooState` coroutine scope
- All route lambdas read from `holder.state`, so replacing `holder.state = ...` is visible to routes

```kotlin
package com.workflow.orchestrator.mockserver

import com.workflow.orchestrator.mockserver.admin.*
import com.workflow.orchestrator.mockserver.bamboo.*
import com.workflow.orchestrator.mockserver.chaos.*
import com.workflow.orchestrator.mockserver.config.MockConfig
import com.workflow.orchestrator.mockserver.jira.*
import com.workflow.orchestrator.mockserver.sonar.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/**
 * Mutable holder so routes always see the current state even after scenario switching.
 * Routes call holder.state on each request, not a captured snapshot.
 */
class StateHolder<T>(var state: T)

private val JIRA_SCENARIOS = listOf("default", "happy-path", "empty-sprint", "large-sprint", "no-active-sprint", "transition-blocked")
private val BAMBOO_SCENARIOS = listOf("default", "happy-path", "all-builds-failing", "build-progression")
private val SONAR_SCENARIOS = listOf("default", "happy-path", "quality-gate-warn", "metrics-missing", "auth-invalid")

fun main() {
    val config = MockConfig()

    val jiraHolder = StateHolder(JiraDataFactory.createDefaultState())
    val bambooHolder = StateHolder(BambooDataFactory.createDefaultState())
    val sonarHolder = StateHolder(SonarDataFactory.createDefaultState())

    val jiraChaos = ChaosConfig()
    val bambooChaos = ChaosConfig()
    val sonarChaos = ChaosConfig()

    val jiraAdmin = AdminState()
    val bambooAdmin = AdminState()
    val sonarAdmin = AdminState()

    printBanner(config)

    runBlocking {
        val jobs = listOf(
            launch {
                startMockServer("Jira", config.jiraPort, jiraChaos, jiraAdmin,
                    routeSetup = { routing { jiraRoutes { jiraHolder.state } } },
                    getState = { buildJsonObject { put("issueCount", jiraHolder.state.issues.size); put("sprintCount", jiraHolder.state.sprints.size) } },
                    reset = { jiraHolder.state = JiraDataFactory.createDefaultState() },
                    loadScenario = { name ->
                        when (name) {
                            "default" -> { jiraHolder.state = JiraDataFactory.createDefaultState(); true }
                            "empty-sprint" -> { jiraHolder.state = JiraDataFactory.createEmptySprintState(); true }
                            "large-sprint" -> { jiraHolder.state = JiraDataFactory.createLargeSprintState(); true }
                            "no-active-sprint" -> { jiraHolder.state = JiraDataFactory.createNoActiveSprintState(); true }
                            "transition-blocked" -> { jiraHolder.state = JiraDataFactory.createTransitionBlockedState(); true }
                            "happy-path" -> { jiraHolder.state = JiraDataFactory.createHappyPathState(); true }
                            else -> false
                        }
                    },
                    scenarios = JIRA_SCENARIOS,
                )
            },
            launch {
                startMockServer("Bamboo", config.bambooPort, bambooChaos, bambooAdmin,
                    routeSetup = { routing { bambooRoutes { bambooHolder.state } } },
                    getState = { buildJsonObject { put("planCount", bambooHolder.state.plans.size); put("buildCount", bambooHolder.state.builds.size) } },
                    reset = { bambooHolder.state.shutdown(); bambooHolder.state = BambooDataFactory.createDefaultState() },
                    loadScenario = { name ->
                        when (name) {
                            "default" -> { bambooHolder.state.shutdown(); bambooHolder.state = BambooDataFactory.createDefaultState(); true }
                            "all-builds-failing" -> { bambooHolder.state.shutdown(); bambooHolder.state = BambooDataFactory.createAllFailingState(); true }
                            "build-progression" -> { bambooHolder.state.shutdown(); bambooHolder.state = BambooDataFactory.createBuildProgressionState(); true }
                            "happy-path" -> { bambooHolder.state.shutdown(); bambooHolder.state = BambooDataFactory.createHappyPathState(); true }
                            else -> false
                        }
                    },
                    scenarios = BAMBOO_SCENARIOS,
                    onStop = { bambooHolder.state.shutdown() },
                )
            },
            launch {
                startMockServer("SonarQube", config.sonarPort, sonarChaos, sonarAdmin,
                    routeSetup = { routing { sonarRoutes { sonarHolder.state } } },
                    getState = { buildJsonObject { put("projectCount", sonarHolder.state.projects.size); put("issueCount", sonarHolder.state.issues.size) } },
                    reset = { sonarHolder.state = SonarDataFactory.createDefaultState() },
                    loadScenario = { name ->
                        when (name) {
                            "default" -> { sonarHolder.state = SonarDataFactory.createDefaultState(); true }
                            "quality-gate-warn" -> { sonarHolder.state = SonarDataFactory.createQualityGateWarnState(); true }
                            "metrics-missing" -> { sonarHolder.state = SonarDataFactory.createMetricsMissingState(); true }
                            "auth-invalid" -> { sonarHolder.state = SonarDataFactory.createAuthInvalidState(); true }
                            "happy-path" -> { sonarHolder.state = SonarDataFactory.createHappyPathState(); true }
                            else -> false
                        }
                    },
                    scenarios = SONAR_SCENARIOS,
                )
            },
        )
        jobs.joinAll()
    }
}

private suspend fun startMockServer(
    name: String,
    port: Int,
    chaosConfig: ChaosConfig,
    adminState: AdminState,
    routeSetup: Application.() -> Unit,
    getState: () -> JsonObject,
    reset: () -> Unit,
    loadScenario: (String) -> Boolean,
    scenarios: List<String>,
    onStop: (() -> Unit)? = null,
) {
    try {
        embeddedServer(Netty, port = port) {
            // Store shared chaos config as application attribute so the plugin reads it by reference
            attributes.put(ChaosConfigKey, chaosConfig)

            install(ContentNegotiation) { json() }
            install(CallLogging)
            install(ChaosPlugin)  // Reads config from attributes, not a copy
            install(AuthPlugin)
            install(StatusPages) {
                exception<Throwable> { call, cause ->
                    call.respondText(
                        """{"error":"${cause.message?.replace("\"", "'")}"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.InternalServerError
                    )
                }
            }

            // Shutdown hook for coroutine cleanup
            if (onStop != null) {
                monitor.subscribe(ApplicationStopped) { onStop() }
            }

            routing {
                get("/health") { call.respondText("$name mock is running") }
                adminRoutes(name, chaosConfig, adminState, getState, reset, loadScenario, scenarios)
            }
            routeSetup()

        }.start(wait = true)
    } catch (e: Exception) {
        System.err.println("ERROR: Failed to start $name mock on port $port — ${e.message}")
        System.err.println("Is port $port already in use?")
        throw e
    }
}

private fun printBanner(config: MockConfig) {
    println("""
        |
        |╔══════════════════════════════════════════════════╗
        |║          Workflow Orchestrator Mock Server        ║
        |╠══════════════════════════════════════════════════╣
        |║  Jira      → http://localhost:${config.jiraPort.toString().padEnd(18)}║
        |║  Bamboo    → http://localhost:${config.bambooPort.toString().padEnd(18)}║
        |║  SonarQube → http://localhost:${config.sonarPort.toString().padEnd(18)}║
        |║                                                  ║
        |║  Chaos mode: OFF (enable via /__admin/chaos)     ║
        |║  Scenario:   default (adversarial)               ║
        |║                                                  ║
        |║  Admin:  GET /__admin/state on any port          ║
        |║  Reset:  POST /__admin/reset on any port         ║
        |╚══════════════════════════════════════════════════╝
    """.trimMargin())
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :mock-server:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Run all tests**

Run: `./gradlew :mock-server:test`
Expected: All PASS

- [ ] **Step 4: Manual smoke test — start server and curl endpoints**

```bash
./gradlew :mock-server:run &
sleep 5

# Test auth rejection
curl -s http://localhost:8180/rest/api/2/myself
# Expected: 401 Authentication required

# Test with auth
curl -s -H "Authorization: Bearer mock-token" http://localhost:8180/rest/api/2/myself
# Expected: 200 with user info

# Test Bamboo divergent state
curl -s -H "Authorization: Bearer mock-token" http://localhost:8280/rest/api/latest/result/PROJ-TEST-50
# Expected: lifeCycleState = "Running"

# Test SonarQube quality gate
curl -s -H "Authorization: Bearer mock-token" http://localhost:8380/api/qualitygates/project_status
# Expected: status = "WARN"

# Test admin
curl -s http://localhost:8180/__admin/state
curl -s http://localhost:8180/__admin/scenarios

# Test chaos toggle
curl -s -X POST "http://localhost:8180/__admin/chaos?enabled=true"

# Test scenario switch
curl -s -X POST http://localhost:8180/__admin/scenario/empty-sprint

# Cleanup
kill %1
```

- [ ] **Step 5: Commit**

```bash
git add mock-server/src/
git commit -m "feat(mock-server): wire all services, chaos, auth, and admin into MockServerMain"
```

---

## Chunk 6: Final Integration and Verification

### Task 11: Full integration test

**Files:**
- Create: `mock-server/src/test/kotlin/com/workflow/orchestrator/mockserver/IntegrationTest.kt`

- [ ] **Step 1: Write integration test**

Create `mock-server/src/test/kotlin/com/workflow/orchestrator/mockserver/IntegrationTest.kt`:

```kotlin
package com.workflow.orchestrator.mockserver

import com.workflow.orchestrator.mockserver.admin.*
import com.workflow.orchestrator.mockserver.bamboo.*
import com.workflow.orchestrator.mockserver.chaos.*
import com.workflow.orchestrator.mockserver.jira.*
import com.workflow.orchestrator.mockserver.sonar.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class IntegrationTest {

    @Test
    fun `full Jira workflow - discover board, find sprint, load issues, transition`() = testApplication {
        val state = JiraDataFactory.createDefaultState()
        application {
            install(ContentNegotiation) { json() }
            routing { jiraRoutes { state } }
        }

        // Step 1: Discover board
        val boardsResponse = client.get("/rest/agile/1.0/board?type=scrum")
        val boards = Json.parseToJsonElement(boardsResponse.bodyAsText()).jsonObject["values"]?.jsonArray
        val boardId = boards?.get(0)?.jsonObject?.get("id")?.jsonPrimitive?.int
        assertEquals(42, boardId)

        // Step 2: Find active sprint
        val sprintsResponse = client.get("/rest/agile/1.0/board/$boardId/sprint?state=active")
        val sprints = Json.parseToJsonElement(sprintsResponse.bodyAsText()).jsonObject["values"]?.jsonArray
        val sprintId = sprints?.get(0)?.jsonObject?.get("id")?.jsonPrimitive?.int
        assertEquals(7, sprintId)

        // Step 3: Load issues
        val issuesResponse = client.get("/rest/agile/1.0/sprint/$sprintId/issue")
        val issues = Json.parseToJsonElement(issuesResponse.bodyAsText()).jsonObject["issues"]?.jsonArray
        assertEquals(6, issues?.size)

        // Step 4: Get transitions for an Open issue
        val transResponse = client.get("/rest/api/2/issue/PROJ-101/transitions")
        val transitions = Json.parseToJsonElement(transResponse.bodyAsText()).jsonObject["transitions"]?.jsonArray
        assertNotNull(transitions)
        // None should be named "In Progress"
        val transNames = transitions!!.map { it.jsonObject["name"]?.jsonPrimitive?.content }
        assertFalse("In Progress" in transNames)

        // Step 5: Try transition without required fields — should fail
        val failResponse = client.post("/rest/api/2/issue/PROJ-101/transitions") {
            contentType(ContentType.Application.Json)
            setBody("""{"transition":{"id":"11"}}""")
        }
        assertEquals(HttpStatusCode.BadRequest, failResponse.status)

        // Step 6: Transition with required fields — should succeed
        val successResponse = client.post("/rest/api/2/issue/PROJ-101/transitions") {
            contentType(ContentType.Application.Json)
            setBody("""{"transition":{"id":"11"},"fields":{"assignee":{"name":"mock.user"}}}""")
        }
        assertEquals(HttpStatusCode.NoContent, successResponse.status)

        // Verify state changed
        val updatedIssue = client.get("/rest/api/2/issue/PROJ-101")
        val updatedStatus = Json.parseToJsonElement(updatedIssue.bodyAsText())
            .jsonObject["fields"]?.jsonObject?.get("status")?.jsonObject?.get("name")?.jsonPrimitive?.content
        assertEquals("WIP", updatedStatus)
    }

    @Test
    fun `SonarQube returns all divergent data correctly`() = testApplication {
        val state = SonarDataFactory.createDefaultState()
        application {
            install(ContentNegotiation) { json() }
            routing { sonarRoutes { state } }
        }

        // Quality gate has WARN
        val gateResponse = client.get("/api/qualitygates/project_status")
        val gate = Json.parseToJsonElement(gateResponse.bodyAsText()).jsonObject
        assertEquals("WARN", gate["projectStatus"]?.jsonObject?.get("status")?.jsonPrimitive?.content)

        // Issues have CRITICAL_SECURITY severity and SECURITY_AUDIT type
        val issuesResponse = client.get("/api/issues/search?resolved=false")
        val issues = Json.parseToJsonElement(issuesResponse.bodyAsText()).jsonObject["issues"]?.jsonArray!!
        val severities = issues.map { it.jsonObject["severity"]?.jsonPrimitive?.content }.toSet()
        val types = issues.map { it.jsonObject["type"]?.jsonPrimitive?.content }.toSet()
        assertTrue("CRITICAL_SECURITY" in severities)
        assertTrue("SECURITY_AUDIT" in types)

        // Metrics omit uncovered_conditions
        val metricsResponse = client.get("/api/measures/component_tree?component=com.example:service&metricKeys=coverage,uncovered_conditions")
        val metrics = Json.parseToJsonElement(metricsResponse.bodyAsText()).jsonObject
        val metricKeys = metrics["baseComponent"]?.jsonObject?.get("measures")?.jsonArray?.map {
            it.jsonObject["metric"]?.jsonPrimitive?.content
        }
        assertTrue(metricKeys?.contains("coverage") == true)
        assertFalse(metricKeys?.contains("uncovered_conditions") == true)
    }
}
```

- [ ] **Step 2: Run all tests**

Run: `./gradlew :mock-server:test`
Expected: All PASS

- [ ] **Step 3: Commit**

```bash
git add mock-server/src/test/
git commit -m "test(mock-server): add integration tests for full workflow and divergent data"
```

---

### Task 12: Final verification

- [ ] **Step 1: Run full test suite**

Run: `./gradlew :mock-server:test`
Expected: All tests PASS

- [ ] **Step 2: Verify mock server starts**

Run: `./gradlew :mock-server:run` (Ctrl+C to stop after banner prints)
Expected: Banner prints with all 3 ports, no errors

- [ ] **Step 3: Verify it doesn't break the plugin build**

Run: `./gradlew :core:compileKotlin`
Expected: BUILD SUCCESSFUL — plugin has no dependency on mock-server

- [ ] **Step 4: Final commit**

```bash
git add -A
git commit -m "feat(mock-server): complete adversarial mock server for Jira, Bamboo, SonarQube"
```
