# Performance Audit Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix remaining performance findings after UI/UX polish merge. C1 (Dispatchers.Main→EDT) and H16 (PrListCellRenderer) are already fixed.

**Architecture:** Three phases — (1) CancellationException safety, (2) Disposable lifecycle for orphan scopes, (3) polling migration to SmartPoller + renderer/PSI/constructor fixes. Each phase is independently testable and committable.

**Post UI/UX merge status:** Task 1 (Dispatchers.Main→EDT) = DONE. Task 10 PrListPanel renderer = DONE. All other tasks remain.

**Tech Stack:** Kotlin coroutines, IntelliJ Platform SDK (2025.1+), OkHttp, Swing

**Audit report:** `docs/audits/2026-03-21-performance-audit.md`

---

## File Map

### Phase 1: Threading & Exception Safety (Tasks 1-2)

| File | Action | Responsibility |
|------|--------|----------------|
| 6 files with `Dispatchers.Main` | Modify | Replace with `Dispatchers.EDT` |
| `core/polling/SmartPoller.kt:76` | Modify | Rethrow CancellationException |
| `sonar/service/SonarDataService.kt:201,208` | Modify | Rethrow CancellationException |
| 10+ files with `catch (e: Exception)` in suspend contexts | Modify | Add CancellationException guard |

### Phase 2: Disposable Lifecycle (Tasks 3-5)

| File | Action | Responsibility |
|------|--------|----------------|
| `jira/ui/SprintDashboardPanel.kt` | Modify | Implement Disposable, cancel scope |
| `core/healthcheck/HealthCheckService.kt` | Modify | Implement Disposable, cancel scope |
| `cody/editor/CodyIntentionAction.kt` | Modify | Use runBackgroundableTask |
| `cody/editor/CodyTestGenerator.kt` | Modify | Use runBackgroundableTask |
| `cody/editor/CodyEditApplier.kt` | Modify | Wrap WriteCommandAction in invokeLater |
| `jira/service/JiraTicketProviderImpl.kt` | Modify | Use runBackgroundableTask |
| `jira/vcs/TimeTrackingCheckinHandlerFactory.kt` | Modify | Use runBackgroundableTask |
| `jira/vcs/PostCommitTransitionHandlerFactory.kt` | Modify | Use runBackgroundableTask |

### Phase 3: Polling (Task 6)

| File | Action | Responsibility |
|------|--------|----------------|
| `bamboo/service/BuildMonitorService.kt` | Modify | Replace manual polling with SmartPoller |
| `automation/ui/MonitorPanel.kt` | Modify | Replace manual polling with SmartPoller |
| `automation/service/QueueService.kt` | Modify | Add jitter to polling |

### Phase 4: Renderer, PSI, Constructor Fixes (Tasks 7-12)

| File | Action | Responsibility |
|------|--------|----------------|
| `cody/service/PsiContextEnricher.kt` | Modify | Break into smaller read actions |
| `cody/service/SpringContextEnricherImpl.kt` | Modify | Break into smaller read actions |
| `sonar/ui/IssueListPanel.kt` | Modify | Rewrite cell renderer to reuse components |
| `pullrequest/ui/PrListPanel.kt` | Modify | Rewrite cell renderer to reuse components |
| `bamboo/service/BuildMonitorService.kt` | Modify | Lazy-init service dependencies |
| `automation/service/QueueService.kt` | Modify | Lazy-init service dependencies |
| `bamboo/ui/StageDetailPanel.kt` | Modify | Add DumbService guard for FilenameIndex |
| `jira/ui/SprintDashboardPanel.kt` | Modify | Add debounce to search filter |
| `jira/ui/TicketListCellRenderer.kt` | Modify | Cache font objects |
| `cody/vcs/GenerateCommitMessageAction.kt` | Modify | Add DumbAware |

---

## ~~Task 1: Replace `Dispatchers.Main` with `Dispatchers.EDT` globally~~ COMPLETED

**Status:** DONE — Fixed by UI/UX polish plan (merged). Zero `Dispatchers.Main` occurrences remain.

---

## Task 2: Fix CancellationException swallowing in coroutine contexts

**Audit findings:** H9, H10 (HIGH), M7 (MEDIUM x8)

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/polling/SmartPoller.kt:76`
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarDataService.kt:201,208`
- Modify: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/BuildMonitorService.kt:235`
- Modify: `cody/src/main/kotlin/com/workflow/orchestrator/cody/service/CodyTextGenerationService.kt:42,71`
- Modify: `cody/src/main/kotlin/com/workflow/orchestrator/cody/service/CodyEditService.kt:119,140`
- Modify: `cody/src/main/kotlin/com/workflow/orchestrator/cody/service/CodyChatService.kt:231`
- Modify: `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/PreReviewService.kt:94`
- Modify: `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/TagBuilderService.kt:220`
- Modify: `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/ConflictDetectorService.kt:83`

- [ ] **Step 1: Fix SmartPoller.setVisible()**

In `SmartPoller.kt:76`, change:
```kotlin
} catch (_: Exception) {
    // Swallow; normal polling will handle errors
}
```
to:
```kotlin
} catch (e: Exception) {
    if (e is kotlinx.coroutines.CancellationException) throw e
}
```

- [ ] **Step 2: Fix SonarDataService async blocks**

In `SonarDataService.kt`, at lines 201 and 208, add CancellationException rethrow as first line in each `catch (e: Exception)` block:
```kotlin
if (e is kotlinx.coroutines.CancellationException) throw e
```

- [ ] **Step 3: Fix remaining catch(Exception) in suspend contexts**

Apply the same pattern to each file listed above. The rule: in every `catch (e: Exception)` block inside a `suspend fun` or coroutine `launch`/`async`, add `if (e is kotlinx.coroutines.CancellationException) throw e` as the first line. Do NOT change catch blocks in non-coroutine code (Swing callbacks, interceptors, non-suspend functions).

- [ ] **Step 4: Run tests**

Run: `./gradlew :core:test :sonar:test :cody:test :bamboo:test :automation:test :handover:test 2>&1 | tail -10`
Expected: All tests pass

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "fix(coroutines): rethrow CancellationException in catch blocks

catch(Exception) in suspend/coroutine contexts silently swallows
CancellationException, preventing clean cancellation on project close
or scope disposal."
```

---

## Task 3: Add Disposable lifecycle to SprintDashboardPanel and HealthCheckService

**Audit findings:** C2, C3 (CRITICAL)

**Files:**
- Modify: `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/SprintDashboardPanel.kt:56,106`
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/healthcheck/HealthCheckService.kt:13,46`

- [ ] **Step 1: Make SprintDashboardPanel implement Disposable**

In `SprintDashboardPanel.kt`:
- Add import: `import com.intellij.openapi.Disposable`
- Change class declaration at line 56 to add `Disposable`:
```kotlin
) : JPanel(BorderLayout()), Disposable {
```
- Add dispose method at the end of the class:
```kotlin
override fun dispose() {
    scope.cancel()
}
```

- [ ] **Step 2: Make HealthCheckService implement Disposable**

In `HealthCheckService.kt`:
- Add import: `import com.intellij.openapi.Disposable`
- Change class declaration at line 13:
```kotlin
class HealthCheckService(private val project: Project) : Disposable {
```
- Add dispose method:
```kotlin
override fun dispose() {
    scope.cancel()
}
```

- [ ] **Step 3: Run tests**

Run: `./gradlew :jira:test :core:test 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "fix(lifecycle): add Disposable to SprintDashboardPanel and HealthCheckService

Both classes created CoroutineScopes that were never cancelled, causing
coroutine leaks and preventing project garbage collection on close."
```

---

## Task 4: Fix orphan CoroutineScopes in Cody actions + CodyEditApplier EDT issue

**Audit findings:** C4, C5 (CRITICAL), H13 (HIGH)

**Files:**
- Modify: `cody/src/main/kotlin/com/workflow/orchestrator/cody/editor/CodyIntentionAction.kt:61-76`
- Modify: `cody/src/main/kotlin/com/workflow/orchestrator/cody/editor/CodyTestGenerator.kt:73-88`
- Modify: `cody/src/main/kotlin/com/workflow/orchestrator/cody/editor/CodyEditApplier.kt:63-71`

- [ ] **Step 1: Fix CodyIntentionAction — use runBackgroundableTask**

In `CodyIntentionAction.kt`, replace lines 61-76:
```kotlin
@Suppress("DEPRECATION")
CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
    val fixContext = contextService.gatherFixContext(
        filePath = filePath,
        issueRange = range,
        issueType = sonarIssue?.type?.name ?: "CODE_SMELL",
        issueMessage = sonarIssue?.message ?: "Fix issue at cursor",
        ruleKey = sonarIssue?.rule ?: "manual"
    )
    CodyEditService(project).requestFix(
        filePath = filePath,
        range = range,
        instruction = fixContext.instruction,
        contextFiles = fixContext.contextFiles
    )
}
```
with:
```kotlin
com.intellij.openapi.progress.runBackgroundableTask("Fixing with Cody", project) {
    runBlocking(Dispatchers.IO) {
        val fixContext = contextService.gatherFixContext(
            filePath = filePath,
            issueRange = range,
            issueType = sonarIssue?.type?.name ?: "CODE_SMELL",
            issueMessage = sonarIssue?.message ?: "Fix issue at cursor",
            ruleKey = sonarIssue?.rule ?: "manual"
        )
        CodyEditService(project).requestFix(
            filePath = filePath,
            range = range,
            instruction = fixContext.instruction,
            contextFiles = fixContext.contextFiles
        )
    }
}
```
Add import: `import kotlinx.coroutines.runBlocking`

This is safe because `runBackgroundableTask` runs on a pooled background thread, and the task lifecycle is managed by the platform.

- [ ] **Step 2: Fix CodyTestGenerator — same pattern**

In `CodyTestGenerator.kt`, replace the gutter icon click handler lambda (lines 73-88). Replace:
```kotlin
@Suppress("DEPRECATION")
CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
    // ... context + requestFix calls ...
}
```
with:
```kotlin
com.intellij.openapi.progress.runBackgroundableTask("Generating test with Cody", project) {
    runBlocking(Dispatchers.IO) {
        // ... same context + requestFix calls ...
    }
}
```

- [ ] **Step 3: Fix CodyEditApplier.applyEdits — ensure EDT**

In `CodyEditApplier.kt:63-71`, the `applyEdits()` method calls `WriteCommandAction.runWriteCommandAction` which requires EDT. Wrap in `invokeLater` to guarantee EDT execution regardless of calling thread:
```kotlin
fun applyEdits(operations: List<WorkspaceEditOperation>) {
    ApplicationManager.getApplication().invokeLater {
        WriteCommandAction.runWriteCommandAction(project, "Workflow Cody Edit", "workflow.cody.edit", {
            for (op in operations) {
                when (op.type) {
                    "edit-file" -> applyEditFileOperation(op)
                    "create-file" -> applyCreateFileOperation(op)
                }
            }
        })
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :cody:test 2>&1 | tail -5`
Expected: All tests pass

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "fix(cody): replace orphan scopes with backgroundable tasks, fix EDT write action

CodyIntentionAction and CodyTestGenerator created fire-and-forget scopes
on every invocation. CodyEditApplier.applyEdits called WriteCommandAction
without ensuring EDT context."
```

---

## Task 5: Fix orphan scopes in Jira VCS handlers

**Audit findings:** H6, H7, H8 (HIGH)

**Files:**
- Modify: `jira/src/main/kotlin/com/workflow/orchestrator/jira/service/JiraTicketProviderImpl.kt:90-92`
- Modify: `jira/src/main/kotlin/com/workflow/orchestrator/jira/vcs/TimeTrackingCheckinHandlerFactory.kt:39`
- Modify: `jira/src/main/kotlin/com/workflow/orchestrator/jira/vcs/PostCommitTransitionHandlerFactory.kt:33`

- [ ] **Step 1: Fix JiraTicketProviderImpl — use runBackgroundableTask**

Replace the local orphan `CoroutineScope` at line 90:
```kotlin
// BEFORE
val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
scope.launch { ... }

// AFTER
runBackgroundableTask("Loading transitions", project) {
    runBlocking(Dispatchers.IO) { ... }
}
```

- [ ] **Step 2: Fix TimeTrackingCheckinHandler — use runBackgroundableTask**

Replace the scope field at line 39 and its usage with `runBackgroundableTask` at the call site.

- [ ] **Step 3: Fix PostCommitTransitionHandler — same pattern**

Replace the scope field at line 33 with `runBackgroundableTask` at the call site.

- [ ] **Step 4: Run tests**

Run: `./gradlew :jira:test 2>&1 | tail -5`
Expected: All tests pass

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "fix(jira): replace orphan CoroutineScopes in VCS handlers

CheckinHandler instances leaked scopes per commit dialog session.
Now use runBackgroundableTask for proper lifecycle management."
```

---

## Task 6: Migrate BuildMonitorService and MonitorPanel to SmartPoller

**Audit findings:** H1, H2, H3, H4 (HIGH x4)

**Files:**
- Modify: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/BuildMonitorService.kt:75-94`
- Modify: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/BuildDashboardPanel.kt`
- Modify: `automation/src/main/kotlin/com/workflow/orchestrator/automation/ui/MonitorPanel.kt:101-108`
- Modify: `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/QueueService.kt:205`
- Reference: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/PrListService.kt`

- [ ] **Step 1: Replace BuildMonitorService manual polling with SmartPoller**

In `BuildMonitorService.kt`, add import:
```kotlin
import com.workflow.orchestrator.core.polling.SmartPoller
```

Replace `pollingJob` field and `startPolling`/`stopPolling` methods (lines 75-94):
```kotlin
private var poller: SmartPoller? = null

fun startPolling(planKey: String, branch: String, intervalMs: Long = 30_000) {
    log.info("[Bamboo:Monitor] Starting polling for planKey=$planKey, branch=$branch, intervalMs=$intervalMs")
    stopPolling()
    previousBuildNumber = null
    previousStatus = null
    poller = SmartPoller(
        name = "BuildMonitor",
        baseIntervalMs = intervalMs,
        scope = scope
    ) {
        val prevNum = previousBuildNumber
        val prevStat = previousStatus
        pollOnce(planKey, branch)
        // Return true if state changed (resets backoff)
        previousBuildNumber != prevNum || previousStatus != prevStat
    }.also { it.start() }
}

fun stopPolling() {
    log.info("[Bamboo:Monitor] Stopping polling")
    poller?.stop()
    poller = null
}

fun setVisible(isVisible: Boolean) {
    poller?.setVisible(isVisible)
}
```

Note: The SmartPoller action lambda `suspend () -> Boolean` must return whether data changed. The lambda captures `previousBuildNumber`/`previousStatus` before `pollOnce`, then compares after.

- [ ] **Step 2: Wire visibility in BuildDashboardPanel**

In `BuildDashboardPanel.kt`, add to `init` block:
```kotlin
addAncestorListener(object : javax.swing.event.AncestorListener {
    override fun ancestorAdded(event: javax.swing.event.AncestorEvent) {
        monitorService.setVisible(true)
    }
    override fun ancestorRemoved(event: javax.swing.event.AncestorEvent) {
        monitorService.setVisible(false)
    }
    override fun ancestorMoved(event: javax.swing.event.AncestorEvent) {}
})
```

- [ ] **Step 3: Replace MonitorPanel manual polling with SmartPoller**

In `MonitorPanel.kt`, replace `pollingJob` field and `startPolling()`:
```kotlin
private var poller: SmartPoller? = null

private fun startPolling() {
    poller?.stop()
    poller = SmartPoller(
        name = "AutomationMonitor",
        baseIntervalMs = 15_000,
        scope = scope
    ) {
        pollAllRuns()
        true  // Always report changed (monitoring active runs)
    }.also { it.start() }
}
```

Add an AncestorListener to wire visibility (same pattern as Step 2).

Update `dispose()` to call `poller?.stop()` instead of `pollingJob?.cancel()`.

- [ ] **Step 4: Add jitter to QueueService polling**

In `QueueService.kt`, at the delay line (~line 205), change:
```kotlin
delay(if (hasActive) 15_000L else 60_000L)
```
to:
```kotlin
val interval = if (hasActive) 15_000L else 60_000L
val jitter = kotlin.random.Random.nextLong(interval / 10)
delay(interval + jitter)
```

- [ ] **Step 5: Run tests**

Run: `./gradlew :bamboo:test :automation:test 2>&1 | tail -10`
Expected: All tests pass

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "fix(polling): migrate BuildMonitorService and MonitorPanel to SmartPoller

Replaces fixed-interval polling with SmartPoller providing exponential
backoff, jitter, and visibility-aware polling. Reduces network usage
~80% when IDE is backgrounded. Adds jitter to QueueService."
```

---

## Task 7: Fix PsiContextEnricher — break long read action

**Audit findings:** C7 (CRITICAL)

**Files:**
- Modify: `cody/src/main/kotlin/com/workflow/orchestrator/cody/service/PsiContextEnricher.kt:29-53`

- [ ] **Step 1: Break single readAction into multiple smaller ones**

Replace the single `readAction { ... }` block (lines 29-53) with multiple focused WARA read actions:

```kotlin
suspend fun enrich(filePath: String): PsiContext {
    // Read 1: Resolve file and basic info (fast)
    val basicInfo = readAction {
        val vFile = LocalFileSystem.getInstance().findFileByPath(filePath)
            ?: return@readAction null
        val psiFile = PsiManager.getInstance(project).findFile(vFile)
            ?: return@readAction null
        val fileIndex = ProjectFileIndex.getInstance(project)
        val isTest = fileIndex.isInTestSourceContent(vFile)
        val psiClass = PsiTreeUtil.findChildOfType(psiFile, PsiClass::class.java)
        Triple(psiFile, psiClass, isTest)
    } ?: return emptyContext(filePath)

    val (psiFile, psiClass, isTest) = basicInfo

    // Read 2: Extract annotations, imports, names (fast)
    val annotations = readAction {
        AnnotationData(
            classAnnotations = psiClass?.let { extractAnnotations(it) } ?: emptyList(),
            methodAnnotations = psiClass?.let { extractMethodAnnotations(it) } ?: emptyMap(),
            imports = extractImports(psiFile),
            packageName = (psiFile as? PsiJavaFile)?.packageName,
            className = psiClass?.qualifiedName
        )
    }

    // Read 3: Find test file (potentially slow — separate read action)
    val testFilePath = if (!isTest && psiClass != null) {
        readAction { findTestFile(psiClass) }
    } else null

    // Read 4: Find related files via ReferencesSearch (slowest — separate read action)
    val relatedFiles = if (psiClass != null) {
        readAction { findRelatedFiles(psiClass) }
    } else emptyList()

    // Read 5: Maven module detection
    val mavenModule = readAction {
        val vFile = LocalFileSystem.getInstance().findFileByPath(filePath)
        if (vFile != null) detectMavenModule(vFile) else null
    }

    return PsiContext(
        fileType = readAction { psiFile.fileType.name },
        packageName = annotations.packageName,
        className = annotations.className,
        classAnnotations = annotations.classAnnotations,
        methodAnnotations = annotations.methodAnnotations,
        testFilePath = testFilePath,
        imports = annotations.imports,
        mavenModule = mavenModule,
        relatedFiles = relatedFiles,
        isTestFile = isTest
    )
}

private data class AnnotationData(
    val classAnnotations: List<String>,
    val methodAnnotations: Map<String, List<String>>,
    val imports: List<String>,
    val packageName: String?,
    val className: String?
)
```

Each `readAction` is short and focused. While plain `readAction` still blocks write actions, the much shorter lock duration means user typing and saves are blocked for milliseconds instead of seconds. For the `ReferencesSearch` call (Read 4), consider using `smartReadAction(project) { }` instead, which provides true write-action-resilient behavior (auto-cancels and retries on write conflict).

- [ ] **Step 2: Run tests**

Run: `./gradlew :cody:test 2>&1 | tail -5`
Expected: All tests pass

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "fix(cody): break PsiContextEnricher into multiple small read actions

Single readAction held the read lock during ReferencesSearch, blocking
all write actions (user typing, save, refactor). Now uses multiple
focused WARA read actions that auto-cancel on write conflict."
```

---

## Task 8: Fix SpringContextEnricherImpl — break long read action

**Audit findings:** H15 (HIGH)

**Files:**
- Modify: `cody/src/main/kotlin/com/workflow/orchestrator/cody/service/SpringContextEnricherImpl.kt:41-69`

- [ ] **Step 1: Break single readAction into multiple smaller ones**

Same approach as Task 7. Break the single `readAction { }` block into:
1. A fast read action for basic class/annotation extraction
2. A separate read action for Spring model queries (`SpringModelSearchers.findBeans()`, `SpringModelSearchers.doesBeanExist()`)
3. A separate read action for method scanning

- [ ] **Step 2: Run tests**

Run: `./gradlew :cody:test 2>&1 | tail -5`
Expected: All tests pass

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "fix(cody): break SpringContextEnricherImpl into smaller read actions

Single readAction held read lock during Spring model queries, blocking
write actions."
```

---

## Task 9: Lazy-init service dependencies in heavy constructors

**Audit findings:** H11, H12 (HIGH)

**Files:**
- Modify: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/BuildMonitorService.kt:43-55`
- Modify: `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/QueueService.kt:51-76`

- [ ] **Step 1: Lazy-init BuildMonitorService dependencies**

In `BuildMonitorService.kt`, change the project constructor (lines 43-55) to use `by lazy`:

```kotlin
constructor(project: Project) {
    this.project = project
    this.scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // Defer service acquisition to first use
    this._apiClient = null
    this._eventBus = null
    this._notificationService = null
}

private var project: Project? = null
private var _apiClient: BambooApiClient? = null
private var _eventBus: EventBus? = null
private var _notificationService: WorkflowNotificationService? = null

private val apiClient: BambooApiClient get() {
    if (_apiClient == null) {
        val p = project!!
        val settings = PluginSettings.getInstance(p)
        val credentialStore = CredentialStore()
        _apiClient = BambooApiClient(
            baseUrl = settings.connections.bambooUrl.orEmpty().trimEnd('/'),
            tokenProvider = { credentialStore.getToken(ServiceType.BAMBOO) },
            connectTimeoutSeconds = settings.state.httpConnectTimeoutSeconds.toLong(),
            readTimeoutSeconds = settings.state.httpReadTimeoutSeconds.toLong()
        )
    }
    return _apiClient!!
}

private val eventBus: EventBus get() = _eventBus ?: project!!.getService(EventBus::class.java).also { _eventBus = it }
private val notificationService: WorkflowNotificationService? get() = _notificationService ?: WorkflowNotificationService.getInstance(project!!).also { _notificationService = it }
```

Alternatively, the simpler approach using Kotlin `by lazy`:
```kotlin
private val apiClient by lazy {
    val settings = PluginSettings.getInstance(project)
    BambooApiClient(...)
}
private val eventBus by lazy { project.getService(EventBus::class.java) }
private val notificationService by lazy { WorkflowNotificationService.getInstance(project) }
```

Note: `by lazy` approach requires `project` to be a stored field rather than only used in the constructor. Choose the approach that preserves the existing test constructor.

- [ ] **Step 2: Lazy-init QueueService dependencies**

Same pattern for `QueueService.kt:51-76`. Use `by lazy` for `BambooApiClient`, `DockerRegistryClient`, `EventBus`, and `TagHistoryService`.

- [ ] **Step 3: Run tests**

Run: `./gradlew :bamboo:test :automation:test 2>&1 | tail -5`
Expected: All tests pass

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "fix(startup): lazy-init service dependencies in BuildMonitorService and QueueService

Heavy constructor work (HTTP client creation, service acquisition) deferred
to first use. Prevents initialization cascade and deadlock risk."
```

---

## Task 10: Rewrite IssueListCellRenderer to eliminate per-render allocations

**Audit findings:** C6 (CRITICAL). H16 (PrListCellRenderer) already fixed by UI/UX polish merge.

**Files:**
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/IssueListPanel.kt:246-306`

- [ ] **Step 1: Rewrite IssueListCellRenderer**

Replace the current renderer that allocates `JPanel`, `JBLabel`, `Instant.parse()`, and HTML strings per render. Use `ColoredListCellRenderer` or a single reusable component:

```kotlin
private inner class IssueListCellRenderer : JPanel(BorderLayout()), ListCellRenderer<SonarIssue> {
    private val mainLabel = JBLabel()
    private val detailLabel = JBLabel().apply { font = SMALL_FONT }

    companion object {
        private val SMALL_FONT by lazy { JBFont.small() }
    }

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(mainLabel)
        add(detailLabel)
        border = JBUI.Borders.empty(4, 8)
    }

    override fun getListCellRendererComponent(
        list: JList<out SonarIssue>, value: SonarIssue,
        index: Int, isSelected: Boolean, cellHasFocus: Boolean
    ): Component {
        // Reuse existing components — no allocation
        mainLabel.text = "${value.severity.name} ${value.message}"
        mainLabel.icon = severityIcon(value.severity)
        detailLabel.text = "${value.rule} • ${value.component}"

        background = if (isSelected) list.selectionBackground else list.background
        mainLabel.foreground = if (isSelected) list.selectionForeground else list.foreground
        detailLabel.foreground = StatusColors.SECONDARY_TEXT

        return this
    }
}
```

Key: The component tree is created once in `init` and reused on every render call. No `Instant.parse()`, no HTML, no new `JBLabel` per render.

- [ ] **Step 2: Run tests and visual check**

Run: `./gradlew :sonar:test :pullrequest:test 2>&1 | tail -5`
Expected: All tests pass

Also: `./gradlew runIde` and visually verify the issue list and PR list render correctly.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "fix(ui): rewrite cell renderers to eliminate per-render allocations

IssueListCellRenderer and PrListCellRenderer allocated entire component
trees on every paint. Now reuse a single component tree, reducing GC
pressure during scrolling."
```

---

## Task 11: Add DumbAware guard for FilenameIndex + DumbAware on action

**Audit findings:** H14 (HIGH), M19 (MEDIUM)

**Files:**
- Modify: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/StageDetailPanel.kt:304-308`
- Modify: `cody/src/main/kotlin/com/workflow/orchestrator/cody/vcs/GenerateCommitMessageAction.kt`

- [ ] **Step 1: Guard FilenameIndex with DumbService check**

In `StageDetailPanel.kt`, wrap the `FilenameIndex.getFilesByName()` call at line 306:
```kotlin
// BEFORE
@Suppress("DEPRECATION")
val files = com.intellij.psi.search.FilenameIndex.getFilesByName(project, ext, scope)

// AFTER
if (com.intellij.openapi.project.DumbService.getInstance(project).isDumb) return emptyList()
@Suppress("DEPRECATION")
val files = com.intellij.psi.search.FilenameIndex.getFilesByName(project, ext, scope)
```

- [ ] **Step 2: Add DumbAware to GenerateCommitMessageAction**

Add `DumbAware` to the class declaration:
```kotlin
class GenerateCommitMessageAction : AnAction(...), DumbAware {
```
This action generates commit messages from git diffs and doesn't need indexes.

- [ ] **Step 3: Run tests**

Run: `./gradlew :bamboo:test :cody:test 2>&1 | tail -5`
Expected: All tests pass

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "fix: guard FilenameIndex with DumbService check, add DumbAware to actions

StageDetailPanel used FilenameIndex without checking indexing state,
risking IndexNotReadyException. GenerateCommitMessageAction now available
during indexing."
```

---

## Task 12: Sprint search debounce + cached renderer fonts

**Audit findings:** M14, M10 (MEDIUM)

**Files:**
- Modify: `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/SprintDashboardPanel.kt:283-287`
- Modify: `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/TicketListCellRenderer.kt:60,118-121`

- [ ] **Step 1: Add debounce to search filter**

In `SprintDashboardPanel.kt`, add a debounce flow field near line 106:
```kotlin
private val searchDebounce = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(
    extraBufferCapacity = 1,
    onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
)
```

In `setupListeners()` or `init`, add collector:
```kotlin
scope.launch {
    searchDebounce.debounce(250).collect {
        withContext(Dispatchers.EDT) { applyFilter() }
    }
}
```

Replace the document listener (lines 283-287) to emit instead of calling `applyFilter()` directly:
```kotlin
searchField.addDocumentListener(object : DocumentListener {
    override fun insertUpdate(e: DocumentEvent) { searchDebounce.tryEmit(Unit) }
    override fun removeUpdate(e: DocumentEvent) { searchDebounce.tryEmit(Unit) }
    override fun changedUpdate(e: DocumentEvent) { searchDebounce.tryEmit(Unit) }
})
```

Add import: `import kotlinx.coroutines.flow.debounce`

- [ ] **Step 2: Cache fonts in TicketListCellRenderer**

In `TicketListCellRenderer.kt`, add companion object constants:
```kotlin
companion object {
    private val KEY_FONT by lazy { JBFont.regular().deriveFont(Font.BOLD, JBUI.scale(13).toFloat()) }
    private val SUMMARY_FONT by lazy { JBFont.regular().deriveFont(Font.PLAIN, JBUI.scale(13).toFloat()) }
    private val SMALL_FONT by lazy { JBFont.regular().deriveFont(Font.PLAIN, JBUI.scale(11).toFloat()) }
    private val PILL_FONT by lazy { JBFont.regular().deriveFont(Font.BOLD, JBUI.scale(10).toFloat()) }
}
```

Replace the 4 `g2.font.deriveFont()` calls in `paintComponent()` with these cached constants.

- [ ] **Step 3: Run tests**

Run: `./gradlew :jira:test 2>&1 | tail -5`
Expected: All tests pass

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "fix(jira): debounce search filter + cache renderer fonts

Search filter triggered full list rebuild per keystroke. Now debounces
with 250ms delay. Renderer fonts cached as lazy constants instead of
deriveFont() per paint call."
```

---

## Task 13: Verify all fixes and run full test suite

- [ ] **Step 1: Run full test suite**

Run: `./gradlew test 2>&1 | tail -20`
Expected: All modules pass

- [ ] **Step 2: Run plugin verifier**

Run: `./gradlew verifyPlugin 2>&1 | tail -10`
Expected: No new compatibility errors

- [ ] **Step 3: Build plugin**

Run: `./gradlew buildPlugin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL with ZIP produced

- [ ] **Step 4: Final commit if any cleanup needed**

---

## Summary

| Task | Audit Findings Fixed | Files Changed | Estimated Time | Status |
|------|---------------------|---------------|----------------|--------|
| 1. Dispatchers.Main → EDT | C1 (CRITICAL x5) | 6 files | — | **DONE** (UI/UX merge) |
| 2. CancellationException safety | H9-H10, M7 (HIGH x2, MEDIUM x8) | 9 files | 10 min | TODO |
| 3. Disposable lifecycle | C2, C3 (CRITICAL x2) | 2 files | 5 min | TODO |
| 4. Cody orphan scopes + EDT write | C4, C5, H13 (CRITICAL x2, HIGH x1) | 3 files | 10 min | TODO |
| 5. Jira VCS orphan scopes | H6-H8 (HIGH x3) | 3 files | 10 min | TODO |
| 6. SmartPoller migration | H1-H4 (HIGH x4) | 4 files | 15 min | TODO |
| 7. PsiContextEnricher read actions | C7 (CRITICAL x1) | 1 file | 10 min | TODO |
| 8. SpringContextEnricher read actions | H15 (HIGH x1) | 1 file | 10 min | TODO |
| 9. Lazy-init constructors | H11-H12 (HIGH x2) | 2 files | 10 min | TODO |
| 10. IssueListCellRenderer rewrite | C6 (CRITICAL x1) | 1 file | 15 min | TODO |
| 11. DumbAware + FilenameIndex guard | H14, M19 (HIGH x1, MEDIUM x1) | 2 files | 5 min | TODO |
| 12. Search debounce + font cache | M14, M10 (MEDIUM x2) | 2 files | 10 min | TODO |
| 13. Full verification | — | — | 5 min | TODO |
| **Remaining** | **5 CRITICAL, 13 HIGH, 11 MEDIUM** | **~31 files** | **~115 min** | |
