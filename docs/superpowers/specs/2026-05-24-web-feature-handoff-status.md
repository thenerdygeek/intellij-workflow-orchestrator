# Web Feature (web_fetch + web_search) — Handoff to "Production-Secure" Push

**Status:** Release-candidate. `v0.86.0-web-rc1` shipped 2026-05-24 with all 20 audit fixes + JCEF/UI Opus review fix + CVE-clean dep attestation. Belt-and-suspenders §5 items (5.6-5.9) and Windows smoke (5.10) still pending — see [§13](#13-status-update-after-aggressive-minimum-push-2026-05-24).

**Date:** 2026-05-24 (updated after aggressive-minimum push)
**Branch:** `worktree-web-fetch-search` @ `6b68037cc` — **67 commits ahead of `bugfix`**, pushed to `origin/worktree-web-fetch-search`
**Worktree path:** `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.claude/worktrees/web-fetch-search/`
**Tests:** 1075 (`:core`) + ~118 (`:web`, +5 new safeLabel cases) + 3871 (`:agent`) ≈ **5064 passing** (run modules individually with `--dependency-verification lenient`)
**Latest release:** [v0.86.0-web-rc1](https://github.com/thenerdygeek/intellij-workflow-orchestrator/releases/tag/v0.86.0-web-rc1) — release candidate. Supersedes `v0.85.38-web-beta` (interim) and `v0.85.37-web-beta` (STALE — do not install).

---

## 1. What this feature is

Two new agent tools in the Workflow Orchestrator IntelliJ plugin:

- **`web_fetch`** — fetches a URL and returns sanitized text via an 8-stage security pipeline (URL screen → shortener resolution → SSRF guard → allowlist → per-call approval → HTTP GET with auth-strip + redirect re-screen + streaming size cap → jsoup structural sanitization → sanitizer subagent semantic sanitization).
- **`web_search`** — pluggable provider model (SearXNG / Brave / Tavily / CustomHttp), no built-in default enabled. 5-stage pipeline (provider gate → query screen with token redaction → provider resolution + URL screen → provider call → result normalization + batched sanitizer subagent).

Positioning: "extremely secure" for the developer-IDE threat model (developer using it on docs/SO/MDN/GitHub, NOT a system processing untrusted external input at scale).

---

## 2. Where the artifacts live

| Artifact | Path |
|---|---|
| **Spec** | `docs/superpowers/specs/2026-05-23-web-fetch-search-design.md` (438 lines) |
| **Implementation plan + Sonnet-review revisions** | `docs/superpowers/plans/2026-05-23-web-fetch-search-plan.md` (~2920 lines) |
| **2026 landscape research** | `docs/research/2026-05-24-web-search-research.md` |
| **First code review (Opus)** | `/tmp/branch-code-review.md` (227 lines, 3 blockers + 6 important — ALL FIXED) |
| **Second code review (Opus, post-fix-1)** | `/tmp/branch-security-audit-2.md` (227 lines, 3 blockers + 8 important — ALL FIXED) |
| **This handoff** | `docs/superpowers/specs/2026-05-24-web-feature-handoff-status.md` |

The `/tmp/*.md` review files do NOT persist across reboots. Either commit them or copy to a permanent location before relying on them.

---

## 3. Journey to current state

1. Brainstorming → spec (`d45ef45e6`, 438 lines).
2. Implementation plan written (`372868b69`, 2548 lines).
3. **Sonnet pre-impl review of plan** caught 3 blocking + 6 important issues; folded into plan as revisions R1–R9 + N1–N4 (`c2f10dd97`).
4. Implementation via subagent-driven dev — 23 tasks across PR 1 (web_fetch) + PR 2 (web_search).
5. 5 test-gap closures (gaps 1–7 from a post-impl audit by me).
6. 3 build/security bug fixes (markdown JSON parse, Gradle implicit-dep, sandbox isolation).
7. Tavily provider added (`f4ad3b72d`).
8. **First Opus code review** of landed code found 3 blockers + 6 important (B1–B3, I1–I6). **All fixed**.
9. Conditional registration (`79d6a5f78`) — settings toggles now fully gate tool registration + prompt sections.
10. Prompt coverage expansion (`12fa55644`, `646ab4a91`) — tool descriptions + workflow + error-recovery guidance.
11. Released as `v0.85.36-web-beta` then `v0.85.37-web-beta` for cross-platform testing.
12. **Second Opus code review** (fresh eyes on post-fix-1 state) found 3 blockers + 8 important (S1–S3, I7–I14). **All fixed** in commits `dccf1c0de`–`b35fe2d0e`.

**Total: 20 review findings across two independent Opus passes — all 20 fixed.**

---

## 4. What "production-secure" still requires

Per my honest assessment to the user before pausing: two LLM reviews are not a guarantee of "zero security issues exist." The reviews surfaced what they were positioned to find; categories below were NOT specifically covered.

### 4.1 NOT covered by either Opus review

| Category | Why it matters | Suggested action |
|---|---|---|
| **JCEF / Chromium surface for web tools** | The chat UI uses JCEF. The approval dialog, allowlist editor, and audit log viewer render via React in JCEF. Reflected user input through the JS↔Kotlin bridge can be XSS-adjacent. The agent prompt teaches the LLM to trust the `<external_content>` wrapper — what if a webpage's content reaches the React side and gets rendered as HTML rather than escaped? | Dedicate one Opus review pass focused on `agent/webview/src/components/` for web-related rendering, the `bridge/jcef-bridge.ts` calls relevant to web tools, and the `WebSettingsConfigurable` → JS data flow. |
| **Static analysis (Semgrep / SonarQube / CodeQL) on `:web`** | LLM reviews find different bugs than static analysis. SA catches uninitialized fields, broken cleanup paths, format-string-style issues, dataflow leaks. | Run Semgrep (free) against `:web` with the `kotlin` ruleset + the `kotlin-injection` and `crypto-rules` packs. ~10 min. |
| **Dependency CVE scan** | jsoup 1.17.2, Moshi 1.15.1, OkHttp 4.x. SHA-256 verification locks WHAT version we ship; doesn't audit the version. | Run `./gradlew dependencyCheckAnalyze` (OWASP Dependency-Check plugin) or equivalent. |
| **File permission posture** | Audit log at `~/.workflow-orchestrator/<proj>/agent/logs/web-audit.log` — what's its umask? On a shared machine, is it readable by other users? Same for the per-session attachments + tool-output dirs. | Audit `Files.createDirectories` / `Files.writeString` call sites for explicit `PosixFilePermission` setting. Document the posture. |
| **Error message info-leak** | `URL_BLOCKED_IPV4_LOOPBACK` confirms a private address resolves at the user's host — info-leak in adversarial multi-tenant environments. | Decide threat model: if multi-tenant matters, generalize error codes (URL_BLOCKED only, no reason). If single-user IDE, current verbosity is fine for debug. |
| **Subagent escape audit** | Sanitizer subagent is constructed with `coreTools = emptyMap()`. Is that ACTUALLY enforced by `SubagentRunner`, or can a jailbroken sanitizer reach IDE services via reflection / coroutine context inheritance / etc.? | Read `SubagentRunner.kt` ctor and dispatch path with intent. Add a test pinning "empty tool map means zero tool calls succeed regardless of XML the sub-agent emits." |
| **Slow-loris DoS** | Read timeout is 30s. A server that drips bytes below that pace stalls the agent for 30s per fetch. | Either accept (developer tool, low value target) or add a minimum-bytes-per-second floor. |
| **TOCTOU between approval and fetch** | User clicks "Allow once" on `https://docs.example.com/page`. Between dialog close and fetch start, what changes? The URL is captured at approval time — verified. But settings changes (e.g. `webMaxBytes` lowered) could affect the in-flight call. | Audit which settings are read at fetch START vs at each iteration. Document and pin with tests. |
| **JCEF settings → Kotlin path** | `WebSettingsConfigurable.apply()` writes to `PluginSettings.state` + PasswordSafe. If a malicious value reaches `webSearchCustomUrl` via a settings-import JSON or registry tampering, does the validation chain catch it? | Add a fuzz pass on the settings JSON import path. |

### 4.2 Things the user can do but I can't

- **Manual smoke test on Windows** — verifies real install + UI render + path semantics (`%USERPROFILE%` vs `$HOME`) + audit log location.
- **External pen test** — definitive but slow. Worth it only if the deployment context is genuinely adversarial.
- **Real-network smoke of Brave + Tavily providers** — user opted to skip earlier; only SearXNG verified live. Easy to do later with free-tier API keys.

---

## 5. Concrete production-secure checklist

In order of cost-to-value:

- [ ] **5.1 Cut `v0.85.38-web-beta`** with the 11 audit fixes. The current GitHub release is dangerous to install. **(~3 min)**
- [ ] **5.2 JCEF/UI-focused Opus review** — see §4.1 row 1. **(~20 min)**
- [ ] **5.3 Apply any fixes from 5.2** — depends on what's found.
- [ ] **5.4 Run Semgrep** on `:web` with kotlin rulesets. **(~10 min + triage)**
- [ ] **5.5 Run OWASP Dependency-Check** or equivalent CVE scan on jsoup/Moshi/OkHttp. **(~5 min)**
- [ ] **5.6 File-permission audit** on audit log + session dirs (`~/.workflow-orchestrator/...`). **(~15 min)**
- [ ] **5.7 SubagentRunner empty-tool-map enforcement** — add the regression test pinning that a sanitizer with `coreTools = emptyMap()` cannot dispatch ANY tool regardless of emitted XML. **(~20 min)**
- [ ] **5.8 Settings-TOCTOU audit + tests** — see §4.1 row 8. **(~30 min)**
- [ ] **5.9 Decide threat model on error-message verbosity** (§4.1 row 5) — generalize or document the choice.
- [ ] **5.10 Manual Windows smoke** — ONLY user can do this.
- [ ] **5.11 Cut release with all production-secure fixes folded in** (probably `v0.86.0-web-rc1` for "release candidate" framing if the leap feels right).
- [ ] **5.12 Decide merge strategy** — `worktree-web-fetch-search` → `bugfix` or directly to `main`? Open a PR for human review? Long-running feature branch?

If you want to be aggressive, **5.1 + 5.2 + 5.5 + 5.10 + 5.11** gets you to a credibly production-secure state. The rest are belt-and-suspenders.

---

## 6. Key invariants to NOT break

Things that took multiple iterations to get right; don't regress:

1. **`StripAuthHeadersInterceptor` on fetch client ONLY, NOT search client.** Plan revision R5 + post-impl B1 fix. Search providers authenticate via headers (`X-Subscription-Token`, etc.); auth-strip would break them.
2. **OkHttp `followRedirects(false)` + manual redirect loop with `UrlPipeline.run(...resolveShorteners=false)` per hop.** Plan revision R3. Default OkHttp behaviour is SSRF-vulnerable.
3. **`PinnedDns` is per-CALL, not per-client.** S1 fix. A shared client with shared cache lets stale entries persist beyond DNS TTL window.
4. **`UrlPipeline` iterates ALL resolved addresses, not just first.** S1 fix. Multi-A-record domains can have one safe + one unsafe.
5. **Sanitizer subagent constructed with `coreTools = emptyMap()`, `maxIterations = 1`, `planMode = false`.** Plan revision R1. Any expansion lets jailbroken content do more damage.
6. **`SubagentSpawnerAdapter` JSON parser handles markdown-fenced + prose-prefixed output.** Bug-fix commit (post-Sonnet review). LLMs return JSON in various wrappers; parser must be resilient.
7. **Sanitizer `Verdict.UNRECOGNISED` fails closed via `WebError.SanitizerRefused`.** I1 fix. Default-SAFE would let jailbroken sanitizers slip text through.
8. **Allowlist `matchesDomain` requires leading-dot or exact, case-insensitive.** S2 fix. Bare `endsWith` lets attacker-similar-domains bypass.
9. **`<external_content>` / `<external_search>` wrappers escape close-tag injection in `cleaned_text` AND in `finalUrl` attribute.** I10 fix. Without escape, jailbroken content breaks out of the wrapper the system prompt instructs the LLM to trust.
10. **CancellationException re-thrown in EVERY catch in `:web/main`.** I13 fix. The pre-commit hook bans `runBlocking` but doesn't catch swallowed cancellation.
11. **Conditional registration: tools NOT registered when both settings off; no capabilities hints; no External Content Trust section.** `79d6a5f78`. The user explicitly wants "off = invisible."
12. **`WebFetchEngine` is a non-`@Service` class taking 8 ctor params; `WebFetchServiceImpl` is the `@Service` thin facade.** Plan revision R9. IntelliJ `@Service` only injects `Project` + `CoroutineScope`.

---

## 7. Test commands

```bash
# Individual module tests (combined invocation works post-sandbox-isolation fix but slower)
./gradlew :core:test --dependency-verification lenient
./gradlew :web:test --dependency-verification lenient
./gradlew :agent:test --dependency-verification lenient

# Live SearXNG smoke (requires Docker Desktop + SearXNG container)
docker run -d --rm -p 8888:8080 -v /tmp/searxng-config:/etc/searxng searxng/searxng
./gradlew :web:test --tests "*SearXNGLiveSmokeTest*" -Dsearxng.live.url=http://localhost:8888 --dependency-verification lenient

# Build the plugin distribution
./gradlew clean buildPlugin --dependency-verification lenient

# Full verifier (pre-existing warnings; not regressions from this branch)
./gradlew verifyPlugin --dependency-verification lenient
```

---

## 8. Release process (per `feedback_release_process.md`)

```bash
# 1. Bump version in gradle.properties (patch segment + -web-beta suffix; rcN for release candidate)
sed -i '' 's/pluginVersion = .*/pluginVersion = 0.85.38-web-beta/' gradle.properties
git add gradle.properties && git commit -m "chore(release): bump pluginVersion to 0.85.38-web-beta"

# 2. Clean build
./gradlew clean buildPlugin --dependency-verification lenient

# 3. Push branch + tag
git push origin worktree-web-fetch-search
git tag v0.85.38-web-beta
git push origin v0.85.38-web-beta

# 4. Create pre-release with notes
gh release create v0.85.38-web-beta --prerelease \
  --title "v0.85.38-web-beta — <headline>" \
  --notes "..." \
  build/distributions/intellij-workflow-orchestrator-0.85.38-web-beta.zip
```

**No Co-Authored-By trailer** in commits per `feedback_no_coauthor.md`.

---

## 9. User preferences (from `~/.claude/.../memory/MEMORY.md`)

Most relevant:

- `feedback_sonnet_for_small_tasks.md` — Sonnet for mechanical; Opus for design/ambiguity (canonical default).
- `feedback_opus_max_effort_subagents.md` — Exploration/research/verification = Opus with max effort.
- `feedback_dont_overgeneralize_model_choice.md` — Per-dispatch model instructions ("use opus this time") are ONE-OFF, not permanent rules.
- `feedback_no_coauthor.md` — No "Co-Authored-By Claude" trailer in commits.
- `feedback_explain_with_analogies.md` — Plain-English explanations + everyday analogy + concrete plugin scenario for unfamiliar terms.
- `feedback_skip_subagent_reviews.md` — Skip reviewer subagents between implementer tasks (the user explicitly opted IN for the two Opus security reviews on this feature — that was an exception worth repeating for security-critical work).
- `feedback_always_subagent.md` — Default to subagent-driven-development for plan execution.
- `feedback_no_parallel_same_tree.md` — Sequential implementer dispatch on the same working tree (no parallel).
- `project_runblocking_ban_pre_commit_hook.md` — `block-runblocking.sh` rejects `kotlinx.coroutines.runBlocking` in `main/`. Use `runBlockingCancellable` for blocking suspend calls on background threads.

---

## 10. Known limitations / acknowledged deferrals

- **`ApprovalDialogIntegrationTest`** is `@Disabled` — IntelliJ `HeadlessDialogFixture` not wired for `:web`. Service-level approval is fully covered via the `ApprovalGate` interface mocking.
- **`WebSettingsConfigurable` / `AllowlistEditorPanel`** UI rendering is not unit-tested — pure Swing scaffolding; bound fields covered indirectly.
- **`LlmBrainFactory.createForSanitization`** not integration-tested against a live Sourcegraph backend — CI has no network. Behavior verified via the `ModelCache.pickHaiku` → `pickSonnetNonThinking` → fallback chain logic.
- **Brave + Tavily live tests** — user opted out earlier; SearXNG only verified live. Easy to add with free-tier keys.
- **Editor-pane streaming for fetched content** — deferred to v2 per spec §8 "Open items".
- **Per-domain rate limits** — v2 per spec §8.
- **Caching layer for fetch responses** — v2 per spec §8 (cache-poisoning risk surface).
- **Status-bar widget sourced from `WebFetchServiceImpl.StateFlow<WebStats>`** — v2 per spec §8.

---

## 11. Quick reference: the 20 fixed findings

| Round | # | Commit | Class |
|---|---|---|---|
| 1 | B1 | `e96dfe565` | Auth header strip broke search |
| 1 | B2 | `a8461d724` | Subdomain glob `*.com` |
| 1 | B3 | `9a4070048` | Provider URL SSRF |
| 1 | I1 | `76aa90af0` | Sanitizer fail-open on unknown verdict |
| 1 | I2 | `eea3c6fd2` | Enable toggles were dead UI |
| 1 | I3 | `bac84ae8d` | Content-Type prefix sniff |
| 1 | I4 | `efd9411d0` | Audit log rotation |
| 1 | I5 | `a7b74a9b0` | Search hardcoded `brainId = null` |
| 1 | I6 | `cfa57fb7f` | Approval dialog missing context |
| 2 | S1 | `dccf1c0de` | DNS rebinding TOCTOU |
| 2 | S2 | `de619d2d9` | Allowlist `endsWith` bypass |
| 2 | S3 | `5ed9b25a8` | Approval dialog dead checkboxes |
| 2 | I7 | `57ee95441` | Allowlist read-modify-write race |
| 2 | I8 | `da9ff1556` | Provider response size cap (OOM) |
| 2 | I9 | `73c0551cb` | Sanitizer boundary attack |
| 2 | I10 | `1d2d81e54` | `<external_content>` boundary attack |
| 2 | I11 | `e2e969c1c` | CustomHttp `{query}` in host SSRF |
| 2 | I12 | `021ce2916` | ShortenerResolver no SSRF + no auth-strip |
| 2 | I13 | `58ba43ae6` | CancellationException swallowed |
| 2 | I14 | `b35fe2d0e` | Multi-window audit log race |

---

## 12. First-thing-to-read for the new session

1. **This file** — `docs/superpowers/specs/2026-05-24-web-feature-handoff-status.md` (you're reading it).
2. **Spec** — `docs/superpowers/specs/2026-05-23-web-fetch-search-design.md`.
3. **Second review report** — `/tmp/branch-security-audit-2.md` (commit to a permanent path if needed; sections 4 + 5 list what each fix addressed and what's still UNCOVERED).
4. **Browse the actual code** — start from `WebFetchEngine.kt` and `WebSearchEngine.kt`; the rest is wiring.

If a fresh agent is picking this up: load `agent/CLAUDE.md` and `core/CLAUDE.md` first for module conventions, then this handoff.

---

## 13. Status update after aggressive-minimum push (2026-05-24)

Following the `Doc's aggressive minimum` scope (5.1 + 5.2 + 5.5 + 5.11), this session shipped:

- **5.1 SHIPPED** — [`v0.85.38-web-beta`](https://github.com/thenerdygeek/intellij-workflow-orchestrator/releases/tag/v0.85.38-web-beta) — interim release with the 11 audit fixes (commits `dccf1c0de`…`b35fe2d0e`) superseding the stale `v0.85.37-web-beta`.
- **5.2 SHIPPED** — Focused JCEF/UI Opus review (sub-agent transcript in completed-tasks logs). Result: **0 blockers + 0 important in React/JCEF/bridge path**; the `<external_content>`/`<external_search>` wrapper escaping (invariant #9) verified; `JBCefJSQuery` all uses `JSON.parse` (no `eval`); `dangerouslySetInnerHTML` in `ToolCallChain.tsx:253` only fires when Shiki maps a language for the tool, which `WebFetch`/`WebSearch` do not. **1 IMPORTANT in Swing `ApprovalDialog`** → fixed in commit `de7d2cab6` (added `ApprovalDialog.safeLabel(value, max=200)` helper that collapses whitespace + truncates + HTML-escapes; pinned by 5 new `ApprovalDialogHelpersTest.safeLabel_*` cases). Two NITs (originalUrl control-char normalisation, JsEscape pinning comment) — first one folded into `safeLabel`, second deferred (no fix needed, `JCEF executeJavaScript` path is exclusive).
- **5.5 SHIPPED** — OWASP dep-CVE scan via OSV.dev against the actually-shipped JARs (not just `libs.versions.toml` declarations). **All 5 production deps CVE-clean**: `okhttp:4.12.0`, `okio-jvm:3.7.0` (transitive — moshi forces 3.7.0 over okhttp's 3.6.0), `jsoup:1.21.2` (Gradle resolves higher than declared 1.17.2 — trap caught by scanning the JAR not the catalog), `moshi:1.15.1`, `moshi-kotlin:1.15.1`.
- **5.11 SHIPPED** — [`v0.86.0-web-rc1`](https://github.com/thenerdygeek/intellij-workflow-orchestrator/releases/tag/v0.86.0-web-rc1) — release-candidate framing with ApprovalDialog hardening + posture statements (dep-CVE clean + JCEF/UI review attestation).

**Still pending from §5:**

- **5.6** File-permission posture audit on `~/.workflow-orchestrator/<proj>/agent/logs/web-audit.log` + session dirs.
- **5.7** `SubagentRunner` empty-tool-map enforcement regression test.
- **5.8** Settings-TOCTOU audit + tests (which settings are read at fetch START vs at each iteration).
- **5.9** Error-message verbosity threat-model decision (info-leak in multi-tenant — generalise or document the choice).
- **5.10** Manual Windows smoke — **only the user can do this**.
- **5.12** Merge strategy — `worktree-web-fetch-search` → `bugfix` or directly to `main`? PR for human review? Long-running branch?

The previously-noted `buildSearchableOptions` warning (`Workflow Orchestrator requires plugin 'com.workflow.orchestrator.web' to be installed`) is a benign headless-mode false positive: the ZIP at `build/distributions/` correctly bundles `intellij-workflow-orchestrator.web.jar` (250 KB) alongside the other module JARs. Verified by `unzip -l` on the produced artifact.
