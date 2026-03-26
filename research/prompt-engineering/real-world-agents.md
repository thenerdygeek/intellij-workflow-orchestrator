# Real-World Agentic AI System Prompts: Production Coding Agents

Research compiled: 2026-03-26

---

## Table of Contents

1. [Claude Code](#1-claude-code)
2. [Cursor](#2-cursor)
3. [Cline (formerly Roo Code)](#3-cline)
4. [Aider](#4-aider)
5. [OpenAI Codex CLI](#5-openai-codex-cli)
6. [dbreunig Comparative Analysis](#6-dbreunig-comparative-analysis)
7. [Simon Willison's Analysis](#7-simon-willisons-analysis)
8. [Lilian Weng's Agent Framework](#8-lilian-wengs-agent-framework)
9. [Cross-Cutting Patterns](#9-cross-cutting-patterns)
10. [Implications for Our Agent](#10-implications-for-our-agent)

---

## 1. Claude Code

**Source:** [Piebald-AI/claude-code-system-prompts](https://github.com/Piebald-AI/claude-code-system-prompts) (extracted from v2.1.84, March 25, 2026)
**Additional:** [Medium leak analysis](https://medium.com/coding-nexus/claude-codes-entire-system-prompt-just-leaked-10d16bb30b87), [Indie Hackers reverse-engineering guide](https://www.indiehackers.com/post/the-complete-guide-to-writing-agent-system-prompts-lessons-from-reverse-engineering-claude-code-6e18d54294)

### Prompt Structure

Claude Code does NOT use a single monolithic system prompt. It dynamically assembles **110+ distinct text strings** conditionally loaded based on environment, configuration, mode, and execution context.

**Five structural categories:**

| Category | Count | Token Range | Purpose |
|---|---|---|---|
| Agent Prompts | ~30 | 78-2,941 | Sub-agents, slash commands, utilities |
| System Prompt Sections | ~70 | 24-1,042 | Behavioral guidelines, task principles |
| System Reminders | ~40 | 12-1,297 | Mid-conversation context injections |
| Data/Reference Templates | ~25 | 200-5,106 | SDK refs, API docs, model catalog |
| Tool Descriptions | ~18 | embedded | Write, Bash, Read, Edit, Grep, Glob, TodoWrite |

**Task execution principles** are broken into ~15 separate 24-90 token micro-sections rather than monolithic blocks -- enabling targeted updates without side effects.

### Tool Handling

- 18 built-in tools with individual description prompts
- Tool-specific strategic guidance in system prompt (when/why to prefer each tool, not repeating schema)
- Parallel tool execution explicitly encouraged
- Tool preferences hierarchy: Read > Edit > Write; Grep/Glob > Bash for search

### Error Recovery

- System reminders injected mid-conversation for behavioral correction
- Hook notifications (success, blocking errors, stopped continuation) as system reminders
- File state alerts: truncated, empty, modified externally

### Doom Loop Prevention

**Auto-compaction:** Triggers at ~95% context window capacity. Summarizes older history, preserves recent exchanges and key decisions.

**System reminder injection pattern:** Declared in system prompt:
> "Tool results may include `<system-reminder>` tags containing system-added information"

Used for:
- Behavioral rule refreshes (overcome "forgetting" at high token counts)
- Mode switching (plan mode, readonly mode)
- File change notifications
- Token budget status updates (39 tokens)
- USD budget warnings (42 tokens)

**Context degradation awareness:**
- 80K-120K tokens: adherence begins degrading
- >180K tokens: severe degradation
- Critical rules placed at BOTH prompt start and end (U-shaped attention curve)

### Context Management

- Conditional loading based on: environment (CLI/IDE/GitHub), mode (plan/learning/auto/minimal), user config, team coordination context
- On-demand knowledge via tools rather than pre-loaded dumps
- Dynamic values (timestamps) placed in user messages, NOT system prompt (preserves prompt cache)
- Prompt caching strategy: static system prompt > tool definitions > project rules > conversation history

### Unique Techniques

1. **Modular micro-prompts** (24-90 tokens each) instead of monolithic instructions
2. **Sub-agent delegation**: Explore agent (494 tokens), Plan mode agent (636 tokens) -- lightweight specialized prompts
3. **Security monitoring prompts** (2,726 + 2,941 tokens) for autonomous action review
4. **Memory consolidation agent** for persistent knowledge across sessions
5. **Mode-specific prompt injection**: learning mode (1,042 tokens), auto mode (255 tokens), minimal mode (164 tokens)
6. **Team coordination** via system reminders (shutdown, coordination messages)
7. **Verification specialist** sub-agent (2,453 tokens) for validation

---

## 2. Cursor

**Source:** [Cursor Agent System Prompt (March 2025)](https://gist.github.com/sshh12/25ad2e40529b269a88b80e7cf1c38084)
**Analysis:** [9 Lessons From Cursor's System Prompt](https://byteatatime.dev/posts/cursor-prompt-analysis/)

### Prompt Structure

~1,250 tokens of system instructions using XML-like tags as organizational markers:

```
<communication> ... </communication>
<tool_calling> ... </tool_calling>
<making_code_changes> ... </making_code_changes>
<search_and_reading> ... </search_and_reading>
```

Core identity statement:
> "You are an AI coding assistant, powered by GPT-4.1. You operate in Cursor. You are pair programming with a USER to solve their coding task. You are an agent."

### Tool Handling

- Execute tools only when necessary; avoid redundant calls
- **NEVER reference tool names** when speaking to the user -- describe actions in natural language
- Follow exact schema specifications with all required parameters
- `read_file` retrieves 200-250 lines at a time (chunked access manages context window)
- **Stateful context**: After running `ls`, system returns current directory state for follow-up

### Error Recovery

- Limit error-fixing iterations to **3 attempts per file**
- Address root causes rather than symptoms
- Use descriptive logging, test functions, and isolated problem analysis

### Doom Loop Prevention

- **3-iteration cap** on linter error fixes: "DO NOT loop more than 3 times on fixing linter errors"
- "NEVER generate extremely long hashes or binary" (prevents expensive/infinite outputs)
- Explicit autonomy reinforcement: "Keep going until the user's query is completely resolved"

### Context Management

- Pre-fetch `@mentioned` URLs, convert webpages to markdown
- Include file contents, IDE state (cursor position, CWD)
- Custom instructions injected as separate user-role message with `<custom_instructions>`, `<available_instructions>`, `<required_instructions>` sections (prevents prompt injection)

### Unique Techniques

1. **XML tag structure** for prompt organization (helps LLMs parse long instructions)
2. **"CRITICAL INSTRUCTION"** prefix for parallel tool execution (overrides serial training patterns)
3. **Meta-communication rules**: Abstracting tool names into natural language
4. **Autonomy reinforcement** repeated in multiple sections ("bias towards not asking for help")
5. **Separation of custom instructions** from system prompt (injection resistance)
6. **Stateful tool returns**: Tool responses include persistent state for chained operations
7. **Smart limits on tool returns** (250 lines max per read) to manage context pressure

---

## 3. Cline

**Source:** [github.com/cline/cline/src/core/prompts/system-prompt](https://github.com/cline/cline/tree/main/src/core/prompts/system-prompt)
**Additional:** [Example system prompt gist](https://gist.github.com/maoxiaoke/cd960ac88e11b08cbb4fa697439ebc68), [Advanced prompt guide](https://cline.ghost.io/system-prompt-advanced/)

### Prompt Structure

~11,747 tokens total. Modular sections:

| Section | Purpose |
|---|---|
| AGENT_ROLE_SECTION | Identity and role definition |
| TOOL_USE_SECTION | Tool usage instructions and definitions |
| MCP_SECTION | MCP server capabilities (~40% of tokens) |
| EDITING_FILES_SECTION | File editing guidelines |
| ACT_VS_PLAN_SECTION | Action vs planning mode instructions |

Role definition:
> "You are a highly skilled software engineer with extensive knowledge in many programming languages, frameworks, design patterns, and best practices."

### Tool Handling

XML-style tool invocation format:
```xml
<tool_name>
<parameter>value</parameter>
</tool_name>
```

**Core tools:**
- `read_file` -- examine file contents
- `write_to_file` -- create new or completely overwrite
- `replace_in_file` -- SEARCH/REPLACE blocks with exact matching
- `execute_command` -- CLI commands with approval flags
- `list_files` -- directory browsing
- `search_files` -- regex pattern matching
- `list_code_definition_names` -- extract classes/functions at directory level

### Error Recovery

- Step-by-step execution: "proceed step-by-step, waiting for the user's message after each tool use before moving forward"
- Human-in-the-loop after every tool call prevents cascading errors
- Approval flags on destructive commands

### Doom Loop Prevention

- **Mandatory wait after each tool use** -- prevents autonomous error spirals
- `attempt_completion` tool signals task completion -- "Do not end result with a question"
- Cannot change directories; must use correct relative paths (prevents directory confusion loops)

### Context Management

- MCP server integration for extensible tool access (4,736 tokens / 40% of prompt)
- `list_code_definition_names` for structural overview without reading full files
- Recursive vs top-level directory listing options

### Unique Techniques

1. **Human-in-the-loop after every tool call** (most conservative of all agents)
2. **Anti-sycophancy enforcement**: "STRICTLY FORBIDDEN from starting your messages with 'Great,' 'Certainly,' 'Okay,' 'Sure'"
3. **MCP Server creation guidance** embedded in system prompt
4. **Approval flag system** on destructive operations
5. **Character-for-character matching** emphasis for SEARCH/REPLACE blocks
6. **Complete file content mandate** for write_to_file (no truncation ever)

---

## 4. Aider

**Source:** [github.com/paul-gauthier/aider/aider/coders/](https://github.com/paul-gauthier/aider)
**Files:** `base_prompts.py`, `editblock_prompts.py`, `wholefile_prompts.py`, `ask_prompts.py`, `architect_prompts.py`

### Prompt Structure

Aider uses a **multi-coder architecture** where different prompt sets handle different interaction modes:

| Coder Mode | Purpose | Key Trait |
|---|---|---|
| **EditBlock** | SEARCH/REPLACE diffs | Minimal, targeted changes |
| **WholeFile** | Complete file output | Full file replacement |
| **Ask** | Questions only | Analytical, no code generation |
| **Architect** | Planning + delegation | Directs an "editor engineer" |

Each coder inherits from `CoderPrompts` base class with shared templates for file context, repository maps, and behavioral constraints.

### Tool Handling

Aider does NOT use tool/function calling. Instead, it relies on **structured text output formats**:

**SEARCH/REPLACE format (EditBlock):**
```
path/to/file.py
<<<<<<< SEARCH
existing code to match
=======
replacement code
>>>>>>> REPLACE
```

**Rules:**
- Blocks match only the FIRST occurrence
- SEARCH must match character-for-character
- Keep blocks concise; break large changes into smaller blocks
- Include surrounding context lines for uniqueness
- Empty SEARCH + content in REPLACE = new file creation

### Error Recovery

- `lazy_prompt`: "implement complete code without leaving unfinished comments"
- `overeager_prompt`: "do exactly what users request without unsolicited improvements"
- Files marked as "authoritative sources" to prevent hallucination about file contents
- Ask clarifying questions if requests are ambiguous

### Doom Loop Prevention

- **No autonomous execution** -- Aider is a pair programmer, not an autonomous agent
- User must approve each edit before it is applied
- Git integration: every accepted change is committed, enabling easy rollback
- Scope constraint via `overeager_prompt`: prevents scope creep

### Context Management

- `files_content_prefix` establishes file trust: provided files are authoritative
- `repo_content_prefix` provides repository map (structural overview)
- `read_only_files_prefix` marks reference-only files
- Repo map shows codebase structure without including full file contents

### Unique Techniques

1. **Architect-Editor two-tier delegation**: Architect provides direction, Editor implements -- separation of planning and execution
2. **No tool calling** -- pure structured text output parsing
3. **Git-native workflow**: Every change committed automatically for rollback safety
4. **Repo map** for structural context without consuming full file tokens
5. **Mode-specific prompts** swap entire behavior profiles (ask vs edit vs architect)
6. **Overeager/Lazy prompt toggles** for controlling output scope
7. **"ONLY EVER RETURN CODE IN A SEARCH/REPLACE BLOCK"** -- absolute constraint prevents out-of-format code

---

## 5. OpenAI Codex CLI

**Source:** [github.com/openai/codex/codex-rs/core/prompt.md](https://github.com/openai/codex/blob/main/codex-rs/core/prompt.md), [gpt_5_codex_prompt.md](https://github.com/openai/codex/blob/main/codex-rs/core/gpt_5_codex_prompt.md)
**Additional:** [Codex CLI docs](https://developers.openai.com/codex/cli)

### Prompt Structure

Compiled into the Rust binary via `include_str!`. Core identity:
> "You are Codex, based on GPT-5. You are running as a coding agent in the Codex CLI on a user's computer."

Overridable at runtime via `experimental_instructions_file` in `~/.codex/config.toml`.

**Key sections:**
- Role definition and personality
- General guidelines (tool preferences)
- Editing constraints
- Plan tool usage rules
- Output formatting rules
- Special request handling (code reviews, terminal commands)

### Tool Handling

- `apply_patch` for single-file edits (NEVER `applypatch` or `apply-patch`)
- Scripting for bulk changes
- `rg` preferred over `grep` for text/file searches
- `update_plan` tool for multi-step work tracking

**Plan management:**
- Skip planning for straightforward tasks (~25% easiest work)
- Avoid single-step plans
- Plans contain 5-7 word steps with status tracking (pending/in_progress/completed)
- Update plans after completing sub-tasks

### Error Recovery

- "Stop immediately if unexpected changes appear; ask user for direction"
- Never revert user changes unless explicitly requested
- Fix root causes rather than applying surface patches
- Never attempt unrelated bug fixes

### Doom Loop Prevention

- **Explicit plan tool** prevents aimless iteration
- Skip planning for simple tasks (avoids over-engineering)
- Preamble messages (8-12 words) before tool calls maintain user visibility
- Test progression: specific (changed code) -> broader (full suite)

### Context Management

- `AGENTS.md` files in repository provide coding conventions (scoped to directory tree)
- Nested AGENTS.md files take precedence over parent files
- Direct user instructions override AGENTS.md content
- ASCII default; non-ASCII only when justified

### Unique Techniques

1. **Rust-compiled prompt** (`include_str!`) -- prompt is part of binary, not dynamically loaded
2. **AGENTS.md convention** -- hierarchical project-level instructions with directory scoping
3. **Plan tool with skip heuristic**: 25% of tasks don't need plans
4. **Preamble messages** (8-12 words) before each tool call for transparency
5. **Methodical, documentation-first approach**: understand fully, then implement once (vs Claude's iterative approach)
6. **File references with line numbers**: `src/app.ts:42` format enforced
7. **Output constraints**: headers 1-3 words Title Case, bullet lists 4-6 items ordered by importance, final answers <10 lines default
8. **Destructive git command protection**: explicit approval required for `git reset --hard`, `git checkout --`

---

## 6. dbreunig Comparative Analysis

**Source:** [How System Prompts Define Agent Behavior](https://www.dbreunig.com/2026/02/10/system-prompts-define-the-agent-as-much-as-the-model.html) (February 2026)
**Additional:** [Two Beliefs About Coding Agents](https://www.dbreunig.com/2026/02/25/two-things-i-believe-about-coding-agents.html)

### Agents Compared

Six CLI coding agents: **Claude Code, Cursor, Gemini CLI, Codex CLI, OpenHands, Kimi CLI**

### Key Findings

**Prompt length variation:**
- Claude Code and OpenHands: ~half the tokens of Codex and Gemini
- Cursor: >1/3 of tokens on personality and steering
- Kimi CLI: shortest, minimal workflow guidance

**System prompts serve two functions:**
1. **Model calibration** -- adjusting for each model's quirks and rough edges
2. **User experience design** -- determining tone, autonomy levels, interaction style

**Behavioral impact evidence:**
When researchers swapped prompts between agents using the OpenCode harness with Opus 4.5:
- **Codex prompt** produced: methodical, documentation-first approach (understand fully, then implement once)
- **Claude prompt** produced: iterative approach (try something, see what breaks, fix it)
- All combinations solved problems correctly -- **workflows differed based on prompt alone**

### Universal Patterns Found

**Code comments:** All agents discourage obvious comments
- Cursor: "not add comments for trivial or obvious code"
- Claude: "no added comments unless the user asks"

**Parallel tool execution:** Multiple prompts use urgent language to override serial execution from training:
- Cursor: "CRITICAL INSTRUCTION" emphasis
- Kimi: ALL-CAPS recommendation

**Agent-specific calibration:** Each prompt compensates for its model's specific weaknesses, making prompts non-transferable even when they work on the same tasks.

---

## 7. Simon Willison's Analysis

**Source:** [Highlights from the Claude 4 system prompt](https://simonwillison.net/2025/May/25/claude-4-system-prompt/)
**Additional:** [Agent definition discussion](https://simonw.substack.com/p/i-think-agent-may-finally-have-a)

### Key Observations

**System prompts as failure documentation:**
> "A system prompt can often be interpreted as a detailed list of all of the things the model used to do before it was told not to do them."

Every instruction hints at a past failure mode that needed correction.

**Agent definition:**
> "An LLM agent runs tools in a loop to achieve a goal."

### Patterns Identified

1. **Anti-sycophancy**: Explicit prohibition on opening with "great question" etc.
2. **List avoidance**: Multiple sections discourage bullet points in casual conversation (models default heavily to lists)
3. **Personality over neutrality**: Claude has preferences and opinions rather than claiming objectivity (false neutrality is itself deceptive)
4. **Interleaved thinking**: Models reason through tool results before responding
5. **Search scaling**: Tool usage adapts dynamically (0-20+ calls based on query complexity)
6. **Transparency value**: Publishing system prompts = "missing documentation for power users"

### Agent Security (Prompt Injection)

From [November 2025 paper analysis](https://simonwillison.net/2025/Nov/2/new-prompt-injection-papers/):
- 14 authors from OpenAI, Anthropic, Google DeepMind tested 12 defenses
- "Adaptive attacks" defeated all published defenses
- Fundamental tension: agents need to process untrusted content while following trusted instructions

---

## 8. Lilian Weng's Agent Framework

**Source:** [LLM Powered Autonomous Agents](https://lilianweng.github.io/posts/2023-06-23-agent/) (June 2023, foundational reference)

### Agent Architecture: LLM as Brain + Components

```
Agent = LLM (brain) + Planning + Memory + Tool Use
```

### Planning Approaches

| Approach | Pattern | Key Innovation |
|---|---|---|
| **Chain of Thought** | "think step by step" | Decomposes complex tasks |
| **Tree of Thoughts** | Explore multiple paths | BFS/DFS over reasoning tree |
| **ReAct** | Thought -> Action -> Observation loop | Interleaves reasoning with environment interaction |
| **Reflexion** | Dynamic memory + self-reflection | Detects inefficient planning, resets to try alternatives |
| **Chain of Hindsight** | Learn from ranked past outputs | Progressively better outputs from feedback |

**ReAct is the dominant pattern in production coding agents** -- all six agents from the dbreunig analysis use variations of this loop.

### Memory Management (Three-Tier)

1. **Sensory Memory**: Embedding representations for raw inputs
2. **Short-Term/Working Memory**: In-context learning (bounded by context window)
3. **Long-Term Memory**: External vector store with fast retrieval (MIPS: LSH, ANNOY, HNSW, FAISS, ScaNN)

### Tool Use Patterns

- **MRKL**: LLM as router directing to specialized expert modules
- **HuggingGPT**: Four-stage (task planning -> model selection -> execution -> response generation)
- **Toolformer/TALM**: Fine-tune models to learn tool APIs natively

### Critical Limitations (Still Relevant)

- Finite context constrains historical information
- Long-term planning and unexpected error recovery remain difficult
- Natural language interface reliability issues

---

## 9. Cross-Cutting Patterns

### Pattern 1: Prompt Architecture

| Agent | Architecture | Tokens |
|---|---|---|
| Claude Code | 110+ conditional micro-prompts | Variable (assembled dynamically) |
| Cursor | XML-tagged sections | ~1,250 |
| Cline | 5 modular sections | ~11,747 |
| Aider | Multi-coder class hierarchy | Per-coder (varies) |
| Codex CLI | Markdown compiled into binary | Single file |

**Trend:** Larger, more successful agents use more modular prompt architectures. Claude Code's 110+ conditional fragments represent the most sophisticated approach.

### Pattern 2: Autonomy Spectrum

```
Most Autonomous                              Most Conservative
     |                                              |
  Claude Code  ---  Codex CLI  ---  Cursor  ---  Cline  ---  Aider
  (keeps going)    (plans first)   (3 retries)  (waits each) (user approves)
```

### Pattern 3: Error Recovery Strategies

| Strategy | Used By | Mechanism |
|---|---|---|
| Iteration caps | Cursor (3), Codex (via plans) | Hard limit on retries |
| Human-in-the-loop | Cline, Aider | Wait for approval |
| Auto-compaction | Claude Code | Summarize + continue |
| Plan tracking | Codex CLI | Structured progress |
| System reminders | Claude Code | Mid-conversation rule refresh |
| Git rollback | Aider | Every change committed |

### Pattern 4: Context Window Management

| Technique | Agent(s) | Description |
|---|---|---|
| Auto-compaction | Claude Code | Summarize at 95% capacity |
| Chunked reads | Cursor (250 lines), Claude Code | Never read full files |
| Repo maps | Aider | Structural overview without content |
| On-demand docs | Claude Code, Codex | Load via tools, not pre-embedded |
| Prompt caching | Claude Code | Static prefix cached |
| U-shaped placement | Claude Code | Critical rules at start AND end |

### Pattern 5: Anti-Patterns Universally Prevented

1. **Sycophancy**: Claude Code, Cline, Claude 4 all prohibit filler phrases
2. **Over-commenting**: All agents discourage obvious code comments
3. **Serial execution**: Multiple agents use CRITICAL/ALL-CAPS to enforce parallel tool calls
4. **Scope creep**: Aider (overeager_prompt), Codex (no unrelated fixes), Cursor (only what's asked)
5. **Hallucination about files**: All agents require reading before editing
6. **Infinite loops**: Every agent has some form of iteration limiting or human gate

### Pattern 6: Tool Definition Strategy

Two schools:
1. **Tool calling API** (Claude Code, Cursor, Cline, Codex): Formal function definitions with schemas
2. **Structured text output** (Aider): Parse formatted text blocks (SEARCH/REPLACE)

For tool calling agents, system prompts contain **strategic guidance** (when/why), NOT schema repetition.

### Pattern 7: Safety and Destructive Operations

Universal pattern: destructive operations require explicit approval.
- Git: `reset --hard`, `checkout --`, force push all gated
- Files: Read before write/edit enforced
- Commands: Approval flags on destructive CLI operations (Cline)
- Claude Code: Dedicated security monitoring prompts (2,726 + 2,941 tokens)

---

## 10. Implications for Our Agent

### Prompt Architecture Recommendations

1. **Use modular, conditional prompt assembly** -- not a single monolithic string. Claude Code's approach of 110+ fragments loaded by context is the gold standard.

2. **Follow the U-shaped attention curve:**
   - Identity + Safety first (200-500 tokens)
   - Core workflow principles (500-2,000 tokens)
   - Tool usage policy (300-1,000 tokens)
   - Critical rules repeated at end (100-300 tokens)

3. **Keep total custom prompt under 6,000 tokens** -- beyond this, adherence degrades.

4. **Use system reminders for mid-conversation rule refresh** -- declare the pattern early, then inject `<system-reminder>` tags to combat context degradation.

### Tool Handling Recommendations

5. **Define tools via schemas; use prompt for strategy** -- when/why to prefer each tool, not what parameters they take.

6. **Enforce parallel execution explicitly** -- training data biases toward serial. Use strong language ("CRITICAL", "ALWAYS").

7. **Chunk data returns** -- never return full files. 200-250 line windows per read.

### Error Recovery Recommendations

8. **Implement iteration caps** -- 3 retries per error type (Cursor's proven pattern).

9. **Use plan tracking for multi-step tasks** -- Codex's `update_plan` with status tracking prevents aimless wandering.

10. **Gate destructive operations** -- approval required for file deletion, git resets, command execution.

### Context Management Recommendations

11. **Auto-compaction at ~95% capacity** -- summarize history, preserve recent context and key decisions.

12. **On-demand knowledge loading** -- use tool pointers ("use get_api_docs tool") instead of pre-embedded documentation.

13. **Prompt caching** -- static system prompt > tool defs > project rules > conversation (cache breakpoints in this order).

### Unique Techniques to Adopt

14. **Architect-Editor delegation** (from Aider) -- separate planning agent from execution agent for complex tasks.

15. **Preamble messages before tool calls** (from Codex) -- 8-12 word explanations maintain user transparency.

16. **Anti-sycophancy enforcement** -- prohibit filler phrases; start with substance.

17. **Dynamic context situations** (from dave.engineer) -- executable checks that inject relevant context based on current environment (detected frameworks, available tools, git state).

---

## Sources

- [Piebald-AI/claude-code-system-prompts](https://github.com/Piebald-AI/claude-code-system-prompts) -- Claude Code prompt extraction (v2.1.84)
- [Claude Code leak analysis (Medium)](https://medium.com/coding-nexus/claude-codes-entire-system-prompt-just-leaked-10d16bb30b87)
- [Reverse-engineering Claude Code prompts (Indie Hackers)](https://www.indiehackers.com/post/the-complete-guide-to-writing-agent-system-prompts-lessons-from-reverse-engineering-claude-code-6e18d54294)
- [Cursor Agent System Prompt (March 2025)](https://gist.github.com/sshh12/25ad2e40529b269a88b80e7cf1c38084)
- [9 Lessons From Cursor's System Prompt](https://byteatatime.dev/posts/cursor-prompt-analysis/)
- [Cline system prompt source](https://github.com/cline/cline/tree/main/src/core/prompts/system-prompt)
- [Cline system prompt example](https://gist.github.com/maoxiaoke/cd960ac88e11b08cbb4fa697439ebc68)
- [Aider coders source](https://github.com/paul-gauthier/aider/tree/main/aider/coders)
- [OpenAI Codex CLI prompt.md](https://github.com/openai/codex/blob/main/codex-rs/core/prompt.md)
- [OpenAI Codex CLI GPT-5 prompt](https://github.com/openai/codex/blob/main/codex-rs/core/gpt_5_codex_prompt.md)
- [dbreunig: How System Prompts Define Agent Behavior](https://www.dbreunig.com/2026/02/10/system-prompts-define-the-agent-as-much-as-the-model.html)
- [Simon Willison: Claude 4 system prompt highlights](https://simonwillison.net/2025/May/25/claude-4-system-prompt/)
- [Simon Willison: Agent definition](https://simonw.substack.com/p/i-think-agent-may-finally-have-a)
- [Lilian Weng: LLM Powered Autonomous Agents](https://lilianweng.github.io/posts/2023-06-23-agent/)
- [AI tool system prompts collection (30+ tools)](https://github.com/x1xhlol/system-prompts-and-models-of-ai-tools)
- [dzlab: Peeking Under the Hood of Claude](https://dzlab.github.io/ai/2025/05/12/peeking-under-the-hood-claude/)
- [dave.engineer: Agent Situational Awareness](https://dave.engineer/blog/2026/01/agent-situations/)
- [Cursor architecture deep dive (Medium)](https://medium.com/@lakkannawalikar/cursor-ai-architecture-system-prompts-and-tools-deep-dive-77f44cb1c6b0)
