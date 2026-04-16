package com.workflow.orchestrator.agent.prompt

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SystemPromptTest {

    private fun defaultPrompt() = SystemPrompt.build(
        projectName = "my-app",
        projectPath = "/home/user/my-app"
    )

    // ---- Section presence tests ----

    @Test
    fun `includes agent role section`() {
        val prompt = defaultPrompt()

        assertTrue(
            prompt.contains("AI coding agent running inside IntelliJ IDEA"),
            "should contain agent role description"
        )
        assertTrue(
            prompt.contains("programming languages, frameworks, design patterns"),
            "should describe engineering expertise"
        )
    }

    @Test
    fun `includes editing files section`() {
        val prompt = defaultPrompt()

        assertTrue(prompt.contains("EDITING FILES"), "should contain EDITING FILES heading")
        assertTrue(prompt.contains("create_file"), "should reference create_file tool")
        assertTrue(prompt.contains("edit_file"), "should reference edit_file tool")
        assertTrue(
            prompt.contains("Default to edit_file"),
            "should recommend edit_file as default"
        )
        assertTrue(
            prompt.contains("auto-format"),
            "should include auto-formatting note"
        )
    }

    @Test
    fun `includes act vs plan mode section`() {
        val prompt = defaultPrompt()

        assertTrue(prompt.contains("ACT MODE V.S. PLAN MODE"), "should contain act vs plan heading")
        assertTrue(prompt.contains("ACT MODE"), "should mention ACT MODE")
        assertTrue(prompt.contains("PLAN MODE"), "should mention PLAN MODE")
        assertTrue(prompt.contains("attempt_completion"), "should reference attempt_completion in act mode")
        assertTrue(prompt.contains("plan_mode_respond"), "should reference plan_mode_respond in plan mode")
    }

    @Test
    fun `includes capabilities section`() {
        val prompt = defaultPrompt()

        assertTrue(prompt.contains("CAPABILITIES"), "should contain CAPABILITIES heading")
        assertTrue(prompt.contains("run_command"), "should reference run_command tool")
        assertTrue(prompt.contains("search_code"), "should reference search_code tool")
        assertTrue(prompt.contains("read_file"), "should reference read_file tool")
    }

    @Test
    fun `includes rules section`() {
        val prompt = defaultPrompt()

        assertTrue(prompt.contains("RULES"), "should contain RULES heading")
        assertTrue(
            prompt.contains("Working directory:"),
            "should mention working directory constraint"
        )
        assertTrue(
            prompt.contains("Be direct and technical"),
            "should include communication rules"
        )
        assertTrue(
            prompt.contains("attempt_completion"),
            "should instruct agent to call attempt_completion"
        )
    }

    @Test
    fun `includes system info section`() {
        val prompt = defaultPrompt()

        assertTrue(prompt.contains("SYSTEM INFORMATION"), "should contain SYSTEM INFORMATION heading")
        assertTrue(prompt.contains("Operating System:"), "should include OS info")
        assertTrue(prompt.contains("Default Shell:"), "should include shell info")
        assertTrue(prompt.contains("IntelliJ IDEA"), "should reference IntelliJ IDEA as IDE")
        assertTrue(prompt.contains("Home Directory:"), "should include home directory")
        assertTrue(prompt.contains("Current Working Directory:"), "should include CWD")
    }

    @Test
    fun `includes objective section`() {
        val prompt = defaultPrompt()

        assertTrue(prompt.contains("OBJECTIVE"), "should contain OBJECTIVE heading")
        assertTrue(
            prompt.contains("Accomplish the user's task iteratively"),
            "should describe iterative approach"
        )
        assertTrue(
            prompt.contains("<thinking>"),
            "should instruct use of thinking tags"
        )
        assertTrue(
            prompt.contains("attempt_completion"),
            "should reference attempt_completion as final step"
        )
    }

    @Test
    fun `sections are separated by section separators`() {
        val prompt = defaultPrompt()
        val separatorCount = "====".toRegex().findAll(prompt).count()

        // At minimum: editing files, act vs plan, capabilities, rules, system info, objective
        // = 6 mandatory sections after agent role = at least 6 separators
        assertTrue(
            separatorCount >= 6,
            "should have at least 6 section separators, found $separatorCount"
        )
    }

    // ---- Plan mode tests ----

    @Test
    fun `act mode mentions ACT MODE`() {
        val prompt = SystemPrompt.build(
            projectName = "p", projectPath = "/p", planModeEnabled = false
        )

        assertTrue(
            prompt.contains("ACT MODE"),
            "should mention ACT MODE"
        )
    }

    @Test
    fun `plan mode mentions PLAN MODE`() {
        val prompt = SystemPrompt.build(
            projectName = "p", projectPath = "/p", planModeEnabled = true
        )

        assertTrue(
            prompt.contains("PLAN MODE"),
            "should mention PLAN MODE"
        )
    }

    @Test
    fun `plan mode section references discard_plan tool`() {
        val prompt = SystemPrompt.build(
            projectName = "p", projectPath = "/p", planModeEnabled = true
        )

        assertTrue(
            prompt.contains("discard_plan"),
            "plan mode prompt should reference discard_plan tool"
        )
    }

    @Test
    fun `act mode section does not mention discard_plan as blocked`() {
        val prompt = SystemPrompt.build(
            projectName = "p", projectPath = "/p", planModeEnabled = false
        )

        // discard_plan may appear in the act-vs-plan section describing plan mode,
        // but the prompt text should describe it as a plan-mode-only tool
        val actVsPlanSection = prompt.substringAfter("ACT MODE V.S. PLAN MODE").substringBefore("====")
        // The discard_plan reference should be within the PLAN MODE description, not as an act-mode tool
        assertTrue(
            actVsPlanSection.contains("discard_plan"),
            "act vs plan section should describe discard_plan as part of plan mode"
        )
    }

    // ---- System info tests ----

    @Test
    fun `system info includes custom OS and shell`() {
        val prompt = SystemPrompt.build(
            projectName = "p",
            projectPath = "/p",
            osName = "macOS 15.2",
            shell = "/bin/zsh"
        )

        assertTrue(prompt.contains("macOS 15.2"), "should contain custom OS name")
        assertTrue(prompt.contains("/bin/zsh"), "should contain custom shell")
    }

    @Test
    fun `system info includes project path as CWD`() {
        val prompt = SystemPrompt.build(
            projectName = "p",
            projectPath = "/workspace/my-project"
        )

        assertTrue(
            prompt.contains("Current Working Directory: /workspace/my-project"),
            "should show project path as CWD"
        )
    }

    // ---- Skills section tests ----

    @Test
    fun `includes skills section when skills provided`() {
        val skills = listOf(
            SkillMetadata("kotlin-expert", "Expert guidance for Kotlin development", "/skills/kotlin-expert/SKILL.md", SkillSource.PROJECT),
            SkillMetadata("react-guru", "React component design and patterns", "/skills/react-guru/SKILL.md", SkillSource.GLOBAL)
        )
        val prompt = SystemPrompt.build(
            projectName = "p",
            projectPath = "/p",
            availableSkills = skills
        )

        assertTrue(prompt.contains("SKILLS"), "should contain SKILLS heading")
        assertTrue(prompt.contains("kotlin-expert"), "should list kotlin-expert skill")
        assertTrue(prompt.contains("react-guru"), "should list react-guru skill")
        assertTrue(prompt.contains("use_skill"), "should reference use_skill tool")
    }

    @Test
    fun `omits skills section when no skills provided`() {
        val prompt = SystemPrompt.build(
            projectName = "p", projectPath = "/p", availableSkills = null
        )

        assertFalse(prompt.contains("SKILLS"), "should not contain SKILLS heading")
    }

    @Test
    fun `omits skills section when empty skills list`() {
        val prompt = SystemPrompt.build(
            projectName = "p", projectPath = "/p", availableSkills = emptyList()
        )

        assertFalse(prompt.contains("SKILLS"), "should not contain SKILLS heading")
    }

    @Test
    fun `includes active skill content when provided`() {
        val prompt = SystemPrompt.build(
            projectName = "p",
            projectPath = "/p",
            availableSkills = listOf(
                SkillMetadata("test-skill", "A test skill", "/skills/test-skill/SKILL.md", SkillSource.BUNDLED)
            ),
            activeSkillContent = "Follow these specific testing guidelines..."
        )

        assertTrue(
            prompt.contains("Active Skill Instructions"),
            "should contain active skill heading"
        )
        assertTrue(
            prompt.contains("Follow these specific testing guidelines"),
            "should contain active skill content"
        )
    }

    // ---- Task progress section tests ----

    @Test
    fun `includes task progress section when provided`() {
        val progress = """- [x] Set up project structure
- [x] Install dependencies
- [ ] Create components
- [ ] Test application"""

        val prompt = SystemPrompt.build(
            projectName = "p",
            projectPath = "/p",
            taskProgress = progress
        )

        assertTrue(
            prompt.contains("UPDATING TASK PROGRESS"),
            "should contain task progress heading"
        )
        assertTrue(
            prompt.contains("- [x] Set up project structure"),
            "should contain completed items"
        )
        assertTrue(
            prompt.contains("- [ ] Create components"),
            "should contain incomplete items"
        )
    }

    @Test
    fun `omits task progress section when null`() {
        val prompt = SystemPrompt.build(
            projectName = "p", projectPath = "/p", taskProgress = null
        )

        assertFalse(
            prompt.contains("UPDATING TASK PROGRESS"),
            "should not contain task progress heading"
        )
    }

    @Test
    fun `omits task progress section when blank`() {
        val prompt = SystemPrompt.build(
            projectName = "p", projectPath = "/p", taskProgress = "  "
        )

        assertFalse(
            prompt.contains("UPDATING TASK PROGRESS"),
            "should not contain task progress heading for blank input"
        )
    }

    // ---- User instructions / additional context tests ----

    @Test
    fun `includes user instructions when additionalContext provided`() {
        val ctx = "Always use Kotlin coroutines for async work."
        val prompt = SystemPrompt.build(
            projectName = "p", projectPath = "/p", additionalContext = ctx
        )

        assertTrue(
            prompt.contains("USER'S CUSTOM INSTRUCTIONS"),
            "should contain custom instructions heading"
        )
        assertTrue(prompt.contains(ctx), "should contain additional context verbatim")
    }

    @Test
    fun `includes repo map in user instructions`() {
        val repoMap = "src/main.kt\nsrc/utils.kt"
        val prompt = SystemPrompt.build(
            projectName = "p", projectPath = "/p", repoMap = repoMap
        )

        assertTrue(
            prompt.contains("USER'S CUSTOM INSTRUCTIONS"),
            "should contain custom instructions heading when repoMap provided"
        )
        assertTrue(prompt.contains("src/main.kt"), "should contain repo map content")
        assertTrue(prompt.contains("src/utils.kt"), "should contain repo map content")
    }

    @Test
    fun `omits user instructions section when no context or repoMap`() {
        val prompt = SystemPrompt.build(
            projectName = "p", projectPath = "/p",
            additionalContext = null, repoMap = null
        )

        assertFalse(
            prompt.contains("USER'S CUSTOM INSTRUCTIONS"),
            "should not contain custom instructions heading when nothing provided"
        )
    }

    @Test
    fun `includes output management guidance in rules section`() {
        val prompt = defaultPrompt()
        val rulesSection = prompt.substringAfter("RULES").substringBefore("====")

        assertTrue(
            rulesSection.contains("Output Management"),
            "should contain Output Management heading in rules section"
        )
        assertTrue(
            rulesSection.contains("grep_pattern"),
            "should document grep_pattern parameter"
        )
        assertTrue(
            rulesSection.contains("output_file"),
            "should document output_file parameter"
        )
        assertTrue(
            rulesSection.contains("context pollution"),
            "should explain why output filtering matters"
        )
    }

    // ---- Project path propagation tests ----

    @Test
    fun `project path appears in rules section`() {
        val prompt = SystemPrompt.build(
            projectName = "my-app",
            projectPath = "/workspace/my-app"
        )

        // Rules section references CWD multiple times
        val rulesSection = prompt.substringAfter("RULES").substringBefore("====")
        assertTrue(
            rulesSection.contains("/workspace/my-app"),
            "rules section should contain project path"
        )
    }

    @Test
    fun `project path appears in capabilities section`() {
        val prompt = SystemPrompt.build(
            projectName = "my-app",
            projectPath = "/workspace/my-app"
        )

        val capSection = prompt.substringAfter("CAPABILITIES").substringBefore("====")
        assertTrue(
            capSection.contains("/workspace/my-app"),
            "capabilities section should contain project path"
        )
    }

    // ---- Tool name adaptation tests ----

    @Test
    fun `uses adapted tool names not Cline originals`() {
        val prompt = defaultPrompt()

        // Our adapted names should be present
        assertTrue(prompt.contains("create_file"), "should use create_file")
        assertTrue(prompt.contains("edit_file"), "should use edit_file")
        assertTrue(prompt.contains("run_command"), "should use run_command")

        // Cline's original XML-format tool names should NOT be present
        assertFalse(prompt.contains("write_to_file"), "should not use Cline's write_to_file")
        assertFalse(prompt.contains("replace_in_file"), "should not use Cline's replace_in_file")
        assertFalse(prompt.contains("execute_command"), "should not use Cline's execute_command")
    }

    // ---- Deferred tool catalog tests ----

    @Test
    fun `includes deferred tool catalog with descriptions`() {
        val catalog = mapOf(
            "Code Intelligence" to listOf(
                "find_implementations" to "Find all implementations of an interface",
                "type_hierarchy" to "Show supertype/subtype hierarchy"
            ),
            "VCS" to listOf(
                "changelist_shelve" to "Manage IntelliJ changelists and shelve operations"
            )
        )
        val prompt = SystemPrompt.build(
            projectName = "p", projectPath = "/p",
            deferredToolCatalog = catalog
        )

        assertTrue(prompt.contains("ADDITIONAL TOOLS"), "should contain deferred tools heading")
        assertTrue(prompt.contains("find_implementations — Find all implementations of an interface"),
            "should contain tool name with description")
        assertTrue(prompt.contains("type_hierarchy — Show supertype/subtype hierarchy"),
            "should contain second tool with description")
        assertTrue(prompt.contains("changelist_shelve — Manage IntelliJ changelists and shelve operations"),
            "should contain VCS tool with description")
        assertTrue(prompt.contains("**Code Intelligence:**"),
            "should contain category heading")
        assertTrue(prompt.contains("**VCS:**"),
            "should contain VCS category heading")
    }

    @Test
    fun `omits deferred catalog when null`() {
        val prompt = SystemPrompt.build(
            projectName = "p", projectPath = "/p",
            deferredToolCatalog = null
        )

        assertFalse(prompt.contains("ADDITIONAL TOOLS"),
            "should not contain deferred tools heading when null")
    }

    @Test
    fun `omits deferred catalog when empty`() {
        val prompt = SystemPrompt.build(
            projectName = "p", projectPath = "/p",
            deferredToolCatalog = emptyMap()
        )

        assertFalse(prompt.contains("ADDITIONAL TOOLS"),
            "should not contain deferred tools heading when empty")
    }

    // ---- Workflow nudge tests ----

    @Test
    fun `capabilities section includes workflow-based IDE tool nudges`() {
        val prompt = defaultPrompt()
        val capSection = prompt.substringAfter("CAPABILITIES").substringBefore("====")

        assertTrue(capSection.contains("Understanding code structure"),
            "should include code structure workflow nudge")
        assertTrue(capSection.contains("Refactoring safely"),
            "should include refactoring workflow nudge")
        assertTrue(capSection.contains("Debugging"),
            "should include debugging workflow nudge")
        assertTrue(capSection.contains("Running tests"),
            "should include testing workflow nudge")
    }

    @Test
    fun `capabilities section emphasizes IDE tools as primary`() {
        val prompt = defaultPrompt()
        val capSection = prompt.substringAfter("CAPABILITIES").substringBefore("====")

        assertTrue(capSection.contains("IDE tools are your primary tools"),
            "should emphasize IDE tools as primary in capabilities section")
    }

    @Test
    fun `does not reference VS Code`() {
        val prompt = defaultPrompt()

        assertFalse(
            prompt.lowercase().contains("vs code"),
            "should not reference VS Code"
        )
        assertFalse(
            prompt.lowercase().contains("vscode"),
            "should not reference vscode"
        )
    }

    @Test
    fun `does not reference browser_action`() {
        val prompt = defaultPrompt()

        assertFalse(
            prompt.contains("browser_action"),
            "should not reference browser_action (not applicable in IDE)"
        )
    }

    @Test
    fun `references IntelliJ IDEA as IDE`() {
        val prompt = defaultPrompt()

        assertTrue(
            prompt.contains("IntelliJ IDEA"),
            "should reference IntelliJ IDEA"
        )
    }

    // ---- availableShells in systemInfo tests ----

    @Test
    fun `systemInfo shows Available Shells line when availableShells provided`() {
        val prompt = SystemPrompt.build(
            projectName = "my-app",
            projectPath = "/home/user/my-app",
            availableShells = listOf("bash")
        )
        assertTrue(
            prompt.contains("Available Shells (run_command): bash"),
            "Should show bash as the only available shell"
        )
        assertFalse(prompt.contains("Default Shell:"), "Should not show Default Shell when availableShells is set")
    }

    @Test
    fun `systemInfo shows all shells when all three are available`() {
        val prompt = SystemPrompt.build(
            projectName = "my-app",
            projectPath = "/home/user/my-app",
            availableShells = listOf("bash", "cmd", "powershell")
        )
        assertTrue(
            prompt.contains("Available Shells (run_command): bash, cmd, powershell"),
            "Should list all three shells"
        )
    }

    @Test
    fun `systemInfo falls back to Default Shell when availableShells is null`() {
        val prompt = SystemPrompt.build(
            projectName = "my-app",
            projectPath = "/home/user/my-app"
            // availableShells not passed — defaults to null
        )
        assertTrue(prompt.contains("Default Shell:"), "Should show Default Shell when availableShells is null")
        assertFalse(prompt.contains("Available Shells (run_command)"), "Should not show Available Shells line")
    }

    @Test
    fun `systemInfo falls back to Default Shell when availableShells is empty list`() {
        val prompt = SystemPrompt.build(
            projectName = "my-app",
            projectPath = "/home/user/my-app",
            availableShells = emptyList()
        )
        assertTrue(prompt.contains("Default Shell:"), "Should show Default Shell when availableShells is empty")
        assertFalse(prompt.contains("Available Shells (run_command)"), "Should not show Available Shells line")
    }
}
