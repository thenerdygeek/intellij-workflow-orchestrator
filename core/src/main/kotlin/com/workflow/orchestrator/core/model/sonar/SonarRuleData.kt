package com.workflow.orchestrator.core.model.sonar

import kotlinx.serialization.Serializable

/**
 * Rule details from SonarQube /api/rules/show — used by Issue Detail Panel.
 *
 * `description` is HTML/markdown that can run several KB on Sonar 25.x rules
 * (security rules ship riskDescription + vulnerabilityDescription + fixRecommendations
 * concatenated). The hand-written [toString] elides it so agent tool output
 * stays compact; the LLM that needs the body reads the structured `data` field.
 */
@Serializable
data class SonarRuleData(
    val ruleKey: String,
    val name: String,
    val description: String,
    val remediation: String?,
    val tags: List<String> = emptyList()
) {
    override fun toString(): String = buildString {
        append("Rule $ruleKey")
        if (name.isNotBlank()) append(" — \"$name\"")
        remediation?.takeIf { it.isNotBlank() }?.let { append(" • Effort $it") }
        if (tags.isNotEmpty()) append(" • tags=[${tags.joinToString(", ")}]")
        if (description.isNotBlank()) {
            append(" • description=${description.length} chars (in data)")
        }
    }
}
