package com.workflow.orchestrator.agent.delegation

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.delegation.ui.AcceptDelegationDialog
import com.workflow.orchestrator.agent.session.DelegationMetadata
import com.workflow.orchestrator.core.delegation.DelegationMessage
import com.workflow.orchestrator.core.delegation.DelegationPaths
import com.workflow.orchestrator.core.delegation.DelegationServer
import com.workflow.orchestrator.core.settings.CrossIdeDelegationSettingsListener
import com.workflow.orchestrator.core.settings.PluginSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

/**
 * Per-project service. Manages the lifecycle of [DelegationServer] for this
 * project and routes incoming Connect messages through the Accept dialog into
 * new delegated AgentSessions.
 *
 * - Subscribes to [CrossIdeDelegationSettingsListener] for runtime toggle.
 * - Caller (a StartupActivity registered in plugin.xml) invokes [start].
 *
 * Spec: docs/superpowers/specs/2026-05-22-cross-ide-agent-delegation-design.md §3.2, §5.4, §6.
 */
@Service(Service.Level.PROJECT)
class DelegationInboundService(
    private val project: Project,
    private val cs: CoroutineScope,
) {
    private val settings get() = project.getService(PluginSettings::class.java).state
    private var server: DelegationServer? = null

    init {
        project.messageBus.connect().subscribe(
            CrossIdeDelegationSettingsListener.TOPIC,
            object : CrossIdeDelegationSettingsListener {
                override fun inboundSettingChanged(enabled: Boolean) {
                    if (enabled) start() else stop()
                }
            },
        )
    }

    fun start() {
        if (server != null) return
        if (!settings.enableInboundCrossIdeDelegation) return
        val projectPath = project.basePath ?: run {
            LOG.warn("Project has no basePath; cannot start DelegationInboundService")
            return
        }
        val socketPath = DelegationPaths.socketFor(Path.of(projectPath))
        val srv = DelegationServer(
            socketPath = socketPath,
            projectPath = projectPath,
            onConnect = { connect, replyWith, closeChannel -> handleConnect(connect, replyWith, closeChannel) },
            scope = cs,
        )
        srv.start()
        server = srv
    }

    fun stop() {
        server?.stop()
        server = null
    }

    private suspend fun handleConnect(
        connect: DelegationMessage.Connect,
        replyWith: suspend (DelegationMessage) -> Unit,
        closeChannel: suspend () -> Unit,
    ) {
        // Show the Accept dialog on the EDT.
        val accepted = withContext(Dispatchers.EDT) {
            val dlg = AcceptDelegationDialog(project, connect)
            dlg.show()
            dlg.isOK
        }
        if (!accepted) {
            replyWith(DelegationMessage.AcceptResult(accepted = false, reason = "user_rejected"))
            // Rejection is a terminal message — close the channel immediately.
            closeChannel()
            return
        }
        replyWith(DelegationMessage.AcceptResult(accepted = true))

        // Hand off to AgentService to actually start the delegated session.
        val agentService = project.getService(AgentService::class.java)
        val metadata = DelegationMetadata(
            delegatorIde = connect.delegatorIde,
            delegatorRepo = connect.delegatorRepo,
            delegatorSessionId = connect.delegatorSessionId,
            startedAt = System.currentTimeMillis(),
        )
        // F1: close the socket channel after writing the terminal Result so the FD is
        // released immediately rather than leaking until JVM exit.
        agentService.startDelegatedSession(
            request = connect.request,
            delegationMetadata = metadata,
            onResult = { result ->
                replyWith(result)
                closeChannel()
            },
        )
    }

    companion object {
        private val LOG = Logger.getInstance(DelegationInboundService::class.java)
    }
}
