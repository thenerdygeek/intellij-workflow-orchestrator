package com.workflow.orchestrator.jira.ui

import com.workflow.orchestrator.core.model.jira.MyPermissionsData
import com.workflow.orchestrator.core.model.jira.PermissionFlag
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Headless tests for [PermissionGate]. R-ADD-1 mandates fail-open semantics so a
 * transient API error never locks the user out of write actions.
 */
class PermissionGateTest {

    private fun perm(key: String, has: Boolean) =
        key to PermissionFlag(key, key, has)

    @Test
    fun `null data is fail-open for every key`() {
        val gate = PermissionGate(null)
        assertTrue(gate.canTransition)
        assertTrue(gate.canComment)
        assertTrue(gate.canViewWatchers)
        assertTrue(gate.canLogWork)
    }

    @Test
    fun `missing keys default to enabled`() {
        val gate = PermissionGate(MyPermissionsData(emptyMap()))
        assertTrue(gate.canTransition, "absent TRANSITION_ISSUES must fail-open")
        assertTrue(gate.canComment, "absent ADD_COMMENTS must fail-open")
    }

    @Test
    fun `present and false key disables capability`() {
        val gate = PermissionGate(MyPermissionsData(mapOf(
            perm("TRANSITION_ISSUES", false),
            perm("ADD_COMMENTS", true)
        )))
        assertFalse(gate.canTransition)
        assertTrue(gate.canComment)
    }

    @Test
    fun `present and true key enables capability`() {
        val gate = PermissionGate(MyPermissionsData(mapOf(
            perm("VIEW_VOTERS_AND_WATCHERS", true)
        )))
        assertTrue(gate.canViewWatchers)
    }

    @Test
    fun `PERMISSIVE constant is fail-open`() {
        assertTrue(PermissionGate.PERMISSIVE.canTransition)
        assertTrue(PermissionGate.PERMISSIVE.canComment)
        assertTrue(PermissionGate.PERMISSIVE.canViewWatchers)
        assertTrue(PermissionGate.PERMISSIVE.canLogWork)
    }
}
