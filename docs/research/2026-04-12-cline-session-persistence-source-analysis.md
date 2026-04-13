# Cline Session Persistence — Source Analysis

**Date:** 2026-04-12
**Source branch:** `cline/cline` @ `main`
**Purpose:** Ground-truth reference for porting Cline's save / resume / exit architecture to the IntelliJ `:agent` module (Kotlin).

All line numbers below are against `main` at time of capture. Primary files read:

- `src/shared/HistoryItem.ts`
- `src/shared/ExtensionMessage.ts`
- `src/core/task/TaskState.ts`
- `src/core/task/message-state.ts` (class `MessageStateHandler`)
- `src/core/storage/disk.ts`
- `src/core/task/index.ts` (class `Task`, ~3800 lines)
- `src/core/controller/index.ts` (class `Controller`)
- `src/core/controller/task/showTaskWithId.ts`, `cancelTask.ts`, `clearTask.ts`

---

## 1. Persisted file layout per task

Cline stores **per-task data in a dedicated directory** under VS Code global storage. The canonical list is a single object in `disk.ts`:

```ts
// src/core/storage/disk.ts:44-70
export const GlobalFileNames = {
    apiConversationHistory: "api_conversation_history.json",
    contextHistory: "context_history.json",
    uiMessages: "ui_messages.json",
    // ... (other non-per-task catalog/model cache names) ...
    taskMetadata: "task_metadata.json",
    // ...
}
```

The task directory itself is resolved via:

```ts
// src/core/storage/disk.ts:121-123
export async function ensureTaskDirectoryExists(taskId: string): Promise<string> {
    return getGlobalStorageDir("tasks", taskId)
}
```

And `getTaskWithId` in `Controller` composes every path a resumable task cares about:

```ts
// src/core/controller/index.ts:796-814
const taskDirPath = path.join(HostProvider.get().globalStorageFsPath, "tasks", id)
const apiConversationHistoryFilePath = path.join(taskDirPath, GlobalFileNames.apiConversationHistory)
const uiMessagesFilePath            = path.join(taskDirPath, GlobalFileNames.uiMessages)
const contextHistoryFilePath        = path.join(taskDirPath, GlobalFileNames.contextHistory)
const taskMetadataFilePath          = path.join(taskDirPath, GlobalFileNames.taskMetadata)
```

Resulting layout (everything is per-task, nothing is global except `taskHistory.json`):

```
<globalStorageFsPath>/
├── state/
│   └── taskHistory.json                    # HistoryItem[] index of all tasks (see §4)
└── tasks/
    └── <taskId>/                           # taskId is Date.now().toString() for new tasks
        ├── api_conversation_history.json   # full ClineStorageMessage[] sent to the LLM
        ├── ui_messages.json                # full ClineMessage[] shown in the chat bubble UI
        ├── context_history.json            # ContextManager's truncation/dedup state (ctx-tracking)
        ├── task_metadata.json              # TaskMetadata { files_in_context, model_usage, environment_history }
        ├── settings.json                   # per-task overridden Settings (optional)
        ├── conversation_history_<ts>.json  # TRANSIENT hook consumption snapshot
        ├── conversation_history_<ts>.txt   # TRANSIENT hook consumption snapshot (text form)
        └── checkpoints/                    # handled by CheckpointTracker (shadow git), not disk.ts
```

### Why two message files?

The two are **not duplicates** — they store different data with different schemas:

| File                         | Type                     | What it stores                                                                                                                                                                                                                                              |
| ---------------------------- | ------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `api_conversation_history.json` | `ClineStorageMessage[]` (Anthropic MessageParam superset) | Exact content sent to / returned from the LLM: `user`/`assistant` roles with text, `tool_use`, `tool_result`, images, reasoning signatures. This is what gets fed back into the next API request on resume. |
| `ui_messages.json`           | `ClineMessage[]`         | Chat-bubble UI stream: `type: "ask" \| "say"` with an `ask`/`say` discriminant (e.g. `"api_req_started"`, `"tool"`, `"resume_task"`), plus `partial`, `reasoning`, `conversationHistoryIndex` (mapping back into API history), checkpoint hashes, model info. |

The critical cross-link is `ClineMessage.conversationHistoryIndex`, set in `addToClineMessages`:

```ts
// src/core/task/message-state.ts:200-216
async addToClineMessages(message: ClineMessage) {
    return await this.withStateLock(async () => {
        message.conversationHistoryIndex =
            this.apiConversationHistory.length - 1
        message.conversationHistoryDeletedRange =
            this.taskState.conversationHistoryDeletedRange
        const index = this.clineMessages.length
        this.clineMessages.push(message)
        this.emitClineMessagesChanged({ type: "add", messages: this.clineMessages, index, message })
        await this.saveClineMessagesAndUpdateHistoryInternal()
    })
}
```

This index lets the UI "time-travel" / revert: given any chat bubble, you know exactly which API message it corresponds to, which is used by edit-message and checkpoint-reversion features.

---

## 2. The `HistoryItem` type (verbatim)

```ts
// src/shared/HistoryItem.ts:1-20
export type HistoryItem = {
    id: string
    ulid?: string               // ULID for better tracking and metrics
    ts: number
    task: string
    tokensIn: number
    tokensOut: number
    cacheWrites?: number
    cacheReads?: number
    totalCost: number

    size?: number
    shadowGitConfigWorkTree?: string
    cwdOnTaskInitialization?: string
    conversationHistoryDeletedRange?: [number, number]
    isFavorited?: boolean
    checkpointManagerErrorMessage?: string

    modelId?: string
}
```

`HistoryItem[]` lives in one global file, `state/taskHistory.json`, written **atomically**:

```ts
// src/core/storage/disk.ts:378-427
export async function getTaskHistoryStateFilePath(): Promise<string> {
    return path.join(await ensureStateDirectoryExists(), "taskHistory.json")
}

export async function writeTaskHistoryToState(items: HistoryItem[]): Promise<void> {
    const filePath = await getTaskHistoryStateFilePath()
    await atomicWriteFile(filePath, JSON.stringify(items))
}
```

If parse fails on load, `readTaskHistoryFromState` calls `reconstructTaskHistory(false)` — it walks `tasks/<id>/ui_messages.json` files to regenerate the index from disk. That's the corruption recovery path.

---

## 3. `TaskState` (verbatim) — the runtime state object

This is the one persisted-via-HistoryItem + mostly-ephemeral runtime struct. Note that **most fields are NOT persisted** — only what's copied into `HistoryItem` survives a restart.

```ts
// src/core/task/TaskState.ts:6-83
export class TaskState {
    // Task-level timing
    taskStartTimeMs = Date.now()
    taskFirstTokenTimeMs?: number

    // Streaming flags                                                    [EPHEMERAL]
    isStreaming = false
    isWaitingForFirstChunk = false
    didCompleteReadingStream = false

    // Content processing                                                 [EPHEMERAL]
    currentStreamingContentIndex = 0
    assistantMessageContent: AssistantMessageContent[] = []
    userMessageContent: (Anthropic.TextBlockParam | Anthropic.ImageBlockParam | Anthropic.ToolResultBlockParam)[] = []
    userMessageContentReady = false
    toolUseIdMap: Map<string, string> = new Map()

    // Presentation locks                                                 [EPHEMERAL]
    presentAssistantMessageLocked = false
    presentAssistantMessageHasPendingUpdates = false

    // Ask/Response handling                                              [EPHEMERAL]
    askResponse?: ClineAskResponse
    askResponseText?: string
    askResponseImages?: string[]
    askResponseFiles?: string[]
    lastMessageTs?: number

    // Plan mode specific state                                           [EPHEMERAL]
    isAwaitingPlanResponse = false
    didRespondToPlanAskBySwitchingMode = false

    // Context and history                                                [PERSISTED via HistoryItem]
    conversationHistoryDeletedRange?: [number, number]

    // Tool execution flags                                               [EPHEMERAL]
    didRejectTool = false
    didAlreadyUseTool = false
    didEditFile = false
    lastToolName = ""
    lastToolParams = ""
    consecutiveIdenticalToolCount = 0

    // File read deduplication cache                                      [EPHEMERAL]
    fileReadCache: Map<string, { readCount: number; mtime: number; imageBlock?: ... }> = new Map()

    // Error tracking                                                     [MOSTLY EPHEMERAL]
    consecutiveMistakeCount = 0                                          // EPHEMERAL — reset on resume
    doubleCheckCompletionPending = false
    didAutomaticallyRetryFailedApiRequest = false
    checkpointManagerErrorMessage?: string                               // PERSISTED via HistoryItem

    // Retry tracking for auto-retry feature
    autoRetryAttempts = 0

    // Task Initialization
    isInitialized = false

    // Focus Chain / Todo List Management
    apiRequestCount = 0
    apiRequestsSinceLastTodoUpdate = 0
    currentFocusChainChecklist: string | null = null
    todoListWasUpdatedByUser = false

    // Task Abort / Cancellation                                          [EPHEMERAL]
    abort = false
    didFinishAbortingStream = false
    abandoned = false

    // Hook execution tracking for cancellation                           [EPHEMERAL]
    activeHookExecution?: HookExecution

    // Auto-context summarization                                         [EPHEMERAL]
    currentlySummarizing = false
    lastAutoCompactTriggerIndex?: number
}
```

**Key observation for the port:** `didRejectTool`, `consecutiveMistakeCount`, `userMessageContent`, `assistantMessageContent` — none of these cross the restart boundary. Cline does **not** attempt to resume a partial tool call or a partial assistant message. Everything is rebuilt from the two JSON files plus a synthetic "task resumption" user message (§5).

The only state fields copied from `HistoryItem` back into `TaskState` during reconstruction are `conversationHistoryDeletedRange` and `checkpointManagerErrorMessage`:

```ts
// src/core/task/index.ts:361-367
if (historyItem) {
    this.ulid = historyItem.ulid ?? ulid()
    this.taskIsFavorited = historyItem.isFavorited
    this.taskState.conversationHistoryDeletedRange = historyItem.conversationHistoryDeletedRange
    if (historyItem.checkpointManagerErrorMessage) {
        this.taskState.checkpointManagerErrorMessage = historyItem.checkpointManagerErrorMessage
    }
}
```

---

## 4. `ClineMessage` and `ExtensionState` (verbatim)

```ts
// src/shared/ExtensionMessage.ts:118-135
export interface ClineMessage {
    ts: number
    type: "ask" | "say"
    ask?: ClineAsk
    say?: ClineSay
    text?: string
    reasoning?: string
    images?: string[]
    files?: string[]
    partial?: boolean
    commandCompleted?: boolean
    lastCheckpointHash?: string
    isCheckpointCheckedOut?: boolean
    isOperationOutsideWorkspace?: boolean
    conversationHistoryIndex?: number
    conversationHistoryDeletedRange?: [number, number] // for when conversation history is truncated for API requests
    modelInfo?: ClineMessageModelInfo
}
```

`ClineAsk` discriminants include `resume_task` and `resume_completed_task` — these are how the resume button is rendered — plus `tool`, `command`, `plan_mode_respond`, `act_mode_respond`, `followup`, `completion_result`, etc. (full list in `ExtensionMessage.ts:137-155`).

`ClineSay` includes `api_req_started`, `api_req_finished`, `reasoning`, `text`, `tool`, `checkpoint_created`, etc. (`ExtensionMessage.ts:157-196`).

---

## 5. Save cadence — the call graph

Cline does **not** save on a timer, and **not** on webview-close. It saves **after every atomic state change**, using an in-process mutex to serialize writes. There is also no batching — every `.push()` hits the filesystem.

### Entry points in `MessageStateHandler`

```ts
// src/core/task/message-state.ts
// 178-184  addToApiConversationHistory()     → saveApiConversationHistory()
// 186-192  overwriteApiConversationHistory() → saveApiConversationHistory()
// 200-216  addToClineMessages()              → saveClineMessagesAndUpdateHistoryInternal()
// 222-233  overwriteClineMessages()          → saveClineMessagesAndUpdateHistoryInternal()
// 239-262  updateClineMessage()              → saveClineMessagesAndUpdateHistoryInternal()
// 268-290  deleteClineMessage()              → saveClineMessagesAndUpdateHistoryInternal()
```

All six go through `await this.withStateLock(async () => { ... })` (a `p-mutex` instance), and `saveClineMessagesAndUpdateHistoryInternal`:

```ts
// src/core/task/message-state.ts:120-166  (abridged)
private async saveClineMessagesAndUpdateHistoryInternal(): Promise<void> {
    try {
        await saveClineMessages(this.taskId, this.clineMessages)   // → atomicWriteFile ui_messages.json

        const apiMetrics = getApiMetrics(
            combineApiRequests(combineCommandSequences(this.clineMessages.slice(1)))
        )
        const taskMessage = this.clineMessages[0]
        const lastRelevantMessage = this.clineMessages[
            findLastIndex(this.clineMessages, (m) =>
                !(m.ask === "resume_task" || m.ask === "resume_completed_task"))
        ]
        // ... compute taskDirSize via getFolderSize.loose, pull latest modelInfo ...
        await this.updateTaskHistory({
            id: this.taskId,
            ulid: this.ulid,
            ts: lastRelevantMessage.ts,
            task: taskMessage.text ?? "",
            tokensIn: apiMetrics.totalTokensIn,
            tokensOut: apiMetrics.totalTokensOut,
            cacheWrites: apiMetrics.totalCacheWrites,
            cacheReads: apiMetrics.totalCacheReads,
            totalCost: apiMetrics.totalCost,
            size: taskDirSize,
            shadowGitConfigWorkTree: await this.checkpointTracker?.getShadowGitConfigWorkTree(),
            cwdOnTaskInitialization: cwd,
            conversationHistoryDeletedRange: this.taskState.conversationHistoryDeletedRange,
            isFavorited: this.taskIsFavorited,
            checkpointManagerErrorMessage: this.taskState.checkpointManagerErrorMessage,
            modelId: lastModelInfo?.modelInfo?.modelId,
        })
    } catch (error) {
        Logger.error("Failed to save cline messages:", error)
    }
}
```

So **every** `addToClineMessages` / `updateClineMessage` triggers:
1. `ui_messages.json` rewrite (atomic).
2. `taskHistory.json` rewrite (atomic) with recomputed metrics.

### Upstream call sites in `Task` (partial list — 20+ total)

| Line in `task/index.ts` | Context                                                                 |
| ----------------------- | ----------------------------------------------------------------------- |
| 475                     | `updateClineMessage` on `api_req_started` for retry status              |
| 684                     | `ask()` updating partial (streaming) message                            |
| 701, 743, 760           | `ask()` adding new (partial or complete) message                        |
| 850, 864, 898, 913      | `say()` adding/updating partial or complete messages (streaming chunks) |
| 2072                    | `updateClineMessage` finalizing `api_req_started` with cost/tokens      |
| 2148, 2189              | Manual and auto retry api_req messages                                  |
| 2485                    | Updating `checkpoint_created` message after checkpoint save             |
| 2624                    | **`addToApiConversationHistory` — user turn** (just after `api_req_started` placeholder) |
| 2645                    | Finalizing `api_req_started` text                                       |
| 2742                    | **`addToApiConversationHistory` — assistant turn during abort** (§6)    |
| 2821                    | Finalizing reasoning block                                              |
| 3157                    | **`addToApiConversationHistory` — assistant turn on normal stream end** |
| 3242                    | **`addToApiConversationHistory` — empty-response fallback error turn**  |

**Key semantic:** the `api_conversation_history.json` is written **once at the start of a turn** (user message) and **once at the end** (assistant message). Partial streaming chunks are written only into `ui_messages.json` via `ask`/`say` `partial: true` → update loop. If the process dies mid-stream, the API history stays consistent (you get the user message but no assistant message) and the UI message that shows the half-streamed text has `partial: true` on disk.

### Save cadence for the streaming chunk loop

```ts
// src/core/task/index.ts:823-908  say() with partial handling
// - partial === true  && previous was also partial → updateClineMessage(lastIndex, {..., partial})
//                                                    → sendPartialMessageEvent (proto) for UI
//                                                    → NO postStateToWebview (just the diff event)
//                                                    → disk write happens inside updateClineMessage
// - partial === true  && new                       → addToClineMessages(...)
//                                                    → disk write + postStateToWebview
// - partial === false && previous was partial      → updateClineMessage(lastIndex, {partial: false, ...})
//                                                    → disk write + sendPartialMessageEvent
// - partial === false (first time)                 → addToClineMessages(...)
//                                                    → disk write + postStateToWebview
```

Every chunk that arrives causes a full-file rewrite of `ui_messages.json`. Cline accepts the IO cost; this is why `p-mutex` matters (concurrent stream-chunk handlers must not corrupt each other).

---

## 6. Atomic write pattern

Cline uses **write-to-temp-then-rename**, in one helper:

```ts
// src/core/storage/disk.ts:30-42
async function atomicWriteFile(filePath: string, data: string): Promise<void> {
    const tmpPath = `${filePath}.tmp.${Date.now()}.${Math.random().toString(36).substring(7)}.json`
    try {
        await fs.writeFile(tmpPath, data, "utf8")
        await fs.rename(tmpPath, filePath)
    } catch (error) {
        fs.unlink(tmpPath).catch(() => {})
        throw error
    }
}
```

Used by `saveApiConversationHistory` (`disk.ts:245-260`), `saveClineMessages` (`disk.ts:277-285`), `writeTaskHistoryToState` (`disk.ts:419-427`), and the PreCompact temp snapshot writers.

**Cline does NOT use JSONL for these files.** Both `ui_messages.json` and `api_conversation_history.json` are full rewrites of `JSON.stringify(entireArray)` on every save. Since the rename is atomic on POSIX and generally on NTFS, readers never see a half-written file — they either see the old complete version or the new complete version. There is no journaling and no fsync. This is good enough because taskHistory reconstruction can recover any task whose `ui_messages.json` is intact.

---

## 7. The resume flow, end to end

### 7a. Extension startup with an unfinished task

On extension activation, `Controller` is constructed. It does **not** auto-resume anything. The only state that survives is `taskHistory.json` which is loaded into the `StateManager` cache. The webview receives `currentTaskItem: undefined` (see `getStateToPostToWebview`, line 899) and the user sees the history list.

There is no "unfinished task on startup" concept beyond: the task appears in the history list with its last timestamp. If the user closed VS Code mid-stream, the task is just "one of the entries in history" until they click it.

### 7b. User clicks "Resume Task" from history

1. Webview sends a gRPC `showTaskWithId(id)`.
2. `showTaskWithId.ts:13-64` calls `controller.initTask(undefined, undefined, undefined, historyItem)`:
   ```ts
   // src/core/controller/task/showTaskWithId.ts:22-25
   if (historyItem) {
       await controller.initTask(undefined, undefined, undefined, historyItem)
       await sendChatButtonClickedEvent()
       // ...
   }
   ```
3. `Controller.initTask` (`controller-index.ts:230`):
   - `await this.clearTask()` — aborts and drops any previous task.
   - Acquires a per-task filesystem lock via `tryAcquireTaskLockWithRetry(taskId)`.
   - Constructs a fresh `new Task({ ..., historyItem, ... })`.
   - In the constructor (`task-index.ts:361-367`), restores `ulid`, `taskIsFavorited`, `conversationHistoryDeletedRange`, `checkpointManagerErrorMessage` from `historyItem`.
   - Constructs a fresh `MessageStateHandler`, `FileContextTracker`, `ModelContextTracker`, `EnvironmentContextTracker`, `CheckpointManager`, `api` handler — all from scratch.
   - Calls `this.task.resumeTaskFromHistory()` (`controller-index.ts:329-330`).

### 7c. `Task.resumeTaskFromHistory()` — the reconstruction

```ts
// src/core/task/index.ts:1178-1235 (abridged)
public async resumeTaskFromHistory() {
    await this.clineIgnoreController.initialize()

    const savedClineMessages = await getSavedClineMessages(this.taskId)

    // Remove any resume messages that may have been added before
    const lastRelevantMessageIndex = findLastIndex(
        savedClineMessages,
        (m) => !(m.ask === "resume_task" || m.ask === "resume_completed_task"),
    )
    if (lastRelevantMessageIndex !== -1) {
        savedClineMessages.splice(lastRelevantMessageIndex + 1)
    }

    // Drop an abandoned api_req_started that never produced any cost/cancel info
    const lastApiReqStartedIndex = findLastIndex(
        savedClineMessages, (m) => m.type === "say" && m.say === "api_req_started")
    if (lastApiReqStartedIndex !== -1) {
        const lastApiReqStarted = savedClineMessages[lastApiReqStartedIndex]
        const { cost, cancelReason }: ClineApiReqInfo = JSON.parse(lastApiReqStarted.text || "{}")
        if (cost === undefined && cancelReason === undefined) {
            savedClineMessages.splice(lastApiReqStartedIndex, 1)
        }
    }

    await this.messageStateHandler.overwriteClineMessages(savedClineMessages)
    this.messageStateHandler.setClineMessages(await getSavedClineMessages(this.taskId))

    // Load API history
    const savedApiConversationHistory = await getSavedApiConversationHistory(this.taskId)
    this.messageStateHandler.setApiConversationHistory(savedApiConversationHistory)

    // Load context-manager state
    await this.contextManager.initializeContextHistory(await ensureTaskDirectoryExists(this.taskId))

    // Pick which ask to show
    const lastClineMessage = this.messageStateHandler.getClineMessages().slice().reverse()
        .find((m) => !(m.ask === "resume_task" || m.ask === "resume_completed_task"))
    const askType = lastClineMessage?.ask === "completion_result" ? "resume_completed_task" : "resume_task"

    this.taskState.isInitialized = true
    this.taskState.abort = false

    const { response, text, images, files } = await this.ask(askType)  // blocks on user click
    // ... TaskResume hook, UserPromptSubmit hook ...
```

After the user clicks the resume button, the code **rewrites the last user message** in the API history to inject a resumption preamble:

```ts
// src/core/task/index.ts:1313-1338 (abridged)
let modifiedOldUserContent: ClineContent[]
let modifiedApiConversationHistory: ClineStorageMessage[]
if (existingApiConversationHistory.length > 0) {
    const lastMessage = existingApiConversationHistory[existingApiConversationHistory.length - 1]
    if (lastMessage.role === "assistant") {
        // Normal case: turn ended cleanly, assistant was last. Start a new user turn.
        modifiedApiConversationHistory = [...existingApiConversationHistory]
        modifiedOldUserContent = []
    } else if (lastMessage.role === "user") {
        // Interrupted case: there's a pending user message that was never answered.
        // Pop it off and merge its content into the new resume message.
        const existingUserContent: ClineContent[] = Array.isArray(lastMessage.content)
            ? lastMessage.content
            : [{ type: "text", text: lastMessage.content }]
        modifiedApiConversationHistory = existingApiConversationHistory.slice(0, -1)
        modifiedOldUserContent = [...existingUserContent]
    }
}

// Later, the resume preamble is appended:
// src/core/task/index.ts:1367-1388
const [taskResumptionMessage, userResponseMessage] = formatResponse.taskResumption(
    mode === "plan" ? "plan" : "act",
    agoText,            // "5 minutes ago"
    this.cwd,
    wasRecent,          // < 30s → soft-resume
    responseText,
    hasPendingFileContextWarnings,
)
// ... push taskResumptionMessage, userResponseMessage, imageBlocks, file content, hook context ...
```

Finally:

```ts
// src/core/task/index.ts:1446-1447
await this.messageStateHandler.overwriteApiConversationHistory(modifiedApiConversationHistory)
await this.initiateTaskLoop(newUserContent)
```

### Fields reconstructed on resume — full list

From `HistoryItem` → `TaskState`:
- `ulid`
- `taskIsFavorited` (Task instance field, not TaskState)
- `conversationHistoryDeletedRange`
- `checkpointManagerErrorMessage`

From disk files:
- `apiConversationHistory` ← `api_conversation_history.json`
- `clineMessages` ← `ui_messages.json` (after trimming dangling `resume_task` and cost-less `api_req_started`)
- `contextManager` internal state ← `context_history.json`
- `taskMetadata` ← `task_metadata.json` (files_in_context, model_usage, environment_history)

**Not reconstructed (ephemeral):** `consecutiveMistakeCount`, `didRejectTool`, `didAlreadyUseTool`, `userMessageContent`, `assistantMessageContent`, `toolUseIdMap`, `fileReadCache`, `autoRetryAttempts`, `isStreaming`, `currentStreamingContentIndex`, plan-mode flags, focus-chain counters. All reset to defaults via `new TaskState()` in the constructor (`task-index.ts:299`).

**Plan mode on resume:** there's no persisted plan-mode flag — the user's current `mode` setting in `StateManager` decides the mode via `getGlobalSettingsKey("mode")` when the resumption message is built (`task-index.ts:1366-1374`). If the user resumed in act mode but the task was in plan mode, you'd get act mode now. The `formatResponse.taskResumption()` helper branches on `"plan" | "act"` and produces a different preamble.

---

## 8. Mid-tool-call interruption — exact handling

This is the most interesting part. Cline's approach is **rewrite the truth when you abort, then let the LLM see the interrupt marker on resume**.

### The abort path (`abortStream` inside the stream loop)

```ts
// src/core/task/index.ts:2721-2780  (abridged)
const abortStream = async (cancelReason: ClineApiReqCancelReason, streamingFailedMessage?: string) => {
    Session.get().finalizeRequest()

    if (this.diffViewProvider.isEditing) {
        await this.diffViewProvider.revertChanges() // closes diff view
    }

    // if last message is a partial we need to update and save it
    const lastMessage = this.messageStateHandler.getClineMessages().at(-1)
    if (lastMessage?.partial) {
        lastMessage.partial = false   // flip it in-place; subsequent save will persist
    }
    // update api_req_started to have cancelled and cost, so that we can display the cost of the partial stream
    await finalizeApiReqMsg(cancelReason, streamingFailedMessage)
    await this.messageStateHandler.saveClineMessagesAndUpdateHistory()

    // Let assistant know their response was interrupted for when task is resumed
    await this.messageStateHandler.addToApiConversationHistory({
        role: "assistant",
        content: [{
            type: "text",
            text: assistantMessage +
                  `\n\n[${cancelReason === "streaming_failed"
                       ? "Response interrupted by API Error"
                       : "Response interrupted by user"}]`,
        }],
        // ... modelInfo, metrics ...
        ts: Date.now(),
    })

    // signals to provider that it can retrieve the saved messages from disk
    this.taskState.didFinishAbortingStream = true
}
```

### What the next turn sees

On resume, `resumeTaskFromHistory` loads that synthetic assistant turn (text + `[Response interrupted by user]`) as the last message in `api_conversation_history.json`. Because `lastMessage.role === "assistant"`, the else-branch at `task-index.ts:1318-1320` takes the clean path: start a new user turn. The new user turn contains the resumption preamble plus the user's typed reply (or just the resumption preamble if they pressed the button without typing).

**Crucially: Cline does NOT attempt to replay an in-flight tool call.** If a `tool_use` block was partially streamed when the user cancelled:
- The partial tool_use XML/JSON is preserved inside `assistantMessage` because `abortStream` appends the entire accumulated text.
- The `[Response interrupted by user]` marker tells the LLM "this was incomplete, don't pretend it ran."
- The next user turn is a fresh start.

If the partial text parsed into a tool_use and the user was mid-approval (i.e. stream ended but user hasn't clicked yes/no), the `didRejectTool` and `didAlreadyUseTool` flags are **reset in TaskState** (they're ephemeral) and the LLM sees only text. The pending tool call is effectively dropped.

### The abort orchestration (outside the stream)

```ts
// src/core/task/index.ts:1531-1673  abortTask() (abridged phases)
async abortTask() {
    // PHASE 1: check if TaskCancel hook should run (based on active work indicators)
    // PHASE 2: this.taskState.abort = true
    // PHASE 3: cancel any running hook execution + background commands
    // PHASE 4: run TaskCancel hook (non-cancellable), then re-emit the resume ask
    //          so the resume button reappears in the UI
    // PHASE 5: await this.messageStateHandler.saveClineMessagesAndUpdateHistory()
    //          + postStateToWebview() so UI reflects abort
    // PHASE 6: check focus-chain incomplete-progress telemetry
    // PHASE 7: cleanup (terminals, browser, diffView.revertChanges(), FocusChainManager, etc.)
    // FINALLY: release task lock, final postStateToWebview
}
```

Then `Controller.cancelTask` (`controller-index.ts:425-493`):

```ts
// await this.task.abortTask()
// pWaitFor(() => isStreaming === false || didFinishAbortingStream || isWaitingForFirstChunk, { timeout: 3000 })
// this.task.taskState.abandoned = true      // prevents zombie gui updates
// historyItem = (await this.getTaskWithId(this.task.taskId)).historyItem
// if (historyItem) await this.initTask(undefined, undefined, undefined, historyItem, undefined)
// else await this.clearTask()
// await this.postStateToWebview()
```

So after cancel, Cline **re-initTasks from history immediately**, which re-runs `resumeTaskFromHistory` and re-shows the resume button in a freshly-constructed Task instance. The user is never looking at a stale reference.

---

## 9. User-facing exit + resume UX

- **No explicit exit button.** The only task-ending affordances are:
  - **Cancel button** during active work → `controller.cancelTask()` → `Task.abortTask()` → re-init from history → resume button shown.
  - **"+" (new task) button** → `controller.clearTask()` → aborts current task → sets `this.task = undefined`.
  - **Closing VS Code** → extension deactivates → `Controller.dispose()` → `this.clearTask()` (task-index.ts:174-175).

- **Resume is implicit:** user closes VS Code, reopens, sees history list, clicks a task card. `showTaskWithId` runs the whole re-init sequence.

- **Does resume fully re-hydrate the chat bubble UI?** **Yes.** `resumeTaskFromHistory` loads `ui_messages.json` verbatim (minus dangling resume messages and cost-less api_req_starteds). Every streamed reasoning block, tool card, checkpoint marker, and cost display appears exactly as it did before. The webview gets the full `clineMessages[]` via `postStateToWebview` and rehydrates the virtuoso list. The resumption preamble only affects the **LLM-side** `api_conversation_history.json` — the UI shows a new "resume_task" ask bubble, not a modified version of the previous turn.

---

## 10. Per-task lock

Not directly asked, but important for the port: Cline uses a filesystem lock to prevent multiple VS Code instances from opening the same task:

```ts
// src/core/controller/index.ts:284-299
const lockResult: FolderLockWithRetryResult = await tryAcquireTaskLockWithRetry(taskId)
if (!lockResult.acquired && !lockResult.skipped) {
    const errorMessage = lockResult.conflictingLock
        ? `Task locked by instance (${lockResult.conflictingLock.held_by})`
        : "Failed to acquire task lock"
    throw new Error(errorMessage)
}
```

Released in `abortTask`'s `finally` block (`task-index.ts:1655-1665`). The Kotlin port should have an equivalent (`java.nio.channels.FileLock` on a sidecar file in the task directory).

---

## Key takeaways for Kotlin port

1. **Two files per session, not one.** Write `api_conversation_history.json` (LLM view) and `ui_messages.json` (chat bubble view) into `~/.workflow-orchestrator/{project-hash}/agent/sessions/{sessionId}/`. Keep them structurally distinct; the cross-link is `ClineMessage.conversationHistoryIndex = apiHistory.size - 1` at add time. Plus a one-file global index (`sessions.json` = `List<HistoryItem>`).

2. **Save after every atomic change, never on a timer.** Every `addMessage`, `updateMessage`, `deleteMessage` goes through a mutex and persists both files. `p-mutex` in TS → `kotlinx.coroutines.sync.Mutex` in Kotlin. Do the write inside `withLock`.

3. **Atomic write pattern:** temp file with random suffix → `fs.writeFile` → `fs.rename`. Kotlin: `java.nio.file.Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)`. No JSONL; full rewrites are fine at Cline's scale (~MB sessions).

4. **TaskState is mostly ephemeral.** Only persist `conversationHistoryDeletedRange`, `checkpointManagerErrorMessage`, `ulid`, `isFavorited`, plus the derived metrics (`tokensIn`, `tokensOut`, `totalCost`, `cwdOnTaskInitialization`, `modelId`) — i.e. `HistoryItem`. Everything else (`consecutiveMistakeCount`, `didRejectTool`, `fileReadCache`, plan-mode flags) is reset to defaults on resume.

5. **Mid-tool-call interruption handling:** on abort, flip the last partial message's `partial = false`, append a synthetic assistant turn to API history containing the accumulated text + `[Response interrupted by user]` marker, finalize the `api_req_started` cost display, and `saveClineMessagesAndUpdateHistory()`. Never replay an in-flight tool. Never re-prompt the LLM with pending-tool-call state.

6. **Resume flow:** load `ui_messages.json`, strip trailing `resume_task` bubbles and cost-less `api_req_started`, load `api_conversation_history.json`, pop a trailing `user` message into `modifiedOldUserContent`, show the resume ask (`resume_task` / `resume_completed_task` picked from the last non-resume message's `ask` type), wait for user to click, build a `taskResumption` preamble with an "X minutes ago" timestamp and optional user reply, and restart `initiateTaskLoop(newUserContent)`.

7. **No explicit exit.** Close-VS-Code path = `Controller.dispose() → clearTask() → abortTask()` (no special save; the per-write saves already captured everything). Resume is implicit from the history list.

8. **Per-task filesystem lock** to prevent two IDE instances clobbering the same task directory. Acquire in `initTask`, release in the abort `finally`.

9. **The `partial: true` flag is the half-streamed marker on disk.** On resume, these get flipped to `partial: false` implicitly (cline trims cost-less `api_req_started` but leaves other partials as display-only; if an abort happened, `abortStream` already flipped them). For the Kotlin port, trim dangling partials in the resume loader for safety.

10. **Port the `getTaskWithId` pattern verbatim.** It composes every per-task file path from `(globalStorageDir, "tasks", id)` and reads the API history. In the Kotlin side, `SessionStore.getSessionWithId(sessionId)` should return a `SessionPaths` data class with the same fields so the controller can pass it to `AgentLoop.resumeFromHistory(sessionPaths)`.

**Report length:** ~2,650 words.
