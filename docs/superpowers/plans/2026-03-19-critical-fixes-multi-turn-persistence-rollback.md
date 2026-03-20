# Critical Fixes: Multi-Turn, Persistence, Rollback, Checkpointing

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the AI agent maintain conversation state across user messages, persist history across IDE restarts, use IntelliJ's LocalHistory for rollback, and support checkpointing for interrupted tasks.

**Architecture:** Replace per-message orchestrator creation with a long-lived ConversationSession that accumulates messages. Conversation serialized to JSONL after each turn. Rollback via LocalHistory.putSystemLabel() instead of git stash. Cross-project history accessible via app-level service.

**Tech Stack:** Kotlin, IntelliJ Platform (LocalHistory, PersistentStateComponent, PathManager, WriteCommandAction), kotlinx.serialization, JSONL

---

## File Structure

### New Files (7)

| File | Responsibility |
|------|---------------|
| `agent/runtime/ConversationSession.kt` | Long-lived session: owns ContextManager, persists across user turns, handles multi-turn |
| `agent/runtime/ConversationStore.kt` | JSONL read/write for conversation messages + metadata |
| `agent/runtime/AgentRollbackManager.kt` | LocalHistory-based rollback with per-step WriteCommandAction groups |
| `agent/runtime/SessionCheckpoint.kt` | Serializable checkpoint state (replaces AgentCheckpoint for new flow) |
| `agent/ui/HistoryTabProvider.kt` | History tab showing all sessions across projects |
| `agent/ui/HistoryPanel.kt` | UI panel for browsing/resuming past sessions |
| `agent/service/GlobalSessionIndex.kt` | App-level service indexing all sessions across projects |

### Modified Files (5)

| File | Change |
|------|--------|
| `agent/ui/AgentController.kt` | Use ConversationSession instead of creating new orchestrator per message |
| `agent/orchestrator/AgentOrchestrator.kt` | Accept existing ContextManager instead of always creating new one |
| `agent/runtime/FileGuard.kt` | Replace git stash with LocalHistory.putSystemLabel() |
| `agent/listeners/AgentStartupActivity.kt` | Detect interrupted sessions, offer resume with loaded conversation |
| `agent/ui/AgentTabProvider.kt` | Register HistoryTabProvider alongside Agent tab |

### Deleted Files (0)
No files deleted — CheckpointStore.kt kept for orchestrated mode backward compat.

---

## Task 1: ConversationSession — Long-Lived Session Across User Turns

The core fix. Instead of creating a new orchestrator per message, create a ConversationSession that persists and reuses the ContextManager.

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ConversationSession.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/AgentOrchestrator.kt`

### Design

```
ConversationSession
├── sessionId: String (UUID)
├── contextManager: ContextManager (PERSISTS across messages)
├── messages: MutableList<ConversationMessage> (full history for serialization)
├── systemPrompt: String (built once, reused)
├── toolDefinitions: List<ToolDefinition> (cached)
├── brain: LlmBrain (cached)
├── createdAt: Long
├── lastMessageAt: Long
└── title: String (first user message)

Flow:
  First message → ConversationSession created, ContextManager initialized
  Subsequent message → Same session, user message appended to ContextManager
  "New Chat" → Session ended, new ConversationSession created
  IDE restart → Session loaded from JSONL, ContextManager reconstructed
```

### Key Change in AgentOrchestrator

Current: `executeTask()` creates ContextManager internally
New: `executeTask()` accepts an optional ContextManager parameter

```kotlin
// AgentOrchestrator.executeTask() — NEW signature
suspend fun executeTask(
    taskDescription: String,
    contextManager: ContextManager? = null, // NEW: reuse existing context
    onProgress: (AgentProgress) -> Unit = {},
    onStreamChunk: (String) -> Unit = {},
    approvalGate: ApprovalGate? = null
): AgentResult {
    // If contextManager provided, reuse it (multi-turn)
    // If null, create new one (backward compat, first message)
    val cm = contextManager ?: ContextManager(
        maxInputTokens = maxInputTokens,
        reservedTokens = reservedTokens
    )
    // ... rest of flow uses cm
}
```

### Key Change in AgentController

Current: Creates new `AgentOrchestrator` per message
New: Creates `ConversationSession` once, reuses across messages

```kotlin
class AgentController(...) {
    private var session: ConversationSession? = null

    fun executeTask(task: String) {
        if (session == null) {
            session = ConversationSession.create(project, agentService)
        }
        session!!.addUserMessage(task)
        // session.contextManager already has prior conversation
        val orchestrator = AgentOrchestrator(session!!.brain, ...)
        orchestrator.executeTask(
            task,
            contextManager = session!!.contextManager, // REUSE
            ...
        )
    }

    fun newChat() {
        session?.save() // persist before discarding
        session = null
        dashboard.reset()
    }
}
```

- [ ] **Step 1:** Create `ConversationSession.kt` with sessionId, contextManager, messages list, create/addUserMessage/save methods
- [ ] **Step 2:** Modify `AgentOrchestrator.executeTask()` to accept optional `contextManager` parameter. If provided, skip creating new one, skip adding system prompt (already in context), skip repo map generation.
- [ ] **Step 3:** Modify `AgentController.executeTask()` to use ConversationSession — create once, reuse across messages
- [ ] **Step 4:** Modify `AgentController.newChat()` to save and discard session
- [ ] **Step 5:** Verify: send two messages, second message should reference first message's context
- [ ] **Step 6:** Commit: `feat(agent): multi-turn conversation — session persists across messages`

---

## Task 2: ConversationStore — JSONL Persistence

Serialize conversation to JSONL after each turn so it survives IDE restarts.

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ConversationStore.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ConversationSession.kt`

### Storage Layout

```
{PathManager.getSystemPath()}/workflow-agent/sessions/
├── {sessionId}/
│   ├── messages.jsonl          # One message per line (append-friendly)
│   ├── metadata.json           # Session info: title, project, model, timestamps
│   └── checkpoint.json         # Resume state if interrupted
```

### JSONL Format

```jsonl
{"role":"user","content":"Fix the NPE in UserService","ts":1710806400}
{"role":"assistant","content":"I'll look at the file first.","ts":1710806401,"toolCalls":[{"id":"t1","name":"read_file","args":"{\"path\":\"UserService.kt\"}"}]}
{"role":"tool","toolCallId":"t1","content":"class UserService { ... }","ts":1710806402}
{"role":"assistant","content":"Found the issue on line 23.","ts":1710806403}
```

- [ ] **Step 1:** Create `ConversationStore.kt` with `saveMessage()` (append JSONL), `loadMessages()` (stream read), `saveMetadata()`, `loadMetadata()`, `listSessions()`, `deleteSession()`
- [ ] **Step 2:** Add `@Serializable` data classes: `PersistedMessage`, `SessionMetadata`
- [ ] **Step 3:** Wire into `ConversationSession`: after each turn (user message + agent response), call `store.saveMessage()` for each new message
- [ ] **Step 4:** Add `ConversationSession.load(sessionId)` — reads JSONL, reconstructs ContextManager with messages
- [ ] **Step 5:** Test: send message, restart (simulate), load session, verify messages restored
- [ ] **Step 6:** Commit: `feat(agent): persist conversations to JSONL — survives IDE restarts`

---

## Task 3: GlobalSessionIndex — Cross-Project History

App-level service that indexes all sessions from all projects for the History tab.

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/service/GlobalSessionIndex.kt`

### Design

```kotlin
@Service(Service.Level.APP)
@State(
    name = "WorkflowAgentSessions",
    storages = [Storage("workflowAgentSessions.xml", roamingType = RoamingType.DISABLED)]
)
class GlobalSessionIndex : PersistentStateComponent<GlobalSessionIndex.State> {
    data class SessionEntry(
        val sessionId: String,
        val projectName: String,
        val projectPath: String,
        val title: String,       // First user message
        val createdAt: Long,
        val lastMessageAt: Long,
        val messageCount: Int,
        val status: String       // "active", "completed", "interrupted"
    )

    fun addSession(entry: SessionEntry)
    fun updateSession(sessionId: String, updater: (SessionEntry) -> SessionEntry)
    fun getSessions(limit: Int = 50): List<SessionEntry>
    fun getSessionsForProject(projectPath: String): List<SessionEntry>
    fun deleteSession(sessionId: String)
    fun cleanup(maxAge: Duration = Duration.ofDays(30), maxCount: Int = 100)
}
```

- [ ] **Step 1:** Create `GlobalSessionIndex.kt` with PersistentStateComponent and SessionEntry data class
- [ ] **Step 2:** Add CRUD methods: addSession, updateSession, getSessions, deleteSession
- [ ] **Step 3:** Add cleanup policy: max 100 sessions, max 30 days old
- [ ] **Step 4:** Wire into ConversationSession: register on create, update on message, mark completed/interrupted
- [ ] **Step 5:** Commit: `feat(agent): global session index — cross-project history service`

---

## Task 4: History Tab — Browse and Resume Past Sessions

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/HistoryPanel.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/HistoryTabProvider.kt`
- Modify: `src/main/resources/META-INF/plugin.xml` (register tab provider)

### UI Layout

```
┌─────────────────────────────────────────────────────┐
│ History                                    [Clear]   │
├─────────────────────────────────────────────────────┤
│ ┌─────────────────────────────────────────────────┐ │
│ │ ● Fix NPE in UserService            2m ago     │ │
│ │   my-service · 12 messages · ✓ Completed       │ │
│ ├─────────────────────────────────────────────────┤ │
│ │ ○ Refactor auth module              15m ago     │ │
│ │   auth-service · 8 messages · ⚠ Interrupted    │ │
│ │                              [Resume] [Delete]  │ │
│ ├─────────────────────────────────────────────────┤ │
│ │ ● Add pagination to /api/users      1h ago     │ │
│ │   my-service · 24 messages · ✓ Completed       │ │
│ └─────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────┘
```

- [ ] **Step 1:** Create `HistoryPanel.kt` with JBList showing sessions from GlobalSessionIndex
- [ ] **Step 2:** Custom cell renderer: title, project name, message count, status, relative time
- [ ] **Step 3:** Add "Resume" button for interrupted sessions — loads conversation, switches to Agent tab
- [ ] **Step 4:** Add "Delete" button — removes JSONL files + index entry
- [ ] **Step 5:** Create `HistoryTabProvider.kt` implementing WorkflowTabProvider
- [ ] **Step 6:** Register in plugin.xml as a tabProvider extension
- [ ] **Step 7:** Commit: `feat(agent): History tab — browse and resume past sessions across projects`

---

## Task 5: AgentRollbackManager — LocalHistory-Based Rollback

Replace FileGuard's git stash with IntelliJ's LocalHistory API.

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/AgentRollbackManager.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/AgentOrchestrator.kt`

### Design

```kotlin
class AgentRollbackManager(private val project: Project) {
    private val checkpoints = mutableMapOf<String, Label>()
    private val touchedFiles = mutableSetOf<String>()

    // Before agent task starts
    fun createTaskCheckpoint(taskDescription: String): String {
        val id = UUID.randomUUID().toString()
        val label = LocalHistory.getInstance().putSystemLabel(
            project, "Agent: $taskDescription"
        )
        checkpoints[id] = label
        return id
    }

    // Track files the agent modifies
    fun trackFileChange(path: String) {
        touchedFiles.add(path)
    }

    // Rollback all changes since checkpoint
    fun rollbackToCheckpoint(checkpointId: String) {
        val label = checkpoints[checkpointId] ?: return
        val baseDir = project.basePath?.let {
            LocalFileSystem.getInstance().findFileByPath(it)
        } ?: return

        WriteCommandAction.runWriteCommandAction(project) {
            label.revert(project, baseDir)
        }
    }

    // Per-step undo group (for IDE undo integration)
    fun <T> executeWithUndo(stepName: String, taskId: String, action: () -> T): T {
        return WriteCommandAction.writeCommandAction(project)
            .withName("Agent: $stepName")
            .withGroupId("agent-$taskId")
            .withGlobalUndo()
            .compute(ThrowableComputable { action() })
    }
}
```

- [ ] **Step 1:** Create `AgentRollbackManager.kt` with createTaskCheckpoint, trackFileChange, rollbackToCheckpoint, executeWithUndo
- [ ] **Step 2:** Modify `AgentOrchestrator.executeTask()`: replace `FileGuard.snapshotFiles()` with `AgentRollbackManager.createTaskCheckpoint()`
- [ ] **Step 3:** Add "Undo All Agent Changes" action in the UI (button or notification action)
- [ ] **Step 4:** Wire tool execution through rollbackManager.trackFileChange() for edit_file results
- [ ] **Step 5:** Test: agent edits a file, click undo, verify file reverted
- [ ] **Step 6:** Commit: `feat(agent): LocalHistory-based rollback — replaces git stash`

---

## Task 6: Session Checkpointing — Resume After Interruption

Save enough state after each tool execution to resume a session.

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SessionCheckpoint.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ConversationSession.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/listeners/AgentStartupActivity.kt`

### Checkpoint Data

```kotlin
@Serializable
data class SessionCheckpoint(
    val sessionId: String,
    val phase: String,          // "executing", "waiting_approval", "completed", "failed"
    val iteration: Int,
    val tokensUsed: Int,
    val lastToolCall: String?,  // Last tool executed
    val touchedFiles: List<String>,
    val rollbackCheckpointId: String?,
    val timestamp: Long
)
```

### Resume Flow

```
IDE starts → AgentStartupActivity
  → Check GlobalSessionIndex for "interrupted" sessions
  → If found: show notification "Resume interrupted task?"
  → User clicks Resume:
    1. Load ConversationSession from JSONL
    2. Reconstruct ContextManager with messages
    3. Validate file state (check touched files for manual edits)
    4. Switch to Agent tab, show loaded conversation
    5. Add system message: "Session resumed. Previous context has been restored."
    6. Wait for user to type next message
```

- [ ] **Step 1:** Create `SessionCheckpoint.kt` with data class and save/load methods
- [ ] **Step 2:** In ConversationSession: save checkpoint after each tool execution
- [ ] **Step 3:** On session complete/fail: delete checkpoint, update index status
- [ ] **Step 4:** In AgentStartupActivity: check for interrupted sessions, show resume notification
- [ ] **Step 5:** Implement resume: load JSONL → reconstruct ContextManager → populate UI → wait for user input
- [ ] **Step 6:** Test: start task, kill IDE, restart, resume from checkpoint
- [ ] **Step 7:** Commit: `feat(agent): session checkpointing — resume after IDE restart`

---

## Task 7: Integration Tests and Cleanup

- [ ] **Step 1:** Test multi-turn: send 3 messages, verify 3rd message has context from 1st
- [ ] **Step 2:** Test persistence: send message, simulate restart (recreate ConversationSession from JSONL), verify history loaded
- [ ] **Step 3:** Test rollback: agent edits file, trigger rollback, verify file restored
- [ ] **Step 4:** Test history tab: create sessions in 2 projects, verify both appear in History
- [ ] **Step 5:** Update AgentService to clean up sessions on project close
- [ ] **Step 6:** Run `./gradlew :agent:test --rerun` — all tests pass
- [ ] **Step 7:** Run `./gradlew compileKotlin` — all modules compile
- [ ] **Step 8:** Commit: `test(agent): integration tests for multi-turn, persistence, rollback, resume`

---

## Verification

```bash
./gradlew :agent:test --rerun    # All tests pass
./gradlew compileKotlin          # All modules compile
./gradlew buildPlugin            # Plugin builds successfully
```

Manual verification in `runIde`:
1. Open project → Agent tab → type message → get response → type follow-up → agent references prior context ✓
2. Close IDE → reopen → History tab shows session → click Resume → conversation restored ✓
3. Agent edits a file → click "Undo Agent Changes" → file reverted ✓
4. Start long task → force-kill IDE → restart → notification offers resume ✓
