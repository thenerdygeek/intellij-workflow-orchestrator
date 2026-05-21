package com.workflow.orchestrator.core.model

sealed class ChatLink {
    abstract val raw: String

    data class FileLink(
        override val raw: String,
        val path: String,
        val line: Int? = null,
        val endLine: Int? = null,
    ) : ChatLink()

    data class ClassLink(
        override val raw: String,
        val fqn: String,
        val method: String? = null,
    ) : ChatLink()

    data class JiraLink(
        override val raw: String,
        val ticketId: String,
    ) : ChatLink()

    data class WebLink(
        override val raw: String,
        val url: String,
    ) : ChatLink()
}
