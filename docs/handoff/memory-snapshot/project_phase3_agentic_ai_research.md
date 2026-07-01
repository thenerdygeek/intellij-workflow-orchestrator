---
name: Phase 3 Agentic AI Architecture (v3 — post production research)
description: REVISED — single agent with tools as default (not multi-worker), LLM IS the orchestrator, streaming, structured output, context engineering focus
type: project
---

Full research archive: ~/Documents/Agentic_AI_Plugin_Architecture_Research_20260317/
Summary document: research_phase3_architecture_review_summary.md

## CRITICAL ARCHITECTURE REVISION (v3)

### v2 → v3 Changes (based on production tool research)

**v2 (WRONG):** Multi-worker architecture — separate Analyzer/Coder/Reviewer/Tooler workers with per-worker system prompts and tool filtering, managed by an LLM orchestrator.

**v3 (CORRECT):** Single agent with ALL tools as the default. The LLM decides what to do — analyze, code, review, call Jira — within one ReAct loop. Multi-step orchestration is opt-in for genuinely complex tasks (5+ files).

**Why the change:**
- Claude Code, Cline, OpenHands all use single agent + tools (research confirmed)
- Multi-agent amplifies errors 17.2x (MAST study, 1,642 traces)
- DeepMind: multi-agent only helps when baseline <45%. Modern LLMs hit 70-80%+.
- Manus's biggest gains came from REMOVING complexity, not adding it
- Separate workers caused inter-worker context loss (Gap 3B), which is the most reported failure mode

### Key Architecture Decisions (v3)

1. **Single agent ReAct loop as default** — LLM IS the orchestrator, no separate orchestrator process
2. **Streaming responses** — `stream: true` via SSE, user sees reasoning in real-time (CRITICAL UX)
3. **Structured output** — `tool_choice: forced` for routing/planning, eliminates regex parsing
4. **Dynamic system prompts** — composable: identity + tools + project context + previous results
5. **Instruction-fade reminders** — inject rules every 3-4 iterations (~50 tokens)
6. **Loop detection** — track last 3 tool calls, redirect if duplicate 3x
7. **LLM-powered summarization** — replace naive truncation with LLM summary call
8. **Auto-verification** — after edits, agent runs diagnostics before completing
9. **40% context utilization target** — quality degrades non-linearly past this
10. **File system as externalized memory** — progress files, plans, state written to disk

### What We Keep From v2
- ReAct loop structure (correct)
- Tool system (AgentTool, ToolRegistry)
- Context compression (two-threshold + anchored summaries, add LLM summarizer)
- Security (InputSanitizer, OutputValidator)
- CheckpointStore, FileGuard
- Settings, UI framework

### What We Do NOT Build
- Separate prompt server — prompts are just strings
- Tool handler agent — tools execute directly in loop
- Multi-agent pipeline for coding — single agent outperforms
- RAG pipeline for code search — just-in-time retrieval works better
- Fine-tuned models — context engineering beats fine-tuning
- Complex agent framework — simple composable patterns win

## Sourcegraph API Risk
**No confirmation that tool/function calling passes through Sourcegraph's proxy.**
Must test empirically before building. If it doesn't work:
- Fallback: XML-based tool description in system prompt + text parsing
- This is less reliable but workable (ToolBridge pattern)

## Estimated Effort for v3 Changes
~18 days of focused work. Priority: single-agent default → streaming → structured output → dynamic prompts → guards → verification → Spring tools → UI
