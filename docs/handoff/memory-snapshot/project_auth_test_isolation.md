---
name: AuthTestService kept isolated from HttpClientFactory.clientFor()
description: AuthTestService must NOT route through HttpClientFactory.clientFor(). Its semantics are fundamentally different from per-service production clients — different timeouts, no redirects, and the token is test data not session data.
type: project
originSessionId: 2bce1f3e-6143-4b15-9d97-55eb42086d7c
---
`core/auth/AuthTestService.kt` builds its `testClient` OkHttpClient from `HttpClientFactory.sharedPool.newBuilder()` with three overrides that the factory does NOT provide:
1. `.followRedirects(false)` + `.followSslRedirects(false)` — intentional; a 3xx response is how the settings UI detects a wrong base URL (e.g., user enters `https://jira.example.com/rest` and gets redirected back to `/`).
2. Short 10s/10s timeouts — connect-test should fail fast, not use the longer project-configured production timeouts.
3. The `Authorization` header is set per-request via `.header("Authorization", buildAuthHeader(...))` using the token the USER just typed into settings. The factory's `AuthInterceptor` would add a second `Authorization` header sourced from `PasswordSafe` — at best redundant, at worst overriding the user-provided test token.

**Why:** The factory's `clientFor(ServiceType)` is designed for the production session model: one per-project token, session-long lifetime, full interceptor stack, auto-follow redirects. AuthTestService is the opposite: ephemeral, one-shot, token-as-data, redirect-as-signal. Merging the two would either break redirect detection or auth-test semantics — both are user-visible bugs in the Settings panel.

**How to apply:**
- Do NOT migrate `AuthTestService.testClient` to `HttpClientFactory.clientFor(...)` in the Phase 3 routing audit or any future consolidation sweep.
- Do NOT install the Phase 3 `CachingInterceptor` on the AuthTestService path. Test-connection responses must never be cached — the whole point is to exercise the network round-trip every time.
- `HttpClientFactory.sharedPool` (bare connection pool, no interceptors) is the correct base for AuthTestService. The 10s/10s timeouts + `followRedirects(false)` stay local to this class.
- `AgentParentConfigurable.kt:206` — the Sourcegraph "Load Models" button in settings builds a one-shot `httpClientOverride` for `SourcegraphChatClient.listModels()`. That override also uses `sharedPool.newBuilder()` by design (feeds into Sourcegraph's isolated client path per `project_sourcegraph_isolation.md`) and must NOT migrate to `clientFor(ServiceType.SOURCEGRAPH)`.

**Confirmed 2026-04-24 during Phase 3 P1 routing audit.** Three deliberate exceptions now exist from the migration: `SourcegraphChatClient` (protocol sensitivity), `DockerRegistryClient` (OAuth bearer-challenge), and `AuthTestService` (test-data semantics). The routing audit is complete after the 5 production migrations (Jira, Bamboo, Sonar, Bitbucket — JiraTaskRepository included with Jira commit).
