package com.workflow.orchestrator.pullrequest.workflow

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.workflow.PrRef
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.workflow.OpenPrLister
import com.workflow.orchestrator.pullrequest.service.PrListService

/**
 * Phase 5 Task 3 — `:pullrequest` implementation of the [OpenPrLister] EP.
 *
 * Bridges [PrListService.allRepoPrs] (list of [com.workflow.orchestrator.core.bitbucket.BitbucketPrDetail])
 * into the cross-module [PrRef] DTO that `:core`'s `WorkflowContextService` consumes for
 * auto-seed and the legacy `PrSelected` event mirror. PRs missing either branch ref are
 * skipped — they cannot drive a usable focus cascade.
 *
 * Same EP pattern as [com.workflow.orchestrator.pullrequest.action.CreatePrLauncherImpl].
 */
class OpenPrListerImpl : OpenPrLister {
    override fun listOpenPrs(project: Project): List<PrRef> {
        val prListService = project.getService(PrListService::class.java) ?: return emptyList()
        val settings = project.getService(PluginSettings::class.java) ?: return emptyList()
        val reposByName = settings.getRepos().associateBy { it.name }

        return prListService.allRepoPrs.value.mapNotNull { pr ->
            val fromBranch = pr.fromRef?.displayId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val toBranch = pr.toRef?.displayId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val repo = reposByName[pr.repoName]
            PrRef(
                prId = pr.id,
                fromBranch = fromBranch,
                toBranch = toBranch,
                repoName = pr.repoName,
                bambooPlanKey = repo?.bambooPlanKey?.takeIf { it.isNotBlank() },
                sonarProjectKey = repo?.sonarProjectKey?.takeIf { it.isNotBlank() },
            )
        }
    }
}
