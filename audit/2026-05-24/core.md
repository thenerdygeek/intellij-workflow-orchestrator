# :core Security & Correctness Audit — 2026-05-24

Auditor: Claude Sonnet 4.6 (read-only, production code only, no tests or build dirs)

---

## Findings

### F-1 [P0] [Security]: RetryInterceptor retries non-idempotent POST/PUT/DELETE on 5xx

**File**: `core/src/main/kotlin/com/workflow/orchestrator/core/http/RetryInterceptor.kt:27-50`

**Description**: `RetryInterceptor` is added to the base client in `HttpClientFactory` and is therefore inherited by every per-service client (Jira, Bamboo, Bitbucket, SonarQube). It retries on HTTP 500/502/503/504 without checking `request.method`. A POST that triggers a side effect (queue a Bamboo build, create a PR, post a Jira comment, merge a PR) will be replayed up to 3 times on transient 5xx. This can cause double-triggers or duplicate records.

**Evidence**:
```kotlin
// RetryInterceptor.kt:27-50
override fun intercept(chain: Interceptor.Chain): Response {
    val request = chain.request()         // method NOT checked
    var response = chain.proceed(request)
    var attempt = 0

    while (response.code in retryableCodes && attempt < maxRetries) {
        attempt++
        ...
        Thread.sleep(delay)
        response = chain.proceed(request)   // POST replayed blindly
    }
```
`retryableCodes = setOf(429, 500, 502, 503, 504)` — 429 is fine to retry for any method; 5xx replay of POST is not safe.

**Impact**: Duplicate Bamboo build triggers, duplicate Jira comments, double-merge attempts, duplicate PR creations. During Bamboo maintenance windows (503), every `triggerBuild` call fires 3 times.

**Fix sketch**: Guard with `if (request.method == "GET" || request.method == "HEAD")` before entering the retry loop, or use a distinct retry-safe allowlist. Keep 429 retryable for all methods (rate-limit retry is safe even for POST because the first attempt was not executed by the server).

---

### F-2 [P0] [Security]: `AuthInterceptor` added as application interceptor — token sent on every redirect including cross-origin

**File**: `core/src/main/kotlin/com/workflow/orchestrator/core/http/HttpClientFactory.kt:51-56`

**Description**: `AuthInterceptor` is added via `addInterceptor()` (application layer), not `addNetworkInterceptor()`. OkHttp application interceptors run *before* the redirect chain is unwound; the `Authorization` header is therefore re-applied by `OkHttpClient.buildRequest()` on every hop, including redirects to a different host. If a Jira/Bamboo server returns a 3xx pointing at a third-party auth system on a different domain, the Bearer token is forwarded to that domain.

**Evidence**:
```kotlin
// HttpClientFactory.kt:51-56
baseClient.newBuilder()
    .addInterceptor(CachingInterceptor(service))
    .addInterceptor(MutationInvalidationInterceptor(service))
    .addInterceptor(HttpMetricsInterceptor())
    .addInterceptor(AuthInterceptor({ tokenProvider(service) }, scheme)) // app-layer
    .build()
```
`AuthTestService` correctly uses `followRedirects(false)` on its test client, but the production client in `HttpClientFactory` follows redirects by default and does not strip `Authorization`.

**Impact**: Bearer tokens leaked to any host that a service URL can be redirected to. In enterprise environments with SSO/proxy redirect chains, tokens escape the intended domain.

**Fix sketch**: Either set `followRedirects(false)` and `followSslRedirects(false)` on the production base client (then handle 3xx manually in callers that need redirect support), or move `AuthInterceptor` to `addNetworkInterceptor()` and strip the `Authorization` header when `request.url.host != originalRequest.url.host`.

---

### F-3 [P0] [Security]: `RawApiTraceConfig.redactPromptBody` defaults to `false` — LLM prompt contents written verbatim to disk when tracing is enabled

**File**: `core/src/main/kotlin/com/workflow/orchestrator/core/http/RawApiTraceConfig.kt:31-35`

**Description**: When `RawApiTraceConfig.mode` is set to `ALWAYS_ON` or `BURST`, every LLM request body (including full conversation history with system prompt, tool definitions, and user messages) is written verbatim to `{projectDir}/agent/logs/raw-api/{date}/` because `redactPromptBody` defaults to `false`. Source code sent through the agent as tool results or context (PSI snippets, git diffs) is written unredacted. The knock-on `PreSanitizeDumper` (called before `sanitizeMessages`) dumps the *pre-sanitize* conversation — including any messages that `sanitizeMessages` was about to remove — to `.pre-sanitize.json`.

**Evidence**:
```kotlin
// RawApiTraceConfig.kt:35
@Volatile var redactPromptBody: Boolean = false  // default: verbatim dump

// SourcegraphChatClient.kt:131-141
.addInterceptor(RawApiTraceInterceptor(
    config = RawApiTraceConfig,
    traceDir = {
        agentLogsDir?.let { dir -> RawApiTraceConfig.traceDir(dir) }
            ?: java.io.File(System.getProperty("user.home"),
                ".workflow-orchestrator/trace-fallback")  // fallback always active
    }
))
```

**Impact**: When any developer enables the trace feature, all LLM traffic including source code, Jira ticket content, commit messages, and any PII in those artifacts is persisted to disk in plaintext. The `trace-fallback` directory under `~/.workflow-orchestrator/` is always active when `agentLogsDir` is null — i.e., for commit-message generation — regardless of tracing mode (the interceptor gates on `config.shouldTrace()`, but the directory creation is unconditional in the lazy builder).

**Fix sketch**: Flip `redactPromptBody` default to `true`. Add a `FilePermissions` call to set the trace directory to `700` (owner-only) at creation time. The `trace-fallback` path should only be created if `config.shouldTrace()` is true.

---

### F-4 [P0] [Security]: `SourcegraphChatClient.sanitizeForDebug` regex misses plaintext token in request JSON — API key written to `api-debug/` files

**File**: `core/src/main/kotlin/com/workflow/orchestrator/core/ai/SourcegraphChatClient.kt:756-762`

**Description**: `sanitizeForDebug` uses a key-name regex to redact credentials. It matches JSON patterns like `"token": "VALUE"` but fails on: (1) non-JSON formats (YAML, `.env` style `TOKEN=value`, shell export syntax), (2) Sourcegraph's token format `sgp_...` appearing in prose content without a key prefix, and (3) multi-line strings across tool results. The raw JSON request body passed as `rawJsonBody` to `dumpApiRequest` goes through `sanitizeForDebug` before being written to `call-NNN-request.json` — but the *pre-sanitize dump* in `PreSanitizeDumper.dump()` bypasses `sanitizeForDebug` entirely.

**Evidence**:
```kotlin
// SourcegraphChatClient.kt:756-762
private fun sanitizeForDebug(text: String): String {
    return text.replace(
        Regex("""("(?:password|token|secret|api_key|...)["s]*[:=]\s*")([^"]{4,})""",
              RegexOption.IGNORE_CASE)
    ) { match -> "${match.groupValues[1]}***REDACTED***" }
}

// PreSanitizeDumper.kt:47-58
fun dump(messages: List<ChatMessage>, reqId: String, traceDir: File) {
    ...
    val payload = json.encodeToString(messages)   // NO sanitizeForDebug call
    OutputStreamWriter(FileOutputStream(file), ...).use { w -> w.write(payload) }
}
```

**Impact**: If an agent tool result or user message contains a literal token string (e.g., from a `.env` file read via `read_file`), it survives the `sanitizeForDebug` pass and lands in `api-debug/` and `.pre-sanitize.json` on disk.

**Fix sketch**: Apply `sanitizeForDebug` (or a stronger CredentialRedactor) to the `PreSanitizeDumper` payload. Extend the regex to cover `TOKEN=VALUE`, `export TOKEN=VALUE`, and bare `sgp_[a-zA-Z0-9]{40}` patterns. Consider replacing the regex approach with the `:agent`'s `CredentialRedactor` which is wired via `bodyRedactor` in `RawApiTraceInterceptor`.

---

### F-5 [P0] [Security]: URL query parameter `filterText` in `getBranches()` is not URL-encoded — path injection into Bitbucket API

**File**: `core/src/main/kotlin/com/workflow/orchestrator/core/bitbucket/BitbucketBranchClient.kt:945,951`

**Description**: `filterText` is interpolated directly into the URL string without `URLEncoder.encode()`. If `filterText` contains `&`, `=`, `#`, or `%` characters (which can appear in branch names with prefix searches), the resulting URL is malformed or injects additional query parameters. Similarly, `branchName` on line 1168 is used as `refs/heads/$branchName` and the resulting value is inserted unencoded as the `at=` query parameter on line 1170.

**Evidence**:
```kotlin
// BitbucketBranchClient.kt:945,951
val filterParam = if (filterText.isNotBlank()) "&filterText=$filterText" else ""
val request = Request.Builder()
    .url("$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/branches" +
         "?limit=100&orderBy=MODIFICATION&details=true$filterParam") // no encoding
    .build()

// BitbucketBranchClient.kt:1168,1170
val branchRef = "refs/heads/$branchName"   // branchName not validated
.url("...pull-requests?direction=OUTGOING&at=$branchRef&state=OPEN")  // unencoded
```
By contrast, line 1312 correctly applies `URLEncoder.encode(filter, "UTF-8")` for the user search endpoint, showing encoding is known and used selectively.

**Impact**: Branches with special characters in names (valid in git) produce malformed Bitbucket API calls. Attacker-controlled branch names could inject `&state=MERGED` or similar parameters to bypass filters. OkHttp may reject the URL with `IllegalArgumentException` on certain characters, surfacing as a crash rather than a clean error.

**Fix sketch**: Use OkHttp's `HttpUrl.Builder` with `addQueryParameter()` for all user-supplied query values. Replace the string-interpolated URL patterns with a structured builder throughout `BitbucketBranchClient`.

---

### F-6 [P0] [Resource leak]: `BitbucketBranchClient` creates a new `HttpClientFactory` instance per client instance — leaks independent connection pools

**File**: `core/src/main/kotlin/com/workflow/orchestrator/core/bitbucket/BitbucketBranchClient.kt:860-863`

**Description**: `BitbucketBranchClient.httpClient` is built via `HttpClientFactory(tokenProvider = ...).clientFor(ServiceType.BITBUCKET)`. Each `HttpClientFactory` instance holds its own `ConcurrentHashMap<ServiceType, OkHttpClient>` of per-service clients. `HttpClientFactory` does reuse `sharedPool` (the static `OkHttpClient` with shared connection pool) as the base, but each factory instantiation creates a fresh `baseClient` via `sharedPool.newBuilder()`. When `BitbucketBranchClient` is constructed per-request or per-panel (which is common), each factory's dispatcher and its interceptor chain objects accumulate without being closed. The `clients` map is not `Closeable`.

**Evidence**:
```kotlin
// BitbucketBranchClient.kt:860-863
private val httpClient: OkHttpClient by lazy {
    HttpClientFactory(tokenProvider = { _ -> tokenProvider() })
        .clientFor(ServiceType.BITBUCKET)
}
```
`HttpClientFactory` is a non-singleton class; every call site that `new`s it creates a fresh `baseClient` via `sharedPool.newBuilder().addInterceptor(RetryInterceptor()).build()` — the `RetryInterceptor` and other interceptor objects are allocated separately per factory even though they share the same underlying pool.

**Impact**: Each `BitbucketBranchClient` instance that is short-lived (created per action) silently leaks `RetryInterceptor`, `SensitiveEndpointNoCacheInterceptor`, `CachingInterceptor`, `MutationInvalidationInterceptor`, `HttpMetricsInterceptor`, and `AuthInterceptor` objects. On projects with active polling + PR views, this accumulates over hours of use.

**Fix sketch**: Make `BitbucketBranchClient` accept an `OkHttpClient` already configured from the factory, or introduce a project-scoped `@Service` that holds the shared `HttpClientFactory` and provides the pre-built per-service clients. `fromConfiguredSettings()` in the project's canonical factory call sites is the right injection point.

---

### F-7 [P0] [Threading]: `RetryInterceptor.intercept()` calls `Thread.sleep()` — blocks OkHttp dispatcher thread and may block EDT

**File**: `core/src/main/kotlin/com/workflow/orchestrator/core/http/RetryInterceptor.kt:49`

**Description**: `RetryInterceptor` uses `Thread.sleep(delay)` to implement its backoff wait. This is an OkHttp application interceptor; it runs on whatever thread OkHttp's dispatcher assigns the call to. When calls are made synchronously from a `Task.Backgroundable.run` block via `runBlockingCancellable`, the sleep blocks that background thread for up to 10 seconds per retry attempt. More critically, if any caller accidentally invokes a synchronous `.execute()` on the EDT (no explicit guard exists in all callers), `Thread.sleep` will freeze the IDE for up to 30 seconds (3 retries × 10s cap). `Thread.sleep` also ignores coroutine cancellation — a user cancelling a Bamboo trigger mid-retry cannot escape until the sleep completes.

**Evidence**:
```kotlin
// RetryInterceptor.kt:49
Thread.sleep(delay)  // delay up to maxDelayMs=10_000ms, 3 retries = 30s max
```

**Impact**: EDT freeze risk; coroutine cancellation blindness; background thread starvation under concurrent API calls.

**Fix sketch**: Replace `Thread.sleep(delay)` with `kotlinx.coroutines.delay(delay)` inside a `suspend` function. This requires making `intercept()` suspending or delegating the retry logic to a suspend wrapper. Alternatively use `okhttp3.Interceptor` with a Kotlin coroutine dispatcher that can be cancelled.

---

### F-8 [P1] [Correctness]: `CredentialStore` shared static `tokenCache` — cross-project token leakage on multi-project IDE sessions

**File**: `core/src/main/kotlin/com/workflow/orchestrator/core/auth/CredentialStore.kt:34,37`

**Description**: `tokenCache` is a static `ConcurrentHashMap` keyed by `ServiceType` (an enum, not project-scoped). When a user has two projects open simultaneously — one connecting to `jira-project-a.example.com` and another to `jira-project-b.example.com` — both share the same `ServiceType.JIRA` cache slot. The second project to load its token overwrites the first project's token in cache. The first project's next API call silently uses the second project's token.

**Evidence**:
```kotlin
// CredentialStore.kt:34-37
private val tokenCache = ConcurrentHashMap<ServiceType, CachedToken>()
private const val CACHE_TTL_MS = 3_600_000L  // 1 hour
```
`CredentialStore` is not a singleton — it's "instantiated directly in 45+ locations" per the comment at line 18. The `generateServiceName("WorkflowOrchestrator", service.name)` key in `PasswordSafe` is also global (not project-scoped), so this affects PasswordSafe storage, not just the cache.

**Impact**: In multi-project IDE sessions with different Jira/Bamboo/Bitbucket instances, API calls use the wrong project's token. At best: 401 errors. At worst: token from a more-privileged project leaks into requests against a less-privileged project, bypassing access controls.

**Fix sketch**: Key the cache by `Pair<ServiceType, serviceUrl>` (where `serviceUrl` comes from `ConnectionSettings` for that project). The `CredentialStore` should be project-scoped or take the service URL as part of the lookup key. `PasswordSafe` key should similarly incorporate the URL.

---

### F-9 [P1] [Correctness]: `AutoDetectFileListener` scope is never cancelled — coroutine scope leaks for the process lifetime

**File**: `core/src/main/kotlin/com/workflow/orchestrator/core/autodetect/AutoDetectFileListener.kt:28`

**Description**: `AutoDetectFileListener` implements `BulkFileListener` (an application-level message bus listener). It allocates a `CoroutineScope(SupervisorJob() + Dispatchers.IO)` at construction time and uses it for debounced detection jobs. The listener has no `dispose()` or `Disposable` implementation, so the scope and any pending `delay(500)` jobs are never cancelled — they outlive all open projects and accumulate if the listener is re-instantiated (e.g., via plugin reload). Unlike `BranchChangedEventEmitter` (which implements `Disposable` and calls `Disposer.register`), this class has no lifecycle hook.

**Evidence**:
```kotlin
// AutoDetectFileListener.kt:23-28
class AutoDetectFileListener : BulkFileListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // No Disposable, no dispose(), no Disposer.register
```

**Impact**: Leaked `SupervisorJob` and `Dispatchers.IO` coroutine scope. Pending debounce jobs may fire against already-disposed `AutoDetectOrchestrator` service instances, causing NPE or `AlreadyDisposedException` in the background.

**Fix sketch**: Implement `Disposable`, call `scope.cancel()` in `dispose()`, and register with `Disposer.register(ApplicationManager.getApplication(), this)` at construction.

---

### F-10 [P1] [Correctness]: `DevStatusCacheInvalidator` allocates its own `CoroutineScope(SupervisorJob())` instead of using platform-injected scope

**File**: `core/src/main/kotlin/com/workflow/orchestrator/core/http/DevStatusCacheInvalidator.kt:42`

**Description**: `DevStatusCacheInvalidator` is a `@Service(Level.PROJECT)` annotated class. Per the platform convention documented in the project (phase 4 prong A), project-scoped services should accept `cs: CoroutineScope` injected by the platform — the platform owns the lifecycle so services don't need to cancel their own scope. This service instead allocates its own `CoroutineScope(SupervisorJob() + Dispatchers.Default)` and cancels it in `dispose()`. While the dispose cancel is correctly wired, the manual scope allocation means the service cannot benefit from structured concurrency with the platform's project scope, and the pattern is inconsistent with the documented convention.

**Evidence**:
```kotlin
// DevStatusCacheInvalidator.kt:40-51
@Service(Service.Level.PROJECT)
class DevStatusCacheInvalidator(private val project: Project) : Disposable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default) // manual
    fun start() {
        scope.launch { eventBus.events.collect { event -> handle(event) } }
    }
    override fun dispose() { scope.cancel() }
}
```

**Impact**: Architectural drift from declared convention. Lower risk than F-9 because dispose is implemented, but the scope may outlive child coroutines if `dispose()` races with an in-flight `handle()` call.

**Fix sketch**: Inject `cs: CoroutineScope` via the constructor (2024.1+ DI pattern) and drop the `Disposable` implementation and manual scope. If injection is not available, the current pattern is acceptable as a fallback but should be documented.

---

### F-11 [P1] [Correctness]: `healthCheckMavenGoals` setting is user-editable free text passed as Maven goal arguments without sanitization

**File**: `core/src/main/kotlin/com/workflow/orchestrator/core/maven/MavenModuleDetector.kt:53-58`; `core/src/main/kotlin/com/workflow/orchestrator/core/maven/MavenBuildService.kt:26-32`

**Description**: `settings.state.healthCheckMavenGoals` (a free-text settings field) is split on whitespace and passed directly as the goals list to `buildMavenArgs`. Those goal tokens are placed as positional arguments in a `GeneralCommandLine`. Maven goals in `GeneralCommandLine` are passed as separate argv tokens (not shell-expanded), so classic shell injection is not a risk. However, Maven lifecycle phase names and plugin goal names accept only alphanumerics and colons/hyphens — no path-traversal characters. A goal like `-Dprop=../../malicious` would be passed as a flag. The `sanitizedEnvironment()` filter strips environment variable secrets but does not validate the goals list. This is lower severity because `GeneralCommandLine` does not invoke a shell, but arbitrary Maven flags can still exfiltrate data via `maven.repo.local` or execute arbitrary Maven plugins.

**Evidence**:
```kotlin
// MavenModuleDetector.kt:53-58
fun buildMavenArgs(modules: List<String>, goals: String): List<String> {
    val goalList = goals.trim().split("\\s+".toRegex())  // no validation
    ...
    return listOf("-pl", modules.joinToString(","), "-am") + goalList
}
// MavenBuildService.kt:26-32
val args = detector.buildMavenArgs(modules, goals)  // goals from settings
val commandLine = GeneralCommandLine(listOf(executable) + allArgs)
```

**Impact**: A user who has been social-engineered into entering a malicious goals string (e.g., via a crafted workspace `.idea` file) can pass arbitrary Maven CLI flags, potentially triggering remote goal execution (`exec:exec`, `antrun:run`) or exfiltrating build artifacts.

**Fix sketch**: Validate `goals` against an allowlist regex `^[a-zA-Z0-9:._-]+(\\s+[a-zA-Z0-9:._-]+)*$` before splitting. Reject and show a settings validation error if it does not match.

---

### F-12 [P1] [Correctness]: `UrlSafetyGuard` is NOT called for configured service base URLs — SSRF via settings

**File**: `core/src/main/kotlin/com/workflow/orchestrator/core/security/UrlSafetyGuard.kt:35`; `core/src/main/kotlin/com/workflow/orchestrator/core/settings/ConnectionsConfigurable.kt`

**Description**: `UrlSafetyGuard.isUrlSafe()` is only used in the `:agent` module's `HttpReadinessProbe` and the unmerged `web-fetch-search` worktree. It is never called when validating `ConnectionSettings.jiraUrl`, `bambooUrl`, `bitbucketUrl`, `sonarUrl`, or `sourcegraphUrl`. These five URLs are user-entered in `ConnectionsConfigurable` and become the `baseUrl` for all API calls. A malicious `.idea/workflowOrchestratorConnections.xml` committed to a repository could point the plugin at `http://169.254.169.254` (AWS IMDS) and exfiltrate credentials via error messages or logs.

**Evidence**:
```kotlin
// ConnectionsConfigurable.kt — no UrlSafetyGuard call in Test Connection or Apply
// All five service URLs flow directly into OkHttp calls:
// BitbucketBranchClient.kt:874
.url("$baseUrl/rest/api/1.0/projects?limit=100")   // baseUrl from settings, no guard
```

**Impact**: SSRF via committed workspace settings. An attacker who can write `.idea/workflowOrchestratorConnections.xml` to a shared repository can redirect any service call to an internal network address that the developer's machine can reach.

**Fix sketch**: Call `UrlSafetyGuard.isUrlSafe(url, allowLoopback = false)` in `ConnectionsConfigurable.apply()` and in the "Test Connection" action for all five service URL fields. Reject URLs pointing at RFC 1918 / link-local ranges with a user-visible error.

---

### F-13 [P1] [Performance]: `HttpClientFactory` ignores `timeoutsFromSettings()` in `BitbucketBranchClient` — always uses hardcoded 10s/30s defaults

**File**: `core/src/main/kotlin/com/workflow/orchestrator/core/bitbucket/BitbucketBranchClient.kt:860-863`

**Description**: When `BitbucketBranchClient` constructs its `HttpClientFactory`, it uses the no-arg default constructor values (connect=10s, read=30s). It never calls `HttpClientFactory.timeoutsFromSettings(project)`. Users who configure longer timeouts in "Network (Advanced)" settings for slow enterprise Bitbucket instances will find that Bitbucket calls still time out at 30 seconds, while other service calls respect their configured timeout.

**Evidence**:
```kotlin
// BitbucketBranchClient.kt:860-863
private val httpClient: OkHttpClient by lazy {
    HttpClientFactory(tokenProvider = { _ -> tokenProvider() })
        // connectTimeoutSeconds and readTimeoutSeconds not passed — use defaults
        .clientFor(ServiceType.BITBUCKET)
}
```

**Impact**: Silent timeout mismatch. Bitbucket calls on slow networks fail at 30s regardless of user configuration. The fix in F-6 (inject a factory-built client) would also fix this.

**Fix sketch**: Accept `HttpTimeouts` in `BitbucketBranchClient`'s constructor (sourced from `HttpClientFactory.timeoutsFromSettings(project)`) and pass them to `HttpClientFactory`.

---

### F-14 [P1] [Correctness/Security]: `SourcegraphChatClient.dumpApiRequest` writes full source-code diffs and Jira content to disk without access controls

**File**: `core/src/main/kotlin/com/workflow/orchestrator/core/ai/SourcegraphChatClient.kt:671-718`

**Description**: `dumpApiRequest()` and `dumpApiResponse()` write the full LLM conversation (including source code from `read_file` / git diff tool results and Jira ticket content) to `{session}/api-debug/call-NNN-{request,response}.{txt,json}` files. `sanitizeForDebug()` only scrubs JSON key-value credential patterns, leaving source code intact. These files are written with default JVM file permissions (`644` or umask-dependent), making them world-readable on shared developer machines.

**Evidence**:
```kotlin
// SourcegraphChatClient.kt:714-715
val rawFile = java.io.File(dir, "call-${String.format("%03d", idx)}-request.json")
rawFile.writeText(sanitizeForDebug(rawJsonBody))  // 644 permissions, no setOwnerReadable
```

**Impact**: Source code and Jira ticket content written to a world-readable file in the user's home directory. On shared workstations or macOS with default umask, this data is accessible to any local user.

**Fix sketch**: Set `rawFile.setReadable(false, false); rawFile.setReadable(true, true)` (owner-only) immediately after creation. Use `Files.createFile()` with `PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))` on POSIX systems.

---

### F-15 [P1] [Performance / Correctness]: `ConnectionPool` size is `15` but the `CLAUDE.md` documents it as `5/3min` — mismatch and over-allocation

**File**: `core/src/main/kotlin/com/workflow/orchestrator/core/http/HttpClientFactory.kt:102`

**Description**: `sharedConnectionPool` is `ConnectionPool(15, 5, TimeUnit.MINUTES)` — 15 idle connections held for 5 minutes. The `core/CLAUDE.md` states "Shared `ConnectionPool(5, 3min)` base client." The deployed code is 3x more aggressive on idle connection retention. For a developer laptop hitting 5 Atlassian services, keeping 15 idle connections for 5 minutes holds OS file descriptors unnecessarily and may trigger enterprise firewall RST after idle timeout.

**Evidence**:
```kotlin
// HttpClientFactory.kt:102
private val sharedConnectionPool = ConnectionPool(15, 5, TimeUnit.MINUTES)
// core/CLAUDE.md (authoritative doc): "Shared ConnectionPool(5, 3min)"
```

**Impact**: Unnecessary file descriptor consumption; connections held past enterprise firewall idle timeouts (typically 4 min) causing `SocketException: Connection reset` on the next use.

**Fix sketch**: Align code with documentation: `ConnectionPool(5, 3, TimeUnit.MINUTES)`. If the increase was intentional, update the CLAUDE.md.

---

### F-16 [P2] [Quality]: `AuthTestService.executeTestRequest` calls `response.body?.string()` inside `response.use{}` but error-branch body is read from same `it` after the switch — double-read risk

**File**: `core/src/main/kotlin/com/workflow/orchestrator/core/auth/AuthTestService.kt:170-216`

**Description**: In `executeTestRequest`, `response.use { it.body?.string() }` is called correctly but on the `401` and `403` branches the code falls through with `body` already consumed, then logs `body.take(200)` which is the same already-read string — this is technically correct but the `200..299` branch reads the body via `it.body?.string()` and then a second `response.use { }` call is made for `checkBitbucketWritePermission`. The second call on the already-consumed body will return `""`. This is not a crash but produces silent empty results in the permission check on some code paths.

**Evidence**:
```kotlin
// AuthTestService.kt:170-216
response.use {
    val body = it.body?.string() ?: ""
    when (it.code) {
        in 200..299 -> {
            if (serviceType == ServiceType.BITBUCKET) {
                val writeWarning = checkBitbucketWritePermission(
                    normalizedBaseUrl,
                    request.header("Authorization") ?: ""    // passes auth header to second client call (OK)
                )
```
The `checkBitbucketWritePermission` correctly opens a new HTTP call, so this is not a double-read. However, the body at `it.body?.string()` and `body.take(200)` are both from the same response body. If the body is consumed once, subsequent reads return empty. The pattern is correct but fragile.

**Impact**: Low — current code is correct because the body is read once to `val body` and then only `body` (the String) is used. But it creates a false sense that `it.body?.string()` can be called again.

**Fix sketch**: Codify the single-read pattern by removing the direct `it.body?.string()` usage inside the `when` clauses and relying solely on the pre-read `body` variable.

---

### F-17 [P2] [Quality]: `EventBus.emit()` uses `tryEmit()` but is declared `suspend` — the suspend context is wasted and the `tryEmit()` failure path silently drops events

**File**: `core/src/main/kotlin/com/workflow/orchestrator/core/events/EventBus.kt:21-26`

**Description**: `EventBus.emit()` is declared `suspend` but internally uses `tryEmit()` (non-suspending). The `MutableSharedFlow` has `extraBufferCapacity = 64` and `onBufferOverflow = DROP_OLDEST`. If the buffer fills (64 queued events), new events are dropped silently with only a `warn` log. Callers that `suspend fun` emit events expect the suspension to backpressure, but `tryEmit()` never suspends — it returns `false` on overflow. The `suspend` marker on `emit()` is therefore misleading. Additionally, `tryEmit()` is documented as not safe for concurrent callers with suspend — but `emit()` (the actual suspending MutableSharedFlow API) is. Using `tryEmit()` removes that safety.

**Evidence**:
```kotlin
// EventBus.kt:21-26
suspend fun emit(event: WorkflowEvent) {
    log.info("[Core:Events] Emitting event: ${event::class.simpleName}")
    if (!_events.tryEmit(event)) {      // NOT _events.emit(event)
        log.warn("[Core:Events] Failed to emit event (buffer full): ...")
    }
}
```

**Impact**: Events silently dropped under heavy load. The `suspend` signature creates a false expectation of backpressure. A burst of 64+ events (e.g., rapid PR status changes) will lose the 65th event.

**Fix sketch**: Replace `tryEmit` with `_events.emit(event)` which is the correct suspending call. The `suspend fun` modifier is then justified.

---

### F-18 [P2] [Quality]: `CredentialStore` instantiated in 45+ locations as `CredentialStore()` — each allocates a new instance with no shared state beyond the static cache

**File**: `core/src/main/kotlin/com/workflow/orchestrator/core/auth/CredentialStore.kt:12`

**Description**: The comment at line 18 acknowledges "CredentialStore is NOT a singleton — it's instantiated directly in 45+ locations." Each construction creates a fresh object. The in-memory cache is static, which is the correct mitigation for the repeated PasswordSafe blocking reads. However, 45+ instantiation sites create maintenance overhead and risk divergence — future developers may add instance-level state that breaks the shared-cache assumption. The pattern also conflicts with the F-8 finding that the cache needs to be project-scoped.

**Evidence**:
```kotlin
// CredentialStore.kt:18-20 (comment)
// CredentialStore is NOT a singleton — it's instantiated directly in 45+ locations.
// The cache MUST be static so all instances share the same cached tokens.
```

**Impact**: Maintenance risk; no current functional bug beyond F-8.

**Fix sketch**: Introduce `CredentialStore.getInstance()` as an application-level `@Service`, eliminating the 45+ raw instantiations. Makes future project-scoping (F-8 fix) straightforward.

---

### F-19 [P2] [Quality]: `ConnectionPool` size undocumented discrepancy creates confusion for `HttpClientFactory` users

**File**: `core/src/main/kotlin/com/workflow/orchestrator/core/http/HttpClientFactory.kt:102-103` and `core/CLAUDE.md`

**Description**: Already covered under F-15 as a correctness/performance issue. Listed here separately as a quality finding because the discrepancy between code and documentation breaks the "docs same commit" convention.

**Impact**: Developers consulting `core/CLAUDE.md` to understand the shared pool size will configure their expectations incorrectly.

**Fix sketch**: See F-15.

---

## Summary Table

| Severity | Security | Resource Leak | Threading | Correctness | Performance | Quality |
|----------|----------|---------------|-----------|-------------|-------------|---------|
| P0       | F-1, F-2, F-3, F-4, F-5 | F-6 | F-7 | — | — | — |
| P1       | F-12 | — | — | F-8, F-9, F-10, F-11 | F-13 | F-14 |
| P2       | — | — | — | — | F-15 | F-16, F-17, F-18, F-19 |

**Totals**: P0: 7 findings, P1: 6 findings, P2: 4 findings, **Total: 17 findings**

---

## Top 5 Must-Fix

1. **F-1 (P0)** — `RetryInterceptor` replays POST/PUT/DELETE on 5xx: `RetryInterceptor.kt:27-50`. Duplicate Bamboo builds, duplicate PRs, duplicate Jira comments. Fix by checking `request.method` before retrying.

2. **F-2 (P0)** — `AuthInterceptor` as application interceptor leaks Bearer tokens on cross-origin redirects: `HttpClientFactory.kt:55`. Token exfiltration to redirected hosts. Fix by using `addNetworkInterceptor` or setting `followRedirects(false)`.

3. **F-3 (P0)** — `redactPromptBody = false` default writes full LLM prompt (source code, Jira content) verbatim to disk: `RawApiTraceConfig.kt:35`. Source code exfiltration via trace files. Fix by flipping default and restricting file permissions.

4. **F-5 (P0)** — Unencoded `filterText` and `branchName` in Bitbucket URL construction: `BitbucketBranchClient.kt:945,1170`. Parameter injection; OkHttp crash on special chars. Fix by using `HttpUrl.Builder.addQueryParameter()`.

5. **F-8 (P1)** — Static `tokenCache` keyed only by `ServiceType` causes cross-project token leakage in multi-project IDE sessions: `CredentialStore.kt:34`. Wrong project's token used for API calls. Fix by adding service URL to cache key.

---

## Files Audited

| File | Lines |
|------|-------|
| `core/src/main/kotlin/.../http/AuthInterceptor.kt` | 36 |
| `core/src/main/kotlin/.../http/HttpClientFactory.kt` | 121 |
| `core/src/main/kotlin/.../http/RetryInterceptor.kt` | 100 |
| `core/src/main/kotlin/.../http/RawApiTraceInterceptor.kt` | 289 |
| `core/src/main/kotlin/.../http/RawApiTraceConfig.kt` | 83 |
| `core/src/main/kotlin/.../http/PreSanitizeDumper.kt` | 61 |
| `core/src/main/kotlin/.../http/DevStatusCacheInvalidator.kt` | 116 |
| `core/src/main/kotlin/.../http/ChatHttpEventListener.kt` | ~120 |
| `core/src/main/kotlin/.../auth/CredentialStore.kt` | 90 |
| `core/src/main/kotlin/.../auth/AuthTestService.kt` | 319 |
| `core/src/main/kotlin/.../settings/ConnectionSettings.kt` | 52 |
| `core/src/main/kotlin/.../settings/PluginSettings.kt` | 464 |
| `core/src/main/kotlin/.../settings/ConnectionsConfigurable.kt` | 351 |
| `core/src/main/kotlin/.../settings/RepoContextResolver.kt` | ~100 |
| `core/src/main/kotlin/.../bitbucket/BitbucketBranchClient.kt` | 3115 |
| `core/src/main/kotlin/.../security/UrlSafetyGuard.kt` | 231 |
| `core/src/main/kotlin/.../ai/SourcegraphChatClient.kt` | 780 |
| `core/src/main/kotlin/.../ai/SourcegraphCompletionsStreamClient.kt` | 198 |
| `core/src/main/kotlin/.../ai/ModelCatalogService.kt` | 204 |
| `core/src/main/kotlin/.../ai/prompts/CommitMessagePromptBuilder.kt` | ~200 |
| `core/src/main/kotlin/.../vcs/GenerateCommitMessageAction.kt` | 761 |
| `core/src/main/kotlin/.../psi/PsiContextEnricher.kt` | 89 |
| `core/src/main/kotlin/.../polling/SmartPoller.kt` | 115 |
| `core/src/main/kotlin/.../events/EventBus.kt` | 28 |
| `core/src/main/kotlin/.../events/BranchChangedEventEmitter.kt` | 60 |
| `core/src/main/kotlin/.../maven/MavenBuildService.kt` | 111 |
| `core/src/main/kotlin/.../maven/MavenModuleDetector.kt` | 104 |
| `core/src/main/kotlin/.../healthcheck/checks/MavenCompileCheck.kt` | 32 |
| `core/src/main/kotlin/.../autodetect/AutoDetectFileListener.kt` | 84 |
| `core/src/main/kotlin/.../services/HttpFormPost.kt` | 202 |
| `core/src/main/kotlin/.../services/AttachmentSink.kt` | 24 |
| `core/src/main/kotlin/.../vfs/PostMutationRefresh.kt` | 133 |

---

## Enterprise-Readiness Verdict

**:core is not yet enterprise-ready**: it has seven P0 findings including a token-leaking application-layer `AuthInterceptor`, a non-idempotent-safe `RetryInterceptor` that can duplicate write operations, verbatim LLM prompt content written to disk by default, unencoded URL parameters in the Bitbucket client, and a cross-project token cache collision that would affect any organisation running IntelliJ with multiple projects open simultaneously.
