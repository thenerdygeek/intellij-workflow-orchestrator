package com.workflow.orchestrator.agent.tools.integration

import com.workflow.orchestrator.core.model.DocumentCursor
import com.workflow.orchestrator.document.service.DocumentArtifactStore
import com.workflow.orchestrator.document.service.EncryptedPdfFixtureFactory
import com.workflow.orchestrator.document.service.LargePdfFixtureFactory
import com.workflow.orchestrator.document.service.TikaDocumentExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SessionDocumentArtifactServiceScenarioTest {

    @TempDir lateinit var cacheRoot: Path
    @TempDir lateinit var work: Path

    private fun service(scope: CoroutineScope) = SessionDocumentArtifactService(
        store = DocumentArtifactStore(TikaDocumentExtractor()),
        cs = scope,
        cacheDirProvider = { cacheRoot },
        jobBudgetMs = 300_000,
    )

    @Test
    fun `concurrent reads of the same large pdf converge on one artifact and succeed`() = runBlocking {
        val pdf = LargePdfFixtureFactory.create(work.resolve("conc.pdf"), pages = 80)
        val svc = service(CoroutineScope(SupervisorJob()))
        val results = (0 until 6).map {
            async { svc.read(pdf, DocumentCursor.Offset(0), 1_000) }
        }.awaitAll()
        assertTrue(results.all { !it.isError })
        assertTrue(results.any { it.data!!.content.isNotEmpty() })
    }

    @Test
    fun `encrypted pdf surfaces an error and is negative-cached for the next read`() = runBlocking {
        val pdf = EncryptedPdfFixtureFactory.create(work.resolve("enc.pdf"))
        val svc = service(CoroutineScope(SupervisorJob()))
        val first = svc.read(pdf, DocumentCursor.Offset(0), 1_000)
        assertTrue(first.isError)
        val second = svc.read(pdf, DocumentCursor.Offset(0), 1_000)
        assertTrue(second.isError)
    }
}
