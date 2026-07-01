---
name: Evidence base for prompt restructure (academic + industry + source code)
description: Hard evidence from papers, official docs, and source code analysis of 8 tools supporting the prompt restructure design decisions
type: reference
---

# Prompt Restructure Evidence Base

## Academic Evidence

### Lost in the Middle (Liu et al., 2023 — Stanford, TACL 2024)
- ~20pp accuracy drop for middle-positioned information
- U-shaped attention: best at beginning (primacy) and end (recency)
- Effect WORSENS with context length
- Claude 3+ mitigates but doesn't eliminate (Anthropic "Found in the Middle" 2024)

### BiasBusters (2025 — Tool Selection Bias)
- LLMs prefer tools listed EARLIER in the prompt — proven across GPT-4.1, Claude 3.5, DeepSeek, Gemini, Qwen
- Tool names also influence selection (not just position)
- Implication: put preferred tools FIRST in tool list

### AgentBench (Liu et al., 2023)
- Moving safety constraints from position 3/7 to position 1 improved adherence by ~15%

## Official Vendor Guidance

### Anthropic
- "Place longform data at TOP, instructions at END" — up to 30% better instruction recall
- Tool descriptions = ACI (Agent-Computer Interface) — invest as much as HCI
- Progressive disclosure: manual with TOC, not dump everything
- "If a human can't say which tool to use, the agent can't either"
- XML tags mitigate positional effects (confirmed)
- Bookending: restate critical rules at both top and bottom

### OpenAI (GPT-4.1 Guide)
- Explicit 7-section order: Role > Instructions > Reasoning > Output > Context
- Three mandatory agent reminders: Persistence, Tool-calling, Planning
- "When tools serve similar roles, explicitly define preferred + when"

### Google (Gemini 3 Guide)
- "Place core request and critical restrictions as FINAL LINE"
- Negative constraints go at the end

## Source Code Analysis

### Claude Code (v2.1.86)
- 248 composable pieces, NOT one monolithic string
- ~31.8K tokens total (system prompt + tool descriptions)
- ORDER: Identity(1) → Constraints(3-13) → Tool policy(19-32) → Dynamic context(33-35)
- Constraints are FRONT-LOADED (items 1-13, ~744 tokens)
- Tool-specific guidance is IN TOOL DESCRIPTIONS, not system prompt
- "Doing Tasks" rules have ZERO examples — terse imperatives
- Examples are IN tool descriptions (TodoWrite has 8 examples)

### Cline (latest)
- Composable registry + builder + template engine
- ~15-20K tokens
- Identity is ONE sentence
- ORDER: Identity(1) → Tool descriptions+examples(2) → Capabilities(7) → Rules(10) → Objective(12)
- "When to use" guidance is IN the tool description string
- NO separate delegation rules section
- Subagent guidance is ONLY in the tool description

### Cursor (8 versions analyzed)
- Behavioral content: 80-230 lines (our 700 is 3-8x more)
- ORDER: Identity → Persistence → Communication → Tool-calling → Search → Code changes → Planning(LAST)
- Planning/task management ALWAYS last section (universal across all versions)
- Sept 2025 (GPT-5): only 229 lines total
- "CRITICAL" and "MANDATORY" used sparingly but strategically
- Examples appear WITHIN rule sections, not as a separate block

### Windsurf
- Only 125 lines total
- Planning at the very END
- Tool call examples INLINE within `<tool_calling>` section

### Devin
- 402 lines (250 are command reference)
- Behavioral rules FIRST, tool definitions SECOND
- `<think>` tool has most detailed guidance (10 specific situations)
- Security rules appear EARLY (high priority)

## Synthesized Optimal Order (from all evidence)

```
1. ROLE & IDENTITY
2. CRITICAL CONSTRAINTS (safety, persistence, tool discipline)
3. LONG-FORM CONTEXT / DATA (repo map, memory, project info)
4. TOOL CATALOG (preferred tools FIRST — exploits BiasBusters positional bias)
5. TOOL SELECTION RULES (decision boundaries — AFTER tools for recency)
6. BEHAVIORAL INSTRUCTIONS (error recovery, communication style)
7. CONSTRAINTS & SAFETY (negative constraints near end — Google recommendation)
8. EXAMPLES (highest recency attention)
9. OUTPUT FORMAT / FINAL REMINDERS (last position = strongest recall)
```
