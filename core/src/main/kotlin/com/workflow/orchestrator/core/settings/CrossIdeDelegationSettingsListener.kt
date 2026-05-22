package com.workflow.orchestrator.core.settings

import com.intellij.util.messages.Topic

/**
 * Notifies subscribers when the cross-IDE delegation inbound setting flips.
 * Subscribers (e.g., DelegationInboundService) bind / unbind the per-project
 * IPC socket in response.
 *
 * Outbound toggle is observed via tool-registry re-registration (see
 * AgentService) — no listener needed for that side.
 */
interface CrossIdeDelegationSettingsListener {
    fun inboundSettingChanged(enabled: Boolean)

    companion object {
        @JvmStatic
        val TOPIC: Topic<CrossIdeDelegationSettingsListener> = Topic.create(
            "CrossIdeDelegationSettingsListener",
            CrossIdeDelegationSettingsListener::class.java,
        )
    }
}
