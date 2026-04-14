package com.workflow.orchestrator.agent.tools.process

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

class ShellResolverTest {

    // ──────────────────────────────────────────────
    // resolve() — Unix defaults and explicit shells
    // ──────────────────────────────────────────────

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `resolve with null shell defaults to bash on Unix`() {
        val config = ShellResolver.resolve(requestedShell = null, project = null)
        assertEquals(ShellType.BASH, config.shellType)
        assertTrue(config.executable.endsWith("bash") || config.executable.endsWith("sh"))
        assertTrue(config.args.contains("-c"))
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `resolve with explicit bash works on Unix`() {
        val config = ShellResolver.resolve(requestedShell = "bash", project = null)
        assertEquals(ShellType.BASH, config.shellType)
        assertTrue(config.args.contains("-c"))
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `resolve with cmd on Unix throws ShellUnavailableException`() {
        val ex = assertThrows(ShellUnavailableException::class.java) {
            ShellResolver.resolve(requestedShell = "cmd", project = null)
        }
        assertTrue(ex.message!!.contains("bash"), "Error message should list available shells")
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `resolve with powershell on Unix throws ShellUnavailableException`() {
        val ex = assertThrows(ShellUnavailableException::class.java) {
            ShellResolver.resolve(requestedShell = "powershell", project = null)
        }
        assertTrue(ex.message!!.contains("bash"), "Error message should list available shells")
    }

    // ──────────────────────────────────────────────
    // detectAvailableShells()
    // ──────────────────────────────────────────────

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `detectAvailableShells includes bash on Unix`() {
        val shells = ShellResolver.detectAvailableShells(project = null)
        assertTrue(shells.any { it.shellType == ShellType.BASH })
    }

    // ──────────────────────────────────────────────
    // resolveBashExecutable()
    // ──────────────────────────────────────────────

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `resolveBashExecutable finds a valid path on Unix`() {
        val path = ShellResolver.resolveBashExecutable()
        assertTrue(path == "/bin/bash" || path == "/bin/sh" || path == "sh" || path.isNotEmpty())
    }

    // ──────────────────────────────────────────────
    // isLikelyBuildCommand()
    // ──────────────────────────────────────────────

    @Test
    fun `isLikelyBuildCommand detects gradle commands`() {
        assertTrue(ShellResolver.isLikelyBuildCommand("gradle build"))
        assertTrue(ShellResolver.isLikelyBuildCommand("./gradlew :core:test"))
        assertTrue(ShellResolver.isLikelyBuildCommand("gradlew assemble"))
    }

    @Test
    fun `isLikelyBuildCommand detects maven commands`() {
        assertTrue(ShellResolver.isLikelyBuildCommand("mvn clean install"))
        assertTrue(ShellResolver.isLikelyBuildCommand("./mvnw package"))
        assertTrue(ShellResolver.isLikelyBuildCommand("mvnw verify"))
    }

    @Test
    fun `isLikelyBuildCommand detects npm yarn pnpm commands`() {
        assertTrue(ShellResolver.isLikelyBuildCommand("npm run build"))
        assertTrue(ShellResolver.isLikelyBuildCommand("yarn install"))
        assertTrue(ShellResolver.isLikelyBuildCommand("pnpm test"))
    }

    @Test
    fun `isLikelyBuildCommand detects docker build`() {
        assertTrue(ShellResolver.isLikelyBuildCommand("docker build -t myimage ."))
    }

    @Test
    fun `isLikelyBuildCommand detects pytest`() {
        assertTrue(ShellResolver.isLikelyBuildCommand("pytest tests/"))
        assertTrue(ShellResolver.isLikelyBuildCommand("python -m pytest"))
    }

    @Test
    fun `isLikelyBuildCommand detects cargo go dotnet make cmake`() {
        assertTrue(ShellResolver.isLikelyBuildCommand("cargo build"))
        assertTrue(ShellResolver.isLikelyBuildCommand("go build ./..."))
        assertTrue(ShellResolver.isLikelyBuildCommand("dotnet build"))
        assertTrue(ShellResolver.isLikelyBuildCommand("make all"))
        assertTrue(ShellResolver.isLikelyBuildCommand("cmake .."))
    }

    @Test
    fun `isLikelyBuildCommand matches keyword test`() {
        assertTrue(ShellResolver.isLikelyBuildCommand("some-script test"))
    }

    @Test
    fun `isLikelyBuildCommand matches keyword coverage`() {
        assertTrue(ShellResolver.isLikelyBuildCommand("run coverage report"))
    }

    @Test
    fun `isLikelyBuildCommand matches keyword docker`() {
        assertTrue(ShellResolver.isLikelyBuildCommand("run docker compose up"))
    }

    @Test
    fun `isLikelyBuildCommand returns false for simple commands`() {
        assertFalse(ShellResolver.isLikelyBuildCommand("ls -la"))
        assertFalse(ShellResolver.isLikelyBuildCommand("echo hello"))
        assertFalse(ShellResolver.isLikelyBuildCommand("cat file.txt"))
    }

    // ──────────────────────────────────────────────
    // stripAnsi()
    // ──────────────────────────────────────────────

    @Test
    fun `stripAnsi removes escape codes`() {
        val input = "\u001B[31mError\u001B[0m: something failed"
        val result = ShellResolver.stripAnsi(input)
        assertEquals("Error: something failed", result)
    }

    @Test
    fun `stripAnsi handles text without escape codes`() {
        val input = "Normal text"
        assertEquals("Normal text", ShellResolver.stripAnsi(input))
    }

    @Test
    fun `stripAnsi handles multiple escape sequences`() {
        val input = "\u001B[1m\u001B[32mBold green\u001B[0m and \u001B[34mblue\u001B[0m"
        assertEquals("Bold green and blue", ShellResolver.stripAnsi(input))
    }

    // ──────────────────────────────────────────────
    // isLikelyPasswordPrompt()
    // ──────────────────────────────────────────────

    @Test
    fun `isLikelyPasswordPrompt detects Password prompt`() {
        assertTrue(ShellResolver.isLikelyPasswordPrompt("Password:"))
        assertTrue(ShellResolver.isLikelyPasswordPrompt("Enter password: "))
        assertTrue(ShellResolver.isLikelyPasswordPrompt("Enter your password:"))
    }

    @Test
    fun `isLikelyPasswordPrompt detects passphrase prompt`() {
        assertTrue(ShellResolver.isLikelyPasswordPrompt("passphrase:"))
        assertTrue(ShellResolver.isLikelyPasswordPrompt("Passphrase: "))
    }

    @Test
    fun `isLikelyPasswordPrompt detects token prompt`() {
        assertTrue(ShellResolver.isLikelyPasswordPrompt("Enter access token:"))
    }

    @Test
    fun `isLikelyPasswordPrompt detects credentials prompt`() {
        assertTrue(ShellResolver.isLikelyPasswordPrompt("credentials:"))
        assertTrue(ShellResolver.isLikelyPasswordPrompt("credential:"))
    }

    @Test
    fun `isLikelyPasswordPrompt detects api key prompt`() {
        assertTrue(ShellResolver.isLikelyPasswordPrompt("API key:"))
        assertTrue(ShellResolver.isLikelyPasswordPrompt("api-key:"))
    }

    @Test
    fun `isLikelyPasswordPrompt detects secret prompt`() {
        assertTrue(ShellResolver.isLikelyPasswordPrompt("secret:"))
    }

    @Test
    fun `isLikelyPasswordPrompt returns false for normal text`() {
        assertFalse(ShellResolver.isLikelyPasswordPrompt("Hello world"))
        assertFalse(ShellResolver.isLikelyPasswordPrompt("Building project..."))
        assertFalse(ShellResolver.isLikelyPasswordPrompt(""))
    }

    @Test
    fun `isLikelyPasswordPrompt only checks last 300 chars`() {
        val longPrefix = "a".repeat(400)
        // Password prompt is beyond the last 300 chars window
        assertFalse(ShellResolver.isLikelyPasswordPrompt("Password: $longPrefix"))
        // Password prompt is within the last 300 chars
        assertTrue(ShellResolver.isLikelyPasswordPrompt("${longPrefix}Password:"))
    }

    // ──────────────────────────────────────────────
    // Error messages list available shells
    // ──────────────────────────────────────────────

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `error message for unavailable shell lists available shells`() {
        val ex = assertThrows(ShellUnavailableException::class.java) {
            ShellResolver.resolve(requestedShell = "cmd", project = null)
        }
        // On Unix, bash should be listed as available
        assertTrue(ex.message!!.contains("Available shells"))
        assertTrue(ex.message!!.contains("bash"))
    }
}
