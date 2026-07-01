---
name: Debug tools audit fix plan
description: 4-phase plan at docs/plans/2026-04-23-debug-tools-audit-fixes.md fixing C1-C7 + D1-D8 from the 2026-04-23 audit; root-cause for "Not suspended" LLM confusion is IdeStateProbe preferring currentSession over uniquely-paused session
type: project
originSessionId: 88aafcf4-63bd-43d4-9582-0bfc674a3146
---
Plan file: `docs/plans/2026-04-23-debug-tools-audit-fixes.md`

**Why:** LLM was getting "Debug session is running but not paused" errors even while stopped at a breakpoint. Audit traced root cause to `IdeStateProbe.debugState()` using `mgr.currentSession` (last-focused) instead of the uniquely-paused session when multiple debug sessions exist. Plus 6 other correctness bugs, 8 description-quality gaps.

**How to apply:**
- Phase 1 (ship-blocker): C1 IdeStateProbe fix + D1/D2/D3 description tags + D8 CLAUDE.md drift. 5 tasks, ~half day. Directly addresses the user-reported bug.
- Phase 2: C2 non-top-frame evaluate + C3 internal-API swap + C5 waitForPause canonical predicate + D7 unused description param. 5 tasks.
- Phase 3: C7 canPutAt precheck + C4 tooManyChildren marker + C6 WriteAction for dispose. 3 tasks.
- Phase 4: D4/D5/D6 polish — usually already absorbed by Phase 1 Task 4 text; verify + patch only if needed.

Branch: `fix/debug-tools-audit` (cut from main, NOT feature/telemetry-and-logging).

**Follow-up (deferred, separate plans):** 7 new-capability stubs listed at the bottom of the plan — threads, smart_step_into, inline breakpoint variants (Kotlin lambda), watches, hit counts, dependent breakpoints, coroutine debugger. Each warrants its own brainstorm before a plan.

**Key files:** IdeStateProbe.kt, DebugInspectTool.kt, DebugStepTool.kt, DebugBreakpointsTool.kt, AgentDebugController.kt, plus matching tests in agent/src/test/.
