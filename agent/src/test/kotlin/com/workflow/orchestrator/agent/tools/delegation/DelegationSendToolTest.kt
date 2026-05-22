package com.workflow.orchestrator.agent.tools.delegation

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Source-text pin tests for [DelegationSendTool].
 *
 * These tests do not instantiate an IntelliJ Project — they verify the
 * structural invariants of the tool by reading its source text.  Richer
 * behavioural tests (mock Project + services) live in Phase 2 / Task 12.
 */
class DelegationSendToolTest {

    private val source: String = Files.readString(
        Path.of("src/main/kotlin/com/workflow/orchestrator/agent/tools/delegation/DelegationSendTool.kt")
    )

    @Test
    fun `tool name is delegation_send`() {
        assertTrue(source.contains("\"delegation_send\""), "Tool name must be exactly 'delegation_send'")
    }

    @Test
    fun `request is in the required parameters list`() {
        assertTrue(source.contains("required = listOf(\"request\")"), "'request' must be the only required parameter")
    }

    @Test
    fun `suggested_repo is declared as an optional parameter`() {
        assertTrue(source.contains("suggested_repo"), "'suggested_repo' optional parameter must be declared")
    }

    @Test
    fun `tool maps UserCanceledPicker to a distinct error`() {
        assertTrue(
            source.contains("DelegationException.UserCanceledPicker"),
            "DelegationException.UserCanceledPicker must be caught and mapped to a ToolResult error"
        )
    }

    @Test
    fun `tool maps TargetNotReachable to a distinct error`() {
        assertTrue(
            source.contains("DelegationException.TargetNotReachable"),
            "DelegationException.TargetNotReachable must be caught and mapped to a ToolResult error"
        )
    }

    @Test
    fun `tool maps LimitReached to a distinct error`() {
        assertTrue(
            source.contains("DelegationException.LimitReached"),
            "DelegationException.LimitReached must be caught and mapped to a ToolResult error"
        )
    }

    @Test
    fun `tool maps Rejected to a distinct error`() {
        assertTrue(
            source.contains("DelegationException.Rejected"),
            "DelegationException.Rejected must be caught and mapped to a ToolResult error"
        )
    }

    @Test
    fun `onResult closure delivers a nudge via enqueueNudgeForSession`() {
        assertTrue(
            source.contains("enqueueNudgeForSession"),
            "onResult closure must call agentService.enqueueNudgeForSession to inject the nudge"
        )
    }

    @Test
    fun `outbound service is consulted via send`() {
        assertTrue(
            source.contains("outboundService.send"),
            "Tool must delegate to DelegationOutboundService.send"
        )
    }

    @Test
    fun `nudge text includes the status of the result`() {
        assertTrue(
            source.contains("result.status"),
            "buildNudgeText must include result.status in the nudge"
        )
    }

    @Test
    fun `nudge text includes the summary when present`() {
        assertTrue(
            source.contains("result.summary"),
            "buildNudgeText must include result.summary in the nudge"
        )
    }

    @Test
    fun `nudge text includes files changed when present`() {
        assertTrue(
            source.contains("result.filesChanged"),
            "buildNudgeText must include result.filesChanged in the nudge"
        )
    }

    @Test
    fun `tool is allowed for ORCHESTRATOR, CODER, and ANALYZER workers`() {
        assertTrue(source.contains("WorkerType.ORCHESTRATOR"), "ORCHESTRATOR must be allowed")
        assertTrue(source.contains("WorkerType.CODER"), "CODER must be allowed")
        assertTrue(source.contains("WorkerType.ANALYZER"), "ANALYZER must be allowed")
    }

    @Test
    fun `delegator session ID is captured from currentSessionState`() {
        assertTrue(
            source.contains("currentSessionState()"),
            "Tool must read the session ID from agentService.currentSessionState()"
        )
    }
}
