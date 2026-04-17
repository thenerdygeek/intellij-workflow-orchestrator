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
    fun `build with minimal params produces valid prompt`() {
        val prompt = SystemPrompt.build(
            projectName = "TestProject",
            projectPath = "/test/project",
            osName = "Linux",
            shell = "/bin/bash"
        )

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

    // ==================== Golden Snapshot Regression Tests ====================

    @Test
    fun `SNAPSHOT null context matches golden file`() {
        val prompt = buildPrompt(null)
        val snapshot = loadSnapshot("null-context")
        assertNotNull(snapshot, "Golden snapshot 'null-context.txt' not found — run 'generate all golden snapshots' first")
        assertEquals(snapshot, prompt,
            "Prompt for null context has changed from golden snapshot. " +
            "If this change is intentional, re-run 'generate all golden snapshots' to update.")
    }

    @Test
    fun `SNAPSHOT IntelliJ Ultimate matches golden file`() {
        val prompt = buildPrompt(intellijUltimate())
        val snapshot = loadSnapshot("intellij-ultimate")
        assertNotNull(snapshot, "Golden snapshot 'intellij-ultimate.txt' not found")
        assertEquals(snapshot, prompt,
            "Prompt for IntelliJ Ultimate has changed from golden snapshot. " +
            "If intentional, re-run 'generate all golden snapshots' to update.")
    }

    @Test
    fun `SNAPSHOT IntelliJ Community matches golden file`() {
        val prompt = buildPrompt(intellijCommunity())
        val snapshot = loadSnapshot("intellij-community")
        assertNotNull(snapshot, "Golden snapshot 'intellij-community.txt' not found")
        assertEquals(snapshot, prompt,
            "Prompt for IntelliJ Community has changed from golden snapshot. " +
            "If intentional, re-run 'generate all golden snapshots' to update.")
    }

    @Test
    fun `SNAPSHOT PyCharm Professional matches golden file`() {
        val prompt = buildPrompt(pycharmProfessional())
        val snapshot = loadSnapshot("pycharm-professional")
        assertNotNull(snapshot, "Golden snapshot 'pycharm-professional.txt' not found")
        assertEquals(snapshot, prompt,
            "Prompt for PyCharm Professional has changed from golden snapshot. " +
            "If intentional, re-run 'generate all golden snapshots' to update.")
    }

    @Test
    fun `SNAPSHOT PyCharm Community matches golden file`() {
        val prompt = buildPrompt(pycharmCommunity())
        val snapshot = loadSnapshot("pycharm-community")
        assertNotNull(snapshot, "Golden snapshot 'pycharm-community.txt' not found")
        assertEquals(snapshot, prompt,
            "Prompt for PyCharm Community has changed from golden snapshot. " +
            "If intentional, re-run 'generate all golden snapshots' to update.")
    }

    @Test
    fun `SNAPSHOT WebStorm matches golden file`() {
        val prompt = buildPrompt(webstorm())
        val snapshot = loadSnapshot("webstorm")
        assertNotNull(snapshot, "Golden snapshot 'webstorm.txt' not found")
        assertEquals(snapshot, prompt,
            "Prompt for WebStorm has changed from golden snapshot. " +
            "If intentional, re-run 'generate all golden snapshots' to update.")
    }

    @Test
    fun `SNAPSHOT IntelliJ Ultimate mixed matches golden file`() {
        val prompt = buildPrompt(intellijUltimateMixed())
        val snapshot = loadSnapshot("intellij-ultimate-mixed")
        assertNotNull(snapshot, "Golden snapshot 'intellij-ultimate-mixed.txt' not found")
        assertEquals(snapshot, prompt,
            "Prompt for IntelliJ Ultimate (mixed Java+Python) has changed from golden snapshot. " +
            "If intentional, re-run 'generate all golden snapshots' to update.")
    }

    // ==================== Cross-Variant Isolation Tests ====================

    @Test
    fun `ISOLATION no Python content in IntelliJ-only snapshots`() {
        val prompt = buildPrompt(intellijUltimate())
        assertFalse(prompt.contains("pytest"), "IntelliJ Ultimate should not mention pytest")
        assertFalse(prompt.contains("python-engineer"), "IntelliJ Ultimate should not mention python-engineer")
        assertFalse(prompt.contains("Django URLs"), "IntelliJ Ultimate should not mention Django URLs")
        assertFalse(prompt.contains("FastAPI routes"), "IntelliJ Ultimate should not mention FastAPI routes")
        assertFalse(prompt.contains("Flask routes"), "IntelliJ Ultimate should not mention Flask routes")
    }

    @Test
    fun `ISOLATION no Java content in PyCharm-only snapshots`() {
        val prompt = buildPrompt(pycharmProfessional())
        assertFalse(prompt.contains("spring-boot-engineer"), "PyCharm should not mention spring-boot-engineer")
        assertFalse(prompt.contains("mvn compile"), "PyCharm should not mention mvn compile")
        assertFalse(prompt.contains("gradlew"), "PyCharm should not mention gradlew")
        assertFalse(prompt.contains("@PostMapping"), "PyCharm should not mention @PostMapping")
        assertFalse(prompt.contains("@Bean"), "PyCharm should not mention @Bean")
    }

    @Test
    fun `ISOLATION WebStorm has no language-specific content`() {
        val prompt = buildPrompt(webstorm())
        assertFalse(prompt.contains("spring-boot-engineer"))
        assertFalse(prompt.contains("python-engineer"))
        assertFalse(prompt.contains("mvn compile"))
        assertFalse(prompt.contains("gradlew"))
        assertFalse(prompt.contains("pytest"))
        assertFalse(prompt.contains("Django"))
        assertFalse(prompt.contains("FastAPI"))
        assertFalse(prompt.contains("Flask"))
        assertFalse(prompt.contains("@PostMapping"))
    }

    @Test
    fun `ISOLATION mixed project has content from both languages`() {
        val prompt = buildPrompt(intellijUltimateMixed())
        // Should have Java content
        assertTrue(prompt.contains("spring-boot-engineer"))
        assertTrue(prompt.contains("mvn compile") || prompt.contains("gradlew"))
        // Should also have Python content
        assertTrue(prompt.contains("python-engineer"))
        assertTrue(prompt.contains("pytest") || prompt.contains("Django"))
    }

    // ==================== Size Bounds Tests ====================

    @Test
    fun `all variants are within acceptable size bounds`() {
        data class VariantSize(val name: String, val context: IdeContext?, val minChars: Int, val maxChars: Int)

        val variants = listOf(
            VariantSize("null", null, 5000, 27000),
            VariantSize("IntelliJ Ultimate", intellijUltimate(), 5000, 27000),
            VariantSize("IntelliJ Community", intellijCommunity(), 5000, 27000),
            VariantSize("PyCharm Professional", pycharmProfessional(), 5000, 27000),
            VariantSize("PyCharm Community", pycharmCommunity(), 5000, 27000),
            VariantSize("WebStorm", webstorm(), 4000, 25000),
            VariantSize("Mixed", intellijUltimateMixed(), 6000, 30000),
        )

        for ((name, context, min, max) in variants) {
            val prompt = buildPrompt(context)
            assertTrue(prompt.length in min..max,
                "$name prompt size ${prompt.length} chars outside bounds [$min, $max]")
        }
    }

    @Test
    fun `WebStorm prompt is smaller than IntelliJ Ultimate prompt`() {
        val webstormPrompt = buildPrompt(webstorm())
        val intellijPrompt = buildPrompt(intellijUltimate())
        assertTrue(webstormPrompt.length < intellijPrompt.length,
            "WebStorm prompt (${webstormPrompt.length}) should be smaller than IntelliJ (${intellijPrompt.length}) — fewer language-specific sections")
    }
}
