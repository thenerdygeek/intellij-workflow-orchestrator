package com.workflow.orchestrator.agent.orchestrator

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolRegistry
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PromptAssemblerTest {

    private lateinit var registry: ToolRegistry
    private lateinit var assembler: PromptAssembler

    @BeforeEach
    fun setUp() {
        registry = ToolRegistry()
        registry.register(FakeToolForPrompt("read_file", "Read a file from disk"))
        registry.register(FakeToolForPrompt("edit_file", "Edit a file with precise string replacement"))
        registry.register(FakeToolForPrompt("run_command", "Run a shell command"))
        assembler = PromptAssembler(registry)
    }

    @Test
    fun `builds prompt with all sections when all parameters provided`() {
        val prompt = assembler.buildSingleAgentPrompt(
            projectName = "my-service",
            projectPath = "/home/user/my-service",
            frameworkInfo = "Spring Boot 3.2, Java 21, Maven",
            previousStepResults = listOf("Analyzed UserService.kt — 3 methods found")
        )

        // Identity + core directives
        assertTrue(prompt.contains("AI coding assistant"), "Should contain core identity")
        assertTrue(prompt.contains("<core_directives>"), "Should contain core directives block")
        assertTrue(prompt.contains("Persistence"), "Should contain persistence directive")

        // Mandatory sections
        assertTrue(prompt.contains("<tool_policy>"), "Should contain tool policy section")
        assertTrue(prompt.contains("<persistence>"), "Should contain persistence section")
        assertTrue(prompt.contains("<available_agents>"), "Should contain available agents section")

        // Project context
        assertTrue(prompt.contains("<project_context>"), "Should contain project context")
        assertTrue(prompt.contains("Project: my-service"), "Should contain project name")
        assertTrue(prompt.contains("Path: /home/user/my-service"), "Should contain project path")
        assertTrue(prompt.contains("Framework: Spring Boot 3.2, Java 21, Maven"), "Should contain framework")

        // Previous results
        assertTrue(prompt.contains("<previous_results>"), "Should contain previous results")
        assertTrue(prompt.contains("Analyzed UserService.kt"), "Should contain the result summary")

        // Rules + bookend
        assertTrue(prompt.contains("<rules>"), "Should contain rules section")
        assertTrue(prompt.contains("old_string in edit_file"), "Should contain edit rules")
        assertTrue(prompt.contains("<final_reminders>"), "Should contain bookend at the end")
    }

    @Test
    fun `tool policy is present`() {
        val prompt = assembler.buildSingleAgentPrompt()

        // Tool policy should explain how tools work
        assertTrue(prompt.contains("<tool_policy>"), "Should have tool policy section")
        // Tool definitions are sent via API tools parameter, not in system prompt
        assertFalse(prompt.contains("Read a file from disk"), "Tool descriptions should not be in system prompt (sent via API)")
    }

    @Test
    fun `project context is included when provided`() {
        val prompt = assembler.buildSingleAgentPrompt(
            projectName = "checkout-service",
            frameworkInfo = "Spring Boot 3.1, Java 17"
        )

        assertTrue(prompt.contains("<project_context>"), "Should have project context section")
        assertTrue(prompt.contains("Project: checkout-service"))
        assertTrue(prompt.contains("Framework: Spring Boot 3.1, Java 17"))
        assertTrue(prompt.contains("OS:"), "Should include OS info")
        assertTrue(prompt.contains("Java:"), "Should include Java version")
    }

    @Test
    fun `previous results are included in orchestrated mode`() {
        val results = listOf(
            "Step 1: Found 5 files matching the pattern",
            "Step 2: Identified UserService as the entry point"
        )
        val prompt = assembler.buildSingleAgentPrompt(previousStepResults = results)

        assertTrue(prompt.contains("<previous_results>"), "Should have previous results section")
        assertTrue(prompt.contains("Context from previous steps:"))
        assertTrue(prompt.contains("- Step 1: Found 5 files matching the pattern"))
        assertTrue(prompt.contains("- Step 2: Identified UserService as the entry point"))
    }

    @Test
    fun `prompt without optional sections still has identity and rules`() {
        val prompt = assembler.buildSingleAgentPrompt()

        // Must have identity + directives
        assertTrue(prompt.contains("AI coding assistant"), "Should contain core identity")
        assertTrue(prompt.contains("<core_directives>"), "Should contain core directives")

        // Must have mandatory sections
        assertTrue(prompt.contains("<tool_policy>"), "Should contain tool policy")
        assertTrue(prompt.contains("<persistence>"), "Should contain persistence")
        assertTrue(prompt.contains("<available_agents>"), "Should contain available agents (always present)")

        // Must have rules
        assertTrue(prompt.contains("<rules>"), "Should contain rules")

        // Should NOT have optional sections
        assertFalse(prompt.contains("<project_context>"), "Should not have project context")
        assertFalse(prompt.contains("<previous_results>"), "Should not have previous results")
        assertFalse(prompt.contains("<repo_map>"), "Should not have repo map")
    }

    @Test
    fun `buildOrchestrationStepPrompt includes only specified tools`() {
        val prompt = assembler.buildOrchestrationStepPrompt(
            stepDescription = "Read the UserService file and analyze its structure",
            previousResults = listOf("Found UserService.kt at src/main/kotlin/..."),
            availableToolNames = listOf("read_file")
        )

        assertTrue(prompt.contains("<current_step>"), "Should contain step description")
        assertTrue(prompt.contains("Read the UserService file"), "Should contain the step text")
        assertTrue(prompt.contains("read_file"), "Should include the allowed tool")
        // edit_file and run_command should not appear in available_tools
        // (they may appear in CORE_IDENTITY text, so check within the tools section)
        val toolsSection = prompt.substringAfter("<available_tools>").substringBefore("</available_tools>")
        assertFalse(toolsSection.contains("edit_file"), "Should not include edit_file in tools")
        assertFalse(toolsSection.contains("run_command"), "Should not include run_command in tools")
    }

    @Test
    fun `buildOrchestrationStepPrompt without previous results omits section`() {
        val prompt = assembler.buildOrchestrationStepPrompt(
            stepDescription = "Initial analysis step",
            previousResults = emptyList(),
            availableToolNames = listOf("read_file", "edit_file")
        )

        assertFalse(prompt.contains("<previous_results>"), "Should not have previous results when empty")
        assertTrue(prompt.contains("<current_step>"), "Should contain step")
        assertTrue(prompt.contains("<rules>"), "Should contain rules")
    }

    @Test
    fun `empty tool registry still has tool policy`() {
        val emptyRegistry = ToolRegistry()
        val emptyAssembler = PromptAssembler(emptyRegistry)

        val prompt = emptyAssembler.buildSingleAgentPrompt()
        assertTrue(prompt.contains("<tool_policy>"), "Should still have tool policy section")
        assertTrue(prompt.contains("request_tools"), "Should mention request_tools")
    }

    // --- Step 2/5: Repo map injection ---

    @Test
    fun `repo map is included when provided`() {
        val repoMap = """
            com.example.service/
              UserService @Service
                + createUser(CreateUserRequest): User
                + findById(Long): User?
        """.trimIndent()

        val prompt = assembler.buildSingleAgentPrompt(repoMapContext = repoMap)

        assertTrue(prompt.contains("<repo_map>"), "Should contain repo_map section")
        assertTrue(prompt.contains("UserService @Service"), "Should contain class info")
        assertTrue(prompt.contains("createUser"), "Should contain method info")
    }

    @Test
    fun `blank repo map is not included`() {
        val prompt = assembler.buildSingleAgentPrompt(repoMapContext = "")
        assertFalse(prompt.contains("<repo_map>"), "Should not have repo_map section for blank input")
    }

    @Test
    fun `null repo map is not included`() {
        val prompt = assembler.buildSingleAgentPrompt(repoMapContext = null)
        assertFalse(prompt.contains("<repo_map>"), "Should not have repo_map section for null input")
    }

    // --- Multi-repo awareness ---

    @Test
    fun `repo context is included when provided`() {
        val repoCtx = "This project has 2 configured repositories:\n- backend (primary) — Bamboo: PROJ-BACK"
        val prompt = assembler.buildSingleAgentPrompt(repoContext = repoCtx)

        assertTrue(prompt.contains("<project_repositories>"), "Should contain project_repositories section")
        assertTrue(prompt.contains("backend (primary)"), "Should contain repo details")
    }

    @Test
    fun `blank repo context is not included`() {
        val prompt = assembler.buildSingleAgentPrompt(repoContext = "")
        assertFalse(prompt.contains("<project_repositories>"), "Should not have project_repositories for blank input")
    }

    @Test
    fun `null repo context is not included`() {
        val prompt = assembler.buildSingleAgentPrompt(repoContext = null)
        assertFalse(prompt.contains("<project_repositories>"), "Should not have project_repositories for null input")
    }

    @Test
    fun `rules contain multi-repo guidance`() {
        val prompt = assembler.buildSingleAgentPrompt()
        assertTrue(prompt.contains("multiple repositories"), "Should contain multi-repo rule")
        assertTrue(prompt.contains("bitbucket(action=\"list_repos\")"), "Should mention bitbucket(action=\"list_repos\") for discovery")
    }

    // --- Few-shot examples ---

    @Test
    fun `prompt contains few-shot examples`() {
        val prompt = assembler.buildSingleAgentPrompt()
        assertTrue(prompt.contains("<examples>"), "Should contain examples section")
        assertTrue(prompt.contains("open-ended-exploration"), "Should contain open-ended exploration example")
        assertTrue(prompt.contains("targeted-lookup"), "Should contain targeted lookup example")
        assertTrue(prompt.contains("edit-with-verification"), "Should contain edit verification example")
        assertTrue(prompt.contains("error-recovery"), "Should contain error recovery example")
        assertTrue(prompt.contains("when-to-plan"), "Should contain planning example")
        assertTrue(prompt.contains("multi-file-implementation"), "Should contain multi-file implementation example")
        assertTrue(prompt.contains("parallel-research"), "Should contain parallel research example")
        assertTrue(prompt.contains("review-before-complete"), "Should contain review before complete example")
    }

    // --- Bookend (U-shaped attention) ---

    @Test
    fun `prompt has bookend at the end`() {
        val prompt = assembler.buildSingleAgentPrompt()
        assertTrue(prompt.contains("<final_reminders>"), "Should contain final reminders bookend")
        // Bookend should appear AFTER rules (U-shaped attention)
        val rulesPos = prompt.indexOf("<rules>")
        val bookendPos = prompt.indexOf("<final_reminders>")
        assertTrue(bookendPos > rulesPos, "Final reminders bookend should appear after rules")
    }

    // --- Anti-loop rules ---

    @Test
    fun `rules contain anti-loop guidance`() {
        val prompt = assembler.buildSingleAgentPrompt()

        assertTrue(prompt.contains("same tool 3 times"), "Should contain anti-loop rule")
        assertTrue(prompt.contains("different approach"), "Should mention trying different approach")
    }

    // --- Available agents ---

    @Test
    fun `prompt always contains available_agents`() {
        val prompt = assembler.buildSingleAgentPrompt()

        assertTrue(prompt.contains("<available_agents>"), "Should contain available_agents section")
        assertTrue(prompt.contains("explorer"), "Should contain built-in explorer agent")
        assertTrue(prompt.contains("coder"), "Should contain built-in coder agent")
    }

    // --- Persistence section ---

    @Test
    fun `prompt contains persistence section`() {
        val prompt = assembler.buildSingleAgentPrompt()

        assertTrue(prompt.contains("<persistence>"), "Should contain persistence section")
    }
}

private class FakeToolForPrompt(
    override val name: String,
    override val description: String,
    override val allowedWorkers: Set<WorkerType> = setOf(WorkerType.CODER),
    override val parameters: FunctionParameters = FunctionParameters(properties = emptyMap())
) : AgentTool {
    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        return ToolResult(content = "fake", summary = "fake", tokenEstimate = 5)
    }
}
