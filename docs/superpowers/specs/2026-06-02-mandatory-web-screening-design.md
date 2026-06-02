# Mandatory Web Egress/Ingress Screening — Design

**Date:** 2026-06-02
**Branch:** `worktree-web-fetch-search`
**Status:** Approved (brainstorming) → pending implementation plan

## Problem

The `:web` module's screening layers exist but ship with their teeth off:

- The **search egress LLM screener** (`QueryEgressLlmScreener`) is gated behind
  `webEgressLlmScreenerEnabled`, which **defaults to `false`**. The only always-on
  layer is a deterministic deny-list (`QueryEgressFilterImpl` Stage 0) whose
  user-supplied term list defaults to **empty**. So out of the box, an agent's
  `web_search` query — which can embed proprietary identifiers, internal hostnames,
  or secrets — reaches the third-party search provider with essentially no semantic
  screening.
- The **fetch content sanitizer** (`SanitizerSubagent`, `WebFetchEngine` stage 8) is
  always invoked, but its fail-closed behavior is disableable via `webSanitizerFailClosed`.

For corporate/data-sensitive use the strongest protective layer must not be opt-in.
This spec makes screening **truly mandatory** (no toggle) and reshapes the search
egress path from "detect & block" to "**sanitizing rewrite**" that always produces a
usable, de-identified query.

## Goals

1. Cheap-LLM **egress screening on every `web_search`** — non-disableable.
2. Egress behavior is **rewrite-to-dummy, preserving intent** — never blocks in
   normal operation; always emits a usable sanitized query.
3. The `web_search` tool **response surfaces the sanitized query actually sent**.
4. Fetch keeps **deterministic-only URL screening** + **mandatory, always-fail-closed
   content screening** (prompt-injection / malicious-instruction detection).
5. Both screeners use the **cheapest available model** by default.
6. No new third-party trust boundary — screeners reuse the existing
   `SubagentSpawner` (Sourcegraph), which the agent already uses.
7. **Web HTTP clients route through IntelliJ's configured proxy**, and fetch errors
   report the **actual** failure (DNS / connect / TLS / refused) with proxy/firewall
   guidance — fixing the `HTTP_TIMEOUT_connect`-on-everything symptom on corporate/VPN
   networks.

## Non-Goals

- LLM-based URL screening before fetch (deterministic SSRF/scheme/allowlist checks
  are reliable and zero-latency; the real payload risk is in content, already screened).
- Changes to the search-provider choice / onboarding (tracked separately).

## Architecture

### Flow 1 — `web_search` (egress)

```
main agent → web_search(query)
   → [Stage 0] deny-list FORCE-SUBSTITUTION (deterministic, zero-cost):
        every configured deny-list term in the query is replaced with a dummy
        placeholder. (Was: hard-block. Now: substitute — never blocks.)
   → [Stage 1] cheap-LLM sanitizing rewrite (MANDATORY, always runs):
        replace remaining sensitive/proprietary data (internal hostnames, service/
        module/class names, credentials, ticket IDs, internal URLs, customer names)
        with neutral dummy values, PRESERVING search intent; emit a usable query.
   → search provider(sanitized query)
   → results returned to main agent, WITH the sanitized query that was sent
```

- **Normal operation never blocks.** Stage 0 substitutes; Stage 1 rewrites.
- **Only block path — screener unavailable (fail-closed):** if the Stage 1 LLM
  times out / errors / returns an unparseable verdict, that single search is blocked
  with `EGRESS_SCREENER_UNAVAILABLE` ("retry"), because safety can't be guaranteed.
  This is the lone exception to "never block" and is the correct posture for a
  mandatory control.

### Flow 2 — `web_fetch` (ingress)

```
main agent → web_fetch(url)
   → deterministic URL screening (scheme / credentials / IP-literal / IDN /
        shortener resolution / SSRF guard / allowlist / approval) — UNCHANGED
   → HTTP GET, TEXT-ONLY (content-type allowlist + binary prefix-sniff) — UNCHANGED
   → structural sanitization (jsoup: strip scripts/styles/iframes) — UNCHANGED
   → [MANDATORY] cheap-LLM content sanitizer (prompt-injection / malicious-
        instruction detection), ALWAYS fail-closed — a timeout blocks the content,
        never passes it raw
   → sanitized text returned to main agent
```

- No LLM on the URL. Content screen is the mandatory LLM layer; it already exists
  and is always invoked — this spec only makes its fail-closed behavior unconditional.

### Connectivity — proxy routing + accurate errors

Root cause of the field report (`web_fetch` → `HTTP_TIMEOUT_connect` for
`docs.spring.io`, `httpbin.org`): the web module builds raw `OkHttpClient.Builder()`
instances (fetch template, shortener, search) with **no proxy selector**, so on a
corporate/VPN network the TCP connect to external hosts times out (internal hosts
like Jira/Bamboo are directly reachable, so they work — masking the gap). DNS already
resolves (the SSRF stage would reject otherwise), confirming it's egress/proxy, not
DNS or target handling. Secondly, `WebFetchEngine` wraps the fetch in a catch-all
`catch (_: Exception) { HttpTimeout("connect") }` (line ~291), so **every** failure
(DNS, TLS/cert, connection-refused, proxy) is mislabeled as a connect timeout.

Fix:

1. **New shared helper in `:core`** — `core/http/IdeProxy.kt` exposing the IDE's
   proxy `ProxySelector` (and proxy `Authenticator`) from
   `com.intellij.util.net.JdkProxyProvider` (2025.1+ platform API). Placed in `:core`
   so the existing `HttpClientFactory` can adopt it later without duplication
   (`:web → :core` direction preserved).
2. **Apply it to all three web clients** — the fetch template + shortener
   (`WebFetchServiceImpl`) and the search client (`WebSearchServiceImpl`) call
   `.proxySelector(IdeProxy.selector())` (+ proxy auth) on their builders.
3. **Replace the catch-all** in `WebFetchEngine` with specific mapping:
   - `UnknownHostException` → `HttpDnsError` ("DNS resolution failed — check connectivity/DNS")
   - `ConnectException` / connect-stage `SocketTimeoutException` → `HttpConnectError`
     with **proxy/firewall guidance** ("connect timed out — if behind a corporate
     proxy/VPN, configure it in Settings → HTTP Proxy; check firewall egress")
   - `SSLException` / cert errors → `HttpTlsError` ("TLS/certificate failure — a
     corporate MITM proxy may require its CA")
   - read-stage `SocketTimeoutException` → `HttpReadTimeout`
   - other → generic `HttpError` carrying the exception class name
   (`CancellationException` still re-thrown, never swallowed.)

### Cheap model

Both screeners resolve to the **cheapest available model** by default (today
`resolveSanitizerBrainId()` returns blank = "cheapest available"; make this the
explicit, documented default for the egress screener too). A specific brain id may
still be configured to override.

## Components & changes

| Component | Change |
|---|---|
| `QueryEgressFilterImpl` | Stage 0 deny-list: `Blocked` → `Rewritten` (force-substitute matched terms with masked dummies). Stage 1: remove the `llmScreenerEnabled` gate — always delegate to the LLM screener. |
| `QueryEgressLlmScreener` | Verdict mapping shifts to rewrite-first: `SAFE`→Safe, `STRIPPED`→Rewritten, `REFUSED`/`TIMEOUT`/`UNRECOGNISED`→`Blocked(EGRESS_SCREENER_UNAVAILABLE)` (fail-closed; the only block). |
| `egress-screener-system-prompt.txt` | Rewrite the persona: from "flag proprietary → block" to "replace sensitive/proprietary tokens with neutral dummy placeholders while preserving search intent; always return a usable rewritten query." Keep the `SAFE`/`STRIPPED`/`REFUSED` verdict labels (parser unchanged). |
| `WebSearchEngine` | Egress filter already always invoked (Stage 1.5). Ensure the result clearly surfaces `queryAfterEgress` as "sanitized query sent" (+ rewritten note). |
| `WebSearchServiceImpl` | Wiring: drop the `llmScreenerEnabled` branch; always construct the LLM screener; pin to cheapest model. |
| `WebFetchEngine` | Stage 8 sanitizer already mandatory; remove the `webSanitizerFailClosed` branch — always fail closed on `TIMEOUT`. |
| `PluginSettings.State` | Remove `webEgressLlmScreenerEnabled` and `webSanitizerFailClosed`. Keep `webEgressDenyListJson` (now substitution seed), `webEgressIncludeAutoDerivedTerms`, `webEgressTimeoutMs`, provider/allowlist/cache fields. |
| `WebSettingsConfigurable` | Remove the two toggles' UI rows; add a short note that egress rewrite + content screening are always-on. |
| `core/http/IdeProxy.kt` (new) | Shared helper exposing the IDE proxy `ProxySelector` + proxy `Authenticator` via `JdkProxyProvider`. |
| `WebFetchServiceImpl` / `WebSearchServiceImpl` | Apply `IdeProxy` selector (+ proxy auth) to the fetch template, shortener, and search client builders. |
| `WebFetchEngine` | Replace the catch-all `HttpTimeout("connect")` with specific `UnknownHostException` / `ConnectException` / `SocketTimeoutException` (connect vs read) / `SSLException` / generic mapping, each with actionable guidance. |
| `WebError` | Add `HttpDnsError`, `HttpConnectError`, `HttpTlsError`, `HttpReadTimeout`, generic `HttpError(detail)`; messages include proxy/firewall guidance where relevant. |

## Error handling

- `EGRESS_SCREENER_UNAVAILABLE` (new `WebError`) — Stage 1 LLM failure on search;
  recoverable; message instructs retry.
- Content sanitizer `TIMEOUT` → existing `SanitizerTimeout` block (now unconditional).
- All decisions audited via `WebAuditLog` (queries masked; rewritten queries record
  the post-rewrite form, never the raw sensitive original beyond masked audit).
- Fetch network failures → specific `WebError` per the connectivity section
  (DNS / connect+proxy / TLS / read-timeout / generic), each recoverable with
  actionable guidance; the catch-all `HttpTimeout("connect")` is removed.

## Testing

- `QueryEgressFilterImpl`: deny-list substitutes (not blocks); Stage 1 always runs.
- `QueryEgressLlmScreener`: SAFE/REWRITTEN pass-through; failure verdicts → fail-closed block.
- `WebSearchEngine`: result includes the sanitized query; rewritten note present;
  screener-unavailable → blocked.
- `WebFetchEngine`: content-sanitizer timeout always blocks (no toggle).
- Cheapest-model selection for both screeners.
- Update/remove existing tests that set `webEgressLlmScreenerEnabled` /
  `webSanitizerFailClosed`.
- **Connectivity:** `IdeProxy` returns the IDE proxy selector; the three web client
  builders apply it; `WebFetchEngine` maps `UnknownHostException` / `ConnectException`
  / connect- vs read-stage `SocketTimeoutException` / `SSLException` to the correct
  `WebError` with the expected guidance string (no more blanket `HTTP_TIMEOUT_connect`).
  Proxy wiring is verified by a source/contract test (builder calls `.proxySelector`),
  since live-proxy behavior can't be unit-tested.

## Related work (separate, not in this spec)

- Search-provider onboarding / default-provider posture (Tavily/Brave/SearXNG +
  DPA/ZDR guidance) — tracked separately.
