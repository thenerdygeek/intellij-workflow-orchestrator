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

    // ── projectKey: cross-IDE agent-dir keying (Bug C, Windows separator divergence) ──

    @Test
    fun `projectKey makes a Windows backslash path equal to its forward-slash form`() {
        // IDE A derives the target agent dir from picked.path.toString() (backslashes on Windows);
        // IDE B uses project.basePath (forward slashes). Both must key to the SAME string so the
        // pending-delegation/ + declined markers land in the same dir. macOS masks this (all
        // forward-slash); this test reproduces the Windows divergence platform-independently.
        val backslash = """C:\Users\jdoe\Documents\01_Repos\acme-provider-simulator"""
        val forward = "C:/Users/jdoe/Documents/01_Repos/acme-provider-simulator"
        assertEquals(DelegationPaths.projectKey(forward), DelegationPaths.projectKey(backslash))
    }

    @Test
    fun `projectKey leaves an already system-independent path unchanged`() {
        val forward = "C:/Users/jdoe/repo"
        assertEquals(forward, DelegationPaths.projectKey(forward))
    }

    @Test
    fun `agentDirForDelegation resolves the same dir for backslash and forward-slash forms`() {
        // The single chokepoint both IDE-A (picked.path.toString()) and IDE-B (project.basePath)
        // route through. Same project must map to the same pending-delegation agent dir regardless
        // of which separator form the caller's path happened to use.
        val backslash = """C:\Users\jdoe\Documents\01_Repos\acme-provider-simulator"""
        val forward = "C:/Users/jdoe/Documents/01_Repos/acme-provider-simulator"
        assertEquals(
            DelegationPaths.agentDirForDelegation(forward),
            DelegationPaths.agentDirForDelegation(backslash),
        )
    }

    @Test
    fun `agentDirForDelegation points under the per-project agent dir`() {
        val dir = DelegationPaths.agentDirForDelegation("C:/Users/jdoe/repo").toString()
        assertTrue(dir.contains(".workflow-orchestrator"), "expected agent dir under .workflow-orchestrator: $dir")
        assertTrue(dir.endsWith("agent"), "expected the agent dir leaf: $dir")
    }
}
