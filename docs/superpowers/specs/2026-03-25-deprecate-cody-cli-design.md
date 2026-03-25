# Deprecate Cody CLI — Direct Sourcegraph API + Agent Chat Redirect

**Date:** 2026-03-25
**Status:** Approved
**Scope:** Replace Cody CLI (JSON-RPC subprocess) with direct Sourcegraph HTTP API for simple tasks, redirect complex tasks to agent chat UI.

---

## Problem

The plugin has two independent LLM communication paths:

1. **Cody CLI (`:cody` module)** — JSON-RPC over stdio to a `cody api jsonrpc-stdio` subprocess. Used by 7 features: commit message, PR description, branch name, code fix, sonar fix, test generation, generic text gen.
2. **Direct HTTP (`:agent` module)** — `SourcegraphChatClient` calling `/.api/llm/chat/completions` with SSE streaming. Used by the AI agent ReAct loop.

Both hit the same Sourcegraph API, but the Cody CLI path:
- Adds a subprocess middleman (process lifecycle, binary path config, stderr logging)
- Cannot stream tokens (JSON-RPC `CompletableFuture.await()` blocks until full response)
- Applies code fixes blindly (single prompt, no verification)
- Requires a separate npm binary (`@sourcegraph/cody`) installed on disk

The direct HTTP path already handles streaming, tool calls, retry with backoff, and self-verification via the ReAct loop.

## Solution

Deprecate the `:cody` module. Replace with:

- **Simple tasks** (commit msg, PR desc, branch name): Direct `LlmBrain.chatStream()` calls from `:core`, with streaming tokens to the UI.
- **Complex tasks** (code fix, sonar fix, test gen): Redirect to the agent chat tab with a pre-built prompt and `@file` mentions. The agent's ReAct loop handles reading, fixing, and verifying.

## Architecture

### Before

```
Feature modules ──→ TextGenerationService (ext point) ──→ CodyTextGenerationService
                                                              ↓
                                                         CodyChatService
                                                              ↓
                                                         CodyAgentServer (JSON-RPC)
                                                              ↓
                                                         cody CLI subprocess
                                                              ↓
                                                         Sourcegraph API
```

### After

```
Simple features ──→ TextGenerationService (ext point) ──→ SourcegraphTextGenerationService
                                                              ↓
                                                         LlmBrain.chatStream() (in :core)
                                                              ↓
                                                         SourcegraphChatClient (direct HTTP + SSE)
                                                              ↓
                                                         Sourcegraph API

Complex features ──→ AgentChatRedirect.sendToAgent()
                         ↓
                    AgentController.executeTask() (opens agent tab, sends prompt + @file mentions)
                         ↓
                    SingleAgentSession (ReAct loop with tools)
                         ↓
                    SourcegraphChatClient (streaming)
```

## Detailed Design

### Phase 1: Move LLM Client to `:core`

Move these classes from `:agent` to `:core`:

| Class | Current Location | New Location |
|-------|-----------------|--------------|
| `LlmBrain` | `agent/brain/LlmBrain.kt` | `core/ai/LlmBrain.kt` |
| `OpenAiCompatBrain` | `agent/brain/OpenAiCompatBrain.kt` | `core/ai/OpenAiCompatBrain.kt` |
| `SourcegraphChatClient` | `agent/api/SourcegraphChatClient.kt` | `core/ai/SourcegraphChatClient.kt` |
| `ChatCompletionModels.kt` | `agent/api/dto/ChatCompletionModels.kt` | `core/ai/dto/ChatCompletionModels.kt` |
| `StreamModels.kt` | `agent/api/dto/StreamModels.kt` | `core/ai/dto/StreamModels.kt` |
| `ListModelsResponse` | `agent/api/dto/ListModelsResponse.kt` | `core/ai/dto/ListModelsResponse.kt` |
| `TokenEstimator` | `agent/context/TokenEstimator.kt` | `core/ai/TokenEstimator.kt` |

**Why move to `:core`?** Feature modules (`:bamboo`, `:jira`, `:sonar`) depend only on `:core`. They cannot import from `:agent`. Moving the LLM client to `:core` makes it available everywhere without new dependencies.

**`:agent` keeps type aliases or re-exports** for backward compatibility during transition. All existing agent code continues to compile without changes.

**Message sanitization** (`sanitizeMessages()` in `SourcegraphChatClient`): Stays with the client. It handles Sourcegraph-specific constraints (no `system` role, no `tool` role, strict alternation) that apply regardless of caller.

**OkHttpClient:** The moved `SourcegraphChatClient` should use `HttpClientFactory.clientFor(ServiceType.SOURCEGRAPH)` for the base client (shared connection pool), with custom timeouts applied via `.newBuilder()`. This is consistent with the codebase pattern and avoids duplicate connection pools.

### Phase 1.5: Extract AI Settings to `:core`

`AgentSettings` lives in `:agent` and cannot be imported from `:core`. Extract the LLM-related fields into a new `:core` settings component:

```kotlin
// core/ai/AiSettings.kt
@Service(Service.Level.PROJECT)
@State(name = "WorkflowAiSettings", storages = [Storage("workflow-ai.xml")])
class AiSettings : PersistentStateComponent<AiSettings.State> {
    data class State(
        var sourcegraphChatModel: String = "anthropic/claude-sonnet-4",
        var maxOutputTokens: Int? = null
    )
    private var myState = State()
    override fun getState() = myState
    override fun loadState(state: State) { myState = state }

    companion object {
        fun getInstance(project: Project): AiSettings =
            project.getService(AiSettings::class.java)
    }
}
```

`AgentSettings` in `:agent` migrates: `sourcegraphChatModel` reads from `AiSettings` (with fallback to its own state for backward compatibility during transition). This ensures `LlmBrainFactory` in `:core` can access the model without importing `:agent`.

### Phase 2: New `TextGenerationService` Implementation

Create `SourcegraphTextGenerationService` in `:core` (replacing `CodyTextGenerationService` in `:cody`):

```kotlin
// core/ai/SourcegraphTextGenerationService.kt
class SourcegraphTextGenerationService : TextGenerationService {

    override suspend fun generateText(
        project: Project,
        prompt: String,
        contextFilePaths: List<String>
    ): String? {
        val brain = LlmBrainFactory.create(project)

        // Build context: read file contents, include in prompt
        val contextBlock = contextFilePaths.mapNotNull { path ->
            val file = File(path)
            if (file.exists()) "File: ${file.name}\n```\n${file.readText().take(50_000)}\n```" else null
        }.joinToString("\n\n")

        val fullPrompt = if (contextBlock.isNotBlank()) {
            "$contextBlock\n\n$prompt"
        } else prompt

        val messages = listOf(ChatMessage(role = "user", content = fullPrompt))
        val result = brain.chatStream(messages, tools = null) { /* streaming optional here */ }

        return when (result) {
            is ApiResult.Success -> result.data.choices.firstOrNull()?.message?.content
            is ApiResult.Error -> null
        }
    }

    override suspend fun generatePrDescription(
        project: Project,
        diff: String,
        commitMessages: List<String>,
        contextFilePaths: List<String>,
        ticketId: String,
        ticketSummary: String,
        ticketDescription: String,
        sourceBranch: String,
        targetBranch: String
    ): String? {
        val brain = LlmBrainFactory.create(project)
        val prompt = PrDescriptionPromptBuilder.build(
            diff, commitMessages, ticketId, ticketSummary,
            ticketDescription, sourceBranch, targetBranch
        )
        val messages = listOf(ChatMessage(role = "user", content = prompt))
        val result = brain.chatStream(messages, tools = null) { /* no-op */ }
        return when (result) {
            is ApiResult.Success -> {
                result.data.choices.firstOrNull()?.message?.content
                    ?.replace(Regex("^```[a-z]*\\n?"), "")
                    ?.replace(Regex("\\n?```$"), "")
                    ?.trim()
            }
            is ApiResult.Error -> null
        }
    }
}
```

**`LlmBrainFactory`** — small factory in `:core` that creates an `OpenAiCompatBrain` from settings:

```kotlin
// core/ai/LlmBrainFactory.kt
object LlmBrainFactory {
    fun create(project: Project): LlmBrain {
        val connections = ConnectionSettings.getInstance()
        val credentialStore = CredentialStore()
        val aiSettings = AiSettings.getInstance(project)
        return OpenAiCompatBrain(
            sourcegraphUrl = connections.state.sourcegraphUrl.trimEnd('/'),
            tokenProvider = { credentialStore.getToken(ServiceType.SOURCEGRAPH) },
            model = aiSettings.state.sourcegraphChatModel
        )
    }

    /** Returns true if Sourcegraph URL and token are configured. */
    fun isAvailable(): Boolean {
        val url = ConnectionSettings.getInstance().state.sourcegraphUrl
        return !url.isNullOrBlank() && CredentialStore().hasToken(ServiceType.SOURCEGRAPH)
    }
}
```

**Prompt builders** — extracted from `CodyChatService` into reusable utilities:

| Builder | Source | New Location |
|---------|--------|-------------|
| `CommitMessagePromptBuilder` | `CodyChatService.generateCommitMessageChained()` | `core/ai/prompts/CommitMessagePromptBuilder.kt` |
| `PrDescriptionPromptBuilder` | `CodyChatService.generatePrDescriptionChained()` | `core/ai/prompts/PrDescriptionPromptBuilder.kt` |

These are pure functions that build prompt strings from inputs (diff, commits, ticket info). No dependencies on Cody or agent infrastructure.

### Phase 3: New `BranchNameAiGenerator` Implementation

Create `SourcegraphBranchNameGenerator` in `:core` (replacing `CodyBranchNameGeneratorImpl` in `:cody`):

```kotlin
// core/ai/SourcegraphBranchNameGenerator.kt
class SourcegraphBranchNameGenerator : BranchNameAiGenerator {

    override suspend fun generateBranchSlug(
        project: Project,
        ticketKey: String,
        title: String,
        description: String
    ): String? {
        val brain = LlmBrainFactory.create(project)
        val prompt = """
            Generate a short git branch slug for this Jira ticket.
            Ticket: $ticketKey
            Title: $title
            Description: ${description.take(300)}

            Output ONLY the slug (lowercase, hyphens, max 50 chars).
            Example: fix-null-pointer-order-service
        """.trimIndent()

        val messages = listOf(ChatMessage(role = "user", content = prompt))
        // Non-streaming — tiny response, not worth streaming
        val result = brain.chat(messages, tools = null)

        return when (result) {
            is ApiResult.Success -> {
                result.data.choices.firstOrNull()?.message?.content
                    ?.trim()
                    ?.removeSurrounding("\"")
                    ?.removeSurrounding("`")
                    ?.replace(Regex("[^a-z0-9-]"), "-")
                    ?.replace(Regex("-+"), "-")
                    ?.trim('-')
                    ?.take(50)
            }
            is ApiResult.Error -> null
        }
    }
}
```

### Phase 4: Agent Chat Redirect for Complex Tasks

Create `AgentChatRedirect` in `:core` as a lightweight bridge to the agent tab:

```kotlin
// core/ai/AgentChatRedirect.kt
interface AgentChatRedirect {
    fun sendToAgent(
        project: Project,
        prompt: String,
        filePaths: List<String> = emptyList()
    )

    companion object {
        fun getInstance(): AgentChatRedirect? {
            return com.intellij.openapi.extensions.ExtensionPointName
                .create<AgentChatRedirect>("com.workflow.orchestrator.agentChatRedirect")
                .extensionList.firstOrNull()
        }
    }
}
```

**Controller lookup:** `AgentController` is not a service — it's a UI-coupled object created by `AgentDashboardPanel`. To make it discoverable for programmatic access, introduce a project-level service:

```kotlin
// agent/ui/AgentControllerRegistry.kt
@Service(Service.Level.PROJECT)
class AgentControllerRegistry {
    @Volatile
    var controller: AgentController? = null

    companion object {
        fun getInstance(project: Project): AgentControllerRegistry =
            project.getService(AgentControllerRegistry::class.java)
    }
}
```

`AgentDashboardPanel` registers the controller on creation: `AgentControllerRegistry.getInstance(project).controller = controller`

**New public method on `AgentController`:**

```kotlin
// Added to AgentController.kt
fun executeTaskWithMentions(prompt: String, filePaths: List<String>) {
    if (filePaths.isNotEmpty()) {
        val mentions = filePaths.map { path ->
            MentionContextBuilder.Mention(
                type = "file",
                name = java.io.File(path).name,
                value = path
            )
        }
        scope.launch(Dispatchers.IO) {
            val context = mentionContextBuilder.buildContext(mentions)
            if (context != null) {
                session?.contextManager?.setMentionAnchor(
                    ChatMessage(role = "system", content = "<mentioned_context>\n$context</mentioned_context>")
                )
            }
            SwingUtilities.invokeLater { executeTask(prompt) }
        }
    } else {
        executeTask(prompt)
    }
}
```

**Implementation in `:agent`:**

```kotlin
// agent/ui/AgentChatRedirectImpl.kt
class AgentChatRedirectImpl : AgentChatRedirect {
    override fun sendToAgent(
        project: Project,
        prompt: String,
        filePaths: List<String>
    ) {
        // Focus the Workflow tool window, switch to agent tab
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Workflow")
        toolWindow?.activate {
            val contentManager = toolWindow.contentManager
            val agentContent = contentManager.contents.find { it.displayName == "Agent" }
            agentContent?.let { contentManager.setSelectedContent(it) }
        }

        // Get controller via registry and send message with file mentions
        val controller = AgentControllerRegistry.getInstance(project).controller
        if (controller == null) {
            // Agent tab hasn't been opened yet — queue will be processed on first open
            LOG.warn("AgentController not initialized — open the Agent tab first")
            return
        }
        controller.executeTaskWithMentions(prompt, filePaths)
    }
}
```

### Phase 5: Rewire UI Actions

#### Code Fix (Alt+Enter) — `CodyIntentionAction` replacement

Move to `:sonar` (where the issue data lives) or keep in `:cody` as deprecated wrapper:

```kotlin
// New: sonar/editor/FixWithAgentIntentionAction.kt
class FixWithAgentIntentionAction : IntentionAction {

    override fun getText(): String = "Fix with AI Agent"
    override fun getFamilyName(): String = "Workflow Orchestrator"

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (editor == null || file == null) return false
        if (!CredentialStore().hasToken(ServiceType.SOURCEGRAPH)) return false
        val caretLine = editor.caretModel.logicalPosition.line
        return editor.markupModel.allHighlighters.any { hl ->
            val startLine = editor.document.getLineNumber(hl.startOffset)
            startLine == caretLine && hl.getUserData(SonarIssueAnnotator.SONAR_ISSUE_KEY) != null
        }
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file == null) return

        val issue = findSonarIssueAtCaret(editor, editor.caretModel.offset) ?: return
        val filePath = file.virtualFile.path

        val prompt = buildString {
            appendLine("Fix the following SonarQube issue in this file.")
            appendLine()
            appendLine("**Issue:** [${issue.rule}] ${issue.message}")
            appendLine("**Type:** ${issue.type}")
            appendLine("**File:** ${issue.filePath}")
            appendLine("**Lines:** ${issue.startLine}-${issue.endLine}")
            appendLine()
            appendLine("Read the file, understand the surrounding code, apply a minimal fix that resolves the issue without changing behavior, and verify with diagnostics that the fix compiles.")
        }

        AgentChatRedirect.getInstance()?.sendToAgent(project, prompt, listOf(filePath))
    }
}
```

#### Sonar Issue List Fix — `IssueListPanel.fixWithCody()` replacement

```kotlin
// In sonar/ui/IssueListPanel.kt — replace fixWithCody():
private fun fixWithAgent(issue: MappedIssue) {
    val basePath = project.basePath ?: return
    val absolutePath = java.io.File(basePath, issue.filePath).absolutePath

    val prompt = buildString {
        appendLine("Fix this SonarQube issue:")
        appendLine()
        appendLine("**Rule:** ${issue.rule}")
        appendLine("**Message:** ${issue.message}")
        appendLine("**File:** ${issue.filePath}")
        appendLine("**Line:** ${issue.startLine}")
        appendLine()
        appendLine("Read the file, understand the context, apply a minimal fix, and verify it compiles with diagnostics.")
    }

    AgentChatRedirect.getInstance()?.sendToAgent(project, prompt, listOf(absolutePath))
}
```

#### Test Generation — `CodyTestGenerator` replacement

```kotlin
// New: sonar/editor/TestWithAgentGutterAction.kt (or keep in :cody as deprecated wrapper)
// Click handler lambda in LineMarkerInfo:
{ _, _ ->
    val prompt = buildString {
        appendLine("Generate unit tests for the uncovered method in this file.")
        appendLine()
        appendLine("**Source file:** $relativePath")
        appendLine("**Method lines:** $methodStartLine-$methodEndLine")
        existingTestFile?.let { appendLine("**Existing test file:** $it") }
        appendLine()
        appendLine("**Instructions:**")
        appendLine("- Use JUnit 5 with standard assertions")
        appendLine("- Read existing tests to match the project's test style and imports")
        appendLine("- If an existing test file exists, add new test methods to it")
        appendLine("- If no test file exists, create one with proper package and imports")
        appendLine("- Run the tests after writing them to verify they pass")
        appendLine("- If tests fail, read the error and fix them")
    }

    val filePaths = mutableListOf(virtualFile.path)
    existingTestFile?.let { filePaths.add(it) }

    AgentChatRedirect.getInstance()?.sendToAgent(project, prompt, filePaths)
}
```

#### Commit Message — `GenerateCommitMessageAction` stays inline

```kotlin
// In cody/vcs/GenerateCommitMessageAction.kt — replace CodyChatService with LlmBrain:
val brain = LlmBrainFactory.create(project)
val prompt = CommitMessagePromptBuilder.build(diff, contextItems, ticketId, recentCommits, codeContext)
val messages = listOf(ChatMessage(role = "user", content = prompt))

// Accumulate tokens, batch UI updates every 50ms to avoid flicker
val buffer = StringBuilder()
val result = brain.chatStream(messages, tools = null) { chunk ->
    chunk.choices.firstOrNull()?.delta?.content?.let { token ->
        buffer.append(token)
    }
}
// Final update with complete text
SwingUtilities.invokeLater {
    commitMessageField.text = buffer.toString()
        .replace(Regex("^```[a-z]*\\n?"), "")
        .replace(Regex("\\n?```$"), "")
        .trim()
}
```

#### Commit Message Handler — `CodyCommitMessageHandlerFactory` stays inline

The handler factory's `generateMessage()` uses the simpler single-turn `CodyChatService.generateCommitMessage()`. Replace with:

```kotlin
// In cody/vcs/CodyCommitMessageHandlerFactory.kt — replace:
val brain = LlmBrainFactory.create(panel.project)
val prompt = CommitMessagePromptBuilder.buildSimple(diff, contextItems)
val messages = listOf(ChatMessage(role = "user", content = prompt))
val result = brain.chat(messages, tools = null)
return when (result) {
    is ApiResult.Success -> result.data.choices.firstOrNull()?.message?.content?.trim()
    is ApiResult.Error -> null
}
```

### Phase 6: Deprecate `:cody` Module

1. **Mark as `@Deprecated`** in all public classes
2. **Remove extension point registrations** from `plugin.xml`:
   - `codyAgentProvider` → removed (no replacement needed)
   - `textGenerationService` → points to `SourcegraphTextGenerationService`
   - `branchNameAiGenerator` → points to `SourcegraphBranchNameGenerator`
3. **Remove settings:**
   - `codyAgentPath` → no longer needed (no subprocess)
   - `codyEnabled` → replaced by checking `sourcegraphUrl` + token presence
4. **Keep module in repo** but exclude from build (comment out in `settings.gradle.kts`)
5. **Update CLAUDE.md** to reflect architecture change

### Phase 7: Settings Cleanup

Remove from `PluginSettings`:
- `codyAgentPath: String` — no binary needed
- `codyEnabled: Boolean` — presence of Sourcegraph URL + token is sufficient

Remove from Settings UI:
- Cody binary path field
- Cody enable/disable toggle

Keep (shared with agent):
- `sourcegraphUrl` in `ConnectionSettings`
- Sourcegraph token in `CredentialStore`
- `sourcegraphChatModel` in `AiSettings` (new, in `:core` — see Phase 1.5)

## New Extension Points

| Extension Point | Interface | Implementation | Module |
|----------------|-----------|----------------|--------|
| `textGenerationService` | `TextGenerationService` (`:core`) | `SourcegraphTextGenerationService` | `:core` |
| `branchNameAiGenerator` | `BranchNameAiGenerator` (`:core`) | `SourcegraphBranchNameGenerator` | `:core` |
| `agentChatRedirect` (NEW) | `AgentChatRedirect` (`:core`) | `AgentChatRedirectImpl` | `:agent` |

## What Each Feature Gets

| Feature | Before (Cody CLI) | After |
|---------|-------------------|-------|
| Commit message | Spinner → full text appears | Streaming tokens into field |
| PR description | Spinner → full text appears | Streaming tokens |
| Branch name | Spinner → slug appears | Same (tiny output, no streaming needed) |
| Code fix | Spinner → code silently replaced | Agent tab opens, user watches fix + verification |
| Sonar fix | Spinner → notification with suggestion | Agent tab opens, user watches fix + verification |
| Test generation | Spinner → code silently added | Agent tab opens, user watches gen + test run |

## Migration Checklist

### Phase 1 — Move LLM Client
- [ ] Move `LlmBrain`, `OpenAiCompatBrain`, `SourcegraphChatClient`, DTOs (`ChatCompletionModels`, `StreamModels`, `ListModelsResponse`), `TokenEstimator` to `core/ai/`
- [ ] Leave type aliases in `:agent` for backward compatibility
- [ ] Wire `SourcegraphChatClient` to use `HttpClientFactory.clientFor(ServiceType.SOURCEGRAPH)` with custom timeouts

### Phase 1.5 — Extract AI Settings
- [ ] Create `AiSettings` in `core/ai/` with `sourcegraphChatModel` and `maxOutputTokens`
- [ ] Register `AiSettings` as project service in `plugin.xml`
- [ ] Migrate `AgentSettings.sourcegraphChatModel` to read from `AiSettings` (with fallback)

### Phase 2-3 — New Service Implementations
- [ ] Create `LlmBrainFactory` in `core/ai/`
- [ ] Create `CommitMessagePromptBuilder` in `core/ai/prompts/`
- [ ] Create `PrDescriptionPromptBuilder` in `core/ai/prompts/`
- [ ] Create `SourcegraphTextGenerationService` in `core/ai/`
- [ ] Create `SourcegraphBranchNameGenerator` in `core/ai/`

### Phase 4 — Agent Chat Redirect
- [ ] Create `AgentChatRedirect` interface in `core/ai/`
- [ ] Add `agentChatRedirect` extension point declaration to `src/main/resources/META-INF/plugin.xml`
- [ ] Create `AgentControllerRegistry` project service in `:agent`
- [ ] Register controller in `AgentDashboardPanel` on creation
- [ ] Add `executeTaskWithMentions(prompt, filePaths)` method to `AgentController`
- [ ] Create `AgentChatRedirectImpl` in `:agent`
- [ ] Register `AgentChatRedirectImpl` in `src/main/resources/META-INF/plugin.xml`

### Phase 5 — Rewire UI Actions
- [ ] Rewire `GenerateCommitMessageAction` → `LlmBrainFactory` + `CommitMessagePromptBuilder`
- [ ] Rewire `CodyCommitMessageHandlerFactory` → `LlmBrainFactory` + `CommitMessagePromptBuilder`
- [ ] Rewire `PrDescriptionGenerator` → `TextGenerationService` (auto-resolves to new impl)
- [ ] Replace `CodyIntentionAction` → `FixWithAgentIntentionAction` (uses `AgentChatRedirect`)
- [ ] Replace `IssueListPanel.fixWithCody()` → `fixWithAgent()` using `AgentChatRedirect`
- [ ] Replace `CodyTestGenerator` → test gutter action using `AgentChatRedirect`

### Phase 6-7 — Deprecate & Cleanup
- [ ] Update `plugin.xml`: change `textGenerationService` impl to `SourcegraphTextGenerationService`
- [ ] Update `plugin.xml`: change `branchNameAiGenerator` impl to `SourcegraphBranchNameGenerator`
- [ ] Remove `codyAgentProvider` extension point declaration and implementation
- [ ] Remove `codyAgentPath` and `codyEnabled` from `PluginSettings`
- [ ] Remove Cody settings UI fields from settings panel
- [ ] Comment out `:cody` from `settings.gradle.kts`
- [ ] Update `:agent` imports from `agent.api.dto.*` to `core.ai.dto.*`
- [ ] Update root `CLAUDE.md`, `cody/CLAUDE.md`, and `docs/architecture/`

## Risks & Mitigations

| Risk | Mitigation |
|------|-----------|
| Sourcegraph SSE doesn't relay tool_calls properly | Empty tool name filter already exists at 3 layers; non-streaming fallback in `LlmBrain` |
| Agent tab not initialized when redirect fires | `AgentChatRedirect.sendToAgent()` activates tool window first; `AgentControllerRegistry` provides lookup; logs warning if controller is null |
| Commit message streaming to VCS field causes flicker | Batch tokens in `StringBuilder`, apply final text in one `invokeLater` call |
| Users with Cody binary path configured | Setting becomes no-op; no breakage, just unused |
| `:agent` module not loaded (disabled by user) | `AgentChatRedirect.getInstance()` returns null; fix/test features show "AI Agent not available". Simple features (commit msg, PR desc, branch name) are unaffected — they use `LlmBrainFactory` in `:core` directly |
| Sourcegraph URL or token not configured | `LlmBrainFactory.isAvailable()` returns false; callers already handle null returns from `TextGenerationService.generateText()` and `BranchNameAiGenerator.generateBranchSlug()` gracefully (fall back to manual input) |
| Large files in context (`file.readText()`) | `SourcegraphTextGenerationService` uses buffered reading with 50K char cap per file; total context budget enforced by Sourcegraph API's input token limit |

## Out of Scope

- **Cody context enrichment** (`PsiContextEnricher`, `SpringContextEnricher`): These are valuable utilities but the agent gathers its own context via tools. They can be moved to `:core` later if inline features want richer prompts.
- **`CodyDocumentSyncListener`**: No longer needed — the agent reads files on demand.
- **`CodyEditApplier`**: The agent uses its own `edit_file` tool with undo support.
