package com.workflow.orchestrator.document.service

import com.workflow.orchestrator.core.model.DocumentCursor
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import java.nio.file.Paths

class DocumentArtifactStoreFormatsTest {

    @TempDir lateinit var work: Path
    private val store = DocumentArtifactStore(TikaDocumentExtractor())

    private fun fixture(name: String) =
        Paths.get(javaClass.classLoader.getResource("fixtures/$name")!!.toURI())

    @ParameterizedTest
    @ValueSource(strings = [
        "spec-with-tables.pdf", "tabula-eu-002.pdf", "ietf-rfc7230.pdf",
        "bug-tracker.xlsx", "slides.pptx", "release-notes.rtf", "data.csv",
    ])
    fun `each format extracts, persists, and serves a non-empty offset-0 slice`(name: String) = runBlocking {
        val src = fixture(name)
        val hash = store.hashFile(src)
        val artDir = work.resolve(name).resolve(hash)

        val artifact = store.extractAndPersist(src, artDir, hash)
        val index = store.loadIndex(artifact)
        val slice = store.slice(artifact, index, DocumentCursor.Offset(0), maxChars = 2_000)

        assertTrue(artifact.meta.contentLength > 0, "$name produced empty content")
        assertFalse(slice.content.isEmpty(), "$name offset-0 slice empty")
        assertEquals(store.readContent(artifact).length, artifact.meta.contentLength)
    }

    @Test
    fun `pageless formats (xlsx, csv) have an empty page index but still serve by offset`() = runBlocking {
        for (name in listOf("bug-tracker.xlsx", "data.csv")) {
            val src = fixture(name)
            val hash = store.hashFile(src)
            val artDir = work.resolve("pageless-$name").resolve(hash)
            val artifact = store.extractAndPersist(src, artDir, hash)
            val index = store.loadIndex(artifact)
            assertTrue(index.pages.isEmpty(), "$name should have no page anchors")
            val slice = store.slice(artifact, index, DocumentCursor.Offset(0), maxChars = 500)
            assertFalse(slice.content.isEmpty())
        }
    }
}
