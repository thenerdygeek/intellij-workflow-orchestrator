package com.workflow.orchestrator.core.capability

/**
 * Typed enumeration of toggleable plugin features. The base backs each with a project-level
 * setting (see [DefaultFeatureRegistry]); a company fork can register a [FeatureRegistry] that
 * resolves them from a license server / LDAP / central policy instead — enabling org-wide
 * enable/disable without each user flipping a local setting.
 *
 * Start small: this lists the user-facing kill-switches that already exist as settings. Add an
 * entry here when a new feature should be centrally controllable by forks/admins.
 */
enum class PluginFeature {
    WEB_FETCH,
    WEB_SEARCH,
    RESEARCH_SUBAGENT,
    IMAGE_INPUT,
    AI_TITLE_GENERATION,
}
