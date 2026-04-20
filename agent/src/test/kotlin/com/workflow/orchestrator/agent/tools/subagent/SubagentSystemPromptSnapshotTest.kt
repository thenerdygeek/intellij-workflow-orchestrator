// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.agent.tools.subagent

import com.workflow.orchestrator.agent.ide.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Golden snapshot tests for [SubagentSystemPromptBuilder].
 *
 * Mirrors the main-agent [SystemPromptIdeContextTest] snapshot pattern.
 * Five variants cover the (persona × IdeContext) matrix described in Task 6 of Track C.
 *
 * Regenerate all snapshots with:
 *   ./gradlew :agent:test --tests "*SubagentSystemPromptSnapshotTest*generate all golden*"
 *
 * Then validate with:
 *   ./gradlew :agent:test --tests "*SubagentSystemPromptSnapshotTest*"
 */
class SubagentSystemPromptSnapshotTest {

    companion object {
        private val SNAPSHOT_DIR = "src/test/resources/subagent-prompt-snapshots"

        /** Stable completing-your-task section used for all snapshot builds. */
        private val COMPLETING_SECTION = SubagentRunner.COMPLETING_YOUR_TASK_SECTION

        // ---- IDE Context Factories (same pattern as SystemPromptIdeContextTest) ----

        fun intellijUltimate() = IdeContext(
            product = IdeProduct.INTELLIJ_ULTIMATE,
            productName = "IntelliJ IDEA 2025.1 Ultimate",
            edition = Edition.ULTIMATE,
            languages = setOf(Language.JAVA, Language.KOTLIN),
            hasJavaPlugin = true,
            hasPythonPlugin = false,
            hasPythonCorePlugin = false,
            hasSpringPlugin = true,
            detectedFrameworks = setOf(Framework.SPRING),
            detectedBuildTools = setOf(BuildTool.GRADLE),
        )

        fun pycharmProfessional() = IdeContext(
            product = IdeProduct.PYCHARM_PROFESSIONAL,
            productName = "PyCharm 2025.1 Professional",
            edition = Edition.PROFESSIONAL,
            languages = setOf(Language.PYTHON),
            hasJavaPlugin = false,
            hasPythonPlugin = true,
            hasPythonCorePlugin = false,
            hasSpringPlugin = false,
            detectedFrameworks = setOf(Framework.DJANGO),
            detectedBuildTools = setOf(BuildTool.POETRY),
        )

        fun intellijCommunity() = IdeContext(
            product = IdeProduct.INTELLIJ_COMMUNITY,
            productName = "IntelliJ IDEA 2025.1 Community",
            edition = Edition.COMMUNITY,
            languages = setOf(Language.JAVA, Language.KOTLIN),
            hasJavaPlugin = true,
            hasPythonPlugin = false,
            hasPythonCorePlugin = false,
            hasSpringPlugin = false,
            detectedFrameworks = emptySet(),
            detectedBuildTools = setOf(BuildTool.MAVEN),
        )

        // ---- Snapshot helpers ----

        private fun saveSnapshot(name: String, content: String) {
            val file = File("$SNAPSHOT_DIR/$name.txt")
            file.parentFile.mkdirs()
            file.writeText(content)
        }

        private fun loadSnapshot(name: String): String? {
            val file = File("$SNAPSHOT_DIR/$name.txt")
            return if (file.exists() && file.length() > 0) file.readText() else null
        }
    }

    private lateinit var loader: AgentConfigLoader

    @BeforeEach
    fun setUp() {
        AgentConfigLoader.resetForTests()
        loader = AgentConfigLoader.getInstance()
        // Load bundled agents (no user directory needed — resources are on classpath)
        loader.loadFromDisk(AgentConfigLoader.DEFAULT_CONFIG_DIR.also {
            // The directory may not exist in CI; loadFromDisk handles missing dirs gracefully.
        })
    }

    @AfterEach
    fun tearDown() {
        AgentConfigLoader.resetForTests()
    }

    // ---- Persona loading helpers ----

    private fun loadPersona(name: String): AgentConfig {
        // Primary: try cache (populated by loadFromDisk → loadBundledAgents)
        loader.getCachedConfig(name)?.let { return it }

        // Fallback: parse directly from classpath resource (robust against directory absence)
        val resourcePath = "/agents/$name.md"
        val content = AgentConfigLoader::class.java.getResourceAsStream(resourcePath)
            ?.bufferedReader()?.readText()
            ?: error("Bundled persona resource not found: $resourcePath")
        return loader.parseAgentConfigFromYaml(content).copy(bundled = true)
    }

    // ---- Prompt builder helper ----

    private fun buildPrompt(persona: AgentConfig, ideContext: IdeContext?): String =
        SubagentSystemPromptBuilder.build(
            personaRole = persona.systemPrompt,
            agentConfig = persona,
            ideContext = ideContext,
            projectName = "TestProject",
            projectPath = "/tmp/test",
            osName = "Darwin",
            shell = "/bin/bash",
            completingYourTaskSection = COMPLETING_SECTION,
        )

    // ==================== Regeneration Test ====================

    /**
     * Generates (or regenerates) all 5 golden snapshot files.
     *
     * Run explicitly:
     *   ./gradlew :agent:test --tests "*SubagentSystemPromptSnapshotTest*generate all golden*"
     *
     * This test always writes the files and verifies they were created.
     * It does NOT compare against existing content — it replaces it.
     */
    @Test
    fun `generate all golden snapshots`() {
        val codeReviewer = loadPersona("code-reviewer")
        val springBootEngineer = loadPersona("spring-boot-engineer")
        val pythonEngineer = loadPersona("python-engineer")
        val testAutomator = loadPersona("test-automator")
        val architectReviewer = loadPersona("architect-reviewer")

        saveSnapshot("code-reviewer-intellij-ultimate",
            buildPrompt(codeReviewer, intellijUltimate()))
        saveSnapshot("spring-boot-engineer-intellij-ultimate",
            buildPrompt(springBootEngineer, intellijUltimate()))
        saveSnapshot("python-engineer-pycharm-professional",
            buildPrompt(pythonEngineer, pycharmProfessional()))
        saveSnapshot("test-automator-null-context",
            buildPrompt(testAutomator, null))
        saveSnapshot("architect-reviewer-intellij-community",
            buildPrompt(architectReviewer, intellijCommunity()))

        // Verify all 5 files were created and are non-empty
        val dir = File(SNAPSHOT_DIR)
        assertTrue(dir.exists(), "Snapshot directory must exist")
        val snapshots = dir.listFiles { f -> f.extension == "txt" } ?: emptyArray()
        assertEquals(5, snapshots.size, "Should have created exactly 5 snapshot files")
        for (f in snapshots) {
            assertTrue(f.length() > 0, "Snapshot '${f.name}' must be non-empty")
        }
    }

    // ==================== Golden Snapshot Regression Tests ====================

    @Test
    fun `SNAPSHOT code-reviewer IntelliJ Ultimate matches golden file`() {
        val persona = loadPersona("code-reviewer")
        val prompt = buildPrompt(persona, intellijUltimate())
        val snapshot = loadSnapshot("code-reviewer-intellij-ultimate")
        assertNotNull(snapshot,
            "Golden snapshot 'code-reviewer-intellij-ultimate.txt' not found — " +
            "run 'generate all golden snapshots' first")
        assertEquals(snapshot, prompt,
            "Prompt for code-reviewer × IntelliJ Ultimate has changed. " +
            "If intentional, re-run 'generate all golden snapshots' to update.")
    }

    @Test
    fun `SNAPSHOT spring-boot-engineer IntelliJ Ultimate matches golden file`() {
        val persona = loadPersona("spring-boot-engineer")
        val prompt = buildPrompt(persona, intellijUltimate())
        val snapshot = loadSnapshot("spring-boot-engineer-intellij-ultimate")
        assertNotNull(snapshot,
            "Golden snapshot 'spring-boot-engineer-intellij-ultimate.txt' not found — " +
            "run 'generate all golden snapshots' first")
        assertEquals(snapshot, prompt,
            "Prompt for spring-boot-engineer × IntelliJ Ultimate has changed. " +
            "If intentional, re-run 'generate all golden snapshots' to update.")
    }

    @Test
    fun `SNAPSHOT python-engineer PyCharm Professional matches golden file`() {
        val persona = loadPersona("python-engineer")
        val prompt = buildPrompt(persona, pycharmProfessional())
        val snapshot = loadSnapshot("python-engineer-pycharm-professional")
        assertNotNull(snapshot,
            "Golden snapshot 'python-engineer-pycharm-professional.txt' not found — " +
            "run 'generate all golden snapshots' first")
        assertEquals(snapshot, prompt,
            "Prompt for python-engineer × PyCharm Professional has changed. " +
            "If intentional, re-run 'generate all golden snapshots' to update.")
    }

    @Test
    fun `SNAPSHOT test-automator null context matches golden file`() {
        val persona = loadPersona("test-automator")
        val prompt = buildPrompt(persona, null)
        val snapshot = loadSnapshot("test-automator-null-context")
        assertNotNull(snapshot,
            "Golden snapshot 'test-automator-null-context.txt' not found — " +
            "run 'generate all golden snapshots' first")
        assertEquals(snapshot, prompt,
            "Prompt for test-automator × null IdeContext has changed. " +
            "If intentional, re-run 'generate all golden snapshots' to update.")
    }

    @Test
    fun `SNAPSHOT architect-reviewer IntelliJ Community matches golden file`() {
        val persona = loadPersona("architect-reviewer")
        val prompt = buildPrompt(persona, intellijCommunity())
        val snapshot = loadSnapshot("architect-reviewer-intellij-community")
        assertNotNull(snapshot,
            "Golden snapshot 'architect-reviewer-intellij-community.txt' not found — " +
            "run 'generate all golden snapshots' first")
        assertEquals(snapshot, prompt,
            "Prompt for architect-reviewer × IntelliJ Community has changed. " +
            "If intentional, re-run 'generate all golden snapshots' to update.")
    }
}
