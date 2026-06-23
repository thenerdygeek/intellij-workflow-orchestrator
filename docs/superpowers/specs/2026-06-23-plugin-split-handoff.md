# Plugin Split — Context Handoff (resume in a new session)

**Date:** 2026-06-23 (rev 2) · **Branch:** `feature/plugin-split` @ `243e4ed46` (**pushed to origin**) · supersedes the earlier 0a+0b-1 handoff on this file.

---

## ▶ TL;DR — where we are

Splitting the "Workflow Orchestrator" IntelliJ plugin into **two installable plugins**:
- **Plugin A** (root project, eventually open-source): the configurable engine — AI agent + pluggable LLM backend + de-conventioned Atlassian/Sonar connectors.
- **Plugin B** (`:plugin-b`, private): A pre-configured for this company + company-only modules. `<depends>` on A via extension points — **never a code fork.**

**Done & merged & pushed:** **Phase 0a** (skeleton + mechanism), **Phase 0b-1** (LLM-provider seam), **Phase 0b-2** (VcsHostClient + CiService connector seams). **In progress (user):** runIde manual testing (still PENDING-USER; not changed by 0b-2). **Next:** Phase 0b-3 → 0b-4 → Phase 1 de-convention → … → Phase 4 native LLM (critical path) → Phase 5 OSS publish (deferred).

## Git state / how to resume
```bash
git clone git@github.com:thenerdygeek/intellij-workflow-orchestrator.git   # or: cd existing clone
git checkout feature/plugin-split && git pull        # @ 243e4ed46 — has EVERYTHING below
```
`main` does NOT have any of this. Everything lives on `feature/plugin-split`. Worktrees used during execution are cleaned up.

The 0b-2 grounding maps (`.superpowers/phase0b/0b2-explore-{vcs,ci}.md`) and SDD ledger are **gitignored scratch** in the working tree (not on origin) — useful for reference but not required to resume.

---

## ✅ What's complete

### Phase 0a — skeleton + mechanism (8 tasks, merged)
`:plugin-b` Gradle subproject (hard `<depends>` on A) · `@State` settings-migration framework (stamps `settingsSchemaVersion` sentinel; no default materialization yet) · B overrides A's `workflowConfig` EP (lowest-order-wins) · `JiraTicketProvider` resolver `firstOrNull()`→`minByOrNull{order}` · `agentToolContributor` EP (narrow factory) + B's `companyb_noop` stub · self-declared `AgentTool.isMutating` for plan-mode safety · konsist contracts (public-EP-surface + A↔B dep-direction).

### Phase 0b-1 — LLM seam `LlmProvider` + `ToolProtocol` (12 tasks, merged)
Behavior-UNCHANGED extraction: `ToolProtocol` + `XmlToolProtocol` (presentTools/parseToolCalls/UI-splitter/wire-prefix/drift-flag/classifyStreamLine) · routed 2 presentation + 3 AgentLoop streaming sites · `NativeProtocol` interface-only (impl = Phase 4) · drift machinery gated on `requiresDialectGuard` · `LlmProvider extends LlmBrain` (catalog/capability/ctx-window + error-classification + `toolProtocol`) · `XmlLlmProvider` absorbs `BrainRouter` image routing (additive/unused) · `ApiMessage.protocol` discriminator reserved · `@InternalApi` retention BINARY→RUNTIME.
⚠ Phase-4 carry-forward checklist for the native provider lives in `project-plugin-split-open-source-backbone` memory + the spec.

### Phase 0b-2 — connector seams `VcsHostClient` + `CiService` (7 tasks, merged @ `243e4ed46`)
**Genuinely-neutral dual-implementation** (the user chose this over a minimal supertype). Behavior-UNCHANGED + **shape-reservation only** (sibling to 0b-1's `NativeProtocol`: NO consumer, NO EP/registration, NO `plugin.xml` change — a future GitHub/Jenkins connector plugs in additively).
- **`CiService`** (`core/services/CiService.kt`, `@InternalApi`) over `BambooService` — 18 neutral ops. `BambooServiceImpl` implements **both**: 11 bind "for free" (identical JVM signatures) + 7 thin delegating methods (`chainKey→pipelineId`, `getPlans→getPipelines`/`PlanData→PipelineData`, `getProjects→getGroups`/`ProjectData→CiGroupData`, `rerunFailedJobs→retryFailedJobs`). New neutral DTOs `PipelineData`/`CiGroupData` (`core/model/CiModels.kt`) + `ToolResult.mapData` envelope-preserving helper (`core/services/ToolResultMapping.kt`). Bamboo-specific ops (plan branches/variables/stage-trigger/auto-detect/short-name/build-variables) stay on `BambooService`.
- **`VcsHostClient`** (`core/services/VcsHostClient.kt`, `@InternalApi`) over `BitbucketService` — 39 neutral ops (= BitbucketService's 41 minus `getLinkedJiraIssues`/`getRequiredBuilds`, both vendor-coupled). `BitbucketServiceImpl` binds for **free** (identical signatures). `typealias VcsUserData = BitbucketUserData`; comment ops use neutral `repoOwner`/`repoName`. `getBuildStatuses`/`getCommitBuildStats` stay on the VCS seam by design (they read the VCS host's commit build-status store, distinct from CiService's CI queries — documented in KDoc).
- Both pinned by `SeamApiStabilityTest` (`:core`) + `PublicApiSurfaceTest` (`:konsist`).

**Quality:** plan grounded in 2 exploration maps + 3 independent opus plan-review rounds; 7 SDD tasks each two-stage-reviewed (implementer + independent task reviewer + controller verify); final opus whole-branch review = READY TO MERGE (behavior-unchanged + IP-clean OSS surface confirmed); full green gate GREEN (all module tests + `verifyPlugin` + detekt; no `:core` Indexing-timeout).

### Key committed artifacts
- **Spec:** `docs/superpowers/specs/2026-06-22-plugin-split-design.md` (rev 3 + §16 = the resolved 0b-2 design note).
- **Plans (force-tracked):** `docs/superpowers/plans/2026-06-22-plugin-split-phase0a.md`, `…/2026-06-23-plugin-split-phase0b-1-llm-seam.md`, `…/2026-06-23-plugin-split-phase0b-2-vcs-ci-seam.md`.
- **runIde test docs:** `PHASE-0A-SMOKE-TESTS.md`, `RUNIDE-TEST-SCENARIOS.md` (unchanged by 0b-2).

### 0b-2 commit chain (on `feature/plugin-split`)
`d75279cde` plan → `f67fcedc5` mapData → `8cd4dafe3` CI DTOs → `2472ee45b` CiService → `ec3425452` BambooServiceImpl:CiService → `67e8827d4` plan-fix (no-defaults) → `6136575d7` VcsHostClient + BitbucketServiceImpl → `243e4ed46` docs.

---

## ⏳ What's next (the queue)

1. **Phase 0b-3** — project-scoped `ToolRegistrationService` (narrow host for *production* B tools that need `spillOrFormat`) + remaining `AgentTool` safety props (`requiresApproval`/`HOOK_EXEMPT`; move the hardcoded `WRITE_TOOLS`/`HOOK_EXEMPT` sets in `AgentLoop` onto self-declared `AgentTool` properties). Until safety props land, B may not contribute write tools. Plan + multi-round review, then subagent-driven execution.
2. **Phase 0b-4** — settings-section contribution EP + B's config preset (overrides A's neutral defaults).
3. **Phase 1** — de-convention → settings (uses the Phase-0a `settingsSchemaVersion` sentinel before blanking any default): `develop`→config, delete the dead `isValidBranchName`, gate the system prompt's hardcoded stack mentions, hide Sprint tab w/o Jira Software, configurable commit format, `PsiContextEnricher` `ModuleManager` fallback, blank `bambooBuildVariableName`/`quickClipboardChips`/status defaults. **This is also where `VcsHostClient.getDefaultBranch` + default-reviewer ops get added + wired** (deferred from 0b-2 — they live on the lower `BitbucketBranchClient`/`DefaultBranchResolver` today, and adding them now would be new behavior, not shape-reservation).
4. **Phase 2** carve company-only → B (`:automation`, `:handover` minus CopyrightFix→A, `DockerTagsProvider`, copyright template, config preset, `devops-engineer` persona). **Phase 3** persona/skill/prompt hardening. **Phase 4** native LLM (Anthropic-direct) — the **critical path** (NativeProtocol-through-loop + persisted-message-format migration + proxy-aware native client). **Phase 5** OSS hardening + publish (DEFERRED; new repo + gitleaks + scrub bundled `agent/.../api-docs/*.json` + audit `:mock-server` `*DataFactory.kt` + Apache NOTICE).
5. **runIde GUI smokes (PENDING-USER, carried from 0a):** `gradlew.bat :plugin-b:runIde` (loads BOTH plugins; root `runIde` loads only A). Match `idea.log` against `RUNIDE-TEST-SCENARIOS.md`. 0b-2 added no runtime behavior, so it doesn't change these.

---

## ⚠ Reusable learnings / traps (honor these)
- **🆕 0b-2 — `MULTIPLE_DEFAULTS_INHERITED_FROM_SUPERTYPES` (dual-impl seams):** a class implementing TWO interfaces that both declare the SAME-signature method with a default param value (EVEN equal values, e.g. both `maxResults = 10` / both `repoName: String? = null`) does NOT compile. **Fix: the consumer-less NEUTRAL interface declares NO param defaults; only the concrete vendor interface keeps them** (the single impl override inherits the vendor defaults; neutral-typed callers have none, harmless with no consumer). The 0b-2 plan AND all 3 plan-review rounds missed this — caught only at Task-4 compile. **Carry into 0b-3/0b-4 and any future dual-impl/Tier-2 seam.**
- **🆕 0b-2 — recursion trap in dual-impl:** when a neutral method shares a *name* but differs in *arity* from the vendor method (`CiService.triggerBuild(pid, vars)` vs `BambooService.triggerBuild(chainKey, vars, stages)`), the delegating override must pass the disambiguating arg EXPLICITLY (`stages = null`, `repoName = null`) or Kotlin resolves the call back to the override itself → infinite recursion.
- **`localPlugin(project(rootProject.path))` is runtime/sandbox-only** — B needs explicit `compileOnly(project(":core"))` / `compileOnly(project(":agent"))` per A-module it imports.
- **Private B can't pass the Marketplace Plugin Verifier** → B's `verifyPlugin` is disabled; root `verifyPlugin` verifies A only.
- **`:core` ONE-`BasePlatformTestCase` invariant** — a 2nd platform fixture → deterministic headless "Indexing timeout" (#51). Pure-test seam/EP/DTO logic (extract pure helpers). All 0b-2 `:core` tests are pure JUnit5.
- **detekt/ktlint: AUTOCORRECT, do NOT baseline** (`./gradlew :<m>:detekt --auto-correct`). CI `check` runs detekt; tests/`verifyPlugin` don't. ⚠ adding imports can *unmask* a pre-existing `ImportOrdering` baseline entry — fix it genuinely (reorder), don't baseline. (A now-stale baseline entry for `BambooServiceImpl.kt` in `bamboo/detekt-baseline.xml` is harmless/unmatched — drop on next regen.)
- **`--no-build-cache --rerun-tasks`** on any commit changing a lambda/fn type to/from `suspend` OR adding a ctor param (stale `Function` bytecode → `NoSuchMethodError`).
- **No `runBlocking` in `main/`** (pre-commit hook; use `runBlockingCancellable`).
- New EP/seam interfaces B implements are **`public` + `@InternalApi`** (NEVER `internal`).
- **Webview build noise:** gradle regenerates `agent/src/main/resources/webview/dist/` (content-hash churn) on `verifyPlugin`/`buildPlugin` — never `git add` it; `git checkout -- agent/src/main/resources/webview/dist/` to drop it before committing.
- **Don't trust the implementer's report** — the controller runs the authoritative build itself; the green gate catches emergent aggregate failures per-task gates can't.

## Standing rules (from the user — keep)
- **Multiple INDEPENDENT review rounds at EVERY step** (`multi-round-review-plugin-split` memory) — not one pass. Per-task two-stage review (implementer self-review + independent task reviewer) + controller verify + a final whole-branch review for execution; plans get multi-lens review (bytecode-accuracy + completeness + skeptic). 0b-2 proved this catches what plan review alone misses.
- **Subagent models:** sonnet = floor (transcription tasks), opus = hardest/critical (keystone tasks, final reviews); **NEVER haiku** (`subagent-model-no-haiku` memory).
- **Process:** plan → multi-round review → `superpowers:subagent-driven-development` in a worktree off `feature/plugin-split` → per-task review → final whole-branch review → `finishing-a-development-branch` (ff-merge + push to origin). Track progress in `.superpowers/sdd/progress.md` (gitignored ledger; the recovery map across compaction). The pivotal API-shape fork is the user's call (0b-2: genuinely-neutral vs minimal-supertype).

## Memory (auto-loaded each session)
- `project-plugin-split-open-source-backbone` — full decision log + status (updated through 0b-2 merged/pushed + the MULTIPLE_DEFAULTS trap).
- `multi-round-review-plugin-split` — the review rule. · `subagent-model-no-haiku` — model floor.
- ⚠ **`MEMORY.md` index is ~40KB, over the 24.4KB load budget** — entries past the limit are silently dropped on load. Recommend a compaction pass (trim each entry to one line; detail already lives in topic files; merge/drop shipped-and-stale entries) early in the next session.

## To resume in a new session, say e.g.
- "Continue the plugin split — start Phase 0b-3 (ToolRegistrationService + AgentTool safety props): write the plan, multi-round review, then execute subagent-driven." OR
- "Compact my MEMORY.md index." OR
- "I ran runIde — here's the log: …" (paste) — diagnose against `RUNIDE-TEST-SCENARIOS.md`.
