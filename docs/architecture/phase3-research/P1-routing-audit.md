# P1 — HttpClientFactory Routing Audit

**Run:** 2026-04-24 on `refactor/cleanup-perf-caching` @ Commit J (`27705b98`)
**Goal:** verify every backend HTTP client routes through `HttpClientFactory.clientFor(ServiceType)` so Phase 3's `CachingInterceptor` installs in one place and covers everything.

---

## TL;DR

**Result: gap larger than expected.** Phase 2's architectural enforcement migrated only `AttachmentDownloadService` to the proper `clientFor()` factory method. Every OTHER production HTTP client bypasses it via `HttpClientFactory.sharedPool.newBuilder()`, which gives them only the shared `ConnectionPool` and `Cache` — NOT the interceptor stack (`RetryInterceptor`, `SensitiveEndpointNoCacheInterceptor`, `HttpMetricsInterceptor`, `AuthInterceptor`).

This means:
1. **Installing `CachingInterceptor` inside `clientFor()` would currently cover ~1% of traffic** (only Jira attachments).
2. **`SensitiveEndpointNoCacheInterceptor` is already half-dead:** it's wired inside `clientFor()` but not `sharedPool`, so the 8 main-path clients don't even run it. That's a pre-existing bug we should fix anyway — sensitive `/rest/auth`, `/rest/api/2/myself`, `/_api/graphql`, `/api/user` responses are technically cacheable in OkHttp's disk cache today (10 MB cap, `HttpClientFactory.kt:104-107`).
3. **Each bypass client duplicates** timeouts, auth interceptor, retry interceptor construction — ~10 lines of boilerplate per client. Consolidation deletes code.

## The two factory entry points

`core/http/HttpClientFactory.kt` exposes two distinct paths that look similar but behave very differently:

### Entry point 1: `clientFor(ServiceType)` — the proper factory (only 1 caller)

```kotlin
// HttpClientFactory.kt:36-57
private val baseClient: OkHttpClient by lazy {
    sharedPool.newBuilder()
        .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
        .addInterceptor(RetryInterceptor())
        .addNetworkInterceptor(SensitiveEndpointNoCacheInterceptor())
        .build()
}

fun clientFor(service: ServiceType): OkHttpClient {
    return clients.getOrPut(service) {
        val scheme = when (service) {
            ServiceType.NEXUS -> AuthScheme.BASIC
            ServiceType.SOURCEGRAPH -> AuthScheme.TOKEN
            else -> AuthScheme.BEARER
        }
        baseClient.newBuilder()
            .addInterceptor(HttpMetricsInterceptor())
            .addInterceptor(AuthInterceptor({ tokenProvider(service) }, scheme))
            .build()
    }
}
```

Full stack: `connectionPool + cache + timeouts + RetryInterceptor + SensitiveEndpointNoCacheInterceptor + HttpMetricsInterceptor + AuthInterceptor`. Per-service auth scheme picked automatically. Cached per `ServiceType`.

**Callers:** `jira/service/AttachmentDownloadService.kt:38` (the ONLY one, introduced in Phase 2 Commit I).

### Entry point 2: `sharedPool.newBuilder()` — the half-step (8 callers)

```kotlin
// HttpClientFactory.kt:100-118
private val sharedConnectionPool = ConnectionPool(15, 5, TimeUnit.MINUTES)
private val sharedCache: Cache by lazy { Cache(cacheDir, 10L * 1024 * 1024) }

val sharedPool: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectionPool(sharedConnectionPool)
        .cache(sharedCache)
        .build()
}
```

Bare-bones: connection pool + (useless) cache. No auth, retry, sensitive-endpoint protection, or metrics. Every caller has to re-add these by hand.

**Callers (all main-path production HTTP):**
- `jira/api/JiraApiClient.kt:41` — manually adds `AuthInterceptor(BEARER) + RetryInterceptor`
- `bamboo/api/BambooApiClient.kt:34` — manually adds `AuthInterceptor(BEARER) + RetryInterceptor`
- `sonar/api/SonarApiClient.kt:46` — manually adds `AuthInterceptor(BEARER) + RetryInterceptor`
- `automation/api/DockerRegistryClient.kt:37` — manually adds `AuthInterceptor(BASIC) + RetryInterceptor`
- `jira/tasks/JiraTaskRepository.kt:42` — manually adds `AuthInterceptor(BEARER) + RetryInterceptor`
- `core/bitbucket/BitbucketBranchClient.kt:537` — manually adds `AuthInterceptor(BEARER) + RetryInterceptor`
- `core/auth/AuthTestService.kt:19` — test-connection only; lower priority
- `agent/settings/AgentParentConfigurable.kt:208` — settings UI "test LLM" button; lower priority
- `bamboo/api/BambooApiClient.kt:306` — *direct* use of `sharedPool` (no `.newBuilder()`) for artifact downloads that shouldn't carry auth headers. Intentional — keep as-is, document.

## Additional finding: Sourcegraph rolls its own entirely

**File:** `core/ai/SourcegraphChatClient.kt:124`

```kotlin
httpClientOverride ?: OkHttpClient.Builder()
    // ... timeout + interceptors configured from scratch, no HttpClientFactory
    .build()
```

- Has an injection point (`httpClientOverride`) used by `AgentParentConfigurable:206` passing `HttpClientFactory.sharedPool.newBuilder()…`
- When no override: constructs a plain `OkHttpClient.Builder()`, bypassing the shared connection pool AND the shared cache.
- This is a **separate issue** from the main audit; it predates Phase 2 and is structurally awkward because the Sourcegraph client is used by both production (via override) and tests (via default). Flag for Prong A scope decision.

## Pre-existing behavioral bug uncovered

`SensitiveEndpointNoCacheInterceptor` strips `Cache-Control` to `no-store` and removes `ETag` on 4 sensitive paths. It's wired inside `clientFor()`'s `baseClient`. Because 0 production HTTP goes through `clientFor()` except attachments, **this interceptor runs on almost nothing in practice**. Currently low blast radius because the Atlassian/Sonar backends already send `no-cache, no-store` themselves — but this interceptor exists precisely to defend against servers that don't, and once Prong A installs a more aggressive cache, it becomes load-bearing.

Not in Phase 3's scope to debate whether to keep the interceptor as insurance — just fix the wiring so it actually runs.

## Proposed fix shape (X1)

Migrate all 6 primary clients + 2 auxiliary to `clientFor(ServiceType)`:

```kotlin
// JiraApiClient — before
private val httpClient: OkHttpClient by lazy {
    HttpClientFactory.sharedPool.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(AuthInterceptor(tokenProvider, AuthScheme.BEARER))
        .addInterceptor(RetryInterceptor())
        .build()
}

// JiraApiClient — after
class JiraApiClient(
    private val baseUrl: String,
    private val httpClient: OkHttpClient,  // injected, constructed via clientFor(JIRA)
) { ... }
```

Constructor injection point moves to the service layer (`JiraServiceImpl`, already a project-scope service) which already reads `HttpClientFactory.timeoutsFromSettings(project)` — it's a short walk. Applies equally to Bamboo, Sonar, Bitbucket, Docker.

**Why constructor injection rather than static factory call inside the client?** The `HttpClientFactory` instance takes a `tokenProvider: (ServiceType) -> String?`. That's a project-scoped function (credentials live in `PasswordSafe`, keyed per project). Having the API client construct its own `HttpClientFactory` at lazy-init time would recreate the ConcurrentHashMap of per-service clients every time an API client is rebuilt — defeating Caffeine-style dedup. Constructing once in the service and passing the `OkHttpClient` down is cheaper and more testable.

## Diff summary, estimated

- Net LOC: **−60 to −100** (remove duplicated timeout + interceptor setup from 6 files).
- Touched files: ~8 production + their unit tests.
- Behavioral changes: (a) `HttpMetricsInterceptor` now runs on all traffic (previously only attachments); (b) `SensitiveEndpointNoCacheInterceptor` runs on all traffic (minor; backends already send no-cache); (c) per-service OkHttpClient instance is now cached in `ConcurrentHashMap<ServiceType, OkHttpClient>` in `HttpClientFactory` (more efficient — previously each API client held its own lazy instance).
- Test impact: existing client unit tests inject `OkHttpClient` via the new constructor arg; most tests already use an injected client.

## Recommendation

This is **Phase 2 tail work**, not Phase 3. It's pure refactor with no caching semantics, so it doesn't need the baseline-metrics gate. Run it as a separate set of focused commits (one per backend) before starting Prong A. Shape:

1. `refactor(jira): JiraApiClient + JiraTaskRepository route through HttpClientFactory.clientFor()`
2. `refactor(bamboo): BambooApiClient route through HttpClientFactory.clientFor()`
3. `refactor(sonar): SonarApiClient route through HttpClientFactory.clientFor()`
4. `refactor(automation): DockerRegistryClient route through HttpClientFactory.clientFor()`
5. `refactor(core): BitbucketBranchClient route through HttpClientFactory.clientFor()` (this one is bigger — the file is 2000+ lines and has many internal newBuilders)
6. `refactor(core): AuthTestService + AgentParentConfigurable route through clientFor()`
7. `refactor(core): consider SourcegraphChatClient — either migrate or document as intentional special case`

Commit 7 may drop out if on reading we decide Sourcegraph is deliberately independent (it handles SSE streaming which has different timeout requirements than REST). Kept as "consider" not "do".

Each commit: `./gradlew :<module>:test` + `verifyPlugin` green. Same discipline as Phases 1 and 2.

**Once this lands, `HttpClientFactory.clientFor()` is the sole seam and Phase 3's `CachingInterceptor` installs in exactly one file, in exactly one line, and covers 100% of routable backend traffic.**
