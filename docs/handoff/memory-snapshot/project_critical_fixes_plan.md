---
name: Critical fixes plan — 4 CRITICAL + 8 HIGH findings
description: Expert review findings and implementation plan for multi-turn conversation, persistence, LocalHistory rollback, checkpointing, and 8 high-severity issues
type: project
---

## Expert Review Summary (2026-03-19)
Full review: docs/superpowers/research/2026-03-19-expert-architecture-review.md
Full research: docs/superpowers/research/2026-03-19-critical-fixes-research.md

## 4 CRITICAL Fixes (Implementation Order)

### 1. Multi-turn conversation (session persists across messages)
- Current: each message creates new AgentOrchestrator → agent has no memory
- Fix: AgentController keeps ONE ContextManager alive across messages
- ContextManager accumulates messages; new user message appended, not reset
- Compression handles growing context (already implemented)
- Only "New Chat" resets the session

### 2. Conversation persistence across IDE restarts
- Storage: JSONL files at PathManager.getSystemPath()/workflow-agent/sessions/
- Index: Application-level PersistentStateComponent (lightweight metadata)
- Cross-project: APP-level service, shared across all projects
- History tab: shows all sessions across all projects, sorted by date
- Format: one ConversationMessage per JSONL line, atomic writes

### 3. Rollback using LocalHistory (replaces git stash)
- Primary: LocalHistory.putSystemLabel() before every agent task
- Per-step: WriteCommandAction.withGroupId() for IDE undo integration
- label.revert(project, scope) for full rollback
- Works for new/modified/deleted files, no VCS dependency
- git stash as optional secondary layer

### 4. Checkpointing and resume
- Checkpoint after every tool execution: conversation + file hashes + iteration
- On IDE restart: detect incomplete sessions, offer resume
- On resume: validate file state, detect manual edits, continue from last phase
- Store alongside conversation JSONL

## 8 HIGH Fixes (After Critical)
1. Increase iteration cap to 50 (add user prompt at 25)
2. Simulated streaming UX for non-streaming tool calls
3. Dynamic tool injection (start with 5 core, add contextually)
4. Working set file cache (live cache of recently touched files)
5. XML tags for system instructions in user messages
6. SyntaxValidator as warning not gate
7. Command allowlist instead of blocklist
8. User intervention mid-loop (interrupt + redirect)

## Breakthrough Opportunities (save for later)
1. Workflow-aware tool chains (Jira+Bamboo+Sonar+Bitbucket)
2. PSI-powered context injection (auto-inject type hierarchy on edit)
3. Enterprise compliance audit (exportable session logs)

## Key IntelliJ APIs to use
- LocalHistory.putSystemLabel() — rollback
- WriteCommandAction.withGroupId() — step-level undo
- WolfTheProblemSolver — real-time compilation errors
- InspectionManager.defaultProcessFile() — run inspections
- PathManager.getSystemPath() — cross-project storage
- PersistentStateComponent at APP level — session index
