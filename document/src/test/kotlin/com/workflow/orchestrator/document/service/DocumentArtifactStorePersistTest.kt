package com.workflow.orchestrator.document.service

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class DocumentArtifactStorePersistTest {

    @TempDir lateinit var cacheRoot: Path

    private val store = DocumentArtifactStore(TikaDocumentExtractor())

    private fun fixture(name: String) =
        Paths.get(javaClass.classLoader.getResource("fixtures/$name")!!.toURI())

    @Test
    fun `extractAndPersist writes content, index and meta-as-sentinel`() = runBlocking {
        val src = fixture("spec-with-tables.pdf")
        val hash = store.hashFile(src)
        val artDir = cacheRoot.resolve(hash)

        val artifact = store.extractAndPersist(src, artDir, hash)

        assertTrue(Files.exists(artDir.resolve("content.md")))
        assertTrue(Files.exists(artDir.resolve("index.json")))
        assertTrue(Files.exists(artDir.resolve("meta.json")))
        assertEquals(hash, artifact.meta.contentHash)
        assertEquals("application/pdf", artifact.meta.mime)
        assertEquals(Files.readString(artDir.resolve("content.md")).length, artifact.meta.contentLength)
    }

    @Test
    fun `loadArtifact returns null when meta sentinel is absent (treated as cold)`() = runBlocking {
        val src = fixture("spec-with-tables.pdf")
        val hash = store.hashFile(src)
        val artDir = cacheRoot.resolve(hash)
        Files.createDirectories(artDir)
        Files.writeString(artDir.resolve("content.md"), "partial write, no meta")
        assertNull(store.loadArtifact(artDir))
    }

    @Test
    fun `loadArtifact round-trips a persisted artifact`() = runBlocking {
        val src = fixture("spec-with-tables.pdf")
        val hash = store.hashFile(src)
        val artDir = cacheRoot.resolve(hash)
        store.extractAndPersist(src, artDir, hash)
        val loaded = store.loadArtifact(artDir)!!
        assertEquals(hash, loaded.meta.contentHash)
    }

    @Test
    fun `hashFile is stable and changes when bytes change`() = runBlocking {
        val a = store.hashFile(fixture("spec-with-tables.pdf"))
        val b = store.hashFile(fixture("spec-with-tables.pdf"))
        val c = store.hashFile(fixture("tabula-eu-002.pdf"))
        assertEquals(a, b)
        assertTrue(a != c)
    }

    /**
     * Smoke test that the [extractTimeoutMs] parameter is accepted at call sites and that
     * passing an explicit value compiles and returns a valid artifact.  The precise forwarding
     * of the value into [com.workflow.orchestrator.core.model.ExtractOptions.timeoutMs] is
     * verified structurally — [DocumentArtifactStore.extractAndPersist] has no code path that
     * ignores [extractTimeoutMs]; the only statement that creates an [ExtractOptions] instance
     * now uses it directly (see the production source).  A MockK-based capture test was
     * evaluated but skipped: [TikaDocumentExtractor] is a `final` class that also executes
     * heavyweight Tika initialisation in `init {}`, and the `:document` module intentionally
     * carries no MockK test-dependency (dependency-lock + verification-metadata would both
     * need updating just for this one assertion).
     */
    @Test
    fun `extractAndPersist accepts an explicit extractTimeoutMs and produces a valid artifact`() = runBlocking {
        val src = fixture("spec-with-tables.pdf")
        val hash = store.hashFile(src)
        val artDir = cacheRoot.resolve("timeout-test-$hash")

        // Pass a non-default timeout value; the call must complete without throwing
        // (the real Tika extraction is fast enough on the test fixture).
        val artifact = store.extractAndPersist(src, artDir, hash, extractTimeoutMs = 300_000L)

        assertEquals(hash, artifact.meta.contentHash)
        assertTrue(Files.exists(artDir.resolve("meta.json")))
    }
}
