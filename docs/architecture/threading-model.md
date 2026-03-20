# Threading Model

## Overview

IntelliJ IDEA enforces strict threading rules. Violating them causes either UI freezes (blocking the EDT) or exceptions (writing from a background thread). The plugin uses three execution contexts.

## Thread Context Diagram

```mermaid
flowchart TD
    subgraph EDT ["EDT (Event Dispatch Thread)"]
        direction TB
        UI_UPDATE["UI Updates<br/><i>panel.repaint(), model.fireChanged()</i>"]
        WRITE_ACTION["Write Actions<br/><i>WriteCommandAction.runWriteCommandAction()</i>"]
        SWING_CB["Swing Callbacks<br/><i>button.addActionListener, list selection</i>"]
        ACTION["AnAction.actionPerformed()"]
    end

    subgraph IO ["Dispatchers.IO"]
        direction TB
        API_CALL["API Calls<br/><i>suspend fun getTicket(), triggerBuild()</i>"]
        FILE_READ["File I/O<br/><i>read fixtures, parse logs</i>"]
        HTTP["HTTP Requests<br/><i>OkHttp execute()</i>"]
    end

    subgraph BG ["Background Thread Pool"]
        direction TB
        POLLING["Build Polling<br/><i>CoroutineScope + SupervisorJob</i>"]
        HEALTH["Health Check<br/><i>runBackgroundableTask</i>"]
        MAVEN_RUN["Maven Build<br/><i>MavenRunner.run()</i>"]
        CODY_AGENT["Cody CLI Process<br/><i>JSON-RPC stdio reader</i>"]
        AGENT_LOOP["Agent ReAct Loop<br/><i>SingleAgentSession + WorkerSession</i>"]
        AGENT_LLM["Agent LLM Calls<br/><i>SourcegraphChatClient</i>"]
    end

    ACTION -->|"launch on IO"| API_CALL
    API_CALL -->|"withContext(EDT)"| UI_UPDATE
    POLLING -->|"withContext(EDT)"| UI_UPDATE
    HEALTH -->|"withContext(EDT)"| UI_UPDATE
    API_CALL -->|"WriteCommandAction"| WRITE_ACTION

    style EDT fill:#2d4a22,stroke:#6a9955,color:#d4d4d4
    style IO fill:#264f78,stroke:#569cd6,color:#d4d4d4
    style BG fill:#4e3a24,stroke:#ce9178,color:#d4d4d4
```

## Rules

### Rule 1: Never Block the EDT

Every API call is a `suspend fun` dispatched on `Dispatchers.IO`. The EDT remains responsive.

```kotlin
// CORRECT
suspend fun loadTickets() = withContext(Dispatchers.IO) {
    val result = jiraService.getTicket("PROJ-123")
    withContext(Dispatchers.EDT) {
        ticketPanel.updateWith(result.data)
    }
}

// WRONG - freezes IDE
fun loadTickets() {
    val result = runBlocking { jiraService.getTicket("PROJ-123") }  // NEVER
    ticketPanel.updateWith(result.data)
}
```

### Rule 2: UI Updates Only on EDT

All Swing component modifications must happen on the EDT.

```kotlin
// CORRECT
withContext(Dispatchers.EDT) {
    tableModel.fireTableDataChanged()
}

// ALTERNATIVE
SwingUtilities.invokeLater {
    tableModel.fireTableDataChanged()
}
```

### Rule 3: Write Actions on EDT

File modifications (applying Cody edits, copyright fixes) require `WriteCommandAction`.

```kotlin
WriteCommandAction.runWriteCommandAction(project) {
    document.replaceString(startOffset, endOffset, newText)
}
```

### Rule 4: User-Triggered Operations Use runBackgroundableTask

Operations like "Test Connection" or "Run Health Check" show a progress indicator.

```kotlin
runBackgroundableTask("Testing connection...", project) {
    val result = service.testConnection()
    invokeLater { showResult(result) }
}
```

### Rule 5: Background Polling Uses Supervised CoroutineScope

Long-running polling is tied to the Project lifecycle and uses `SupervisorJob` so one failure does not cancel sibling coroutines.

```kotlin
class BuildMonitorService(project: Project) {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("BuildMonitor")
    )

    fun startPolling() {
        scope.launch {
            while (isActive) {
                val result = bambooService.getLatestBuild(planKey)
                withContext(Dispatchers.EDT) { updateUI(result) }
                delay(pollingIntervalMs)
            }
        }
    }
}
```

## Threading Pattern Summary

```mermaid
flowchart LR
    subgraph "User Action"
        BTN["Button Click<br/>(EDT)"]
    end

    subgraph "Background Work"
        BG["launch(IO)"]
        API["API Call"]
        PROC["Process Result"]
    end

    subgraph "UI Update"
        EDT2["withContext(EDT)"]
        UPD["Update Panel"]
    end

    BTN --> BG --> API --> PROC --> EDT2 --> UPD

    style BTN fill:#2d4a22,stroke:#6a9955,color:#d4d4d4
    style BG fill:#264f78,stroke:#569cd6,color:#d4d4d4
    style API fill:#264f78,stroke:#569cd6,color:#d4d4d4
    style PROC fill:#264f78,stroke:#569cd6,color:#d4d4d4
    style EDT2 fill:#2d4a22,stroke:#6a9955,color:#d4d4d4
    style UPD fill:#2d4a22,stroke:#6a9955,color:#d4d4d4
```

## SmartPoller

The plugin uses `SmartPoller` (in `:core`) for all background polling (build status, queue position, quality data). It implements activity-aware polling with several optimizations:

### Backoff Strategy
- **Exponential backoff**: Interval multiplied by 1.5x (build polling) or 2x (queue polling) on consecutive unchanged responses
- **Jitter**: +/-10% random jitter on each interval to prevent thundering herd across multiple pollers
- **Maximum interval cap**: Backoff is capped at a configurable maximum (e.g., 60s for builds, 120s for queue)

### Visibility Gating
- **Hidden tab penalty**: When the tool window tab is not visible, polling interval is multiplied by 4x
- **Reset on visibility**: When the tab becomes visible again, the interval resets to the base value and an immediate poll is triggered

### Activity Awareness
- Polls reset to base interval when the user interacts with the relevant tab
- Polling stops entirely when the project is disposed or the IDE is in power-save mode

```kotlin
class SmartPoller(
    private val baseIntervalMs: Long,
    private val backoffMultiplier: Double = 1.5,
    private val maxIntervalMs: Long = 60_000,
    private val hiddenMultiplier: Int = 4,
    private val jitterPercent: Double = 0.10
) {
    fun start(scope: CoroutineScope, poll: suspend () -> Boolean)
    fun resetInterval()
    fun setVisible(visible: Boolean)
    fun stop()
}
```

## Agent Threading Patterns

The `:agent` module introduces additional threading considerations due to the ReAct loop, delegation, and JCEF browser integration.

### Agent ReAct Loop

The core agent loop runs entirely on `Dispatchers.IO` via a `CoroutineScope`. Each iteration makes an LLM call and optionally executes a tool.

```kotlin
// SingleAgentSession.execute() — suspend function, max 50 iterations
suspend fun execute(userMessage: String): AgentResult = withContext(Dispatchers.IO) {
    repeat(MAX_ITERATIONS) { iteration ->
        val response = llmClient.chat(messages)  // suspend, IO
        val toolCall = parseToolCall(response)
        if (toolCall != null) {
            val result = toolRegistry.execute(toolCall)  // suspend, IO
            // result fed back into conversation
        }
    }
}
```

### Worker Delegation

`WorkerSession` is a scoped ReAct loop (max 10 iterations) spawned by the `delegate_task` tool. Workers run as child coroutines with parent Job cancellation support -- cancelling the parent `SingleAgentSession` cancels all workers.

### Plan Approval (Non-Blocking)

Plan approval uses `suspendCancellableCoroutine` to pause the agent loop without blocking any thread. The JCEF UI calls the continuation when the user approves or revises.

```kotlin
val approved = suspendCancellableCoroutine<Boolean> { cont ->
    // JCEF bridge callback resumes the coroutine
    showPlanApprovalUI(plan) { userApproved -> cont.resume(userApproved) }
}
```

### Token Reconciliation

Token reconciliation (ContextManager calibrating heuristic estimates with the API's actual `usage.prompt_tokens`) happens synchronously after each LLM response within the same IO coroutine. No additional threading concern.

### LLM-Powered Compression

`compressWithLlm()` is a suspend function that calls the Sourcegraph LLM API to summarize large tool results. Triggered by `BudgetEnforcer` when token usage exceeds the COMPRESS threshold.

### Budget Enforcement Actions

| Threshold | Action | Thread Impact |
|---|---|---|
| COMPRESS | Compress oldest tool results via LLM | Additional IO coroutine for LLM call |
| NUDGE | Inject "wrap up" message into conversation | No thread impact (message append) |
| STRONG_NUDGE | Inject stronger "must finish" message | No thread impact (message append) |
| TERMINATE | Stop the ReAct loop | Coroutine cancellation |

### JCEF Browser Thread

The JCEF chat panel runs its JavaScript on the Chromium browser thread (CEF thread). Communication between Kotlin and JavaScript uses `JBCefJSQuery` callbacks which marshal data to the EDT. This is safe because JCEF handles the thread bridging internally.

## Common Anti-Patterns

| Anti-Pattern | Problem | Fix |
|---|---|---|
| `runBlocking` on EDT | Freezes the IDE | Use `launch(Dispatchers.IO)` |
| `Thread.sleep()` on EDT | Freezes the IDE | Use `delay()` in coroutine |
| `SwingWorker` | Non-structured concurrency | Use `CoroutineScope` with `SupervisorJob` |
| Direct `Thread()` creation | Unmanaged lifecycle | Use coroutine scope tied to Project |
| Writing PSI/Document off EDT | `IncorrectOperationException` | Use `WriteCommandAction` |
| Updating Swing off EDT | Visual glitches, race conditions | Use `withContext(Dispatchers.EDT)` |
