# Checkpoint/Revert Architecture Comparison — AI Coding Agents

**Date:** 2026-04-03
**Purpose:** Research how enterprise AI coding agents handle checkpoint/revert of file changes, focusing on UI patterns and architecture.
**Tools analyzed:** Claude Code, Cursor, Cline, Codex CLI, Aider, Windsurf, OpenHands

---

## Executive Summary

Six distinct architectural approaches exist for checkpoint/revert across the seven tools analyzed:

1. **File-backup with UUID tracking** (Claude Code) — SDK-level file backup before each tool use, no git involved
2. **Local snapshot store** (Cursor) — hidden directory with pre-change file snapshots, ephemeral per session
3. **Shadow git repository** (Cline) — separate `.git` in VS Code global storage, full commit history per task
4. **Real git commits with rollback** (Aider, Codex CLI) — actual git commits per edit, undo via `git reset`
5. **Named project snapshots** (Windsurf) — user-created named checkpoints, irreversible revert
6. **Event-sourced state** (OpenHands) — immutable event log with deterministic replay, no user-facing undo yet

**Key finding:** No tool allows the LLM itself to trigger a revert. All revert operations are user-initiated. This is an intentional safety decision across the entire industry.

---

## Detailed Comparison Table

| Dimension | Claude Code | Cursor | Cline | Aider | Codex CLI | Windsurf | OpenHands |
|---|---|---|---|---|---|---|---|
| **Storage mechanism** | File backups (SDK-managed) | Hidden local directory (custom) | Shadow git repo in VS Code global storage | Real git repo (user's repo) | Ghost git commits in user's repo | Unknown (likely file snapshots) | Event-sourced immutable log |
| **Checkpoint creation** | Automatic before each Write/Edit/NotebookEdit tool | Automatic before each Agent code edit | Automatic after each tool use (file edits, commands) | Automatic after each AI edit (git commit) | Automatic each turn (ghost_commit, experimental) | Manual (user creates named checkpoints) | Automatic (all actions appended to event log) |
| **What is tracked** | Files created + files modified + original content | Full project file state (AI changes only) | All files in workspace (including non-git-tracked) | All file changes (each gets its own commit) | Full repo snapshot (respects .gitignore) | Project state at named checkpoint | All events: commands, edits, messages, results |
| **Bash/terminal changes tracked?** | NO — only Write/Edit/NotebookEdit tools | NO — only Agent-generated changes | YES — checkpoints after command tool use too | YES — all changes committed to git | Partially — ghost commit snapshots full repo | Unknown | YES — all events recorded |
| **Revert trigger (UI)** | `Esc Esc` or `/rewind` opens scrollable picker | "Restore Checkpoint" button on chat messages, "+" hover button | Bookmark icon with Compare/Restore buttons after each tool use | `/undo` command in chat | `/undo` command | Hover arrow on prompts, table of contents | None (proposed but closed as NOT_PLANNED) |
| **Revert options** | 5: Restore code+conversation, restore conversation only, restore code only, summarize from here, cancel | 1: Restore files to checkpoint state | 3: Restore files only, restore task only, restore files+task | 1: Undo last aider commit | 1: Undo last ghost commit | 1: Revert all code changes to checkpoint state | N/A — no user-facing undo |
| **Conversation after revert** | Configurable: can rewind conversation, keep it, or summarize it | Preserved (messages stay, files revert) | Configurable: can restore task history or preserve it | Preserved (chat continues, git reverted) | Context rolled back (drops last N turns) | Preserved (chat history stays) | N/A |
| **Can LLM trigger revert?** | NO — user only | NO — user only | NO — user only | NO — user only | NO — user only | NO — user only | NO — user only |
| **Revert reversibility** | Reversible (original messages preserved in transcript) | DESTRUCTIVE — reported to permanently destroy change history, no redo | Reversible (shadow git preserves full history) | Reversible (git reflog available) | Unreliable (users report "No ghost snapshot available") | IRREVERSIBLE — explicitly documented | N/A |
| **Git independence** | YES — operates independently of git | YES — separate from git | YES — shadow git, user's git untouched | NO — uses user's actual git repo | NO — ghost commits in user's actual git repo | Unknown | YES — event log, separate from git |
| **Session persistence** | YES — checkpoints persist across sessions (30-day configurable cleanup) | NO — ephemeral, wiped after session | YES — persists per task in VS Code global storage | YES — standard git commits persist permanently | Partially — ghost commits may be GC'd | Unknown | YES — event log persistent with deterministic replay |
| **Destructive git protection** | Not built-in (community hooks like DCG exist) | Not built-in | Not built-in | Commits dirty files first to protect user work | Ghost commits are "Codex Snapshot" identity | Not built-in ("reverts are irreversible, so checkpoint often") | Sandbox isolation prevents damage to host |

---

## Architecture Deep Dives

### 1. Claude Code — File-Backup with UUID Tracking

**How it works internally:**
- When `enable_file_checkpointing=True`, the SDK creates backup copies of files *before* modifying them via Write, Edit, or NotebookEdit tools
- Each user message in the response stream includes a checkpoint UUID
- Checkpoints are session-scoped, persisted across resumes, cleaned up after 30 days
- The checkpoint system is completely independent of git

**Rewind mechanics:**
- `Esc Esc` or `/rewind` opens a scrollable list of all prompts from the session
- User selects a point, then chooses from 5 actions:
  - **Restore code and conversation** — revert both files and chat to that point
  - **Restore conversation** — rewind chat but keep current files
  - **Restore code** — revert files but keep conversation
  - **Summarize from here** — compress conversation from that point forward (context optimization)
  - **Never mind** — cancel
- After restore, the original prompt from the selected message is placed back in the input field

**SDK/API exposure:**
- Programmatic API: `rewindFiles(checkpointId)` (TypeScript) / `rewind_files(checkpoint_id)` (Python)
- CLI: `claude -p --resume <session-id> --rewind-files <checkpoint-uuid>`
- Can rewind during stream (mid-session) or after stream (by resuming session with empty prompt)
- Supports multiple restore points — each user message UUID is a potential checkpoint

**Limitations:**
- Bash command changes NOT tracked (rm, mv, cp, sed -i, echo >)
- External/manual changes not tracked
- Directory creation/deletion not undone
- File content only, not filesystem structure

**UI after revert:** Original prompt restored to input field. Conversation can be selectively preserved or rewound. Summary mode compresses without changing files on disk.

### 2. Cursor — Local Snapshot Store

**How it works internally:**
- Cursor stores checkpoints in a hidden local directory, separate from git
- Before every Agent code modification, the system "zips up the pre-change state into a checkpoint"
- Only AI Agent-generated changes are captured; manual human edits are NOT tracked
- Checkpoints are ephemeral — wiped after sessions end

**Revert mechanics:**
- "Restore Checkpoint" button appears inline in chat on previous messages
- A "+" button appears on hover over chat messages
- Restoring resets all files to that point in the conversation
- Conversation messages after the checkpoint are preserved in chat history (not removed)

**Shadow workspace (separate feature):**
- Cursor has a "shadow workspace" — a hidden background workspace where AI tests changes before presenting them
- Uses git worktrees under the hood for this separate feature
- This is for *validation*, not checkpointing

**Known issues:**
- "Restore Checkpoint" reported to permanently destroy change history (no redo available)
- Bug: "you can always undo this later" shown in UI but actually irreversible
- Formatting/indentation breaks reported after restoration
- In multi-agent scenarios (Cursor 2.0), undo from one agent may undo all agents' changes

**Limitations:**
- Manual edits not tracked
- Ephemeral — no persistence across sessions
- No diff view of what will be reverted
- No conversation rewind (only files revert)

### 3. Cline — Shadow Git Repository

**How it works internally:**
- Creates a completely separate git repository in VS Code's global storage
- Path: `%APPDATA%/Code/User/globalStorage/saoudrizwan.claude-dev/checkpoints/<workspace-id>.git`
- Uses git's `core.worktree` setting to track the actual workspace files
- Custom identity: "Cline Checkpoint" as author
- After each tool use, commits all workspace files to this shadow repo
- Handles nested `.git` directories by temporarily renaming them to `.git_disabled`

**Checkpoint UI:**
- Bookmark icon labeled "Checkpoint" appears after each tool use
- Dotted line connects to **Compare** and **Restore** buttons
- Compare opens a diff viewer in the editor showing all modifications between checkpoints
- Step diffs (between consecutive checkpoints) and task diffs (baseline to latest) available

**Revert mechanics:**
- Three restore options:
  1. **Restore Files** — revert workspace to checkpoint, keep conversation
  2. **Restore Task Only** — delete subsequent messages, keep files
  3. **Restore Files & Task** — reset both to checkpoint state
- `resetHead()` method restores files to the checkpoint commit state
- Message editing integrates: editing a prior message offers "Restore All" to revert to that checkpoint

**Known issues:**
- Excessive disk space: 4GB+ per task, 120GB+ over time reported
- Git repository corruption in large monorepos
- Nested `.git` renaming can cause issues with infinite nesting in `node_modules`
- Performance concerns for very large repositories

**Strengths:** Captures everything including non-git-tracked files. Full diff capability between any two checkpoints.

### 4. Aider — Real Git Commits

**How it works internally:**
- Every AI edit is committed to the user's actual git repository
- Commits are attributed with "(aider)" appended to git author/committer name
- Aider-authored changes: "(aider)" on both author and committer
- Aider-committed user changes (dirty files): "(aider)" on committer only
- Commit messages auto-generated using a weak model with Conventional Commits format

**Pre-edit safety:**
- Before applying AI edits, Aider commits any pre-existing uncommitted changes
- This keeps user edits separate from AI edits
- Ensures user work is never lost even if AI makes inappropriate changes

**Undo mechanics:**
- `/undo` command undoes the last git commit IF it was made by Aider
- Effectively a `git reset` on the most recent Aider-attributed commit
- `/diff` shows changes since the last message
- Standard git tools (cherry-pick, revert, reflog) all work since these are real commits
- Can disable with `--no-auto-commits`, `--no-dirty-commits`, `--no-git` (discouraged)

**Strengths:**
- No custom infrastructure — leverages git natively
- Full git history preserved — reflog, bisect, cherry-pick all work
- Clean attribution makes AI changes identifiable
- Pre-existing work always protected via auto-commit

**Limitations:**
- Pollutes actual git history with many small commits
- No conversation rewind — only file changes revert
- No multi-step rollback UI — just last commit
- Requires the project to be a git repository

### 5. Codex CLI — Ghost Git Commits

**How it works internally (Rust implementation):**
- `GhostCommit` struct contains: commit ID, optional parent commit ID, lists of preexisting untracked files/directories
- Ghost commits use identity "Codex Snapshot" / `snapshot@codex.local`
- Default commit message: "codex snapshot"
- Created each turn via `ghost_commit` feature (experimental, enabled by default recently)
- `capture_existing_untracked()` scans for untracked files (excludes git-ignored for warnings, includes for actual snapshot)
- `restore_ghost_commit_with_options()` in `undo.rs` handles restoration

**Undo mechanics:**
- `/undo` command triggers `UndoTask` which calls `codex_git::restore_ghost_commit_with_options`
- Also has `thread/rollback` function to drop last N turns from context and persist a rollback marker

**Known issues (significant):**
- Users frequently report "No ghost snapshot available to undo" errors
- Ghost commits listing every untracked file can create enormous sessions that crash Codex
- Contradictory default settings between code and documentation
- Feature request for proper rollback UI (issue #6449) was closed as "not planned"
- Ghost-commit config has backward compatibility concerns

**Limitations:**
- Experimental and unreliable
- No visual rollback picker
- No multi-step undo
- Can overwhelm session with large repo snapshots

### 6. Windsurf — Named Project Snapshots

**How it works internally:**
- User-created named snapshots of project state
- Created manually from within the conversation
- Can navigate to and revert at any time via table of contents or hover arrows

**Revert mechanics:**
- Hover over an original prompt, click revert arrow
- Reverts all code changes back to the state at the desired step
- **Reverts are explicitly irreversible** — documented as a permanent operation
- No undo of the revert itself

**Limitations:**
- Manual creation only (not automatic)
- Irreversible revert — no recovery after revert
- Internal implementation undocumented
- Previous accept/reject UI for individual changes was removed (regression reported Feb 2025)
- Strong recommendation to maintain separate Git checkpoint branches as safety net

### 7. OpenHands — Event-Sourced State (No User-Facing Undo)

**How it works internally:**
- All interactions modeled as immutable events appended to an event log
- Event-sourcing pattern enables deterministic replay
- Sandboxed Docker containers provide isolation
- If container restarts, events can be replayed to rebuild state
- `ConversationState` tracks session metadata + full interaction history

**Undo status:**
- Can undo file changes on single files, only 1 step back
- Feature request for workspace-level undo (#6163) was **closed as NOT_PLANNED**
- Proposed approaches included: Btrfs snapshots, LVM snapshots, Docker commit, `cp -r`
- Kernel-level approaches rejected due to Kubernetes incompatibility
- No user-facing undo UI exists

**Unique aspects:**
- Sandbox isolation means the host system is inherently protected from destructive operations
- Event log provides audit trail even without explicit undo
- Architecture *supports* checkpoint/restore theoretically, but no product feature ships it

---

## Cross-Cutting Analysis

### Checkpoint Creation: Automatic vs Manual

| Automatic | Manual |
|---|---|
| Claude Code (every tool use) | Windsurf (user-created named snapshots) |
| Cursor (every Agent edit) | |
| Cline (every tool use) | |
| Aider (every AI edit) | |
| Codex CLI (every turn, experimental) | |

**Recommendation for our plugin:** Automatic checkpointing is the industry standard. Every tool use that modifies files should create a checkpoint without user intervention.

### Storage: Git-Based vs Custom

| Real Git | Shadow Git | Custom File Backup | Event Log |
|---|---|---|---|
| Aider (user's repo) | Cline (separate repo) | Claude Code (SDK backups) | OpenHands (immutable events) |
| Codex CLI (ghost commits) | | Cursor (hidden directory) | |
| | | Windsurf (unknown internal) | |

**Trade-offs:**
- **Real git:** Zero infrastructure, full git tooling works, but pollutes history
- **Shadow git:** Clean user history, full diff capability, but disk space explosion (Cline: 120GB+)
- **Custom backup:** Lightweight, git-independent, but no diff between checkpoints
- **Event log:** Most architecturally sound, but complex to implement user-facing undo

### Conversation State After Revert

| Tool | Conversation Behavior |
|---|---|
| Claude Code | Configurable: rewind, keep, or summarize |
| Cursor | Preserved (messages stay, only files revert) |
| Cline | Configurable: restore task history or preserve it |
| Aider | Preserved (chat continues after git reset) |
| Codex CLI | Rolled back (drops last N turns from context) |
| Windsurf | Preserved (chat history stays) |
| OpenHands | N/A |

**Key insight:** Claude Code's "summarize from here" option is unique and powerful — it compresses verbose debugging sessions while preserving early context, serving double duty as both undo mechanism and context optimization tool.

### Destructive Git Protection

No tool has built-in protection against destructive git commands executed by the LLM. Protection comes from:
- **External hooks** (DCG — Destructive Command Guard, Claude Git Guard)
- **Sandbox isolation** (OpenHands)
- **Pre-edit safety commits** (Aider — commits dirty files before AI edits)
- **Approval modes** (Codex CLI — Auto/Read-only/Full Access)
- **User culture** ("Git checkpoint branch before any Cascade session" — Windsurf docs)

---

## Architectural Recommendations for Our Plugin

Based on this research, the recommended approach for our IntelliJ plugin agent:

### 1. Storage: Shadow Git Repository (Cline-inspired, with improvements)

**Why shadow git over alternatives:**
- Full diff capability between any two checkpoints (critical for IntelliJ diff UI integration)
- Captures all file changes including non-git-tracked files
- User's git history stays clean
- Git infrastructure already exists in IntelliJ (JGit via VCS APIs)

**Improvements over Cline:**
- Use `.gitignore`-aware exclusions to prevent disk space explosion
- Cap maximum checkpoint storage per session (configurable)
- Periodic garbage collection of old shadow repos
- Store under `~/.workflow-orchestrator/{ProjectName-hash}/agent/checkpoints/` (consistent with existing storage)

### 2. Checkpoint Creation: Automatic + Granular

- Create checkpoint before each file-modifying tool execution (`edit_file`, `create_file`, `format_code`, etc.)
- Also checkpoint before meta-tool write actions (`jira.transition`, `bamboo_builds.trigger_build`)
- Each checkpoint gets a UUID linked to the conversation turn

### 3. Revert UI: Chat-Integrated (Claude Code-inspired)

- Checkpoint markers in chat after each tool use (similar to Cline's bookmark icons)
- Click to open revert picker with options:
  - **Restore files only** — revert files, keep conversation
  - **Restore files and conversation** — revert both
  - **Compare** — open IntelliJ diff viewer for all changed files
- Use IntelliJ's native diff infrastructure for comparison views

### 4. Conversation Handling: Configurable (Claude Code-inspired)

- Support "summarize from here" for context optimization
- Support conversation rewind for full undo
- Support files-only revert for iterative approaches

### 5. Destructive Git Protection: Built-in

- Pre-tool-use hook that blocks `git reset --hard`, `git push --force`, `git clean -f`
- Implemented as execution guard in the tool system (consistent with plan mode enforcement pattern)
- Pre-edit safety: auto-commit dirty files before AI modifications (Aider pattern)

### 6. LLM Revert: User-Only (Industry Consensus)

- The LLM should NOT be able to trigger revert — this is universal across all tools
- Revert is always a user-initiated action
- The LLM CAN suggest that the user should revert (via assistant message)

---

## Sources

### Claude Code
- [Checkpointing - Claude Code Docs](https://code.claude.com/docs/en/checkpointing)
- [Rewind file changes with checkpointing - Claude API Docs](https://platform.claude.com/docs/en/agent-sdk/file-checkpointing)
- [FEATURE: Expose checkpoint restore/rewind in headless Claude Code](https://github.com/anthropics/claude-code/issues/16976)
- [Claude Code Checkpoints | Hacker News](https://news.ycombinator.com/item?id=45050090)

### Cursor
- [Checkpoints | Cursor Docs](https://cursor.com/docs/agent/chat/checkpoints)
- [Understanding Cursor Checkpoints - Steve Kinney](https://stevekinney.com/courses/ai-development/cursor-checkpoints)
- [Restore Checkpoint permanently destroys change history](https://forum.cursor.com/t/restore-checkpoint-permanently-destroys-change-history/129652)
- [Cursor's Shadow Workspace Reverse-Engineered](https://personaldevblog.web.app/en/blog/cursor-shadow-workspace-en)
- [In 2.0 undo checkpoint is not agent-independent](https://forum.cursor.com/t/in-2-0-undo-checkpoint-is-not-agent-independent/139630)

### Cline
- [Checkpoints - Cline Docs](https://docs.cline.bot/core-workflows/checkpoints)
- [Checkpoint System | DeepWiki](https://deepwiki.com/hyhfish/cline/7.1-checkpoint-system)
- [Cline's Backroom Git: The Secret History of "View Changes"](https://dev.to/alex_buzunov_4d5851f839cc/clines-backroom-git-the-secret-history-of-view-changes-1ma1)
- [The Shadow Git Behind Cline - Medium](https://medium.com/codex/clines-backroom-git-the-secret-history-of-view-changes-8523c7c6437f)
- [Fix Checkpoint System Issues - GitHub](https://github.com/cline/cline/issues/4388)
- [Checkpoint feature corrupts git repo in large monorepo](https://github.com/cline/cline/issues/9590)

### Aider
- [Git integration | aider](https://aider.chat/docs/git.html)
- [Usage | aider](https://aider.chat/docs/usage.html)
- [Feature request: /undo should do something useful](https://github.com/paul-gauthier/aider/issues/76)

### Codex CLI
- [Features - Codex CLI](https://developers.openai.com/codex/cli/features)
- [Code and context rollback - GitHub Issue](https://github.com/openai/codex/issues/6449)
- [/undo command not working - GitHub Issue](https://github.com/openai/codex/issues/6074)
- [ghost_commit source code (ghost_commits.rs)](https://fossies.org/linux/codex-rust/codex-rs/utils/git/src/ghost_commits.rs)
- [ghost_commit creates large session that kills codex](https://github.com/openai/codex/issues/7395)
- [ghost_commit config documentation update](https://github.com/openai/codex/issues/7966)

### Windsurf
- [Windsurf - Cascade Docs](https://docs.windsurf.com/windsurf/cascade/cascade)
- [Loss of Accept/Reject Controls regression](https://github.com/Exafunction/codeium/issues/131)

### OpenHands
- [Workspace Undo feature request (closed NOT_PLANNED)](https://github.com/All-Hands-AI/OpenHands/issues/6163)
- [OpenHands Software Agent SDK paper](https://arxiv.org/html/2511.03690v1)
- [Docker Sandbox - OpenHands Docs](https://docs.openhands.dev/sdk/guides/agent-server/docker-sandbox)

### Cross-Cutting
- [Checkpoint/Restore Systems in AI Agents - eunomia](https://eunomia.dev/blog/2025/05/11/checkpointrestore-systems-evolution-techniques-and-applications-in-ai-agents/)
- [Destructive Command Guard (DCG)](https://github.com/Dicklesworthstone/destructive_command_guard)
- [diffback - AI agent undo tool](https://github.com/A386official/diffback)
