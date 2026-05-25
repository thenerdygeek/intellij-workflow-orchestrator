# Queued During Phase 4 (Clusters E + F partial) — 2026-05-24

## Incidental findings observed but NOT fixed (per constraint)

### Q1 — MemoryIndex.atomicWrite has a fixed tmp suffix collision risk
File: `agent/src/main/kotlin/com/workflow/orchestrator/agent/memory/MemoryIndex.kt`
Line: `val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")`
Note: Unlike AtomicFileWriter (which appends timestamp+random), MemoryIndex uses a
deterministic `.tmp` suffix. Two concurrent writes to the same MEMORY.md will collide
on the tmp path. The `synchronized(lockFor(memoryDir))` prevents this within a JVM,
but cross-process writes remain a theoretical race. Low priority (single-JVM IDE plugin).

### Q2 — BackgroundPersistence and ToolOutputSpiller not covered by E2 perms
Files:
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/background/BackgroundPersistence.kt`
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ToolOutputSpiller.kt`
Note: These write to disk (spill files and background state) but were not in scope for E2.
Both should use `AtomicFileWriter.applyOwnerOnlyPerms` on initial file creation to be
consistent with the E2 policy. Bundle into a future "complete E2 sweep" PR.

### Q3 — `resolveAndValidateForSessionDownloads` already has a real-path check but no symlink walk
File: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/PathValidator.kt` line ~126
Note: This method calls `toRealPath()` which derefs symlinks, then re-checks the
prefix. That's correct for downloads (we WANT to follow the link to get the real file),
but a symlink in the downloads root itself would still escape. Low-risk (downloads root
is agent-controlled, not user-provided), but worth documenting for F-tier work.

---

## Incidental findings from Phase 4b (F1 + F2) — 2026-05-24

### Q4 — InsightsPanel invokes actionPerformed directly (override-only)
File: `core/src/main/kotlin/com/workflow/orchestrator/core/toolwindow/insights/InsightsPanel.kt`
Method: `buildToolbar$lambda$4$lambda$3` calls `GenerateReportAction.actionPerformed(AnActionEvent)`
Note: `@ApiStatus.OverrideOnly` — must be triggered via `ActionManager` / event dispatch, not direct
call. Pre-existing, surfaced by verifyPlugin. Phase 4c or dedicated cleanup PR.

### Q5 — RefactorRenameTool invokes findUsages directly (override-only)
File: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/RefactorRenameTool.kt`
Method: calls `RenameProcessor.findUsages()` directly.
Note: Same `@ApiStatus.OverrideOnly` issue as Q4. Pre-existing. Not a security finding, but
will need resolution before 2026.2 platform compatibility window closes.

### Q6 — CommandSafetyAnalyzer does not cover the new evasion patterns added to DefaultCommandFilter
File: `agent/src/main/kotlin/com/workflow/orchestrator/agent/security/CommandSafetyAnalyzer.kt`
Note: F1 hardened DefaultCommandFilter (the hard-block layer) but CommandSafetyAnalyzer (the
risk-classification layer for user-approvable commands) was not updated with equivalent evasion
detection. Since CSA gates user-visible approval dialogs, updating it improves defense-in-depth
at the approval layer as well. Medium priority; phase 4c candidate.

### Q7 — antiInteractiveEnv does not suppress PYTHONDONTWRITEBYTECODE / PYTHONSTARTUP
File: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/process/ProcessEnvironment.kt`
Note: `PYTHONSTARTUP` can load arbitrary Python code at interpreter start. `PYTHONDONTWRITEBYTECODE`
is benign but indicative that the anti-interactive set may be incomplete. Minor coverage gap.

## Incidental findings from Phase 4c (F3 + F4) — 2026-05-24

### Q8 — writeAction is @Experimental in com.intellij.openapi.application.CoroutinesKt
Files: `EditFileTool.kt`, `CreateFileTool.kt`, `DeleteFileTool.kt`
Note: `writeAction` (com.intellij.openapi.application) is marked `@Experimental` in the
IntelliJ platform. verifyPlugin now reports four NEW warnings (one per writeViaDocument,
writeViaVfs in Edit, writeViaVfs in Create, deleteViaVfs in Delete). These are warnings
only — not errors that block the build in isolation. The pre-existing Q4/Q5 @OverrideOnly
violations are what actually fail verifyPlugin. When Q4/Q5 are fixed, check whether the
@Experimental suppression annotation (@Suppress("UnstableApiUsage")) needs to be added
to the three helper functions or whether the API has stabilised on the platform version
in use by then. Low risk: @Experimental APIs in IJ Platform rarely break between minor
releases; the pattern is widely used in IJ plugins including JetBrains' own.

---

## RESOLUTION (2026-05-25, Tier-A incidentals pass)

- **Q7** — FIXED. `ProcessEnvironment.antiInteractiveEnv` now sets `PYTHONSTARTUP=""` (neutralizes the inherited-env code-exec vector); `PYTHONSTARTUP` also added to `BLOCKED_ENV_VARS` so Layer-3 user overrides can't re-add it.
- Q1/Q2/Q3/Q4/Q5/Q6/Q8 — deferred (Tier B/D: perms sweep, OverrideOnly/Experimental verifier work, CSA evasion parity).

## RESOLUTION (2026-05-25, Tier-B incidentals pass)

- **Q2** — FIXED. `BackgroundPersistence.writeAtomic` applies `AtomicFileWriter.applyOwnerOnlyPerms(tmp)` before the atomic move; `ToolOutputSpiller.spill` applies it to the spill file after write. Both now rw------- (E2 policy consistency).
- **Q6** — DEFERRED. `DefaultCommandFilter` (the enforced hard-block) already depends on `CommandSafetyAnalyzer.tokenize`; making CSA call back into the filter would create bidirectional coupling, and CSA only sets the approval-dialog *label* (DefaultCommandFilter hard-blocks every evasion at execution regardless). A proper fix = extract a shared evasion-normalization module both consume (dedicated refactor, not an incidental). Auditor-marked "medium priority, phase 4c candidate".
