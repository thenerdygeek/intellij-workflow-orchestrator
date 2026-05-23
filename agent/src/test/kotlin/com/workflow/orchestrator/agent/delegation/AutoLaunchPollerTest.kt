package com.workflow.orchestrator.agent.delegation

import com.workflow.orchestrator.core.delegation.DelegationMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Path

@OptIn(ExperimentalCoroutinesApi::class)
class AutoLaunchPollerTest {

    @Test
    fun `succeeds on first ping that returns a Pong`() = runTest {
        var calls = 0
        val poller = AutoLaunchPoller(
            socketPath = Path.of("/tmp/fake.sock"),
            scope = this,
            pollIntervalMillis = 500L,
            timeoutMillis = 90_000L,
            pingFn = {
                calls++
                if (calls >= 3) DelegationMessage.Pong(projectPath = "/some/project") else null
            },
        )
        val result = poller.awaitOrTimeout()
        assertTrue(result is AutoLaunchOutcome.Ready, "Expected Ready outcome, got $result")
        assertEquals(3, calls)
    }

    @Test
    fun `times out after 90 seconds`() = runTest {
        val poller = AutoLaunchPoller(
            socketPath = Path.of("/tmp/fake.sock"),
            scope = this,
            pollIntervalMillis = 500L,
            timeoutMillis = 90_000L,
            pingFn = { null },
        )
        val outcome = poller.awaitOrTimeout()
        assertTrue(outcome is AutoLaunchOutcome.TimedOut, "Expected TimedOut, got $outcome")
    }
}
