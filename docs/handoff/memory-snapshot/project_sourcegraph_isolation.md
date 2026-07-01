---
name: Sourcegraph HTTP client kept isolated from HttpClientFactory
description: SourcegraphChatClient is intentionally NOT routed through HttpClientFactory.clientFor(). Sourcegraph is sensitive about client usage patterns and must stay independent.
type: project
originSessionId: 2bce1f3e-6143-4b15-9d97-55eb42086d7c
---
`core/ai/SourcegraphChatClient.kt` constructs its own `OkHttpClient.Builder()` (with optional override) and deliberately does NOT go through `HttpClientFactory.clientFor(ServiceType.SOURCEGRAPH)`, even when other clients do.

**Why:** Sourcegraph (Cody Enterprise) is sensitive about client usage patterns — shared interceptor stacks, shared connection pools, and shared caches can trigger rate-limit or detection behaviors that don't affect traditional REST backends (Atlassian, SonarSource). Keeping the Sourcegraph HTTP client on its own code path protects against unintended coupling. User confirmed this policy 2026-04-24 when scoping Phase 3 migration work: "Yes skip 7 as it was supposed to be different as sourcegraph is really sensitive about usages."

**How to apply:**
- Do NOT migrate `SourcegraphChatClient` to `HttpClientFactory.clientFor()` as part of Phase 3 routing audit or any future consolidation pass.
- Do NOT install the Phase 3 `CachingInterceptor` on the Sourcegraph client path. Sourcegraph's `/.api/completions/stream` is SSE anyway, but even non-streaming endpoints (`/.api/client-config`, `/.api/llm/models`) stay off the shared cache.
- If a future refactor proposes sharing the connection pool via `HttpClientFactory.sharedPool` for Sourcegraph, DO NOT accept it — flag this memory.
- The `httpClientOverride` injection point in `SourcegraphChatClient` is allowed (used by tests + `AgentParentConfigurable` "test LLM" button) but the default path must remain a plain `OkHttpClient.Builder()`.
- `core/ai/OpenAiCompatBrain.kt` (the other AI client) is out of scope here — verify separately if consolidation is ever proposed for it.
