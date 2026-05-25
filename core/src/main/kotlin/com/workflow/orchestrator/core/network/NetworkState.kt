package com.workflow.orchestrator.core.network

/** Coalesced machine-wide connectivity state. RECONNECTING is a transient post-wake state. */
enum class NetworkState { ONLINE, OFFLINE, RECONNECTING }
