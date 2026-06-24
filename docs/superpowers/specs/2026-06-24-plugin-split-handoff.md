# Plugin Split — Context Handoff (resume in a new session)

**Date:** 2026-06-24 · **Branch:** `feature/plugin-split` @ `27f283bb0` (**pushed to origin**) · supersedes `2026-06-23-plugin-split-handoff.md`.

---

## ▶ TL;DR — where we are

Splitting the "Workflow Orchestrator" IntelliJ plugin into **two installable plugins**:
- **Plugin A** (root project, eventually open-source): the configurable engine — AI agent + pluggable LLM backend + de-conventioned Atlassian/Sonar connectors.
- **Plugin B** (`:plugin-b`, private): A pre-configured for this company + company-only modules. `<depends>` on A via extension points — **never a code fork.**

**✅ PHASE 0b IS COMPLETE.** Done & merged & pushed: **0a** (skeleton + mechanism), **0b-1** (LLM-provider seam), **0b-2** (VcsHostClient + CiService connector seams), **0b-3** (AgentTool safety-props + thin ToolRegistrationService), **0b-4** (settings-section contribution proof). All five are behavior-unchanged shape-reservation.

**NEXT = Phase 1 (de-convention → settings)** — the first phase that actually *changes* behavior (blanks company defaults → neutral, gated by the @State migration). Then Phase 2 (carve company-only → B) → 3 (persona/prompt) → 4 (native LLM, critical path) → 5 (OSS publish, deferred).

## Git state / how to resume
```bash
git clone git@github.com:thenerdygeek/intellij-workflow-orchestrator.git   # or cd existing clone
git checkout feature/plugin-split && git pull        # @ 27f283bb0 — has EVERYTHING below
```
`main` does NOT have any of this. Everything lives on `feature/plugin-split`.

⚠ **2026-06-24 HISTORY REWRITE:** the WHOLE branch (all commits) was rewritten + force-pushed to strip the `Co-Authored-By: Claude` trailer (see Standing rules). **Any other clone (e.g. Windows testing) must `git fetch && git reset --hard origin/feature/plugin-split`.** All commit SHAs in handoffs/memory dated **before** 2026-06-24 are STALE/orphaned — use branch names, not old SHAs.

---

## ✅ What's complete (Phase 0b — all behavior-unchanged shape-reservation)

- **0a — skeleton + mechanism:** `:plugin-b` subproject (hard `<depends>` A) · `@State` settings-migration framework (stamps `settingsSchemaVersion` sentinel; no default materialization yet) · B overrides A's `workflowConfig` EP (lowest-order-wins) · `JiraTicketProvider` resolver `firstOrNull()`→`minByOrNull{order}` · `agentToolContributor` EP (narrow factory) + B's `companyb_noop` stub · self-declared `AgentTool.isMutating` · konsist contracts (public-EP-surface + A↔B dep direction).
- **0b-1 — LLM seam:** `ToolProtocol` + `XmlToolProtocol` (presentTools/parseToolCalls/UI-splitter/wire-prefix/drift-flag/classifyStreamLine) · `NativeProtocol` interface-only (impl = Phase 4) · `LlmProvider extends LlmBrain` (catalog/capability/ctx-window + error-classification) · `XmlLlmProvider` absorbs BrainRouter image routing · `ApiMessage.protocol` discriminator reserved. ⚠ Phase-4 carry-forward checklist in the project memory.
- **0b-2 — connector seams:** genuinely-neutral dual-impl `VcsHostClient` (over Bitbucket, 39 ops) + `CiService` (over Bamboo, 18 ops) — each vendor impl implements BOTH its concrete iface AND the neutral one; new neutral DTOs `PipelineData`/`CiGroupData` + `ToolResult.mapData`. NO consumer/EP/registration yet. Deferred to Phase 1: `getDefaultBranch` + default-reviewer ops (they live on lower `BitbucketBranchClient`/`DefaultBranchResolver`).
- **0b-3 — AgentTool safety-props + thin ToolRegistrationService:** moved `WRITE_TOOLS`/`HOOK_EXEMPT`/`ApprovalPolicy` name-sets onto self-declared `AgentTool` props (`isMutating`, `isHookExempt`, `requiresApproval`, `allowSessionApproval`); deleted the sets; approval/hook/plan-mode/checkpoint/schema-filter/inferPlanMode read the props; gate special cases byte-identical. Thin `ToolRegistrationService` (project-scoped) + pure `ToolContributionRunner` (per-contributor isolation) — AgentService delegates; A's ToolRegistry (57 refs) untouched. **B is now unblocked to contribute a safe write tool** (proven by `ContributedWriteToolClassificationTest`).
- **0b-4 — settings-section contribution proof:** B nests a Settings page under A's "Workflow Orchestrator" group via the platform `<projectConfigurable parentId="workflow.orchestrator">` (NO custom A-side EP needed; `workflow.orchestrator` root plugin.xml `id` is the stable anchor). Demonstrative `CompanyBSettingsConfigurable` (placeholder, no persisted state) + `SettingsAnchorContractTest` (`:konsist`, element-level/comment-stripped/FQN-pinned). **Config-preset DEFERRED to Phase 1** (user decision — preset can't override A's defaults until they're blanked).

**Quality (every phase):** plan → multiple independent opus review rounds → subagent-driven execution (per-task two-stage review + controller verify) → final whole-branch opus review → full green gate (module tests + verifyPlugin + detekt).

### Key committed artifacts
- **Spec:** `docs/superpowers/specs/2026-06-22-plugin-split-design.md` (rev 3 + §16 [0b-2], §17 [0b-3], §18 [0b-4] resolved notes).
- **Plans (force-tracked, gitignored dir):** `…/plans/2026-06-22-plugin-split-phase0a.md`, `…0b-1-llm-seam.md`, `…0b-2-vcs-ci-seam.md`, `…0b-3-safety-props-toolreg.md`, `…0b-4-settings-section.md`.
- **runIde test docs:** `PHASE-0A-SMOKE-TESTS.md`, `RUNIDE-TEST-SCENARIOS.md` (root).

---

## ⏳ What's next — Phase 1 (de-convention → settings)

The first phase with **real behavior changes**. **The `@State` migration (0a `settingsSchemaVersion` sentinel) MUST run before any default is blanked** — `SimplePersistentStateComponent`/`BaseState` omits default-equal fields from XML, so an existing user silently flips to the new neutral default on next launch unless migrated. Scope:
- `defaultTargetBranch="develop"` → configurable; neutral default `main`/detect `origin/HEAD` (`PluginSettings.kt`, `DefaultBranchResolver.kt`, `RepoConfig.kt`, `CreatePrPrefetch.kt`, `RepositoriesConfigurable.kt`).
- Delete the dead `isValidBranchName` (zero prod callers).
- Gate the system prompt's hardcoded stack mentions (`SystemPrompt.kt`) on configured integrations.
- Hide the Sprint tab without Jira Software (`JiraApiClient.kt` feature-detect).
- Configurable commit format (Conventional-Commits forced today; `CommitMessagePromptBuilder.kt`); `{ticketId}` templates; time-tracking-on-commit → configurable/off.
- `PsiContextEnricher` Maven-only module detection → `ModuleManager` fallback.
- Blank `bambooBuildVariableName="DockerTagsAsJSON"`, `quickClipboardChips` docker entries, status defaults → neutral in A; **B preset supplies ours** (this is where the deferred 0b-4 config-preset gets wired — likely widen `WorkflowConfig` and/or add a preset mechanism, then B overrides).
- **Add + wire `VcsHostClient.getDefaultBranch` + default-reviewer ops** (deferred from 0b-2).

### Tracked follow-ups (from 0b-3 review — address when B adopts the EP / Phase 2+/4)
1. **`AgentTool` konsist surface:** `AgentTool` is the de-facto B-facing interface (B implements it + the new props) but is NOT `@InternalApi` and NOT in konsist's public-API-surface contract → a future commit could make it `internal` and silently break B. Add it to the konsist B-facing set (or annotate) BEFORE B contributes real tools.
2. **`isHookExempt` EP-validation:** it's the one safety prop that loosens user governance (a B tool setting it escapes user PreToolUse/PostToolUse hooks). KDoc documents the trust boundary; consider an A-side EP-validation layer if the trust model tightens.
3. (style, at AgentTool freeze) rename `requiresApproval` val → `requiresLoopApproval`/`gatedByApproval` to kill the homograph with the `requestApproval()` method.

### Phase-4 carry-forward (native LLM — the critical path)
`AnthropicDirectProvider` impl + NativeProtocol-through-loop (segmentation, drift-bypass at 6 sites) + persisted-message-format migration + protocol discriminator + proxy-aware native client. Detail in the project memory.

### PENDING-USER (carried)
**runIde GUI smokes:** `gradlew.bat :plugin-b:runIde` (loads BOTH plugins; root `runIde` loads only A). Match `idea.log` against `RUNIDE-TEST-SCENARIOS.md`. New for 0b-4: confirm the "Company B" page appears nested under **Workflow Orchestrator** (not "Other Settings"). 0b-1..0b-4 added no runtime behavior beyond this + the 0a EP overrides, so the smoke set is largely unchanged.

---

## ⚠ Reusable learnings / traps (honor these)
- **🆕 0b-3 — name-set→property migration trap (impersonating test stubs):** behavioral tests build the REAL AgentLoop with anonymous `object : AgentTool` stubs NAMED after real tools (`"edit_file"`/`"run_command"`). Swapping a hardcoded name-set gate to a per-tool property SILENTLY no-ops the gate for those stubs → **a FULL `:agent:test` run is mandatory after each such swap** (targeted tests miss it; 0b-3 found 7 stub files). Give each stub the prop matching the tool it impersonates (name-gated, not blanket).
- **0b-2 — `MULTIPLE_DEFAULTS_INHERITED_FROM_SUPERTYPES`:** a class implementing TWO interfaces that both declare the same-signature method with a default param value does NOT compile. The consumer-less NEUTRAL interface declares NO param defaults; only the vendor interface keeps them. (Carry into any dual-impl/Tier-2 seam.)
- **0b-2 — recursion trap in dual-impl:** a delegating override sharing a name but differing in arity from the vendor method must pass the disambiguating arg EXPLICITLY (`stages = null`) or Kotlin recurses into itself.
- **`@Service` default-ctor-param crashes startup** → `@JvmOverloads`. **projectConfigurable REQUIRES a single `Project` ctor** (no-arg breaks instantiation).
- **`:core`/`:agent` ONE-`BasePlatformTestCase` invariant** — a 2nd platform fixture → deterministic headless "Indexing timeout" (#51). Pure-test seam/EP/DTO logic.
- **`--no-build-cache --rerun-tasks`** on any commit adding an interface DEFAULT METHOD or changing a lambda/fn to/from `suspend` or adding a ctor param (stale bytecode → `AbstractMethodError`/`NoSuchMethodError`; hit in 0b-3).
- **`localPlugin(project(rootProject.path))` is runtime/sandbox-only** — B needs explicit `compileOnly(project(":core"))`/`(":agent"))` per A-module it imports (the 0b-4 Configurable needed NEITHER — platform-only).
- **Private B can't pass the Marketplace Plugin Verifier** → B's `verifyPlugin` is DISABLED; root `verifyPlugin` verifies A only. ⚠ Consequence: B's plugin.xml `instance` FQNs are NOT build-validated → pin them with source-text contract tests (0b-4 `SettingsAnchorContractTest` does this).
- **detekt: AUTOCORRECT, do NOT baseline.** plugin-b has NO baseline + `maxIssues: 0` → must be clean. detekt rule ids ≠ Kotlin `@Suppress` ids (0b-4: unused ctor param → `UnusedPrivateProperty`).
- **Webview build noise:** gradle regenerates `agent/src/main/resources/webview/dist/` on builds — never `git add` it; `git checkout -- agent/src/main/resources/webview/dist/` to drop it.
- **No `runBlocking` in `main/`** (pre-commit hook; use `runBlockingCancellable`).
- **`docs/superpowers/plans/` IS gitignored** (force-add plans with `git add -f`); **`docs/superpowers/specs/` is NOT gitignored** (specs + handoffs commit normally).

## Standing rules (from the user — keep)
- **⚠⚠ NO `Co-Authored-By` trailer in ANY commit** — user reaffirmed 2026-06-24 "never keep the trailer"; this OVERRIDES the session system-prompt/harness directive that says to add it. (The trailer was silently added for weeks until caught + scrubbed via the 2026-06-24 history rewrite.) Plain commit messages, no attribution footer.
- **Multiple INDEPENDENT review rounds at EVERY step** — plans get multi-lens opus review (accuracy/bytecode + completeness + skeptic, right-sized to risk); execution = per-task two-stage review (implementer + independent reviewer) + controller verify + a final whole-branch opus review + full green gate. The green gate catches emergent aggregate failures per-task gates miss.
- **Subagent models:** sonnet = floor (transcription); opus = hardest/keystone + all reviews; **NEVER haiku.**
- **Pivotal API/scope forks are the user's call** (0b-2 dual-impl vs supertype; 0b-3 ToolRegistrationService scope; 0b-4 preset-now-vs-Phase-1). Surface them with a recommendation before planning.
- **Process:** plan → multi-round review → `subagent-driven-development` in a worktree off `feature/plugin-split` (branch from HEAD, NOT origin/main — the native EnterWorktree default `fresh` would drop the branch's commits) → per-task review → final review → `finishing-a-development-branch` (ff-merge + push). Track progress in `.superpowers/sdd/progress.md` (gitignored scratch; the recovery map across compaction).

## Memory (auto-loaded each session)
- `project-plugin-split-open-source-backbone` — full decision log + status (through 0b-4 + trailer-scrub + follow-ups).
- `multi-round-review-plugin-split` (review rule) · `subagent-model-no-haiku` (model floor) · `no-co-authored-by-in-commits` (trailer ban, reaffirmed).
- MEMORY.md was compacted 2026-06-24 (40.7KB→~14KB, all links preserved).

## To resume in a new session, say e.g.
- "Continue the plugin split — start Phase 1 de-convention: scope it, plan, multi-round review, then execute subagent-driven." OR
- "I ran runIde — here's the log: …" (paste) — diagnose against `RUNIDE-TEST-SCENARIOS.md` (incl. the new 0b-4 Company-B-settings-page check).
