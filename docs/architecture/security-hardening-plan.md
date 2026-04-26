# Security Hardening Plan

**Branch:** `refactor/cleanup-perf-caching` (Phase 6 — security hardening, post-Phase-5)
**Source audit:** `/tmp/security-audit-report.md` (2026-04-26)
**Scope:** all HIGH + MED findings + cheap LOW items. Skip the audit/re-audit-only LOW (#9 — bridge re-audit) until evidence of a hit.

**Why this is its own phase, not a Phase 5b cleanup:** Phase 5b is the WorkflowContextService bridge teardown — purely additive cleanup. Security findings are correctness fixes that change tool behavior (LLM-supplied URL validation, shell-quoting model, markdown rendering pipeline). They need their own gate, their own tests, and their own commit boundary.

---

## Execution model

- **Branch:** continue on `refactor/cleanup-perf-caching` (per `feedback_work_on_current_branch.md`).
- **Subagent-driven** (per `feedback_always_subagent.md`): one implementer subagent per task + one `superpowers:code-reviewer` per task. Skip spec/quality reviewers (per `feedback_skip_subagent_reviews.md`).
- **Model selection** (per `feedback_sonnet_for_small_tasks.md`): Sonnet for mechanical tasks (T2, T4, T5); Opus max-effort for ambiguous design (T1, T6).
- **TDD per task** (per `feedback_real_tdd.md`): write the failing test from the threat model, then implement.
- **Docs same commit** (per `feedback_update_docs_immediately.md`): each task commits its docs touch alongside the code change.
- **No Co-Authored-By trailer.**

---

## Tasks

### T1 — HIGH — Strip raw HTML from markdown renderers (XSS surface)

**Files:**
- `agent/webview/src/components/markdown/MarkdownRenderer.tsx:179` (drop `rehypeRaw`)
- `agent/webview/src/components/plan/PlanDocumentViewer.tsx:175,223` (drop `rehypeRaw` from both render calls)
- `agent/webview/package.json` — add `rehype-sanitize` if not present; consider removing `rehype-raw` once all references are gone.

**Approach (B is the default; A is fallback if B turns out to be incompatible):**
- (B — DEFAULT) **Replace `rehype-raw` with `rehype-sanitize`** using the default schema (`defaultSchema` from `hast-util-sanitize`). Strips `<script>`, `<iframe>`, `<object>`, `onerror=`/`onload=`/etc. event handlers, `javascript:`/`data:` URLs in `href`, and `style=` attributes — but preserves the safe HTML subset LLMs actually use in long responses: `<details>`, `<summary>`, `<kbd>`, `<sub>`, `<sup>`, `<mark>`, `<abbr>`, `<dl>`/`<dt>`/`<dd>`, table structural attrs (`colspan`, `rowspan`), `<br>`, `<a>` with sanitized `href`. Bundle cost ~6 KB gzipped (rehype-raw was ~4 KB; net +2 KB).
- (A — FALLBACK) Drop `rehype-raw` outright. Smallest diff but loses every raw-HTML element listed above; agent responses that wrap long stack traces in `<details><summary>` would degrade to literal text. Only use this option if rehype-sanitize causes a concrete incompatibility with `streamdown` / `react-markdown` we can't work around.

**Why default to B:** the threat is raw HTML *execution*, not raw HTML *rendering*. Sanitization neutralizes the threat at exactly the boundary that matters (HAST tree), without amputating legitimate UX. LLMs (and Cline-style agent personas) routinely emit `<details><summary>` for collapsible context and `<kbd>` for shortcut hints — option A would silently regress those.

**Schema customization (if needed during implementation):** the default `hast-util-sanitize` schema is conservative — if a smoke test reveals a wanted element it strips (e.g., `<input type="checkbox" disabled>` for rendered task lists from `remark-gfm`), extend the schema rather than fall back to A. `remark-gfm` task-list checkboxes are typically already in the default schema; verify in implementation.

**Tests (write first):**

*Threat-model assertions (must fail before fix, pass after):*
- `MarkdownRendererTest`: render `Hello <script>window.__pwned=1</script> world` → assert `window.__pwned` is undefined and no `<script>` element exists in the rendered DOM (sanitize STRIPS the element entirely; it does not escape it as text — that's a difference vs option A).
- Render `<img src=x onerror="window.__pwned=1">` → assert no `<img>` element is present (or `onerror` attribute is stripped if `<img>` is in schema).
- Render `<a href="javascript:alert(1)">click</a>` → assert anchor renders but `href` is sanitized (rewritten to `#` or removed).
- Render `<iframe src="https://evil.com"></iframe>` → assert no `<iframe>` element exists.
- Render `<svg><script>alert(1)</script></svg>` → assert `<svg>` may render but `<script>` inside it is stripped.
- `PlanDocumentViewerTest`: same five assertions on both the body and the comment-thread render paths.

*Behavioral preservation (must pass after fix — these are the option-B-vs-A regression guards):*
- Render `<details><summary>Click</summary>Hidden text</details>` → assert `<details>` and `<summary>` elements are present in DOM.
- Render `Press <kbd>Ctrl+C</kbd>` → assert `<kbd>` element is present.
- Render `H<sub>2</sub>O` → assert `<sub>` element is present.
- Render a `remark-gfm` task list (`- [ ] todo`) → assert `<input type="checkbox" disabled>` renders (not stripped by sanitize schema).

**Exit criterion:** all five XSS payloads are neutralized (stripped or sanitized); all four behavioral-preservation tests pass (option B preserves the safe HTML subset); no other test in `agent/webview/` regresses; `npm run build` succeeds. Bundle delta is informational only (expect ~+2 KB net).

**Model:** Sonnet (mechanical refactor + targeted tests).

**Commit message:** `fix(webview): strip raw HTML from markdown renderers (XSS hardening)`

---

### T2 — HIGH — Move Sonar token off argv

**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/SonarTool.kt:346-349`

**Current shape (vulnerable):**
```kotlin
val command = "mvn sonar:sonar -Dsonar.token=$token -Dsonar.host.url=$sonarUrl ..."
ProcessBuilder("sh", "-c", command).start()
```

**Two problems:**
1. `$token` visible in `ps aux` for scan duration (local-user threat).
2. Branch name / sonarUrl interpolated into shell string → injection if branch name contains shell metacharacters.

**Fix:**
1. Pass token via `SONAR_TOKEN` environment variable (Sonar scanner reads it natively).
2. Switch to `ProcessBuilder` argv form: `ProcessBuilder("mvn", "sonar:sonar", "-Dsonar.host.url=$sonarUrl", "-Dsonar.branch.name=$branch", ...)`.
3. Validate `branch` against `^[a-zA-Z0-9._\-/]+$` regex before passing — defensive even though argv form prevents injection.

**Tests (write first):**
- `SonarToolTest`:
  - Capture the `ProcessBuilder` invocation (mock at `ProcessBuilder` factory boundary). Assert `command()` does NOT contain the substring `sonar.token=`.
  - Assert `environment()["SONAR_TOKEN"]` equals the test token.
  - Assert branch name `'foo; curl evil'` is rejected with `INVALID_BRANCH_NAME` before any process spawn.
  - Assert legitimate branch names (`feature/PROJ-123`, `release-1.2.3`) pass validation.

**Exit criterion:** existing Sonar scan integration test (if any) still green; new tests pass; `ps aux` smoke test (manual, document in commit msg) confirms token absent during a real scan.

**Model:** Sonnet.

**Commit message:** `fix(sonar): pass token via env var, use argv ProcessBuilder, validate branch name`

---

### T3 — MED — Extract `isRealmSafe()` to shared util + apply to `HttpReadinessProbe`

**Source of truth:** `automation/src/main/kotlin/com/workflow/orchestrator/automation/api/DockerRegistryClient.kt:196-229` (`isRealmSafe`). Per `feedback_read_working_code_first.md`, lift the working impl rather than rewrite.

**New file:** `core/src/main/kotlin/com/workflow/orchestrator/core/security/UrlSafetyGuard.kt`
- `fun isUrlSafe(url: String, allowLoopback: Boolean): Result<Unit>`
- For `HttpReadinessProbe` callers: `allowLoopback = true` (the legitimate use case is `http://localhost:PORT/health`).
- For `DockerRegistryClient` callers: `allowLoopback = false` (existing behavior).
- Rejects: link-local (`169.254.0.0/16`), AWS metadata (`169.254.169.254`), site-local (`10/8`, `192.168/16`, `172.16/12`) when `allowLoopback=false`, any-local (`0.0.0.0`, `::`), DNS-resolved IPs in those ranges.

**Files modified:**
- `core/src/main/kotlin/com/workflow/orchestrator/core/security/UrlSafetyGuard.kt` (new)
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/HttpReadinessProbe.kt` — call guard with `allowLoopback=true` before each probe attempt; return `READINESS_DETECTION_FAILED` with `URL_BLOCKED` reason on rejection.
- `automation/src/main/kotlin/com/workflow/orchestrator/automation/api/DockerRegistryClient.kt` — replace inline `isRealmSafe()` with `UrlSafetyGuard.isUrlSafe(realm, allowLoopback=false)`. Keep behavior byte-equivalent.

**Tests (write first):**
- `UrlSafetyGuardTest`:
  - `http://169.254.169.254/latest/meta-data` → rejected for both modes.
  - `http://localhost:8080/health` → ALLOWED with `allowLoopback=true`, REJECTED with `allowLoopback=false`.
  - `http://10.0.0.1/v2/` → REJECTED both modes.
  - `http://example.com/` (DNS-resolves to public IP) → ALLOWED both modes.
  - DNS-resolves-to-loopback case (`http://my-malicious-host.com/` resolving to `127.0.0.1`) → REJECTED both modes.
- `HttpReadinessProbeTest`: probing `169.254.169.254` returns `URL_BLOCKED` and never opens a socket.
- `DockerRegistryClientRealmSafetyTest`: existing realm-safety tests pass against the new shared guard (regression).

**Exit criterion:** existing `DockerRegistryClient` realm tests pass unchanged; new `HttpReadinessProbe` SSRF tests pass; no other module regresses.

**Model:** Opus (cross-module refactor + DNS resolution semantics need care).

**Commit message:** `feat(core): UrlSafetyGuard with SSRF allowlist; apply to HttpReadinessProbe`

---

### T4 — MED — Argv form for Java Maven/Gradle shell fallback

**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/JavaRuntimeExecTool.kt:1064-1067` (Maven/Gradle shell-fallback path)

**Current shape:** `sh -c "$command"` with command interpolating module path, test class name, methods list.

**Fix:** convert to `ProcessBuilder(listOf("mvn", "test", "-Dtest=$class#$methods", "-pl", modulePath))` argv form. No `sh -c`. No shell metacharacter interpretation.

**Considerations:**
- Maven Surefire `+`-separated multi-method param (`Class#m1+m2+m3`) survives argv form fine — it's a single argv element.
- Gradle `--tests` flag needs one entry per method when multi-method (already documented in `:agent` CLAUDE.md). Each becomes its own argv element.
- `BuildSystemValidator` paths still feed in but as argv elements, not shell tokens.
- If the executable is `mvnw` / `gradlew` (project-local wrapper), resolve absolute path before passing.

**Tests (write first):**
- `JavaRuntimeExecToolMavenTest`:
  - Mock the ProcessBuilder factory; assert `command()` is a list of 6+ elements, not a 3-element `sh -c "..."` shape.
  - Assert a maliciously-named test method (`testFoo; rm -rf /`) is rejected by the existing `METHOD_NAME_REGEX` BEFORE reaching the ProcessBuilder.
  - Assert legitimate multi-method invocation (`testFoo+testBar+testBaz`) survives as a single argv element.
- Same for Gradle.

**Exit criterion:** existing `run_tests` integration tests pass (no behavioral regression on legitimate inputs); injection regression tests (`testFoo; touch /tmp/pwned`) demonstrate no shell interpretation occurs.

**Model:** Sonnet.

**Commit message:** `fix(agent): argv ProcessBuilder for Maven/Gradle shell fallback (no sh -c)`

---

### T5 — MED — Path-traversal check in `CefResourceSchemeHandler`

**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/CefResourceSchemeHandler.kt:47-49`

**Current shape:** `path = url.removePrefix(BASE_URL); resourcePath = "webview/dist/$path"; classLoader.getResourceAsStream(resourcePath)`

**Fix:** before computing `resourcePath`, reject paths that match `path.contains("..")` OR `path.startsWith("/")` OR `path.contains("\\")` OR `path.contains(" ")`. Return 404 with no log noise (these would be probe attempts).

**Tests (write first):**
- `CefResourceSchemeHandlerTest`:
  - `http://workflow-agent/../../etc/passwd` → 404, no `getResourceAsStream` call.
  - `http://workflow-agent//absolute/path` → 404.
  - `http://workflow-agent/foo\\..\\bar` → 404.
  - `http://workflow-agent/foo%00.html` → 404 (URL-decode happens upstream; null byte after decode is rejected).
  - `http://workflow-agent/dist/assets/index.js` → ALLOWED (legitimate request).

**Exit criterion:** webview still loads in `runIde` (manual smoke test); all traversal payloads rejected.

**Model:** Sonnet.

**Commit message:** `fix(agent): reject path-traversal probes in CefResourceSchemeHandler`

---

### T6 — MED — Gradle dependency-locking + verification-metadata

**Files:**
- `gradle.properties` — add `org.gradle.dependency.verification=strict` (or `lenient` initially; promote to `strict` after baseline lock).
- All `*/build.gradle.kts` (or root with `subprojects { … }`) — add `dependencyLocking { lockAllConfigurations() }`.
- `gradle/verification-metadata.xml` (new, generated) — SHA-256 of every Gradle artifact.

**Steps:**
1. Add the locking + verification config blocks (no fetches yet).
2. Run `./gradlew --write-verification-metadata sha256 help` to generate `gradle/verification-metadata.xml`.
3. Run `./gradlew dependencies --write-locks` to generate per-configuration lockfiles under `*/gradle.lockfile`.
4. Verify the build still works with both files present: `./gradlew clean verifyPlugin buildPlugin --refresh-dependencies`.
5. Document the lockfile-update protocol in `docs/architecture/dependency-locking.md`: when bumping a version, edit `libs.versions.toml`, run `./gradlew dependencies --write-locks --write-verification-metadata sha256`, commit both lockfiles + metadata in the same commit as the version bump.

**npm side:**
- Verify `agent/webview/`'s build process uses `npm ci` (lockfile-strict) not `npm install`. Check for any CI/build script that runs `npm install` and switch to `npm ci`. Update `agent/webview/CLAUDE.md` (if exists) or `:agent/CLAUDE.md` to document.

**Tests:**
- N/A — this is build infrastructure. The "test" is `./gradlew clean verifyPlugin buildPlugin --refresh-dependencies` succeeds on a clean cache, AND a deliberate version-mismatch in `libs.versions.toml` (dev-only experiment, NOT committed) triggers the verification failure expected.

**Exit criterion:** clean build green; verification-metadata.xml + per-module lockfiles committed; `dependency-locking.md` doc lands in same commit.

**Model:** Opus (build infra, easy to break the whole build).

**Commit message:** `chore(build): enable dependency-locking + SHA verification-metadata`

---

### T7 — LOW — Document vendored JS provenance in NOTICES

**File:** `THIRD_PARTY_NOTICES.md` (and the `META-INF/THIRD_PARTY_NOTICES.md` JAR copy)

**What:** for each file in `agent/src/main/resources/webview/lib/` (`marked.min.js`, `purify.min.js`, `prism-core.min.js`, all `prism-languages/*.js`, all `prism-themes/*.css`, `ansi_up.js`, `chart.min.js`, `dagre.min.js`, `diff2html.min.js`, `diff2html.min.css`, `katex.min.js`, `katex.min.css`, all `katex-fonts/*.woff2`, `mermaid.min.js`, `prism-autoloader.min.js`, `tailwind-play.js` — verify exact list with `find` first), add to `THIRD_PARTY_NOTICES.md` § 2 (or new § 2a) a row with: filename, version, source URL, SHA-256.

**Computing SHA-256:** `shasum -a 256 agent/src/main/resources/webview/lib/marked.min.js`

**Source URLs:** the upstream CDN where each was downloaded from (`https://cdn.jsdelivr.net/npm/marked@12.0.2/marked.min.js`, etc.). If the upstream URL is unknown for a file, mark as `(provenance: unknown — verify before next release)` so it's flagged for follow-up.

**Tests:** N/A.

**Exit criterion:** every file in `webview/lib/` has a row; both NOTICES copies (root + META-INF) are byte-identical.

**Model:** Sonnet (mechanical sweep with `shasum`).

**Commit message:** `docs(notices): add SHA-256 provenance for vendored JS libs`

---

### T8 — LOW — Escape mention labels in `RichInput` chip builder

**File:** `agent/webview/src/components/input/RichInput.tsx:266,469`

**Current shape:** `chip.innerHTML = \`...${mention.label}...\`` and `\`...${ticketKey}...\``

**Fix:** build chip via `document.createElement` + `textContent` for the dynamic parts; or use a tiny escape helper (`s.replace(/[&<>"']/g, c => entities[c])`).

**Tests (write first):**
- `RichInputTest`: feeding a mention with `label = '<img src=x onerror=alert(1)>'` produces a chip whose innerHTML contains `&lt;img...` (escaped) NOT a real image element.

**Exit criterion:** existing chip rendering visually identical for normal labels; XSS payloads escape.

**Model:** Sonnet.

**Commit message:** `fix(webview): escape mention labels in RichInput chip builder`

---

## Tasks deliberately deferred

- **#9 (LOW) — Re-audit JCEF bridges for `PathValidator` calls.** Audit-only task with no concrete finding. Skip until a real bridge handler is identified as missing validation. (Add to `project_remaining_wiring_gaps.md` if any are discovered during T5.)

---

## Phase 6 exit gate

1. All T1-T8 commits land on `refactor/cleanup-perf-caching`.
2. `./gradlew clean verifyPlugin buildPlugin` green on IU-251/252/253.
3. `cd agent/webview && npm run build` green.
4. Manual `runIde` smoke test confirms: chat renders, plan editor renders, agent run executes, Sonar scan still works, `run_tests` for a Spring Boot project still works.
5. `THIRD_PARTY_NOTICES.md` updated for any new dep introduced (`rehype-sanitize` if added).
6. `phase6-security-closeout.md` summary doc lands as the final commit.

## Estimated commit count

8 task commits + 1 closeout doc = **9 commits**. Some tasks ship 1-2 followups (per Phase 5 reviewer-cadence pattern), so realistic range is **9-13 commits**.

## Estimated duration

~4-6 sessions of subagent-driven dev (T1, T6 are larger; T2, T4, T5, T7, T8 are <1 session each).

## Risk / blast radius

- T1 (markdown raw HTML) — option B preserves the safe HTML subset (`<details>`, `<kbd>`, `<sub>`, etc.) so visible UX regression is minimal. Risk reduces to: any agent response that relied on `<script>`, `<iframe>`, `style=` attributes, or `on*` handlers will lose those — which is the intended threat surface, not a regression.
- T3 (SSRF guard) — could break a legitimate developer probing a self-hosted internal IP for testing. Mitigation: `allowLoopback=true` covers the only documented use case; any rejected URL returns a clear `URL_BLOCKED` error so the LLM can route around it.
- T6 (dependency-locking) — most likely to break the build for someone else; verify with a clean-cache rebuild before commit.

All other tasks are scoped and reversible.
