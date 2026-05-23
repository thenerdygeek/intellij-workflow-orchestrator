package com.workflow.orchestrator.core.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/**
 * Settings page rendered under
 *   File ▸ Settings ▸ Tools ▸ Workflow Orchestrator ▸ Agent ▸ Cross-IDE Delegation
 *
 * Exposes the two opt-in toggles for cross-IDE agent delegation persisted on
 * [PluginSettings.State]. Fires [CrossIdeDelegationSettingsListener.inboundSettingChanged]
 * via the project message bus when the inbound toggle is modified and applied,
 * so downstream services can bind/unbind the per-project IPC socket without
 * polling the settings on every tick.
 *
 * Spec: docs/superpowers/specs/2026-05-22-cross-ide-agent-delegation-design.md §3.3.
 */
class CrossIdeDelegationConfigurable(private val project: Project) : Configurable {
    private val settings get() = project.getService(PluginSettings::class.java).state
    private var outboundEnabled = settings.enableOutboundCrossIdeDelegation
    private var inboundEnabled = settings.enableInboundCrossIdeDelegation
    private var autoApprove = settings.autoApproveDelegationAnswers
    private var idleTimeoutMinutes = settings.delegationIdleTimeoutMinutes

    override fun getDisplayName(): String = "Cross-IDE Delegation"

    override fun createComponent(): JComponent = panel {
        group("Cross-IDE Agent Delegation") {
            row {
                checkBox("Allow this IDE to delegate to other IDEs (outbound)")
                    .bindSelected({ outboundEnabled }, { outboundEnabled = it })
                    .comment(
                        "When on, this IDE's agent gains delegation_send / delegation_close tools. " +
                            "Default off."
                    )
            }
            row {
                checkBox("Accept incoming delegations from other IDEs (inbound)")
                    .bindSelected({ inboundEnabled }, { inboundEnabled = it })
                    .comment(
                        "When on, this IDE binds a per-project socket and shows an Accept " +
                            "dialog when another IDE delegates work here. Default off."
                    )
            }
            row {
                checkBox("Auto-approve Agent-A's answers to delegated-session questions")
                    .bindSelected({ autoApprove }, { autoApprove = it })
                    .comment(
                        "When on, Agent-A's drafted answers to questions raised by remote " +
                            "delegated sessions are sent without an IDE-A confirmation prompt. " +
                            "Default off — leaves the human as the verification step."
                    )
            }
            row("Idle timeout (minutes)") {
                cell(JBIntSpinner(idleTimeoutMinutes, 0, 720, 5))
                    .applyToComponent {
                        addChangeListener { idleTimeoutMinutes = (value as Int) }
                    }
                    .comment(
                        "Close a delegation channel after this many minutes of no IPC traffic. " +
                            "0 disables idle detection (channels stay open until explicit close). " +
                            "Default 30."
                    )
            }
            row {
                comment(
                    "All gates default off. Feature is local-only (same machine, same user). " +
                        "See documentation for security and privacy implications."
                )
            }
        }
    }

    override fun isModified(): Boolean =
        outboundEnabled != settings.enableOutboundCrossIdeDelegation ||
            inboundEnabled != settings.enableInboundCrossIdeDelegation ||
            autoApprove != settings.autoApproveDelegationAnswers ||
            idleTimeoutMinutes != settings.delegationIdleTimeoutMinutes

    override fun apply() {
        val prevOutbound = settings.enableOutboundCrossIdeDelegation
        val prevInbound = settings.enableInboundCrossIdeDelegation
        settings.enableOutboundCrossIdeDelegation = outboundEnabled
        settings.enableInboundCrossIdeDelegation = inboundEnabled
        settings.autoApproveDelegationAnswers = autoApprove
        settings.delegationIdleTimeoutMinutes = idleTimeoutMinutes
        if (prevOutbound != outboundEnabled) {
            project.messageBus.syncPublisher(CrossIdeDelegationSettingsListener.TOPIC)
                .outboundSettingChanged(outboundEnabled)
        }
        if (prevInbound != inboundEnabled) {
            project.messageBus.syncPublisher(CrossIdeDelegationSettingsListener.TOPIC)
                .inboundSettingChanged(inboundEnabled)
        }
    }

    override fun reset() {
        outboundEnabled = settings.enableOutboundCrossIdeDelegation
        inboundEnabled = settings.enableInboundCrossIdeDelegation
        autoApprove = settings.autoApproveDelegationAnswers
        idleTimeoutMinutes = settings.delegationIdleTimeoutMinutes
    }
}
