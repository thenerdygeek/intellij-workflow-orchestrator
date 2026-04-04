package com.workflow.orchestrator.agent.prompt

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SystemPromptTest {

    @Test
    fun `includes project name and path`() {
        val prompt = SystemPrompt.build(projectName = "my-app", projectPath = "/home/user/my-app")

        assertTrue(prompt.contains("my-app"), "should contain project name")
        assertTrue(prompt.contains("/home/user/my-app"), "should contain project path")
    }

    @Test
    fun `includes attempt_completion instruction`() {
        val prompt = SystemPrompt.build(projectName = "p", projectPath = "/p")

        assertTrue(
            prompt.contains("attempt_completion"),
            "should instruct agent to call attempt_completion"
        )
    }

    @Test
    fun `plan mode adds read-only constraint`() {
        val normal = SystemPrompt.build(projectName = "p", projectPath = "/p", planModeEnabled = false)
        val plan = SystemPrompt.build(projectName = "p", projectPath = "/p", planModeEnabled = true)

        assertFalse(normal.contains("read-only"), "normal mode should not mention read-only")
        assertTrue(plan.contains("read-only"), "plan mode should mention read-only")
    }

    @Test
    fun `includes repo map when provided`() {
        val repoMap = "src/main.kt\nsrc/utils.kt"
        val prompt = SystemPrompt.build(
            projectName = "p", projectPath = "/p", repoMap = repoMap
        )

        assertTrue(prompt.contains("src/main.kt"), "should contain repo map content")
        assertTrue(prompt.contains("src/utils.kt"), "should contain repo map content")
    }

    @Test
    fun `omits repo map section when null`() {
        val prompt = SystemPrompt.build(projectName = "p", projectPath = "/p", repoMap = null)

        assertFalse(
            prompt.contains("Repository structure"),
            "should not contain repo map heading when null"
        )
    }

    @Test
    fun `includes additionalContext when provided`() {
        val ctx = "Always use Kotlin coroutines for async work."
        val prompt = SystemPrompt.build(
            projectName = "p", projectPath = "/p", additionalContext = ctx
        )

        assertTrue(prompt.contains(ctx), "should contain additional context verbatim")
    }

    @Test
    fun `omits additional context section when null`() {
        val prompt = SystemPrompt.build(projectName = "p", projectPath = "/p", additionalContext = null)

        assertFalse(
            prompt.contains("Additional context"),
            "should not contain additional context heading when null"
        )
    }
}
