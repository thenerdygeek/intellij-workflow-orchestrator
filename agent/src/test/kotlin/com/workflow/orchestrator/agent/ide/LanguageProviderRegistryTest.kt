package com.workflow.orchestrator.agent.ide

import io.mockk.every
import io.mockk.mockk
import com.intellij.psi.PsiFile
import com.intellij.lang.Language as IjLanguage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class LanguageProviderRegistryTest {

    @Test
    fun `register and resolve provider by language id`() {
        val registry = LanguageProviderRegistry()
        val provider = mockk<LanguageIntelligenceProvider>()
        every { provider.supportedLanguageIds } returns setOf("JAVA", "kotlin")

        registry.register(provider)

        assertEquals(provider, registry.forLanguageId("JAVA"))
        assertEquals(provider, registry.forLanguageId("kotlin"))
        assertNull(registry.forLanguageId("Python"))
    }

    @Test
    fun `resolve provider for PsiFile`() {
        val registry = LanguageProviderRegistry()
        val provider = mockk<LanguageIntelligenceProvider>()
        every { provider.supportedLanguageIds } returns setOf("JAVA")

        registry.register(provider)

        val language = mockk<IjLanguage>()
        every { language.id } returns "JAVA"

        val file = mockk<PsiFile>()
        every { file.language } returns language

        assertEquals(provider, registry.forFile(file))
    }

    @Test
    fun `returns null for unsupported language`() {
        val registry = LanguageProviderRegistry()
        assertNull(registry.forLanguageId("Ruby"))
    }

    @Test
    fun `hasProvider returns correct state`() {
        val registry = LanguageProviderRegistry()
        assertFalse(registry.hasProvider("JAVA"))

        val provider = mockk<LanguageIntelligenceProvider>()
        every { provider.supportedLanguageIds } returns setOf("JAVA")
        registry.register(provider)

        assertTrue(registry.hasProvider("JAVA"))
    }

    @Test
    fun `later registration overrides earlier for same language`() {
        val registry = LanguageProviderRegistry()
        val provider1 = mockk<LanguageIntelligenceProvider>()
        val provider2 = mockk<LanguageIntelligenceProvider>()
        every { provider1.supportedLanguageIds } returns setOf("JAVA")
        every { provider2.supportedLanguageIds } returns setOf("JAVA")

        registry.register(provider1)
        registry.register(provider2)

        assertEquals(provider2, registry.forLanguageId("JAVA"))
    }
}
