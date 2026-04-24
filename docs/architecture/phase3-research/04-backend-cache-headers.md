# Phase 3 Research — Backend HTTP Cache Header Support

Scope: decide whether Phase 3 of the `refactor/cleanup-perf-caching` branch can rely on OkHttp's built-in [`Cache`](https://square.github.io/okhttp/features/caching/) (driven by server-emitted `ETag` / `Last-Modified` / `Cache-Control`) or whether the plugin must implement a **synthetic-ETag** interceptor (body-hash → 304 emulation in-client).

Backends: Jira DC, Bamboo DC, Bitbucket DC, SonarQube (Community/Dev/Enterprise), Sourcegraph Cody Enterprise, Sonatype Nexus 3.

TL;DR: **None** of the six backends emit reliable HTTP cache validators on the JSON REST endpoints this plugin calls. All six require synthetic-ETag (body-hash) caching if we want conditional revalidation. OkHttp's built-in disk `Cache` is essentially useless for these APIs because (a) every request carries `Authorization:` (RFC 9111 §3.5 forbids shared caching unless server opts in with `public` / `s-maxage` / `must-revalidate`, which none do) and (b) Atlassian servers actively emit `Cache-Control: no-cache, no-store, no-transform` on REST responses.

---

## RFC 7234 / RFC 9111 baseline

- A response is cacheable only if the server sends enough metadata to compute freshness (explicit `max-age` / `Expires`, or heuristic via `Last-Modified`) or a validator (`ETag` / `Last-Modified`).
- **Authorization + caching:** RFC 9111 §3.5 — a shared cache MUST NOT reuse a response to an `Authorization`-bearing request *unless* the response has `public`, `s-maxage`, or `must-revalidate`. OkHttp's `Cache` is a **private** (single-user) cache so that rule is technically relaxed, but OkHttp still refuses to store responses marked `no-store` and will revalidate on every hit when `no-cache` is set. ([RFC 9111](https://www.rfc-editor.org/rfc/rfc9111.html))
- A correctly-behaved 304 response MUST echo `Cache-Control`, `Date`, `ETag`, `Expires`, and `Vary` per RFC 9111 §4.1.

Takeaway: for *any* backend that returns `Cache-Control: no-store` or omits validators entirely, OkHttp's built-in cache does nothing useful.

---

## A. Jira Data Center

Endpoints in use: `/rest/api/2/issue/{key}`, `/rest/api/2/search`, `/rest/agile/1.0/board`, `/rest/agile/1.0/sprint`, `/rest/api/2/issue/{key}/transitions`, `/rest/api/2/user/assignable/search`, `/rest/api/2/attachment/{id}`.

| Question | Answer |
|---|---|
| `ETag` on GET? | **No, not on the JSON REST endpoints we call.** ETags exist on a handful of write paths (e.g. ApplicationRoles, dashboard config) for optimistic-concurrency PUTs — with missing `If-Match` they return HTTP 412 — but not on `/issue`, `/search`, or `/agile/1.0/*`. |
| `Last-Modified`? | **No.** [JRASERVER-36374](https://jira.atlassian.com/browse/JRASERVER-36374) asked Atlassian to emit `Last-Modified` on `/rest/api/2/search`; closed as **Not a bug** (5.2.11 / 6.1.5 era, never fixed). Still missing in Jira DC 10.x / 11.x. |
| `Cache-Control`? | **Yes — `Cache-Control: no-cache, no-store, no-transform`** on REST responses (confirmed in Atlassian community threads for both Server/DC and Cloud; applied at the Struts/Seraph filter layer). Static resources (/s/, images/js) got a *separate* `public` directive fix in [JRASERVER-17373](https://jira.atlassian.com/browse/JRASERVER-17373) (3.13.5 / 4.0), but that fix is scoped to static assets, not `/rest/*`. |
| Honors `If-None-Match` / `If-Modified-Since` → 304? | **No on JSON REST.** ETag-enabled admin endpoints respond with `412 Precondition Failed` on `If-Match` mismatches (write-path only), never `304`. [JRASERVER-65373](https://jira.atlassian.com/browse/JRASERVER-65373) is still open, "Gathering Interest" since 2017. |
| Rate limits | DC rate-limiting is token-bucket, returns `429 Too Many Requests` with headers `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Interval-Seconds`, `X-RateLimit-FillRate`, and `retry-after`. Caching directly mitigates this — every avoided poll is a saved token. |
| Known issues | Sprint cache warm-up in the DB layer causes 20s–minutes stalls on `/rest/agile/1.0/*` when the sprint cache flushes ([JSWSERVER-20618](https://jira.atlassian.com/browse/JSWSERVER-20618)). Any 304-style revalidation has to be client-synthesised. Many DC deployments sit behind nginx/F5 which strip strong ETags when gzip is on (fixed only in nginx ≥ 1.7.3, and *still* strips on some gzip configs — [nginx ticket #101](https://trac.nginx.org/nginx/ticket/101), [ingress-nginx #1390](https://github.com/kubernetes/ingress-nginx/issues/1390)). |

**Verdict: synthetic ETag via body hash is required.** The existing `no-store` means even if proxies did forward an ETag (they don't), OkHttp's `Cache` would refuse to store. Client-side hash-of-body → `If-None-Match` is not possible either because the server doesn't understand it; we must build **in-client-only** revalidation: hash the response body, cache the parsed model under a TTL, and compare hashes on the next scheduled poll to skip parse+UI-update work (no network savings unless we pair with `retry-after`/etag-faking).

---

## B. Bamboo Data Center

Endpoints: `/rest/api/latest/result/{key}`, `/rest/api/latest/result/{plan}?includeAllStates=true`, `/rest/api/latest/plan`, plan/job logs, artifact downloads.

| Question | Answer |
|---|---|
| `ETag` on GET? | **Not documented. Not observed in public Bamboo REST docs (5.x–10.x).** Bamboo REST is Struts/Spring-backed like Jira; same "no cache validators on REST JSON" posture. |
| `Last-Modified`? | **Not on the JSON REST responses.** May appear on static artifact downloads (see below) depending on the underlying servlet. |
| `Cache-Control`? | Same Atlassian family default: `no-cache, no-store, no-transform` on REST JSON. No documented opt-in. |
| 304 support? | **Not for REST JSON.** |
| Rate limits | DC rate-limiting matches Jira's pattern (token bucket, `retry-after`, same X-RateLimit-\* family). Documented in [Bamboo DC 12.1 "Adjusting your code for rate limiting"](https://confluence.atlassian.com/spaces/BAMBOO/pages/1115684477/Adjusting+your+code+for+rate+limiting). |
| Artifact / log downloads | Served as static files via Tomcat default servlet — Tomcat's `DefaultServlet` *does* emit `ETag` (weak, based on mtime+size) and `Last-Modified` and honors `If-Modified-Since` / `If-None-Match`. **But:** artifact URLs are frequently served through `/download/` or `/browse/PLAN-JOBSHORTKEY-BUILDNUM/` paths that pass through the Struts filter chain first, which may override Tomcat's cache headers. Range requests work on artifact downloads. |
| Known issues | Job-level logs (29 KB+ per build) are the hot polling path per `reference_bamboo_api_probe_findings` — JSON endpoint, not cacheable by headers. |

**Verdict: synthetic ETag required for all REST JSON paths.** Artifact/log *static* downloads might be cacheable with OkHttp's built-in cache, but only once we confirm the Struts filter doesn't clobber headers — treat as opt-in per-URL, not default.

---

## C. Bitbucket Data Center

Endpoints: `/rest/api/1.0/projects/{key}/repos/{slug}/pull-requests`, `/rest/api/1.0/.../pull-requests/{id}/activities`, `/rest/api/1.0/.../pull-requests/{id}/comments`, `/rest/branch-utils/.../branches`.

| Question | Answer |
|---|---|
| `ETag` on GET? | **No.** The Bitbucket Server REST API documentation contains a `CachePolicies` utility class ([javadoc](https://docs.atlassian.com/bitbucket-server/javadoc/4.6.0/rest-api/reference/com/atlassian/bitbucket/rest/util/CachePolicies.html)) that *defines* `no-cache` / `no-store` / private directives, strongly implying REST responses go out with those defaults. No documented `ETag` on `/pull-requests`, `/activities`, or `/comments`. |
| `Last-Modified`? | **No** on REST JSON. Possibly yes on `/raw/` file endpoints (served by the SCM cache plugin), but not documented. |
| `Cache-Control`? | Same Atlassian default — `no-cache, no-store`. |
| 304 support? | **No** for REST JSON. |
| Rate limits | Bitbucket DC has identical token-bucket rate limiting to Jira/Bamboo — 429 response, `retry-after` header. Jenkins ran into this enough to file [JENKINS-66539](https://issues.jenkins.io/browse/JENKINS-66539). |
| Known issues | `/pull-requests/{id}/activities` is the classic long-polling endpoint and returns large paginated JSON — single biggest payload in the plugin's PR dashboard polling path. No header-based caching → synthetic-ETag pays for itself here quickly. |

**Verdict: synthetic ETag required.**

---

## D. SonarQube (Community / Developer / Enterprise)

Endpoints: `/api/issues/search`, `/api/measures/component`, `/api/ce/task`, `/api/qualitygates/project_status`, `/api/sources/raw`.

| Question | Answer |
|---|---|
| `ETag` on GET? | **Not documented.** SonarQube's [Web API docs](https://docs.sonarsource.com/sonarqube-server/extension-guide/web-api) describe endpoints in terms of JSON schema, deprecations, and auth — not HTTP-level cache behavior. No mention of `ETag` in `web_api.properties`-style config. Internally Sonar uses its own HTTP stack + Elasticsearch; no Spring `ShallowEtagHeaderFilter` wired in by default. |
| `Last-Modified`? | **Not observed.** |
| `Cache-Control`? | Responses are typically served with `Cache-Control: no-cache, no-store, must-revalidate` or similar (Spring Security default when authenticated). Not explicitly documented. |
| 304 support? | **No.** |
| CE vs Dev/Enterprise | No caching differences between editions — the HTTP stack is identical, only feature sets differ (branches, PR decoration, new-code period are Developer+). |
| Rate limits | SonarQube **Cloud** rate-limits (bare 429 without detailed documentation). SonarQube **Server / Community Build** does not advertise a request rate limit; pressure shows up as DB/Elasticsearch slowness, not 429. |
| Known issues | `/api/measures/search_history` and `/api/projects/search` are documented slow endpoints — classic candidates for client-side result caching. `/api/issues/search` is the one we poll; synthetic ETag (+ short TTL) is the right tool. `/api/ce/task` is *by design* short-lived polling (task progress) — don't cache. |

**Verdict: synthetic ETag required.** Exclude `/api/ce/task` and any streaming progress endpoint.

---

## E. Sourcegraph Cody Enterprise

Endpoints: `/.api/completions/stream`, `/.api/client-config`, `/.api/llm/models`.
Auth: non-standard `Authorization: token <token>` (not `Bearer`, though Bearer is accepted on the newer `/.api/llm/*` paths).

| Endpoint | ETag | Last-Modified | Cache-Control | Cacheable? |
|---|---|---|---|---|
| `/.api/completions/stream` | N/A | N/A | N/A | **Never cache.** SSE / chunked stream response. |
| `/.api/client-config` | Not documented | Not documented | Not documented, but this is effectively a static config snapshot per-instance | **Yes, client-side TTL** (e.g. 5-10 min). Synthetic ETag fine. Cody JetBrains client currently refetches on every session start. |
| `/.api/llm/models` | Not documented | Not documented | Not documented | **Yes, client-side TTL.** Model catalog changes on admin config push, rarely per-user session. |

Rate limits: Cody Gateway enforces per-license concurrency and per-feature quotas (chat vs completions separately). Unsuccessful requests are not counted; successful requests all count. No documented per-endpoint rate-limit HTTP header surface on the Cody Enterprise API itself (it's proxied via Cody Gateway which has its own 429 / `retry-after` semantics).

**Verdict: streaming never cacheable. For `client-config` and `llm/models`, synthetic ETag + TTL required** (server sends no validators we can trust).

---

## F. Sonatype Nexus Repository Manager 3

Endpoints: `/service/rest/v1/search`, asset download URLs under `/repository/{repo}/{path}`.

| Question | Answer |
|---|---|
| `ETag` on GET? | **Search API: not documented, not observed.** Asset download: **Yes on proxied repos** — Nexus stores upstream `ETag` and `Last-Modified` per asset (it uses them for its own *outbound* conditional GET to upstream registries) and **re-serves them** to clients for hosted + proxy + group raw/maven/npm assets. Behavior confirmed in Sonatype docs and community posts on npm proxy behavior. |
| `Last-Modified`? | **Search API: no. Asset download: yes** (mirrored from upstream or synthesized from asset's `lastUpdated` timestamp). |
| `Cache-Control`? | Not documented on `/service/rest/v1/search`. Asset downloads typically go out with `Cache-Control: max-age=0, must-revalidate` or similar from the Jetty layer. |
| 304 support? | **Search API: no. Asset download: yes**, Nexus' storage layer honors `If-Modified-Since` / `If-None-Match` on assets. |
| Rate limits | No documented Nexus-side rate limit; bandwidth caps configurable per-repo. |
| Known issues | Stale-metadata problem: Nexus trusts upstream ETag / Last-Modified so aggressively that when a registry *silently* replaces a tarball, Nexus keeps serving the cached version ("Handling Stale npm Metadata in Nexus Repository After Registry Package Deletions"). Irrelevant to *our* client caching strategy but documents Nexus' heavy reliance on those headers. |

**Verdict:**
- `/service/rest/v1/search` — synthetic ETag required.
- Asset download URLs — OkHttp built-in cache actually works *if* we let it. But the plugin's use of asset downloads (if any) is one-shot; caching provides no benefit.

---

## G. Reverse proxies and corporate middleware

All six backends are typically fronted by nginx, F5, Apache, or a corporate egress proxy (Zscaler, Skyhigh). Observed realities:

- **Strong `ETag` is stripped by nginx when gzip is enabled.** Fixed somewhat in nginx 1.7.3 (2014), but the stripping behavior is still reported against recent ingress-nginx versions when `proxy_set_header` / `gzip on;` interact ([ingress-nginx#1390](https://github.com/kubernetes/ingress-nginx/issues/1390)). Many deployments work around this by converting to *weak* ETag (`W/"..."`) since those survive.
- **`Cache-Control: private` sometimes downgraded to `public`** by reverse proxies that want to serve static responses from an edge cache (and vice versa). Not observed to be a problem with Atlassian REST endpoints because `no-store` takes precedence.
- **Zscaler / F5 TLS-terminating proxies** can re-write, add, or drop arbitrary response headers depending on the deployed policy — teams have no visibility into whether ETag survives end-to-end. This is the strongest argument for a **self-contained client-side caching scheme** that doesn't depend on *any* server or intermediary behavior.
- **RFC 9111 + `Authorization`**: OkHttp's Cache treats itself as a private cache, so the shared-cache prohibition doesn't bite, but OkHttp still refuses to store responses with `no-store` and always revalidates when `no-cache` is present. Since every Atlassian response ships `no-cache, no-store`, OkHttp Cache is a no-op for the plugin's paths.

---

## H. Go / No-Go Matrix

| Backend | ETag emitted? | Last-Modified? | Cache-Control useful? | OkHttp built-in Cache viable? | Synthetic ETag required? | Recommended strategy |
|---|---|---|---|---|---|---|
| **Jira DC** (`/rest/api/2/*`, `/rest/agile/1.0/*`) | No (write-path only, for 412) | No ([JRASERVER-36374](https://jira.atlassian.com/browse/JRASERVER-36374) declined) | No (`no-cache, no-store, no-transform`) | **No** | **Yes** | Body-hash in a `CachingInterceptor`; TTL + hash-skip; on hash-match suppress parse+UI update. Key on (URL + Authorization-hash + Accept). |
| **Bamboo DC** REST (`/rest/api/latest/*`) | No | No | No | **No** | **Yes** | Same as Jira. |
| **Bamboo DC** artifacts/logs (static) | Weak ETag via Tomcat default servlet (if filter chain doesn't overwrite) | Yes (mtime) | Maybe | **Partially** (verify per-URL) | Recommended as fallback | Try OkHttp Cache first with a short directive rewrite interceptor; fall back to synthetic if headers don't survive. |
| **Bitbucket DC** | No | No | No | **No** | **Yes** | Same synthetic-ETag pattern. `/activities` is highest payload → biggest win. |
| **SonarQube** (`/api/*`) | No | No | No | **No** | **Yes** | Synthetic ETag + TTL; **exclude** `/api/ce/task` (progress polling must stay fresh). |
| **Sourcegraph Cody** `/.api/completions/stream` | N/A streaming | N/A | N/A | **Never** | N/A | Never cache. |
| **Sourcegraph Cody** `/.api/client-config`, `/.api/llm/models` | Not documented | Not documented | Not documented | Unknown → assume **No** | **Yes** | Synthetic ETag + 5–10 min TTL; refresh on explicit "Reload models" action. |
| **Nexus** `/service/rest/v1/search` | No | No | No | **No** | **Yes** | Synthetic ETag + short TTL. |
| **Nexus** asset download (`/repository/*`) | Yes (mirrored from upstream) | Yes | `must-revalidate` typical | **Yes** | No (use native) | Enable OkHttp Cache; respect server ETag. |

---

## Implementation guidance for Phase 3 `CachingInterceptor`

Given the uniformly-hostile cache-header landscape across 5 of 6 backends, the interceptor should:

1. Maintain a **client-internal `Map<CacheKey, Entry>`** (LRU, disk-backed if possible) where `CacheKey = hash(URL + normalized query + Authorization-hash + Accept + Accept-Language)`.
2. `Entry = { bodyBytes, sha256, parsedAt, ttlExpiresAt, syntheticETag = sha256 }`.
3. On request:
   - If entry exists and not expired → return cached `Response` short-circuit, **don't hit network** (pure client-side cache).
   - If entry exists but expired → make network call. Compare new body's SHA-256 to stored `syntheticETag`; if identical, **reuse parsed model reference** (skip downstream Jackson parse + EDT paint). If different, replace entry.
   - No entry → call, store, return.
4. TTLs per-endpoint class (configurable in `CacheProfile`):
   - Jira issue/search: 30–60 s (SmartPoller already backs off 1.5×)
   - Agile board/sprint: 5–10 min (rarely changes)
   - Bamboo results: 10–30 s (active builds) / 5 min (done builds)
   - Bitbucket PR list: 60 s / activities: 30 s
   - Sonar issues/measures: 2–5 min
   - Cody client-config, llm/models: 10 min
   - Nexus search: 2 min
5. **Never cache write requests, streaming endpoints, `/api/ce/task`, or auth/login.**
6. Use `NSCache`-style `private` qualifier in logs / never share across user sessions — respects RFC 9111 Authorization semantics.
7. Emit Micrometer/SLF4J counters: `http.cache.hit`, `http.cache.stale_match`, `http.cache.miss` — we need these to gate Phase 3 progress against the "baseline metrics" condition.
8. For Nexus asset downloads only, delegate to OkHttp's native `Cache` (different OkHttpClient instance or separate cache dir) because the server actually sends good validators.

---

## Key sources

- [RFC 9111 — HTTP Caching](https://www.rfc-editor.org/rfc/rfc9111.html) (supersedes RFC 7234)
- [OkHttp — Caching](https://square.github.io/okhttp/features/caching/)
- [JRASERVER-36374 — Jira REST does not send Last-Modified (Not a Bug)](https://jira.atlassian.com/browse/JRASERVER-36374)
- [JRASERVER-65373 — Support ETags in Jira REST (open, "Gathering Interest")](https://jira.atlassian.com/browse/JRASERVER-65373)
- [JRASERVER-17373 — Cache-Control: public for static resources (Fixed 3.13.5 / 4.0)](https://jira.atlassian.com/browse/JRASERVER-17373)
- [JSWSERVER-20618 — Jira Agile sprint cache warm-up performance](https://jira.atlassian.com/browse/JSWSERVER-20618)
- [Jira DC rate limiting — "Improving instance stability with rate limiting" (11.3)](https://confluence.atlassian.com/adminjiraserver/improving-instance-stability-with-rate-limiting-983794911.html)
- [Bamboo DC rate limiting — "Adjusting your code for rate limiting" (12.1)](https://confluence.atlassian.com/spaces/BAMBOO/pages/1115684477/Adjusting+your+code+for+rate+limiting)
- [Bitbucket DC rate limiting — "Improving instance stability" (10.2)](https://confluence.atlassian.com/bitbucketserver/improving-instance-stability-with-rate-limiting-976171954.html)
- [Bitbucket Server CachePolicies javadoc (4.6.0)](https://docs.atlassian.com/bitbucket-server/javadoc/4.6.0/rest-api/reference/com/atlassian/bitbucket/rest/util/CachePolicies.html)
- [SonarQube Web API docs](https://docs.sonarsource.com/sonarqube-server/extension-guide/web-api)
- [Sourcegraph Cody Gateway — rate limiting & quotas](https://sourcegraph.com/docs/cody/core-concepts/cody-gateway)
- [Nexus Search API](https://help.sonatype.com/en/search-api.html)
- [Sonatype support — Handling Stale npm Metadata (confirms proxy ETag reliance)](https://support.sonatype.com/hc/en-us/articles/44588764417171-Handling-Stale-npm-Metadata-in-Nexus-Repository-After-Registry-Package-Deletions)
- [nginx trac #101 — strong ETag stripped on gzip](https://trac.nginx.org/nginx/ticket/101)
- [ingress-nginx #1390 — ETag removed when gzip enabled](https://github.com/kubernetes/ingress-nginx/issues/1390)
- [Atlassian rate-limiting and retries — app migration platform](https://developer.atlassian.com/platform/app-migration/rate-limiting-and-retries/)
- [JENKINS-66539 — Jenkins throttling to survive Bitbucket DC rate limits](https://issues.jenkins.io/browse/JENKINS-66539)
