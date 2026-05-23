package com.workflow.orchestrator.agent.delegation.ui

import com.workflow.orchestrator.core.delegation.DelegationMessage
import com.workflow.orchestrator.core.delegation.DelegationPaths
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/**
 * One discovered IDE-B endpoint and the project it advertised in its PONG.
 */
data class DiscoveredProject(
    val socketPath: Path,
    val projectPath: String,
)

/**
 * Picker supplement: globs all `.sock` files under `~/.workflow-orchestrator/ipc/`, PINGs each
 * endpoint, and returns the project paths that answered. Used by
 * [DelegationPicker] to surface IDE-B instances whose project isn't in
 * IDE-A's `RecentProjectsManager` (e.g., different JetBrains Toolbox slots).
 *
 * Inbound-disabled IDEs do not bind a socket file at all — they're invisible
 * to this discovery, by design (spec §5.1).
 *
 * Plan 3 spec §5.5.
 */
class SocketGlobDiscovery(
    private val ipcDir: Path = DelegationPaths.ipcDir(),
    private val pingFn: suspend (Path) -> DelegationMessage.Pong?,
) {
    suspend fun discover(): List<DiscoveredProject> {
        if (!Files.exists(ipcDir) || !Files.isDirectory(ipcDir)) return emptyList()
        val sockets = Files.list(ipcDir).use { stream ->
            stream
                .filter { it.isRegularFile() && it.extension == "sock" }
                .toList()
        }
        return sockets.mapNotNull { socket ->
            val pong = pingFn(socket)
            if (pong != null) DiscoveredProject(socket, pong.projectPath) else null
        }
    }
}
