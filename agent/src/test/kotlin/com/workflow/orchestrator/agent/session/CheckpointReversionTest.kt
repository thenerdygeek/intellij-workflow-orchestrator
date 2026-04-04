package com.workflow.orchestrator.agent.session

import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.ai.dto.FunctionCall
import com.workflow.orchestrator.core.ai.dto.ToolCall
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Tests for checkpoint reversion in SessionStore.
 * Ported from Cline's checkpoint-based conversation reversion pattern.
 */
class CheckpointReversionTest {

    @TempDir
    lateinit var tempDir: File

    // ── Checkpoint save and load ────────────────────────────────

    @Nested
    inner class SaveAndLoad {

        @Test
        fun `save and load checkpoint roundtrip`() {
            val store = SessionStore(tempDir)
            val messages = listOf(
                ChatMessage(role = "user", content = "Fix the bug"),
                ChatMessage(role = "assistant", content = "Let me read the file."),
                ChatMessage(role = "tool", content = "file content here", toolCallId = "call_1")
            )

            store.saveCheckpoint("sess-1", "cp-1", messages, "After reading file")

            val loaded = store.loadCheckpoint("sess-1", "cp-1")
            assertNotNull(loaded)
            assertEquals(3, loaded!!.size)
            assertEquals("user", loaded[0].role)
            assertEquals("Fix the bug", loaded[0].content)
            assertEquals("assistant", loaded[1].role)
            assertEquals("tool", loaded[2].role)
            assertEquals("call_1", loaded[2].toolCallId)
        }

        @Test
        fun `load nonexistent checkpoint returns null`() {
            val store = SessionStore(tempDir)
            val result = store.loadCheckpoint("sess-1", "nonexistent")
            assertNull(result)
        }

        @Test
        fun `checkpoint preserves tool call structure`() {
            val store = SessionStore(tempDir)
            val messages = listOf(
                ChatMessage(
                    role = "assistant",
                    content = null,
                    toolCalls = listOf(
                        ToolCall(
                            id = "call_edit",
                            type = "function",
                            function = FunctionCall(
                                name = "edit_file",
                                arguments = """{"path":"src/main.kt","old_string":"old","new_string":"new","description":"fix"}"""
                            )
                        )
                    )
                ),
                ChatMessage(role = "tool", content = "Edit applied.", toolCallId = "call_edit")
            )

            store.saveCheckpoint("sess-tc", "cp-tc", messages)

            val loaded = store.loadCheckpoint("sess-tc", "cp-tc")!!
            assertEquals("assistant", loaded[0].role)
            assertNotNull(loaded[0].toolCalls)
            assertEquals("edit_file", loaded[0].toolCalls!![0].function.name)
            assertEquals("call_edit", loaded[1].toolCallId)
        }

        @Test
        fun `multiple checkpoints per session`() {
            val store = SessionStore(tempDir)

            val msg1 = listOf(ChatMessage(role = "user", content = "Step 1"))
            val msg2 = listOf(
                ChatMessage(role = "user", content = "Step 1"),
                ChatMessage(role = "assistant", content = "Done step 1"),
                ChatMessage(role = "user", content = "Step 2")
            )
            val msg3 = listOf(
                ChatMessage(role = "user", content = "Step 1"),
                ChatMessage(role = "assistant", content = "Done step 1"),
                ChatMessage(role = "user", content = "Step 2"),
                ChatMessage(role = "assistant", content = "Done step 2"),
                ChatMessage(role = "user", content = "Step 3")
            )

            store.saveCheckpoint("sess-multi", "cp-1", msg1, "After step 1")
            store.saveCheckpoint("sess-multi", "cp-2", msg2, "After step 2")
            store.saveCheckpoint("sess-multi", "cp-3", msg3, "After step 3")

            assertEquals(1, store.loadCheckpoint("sess-multi", "cp-1")!!.size)
            assertEquals(3, store.loadCheckpoint("sess-multi", "cp-2")!!.size)
            assertEquals(5, store.loadCheckpoint("sess-multi", "cp-3")!!.size)
        }
    }

    // ── List checkpoints ────────────────────────────────

    @Nested
    inner class ListCheckpoints {

        @Test
        fun `list returns checkpoints sorted newest first`() {
            val store = SessionStore(tempDir)

            store.saveCheckpoint("sess-list", "cp-old", listOf(
                ChatMessage(role = "user", content = "old")
            ), "Old checkpoint")
            // Ensure different timestamps
            Thread.sleep(10)
            store.saveCheckpoint("sess-list", "cp-new", listOf(
                ChatMessage(role = "user", content = "new")
            ), "New checkpoint")

            val checkpoints = store.listCheckpoints("sess-list")
            assertEquals(2, checkpoints.size)
            assertEquals("cp-new", checkpoints[0].id)
            assertEquals("cp-old", checkpoints[1].id)
            assertEquals("New checkpoint", checkpoints[0].description)
            assertEquals("Old checkpoint", checkpoints[1].description)
        }

        @Test
        fun `list returns empty for session with no checkpoints`() {
            val store = SessionStore(tempDir)
            val checkpoints = store.listCheckpoints("nonexistent")
            assertTrue(checkpoints.isEmpty())
        }

        @Test
        fun `list includes correct message counts`() {
            val store = SessionStore(tempDir)

            store.saveCheckpoint("sess-counts", "cp-1", listOf(
                ChatMessage(role = "user", content = "msg 1")
            ), "One message")
            store.saveCheckpoint("sess-counts", "cp-2", listOf(
                ChatMessage(role = "user", content = "msg 1"),
                ChatMessage(role = "assistant", content = "msg 2"),
                ChatMessage(role = "user", content = "msg 3")
            ), "Three messages")

            val checkpoints = store.listCheckpoints("sess-counts")
            val cp1 = checkpoints.find { it.id == "cp-1" }!!
            val cp2 = checkpoints.find { it.id == "cp-2" }!!

            assertEquals(1, cp1.messageCount)
            assertEquals(3, cp2.messageCount)
        }
    }

    // ── Revert to checkpoint (discards later checkpoints) ────────

    @Nested
    inner class Reversion {

        @Test
        fun `revert to earlier checkpoint restores correct messages`() {
            val store = SessionStore(tempDir)

            val earlyMessages = listOf(
                ChatMessage(role = "user", content = "Fix the bug"),
                ChatMessage(role = "assistant", content = "I'll read the file first.")
            )
            val laterMessages = listOf(
                ChatMessage(role = "user", content = "Fix the bug"),
                ChatMessage(role = "assistant", content = "I'll read the file first."),
                ChatMessage(role = "user", content = "Also fix the tests"),
                ChatMessage(role = "assistant", content = "Running tests now.")
            )

            store.saveCheckpoint("sess-rv", "cp-early", earlyMessages, "After first read")
            Thread.sleep(10)
            store.saveCheckpoint("sess-rv", "cp-later", laterMessages, "After test fix")

            // Revert to early checkpoint
            val restored = store.loadCheckpoint("sess-rv", "cp-early")
            assertNotNull(restored)
            assertEquals(2, restored!!.size)
            assertEquals("Fix the bug", restored[0].content)
            assertEquals("I'll read the file first.", restored[1].content)
        }

        @Test
        fun `deleteCheckpointsAfter removes later checkpoints`() {
            val store = SessionStore(tempDir)

            store.saveCheckpoint("sess-del", "cp-1", listOf(
                ChatMessage(role = "user", content = "step 1")
            ), "Checkpoint 1")
            Thread.sleep(10)
            store.saveCheckpoint("sess-del", "cp-2", listOf(
                ChatMessage(role = "user", content = "step 2")
            ), "Checkpoint 2")
            Thread.sleep(10)
            store.saveCheckpoint("sess-del", "cp-3", listOf(
                ChatMessage(role = "user", content = "step 3")
            ), "Checkpoint 3")

            assertEquals(3, store.listCheckpoints("sess-del").size)

            // Delete checkpoints after cp-1
            store.deleteCheckpointsAfter("sess-del", "cp-1")

            val remaining = store.listCheckpoints("sess-del")
            assertEquals(1, remaining.size)
            assertEquals("cp-1", remaining[0].id)
        }

        @Test
        fun `deleteCheckpointsAfter keeps the target checkpoint`() {
            val store = SessionStore(tempDir)

            store.saveCheckpoint("sess-keep", "cp-target", listOf(
                ChatMessage(role = "user", content = "target")
            ), "Target")
            Thread.sleep(10)
            store.saveCheckpoint("sess-keep", "cp-after", listOf(
                ChatMessage(role = "user", content = "after")
            ), "After")

            store.deleteCheckpointsAfter("sess-keep", "cp-target")

            // Target should still be loadable
            val target = store.loadCheckpoint("sess-keep", "cp-target")
            assertNotNull(target)
            assertEquals("target", target!![0].content)

            // After should be deleted
            val after = store.loadCheckpoint("sess-keep", "cp-after")
            assertNull(after)
        }

        @Test
        fun `deleteCheckpointsAfter with nonexistent checkpoint is no-op`() {
            val store = SessionStore(tempDir)

            store.saveCheckpoint("sess-noop", "cp-1", listOf(
                ChatMessage(role = "user", content = "exists")
            ), "Exists")

            // Should not throw
            store.deleteCheckpointsAfter("sess-noop", "nonexistent")

            assertEquals(1, store.listCheckpoints("sess-noop").size)
        }
    }

    // ── ContextManager integration ────────────────────────────────

    @Nested
    inner class ContextManagerIntegration {

        @Test
        fun `checkpoint messages can be restored into ContextManager`() {
            val store = SessionStore(tempDir)
            val messages = listOf(
                ChatMessage(role = "user", content = "Original task"),
                ChatMessage(role = "assistant", content = "Working on it."),
                ChatMessage(role = "user", content = "More details please")
            )

            store.saveCheckpoint("sess-ctx", "cp-1", messages)

            // Restore into a fresh ContextManager
            val cm = com.workflow.orchestrator.agent.loop.ContextManager(maxInputTokens = 100_000)
            val loaded = store.loadCheckpoint("sess-ctx", "cp-1")!!
            cm.restoreMessages(loaded)

            val restored = cm.exportMessages()
            assertEquals(3, restored.size)
            assertEquals("Original task", restored[0].content)
            assertEquals("Working on it.", restored[1].content)
            assertEquals("More details please", restored[2].content)
        }

        @Test
        fun `revert discards messages added after checkpoint`() {
            val store = SessionStore(tempDir)

            // Create checkpoint with 2 messages
            val checkpointMessages = listOf(
                ChatMessage(role = "user", content = "Task"),
                ChatMessage(role = "assistant", content = "Starting.")
            )
            store.saveCheckpoint("sess-discard", "cp-1", checkpointMessages)

            // Simulate further work (4 more messages)
            val cm = com.workflow.orchestrator.agent.loop.ContextManager(maxInputTokens = 100_000)
            cm.restoreMessages(checkpointMessages)
            cm.addUserMessage("Do more work")
            cm.addAssistantMessage(ChatMessage(role = "assistant", content = "More work done."))

            assertEquals(4, cm.exportMessages().size)

            // Revert to checkpoint
            val reverted = store.loadCheckpoint("sess-discard", "cp-1")!!
            cm.restoreMessages(reverted)

            assertEquals(2, cm.exportMessages().size)
            assertEquals("Task", cm.exportMessages()[0].content)
            assertEquals("Starting.", cm.exportMessages()[1].content)
        }
    }
}
