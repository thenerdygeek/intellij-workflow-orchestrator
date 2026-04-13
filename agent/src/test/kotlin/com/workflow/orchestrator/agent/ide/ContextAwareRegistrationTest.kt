package com.workflow.orchestrator.agent.ide

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ContextAwareRegistrationTest {

    @Test
    fun `shouldRegisterJavaPsiTools returns true for IntelliJ with Java plugin`() {
        val context = makeContext(product = IdeProduct.INTELLIJ_ULTIMATE, hasJavaPlugin = true)
        assertTrue(ToolRegistrationFilter.shouldRegisterJavaPsiTools(context))
    }

    @Test
    fun `shouldRegisterJavaPsiTools returns false for PyCharm`() {
        val context = makeContext(product = IdeProduct.PYCHARM_PROFESSIONAL, hasJavaPlugin = false)
        assertFalse(ToolRegistrationFilter.shouldRegisterJavaPsiTools(context))
    }

    @Test
    fun `shouldRegisterJavaPsiTools returns false for OTHER IDE without Java`() {
        val context = makeContext(product = IdeProduct.OTHER, hasJavaPlugin = false)
        assertFalse(ToolRegistrationFilter.shouldRegisterJavaPsiTools(context))
    }

    @Test
    fun `shouldRegisterSpringTools returns true for IU with Spring plugin`() {
        val context = makeContext(product = IdeProduct.INTELLIJ_ULTIMATE, hasJavaPlugin = true, hasSpringPlugin = true)
        assertTrue(ToolRegistrationFilter.shouldRegisterSpringTools(context))
    }

    @Test
    fun `shouldRegisterSpringTools returns false for IC without Spring`() {
        val context = makeContext(product = IdeProduct.INTELLIJ_COMMUNITY, hasJavaPlugin = true, hasSpringPlugin = false)
        assertFalse(ToolRegistrationFilter.shouldRegisterSpringTools(context))
    }

    @Test
    fun `shouldRegisterSpringTools returns false for PyCharm even with Spring`() {
        val context = makeContext(product = IdeProduct.PYCHARM_PROFESSIONAL, hasJavaPlugin = false, hasSpringPlugin = true)
        assertFalse(ToolRegistrationFilter.shouldRegisterSpringTools(context))
    }

    @Test
    fun `shouldRegisterJavaBuildTools returns true for IntelliJ`() {
        val context = makeContext(product = IdeProduct.INTELLIJ_COMMUNITY, hasJavaPlugin = true, detectedBuildTools = setOf(BuildTool.MAVEN))
        assertTrue(ToolRegistrationFilter.shouldRegisterJavaBuildTools(context))
    }

    @Test
    fun `shouldRegisterJavaBuildTools returns false for PyCharm`() {
        val context = makeContext(product = IdeProduct.PYCHARM_COMMUNITY, hasJavaPlugin = false)
        assertFalse(ToolRegistrationFilter.shouldRegisterJavaBuildTools(context))
    }

    @Test
    fun `shouldRegisterJavaDebugTools returns true for IntelliJ`() {
        val context = makeContext(product = IdeProduct.INTELLIJ_COMMUNITY, hasJavaPlugin = true)
        assertTrue(ToolRegistrationFilter.shouldRegisterJavaDebugTools(context))
    }

    @Test
    fun `shouldRegisterJavaDebugTools returns false for PyCharm`() {
        val context = makeContext(product = IdeProduct.PYCHARM_PROFESSIONAL, hasJavaPlugin = false)
        assertFalse(ToolRegistrationFilter.shouldRegisterJavaDebugTools(context))
    }

    @Test
    fun `shouldPromoteFrameworkTool returns true for detected framework`() {
        val context = makeContext(detectedFrameworks = setOf(Framework.SPRING), hasSpringPlugin = true, hasJavaPlugin = true)
        assertTrue(ToolRegistrationFilter.shouldPromoteFrameworkTool(context, Framework.SPRING))
    }

    @Test
    fun `shouldPromoteFrameworkTool returns false for undetected framework`() {
        val context = makeContext(detectedFrameworks = emptySet())
        assertFalse(ToolRegistrationFilter.shouldPromoteFrameworkTool(context, Framework.DJANGO))
    }

    @Test
    fun `shouldRegisterCoverageTool returns true for Ultimate`() {
        val context = makeContext(product = IdeProduct.INTELLIJ_ULTIMATE)
        assertTrue(ToolRegistrationFilter.shouldRegisterCoverageTool(context))
    }

    @Test
    fun `shouldRegisterCoverageTool returns true for Professional`() {
        val context = makeContext(product = IdeProduct.PYCHARM_PROFESSIONAL)
        assertTrue(ToolRegistrationFilter.shouldRegisterCoverageTool(context))
    }

    @Test
    fun `shouldRegisterCoverageTool returns false for Community`() {
        val context = makeContext(product = IdeProduct.INTELLIJ_COMMUNITY)
        assertFalse(ToolRegistrationFilter.shouldRegisterCoverageTool(context))
    }

    @Test
    fun `shouldRegisterCoverageTool returns false for OTHER`() {
        val context = makeContext(product = IdeProduct.OTHER)
        assertFalse(ToolRegistrationFilter.shouldRegisterCoverageTool(context))
    }

    @Test
    fun `database tools always register regardless of IDE`() {
        for (product in IdeProduct.entries) {
            val context = makeContext(product = product)
            assertTrue(ToolRegistrationFilter.shouldRegisterDatabaseTools(context), "Database tools should register for $product")
        }
    }

    @Test
    fun `universal tools always register regardless of IDE`() {
        for (product in IdeProduct.entries) {
            val context = makeContext(product = product)
            assertTrue(ToolRegistrationFilter.shouldRegisterUniversalTools(context), "Universal tools should register for $product")
        }
    }

    // --- Python filter tests ---

    @Test
    fun `shouldRegisterPythonPsiTools returns true for PyCharm`() {
        val context = makeContext(product = IdeProduct.PYCHARM_PROFESSIONAL, hasPythonPlugin = true)
        assertTrue(ToolRegistrationFilter.shouldRegisterPythonPsiTools(context))
    }

    @Test
    fun `shouldRegisterPythonPsiTools returns false for IntelliJ without Python`() {
        val context = makeContext(product = IdeProduct.INTELLIJ_COMMUNITY, hasJavaPlugin = true)
        assertFalse(ToolRegistrationFilter.shouldRegisterPythonPsiTools(context))
    }

    @Test
    fun `shouldRegisterDjangoTools returns true when Django detected in PyCharm`() {
        val context = makeContext(
            product = IdeProduct.PYCHARM_COMMUNITY,
            hasPythonCorePlugin = true,
            detectedFrameworks = setOf(Framework.DJANGO),
        )
        assertTrue(ToolRegistrationFilter.shouldRegisterDjangoTools(context))
    }

    @Test
    fun `shouldRegisterDjangoTools returns false when Django not detected`() {
        val context = makeContext(product = IdeProduct.PYCHARM_PROFESSIONAL, hasPythonPlugin = true)
        assertFalse(ToolRegistrationFilter.shouldRegisterDjangoTools(context))
    }

    @Test
    fun `shouldRegisterPythonAdvancedDebugTools requires Professional Python plugin`() {
        val communityContext = makeContext(product = IdeProduct.PYCHARM_COMMUNITY, hasPythonCorePlugin = true)
        assertFalse(ToolRegistrationFilter.shouldRegisterPythonAdvancedDebugTools(communityContext))

        val proContext = makeContext(product = IdeProduct.PYCHARM_PROFESSIONAL, hasPythonPlugin = true)
        assertTrue(ToolRegistrationFilter.shouldRegisterPythonAdvancedDebugTools(proContext))
    }

    // --- Python debug registration (Phase C5) ---

    @Test
    fun `debug tools register for PyCharm Community with Python core plugin`() {
        val context = makeContext(product = IdeProduct.PYCHARM_COMMUNITY, hasPythonCorePlugin = true)
        val hasDebug = ToolRegistrationFilter.shouldRegisterJavaDebugTools(context) ||
            ToolRegistrationFilter.shouldRegisterPythonDebugTools(context)
        assertTrue(hasDebug)
    }

    @Test
    fun `debug tools register for PyCharm Professional`() {
        val context = makeContext(product = IdeProduct.PYCHARM_PROFESSIONAL, hasPythonPlugin = true)
        val hasDebug = ToolRegistrationFilter.shouldRegisterJavaDebugTools(context) ||
            ToolRegistrationFilter.shouldRegisterPythonDebugTools(context)
        assertTrue(hasDebug)
    }

    @Test
    fun `debug tools register for IntelliJ with Java plugin`() {
        val context = makeContext(product = IdeProduct.INTELLIJ_ULTIMATE, hasJavaPlugin = true)
        val hasDebug = ToolRegistrationFilter.shouldRegisterJavaDebugTools(context) ||
            ToolRegistrationFilter.shouldRegisterPythonDebugTools(context)
        assertTrue(hasDebug)
    }

    @Test
    fun `debug tools do NOT register for OTHER IDE with no language plugins`() {
        val context = makeContext(product = IdeProduct.OTHER)
        val hasDebug = ToolRegistrationFilter.shouldRegisterJavaDebugTools(context) ||
            ToolRegistrationFilter.shouldRegisterPythonDebugTools(context)
        assertFalse(hasDebug)
    }

    @Test
    fun `shouldRegisterPythonDebugTools returns false for IntelliJ without Python`() {
        val context = makeContext(product = IdeProduct.INTELLIJ_COMMUNITY, hasJavaPlugin = true)
        assertFalse(ToolRegistrationFilter.shouldRegisterPythonDebugTools(context))
    }

    private fun makeContext(
        product: IdeProduct = IdeProduct.INTELLIJ_ULTIMATE,
        hasJavaPlugin: Boolean = false,
        hasPythonPlugin: Boolean = false,
        hasPythonCorePlugin: Boolean = false,
        hasSpringPlugin: Boolean = false,
        detectedFrameworks: Set<Framework> = emptySet(),
        detectedBuildTools: Set<BuildTool> = emptySet(),
    ): IdeContext = IdeContext(
        product = product,
        productName = product.name,
        edition = IdeContextDetector.classifyEdition(product),
        languages = IdeContextDetector.deriveLanguages(product, hasJavaPlugin, hasPythonPlugin || hasPythonCorePlugin),
        hasJavaPlugin = hasJavaPlugin,
        hasPythonPlugin = hasPythonPlugin,
        hasPythonCorePlugin = hasPythonCorePlugin,
        hasSpringPlugin = hasSpringPlugin,
        detectedFrameworks = detectedFrameworks,
        detectedBuildTools = detectedBuildTools,
    )
}
