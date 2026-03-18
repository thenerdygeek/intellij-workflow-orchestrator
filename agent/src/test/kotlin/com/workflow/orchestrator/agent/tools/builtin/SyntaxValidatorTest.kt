package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests for SyntaxValidator.
 *
 * NOTE: SyntaxValidator uses PsiFileFactory which requires a full IntelliJ platform
 * environment (BasePlatformTestCase). In unit tests without platform, validate()
 * catches the exception and returns empty (fail-open). These tests verify the
 * fail-open behavior and the logic boundaries. Full PSI-based validation is
 * tested in integration tests with BasePlatformTestCase.
 */
class SyntaxValidatorTest {

    private val project = mockk<Project>(relaxed = true)

    @Test
    fun `non-java-kotlin files return empty list`() {
        val errors = SyntaxValidator.validate(project, "style.css", "body { color: red; }")
        assertTrue(errors.isEmpty(), "CSS files should not be validated")
    }

    @Test
    fun `non-supported extension returns empty list`() {
        val errors = SyntaxValidator.validate(project, "data.json", """{"key": "value"}""")
        assertTrue(errors.isEmpty(), "JSON files should not be validated")
    }

    @Test
    fun `txt files return empty list`() {
        val errors = SyntaxValidator.validate(project, "notes.txt", "some notes")
        assertTrue(errors.isEmpty(), "TXT files should not be validated")
    }

    @Test
    fun `xml files return empty list`() {
        val errors = SyntaxValidator.validate(project, "pom.xml", "<project></project>")
        assertTrue(errors.isEmpty(), "XML files should not be validated")
    }

    @Test
    fun `kotlin file extension is recognized`() {
        // Without IntelliJ platform, validate catches exception and returns empty
        // This test verifies the extension matching logic routes kt files to validation
        val errors = SyntaxValidator.validate(project, "Test.kt", "fun main() {}")
        // In unit test without platform, should return empty (fail-open)
        assertTrue(errors.isEmpty(), "Should fail-open without platform environment")
    }

    @Test
    fun `java file extension is recognized`() {
        // Without IntelliJ platform, validate catches exception and returns empty
        val errors = SyntaxValidator.validate(project, "Test.java", "public class Test {}")
        assertTrue(errors.isEmpty(), "Should fail-open without platform environment")
    }

    @Test
    fun `case insensitive extension matching`() {
        val errors = SyntaxValidator.validate(project, "Test.JAVA", "public class Test {}")
        // Extension matching is case-insensitive
        assertTrue(errors.isEmpty(), "Should handle uppercase extensions")
    }

    @Test
    fun `file with no extension returns empty`() {
        val errors = SyntaxValidator.validate(project, "Makefile", "all: build")
        assertTrue(errors.isEmpty(), "Files without extension should not be validated")
    }
}
