# Sub-Agent Control, Status Checking, Rollback & Background Agent Management

Research date: 2026-03-24
Sources: Claude Code official docs, GitHub issues, DeepWiki, dev.to, Codex CLI docs, Cline docs

---

## 1. CLAUDE CODE — Background Agent Status & Notification

### 1.1 How Background Agents Work

When `run_in_background: true` is set (or `background: true` in agent frontmatter), the subagent runs asynchronously. The main agent continues accepting user input while the subagent works independently.

**Three ways to background:**
1. Set `run_in_background: true` in Agent tool invocation
2. Set `background: true` in custom agent frontmatter YAML
3. Press `Ctrl+B` during a running foreground subagent to background it mid-execution

**Permission handling for background agents:**
- Before launching, Claude Code prompts for any tool permissions the subagent will need upfront
- Once running, the subagent inherits these pre-approved permissions
- Auto-denies anything not pre-approved
- If a background subagent needs to ask clarifying questions, that tool call fails but the subagent continues

### 1.2 Notification When Background Agent Completes

**Internal mechanism:** `TaskOutputTool` delivers final results with:
- Final response summary (capped at 3 lines in UI)
- Full transcript file reference
- Metrics: token count, tool uses, and duration
- Agent ID (for resume capability)

**What the main agent sees in the tool result:**
```
[subagent's final text response]

Agent completed successfully.
agentId: fad96168
```

The agent ID was initially missing from sync completions (bug #10864, fixed in v2.0.51+). The fix appends the agentId as a text block:
```javascript
if (A.status === "completed")
  return {
    tool_use_id: B,
    type: "tool_result",
    content: [
      ...A.content,
      {type: "text", text: `\nAgent completed successfully.\nagentId: ${A.agentId}`}
    ]
  };
```

### 1.3 Status Checking Mid-Execution

- `/tasks` command: Shows all running and completed background agents with status, token usage, progress
- Output files stored at `/private/tmp/claude/-Users-[path]/tasks/[agent-id].output`
- `tail -f [output-file]` enables live monitoring of a background agent
- `TaskUpdate` tool: Subagents can report progress without completing (intermediate status updates)

### 1.4 Lifecycle Hooks for Background Agent Events

| Hook Event | Trigger Point |
|-----------|---------------|
| `SubagentStart` | When a subagent begins execution |
| `SubagentStop` | When a subagent completes |
| `TaskCompleted` | Task completion |
| `TeammateIdle` | Teammate waiting for work (agent teams) |

Hooks can be configured in `settings.json` with matchers to target specific agent types:
```json
{
  "hooks": {
    "SubagentStop": [
      {
        "matcher": "db-agent",
        "hooks": [
          { "type": "command", "command": "./scripts/cleanup-db-connection.sh" }
        ]
      }
    ]
  }
}
```

---

## 2. CLAUDE CODE — Sub-Agent Result Visibility

### 2.1 What the Parent Agent Sees

**Only the final message.** Intermediate tool calls, file reads, and reasoning all stay inside the subagent's context window. The parent receives:
- The subagent's final text response (verbatim)
- Agent ID for resume capability
- Metrics (token count, tool uses, duration)

**The parent does NOT see:**
- Individual tool calls made by the subagent
- Files the subagent read
- Intermediate reasoning/thinking
- The subagent's conversation history

### 2.2 Subagent Transcripts

Stored at: `~/.claude/projects/{project}/{sessionId}/subagents/agent-{agentId}.jsonl`
- Persist independently of the main conversation
- Survive main conversation compaction
- Auto-cleaned after 30 days (configurable via `cleanupPeriodDays`)

### 2.3 Resuming Subagents

Using the `resume` parameter with previous agent ID:
```xml
<invoke name="Agent">
  <parameter name="resume">fad96168</parameter>
  <parameter name="prompt">Continue where we left off...</parameter>
</invoke>
```

If a stopped subagent receives a `SendMessage`, it auto-resumes in the background.

---

## 3. CLAUDE CODE — Agent Tool Parameters (Exact Format)

### 3.1 Agent Tool Invocation

```xml
<invoke name="Agent">
  <parameter name="subagent_type">general-purpose</parameter>
  <parameter name="description">Short task description (3-5 words)</parameter>
  <parameter name="prompt">Detailed instructions for the sub-agent</parameter>
  <parameter name="run_in_background">true</parameter>
  <parameter name="model">haiku</parameter>
  <parameter name="resume">agent_id</parameter>
</invoke>
```

**Required parameters:**
- `subagent_type`: Agent variant (general-purpose, explore, plan, bash, or custom name)
- `description`: Concise 3-5 word summary
- `prompt`: Complete task instructions (only channel from parent to subagent)

**Optional parameters:**
- `run_in_background`: Boolean, default false (foreground/blocking)
- `model`: claude-haiku, claude-sonnet, claude-opus, or full model ID
- `resume`: Agent ID to continue previous work

### 3.2 Built-in Agent Types

| Type | Model | Tools | Purpose |
|------|-------|-------|---------|
| explore | Haiku | Read-only (no Write/Edit) | Fast codebase search/analysis |
| plan | Inherits | Read-only | Research for plan mode |
| general-purpose | Inherits | All tools | Complex multi-step operations |
| bash | Inherits | Terminal commands | Running commands in separate context |

### 3.3 Custom Agent Frontmatter Fields

```yaml
---
name: code-reviewer            # Required: unique identifier
description: Reviews code      # Required: when to delegate
tools: Read, Grep, Glob, Bash  # Optional: tool allowlist
disallowedTools: Write, Edit   # Optional: tool denylist
model: sonnet                  # Optional: sonnet/opus/haiku/inherit/full-ID
permissionMode: default        # Optional: default/acceptEdits/dontAsk/bypassPermissions/plan
maxTurns: 20                   # Optional: max agentic turns
skills: [api-conventions]      # Optional: preloaded skill content
mcpServers: [github]           # Optional: MCP server access
hooks: { ... }                 # Optional: lifecycle hooks
memory: user                   # Optional: user/project/local persistence
background: true               # Optional: always run as background
effort: high                   # Optional: low/medium/high/max
isolation: worktree            # Optional: git worktree isolation
---
System prompt in markdown body...
```

---

## 4. CLAUDE CODE — Agent Isolation (Worktrees)

### 4.1 How Worktree Isolation Works

- `--worktree <name>` flag (or `-w`) creates isolated worktree at `<repo>/.claude/worktrees/<name>/`
- Branch named `worktree-<name>`, branching from default remote branch
- Each subagent with `isolation: worktree` gets its own worktree
- Complete file system separation while sharing git history and remotes

### 4.2 Preventing Concurrent Edit Conflicts

- Each agent in its own worktree has its own copy of all files
- No file locking needed — physical separation prevents conflicts
- Changes are on separate branches — merging happens afterward
- `.claude/worktrees/` should be added to `.gitignore`

### 4.3 Cleanup Behavior

| Condition | Behavior |
|-----------|----------|
| No changes | Worktree + branch removed automatically |
| Uncommitted changes exist | Prompts keep/remove |
| Commits exist | Prompts keep/remove |
| Keeping | Preserves directory + branch for later |
| Removing | Deletes worktree directory + branch, discards all changes |

**Known issues:**
- Cleanup checks only uncommitted changes, not diverged commits (bug #27753)
- Stale worktrees not cleaned up after crashes (bug #26725)
- No garbage collection on subsequent session starts
- `.claude/` subdirectories (skills, agents, docs) not copied to worktree (bug #28041)

---

## 5. CLAUDE CODE — Rollback / Undo of Sub-Agent Changes

### 5.1 Built-in Checkpointing (Rewind)

**Mechanism:** Every user prompt creates a checkpoint. Checkpoints track all changes made by Claude's file editing tools (Write, Edit).

**Rewind options (Esc+Esc or `/rewind`):**
1. **Restore code and conversation** — revert both to that point
2. **Restore conversation only** — rewind messages, keep current code
3. **Restore code only** — revert file changes, keep conversation
4. **Summarize from here** — compress conversation from that point

**Internal storage:** Uses git-based shadow mechanism. Modifications are tracked and can be explored with `git stash list`. Some implementations use shadow git repos at `~/.claude/hooks/...checkpoints/{project_hash}/`.

### 5.2 Limitations of Checkpointing for Sub-Agents

- Only tracks files modified by Claude's file editing tools (Write, Edit)
- Does NOT track bash command changes (rm, mv, cp)
- Does NOT track external changes made outside Claude
- Does NOT explicitly support cross-agent rollback
- No "undo what subagent X did" command

### 5.3 Worktree-Based Rollback (Recommended for Subagents)

The safest rollback for subagent work:
1. Use `isolation: worktree` for the subagent
2. If results are bad, choose "remove" when prompted at exit
3. This deletes the entire worktree + branch, discarding all changes
4. If results are good, merge the worktree branch

### 5.4 Third-Party Rollback Tools

- **Rewind MCP server** (`mcpmarket.com/server/rewind`): Automatic snapshots before AI modifications
- **ccundo** (`github.com/RonitSachdev/ccundo`): Reads session files, tracks file operations, selective revert with cascading safety
- **claude-code-checkpointing-hook** (`github.com/Ixe1`): PreToolUse hook creates git commits in shadow repo before modifications
- **claude-code-rewind** (`github.com/holasoymalva`): Time machine with visual diffs

---

## 6. CLAUDE CODE — Kill / Cancel Mechanism

### 6.1 Available Kill Methods

| Method | Scope | Details |
|--------|-------|---------|
| `Ctrl+B` | Single task | Background a running task (doesn't kill it) |
| `/tasks` | Monitoring | View all background agents, click for details |
| Natural language | Single task | "Kill background task [id]" |
| `KillBash` tool | Bash processes | Kill background bash commands specifically |
| `TaskUpdate` with `delete` | Task removal | Delete a task from active list |
| `CLAUDE_CODE_DISABLE_BACKGROUND_TASKS=1` | All background | Disables all background task functionality |

### 6.2 What Happens to Changes When Killed

- **No explicit cleanup documented** for killed mid-execution agents
- Partial file changes may remain on disk
- With worktree isolation: changes are contained in the worktree (can be removed)
- Without isolation: partial changes remain in the working directory

### 6.3 Known Issues

- No force-kill or timeout mechanism for unresponsive teammate agents (bug)
- Agent count indicator in statusline persists after completion (bug #34436)
- Hung agents can ignore shutdown_request (bug #31788)
- Agents can enter runaway state — ignores user, restarts killed processes, survives session kill (bug #25963)
- Feature request for "silent kill" that enforces at runtime level, not as suggestion (issue #19490)

---

## 7. CLAUDE CODE — Agent Teams (Multi-Agent Coordination)

### 7.1 Architecture

Fundamentally decentralized, file-based coordination:

```
~/.claude/
  teams/{team-name}/
    config.json              # Member registry
    inboxes/{agent-name}.json  # Per-agent mailbox
  tasks/{team-name}/
    .lock                    # flock()-based mutual exclusion
    .highwatermark           # Next available task ID
    1.json, 2.json, ...      # Individual task files
```

### 7.2 Task File Schema

```json
{
  "id": "1",
  "subject": "Hunt for bugs",
  "description": "...",
  "activeForm": "Hunting for bugs",
  "owner": "bug-hunter",
  "status": "completed",
  "blocks": [],
  "blockedBy": []
}
```

Status transitions: `pending` -> `in_progress` -> `completed` (or `deleted`)

### 7.3 SendMessage Protocol

Mailbox format (append-only JSON array):
```json
{
  "from": "team-lead",
  "text": "{\"type\":\"task_assignment\",\"taskId\":\"1\",\"subject\":\"...\"}",
  "timestamp": "2026-02-18T02:37:16.890Z",
  "read": false
}
```

Message types: `task_assignment`, `message`, `broadcast`, `shutdown_request`, `shutdown_response`, `plan_approval_request`, `plan_approval_response`, `idle_notification`

### 7.4 Shutdown Protocol

1. Lead sends `shutdown_request` message type
2. Teammate responds with `shutdown_response`
3. `TeamDelete` removes `~/.claude/teams/{team-name}/` and `~/.claude/tasks/{team-name}/`
4. Cleanup fails if any teammates remain active

---

## 8. CODEX CLI — Sub-Agent Control & Isolation

### 8.1 Agent Hierarchy

Built-in roles: `default`, `worker`, `explorer`
- Workers get explicit file/module ownership
- Workers told "you are not alone in the codebase, don't revert others' edits"
- Explorers are read-only, can be spawned in parallel

### 8.2 Concurrency Control

```toml
[agents]
max_threads = 6        # Concurrent open thread cap
max_depth = 1          # Spawned agent nesting depth
job_max_runtime_seconds = 1800  # Per-worker timeout (CSV jobs)
```

### 8.3 File Ownership for Workers

- Workers identify entry points, state transitions, and likely files before starting
- CSV fan-out: `spawn_agents_on_csv` with path and owner columns
- Workers must call `report_agent_job_result` exactly once
- If a worker exits without reporting, Codex marks that row with error
- SQLite-backed state tracking for agent jobs

### 8.4 CSV Fan-Out Pattern

```
spawn_agents_on_csv parameters:
- csv_path: Source CSV file
- instruction: Worker prompt template with {column_name} placeholders
- id_column: Optional stable item identifiers
- output_schema: Optional JSON shape
- output_csv_path: Result export destination
- max_concurrency: Job parallelism control
- max_runtime_seconds: Per-worker timeout override
```

### 8.5 Sandbox Isolation

- Sandbox modes: `read-only`, `workspace-write` (per agent)
- Subagents inherit parent's sandbox policy
- Runtime overrides (approvals, --yolo flags) propagate to children
- Linux bubblewrap sandbox unshares user namespace
- Spawned subagents inherit symlinked writable roots

### 8.6 Kill/Cancel

- Direct prompts to steer, stop, or close agent threads
- CLI `/agent` command for thread switching/inspection
- Non-interactive: actions needing approval fail and error surfaces to parent
- Per-worker timeout via `job_max_runtime_seconds`

---

## 9. CLINE — Sub-Agent Results

### 9.1 Architecture

- `use_subagents` tool: up to 5 parallel subagents per call
- Each subagent gets its own `SubagentRunner` with independent context + API handler
- Subagents are read-only research agents only

### 9.2 Allowed Tools

```typescript
SUBAGENT_DEFAULT_ALLOWED_TOOLS = [
    FILE_READ, LIST_FILES, SEARCH, LIST_CODE_DEF, BASH, USE_SKILL, ATTEMPT
]
```

Cannot: write files, apply patches, use browser, access MCP, spawn nested subagents, web search

### 9.3 Result Format

- Subagent must call `attempt_completion` when done
- Result goes directly to main agent
- Must include "Relevant file paths" section
- Per-subagent stats tracked: toolCalls, tokens, cost, contextUsagePercentage
- Cost rolled into task's total cost
- Per-subagent stats visible in chat UI

### 9.4 Lifecycle & Error Handling

1. `SubagentBuilder` creates API handler, resolves tools, builds system prompt
2. `SubagentRunner.run()` executes agent loop
3. Proactive compaction at 75% context threshold
4. Context overflow: compact + retry up to 3 attempts
5. Abort support: cancels active API stream + running commands
6. Progress callbacks report stats and latest tool call to parent

---

## 10. Key Architectural Decisions Summary

| Feature | Claude Code | Codex CLI | Cline |
|---------|-------------|-----------|-------|
| Sub-agent writes | Yes (configurable) | Yes (workers with ownership) | No (read-only only) |
| Isolation | Git worktrees | Sandbox modes (OS-level) | Separate context window |
| Result visibility | Final message only | report_agent_job_result | attempt_completion result |
| Background agents | Yes (Ctrl+B, background flag) | Yes (thread management) | No (all foreground) |
| Kill mechanism | Natural language, KillBash | /agent command, timeouts | Abort via UI |
| Rollback | Checkpointing + worktree removal | No explicit mechanism | No mechanism |
| Max concurrent | Unlimited (practical limits) | 6 (configurable) | 5 per use_subagents call |
| Inter-agent comms | SendMessage (file-based mailbox) | InterAgentCommunication protocol | None (parallel only) |
| Coordination | File-based (tasks/ + inboxes/) | Agent registry + threads | Independent runners |
| Nesting | No (subagents cannot spawn subagents) | Depth 1 default (configurable) | No nesting |
