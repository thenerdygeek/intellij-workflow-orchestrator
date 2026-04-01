package com.workflow.orchestrator.agent.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class FileOwnershipRegistryTest {

    private lateinit var registry: FileOwnershipRegistry

    @BeforeEach
    fun setup() {
        registry = FileOwnershipRegistry()
    }

    @Test
    fun `claim grants ownership for unclaimed file`() {
        val result = registry.claim("/src/Auth.kt", "agent-1", WorkerType.CODER)
        assertEquals(ClaimResult.GRANTED, result.result)
        assertEquals("agent-1", registry.getOwner("/src/Auth.kt")?.agentId)
    }

    @Test
    fun `claim is idempotent for same agent`() {
        registry.claim("/src/Auth.kt", "agent-1", WorkerType.CODER)
        val result = registry.claim("/src/Auth.kt", "agent-1", WorkerType.CODER)
        assertEquals(ClaimResult.GRANTED, result.result)
    }

    @Test
    fun `claim denied when file owned by another agent`() {
        registry.claim("/src/Auth.kt", "agent-1", WorkerType.CODER)
        val result = registry.claim("/src/Auth.kt", "agent-2", WorkerType.CODER)
        assertEquals(ClaimResult.DENIED, result.result)
        assertEquals("agent-1", result.ownerAgentId)
    }

    @Test
    fun `release frees file for other agents`() {
        registry.claim("/src/Auth.kt", "agent-1", WorkerType.CODER)
        assertTrue(registry.release("/src/Auth.kt", "agent-1"))
        val result = registry.claim("/src/Auth.kt", "agent-2", WorkerType.CODER)
        assertEquals(ClaimResult.GRANTED, result.result)
    }

    @Test
    fun `release by wrong agent returns false`() {
        registry.claim("/src/Auth.kt", "agent-1", WorkerType.CODER)
        assertFalse(registry.release("/src/Auth.kt", "agent-2"))
    }

    @Test
    fun `releaseAll frees all files for agent`() {
        registry.claim("/src/Auth.kt", "agent-1", WorkerType.CODER)
        registry.claim("/src/User.kt", "agent-1", WorkerType.CODER)
        registry.claim("/src/Other.kt", "agent-2", WorkerType.CODER)
        val count = registry.releaseAll("agent-1")
        assertEquals(2, count)
        assertNull(registry.getOwner("/src/Auth.kt"))
        assertNull(registry.getOwner("/src/User.kt"))
        assertNotNull(registry.getOwner("/src/Other.kt"))
    }

    @Test
    fun `isOwnedByOther returns true for different agent`() {
        registry.claim("/src/Auth.kt", "agent-1", WorkerType.CODER)
        assertTrue(registry.isOwnedByOther("/src/Auth.kt", "agent-2"))
        assertFalse(registry.isOwnedByOther("/src/Auth.kt", "agent-1"))
    }

    @Test
    fun `listOwnedFiles returns files for specific agent`() {
        registry.claim("/src/Auth.kt", "agent-1", WorkerType.CODER)
        registry.claim("/src/User.kt", "agent-1", WorkerType.CODER)
        registry.claim("/src/Other.kt", "agent-2", WorkerType.CODER)
        val files = registry.listOwnedFiles("agent-1")
        assertEquals(2, files.size)
        assertTrue(files.contains("/src/Auth.kt"))
        assertTrue(files.contains("/src/User.kt"))
    }

    @Test
    fun `getOwner returns null for unclaimed file`() {
        assertNull(registry.getOwner("/src/Auth.kt"))
    }
}
