package com.workflow.orchestrator.agent.session

import com.workflow.orchestrator.agent.tools.background.BackgroundCompletionEvent
import com.workflow.orchestrator.agent.tools.background.BackgroundState
import com.workflow.orchestrator.agent.tools.background.DelegationNudge
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

    // --- resume preamble away-event section formatters (Phase 3 cut C incision) ---

    private fun completionEvent(
        bgId: String = "bg-1",
        kind: String = "command",
        label: String = "npm test",
        exitCode: Int = 0,
        state: BackgroundState = BackgroundState.EXITED,
        runtimeMs: Long = 1200L,
        tailContent: String = "line1\nline2",
    ) = BackgroundCompletionEvent(
        bgId = bgId,
        kind = kind,
        label = label,
        sessionId = "sess",
        exitCode = exitCode,
        state = state,
        runtimeMs = runtimeMs,
        tailContent = tailContent,
        spillPath = null,
        occurredAt = 0L,
    )

    @Test
    fun `empty background completions yield empty section`() {
        assertEquals("", ResumeHelper.formatBackgroundCompletionsSection(emptyList()))
    }

    @Test
    fun `formats background completions section exactly`() {
        val section = ResumeHelper.formatBackgroundCompletionsSection(listOf(completionEvent()))
        val expected = "\n\n[BACKGROUND COMPLETIONS — delivered on resume]\n" +
            "While the session was paused, these background processes completed:\n\n" +
            "- bg-1 (command: \"npm test\") — exit 0, EXITED, 1200ms\n  line1\n  line2" +
            "\n"
        assertEquals(expected, section)
    }

    @Test
    fun `background completion label is truncated to 80 chars and tail capped at 5 lines`() {
        val longLabel = "x".repeat(120)
        val tail = (1..10).joinToString("\n") { "l$it" }
        val section = ResumeHelper.formatBackgroundCompletionsSection(
            listOf(completionEvent(label = longLabel, tailContent = tail))
        )
        assertTrue(section.contains("\"${"x".repeat(80)}\""), "label must be truncated to 80 chars")
        assertFalse(section.contains("x".repeat(81)), "no more than 80 label chars")
        assertTrue(section.contains("l6\n  l7\n  l8\n  l9\n  l10"), "only the last 5 tail lines kept")
        assertFalse(section.contains("  l5"), "earlier tail lines dropped")
    }

    @Test
    fun `formats multiple background completions joined by blank line`() {
        val section = ResumeHelper.formatBackgroundCompletionsSection(
            listOf(completionEvent(bgId = "bg-1"), completionEvent(bgId = "bg-2"))
        )
        assertTrue(section.contains("- bg-1 "))
        assertTrue(section.contains("- bg-2 "))
    }

    @Test
    fun `empty delegation nudges yield empty section`() {
        assertEquals("", ResumeHelper.formatDelegationNudgesSection(emptyList()))
    }

    @Test
    fun `formats delegation nudges section exactly`() {
        val nudges = listOf(
            DelegationNudge(id = "n1", text = "result A", occurredAt = 0L),
            DelegationNudge(id = "n2", text = "question B", occurredAt = 0L),
        )
        val section = ResumeHelper.formatDelegationNudgesSection(nudges)
        val expected = "\n\n[DELEGATION RESULTS — delivered on resume]\n" +
            "While the session was paused, these cross-IDE delegation results/questions " +
            "arrived. Decide whether each needs action; if a question is included, answer " +
            "it via delegation(action=\"answer\"):\n\n" +
            "result A\n\n---\n\nquestion B" +
            "\n"
        assertEquals(expected, section)
    }

    @Test
    fun `empty monitor notifications yield empty section`() {
        assertEquals("", ResumeHelper.formatMonitorNotificationsSection(emptyList()))
    }

    @Test
    fun `formats monitor notifications section exactly`() {
        val section = ResumeHelper.formatMonitorNotificationsSection(listOf("build failed", "tests green"))
        val expected = "\n\n# Monitor notifications while away\n" +
            "While the session was paused, the following monitor events fired:\n\n" +
            "build failed\ntests green" +
            "\n"
        assertEquals(expected, section)
    }
}
