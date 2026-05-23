package com.workflow.orchestrator.agent.delegation

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PersistentHandleStoreTest {

    @Test
    fun `empty store reads back empty list`(@TempDir tmp: Path) {
        val store = PersistentHandleStore(sessionDir = tmp)
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `save then load round-trips a single handle`(@TempDir tmp: Path) {
        val store = PersistentHandleStore(sessionDir = tmp)
        val handle = PersistentHandleEntry(
            handleId = "h-1",
            targetProjectPath = "/repo/b",
            targetRepoName = "frontend",
            bSessionId = "sess-b-uuid",
            lastSeenState = "RUNNING",
            createdAt = 1716480000000L,
        )
        store.save(listOf(handle))
        val loaded = store.load()
        assertEquals(1, loaded.size)
        assertEquals("h-1", loaded[0].handleId)
        assertEquals("RUNNING", loaded[0].lastSeenState)
        assertEquals("sess-b-uuid", loaded[0].bSessionId)
    }

    @Test
    fun `unknown schemaVersion is rejected and returns empty`(@TempDir tmp: Path) {
        val file = tmp.resolve("delegation-handles.json")
        Files.writeString(file, """{"schemaVersion":99,"handles":[{"handleId":"h"}]}""")
        val store = PersistentHandleStore(sessionDir = tmp)
        assertTrue(store.load().isEmpty(), "Unknown schemaVersion must return empty list")
    }

    @Test
    fun `atomic write — tmp file does not linger`(@TempDir tmp: Path) {
        val store = PersistentHandleStore(sessionDir = tmp)
        store.save(listOf(
            PersistentHandleEntry(
                handleId = "h-2",
                targetProjectPath = "/repo/c",
                targetRepoName = "api",
                bSessionId = "sess-c",
                lastSeenState = "RUNNING",
                createdAt = 0L,
            )
        ))
        val tmpFile = tmp.resolve("delegation-handles.json.tmp")
        val mainFile = tmp.resolve("delegation-handles.json")
        assertFalse(Files.exists(tmpFile), "tmp file must be moved atomically, not left behind")
        assertTrue(Files.exists(mainFile))
    }

    @Test
    fun `corrupt JSON returns empty without throwing`(@TempDir tmp: Path) {
        val file = tmp.resolve("delegation-handles.json")
        Files.writeString(file, """{"schemaVersion":1,"handles":[ corrupt""")
        val store = PersistentHandleStore(sessionDir = tmp)
        assertTrue(store.load().isEmpty())
    }
}
