# Dynamic Instruction/Rule/Skill Injection Patterns in Agentic Coding Tools

**Date:** 2026-04-03
**Source:** Direct source code analysis of GitHub repositories

## Summary

All six tools inject custom rules into the **system prompt** (beginning of context). None use mid-conversation system messages for rules. Some additionally use recency-based reinforcement. Compression-proof anchoring varies significantly.

---

## Comparison Table

| Aspect | Cursor | Cline | Codex CLI | Windsurf/Cascade | Continue.dev | Aider |
|---|---|---|---|---|---|---|
| **Rule files** | `.cursorrules`, `.cursor/rules/*.md`, project rules UI | `.clinerules/`, `.cursorrules` (compat), `.windsurfrules` (compat), `AGENTS.md` | `AGENTS.md` (hierarchical, scoped by directory) | `.windsurfrules`, memories (auto-generated) | `.continue/rules/*.md`, `rules.md` (colocated), `AGENTS.md`/`CLAUDE.md` | `.aider.conf.yml`, read-only files (`--read`), conventions file |
| **Injection location** | System prompt (end section) | System prompt (last section, after RULES, before OBJECTIVE) | User message with XML markers, injected before first user turn | System prompt (4-layer pipeline: rules/memories/files/RAG) | System prompt (appended after base system message) | System prompt (beginning) + system reminder (end, after conversation) |
| **Message role** | `system` | `system` (single concatenated system prompt) | `user` role with `<INSTRUCTIONS>` XML tags | `system` (layered) | `system` | `system` (beginning) + `system` or stuffed into last `user` message (reminder) |
| **Mid-conversation injection** | No (static per request) | No (rebuilt each turn but same position) | Yes -- AGENTS.md re-injected as user messages on each turn; skills injected dynamically via `<skill>` tags | Yes -- memories updated between turns | No (rules re-evaluated per turn but always in system prompt) | No (static per conversation, reminder at end) |
| **Recency reinforcement** | No | No | No explicit duplication | Yes (memories placed for recency) | No | Yes -- `system_reminder` placed at END of all messages (after conversation history) |
| **Compression-proof** | No compaction system | No (uses `new_task` handoff which loses rules context) | Yes -- AGENTS.md fragments are explicitly excluded from memory generation but re-injected fresh each turn. `is_memory_excluded_contextual_user_fragment()` filters them from compaction input | Unknown (closed source) | No (uses conversation summary; rules in system prompt rebuilt) | Partially -- system prompt and reminder are never part of summarized history (`done_messages`). Repo map and read-only files are in separate chunks |
| **Conditional/scoped rules** | Yes (glob patterns, `alwaysApply` flag) | Yes (YAML frontmatter `paths:` for conditional activation based on touched files/tabs/tool requests) | Yes (directory-scoped: deeper AGENTS.md takes precedence) | Unknown | Yes (glob patterns, regex content matching, `alwaysApply`, directory-scoped colocated `rules.md`) | No (all read-only files always included) |
| **Dynamic skill loading** | No native skill system | Yes (`use_skill` tool loads skill instructions mid-conversation as tool result) | Yes (`<skill>` XML-tagged user messages injected dynamically) | No | No | No |

---

## Detailed Analysis

### 1. Cursor

**Source:** Partially open-source repo (`getcursor/cursor`), community documentation, Cline compatibility layer.

- **Injection mechanism:** Rules from `.cursorrules` and `.cursor/rules/` are read at conversation start and injected into the system prompt.
- **Position:** End of system prompt, before the user's actual message.
- **Role:** `system` message.
- **Conditional rules:** Supports glob patterns for file-scoped rules and an `alwaysApply` boolean. Rules in `.cursor/rules/` can have YAML frontmatter with `globs` to scope them to specific files.
- **Compaction:** Cursor uses server-side context management (closed source). No evidence of client-side compression-proof anchoring for rules.
- **Cross-tool compatibility:** Cline reads `.cursorrules` files as a fallback, confirming they are simple markdown/text files injected as custom instructions.

### 2. Cline

**Source:** `cline/cline` GitHub repo, direct source code analysis.

- **Injection mechanism:** Rules are assembled by `buildUserInstructions()` in `src/core/prompts/system-prompt/components/user_instructions.ts`. The function concatenates instructions from: global `.clinerules`, local `.clinerules/`, `.cursorrules` (compat), `.windsurfrules` (compat), `AGENTS.md`, and `.clineignore`.
- **Position:** Last section of the system prompt template. The template ordering is:
  ```
  AGENT_ROLE > TOOL_USE > TASK_PROGRESS > MCP > EDITING_FILES > ACT_VS_PLAN >
  CAPABILITIES > SKILLS > FEEDBACK > RULES > SYSTEM_INFO > OBJECTIVE > USER_INSTRUCTIONS
  ```
  User instructions are literally the final section, giving them maximum recency in the system prompt.
- **Role:** Single `system` message (entire prompt is one message).
- **Conditional rules:** `.clinerules/` files support YAML frontmatter with `paths:` arrays for conditional activation. `RuleContextBuilder` evaluates which rules to include based on file paths from: user messages, open/visible tabs, tool results (completed file operations), and tool requests (pending operations). This means rules can activate based on *intent* before a tool even executes.
- **Skills:** Loaded dynamically via `use_skill` tool. Skill descriptions are listed in the system prompt; actual skill instructions are returned as tool results when activated.
- **Compaction:** Cline uses `new_task` for session handoff and `summarize_task` for auto-compaction. Rules in the system prompt are rebuilt each turn, but there is no explicit mechanism to preserve rule awareness through compaction of conversation history.

### 3. Codex CLI

**Source:** `openai/codex` GitHub repo (`codex-rs/` Rust codebase), direct source code analysis.

- **Injection mechanism:** AGENTS.md files are loaded hierarchically (directory-scoped, deeper files take precedence). They are serialized with XML markers:
  ```
  # AGENTS.md instructions for {directory}
  
  <INSTRUCTIONS>
  {contents}
  </INSTRUCTIONS>
  ```
  These are injected as `user` role messages (not system messages) using `ContextualUserFragmentDefinition`.
- **Position:** Injected before the user's first message in each turn. The system prompt (`default.md`) is a separate developer/system message. AGENTS.md content sits between the system prompt and user conversation.
- **Role:** `user` message with XML tags (specifically `ResponseItem::Message { role: "user" }`).
- **Skills:** Also use XML-tagged user messages: `<skill>{name + path + contents}</skill>`.
- **Compaction:** Codex has explicit compression-proof handling:
  - `is_memory_excluded_contextual_user_fragment()` returns `true` for AGENTS.md and skill fragments, excluding them from memory/summarization input.
  - AGENTS.md fragments are re-injected fresh each turn via `build_contextual_user_message()`.
  - Environment context and subagent notifications are NOT excluded from memory (they carry useful execution context).
- **Scoping:** AGENTS.md files are directory-scoped. The spec states: "For every file you touch in the final patch, you must obey instructions in any AGENTS.md file whose scope includes that file." More-deeply-nested files take precedence.

### 4. Windsurf/Cascade

**Source:** Closed source. Analysis based on public documentation, blog posts, and architectural patterns described in prior research.

- **Injection mechanism:** Rules from `.windsurfrules` are injected into the system prompt. Windsurf uses a 4-layer pipeline: rules/memories/files/RAG/actions.
- **Position:** Rules are in the system prompt. "Memories" (auto-generated contextual notes from previous interactions) are also injected, providing a form of persistent instruction.
- **Role:** `system` message.
- **Memories:** Windsurf's distinctive feature is auto-generated memories that persist across sessions. These function as dynamic instructions that the system learns over time. They are placed for recency in the context.
- **Compaction:** Unknown internals. The memory system provides a form of compression-proof persistence since memories are stored externally and re-injected.
- **M-Query RAG:** Windsurf pre-loads context via M-Query RAG before the conversation starts, which can include relevant rules and file content.

### 5. Continue.dev

**Source:** `continuedev/continue` GitHub repo, direct source code analysis.

- **Injection mechanism:** Rules are loaded from three sources:
  1. **Agent files:** `AGENTS.md`, `AGENT.md`, or `CLAUDE.md` at workspace root (first found wins, always `alwaysApply: true`)
  2. **Markdown rules:** `.continue/rules/*.md` and `.continue/prompts/*.md` files with YAML frontmatter
  3. **Colocated rules:** `rules.md` files anywhere in the codebase (scoped to their directory)
  
  Rules are assembled by `getSystemMessageWithRules()` which appends applicable rules to the base system message.
- **Position:** Appended to the end of the system message. Base system message comes first (mode-specific: chat/agent/plan), then all applicable rules are concatenated with `\n\n` separators.
- **Role:** `system` message.
- **Conditional rules:** Sophisticated matching via `shouldApplyRule()`:
  - Glob patterns for file paths
  - Regex patterns for file content matching
  - Directory scoping for colocated rules
  - `alwaysApply` flag (true/false/undefined)
  - Policy overrides (`on`/`off` per rule name)
  - File paths extracted from both user messages (code blocks) and context items
- **Compaction:** Continue uses conversation summaries. Rules are in the system prompt which is rebuilt each turn, so they survive compaction of conversation history. However, the conversation summary may lose context about which rules were previously applied.
- **Context providers:** Rules can also be manually injected via the `@rules` context provider, which adds rule content as user message context items.

### 6. Aider

**Source:** `Aider-AI/aider` GitHub repo, direct source code analysis of `base_coder.py` and `chat_chunks.py`.

- **Injection mechanism:** Aider uses a `ChatChunks` dataclass that structures the entire message array into ordered segments:
  ```python
  system -> examples -> readonly_files -> repo -> done -> chat_files -> cur -> reminder
  ```
  - The **system prompt** (beginning) contains the main instructions + `system_reminder` appended.
  - The **repo map** is injected as user/assistant message pairs after examples.
  - **Read-only files** (conventions, reference docs) are injected as user/assistant pairs.
  - The **system_reminder** is ALSO placed at the END of the conversation as either a `system` message or stuffed into the last `user` message.
- **Position:** Dual injection -- beginning (system prompt) AND end (reminder after all conversation).
- **Role:** System prompt is `system` role. Reminder is either `system` role or appended to the last `user` message, depending on `model.reminder` setting (`"sys"` or `"user"`).
- **system_reminder content:** Contains the edit format rules (e.g., SEARCH/REPLACE block rules). This is the most critical instruction for correct output formatting.
- **Compaction:** Aider uses recursive LLM-based summarization (`ChatSummary`). The system prompt and reminder are NEVER included in the summarization -- they are separate chunks. The `done_messages` (summarized history) sit between the static context (system/examples/repo/files) and current messages. The repo map and read-only files are also never summarized. This effectively makes them compression-proof.
- **Conventions:** Loaded via `--read` flag (read-only files) or conventions file. These become part of the `readonly_files` chunk, injected as user messages with content prefix: "Here are some read-only files..."
- **Cache control:** Aider adds `cache_control: {"type": "ephemeral"}` headers to key message boundaries (examples, repo map, chat files) for prompt caching optimization.

---

## Key Architectural Patterns

### Pattern 1: System Prompt Injection (Most Common)
**Used by:** Cursor, Cline, Windsurf, Continue.dev, Aider (primary)

Rules are concatenated into the system prompt. Simple, but:
- Pros: Highest priority for instruction following, always first in context
- Cons: Grows system prompt size, no positional recency benefit, lost if system prompt is truncated

### Pattern 2: User Message with XML Tags (Codex CLI)
**Used by:** Codex CLI

Instructions injected as user-role messages with structural XML markers. Distinctive because:
- Pros: Can be re-injected each turn, explicitly filtered from compaction, semantically tagged
- Cons: User-role messages may have lower instruction-following priority than system messages

### Pattern 3: Dual Injection / Recency Reinforcement (Aider)
**Used by:** Aider

Critical instructions appear both at the beginning (system prompt) AND end (reminder) of the context. This is the most robust approach because:
- Pros: Leverages both primacy and recency bias, survives attention degradation in long contexts
- Cons: Token overhead from duplication

### Pattern 4: Dynamic Skill Loading (Cline, Codex CLI)
**Used by:** Cline, Codex CLI

Skills/instructions loaded on-demand during conversation via tool calls or XML injection:
- Pros: No upfront token cost, contextually relevant, can be large
- Cons: Available only after activation, may not survive compaction

### Pattern 5: Conditional Rule Activation
**Used by:** Cline, Continue.dev, Codex CLI, Cursor

Rules only injected when file paths match globs/patterns:
- Pros: Reduces system prompt size, contextually relevant rules only
- Cons: Rules may not activate when expected if file detection fails

---

## Compression-Proof Anchoring Comparison

| Tool | Approach | Effectiveness |
|---|---|---|
| **Codex CLI** | XML-tagged fragments explicitly excluded from memory generation, re-injected fresh each turn | **Best** -- explicit, architectural |
| **Aider** | System prompt + reminder never part of summarizable history; separate ChatChunks segments | **Strong** -- structural separation |
| **Continue.dev** | System prompt rebuilt each turn from rules; conversation summaries don't include system messages | **Good** -- implicit through rebuild |
| **Cline** | System prompt rebuilt each turn; `new_task` handoff passes structured context but may lose rules | **Moderate** -- depends on handoff |
| **Cursor** | Server-side context management (opaque) | **Unknown** |
| **Windsurf** | External memory system re-injects across sessions | **Good** for memories, unknown for rules |

---

## Recommendations for Our Agent

Based on this analysis, the optimal instruction injection strategy combines:

1. **System prompt for always-on rules** (like all tools do)
2. **End-of-context reminder for critical formatting rules** (Aider pattern -- proven effective)
3. **XML-tagged contextual fragments for dynamic instructions** (Codex pattern -- explicit compaction exclusion)
4. **Conditional activation based on file paths** (Cline/Continue pattern -- reduces token waste)
5. **Explicit compaction exclusion** for instruction fragments (Codex pattern -- most architecturally clean)
