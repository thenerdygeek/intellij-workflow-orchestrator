package com.workflow.orchestrator.document.probe

import com.workflow.orchestrator.core.model.DocumentCursor
import com.workflow.orchestrator.document.service.DocumentArtifactStore
import com.workflow.orchestrator.document.service.TikaDocumentExtractor
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.streams.toList

/**
 * Opt-in empirical probe for the INDEXED read_document path (the one with section anchors).
 *
 * Unlike [EndToEndProbe] (which exercises the old single-shot [TikaDocumentExtractor.extract]),
 * this runs the full [DocumentArtifactStore.extractAndPersist] pipeline that produces
 * `index.json` — the page/section anchor table that the `section=` and `page=` cursors resolve
 * against. It then SIMULATES what the LLM experiences when it passes a `section=` value.
 *
 * Inputs:  every `*.pdf` under /tmp/rd-probe/inputs
 * Outputs: per-pdf `content.md` + `index.json` under /tmp/rd-probe/out/<name>/, plus a
 *          consolidated REPORT.md summarising section-anchor coverage and lookup hit/miss.
 *
 * Run with:
 *   ./gradlew :document:test --tests "*IndexedExtractionProbe*" --rerun-tasks
 */
class IndexedExtractionProbe {

    private val inputDir = Paths.get("/tmp/rd-probe/inputs")
    private val outDir = Paths.get("/tmp/rd-probe/out")

    @Test
    fun `indexed probe — extract, dump index, simulate section lookups`() {
        if (!Files.isDirectory(inputDir)) {
            println("[indexed-probe] no input dir at $inputDir — skipping")
            return
        }
        Files.createDirectories(outDir)
        val store = DocumentArtifactStore(TikaDocumentExtractor())

        val pdfs = Files.list(inputDir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".pdf", ignoreCase = true) }.toList().sorted()
        }

        val report = StringBuilder()
        report.appendLine("# read_document indexed-path probe")
        report.appendLine()
        report.appendLine("| PDF | bytes | pages | #sectionAnchors | #pageAnchors | exact-hit | slug-hit | natural-hit |")
        report.appendLine("|---|--:|--:|--:|--:|--:|--:|--:|")

        for (pdf in pdfs) {
            val name = pdf.fileName.toString()
            val artDir = outDir.resolve(name.replace('.', '_'))
            val bytes = Files.size(pdf)
            try {
                runBlocking {
                    val hash = store.hashFile(pdf)
                    val artifact = store.extractAndPersist(pdf, artDir, hash, extractTimeoutMs = 300_000L)
                    val index = store.loadIndex(artifact)
                    val md = store.readContent(artifact)

                    // ── Dump the section anchor table for manual inspection ──
                    val anchorDump = StringBuilder()
                    anchorDump.appendLine("# $name — section anchors (${index.sections.size})")
                    anchorDump.appendLine()
                    index.sections.forEachIndexed { i, a ->
                        anchorDump.appendLine("${i + 1}. [off=${a.offset}] ${a.key}")
                    }
                    Files.writeString(artDir.resolve("sections.txt"), anchorDump.toString())

                    // ── Simulate LLM section lookups ──
                    // (a) exact: pass back a real anchor key verbatim — should resolve != 0
                    val realKey = index.sections.firstOrNull { it.offset > 0 }?.key
                    val exactHit = realKey?.let { resolves(store, artifact, index, md, it) } ?: false

                    // (b) slug: kebab-case the real key the way an LLM tends to ("Fetch Product
                    //     Metadata" -> "fetch-product-metadata") — substring match should FAIL
                    val slugHit = realKey?.let {
                        val slug = it.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
                        resolves(store, artifact, index, md, slug)
                    } ?: false

                    // (c) natural: strip the leading "N.N " section number an LLM often omits
                    val naturalHit = realKey?.let {
                        val natural = it.replace(Regex("^\\d+(?:\\.\\d+)*\\.?\\s+"), "")
                        if (natural == it) false else resolves(store, artifact, index, md, natural)
                    } ?: false

                    report.appendLine(
                        "| $name | $bytes | ${artifact.meta.pageCount ?: "-"} | ${index.sections.size} | " +
                            "${index.pages.size} | ${tick(exactHit)} | ${tick(slugHit)} | ${tick(naturalHit)} |"
                    )
                    println("[indexed-probe] $name: ${index.sections.size} section anchors, ${index.pages.size} page anchors, ${md.length} chars")
                }
            } catch (e: Throwable) {
                report.appendLine("| $name | $bytes | ERROR | - | - | - | - | ${e.message?.take(40)} |")
                println("[indexed-probe] $name FAILED: ${e.message}")
            }
        }

        val reportPath = outDir.resolve("REPORT.md")
        Files.writeString(reportPath, report.toString())
        println("\n[indexed-probe] report → $reportPath\n")
        println(report)
    }

    /** True iff [needle] resolves to a NON-ZERO offset AND the served slice differs from a from-the-top read. */
    private suspend fun resolves(
        store: DocumentArtifactStore,
        artifact: com.workflow.orchestrator.core.model.DocumentArtifact,
        index: com.workflow.orchestrator.core.model.DocumentIndex,
        md: String,
        needle: String,
    ): Boolean {
        val resolved = index.offsetForSection(needle)
        if (resolved == null || resolved == 0) return false
        // Confirm the served content actually jumps (what the LLM would observe).
        val slice = store.slice(artifact, index, DocumentCursor.Section(needle), 2_000)
        return slice.startOffset != 0 && slice.content != md.take(slice.content.length)
    }

    private fun tick(b: Boolean) = if (b) "✅" else "❌"
}
