---
name: project_subagent_contextbar_bugfixes
description: 2026-06-08 subagent + context-bar bug-fix batch (PR
metadata: 
  node_type: memory
  type: project
  originSessionId: ac73cd09-203e-4cd4-baf3-39f23801e97f
---

# Subagent + context-bar bug fixes — PR #33 ✅ MERGED 2026-06-08 (main @ c9b8d693d)

Combined PR, root-caused (systematic-debugging) + TDD'd, CI 5/5 green, MERGED. ✅ RELEASED as
**v0.86.0-phase3.2** (2026-06-08, main @ 8b6216992) — built fully from scratch (cleared Gradle
build-cache-1 + project .gradle + webview dist, `--no-build-cache --no-configuration-cache --rerun-tasks`,
verifyPlugin passed). Release bundles #33 + Phase 3 incisions 2 (#31)/3 (#32). ⚠ NOT Windows-smoke-tested yet. Bundled 9 fixes total
(see list below): render_artifact exclusion, subagent status-leak→card, token=0, bar-frozen, bar-max,
plan-mode guard, context-bar live-refresh, + 3 test-runner fixes (#1a/#1b/#2). #3 (subagent edit
persistence) DEFERRED.

- **#1 render_artifact in subagents** — subagents have no chat surface (output → task_report; render
  returns Skipped(headless)). Fixed at the tool filter: `SpawnAgentTool.resolveConfigToolsTiered`
  now drops render_artifact (core + deferred), like agent/attempt_completion. Removed it from 6
  persona configs + explorer's "Producing Visualizations" section + 4 "Visualization" blockquotes.
  Regenerated code-reviewer/architect-reviewer subagent snapshots. Pin: SpawnAgentToolTest.
- **#2 subagent retry/compaction leaked to MAIN chat** — SubagentRunner forwarded the orchestrator's
  onRetry/onCompactionState (→ main chat pill/overlay) into the subagent loop. Severed the chain
  (removed params: SubagentRunner ← SpawnAgentTool ← AgentService liveOnRetry/liveOnCompactionState,
  all deleted). Routed subagent retry/compaction to its OWN CARD via onProgress + new
  `SubagentProgressUpdate.statusNote`/`statusNoteSet`; new `AgentCefPanel.setSubAgentStatusNote` +
  AgentDashboardPanel delegate + AgentController handler + webview (types.ts SubAgentState.statusNote,
  chatStore reducer, jcef-bridge reg, SubAgentView render). Updated wiring tests (ParityTest,
  SubagentRunnerWiringTest, AgentServiceSpawnWiringTest) to drop the 2 params.
- **#3 subagent token=0 + #4 main context bar frozen** — SAME root cause: AgentLoop updated tokens
  ONLY inside `response.usage?.let{}`; Sourcegraph streaming often returns usage==null → lastPromptTokens
  froze (bar stuck) + subagent stats stayed 0. Fix: pure `loop/TokenUsageEstimator.reportFor(usage,
  promptEstimate, responseText, estimate)` → falls back to estimate when usage null; AgentLoop reports
  EVERY turn (updateTokens + onTokenUpdate moved out of the usage?.let block). Pin: TokenUsageEstimatorTest.
  Also fixes #5 (post-compaction lag).
- **#4b context bar showed 93K not 132K** — the bar keyed max off saved `AgentSettings.sourcegraphChatModel`
  (BLANK on auto-pick → `cm.maxInputTokens` 90K fallback) instead of the resolved model. Fixed: provider
  now uses `ContextManager.effectiveMaxInputTokens()` (keyed on currentBrainModelId — the SAME source the
  88% compaction trigger uses). Pin: ContextUsageProviderSourceTest. ⚠ investigator wrongly blamed catalog
  warm-up TIMING; real cause was the saved-setting-vs-resolved-model KEY mismatch (verified: blank setting
  → fallback).

- **#5 main-agent plan-mode guard (added to PR #33)** — after Approve (live→ACT) edit_file was
  "blocked in plan mode" while env said ACT. Root cause: TWO sources of truth — env_details + schema
  filter read LIVE isPlanModeActive(), but AgentLoop's write-guard + plan-wait read AgentLoop.planMode
  (construction-time snapshot val, never updated). Fix: added `AgentLoop.planModeProvider: () -> Boolean`;
  guard + wait use `planModeProvider?.invoke() ?: planMode`; AgentService wires `{ isPlanModeActive() }`;
  subagents/tests keep snapshot. Pinned by 2 new PlanModeWriteActionGuardTest cases (+ ModelPricingRegistry
  leak-cleanup AfterEach). ⚠ NOTE: subagent read-only is ALSO (separately) enforced via planMode=isReadOnly
  (SpawnAgentTool.executeSingle 6th arg + line 942 hardcoded) with inferPlanMode = "no CORE write tools" —
  a latent conflation worth decoupling later (read-only should withhold write tools, not fake plan mode).
- **#6 context bar not live after compaction/new_task (added to PR #33)** — bar is pull-only (1s poll,
  paused on document.hidden). Added `AgentCefPanel.refreshContextUsage()` → fires `wf-context-usage-refresh`
  window event; UsageIndicator re-fetches immediately (bypassing the poll guard). AgentController fires it on
  compaction-done (onCompactionState !active) + onContextManagerReady (new/handoff session). Values were
  already correct via #3/#4b; this guarantees the bar re-reads promptly.

- **#1a/#1b/#2 test-runner fixes (added to PR #33)** — from a multi-module project agent.
  #1a Maven method-targeting: extracted pure `JavaRuntimeExecTool.buildMavenTestArgv` — multi-module now
  scoped via `-pl <relPath> -am` from the reactor ROOT (workDir=baseDir; was cd-into-submodule + --also-make)
  + `-Dsurefire.failIfNoSpecifiedTests=false`; `-Dtest=Class#m(+m2)` filter preserved. Pinned by ShellFallbackTest.
  ⚠ whether Surefire honors `#method` is PROJECT-config-dependent (old JUnit5 providers ignore it → whole class
  runs) — can't fix plugin-side; the native runner is the reliable method-targeting path.
  #1b "looked stuck": Maven runs with `-q` (silent) → added immediate launch line + 12s heartbeat
  (HEARTBEAT_INTERVAL_MS) to the LIVE UI stream only (not captured output) so long runs don't look hung.
  #2 native "setup returned null without a specific reason": every createJUnitRunSettings null path already
  fail()s with a reason (incl. a good multi-module module-resolution msg); the blank case now yields an
  actionable fallback message (multi-module resolution / indexing / runner-incompat). JavaRuntimeExecTool has
  NO logger (don't add LOG.x).
- ✅ **#3 subagent edit_file silent no-op false-success — REPRODUCED + FIXED 2026-06-09 (PR #46, branch
  `fix/edit-file-silent-noop-data-loss`)**. ROOT CAUSE pinned: `EditFileTool.writeViaDocument` returned
  `true` even when it replaced nothing — `document.text.indexOf(oldString)` could be -1 (the validated
  `content` diverges from the Document via the readAction→java.io fallback / a VFS-Document reload between
  read and EDT-write / an external write behind the VFS), the `if (offset>=0)` replace silently no-op'd,
  `saveDocument` no-op'd, and it returned true → `execute()`'s `writeViaDocument || writeViaVfs ||
  writeViaFileIo` chain short-circuited → edit reported done, file unchanged. NOT a stale-bytecode/JPS issue;
  NOT multi-module-path. FIX: track `replaced`, return false on no-op so the `||` chain falls through to
  writeViaVfs which writes the full validated newContent (and reloads the Document) — the edit SELF-HEALS and
  lands (better than post-write verification which only fails loudly). Reproduced DETERMINISTICALLY by
  `EditFilePersistenceFixtureTest` (new `BasePlatformTestCase` driving real `writeViaDocument` against an
  in-memory `LightVirtualFile` — no LocalFileSystem/disk/indexing); verified clean RED→GREEN. See
  [[project_agent_platform_fixture_tests]] for the :agent BasePlatformTestCase infra + gotchas this added.

⚠ **build-cache trap recurred**: changing SubagentProgressUpdate fields + SpawnAgentTool ctor caused
NoSuchMethodError in tests (stale bytecode). Always `--no-build-cache --rerun-tasks` for ctor/data-class
signature changes (CLAUDE.md). ⚠ detekt wrapping after edits → fixed via re-wrap + surgical
detekt-baseline update of the `Wrapping:AgentService.kt$...SpawnAgentTool(...)` entry signature.
