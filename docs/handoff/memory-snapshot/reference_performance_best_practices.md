# IntelliJ Plugin Performance Best Practices

Research completed 2026-03-19. All findings are practical and applicable to the Workflow Orchestrator plugin.

---

## 1. EDT Thread Safety and Performance

### The 16ms Rule
Events on the EDT must be processed within **16ms** to maintain 60fps. Any operation exceeding this causes visible jank. The IDE becomes completely unresponsive if the EDT is blocked.

### Operations Prohibited on EDT
- VFS traversal, PSI parsing, reference resolution, index queries
- Network calls (HTTP requests to Jira, Bamboo, SonarQube, etc.)
- File I/O beyond trivial reads
- `runBlocking` (deadlocks the EDT)
- Modifying PSI/VFS/project model from UI renderers

### Detecting EDT Violations
1. **`SlowOperations.assertSlowOperationsAreAllowed()`** — The platform calls this automatically. Fires in EAP, internal mode, and development instances (`runIde`). Any slow operation on EDT triggers an assertion error.
2. **Thread Access Info plugin** (marketplace) — Shows threading rule violations during debugging.
3. **Thread dumps** — Search for `AWT-EventQueue-N` to see what the EDT is doing. If it's waiting on `upgradeWritePermit`, a background read action is blocking it.
4. **PerformanceWatcher** — Built-in platform component that monitors EDT responsiveness and generates automatic thread dumps when the IDE is sluggish.

### Profiling EDT Blocking
- Run IDE with `-Didea.is.internal=true` to enable internal mode assertions
- Use **Help > Diagnostic Tools > Activity Monitor** to see EDT utilization
- Capture thread dumps via **Help > Diagnostic Tools > Thread Dump** or automatically via PerformanceWatcher
- Look for plugin code in EDT stack traces — that's where the freeze originates

### Plugin-Specific Concerns
- All 5 service API calls (Jira, Bamboo, Sonar, Bitbucket, Nexus) MUST be on `Dispatchers.IO`
- Cody JSON-RPC stdio communication MUST NOT block EDT
- UI updates after API responses must use `withContext(Dispatchers.EDT)` or `invokeLater`
- The "Test Connection" buttons already use `runBackgroundableTask` — correct pattern

---

## 2. Memory Management

### Disposable Hierarchy Rules
- Every resource with a lifecycle MUST have a parent Disposable
- **NEVER use `Application` or `Project` directly as parent** in plugin code — these outlive plugin unloads, causing leaks
- Use service-level Disposable (the service itself) as parent
- `Disposer.register(parent, child)` — children are disposed BEFORE parents
- Always check `Disposer.isDisposed()` before executing async callbacks

### Choosing Parent Disposable
| Resource | Parent Disposable |
|---|---|
| Plugin-lifetime resources | Application/project-level service |
| Dialog resources | `DialogWrapper.getDisposable()` |
| Tool window content | `Content.setDisposer()` |
| Short-lived resources | `Disposer.newDisposable()` with explicit parent |

### CoroutineScope Lifecycle (2024.1+)
- **Inject scope via constructor**: `MyService(project: Project, cs: CoroutineScope)`
- Each service gets its own scope with `Dispatchers.Default` and `CoroutineName`
- **DEPRECATED**: `Application.getCoroutineScope()`, `Project.getCoroutineScope()` — causes leaks
- Platform scopes are supervisor scopes (child failures don't cancel parent)
- Intersection scopes: Application x Plugin, Project x Plugin — auto-cancel on plugin unload

### Detecting Memory Leaks
- In test/internal/debug modes, platform captures stack traces during `Disposer.register()`
- On application exit, undisposed objects generate errors with the registration call site
- Use **Help > Diagnostic Tools > Memory** or the Memory tab in debugger to track object retention
- `AstLoadingFilter` in production prevents accidental AST loading (memory hog)

### Plugin-Specific Concerns
- BuildMonitorService, QueueService, PrListService, SonarDataService all have CoroutineScope — verify they cancel on dispose
- CodyAgentManager holds an OS process — must terminate on dispose
- UI panels with polling jobs must cancel in their dispose() methods
- The `ConcurrentHashMap<ServiceType, OkHttpClient>` in HttpClientFactory never clears — acceptable since it's bounded (6 entries max)

---

## 3. Network Call Optimization

### OkHttpClient Connection Pool
**Current state**: HttpClientFactory creates a `baseClient` with default pool (5 idle connections, 5 min keepalive).

**Recommended tuning for our plugin**:
```
ConnectionPool(
    maxIdleConnections = 5,    // We talk to 5-6 hosts; one per service is ideal
    keepAliveDuration = 3,     // 3 minutes; shorter than default since polling is 60s
    TimeUnit.MINUTES
)
```

**Key practices**:
- Single `OkHttpClient` instance shared via `newBuilder()` — already doing this correctly
- `newBuilder()` shares the connection pool and thread pool with the parent — our `clientFor()` pattern is correct
- Never create `OkHttpClient()` ad-hoc — the `GeneralConfigurable.kt:330` call creates a throwaway client for `whoami`; should use the shared factory

### HTTP/2 Multiplexing
- OkHttp supports HTTP/2 automatically when the server supports it
- Multiplexing allows multiple requests over a single TCP connection
- Benefit: Bamboo build polling + log fetching can share one connection
- No code changes needed — OkHttp negotiates HTTP/2 via ALPN

### Response Caching with ETag/If-Modified-Since
- OkHttp has built-in ETag support — just set `Cache`:
```kotlin
OkHttpClient.Builder()
    .cache(Cache(cacheDir, maxSize = 10L * 1024 * 1024)) // 10MB
    .build()
```
- Server must send `ETag` or `Last-Modified` headers
- OkHttp automatically sends `If-None-Match` / `If-Modified-Since` on subsequent requests
- 304 responses return cached body transparently
- **Applicable to**: Sprint ticket lists (change rarely), quality gate status, build results (immutable once complete)
- **Not applicable to**: Running build status (changes every few seconds)

### Throttling/Debouncing API Calls
- Use `MergingUpdateQueue` or `Flow.debounce()` to batch rapid-fire events
- Example: Multiple file saves in quick succession should trigger ONE sonar check, not one per file
- Minimum interval between identical API calls: 5 seconds recommended

---

## 4. Background Task Best Practices

### Task API Comparison
| API | Use Case | Cancellation | Progress UI |
|---|---|---|---|
| `ProgressManager.run(Task.Backgroundable)` | User-triggered operations (Test Connection, Health Check) | Via progress indicator cancel button | Yes (status bar) |
| Service-injected `CoroutineScope` | Service lifecycle tasks (polling, monitoring) | Automatic on service dispose | No (silent) |
| `ReadAction.nonBlocking()` | Background reads needing PSI/index access | Auto-cancel on write action | No |
| `Application.executeOnPooledThread` | Legacy; avoid in new code | Manual | No |

### Cancellation Best Practices
- Call `ProgressManager.checkCanceled()` frequently in long-running read actions (every loop iteration)
- Use `expireWith(disposable)` on `NonBlockingReadAction` to auto-cancel when parent disposes
- Coroutine cancellation: check `isActive` or use `ensureActive()` in compute-heavy loops
- **Never swallow CancellationException** — it breaks structured concurrency

### CPU-bound vs IO-bound Dispatching
- `Dispatchers.Default` — CPU-bound work (JSON parsing, diff computation, string formatting). Limited to CPU core count threads.
- `Dispatchers.IO` — Blocking I/O (HTTP calls, file reads, process I/O). Limited to 64 threads.
- **Critical**: `Dispatchers.Default` has very few threads. If all are blocked (e.g., by `runBlocking`), the entire coroutine system starves. Never use `runBlocking` on Default threads.
- Store `limitedParallelism()` results in companion objects — each call creates a NEW dispatcher instance

### Plugin-Specific Concerns
- Cody agent stdio I/O should use `Dispatchers.IO`
- JSON parsing of large build logs should use `Dispatchers.Default`
- The 5 service polling loops are IO-bound — correct to use scope.launch on IO

---

## 5. Swing/UI Performance

### Avoiding Excessive Revalidate/Repaint
- Batch multiple model changes before calling `revalidate()` + `repaint()` once
- Use `MergingUpdateQueue` (deprecated in favor of `Flow.debounce()`) to coalesce rapid UI updates
- Never call `revalidate()` inside a loop — accumulate changes, then revalidate once
- `setPaintBusy(true)` on JBList/Tree during background loads — shows spinner, prevents layout thrashing

### ListModel Updates vs Recreating
- **DO**: Update the existing `DefaultListModel` via `setElementAt()`, `addElement()`, `removeElement()`
- **DON'T**: Create a new model and call `setModel()` on every refresh — this resets scroll position, selection, and forces full re-render
- For JBTable: update `AbstractTableModel` rows and fire `fireTableRowsUpdated(first, last)` for changed rows only
- `fireTableDataChanged()` is expensive — use row-level fire methods when possible

### JBTable Performance with Large Datasets
- JTable/JBTable uses a flyweight renderer pattern — only visible rows are rendered
- This means 10,000 rows in the model is fine as long as rendering is fast
- Keep `getValueAt()` and cell renderer logic O(1) — no computation in renderers
- If data fetch is slow, load paginated data and append to model

### Lazy Loading in Trees and Lists
- For trees: implement `TreeNode` lazily — load children only when node is expanded
- For lists: load first page, add "Load More" action or scroll listener for pagination
- `setPaintBusy(true)` while loading — visual feedback without blocking

### Plugin-Specific Concerns
- Sprint dashboard (JBList of tickets): update model incrementally, not replace
- Build dashboard (JBTable of builds): use `fireTableRowsUpdated` for status changes
- Quality issues list: could grow large — ensure cell renderers are trivial
- Tag staging panel: small dataset, no performance concern

---

## 6. IntelliJ-Specific Performance APIs

### ReadAction.nonBlocking() (NBRA)
```kotlin
ReadAction.nonBlocking<Result> {
    // Safe to access PSI, indexes here
    // Auto-cancels when write action arrives
    computeResult()
}
.expireWith(disposable)           // Cancel when parent disposes
.finishOnUiThread(ModalityState.any()) { result ->
    // Update UI here — guaranteed on EDT
    updatePanel(result)
}
.submit(AppExecutorUtil.getAppExecutorService())
```
- Use for any background operation that needs to read PSI or indexes
- The `finishOnUiThread` callback is the safe way to update UI after computation

### Coroutine Read Actions (2024.1+)
- `readAction { }` — write-allowing read action (WARA); cancels and retries when write action arrives
- `smartReadAction { }` — waits for smart mode (indexing complete) before executing
- `readActionBlocking { }` — blocks write actions until complete; use sparingly
- **No suspension allowed inside read action blocks** — prepare data outside, read inside

### DumbAware vs Non-DumbAware
- Mark actions, tool windows, and startup activities as `DumbAware` when they don't need indexes
- Non-DumbAware components are disabled during indexing — can frustrate users
- Our tool window tabs (Sprint, Build, Quality, Automation, Handover) should all be DumbAware since they query external APIs, not local indexes
- Only Sonar gutter markers and ExternalAnnotator need smart mode (they map to PSI elements)

### Slow Operations Detection
- `SlowOperations.assertSlowOperationsAreAllowed()` fires automatically in dev mode
- Registry key `ide.slow.operations.assertion` controls assertion behavior
- In 2025.1+, slow operations on EDT throw exceptions in EAP builds

### CachedValue for Expensive Computations
```kotlin
CachedValuesManager.getManager(project).createCachedValue {
    CachedValueProvider.Result.create(
        expensiveComputation(),
        ModificationTracker.NEVER_CHANGED  // or appropriate dependency
    )
}
```
- Cache Sonar issue data per file (invalidate on file change or poll refresh)
- Cache parsed build log sections (invalidate on new build result)

---

## 7. Polling Optimization

### Current State
- Build polling: 60s interval
- PR list polling: 60s interval
- Queue polling: custom interval
- Automation monitor: custom interval

### Exponential Backoff Pattern
```kotlin
var interval = baseInterval  // e.g., 30_000L
while (isActive) {
    val result = fetchData()
    if (result.hasChanges) {
        interval = baseInterval  // Reset on activity
        processChanges(result)
    } else {
        interval = (interval * 1.5).toLong().coerceAtMost(maxInterval) // e.g., 300_000L
    }
    delay(interval)
}
```
- Start at 30s, back off to 5 min max when nothing changes
- Reset to base interval when changes detected

### Conditional Requests (304 Not Modified)
- Send `If-Modified-Since` or `If-None-Match` headers with polls
- Server returns 304 with empty body — saves bandwidth and server CPU
- OkHttp Cache handles this automatically if enabled
- **Jira/Bamboo/Sonar all support ETag** — enable OkHttp cache to leverage this

### Smart Polling (Activity-Aware)
```kotlin
// Poll faster when user is actively looking at the tab
val interval = if (isTabVisible && isIdeActive) {
    ACTIVE_INTERVAL   // 30s
} else if (isIdeActive) {
    IDLE_INTERVAL     // 120s
} else {
    BACKGROUND_INTERVAL  // 300s (5 min)
}
```
- Detect tab visibility via `ComponentListener` or `AncestorListener`
- Detect IDE focus via `ApplicationActivationListener`
- PR dashboard already has visibility-based polling — extend to all panels

### Jitter
- Add random jitter (0-10% of interval) to prevent all polls hitting the server simultaneously
- Especially important with 5 services polling independently:
```kotlin
delay(interval + Random.nextLong(interval / 10))
```

---

## 8. Common Real-World Plugin Performance Issues

### Top Freeze Causes (from JetBrains investigation, Sep 2025)

1. **Read-Write Lock Blocking** — Background thread holds read lock, EDT needs write lock. Fix: call `ProgressManager.checkCanceled()` frequently in read actions.

2. **Thread Starvation** — All `Dispatchers.Default` threads blocked by `runBlocking`. Fix: never use `runBlocking` on dispatcher threads; use `Dispatchers.IO` for blocking operations.

3. **Service Initialization Deadlocks** — Service init needs read action, but EDT holds write lock. Fix: avoid read actions in service constructors; use lazy initialization.

4. **Background Write Actions** — Write action on background thread blocks EDT from acquiring write-intent lock. Fix: keep write actions short; prepare data outside write action.

5. **Non-Cancellable Read Actions** — Long read action without `checkCanceled()` blocks all write actions (including user typing). Fix: use NBRA or WARA coroutine read actions.

### How JetBrains Profiles Plugin Performance
- Automatic thread dumps when IDE is unresponsive (PerformanceWatcher)
- Freeze reports from users include EDT stack traces
- Plugin Verifier checks API compatibility but not performance
- Internal `SuvorovProgress` emergency mode indicates severe freeze

### Specific Risks for Our Plugin
1. **Cody agent process** — If stdio pipe blocks, could starve IO threads. Use dedicated single-thread dispatcher.
2. **Build log parsing** — Large logs (100K+ lines) parsed on Default dispatcher could starve it. Use `Dispatchers.IO` or dedicated dispatcher.
3. **5 simultaneous polling loops** — All hitting network every 60s. Stagger start times by a few seconds.
4. **OkHttpClient created ad-hoc** in GeneralConfigurable — Creates new connection pool each time. Should use shared factory.
5. **JSON deserialization of large responses** — Sprint with 200+ tickets or build with many stages. Consider streaming JSON parser for very large responses.

---

## Summary: Priority Actions for Our Plugin

### High Priority (Prevents Freezes)
1. Audit all EDT code paths — ensure no network calls or heavy computation on EDT
2. Add `checkCanceled()` calls in any read action loops
3. Fix ad-hoc `OkHttpClient()` creation in GeneralConfigurable
4. Verify all CoroutineScope instances cancel on service dispose

### Medium Priority (Improves Responsiveness)
5. Enable OkHttp Cache for conditional requests (ETag/304)
6. Implement activity-aware smart polling (faster when visible, slower when hidden)
7. Add exponential backoff to polling when no changes detected
8. Use `fireTableRowsUpdated` instead of `fireTableDataChanged` for incremental updates

### Low Priority (Polish)
9. Configure ConnectionPool explicitly (5 idle, 3 min keepalive)
10. Add jitter to polling intervals
11. Stagger polling start times across services
12. Consider `Flow.debounce()` for rapid event coalescing
