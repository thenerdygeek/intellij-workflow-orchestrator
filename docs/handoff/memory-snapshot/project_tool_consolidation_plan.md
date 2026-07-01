---
name: Tool consolidation — Phase 1 COMPLETE
description: Phase 1 complete (2026-03-30). 138 tools consolidated into 9 meta-tools (57 total registered). Tests pass. Phases 2-4 are optimization, not yet started.
type: project
---

Phase 1 of tool consolidation is **COMPLETE** as of 2026-03-30.

**Why:** 183 tool definitions consumed ~45K tokens (25% of 190K budget), causing degenerate LLM behavior (file-path responses, loops). Consolidation reduced to 57 registered tools (~17K tokens).

**How to apply:** Phase 1 solved the critical bug. Phases 2-4 are optimization (deferred schemas, adaptive shrinking, text-in-prompt) — implement when needed.

## Results

| Before | After |
|--------|-------|
| 183 registered tools | 57 registered tools |
| ~45K tokens in tool definitions | ~17K tokens (estimated) |
| ~25% of context budget | ~9% of context budget |

## 9 Meta-tools created

| Meta-tool | Actions | Replaces |
|-----------|---------|----------|
| `jira` | 15 | jira_* tools |
| `bamboo` | 18 | bamboo_* tools |
| `sonar` | 9 | sonar_* tools |
| `bitbucket` | 26 | bitbucket_* tools |
| `debug` | 24 | debug_* tools |
| `git` | 11 | git_*/changelist_shelve tools |
| `spring` | 15 | spring_*/jpa_entities tools |
| `build` | 11 | maven_*/gradle_*/project_modules tools |
| `runtime` | 9 | run_config/process/test/compile tools |

## Remaining Phases (not started)

- Phase 2: Deferred schema loading (names-only initially)
- Phase 3: Adaptive tool set shrinking (remove "only-expand" stabilization)
- Phase 4: Text-in-prompt for niche tools

Plan: `docs/superpowers/plans/2026-03-30-tool-consolidation-token-reduction.md`
