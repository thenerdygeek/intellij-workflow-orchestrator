package com.workflow.orchestrator.jira.ui

import com.workflow.orchestrator.core.model.jira.MyPermissionsData

/**
 * Read-only view over [MyPermissionsData] for UI gating decisions.
 *
 * Defaults to **enabled** (`true`) when [data] is null or doesn't carry the
 * key — fail-open so transient API errors never lock the user out of write
 * actions. Caller logs a warning when the underlying load failed.
 */
class PermissionGate(private val data: MyPermissionsData?) {

    /** True if the named modern Jira permission key is granted. */
    fun has(key: String): Boolean {
        val d = data ?: return true // fail-open
        val flag = d.permissions[key] ?: return true // missing key → fail-open
        return flag.havePermission
    }

    val canTransition: Boolean get() = has(KEY_TRANSITION)
    val canComment: Boolean get() = has(KEY_ADD_COMMENT)
    val canViewWatchers: Boolean get() = has(KEY_VIEW_WATCHERS)
    val canLogWork: Boolean get() = has(KEY_WORK_ON_ISSUES)

    companion object {
        const val KEY_TRANSITION = "TRANSITION_ISSUES"
        const val KEY_ADD_COMMENT = "ADD_COMMENTS"
        const val KEY_VIEW_WATCHERS = "VIEW_VOTERS_AND_WATCHERS"
        const val KEY_WORK_ON_ISSUES = "WORK_ON_ISSUES"

        /** Fail-open gate when the permissions load errored. */
        val PERMISSIVE = PermissionGate(null)
    }
}
