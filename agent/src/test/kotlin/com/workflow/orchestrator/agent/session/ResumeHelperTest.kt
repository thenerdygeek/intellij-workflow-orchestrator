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
    fun `recent resume skips state-change warning`() {
        val preamble = ResumeHelper.buildTaskResumptionPreamble(
            mode = "act", agoText = "5s ago", cwd = "/project", wasRecent = true
        )
        assertFalse(preamble.contains("project state may have changed"))
        assertTrue(preamble.contains("[TASK RESUMPTION]"))
        assertTrue(preamble.contains("Continue where you left off."))
        // Should NOT have the longer instruction
        assertFalse(preamble.contains("Do not repeat completed work"))
    }

    @Test
    fun `old resume includes full state-change warning`() {
        val preamble = ResumeHelper.buildTaskResumptionPreamble(
            mode = "act", agoText = "2 hours ago", cwd = "/project", wasRecent = false
        )
        assertTrue(preamble.contains("project state may have changed"))
        assertTrue(preamble.contains("Do not repeat completed work"))
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
