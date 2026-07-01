---
name: source-text-sentinel-slice-trap
description: AgentService source-text contract tests slice between function-name sentinels — inserting a new function between two sentinels pulls its text into the assertion
metadata: 
  node_type: memory
  type: project
  originSessionId: eb2d0596-d309-4373-af8d-50bbcbba5496
---

⚠ TRAP/FIXED 2026-06-08 (PR #37, Phase 3 cut G incision 2)

Several `:agent` source-text contract tests `readText()` `AgentService.kt` and assert on a SLICE bounded by **function-name sentinels** — e.g. `DelegationConversationNarrationTest` does `svc.substringAfter("fun delegatedIncomingTaskText").substringBefore("fun mapLoopResultToDelegationResult")` and then `assertFalse(region.contains("IDE-A") || region.contains("IDE-B"))`.

**The bite:** inserting a NEW companion function *between* two sentinel functions silently pulls that new function's body (incl. its KDoc) into the asserted region. I added `delegationResultForDeliveryFailure` (KDoc said "sent back to IDE-A") between `delegatedIncomingTaskText` and `mapLoopResultToDelegationResult` → the "must NOT mention IDE-A/IDE-B" assertion failed. Full `:agent:test` caught it; the per-test `--tests "*X*"` run had not.

**Fix / rule:** when adding a function near sentinel-named functions used by these slice tests, place it OUTSIDE the sliced range (I moved it AFTER `mapLoopResultToDelegationResult` and left a code comment saying why). Belt-and-braces: avoid `IDE-A`/`IDE-B` literals in delegation code/comments anyway (see [[feedback_delegation_use_repo_names]]).

**Process lesson:** the research/scope doc already says "grep the source-text contract tests BEFORE moving code." Extend that to BEFORE *inserting* code between named functions, not just moving. Always run the FULL `:agent:test` (not just `--tests` for the new test) before committing a structural change — the slice tests live in unrelated files.

This is a concrete instance of the general source-text-contract caveat in the Phase 3 decomposition scope doc + agent/CLAUDE.md. Related: [[project_enterprise_roadmap]].
