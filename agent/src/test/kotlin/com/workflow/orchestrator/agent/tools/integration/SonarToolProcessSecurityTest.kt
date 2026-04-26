package com.workflow.orchestrator.agent.tools.integration

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Security tests for SonarTool's local_analysis process-spawn path.
 *
 * T2 — HIGH — Move Sonar token off argv
 *
 * Threat model:
 *  1. Token visible in `ps aux` when passed via -Dsonar.token= on argv.
 *  2. Branch name / sonarUrl interpolated into a shell string → injection if branch
 *     name contains shell metacharacters (e.g., `foo; curl evil`).
 *
 * Fix:
 *  1. Token passed via SONAR_TOKEN environment variable (SonarQube scanner reads it natively).
 *  2. ProcessBuilder argv form — no `sh -c`, no shell metacharacter interpretation.
 *  3. Branch name validated against `^[a-zA-Z0-9._\-/]+$` before passing to ProcessBuilder.
 *
 * These tests exercise the extracted `buildScannerProcess` helper directly so they never
 * need a real Maven/Gradle install or a live IntelliJ Project.
 */
class SonarToolProcessSecurityTest {

    private val tool = SonarTool()

    // ══════════════════════════════════════════════════════════════════════════════════
    // Token-off-argv assertions
    // ══════════════════════════════════════════════════════════════════════════════════

    @Test
    fun `Maven command list does NOT contain sonar dot token`() {
        val pb = tool.buildScannerProcess(
            buildTool = "Maven",
            sonarUrl = "https://sonar.example.com",
            token = "super-secret-token",
            projectsFlag = null,
            scannerInclusions = "src/main/java/Foo.java",
            branch = "feature/PROJ-123",
            workingDir = createTempDir()
        )
        val cmd = pb.command()
        assertFalse(
            cmd.any { it.contains("sonar.token") },
            "Command list must NOT contain sonar.token — got: $cmd"
        )
    }

    @Test
    fun `Gradle command list does NOT contain sonar dot token`() {
        val pb = tool.buildScannerProcess(
            buildTool = "Gradle",
            sonarUrl = "https://sonar.example.com",
            token = "another-secret",
            projectsFlag = null,
            scannerInclusions = "src/main/java/Bar.java",
            branch = null,
            workingDir = createTempDir()
        )
        val cmd = pb.command()
        assertFalse(
            cmd.any { it.contains("sonar.token") },
            "Command list must NOT contain sonar.token — got: $cmd"
        )
    }

    // ══════════════════════════════════════════════════════════════════════════════════
    // Token-in-environment assertions
    // ══════════════════════════════════════════════════════════════════════════════════

    @Test
    fun `Maven ProcessBuilder sets SONAR_TOKEN env var to the provided token`() {
        val testToken = "my-test-sonar-token-abc123"
        val pb = tool.buildScannerProcess(
            buildTool = "Maven",
            sonarUrl = "https://sonar.example.com",
            token = testToken,
            projectsFlag = null,
            scannerInclusions = "src/main/java/Foo.java",
            branch = "feature/PROJ-123",
            workingDir = createTempDir()
        )
        assertEquals(
            testToken,
            pb.environment()["SONAR_TOKEN"],
            "SONAR_TOKEN env var must equal the provided token"
        )
    }

    @Test
    fun `Gradle ProcessBuilder sets SONAR_TOKEN env var to the provided token`() {
        val testToken = "gradle-sonar-token-xyz"
        val pb = tool.buildScannerProcess(
            buildTool = "Gradle",
            sonarUrl = "https://sonar.example.com",
            token = testToken,
            projectsFlag = null,
            scannerInclusions = "src/main/java/Bar.java",
            branch = null,
            workingDir = createTempDir()
        )
        assertEquals(
            testToken,
            pb.environment()["SONAR_TOKEN"],
            "SONAR_TOKEN env var must equal the provided token"
        )
    }

    // ══════════════════════════════════════════════════════════════════════════════════
    // No-shell-wrapper assertion (argv form, not sh -c)
    // ══════════════════════════════════════════════════════════════════════════════════

    @Test
    fun `Maven ProcessBuilder command is NOT a 3-element sh -c shell invocation`() {
        val pb = tool.buildScannerProcess(
            buildTool = "Maven",
            sonarUrl = "https://sonar.example.com",
            token = "tok",
            projectsFlag = null,
            scannerInclusions = "src/main/java/Foo.java",
            branch = "feature/PROJ-123",
            workingDir = createTempDir()
        )
        val cmd = pb.command()
        assertFalse(
            cmd.size == 3 && (cmd[0] == "sh" || cmd[0] == "cmd.exe") && cmd[1] in listOf("-c", "/c"),
            "Command must NOT be a shell-wrapper invocation — got: $cmd"
        )
        assertTrue(cmd.size >= 2, "Expected at least 2 argv elements — got: $cmd")
    }

    @Test
    fun `Gradle ProcessBuilder command is NOT a 3-element sh -c shell invocation`() {
        val pb = tool.buildScannerProcess(
            buildTool = "Gradle",
            sonarUrl = "https://sonar.example.com",
            token = "tok",
            projectsFlag = null,
            scannerInclusions = "src/main/java/Bar.java",
            branch = null,
            workingDir = createTempDir()
        )
        val cmd = pb.command()
        assertFalse(
            cmd.size == 3 && (cmd[0] == "sh" || cmd[0] == "cmd.exe") && cmd[1] in listOf("-c", "/c"),
            "Command must NOT be a shell-wrapper invocation — got: $cmd"
        )
    }

    // ══════════════════════════════════════════════════════════════════════════════════
    // Branch-name validation
    // ══════════════════════════════════════════════════════════════════════════════════

    @Test
    fun `malicious branch name with semicolon is rejected before process spawn`() {
        val result = tool.validateBranchName("foo; curl evil")
        assertTrue(result.isError, "Semicolon in branch name must be rejected")
        assertTrue(
            result.content.contains("INVALID_BRANCH_NAME") || result.content.contains("Invalid branch name"),
            "Error message must reference INVALID_BRANCH_NAME — got: ${result.content}"
        )
    }

    @Test
    fun `branch name with shell backtick is rejected`() {
        val result = tool.validateBranchName("foo`rm -rf /`")
        assertTrue(result.isError, "Backtick in branch name must be rejected")
    }

    @Test
    fun `branch name with dollar sign is rejected`() {
        val result = tool.validateBranchName("feature/\${HOME}")
        assertTrue(result.isError, "Dollar sign in branch name must be rejected")
    }

    @Test
    fun `branch name with ampersand is rejected`() {
        val result = tool.validateBranchName("foo && evil")
        assertTrue(result.isError, "Ampersand in branch name must be rejected")
    }

    @Test
    fun `branch name with pipe is rejected`() {
        val result = tool.validateBranchName("foo | cat /etc/passwd")
        assertTrue(result.isError, "Pipe in branch name must be rejected")
    }

    @Test
    fun `legitimate branch name feature slash PROJ-123 passes validation`() {
        val result = tool.validateBranchName("feature/PROJ-123")
        assertFalse(result.isError, "feature/PROJ-123 must pass validation — got: ${result.content}")
    }

    @Test
    fun `legitimate branch name release-1 dot 2 dot 3 passes validation`() {
        val result = tool.validateBranchName("release-1.2.3")
        assertFalse(result.isError, "release-1.2.3 must pass validation — got: ${result.content}")
    }

    @Test
    fun `legitimate branch name with underscores passes validation`() {
        val result = tool.validateBranchName("fix_TICKET_456")
        assertFalse(result.isError, "fix_TICKET_456 must pass validation — got: ${result.content}")
    }

    @Test
    fun `blank branch name returns non-error (omit from command)`() {
        // A blank branch means "omit -Dsonar.branch.name" — it should NOT be an error.
        val result = tool.validateBranchName(null)
        assertFalse(result.isError, "null branch name must return a non-error result (caller omits the flag)")
    }

    @Test
    fun `empty string branch name returns non-error (treated as absent)`() {
        val result = tool.validateBranchName("")
        assertFalse(result.isError, "empty branch name must return a non-error result")
    }

    // ══════════════════════════════════════════════════════════════════════════════════
    // Maven -pl flag (projects flag) flows through correctly in argv form
    // ══════════════════════════════════════════════════════════════════════════════════

    @Test
    fun `Maven with projectsFlag includes -pl as separate argv element`() {
        val pb = tool.buildScannerProcess(
            buildTool = "Maven",
            sonarUrl = "https://sonar.example.com",
            token = "tok",
            projectsFlag = "module-a,module-b",
            scannerInclusions = "src/main/java/Foo.java",
            branch = null,
            workingDir = createTempDir()
        )
        val cmd = pb.command()
        assertTrue(cmd.contains("-pl"), "Command must contain -pl flag — got: $cmd")
        val plIdx = cmd.indexOf("-pl")
        assertEquals("module-a,module-b", cmd[plIdx + 1], "-pl value must be next argv element")
    }

    @Test
    fun `branch name is passed as separate -Dsonar dot branch dot name argv element`() {
        val pb = tool.buildScannerProcess(
            buildTool = "Maven",
            sonarUrl = "https://sonar.example.com",
            token = "tok",
            projectsFlag = null,
            scannerInclusions = "src/main/java/Foo.java",
            branch = "feature/MY-999",
            workingDir = createTempDir()
        )
        val cmd = pb.command()
        assertTrue(
            cmd.any { it == "-Dsonar.branch.name=feature/MY-999" },
            "Branch must appear as a single argv element — got: $cmd"
        )
    }

    @Test
    fun `null branch omits -Dsonar dot branch dot name from command`() {
        val pb = tool.buildScannerProcess(
            buildTool = "Maven",
            sonarUrl = "https://sonar.example.com",
            token = "tok",
            projectsFlag = null,
            scannerInclusions = "src/main/java/Foo.java",
            branch = null,
            workingDir = createTempDir()
        )
        val cmd = pb.command()
        assertFalse(
            cmd.any { it.contains("sonar.branch.name") },
            "Null branch must omit -Dsonar.branch.name entirely — got: $cmd"
        )
    }

    private fun createTempDir(): java.io.File {
        val f = java.io.File(System.getProperty("java.io.tmpdir"), "sonar-test-${System.nanoTime()}")
        f.mkdirs()
        f.deleteOnExit()
        return f
    }
}
