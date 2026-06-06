# Phase 3 — Architecture Decomposition (Spec)

**Date:** 2026-06-07
**Status:** Spec for review (autonomous-run output; not yet planned into tasks)
**Depends on:** Phase 0 nets (CI + tests + coverage floor + Konsist) — all now live on `main`.
**Risk:** HIGH (invasive). Each extraction must be a separate, smoke-tested PR.

---

## Why now

The Phase 0 safety nets exist (green CI: build, full tests, detekt, Konsist arch tests, Kover
floor 48%). Decomposition is the invasive tier — only safe with those nets. These god-classes are
also the **merge-conflict epicenters** for company forks, so splitting them directly serves the
fork model (Phase 2).

## Targets (verified sizes, 2026-06-07)

| Target | LOC | Proposed decomposition |
|---|---|---|
| `agent/.../ui/AgentController.kt` | 5403 | Extract the JCEF bridge → `AgentJcefBridge`; split lifecycle/wiring from message routing |
| `agent/.../AgentService.kt` | 4169 | Extract `ToolRegistry` into a standalone service; split session management from tool wiring |
| `agent/.../loop/AgentLoop.kt` | 2762 | Separate the ReAct state machine from I/O orchestration |
| `pullrequest/.../ui/PrDetailPanel.kt` | 2521 | Extract table models + detail sections + card components |
| Large tools (`SonarTool` ~2.4K, `JavaRuntimeExecTool` ~2.2K, `RuntimeExecTool` ~2.1K, `JiraTool` ~1.9K) | — | Base class + per-concern subclasses |

## Iron discipline: characterization tests FIRST

For each extraction, **before touching the god-class**:
1. Write characterization (golden-master) tests that pin the *current* observable behavior of the
   unit being extracted — inputs → outputs/side-effects/emitted events, as-is (even if quirky).
2. Run them green against the un-refactored code.
3. Perform the extraction (move code, introduce the new type, delegate).
4. Re-run the characterization tests — must stay green (behavior unchanged).
5. Smoke-test in a running IDE (`runIde`) for the UI-bearing ones (AgentController, PrDetailPanel) —
   CI cannot exercise the JCEF/Swing surface.

Use `superpowers:systematic-debugging` discipline if any test goes red mid-extraction: revert to the
last green, shrink the step, retry. Never "fix forward" through a red characterization test.

## Sequencing (lowest-risk / highest-value first)

1. **`AgentService` → extract `ToolRegistry`** — the registry is already a conceptual unit (deferred,
   per-tool timeouts); cleanest seam, high value (forks add/remove tools here). Start here.
2. **`AgentLoop` → ReAct state machine vs I/O** — well-bounded; characterization-testable via the
   existing loop tests. Medium.
3. **`PrDetailPanel` → table models + sections + cards** — UI; needs `runIde` smoke. Medium.
4. **`AgentController` → `AgentJcefBridge`** — largest + riskiest (JCEF bridge); do LAST, after the
   pattern is proven on the smaller three. Needs `runIde` smoke.
5. **Large tools → base + subclasses** — opportunistic; do as each tool is touched.

## Per-extraction PR shape (each is its own PR, NOT batched)

- Commit 1: characterization tests (green against current code).
- Commit 2..n: the extraction, each step keeping tests green.
- PR body: what moved, why the seam is there, the smoke-test evidence (screenshot/log for UI).
- **Leave for human review + smoke-test before merge** (invasive; CI can't cover UI behavior).

## Coverage opportunism

Raise coverage in the thin modules (`:pullrequest`, `:sonar`, `:automation`, `:web`) as they are
touched — ratchet the Kover floor up (currently 48%) once a module's coverage durably rises.

## Out of scope

- Behavior changes. This phase is **pure structure** — if a behavior "should" change, that's a
  separate ticket. Characterization tests enforce this.
