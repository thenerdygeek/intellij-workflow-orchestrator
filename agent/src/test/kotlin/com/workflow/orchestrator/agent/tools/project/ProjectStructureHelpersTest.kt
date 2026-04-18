package com.workflow.orchestrator.agent.tools.project

import com.intellij.openapi.roots.DependencyScope
import com.intellij.pom.java.LanguageLevel
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ProjectStructureHelpersTest {

    @Test fun `relativizeToProject strips basePath when present`() {
        assertEquals("core/src/Foo.kt", relativizeToProject("/proj/core/src/Foo.kt", "/proj"))
    }

    @Test fun `relativizeToProject returns absolute when no basePath match`() {
        assertEquals("/other/Foo.kt", relativizeToProject("/other/Foo.kt", "/proj"))
    }

    @Test fun `relativizeToProject handles null basePath`() {
        assertEquals("/any/Foo.kt", relativizeToProject("/any/Foo.kt", null))
    }

    @Test fun `relativizeToProject handles exact match`() {
        assertEquals(".", relativizeToProject("/proj", "/proj"))
    }

    @Test fun `sourceRootKindLabel maps all four kinds`() {
        assertEquals("source", sourceRootKindLabel(SourceRootKind.SOURCE))
        assertEquals("test_source", sourceRootKindLabel(SourceRootKind.TEST_SOURCE))
        assertEquals("resource", sourceRootKindLabel(SourceRootKind.RESOURCE))
        assertEquals("test_resource", sourceRootKindLabel(SourceRootKind.TEST_RESOURCE))
    }

    @Test fun `parseSourceRootKind round-trips all labels`() {
        SourceRootKind.entries.forEach { kind ->
            assertEquals(kind, parseSourceRootKind(sourceRootKindLabel(kind)))
        }
    }

    @Test fun `parseSourceRootKind returns null for unknown`() {
        assertNull(parseSourceRootKind("unknown"))
        assertNull(parseSourceRootKind(null))
    }

    // ── parseDependencyScope ─────────────────────────────────────────────────

    @Test
    fun `parseDependencyScope returns COMPILE for compile`() {
        assertEquals(DependencyScope.COMPILE, parseDependencyScope("compile"))
    }

    @Test
    fun `parseDependencyScope returns TEST for test`() {
        assertEquals(DependencyScope.TEST, parseDependencyScope("test"))
    }

    @Test
    fun `parseDependencyScope returns RUNTIME for runtime`() {
        assertEquals(DependencyScope.RUNTIME, parseDependencyScope("runtime"))
    }

    @Test
    fun `parseDependencyScope returns PROVIDED for provided`() {
        assertEquals(DependencyScope.PROVIDED, parseDependencyScope("provided"))
    }

    @Test
    fun `parseDependencyScope is case-insensitive`() {
        assertEquals(DependencyScope.COMPILE, parseDependencyScope("COMPILE"))
    }

    @Test
    fun `parseDependencyScope returns null for unknown string`() {
        assertNull(parseDependencyScope("bogus"))
    }

    @Test
    fun `parseDependencyScope returns null for null input`() {
        assertNull(parseDependencyScope(null))
    }

    @Test
    fun `parseDependencyScope returns null for blank string`() {
        assertNull(parseDependencyScope(""))
    }

    // ── dependencyScopeLabel ─────────────────────────────────────────────────

    @Test
    fun `dependencyScopeLabel returns compile for COMPILE`() {
        assertEquals("compile", dependencyScopeLabel(DependencyScope.COMPILE))
    }

    @Test
    fun `dependencyScopeLabel returns test for TEST`() {
        assertEquals("test", dependencyScopeLabel(DependencyScope.TEST))
    }

    // ── parseLanguageLevel ───────────────────────────────────────────────────

    @Test
    fun `parseLanguageLevel normalises 8 to JDK_1_8`() {
        assertEquals(LanguageLevel.JDK_1_8, parseLanguageLevel("8"))
    }

    @Test
    fun `parseLanguageLevel normalises 11 to JDK_11`() {
        assertEquals(LanguageLevel.JDK_11, parseLanguageLevel("11"))
    }

    @Test
    fun `parseLanguageLevel normalises 17 to JDK_17`() {
        assertEquals(LanguageLevel.JDK_17, parseLanguageLevel("17"))
    }

    @Test
    fun `parseLanguageLevel normalises 21 to JDK_21`() {
        assertEquals(LanguageLevel.JDK_21, parseLanguageLevel("21"))
    }

    @Test
    fun `parseLanguageLevel accepts canonical name JDK_17`() {
        assertEquals(LanguageLevel.JDK_17, parseLanguageLevel("JDK_17"))
    }

    @Test
    fun `parseLanguageLevel accepts canonical name JDK_1_8`() {
        assertEquals(LanguageLevel.JDK_1_8, parseLanguageLevel("JDK_1_8"))
    }

    @Test
    fun `parseLanguageLevel returns null for invalid string`() {
        assertNull(parseLanguageLevel("Java99"))
    }

    @Test
    fun `parseLanguageLevel returns null for null input`() {
        assertNull(parseLanguageLevel(null))
    }

    @Test
    fun `parseLanguageLevel returns null for blank string`() {
        assertNull(parseLanguageLevel(""))
    }
}
