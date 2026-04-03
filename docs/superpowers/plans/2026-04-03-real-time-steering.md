# Real-Time Steering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow users to send steering messages while the agent is working, injected at iteration boundaries (between tool calls), matching Claude Code's boundary-aware queuing pattern.

**Architecture:** Three-layer design: (1) a `SteeringChannel` in the runtime layer accepts prioritized messages from the UI thread, (2) `SingleAgentSession` drains the channel at iteration boundaries and injects messages as `UserSteeringAction` events into `EventSourcedContextBridge`, (3) the React chat UI keeps input enabled during execution with a visual "steering mode" indicator. Messages are NOT mid-tool interrupts — they queue and inject between iterations, which is the safest pattern (no corrupted state from interrupted file writes).

**Tech Stack:** Kotlin coroutines `Channel`, `AtomicReference`, event-sourced context system, JCEF bridge, React/Zustand state

---

## File Structure

```
agent/src/main/kotlin/com/workflow/orchestrator/agent/
  runtime/
    SteeringChannel.kt              (CREATE) — Priority message channel with drain semantics
  context/events/
    Actions.kt                      (MODIFY) — Add UserSteeringAction event type
    EventSerializer.kt              (MODIFY) — Serialize/deserialize UserSteeringAction
  context/
    EventSourcedContextBridge.kt    (MODIFY) — Add addSteeringMessage() method
    ConversationMemory.kt           (MODIFY) — Render UserSteeringAction as user message with steering tag
  runtime/
    SingleAgentSession.kt           (MODIFY) — Drain steering channel at iteration boundary
  ui/
    AgentController.kt              (MODIFY) — Route messages to SteeringChannel when agent is running
    AgentCefPanel.kt                (MODIFY) — Add sendSteeringMessage bridge + setSteeringMode bridge
    AgentDashboardPanel.kt          (MODIFY) — Proxy setSteeringMode to panel

agent/webview/src/
  bridge/jcef-bridge.ts             (MODIFY) — Register setSteeringMode bridge function
  stores/chatStore.ts               (MODIFY) — Add steeringMode state + sendSteering action
  components/input/InputBar.tsx      (MODIFY) — Keep input enabled during busy, show steering indicator

agent/src/test/kotlin/com/workflow/orchestrator/agent/
  runtime/SteeringChannelTest.kt    (CREATE) — Unit tests for channel semantics
  runtime/SingleAgentSessionSteeringTest.kt (CREATE) — Integration test for injection at iteration boundary
  context/events/EventSerializerSteeringTest.kt (CREATE) — Roundtrip serialization test
```

---

### Task 1: SteeringChannel — Priority Message Channel

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SteeringChannel.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/SteeringChannelTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
// agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/SteeringChannelTest.kt
package com.workflow.orchestrator.agent.runtime

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SteeringChannelTest {

    private lateinit var channel: SteeringChannel

    @BeforeEach
    fun setup() {
        channel = SteeringChannel()
    }

    @Test
    fun `drain returns empty list when no messages`() = runTest {
        val messages = channel.drain()
        assertTrue(messages.isEmpty())
    }

    @Test
    fun `enqueue and drain returns messages in FIFO order`() = runTest {
        channel.enqueue("Fix the tests first")
        channel.enqueue("Also check UserService")
        val messages = channel.drain()
        assertEquals(2, messages.size)
        assertEquals("Fix the tests first", messages[0].content)
        assertEquals("Also check UserService", messages[1].content)
    }

    @Test
    fun `drain clears the channel`() = runTest {
        channel.enqueue("message 1")
        channel.drain()
        val second = channel.drain()
        assertTrue(second.isEmpty())
    }

    @Test
    fun `hasPending returns true when messages exist`() {
        assertFalse(channel.hasPending())
        channel.enqueue("hello")
        assertTrue(channel.hasPending())
    }

    @Test
    fun `clear removes all pending messages`() {
        channel.enqueue("msg1")
        channel.enqueue("msg2")
        channel.clear()
        assertFalse(channel.hasPending())
    }

    @Test
    fun `concurrent enqueue is thread-safe`() = runTest {
        val threads = (1..100).map { i ->
            Thread { channel.enqueue("msg-$i") }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        val messages = channel.drain()
        assertEquals(100, messages.size)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :agent:test --tests "com.workflow.orchestrator.agent.runtime.SteeringChannelTest" -x verifyPlugin`
Expected: FAIL with "Unresolved reference: SteeringChannel"

- [ ] **Step 3: Write the SteeringChannel implementation**

```kotlin
// agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SteeringChannel.kt
package com.workflow.orchestrator.agent.runtime

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Thread-safe channel for user steering messages sent while the agent is working.
 *
 * Messages are enqueued from the UI thread (EDT via JCEF bridge) and drained
 * at iteration boundaries in [SingleAgentSession.execute]. This implements
 * boundary-aware queuing: messages wait for the current tool execution to complete,
 * then inject before the next LLM call.
 *
 * Design matches Claude Code's h2A async queue pattern — dual path with
 * immediate drain when consumer is waiting, buffered accumulation otherwise.
 */
class SteeringChannel {

    /**
     * A steering message from the user, sent while the agent was working.
     */
    data class SteeringMessage(
        val content: String,
        val timestampMs: Long = System.currentTimeMillis()
    )

    private val queue = ConcurrentLinkedQueue<SteeringMessage>()

    /**
     * Enqueue a steering message. Called from UI thread — must be non-blocking.
     */
    fun enqueue(content: String) {
        queue.add(SteeringMessage(content = content))
    }

    /**
     * Drain all pending messages atomically. Returns empty list if none.
     * Called from the ReAct loop coroutine at iteration boundaries.
     */
    fun drain(): List<SteeringMessage> {
        val result = mutableListOf<SteeringMessage>()
        while (true) {
            val msg = queue.poll() ?: break
            result.add(msg)
        }
        return result
    }

    /**
     * Check if there are pending messages without consuming them.
     */
    fun hasPending(): Boolean = queue.isNotEmpty()

    /**
     * Clear all pending messages (used on session reset / new chat).
     */
    fun clear() {
        queue.clear()
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :agent:test --tests "com.workflow.orchestrator.agent.runtime.SteeringChannelTest" -x verifyPlugin`
Expected: All 6 tests PASS

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SteeringChannel.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/SteeringChannelTest.kt
git commit -m "feat(agent): add SteeringChannel for boundary-aware user message queuing"
```

---

### Task 2: UserSteeringAction Event Type

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/context/events/Actions.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/context/events/EventSerializer.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/context/events/EventSerializerSteeringTest.kt`

- [ ] **Step 1: Write the failing serialization roundtrip test**

```kotlin
// agent/src/test/kotlin/com/workflow/orchestrator/agent/context/events/EventSerializerSteeringTest.kt
package com.workflow.orchestrator.agent.context.events

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class EventSerializerSteeringTest {

    @Test
    fun `UserSteeringAction serializes and deserializes`() {
        val action = UserSteeringAction(
            content = "Focus on the API layer instead",
            id = 42,
            timestamp = Instant.parse("2026-04-03T10:00:00Z"),
            source = EventSource.USER
        )
        val json = EventSerializer.serialize(action)
        assertTrue(json.contains("\"type\":\"user_steering\""))
        assertTrue(json.contains("Focus on the API layer instead"))

        val restored = EventSerializer.deserialize(json)
        assertTrue(restored is UserSteeringAction)
        val steering = restored as UserSteeringAction
        assertEquals("Focus on the API layer instead", steering.content)
        assertEquals(42, steering.id)
        assertEquals(EventSource.USER, steering.source)
    }

    @Test
    fun `UserSteeringAction is not compression-proof`() {
        val action = UserSteeringAction(content = "steer me")
        assertFalse(action.isCompressionProof)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agent:test --tests "com.workflow.orchestrator.agent.context.events.EventSerializerSteeringTest" -x verifyPlugin`
Expected: FAIL with "Unresolved reference: UserSteeringAction"

- [ ] **Step 3: Add UserSteeringAction to Actions.kt**

In `agent/src/main/kotlin/com/workflow/orchestrator/agent/context/events/Actions.kt`, add after `SystemMessageAction` (after line 42):

```kotlin
/**
 * A steering message from the user, sent mid-execution while the agent was working.
 * Injected at iteration boundaries (between tool calls) to redirect the agent.
 * Distinct from [MessageAction] so condensers can identify steering context.
 */
data class UserSteeringAction(
    val content: String,
    override val id: Int = -1,
    override val timestamp: Instant = Instant.EPOCH,
    override val source: EventSource = EventSource.USER
) : Action
```

- [ ] **Step 4: Add serialization support in EventSerializer.kt**

In `EventSerializer.toJsonObject()`, add a `when` branch for `UserSteeringAction`:

```kotlin
is UserSteeringAction -> {
    map["content"] = JsonPrimitive(event.content)
}
```

In `EventSerializer.typeDiscriminator()`, add:

```kotlin
is UserSteeringAction -> "user_steering"
```

In `EventSerializer.fromJsonObject()`, add a `when` branch in the type discriminator match:

```kotlin
"user_steering" -> UserSteeringAction(
    content = obj["content"]!!.jsonPrimitive.content,
    id = id,
    timestamp = timestamp,
    source = source
)
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :agent:test --tests "com.workflow.orchestrator.agent.context.events.EventSerializerSteeringTest" -x verifyPlugin`
Expected: All 2 tests PASS

- [ ] **Step 6: Run existing EventSerializer tests to verify no regression**

Run: `./gradlew :agent:test --tests "com.workflow.orchestrator.agent.context.events.*" -x verifyPlugin`
Expected: All existing tests PASS

- [ ] **Step 7: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/context/events/Actions.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/context/events/EventSerializer.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/context/events/EventSerializerSteeringTest.kt
git commit -m "feat(agent): add UserSteeringAction event type for mid-execution user messages"
```

---

### Task 3: EventSourcedContextBridge — Steering Message Injection

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/context/EventSourcedContextBridge.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ConversationMemory.kt`

- [ ] **Step 1: Add addSteeringMessage() to EventSourcedContextBridge**

In `EventSourcedContextBridge.kt`, after the `addSystemMessage()` method (after line 202), add:

```kotlin
/**
 * Add a user steering message. These are mid-execution redirections from the user,
 * injected at iteration boundaries. Recorded as [UserSteeringAction] so condensers
 * can distinguish steering from initial task messages.
 */
fun addSteeringMessage(content: String) {
    eventStore.add(
        UserSteeringAction(content = content),
        EventSource.USER
    )
}
```

- [ ] **Step 2: Handle UserSteeringAction in ConversationMemory**

In `ConversationMemory.kt`, in the `processEvents()` method where events are converted to `ChatMessage` list, find the `when` block that maps event types to messages. Add handling for `UserSteeringAction`:

```kotlin
is UserSteeringAction -> {
    // Render as a user message with steering tag so LLM knows this is a mid-task redirection
    messages.add(ChatMessage(
        role = "user",
        content = "<user_steering>\n${event.content}\n</user_steering>"
    ))
}
```

This wraps steering messages in `<user_steering>` tags so the LLM can distinguish them from the original task and treat them as priority redirections.

- [ ] **Step 3: Run existing context management tests**

Run: `./gradlew :agent:test --tests "com.workflow.orchestrator.agent.context.*" -x verifyPlugin`
Expected: All existing tests PASS (UserSteeringAction is additive, no existing behavior changes)

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/context/EventSourcedContextBridge.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ConversationMemory.kt
git commit -m "feat(agent): wire UserSteeringAction into context bridge and conversation memory"
```

---

### Task 4: SingleAgentSession — Drain Steering at Iteration Boundaries

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSessionSteeringTest.kt`

- [ ] **Step 1: Write the failing integration test**

```kotlin
// agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSessionSteeringTest.kt
package com.workflow.orchestrator.agent.runtime

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.*
import com.workflow.orchestrator.agent.brain.LlmBrain
import com.workflow.orchestrator.agent.context.EventSourcedContextBridge
import com.workflow.orchestrator.agent.context.events.UserSteeringAction
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.core.model.ApiResult
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SingleAgentSessionSteeringTest {

    private lateinit var session: SingleAgentSession
    private lateinit var brain: LlmBrain
    private lateinit var bridge: EventSourcedContextBridge
    private lateinit var project: Project
    private lateinit var steeringChannel: SteeringChannel

    @BeforeEach
    fun setup() {
        steeringChannel = SteeringChannel()
        session = SingleAgentSession(maxIterations = 5)
        brain = mockk()
        bridge = mockk(relaxed = true)
        project = mockk()

        every { bridge.getMessages() } returns listOf(
            ChatMessage(role = "system", content = "You are an AI coding assistant"),
            ChatMessage(role = "user", content = "Do something")
        )
        every { bridge.currentTokens } returns 1000
        every { bridge.remainingBudget() } returns 149_000

        coEvery { brain.chatStream(any(), any(), any(), any()) } throws NotImplementedError("test fallback")
    }

    @Test
    fun `steering message injected into bridge at iteration boundary`() = runTest {
        // Enqueue a steering message before execution starts
        steeringChannel.enqueue("Actually, focus on the tests instead")

        // LLM returns a simple completion (no tools)
        coEvery { brain.chat(any(), any(), any(), any()) } returns ApiResult.Success(
            ChatCompletionResponse(
                content = "OK, focusing on tests now.",
                toolCalls = null,
                usage = Usage(promptTokens = 500, completionTokens = 50, totalTokens = 550),
                finishReason = "stop"
            )
        )

        session.execute(
            task = "Fix the bug",
            tools = emptyMap(),
            toolDefinitions = emptyList(),
            brain = brain,
            bridge = bridge,
            project = project,
            steeringChannel = steeringChannel
        )

        // Verify steering message was injected into the bridge
        verify(atLeast = 1) { bridge.addSteeringMessage("Actually, focus on the tests instead") }
    }

    @Test
    fun `no steering injection when channel is empty`() = runTest {
        coEvery { brain.chat(any(), any(), any(), any()) } returns ApiResult.Success(
            ChatCompletionResponse(
                content = "Done.",
                toolCalls = null,
                usage = Usage(promptTokens = 500, completionTokens = 50, totalTokens = 550),
                finishReason = "stop"
            )
        )

        session.execute(
            task = "Fix the bug",
            tools = emptyMap(),
            toolDefinitions = emptyList(),
            brain = brain,
            bridge = bridge,
            project = project,
            steeringChannel = steeringChannel
        )

        verify(exactly = 0) { bridge.addSteeringMessage(any()) }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agent:test --tests "com.workflow.orchestrator.agent.runtime.SingleAgentSessionSteeringTest" -x verifyPlugin`
Expected: FAIL — `execute()` has no `steeringChannel` parameter

- [ ] **Step 3: Add steeringChannel parameter to execute()**

In `SingleAgentSession.kt`, add an optional parameter to `execute()` at line 249 (after `onCheckpoint`):

```kotlin
/** Optional steering channel for mid-execution user messages. */
steeringChannel: SteeringChannel? = null
```

- [ ] **Step 4: Drain steering messages at iteration boundary**

In `SingleAgentSession.kt`, inside the `for (iteration in 1..maxIterations)` loop, add steering drain **after** the worker message drain block (after line 351) and **before** the budget check (line 390):

```kotlin
// Drain steering messages from user (mid-execution redirections)
if (steeringChannel != null) {
    val steeringMessages = steeringChannel.drain()
    if (steeringMessages.isNotEmpty()) {
        for (msg in steeringMessages) {
            bridge.addSteeringMessage(msg.content)
        }
        LOG.info("SingleAgentSession: injected ${steeringMessages.size} steering message(s) at iteration $iteration")
        onProgress(AgentProgress(
            step = "Received steering from user",
            tokensUsed = bridge.currentTokens
        ))
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :agent:test --tests "com.workflow.orchestrator.agent.runtime.SingleAgentSessionSteeringTest" -x verifyPlugin`
Expected: Both tests PASS

- [ ] **Step 6: Run full SingleAgentSession test suite for regression**

Run: `./gradlew :agent:test --tests "com.workflow.orchestrator.agent.runtime.SingleAgentSessionTest" -x verifyPlugin`
Expected: All existing tests PASS (steeringChannel defaults to null, no behavior change)

- [ ] **Step 7: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSessionSteeringTest.kt
git commit -m "feat(agent): drain steering channel at iteration boundaries in ReAct loop"
```

---

### Task 5: AgentOrchestrator — Thread SteeringChannel Through

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/AgentOrchestrator.kt`

The `AgentOrchestrator` creates `SingleAgentSession` and calls `execute()`. It needs to accept and forward the `SteeringChannel`.

- [ ] **Step 1: Find the executeTask method in AgentOrchestrator**

Read the file to locate where `SingleAgentSession.execute()` is called — it will have parameters like `task`, `tools`, `brain`, `bridge`, etc.

- [ ] **Step 2: Add steeringChannel parameter to AgentOrchestrator.executeTask()**

Add an optional parameter:

```kotlin
steeringChannel: SteeringChannel? = null
```

- [ ] **Step 3: Forward steeringChannel to SingleAgentSession.execute()**

In the call to `session.execute(...)`, add:

```kotlin
steeringChannel = steeringChannel
```

- [ ] **Step 4: Run existing orchestrator tests**

Run: `./gradlew :agent:test --tests "com.workflow.orchestrator.agent.orchestrator.*" -x verifyPlugin`
Expected: All PASS (optional parameter with null default)

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/AgentOrchestrator.kt
git commit -m "feat(agent): thread SteeringChannel through AgentOrchestrator to SingleAgentSession"
```

---

### Task 6: AgentController — Route Messages to SteeringChannel

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt`

This is the critical integration point. Currently, when the agent is running and the user sends a message, it's added to `pendingUserMessages` (line 765) and only drained **after the task completes** (line 1405). We change this to use `SteeringChannel` instead — messages get injected at the next iteration boundary.

- [ ] **Step 1: Add SteeringChannel field to AgentController**

At line 53, replace the `pendingUserMessages` field:

```kotlin
// Replace this:
private val pendingUserMessages = java.util.concurrent.ConcurrentLinkedQueue<String>()

// With this:
private val steeringChannel = SteeringChannel()
```

- [ ] **Step 2: Update the message queuing in executeTask()**

At line 764-768, change the message queuing to use `steeringChannel`:

```kotlin
if (!isWaitingForUser) {
    steeringChannel.enqueue(task)
    dashboard.appendUserMessage(task)
    dashboard.appendStatus("Message sent — agent will see it after the current step completes.", RichStreamingPanel.StatusType.INFO)
    return
}
```

Note the status message change: "will be sent to the agent after the current step" (iteration-level) instead of "after the current task" (task-level).

- [ ] **Step 3: Pass steeringChannel to AgentOrchestrator**

In the `scope.launch` block at line 823 where `orchestrator.executeTask()` is called, add:

```kotlin
steeringChannel = steeringChannel
```

- [ ] **Step 4: Remove the post-task drain from handleResult()**

At line 1405-1410, remove the `pendingUserMessages.poll()` + `invokeLater { executeTask(pending) }` block entirely. Steering messages are now consumed during execution, not after.

- [ ] **Step 5: Clear steeringChannel on newChat()**

In the `newChat()` method, add:

```kotlin
steeringChannel.clear()
```

- [ ] **Step 6: Clear steeringChannel on cancelTask()**

In the `cancelTask()` method, add:

```kotlin
steeringChannel.clear()
```

- [ ] **Step 7: Run existing AgentController-related tests**

Run: `./gradlew :agent:test -x verifyPlugin`
Expected: All PASS. Any test referencing `pendingUserMessages` by name must be updated to use `steeringChannel`.

- [ ] **Step 8: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt
git commit -m "feat(agent): route user messages to SteeringChannel for boundary-aware injection"
```

---

### Task 7: React UI — Keep Input Enabled During Agent Execution

**Files:**
- Modify: `agent/webview/src/stores/chatStore.ts`
- Modify: `agent/webview/src/components/input/InputBar.tsx`
- Modify: `agent/webview/src/bridge/jcef-bridge.ts`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentDashboardPanel.kt`

- [ ] **Step 1: Add steeringMode to chatStore state**

In `chatStore.ts`, add to the state interface (after `busy: boolean` at line 84):

```typescript
steeringMode: boolean;  // true when agent is working and input is accepting steering messages
```

Add initial value (after `busy: false` at line 200):

```typescript
steeringMode: false,
```

Add action to the interface (after `setBusy` at line 129):

```typescript
setSteeringMode(enabled: boolean): void;
```

Add action implementation (after `setBusy` implementation at line 505):

```typescript
setSteeringMode(enabled: boolean) {
    set({ steeringMode: enabled });
},
```

- [ ] **Step 2: Modify startSession to enable steeringMode**

In `chatStore.ts` `startSession()` (line 222), add `steeringMode: true` to the `set()` call (agent starts working = steering possible).

In `completeSession()` (line 251), add `steeringMode: false` to the `set()` call.

- [ ] **Step 3: Register setSteeringMode bridge function**

In `jcef-bridge.ts`, add to `bridgeFunctions` (after `setBusy` at line 113):

```typescript
setSteeringMode(enabled: boolean) {
    stores?.getChatStore().setSteeringMode(enabled);
},
```

- [ ] **Step 4: Add Kotlin-side bridge method**

In `AgentCefPanel.kt`, add after `setBusy()` (after line 782):

```kotlin
fun setSteeringMode(enabled: Boolean) {
    callJs("setSteeringMode(${if (enabled) "true" else "false"})")
}
```

In `AgentDashboardPanel.kt`, add after the existing `setBusy()` proxy:

```kotlin
fun setSteeringMode(enabled: Boolean) {
    runOnEdt { cefPanel?.setSteeringMode(enabled) }
    mirrors.forEach { it.setSteeringMode(enabled) }
}
```

- [ ] **Step 5: Modify InputBar to allow sending during steeringMode**

In `InputBar.tsx`, the send guard at line 582 currently blocks when `busy`:

```typescript
// BEFORE:
if (useChatStore.getState().inputState.locked || useChatStore.getState().busy) return;
```

Change to allow sending when in steering mode:

```typescript
// AFTER:
const state = useChatStore.getState();
if (state.inputState.locked) return;
if (state.busy && !state.steeringMode) return;
```

Update `canSend` at line 601:

```typescript
// BEFORE:
const canSend = hasText && !inputState.locked && !busy;

// AFTER:
const steeringMode = useChatStore(s => s.steeringMode);
const canSend = hasText && !inputState.locked && (!busy || steeringMode);
```

- [ ] **Step 6: Add visual steering indicator**

In `InputBar.tsx`, inside the `InputBarContent` component, add a steering mode indicator that shows above the input when the agent is working. Find the working indicator section and add nearby:

```tsx
{busy && steeringMode && (
    <div
        className="text-xs px-2 py-1 mb-1 rounded flex items-center gap-1.5"
        style={{ color: 'var(--text-secondary)', backgroundColor: 'var(--bg-secondary)' }}
    >
        <span style={{ color: 'var(--accent-blue)' }}>&#9650;</span>
        <span>Type to steer the agent — message will be sent after the current step</span>
    </div>
)}
```

Also change the placeholder text of `RichInput` when in steering mode:

```tsx
placeholder={busy && steeringMode ? 'Steer the agent...' : 'Ask anything...'}
```

- [ ] **Step 7: Build webview**

Run: `cd agent/webview && npm run build`
Expected: Build succeeds with no TypeScript errors

- [ ] **Step 8: Set steeringMode from AgentController**

In `AgentController.kt`, in `executeTask()`, after `dashboard.setBusy(true)` (line 819), add:

```kotlin
dashboard.setSteeringMode(true)
```

In `handleResult()`, before `dashboard.completeSession(...)`, add:

```kotlin
dashboard.setSteeringMode(false)
```

In `cancelTask()`, add:

```kotlin
dashboard.setSteeringMode(false)
```

- [ ] **Step 9: Commit**

```bash
git add agent/webview/src/stores/chatStore.ts \
       agent/webview/src/components/input/InputBar.tsx \
       agent/webview/src/bridge/jcef-bridge.ts \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentDashboardPanel.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt \
       agent/src/main/resources/webview/dist/
git commit -m "feat(agent): enable input during execution with steering mode indicator"
```

---

### Task 8: System Prompt — Teach the LLM About Steering

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt`

The LLM needs to know what `<user_steering>` tags mean so it can respond appropriately.

- [ ] **Step 1: Find the RULES or COMMUNICATION section in PromptAssembler**

Read `PromptAssembler.kt` and find where behavioral rules are defined. Look for `RULES` or `COMMUNICATION` constants.

- [ ] **Step 2: Add steering awareness to the rules section**

Add to the `RULES` section (or equivalent behavioral instructions block):

```kotlin
const val STEERING_RULES = """
## User Steering

Messages wrapped in <user_steering> tags are real-time redirections from the user sent while you were working. When you see a steering message:

1. **Acknowledge briefly** — confirm you received the redirection (one sentence)
2. **Adjust immediately** — change your approach to match the user's new direction
3. **Don't restart from scratch** — build on work already done unless the user explicitly asks to discard it
4. **Don't apologize** — the user is steering, not correcting a mistake
"""
```

- [ ] **Step 3: Include STEERING_RULES in the prompt assembly**

Add `STEERING_RULES` to the recency zone (after RULES, before COMMUNICATION) in the prompt assembly method.

- [ ] **Step 4: Run build to verify compilation**

Run: `./gradlew :agent:compileKotlin -x verifyPlugin`
Expected: Compilation succeeds

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt
git commit -m "feat(agent): add steering awareness rules to system prompt"
```

---

### Task 9: Audit Trail — Log Steering Events

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/AgentEventLog.kt`

- [ ] **Step 1: Add STEERING_RECEIVED event type**

In `AgentEventLog.kt`, find the `AgentEventType` enum and add:

```kotlin
STEERING_RECEIVED,
```

- [ ] **Step 2: Log steering events in SingleAgentSession**

In `SingleAgentSession.kt`, inside the steering drain block (added in Task 4), after logging to `LOG.info`, add:

```kotlin
eventLog?.log(AgentEventType.STEERING_RECEIVED, "User steering: ${msg.content.take(200)}")
```

- [ ] **Step 3: Run build to verify compilation**

Run: `./gradlew :agent:compileKotlin -x verifyPlugin`
Expected: Compilation succeeds

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/AgentEventLog.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt
git commit -m "feat(agent): add STEERING_RECEIVED to audit trail"
```

---

### Task 10: Full Integration Test — End-to-End Steering Flow

**Files:**
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/SteeringIntegrationTest.kt`

- [ ] **Step 1: Write integration test that simulates a multi-iteration session with steering**

```kotlin
// agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/SteeringIntegrationTest.kt
package com.workflow.orchestrator.agent.runtime

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.*
import com.workflow.orchestrator.agent.brain.LlmBrain
import com.workflow.orchestrator.agent.context.EventSourcedContextBridge
import com.workflow.orchestrator.agent.context.events.*
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.core.model.ApiResult
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SteeringIntegrationTest {

    private lateinit var brain: LlmBrain
    private lateinit var bridge: EventSourcedContextBridge
    private lateinit var project: Project
    private lateinit var steeringChannel: SteeringChannel

    @BeforeEach
    fun setup() {
        brain = mockk()
        bridge = mockk(relaxed = true)
        project = mockk()
        steeringChannel = SteeringChannel()

        every { bridge.getMessages() } returns listOf(
            ChatMessage(role = "system", content = "system"),
            ChatMessage(role = "user", content = "task")
        )
        every { bridge.currentTokens } returns 1000
        every { bridge.remainingBudget() } returns 149_000

        coEvery { brain.chatStream(any(), any(), any(), any()) } throws NotImplementedError("test")
    }

    @Test
    fun `steering message appears in bridge before second LLM call`() = runTest {
        val callCount = slot<Int>()
        var llmCallCount = 0

        // First LLM call: returns a tool call
        // Second LLM call: returns completion text
        coEvery { brain.chat(any(), any(), any(), any()) } answers {
            llmCallCount++
            if (llmCallCount == 1) {
                // After first call returns, enqueue steering for iteration 2
                // (simulating user typing while tool executes)
                steeringChannel.enqueue("Skip the database migration, focus on API")
                ApiResult.Success(ChatCompletionResponse(
                    content = "I'll read the file first.",
                    toolCalls = listOf(ToolCall(
                        id = "tc1",
                        function = ToolCall.Function(name = "read_file", arguments = """{"path":"src/Main.kt"}""")
                    )),
                    usage = Usage(500, 100, 600),
                    finishReason = "tool_calls"
                ))
            } else {
                ApiResult.Success(ChatCompletionResponse(
                    content = "OK, focusing on API layer as requested.",
                    toolCalls = null,
                    usage = Usage(600, 50, 650),
                    finishReason = "stop"
                ))
            }
        }

        val readTool = mockk<AgentTool>()
        every { readTool.name } returns "read_file"
        every { readTool.toToolDefinition() } returns ToolDefinition("read_file", "Read a file", mockk())
        coEvery { readTool.execute(any(), any()) } returns ToolResult(
            content = "file contents",
            summary = "Read Main.kt"
        )

        val session = SingleAgentSession(maxIterations = 5)
        session.execute(
            task = "Fix the migration",
            tools = mapOf("read_file" to readTool),
            toolDefinitions = listOf(readTool.toToolDefinition()),
            brain = brain,
            bridge = bridge,
            project = project,
            steeringChannel = steeringChannel
        )

        // Verify the steering message was injected between iterations
        verify { bridge.addSteeringMessage("Skip the database migration, focus on API") }

        // Verify at least 2 LLM calls were made (tool call iteration + steering-aware iteration)
        assertTrue(llmCallCount >= 2)
    }

    @Test
    fun `multiple steering messages drained together`() = runTest {
        steeringChannel.enqueue("First redirection")
        steeringChannel.enqueue("Second redirection")

        coEvery { brain.chat(any(), any(), any(), any()) } returns ApiResult.Success(
            ChatCompletionResponse(
                content = "Adjusting.",
                toolCalls = null,
                usage = Usage(500, 50, 550),
                finishReason = "stop"
            )
        )

        val session = SingleAgentSession(maxIterations = 3)
        session.execute(
            task = "Do work",
            tools = emptyMap(),
            toolDefinitions = emptyList(),
            brain = brain,
            bridge = bridge,
            project = project,
            steeringChannel = steeringChannel
        )

        verify { bridge.addSteeringMessage("First redirection") }
        verify { bridge.addSteeringMessage("Second redirection") }
    }
}
```

- [ ] **Step 2: Run the integration test**

Run: `./gradlew :agent:test --tests "com.workflow.orchestrator.agent.runtime.SteeringIntegrationTest" -x verifyPlugin`
Expected: All 2 tests PASS

- [ ] **Step 3: Run full agent test suite**

Run: `./gradlew :agent:test -x verifyPlugin`
Expected: All tests PASS

- [ ] **Step 4: Commit**

```bash
git add agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/SteeringIntegrationTest.kt
git commit -m "test(agent): add end-to-end steering integration tests"
```

---

### Task 11: Documentation Updates

**Files:**
- Modify: `agent/CLAUDE.md`
- Modify: `CLAUDE.md` (root)

- [ ] **Step 1: Add steering section to agent/CLAUDE.md**

Add a new section after "## Key Components":

```markdown
## Real-Time Steering

Users can send messages while the agent is working. Messages are injected at iteration boundaries (between tool calls), not mid-tool. This is boundary-aware queuing, matching Claude Code's pattern.

**Flow:**
1. User types in chat input during agent execution (input stays enabled in "steering mode")
2. `AgentController.executeTask()` routes message to `SteeringChannel.enqueue()`
3. At the top of each ReAct loop iteration, `SingleAgentSession` calls `steeringChannel.drain()`
4. Drained messages recorded as `UserSteeringAction` events in `EventSourcedContextBridge`
5. `ConversationMemory` renders them as `<user_steering>` tagged user messages
6. LLM sees the steering context on the next call and adjusts its approach

**Key files:**
- `SteeringChannel.kt` — Thread-safe ConcurrentLinkedQueue wrapper
- `Actions.kt` — `UserSteeringAction` event type
- `SingleAgentSession.kt` — Drain + inject at iteration boundary (after worker messages, before budget check)
- `InputBar.tsx` — Input enabled during `steeringMode` with visual indicator
```

- [ ] **Step 2: Update root CLAUDE.md**

In the root `CLAUDE.md` Architecture section, add a brief mention:

```markdown
- **Real-time steering**: Users can send messages mid-execution; injected at iteration boundaries via `SteeringChannel`
```

- [ ] **Step 3: Commit**

```bash
git add agent/CLAUDE.md CLAUDE.md
git commit -m "docs: add real-time steering architecture documentation"
```

---

## Architecture Summary

```
┌─────────────────────────────────────────────────────┐
│                   React Chat UI                      │
│  InputBar.tsx: input enabled during steeringMode     │
│  "Steer the agent..." placeholder                    │
│  Visual indicator: "▲ Type to steer..."              │
└───────────────┬─────────────────────────────────────┘
                │ kotlinBridge.sendMessage(text)
                ▼
┌─────────────────────────────────────────────────────┐
│              AgentController.executeTask()            │
│  if (agent running && !waitingForUser)                │
│    → steeringChannel.enqueue(task)                   │
│    → dashboard.appendUserMessage(task)                │
│    → dashboard.appendStatus("Message sent...")        │
│    → return (don't start new turn)                   │
└───────────────┬─────────────────────────────────────┘
                │ SteeringChannel (ConcurrentLinkedQueue)
                ▼
┌─────────────────────────────────────────────────────┐
│        SingleAgentSession.execute() — ReAct loop     │
│  for (iteration in 1..50) {                          │
│    ① Check cancellation                              │
│    ② Drain worker messages                           │
│    ③ Drain steering messages ← NEW                   │
│       steeringChannel.drain()                        │
│       → bridge.addSteeringMessage(content)           │
│    ④ Budget check                                    │
│    ⑤ Get messages via condenser pipeline             │
│    ⑥ Call LLM                                        │
│    ⑦ Execute tools                                   │
│  }                                                   │
└───────────────┬─────────────────────────────────────┘
                │ bridge.addSteeringMessage(content)
                ▼
┌─────────────────────────────────────────────────────┐
│           EventSourcedContextBridge                  │
│  addSteeringMessage() → EventStore.add(              │
│    UserSteeringAction(content)                       │
│  )                                                   │
│                                                      │
│  ConversationMemory renders it as:                   │
│  ChatMessage(role="user",                            │
│    content="<user_steering>...</user_steering>")     │
└─────────────────────────────────────────────────────┘
```

## Timing Characteristics

| Scenario | Injection Delay |
|----------|----------------|
| Agent thinking (LLM call in progress) | After current LLM response returns (~2-15s) |
| Agent executing read-only tools | After parallel batch completes (~1-5s) |
| Agent executing write tool | After that tool completes (~1-10s) |
| Agent running `./gradlew test` | After command finishes (could be minutes) |
| Agent waiting for approval | Immediate — not queued, falls through to normal handling |

## What This Does NOT Do (By Design)

1. **No mid-tool interruption** — Running tools always complete. This prevents corrupted file writes, orphaned processes, or partial git operations.
2. **No API call cancellation** — In-flight LLM requests complete. The steering message affects the *next* LLM call, not the current one.
3. **No priority levels** — Unlike Claude Code's `now/next/later` system, all steering messages are equal priority. This matches our simpler single-consumer model.
4. **No message smooshing** — Each steering message is a separate `UserSteeringAction` event. The Sourcegraph API handles consecutive user messages via `sanitizeMessages()` merging.
