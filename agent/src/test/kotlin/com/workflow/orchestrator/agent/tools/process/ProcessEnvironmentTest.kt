package com.workflow.orchestrator.agent.tools.process

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ProcessEnvironmentTest {

    // ──────────────────────────────────────────────
    // SENSITIVE_ENV_VARS — credential leak prevention
    // ──────────────────────────────────────────────

    @Nested
    inner class SensitiveEnvVars {

        @Test
        fun `contains critical API keys`() {
            val expected = listOf(
                "ANTHROPIC_API_KEY", "OPENAI_API_KEY", "SOURCEGRAPH_TOKEN",
                "GITHUB_TOKEN", "GH_TOKEN", "GITLAB_TOKEN", "BITBUCKET_TOKEN",
            )
            expected.forEach { key ->
                assertTrue(key in ProcessEnvironment.SENSITIVE_ENV_VARS, "Missing sensitive var: $key")
            }
        }

        @Test
        fun `contains cloud provider credentials`() {
            val expected = listOf(
                "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN", "AWS_ACCESS_KEY_ID",
                "AZURE_CLIENT_SECRET", "AZURE_SUBSCRIPTION_ID",
                "GOOGLE_APPLICATION_CREDENTIALS",
            )
            expected.forEach { key ->
                assertTrue(key in ProcessEnvironment.SENSITIVE_ENV_VARS, "Missing sensitive var: $key")
            }
        }

        @Test
        fun `contains SSH credentials`() {
            val expected = listOf("SSH_AUTH_SOCK", "SSH_PRIVATE_KEY", "SSH_KEY_PATH")
            expected.forEach { key ->
                assertTrue(key in ProcessEnvironment.SENSITIVE_ENV_VARS, "Missing sensitive var: $key")
            }
        }

        @Test
        fun `contains database credentials`() {
            val expected = listOf("DATABASE_URL", "PGPASSWORD", "MYSQL_PWD")
            expected.forEach { key ->
                assertTrue(key in ProcessEnvironment.SENSITIVE_ENV_VARS, "Missing sensitive var: $key")
            }
        }

        @Test
        fun `contains infrastructure secrets`() {
            val expected = listOf(
                "KUBECONFIG", "VAULT_TOKEN", "VAULT_ADDR",
                "DOCKER_PASSWORD", "DOCKER_CONFIG",
                "GITHUB_APP_PRIVATE_KEY",
            )
            expected.forEach { key ->
                assertTrue(key in ProcessEnvironment.SENSITIVE_ENV_VARS, "Missing sensitive var: $key")
            }
        }

        @Test
        fun `contains CI and package manager tokens`() {
            val expected = listOf(
                "NPM_TOKEN", "NUGET_API_KEY",
                "SONAR_TOKEN", "JIRA_TOKEN", "BAMBOO_TOKEN",
                "HEROKU_API_KEY", "TWILIO_AUTH_TOKEN", "SLACK_BOT_TOKEN",
            )
            expected.forEach { key ->
                assertTrue(key in ProcessEnvironment.SENSITIVE_ENV_VARS, "Missing sensitive var: $key")
            }
        }

        @Test
        fun `has at least 35 entries`() {
            assertTrue(
                ProcessEnvironment.SENSITIVE_ENV_VARS.size >= 35,
                "Expected at least 35 sensitive vars, got ${ProcessEnvironment.SENSITIVE_ENV_VARS.size}"
            )
        }
    }

    // ──────────────────────────────────────────────
    // BLOCKED_ENV_VARS — injection prevention
    // ──────────────────────────────────────────────

    @Nested
    inner class BlockedEnvVars {

        @Test
        fun `contains system path and shell vars`() {
            val expected = listOf("PATH", "HOME", "SHELL", "TERM", "USER", "LOGNAME", "USERNAME")
            expected.forEach { key ->
                assertTrue(key in ProcessEnvironment.BLOCKED_ENV_VARS, "Missing blocked var: $key")
            }
        }

        @Test
        fun `contains Windows system vars`() {
            val expected = listOf("SYSTEMROOT", "COMSPEC", "WINDIR", "APPDATA", "LOCALAPPDATA")
            expected.forEach { key ->
                assertTrue(key in ProcessEnvironment.BLOCKED_ENV_VARS, "Missing blocked var: $key")
            }
        }

        @Test
        fun `contains library injection vectors`() {
            val expected = listOf(
                "LD_PRELOAD", "LD_LIBRARY_PATH",
                "DYLD_INSERT_LIBRARIES", "DYLD_LIBRARY_PATH",
            )
            expected.forEach { key ->
                assertTrue(key in ProcessEnvironment.BLOCKED_ENV_VARS, "Missing blocked var: $key")
            }
        }

        @Test
        fun `contains JVM injection vectors`() {
            val expected = listOf("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH")
            expected.forEach { key ->
                assertTrue(key in ProcessEnvironment.BLOCKED_ENV_VARS, "Missing blocked var: $key")
            }
        }

        @Test
        fun `contains language path overrides`() {
            val expected = listOf(
                "PYTHONPATH", "NODE_PATH", "GOPATH", "CARGO_HOME",
                "PERL5LIB", "RUBYLIB",
            )
            expected.forEach { key ->
                assertTrue(key in ProcessEnvironment.BLOCKED_ENV_VARS, "Missing blocked var: $key")
            }
        }

        @Test
        fun `contains build tool home overrides`() {
            val expected = listOf("MAVEN_HOME", "GRADLE_HOME", "GRADLE_USER_HOME")
            expected.forEach { key ->
                assertTrue(key in ProcessEnvironment.BLOCKED_ENV_VARS, "Missing blocked var: $key")
            }
        }

        @Test
        fun `has at least 25 entries`() {
            assertTrue(
                ProcessEnvironment.BLOCKED_ENV_VARS.size >= 25,
                "Expected at least 25 blocked vars, got ${ProcessEnvironment.BLOCKED_ENV_VARS.size}"
            )
        }
    }

    // ──────────────────────────────────────────────
    // filterUserEnv — LLM-provided env filtering
    // ──────────────────────────────────────────────

    @Nested
    inner class FilterUserEnv {

        @Test
        fun `rejects blocked vars`() {
            val input = mapOf("PATH" to "/evil", "HOME" to "/tmp", "MY_VAR" to "ok")
            val (allowed, rejected) = ProcessEnvironment.filterUserEnv(input)

            assertFalse("PATH" in allowed)
            assertFalse("HOME" in allowed)
            assertEquals("ok", allowed["MY_VAR"])
            assertTrue("PATH" in rejected)
            assertTrue("HOME" in rejected)
        }

        @Test
        fun `is case-insensitive for blocked vars`() {
            val input = mapOf(
                "path" to "/evil",
                "Path" to "/evil2",
                "LD_PRELOAD" to "/lib.so",
                "ld_preload" to "/lib2.so",
                "SAFE_VAR" to "value",
            )
            val (allowed, rejected) = ProcessEnvironment.filterUserEnv(input)

            assertEquals(1, allowed.size, "Only SAFE_VAR should pass")
            assertEquals("value", allowed["SAFE_VAR"])
            assertTrue(rejected.any { it.equals("path", ignoreCase = true) })
            assertTrue(rejected.any { it.equals("ld_preload", ignoreCase = true) })
        }

        @Test
        fun `passes all safe vars through`() {
            val input = mapOf(
                "MY_APP_CONFIG" to "value1",
                "DEBUG" to "true",
                "LOG_LEVEL" to "info",
            )
            val (allowed, rejected) = ProcessEnvironment.filterUserEnv(input)

            assertEquals(3, allowed.size)
            assertTrue(rejected.isEmpty())
            assertEquals("value1", allowed["MY_APP_CONFIG"])
        }

        @Test
        fun `returns empty maps for empty input`() {
            val (allowed, rejected) = ProcessEnvironment.filterUserEnv(emptyMap())
            assertTrue(allowed.isEmpty())
            assertTrue(rejected.isEmpty())
        }

        @Test
        fun `rejects all blocked categories`() {
            // One representative from each blocked category
            val input = mapOf(
                "PATH" to "x",                   // system
                "COMSPEC" to "x",                 // Windows
                "LD_PRELOAD" to "x",              // library injection
                "JAVA_TOOL_OPTIONS" to "x",       // JVM injection
                "PYTHONPATH" to "x",              // language path
                "GRADLE_HOME" to "x",             // build tool
            )
            val (allowed, rejected) = ProcessEnvironment.filterUserEnv(input)

            assertTrue(allowed.isEmpty(), "No blocked vars should pass")
            assertEquals(6, rejected.size)
        }
    }

    // ──────────────────────────────────────────────
    // antiInteractiveEnv — pager/editor suppression
    // ──────────────────────────────────────────────

    @Nested
    inner class AntiInteractiveEnv {

        @Test
        fun `includes pager suppression`() {
            val env = ProcessEnvironment.antiInteractiveEnv(isWindows = false)
            assertEquals("cat", env["PAGER"])
            assertEquals("cat", env["GIT_PAGER"])
            assertEquals("cat", env["MANPAGER"])
            assertEquals("", env["SYSTEMD_PAGER"])
        }

        @Test
        fun `includes editor suppression`() {
            val env = ProcessEnvironment.antiInteractiveEnv(isWindows = false)
            assertEquals("cat", env["EDITOR"])
            assertEquals("cat", env["VISUAL"])
            assertEquals("cat", env["GIT_EDITOR"])
        }

        @Test
        fun `includes terminal and color suppression`() {
            val env = ProcessEnvironment.antiInteractiveEnv(isWindows = false)
            assertEquals("-FRX", env["LESS"])
            assertEquals("dumb", env["TERM"])
            assertEquals("1", env["NO_COLOR"])
        }

        @Test
        fun `includes git non-interactive settings`() {
            val env = ProcessEnvironment.antiInteractiveEnv(isWindows = false)
            assertEquals("ssh -o BatchMode=yes", env["GIT_SSH_COMMAND"])
            assertEquals("0", env["GIT_TERMINAL_PROMPT"])
        }

        @Test
        fun `includes npm and python settings`() {
            val env = ProcessEnvironment.antiInteractiveEnv(isWindows = false)
            assertEquals("false", env["NPM_CONFIG_INTERACTIVE"])
            assertEquals("utf-8", env["PYTHONIOENCODING"])
        }

        @Test
        fun `uses bin-false for GIT_ASKPASS on Unix`() {
            val env = ProcessEnvironment.antiInteractiveEnv(isWindows = false)
            assertEquals("/bin/false", env["GIT_ASKPASS"])
        }

        @Test
        fun `uses echo for GIT_ASKPASS on Windows`() {
            val env = ProcessEnvironment.antiInteractiveEnv(isWindows = true)
            assertEquals("echo", env["GIT_ASKPASS"])
        }

        @Test
        fun `Windows and Unix share all non-platform entries`() {
            val unix = ProcessEnvironment.antiInteractiveEnv(isWindows = false)
            val windows = ProcessEnvironment.antiInteractiveEnv(isWindows = true)

            // Only GIT_ASKPASS should differ
            val unixWithout = unix.filterKeys { it != "GIT_ASKPASS" }
            val windowsWithout = windows.filterKeys { it != "GIT_ASKPASS" }
            assertEquals(unixWithout, windowsWithout)
        }
    }

    // ──────────────────────────────────────────────
    // applyToEnvironment — layered env composition
    // ──────────────────────────────────────────────

    @Nested
    inner class ApplyToEnvironment {

        @Test
        fun `strips sensitive vars`() {
            val env = mutableMapOf(
                "ANTHROPIC_API_KEY" to "sk-secret",
                "GITHUB_TOKEN" to "ghp_secret",
                "SAFE_VAR" to "keep-me",
            )
            ProcessEnvironment.applyToEnvironment(env, isWindows = false)

            assertNull(env["ANTHROPIC_API_KEY"])
            assertNull(env["GITHUB_TOKEN"])
            assertEquals("keep-me", env["SAFE_VAR"])
        }

        @Test
        fun `applies anti-interactive overrides`() {
            val env = mutableMapOf<String, String>()
            ProcessEnvironment.applyToEnvironment(env, isWindows = false)

            assertEquals("cat", env["PAGER"])
            assertEquals("cat", env["GIT_PAGER"])
            assertEquals("dumb", env["TERM"])
            assertEquals("/bin/false", env["GIT_ASKPASS"])
        }

        @Test
        fun `applies user overrides on top`() {
            val env = mutableMapOf<String, String>()
            val userOverrides = mapOf("MY_VAR" to "custom-value", "DEBUG" to "1")
            ProcessEnvironment.applyToEnvironment(env, isWindows = false, userOverrides = userOverrides)

            assertEquals("custom-value", env["MY_VAR"])
            assertEquals("1", env["DEBUG"])
        }

        @Test
        fun `user overrides can override anti-interactive values`() {
            val env = mutableMapOf<String, String>()
            val userOverrides = mapOf("PAGER" to "less")
            ProcessEnvironment.applyToEnvironment(env, isWindows = false, userOverrides = userOverrides)

            // User override should win over anti-interactive
            assertEquals("less", env["PAGER"])
        }

        @Test
        fun `correct order — sensitive stripped before anti-interactive applied`() {
            val env = mutableMapOf(
                "ANTHROPIC_API_KEY" to "secret",
                "PAGER" to "more", // will be overridden by anti-interactive
            )
            ProcessEnvironment.applyToEnvironment(env, isWindows = false)

            assertNull(env["ANTHROPIC_API_KEY"])
            assertEquals("cat", env["PAGER"]) // anti-interactive override wins
        }

        @Test
        fun `correct order — user overrides applied last`() {
            val env = mutableMapOf(
                "ANTHROPIC_API_KEY" to "secret",
                "PAGER" to "more",
            )
            val userOverrides = mapOf("PAGER" to "bat")
            ProcessEnvironment.applyToEnvironment(env, isWindows = false, userOverrides = userOverrides)

            assertNull(env["ANTHROPIC_API_KEY"]) // still stripped
            assertEquals("bat", env["PAGER"]) // user override wins over anti-interactive
        }

        @Test
        fun `Windows GIT_ASKPASS applied correctly`() {
            val env = mutableMapOf<String, String>()
            ProcessEnvironment.applyToEnvironment(env, isWindows = true)

            assertEquals("echo", env["GIT_ASKPASS"])
        }

        @Test
        fun `empty user overrides are a no-op`() {
            val env = mutableMapOf("SAFE_VAR" to "value")
            ProcessEnvironment.applyToEnvironment(env, isWindows = false, userOverrides = emptyMap())

            assertEquals("value", env["SAFE_VAR"])
            assertEquals("cat", env["PAGER"]) // anti-interactive still applied
        }

        @Test
        fun `all three layers compose correctly`() {
            val env = mutableMapOf(
                "ANTHROPIC_API_KEY" to "sk-secret",       // Layer 1: will be stripped
                "AWS_SECRET_ACCESS_KEY" to "aws-secret",  // Layer 1: will be stripped
                "KUBECONFIG" to "/home/.kube/config",     // Layer 1: will be stripped
                "EXISTING_VAR" to "original",             // Untouched
            )
            val userOverrides = mapOf(
                "MY_APP_PORT" to "8080",     // Layer 3: user addition
                "TERM" to "xterm-256color",  // Layer 3: overrides anti-interactive TERM=dumb
            )

            ProcessEnvironment.applyToEnvironment(env, isWindows = false, userOverrides = userOverrides)

            // Layer 1: sensitive stripped
            assertNull(env["ANTHROPIC_API_KEY"])
            assertNull(env["AWS_SECRET_ACCESS_KEY"])
            assertNull(env["KUBECONFIG"])
            // Untouched
            assertEquals("original", env["EXISTING_VAR"])
            // Layer 2: anti-interactive applied
            assertEquals("cat", env["PAGER"])
            assertEquals("cat", env["GIT_PAGER"])
            // Layer 3: user overrides win
            assertEquals("8080", env["MY_APP_PORT"])
            assertEquals("xterm-256color", env["TERM"]) // user override beats anti-interactive
        }
    }

    // ──────────────────────────────────────────────
    // Set disjointness — sensitive and blocked don't overlap
    // ──────────────────────────────────────────────

    @Test
    fun `sensitive and blocked sets are disjoint`() {
        val overlap = ProcessEnvironment.SENSITIVE_ENV_VARS.intersect(ProcessEnvironment.BLOCKED_ENV_VARS)
        assertTrue(overlap.isEmpty(), "Sensitive and blocked sets should not overlap: $overlap")
    }
}
