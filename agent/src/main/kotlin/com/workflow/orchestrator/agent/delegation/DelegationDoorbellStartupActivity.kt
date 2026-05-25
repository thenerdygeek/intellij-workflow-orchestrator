package com.workflow.orchestrator.agent.delegation

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Always binds the [DelegationDoorbellService] when a project opens — regardless
 * of the `enableInboundCrossIdeDelegation` setting. The doorbell is the on-demand
 * inbound consent surface ("doorbell"): it must be listening even when standing
 * inbound delegation is disabled so an outbound knock from another IDE can still
 * raise a consent prompt.
 *
 * After indexing completes ([DumbService.runWhenSmart]), replays any pending
 * file-based delegation requests so a fresh launch (the IDE was not running when
 * the knock was written to disk) still surfaces the consent dialog.
 */
class DelegationDoorbellStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val doorbell = project.getService(DelegationDoorbellService::class.java)
        doorbell.start()
        // After indexing, replay any pending file-based requests (fresh-launch path).
        com.intellij.openapi.project.DumbService.getInstance(project).runWhenSmart {
            doorbell.replayPendingRequests()
        }
    }
}
