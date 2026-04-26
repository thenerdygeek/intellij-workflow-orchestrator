# Phase 6 Closeout — Security Hardening

**Branch:** `refactor/cleanup-perf-caching`
**Audit source:** `/tmp/security-audit-report.md` (2026-04-26)
**Plan:** `docs/architecture/security-hardening-plan.md`
**Exit gate PASSED:** `./gradlew clean verifyPlugin buildPlugin --refresh-dependencies` green on IU-251.29188.11 / IU-252.28539.33 / IU-253.32098.37 (full plugin-verifier sweep, T6 verification step). Plugin ZIP `intellij-workflow-orchestrator-0.83.24-beta.zip` (34 MB) produced.

---

## What landed (10 commits)

| # | Task | Commit | Severity | Subject |
|---|---|---|---|---|
| 1 | License-audit closeout | `4333bc21` | — | `docs(legal): add LICENSE + vendor block + README enterprise requirements` |
| 2 | Plan | `c4a33700` | — | `docs(architecture): Phase 6 security hardening plan` |
| 3 | T1 | `8833dca7` | HIGH | `fix(webview): strip raw HTML from markdown renderers (XSS hardening)` |
| 4 | T3 | `8a87f937` | MED | `feat(core): UrlSafetyGuard with SSRF allowlist; apply to HttpReadinessProbe` |
| 5 | T4 | `8d297ef4` | MED | `fix(agent): argv ProcessBuilder for Maven/Gradle shell fallback (no sh -c)` |
| 6 | T5 | `49e0804f` | MED | `fix(agent): reject path-traversal probes in CefResourceSchemeHandler` |
| 7 | T2 | `9b5e71c2` | HIGH | `fix(sonar): pass token via env var, use argv ProcessBuilder, validate branch name` |
| 8 | T8 | `193fe657` | LOW | `fix(webview): escape mention labels in RichInput chip builder` |
| 9 | T6 | `443365f8` | MED | `chore(build): enable dependency-locking + SHA verification-metadata` |
| 10 | T7 | `467e8815` | LOW | `docs(notices): add SHA-256 provenance for vendored JS libs` (landed earlier in session, kept in scope) |

T7 landed before the others due to a multi-agent crash recovery sequence; from a chronology-of-fixes standpoint it is part of the same Phase 6 batch.

---

## Threat-model coverage

| Audit finding | Severity | Status | Closing commit |
|---|---|---|---|
| `rehype-raw` + `unsafe-inline` → LLM markdown can call any `window._*` Kotlin bridge | HIGH | Closed | `8833dca7` |
| Sonar token visible in `ps aux` via argv | HIGH | Closed | `9b5e71c2` |
| `HttpReadinessProbe` SSRF (no guard on LLM-supplied `ready_url`) | MED | Closed | `8a87f937` |
| `JavaRuntimeExecTool` Maven/Gradle `sh -c` injection vector | MED | Closed | `8d297ef4` |
| `CefResourceSchemeHandler` path traversal (no `..` check) | MED | Closed | `49e0804f` |
| Gradle dependency-locking + SHA verification-metadata absent | MED | Closed | `443365f8` |
| Vendored JS provenance unrecorded | LOW | Closed | `467e8815` |
| `RichInput` chip builder template-literal `innerHTML` | LOW | Closed | `193fe657` |

**Deferred (per plan):**
- LOW finding #9 (re-audit JCEF bridges for `PathValidator` calls). Audit-only task; no concrete missing-validation finding. Leave for follow-up if a real bridge handler is identified as missing validation.

**Network-claim audit verdict (from the same audit):** clean. No outbound calls outside the user-configured Atlassian / Sourcegraph / SonarQube / Nexus servers. CSP `connect-src 'none'` enforced on the JCEF webview, both interactive iframes use `sandbox="allow-scripts"` (not `allow-same-origin`), Mermaid runs at `securityLevel: 'strict'`, no remote CDN refs in any bundled HTML/JS/CSS, no transitive npm package found to issue runtime network calls. Verified by static analysis only — runtime-socket verification (e.g., packet capture during `runIde`) was not performed.

---

## Subagent execution notes

Tasks were dispatched as ONE foreground subagent per task, sequentially. Initial parallel dispatch (7 concurrent subagents for T1–T8 minus T6) hung the user's machine; recovery strategy was to inspect the partial work in the working tree (most tasks had committed test files and impl files but not the commit), commit the surviving work in-process, and dispatch the remaining tasks (T2, T8, T6) one-at-a-time after the machine recovered.

**Pattern recorded for future phases:** parallel-agent count must be calibrated to user hardware. The Phase 5 record warned about per-agent context size; Phase 6 added the corollary that concurrent-agent count is its own resource limit independent of per-agent size. **Future plans should default to ≤2 concurrent foreground subagents on this user's machine.**

Reviewer cadence: skipped explicit `superpowers:code-reviewer` per `feedback_skip_subagent_reviews.md`. TDD-per-task served as the reviewer signal — implementer wrote the failing test from the threat model first, then made it pass. Worked cleanly across all 8 tasks; no rollback or follow-up needed.

---

## Audit-overstatement gotchas surfaced during execution

Continuing the branch-wide pattern:

1. **T1 — option B vs option A.** First plan recommended dropping `rehype-raw` outright (option A), without pricing in the loss of `<details>`, `<kbd>`, `<sub>`, GFM task-list checkboxes, and other safe HTML elements LLMs routinely emit. Plan was revised to default to `rehype-sanitize` (option B); option A retained as fallback only.
2. **T6 step 3 — `--write-locks` only locks the root project.** First-pass `./gradlew dependencies --write-locks` produced a single `gradle.lockfile` in the root. Had to invoke per-subproject (`./gradlew :core:dependencies :jira:dependencies … --write-locks`) to actually lock all 9 modules.
3. **T6 step 4 — `--write-verification-metadata sha256 help` misses 12 buildscript-classpath POMs and BOMs** (Jackson BOMs, JUnit BOMs, kotlinx-coroutines-bom, oss-parent). First verify run failed against 12 missing artifacts. Had to re-run with `--refresh-dependencies verifyPlugin buildPlugin` to exercise the buildscript classpath and capture those entries.
4. **T6 step 6 — npm side already correct.** `agent/build.gradle.kts:65` already uses `npm ci` for `npmInstallWebview`. No `npm install` references found in any script/doc. The audit's recommendation was redundant — would not have known without reading the source first.
5. **T8 — jsdom polyfills required.** The pre-written test used `ClipboardEvent` and `DataTransfer` which jsdom does not implement. Had to add minimal polyfills at the top of the test file before the paste-path tests would even compile, let alone run.

---

## Patterns established

- **`UrlSafetyGuard` in `core/security/`** — reusable SSRF guard with `allowLoopback` flag. Lift this into other places that fetch LLM-supplied URLs in the future. The DockerRegistry → HttpReadinessProbe consolidation removed 25 lines of inline regex + DNS resolution.
- **`SonarTool.buildScannerProcess` + `validateBranchName` extraction** — pattern for any other tool that builds a shell command from user/LLM input. Token via env var, branch validated against `^[a-zA-Z0-9._\-/]+$`, `mvnw`/`gradlew` resolved to absolute path. T2 + T4 use the same shape.
- **`CefResourceSchemeHandler.isPathSafe` companion-object helper** — 8 unit tests exercise the pure function without needing JCEF interfaces. The same template (pure-function path-safety check + integration tests via mocked CEF interfaces) applies to any future scheme handler.
- **`rehype-sanitize` with `defaultSchema` + minimal extensions** — pattern for any future markdown renderer in this plugin. Don't extend the schema speculatively; add only what the renderer actually needs (`pre.ascii-art` class, `code[data-meta]` attribute) and document the rationale inline.
- **TDD-per-task subagent dispatch with NO explicit reviewer** — implementer writes failing test from threat model, makes it pass, commits. Phase 6 was 8-for-8 on this pattern with zero rollbacks.

---

## Releasable

Branch is now at **134 commits ahead of `main`** (Phase 1: 28, Phase 2: 11, Phase 3: 13, Phase 4: 39, Phase 5: 30 + closeout, Phase 6: 10 + closeout = 132 + this closeout commit + the closeout above = 134).

Working tree clean. All 8 Phase 6 tasks closed against their threat-model assertions. T6's full `verifyPlugin buildPlugin --refresh-dependencies` run is the gate proof — the lockfiles + verification-metadata.xml admit a fresh dependency download, the plugin still builds against IU-251/252/253.

**Recommended next step:** bump `pluginVersion` from `0.83.24-beta` → `0.83.25-beta` per the established release-timing convention, push the branch + open a PR, and ship a release ZIP per the documented release process.

---

## Self-delete trigger reminder

This closeout file is permanent (lives in `docs/architecture/`). The BRANCH-SCOPED memory file at `~/.claude/projects/.../memory/project_branch_refactor_cleanup_perf_caching.md` and the temporary `~/.agents/skills/simplify-scoped/` skill should be deleted when this branch is merged into main, per the self-delete trigger documented in that memory file.
