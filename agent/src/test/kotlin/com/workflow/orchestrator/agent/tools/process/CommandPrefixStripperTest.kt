package com.workflow.orchestrator.agent.tools.process

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CommandPrefixStripperTest {

    @Test
    fun `strips cmd slash c with double-quoted command`() {
        val result = CommandPrefixStripper.strip("""cmd /c "mvn -version"""")
        assertEquals("mvn -version", result.command)
        assertEquals("cmd /c", result.strippedPrefix)
    }

    @Test
    fun `strips cmd dot exe slash c case-insensitively`() {
        val result = CommandPrefixStripper.strip("""CMD.EXE /C "dir"""")
        assertEquals("dir", result.command)
        assertNotNull(result.strippedPrefix)
    }

    @Test
    fun `strips bash dash c with single-quoted command`() {
        val result = CommandPrefixStripper.strip("bash -c 'echo hello'")
        assertEquals("echo hello", result.command)
        assertEquals("bash -c", result.strippedPrefix)
    }

    @Test
    fun `strips sh dash c with double-quoted command`() {
        val result = CommandPrefixStripper.strip("""sh -c "ls -la"""")
        assertEquals("ls -la", result.command)
        assertEquals("sh -c", result.strippedPrefix)
    }

    @Test
    fun `strips powershell dash Command`() {
        val result = CommandPrefixStripper.strip("""powershell -Command "Get-ChildItem"""")
        assertEquals("Get-ChildItem", result.command)
        assertNotNull(result.strippedPrefix)
    }

    @Test
    fun `strips powershell with NoProfile and NonInteractive flags`() {
        val result = CommandPrefixStripper.strip("""pwsh -NoProfile -NonInteractive -Command "Get-Date"""")
        assertEquals("Get-Date", result.command)
        assertNotNull(result.strippedPrefix)
    }

    @Test
    fun `does not strip when command has no shell prefix`() {
        val result = CommandPrefixStripper.strip("mvn -version")
        assertEquals("mvn -version", result.command)
        assertNull(result.strippedPrefix)
    }

    @Test
    fun `does not strip cmd when not followed by slash c`() {
        // `cmd` could be a real binary the user wants to invoke (e.g. a script
        // named `cmd` on Unix). Only the `/c` form is the redundant wrapper.
        val result = CommandPrefixStripper.strip("cmd --help")
        assertEquals("cmd --help", result.command)
        assertNull(result.strippedPrefix)
    }

    @Test
    fun `does not strip when stripping would yield empty command`() {
        val result = CommandPrefixStripper.strip("""cmd /c """"")
        // Empty inner command — leave it alone so the validation error is honest.
        assertEquals("""cmd /c """"", result.command)
        assertNull(result.strippedPrefix)
    }

    @Test
    fun `unwraps quotes only when both ends match`() {
        // Asymmetric quoting → don't strip the quotes (they're meaningful).
        val result = CommandPrefixStripper.strip("""cmd /c "echo "hi"""")
        // The leading and trailing chars are both `"` so they get unwrapped;
        // the middle quotes survive as literal chars in the cleaned command.
        assertEquals("""echo "hi""", result.command)
    }

    @Test
    fun `bare command without quotes still strips prefix`() {
        val result = CommandPrefixStripper.strip("cmd /c mvn -version")
        assertEquals("mvn -version", result.command)
        assertNotNull(result.strippedPrefix)
    }

    @Test
    fun `tolerates leading whitespace before the prefix`() {
        val result = CommandPrefixStripper.strip("   bash -c 'ls'")
        assertEquals("ls", result.command)
        assertEquals("bash -c", result.strippedPrefix)
    }
}
