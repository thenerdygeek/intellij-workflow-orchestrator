package com.workflow.orchestrator.core.model.sonar

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * isAdmin must reflect SonarQube's global `admin` permission specifically — not "any global
 * permission". A scan-only token previously reported admin, contradicting the agent guidance
 * that a 403 on admin-only actions means the token is non-admin.
 */
class SonarCurrentUserDataTest {

    private fun user(perms: List<String>) = SonarCurrentUserData(
        login = "u", name = "User", email = null, groups = emptyList(),
        globalPermissions = perms, externalProvider = null, isLoggedIn = true,
    )

    @Test
    fun `admin permission yields isAdmin true`() {
        assertTrue(user(listOf("scan", "admin")).isAdmin)
        assertTrue(user(listOf("ADMIN")).isAdmin) // case-insensitive
    }

    @Test
    fun `non-admin global permissions do not yield admin`() {
        assertFalse(user(listOf("scan", "provisioning")).isAdmin)
        assertFalse(user(emptyList()).isAdmin)
    }
}
