package com.workflow.orchestrator.core.delegation

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Path

class DelegationDoorbellProtocolTest {
    private val json = Json { classDiscriminator = "type"; encodeDefaults = true }

    @Test
    fun `Knock round-trips through framing json`() {
        val k = DelegationMessage.Knock(
            delegatorIde = "ide-1", delegatorRepo = "backend",
            delegatorSessionId = "s1", requestPreview = "do X", nonce = "n1",
        )
        val decoded = json.decodeFromString(DelegationMessage.serializer(), json.encodeToString(DelegationMessage.serializer(), k))
        assertEquals(k, decoded)
    }

    @Test
    fun `KnockAck carries outcome`() {
        val a = DelegationMessage.KnockAck(nonce = "n1", outcome = KnockOutcome.RINGING)
        val decoded = json.decodeFromString(DelegationMessage.serializer(), json.encodeToString(DelegationMessage.serializer(), a))
        assertEquals(KnockOutcome.RINGING, (decoded as DelegationMessage.KnockAck).outcome)
    }

    @Test
    fun `Connect preauthNonce defaults to null and round-trips when set`() {
        val c = DelegationMessage.Connect(
            delegatorIde = "ide-1", delegatorRepo = "backend",
            delegatorSessionId = "s1", request = "do X", preauthNonce = "n1",
        )
        val decoded = json.decodeFromString(DelegationMessage.serializer(), json.encodeToString(DelegationMessage.serializer(), c)) as DelegationMessage.Connect
        assertEquals("n1", decoded.preauthNonce)
    }

    @Test
    fun `doorbellSocketFor differs from socketFor for same project`() {
        val p = Path.of("/tmp/projA")
        assertNotEquals(DelegationPaths.socketFor(p).fileName.toString(),
                        DelegationPaths.doorbellSocketFor(p).fileName.toString())
    }
}
