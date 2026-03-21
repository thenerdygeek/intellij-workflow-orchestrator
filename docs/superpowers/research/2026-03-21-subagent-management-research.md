# Subagent Management Research — SendMessage, Resume, Background

Research date: 2026-03-21
Sources: Claude Code official docs (code.claude.com/docs/en/sub-agents, interactive-mode, agent-teams, tools-reference)

## Key Finding: Subagents vs Agent Teams Are DIFFERENT Systems

Claude Code has TWO parallel execution systems:

### 1. Subagents (single-session, what we're building)
- Spawned via `Agent` tool
- Run WITHIN the parent session
- Parent WAITS for foreground subagents (blocking)
- Background subagents run concurrently, parent gets notified on completion
- Resume via `Agent(resume=agentId)` — continues with full transcript
- NO inter-agent messaging — subagents only return results
- `SendMessage` is for resuming, not real-time communication
- `TaskStop` can kill background subagents
- `TaskOutput` reads buffered output from background tasks
- Subagents CANNOT spawn other subagents

### 2. Agent Teams (multi-session, experimental, NOT what we need)
- Multiple independent Claude Code instances
- Shared task list, direct teammate messaging
- Each teammate is a FULL session with its own terminal
- `SendMessage` used for inter-team communication
- Experimental feature, disabled by default
- Requires tmux or iTerm2 for split panes
- Way more complex, higher token cost

**For our plugin: We should implement SUBAGENT management only, not agent teams.**

## Subagent Lifecycle in Claude Code

### Spawning
```
Claude calls: Agent(description="review auth", prompt="Review the auth module...", subagent_type="code-reviewer")
→ Subagent starts with:
  - System prompt from agent definition markdown body
  - Tools from agent definition's tools/disallowedTools
  - Model from agent definition or parent
  - Fresh context window (no parent history)
  - CLAUDE.md + skills loaded normally
```

### Foreground Execution (default)
```
Parent: calls Agent tool → BLOCKS → waits for subagent to finish
Subagent: works autonomously (ReAct loop)
Subagent: returns results + agent ID
Parent: receives ToolResult with summary + agent ID
Parent: continues conversation
```

### Background Execution
```
Parent: calls Agent(run_in_background=true) → RETURNS IMMEDIATELY with task ID
Parent: continues working on other things
Subagent: works in background
Subagent: completes → parent gets NOTIFIED automatically
Parent: can check status via TaskOutput(taskId)
Parent: can kill via TaskStop(taskId)
```

### Resume
```
Parent: calls Agent(resume=previousAgentId, prompt="continue with authorization")
→ Subagent resumes with FULL PREVIOUS TRANSCRIPT preserved
→ New prompt appended to existing conversation
→ Subagent continues where it left off
```

### Key detail from docs:
> "When a subagent completes, Claude receives its agent ID. Claude uses the SendMessage tool with the agent's ID as the 'to' field to resume it."

> "If a stopped subagent receives a SendMessage, it auto-resumes in the background without requiring a new Agent invocation."

### Killing/Stopping
- `Ctrl+F` kills ALL background agents (press twice to confirm)
- `TaskStop(taskId)` kills a specific background task
- Foreground subagents can be cancelled with `Ctrl+C` (standard interrupt)

## Subagent Transcript Persistence

From the docs:
> "Subagent transcripts persist independently of the main conversation"
> "Main conversation compaction: subagent transcripts are unaffected. They're stored in separate files."
> "Session persistence: Subagent transcripts persist within their session."
> "You can resume a subagent after restarting Claude Code by resuming the same session."
> "Automatic cleanup: Transcripts are cleaned up based on the cleanupPeriodDays setting (default: 30 days)."

Storage: `~/.claude/projects/{project}/{sessionId}/subagents/agent-{agentId}.jsonl`

## What We Need to Build

### Phase 1: Resume Support
1. Persist worker transcripts to JSONL (same pattern as ConversationSession)
2. Agent ID system (UUID per worker invocation)
3. `resume` parameter on the `agent` tool
4. Reconstruct WorkerSession from saved transcript

### Phase 2: Background Execution
1. `run_in_background` parameter on `agent` tool
2. Return immediately with task/agent ID
3. Run worker in a separate coroutine
4. Notification mechanism when worker completes
5. `TaskOutput`-equivalent to check status
6. Kill mechanism for background workers

### Phase 3: SendMessage for Resume
1. `SendMessage` tool or integrate with existing resume
2. Auto-resume stopped subagents on message receipt
3. Append new prompt to existing transcript

## Architecture Implications for Our Plugin

### Current: WorkerSession is fire-and-forget
```
DelegateTaskTool/SpawnAgentTool
  → creates WorkerSession
  → calls workerSession.execute() (blocking suspend)
  → gets WorkerResult
  → returns ToolResult
  → WorkerSession is GC'd
```

### Needed: WorkerSession is persistent and resumable
```
SpawnAgentTool
  → creates WorkerSession with unique agentId
  → if foreground: execute and wait (current behavior)
  → if background: launch in separate coroutine, return immediately
  → persists transcript to {sessionDir}/subagents/agent-{id}.jsonl
  → on resume: reconstruct WorkerSession from transcript, continue

AgentService manages:
  → activeWorkers: Map<String, WorkerSession> (for background)
  → transcriptDir: per-session subagent transcript storage
  → notification callback when background worker completes
```

### Storage Location
Per the user's suggestion, store in `.idea/` or our existing session directory:
```
{systemPath}/workflow-agent/sessions/{sessionId}/subagents/
  agent-{agentId}.jsonl       # Transcript
  agent-{agentId}-meta.json   # Metadata (status, type, timestamps)
```
