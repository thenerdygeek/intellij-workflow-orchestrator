package com.workflow.orchestrator.agent.delegation.ui

import com.workflow.orchestrator.core.delegation.DelegationMessage
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DelegationPickerSocketGlobTest {

    @Test
    fun `globs socket files and asks each for projectPath via PING`(@TempDir tmp: Path) = runBlocking {
        val ipcDir = Files.createDirectories(tmp.resolve("ipc"))
        val socketA = Files.createFile(ipcDir.resolve("aaa.sock"))
        val socketB = Files.createFile(ipcDir.resolve("bbb.sock"))
        Files.createFile(ipcDir.resolve("not-a-sock.txt"))

        val seenPaths = mutableListOf<Path>()
        val results: List<DiscoveredProject> = SocketGlobDiscovery(
            ipcDir = ipcDir,
            pingFn = { path ->
                seenPaths.add(path)
                when (path) {
                    socketA -> DelegationMessage.Pong(projectPath = "/some/project/A")
                    socketB -> DelegationMessage.Pong(projectPath = "/some/project/B")
                    else -> null
                }
            },
        ).discover()

        assertEquals(setOf(socketA, socketB), seenPaths.toSet())
        assertEquals(setOf("/some/project/A", "/some/project/B"), results.map { it.projectPath }.toSet())
    }

    @Test
    fun `missing ipc dir returns empty list`(@TempDir tmp: Path) = runBlocking {
        val ipcDir = tmp.resolve("nope")
        val discovery = SocketGlobDiscovery(
            ipcDir = ipcDir,
            pingFn = { null },
        )
        assertTrue(discovery.discover().isEmpty())
    }

    @Test
    fun `socket files where PING fails or times out are dropped silently`(@TempDir tmp: Path) = runBlocking {
        val ipcDir = Files.createDirectories(tmp.resolve("ipc"))
        Files.createFile(ipcDir.resolve("dead.sock"))
        val discovery = SocketGlobDiscovery(
            ipcDir = ipcDir,
            pingFn = { null },
        )
        assertTrue(discovery.discover().isEmpty())
    }
}
