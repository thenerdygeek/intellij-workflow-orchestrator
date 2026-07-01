---
name: subagent-dialect-drift-correction-gap
description: Sub-agents ran away emitting <function_calls>/<invoke> dialect because the .19 dialect-drift CORRECTION was orchestrator-only; fixed by threading dialectDriftDetected through the subagent prompt path.
metadata: 
  node_type: memory
  type: project
  originSessionId: 1f0b8dc3-49e6-4d30-83a1-4088518af582
---

**FIXED 2026-05-27 (uncommitted, bugfix worktree): sub-agent dialect-drift runaway.**

Symptom: a sub-agent (explorer) emitted dozens of Anthropic `<function_calls><invoke name="X">` blocks as literal text (incl. corrupted `name="globme="glob_files"`), ran nothing, then confabulated "every tool returned empty" and gave up. Same failure the main agent had until ~v.19.

Root cause — a **split-responsibility regression**. The .19 fix (commit `7ba82628e`, 4 layers) detects off-dialect turns in `MessageStateHandler.addToApiConversationHistory` → rejects turn, sets `dialectDriftFlag`. The layer that actually breaks the runaway is the corrective `<system-reminder>` ("CRITICAL — TOOL-CALL FORMAT CORRECTION", `SystemPrompt.kt:145`), injected only via `AgentService.systemPromptBuilder` (`consumeDialectDriftFlag()` → `dialectDriftDetected=…`). Sub-agents build their per-iteration prompt through a **different** path — `SubagentRunner.buildUnifiedSystemPrompt` → `SubagentSystemPromptBuilder.build` — which never passed `dialectDriftDetected`. So the flag was raised (factory wired at `AgentService.kt:1042`) and **never consumed**: the model was never told to stop, and re-anchored its own bad dialect (in-context "context poisoning").

Fix: `SubagentSystemPromptBuilder.build` now takes `dialectDriftDetected: Boolean = false` (forwarded to `SystemPrompt.build`); `SubagentRunner.buildUnifiedSystemPrompt` consumes `messageStateHandler?.consumeDialectDriftFlag() ?: false` (the fn is the wired `systemPromptProvider`, so this mirrors the orchestrator's per-turn consume). Default false keeps `SubagentSystemPromptSnapshotTest` byte-stable. Pinned by 2 new cases in `SubagentSystemPromptBuilderTest`. :agent main compiles; targeted tests green (12/0). NOT committed.

**Why:** OpenAiCompat sends `tools=null` — tool schema lives only in the system prompt as canonical XML (`<tool>...`), so any other dialect silently doesn't parse. Related: [[project_brainrouter_stream_downgrade_dialect_trap]].

**How to apply:** whenever a behavior is wired through `AgentService.systemPromptBuilder`, check whether the sub-agent path (`SubagentRunner`/`SubagentSystemPromptBuilder`) needs the same — the two prompt-assembly paths diverge by design (scoped flags, task_report footer) and silently miss orchestrator-only fixes.
