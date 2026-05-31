# Design: Enterprise-grade audit & remediation of the 6 product tabs

**Date:** 2026-05-31
**Branch:** `feature/cross-ide-delegation`
**Status:** Approved — execution in progress

## Goal

Make the 6 user-facing product tabs of the Workflow Orchestrator plugin "enterprise grade / gold standard": find bugs, remove dead code and unnecessary fallbacks, fix architecture/contract violations, and raise test coverage — then fix the findings. The `:agent` module internals are explicitly **out of scope** for this first pass (deferred).

### In-scope modules / tabs

| Tab | Module | main LOC | test LOC |
|---|---|---|---|
| Sprint | `:jira` | ~14.7K | ~7.0K |
| PR | `:pullrequest` | ~10.1K | ~3.3K |
| Build | `:bamboo` | ~8.0K | ~6.2K |
| Quality | `:sonar` | ~8.2K | ~4.5K |
| Automation | `:automation` | ~6.2K | ~3.8K |
| Handover | `:handover` | ~5.5K | ~4.4K |

## Decisions (from brainstorming)

1. **Scope:** 6 product tabs first; `:agent` and `:core` internals deferred (`:core` read only for contracts).
2. **Fix posture:** verify-then-gated-fix. Every finding adversarially verified before any change; fixes applied in reviewable waves.
3. **Depth:** Audit-deep + test strengthening — fixes also add/strengthen automated tests toward gold-standard coverage.

## Hard constraint

Background subagents cannot drive a live IntelliJ GUI (Swing tabs + JCEF webview run inside the IDE; there is no external browser to attach to). Therefore the workflow audits panel/service/api **code**, runs the **Kotlin + vitest suites headless**, writes **new automated tests**, and **fixes** findings. The final live click-through via `runIde` remains a manual step for the user.

## Architecture — two workflows with a human gate

```
Workflow #1: FIND + VERIFY  ->  ranked verified report  ->  [GATE]  ->  Workflow #2: FIX WAVES
   (read-only)                  audit/2026-05-31/                       (gated, per-module)
```

### Workflow #1 — Find + Verify (read-only)

**Phase A — 4-lens sweep, pipelined per module (6 modules × 4 lenses = 24 finder agents):**

| Lens | Hunts for |
|---|---|
| Correctness & threading | Logic/null/empty/boundary bugs, error handling, API-contract misuse, EDT/`Dispatchers.IO`/`WriteCommandAction` threading violations & freezes, races, leaked Disposables/scopes |
| Cleanup & dead code | Unreachable/unused code, unnecessary fallbacks (error-masking catch-defaults, defensive impossible-state code), duplicate helpers reinventing `core/` utilities |
| Architecture & contract | `:core`-only deps, EventBus vs direct cross-module calls, `api→service→ui→listeners` layering, `core-interface → ToolResult<T> → feature-impl`, auth schemes, JB-only UI, empty-state convention, "no Cline in UI" |
| Test-coverage gaps | Untested error/failure paths, empty/boundary inputs, threading, parsing edge cases, state transitions, untested complex public methods |

Each finder is primed with the CLAUDE.md conventions **and a "known-intentional, do NOT flag" suppression list** (`SourcegraphChatClient`, `DockerRegistryClient`, `AuthTestService` HTTP isolations; deliberate fallbacks) to cut false positives at the source.

**Phase B — Adversarial verify (pipelined off each finder):** each finding gets a skeptic prompted to *refute* it (default-reject when uncertain), reading the actual code/tests; returns `{isReal, confidence, severityReassessed, isHarmfulToFix, reasoning, recommendedAction}`. **High/Critical findings escalate to a 3-skeptic panel** with diverse lenses (does-it-reproduce / fix-regression-risk / already-handled-or-intentional); majority must confirm.

**Output:** consolidated ranked report `audit/2026-05-31/findings.md` + machine-readable `audit/2026-05-31/findings.json` (confirmed findings with file:line, evidence, suggested fix, verdict; plus a rejected list with reasons).

### Workflow #2 — Fix waves (authored after the report, gated)

Confirmed non-harmful findings grouped **by module**. Each wave: re-confirm finding → apply architectural-correct fix (not cheap patch) → write/strengthen tests → run `:<module>:test` + `verifyPlugin` until green → present diff. Fan-out (parallel worktrees vs sequential) finalized once finding volume is known.

## Quality principles (from project memory)

- **Verify, don't trust** — last audit, 9/28 findings were non-real/harmful (one "fix" crashed startup). Adversarial verify is non-negotiable.
- **Architectural fix over cheap patch** — fix root functions; consolidate over parallel paths.
- **Reuse core utilities** — flag reinvention; do not add new parallel helpers.
- **Conservative on fallbacks** — only flag a fallback as unnecessary if removing it cannot change correct behavior.
- **Opus, max effort** for finder/verifier subagents (exploration/verification work).

## Cost

Workflow #1 ≈ 24 finders + ~120–170 verifiers (~150–200 agents), read-only, within engine caps. The thorough approach was chosen knowingly.
