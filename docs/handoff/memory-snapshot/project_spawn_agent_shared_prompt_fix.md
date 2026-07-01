---
name: project_spawn_agent_shared_prompt_fix
description: "agent tool parallel fan-out had no shared-context field → model wrote \"[same as above]\" placeholders into prompt_2/4 that dispatched literally; fixed with shared_prompt + stub guard"
metadata: 
  node_type: memory
  type: project
  originSessionId: 6cc3e2d3-0eba-44bf-b4b0-f584a0558afc
---

**FIXED 2026-05-27 (NOT committed): `agent` tool parallel-prompt shared-context gap.**

Agent-reported feedback, verified real: `SpawnAgentTool` exposed `prompt`/`prompt_2..5` as fully self-contained fields with NO shared-context field (tool-docs even said "include ALL context in the prompt"). To fan N sub-agents over the SAME large payload the model had to duplicate it N times; it "optimized" by writing `[Same prompt as above …]` into `prompt_2`/`prompt_4`, which the dispatcher (`params[pKey]?.content`, old SpawnAgentTool.kt:657-668) passed through literally → those workers got the placeholder, not the work.

**Fix (chose "shared_prompt + guard" via AskUserQuestion):**
- New optional `shared_prompt` param — **prepended to every branch** (`prompt` + `prompt_2..5`, and the single-agent `prompt` too) via a new pure companion fn `SpawnAgentTool.composePromptPairs(params, isReadOnly): PromptComposition (Resolved|Invalid)`. Absent/blank shared_prompt → byte-for-byte unchanged (backward compatible).
- Placeholder-stub guard (defense-in-depth): when fanning out (≥2 branches), a branch ≤200 chars containing phrases like "as above"/"see above"/"previous prompt"/"placeholder" is rejected with an error pointing at shared_prompt. Skipped for single dispatch and for long genuine prompts.
- `PromptComposition` sealed class is CLASS-level, not in the companion (companion-nested classes aren't reachable as `SpawnAgentTool.X` — only callables get promoted).

**Tests/docs:** 11 new tests in `SpawnAgentToolPromptCompositionTest` (TDD, RED→GREEN). Had to declare `shared_prompt` in BOTH DSL action blocks (single + parallel_fanout) with `llmSeesIt` matching the schema description verbatim, else `ToolDslSchemaParityTest` fails (SCHEMA_ONLY). System-prompt snapshots NOT affected (they render agent-tool prose, not the param schema). Docs updated: `tool-docs/agent.md` + `agent/CLAUDE.md`. Full `:agent:test` green (4242 tests). On branch `bugfix` (cross-ide worktree). Related: [[feedback_dont_over_review_ship]].
