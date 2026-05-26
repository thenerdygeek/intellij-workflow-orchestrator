package com.workflow.orchestrator.core.model

data class LinkResolution(
    val kind: Kind,
    val raw: String,
    val displayLabel: String,
    val targetDescription: String,
    /**
     * The concrete external URL this link opens in a browser, when applicable
     * (JIRA → the resolved `…/browse/TICKET` URL; WEB → the URL itself). Null for
     * IDE-local links (FILE/CLASS), which never reach the browser-open dialogue.
     * The confirmation modal copies/displays this instead of the raw `jira:`/scheme href.
     */
    val browserUrl: String? = null,
) {
    enum class Kind { FILE, CLASS, JIRA, WEB }
}
