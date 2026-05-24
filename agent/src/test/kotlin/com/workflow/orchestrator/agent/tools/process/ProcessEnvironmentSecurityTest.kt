package com.workflow.orchestrator.agent.tools.process

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Security-focused tests for [ProcessEnvironment] addressing Phase 4b audit finding F-4.
 *
 * The core invariant: **BLOCKED ⊇ SENSITIVE**. The LLM must not be able to re-add a
 * credential that was stripped from the inherited environment by supplying it via the
 * `env` parameter.
 */
class ProcessEnvironmentSecurityTest {

    // ─────────────────────────────────────────────────────────────────────────
    //  Invariant: SENSITIVE ⊆ BLOCKED
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `every SENSITIVE var is also in BLOCKED (superset invariant)`() {
        val sensitiveNotBlocked = ProcessEnvironment.SENSITIVE_ENV_VARS.filter { sensitiveKey ->
            val upper = sensitiveKey.uppercase()
            upper !in ProcessEnvironment.BLOCKED_ENV_VARS.map { it.uppercase() }.toSet()
                && !ProcessEnvironment.isCredentialByPattern(sensitiveKey)
        }
        assertTrue(sensitiveNotBlocked.isEmpty()) {
            "SENSITIVE vars not covered by BLOCKED or pattern: $sensitiveNotBlocked"
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  LLM cannot re-inject stripped credentials via env parameter
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    inner class LlmCredentialReinjection {

        @Test
        fun `LLM cannot re-add ANTHROPIC_API_KEY via env parameter`() {
            val input = mapOf("ANTHROPIC_API_KEY" to "stolen")
            val (allowed, rejected) = ProcessEnvironment.filterUserEnv(input)
            assertFalse("ANTHROPIC_API_KEY" in allowed, "ANTHROPIC_API_KEY must not pass filterUserEnv")
            assertTrue("ANTHROPIC_API_KEY" in rejected)
        }

        @Test
        fun `LLM cannot re-add JIRA_TOKEN via env parameter`() {
            val input = mapOf("JIRA_TOKEN" to "evil")
            val (allowed, rejected) = ProcessEnvironment.filterUserEnv(input)
            assertFalse("JIRA_TOKEN" in allowed)
            assertTrue("JIRA_TOKEN" in rejected)
        }

        @Test
        fun `LLM cannot re-add GITHUB_TOKEN via env parameter`() {
            val input = mapOf("GITHUB_TOKEN" to "evil")
            val (allowed, rejected) = ProcessEnvironment.filterUserEnv(input)
            assertFalse("GITHUB_TOKEN" in allowed)
            assertTrue("GITHUB_TOKEN" in rejected)
        }

        @Test
        fun `LLM cannot re-add AWS_SECRET_ACCESS_KEY via env parameter`() {
            val input = mapOf("AWS_SECRET_ACCESS_KEY" to "evil")
            val (allowed, rejected) = ProcessEnvironment.filterUserEnv(input)
            assertFalse("AWS_SECRET_ACCESS_KEY" in allowed)
            assertTrue("AWS_SECRET_ACCESS_KEY" in rejected)
        }

        @Test
        fun `LLM cannot re-add KUBECONFIG via env parameter`() {
            val input = mapOf("KUBECONFIG" to "/tmp/evil-kubeconfig")
            val (allowed, rejected) = ProcessEnvironment.filterUserEnv(input)
            assertFalse("KUBECONFIG" in allowed)
            assertTrue("KUBECONFIG" in rejected)
        }

        @Test
        fun `LLM cannot re-add OPENAI_API_KEY via env parameter`() {
            val input = mapOf("OPENAI_API_KEY" to "sk-stolen")
            val (allowed, rejected) = ProcessEnvironment.filterUserEnv(input)
            assertFalse("OPENAI_API_KEY" in allowed)
            assertTrue("OPENAI_API_KEY" in rejected)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Existing injection vectors still blocked
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `LLM cannot inject LD_PRELOAD`() {
        val input = mapOf("LD_PRELOAD" to "/tmp/evil.so")
        val (allowed, rejected) = ProcessEnvironment.filterUserEnv(input)
        assertFalse("LD_PRELOAD" in allowed)
        assertTrue("LD_PRELOAD" in rejected)
    }

    @Test
    fun `LLM cannot inject DYLD_INSERT_LIBRARIES`() {
        val input = mapOf("DYLD_INSERT_LIBRARIES" to "/tmp/evil.dylib")
        val (allowed, rejected) = ProcessEnvironment.filterUserEnv(input)
        assertFalse("DYLD_INSERT_LIBRARIES" in allowed)
        assertTrue("DYLD_INSERT_LIBRARIES" in rejected)
    }

    @Test
    fun `LLM cannot inject JAVA_TOOL_OPTIONS`() {
        val input = mapOf("JAVA_TOOL_OPTIONS" to "-javaagent:/tmp/evil.jar")
        val (allowed, rejected) = ProcessEnvironment.filterUserEnv(input)
        assertFalse("JAVA_TOOL_OPTIONS" in allowed)
        assertTrue("JAVA_TOOL_OPTIONS" in rejected)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Suffix-pattern blocking
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    inner class SuffixPatternBlocking {

        @Test
        fun `OPENAI_API_KEY is blocked by suffix pattern`() {
            assertTrue(ProcessEnvironment.isCredentialByPattern("OPENAI_API_KEY"))
            val (allowed, _) = ProcessEnvironment.filterUserEnv(mapOf("OPENAI_API_KEY" to "x"))
            assertFalse("OPENAI_API_KEY" in allowed)
        }

        @Test
        fun `FOO_API_KEY is blocked by suffix pattern`() {
            assertTrue(ProcessEnvironment.isCredentialByPattern("FOO_API_KEY"))
            val (allowed, _) = ProcessEnvironment.filterUserEnv(mapOf("FOO_API_KEY" to "x"))
            assertFalse("FOO_API_KEY" in allowed)
        }

        @Test
        fun `MY_SECRET_PASSWORD is blocked by suffix pattern`() {
            assertTrue(ProcessEnvironment.isCredentialByPattern("MY_SECRET_PASSWORD"))
            val (allowed, _) = ProcessEnvironment.filterUserEnv(mapOf("MY_SECRET_PASSWORD" to "x"))
            assertFalse("MY_SECRET_PASSWORD" in allowed)
        }

        @Test
        fun `MY_SERVICE_TOKEN is blocked by suffix pattern`() {
            assertTrue(ProcessEnvironment.isCredentialByPattern("MY_SERVICE_TOKEN"))
            val (allowed, _) = ProcessEnvironment.filterUserEnv(mapOf("MY_SERVICE_TOKEN" to "x"))
            assertFalse("MY_SERVICE_TOKEN" in allowed)
        }

        @Test
        fun `DB_SECRET_KEY is blocked by suffix pattern`() {
            assertTrue(ProcessEnvironment.isCredentialByPattern("DB_SECRET_KEY"))
        }

        @Test
        fun `MY_APP_CONFIG is NOT blocked by suffix pattern`() {
            assertFalse(ProcessEnvironment.isCredentialByPattern("MY_APP_CONFIG"))
            val (allowed, rejected) = ProcessEnvironment.filterUserEnv(mapOf("MY_APP_CONFIG" to "value"))
            assertTrue("MY_APP_CONFIG" in allowed)
            assertTrue(rejected.isEmpty())
        }

        @Test
        fun `DEBUG is NOT blocked`() {
            assertFalse(ProcessEnvironment.isCredentialByPattern("DEBUG"))
            val (allowed, _) = ProcessEnvironment.filterUserEnv(mapOf("DEBUG" to "true"))
            assertTrue("DEBUG" in allowed)
        }

        @Test
        fun `suffix pattern matching is case-insensitive`() {
            // lowercase key with _api_key suffix
            assertTrue(ProcessEnvironment.isCredentialByPattern("my_api_key"))
            val (allowed, _) = ProcessEnvironment.filterUserEnv(mapOf("my_api_key" to "x"))
            assertFalse("my_api_key" in allowed)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Safe LLM-provided vars still allowed
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `safe app config vars are allowed`() {
        val input = mapOf(
            "MY_APP_CONFIG" to "value",
            "LOG_LEVEL" to "info",
            "PORT" to "8080",
            "APP_ENV" to "staging",
        )
        val (allowed, rejected) = ProcessEnvironment.filterUserEnv(input)
        assertEquals(4, allowed.size)
        assertTrue(rejected.isEmpty())
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Inherited env stripping still works
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `inherited ANTHROPIC_API_KEY is stripped from child process env`() {
        val env = mutableMapOf(
            "ANTHROPIC_API_KEY" to "sk-real",
            "SAFE_VAR" to "keep-me",
        )
        ProcessEnvironment.applyToEnvironment(env, isWindows = false)
        assertNull(env["ANTHROPIC_API_KEY"], "ANTHROPIC_API_KEY must be stripped from inherited env")
        assertEquals("keep-me", env["SAFE_VAR"])
    }

    @Test
    fun `all SENSITIVE_ENV_VARS are stripped from inherited env`() {
        val env = mutableMapOf<String, String>()
        for (key in ProcessEnvironment.SENSITIVE_ENV_VARS) {
            env[key] = "value-for-$key"
        }
        env["SAFE_VAR"] = "keep-me"

        ProcessEnvironment.applyToEnvironment(env, isWindows = false)

        for (key in ProcessEnvironment.SENSITIVE_ENV_VARS) {
            assertNull(env[key], "Expected $key to be stripped from inherited env")
        }
        assertEquals("keep-me", env["SAFE_VAR"])
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Set coverage checks
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `SENSITIVE has at least 50 entries (expanded set)`() {
        assertTrue(ProcessEnvironment.SENSITIVE_ENV_VARS.size >= 50) {
            "Expected at least 50 sensitive vars, got ${ProcessEnvironment.SENSITIVE_ENV_VARS.size}"
        }
    }

    @Test
    fun `BLOCKED has at least 60 entries (SENSITIVE union injection vectors)`() {
        assertTrue(ProcessEnvironment.BLOCKED_ENV_VARS.size >= 60) {
            "Expected at least 60 blocked vars, got ${ProcessEnvironment.BLOCKED_ENV_VARS.size}"
        }
    }

    @Test
    fun `BLOCKED contains all classic injection vectors`() {
        val required = listOf(
            "LD_PRELOAD", "LD_LIBRARY_PATH", "LD_AUDIT",
            "DYLD_INSERT_LIBRARIES", "DYLD_LIBRARY_PATH", "DYLD_FRAMEWORK_PATH",
            "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH", "JDK_JAVA_OPTIONS",
            "PYTHONPATH", "NODE_PATH", "PERL5LIB", "RUBYLIB",
            "PATH", "HOME",
        )
        required.forEach { key ->
            assertTrue(key in ProcessEnvironment.BLOCKED_ENV_VARS, "Missing injection vector: $key")
        }
    }

    @Test
    fun `SENSITIVE contains all major cloud provider credentials`() {
        val required = listOf(
            "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN",
            "GOOGLE_APPLICATION_CREDENTIALS",
            "AZURE_CLIENT_SECRET", "AZURE_SUBSCRIPTION_ID",
        )
        required.forEach { key ->
            assertTrue(key in ProcessEnvironment.SENSITIVE_ENV_VARS, "Missing cloud credential: $key")
        }
    }
}
