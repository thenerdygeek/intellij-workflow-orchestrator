# Phase 3 Research — OkHttp Client-Side Caching

**Scope:** What OkHttp gives us for free vs. what we have to write, plus the Caffeine + OkHttp two-tier pattern and the real-world gotchas that matter for the Workflow Orchestrator plugin.

**Version baseline (April 2026):** OkHttp **5.3.2** (2025-11-18) is the latest stable on the 5.x line (5.0.0 GA 2025-07-02; "first stable release since 2023"). OkHttp 4.12.0 remains the last stable 4.x. Both run on JDK 17 (our IntelliJ target). Caffeine **3.2.3** (2024-10-28) is the latest stable and requires JDK 11+.

---

## 1. OkHttp Built-in `Cache` Class

### 1.1 Construction

Two constructors on `okhttp3.Cache`:

```kotlin
Cache(directory: File, maxSize: Long)
Cache(fileSystem: FileSystem, directory: Path, maxSize: Long)  // for testing with an in-memory FS
```

`maxSize` is bytes, not entries. OkHttp evicts least-recently-used entries when `size() > maxSize`. It does *not* enforce a hard ceiling instantaneously — eviction is opportunistic, so actual on-disk size can briefly exceed `maxSize`.

**Canonical plugin use:**

```kotlin
val cache = Cache(
    directory = File(PathManager.getPluginTempPath(), "workflow-orch-http"),
    maxSize  = 50L * 1024 * 1024 // 50 MiB
)
val client = OkHttpClient.Builder()
    .cache(cache)
    .build()
```

### 1.2 Disk Format (`DiskLruCache`)

Files on disk are managed by `okhttp3.internal.cache.DiskLruCache` (Square's own implementation, not Android's `android.util.LruCache`). Each entry is a small set of files plus one journal file. The journal is rebuilt every 2,000 operations to keep it bounded.

**Corruption recovery:** If the journal is unreadable at startup, OkHttp logs `"DiskLruCache [directory] is corrupt: ...removing"` and wipes the directory via `delete()`, then re-initializes from empty. **This means any upstream bug that corrupts the journal silently loses the whole cache** — not terrible for an HTTP cache, but worth knowing for support burns.

**Exclusive access:** A single `Cache` instance must be the *only* one touching a directory. *"It is an error to have multiple caches accessing the same cache directory simultaneously."* The `Cache` instance itself, however, **can safely be shared across multiple `OkHttpClient` instances** — the official recommendation is one `Cache`, one or more clients.

### 1.3 HTTP Directives Respected

OkHttp claims RFC 7234 compliance ("this client honors all HTTP/1.1 (RFC 7234) cache headers"). Practical coverage:

| Directive | Behavior |
|---|---|
| `Cache-Control: max-age=N` | Cached; served without network for `N` seconds |
| `Cache-Control: no-cache` | Cached but **always revalidated** with server (conditional GET) |
| `Cache-Control: no-store` | Never cached, and existing cache entry is discarded |
| `Cache-Control: private` | Cached (client cache = private by definition) |
| `Cache-Control: public` | Cached |
| `Cache-Control: must-revalidate` | When stale, forces revalidation instead of serving stale |
| `Cache-Control: immutable` | Skips revalidation entirely while fresh |
| `Expires: <date>` | Respected as fallback when no `max-age` |
| `ETag` + `If-None-Match` | Auto-injected on subsequent requests; 304 reuses cached body |
| `Last-Modified` + `If-Modified-Since` | Auto-injected when no ETag; 304 reuses cached body |
| `Vary: <headers>` | Cache key includes the listed request headers; different values = different cache entries |
| `Vary: *` | **Response is not cached at all** (by RFC) |

**Priority:** OkHttp picks ETag over Last-Modified when both are present.

### 1.4 Cache Hit / 304 Revalidation / Miss Semantics

From `Response`, three observable fields reveal what happened:

- `response.cacheResponse` — non-null if a cached entry was used
- `response.networkResponse` — non-null if the network was touched
- Both non-null and `networkResponse.code == 304` → **conditional cache hit** (body came from cache, server confirmed freshness)

**Counters on the `Cache` object:**

```kotlin
cache.requestCount()          // total
cache.networkCount()          // had to hit network (miss or revalidate)
cache.hitCount()              // served fully from cache
cache.writeSuccessCount()
cache.writeAbortCount()
cache.size() / cache.maxSize()
```

On a 304, `hitCount` and `networkCount` both increment — which matches the "I still paid a round trip but saved the body" reality.

### 1.5 Thread Safety

- `Cache` itself is thread-safe and documented as such.
- Safe to share one `Cache` across multiple `OkHttpClient` instances (standard recommendation).
- `cache.urls()` returns a `MutableIterator<String>` that **does not throw `ConcurrentModificationException`** — writes during iteration may be silently skipped, and calling `iterator.remove()` evicts an entry. This is the correct mechanism for URL-pattern invalidation.

### 1.6 Known Limitations

1. **Method filter:** Only `GET` is actually cached in practice. `HEAD` is technically eligible under the RFC but rarely cached because responses have no body. `POST`, `PUT`, `PATCH`, `DELETE`, `MOVE` are never cached.
2. **Partial responses (`206`) are never cached.**
3. **`Vary: *` is not cacheable** — the response is stored nowhere.
4. **Response body must be fully consumed** for the entry to reach disk. Cancellation or an unread body = no cache write. This is the single biggest footgun and easy to hit in coroutine code that throws mid-stream.
5. **304 does not update the cached entry's freshness metadata** in older OkHttp (see [issue #2815](https://github.com/square/okhttp/issues/2815)) — subsequent requests after a 304 may keep hitting the network until a new `max-age` is delivered on a full 200. Modern 5.x releases have largely addressed this but it's worth a regression test on the version we pin.
6. **Invalidate-on-mutation is partially buggy:** `Cache.put()` calls `HttpMethod.invalidatesCache(method)` which returns true for `POST`, `PUT`, `DELETE`, `PATCH`, `MOVE` — *but the invalidation only fires if the response has a body.* A `PUT → 204 No Content` does **not** invalidate the cached GET ([issue #3203](https://github.com/square/okhttp/issues/3203)). We must handle this in our `CachingInterceptor`.
7. **Authorization header:** OkHttp **does** cache authenticated responses as long as the server doesn't send `Cache-Control: no-store`. This is RFC-compliant for a *private* client cache (the directive `private` actually permits this), but if your server uses bearer tokens with short rotation and returns `Cache-Control: private, max-age=60`, the cache will happily serve the stale entry to any caller — including one who presents a different token. **For our plugin this is safe** because the cache is per-install and token rotation is rare, but document it.
8. **Cookie-handling is orthogonal:** OkHttp's `CookieJar` runs before the cache decision. Cached responses do *not* re-execute `CookieJar.saveFromResponse`, so Set-Cookie on a cached 200 hit is effectively dropped on replay. Not an issue for bearer-token APIs.

### 1.7 Forcing Cache / Network Behavior

```kotlin
// Force network — skip cache entirely
val req = Request.Builder().url(url).cacheControl(CacheControl.FORCE_NETWORK).build()

// Force cache — never hit network; returns 504 Unsatisfiable Request if not cached
val req = Request.Builder().url(url).cacheControl(CacheControl.FORCE_CACHE).build()

// Only-if-cached with max-stale — "give me anything you have"
val req = Request.Builder().url(url).cacheControl(
    CacheControl.Builder()
        .onlyIfCached()
        .maxStale(Int.MAX_VALUE, TimeUnit.SECONDS)
        .build()
).build()
```

---

## 2. `CacheControl` Builder

`CacheControl` is both the **parsed form** of the header (exposed on `Response.cacheControl`) and a builder for new headers.

### 2.1 Builder Methods

```kotlin
CacheControl.Builder()
    .maxAge(duration, TimeUnit)        // Response: "cache this for N"
    .maxStale(duration, TimeUnit)      // Request: "I'll take up to N seconds stale"
    .minFresh(duration, TimeUnit)      // Request: "only give me if fresh for at least N more"
    .noCache()                         // "must revalidate, don't use stored without asking"
    .noStore()                         // "don't even put on disk"
    .onlyIfCached()                    // Request-only: cache-or-fail
    .noTransform()                     // Don't transform body
    .immutable()                       // Response: never revalidate
    .build()
    .toString()                        // → "max-age=60, no-transform"
```

### 2.2 Overriding Server Headers via Network Interceptor

This is the canonical pattern for *"server doesn't send Cache-Control but I want 60-second TTL"*:

```kotlin
class ServerHeaderOverrideInterceptor(
    private val defaultMaxAge: Int = 60
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.header("Cache-Control") == null) {
            return response.newBuilder()
                .header("Cache-Control", "max-age=$defaultMaxAge")
                .build()
        }
        return response
    }
}

// Registered as NETWORK interceptor so the modified header reaches the Cache layer:
OkHttpClient.Builder()
    .cache(cache)
    .addNetworkInterceptor(ServerHeaderOverrideInterceptor())
    .build()
```

**Critical:** must be a **network** interceptor (`addNetworkInterceptor`), not an application interceptor. The OkHttp cache reads headers *after* network interceptors run but *before* application interceptors see the response. If you register this as `addInterceptor`, the cache layer sees the original (header-less) response and never caches it.

---

## 3. Writing a `CachingInterceptor`

### 3.1 Application vs Network — Which Layer

| Goal | Layer | Why |
|---|---|---|
| Override/inject `Cache-Control` so OkHttp's own cache respects it | **Network** | Cache reads headers after network interceptors |
| Synthesize `ETag` from body hash for later `If-None-Match` | **Network** | Same reason — cache must see it |
| Per-URL TTL policy (different endpoints, different freshness) | **Network** | Same |
| Force offline fallback (`FORCE_CACHE` if offline) | **Application** | Application interceptor sees cached responses too |
| Short-circuit & return stub (mocking, circuit-breaker) | **Application** | Can skip `chain.proceed()` |
| Invalidate related GET entries on POST/PUT/DELETE | **Application** | Fires once per logical call, not per redirect |

### 3.2 URL-Pattern TTL Policy (Network Interceptor)

```kotlin
class UrlPatternCachePolicyInterceptor(
    private val policies: List<Pair<Regex, Int>> = listOf(
        Regex("""/rest/api/2/issue/\w+""")  to 60,   // issue details: 60s
        Regex("""/rest/api/2/search""")      to 10,   // search: 10s
        Regex("""/rest/api/latest/board""") to 300   // boards: 5 min
    )
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        // Only override when server didn't specify and request was a GET
        if (request.method != "GET") return response
        if (response.header("Cache-Control")?.contains("no-store") == true) return response

        val path = request.url.encodedPath
        val matched = policies.firstOrNull { (re, _) -> re.containsMatchIn(path) } ?: return response
        val (_, seconds) = matched

        return response.newBuilder()
            .header("Cache-Control", "private, max-age=$seconds")
            .removeHeader("Pragma")      // legacy HTTP/1.0 header; can defeat cache
            .build()
    }
}
```

### 3.3 Synthesized ETag (Network Interceptor)

When the server *doesn't* send an ETag but we still want conditional revalidation, we hash the body and inject a synthetic one. **Important:** this only saves bandwidth if the *server* also honors the `If-None-Match` we send back — i.e. it computes the same hash. If the server does not, the synthesized ETag is useless (server will return a full 200 every time). For pure OkHttp client-side dedup of identical bodies, hash-in-memory with Caffeine is simpler.

```kotlin
class SynthesizedEtagInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.header("ETag") != null) return response
        if (chain.request().method != "GET") return response

        // Read body once, compute SHA-256, and rebuild response
        val source = response.body?.source() ?: return response
        source.request(Long.MAX_VALUE)                 // buffer fully
        val bodyBytes = source.buffer.snapshot().toByteArray()
        val etag = "\"" + bodyBytes.sha256Hex() + "\""

        return response.newBuilder()
            .header("ETag", etag)
            .body(bodyBytes.toResponseBody(response.body?.contentType()))
            .build()
    }
}

private fun ByteArray.sha256Hex(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }
```

### 3.4 Invalidation on Mutation (Application Interceptor)

Handles the 204-No-Content gap and also lets us match *related* GET paths, not just the exact URL:

```kotlin
class MutationInvalidationInterceptor(
    private val cache: Cache,
    private val relatedPaths: (Request) -> List<Regex> = { req ->
        // e.g. PUT /rest/api/2/issue/ABC-123 should invalidate:
        //   GET /rest/api/2/issue/ABC-123
        //   GET /rest/api/2/search       (rough, but common)
        val id = req.url.encodedPathSegments.lastOrNull().orEmpty()
        listOf(
            Regex(Regex.escape(req.url.encodedPath)),
            Regex("""/rest/api/2/search.*"""),
            Regex("""/rest/api/2/issue/$id(/.*)?""")
        )
    }
) : Interceptor {
    private val mutatingMethods = setOf("POST", "PUT", "PATCH", "DELETE")

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (request.method !in mutatingMethods) return response
        if (!response.isSuccessful) return response       // don't invalidate on 4xx/5xx

        val patterns = relatedPaths(request)
        val urlIter = cache.urls()
        while (urlIter.hasNext()) {
            val url = urlIter.next()
            if (patterns.any { it.containsMatchIn(url) }) {
                urlIter.remove()                          // evicts the entry
            }
        }
        return response
    }
}
```

### 3.5 Thread-Safety Notes

- `Cache.urls()` is safe to call concurrently with requests on the same cache.
- Interceptors run on whatever thread OkHttp dispatches — for the plugin, we're already on `Dispatchers.IO`.
- Avoid holding cache-internal state in the interceptor; the `Cache` is the source of truth. If you need in-memory dedup, layer Caffeine on top (§4).

---

## 4. Caffeine (L1 In-Memory Layer)

### 4.1 Version / JDK

- **Caffeine 3.2.3** (latest stable, Oct 2024). Requires JDK 11+.
- JDK 17 compatible (our IntelliJ target). **Watch-out:** [SOLR-16463](https://issues.apache.org/jira/browse/SOLR-16463) documents a historical JIT crash on JDK 17+ that has been addressed in modern Caffeine releases — pin to 3.2.x.
- If we ever had to support JDK 8 (we don't; IntelliJ 2025.1+ is JDK 17), Caffeine 2.x is still maintained.

Gradle:

```kotlin
implementation("com.github.ben-manes.caffeine:caffeine:3.2.3")
```

### 4.2 Size-Based LRU

Caffeine uses **Window TinyLFU (W-TinyLFU)**, not strict LRU — near-optimal hit-rate with 8 bytes of per-entry overhead for the frequency sketch. Two ways to bound size:

```kotlin
// Count-based: max 10,000 entries
val byCount = Caffeine.newBuilder()
    .maximumSize(10_000)
    .build<String, CachedResponse>()

// Weight-based: max 50 MB total, entries weighted by body length
val byBytes = Caffeine.newBuilder()
    .maximumWeight(50L * 1024 * 1024)
    .weigher<String, CachedResponse> { _, v -> v.body.size }
    .build<String, CachedResponse>()
```

### 4.3 Expiration

```kotlin
Caffeine.newBuilder()
    .expireAfterWrite(Duration.ofMinutes(5))    // N seconds after insert/update
    .expireAfterAccess(Duration.ofMinutes(10))  // N seconds after last get/put
    .refreshAfterWrite(Duration.ofMinutes(2))   // proactively reload when stale (LoadingCache only)
    .build()
```

Use `expireAfterWrite` for freshness semantics (matches HTTP `max-age`); `expireAfterAccess` is "LRU with timeout" and is less useful for API caching because a hot endpoint never expires.

### 4.4 `AsyncLoadingCache` — Thundering Herd Protection

```kotlin
val loader: AsyncCacheLoader<String, JiraIssue> = AsyncCacheLoader { key, _ ->
    CompletableFuture.supplyAsync({ fetchFromNetwork(key) }, ioExecutor)
}
val cache: AsyncLoadingCache<String, JiraIssue> = Caffeine.newBuilder()
    .maximumSize(1_000)
    .expireAfterWrite(Duration.ofMinutes(5))
    .buildAsync(loader)

// Coroutine integration:
suspend fun getIssue(key: String): JiraIssue = cache.get(key).await()
```

**Concurrent-miss coalescing:** Under concurrency, `get(key)` for the same key triggers **exactly one** `AsyncCacheLoader.asyncLoad()` invocation. The first caller blocks on the `CompletableFuture`; subsequent callers receive the same future. This solves thundering herd for free.

**Caveat:** `refresh()` does *not* coalesce. Multiple concurrent `refresh(key)` calls can trigger multiple reloads ([discussion #1935](https://github.com/ben-manes/caffeine/discussions/1935)).

### 4.5 Memory Footprint

From the Caffeine wiki ([Memory overhead](https://github.com/ben-manes/caffeine/wiki/Memory-overhead)):

| Config | Bytes per entry |
|---|---|
| Unbounded `Cache.newBuilder().build()` | 32 |
| `maximumSize` | 64 |
| `maximumSize` + `expireAfterWrite/Access` | 72–80 |
| Above + weak/soft refs | 88–120 |

Plus 8 bytes for the TinyLFU sketch. Fixed startup overhead per cache instance: 1,100–1,400 bytes for a configured cache.

**Is it safe to cache 50KB response bodies?** Yes, trivially. 10,000 × 50KB ≈ 500 MB — too much. 1,000 × 50KB ≈ 50 MB — fine. Use `maximumWeight` with a byte-weigher to bound memory directly rather than counting entries.

### 4.6 When to use Caffeine vs OkHttp Cache

- **OkHttp Cache** — wire-level HTTP caching, conditional GET, server-sent `Cache-Control`, across process restarts.
- **Caffeine** — application-level values (parsed `JiraIssue`, not raw JSON), L1 deduplication, coalescing concurrent loads, TTL independent of HTTP headers.

For the plugin, both make sense in different places — raw bytes in OkHttp Cache (L2), parsed domain objects in Caffeine (L1).

---

## 5. Two-Tier Caching Patterns

### 5.1 Architecture

```
caller
   │ suspend fun getIssue(key)
   ▼
Caffeine AsyncLoadingCache (L1, parsed domain objects, in-memory)
   │ asyncLoad on miss
   ▼
OkHttpClient with Cache (L2, raw HTTP responses, on disk)
   │ network on miss or revalidation
   ▼
remote server
```

### 5.2 Write-Through vs Read-Through

- **Read-through** (recommended): L1 miss → L1 loads from L2 (via OkHttp) → L2 miss → network. Populates both tiers from a single miss. Simplest model; matches Caffeine's `LoadingCache` semantics natively.
- **Write-through** (mutations): Write to network → on 2xx, invalidate both L1 (`cache.invalidate(key)`) and L2 (`cache.urls()` iterator). For our plugin, mutations are rare and we can afford naive full invalidation.

### 5.3 Coherence Concerns

**Key worry:** what if L2 has a fresher entry than L1? This happens if another `OkHttpClient` sharing the same disk cache (or a different process) wrote a newer response while our L1 entry was still live. Mitigations:

1. **Same-process only:** The plugin is a single JVM. Only our code writes to either tier. No external writer. Coherence is a non-issue in practice.
2. **Trust L1 TTL:** If L1's `expireAfterWrite` is ≤ L2's max-age, L1 is always as-fresh-or-stale-as L2. Keep them aligned.
3. **Explicit invalidation on mutation** (§3.4) that hits both tiers.

### 5.4 Prebuilt L1+L2 Libraries?

**No well-known Kotlin/Java library does coherent L1+L2 on top of OkHttp.** Options considered:

- **Cache2k** — fast, L1-only, less community momentum than Caffeine.
- **Ehcache 3** — supports tiered (heap → off-heap → disk) but is heavyweight (~1 MB jar, Terracotta lineage) and is aimed at JCache/Spring environments, not an IntelliJ plugin.
- **Spring Cache** — annotation-driven, requires Spring. Not an option.
- **JCache (JSR-107)** — just an API; needs a provider (Ehcache/Hazelcast/Infinispan). Overkill for our use.

**Recommendation:** Compose Caffeine (L1) + OkHttp Cache (L2) ourselves. ~50 lines of glue code, no new dependency beyond Caffeine.

---

## 6. Coroutine / Kotlin Integration

### 6.1 Suspend-Friendly OkHttp

Two mainstream options:

1. **[gildor/kotlin-coroutines-okhttp](https://github.com/gildor/kotlin-coroutines-okhttp)** — minimal `Call.await()` extension with cancellation support. ~50 LoC; we can vendor it.
2. **Roll our own** with `suspendCancellableCoroutine`:

```kotlin
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response)
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
}
```

### 6.2 Caffeine `CompletableFuture` → `suspend`

```kotlin
suspend fun <K, V> AsyncLoadingCache<K, V>.getSuspending(key: K): V = get(key).await()
```

`kotlinx.coroutines` ships `kotlinx.coroutines.future.await()` for this (`kotlinx-coroutines-jdk8`).

### 6.3 Cancellation-Safe Reads

- A cancelled coroutine whose `Call` is cancelled mid-response may produce a half-read body. **OkHttp will not cache half-read bodies** (§1.6 limitation 4). This is actually the behavior we want — no corrupt cache.
- Caffeine's `AsyncLoadingCache` coalesces concurrent callers, so if caller A cancels mid-load, caller B's coroutine still receives the completed future.

---

## 7. Real-World Gotchas

| # | Gotcha | Mitigation |
|---|---|---|
| 1 | **Body must be fully consumed** or cache write is aborted silently. | Always consume; use `response.body.close()` in finally. For streaming callers who don't want the body in memory, accept that responses won't be cached. |
| 2 | **Authorization header caching** — OkHttp caches authenticated responses by default. | For our plugin (per-user install, single user), this is fine. Add `Cache-Control: no-store` on endpoints where tokens are scoped finer than the install. |
| 3 | **Cookie / Set-Cookie replay** — `CookieJar.saveFromResponse` is not called on cache hits. | N/A — we use bearer tokens, not cookies. |
| 4 | **`Vary: *` kills caching** — some servers send this on authenticated endpoints. | If we hit such an endpoint and want caching anyway, strip the header in a network interceptor (with care). |
| 5 | **Large response bodies + `string()`** can OOM. | Don't cache huge bodies; bound `maxSize` and set a per-response weigher if using Caffeine. |
| 6 | **Cache corruption on disk** → whole cache is wiped silently. | Accept the rebuild cost; log `Cache.writeAbortCount()` to detect. |
| 7 | **No formal version bump for schema migration.** OkHttp's cache format is versioned internally; across OkHttp major versions (4 → 5) the format is compatible in practice, but we should wipe on first run after upgrading to avoid subtle issues. | On plugin update, invalidate cache directory: `File(dir, "http-cache").deleteRecursively()`. |
| 8 | **Redirects and retries:** application interceptors see only the final response; network interceptors see each hop. If we want to cache the final result only, stick to application-layer policy reads. |
| 9 | **304 doesn't refresh entry's `max-age`** in some OkHttp versions → every subsequent request re-validates. | Pin OkHttp 5.x (fix landed); monitor `networkCount()` vs `hitCount()` in diagnostics. |
| 10 | **POST/PUT/PATCH/DELETE with 204 response do NOT invalidate cache.** | Our `MutationInvalidationInterceptor` (§3.4) handles this. |
| 11 | **Clock skew** — OkHttp uses server `Date`/`Age` headers for freshness. On machines with drifted clocks, cache freshness is off. | Out of scope; trust OS clock. |

---

## 8. Alternatives — Brief Comparison

| Library | Fit for plugin | Notes |
|---|---|---|
| **OkHttp `Cache`** | Native | Already transitively on classpath; zero-cost to adopt. HTTP-aware. |
| **Caffeine** | Perfect L1 | Best-in-class in-memory cache; 8 bytes/entry overhead; coalescing. |
| **Cache2k** | Redundant | Competitor to Caffeine; slightly faster in micro-benchmarks but Caffeine has broader adoption. |
| **Ehcache 3** | Overkill | Tiered cache with off-heap + disk; targets JCache/Spring. Heavy dependency, not worth it here. |
| **JCache (JSR-107)** | Wrong abstraction | Standard API, requires a provider. No HTTP awareness. |
| **Spring Cache** | Not applicable | Requires Spring. Not an option in an IJ plugin. |
| **`ConcurrentHashMap`** | Works for tiny caches | No eviction; grows unbounded. Only for small, bounded-domain caches (e.g. <100 entries). |

**Why OkHttp's built-in cache is sufficient for the L2 layer:** it's already there, HTTP-correct (respects `Cache-Control`, `ETag`, `Vary`, `Last-Modified`), thread-safe, persists across IDE restarts, and has zero dependency cost. Its limitations (GET-only, body-must-fully-read, occasional 304 quirks) are all manageable with a thin interceptor layer.

**Why we still want Caffeine on top:** OkHttp Cache stores raw bytes; every hit re-parses JSON. For hot paths (e.g. repeated `JiraService.getIssue(key)` calls in the same IDE session), caching the parsed `JiraIssue` in Caffeine avoids both disk I/O and JSON parsing. It also adds coalescing that OkHttp Cache does not provide.

---

## 9. Recommended Plugin Architecture

```kotlin
// core/http/CachingHttpClient.kt
object CachingHttpClient {
    private val okHttpCache = Cache(
        directory = File(PathManager.getPluginTempPath(), "workflow-orch-http"),
        maxSize   = 50L * 1024 * 1024
    )

    fun build(settings: PluginSettings): OkHttpClient {
        val (connectTo, readTo, writeTo) = HttpClientFactory.timeoutsFromSettings(settings)
        return OkHttpClient.Builder()
            .connectTimeout(connectTo).readTimeout(readTo).writeTimeout(writeTo)
            .cache(okHttpCache)
            // Network layer: teach cache about endpoints that lack Cache-Control
            .addNetworkInterceptor(UrlPatternCachePolicyInterceptor())
            // Application layer: mutation invalidation
            .addInterceptor(MutationInvalidationInterceptor(okHttpCache))
            .build()
    }

    fun stats() = "requests=${okHttpCache.requestCount()}, " +
        "hits=${okHttpCache.hitCount()}, " +
        "network=${okHttpCache.networkCount()}"

    fun evictAll() = okHttpCache.evictAll()
    fun invalidateMatching(predicate: (String) -> Boolean) {
        val it = okHttpCache.urls()
        while (it.hasNext()) if (predicate(it.next())) it.remove()
    }
}
```

Layer Caffeine as a per-service optimization on top (e.g. `JiraServiceImpl` wraps `getIssue()` with an `AsyncLoadingCache<String, JiraIssue>`). Do **not** add a blanket L1 in front of every HTTP call — it complicates invalidation and memory accounting with little benefit on cold endpoints.

---

## 10. Sources

- [OkHttp Caching Guide](https://square.github.io/okhttp/features/caching/)
- [OkHttp 5.x `Cache` API](https://square.github.io/okhttp/5.x/okhttp/okhttp3/-cache/index.html)
- [OkHttp 5.x `CacheControl` API](https://square.github.io/okhttp/5.x/okhttp/okhttp3/-cache-control/index.html)
- [OkHttp 5.x `Cache.urls()`](https://square.github.io/okhttp/5.x/okhttp/okhttp3/-cache/urls.html)
- [OkHttp Change Log](https://square.github.io/okhttp/changelogs/changelog/)
- [OkHttp Interceptors](https://square.github.io/okhttp/features/interceptors/)
- [OkHttp issue #2815 — 304 doesn't refresh expiration](https://github.com/square/okhttp/issues/2815)
- [OkHttp issue #3203 — PUT 204 doesn't invalidate cache](https://github.com/square/okhttp/issues/3203)
- [OkHttp issue #7755 — POST invalidation behavior](https://github.com/square/okhttp/issues/7755)
- [OkHttp issue #3927 — Cache-Control max-age edge cases](https://github.com/square/okhttp/issues/3927)
- [OkHttp issue #5599 — response size limit](https://github.com/square/okhttp/issues/5599)
- [Caffeine GitHub](https://github.com/ben-manes/caffeine)
- [Caffeine Efficiency (W-TinyLFU)](https://github.com/ben-manes/caffeine/wiki/Efficiency)
- [Caffeine Memory Overhead](https://github.com/ben-manes/caffeine/wiki/Memory-overhead)
- [Caffeine Population Strategies](https://github.com/ben-manes/caffeine/wiki/Population)
- [Caffeine discussion #1935 — sync vs async refresh](https://github.com/ben-manes/caffeine/discussions/1935)
- [gildor/kotlin-coroutines-okhttp](https://github.com/gildor/kotlin-coroutines-okhttp)
- [Stefan M. — ETag Caching with OkHttp](https://stefma.medium.com/etag-caching-with-okhttp-7b37e494e9e8)
- [Frank — Reducing networking footprint with OkHttp ETags](https://medium.com/android-news/reducing-your-networking-footprint-with-okhttp-etags-and-if-modified-since-b598b8dd81a1)
- [Caching with OkHttp Interceptor and Retrofit (Outcome School)](https://outcomeschool.com/blog/caching-with-okhttp-interceptor-and-retrofit)
- [Conditional Caching with Retrofit and OkHttp (valueof.io)](https://www.valueof.io/blog/implement-retrofit-okhttp-caching)
