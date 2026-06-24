package com.workflow.orchestrator.core.settings

import com.intellij.openapi.components.BaseState

class RepoConfig : BaseState() {
    var name by string("")
    var bitbucketProjectKey by string("")
    var bitbucketRepoSlug by string("")
    var bambooPlanKey by string("")
    var sonarProjectKey by string("")
    var dockerTagKey by string("")
    var defaultTargetBranch by string(NEUTRAL_DEFAULT_TARGET_BRANCH)
    var localVcsRootPath by string("")
    /** Server-anchored HTTPS clone URL, populated after first successful Bitbucket validation. */
    var canonicalCloneUrl by string("")
    var isPrimary by property(false)

    val isConfigured: Boolean
        get() = !bitbucketProjectKey.isNullOrBlank() && !bitbucketRepoSlug.isNullOrBlank()

    val displayLabel: String
        get() = (name ?: "").ifBlank { "${bitbucketProjectKey ?: ""}/${bitbucketRepoSlug ?: ""}" }
}
