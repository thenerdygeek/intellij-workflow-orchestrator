package com.workflow.orchestrator.handover.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class PrService {

    private var project: Project? = null

    constructor(project: Project) {
        this.project = project
    }

    constructor()

    companion object {
        private const val MAX_TITLE_LENGTH = 120

        // SSH: ssh://git@host(:port)/PROJECT/repo.git
        private val SSH_URL_PATTERN = Regex("""ssh://[^/]+(?::\d+)?/([^/]+)/([^/]+?)(?:\.git)?$""")
        // HTTPS: https://host/scm/PROJECT/repo.git
        private val HTTPS_URL_PATTERN = Regex("""https?://[^/]+/scm/([^/]+)/([^/]+?)(?:\.git)?$""")
        // SCP-style: git@host:PROJECT/repo.git
        private val SCP_URL_PATTERN = Regex("""[^@]+@[^:]+:([^/]+)/([^/]+?)(?:\.git)?$""")

        fun getInstance(project: Project): PrService {
            return project.getService(PrService::class.java)
        }
    }

    fun parseGitRemote(remoteUrl: String): Pair<String, String>? {
        SSH_URL_PATTERN.find(remoteUrl)?.let { match ->
            return Pair(match.groupValues[1], match.groupValues[2])
        }
        HTTPS_URL_PATTERN.find(remoteUrl)?.let { match ->
            return Pair(match.groupValues[1], match.groupValues[2])
        }
        SCP_URL_PATTERN.find(remoteUrl)?.let { match ->
            return Pair(match.groupValues[1], match.groupValues[2])
        }
        return null
    }

    fun buildPrTitle(ticketId: String, ticketSummary: String): String {
        val full = "$ticketId: $ticketSummary"
        return if (full.length > MAX_TITLE_LENGTH) {
            full.take(MAX_TITLE_LENGTH - 3) + "..."
        } else {
            full
        }
    }

    fun buildFallbackDescription(ticketId: String, ticketSummary: String, branchName: String): String {
        return "$ticketId: $ticketSummary\n\nBranch: $branchName"
    }
}
