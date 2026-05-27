package com.workflow.orchestrator.agent.tools.integration

import com.workflow.orchestrator.core.model.DocumentArtifact
import com.workflow.orchestrator.core.model.DocumentArtifactMeta
import com.workflow.orchestrator.core.model.DocumentCursor
import com.workflow.orchestrator.core.model.DocumentIndex
import com.workflow.orchestrator.core.model.DocumentSlice
import com.workflow.orchestrator.document.service.DocumentArtifactStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SessionDocumentArtifactServiceResolveTest {

    @TempDir lateinit var sessionDir: Path

    private fun fakeArtifact(): DocumentArtifact {
        val d = Files.createDirectories(sessionDir.resolve("art"))
        Files.writeString(d.resolve("content.md"), "REAL CONTENT")
        return DocumentArtifact(DocumentArtifactMeta("h", "application/pdf", 12, 1, 0L), d.resolve("content.md"), d.resolve("index.json"))
    }

    private fun service(store: DocumentArtifactStore) = SessionDocumentArtifactService(
        store = store,
        cs = CoroutineScope(SupervisorJob()),
        cacheDirProvider = { sessionDir.resolve("document-cache") }, // → parent == sessionDir
        jobBudgetMs = 60_000,
    )

    private fun warmStore(): DocumentArtifactStore {
        val store = mockk<DocumentArtifactStore>(relaxed = true)
        coEvery { store.hashFile(any()) } returns "hash"
        coEvery { store.loadArtifact(any()) } returns fakeArtifact()
        coEvery { store.loadFailureIfFresh(any(), any(), any()) } returns null
        coEvery { store.loadIndex(any()) } returns DocumentIndex(listOf(DocumentIndex.Anchor("1", 0)), emptyList())
        coEvery { store.slice(any(), any(), any(), any()) } returns DocumentSlice("REAL CONTENT", 0, 12, 0, 1, 1)
        return store
    }

    @Test
    fun `wrong directory with correct filename resolves to the real download and notes the resolved path`() = runBlocking {
        // Real file under the session downloads tree:
        val real = sessionDir.resolve("downloads/jira-10042/design.pdf")
        Files.createDirectories(real.parent); Files.writeString(real, "bytes")

        val svc = service(warmStore())
        val r = svc.read(Path.of("/hallucinated/dir/design.pdf"), DocumentCursor.Offset(0), 100)

        assertFalse(r.isError)
        assertTrue(r.data!!.content.contains("resolved by filename"), "expected a resolution note in content: '${r.data!!.content}'")
        assertTrue(r.data!!.content.contains(real.toAbsolutePath().toString()), "note should include the real path")
        assertTrue(r.data!!.content.contains("REAL CONTENT"))
    }

    @Test
    fun `ambiguous filename returns an error listing candidates`() = runBlocking {
        Files.createDirectories(sessionDir.resolve("downloads/jira-1")); Files.writeString(sessionDir.resolve("downloads/jira-1/spec.pdf"), "a")
        Files.createDirectories(sessionDir.resolve("downloads/jira-2")); Files.writeString(sessionDir.resolve("downloads/jira-2/spec.pdf"), "b")

        val svc = service(warmStore())
        val r = svc.read(Path.of("/x/spec.pdf"), DocumentCursor.Offset(0), 100)

        assertTrue(r.isError)
        assertTrue(r.summary.contains("Multiple", ignoreCase = true) || r.summary.contains("match", ignoreCase = true))
    }

    @Test
    fun `existing path is used as-is with no note`() = runBlocking {
        val real = sessionDir.resolve("downloads/jira-3/ok.pdf")
        Files.createDirectories(real.parent); Files.writeString(real, "bytes")

        val svc = service(warmStore())
        val r = svc.read(real, DocumentCursor.Offset(0), 100)

        assertFalse(r.isError)
        assertFalse(r.data!!.content.contains("resolved by filename"))
    }
}
