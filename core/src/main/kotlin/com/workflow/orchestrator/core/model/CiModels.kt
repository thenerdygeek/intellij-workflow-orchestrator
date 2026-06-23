package com.workflow.orchestrator.core.model

import com.workflow.orchestrator.core.model.bamboo.PlanData
import com.workflow.orchestrator.core.model.bamboo.ProjectData

/**
 * Neutral CI-pipeline descriptor — the vendor-agnostic counterpart of Bamboo's [PlanData]
 * (Phase 0b-2 `CiService` seam). A "pipeline" is one buildable unit (Bamboo plan / Jenkins job /
 * GitHub-Actions workflow); a "group" ([CiGroupData]) is its container (Bamboo project / Jenkins
 * folder / GitHub org-or-repo). Field names avoid Bamboo's "plan/project" vocabulary.
 */
data class PipelineData(
    val key: String,
    val name: String,
    val shortName: String = "",
    val groupKey: String,
    val groupName: String,
    val enabled: Boolean = true,
)

/** Neutral CI grouping descriptor — vendor-agnostic counterpart of Bamboo's [ProjectData]. */
data class CiGroupData(
    val key: String,
    val name: String,
    val description: String? = null,
)

/** Adapter: Bamboo [PlanData] → neutral [PipelineData] (`projectKey/projectName` → `groupKey/groupName`). */
fun PlanData.toPipelineData(): PipelineData =
    PipelineData(
        key = key,
        name = name,
        shortName = shortName,
        groupKey = projectKey,
        groupName = projectName,
        enabled = enabled,
    )

/** Adapter: Bamboo [ProjectData] → neutral [CiGroupData] (1:1). */
fun ProjectData.toCiGroupData(): CiGroupData =
    CiGroupData(key = key, name = name, description = description)
