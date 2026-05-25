# Web Tooling Competitive Comparison & Gap Analysis

Date: 2026-05-24
Scope: web_fetch + web_search + egress filter across 8 agentic coding tools

## Tools surveyed

| Tool | Web fetch primitive | Search primitive | Browser/JS? |
|---|---|---|---|
| **Workflow Orchestrator** (this plugin) | `web_fetch` (OkHttp GET + jsoup + LLM sanitizer) | `web_search` (4 providers: SearXNG / Brave / Tavily / CustomHttp) | ❌ no |
| **Claude Code** | `WebFetch(url, prompt)` (server-side Anthropic fetch) | `WebSearch(query, allowed_domains, blocked_domains)` (Anthropic-internal backend) | ❌ no |
| **Cursor** | `@Web`, `@Docs`, `@Link` | `@Web` via Exa.ai | partial (server-side crawl) |
| **Aider** | `/web URL` | none | ✅ Playwright fallback when installed |
| **Continue.dev** | `@Url`, `@Docs` (RAG), `@Web`, `@Google` (Serper) | `@Google` | ❌ no |
| **Cline** | `fetch_web` + `browser_action` (Puppeteer) | `web_search` (3.48+) | ✅ Puppeteer Chromium |
| **Roo Code** (Cline fork) | `browser_action` only (Puppeteer) | MCP-supplied (Exa/Tavily/etc) | ✅ Puppeteer |
| **OpenHands** | `web_read` + `browse_url` + `browse_interactive` | Tavily (search/extract/crawl/map) | ✅ Playwright + AXTree + screenshots |
| **Windsurf Cascade** | `@web` automatic URL read | `@web` (provider undisclosed) | ❌ no |

## Feature matrix

Legend: ✅ first-class, ⚠ partial / opt-in, ❌ absent / not documented

| Feature | Ours | ClaudeCode | Cursor | Aider | Continue | Cline | Roo | OpenHands | Windsurf |
|---|:--:|:--:|:--:|:--:|:--:|:--:|:--:|:--:|:--:|
| **Outbound query deny-list (egress filter)** | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| **Outbound query LLM screener** | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| **DNS-pinned SSRF guard** | ✅ | n/a* | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| **Auth-header stripping** | ✅ | n/a* | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| **Domain allowlist (user-managed)** | ✅ | ✅ | ⚠ (`@Docs` opt-in) | ❌ | ⚠ (`@Docs` opt-in) | ❌ | ❌ | ❌ | ❌ |
| **Approval dialog per-domain** | ✅ | ✅ | n/a (`@`=consent) | ✅ (`/`=consent) | n/a | ✅ | ✅ | ⚠ | n/a |
| **Random-delim boundary defense** | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| **Structural + LLM dual sanitization** | ✅ | ✅ | ❌ | ⚠ (BeautifulSoup) | ❌ | ✅ | ✅ | ✅ | ⚠ |
| **Sanitizer "verbatim, no paraphrase" contract** | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| **Audit log (queryBeforeFilter + decision)** | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| **Page+prompt fusion** (fetch w/ extraction prompt) | ❌ | ✅ | ⚠ | ❌ | ❌ | ✅ | ✅ | ✅ | ⚠ |
| **Response cache w/ TTL** | ❌ | ✅ (15 min) | ❌ | ❌ | ❌ | ⚠ | ❌ | ❌ | ❌ |
| **Citations / `char_location` blocks** | ❌ | ✅ | ⚠ (link only) | ❌ | ⚠ (URL shown) | ❌ | ❌ | ❌ | ❌ |
| **URL-provenance gate** (model can only fetch URLs it saw) | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| **`max_uses` per-conversation budget** | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| **PDF extraction** | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ |
| **Robots.txt respect** | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| **JS rendering / SPA support** | ❌ | ❌ | ⚠ | ✅ (Playwright opt) | ❌ | ✅ | ✅ | ✅ | ❌ |
| **Browser automation** (click/type/scroll/screenshot) | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ | ✅ | ❌ |
| **Page screenshots for vision model** | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ | ✅ | ❌ |
| **Multi-page navigation** | ❌ | ⚠ (redirect only) | ❌ | ❌ | ❌ | ✅ | ✅ | ✅ | ❌ |
| **Indexed RAG over user-added URLs** | ❌ | ❌ | ✅ (`@Docs`) | ❌ | ✅ (`@Docs`) | ❌ | ❌ | ❌ | ⚠ (`@docs`) |
| **Trusted-sites fast-path bypass** | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ (~80 sites) | ⚠ | ❌ | ❌ |
| **Search-provider MCP plugability** | ⚠ (4 hardcoded) | ❌ | ⚠ (Brave via MCP) | ❌ | ✅ | ✅ (MCP) | ✅ (MCP) | ⚠ | ⚠ (MCP) |

`*` Claude Code's `n/a`: their fetch runs server-side at Anthropic, so client-side SSRF/auth-strip aren't applicable (different threat model — they own the network exit).

## What we have that nobody else has

Genuine differentiators on the security/governance axis:

1. **Outbound egress filter** (deny-list + LLM screener) — nobody screens outbound queries for proprietary identifiers. Every other tool happily sends `MyComp.class` or `jenkins.acme.corp` to Brave/Tavily.
2. **DNS-pinned SSRF defense** — closes the rebinding TOCTOU. Nobody else does this on the client side.
3. **Auth-header stripping interceptor** — defense-in-depth against credential leak.
4. **Random per-call delimiters (I9)** — boundary-attack defense for the sanitizer prompt. Unique pattern.
5. **"Verbatim, never paraphrase" sanitizer contract** — every other LLM-sanitizer either lets the LLM rewrite freely or doesn't have one. Our explicit anti-paraphrase guardrails are unique post-Phase-A.
6. **Audit log with `queryBeforeFilter` + `egressDecision`** — post-hoc "what did we almost send" forensics. Nobody else logs this.

## What we're missing

Ranked by recommended priority (security/value vs. effort):

### Tier 1 — High value, low-to-medium effort

1. **Response cache with TTL** (Claude Code's 15-min model)
   - Why: identical to a config tweak; LRU + sha256(url+max_bytes) → text. Cuts cost+latency materially for documentation-heavy sessions.
   - Effort: ~50 lines; reuse the existing `HttpResponseCache`-style infra in `:core`.

2. **`max_uses` per-conversation budget** (Claude Code pattern)
   - Why: circuit breaker independent of token budget. Stops runaway fetch loops the LoopDetector won't catch (different URLs each time).
   - Effort: ~30 lines; counter on `AgentLoop` keyed by `(sessionId, tool_name)`.

3. **Page+prompt fusion** (Claude Code `WebFetch(url, prompt)`)
   - Why: cheaper (Haiku pre-extracts; main model doesn't see 30K of irrelevant HTML), faster (one round-trip), better signal (extraction targeted to question).
   - Effort: ~100 lines; add optional `prompt` param to `web_fetch`, run extraction in the existing sanitizer subagent.

4. **Cite-as-data citations** (`char_location` blocks)
   - Why: provenance enforcement; the LLM is structurally required to point at the source for every claim.
   - Effort: medium; needs schema add on `ToolResult`, downstream rendering in chat. Could ship without UI as just text-format citations first.

### Tier 2 — High value, medium-to-high effort

5. **URL-provenance gate** (model can only fetch URLs it saw in context)
   - Why: orthogonal exfil defense — even if the LLM somehow constructs a malicious URL, the gate blocks it. Cheap to implement, complements the allowlist.
   - Effort: ~80 lines; scan recent context for URL substring before allowing fetch. Has false-positive risk if a URL was constructed from documented parts.

6. **PDF extraction** (Claude Code, OpenHands)
   - Why: PDFs are ubiquitous in docs; we currently reject as binary. Apache PDFBox (already a dep elsewhere) can extract.
   - Effort: medium; add to `JsoupReadability.sanitize()` content-type branch.

7. **JS rendering on demand** (Aider's Playwright pattern)
   - Why: SPAs (React docs, modern dashboards) are unreadable without it. Aider's "install Playwright optionally" model fits our user base.
   - Effort: high; bundle JBR-compatible Playwright or use existing JCEF Chromium. Carries footprint cost.

### Tier 3 — Lower priority

8. **Robots.txt respect** — etiquette, not security. Trivial in code (~40 lines), but no real consequences for ignoring it on a developer's own machine.
9. **Indexed `@Docs`-style RAG** — different surface; doesn't replace web_fetch. Significant scope (embedding store, refresh logic, query path).
10. **MCP search-provider plugability** — when MCP lands in the plugin, this becomes free. Don't build a parallel mechanism.
11. **Browser automation / screenshots** — big surface area, large dependency footprint, not aligned with the current "single shot fetch" design. Cline/OpenHands chose this differently because their whole agent loop is browser-driven.

## Recommended minimum addition

If you want to materially close the gap with one PR each:

- **PR 1 (Tier 1 #1+#2):** Response cache + `max_uses` budget. Maybe a 200-line diff. Big bang-for-buck.
- **PR 2 (Tier 1 #3):** Page+prompt fusion on `web_fetch`. ~150 lines. Compounds with the sanitizer subagent we already have.
- **PR 3 (Tier 2 #6):** PDF extraction. ~100 lines if PDFBox is already a dep.

URL-provenance gate (#5) is the security-flavored counterpart to those three but takes more design discussion about false-positives.

## Sources

Claude Code:
- https://platform.claude.com/docs/en/agents-and-tools/tool-use/web-fetch-tool
- https://code.claude.com/docs/en/permissions
- https://mikhail.io/2025/10/claude-code-web-tools/

Cursor:
- https://cursor.com/docs
- https://github.com/exa-labs/exa-cursor-plugin

Aider:
- https://github.com/paul-gauthier/aider/blob/main/aider/scrape.py

Continue.dev:
- https://docs.continue.dev/customize/custom-providers

Cline:
- https://docs.cline.bot/exploring-clines-tools/cline-tools-guide
- https://cline.bot/blog/cline-3-48-0-skills-and-websearch-make-cline-smarter

Roo Code:
- https://docs.roocode.com/advanced-usage/available-tools/browser-action

OpenHands:
- https://docs.openhands.dev/openhands/usage/advanced/search-engine-setup
- https://github.com/All-Hands-AI/OpenHands/pull/7457 (PDF/image via browser)

Windsurf:
- https://docs.windsurf.com/windsurf/cascade/web-search
- https://windsurf.com/security
