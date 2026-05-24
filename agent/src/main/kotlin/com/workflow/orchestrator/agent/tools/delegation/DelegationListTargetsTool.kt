package com.workflow.orchestrator.agent.tools.delegation

import com.intellij.ide.RecentProjectsManager
import com.intellij.ide.RecentProjectsManagerBase
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.delegation.ui.SocketGlobDiscovery
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.core.delegation.DelegationClient
import com.workflow.orchestrator.core.delegation.DelegationPaths
import com.workflow.orchestrator.core.settings.PluginSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path

/**
 * `delegation_list_targets` — read-only enumeration of potential cross-IDE delegation
 * targets. Returns the same list the picker would show (recents + socket-glob discovered)
 * but without opening UI. Lets the LLM pre-flight what's available before choosing to
 * call `delegation_send`.
 *
 * Plan 4 follow-up. Spec §4.5 explicitly rejected `delegation_list_active` (active channels);
 * this is a different shape — potential targets, not in-flight channels.
 *
 * Spec: docs/superpowers/specs/2026-05-22-cross-ide-agent-delegation-design.md §4.5.
 */
class DelegationListTargetsTool(
    private val recentsProvider: suspend (Project) -> List<RecentEntry> = ::defaultRecentsProvider,
    private val discoveredProvider: suspend (Project) -> List<RecentEntry> = ::defaultDiscoveredProvider,
) : AgentTool {

    /**
     * One potential delegation target.
     *
     * @property projectPath  Absolute path to the project root.
     * @property repoName     Display name (from RecentProjectsManager or directory base name).
     * @property status       One of: "running" (IDE reachable), "closed" (in recents, not running),
     *                        "discovered" (socket-glob only), "missing" (path doesn't exist on disk).
     * @property lastOpened   Epoch millis if known from recents; null otherwise.
     */
    data class RecentEntry(
        val projectPath: String,
        val repoName: String,
        val status: String,
        val lastOpened: Long?,
    )

    override val name = "delegation_list_targets"

    override val description = """
        List potential cross-IDE delegation targets — the same recents + discovered IDEs
        the picker would show, but as data instead of UI. Useful before calling
        `delegation_send` if you want to tell the user which repos are options or pre-select
        a `suggested_repo`. Read-only; no side effects.

        Returns a JSON object with a `targets` array. Each entry has:
          repoName     display name (e.g. "frontend-app")
          projectPath  absolute path
          status       "running"    — IDE has the project open and is reachable
                       "closed"     — in recents but IDE not running on it
                       "discovered" — found via socket glob; not in this IDE's recents
                                      (likely a different JetBrains Toolbox slot)
                       "missing"    — path no longer exists on disk; skip
          lastOpened   epoch millis if known, else null
    """.trimIndent()

    override val parameters = FunctionParameters(
        properties = emptyMap(),
        required = emptyList(),
    )

    override val allowedWorkers = setOf(
        WorkerType.ORCHESTRATOR,
        WorkerType.CODER,
        WorkerType.ANALYZER,
    )

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        if (!PluginSettings.getInstance(project).state.enableOutboundCrossIdeDelegation) {
            return ToolResult.error(
                "DelegationOutboundDisabled: cross-IDE delegation is currently disabled in settings " +
                    "(Tools → Workflow Orchestrator → Agent → Enable outbound cross-IDE delegation)"
            )
        }

        val recents = try {
            recentsProvider(project)
        } catch (e: Exception) {
            LOG.warn("delegation_list_targets: recents lookup failed", e)
            emptyList()
        }

        val recentPaths = recents.map { canon(it.projectPath) }.toSet()

        val discovered = try {
            discoveredProvider(project)
        } catch (e: Exception) {
            LOG.warn("delegation_list_targets: discovery failed", e)
            emptyList()
        }.filter { canon(it.projectPath) !in recentPaths }

        val all = recents + discovered

        val json = buildString {
            append("""{"targets":[""")
            all.forEachIndexed { i, e ->
                if (i > 0) append(',')
                append("""{"repoName":""")
                append(quoteJson(e.repoName))
                append(""","projectPath":""")
                append(quoteJson(e.projectPath))
                append(""","status":""")
                append(quoteJson(e.status))
                append(""","lastOpened":""")
                append(e.lastOpened?.toString() ?: "null")
                append('}')
            }
            append("]}")
        }

        LOG.debug("[DelegationListTargets] returning ${all.size} targets")

        return ToolResult(
            content = json,
            summary = json,
            tokenEstimate = (json.length / 4).coerceAtLeast(10),
        )
    }

    companion object {
        private val LOG = Logger.getInstance(DelegationListTargetsTool::class.java)

        private fun canon(p: String): String =
            try { Path.of(p).toAbsolutePath().normalize().toString() } catch (_: Exception) { p }

        private fun quoteJson(s: String): String =
            '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"'

        /**
         * Production recents provider.
         *
         * Reads [RecentProjectsManagerBase.getRecentPaths], probes each path's UDS socket
         * to determine "running" vs "closed". Paths that don't exist on disk are marked
         * "missing" (socket probe is skipped for non-existent paths).
         */
        suspend fun defaultRecentsProvider(project: Project): List<RecentEntry> =
            withContext(Dispatchers.IO) {
                val mgr = (RecentProjectsManager.getInstance() as? RecentProjectsManagerBase)
                    ?: return@withContext emptyList()
                val paths: List<String> = try {
                    mgr.getRecentPaths()
                } catch (_: Exception) {
                    emptyList()
                }
                paths.mapNotNull { pathStr ->
                    try {
                        val path = Path.of(pathStr)
                        val name: String = try {
                            mgr.getDisplayName(pathStr)?.takeIf { it.isNotBlank() }
                        } catch (_: Exception) { null }
                            ?: path.fileName?.toString()
                            ?: pathStr
                        val status = when {
                            !Files.exists(path) -> "missing"
                            DelegationClient.ping(DelegationPaths.socketFor(path)) != null -> "running"
                            else -> "closed"
                        }
                        RecentEntry(
                            projectPath = pathStr,
                            repoName = name,
                            status = status,
                            lastOpened = null, // RecentProjectsManagerBase doesn't expose timestamps directly
                        )
                    } catch (e: Exception) {
                        LOG.debug("delegation_list_targets: skipping malformed recent $pathStr", e)
                        null
                    }
                }
            }

        /**
         * Production discovered provider.
         *
         * Uses [SocketGlobDiscovery] to find IDE instances whose socket is reachable but
         * whose project path is not in the recents list. Callers filter out any paths
         * already covered by [defaultRecentsProvider].
         */
        suspend fun defaultDiscoveredProvider(project: Project): List<RecentEntry> =
            withContext(Dispatchers.IO) {
                try {
                    SocketGlobDiscovery(pingFn = { p -> DelegationClient.ping(p) })
                        .discover()
                        .map { d ->
                            RecentEntry(
                                projectPath = d.projectPath,
                                repoName = Path.of(d.projectPath).fileName?.toString() ?: d.projectPath,
                                status = "discovered",
                                lastOpened = null,
                            )
                        }
                } catch (e: Exception) {
                    LOG.warn("delegation_list_targets: socket-glob discovery failed", e)
                    emptyList()
                }
            }
    }
}
