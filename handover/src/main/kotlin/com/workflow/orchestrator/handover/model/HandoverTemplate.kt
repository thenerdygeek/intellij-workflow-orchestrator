package com.workflow.orchestrator.handover.model

/**
 * Which kind of clipboard / share output a template produces.
 *
 * - JIRA: Jira wiki markup, posted via JiraService.addComment or copied as plain wiki text.
 * - EMAIL: HTML for Outlook, copied as a multi-flavor (text/html + text/plain) clipboard transferable.
 */
enum class HandoverTemplateAction { JIRA, EMAIL }

/**
 * Where this template was loaded from. Used to decide read-only vs. editable, and
 * to render the project-override badge in the picker.
 *
 * - BUNDLED: classpath /handover/templates/{action}/<name>.<ext> — read-only.
 * - GLOBAL: ~/.workflow-orchestrator/handover/templates/{action}/<name>.<ext> — user-editable, all projects.
 * - PROJECT: ~/.workflow-orchestrator/{projectDirHash}/handover/templates/{action}/<name>.<ext>
 *   — overrides BUNDLED or GLOBAL of the same name; rendered with a project badge.
 */
enum class HandoverTemplateOrigin { BUNDLED, GLOBAL, PROJECT }

/**
 * One named handover template (Jira wiki or Email HTML).
 *
 * @property id Stable id including action prefix, e.g. "jira/standard-closure". Used as the
 *              merge key when layering BUNDLED < GLOBAL < PROJECT.
 * @property name Display name shown in the picker (no action prefix; deduplicated by [id]).
 * @property action Which output kind this template produces.
 * @property source Raw template text — Jira wiki markup or HTML, depending on [action].
 * @property origin Where the template was loaded from.
 */
data class HandoverTemplate(
    val id: String,
    val name: String,
    val action: HandoverTemplateAction,
    val source: String,
    val origin: HandoverTemplateOrigin,
) {
    val isBundled: Boolean get() = origin == HandoverTemplateOrigin.BUNDLED
    val isOverride: Boolean get() = origin == HandoverTemplateOrigin.PROJECT
}
