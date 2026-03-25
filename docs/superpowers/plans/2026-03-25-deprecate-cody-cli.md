# Deprecate Cody CLI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Cody CLI JSON-RPC subprocess with direct Sourcegraph HTTP API calls for simple LLM tasks, and redirect complex tasks (code fix, test gen) to the agent chat UI.

**Architecture:** Move `LlmBrain`, `SourcegraphChatClient`, and DTOs from `:agent` to `:core` so all modules can access the LLM directly. Create new implementations of `TextGenerationService` and `BranchNameAiGenerator` that use direct HTTP. Add `AgentChatRedirect` extension point so fix/test actions can open the agent chat with a pre-built prompt. Use type aliases in `:agent` to avoid rewriting 175 import statements.

**Tech Stack:** Kotlin, OkHttp (SSE streaming), kotlinx.serialization, IntelliJ Platform SDK extension points

**Spec:** `docs/superpowers/specs/2026-03-25-deprecate-cody-cli-design.md`

---

## File Structure

### New files in `:core`

```
core/src/main/kotlin/com/workflow/orchestrator/core/ai/
  LlmBrain.kt                          # Interface (moved from agent/brain/)
  OpenAiCompatBrain.kt                  # Implementation (moved from agent/brain/)
  SourcegraphChatClient.kt              # HTTP client (moved from agent/api/)
  TokenEstimator.kt                     # Token estimation (moved from agent/context/)
  LlmBrainFactory.kt                    # Factory: creates brain from settings (NEW)
  AiSettings.kt                         # Project-level AI settings (NEW)
  AgentChatRedirect.kt                  # Interface for redirecting to agent chat (NEW)
  SourcegraphTextGenerationService.kt   # Replaces CodyTextGenerationService (NEW)
  SourcegraphBranchNameGenerator.kt     # Replaces CodyBranchNameGeneratorImpl (NEW)
  dto/
    ChatCompletionModels.kt             # DTOs (moved from agent/api/dto/)
    StreamModels.kt                     # Stream DTOs (moved from agent/api/dto/)
    ToolCallModels.kt                   # Tool/function DTOs (moved from agent/api/dto/)
    ModelModels.kt                      # Model listing DTOs (moved from agent/api/dto/)
  prompts/
    CommitMessagePromptBuilder.kt       # Extracted from CodyChatService (NEW)
    PrDescriptionPromptBuilder.kt       # Extracted from CodyChatService (NEW)
```

### New files in `:agent`

```
agent/src/main/kotlin/com/workflow/orchestrator/agent/
  ui/
    AgentControllerRegistry.kt          # Project service for controller lookup (NEW)
    AgentChatRedirectImpl.kt            # Extension point implementation (NEW)
  brain/
    LlmBrain.kt                        # Type alias re-exporting from core (REPLACE)
    OpenAiCompatBrain.kt               # Type alias re-exporting from core (REPLACE)
  api/
    SourcegraphChatClient.kt           # Type alias re-exporting from core (REPLACE)
    dto/
      ChatCompletionModels.kt          # Type aliases re-exporting from core (REPLACE)
      StreamModels.kt                  # Type aliases re-exporting from core (REPLACE)
      ToolCallModels.kt               # Type aliases re-exporting from core (REPLACE)
      ModelModels.kt                   # Type aliases re-exporting from core (REPLACE)
  context/
    TokenEstimator.kt                  # Type alias re-exporting from core (REPLACE)
```

### Modified files

```
src/main/resources/META-INF/plugin.xml   # Extension point changes
core/src/main/kotlin/.../settings/PluginSettings.kt  # Remove codyAgentPath, codyEnabled
agent/src/main/kotlin/.../ui/AgentTabProvider.kt      # Register controller in registry
agent/src/main/kotlin/.../ui/AgentController.kt       # Add executeTaskWithMentions()
agent/src/main/kotlin/.../settings/AgentSettings.kt   # Delegate to AiSettings
agent/src/main/kotlin/.../AgentService.kt              # Use core brain imports
sonar/src/main/kotlin/.../ui/IssueListPanel.kt         # fixWithCody → fixWithAgent
```

---

## Task 1: Move DTOs to `:core`

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/ai/dto/ChatCompletionModels.kt`
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/ai/dto/StreamModels.kt`
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/ai/dto/ModelModels.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/api/dto/ChatCompletionModels.kt` (replace with type aliases)
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/api/dto/StreamModels.kt` (replace with type aliases)
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/api/dto/ModelModels.kt` (replace with type aliases)

- [ ] **Step 1: Copy ChatCompletionModels.kt to core**

Read `agent/src/main/kotlin/com/workflow/orchestrator/agent/api/dto/ChatCompletionModels.kt`. Copy its content to `core/src/main/kotlin/com/workflow/orchestrator/core/ai/dto/ChatCompletionModels.kt`, changing only the package declaration to `com.workflow.orchestrator.core.ai.dto`.

- [ ] **Step 2: Copy StreamModels.kt to core**

Read `agent/src/main/kotlin/com/workflow/orchestrator/agent/api/dto/StreamModels.kt`. Copy to `core/src/main/kotlin/com/workflow/orchestrator/core/ai/dto/StreamModels.kt`, changing package to `com.workflow.orchestrator.core.ai.dto`.

- [ ] **Step 3: Copy ToolCallModels.kt to core**

Read `agent/src/main/kotlin/com/workflow/orchestrator/agent/api/dto/ToolCallModels.kt`. Copy to `core/src/main/kotlin/com/workflow/orchestrator/core/ai/dto/ToolCallModels.kt`, changing package to `com.workflow.orchestrator.core.ai.dto`. Contains: `ToolDefinition`, `FunctionDefinition`, `FunctionParameters`, `ParameterProperty`, `ToolCall`, `FunctionCall`.

- [ ] **Step 4: Copy ModelModels.kt to core**

Read `agent/src/main/kotlin/com/workflow/orchestrator/agent/api/dto/ModelModels.kt`. Copy to `core/src/main/kotlin/com/workflow/orchestrator/core/ai/dto/ModelModels.kt`, changing package to `com.workflow.orchestrator.core.ai.dto`.

- [ ] **Step 5: Replace agent DTOs with type aliases**

Replace each original file in `:agent` with type aliases that re-export from `:core`. This ensures all 175 existing imports in `:agent` continue to compile without changes.

For `agent/src/main/kotlin/com/workflow/orchestrator/agent/api/dto/ChatCompletionModels.kt`:
```kotlin
@file:Suppress("unused")
package com.workflow.orchestrator.agent.api.dto

typealias ChatCompletionRequest = com.workflow.orchestrator.core.ai.dto.ChatCompletionRequest
typealias ChatMessage = com.workflow.orchestrator.core.ai.dto.ChatMessage
typealias ChatCompletionResponse = com.workflow.orchestrator.core.ai.dto.ChatCompletionResponse
typealias Choice = com.workflow.orchestrator.core.ai.dto.Choice
typealias UsageInfo = com.workflow.orchestrator.core.ai.dto.UsageInfo
```

For `agent/src/main/kotlin/com/workflow/orchestrator/agent/api/dto/ToolCallModels.kt`:
```kotlin
@file:Suppress("unused")
package com.workflow.orchestrator.agent.api.dto

typealias ToolDefinition = com.workflow.orchestrator.core.ai.dto.ToolDefinition
typealias FunctionDefinition = com.workflow.orchestrator.core.ai.dto.FunctionDefinition
typealias FunctionParameters = com.workflow.orchestrator.core.ai.dto.FunctionParameters
typealias ParameterProperty = com.workflow.orchestrator.core.ai.dto.ParameterProperty
typealias ToolCall = com.workflow.orchestrator.core.ai.dto.ToolCall
typealias FunctionCall = com.workflow.orchestrator.core.ai.dto.FunctionCall
```

Do the same pattern for `StreamModels.kt` and `ModelModels.kt` — each type gets a typealias line.

- [ ] **Step 5: Verify compilation**

Run: `./gradlew :core:compileKotlin :agent:compileKotlin`
Expected: BUILD SUCCESSFUL — no import errors in either module.

- [ ] **Step 6: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/ai/dto/ \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/api/dto/
git commit -m "refactor: move LLM DTOs to :core, type aliases in :agent"
```

---

## Task 2: Move `TokenEstimator` to `:core`

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/ai/TokenEstimator.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/context/TokenEstimator.kt` (replace with type alias)

- [ ] **Step 1: Copy TokenEstimator to core**

Read `agent/src/main/kotlin/com/workflow/orchestrator/agent/context/TokenEstimator.kt`. Copy to `core/src/main/kotlin/com/workflow/orchestrator/core/ai/TokenEstimator.kt`, changing package to `com.workflow.orchestrator.core.ai`.

- [ ] **Step 2: Replace agent TokenEstimator with type alias**

```kotlin
package com.workflow.orchestrator.agent.context
typealias TokenEstimator = com.workflow.orchestrator.core.ai.TokenEstimator
```

- [ ] **Step 3: Verify compilation**

Run: `./gradlew :core:compileKotlin :agent:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/ai/TokenEstimator.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/context/TokenEstimator.kt
git commit -m "refactor: move TokenEstimator to :core"
```

---

## Task 3: Move `LlmBrain` and `OpenAiCompatBrain` to `:core`

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/ai/LlmBrain.kt`
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/ai/OpenAiCompatBrain.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/brain/LlmBrain.kt` (replace with type alias)
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/brain/OpenAiCompatBrain.kt` (replace with type alias)

- [ ] **Step 1: Copy LlmBrain to core**

Read `agent/src/main/kotlin/com/workflow/orchestrator/agent/brain/LlmBrain.kt`. Copy to `core/src/main/kotlin/com/workflow/orchestrator/core/ai/LlmBrain.kt`. Change:
- Package to `com.workflow.orchestrator.core.ai`
- Imports from `agent.api.dto.*` to `core.ai.dto.*`

- [ ] **Step 2: Copy OpenAiCompatBrain to core**

Read `agent/src/main/kotlin/com/workflow/orchestrator/agent/brain/OpenAiCompatBrain.kt`. Copy to `core/src/main/kotlin/com/workflow/orchestrator/core/ai/OpenAiCompatBrain.kt`. Change:
- Package to `com.workflow.orchestrator.core.ai`
- Imports from `agent.api.dto.*` to `core.ai.dto.*`
- Imports from `agent.api.SourcegraphChatClient` to `core.ai.SourcegraphChatClient`
- Imports from `agent.context.TokenEstimator` to `core.ai.TokenEstimator`

- [ ] **Step 3: Replace agent brain files with type aliases**

`agent/src/main/kotlin/com/workflow/orchestrator/agent/brain/LlmBrain.kt`:
```kotlin
package com.workflow.orchestrator.agent.brain
typealias LlmBrain = com.workflow.orchestrator.core.ai.LlmBrain
```

`agent/src/main/kotlin/com/workflow/orchestrator/agent/brain/OpenAiCompatBrain.kt`:
```kotlin
package com.workflow.orchestrator.agent.brain
typealias OpenAiCompatBrain = com.workflow.orchestrator.core.ai.OpenAiCompatBrain
```

- [ ] **Step 4: Verify compilation**

Run: `./gradlew :core:compileKotlin :agent:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/ai/LlmBrain.kt \
        core/src/main/kotlin/com/workflow/orchestrator/core/ai/OpenAiCompatBrain.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/brain/
git commit -m "refactor: move LlmBrain and OpenAiCompatBrain to :core"
```

---

## Task 4: Move `SourcegraphChatClient` to `:core`

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/ai/SourcegraphChatClient.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/api/SourcegraphChatClient.kt` (replace with type alias)

- [ ] **Step 1: Copy SourcegraphChatClient to core**

Read `agent/src/main/kotlin/com/workflow/orchestrator/agent/api/SourcegraphChatClient.kt`. Copy to `core/src/main/kotlin/com/workflow/orchestrator/core/ai/SourcegraphChatClient.kt`. Change:
- Package to `com.workflow.orchestrator.core.ai`
- Imports from `agent.api.dto.*` to `core.ai.dto.*`
- Import `core.http.AuthInterceptor` and `core.http.RetryInterceptor` (already in `:core`, no change needed)
- Import `core.model.ApiResult` and `core.model.ErrorType` (already in `:core`, no change needed)

- [ ] **Step 2: Replace agent SourcegraphChatClient with type alias**

```kotlin
package com.workflow.orchestrator.agent.api
typealias SourcegraphChatClient = com.workflow.orchestrator.core.ai.SourcegraphChatClient
```

- [ ] **Step 3: Verify compilation**

Run: `./gradlew :core:compileKotlin :agent:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/ai/SourcegraphChatClient.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/api/SourcegraphChatClient.kt
git commit -m "refactor: move SourcegraphChatClient to :core"
```

---

## Task 5: Create `AiSettings` in `:core`

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/ai/AiSettings.kt`
- Modify: `src/main/resources/META-INF/plugin.xml` (register as project service)

- [ ] **Step 1: Create AiSettings**

```kotlin
// core/src/main/kotlin/com/workflow/orchestrator/core/ai/AiSettings.kt
package com.workflow.orchestrator.core.ai

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(name = "WorkflowAiSettings", storages = [Storage("workflow-ai.xml")])
class AiSettings : PersistentStateComponent<AiSettings.State> {

    class State : BaseState() {
        var sourcegraphChatModel by string("anthropic::2024-10-22::claude-sonnet-4-20250514")
        var maxOutputTokens by property(64000)
    }

    private var myState = State()
    override fun getState() = myState
    override fun loadState(state: State) { myState = state }

    companion object {
        fun getInstance(project: Project): AiSettings =
            project.getService(AiSettings::class.java)
    }
}
```

- [ ] **Step 2: Register in plugin.xml**

In `src/main/resources/META-INF/plugin.xml`, add within the `<extensions defaultExtensionNs="com.intellij">` block (near the existing core services around line 160):

```xml
<projectService serviceImplementation="com.workflow.orchestrator.core.ai.AiSettings"/>
```

- [ ] **Step 3: Migrate AgentSettings to delegate to AiSettings**

In `agent/src/main/kotlin/com/workflow/orchestrator/agent/settings/AgentSettings.kt`, update the `sourcegraphChatModel` property to read from `AiSettings` as the primary source, falling back to its own state for backward compatibility:

```kotlin
// In AgentSettings — add a method that checks AiSettings first
fun getEffectiveModel(project: Project): String {
    return com.workflow.orchestrator.core.ai.AiSettings.getInstance(project)
        .state.sourcegraphChatModel
        ?: state.sourcegraphChatModel
        ?: "anthropic::2024-10-22::claude-sonnet-4-20250514"
}
```

Update `AgentService.kt` where `brain` is created to use `AiSettings` directly (it will now be available from `:core`).

- [ ] **Step 4: Verify compilation**

Run: `./gradlew :core:compileKotlin :agent:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/ai/AiSettings.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/settings/AgentSettings.kt \
        src/main/resources/META-INF/plugin.xml
git commit -m "feat: add AiSettings to :core, migrate AgentSettings to delegate"
```

---

## Task 6: Create `LlmBrainFactory` in `:core`

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/ai/LlmBrainFactory.kt`

- [ ] **Step 1: Create LlmBrainFactory**

```kotlin
// core/src/main/kotlin/com/workflow/orchestrator/core/ai/LlmBrainFactory.kt
package com.workflow.orchestrator.core.ai

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.ConnectionSettings

object LlmBrainFactory {

    fun create(project: Project): LlmBrain {
        val connections = ConnectionSettings.getInstance()
        val credentialStore = CredentialStore()
        val aiSettings = AiSettings.getInstance(project)
        return OpenAiCompatBrain(
            sourcegraphUrl = connections.state.sourcegraphUrl.trimEnd('/'),
            tokenProvider = { credentialStore.getToken(ServiceType.SOURCEGRAPH) },
            model = aiSettings.state.sourcegraphChatModel
                ?: "anthropic::2024-10-22::claude-sonnet-4-20250514"
        )
    }

    /** Returns true if Sourcegraph URL and token are configured. */
    fun isAvailable(): Boolean {
        val url = ConnectionSettings.getInstance().state.sourcegraphUrl
        return !url.isNullOrBlank() && CredentialStore().hasToken(ServiceType.SOURCEGRAPH)
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :core:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/ai/LlmBrainFactory.kt
git commit -m "feat: add LlmBrainFactory for creating LLM brain from settings"
```

---

## Task 7: Create Prompt Builders in `:core`

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/ai/prompts/CommitMessagePromptBuilder.kt`
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/ai/prompts/PrDescriptionPromptBuilder.kt`

- [ ] **Step 1: Create CommitMessagePromptBuilder**

Extract the prompt-building logic from `CodyChatService.generateCommitMessageChained()` (lines 41-93 of `cody/src/main/kotlin/com/workflow/orchestrator/cody/service/CodyChatService.kt`). Create a pure function in `:core`:

```kotlin
// core/src/main/kotlin/com/workflow/orchestrator/core/ai/prompts/CommitMessagePromptBuilder.kt
package com.workflow.orchestrator.core.ai.prompts

object CommitMessagePromptBuilder {

    fun build(
        diff: String,
        ticketId: String = "",
        filesSummary: String = "",
        recentCommits: List<String> = emptyList(),
        codeContext: String = ""
    ): String = buildString {
        // Copy the exact prompt logic from CodyChatService.generateCommitMessageChained() lines 42-93
        // This is a pure string builder — no Cody or agent dependencies
        appendLine("Generate a git commit message for the following changes. Output ONLY the raw commit message — no commentary, no markdown code blocks.")
        appendLine()
        appendLine("FORMAT (follow exactly):")
        appendLine("type(scope): imperative summary (max 72 chars, no trailing period)")
        appendLine()
        appendLine("- Bullet point per logical change, imperative verb, explains what+why")
        appendLine("- Group related edits into one bullet")
        appendLine()
        appendLine("RULES:")
        appendLine("- type: feat|fix|refactor|perf|test|docs|style|build|ci|chore")
        appendLine("- scope: domain area (e.g., auth, billing, pr-list), NOT file paths")
        appendLine("- Summary: imperative mood ('add' not 'added'), captures the essence")
        appendLine("- Body: ALWAYS bullet points with '- ' prefix, even for single changes")
        appendLine("- Bullets describe behavioral/semantic changes, not line-level edits")
        appendLine("- If trivial (typo, import, version bump), body can be one short bullet")
        if (ticketId.isNotBlank()) {
            appendLine("- Prefix summary with ticket: $ticketId type(scope): description")
        }
        appendLine()
        appendLine("AVOID: repeating the type in summary, file paths in bullets, passive voice,")
        appendLine("'This commit/change' phrasing, wrapping in code blocks")
        if (recentCommits.isNotEmpty()) {
            appendLine()
            appendLine("RECENT COMMITS (match this project's style):")
            recentCommits.forEach { appendLine("  $it") }
        }
        if (codeContext.isNotBlank()) {
            appendLine()
            appendLine("CODE CONTEXT:")
            appendLine(codeContext)
        }
        if (filesSummary.isNotBlank()) {
            appendLine()
            appendLine("CHANGED FILES: $filesSummary")
        }
        appendLine()
        appendLine("DIFF:")
        appendLine("```diff")
        appendLine(diff)
        appendLine("```")
    }
}
```

- [ ] **Step 2: Create PrDescriptionPromptBuilder**

Extract the prompt-building logic from `CodyChatService.generatePrDescriptionChained()` (lines 125-188 of `CodyChatService.kt`):

```kotlin
// core/src/main/kotlin/com/workflow/orchestrator/core/ai/prompts/PrDescriptionPromptBuilder.kt
package com.workflow.orchestrator.core.ai.prompts

object PrDescriptionPromptBuilder {

    fun build(
        diff: String,
        commitMessages: List<String> = emptyList(),
        ticketId: String = "",
        ticketSummary: String = "",
        ticketDescription: String = "",
        sourceBranch: String = "",
        targetBranch: String = ""
    ): String = buildString {
        // Copy the exact prompt logic from CodyChatService.generatePrDescriptionChained() lines 126-188
        appendLine("Generate a pull request description in markdown. Output ONLY the markdown — no preamble, no wrapping code blocks.")
        appendLine()
        appendLine("STRUCTURE (follow exactly):")
        appendLine()
        appendLine("## Summary")
        appendLine("2-3 sentences: what this PR does and why.")
        appendLine()
        appendLine("## Changes")
        appendLine("- Bullet per logical change, imperative mood")
        appendLine()
        appendLine("## Testing")
        appendLine("- [ ] Checkbox items for what reviewers should verify")
        if (ticketId.isNotBlank()) {
            appendLine()
            appendLine("## Jira")
            appendLine("$ticketId: $ticketSummary")
        }
        appendLine()
        appendLine("RULES:")
        appendLine("- Summary understandable without reading the code")
        appendLine("- Changes bullets: WHAT and WHY, not HOW or file paths")
        appendLine("- Be concise — reviewers scan, don't read novels")
        appendLine("- Omit empty sections")
        appendLine()
        appendLine("AVOID: file paths in Changes, passive voice, 'This PR' phrasing, wrapping in code blocks")
        if (ticketId.isNotBlank()) {
            appendLine()
            appendLine("JIRA TICKET: $ticketId — $ticketSummary")
            if (ticketDescription.isNotBlank()) {
                appendLine("Ticket description: ${ticketDescription.take(500)}")
            }
        }
        if (sourceBranch.isNotBlank()) {
            appendLine()
            appendLine("BRANCH: $sourceBranch → $targetBranch")
        }
        if (commitMessages.isNotEmpty()) {
            appendLine()
            appendLine("COMMITS IN THIS PR:")
            commitMessages.take(20).forEach { appendLine("  - $it") }
        }
        appendLine()
        appendLine("DIFF:")
        appendLine("```diff")
        appendLine(diff)
        appendLine("```")
    }
}
```

- [ ] **Step 3: Verify compilation**

Run: `./gradlew :core:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/ai/prompts/
git commit -m "feat: add CommitMessagePromptBuilder and PrDescriptionPromptBuilder"
```

---

## Task 8: Create `SourcegraphTextGenerationService` in `:core`

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/ai/SourcegraphTextGenerationService.kt`

- [ ] **Step 1: Create SourcegraphTextGenerationService**

```kotlin
// core/src/main/kotlin/com/workflow/orchestrator/core/ai/SourcegraphTextGenerationService.kt
package com.workflow.orchestrator.core.ai

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.ai.prompts.PrDescriptionPromptBuilder
import com.workflow.orchestrator.core.model.ApiResult
import java.io.File

class SourcegraphTextGenerationService : TextGenerationService {

    private val log = Logger.getInstance(SourcegraphTextGenerationService::class.java)

    override suspend fun generateText(
        project: Project,
        prompt: String,
        contextFilePaths: List<String>
    ): String? {
        if (!LlmBrainFactory.isAvailable()) return null
        val brain = LlmBrainFactory.create(project)

        val contextBlock = contextFilePaths.mapNotNull { path ->
            val file = File(path)
            if (file.exists() && file.isFile) {
                val content = file.bufferedReader().use { r ->
                    val buf = CharArray(50_000)
                    val len = r.read(buf)
                    if (len > 0) String(buf, 0, len) else ""
                }
                "File: ${file.name}\n```\n$content\n```"
            } else null
        }.joinToString("\n\n")

        val fullPrompt = if (contextBlock.isNotBlank()) "$contextBlock\n\n$prompt" else prompt
        val messages = listOf(ChatMessage(role = "user", content = fullPrompt))

        return when (val result = brain.chat(messages, tools = null)) {
            is ApiResult.Success -> {
                val text = result.data.choices.firstOrNull()?.message?.content
                log.info("[AI:TextGen] Generated ${text?.length ?: 0} chars")
                text
            }
            is ApiResult.Error -> {
                log.warn("[AI:TextGen] Failed: ${result.message}")
                null
            }
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
        if (!LlmBrainFactory.isAvailable()) return null
        val brain = LlmBrainFactory.create(project)

        val prompt = PrDescriptionPromptBuilder.build(
            diff, commitMessages, ticketId, ticketSummary,
            ticketDescription, sourceBranch, targetBranch
        )
        val messages = listOf(ChatMessage(role = "user", content = prompt))

        return when (val result = brain.chat(messages, tools = null)) {
            is ApiResult.Success -> {
                result.data.choices.firstOrNull()?.message?.content
                    ?.replace(Regex("^```[a-z]*\\n?"), "")
                    ?.replace(Regex("\\n?```$"), "")
                    ?.trim()
            }
            is ApiResult.Error -> {
                log.warn("[AI:PrDesc] Failed: ${result.message}")
                null
            }
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :core:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/ai/SourcegraphTextGenerationService.kt
git commit -m "feat: add SourcegraphTextGenerationService using direct HTTP"
```

---

## Task 9: Create `SourcegraphBranchNameGenerator` in `:core`

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/ai/SourcegraphBranchNameGenerator.kt`

- [ ] **Step 1: Create SourcegraphBranchNameGenerator**

```kotlin
// core/src/main/kotlin/com/workflow/orchestrator/core/ai/SourcegraphBranchNameGenerator.kt
package com.workflow.orchestrator.core.ai

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.model.ApiResult

class SourcegraphBranchNameGenerator : BranchNameAiGenerator {

    private val log = Logger.getInstance(SourcegraphBranchNameGenerator::class.java)

    override suspend fun generateBranchSlug(
        project: Project,
        ticketKey: String,
        title: String,
        description: String?
    ): String? {
        if (!LlmBrainFactory.isAvailable()) return null
        val brain = LlmBrainFactory.create(project)

        val prompt = buildString {
            appendLine("Generate a short git branch slug for this Jira ticket.")
            appendLine("Ticket: $ticketKey")
            appendLine("Title: $title")
            if (!description.isNullOrBlank()) {
                appendLine("Description: ${description.take(300)}")
            }
            appendLine()
            appendLine("Output ONLY the slug (lowercase, hyphens, max 50 chars, no ticket key prefix).")
            appendLine("Example: fix-null-pointer-order-service")
        }

        val messages = listOf(ChatMessage(role = "user", content = prompt))
        return when (val result = brain.chat(messages, tools = null)) {
            is ApiResult.Success -> {
                val raw = result.data.choices.firstOrNull()?.message?.content?.trim()
                raw?.removeSurrounding("\"")
                    ?.removeSurrounding("`")
                    ?.lowercase()
                    ?.replace(Regex("[^a-z0-9-]"), "-")
                    ?.replace(Regex("-+"), "-")
                    ?.trim('-')
                    ?.take(50)
                    .also { log.info("[AI:BranchName] Generated slug: $it") }
            }
            is ApiResult.Error -> {
                log.warn("[AI:BranchName] Failed: ${result.message}")
                null
            }
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :core:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/ai/SourcegraphBranchNameGenerator.kt
git commit -m "feat: add SourcegraphBranchNameGenerator using direct HTTP"
```

---

## Task 10: Create `AgentChatRedirect` Interface and Implementation

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/ai/AgentChatRedirect.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentControllerRegistry.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentChatRedirectImpl.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentTabProvider.kt` (register controller)
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt` (add executeTaskWithMentions)
- Modify: `src/main/resources/META-INF/plugin.xml` (add extension point + impl)

- [ ] **Step 1: Create AgentChatRedirect interface in :core**

```kotlin
// core/src/main/kotlin/com/workflow/orchestrator/core/ai/AgentChatRedirect.kt
package com.workflow.orchestrator.core.ai

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project

/**
 * Extension point for redirecting AI tasks to the agent chat tab.
 * Interface in :core, implementation in :agent.
 */
interface AgentChatRedirect {

    /**
     * Opens the agent chat tab and sends a task with optional file context.
     * @param project Current project
     * @param prompt The task description/instruction for the agent
     * @param filePaths Absolute paths of files to attach as @file mentions
     */
    fun sendToAgent(
        project: Project,
        prompt: String,
        filePaths: List<String> = emptyList()
    )

    companion object {
        val EP_NAME = ExtensionPointName.create<AgentChatRedirect>(
            "com.workflow.orchestrator.agentChatRedirect"
        )

        fun getInstance(): AgentChatRedirect? =
            EP_NAME.extensionList.firstOrNull()
    }
}
```

- [ ] **Step 2: Create AgentControllerRegistry in :agent**

```kotlin
// agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentControllerRegistry.kt
package com.workflow.orchestrator.agent.ui

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

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

- [ ] **Step 3: Register controller in AgentTabProvider**

In `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentTabProvider.kt`, after line 38 (`val controller = AgentController(project, dashboard)`), add:

```kotlin
AgentControllerRegistry.getInstance(project).controller = controller
```

- [ ] **Step 4: Add executeTaskWithMentions to AgentController**

In `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt`, add this public method:

```kotlin
/**
 * Execute a task with file mentions injected as context.
 * Called by AgentChatRedirectImpl when external actions redirect to the agent chat.
 */
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
                    com.workflow.orchestrator.agent.api.dto.ChatMessage(
                        role = "system",
                        content = "<mentioned_context>\n$context</mentioned_context>"
                    )
                )
            }
            SwingUtilities.invokeLater { executeTask(prompt) }
        }
    } else {
        executeTask(prompt)
    }
}
```

- [ ] **Step 5: Create AgentChatRedirectImpl**

```kotlin
// agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentChatRedirectImpl.kt
package com.workflow.orchestrator.agent.ui

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.workflow.orchestrator.core.ai.AgentChatRedirect

class AgentChatRedirectImpl : AgentChatRedirect {

    private val log = Logger.getInstance(AgentChatRedirectImpl::class.java)

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
            log.warn("[AgentRedirect] AgentController not initialized — open the Agent tab first")
            return
        }
        controller.executeTaskWithMentions(prompt, filePaths)
    }
}
```

- [ ] **Step 6: Register extension point and implementation in plugin.xml**

In `src/main/resources/META-INF/plugin.xml`:

Add extension point declaration (in `<extensionPoints>` block, after the existing `textGenerationService`):
```xml
<extensionPoint
    qualifiedName="com.workflow.orchestrator.agentChatRedirect"
    interface="com.workflow.orchestrator.core.ai.AgentChatRedirect"
    dynamic="true"/>
```

Add implementation (in `<extensions defaultExtensionNs="com.workflow.orchestrator">` block):
```xml
<agentChatRedirect
    implementation="com.workflow.orchestrator.agent.ui.AgentChatRedirectImpl"/>
```

Add service registration (in `<extensions defaultExtensionNs="com.intellij">` block):
```xml
<projectService serviceImplementation="com.workflow.orchestrator.agent.ui.AgentControllerRegistry"/>
```

- [ ] **Step 7: Verify compilation**

Run: `./gradlew :core:compileKotlin :agent:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/ai/AgentChatRedirect.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentControllerRegistry.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentChatRedirectImpl.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentTabProvider.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt \
        src/main/resources/META-INF/plugin.xml
git commit -m "feat: add AgentChatRedirect extension point for task redirection"
```

---

## Task 11: Rewire Extension Point Registrations

**Files:**
- Modify: `src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Update extension implementations**

In `src/main/resources/META-INF/plugin.xml`, change the extension implementations from `:cody` to `:core`:

Replace (around line 364-365):
```xml
<branchNameAiGenerator
    implementation="com.workflow.orchestrator.cody.service.CodyBranchNameGeneratorImpl"/>
```
With:
```xml
<branchNameAiGenerator
    implementation="com.workflow.orchestrator.core.ai.SourcegraphBranchNameGenerator"/>
```

Replace (around line 368-369):
```xml
<textGenerationService
    implementation="com.workflow.orchestrator.cody.service.CodyTextGenerationService"/>
```
With:
```xml
<textGenerationService
    implementation="com.workflow.orchestrator.core.ai.SourcegraphTextGenerationService"/>
```

- [ ] **Step 2: Verify full build**

Run: `./gradlew :core:compileKotlin :agent:compileKotlin :bamboo:compileKotlin :jira:compileKotlin :sonar:compileKotlin`
Expected: BUILD SUCCESSFUL — all modules that use TextGenerationService or BranchNameAiGenerator continue to work (they depend on the interface in `:core`, not the implementation).

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/META-INF/plugin.xml
git commit -m "feat: switch TextGenerationService and BranchNameAiGenerator to direct HTTP impls"
```

---

## Task 12: Rewire Fix and Test Actions to Agent Chat

**Files:**
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/IssueListPanel.kt`
- Modify: `cody/src/main/kotlin/com/workflow/orchestrator/cody/editor/CodyIntentionAction.kt`
- Modify: `cody/src/main/kotlin/com/workflow/orchestrator/cody/editor/CodyTestGenerator.kt`

- [ ] **Step 1: Replace fixWithCody in IssueListPanel**

In `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/IssueListPanel.kt`:

Replace the `fixWithCody` method (line 211-243) with:
```kotlin
private fun fixWithAgent(issue: MappedIssue) {
    val basePath = project.basePath ?: return
    val absolutePath = java.io.File(basePath, issue.filePath).absolutePath
    navigateToIssue(issue)

    val prompt = buildString {
        appendLine("Fix this SonarQube issue:")
        appendLine()
        appendLine("**Rule:** ${issue.rule}")
        appendLine("**Message:** ${issue.message}")
        appendLine("**File:** ${issue.filePath}")
        appendLine("**Line:** ${issue.startLine}")
        appendLine()
        appendLine("Read the file, understand the context, apply a minimal fix that resolves the issue without changing behavior, and verify it compiles with diagnostics.")
    }

    val redirect = com.workflow.orchestrator.core.ai.AgentChatRedirect.getInstance()
    if (redirect != null) {
        redirect.sendToAgent(project, prompt, listOf(absolutePath))
    } else {
        WorkflowNotificationService.getInstance(project).notifyError(
            WorkflowNotificationService.GROUP_QUALITY,
            "AI Agent Not Available",
            "Enable the Agent tab in Settings to use AI-powered fixes."
        )
    }
}
```

Also update the menu item text from "Fix with Cody" to "Fix with AI Agent" and change the `addActionListener` call from `fixWithCody` to `fixWithAgent`.

- [ ] **Step 2: Update CodyIntentionAction to use AgentChatRedirect**

In `cody/src/main/kotlin/com/workflow/orchestrator/cody/editor/CodyIntentionAction.kt`:

Replace the `invoke()` method body (lines 39-78) to use `AgentChatRedirect` instead of `CodyEditService`:

```kotlin
override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
    if (editor == null || file == null) return
    val sonarIssue = findSonarIssueAtCaret(editor, editor.caretModel.offset) ?: return
    val filePath = file.virtualFile.path

    val prompt = buildString {
        appendLine("Fix the following SonarQube issue in this file.")
        appendLine()
        appendLine("**Issue:** [${sonarIssue.rule}] ${sonarIssue.message}")
        appendLine("**Type:** ${sonarIssue.type}")
        appendLine("**File:** ${file.virtualFile.name}")
        appendLine("**Lines:** ${sonarIssue.startLine}-${sonarIssue.endLine}")
        appendLine()
        appendLine("Read the file, understand the surrounding code, apply a minimal fix that resolves the issue without changing behavior, and verify with diagnostics that the fix compiles.")
    }

    val redirect = com.workflow.orchestrator.core.ai.AgentChatRedirect.getInstance()
    if (redirect != null) {
        redirect.sendToAgent(project, prompt, listOf(filePath))
    }
}
```

Update `getText()` to return `"Fix with AI Agent (Workflow)"`.

- [ ] **Step 3: Update CodyTestGenerator to use AgentChatRedirect**

In `cody/src/main/kotlin/com/workflow/orchestrator/cody/editor/CodyTestGenerator.kt`:

Replace the click handler lambda (lines 69-91) to use `AgentChatRedirect`:

```kotlin
{ _, _ ->
    val prompt = buildString {
        appendLine("Generate unit tests for the uncovered method in this file.")
        appendLine()
        appendLine("**Source file:** $relativePath")
        appendLine("**Method lines:** $methodStartLine-$methodEndLine")
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
    val redirect = com.workflow.orchestrator.core.ai.AgentChatRedirect.getInstance()
    redirect?.sendToAgent(project, prompt, filePaths)
}
```

Update tooltip from "Cover with Cody" to "Cover with AI Agent".

- [ ] **Step 4: Verify compilation**

Run: `./gradlew :sonar:compileKotlin :cody:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/IssueListPanel.kt \
        cody/src/main/kotlin/com/workflow/orchestrator/cody/editor/CodyIntentionAction.kt \
        cody/src/main/kotlin/com/workflow/orchestrator/cody/editor/CodyTestGenerator.kt
git commit -m "feat: redirect fix and test actions to agent chat with context"
```

---

## Task 13: Rewire Commit Message Actions

**Files:**
- Modify: `cody/src/main/kotlin/com/workflow/orchestrator/cody/vcs/GenerateCommitMessageAction.kt`
- Modify: `cody/src/main/kotlin/com/workflow/orchestrator/cody/vcs/CodyCommitMessageHandlerFactory.kt`

These files live in `:cody` and currently use `CodyChatService`. Rewire them to use `LlmBrainFactory` + `CommitMessagePromptBuilder` from `:core`.

- [ ] **Step 1: Rewire GenerateCommitMessageAction**

In `GenerateCommitMessageAction.kt`, replace the `CodyChatService(project).generateCommitMessageChained(...)` call with:

```kotlin
val brain = com.workflow.orchestrator.core.ai.LlmBrainFactory.create(project)
val prompt = com.workflow.orchestrator.core.ai.prompts.CommitMessagePromptBuilder.build(
    diff = diff, ticketId = ticketId, filesSummary = filesSummary,
    recentCommits = recentCommits, codeContext = codeContext
)
val messages = listOf(com.workflow.orchestrator.core.ai.dto.ChatMessage(role = "user", content = prompt))
val result = brain.chat(messages, tools = null)
val commitMessage = when (result) {
    is com.workflow.orchestrator.core.model.ApiResult.Success ->
        result.data.choices.firstOrNull()?.message?.content
            ?.replace(Regex("^```[a-z]*\\n?"), "")
            ?.replace(Regex("\\n?```$"), "")
            ?.trim()
    is com.workflow.orchestrator.core.model.ApiResult.Error -> null
}
```

Remove imports of `CodyChatService`, `CodyAgentProviderService`, and Cody protocol classes. Add imports of `core.ai.*` classes.

- [ ] **Step 2: Rewire CodyCommitMessageHandlerFactory**

In `CodyCommitMessageHandlerFactory.kt`, replace the `CodyChatService(panel.project).generateCommitMessage(...)` call with the same `LlmBrainFactory` + `CommitMessagePromptBuilder` pattern as Step 1.

- [ ] **Step 3: Verify compilation**

Run: `./gradlew :cody:compileKotlin`
Expected: BUILD SUCCESSFUL — no more imports of CodyChatService.

- [ ] **Step 4: Commit**

```bash
git add cody/src/main/kotlin/com/workflow/orchestrator/cody/vcs/
git commit -m "refactor: rewire commit message actions to use LlmBrainFactory"
```

---

## Task 14: Fix Handover PreReviewService Reflection

**Files:**
- Modify: `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/PreReviewService.kt`

The `PreReviewService` uses `Class.forName()` reflection to load `PsiContextEnricher` and `SpringContextEnricher` from `:cody`. These will throw `ClassNotFoundException` when `:cody` classes are removed. Add graceful fallback.

- [ ] **Step 1: Add try-catch around reflection calls**

In `PreReviewService.kt`, wrap the `Class.forName("com.workflow.orchestrator.cody.service.PsiContextEnricher")` and `Class.forName("com.workflow.orchestrator.cody.service.SpringContextEnricher")` calls (around lines 29, 35) in try-catch blocks that log a warning and return null/skip enrichment if the class is not found.

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :handover:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add handover/src/main/kotlin/com/workflow/orchestrator/handover/service/PreReviewService.kt
git commit -m "fix: graceful fallback when Cody context enrichers unavailable"
```

---

## Task 15: Hollow Out `:cody` Module and Clean Up

**Strategy:** Keep `:cody` in the build (UI action classes still live there) but delete all Cody CLI infrastructure. The module becomes a thin shell holding only the rewired UI actions that now depend on `:core` (AgentChatRedirect, LlmBrainFactory).

**Files:**
- Delete: `cody/src/main/kotlin/.../agent/` (CodyAgentManager, CodyAgentServer, CodyAgentClient, StandaloneCodyAgentProvider, CodyAgentProviderService)
- Delete: `cody/src/main/kotlin/.../service/CodyChatService.kt`
- Delete: `cody/src/main/kotlin/.../service/CodyEditService.kt`
- Delete: `cody/src/main/kotlin/.../service/CodyTextGenerationService.kt`
- Delete: `cody/src/main/kotlin/.../service/CodyBranchNameGeneratorImpl.kt`
- Delete: `cody/src/main/kotlin/.../service/CodyContextService.kt`
- Delete: `cody/src/main/kotlin/.../protocol/` (all JSON-RPC protocol DTOs)
- Modify: `src/main/resources/META-INF/plugin.xml`
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/settings/PluginSettings.kt`
- Modify: root `CLAUDE.md`

- [ ] **Step 1: Remove Cody CLI registrations from plugin.xml**

In `src/main/resources/META-INF/plugin.xml`, remove these specific lines:

```
Line 25-28:  <extensionPoint qualifiedName="com.workflow.orchestrator.codyAgentProvider" .../>
Line 177:    <notificationGroup id="workflow.cody" .../>
Line 182:    CodyAgentManager project service
Line 184:    CodyAgentProviderService project service
Line 188:    CodyContextService project service
Line 218:    CodyDocumentSyncListener editor factory listener
Line 362-363: codyAgentProvider implementation (StandaloneCodyAgentProvider)
Line 382:    CodyFocusListener application listener
```

Keep these (they were rewired in Tasks 12-13 to use AgentChatRedirect / LlmBrainFactory):
```
Line 193-196: CodyGutterAction line marker providers → DELETE (replaced by CodyIntentionAction redirect)
Line 199-202: CodyTestGenerator line marker providers → KEEP (rewired)
Line 207-212: CodyIntentionAction intention actions → KEEP (rewired)
Line 222:     CodyCommitMessageHandlerFactory → KEEP (rewired)
Line 391:     GenerateCommitMessageAction → KEEP (rewired)
```

Also remove `CodyGutterAction` line marker providers (lines 193-196) — they duplicate `CodyIntentionAction` which now uses AgentChatRedirect.

- [ ] **Step 2: Delete Cody CLI infrastructure files**

Delete the following directories/files from `:cody`:
```bash
rm -rf cody/src/main/kotlin/com/workflow/orchestrator/cody/agent/
rm -rf cody/src/main/kotlin/com/workflow/orchestrator/cody/protocol/
rm cody/src/main/kotlin/com/workflow/orchestrator/cody/service/CodyChatService.kt
rm cody/src/main/kotlin/com/workflow/orchestrator/cody/service/CodyEditService.kt
rm cody/src/main/kotlin/com/workflow/orchestrator/cody/service/CodyTextGenerationService.kt
rm cody/src/main/kotlin/com/workflow/orchestrator/cody/service/CodyBranchNameGeneratorImpl.kt
rm cody/src/main/kotlin/com/workflow/orchestrator/cody/service/CodyContextService.kt
rm cody/src/main/kotlin/com/workflow/orchestrator/cody/service/PsiContextEnricher.kt
rm cody/src/main/kotlin/com/workflow/orchestrator/cody/service/SpringContextEnricher.kt
rm cody/src/main/kotlin/com/workflow/orchestrator/cody/service/SpringContextEnricherImpl.kt
rm cody/src/main/kotlin/com/workflow/orchestrator/cody/service/SensitiveContentSanitizer.kt
```

Keep:
- `cody/src/main/kotlin/.../editor/CodyIntentionAction.kt` (rewired)
- `cody/src/main/kotlin/.../editor/CodyTestGenerator.kt` (rewired)
- `cody/src/main/kotlin/.../vcs/GenerateCommitMessageAction.kt` (rewired)
- `cody/src/main/kotlin/.../vcs/CodyCommitMessageHandlerFactory.kt` (rewired)

- [ ] **Step 3: Deprecate cody settings in PluginSettings**

In `core/src/main/kotlin/com/workflow/orchestrator/core/settings/PluginSettings.kt` (lines 61-62):

```kotlin
@Deprecated("Cody CLI deprecated. LLM calls use direct Sourcegraph HTTP API.")
var codyAgentPath by string("")

@Deprecated("Cody CLI deprecated. Availability determined by Sourcegraph URL + token.")
var codyEnabled by property(true)
```

Keep the fields for backward compatibility with existing XML configs.

- [ ] **Step 4: Update CLAUDE.md**

Update root `CLAUDE.md` module table: change `:cody` description to "DEPRECATED — former Cody CLI agent. Now a thin shell for rewired UI actions (intention, gutter, VCS). LLM calls use direct Sourcegraph HTTP in :core."

- [ ] **Step 5: Verify full build**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL — `:cody` compiles with only UI actions remaining.

- [ ] **Step 6: Commit**

```bash
git add -u  # stages deletions and modifications
git commit -m "chore: hollow out :cody module — remove Cody CLI, keep rewired UI actions"
```

---

## Task 16: Final Verification

- [ ] **Step 1: Run all module tests**

```bash
./gradlew :core:test :agent:test :bamboo:test :jira:test :sonar:test :pullrequest:test -x buildWebview
```

Expected: All tests pass. Agent tests may need `-x buildWebview` to skip the pre-existing TypeScript error.

- [ ] **Step 2: Run verifyPlugin**

```bash
./gradlew verifyPlugin
```

Expected: No API compatibility issues.

- [ ] **Step 3: Verify the plugin runs**

```bash
./gradlew runIde
```

Manual checks in the sandbox IDE:
1. Open a project with Sourcegraph configured
2. Verify commit message generation works (VCS dialog → Generate button)
3. Verify "Fix with AI Agent" appears in Alt+Enter menu on a Sonar issue
4. Click it → verify agent tab opens with the issue prompt
5. Verify branch name generation works in Sprint dashboard → Start Work

- [ ] **Step 4: Final commit if any fixes needed**

```bash
git add -A && git commit -m "fix: address issues from final verification"
```
