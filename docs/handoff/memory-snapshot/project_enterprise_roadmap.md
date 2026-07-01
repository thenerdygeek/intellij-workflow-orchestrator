---
name: project_enterprise_roadmap
description: Enterprise-grade roadmap for the plugin — Phase 0 SHIPPED (merged); Phase 1 governance docs SHIPPED (PR #20 open); phases 2-5 + deferred coverage gate remain
metadata: 
  node_type: memory
  type: project
  originSessionId: 1cb8914a-6798-406e-bde5-7de7eb67a554
---

# Enterprise-Grade Roadmap — status & what's next

**Context:** Solo dev. Distribution model = **public core + per-company forks** (forks
customize via extension seams; clean seams + arch enforcement matter most). Roadmap spec:
`docs/superpowers/specs/2026-06-06-enterprise-grade-roadmap-design.md` (committed). Phase 0
plan: `docs/superpowers/plans/2026-06-06-phase0-enforcement-foundation.md` (gitignored, local
only — has the coverage-gate resume detail).

**Repo is PUBLIC** (`thenerdygeek/intellij-workflow-orchestrator`) → GitHub Actions minutes
are free/unlimited. Heavy CI runs cost nothing.

## Phase 0 — Enforcement Foundation: ✅ SHIPPED 2026-06-07 (merged PR #10, `main` @ `e8f3b665f`)

Delivered 4 of 5 gates, all green on Linux CI:
1. CI pipeline (`.github/workflows/ci.yml`): build-and-verify, test (full suite), lint (detekt), arch (konsist)
2. detekt 1.23.8 + detekt-formatting, **per-module** `<module>/detekt-baseline.xml` (shared baseline is overwritten last-write-wins)
3. Konsist `:konsist` module — module-boundary + layering arch tests (teeth-verified)
4. Dependabot (`.github/dependabot.yml`) + vuln alerts + auto-fixes enabled

**DEFERRED — coverage gate (Task 3):** blocked by Kover-instrumentation × MockK conflict —
applying Kover to `:agent` breaks 8 MockK tests (suspend-function-type mocks + reflective tool
construction in ToolDslSchemaParityTest) with `UnsupportedOperationException`; other 5,056 pass.
Resume path (smallest first): (a) exclude the ~3 conflicting test classes from Kover
instrumentation via `kover { currentProject { instrumentation { ... } } }`; (b) Kover↔MockK
compat settings; (c) run coverage in a dedicated CI job. All need ONE full instrumented
`koverXmlReport --no-configuration-cache` to read the baseline %, then set `koverVerify` floor.
Kover aggregation requires applying kover to EVERY module (root `kover(project(...))` else
variant-resolution fails) — that's what triggers the MockK conflict.

## ⚠ CI cross-platform traps discovered (all fixed, but remember for forks/new modules)

The full test suite had NEVER run on Linux before Phase 0. Fixes that landed:
- **verification-metadata.xml** must `<trust>` the synthetic/platform-specific groups: `idea`,
  `org.jetbrains.runtime`, `bundledPlugin`, `bundledModule` (per-OS or locally-generated hashes
  can't be pinned). Without these, Linux CI fails verifying `ideaIU-*.tar.gz` etc.
- **`gradle.properties` hardcodes `org.gradle.java.home`** (macOS Homebrew path) → every CI
  gradle call passes `-Dorg.gradle.java.home="$JAVA_HOME"`.
- **`--no-configuration-cache` on CI** build/verify/test (KotlinCompile classpath snapshot isn't
  CC-serializable on Gradle 9.4; ephemeral runner gains nothing from CC anyway). CC stays ON locally.
- **System-prompt snapshot tests were macOS-coupled**: `SystemPrompt.build`/`SubagentSystemPromptBuilder`
  now take an injectable `homeDir` param (was hardcoded `System.getProperty("user.home")`); both
  snapshot tests pin it. Regenerate goldens via the "generate all golden snapshots" tests.
- **Async test races flake on slower CI**: QueueServiceTest probed SQLite immediately after the
  in-memory stateFlow emptied, racing the async DELETE — now polls. Watch for this pattern.
- Test memory: CI test job capped `--max-workers=2` (4 GB heap/fork OOMs otherwise).

## Phase 1 — Governance docs: ✅ SHIPPED + MERGED 2026-06-07 (PR #20, `main` @ `bee4437e4`; all 4 CI gates green)

7 commits. Delivered: SECURITY.md, THREAT_MODEL.md, **FORKING.md** (keystone — 11 `:core` EPs
are the stable fork seam, all verified vs plugin.xml), CONTRIBUTING.md + .github/CODEOWNERS,
docs/adr/ (README + 0000-template + 6 backfilled ADRs 0001–0006), CHANGELOG.md populated
(Keep-a-Changelog) + fixed placeholder `pluginRepositoryUrl` (`example/`→`thenerdygeek/`) +
removed stale `.gitignore /CHANGELOG.md` rule that had kept it empty. Plan:
`docs/superpowers/plans/2026-06-07-phase1-governance-docs.md`. Built subagent-driven (impl +
spec-review + quality-review per task); reviews caught real bugs (non-existent "Basic (Nexus)"
auth claim — Nexus is DEFERRED/unimplemented, `ServiceType` has no NEXUS; brittle lockfile count;
the runblocking guard is a Claude Code hook not a git pre-commit hook [[runblocking-ban-pre-commit-hook]];
stale "9 submodules"→12; the .gitignore trap). ⚠ FOLLOW-UP: `CLAUDE.md` still says "9 submodules"
but `settings.gradle.kts` has 12 (:core,:document,:jira,:bamboo,:sonar,:pullrequest,:automation,
:handover,:agent,:web,:mock-server,:konsist) — fix when next touching CLAUDE.md.

## Autonomous run 2026-06-07 (after Phase 1 merge) — coverage gate DONE, Phase 2 A in review, Phase 3/4 specced

- ✅ **Coverage gate (Phase 0 gate 5/5) SHIPPED+MERGED** — PR #21 (Kover aggregation over 10 code
  modules; baseline 49.66%; `koverVerify` floor 48%; new `coverage` CI job) + PR #22 hotfix. main green.
  ⚠ Kover instruments EVERY test task once applied — gate instrumentation on `-Pcoverage`
  (`disabledForAll = !hasProperty("coverage")`) + `ignoreFailures` on the coverage run only, else the
  plain `test` job hits the Kover×MockK conflict (broader than the 3 `:agent` classes; also `:handover
  TemplateEditorCardTest`). Correctness=`test` job; floor=`coverage` job.
- ✅ **Phase 2 deliverable A (AuthProvider seam) MERGED** — PR #23 (main @ `e186b0a5e`). Public
  contract APPROVED as-is by user: synchronous `credentialFor(service): Credential?` (no OAuth/SSO in
  base — user confirmed no plans), sealed `Credential` Bearer/Token/Basic/Custom, keyed on
  `ServiceType` (YAGNI — no new backends planned). `DefaultAuthProvider` preserves prior behavior;
  `HttpClientFactory`/`AuthInterceptor` routed through it, back-compat ctor kept. Worktree removed.
  Research: `docs/superpowers/research/2026-06-07-phase2-design-inputs.md`.
- ✅ **Phase 2 COMPLETE** — B/C/D MERGED (PR #25, main @ `2b21f1d0e`). Phase 3/4 specs also merged (PR #24).
  B/C/D detail: B=`WorkflowConfig`
  EP + `DefaultWorkflowConfig` (`CredentialStore.serverUrlFor` delegates to it, DRY, behavior unchanged);
  C=`FeatureRegistry` EP + `PluginFeature` enum + `DefaultFeatureRegistry`→PluginSettings (additive seam;
  redirecting the :agent inline toggle checks = follow-up); D=`@StableApi`/`@InternalApi` + `docs/STABLE-API.md`
  (3 new seams annotated; in-code annotation of the 11 existing EPs = follow-up). :core:test + detekt green
  locally. All seams mirror AuthProvider (EP + lowest-priority default + runCatching `resolve()`).
- ▶ **Phase 3 extraction #1 DONE (local branch `phase3/extract-agent-monitor-coordinator`, UNCOMMITTED 2026-06-07):**
  monitor cluster pulled out of `AgentService` (4169 LOC) into `agent/.../monitor/AgentMonitorCoordinator.kt`
  (moved: `monitorManagers` map + `MonitorPersistence` + `monitorManagerFor`/`ensureMonitorManager`/
  `disposeMonitorsForSession`/`forgetMonitor`/`markMonitorsDormantForSession`/`clearPersistedMonitors`/
  `reArmMonitors` + MonitorBridge router/forget-callback/200ms-flush init wiring + dispose clearRouter).
  Side effects INJECTED (`activeLoopForSession` lambda, shared `idleWaker`, `agentDirProvider` — `agentDir`
  is lateinit so MUST be a provider) → cluster now unit-instantiable. AgentService keeps thin same-named
  delegators (external callers unchanged). TDD: new behavioral `AgentMonitorCoordinatorTest` (red→green).
  ⚠ The 2 source-text contract pins (`MonitorToolTest` ×2, `MonitorReArmIntegrationTest.case6` part-a) had to
  be REDIRECTED to read the new file (they `readText()` the .kt). detekt: fixed new-file formatting + surgically
  updated the ONE `ImportOrdering:AgentService.kt` baseline entry (it keys on the whole import-block text).
  `:agent:test` (full, --no-build-cache) + `:agent:detekt` GREEN. **SHIPPED as PR #26** (branch
  `phase3/extract-agent-monitor-coordinator`, pushed; left for review + `runIde` smoke, NOT auto-merged).
- ▶ **Phase 3 extraction #2 (cut D, Brain/model) DONE → PR #27** (branch `phase3/extract-brain-model`, off main,
  pushed). Extracted per-task brain construction + model-selection precedence from `AgentService.createBrain`
  into `agent/.../brain/BrainFactory.kt`. The precedence is carved into a PURE `suspend resolveModel(override,
  savedModel, fetchBest)` (override > saved settings > pickBest > LlmBrainFactory fallback) — the 2026-05-06
  5x-over-billing regression (saved chip must beat pickBest) is now a BEHAVIORAL test (`BrainModelResolutionTest`)
  not a source-text grep. `createBrain` stays a delegator (4 call sites unchanged). ⚠ scope-doc said "pure factory
  internally" but the SCOUT found `wrapBrainWithRouter`/shared `ModelCatalogService` holder/`activeAttachmentStore`/
  `currentBrainModelId` are session-state-coupled → DEFERRED to a later cut; only the genuinely-pure factory was
  taken. Redirected `CreateBrainModelPriorityTest` to BrainFactory.kt; removed unused `LlmBrainFactory` import +
  updated its ImportOrdering baseline entry. ⚠ `ModelCache.getModels` is suspend → `resolveModel`+`fetchBest` are
  suspend. `:agent:test`(full,--no-build-cache: 5068 pass, 1 UNRELATED flake `BackgroundProcessToolTest.send_stdin`
  passes in isolation) + `:agent:detekt` GREEN. Both PRs touch only the AgentService import block → trivial overlap.
  Remaining cuts (ascending risk): F background → G delegation wrappers → C resume → B `executeTask` (hardest);
  H cross-cuts. Resume scope: `docs/superpowers/research/2026-06-07-phase3-agentservice-decomposition-scope.md`.
- ▶ **Phase 3 extraction #3 (cut F, background-completion) DONE → PR #28** (branch
  `phase3/extract-background-completion`, off main, pushed). `tools/background/BackgroundCompletionCoordinator.kt`
  owns onBackgroundCompletion routing (test-capturer→live-loop steering→idle persist+auto-wake) + the BackgroundPool
  completion-listener subscription + the 2 synthetic-message builders (now PURE companion fns, pinned by
  `BackgroundCompletionCoordinatorTest` — the "AutoWakeSyntheticMessageFormatTest the KDoc promised but never had").
  ⚠ KEY: the SHARED auto-wake substrate (idleWaker/autoWakeGuards/autoWakeListener/autoWakeIdleSession/
  activeLoopForSession) STAYS on AgentService — monitor(E)+background(F)+delegation(G) all wake through ONE guard, so
  no coordinator can own it; injected as `autoWake` lambda. NO source-text redirects (the only pin SafeAutoWakeRouteTest
  asserts idleWaker wiring which stays); F code used FQNs so NO import/baseline churn. AgentService keeps a
  setSteeringCapturerForTest delegator + registers coordinator for disposal. `:agent:test`(full)+`:agent:detekt` GREEN.
  Smoke scenarios for E/D/F in `PHASE3-SMOKE-TESTS.md` (repo root, committed on F branch). Smoke deferred to a batch at
  the end per user. **3 PRs open (#26 monitor, #27 brain, #28 background), all independent off main, awaiting review+smoke.**
  Remaining: G delegation wrappers → C resumeSession → B executeTask (hardest, last); H cross-cuts.
- ⚠ **SCOPE-DOC ORDERING CORRECTION (scouted 2026-06-07, cut G):** the scope doc lists G (delegation
  wrappers) BEFORE B, but that DOESN'T hold — `startDelegatedSession`/`resumeDelegatedSession` pass the full
  24-field `SessionUiCallbacks` bundle straight INTO `executeTask`/`resumeSession` (= B/C) and lean on private
  helpers (`sessionStateFor`, `mapLoopResultToDelegationResult`, `activeDelegatedSessions`). Extracting them
  before B exists as a service = injecting the god-function as a lambda (fragile). **G's wrappers must come
  AFTER B.** The only separable delegation piece (`enqueueNudgeForSession`+`persistDelegationNudge`+
  `appendDelegationCardToSession`) is a POOR cut: 9 external callers (must stay public delegators), 2 source-text
  pins to redirect (`DelegationConversationNarrationTest` ln162, `DelegationIdleWakeRoutingTest` ln70), and its
  routing is ALREADY pure-extracted as `idleWakeRoute`. Net ~65 LOC, marginal win → SKIPPED. **The clean
  loose-cluster cuts (E/D/F) are DONE; what remains (B/C/G) is the invasive coupled CORE.** Recommended next:
  land+review+smoke #26/#27/#28 FIRST (settle the base), then tackle B (executeTask) as the foundation, then C, then G.
- ▶ **Phase 3 cut B INCISION 1 DONE → PR #29** (branch `phase3/extract-executetask-helper`, off main, pushed).
  ⚠ KEY DISCIPLINE: `executeTask` (~1090 LOC) CANNOT be characterization-tested (non-instantiable @Service +
  full platform), so the ONLY safe extractions are PURE slices pullable out + tested in isolation. First one:
  `loop/NetworkRecoveryPolicy.kt` — the L2-tier-escalation gating decision (effectiveStrategy/fallbackChainOrNull/
  compactOnTimeoutExhaustion), billing-adjacent (gates silent model-switching). Behavior + log msgs preserved
  EXACTLY; `cachedFallbackChain` var name unchanged so the SpawnAgentTool*/SubagentRunner* wiring source-pins
  still hold (NO redirects). Pinned by `NetworkRecoveryPolicyTest`. `:agent:test`(full)+detekt GREEN. **executeTask
  needs MANY more such pure incisions; this established the pattern.** **4 PRs now open: #26 monitor, #27 brain,
  #28 background, #29 executeTask-incision-1 — all independent off main, awaiting review+smoke.** Smoke checklist
  `PHASE3-SMOKE-TESTS.md` lives on the #28 branch (B's S-B1/S-B2 are in the #29 PR body). Next safe options:
  more executeTask pure incisions (e.g. the brainFactory/recycle-counter block is stateful=NOT pure→skip;
  look for other pure decisions), OR C resumeSession (also coupled), OR checkpoint to merge the 4 PRs first.
  ⚠ detekt-baseline keys ImportOrdering on the WHOLE import-block text → editing AgentService imports requires
  surgically updating its one baseline entry (recurring in every AgentService cut).
- 🔶 **Phase 3 + Phase 4 specs** — PR #24: `docs/superpowers/specs/2026-06-07-phase3-architecture-decomposition-spec.md`
  + `...phase4-enterprise-features-spec.md`. Phase 4 has product/legal DECISIONS for the user
  (telemetry opt-in default, privacy wording, base policy set).
- ✅ **FLAKY test FIXED → PR #30** (branch `chore/deflake-session-document-artifact-test`): `SessionDocumentArtifactServiceTest`
  was intermittently redding CI's Tests job (failed on #28/#29). ROOT CAUSE: 4 extraction-path tests drove a real
  `cs.async(Dispatchers.IO)` job from `runBlocking` then asserted — cross-thread handoff has no ordering guarantee,
  reds under CI `--max-workers=2` contention, passes on roomy local box. FIX (inject-the-dispatcher): production gets
  `ioDispatcher: CoroutineDispatcher = Dispatchers.IO` (default → byte-identical prod behavior), tests use
  `runTest`+`StandardTestDispatcher` (shared testScheduler, injected as cs+ioDispatcher) → single deterministic
  scheduler, no real threads. ⚠ MUST use StandardTestDispatcher NOT Unconfined: Unconfined runs the job eagerly→
  `invokeOnCompletion{inFlight.remove}` fires re-entrantly inside `ConcurrentHashMap.computeIfAbsent`→IllegalStateException
  (Recursive update); never happens in prod (real IO thread, bin-lock serializes). Verified 20/20 stress runs green
  + full :agent:test + detekt. **5 PRs now open: #26 monitor, #27 brain, #28 background, #29 executeTask-incision-1,
  #30 de-flake. Merge #30 FIRST → reruns of #28/#29 Tests job go green.**
- ✅ **ALL 5 Phase 3 PRs MERGED 2026-06-07 (main @ `a6f650a8a`).** Order: #30 (de-flake) → #26 (monitor, CLEAN)
  → #29 (NetworkRecoveryPolicy, CLEAN) → #27 (BrainFactory, rebased) → #28 (BackgroundCompletionCoordinator,
  rebased). Each merged one-at-a-time after its own CI went 5/5 green. #27 rebase resolved the
  `detekt-baseline.xml ImportOrdering:AgentService.kt` entry (dropped `LlmBrainFactory` token to match source) — no
  source conflict (AgentService.kt auto-merged). #28 rebased onto the FULL main (picks up #30's de-flake) → only
  `agent/CLAUDE.md` conflicted (merged the cut-D + cut-F AgentService bullets). #28's green CI run therefore
  validated the whole 5-way combination. ⚠ NEW rebase-conflict pattern for AgentService cuts: conflicts land in TWO
  files — `agent/detekt-baseline.xml` (import-block entry) AND `agent/CLAUDE.md` (each cut self-documents in the same
  "Key Components → AgentService" bullet; merge the notes, don't pick one side).
- ✅ **Phase 3 cut B INCISION 2 MERGED → PR #31** (main @ `489f85faf`, CI 5/5 green, branch deleted).
- ✅ **Phase 3 cut B INCISION 3 MERGED → PR #32** (main @ `4d61463b1`, CI 5/5 green, branch deleted). TDD-extracted `tools/ToolDefinitionFilter.shouldInclude(toolName, isPlanMode,
  isDelegatedSession, hasSkills, writeToolNames)` — the LLM tool-visibility predicate (use_skill gating +
  act-only delegated drops both plan tools + plan/act split) out of `executeTask`'s `toolDefinitionProvider`
  closure. FQN call site → NO import/baseline churn. KEY: consolidated 2 pre-existing tests onto the real fn —
  `AgentServiceToolFilterTest.filterForMode` (was a hand-ported dup, now delegates, 17 cases) +
  `DelegatedActOnlyToolFilterTest` (was a source-text grep on AgentService.kt, now behavioral, 3 cases) + new
  `ToolDefinitionFilterTest` (4 cases). Full :agent:test + detekt green local. Next executeTask candidates are
  thinning — remaining inline logic is increasingly stateful (brain wiring, coroutine launch, counters = NOT pure). TDD-extracted `NetworkRecoveryPolicy.resolveFallbackChain(strategy, buildChain) →
  FallbackChainResolution(chain, reason)` — folds the inline 3-way branch (none→STRATEGY_NONE / ≤1-tier→
  CHAIN_TOO_SHORT / else→CHAIN_AVAILABLE) out of `executeTask`. ⚠ chain built LAZILY so the none opt-out still
  never calls `ModelCache.buildFallbackChain` (short-circuit preserved + asserted). `cachedFallbackChain` local
  KEPT (SubagentRunner/SpawnAgentTool source-pins hold); FQNs used → NO import/baseline churn. 3 new
  NetworkRecoveryPolicyTest cases (9 total in class). Full :agent:test + detekt green local. NOT merged yet.
  Scout found next candidate after this = the plan-mode tool-definition filter (~14 LOC, AgentService.kt:2050).
- ▶ **NEXT: runIde smoke batch is now DUE** (deferred per user; the 5 landed without it). Checklist
  `PHASE3-SMOKE-TESTS.md` (now on main). Then resume the high-risk coupled core: more `executeTask` (B) PURE
  incisions → C `resumeSession` → G delegation wrappers (G AFTER B, scope-doc ordering is wrong). E/D/F loose-cluster
  cuts are all DONE+MERGED.
- ⚠ **No branch-protection**: `gh pr merge` merges IMMEDIATELY (caused the #21→#22 regression).
  Recommend requiring the 5 CI checks. Use poll-then-merge meanwhile.
- Handoff: `PHASE-RUN-STATUS.md` (repo root, untracked).

## ▶ NEXT-SESSION RESUME POINT (2026-06-09, UPDATED — big decomposition session + phase3.3 release)

**11 Phase-3 incisions merged green @ main `6e0af879a`** (PRs #35-#45, one per incision, TDD, full module
test+detekt + 5/5 CI each) PLUS **shipped `v0.86.0-phase3.3`** (merged PR #34 runtime_exec streaming fix +
from-scratch clean build + GitHub release w/ ZIP).
- **AgentService** cut C (`resumeSession`): #35 preamble formatters, #38 history-cleanup transforms, #39
  TASK_RESUME cancel note → all `ResumeHelper`. cut G (delegation): #36 `DelegationTargetComposer`, #37
  `delegationResultForDeliveryFailure`.
- **AgentController** (~5.4K): #40 `ModelVersionOrdering`, #41 `AttachmentMimeTypes`, #42 `AnswerPayloadEnricher`.
- **PrDetailPanel** (~2.5K): #43 `PrActivityGrouping`, #44 `MergeButtonStateDeriver`, #45 `BuildStatusBadgeDeriver`.
- **HIGH-VALUE PURE SLICES EXHAUSTED in all 3 files.** Remaining in each = THIN verb-maps/serializers/badge
  builders (deliberately left to avoid padding). Cut G whole-cluster lift still BLOCKED until B/C are services.
  For fresh high-value slices next time, scout a NEW god-file (`:bamboo`/`:automation` panels, etc.).
- ⚠ Traps that bit (all caught by FULL module test/detekt, not `--tests`): source-text sentinel-slice
  ([[project_source_text_sentinel_slice_trap]]); `ImportOrdering:AgentService.kt` baseline pins the whole import
  block (surgical baseline edit on import removal); ktlint wrapping fires on moved/new code.
- Fresh full handoff: `PHASE-RUN-STATUS.md`. Loose ends: data-loss bug #3 (higher severity), Windows smoke debt.

### Pre-decomposition state (still-open loose ends, NOT done this session):
Long bug-fix session done; user is on released build **v0.86.0-phase3.2** and happy with it. Wants to
resume Phase 3 decomposition next session. State:
- **main @ `8b6216992`** (= phase3.2 release commit). Local on main, clean. *(SUPERSEDED — now `5b5dd7d0b`.)*
- **PR #34 OPEN + green + MERGEABLE, NOT merged** (`fix/runtime-exec-streaming`): adds java_runtime_exec/
  python_runtime_exec/runtime_exec/coverage to `AgentLoop.STREAMING_TOOLS` so run_tests shell-fallback
  output + heartbeat actually stream (phase3.2 shipped the heartbeat behind a dead `toolCallId==null` gate).
  ⚠ phase3.2 still shows the silent "executing" spinner for use_native_runner=false — #34 fixes it.
  DECISION for next session: merge #34 + cut **v0.86.0-phase3.3** (from-scratch clean build like phase3.2 was).
- **Deferred bug #3** (data-loss): sub-agent edit_file edits occasionally not persisting / false-success;
  needs timing-safe post-write verification. See [[project_subagent_contextbar_bugfixes]].
- **Windows smoke debt**: lots merged since phase3.1 with no live-IDE smoke pass.

**NEXT PHASE 3 WORK** = continue executeTask (B) decomposition: the cheap PURE incisions are mostly used up
(did NetworkRecoveryPolicy #29, resolveFallbackChain #31, ToolDefinitionFilter #32) — remaining inline B logic
is increasingly stateful (brain wiring/coroutine launch/counters = NOT pure). So the next target is **C
`resumeSession`** (coupled variant of B; look for pure preamble/decision slices) → then **G delegation
wrappers** (BLOCKED until B/C exist as services). `AgentController` (~5.4K) + `PrDetailPanel` also untouched.
Scope/map: `docs/superpowers/research/2026-06-07-phase3-agentservice-decomposition-scope.md`.
Pattern that's worked all 3 incisions: extract a pure decision → unit-test it (TDD) → delegate from the
god-function → FQN at call site (avoids detekt-baseline import churn) → `--no-build-cache` on ctor/data-class
signature changes (recurring trap). One PR per incision, watch CI, merge.

## What's next (pick up here)

Recommended order (each phase = its own writing-plans → subagent-driven-development cycle):
- **Phase 2 — Forkability & extensibility seams** (~2 weeks): mark stable EPs/interfaces vs internal;
  config-driven company-variable behavior; capability/feature-flag framework; `AuthProvider` EP (forks
  plug in SSO/SAML/licensing without touching `:core`). Lean on existing 8 EPs + optional-dependency config-files.
- **Phase 3 — Architecture decomposition** (high risk, gated on safety nets now in place): AgentController
  5.4K, AgentService 4.2K, AgentLoop, PrDetailPanel, large tools. Characterization tests first.
- **Phase 4 — Enterprise product features**: audit-log subsystem (formalize AgentFileLogger JSONL),
  admin/policy controls, telemetry opt-in/out + privacy disclosure, proxy/on-prem hardening, compat matrix.
- **Phase 5 — Release & supply-chain automation**: automated signed release on tag, SBOM (CycloneDX),
  CHANGELOG via plugin, reproducible-build verification.
- **Also pending:** the deferred coverage gate (small, scoped — see above). A Dependabot postcss PR
  is already open from Phase 0's CVE scanning.

See [[project_token_context_optimization]] for the prior branch (merged before this work started).

## ✅ Enterprise-Hardening WAVE 1 — PR #67 OPEN + ALL 5 CI GREEN + MERGEABLE=CLEAN 2026-06-22 (branch `worktree-enterprise-hardening-wave1`, head `bca8f82c0`; worktree kept alive)

⚠ **REBASED onto post-#65 main during review:** main advanced `81c9d1b8a`→`38e7ee9c2` (#65 run_command
auto-approve merged) while the PR was open. CI tests `merge(branch, currentMain)` so it surfaced 2 real
failures invisible locally: (1) D4 `logToolCall` called `PluginSettings.getInstance(project)` at the hot
path → ClassCastException against #65's `AgentLoopAutoApproveTest` relaxed-mock Project (the documented
"no project-service on the hot path with mock projects" trap) → FIXED by injecting `includeCommandOutputInLogs:
() -> Boolean = { false }` into AgentLoop, resolved against the real project by AgentService (mirrors #65's
`autoApproveSafeCommands` injection); (2) 3+4 :core detekt misses (I'd only re-run `:agent:detekt`, not
`:core:detekt`, after fixing :agent's 17). ⚠ AgentLoop CTOR-SIGNATURE change → stale-cache NoSuchMethodError
locally → re-verified with `--no-build-cache --rerun-tasks --continue` (all green). ⚠ `git add -A` swept in
webview `dist/` build-hash churn → restored from origin/main (my work never touches webview). ⚠ CI `Tests`
job flaked on `BackgroundProcessToolTest` (real-process timing, passes locally; failure MIGRATED between runs
= flake signature, issue #51) → `gh run rerun --failed` → GREEN. Final: detekt/Tests/Konsist/Build/Coverage
all pass, mergeStateStatus=CLEAN. User to merge (no branch protection → manual). Lesson: after branching,
main can move; a green-locally PR can red on CI's merge-with-main — rebase + re-verify before trusting.

Driven by a 4-agent parallel read-only audit (security/reliability/supply-chain/audit) of main. The
codebase was already mature (SSRF/TLS/deser/JCEF/EDT/PathValidator-on-writes all verified clean). 8
commits, **all module tests + detekt GREEN** (`:core:test` `:agent:test` `:core:detekt` `:agent:detekt`).
Spec: `docs/superpowers/specs/2026-06-21-enterprise-hardening-wave1-design.md`. Every fix TDD'd (red→green).

**Security (`0c3969d14` + `0102c3abd`):** A1 [P0] `monitor(source=shell)` now runs the LLM command
through `DefaultCommandFilter` before spawn (was unguarded RCE; closed the `TODO(Phase2)` in
`ShellCommandSource`). A2 [P0] `read_document` enforces `PathValidator.resolveAndValidateForRead` (was
arbitrary-file-read sink) — ⚠ required updating ~22 DocumentTool tests that used out-of-tree `/tmp` paths
(set `basePath="/tmp"`). A3 [P1] new `SessionIdValidator` guards showSession/resumeSession/
formatSessionAsMarkdown vs `../` ids. A4 [P1] new `:core` `OwnerOnlyFile` makes api-debug/raw-trace/
pre-sanitize dump dirs `rwx------` (`:core` can't use `:agent`'s internal applyOwnerOnlyPerms).

**Reliability (`c085a3ea9` + `0102c3abd`):** B1 `AutoWakeGuardState.decide()` now atomic (per-session
lock; deterministic concurrency test). B2/B3 `releaseSessionState` frees `sessionQueues` + the dead
`autoWakeGuards.resetSession`. B4 Background/QueuePersistence log+self-heal on corrupt file. B5
EditFileTool 3 write tiers rethrow CancellationException. B6 `ModelCache.models/lastFetchMs` @Volatile.

**Audit (`01b82a44e`):** D1/D2/D4 wired the 3 DEAD `TelemetryConfigurable` toggles (`retentionDays`,
`diagnosticJsonlEnabled`→`AgentFileLogger(enabled=)`, `includeCommandOutputInLogs`) + D3 task summary
in session_start. AgentService/AgentLoop wiring pinned by source-contract tests.

**Supply-chain (`8d27b57e1`, CI config by a sonnet subagent):** C1 actions→SHA-pinned, C2
wrapper-validation per job, C3 per-job least-priv permissions, C4 npm Dependabot, C6 new `release.yml`
(signed-gated-on-secrets + attest-build-provenance + gh release), C7 reproducible archives
(AbstractArchiveTask in allprojects), C8 wrapper distributionSha256Sum (gradlew verified it).

**DEFERRED (verify-don't-trust paid off):** find/tee auto-approve guard + approval-source-audit →
belong on PR #65 branch (depend on `CommandShape`, not on main). **D5 raw-trace disclosure: re-assessed
as NOT a real gap on main** — no editable UI toggle, redactPromptBody already defaults true + test-pinned,
pruning already wired; audit over-stated it.

**C5 SBOM — DONE in follow-up (`1c55894ca`+`4337e69ec`, pushed to PR #67), Phase 5 now complete.** Applied
`org.cyclonedx.bom` 3.2.4 at root (libs.versions.toml + build.gradle.kts) → `cyclonedxBom` aggregates all
submodules into `build/reports/cyclonedx/bom.json` (CycloneDX 1.6, 410 components). ⚠ strict
`verify-metadata=true`: `./gradlew --write-verification-metadata sha256 cyclonedxBom --no-configuration-cache`
added +1096 SHA-256 entries to verification-metadata.xml (PURELY ADDITIVE — git diff --stat showed +1096/-0,
no existing entries touched/reordered). Verified `cyclonedxBom` passes under STRICT verify (not just write-mode)
+ verifyPlugin unaffected by the plugin application. release.yml SBOM step de-`continue-on-error`'d + asset path
fixed to `build/reports/cyclonedx/bom.json`. ⚠ cyclonedx 3.x is NOT config-cache-compatible → always
`--no-configuration-cache` for cyclonedxBom (release.yml already does). **Phase 5 remaining = only the LIVE
signed-release run** (needs repo CERTIFICATE_CHAIN/PRIVATE_KEY secrets + a `v*` tag — workflow skips signing
cleanly when absent).

## ⏸ PHASE 4 (Enterprise Product Features) — DEFERRED BY USER 2026-06-22 until the plugin is FEATURE-COMPLETE

User decision (2026-06-22): **do NOT start Phase 4 yet — revisit AFTER the plugin itself is feature-complete.**
Don't proactively begin Phase 4; wait for the user to say the plugin is done. Spec:
`docs/superpowers/specs/2026-06-07-phase4-enterprise-features-spec.md`. Phase 4 = the org/admin controls for
deploying the agent fleet-wide (not per-user): **(1)** audit-log subsystem (typed event schema in :core +
queryable ToolResult service — Wave-1 D1-D4 only chipped retention/toggle/task-summary, schema+service NOT
built); **(2)** admin/policy controls = the headline gap (expand `PluginFeature` to gate run_command /
auto-approve / sub-agents / file-writes + central admin-locked settings, enforced via the Phase 2
FeatureRegistry/capability framework); **(3)** telemetry opt-in/out + privacy disclosure; **(4)** proxy /
on-prem / configurable-LLM-endpoint hardening; **(5)** tested Jira/Bamboo/Bitbucket/Sonar compat matrix.
Decision-free plumbing (audit subsystem, proxy/endpoint, compat matrix, policy *mechanism* with no defaults)
can start whenever resumed; **3 product/legal DECISIONS gate the rest, USER's call:** telemetry default
(rec: opt-in/OFF), privacy-disclosure wording, which admin policies ship in base vs forks.

⚠ **detekt trap hit + fixed:** adding imports to EditFileTool/MonitorTool/QueuePersistenceTest (which have
ImportOrdering baseline entries keyed on the whole import block) broke the baseline → reverted to FQN at
call sites (the documented anti-churn pattern) rather than editing the baseline XML. ⚠ FQN'd a
`/tmp`-path File() pushed a test line >120 → extract to a `val path`. Local gradle: do NOT pass
`-Dorg.gradle.java.home=$JAVA_HOME` ($JAVA_HOME empty in this shell; gradle.properties has the path).
