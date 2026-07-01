---
name: Sub-agent design patterns synthesis (12 tools analyzed)
description: Cross-cutting analysis of how Claude Code, Cline, Cursor, Windsurf, OpenHands, Devin, Aider, SWE-agent, Codex CLI, LangGraph, CrewAI, AutoGen, and OpenAI Agents SDK implement sub-agents — with actionable recommendations for Workflow Orchestrator
type: reference
---

# Sub-Agent Design Patterns: Industry Synthesis

**Research date:** 2026-03-28
**Tools analyzed:** Claude Code, Cline, Cursor, Windsurf, OpenHands, Devin, Aider, SWE-agent, Codex CLI, LangGraph, CrewAI, AutoGen/Microsoft, OpenAI Agents SDK, Google ADK

## 1. Architecture Spectrum

### No Sub-Agents (Single Loop)
- **Aider**: Two-LLM pipeline (architect→editor), but synchronous, not delegation
- **SWE-agent**: Single ReAct loop with RetryAgent wrapper for multiple attempts
- **Windsurf Cascade**: Planner+Executor pair (not general delegation)

### Sub-Agents as Tools (LLM decides)
- **Claude Code**: `Agent` tool with `subagent_type` parameter
- **Cursor 2.4+**: `Task` tool spawning Explore/Bash/Browser sub-agents
- **Cline**: `use_subagents` tool spawning up to 5 parallel research agents
- **OpenAI Agents SDK**: `transfer_to_<agent>` handoff tools
- **LangGraph**: `transfer_to_<agent_name>` auto-generated tools
- **CrewAI**: `Delegate work to coworker(task, context, coworker)` tool

### Structural Delegation (Code decides)
- **OpenHands V0**: `AgentDelegateAction` — hardcoded delegation trigger
- **Codex CLI**: `spawn_agent` with depth limiting and role system
- **Devin**: Platform-level VM coordination (not prompt-level)

## 2. The Universal Pattern

**Every framework** converts sub-agents into standard tools. The LLM routes based on:
1. **Tool/agent descriptions** (most important lever)
2. **System prompt rules** explaining when to delegate
3. **Few-shot examples** showing correct delegation
4. **Structural constraints** (which tools are visible)

## 3. How Top Tools Make the LLM Correctly Delegate

### Pattern A: Confidence-Based Decision (Claude Code)
> "For broader codebase exploration and deep research, use the Agent tool with subagent_type=Explore. This is slower than using Glob/Grep directly, so use this only when a simple, directed search proves to be insufficient or when your task will clearly require more than 3 queries."

**Key insight**: The criterion is "will I need more than N queries" — a concrete, measurable threshold.

### Pattern B: Context Protection Framing (Cline)
> "Use this for broad exploration when reading many files would consume the main agent's context window."

**Key insight**: Frame delegation as protecting the parent's context, not as "this is hard." LLMs understand resource management.

### Pattern C: Speed/Parallelism Framing (Cursor)
> Explore sub-agent uses a "faster/cheaper model to run 10 parallel searches in the time a single main-agent search would take."

**Key insight**: Frame the sub-agent as FASTER than doing it yourself, not just as an alternative.

### Pattern D: Description-Driven Routing (All Frameworks)
> Google ADK: "The description field of your sub-agents is effectively your API documentation for the LLM. Be precise."

> OpenAI Agents SDK: `handoff_description` attribute is THE key lever for routing.

> Cursor: "Include phrases like 'use proactively' or 'always use for' in your description field."

**Key insight**: The tool description is the single most important lever. Not the system prompt rules, not the examples — the description.

### Pattern E: Anti-Recursion Guard (Cline, Codex CLI)
- Cline: `isSubagentRun: true` hides `use_subagents` from sub-agents
- Codex CLI: `exceeds_thread_spawn_depth_limit()` returns error
- OpenHands V1: `max_children` parameter

### Pattern F: Read-Only Sub-Agents (Claude Code, Cline)
- Explorer/research agents get ONLY read tools
- Prevents sub-agents from making destructive changes
- Reduces approval friction (no approval needed for reads)

## 4. Anti-Patterns That Cause LLMs to NOT Delegate

### Anti-Pattern 1: "DO NOT use" Lists Are Too Prominent
When the prompt has a long list of "Do NOT use explorer when..." cases, the LLM over-indexes on avoidance. **This was our exact bug.**

**Fix**: Lead with WHEN TO USE (positive), put the "don't" cases second and keep them short.

### Anti-Pattern 2: Examples Contradict Rules
If examples show the LLM doing manual search for tasks that should use a sub-agent, the LLM learns from examples over rules. **This was our second bug.**

**Fix**: Examples must demonstrate delegation for open-ended tasks.

### Anti-Pattern 3: Tool Overload
Berkeley research: accuracy drops significantly beyond 5-10 tools. More tools = more confusion about which to use.

**Fix**: Dynamic tool selection (only show relevant tools). We already do this via DynamicToolSelector.

### Anti-Pattern 4: Vague Descriptions
> "general-purpose agent for complex tasks" tells the LLM nothing about WHEN to use it.

**Fix**: Descriptions must include trigger conditions, not just capabilities. "Use when you're not confident you'll find what you need in 1-2 tool calls."

### Anti-Pattern 5: Missing "Proactive" Signals
If the description doesn't say "use proactively" or "always use for X", the LLM defaults to doing things itself.

**Fix**: Cursor's pattern — put routing hints directly in the description field.

## 5. Codex CLI's Interesting Constraint
> spawn_agent description: "Only use spawn_agent if and only if the user explicitly asks for sub-agents, delegation, or parallel agent work."

This is the OPPOSITE of what Claude Code and Cline do. Codex deliberately suppresses autonomous delegation. This makes sense for Codex's simpler use case but would hurt our plugin.

## 6. Recommendations for Workflow Orchestrator

### A. Rewrite Tool Description (Highest Impact)
The `agent` tool description should embed routing signals per type:
```
"explorer: Use PROACTIVELY for any codebase research that would require 3+ searches.
Faster than doing it yourself — runs parallel PSI-powered searches. Protects your
context from verbose results. Specify thoroughness: quick/medium/very thorough."
```

### B. Frame Delegation as an Advantage, Not a Fallback
Current: "use explorer when you're NOT confident" (negative framing)
Better: "explorer is FASTER and protects your context" (positive framing, like Cursor/Cline)

### C. Ensure Examples Match Rules
Every example involving open-ended search should show `agent(subagent_type="explorer")`.
Every example involving targeted lookup should show direct tools.

### D. Keep the "Don't Use" Section Short
Max 3 bullets for when NOT to use. Lead with the positive cases.

### E. Consider Cline's Context-Protection Framing
Add to the description: "Prevents verbose search results from filling your context window."

### F. Consider Cursor's Proactive Language
Add "use proactively" or "always use for open-ended research" to the explorer description.

### G. Structural Enforcement
- Explorer sub-agents should NEVER have the `agent` tool (no recursion) ✓ already done
- Explorer should ONLY have read tools ✓ already done
- Main agent should see sub-agent descriptions at session start ✓ already done via `<available_subagents>`

## 7. Raw Research Files

| Tool | Memory File |
|------|------------|
| Claude Code | `reference_claude_code_subagent_tool_implementation.md` |
| Cline | `reference_cline_subagent_task_delegation.md` |
| Cursor/Windsurf | `reference_cursor_windsurf_agent_architecture.md` |
| OpenHands/Devin | `reference_openhands_devin_multiagent.md` |
| Aider/SWE-agent/Codex | `reference_aider_sweagent_codex_subagent_patterns.md` |
| Frameworks | `reference_multi_agent_delegation_frameworks.md` |
