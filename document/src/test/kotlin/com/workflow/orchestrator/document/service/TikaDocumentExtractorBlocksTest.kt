package com.workflow.orchestrator.document.service

import com.workflow.orchestrator.core.model.DocumentBlock
import com.workflow.orchestrator.core.model.ExtractOptions
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class TikaDocumentExtractorBlocksTest {

    private val extractor = TikaDocumentExtractor()

    private fun fixture(name: String) =
        Paths.get(javaClass.classLoader.getResource("fixtures/$name")!!.toURI())

    @Test
    fun `extractBlocks returns typed blocks and mime for a PDF`() = runBlocking {
        val result = extractor.extractBlocks(fixture("spec-with-tables.pdf"), ExtractOptions())
        assertFalse(result.isError, result.summary)
        val data = result.data!!
        assertTrue(data.blocks.isNotEmpty())
        assertTrue(data.mime == "application/pdf")
        assertTrue(data.blocks.any { it is DocumentBlock.Table })
    }
}
