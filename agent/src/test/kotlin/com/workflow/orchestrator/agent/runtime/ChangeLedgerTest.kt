package com.workflow.orchestrator.agent.runtime

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

// Comprehensive tests for ChangeLedger — the core of the change tracking system.
// Tests cover: recording, querying, persistence, context string, checkpoints, rollback.
class ChangeLedgerTest {

    private lateinit var ledger: ChangeLedger

    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun setUp() {
        ledger = ChangeLedger()
        ledger.initialize(tempDir)
    }

    private fun makeEntry(
        id: String = "test-${System.nanoTime()}",
        iteration: Int = 1,
        filePath: String = "/src/Test.kt",
        relativePath: String = "src/Test.kt",
        action: ChangeAction = ChangeAction.MODIFIED,
        linesAdded: Int = 10,
        linesRemoved: Int = 3,
        checkpointId: String = "cp-123"
    ) = ChangeEntry(
        id = id,
        sessionId = "sess-1",
        iteration = iteration,
        timestamp = System.currentTimeMillis(),
        filePath = filePath,
        relativePath = relativePath,
        toolName = "edit_file",
        action = action,
        linesAdded = linesAdded,
        linesRemoved = linesRemoved,
        linesBefore = 100,
        linesAfter = 100 + linesAdded - linesRemoved,
        oldPreview = "old code...",
        newPreview = "new code...",
        editLineRange = "42-55",
        checkpointId = checkpointId
    )

    // ── Recording ──

    @Test
    fun `recordChange adds entry to ledger`() {
        ledger.recordChange(makeEntry())
        assertEquals(1, ledger.allEntries().size)
    }

    @Test
    fun `multiple entries accumulate`() {
        ledger.recordChange(makeEntry(id = "a"))
        ledger.recordChange(makeEntry(id = "b"))
        ledger.recordChange(makeEntry(id = "c"))
        assertEquals(3, ledger.allEntries().size)
    }

    // ── Querying ──

    @Test
    fun `changesForFile filters by file path`() {
        ledger.recordChange(makeEntry(filePath = "/src/A.kt", relativePath = "src/A.kt"))
        ledger.recordChange(makeEntry(filePath = "/src/B.kt", relativePath = "src/B.kt"))
        ledger.recordChange(makeEntry(filePath = "/src/A.kt", relativePath = "src/A.kt"))

        val aChanges = ledger.changesForFile("/src/A.kt")
        assertEquals(2, aChanges.size)

        val bChanges = ledger.changesForFile("/src/B.kt")
        assertEquals(1, bChanges.size)
    }

    @Test
    fun `changesForFile works with relative path too`() {
        ledger.recordChange(makeEntry(filePath = "/project/src/A.kt", relativePath = "src/A.kt"))

        val byRelative = ledger.changesForFile("src/A.kt")
        assertEquals(1, byRelative.size)
    }

    @Test
    fun `changesForIteration filters by iteration number`() {
        ledger.recordChange(makeEntry(iteration = 1))
        ledger.recordChange(makeEntry(iteration = 2))
        ledger.recordChange(makeEntry(iteration = 2))
        ledger.recordChange(makeEntry(iteration = 3))

        assertEquals(1, ledger.changesForIteration(1).size)
        assertEquals(2, ledger.changesForIteration(2).size)
        assertEquals(1, ledger.changesForIteration(3).size)
        assertEquals(0, ledger.changesForIteration(99).size)
    }

    // ── Statistics ──

    @Test
    fun `totalStats sums across all entries`() {
        ledger.recordChange(makeEntry(linesAdded = 10, linesRemoved = 3, filePath = "/a"))
        ledger.recordChange(makeEntry(linesAdded = 5, linesRemoved = 0, filePath = "/b"))
        ledger.recordChange(makeEntry(linesAdded = 0, linesRemoved = 7, filePath = "/c"))

        val stats = ledger.totalStats()
        assertEquals(15, stats.totalLinesAdded)
        assertEquals(10, stats.totalLinesRemoved)
        assertEquals(3, stats.filesModified)
    }

    @Test
    fun `totalStats counts unique files`() {
        ledger.recordChange(makeEntry(filePath = "/a", linesAdded = 5, linesRemoved = 0))
        ledger.recordChange(makeEntry(filePath = "/a", linesAdded = 3, linesRemoved = 1))
        ledger.recordChange(makeEntry(filePath = "/b", linesAdded = 2, linesRemoved = 0))

        val stats = ledger.totalStats()
        assertEquals(2, stats.filesModified) // /a and /b
        assertEquals(10, stats.totalLinesAdded) // 5+3+2
        assertEquals(1, stats.totalLinesRemoved)
    }

    @Test
    fun `fileStats groups by relative path`() {
        ledger.recordChange(makeEntry(relativePath = "src/A.kt", linesAdded = 10, linesRemoved = 2))
        ledger.recordChange(makeEntry(relativePath = "src/A.kt", linesAdded = 5, linesRemoved = 1))
        ledger.recordChange(makeEntry(relativePath = "src/B.kt", linesAdded = 20, linesRemoved = 0))

        val stats = ledger.fileStats()
        assertEquals(2, stats.size)

        val aStats = stats["src/A.kt"]!!
        assertEquals(2, aStats.editCount)
        assertEquals(15, aStats.totalLinesAdded)
        assertEquals(3, aStats.totalLinesRemoved)

        val bStats = stats["src/B.kt"]!!
        assertEquals(1, bStats.editCount)
        assertEquals(20, bStats.totalLinesAdded)
    }

    // ── Verification ──

    @Test
    fun `markVerified updates latest entry for file`() {
        ledger.recordChange(makeEntry(filePath = "/src/A.kt"))

        assertFalse(ledger.allEntries().last().verified)

        ledger.markVerified("/src/A.kt", true)
        assertTrue(ledger.allEntries().last().verified)
    }

    @Test
    fun `markVerified with error stores error message`() {
        ledger.recordChange(makeEntry(filePath = "/src/A.kt"))

        ledger.markVerified("/src/A.kt", false, "Syntax error at line 42")

        val entry = ledger.allEntries().last()
        assertFalse(entry.verified)
        assertEquals("Syntax error at line 42", entry.verificationError)
    }

    // ── Checkpoints ──

    @Test
    fun `recordCheckpoint stores checkpoint metadata`() {
        ledger.recordCheckpoint(CheckpointMeta(
            id = "cp-1", description = "Iteration 1", iteration = 1,
            timestamp = System.currentTimeMillis(),
            filesModified = listOf("A.kt"), totalLinesAdded = 10, totalLinesRemoved = 3
        ))

        val checkpoints = ledger.listCheckpoints()
        assertEquals(1, checkpoints.size)
        assertEquals("cp-1", checkpoints[0].id)
    }

    @Test
    fun `listCheckpoints ordered by iteration`() {
        ledger.recordCheckpoint(CheckpointMeta("cp-3", "Iter 3", 3, 0, emptyList(), 0, 0))
        ledger.recordCheckpoint(CheckpointMeta("cp-1", "Iter 1", 1, 0, emptyList(), 0, 0))
        ledger.recordCheckpoint(CheckpointMeta("cp-2", "Iter 2", 2, 0, emptyList(), 0, 0))

        val ordered = ledger.listCheckpoints()
        assertEquals(listOf(1, 2, 3), ordered.map { it.iteration })
    }

    // ── Persistence ──

    @Test
    fun `entries persist to changes_jsonl`() {
        ledger.recordChange(makeEntry(id = "persist-1"))
        ledger.recordChange(makeEntry(id = "persist-2"))

        val changesFile = File(tempDir, "changes.jsonl")
        assertTrue(changesFile.exists())

        val lines = changesFile.readLines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("persist-1"))
        assertTrue(lines[1].contains("persist-2"))
    }

    @Test
    fun `loadFromDisk restores entries`() {
        ledger.recordChange(makeEntry(id = "load-1", linesAdded = 7))
        ledger.recordChange(makeEntry(id = "load-2", linesAdded = 3))

        // Create a new ledger and load from same directory
        val ledger2 = ChangeLedger()
        ledger2.initialize(tempDir)
        ledger2.loadFromDisk()

        assertEquals(2, ledger2.allEntries().size)
        assertEquals(10, ledger2.totalStats().totalLinesAdded)
    }

    @Test
    fun `loadFromDisk handles empty file`() {
        File(tempDir, "changes.jsonl").writeText("")

        val ledger2 = ChangeLedger()
        ledger2.initialize(tempDir)
        ledger2.loadFromDisk()

        assertEquals(0, ledger2.allEntries().size)
    }

    @Test
    fun `loadFromDisk skips malformed lines`() {
        File(tempDir, "changes.jsonl").writeText("not json\n{invalid\n")

        val ledger2 = ChangeLedger()
        ledger2.initialize(tempDir)
        ledger2.loadFromDisk() // should not throw

        assertEquals(0, ledger2.allEntries().size)
    }

    // ── Context String (COMPRESSION) ──

    @Test
    fun `toContextString empty when no entries`() {
        assertEquals("", ledger.toContextString())
    }

    @Test
    fun `toContextString includes file summary`() {
        ledger.recordChange(makeEntry(relativePath = "src/A.kt", action = ChangeAction.MODIFIED, linesAdded = 10, linesRemoved = 3))

        val ctx = ledger.toContextString()
        assertTrue(ctx.contains("src/A.kt"))
        assertTrue(ctx.contains("+10/-3"))
        assertTrue(ctx.contains("MOD"))
    }

    @Test
    fun `toContextString shows CREATED for new files`() {
        ledger.recordChange(makeEntry(action = ChangeAction.CREATED, relativePath = "src/New.kt"))

        val ctx = ledger.toContextString()
        assertTrue(ctx.contains("NEW"))
    }

    @Test
    fun `toContextString shows totals`() {
        ledger.recordChange(makeEntry(relativePath = "a", linesAdded = 10, linesRemoved = 2, filePath = "/a"))
        ledger.recordChange(makeEntry(relativePath = "b", linesAdded = 5, linesRemoved = 1, filePath = "/b"))

        val ctx = ledger.toContextString()
        assertTrue(ctx.contains("2 files"), "Expected '2 files' in:\n$ctx")
        assertTrue(ctx.contains("+15/-3"), "Expected '+15/-3' in:\n$ctx")
    }

    @Test
    fun `toContextString shows checkpoint info`() {
        ledger.recordChange(makeEntry())
        ledger.recordCheckpoint(CheckpointMeta("cp-test", "Iter 1", 1, 0, emptyList(), 10, 3))

        val ctx = ledger.toContextString()
        assertTrue(ctx.contains("cp-test"))
        assertTrue(ctx.contains("rollback_changes"))
    }

    @Test
    fun `toContextString caps at MAX_ANCHOR_ENTRIES`() {
        // Add more than MAX_ANCHOR_ENTRIES files
        for (i in 0..ChangeLedger.MAX_ANCHOR_ENTRIES + 10) {
            ledger.recordChange(makeEntry(relativePath = "file-$i.kt", filePath = "/file-$i.kt"))
        }

        val ctx = ledger.toContextString()
        assertTrue(ctx.contains("most recent"))
    }

    // ── Rollback ──

    private fun makeRollback(
        id: String = "rb-${System.nanoTime()}",
        checkpointId: String = "cp-123",
        rolledBackEntryIds: List<String> = emptyList(),
        affectedFiles: List<String> = listOf("src/Test.kt"),
        source: RollbackSource = RollbackSource.LLM_TOOL,
        mechanism: RollbackMechanism = RollbackMechanism.LOCAL_HISTORY,
        scope: RollbackScope = RollbackScope.FULL_CHECKPOINT
    ) = RollbackEntry(
        id = id,
        timestamp = System.currentTimeMillis(),
        checkpointId = checkpointId,
        description = "Rollback to $checkpointId",
        source = source,
        mechanism = mechanism,
        affectedFiles = affectedFiles,
        rolledBackEntryIds = rolledBackEntryIds,
        scope = scope
    )

    @Test
    fun `recordRollback adds rollback entry`() {
        val rb = makeRollback()
        ledger.recordRollback(rb)

        val rollbacks = ledger.allRollbacks()
        assertEquals(1, rollbacks.size)
        assertEquals(rb.id, rollbacks[0].id)
    }

    @Test
    fun `totalStats excludes rolled-back entries`() {
        val entry1 = makeEntry(id = "e1", linesAdded = 10, linesRemoved = 2, filePath = "/a")
        val entry2 = makeEntry(id = "e2", linesAdded = 5, linesRemoved = 1, filePath = "/b")
        val entry3 = makeEntry(id = "e3", linesAdded = 20, linesRemoved = 0, filePath = "/c")
        ledger.recordChange(entry1)
        ledger.recordChange(entry2)
        ledger.recordChange(entry3)

        // Roll back entry1 and entry2
        ledger.recordRollback(makeRollback(rolledBackEntryIds = listOf("e1", "e2")))

        val stats = ledger.totalStats()
        // Only entry3 should count
        assertEquals(20, stats.totalLinesAdded)
        assertEquals(0, stats.totalLinesRemoved)
        assertEquals(1, stats.filesModified)
    }

    @Test
    fun `fileStats excludes rolled-back entries`() {
        val entry1 = makeEntry(id = "e1", relativePath = "src/A.kt", linesAdded = 10, linesRemoved = 2)
        val entry2 = makeEntry(id = "e2", relativePath = "src/A.kt", linesAdded = 5, linesRemoved = 1)
        val entry3 = makeEntry(id = "e3", relativePath = "src/B.kt", linesAdded = 20, linesRemoved = 0)
        ledger.recordChange(entry1)
        ledger.recordChange(entry2)
        ledger.recordChange(entry3)

        // Roll back entry1
        ledger.recordRollback(makeRollback(rolledBackEntryIds = listOf("e1")))

        val stats = ledger.fileStats()
        val aStats = stats["src/A.kt"]!!
        assertEquals(1, aStats.editCount) // Only entry2
        assertEquals(5, aStats.totalLinesAdded)
        assertEquals(1, aStats.totalLinesRemoved)

        val bStats = stats["src/B.kt"]!!
        assertEquals(1, bStats.editCount)
        assertEquals(20, bStats.totalLinesAdded)
    }

    @Test
    fun `toContextString marks rolled-back entries`() {
        val entry1 = makeEntry(
            id = "e1", relativePath = "src/Reverted.kt", filePath = "/src/Reverted.kt",
            linesAdded = 10, linesRemoved = 2
        )
        val entry2 = makeEntry(
            id = "e2", relativePath = "src/Kept.kt", filePath = "/src/Kept.kt",
            linesAdded = 5, linesRemoved = 1
        )
        ledger.recordChange(entry1)
        ledger.recordChange(entry2)

        ledger.recordRollback(
            makeRollback(
                rolledBackEntryIds = listOf("e1"),
                affectedFiles = listOf("src/Reverted.kt")
            )
        )

        val ctx = ledger.toContextString()
        assertTrue(ctx.contains("[REVERTED]"), "Expected [REVERTED] in:\n$ctx")
        assertTrue(ctx.contains("src/Reverted.kt"), "Expected reverted file in:\n$ctx")
        assertTrue(ctx.contains("src/Kept.kt"), "Expected kept file in:\n$ctx")
    }

    @Test
    fun `rollback entries persist and reload`() {
        val entry1 = makeEntry(id = "e1", linesAdded = 10, linesRemoved = 0, filePath = "/a")
        ledger.recordChange(entry1)
        ledger.recordRollback(makeRollback(id = "rb-1", rolledBackEntryIds = listOf("e1")))

        // Create a new ledger and load from same directory
        val ledger2 = ChangeLedger()
        ledger2.initialize(tempDir)
        ledger2.loadFromDisk()

        assertEquals(1, ledger2.allRollbacks().size)
        assertEquals("rb-1", ledger2.allRollbacks()[0].id)
        // totalStats should exclude rolled-back entry
        assertEquals(0, ledger2.totalStats().totalLinesAdded)
    }

    @Test
    fun `entriesAfterCheckpoint returns correct entries`() {
        ledger.recordCheckpoint(CheckpointMeta("cp-1", "Iter 2", 2, 0, emptyList(), 0, 0))

        val entry1 = makeEntry(id = "e1", iteration = 1, checkpointId = "cp-1")
        val entry2 = makeEntry(id = "e2", iteration = 2, checkpointId = "cp-1")
        val entry3 = makeEntry(id = "e3", iteration = 3, checkpointId = "cp-2")
        val entry4 = makeEntry(id = "e4", iteration = 4, checkpointId = "cp-2")
        ledger.recordChange(entry1)
        ledger.recordChange(entry2)
        ledger.recordChange(entry3)
        ledger.recordChange(entry4)

        val after = ledger.entriesAfterCheckpoint("cp-1")
        assertEquals(2, after.size)
        assertEquals("e3", after[0].id)
        assertEquals("e4", after[1].id)
    }
}
