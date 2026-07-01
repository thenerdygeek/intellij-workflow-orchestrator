# Claude Code Sub-Agent/Task Tool Implementation (v2.1.84-86)

Source: Piebald-AI/claude-code-system-prompts repo (extracted from npm package via prompt tracing)

## 1. Tool Naming History

- Originally called "Agent tool" / "Task tool"
- v2.1.63: Task tool renamed to Agent. Existing `Task(...)` references still work as aliases.
- Current tool name: `Agent` (with `TaskCreate` being a separate task-tracking tool)

## 2. Agent Tool Description (System Prompt Wording)

From `tool-description-agent-when-to-launch-subagents.md`:

```
Launch a new agent to handle complex, multi-step tasks autonomously.

The ${AGENT_TOOL_NAME} tool launches specialized agents (subprocesses) that autonomously
handle complex tasks. Each agent type has specific capabilities and tools available to it.

${AVAILABLE_AGENT_TYPES}

When using the ${AGENT_TOOL_NAME} tool, specify a subagent_type parameter to select which
agent type to use. If omitted, the general-purpose agent is used.
```

Alternative when fork context is available:
```
When using the ${AGENT_TOOL_NAME} tool, specify a subagent_type to use a specialized agent,
or omit it to fork yourself -- a fork inherits your full conversation context.
```

## 3. Agent Tool Parameter Schema

From the gist and system prompts:

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "type": "object",
  "required": ["description", "prompt"],
  "additionalProperties": false,
  "properties": {
    "description": {
      "type": "string",
      "description": "A short (3-5 word) description of the task"
    },
    "prompt": {
      "type": "string",
      "description": "The task for the agent to perform"
    },
    "subagent_type": {
      "type": "string",
      "description": "The type of specialized agent to use for this task"
    },
    "name": {
      "type": "string",
      "description": "Short name (one or two words, lowercase) for UI display"
    },
    "model": {
      "type": "string",
      "description": "sonnet (default), opus, haiku, or a full model ID like claude-opus-4-6"
    },
    "run_in_background": {
      "type": "boolean",
      "description": "Set to true to run this agent in the background"
    },
    "isolation": {
      "type": "string",
      "enum": ["worktree"],
      "description": "Set to 'worktree' to run in a temporary git worktree"
    },
    "allowed_tools": {
      "type": "array",
      "items": { "type": "string" },
      "description": "Array of tools to grant the agent"
    }
  }
}
```

Note: `subagent_type` is NOT required in the schema -- omitting it either forks the current context (if fork experiment active) or uses general-purpose agent.

## 4. Built-In Agent Types

### 4a. Explore Agent
- **agentType**: `Explore`
- **Default model**: `haiku` (fast, low-latency)
- **Tools**: Glob, Grep, Read, Bash (read-only), LS
- **Disallowed tools**: Agent, ExitPlanMode, Edit, Write, NotebookEdit
- **whenToUse** (exact text):
  ```
  Fast agent specialized for exploring codebases. Use this when you need to quickly find files by
  patterns (eg. "src/components/**/*.tsx"), search code for keywords (eg. "API endpoints"), or answer
  questions about the codebase (eg. "how do API endpoints work?"). When calling this agent, specify
  the desired thoroughness level: "quick" for basic searches, "medium" for moderate exploration, or
  "very thorough" for comprehensive analysis across multiple locations and naming conventions.
  ```
- **System prompt key points**:
  - "You are a file search specialist for Claude Code"
  - READ-ONLY MODE - strictly prohibited from creating/modifying/deleting files
  - "You are meant to be a fast agent that returns output as quickly as possible"
  - "Wherever possible you should try to spawn multiple parallel tool calls"
  - "Adapt your search approach based on the thoroughness level specified by the caller"
  - "Communicate your final report directly as a regular message - do NOT attempt to create files"

### 4b. Plan Agent
- **agentType**: `Plan`
- **Default model**: `inherit` (uses main conversation model)
- **Tools**: Read-only tools
- **Disallowed tools**: Agent, ExitPlanMode, Edit, Write, NotebookEdit
- **whenToUse** (exact text):
  ```
  Software architect agent for designing implementation plans. Use this when you need to plan the
  implementation strategy for a task. Returns step-by-step plans, identifies critical files, and
  considers architectural trade-offs.
  ```
- **System prompt key points**:
  - "You are a software architect and planning specialist for Claude Code"
  - READ-ONLY MODE
  - Process: Understand Requirements -> Explore Thoroughly -> Design Solution -> Detail the Plan
  - Must output "Critical Files for Implementation" section at end
  - "REMEMBER: You can ONLY explore and plan. You CANNOT and MUST NOT write, edit, or modify any files."

### 4c. General-Purpose Agent
- **agentType**: `general-purpose`
- **Default model**: `inherit`
- **Tools**: ALL tools (*)
- **whenToUse** (exact text):
  ```
  General-purpose agent for researching complex questions, searching for code, and executing
  multi-step tasks. When you are searching for a keyword or file and are not confident that you will
  find the right match in the first few tries use this agent to perform the search for you.
  ```
- **System prompt key points**:
  - "You are an agent for Claude Code, Anthropic's official CLI for Claude"
  - "Complete the task fully -- don't gold-plate, but don't leave it half-done"
  - "respond with a concise report covering what was done and any key findings"
  - "the caller will relay this to the user, so it only needs the essentials"
  - Strengths: searching code, analyzing multiple files, investigating complex questions, multi-step research
  - "NEVER create files unless they're absolutely necessary"
  - "NEVER proactively create documentation files (*.md) or README files"

### 4d. Fork (implicit agent type)
- **agentType**: `fork`
- **Default model**: `inherit`
- **permissionMode**: `bubble`
- **maxTurns**: 200
- **Tools**: ALL tools (*)
- **Triggered by**: Omitting `subagent_type` when fork experiment is active
- **whenToUse**: "Implicit fork -- inherits full conversation context. Not selectable via subagent_type"
- **Worker fork prompt rules**:
  - "You are a forked worker process. You are NOT the main agent."
  - "Do NOT spawn sub-agents; execute directly"
  - "Do NOT editorialize or add meta-commentary"
  - "If you modify files, commit your changes before reporting. Include the commit hash."
  - "Do NOT emit text between tool calls. Use tools silently, then report once at the end."
  - "Keep your report under 500 words"
  - Output format: Scope / Result / Key files / Files changed / Issues

### 4e. Other Built-In Agents
- **Bash**: Command execution only, inherits model
- **statusline-setup**: Sonnet model, for /statusline command
- **Claude Code Guide**: Haiku, for questions about Claude Code features
- **code-reviewer**: Custom agent for security/PR analysis
- **verification-specialist**: Adversarial testing agent with detailed PASS/FAIL/PARTIAL verdict system
- **security-review**: Comprehensive security review with false-positive filtering
- **agent-creation-architect**: Creates custom agent definitions (JSON output with identifier, whenToUse, systemPrompt)

## 5. Thoroughness Levels (Explore Agent)

Three levels specified in the `prompt` parameter when calling Explore:
- **"quick"**: Basic searches, targeted lookups. The agent does minimal exploration.
- **"medium"**: Moderate exploration, balanced depth/breadth.
- **"very thorough"**: Comprehensive analysis across multiple locations and naming conventions. Multiple search strategies, parallel tool calls.

The Explore agent is instructed: "Adapt your search approach based on the thoroughness level specified by the caller"

There is NO separate thoroughness parameter in the schema. It is conveyed as natural language within the `prompt` string.

## 6. When to Use Agent vs Direct Tools (Decision Logic)

### From system-prompt-tool-usage-direct-search.md:
```
For simple, directed codebase searches (e.g. for a specific file/class/function) use
${SEARCH_TOOLS} directly.
```

### From system-prompt-tool-usage-delegate-exploration.md:
```
For broader codebase exploration and deep research, use the ${TASK_TOOL_NAME} tool with
subagent_type=${EXPLORE_SUBAGENT.agentType}. This is slower than using ${SEARCH_TOOLS} directly,
so use this only when a simple, directed search proves to be insufficient or when your task
will clearly require more than ${QUERY_LIMIT} queries.
```

### From system-prompt-tool-usage-subagent-guidance.md:
```
Use the ${TASK_TOOL_NAME} tool with specialized agents when the task at hand matches the agent's
description. Subagents are valuable for parallelizing independent queries or for protecting the
main context window from excessive results, but they should not be used excessively when not needed.
Importantly, avoid duplicating work that subagents are already doing - if you delegate research to
a subagent, do not also perform the same searches yourself.
```

### Decision Tree (synthesized):
- **Use direct tools (Glob, Grep, Read)** when:
  - Simple, directed search for a specific file/class/function
  - You know roughly where to look
  - Fewer than ${QUERY_LIMIT} queries needed
  - Reading a specific known file path

- **Use Explore subagent** when:
  - Broader codebase exploration / deep research
  - Simple directed search proved insufficient
  - Task will clearly require many queries
  - You want to protect main context from verbose output

- **Use general-purpose subagent** when:
  - Complex multi-step task requiring both exploration and modification
  - Not confident you'll find the right match in the first few tries
  - Task requires both reading and writing

- **Use Plan subagent** when:
  - In plan mode, need to research before presenting a plan
  - Designing implementation strategy

- **Fork (omit subagent_type)** when:
  - Intermediate tool output isn't worth keeping in your context
  - Open-ended research questions (can parallelize with multiple forks)
  - Implementation requiring more than a couple of edits

## 7. How Results Are Returned to Parent

From `tool-description-agent-usage-notes.md`:
```
When the agent is done, it will return a single message back to you. The result returned by the
agent is not visible to the user. To show the user the result, you should send a text message
back to the user with a concise summary of the result.
```

Key behaviors:
- Agent returns ONE message upon completion
- Result is NOT visible to the user directly
- Parent must relay/summarize the result to the user
- Each fresh invocation with subagent_type starts WITHOUT context
- To continue a previously spawned agent, use `SendMessage` with the agent's ID or name
- Resumed subagents retain full conversation history
- Background agents: completion notification arrives as user-role message in a later turn
- Background agents: output saved to file at `/private/tmp/claude/-Users-[path]/tasks/[id].output`

## 8. "When NOT to Use" / Anti-Patterns

### From fork usage guidelines:
- "Don't peek" -- do not Read or tail the output_file path unless user explicitly asks
- "Don't race" -- never fabricate or predict fork results. If user asks before completion, say "still running"
- Don't set `model` on a fork (different model can't reuse parent's cache)

### From subagent guidance:
- "should not be used excessively when not needed"
- "avoid duplicating work that subagents are already doing"
- Don't use Explore for simple directed searches (use Glob/Grep directly)

### From delegation examples:
- Avoid nesting: "Subagents cannot spawn other subagents"
- Don't fabricate results mid-wait
- Don't use terse command-style prompts for subagent_type agents (they start fresh, need full context)

### From writing subagent prompts:
- "never delegate understanding" -- Don't write "based on your findings, fix the bug"
- "Those phrases push synthesis onto the agent instead of doing it yourself"
- "Write prompts that prove you understood: include file paths, line numbers, what specifically to change"

### From official docs:
- Don't use subagents for tasks needing frequent back-and-forth
- Don't use subagents when multiple phases share significant context
- Don't use subagents for quick targeted changes
- Don't use subagents when latency matters (they start fresh, need context gathering time)
- Limit to 3-4 custom subagents max (spending too much time deciding which to invoke kills productivity)

## 9. Foreground vs Background Decision

From usage notes:
```
Use foreground (default) when you need the agent's results before you can proceed -- e.g.,
research agents whose findings inform your next steps. Use background when you have genuinely
independent work to do in parallel.
```

Background agents:
- Auto-deny permission prompts not pre-approved
- Cannot ask clarifying questions (AskUserQuestion fails but agent continues)
- Completion notification arrives as user-role message
- User can press Ctrl+B to background a running task

## 10. Worktree Isolation

From usage notes:
```
You can optionally set isolation: "worktree" to run the agent in a temporary git worktree,
giving it an isolated copy of the repository. The worktree is automatically cleaned up if
the agent makes no changes; if changes are made, the worktree path and branch are returned
in the result.
```

## 11. Custom Subagent Definition Format

Markdown files with YAML frontmatter. Stored in:
1. `--agents` CLI flag (session scope, highest priority)
2. `.claude/agents/` (project scope)
3. `~/.claude/agents/` (user scope)
4. Plugin's `agents/` directory (lowest priority)

### Supported Frontmatter Fields
| Field | Required | Description |
|---|---|---|
| name | Yes | Unique identifier, lowercase + hyphens |
| description | Yes | When Claude should delegate to this subagent |
| tools | No | Tool allowlist. Inherits all if omitted |
| disallowedTools | No | Tool denylist, removed from inherited/specified list |
| model | No | sonnet, opus, haiku, full model ID, or inherit (default) |
| permissionMode | No | default, acceptEdits, dontAsk, bypassPermissions, plan |
| maxTurns | No | Maximum agentic turns before stopping |
| skills | No | Skills to preload into subagent context |
| mcpServers | No | MCP servers available to this subagent |
| hooks | No | Lifecycle hooks scoped to this subagent |
| memory | No | Persistent memory scope: user, project, or local |
| background | No | Set true to always run as background task |
| effort | No | low, medium, high, max (Opus 4.6 only) |
| isolation | No | Set to "worktree" for git worktree isolation |
| initialPrompt | No | Auto-submitted as first user turn when running as main session agent |

## 12. Model Resolution Priority
1. `CLAUDE_CODE_SUBAGENT_MODEL` environment variable
2. Per-invocation `model` parameter
3. Subagent definition's `model` frontmatter
4. Main conversation's model

## 13. Agent Tool Restriction Syntax
Use `Agent(agent_type)` to restrict which subagents can be spawned:
```yaml
tools: Agent(worker, researcher), Read, Bash
```
This is an allowlist. To block specific agents: `permissions.deny: ["Agent(Explore)"]`

## 14. Context Management
- Subagents support auto-compaction at ~95% capacity (configurable via CLAUDE_AUTOCOMPACT_PCT_OVERRIDE)
- Transcripts stored at `~/.claude/projects/{project}/{sessionId}/subagents/agent-{agentId}.jsonl`
- Transcripts persist independently of main conversation compaction
- Cleaned up based on cleanupPeriodDays setting (default: 30 days)
