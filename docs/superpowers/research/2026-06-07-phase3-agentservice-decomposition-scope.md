# Phase 3 — AgentService Decomposition Scope (research / resume point)

Captured 2026-06-07 (read-only scout of `agent/.../AgentService.kt`, 4169 lines) so the next
session can start the first extraction without re-scouting. Pairs with the Phase 3 spec
(`docs/superpowers/specs/2026-06-07-phase3-architecture-decomposition-spec.md`).

## ⚠ Spec correction
The roadmap said "extract ToolRegistry into a standalone service." **Already done** —
`agent/.../tools/ToolRegistry.kt` (293 lines) is well-extracted and self-contained. AgentService
only holds tool *wiring* (which tools to register), not registry mechanics. Do NOT pursue that.

## ⚠ Testing reality
`AgentService` is `@Service(Service.Level.PROJECT)` and **cannot be instantiated in unit tests**
(needs the full platform). Its existing "tests" are mostly **source-text contracts** — they
`readText()` the `.kt` and assert on string patterns. Implication: classic behavioral
characterization tests can't wrap AgentService directly. Extracting a cluster into a plain
injectable class makes that logic behaviorally testable for the first time (a win). BEFORE moving
code, grep the source-text contract tests so a move doesn't trip a "pattern must exist in
AgentService.kt" assertion.

## AgentService responsibility clusters (approx line ranges)
| Cluster | Lines | Coupling | Notes |
|---|---|---|---|
| A — Tool registration (`registerAllTools` etc.) | ~1261–1793 (~530) | Tight | sets shared state (ideContext, providerRegistry…) used by B |
| B — Session lifecycle / `executeTask` | ~1840–2933 (~1090) | Very tight | the god-function; ~25 callbacks into AgentLoop |
| C — Session resume (`resumeSession`) | ~2960–3372 (~410) | Tight | variant of B |
| D — Brain/model (`createBrain`, `wrapBrainWithRouter`) | ~985–1230 (~245) | Medium | pure factory internally |
| **E — Monitor framework** | ~228–395 methods + ~30 init (~195) | **Loose** | **FIRST EXTRACTION TARGET** |
| F — Background completion / auto-wake | ~617–795 (~175) | Medium-loose | event-driven |
| G — Cross-IDE delegation | ~1524–1527, ~3558–3889 (~500) | Medium | thin wrappers over B/C |
| H — Per-session state / plan mode / lifecycle | ~3890–4169 (~280) | Tight | cross-cuts at session boundaries |

## RECOMMENDED FIRST EXTRACTION: Monitor cluster (E) → `AgentMonitorCoordinator`
- **New class:** `agent/.../monitor/AgentMonitorCoordinator.kt` (companion to existing MonitorManager/Pool/Bridge/Persistence).
- **Move fields:** `monitorManagers: ConcurrentHashMap<String, MonitorManager>`, `monitorPersistence` (lazy).
- **Move methods:** `monitorManagerFor`, `ensureMonitorManager`, `disposeMonitorsForSession`, `forgetMonitor`,
  `markMonitorsDormantForSession`, `clearPersistedMonitors`, `reArmMonitors`.
- **Move init wiring:** `MonitorBridge.setRouter(...)` + the 200ms flush coroutine + `MonitorPool.forgetCallback`.
- **Deps to inject:** `project`, `cs`, `agentDir`, `idleWaker` (+ autoWakeListener if needed). `: Disposable`.
- **Seam:** AgentService keeps `private val monitorCoordinator` and delegates the 7 call sites (callers:
  AgentController, resumeSession, cancelCurrentTask — already call by name). Remove the moved fields.
- **Why safe:** self-contained state (no other cluster touches monitorManagers/monitorPersistence), clean
  named seam, no lambda-closure tangle, ~5% of file.
- **Tests already covering the components:** `monitor/MonitorPersistenceTest`, `MonitorReArmIntegrationTest`,
  `MonitorNotificationsTest`, `MonitorManagerTest`, `tools/builtin/MonitorToolTest`. Add a new behavioral
  test for `AgentMonitorCoordinator` itself (now instantiable).

## Discipline (per Phase 3 spec)
Char-tests/source-grep first → extract in small steps keeping `:agent:test` green → `:agent:detekt`
→ `runIde` smoke (monitor re-arm on resume) → one PR, left for review. Use `--no-build-cache` if a
suspend signature changes (build-cache trap, see CLAUDE.md).

## After E succeeds (later cuts, ascending risk)
D (Brain/model, medium) → F (background, medium) → G (delegation wrappers) → C (resume) →
B (`executeTask`, hardest, last). H cross-cuts; do opportunistically.
