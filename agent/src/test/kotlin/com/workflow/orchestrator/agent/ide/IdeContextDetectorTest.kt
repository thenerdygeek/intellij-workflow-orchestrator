package com.workflow.orchestrator.agent.ide

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class IdeContextDetectorTest {

    @Test
    fun `classifyProduct returns INTELLIJ_ULTIMATE for IU code`() {
        val result = IdeContextDetector.classifyProduct("IU")
        assertEquals(IdeProduct.INTELLIJ_ULTIMATE, result)
    }

    @Test
    fun `classifyProduct returns INTELLIJ_COMMUNITY for IC code`() {
        val result = IdeContextDetector.classifyProduct("IC")
        assertEquals(IdeProduct.INTELLIJ_COMMUNITY, result)
    }

    @Test
    fun `classifyProduct returns PYCHARM_PROFESSIONAL for PY code`() {
        val result = IdeContextDetector.classifyProduct("PY")
        assertEquals(IdeProduct.PYCHARM_PROFESSIONAL, result)
    }

    @Test
    fun `classifyProduct returns PYCHARM_COMMUNITY for PC code`() {
        val result = IdeContextDetector.classifyProduct("PC")
        assertEquals(IdeProduct.PYCHARM_COMMUNITY, result)
    }

    @Test
    fun `classifyProduct returns OTHER for unknown code`() {
        val result = IdeContextDetector.classifyProduct("WS")
        assertEquals(IdeProduct.OTHER, result)
    }

    @Test
    fun `classifyProduct returns OTHER for GoLand`() {
        val result = IdeContextDetector.classifyProduct("GO")
        assertEquals(IdeProduct.OTHER, result)
    }

    @Test
    fun `classifyEdition returns ULTIMATE for IU`() {
        val result = IdeContextDetector.classifyEdition(IdeProduct.INTELLIJ_ULTIMATE)
        assertEquals(Edition.ULTIMATE, result)
    }

    @Test
    fun `classifyEdition returns COMMUNITY for IC`() {
        val result = IdeContextDetector.classifyEdition(IdeProduct.INTELLIJ_COMMUNITY)
        assertEquals(Edition.COMMUNITY, result)
    }

    @Test
    fun `classifyEdition returns PROFESSIONAL for PY`() {
        val result = IdeContextDetector.classifyEdition(IdeProduct.PYCHARM_PROFESSIONAL)
        assertEquals(Edition.PROFESSIONAL, result)
    }

    @Test
    fun `classifyEdition returns COMMUNITY for PC`() {
        val result = IdeContextDetector.classifyEdition(IdeProduct.PYCHARM_COMMUNITY)
        assertEquals(Edition.COMMUNITY, result)
    }

    @Test
    fun `classifyEdition returns OTHER for unknown products`() {
        val result = IdeContextDetector.classifyEdition(IdeProduct.OTHER)
        assertEquals(Edition.OTHER, result)
    }

    @Test
    fun `deriveLanguages returns JAVA and KOTLIN for IntelliJ products`() {
        val result = IdeContextDetector.deriveLanguages(
            IdeProduct.INTELLIJ_ULTIMATE,
            hasJavaPlugin = true,
            hasPythonPlugin = false
        )
        assertEquals(setOf(Language.JAVA, Language.KOTLIN), result)
    }

    @Test
    fun `deriveLanguages returns PYTHON for PyCharm products`() {
        val result = IdeContextDetector.deriveLanguages(
            IdeProduct.PYCHARM_PROFESSIONAL,
            hasJavaPlugin = false,
            hasPythonPlugin = true
        )
        assertEquals(setOf(Language.PYTHON), result)
    }

    @Test
    fun `deriveLanguages returns JAVA KOTLIN and PYTHON for IU with Python plugin`() {
        val result = IdeContextDetector.deriveLanguages(
            IdeProduct.INTELLIJ_ULTIMATE,
            hasJavaPlugin = true,
            hasPythonPlugin = true
        )
        assertEquals(setOf(Language.JAVA, Language.KOTLIN, Language.PYTHON), result)
    }

    @Test
    fun `deriveLanguages returns empty set for OTHER with no plugins`() {
        val result = IdeContextDetector.deriveLanguages(
            IdeProduct.OTHER,
            hasJavaPlugin = false,
            hasPythonPlugin = false
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `IdeContext summary for IntelliJ Ultimate`() {
        val context = IdeContext(
            product = IdeProduct.INTELLIJ_ULTIMATE,
            productName = "IntelliJ IDEA Ultimate",
            edition = Edition.ULTIMATE,
            languages = setOf(Language.JAVA, Language.KOTLIN),
            hasJavaPlugin = true,
            hasPythonPlugin = false,
            hasPythonCorePlugin = false,
            hasSpringPlugin = true,
            detectedFrameworks = setOf(Framework.SPRING),
            detectedBuildTools = setOf(BuildTool.GRADLE),
        )
        val summary = context.summary()
        assertTrue(summary.contains("IntelliJ IDEA Ultimate"))
        assertTrue(summary.contains("Java"))
        assertTrue(summary.contains("Spring"))
        assertTrue(summary.contains("gradle"))
    }

    @Test
    fun `IdeContext summary for PyCharm Community`() {
        val context = IdeContext(
            product = IdeProduct.PYCHARM_COMMUNITY,
            productName = "PyCharm Community",
            edition = Edition.COMMUNITY,
            languages = setOf(Language.PYTHON),
            hasJavaPlugin = false,
            hasPythonPlugin = false,
            hasPythonCorePlugin = true,
            hasSpringPlugin = false,
            detectedFrameworks = setOf(Framework.DJANGO),
            detectedBuildTools = setOf(BuildTool.POETRY),
        )
        val summary = context.summary()
        assertTrue(summary.contains("PyCharm Community"))
        assertTrue(summary.contains("Python"))
        assertTrue(summary.contains("Django"))
        assertTrue(summary.contains("poetry"))
    }

    @Test
    fun `IdeContext supportsJava returns true for IntelliJ`() {
        val context = IdeContext(
            product = IdeProduct.INTELLIJ_COMMUNITY,
            productName = "IntelliJ IDEA Community",
            edition = Edition.COMMUNITY,
            languages = setOf(Language.JAVA, Language.KOTLIN),
            hasJavaPlugin = true,
            hasPythonPlugin = false,
            hasPythonCorePlugin = false,
            hasSpringPlugin = false,
            detectedFrameworks = emptySet(),
            detectedBuildTools = setOf(BuildTool.MAVEN),
        )
        assertTrue(context.supportsJava)
        assertFalse(context.supportsPython)
    }

    @Test
    fun `IdeContext supportsPython returns true for PyCharm`() {
        val context = IdeContext(
            product = IdeProduct.PYCHARM_PROFESSIONAL,
            productName = "PyCharm Professional",
            edition = Edition.PROFESSIONAL,
            languages = setOf(Language.PYTHON),
            hasJavaPlugin = false,
            hasPythonPlugin = true,
            hasPythonCorePlugin = false,
            hasSpringPlugin = false,
            detectedFrameworks = emptySet(),
            detectedBuildTools = setOf(BuildTool.PIP),
        )
        assertFalse(context.supportsJava)
        assertTrue(context.supportsPython)
        assertTrue(context.supportsPythonAdvanced)
    }

    @Test
    fun `IdeContext supportsPythonAdvanced false for Community Python`() {
        val context = IdeContext(
            product = IdeProduct.PYCHARM_COMMUNITY,
            productName = "PyCharm Community",
            edition = Edition.COMMUNITY,
            languages = setOf(Language.PYTHON),
            hasJavaPlugin = false,
            hasPythonPlugin = false,
            hasPythonCorePlugin = true,
            hasSpringPlugin = false,
            detectedFrameworks = emptySet(),
            detectedBuildTools = emptySet(),
        )
        assertTrue(context.supportsPython)
        assertFalse(context.supportsPythonAdvanced)
    }

    @Test
    fun `hasPyTestConfigType defaults to false when not set`() {
        val context = IdeContext(
            product = IdeProduct.INTELLIJ_COMMUNITY,
            productName = "IntelliJ IDEA Community",
            edition = Edition.COMMUNITY,
            languages = setOf(Language.JAVA, Language.KOTLIN),
            hasJavaPlugin = true,
            hasPythonPlugin = false,
            hasPythonCorePlugin = false,
            hasSpringPlugin = false,
            detectedFrameworks = emptySet(),
            detectedBuildTools = emptySet(),
        )
        assertFalse(context.hasPyTestConfigType)
    }

    @Test
    fun `hasPyTestConfigType is true when explicitly set`() {
        val context = IdeContext(
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
            hasPyTestConfigType = true,
        )
        assertTrue(context.hasPyTestConfigType)
    }
}
