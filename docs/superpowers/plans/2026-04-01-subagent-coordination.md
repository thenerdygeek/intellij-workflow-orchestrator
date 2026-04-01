# Subagent Coordination Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add file ownership tracking, bidirectional parent↔child messaging, and remove hard timeouts from the subagent architecture.

**Architecture:** Three new runtime classes (`FileOwnershipRegistry`, `WorkerMessageBus`, `WorkerContext`) wired through `AgentService` and consumed at ReAct loop iteration boundaries. One new tool (`send_message_to_parent`) for child→parent messaging; `agent` tool extended with `send`/`message` params for parent→child.

**Tech Stack:** Kotlin, kotlinx.coroutines (Channel, CoroutineContext), JUnit 5, MockK

**Spec:** `docs/superpowers/specs/2026-04-01-subagent-coordination-design.md`

---

### Task 1: FileOwnershipRegistry

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/FileOwnershipRegistry.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/FileOwnershipRegistryTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
package com.workflow.orchestrator.agent.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class FileOwnershipRegistryTest {

    private lateinit var registry: FileOwnershipRegistry

    @BeforeEach
    fun setup() {
        registry = FileOwnershipRegistry()
    }

    @Test
    fun `claim grants ownership for unclaimed file`() {
        val result = registry.claim("/src/Auth.kt", "agent-1", WorkerType.CODER)
        assertEquals(ClaimResult.GRANTED, result.result)
        assertEquals("agent-1", registry.getOwner("/src/Auth.kt")?.agentId)
    }

    @Test
    fun `claim is idempotent for same agent`() {
        registry.claim("/src/Auth.kt", "agent-1", WorkerType.CODER)
        val result = registry.claim("/src/Auth.kt", "agent-1", WorkerType.CODER)
        assertEquals(ClaimResult.GRANTED, result.result)
    }

    @Test
    fun `claim denied when file owned by another agent`() {
        registry.claim("/src/Auth.kt", "agent-1", WorkerType.CODER)
        val result = registry.claim("/src/Auth.kt", "agent-2", WorkerType.CODER)
        assertEquals(ClaimResult.DENIED, result.result)
        assertEquals("agent-1", result.ownerAgentId)
    }

    @Test
    fun `release frees file for other agents`() {
        registry.claim("/src/Auth.kt", "agent-1", WorkerType.CODER)
        assertTrue(registry.release("/src/Auth.kt", "agent-1"))
        val result = registry.claim("/src/Auth.kt", "agent-2", WorkerType.CODER)
        assertEquals(ClaimResult.GRANTED, result.result)
    }

    @Test
    fun `release by wrong agent returns false`() {
        registry.claim("/src/Auth.kt", "agent-1", WorkerType.CODER)
        assertFalse(registry.release("/src/Auth.kt", "agent-2"))
    }

    @Test
    fun `releaseAll frees all files for agent`() {
        registry.claim("/src/Auth.kt", "agent-1", WorkerType.CODER)
        registry.claim("/src/User.kt", "agent-1", WorkerType.CODER)
        registry.claim("/src/Other.kt", "agent-2", WorkerType.CODER)
        val count = registry.releaseAll("agent-1")
        assertEquals(2, count)
        assertNull(registry.getOwner("/src/Auth.kt"))
        assertNull(registry.getOwner("/src/User.kt"))
        assertNotNull(registry.getOwner("/src/Other.kt"))
    }

    @Test
    fun `isOwnedByOther returns true for different agent`() {
        registry.claim("/src/Auth.kt", "agent-1", WorkerType.CODER)
        assertTrue(registry.isOwnedByOther("/src/Auth.kt", "agent-2"))
        assertFalse(registry.isOwnedByOther("/src/Auth.kt", "agent-1"))
    }

    @Test
    fun `listOwnedFiles returns files for specific agent`() {
        registry.claim("/src/Auth.kt", "agent-1", WorkerType.CODER)
        registry.claim("/src/User.kt", "agent-1", WorkerType.CODER)
        registry.claim("/src/Other.kt", "agent-2", WorkerType.CODER)
        val files = registry.listOwnedFiles("agent-1")
        assertEquals(2, files.size)
        assertTrue(files.contains("/src/Auth.kt"))
        assertTrue(files.contains("/src/User.kt"))
    }

    @Test
    fun `getOwner returns null for unclaimed file`() {
        assertNull(registry.getOwner("/src/Auth.kt"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :agent:test --tests "*.FileOwnershipRegistryTest" -x verifyPlugin`
Expected: Compilation failure — `FileOwnershipRegistry`, `ClaimResult`, `ClaimResponse`, `OwnershipRecord` not found

- [ ] **Step 3: Implement FileOwnershipRegistry**

```kotlin
package com.workflow.orchestrator.agent.runtime

import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class OwnershipRecord(
    val agentId: String,
    val workerType: WorkerType,
    val claimedAt: Long = System.currentTimeMillis()
)

enum class ClaimResult {
    GRANTED,
    DENIED
}

data class ClaimResponse(
    val result: ClaimResult,
    val ownerAgentId: String? = null
)

class FileOwnershipRegistry {
    private val fileOwners = ConcurrentHashMap<String, OwnershipRecord>()

    fun claim(filePath: String, agentId: String, workerType: WorkerType): ClaimResponse {
        val canonical = canonicalize(filePath)
        val existing = fileOwners[canonical]
        if (existing != null && existing.agentId != agentId) {
            return ClaimResponse(ClaimResult.DENIED, existing.agentId)
        }
        fileOwners[canonical] = OwnershipRecord(agentId, workerType)
        return ClaimResponse(ClaimResult.GRANTED)
    }

    fun release(filePath: String, agentId: String): Boolean {
        val canonical = canonicalize(filePath)
        val existing = fileOwners[canonical] ?: return false
        if (existing.agentId != agentId) return false
        fileOwners.remove(canonical)
        return true
    }

    fun releaseAll(agentId: String): Int {
        val toRemove = fileOwners.entries.filter { it.value.agentId == agentId }.map { it.key }
        toRemove.forEach { fileOwners.remove(it) }
        return toRemove.size
    }

    fun getOwner(filePath: String): OwnershipRecord? {
        return fileOwners[canonicalize(filePath)]
    }

    fun isOwnedByOther(filePath: String, agentId: String): Boolean {
        val owner = getOwner(filePath) ?: return false
        return owner.agentId != agentId
    }

    fun listOwnedFiles(agentId: String): List<String> {
        return fileOwners.entries.filter { it.value.agentId == agentId }.map { it.key }
    }

    private fun canonicalize(filePath: String): String {
        return try {
            File(filePath).canonicalPath
        } catch (_: Exception) {
            filePath
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :agent:test --tests "*.FileOwnershipRegistryTest" -x verifyPlugin`
Expected: All 8 tests PASS

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/FileOwnershipRegistry.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/FileOwnershipRegistryTest.kt
git commit -m "feat(agent): add FileOwnershipRegistry for subagent file conflict prevention"
```

---

### Task 2: WorkerMessageBus

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/WorkerMessageBus.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/WorkerMessageBusTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
package com.workflow.orchestrator.agent.runtime

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class WorkerMessageBusTest {

    private lateinit var bus: WorkerMessageBus

    @BeforeEach
    fun setup() {
        bus = WorkerMessageBus()
    }

    @Test
    fun `createInbox and send message`() = runTest {
        bus.createInbox("agent-1")
        val msg = WorkerMessage(
            from = WorkerMessageBus.ORCHESTRATOR_ID,
            to = "agent-1",
            type = MessageType.INSTRUCTION,
            content = "Focus on service layer"
        )
        assertTrue(bus.send(msg))
        val drained = bus.drain("agent-1")
        assertEquals(1, drained.size)
        assertEquals("Focus on service layer", drained[0].content)
        assertEquals(MessageType.INSTRUCTION, drained[0].type)
    }

    @Test
    fun `drain returns empty after consuming`() = runTest {
        bus.createInbox("agent-1")
        bus.send(WorkerMessage("orch", "agent-1", MessageType.INSTRUCTION, "msg1"))
        bus.drain("agent-1")
        val second = bus.drain("agent-1")
        assertTrue(second.isEmpty())
    }

    @Test
    fun `send to nonexistent inbox returns false`() = runTest {
        assertFalse(bus.send(WorkerMessage("orch", "agent-999", MessageType.INSTRUCTION, "msg")))
    }

    @Test
    fun `hasPending returns true when messages exist`() = runTest {
        bus.createInbox("agent-1")
        assertFalse(bus.hasPending("agent-1"))
        bus.send(WorkerMessage("orch", "agent-1", MessageType.INSTRUCTION, "msg"))
        assertTrue(bus.hasPending("agent-1"))
    }

    @Test
    fun `closeInbox prevents further sends`() = runTest {
        bus.createInbox("agent-1")
        bus.closeInbox("agent-1")
        assertFalse(bus.send(WorkerMessage("orch", "agent-1", MessageType.INSTRUCTION, "msg")))
    }

    @Test
    fun `multiple messages drain in order`() = runTest {
        bus.createInbox("agent-1")
        bus.send(WorkerMessage("orch", "agent-1", MessageType.INSTRUCTION, "first"))
        bus.send(WorkerMessage("orch", "agent-1", MessageType.INSTRUCTION, "second"))
        bus.send(WorkerMessage("orch", "agent-1", MessageType.INSTRUCTION, "third"))
        val drained = bus.drain("agent-1")
        assertEquals(3, drained.size)
        assertEquals("first", drained[0].content)
        assertEquals("second", drained[1].content)
        assertEquals("third", drained[2].content)
    }

    @Test
    fun `orchestrator inbox works`() = runTest {
        bus.createInbox(WorkerMessageBus.ORCHESTRATOR_ID)
        val msg = WorkerMessage(
            from = "agent-1",
            to = WorkerMessageBus.ORCHESTRATOR_ID,
            type = MessageType.FINDING,
            content = "Found circular dependency"
        )
        assertTrue(bus.send(msg))
        val drained = bus.drain(WorkerMessageBus.ORCHESTRATOR_ID)
        assertEquals(1, drained.size)
        assertEquals(MessageType.FINDING, drained[0].type)
    }

    @Test
    fun `close shuts down all inboxes`() = runTest {
        bus.createInbox("agent-1")
        bus.createInbox("agent-2")
        bus.close()
        assertFalse(bus.send(WorkerMessage("orch", "agent-1", MessageType.INSTRUCTION, "msg")))
        assertFalse(bus.send(WorkerMessage("orch", "agent-2", MessageType.INSTRUCTION, "msg")))
    }

    @Test
    fun `drain on nonexistent inbox returns empty`() = runTest {
        val drained = bus.drain("agent-999")
        assertTrue(drained.isEmpty())
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :agent:test --tests "*.WorkerMessageBusTest" -x verifyPlugin`
Expected: Compilation failure — `WorkerMessageBus`, `WorkerMessage`, `MessageType` not found

- [ ] **Step 3: Implement WorkerMessageBus**

```kotlin
package com.workflow.orchestrator.agent.runtime

import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap

enum class MessageType {
    INSTRUCTION,
    FINDING,
    STATUS_UPDATE,
    FILE_CONFLICT
}

data class WorkerMessage(
    val from: String,
    val to: String,
    val type: MessageType,
    val content: String,
    val metadata: Map<String, String> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis()
)

class WorkerMessageBus {
    companion object {
        const val ORCHESTRATOR_ID = "orchestrator"
        private const val INBOX_CAPACITY = 20
        private val LOG = Logger.getInstance(WorkerMessageBus::class.java)
    }

    private val inboxes = ConcurrentHashMap<String, Channel<WorkerMessage>>()

    fun createInbox(agentId: String): Channel<WorkerMessage> {
        val channel = Channel<WorkerMessage>(
            capacity = INBOX_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        inboxes[agentId] = channel
        LOG.info("WorkerMessageBus: created inbox for $agentId")
        return channel
    }

    fun closeInbox(agentId: String) {
        val channel = inboxes.remove(agentId)
        if (channel != null) {
            // Drain and log remaining messages
            val remaining = mutableListOf<WorkerMessage>()
            while (true) {
                val msg = channel.tryReceive().getOrNull() ?: break
                remaining.add(msg)
            }
            channel.close()
            if (remaining.isNotEmpty()) {
                LOG.info("WorkerMessageBus: closed inbox for $agentId with ${remaining.size} unread messages")
            }
        }
    }

    fun send(message: WorkerMessage): Boolean {
        val channel = inboxes[message.to] ?: return false
        return try {
            channel.trySend(message).isSuccess
        } catch (_: Exception) {
            false
        }
    }

    fun drain(agentId: String): List<WorkerMessage> {
        val channel = inboxes[agentId] ?: return emptyList()
        val messages = mutableListOf<WorkerMessage>()
        while (true) {
            val msg = channel.tryReceive().getOrNull() ?: break
            messages.add(msg)
        }
        return messages
    }

    fun hasPending(agentId: String): Boolean {
        val channel = inboxes[agentId] ?: return false
        return !channel.isEmpty
    }

    fun close() {
        val ids = inboxes.keys.toList()
        ids.forEach { closeInbox(it) }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :agent:test --tests "*.WorkerMessageBusTest" -x verifyPlugin`
Expected: All 9 tests PASS

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/WorkerMessageBus.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/WorkerMessageBusTest.kt
git commit -m "feat(agent): add WorkerMessageBus for parent-child communication"
```

---

### Task 3: WorkerContext Coroutine Context Element

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/WorkerContext.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/WorkerContextTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
package com.workflow.orchestrator.agent.runtime

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.coroutines.coroutineContext

class WorkerContextTest {

    @Test
    fun `WorkerContext is accessible from coroutine context`() = runTest {
        val ctx = WorkerContext(
            agentId = "agent-1",
            workerType = WorkerType.CODER,
            messageBus = null,
            fileOwnership = null
        )
        withContext(ctx) {
            val retrieved = coroutineContext[WorkerContext]
            assertNotNull(retrieved)
            assertEquals("agent-1", retrieved!!.agentId)
            assertEquals(WorkerType.CODER, retrieved.workerType)
            assertFalse(retrieved.isOrchestrator)
        }
    }

    @Test
    fun `orchestrator context has null agentId`() = runTest {
        val ctx = WorkerContext(
            agentId = null,
            workerType = WorkerType.ORCHESTRATOR,
            messageBus = null,
            fileOwnership = null
        )
        withContext(ctx) {
            val retrieved = coroutineContext[WorkerContext]
            assertNotNull(retrieved)
            assertNull(retrieved!!.agentId)
            assertTrue(retrieved.isOrchestrator)
        }
    }

    @Test
    fun `WorkerContext carries messageBus and fileOwnership`() = runTest {
        val bus = WorkerMessageBus()
        val registry = FileOwnershipRegistry()
        val ctx = WorkerContext(
            agentId = "agent-2",
            workerType = WorkerType.ANALYZER,
            messageBus = bus,
            fileOwnership = registry
        )
        withContext(ctx) {
            val retrieved = coroutineContext[WorkerContext]
            assertNotNull(retrieved)
            assertSame(bus, retrieved!!.messageBus)
            assertSame(registry, retrieved.fileOwnership)
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :agent:test --tests "*.WorkerContextTest" -x verifyPlugin`
Expected: Compilation failure — `WorkerContext` not found

- [ ] **Step 3: Implement WorkerContext**

```kotlin
package com.workflow.orchestrator.agent.runtime

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

data class WorkerContext(
    val agentId: String?,
    val workerType: WorkerType,
    val messageBus: WorkerMessageBus?,
    val fileOwnership: FileOwnershipRegistry?
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<WorkerContext>

    val isOrchestrator: Boolean get() = agentId == null
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :agent:test --tests "*.WorkerContextTest" -x verifyPlugin`
Expected: All 3 tests PASS

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/WorkerContext.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/WorkerContextTest.kt
git commit -m "feat(agent): add WorkerContext coroutine context element"
```

---

### Task 4: SendMessageToParentTool

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SendMessageToParentTool.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/SendMessageToParentToolTest.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt:174` — register the tool

- [ ] **Step 1: Write failing tests**

```kotlin
package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.runtime.*
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SendMessageToParentToolTest {

    private val project: Project = mockk(relaxed = true)
    private val tool = SendMessageToParentTool()

    @Test
    fun `tool name is send_message_to_parent`() {
        assertEquals("send_message_to_parent", tool.name)
    }

    @Test
    fun `allowed for all worker types`() {
        assertTrue(tool.allowedWorkers.containsAll(
            setOf(WorkerType.ORCHESTRATOR, WorkerType.ANALYZER, WorkerType.CODER,
                  WorkerType.REVIEWER, WorkerType.TOOLER)
        ))
    }

    @Test
    fun `sends finding to orchestrator inbox`() = runTest {
        val bus = WorkerMessageBus()
        bus.createInbox(WorkerMessageBus.ORCHESTRATOR_ID)

        val ctx = WorkerContext(
            agentId = "agent-1",
            workerType = WorkerType.CODER,
            messageBus = bus,
            fileOwnership = null
        )

        val params = buildJsonObject {
            put("type", "finding")
            put("content", "Found circular dependency in AuthService.kt")
        }

        val result = withContext(ctx) {
            tool.execute(params, project)
        }

        assertFalse(result.isError)
        val messages = bus.drain(WorkerMessageBus.ORCHESTRATOR_ID)
        assertEquals(1, messages.size)
        assertEquals(MessageType.FINDING, messages[0].type)
        assertEquals("agent-1", messages[0].from)
        assertEquals("Found circular dependency in AuthService.kt", messages[0].content)
    }

    @Test
    fun `sends status_update to orchestrator`() = runTest {
        val bus = WorkerMessageBus()
        bus.createInbox(WorkerMessageBus.ORCHESTRATOR_ID)

        val ctx = WorkerContext("agent-2", WorkerType.CODER, bus, null)
        val params = buildJsonObject {
            put("type", "status_update")
            put("content", "Completed 3/5 files")
        }

        val result = withContext(ctx) { tool.execute(params, project) }
        assertFalse(result.isError)
        val messages = bus.drain(WorkerMessageBus.ORCHESTRATOR_ID)
        assertEquals(MessageType.STATUS_UPDATE, messages[0].type)
    }

    @Test
    fun `returns error when no WorkerContext`() = runTest {
        val params = buildJsonObject {
            put("type", "finding")
            put("content", "test")
        }
        val result = tool.execute(params, project)
        assertTrue(result.isError)
    }

    @Test
    fun `returns error when no messageBus`() = runTest {
        val ctx = WorkerContext("agent-1", WorkerType.CODER, null, null)
        val params = buildJsonObject {
            put("type", "finding")
            put("content", "test")
        }
        val result = withContext(ctx) { tool.execute(params, project) }
        assertTrue(result.isError)
    }

    @Test
    fun `returns error for missing type`() = runTest {
        val bus = WorkerMessageBus()
        bus.createInbox(WorkerMessageBus.ORCHESTRATOR_ID)
        val ctx = WorkerContext("agent-1", WorkerType.CODER, bus, null)
        val params = buildJsonObject { put("content", "test") }
        val result = withContext(ctx) { tool.execute(params, project) }
        assertTrue(result.isError)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :agent:test --tests "*.SendMessageToParentToolTest" -x verifyPlugin`
Expected: Compilation failure — `SendMessageToParentTool` not found

- [ ] **Step 3: Implement SendMessageToParentTool**

```kotlin
package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.*
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.coroutineContext

class SendMessageToParentTool : AgentTool {

    override val name = "send_message_to_parent"

    override val description = "Send a message to the parent orchestrator. " +
        "Use for critical findings that affect the overall task, or status updates on progress. " +
        "The parent sees your message at its next iteration — it is not instant.\n\n" +
        "Use 'finding' for discoveries that may change the approach (e.g., circular dependency, " +
        "missing API, breaking change). Use 'status_update' for progress reports."

    override val parameters = FunctionParameters(
        properties = mapOf(
            "type" to ParameterProperty(
                type = "string",
                enum = listOf("finding", "status_update"),
                description = "Message type: 'finding' for discoveries that affect the task, " +
                    "'status_update' for progress reports"
            ),
            "content" to ParameterProperty(
                type = "string",
                description = "The message content. Be concise and actionable."
            )
        ),
        required = listOf("type", "content")
    )

    override val allowedWorkers = setOf(
        WorkerType.ORCHESTRATOR, WorkerType.ANALYZER, WorkerType.CODER,
        WorkerType.REVIEWER, WorkerType.TOOLER
    )

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val typeStr = params["type"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'type' parameter required", "Error: missing type",
                ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val content = params["content"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'content' parameter required", "Error: missing content",
                ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val messageType = when (typeStr) {
            "finding" -> MessageType.FINDING
            "status_update" -> MessageType.STATUS_UPDATE
            else -> return ToolResult("Error: type must be 'finding' or 'status_update'",
                "Error: invalid type", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        val workerCtx = coroutineContext[WorkerContext]
            ?: return ToolResult("Error: not running in a worker context",
                "Error: no worker context", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val bus = workerCtx.messageBus
            ?: return ToolResult("Error: message bus not available",
                "Error: no message bus", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val agentId = workerCtx.agentId ?: "orchestrator"
        val sent = bus.send(WorkerMessage(
            from = agentId,
            to = WorkerMessageBus.ORCHESTRATOR_ID,
            type = messageType,
            content = content
        ))

        return if (sent) {
            ToolResult(
                content = "Message sent to orchestrator. Continue with your task.",
                summary = "Sent $typeStr to orchestrator",
                tokenEstimate = 15
            )
        } else {
            ToolResult(
                content = "Warning: orchestrator inbox not available. Message not delivered. Continue with your task.",
                summary = "Message delivery failed",
                tokenEstimate = 15,
                isError = true
            )
        }
    }
}
```

- [ ] **Step 4: Register tool in AgentService.toolRegistry**

In `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt`, add after line 233 (`register(WorkerCompleteTool())`):

```kotlin
register(SendMessageToParentTool())
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :agent:test --tests "*.SendMessageToParentToolTest" -x verifyPlugin`
Expected: All 6 tests PASS

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SendMessageToParentTool.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/SendMessageToParentToolTest.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt
git commit -m "feat(agent): add send_message_to_parent tool for child-to-parent messaging"
```

---

### Task 5: Wire AgentService — Registry + Bus Lifecycle

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt:80-170`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ConversationSession.kt:54-72`

- [ ] **Step 1: Add fields to AgentService**

In `AgentService.kt`, after the `totalSessionTokens` field (line 84), add:

```kotlin
/** Per-session file ownership registry. Created by ConversationSession, shared across all workers. */
@Volatile var fileOwnershipRegistry: FileOwnershipRegistry? = null

/** Per-session message bus. Created by ConversationSession, shared across all workers. */
@Volatile var workerMessageBus: WorkerMessageBus? = null
```

- [ ] **Step 2: Update killWorker() to release ownership and close inbox**

In `AgentService.kt`, update `killWorker()` (line 157-164):

```kotlin
fun killWorker(agentId: String): Boolean {
    val worker = backgroundWorkers[agentId] ?: return false
    worker.job.cancel()
    worker.status = WorkerStatus.KILLED
    backgroundWorkers.remove(agentId)
    activeWorkerCount.decrementAndGet()
    fileOwnershipRegistry?.releaseAll(agentId)
    workerMessageBus?.closeInbox(agentId)
    return true
}
```

- [ ] **Step 3: Initialize registry and bus in ConversationSession**

In `ConversationSession.kt`, find the `companion object` with `create()` factory method. In the `create()` method, after creating the session instance, add initialization of registry and bus on the `AgentService`:

First, find the `create()` method:

Run: `grep -n "fun create" agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ConversationSession.kt`

Then add after the session instance is created:

```kotlin
// Initialize subagent coordination infrastructure
val agentSvc = try { AgentService.getInstance(project) } catch (_: Exception) { null }
if (agentSvc != null) {
    agentSvc.fileOwnershipRegistry = FileOwnershipRegistry()
    agentSvc.workerMessageBus = WorkerMessageBus().also {
        it.createInbox(WorkerMessageBus.ORCHESTRATOR_ID)
    }
}
```

- [ ] **Step 4: Clean up on session end**

In `ConversationSession.markCompleted()` (line 126), add cleanup:

```kotlin
fun markCompleted(success: Boolean) {
    status = if (success) SessionStatus.COMPLETED else SessionStatus.FAILED
    // Clean up subagent coordination
    try {
        val agentSvc = AgentService.getInstance(/* need project ref */)
        agentSvc.workerMessageBus?.close()
        agentSvc.workerMessageBus = null
        agentSvc.fileOwnershipRegistry = null
    } catch (_: Exception) { /* best effort */ }
    try {
        GlobalSessionIndex.getInstance().updateSession(sessionId) { it.copy(status = status.value) }
    } catch (_: Exception) { /* best effort — index may not be available in tests */ }
}
```

Note: `ConversationSession` doesn't currently hold a `project` reference. Check whether it's available through `projectBasePath` or if `AgentService` can be accessed another way. If not, store a `project` reference on the session or do cleanup in `AgentController` which holds both.

- [ ] **Step 5: Run existing tests to verify no regressions**

Run: `./gradlew :agent:test -x verifyPlugin`
Expected: All existing tests PASS

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ConversationSession.kt
git commit -m "feat(agent): wire FileOwnershipRegistry and WorkerMessageBus lifecycle in AgentService"
```

---

### Task 6: Wire WorkerContext into WorkerSession

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/WorkerSession.kt:40-97`
- Modify: `agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/WorkerSessionTest.kt`

- [ ] **Step 1: Add WorkerContext parameters to WorkerSession**

Update `WorkerSession` constructor (line 40-46) to accept the coordination infrastructure:

```kotlin
class WorkerSession(
    private val maxIterations: Int = 10,
    private val parentJob: kotlinx.coroutines.Job? = null,
    private val transcriptStore: WorkerTranscriptStore? = null,
    val agentId: String = WorkerTranscriptStore.generateAgentId(),
    private val uiCallbacks: AgentService.SubAgentCallbacks? = null,
    private val messageBus: WorkerMessageBus? = null,
    private val fileOwnership: FileOwnershipRegistry? = null
)
```

- [ ] **Step 2: Wrap execute body in withContext(WorkerContext(...))**

Update `execute()` (line 76-97) to set the coroutine context:

```kotlin
suspend fun execute(
    workerType: WorkerType,
    systemPrompt: String,
    task: String,
    tools: Map<String, AgentTool>,
    toolDefinitions: List<ToolDefinition>,
    brain: LlmBrain,
    bridge: EventSourcedContextBridge,
    project: Project,
    maxOutputTokens: Int? = null
): WorkerResult {
    LOG.info("WorkerSession: starting $workerType worker for task: ${task.take(100)}")

    bridge.addSystemPrompt(systemPrompt)
    recordMessage("system", systemPrompt)

    bridge.addUserMessage(task)
    recordMessage("user", task)

    return withContext(WorkerContext(
        agentId = agentId,
        workerType = workerType,
        messageBus = messageBus,
        fileOwnership = fileOwnership
    )) {
        runReactLoop(tools, toolDefinitions, brain, bridge, project, maxOutputTokens)
    }
}
```

Do the same for `executeFromContext()` (line 103-113):

```kotlin
suspend fun executeFromContext(
    tools: Map<String, AgentTool>,
    toolDefinitions: List<ToolDefinition>,
    brain: LlmBrain,
    bridge: EventSourcedContextBridge,
    project: Project,
    maxOutputTokens: Int? = null
): WorkerResult {
    LOG.info("WorkerSession: resuming agent $agentId from existing context")
    // WorkerContext should already be set by execute() or re-set here for resume
    return withContext(WorkerContext(
        agentId = agentId,
        workerType = WorkerType.ORCHESTRATOR,  // resume doesn't have workerType; default to ORCHESTRATOR
        messageBus = messageBus,
        fileOwnership = fileOwnership
    )) {
        runReactLoop(tools, toolDefinitions, brain, bridge, project, maxOutputTokens)
    }
}
```

Add `import kotlinx.coroutines.withContext` to imports.

- [ ] **Step 3: Add inbox drain at iteration top in runReactLoop**

In `runReactLoop()` (line 118-312), add message consumption at the top of the loop, after the `parentJob` check (line 131-140):

```kotlin
for (iteration in 1..maxIterations) {
    if (parentJob?.isActive == false) {
        // ... existing cancellation code
    }

    // Drain pending messages from parent
    if (messageBus != null) {
        val pending = messageBus.drain(agentId)
        if (pending.isNotEmpty()) {
            val formatted = pending.joinToString("\n") { msg ->
                "<parent_message type=\"${msg.type.name.lowercase()}\" timestamp=\"${msg.timestamp}\">\n" +
                "${msg.content}\n" +
                "</parent_message>"
            }
            bridge.addSystemMessage(formatted)
            recordMessage("system", formatted)
        }
    }

    LOG.info("WorkerSession: iteration $iteration/$maxIterations")
    // ... rest of loop
}
```

- [ ] **Step 4: Update existing WorkerSessionTest to pass new params**

In `WorkerSessionTest.kt`, update the `setup()` method (line 28-29):

```kotlin
@BeforeEach
fun setup() {
    session = WorkerSession(maxIterations = 5)  // No change needed — new params are optional
    // ...
}
```

No change required — the new parameters have defaults (`null`). Existing tests pass as-is.

- [ ] **Step 5: Add test for message drain during iteration**

Add to `WorkerSessionTest.kt`:

```kotlin
@Test
fun `drains parent messages at iteration start`() = runTest {
    val bus = WorkerMessageBus()
    bus.createInbox("test-agent")
    bus.send(WorkerMessage(
        from = WorkerMessageBus.ORCHESTRATOR_ID,
        to = "test-agent",
        type = MessageType.INSTRUCTION,
        content = "Focus on service layer"
    ))

    val sessionWithBus = WorkerSession(
        maxIterations = 5,
        agentId = "test-agent",
        messageBus = bus
    )

    // LLM returns a text response (no tools) — will nudge then force-accept
    coEvery { brain.chat(any(), any(), any(), any()) } returns ApiResult.Success(
        ChatCompletionResponse(
            id = "resp-1",
            choices = listOf(Choice(0, ChatMessage("assistant", "Done."), "stop")),
            usage = UsageInfo(100, 20, 120)
        )
    )

    val result = sessionWithBus.execute(
        workerType = WorkerType.CODER,
        systemPrompt = "You are a coder",
        task = "Implement feature",
        tools = emptyMap(),
        toolDefinitions = emptyList(),
        brain = brain,
        bridge = bridge,
        project = project
    )

    // Verify system message was injected with parent message
    verify { bridge.addSystemMessage(match { it.contains("Focus on service layer") }) }
}
```

- [ ] **Step 6: Run tests**

Run: `./gradlew :agent:test --tests "*.WorkerSessionTest" -x verifyPlugin`
Expected: All tests PASS

- [ ] **Step 7: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/WorkerSession.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/WorkerSessionTest.kt
git commit -m "feat(agent): wire WorkerContext and message drain into WorkerSession"
```

---

### Task 7: Wire SpawnAgentTool — Pass Bus/Registry, Remove Timeout, Add Send

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SpawnAgentTool.kt`
- Modify: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/SpawnAgentToolTest.kt`

- [ ] **Step 1: Remove WORKER_TIMEOUT_MS and withTimeout wrappers**

In `SpawnAgentTool.kt`:

Remove line 45: `private const val WORKER_TIMEOUT_MS = 300_000L // 5 minutes`

In `execute()` (around line 301), replace:
```kotlin
val workerResult: WorkerResult = withTimeout(WORKER_TIMEOUT_MS) {
    workerSession.execute(...)
}
```
With:
```kotlin
val workerResult: WorkerResult = workerSession.execute(
    workerType = workerType,
    systemPrompt = systemPrompt,
    task = prompt,
    tools = toolMap,
    toolDefinitions = toolDefinitions,
    brain = agentService.brain,
    bridge = contextManager,
    project = project,
    maxOutputTokens = AgentSettings.getInstance(project).state.maxOutputTokens
)
```

Do the same in `executeResume()` (around line 477) and `executeBackground()` (around line 569).

Remove all three `TimeoutCancellationException` catch blocks (around lines 377-392).

Remove `import kotlinx.coroutines.withTimeout` if no longer used.

- [ ] **Step 2: Pass messageBus and fileOwnership to WorkerSession constructor**

In the foreground spawn section of `execute()` (around line 294), update:

```kotlin
val workerSession = WorkerSession(
    maxIterations = maxIter,
    parentJob = parentJob,
    transcriptStore = transcriptStore,
    agentId = agentId,
    uiCallbacks = uiCallbacks,
    messageBus = agentService.workerMessageBus,
    fileOwnership = agentService.fileOwnershipRegistry
)
```

Also create the worker's inbox:
```kotlin
agentService.workerMessageBus?.createInbox(agentId)
```

And in the `finally` block (around line 410-412), add cleanup:
```kotlin
finally {
    agentService.activeWorkerCount.decrementAndGet()
    agentService.fileOwnershipRegistry?.releaseAll(agentId)
    agentService.workerMessageBus?.closeInbox(agentId)
}
```

Do the same for `executeResume()` and `executeBackground()`.

- [ ] **Step 3: Add send/message parameters**

Add to the `parameters` map (after line 133):

```kotlin
"send" to ParameterProperty(
    type = "string",
    description = "Agent ID to send a message to. Use with 'message' parameter to send an instruction to a running worker."
),
"message" to ParameterProperty(
    type = "string",
    description = "Instruction message to send to a running agent. Requires 'send' parameter."
)
```

- [ ] **Step 4: Add send handling in execute()**

In `execute()`, after the kill check (after line 159) and before the description/prompt parsing:

```kotlin
// --- 0b. Send check (no description/prompt required) ---
val sendTo = params["send"]?.jsonPrimitive?.contentOrNull
val sendMessage = params["message"]?.jsonPrimitive?.contentOrNull
if (sendTo != null) {
    if (sendMessage.isNullOrBlank()) {
        return errorResult("Error: 'message' parameter required when using 'send'")
    }
    val bus = agentService.workerMessageBus
        ?: return errorResult("Error: message bus not available. No active session.")
    val sent = bus.send(WorkerMessage(
        from = WorkerMessageBus.ORCHESTRATOR_ID,
        to = sendTo,
        type = MessageType.INSTRUCTION,
        content = sendMessage
    ))
    return if (sent) {
        ToolResult(
            "Message sent to agent '$sendTo'. It will receive this at its next iteration.",
            "Sent instruction to $sendTo",
            30
        )
    } else {
        errorResult("Agent '$sendTo' not found or inbox closed. Active: ${agentService.listBackgroundWorkers().joinToString { it.agentId }}")
    }
}
```

- [ ] **Step 5: Update KDoc comment**

Replace the class KDoc (lines 25-39) to remove timeout references:

```kotlin
/**
 * Spawns a subagent to handle a task autonomously, matching Claude Code's Agent tool design.
 *
 * Supports lifecycle operations:
 * - **Spawn:** Create a new worker with isolated context, filtered tools, and file ownership tracking
 * - **Resume:** Reload a previous agent's transcript and continue execution
 * - **Background:** Launch an agent in a detached coroutine, return immediately
 * - **Kill:** Cancel a running background agent via its agentId
 * - **Send:** Send an instruction message to a running worker
 *
 * Only available to ORCHESTRATOR-level sessions (the main agent).
 * Workers cannot spawn further agents, preventing nested delegation.
 *
 * Workers are bounded by iteration limits (default 10) and context budget (150K),
 * not wall-clock timeouts. On failure, file changes are rolled back via LocalHistory.
 */
```

- [ ] **Step 6: Add tests for send and timeout removal**

Add to `SpawnAgentToolTest.kt`:

```kotlin
@Test
fun `send to nonexistent agent returns error`() = runTest {
    every { agentService.workerMessageBus } returns WorkerMessageBus()
    every { agentService.listBackgroundWorkers() } returns emptyList()

    val tool = SpawnAgentTool()
    val params = buildJsonObject {
        put("description", "test")
        put("prompt", "test")
        put("send", "agent-999")
        put("message", "focus on service layer")
    }
    val result = tool.execute(params, project)
    assertTrue(result.isError)
    assertTrue(result.content.contains("not found"))
}

@Test
fun `send delivers message to worker inbox`() = runTest {
    val bus = WorkerMessageBus()
    bus.createInbox("agent-123")
    every { agentService.workerMessageBus } returns bus

    val tool = SpawnAgentTool()
    val params = buildJsonObject {
        put("description", "test")
        put("prompt", "test")
        put("send", "agent-123")
        put("message", "focus on service layer")
    }
    val result = tool.execute(params, project)
    assertFalse(result.isError)
    assertTrue(result.content.contains("Message sent"))

    val messages = bus.drain("agent-123")
    assertEquals(1, messages.size)
    assertEquals("focus on service layer", messages[0].content)
}

@Test
fun `send requires message parameter`() = runTest {
    every { agentService.workerMessageBus } returns WorkerMessageBus()

    val tool = SpawnAgentTool()
    val params = buildJsonObject {
        put("description", "test")
        put("prompt", "test")
        put("send", "agent-123")
    }
    val result = tool.execute(params, project)
    assertTrue(result.isError)
    assertTrue(result.content.contains("'message' parameter required"))
}
```

- [ ] **Step 7: Run all agent tests**

Run: `./gradlew :agent:test -x verifyPlugin`
Expected: All tests PASS

- [ ] **Step 8: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SpawnAgentTool.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/SpawnAgentToolTest.kt
git commit -m "feat(agent): remove timeout, add send/message params, wire bus/registry into SpawnAgentTool"
```

---

### Task 8: Wire File Ownership Checks into EditFileTool, CreateFileTool, ReadFileTool

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/EditFileTool.kt:47-58`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/CreateFileTool.kt:47-56`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/ReadFileTool.kt:38-46`

- [ ] **Step 1: Add ownership check to EditFileTool**

In `EditFileTool.execute()`, after path resolution (line 58 `val resolvedPath = path!!`), add:

```kotlin
// File ownership check — workers cannot edit files owned by other workers
val workerCtx = kotlin.coroutines.coroutineContext[WorkerContext]
if (workerCtx != null && !workerCtx.isOrchestrator) {
    val registry = workerCtx.fileOwnership
    if (registry != null) {
        val claim = registry.claim(resolvedPath, workerCtx.agentId!!, workerCtx.workerType)
        if (claim.result == ClaimResult.DENIED) {
            // Notify orchestrator about the conflict
            workerCtx.messageBus?.send(WorkerMessage(
                from = workerCtx.agentId!!,
                to = WorkerMessageBus.ORCHESTRATOR_ID,
                type = MessageType.FILE_CONFLICT,
                content = "Edit denied for '$resolvedPath' — locked by agent '${claim.ownerAgentId}'",
                metadata = mapOf("file" to resolvedPath, "owner" to (claim.ownerAgentId ?: ""))
            ))
            return ToolResult(
                "Error: File '$rawPath' is locked by agent '${claim.ownerAgentId}'. " +
                    "Wait for it to complete or ask the orchestrator to coordinate.",
                "File locked by ${claim.ownerAgentId}",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }
}
```

Add imports:
```kotlin
import com.workflow.orchestrator.agent.runtime.WorkerContext
import com.workflow.orchestrator.agent.runtime.ClaimResult
import com.workflow.orchestrator.agent.runtime.WorkerMessage
import com.workflow.orchestrator.agent.runtime.WorkerMessageBus
import com.workflow.orchestrator.agent.runtime.MessageType
```

- [ ] **Step 2: Add ownership check to CreateFileTool**

In `CreateFileTool.execute()`, after path resolution (line 56 `val resolvedPath = path!!`), add the same pattern:

```kotlin
// File ownership check
val workerCtx = kotlin.coroutines.coroutineContext[WorkerContext]
if (workerCtx != null && !workerCtx.isOrchestrator) {
    val registry = workerCtx.fileOwnership
    if (registry != null) {
        val claim = registry.claim(resolvedPath, workerCtx.agentId!!, workerCtx.workerType)
        if (claim.result == ClaimResult.DENIED) {
            workerCtx.messageBus?.send(WorkerMessage(
                from = workerCtx.agentId!!,
                to = WorkerMessageBus.ORCHESTRATOR_ID,
                type = MessageType.FILE_CONFLICT,
                content = "Create denied for '$resolvedPath' — locked by agent '${claim.ownerAgentId}'",
                metadata = mapOf("file" to resolvedPath, "owner" to (claim.ownerAgentId ?: ""))
            ))
            return ToolResult(
                "Error: File '$rawPath' is locked by agent '${claim.ownerAgentId}'. " +
                    "Wait for it to complete or ask the orchestrator to coordinate.",
                "File locked by ${claim.ownerAgentId}",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }
}
```

- [ ] **Step 3: Add ownership warning to ReadFileTool**

In `ReadFileTool.execute()`, after the binary file check (line 59), add:

```kotlin
// Warn if file is being edited by another worker (stale read risk)
val workerCtx = kotlin.coroutines.coroutineContext[WorkerContext]
if (workerCtx != null && !workerCtx.isOrchestrator && workerCtx.agentId != null) {
    val registry = workerCtx.fileOwnership
    if (registry != null && registry.isOwnedByOther(resolvedPath, workerCtx.agentId!!)) {
        val owner = registry.getOwner(resolvedPath)
        // We'll prepend a warning to the result content later
        // Store it in a local variable
        var ownershipWarning = "⚠ This file is being actively edited by agent '${owner?.agentId}'. Contents may change.\n\n"
    }
}
```

Actually, a cleaner approach — add the warning when building the result. After the file content is read and before returning, prepend the warning:

```kotlin
// At the end of execute(), before the return statement that returns the file content:
val ownerWarning = if (workerCtx != null && !workerCtx.isOrchestrator && workerCtx.agentId != null) {
    val registry = workerCtx.fileOwnership
    if (registry != null && registry.isOwnedByOther(resolvedPath, workerCtx.agentId!!)) {
        val owner = registry.getOwner(resolvedPath)
        "⚠ This file is being actively edited by agent '${owner?.agentId}'. Contents may change.\n\n"
    } else ""
} else ""

// Then prepend ownerWarning to the content in the ToolResult
```

Add imports to ReadFileTool:
```kotlin
import com.workflow.orchestrator.agent.runtime.WorkerContext
```

- [ ] **Step 4: Write tests for ownership checks**

Create `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/FileOwnershipIntegrationTest.kt`:

```kotlin
package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.runtime.*
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class FileOwnershipIntegrationTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var project: Project
    private lateinit var registry: FileOwnershipRegistry
    private lateinit var bus: WorkerMessageBus

    @BeforeEach
    fun setup() {
        project = mockk(relaxed = true) {
            every { basePath } returns tempDir.absolutePath
        }
        registry = FileOwnershipRegistry()
        bus = WorkerMessageBus()
        bus.createInbox(WorkerMessageBus.ORCHESTRATOR_ID)
    }

    @Test
    fun `edit_file denied when file owned by another agent`() = runTest {
        // Agent-1 claims the file
        registry.claim("${tempDir.absolutePath}/Auth.kt", "agent-1", WorkerType.CODER)

        // Create the file so edit can find it
        File(tempDir, "Auth.kt").writeText("class Auth {}")

        // Agent-2 tries to edit
        val ctx = WorkerContext("agent-2", WorkerType.CODER, bus, registry)
        val tool = EditFileTool()
        val params = buildJsonObject {
            put("path", "${tempDir.absolutePath}/Auth.kt")
            put("old_string", "class Auth {}")
            put("new_string", "class Auth { val x = 1 }")
            put("description", "test edit")
        }

        val result = withContext(ctx) { tool.execute(params, project) }
        assertTrue(result.isError)
        assertTrue(result.content.contains("locked by agent"))

        // Verify FILE_CONFLICT message sent to orchestrator
        val messages = bus.drain(WorkerMessageBus.ORCHESTRATOR_ID)
        assertEquals(1, messages.size)
        assertEquals(MessageType.FILE_CONFLICT, messages[0].type)
    }

    @Test
    fun `edit_file allowed when file owned by same agent`() = runTest {
        val filePath = "${tempDir.absolutePath}/Auth.kt"
        File(tempDir, "Auth.kt").writeText("class Auth {}")
        registry.claim(filePath, "agent-1", WorkerType.CODER)

        val ctx = WorkerContext("agent-1", WorkerType.CODER, bus, registry)
        val tool = EditFileTool()
        val params = buildJsonObject {
            put("path", filePath)
            put("old_string", "class Auth {}")
            put("new_string", "class Auth { val x = 1 }")
            put("description", "test edit")
        }

        val result = withContext(ctx) { tool.execute(params, project) }
        // Should succeed (or at least not fail due to ownership — may fail due to VFS mocking)
        // The key assertion is that it does NOT return "locked by agent"
        assertFalse(result.content.contains("locked by agent"))
    }

    @Test
    fun `edit_file allowed for orchestrator regardless`() = runTest {
        val filePath = "${tempDir.absolutePath}/Auth.kt"
        File(tempDir, "Auth.kt").writeText("class Auth {}")
        registry.claim(filePath, "agent-1", WorkerType.CODER)

        // Orchestrator context — agentId is null
        val ctx = WorkerContext(null, WorkerType.ORCHESTRATOR, bus, registry)
        val tool = EditFileTool()
        val params = buildJsonObject {
            put("path", filePath)
            put("old_string", "class Auth {}")
            put("new_string", "class Auth { val x = 1 }")
            put("description", "test edit")
        }

        val result = withContext(ctx) { tool.execute(params, project) }
        assertFalse(result.content.contains("locked by agent"))
    }
}
```

- [ ] **Step 5: Run tests**

Run: `./gradlew :agent:test --tests "*.FileOwnershipIntegrationTest" -x verifyPlugin`
Expected: All 3 tests PASS

Run: `./gradlew :agent:test -x verifyPlugin`
Expected: All existing tests still PASS

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/EditFileTool.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/CreateFileTool.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/ReadFileTool.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/FileOwnershipIntegrationTest.kt
git commit -m "feat(agent): wire file ownership checks into edit/create/read tools"
```

---

### Task 9: Wire Message Drain into SingleAgentSession (Orchestrator Side)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt:306-312`

- [ ] **Step 1: Add orchestrator inbox drain at iteration top**

In `SingleAgentSession.execute()`, inside the main loop (line 306 `for (iteration in 1..maxIterations)`), after the cancellation check (around line 308-311), add:

```kotlin
// Drain messages from child workers (findings, status updates, file conflicts)
val bus = try {
    AgentService.getInstance(project).workerMessageBus
} catch (_: Exception) { null }

if (bus != null) {
    val childMessages = bus.drain(WorkerMessageBus.ORCHESTRATOR_ID)
    if (childMessages.isNotEmpty()) {
        val formatted = childMessages.joinToString("\n") { msg ->
            "<worker_message from=\"${msg.from}\" type=\"${msg.type.name.lowercase()}\">\n" +
            "${msg.content}\n" +
            "</worker_message>"
        }
        bridge.addSystemMessage(formatted)
        eventLog?.log(AgentEventType.INFO, "Received ${childMessages.size} worker messages")
    }
}
```

Add import: `import com.workflow.orchestrator.agent.runtime.WorkerMessageBus`

- [ ] **Step 2: Run all agent tests**

Run: `./gradlew :agent:test -x verifyPlugin`
Expected: All tests PASS

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt
git commit -m "feat(agent): wire orchestrator inbox drain in SingleAgentSession iteration loop"
```

---

### Task 10: Update Documentation

**Files:**
- Modify: `agent/CLAUDE.md`

- [ ] **Step 1: Add Subagent Coordination section to agent/CLAUDE.md**

After the "Agent Tool (Subagent Management)" section, add:

```markdown
## Subagent Coordination

### File Ownership
`FileOwnershipRegistry` prevents concurrent workers from editing the same file. Write tools (`edit_file`, `create_file`) acquire ownership before proceeding; `read_file` warns if the file is owned by another worker. Ownership is released when workers complete, fail, or are killed.

- Orchestrator is exempt from ownership checks
- Whole-file granularity (not line-level)
- Canonical paths prevent aliasing

### Parent↔Child Messaging
`WorkerMessageBus` enables bidirectional communication via Kotlin `Channel(capacity=20, DROP_OLDEST)`:

- **Parent → Child:** `agent(send="agentId", message="...")` sends `INSTRUCTION` to worker's inbox
- **Child → Parent:** `send_message_to_parent(type="finding|status_update", content="...")` sends to orchestrator inbox
- **System:** `FILE_CONFLICT` messages auto-sent on ownership denial
- Messages consumed at ReAct loop iteration boundaries (not instant)

### WorkerContext
Coroutine context element (`AbstractCoroutineContextElement`) carrying `agentId`, `workerType`, `messageBus`, and `fileOwnership` to all tools within a worker's scope. Set via `withContext(WorkerContext(...))` in `WorkerSession.execute()`.
```

- [ ] **Step 2: Update tool count in agent/CLAUDE.md**

Update the tool table to include `send_message_to_parent` in the Core tools row.

- [ ] **Step 3: Commit**

```bash
git add agent/CLAUDE.md
git commit -m "docs: add subagent coordination documentation to agent CLAUDE.md"
```

---

### Task 11: Final Verification

- [ ] **Step 1: Run all agent tests**

Run: `./gradlew :agent:test -x verifyPlugin`
Expected: All tests PASS (existing + ~30 new)

- [ ] **Step 2: Run verifyPlugin**

Run: `./gradlew verifyPlugin`
Expected: PASS — no API compatibility issues

- [ ] **Step 3: Build plugin**

Run: `./gradlew buildPlugin`
Expected: BUILD SUCCESSFUL, ZIP created in `build/distributions/`
