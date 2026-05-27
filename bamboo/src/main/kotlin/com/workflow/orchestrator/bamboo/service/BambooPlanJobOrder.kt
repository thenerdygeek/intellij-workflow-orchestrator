package com.workflow.orchestrator.bamboo.service

import com.workflow.orchestrator.bamboo.api.dto.BambooPlanConfigResponse

/**
 * Derives the canonical per-stage job order from a plan DEFINITION
 * ([BambooPlanConfigResponse], `GET /plan/{key}?expand=stages.stage.plans.plan`).
 *
 * Bamboo's build-result endpoint returns a stage's jobs in an unstable order, while the
 * plan definition lists them in the order the plan defines (the same order the Bamboo
 * website shows). [BambooBuildStructureMapper] uses this map to stable-sort the Build-tab
 * job list back into that defined order. Jobs are matched by `shortName` — branch-agnostic
 * and exactly what the result DTO carries as the job name.
 */
object BambooPlanJobOrder {

    /** stageName -> ordered job shortNames, as defined in the plan. */
    fun fromConfig(resp: BambooPlanConfigResponse): Map<String, List<String>> =
        resp.stages.stage.associate { stage ->
            stage.name to stage.plans.plan
                .map { it.shortName.ifBlank { it.buildName } }
                .filter { it.isNotBlank() }
        }
}
