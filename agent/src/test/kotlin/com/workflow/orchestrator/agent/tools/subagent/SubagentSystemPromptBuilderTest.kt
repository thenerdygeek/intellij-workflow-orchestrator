package com.workflow.orchestrator.agent.tools.subagent

import com.workflow.orchestrator.agent.ide.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for [SubagentSystemPromptBuilder].
 *
 * All tests are self-contained — no IntelliJ platform services required.
 * IdeContext and AgentConfig instances are constructed inline.
 */
class SubagentSystemPromptBuilderTest {

    // ---- Fixtures ----

    private val DUMMY_COMPLETING_SECTION =
        "COMPLETING YOUR TASK\n\nCall task_report when done."

    private val PERSONA_ROLE = "You are a specialist code reviewer focused on security."

    private fun buildPrompt(
        personaRole: String = PERSONA_ROLE,
        ideContext: IdeContext? = null,
        agentConfig: AgentConfig? = null,
    ): String = SubagentSystemPromptBuilder.build(
        personaRole = personaRole,
        agentConfig = agentConfig,
        ideContext = ideContext,
        projectName = "TestProject",
        projectPath = "/tmp/test",
        osName = "Linux",
        shell = "/bin/bash",
        completingYourTaskSection = DUMMY_COMPLETING_SECTION,
    )

    private fun pyCharmProfessionalContext() = IdeContext(
        product = IdeProduct.PYCHARM_PROFESSIONAL,
        productName = "PyCharm Professional",
        edition = Edition.PROFESSIONAL,
        languages = setOf(Language.PYTHON),
        hasJavaPlugin = false,
        hasPythonPlugin = true,
        hasPythonCorePlugin = false,
        hasSpringPlugin = false,
        detectedFrameworks = emptySet(),
        detectedBuildTools = emptySet(),
    )

    // ---- Tests ----

    @Test
    fun `builds prompt with persona role at start`() {
        val prompt = buildPrompt()
        // personaRole must appear before the first section separator
        val firstSep = prompt.indexOf("\n\n====\n\n")
        assertTrue(firstSep > 0, "Expected a section separator in the output")
        val beforeFirstSep = prompt.substring(0, firstSep)
        assertTrue(
            beforeFirstSep.contains(PERSONA_ROLE),
            "personaRole text should appear before the first section separator"
        )
    }

    @Test
    fun `omits TASK MANAGEMENT section`() {
        val prompt = buildPrompt()
        assertFalse(
            prompt.contains("TASK MANAGEMENT"),
            "Sub-agent prompt must NOT contain TASK MANAGEMENT section"
        )
    }

    @Test
    fun `omits ACT MODE V_S_ PLAN MODE section`() {
        val prompt = buildPrompt()
        assertFalse(
            prompt.contains("ACT MODE V.S. PLAN MODE"),
            "Sub-agent prompt must NOT contain ACT MODE V.S. PLAN MODE section"
        )
    }

    @Test
    fun `omits Subagent Delegation subsection`() {
        val prompt = buildPrompt()
        assertFalse(
            prompt.contains("# Subagent Delegation"),
            "Sub-agent prompt must NOT contain the Subagent Delegation subsection"
        )
    }

    @Test
    fun `includes RULES header`() {
        val prompt = buildPrompt()
        assertTrue(
            prompt.contains("RULES"),
            "Sub-agent prompt MUST contain the RULES section"
        )
    }

    @Test
    fun `includes SYSTEM INFORMATION`() {
        val prompt = buildPrompt()
        assertTrue(
            prompt.contains("SYSTEM INFORMATION"),
            "Sub-agent prompt MUST contain the SYSTEM INFORMATION section"
        )
    }

    @Test
    fun `ends with completingYourTaskSection`() {
        val prompt = buildPrompt()
        assertTrue(
            prompt.endsWith(DUMMY_COMPLETING_SECTION),
            "Prompt should end with the completingYourTaskSection string"
        )
    }

    @Test
    fun `passes ideContext through`() {
        val prompt = buildPrompt(ideContext = pyCharmProfessionalContext())
        assertTrue(
            prompt.contains("PyCharm Professional"),
            "Output should contain the productName from the supplied IdeContext"
        )
    }

    @Test
    fun `agentConfig with memory none suppresses memory XML blocks`() {
        val config = AgentConfig(
            name = "test",
            description = "test",
            tools = emptyList(),
            skills = null,
            modelId = null,
            systemPrompt = PERSONA_ROLE,
            promptSections = PromptSectionsConfig(memory = "none"),
        )
        val coreXml = "<core_memory><item key=\"k\">some-core-data</item></core_memory>"
        val prompt = SubagentSystemPromptBuilder.build(
            personaRole = PERSONA_ROLE,
            agentConfig = config,
            ideContext = null,
            projectName = "TestProject",
            projectPath = "/tmp/test",
            osName = "Linux",
            shell = "/bin/bash",
            coreMemoryXml = coreXml,
            recalledMemoryXml = "<recalled_memory>recalled-data</recalled_memory>",
            completingYourTaskSection = DUMMY_COMPLETING_SECTION,
        )
        assertFalse(
            prompt.contains("some-core-data"),
            "memory: none must suppress coreMemoryXml content"
        )
        assertFalse(
            prompt.contains("recalled-data"),
            "memory: none must suppress recalledMemoryXml content"
        )
    }

    @Test
    fun `agentConfig with capabilities false omits CAPABILITIES section`() {
        val config = AgentConfig(
            name = "test",
            description = "test",
            tools = emptyList(),
            skills = null,
            modelId = null,
            systemPrompt = PERSONA_ROLE,
            promptSections = PromptSectionsConfig(capabilities = false),
        )
        val prompt = SubagentSystemPromptBuilder.build(
            personaRole = PERSONA_ROLE,
            agentConfig = config,
            ideContext = null,
            projectName = "TestProject",
            projectPath = "/tmp/test",
            osName = "Linux",
            shell = "/bin/bash",
            completingYourTaskSection = DUMMY_COMPLETING_SECTION,
        )
        assertFalse(
            prompt.contains("CAPABILITIES"),
            "capabilities: false must omit the CAPABILITIES section header"
        )
    }
}
