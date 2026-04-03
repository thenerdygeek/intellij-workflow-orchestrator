# Quality Tab Enhancements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add security hotspots browsing, issue detail drill-down, coverage preview pane, and failed quality gate banner to the Quality tab.

**Architecture:** New focused panels (`IssueDetailPanel`, `CoveragePreviewPanel`, `GateStatusBanner`) composed into existing dashboard via `JBSplitter`. State flows through existing `StateFlow<SonarState>`. Each enhancement is independently testable and shippable.

**Tech Stack:** Kotlin, IntelliJ Platform SDK (JBSplitter, JBList, JBTable, JBColor, JBUI), MockK, Turbine, JUnit 5

**Spec:** `docs/superpowers/specs/2026-04-03-quality-tab-enhancements-design.md`

---

## File Structure

### New Files
| File | Responsibility |
|---|---|
| `core/src/main/kotlin/com/workflow/orchestrator/core/model/sonar/SonarRuleData.kt` | Rule data class for `/api/rules/show` |
| `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/IssueDetailPanel.kt` | Issue/hotspot detail view with code snippet, rule info, actions |
| `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/CoveragePreviewPanel.kt` | Uncovered-region preview with file metrics |
| `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/GateStatusBanner.kt` | Full-width failure banner with "Show Blocking Issues" action |
| `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/QualityListItem.kt` | Sealed interface for unified issue+hotspot list items |
| `sonar/src/test/kotlin/com/workflow/orchestrator/sonar/ui/QualityListItemTest.kt` | Tests for QualityListItem merging/filtering |
| `sonar/src/test/kotlin/com/workflow/orchestrator/sonar/ui/GateStatusBannerTest.kt` | Tests for gate banner visibility and condition mapping |
| `sonar/src/test/kotlin/com/workflow/orchestrator/sonar/ui/CoveragePreviewLogicTest.kt` | Tests for uncovered region extraction |
| `sonar/src/test/kotlin/com/workflow/orchestrator/sonar/service/SonarDataServiceHotspotsTest.kt` | Tests for hotspot fetch in refresh loop |

### Modified Files
| File | Change |
|---|---|
| `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/model/SonarState.kt` | Add `securityHotspots`, `selectedIssue`, `selectedCoverageFile` fields |
| `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarDataService.kt` | Fetch hotspots in parallel during `refreshWith()` |
| `core/src/main/kotlin/com/workflow/orchestrator/core/services/SonarService.kt` | Add `getRule()` method |
| `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarServiceImpl.kt` | Implement `getRule()` |
| `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/api/SonarApiClient.kt` | Add `getRule()` API call |
| `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/IssueListPanel.kt` | JBSplitter wrapping, hotspot support via QualityListItem, `applyPreFilter()` |
| `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/CoverageTablePanel.kt` | JBSplitter wrapping, directory grouping, search, summary bar |
| `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/OverviewPanel.kt` | Real hotspot count, enhanced gate card styling |
| `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/QualityDashboardPanel.kt` | Insert GateStatusBanner, wire cross-tab "Show Blocking Issues" |

---

## Task 1: Add Security Hotspots to SonarState and Data Service

**Files:**
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/model/SonarState.kt`
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarDataService.kt`
- Test: `sonar/src/test/kotlin/com/workflow/orchestrator/sonar/service/SonarDataServiceHotspotsTest.kt`

- [ ] **Step 1: Write the failing test for hotspot fetching**

```kotlin
// sonar/src/test/kotlin/com/workflow/orchestrator/sonar/service/SonarDataServiceHotspotsTest.kt
package com.workflow.orchestrator.sonar.service

import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.sonar.api.SonarApiClient
import com.workflow.orchestrator.sonar.api.dto.*
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SonarDataServiceHotspotsTest {

    private val apiClient = mockk<SonarApiClient>()

    private fun stubMinimalResponses() {
        coEvery { apiClient.getQualityGateStatus(any(), any()) } returns ApiResult.Success(
            SonarQualityGateDto("OK", emptyList())
        )
        coEvery { apiClient.getIssuesWithPaging(any(), any(), any()) } returns ApiResult.Success(
            SonarPagedIssuesDto(emptyList(), SonarPagingDto(1, 100, 0))
        )
        coEvery { apiClient.getBranches(any()) } returns ApiResult.Success(emptyList())
        coEvery { apiClient.getAnalysisTasks(any()) } returns ApiResult.Success(emptyList())
        coEvery { apiClient.getNewCodePeriod(any(), any()) } returns ApiResult.Error("N/A", ErrorType.NOT_FOUND)
        coEvery { apiClient.getProjectMeasures(any(), any()) } returns ApiResult.Success(emptyList())
        coEvery { apiClient.getMeasures(any(), any(), any()) } returns ApiResult.Success(emptyList())
    }

    @Test
    fun `refreshWith fetches security hotspots and stores them in state`() = runTest {
        stubMinimalResponses()
        coEvery { apiClient.getSecurityHotspots(any(), any()) } returns ApiResult.Success(
            SonarHotspotsResponseDto(
                listOf(
                    SonarHotspotDto(
                        key = "h1", message = "SQL Injection risk",
                        component = "proj:src/Db.kt", line = 42,
                        securityCategory = "sql-injection", vulnerabilityProbability = "HIGH",
                        status = "TO_REVIEW", resolution = null
                    ),
                    SonarHotspotDto(
                        key = "h2", message = "Weak crypto",
                        component = "proj:src/Crypto.kt", line = 10,
                        securityCategory = "weak-cryptography", vulnerabilityProbability = "MEDIUM",
                        status = "REVIEWED", resolution = "ACKNOWLEDGED"
                    )
                ),
                SonarPagingDto(1, 100, 2)
            )
        )

        val service = TestSonarDataService(apiClient)
        service.refreshWith(apiClient, "proj", "main")

        val state = service.stateFlow.value
        assertEquals(2, state.securityHotspots.size)
        assertEquals("SQL Injection risk", state.securityHotspots[0].message)
        assertEquals("HIGH", state.securityHotspots[0].probability)
        assertEquals("MEDIUM", state.securityHotspots[1].probability)
    }

    @Test
    fun `refreshWith handles hotspot API failure gracefully`() = runTest {
        stubMinimalResponses()
        coEvery { apiClient.getSecurityHotspots(any(), any()) } returns ApiResult.Error(
            "Developer Edition required", ErrorType.FORBIDDEN
        )

        val service = TestSonarDataService(apiClient)
        service.refreshWith(apiClient, "proj", "main")

        val state = service.stateFlow.value
        assertTrue(state.securityHotspots.isEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :sonar:test --tests "*.SonarDataServiceHotspotsTest" -x verifyPlugin`
Expected: FAIL — `securityHotspots` field does not exist on `SonarState`

- [ ] **Step 3: Add securityHotspots field to SonarState**

In `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/model/SonarState.kt`, add after `projectHealth`:

```kotlin
    val projectHealth: ProjectHealthMetrics = ProjectHealthMetrics(),
    val securityHotspots: List<com.workflow.orchestrator.core.model.sonar.SecurityHotspotData> = emptyList()
```

- [ ] **Step 4: Fetch hotspots in SonarDataService.refreshWith()**

In `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarDataService.kt`, inside `refreshWith()`:

Add a new deferred alongside existing parallel fetches (after `measuresDeferred`):

```kotlin
        val hotspotsDeferred = scope.async {
            try { client.getSecurityHotspots(projectKey, branch) }
            catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                log.info("[Sonar:Hotspots] Security hotspots not available (may require Developer Edition)")
                null
            }
        }
```

Add the await and mapping after `val measuresResult`:

```kotlin
        val hotspotsResult = hotspotsDeferred.await()

        val securityHotspots = when (hotspotsResult) {
            is ApiResult.Success -> hotspotsResult.data.hotspots.map { dto ->
                com.workflow.orchestrator.core.model.sonar.SecurityHotspotData(
                    key = dto.key,
                    message = dto.message,
                    component = dto.component,
                    line = dto.line,
                    securityCategory = dto.securityCategory,
                    probability = dto.vulnerabilityProbability,
                    status = dto.status,
                    resolution = dto.resolution
                )
            }
            is ApiResult.Error -> {
                log.info("[Sonar:Hotspots] Failed to fetch hotspots: ${hotspotsResult.message}")
                emptyList()
            }
            null -> emptyList()
        }
```

Add `securityHotspots = securityHotspots` to the `SonarState(...)` constructor call at the bottom of `refreshWith()`.

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :sonar:test --tests "*.SonarDataServiceHotspotsTest" -x verifyPlugin`
Expected: PASS

- [ ] **Step 6: Run full sonar test suite**

Run: `./gradlew :sonar:test -x verifyPlugin`
Expected: All existing tests still pass

- [ ] **Step 7: Commit**

```bash
git add sonar/src/main/kotlin/com/workflow/orchestrator/sonar/model/SonarState.kt \
       sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarDataService.kt \
       sonar/src/test/kotlin/com/workflow/orchestrator/sonar/service/SonarDataServiceHotspotsTest.kt
git commit -m "feat(sonar): fetch security hotspots in refresh loop and store in SonarState"
```

---

## Task 2: Create QualityListItem and Integrate Hotspots into IssueListPanel

**Files:**
- Create: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/QualityListItem.kt`
- Test: `sonar/src/test/kotlin/com/workflow/orchestrator/sonar/ui/QualityListItemTest.kt`
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/IssueListPanel.kt`
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/OverviewPanel.kt`

- [ ] **Step 1: Write tests for QualityListItem merging and filtering**

```kotlin
// sonar/src/test/kotlin/com/workflow/orchestrator/sonar/ui/QualityListItemTest.kt
package com.workflow.orchestrator.sonar.ui

import com.workflow.orchestrator.core.model.sonar.SecurityHotspotData
import com.workflow.orchestrator.sonar.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class QualityListItemTest {

    private val issue = MappedIssue(
        key = "i1", type = IssueType.BUG, severity = IssueSeverity.CRITICAL,
        message = "NPE", rule = "java:S2259", filePath = "src/File.kt",
        startLine = 10, endLine = 10, startOffset = 0, endOffset = 0,
        effort = "30min", creationDate = "2026-01-01T00:00:00Z"
    )

    private val hotspot = SecurityHotspotData(
        key = "h1", message = "SQL Injection", component = "proj:src/Db.kt",
        line = 42, securityCategory = "sql-injection", probability = "HIGH",
        status = "TO_REVIEW", resolution = null
    )

    @Test
    fun `merge issues and hotspots into unified list`() {
        val items = QualityListItem.merge(listOf(issue), listOf(hotspot))
        assertEquals(2, items.size)
        assertTrue(items[0] is QualityListItem.IssueItem)
        assertTrue(items[1] is QualityListItem.HotspotItem)
    }

    @Test
    fun `filter by type Bug excludes hotspots`() {
        val items = QualityListItem.merge(listOf(issue), listOf(hotspot))
        val filtered = items.filter { it.matchesTypeFilter("Bug") }
        assertEquals(1, filtered.size)
        assertTrue(filtered[0] is QualityListItem.IssueItem)
    }

    @Test
    fun `filter by type Security Hotspot excludes issues`() {
        val items = QualityListItem.merge(listOf(issue), listOf(hotspot))
        val filtered = items.filter { it.matchesTypeFilter("Security Hotspot") }
        assertEquals(1, filtered.size)
        assertTrue(filtered[0] is QualityListItem.HotspotItem)
    }

    @Test
    fun `filter by type All includes everything`() {
        val items = QualityListItem.merge(listOf(issue), listOf(hotspot))
        val filtered = items.filter { it.matchesTypeFilter("All") }
        assertEquals(2, filtered.size)
    }

    @Test
    fun `hotspot displayFileName extracts from component`() {
        val item = QualityListItem.HotspotItem(hotspot)
        assertEquals("Db.kt", item.displayFileName)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :sonar:test --tests "*.QualityListItemTest" -x verifyPlugin`
Expected: FAIL — `QualityListItem` does not exist

- [ ] **Step 3: Create QualityListItem sealed interface**

```kotlin
// sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/QualityListItem.kt
package com.workflow.orchestrator.sonar.ui

import com.workflow.orchestrator.core.model.sonar.SecurityHotspotData
import com.workflow.orchestrator.sonar.model.IssueType
import com.workflow.orchestrator.sonar.model.MappedIssue

sealed interface QualityListItem {

    val displayMessage: String
    val displayFileName: String
    val displayLine: Int?

    fun matchesTypeFilter(filter: String): Boolean
    fun matchesSeverityFilter(filter: String): Boolean

    data class IssueItem(val issue: MappedIssue) : QualityListItem {
        override val displayMessage get() = issue.message
        override val displayFileName get() = java.io.File(issue.filePath).name
        override val displayLine get() = issue.startLine

        override fun matchesTypeFilter(filter: String) = when (filter) {
            "All" -> true
            "Bug" -> issue.type == IssueType.BUG
            "Vulnerability" -> issue.type == IssueType.VULNERABILITY
            "Code Smell" -> issue.type == IssueType.CODE_SMELL
            else -> false
        }

        override fun matchesSeverityFilter(filter: String) = when (filter) {
            "All" -> true
            else -> issue.severity.name.equals(filter, ignoreCase = true)
        }
    }

    data class HotspotItem(val hotspot: SecurityHotspotData) : QualityListItem {
        override val displayMessage get() = hotspot.message
        override val displayFileName: String
            get() = hotspot.component.substringAfterLast(':').substringAfterLast('/')
        override val displayLine get() = hotspot.line

        override fun matchesTypeFilter(filter: String) = when (filter) {
            "All", "Security Hotspot" -> true
            else -> false
        }

        override fun matchesSeverityFilter(filter: String) = when (filter) {
            "All" -> true
            else -> false // hotspots use probability, not severity
        }
    }

    companion object {
        fun merge(issues: List<MappedIssue>, hotspots: List<SecurityHotspotData>): List<QualityListItem> {
            return issues.map { IssueItem(it) } + hotspots.map { HotspotItem(it) }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :sonar:test --tests "*.QualityListItemTest" -x verifyPlugin`
Expected: PASS

- [ ] **Step 5: Refactor IssueListPanel to use QualityListItem**

In `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/IssueListPanel.kt`:

1. Change `listModel` from `DefaultListModel<MappedIssue>` to `DefaultListModel<QualityListItem>`
2. Change `issueList` from `JBList<MappedIssue>` to `JBList<QualityListItem>`
3. Add a field: `private var allHotspots: List<SecurityHotspotData> = emptyList()`
4. Add `updateHotspots(hotspots: List<SecurityHotspotData>)` method that stores hotspots and calls `applyFilters()`
5. In `applyFilters()`: merge `allIssues` and `allHotspots` via `QualityListItem.merge()`, then apply type/severity filters using the `matchesTypeFilter`/`matchesSeverityFilter` methods
6. Update `IssueListCellRenderer` to implement `ListCellRenderer<QualityListItem>` — check `when (value)` to render `IssueItem` with severity badge or `HotspotItem` with probability badge
7. Update double-click handler: for `IssueItem` call existing `navigateToIssue()`, for `HotspotItem` navigate using component path
8. Update context menu: for `HotspotItem` show "Review in SonarQube" (opens browser) instead of "Fix with AI Agent"

- [ ] **Step 6: Update OverviewPanel hotspot count**

In `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/OverviewPanel.kt`, change the `update()` method's issues section. Replace:

```kotlin
        val hotspots = state.activeIssues.count { it.type == IssueType.SECURITY_HOTSPOT }
```

With:

```kotlin
        val hotspots = state.securityHotspots.size
```

- [ ] **Step 7: Wire hotspots in QualityDashboardPanel**

In `QualityDashboardPanel.kt`, in the `updateUI()` method where `issuesStale` is checked, after `issueListPanel.update(state.activeIssues, ...)` add:

```kotlin
                    issueListPanel.updateHotspots(state.securityHotspots)
```

Also add a `hotspotsChanged` check:

```kotlin
        val hotspotsChanged = prev == null || prev.securityHotspots != state.securityHotspots
```

And mark `issuesStale = true` when `hotspotsChanged`.

- [ ] **Step 8: Run full sonar test suite**

Run: `./gradlew :sonar:test -x verifyPlugin`
Expected: All tests pass

- [ ] **Step 9: Commit**

```bash
git add sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/QualityListItem.kt \
       sonar/src/test/kotlin/com/workflow/orchestrator/sonar/ui/QualityListItemTest.kt \
       sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/IssueListPanel.kt \
       sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/OverviewPanel.kt \
       sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/QualityDashboardPanel.kt
git commit -m "feat(sonar): integrate security hotspots into Issues tab with unified QualityListItem"
```

---

## Task 3: Add getRule() API for Issue Detail Panel

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/model/sonar/SonarRuleData.kt`
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/services/SonarService.kt`
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/api/SonarApiClient.kt`
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarServiceImpl.kt`

- [ ] **Step 1: Create SonarRuleData model**

```kotlin
// core/src/main/kotlin/com/workflow/orchestrator/core/model/sonar/SonarRuleData.kt
package com.workflow.orchestrator.core.model.sonar

import kotlinx.serialization.Serializable

@Serializable
data class SonarRuleData(
    val ruleKey: String,
    val name: String,
    val description: String,
    val remediation: String?,
    val tags: List<String> = emptyList()
)
```

- [ ] **Step 2: Add getRule() to SonarService interface**

In `core/src/main/kotlin/com/workflow/orchestrator/core/services/SonarService.kt`, add after the `getIssuesPaged` method:

```kotlin
    /** Get rule details (name, description, remediation) for a specific rule key. */
    suspend fun getRule(ruleKey: String, repoName: String? = null): ToolResult<SonarRuleData>
```

Add import: `import com.workflow.orchestrator.core.model.sonar.SonarRuleData`

- [ ] **Step 3: Add getRule() to SonarApiClient**

In `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/api/SonarApiClient.kt`, add:

```kotlin
    suspend fun getRule(ruleKey: String): ApiResult<SonarRuleDto> {
        log.info("[Sonar:API] GET /api/rules/show for rule '$ruleKey'")
        val url = "$baseUrl/api/rules/show?key=${URLEncoder.encode(ruleKey, "UTF-8")}"
        return executeGet(url) { body ->
            val json = Json { ignoreUnknownKeys = true }
            val response = json.decodeFromString<SonarRuleShowResponseDto>(body)
            response.rule
        }
    }
```

Add DTO classes in `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/api/dto/SonarDtos.kt`:

```kotlin
@Serializable
data class SonarRuleShowResponseDto(
    val rule: SonarRuleDto
)

@Serializable
data class SonarRuleDto(
    val key: String,
    val name: String,
    val htmlDesc: String? = null,
    val mdDesc: String? = null,
    val remFnBaseEffort: String? = null,
    val tags: List<String> = emptyList()
)
```

- [ ] **Step 4: Implement getRule() in SonarServiceImpl**

In `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarServiceImpl.kt`, add:

```kotlin
    override suspend fun getRule(ruleKey: String, repoName: String?): ToolResult<SonarRuleData> {
        val api = resolveApiClient(repoName) ?: return ToolResult.error(
            summary = "SonarQube not configured. Cannot fetch rule details.",
            hint = "Configure SonarQube URL and token in Settings > CI/CD."
        )
        return when (val result = api.getRule(ruleKey)) {
            is ApiResult.Success -> {
                val dto = result.data
                val data = SonarRuleData(
                    ruleKey = dto.key,
                    name = dto.name,
                    description = dto.mdDesc ?: dto.htmlDesc ?: "",
                    remediation = dto.remFnBaseEffort,
                    tags = dto.tags
                )
                ToolResult.success(data = data, summary = "Rule ${dto.key}: ${dto.name}")
            }
            is ApiResult.Error -> {
                ToolResult.error(summary = "Error fetching rule $ruleKey: ${result.message}")
            }
        }
    }
```

- [ ] **Step 5: Run full test suite**

Run: `./gradlew :sonar:test :core:test -x verifyPlugin`
Expected: All tests pass (new method has no callers yet in tests)

- [ ] **Step 6: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/model/sonar/SonarRuleData.kt \
       core/src/main/kotlin/com/workflow/orchestrator/core/services/SonarService.kt \
       sonar/src/main/kotlin/com/workflow/orchestrator/sonar/api/SonarApiClient.kt \
       sonar/src/main/kotlin/com/workflow/orchestrator/sonar/api/dto/SonarDtos.kt \
       sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarServiceImpl.kt
git commit -m "feat(sonar): add getRule() API for issue detail drill-down"
```

---

## Task 4: Create IssueDetailPanel (Split Pane)

**Files:**
- Create: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/IssueDetailPanel.kt`
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/model/SonarState.kt`
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/IssueListPanel.kt`

- [ ] **Step 1: Add selectedIssue to SonarState**

In `SonarState.kt`, add after `securityHotspots`:

```kotlin
    val selectedIssue: MappedIssue? = null
```

- [ ] **Step 2: Create IssueDetailPanel**

```kotlin
// sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/IssueDetailPanel.kt
package com.workflow.orchestrator.sonar.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.ai.AgentChatRedirect
import com.workflow.orchestrator.core.model.sonar.SecurityHotspotData
import com.workflow.orchestrator.core.model.sonar.SonarRuleData
import com.workflow.orchestrator.core.ui.StatusColors
import com.workflow.orchestrator.sonar.model.*
import com.workflow.orchestrator.sonar.service.SonarDataService
import kotlinx.coroutines.*
import java.awt.*
import java.util.concurrent.ConcurrentHashMap
import javax.swing.*

class IssueDetailPanel(
    private val project: Project
) : JPanel(BorderLayout()), Disposable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dataService = SonarDataService.getInstance(project)
    private val ruleCache = ConcurrentHashMap<String, SonarRuleData>()

    // Header
    private val titleLabel = JBLabel("Select an issue to view details").apply {
        font = font.deriveFont(Font.BOLD, JBUI.scale(13).toFloat())
        border = JBUI.Borders.empty(8)
    }

    // Metadata
    private val metadataLabel = JBLabel("").apply {
        font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
        foreground = StatusColors.SECONDARY_TEXT
        border = JBUI.Borders.empty(0, 8, 4, 8)
    }

    // Code snippet
    private val codeArea = JTextArea().apply {
        isEditable = false
        font = Font("JetBrains Mono", Font.PLAIN, JBUI.scale(12))
        background = JBColor(Color(0xF5, 0xF5, 0xF5), Color(0x1E, 0x1E, 0x1E))
        border = JBUI.Borders.empty(4)
    }

    // Rule info
    private val ruleLabel = JBLabel("").apply {
        font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
        border = JBUI.Borders.empty(4, 8)
    }

    // Actions
    private val fixWithAgentButton = JButton("Fix with AI Agent").apply {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }
    private val openInEditorButton = JButton("Open in Editor").apply {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }
    private val prevButton = JButton("\u25C0 Prev")
    private val nextButton = JButton("Next \u25B6")

    var onNavigatePrev: (() -> Unit)? = null
    var onNavigateNext: (() -> Unit)? = null

    private var currentItem: QualityListItem? = null

    init {
        border = JBUI.Borders.empty(4)

        val headerPanel = JPanel(BorderLayout()).apply {
            add(titleLabel, BorderLayout.CENTER)
            add(metadataLabel, BorderLayout.SOUTH)
        }

        val codeScrollPane = JBScrollPane(codeArea).apply {
            border = JBUI.Borders.empty(4, 8)
            preferredSize = Dimension(0, JBUI.scale(200))
        }

        val actionsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            add(fixWithAgentButton)
            add(openInEditorButton)
            add(Box.createHorizontalStrut(16))
            add(prevButton)
            add(nextButton)
        }

        val contentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(headerPanel)
            add(codeScrollPane)
            add(ruleLabel)
        }

        add(JBScrollPane(contentPanel), BorderLayout.CENTER)
        add(actionsPanel, BorderLayout.SOUTH)

        // Wire button actions
        openInEditorButton.addActionListener { navigateToEditor() }
        fixWithAgentButton.addActionListener { fixWithAgent() }
        prevButton.addActionListener { onNavigatePrev?.invoke() }
        nextButton.addActionListener { onNavigateNext?.invoke() }

        showEmptyState()
    }

    fun showItem(item: QualityListItem) {
        currentItem = item
        when (item) {
            is QualityListItem.IssueItem -> showIssue(item.issue)
            is QualityListItem.HotspotItem -> showHotspot(item.hotspot)
        }
    }

    private fun showIssue(issue: MappedIssue) {
        val severityColor = when (issue.severity) {
            IssueSeverity.BLOCKER, IssueSeverity.CRITICAL -> StatusColors.htmlColor(StatusColors.ERROR)
            IssueSeverity.MAJOR, IssueSeverity.MINOR -> StatusColors.htmlColor(StatusColors.WARNING)
            IssueSeverity.INFO -> StatusColors.htmlColor(StatusColors.INFO)
        }
        titleLabel.text = "<html><font color='$severityColor'>[${issue.severity}]</font> ${issue.type.name.replace("_", " ")} — ${issue.message}</html>"

        metadataLabel.text = "${issue.filePath}:${issue.startLine}  |  Rule: ${issue.rule}  |  ${issue.effort ?: ""}  |  ${issue.creationDate ?: ""}"

        fixWithAgentButton.isVisible = true
        fixWithAgentButton.isEnabled = AgentChatRedirect.getInstance() != null

        loadCodeSnippet(issue.filePath, issue.startLine)
        loadRuleInfo(issue.rule)
    }

    private fun showHotspot(hotspot: SecurityHotspotData) {
        val probColor = when (hotspot.probability) {
            "HIGH" -> StatusColors.htmlColor(StatusColors.ERROR)
            "MEDIUM" -> StatusColors.htmlColor(StatusColors.WARNING)
            else -> StatusColors.htmlColor(StatusColors.INFO)
        }
        val file = hotspot.component.substringAfterLast(':').substringAfterLast('/')
        titleLabel.text = "<html><font color='$probColor'>[${hotspot.probability}]</font> ${hotspot.securityCategory} — ${hotspot.message}</html>"

        val reviewStatus = hotspot.resolution ?: hotspot.status.replace("_", " ")
        metadataLabel.text = "$file:${hotspot.line ?: "?"}  |  Status: $reviewStatus  |  Category: ${hotspot.securityCategory}"

        fixWithAgentButton.isVisible = false

        val relativePath = hotspot.component.substringAfter(':')
        loadCodeSnippet(relativePath, hotspot.line ?: 1)
        ruleLabel.text = ""
    }

    fun showEmptyState() {
        currentItem = null
        titleLabel.text = "Select an issue to view details"
        metadataLabel.text = ""
        codeArea.text = ""
        ruleLabel.text = ""
        fixWithAgentButton.isVisible = false
    }

    private fun loadCodeSnippet(filePath: String, line: Int) {
        codeArea.text = "Loading..."
        scope.launch {
            val coverage = dataService.getLineCoverage(filePath)
            val basePath = project.basePath ?: return@launch
            val file = java.io.File(basePath, filePath)
            if (!file.exists()) {
                withContext(Dispatchers.Main) { codeArea.text = "File not found: $filePath" }
                return@launch
            }
            val lines = file.readLines()
            val startLine = maxOf(0, line - 6)
            val endLine = minOf(lines.size, line + 5)
            val snippet = lines.subList(startLine, endLine).mapIndexed { idx, text ->
                val lineNum = startLine + idx + 1
                val marker = when {
                    lineNum == line -> ">>>"
                    coverage[lineNum] == LineCoverageStatus.UNCOVERED -> " ! "
                    coverage[lineNum] == LineCoverageStatus.PARTIAL -> " ~ "
                    else -> "   "
                }
                "$marker ${lineNum.toString().padStart(4)} | $text"
            }.joinToString("\n")

            withContext(Dispatchers.Main) { codeArea.text = snippet }
        }
    }

    private fun loadRuleInfo(ruleKey: String) {
        val cached = ruleCache[ruleKey]
        if (cached != null) {
            ruleLabel.text = "<html><b>${cached.name}</b><br/>${cached.remediation ?: cached.description.take(200)}</html>"
            return
        }
        ruleLabel.text = "Loading rule info..."
        scope.launch {
            val sonarService = project.getService(com.workflow.orchestrator.core.services.SonarService::class.java)
            if (sonarService != null) {
                val result = sonarService.getRule(ruleKey)
                if (!result.isError && result.data != null) {
                    ruleCache[ruleKey] = result.data
                    withContext(Dispatchers.Main) {
                        ruleLabel.text = "<html><b>${result.data.name}</b><br/>${result.data.remediation ?: result.data.description.take(200)}</html>"
                    }
                } else {
                    withContext(Dispatchers.Main) { ruleLabel.text = "Rule: $ruleKey" }
                }
            }
        }
    }

    private fun navigateToEditor() {
        val item = currentItem ?: return
        val basePath = project.basePath ?: return
        when (item) {
            is QualityListItem.IssueItem -> {
                val vf = LocalFileSystem.getInstance().findFileByPath(java.io.File(basePath, item.issue.filePath).path) ?: return
                OpenFileDescriptor(project, vf, item.issue.startLine - 1, item.issue.startOffset).navigate(true)
            }
            is QualityListItem.HotspotItem -> {
                val path = item.hotspot.component.substringAfter(':')
                val vf = LocalFileSystem.getInstance().findFileByPath(java.io.File(basePath, path).path) ?: return
                OpenFileDescriptor(project, vf, (item.hotspot.line ?: 1) - 1, 0).navigate(true)
            }
        }
    }

    private fun fixWithAgent() {
        val item = currentItem as? QualityListItem.IssueItem ?: return
        val issue = item.issue
        val basePath = project.basePath ?: return
        val absolutePath = java.io.File(basePath, issue.filePath).absolutePath
        navigateToEditor()
        val prompt = buildString {
            appendLine("Fix this SonarQube issue:")
            appendLine()
            appendLine("**Rule:** ${issue.rule}")
            appendLine("**Message:** ${issue.message}")
            appendLine("**File:** ${issue.filePath}")
            appendLine("**Line:** ${issue.startLine}")
            appendLine()
            appendLine("Read the file, understand the context, apply a minimal fix that resolves the issue without changing behavior, and verify it compiles with diagnostics.")
        }
        AgentChatRedirect.getInstance()?.sendToAgent(project, prompt, listOf(absolutePath))
    }

    override fun dispose() {
        scope.cancel()
    }
}
```

- [ ] **Step 3: Wrap IssueListPanel with JBSplitter for detail pane**

In `IssueListPanel.kt`, add after the `init` block the splitter setup:

1. Add field: `private val detailPanel = IssueDetailPanel(project).also { com.intellij.openapi.util.Disposer.register(this, it) }`
2. After building the filter toolbar and scrollPane in `init`, wrap them in a `JBSplitter`:

```kotlin
        // Wrap list + detail in split pane
        val listContent = JPanel(BorderLayout()).apply {
            add(filterPanel, BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
        }
        val splitter = com.intellij.ui.JBSplitter(false, 0.4f).apply {
            firstComponent = listContent
            secondComponent = detailPanel
        }
        add(splitter, BorderLayout.CENTER)
```

3. Add single-click selection listener to `issueList`:

```kotlin
        issueList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                val selected = issueList.selectedValue
                if (selected != null) {
                    detailPanel.showItem(selected)
                } else {
                    detailPanel.showEmptyState()
                }
            }
        }
```

4. Wire prev/next navigation:

```kotlin
        detailPanel.onNavigatePrev = {
            val idx = issueList.selectedIndex
            if (idx > 0) issueList.selectedIndex = idx - 1
        }
        detailPanel.onNavigateNext = {
            val idx = issueList.selectedIndex
            if (idx < listModel.size - 1) issueList.selectedIndex = idx + 1
        }
```

- [ ] **Step 4: Run full sonar test suite**

Run: `./gradlew :sonar:test -x verifyPlugin`
Expected: All tests pass

- [ ] **Step 5: Commit**

```bash
git add sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/IssueDetailPanel.kt \
       sonar/src/main/kotlin/com/workflow/orchestrator/sonar/model/SonarState.kt \
       sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/IssueListPanel.kt
git commit -m "feat(sonar): add issue detail split pane with code snippet and rule info"
```

---

## Task 5: Create CoveragePreviewPanel and Enhance Coverage Table

**Files:**
- Create: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/CoveragePreviewPanel.kt`
- Test: `sonar/src/test/kotlin/com/workflow/orchestrator/sonar/ui/CoveragePreviewLogicTest.kt`
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/model/SonarState.kt`
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/CoverageTablePanel.kt`

- [ ] **Step 1: Write test for uncovered region extraction**

```kotlin
// sonar/src/test/kotlin/com/workflow/orchestrator/sonar/ui/CoveragePreviewLogicTest.kt
package com.workflow.orchestrator.sonar.ui

import com.workflow.orchestrator.sonar.model.LineCoverageStatus
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CoveragePreviewLogicTest {

    @Test
    fun `extractUncoveredRegions finds contiguous uncovered blocks with context`() {
        val lines = (1..20).map { "line $it of code" }
        val statuses = mapOf(
            1 to LineCoverageStatus.COVERED,
            2 to LineCoverageStatus.COVERED,
            3 to LineCoverageStatus.UNCOVERED,
            4 to LineCoverageStatus.UNCOVERED,
            5 to LineCoverageStatus.COVERED,
            10 to LineCoverageStatus.PARTIAL,
            15 to LineCoverageStatus.UNCOVERED
        )

        val regions = CoveragePreviewPanel.extractUncoveredRegions(lines, statuses, contextLines = 2)

        // Should find 3 uncovered/partial locations grouped into regions
        assertTrue(regions.isNotEmpty())
        // First region should include lines 1-6 (context around lines 3-4)
        val first = regions[0]
        assertTrue(first.lines.any { it.status == LineCoverageStatus.UNCOVERED })
    }

    @Test
    fun `extractUncoveredRegions returns empty for fully covered file`() {
        val lines = (1..5).map { "line $it" }
        val statuses = (1..5).associate { it to LineCoverageStatus.COVERED }

        val regions = CoveragePreviewPanel.extractUncoveredRegions(lines, statuses, contextLines = 2)
        assertTrue(regions.isEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :sonar:test --tests "*.CoveragePreviewLogicTest" -x verifyPlugin`
Expected: FAIL — `CoveragePreviewPanel` does not exist

- [ ] **Step 3: Add selectedCoverageFile to SonarState**

In `SonarState.kt`, add after `selectedIssue`:

```kotlin
    val selectedCoverageFile: String? = null
```

- [ ] **Step 4: Create CoveragePreviewPanel**

```kotlin
// sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/CoveragePreviewPanel.kt
package com.workflow.orchestrator.sonar.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.ui.StatusColors
import com.workflow.orchestrator.sonar.model.FileCoverageData
import com.workflow.orchestrator.sonar.model.LineCoverageStatus
import com.workflow.orchestrator.sonar.service.SonarDataService
import kotlinx.coroutines.*
import java.awt.*
import javax.swing.*

class CoveragePreviewPanel(
    private val project: Project
) : JPanel(BorderLayout()), Disposable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dataService = SonarDataService.getInstance(project)

    private val metricsLabel = JBLabel("").apply {
        font = font.deriveFont(Font.BOLD, JBUI.scale(11).toFloat())
        border = JBUI.Borders.empty(6, 8)
    }

    private val codeArea = JTextArea().apply {
        isEditable = false
        font = Font("JetBrains Mono", Font.PLAIN, JBUI.scale(11))
        background = JBColor(Color(0xF8, 0xF8, 0xF8), Color(0x1A, 0x1A, 0x1A))
        border = JBUI.Borders.empty(4)
    }

    private val filePathLabel = JBLabel("").apply {
        foreground = StatusColors.SECONDARY_TEXT
        font = font.deriveFont(Font.PLAIN, JBUI.scale(10).toFloat())
        border = JBUI.Borders.empty(4, 8)
    }

    private val openInEditorButton = JButton("Open in Editor").apply {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    private var currentFilePath: String? = null

    init {
        border = JBUI.Borders.empty(4)

        val footerPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
            add(filePathLabel)
            add(openInEditorButton)
        }

        add(metricsLabel, BorderLayout.NORTH)
        add(JBScrollPane(codeArea), BorderLayout.CENTER)
        add(footerPanel, BorderLayout.SOUTH)

        openInEditorButton.addActionListener {
            val path = currentFilePath ?: return@addActionListener
            val basePath = project.basePath ?: return@addActionListener
            val vf = LocalFileSystem.getInstance().findFileByPath(java.io.File(basePath, path).path) ?: return@addActionListener
            OpenFileDescriptor(project, vf, 0, 0).navigate(true)
        }

        showEmptyState()
    }

    fun showFile(filePath: String, coverageData: FileCoverageData) {
        currentFilePath = filePath
        metricsLabel.text = "Line: %.1f%%  |  Branch: %.1f%%  |  Uncovered: %d lines, %d conds  |  Complexity: %d  |  Cognitive: %d".format(
            coverageData.lineCoverage, coverageData.branchCoverage,
            coverageData.uncoveredLines, coverageData.uncoveredConditions,
            coverageData.complexity, coverageData.cognitiveComplexity
        )
        filePathLabel.text = filePath

        codeArea.text = "Loading coverage..."
        scope.launch {
            val statuses = dataService.getLineCoverage(filePath)
            val basePath = project.basePath ?: return@launch
            val file = java.io.File(basePath, filePath)
            if (!file.exists()) {
                withContext(Dispatchers.Main) { codeArea.text = "File not found: $filePath" }
                return@launch
            }
            val lines = file.readLines()
            val regions = extractUncoveredRegions(lines, statuses)

            val text = if (regions.isEmpty()) {
                "All lines covered."
            } else {
                regions.joinToString("\n\n--- gap ---\n\n") { region ->
                    region.lines.joinToString("\n") { line ->
                        val marker = when (line.status) {
                            LineCoverageStatus.UNCOVERED -> "\u2716"
                            LineCoverageStatus.PARTIAL -> "~"
                            LineCoverageStatus.COVERED -> "\u2713"
                            null -> " "
                        }
                        "$marker ${line.lineNumber.toString().padStart(4)} | ${line.text}"
                    }
                }
            }

            withContext(Dispatchers.Main) { codeArea.text = text }
        }
    }

    fun showEmptyState() {
        currentFilePath = null
        metricsLabel.text = "Select a file to preview coverage"
        codeArea.text = ""
        filePathLabel.text = ""
    }

    data class CoverageRegionLine(
        val lineNumber: Int,
        val text: String,
        val status: LineCoverageStatus?
    )

    data class CoverageRegion(val lines: List<CoverageRegionLine>)

    companion object {
        fun extractUncoveredRegions(
            lines: List<String>,
            statuses: Map<Int, LineCoverageStatus>,
            contextLines: Int = 3
        ): List<CoverageRegion> {
            // Find all uncovered/partial line numbers
            val uncoveredLines = statuses.entries
                .filter { it.value == LineCoverageStatus.UNCOVERED || it.value == LineCoverageStatus.PARTIAL }
                .map { it.key }
                .sorted()

            if (uncoveredLines.isEmpty()) return emptyList()

            // Group into contiguous regions (with context)
            val regions = mutableListOf<CoverageRegion>()
            var regionStart = maxOf(1, uncoveredLines.first() - contextLines)
            var regionEnd = minOf(lines.size, uncoveredLines.first() + contextLines)

            for (i in 1 until uncoveredLines.size) {
                val lineNum = uncoveredLines[i]
                val newStart = maxOf(1, lineNum - contextLines)
                if (newStart <= regionEnd + 1) {
                    // Merge with current region
                    regionEnd = minOf(lines.size, lineNum + contextLines)
                } else {
                    // Emit current region and start new one
                    regions.add(buildRegion(lines, statuses, regionStart, regionEnd))
                    regionStart = newStart
                    regionEnd = minOf(lines.size, lineNum + contextLines)
                }
            }
            regions.add(buildRegion(lines, statuses, regionStart, regionEnd))

            return regions
        }

        private fun buildRegion(
            lines: List<String>,
            statuses: Map<Int, LineCoverageStatus>,
            start: Int, end: Int
        ): CoverageRegion {
            val regionLines = (start..end).map { lineNum ->
                CoverageRegionLine(
                    lineNumber = lineNum,
                    text = lines.getOrElse(lineNum - 1) { "" },
                    status = statuses[lineNum]
                )
            }
            return CoverageRegion(regionLines)
        }
    }

    override fun dispose() {
        scope.cancel()
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :sonar:test --tests "*.CoveragePreviewLogicTest" -x verifyPlugin`
Expected: PASS

- [ ] **Step 6: Enhance CoverageTablePanel with splitter, summary bar, and search**

In `CoverageTablePanel.kt`:

1. Add fields:

```kotlin
    private val previewPanel = CoveragePreviewPanel(project)
    private val searchField = com.intellij.ui.SearchTextField(false).apply {
        textEditor.emptyText.text = "Filter files..."
    }
    private val summaryLabel = JBLabel("").apply {
        font = font.deriveFont(Font.PLAIN, JBUI.scale(10).toFloat())
        foreground = StatusColors.SECONDARY_TEXT
        border = JBUI.Borders.empty(4, 8)
    }
    private var allCoverageData: List<FileCoverageData> = emptyList()
```

2. In `init`, wrap existing content in a `JBSplitter`:

```kotlin
        val topPanel = JPanel(BorderLayout()).apply {
            val headerRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
                add(summaryLabel)
                add(searchField)
            }
            add(headerRow, BorderLayout.NORTH)
            add(paginationWarning, BorderLayout.CENTER)
        }

        val tableContent = JPanel(BorderLayout()).apply {
            add(topPanel, BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
        }

        val splitter = com.intellij.ui.JBSplitter(true, 0.6f).apply {
            firstComponent = tableContent
            secondComponent = previewPanel
        }
        add(splitter, BorderLayout.CENTER)
```

3. Add single-click listener to table:

```kotlin
        table.selectionModel.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                val row = table.selectedRow
                if (row >= 0) {
                    val modelRow = table.convertRowIndexToModel(row)
                    val filePath = tableModel.getFilePath(modelRow)
                    val data = allCoverageData.find { it.filePath == filePath }
                    if (data != null) previewPanel.showFile(filePath, data)
                } else {
                    previewPanel.showEmptyState()
                }
            }
        }
```

4. Add search filtering via `searchField.addDocumentListener`:

```kotlin
        searchField.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = filterTable()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = filterTable()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = filterTable()
        })
```

5. Add `filterTable()` method and update `update()` to store `allCoverageData` and update `summaryLabel`:

```kotlin
    private fun filterTable() {
        val query = searchField.text.lowercase()
        val filtered = if (query.isBlank()) allCoverageData
        else allCoverageData.filter { it.filePath.lowercase().contains(query) }
        tableModel.setData(filtered, tableModel.isNewCodeMode())
    }
```

In `update()`, add summary bar update:

```kotlin
        allCoverageData = data
        val avgCoverage = if (data.isNotEmpty()) data.map { it.lineCoverage }.average() else 0.0
        val belowThreshold = data.count { it.lineCoverage < 80.0 }
        summaryLabel.text = "${data.size} files  |  %.1f%% avg  |  $belowThreshold below 80%%".format(avgCoverage)
```

- [ ] **Step 7: Run full sonar test suite**

Run: `./gradlew :sonar:test -x verifyPlugin`
Expected: All tests pass

- [ ] **Step 8: Commit**

```bash
git add sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/CoveragePreviewPanel.kt \
       sonar/src/test/kotlin/com/workflow/orchestrator/sonar/ui/CoveragePreviewLogicTest.kt \
       sonar/src/main/kotlin/com/workflow/orchestrator/sonar/model/SonarState.kt \
       sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/CoverageTablePanel.kt
git commit -m "feat(sonar): add coverage preview pane with uncovered region extraction"
```

---

## Task 6: Create GateStatusBanner and Wire Cross-Tab Actions

**Files:**
- Create: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/GateStatusBanner.kt`
- Test: `sonar/src/test/kotlin/com/workflow/orchestrator/sonar/ui/GateStatusBannerTest.kt`
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/IssueListPanel.kt`
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/OverviewPanel.kt`
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/QualityDashboardPanel.kt`

- [ ] **Step 1: Write test for gate condition-to-filter mapping**

```kotlin
// sonar/src/test/kotlin/com/workflow/orchestrator/sonar/ui/GateStatusBannerTest.kt
package com.workflow.orchestrator.sonar.ui

import com.workflow.orchestrator.sonar.model.GateCondition
import com.workflow.orchestrator.sonar.model.IssueType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GateStatusBannerTest {

    @Test
    fun `mapConditionToFilter maps new_bugs to Bug type`() {
        val cond = GateCondition("new_bugs", "GT", "0", "3", passed = false)
        val filter = GateStatusBanner.mapConditionToFilter(cond)
        assertEquals(IssueType.BUG, filter?.issueType)
        assertTrue(filter?.newCodeMode == true)
    }

    @Test
    fun `mapConditionToFilter maps new_vulnerabilities to Vulnerability type`() {
        val cond = GateCondition("new_vulnerabilities", "GT", "0", "2", passed = false)
        val filter = GateStatusBanner.mapConditionToFilter(cond)
        assertEquals(IssueType.VULNERABILITY, filter?.issueType)
    }

    @Test
    fun `mapConditionToFilter maps new_security_hotspots_reviewed to Hotspot type`() {
        val cond = GateCondition("new_security_hotspots_reviewed", "LT", "100", "75.0", passed = false)
        val filter = GateStatusBanner.mapConditionToFilter(cond)
        assertEquals(IssueType.SECURITY_HOTSPOT, filter?.issueType)
    }

    @Test
    fun `mapConditionToFilter returns null for coverage conditions`() {
        val cond = GateCondition("new_coverage", "LT", "80", "62.3", passed = false)
        val filter = GateStatusBanner.mapConditionToFilter(cond)
        assertNull(filter?.issueType)
        assertTrue(filter?.isCoverageCondition == true)
    }

    @Test
    fun `mapConditionToFilter returns null for passing conditions`() {
        val cond = GateCondition("new_bugs", "GT", "0", "0", passed = true)
        val filter = GateStatusBanner.mapConditionToFilter(cond)
        assertNull(filter)
    }

    @Test
    fun `formatConditions joins failing conditions with separator`() {
        val conditions = listOf(
            GateCondition("new_coverage", "LT", "80", "62.3", passed = false),
            GateCondition("new_bugs", "GT", "0", "3", passed = false)
        )
        val text = GateStatusBanner.formatFailingConditions(conditions)
        assertTrue(text.contains("62.3"))
        assertTrue(text.contains("3"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :sonar:test --tests "*.GateStatusBannerTest" -x verifyPlugin`
Expected: FAIL — `GateStatusBanner` does not exist

- [ ] **Step 3: Create GateStatusBanner**

```kotlin
// sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/GateStatusBanner.kt
package com.workflow.orchestrator.sonar.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.ui.StatusColors
import com.workflow.orchestrator.sonar.model.GateCondition
import com.workflow.orchestrator.sonar.model.IssueType
import com.workflow.orchestrator.sonar.model.QualityGateState
import com.workflow.orchestrator.sonar.model.QualityGateStatus
import java.awt.*
import javax.swing.JPanel

class GateStatusBanner : JPanel(BorderLayout()) {

    data class FilterAction(
        val issueType: IssueType? = null,
        val newCodeMode: Boolean = true,
        val isCoverageCondition: Boolean = false
    )

    var onShowBlockingIssues: ((FilterAction) -> Unit)? = null

    private val iconLabel = JBLabel("\u26A0").apply {
        font = font.deriveFont(Font.BOLD, JBUI.scale(14).toFloat())
        foreground = StatusColors.ERROR
    }
    private val titleLabel = JBLabel("Quality Gate Failed").apply {
        font = font.deriveFont(Font.BOLD, JBUI.scale(12).toFloat())
        foreground = StatusColors.ERROR
    }
    private val conditionsLabel = JBLabel("").apply {
        font = font.deriveFont(Font.PLAIN, JBUI.scale(10).toFloat())
    }
    private val actionLink = JBLabel("<html><a href='#'>Show Blocking Issues</a></html>").apply {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
    }

    private var failingConditions: List<GateCondition> = emptyList()

    init {
        border = JBUI.Borders.empty(6, 12)
        background = JBColor(Color(0xFD, 0xE7, 0xE9), Color(0x3A, 0x1A, 0x1A))
        isOpaque = true
        isVisible = false

        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            add(iconLabel)
            add(titleLabel)
        }

        add(leftPanel, BorderLayout.WEST)
        add(conditionsLabel, BorderLayout.CENTER)
        add(actionLink, BorderLayout.EAST)

        actionLink.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                // Find the first failing issue-related condition
                val firstIssueFilter = failingConditions
                    .filter { !it.passed }
                    .mapNotNull { mapConditionToFilter(it) }
                    .firstOrNull { it.issueType != null }

                if (firstIssueFilter != null) {
                    onShowBlockingIssues?.invoke(firstIssueFilter)
                } else {
                    // Coverage condition — navigate to coverage tab
                    onShowBlockingIssues?.invoke(FilterAction(isCoverageCondition = true))
                }
            }
        })
    }

    fun update(gateState: QualityGateState) {
        if (gateState.status == QualityGateStatus.FAILED) {
            failingConditions = gateState.conditions
            conditionsLabel.text = formatFailingConditions(gateState.conditions)
            isVisible = true
        } else {
            isVisible = false
        }
        revalidate()
    }

    companion object {
        fun mapConditionToFilter(condition: GateCondition): FilterAction? {
            if (condition.passed) return null
            val metric = condition.metric.lowercase()
            return when {
                metric.contains("bug") -> FilterAction(IssueType.BUG, newCodeMode = metric.startsWith("new_"))
                metric.contains("vulnerabilit") -> FilterAction(IssueType.VULNERABILITY, newCodeMode = metric.startsWith("new_"))
                metric.contains("hotspot") -> FilterAction(IssueType.SECURITY_HOTSPOT, newCodeMode = metric.startsWith("new_"))
                metric.contains("coverage") || metric.contains("duplicat") ->
                    FilterAction(isCoverageCondition = true, newCodeMode = metric.startsWith("new_"))
                else -> FilterAction(newCodeMode = metric.startsWith("new_"))
            }
        }

        fun formatFailingConditions(conditions: List<GateCondition>): String {
            val failing = conditions.filter { !it.passed }
            if (failing.isEmpty()) return ""
            return failing.joinToString("  |  ") { cond ->
                val metricName = cond.metric.replace("_", " ")
                    .split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                val isCoverage = cond.metric.contains("coverage", ignoreCase = true)
                val suffix = if (isCoverage) "%" else ""
                "$metricName: ${cond.actualValue}$suffix (threshold: ${cond.threshold}$suffix)"
            }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :sonar:test --tests "*.GateStatusBannerTest" -x verifyPlugin`
Expected: PASS

- [ ] **Step 5: Add applyPreFilter() to IssueListPanel**

In `IssueListPanel.kt`, add public method:

```kotlin
    fun applyPreFilter(type: IssueType?, newCodeMode: Boolean?) {
        // Set filter dropdowns programmatically
        val typeIndex = when (type) {
            IssueType.BUG -> 1
            IssueType.VULNERABILITY -> 2
            IssueType.CODE_SMELL -> 3
            IssueType.SECURITY_HOTSPOT -> 4
            null -> 0
        }
        filterCombo.selectedIndex = typeIndex
        severityCombo.selectedIndex = 0 // Reset severity filter
        // Note: newCodeMode is handled by QualityDashboardPanel via dataService.setNewCodeMode()
    }
```

- [ ] **Step 6: Enhance OverviewPanel gate card styling**

In `OverviewPanel.kt`, in the `update()` method's quality gate section, change the condition rendering loop:

Replace the condition label creation with:

```kotlin
            val icon = if (cond.passed) "\u2713" else "\u2717"
            val label = JBLabel("$icon $metricName: ${cond.actualValue}$suffix (threshold: ${cond.threshold}$suffix)")
            label.font = FONT_PLAIN_10
            label.foreground = when {
                !cond.passed -> StatusColors.ERROR
                cond.passed -> {
                    val inWarningZone = cond.warningThreshold?.let { wt ->
                        isInWarningZone(cond.actualValue, wt, cond.threshold, cond.comparator)
                    } ?: false
                    if (inWarningZone) StatusColors.WARNING else StatusColors.SUCCESS
                }
                else -> JBColor.GRAY
            }
```

Also change the card border width when FAILED — in `createCard()` or in `update()`, after setting gate status, update the gate card's border:

In `update()`, after the gate status block:

```kotlin
        // Widen gate card accent border when FAILED
        val gateBorderWidth = if (state.qualityGate.status == QualityGateStatus.FAILED) 4 else 2
        gateStatusLabel.parent?.parent?.let { card ->
            (card as? JPanel)?.border = javax.swing.BorderFactory.createCompoundBorder(
                javax.swing.BorderFactory.createMatteBorder(0, gateBorderWidth, 0, 0, ACCENT_GATE),
                JBUI.Borders.empty(10)
            )
        }
```

- [ ] **Step 7: Wire GateStatusBanner into QualityDashboardPanel**

In `QualityDashboardPanel.kt`:

1. Add field: `private val gateBanner = GateStatusBanner()`
2. In `init`, insert the banner into the `topSection` panel, after `branchInfoPanel`:

```kotlin
            add(gateBanner)
```

3. Wire the banner's action callback:

```kotlin
        gateBanner.onShowBlockingIssues = { filter ->
            if (filter.isCoverageCondition) {
                // Switch to Coverage tab
                tabbedPane.selectedIndex = 2
            } else {
                // Switch to Issues tab and apply filter
                if (filter.newCodeMode) dataService.setNewCodeMode(true)
                tabbedPane.selectedIndex = 1
                filter.issueType?.let { issueListPanel.applyPreFilter(it, filter.newCodeMode) }
            }
        }
```

Note: `tabbedPane` needs to be accessible — extract it to a field:

```kotlin
    private lateinit var tabbedPane: JBTabbedPane
```

And assign in `init`: `tabbedPane = JBTabbedPane().apply { ... }`

4. In `updateUI()`, add banner update in the header section:

```kotlin
        gateBanner.update(state.qualityGate)
```

- [ ] **Step 8: Run full sonar test suite**

Run: `./gradlew :sonar:test -x verifyPlugin`
Expected: All tests pass

- [ ] **Step 9: Run buildPlugin to verify compilation**

Run: `./gradlew buildPlugin -x verifyPlugin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 10: Commit**

```bash
git add sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/GateStatusBanner.kt \
       sonar/src/test/kotlin/com/workflow/orchestrator/sonar/ui/GateStatusBannerTest.kt \
       sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/IssueListPanel.kt \
       sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/OverviewPanel.kt \
       sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/QualityDashboardPanel.kt
git commit -m "feat(sonar): add failed quality gate banner with Show Blocking Issues action"
```

---

## Task 7: Final Integration Test and Cleanup

**Files:**
- All modified/created files from Tasks 1-6

- [ ] **Step 1: Run full test suite**

Run: `./gradlew :sonar:test :core:test -x verifyPlugin`
Expected: All tests pass

- [ ] **Step 2: Run verifyPlugin for API compatibility**

Run: `./gradlew verifyPlugin`
Expected: No API compatibility issues

- [ ] **Step 3: Build the plugin**

Run: `./gradlew buildPlugin`
Expected: BUILD SUCCESSFUL, ZIP in `build/distributions/`

- [ ] **Step 4: Update module CLAUDE.md**

In `sonar/CLAUDE.md`, update the UI section to reflect the new panels:

```
## UI

- `QualityDashboardPanel` — 3 sub-panels: overview, issue list (with detail split pane), coverage table (with preview pane). GateStatusBanner shown when gate fails.
- `IssueDetailPanel` — Split pane detail view: code snippet, rule info, severity/type badges, Fix with AI Agent
- `CoveragePreviewPanel` — Uncovered region preview with file metrics, Open in Editor action
- `GateStatusBanner` — Full-width error banner for failed quality gate with Show Blocking Issues action
- `QualityListItem` — Sealed interface unifying MappedIssue and SecurityHotspotData for the issues list
```

- [ ] **Step 5: Commit documentation**

```bash
git add sonar/CLAUDE.md
git commit -m "docs(sonar): update CLAUDE.md with new Quality tab panels"
```
