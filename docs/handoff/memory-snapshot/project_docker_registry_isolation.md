---
name: DockerRegistryClient kept isolated from HttpClientFactory.clientFor()
description: DockerRegistryClient uses the Docker Registry v2 OAuth bearer-challenge protocol and must NOT route through HttpClientFactory.clientFor(ServiceType.NEXUS). Its auth flow is incompatible with the factory's static AuthInterceptor.
type: project
originSessionId: 2bce1f3e-6143-4b15-9d97-55eb42086d7c
---
`automation/api/DockerRegistryClient.kt` constructs its OkHttpClient via `HttpClientFactory.sharedPool.newBuilder()` and deliberately does NOT go through `HttpClientFactory.clientFor(ServiceType.NEXUS)`.

**Why:** Docker Registry v2 API uses a multi-step OAuth flow:
1. Client sends unauthenticated request → server returns `401 Unauthorized` with `WWW-Authenticate: Bearer realm="..."` header.
2. Client extracts the realm URL, validates it via `isRealmSafe()` (SSRF guard — rejects localhost, private IP ranges, non-matching hosts), then fetches a short-lived bearer token from the realm using the base64 `username:password` supplied by the tokenProvider.
3. Client retries the original request with `Authorization: Bearer <token>` header.

Installing `AuthInterceptor(tokenProvider, AuthScheme.BASIC)` (which is what `clientFor(ServiceType.NEXUS)` does) would add `Authorization: Basic <base64-user:pass>` to EVERY request, which:
- Leaks credentials to the registry on the initial unauthenticated request (intent is to first discover the realm URL without sending credentials).
- Interferes with the bearer-token retry in `executeWithAuth()` — OkHttp treats headers set via `request.newBuilder().header(...)` as overrides, but the retry path assumes the original request had no Authorization header.
- Bypasses the SSRF-safe realm validation (`isRealmSafe()` at line 196+ of DockerRegistryClient.kt) because the OAuth challenge handshake is skipped.

The ServiceType enum value `NEXUS("Nexus Docker Registry")` with `AuthScheme.BASIC` is retained in `HttpClientFactory` for OTHER Nexus-hosted REST endpoints that use Basic auth (none exist in the plugin today — the enum is a forward-looking contract).

**How to apply:**
- Do NOT migrate `DockerRegistryClient` to `HttpClientFactory.clientFor(ServiceType.NEXUS)` as part of the Phase 3 routing audit or any future consolidation pass.
- Do NOT install the Phase 3 `CachingInterceptor` on the Docker Registry client path. DockerRegistryClient has its own application-level `tagCache` (ConcurrentHashMap with 5-min TTL) that correctly handles per-service caching; shared HTTP caching at the interceptor layer would duplicate work and confuse invalidation.
- If a future commit proposes sharing the connection pool + auth via `HttpClientFactory.clientFor(ServiceType.NEXUS)` for Docker registry traffic, DO NOT accept it — flag this memory and the associated `executeWithAuth` security semantics.
- `HttpClientFactory.sharedPool` (without interceptors) is the correct entry point for DockerRegistryClient and remains so.

**Confirmed 2026-04-24 during Phase 3 P1 routing audit.** Two deliberate exceptions now exist: this one and `SourcegraphChatClient` (see `project_sourcegraph_isolation.md`).
