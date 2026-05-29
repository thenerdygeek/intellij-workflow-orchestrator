package com.workflow.orchestrator.document.probe

import com.workflow.orchestrator.core.model.DocumentCursor
import com.workflow.orchestrator.document.service.DocumentArtifactStore
import com.workflow.orchestrator.document.service.TikaDocumentExtractor
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Corpus-gated proof that real-world table captions are indexed and resolvable via `section=`.
 *
 * Gated on the presence of /tmp/rd-corpus/inputs (skips silently when the corpus isn't dropped),
 * so this never breaks a clean checkout / CI. Run after re-extracting the corpus.
 *
 *   ./gradlew :document:test --tests "*TableAnchorCorpusProbe*" --rerun-tasks
 */
class TableAnchorCorpusProbe {

    @TempDir lateinit var work: Path
    private val inputDir = Paths.get("/tmp/rd-corpus/inputs")
    private val store = DocumentArtifactStore(TikaDocumentExtractor())

    @Test
    fun `microchip TABLE 1-2 is indexed and section navigation resolves it`() {
        val src = inputDir.resolve("spec-microchip-pic18f-datasheet.pdf")
        if (!Files.exists(src)) { println("[table-anchor-probe] corpus absent — skipping"); return }
        runBlocking {
            val hash = store.hashFile(src)
            val artifact = store.extractAndPersist(src, work.resolve("microchip-$hash"), hash, extractTimeoutMs = 300_000L)
            val index = store.loadIndex(artifact)

            println("[table-anchor-probe] microchip tables (${index.tables.size}): " +
                index.tables.take(8).joinToString(" | ") { it.key })

            assertTrue(index.tables.any { it.key.contains("TABLE 1-2", ignoreCase = true) },
                "TABLE 1-2 must be indexed; tables=${index.tables.map { it.key }.take(10)}")

            // section="Table 1-2" must resolve to a real table offset (non-zero) and report a hit.
            val slice = store.slice(artifact, index, DocumentCursor.Section("Table 1-2"), maxChars = 2_000)
            assertTrue(slice.sectionMatched == true, "section='Table 1-2' must be a hit")
            assertTrue(slice.startOffset > 0, "must resolve to a real offset, not a fallback to 0 (got ${slice.startOffset})")
            assertTrue(slice.content.contains("PINOUT", ignoreCase = true) || slice.content.contains("Table 1-2", ignoreCase = true),
                "the resolved region should be the pinout table; got head: ${slice.content.take(120)}")

            // Tables are surfaced in their OWN list (separate take/cap), not merged into sections.
            assertTrue(index.tables.isNotEmpty(), "table anchors must be populated independently")
            assertTrue(slice.availableTables.isNotEmpty(), "availableTables must be surfaced separately from availableSections")
        }
    }

    /**
     * In DICOM, "Request Header Fields" is ALSO a heading in dozens of sub-sections, so by the
     * heading-first precedence the title-only form correctly resolves to the first such HEADING,
     * not the table. The unambiguous proof that title→table works lives in the unit test
     * (DocumentIndexTableAnchorTest); here we prove the NUMBER form pins the actual table.
     */
    @Test
    fun `DICOM Table 5_2-1 resolves by number to the table region`() {
        val src = inputDir.resolve("spec-dicom-ps3.18-web-services.pdf")
        if (!Files.exists(src)) { println("[table-anchor-probe] corpus absent — skipping"); return }
        runBlocking {
            val hash = store.hashFile(src)
            val artifact = store.extractAndPersist(src, work.resolve("dicom-$hash"), hash, extractTimeoutMs = 300_000L)
            val index = store.loadIndex(artifact)

            println("[table-anchor-probe] dicom tables (${index.tables.size}): " +
                index.tables.take(8).joinToString(" | ") { it.key })

            assertTrue(index.tables.any { it.key.contains("Table 5.2-1", ignoreCase = true) },
                "Table 5.2-1 must be indexed; tables=${index.tables.map { it.key }.take(10)}")

            val byNumber = store.slice(artifact, index, DocumentCursor.Section("Table 5.2-1"), maxChars = 1_000)

            assertTrue(byNumber.sectionMatched == true && byNumber.startOffset > 0,
                "section='Table 5.2-1' must resolve to the table (offset=${byNumber.startOffset}, matched=${byNumber.sectionMatched})")
            assertTrue(byNumber.content.contains("Request Header Fields", ignoreCase = true) ||
                byNumber.content.contains("Table 5.2-1", ignoreCase = true),
                "the resolved region should be the Request Header Fields table; got head: ${byNumber.content.take(120)}")
        }
    }
}
