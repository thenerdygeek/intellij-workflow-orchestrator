package com.workflow.orchestrator.agent.prompt

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

/**
 * Golden snapshot test for the current system prompt.
 *
 * Captures the output of SystemPrompt.build() with minimal params and asserts
 * structural invariants. The snapshot file serves as a reference for verifying
 * that future prompt refactoring doesn't break existing behavior.
 */
class SystemPromptIdeContextTest {

    @Test
    fun `build with minimal params produces valid prompt and saves snapshot`() {
        val prompt = SystemPrompt.build(
            projectName = "TestProject",
            projectPath = "/test/project",
            osName = "Linux",
            shell = "/bin/bash"
        )

        // Save snapshot to test resources
        val snapshotFile = File("src/test/resources/prompt-snapshot-intellij-ultimate.txt")
        snapshotFile.parentFile.mkdirs()
        snapshotFile.writeText(prompt)

        // Structural invariants
        assertTrue(prompt.contains("===="), "Prompt must contain section separators (====)")
        assertTrue(prompt.contains("IntelliJ"), "Prompt must reference IntelliJ (current hardcoded IDE)")
        assertTrue(prompt.length > 3000, "Prompt length ${prompt.length} must be > 3000")
        assertTrue(prompt.length < 30000, "Prompt length ${prompt.length} must be < 30000")

        // Key sections
        assertTrue(prompt.contains("CAPABILITIES"), "Prompt must contain CAPABILITIES section")
        assertTrue(prompt.contains("RULES"), "Prompt must contain RULES section")
        assertTrue(prompt.contains("SYSTEM INFORMATION"), "Prompt must contain SYSTEM INFORMATION section")
        assertTrue(prompt.contains("OBJECTIVE"), "Prompt must contain OBJECTIVE section")
    }

    @Test
    fun `build contains all expected sections in correct order`() {
        val prompt = SystemPrompt.build(
            projectName = "TestProject",
            projectPath = "/test/project",
            osName = "Linux",
            shell = "/bin/bash"
        )

        // Verify section order by checking index positions
        val sections = listOf(
            "IntelliJ IDEA",        // Agent Role (section 1)
            "EDITING FILES",        // Section 3
            "ACT MODE V.S. PLAN MODE", // Section 4
            "CAPABILITIES",         // Section 5
            "RULES",                // Section 7
            "SYSTEM INFORMATION",   // Section 8
            "OBJECTIVE",            // Section 9
            "MEMORY"                // Section 10
        )

        var lastIndex = -1
        for (section in sections) {
            val index = prompt.indexOf(section)
            assertTrue(index > lastIndex, "Section '$section' must appear after previous section (index=$index, lastIndex=$lastIndex)")
            lastIndex = index
        }
    }

    @Test
    fun `build includes system info with provided values`() {
        val prompt = SystemPrompt.build(
            projectName = "TestProject",
            projectPath = "/test/project",
            osName = "TestOS",
            shell = "/bin/zsh"
        )

        assertTrue(prompt.contains("Operating System: TestOS"), "Prompt must include provided OS name")
        assertTrue(prompt.contains("Default Shell: /bin/zsh"), "Prompt must include provided shell")
        assertTrue(prompt.contains("Current Working Directory: /test/project"), "Prompt must include provided project path")
    }

    @Test
    fun `build without optional params omits optional sections`() {
        val prompt = SystemPrompt.build(
            projectName = "TestProject",
            projectPath = "/test/project",
            osName = "Linux",
            shell = "/bin/bash"
        )

        // These sections should NOT appear when their params are null/empty
        assertFalse(prompt.contains("UPDATING TASK PROGRESS"), "Task progress should not appear without taskProgress param")
        assertFalse(prompt.contains("USER'S CUSTOM INSTRUCTIONS"), "User instructions should not appear without additionalContext or repoMap")
        assertFalse(prompt.contains("ADDITIONAL TOOLS (load via tool_search)"), "Deferred tool catalog should not appear without catalog param")
    }

    @Test
    fun `section separators use four equals signs`() {
        val prompt = SystemPrompt.build(
            projectName = "TestProject",
            projectPath = "/test/project",
            osName = "Linux",
            shell = "/bin/bash"
        )

        val separatorCount = Regex("\n\n====\n\n").findAll(prompt).count()
        assertTrue(separatorCount >= 7, "Prompt must have at least 7 section separators, found $separatorCount")
    }
}
