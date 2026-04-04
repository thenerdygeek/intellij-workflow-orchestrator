package com.workflow.orchestrator.agent.session

import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.ai.dto.FunctionCall
import com.workflow.orchestrator.core.ai.dto.ToolCall
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SessionStoreTest {

    @TempDir
    lateinit var tempDir: File

    // ── Session metadata persistence ────────────────────────────────

    @Nested
    inner class SessionMetadata {

        @Test
        fun `save and load roundtrip`() {
            val store = SessionStore(tempDir)
            val session = Session(
                id = "sess-001",
                title = "Fix login bug",
                createdAt = 1000L,
                lastMessageAt = 2000L,
                messageCount = 5,
                status = SessionStatus.ACTIVE,
                totalTokens = 1234
            )

            store.save(session)
            val loaded = store.load("sess-001")

            assertNotNull(loaded)
            assertEquals(session.id, loaded!!.id)
            assertEquals(session.title, loaded.title)
            assertEquals(session.createdAt, loaded.createdAt)
            assertEquals(session.lastMessageAt, loaded.lastMessageAt)
            assertEquals(session.messageCount, loaded.messageCount)
            assertEquals(session.status, loaded.status)
            assertEquals(session.totalTokens, loaded.totalTokens)
        }

        @Test
        fun `save and load roundtrip with checkpoint fields`() {
            val store = SessionStore(tempDir)
            val session = Session(
                id = "sess-cp",
                title = "Checkpoint test",
                createdAt = 1000L,
                lastMessageAt = 3000L,
                messageCount = 10,
                status = SessionStatus.ACTIVE,
                totalTokens = 5000,
                systemPrompt = "You are a helpful assistant.",
                planModeEnabled = true,
                lastToolCallId = "call_abc123"
            )

            store.save(session)
            val loaded = store.load("sess-cp")

            assertNotNull(loaded)
            assertEquals("You are a helpful assistant.", loaded!!.systemPrompt)
            assertTrue(loaded.planModeEnabled)
            assertEquals("call_abc123", loaded.lastToolCallId)
        }

        @Test
        fun `list returns sessions sorted by creation time newest first`() {
            val store = SessionStore(tempDir)
            store.save(Session(id = "old", title = "Old", createdAt = 1000L))
            store.save(Session(id = "mid", title = "Mid", createdAt = 2000L))
            store.save(Session(id = "new", title = "New", createdAt = 3000L))

            val list = store.list()

            assertEquals(3, list.size)
            assertEquals("new", list[0].id)
            assertEquals("mid", list[1].id)
            assertEquals("old", list[2].id)
        }

        @Test
        fun `load returns null for missing session`() {
            val store = SessionStore(tempDir)

            val result = store.load("nonexistent")

            assertNull(result)
        }

        @Test
        fun `save same ID twice overwrites`() {
            val store = SessionStore(tempDir)

            store.save(Session(id = "sess-x", title = "Version 1", createdAt = 1000L, messageCount = 1))
            store.save(Session(id = "sess-x", title = "Version 2", createdAt = 1000L, messageCount = 10))

            val loaded = store.load("sess-x")
            assertNotNull(loaded)
            assertEquals("Version 2", loaded!!.title)
            assertEquals(10, loaded.messageCount)

            // list should have only one entry
            assertEquals(1, store.list().size)
        }

        @Test
        fun `ignores unknown fields for forward compatibility`() {
            val store = SessionStore(tempDir)

            // Write a session with extra fields
            val sessionsDir = File(tempDir, "sessions")
            sessionsDir.mkdirs()
            val file = File(sessionsDir, "sess-forward.json")
            file.writeText("""
                {
                  "id": "sess-forward",
                  "title": "Forward compat",
                  "createdAt": 1000,
                  "lastMessageAt": 1000,
                  "messageCount": 0,
                  "status": "ACTIVE",
                  "totalTokens": 0,
                  "systemPrompt": "",
                  "planModeEnabled": false,
                  "lastToolCallId": null,
                  "futureField": "should be ignored"
                }
            """.trimIndent())

            val loaded = store.load("sess-forward")
            assertNotNull(loaded)
            assertEquals("Forward compat", loaded!!.title)
        }
    }

    // ── Message persistence (JSONL) — ported from Cline's api_conversation_history ──

    @Nested
    inner class MessagePersistence {

        @Test
        fun `appendMessage and loadMessages roundtrip`() {
            val store = SessionStore(tempDir)

            store.appendMessage("sess-1", ChatMessage(role = "user", content = "Hello"))
            store.appendMessage("sess-1", ChatMessage(role = "assistant", content = "Hi there"))
            store.appendMessage("sess-1", ChatMessage(role = "user", content = "Fix the bug"))

            val loaded = store.loadMessages("sess-1")

            assertEquals(3, loaded.size)
            assertEquals("user", loaded[0].role)
            assertEquals("Hello", loaded[0].content)
            assertEquals("assistant", loaded[1].role)
            assertEquals("Hi there", loaded[1].content)
            assertEquals("user", loaded[2].role)
            assertEquals("Fix the bug", loaded[2].content)
        }

        @Test
        fun `appendMessage preserves tool call structure`() {
            val store = SessionStore(tempDir)

            val assistantMsg = ChatMessage(
                role = "assistant",
                content = null,
                toolCalls = listOf(
                    ToolCall(
                        id = "call_123",
                        type = "function",
                        function = FunctionCall(
                            name = "read_file",
                            arguments = """{"path":"src/main.kt"}"""
                        )
                    )
                )
            )
            val toolResult = ChatMessage(
                role = "tool",
                content = "fun main() { println(\"hello\") }",
                toolCallId = "call_123"
            )

            store.appendMessage("sess-tc", assistantMsg)
            store.appendMessage("sess-tc", toolResult)

            val loaded = store.loadMessages("sess-tc")

            assertEquals(2, loaded.size)

            // Verify assistant message with tool calls
            val loadedAssistant = loaded[0]
            assertEquals("assistant", loadedAssistant.role)
            assertNull(loadedAssistant.content)
            assertNotNull(loadedAssistant.toolCalls)
            assertEquals(1, loadedAssistant.toolCalls!!.size)
            assertEquals("call_123", loadedAssistant.toolCalls!![0].id)
            assertEquals("read_file", loadedAssistant.toolCalls!![0].function.name)

            // Verify tool result
            val loadedTool = loaded[1]
            assertEquals("tool", loadedTool.role)
            assertEquals("call_123", loadedTool.toolCallId)
            assertTrue(loadedTool.content!!.contains("hello"))
        }

        @Test
        fun `loadMessages returns empty list for missing session`() {
            val store = SessionStore(tempDir)

            val messages = store.loadMessages("nonexistent")

            assertTrue(messages.isEmpty())
        }

        @Test
        fun `saveMessages overwrites existing messages`() {
            val store = SessionStore(tempDir)

            // Initial messages
            store.appendMessage("sess-ow", ChatMessage(role = "user", content = "first"))
            store.appendMessage("sess-ow", ChatMessage(role = "assistant", content = "response"))

            assertEquals(2, store.loadMessages("sess-ow").size)

            // Overwrite with different messages (Cline's overwriteApiConversationHistory)
            store.saveMessages("sess-ow", listOf(
                ChatMessage(role = "user", content = "only this one")
            ))

            val loaded = store.loadMessages("sess-ow")
            assertEquals(1, loaded.size)
            assertEquals("only this one", loaded[0].content)
        }

        @Test
        fun `messageCount returns correct count without loading messages`() {
            val store = SessionStore(tempDir)

            store.appendMessage("sess-count", ChatMessage(role = "user", content = "one"))
            store.appendMessage("sess-count", ChatMessage(role = "assistant", content = "two"))
            store.appendMessage("sess-count", ChatMessage(role = "user", content = "three"))

            assertEquals(3, store.messageCount("sess-count"))
        }

        @Test
        fun `messageCount returns 0 for missing session`() {
            val store = SessionStore(tempDir)

            assertEquals(0, store.messageCount("nonexistent"))
        }

        @Test
        fun `appendMessage is incremental - does not rewrite file`() {
            val store = SessionStore(tempDir)

            store.appendMessage("sess-inc", ChatMessage(role = "user", content = "first"))
            val sizeAfterFirst = File(tempDir, "sessions/sess-inc/messages.jsonl").length()

            store.appendMessage("sess-inc", ChatMessage(role = "assistant", content = "second"))
            val sizeAfterSecond = File(tempDir, "sessions/sess-inc/messages.jsonl").length()

            assertTrue(sizeAfterSecond > sizeAfterFirst,
                "File should grow after append (not be rewritten)")
        }

        @Test
        fun `handles messages with special characters`() {
            val store = SessionStore(tempDir)

            val content = "Line 1\nLine 2\ttabbed\n\"quoted\" and \\escaped\\"
            store.appendMessage("sess-special", ChatMessage(role = "user", content = content))

            val loaded = store.loadMessages("sess-special")
            assertEquals(1, loaded.size)
            assertEquals(content, loaded[0].content)
        }

        @Test
        fun `skips corrupt lines without failing`() {
            val store = SessionStore(tempDir)

            // Write some valid messages, then corrupt data, then more valid
            val dir = File(tempDir, "sessions/sess-corrupt")
            dir.mkdirs()
            val file = File(dir, "messages.jsonl")
            file.writeText(
                """{"role":"user","content":"valid 1"}""" + "\n" +
                "THIS IS NOT JSON\n" +
                """{"role":"assistant","content":"valid 2"}""" + "\n"
            )

            val loaded = store.loadMessages("sess-corrupt")

            assertEquals(2, loaded.size, "Should skip corrupt line and load valid messages")
            assertEquals("valid 1", loaded[0].content)
            assertEquals("valid 2", loaded[1].content)
        }

        @Test
        fun `delete removes both session metadata and messages`() {
            val store = SessionStore(tempDir)

            store.save(Session(id = "sess-del", title = "To delete"))
            store.appendMessage("sess-del", ChatMessage(role = "user", content = "hello"))

            assertNotNull(store.load("sess-del"))
            assertEquals(1, store.loadMessages("sess-del").size)

            store.delete("sess-del")

            assertNull(store.load("sess-del"))
            assertEquals(0, store.loadMessages("sess-del").size)
        }
    }

    // ── Atomic write safety ──────────────────────────────────────────

    @Nested
    inner class AtomicWriteSafety {

        @Test
        fun `save uses atomic write - no temp file left behind on success`() {
            val store = SessionStore(tempDir)
            val session = Session(id = "atomic-1", title = "Atomic test", createdAt = 1000L)

            store.save(session)

            // The session file should exist
            val sessionFile = File(tempDir, "sessions/atomic-1.json")
            assertTrue(sessionFile.exists(), "Session file should exist after save")

            // No .tmp file should remain
            val tmpFile = File(tempDir, "sessions/atomic-1.json.tmp")
            assertFalse(tmpFile.exists(), "Temp file should not remain after successful atomic write")

            // Data should be correct
            val loaded = store.load("atomic-1")
            assertNotNull(loaded)
            assertEquals("Atomic test", loaded!!.title)
        }

        @Test
        fun `saveMessages uses atomic write - no temp file left behind`() {
            val store = SessionStore(tempDir)

            store.saveMessages("atomic-2", listOf(
                ChatMessage(role = "user", content = "hello"),
                ChatMessage(role = "assistant", content = "world")
            ))

            // Messages should be readable
            val loaded = store.loadMessages("atomic-2")
            assertEquals(2, loaded.size)

            // No .tmp file should remain
            val tmpFile = File(tempDir, "sessions/atomic-2/messages.jsonl.tmp")
            assertFalse(tmpFile.exists(), "Temp file should not remain after successful atomic write")
        }

        @Test
        fun `saveCheckpoint uses atomic write - no temp files left behind`() {
            val store = SessionStore(tempDir)
            val messages = listOf(ChatMessage(role = "user", content = "checkpoint msg"))

            store.saveCheckpoint("atomic-3", "cp-1", messages, "Test checkpoint")

            // Checkpoint should be loadable
            val loaded = store.loadCheckpoint("atomic-3", "cp-1")
            assertNotNull(loaded)
            assertEquals(1, loaded!!.size)

            // No .tmp files should remain
            val cpTmp = File(tempDir, "sessions/atomic-3/checkpoints/cp-1.jsonl.tmp")
            val metaTmp = File(tempDir, "sessions/atomic-3/checkpoints/cp-1.meta.json.tmp")
            assertFalse(cpTmp.exists(), "Checkpoint JSONL temp file should not remain")
            assertFalse(metaTmp.exists(), "Checkpoint meta temp file should not remain")
        }

        @Test
        fun `atomic write overwrites existing file correctly`() {
            val store = SessionStore(tempDir)

            // First write
            store.save(Session(id = "overwrite-1", title = "Version 1", createdAt = 1000L))
            assertEquals("Version 1", store.load("overwrite-1")!!.title)

            // Overwrite via atomic write
            store.save(Session(id = "overwrite-1", title = "Version 2", createdAt = 1000L))
            assertEquals("Version 2", store.load("overwrite-1")!!.title)

            // No temp files
            val tmpFile = File(tempDir, "sessions/overwrite-1.json.tmp")
            assertFalse(tmpFile.exists())
        }
    }

    // ── Conversation checkpoint simulation ──────────────────────────

    @Nested
    inner class CheckpointSimulation {

        /**
         * Simulates the full Cline checkpoint pattern:
         * 1. Create session
         * 2. Execute tool calls, checkpointing after each
         * 3. "Crash" (discard in-memory state)
         * 4. Resume from checkpoint
         * 5. Verify full conversation is restored
         */
        @Test
        fun `full checkpoint and resume cycle`() {
            val store = SessionStore(tempDir)

            // Phase 1: Initial execution with checkpointing
            val sessionId = "sess-resume"
            store.save(Session(
                id = sessionId,
                title = "Fix the bug",
                status = SessionStatus.ACTIVE,
                systemPrompt = "You are a coding assistant.",
                planModeEnabled = false
            ))

            // Simulate agent loop adding messages and checkpointing
            val messages = listOf(
                ChatMessage(role = "user", content = "Fix the bug in main.kt"),
                ChatMessage(role = "assistant", content = null, toolCalls = listOf(
                    ToolCall(id = "call_1", type = "function",
                        function = FunctionCall("read_file", """{"path":"main.kt"}"""))
                )),
                ChatMessage(role = "tool", content = "fun main() { error() }", toolCallId = "call_1"),
                ChatMessage(role = "assistant", content = null, toolCalls = listOf(
                    ToolCall(id = "call_2", type = "function",
                        function = FunctionCall("edit_file", """{"path":"main.kt","content":"fixed"}"""))
                )),
                ChatMessage(role = "tool", content = "File edited successfully.", toolCallId = "call_2")
            )

            // Checkpoint after each message (matching Cline's per-message save pattern)
            for (msg in messages) {
                store.appendMessage(sessionId, msg)
            }

            // Update session metadata
            store.save(Session(
                id = sessionId,
                title = "Fix the bug",
                status = SessionStatus.ACTIVE,
                messageCount = messages.size,
                systemPrompt = "You are a coding assistant.",
                lastToolCallId = "call_2"
            ))

            // Phase 2: "Crash" — discard all in-memory state
            // (nothing to do, we just stop using the variables)

            // Phase 3: Resume from checkpoint
            val restoredSession = store.load(sessionId)
            val restoredMessages = store.loadMessages(sessionId)

            assertNotNull(restoredSession)
            assertEquals("Fix the bug", restoredSession!!.title)
            assertEquals(SessionStatus.ACTIVE, restoredSession.status)
            assertEquals("You are a coding assistant.", restoredSession.systemPrompt)
            assertEquals("call_2", restoredSession.lastToolCallId)

            assertEquals(5, restoredMessages.size)
            assertEquals("user", restoredMessages[0].role)
            assertEquals("Fix the bug in main.kt", restoredMessages[0].content)
            assertEquals("assistant", restoredMessages[1].role)
            assertEquals("read_file", restoredMessages[1].toolCalls!![0].function.name)
            assertEquals("tool", restoredMessages[2].role)
            assertEquals("call_1", restoredMessages[2].toolCallId)
            assertEquals("tool", restoredMessages[4].role)
            assertEquals("call_2", restoredMessages[4].toolCallId)
        }

        @Test
        fun `incremental checkpoint appends only new messages`() {
            val store = SessionStore(tempDir)
            val sessionId = "sess-incremental"

            // First batch of messages
            store.appendMessage(sessionId, ChatMessage(role = "user", content = "msg 1"))
            store.appendMessage(sessionId, ChatMessage(role = "assistant", content = "reply 1"))

            assertEquals(2, store.messageCount(sessionId))

            // Second batch (agent loop continues, checkpoints new messages only)
            store.appendMessage(sessionId, ChatMessage(role = "user", content = "msg 2"))
            store.appendMessage(sessionId, ChatMessage(role = "assistant", content = "reply 2"))

            assertEquals(4, store.messageCount(sessionId))

            val all = store.loadMessages(sessionId)
            assertEquals(4, all.size)
            assertEquals("msg 1", all[0].content)
            assertEquals("reply 1", all[1].content)
            assertEquals("msg 2", all[2].content)
            assertEquals("reply 2", all[3].content)
        }
    }
}
