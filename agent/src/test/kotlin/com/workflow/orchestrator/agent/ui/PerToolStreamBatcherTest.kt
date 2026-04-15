package com.workflow.orchestrator.agent.ui

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

/**
 * Tests for [PerToolStreamBatcher] — the per-tool-call stream batching class that coalesces
 * rapid LLM stream chunks into batched EDT dispatches, keyed by toolCallId.
 *
 * All tests pass `invoker = { block -> block() }` so that every flush operation runs
 * synchronously, making assertions deterministic without needing timers or sleeps.
 */
@DisplayName("PerToolStreamBatcher: per-tool-call output batching")
class PerToolStreamBatcherTest {

    /** Captures (toolCallId, batched) pairs in the order they were delivered. */
    private val flushed = CopyOnWriteArrayList<Pair<String, String>>()
    private lateinit var batcher: PerToolStreamBatcher

    @BeforeEach
    fun setup() {
        flushed.clear()
        batcher = PerToolStreamBatcher(
            onFlush = { id, text -> flushed.add(id to text) },
            invoker = { block -> block() }
        )
    }

    /**
     * Always dispose the batcher after each test so IntelliJ's [SwingTimerWatcherExtension]
     * does not fail with "Not disposed javax.swing.Timer". Tests that use flush() / flush(id)
     * without full drain may leave the timer running.
     */
    @AfterEach
    fun tearDown() {
        batcher.dispose()
    }

    // ════════════════════════════════════════════
    //  1. Per-ID isolation
    // ════════════════════════════════════════════

    @Nested
    @DisplayName("Per-ID isolation — each toolCallId has its own buffer")
    inner class PerIdIsolation {

        @Test
        fun `append to two IDs interleaved then full flush delivers correct batches`() {
            batcher.append("tool1", "A")
            batcher.append("tool2", "X")
            batcher.append("tool1", "B")
            batcher.append("tool2", "Y")

            batcher.flush() // full drain

            assertEquals(2, flushed.size)
            val byId = flushed.associateBy { it.first }
            assertEquals("AB", byId["tool1"]?.second)
            assertEquals("XY", byId["tool2"]?.second)
        }

        @Test
        fun `chunks from different IDs never cross-contaminate`() {
            batcher.append("alpha", "hello")
            batcher.append("beta", "world")
            batcher.append("gamma", "!")

            batcher.flush()

            assertEquals(3, flushed.size)
            val byId = flushed.associateBy { it.first }
            assertEquals("hello", byId["alpha"]?.second)
            assertEquals("world", byId["beta"]?.second)
            assertEquals("!", byId["gamma"]?.second)
        }
    }

    // ════════════════════════════════════════════
    //  2. Within-ID ordering
    // ════════════════════════════════════════════

    @Nested
    @DisplayName("Within-ID ordering — chunks for the same ID are concatenated in order")
    inner class WithinIdOrdering {

        @Test
        fun `three sequential appends to same ID produce concatenated string`() {
            batcher.append("t1", "1")
            batcher.append("t1", "2")
            batcher.append("t1", "3")

            batcher.flush()

            assertEquals(1, flushed.size)
            assertEquals("t1", flushed[0].first)
            assertEquals("123", flushed[0].second)
        }

        @Test
        fun `many appends to same ID are all concatenated`() {
            repeat(10) { i -> batcher.append("myTool", "$i") }
            batcher.flush()

            assertEquals(1, flushed.size)
            assertEquals("0123456789", flushed[0].second)
        }
    }

    // ════════════════════════════════════════════
    //  3. flush(toolCallId) drains only that ID
    // ════════════════════════════════════════════

    @Nested
    @DisplayName("flush(id) — synchronous per-key drain")
    inner class PerKeyFlush {

        @Test
        fun `flush of specific ID delivers only that ID and leaves the other pending`() {
            batcher.append("a", "hello")
            batcher.append("b", "world")

            batcher.flush("a")

            assertEquals(1, flushed.size, "Only ID 'a' should have been flushed")
            assertEquals("a" to "hello", flushed[0])

            // Now flush the other ID
            batcher.flush("b")

            assertEquals(2, flushed.size)
            assertEquals("b" to "world", flushed[1])
        }

        @Test
        fun `flushing one ID does not affect accumulated text for another`() {
            batcher.append("x", "chunk1")
            batcher.append("y", "partial")
            batcher.append("x", "chunk2")

            batcher.flush("x")

            assertEquals(1, flushed.size)
            assertEquals("x" to "chunk1chunk2", flushed[0])

            // "y" still has its accumulated text
            batcher.flush("y")
            assertEquals(2, flushed.size)
            assertEquals("y" to "partial", flushed[1])
        }
    }

    // ════════════════════════════════════════════
    //  4. flush(toolCallId) for unknown ID is a no-op
    // ════════════════════════════════════════════

    @Nested
    @DisplayName("flush(unknown) — no crash, no callback")
    inner class UnknownIdFlush {

        @Test
        fun `flushing an unknown ID does not crash and does not call onFlush`() {
            assertDoesNotThrow {
                batcher.flush("nonexistent-tool-id")
            }
            assertEquals(0, flushed.size)
        }

        @Test
        fun `flushing an already-flushed ID is also a no-op`() {
            batcher.append("t1", "text")
            batcher.flush("t1")
            assertEquals(1, flushed.size)

            // Second flush for the same key — buffer already removed
            assertDoesNotThrow {
                batcher.flush("t1")
            }
            assertEquals(1, flushed.size, "No second callback should have fired")
        }
    }

    // ════════════════════════════════════════════
    //  5. Full flush() drains all buffers
    // ════════════════════════════════════════════

    @Nested
    @DisplayName("flush() — full synchronous drain of all buffers")
    inner class FullFlush {

        @Test
        fun `full flush delivers all pending IDs`() {
            batcher.append("id1", "aaa")
            batcher.append("id2", "bbb")
            batcher.append("id3", "ccc")

            batcher.flush()

            assertEquals(3, flushed.size)
            val byId = flushed.associateBy { it.first }
            assertEquals("aaa", byId["id1"]?.second)
            assertEquals("bbb", byId["id2"]?.second)
            assertEquals("ccc", byId["id3"]?.second)
        }

        @Test
        fun `full flush on empty batcher is a no-op`() {
            assertDoesNotThrow { batcher.flush() }
            assertEquals(0, flushed.size)
        }

        @Test
        fun `buffers are empty after full flush`() {
            batcher.append("k1", "v1")
            batcher.flush()
            assertEquals(1, flushed.size)

            // Second full flush — buffers already cleared
            batcher.flush()
            assertEquals(1, flushed.size, "No duplicate callbacks on second flush")
        }

        @Test
        fun `full flush preserves insertion order across IDs`() {
            batcher.append("first", "1")
            batcher.append("second", "2")
            batcher.append("third", "3")

            batcher.flush()

            assertEquals(3, flushed.size)
            assertEquals("first", flushed[0].first)
            assertEquals("second", flushed[1].first)
            assertEquals("third", flushed[2].first)
        }
    }

    // ════════════════════════════════════════════
    //  6. Disposal drops buffers
    // ════════════════════════════════════════════

    @Nested
    @DisplayName("dispose() — buffered data is dropped, no further flush fires")
    inner class Disposal {

        @Test
        fun `append after dispose never fires onFlush`() {
            batcher.dispose()
            batcher.append("t1", "should-be-dropped")

            // No way to trigger a flush after dispose, but we can call flush() to confirm
            batcher.flush()
            assertEquals(0, flushed.size)
        }

        @Test
        fun `buffers accumulated before dispose are silently dropped on dispose`() {
            batcher.append("t1", "before-dispose")
            batcher.dispose()

            // After dispose, calling flush() should not deliver pre-dispose data
            batcher.flush()
            assertEquals(0, flushed.size)
        }

        @Test
        fun `flush(id) after dispose delivers nothing`() {
            batcher.append("t1", "data")
            batcher.dispose()

            batcher.flush("t1")
            assertEquals(0, flushed.size)
        }

        @Test
        fun `dispose is idempotent`() {
            batcher.append("t1", "data")
            assertDoesNotThrow {
                batcher.dispose()
                batcher.dispose()
            }
            assertEquals(0, flushed.size)
        }
    }

    // ════════════════════════════════════════════
    //  7. Concurrent appends preserve per-ID ordering
    // ════════════════════════════════════════════

    @Nested
    @DisplayName("Thread safety — concurrent appends to the same ID")
    inner class ConcurrentSingleId {

        @Test
        fun `N threads appending to the same ID preserve total chunk count`() {
            val n = 50
            val chunkSize = 3
            val latch = CountDownLatch(1)

            val threads = (0 until n).map { i ->
                Thread {
                    latch.await(5, TimeUnit.SECONDS)
                    // Each thread appends one 3-char chunk like "000", "001", ...
                    batcher.append("t1", i.toString().padStart(chunkSize, '0'))
                }
            }

            threads.forEach { it.start() }
            latch.countDown()
            threads.forEach { it.join(5000) }

            batcher.flush()

            assertEquals(1, flushed.size, "All appends to 't1' should coalesce into one callback")
            val text = flushed[0].second
            assertEquals(n * chunkSize, text.length,
                "Total length should be N * chunkSize (no drops/duplicates): got '${text.length}', expected ${n * chunkSize}")
        }
    }

    // ════════════════════════════════════════════
    //  8. Multiple IDs concurrently — no cross-contamination
    // ════════════════════════════════════════════

    @Nested
    @DisplayName("Thread safety — 10 threads each appending to their own unique ID")
    inner class ConcurrentMultipleIds {

        @Test
        fun `each thread's data lands only in its own toolCallId buffer`() {
            val idCount = 10
            val barrier = CyclicBarrier(idCount)

            val threads = (0 until idCount).map { i ->
                Thread {
                    barrier.await(5, TimeUnit.SECONDS)
                    batcher.append("id-$i", "value-$i")
                }
            }

            threads.forEach { it.start() }
            threads.forEach { it.join(5000) }

            batcher.flush()

            assertEquals(idCount, flushed.size, "Each ID should produce exactly one callback")

            val byId = flushed.associateBy { it.first }
            for (i in 0 until idCount) {
                val key = "id-$i"
                assertNotNull(byId[key], "Expected callback for '$key' but it was missing")
                assertEquals("value-$i", byId[key]?.second,
                    "Value for '$key' was contaminated: expected 'value-$i', got '${byId[key]?.second}'")
            }
        }

        @Test
        fun `heavy concurrent writes to multiple IDs produce no cross-contamination`() {
            val idCount = 5
            val appendsPerThread = 20
            val latch = CountDownLatch(1)

            // Each thread appends its own digit character `appendsPerThread` times
            val threads = (0 until idCount).map { i ->
                Thread {
                    latch.await(5, TimeUnit.SECONDS)
                    repeat(appendsPerThread) {
                        batcher.append("heavy-$i", "$i")
                    }
                }
            }

            threads.forEach { it.start() }
            latch.countDown()
            threads.forEach { it.join(5000) }

            batcher.flush()

            assertEquals(idCount, flushed.size)
            val byId = flushed.associateBy { it.first }

            for (i in 0 until idCount) {
                val key = "heavy-$i"
                val text = byId[key]?.second
                assertNotNull(text, "Missing callback for '$key'")
                assertEquals(appendsPerThread, text!!.length,
                    "Wrong length for '$key': expected $appendsPerThread, got ${text.length}")
                assertTrue(text.all { c -> c == "$i"[0] },
                    "Cross-contamination detected in '$key': '$text' contains chars from other IDs")
            }
        }
    }
}
