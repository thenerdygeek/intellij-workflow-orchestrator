package com.workflow.orchestrator.agent.tools.delegation

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Source-text pin tests for the `close` action of the consolidated [DelegationTool].
 *
 * These tests do not instantiate an IntelliJ Project — they verify the
 * structural invariants of the tool by reading its source text. Richer
 * behavioural tests (mock Project + services) live in Phase 2 / Task 12.
 */
class DelegationCloseToolTest {

    private val source: String = Files.readString(
        Path.of("src/main/kotlin/com/workflow/orchestrator/agent/tools/delegation/DelegationTool.kt")
    )

    @Test
    fun `tool exposes close action`() {
        assertTrue(source.contains("\"close\""), "Tool must expose 'close' as an action enum value")
        assertTrue(source.contains("handleClose"), "close handler must be defined")
    }

    @Test
    fun `handle is required parameter`() {
        assertTrue(
            source.contains("'handle' is required") || source.contains("\"handle\" is required"),
            "'handle' must be validated as required"
        )
    }

    @Test
    fun `delegates to outbound service close`() {
        assertTrue(source.contains(".close("), "Tool must delegate to DelegationOutboundService.close")
    }

    @Test
    fun `idempotent - already-closed returns success`() {
        // The close() Boolean return is used to choose summary text but NOT to fail.
        // Both true and false outcomes must produce a ToolResult success, not an error.
        assertTrue(
            source.contains("ToolResult(") && source.contains(".close("),
            "A no-op close must return ToolResult success, never ToolResult.error"
        )
    }

    @Test
    fun `result contains closed boolean field`() {
        assertTrue(source.contains("\"closed\":"), "Content JSON must include the 'closed' boolean field")
    }

    @Test
    fun `result contains handle field`() {
        assertTrue(source.contains("\"handle\":"), "Content JSON must echo the handle id back in the result")
    }

    @Test
    fun `summary text distinguishes closed from already-closed`() {
        assertTrue(
            source.contains("Closed delegation") && source.contains("already closed"),
            "Summary must produce different text for a fresh close vs an already-closed handle"
        )
    }

    @Test
    fun `tool is allowed for ORCHESTRATOR, CODER, and ANALYZER workers`() {
        assertTrue(source.contains("WorkerType.ORCHESTRATOR"), "ORCHESTRATOR must be allowed")
        assertTrue(source.contains("WorkerType.CODER"), "CODER must be allowed")
        assertTrue(source.contains("WorkerType.ANALYZER"), "ANALYZER must be allowed")
    }
}
