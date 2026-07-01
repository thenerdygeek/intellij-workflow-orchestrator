# Cline Sub-Agent & Task Delegation Architecture (Source: cline/cline repo, March 2026)

## 1. Two Distinct Delegation Mechanisms

Cline has TWO separate delegation systems:
- **`new_task` tool** — Context-preserving task handoff (ends current session, starts new one)
- **`use_subagents` tool** — In-process parallel sub-agents for research/exploration

Plus a related but separate mechanism:
- **`summarize_task` tool** — Auto-compaction triggered when context window fills up

---

## 2. Sub-Agent Architecture (`use_subagents`)

### Tool Definition (system prompt)
```
"Run up to five focused in-process subagents in parallel. Each subagent gets its own
prompt and returns a comprehensive research result with tool and token stats. Use this
for broad exploration when reading many files would consume the main agent's context
window. You do not need to launch multiple subagents every time; using one subagent
is valid when it avoids unnecessary context usage for light discovery work."
```

### Parameters
- `prompt_1` (required): First subagent prompt
- `prompt_2` through `prompt_5` (optional): Additional parallel subagent prompts

### Key Design Decisions
- **Max 5 parallel subagents** (`MAX_SUBAGENT_PROMPTS = 5`)
- **Guard: `contextRequirements: (context) => context.subagentsEnabled === true && !context.isSubagentRun`** — subagents CANNOT spawn subagents (no recursion)
- **Requires user approval** unless auto-approve is enabled
- **Each subagent runs in-process** with its own conversation loop, API handler, and context management

### SubagentRunner — The Execution Engine
Location: `src/core/task/tools/subagent/SubagentRunner.ts`

Each subagent is a **full ReAct loop**:
1. Gets its own `SubagentBuilder` (builds system prompt + API handler)
2. Runs `while(true)` loop: send prompt -> stream response -> parse tool calls -> execute tools -> push results -> repeat
3. Terminates when the sub-agent calls `attempt_completion` tool
4. Returns `SubagentRunResult { status, result, error, stats }`

**Allowed tools for subagents** (from `SubagentBuilder.ts`):
```typescript
export const SUBAGENT_DEFAULT_ALLOWED_TOOLS: ClineDefaultTool[] = [
    ClineDefaultTool.FILE_READ,      // read_file
    ClineDefaultTool.LIST_FILES,      // list_files
    ClineDefaultTool.SEARCH,          // search_files
    ClineDefaultTool.LIST_CODE_DEF,   // list_code_definition_names
    ClineDefaultTool.BASH,            // execute_command
    ClineDefaultTool.USE_SKILL,       // use_skill
    ClineDefaultTool.ATTEMPT,         // attempt_completion
]
```
Note: NO file write, NO browser, NO MCP, NO new_task, NO sub-subagents.

### Subagent System Prompt Suffix
```
"You are running as a research subagent. Your job is to explore the codebase and
gather information to answer the question.
Explore, read related files, trace through call chains, and build a complete picture
before reporting back.
You can read files, list directories, search for patterns, list code definitions,
and run commands.
Only use execute_command for readonly operations like ls, grep, git log, git diff, gh, etc.
Do not run commands that modify files or system state.
When you have a comprehensive answer, call the attempt_completion tool.
The attempt_completion result field is sent directly to the main agent, so put your
full final findings there.
Unless the subagent prompt explicitly asks for detailed analysis, keep the result
concise and focus on the files the main agent should read next.
Include a section titled 'Relevant file paths' and list only file paths, one per line."
```

### Subagent Context Management
- Each subagent gets its own `ContextManager` instance
- Proactive compaction: `shouldCompactBeforeNextRequest()` checks if tokens exceed 75% of context window (for auto-condense models) or maxAllowedSize
- Compaction strategy: first tries `attemptFileReadOptimizationInMemory`, then falls back to `getNextTruncationRange` with "quarter" strategy
- Context window exceeded errors trigger automatic compaction + retry (up to 3 attempts)

### Subagent Stats Tracking
Each subagent tracks: toolCalls, inputTokens, outputTokens, cacheWriteTokens, cacheReadTokens, totalCost, contextTokens, contextWindow, contextUsagePercentage

### SubagentToolHandler — Orchestration
Location: `src/core/task/tools/handlers/SubagentToolHandler.ts`

- Creates N `SubagentRunner` instances (one per prompt)
- Runs ALL in parallel via `Promise.allSettled(execution)`
- Aggregates results with status (completed/failed), tool call counts, token usage
- Returns formatted summary to main agent:
```
"Subagent results:
Total: N
Succeeded: X
Failed: Y
Tool calls: Z
Peak context usage: ...
[1] COMPLETED - <prompt>
<result excerpt>
[2] FAILED - <prompt>
<error excerpt>"
```

### Dynamic Named Subagents (Agent Config)
Location: `src/core/task/tools/subagent/AgentConfigLoader.ts`

Users can define custom named agents in `~/Documents/Cline/Agents/*.yaml`:
```yaml
---
name: "Security Reviewer"
description: "Reviews code for security vulnerabilities"
modelId: "anthropic/claude-sonnet-4"  # optional model override
tools: [read_file, search_files, list_files]  # optional tool restriction
skills: [skill_name]  # optional skill restriction
---
You are a security review specialist. Analyze code for...
```

These become **dynamically registered tools** like `use_subagent_security_reviewer` with their own system prompts, tool restrictions, and optional model overrides. The `AgentConfigLoader` watches the directory with chokidar for hot-reload.

---

## 3. new_task Tool — Task Handoff

### Tool Definition
```
"Request to create a new task with preloaded context covering the conversation with
the user up to this point and key information for continuing with the new task."
```

### Required Context Parameter Structure
1. **Current Work**: What was being worked on (focus on recent messages)
2. **Key Technical Concepts**: Technologies, conventions, frameworks discussed
3. **Relevant Files and Code**: Specific files examined/modified/created with code snippets
4. **Problem Solving**: Problems solved, ongoing troubleshooting
5. **Pending Tasks and Next Steps**: Outstanding work with verbatim quotes from conversation

### Implementation Flow
Location: `src/core/task/tools/handlers/NewTaskHandler.ts`

1. LLM calls `new_task` with context parameter
2. Context is shown to user as a preview
3. User can either:
   - Click "Create New Task" -> response: "The user has created a new task with the provided context."
   - Provide feedback text -> response: "The user provided feedback instead of creating a new task"
4. If user approves, `controller.initTask()` creates new task session with the context preloaded
5. Old task session ends completely

### new_task is DISABLED in YOLO mode
```typescript
contextRequirements: (context) => !context.yoloModeToggled
```

### /newtask Slash Command
Users can type `/newtask` which triggers explicit instructions:
```
"The user has explicitly asked you to help them create a new task with preloaded context,
which you will generate... you are ONLY allowed to respond to this message by calling
the new_task tool."
```

---

## 4. summarize_task — Auto-Compaction (Context Window Management)

Location: `src/core/prompts/contextManagement.ts`

When context window fills up, the system injects an explicit instruction:
```
"The current conversation is rapidly running out of context. Now, your urgent task is
to create a comprehensive detailed summary..."
```

The LLM must call `summarize_task` with:
1. Primary Request and Intent
2. Key Technical Concepts
3. Files and Code Sections (with code snippets)
4. Problem Solving
5. Pending Tasks
6. Task Evolution (original task -> modifications -> current active task with direct quotes)
7. Current Work
8. Next Step (with verbatim quotes)
9. Required Files (paths relative to CWD)

The summary then replaces the conversation via `continuationPrompt()`:
```
"This session is being continued from a previous conversation that ran out of context.
The conversation is summarized below: [summary].
Please continue the conversation from where we left it off without asking the user
any further questions."
```

---

## 5. MCP Integration & Tool Delegation

### How MCP Enables Tool Delegation
MCP servers expose tools that appear alongside built-in tools in the system prompt. Each MCP tool becomes a native tool with name format: `{server_uid}__mcp__{tool_name}` (max 64 chars).

The LLM sees MCP tools identically to built-in tools. There is NO special delegation logic — the LLM simply calls the tool by name. MCP tools are NOT available to subagents (only the main agent gets them).

### MCP Tool Registration
```typescript
// MCP tools converted to ClineToolSpec format dynamically
const mcpTools = mcpServers?.flatMap((server) => mcpToolToClineToolSpec(variant.family, server))
```

---

## 6. Prompt Engineering Patterns

### Pattern 1: XML Tool Use Format
All tools use XML-style tags, not JSON function calling (except for "native tool call" models):
```xml
<tool_name>
<parameter1_name>value1</parameter1_name>
</tool_name>
```

### Pattern 2: One Tool Per Message (Main Agent)
```
"You can use one tool per message, and will receive the result of that tool use
in the user's response."
```
Subagents follow the same pattern.

### Pattern 3: Explicit Instructions Pattern
Slash commands inject `<explicit_instructions type="...">` blocks that force specific tool usage:
```
"you are ONLY allowed to respond to this message by calling the new_task tool"
```

### Pattern 4: Subagent Anti-Recursion Guard
```typescript
contextRequirements: (context) => context.subagentsEnabled === true && !context.isSubagentRun
```
Subagents have `isSubagentRun: true` in their context, preventing them from seeing the `use_subagents` tool.

### Pattern 5: Read-Only Subagent Constraint
The subagent system prompt explicitly says "Do not run commands that modify files or system state" even though `execute_command` is available. This is a prompt-level constraint, not a code-level one.

### Pattern 6: Result Routing
```
"The attempt_completion result field is sent directly to the main agent, so put
your full final findings there."
```

### Pattern 7: Context-Aware Tool Gating
Tools are conditionally included based on context:
- `new_task` hidden in YOLO mode
- `use_subagents` hidden when disabled or inside subagent
- `plan_mode_respond` / `act_mode_respond` depend on current mode
- Dynamic agent tools only shown when subagents enabled

### Pattern 8: Model-Specific Prompt Variants
Different model families (generic, next-gen, gemini-3, gpt-5, hermes, glm, xs, devstral) get different prompt variants with different tool sets. The `PromptRegistry` + `ClineToolSet` handle variant selection.

---

## 7. Architecture Summary

```
Main Agent (full tool set)
  |
  |-- use_subagents tool
  |     |-- SubagentRunner #1 (parallel, read-only tools, own context window)
  |     |-- SubagentRunner #2 (parallel, read-only tools, own context window)
  |     |-- ... up to 5
  |     |-- Results aggregated back to main agent
  |
  |-- new_task tool
  |     |-- Ends current session
  |     |-- Starts new session with preloaded context summary
  |
  |-- summarize_task tool (auto-triggered)
  |     |-- Compacts context window in-place
  |     |-- Continues same session with summary
  |
  |-- use_mcp_tool (external tool servers)
  |-- use_skill (reusable prompt snippets)
  |-- Dynamic named subagents (use_subagent_xxx)
```

Key insight: Cline's subagents are NOT autonomous agents — they are **research assistants** with read-only access that report back to the main agent. The main agent retains all decision-making authority. This is a hub-and-spoke model, not a multi-agent collaboration.
