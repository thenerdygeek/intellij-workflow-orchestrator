---
name: Context management deep analysis (13 tools, 20+ papers)
description: Comprehensive source-code-level analysis of context keeping, retention, and compression in 13 enterprise agentic tools plus academic research. Includes architectural patterns, anti-patterns, and actionable recommendations for our agent pipeline.
type: reference
---

# Context Management Deep Analysis Summary

**Full document:** `docs/research/2026-03-31-context-management-deep-analysis.md`
**Research date:** 2026-03-31

## Key Findings

1. **Industry standard: 4-stage compaction pipeline** (tool clearing → LLM summarization → sliding window → truncation)
2. **No tool implements multi-tier context budgets** (OK/COMPRESS/NUDGE/TERMINATE) — our differentiation opportunity
3. **Two camps:** LLM-powered compression (Cline, Aider, OpenHands, Codex, Goose, ADK) vs heuristic-only (SWE-agent, AutoGen)
4. **Context resets > compaction** for long-running work (Anthropic finding)
5. **KV-cache hit rate = #1 production metric** (Manus AI)
6. **65% of enterprise AI failures** from context drift, not exhaustion
7. **Weak model for summarization** is winning pattern (Aider, Goose, Microsoft)

## Tools Analyzed (Source Code)

Cline, Aider, OpenHands, Codex CLI, SWE-agent, Goose, Amazon Q CLI, LangGraph, CrewAI, Anthropic SDK, Google ADK, AutoGen

## Recommended Architecture

5-tier budget system (OK → COMPRESS → NUDGE → CRITICAL → TERMINATE) with 6-stage compaction pipeline, cheap model summarization, summary chaining, file dedup, stuck detection, and session handoff.

## Related Memory Files

- `reference_context_window_management_comparison.md` — earlier 7-tool comparison
- `reference_agent_loop_exit_triggers_comparison.md` — exit trigger analysis
- `reference_codex_sweagent_context_management_deep_analysis.md` — Codex + SWE-agent deep dive
- `reference_context_memory_management_three_frameworks.md` — Anthropic/ADK/AutoGen
- `reference_goose_amazonq_context_management.md` — Goose + Amazon Q deep dive
- `reference_context_engineering_2025_2026.md` — Web research papers and publications
