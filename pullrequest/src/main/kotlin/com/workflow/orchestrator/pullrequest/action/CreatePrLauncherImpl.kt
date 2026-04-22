package com.workflow.orchestrator.pullrequest.action

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.bitbucket.CreatePrLauncher
import kotlinx.coroutines.CoroutineScope

/**
 * Phase 5 placeholder for the [CreatePrLauncher] extension point.
 *
 * The dialog has been moved from :bamboo to :pullrequest but the call-site in
 * [com.workflow.orchestrator.bamboo.ui.PrBar] still handles its own prefetch
 * and instantiates [com.workflow.orchestrator.pullrequest.ui.CreatePrDialog]
 * directly via a temporary `:bamboo → :pullrequest` compile dependency.
 *
 * Phase 6 will:
 *  - Introduce `CreatePrPrefetch` in :pullrequest to consolidate the remote-branch
 *    + ticket + reviewer + default-title lookups.
 *  - Fill in this [launch] to run that prefetch and open [CreatePrDialog] itself.
 *  - Replace [PrBar.openCreatePrDialog]'s direct construction with
 *    `CreatePrLauncher.getInstance()?.launch(...)`.
 *  - Remove the `:bamboo → :pullrequest` Gradle dep.
 *
 * Until then this impl is registered only so the extension point has an
 * implementation when callers query it. It throws when actually invoked so a
 * premature integration attempt surfaces loudly.
 */
class CreatePrLauncherImpl : CreatePrLauncher {

    private val log = Logger.getInstance(CreatePrLauncherImpl::class.java)

    override fun launch(
        project: Project,
        scope: CoroutineScope,
        onCreated: (prUrl: String, prId: Int) -> Unit
    ) {
        log.warn(
            "[PR:Create] CreatePrLauncherImpl.launch() invoked — Phase 5 placeholder is not wired. " +
                "Phase 6 will supply real prefetch + dialog launch."
        )
        error("CreatePrLauncherImpl.launch() is a Phase 5 placeholder; Phase 6 wires it up.")
    }
}
