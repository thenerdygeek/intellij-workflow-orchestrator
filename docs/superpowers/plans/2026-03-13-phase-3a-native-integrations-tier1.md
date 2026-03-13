# Phase 3A: Native IntelliJ Integrations — Tier 1 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Surface Jira workflow data in 7 native IntelliJ UI extension points (TODO, editor tabs, Git log, post-commit transitions, Search Everywhere, commit dialog time tracking, Task/Issue Tracker).

**Architecture:** Each integration is a thin adapter that reads from existing services (`ActiveTicketService`, `JiraApiClient`, `SprintService`) and surfaces data through IntelliJ extension points. No new API clients or services are needed — we're wiring existing data into native UI.

**Tech Stack:** Kotlin, IntelliJ Platform SDK (2025.1+), kotlinx.coroutines, existing OkHttp-based API clients

**Design Spec:** `docs/superpowers/specs/2026-03-13-intellij-native-integrations-design.md`

---

## Chunk 1: Quick Wins (TODO Pattern + Editor Tab Badge)

### Task 1: TODO Pattern for Jira Ticket References

**Files:**
- Modify: `src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Add TODO index pattern to plugin.xml**

Add inside the `<extensions defaultExtensionNs="com.intellij">` block:

```xml
<!-- Jira Ticket Reference TODO Pattern -->
<todoIndexPattern pattern="\b[A-Z][A-Z0-9]+-\d+\b" caseSensitive="true"/>
```

- [ ] **Step 2: Verify in runIde**

Run: `./gradlew runIde`
Open any source file with a comment containing `// PROJ-123 fix this later`
Expected: The TODO tool window (View > Tool Windows > TODO) shows the ticket reference.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/META-INF/plugin.xml
git commit -m "feat(jira): add TODO pattern for Jira ticket references in comments"
```

---

### Task 2: Editor Tab Ticket Badge

**Files:**
- Create: `jira/src/main/kotlin/com/workflow/orchestrator/jira/editor/TicketEditorTabTitleProvider.kt`
- Create: `jira/src/test/kotlin/com/workflow/orchestrator/jira/editor/TicketEditorTabTitleProviderTest.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.workflow.orchestrator.jira.editor

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TicketEditorTabTitleProviderTest {

    @Test
    fun `generates tab title with ticket suffix when active`() {
        val title = TicketTabTitleHelper.generateTitle("UserService.kt", "PROJ-123")
        assertEquals("UserService.kt [PROJ-123]", title)
    }

    @Test
    fun `returns null when no active ticket`() {
        val title = TicketTabTitleHelper.generateTitle("UserService.kt", "")
        assertNull(title)
    }

    @Test
    fun `returns null when ticket is blank`() {
        val title = TicketTabTitleHelper.generateTitle("UserService.kt", "  ")
        assertNull(title)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :jira:test --tests '*TicketEditorTabTitleProviderTest*'`
Expected: FAIL — `TicketTabTitleHelper` not found

- [ ] **Step 3: Implement the helper and provider**

Create `jira/src/main/kotlin/com/workflow/orchestrator/jira/editor/TicketEditorTabTitleProvider.kt`:

```kotlin
package com.workflow.orchestrator.jira.editor

import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VirtualFile
import com.workflow.orchestrator.core.settings.PluginSettings

/**
 * Appends the active Jira ticket ID to editor tab titles for modified files.
 * Only shows the badge on files that are in the current VCS changelist,
 * so unchanged files keep their normal tab title.
 */
class TicketEditorTabTitleProvider : EditorTabTitleProvider {
    override fun getEditorTabTitle(project: Project, file: VirtualFile): String? {
        val ticketId = PluginSettings.getInstance(project).state.activeTicketId
        if (ticketId.isNullOrBlank()) return null

        // Only badge files that are modified (in changelist)
        val changeListManager = ChangeListManager.getInstance(project)
        val isModified = changeListManager.getChange(file) != null
        if (!isModified) return null

        return TicketTabTitleHelper.generateTitle(file.presentableName, ticketId)
    }
}

/** Pure helper — easily testable without IntelliJ dependencies. */
object TicketTabTitleHelper {
    fun generateTitle(fileName: String, ticketId: String?): String? {
        if (ticketId.isNullOrBlank()) return null
        return "$fileName [$ticketId]"
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :jira:test --tests '*TicketEditorTabTitleProviderTest*'`
Expected: 3 tests PASS

- [ ] **Step 5: Register in plugin.xml**

Add to the `<extensions defaultExtensionNs="com.intellij">` block:

```xml
<!-- Editor Tab Ticket Badge -->
<editorTabTitleProvider
    implementation="com.workflow.orchestrator.jira.editor.TicketEditorTabTitleProvider"/>
```

- [ ] **Step 6: Commit**

```bash
git add jira/src/main/kotlin/com/workflow/orchestrator/jira/editor/TicketEditorTabTitleProvider.kt
git add jira/src/test/kotlin/com/workflow/orchestrator/jira/editor/TicketEditorTabTitleProviderTest.kt
git add src/main/resources/META-INF/plugin.xml
git commit -m "feat(jira): add ticket ID badge to editor tabs for modified files"
```

---

## Chunk 2: VCS Log Column + Post-Commit Transition

### Task 3: Ticket Cache (shared by VCS Log Column and others)

**Files:**
- Create: `jira/src/main/kotlin/com/workflow/orchestrator/jira/vcs/TicketCache.kt`
- Create: `jira/src/test/kotlin/com/workflow/orchestrator/jira/vcs/TicketCacheTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.workflow.orchestrator.jira.vcs

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TicketCacheTest {

    @Test
    fun `stores and retrieves cached ticket`() {
        val cache = TicketCache(maxSize = 10, ttlMs = 60_000)
        cache.put("PROJ-123", TicketCacheEntry("PROJ-123", "Fix login", "In Progress"))
        val entry = cache.get("PROJ-123")
        assertNotNull(entry)
        assertEquals("Fix login", entry!!.summary)
    }

    @Test
    fun `returns null for missing ticket`() {
        val cache = TicketCache(maxSize = 10, ttlMs = 60_000)
        assertNull(cache.get("PROJ-999"))
    }

    @Test
    fun `evicts expired entries`() {
        val cache = TicketCache(maxSize = 10, ttlMs = 1) // 1ms TTL
        cache.put("PROJ-123", TicketCacheEntry("PROJ-123", "Fix login", "In Progress"))
        Thread.sleep(10)
        assertNull(cache.get("PROJ-123"))
    }

    @Test
    fun `evicts oldest when at capacity`() {
        val cache = TicketCache(maxSize = 2, ttlMs = 60_000)
        cache.put("A-1", TicketCacheEntry("A-1", "First", "Open"))
        cache.put("A-2", TicketCacheEntry("A-2", "Second", "Open"))
        cache.put("A-3", TicketCacheEntry("A-3", "Third", "Open"))
        assertNull(cache.get("A-1")) // evicted
        assertNotNull(cache.get("A-3"))
    }

    @Test
    fun `extracts ticket ID from commit message`() {
        assertEquals("PROJ-123", TicketIdExtractor.extract("PROJ-123: fix login"))
        assertEquals("PROJ-123", TicketIdExtractor.extract("feat(PROJ-123): fix login"))
        assertEquals("AB-1", TicketIdExtractor.extract("AB-1 something"))
        assertNull(TicketIdExtractor.extract("no ticket here"))
        assertNull(TicketIdExtractor.extract(""))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :jira:test --tests '*TicketCacheTest*'`
Expected: FAIL — classes not found

- [ ] **Step 3: Implement TicketCache and TicketIdExtractor**

Create `jira/src/main/kotlin/com/workflow/orchestrator/jira/vcs/TicketCache.kt`:

```kotlin
package com.workflow.orchestrator.jira.vcs

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

data class TicketCacheEntry(
    val key: String,
    val summary: String,
    val statusName: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Thread-safe LRU cache with TTL for Jira ticket metadata.
 * Used by VCS Log Column and other integrations that need ticket details
 * without hitting the API on every render.
 */
class TicketCache(
    private val maxSize: Int = 500,
    private val ttlMs: Long = 600_000 // 10 minutes
) {
    private val map = ConcurrentHashMap<String, TicketCacheEntry>()
    private val accessOrder = ConcurrentLinkedDeque<String>()

    fun get(key: String): TicketCacheEntry? {
        val entry = map[key] ?: return null
        if (System.currentTimeMillis() - entry.timestamp > ttlMs) {
            map.remove(key)
            accessOrder.remove(key)
            return null
        }
        // Move to end (most recently used)
        accessOrder.remove(key)
        accessOrder.addLast(key)
        return entry
    }

    fun put(key: String, entry: TicketCacheEntry) {
        map[key] = entry
        accessOrder.remove(key)
        accessOrder.addLast(key)
        while (map.size > maxSize) {
            val oldest = accessOrder.pollFirst() ?: break
            map.remove(oldest)
        }
    }

    fun clear() {
        map.clear()
        accessOrder.clear()
    }
}

/** Extracts Jira ticket ID from commit messages. */
object TicketIdExtractor {
    private val TICKET_PATTERN = Regex("\\b([A-Z][A-Z0-9]+-\\d+)\\b")

    fun extract(commitMessage: String): String? {
        return TICKET_PATTERN.find(commitMessage)?.groupValues?.get(1)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :jira:test --tests '*TicketCacheTest*'`
Expected: 5 tests PASS

- [ ] **Step 5: Commit**

```bash
git add jira/src/main/kotlin/com/workflow/orchestrator/jira/vcs/TicketCache.kt
git add jira/src/test/kotlin/com/workflow/orchestrator/jira/vcs/TicketCacheTest.kt
git commit -m "feat(jira): add LRU ticket cache with TTL and ticket ID extractor"
```

---

### Task 4: VCS Log Ticket Column

**Files:**
- Create: `jira/src/main/kotlin/com/workflow/orchestrator/jira/vcs/JiraVcsLogColumn.kt`
- Modify: `src/main/resources/META-INF/plugin.xml` (or `plugin-withGit.xml`)

- [ ] **Step 1: Implement the VCS Log Column**

Create `jira/src/main/kotlin/com/workflow/orchestrator/jira/vcs/JiraVcsLogColumn.kt`:

```kotlin
package com.workflow.orchestrator.jira.vcs

import com.intellij.openapi.project.Project
import com.intellij.openapi.diagnostic.Logger
import com.intellij.vcs.log.VcsCommitMetadata
import com.intellij.vcs.log.VcsLogCustomColumn
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.jira.api.JiraApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.awt.Component
import javax.swing.JTable
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer

/**
 * Adds a "Jira Ticket" column to the Git Log view.
 * Extracts ticket IDs from commit messages and displays the summary + status.
 *
 * Ticket metadata is fetched lazily and cached. The column shows the ticket key
 * immediately, then updates with the summary once the API responds.
 */
class JiraVcsLogColumn : VcsLogCustomColumn<String> {

    private val log = Logger.getInstance(JiraVcsLogColumn::class.java)
    private val cache = TicketCache(maxSize = 500, ttlMs = 600_000)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingFetches = mutableSetOf<String>()

    override fun getStubValue(commitMetadata: VcsCommitMetadata?): String {
        if (commitMetadata == null) return ""
        return TicketIdExtractor.extract(commitMetadata.fullMessage) ?: ""
    }

    override fun getValue(commitMetadata: VcsCommitMetadata, project: Project): String {
        val ticketId = TicketIdExtractor.extract(commitMetadata.fullMessage) ?: return ""

        val cached = cache.get(ticketId)
        if (cached != null) {
            return "$ticketId | ${cached.summary} (${cached.statusName})"
        }

        // Schedule async fetch if not already pending
        if (ticketId !in pendingFetches) {
            pendingFetches.add(ticketId)
            val settings = PluginSettings.getInstance(project)
            val baseUrl = settings.state.jiraUrl.orEmpty().trimEnd('/')
            if (baseUrl.isNotBlank()) {
                scope.launch {
                    try {
                        val credentialStore = CredentialStore()
                        val client = JiraApiClient(baseUrl) { credentialStore.getToken(ServiceType.JIRA) }
                        when (val result = client.getIssue(ticketId)) {
                            is ApiResult.Success -> {
                                cache.put(ticketId, TicketCacheEntry(
                                    ticketId,
                                    result.data.fields.summary,
                                    result.data.fields.status.name
                                ))
                            }
                            is ApiResult.Error -> {
                                log.debug("[Jira:VcsLog] Could not fetch $ticketId: ${result.message}")
                            }
                        }
                    } finally {
                        pendingFetches.remove(ticketId)
                    }
                }
            }
        }

        return ticketId // Show just the key until fetch completes
    }

    override fun getName(): String = "Jira Ticket"

    override fun isEnabledByDefault(): Boolean = true

    override fun createTableCellRenderer(): TableCellRenderer {
        return object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable, value: Any?, isSelected: Boolean,
                hasFocus: Boolean, row: Int, column: Int
            ): Component {
                val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                val text = value?.toString() ?: ""
                // Extract status for coloring
                if (text.contains("(") && text.contains(")")) {
                    val status = text.substringAfterLast("(").substringBefore(")")
                    if (!isSelected) {
                        foreground = when {
                            status.contains("Done", ignoreCase = true) ||
                            status.contains("Closed", ignoreCase = true) ||
                            status.contains("Resolved", ignoreCase = true) -> java.awt.Color(0x59, 0xA6, 0x0F)
                            status.contains("Progress", ignoreCase = true) -> java.awt.Color(0x40, 0x7E, 0xC9)
                            else -> table.foreground
                        }
                    }
                }
                return component
            }
        }
    }
}
```

- [ ] **Step 2: Register in plugin-withGit.xml**

Find or create `src/main/resources/META-INF/plugin-withGit.xml` and add:

```xml
<idea-plugin>
    <extensions defaultExtensionNs="com.intellij">
        <vcsLogCustomColumn
            implementation="com.workflow.orchestrator.jira.vcs.JiraVcsLogColumn"/>
    </extensions>
</idea-plugin>
```

If `plugin-withGit.xml` already has content, add the `<vcsLogCustomColumn>` entry inside the existing `<extensions>` block.

- [ ] **Step 3: Verify in runIde**

Run: `./gradlew runIde`
Open Git Log (Cmd+9 or View > Tool Windows > Git)
Expected: "Jira Ticket" column visible, shows ticket IDs from commit messages.

- [ ] **Step 4: Commit**

```bash
git add jira/src/main/kotlin/com/workflow/orchestrator/jira/vcs/JiraVcsLogColumn.kt
git add src/main/resources/META-INF/plugin-withGit.xml
git commit -m "feat(jira): add Jira ticket column to Git Log view"
```

---

### Task 5: Post-Commit Jira Transition

**Files:**
- Create: `jira/src/main/kotlin/com/workflow/orchestrator/jira/vcs/PostCommitTransitionHandlerFactory.kt`
- Create: `jira/src/test/kotlin/com/workflow/orchestrator/jira/vcs/PostCommitTransitionLogicTest.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.workflow.orchestrator.jira.vcs

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PostCommitTransitionLogicTest {

    @Test
    fun `should suggest transition when status is To Do`() {
        assertTrue(PostCommitTransitionLogic.shouldSuggestTransition("To Do"))
    }

    @Test
    fun `should suggest transition when status is Open`() {
        assertTrue(PostCommitTransitionLogic.shouldSuggestTransition("Open"))
    }

    @Test
    fun `should not suggest when already In Progress`() {
        assertFalse(PostCommitTransitionLogic.shouldSuggestTransition("In Progress"))
    }

    @Test
    fun `should not suggest when Done`() {
        assertFalse(PostCommitTransitionLogic.shouldSuggestTransition("Done"))
    }

    @Test
    fun `should not suggest when status is blank`() {
        assertFalse(PostCommitTransitionLogic.shouldSuggestTransition(""))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :jira:test --tests '*PostCommitTransitionLogicTest*'`
Expected: FAIL — class not found

- [ ] **Step 3: Implement the logic and handler**

Create `jira/src/main/kotlin/com/workflow/orchestrator/jira/vcs/PostCommitTransitionHandlerFactory.kt`:

```kotlin
package com.workflow.orchestrator.jira.vcs

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.CheckinHandlerFactory
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.notifications.WorkflowNotificationService
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.jira.api.JiraApiClient
import com.workflow.orchestrator.jira.service.ActiveTicketService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * After a successful commit, suggests transitioning the Jira ticket
 * to "In Progress" if it's still in "To Do" or "Open" status.
 */
class PostCommitTransitionHandlerFactory : CheckinHandlerFactory() {

    override fun createHandler(panel: CheckinProjectPanel, commitContext: CommitContext): CheckinHandler {
        return PostCommitTransitionHandler(panel.project)
    }
}

class PostCommitTransitionHandler(private val project: Project) : CheckinHandler() {

    private val log = Logger.getInstance(PostCommitTransitionHandler::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun checkinSuccessful() {
        val settings = PluginSettings.getInstance(project)
        val ticketId = settings.state.activeTicketId
        if (ticketId.isNullOrBlank()) return

        val baseUrl = settings.state.jiraUrl.orEmpty().trimEnd('/')
        if (baseUrl.isBlank()) return

        scope.launch {
            try {
                val credentialStore = CredentialStore()
                val client = JiraApiClient(baseUrl) { credentialStore.getToken(ServiceType.JIRA) }

                when (val result = client.getIssue(ticketId)) {
                    is ApiResult.Success -> {
                        val currentStatus = result.data.fields.status.name
                        if (PostCommitTransitionLogic.shouldSuggestTransition(currentStatus)) {
                            // Find "In Progress" transition
                            when (val transitions = client.getTransitions(ticketId)) {
                                is ApiResult.Success -> {
                                    val inProgressTransition = transitions.data.find {
                                        it.to.name.equals("In Progress", ignoreCase = true)
                                    }
                                    if (inProgressTransition != null) {
                                        val notificationService = WorkflowNotificationService.getInstance(project)
                                        notificationService.notifyWithAction(
                                            "workflow.automation",
                                            "Transition $ticketId?",
                                            "$ticketId is still '$currentStatus'. Move to In Progress?",
                                            "Transition"
                                        ) {
                                            scope.launch {
                                                client.transitionIssue(ticketId, inProgressTransition.id)
                                                log.info("[Jira:PostCommit] Transitioned $ticketId to In Progress")
                                            }
                                        }
                                    }
                                }
                                is ApiResult.Error -> {
                                    log.debug("[Jira:PostCommit] Could not fetch transitions for $ticketId")
                                }
                            }
                        }
                    }
                    is ApiResult.Error -> {
                        log.debug("[Jira:PostCommit] Could not fetch $ticketId: ${result.message}")
                    }
                }
            } catch (e: Exception) {
                log.debug("[Jira:PostCommit] Error checking ticket status: ${e.message}")
            }
        }
    }
}

/** Pure logic — testable without IntelliJ dependencies. */
object PostCommitTransitionLogic {
    private val NEEDS_TRANSITION_STATUSES = setOf("to do", "open", "new", "backlog", "selected for development")

    fun shouldSuggestTransition(currentStatus: String): Boolean {
        if (currentStatus.isBlank()) return false
        return currentStatus.lowercase() in NEEDS_TRANSITION_STATUSES
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :jira:test --tests '*PostCommitTransitionLogicTest*'`
Expected: 5 tests PASS

- [ ] **Step 5: Register in plugin.xml**

Add to `<extensions defaultExtensionNs="com.intellij">`:

```xml
<!-- Post-Commit Jira Transition -->
<vcsCheckinHandlerFactory
    implementation="com.workflow.orchestrator.jira.vcs.PostCommitTransitionHandlerFactory"/>
```

- [ ] **Step 6: Commit**

```bash
git add jira/src/main/kotlin/com/workflow/orchestrator/jira/vcs/PostCommitTransitionHandlerFactory.kt
git add jira/src/test/kotlin/com/workflow/orchestrator/jira/vcs/PostCommitTransitionLogicTest.kt
git add src/main/resources/META-INF/plugin.xml
git commit -m "feat(jira): suggest Jira transition to In Progress after commit"
```

---

## Chunk 3: Search Everywhere + Commit Dialog Time Tracking

### Task 6: Search Everywhere Jira Contributor

**Files:**
- Create: `jira/src/main/kotlin/com/workflow/orchestrator/jira/search/JiraSearchContributorFactory.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Implement the Search Everywhere contributor**

Create `jira/src/main/kotlin/com/workflow/orchestrator/jira/search/JiraSearchContributorFactory.kt`:

```kotlin
package com.workflow.orchestrator.jira.search

import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributor
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributorFactory
import com.intellij.ide.actions.searcheverywhere.WeightedSearchEverywhereContributor
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.util.Processor
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.jira.api.JiraApiClient
import com.workflow.orchestrator.jira.api.dto.JiraIssue
import com.workflow.orchestrator.jira.service.ActiveTicketService
import java.awt.Component
import java.net.URLEncoder
import javax.swing.JLabel
import javax.swing.ListCellRenderer

class JiraSearchContributorFactory : SearchEverywhereContributorFactory<JiraIssue> {
    override fun createContributor(initEvent: AnActionEvent): SearchEverywhereContributor<JiraIssue> {
        return JiraSearchContributor(initEvent.project!!)
    }
}

class JiraSearchContributor(
    private val project: Project
) : WeightedSearchEverywhereContributor<JiraIssue> {

    private val log = Logger.getInstance(JiraSearchContributor::class.java)

    override fun getSearchProviderId(): String = "JiraWorkflowSearch"
    override fun getGroupName(): String = "Jira Tickets"
    override fun getSortWeight(): Int = 50
    override fun showInFindResults(): Boolean = false
    override fun isShownInSeparateTab(): Boolean = true

    override fun fetchWeightedElements(
        pattern: String,
        progressIndicator: ProgressIndicator,
        consumer: Processor<in FoundItemDescriptor<JiraIssue>>
    ) {
        if (pattern.length < 3) return

        val settings = PluginSettings.getInstance(project)
        val baseUrl = settings.state.jiraUrl.orEmpty().trimEnd('/')
        if (baseUrl.isBlank()) return

        progressIndicator.checkCanceled()

        try {
            val credentialStore = CredentialStore()
            val client = JiraApiClient(baseUrl) { credentialStore.getToken(ServiceType.JIRA) }

            // Build JQL — search by text or exact key
            val jql = if (pattern.matches(Regex("[A-Z][A-Z0-9]+-\\d+"))) {
                "key = $pattern"
            } else {
                val encoded = URLEncoder.encode(pattern, "UTF-8")
                "text ~ \"$encoded\" AND assignee = currentUser() ORDER BY updated DESC"
            }

            // Synchronous call (Search Everywhere runs on a background thread)
            val request = okhttp3.Request.Builder()
                .url("$baseUrl/rest/api/2/search?jql=${URLEncoder.encode(jql, "UTF-8")}&maxResults=20")
                .addHeader("Authorization", "Bearer ${credentialStore.getToken(ServiceType.JIRA) ?: ""}")
                .build()

            val httpClient = okhttp3.OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val response = httpClient.newCall(request).execute()
            response.use {
                if (it.isSuccessful) {
                    val body = it.body?.string() ?: return
                    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                    val result = json.decodeFromString<com.workflow.orchestrator.jira.api.dto.JiraIssueSearchResult>(body)

                    for (issue in result.issues) {
                        progressIndicator.checkCanceled()
                        consumer.process(FoundItemDescriptor(issue, getSortWeight()))
                    }
                }
            }
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            log.debug("[Jira:Search] Search failed: ${e.message}")
        }
    }

    override fun getDataForItem(element: JiraIssue, dataId: String): Any? = null

    override fun processSelectedItem(selected: JiraIssue, modifiers: Int, searchText: String): Boolean {
        // Set as active ticket
        ApplicationManager.getApplication().invokeLater {
            val activeTicketService = ActiveTicketService.getInstance(project)
            activeTicketService.setActiveTicket(selected.key, selected.fields.summary)
        }
        return true
    }

    override fun getElementsRenderer(): ListCellRenderer<in JiraIssue> {
        return ListCellRenderer { list, value, index, isSelected, cellHasFocus ->
            val label = JLabel()
            if (value != null) {
                val typeIcon = value.fields.issuetype?.name ?: "Task"
                val status = value.fields.status.name
                label.text = "[${value.key}] ${value.fields.summary} ($status)"
            }
            if (isSelected) {
                label.background = list.selectionBackground
                label.foreground = list.selectionForeground
                label.isOpaque = true
            }
            label
        }
    }
}
```

- [ ] **Step 2: Register in plugin.xml**

Add to `<extensions defaultExtensionNs="com.intellij">`:

```xml
<!-- Search Everywhere: Jira Tickets -->
<searchEverywhereContributor
    implementation="com.workflow.orchestrator.jira.search.JiraSearchContributorFactory"/>
```

- [ ] **Step 3: Verify in runIde**

Run: `./gradlew runIde`
Press Shift+Shift, type a ticket ID or keyword.
Expected: "Jira Tickets" tab appears with matching results.

- [ ] **Step 4: Commit**

```bash
git add jira/src/main/kotlin/com/workflow/orchestrator/jira/search/JiraSearchContributorFactory.kt
git add src/main/resources/META-INF/plugin.xml
git commit -m "feat(jira): add Jira ticket search to Search Everywhere dialog"
```

---

### Task 7: Commit Dialog Time Tracking Panel

**Files:**
- Create: `jira/src/main/kotlin/com/workflow/orchestrator/jira/vcs/TimeTrackingCheckinHandlerFactory.kt`
- Create: `jira/src/test/kotlin/com/workflow/orchestrator/jira/vcs/TimeTrackingLogicTest.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.workflow.orchestrator.jira.vcs

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TimeTrackingLogicTest {

    @Test
    fun `formats minutes into Jira time format`() {
        assertEquals("2h 30m", TimeTrackingLogic.formatJiraTime(150))
        assertEquals("1h 0m", TimeTrackingLogic.formatJiraTime(60))
        assertEquals("0h 30m", TimeTrackingLogic.formatJiraTime(30))
        assertEquals("0h 0m", TimeTrackingLogic.formatJiraTime(0))
    }

    @Test
    fun `calculates elapsed minutes from timestamp`() {
        val now = System.currentTimeMillis()
        val twoHoursAgo = now - (2 * 60 * 60 * 1000)
        val elapsed = TimeTrackingLogic.elapsedMinutes(twoHoursAgo, now)
        assertEquals(120, elapsed)
    }

    @Test
    fun `clamps elapsed time to max hours`() {
        val now = System.currentTimeMillis()
        val tenHoursAgo = now - (10 * 60 * 60 * 1000)
        val clamped = TimeTrackingLogic.clampMinutes(
            TimeTrackingLogic.elapsedMinutes(tenHoursAgo, now),
            maxHours = 7.0f
        )
        assertEquals(420, clamped) // 7 hours = 420 minutes
    }

    @Test
    fun `returns 0 when no start timestamp`() {
        assertEquals(0, TimeTrackingLogic.elapsedMinutes(0, System.currentTimeMillis()))
    }

    @Test
    fun `builds Jira worklog time spent string`() {
        assertEquals("2h 30m", TimeTrackingLogic.toJiraTimeSpent(150))
        assertEquals("1h", TimeTrackingLogic.toJiraTimeSpent(60))
        assertEquals("30m", TimeTrackingLogic.toJiraTimeSpent(30))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :jira:test --tests '*TimeTrackingLogicTest*'`
Expected: FAIL — class not found

- [ ] **Step 3: Implement the logic and handler**

Create `jira/src/main/kotlin/com/workflow/orchestrator/jira/vcs/TimeTrackingCheckinHandlerFactory.kt`:

```kotlin
package com.workflow.orchestrator.jira.vcs

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.CheckinHandlerFactory
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.jira.api.JiraApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import javax.swing.*

class TimeTrackingCheckinHandlerFactory : CheckinHandlerFactory() {
    override fun createHandler(panel: CheckinProjectPanel, commitContext: CommitContext): CheckinHandler {
        return TimeTrackingCheckinHandler(panel.project, panel)
    }
}

class TimeTrackingCheckinHandler(
    private val project: Project,
    private val panel: CheckinProjectPanel
) : CheckinHandler() {

    private val log = Logger.getInstance(TimeTrackingCheckinHandler::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var logTimeCheckbox: JCheckBox? = null
    private var timeSpinner: JSpinner? = null
    private var commentField: JTextField? = null

    override fun getBeforeCheckinConfigurationPanel(): RefreshableOnComponent? {
        val settings = PluginSettings.getInstance(project)
        val ticketId = settings.state.activeTicketId
        if (ticketId.isNullOrBlank()) return null

        val startTimestamp = settings.state.startWorkTimestamp
        val elapsedMinutes = TimeTrackingLogic.elapsedMinutes(startTimestamp, System.currentTimeMillis())
        val clampedMinutes = TimeTrackingLogic.clampMinutes(elapsedMinutes, settings.state.maxWorklogHours)
        val incrementMinutes = (settings.state.worklogIncrementHours * 60).toInt()

        val checkbox = JCheckBox("Log time to $ticketId", false)
        logTimeCheckbox = checkbox

        val spinner = JSpinner(SpinnerNumberModel(
            clampedMinutes.coerceAtLeast(incrementMinutes),
            0,
            (settings.state.maxWorklogHours * 60).toInt(),
            incrementMinutes
        ))
        timeSpinner = spinner

        val comment = JTextField(20)
        commentField = comment

        val configPanel = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
            add(checkbox, BorderLayout.WEST)
            val rightPanel = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
                add(JLabel("Time (min):"), BorderLayout.WEST)
                add(spinner, BorderLayout.CENTER)
            }
            add(rightPanel, BorderLayout.EAST)
        }

        return object : RefreshableOnComponent {
            override fun getComponent(): JComponent = configPanel
            override fun refresh() {}
            override fun saveState() {}
            override fun restoreState() {}
        }
    }

    override fun checkinSuccessful() {
        val checkbox = logTimeCheckbox ?: return
        if (!checkbox.isSelected) return

        val settings = PluginSettings.getInstance(project)
        val ticketId = settings.state.activeTicketId ?: return
        val minutes = (timeSpinner?.value as? Int) ?: return
        if (minutes <= 0) return

        val timeSpent = TimeTrackingLogic.toJiraTimeSpent(minutes)
        val comment = commentField?.text?.takeIf { it.isNotBlank() }
            ?: panel.commitMessage.lines().firstOrNull() ?: ""

        val baseUrl = settings.state.jiraUrl.orEmpty().trimEnd('/')
        if (baseUrl.isBlank()) return

        scope.launch {
            try {
                val credentialStore = CredentialStore()
                val token = credentialStore.getToken(ServiceType.JIRA) ?: return@launch

                val body = buildString {
                    append("{\"timeSpent\":\"$timeSpent\"")
                    if (comment.isNotBlank()) append(",\"comment\":\"${comment.replace("\"", "\\\"")}\"")
                    append("}")
                }

                val request = okhttp3.Request.Builder()
                    .url("$baseUrl/rest/api/2/issue/$ticketId/worklog")
                    .addHeader("Authorization", "Bearer $token")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()

                val httpClient = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                val response = httpClient.newCall(request).execute()
                response.use {
                    if (it.isSuccessful) {
                        log.info("[Jira:TimeTracking] Logged $timeSpent to $ticketId")
                    } else {
                        log.warn("[Jira:TimeTracking] Failed to log time: ${it.code}")
                    }
                }
            } catch (e: Exception) {
                log.warn("[Jira:TimeTracking] Error logging time: ${e.message}")
            }
        }
    }
}

/** Pure logic — testable without IntelliJ dependencies. */
object TimeTrackingLogic {
    fun formatJiraTime(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return "${h}h ${m}m"
    }

    fun elapsedMinutes(startTimestampMs: Long, nowMs: Long): Int {
        if (startTimestampMs <= 0) return 0
        return ((nowMs - startTimestampMs) / 60_000).toInt().coerceAtLeast(0)
    }

    fun clampMinutes(minutes: Int, maxHours: Float): Int {
        val maxMinutes = (maxHours * 60).toInt()
        return minutes.coerceAtMost(maxMinutes)
    }

    fun toJiraTimeSpent(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h"
            else -> "${m}m"
        }
    }
}
```

Note: The handler uses `okhttp3.RequestBody.Companion.toRequestBody` and `okhttp3.MediaType.Companion.toMediaType` — add these imports.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :jira:test --tests '*TimeTrackingLogicTest*'`
Expected: 5 tests PASS

- [ ] **Step 5: Register in plugin.xml**

Add to `<extensions defaultExtensionNs="com.intellij">`:

```xml
<!-- Commit Dialog: Jira Time Tracking -->
<vcsCheckinHandlerFactory
    implementation="com.workflow.orchestrator.jira.vcs.TimeTrackingCheckinHandlerFactory"/>
```

- [ ] **Step 6: Commit**

```bash
git add jira/src/main/kotlin/com/workflow/orchestrator/jira/vcs/TimeTrackingCheckinHandlerFactory.kt
git add jira/src/test/kotlin/com/workflow/orchestrator/jira/vcs/TimeTrackingLogicTest.kt
git add src/main/resources/META-INF/plugin.xml
git commit -m "feat(jira): add time tracking panel to commit dialog"
```

---

## Chunk 4: Jira Task/Issue Tracker

### Task 8: Jira Task Repository Type

**Files:**
- Create: `jira/src/main/kotlin/com/workflow/orchestrator/jira/tasks/JiraTaskRepositoryType.kt`
- Create: `jira/src/main/kotlin/com/workflow/orchestrator/jira/tasks/JiraTaskRepository.kt`
- Create: `jira/src/main/kotlin/com/workflow/orchestrator/jira/tasks/JiraTask.kt`
- Create: `jira/src/test/kotlin/com/workflow/orchestrator/jira/tasks/JiraTaskMappingTest.kt`
- Create: `src/main/resources/META-INF/plugin-withTasks.xml`
- Modify: `src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.workflow.orchestrator.jira.tasks

import com.intellij.tasks.TaskType
import com.intellij.tasks.TaskState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class JiraTaskMappingTest {

    @Test
    fun `maps Bug issue type to BUG task type`() {
        assertEquals(TaskType.BUG, JiraTaskMapping.mapIssueType("Bug"))
    }

    @Test
    fun `maps Story issue type to FEATURE`() {
        assertEquals(TaskType.FEATURE, JiraTaskMapping.mapIssueType("Story"))
    }

    @Test
    fun `maps Task issue type to FEATURE`() {
        assertEquals(TaskType.FEATURE, JiraTaskMapping.mapIssueType("Task"))
    }

    @Test
    fun `maps unknown issue type to OTHER`() {
        assertEquals(TaskType.OTHER, JiraTaskMapping.mapIssueType("Epic"))
        assertEquals(TaskType.OTHER, JiraTaskMapping.mapIssueType("Sub-task"))
    }

    @Test
    fun `maps new status category to OPEN`() {
        assertEquals(TaskState.OPEN, JiraTaskMapping.mapStatusCategory("new"))
    }

    @Test
    fun `maps indeterminate status category to IN_PROGRESS`() {
        assertEquals(TaskState.IN_PROGRESS, JiraTaskMapping.mapStatusCategory("indeterminate"))
    }

    @Test
    fun `maps done status category to RESOLVED`() {
        assertEquals(TaskState.RESOLVED, JiraTaskMapping.mapStatusCategory("done"))
    }

    @Test
    fun `maps unknown status category to OPEN`() {
        assertEquals(TaskState.OPEN, JiraTaskMapping.mapStatusCategory("undefined"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :jira:test --tests '*JiraTaskMappingTest*'`
Expected: FAIL — class not found

Note: This test requires `com.intellij.tasks` on the test classpath. If not available, the test needs to be run as a platform test. Check if `testFramework(TestFrameworkType.Platform)` is already in `jira/build.gradle.kts`. If `TaskType` and `TaskState` are not available, add:

```kotlin
intellijPlatform {
    bundledPlugin("com.intellij.tasks")
}
```

to the `jira/build.gradle.kts` `dependencies { intellijPlatform { } }` block.

- [ ] **Step 3: Implement the mapping helper**

Create `jira/src/main/kotlin/com/workflow/orchestrator/jira/tasks/JiraTaskMapping.kt`:

```kotlin
package com.workflow.orchestrator.jira.tasks

import com.intellij.tasks.TaskState
import com.intellij.tasks.TaskType

/** Maps Jira issue fields to IntelliJ Task system types. */
object JiraTaskMapping {

    fun mapIssueType(jiraTypeName: String): TaskType = when (jiraTypeName.lowercase()) {
        "bug" -> TaskType.BUG
        "story", "task", "improvement", "new feature" -> TaskType.FEATURE
        else -> TaskType.OTHER
    }

    fun mapStatusCategory(categoryKey: String): TaskState = when (categoryKey.lowercase()) {
        "new", "undefined" -> TaskState.OPEN
        "indeterminate" -> TaskState.IN_PROGRESS
        "done" -> TaskState.RESOLVED
        else -> TaskState.OPEN
    }
}
```

- [ ] **Step 4: Implement JiraTask**

Create `jira/src/main/kotlin/com/workflow/orchestrator/jira/tasks/JiraTask.kt`:

```kotlin
package com.workflow.orchestrator.jira.tasks

import com.intellij.tasks.Task
import com.intellij.tasks.TaskType
import com.intellij.tasks.TaskState
import com.workflow.orchestrator.jira.api.dto.JiraIssue
import java.util.Date
import javax.swing.Icon

/**
 * Wraps a JiraIssue as an IntelliJ Task for the Task Management system.
 * Enables native branch creation, context switching, and commit prefixing.
 */
class JiraTask(private val issue: JiraIssue, private val jiraBaseUrl: String) : Task() {

    override fun getId(): String = issue.key

    override fun getSummary(): String = issue.fields.summary

    override fun getDescription(): String? = issue.fields.description

    override fun getCreated(): Date? = null // Could parse issue.fields.created if needed

    override fun getUpdated(): Date? = null // Could parse issue.fields.updated if needed

    override fun isClosed(): Boolean {
        val category = issue.fields.status.statusCategory?.key ?: ""
        return category.equals("done", ignoreCase = true)
    }

    override fun isIssue(): Boolean = true

    override fun getIssueUrl(): String? {
        if (jiraBaseUrl.isBlank()) return null
        return "$jiraBaseUrl/browse/${issue.key}"
    }

    override fun getType(): TaskType {
        return JiraTaskMapping.mapIssueType(issue.fields.issuetype?.name ?: "Task")
    }

    override fun getState(): TaskState? {
        val categoryKey = issue.fields.status.statusCategory?.key ?: "new"
        return JiraTaskMapping.mapStatusCategory(categoryKey)
    }

    override fun getIcon(): Icon = com.intellij.icons.AllIcons.Nodes.Tag
}
```

- [ ] **Step 5: Implement JiraTaskRepository**

Create `jira/src/main/kotlin/com/workflow/orchestrator/jira/tasks/JiraTaskRepository.kt`:

```kotlin
package com.workflow.orchestrator.jira.tasks

import com.intellij.openapi.diagnostic.Logger
import com.intellij.tasks.Task
import com.intellij.tasks.impl.BaseRepository
import com.intellij.tasks.impl.BaseRepositoryImpl
import com.intellij.util.xmlb.annotations.Tag
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * IntelliJ Task Repository that connects to Jira Server/Data Center.
 * Reuses the same REST API endpoints as JiraApiClient but via synchronous
 * calls (Task framework runs on background threads).
 */
@Tag("Jira (Workflow)")
class JiraTaskRepository : BaseRepositoryImpl {

    private val log = Logger.getInstance(JiraTaskRepository::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    // For serialization
    constructor() : super()

    constructor(other: JiraTaskRepository) : super(other)

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    override fun findTask(id: String): Task? {
        val response = executeGet("/rest/api/2/issue/$id?expand=issuelinks") ?: return null
        return try {
            val issue = json.decodeFromString<com.workflow.orchestrator.jira.api.dto.JiraIssue>(response)
            JiraTask(issue, url)
        } catch (e: Exception) {
            log.debug("[Jira:Tasks] Failed to parse issue $id: ${e.message}")
            null
        }
    }

    override fun getIssues(query: String?, offset: Int, limit: Int, withClosed: Boolean): Array<Task> {
        val jqlParts = mutableListOf<String>()
        if (!query.isNullOrBlank()) {
            jqlParts.add("text ~ \"${query.replace("\"", "\\\"")}\"")
        }
        if (!withClosed) {
            jqlParts.add("resolution = Unresolved")
        }
        jqlParts.add("assignee = currentUser()")
        val jql = URLEncoder.encode(jqlParts.joinToString(" AND ") + " ORDER BY updated DESC", "UTF-8")

        val path = "/rest/api/2/search?jql=$jql&startAt=$offset&maxResults=$limit"
        val response = executeGet(path) ?: return emptyArray()

        return try {
            val result = json.decodeFromString<com.workflow.orchestrator.jira.api.dto.JiraIssueSearchResult>(response)
            result.issues.map { JiraTask(it, url) }.toTypedArray()
        } catch (e: Exception) {
            log.debug("[Jira:Tasks] Failed to parse search results: ${e.message}")
            emptyArray()
        }
    }

    override fun createCancellableConnection(): CancellableConnection {
        return object : CancellableConnection() {
            override fun doTest() {
                val response = executeGet("/rest/api/2/myself")
                if (response == null) throw Exception("Cannot connect to Jira")
            }

            override fun cancel() {
                // OkHttpClient handles timeouts
            }
        }
    }

    override fun clone(): BaseRepository = JiraTaskRepository(this)

    override fun isConfigured(): Boolean = url.isNotBlank()

    override fun getUrl(): String = super.getUrl()

    private fun executeGet(path: String): String? {
        val fullUrl = "${url.trimEnd('/')}$path"
        val request = Request.Builder()
            .url(fullUrl)
            .addHeader("Authorization", "Bearer $password")
            .get()
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            response.use {
                if (it.isSuccessful) it.body?.string() else null
            }
        } catch (e: Exception) {
            log.debug("[Jira:Tasks] HTTP error for $path: ${e.message}")
            null
        }
    }
}
```

- [ ] **Step 6: Implement JiraTaskRepositoryType**

Create `jira/src/main/kotlin/com/workflow/orchestrator/jira/tasks/JiraTaskRepositoryType.kt`:

```kotlin
package com.workflow.orchestrator.jira.tasks

import com.intellij.icons.AllIcons
import com.intellij.tasks.TaskRepository
import com.intellij.tasks.TaskRepositoryType
import com.intellij.tasks.config.TaskRepositoryEditor
import com.intellij.tasks.impl.BaseRepositoryImpl
import com.intellij.util.Consumer
import javax.swing.Icon

/**
 * Registers a "Jira (Workflow)" repository type in IntelliJ's Task Management.
 * This allows using Tools > Tasks > Open Task to search and activate Jira tickets
 * with native branch creation and commit message prefixing support.
 */
class JiraTaskRepositoryType : TaskRepositoryType<JiraTaskRepository>() {

    override fun getName(): String = "Jira (Workflow)"

    override fun getIcon(): Icon = AllIcons.Providers.Jira

    override fun createRepository(): JiraTaskRepository = JiraTaskRepository()

    override fun getRepositoryClass(): Class<JiraTaskRepository> = JiraTaskRepository::class.java

    override fun createEditor(
        repository: JiraTaskRepository,
        project: com.intellij.openapi.project.Project,
        changeListener: Consumer<in JiraTaskRepository>
    ): TaskRepositoryEditor {
        return object : TaskRepositoryEditor {
            // Use the default server/username/password editor from IntelliJ
            // URL = Jira base URL, Password = PAT token
            override fun apply() {}
            override fun getPanel() = null
        }
    }
}
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `./gradlew :jira:test --tests '*JiraTaskMappingTest*'`
Expected: 8 tests PASS

- [ ] **Step 8: Create plugin-withTasks.xml**

Create `src/main/resources/META-INF/plugin-withTasks.xml`:

```xml
<idea-plugin>
    <extensions defaultExtensionNs="com.intellij">
        <tasks.repositoryType
            implementation="com.workflow.orchestrator.jira.tasks.JiraTaskRepositoryType"/>
    </extensions>
</idea-plugin>
```

- [ ] **Step 9: Add optional dependency in plugin.xml**

Add to the `<depends>` section:

```xml
<depends optional="true" config-file="plugin-withTasks.xml">com.intellij.tasks</depends>
```

- [ ] **Step 10: Add tasks bundled plugin to jira/build.gradle.kts**

Add to `dependencies { intellijPlatform { } }`:

```kotlin
bundledPlugin("com.intellij.tasks")
```

- [ ] **Step 11: Commit**

```bash
git add jira/src/main/kotlin/com/workflow/orchestrator/jira/tasks/
git add jira/src/test/kotlin/com/workflow/orchestrator/jira/tasks/
git add src/main/resources/META-INF/plugin-withTasks.xml
git add src/main/resources/META-INF/plugin.xml
git add jira/build.gradle.kts
git commit -m "feat(jira): integrate with IntelliJ Task/Issue Tracker system"
```

---

## Verification

After all tasks are complete:

- [ ] `./gradlew :core:test` — all core tests pass
- [ ] `./gradlew :jira:test` — all jira tests pass (including new tests)
- [ ] `./gradlew verifyPlugin` — no API compatibility issues
- [ ] `./gradlew runIde` — manual verification:
  - TODO tool window shows ticket IDs in comments
  - Editor tabs show `[PROJ-123]` suffix on modified files
  - Git Log has "Jira Ticket" column
  - After commit, transition suggestion appears (if ticket is "To Do")
  - Shift+Shift shows "Jira Tickets" tab
  - Commit dialog shows time tracking panel when ticket is active
  - Tools > Tasks > Open Task shows "Jira (Workflow)" server option
