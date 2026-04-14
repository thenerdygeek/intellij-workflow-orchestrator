package com.workflow.orchestrator.agent.prompt

import com.workflow.orchestrator.agent.ide.*
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

    companion object {
        private val SNAPSHOT_DIR = "src/test/resources/prompt-snapshots"

        /** Standard build params used for all snapshots (consistent, reproducible) */
        private fun buildPrompt(ideContext: IdeContext? = null) = SystemPrompt.build(
            projectName = "SnapshotProject",
            projectPath = "/snapshot/project",
            osName = "Linux",
            shell = "/bin/bash",
            ideContext = ideContext,
        )

        private fun saveSnapshot(name: String, content: String) {
            val file = File("$SNAPSHOT_DIR/$name.txt")
            file.parentFile.mkdirs()
            file.writeText(content)
        }

        private fun loadSnapshot(name: String): String? {
            val file = File("$SNAPSHOT_DIR/$name.txt")
            return if (file.exists()) file.readText() else null
        }

        // ---- IDE Context Factories ----

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

        fun pycharmCommunity() = IdeContext(
            product = IdeProduct.PYCHARM_COMMUNITY,
            productName = "PyCharm 2025.1 Community",
            edition = Edition.COMMUNITY,
            languages = setOf(Language.PYTHON),
            hasJavaPlugin = false,
            hasPythonPlugin = false,
            hasPythonCorePlugin = true,
            hasSpringPlugin = false,
            detectedFrameworks = setOf(Framework.FASTAPI),
            detectedBuildTools = setOf(BuildTool.UV),
        )

        fun webstorm() = IdeContext(
            product = IdeProduct.OTHER,
            productName = "WebStorm 2025.1",
            edition = Edition.OTHER,
            languages = emptySet(),
            hasJavaPlugin = false,
            hasPythonPlugin = false,
            hasPythonCorePlugin = false,
            hasSpringPlugin = false,
            detectedFrameworks = emptySet(),
            detectedBuildTools = emptySet(),
        )

        fun intellijUltimateMixed() = IdeContext(
            product = IdeProduct.INTELLIJ_ULTIMATE,
            productName = "IntelliJ IDEA 2025.1 Ultimate",
            edition = Edition.ULTIMATE,
            languages = setOf(Language.JAVA, Language.KOTLIN, Language.PYTHON),
            hasJavaPlugin = true,
            hasPythonPlugin = true,
            hasPythonCorePlugin = false,
            hasSpringPlugin = true,
            detectedFrameworks = setOf(Framework.SPRING, Framework.DJANGO),
            detectedBuildTools = setOf(BuildTool.GRADLE, BuildTool.POETRY),
        )
    }

    @Test
    fun `generate all golden snapshots`() {
        saveSnapshot("null-context", buildPrompt(null))
        saveSnapshot("intellij-ultimate", buildPrompt(intellijUltimate()))
        saveSnapshot("intellij-community", buildPrompt(intellijCommunity()))
        saveSnapshot("pycharm-professional", buildPrompt(pycharmProfessional()))
        saveSnapshot("pycharm-community", buildPrompt(pycharmCommunity()))
        saveSnapshot("webstorm", buildPrompt(webstorm()))
        saveSnapshot("intellij-ultimate-mixed", buildPrompt(intellijUltimateMixed()))

        // Verify all files were created
        val dir = File(SNAPSHOT_DIR)
        assertTrue(dir.exists())
        assertEquals(7, dir.listFiles()?.count { it.extension == "txt" },
            "Should have created 7 snapshot files")
    }

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

    // ==================== IDE Context Tests ====================

    @Test
    fun `null ideContext produces IntelliJ-flavored prompt with Java defaults`() {
        val prompt = SystemPrompt.build(
            projectName = "TestProject",
            projectPath = "/test/project",
            ideContext = null,
        )
        // Null ideContext defaults to IntelliJ IDEA with Java/Spring content
        assertTrue(prompt.contains("IntelliJ"))
        assertTrue(prompt.contains("spring-boot-engineer"))
        assertTrue(prompt.contains("mvn compile") || prompt.contains("gradlew"))
    }

    @Test
    fun `IntelliJ Ultimate prompt contains Java and Spring content`() {
        val context = IdeContext(
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
        val prompt = SystemPrompt.build(
            projectName = "TestProject",
            projectPath = "/test/project",
            ideContext = context,
        )
        assertTrue(prompt.contains("IntelliJ"))
        assertTrue(prompt.contains("Spring") || prompt.contains("spring"))
        assertTrue(prompt.contains("spring-boot-engineer"))
        assertFalse(prompt.contains("pytest"))
        assertFalse(prompt.contains("python-engineer"))
    }

    @Test
    fun `PyCharm prompt has Python content without Java content`() {
        val context = IdeContext(
            product = IdeProduct.PYCHARM_COMMUNITY,
            productName = "PyCharm 2025.1 Community",
            edition = Edition.COMMUNITY,
            languages = setOf(Language.PYTHON),
            hasJavaPlugin = false,
            hasPythonPlugin = false,
            hasPythonCorePlugin = true,
            hasSpringPlugin = false,
            detectedFrameworks = setOf(Framework.DJANGO),
            detectedBuildTools = setOf(BuildTool.POETRY),
        )
        val prompt = SystemPrompt.build(
            projectName = "TestProject",
            projectPath = "/test/project",
            ideContext = context,
        )
        assertTrue(prompt.contains("PyCharm"))
        assertTrue(prompt.contains("Python") || prompt.contains("python"))
        assertTrue(prompt.contains("Django") || prompt.contains("django"))
        assertFalse(prompt.contains("spring-boot-engineer"))
        assertFalse(prompt.contains("mvn compile"))
        assertFalse(prompt.contains("gradlew"))
    }

    @Test
    fun `WebStorm prompt has minimal language-specific content`() {
        val context = IdeContext(
            product = IdeProduct.OTHER,
            productName = "WebStorm 2025.1",
            edition = Edition.OTHER,
            languages = emptySet(),
            hasJavaPlugin = false,
            hasPythonPlugin = false,
            hasPythonCorePlugin = false,
            hasSpringPlugin = false,
            detectedFrameworks = emptySet(),
            detectedBuildTools = emptySet(),
        )
        val prompt = SystemPrompt.build(
            projectName = "TestProject",
            projectPath = "/test/project",
            ideContext = context,
        )
        assertTrue(prompt.contains("WebStorm"))
        assertFalse(prompt.contains("spring-boot-engineer"))
        assertFalse(prompt.contains("python-engineer"))
        assertFalse(prompt.contains("mvn compile"))
        assertFalse(prompt.contains("pytest"))
    }

    // ==================== Task-to-Tool Hints Tests ====================

    @Test
    fun `capabilities includes task-to-tool hints table`() {
        val prompt = SystemPrompt.build(
            projectName = "TestProject",
            projectPath = "/test/project",
        )
        assertTrue(prompt.contains("When to Use Specialized Tools"))
        assertTrue(prompt.contains("type_hierarchy"))
        assertTrue(prompt.contains("call_hierarchy"))
        assertTrue(prompt.contains("refactor_rename"))
    }

    @Test
    fun `IntelliJ capabilities includes Spring endpoint hints`() {
        val context = IdeContext(
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
        val prompt = SystemPrompt.build(
            projectName = "TestProject",
            projectPath = "/test/project",
            ideContext = context,
        )
        assertTrue(prompt.contains("Find API endpoints"))
        assertTrue(prompt.contains("spring"))
    }

    @Test
    fun `PyCharm with Django capabilities includes Django hints`() {
        val context = IdeContext(
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
        val prompt = SystemPrompt.build(
            projectName = "TestProject",
            projectPath = "/test/project",
            ideContext = context,
        )
        assertTrue(prompt.contains("Django URLs/views") || prompt.contains("django"))
        assertFalse(prompt.contains("Find API endpoints") && prompt.contains("spring"))
    }

    @Test
    fun `prompt sections are properly ordered`() {
        val prompt = SystemPrompt.build(
            projectName = "TestProject",
            projectPath = "/test/project",
        )
        val agentRoleIdx = prompt.indexOf("AI coding agent")
        val capabilitiesIdx = prompt.indexOf("CAPABILITIES")
        val rulesIdx = prompt.indexOf("RULES")
        val systemInfoIdx = prompt.indexOf("SYSTEM INFORMATION")
        val objectiveIdx = prompt.indexOf("OBJECTIVE")
        assertTrue(agentRoleIdx < capabilitiesIdx, "Agent role before capabilities")
        assertTrue(capabilitiesIdx < rulesIdx, "Capabilities before rules")
        assertTrue(rulesIdx < systemInfoIdx, "Rules before system info")
        assertTrue(systemInfoIdx < objectiveIdx, "System info before objective")
    }
}
