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
        assertTrue(preamble.contains("[TASK RESUMPTION]"))
        assertFalse(preamble.contains("[TASK CONTINUATION]"))
    }

    @Test
    fun `continuation preamble flags previously-completed task instead of interruption`() {
        val preamble = ResumeHelper.buildTaskResumptionPreamble(
            mode = "act",
            agoText = "2 hours ago",
            cwd = "/Users/test/project",
            userText = "Now do X too",
            wasPreviouslyCompleted = true,
        )
        assertTrue(preamble.contains("[TASK CONTINUATION]"))
        assertTrue(preamble.contains("previous task in this conversation was completed"))
        assertFalse(preamble.contains("interrupted"))
        assertTrue(preamble.contains("Now do X too"))
        // The LLM guidance must NOT tell the model to pick up mid-task on a completed continuation
        assertFalse(preamble.contains("pick up from the last tool result"))
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

    // agent-runtime:F-14 — tool-result-only USER messages must also be popped
    @Test
    fun `pops trailing user message whose content is only a tool result block`() {
        val toolResultContent = listOf(
            ContentBlock.ToolResult(toolUseId = "call-abc", content = "file written")
        )
        val apiHistory = listOf(
            ApiMessage(role = ApiRole.USER, content = listOf(ContentBlock.Text("start task"))),
            ApiMessage(role = ApiRole.ASSISTANT, content = listOf(ContentBlock.Text("calling tool"))),
            ApiMessage(role = ApiRole.USER, content = toolResultContent),
        )
        val result = ResumeHelper.popTrailingUserMessage(apiHistory)
        assertEquals(2, result.trimmedHistory.size, "tool-result-only USER turn must be popped")
        assertEquals(toolResultContent, result.poppedContent)
    }

    @Test
    fun `pop of tool-result-only user message restores content for replay`() {
        // The popped content must survive so the caller can decide whether to
        // re-inject it or discard it — verify it is non-empty and is the ToolResult.
        val toolResultContent = listOf<ContentBlock>(
            ContentBlock.ToolResult(toolUseId = "call-xyz", content = "tool output here")
        )
        val apiHistory = listOf(
            ApiMessage(role = ApiRole.ASSISTANT, content = listOf(ContentBlock.Text("using tool"))),
            ApiMessage(role = ApiRole.USER, content = toolResultContent),
        )
        val result = ResumeHelper.popTrailingUserMessage(apiHistory)
        assertFalse(result.poppedContent.isEmpty(), "popped content must not be empty")
        assertTrue(result.poppedContent.all { it is ContentBlock.ToolResult })
    }
}
