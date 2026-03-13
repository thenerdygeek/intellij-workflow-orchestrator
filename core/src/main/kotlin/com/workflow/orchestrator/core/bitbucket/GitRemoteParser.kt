package com.workflow.orchestrator.core.bitbucket

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import git4idea.repo.GitRepositoryManager

/**
 * Utility to extract Bitbucket project key and repo slug from the git remote URL.
 * Lives in :core so any module can use it.
 */
object GitRemoteParser {

    private val log = Logger.getInstance(GitRemoteParser::class.java)

    // SSH: ssh://git@host(:port)/PROJECT/repo.git
    private val SSH_URL_PATTERN = Regex("""ssh://[^/]+(?::\d+)?/([^/]+)/([^/]+?)(?:\.git)?$""")
    // HTTPS: https://host/scm/PROJECT/repo.git
    private val HTTPS_URL_PATTERN = Regex("""https?://[^/]+/scm/([^/]+)/([^/]+?)(?:\.git)?$""")
    // SCP-style: git@host:PROJECT/repo.git
    private val SCP_URL_PATTERN = Regex("""[^@]+@[^:]+:([^/]+)/([^/]+?)(?:\.git)?$""")

    /**
     * Parses a git remote URL and returns (projectKey, repoSlug), or null if unrecognized.
     */
    fun parseRemoteUrl(remoteUrl: String): Pair<String, String>? {
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

    /**
     * Detects project key and repo slug from the current project's git remote (origin).
     * Returns (projectKey, repoSlug) or null if detection fails.
     */
    fun detectFromProject(project: Project): Pair<String, String>? {
        return try {
            val repos = GitRepositoryManager.getInstance(project).repositories
            if (repos.isEmpty()) {
                log.info("[Core:GitRemote] No git repositories found in project")
                return null
            }
            val repo = repos.first()
            val origin = repo.remotes.find { it.name == "origin" } ?: repo.remotes.firstOrNull()
            if (origin == null) {
                log.info("[Core:GitRemote] No git remotes found")
                return null
            }
            val remoteUrl = origin.firstUrl
            if (remoteUrl == null) {
                log.info("[Core:GitRemote] Remote '${origin.name}' has no URL")
                return null
            }
            log.info("[Core:GitRemote] Parsing remote URL: $remoteUrl")
            val result = parseRemoteUrl(remoteUrl)
            if (result != null) {
                log.info("[Core:GitRemote] Detected project='${result.first}', repo='${result.second}'")
            } else {
                log.warn("[Core:GitRemote] Could not parse remote URL: $remoteUrl")
            }
            result
        } catch (e: Exception) {
            log.warn("[Core:GitRemote] Failed to detect from project: ${e.message}")
            null
        }
    }
}
