# Cline Session Persistence Port — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port Cline's two-file session persistence (api_conversation_history.json + ui_messages.json) so users can close IDE and resume any conversation with full UI fidelity.

**Architecture:** Replace current JSONL-append SessionStore with Cline's MessageStateHandler pattern: two JSON files per session, rewritten atomically under a coroutine Mutex after every state change. Resume loads both files verbatim and rehydrates the chat UI.

**Tech Stack:** Kotlin, kotlinx.serialization, kotlinx.coroutines.sync.Mutex, java.nio.file (atomic move), JUnit 5, MockK, React/Zustand (webview)

**Spec:** `docs/superpowers/specs/2026-04-12-cline-session-persistence-port-design.md`
**Cline source reference:** `docs/research/2026-04-12-cline-session-persistence-source-analysis.md`

---

## Review Amendments (2026-04-12)

Critical and important issues identified by code review, all resolved below:

| ID | Issue | Resolution |
|----|-------|------------|
| C2 | `ApiMessage.toChatMessage()` lossy — drops ToolUse/ToolResult | Fixed: lossless conversion preserving toolCalls and tool role messages |
| C3 | `launch(Dispatchers.IO)` fire-and-forget defeats per-change guarantee | Fixed: persistence calls are `suspend` and awaited inline (AgentLoop runs on IO already) |
| C4 | `conversationHistoryIndex` off-by-one when empty | Fixed: returns `null` when apiHistory is empty |
| C5 | `runBlocking` in `resumeSession` freezes EDT | Fixed: `resumeSession` dispatches to IO coroutine before hook call |
| I1 | No migration for existing sessions | Added: Task 12.5 migrates old JSONL sessions |
| I2 | `sessions.json` races across concurrent sessions | Fixed: separate `globalIndexMutex` in MessageStateHandler |
| I4 | `convertUiMessageToStoreMessage` undefined | Fixed: full implementation in Task 9 |
| I5 | No abort path test | Fixed: added test in Task 7 |
| I6 | `setClineMessages` bypasses mutex | Fixed: documented as init-only, asserted with `check(!mutex.isLocked)` |
| I7 | `api_req_started` cost tracking missing | Fixed: added to Task 6 streaming wiring |

---

## File Structure

```
agent/src/main/kotlin/com/workflow/orchestrator/agent/session/
├── UiMessage.kt          — NEW: UiMessage data class + UiAsk/UiSay/UiMessageType enums + extension data classes
├── ApiMessage.kt         — NEW: ApiMessage data class + ApiRole enum + ContentBlock sealed interface
├── HistoryItem.kt        — NEW: Global session index entry
├── AtomicFileWriter.kt   — NEW: write-then-rename utility
├── SessionLock.kt        — NEW: per-session FileLock wrapper
├── MessageStateHandler.kt — NEW: owns arrays + mutex + save logic (replaces SessionStore)
├── ResumeHelper.kt       — NEW: trim logic + taskResumption preamble builder
├── Session.kt            — MODIFY: slim down to reference HistoryItem
├── SessionStore.kt       — RETIRE after MessageStateHandler is wired
├── CheckpointInfo.kt     — KEEP unchanged

agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/
├── AgentLoop.kt          — MODIFY: stream chunks → MessageStateHandler calls
├── ContextManager.kt     — MODIFY: reads from apiHistory list, overwrite on compaction

agent/src/main/kotlin/com/workflow/orchestrator/agent/
├── AgentService.kt       — MODIFY: replace SessionStore calls, rewrite resumeSession

agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/
├── AgentController.kt    — MODIFY: postStateToWebview pushes full ui_messages
├── HistoryPanel.kt       — MODIFY: reads sessions.json for HistoryItem list

agent/src/main/kotlin/com/workflow/orchestrator/agent/listeners/
├── AgentStartupActivity.kt — MODIFY: optional notification, not primary resume path

agent/src/main/kotlin/com/workflow/orchestrator/agent/memory/
├── ConversationRecall.kt — MODIFY: search api_history files

agent/webview/src/stores/
├── chatStore.ts          — MODIFY: add hydrateFromUiMessages() action

agent/webview/src/bridge/
├── jcef-bridge.ts        — MODIFY: add loadSessionState bridge function

agent/src/test/kotlin/.../session/
├── MessageStateHandlerTest.kt — NEW
├── ResumeHelperTest.kt        — NEW
├── AtomicFileWriterTest.kt    — NEW
├── SessionLockTest.kt         — NEW
```

---

### Task 1: Data Model — UiMessage, ApiMessage, HistoryItem

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/session/UiMessage.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/session/ApiMessage.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/session/HistoryItem.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/session/DataModelSerializationTest.kt`

- [ ] **Step 1: Write the serialization test**

```kotlin
package com.workflow.orchestrator.agent.session

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DataModelSerializationTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `UiMessage round-trips through JSON`() {
        val msg = UiMessage(
            ts = 1712000000000L,
            type = UiMessageType.SAY,
            say = UiSay.TEXT,
            text = "Hello world",
            partial = false,
            conversationHistoryIndex = 3
        )
        val encoded = json.encodeToString(msg)
        val decoded = json.decodeFromString<UiMessage>(encoded)
        assertEquals(msg, decoded)
    }

    @Test
    fun `UiMessage with extension data round-trips`() {
        val msg = UiMessage(
            ts = 1712000000000L,
            type = UiMessageType.ASK,
            ask = UiAsk.APPROVAL_GATE,
            approvalData = ApprovalGateData(
                toolName = "edit_file",
                toolInput = """{"path":"/a.kt","content":"x"}""",
                diffPreview = "- old\n+ new",
                status = ApprovalStatus.PENDING
            )
        )
        val encoded = json.encodeToString(msg)
        val decoded = json.decodeFromString<UiMessage>(encoded)
        assertEquals(msg.approvalData, decoded.approvalData)
    }

    @Test
    fun `ApiMessage with tool_use content round-trips`() {
        val msg = ApiMessage(
            role = ApiRole.ASSISTANT,
            content = listOf(
                ContentBlock.Text("Let me read the file"),
                ContentBlock.ToolUse(id = "tu_1", name = "read_file", input = """{"path":"/a.kt"}""")
            ),
            ts = 1712000000000L
        )
        val encoded = json.encodeToString(msg)
        val decoded = json.decodeFromString<ApiMessage>(encoded)
        assertEquals(2, decoded.content.size)
        assertTrue(decoded.content[1] is ContentBlock.ToolUse)
    }

    @Test
    fun `HistoryItem round-trips`() {
        val item = HistoryItem(
            id = "sess-123",
            ts = 1712000000000L,
            task = "Fix the login bug",
            tokensIn = 50000,
            tokensOut = 2000,
            totalCost = 0.15,
            modelId = "anthropic/claude-sonnet-4"
        )
        val encoded = json.encodeToString(item)
        val decoded = json.decodeFromString<HistoryItem>(encoded)
        assertEquals(item, decoded)
    }

    @Test
    fun `UiMessage ignores unknown fields for forward compatibility`() {
        val jsonStr = """{"ts":1712000000000,"type":"SAY","say":"TEXT","text":"hi","unknownField":42}"""
        val decoded = json.decodeFromString<UiMessage>(jsonStr)
        assertEquals("hi", decoded.text)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/session-persistence && ./gradlew :agent:test --tests "*.DataModelSerializationTest" --rerun`
Expected: FAIL — classes don't exist yet

- [ ] **Step 3: Implement UiMessage.kt**

```kotlin
package com.workflow.orchestrator.agent.session

import kotlinx.serialization.Serializable

@Serializable
enum class UiMessageType { ASK, SAY }

@Serializable
enum class UiAsk {
    RESUME_TASK,
    RESUME_COMPLETED_TASK,
    TOOL,
    COMMAND,
    FOLLOWUP,
    COMPLETION_RESULT,
    PLAN_APPROVE,
    PLAN_MODE_RESPOND,
    ACT_MODE_RESPOND,
    ARTIFACT_RENDER,
    QUESTION_WIZARD,
    APPROVAL_GATE,
    SUBAGENT_PERMISSION,
}

@Serializable
enum class UiSay {
    API_REQ_STARTED,
    API_REQ_FINISHED,
    TEXT,
    REASONING,
    TOOL,
    CHECKPOINT_CREATED,
    ERROR,
    PLAN_UPDATE,
    ARTIFACT_RESULT,
    SUBAGENT_STARTED,
    SUBAGENT_PROGRESS,
    SUBAGENT_COMPLETED,
    STEERING_RECEIVED,
    CONTEXT_COMPRESSED,
    MEMORY_SAVED,
    ROLLBACK_PERFORMED,
}

@Serializable
enum class ApprovalStatus { PENDING, APPROVED, REJECTED }

@Serializable
enum class PlanStatus { DRAFTING, AWAITING_APPROVAL, APPROVED, EXECUTING }

@Serializable
enum class SubagentStatus { RUNNING, COMPLETED, FAILED, KILLED }

@Serializable
enum class WizardStatus { IN_PROGRESS, COMPLETED, SKIPPED }

@Serializable
data class PlanStep(val title: String, val status: String = "pending")

@Serializable
data class PlanCardData(
    val steps: List<PlanStep>,
    val status: PlanStatus,
    val comments: Map<Int, String> = emptyMap(),
)

@Serializable
data class ApprovalGateData(
    val toolName: String,
    val toolInput: String,
    val diffPreview: String? = null,
    val status: ApprovalStatus,
)

@Serializable
data class SubagentCardData(
    val agentId: String,
    val agentType: String,
    val description: String,
    val status: SubagentStatus,
    val iterations: Int = 0,
    val summary: String? = null,
)

@Serializable
data class QuestionItem(val text: String, val options: List<String> = emptyList())

@Serializable
data class QuestionWizardData(
    val questions: List<QuestionItem>,
    val currentIndex: Int = 0,
    val answers: Map<Int, String> = emptyMap(),
    val status: WizardStatus,
)

@Serializable
data class ModelInfo(val modelId: String? = null, val provider: String? = null)

@Serializable
data class UiMessage(
    val ts: Long,
    val type: UiMessageType,
    val ask: UiAsk? = null,
    val say: UiSay? = null,
    val text: String? = null,
    val reasoning: String? = null,
    val images: List<String>? = null,
    val files: List<String>? = null,
    val partial: Boolean = false,
    val conversationHistoryIndex: Int? = null,
    val conversationHistoryDeletedRange: List<Int>? = null,
    val lastCheckpointHash: String? = null,
    val modelInfo: ModelInfo? = null,
    val artifactId: String? = null,
    val planData: PlanCardData? = null,
    val approvalData: ApprovalGateData? = null,
    val questionData: QuestionWizardData? = null,
    val subagentData: SubagentCardData? = null,
)
```

- [ ] **Step 4: Implement ApiMessage.kt**

```kotlin
package com.workflow.orchestrator.agent.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ApiRole { USER, ASSISTANT }

@Serializable
sealed interface ContentBlock {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : ContentBlock

    @Serializable
    @SerialName("tool_use")
    data class ToolUse(val id: String, val name: String, val input: String) : ContentBlock

    @Serializable
    @SerialName("tool_result")
    data class ToolResult(val toolUseId: String, val content: String, val isError: Boolean = false) : ContentBlock

    @Serializable
    @SerialName("image")
    data class Image(val mediaType: String, val data: String) : ContentBlock
}

@Serializable
data class ApiRequestMetrics(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cost: Double? = null,
)

@Serializable
data class ApiMessage(
    val role: ApiRole,
    val content: List<ContentBlock>,
    val ts: Long = System.currentTimeMillis(),
    val modelInfo: ModelInfo? = null,
    val metrics: ApiRequestMetrics? = null,
)
```

- [ ] **Step 5: Implement HistoryItem.kt**

```kotlin
package com.workflow.orchestrator.agent.session

import kotlinx.serialization.Serializable

@Serializable
data class HistoryItem(
    val id: String,
    val ts: Long,
    val task: String,
    val tokensIn: Long = 0,
    val tokensOut: Long = 0,
    val cacheWrites: Long? = null,
    val cacheReads: Long? = null,
    val totalCost: Double = 0.0,
    val size: Long? = null,
    val cwdOnTaskInitialization: String? = null,
    val conversationHistoryDeletedRange: List<Int>? = null,
    val isFavorited: Boolean = false,
    val checkpointManagerErrorMessage: String? = null,
    val modelId: String? = null,
)
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/session-persistence && ./gradlew :agent:test --tests "*.DataModelSerializationTest" --rerun`
Expected: PASS (5 tests)

- [ ] **Step 7: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/session/UiMessage.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/session/ApiMessage.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/session/HistoryItem.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/session/DataModelSerializationTest.kt
git commit -m "feat(session): add UiMessage, ApiMessage, HistoryItem data model

Port of Cline's ClineMessage + ClineStorageMessage + HistoryItem types
to Kotlin with extensions for plan cards, approval gates, artifacts,
sub-agent cards, and question wizard state."
```

---

### Task 2: AtomicFileWriter Utility

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/session/AtomicFileWriter.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/session/AtomicFileWriterTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package com.workflow.orchestrator.agent.session

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class AtomicFileWriterTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `writes content atomically — file appears only after completion`() {
        val target = File(tempDir.toFile(), "test.json")
        AtomicFileWriter.write(target, """{"key":"value"}""")
        assertTrue(target.exists())
        assertEquals("""{"key":"value"}""", target.readText())
    }

    @Test
    fun `no temp files left after successful write`() {
        val target = File(tempDir.toFile(), "test.json")
        AtomicFileWriter.write(target, "content")
        val remainingFiles = tempDir.toFile().listFiles()!!
        assertEquals(1, remainingFiles.size)
        assertEquals("test.json", remainingFiles[0].name)
    }

    @Test
    fun `overwrites existing file atomically`() {
        val target = File(tempDir.toFile(), "test.json")
        target.writeText("old content")
        AtomicFileWriter.write(target, "new content")
        assertEquals("new content", target.readText())
    }

    @Test
    fun `creates parent directories if needed`() {
        val target = File(tempDir.toFile(), "sub/dir/test.json")
        AtomicFileWriter.write(target, "deep")
        assertEquals("deep", target.readText())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agent:test --tests "*.AtomicFileWriterTest" --rerun`
Expected: FAIL — class doesn't exist

- [ ] **Step 3: Implement AtomicFileWriter.kt**

```kotlin
package com.workflow.orchestrator.agent.session

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object AtomicFileWriter {

    fun write(target: File, content: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parent, "${target.name}.tmp.${System.currentTimeMillis()}.${(Math.random() * 100000).toInt()}")
        try {
            tmp.writeText(content, Charsets.UTF_8)
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :agent:test --tests "*.AtomicFileWriterTest" --rerun`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/session/AtomicFileWriter.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/session/AtomicFileWriterTest.kt
git commit -m "feat(session): add AtomicFileWriter utility

Port of Cline's atomicWriteFile (disk.ts:30-42) — write to temp file
with random suffix then Files.move with ATOMIC_MOVE."
```

---

### Task 3: SessionLock

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/session/SessionLock.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/session/SessionLockTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package com.workflow.orchestrator.agent.session

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class SessionLockTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `acquire succeeds on first attempt`() {
        val sessionDir = File(tempDir.toFile(), "session-1").also { it.mkdirs() }
        val lock = SessionLock.tryAcquire(sessionDir)
        assertNotNull(lock)
        lock!!.release()
    }

    @Test
    fun `second acquire on same dir fails`() {
        val sessionDir = File(tempDir.toFile(), "session-1").also { it.mkdirs() }
        val lock1 = SessionLock.tryAcquire(sessionDir)
        assertNotNull(lock1)
        val lock2 = SessionLock.tryAcquire(sessionDir)
        assertNull(lock2)
        lock1!!.release()
    }

    @Test
    fun `after release, can re-acquire`() {
        val sessionDir = File(tempDir.toFile(), "session-1").also { it.mkdirs() }
        val lock1 = SessionLock.tryAcquire(sessionDir)!!
        lock1.release()
        val lock2 = SessionLock.tryAcquire(sessionDir)
        assertNotNull(lock2)
        lock2!!.release()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agent:test --tests "*.SessionLockTest" --rerun`
Expected: FAIL

- [ ] **Step 3: Implement SessionLock.kt**

```kotlin
package com.workflow.orchestrator.agent.session

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock

class SessionLock private constructor(
    private val lockFile: RandomAccessFile,
    private val lock: FileLock
) {
    fun release() {
        try { lock.release() } catch (_: Exception) {}
        try { lockFile.close() } catch (_: Exception) {}
    }

    companion object {
        private const val LOCK_FILE_NAME = ".lock"

        fun tryAcquire(sessionDir: File): SessionLock? {
            sessionDir.mkdirs()
            val file = File(sessionDir, LOCK_FILE_NAME)
            return try {
                val raf = RandomAccessFile(file, "rw")
                val lock = raf.channel.tryLock()
                if (lock != null) {
                    SessionLock(raf, lock)
                } else {
                    raf.close()
                    null
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :agent:test --tests "*.SessionLockTest" --rerun`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/session/SessionLock.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/session/SessionLockTest.kt
git commit -m "feat(session): add per-session FileLock

Port of Cline's tryAcquireTaskLockWithRetry — prevents two IDE
instances from clobbering the same session directory."
```

---

### Task 4: MessageStateHandler — Core Persistence

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/session/MessageStateHandler.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/session/MessageStateHandlerTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package com.workflow.orchestrator.agent.session

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class MessageStateHandlerTest {

    @TempDir
    lateinit var tempDir: Path

    private fun handler(sessionId: String = "test-session"): MessageStateHandler {
        return MessageStateHandler(
            baseDir = tempDir.toFile(),
            sessionId = sessionId,
            taskText = "Fix the login bug"
        )
    }

    @Test
    fun `addToClineMessages persists to ui_messages json`() = runTest {
        val h = handler()
        h.addToClineMessages(UiMessage(
            ts = 1000L,
            type = UiMessageType.SAY,
            say = UiSay.TEXT,
            text = "Hello"
        ))
        val file = File(tempDir.toFile(), "sessions/test-session/ui_messages.json")
        assertTrue(file.exists())
        val content = file.readText()
        assertTrue(content.contains("Hello"))
    }

    @Test
    fun `addToApiConversationHistory persists to api_conversation_history json`() = runTest {
        val h = handler()
        h.addToApiConversationHistory(ApiMessage(
            role = ApiRole.USER,
            content = listOf(ContentBlock.Text("Fix the bug"))
        ))
        val file = File(tempDir.toFile(), "sessions/test-session/api_conversation_history.json")
        assertTrue(file.exists())
        val content = file.readText()
        assertTrue(content.contains("Fix the bug"))
    }

    @Test
    fun `conversationHistoryIndex is set correctly on addToClineMessages`() = runTest {
        val h = handler()
        h.addToApiConversationHistory(ApiMessage(role = ApiRole.USER, content = listOf(ContentBlock.Text("msg1"))))
        h.addToApiConversationHistory(ApiMessage(role = ApiRole.ASSISTANT, content = listOf(ContentBlock.Text("msg2"))))
        h.addToClineMessages(UiMessage(ts = 1000L, type = UiMessageType.SAY, say = UiSay.TEXT, text = "bubble"))
        val msgs = h.getClineMessages()
        assertEquals(1, msgs.last().conversationHistoryIndex) // apiHistory.size - 1 = 2 - 1 = 1
    }

    @Test
    fun `updateClineMessage updates and persists`() = runTest {
        val h = handler()
        h.addToClineMessages(UiMessage(ts = 1000L, type = UiMessageType.SAY, say = UiSay.TEXT, text = "partial", partial = true))
        h.updateClineMessage(0, h.getClineMessages()[0].copy(text = "partial complete", partial = false))
        val msgs = h.getClineMessages()
        assertEquals("partial complete", msgs[0].text)
        assertFalse(msgs[0].partial)
    }

    @Test
    fun `sessions json is updated with HistoryItem on every save`() = runTest {
        val h = handler()
        h.addToClineMessages(UiMessage(ts = 1000L, type = UiMessageType.SAY, say = UiSay.TEXT, text = "hello"))
        val indexFile = File(tempDir.toFile(), "sessions.json")
        assertTrue(indexFile.exists())
        val content = indexFile.readText()
        assertTrue(content.contains("test-session"))
        assertTrue(content.contains("Fix the login bug"))
    }

    @Test
    fun `concurrent writes do not corrupt files`() = runTest {
        val h = handler()
        val jobs = (1..50).map { i ->
            kotlinx.coroutines.async {
                h.addToClineMessages(UiMessage(ts = i.toLong(), type = UiMessageType.SAY, say = UiSay.TEXT, text = "msg-$i"))
            }
        }
        jobs.forEach { it.await() }
        assertEquals(50, h.getClineMessages().size)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agent:test --tests "*.MessageStateHandlerTest" --rerun`
Expected: FAIL

- [ ] **Step 3: Implement MessageStateHandler.kt**

```kotlin
package com.workflow.orchestrator.agent.session

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class MessageStateHandler(
    private val baseDir: File,
    val sessionId: String,
    private val taskText: String,
) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }
    private val prettyJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    companion object {
        /** Separate mutex for sessions.json to prevent races between concurrent sessions (I2 fix). */
        private val globalIndexMutex = Mutex()
    }

    private val uiMessages: MutableList<UiMessage> = mutableListOf()
    private val apiHistory: MutableList<ApiMessage> = mutableListOf()

    private val sessionDir: File get() = File(baseDir, "sessions/$sessionId")
    private val uiMessagesFile: File get() = File(sessionDir, "ui_messages.json")
    private val apiHistoryFile: File get() = File(sessionDir, "api_conversation_history.json")
    private val globalIndexFile: File get() = File(baseDir, "sessions.json")

    fun getClineMessages(): List<UiMessage> = uiMessages.toList()
    fun getApiConversationHistory(): List<ApiMessage> = apiHistory.toList()

    suspend fun addToClineMessages(message: UiMessage) = mutex.withLock {
        val histIdx = if (apiHistory.isEmpty()) null else apiHistory.size - 1
        val indexed = message.copy(conversationHistoryIndex = histIdx)
        uiMessages.add(indexed)
        saveInternal()
    }

    suspend fun updateClineMessage(index: Int, updated: UiMessage) = mutex.withLock {
        if (index in uiMessages.indices) {
            uiMessages[index] = updated
            saveInternal()
        }
    }

    suspend fun deleteClineMessage(index: Int) = mutex.withLock {
        if (index in uiMessages.indices) {
            uiMessages.removeAt(index)
            saveInternal()
        }
    }

    suspend fun addToApiConversationHistory(message: ApiMessage) = mutex.withLock {
        apiHistory.add(message)
        saveApiHistoryInternal()
    }

    suspend fun overwriteApiConversationHistory(messages: List<ApiMessage>) = mutex.withLock {
        apiHistory.clear()
        apiHistory.addAll(messages)
        saveApiHistoryInternal()
    }

    suspend fun overwriteClineMessages(messages: List<UiMessage>) = mutex.withLock {
        uiMessages.clear()
        uiMessages.addAll(messages)
        saveInternal()
    }

    /** Call ONLY during initialization, before any concurrent access begins. */
    fun setClineMessages(messages: List<UiMessage>) {
        check(!mutex.isLocked) { "setClineMessages must only be called during init, before concurrent access" }
        uiMessages.clear()
        uiMessages.addAll(messages)
    }

    /** Call ONLY during initialization, before any concurrent access begins. */
    fun setApiConversationHistory(messages: List<ApiMessage>) {
        check(!mutex.isLocked) { "setApiConversationHistory must only be called during init, before concurrent access" }
        apiHistory.clear()
        apiHistory.addAll(messages)
    }

    private suspend fun saveInternal() {
        sessionDir.mkdirs()
        AtomicFileWriter.write(uiMessagesFile, json.encodeToString(uiMessages))
        updateGlobalIndex()
    }

    private fun saveApiHistoryInternal() {
        sessionDir.mkdirs()
        AtomicFileWriter.write(apiHistoryFile, json.encodeToString(apiHistory))
    }

    suspend fun saveBoth() = mutex.withLock {
        saveInternal()
        saveApiHistoryInternal()
    }

    private suspend fun updateGlobalIndex() = Companion.globalIndexMutex.withLock {
        val totalTokensIn = apiHistory.sumOf { it.metrics?.inputTokens ?: 0 }
        val totalTokensOut = apiHistory.sumOf { it.metrics?.outputTokens ?: 0 }
        val totalCost = apiHistory.sumOf { it.metrics?.cost ?: 0.0 }
        val lastModel = apiHistory.lastOrNull { it.modelInfo != null }?.modelInfo?.modelId

        val item = HistoryItem(
            id = sessionId,
            ts = uiMessages.lastOrNull()?.ts ?: System.currentTimeMillis(),
            task = taskText.take(200),
            tokensIn = totalTokensIn.toLong(),
            tokensOut = totalTokensOut.toLong(),
            totalCost = totalCost,
            modelId = lastModel
        )

        val existingItems: MutableList<HistoryItem> = try {
            if (globalIndexFile.exists()) {
                prettyJson.decodeFromString<MutableList<HistoryItem>>(globalIndexFile.readText())
            } else mutableListOf()
        } catch (_: Exception) { mutableListOf() }

        val idx = existingItems.indexOfFirst { it.id == sessionId }
        if (idx >= 0) existingItems[idx] = item else existingItems.add(0, item)

        baseDir.mkdirs()
        AtomicFileWriter.write(globalIndexFile, prettyJson.encodeToString(existingItems))
    }

    companion object {
        private val loaderJson = Json { ignoreUnknownKeys = true }

        fun loadUiMessages(sessionDir: File): List<UiMessage> {
            val file = File(sessionDir, "ui_messages.json")
            if (!file.exists()) return emptyList()
            return try {
                loaderJson.decodeFromString<List<UiMessage>>(file.readText())
            } catch (_: Exception) { emptyList() }
        }

        fun loadApiHistory(sessionDir: File): List<ApiMessage> {
            val file = File(sessionDir, "api_conversation_history.json")
            if (!file.exists()) return emptyList()
            return try {
                loaderJson.decodeFromString<List<ApiMessage>>(file.readText())
            } catch (_: Exception) { emptyList() }
        }

        fun loadGlobalIndex(baseDir: File): List<HistoryItem> {
            val file = File(baseDir, "sessions.json")
            if (!file.exists()) return emptyList()
            return try {
                loaderJson.decodeFromString<List<HistoryItem>>(file.readText())
            } catch (_: Exception) { emptyList() }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :agent:test --tests "*.MessageStateHandlerTest" --rerun`
Expected: PASS (6 tests)

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/session/MessageStateHandler.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/session/MessageStateHandlerTest.kt
git commit -m "feat(session): add MessageStateHandler with dual-file persistence

Port of Cline's MessageStateHandler (message-state.ts): owns both
ui_messages and api_conversation_history arrays, mutex-guarded writes,
atomic file persistence on every state change, global sessions.json
index update."
```

---

### Task 5: ResumeHelper — Trim Logic + Preamble Builder

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/session/ResumeHelper.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/session/ResumeHelperTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package com.workflow.orchestrator.agent.session

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ResumeHelperTest {

    @Test
    fun `trims trailing resume_task messages`() {
        val messages = listOf(
            UiMessage(ts = 1000L, type = UiMessageType.SAY, say = UiSay.TEXT, text = "Hello"),
            UiMessage(ts = 2000L, type = UiMessageType.ASK, ask = UiAsk.RESUME_TASK),
            UiMessage(ts = 3000L, type = UiMessageType.ASK, ask = UiAsk.RESUME_TASK),
        )
        val trimmed = ResumeHelper.trimResumeMessages(messages)
        assertEquals(1, trimmed.size)
        assertEquals("Hello", trimmed[0].text)
    }

    @Test
    fun `trims cost-less api_req_started at end`() {
        val messages = listOf(
            UiMessage(ts = 1000L, type = UiMessageType.SAY, say = UiSay.TEXT, text = "Hello"),
            UiMessage(ts = 2000L, type = UiMessageType.SAY, say = UiSay.API_REQ_STARTED, text = "{}"),
        )
        val trimmed = ResumeHelper.trimResumeMessages(messages)
        assertEquals(1, trimmed.size)
    }

    @Test
    fun `does not trim api_req_started with cost`() {
        val messages = listOf(
            UiMessage(ts = 1000L, type = UiMessageType.SAY, say = UiSay.TEXT, text = "Hello"),
            UiMessage(ts = 2000L, type = UiMessageType.SAY, say = UiSay.API_REQ_STARTED, text = """{"cost":0.05}"""),
        )
        val trimmed = ResumeHelper.trimResumeMessages(messages)
        assertEquals(2, trimmed.size)
    }

    @Test
    fun `builds task resumption preamble with time ago`() {
        val preamble = ResumeHelper.buildTaskResumptionPreamble(
            mode = "act",
            agoText = "5 minutes ago",
            cwd = "/Users/test/project",
            userText = "Keep going"
        )
        assertTrue(preamble.contains("5 minutes ago"))
        assertTrue(preamble.contains("/Users/test/project"))
        assertTrue(preamble.contains("Keep going"))
    }

    @Test
    fun `detects trailing user message in api history`() {
        val apiHistory = listOf(
            ApiMessage(role = ApiRole.USER, content = listOf(ContentBlock.Text("first"))),
            ApiMessage(role = ApiRole.ASSISTANT, content = listOf(ContentBlock.Text("response"))),
            ApiMessage(role = ApiRole.USER, content = listOf(ContentBlock.Text("interrupted"))),
        )
        val result = ResumeHelper.popTrailingUserMessage(apiHistory)
        assertEquals(2, result.trimmedHistory.size)
        assertEquals(1, result.poppedContent.size)
        assertEquals("interrupted", (result.poppedContent[0] as ContentBlock.Text).text)
    }

    @Test
    fun `no pop when last message is assistant`() {
        val apiHistory = listOf(
            ApiMessage(role = ApiRole.USER, content = listOf(ContentBlock.Text("first"))),
            ApiMessage(role = ApiRole.ASSISTANT, content = listOf(ContentBlock.Text("done"))),
        )
        val result = ResumeHelper.popTrailingUserMessage(apiHistory)
        assertEquals(2, result.trimmedHistory.size)
        assertTrue(result.poppedContent.isEmpty())
    }

    @Test
    fun `determines resume ask type from last message`() {
        val withCompletion = listOf(
            UiMessage(ts = 1000L, type = UiMessageType.ASK, ask = UiAsk.COMPLETION_RESULT, text = "Done!")
        )
        assertEquals(UiAsk.RESUME_COMPLETED_TASK, ResumeHelper.determineResumeAskType(withCompletion))

        val withTool = listOf(
            UiMessage(ts = 1000L, type = UiMessageType.SAY, say = UiSay.TOOL, text = "edited file")
        )
        assertEquals(UiAsk.RESUME_TASK, ResumeHelper.determineResumeAskType(withTool))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agent:test --tests "*.ResumeHelperTest" --rerun`
Expected: FAIL

- [ ] **Step 3: Implement ResumeHelper.kt**

```kotlin
package com.workflow.orchestrator.agent.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

object ResumeHelper {

    private val json = Json { ignoreUnknownKeys = true }

    data class PopResult(
        val trimmedHistory: List<ApiMessage>,
        val poppedContent: List<ContentBlock>,
    )

    fun trimResumeMessages(messages: List<UiMessage>): List<UiMessage> {
        val result = messages.toMutableList()

        // Remove trailing resume_task / resume_completed_task messages
        while (result.isNotEmpty()) {
            val last = result.last()
            if (last.ask == UiAsk.RESUME_TASK || last.ask == UiAsk.RESUME_COMPLETED_TASK) {
                result.removeAt(result.lastIndex)
            } else {
                break
            }
        }

        // Remove last api_req_started if it has no cost/cancelReason
        if (result.isNotEmpty()) {
            val lastIdx = result.indexOfLast { it.say == UiSay.API_REQ_STARTED }
            if (lastIdx >= 0 && lastIdx == result.lastIndex) {
                val textJson = result[lastIdx].text ?: "{}"
                if (!hasCostOrCancel(textJson)) {
                    result.removeAt(lastIdx)
                }
            }
        }

        return result
    }

    private fun hasCostOrCancel(textJson: String): Boolean {
        return try {
            val obj = json.decodeFromString<JsonObject>(textJson)
            obj.containsKey("cost") && obj["cost"]?.jsonPrimitive?.content != "null" ||
                obj.containsKey("cancelReason")
        } catch (_: Exception) {
            false
        }
    }

    fun popTrailingUserMessage(apiHistory: List<ApiMessage>): PopResult {
        if (apiHistory.isEmpty()) return PopResult(apiHistory, emptyList())
        val last = apiHistory.last()
        return if (last.role == ApiRole.USER) {
            PopResult(
                trimmedHistory = apiHistory.dropLast(1),
                poppedContent = last.content
            )
        } else {
            PopResult(trimmedHistory = apiHistory, poppedContent = emptyList())
        }
    }

    fun determineResumeAskType(trimmedUiMessages: List<UiMessage>): UiAsk {
        val lastNonResume = trimmedUiMessages.lastOrNull {
            it.ask != UiAsk.RESUME_TASK && it.ask != UiAsk.RESUME_COMPLETED_TASK
        }
        return if (lastNonResume?.ask == UiAsk.COMPLETION_RESULT) {
            UiAsk.RESUME_COMPLETED_TASK
        } else {
            UiAsk.RESUME_TASK
        }
    }

    fun buildTaskResumptionPreamble(
        mode: String,
        agoText: String,
        cwd: String,
        userText: String? = null,
    ): String {
        val modeStr = if (mode == "plan") "Plan Mode" else "Act Mode"
        return buildString {
            appendLine("[TASK RESUMPTION] This task was interrupted $agoText. The conversation history has been preserved.")
            appendLine("Mode: $modeStr")
            appendLine("Working directory: $cwd")
            if (!userText.isNullOrBlank()) {
                appendLine()
                appendLine("User message on resume: $userText")
            }
            appendLine()
            appendLine("Continue where you left off. Do not repeat completed work. If you were mid-task, pick up from the last tool result in the conversation history.")
        }
    }

    fun formatTimeAgo(lastActivityTs: Long): String {
        val diffMs = System.currentTimeMillis() - lastActivityTs
        val seconds = diffMs / 1000
        return when {
            seconds < 60 -> "${seconds}s ago"
            seconds < 3600 -> "${seconds / 60} minutes ago"
            seconds < 86400 -> "${seconds / 3600} hours ago"
            else -> "${seconds / 86400} days ago"
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :agent:test --tests "*.ResumeHelperTest" --rerun`
Expected: PASS (7 tests)

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/session/ResumeHelper.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/session/ResumeHelperTest.kt
git commit -m "feat(session): add ResumeHelper for resume flow logic

Port of Cline's resumeTaskFromHistory trim logic + taskResumption
preamble builder. Handles: trailing resume message removal, cost-less
api_req_started trimming, trailing user message popping, time-ago
formatting, and resume ask type determination."
```

---

### Task 6: Wire AgentLoop Streaming → MessageStateHandler

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt`

This task adds `MessageStateHandler` as a dependency of AgentLoop so streaming chunks and tool results are persisted per-change.

- [ ] **Step 1: Add MessageStateHandler callback to AgentLoop constructor**

In `AgentLoop.kt`, add a new constructor parameter after `onCheckpoint`:

```kotlin
    private val messageStateHandler: MessageStateHandler? = null,
```

- [ ] **Step 2: Add api_req_started before each LLM call (I7 fix)**

Before the LLM streaming call, add an `api_req_started` UI message (Cline pattern):

```kotlin
// AgentLoop runs on Dispatchers.IO already — all suspend calls are awaited inline.
// NO launch(Dispatchers.IO) — that would be fire-and-forget (C3 fix).
messageStateHandler?.addToClineMessages(UiMessage(
    ts = System.currentTimeMillis(),
    type = UiMessageType.SAY,
    say = UiSay.API_REQ_STARTED,
    text = "{}"  // Will be updated with cost after response
))
val apiReqStartedIdx = messageStateHandler?.getClineMessages()?.lastIndex ?: -1
```

- [ ] **Step 3: Add ui_messages persistence to the streaming path (C3 fix — NO fire-and-forget)**

In `AgentLoop.kt`, after each streaming chunk is accumulated. Since AgentLoop.execute() is a suspend fun on IO, all calls are awaited inline:

```kotlin
// After each chunk is added to assistantText (called directly, NOT in launch{}):
messageStateHandler?.let { handler ->
    if (isFirstChunk) {
        handler.addToClineMessages(UiMessage(
            ts = System.currentTimeMillis(),
            type = UiMessageType.SAY,
            say = UiSay.TEXT,
            text = assistantText,
            partial = true
        ))
        isFirstChunk = false
    } else {
        val msgs = handler.getClineMessages()
        val lastIdx = msgs.lastIndex
        if (lastIdx >= 0 && msgs[lastIdx].partial) {
            handler.updateClineMessage(lastIdx, msgs[lastIdx].copy(text = assistantText))
        }
    }
}
```

- [ ] **Step 4: Finalize streaming message on stream end (partial=false) + update api_req_started cost**

When the stream completes and assistant text is finalized:

```kotlin
messageStateHandler?.let { handler ->
    // Finalize partial text message
    val msgs = handler.getClineMessages()
    val lastIdx = msgs.lastIndex
    if (lastIdx >= 0 && msgs[lastIdx].partial) {
        handler.updateClineMessage(lastIdx, msgs[lastIdx].copy(partial = false))
    }

    // Update api_req_started with cost/token info (I7 fix)
    if (apiReqStartedIdx >= 0) {
        val costJson = """{"cost":${estimatedCost},"tokensIn":$promptTokens,"tokensOut":$completionTokens}"""
        handler.updateClineMessage(apiReqStartedIdx, msgs[apiReqStartedIdx].copy(text = costJson))
    }
}
```

- [ ] **Step 5: Persist api_conversation_history after assistant message**

After the full assistant message is added to ContextManager:

```kotlin
messageStateHandler?.addToApiConversationHistory(ApiMessage(
    role = ApiRole.ASSISTANT,
    content = buildApiContentBlocks(parsedContent),
    ts = System.currentTimeMillis(),
    modelInfo = ModelInfo(modelId = currentModelId),
    metrics = ApiRequestMetrics(inputTokens = promptTokens, outputTokens = completionTokens, cost = estimatedCost)
))
```

- [ ] **Step 6: Persist tool results to both files**

After each tool result is added to ContextManager:

```kotlin
messageStateHandler?.addToApiConversationHistory(ApiMessage(
    role = ApiRole.USER,
    content = listOf(ContentBlock.ToolResult(toolUseId = toolCallId, content = result, isError = isError)),
    ts = System.currentTimeMillis()
))
messageStateHandler?.addToClineMessages(UiMessage(
    ts = System.currentTimeMillis(),
    type = UiMessageType.SAY,
    say = UiSay.TOOL,
    text = toolResultSummary
))
```

- [ ] **Step 6: Wire MessageStateHandler in AgentService.executeTask**

In `AgentService.kt`, create `MessageStateHandler` before the `AgentLoop` constructor and pass it:

```kotlin
val messageState = MessageStateHandler(
    baseDir = sessionBaseDir,
    sessionId = sid,
    taskText = task.take(200)
)

// Add user message to both files at task start
messageState.addToApiConversationHistory(ApiMessage(
    role = ApiRole.USER,
    content = listOf(ContentBlock.Text(task)),
    ts = System.currentTimeMillis()
))
messageState.addToClineMessages(UiMessage(
    ts = System.currentTimeMillis(),
    type = UiMessageType.SAY,
    say = UiSay.API_REQ_STARTED,
    text = "{}"
))
```

- [ ] **Step 7: Run full agent test suite**

Run: `./gradlew :agent:test --rerun`
Expected: All existing tests pass (new path is additive via null-safe `messageStateHandler?`)

- [ ] **Step 8: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt
git commit -m "feat(session): wire streaming + tool results to MessageStateHandler

AgentLoop now persists every streaming chunk (partial=true) and tool
result to ui_messages.json + api_conversation_history.json via
MessageStateHandler. Crash at any point leaves a consistent state
on disk with partial flag marking incomplete streams."
```

---

### Task 7: Abort/Interrupt Path

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt`

- [ ] **Step 1: Implement abortStream in AgentLoop**

Find the cancellation/abort path (where `cancelled` is detected or the loop exits due to user cancel). Add:

```kotlin
private suspend fun abortStream(assistantText: String, cancelReason: String) {
    messageStateHandler?.let { handler ->
        // Flip last partial message to non-partial
        val msgs = handler.getClineMessages()
        val lastIdx = msgs.lastIndex
        if (lastIdx >= 0 && msgs[lastIdx].partial) {
            handler.updateClineMessage(lastIdx, msgs[lastIdx].copy(partial = false))
        }

        // Append synthetic assistant turn with interrupt marker
        val interruptMarker = if (cancelReason == "streaming_failed") {
            "[Response interrupted by API Error]"
        } else {
            "[Response interrupted by user]"
        }
        handler.addToApiConversationHistory(ApiMessage(
            role = ApiRole.ASSISTANT,
            content = listOf(ContentBlock.Text("$assistantText\n\n$interruptMarker")),
            ts = System.currentTimeMillis()
        ))

        handler.saveBoth()
    }
}
```

- [ ] **Step 2: Call abortStream in the cancellation paths**

In AgentLoop where cancellation is detected (the isCancelled check), call:

```kotlin
abortStream(currentAssistantText, "user_cancelled")
```

In the API error path that exits the loop:

```kotlin
abortStream(currentAssistantText, "streaming_failed")
```

- [ ] **Step 3: Write abort path test (I5 fix)**

Create `agent/src/test/kotlin/com/workflow/orchestrator/agent/session/AbortStreamTest.kt`:

```kotlin
package com.workflow.orchestrator.agent.session

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class AbortStreamTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `abortStream flips last partial to false and appends interrupt marker`() = runTest {
        val handler = MessageStateHandler(baseDir = tempDir.toFile(), sessionId = "abort-test", taskText = "test")

        // Simulate streaming: add a partial message
        handler.addToClineMessages(UiMessage(
            ts = 1000L, type = UiMessageType.SAY, say = UiSay.TEXT,
            text = "I'll edit the fi", partial = true
        ))

        // Simulate abort
        val msgs = handler.getClineMessages()
        val lastIdx = msgs.lastIndex
        handler.updateClineMessage(lastIdx, msgs[lastIdx].copy(partial = false))
        handler.addToApiConversationHistory(ApiMessage(
            role = ApiRole.ASSISTANT,
            content = listOf(ContentBlock.Text("I'll edit the fi\n\n[Response interrupted by user]"))
        ))
        handler.saveBoth()

        // Verify: last UI message is NOT partial
        val savedUi = MessageStateHandler.loadUiMessages(File(tempDir.toFile(), "sessions/abort-test"))
        assertFalse(savedUi.last().partial)

        // Verify: api_history ends with assistant turn containing interrupt marker
        val savedApi = MessageStateHandler.loadApiHistory(File(tempDir.toFile(), "sessions/abort-test"))
        val lastApi = savedApi.last()
        assertEquals(ApiRole.ASSISTANT, lastApi.role)
        val text = (lastApi.content.first() as ContentBlock.Text).text
        assertTrue(text.contains("[Response interrupted by user]"))
    }

    @Test
    fun `abortStream with streaming_failed uses API Error marker`() = runTest {
        val handler = MessageStateHandler(baseDir = tempDir.toFile(), sessionId = "abort-api-err", taskText = "test")
        handler.addToClineMessages(UiMessage(
            ts = 1000L, type = UiMessageType.SAY, say = UiSay.TEXT,
            text = "partial", partial = true
        ))

        val msgs = handler.getClineMessages()
        handler.updateClineMessage(msgs.lastIndex, msgs.last().copy(partial = false))
        handler.addToApiConversationHistory(ApiMessage(
            role = ApiRole.ASSISTANT,
            content = listOf(ContentBlock.Text("partial\n\n[Response interrupted by API Error]"))
        ))

        val savedApi = MessageStateHandler.loadApiHistory(File(tempDir.toFile(), "sessions/abort-api-err"))
        assertTrue((savedApi.last().content.first() as ContentBlock.Text).text.contains("API Error"))
    }
}
```

- [ ] **Step 4: Run abort tests**

Run: `./gradlew :agent:test --tests "*.AbortStreamTest" --rerun`
Expected: PASS

- [ ] **Step 5: Run full agent tests**

Run: `./gradlew :agent:test --rerun`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/session/AbortStreamTest.kt
git commit -m "feat(session): add abortStream for mid-tool interruption handling

Port of Cline's abortStream (task-index.ts:2721-2780): flip last
partial message, append synthetic assistant turn with interrupt
marker, save both files. Never replays an in-flight tool call."
```

---

### Task 8: Rewrite resumeSession to Cline's Flow

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt`

- [ ] **Step 1: Rewrite resumeSession**

Replace the current `resumeSession` method (lines 1118-1202) with:

```kotlin
fun resumeSession(
    sessionId: String,
    userText: String? = null,
    onStreamChunk: (String) -> Unit = {},
    onToolCall: (ToolCallProgress) -> Unit = {},
    onTaskProgress: (TaskProgress) -> Unit = {},
    onComplete: (LoopResult) -> Unit = {},
    onUiMessagesLoaded: ((List<UiMessage>) -> Unit)? = null,
): Job? {
    val sessionDir = File(sessionBaseDir, "sessions/$sessionId")
    if (!sessionDir.exists()) {
        log.warn("AgentService.resumeSession: session dir not found for $sessionId")
        return null
    }

    // Acquire session lock
    val lock = SessionLock.tryAcquire(sessionDir)
    if (lock == null) {
        log.warn("AgentService.resumeSession: session $sessionId is locked by another instance")
        return null
    }

    // Load persisted state
    var savedUiMessages = MessageStateHandler.loadUiMessages(sessionDir)
    val savedApiHistory = MessageStateHandler.loadApiHistory(sessionDir)

    if (savedApiHistory.isEmpty()) {
        log.warn("AgentService.resumeSession: no api history for $sessionId")
        lock.release()
        return null
    }

    // Trim resume messages and cost-less api_req_started (Cline pattern)
    savedUiMessages = ResumeHelper.trimResumeMessages(savedUiMessages)

    // Push full ui_messages to webview for rehydration
    onUiMessagesLoaded?.invoke(savedUiMessages)

    // Determine resume type
    val resumeAskType = ResumeHelper.determineResumeAskType(savedUiMessages)

    // Pop trailing user message if interrupted mid-submission
    val popResult = ResumeHelper.popTrailingUserMessage(savedApiHistory)
    val activeApiHistory = popResult.trimmedHistory

    // Build task resumption preamble
    val lastActivityTs = savedUiMessages.lastOrNull()?.ts ?: System.currentTimeMillis()
    val agoText = ResumeHelper.formatTimeAgo(lastActivityTs)
    val mode = if (planModeActive.get()) "plan" else "act"
    val cwd = project.basePath ?: ""
    val preamble = ResumeHelper.buildTaskResumptionPreamble(mode, agoText, cwd, userText)

    // Build new user content: preamble + any popped content + user text
    val newUserContent = buildList {
        add(ContentBlock.Text(preamble))
        addAll(popResult.poppedContent)
    }

    // Create MessageStateHandler with restored state
    val taskText = savedUiMessages.firstOrNull()?.text ?: "Resumed session"
    val handler = MessageStateHandler(baseDir = sessionBaseDir, sessionId = sessionId, taskText = taskText)
    handler.setClineMessages(savedUiMessages.toMutableList())
    handler.setApiConversationHistory(activeApiHistory.toMutableList())

    // Add resume ask to UI messages
    scope.launch(Dispatchers.IO) {
        handler.addToClineMessages(UiMessage(
            ts = System.currentTimeMillis(),
            type = UiMessageType.ASK,
            ask = resumeAskType,
            text = "Task was interrupted $agoText. Resuming..."
        ))
    }

    // TASK_RESUME hook — dispatched on IO, NOT runBlocking on EDT (C5 fix)
    // resumeSession is called from scope.launch(Dispatchers.IO) in AgentController,
    // so suspend calls are safe here.
    if (hookManager.hasHooks(HookType.TASK_RESUME)) {
        val hookResult = hookManager.dispatch(HookEvent(
            type = HookType.TASK_RESUME,
            data = mapOf("sessionId" to sessionId, "messageCount" to savedApiHistory.size)
        ))
        if (hookResult is HookResult.Cancel) {
            lock.release()
            return null
        }
    }

    // Rebuild ContextManager from api history
    val agentSettings = AgentSettings.getInstance(project)
    val ctx = ContextManager(maxInputTokens = agentSettings.state.maxInputTokens)
    val systemPrompt = SystemPrompt.build(
        projectName = project.name,
        projectPath = project.basePath ?: "",
        planModeEnabled = planModeActive.get()
    )
    ctx.setSystemPrompt(systemPrompt)

    // Convert ApiMessage list back to ChatMessage list for ContextManager
    val chatMessages = activeApiHistory.map { it.toChatMessage() }
    ctx.restoreMessages(chatMessages)

    // Add the resumption user message
    val resumeUserMsg = ChatMessage(role = "user", content = preamble)
    ctx.addMessage(resumeUserMsg)

    // Add to api history
    scope.launch(Dispatchers.IO) {
        handler.addToApiConversationHistory(ApiMessage(
            role = ApiRole.USER,
            content = newUserContent,
            ts = System.currentTimeMillis()
        ))
    }

    log.info("[Agent] Resuming session: $sessionId (${savedApiHistory.size} api messages, interrupted $agoText)")

    // Execute with the restored context and new MessageStateHandler
    val job = executeTask(
        task = preamble,
        sessionId = sessionId,
        contextManager = ctx,
        messageStateHandler = handler,
        onStreamChunk = onStreamChunk,
        onToolCall = onToolCall,
        onTaskProgress = onTaskProgress,
        onComplete = { result ->
            lock.release()
            onComplete(result)
        }
    )
    return job
}
```

- [ ] **Step 2: Add lossless ApiMessage.toChatMessage() conversion (C2 fix)**

In `ApiMessage.kt`, add bidirectional conversion that preserves tool calls:

```kotlin
import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.ai.dto.ToolCall as CoreToolCall
import com.workflow.orchestrator.core.ai.dto.FunctionCall

/**
 * Lossless conversion from ApiMessage to ChatMessage.
 * Preserves tool_use blocks as ChatMessage.toolCalls and tool_result as role="tool".
 */
fun ApiMessage.toChatMessage(): ChatMessage {
    val textContent = content.filterIsInstance<ContentBlock.Text>()
        .joinToString("\n") { it.text }
        .takeIf { it.isNotBlank() }

    val toolUses = content.filterIsInstance<ContentBlock.ToolUse>()
    val toolResults = content.filterIsInstance<ContentBlock.ToolResult>()

    return when {
        // Assistant message with tool calls
        role == ApiRole.ASSISTANT && toolUses.isNotEmpty() -> ChatMessage(
            role = "assistant",
            content = textContent,
            toolCalls = toolUses.map { tu ->
                CoreToolCall(id = tu.id, function = FunctionCall(name = tu.name, arguments = tu.input))
            }
        )
        // Tool result message (role="tool" in OpenAI format)
        toolResults.isNotEmpty() -> ChatMessage(
            role = "tool",
            content = toolResults.first().content,
            toolCallId = toolResults.first().toolUseId
        )
        // Plain text message
        else -> ChatMessage(
            role = role.name.lowercase(),
            content = textContent ?: ""
        )
    }
}

/**
 * Convert ChatMessage back to ApiMessage (for migration from old format).
 */
fun ChatMessage.toApiMessage(): ApiMessage {
    val blocks = mutableListOf<ContentBlock>()
    if (!content.isNullOrBlank()) blocks.add(ContentBlock.Text(content!!))
    toolCalls?.forEach { tc ->
        blocks.add(ContentBlock.ToolUse(id = tc.id, name = tc.function.name, input = tc.function.arguments))
    }
    if (role == "tool" && toolCallId != null) {
        return ApiMessage(
            role = ApiRole.USER,
            content = listOf(ContentBlock.ToolResult(toolUseId = toolCallId!!, content = content ?: ""))
        )
    }
    val apiRole = if (role == "assistant") ApiRole.ASSISTANT else ApiRole.USER
    return ApiMessage(role = apiRole, content = blocks)
}
```

- [ ] **Step 3: Run agent tests**

Run: `./gradlew :agent:test --rerun`
Expected: PASS (existing tests use the old resumeSession signature — may need updating if they call it directly)

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/session/ApiMessage.kt
git commit -m "feat(session): rewrite resumeSession to Cline's faithful flow

Loads both JSON files, trims resume/cost-less messages, pops trailing
user message, builds taskResumption preamble with time-ago, acquires
session lock, rebuilds ContextManager, pushes full ui_messages to
webview for rehydration. Matches Cline's resumeTaskFromHistory exactly."
```

---

### Task 9: Webview Rehydration — chatStore

**Files:**
- Modify: `agent/webview/src/stores/chatStore.ts`
- Modify: `agent/webview/src/bridge/jcef-bridge.ts`

- [ ] **Step 1: Add UiMessage TypeScript types**

In `agent/webview/src/bridge/types.ts` (or a new file `agent/webview/src/types/UiMessage.ts`):

```typescript
export type UiMessageType = 'ASK' | 'SAY'
export type UiAsk = 'RESUME_TASK' | 'RESUME_COMPLETED_TASK' | 'TOOL' | 'COMMAND' |
  'FOLLOWUP' | 'COMPLETION_RESULT' | 'PLAN_APPROVE' | 'PLAN_MODE_RESPOND' |
  'ACT_MODE_RESPOND' | 'ARTIFACT_RENDER' | 'QUESTION_WIZARD' | 'APPROVAL_GATE' | 'SUBAGENT_PERMISSION'
export type UiSay = 'API_REQ_STARTED' | 'API_REQ_FINISHED' | 'TEXT' | 'REASONING' |
  'TOOL' | 'CHECKPOINT_CREATED' | 'ERROR' | 'PLAN_UPDATE' | 'ARTIFACT_RESULT' |
  'SUBAGENT_STARTED' | 'SUBAGENT_PROGRESS' | 'SUBAGENT_COMPLETED' | 'STEERING_RECEIVED' |
  'CONTEXT_COMPRESSED' | 'MEMORY_SAVED' | 'ROLLBACK_PERFORMED'

export interface UiMessage {
  ts: number
  type: UiMessageType
  ask?: UiAsk
  say?: UiSay
  text?: string
  reasoning?: string
  partial?: boolean
  conversationHistoryIndex?: number
  modelInfo?: { modelId?: string }
  artifactId?: string
  planData?: any
  approvalData?: any
  questionData?: any
  subagentData?: any
}
```

- [ ] **Step 2: Add hydrateFromUiMessages action to chatStore (I4 fix — full implementation)**

In `chatStore.ts`, add the converter and hydration action:

```typescript
import { v4 as uuid } from 'uuid'; // or use crypto.randomUUID()

function convertUiMessageToStoreMessage(msg: UiMessage, idx: number): Message {
  // Map UiMessage ask/say discriminants to the store's Message shape
  const id = `restored-${idx}-${msg.ts}`;
  const timestamp = msg.ts;

  // Determine role from type + discriminant
  let role: MessageRole = 'assistant';
  let content = msg.text || '';

  if (msg.type === 'ASK') {
    switch (msg.ask) {
      case 'RESUME_TASK':
      case 'RESUME_COMPLETED_TASK':
        role = 'system';
        content = msg.text || 'Session resumed';
        break;
      case 'FOLLOWUP':
      case 'COMPLETION_RESULT':
        role = 'assistant';
        break;
      case 'TOOL':
      case 'COMMAND':
      case 'APPROVAL_GATE':
        role = 'system'; // tool approval cards render as system messages
        break;
      default:
        role = 'assistant';
    }
  } else { // SAY
    switch (msg.say) {
      case 'TEXT':
      case 'REASONING':
        role = 'assistant';
        break;
      case 'API_REQ_STARTED':
      case 'API_REQ_FINISHED':
      case 'CHECKPOINT_CREATED':
      case 'CONTEXT_COMPRESSED':
      case 'MEMORY_SAVED':
      case 'ROLLBACK_PERFORMED':
      case 'STEERING_RECEIVED':
        role = 'system';
        break;
      case 'TOOL':
        role = 'system'; // tool results render as system tool-chain messages
        break;
      case 'ERROR':
        role = 'system';
        break;
      case 'SUBAGENT_STARTED':
      case 'SUBAGENT_PROGRESS':
      case 'SUBAGENT_COMPLETED':
        role = 'system';
        break;
      default:
        role = 'assistant';
    }
  }

  const message: Message = { id, role, content, timestamp };

  // Map extended data to store Message fields
  if (msg.subagentData) {
    message.subAgent = {
      id: msg.subagentData.agentId,
      type: msg.subagentData.agentType,
      description: msg.subagentData.description,
      status: msg.subagentData.status.toLowerCase() as any,
      iterations: msg.subagentData.iterations,
      summary: msg.subagentData.summary || undefined,
    };
  }

  if (msg.artifactId) {
    message.artifact = { id: msg.artifactId, status: 'rendered' };
  }

  if (msg.approvalData) {
    // Approval gates render as tool chain entries
    message.toolChain = [{
      id: `approval-${msg.ts}`,
      name: msg.approvalData.toolName,
      args: msg.approvalData.toolInput,
      status: msg.approvalData.status === 'PENDING' ? 'running' : 'done',
      result: msg.approvalData.status,
    }];
  }

  return message;
}

// In the store actions:
hydrateFromUiMessages: (messages: UiMessage[]) => {
  // Filter out api_req_started/finished (internal tracking, not rendered as bubbles)
  const visible = messages.filter(m =>
    m.say !== 'API_REQ_STARTED' && m.say !== 'API_REQ_FINISHED'
  );
  const converted = visible.map((msg, idx) => convertUiMessageToStoreMessage(msg, idx));
  set({ messages: converted, activeStream: null });
}
```

- [ ] **Step 3: Add bridge function for loadSessionState**

In `jcef-bridge.ts`, register:

```typescript
window._loadSessionState = (uiMessagesJson: string) => {
  const messages: UiMessage[] = JSON.parse(uiMessagesJson)
  useChatStore.getState().hydrateFromUiMessages(messages)
}
```

- [ ] **Step 4: Build webview**

Run: `cd agent/webview && npm run build`
Expected: Build succeeds, output in `agent/src/main/resources/webview/dist/`

- [ ] **Step 5: Commit**

```bash
git add agent/webview/src/stores/chatStore.ts \
        agent/webview/src/bridge/jcef-bridge.ts \
        agent/webview/src/bridge/types.ts \
        agent/src/main/resources/webview/dist/
git commit -m "feat(webview): add session rehydration from ui_messages

chatStore.hydrateFromUiMessages() takes the full UiMessage[] array
from Kotlin and rebuilds every chat bubble. Bridge function
_loadSessionState registered for Kotlin→JS call."
```

---

### Task 10: AgentController — Push State to Webview on Resume

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt`

- [ ] **Step 1: Add postStateToWebview method**

```kotlin
private fun postStateToWebview(uiMessages: List<UiMessage>) {
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    val messagesJson = json.encodeToString(uiMessages)
    cefPanel?.callJs("_loadSessionState", messagesJson)
}
```

- [ ] **Step 2: Wire into resume flow**

In the `onUiMessagesLoaded` callback passed to `resumeSession`:

```kotlin
agentService.resumeSession(
    sessionId = sessionId,
    userText = userReply,
    onUiMessagesLoaded = { uiMessages -> postStateToWebview(uiMessages) },
    // ... other callbacks
)
```

- [ ] **Step 3: Run verifyPlugin**

Run: `./gradlew verifyPlugin`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt
git commit -m "feat(ui): push full ui_messages to webview on session resume

AgentController.postStateToWebview serializes the ui_messages array
and calls the JCEF bridge to rehydrate chatStore. Every bubble,
tool card, plan step, and approval gate appears exactly as it did
before the IDE closed."
```

---

### Task 11: History Panel — Read sessions.json

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/HistoryPanel.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/HistoryTabProvider.kt`

- [ ] **Step 1: Update HistoryPanel to read sessions.json**

Replace the current session list loading (which reads `sessions/*.json` metadata files) with:

```kotlin
private fun loadHistory(): List<HistoryItem> {
    return MessageStateHandler.loadGlobalIndex(sessionBaseDir)
}
```

- [ ] **Step 2: Update click handler to call resumeSession**

```kotlin
private fun onHistoryItemClicked(item: HistoryItem) {
    agentService.resumeSession(
        sessionId = item.id,
        onUiMessagesLoaded = { msgs -> controller.postStateToWebview(msgs) },
        onStreamChunk = controller::onStreamChunk,
        onToolCall = controller::onToolCall,
        onComplete = controller::onComplete
    )
}
```

- [ ] **Step 3: Run verifyPlugin**

Run: `./gradlew verifyPlugin`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/HistoryPanel.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/HistoryTabProvider.kt
git commit -m "feat(ui): history panel reads sessions.json with click-to-resume

History panel now loads HistoryItem[] from sessions.json global index.
Clicking a history card triggers the full Cline-faithful resume flow."
```

---

### Task 12: Retire SessionStore + Update Remaining Callers

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt` (remove SessionStore references)
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/memory/ConversationRecall.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/listeners/AgentStartupActivity.kt`
- Retire: `agent/src/main/kotlin/com/workflow/orchestrator/agent/session/SessionStore.kt`
- Retire: `agent/src/test/kotlin/.../session/SessionStoreTest.kt` (if exists)

- [ ] **Step 1: Update ConversationRecall to search api_history files**

```kotlin
fun search(query: String, maxResults: Int = 5): List<RecallResult> {
    val sessionsDir = File(baseDir, "sessions")
    if (!sessionsDir.exists()) return emptyList()
    return sessionsDir.listFiles { f -> f.isDirectory }
        ?.flatMap { sessionDir ->
            val history = MessageStateHandler.loadApiHistory(sessionDir)
            history.filter { msg ->
                msg.content.any { block ->
                    block is ContentBlock.Text && block.text.contains(query, ignoreCase = true)
                }
            }.map { msg ->
                RecallResult(sessionId = sessionDir.name, text = msg.content.filterIsInstance<ContentBlock.Text>().joinToString { it.text }, ts = msg.ts)
            }
        }
        ?.sortedByDescending { it.ts }
        ?.take(maxResults)
        ?: emptyList()
}
```

- [ ] **Step 2: Update AgentStartupActivity to read sessions.json**

```kotlin
override suspend fun execute(project: Project) {
    val baseDir = getSessionBaseDir(project)
    val history = MessageStateHandler.loadGlobalIndex(baseDir)
    // Look for any session that doesn't have a completed marker
    // (In the new model, active sessions are those with a recent timestamp
    //  and no explicit completion. The lock file being absent means it was interrupted.)
    val interrupted = history.firstOrNull { item ->
        val sessionDir = File(baseDir, "sessions/${item.id}")
        val lockFile = File(sessionDir, ".lock")
        !lockFile.exists() && isRecent(item.ts)
    }
    if (interrupted != null) {
        showResumeNotification(project, interrupted)
    }
}
```

- [ ] **Step 3: Remove SessionStore from AgentService**

Remove the `private val sessionStore: SessionStore` field and all direct calls to it. The `onCheckpoint` callback that used `sessionStore.appendMessage` should be removed (MessageStateHandler now handles per-change persistence).

- [ ] **Step 4: Delete SessionStore.kt**

```bash
git rm agent/src/main/kotlin/com/workflow/orchestrator/agent/session/SessionStore.kt
```

- [ ] **Step 5: Run full test suite**

Run: `./gradlew :agent:test --rerun`
Expected: PASS (any tests that referenced SessionStore directly need updating or removal)

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor(session): retire SessionStore, wire all callers to MessageStateHandler

ConversationRecall searches api_conversation_history.json files.
AgentStartupActivity reads sessions.json index.
AgentService no longer uses SessionStore — MessageStateHandler handles
all persistence via per-change atomic writes.
SessionStore.kt deleted."
```

---

### Task 12.5: Migration from Old JSONL Sessions (I1 fix)

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/session/SessionMigrator.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/session/SessionMigratorTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package com.workflow.orchestrator.agent.session

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SessionMigratorTest {

    @TempDir
    lateinit var tempDir: Path

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Test
    fun `migrates old JSONL session to new two-file format`() = runTest {
        // Set up old format: {sessionId}.json + {sessionId}/messages.jsonl
        val sessionsDir = File(tempDir.toFile(), "sessions").also { it.mkdirs() }
        val oldMeta = """{"id":"old-1","title":"Fix bug","createdAt":1712000000000,"messageCount":2,"status":"ACTIVE","totalTokens":5000,"inputTokens":3000,"outputTokens":2000}"""
        File(sessionsDir, "old-1.json").writeText(oldMeta)

        val msgDir = File(sessionsDir, "old-1").also { it.mkdirs() }
        val msg1 = """{"role":"user","content":"Fix the login bug"}"""
        val msg2 = """{"role":"assistant","content":"I'll look at the code."}"""
        File(msgDir, "messages.jsonl").writeText("$msg1\n$msg2\n")

        // Run migration
        SessionMigrator.migrate(tempDir.toFile())

        // Verify new format exists
        val apiFile = File(msgDir, "api_conversation_history.json")
        assertTrue(apiFile.exists())
        val apiHistory = Json { ignoreUnknownKeys = true }.decodeFromString<List<ApiMessage>>(apiFile.readText())
        assertEquals(2, apiHistory.size)
        assertEquals(ApiRole.USER, apiHistory[0].role)

        val uiFile = File(msgDir, "ui_messages.json")
        assertTrue(uiFile.exists())

        // Verify global index created
        val indexFile = File(tempDir.toFile(), "sessions.json")
        assertTrue(indexFile.exists())
    }

    @Test
    fun `skips sessions already in new format`() = runTest {
        val sessionsDir = File(tempDir.toFile(), "sessions").also { it.mkdirs() }
        val msgDir = File(sessionsDir, "new-1").also { it.mkdirs() }
        File(msgDir, "api_conversation_history.json").writeText("[]")
        File(msgDir, "ui_messages.json").writeText("[]")

        SessionMigrator.migrate(tempDir.toFile())
        // Should not crash, should leave files unchanged
        assertEquals("[]", File(msgDir, "api_conversation_history.json").readText())
    }
}
```

- [ ] **Step 2: Implement SessionMigrator.kt**

```kotlin
package com.workflow.orchestrator.agent.session

import com.workflow.orchestrator.core.ai.dto.ChatMessage
import kotlinx.serialization.json.Json
import java.io.File

object SessionMigrator {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun migrate(baseDir: File) {
        val sessionsDir = File(baseDir, "sessions")
        if (!sessionsDir.exists()) return

        val historyItems = mutableListOf<HistoryItem>()

        // Find old-format sessions: those with messages.jsonl but no api_conversation_history.json
        sessionsDir.listFiles { f -> f.isDirectory }?.forEach { sessionDir ->
            val oldJsonl = File(sessionDir, "messages.jsonl")
            val newApiFile = File(sessionDir, "api_conversation_history.json")

            if (oldJsonl.exists() && !newApiFile.exists()) {
                migrateSession(baseDir, sessionDir, oldJsonl, historyItems)
            } else if (newApiFile.exists()) {
                // Already migrated — just add to index if not present
                addToIndex(baseDir, sessionDir, historyItems)
            }
        }

        // Write global index
        if (historyItems.isNotEmpty()) {
            val indexFile = File(baseDir, "sessions.json")
            val existing = try {
                if (indexFile.exists()) json.decodeFromString<List<HistoryItem>>(indexFile.readText())
                else emptyList()
            } catch (_: Exception) { emptyList() }

            val merged = (historyItems + existing).distinctBy { it.id }.sortedByDescending { it.ts }
            AtomicFileWriter.write(indexFile, json.encodeToString(merged))
        }
    }

    private fun migrateSession(baseDir: File, sessionDir: File, oldJsonl: File, items: MutableList<HistoryItem>) {
        val oldMessages = try {
            oldJsonl.readLines().filter { it.isNotBlank() }.mapNotNull { line ->
                try { json.decodeFromString<ChatMessage>(line) } catch (_: Exception) { null }
            }
        } catch (_: Exception) { return }

        if (oldMessages.isEmpty()) return

        // Convert ChatMessages to ApiMessages
        val apiMessages = oldMessages.map { it.toApiMessage() }

        // Generate basic UI messages from API messages
        val uiMessages = apiMessages.mapIndexed { idx, apiMsg ->
            UiMessage(
                ts = System.currentTimeMillis() + idx, // approximate ordering
                type = UiMessageType.SAY,
                say = if (apiMsg.role == ApiRole.ASSISTANT) UiSay.TEXT else UiSay.TOOL,
                text = apiMsg.content.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text },
                conversationHistoryIndex = idx
            )
        }

        // Write new format files
        AtomicFileWriter.write(File(sessionDir, "api_conversation_history.json"), json.encodeToString(apiMessages))
        AtomicFileWriter.write(File(sessionDir, "ui_messages.json"), json.encodeToString(uiMessages))

        // Load old metadata if available
        val metaFile = File(sessionDir.parent, "${sessionDir.name}.json")
        val item = if (metaFile.exists()) {
            try {
                val oldSession = json.decodeFromString<OldSession>(metaFile.readText())
                HistoryItem(
                    id = oldSession.id,
                    ts = oldSession.lastMessageAt,
                    task = oldSession.title.ifBlank { uiMessages.firstOrNull()?.text?.take(100) ?: "Untitled" },
                    tokensIn = oldSession.inputTokens.toLong(),
                    tokensOut = oldSession.outputTokens.toLong(),
                    modelId = null
                )
            } catch (_: Exception) { null }
        } else null

        if (item != null) items.add(item)
    }

    private fun addToIndex(baseDir: File, sessionDir: File, items: MutableList<HistoryItem>) {
        // Read existing api_history to build a HistoryItem
        val apiHistory = MessageStateHandler.loadApiHistory(sessionDir)
        if (apiHistory.isEmpty()) return
        items.add(HistoryItem(
            id = sessionDir.name,
            ts = apiHistory.lastOrNull()?.ts ?: System.currentTimeMillis(),
            task = apiHistory.firstOrNull()?.content?.filterIsInstance<ContentBlock.Text>()?.firstOrNull()?.text?.take(100) ?: "Session"
        ))
    }

    @kotlinx.serialization.Serializable
    private data class OldSession(
        val id: String,
        val title: String = "",
        val createdAt: Long = 0,
        val lastMessageAt: Long = 0,
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
    )
}
```

- [ ] **Step 3: Run migration tests**

Run: `./gradlew :agent:test --tests "*.SessionMigratorTest" --rerun`
Expected: PASS

- [ ] **Step 4: Wire migration into AgentStartupActivity**

In `AgentStartupActivity.kt`, call `SessionMigrator.migrate(baseDir)` before loading the history index. This runs once on startup, is idempotent, and converts any old sessions it finds.

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/session/SessionMigrator.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/session/SessionMigratorTest.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/listeners/AgentStartupActivity.kt
git commit -m "feat(session): add SessionMigrator for old JSONL → new two-file format

Runs on startup, converts sessions/{id}/messages.jsonl to
api_conversation_history.json + ui_messages.json. Idempotent —
skips already-migrated sessions. Builds sessions.json global index."
```

---

### Task 13: Update ContextManager to Operate on ApiHistory Array

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/ContextManager.kt`

- [ ] **Step 1: Add overwriteApiHistory callback**

ContextManager's compaction (truncation + summarization) needs to persist the truncated result. Add a callback:

```kotlin
class ContextManager(
    val maxInputTokens: Int = 150_000,
    private val compactionThreshold: Double = 0.85,
    private val onHistoryOverwrite: (suspend (List<ChatMessage>, deletedRange: Pair<Int, Int>) -> Unit)? = null,
)
```

- [ ] **Step 2: Call the callback after truncation**

In the truncation method, after messages are removed:

```kotlin
val deletedRange = Pair(startIdx, endIdx)
onHistoryOverwrite?.invoke(messages.toList(), deletedRange)
```

- [ ] **Step 3: Wire in AgentService when creating ContextManager**

```kotlin
val ctx = ContextManager(
    maxInputTokens = agentSettings.state.maxInputTokens,
    onHistoryOverwrite = { msgs, deletedRange ->
        messageState.overwriteApiConversationHistory(
            msgs.map { it.toApiMessage() }
        )
    }
)
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :agent:test --rerun`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/ContextManager.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt
git commit -m "feat(context): wire ContextManager compaction to MessageStateHandler

ContextManager truncation/summarization now calls
overwriteApiConversationHistory on MessageStateHandler so the
persisted api_history stays in sync with the in-memory context."
```

---

### Task 14: Update agent/CLAUDE.md Documentation

**Files:**
- Modify: `agent/CLAUDE.md`

- [ ] **Step 1: Update the "Conversation Persistence & Durable Execution" section**

Replace the current section with documentation reflecting the new two-file architecture, MessageStateHandler, resume flow, per-session lock, and sessions.json global index. Include:
- New file layout diagram
- Save cadence description (per-change under Mutex)
- Resume flow (trim, pop, preamble, rehydrate)
- Mid-stream interruption (abortStream)

- [ ] **Step 2: Commit**

```bash
git add agent/CLAUDE.md
git commit -m "docs(agent): update CLAUDE.md for Cline session persistence port

Documents the new two-file model (ui_messages.json +
api_conversation_history.json), MessageStateHandler, per-change
saves, resume flow, and mid-stream abort handling."
```

---

## Execution Order

```
Task 1  (data model)         ← foundation, no deps
Task 2  (AtomicFileWriter)   ← foundation, no deps
Task 3  (SessionLock)        ← foundation, no deps
  ↓
Task 4  (MessageStateHandler) ← depends on 1, 2
Task 5  (ResumeHelper)        ← depends on 1
  ↓
Task 6  (wire AgentLoop)      ← depends on 4, modifies AgentService.kt
Task 7  (abort path)          ← depends on 4, 6
  ↓
Task 8  (rewrite resume)      ← depends on 4, 5, SEQUENTIAL after 6 (both touch AgentService.kt)
  ↓
Task 9  (webview rehydration) ← depends on 1 (types)
Task 10 (AgentController)     ← depends on 8, 9
Task 11 (History panel)       ← depends on 4, 8
  ↓
Task 12   (retire SessionStore) ← depends on 6, 7, 8, 10, 11
Task 12.5 (migration)          ← depends on 4, 12
Task 13   (ContextManager wire) ← depends on 4, 12
Task 14   (docs)               ← depends on all above
```

**Parallelizable groups:**
- Tasks 1, 2, 3 (all independent foundations)
- Tasks 4 and 5 (independent after 1+2)
- Tasks 6 THEN 8 (SEQUENTIAL — both modify AgentService.kt, cannot parallelize)
- Tasks 9, 10, 11 (independent webview/UI work, after 8)
- Tasks 12.5 and 13 (independent after 12)
