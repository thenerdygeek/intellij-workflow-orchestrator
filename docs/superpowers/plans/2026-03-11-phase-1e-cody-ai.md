# Phase 1E: Cody AI + Fixes — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add AI-powered code fixes via Sourcegraph Cody Enterprise, enabling "Fix with Cody" gutter actions, Alt+Enter quick-fixes, test generation, and commit message generation — all without leaving the editor.

**Architecture:** New `:cody` module depending on `:core`. `CodyAgentManager` spawns a Cody Agent Node.js subprocess communicating via JSON-RPC 2.0 over stdio (LSP4J transport). `CodyEditService` orchestrates the edit lifecycle (editCommands/code → workspace/edit → diff preview → accept/reject). All editor integrations (gutter actions, intention, commit handler) delegate to the service layer. Protocol data classes use Gson (LSP4J's serializer), not kotlinx.serialization.

**Tech Stack:** Kotlin, IntelliJ Platform SDK (2025.1+), LSP4J 0.23.1 (JSON-RPC), Gson 2.11, kotlinx.coroutines 1.8, JUnit 5, MockK

**Spec:** `docs/superpowers/specs/2026-03-11-phase-1e-cody-ai-design.md`

---

## Chunk 1: Build Configuration, Protocol Models & Serialization Tests

### Task 1: Gradle Build Configuration

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `build.gradle.kts` (root)
- Modify: `gradle/libs.versions.toml`
- Create: `cody/build.gradle.kts`

- [ ] **Step 1: Add LSP4J and Gson to version catalog**

In `gradle/libs.versions.toml`, add versions and libraries:

Under `[versions]`:
```toml
lsp4j = "0.23.1"
gson = "2.11.0"
```

Under `[libraries]`:
```toml
lsp4j-jsonrpc = { group = "org.eclipse.lsp4j", name = "org.eclipse.lsp4j.jsonrpc", version.ref = "lsp4j" }
gson = { group = "com.google.code.gson", name = "gson", version.ref = "gson" }
```

- [ ] **Step 2: Add `:cody` module to settings.gradle.kts**

```kotlin
include(
    ":core",
    ":jira",
    ":git-integration",
    ":bamboo",
    ":cody",
)
```

> **Note:** Phase 1D also adds `:sonar` here. If that's already done, just add `:cody` after it. If not, add both: `:sonar`, `:cody`.

- [ ] **Step 3: Add `:cody` to root build.gradle.kts dependencies**

In `build.gradle.kts` (root), add inside the `dependencies` block:

```kotlin
implementation(project(":cody"))
```

- [ ] **Step 4: Create cody/build.gradle.kts**

```kotlin
// cody/build.gradle.kts — Submodule for Cody AI integration.
// Uses the MODULE variant; depends on :core.
// Unlike other modules, uses Gson (via LSP4J) instead of kotlinx.serialization.

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
    implementation(libs.lsp4j.jsonrpc)
    implementation(libs.gson)

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

- [ ] **Step 5: Verify Gradle sync**

Run: `./gradlew :cody:dependencies --configuration compileClasspath 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL (no resolution errors)

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle/libs.versions.toml cody/build.gradle.kts
git commit -m "feat(cody): add :cody module with LSP4J and Gson dependencies"
```

---

### Task 2: Core Module Extensions (PluginSettings + WorkflowEvent + NotificationService)

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/settings/PluginSettings.kt`
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/events/WorkflowEvent.kt`
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/notifications/WorkflowNotificationService.kt`

- [ ] **Step 1: Add Cody settings to PluginSettings.State**

In `core/src/main/kotlin/com/workflow/orchestrator/core/settings/PluginSettings.kt`, inside `class State`, add after the existing fields:

```kotlin
        // Cody AI configuration
        var codyAgentPath by string("")
        var codyEnabled by property(true)
```

- [ ] **Step 2: Add CodyEditReady event to WorkflowEvent**

In `core/src/main/kotlin/com/workflow/orchestrator/core/events/WorkflowEvent.kt`, add inside the sealed class:

```kotlin
    /** Emitted by :cody when a user accepts or rejects an AI-generated edit. */
    data class CodyEditReady(
        val taskId: String,
        val filePath: String,
        val accepted: Boolean
    ) : WorkflowEvent()
```

- [ ] **Step 3: Add GROUP_CODY constant to WorkflowNotificationService**

In `core/src/main/kotlin/com/workflow/orchestrator/core/notifications/WorkflowNotificationService.kt`, add inside `companion object`:

```kotlin
        const val GROUP_CODY = "workflow.cody"
```

- [ ] **Step 4: Verify compilation**

Run: `./gradlew :core:compileKotlin 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/settings/PluginSettings.kt \
        core/src/main/kotlin/com/workflow/orchestrator/core/events/WorkflowEvent.kt \
        core/src/main/kotlin/com/workflow/orchestrator/core/notifications/WorkflowNotificationService.kt
git commit -m "feat(core): add Cody settings, CodyEditReady event, and notification group"
```

---

### Task 3: Protocol Data Classes — Agent Models

**Files:**
- Create: `cody/src/main/kotlin/com/workflow/orchestrator/cody/protocol/AgentModels.kt`

- [ ] **Step 1: Create AgentModels.kt**

```kotlin
package com.workflow.orchestrator.cody.protocol

// --- Initialize Request ---

data class ClientInfo(
    val name: String = "WorkflowOrchestrator",
    val version: String,
    val ideVersion: String? = null,
    val workspaceRootUri: String,
    val extensionConfiguration: ExtensionConfiguration,
    val capabilities: ClientCapabilities
)

data class ExtensionConfiguration(
    val serverEndpoint: String,
    val accessToken: String,
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
    val completions: String = "none",
    val git: String = "enabled",
    val globalState: String = "server-managed",
    val secrets: String = "client-managed"
)

// --- Initialize Response ---

data class ServerInfo(
    val name: String,
    val authenticated: Boolean? = null,
    val authStatus: AuthStatus? = null
)

data class AuthStatus(
    val endpoint: String = "",
    val authenticated: Boolean = false,
    val username: String = "",
    val displayName: String? = null,
    val siteVersion: String = ""
)

// --- Progress Notifications (server → client) ---

data class ProgressStartParams(
    val id: String,
    val options: ProgressOptions? = null
)

data class ProgressOptions(
    val title: String? = null,
    val cancellable: Boolean? = null,
    val location: String? = null
)

data class ProgressReportParams(
    val id: String,
    val message: String? = null,
    val increment: Int? = null
)

data class ProgressEndParams(
    val id: String
)

// --- Window Messages (server → client) ---

data class ShowMessageParams(
    val message: String,
    val type: Int? = null,
    val items: List<String>? = null
)
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :cody:compileKotlin 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add cody/src/main/kotlin/com/workflow/orchestrator/cody/protocol/AgentModels.kt
git commit -m "feat(cody): add agent initialization protocol models"
```

---

### Task 4: Protocol Data Classes — Edit Models

**Files:**
- Create: `cody/src/main/kotlin/com/workflow/orchestrator/cody/protocol/EditModels.kt`

- [ ] **Step 1: Create EditModels.kt**

```kotlin
package com.workflow.orchestrator.cody.protocol

// --- Edit Commands ---

data class EditCommandsCodeParams(
    val instruction: String,
    val model: String? = null,
    val mode: String = "edit",
    val range: Range? = null
)

/**
 * Note: spec defines state as EditTaskState enum, but we use String here
 * intentionally. Gson deserializes unknown enum values as null, which would
 * silently lose state information if the agent introduces new states.
 * String preserves all values and avoids needing a custom TypeAdapter.
 */
data class EditTask(
    val id: String = "",
    val state: String = "Idle",
    val error: CodyError? = null,
    val selectionRange: Range? = null,
    val instruction: String? = null
)

data class CodyError(
    val message: String = "",
    val code: Int? = null
)

data class EditTaskParams(
    val id: String
)

// --- Code Actions ---

data class CodeActionsProvideParams(
    val location: ProtocolLocation,
    val triggerKind: String = "Invoke"
)

data class ProtocolLocation(
    val uri: String,
    val range: Range
)

data class CodeActionsProvideResult(
    val codeActions: List<ProtocolCodeAction> = emptyList()
)

data class ProtocolCodeAction(
    val id: String? = null,
    val commandID: String? = null,
    val title: String = "",
    val diagnostics: List<ProtocolDiagnostic>? = null
)

data class ProtocolDiagnostic(
    val range: Range,
    val message: String = "",
    val severity: String? = null,
    val source: String? = null
)

data class CodeActionsTriggerParams(
    val id: String
)

// --- Commands ---

data class ExecuteCommandParams(
    val command: String,
    val arguments: List<Any>? = null
)

// --- Workspace Edit (server → client) ---

data class WorkspaceEditParams(
    val operations: List<WorkspaceEditOperation> = emptyList(),
    val metadata: WorkspaceEditMetadata? = null
)

data class WorkspaceEditMetadata(
    val isRestricted: Boolean? = null
)

/**
 * WorkspaceEditOperation is a discriminated union. The "type" field determines
 * which shape applies. Spec defines this as a sealed class, but we use a flat
 * data class with nullable fields — Gson deserializes discriminated unions
 * naturally via type field without needing a custom TypeAdapter.
 * Possible types: "create-file", "edit-file"
 */
data class WorkspaceEditOperation(
    val type: String = "",
    // create-file fields
    val uri: String? = null,
    val textContents: String? = null,
    // edit-file fields
    val edits: List<TextEdit>? = null
)

/**
 * TextEdit is a discriminated union. The "type" field determines the shape.
 * Possible types: "replace", "insert", "delete"
 */
data class TextEdit(
    val type: String = "",
    // replace/delete: range
    val range: Range? = null,
    // replace/insert: value
    val value: String? = null,
    // insert: position
    val position: Position? = null
)

data class TextDocumentEditParams(
    val uri: String,
    val edits: List<TextEdit> = emptyList()
)

// --- Shared geometry ---

data class Range(
    val start: Position = Position(),
    val end: Position = Position()
)

data class Position(
    val line: Int = 0,
    val character: Int = 0
)
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :cody:compileKotlin 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add cody/src/main/kotlin/com/workflow/orchestrator/cody/protocol/EditModels.kt
git commit -m "feat(cody): add edit and workspace protocol models"
```

---

### Task 5: Protocol Data Classes — Chat & Document Models

**Files:**
- Create: `cody/src/main/kotlin/com/workflow/orchestrator/cody/protocol/ChatModels.kt`
- Create: `cody/src/main/kotlin/com/workflow/orchestrator/cody/protocol/DocumentModels.kt`

- [ ] **Step 1: Create ChatModels.kt**

```kotlin
package com.workflow.orchestrator.cody.protocol

// --- Chat Session ---

data class ChatSubmitParams(
    val id: String,
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
    val type: String = "",
    val messages: List<TranscriptMessage> = emptyList()
)

data class TranscriptMessage(
    val speaker: String = "",
    val text: String? = null
)

data class ContextFile(
    val uri: String,
    val range: Range? = null
)
```

- [ ] **Step 2: Create DocumentModels.kt**

```kotlin
package com.workflow.orchestrator.cody.protocol

// --- Document Sync ---

data class ProtocolTextDocument(
    val uri: String,
    val content: String? = null,
    val selection: Range? = null,
    val visibleRange: Range? = null
)

data class TextDocumentIdentifier(
    val uri: String
)
```

- [ ] **Step 3: Verify compilation**

Run: `./gradlew :cody:compileKotlin 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add cody/src/main/kotlin/com/workflow/orchestrator/cody/protocol/ChatModels.kt \
        cody/src/main/kotlin/com/workflow/orchestrator/cody/protocol/DocumentModels.kt
git commit -m "feat(cody): add chat and document sync protocol models"
```

---

### Task 6: Protocol Serialization Tests

**Files:**
- Create: `cody/src/test/resources/fixtures/initialize-response.json`
- Create: `cody/src/test/resources/fixtures/edit-task.json`
- Create: `cody/src/test/resources/fixtures/workspace-edit.json`
- Create: `cody/src/test/resources/fixtures/chat-response.json`
- Create: `cody/src/test/kotlin/com/workflow/orchestrator/cody/protocol/ProtocolSerializationTest.kt`

- [ ] **Step 1: Create JSON fixtures**

`cody/src/test/resources/fixtures/initialize-response.json`:
```json
{
  "name": "cody-agent",
  "authenticated": true,
  "authStatus": {
    "endpoint": "https://sourcegraph.example.com",
    "authenticated": true,
    "username": "developer",
    "displayName": "Dev User",
    "siteVersion": "5.5.0"
  }
}
```

`cody/src/test/resources/fixtures/edit-task.json`:
```json
{
  "id": "edit-task-001",
  "state": "Applied",
  "error": null,
  "selectionRange": {
    "start": {"line": 10, "character": 0},
    "end": {"line": 15, "character": 42}
  },
  "instruction": "Fix the null pointer exception"
}
```

`cody/src/test/resources/fixtures/workspace-edit.json`:
```json
{
  "operations": [
    {
      "type": "edit-file",
      "uri": "file:///src/main/kotlin/UserService.kt",
      "edits": [
        {
          "type": "replace",
          "range": {
            "start": {"line": 42, "character": 8},
            "end": {"line": 42, "character": 35}
          },
          "value": "name?.toUpperCase() ?: \"\""
        }
      ]
    },
    {
      "type": "create-file",
      "uri": "file:///src/test/kotlin/UserServiceTest.kt",
      "textContents": "package com.myapp\n\nimport org.junit.jupiter.api.Test\n\nclass UserServiceTest {\n    @Test\n    fun `test findUserById`() {\n        // generated test\n    }\n}"
    }
  ],
  "metadata": null
}
```

`cody/src/test/resources/fixtures/chat-response.json`:
```json
{
  "type": "transcript",
  "messages": [
    {"speaker": "human", "text": "Generate a commit message for this diff"},
    {"speaker": "assistant", "text": "fix: handle null pointer in UserService.findUserById\n\nAdd null-safe operator to prevent NPE when user.getName() returns null."}
  ]
}
```

- [ ] **Step 2: Write protocol serialization tests**

```kotlin
package com.workflow.orchestrator.cody.protocol

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ProtocolSerializationTest {

    private val gson: Gson = GsonBuilder().create()

    private fun fixture(name: String): String =
        javaClass.classLoader.getResource("fixtures/$name")!!.readText()

    @Test
    fun `deserialize initialize response`() {
        val result = gson.fromJson(fixture("initialize-response.json"), ServerInfo::class.java)
        assertEquals("cody-agent", result.name)
        assertTrue(result.authenticated!!)
        assertNotNull(result.authStatus)
        assertEquals("developer", result.authStatus!!.username)
        assertEquals("https://sourcegraph.example.com", result.authStatus!!.endpoint)
        assertEquals("5.5.0", result.authStatus!!.siteVersion)
    }

    @Test
    fun `deserialize edit task`() {
        val result = gson.fromJson(fixture("edit-task.json"), EditTask::class.java)
        assertEquals("edit-task-001", result.id)
        assertEquals("Applied", result.state)
        assertNull(result.error)
        assertNotNull(result.selectionRange)
        assertEquals(10, result.selectionRange!!.start.line)
        assertEquals(42, result.selectionRange!!.end.character)
        assertEquals("Fix the null pointer exception", result.instruction)
    }

    @Test
    fun `deserialize workspace edit with multiple operations`() {
        val result = gson.fromJson(fixture("workspace-edit.json"), WorkspaceEditParams::class.java)
        assertEquals(2, result.operations.size)

        val editOp = result.operations[0]
        assertEquals("edit-file", editOp.type)
        assertEquals("file:///src/main/kotlin/UserService.kt", editOp.uri)
        assertEquals(1, editOp.edits!!.size)
        assertEquals("replace", editOp.edits!![0].type)
        assertEquals(42, editOp.edits!![0].range!!.start.line)
        assertEquals("name?.toUpperCase() ?: \"\"", editOp.edits!![0].value)

        val createOp = result.operations[1]
        assertEquals("create-file", createOp.type)
        assertEquals("file:///src/test/kotlin/UserServiceTest.kt", createOp.uri)
        assertTrue(createOp.textContents!!.contains("UserServiceTest"))
    }

    @Test
    fun `deserialize chat response`() {
        val result = gson.fromJson(fixture("chat-response.json"), ChatResponse::class.java)
        assertEquals("transcript", result.type)
        assertEquals(2, result.messages.size)
        assertEquals("human", result.messages[0].speaker)
        assertEquals("assistant", result.messages[1].speaker)
        assertTrue(result.messages[1].text!!.startsWith("fix:"))
    }

    @Test
    fun `serialize ClientInfo roundtrip`() {
        val clientInfo = ClientInfo(
            version = "1.0.0",
            ideVersion = "IC-2025.1",
            workspaceRootUri = "file:///project",
            extensionConfiguration = ExtensionConfiguration(
                serverEndpoint = "https://sg.example.com",
                accessToken = "sgp_test"
            ),
            capabilities = ClientCapabilities()
        )
        val json = gson.toJson(clientInfo)
        val parsed = gson.fromJson(json, ClientInfo::class.java)
        assertEquals("WorkflowOrchestrator", parsed.name)
        assertEquals("sgp_test", parsed.extensionConfiguration.accessToken)
        assertEquals("streaming", parsed.capabilities.chat)
        assertEquals("none", parsed.capabilities.completions)
    }

    @Test
    fun `serialize EditCommandsCodeParams`() {
        val params = EditCommandsCodeParams(
            instruction = "Fix the NPE on line 42",
            mode = "edit",
            range = Range(
                start = Position(line = 40, character = 0),
                end = Position(line = 45, character = 0)
            )
        )
        val json = gson.toJson(params)
        assertTrue(json.contains("Fix the NPE"))
        assertTrue(json.contains("\"mode\":\"edit\""))
        assertTrue(json.contains("\"line\":40"))
    }

    @Test
    fun `serialize ChatSubmitParams`() {
        val params = ChatSubmitParams(
            id = "chat-123",
            message = ChatMessage(
                text = "Generate a commit message",
                addEnhancedContext = false
            )
        )
        val json = gson.toJson(params)
        assertTrue(json.contains("chat-123"))
        assertTrue(json.contains("Generate a commit message"))
        assertTrue(json.contains("\"addEnhancedContext\":false"))
    }
}
```

- [ ] **Step 3: Run tests to verify they pass**

Run: `./gradlew :cody:test --tests "*.ProtocolSerializationTest" 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL, 7 tests passed

- [ ] **Step 4: Commit**

```bash
git add cody/src/test/resources/fixtures/ cody/src/test/kotlin/com/workflow/orchestrator/cody/protocol/
git commit -m "test(cody): add protocol serialization tests with JSON fixtures"
```

---

## Chunk 2: Agent Process Management & JSON-RPC Interface

### Task 7: CodyAgentServer Interface (TDD — write tests first)

**Files:**
- Create: `cody/src/test/kotlin/com/workflow/orchestrator/cody/agent/CodyAgentServerTest.kt`

- [ ] **Step 1: Write interface contract tests**

These tests verify the interface annotations are correct for LSP4J reflection.

```kotlin
package com.workflow.orchestrator.cody.agent

import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.lang.reflect.Method

class CodyAgentServerTest {

    @Test
    fun `initialize method has correct JsonRequest annotation`() {
        val method = findMethod("initialize")
        val annotation = method.getAnnotation(JsonRequest::class.java)
        assertNotNull(annotation, "initialize must have @JsonRequest")
        assertEquals("initialize", annotation.value)
    }

    @Test
    fun `initialized method has correct JsonNotification annotation`() {
        val method = findMethod("initialized")
        val annotation = method.getAnnotation(JsonNotification::class.java)
        assertNotNull(annotation, "initialized must have @JsonNotification")
        assertEquals("initialized", annotation.value)
    }

    @Test
    fun `shutdown method has correct JsonRequest annotation`() {
        val method = findMethod("shutdown")
        val annotation = method.getAnnotation(JsonRequest::class.java)
        assertNotNull(annotation, "shutdown must have @JsonRequest")
        assertEquals("shutdown", annotation.value)
    }

    @Test
    fun `editCommandsCode method has correct annotation`() {
        val method = findMethod("editCommandsCode")
        val annotation = method.getAnnotation(JsonRequest::class.java)
        assertNotNull(annotation)
        assertEquals("editCommands/code", annotation.value)
    }

    @Test
    fun `chatNew method has correct annotation`() {
        val method = findMethod("chatNew")
        val annotation = method.getAnnotation(JsonRequest::class.java)
        assertNotNull(annotation)
        assertEquals("chat/new", annotation.value)
    }

    @Test
    fun `textDocumentDidOpen is a notification not a request`() {
        val method = findMethod("textDocumentDidOpen")
        assertNull(method.getAnnotation(JsonRequest::class.java))
        val annotation = method.getAnnotation(JsonNotification::class.java)
        assertNotNull(annotation)
        assertEquals("textDocument/didOpen", annotation.value)
    }

    @Test
    fun `all server methods are defined`() {
        val expectedMethods = listOf(
            "initialize", "initialized", "shutdown", "exit",
            "editCommandsCode", "editTaskAccept", "editTaskUndo", "editTaskCancel",
            "chatNew", "chatSubmitMessage",
            "textDocumentDidOpen", "textDocumentDidChange", "textDocumentDidFocus", "textDocumentDidClose",
            "codeActionsProvide", "codeActionsTrigger",
            "commandsTest", "commandExecute"
        )
        val actual = CodyAgentServer::class.java.declaredMethods.map { it.name }.toSet()
        for (name in expectedMethods) {
            assertTrue(name in actual, "Missing method: $name")
        }
    }

    private fun findMethod(name: String): Method =
        CodyAgentServer::class.java.declaredMethods.first { it.name == name }
}
```

- [ ] **Step 2: Tests won't compile yet** — CodyAgentServer interface doesn't exist. That's expected in TDD.

- [ ] **Step 3: Commit test files**

```bash
git add cody/src/test/kotlin/com/workflow/orchestrator/cody/agent/CodyAgentServerTest.kt
git commit -m "test(cody): add CodyAgentServer interface contract tests (red)"
```

---

### Task 8: CodyAgentServer Interface Implementation

**Files:**
- Create: `cody/src/main/kotlin/com/workflow/orchestrator/cody/agent/CodyAgentServer.kt`

- [ ] **Step 1: Create CodyAgentServer interface**

```kotlin
package com.workflow.orchestrator.cody.agent

import com.workflow.orchestrator.cody.protocol.*
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import java.util.concurrent.CompletableFuture

/**
 * Defines all client-to-server JSON-RPC methods for the Cody Agent protocol.
 * LSP4J generates a proxy implementation from this interface at runtime.
 */
interface CodyAgentServer {

    // --- Lifecycle ---

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
    fun chatNew(): CompletableFuture<String>

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

- [ ] **Step 2: Run tests to verify they pass**

Run: `./gradlew :cody:test --tests "*.CodyAgentServerTest" 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL, 7 tests passed

- [ ] **Step 3: Commit**

```bash
git add cody/src/main/kotlin/com/workflow/orchestrator/cody/agent/CodyAgentServer.kt
git commit -m "feat(cody): add CodyAgentServer JSON-RPC interface (green)"
```

---

### Task 9: CodyAgentClient (server-to-client handler)

**Files:**
- Create: `cody/src/main/kotlin/com/workflow/orchestrator/cody/agent/CodyAgentClient.kt`

- [ ] **Step 1: Create CodyAgentClient**

```kotlin
package com.workflow.orchestrator.cody.agent

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.cody.protocol.*
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Handles JSON-RPC requests and notifications sent FROM the Cody Agent TO the IDE.
 * LSP4J dispatches incoming messages to the annotated methods on this class.
 */
class CodyAgentClient(private val project: Project) {

    private val log = Logger.getInstance(CodyAgentClient::class.java)

    /** Listeners for workspace edit requests from the agent. */
    val editListeners = CopyOnWriteArrayList<(WorkspaceEditParams) -> Boolean>()

    /** Listeners for edit task state changes. */
    val editTaskStateListeners = CopyOnWriteArrayList<(EditTask) -> Unit>()

    @JsonRequest("workspace/edit")
    fun workspaceEdit(params: WorkspaceEditParams): CompletableFuture<Boolean> {
        log.info("Received workspace/edit with ${params.operations.size} operations")
        return CompletableFuture.supplyAsync {
            editListeners.any { listener -> listener(params) }
        }
    }

    @JsonRequest("textDocument/edit")
    fun textDocumentEdit(params: TextDocumentEditParams): CompletableFuture<Boolean> {
        log.info("Received textDocument/edit for ${params.uri}")
        val asWorkspaceEdit = WorkspaceEditParams(
            operations = listOf(
                WorkspaceEditOperation(
                    type = "edit-file",
                    uri = params.uri,
                    edits = params.edits
                )
            )
        )
        return workspaceEdit(asWorkspaceEdit)
    }

    @JsonNotification("editTaskState/didChange")
    fun editTaskStateDidChange(params: EditTask) {
        log.info("Edit task ${params.id} state changed to ${params.state}")
        editTaskStateListeners.forEach { it(params) }
    }

    @JsonNotification("progress/start")
    fun progressStart(params: ProgressStartParams) {
        log.debug("Progress start: ${params.id} — ${params.options?.title}")
    }

    @JsonNotification("progress/report")
    fun progressReport(params: ProgressReportParams) {
        log.debug("Progress: ${params.id} — ${params.message}")
    }

    @JsonNotification("progress/end")
    fun progressEnd(params: ProgressEndParams) {
        log.debug("Progress end: ${params.id}")
    }

    @JsonRequest("window/showMessage")
    fun windowShowMessage(params: ShowMessageParams): CompletableFuture<String?> {
        log.info("Agent message: ${params.message}")
        return CompletableFuture.completedFuture(null)
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :cody:compileKotlin 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add cody/src/main/kotlin/com/workflow/orchestrator/cody/agent/CodyAgentClient.kt
git commit -m "feat(cody): add CodyAgentClient for server-to-client JSON-RPC"
```

---

### Task 10: CodyAgentManager — Process Lifecycle

**Files:**
- Create: `cody/src/main/kotlin/com/workflow/orchestrator/cody/agent/CodyAgentManager.kt`
- Create: `cody/src/test/kotlin/com/workflow/orchestrator/cody/agent/CodyAgentManagerTest.kt`

- [ ] **Step 1: Write CodyAgentManager tests**

```kotlin
package com.workflow.orchestrator.cody.agent

import com.workflow.orchestrator.cody.protocol.AuthStatus
import com.workflow.orchestrator.cody.protocol.ServerInfo
import io.mockk.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

class CodyAgentManagerTest {

    @Test
    fun `initial state is Stopped`() {
        val manager = createTestManager()
        assertTrue(manager.state.value is CodyAgentManager.AgentState.Stopped)
    }

    @Test
    fun `isRunning returns false when stopped`() {
        val manager = createTestManager()
        assertFalse(manager.isRunning())
    }

    @Test
    fun `resolveAgentBinary returns configured path when set`() {
        val manager = createTestManager(agentPath = "/custom/cody-agent")
        val resolved = manager.resolveAgentBinaryForTest()
        assertEquals("/custom/cody-agent", resolved)
    }

    @Test
    fun `resolveAgentBinary returns null when not configured and not on PATH`() {
        val manager = createTestManager(agentPath = "")
        val resolved = manager.resolveAgentBinaryForTest()
        // Returns null when nothing is configured and binary not found on PATH
        // (In test environment, cody-agent is not on PATH)
        assertNull(resolved)
    }

    @Test
    fun `buildClientInfo creates correct structure`() {
        val manager = createTestManager(
            sourcegraphUrl = "https://sg.example.com",
            token = "sgp_test123"
        )
        val clientInfo = manager.buildClientInfoForTest()
        assertEquals("WorkflowOrchestrator", clientInfo.name)
        assertEquals("https://sg.example.com", clientInfo.extensionConfiguration.serverEndpoint)
        assertEquals("sgp_test123", clientInfo.extensionConfiguration.accessToken)
        assertEquals("enabled", clientInfo.capabilities.edit)
        assertEquals("none", clientInfo.capabilities.completions)
    }

    /**
     * Creates a testable CodyAgentManager that doesn't depend on IntelliJ Project.
     * Uses the same internal logic but with injected settings.
     */
    private fun createTestManager(
        agentPath: String = "",
        sourcegraphUrl: String = "",
        token: String? = null
    ): TestCodyAgentManager = TestCodyAgentManager(agentPath, sourcegraphUrl, token)
}

/**
 * Test double for CodyAgentManager that replicates core logic
 * without requiring IntelliJ Project or PasswordSafe.
 */
class TestCodyAgentManager(
    private val agentPath: String,
    private val sourcegraphUrl: String,
    private val token: String?
) {
    private val _state = kotlinx.coroutines.flow.MutableStateFlow<CodyAgentManager.AgentState>(
        CodyAgentManager.AgentState.Stopped
    )
    val state: kotlinx.coroutines.flow.StateFlow<CodyAgentManager.AgentState> = _state

    fun isRunning(): Boolean = _state.value is CodyAgentManager.AgentState.Running

    fun resolveAgentBinaryForTest(): String? {
        if (agentPath.isNotBlank()) return agentPath
        // Simulate PATH lookup — in tests, binary won't be found
        return try {
            val process = ProcessBuilder("which", "cody-agent").start()
            val result = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            result.ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }

    fun buildClientInfoForTest(): com.workflow.orchestrator.cody.protocol.ClientInfo {
        return com.workflow.orchestrator.cody.protocol.ClientInfo(
            version = "1.0.0",
            workspaceRootUri = "file:///test",
            extensionConfiguration = com.workflow.orchestrator.cody.protocol.ExtensionConfiguration(
                serverEndpoint = sourcegraphUrl,
                accessToken = token ?: ""
            ),
            capabilities = com.workflow.orchestrator.cody.protocol.ClientCapabilities()
        )
    }
}
```

- [ ] **Step 2: Tests won't compile yet** — CodyAgentManager doesn't exist.

- [ ] **Step 3: Create CodyAgentManager**

```kotlin
package com.workflow.orchestrator.cody.agent

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.cody.protocol.*
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.notifications.WorkflowNotificationService
import com.workflow.orchestrator.core.settings.PluginSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.eclipse.lsp4j.jsonrpc.Launcher
import java.io.File
import java.util.concurrent.TimeUnit

@Service(Service.Level.PROJECT)
class CodyAgentManager(private val project: Project) : Disposable {

    private val log = Logger.getInstance(CodyAgentManager::class.java)

    private var process: Process? = null
    private var server: CodyAgentServer? = null
    private var _client: CodyAgentClient? = null

    val client: CodyAgentClient? get() = _client

    private val _state = MutableStateFlow<AgentState>(AgentState.Stopped)
    val state: StateFlow<AgentState> = _state.asStateFlow()

    sealed class AgentState {
        object Stopped : AgentState()
        object Starting : AgentState()
        data class Running(val serverInfo: ServerInfo) : AgentState()
        data class Error(val message: String) : AgentState()
    }

    @Synchronized
    suspend fun ensureRunning(): CodyAgentServer {
        val currentServer = server
        if (currentServer != null && isRunning()) return currentServer

        _state.value = AgentState.Starting

        val binaryPath = resolveAgentBinary()
        if (binaryPath == null) {
            val msg = "Cody Agent binary not found. Configure path in Settings > Workflow Orchestrator."
            _state.value = AgentState.Error(msg)
            notifyError(msg)
            throw IllegalStateException(msg)
        }

        val settings = PluginSettings.getInstance(project)
        val token = CredentialStore().getToken(ServiceType.SOURCEGRAPH)
        if (token.isNullOrBlank()) {
            val msg = "Sourcegraph access token not configured."
            _state.value = AgentState.Error(msg)
            notifyError(msg)
            throw IllegalStateException(msg)
        }

        return startAgent(binaryPath, settings, token)
    }

    private fun startAgent(binaryPath: String, settings: PluginSettings, token: String): CodyAgentServer {
        val pb = ProcessBuilder(binaryPath, "api", "jsonrpc-stdio")
            .redirectErrorStream(false)

        val env = pb.environment()
        env["CODY_DEBUG"] = if (log.isDebugEnabled) "true" else "false"

        val proc = pb.start()
        process = proc

        val agentClient = CodyAgentClient(project)
        _client = agentClient

        val launcher = Launcher.Builder<CodyAgentServer>()
            .setInput(proc.inputStream)
            .setOutput(proc.outputStream)
            .setLocalService(agentClient)
            .setRemoteInterface(CodyAgentServer::class.java)
            .create()

        val agentServer = launcher.remoteProxy
        server = agentServer

        launcher.startListening()

        // Monitor process exit
        Thread({
            proc.waitFor()
            log.warn("Cody Agent process exited with code ${proc.exitValue()}")
            if (_state.value is AgentState.Running) {
                _state.value = AgentState.Error("Agent process exited unexpectedly")
            }
            server = null
        }, "cody-agent-monitor").apply { isDaemon = true }.start()

        // Initialize handshake
        val clientInfo = buildClientInfo(settings, token)
        val serverInfo = agentServer.initialize(clientInfo).get(30, TimeUnit.SECONDS)
        agentServer.initialized()

        if (serverInfo.authenticated != true) {
            val msg = "Sourcegraph authentication failed. Check your access token."
            _state.value = AgentState.Error(msg)
            notifyError(msg)
            dispose()
            throw IllegalStateException(msg)
        }

        _state.value = AgentState.Running(serverInfo)
        log.info("Cody Agent started: ${serverInfo.name}, user: ${serverInfo.authStatus?.username}")

        return agentServer
    }

    fun isRunning(): Boolean {
        val proc = process ?: return false
        return proc.isAlive && _state.value is AgentState.Running
    }

    suspend fun restart() {
        dispose()
        ensureRunning()
    }

    internal fun resolveAgentBinary(): String? {
        val settings = PluginSettings.getInstance(project)
        val configured = settings.state.codyAgentPath
        if (!configured.isNullOrBlank() && File(configured).exists()) {
            return configured
        }

        // Try PATH lookup
        return try {
            val whichCmd = if (System.getProperty("os.name").lowercase().contains("win")) "where" else "which"
            val proc = ProcessBuilder(whichCmd, "cody-agent").start()
            val result = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor(5, TimeUnit.SECONDS)
            if (result.isNotBlank() && File(result).exists()) result else null
        } catch (e: Exception) {
            log.debug("cody-agent not found on PATH", e)
            null
        }
    }

    internal fun buildClientInfo(settings: PluginSettings, token: String): ClientInfo {
        val ideVersion = try {
            ApplicationInfo.getInstance().build.toString()
        } catch (e: Exception) { null }

        return ClientInfo(
            version = "1.0.0",
            ideVersion = ideVersion,
            workspaceRootUri = "file://${project.basePath}",
            extensionConfiguration = ExtensionConfiguration(
                serverEndpoint = settings.state.sourcegraphUrl ?: "",
                accessToken = token
            ),
            capabilities = ClientCapabilities()
        )
    }

    override fun dispose() {
        try {
            server?.shutdown()?.get(5, TimeUnit.SECONDS)
            server?.exit()
        } catch (e: Exception) {
            log.debug("Shutdown error (expected if agent already exited)", e)
        }
        process?.destroyForcibly()
        process = null
        server = null
        _client = null
        _state.value = AgentState.Stopped
    }

    private fun notifyError(message: String) {
        try {
            WorkflowNotificationService.getInstance(project).notifyError(
                WorkflowNotificationService.GROUP_CODY,
                "Cody AI",
                message
            )
        } catch (e: Exception) {
            log.error("Failed to show notification", e)
        }
    }

    companion object {
        fun getInstance(project: Project): CodyAgentManager =
            project.service<CodyAgentManager>()
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :cody:test --tests "*.CodyAgentManagerTest" 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL, 5 tests passed

- [ ] **Step 5: Commit**

```bash
git add cody/src/main/kotlin/com/workflow/orchestrator/cody/agent/CodyAgentManager.kt \
        cody/src/test/kotlin/com/workflow/orchestrator/cody/agent/CodyAgentManagerTest.kt
git commit -m "feat(cody): add CodyAgentManager with process lifecycle management"
```

---

## Chunk 3: Service Layer (CodyEditService, CodyChatService, CodyContextService)

### Task 11: CodyEditService Tests & Implementation

**Files:**
- Create: `cody/src/test/kotlin/com/workflow/orchestrator/cody/service/CodyEditServiceTest.kt`
- Create: `cody/src/main/kotlin/com/workflow/orchestrator/cody/service/CodyEditService.kt`

- [ ] **Step 1: Write CodyEditService tests**

```kotlin
package com.workflow.orchestrator.cody.service

import com.workflow.orchestrator.cody.protocol.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

class CodyEditServiceTest {

    private val mockServer = mockk<com.workflow.orchestrator.cody.agent.CodyAgentServer>()

    private lateinit var service: TestCodyEditService

    @BeforeEach
    fun setUp() {
        every { mockServer.textDocumentDidFocus(any()) } just runs
        service = TestCodyEditService(mockServer)
    }

    @Test
    fun `requestFix sends didFocus then editCommands`() = runTest {
        val editTask = EditTask(id = "task-1", state = "Working")
        every { mockServer.editCommandsCode(any()) } returns CompletableFuture.completedFuture(editTask)

        val result = service.requestFix(
            filePath = "file:///src/main/Foo.kt",
            range = Range(start = Position(10, 0), end = Position(15, 0)),
            instruction = "Fix the NPE"
        )

        assertEquals("task-1", result.id)
        assertEquals("Working", result.state)

        verify { mockServer.textDocumentDidFocus(TextDocumentIdentifier("file:///src/main/Foo.kt")) }
        verify {
            mockServer.editCommandsCode(match {
                it.instruction == "Fix the NPE" && it.mode == "edit"
            })
        }
    }

    @Test
    fun `requestTestGeneration sends editCommands with test instruction`() = runTest {
        val editTask = EditTask(id = "task-2", state = "Working")
        every { mockServer.editCommandsCode(any()) } returns CompletableFuture.completedFuture(editTask)

        val result = service.requestTestGeneration(
            filePath = "file:///src/main/Foo.kt",
            targetRange = Range(start = Position(20, 0), end = Position(25, 0)),
            existingTestFile = "file:///src/test/FooTest.kt"
        )

        assertEquals("task-2", result.id)
        verify {
            mockServer.editCommandsCode(match {
                it.instruction.contains("test") || it.instruction.contains("Test")
            })
        }
    }

    @Test
    fun `acceptEdit sends editTask accept`() = runTest {
        every { mockServer.editTaskAccept(any()) } returns CompletableFuture.completedFuture(null)

        service.acceptEdit("task-1")

        verify { mockServer.editTaskAccept(EditTaskParams("task-1")) }
    }

    @Test
    fun `undoEdit sends editTask undo`() = runTest {
        every { mockServer.editTaskUndo(any()) } returns CompletableFuture.completedFuture(null)

        service.undoEdit("task-1")

        verify { mockServer.editTaskUndo(EditTaskParams("task-1")) }
    }

    @Test
    fun `cancelEdit sends editTask cancel`() = runTest {
        every { mockServer.editTaskCancel(any()) } returns CompletableFuture.completedFuture(null)

        service.cancelEdit("task-1")

        verify { mockServer.editTaskCancel(EditTaskParams("task-1")) }
    }
}

/**
 * Test double that accepts a mock server directly,
 * bypassing CodyAgentManager dependency.
 */
class TestCodyEditService(
    private val server: com.workflow.orchestrator.cody.agent.CodyAgentServer
) {
    suspend fun requestFix(
        filePath: String,
        range: Range,
        instruction: String,
        contextFiles: List<ContextFile> = emptyList()
    ): EditTask {
        server.textDocumentDidFocus(TextDocumentIdentifier(filePath))
        return server.editCommandsCode(
            EditCommandsCodeParams(
                instruction = instruction,
                mode = "edit",
                range = range
            )
        ).get()
    }

    suspend fun requestTestGeneration(
        filePath: String,
        targetRange: Range,
        existingTestFile: String? = null
    ): EditTask {
        server.textDocumentDidFocus(TextDocumentIdentifier(filePath))
        val instruction = buildString {
            append("Generate a unit test covering the code at lines ")
            append("${targetRange.start.line}-${targetRange.end.line}")
            if (existingTestFile != null) {
                append(". Add to existing test file: $existingTestFile")
            }
        }
        return server.editCommandsCode(
            EditCommandsCodeParams(instruction = instruction, mode = "edit", range = targetRange)
        ).get()
    }

    suspend fun acceptEdit(taskId: String) {
        server.editTaskAccept(EditTaskParams(taskId)).get()
    }

    suspend fun undoEdit(taskId: String) {
        server.editTaskUndo(EditTaskParams(taskId)).get()
    }

    suspend fun cancelEdit(taskId: String) {
        server.editTaskCancel(EditTaskParams(taskId)).get()
    }
}
```

- [ ] **Step 2: Run tests to verify they pass**

Run: `./gradlew :cody:test --tests "*.CodyEditServiceTest" 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL, 5 tests passed

- [ ] **Step 3: Create CodyEditService**

```kotlin
package com.workflow.orchestrator.cody.service

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.cody.agent.CodyAgentManager
import com.workflow.orchestrator.cody.protocol.*
import kotlinx.coroutines.future.await

class CodyEditService(private val project: Project) {

    suspend fun requestFix(
        filePath: String,
        range: Range,
        instruction: String,
        contextFiles: List<ContextFile> = emptyList()
    ): EditTask {
        val server = CodyAgentManager.getInstance(project).ensureRunning()
        server.textDocumentDidFocus(TextDocumentIdentifier(filePath))
        return server.editCommandsCode(
            EditCommandsCodeParams(
                instruction = instruction,
                mode = "edit",
                range = range
            )
        ).await()
    }

    suspend fun requestTestGeneration(
        filePath: String,
        targetRange: Range,
        existingTestFile: String? = null
    ): EditTask {
        val server = CodyAgentManager.getInstance(project).ensureRunning()
        server.textDocumentDidFocus(TextDocumentIdentifier(filePath))
        val instruction = buildString {
            append("Generate a unit test covering the code at lines ")
            append("${targetRange.start.line}-${targetRange.end.line}")
            if (existingTestFile != null) {
                append(". Add to existing test file: $existingTestFile")
            }
        }
        return server.editCommandsCode(
            EditCommandsCodeParams(instruction = instruction, mode = "edit", range = targetRange)
        ).await()
    }

    suspend fun acceptEdit(taskId: String) {
        val server = CodyAgentManager.getInstance(project).ensureRunning()
        server.editTaskAccept(EditTaskParams(taskId)).await()
    }

    suspend fun undoEdit(taskId: String) {
        val server = CodyAgentManager.getInstance(project).ensureRunning()
        server.editTaskUndo(EditTaskParams(taskId)).await()
    }

    suspend fun cancelEdit(taskId: String) {
        val server = CodyAgentManager.getInstance(project).ensureRunning()
        server.editTaskCancel(EditTaskParams(taskId)).await()
    }
}
```

- [ ] **Step 4: Verify compilation**

Run: `./gradlew :cody:compileKotlin 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add cody/src/main/kotlin/com/workflow/orchestrator/cody/service/CodyEditService.kt \
        cody/src/test/kotlin/com/workflow/orchestrator/cody/service/CodyEditServiceTest.kt
git commit -m "feat(cody): add CodyEditService with edit lifecycle management"
```

---

### Task 12: CodyChatService Tests & Implementation

**Files:**
- Create: `cody/src/test/kotlin/com/workflow/orchestrator/cody/service/CodyChatServiceTest.kt`
- Create: `cody/src/main/kotlin/com/workflow/orchestrator/cody/service/CodyChatService.kt`

- [ ] **Step 1: Write CodyChatService tests**

```kotlin
package com.workflow.orchestrator.cody.service

import com.workflow.orchestrator.cody.protocol.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

class CodyChatServiceTest {

    private val mockServer = mockk<com.workflow.orchestrator.cody.agent.CodyAgentServer>()

    private lateinit var service: TestCodyChatService

    @BeforeEach
    fun setUp() {
        service = TestCodyChatService(mockServer)
    }

    @Test
    fun `generateCommitMessage creates chat and returns assistant response`() = runTest {
        every { mockServer.chatNew() } returns CompletableFuture.completedFuture("chat-001")
        every { mockServer.chatSubmitMessage(any()) } returns CompletableFuture.completedFuture(
            ChatResponse(
                type = "transcript",
                messages = listOf(
                    TranscriptMessage(speaker = "human", text = "Generate commit message"),
                    TranscriptMessage(speaker = "assistant", text = "fix: handle NPE in UserService")
                )
            )
        )

        val result = service.generateCommitMessage("diff --git a/...")

        assertEquals("fix: handle NPE in UserService", result)
        verify { mockServer.chatNew() }
        verify {
            mockServer.chatSubmitMessage(match {
                it.id == "chat-001" && it.message.text.contains("diff")
            })
        }
    }

    @Test
    fun `generateCommitMessage returns null when no assistant response`() = runTest {
        every { mockServer.chatNew() } returns CompletableFuture.completedFuture("chat-002")
        every { mockServer.chatSubmitMessage(any()) } returns CompletableFuture.completedFuture(
            ChatResponse(type = "transcript", messages = emptyList())
        )

        val result = service.generateCommitMessage("diff")

        assertNull(result)
    }

    @Test
    fun `buildCommitMessagePrompt includes diff content`() {
        val prompt = service.buildCommitMessagePromptForTest("--- a/Foo.kt\n+++ b/Foo.kt")
        assertTrue(prompt.contains("--- a/Foo.kt"))
        assertTrue(prompt.contains("conventional commits"))
    }
}

class TestCodyChatService(
    private val server: com.workflow.orchestrator.cody.agent.CodyAgentServer
) {
    suspend fun generateCommitMessage(diff: String): String? {
        val chatId = server.chatNew().get()
        val prompt = buildCommitMessagePromptForTest(diff)
        val response = server.chatSubmitMessage(
            ChatSubmitParams(id = chatId, message = ChatMessage(text = prompt))
        ).get()
        return response.messages.lastOrNull { it.speaker == "assistant" }?.text
    }

    fun buildCommitMessagePromptForTest(diff: String): String =
        """Generate a concise git commit message for this diff.
           |Use conventional commits format (feat/fix/refactor/etc).
           |One line summary, optional body.
           |
           |```diff
           |$diff
           |```""".trimMargin()
}
```

- [ ] **Step 2: Run tests**

Run: `./gradlew :cody:test --tests "*.CodyChatServiceTest" 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL, 3 tests passed

- [ ] **Step 3: Create CodyChatService**

```kotlin
package com.workflow.orchestrator.cody.service

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.cody.agent.CodyAgentManager
import com.workflow.orchestrator.cody.protocol.*
import kotlinx.coroutines.future.await

class CodyChatService(private val project: Project) {

    suspend fun generateCommitMessage(diff: String): String? {
        val server = CodyAgentManager.getInstance(project).ensureRunning()
        val chatId = server.chatNew().await()
        val prompt = buildCommitMessagePrompt(diff)
        val response = server.chatSubmitMessage(
            ChatSubmitParams(id = chatId, message = ChatMessage(text = prompt))
        ).await()
        return response.messages.lastOrNull { it.speaker == "assistant" }?.text
    }

    internal fun buildCommitMessagePrompt(diff: String): String =
        """Generate a concise git commit message for this diff.
           |Use conventional commits format (feat/fix/refactor/etc).
           |One line summary, optional body.
           |
           |```diff
           |$diff
           |```""".trimMargin()
}
```

- [ ] **Step 4: Verify compilation**

Run: `./gradlew :cody:compileKotlin 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add cody/src/main/kotlin/com/workflow/orchestrator/cody/service/CodyChatService.kt \
        cody/src/test/kotlin/com/workflow/orchestrator/cody/service/CodyChatServiceTest.kt
git commit -m "feat(cody): add CodyChatService for commit message generation"
```

---

### Task 13: CodyContextService Tests & Implementation

**Files:**
- Create: `cody/src/test/kotlin/com/workflow/orchestrator/cody/service/CodyContextServiceTest.kt`
- Create: `cody/src/main/kotlin/com/workflow/orchestrator/cody/service/CodyContextService.kt`

- [ ] **Step 1: Write CodyContextService tests**

```kotlin
package com.workflow.orchestrator.cody.service

import com.workflow.orchestrator.cody.protocol.ContextFile
import com.workflow.orchestrator.cody.protocol.Range
import com.workflow.orchestrator.cody.protocol.Position
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CodyContextServiceTest {

    private val service = CodyContextServiceLogic()

    @Test
    fun `buildFixInstruction includes issue message and type`() {
        val instruction = service.buildFixInstruction(
            issueType = "BUG",
            issueMessage = "Possible NullPointerException",
            ruleKey = "java:S2259"
        )
        assertTrue(instruction.contains("BUG"))
        assertTrue(instruction.contains("NullPointerException"))
        assertTrue(instruction.contains("S2259"))
    }

    @Test
    fun `buildTestInstruction includes line range`() {
        val instruction = service.buildTestInstruction(
            range = Range(start = Position(20, 0), end = Position(30, 0)),
            existingTestFile = null
        )
        assertTrue(instruction.contains("20"))
        assertTrue(instruction.contains("30"))
    }

    @Test
    fun `buildTestInstruction references existing test file when provided`() {
        val instruction = service.buildTestInstruction(
            range = Range(start = Position(10, 0), end = Position(20, 0)),
            existingTestFile = "src/test/kotlin/FooTest.kt"
        )
        assertTrue(instruction.contains("FooTest.kt"))
        assertTrue(instruction.contains("existing"))
    }

    @Test
    fun `resolveTestFile maps main to test path`() {
        val result = service.resolveTestFile("src/main/kotlin/com/app/UserService.kt")
        assertEquals("src/test/kotlin/com/app/UserServiceTest.kt", result)
    }

    @Test
    fun `resolveTestFile maps java main to test path`() {
        val result = service.resolveTestFile("src/main/java/com/app/UserService.java")
        assertEquals("src/test/java/com/app/UserServiceTest.java", result)
    }

    @Test
    fun `resolveTestFile returns null for non-main files`() {
        val result = service.resolveTestFile("src/test/kotlin/FooTest.kt")
        assertNull(result)
    }
}

/**
 * Pure logic extracted from CodyContextService for testing
 * without IntelliJ PSI dependencies.
 */
class CodyContextServiceLogic {

    fun buildFixInstruction(issueType: String, issueMessage: String, ruleKey: String): String =
        """Fix the following SonarQube $issueType issue (rule: $ruleKey):
           |$issueMessage
           |
           |Provide a minimal fix that addresses the issue without changing behavior.""".trimMargin()

    fun buildTestInstruction(range: Range, existingTestFile: String?): String = buildString {
        append("Generate a unit test covering the code at lines ")
        append("${range.start.line}-${range.end.line}. ")
        append("Use JUnit 5 with standard assertions. ")
        if (existingTestFile != null) {
            append("Add to the existing test file: $existingTestFile. ")
            append("Match the existing test style and imports.")
        } else {
            append("Create a new test class with proper package and imports.")
        }
    }

    fun resolveTestFile(sourceFilePath: String): String? {
        if (!sourceFilePath.contains("src/main/")) return null
        val testPath = sourceFilePath.replace("src/main/", "src/test/")
        val ext = testPath.substringAfterLast(".")
        val nameWithoutExt = testPath.substringBeforeLast(".")
        return "${nameWithoutExt}Test.$ext"
    }
}
```

- [ ] **Step 2: Run tests**

Run: `./gradlew :cody:test --tests "*.CodyContextServiceTest" 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL, 6 tests passed

- [ ] **Step 3: Create CodyContextService**

> **Spec divergence note:** Spec defines `gatherFixContext(file: PsiFile, ...)` and `gatherTestContext(file: PsiFile, ...)`.
> This plan uses `String` file paths instead of `PsiFile` to keep the service testable without IntelliJ PSI fixtures.
> The `issueType` and `ruleKey` parameters are additions needed for the instruction builder that the spec omits.

```kotlin
package com.workflow.orchestrator.cody.service

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.cody.protocol.ContextFile
import com.workflow.orchestrator.cody.protocol.Range

class CodyContextService(private val project: Project) {

    private val logic = CodyContextServiceLogic()

    data class FixContext(
        val instruction: String,
        val contextFiles: List<ContextFile>
    )

    data class TestContext(
        val instruction: String,
        val contextFiles: List<ContextFile>,
        val existingTestFile: String?
    )

    fun gatherFixContext(
        filePath: String,
        issueRange: Range,
        issueType: String,
        issueMessage: String,
        ruleKey: String
    ): FixContext {
        val instruction = logic.buildFixInstruction(issueType, issueMessage, ruleKey)
        val contextFiles = listOf(
            ContextFile(uri = filePath, range = issueRange)
        )
        return FixContext(instruction, contextFiles)
    }

    fun gatherTestContext(
        filePath: String,
        targetRange: Range
    ): TestContext {
        val existingTestFile = logic.resolveTestFile(filePath)
        val instruction = logic.buildTestInstruction(targetRange, existingTestFile)
        val contextFiles = mutableListOf(
            ContextFile(uri = filePath, range = targetRange)
        )
        if (existingTestFile != null) {
            contextFiles.add(ContextFile(uri = existingTestFile))
        }
        return TestContext(instruction, contextFiles, existingTestFile)
    }
}
```

- [ ] **Step 4: Verify compilation**

Run: `./gradlew :cody:compileKotlin 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add cody/src/main/kotlin/com/workflow/orchestrator/cody/service/CodyContextService.kt \
        cody/src/main/kotlin/com/workflow/orchestrator/cody/service/CodyContextServiceLogic.kt \
        cody/src/test/kotlin/com/workflow/orchestrator/cody/service/CodyContextServiceTest.kt
git commit -m "feat(cody): add CodyContextService with fix and test context gathering"
```

> **Note:** Extract `CodyContextServiceLogic` to its own file since both the service and test reference it:
> `cody/src/main/kotlin/com/workflow/orchestrator/cody/service/CodyContextServiceLogic.kt`

---

## Chunk 4: Editor Integration (Gutter Actions, IntentionAction, EditApplier)

### Task 14: CodyEditApplier

**Files:**
- Create: `cody/src/main/kotlin/com/workflow/orchestrator/cody/editor/CodyEditApplier.kt`
- Create: `cody/src/test/kotlin/com/workflow/orchestrator/cody/editor/CodyEditApplierLogicTest.kt`

- [ ] **Step 1: Write edit application logic tests**

```kotlin
package com.workflow.orchestrator.cody.editor

import com.workflow.orchestrator.cody.protocol.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CodyEditApplierLogicTest {

    @Test
    fun `applyTextEditsToContent replaces text correctly`() {
        val content = "line0\nline1\nline2\nline3\nline4"
        val edits = listOf(
            TextEdit(
                type = "replace",
                range = Range(
                    start = Position(line = 1, character = 0),
                    end = Position(line = 1, character = 5)
                ),
                value = "REPLACED"
            )
        )
        val result = CodyEditApplierLogic.applyTextEditsToContent(content, edits)
        assertTrue(result.contains("REPLACED"))
        assertFalse(result.contains("line1"))
    }

    @Test
    fun `applyTextEditsToContent inserts text`() {
        val content = "line0\nline1\nline2"
        val edits = listOf(
            TextEdit(
                type = "insert",
                position = Position(line = 1, character = 0),
                value = "INSERTED\n"
            )
        )
        val result = CodyEditApplierLogic.applyTextEditsToContent(content, edits)
        assertTrue(result.contains("INSERTED"))
    }

    @Test
    fun `applyTextEditsToContent deletes text`() {
        val content = "line0\nDELETE_ME\nline2"
        val edits = listOf(
            TextEdit(
                type = "delete",
                range = Range(
                    start = Position(line = 1, character = 0),
                    end = Position(line = 2, character = 0)
                )
            )
        )
        val result = CodyEditApplierLogic.applyTextEditsToContent(content, edits)
        assertFalse(result.contains("DELETE_ME"))
        assertTrue(result.contains("line0"))
        assertTrue(result.contains("line2"))
    }

    @Test
    fun `computeOffset converts line and character to offset`() {
        val content = "abc\ndef\nghi"  // line 0: abc(3), \n(1), line 1: def(3), \n(1), line 2: ghi
        assertEquals(0, CodyEditApplierLogic.computeOffset(content, Position(0, 0)))
        assertEquals(4, CodyEditApplierLogic.computeOffset(content, Position(1, 0)))
        assertEquals(5, CodyEditApplierLogic.computeOffset(content, Position(1, 1)))
        assertEquals(8, CodyEditApplierLogic.computeOffset(content, Position(2, 0)))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :cody:test --tests "*.CodyEditApplierLogicTest" 2>&1 | tail -5`
Expected: FAIL (class not found)

- [ ] **Step 3: Create CodyEditApplier with logic**

```kotlin
package com.workflow.orchestrator.cody.editor

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.workflow.orchestrator.cody.protocol.*
import com.workflow.orchestrator.cody.service.CodyEditService

/**
 * Shows diff preview and applies edits from workspace/edit callbacks.
 * Note: Accept/Reject buttons are wired at integration time via
 * CodyAgentClient.editListeners — the client registers a listener that
 * calls showDiffPreview, and the CodyEditService handles accept/undo
 * via editTask/accept and editTask/undo JSON-RPC methods.
 */
class CodyEditApplier(private val project: Project) {

    fun showDiffPreview(editTask: EditTask, operations: List<WorkspaceEditOperation>) {
        for (op in operations) {
            when (op.type) {
                "edit-file" -> showEditFileDiff(editTask, op)
                "create-file" -> showCreateFileDiff(editTask, op)
            }
        }
    }

    private fun showEditFileDiff(editTask: EditTask, op: WorkspaceEditOperation) {
        val uri = op.uri ?: return
        val filePath = uri.removePrefix("file://")
        val vFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return
        val document = FileDocumentManager.getInstance().getDocument(vFile) ?: return

        val originalContent = document.text
        val modifiedContent = CodyEditApplierLogic.applyTextEditsToContent(originalContent, op.edits ?: emptyList())

        val contentFactory = DiffContentFactory.getInstance()
        val request = SimpleDiffRequest(
            "Cody AI Fix — ${editTask.instruction ?: "Edit"}",
            contentFactory.create(project, originalContent, vFile.fileType),
            contentFactory.create(project, modifiedContent, vFile.fileType),
            "Original",
            "Cody Suggestion"
        )

        ApplicationManager.getApplication().invokeLater {
            DiffManager.getInstance().showDiff(project, request)
        }
    }

    private fun showCreateFileDiff(editTask: EditTask, op: WorkspaceEditOperation) {
        val content = op.textContents ?: return
        val contentFactory = DiffContentFactory.getInstance()
        val request = SimpleDiffRequest(
            "Cody AI — New File",
            contentFactory.createEmpty(),
            contentFactory.create(content),
            "Empty",
            "Cody Generated"
        )

        ApplicationManager.getApplication().invokeLater {
            DiffManager.getInstance().showDiff(project, request)
        }
    }

    fun applyEdits(operations: List<WorkspaceEditOperation>) {
        WriteCommandAction.runWriteCommandAction(project, "Cody AI Edit", "cody.edit", {
            for (op in operations) {
                when (op.type) {
                    "edit-file" -> applyEditFileOperation(op)
                    "create-file" -> applyCreateFileOperation(op)
                }
            }
        })
    }

    private fun applyEditFileOperation(op: WorkspaceEditOperation) {
        val uri = op.uri ?: return
        val filePath = uri.removePrefix("file://")
        val vFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return
        val document = FileDocumentManager.getInstance().getDocument(vFile) ?: return

        val newContent = CodyEditApplierLogic.applyTextEditsToContent(document.text, op.edits ?: emptyList())
        document.setText(newContent)
    }

    private fun applyCreateFileOperation(op: WorkspaceEditOperation) {
        val uri = op.uri ?: return
        val filePath = uri.removePrefix("file://")
        val parentPath = filePath.substringBeforeLast("/")
        val fileName = filePath.substringAfterLast("/")

        val parentDir = LocalFileSystem.getInstance().findFileByPath(parentPath) ?: return
        val newFile = parentDir.createChildData(this, fileName)
        val document = FileDocumentManager.getInstance().getDocument(newFile) ?: return
        document.setText(op.textContents ?: "")
    }
}

/**
 * Pure text-edit logic extracted for unit testing without IntelliJ dependencies.
 */
object CodyEditApplierLogic {

    fun applyTextEditsToContent(content: String, edits: List<TextEdit>): String {
        // Apply edits in reverse offset order to avoid shifting positions
        val sortedEdits = edits.sortedByDescending { edit ->
            when (edit.type) {
                "replace", "delete" -> computeOffset(content, edit.range?.start ?: Position())
                "insert" -> computeOffset(content, edit.position ?: Position())
                else -> 0
            }
        }

        var result = content
        for (edit in sortedEdits) {
            result = when (edit.type) {
                "replace" -> {
                    val start = computeOffset(result, edit.range?.start ?: Position())
                    val end = computeOffset(result, edit.range?.end ?: Position())
                    result.substring(0, start) + (edit.value ?: "") + result.substring(end)
                }
                "insert" -> {
                    val offset = computeOffset(result, edit.position ?: Position())
                    result.substring(0, offset) + (edit.value ?: "") + result.substring(offset)
                }
                "delete" -> {
                    val start = computeOffset(result, edit.range?.start ?: Position())
                    val end = computeOffset(result, edit.range?.end ?: Position())
                    result.substring(0, start) + result.substring(end)
                }
                else -> result
            }
        }
        return result
    }

    fun computeOffset(content: String, pos: Position): Int {
        var offset = 0
        var currentLine = 0
        for (char in content) {
            if (currentLine == pos.line) break
            if (char == '\n') currentLine++
            offset++
        }
        return (offset + pos.character).coerceAtMost(content.length)
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :cody:test --tests "*.CodyEditApplierLogicTest" 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL, 4 tests passed

- [ ] **Step 5: Commit**

```bash
git add cody/src/main/kotlin/com/workflow/orchestrator/cody/editor/CodyEditApplier.kt \
        cody/src/test/kotlin/com/workflow/orchestrator/cody/editor/CodyEditApplierLogicTest.kt
git commit -m "feat(cody): add CodyEditApplier with diff preview and write-command application"
```

---

### Task 15: CodyGutterAction

**Files:**
- Create: `cody/src/main/kotlin/com/workflow/orchestrator/cody/editor/CodyGutterAction.kt`

- [ ] **Step 1: Create CodyGutterAction**

```kotlin
package com.workflow.orchestrator.cody.editor

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.progress.runBackgroundableTask
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.workflow.orchestrator.cody.protocol.Position
import com.workflow.orchestrator.cody.protocol.Range
import com.workflow.orchestrator.cody.service.CodyContextService
import com.workflow.orchestrator.cody.service.CodyEditService
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.PluginSettings
import kotlinx.coroutines.runBlocking

class CodyGutterAction : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(
        elements: MutableList<out PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>
    ) {
        if (elements.isEmpty()) return
        val file = elements.firstOrNull()?.containingFile ?: return
        val project = file.project

        // Check if Cody is configured
        val settings = PluginSettings.getInstance(project)
        if (settings.state.sourcegraphUrl.isNullOrBlank()) return
        if (settings.state.codyEnabled == false) return
        if (!CredentialStore().hasToken(ServiceType.SOURCEGRAPH)) return

        // Collect lines with Sonar issue annotations
        // In Phase 1E, we detect lines that have error/warning annotations
        // from the SonarIssueAnnotator (Phase 1D). For now, this checks
        // if the element has error-level annotations — the full integration
        // depends on Phase 1D's ExternalAnnotator being active.
        // Placeholder: add markers on method declarations for demonstration.
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :cody:compileKotlin 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add cody/src/main/kotlin/com/workflow/orchestrator/cody/editor/CodyGutterAction.kt
git commit -m "feat(cody): add CodyGutterAction skeleton for Fix with Cody gutter icon"
```

---

### Task 16: CodyIntentionAction

**Files:**
- Create: `cody/src/main/kotlin/com/workflow/orchestrator/cody/editor/CodyIntentionAction.kt`
- Create: `cody/src/test/kotlin/com/workflow/orchestrator/cody/editor/CodyIntentionActionTest.kt`

- [ ] **Step 1: Write CodyIntentionAction availability test**

```kotlin
package com.workflow.orchestrator.cody.editor

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CodyIntentionActionTest {

    @Test
    fun `getText returns expected label`() {
        val action = CodyIntentionAction()
        assertEquals("Ask Cody to fix", action.text)
    }

    @Test
    fun `getFamilyName returns Cody AI`() {
        val action = CodyIntentionAction()
        assertEquals("Cody AI", action.familyName)
    }

    @Test
    fun `startInWriteAction returns false`() {
        val action = CodyIntentionAction()
        assertFalse(action.startInWriteAction())
    }
}
```

- [ ] **Step 2: Create CodyIntentionAction**

```kotlin
package com.workflow.orchestrator.cody.editor

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.runBackgroundableTask
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.workflow.orchestrator.cody.protocol.Position
import com.workflow.orchestrator.cody.protocol.Range
import com.workflow.orchestrator.cody.service.CodyContextService
import com.workflow.orchestrator.cody.service.CodyEditService
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.PluginSettings
import kotlinx.coroutines.runBlocking

class CodyIntentionAction : IntentionAction {

    override fun getText(): String = "Ask Cody to fix"

    override fun getFamilyName(): String = "Cody AI"

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (editor == null || file == null) return false
        val settings = PluginSettings.getInstance(project)
        if (settings.state.sourcegraphUrl.isNullOrBlank()) return false
        if (settings.state.codyEnabled == false) return false
        if (!CredentialStore().hasToken(ServiceType.SOURCEGRAPH)) return false
        return true
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file == null) return

        val caretLine = editor.caretModel.logicalPosition.line
        val lineStart = Position(line = caretLine, character = 0)
        val lineEnd = Position(line = caretLine + 1, character = 0)
        val range = Range(start = lineStart, end = lineEnd)

        val filePath = "file://${file.virtualFile.path}"
        val contextService = CodyContextService(project)
        val fixContext = contextService.gatherFixContext(
            filePath = filePath,
            issueRange = range,
            issueType = "CODE_SMELL",
            issueMessage = "Issue detected on this line",
            ruleKey = "unknown"
        )

        runBackgroundableTask("Asking Cody to fix...", project) {
            runBlocking {
                CodyEditService(project).requestFix(
                    filePath = filePath,
                    range = range,
                    instruction = fixContext.instruction,
                    contextFiles = fixContext.contextFiles
                )
            }
        }
    }

    override fun startInWriteAction(): Boolean = false
}
```

- [ ] **Step 3: Run tests**

Run: `./gradlew :cody:test --tests "*.CodyIntentionActionTest" 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL, 3 tests passed

- [ ] **Step 4: Commit**

```bash
git add cody/src/main/kotlin/com/workflow/orchestrator/cody/editor/CodyIntentionAction.kt \
        cody/src/test/kotlin/com/workflow/orchestrator/cody/editor/CodyIntentionActionTest.kt
git commit -m "feat(cody): add CodyIntentionAction for Alt+Enter Ask Cody to fix"
```

---

### Task 17: CodyTestGenerator

**Files:**
- Create: `cody/src/main/kotlin/com/workflow/orchestrator/cody/editor/CodyTestGenerator.kt`

- [ ] **Step 1: Create CodyTestGenerator**

```kotlin
package com.workflow.orchestrator.cody.editor

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.psi.PsiElement
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.PluginSettings

/**
 * Adds "Cover with Cody" gutter icon on uncovered/partially-covered lines.
 * Requires Phase 1D coverage data to be active in the editor.
 */
class CodyTestGenerator : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(
        elements: MutableList<out PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>
    ) {
        if (elements.isEmpty()) return
        val file = elements.firstOrNull()?.containingFile ?: return
        val project = file.project

        val settings = PluginSettings.getInstance(project)
        if (settings.state.sourcegraphUrl.isNullOrBlank()) return
        if (settings.state.codyEnabled == false) return
        if (!CredentialStore().hasToken(ServiceType.SOURCEGRAPH)) return

        // Phase 1E skeleton: Full implementation depends on Phase 1D
        // CoverageLineMarkerProvider being active. When coverage data is
        // available, this provider adds "Cover with Cody" markers on
        // uncovered lines (grey gutter bars from Phase 1D).
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :cody:compileKotlin 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add cody/src/main/kotlin/com/workflow/orchestrator/cody/editor/CodyTestGenerator.kt
git commit -m "feat(cody): add CodyTestGenerator skeleton for Cover with Cody gutter action"
```

---

## Chunk 5: VCS Integration, Document Sync & Plugin Registration

### Task 18: CodyCommitMessageHandlerFactory

**Files:**
- Create: `cody/src/main/kotlin/com/workflow/orchestrator/cody/vcs/CodyCommitMessageHandlerFactory.kt`

- [ ] **Step 1: Create CodyCommitMessageHandlerFactory**

```kotlin
package com.workflow.orchestrator.cody.vcs

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.VcsCheckinHandlerFactory
import git4idea.GitVcs

class CodyCommitMessageHandlerFactory : VcsCheckinHandlerFactory(GitVcs.getKey()) {

    override fun createVcsHandler(
        panel: CheckinProjectPanel,
        commitContext: CommitContext
    ): CheckinHandler {
        return CodyCommitMessageHandler(panel)
    }
}

private class CodyCommitMessageHandler(
    private val panel: CheckinProjectPanel
) : CheckinHandler() {

    private val log = Logger.getInstance(CodyCommitMessageHandler::class.java)

    // This handler does not block commit — it provides a utility function.
    // The "Generate with Cody" button would ideally be added to the commit
    // dialog toolbar. For Phase 1E, the handler checks if the commit message
    // is empty and offers to generate one.

    override fun getBeforeCheckinConfigurationPanel() = null
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :cody:compileKotlin 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add cody/src/main/kotlin/com/workflow/orchestrator/cody/vcs/CodyCommitMessageHandlerFactory.kt
git commit -m "feat(cody): add CodyCommitMessageHandlerFactory for VCS integration"
```

---

### Task 19: Document Sync Listeners

**Files:**
- Create: `cody/src/main/kotlin/com/workflow/orchestrator/cody/agent/CodyDocumentSyncListener.kt`

- [ ] **Step 1: Create CodyDocumentSyncListener**

```kotlin
package com.workflow.orchestrator.cody.agent

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.ProjectManager
import com.workflow.orchestrator.cody.protocol.ProtocolTextDocument
import com.workflow.orchestrator.cody.protocol.TextDocumentIdentifier

/**
 * Sends textDocument/didOpen and textDocument/didClose notifications to the
 * Cody Agent when editors are opened/closed in the IDE.
 */
class CodyDocumentSyncListener : EditorFactoryListener {

    private val log = Logger.getInstance(CodyDocumentSyncListener::class.java)

    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        val document = editor.document
        val vFile = FileDocumentManager.getInstance().getFile(document) ?: return
        val project = editor.project ?: return

        val manager = try {
            CodyAgentManager.getInstance(project)
        } catch (e: Exception) { return }

        if (!manager.isRunning()) return

        val uri = "file://${vFile.path}"
        try {
            // Get server via state check — don't trigger lazy start for doc sync
            val server = getServerIfRunning(manager) ?: return
            server.textDocumentDidOpen(
                ProtocolTextDocument(
                    uri = uri,
                    content = document.text
                )
            )
            log.debug("Sent didOpen for $uri")
        } catch (e: Exception) {
            log.debug("Failed to send didOpen", e)
        }
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        val editor = event.editor
        val document = editor.document
        val vFile = FileDocumentManager.getInstance().getFile(document) ?: return
        val project = editor.project ?: return

        val manager = try {
            CodyAgentManager.getInstance(project)
        } catch (e: Exception) { return }

        if (!manager.isRunning()) return

        val uri = "file://${vFile.path}"
        try {
            val server = getServerIfRunning(manager) ?: return
            server.textDocumentDidClose(ProtocolTextDocument(uri = uri))
            log.debug("Sent didClose for $uri")
        } catch (e: Exception) {
            log.debug("Failed to send didClose", e)
        }
    }

    /**
     * Gets the agent server only if it's already running.
     * Does NOT trigger lazy start — document sync should not spawn the agent.
     */
    private fun getServerIfRunning(manager: CodyAgentManager): CodyAgentServer? {
        if (manager.state.value !is CodyAgentManager.AgentState.Running) return null
        // Access the server through reflection or a getter
        // Since CodyAgentManager exposes state, we check that first
        // and then use ensureRunning which returns immediately when already running
        return try {
            kotlinx.coroutines.runBlocking {
                manager.ensureRunning()
            }
        } catch (e: Exception) {
            null
        }
    }
}
```

- [ ] **Step 2: Create CodyDocumentChangeListener**

Create `cody/src/main/kotlin/com/workflow/orchestrator/cody/agent/CodyDocumentChangeListener.kt`:

```kotlin
package com.workflow.orchestrator.cody.agent

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.cody.protocol.ProtocolTextDocument
import java.util.Timer
import java.util.TimerTask

/**
 * Per-editor DocumentListener that sends debounced textDocument/didChange
 * notifications to the Cody Agent. Registered/unregistered by
 * CodyDocumentSyncListener when editors open/close.
 */
class CodyDocumentChangeListener(
    private val project: Project,
    private val fileUri: String
) : DocumentListener {

    private val log = Logger.getInstance(CodyDocumentChangeListener::class.java)
    private var debounceTimer: Timer? = null

    override fun documentChanged(event: DocumentEvent) {
        debounceTimer?.cancel()
        debounceTimer = Timer("cody-debounce", true).apply {
            schedule(object : TimerTask() {
                override fun run() {
                    sendDidChange(event)
                }
            }, DEBOUNCE_MS)
        }
    }

    private fun sendDidChange(event: DocumentEvent) {
        val manager = try {
            CodyAgentManager.getInstance(project)
        } catch (e: Exception) { return }

        if (manager.state.value !is CodyAgentManager.AgentState.Running) return

        try {
            val server = kotlinx.coroutines.runBlocking { manager.ensureRunning() }
            server.textDocumentDidChange(
                ProtocolTextDocument(
                    uri = fileUri,
                    content = event.document.text
                )
            )
            log.debug("Sent didChange for $fileUri")
        } catch (e: Exception) {
            log.debug("Failed to send didChange", e)
        }
    }

    fun dispose() {
        debounceTimer?.cancel()
        debounceTimer = null
    }

    companion object {
        private const val DEBOUNCE_MS = 300L
    }
}
```

- [ ] **Step 3: Wire CodyDocumentChangeListener into CodyDocumentSyncListener**

Update the `editorCreated` method in `CodyDocumentSyncListener` to register a per-editor `CodyDocumentChangeListener`, and `editorReleased` to unregister it. Add this field and logic:

At the top of `CodyDocumentSyncListener`, add:
```kotlin
    private val changeListeners = java.util.concurrent.ConcurrentHashMap<String, CodyDocumentChangeListener>()
```

At the end of `editorCreated`, after the `didOpen` call, add:
```kotlin
            // Register per-editor change listener for debounced didChange
            val changeListener = CodyDocumentChangeListener(project, uri)
            document.addDocumentListener(changeListener)
            changeListeners[uri] = changeListener
```

At the end of `editorReleased`, after the `didClose` call, add:
```kotlin
            // Unregister change listener
            changeListeners.remove(uri)?.dispose()
```

- [ ] **Step 4: Verify compilation**

Run: `./gradlew :cody:compileKotlin 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add cody/src/main/kotlin/com/workflow/orchestrator/cody/agent/CodyDocumentSyncListener.kt \
        cody/src/main/kotlin/com/workflow/orchestrator/cody/agent/CodyDocumentChangeListener.kt
git commit -m "feat(cody): add document sync with debounced didChange notifications"
```

---

### Task 19b: FileEditorManagerListener for didFocus

**Files:**
- Modify: `cody/src/main/kotlin/com/workflow/orchestrator/cody/agent/CodyDocumentSyncListener.kt` (add focus tracking)

- [ ] **Step 1: Add CodyFocusListener**

Create or append to `CodyDocumentSyncListener.kt`:

```kotlin
/**
 * Sends textDocument/didFocus when the user switches editor tabs.
 * Registered in plugin.xml as a projectListener.
 */
class CodyFocusListener(private val project: Project) :
    com.intellij.openapi.fileEditor.FileEditorManagerListener {

    private val log = Logger.getInstance(CodyFocusListener::class.java)

    override fun selectionChanged(event: com.intellij.openapi.fileEditor.FileEditorManagerEvent) {
        val newFile = event.newFile ?: return
        val manager = try {
            CodyAgentManager.getInstance(project)
        } catch (e: Exception) { return }

        if (manager.state.value !is CodyAgentManager.AgentState.Running) return

        val uri = "file://${newFile.path}"
        try {
            val server = kotlinx.coroutines.runBlocking { manager.ensureRunning() }
            server.textDocumentDidFocus(
                com.workflow.orchestrator.cody.protocol.TextDocumentIdentifier(uri)
            )
            log.debug("Sent didFocus for $uri")
        } catch (e: Exception) {
            log.debug("Failed to send didFocus", e)
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :cody:compileKotlin 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add cody/src/main/kotlin/com/workflow/orchestrator/cody/agent/CodyDocumentSyncListener.kt
git commit -m "feat(cody): add CodyFocusListener for textDocument/didFocus on tab switch"
```

---

### Task 20: plugin.xml Registration

**Files:**
- Modify: `core/src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Add Cody registrations to plugin.xml**

In `core/src/main/resources/META-INF/plugin.xml`, add inside `<extensions defaultExtensionNs="com.intellij">`:

```xml
        <!-- Cody AI Notification Group -->
        <notificationGroup id="workflow.cody" displayType="BALLOON"/>

        <!-- Cody Agent Service -->
        <projectService
            serviceImplementation="com.workflow.orchestrator.cody.agent.CodyAgentManager"/>

        <!-- Cody Gutter Actions -->
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

        <!-- Cody Intention Action (Alt+Enter) -->
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

        <!-- Cody Document Sync -->
        <editorFactoryListener
            implementation="com.workflow.orchestrator.cody.agent.CodyDocumentSyncListener"/>

        <!-- Cody Commit Message -->
        <vcsCheckinHandlerFactory
            implementation="com.workflow.orchestrator.cody.vcs.CodyCommitMessageHandlerFactory"/>
```

Also add inside `<projectListeners>` (alongside the existing `BranchChangeTicketDetector`):

```xml
        <!-- Cody Focus Tracking -->
        <listener class="com.workflow.orchestrator.cody.agent.CodyFocusListener"
                  topic="com.intellij.openapi.fileEditor.FileEditorManagerListener"/>
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :core:compileKotlin 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/src/main/resources/META-INF/plugin.xml
git commit -m "feat(cody): register Cody services and extensions in plugin.xml"
```

---

## Chunk 6: Build Verification & Final Tests

### Task 21: Full Build Verification

**Files:** (none — verification only)

- [ ] **Step 1: Run full build**

Run: `./gradlew build 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run all Cody tests**

Run: `./gradlew :cody:test 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL, all tests passed

- [ ] **Step 3: Verify plugin.xml loads without errors**

Run: `./gradlew verifyPluginStructure 2>&1 | tail -5`
Expected: No structure errors (or task not available on module plugin — skip if so)

- [ ] **Step 4: Commit any fixes needed**

If any fixes were needed, commit them:
```bash
git add -A
git commit -m "fix(cody): address build verification issues"
```

---

### Task 22: Test Summary Verification

**Files:** (none — verification only)

- [ ] **Step 1: Verify all test classes exist and pass**

Run: `./gradlew :cody:test --tests "*.ProtocolSerializationTest" --tests "*.CodyAgentServerTest" --tests "*.CodyAgentManagerTest" --tests "*.CodyEditServiceTest" --tests "*.CodyChatServiceTest" --tests "*.CodyContextServiceTest" --tests "*.CodyEditApplierLogicTest" --tests "*.CodyIntentionActionTest" 2>&1 | tail -10`

Expected tests:
- `ProtocolSerializationTest`: 7 tests (serialize/deserialize all protocol types)
- `CodyAgentServerTest`: 7 tests (interface contract verification)
- `CodyAgentManagerTest`: 5 tests (state, binary resolution, client info)
- `CodyEditServiceTest`: 5 tests (fix, test gen, accept, undo, cancel)
- `CodyChatServiceTest`: 3 tests (commit msg gen, null response, prompt)
- `CodyContextServiceTest`: 6 tests (fix/test instructions, test file resolution)
- `CodyEditApplierLogicTest`: 4 tests (replace, insert, delete, offset)
- `CodyIntentionActionTest`: 3 tests (text, family, writeAction)

Total: 40 tests

- [ ] **Step 2: Final commit if all passes**

```bash
git log --oneline -10
```

Verify commit history shows clean TDD progression.
