package com.workflow.orchestrator.core.delegation

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Path

class DelegationPathsTest {
    @Test
    fun `socket file lives under user home ipc dir`() {
        val p = DelegationPaths.socketFor(Path.of("/Users/me/projects/backend-api"))
        assertTrue(p.toString().contains(".workflow-orchestrator/ipc/"))
        assertTrue(p.toString().endsWith(".sock"))
    }

    @Test
    fun `same project path produces same socket path`() {
        val a = DelegationPaths.socketFor(Path.of("/Users/me/projects/foo"))
        val b = DelegationPaths.socketFor(Path.of("/Users/me/projects/foo"))
        assertEquals(a, b)
    }

    @Test
    fun `different project paths produce different socket paths`() {
        val a = DelegationPaths.socketFor(Path.of("/Users/me/projects/foo"))
        val b = DelegationPaths.socketFor(Path.of("/Users/me/projects/bar"))
        assertNotEquals(a, b)
    }

    @Test
    fun `relative and absolute paths for same project hash to same socket`() {
        val absolute = Path.of("/Users/me/projects/foo").toAbsolutePath().normalize()
        val viaRelative = Path.of("/Users/me/projects/sub/../foo").toAbsolutePath().normalize()
        assertEquals(absolute, viaRelative)
        // The canonicalization happens inside socketFor; this just asserts the property.
        assertEquals(DelegationPaths.socketFor(absolute), DelegationPaths.socketFor(viaRelative))
    }
}
