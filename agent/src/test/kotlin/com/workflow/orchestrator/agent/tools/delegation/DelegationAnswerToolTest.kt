package com.workflow.orchestrator.agent.tools.delegation

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Source-text pin tests for the `answer` action of the consolidated [DelegationTool].
 *
 * These tests do not instantiate an IntelliJ Project — they verify the
 * structural invariants of the tool by reading its source text. Richer
 * behavioural tests (mock Project + services) live in Phase 2 / Task 12.
 */
class DelegationAnswerToolTest {

    private val source: String = Files.readString(
        Path.of("src/main/kotlin/com/workflow/orchestrator/agent/tools/delegation/DelegationTool.kt")
    )

    @Test
    fun `tool exposes answer action`() {
        assertTrue(source.contains("\"answer\""), "Tool must expose 'answer' as an action enum value")
        assertTrue(source.contains("handleAnswer"), "answer handler must be defined")
    }

    @Test
    fun `requires handle question_id and answer`() {
        assertTrue(
            source.contains("'handle' is required"),
            "'handle' must be validated as required"
        )
        assertTrue(
            source.contains("'question_id' is required"),
            "'question_id' must be validated as required"
        )
        assertTrue(
            source.contains("'answer' is required"),
            "'answer' must be validated as required"
        )
    }

    @Test
    fun `self-gates on enableOutboundCrossIdeDelegation`() {
        assertTrue(
            source.contains("enableOutboundCrossIdeDelegation"),
            "Tool must gate on the enableOutboundCrossIdeDelegation setting"
        )
        assertTrue(
            source.contains("DelegationOutboundDisabled"),
            "Error code DelegationOutboundDisabled must be emitted when setting is off"
        )
    }

    @Test
    fun `delegates to outbound service sendAnswer`() {
        assertTrue(
            source.contains("sendAnswer("),
            "Tool must delegate to DelegationOutboundService.sendAnswer"
        )
    }

    @Test
    fun `result contains sent boolean field`() {
        assertTrue(source.contains("\"sent\":true"), "Success JSON must include '\"sent\":true'")
    }

    @Test
    fun `result contains handle and question_id fields`() {
        assertTrue(source.contains("\"handle\":"), "Success JSON must echo the handle id")
        assertTrue(source.contains("\"question_id\":"), "Success JSON must echo the question_id")
    }

    @Test
    fun `error when handle unknown or closed`() {
        assertTrue(
            source.contains("unknown or closed"),
            "Must return a distinct error when the handle is unknown or closed"
        )
    }

    @Test
    fun `tool is allowed for ORCHESTRATOR, CODER, and ANALYZER workers`() {
        assertTrue(source.contains("WorkerType.ORCHESTRATOR"), "ORCHESTRATOR must be allowed")
        assertTrue(source.contains("WorkerType.CODER"), "CODER must be allowed")
        assertTrue(source.contains("WorkerType.ANALYZER"), "ANALYZER must be allowed")
    }
}
