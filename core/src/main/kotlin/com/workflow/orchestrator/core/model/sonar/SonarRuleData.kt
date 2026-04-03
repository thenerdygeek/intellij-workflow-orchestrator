package com.workflow.orchestrator.core.model.sonar

import kotlinx.serialization.Serializable

/**
 * Rule details from SonarQube /api/rules/show — used by Issue Detail Panel.
 */
@Serializable
data class SonarRuleData(
    val ruleKey: String,
    val name: String,
    val description: String,
    val remediation: String?,
    val tags: List<String> = emptyList()
)
