---
name: Deferred UI refactors — PrDetailPanel and AgentController
description: Two large UI files (4000+ combined LOC) were deliberately NOT refactored during the cleanup pass because they lack test infrastructure. Decision rationale and required prerequisite work documented for when this is revisited.
type: project
---

During the 2026-04-07 cleanup pass, Phase 4 (file-level refactoring) was scoped to 4 large files but deliberately stopped at 2:

**Refactored (TDD-locked):**
- `agent/tools/framework/SpringTool.kt` — 2820 → 162 lines (extracted to `spring/` subpackage with 15 action files)
- `agent/tools/framework/BuildTool.kt` — 1680 → 143 lines (extracted to `build/` subpackage with 11 action files)

**Deferred:**
- `pullrequest/ui/PrDetailPanel.kt` (~2700 lines) — 5 inner sub-panels (Description/Activity/Files/Commits/AiReview) plus 2 cell renderers, all `inner class` referencing outer state. Natural extraction seam exists.
- `agent/ui/AgentController.kt` (~1900 lines) — coordinator with ~30 callbacks, JCEF bridge contract, session lifecycle.

**Why:** The user's TDD rule was strict — "write all related tests and run to see, after that make changes and run the tests again. (don't just write unit test, write scenario related tests)". Both deferred files have ZERO test coverage and the modules they live in have NO test infrastructure for UI/JCEF (no `BasePlatformTestCase` setup, no JCEF mock browser). Writing meaningful scenario tests requires building that infrastructure first.

**How to apply:** Before refactoring either file in a future session, do ONE of these first:
1. Add `BasePlatformTestCase` test fixture to the pullrequest module + write integration tests covering: load PR detail, switch sub-tabs, render reviewers, click merge, edit title, create new PR. THEN extract `DescriptionSubPanel`/`ActivitySubPanel`/`FilesSubPanel`/`CommitsSubPanel`/`AiReviewSubPanel` to top-level files with explicit dependency parameters.
2. For `AgentController`, build a JCEF bridge mock that captures call sequences. Then split by responsibility (session lifecycle / mirror panels / JCEF bridge / approval flow). The JCEF bridge contract is the riskiest seam.

**Anti-pattern:** Do NOT refactor either file without test coverage just because their LOC is concerning. They work today; size alone isn't pain. The refactors are defensible only when there's a safety net to catch silent UI regressions.
