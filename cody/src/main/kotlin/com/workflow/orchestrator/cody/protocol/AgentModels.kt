package com.workflow.orchestrator.cody.protocol

// --- Initialize Request ---

data class ClientInfo(
    val name: String = "WorkflowOrchestrator",
    val version: String,
    val ideVersion: String? = null,
    val workspaceRootUri: String,
    val extensionConfiguration: ExtensionConfiguration,
    val capabilities: ClientCapabilities
)

data class ExtensionConfiguration(
    val serverEndpoint: String,
    val accessToken: String,
    val customHeaders: Map<String, String> = emptyMap(),
    val codebase: String? = null
)

data class ClientCapabilities(
    val chat: String = "streaming",
    val edit: String = "enabled",
    val editWorkspace: String = "enabled",
    val showDocument: String = "enabled",
    val codeActions: String = "enabled",
    val codeLenses: String = "none",
    val completions: String = "none",
    val git: String = "enabled",
    val globalState: String = "server-managed",
    val secrets: String = "client-managed"
)

// --- Initialize Response ---

data class ServerInfo(
    val name: String,
    val authenticated: Boolean? = null,
    val authStatus: AuthStatus? = null
)

data class AuthStatus(
    val endpoint: String = "",
    val authenticated: Boolean = false,
    val username: String = "",
    val displayName: String? = null,
    val siteVersion: String = ""
)

// --- Progress Notifications (server -> client) ---

data class ProgressStartParams(
    val id: String,
    val options: ProgressOptions? = null
)

data class ProgressOptions(
    val title: String? = null,
    val cancellable: Boolean? = null,
    val location: String? = null
)

data class ProgressReportParams(
    val id: String,
    val message: String? = null,
    val increment: Int? = null
)

data class ProgressEndParams(
    val id: String
)

// --- Window Messages (server -> client) ---

data class ShowMessageParams(
    val message: String,
    val type: Int? = null,
    val items: List<String>? = null
)
