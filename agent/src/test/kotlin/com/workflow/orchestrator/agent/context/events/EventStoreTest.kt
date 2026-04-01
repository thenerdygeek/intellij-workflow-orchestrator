package com.workflow.orchestrator.agent.context.events

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant

class EventStoreTest {

    // =========================================================================
    // add() — monotonic gapless IDs
    // =========================================================================

    @Test
    fun `add assigns monotonic gapless IDs starting at 0`() {
        val store = EventStore()
        val e0 = store.add(MessageAction(content = "first"), EventSource.USER)
        val e1 = store.add(MessageAction(content = "second"), EventSource.USER)
        val e2 = store.add(AgentThinkAction(thought = "thinking"), EventSource.AGENT)

        assertEquals(0, e0.id)
        assertEquals(1, e1.id)
        assertEquals(2, e2.id)
    }

    @Test
    fun `add sets timestamp between before and after Instant now`() {
        val store = EventStore()
        val before = Instant.now()
        val event = store.add(MessageAction(content = "hello"), EventSource.USER)
        val after = Instant.now()

        assertFalse(event.timestamp.isBefore(before), "Timestamp should not be before 'before'")
        assertFalse(event.timestamp.isAfter(after), "Timestamp should not be after 'after'")
    }

    @Test
    fun `add sets source correctly`() {
        val store = EventStore()
        val userEvent = store.add(MessageAction(content = "hi"), EventSource.USER)
        val agentEvent = store.add(AgentThinkAction(thought = "hmm"), EventSource.AGENT)
        val systemEvent = store.add(SystemMessageAction(content = "sys"), EventSource.SYSTEM)

        assertEquals(EventSource.USER, userEvent.source)
        assertEquals(EventSource.AGENT, agentEvent.source)
        assertEquals(EventSource.SYSTEM, systemEvent.source)
    }

    @Test
    fun `add returns immutable copy without modifying original sentinel values`() {
        val original = MessageAction(content = "hello")
        assertEquals(-1, original.id)
        assertEquals(Instant.EPOCH, original.timestamp)

        val store = EventStore()
        val stored = store.add(original, EventSource.USER)

        // Original is unchanged
        assertEquals(-1, original.id)
        assertEquals(Instant.EPOCH, original.timestamp)

        // Stored has assigned values
        assertEquals(0, stored.id)
        assertNotEquals(Instant.EPOCH, stored.timestamp)
    }

    // =========================================================================
    // get()
    // =========================================================================

    @Test
    fun `get returns event by ID`() {
        val store = EventStore()
        store.add(MessageAction(content = "zero"), EventSource.USER)
        store.add(MessageAction(content = "one"), EventSource.USER)
        store.add(MessageAction(content = "two"), EventSource.USER)

        val event = store.get(1)
        assertNotNull(event)
        assertEquals(1, event!!.id)
        assertTrue(event is MessageAction)
        assertEquals("one", (event as MessageAction).content)
    }

    @Test
    fun `get returns null for negative ID`() {
        val store = EventStore()
        store.add(MessageAction(content = "test"), EventSource.USER)
        assertNull(store.get(-1))
    }

    @Test
    fun `get returns null for ID beyond size`() {
        val store = EventStore()
        store.add(MessageAction(content = "test"), EventSource.USER)
        assertNull(store.get(1))
        assertNull(store.get(100))
    }

    @Test
    fun `get returns null on empty store`() {
        val store = EventStore()
        assertNull(store.get(0))
    }

    // =========================================================================
    // all() and size()
    // =========================================================================

    @Test
    fun `all returns snapshot copy of all events`() {
        val store = EventStore()
        store.add(MessageAction(content = "a"), EventSource.USER)
        store.add(MessageAction(content = "b"), EventSource.USER)

        val snapshot = store.all()
        assertEquals(2, snapshot.size)

        // Adding more events does not affect the snapshot
        store.add(MessageAction(content = "c"), EventSource.USER)
        assertEquals(2, snapshot.size)
        assertEquals(3, store.size())
    }

    @Test
    fun `size tracks event count`() {
        val store = EventStore()
        assertEquals(0, store.size())

        store.add(MessageAction(content = "a"), EventSource.USER)
        assertEquals(1, store.size())

        store.add(MessageAction(content = "b"), EventSource.USER)
        assertEquals(2, store.size())
    }

    // =========================================================================
    // slice()
    // =========================================================================

    @Test
    fun `slice returns correct range`() {
        val store = EventStore()
        repeat(5) { store.add(MessageAction(content = "msg-$it"), EventSource.USER) }

        val sliced = store.slice(1, 4)
        assertEquals(3, sliced.size)
        assertEquals(1, sliced[0].id)
        assertEquals(2, sliced[1].id)
        assertEquals(3, sliced[2].id)
    }

    @Test
    fun `slice clamps to valid range`() {
        val store = EventStore()
        repeat(3) { store.add(MessageAction(content = "msg-$it"), EventSource.USER) }

        val sliced = store.slice(-5, 100)
        assertEquals(3, sliced.size)
    }

    @Test
    fun `slice returns empty for invalid range`() {
        val store = EventStore()
        repeat(3) { store.add(MessageAction(content = "msg-$it"), EventSource.USER) }

        assertTrue(store.slice(3, 1).isEmpty())
        assertTrue(store.slice(5, 10).isEmpty())
    }

    // =========================================================================
    // JSONL persistence — round-trip
    // =========================================================================

    @Test
    fun `JSONL round-trip preserves all events`(@TempDir tempDir: File) {
        val store = EventStore(tempDir)
        store.add(MessageAction(content = "hello world"), EventSource.USER)
        store.add(AgentThinkAction(thought = "let me think"), EventSource.AGENT)
        store.add(
            FileReadAction(
                toolCallId = "tc1",
                responseGroupId = "rg1",
                path = "/src/Main.kt"
            ), EventSource.AGENT
        )
        store.appendToJsonl()

        // Reload from disk
        val loaded = EventStore.loadFromJsonl(tempDir)
        assertEquals(3, loaded.size())

        // Verify event 0
        val msg = loaded.get(0) as MessageAction
        assertEquals(0, msg.id)
        assertEquals("hello world", msg.content)
        assertEquals(EventSource.USER, msg.source)

        // Verify event 1
        val think = loaded.get(1) as AgentThinkAction
        assertEquals(1, think.id)
        assertEquals("let me think", think.thought)

        // Verify event 2
        val fileRead = loaded.get(2) as FileReadAction
        assertEquals(2, fileRead.id)
        assertEquals("/src/Main.kt", fileRead.path)
        assertEquals("tc1", fileRead.toolCallId)
    }

    @Test
    fun `JSONL incremental append writes only new events`(@TempDir tempDir: File) {
        val store = EventStore(tempDir)

        // First batch
        store.add(MessageAction(content = "first"), EventSource.USER)
        store.add(MessageAction(content = "second"), EventSource.USER)
        store.flush()

        val file = File(tempDir, EventStore.JSONL_FILENAME)
        val linesAfterFirstFlush = file.readLines().filter { it.isNotBlank() }
        assertEquals(2, linesAfterFirstFlush.size)

        // Second batch
        store.add(MessageAction(content = "third"), EventSource.USER)
        store.flush()

        val linesAfterSecondFlush = file.readLines().filter { it.isNotBlank() }
        assertEquals(3, linesAfterSecondFlush.size)

        // Verify full reload
        val loaded = EventStore.loadFromJsonl(tempDir)
        assertEquals(3, loaded.size())
        assertEquals("first", (loaded.get(0) as MessageAction).content)
        assertEquals("second", (loaded.get(1) as MessageAction).content)
        assertEquals("third", (loaded.get(2) as MessageAction).content)
    }

    @Test
    fun `flush is no-op when no sessionDir`() {
        val store = EventStore(sessionDir = null)
        store.add(MessageAction(content = "hello"), EventSource.USER)
        // Should not throw
        store.flush()
    }

    @Test
    fun `flush is no-op when no new events`(@TempDir tempDir: File) {
        val store = EventStore(tempDir)
        store.add(MessageAction(content = "hello"), EventSource.USER)
        store.flush()

        val file = File(tempDir, EventStore.JSONL_FILENAME)
        val sizeAfterFirst = file.length()

        // Flush again with no new events
        store.flush()
        assertEquals(sizeAfterFirst, file.length())
    }

    @Test
    fun `loadFromJsonl restores nextId correctly`(@TempDir tempDir: File) {
        val store = EventStore(tempDir)
        store.add(MessageAction(content = "a"), EventSource.USER)
        store.add(MessageAction(content = "b"), EventSource.USER)
        store.flush()

        val loaded = EventStore.loadFromJsonl(tempDir)
        // Adding a new event should get ID 2 (continuing from where we left off)
        val newEvent = loaded.add(MessageAction(content = "c"), EventSource.USER)
        assertEquals(2, newEvent.id)
    }

    @Test
    fun `loadFromJsonl handles missing file gracefully`(@TempDir tempDir: File) {
        val loaded = EventStore.loadFromJsonl(tempDir)
        assertEquals(0, loaded.size())
    }

    // =========================================================================
    // EventSerializer — various event types
    // =========================================================================

    @Test
    fun `MessageAction serializes and deserializes correctly`() {
        val event = MessageAction(
            content = "Hello\nWorld",
            imageUrls = listOf("http://img1.png", "http://img2.png"),
            id = 5,
            timestamp = Instant.ofEpochSecond(1700000000),
            source = EventSource.USER
        )
        val json = EventSerializer.serialize(event)
        val restored = EventSerializer.deserialize(json) as MessageAction

        assertEquals(5, restored.id)
        assertEquals("Hello\nWorld", restored.content)
        assertEquals(listOf("http://img1.png", "http://img2.png"), restored.imageUrls)
        assertEquals(EventSource.USER, restored.source)
    }

    @Test
    fun `CondensationAction with explicit IDs serializes and deserializes`() {
        val event = CondensationAction(
            forgottenEventIds = listOf(1, 3, 5),
            forgottenEventsStartId = null,
            forgottenEventsEndId = null,
            summary = "Summary text",
            summaryOffset = 1,
            id = 10,
            timestamp = Instant.ofEpochSecond(1700000000),
            source = EventSource.SYSTEM
        )
        val json = EventSerializer.serialize(event)
        val restored = EventSerializer.deserialize(json) as CondensationAction

        assertEquals(listOf(1, 3, 5), restored.forgottenEventIds)
        assertNull(restored.forgottenEventsStartId)
        assertNull(restored.forgottenEventsEndId)
        assertEquals("Summary text", restored.summary)
        assertEquals(1, restored.summaryOffset)
    }

    @Test
    fun `CondensationAction with range serializes and deserializes`() {
        val event = CondensationAction(
            forgottenEventIds = null,
            forgottenEventsStartId = 2,
            forgottenEventsEndId = 8,
            summary = null,
            summaryOffset = null,
            id = 11,
            timestamp = Instant.ofEpochSecond(1700000000),
            source = EventSource.SYSTEM
        )
        val json = EventSerializer.serialize(event)
        val restored = EventSerializer.deserialize(json) as CondensationAction

        assertNull(restored.forgottenEventIds)
        assertEquals(2, restored.forgottenEventsStartId)
        assertEquals(8, restored.forgottenEventsEndId)
    }

    @Test
    fun `FileReadAction serializes and deserializes correctly`() {
        val event = FileReadAction(
            toolCallId = "tc_abc",
            responseGroupId = "rg_def",
            path = "/path/to/file.kt",
            id = 3,
            timestamp = Instant.ofEpochSecond(1700000000),
            source = EventSource.AGENT
        )
        val json = EventSerializer.serialize(event)
        val restored = EventSerializer.deserialize(json) as FileReadAction

        assertEquals("tc_abc", restored.toolCallId)
        assertEquals("rg_def", restored.responseGroupId)
        assertEquals("/path/to/file.kt", restored.path)
        assertEquals(3, restored.id)
        assertEquals(EventSource.AGENT, restored.source)
    }

    @Test
    fun `ToolResultObservation serializes and deserializes correctly`() {
        val event = ToolResultObservation(
            toolCallId = "tc1",
            content = "File contents here\nline 2",
            isError = false,
            toolName = "read_file",
            id = 4,
            timestamp = Instant.ofEpochSecond(1700000000),
            source = EventSource.SYSTEM
        )
        val json = EventSerializer.serialize(event)
        val restored = EventSerializer.deserialize(json) as ToolResultObservation

        assertEquals("tc1", restored.toolCallId)
        assertEquals("File contents here\nline 2", restored.content)
        assertFalse(restored.isError)
        assertEquals("read_file", restored.toolName)
    }

    @Test
    fun `ToolResultObservation with error flag serializes correctly`() {
        val event = ToolResultObservation(
            toolCallId = "tc2",
            content = "Error: not found",
            isError = true,
            toolName = "read_file",
            id = 7,
            timestamp = Instant.ofEpochSecond(1700000000),
            source = EventSource.SYSTEM
        )
        val json = EventSerializer.serialize(event)
        val restored = EventSerializer.deserialize(json) as ToolResultObservation

        assertTrue(restored.isError)
    }

    @Test
    fun `MetaToolAction serializes and deserializes correctly`() {
        val event = MetaToolAction(
            toolCallId = "tc_meta",
            responseGroupId = "rg_meta",
            toolName = "jira",
            actionName = "get_ticket",
            arguments = """{"key":"PROJ-123"}""",
            id = 8,
            timestamp = Instant.ofEpochSecond(1700000000),
            source = EventSource.AGENT
        )
        val json = EventSerializer.serialize(event)
        val restored = EventSerializer.deserialize(json) as MetaToolAction

        assertEquals("jira", restored.toolName)
        assertEquals("get_ticket", restored.actionName)
        assertEquals("""{"key":"PROJ-123"}""", restored.arguments)
    }

    @Test
    fun `AgentFinishAction with outputs serializes correctly`() {
        val event = AgentFinishAction(
            finalThought = "Done",
            outputs = mapOf("result" to "success", "files" to "3"),
            id = 9,
            timestamp = Instant.ofEpochSecond(1700000000),
            source = EventSource.AGENT
        )
        val json = EventSerializer.serialize(event)
        val restored = EventSerializer.deserialize(json) as AgentFinishAction

        assertEquals("Done", restored.finalThought)
        assertEquals(mapOf("result" to "success", "files" to "3"), restored.outputs)
    }

    @Test
    fun `ErrorObservation with errorId serializes correctly`() {
        val event = ErrorObservation(
            content = "Something failed",
            errorId = "ERR_001",
            id = 12,
            timestamp = Instant.ofEpochSecond(1700000000),
            source = EventSource.SYSTEM
        )
        val json = EventSerializer.serialize(event)
        val restored = EventSerializer.deserialize(json) as ErrorObservation

        assertEquals("Something failed", restored.content)
        assertEquals("ERR_001", restored.errorId)
    }

    @Test
    fun `MentionAction serializes and deserializes correctly`() {
        val event = MentionAction(
            paths = listOf("/src/Main.kt", "/src/Utils.kt"),
            content = "file contents",
            id = 13,
            timestamp = Instant.ofEpochSecond(1700000000),
            source = EventSource.USER
        )
        val json = EventSerializer.serialize(event)
        val restored = EventSerializer.deserialize(json) as MentionAction

        assertEquals(listOf("/src/Main.kt", "/src/Utils.kt"), restored.paths)
        assertEquals("file contents", restored.content)
    }

    @Test
    fun `unknown event type throws IllegalArgumentException`() {
        val badJson = """{"type":"unknown_type","id":0,"timestamp":"1970-01-01T00:00:00Z","source":"SYSTEM"}"""
        assertThrows(IllegalArgumentException::class.java) {
            EventSerializer.deserialize(badJson)
        }
    }

    // =========================================================================
    // Thread safety (basic concurrent test)
    // =========================================================================

    @Test
    fun `concurrent adds produce gapless IDs`() {
        val store = EventStore()
        val threads = (0 until 10).map { threadIdx ->
            Thread {
                repeat(100) {
                    store.add(MessageAction(content = "t$threadIdx-$it"), EventSource.USER)
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals(1000, store.size())

        // Verify all IDs 0..999 are present and gapless
        val ids = store.all().map { it.id }.sorted()
        assertEquals((0 until 1000).toList(), ids)
    }
}
