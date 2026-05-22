package com.workflow.orchestrator.core.settings

import com.intellij.util.messages.Topic

/**
 * Notifies subscribers when a cross-IDE delegation setting flips.
 *
 * - [inboundSettingChanged]: fired when the "accept inbound" toggle changes.
 *   [com.workflow.orchestrator.agent.delegation.DelegationInboundService] binds /
 *   unbinds the per-project IPC socket in response.
 *
 * - [outboundSettingChanged]: fired when the "allow outbound" toggle changes.
 *   [com.workflow.orchestrator.agent.AgentService] adds / removes the
 *   `delegation_send` and `delegation_close` tools in response so the LLM
 *   has no knowledge of delegation when the feature is off (§3.3).
 */
interface CrossIdeDelegationSettingsListener {
    fun inboundSettingChanged(enabled: Boolean)

    /** Default no-op so existing implementors (DelegationInboundService) need not override. */
    fun outboundSettingChanged(enabled: Boolean) {}

    companion object {
        @JvmStatic
        val TOPIC: Topic<CrossIdeDelegationSettingsListener> = Topic.create(
            "CrossIdeDelegationSettingsListener",
            CrossIdeDelegationSettingsListener::class.java,
        )
    }
}
