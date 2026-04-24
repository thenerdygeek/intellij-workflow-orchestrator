# Phase 3 — Caching Strategy

**Status:** IMPLEMENTED (see §11 — Outcomes, 2026-04-25)
**Branch:** `refactor/cleanup-perf-caching`
**Date:** 2026-04-24
**Supersedes:** Phase 3 three-item description in `project_branch_refactor_cleanup_perf_caching.md` (the shape of item 1 has changed; items 2 and 3 are unchanged)

---

## 1. Short version (read this first)

The original Phase 3 plan assumed we could cache HTTP responses by turning on OkHttp's built-in `Cache`, which uses server-supplied `ETag` and `Last-Modified` headers to know when data is stale. **Research shows none of the Atlassian backends (Jira DC, Bamboo, Bitbucket DC) nor SonarQube ship those headers on the REST JSON we care about.** They even actively tell the client not to cache (`Cache-Control: no-cache, no-store`). So the free mechanism doesn't fire — we have to build our own.

The revised Phase 3 has three prongs, all client-side (no backend changes required):

| Prong | What it does | Primary tech |
|---|---|---|
| **A. HTTP response caching** | Stores response bodies in memory, hashes them, returns the old body if the server gives us a byte-identical response ("synthetic ETag") | Caffeine + custom OkHttp `CachingInterceptor` |
| **B. RepoContextResolver memoization** | Stops recomputing the active repo on every tool window refresh; invalidates automatically when the user switches repos | IntelliJ `CachedValuesManager` + `SimpleModificationTracker` |
| **C. SmartPoller result dedup** | Polls still happen, but the UI doesn't re-render if the response body is byte-identical to last poll | Body hash comparison inside poller |

Expected wins (order of magnitude, to be confirmed against baseline): ~40–60% of poll responses are identical to prior poll → equivalent reduction in EDT repaint cost; HTTP bandwidth savings are more modest (bodies still fly over the wire — the saving is in Jackson parsing + Swing repaint).

---

## 2. Glossary (plain-English primer with analogies)

Each term below has three parts: a plain-English definition, an everyday analogy, and a concrete scenario from this plugin. Skip the ones you already know; come back when you hit the word elsewhere in the doc.

### 2.1 HTTP protocol terms

**HTTP request / response**
- *Plain:* The client (plugin) sends a question over the network, the server sends an answer back.
- *Analogy:* You send a letter to a catalog company asking for their latest prices. They mail you a booklet back.
- *Plugin:* Every tool-window refresh sends HTTP requests to Jira, Bamboo, etc., and renders the responses.

**GET, POST, PUT, DELETE** (HTTP "methods")
- *Plain:* Different verbs for different kinds of requests. GET = "give me this data", POST = "create this new thing", PUT = "replace this thing", DELETE = "remove it".
- *Analogy:* Asking a librarian: GET is "may I see this book?", POST is "here's a new book to add", PUT is "replace this book with this updated copy", DELETE is "please remove this book from the collection".
- *Plugin:* Listing PRs is a GET. Transitioning a Jira ticket is a POST. Creating a PR is a POST. Marking a comment resolved is a PUT.

**Mutation**
- *Plain:* Any request that changes data on the server (POST, PUT, PATCH, DELETE). Not a GET.
- *Analogy:* Editing your library's catalog versus just reading it.
- *Plugin:* When the user clicks "Transition to In Progress", that's a mutation. Our cache has to notice and throw away the now-stale ticket GET.

**Header**
- *Plain:* Little labeled metadata attached to a request or response, alongside the body. Things like `Authorization: Bearer ...`, `Content-Type: application/json`.
- *Analogy:* The outside of a mailed envelope — to/from addresses, postage stamp, "fragile" sticker. The body is the letter inside.
- *Plugin:* `HttpClientFactory` adds the `Authorization` header to every request. Our caching interceptor will read response headers to decide cache rules.

**`Cache-Control` header**
- *Plain:* The server's instructions about whether and how long the client may cache this response.
- *Analogy:* "Use by" sticker on food — "good until Friday" vs. "consume immediately".
- *Plugin:* Jira sends `Cache-Control: no-cache, no-store` — it's saying "don't save this; ask me again every time". Which is why we need our own strategy that ignores the server's advice.

**`ETag`** (Entity Tag)
- *Plain:* A short version label the server attaches to each response. Think of it as a fingerprint for "this exact copy of the data".
- *Analogy:* A receipt number. You ask the post office for package #1234; they tell you its status AND give you ticket `ETag: "v17"`. Later you call back: "do you still have version v17 of package 1234?" — they can instantly say "yes, unchanged" without re-looking-up all the tracking detail.
- *Plugin:* **Would** let us efficiently check "has Jira ticket PROJ-123 changed since I saw it?" — but Jira DC doesn't send ETags, so we can't play this game.

**`If-None-Match` header + `304 Not Modified` response**
- *Plain:* The client says "I already have the version tagged v17 — only send me the body if it's changed." Server replies with a tiny 304 response (no body) if unchanged, or a full 200 with new data if changed.
- *Analogy:* You call the post office quoting your old ticket. They say "nothing changed, you're up to date" (tiny answer) — versus reading you the whole tracking history again (huge answer).
- *Plugin:* This is the mechanism OkHttp's built-in cache uses. Dead on our Atlassian backends because they never sent us an ETag to quote back.

**`Last-Modified` + `If-Modified-Since`**
- *Plain:* Same idea as ETag but uses a timestamp instead of an opaque tag. "Has this changed since 3:04 PM yesterday?"
- *Analogy:* Instead of quoting a receipt number, you quote the date of your last update.
- *Plugin:* Also absent on our backends. Same verdict.

**Synthetic ETag**
- *Plain:* WE compute our own version tag on the client by running the response body through a hash function (see SHA-256 below). Next time we fetch the same URL, we hash the new response and compare. Same hash = same bytes = we can reuse the already-parsed data.
- *Analogy:* The post office won't give you receipt numbers, so you invent your own: every time you receive a package, you weigh it to the gram and write that down. Next time you pick up, you weigh the new package — same weight to the gram? Probably the same package.
- *Plugin:* Our `CachingInterceptor` will do this for every GET on a cacheable URL. When the hash matches, Prong C skips the UI rebuild.

### 2.2 Caching mechanics

**Cache**
- *Plain:* A store of previously-computed or previously-fetched answers, keyed so you can find them again.
- *Analogy:* Your kitchen pantry of groceries bought last week — it saves you a trip to the store when you need flour.
- *Plugin:* Each of Prong A (HTTP responses), Prong B (resolved repo context), Prong C (last poll hash) is a cache of something.

**Cache hit / cache miss**
- *Plain:* Hit = "yes, we had the answer, here it is." Miss = "nope, have to go get it."
- *Analogy:* Looking for flour in the pantry — hit if you have it, miss if you need to go shopping.
- *Plugin:* Our metrics count both. A high hit rate means the cache is earning its keep.

**Cache key**
- *Plain:* The string used to look up an entry in the cache. Everything that changes the answer must be part of the key.
- *Analogy:* A library's card catalog entry: title + author + edition. If you forget "edition", you might pull the 1995 edition when you wanted the 2020.
- *Plugin:* For a GET request, the key is `method + URL + query-params + auth-hash + Accept-header`. Miss any of these and caches collide.

**TTL** (Time To Live)
- *Plain:* How long we trust a cached entry before we consider it stale and refetch.
- *Analogy:* A "best by" date on yogurt. A day-old yogurt is fine; a month-old one, probably not.
- *Plugin:* A Jira sprint list is fine 5 min old (TTL = 300s). A running build's status must be fresh every poll (TTL = 0s; we hash-dedup instead).

**Fresh / stale**
- *Plain:* Fresh = within TTL, safe to use directly. Stale = past TTL, needs revalidation or refetch.
- *Analogy:* Fresh bread vs. day-old bread. Day-old might still be fine if you toast it — that's what synthetic ETag does: revalidate before trusting.
- *Plugin:* Stale entries don't get deleted immediately — they get rechecked, and if the server returns the same bytes, we reuse the stale copy.

**Eviction**
- *Plain:* Removing an entry from the cache, either because it expired (TTL) or because the cache is full and we need space.
- *Analogy:* Throwing out the oldest leftovers when the fridge is full.
- *Plugin:* Caffeine evicts by weight (total bytes). Our cap is 5 MB across all backends.

**LRU** (Least Recently Used)
- *Plain:* An eviction strategy: when the cache is full, throw out the entry we haven't used in the longest time.
- *Analogy:* Your closet — when full, the shirt you haven't worn in 2 years goes to Goodwill before the one you wore yesterday.
- *Plugin:* Caffeine is LRU-based. A popular endpoint (Jira issue details) stays cached; a one-off query gets evicted first when space tightens.

**Thundering herd**
- *Plain:* What happens when 20 simultaneous callers all cache-miss at the same time and each fires its own identical HTTP request. You stampede the backend instead of sending one request and sharing the answer.
- *Analogy:* A popular book returns to the library; 20 patrons all rush the desk at once instead of forming a single queue.
- *Plugin:* Would happen at startup when all 4 pollers awaken together. Caffeine's `AsyncLoadingCache` fixes this: the 2nd–20th callers get attached to the in-flight request and share its result.

**Memoization**
- *Plain:* Caching the output of a function call based on its inputs. Same inputs → return the stored output, don't recompute.
- *Analogy:* A multiplication table. Instead of calculating 7×8 fresh each time, you memorized the answer 56 and look it up.
- *Plugin:* Prong B. Same active editor file + same VCS mapping + same repo list → same `RepoContext` answer; no recomputation needed until one of those inputs changes.

**Write-through / read-through cache**
- *Plain:* Write-through = updates go to the cache AND the backing store. Read-through = on miss, the cache itself goes to the backing store and fills itself. Jargon you'll see in caching libraries.
- *Analogy:* Write-through = you put groceries in the pantry AND update the kitchen list on the fridge. Read-through = you ask the pantry for flour; if it's not there, the pantry itself sends someone to the store.
- *Plugin:* Prong A is read-through. Callers ask the cache; cache fetches from backend on miss and stores the result before returning.

### 2.3 Libraries and tech we'll use

**Caffeine**
- *Plain:* A popular Java caching library. In-memory, LRU, TTL per entry, handles concurrent access and thundering herd correctly. One JAR, no other dependencies on JDK 17.
- *Analogy:* A smart pantry that knows what's expired, auto-discards the oldest when space runs out, and stops three family members from buying duplicate flour at the same time.
- *Plugin:* The storage engine inside Prong A's `CachingInterceptor`.

**OkHttp**
- *Plain:* The HTTP client library used to make network requests. Widely used (built by Square; used by Android, Retrofit, many others).
- *Analogy:* A courier service. Your app hands it an envelope; it delivers, waits for reply, hands the reply back.
- *Plugin:* Every plugin HTTP call goes through OkHttp, configured in `HttpClientFactory`.

**OkHttp `Cache`**
- *Plain:* OkHttp's built-in disk cache that reads server headers (ETag, Cache-Control) and automatically handles the revalidation dance.
- *Analogy:* A post office receptionist who's been trained to follow "use by" stickers on parcels. Perfect if the sender writes the stickers — useless if they don't.
- *Plugin:* Dead for Atlassian/Sonar JSON (servers don't send headers). Alive for Nexus asset downloads (Nexus DOES send headers). So we'll use it in a separate client for assets only.

**Interceptor** (OkHttp concept)
- *Plain:* A callback function OkHttp invokes on every request before it leaves and every response before it returns. You can log, modify, cancel, or short-circuit any call.
- *Analogy:* Airport security checkpoint. Every passenger (request) passes through; the checkpoint can inspect, add a tag, or reroute them entirely.
- *Plugin:* Our `CachingInterceptor`, the existing `HttpMetricsInterceptor`, `RawApiTraceInterceptor`, `CredentialRedactor`, and `SensitiveEndpointNoCacheInterceptor` are all interceptors stacked in the HTTP pipeline.

**Application interceptor vs. network interceptor**
- *Plain:* Application interceptors see requests/responses as the calling code sees them (before caching, before redirects). Network interceptors see exactly what went over the wire (after caching decisions, after following redirects).
- *Analogy:* Application interceptor = the front desk of a hotel — greets every guest who asks for a room. Network interceptor = the security booth at the parking garage — only sees guests who actually drive in.
- *Plugin:* Our caching policy header injection runs as a network interceptor (so it's seen by the cache). Our mutation-invalidation runs as an application interceptor (so it fires on every caller, even served-from-cache ones).

### 2.4 Crypto / hashing

**Hash function**
- *Plain:* A function that takes any input (a big blob of bytes) and produces a short fixed-length "fingerprint" of it. Same input → same hash; different input → different hash (with astronomically tiny collision chance).
- *Analogy:* Running a document through a sophisticated shredder that makes uniquely-patterned confetti. You can't reconstruct the document from the confetti, but if two shreds match, the documents were identical.
- *Plugin:* We hash each HTTP response body. Same hash next time = same body = skip parsing.

**SHA-256**
- *Plain:* A specific, widely-used hash algorithm. Produces a 256-bit (32-byte) output.
- *Analogy:* One specific brand of shredder, trusted industry-wide.
- *Plugin:* What Prong A will use to compute the body hash. Java's built-in `MessageDigest.getInstance("SHA-256")`; no external dep.

### 2.5 IntelliJ platform primitives

**`CachedValue`** / **`CachedValuesManager`**
- *Plain:* IntelliJ's built-in memoization tool. You give it a computation plus a list of "freshness trackers". Result is stored; reused on the next call; automatically discarded when any tracker signals a change.
- *Analogy:* A spreadsheet cell with a formula. The cell shows the result; if any input cell changes, the result auto-recalculates. You never manually say "invalidate this cell".
- *Plugin:* Exactly fits Prong B. The computation is "resolve current editor's repo"; the trackers are "VCS mappings changed" + "active editor changed". When either bumps, the next call recomputes.

**`ModificationTracker`**
- *Plain:* A tiny object with one method — `getModificationCount(): Long`. Anyone who's watching compares the current count to what they saw last time; if higher, data has changed.
- *Analogy:* An odometer. You don't care about the exact miles driven; you only care whether the number is higher than last time you looked.
- *Plugin:* We'll create a `SimpleModificationTracker`, bump its count from a `VcsRepositoryMappingListener`. Our `CachedValue` watches that tracker.

**`@Service(Level.PROJECT)`**
- *Plain:* An annotation that declares a class as a "project-scoped service" — the IDE instantiates one per open project, disposes it when the project closes. Retrievable anywhere via `project.service<MyService>()`.
- *Analogy:* A hotel room safe. Each hotel room (project) gets its own; empties when checkout happens.
- *Plugin:* Prong B's `RepoContextCache` will be a project service. Prong A's global `HttpCache` will be an application-scoped service (one per IDE, not per project).

**EDT** (Event Dispatch Thread)
- *Plain:* The single thread Swing UIs use to paint and handle user input. Blocking it = frozen UI.
- *Analogy:* The one cashier at a shop. If they spend 5 seconds on a single customer's receipt, everyone behind waits 5 seconds.
- *Plugin:* Every `JBTable` repaint, every tool-window refresh happens on EDT. The whole point of caching (especially Prong C) is to spend less time on EDT.

**Read action / Write action**
- *Plain:* IntelliJ's locking model for accessing the code model (PSI). A read action can happen from any thread but must not run while a write is in progress. A write action must run on EDT.
- *Analogy:* A library's rare-manuscripts room. Many people can read simultaneously; only one person can edit, and only during a scheduled editing slot (EDT).
- *Plugin:* Mostly relevant to Phase 4 (PSI batching). For Phase 3, the main interaction is that `CachedValue` providers that touch PSI need a read action; our providers don't touch PSI, so we're fine.

**Polling**
- *Plain:* Asking "anything new?" on a regular schedule. Alternative to the server pushing updates (which isn't offered by any of our backends).
- *Analogy:* Checking the mailbox every hour instead of having the mail carrier ring your doorbell.
- *Plugin:* `SmartPoller` drives 4 repeating HTTP calls (builds, PRs, automations, insights). Prong C makes those polls cheaper when nothing changed.

**Dedup (deduplicate)**
- *Plain:* Recognizing that two things are identical and only processing one.
- *Analogy:* A mail sorter noticing that today's newspaper is identical to yesterday's (misprint); they toss the dupe instead of redelivering it.
- *Plugin:* Prong C dedups poll results by body hash: if this tick's response is identical to the last tick's, skip the downstream UI rebuild.

---

## 3. Research inputs (four files)

The four linked files are the raw research; this doc is the synthesis.

1. `phase3-research/01-codebase-audit.md` — current state of `HttpClientFactory`, `SmartPoller`, `RepoContextResolver`, existing caches, endpoint inventory.
2. `phase3-research/02-okhttp-caching.md` — OkHttp `Cache`, `CacheControl`, interceptor patterns, Caffeine. Kotlin code snippets.
3. `phase3-research/03-intellij-caching.md` — `CachedValue`, `ModificationTracker`, `coalesceBy`, `@Service` idioms, JetBrains plugin survey.
4. `phase3-research/04-backend-cache-headers.md` — the critical file. Per-backend matrix showing ETag/Last-Modified/Cache-Control support. The finding that reshapes the strategy.

### Three quotations from the research that drive the design

> **"Atlassian declined JRASERVER-36374 (Last-Modified) as 'Not a Bug' in the 5.x era, never fixed since. JRASERVER-65373 (ETag) open since 2017, status 'Gathering Interest.' Responses ship `Cache-Control: no-cache, no-store, no-transform`."** (agent 4)

> **"`coalesceBy` is the platform's dedup primitive but has cancel-loser semantics — wrong for polling, where we want share-result."** (agent 3)

> **"OkHttpClient's 10 MB shared cache is enabled but only works via ETag/304 … JiraApiClient, BambooApiClient, SonarApiClient rebuild shared pool + interceptors instead of using `HttpClientFactory.clientFor()`. Phase 2 cleanup may be incomplete."** (agent 1)

The third quote is an unexpected pre-requisite we didn't plan for: before the caching interceptor can be installed once and cover everything, we have to confirm every HTTP call actually routes through `HttpClientFactory.clientFor()`. Phase 2 got Jira there; the remaining backends need verification.

---

## 4. The three prongs, in detail

### Prong A — HTTP response caching (replaces OkHttp `Cache` plan)

**Goal:** when the server sends back the same bytes we already saw, avoid re-parsing JSON and avoid triggering downstream UI repaints. Save Jackson time and EDT reflow time.

**Why not OkHttp's built-in `Cache`?** Because the Atlassian/SonarSource servers emit `Cache-Control: no-cache, no-store` on every REST JSON response. OkHttp respects that (as it must per RFC 9111) and stores nothing. Zero hit rate. Confirmed in agent 4's research against JRASERVER-36374/65373, Bitbucket's `CachePolicies` utility, and Sonar's Spring Security default headers.

**Design — one interceptor, one in-memory cache:**

```
CachingInterceptor (application-level)
  ├── keyOf(request) = sha256(method + URL + query + auth-hash + Accept)
  ├── store:  Caffeine<String, CacheEntry>
  │             maxSize = 500 entries (per-backend split below)
  │             expireAfterWrite = URL-pattern-dependent (policy registry)
  ├── on request:
  │     if fresh hit:  return cached Response (no network call)
  │     if stale hit:  fetch, compare sha256; if match, reuse cached Response (no parse)
  │     if miss:       fetch, store, return
  ├── on mutation (POST/PUT/PATCH/DELETE, 2xx): invalidate entries whose URL starts with <base>
  └── sensitive endpoints: skip entirely (existing SensitiveEndpointNoCacheInterceptor stays)

CacheEntry:
  - bodyBytes: ByteArray           // the raw response body
  - sha256: ByteArray              // for stale-match detection
  - parsedAtMillis: Long
  - ttlExpiresAtMillis: Long
  - contentType: String
  - statusCode: Int                // only cache 200 and 304
```

**URL-pattern TTL policy** (starting values; tunable after baseline):

| Pattern | TTL | Rationale |
|---|---|---|
| `GET /rest/api/2/issue/\w+` | 60s | Ticket details change on transitions; 60s keeps same-session changes visible quickly |
| `GET /rest/api/2/search` | 10s | User-driven; short because list order matters |
| `GET /rest/agile/1.0/board` | 300s | Board definitions change rarely |
| `GET /rest/agile/1.0/sprint/\d+` | 120s | Sprint metadata semi-stable |
| `GET /rest/api/2/user/assignable/search` | 3600s | User list changes very rarely |
| `GET bamboo/rest/api/latest/result/[A-Z0-9-]+/\d+$` (finished build) | 86400s | Finished builds are immutable |
| `GET bamboo/rest/api/latest/result/[A-Z0-9-]+$` (plan, includes running) | 0s + hash | Always revalidate; hash-match skips UI |
| `GET bitbucket/rest/api/1.0/.../pull-requests` | 30s | PR list changes frequently |
| `GET bitbucket/rest/api/1.0/.../pull-requests/\d+/activities` | 60s | Biggest polling payload per research |
| `GET sonar/api/qualitygates/project_status` | 300s | |
| `GET sonar/api/ce/task` | **NEVER CACHE** | Progress polling must stay fresh |
| `GET sourcegraph/.api/client-config` | 600s | Effectively static per instance |
| `GET sourcegraph/.api/completions/stream` | **NEVER CACHE** | SSE stream |
| `GET nexus/service/rest/v1/search` | 120s | Search results stable short-term |

**Sensitive paths (never cache, already enforced):** `/rest/api/2/myself`, `/rest/auth`, `/_api/graphql`, `/api/user`, plus all writes.

**Cache sizing:** 500 entries total across all backends, ~5 MB memory cap via weight-based eviction (Caffeine `maximumWeight` with `Weigher` = `bodyBytes.size`). Spread: budget 2 MB Jira, 1 MB Bamboo, 1 MB Bitbucket, 0.5 MB Sonar, 0.5 MB others. Single shared instance keyed by `ServiceType` prefix in the key.

**Nexus asset downloads** (different shape — the ONE place native HTTP caching works): separate `OkHttpClient` with OkHttp `Cache` enabled, ~50 MB disk at `~/.workflow-orchestrator/{proj}/http-cache/nexus-assets/`. Nexus mirrors upstream `ETag` + `Last-Modified` and honors conditional GETs. This is additive and independent of the main interceptor.

**Metrics emitted (must land in commit 1, before any policy):**
- `http.cache.hit.fresh` — served from cache without revalidation
- `http.cache.hit.stale_match` — refetched, hash matched, reused parse
- `http.cache.hit.stale_differ` — refetched, hash differed, reparsed
- `http.cache.miss` — not in cache
- `http.cache.evictions` — by size / by TTL
- `http.cache.bytes` — total memory footprint

Exposed via simple counters read from the tool window debug panel (no new UI surface — the Agent has a debug tab we can piggyback on). Without these, the next sessions can't tell if the phase is working.

**What this replaces:** the three ad-hoc Jira caches surfaced by agent 1 (`TicketKeyCache`, `IssueDetailCache`, `SprintPaginationCache`) should migrate into this general mechanism. They each invented their own eviction, hash, and TTL semantics. Consolidating removes ~250 LOC of bespoke cache code and gives them the same observability as everything else. Scheduled as commits 6–7 in the plan below.

---

### Prong B — RepoContextResolver memoization

**Goal:** `RepoContextResolver.resolveCurrentEditorRepoOrPrimary()` is called ~16× per session per agent 1's audit, and does O(n) scan over configured repos + active editor file path parsing every time. None of those inputs change between most calls. Memoize.

**Design:**

```kotlin
@Service(Service.Level.PROJECT)
class RepoContextCache(private val project: Project) {

    // Bumped by VcsRepositoryMappingListener + PluginSettings listener
    private val vcsTracker = SimpleModificationTracker()

    fun resolve(): RepoContext {
        return CachedValuesManager.getManager(project).getCachedValue(project) {
            val ctx = doResolve()  // the original computation
            CachedValueProvider.Result.create(
                ctx,
                vcsTracker,
                FileEditorManagerEx.getInstanceEx(project),  // active file changes
                PsiModificationTracker.MODIFICATION_COUNT    // redundant safety net
            )
        }
    }
}
```

**Why `CachedValuesManager` and not Caffeine here?** Because IntelliJ knows when the underlying state changes — VCS mapping, editor selection — and handles invalidation for us via tracker bumps. Caffeine would need us to listen to those events manually and call `invalidate()`. Less code, fewer invalidation bugs.

**Invalidation triggers:**
1. `VcsRepositoryMappingListener` fires when VCS mappings change → `vcsTracker.incModificationCount()`
2. User switches branch → already covered by `VcsRepositoryMappingListener` in recent IJ versions
3. User edits the repos list in PluginSettings → dedicated listener bumps `vcsTracker`
4. Editor selection change → `FileEditorManagerEx` is already a `ModificationTracker` in modern platform

**Zero eviction policy needed** — platform GCs soft-referenced CachedValues automatically under memory pressure and on project close.

**Risk:** the `BranchChanged` event surfaced by agent 1 (CLAUDE.md mentions `EventBus` for cross-module events) must be added to the tracker bump set if not already covered by `VcsRepositoryMappingListener`. Verify in implementation.

---

### Prong C — SmartPoller result dedup

**Goal:** every time `SmartPoller` ticks, it fires an HTTP call and rebuilds the UI. If the response is byte-identical to the previous response, the UI rebuild is wasted work (JBTable model swap, all cell renderers repaint, any expanded rows collapse). Agent 1 found 4 active pollers; agent 3 confirmed this is the largest EDT win available.

**Design:**

```kotlin
class SmartPoller<T>(...) {
    private var lastHashPerKey: ConcurrentHashMap<String, ByteArray> = ConcurrentHashMap()

    private suspend fun tick() {
        val result = fetcher()
        val hash = sha256(result.rawBytes)  // piggy-back on Prong A interceptor's hash
        val prev = lastHashPerKey[key]
        if (prev != null && prev.contentEquals(hash)) {
            metrics.increment("poller.skipped_ui_updates")
            return  // skip onResult; poll continues normally
        }
        lastHashPerKey[key] = hash
        onResult(result)
    }
}
```

**Critical:** Prong A's `CachingInterceptor` computes the body SHA-256 already for stale-match detection. Expose it via `Response.tag(Sha256Tag::class.java)` so the poller reads it without recomputing.

**Why not `coalesceBy`?** Agent 3's research: `ReadAction.nonBlocking().coalesceBy()` cancels the previous computation when a new one with the same key starts. That's *exactly wrong* for polling — we want share-result semantics (both callers get the answer), not cancel-loser (one gets cancelled). If we need concurrent request coalescing somewhere (e.g., two UI panels both requesting the same endpoint at the same time), use Caffeine's `AsyncLoadingCache.get()` instead — that coalesces share-result, which is built into Prong A.

**Risk:** pollers that present data with volatile formatting (timestamps embedded in the body with second-resolution) will have hashes that differ every tick even when the meaningful data is unchanged. Mitigation: per-poller "semantic key" extractor as fallback — e.g., BuildMonitor could hash `"${buildKey}:${state}:${completedTime}"` instead of the full body. Start with body hash; add semantic extractors only if measurement shows low dedup rate on a specific poller.

---

## 5. Commit plan

Same discipline as Phases 1/2: one concern per commit, `./gradlew verifyPlugin buildPlugin` + module tests green at each step.

**Pre-work (mandatory before any commit below):**
- **P0.** Capture baseline in `docs/architecture/phase3-baseline.md`. Per the memory gate. This is a manual IDE step by the user. Cannot be delegated.
- **P1.** Audit: confirm every `OkHttpClient` use goes through `HttpClientFactory.clientFor(ServiceType)`. Agent 1 flagged `JiraApiClient`, `BambooApiClient`, `SonarApiClient` as possibly constructing their own. Read each, verify. Fix any escapees as a Phase 2 tail commit (not Phase 3).

**Phase 3 proper:**
1. `feat(core): add HttpCacheMetrics + counters` — scaffolding only, no behavior change, so we can see the rest.
2. `feat(core): add CachingInterceptor skeleton (pass-through, counters only)` — installed via `HttpClientFactory`, every request counted as miss. Confirms routing, emits baseline hit-rate of 0%.
3. `feat(core): add UrlPatternCachePolicy registry` — data-only, no consumers yet. Tests for pattern matching.
4. `feat(core): CachingInterceptor honors URL policy + in-memory Caffeine store` — activation commit. Expected immediate hit rate on stable endpoints; verify metrics move.
5. `feat(core): add MutationInvalidationInterceptor` — fixes stale cache after user actions (ticket transitions, PR creates).
6. `refactor(jira): migrate TicketKeyCache into CachingInterceptor` — remove bespoke cache; verify behavior equivalent via existing tests.
7. `refactor(jira): migrate IssueDetailCache + SprintPaginationCache` — same pattern.
8. `feat(core): RepoContextResolver memoization via CachedValuesManager` — Prong B, independent of A.
9. `feat(core): SmartPoller result dedup using response SHA-256 tag` — Prong C, depends on A for the tag.
10. `feat(automation): Nexus asset OkHttpClient with native Cache` — optional, only if a baseline measurement shows Nexus traffic is a meaningful fraction.
11. `feat(settings): HTTP cache config UI + purge button` — exposes max size, TTL profile override, clear-cache action.
12. `docs(architecture): Phase 3 outcomes + measurement results` — commit J equivalent; update `index.html`, `module-structure.md`, `phase3-baseline.md`, this file's status line.

Expected commit count: 10–12. Expected net LOC: roughly neutral (+500 new caching code, −250 bespoke Jira cache code, −~150 SmartPoller churn in call sites, docs). Phase 1 was −1050, Phase 2 was +518, Phase 3 likely +300 ± 200.

---

## 6. Risk register

| Risk | Probability | Impact | Mitigation |
|---|---|---|---|
| Bodies contain timestamps → hash never matches → dedup useless | Medium | Medium | Per-poller semantic key fallback; measurement in commit 1 reveals which pollers need it |
| User sees stale ticket after their own transition | High without mitigation | Low severity, high annoyance | `MutationInvalidationInterceptor` (commit 5) |
| Cache grows unbounded on a long session | Low | Medium | Caffeine `maximumWeight` hard cap; metrics visible; purge button |
| Two API clients still bypass `HttpClientFactory` → half the cache surface covered | Medium | High (invisible coverage gap) | P1 audit before commit 1; fix in Phase 2 tail |
| CachedValue invalidation signal missing for Prong B | Low | Medium | `vcsTracker.incModificationCount()` from both `VcsRepositoryMappingListener` and any plugin `BranchChanged` event; belt-and-braces |
| Caffeine adds transitive deps that don't play well with plugin classpath | Low | High | Caffeine is a single ~1 MB JAR with no transitives on JDK 17; verified in agent 2 research |
| Measurement reveals Phase 3 has no user-visible improvement | Medium | Low | The three prongs are independent; Prong B alone should visibly improve tool window open latency. Worst case we keep what measurably helps and revert the rest. |
| Disk cache dir collides with existing agent storage | Low | Low | Separate path: `~/.workflow-orchestrator/{proj}/http-cache/` (sibling to `agent/`) |
| Phase 4 `runBlocking` sites deferred from Phase 1 interact with cache | Low | Medium | Caching is additive and synchronous; no interaction. Document that Phase 4 cleanup should coexist. |

---

## 7. Settings surface (new PluginSettings fields)

```kotlin
// In PluginSettings.kt (or dedicated CacheSettings)
var httpCacheEnabled: Boolean = true
var httpCacheMaxEntriesTotal: Int = 500
var httpCacheMaxMegabytes: Int = 5
var httpCacheNexusAssetsMaxMegabytes: Int = 50
var httpCacheTtlOverrides: Map<String, Long> = emptyMap()  // URL-regex → TTL-seconds
var repoContextMemoEnabled: Boolean = true
var smartPollerDedupEnabled: Boolean = true
```

UI exposed in existing "Tools > Workflow Orchestrator > Advanced" configurable under a new "Performance & Caching" section. Purge button calls `HttpCache.clear()` synchronously and logs.

All three feature-flag booleans default `true`; per `feedback_reuse_code.md` we normally avoid dual-path flags, but here they're kill-switches for the measurement phase — once we've validated with metrics that each prong is net-positive, the booleans are deleted in commit 12 (docs/cleanup).

---

## 8. Measurement policy (revised 2026-04-25)

Original plan required a formal `phase3-baseline.md` capture before any commit. User chose the targeted-measurement alternative (decision logged in `project_branch_refactor_cleanup_perf_caching.md`).

**What doesn't need baselines:**
- **Prong B** — memoizing a pure function with stable inputs is correct independent of measurement; `CachedValuesManager` has zero downside when inputs don't change.
- **Prong A mechanical commits** — scaffold, interceptor skeleton, URL policy registry, Caffeine activation, mutation invalidation, Jira cache consolidation. Each is a refactor or new infrastructure that either compiles + tests green or doesn't. Hit-rate counters emit from commit 1, so Prong A's own activation commit is self-comparative (0% before commit 4 → measured N% after).

**What does need targeted measurement:**
- **Prong C (SmartPoller dedup)** — whole hypothesis is "N% of polls produce byte-identical responses; skipping UI rebuild saves EDT time." Before the Prong C commit: run one tool-window refresh cycle under `-Dworkflow.debug.timing=true`, note EDT paint time, record in the commit message. After: re-run the same cycle, record. No separate baseline document.

**What we give up:**
- Ability to make claims like "Phase 3 reduced startup time by X%" in a release note. Targeted measurement supports per-commit claims but not aggregate claims.
- A clean reference point if a user later reports "tool window feels slower after Phase 3." Mitigated by the fact that Prong A's counters provide continuous observability post-deployment.

**Phase 4 measurement is unaffected.** Profile-driven perf work (EDT hotspots, coroutine scope tightening, `runBlocking` removal) still requires its own baseline capture — that's Phase 4's gate, not Phase 3's.

---

## 9. Decisions (resolved 2026-04-24)

User green-lit recommendations.

- **D1. RESOLVED:** `HttpCacheMetrics` hidden behind `PluginSettings.showCacheMetrics` (default `false`). Surface in Phase 4 debug surface if useful. Counters are always recorded; only the UI display is gated.
- **D2. RESOLVED:** Nexus disk cache deferred. Commit 10 skipped on first pass. Revisited only if baseline measurement shows Nexus traffic > 5% of total session bytes.
- **D3. RESOLVED:** Feature-flag booleans (`httpCacheEnabled`, `repoContextMemoEnabled`, `smartPollerDedupEnabled`) deleted in commit 12, consistent with `feedback_reuse_code.md`. Kill-switch behavior during the measurement window only.
- **D4. RESOLVED:** SmartPoller semantic-key extractors built reactively. Commit 9 ships body-hash dedup only. Per-poller extractors added only if measurement shows specific poller has near-zero dedup rate.
- **D5. NO ACTION:** `attempt_completion redesign` is worktree-scoped and unaffected by Phase 3. Phase 3 is HTTP-layer only.

---

## 11. Outcomes — 2026-04-25

Phase 3 complete. 13 commits landed on `refactor/cleanup-perf-caching` since the previous summary in `project_branch_refactor_cleanup_perf_caching.md`.

### Phase 2 tail (routing consolidation — 5 commits, −28 LOC)

| Commit | Scope |
|---|---|
| `e8b0c9d3` | JiraApiClient + JiraTaskRepository via `clientFor(ServiceType.JIRA)` |
| `832b154c` | BambooApiClient via `clientFor(ServiceType.BAMBOO)` |
| `b97b8673` | Pre-existing sonar test rot fix (CoverageThresholdsTest StatusColors refs) |
| `c7004ada` | SonarApiClient via `clientFor(ServiceType.SONARQUBE)` |
| `db6b5013` | BitbucketBranchClient via `clientFor(ServiceType.BITBUCKET)` |

Three intentional exceptions documented as memory (`SourcegraphChatClient`, `DockerRegistryClient`, `AuthTestService`) — each has protocol-level reasons for remaining on `sharedPool.newBuilder()`.

### Prong B — RepoContextResolver memoization (1 commit)

| Commit | Scope |
|---|---|
| `b50e864d` | `CachedValuesManager` + `SimpleModificationTracker` gated on VCS mapping and editor selection; invalidated by `RepositoriesConfigurable.apply()` |

### Prong A — HTTP response caching (7 commits, +1290 LOC incl. tests)

| Commit | Scope |
|---|---|
| `52c698df` | HttpCacheMetrics counters (HIT_FRESH/HIT_STALE_MATCH/HIT_STALE_DIFFER/MISS + evictions + mutation invalidations) |
| `9c38d952` | CachingInterceptor pass-through (counts only, no caching) |
| `1537f3f8` | CachePolicyRegistry — URL-pattern TTL table per ServiceType |
| `f2a01041` | Caffeine 3.2.3 dep + HttpResponseCache store (5 MB cap, weight-based eviction) + CacheKey |
| `ca38f180` | Activate: fresh-hit + miss + store |
| `fce620e3` | Synthetic-ETag stale-match detection (SHA-256 body hash) |
| `15106a76` | MutationInvalidationInterceptor — evicts cached GETs after 2xx POST/PUT/PATCH/DELETE |

### Prong C — analysis only (no code)

After implementation of Prong A, Prong C was revisited. See `phase3-research/P2-prong-c-analysis.md`:

- 4 of 5 SmartPoller consumers publish to `MutableStateFlow`, which has built-in `Object.equals`-based dedup.
- Byte-identical poll results already produce zero UI work under StateFlow semantics.
- Only `CommentsTabPanel` operates outside StateFlow; deferred to Phase 4 profile-driven evaluation.
- **No SmartPoller-layer dedup mechanism added.** The hypothesis was redundant with framework primitives.

### Totals

- **13 commits**
- **~1400 LOC added net** (Prong A infrastructure + tests), partially offset by Phase 2 tail cleanup
- **~70 new unit tests** across HttpCacheMetrics, CachePolicyRegistry, HttpResponseCache, CachingInterceptor, MutationInvalidationInterceptor
- **1 new runtime dependency**: Caffeine 3.2.3 (~1 MB JAR)
- **`./gradlew verifyPlugin buildPlugin` green** on IU-251 / IU-252 / IU-253

### Measurement policy honoured

Per §8 (revised 2026-04-25), no formal `phase3-baseline.md` was captured. Each Prong A activation commit landed on top of a self-comparative baseline (hit rate was 0% between commits 2 and 4, became measured N% at commit 4). `HttpCacheMetrics` counters continue to emit in production so post-deployment hit-rate observability is continuous.

### Gate for Phase 4

When Phase 4 (perf boost — EDT hotspots, coroutine tightening, `runBlocking` removal) begins, a formal baseline document will be needed — see the deferred `runBlocking` list in the branch memory. Phase 3's measurement policy does not affect that gate.

---

## 10. What Phase 3 explicitly does NOT do

- **No** EDT fixes (Phase 4).
- **No** `runBlocking` removal (Phase 4 — list already in memory).
- **No** PSI read-action batching (Phase 4).
- **No** Font derivation optimization (Phase 4, per `intellij-plugin-performance` skill).
- **No** disk cache for Atlassian/SonarSource JSON (they don't let us; Nexus assets only).
- **No** changes to `:agent` module internals beyond possibly reading from the same `HttpClientFactory` if any agent tool happens to make backend HTTP (confirmed none do today per agent 1).
- **No** removal of `RawApiTraceInterceptor` or `HttpMetricsInterceptor` — they stack with the caching interceptor and stay.
