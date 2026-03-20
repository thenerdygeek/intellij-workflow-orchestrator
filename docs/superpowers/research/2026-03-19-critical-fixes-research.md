# Critical Fixes Research — 5 Topics for Agentic AI Plugin

**Date:** 2026-03-19
**Scope:** Multi-turn conversation, persistence, git rollback, checkpointing, IntelliJ Ultimate APIs

---

## Topic 1: Multi-Turn Conversation in Production AI Agents

### How the Anthropic Messages API Works (Foundational)

The Claude Messages API is **stateless per request**. There is no server-side session — the client must send the **entire conversation history** with every API call.

```
POST /v1/messages
{
  "model": "claude-opus-4-6",
  "system": "You are a helpful assistant.",
  "messages": [
    { "role": "user", "content": "Hello" },
    { "role": "assistant", "content": "Hi there!" },
    { "role": "user", "content": "Follow-up question..." }
  ],
  "tools": [...]
}
```

**Key facts:**
- Maximum 100,000 messages in a single request
- Messages must alternate user/assistant (consecutive same-role messages are auto-merged)
- System prompt is a top-level parameter, NOT a message role
- Tool results are `tool_result` content blocks inside a `user` message
- The client is 100% responsible for maintaining conversation state

### Tool Use Loop Structure

A single "turn" involves multiple API round-trips when tools are used:

```
User message
  → API call → Claude responds with [thinking] + [tool_use]
  → Client executes tool
  → API call with: original messages + assistant [tool_use] + user [tool_result]
  → Claude responds with [thinking] + [text] OR more [tool_use]
  → ... repeat until Claude returns stop_reason="end_turn"
```

**Critical:** Each API call in the tool loop sends the FULL conversation history including all prior tool_use/tool_result pairs. The context grows with every round-trip.

### How Claude Code Manages Conversation State

Claude Code (this tool) operates as follows:
- **One session per conversation** — messages accumulate in memory
- **Full history sent with every API call** — no server-side state
- **Tool call results appended** — each tool_use and tool_result pair grows the context
- **Prompt caching** — stable prefix (system prompt + early messages) is cached server-side
- **Session interruption** — if the process dies, conversation state is lost unless persisted externally
- **No explicit summarization** — relies on large context window (200K+ tokens)

### How Cline Manages Conversation State

Cline (VS Code extension) uses a **task-based architecture**:
- Each user task gets a unique task ID and directory
- Maintains two parallel histories:
  - `api_conversation_history.json` — the raw `MessageParam[]` sent to the API
  - `ui_messages.json` — user-facing formatted messages
- **Full history sent every API call** — same as Claude Code
- **Atomic writes** — uses temp-file-then-rename pattern to prevent corruption
- **No sliding window or truncation** observed — relies on large context windows
- Task metadata tracks token usage per task

### How Continue.dev Manages Conversation State

Continue.dev uses a more sophisticated approach:
- **`compileChatMessages()`** — compiles and truncates messages before sending
- **Token budget:** reserves 75% of context for input, 25% for output
- **`pruneRawPromptFromTop()`** — removes oldest messages when context is too large
- **Session persistence** via `HistoryManager`:
  - `historyManager.save(session)` / `historyManager.load(id)`
  - Supports both local and remote (cloud) storage
  - `compactConversation()` — compresses old messages
- **Default limit:** 100 sessions stored

### How Cursor/Copilot Handle Context Growth

Based on observed behavior and public documentation:
- **Cursor:** Uses a sliding window with summarization of older context. Sends summarized history + recent messages. Has a "long context" mode that sends more history at higher cost.
- **GitHub Copilot Chat:** Creates a new API session per message but includes recent conversation turns. Uses a fixed window of recent messages (typically last 10-20 turns). Appends workspace context and file context per-message.

### Prompt Caching for Multi-Turn Conversations

Anthropic's prompt caching is critical for efficient multi-turn conversations:

```python
response = client.messages.create(
    model="claude-opus-4-6",
    max_tokens=16000,
    cache_control={"type": "ephemeral"},  # Automatic caching
    system="You are a helpful assistant.",
    messages=[...full_conversation_history...]
)
```

**How it works for multi-turn:**

| Request | Cache Behavior |
|---------|----------------|
| Request 1 | System + User(1) + Asst(1) + User(2) → All written to cache |
| Request 2 | System through User(2) → Read from cache; Asst(2) + User(3) → Written |
| Request 3 | System through User(3) → Read from cache; Asst(3) + User(4) → Written |

**Pricing impact:**
- Cache reads: 0.1x base price (90% savings)
- Cache writes: 1.25x base price (5-minute TTL) or 2x (1-hour TTL)
- Minimum cacheable: 4,096 tokens for Opus/Haiku, 2,048 for Sonnet

**Cache invalidation triggers:** tool definition changes, thinking budget changes, image additions/removals. Safe: `tool_choice` changes, new conversation turns.

### Extended Thinking with Tool Use

When using extended thinking in agentic loops:
- **Must pass thinking blocks back** in the assistant message when continuing tool loops
- Thinking blocks from **previous user turns** are auto-removed from context (but still count as cached input tokens)
- `budget_tokens` can exceed `max_tokens` when interleaved thinking is enabled
- Use `display: "omitted"` for latency-sensitive pipelines (returns encrypted signature instead of thinking text)

### Recommended Approach for Our Plugin

```
Architecture: Client-managed conversation state with two-tier compression

1. CONVERSATION BUFFER (in-memory)
   - Full MessageParam[] array
   - Grows with every user message and tool result
   - Sent to API with prompt caching enabled

2. COMPRESSION TRIGGER
   - When token count exceeds 60% of context window:
     a. Summarize older tool results (keep tool_use IDs, compress content)
     b. Summarize older assistant responses (keep key decisions)
     c. Never compress: system prompt, last 5 user turns, active tool loop

3. PROMPT CACHING
   - Use automatic caching (cache_control at request level)
   - System prompt + tools cached across all requests
   - Conversation prefix cached and extended each turn
   - Use 1-hour TTL for agent tasks (often >5 min between interactions)

4. SESSION PERSISTENCE
   - Serialize full conversation to disk after each turn
   - On IDE restart: reload and continue
```

---

## Topic 2: Conversation Persistence Across IDE Restarts

### IntelliJ Platform Storage APIs

#### PersistentStateComponent (XML-based)

The standard approach for structured plugin state:

```kotlin
@State(
    name = "AgentConversationState",
    storages = [Storage("workflowAgentConversations.xml")]
)
class ConversationStateService : PersistentStateComponent<ConversationState> {
    override fun getState(): ConversationState = state
    override fun loadState(state: ConversationState) { this.state = state }
}
```

**Storage annotations:**
- `@Storage("file.xml")` — project-level storage (in `.idea/`)
- `@Storage(StoragePathMacros.WORKSPACE_FILE)` — workspace-specific (not shared via VCS)
- `@Storage(StoragePathMacros.CACHE_FILE)` — for cached/reconstructable data
- `roamingType = RoamingType.DISABLED` — never sync to cloud

**Limitations for conversation storage:**
- XML serialization is verbose for large conversation histories
- Not designed for append-heavy workloads
- 10MB+ XML files will slow IDE startup
- No built-in rotation or cleanup

#### SimplePersistentStateComponent (Kotlin-friendly)

```kotlin
class ConversationSettings : BaseState() {
    var maxHistorySize by property(100)
    var persistHistory by property(true)
    var compressionThreshold by property(50000) // tokens
}
```

Uses property delegates with automatic modification tracking.

#### PropertiesComponent (Simple key-value)

```kotlin
// Application-level
PropertiesComponent.getInstance().setValue("agent.lastSessionId", sessionId)

// Project-level
PropertiesComponent.getInstance(project).setValue("agent.activeTaskId", taskId)
```

Good for: session IDs, flags, small metadata. Bad for: conversation history.

### How Cline Persists Conversations

Cline uses **file-based persistence** (NOT a database):

```
~/.cline/tasks/
  └── {taskId}/
      ├── api_conversation_history.json   # MessageParam[] for API
      ├── ui_messages.json                # ClineMessage[] for display
      └── task_metadata.json              # Token usage, model info
```

**Key design decisions:**
- One directory per task (natural isolation)
- JSON format (human-readable, debuggable)
- Atomic writes via temp-file-then-rename
- Separate API history from UI history (different consumers)
- Metadata separate from conversation (lightweight reads for list views)

### How Continue.dev Persists Conversations

Continue.dev uses a **HistoryManager** with local + optional remote storage:

```typescript
// Local persistence
historyManager.save(session)      // Writes to local storage
historyManager.load(sessionId)    // Loads by ID
historyManager.list({ limit: 100, offset: 0 }) // Paginated list
historyManager.clearAll()         // Cleanup

// Remote (optional, cloud)
controlPlaneClient.loadRemoteSession(remoteId)
```

Sessions are sorted by `dateCreated` descending. The system supports "compacting" conversations — `compactConversation(sessionId, index, historyManager, currentModel)` — which likely summarizes older turns.

### Recommended Approach: JSONL Files + PersistentStateComponent Index

```
Storage Architecture:

1. INDEX (PersistentStateComponent — XML in .idea/)
   @State(name = "AgentSessionIndex", storages = [Storage("workflowAgentSessions.xml")])
   class AgentSessionIndex : PersistentStateComponent<SessionIndexState> {
       // Lightweight metadata only
       data class SessionEntry(
           val sessionId: String,
           val projectName: String,
           val createdAt: Long,
           val lastMessageAt: Long,
           val messageCount: Int,
           val tokenCount: Int,
           val title: String  // First user message or summary
       )
   }

2. CONVERSATION FILES (JSONL in application data directory)
   Location: PathManager.getSystemPath() + "/workflow-agent/sessions/"

   {sessionId}/
   ├── messages.jsonl          # One JSON object per line (append-friendly)
   ├── tool-results.jsonl      # Large tool outputs stored separately
   └── metadata.json           # Model, token counts, timestamps

   JSONL format (one line per message):
   {"role":"user","content":"Fix the NPE in UserService","ts":1710806400}
   {"role":"assistant","content":[{"type":"text","text":"I'll look..."}],"ts":1710806401}
   {"role":"assistant","content":[{"type":"tool_use","id":"t1","name":"read_file",...}],"ts":1710806402}
   {"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"..."}],"ts":1710806403}

3. CLEANUP POLICY
   - Keep last 50 sessions per project
   - Delete sessions older than 30 days
   - Compress tool results older than 7 days (keep summary only)
   - Run cleanup on IDE startup (background coroutine)
```

**Why JSONL over SQLite:**
- JSONL is append-only (crash-safe — partial writes lose only last line)
- No driver dependency (kotlinx.serialization handles everything)
- Human-readable (developers can inspect/debug)
- Natural per-session isolation (one file per conversation)
- SQLite would be overkill for sequential reads/writes

**Why JSONL over JSON:**
- Append without parsing entire file
- Streaming reads for large conversations
- Partial reads (resume from line N)

**Why PathManager.getSystemPath() over .idea/:**
- Conversations are user-specific, not project-shared
- Large files in .idea/ slow VCS operations
- System path persists across project reimports

### Cross-Project History

Store session index at application level, conversation files in a shared location:

```kotlin
// Application-level service (not project-level)
@Service(Service.Level.APP)
@State(
    name = "WorkflowAgentGlobalIndex",
    storages = [Storage("workflowAgentGlobalIndex.xml", roamingType = RoamingType.DISABLED)]
)
class GlobalSessionIndexService : PersistentStateComponent<GlobalSessionIndex>
```

### Serialization with kotlinx.serialization

```kotlin
@Serializable
data class ConversationMessage(
    val role: String,                    // "user" or "assistant"
    val content: List<ContentBlock>,     // Text, tool_use, tool_result
    val timestamp: Long,
    val tokenCount: Int? = null,
    val checkpointId: String? = null     // For rollback support
)

@Serializable
sealed class ContentBlock {
    @Serializable @SerialName("text")
    data class Text(val text: String) : ContentBlock()

    @Serializable @SerialName("tool_use")
    data class ToolUse(val id: String, val name: String, val input: JsonObject) : ContentBlock()

    @Serializable @SerialName("tool_result")
    data class ToolResult(val toolUseId: String, val content: String) : ContentBlock()
}

// JSONL writer
fun appendMessage(sessionDir: Path, message: ConversationMessage) {
    val json = Json.encodeToString(message)
    sessionDir.resolve("messages.jsonl").appendText(json + "\n")
}

// JSONL reader (streaming)
fun readMessages(sessionDir: Path): Sequence<ConversationMessage> {
    return sessionDir.resolve("messages.jsonl")
        .bufferedReader()
        .lineSequence()
        .filter { it.isNotBlank() }
        .map { Json.decodeFromString<ConversationMessage>(it) }
}
```

---

## Topic 3: Git Rollback in IntelliJ — Available APIs

### LocalHistory API (Built-in File Versioning)

IntelliJ maintains its own local history independent of VCS. This is the **best API for agent rollback**.

```kotlin
import com.intellij.history.LocalHistory
import com.intellij.history.Label

// Create a restore point BEFORE agent starts modifying files
val label: Label = LocalHistory.getInstance().putSystemLabel(
    project,
    "Before Agent Task: Fix NPE in UserService"
)

// ... agent modifies files ...

// Rollback ALL changes since the label for a specific file
label.revert(project, virtualFile)

// Rollback ALL changes since the label for a directory (recursive)
label.revert(project, projectBaseDir)
```

**`LocalHistory` methods:**

| Method | Signature | Purpose |
|--------|-----------|---------|
| `putSystemLabel` | `(Project, String): Label` | Create named restore point |
| `putSystemLabel` | `(Project, String, Int): Label` | Create labeled restore point with color |
| `putUserLabel` | `(Project, String): Label` | User-visible label (Internal API) |
| `putEventLabel` | `(Project, String, ActivityId): Label` | Event-correlated label |
| `startAction` | `(String?): LocalHistoryAction` | Group multiple file changes |
| `startAction` | `(String?, ActivityId?): LocalHistoryAction` | Group with activity tracking |
| `getByteContent` | `(VirtualFile, FileRevisionTimestampComparator): ByteArray?` | Get historical content |
| `isUnderControl` | `(VirtualFile): Boolean` | Check if file is tracked |

**`Label` methods:**

| Method | Signature | Purpose |
|--------|-----------|---------|
| `revert` | `(Project, VirtualFile): void` | Revert file/dir to this label state |
| `getByteContent` | `(String): ByteContent` | Get content at this label |

**Critical implementation pattern for agent rollback:**

```kotlin
class AgentRollbackManager(private val project: Project) {
    private val checkpoints = mutableMapOf<String, Label>()

    fun createCheckpoint(taskId: String, description: String): String {
        val checkpointId = UUID.randomUUID().toString()
        val label = LocalHistory.getInstance().putSystemLabel(
            project,
            "Agent Checkpoint [$taskId]: $description"
        )
        checkpoints[checkpointId] = label
        return checkpointId
    }

    fun rollback(checkpointId: String, scope: VirtualFile) {
        val label = checkpoints[checkpointId]
            ?: throw IllegalStateException("Checkpoint not found: $checkpointId")

        // Must run on EDT within write action
        WriteCommandAction.runWriteCommandAction(project) {
            label.revert(project, scope)
        }
    }
}
```

### WriteCommandAction + UndoManager (IDE-Level Undo)

For fine-grained undo of individual edits within the IDE's undo system:

```kotlin
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager

// Group multiple file edits into ONE undoable action
WriteCommandAction.writeCommandAction(project)
    .withName("Agent: Apply Fix")
    .withGroupId("agent-task-$taskId")  // Groups consecutive commands
    .withGlobalUndo()                    // Undo across all editors
    .run(ThrowableRunnable {
        // All PSI/document modifications here
        document1.replaceString(start, end, newText)
        document2.insertString(offset, insertion)
    })

// Check if undo is available
val undoManager = UndoManager.getInstance(project)
val editor = FileEditorManager.getInstance(project).selectedEditor
if (undoManager.isUndoAvailable(editor)) {
    undoManager.undo(editor)  // Must be called on EDT
}
```

**`UndoManager` methods:**

| Method | Signature | Purpose |
|--------|-----------|---------|
| `getInstance` | `(Project): UndoManager` | Get project-level instance |
| `undo` | `(FileEditor?): void` | Perform undo (EDT only) |
| `redo` | `(FileEditor?): void` | Perform redo (EDT only) |
| `isUndoAvailable` | `(FileEditor?): Boolean` | Check undo availability |
| `isRedoAvailable` | `(FileEditor?): Boolean` | Check redo availability |
| `getUndoActionNameAndDescription` | `(FileEditor?): Pair<String, String>` | Get undo action info |
| `isUndoInProgress` | `(): Boolean` | Check if undo is running |
| `undoableActionPerformed` | `(UndoableAction): void` | Register custom undoable |
| `nonundoableActionPerformed` | `(DocumentReference, Boolean): void` | Mark as non-undoable |

**`WriteCommandAction.Builder` methods:**

| Method | Signature | Purpose |
|--------|-----------|---------|
| `withName` | `(String?): Builder` | Undo/redo action name |
| `withGroupId` | `(String?): Builder` | Group consecutive commands |
| `withGlobalUndo` | `(): Builder` | Undo across all editors |
| `withUndoConfirmationPolicy` | `(UndoConfirmationPolicy): Builder` | Control undo confirmation |
| `run` | `(ThrowableRunnable<E>): void` | Execute write action |
| `compute` | `(ThrowableComputable<R, E>): R` | Execute and return value |

**`UndoConfirmationPolicy` enum:**
- `DEFAULT` — standard behavior
- `REQUEST_CONFIRMATION` — always ask user before undo
- `DO_NOT_REQUEST_CONFIRMATION` — never ask

### ChangeListManager (VCS-Level Change Tracking)

```kotlin
import com.intellij.openapi.vcs.changes.ChangeListManager

val clm = ChangeListManager.getInstance(project)

// Get all uncommitted changes
val allChanges: Collection<Change> = clm.allChanges

// Get changes for a specific file
val change: Change? = clm.getChange(virtualFile)

// Get affected files
val affectedFiles: List<VirtualFile> = clm.affectedFiles

// Check file status
val status: FileStatus = clm.getStatus(virtualFile)

// Listen for change events
clm.addChangeListListener(object : ChangeListListener {
    override fun changeListChanged(list: ChangeList) { /* ... */ }
}, parentDisposable)
```

### Git-Specific Operations

```kotlin
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.repo.GitRepositoryManager

// Get git repository
val repoManager = GitRepositoryManager.getInstance(project)
val repo = repoManager.repositories.firstOrNull() ?: return

// Git stash (save current state)
val git = Git.getInstance()
git.stashSave(repo, "Agent checkpoint: $description")

// Git stash pop (restore)
git.stashPop(repo)

// Create lightweight tag as checkpoint
git.runCommand {
    GitCommand.TAG
    addParameters("agent-checkpoint-$taskId")
}

// Reset to checkpoint
git.runCommand {
    GitCommand.RESET
    addParameters("--hard", "agent-checkpoint-$taskId")
}
```

### Comparison of Rollback Approaches

| Approach | Granularity | Scope | Persists Restart? | VCS Required? | Best For |
|----------|------------|-------|-------------------|---------------|----------|
| `LocalHistory.putSystemLabel()` | All file changes | Per-file or directory | Yes (limited time) | No | **Agent rollback (primary)** |
| `WriteCommandAction` + `UndoManager` | Per-command | Per-editor or global | No (session only) | No | Single-edit undo |
| `git stash` | Working tree | Entire repo | Yes | Yes | Saving full state |
| `git tag` + `git reset` | Commit-level | Entire repo | Yes | Yes | Committed checkpoints |
| `git worktree` | Branch-level | Isolated copy | Yes | Yes | Parallel work |

### Recommended Approach: Layered Rollback

```
LAYER 1: LocalHistory Labels (always available, no VCS dependency)
  - putSystemLabel() before EVERY agent task
  - label.revert() for full rollback
  - Works for new/modified/deleted files

LAYER 2: WriteCommandAction groups (for IDE undo integration)
  - Group all edits per agent step into one command
  - withGroupId("agent-step-N") for step-level undo
  - withGlobalUndo() so undo works from any editor

LAYER 3: Git stash/tag (optional, for committed state)
  - git stash before destructive operations
  - Lightweight tags for named checkpoints
  - Only when user explicitly requests VCS-level rollback

Implementation:
  Before agent task starts:
    1. label = LocalHistory.putSystemLabel(project, "Before: $taskDescription")
    2. git.stashSave(repo, "agent-backup-$taskId")  // optional

  Each agent step:
    3. WriteCommandAction.withGroupId("agent-$taskId-step-$N").run { ... }

  On rollback request:
    4. label.revert(project, projectBaseDir)  // nuclear option
    -- OR --
    5. UndoManager.undo(editor)  // step-by-step undo
```

---

## Topic 4: Checkpointing/Resume Patterns for Long-Running Agent Tasks

### The Checkpointing Problem

An agent task can span many minutes (or longer) and involves:
1. Growing conversation history
2. Multiple file modifications
3. Tool execution state (which files were read, what was analyzed)
4. Pending decisions (waiting for user input or external services)

If the IDE crashes, process dies, or user restarts — all state is lost unless checkpointed.

### What to Save vs. Reconstruct

| Data | Save? | Rationale |
|------|-------|-----------|
| Conversation history (MessageParam[]) | **YES** | Cannot be reconstructed. This IS the agent state. |
| System prompt + tools | **NO** | Reconstructed from plugin config at startup |
| File modifications (diffs) | **YES** | Needed for rollback and audit |
| LocalHistory label reference | **NO** | Labels survive IDE restart natively |
| Current step/phase | **YES** | Know where to resume |
| Tool execution results | **YES** | Avoid re-executing expensive tools |
| Token counts | **YES** | Needed for compression decisions |
| User preferences for this task | **YES** | Auto-approve settings, etc. |
| PSI state / code analysis | **NO** | Reconstructed from files on restart |
| Active file editors | **NO** | IDE manages this |

### Serializable Agent State

```kotlin
@Serializable
data class AgentCheckpoint(
    val version: Int = 1,
    val taskId: String,
    val sessionId: String,
    val createdAt: Long,

    // Conversation state (the core of the checkpoint)
    val messages: List<ConversationMessage>,
    val systemPrompt: String? = null,  // Only if customized
    val tokenCount: Int,

    // Task progress
    val currentPhase: AgentPhase,
    val completedSteps: List<StepRecord>,
    val pendingAction: PendingAction? = null,

    // File modification tracking
    val modifiedFiles: List<FileModification>,
    val localHistoryLabelName: String,  // For rollback reference

    // Tool state
    val toolResultCache: Map<String, String>,  // toolCallId -> compressed result

    // Compression metadata
    val compressionApplied: Boolean = false,
    val originalTokenCount: Int? = null
)

@Serializable
data class FileModification(
    val filePath: String,
    val action: ModAction,  // CREATED, MODIFIED, DELETED
    val beforeHash: String?,
    val afterHash: String?,
    val stepIndex: Int
)

@Serializable
enum class AgentPhase {
    ANALYZING,        // Reading code, understanding task
    PLANNING,         // Deciding what to do
    EXECUTING,        // Making changes
    VERIFYING,        // Running tests, checking results
    AWAITING_INPUT,   // Waiting for user decision
    COMPLETED,
    FAILED
}

@Serializable
data class PendingAction(
    val type: PendingActionType,
    val description: String,
    val options: List<String>? = null,
    val context: JsonObject? = null
)
```

### Checkpoint Timing Strategy

```
WHEN to checkpoint:

1. After every USER message processed
   - Conversation state has changed
   - User might close IDE right after

2. After every TOOL execution
   - Tool results are expensive to reproduce
   - File modifications may have happened

3. Before DESTRUCTIVE operations
   - File deletions, large refactors
   - Allows clean rollback

4. On PHASE transitions
   - ANALYZING → PLANNING → EXECUTING → VERIFYING
   - Natural pause points

5. On IDLE detection
   - If agent is waiting (for build, for user, etc.)
   - Checkpoint the wait state

DO NOT checkpoint:
   - Mid-API-call (response streaming)
   - During file write operations (inconsistent state)
   - Every second (too expensive)
```

### Resume Strategy

```kotlin
class AgentResumeManager(private val project: Project) {

    suspend fun resumeFromCheckpoint(checkpoint: AgentCheckpoint): ResumeResult {
        // 1. Validate file state matches checkpoint
        val fileStateValid = validateFileState(checkpoint.modifiedFiles)

        if (!fileStateValid) {
            // Files changed since checkpoint — user may have edited manually
            return ResumeResult.ConflictDetected(
                checkpoint = checkpoint,
                conflicts = detectConflicts(checkpoint)
            )
        }

        // 2. Reconstruct conversation for API
        val messages = checkpoint.messages.map { it.toMessageParam() }

        // 3. Resume based on phase
        return when (checkpoint.currentPhase) {
            AgentPhase.ANALYZING, AgentPhase.PLANNING -> {
                // Safe to re-run from this point
                ResumeResult.ContinueFromPhase(checkpoint.currentPhase, messages)
            }
            AgentPhase.EXECUTING -> {
                // Was mid-execution — check which steps completed
                val lastCompleted = checkpoint.completedSteps.lastOrNull()
                ResumeResult.ContinueFromStep(lastCompleted?.index ?: 0, messages)
            }
            AgentPhase.AWAITING_INPUT -> {
                // Re-present the pending question to the user
                ResumeResult.AwaitingInput(checkpoint.pendingAction!!, messages)
            }
            AgentPhase.VERIFYING -> {
                // Re-run verification (tests may have changed)
                ResumeResult.RerunVerification(messages)
            }
            else -> ResumeResult.CannotResume("Task was in terminal state")
        }
    }

    private fun validateFileState(modifications: List<FileModification>): Boolean {
        return modifications.all { mod ->
            val file = LocalFileSystem.getInstance().findFileByPath(mod.filePath)
            when (mod.action) {
                ModAction.CREATED -> file != null && file.contentHash() == mod.afterHash
                ModAction.MODIFIED -> file != null && file.contentHash() == mod.afterHash
                ModAction.DELETED -> file == null
            }
        }
    }
}
```

### IntelliJ Background Task Integration

For long-running agent tasks that need to survive IDE interactions:

```kotlin
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task

// Backgroundable task with progress
object : Task.Backgroundable(project, "Agent: $taskDescription", true) {
    override fun run(indicator: ProgressIndicator) {
        indicator.text = "Analyzing code..."
        indicator.fraction = 0.1

        // Agent work here — checkpoint at each step
        for ((index, step) in steps.withIndex()) {
            indicator.checkCanceled()  // Throws if user cancels
            indicator.text = step.description
            indicator.fraction = index.toDouble() / steps.size

            executeStep(step)
            checkpoint(taskId, step)
        }
    }

    override fun onCancel() {
        // Save checkpoint before cancellation completes
        saveCheckpoint(taskId, AgentPhase.AWAITING_INPUT)
    }
}

// Coroutine-based alternative (preferred for our plugin)
val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
scope.launch {
    try {
        runAgent(taskId)
    } catch (e: CancellationException) {
        saveCheckpoint(taskId, AgentPhase.AWAITING_INPUT)
        throw e
    }
}
```

### Recommended Checkpoint Architecture

```
┌─────────────────────────────────┐
│         AgentTaskRunner         │
│  - Manages one agent execution  │
│  - Creates checkpoints          │
│  - Handles cancellation         │
└────────────┬────────────────────┘
             │
             ▼
┌─────────────────────────────────┐
│      CheckpointManager          │
│  - Serializes AgentCheckpoint   │
│  - Writes to JSONL file         │
│  - Rotates old checkpoints      │
│  - Validates on resume          │
└────────────┬────────────────────┘
             │
             ▼
┌─────────────────────────────────┐
│    PathManager.getSystemPath()  │
│    /workflow-agent/             │
│      sessions/{sessionId}/     │
│        messages.jsonl           │
│        checkpoints/             │
│          cp-001.json            │  ← After step 1
│          cp-002.json            │  ← After step 2
│          latest.json            │  ← Always points to latest
│        metadata.json            │
└─────────────────────────────────┘

On IDE startup:
  1. Scan for sessions with phase != COMPLETED/FAILED
  2. Show notification: "Agent task interrupted. Resume?"
  3. User clicks Resume → load latest.json → resumeFromCheckpoint()
  4. User clicks Discard → rollback via LocalHistory label
```

---

## Topic 5: IntelliJ Ultimate APIs We Might Be Missing

### DataContext and DataManager (Rich IDE Context)

The `DataContext` system provides typed access to the current IDE state. Essential for an agent that needs to understand what the user is looking at.

```kotlin
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.actionSystem.DataManager

// From an AnAction
override fun actionPerformed(e: AnActionEvent) {
    val project = e.getData(CommonDataKeys.PROJECT)
    val editor = e.getData(CommonDataKeys.EDITOR)
    val psiFile = e.getData(CommonDataKeys.PSI_FILE)
    val psiElement = e.getData(CommonDataKeys.PSI_ELEMENT)
    val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
    val caret = e.getData(CommonDataKeys.CARET)
    val navigatable = e.getData(CommonDataKeys.NAVIGATABLE)
}

// From any component
val dataContext = DataManager.getInstance().getDataContext(component)
val editor = CommonDataKeys.EDITOR.getData(dataContext)
```

**Key DataKeys for agent context:**

| DataKey | Class | Provides |
|---------|-------|----------|
| `CommonDataKeys.PROJECT` | `Project` | Current project |
| `CommonDataKeys.EDITOR` | `Editor` | Active editor |
| `CommonDataKeys.PSI_FILE` | `PsiFile` | PSI tree of current file |
| `CommonDataKeys.PSI_ELEMENT` | `PsiElement` | Element at caret |
| `CommonDataKeys.VIRTUAL_FILE` | `VirtualFile` | Current file |
| `CommonDataKeys.VIRTUAL_FILE_ARRAY` | `VirtualFile[]` | Selected files |
| `CommonDataKeys.CARET` | `Caret` | Caret position |
| `CommonDataKeys.HOST_EDITOR` | `Editor` | Outer editor (for injections) |
| `CommonDataKeys.NAVIGATABLE` | `Navigatable` | Navigation target |
| `LangDataKeys.MODULE` | `Module` | Current module |
| `LangDataKeys.MODULE_CONTEXT` | `Module` | Module for context actions |
| `LangDataKeys.PSI_ELEMENT_ARRAY` | `PsiElement[]` | Selected PSI elements |
| `PlatformDataKeys.TOOL_WINDOW` | `ToolWindow` | Active tool window |
| `PlatformDataKeys.FILE_EDITOR` | `FileEditor` | Active file editor |
| `PlatformDataKeys.SELECTED_ITEMS` | `Object[]` | Selected items in lists/trees |
| `PlatformDataKeys.CONTEXT_COMPONENT` | `Component` | Component with focus |

### InspectionManager and ProblemsView (Real-Time Diagnostics)

Access compilation errors, warnings, and inspection results for the agent to react to.

```kotlin
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor

val inspectionManager = InspectionManager.getInstance(project)

// Run a specific inspection on a file
val problems: List<ProblemDescriptor> = inspectionManager.defaultProcessFile(
    inspectionTool,
    psiFile
)

// Create a new global inspection context
val context = inspectionManager.createNewGlobalContext()

// Create problem descriptors programmatically
val descriptor = inspectionManager.createProblemDescriptor(
    psiElement,                           // Element with problem
    "Description of the problem",         // Message
    arrayOf(myQuickFix),                  // Available fixes
    ProblemHighlightType.GENERIC_ERROR,   // Severity
    true                                  // onTheFly
)
```

**WolfTheProblemSolver — Real-Time Problem Tracking:**

```kotlin
import com.intellij.problems.WolfTheProblemSolver

val wolf = WolfTheProblemSolver.getInstance(project)

// Check if file has problems
val hasProblems: Boolean = wolf.isProblemFile(virtualFile)

// Listen for problem changes
wolf.addProblemListener(object : ProblemListener {
    override fun problemsAppeared(file: VirtualFile) {
        // Agent reacts to new compilation errors
    }
    override fun problemsDisappeared(file: VirtualFile) {
        // Agent's fix worked!
    }
}, parentDisposable)
```

**Use case for agent:** After making a code change, subscribe to `WolfTheProblemSolver` to know if the change introduced compilation errors without running a full build.

### XDebuggerManager (Debugging Integration)

```kotlin
import com.intellij.xdebugger.XDebuggerManager

val debugManager = XDebuggerManager.getInstance(project)

// Get active debug sessions
val sessions = debugManager.debugSessions

// Get current session
val currentSession = debugManager.currentSession

// Start a debug session
debugManager.newSessionBuilder(debugProcessStarter)
    // Configure session
    // Launch

// Get breakpoint manager
val breakpointManager = debugManager.breakpointManager
```

**Use case for agent:** When diagnosing a bug, the agent could:
1. Set a breakpoint at the error location
2. Start a debug session
3. Inspect variable values
4. Suggest a fix based on runtime state

### RunManager (Run Configuration Execution)

```kotlin
import com.intellij.execution.RunManager
import com.intellij.execution.ExecutionManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder

val runManager = RunManager.getInstance(project)

// Find existing configuration
val config = runManager.findConfigurationByName("My Test")

// Create new configuration
val settings = runManager.createConfiguration(
    "Agent Test Run",
    JUnitConfigurationType::class.java
)
runManager.addConfiguration(settings)

// Execute a configuration
val executor = DefaultRunExecutor.getRunExecutorInstance()
if (RunManager.canRunConfiguration(settings, executor)) {
    val env = ExecutionEnvironmentBuilder
        .create(executor, settings)
        .build()
    ExecutionManager.getInstance(project).restartRunProfile(env)
}

// List all configurations of a type
val junitConfigs = runManager.getConfigurationSettingsList(
    JUnitConfigurationType.getInstance()
)
```

**Use case for agent:** Run specific test configurations to verify fixes, create temporary run configs for targeted testing.

### RefactoringActionHandler (IDE-Native Refactoring)

```kotlin
import com.intellij.refactoring.RefactoringActionHandler
import com.intellij.refactoring.rename.RenameHandler

// Programmatic rename
val handler: RefactoringActionHandler = RenameHandler()
handler.invoke(project, editor, psiFile, dataContext)

// Or invoke with specific elements
handler.invoke(project, arrayOf(psiElement), dataContext)
```

**Key refactoring handlers:**
- `RenameHandler` — rename symbol
- `MoveHandler` — move class/method
- `ExtractMethodHandler` — extract method
- `InlineHandler` — inline variable/method
- `IntroduceVariableHandler` — introduce variable

**Use case for agent:** Instead of doing text-based find-and-replace for renames, use IntelliJ's refactoring engine which updates all references, import statements, and string literals correctly.

### CodeInsightAction (Code Generation/Modification)

```kotlin
import com.intellij.codeInsight.actions.CodeInsightAction

// Create a code insight action
abstract class AgentCodeAction : CodeInsightAction() {
    override fun getHandler(dataContext: DataContext): CodeInsightActionHandler {
        return MyHandler()
    }

    override fun isValidForFile(project: Project, editor: Editor, file: PsiFile): Boolean {
        return file.language == JavaLanguage.INSTANCE
    }
}
```

### IntentionAction (Quick Fixes via Alt+Enter)

```kotlin
import com.intellij.codeInsight.intention.IntentionAction

class AgentFixIntention : IntentionAction {
    override fun getText() = "Fix with Agent"

    override fun isAvailable(project: Project, editor: Editor, file: PsiFile): Boolean {
        // Check if agent can fix the issue at caret
        return true
    }

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        // Launch agent to fix the issue
    }

    override fun startInWriteAction() = false  // We handle write action ourselves

    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo {
        // Show diff preview of what agent would do
        return IntentionPreviewInfo.EMPTY
    }
}
```

### EndpointsProvider (REST Endpoint Discovery)

```kotlin
// Extension point: com.intellij.microservices.endpointsProvider
interface EndpointsProvider<Group, Endpoint> {
    fun getStatus(): Status
    fun getEndpointGroups(project: Project, filter: EndpointsFilter): List<Group>
    fun getEndpoints(group: Group): List<Endpoint>
    fun getEndpointPresentation(group: Group, endpoint: Endpoint): EndpointPresentation
    fun getModificationTracker(): ModificationTracker
}

// Also provides:
// EndpointsUrlTargetProvider — URL mapping details
// EndpointsDocumentationProvider — API documentation
```

**Use case for agent:** Discover all REST endpoints in the project, understand URL mappings, and generate/verify API integration code.

### Spring-Specific APIs (Ultimate Only)

```kotlin
// Dependency: <depends>com.intellij.spring</depends>

// Bean discovery
import com.intellij.spring.model.SpringModel
import com.intellij.spring.model.utils.SpringModelSearchers

val springModel = SpringManager.getInstance(project).getCombinedModel(module)
val beans = SpringModelSearchers.findBeans(springModel, psiType)
val bean = springModel.findBeanByName("userService")

// Spring Boot utilities
import com.intellij.spring.boot.library.SpringBootLibraryUtil

val isSpringBoot = SpringBootLibraryUtil.isSpringBootProject(module)

// Custom namespace support
// Extension point: com.intellij.spring.customNamespaces
```

### MavenProjectsManager (Project Structure)

```kotlin
import org.jetbrains.idea.maven.project.MavenProjectsManager

val mavenManager = MavenProjectsManager.getInstance(project)

// Get all Maven projects
val projects: List<MavenProject> = mavenManager.projects
val rootProjects: List<MavenProject> = mavenManager.rootProjects

// Find project for a module
val mavenProject: MavenProject? = mavenManager.findProject(module)

// Get dependencies, profiles, repositories
val profiles: Collection<String> = mavenManager.availableProfiles
val repos: Set<MavenRemoteRepository> = mavenManager.remoteRepositories

// Module relationships
val parent: MavenProject? = mavenManager.findRootProject(mavenProject)
val children: List<MavenProject> = mavenManager.getModules(aggregatorProject)
```

**Use case for agent:** Understand multi-module project structure, identify which modules are affected by changes, find dependency versions for CVE checks.

### 2025.1 Platform Changes (Relevant)

From the 2025.x API changelog:

1. **Threading model update:** Code scheduled with `SwingUtilities.invokeLater` no longer holds the write-intent lock. Migration: use `runReadAction()`/`runWriteAction()` explicitly.

2. **Kotlin UI DSL v1 removed:** All UI must use DSL v2 (we already do this).

3. **Module extraction:** Several modules now require explicit `bundledModule()` Gradle dependencies:
   - VCS modules
   - Tasks module
   - Spellchecker

4. **No new AI-specific APIs** in the public IntelliJ Platform SDK as of 2025.1. JetBrains AI Assistant uses internal APIs not exposed to third-party plugins.

### APIs We Should Integrate

**Priority 1 (Agent Core):**

| API | Current Usage | New Usage for Agent |
|-----|--------------|---------------------|
| `LocalHistory.putSystemLabel()` | Not used | Checkpoint before every agent task |
| `WriteCommandAction.withGroupId()` | Used but no grouping | Group all agent edits per step |
| `UndoManager` | Not used | Step-by-step rollback |
| `WolfTheProblemSolver` | Not used | Real-time compilation error feedback |
| `DataContext/CommonDataKeys` | Partial | Full context capture for agent |

**Priority 2 (Agent Intelligence):**

| API | Current Usage | New Usage for Agent |
|-----|--------------|---------------------|
| `InspectionManager.defaultProcessFile()` | Not used | Run inspections on modified files |
| `EndpointsProvider` | Not used | Discover REST endpoints for context |
| `RefactoringActionHandler` | Not used | IDE-native refactoring instead of text edits |
| `RunManager` | Used for stage triggers | Run tests to verify agent fixes |

**Priority 3 (Advanced Agent Features):**

| API | Current Usage | New Usage for Agent |
|-----|--------------|---------------------|
| `XDebuggerManager` | Not used | Debug-assisted bug fixing |
| `MavenProjectsManager` | Used for module detection | Full dependency graph for agent context |
| `SpringModelSearchers` | Used for bean context | Deep Spring-aware code generation |

---

## Summary: Key Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Conversation state management | Client-managed full history + prompt caching | Stateless API, caching gives 90% cost savings |
| Context growth strategy | Two-tier: compress at 60% capacity, summarize at 80% | Balances context quality vs. window limits |
| Persistence format | JSONL files + XML index | Append-friendly, crash-safe, no dependencies |
| Storage location | `PathManager.getSystemPath()/workflow-agent/` | User-specific, survives project reimport |
| Primary rollback API | `LocalHistory.putSystemLabel()` + `Label.revert()` | No VCS dependency, tracks all file changes |
| Undo integration | `WriteCommandAction.withGroupId()` | Groups agent edits for step-level undo |
| Checkpoint trigger | After every tool execution + phase transition | Balances safety vs. performance |
| Resume strategy | Validate file state → continue from last phase | Handles manual edits during interruption |
| IDE context capture | `CommonDataKeys` + `WolfTheProblemSolver` | Rich context without expensive analysis |
| Refactoring approach | `RefactoringActionHandler` over text edits | Correct reference updates across codebase |
