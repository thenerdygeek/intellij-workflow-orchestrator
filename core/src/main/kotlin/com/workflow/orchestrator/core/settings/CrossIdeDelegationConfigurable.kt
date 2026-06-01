package com.workflow.orchestrator.core.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/**
 * Settings page rendered under
 *   File ▸ Settings ▸ Tools ▸ Workflow Orchestrator ▸ Agent ▸ Cross-IDE Delegation
 *
 * Exposes the opt-in toggles for cross-IDE agent delegation persisted on
 * [PluginSettings.State]. Fires [CrossIdeDelegationSettingsListener.inboundSettingChanged]
 * / `outboundSettingChanged` via the project message bus on apply so downstream
 * services can bind/unbind the per-project IPC socket without polling.
 *
 * Implementation note: holds the [DialogPanel] reference and delegates
 * `isModified` / `apply` / `reset` to it, so the `bindSelected({get},{set})`
 * lambdas in the DSL are actually invoked. The earlier version threw away the
 * panel reference and compared local vars manually — but the setter lambdas
 * only fire during [DialogPanel.apply] (never called), so the vars stayed
 * frozen at construction-time values and `isModified` always returned false.
 * Pattern mirrors [TelemetryConfigurable].
 *
 * Spec: docs/superpowers/specs/2026-05-22-cross-ide-agent-delegation-design.md §3.3.
 */
class CrossIdeDelegationConfigurable(private val project: Project) : Configurable {
    private val settings get() = project.getService(PluginSettings::class.java).state
    private var outboundEnabled = settings.enableOutboundCrossIdeDelegation
    private var inboundEnabled = settings.enableInboundCrossIdeDelegation
    private var autoApprove = settings.autoApproveDelegationAnswers
    private var idleTimeoutMinutes = settings.delegationIdleTimeoutMinutes
    private var dialogPanel: DialogPanel? = null
    private var idleSpinner: JBIntSpinner? = null

    override fun getDisplayName(): String = "Cross-IDE Delegation"

    override fun createComponent(): JComponent {
        val p = panel {
            group("Cross-IDE Agent Delegation") {
                row {
                    checkBox("Allow this IDE to delegate to other IDEs (outbound)")
                        .bindSelected({ outboundEnabled }, { outboundEnabled = it })
                        .comment(
                            "When on, this IDE's agent gains the `delegation` tool " +
                                "(send / close / answer / fetch_transcript / list_targets actions). " +
                                "Default off."
                        )
                }
                row {
                    checkBox("Accept incoming delegations from other IDEs (inbound)")
                        .bindSelected({ inboundEnabled }, { inboundEnabled = it })
                        .comment(
                            "When on, this IDE binds a per-project socket and shows an Accept " +
                                "dialog when another IDE delegates work here. Default off. " +
                                "Even when off, other IDEs can ask to delegate to this project " +
                                "— you'll get a prompt to Allow once, Allow always, or Cancel."
                        )
                }
                row {
                    checkBox("Auto-approve Agent-A's answers to delegated-session questions")
                        .bindSelected({ autoApprove }, { autoApprove = it })
                        .comment(
                            "When on, the delegating agent's drafted answers to questions raised by remote " +
                                "delegated sessions are sent without prompting you to confirm on this side. " +
                                "Default off — leaves the human as the verification step."
                        )
                }
                row("Idle timeout (minutes)") {
                    val spinner = JBIntSpinner(idleTimeoutMinutes, 0, 720, 5)
                    spinner.addChangeListener { idleTimeoutMinutes = spinner.value as Int }
                    idleSpinner = spinner
                    cell(spinner).comment(
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
        dialogPanel = p
        return p
    }

    override fun isModified(): Boolean =
        (dialogPanel?.isModified() == true) ||
            idleTimeoutMinutes != settings.delegationIdleTimeoutMinutes

    override fun apply() {
        val prevOutbound = settings.enableOutboundCrossIdeDelegation
        val prevInbound = settings.enableInboundCrossIdeDelegation
        // Runs the bindSelected setter lambdas, updating outboundEnabled /
        // inboundEnabled / autoApprove from the current UI state. The spinner
        // is wired with a live change listener so idleTimeoutMinutes is already
        // current; this is effectively a no-op for it.
        dialogPanel?.apply()
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
        // Refresh checkbox UI from the (now-refreshed) getter lambdas; refresh
        // the spinner directly since it isn't DSL-bound.
        dialogPanel?.reset()
        idleSpinner?.value = idleTimeoutMinutes
    }

    override fun disposeUIResources() {
        dialogPanel = null
        idleSpinner = null
    }
}
