---
name: feedback_delegation_use_repo_names
description: "In user-facing cross-IDE delegation text, name the actual repos, never \"IDE-A\"/\"IDE-B\""
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 228a096d-d457-46ba-809f-ed35f5192bc2
---

In all USER-FACING cross-IDE delegation text, use the concrete repo names (`delegatorRepo` / target repo / `handle.targetRepoName`), NOT "IDE-A"/"IDE-B"/"IDE A"/"IDE B" — the A/B shorthand is dev-facing and confusing from a user's perspective (user feedback, 2026-06-01, cross-ide branch).

**Why:** A user watching a delegated session sees "IDE-A"/"IDE-B" and can't tell which repo/window is meant; repo names are unambiguous.

**How to apply:**
- Concrete (a repo is known at that point) → substitute the repo name: delegation chat cards (incoming task "from {delegatorRepo}", "Asked {delegatorRepo}", "{delegatorRepo} answered", "Result sent to {delegatorRepo}"), busy-decline prose (name the target's own repo + delegatorRepo), DelegationPicker launch-failure ("{targetRepo} is still not reachable"), AskQuestionsTool delegated-error ("answer from {delegatorRepo}").
- Abstract (no concrete repo there) → role wording ("the delegating repo" / "the target repo" / "this IDE" / "the remote IDE"), still not "IDE-A/IDE-B": settings descriptions, LLM-facing docs (`tool-docs/delegation.md`, `cross-ide-delegation/SKILL.md`).
- DO NOT touch: dev logs, code comments/KDoc, variable/function/test names, and the machine error-taxonomy TOKENS (`ide_b_busy`, `ide_b_not_running`, `ide_b_agent_unavailable`, `resume_failed`, etc.) — those are a documented contract the LLM matches on; the repo is named in the human prose around the token. See [[project_cross_ide_delegation_bugs_and_prompt_queue]].
