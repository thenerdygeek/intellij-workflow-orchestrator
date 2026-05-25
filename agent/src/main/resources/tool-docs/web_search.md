# `web_search` — extended notes

## Why this exists

`web_search` is the **discovery** half of web research; `web_fetch` is the **reading** half.
You search to find a URL you didn't know, then fetch the best one to actually read it.

The hard part isn't calling a search API — it's doing so safely from an agent whose prompt
is full of the user's *internal* context. Two guards make that safe:

- **Token redaction (Stage 1)** — an accidental `Bearer eyJ…` or AWS key in the query is
  redacted before anything leaves the machine.
- **Egress filter (Stage 1.5)** — proprietary identifiers (internal hostnames, class/package
  names, project/customer names, file paths) are blocked from being sent to a third-party
  search engine, where they would reveal organizational structure.

Results come back wrapped:

```
<external_search query='…' provider='SearXNG|Brave|Tavily|…' count='N' retrieved_at='…'>
  [1] Title — https://url  [FLAGS]
      one-line sanitized snippet
  [2] …
</external_search>
```

Snippets are **untrusted, sanitized teasers** — use them to decide *which* result to fetch,
not as final answers.

---

## The pipeline, stage by stage

| # | Stage | What it does | Failure code |
|---|-------|--------------|--------------|
| 0 | Plan-mode + provider check | blocks in plan mode; requires a configured provider | `PLAN_MODE_BLOCKED`, `NO_PROVIDER_CONFIGURED` |
| 1 | Query screen | redacts `Bearer` / JWT / AWS-key tokens | — |
| 1.5 | Egress filter | deny-list (always on) + optional LLM screener for proprietary terms | `QUERY_BLOCKED_SENSITIVE` |
| 2 | Provider resolve + SSRF | picks SearXNG/Brave/Tavily/Custom, screens the provider's own base URL | `PROVIDER_URL_UNSAFE` |
| 3 | Provider search | HTTP call to the provider; maps failure to a typed error | `PROVIDER_AUTH_FAILED`, `PROVIDER_MALFORMED_RESPONSE` |
| 4 | Per-hit normalize | URL-screens each result for flag badges; structural snippet strip | — |
| 5 | Batch sanitize | one sanitizer call over all snippets; truncate snippet + cap result count | — |

The result-URL **flags** in `[BRACKETS]` come from the URL screener: `SHORTENER`,
`IDN_HOMOGRAPH`, `IDN_NON_ASCII`, `SUSPICIOUS_TLD`. They warn the agent before it decides to
`web_fetch` a sketchy-looking hit.

---

## Scenario 1 — a clean search

**IN:**

```xml
<web_search><query>OkHttp connection pool default settings</query></web_search>
```

Stage 1 finds no tokens. Stage 1.5 finds no proprietary terms. Stage 2 resolves the
provider (say SearXNG at the user's configured instance) and screens its base URL.

**SENT to the internet (provider-specific; SearXNG shown):**

```http
GET /search?q=OkHttp+connection+pool+default+settings&format=json HTTP/1.1
Host: searx.example.org
```

**BACK (raw provider JSON, trimmed):**

```json
{ "results": [
  { "title": "Connections - OkHttp", "url": "https://square.github.io/okhttp/connections/",
    "content": "By default OkHttp shares a single ConnectionPool ... max 5 idle, 5 min keep-alive ..." },
  { "title": "OkHttpClient.Builder", "url": "https://square.github.io/okhttp/4.x/okhttp/...",
    "content": "connectionPool(ConnectionPool) ..." }
] }
```

**Stages 4–5** screen each URL (no flags), strip + sanitize each snippet, cap to
`max_results`.

**RESULT the LLM receives:**

```xml
<external_search query='OkHttp connection pool default settings' provider='SearXNG'
  count='2' retrieved_at='2026-05-25T22:20:11Z'>
  [1] Connections - OkHttp — https://square.github.io/okhttp/connections/
      By default OkHttp shares a single ConnectionPool: max 5 idle connections, 5-minute keep-alive.
  [2] OkHttpClient.Builder — https://square.github.io/okhttp/4.x/okhttp/okhttp3/-ok-http-client/-builder/
      connectionPool(ConnectionPool) overrides the shared default pool.
</external_search>
```

Typical next move: `web_fetch` result [1].

---

## Scenario 2 — accidental secret in the query (Stage 1 redaction)

**IN (the LLM pasted a token by mistake):**

```xml
<web_search><query>why does request fail with Authorization: Bearer eyJhbGciOiJIUzI1NiIs… 401</query></web_search>
```

**Stage 1** rewrites the query before it leaves the machine:

```
why does request fail with Authorization: Bearer [REDACTED] 401
```

The provider — and the audit log — only ever see the redacted form. The search still
proceeds; the wrapper's `query='…'` shows the redacted text.

---

## Scenario 3 — proprietary identifier (Stage 1.5 egress block)

**IN:**

```xml
<web_search><query>jenkins.acme.corp pipeline fails on PaymentsGatewayService timeout</query></web_search>
```

The egress filter matches `jenkins.acme.corp` (internal hostname) on the deny-list (and/or
the LLM screener flags `PaymentsGatewayService` as an internal class name). **Nothing is
sent to the provider.**

**RESULT:**

```
QUERY_BLOCKED_SENSITIVE: Search query blocked by egress filter (deny-list):
contains "jen***". Rewrite the query without internal identifiers (hostnames,
class names, project names, file paths) and try again.
```

Note the **masked term** (`jen***`) — the agent learns *what kind* of thing tripped the
filter without the sensitive value being echoed back unredacted. Correct recovery:

```xml
<web_search><query>Jenkins pipeline fails on payment service gateway timeout</query></web_search>
```

---

## Scenario 4 — provider problems

| Situation | Code | Recovery |
|-----------|------|----------|
| No provider set up in Settings ▸ Web | `NO_PROVIDER_CONFIGURED` | ask the user to configure SearXNG / Brave / Tavily |
| Wrong/expired API key | `PROVIDER_AUTH_FAILED` | tell the user to fix the key in settings; don't retry |
| Provider returned junk | `PROVIDER_MALFORMED_RESPONSE` | retry once, then try a different query/provider |
| Provider base URL points at an internal/loopback host | `PROVIDER_URL_UNSAFE` | misconfiguration — the provider URL itself failed the SSRF screen |

`NO_PROVIDER_CONFIGURED`, `PROVIDER_AUTH_FAILED`, and `PROVIDER_MALFORMED_RESPONSE` are
marked *recoverable* in `WebError`, but the first two are really settings problems — retrying
the same call without a settings change just fails again.

---

## Scenario 5 — flagged result URLs

If a hit's URL looks risky, the screener annotates it inline so the agent can avoid it:

```xml
<external_search query='free pdf merge tool' provider='Brave' count='2' retrieved_at='…'>
  [1] PDF Merge — https://pdf-merge.example.org
      Merge PDF files online for free.
  [2] FREE!! merge — https://merge-pdf.tk [SUSPICIOUS_TLD]
      …
  [3] click here — https://bit.ly/2xShady [SHORTENER]
      …
</external_search>
```

`[SUSPICIOUS_TLD]` and `[SHORTENER]` are advisory — the agent should prefer the unflagged
result, and remember that even if it tries to `web_fetch` the flagged one, that fetch will
re-screen and (for the shortener) resolve + re-screen the destination.

---

## Things that look like bugs but aren't

- **`provider='unknown'` with `count='0'`** — the provider returned no hits; the wrapper
  still renders so the LLM sees an explicit empty result rather than nothing.
- **A snippet that ends mid-sentence** — snippets are truncated at
  `settings.webSearchSnippetMaxChars`. Fetch the page for the full text.
- **Fewer results than `max_results`** — the provider had fewer hits, or the global cap
  (`webSearchMaxResults`) was smaller than the requested count.
- **A forged `</external_search>` inside a title/snippet** is HTML-escaped to
  `&lt;/external_search&gt;` rather than dropped — search returns N independent hits, so one
  hostile snippet is neutralized without poisoning the whole set (contrast web_fetch, which
  refuses the entire page on a forged close tag).

## Cost posture

- One provider HTTP call + **one** batch sanitizer call over all snippets (not one per hit).
- ~1 second typical. Cheap relative to `web_fetch` — which is why the intended loop is
  *search broadly, fetch narrowly*.
- Each search appends a row to `web-audit.log`: the (redacted) query, the post-egress query,
  provider, hit count, elapsed ms, and the egress decision.
