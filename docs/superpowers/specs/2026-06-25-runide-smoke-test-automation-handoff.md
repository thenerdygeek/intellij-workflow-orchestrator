# Context Handoff — Plugin-Split runIde Smoke-Test Campaign & Test Automation

**Date:** 2026-06-25
**Branch:** `feature/plugin-split`
**HEAD at handoff:** `e8c524c6e` (fully pushed; in sync with origin)
**Uncommitted:** only the pre-existing `webview/dist` changes (NOT from this work — deliberately left unstaged)

> Companion to `2026-06-25-plugin-split-handoff.md` (which covers Phase 1a+1b completion and Phase 1c next steps). This doc covers the runIde smoke-test campaign + the test-automation work layered on top.

---

## 1. Goal

Validate the plugin-split via runIde smoke testing. It expanded into: green-lighting the Mac-side
gates, fixing two latent blockers, building diagnostics + handoff docs for Windows, and then
**automating as much of the manual smoke as possible** (headless + Remote Robot UI).

## 2. Commits this session (all pushed, in order)

| Hash | Summary |
|---|---|
| `ced84d111` | **fix(build):** `duplicatesStrategy=EXCLUDE` on `buildPlugin` (Gradle 9.4 blocker) |
| `04da4e89c` | **feat(core):** shareable plugin-only diagnostic log |
| `74b86b690` | **docs:** `WINDOWS-RUNIDE-CHECKLIST.md` (read-only P0–P2) |
| `47afce8ad` | **docs:** `HOW-TO-RUN-TESTS.md` (beginner guide) |
| `73c1419a4` | **test:** headless tests for the split's EP wiring |
| `1c36dbd44` | **test(ui):** Remote Robot UI harness + first visual test |
| `e8c524c6e` | **docs:** link the Remote Robot UI tests into the test guide |

This push also synced the previously-**unpushed** Phase **1a + 1b + the prior handoff** (`bfb671c15`) — the whole branch is now on origin.

## 3. Hard environment constraints (shaped every decision)

- **This dev Mac has no activated IntelliJ Ultimate.** `runIde` (and the Remote Robot UI tests) launch license-gated **Ultimate** and sit on the license wall here (`#LicenseManager - License required` / `LicenseFacadeFail`; `[PluginSplit]` never fires because no project opens). The plugin *requires* Ultimate (Spring/Database bundled deps) — Community is not an option.
- **→ All interactive `runIde` + UI testing happens on a licensed Windows box** (or CI + Xvfb). The Mac does only headless work.
- **User rule:** strictly **NO backend writes** during testing (no ticket create/transition, time-log, branch create, merge, build trigger, Jira closure, copyright fix-all). Sourcegraph + connector tokens live only on the Windows machine.

## 4. State of the tests now

- **1,135 automated headless test files** across 14 modules (`gradlew test`) — all green (verified full run, `BUILD SUCCESSFUL in 4m45s`). Largest: `agent` 615, `core` 185, `document` 71.
- **1 Remote Robot UI test** (`src/uiTest/.../WorkflowToolWindowSmokeTest.kt`) — compiles + isolated here; **runs only on Windows/CI** (license + display).
- **Manual:** `WINDOWS-RUNIDE-CHECKLIST.md` (read-only, every write op flagged ⛔ SKIP).
- **Three tiers:** headless (anywhere, no license) → Remote Robot UI (Windows/CI) → manual.
- **Irreducibly manual** (no framework fixes these): live A↔B plugin-classloader resolution, JCEF/Chromium rendering (Agent chat), native OS keychain, Swing-panel *paint*.

### New tests added this session
- `konsist/…/PluginSplitEpContractTest` — both plugin.xml files register the right EP impls; B hard-depends on A.
- `core/…/config/WorkflowConfigResolutionTest` — `order=0` override beats the default (both list orderings); headless `resolve()` fallback to `DefaultWorkflowConfig`.
- `core/…/config/PluginSplitStartupOracleContractTest` — pins the `[PluginSplit]` startup oracle.
- `plugin-b/…/CompanyBSplitBehaviorTest` — the *real* `CompanyBWorkflowConfig` (order=0 wins) + `CompanyBToolContributor` (registers `companyb_noop`). Required `testImplementation(:core,:agent)` + mockk in `plugin-b` (new test source set).
- `agent/…/AgentServiceToolRegistrationWiringContractTest` — added the `[agentToolContributor]` + tool-count source-text oracle pins.
- `src/uiTest/…/WorkflowToolWindowSmokeTest` — Remote Robot: open Workflow tool window → assert 6 Swing tabs → screenshot.

## 5. Traps / gotchas discovered (read before next build work)

- **`buildPlugin` Gradle-9.4 duplicate-searchableOptions:** `clean` does **NOT** fix it (the `plugin-b/build.gradle.kts` note claiming so is **wrong**). It's the Gradle 9.4 default `duplicatesStrategy` change (INCLUDE→FAIL) on a byte-identical dup. **Fixed** via `EXCLUDE` scoped to `buildPlugin`. It is reachable from `test` / `runIde` / `:plugin-b:runIde` via plugin-b's `localPlugin(rootProject)` self-reference, so it blocked all three.
- **Never pipe background Gradle through `tail`** — the captured exit code becomes `tail`'s (always 0), masking `BUILD FAILED`. Use `${PIPESTATUS[0]}` or read the `BUILD …` line from the output file.
- **Adding deps** needs the repo's documented protocol: `./gradlew dependencies --write-locks --write-verification-metadata sha256` (locks + SHA-256; additions-only — review the diff for no removals / no trust-wildcards).
- **`uiTest` source set** needs explicit `kotlin("stdlib")` + `libs.okhttp` (`kotlin.stdlib.default.dependency=false`, and `RemoteRobot`'s constructor references `OkHttpClient` at compile time, which remote-robot exposes only as a runtime transitive).
- **v2 Remote Robot:** `intellijPlatformTesting.runIde.registering { plugins { robotServerPlugin() } }` + a separate `Test` task — the v1 `runIdeForUiTests` built-in does **not** exist in IntelliJ Platform Gradle Plugin 2.x. (`testIdeUi` is reserved for the Starter framework.)
- **Incidental & kept:** the global `--write-locks` refreshed `verifier-cli 1.405→1.407` across all lockfiles. `verifyPlugin` re-verified **Compatible** (identical verdict, 17 internal-API usages), so it was kept (reverting would just re-drift on the next `--write-locks`).
- The `webview/dist` deleted-JS in `git status` is a **false alarm** (old hashed bundles replaced by new ones; dist is self-consistent; no rebuild needed).

## 6. Key artifacts / where to look

- `HOW-TO-RUN-TESTS.md` — beginner test guide (§7.1 = Remote Robot UI tests).
- `WINDOWS-RUNIDE-CHECKLIST.md` — read-only manual smoke, P0–P2, with the master ⛔ SKIP list.
- `RUNIDE-TEST-SCENARIOS.md` — the original oracle catalog the checklist derives from.
- **Diagnostic log to collect on any failure:** `~/.workflow-orchestrator/diagnostics/plugin-0.log` (plugin-only, small, rotating; default-on, toggle in Telemetry settings — "applies after restart"). Agent failures also → `…/{proj}-{sha6}/logs/agent-*.jsonl`.

## 7. Next steps

1. **On Windows:** `git pull` → activate Ultimate (or 30-day trial) → run the runIde smoke per `WINDOWS-RUNIDE-CHECKLIST.md` (P0 + token-gated P1/P2, **read-only**), and the UI test (`gradlew runIdeForUiTests` in terminal 1, `gradlew uiTest` in terminal 2). **Tune the XPath locators** against the live component tree at `http://127.0.0.1:8082` (they're best-guesses). Send `plugin-0.log` lines on any failure.
2. **Phase 1c** (per `2026-06-25-plugin-split-handoff.md`): delete dead `isValidBranchName`, configurable commit format, Sprint feature-detect+hide, `PsiContextEnricher` ModuleManager fallback, `VcsHostClient.getDefaultBranch`+reviewers.
3. **Deferred (pre-existing):** detekt RED on 73 0b-3 issues (cache-masked; → IDE-autocorrect cleanup).
4. **Optional UI-harness follow-ups** (from the review, non-blocking): forward a `robot.server.url` system property; exclude the `uiTest` task from Kover instrumentation.

## 8. Honest gaps / not-yet-done

- **No runIde smoke has actually been executed yet** (Mac blocked by license; Windows pending). The headless + structural layers are proven; the live IDE-integration layer is specified + instrumented but unrun.
- The UI test's component locators are **unverified** (compile-only here) and need live tuning on Windows.
