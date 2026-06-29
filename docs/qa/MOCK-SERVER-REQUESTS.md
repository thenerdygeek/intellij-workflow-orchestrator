# Mock-Server Requests & Testing-Setup Gaps

Maintained by the QA cowork agent. Each entry: what was being tested → what the plugin/mock did → what was needed → suggested change.

---

## REQ-1 — ✅ RESOLVED (2026-06-26): plugin SSRF guard rejected all localhost/private URLs → mock unreachable

**Resolution:** Fixed in plugin commits **44f62ed20** (dev-only SSRF escape hatch — runIde sandbox sets `-Dworkflow.orchestrator.allowPrivateUrls=true`, accepting localhost/127.0.0.1/private URLs in the sandbox only; production keeps full SSRF protection) and **45ebc3783** (`ConnectionsConfigurable.apply()` now throws a visible `ConfigurationException` and preserves tokens instead of silently dropping all saves). Verified live: all five `localhost` connectors save and show *configured*; `Stored and verified credential` logged for all five; Agent integration runs end-to-end (RESULTS Part 3). No mock change was required — this was purely a plugin fix.

~~**Status:** BLOCKS the entire mock integration (Priority 1 and all connector tabs).~~ (original report retained below for history)
**Found:** 2026-06-26, plugin v0.87.2 (sandbox build 12:43), against mock per `MOCK-SERVER-COWORK-HANDOFF.md`.

**What I was testing:** Pointing the plugin at the mock (`Sourcegraph http://localhost:8088`, Jira/Bamboo/Bitbucket/Sonar on :8180/:8280/:8480/:8380) per the handoff, to prove the Agent integration.

**What happened:** Connections never persist for the mock. After entering the URLs + token `mock` and clicking **Apply**, the page stays "modified" (Apply never greys out) and the root **Connection Status** shows SonarQube and Sourcegraph "not configured". `Test Connection` for Sourcegraph returns connection-refused even when the mock is up.

**Root cause (source + log confirmed):**
- `core/security/BaseUrlValidator.kt` is an SSRF guard that **rejects** `localhost`, `::1`, `127.0.0.0/8`, link-local `169.254/16`, and RFC-1918 private ranges (`10/8`, `192.168/16`, `172.16-31/12`), and it **DNS-resolves hostnames** and rejects any that resolve to those ranges (`validate()` lines 54-107, `literalBlockedReason` 117-140, `blockedAddressReason` 147-153). Only a host resolving to a **public** address — or a hostname that fails to resolve entirely (→ `SoftWarning`, non-blocking) — is allowed.
- `core/settings/ConnectionsConfigurable.kt` `apply()` (lines 122-190) validates Jira/Bamboo/Bitbucket/Sonar URLs and on the **first `Invalid`** does `return` — aborting the *entire* apply **before** `pendingTokens` are saved and **before** `pendingTokens.clear()`. So: tokens never persist, and `isModified()` stays true forever.
- **Log oracle:** `idea.log` repeatedly logs `[Settings:Connections] Jira URL rejected by SSRF guard: Loopback address 'localhost' is not allowed (SSRF risk).` on each Apply (7+ times).

**Why I can't work around it from the mock/host side:** loopback, 127.0.0.1, and LAN IPs are all blocked; an `/etc/hosts` alias still resolves to loopback → blocked. There is **no dev/test bypass** (no Registry key, system property, or `isInternal` check) in `validate()`.

**What's needed (pick one):**
1. **Plugin dev/test escape hatch (preferred for QA).** Honor a bypass in `BaseUrlValidator.validate()` — e.g. a Registry key `workflow.orchestrator.allowLoopbackUrls` or system property `-Dworkflow.orchestrator.disableSsrfGuard=true`, or skip the loopback/private checks when `ApplicationManager.getApplication().isUnitTestMode || isInternal`. Then rebuild + relaunch `runIde`. Lets QA point at `localhost:*` directly.
2. **Public tunnel (no plugin change, usable today).** Expose the mock ports via a tunnel that yields a public hostname, e.g. `cloudflared tunnel --url http://localhost:8088` → `https://xxxx.trycloudflare.com`. That host resolves to a public IP → passes the guard. Put the tunnel URL in Settings instead of `localhost:8088`. Needs a tunnel per service actually exercised (at minimum Sourcegraph :8088; add Bitbucket :8480 for the PR tab).
3. **Mock/handoff doc update.** The handoff's "set Sourcegraph URL `http://localhost:8088`" cannot work against a build with the SSRF guard. The mock README/handoff should call this out and document the tunnel recipe (or require the plugin dev-bypass build).

**Separate plugin bug worth filing regardless of the mock** (see RESULTS Part 2 → BUG-CFG-1): `ConnectionsConfigurable.apply()` silently drops **all** credential saves (and leaves the panel permanently "modified") when any one URL is rejected, instead of saving the valid ones / raising a blocking `ConfigurationException` the user can see in the dialog. The only signal is a transient notification + a log WARN.

---

## REQ-2 — 🟡 Mock returns SSE for ALL chat requests; breaks the plugin's non-streaming title-generation call

**Status:** Non-blocking (the main agent chat works), but it throws a `SEVERE` and pops an "IDE error occurred" balloon on every agent completion.
**Found:** 2026-06-26, fixed build, during the Priority-1 integration run.

**What I was testing:** A plain agent message (default `read-and-finish` scenario). The agent itself streamed and rendered correctly.

**What happened:** On completion, the plugin generates a session title via a **non-streaming** LLM call (`HaikuPhraseGenerator.evaluateTitleFromCompletion` → `SourcegraphChatClient.sendMessage` at `SourcegraphChatClient.kt:836`, which does `Json.decodeFromString<ChatCompletionResponse>(body)`). The mock answered that request with an **SSE body** (`data: {"id":"chatcmpl-mock-590…`), so the non-streaming decoder failed:
```
SEVERE …SourcegraphChatClient — [Agent:API] Unexpected error: Unexpected JSON token at offset 0:
Expected start of the object '{', but had 'd' instead at path: $
JSON input: data: {"id":"chatcmpl-mock-590…
```
(idea.log:31850; HTTP was `code=200`, body bytes streamed.)

**What the plugin needed vs. what the mock did:** The plugin's title-gen path issues a **non-streaming** chat completion (expects a single `ChatCompletionResponse` JSON object). The mock's `/.api/llm/chat/completions` **always streams** regardless of the request's `stream` flag.

**Suggested change (mock):** Honor non-streaming requests — if the request body has `"stream": false` (or no `Accept: text/event-stream`), return a single `application/json` `ChatCompletionResponse` object (id/choices[].message.content/usage) instead of an SSE stream. That unblocks `HaikuPhraseGenerator` (and any other non-streaming caller).

**Also a plugin-side note (for the maintainer, not the mock):** `SourcegraphChatClient.sendMessage`/`HaikuPhraseGenerator` could be made resilient to receiving an SSE body (strip `data:` lines / fall back) so a streaming server doesn't surface a user-visible "IDE error occurred". Tracked as BUG-AGENT-2 in RESULTS Part 3.

---

## REQ-3 — 🟡 `plan-mode` scenario uses `enable_plan_mode` and finishes; doesn't drive the plugin's Approve→Act plan flow

**Status:** Non-blocking, but it means the plan-mode UI (plan card → **Approve** → act → PlanApprovedBubble; test-plan HDR-19…24) can't be exercised via the built-in scenario.
**Found:** 2026-06-26, fixed build. Prompt `[plan-mode] …` → mock served the plan scenario (JSONL shows an `enable_plan_mode` tool_call) but the agent ran straight to `TASK COMPLETED` with no plan card / Approve button / suspension.

**What the plugin needs:** Per `agent/CLAUDE.md` (Plan Mode), the plugin enters plan mode and **suspends** the loop, waiting for the user to click **Approve** (only the user switches plan→act). The LLM is expected to emit **`plan_mode_respond`** (with `needsMoreExploration=false`) to trigger the suspend, not `enable_plan_mode` followed by other tools.

**Suggested change (mock):** Rework the `plan-mode` scenario so it emits a turn whose tool call is **`plan_mode_respond`** (the plan content) and then **stops** (no further scripted turns / no `attempt_completion`), so the loop suspends on the Approve gate. The tester then clicks Approve to resume into the act phase. Optionally provide a follow-up turn that the mock plays only after approval.

**Minor render note (plugin):** `enable_plan_mode` produced **no visible tool card** in the chat (executed silently). Consistent with S2's theme (tools missing from `CATEGORY_MAP`). Low priority.

---

## REQ-4 — 🟡 `multi-tool` scenario's `run_command` is unscoped (`grep -rn TODO .`) → repo-wide scan, floods UI/log

**Status:** Non-blocking, but it makes the `multi-tool` scenario unusable on a real repo and masks/triggers other bugs.
**Found:** 2026-06-27, driving `[multi-tool]`.

**What happened:** the scenario's 2nd turn runs `run_command{"command":"grep -rn TODO . || true"}` (`ScenarioLibrary.kt:186`). From the repo root that recurses `.git/`, `.worktrees/` (multiple full worktrees!), `node_modules/`, `build/` → thousands of matches (3258+ lines and climbing), 16 KB-chunk flushes into the chat, the same output mirrored into `idea.log`, and IDE "Indexing…" churn. It also ran long enough to expose BUG-STOP-1 (can't stop it) and GLITCH-1 (frozen timer).

**Suggested change (mock):** scope the demo command so it returns quickly and small, e.g. `grep -rn TODO src/ --include='*.kt' || true` or `grep -rn TODO README.md || true`. Same care for any other scenario that shells out from the repo root. (Plugin-side follow-ups are tracked as BUG-STOP-1 / GLITCH-1 / PERF-1 in BUG-REPRO-RESULTS.md.)

---

## REQ-5 — 🟡 Thinking is emitted as ONE instant SSE frame with no inter-frame delay → BUG-3's timing window is unreachable

**Status:** Non-blocking, but it **prevents live reproduction of BUG-3** (history thinking-block cross-append). The root cause is confirmed in plugin source; the mock just can't open a wide enough streaming window to catch it in the UI.
**Found:** 2026-06-27, driving custom `long-thinking` (600-line) and `huge-thinking` (4000-line) `<thinking>` scenarios.

**What happens:** `Turn.contentFragments()` (`scenario/Turn.kt:95-100`) wraps the entire `thinking` string in a **single** `<thinking>…</thinking>` fragment, and `CodySseSerializer` emits **one `deltaText` frame per fragment** (`CodySseSerializer.kt:34-38`). There is **no inter-frame delay** in the SSE path (grep `delay`/`sleep` → none). So even a 4000-line thinking block streams in one burst and the plugin renders it in <1 s. Meanwhile opening another history session in the UI takes ~3–4 s (async list load + 2-click expand→Resume). The stream always finishes before a second session can be displayed, so the cross-append (BUG-3) can't be observed live.

**What's needed (mock):**
1. **Chunk the thinking string** into many small `deltaText` frames (e.g. split on lines or ~80-char windows) instead of one fragment — so thinking streams progressively like a real model.
2. **Add a small per-frame delay** (configurable; ~30–50 ms default, or a `frameDelayMs` field on the custom-scenario turn) so a long thinking block streams over several seconds.

Either alone helps; both together give a comfortable window to navigate to another session mid-thinking and reproduce BUG-3. (Plugin-side, the durable fix is the `viewedSessionId` guard on the `appendToThinking` push at `AgentController.kt:518`, and/or a source UI test — see BUG-REPRO-RESULTS.md → BUG-3.)

---

## REQ-6 — 🟡 Mock Jira issue-link `type` omits `inward`/`outward` → plugin throws and the whole Sprint board fails to load

**Status:** Functional — breaks the Sprint dashboard data load on the mock. **Found:** 2026-06-29, Phase-1 split smoke (Sprint tab).

**What happens:** the mock Jira's search/issue JSON returns `fields.issuelinks[].type` **without** the `inward`/`outward` label strings. The plugin's `JiraIssueLinkType` (`jira/api/dto/JiraDtos.kt:82`) marks both as **required, non-nullable, no default**, so `JiraApiClient.getSprintIssues` deserialization throws `MissingFieldException: Fields [inward, outward] are required … at path $.issues[0].fields.issuelinks[0].type`, unhandled in `Dispatchers.IO` → the sprint board can't populate.

**Second manifestation (2026-06-29, A-alone run):** with slightly different mock data the same load fails one level deeper — `MissingFieldException: Field 'fields' is required for type 'JiraLinkedIssue' … at $.issues[0].fields.issuelinks[0].outwardIssue`. So the mock's `issuelinks[]` entries are incomplete in **two** ways: (a) `type` lacks `inward`/`outward`, and (b) `inwardIssue`/`outwardIssue` lack a `fields` object. Real Jira includes both.

**Suggested change (mock):** make every `issuelinks[]` entry complete — `type` with `inward`/`outward` (e.g. `"type": { "name": "Blocks", "inward": "is blocked by", "outward": "blocks" }`) **and** each `inwardIssue`/`outwardIssue` with at least a minimal `fields` object (`status`, `summary`, `issuetype`). Applies to any issue fixture carrying `issuelinks`.

**Plugin-side follow-up (not the mock):** harden `JiraIssueLinkType` so a missing `inward`/`outward` (or an unexpected link shape) can't abort the entire sprint-issues parse — make them nullable/defaulted or decode with `coerceInputValues`/`ignoreUnknownKeys`. Tracked in SPLIT-PHASE1-SMOKE-RESULTS.md → "NEW BUG".

---

## REQ-7 — 🟡 No non-Software (non-agile) Jira scenario to verify the Sprint-tab HIDE path

**Status:** Non-blocking; blocks one direction of Phase-1 Item 3 / split `WorkflowTabProvider.isAvailable`. **Found:** 2026-06-29.

**What's needed:** the mock Jira currently presents as a **Software/agile** project (board discovery succeeds, `Active sprint: Sprint 2026.11 (id=7)`), so the Sprint tab is correctly **shown**. To verify the **hidden-when-non-Software** branch, the mock needs a toggle/scenario (e.g. `/__admin` on 8180) where the agile/board capability probe returns **not-agile** (no boards / `404` on the agile API), so `JiraAgileCapabilityService` resolves non-Software and the Sprint tab disappears on tool-window rebuild.

---

## REQ-8 — 🟡 Mock Bamboo project/plan-listing 404s → can't add an automation suite → Phase-2 check 2.3 (`ManualStageDialog`) can't be reached

**Status:** Non-blocking, but it **blocks Phase-2 runIde check 2.3** (the ⭐ cross-plugin classloader test). **Found:** 2026-06-29, A+B runIde smoke, Bamboo configured to `http://localhost:8280`.

**What happens:** Bamboo *connects* ("Bamboo connected ✓"), but **Settings → Workflow Orchestrator → Automation → "Add suite by browsing Bamboo projects"** shows **Project: "Failed: …resource not found"** — the mock's Bamboo project-listing endpoint returns 404. With no projects/plans, no automation **suite** can be added, the Automation tab's **SUITE** dropdown stays empty, and **"Trigger Customized…"** has **no stages** to act on — so clicking it does nothing and the `ManualStageDialog` (the `:bamboo` class that B's automation panel calls across the plugin classloader) never opens. (No `NoClassDefFoundError` — the panel/menu load fine; there's simply no stage data.)

**Suggested change (mock):** add a Bamboo scenario that returns **at least one project → plan → with ≥1 stage** from the project/plan-listing endpoints the plugin queries (so a suite can be added and the plan exposes stages). Then: configure Bamboo → Settings → Automation → add the plan as a suite → Automation tab SUITE populates → **Trigger ▾ → "Trigger Customized…"** opens `ManualStageDialog`, completing check 2.3. (Until then, 2.3 stays **deferred**, per the run-sheet's "don't force it" guidance.)
