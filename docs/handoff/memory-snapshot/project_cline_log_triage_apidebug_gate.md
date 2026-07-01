---
name: project_cline_log_triage_apidebug_gate
description: "Triage of a Cline-on-Windows-IDE-logs perf report (2026-06-19): 5/6 plugin findings already fixed by the perf campaign + earlier work; the one actionable item — always-on api-debug disk dumps — gated behind a new default-OFF setting"
metadata: 
  node_type: memory
  type: project
  originSessionId: 81fd9911-d3eb-4f23-89cb-908402bade44
---

2026-06-19: User ran Cline over their Windows IDE logs (IntelliJ 2025.3.3, JBR 21, they'd
bumped heap to 8GB to stop freezes) and brought back a perf/hang report on the plugin
(v0.85.x era). I verified all findings against current code (0.87.2, post-perf-campaign) with 5
parallel Explore agents + direct checks.

**Headline (Cline got it right):** the IDE-wide freezes are NOT the plugin — every freeze thread
dump had the EDT in platform code (Islands UI paint, EditorConfig disk IO, write-intent lock),
zero `com.workflow.orchestrator` frames. Root cause is environmental: antivirus real-time scan +
OneDrive-synced `Documents` strangling disk IO (git tag 14s, git ls-remote 146s). Durable fix =
Defender exclusions for project/.git/AppData/.m2 + move repo off OneDrive. The 8GB heap helped
because JCEF/Chromium holds NATIVE memory outside the JVM heap.

**Triage of the 6 plugin-attributable sub-findings — 5 already fixed (report predates the work):**
1. `BuildProjectModulesAction` read-access SEVERE → FIXED: `executeProjectModules` wraps
   `collectProjectModulesSnapshot()` (incl. `tryGetMavenInfo`) in `readAction { }`.
2. Gradle `NoClassDefFoundError` on Maven projects → FIXED: layered `try/catch (LinkageError)` in
   `GradleSonarKeyDetector.detect()` + outer guard in `SonarKeyDetector.detectForPath()`.
3. `IndexNotReadyException` dumb-mode → FIXED: AutoDetect gated by `DumbService.runWhenSmart`;
   PSI/index tools use `smartReadAction`. (`VulnerableApiService` no longer exists in the tree.)
4. Disposal races + 371 `jcef_chromium_*.log` churn → FIXED by perf Wave 4 (just merged): lazy tab
   materialization, Content-scoped disposal, ONE Chromium reused per session, AgentDashboardPanel
   is Disposable, self-healing AgentConfigLoader singleton; no `JBAnimator` anywhere.
5a. Context grew to 253 msgs / 109% window → FIXED by the 2026-05-17 ContextManager redesign
    (single 88% gate vs effective window, synchronous pre-call compaction).
5b. `call-NNN-request.txt` dumps (200–280 KB) written on EVERY call → **this was the one real
    open item.** `AgentService` wired `setApiDebugDir(sessionDebugDir)` unconditionally — always-on,
    no setting. On the user's AV-scanned/OneDrive disk every dump triggers a Defender scan.

**Fix (user chose "gate behind setting, default OFF"), TDD + systematic-debugging. MERGED to main
2026-06-19 via PR #60 squash → `6cd1440ee` (all CI green incl. Build & Verify Plugin; branch
`perf/api-debug-dumps-opt-in` deleted). No version bump / release cut — folded onto main on top of
0.87.2:**
- New `AgentSettings.State.writeApiDebugDumps` (default `false`) + a checkbox in
  `AgentAdvancedConfigurable` ("Debugging & Diagnostics" → "Write API debug dumps to disk").
- **Single gate in `AgentService`:** `val apiDebugDir = sessionDebugDir.takeIf { writeApiDebugDumps }`
  (null when off), threaded to the initial brain, recycled/fallback brains (`.also { b -> ... }`),
  the recycle-marker write (`apiDebugDir?.let`), and `spawnAgentTool.sessionDebugDir`.
- ⚠ **Regression I hit + the lesson:** my first attempt put the gate INSIDE
  `SpawnAgentTool.subagentDebugDir` via `AgentSettings.getInstance(project)`. That coupled the
  sub-agent hot path to a project service → 18 `SpawnAgentToolTest`/`ParallelSubagentIntegrationTest`
  failures (`ClassCastException` — those tests build SpawnAgentTool with a bare `mockk<Project>`
  that has no AgentSettings service). Root cause: don't reach into project services from
  SpawnAgentTool. `sessionDebugDir` was ALREADY the single lever (its only use is
  `subagentDebugDir`'s `?: return null`), so gate at the AgentService ASSIGNMENT and keep
  SpawnAgentTool settings-free. `setApiDebugDir(dir: File?)` accepts null (clean disable).
- Tests: `AgentSettingsApiDebugDumpTest` (defaults) + `ApiDebugDumpGatingContractTest` (source-text
  contract — AgentService isn't unit-instantiable; pins the gated `apiDebugDir` reaching brain +
  sub-agent, and that SpawnAgentTool stays decoupled). Full `:agent:test` + `:agent:detekt` green.
  Doc: `agent/CLAUDE.md` Observability → "API Debug Dumps (opt-in, default OFF)".

Related: [[project_perf_audit_2026_06_10]] (Wave 4 = the JCEF/disposal fixes),
[[project_api_debug_and_stats_reset_bugs]] (the separate call-counter-reset bug).
