package com.workflow.orchestrator.agent.loop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regression coverage for the `ClosedReceiveChannelException at AgentLoop.run` crash.
 *
 * The loop suspends on `userInputChannel.receive()` waiting for the user (plan-mode
 * approval, max-mistakes feedback, etc.). When the user clicks Cancel / New Chat,
 * `AgentController.clearActiveLoopState()` closes the channel. If it closes with no
 * cause, the suspended receive throws `ClosedReceiveChannelException` — which is NOT
 * a `CancellationException` and falls through to `AgentService`'s catch-all,
 * surfacing as "task execution failed" instead of a clean Cancelled result.
 *
 * Contract pinned here: when production code closes the channel with a
 * `CancellationException` cause, the suspended receive throws that
 * `CancellationException` rather than `ClosedReceiveChannelException`, which the
 * existing `catch (e: CancellationException)` arm in `AgentService` handles
 * correctly.
 */
class UserInputChannelCancellationTest {

    @Test
    fun `closing channel with CancellationException cause throws CancellationException at receive`() = runTest {
        val channel = Channel<String>(Channel.RENDEZVOUS)

        val receiver = async<Throwable?> {
            try {
                channel.receive()
                null
            } catch (e: Throwable) {
                e
            }
        }

        // Let the receiver suspend on receive()
        yield()

        // This is the pattern AgentController.clearActiveLoopState should use.
        channel.close(CancellationException("user cancelled task"))

        val caught = receiver.await()
        assertNotNull(caught, "Receiver should have thrown")
        assertTrue(
            caught is CancellationException,
            "Expected CancellationException — AgentService's existing cancel-catch handles this. " +
                "Got: ${caught!!.javaClass.name}"
        )
        assertFalse(
            caught is ClosedReceiveChannelException,
            "ClosedReceiveChannelException would fall through to the catch-all and surface as " +
                "'task execution failed' instead of Cancelled."
        )
    }

    @Test
    fun `closing channel without cause throws ClosedReceiveChannelException (demonstrates the bug we are fixing)`() = runTest {
        val channel = Channel<String>(Channel.RENDEZVOUS)

        val receiver = async<Throwable?> {
            try {
                channel.receive()
                null
            } catch (e: Throwable) {
                e
            }
        }

        yield()

        // The old (broken) pattern — close with no cause.
        channel.close()

        val caught = receiver.await()
        assertTrue(
            caught is ClosedReceiveChannelException,
            "Bare close() throws ClosedReceiveChannelException — this is what the old code produced " +
                "and why cancels were logged as 'task execution failed'."
        )
    }
}
