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

## Common Anti-Patterns

| Anti-Pattern | Problem | Fix |
|---|---|---|
| `runBlocking` on EDT | Freezes the IDE | Use `launch(Dispatchers.IO)` |
| `Thread.sleep()` on EDT | Freezes the IDE | Use `delay()` in coroutine |
| `SwingWorker` | Non-structured concurrency | Use `CoroutineScope` with `SupervisorJob` |
| Direct `Thread()` creation | Unmanaged lifecycle | Use coroutine scope tied to Project |
| Writing PSI/Document off EDT | `IncorrectOperationException` | Use `WriteCommandAction` |
| Updating Swing off EDT | Visual glitches, race conditions | Use `withContext(Dispatchers.EDT)` |
