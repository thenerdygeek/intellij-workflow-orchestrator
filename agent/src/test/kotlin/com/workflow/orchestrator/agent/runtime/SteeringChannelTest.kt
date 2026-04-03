package com.workflow.orchestrator.agent.runtime

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SteeringChannelTest {

    private lateinit var channel: SteeringChannel

    @BeforeEach
    fun setup() {
        channel = SteeringChannel()
    }

    @Test
    fun `drain returns empty list when no messages`() = runTest {
        val messages = channel.drain()
        assertTrue(messages.isEmpty())
    }

    @Test
    fun `enqueue and drain returns messages in FIFO order`() = runTest {
        channel.enqueue("Fix the tests first")
        channel.enqueue("Also check UserService")
        val messages = channel.drain()
        assertEquals(2, messages.size)
        assertEquals("Fix the tests first", messages[0].content)
        assertEquals("Also check UserService", messages[1].content)
    }

    @Test
    fun `drain clears the channel`() = runTest {
        channel.enqueue("message 1")
        channel.drain()
        val second = channel.drain()
        assertTrue(second.isEmpty())
    }

    @Test
    fun `hasPending returns true when messages exist`() {
        assertFalse(channel.hasPending())
        channel.enqueue("hello")
        assertTrue(channel.hasPending())
    }

    @Test
    fun `clear removes all pending messages`() {
        channel.enqueue("msg1")
        channel.enqueue("msg2")
        channel.clear()
        assertFalse(channel.hasPending())
    }

    @Test
    fun `concurrent enqueue is thread-safe`() = runTest {
        val threads = (1..100).map { i ->
            Thread { channel.enqueue("msg-$i") }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        val messages = channel.drain()
        assertEquals(100, messages.size)
    }

    @Test
    fun `steering message has timestamp`() {
        val before = System.currentTimeMillis()
        channel.enqueue("test")
        val after = System.currentTimeMillis()
        val msg = channel.drain().first()
        assertTrue(msg.timestampMs in before..after)
    }
}
