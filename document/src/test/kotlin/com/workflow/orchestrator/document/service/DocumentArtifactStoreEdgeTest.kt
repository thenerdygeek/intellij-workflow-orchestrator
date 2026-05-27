package com.workflow.orchestrator.document.service

import com.workflow.orchestrator.core.model.DocumentCursor
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Paths

class DocumentArtifactStoreEdgeTest {

    @TempDir lateinit var work: Path
    private val store = DocumentArtifactStore(TikaDocumentExtractor())
    private fun fixture(name: String) = Paths.get(javaClass.classLoader.getResource("fixtures/$name")!!.toURI())

    @Test
    fun `encrypted pdf extraction throws so the caller can write a failure marker`() {
        val pdf = EncryptedPdfFixtureFactory.create(work.resolve("enc.pdf"))
        val hash = runBlocking { store.hashFile(pdf) }
        assertThrows(Throwable::class.java) {
            runBlocking { store.extractAndPersist(pdf, work.resolve(hash), hash) }
        }
    }

    @Test
    fun `offset past end yields empty content and zero remaining on a real doc`() = runBlocking {
        val src = fixture("release-notes.rtf")
        val hash = store.hashFile(src)
        val artifact = store.extractAndPersist(src, work.resolve(hash), hash)
        val index = store.loadIndex(artifact)
        val slice = store.slice(artifact, index, DocumentCursor.Offset(Int.MAX_VALUE / 2), maxChars = 100)
        assertTrue(slice.content.isEmpty())
        assertEquals(0, slice.remaining)
    }

    @Test
    fun `page beyond count falls back to offset 0 (best-effort anchor)`() = runBlocking {
        val src = fixture("spec-with-tables.pdf")
        val hash = store.hashFile(src)
        val artifact = store.extractAndPersist(src, work.resolve(hash), hash)
        val index = store.loadIndex(artifact)
        val slice = store.slice(artifact, index, DocumentCursor.Page(9999), maxChars = 100)
        assertEquals(0, slice.startOffset)
    }
}
