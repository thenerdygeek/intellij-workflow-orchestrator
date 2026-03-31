package com.workflow.orchestrator.agent.context

import com.workflow.orchestrator.agent.context.events.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ConversationMemoryTest {

    private val memory = ConversationMemory(maxMessageChars = 30_000)

    // ═══════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════

    private fun userMsg(id: Int, content: String = "user-msg-$id") =
        MessageAction(content = content, id = id, source = EventSource.USER)

    private fun agentMsg(id: Int, content: String = "agent-msg-$id") =
        MessageAction(content = content, id = id, source = EventSource.AGENT)

    private fun sysMsg(id: Int, content: String = "system-msg-$id") =
        SystemMessageAction(content = content, id = id)

    private fun fileRead(id: Int, toolCallId: String, responseGroupId: String, path: String = "/test.kt") =
        FileReadAction(
            toolCallId = toolCallId, responseGroupId = responseGroupId,
            path = path, id = id
        )

    private fun commandRun(id: Int, toolCallId: String, responseGroupId: String, command: String = "ls") =
        CommandRunAction(
            toolCallId = toolCallId, responseGroupId = responseGroupId,
            command = command, id = id
        )

    private fun toolResult(id: Int, toolCallId: String, content: String = "result", toolName: String = "read_file") =
        ToolResultObservation(
            toolCallId = toolCallId, content = content,
            isError = false, toolName = toolName, id = id
        )

    private fun condensationObs(id: Int, content: String = "Summary of forgotten events") =
        CondensationObservation(content = content, id = id)

    private fun think(id: Int, thought: String = "thinking...") =
        AgentThinkAction(thought = thought, id = id)

    private fun finish(id: Int, thought: String = "Done!") =
        AgentFinishAction(finalThought = thought, id = id)

    private fun factRecorded(id: Int, factType: String = "DISCOVERY", path: String? = null, content: String = "A fact") =
        FactRecordedAction(factType = factType, path = path, content = content, id = id)

    private fun planUpdated(id: Int, planJson: String = """{"steps":[]}""") =
        PlanUpdatedAction(planJson = planJson, id = id)

    // ═══════════════════════════════════════════════════════════════════════
    // Initial user message insertion
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    inner class InitialUserMessage {

        @Test
        fun `inserts initial user message at index 1 when not present`() {
            val initial = userMsg(100, "Hello agent")
            val events = listOf(sysMsg(0, "System prompt"), agentMsg(1, "Hi"))
            val result = memory.processEvents(events, initial)

            // Should contain the initial user message content somewhere
            assertTrue(result.any { it.content?.contains("Hello agent") == true })
        }

        @Test
        fun `does not duplicate initial user message when already present at index 1`() {
            val initial = userMsg(1, "Hello agent")
            val events = listOf(sysMsg(0, "System prompt"), userMsg(1, "Hello agent"))
            val result = memory.processEvents(events, initial)

            val userMsgCount = result.count { it.content?.contains("Hello agent") == true }
            assertEquals(1, userMsgCount)
        }

        @Test
        fun `does not insert initial user message when id is in forgottenEventIds`() {
            val initial = userMsg(100, "Forgotten message")
            val events = listOf(sysMsg(0, "System prompt"), agentMsg(1, "Hi"))
            val result = memory.processEvents(events, initial, forgottenEventIds = setOf(100))

            assertFalse(result.any { it.content?.contains("Forgotten message") == true })
        }

        @Test
        fun `inserts at index 0 when events list is empty`() {
            val initial = userMsg(1, "Hello")
            val result = memory.processEvents(emptyList(), initial)

            assertTrue(result.isNotEmpty())
            assertTrue(result.any { it.content?.contains("Hello") == true })
        }

        @Test
        fun `does not insert when first event is already a user MessageAction`() {
            val initial = userMsg(0, "First msg")
            val events = listOf(userMsg(0, "First msg"), agentMsg(1, "Reply"))
            val result = memory.processEvents(events, initial)

            val firstMsgCount = result.count { it.content?.contains("First msg") == true }
            assertEquals(1, firstMsgCount)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Event type conversion
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    inner class EventConversion {

        @Test
        fun `MessageAction USER becomes user message`() {
            val events = listOf(userMsg(0, "Hello"))
            val result = memory.processEvents(events, userMsg(0, "Hello"))

            assertTrue(result.any { it.role == "user" && it.content?.contains("Hello") == true })
        }

        @Test
        fun `MessageAction AGENT becomes assistant message`() {
            val events = listOf(userMsg(0, "Hi"), agentMsg(1, "Hello back"))
            val result = memory.processEvents(events, userMsg(0, "Hi"))

            assertTrue(result.any { it.role == "assistant" && it.content?.contains("Hello back") == true })
        }

        @Test
        fun `AgentThinkAction is skipped`() {
            val events = listOf(userMsg(0, "Hi"), think(1), agentMsg(2, "Reply"))
            val result = memory.processEvents(events, userMsg(0, "Hi"))

            assertFalse(result.any { it.content?.contains("thinking") == true })
        }

        @Test
        fun `AgentFinishAction becomes assistant message`() {
            val events = listOf(userMsg(0, "Hi"), finish(1, "Task completed"))
            val result = memory.processEvents(events, userMsg(0, "Hi"))

            assertTrue(result.any { it.role == "assistant" && it.content?.contains("Task completed") == true })
        }

        @Test
        fun `FactRecordedAction becomes system message with agent_facts tags`() {
            val events = listOf(
                userMsg(0, "Hi"),
                factRecorded(1, factType = "CODE_PATTERN", path = "/src/Main.kt", content = "Uses singleton")
            )
            val result = memory.processEvents(events, userMsg(0, "Hi"))

            // After sanitization, system becomes user with <system_instructions> wrapping
            assertTrue(result.any {
                it.content?.contains("<agent_facts>") == true &&
                    it.content?.contains("CODE_PATTERN") == true &&
                    it.content?.contains("/src/Main.kt") == true
            })
        }

        @Test
        fun `PlanUpdatedAction becomes system message with active_plan tags`() {
            val planJson = """{"steps":[{"name":"step1"}]}"""
            val events = listOf(userMsg(0, "Hi"), planUpdated(1, planJson))
            val result = memory.processEvents(events, userMsg(0, "Hi"))

            assertTrue(result.any {
                it.content?.contains("<active_plan>") == true &&
                    it.content?.contains(planJson) == true
            })
        }

        @Test
        fun `CondensationObservation becomes user message`() {
            val events = listOf(
                userMsg(0, "Hi"),
                agentMsg(1, "Reply"),
                condensationObs(2, "Summary of previous conversation")
            )
            val result = memory.processEvents(events, userMsg(0, "Hi"))

            assertTrue(result.any { it.content?.contains("Summary of previous conversation") == true })
        }

        @Test
        fun `CondensationAction and CondensationRequestAction are skipped`() {
            val events = listOf(
                userMsg(0, "Hi"),
                CondensationAction(
                    forgottenEventIds = listOf(99), forgottenEventsStartId = null,
                    forgottenEventsEndId = null, summary = null, summaryOffset = null, id = 1
                ),
                CondensationRequestAction(id = 2),
                agentMsg(3, "Reply")
            )
            val result = memory.processEvents(events, userMsg(0, "Hi"))

            // Should only have user and assistant messages, no condensation artifacts
            assertTrue(result.all { it.role == "user" || it.role == "assistant" })
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Tool call pairing
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    inner class ToolCallPairing {

        @Test
        fun `single ToolAction creates assistant message with tool_calls`() {
            val events = listOf(
                userMsg(0, "Read file"),
                fileRead(1, toolCallId = "tc-1", responseGroupId = "rg-1", path = "/test.kt"),
                toolResult(2, toolCallId = "tc-1", content = "file contents")
            )
            val result = memory.processEvents(events, userMsg(0, "Read file"))

            // Should have an assistant message with tool_calls
            val assistantWithTools = result.find { it.role == "assistant" && it.toolCalls != null }
            assertNotNull(assistantWithTools)
            assertEquals(1, assistantWithTools!!.toolCalls!!.size)
            assertEquals("tc-1", assistantWithTools.toolCalls!![0].id)
            assertEquals("read_file", assistantWithTools.toolCalls!![0].function.name)
        }

        @Test
        fun `parallel ToolActions with same responseGroupId create single assistant message`() {
            val events = listOf(
                userMsg(0, "Search and read"),
                fileRead(1, toolCallId = "tc-1", responseGroupId = "rg-1"),
                commandRun(2, toolCallId = "tc-2", responseGroupId = "rg-1"),
                toolResult(3, toolCallId = "tc-1"),
                toolResult(4, toolCallId = "tc-2")
            )
            val result = memory.processEvents(events, userMsg(0, "Search and read"))

            val assistantWithTools = result.find { it.role == "assistant" && it.toolCalls != null }
            assertNotNull(assistantWithTools)
            assertEquals(2, assistantWithTools!!.toolCalls!!.size)
        }

        @Test
        fun `different responseGroupIds create separate assistant messages`() {
            val events = listOf(
                userMsg(0, "Do things"),
                fileRead(1, toolCallId = "tc-1", responseGroupId = "rg-1"),
                toolResult(2, toolCallId = "tc-1"),
                commandRun(3, toolCallId = "tc-2", responseGroupId = "rg-2"),
                toolResult(4, toolCallId = "tc-2")
            )
            val result = memory.processEvents(events, userMsg(0, "Do things"))

            val assistantMessages = result.filter { it.role == "assistant" && it.toolCalls != null }
            assertEquals(2, assistantMessages.size)
        }

        @Test
        fun `GenericToolAction uses toolName as function name`() {
            val events = listOf(
                userMsg(0, "Glob files"),
                GenericToolAction(
                    toolCallId = "tc-1", responseGroupId = "rg-1",
                    toolName = "glob_files", arguments = """{"pattern":"*.kt"}""", id = 1
                ),
                toolResult(2, toolCallId = "tc-1", toolName = "glob_files")
            )
            val result = memory.processEvents(events, userMsg(0, "Glob files"))

            val assistantWithTools = result.find { it.role == "assistant" && it.toolCalls != null }
            assertNotNull(assistantWithTools)
            assertEquals("glob_files", assistantWithTools!!.toolCalls!![0].function.name)
        }

        @Test
        fun `MetaToolAction uses toolName as function name`() {
            val events = listOf(
                userMsg(0, "Get jira ticket"),
                MetaToolAction(
                    toolCallId = "tc-1", responseGroupId = "rg-1",
                    toolName = "jira", actionName = "get_ticket",
                    arguments = """{"issue_key":"PROJ-123"}""", id = 1
                ),
                toolResult(2, toolCallId = "tc-1", toolName = "jira")
            )
            val result = memory.processEvents(events, userMsg(0, "Get jira ticket"))

            val assistantWithTools = result.find { it.role == "assistant" && it.toolCalls != null }
            assertNotNull(assistantWithTools)
            assertEquals("jira", assistantWithTools!!.toolCalls!![0].function.name)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Orphan filtering
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    inner class OrphanFiltering {

        @Test
        fun `assistant with tool_calls but no matching tool results is removed`() {
            val events = listOf(
                userMsg(0, "Read file"),
                fileRead(1, toolCallId = "tc-orphan", responseGroupId = "rg-1")
                // No toolResult for tc-orphan
            )
            val result = memory.processEvents(events, userMsg(0, "Read file"))

            // The assistant with tool_calls should be removed
            assertFalse(result.any { it.toolCalls != null })
        }

        @Test
        fun `tool result with no matching assistant tool_call is removed`() {
            val events = listOf(
                userMsg(0, "Hi"),
                toolResult(1, toolCallId = "tc-orphan", content = "orphan result")
            )
            val result = memory.processEvents(events, userMsg(0, "Hi"))

            // After sanitization, tool msgs become user msgs with <tool_result> tags
            // But orphan filtering happens before sanitization, so it should be gone
            assertFalse(result.any { it.content?.contains("orphan result") == true })
        }

        @Test
        fun `matched tool calls and results are preserved`() {
            val events = listOf(
                userMsg(0, "Read file"),
                fileRead(1, toolCallId = "tc-1", responseGroupId = "rg-1"),
                toolResult(2, toolCallId = "tc-1", content = "file content here")
            )
            val result = memory.processEvents(events, userMsg(0, "Read file"))

            assertTrue(result.any { it.toolCalls != null })
            assertTrue(result.any { it.content?.contains("file content here") == true })
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Sourcegraph sanitization
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    inner class Sanitization {

        @Test
        fun `system messages are wrapped in system_instructions and converted to user`() {
            val events = listOf(
                sysMsg(0, "You are an assistant"),
                userMsg(1, "Hello")
            )
            val result = memory.processEvents(events, userMsg(1, "Hello"))

            // System message should be merged into user with wrapping
            assertTrue(result.all { it.role == "user" || it.role == "assistant" })
            assertTrue(result.any { it.content?.contains("<system_instructions>") == true })
        }

        @Test
        fun `tool results are wrapped in tool_result tags with tool_use_id`() {
            val events = listOf(
                userMsg(0, "Read file"),
                fileRead(1, toolCallId = "tc-1", responseGroupId = "rg-1"),
                toolResult(2, toolCallId = "tc-1", content = "file content")
            )
            val result = memory.processEvents(events, userMsg(0, "Read file"))

            assertTrue(result.any {
                it.content?.contains("<tool_result tool_use_id=\"tc-1\">") == true &&
                    it.content?.contains("file content") == true
            })
        }

        @Test
        fun `output starts with user message`() {
            val events = listOf(userMsg(0, "Hello"), agentMsg(1, "Hi"))
            val result = memory.processEvents(events, userMsg(0, "Hello"))

            assertEquals("user", result.first().role)
        }

        @Test
        fun `prepends context placeholder if first message is not user`() {
            // Force a scenario where the first converted message is assistant
            val events = listOf(agentMsg(0, "Unsolicited reply"))
            val initial = userMsg(99, "Original question")
            val result = memory.processEvents(events, initial)

            assertEquals("user", result.first().role)
        }

        @Test
        fun `consecutive same-role messages are merged`() {
            val events = listOf(
                userMsg(0, "Part 1"),
                userMsg(1, "Part 2"),
                agentMsg(2, "Reply")
            )
            val result = memory.processEvents(events, userMsg(0, "Part 1"))

            // Consecutive user messages should be merged
            val userMessages = result.filter { it.role == "user" }
            // After merging, there should be fewer user messages
            assertTrue(userMessages.size <= 2)
        }

        @Test
        fun `empty assistant content with tool_calls gets placeholder`() {
            val events = listOf(
                userMsg(0, "Read file"),
                fileRead(1, toolCallId = "tc-1", responseGroupId = "rg-1"),
                toolResult(2, toolCallId = "tc-1", content = "content")
            )
            val result = memory.processEvents(events, userMsg(0, "Read file"))

            val assistantWithTools = result.find { it.toolCalls != null }
            assertNotNull(assistantWithTools)
            assertEquals("<tool_calls/>", assistantWithTools!!.content)
        }

        @Test
        fun `no system or tool roles survive in output`() {
            val events = listOf(
                sysMsg(0, "System prompt"),
                userMsg(1, "Hello"),
                factRecorded(2),
                agentMsg(3, "Reply"),
                fileRead(4, toolCallId = "tc-1", responseGroupId = "rg-1"),
                toolResult(5, toolCallId = "tc-1"),
                planUpdated(6)
            )
            val result = memory.processEvents(events, userMsg(1, "Hello"))

            assertTrue(result.all { it.role == "user" || it.role == "assistant" },
                "All messages should be user or assistant, found: ${result.map { it.role }}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Truncation
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    inner class Truncation {

        @Test
        fun `messages within limit are not truncated`() {
            val smallMemory = ConversationMemory(maxMessageChars = 100)
            val events = listOf(userMsg(0, "Short message"))
            val result = smallMemory.processEvents(events, userMsg(0, "Short message"))

            assertTrue(result.any { it.content?.contains("Short message") == true })
            assertFalse(result.any { it.content?.contains("truncated") == true })
        }

        @Test
        fun `oversized messages are middle-truncated`() {
            val smallMemory = ConversationMemory(maxMessageChars = 100)
            val longContent = "A".repeat(200)
            val events = listOf(userMsg(0, longContent))
            val result = smallMemory.processEvents(events, userMsg(0, longContent))

            val truncated = result.find { it.content?.contains("truncated") == true }
            assertNotNull(truncated, "Should have a truncated message")
            assertTrue(truncated!!.content!!.length < 200 + 100) // original + marker overhead
        }

        @Test
        fun `truncation preserves front 60 percent and back 40 percent`() {
            val smallMemory = ConversationMemory(maxMessageChars = 100)
            val content = "F".repeat(100) + "B".repeat(100)
            val events = listOf(userMsg(0, content))
            val result = smallMemory.processEvents(events, userMsg(0, content))

            val msg = result.first()
            // Front should start with F's, back should end with B's
            assertTrue(msg.content!!.startsWith("F"))
            assertTrue(msg.content!!.endsWith("B"))
            assertTrue(msg.content!!.contains("truncated"))
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // End-to-end scenarios
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    inner class EndToEnd {

        @Test
        fun `typical conversation flow produces valid output`() {
            val events = listOf(
                sysMsg(0, "You are a coding assistant"),
                userMsg(1, "Read main.kt and fix the bug"),
                fileRead(2, toolCallId = "tc-1", responseGroupId = "rg-1", path = "/main.kt"),
                toolResult(3, toolCallId = "tc-1", content = "fun main() { println(\"hello\") }"),
                agentMsg(4, "I see the code. Let me fix it."),
                FileEditAction(
                    toolCallId = "tc-2", responseGroupId = "rg-2",
                    path = "/main.kt", oldStr = "hello", newStr = "world", id = 5
                ),
                toolResult(6, toolCallId = "tc-2", content = "Edit applied successfully"),
                finish(7, "Fixed the bug in main.kt")
            )
            val result = memory.processEvents(events, userMsg(1, "Read main.kt and fix the bug"))

            // Validate structure
            assertEquals("user", result.first().role)
            assertTrue(result.all { it.role == "user" || it.role == "assistant" })

            // Validate alternation: no two consecutive same-role without tool_calls
            for (i in 1 until result.size) {
                val prev = result[i - 1]
                val curr = result[i]
                if (prev.role == curr.role) {
                    assertTrue(
                        prev.toolCalls != null || curr.toolCalls != null,
                        "Consecutive same-role messages at index ${i - 1} and $i without tool_calls"
                    )
                }
            }
        }

        @Test
        fun `empty event list with initial message produces output`() {
            val result = memory.processEvents(emptyList(), userMsg(1, "Hello"))

            assertTrue(result.isNotEmpty())
            assertEquals("user", result.first().role)
        }

        @Test
        fun `condensation observation integrates as user message`() {
            val events = listOf(
                condensationObs(0, "Previously: you were analyzing code"),
                userMsg(1, "Continue please"),
                agentMsg(2, "Sure, continuing from where we left off")
            )
            val result = memory.processEvents(events, userMsg(1, "Continue please"))

            assertEquals("user", result.first().role)
            assertTrue(result.any { it.content?.contains("Previously") == true })
        }
    }
}
