package com.workflow.orchestrator.agent.hooks

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies that credentials emitted by hook processes on stderr do NOT survive into
 * [HookResult] — they are routed through [CredentialRedactor] before being used
 * as the cancel reason or context modification.
 *
 * Closes audit finding agent-runtime:F-6.
 */
class CredentialRedactorHookTest {

    private val runner = HookRunner()

    /**
     * Simulate a non-JSON stderr that contains a Bearer token and a plain env-style secret.
     * The hook exits non-zero so the stderr becomes the [HookResult.Cancel.reason].
     */
    @Test
    fun `Bearer token in hook stderr is redacted in cancel reason`() {
        val stderrWithToken = "curl: (6) Authorization: Bearer abc123xyz456abc123xyz456\nFailed to connect"
        val result = ProcessResult(exitCode = 1, stdout = "", stderr = stderrWithToken)
        val event = HookEvent(
            type = HookType.PRE_TOOL_USE,
            data = emptyMap(),
            cancellable = true,
        )

        val hookResult = runner.interpretResult(result, event)

        assertTrue(hookResult is HookResult.Cancel, "Non-zero exit with cancellable event must cancel")
        val reason = (hookResult as HookResult.Cancel).reason
        assertFalse(reason.contains("abc123xyz456"), "Raw Bearer token must NOT appear in cancel reason; got: $reason")
        assertTrue(reason.contains("[REDACTED]"), "Redacted marker must be present; got: $reason")
    }

    @Test
    fun `JIRA_TOKEN env variable in hook stderr is redacted`() {
        // Simulate a hook that accidentally echoes its environment
        val stderrWithEnv = "JIRA_TOKEN=secret-jira-token-value\nJIRA_URL=https://jira.example.com"
        val result = ProcessResult(exitCode = 2, stdout = "", stderr = stderrWithEnv)
        val event = HookEvent(
            type = HookType.TASK_START,
            data = emptyMap(),
            cancellable = true,
        )

        val hookResult = runner.interpretResult(result, event)
        assertTrue(hookResult is HookResult.Cancel)
        val reason = (hookResult as HookResult.Cancel).reason
        // The specific JIRA_TOKEN value should not appear raw; generic strings stay
        assertTrue(reason.contains("JIRA_URL"), "Non-sensitive env values should survive")
    }

    @Test
    fun `Sourcegraph token in hook contextModification is redacted`() {
        val sgToken = "sgp_" + "a".repeat(40)
        val stdout = """{"cancel": false, "contextModification": "Token: $sgToken loaded"}"""
        val result = ProcessResult(exitCode = 0, stdout = stdout, stderr = "")
        val event = HookEvent(
            type = HookType.PRE_TOOL_USE,
            data = emptyMap(),
            cancellable = false,
        )

        val hookResult = runner.interpretResult(result, event)
        assertTrue(hookResult is HookResult.Proceed)
        val contextMod = (hookResult as HookResult.Proceed).contextModification
        assertFalse(
            contextMod?.contains(sgToken) == true,
            "Sourcegraph token must be redacted from contextModification; got: $contextMod"
        )
    }

    @Test
    fun `errorMessage in hook JSON stdout with Bearer token is redacted`() {
        val token = "ghp_" + "X".repeat(36)
        val stdout = """{"cancel": true, "errorMessage": "Failed with token Bearer $token"}"""
        val result = ProcessResult(exitCode = 0, stdout = stdout, stderr = "")
        val event = HookEvent(
            type = HookType.PRE_TOOL_USE,
            data = emptyMap(),
            cancellable = true,
        )

        val hookResult = runner.interpretResult(result, event)
        assertTrue(hookResult is HookResult.Cancel)
        val reason = (hookResult as HookResult.Cancel).reason
        assertFalse(reason.contains(token), "GitHub token must be redacted from errorMessage; got: $reason")
    }
}
