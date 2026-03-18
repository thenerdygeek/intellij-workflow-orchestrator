package com.workflow.orchestrator.agent.orchestrator

import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class OrchestratorPromptsTest {

    @ParameterizedTest
    @EnumSource(WorkerType::class)
    fun `getSystemPrompt returns non-blank for each WorkerType`(workerType: WorkerType) {
        val prompt = OrchestratorPrompts.getSystemPrompt(workerType)
        assertTrue(prompt.isNotBlank(), "Prompt for $workerType should not be blank")
    }

    @Test
    fun `ORCHESTRATOR prompt contains plan or orchestrator`() {
        val prompt = OrchestratorPrompts.getSystemPrompt(WorkerType.ORCHESTRATOR).lowercase()
        assertTrue(
            prompt.contains("plan") || prompt.contains("orchestrator"),
            "ORCHESTRATOR prompt should mention 'plan' or 'orchestrator'"
        )
    }

    @Test
    fun `CODER prompt contains edit or code`() {
        val prompt = OrchestratorPrompts.getSystemPrompt(WorkerType.CODER).lowercase()
        assertTrue(
            prompt.contains("edit") || prompt.contains("code"),
            "CODER prompt should mention 'edit' or 'code'"
        )
    }

    @Test
    fun `ANALYZER prompt contains analyze or read`() {
        val prompt = OrchestratorPrompts.getSystemPrompt(WorkerType.ANALYZER).lowercase()
        assertTrue(
            prompt.contains("analyz") || prompt.contains("read"),
            "ANALYZER prompt should mention 'analyze' or 'read'"
        )
    }

    @Test
    fun `REVIEWER prompt contains review`() {
        val prompt = OrchestratorPrompts.getSystemPrompt(WorkerType.REVIEWER).lowercase()
        assertTrue(
            prompt.contains("review"),
            "REVIEWER prompt should mention 'review'"
        )
    }

    @Test
    fun `TOOLER prompt contains Jira or enterprise`() {
        val prompt = OrchestratorPrompts.getSystemPrompt(WorkerType.TOOLER).lowercase()
        assertTrue(
            prompt.contains("jira") || prompt.contains("enterprise"),
            "TOOLER prompt should mention 'Jira' or 'enterprise'"
        )
    }

    @ParameterizedTest
    @EnumSource(WorkerType::class)
    fun `all prompts are under 2000 tokens`(workerType: WorkerType) {
        val prompt = OrchestratorPrompts.getSystemPrompt(workerType)
        val tokens = TokenEstimator.estimate(prompt)
        assertTrue(
            tokens < 2000,
            "Prompt for $workerType is $tokens tokens, expected under 2000"
        )
    }

    @Test
    fun `COMPLEXITY_ROUTER_PROMPT is non-blank`() {
        assertTrue(OrchestratorPrompts.COMPLEXITY_ROUTER_PROMPT.isNotBlank())
    }

    @Test
    fun `COMPLEXITY_ROUTER_PROMPT mentions SIMPLE and COMPLEX`() {
        val prompt = OrchestratorPrompts.COMPLEXITY_ROUTER_PROMPT
        assertTrue(prompt.contains("SIMPLE"), "Should mention SIMPLE classification")
        assertTrue(prompt.contains("COMPLEX"), "Should mention COMPLEX classification")
    }
}
