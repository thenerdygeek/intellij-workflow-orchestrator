package com.workflow.orchestrator.core.ai.prompts

import com.workflow.orchestrator.core.workflow.TicketContext
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PrTitlePromptBuilderTest {

    private fun makeTicket(
        key: String = "PROJ-42",
        summary: String = "Add payment flow",
        description: String? = "Implement the new payment flow",
        status: String? = "In Progress",
        issueType: String? = "Story"
    ) = TicketContext(
        key = key,
        summary = summary,
        description = description,
        status = status,
        priority = null,
        issueType = issueType,
        assignee = null,
        reporter = null
    )

    // ── Description present ────────────────────────────────────────────────────

    @Test
    fun `ticket with description includes description section`() {
        val ticket = makeTicket(description = "Implement the payment flow end to end")
        val result = PrTitlePromptBuilder.build(ticket)
        assertTrue(result.contains("Description:"), "Description section header should be present")
        assertTrue(result.contains("Implement the payment flow end to end"), "Description content should appear")
    }

    @Test
    fun `description longer than 1500 chars is truncated with ellipsis`() {
        val longDesc = "A".repeat(2000)
        val ticket = makeTicket(description = longDesc)
        val result = PrTitlePromptBuilder.build(ticket)
        assertTrue(result.contains("A".repeat(1500)), "First 1500 chars of description should be present")
        assertFalse(result.contains("A".repeat(1501)), "Description should not exceed 1500 chars")
        assertTrue(result.contains("..."), "Truncated description should have ellipsis")
    }

    // ── Blank description ──────────────────────────────────────────────────────

    @Test
    fun `blank description omits description section`() {
        val ticket = makeTicket(description = "   ")
        val result = PrTitlePromptBuilder.build(ticket)
        assertFalse(result.contains("Description:"), "Description section should be omitted when blank")
    }

    @Test
    fun `null description omits description section`() {
        val ticket = makeTicket(description = null)
        val result = PrTitlePromptBuilder.build(ticket)
        assertFalse(result.contains("Description:"), "Description section should be omitted when null")
    }

    // ── Commit messages ────────────────────────────────────────────────────────

    @Test
    fun `empty commitMessages omits COMMITS block`() {
        val ticket = makeTicket()
        val result = PrTitlePromptBuilder.build(ticket, commitMessages = emptyList())
        assertFalse(result.contains("COMMITS"), "COMMITS block should be omitted when no commit messages")
    }

    @Test
    fun `non-empty commitMessages emits COMMITS block`() {
        val ticket = makeTicket()
        val result = PrTitlePromptBuilder.build(
            ticket,
            commitMessages = listOf("feat: implement payment gateway", "fix: handle timeout")
        )
        assertTrue(result.contains("COMMITS"), "COMMITS block should appear when messages provided")
        assertTrue(result.contains("feat: implement payment gateway"))
        assertTrue(result.contains("fix: handle timeout"))
    }

    @Test
    fun `more than 10 commit messages are capped at 10`() {
        val ticket = makeTicket()
        val commits = (1..15).map { "commit $it" }
        val result = PrTitlePromptBuilder.build(ticket, commitMessages = commits)
        assertTrue(result.contains("commit 10"), "10th commit should be included")
        assertFalse(result.contains("commit 11"), "11th commit should be excluded")
    }

    // ── Ticket key in format rule ──────────────────────────────────────────────

    @Test
    fun `ticket key appears in the format rule line`() {
        val ticket = makeTicket(key = "MYPROJ-99")
        val result = PrTitlePromptBuilder.build(ticket)
        assertTrue(result.contains("MYPROJ-99"), "Ticket key should appear in the prompt (format rule and CONTEXT)")
        assertTrue(result.contains("Start with the ticket key: MYPROJ-99"), "Format rule should reference ticket key")
    }

    @Test
    fun `format rule includes ticket key format example`() {
        val ticket = makeTicket(key = "ABC-123")
        val result = PrTitlePromptBuilder.build(ticket)
        assertTrue(result.contains("\"ABC-123: {concise summary}\""), "Format example should include ticket key")
    }

    // ── Structure verification ─────────────────────────────────────────────────

    @Test
    fun `prompt contains output-only instruction`() {
        val ticket = makeTicket()
        val result = PrTitlePromptBuilder.build(ticket)
        assertTrue(result.contains("Output ONLY the title") || result.contains("Output the title only"),
            "Prompt should instruct model to output only the title")
    }

    @Test
    fun `prompt contains max 100 character rule`() {
        val ticket = makeTicket()
        val result = PrTitlePromptBuilder.build(ticket)
        assertTrue(result.contains("100"), "Prompt should mention 100 character limit")
    }

    @Test
    fun `prompt contains imperative mood rule`() {
        val ticket = makeTicket()
        val result = PrTitlePromptBuilder.build(ticket)
        assertTrue(result.contains("Imperative mood") || result.contains("imperative mood"),
            "Prompt should specify imperative mood")
    }
}
