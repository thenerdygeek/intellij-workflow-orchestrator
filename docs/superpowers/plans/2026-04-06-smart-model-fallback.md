# Smart Model Fallback & API Call Progress — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Automatically fall back to cheaper LLM models on network errors and escalate back when connectivity stabilizes, with real-time progress visibility.

**Architecture:** New `ModelFallbackManager` state machine tracks the current model position in a fallback chain (Opus thinking → Opus → Sonnet thinking → Sonnet). `AgentLoop` gains a `brainFactory` lambda and delegates model-switch decisions to the manager. Existing `dashboard.setModelName()` updates the UI model chip on every switch.

**Tech Stack:** Kotlin, IntelliJ Platform SDK, JUnit 5 + MockK

---

### Task 1: ModelFallbackManager — State Machine

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/ModelFallbackManager.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/loop/ModelFallbackManagerTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
// agent/src/test/kotlin/com/workflow/orchestrator/agent/loop/ModelFallbackManagerTest.kt
package com.workflow.orchestrator.agent.loop

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ModelFallbackManagerTest {

    private val chain = listOf(
        "anthropic::2024-10-22::claude-opus-4-20250514-thinking",
        "anthropic::2024-10-22::claude-opus-4-20250514",
        "anthropic::2024-10-22::claude-sonnet-4-20250514-thinking",
        "anthropic::2024-10-22::claude-sonnet-4-20250514"
    )

    @Test
    fun `starts on primary model`() {
        val mgr = ModelFallbackManager(chain)
        assertEquals(chain[0], mgr.getCurrentModelId())
        assertTrue(mgr.isPrimary())
    }

    @Test
    fun `onNetworkError advances to next model in chain`() {
        val mgr = ModelFallbackManager(chain)
        val fallback = mgr.onNetworkError()
        assertEquals(chain[1], fallback)
        assertEquals(chain[1], mgr.getCurrentModelId())
        assertFalse(mgr.isPrimary())
    }

    @Test
    fun `onNetworkError walks full chain then returns null`() {
        val mgr = ModelFallbackManager(chain)
        assertEquals(chain[1], mgr.onNetworkError())
        assertEquals(chain[2], mgr.onNetworkError())
        assertEquals(chain[3], mgr.onNetworkError())
        assertNull(mgr.onNetworkError()) // exhausted
    }

    @Test
    fun `onIterationSuccess returns null before threshold`() {
        val mgr = ModelFallbackManager(chain, initialEscalationThreshold = 3)
        mgr.onNetworkError() // fall to index 1
        assertNull(mgr.onIterationSuccess()) // iteration 1
        assertNull(mgr.onIterationSuccess()) // iteration 2
    }

    @Test
    fun `onIterationSuccess returns primary at threshold`() {
        val mgr = ModelFallbackManager(chain, initialEscalationThreshold = 3)
        mgr.onNetworkError() // fall to index 1
        mgr.onIterationSuccess() // 1
        mgr.onIterationSuccess() // 2
        val escalation = mgr.onIterationSuccess() // 3 — threshold reached
        assertEquals(chain[0], escalation)
        assertTrue(mgr.isPrimary())
    }

    @Test
    fun `onEscalationFailed reverts to previous fallback and uses extended threshold`() {
        val mgr = ModelFallbackManager(chain, initialEscalationThreshold = 3, extendedEscalationThreshold = 6)
        mgr.onNetworkError() // fall to index 1
        repeat(3) { mgr.onIterationSuccess() } // triggers escalation to primary
        assertTrue(mgr.isPrimary())

        val revert = mgr.onEscalationFailed()
        assertEquals(chain[1], revert)
        assertFalse(mgr.isPrimary())

        // Now needs 6 iterations to try again
        repeat(5) { assertNull(mgr.onIterationSuccess()) }
        val escalation = mgr.onIterationSuccess() // 6 — extended threshold
        assertEquals(chain[0], escalation)
    }

    @Test
    fun `successful escalation resets to initial threshold`() {
        val mgr = ModelFallbackManager(chain, initialEscalationThreshold = 3, extendedEscalationThreshold = 6)
        mgr.onNetworkError() // fall to index 1

        // First escalation fails
        repeat(3) { mgr.onIterationSuccess() }
        mgr.onEscalationFailed()

        // Second escalation succeeds (after 6 iterations)
        repeat(6) { mgr.onIterationSuccess() }
        assertTrue(mgr.isPrimary())

        // Simulate another fallback + escalation — should use initial threshold (3) again
        mgr.onNetworkError()
        repeat(2) { assertNull(mgr.onIterationSuccess()) }
        val escalation = mgr.onIterationSuccess() // 3 — initial threshold
        assertEquals(chain[0], escalation)
    }

    @Test
    fun `onIterationSuccess returns null when already on primary`() {
        val mgr = ModelFallbackManager(chain)
        assertNull(mgr.onIterationSuccess()) // already on primary, no escalation needed
    }

    @Test
    fun `reset returns to primary`() {
        val mgr = ModelFallbackManager(chain)
        mgr.onNetworkError()
        mgr.onNetworkError()
        assertFalse(mgr.isPrimary())
        mgr.reset()
        assertTrue(mgr.isPrimary())
        assertEquals(chain[0], mgr.getCurrentModelId())
    }

    @Test
    fun `single model chain — onNetworkError returns null immediately`() {
        val mgr = ModelFallbackManager(listOf("only-model"))
        assertNull(mgr.onNetworkError())
    }

    @Test
    fun `empty chain throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            ModelFallbackManager(emptyList())
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :agent:test --tests "com.workflow.orchestrator.agent.loop.ModelFallbackManagerTest" -x :agent:compileTestKotlin 2>&1 | tail -3`
Expected: Compilation failure — `ModelFallbackManager` does not exist yet.

- [ ] **Step 3: Implement ModelFallbackManager**

```kotlin
// agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/ModelFallbackManager.kt
package com.workflow.orchestrator.agent.loop

/**
 * Manages smart model fallback during the agent loop.
 *
 * On network errors, advances through a fallback chain of progressively cheaper models.
 * After a cooldown period of successful iterations, attempts to escalate back to the
 * primary model. If escalation fails, waits longer before trying again.
 *
 * Fallback chain order (resolved from ModelCache at runtime):
 * 1. Opus thinking (primary)
 * 2. Opus non-thinking
 * 3. Sonnet thinking
 * 4. Sonnet non-thinking
 *
 * @param fallbackChain ordered list of model IDs (index 0 = primary)
 * @param initialEscalationThreshold successful iterations before first escalation attempt
 * @param extendedEscalationThreshold successful iterations after a failed escalation
 */
class ModelFallbackManager(
    private val fallbackChain: List<String>,
    private val initialEscalationThreshold: Int = 3,
    private val extendedEscalationThreshold: Int = 6
) {
    init {
        require(fallbackChain.isNotEmpty()) { "Fallback chain must not be empty" }
    }

    /** Current position in the fallback chain (0 = primary). */
    private var currentIndex: Int = 0

    /** Successful iterations since the last model switch. */
    private var iterationsSinceSwitch: Int = 0

    /** Whether the last escalation attempt failed (switches to extended threshold). */
    private var useExtendedThreshold: Boolean = false

    /** The fallback index we were at before the last escalation attempt. */
    private var preEscalationIndex: Int = 0

    fun getCurrentModelId(): String = fallbackChain[currentIndex]

    fun isPrimary(): Boolean = currentIndex == 0

    /**
     * Called on NETWORK_ERROR/TIMEOUT during retry.
     * Returns the next fallback model ID, or null if the chain is exhausted.
     */
    fun onNetworkError(): String? {
        if (currentIndex >= fallbackChain.size - 1) return null
        currentIndex++
        iterationsSinceSwitch = 0
        return fallbackChain[currentIndex]
    }

    /**
     * Called after each successful API call.
     * Returns an escalation model ID if it's time to try the primary model,
     * or null to stay on the current model.
     */
    fun onIterationSuccess(): String? {
        if (isPrimary()) return null
        iterationsSinceSwitch++
        val threshold = if (useExtendedThreshold) extendedEscalationThreshold else initialEscalationThreshold
        if (iterationsSinceSwitch >= threshold) {
            preEscalationIndex = currentIndex
            currentIndex = 0
            iterationsSinceSwitch = 0
            return fallbackChain[0]
        }
        return null
    }

    /**
     * Called when an escalation attempt fails (network error on primary model).
     * Reverts to the previous fallback model and switches to the extended threshold.
     * Returns the model ID to revert to.
     */
    fun onEscalationFailed(): String {
        currentIndex = preEscalationIndex
        iterationsSinceSwitch = 0
        useExtendedThreshold = true
        return fallbackChain[currentIndex]
    }

    /** Reset to primary model. */
    fun reset() {
        currentIndex = 0
        iterationsSinceSwitch = 0
        useExtendedThreshold = false
        preEscalationIndex = 0
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :agent:test --tests "com.workflow.orchestrator.agent.loop.ModelFallbackManagerTest"`
Expected: All 10 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/ModelFallbackManager.kt agent/src/test/kotlin/com/workflow/orchestrator/agent/loop/ModelFallbackManagerTest.kt
git commit -m "feat(agent): add ModelFallbackManager state machine for smart model fallback"
```

---

### Task 2: ModelCache.buildFallbackChain()

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/ai/ModelCache.kt`
- Test: `core/src/test/kotlin/com/workflow/orchestrator/core/ai/ModelCacheTest.kt` (create if not exists, or add to existing)

- [ ] **Step 1: Write the failing tests**

```kotlin
// Add to existing ModelCacheTest or create new file
package com.workflow.orchestrator.core.ai

import com.workflow.orchestrator.core.ai.dto.ModelInfo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ModelCacheFallbackChainTest {

    private fun model(id: String, created: Long = 1000L) = ModelInfo(id = id, created = created)

    @Test
    fun `buildFallbackChain returns correct order with all tiers`() {
        val models = listOf(
            model("anthropic::v1::claude-opus-4-20250514-thinking"),
            model("anthropic::v1::claude-opus-4-20250514"),
            model("anthropic::v1::claude-sonnet-4-20250514-thinking"),
            model("anthropic::v1::claude-sonnet-4-20250514"),
            model("anthropic::v1::claude-haiku-4-20250514") // should be excluded
        )
        val chain = ModelCache.buildFallbackChain(models)
        assertEquals(4, chain.size)
        assertTrue(chain[0].contains("opus") && chain[0].contains("thinking"))
        assertTrue(chain[1].contains("opus") && !chain[1].contains("thinking"))
        assertTrue(chain[2].contains("sonnet") && chain[2].contains("thinking"))
        assertTrue(chain[3].contains("sonnet") && !chain[3].contains("thinking"))
    }

    @Test
    fun `buildFallbackChain skips missing tiers`() {
        val models = listOf(
            model("anthropic::v1::claude-opus-4-20250514"),
            model("anthropic::v1::claude-sonnet-4-20250514")
        )
        val chain = ModelCache.buildFallbackChain(models)
        assertEquals(2, chain.size)
        assertTrue(chain[0].contains("opus"))
        assertTrue(chain[1].contains("sonnet"))
    }

    @Test
    fun `buildFallbackChain returns empty for no anthropic models`() {
        val models = listOf(model("openai::v1::gpt-4"))
        val chain = ModelCache.buildFallbackChain(models)
        assertTrue(chain.isEmpty())
    }

    @Test
    fun `buildFallbackChain picks latest when multiple models per tier`() {
        val models = listOf(
            model("anthropic::v1::claude-opus-4-20250514", created = 1000),
            model("anthropic::v1::claude-opus-4-20260101", created = 2000),
            model("anthropic::v1::claude-sonnet-4-20250514", created = 1000)
        )
        val chain = ModelCache.buildFallbackChain(models)
        assertEquals(2, chain.size)
        assertEquals("anthropic::v1::claude-opus-4-20260101", chain[0])
    }

    @Test
    fun `buildFallbackChain excludes haiku`() {
        val models = listOf(
            model("anthropic::v1::claude-haiku-4-20250514")
        )
        val chain = ModelCache.buildFallbackChain(models)
        assertTrue(chain.isEmpty())
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :core:test --tests "com.workflow.orchestrator.core.ai.ModelCacheFallbackChainTest" 2>&1 | tail -3`
Expected: Compilation failure — `buildFallbackChain` does not exist.

- [ ] **Step 3: Implement buildFallbackChain in ModelCache**

Add to `core/src/main/kotlin/com/workflow/orchestrator/core/ai/ModelCache.kt`, inside the `ModelCache` object, after the `pickCheapest` method:

```kotlin
/**
 * Build an ordered fallback chain for smart model fallback.
 * Order: Opus thinking → Opus → Sonnet thinking → Sonnet.
 * Skips tiers with no matching model. Excludes Haiku.
 * Picks the latest (by created timestamp) model per tier.
 */
fun buildFallbackChain(models: List<ModelInfo>): List<String> {
    val anthropic = models.filter { it.provider == "anthropic" }
    val chain = mutableListOf<String>()

    // Tier 1: Opus thinking
    anthropic.filter { it.isOpusClass && it.isThinkingModel }
        .maxByOrNull { it.created }?.let { chain.add(it.id) }

    // Tier 2: Opus non-thinking
    anthropic.filter { it.isOpusClass && !it.isThinkingModel }
        .maxByOrNull { it.created }?.let { chain.add(it.id) }

    // Tier 3: Sonnet thinking
    anthropic.filter { it.modelName.lowercase().contains("sonnet") && it.isThinkingModel }
        .maxByOrNull { it.created }?.let { chain.add(it.id) }

    // Tier 4: Sonnet non-thinking
    anthropic.filter { it.modelName.lowercase().contains("sonnet") && !it.isThinkingModel }
        .maxByOrNull { it.created }?.let { chain.add(it.id) }

    return chain
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :core:test --tests "com.workflow.orchestrator.core.ai.ModelCacheFallbackChainTest"`
Expected: All 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/ai/ModelCache.kt core/src/test/kotlin/com/workflow/orchestrator/core/ai/ModelCacheFallbackChainTest.kt
git commit -m "feat(core): add ModelCache.buildFallbackChain for smart model fallback"
```

---

### Task 3: Settings — enableModelFallback

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/settings/AgentSettings.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/settings/AgentSettingsConfigurable.kt`

- [ ] **Step 1: Add the setting field**

In `agent/src/main/kotlin/com/workflow/orchestrator/agent/settings/AgentSettings.kt`, inside `class State : BaseState()`, after the `smartWorkingIndicator` field (line 34), add:

```kotlin
/** Automatically fall back to cheaper models on network errors and escalate back when stable. */
var enableModelFallback by property(false)
```

- [ ] **Step 2: Add the UI mutable copy and checkbox**

In `agent/src/main/kotlin/com/workflow/orchestrator/agent/settings/AgentSettingsConfigurable.kt`:

Add the mutable copy field after `smartWorkingIndicator` (line 55):

```kotlin
private var enableModelFallback = settings.state.enableModelFallback
```

In `createComponent()`, inside the `group("Advanced")` block (after the smart working indicator row around line 154), add:

```kotlin
row {
    checkBox("Smart model fallback")
        .bindSelected(::enableModelFallback)
        .comment("On network errors, fall back to a cheaper model and escalate back when stable (Opus → Sonnet)")
}
```

In `apply()` (after line 391 `settings.state.smartWorkingIndicator = smartWorkingIndicator`), add:

```kotlin
settings.state.enableModelFallback = enableModelFallback
```

In `reset()` (after line 406 `smartWorkingIndicator = settings.state.smartWorkingIndicator`), add:

```kotlin
enableModelFallback = settings.state.enableModelFallback
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :agent:compileKotlin 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/settings/AgentSettings.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/settings/AgentSettingsConfigurable.kt
git commit -m "feat(agent): add enableModelFallback setting (opt-in)"
```

---

### Task 4: AgentLoop — Model Fallback + Progress Callbacks

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt`

- [ ] **Step 1: Add new constructor parameters**

After the `onRetry` parameter (line 212), add:

```kotlin
/**
 * Callback fired before each LLM API call.
 * Used by the UI to show "Thinking (model)..." status.
 */
private val onApiCallStart: ((modelId: String) -> Unit)? = null,
/**
 * Optional model fallback manager. When provided with [brainFactory],
 * the loop falls back to cheaper models on network errors and escalates back.
 */
private val fallbackManager: ModelFallbackManager? = null,
/**
 * Factory to create a new LlmBrain for a given model ID.
 * Used by the fallback manager to switch models mid-loop.
 */
private val brainFactory: (suspend (modelId: String) -> LlmBrain)? = null,
/**
 * Callback fired when the loop switches to a different model.
 * Used by the UI to update the model chip and show a status message.
 */
private val onModelSwitch: ((fromModel: String, toModel: String, reason: String) -> Unit)? = null
```

- [ ] **Step 2: Change `brain` from val to var**

Change line 75 from:

```kotlin
private val brain: LlmBrain,
```

to:

```kotlin
private var brain: LlmBrain,
```

- [ ] **Step 3: Add onApiCallStart before the API call**

In the `run()` method, immediately before the `brain.chatStream(...)` call (around line 412), add:

```kotlin
onApiCallStart?.invoke(brain.modelId)
```

- [ ] **Step 4: Add model fallback in the retry block**

In the retry block (around line 438, inside the `if (apiResult.type in RETRYABLE_ERRORS && apiRetryCount < maxRetries)` block), after `apiRetryCount++` and before the delay calculation, add:

```kotlin
// Smart model fallback: try a cheaper model on network/timeout errors
if (fallbackManager != null && brainFactory != null && apiResult.type in TIMEOUT_ERRORS) {
    if (fallbackManager.isPrimary()) {
        // First failure on primary — try fallback
        val fallbackModel = fallbackManager.onNetworkError()
        if (fallbackModel != null) {
            val oldModel = brain.modelId
            brain = brainFactory.invoke(fallbackModel)
            onModelSwitch?.invoke(oldModel, fallbackModel, "Network error — falling back")
            LOG.info("[Loop] Model fallback: $oldModel → $fallbackModel")
        }
    } else if (!fallbackManager.isPrimary()) {
        // Already on fallback and still failing — try next in chain
        val nextModel = fallbackManager.onNetworkError()
        if (nextModel != null) {
            val oldModel = brain.modelId
            brain = brainFactory.invoke(nextModel)
            onModelSwitch?.invoke(oldModel, nextModel, "Still failing — falling back further")
            LOG.info("[Loop] Model fallback: $oldModel → $nextModel")
        }
    }
}
```

- [ ] **Step 5: Add escalation check after successful API calls**

After the successful API call processing (after `apiRetryCount = 0` and `contextOverflowRetries = 0` around line 476), add:

```kotlin
// Smart model escalation: try primary model after cooldown
if (fallbackManager != null && brainFactory != null && !fallbackManager.isPrimary()) {
    val escalationModel = fallbackManager.onIterationSuccess()
    if (escalationModel != null) {
        val oldModel = brain.modelId
        brain = brainFactory.invoke(escalationModel)
        onModelSwitch?.invoke(oldModel, escalationModel, "Escalating back")
        LOG.info("[Loop] Model escalation: $oldModel → $escalationModel")
    }
}
```

- [ ] **Step 6: Add escalation failure handling in the retry block**

In the retry block, before the existing fallback logic added in Step 4, add an escalation failure check:

```kotlin
// Escalation failure: we just tried to go back to primary and it failed
if (fallbackManager != null && brainFactory != null && fallbackManager.isPrimary() && apiResult.type in TIMEOUT_ERRORS) {
    val revertModel = fallbackManager.onEscalationFailed()
    val oldModel = brain.modelId
    brain = brainFactory.invoke(revertModel)
    onModelSwitch?.invoke(oldModel, revertModel, "Escalation failed — reverting")
    LOG.info("[Loop] Escalation failed, reverting: $oldModel → $revertModel")
}
```

Reorganize the retry block so escalation failure is checked FIRST (when `isPrimary()` is true after an escalation), then the normal fallback logic runs for non-primary models.

- [ ] **Step 7: Verify it compiles**

Run: `./gradlew :agent:compileKotlin 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt
git commit -m "feat(agent): integrate model fallback and API progress callbacks into AgentLoop"
```

---

### Task 5: AgentService — Wire Fallback Manager + Brain Factory

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt`

- [ ] **Step 1: Build fallback chain and manager in executeTask()**

In `executeTask()`, after the brain is created (around line 543 `val brain = createBrain()`), add:

```kotlin
// Build model fallback chain if enabled
val fallbackManager = if (agentSettings.state.enableModelFallback) {
    val cachedModels = ModelCache.getCached()
    val chain = ModelCache.buildFallbackChain(cachedModels)
    if (chain.size > 1) {
        log.info("[Agent] Model fallback enabled, chain: ${chain.map { it.substringAfterLast("::") }}")
        ModelFallbackManager(chain)
    } else {
        log.info("[Agent] Model fallback enabled but chain has ≤1 model, skipping")
        null
    }
} else null
```

- [ ] **Step 2: Create the brainFactory lambda**

After the fallback manager creation, add:

```kotlin
val brainFactory: (suspend (String) -> LlmBrain)? = if (fallbackManager != null) { modelId ->
    val connections = ConnectionSettings.getInstance()
    val sgUrl = connections.state.sourcegraphUrl.trimEnd('/')
    val credentialStore = CredentialStore()
    val tokenProvider = { credentialStore.getToken(ServiceType.SOURCEGRAPH) }
    OpenAiCompatBrain(
        sourcegraphUrl = sgUrl,
        tokenProvider = tokenProvider,
        model = modelId
    ).also { newBrain ->
        // Wire API debug dir to the new brain (same session directory)
        newBrain.setApiDebugDir(sessionDebugDir)
    }
} else null
```

- [ ] **Step 3: Pass new parameters to AgentLoop constructor**

In the `AgentLoop(...)` constructor call (around line 650), add the new parameters:

```kotlin
fallbackManager = fallbackManager,
brainFactory = brainFactory,
onModelSwitch = onModelSwitch,
onApiCallStart = onApiCallStart,
```

- [ ] **Step 4: Add onModelSwitch and onApiCallStart to executeTask parameter list**

In the `executeTask()` function signature (around line 420), add two new callback parameters:

```kotlin
onModelSwitch: ((fromModel: String, toModel: String, reason: String) -> Unit)? = null,
onApiCallStart: ((modelId: String) -> Unit)? = null,
```

- [ ] **Step 5: Verify it compiles**

Run: `./gradlew :agent:compileKotlin 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt
git commit -m "feat(agent): wire ModelFallbackManager and brainFactory into AgentService.executeTask"
```

---

### Task 6: AgentController — Wire UI Callbacks

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt`

- [ ] **Step 1: Wire onApiCallStart callback**

In `executeTask()` inside the `service.executeTask(...)` call (around line 607), add:

```kotlin
onApiCallStart = { modelId ->
    invokeLater {
        val shortName = modelId.substringAfterLast("::")
        dashboard.appendStatus("Thinking ($shortName)...", RichStreamingPanel.StatusType.INFO)
    }
},
```

- [ ] **Step 2: Wire onModelSwitch callback**

In the same `service.executeTask(...)` call, add:

```kotlin
onModelSwitch = { from, to, reason ->
    invokeLater {
        val shortTo = to.substringAfterLast("::")
        dashboard.appendStatus("$reason to $shortTo", RichStreamingPanel.StatusType.WARNING)
        dashboard.setModelName(to)
    }
},
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :agent:compileKotlin 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt
git commit -m "feat(agent): wire model fallback and API progress callbacks to dashboard UI"
```

---

### Task 7: Integration Test — Model Fallback in AgentLoop

**Files:**
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/loop/ModelFallbackLoopTest.kt`

- [ ] **Step 1: Write the integration test**

```kotlin
// agent/src/test/kotlin/com/workflow/orchestrator/agent/loop/ModelFallbackLoopTest.kt
package com.workflow.orchestrator.agent.loop

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.core.ai.LlmBrain
import com.workflow.orchestrator.core.ai.dto.*
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ModelFallbackLoopTest {

    private val project = mockk<Project>(relaxed = true)

    private fun successResponse(content: String = "Done", toolCalls: List<ToolCall>? = null) =
        ApiResult.Success(ChatCompletionResponse(
            id = "test",
            choices = listOf(Choice(
                index = 0,
                message = MessageContent(
                    role = "assistant",
                    content = content,
                    toolCalls = toolCalls
                ),
                finishReason = if (toolCalls != null) "tool_calls" else "stop"
            )),
            usage = UsageInfo(promptTokens = 100, completionTokens = 50, totalTokens = 150)
        ))

    private fun networkError() = ApiResult.Error(
        type = ErrorType.NETWORK_ERROR,
        message = "Connection reset"
    )

    private fun completionToolCall() = listOf(ToolCall(
        id = "tc1",
        type = "function",
        function = FunctionCall(name = "attempt_completion", arguments = """{"result":"done"}""")
    ))

    @Test
    fun `model fallback switches brain on network error`() = runTest {
        val chain = listOf("model-opus-thinking", "model-opus", "model-sonnet")
        val fallbackManager = ModelFallbackManager(chain)

        var callCount = 0
        val switchedModels = mutableListOf<String>()

        val primaryBrain = mockk<LlmBrain>(relaxed = true) {
            every { modelId } returns "model-opus-thinking"
            every { runBlocking { chatStream(any(), any(), any(), any()) } } returns networkError()
        }

        val fallbackBrain = mockk<LlmBrain>(relaxed = true) {
            every { modelId } returns "model-opus"
            every { runBlocking { chatStream(any(), any(), any(), any()) } } answers {
                successResponse(toolCalls = completionToolCall())
            }
        }

        val completionTool = mockk<AgentTool>(relaxed = true) {
            every { name } returns "attempt_completion"
            every { runBlocking { execute(any(), any()) } } returns ToolResult(
                content = "done", summary = "done", isCompletion = true
            )
        }

        val brainFactory: suspend (String) -> LlmBrain = { modelId ->
            if (modelId == "model-opus") fallbackBrain else primaryBrain
        }

        val ctx = ContextManager(maxInputTokens = 100000)
        ctx.setSystemPrompt("test")
        ctx.addUserMessage("do something")

        val loop = AgentLoop(
            brain = primaryBrain,
            tools = mapOf("attempt_completion" to completionTool),
            toolDefinitions = emptyList(),
            contextManager = ctx,
            project = project,
            fallbackManager = fallbackManager,
            brainFactory = brainFactory,
            onModelSwitch = { _, to, _ -> switchedModels.add(to) }
        )

        val result = loop.run("do something")

        assertTrue(switchedModels.contains("model-opus"), "Should have switched to fallback model")
    }
}
```

- [ ] **Step 2: Run the test**

Run: `./gradlew :agent:test --tests "com.workflow.orchestrator.agent.loop.ModelFallbackLoopTest"`
Expected: PASS (may need adjustments based on exact mock behavior)

- [ ] **Step 3: Commit**

```bash
git add agent/src/test/kotlin/com/workflow/orchestrator/agent/loop/ModelFallbackLoopTest.kt
git commit -m "test(agent): add integration test for model fallback in AgentLoop"
```

---

### Task 8: Update Documentation

**Files:**
- Modify: `agent/CLAUDE.md`
- Modify: `CLAUDE.md` (root)

- [ ] **Step 1: Update agent/CLAUDE.md**

In the `## Error Handling` section, after the API retry bullet, add:

```markdown
- **Model fallback**: Opt-in (`AgentSettings.enableModelFallback`). On NETWORK_ERROR/TIMEOUT, `ModelFallbackManager` advances through fallback chain (Opus thinking → Opus → Sonnet thinking → Sonnet, no Haiku). After 3 successful iterations on fallback, attempts escalation back to primary. If escalation fails, waits 6 iterations. `brainFactory` creates fresh `OpenAiCompatBrain` per switch. `onModelSwitch` callback updates UI model chip via existing `dashboard.setModelName()`.
- **API call progress**: `onApiCallStart` callback fires before each LLM call, showing "Thinking (model)..." in chat status.
```

- [ ] **Step 2: Verify the build**

Run: `./gradlew :agent:test 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 3: Commit**

```bash
git add agent/CLAUDE.md CLAUDE.md
git commit -m "docs: document smart model fallback and API call progress"
```
