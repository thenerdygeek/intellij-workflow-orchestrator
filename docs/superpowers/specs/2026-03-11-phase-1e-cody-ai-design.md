# Phase 1E: Cody AI + Fixes — Design Specification

> **Date:** 2026-03-11
> **Status:** Draft
> **Gate 5 Milestone:** AI-powered code fixes directly in the editor
> **Prerequisite:** Phase 1D (SonarQube integration provides issue markers that Cody fixes)

---

## 1. Scope

Read-write AI integration via Sourcegraph Cody Enterprise (self-hosted). Eight features:

| # | Feature | Surface |
|---|---------|---------|
| 30 | Cody Agent Manager (spawn, JSON-RPC, lifecycle) | Background service |
| 31 | CodyEditService (editCommands/code + workspace/edit) | Service layer |
| 32 | "Fix with Cody" gutter action | Editor gutter icon |
| 33 | CodyIntentionAction (Alt+Enter "Ask Cody to fix") | Quick-fix menu |
| 34 | CodyEditApplier (diff preview + accept/reject) | Editor diff panel |
| 35 | "Cover this branch" (test generation) | Gutter action + dialog |
| 36 | Commit message generation | VCS commit dialog |
| 37 | Spring-aware context enrichment | Context gathering |

**Out of scope:** Autocomplete, chat UI panel, code search, Cody commands beyond /fix and /test.

---

## 2. Module Architecture

New `:cody` Gradle module following the established pattern (`:jira`, `:bamboo`, `:sonar`).

**Note on master spec divergence:** The master spec (Section 4.3) places the AI layer in `core/ai/`. This design intentionally deviates to a separate `:cody` module for consistency with all other feature modules and to keep `:core` lean. The dependency rule is unchanged: `:cody` → `:core` only, no sibling dependencies.

**Prerequisite contract (Phase 1D):** The `:sonar` module must emit `WorkflowEvent.QualityGateResult` and `WorkflowEvent.CoverageUpdated` events, and the `SonarIssueAnnotator` must produce `ExternalAnnotator` annotations. These are defined in the Phase 1D plan.

```
cody/
├── build.gradle.kts
└── src/
    ├── main/kotlin/com/workflow/orchestrator/cody/
    │   ├── agent/                    # Agent process management
    │   │   ├── CodyAgentManager.kt   # Spawns & manages Node.js process
    │   │   ├── CodyAgentProtocol.kt  # JSON-RPC method interfaces (LSP4J)
    │   │   ├── CodyAgentClient.kt    # Server-to-client request handlers
    │   │   ├── CodyDocumentSyncListener.kt  # EditorFactoryListener for didOpen/didClose
    │   │   └── CodyDocumentChangeListener.kt # DocumentListener for didChange (debounced)
    │   │
    │   ├── protocol/                 # Protocol data classes (Gson-annotated for LSP4J)
    │   │   ├── AgentModels.kt        # ClientInfo, ExtensionConfiguration, ClientCapabilities, ServerInfo, AuthStatus
    │   │   ├── EditModels.kt         # EditTask, WorkspaceEditParams, TextEdit, EditCommandsCodeParams
    │   │   ├── ChatModels.kt         # ChatSubmitParams, ChatMessage, ChatResponse, TranscriptMessage
    │   │   └── DocumentModels.kt     # ProtocolTextDocument, Range, Position, TextDocumentIdentifier
    │   │
    │   ├── service/                  # High-level capabilities
    │   │   ├── CodyEditService.kt    # editCommands/code → workspace/edit lifecycle
    │   │   ├── CodyChatService.kt    # chat/new → chat/submitMessage (for commit msgs)
    │   │   └── CodyContextService.kt # Spring-aware context gathering via PSI
    │   │
    │   ├── editor/                   # IntelliJ editor integration
    │   │   ├── CodyGutterAction.kt   # "Fix with Cody" gutter icon on Sonar markers
    │   │   ├── CodyIntentionAction.kt # Alt+Enter quick-fix
    │   │   ├── CodyEditApplier.kt    # Applies workspace/edit diffs to editor
    │   │   └── CodyTestGenerator.kt  # "Cover this branch" action
    │   │
    │   └── vcs/                      # VCS integration
    │       └── CodyCommitMessageHandlerFactory.kt  # Commit message generation
    │
    └── test/kotlin/com/workflow/orchestrator/cody/
        ├── agent/                    # Agent manager tests (mock process)
        ├── protocol/                 # Protocol serialization tests
        ├── service/                  # Service logic tests
        └── editor/                   # Editor integration logic tests
```

**Dependency rule:** `:cody` depends on `:core` only. Reads Sonar issue data from `SonarDataService` (Phase 1D) via `:core` EventBus events, not direct module dependency. The `:sonar` module emits `WorkflowEvent.CoverageUpdated` and `QualityGateResult` — the `:cody` module listens for these to know which files have issues.

**Cross-module data flow for "Fix with Cody":**
The gutter action needs Sonar issue data. Rather than depending on `:sonar`, the `:cody` module reads issue context from the `ExternalAnnotator` annotations already present in the editor (via IntelliJ's `AnnotationHolder` API). This keeps modules decoupled.

---

## 3. Agent Process Management

### 3.1 CodyAgentManager

Project-level service (`@Service(Service.Level.PROJECT)`) that owns the Cody Agent Node.js subprocess lifecycle.

```kotlin
@Service(Service.Level.PROJECT)
class CodyAgentManager(private val project: Project) : Disposable {

    private var process: Process? = null
    private var server: CodyAgentServer? = null   // LSP4J remote proxy
    private var client: CodyAgentClient? = null    // Our handler for server→client calls
    private val _state = MutableStateFlow<AgentState>(AgentState.Stopped)
    val state: StateFlow<AgentState> = _state.asStateFlow()

    sealed class AgentState {
        object Stopped : AgentState()
        object Starting : AgentState()
        data class Running(val serverInfo: ServerInfo) : AgentState()
        data class Error(val message: String) : AgentState()
    }

    suspend fun ensureRunning(): CodyAgentServer
    suspend fun restart()
    fun isRunning(): Boolean
    override fun dispose()  // kills process

    companion object {
        fun getInstance(project: Project): CodyAgentManager =
            project.service<CodyAgentManager>()
    }
}
```

**Lifecycle:**
1. **Lazy start** — agent is not started at IDE startup. First call to `ensureRunning()` triggers spawn.
2. **Spawn** — locates Node.js binary + agent `index.js`, starts process with `ProcessBuilder`.
3. **Initialize** — sends `initialize` JSON-RPC with `ClientInfo`, `ExtensionConfiguration` (token from CredentialStore), and `ClientCapabilities`.
4. **Health monitoring** — if process exits unexpectedly, state transitions to `Error`. Next `ensureRunning()` call triggers restart.
5. **Dispose** — sends `shutdown` request, then destroys process.

### 3.2 Agent Binary Resolution

**Strategy:** User-configured path with auto-detection fallback.

```
Resolution order:
1. PluginSettings.codyAgentPath (user override)
2. System PATH lookup for "cody-agent" binary
3. Bundled fallback: <plugin-data-dir>/cody-agent/index.js + node binary
```

For enterprise environments where npm access is restricted, the user sets the path in settings. The plugin does NOT auto-download from the internet.

**Node.js binary:** The plugin assumes `node` is available on PATH. If not found, an error notification directs the user to install Node.js or configure the agent path. This avoids bundling large platform-specific Node binaries.

**Process environment:** The `ProcessBuilder` inherits the current process environment (includes `HTTP_PROXY`, `HTTPS_PROXY`, `NO_PROXY`, `NODE_OPTIONS`). Additionally sets:
- `CODY_DEBUG=true` when IDE is in debug mode
- `CODY_AGENT_DEBUG_REMOTE=true` when TCP mode is enabled (dev only)

### 3.3 JSON-RPC Transport

Uses `org.eclipse.lsp4j:org.eclipse.lsp4j.jsonrpc` for the transport layer. LSP4J provides:
- Content-Length framed JSON-RPC 2.0 over stdin/stdout
- `@JsonRequest` / `@JsonNotification` annotation-driven method dispatch
- `Launcher` builder for bidirectional communication
- Automatic `CompletableFuture`-based request/response correlation

```kotlin
val launcher = Launcher.Builder<CodyAgentServer>()
    .setInput(process.inputStream)
    .setOutput(process.outputStream)
    .setLocalService(client)               // handles server→client requests
    .setRemoteInterface(CodyAgentServer::class.java)
    .configureGson { it.registerTypeAdapterFactory(sealedClassAdapter) }
    .create()

server = launcher.remoteProxy
launcher.startListening()  // background thread reads stdin
```

### 3.4 CodyAgentServer Interface

Defines all client-to-server JSON-RPC methods:

```kotlin
interface CodyAgentServer {

    @JsonRequest("initialize")
    fun initialize(params: ClientInfo): CompletableFuture<ServerInfo>

    @JsonNotification("initialized")
    fun initialized()

    @JsonRequest("shutdown")
    fun shutdown(): CompletableFuture<Unit?>

    @JsonNotification("exit")
    fun exit()

    // --- Edit Commands ---
    @JsonRequest("editCommands/code")
    fun editCommandsCode(params: EditCommandsCodeParams): CompletableFuture<EditTask>

    @JsonRequest("editTask/accept")
    fun editTaskAccept(params: EditTaskParams): CompletableFuture<Unit?>

    @JsonRequest("editTask/undo")
    fun editTaskUndo(params: EditTaskParams): CompletableFuture<Unit?>

    @JsonRequest("editTask/cancel")
    fun editTaskCancel(params: EditTaskParams): CompletableFuture<Unit?>

    // --- Chat ---
    @JsonRequest("chat/new")
    fun chatNew(): CompletableFuture<String>  // returns chat ID

    @JsonRequest("chat/submitMessage")
    fun chatSubmitMessage(params: ChatSubmitParams): CompletableFuture<ChatResponse>

    // --- Document Sync ---
    @JsonNotification("textDocument/didOpen")
    fun textDocumentDidOpen(params: ProtocolTextDocument)

    @JsonNotification("textDocument/didChange")
    fun textDocumentDidChange(params: ProtocolTextDocument)

    @JsonNotification("textDocument/didFocus")
    fun textDocumentDidFocus(params: TextDocumentIdentifier)

    @JsonNotification("textDocument/didClose")
    fun textDocumentDidClose(params: ProtocolTextDocument)

    // --- Code Actions ---
    @JsonRequest("codeActions/provide")
    fun codeActionsProvide(params: CodeActionsProvideParams): CompletableFuture<CodeActionsProvideResult>

    @JsonRequest("codeActions/trigger")
    fun codeActionsTrigger(params: CodeActionsTriggerParams): CompletableFuture<EditTask>

    // --- Commands ---
    @JsonRequest("commands/test")
    fun commandsTest(): CompletableFuture<EditTask>

    @JsonRequest("command/execute")
    fun commandExecute(params: ExecuteCommandParams): CompletableFuture<Any>
}
```

### 3.5 CodyAgentClient Interface

Handles server-to-client requests (agent calls back to the IDE):

```kotlin
class CodyAgentClient(private val project: Project) {

    @JsonRequest("workspace/edit")
    fun workspaceEdit(params: WorkspaceEditParams): CompletableFuture<Boolean> {
        // Dispatches to CodyEditApplier on EDT
    }

    @JsonRequest("textDocument/edit")
    fun textDocumentEdit(params: TextDocumentEditParams): CompletableFuture<Boolean> {
        // Single-file edit variant
    }

    @JsonNotification("editTaskState/didChange")
    fun editTaskStateDidChange(params: EditTask) {
        // Updates edit task state, fires EventBus event
    }

    @JsonNotification("progress/start")
    fun progressStart(params: ProgressStartParams) {
        // Shows IntelliJ progress indicator
    }

    @JsonNotification("progress/report")
    fun progressReport(params: ProgressReportParams) {}

    @JsonNotification("progress/end")
    fun progressEnd(params: ProgressEndParams) {}

    @JsonRequest("window/showMessage")
    fun windowShowMessage(params: ShowMessageParams): CompletableFuture<String?> {
        // Shows IntelliJ notification
    }
}
```

---

## 4. Protocol Data Classes

### 4.1 Agent Initialization

```kotlin
data class ClientInfo(
    val name: String = "WorkflowOrchestrator",
    val version: String,                          // plugin version
    val ideVersion: String? = null,               // e.g. "IC-2025.1"
    val workspaceRootUri: String,                 // file:///path/to/project
    val extensionConfiguration: ExtensionConfiguration,
    val capabilities: ClientCapabilities
)

data class ExtensionConfiguration(
    val serverEndpoint: String,                   // Sourcegraph enterprise URL
    val accessToken: String,                      // from CredentialStore
    val customHeaders: Map<String, String> = emptyMap(),
    val codebase: String? = null
)

data class ClientCapabilities(
    val chat: String = "streaming",
    val edit: String = "enabled",
    val editWorkspace: String = "enabled",
    val showDocument: String = "enabled",
    val codeActions: String = "enabled",
    val codeLenses: String = "none",
    val completions: String = "none",            // out of scope
    val git: String = "enabled",
    val globalState: String = "server-managed",
    val secrets: String = "client-managed"
)

data class ServerInfo(
    val name: String,
    val authenticated: Boolean?,
    val authStatus: AuthStatus?
)

data class AuthStatus(
    val endpoint: String,
    val authenticated: Boolean,
    val username: String,
    val displayName: String?,
    val siteVersion: String
)
```

### 4.2 Edit Protocol

```kotlin
data class EditCommandsCodeParams(
    val instruction: String,                      // natural language instruction
    val model: String? = null,                    // LLM model override
    val mode: String = "edit",                    // "edit" | "insert"
    val range: Range? = null                      // target code range
)

data class EditTask(
    val id: String,
    val state: EditTaskState,                     // Idle|Working|Applying|Applied|Finished|Error
    val error: CodyError? = null,
    val selectionRange: Range? = null,
    val instruction: String? = null
)

enum class EditTaskState {
    Idle, Working, Inserting, Applying, Applied, Finished, Error, Pending
}

data class EditTaskParams(val id: String)

data class WorkspaceEditParams(
    val operations: List<WorkspaceEditOperation>,
    val metadata: WorkspaceEditMetadata? = null
)

// Discriminated union via "type" field
sealed class WorkspaceEditOperation {
    data class CreateFile(val uri: String, val textContents: String) : WorkspaceEditOperation()
    data class EditFile(val uri: String, val edits: List<TextEdit>) : WorkspaceEditOperation()
}

sealed class TextEdit {
    data class Replace(val range: Range, val value: String) : TextEdit()
    data class Insert(val position: Position, val value: String) : TextEdit()
    data class Delete(val range: Range) : TextEdit()
}

data class Range(val start: Position, val end: Position)
data class Position(val line: Int, val character: Int)
```

### 4.3 Chat Protocol

```kotlin
data class ChatSubmitParams(
    val id: String,                               // chat session ID
    val message: ChatMessage
)

data class ChatMessage(
    val command: String = "submit",
    val text: String,
    val submitType: String = "user",
    val addEnhancedContext: Boolean = true,
    val contextFiles: List<ContextFile> = emptyList()
)

data class ChatResponse(
    val type: String,                             // "transcript"
    val messages: List<TranscriptMessage>
)

data class TranscriptMessage(
    val speaker: String,                          // "human" | "assistant"
    val text: String?
)

data class ContextFile(
    val uri: String,
    val range: Range? = null
)
```

### 4.4 Document Sync

```kotlin
data class ProtocolTextDocument(
    val uri: String,
    val content: String? = null,
    val selection: Range? = null,
    val visibleRange: Range? = null
)

data class TextDocumentIdentifier(val uri: String)
```

---

## 5. Service Layer

### 5.1 CodyEditService

Orchestrates the full edit lifecycle: gather context → send instruction → receive edits → show diff → accept/reject.

```kotlin
class CodyEditService(private val project: Project) {

    suspend fun requestFix(
        filePath: String,
        range: Range,
        instruction: String,
        contextFiles: List<ContextFile> = emptyList()
    ): EditTask

    suspend fun requestTestGeneration(
        filePath: String,
        targetRange: Range,
        existingTestFile: String? = null
    ): EditTask

    suspend fun acceptEdit(taskId: String)
    suspend fun undoEdit(taskId: String)
    suspend fun cancelEdit(taskId: String)
}
```

**Edit flow:**
1. `requestFix()` calls `ensureRunning()` on `CodyAgentManager`
2. Sends `textDocument/didFocus` for the target file
3. Sends `editCommands/code` with instruction and context
4. Agent processes via enterprise LLM, returns `EditTask` with state `Working`
5. Agent calls back `workspace/edit` on `CodyAgentClient` with file diffs
6. Agent sends `editTaskState/didChange` with state `Applied`
7. `CodyEditApplier` shows diff preview in editor
8. User accepts → `editTask/accept` sent; or rejects → `editTask/undo` sent

### 5.2 CodyChatService

Thin wrapper for commit message generation. No full chat UI.

```kotlin
class CodyChatService(private val project: Project) {

    suspend fun generateCommitMessage(diff: String): String? {
        val agent = CodyAgentManager.getInstance(project).ensureRunning()
        val chatId = agent.chatNew().await()
        val prompt = buildCommitMessagePrompt(diff)
        val response = agent.chatSubmitMessage(
            ChatSubmitParams(id = chatId, message = ChatMessage(text = prompt))
        ).await()
        return response.messages.lastOrNull { it.speaker == "assistant" }?.text
    }

    private fun buildCommitMessagePrompt(diff: String): String =
        """Generate a concise git commit message for this diff.
           |Use conventional commits format (feat/fix/refactor/etc).
           |One line summary, optional body.
           |
           |```diff
           |$diff
           |```""".trimMargin()
}
```

### 5.3 CodyContextService

Gathers rich context for Cody requests using IntelliJ PSI and Spring APIs.

```kotlin
class CodyContextService(private val project: Project) {

    fun gatherFixContext(
        file: PsiFile,
        issueRange: Range,
        issueMessage: String
    ): FixContext

    fun gatherTestContext(
        file: PsiFile,
        targetRange: Range
    ): TestContext

    data class FixContext(
        val instruction: String,          // built from issue + context
        val contextFiles: List<ContextFile>
    )

    data class TestContext(
        val instruction: String,
        val contextFiles: List<ContextFile>,
        val existingTestFile: String?     // matched by convention
    )
}
```

**Context gathering strategy:**

1. **Sonar issue context:** Issue type, severity, rule key, message — extracted from editor annotations.
2. **Surrounding code:** The full containing method/class via PSI traversal.
3. **Spring context (optional):** If `com.intellij.spring` plugin is available, detect injected beans, `@Transactional` boundaries, repository patterns. Loaded via reflection to avoid hard dependency.
4. **Test style matching:** For "Cover this branch", find existing test files using naming conventions (`*Test.kt`, `*Spec.kt`), extract assertion style (JUnit 5 / AssertJ / Kotest), and include as context for style-consistent generation.
5. **File dependencies:** Import statements parsed via PSI to include key dependency files.

**Spring integration is optional.** If Spring plugin is not installed, context gathering skips Spring-specific enrichment. This uses `PluginManager.isPluginInstalled()` check at runtime.

---

## 6. Editor Integration

### 6.1 CodyGutterAction — "Fix with Cody"

A `GutterIconNavigationHandler` that appears as an additional gutter icon on lines that have Sonar issue annotations (bugs, vulnerabilities).

```kotlin
class CodyGutterAction : LineMarkerProvider {
    // Adds a "Fix with Cody" gutter icon on lines with Sonar issues.
    // Icon: AI/wand icon in the gutter, appears alongside existing Sonar markers.
    // Click → gathers context via CodyContextService → sends fix request via CodyEditService.
    // Only visible when CodyAgentManager is configured (sourcegraphUrl + token set).
}
```

**Activation condition:** Only shows when:
1. `PluginSettings.getInstance(project).state.sourcegraphUrl` is non-blank
2. `CredentialStore().hasToken(ServiceType.SOURCEGRAPH)` is true (CredentialStore is a plain utility class, instantiated directly — it wraps `PasswordSafe.instance` internally)
3. The line has a Sonar issue annotation (detected via `AnnotationHolder`)

### 6.2 CodyIntentionAction — Alt+Enter Quick-Fix

An `IntentionAction` registered for Java/Kotlin files that appears in the Alt+Enter menu when the caret is on a line with a Sonar issue or compiler error.

```kotlin
class CodyIntentionAction : IntentionAction {
    override fun getText() = "Ask Cody to fix"
    override fun getFamilyName() = "Cody AI"
    override fun isAvailable(project: Project, editor: Editor, file: PsiFile): Boolean
    override fun invoke(project: Project, editor: Editor, file: PsiFile)
    // Same flow as CodyGutterAction but triggered from Alt+Enter context.
}
```

### 6.3 CodyEditApplier — Diff Preview

When the agent sends `workspace/edit`, this component shows a diff preview before applying changes.

```kotlin
class CodyEditApplier(private val project: Project) {

    fun showDiffPreview(
        editTask: EditTask,
        operations: List<WorkspaceEditOperation>
    )
    // Opens a SimpleDiffRequest in an editor tab showing before/after.
    // "Accept" button → calls CodyEditService.acceptEdit(taskId)
    // "Reject" button → calls CodyEditService.undoEdit(taskId)
    // Applied via WriteCommandAction.runWriteCommandAction() for undo support.
}
```

**Diff preview approach:** Uses IntelliJ's built-in `DiffManager.getInstance().showDiff()` with a `SimpleDiffRequest` containing `DocumentContentImpl` for before/after content. This provides a familiar diff UI with syntax highlighting.

**Edit application:** Uses `WriteCommandAction` to modify documents on the EDT, which:
- Integrates with IntelliJ's undo/redo system
- Handles document locking properly
- Shows changes in the editor immediately

### 6.4 CodyTestGenerator — "Cover this branch"

Gutter action on uncovered lines (from Phase 1D coverage markers) that generates tests.

```kotlin
class CodyTestGenerator : LineMarkerProvider {
    // Adds "Cover with Cody" gutter icon on uncovered/partially-covered lines.
    // Click → CodyContextService.gatherTestContext() → CodyEditService.requestTestGeneration()
    // Agent generates test, applies to test file via workspace/edit.
    // Shows diff preview for the generated test file.
}
```

**Test file resolution:** Follows standard conventions:
- `src/main/.../Foo.kt` → `src/test/.../FooTest.kt`
- If test file exists, opens it and inserts new test method
- If test file doesn't exist, creates it with proper imports/class structure

### 6.5 CodyCommitMessageHandlerFactory — VCS Integration

Adds a "Generate with Cody" action to the commit dialog, following the same `VcsCheckinHandlerFactory` pattern already used by `CommitMessagePrefixHandlerFactory` in the `:jira` module.

```kotlin
class CodyCommitMessageHandlerFactory : VcsCheckinHandlerFactory(GitVcs.getKey()) {
    // Creates a CheckinHandler that:
    // 1. Adds a "Generate with Cody" action to the commit dialog's action toolbar
    // 2. On click: gets staged diff via Git4Idea
    // 3. Sends to CodyChatService.generateCommitMessage(diff)
    // 4. Populates the commit message field
}
```

**Note (design simplification):** The master spec lists a separate `CodyEditPreview.kt` file. This design merges diff preview logic into `CodyEditApplier.kt` since they share the same lifecycle and there is no use case for preview without application.

---

## 7. Document Synchronization

The agent needs to know about open files to provide accurate context. An `EditorFactoryListener` sends document sync notifications.

```kotlin
class CodyDocumentSyncListener : EditorFactoryListener {
    override fun editorCreated(event: EditorFactoryEvent) {
        // Send textDocument/didOpen with file URI and content
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        // Send textDocument/didClose
    }
}

class CodyDocumentChangeListener : DocumentListener {
    // Registered per open editor (not globally via BulkFileListener).
    // Debounced (300ms) textDocument/didChange notifications.
    // Only fires for files in the project scope.
    // Registered/unregistered by CodyDocumentSyncListener when editors open/close.
}
```

**Focus tracking:** A `FileEditorManagerListener` sends `textDocument/didFocus` when the user switches editor tabs.

---

## 8. Settings Extensions

New settings fields in `PluginSettings.State` (**requires modification of `:core` module**):

```kotlin
// Cody agent configuration (added to core/settings/PluginSettings.kt)
var codyAgentPath by string("")          // Override path to agent binary
var codyEnabled by property(true)         // Feature toggle for Cody AI features
```

Existing fields already handle Sourcegraph:
- `sourcegraphUrl` — Sourcegraph enterprise server URL (already in PluginSettings)
- `ServiceType.SOURCEGRAPH` in `CredentialStore` — access token (already supported)

---

## 9. WorkflowEvent Extensions

New events added to the sealed class:

```kotlin
// From :cody — Note: master spec defines CodyEditReady(filePath, diffs: List<TextEdit>).
// This design uses taskId + state instead, as the diffs are applied via CodyEditApplier
// before the event fires. Consumers need to know "an edit happened" not "what the edit was".
data class CodyEditReady(
    val taskId: String,
    val filePath: String,
    val accepted: Boolean           // true = user accepted, false = user rejected
) : WorkflowEvent()
```

---

## 10. Error Handling

| Scenario | Handling |
|----------|----------|
| Agent binary not found | Notification with link to settings. State → `Error`. |
| Node.js not on PATH | Notification: "Node.js required for Cody AI features". |
| Auth failure (401) | Notification: "Sourcegraph token invalid". State → `Error`. |
| Agent process crash | State → `Error`. Next `ensureRunning()` triggers auto-restart. |
| Edit timeout (30s) | Cancel edit task. Notification: "Cody edit timed out". |
| Network failure to Sourcegraph | Agent handles internally. Client sees error in edit task state. |
| Spring plugin not installed | Context gathering skips Spring enrichment. No error. |

---

## 11. plugin.xml Registrations

```xml
<!-- Notification Group -->
<notificationGroup id="workflow.cody" displayType="BALLOON"/>

<!-- Project Service -->
<projectService
    serviceImplementation="com.workflow.orchestrator.cody.agent.CodyAgentManager"/>

<!-- Gutter Actions -->
<codeInsight.lineMarkerProvider
    language="JAVA"
    implementationClass="com.workflow.orchestrator.cody.editor.CodyGutterAction"/>
<codeInsight.lineMarkerProvider
    language="kotlin"
    implementationClass="com.workflow.orchestrator.cody.editor.CodyGutterAction"/>
<codeInsight.lineMarkerProvider
    language="JAVA"
    implementationClass="com.workflow.orchestrator.cody.editor.CodyTestGenerator"/>
<codeInsight.lineMarkerProvider
    language="kotlin"
    implementationClass="com.workflow.orchestrator.cody.editor.CodyTestGenerator"/>

<!-- Intention Action (Alt+Enter) -->
<intentionAction>
    <language>JAVA</language>
    <className>com.workflow.orchestrator.cody.editor.CodyIntentionAction</className>
    <category>Cody AI</category>
</intentionAction>
<intentionAction>
    <language>kotlin</language>
    <className>com.workflow.orchestrator.cody.editor.CodyIntentionAction</className>
    <category>Cody AI</category>
</intentionAction>

<!-- Document Sync -->
<editorFactoryListener
    implementation="com.workflow.orchestrator.cody.agent.CodyDocumentSyncListener"/>

<!-- Commit Message -->
<vcsCheckinHandlerFactory
    implementation="com.workflow.orchestrator.cody.vcs.CodyCommitMessageHandlerFactory"/>
```

**Optional Spring dependency:**
```xml
<depends optional="true" config-file="cody-spring.xml">com.intellij.spring</depends>
```

Where `cody-spring.xml` registers the Spring-aware context provider only when the Spring plugin is present.

---

## 12. Testing Strategy

| Layer | What | How |
|-------|------|-----|
| Protocol serialization | All data classes serialize/deserialize correctly | JSON fixtures + kotlinx.serialization |
| Agent manager | Spawn, initialize, shutdown lifecycle | Mock Process + mock stdin/stdout |
| CodyEditService | Edit request → diff application flow | Mock CodyAgentServer (LSP4J interface) |
| CodyChatService | Commit message generation | Mock CodyAgentServer |
| CodyContextService | Context gathering with/without Spring | PSI test fixtures, mock PluginManager |
| CodyEditApplier | Diff application logic | Unit test on TextEdit → Document transformation |
| Gutter actions | Availability checks | Mock editor state, annotation presence |
| CodyIntentionAction | isAvailable + invoke logic | Mock project/editor/file |

**No integration tests with real Cody Agent.** All tests mock the JSON-RPC boundary. The protocol data classes ensure wire compatibility.

---

## 13. Data Flow Summary

**"Fix with Cody" flow:**
1. User sees Sonar issue gutter marker → clicks "Fix with Cody" (or Alt+Enter)
2. `CodyGutterAction` → `CodyContextService.gatherFixContext()` (PSI + annotations + optional Spring context)
3. `CodyEditService.requestFix()` → `CodyAgentManager.ensureRunning()` (lazy spawn if needed)
4. Agent receives `editCommands/code` → processes via enterprise LLM
5. Agent calls `workspace/edit` on `CodyAgentClient` with diffs
6. `CodyEditApplier.showDiffPreview()` → IntelliJ diff viewer opens
7. User clicks Accept → `editTask/accept` → diff applied via `WriteCommandAction`
8. EventBus emits `CodyEditReady(taskId, filePath, "Applied")`

**"Cover this branch" flow:**
1. User sees uncovered line gutter marker → clicks "Cover with Cody"
2. `CodyTestGenerator` → `CodyContextService.gatherTestContext()` (finds existing test file, matches style)
3. `CodyEditService.requestTestGeneration()` → `editCommands/code` with test instruction
4. Agent generates test → `workspace/edit` creates/modifies test file
5. Diff preview shows generated test → user accepts/rejects

**Commit message generation:**
1. User opens commit dialog → clicks "Generate with Cody"
2. `CodyCommitMessageProvider` gets staged diff via `Git.diff()`
3. `CodyChatService.generateCommitMessage(diff)` → `chat/new` + `chat/submitMessage`
4. Response text populates commit message field

---

## 14. Build Configuration

```kotlin
// cody/build.gradle.kts — Submodule for Cody AI integration.
// Uses the MODULE variant; depends on :core.

plugins {
    alias(libs.plugins.kotlin)
    id("org.jetbrains.intellij.platform.module")
}

kotlin {
    jvmToolchain(providers.gradleProperty("javaVersion").get().toInt())
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
        bundledPlugin("Git4Idea")
    }

    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j.jsonrpc:0.23.1")
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
}
```

**Serialization note:** LSP4J uses Gson internally for JSON-RPC serialization. All protocol data classes in `protocol/` use Gson annotations (`@SerializedName`) for field mapping. The `kotlinx.serialization` dependency is NOT needed in this module — Gson handles all wire format concerns. This differs from `:jira`, `:bamboo`, `:sonar` which use kotlinx.serialization for their REST API DTOs.
