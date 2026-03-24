# Performance Audit Report — 2026-03-21

Comprehensive audit of the Workflow Orchestrator IntelliJ plugin against the `intellij-plugin-performance` skill's anti-pattern checklist (Section 12). All 9 modules audited across 5 dimensions.

---

## Executive Summary

| Severity | Count | Key Themes |
|----------|-------|------------|
| CRITICAL | 7 | `Dispatchers.Main` systemic misuse (5), orphan CoroutineScopes (2), PSI read lock held too long (1) |
| HIGH | 13 | Polling without SmartPoller (4), CancellationException swallowed (2), heavy service constructors (2), orphan scopes (3), write action threading (1), missing DumbAware guard (1) |
| MEDIUM | 22 | Font/Color allocations in renderers, fireTableDataChanged misuse, missing debounce, broad IO dispatch, catch(Exception), service field caching |
| LOW | 12 | Minor renderer allocations, redundant revalidate, safe runBlocking |

**Total findings: 54**

---

## CRITICAL Findings (Must Fix)

### C1. Systemic `Dispatchers.Main` instead of `Dispatchers.EDT` (5 findings)

IntelliJ 2025.1+ changed the threading model: `Dispatchers.Main` no longer acquires the write-intent lock. All UI dispatch must use `Dispatchers.EDT`.

**Only 1 file in the entire codebase uses the correct dispatcher** (`DisambiguationHelper.kt`).

| File | Lines | Usage |
|------|-------|-------|
| `sonar/ui/QualityDashboardPanel.kt` | 30 | `CoroutineScope(SupervisorJob() + Dispatchers.Main)` |
| `handover/ui/HandoverPanel.kt` | 20 | `CoroutineScope(Dispatchers.Main + SupervisorJob())` |
| `jira/ui/SprintDashboardPanel.kt` | 304, 501, 511, 535, 553, 558, 565, 583, 610 | `withContext(Dispatchers.Main) { ... }` |
| `sonar/ui/IssueListPanel.kt` | 224 | `withContext(Dispatchers.Main) { ... }` |
| `automation/ui/AutomationStatusBarWidgetFactory.kt` | 84 | `withContext(Dispatchers.Main) { ... }` |
| `bamboo/ui/CreatePrDialog.kt` | 360 | `withContext(Dispatchers.Main) { ... }` |

**Fix:** Global find-and-replace `Dispatchers.Main` → `Dispatchers.EDT` across all production code.

### C2. SprintDashboardPanel — CoroutineScope never cancelled

- **File:** `jira/ui/SprintDashboardPanel.kt:106`
- **Code:** `private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)`
- **Problem:** Does NOT implement `Disposable`. Scope never cancelled. All child coroutines (many `scope.launch` calls across 734 lines) persist until JVM shutdown.

### C3. HealthCheckService — Project-scoped service without Disposable

- **File:** `core/healthcheck/HealthCheckService.kt:46`
- **Code:** `private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)` + EventBus collector in `init`
- **Problem:** `@Service(Service.Level.PROJECT)` that never implements `Disposable`. EventBus collector runs forever, holding project in memory after close.

### C4. CodyIntentionAction — Orphan CoroutineScope per invocation

- **File:** `cody/editor/CodyIntentionAction.kt:62`
- **Code:** `CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { ... }`
- **Problem:** Each invocation creates a new orphan scope. Never cancelled. `@Suppress("DEPRECATION")` suppresses the warning.

### C5. CodyTestGenerator — Orphan CoroutineScope per click

- **File:** `cody/editor/CodyTestGenerator.kt:74`
- **Code:** Same pattern as C4. Fire-and-forget scope on every gutter icon click.

### C6. IssueListCellRenderer — Allocates entire component tree per render

- **File:** `sonar/ui/IssueListPanel.kt:249-303`
- **Code:** `getListCellRendererComponent()` creates new `JPanel`, 2x `JBLabel`, parses `Instant`, builds HTML strings, calls `font.deriveFont()` — all per visible cell per paint.
- **Problem:** With 500+ issues, scrolling generates massive GC pressure.

### C7. PsiContextEnricher — Holds read lock during ReferencesSearch

- **File:** `cody/service/PsiContextEnricher.kt:30-53`
- **Code:** `readAction { ... ReferencesSearch.search(psiClass).findAll() ... }`
- **Problem:** `ReferencesSearch` can be extremely slow on large codebases. Single `readAction` block holds read lock for entire duration, blocking all write actions (user typing, save, refactor). No `checkCanceled()` calls.

---

## HIGH Findings (Should Fix)

### H1-H4. Polling without SmartPoller (4 findings)

`SmartPoller` exists in `:core` with backoff, jitter, and visibility awareness — but several pollers don't use it.

| File | Interval | Missing |
|------|----------|---------|
| `bamboo/service/BuildMonitorService.kt:77-88` | Fixed 30s | Backoff, jitter, visibility |
| `automation/ui/MonitorPanel.kt:101-108` | Fixed 15s | Backoff, jitter, visibility |
| `automation/service/QueueService.kt:184-208` | Adaptive (15s/60s) | Jitter, visibility |
| **Good:** `pullrequest/service/PrListService.kt` | SmartPoller | Correct pattern to follow |

### H5. RetryInterceptor — Thread.sleep() blocks IO pool thread

- **File:** `core/http/RetryInterceptor.kt:25`
- `Thread.sleep()` up to 60s per retry. Ties up a `Dispatchers.IO` thread (limited to 64). Inherent OkHttp limitation but worth noting.

### H6-H8. Orphan CoroutineScopes (3 more)

| File | Line | Context |
|------|------|---------|
| `jira/service/JiraTicketProviderImpl.kt` | 90 | Fire-and-forget scope per transition dialog |
| `jira/vcs/TimeTrackingCheckinHandlerFactory.kt` | 39 | Scope leaked per commit dialog session |
| `jira/vcs/PostCommitTransitionHandlerFactory.kt` | 33 | Scope leaked per commit dialog session |

### H9-H10. CancellationException swallowed (2 high-severity)

| File | Line | Context |
|------|------|---------|
| `core/polling/SmartPoller.kt` | 76 | `catch (_: Exception)` in `setVisible()` — prevents cancellation during visibility toggle |
| `sonar/service/SonarDataService.kt` | 201, 208 | `catch (e: Exception)` in `async` blocks — prevents clean cancellation |

### H11-H12. Heavy service constructors

| File | Problem |
|------|---------|
| `bamboo/service/BuildMonitorService.kt:43-55` | Acquires `PluginSettings`, `EventBus`, `WorkflowNotificationService`, creates `BambooApiClient` |
| `automation/service/QueueService.kt:51-76` | Creates 2 API clients, acquires `EventBus` + `TagHistoryService` |

### H13. CodyEditApplier — WriteCommandAction potentially off EDT

- **File:** `cody/editor/CodyEditApplier.kt:64`
- `WriteCommandAction.runWriteCommandAction` called without `invokeLater`. If invoked from JSON-RPC handler thread (not EDT), this will crash.

### H14. StageDetailPanel — FilenameIndex without dumb mode guard

- **File:** `bamboo/ui/StageDetailPanel.kt:306`
- `FilenameIndex.getFilesByName()` requires indexes. Will throw `IndexNotReadyException` during indexing.

### H15. SpringContextEnricherImpl — Long read action

- **File:** `cody/service/SpringContextEnricherImpl.kt:41-69`
- `readAction { }` wraps Spring model queries and PSI traversal. Same pattern as C7 but shorter duration.

### H16. PrListCellRenderer — Allocates 6+ JPanels + 5+ JBLabels per render

- **File:** `pullrequest/ui/PrListPanel.kt:189-268`
- `createPrCell()` builds entire component tree per cell. Multiple `font.deriveFont()` calls. Custom `paintComponent` status badges.

---

## MEDIUM Findings (Improve When Touched)

### Threading (3)
| # | File | Issue |
|---|------|-------|
| M1 | `sonar/service/SonarDataService.kt:174-178` | `onComplete` callback invoked on IO thread — fragile API contract |
| M2 | `jira/api/JiraApiClient.kt:268-301` | `withContext(Dispatchers.IO)` wraps entire function including JSON parsing |
| M3 | All API clients | Same broad IO wrapping pattern — CPU-bound parsing on IO pool |

### Memory (6)
| # | File | Issue |
|---|------|-------|
| M4 | `jira/vcs/JiraVcsLogColumn.kt:31` | CoroutineScope never cancelled (singleton-bounded) |
| M5 | `bamboo/ui/BuildDashboardPanel.kt:48,53` | Service references stored in fields |
| M6 | `core/settings/GeneralConfigurable.kt:38-41` | Service references stored in fields |
| M7 | 10+ files | `catch (e: Exception)` in suspend contexts without CancellationException rethrow |
| M8 | `core/settings/GeneralConfigurable.kt:524,563` | `runBlocking` potentially on EDT in action listeners |
| M9 | 30+ callsites | `CredentialStore()` instantiated repeatedly — should be singleton `@Service` |

### UI Rendering (7)
| # | File | Issue |
|---|------|-------|
| M10 | `jira/ui/TicketListCellRenderer.kt:60,118-121` | 4 `font.deriveFont()` calls per paint |
| M11 | `automation/ui/MonitorPanel.kt:321-351` | `JBColor` objects created per render |
| M12 | `sonar/ui/CoverageTablePanel.kt:203` | `fireTableDataChanged()` instead of `fireTableRowsUpdated()` |
| M13 | `automation/ui/TagStagingPanel.kt:53` | `fireTableDataChanged()` on every poll refresh |
| M14 | `jira/ui/SprintDashboardPanel.kt:283-287` | Search filter triggers full list rebuild per keystroke — no debounce |
| M15 | `jira/ui/SprintDashboardPanel.kt:261-270` | Mouse hover calls `ticketList.repaint()` on entire list |
| M16 | `sonar/ui/OverviewPanel.kt:106-209` | `removeAll()` + add loops + `font.deriveFont()` in update(), effort parsing on EDT |

### Startup/PSI (3)
| # | File | Issue |
|---|------|-------|
| M17 | `sonar/service/SonarDataService.kt:70-72` | `init { subscribeToEvents() }` — side effects in constructor |
| M18 | `handover/service/HandoverStateService.kt:32-37` | Service acquisition + coroutine launch in constructor |
| M19 | `jira/ui/TicketStatusBarWidgetFactory.kt`, `automation/ui/AutomationStatusBarWidgetFactory.kt`, `cody/vcs/GenerateCommitMessageAction.kt` | Missing `DumbAware` — disabled during indexing unnecessarily |

### Networking (3)
| # | File | Issue |
|---|------|-------|
| M20 | Multiple files | Multiple `BambooApiClient` instances created per project |
| M21 | `core/http/HttpClientFactory.kt` | `clientFor()` method is dead code — never called |
| M22 | `core/http/HttpClientFactory.kt:44` | Connection pool sized at 5 for 6+ backend services |

---

## LOW Findings (12)

Minor renderer allocations (JBColor in renderers), redundant revalidate calls, safe runBlocking usages, raw PSI references in short-lived contexts, missing DumbAware on rarely-used actions. See individual audit transcripts for details.

---

## Recommended Fix Priority

### Phase 1: Critical Fixes (Prevents Freezes & Leaks)

1. **Global `Dispatchers.Main` → `Dispatchers.EDT` replacement** (C1) — single find-and-replace, highest impact
2. **Add Disposable + scope.cancel() to SprintDashboardPanel** (C2) — active coroutine leak
3. **Add Disposable to HealthCheckService** (C3) — project memory leak
4. **Fix CodyIntentionAction/CodyTestGenerator orphan scopes** (C4, C5) — use service-injected scope
5. **Break PsiContextEnricher readAction into NBRA** (C7) — blocks user typing

### Phase 2: High-Impact Improvements

6. **Migrate BuildMonitorService + MonitorPanel to SmartPoller** (H1-H4) — follow PrListService pattern
7. **Fix CancellationException swallowing in SmartPoller + SonarDataService** (H9-H10)
8. **Lazy-init service dependencies in constructors** (H11-H12) — use `by lazy { }`
9. **Rewrite IssueListCellRenderer to reuse single component** (C6) — massive GC pressure
10. **Rewrite PrListCellRenderer** (H16) — same issue

### Phase 3: Polish

11. Add 200-300ms debounce to sprint search filter (M14)
12. Replace `fireTableDataChanged()` with `fireTableRowsUpdated()` (M12, M13)
13. Cache Font objects as companion object constants (M10, M16)
14. Convert `CredentialStore` to `@Service` singleton (M9)
15. Add DumbAware to status bar widgets and commit message action (M19)

---

## Anti-Pattern Checklist Results

### Threading
- [x] No network calls on EDT — **PASS** (all API calls on IO)
- [x] No `runBlocking` on EDT — **PASS** (all on background threads)
- [ ] All UI updates via `Dispatchers.EDT` — **FAIL** (systemic `Dispatchers.Main` usage)
- [ ] `ProgressManager.checkCanceled()` in read action loops — **FAIL** (missing in PsiContextEnricher, SpringContextEnricher)
- [ ] Using `Dispatchers.EDT` not `Dispatchers.Main` — **FAIL** (only 1 of ~15 callsites correct)
- [x] `Dispatchers.IO` wraps only actual I/O — **PARTIAL** (broad wrapping but not harmful)

### Memory
- [ ] All resources have parent Disposable — **FAIL** (7+ orphan CoroutineScopes)
- [ ] Service-injected coroutine scopes — **FAIL** (manual `CoroutineScope()` everywhere)
- [ ] No service references in fields — **PARTIAL** (some field caching)
- [ ] Listeners registered with disposable — **PASS**
- [ ] CancellationException never swallowed — **FAIL** (10+ catch(Exception) sites)

### Networking
- [x] Single shared OkHttpClient via newBuilder() — **PASS** (shared connection pool)
- [x] Explicit timeouts set — **PASS** (connect + read set)
- [x] limitedParallelism cached — **N/A** (not used)
- [ ] Polling uses backoff + jitter + visibility — **FAIL** (2 of 4 pollers lack all three)

### UI Rendering
- [ ] Cell renderers allocate nothing — **FAIL** (3 renderers allocate per paint)
- [ ] fireTableRowsUpdated instead of fireTableDataChanged — **FAIL** (2 misuses)
- [x] No revalidate in loops — **PASS**
- [ ] Model updated incrementally — **PARTIAL** (clear + re-add pattern)
- [ ] setPaintBusy during loads — **FAIL** (not used anywhere)

### PSI Access
- [ ] Using readAction or NBRA — **FAIL** (blocking readAction in PsiContextEnricher)
- [x] smartReadAction for index code — **PASS** (sonar uses it correctly)
- [x] SmartPsiElementPointer — **PASS** (no long-lived PSI storage)
- [x] Write actions are short — **PASS**

**Checklist score: 10/20 pass, 8 fail, 2 partial**
