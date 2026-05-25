# Web-Search Landscape for an Agentic IDE Plugin — 2026

> **Context:** Synthesis research conducted after `web_fetch` + `web_search` shipped on the `worktree-web-fetch-search` branch (see `docs/superpowers/specs/2026-05-23-web-fetch-search-design.md`). Goal: validate the pluggable-provider architecture against the actual 2026 landscape and surface anything that should change.

**TL;DR for the implementer:** The pluggable `SearchProvider` design is the correct call — every open-source agent does exactly this in 2026. **Brave Search API** is the new corporate-friendly default. **Bing API is dead** (Aug 2025), **Google PSE is sunset** (Jan 2027), **Whoogle is dead** (Apr 2026), **Stract is archived** (Apr 2026). **SearXNG** AGPL is fine to call over HTTP from a permissive client (FSF-explicit). Adding a **Tavily** provider would be the highest-value follow-up — it's the LLM-tuned choice most other agents have converged on.

---

## 1. What other agentic AI coding tools actually do

Three architectural camps:

### Camp 1 — Hosted-LLM bundled (vendor-mediated)

The vendor's backend brokers search; the user pays via subscription/credits and never directly touches a provider key. The vendor sees every query.

| Tool | Search backend | Fetch backend | Vendor sees query? |
|---|---|---|---|
| **Claude Code** | Anthropic's server-side `web_search_20250305` (same engine as Claude.ai chat). US-only. | **Local** — Node/axios HTTP GET, HTML → Markdown via Turndown, then a Haiku 3.5 sub-call summarises (skipped if MD < 100 KB). Domain check via `claude.ai/api/web/domain_info`. | Yes (search + Haiku summarisation see content). Fetch HTTP egresses from user's machine. |
| **Cursor** | `@web` is server-side via Cursor's backend; primary provider undisclosed but **Tavily is the official plugin partner**, with Exa/Firecrawl/You.com as alternative MCP backends. | Cursor backend, or pluggable MCP. | Yes |
| **Windsurf** | `@web` is server-side on Windsurf infra; URL parsing (`@docs`, pasted URLs) runs **locally on the device** ("entirely on your device within your network"). | Local for URL reads, server for `@web`. Page chunking by section for credit efficiency. | For `@web` only |
| **GitHub Copilot Chat** | `@github` participant generates a Bing query and calls the **Bing Search API** (Microsoft-side). Newer github.com models can use "model-native web search"; older models still route via Bing. | Bing returns snippets/URLs; no separate fetch tool. | Yes (GitHub generates query; Bing receives it under Microsoft's privacy policy) |
| **OpenAI Codex / ChatGPT browsing** | Server-side via OpenAI's hosted `web_search` tool; 2026 Codex desktop unifies with the **Atlas** browser for agent-mode navigation. | Hosted browser tool (Atlas/headless) on OpenAI side; Codex CLI can also drive a local browser. | Yes (query, page content, screenshots flow through OpenAI) |
| **Cline 3.48+** | Two coexisting paths: (1) **built-in `web_search`/`web_fetch`** through Cline's own backend, gated to "Cline provider users with account credits"; (2) MCP servers per user. | (1) Cline backend returns plaintext (no headless browser); (2) MCP fetch servers; (3) legacy `browser_action` Puppeteer. | For built-ins, yes |

### Camp 2 — Open-source / BYO-key via MCP

User wires Brave/Tavily/Bright Data MCP servers, pays providers directly, agent vendor stays out of the loop.

| Tool | Search backend | Fetch backend | Notes |
|---|---|---|---|
| **Continue.dev** | `@web` is **deprecated** — docs now steer users to MCP servers (Brave Search MCP, Tavily MCP). `@search` is local ripgrep over the codebase. | Via MCP fetch servers. | User pays the provider directly. |
| **Aider** | No native search. URL must be supplied with `/web <url>`. | Local: `httpx` by default; optional Playwright/Chromium for JS-rendered pages. HTML → Markdown → added to chat. | User pays whichever LLM session is summarising. |
| **Roo Code** | No first-party search; `browser_action` (Puppeteer + screenshots) or MCP (Bright Data / Brave / Tavily). | Local Puppeteer Chromium returning screenshots + console logs. | Active community request for non-MCP search. |
| **Kilo Code** | No built-in; users wire MCP (Bright Data, Tavily) or use Cline-inherited `browser_action`. | Same Puppeteer + MCP pattern. | Open GH discussion #826 asks to add native search. |
| **Augment Code** | No first-party web. Bright Data MCP common. | MCP-only. | Augment subscription covers indexing; user pays MCP. |

### Camp 3 — No web access at all

| Tool | State |
|---|---|
| **Sourcegraph Cody** | No web search, no web-URL fetch tool. `@-mentions` cover files/symbols/repos but **not** arbitrary web URLs. 2026 "Skills API" lets enterprises script integrations (Jira/Notion) but is not a generic web tool. Cody Free/Pro discontinued in 2025; enterprise-only at $59/user/mo. |

### Patterns (this matters for our design)

1. **Hosted-LLM agents bundle search and pay for it themselves.** User sees a subscription line; vendor sees every query. This is what most managed agents (Claude Code, Cursor, Copilot, Codex, Cline 3.48) do.
2. **Open-source / local-first agents push provider choice and billing onto the user via MCP** (or via direct config like Continue's deprecated providers). Continue, Aider, Roo, Kilo, Augment. The benefit is BYO-key economics and direct vendor relationships; the cost is configuration burden.
3. **Fetch is consistently split from search and is much more often local.** Even Claude Code fetches with local axios and only delegates *summarisation* to Haiku. Aider/Roo/Kilo/Continue all fetch locally. The motivation: fetch is cheap HTTP, but summarising 10–100 KB of HTML is where tokens burn — do IO close to the user, summarisation close to the model.

**Our plugin lands squarely in Camp 2** — Apache/MIT-licensed, no central plugin backend, user supplies their own provider config. The `WebFetchEngine` does local OkHttp fetch + local jsoup sanitization + delegated subagent summarisation. The `WebSearchService` is pluggable per provider. This matches the open-source pattern exactly.

---

## 2. Search-API provider landscape (2026)

### Dead or deprecated — do not adopt

| Provider | Status | Notes |
|---|---|---|
| **Bing Web Search API** | **RETIRED Aug 11, 2025** | Replacement "Grounding with Bing Search" inside Azure AI Foundry costs 40–483% more, requires full Azure project, returns only model-mediated citations not raw results. |
| **Google PSE / Custom Search JSON API** | **DEPRECATED, closed to new signups; sunset Jan 1, 2027** | Migration path is Vertex AI Search. Don't build new integrations against PSE. |
| **DuckDuckGo HTML scrape** (`html.duckduckgo.com`) | **Violates DDG TOS + `robots.txt`** | `Disallow: /html`, `/lite`, `/*?`. IP/account bans likely. Corporate legal will reject. **Do not ship.** |
| **DuckDuckGo Instant Answer API** (the JSON one) | Free, no key | Not a SERP — returns Wikipedia-style "instant answers" only. Useful as a fallback for definitional queries; insufficient as a primary provider. |

### Active, ranked for our use case (developer IDE, ~5–50 searches/day per user)

| Provider | Free Tier | Paid (per 1K) | Auth Header | Response Body | Corp-friendly? |
|---|---|---|---|---|---|
| **Brave Search API** | $5/mo credits (~1K req) | $5 / 1K (Search) | `X-Subscription-Token` | title, URL, description, snippets, schema metadata | **Yes** — ZDR option, GDPR DPA + EU SCCs, independent 30B-page index |
| **Tavily AI** | 1,000 credits/mo (no card) | ~$8 / 1K basic | `Authorization: Bearer tvly-…` | title, url, content (snippet ready for LLM), raw_content (opt), score, answer (opt) | **Yes** — AI-search positioned; LangChain ecosystem default; enterprise contracts |
| **Exa AI** | 1,000 req/mo | $7 / 1K (Search), $12–15 (Deep), $1 / 1K (Contents) | `x-api-key` | title, url, text, highlights, score, publishedDate | **Yes** — ZDR for enterprise, US co; neural/semantic search |
| **Serper** | 2,500 free credits (6-mo expiry) | $0.30–$1 / 1K | `X-API-KEY` | Full Google SERP shape | **Caution** — scrapes Google SERP; no legal-shield guarantee. Cheapest by far but corporate legal may flag the Google-scraping relationship. |
| **SerpAPI** | 250 q/mo | $25 → $275/mo for 1K → 30K plans (~$8–25 / 1K effective) | `api_key` | Mirrors Google/Bing SERP | **Yes** — "U.S. Legal Shield" up to $2M on paid plans; subscription-only |
| **You.com API** | $100 starter credits + 100 q/day free MCP profile | $5 / 1K (Web Search), $1 / 1K (Contents) as of Mar 2026 | `X-API-Key` | title, url, snippets, full page content (Contents API), Research API for deep search | **Yes** — US enterprise vendor, active product investment |
| **Perplexity Sonar** | None advertised | Tokens: Sonar $1/$1 per 1M in/out; Sonar Pro $3/$15. Request fee $5–14 / 1K. | `Authorization: Bearer pplx-…` | LLM-synthesised answer + citations array (URLs) — **not a raw SERP** | **Yes for Enterprise** — Sonar has ZDR, no training on Sonar customer data |
| **Kagi Search API** | None — contact sales | ~$25 / 1K | `Authorization: Bot <token>` | data[] with t, url, title, snippet; inherits account block/promote rules | **Yes** — privacy-first vendor, no ads, but sales-gated; premium pricing |
| **Mojeek** | (paid) | (paid) | REST | Independent UK index | **Yes** — closed SaaS, independent crawler |

### Quick recommendation for our plugin

For Apache/MIT IDE plugin where the developer supplies their own key:

1. **Brave Search API** (primary, corporate-friendly default) — `X-Subscription-Token`, $5/mo free credits cover light dev usage, $5/1K thereafter, independent index, ZDR/GDPR-DPA story, clean docs.
2. **Tavily AI** (LLM-tuned alternative) — `Authorization: Bearer`, 1,000 free credits/mo, returns LLM-ready `content` + optional `raw_content`. Best when the downstream consumer is an LLM (our agent).
3. **Exa AI** (semantic/research mode) — `x-api-key`, 1,000 free req/mo, returns page `text` and `highlights`, neural search excels at "find me code/docs similar to X" — natural fit for an IDE.

All three share a "title + url + snippet/content" response shape that maps cleanly to a single `SearchHit` model — exactly the shape we already have.

---

## 3. Self-hostable + license-friendly options

### The AGPL question (load-bearing)

Per the **FSF GPL FAQ**:

> *"If some network client software is released under AGPLv3, does it have to be able to provide source to the servers it interacts with? **This is not required by the AGPL.**"*

AGPLv3 §13 only triggers for the **modified server program** offered to remote users. It does **not** propagate across a network API boundary to clients. Our Apache plugin merely sending HTTPS requests to a SearXNG instance is **not** a derivative work and incurs **zero** AGPL obligations on the plugin's own code.

**Bundling is different.** Shipping a SearXNG Docker image inside our distribution = "conveying" under AGPL §0/§5 → triggers source-availability for SearXNG (which is already public), but **does not relicense the plugin** (separate aggregation; AGPL §5 explicitly permits "mere aggregation"). We choose not to bundle for support-burden reasons, not legal ones.

**Corporate precedent — AGPL is *touched* daily:**
- MongoDB (pre-SSPL, 2009–2018) was AGPL and used by Fortune 500s as a network service — no infection events.
- Plausible Analytics runs as AGPL since Oct 2020; thousands of corporate sites embed its `script.js` (MIT-clean) and self-host the AGPL server without internal-code disclosure.
- Mastodon instances run AGPL servers behind corporate brands (Medium, MIT, Vivaldi) without source publication beyond Mastodon itself.

**The AGPL-allergic exception:** Google internally bans AGPL outright ("Code licensed under AGPL MUST NOT be used at Google") — but this is **policy, not statute**. Many BigCo legal teams follow the same posture.

**No case law has tested AGPL §13 in a US court** as of 2026. The interpretive consensus (FSF + practitioner blogs + 17 years of operational adoption) is stable.

### Self-hostable options comparison

| Option | License | Self-host footprint | Index/Freshness | Status (2026) |
|---|---|---|---|---|
| **SearXNG** | AGPL-3.0 | 1× `docker run searxng/searxng`, ~150 MB image | Meta-search — proxies 200+ engines (Google/Bing/DDG/Brave/StackOverflow/GitHub). Always current. | **Active**, weekly releases |
| **Whoogle** | MIT | Docker, ~80 MB | Google scraper proxy | **DEAD** — final release April 2026; Google permanently blocked JS-less queries |
| **Stract** | AGPL-3.0 | Rust binary, own crawler+index | Independent | **ARCHIVED 2026-04-02** (read-only on GitHub) |
| **Marginalia** | AGPL-3.0 (code) / **CC-BY-NC-SA 4.0** (index data) | Java/Gradle; heavy infra | Hand-curated indie "small/old/weird" web | **Poor for dev docs** — by design excludes commercial sites |
| **Mwmbl** | AGPL-3.0 | Python, Docker | ~500M URLs mid-2025; goal 10B by end-2026 | Active but weak — community-ranked, sparse |
| **YaCy** | GPL-2.0+ | JAR/Docker, 3-min install | P2P federated; freshness depends on peers | Active (Apr 2026), Java-heavy, inconsistent quality |
| **Lucene + own crawler** | Apache-2.0 | Massive — write your own crawler/scheduler/ranker | Whatever you crawl | Not viable for dev-doc queries (corpus cost) |

### Decision matrix

| Scenario | Pick |
|---|---|
| Solo dev — free + private + zero infra | **Brave Search API free tier** (2K queries/mo, MIT-friendly TOS, independent index, no AGPL question) |
| Solo dev — willing to `docker run` once | **SearXNG** — best objective fit. Meta-searches StackOverflow/GitHub/MDN, AGPL boundary cleanly across HTTP, ~150 MB image, zero telemetry |
| Corporate — legal is AGPL-allergic | **Brave Search API** (paid tier, ~$3/1K) or **Mojeek API**. No good open-source answer in 2026 now that Stract is archived |

---

## 4. What this means for our existing design

Our shipped design (`docs/superpowers/specs/2026-05-23-web-fetch-search-design.md`) made all the right architectural calls. Concretely:

| Design decision | Validated by research? |
|---|---|
| Pluggable `SearchProvider` interface (no built-in default enabled) | **Yes** — every Camp-2 open-source agent does this. Continue deprecated their built-in `@web` in favour of MCP. |
| Ship SearXNG + Brave + CustomHttp as the three first providers | **Mostly yes** — Tavily should arguably be the 3rd ahead of CustomHttp, since Cursor/LangChain ecosystem has converged on it. CustomHttp remains valuable as the corporate-proxy escape hatch. |
| Don't bundle SearXNG; user provides URL | **Yes** — confirmed by AGPL analysis. Bundling triggers conveyance even though it doesn't relicense the plugin; the support-burden cost isn't worth it. |
| Default-deny allowlist + per-call approval for fetch | **Yes** — no other agent does this as strictly; we're more conservative than Claude Code (which only does domain_info safety check, not user allowlist). This is a differentiator for "extremely secure." |
| Local fetch + delegated subagent summarisation | **Yes** — exactly Claude Code's pattern (axios fetch + Haiku summarise). Our impl uses Haiku-tier via `LlmBrainFactory.createForSanitization()` — same idea. |
| Strip `Authorization`/`Cookie` on every outgoing request | **Yes — and this is something most agents don't bother with.** Real differentiator. |
| Manual redirect-loop re-screening (no `followRedirects=true`) | **Yes — also rare.** Most fetch tools accept OkHttp's default and have the SSRF redirect bypass we caught. |

### Things to consider changing or adding

1. **Add Tavily as a 4th provider.** ~60 LOC mirroring `SearXNGProvider`. Tavily's `Authorization: Bearer tvly-…` + `{results: [{title, url, content, raw_content, score}]}` response shape is even closer to our `SearchHit` model than Brave's. The "LangChain ecosystem default" matters for credibility with users coming from other agents.

2. **Add an Exa adapter** if/when neural-search use cases surface. Lower priority — different mental model from keyword search.

3. **Remove any mention of Bing or Google PSE** from docs if we wrote any. Looking at the spec — we didn't list them, but the CustomHttpProvider's example URL template was Google-ish. Worth a doc note.

4. **Document the AGPL/SearXNG legal analysis** in the settings page's SearXNG sub-panel or a help link. Most corporate users won't know that calling SearXNG over HTTP is FSF-explicit "no infection." A 2-sentence tooltip + link to the FSF FAQ would head off a lot of "is this OK to use at $WORK?" tickets.

5. **Don't add a server-side bundled provider** (the Camp-1 pattern). That would require running our own backend, billing relationship, and access to a search-index licence. Out of scope for an Apache/MIT plugin.

6. **Consider an MCP-provider adapter** as a future provider (after the existing three+Tavily land). MCP is becoming the de-facto standard for "agent calls out to external service" — being able to point our `WebSearchService` at any MCP search server would buy us Bright Data, Firecrawl, and whatever else emerges, without us writing per-vendor code. Low priority but high optionality.

---

## 5. Sources

### Subagent reports (verified, ~1,500 words each)

- `/tmp/web-search-providers-research.md` — Provider pricing + licensing matrix (Brave, Tavily, Exa, Serper, SerpAPI, You.com, Perplexity, Kagi, Google PSE, Bing, DuckDuckGo)
- `/tmp/agentic-tools-web-impl.md` — How 10 agentic tools implement web search/fetch
- `/tmp/self-hosted-search-research.md` — Self-hosted + license-friendly analysis (SearXNG AGPL, Whoogle, Stract, Marginalia, Mwmbl, YaCy, Lucene-DIY)

### Primary sources (cited inline above)

- [FSF GPL FAQ — AGPL client clause](https://www.gnu.org/licenses/gpl-faq.en.html)
- [AGPL-3.0 text](https://www.gnu.org/licenses/agpl-3.0.en.html)
- [Brave Search API](https://brave.com/search/api/) · [Brave ZDR announcement](https://brave.com/blog/search-api-zero-data-retention/)
- [Tavily API Credits docs](https://docs.tavily.com/documentation/api-credits)
- [Exa Pricing](https://exa.ai/pricing)
- [You.com Business API](https://you.com/business/api/) · [March 2026 pricing reduction](https://you.com/resources/lower-search-api-cost)
- [Perplexity API pricing](https://docs.perplexity.ai/guides/pricing)
- [Kagi Search API docs](https://help.kagi.com/kagi/api/search.html)
- [Google Custom Search JSON API (deprecation)](https://developers.google.com/custom-search/v1/overview)
- [Bing Search API Retirement (Microsoft Lifecycle)](https://learn.microsoft.com/en-us/lifecycle/announcements/bing-search-api-retirement)
- [Claude Code Web Tools reverse-engineering — Mikhail Shilkov](https://mikhail.io/2025/10/claude-code-web-tools/)
- [Anthropic Web Fetch tool docs](https://platform.claude.com/docs/en/agents-and-tools/tool-use/web-fetch-tool)
- [GitHub Copilot Chat — Responsible use / Bing search](https://docs.github.com/en/copilot/responsible-use/chat-in-your-ide)
- [Cline 3.48 release post — Skills and WebSearch](https://cline.bot/blog/cline-3-48-0-skills-and-websearch-make-cline-smarter)
- [Continue.dev Context Providers (deprecated @web note)](https://docs.continue.dev/customize/deep-dives/custom-providers)
- [Aider — Optional steps (Playwright/scraping)](https://aider.chat/docs/install/optional.html)
- [Cursor Docs](https://cursor.com/docs)
- [Windsurf Docs — Web and Docs Search](https://docs.windsurf.com/windsurf/cascade/web-search)
- [SearXNG GitHub](https://github.com/searxng/searxng) · [Whoogle GitHub (LICENSE MIT)](https://github.com/benbusby/whoogle-search/blob/main/LICENSE) · [Stract GitHub (archived)](https://github.com/StractOrg/stract) · [Marginalia API](https://about.marginalia-search.com/article/api/)
- [Plausible — why we switched to AGPL](https://plausible.io/blog/open-source-licenses) · [SSPL Wikipedia (MongoDB precedent)](https://en.wikipedia.org/wiki/Server_Side_Public_License)
