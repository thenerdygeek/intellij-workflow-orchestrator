# Phase 3 Research — IntelliJ Platform Native Caching Primitives

**Scope:** Phase 3 of `refactor/cleanup-perf-caching`. Target platform: IntelliJ IDEA 2025.1+, Kotlin 2.1.10.

**Three Phase 3 caching targets** (assessed at end of each section):

- **(a) HTTP response body caching** — e.g. Jira `/rest/api/2/issue/KEY`, Bamboo build results, Bitbucket PR lists.
- **(b) `RepoContextResolver` memoization** — `core/src/main/kotlin/com/workflow/orchestrator/core/settings/RepoContextResolver.kt`, resolving editor file → `GitRepository` → `RepoConfig` on every action/panel refresh.
- **(c) `SmartPoller` dedup** — `core/src/main/kotlin/com/workflow/orchestrator/core/polling/SmartPoller.kt`; two panels polling the same endpoint fire two HTTP calls.

**TL;DR verdict for each target**

| Target | Primitive | Rationale |
|---|---|---|
| (a) HTTP body cache | **Keep OkHttp `Cache` (ETag/304); do NOT use `CachedValue`** | OkHttp's RFC 7234 cache already handles what we want. `HttpRequests` has no body cache. `CachedValue` is for PSI-dependent computation, not raw bytes. |
| (b) `RepoContextResolver` | **`CachedValuesManager.getCachedValue` on the `Project` + composite `ModificationTracker`** (`VcsRepositoryMappingListener`-backed `SimpleModificationTracker` + a settings-change tracker) | Pure function of `(GitRepositoryManager.repositories, PluginSettings.repos, current editor file)`. Exactly the shape `CachedValue` was designed for. |
| (c) `SmartPoller` dedup | **`ReadAction.nonBlocking { ... }.coalesceBy(pollKey)`** OR a lightweight `ConcurrentHashMap<Key, Deferred<T>>` — prefer the ad-hoc `Deferred` map because polling doesn't require read-lock semantics | `coalesceBy` *is* the platform's dedup primitive, but it cancels-losers which is the wrong semantic for polling (we want share-result). A `Deferred` map gives share-result naturally. |

---

## 1. `CachedValuesManager` + `CachedValue<T>`

### FQNs

- `com.intellij.psi.util.CachedValue<T>` (interface)
- `com.intellij.psi.util.CachedValuesManager` (project-level service)
- `com.intellij.psi.util.CachedValueProvider<T>` (functional interface, returns `Result<T>`)
- `com.intellij.psi.util.CachedValueProvider.Result<T>` (holds value + dependency array)
- Source: [intellij-community/platform/core-api/src/com/intellij/psi/util/CachedValue.java](https://github.com/JetBrains/intellij-community/blob/master/platform/core-api/src/com/intellij/psi/util/CachedValue.java), [CachedValuesManager.java](https://github.com/JetBrains/intellij-community/blob/master/platform/core-api/src/com/intellij/psi/util/CachedValuesManager.java)

### Lifecycle

1. **Creation** — prefer `CachedValuesManager.getCachedValue(dataHolder, key, provider)` (static helper). It looks up an existing `CachedValue` stored under `key` in `dataHolder`'s user data; creates and puts one if absent. `getCachedValue(psiElement, provider)` (no explicit key) is the PSI-specialised form that auto-creates a stable key.
2. **First read** — provider runs, returns `Result<T>(value, dep1, dep2, ...)`. Result is stored internally in a `SoftReference`.
3. **Subsequent reads** — if any dependency's `getModificationCount()` differs from the stamp snapshot taken when the value was cached, OR the `SoftReference` was GC'd, the provider re-runs.
4. **GC** — values are held via `SoftReference`, so they survive normal operation but can be reclaimed under memory pressure. Plugin unload clears all `CachedValue` state.
5. **Idempotence** — the platform runs `IdempotenceChecker.checkEquivalence()` on repeated provider invocations; non-equivalent results trigger a warning/exception. Providers MUST be pure functions of `(dataHolder, dependencies, app/project services)`.

### Key semantic: invalidation = "dependency counter moved"

Nothing actively invalidates the cache. Invalidation is **lazy** — on the next `getValue()`, the cached entry's stored dependency stamps are compared to current counters. If any has advanced, re-compute. This means:

- You never call `invalidate()`. You either **increment a `ModificationTracker`** that the cache depends on, or you wait for GC.
- Cache is effectively free when dependencies haven't moved (one array-compare per dependency).

### `CachedValueProvider.Result<T>` dependency list — what's available

From `com.intellij.psi.util.PsiModificationTracker` and `com.intellij.openapi.util.ModificationTracker`:

| Dependency | FQN | Increments when |
|---|---|---|
| `PsiModificationTracker.MODIFICATION_COUNT` | `com.intellij.psi.util.PsiModificationTracker` static `Key` | Any physical PSI change, or dumb mode enter/exit |
| `psiModTracker.forLanguage(Language)` | ` ` | PSI changes in files of that language only |
| `psiModTracker.forLanguages(Predicate<Language>)` | | Multi-language filtered |
| `ModificationTracker.EVER_CHANGED` | `com.intellij.openapi.util.ModificationTracker` | Every invocation — forces always-recompute |
| `ModificationTracker.NEVER_CHANGED` | | Never — cache lives forever (GC only) |
| `ProjectRootManager.getInstance(project)` | implements `ModificationTracker` | Module roots / SDK / libraries changed |
| `VirtualFileManager.getInstance().getModificationCount()` | since 2019.2; via `VirtualFileManager` — implements `ModificationTracker` | Any VFS change in the IDE |
| `com.intellij.openapi.externalSystem.ExternalSystemModificationTracker` | ExternalSystem plugin | Gradle/Maven sync |
| `com.intellij.openapi.vfs.newvfs.ManagingFS` | — | Deep VFS ops |
| `DumbService.getInstance(project).modificationTracker` | | Enter/exit dumb mode |
| `SimpleModificationTracker` | `com.intellij.openapi.util.SimpleModificationTracker` (backed by `AtomicLong`) | Whenever you call `.incModificationCount()` — **this is your custom lever** |

**Documentation gap:** the topics listed in the task prompt — `ProjectLevelVcsManager`, `FileModificationTracker` — do not expose `getModificationCount()` directly. For VCS changes you roll your own `SimpleModificationTracker` driven by `VcsRepositoryMappingListener` (see §2).

### Composite dependencies

Just add multiple items to the vararg:

```kotlin
CachedValueProvider.Result.create(
    computeValue(),
    PsiModificationTracker.MODIFICATION_COUNT,
    ProjectRootManager.getInstance(project),
    myCustomTracker
)
```

The cache is valid only while **all** counters are unchanged.

### Thread safety

From the source: "If several threads request the cached value simultaneously, the computation may be run concurrently on more than one thread." Providers must be **side-effect-free** and tolerate being called concurrently. The `getCachedValue` call itself uses `UserDataHolderEx.putUserDataIfAbsent` (lock-free CAS) where possible.

- You can **read** a `CachedValue` from any thread, but the **provider body must be legal for the callers' context**. If the provider touches PSI, callers need a read-lock (or the platform will wrap the read for you if you use `getProjectPsiDependentCache`).
- For non-PSI providers (our use case — `GitRepository` + `PluginSettings`) this means: make sure the provider can run on any thread. Reading `GitRepositoryManager.getInstance(project).repositories` and settings state **does not** require a read-lock.

### Memory lifecycle

- Held via `SoftReference` — GC-reclaimable.
- Stored in the `UserDataHolder` passed to `getCachedValue`. If you cache on `project`, it dies with the project. If on a `VirtualFile`, dies with the VFile. If on a `PsiElement`, dies when the PSI tree is rebuilt (usually).
- Plugin unload/reload clears **all** `CachedValue` state (documented in [Dynamic Plugins guide](https://plugins.jetbrains.com/docs/intellij/dynamic-plugins.html)).

### `getCachedValue` vs manual `createCachedValue`

`CachedValuesManager.getCachedValue(dataHolder, key, provider)`:
- Looks up by `Key<CachedValue<T>>` in `dataHolder.getUserData`.
- One-liner; idiomatic; auto-creates the value the first time.

`CachedValuesManager.getManager(project).createCachedValue(provider, trackValue=false)`:
- Returns a bare `CachedValue<T>` object you store yourself (in a field, in user data, anywhere).
- Needed when you want to pre-create the value or store it somewhere other than `UserDataHolder`.

**Recommendation**: always prefer the static `getCachedValue(dataHolder, key, provider)` form.

### Is it appropriate for non-PSI data?

- **HTTP response caching: NO.** `CachedValue` is designed for small, deterministic computations keyed by `(dataHolder, dependencies)`. HTTP responses are byte arrays whose validity is governed by **server-side ETag / Last-Modified / max-age**, not by IDE-side PSI/VFS state. Using `CachedValue` for HTTP bodies would either keep stale data forever (no good invalidation signal from `ModificationTracker`) or invalidate too aggressively (every PSI edit). OkHttp's `Cache` is the right answer.
- **`RepoContextResolver` memoization: YES.** Pure function of `(git repositories list, settings repos, current editor file)`. Wrap all three in a composite dependency and the cache works perfectly.

### Example: `RepoContextResolver` with `CachedValue`

```kotlin
@Service(Service.Level.PROJECT)
class RepoContextResolver(private val project: Project) : Disposable {

    // Bumped by VcsRepositoryMappingListener + settings-change listener (§2)
    private val invalidationTracker = SimpleModificationTracker()

    private val primaryKey = Key.create<CachedValue<RepoConfig?>>("wo.primaryRepo")

    fun getPrimary(): RepoConfig? =
        CachedValuesManager.getManager(project).getCachedValue(project, primaryKey, {
            val value = PluginSettings.getInstance(project).getPrimaryRepo()
            CachedValueProvider.Result.create(
                value,
                invalidationTracker,
                ProjectRootManager.getInstance(project) // module changes
            )
        }, false)

    // Called by VcsRepositoryMappingListener and settings-change event
    fun invalidate() = invalidationTracker.incModificationCount()

    override fun dispose() { /* message-bus connection disposed via Disposer */ }
}
```

---

## 2. `ModificationTracker` design patterns

### The interface

```java
// com.intellij.openapi.util.ModificationTracker  (SAM interface)
public interface ModificationTracker {
    long getModificationCount();

    ModificationTracker EVER_CHANGED   = new AtomicLong()::incrementAndGet;
    ModificationTracker NEVER_CHANGED  = () -> 0;
}
```

Source: [ModificationTracker.java](https://github.com/JetBrains/intellij-community/blob/master/platform/util/src/com/intellij/openapi/util/ModificationTracker.java).

### Writing a custom tracker

Subclass (or just use as-is) `com.intellij.openapi.util.SimpleModificationTracker`:

```kotlin
// platform/util/src/com/intellij/openapi/util/SimpleModificationTracker.java
// Minimal public API: getModificationCount() + incModificationCount()
// Backed by AtomicLong for thread safety.

class RepoConfigTracker : SimpleModificationTracker()
```

Call `incModificationCount()` whenever the tracked state changes — any `CachedValue` listing this tracker in its dependency array will re-compute on its next read.

### Hooking platform events

| Event | Topic / Listener | Increment call |
|---|---|---|
| VCS repository roots changed | `VcsRepositoryMappingListener.BUS` (`com.intellij.dvcs.repo.VcsRepositoryMappingListener`) | ✓ |
| Git branch / remote state | `GitRepository.GIT_REPO_CHANGE` | ✓ |
| File editor selection | `FileEditorManagerListener.FILE_EDITOR_MANAGER` → `selectionChanged` | ✓ |
| VFS change | `BulkFileListener` via `VirtualFileManager.VFS_CHANGES` | ✓ |
| Project roots | `ModuleRootListener` via `ProjectTopics.PROJECT_ROOTS` | or just use `ProjectRootManager.getInstance(project)` as a tracker directly |
| Plugin settings changed | Custom — fire your own event | ✓ |

### Canonical wiring pattern (project-scope service)

```kotlin
@Service(Service.Level.PROJECT)
class RepoStateTracker(project: Project) : SimpleModificationTracker(), Disposable {
    init {
        val bus = project.messageBus.connect(this) // tied to this service's lifetime
        bus.subscribe(VcsRepositoryMappingListener.BUS, VcsRepositoryMappingListener {
            incModificationCount()
        })
        bus.subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener {
            incModificationCount()
        })
    }
    override fun dispose() {} // messageBus connection auto-disposed via `connect(this)`
    companion object {
        fun getInstance(p: Project): RepoStateTracker = p.service()
    }
}
```

Docs: [Plugin Listeners](https://plugins.jetbrains.com/docs/intellij/plugin-listeners.html).

---

## 3. `UserDataHolder` / `Key<T>`

### FQNs & implementers

- `com.intellij.openapi.util.UserDataHolder` — interface with `getUserData(Key<T>)` / `putUserData(Key<T>, T)`
- Implemented by: `Project`, `PsiElement`, `VirtualFile`, `Editor`, `Document`, `Module`, most `@Service` classes extending `UserDataHolderBase`.
- `com.intellij.openapi.util.Key<T>` — typed key (`Key.create<Foo>("my.key.id")`).

### UserDataHolder vs CachedValuesManager

| Feature | `putUserData` / `getUserData` | `CachedValuesManager.getCachedValue` |
|---|---|---|
| Invalidation | **None.** You must manually `putUserData(key, null)` | Automatic via `ModificationTracker` deps |
| GC | Lives as long as the holder | SoftReference — GC-reclaimable |
| Thread safety | `UserDataHolderEx.putUserDataIfAbsent` | Same, plus idempotence check |
| Best for | Tagging an object with a fixed-for-lifetime attribute (e.g., `SonarIssueAnnotator.SONAR_ISSUE_KEY` on editor — see `sonar/src/main/kotlin/.../SonarIssueAnnotator.kt`) | Computed values that need invalidation |

**Rule of thumb (JetBrains forum consensus):** "There is no automatic mechanism that clears the userdata of a PSI element" ([UserData lifecycle post](https://intellij-support.jetbrains.com/hc/en-us/community/posts/206755575-UserData-lifecycle)). If the cache needs to expire, use `CachedValue`. If it lives for the object's lifetime, use raw user data.

### Memory leak risk

- Storing a `Disposable` reference in user data of a long-lived holder (e.g., `Project`) **leaks that Disposable** — its `dispose()` may never be called, and it keeps its referrents alive. Use `Disposer.register(project, myDisposable)` instead.
- Using `FileType` / `Language` objects as map keys is a known antipattern — use `String` IDs ([Dynamic Plugins docs](https://plugins.jetbrains.com/docs/intellij/dynamic-plugins.html)).

---

## 4. Project/Application service caching patterns

### `@Service(Service.Level.PROJECT)` as cache container

This is the idiomatic IntelliJ pattern — matches exactly how our `RepoContextResolver` is already structured:

```kotlin
@Service(Service.Level.PROJECT)
class RepoContextResolver(private val project: Project) {
    companion object {
        fun getInstance(project: Project): RepoContextResolver =
            project.getService(RepoContextResolver::class.java)
    }
}
```

- **Instantiation:** lazy, first `getService()` call. One instance per project.
- **Disposal:** automatic when the project closes or the plugin unloads.
- **Thread-safety:** it's up to you — no implicit lock. The platform guarantees only one instance via CAS.
- **Restrictions for light services (docs):** class must be `final`, no constructor-injected custom deps (allowed: `Project`, `CoroutineScope`).

Source: [Services docs](https://plugins.jetbrains.com/docs/intellij/plugin-services.html).

### CoroutineScope injection (IJ 2024.1+)

```kotlin
@Service(Service.Level.PROJECT)
class MyService(private val project: Project, private val scope: CoroutineScope) {
    // 'scope' is Dispatchers.Default + CoroutineName(MyService::class);
    // cancelled automatically on project close / plugin unload.
}
```

This is the platform's replacement for hand-rolled `SupervisorJob` + `CoroutineScope` fields — use it for `SmartPoller` in Phase 3 if/when we rewire.

---

## 5. `ProgressManager` + `NonBlockingReadAction`

### The builder API

```kotlin
// com.intellij.openapi.application.ReadAction
ReadAction.nonBlocking<Result> { /* pure read */ }
    .inSmartMode(project)                // wait for indexing if needed
    .expireWith(parentDisposable)        // cancel if parent is disposed
    .expireWhen { !file.isValid }        // BooleanSupplier checked before and during
    .coalesceBy(serviceClass, userId)    // DEDUP: see below
    .finishOnUiThread(ModalityState.defaultModalityState()) { result -> /* UI */ }
    .submit(AppExecutorUtil.getAppExecutorService())
```

Source: [NonBlockingReadAction.java](https://github.com/JetBrains/intellij-community/blob/master/platform/core-api/src/com/intellij/openapi/application/NonBlockingReadAction.java), [Threading Model docs](https://plugins.jetbrains.com/docs/intellij/threading-model.html).

### `coalesceBy` — deep dive

**Semantic:** "Merges together similar computations by **cancelling the previous ones** when a new one is submitted." — from the javadoc.

Mechanism:
1. Each submission provides N equality objects (vararg).
2. The platform keeps an internal map keyed on those objects.
3. If a new submission arrives with equality objects `equals()` to an in-flight one, the **in-flight one is cancelled** and the new one takes its place.

**Critical constraints:**
- "The current implementation prohibits using the same `.coalesceBy` key for computations of different origins" — always include `serviceClass` or `this::class` as one of the keys.
- **`coalesceBy` is cancel-loser semantics, not share-result semantics.** Two concurrent callers → one is cancelled, the other runs; the cancelled caller gets a `CancellationException` (wrapped as failure in the returned `CompletableFuture`).

### Applicability to `SmartPoller` dedup

If two panels call `refresh()` 50ms apart and we want both to see the same result:
- `coalesceBy` cancels the first — panel 1 never gets its update.
- This is the wrong semantic for polling. Polling wants **share-result**, not cancel-loser.

**Better: a coroutine `Deferred` map (no platform primitive; ~30 LOC):**

```kotlin
@Service(Service.Level.PROJECT)
class PollDedup(private val project: Project, private val scope: CoroutineScope) {
    private val inFlight = ConcurrentHashMap<String, Deferred<Any>>()

    @Suppress("UNCHECKED_CAST")
    suspend fun <T : Any> dedup(key: String, block: suspend () -> T): T {
        val existing = inFlight[key] as? Deferred<T>
        if (existing != null && !existing.isCompleted) return existing.await()
        val d = scope.async { try { block() } finally { inFlight.remove(key) } }
        inFlight[key] = d as Deferred<Any>
        return d.await()
    }
}
```

This is what we want for Phase 3 target (c).

### Cancellation-respecting cached reads

When the cache **provider** needs a read lock and can be cancelled:

```kotlin
val result = readAction { /* in a coroutine; auto-cancels on write action */ }
// or
ReadAction.nonBlocking<T> { compute() }.expireWith(project).executeSynchronously()
```

---

## 6. `VirtualFile` content caching / `FileContentUtil`

Not applicable to HTTP or polling. Reason: `VirtualFile` content cache works at the VFS layer for file-system paths; our HTTP response bodies are not files.

One edge case: if we cache large response bodies to disk, we could write them into a plugin-private scratch directory (`PathManager.getSystemPath() + "/workflow-orchestrator/http-cache"` — already what `HttpClientFactory` does) and read via `LocalFileSystem`. Adds no value over OkHttp's on-disk `Cache` (which we already use).

---

## 7. `Disposable` lifecycle and cache cleanup

### Rules

- Every cache that holds resources (file handles, message-bus connections, background tasks) **must be tied to a `Disposable`** — either by implementing `Disposable` itself or by being a child of one.
- **Never** use `Application` or `Project` directly as a parent — this prevents cleanup on plugin unload ([Disposer docs](https://plugins.jetbrains.com/docs/intellij/disposers.html)).
- **Do** use your own `@Service` as the parent disposable: `messageBus.connect(this)` inside a `Disposable` service works because the service is registered with the platform's `Disposer`.

### Pattern

```kotlin
@Service(Service.Level.PROJECT)
class MyCache(project: Project) : Disposable {
    private val connection = project.messageBus.connect(this)
    init {
        connection.subscribe(VirtualFileManager.VFS_CHANGES, myListener)
    }
    override fun dispose() { /* message bus auto-disconnected via connect(this) */ }
}
```

### Common leak patterns

1. **Capturing `Project` in a static / companion field** — keeps the project alive forever.
2. **Registering `Disposable` on `Project` without a plugin-scoped parent** — leaks across plugin reload.
3. **Adding listeners without a parent disposable** — `connect()` without an argument ties to application; the listener outlives plugin unload.
4. **Storing `PsiElement` directly** — use `SmartPsiElementPointer`.
5. **`ExecutorService` without `shutdownNow()` in `dispose()`** — threads keep running, hold plugin classloader, prevent unload.

Cross-reference: `intellij-plugin-performance` skill in this repo's skills catalogue.

---

## 8. What JetBrains-official plugins do for HTTP caching

### Finding: JetBrains plugins generally do not cache HTTP bodies

I surveyed four:

1. **Tasks plugin (`plugins/tasks/tasks-core`)** — `JiraRepository.java` uses Apache Commons HttpClient, **no ETag**, **no response cache**. Relies on user-configurable "cache N issues for M minutes" at the issue-object level, not HTTP level. See [JiraRepository.java](https://github.com/JetBrains/intellij-community/blob/master/plugins/tasks/tasks-core/jira/src/com/intellij/tasks/jira/JiraRepository.java).
2. **YouTrack IDEA plugin** — `IssueStoreUpdaterService` uses `@Service(Level.PROJECT)` + `JobScheduler.scheduleWithFixedDelay` (5-minute polling). No HTTP caching; issue list cached in memory object (`issueStoreComponent`) with observer pattern.
3. **Git4Idea** — `GitRepositoryManager` is a project-level service extending `AbstractRepositoryManager<GitRepository>`. Caches `GitRepository` instances in an internal map; invalidates via `VcsRepositoryMappingListener`. **No HTTP** (git is local).
4. **IntelliJ platform's Space plugin** — not in open-source community source (closed-source).

### Takeaway for us

The idiomatic JetBrains answer for "HTTP API with periodic refresh" is:
1. Store the parsed objects in a `@Service(Level.PROJECT)` singleton.
2. Refresh on a timer (`JobScheduler.getScheduler().scheduleWithFixedDelay`) or listener-triggered.
3. Notify UI via message-bus `Topic<Listener>`.
4. **Don't** try to cache HTTP response bodies at the HTTP level — let the object store be the cache.

This is structurally what we do today (`SmartPoller` + service caches) — and OkHttp's 10 MB disk cache handles the raw-body ETag case as a bonus.

---

## 9. Performance gotchas specific to IJ

- **EDT-safe reads:** `CachedValue.getValue()` itself is EDT-safe if the provider doesn't touch PSI (or does so via `getProjectPsiDependentCache` which wraps in a read lock). Our `RepoContextResolver` use case (reads `GitRepositoryManager` + settings) does not require a read lock.
- **Dumb mode:** `PsiModificationTracker.MODIFICATION_COUNT` bumps on dumb-mode enter/exit. If you cache on it, your cache clears after indexing. For non-PSI use cases avoid adding it as a dependency.
- **Indexing impact:** `ReadAction.nonBlocking { }.inSmartMode(project)` waits for indexing to complete. Use it only if the read depends on indexes; otherwise you block needlessly.
- **`ProjectRootManager` as dep without `DumbService`:** PSI Performance docs warn: *"If cached values use `ProjectRootManager` as a dependency without `PsiModificationTracker` and depend on indexes, a dependency on `DumbService` must be added."* Relevant if we ever index-query in a cached value.
- **EDT slow ops:** `com.intellij.openapi.progress.util.PotemkinProgress` / the platform's slow-ops detection will log warnings if `getValue()` takes >300ms on EDT. Keep providers cheap.

---

## 10. IntelliJ's `HttpRequests` API

### FQN and surface

- `com.intellij.util.io.HttpRequests` — static-factory, builder-style HTTP client bundled with the platform.
- Source: `platform/platform-impl/src/com/intellij/util/io/HttpRequests.kt` (Kotlin; private impl).
- Reference docs: [HttpRequests unofficial api doc](https://dploeger.github.io/intellij-api-doc/com/intellij/util/io/HttpRequests.html).

### Shape

```kotlin
val body: String = HttpRequests.request(url)
    .userAgent("WorkflowOrchestrator/1.0")
    .accept("application/json")
    .tuner { conn -> conn.setRequestProperty("Authorization", "Bearer $token") }
    .connectTimeout(5_000)
    .readTimeout(30_000)
    .readString()  // or .saveToFile(path), .connect { it.readBytes() }

HttpRequests.post(url, "application/json").connect { req ->
    req.write(jsonBody)
    req.readString()
}
```

### Proxy support

- **Yes**, integrates with `HttpConfigurable` (IDE proxy settings at **Settings → Appearance & Behavior → System Settings → HTTP Proxy**). See [IdeHttpClientHelpers.java](https://github.com/JetBrains/intellij-community/blob/master/platform/platform-impl/src/com/intellij/util/net/IdeHttpClientHelpers.java).
- Users who sit behind a corporate proxy get proxy traversal for free.

### HTTP body caching?

- **No.** `HttpRequests` is a thin wrapper around `HttpURLConnection`. No `ETag` / `If-None-Match` / `304 Not Modified` handling built in.
- No response-body disk cache.

### vs OkHttp (current)

| Feature | OkHttp (current) | `HttpRequests` | Ktor Client |
|---|---|---|---|
| Response cache (ETag/304) | ✓ built-in `Cache` | ✗ (roll your own) | ✗ (no standard) |
| Connection pool | ✓ | ✓ HttpURLConnection's | ✓ |
| HTTP/2 | ✓ | partial (JDK-dep) | ✓ |
| IDE proxy | manual (but we could hook `HttpConfigurable`) | ✓ automatic | manual |
| IDE-bundled (no extra JAR) | ✗ we bundle `okhttp3` | ✓ free | ✗ bundled |
| Interceptor chain | ✓ first-class | partial via `.tuner()` | ✓ |

### Verdict

- **Don't migrate to `HttpRequests`.** We gain automatic proxy config but lose ETag caching, interceptors, and HTTP/2. Our OkHttp layer already ships with a 10 MB disk `Cache`, retry interceptor, metrics interceptor, sensitive-endpoint no-cache interceptor — rebuilding all this on top of `HttpRequests` is net-negative.
- **Do steal one idea from `HttpRequests`:** plug `HttpConfigurable.getInstance()` into our OkHttp `ProxySelector` so corporate users stop needing env-vars. This is a small Phase 3 win worth tracking.

---

## Recommendations — mapping to Phase 3 targets

### (a) HTTP response body caching

**Do not use `CachedValue`. Do not migrate to `HttpRequests`. Keep OkHttp `Cache`.**

Actions for Phase 3:
- Audit which Jira/Bamboo/Bitbucket endpoints emit `ETag` or `Cache-Control: max-age` headers; add a test harness that proves OkHttp's `Cache` is producing 304s on those endpoints.
- Add `HttpConfigurable` integration to `HttpClientFactory.sharedPool` so IDE proxy settings are honoured (today we bypass them).
- Leave the current `SensitiveEndpointNoCacheInterceptor` alone — it correctly opts sensitive URLs out of caching.

### (b) `RepoContextResolver` memoization

**Use `CachedValue` + composite `ModificationTracker`.**

```kotlin
@Service(Service.Level.PROJECT)
class RepoContextResolver(private val project: Project) : Disposable {
    private val tracker = SimpleModificationTracker()
    init {
        project.messageBus.connect(this).subscribe(
            VcsRepositoryMappingListener.BUS,
            VcsRepositoryMappingListener { tracker.incModificationCount() }
        )
        // also: subscribe to the plugin's own settings-change topic
    }

    private val primaryKey = Key.create<CachedValue<RepoConfig?>>("wo.primary")
    fun getPrimary(): RepoConfig? =
        CachedValuesManager.getManager(project).getCachedValue(project, primaryKey, {
            CachedValueProvider.Result.create(
                PluginSettings.getInstance(project).getPrimaryRepo(),
                tracker
            )
        }, false)

    // Similar for resolveFromCurrentEditor — depend on (tracker, FileEditorManager selection)
    // Use a per-file-path key or a ParameterizedCachedValue.

    override fun dispose() {}
}
```

Expected payoff: `resolveFromCurrentEditor()` / `resolveCurrentEditorRepoOrPrimary()` hot paths drop from O(repos × editor lookup) per call to O(1) map-get when nothing changed. Profiling baseline needed to quantify — that's the Phase 3 gate in the memory plan.

### (c) `SmartPoller` dedup

**Don't use `coalesceBy` (wrong semantic — cancels losers). Don't use `CachedValue` (polling isn't invalidated by PSI).**

**Use a `ConcurrentHashMap<String, Deferred<T>>` in a project-scope `@Service` with injected `CoroutineScope`.** ~30 LOC. Share-result semantics. See §5 example.

Register per-endpoint keys: `"jira:$boardId:activeSprint"`, `"bamboo:$planKey:status"`, `"bitbucket:$projectKey/$repo:prs"`. Two panels polling the same endpoint → one HTTP call → both see the result.

---

## Sources

- [IntelliJ Platform Plugin SDK — Services](https://plugins.jetbrains.com/docs/intellij/plugin-services.html)
- [IntelliJ Platform Plugin SDK — Disposers](https://plugins.jetbrains.com/docs/intellij/disposers.html)
- [IntelliJ Platform Plugin SDK — Threading Model](https://plugins.jetbrains.com/docs/intellij/threading-model.html)
- [IntelliJ Platform Plugin SDK — Coroutine Read Actions](https://plugins.jetbrains.com/docs/intellij/coroutine-read-actions.html)
- [IntelliJ Platform Plugin SDK — Plugin Listeners](https://plugins.jetbrains.com/docs/intellij/plugin-listeners.html)
- [IntelliJ Platform Plugin SDK — Dynamic Plugins](https://plugins.jetbrains.com/docs/intellij/dynamic-plugins.html)
- [IntelliJ Platform Plugin SDK — PSI Performance](https://plugins.jetbrains.com/docs/intellij/psi-performance.html)
- Source: [CachedValue.java](https://github.com/JetBrains/intellij-community/blob/master/platform/core-api/src/com/intellij/psi/util/CachedValue.java)
- Source: [CachedValuesManager.java](https://github.com/JetBrains/intellij-community/blob/master/platform/core-api/src/com/intellij/psi/util/CachedValuesManager.java)
- Source: [PsiModificationTracker.java](https://github.com/JetBrains/intellij-community/blob/master/platform/core-api/src/com/intellij/psi/util/PsiModificationTracker.java)
- Source: [ModificationTracker.java](https://github.com/JetBrains/intellij-community/blob/master/platform/util/src/com/intellij/openapi/util/ModificationTracker.java)
- Source: [NonBlockingReadAction.java](https://github.com/JetBrains/intellij-community/blob/master/platform/core-api/src/com/intellij/openapi/application/NonBlockingReadAction.java)
- Source: [IdeHttpClientHelpers.java](https://github.com/JetBrains/intellij-community/blob/master/platform/platform-impl/src/com/intellij/util/net/IdeHttpClientHelpers.java)
- Source: [JiraRepository.java (tasks plugin)](https://github.com/JetBrains/intellij-community/blob/master/plugins/tasks/tasks-core/jira/src/com/intellij/tasks/jira/JiraRepository.java)
- Forum: [UserData lifecycle](https://intellij-support.jetbrains.com/hc/en-us/community/posts/206755575-UserData-lifecycle)
- Forum: [Best practices for getProjectPsiDependentCache](https://intellij-support.jetbrains.com/hc/en-us/community/posts/360010569739-Best-practices-for-CachedValuesManager-getProjectPsiDependentCache)
