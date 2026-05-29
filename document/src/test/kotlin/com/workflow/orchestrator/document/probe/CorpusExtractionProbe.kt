package com.workflow.orchestrator.document.probe

import com.workflow.orchestrator.document.service.DocumentArtifactStore
import com.workflow.orchestrator.document.service.TikaDocumentExtractor
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.streams.toList

/**
 * Opt-in, FORMAT-AGNOSTIC empirical probe for the indexed read_document path.
 *
 * Unlike [IndexedExtractionProbe] (PDF-only, section-lookup focused), this runs the full
 * [DocumentArtifactStore.extractAndPersist] pipeline against EVERY file in a corpus dir
 * regardless of extension (PDF / DOCX / XLSX / PPTX / CSV / HTML / TXT …) and dumps rich
 * per-file artifacts so an out-of-band analyst can compare extracted output against the raw
 * file and surface extraction defects (dropped tables, mangled JSON, lost hyperlinks, missing
 * navigation anchors, structure loss).
 *
 * Inputs:  every regular file under /tmp/rd-corpus/inputs (drop the corpus there)
 * Outputs: per-file <name>/{content.md, sections.txt, pages.txt, summary.txt} under
 *          /tmp/rd-corpus/out, plus a consolidated REPORT.md.
 *
 * Run with:
 *   ./gradlew :document:test --tests "*CorpusExtractionProbe*" --rerun-tasks
 *
 * Path is hardcoded (mirrors [IndexedExtractionProbe]) because Gradle does not forward `-D`
 * system properties to the forked test JVM. Skips silently when the corpus dir is absent, so
 * it is safe to leave in tree.
 */
class CorpusExtractionProbe {

    private val inputDir: Path = Paths.get("/tmp/rd-corpus/inputs")
    private val outDir: Path = Paths.get("/tmp/rd-corpus/out")

    private val urlRegex = Regex("""https?://[^\s)\]<>"']+""")
    private val pipeRowRegex = Regex("""^\s*\|.*\|\s*$""")

    @Test
    fun `corpus probe — extract every file, dump artifacts, summarise extraction fidelity`() {
        if (!Files.isDirectory(inputDir)) {
            println("[corpus-probe] no input dir at $inputDir — skipping")
            return
        }
        Files.createDirectories(outDir)
        val store = DocumentArtifactStore(TikaDocumentExtractor())

        val files = Files.list(inputDir).use { stream ->
            stream.filter { Files.isRegularFile(it) && !it.fileName.toString().startsWith(".") }
                .toList().sortedBy { it.fileName.toString() }
        }

        val report = StringBuilder()
        report.appendLine("# read_document corpus extraction probe")
        report.appendLine()
        report.appendLine("| file | ext | bytes | pages | #sectionAnchors | #pageAnchors | tableRows | imageMarkers | embeddedObjs | urlRefs | chars | status |")
        report.appendLine("|---|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|---|")

        for (file in files) {
            val name = file.fileName.toString()
            val ext = name.substringAfterLast('.', "").lowercase()
            val artDir = outDir.resolve(name.replace('.', '_'))
            val bytes = Files.size(file)
            try {
                runBlocking {
                    val hash = store.hashFile(file)
                    val artifact = store.extractAndPersist(file, artDir, hash, extractTimeoutMs = 300_000L)
                    val index = store.loadIndex(artifact)
                    val md = store.readContent(artifact)

                    Files.writeString(artDir.resolve("content.md"), md)

                    val sectionsDump = StringBuilder("# $name — section anchors (${index.sections.size})\n\n")
                    index.sections.forEachIndexed { i, a -> sectionsDump.appendLine("${i + 1}. [off=${a.offset}] ${a.key}") }
                    Files.writeString(artDir.resolve("sections.txt"), sectionsDump.toString())

                    val pagesDump = StringBuilder("# $name — page anchors (${index.pages.size})\n\n")
                    index.pages.forEach { a -> pagesDump.appendLine("[off=${a.offset}] page ${a.key}") }
                    Files.writeString(artDir.resolve("pages.txt"), pagesDump.toString())

                    val tableRows = md.lineSequence().count { pipeRowRegex.matches(it) }
                    val imageMarkers = Regex("""\[image:""").findAll(md).count()
                    // G-7 / IMG-3: SmartArt / shape / OLE presence placeholders. Counted
                    // separately from imageMarkers — these are non-viewable objects, not images.
                    val embeddedObjs = Regex("""\[(SmartArt|Shape|Embedded object)\b""").findAll(md).count()
                    val urlRefs = urlRegex.findAll(md).count()

                    val summary = buildString {
                        appendLine("file: $name")
                        appendLine("ext: $ext")
                        appendLine("bytes: $bytes")
                        appendLine("pageCount(meta): ${artifact.meta.pageCount ?: "-"}")
                        appendLine("sectionAnchors: ${index.sections.size}")
                        appendLine("pageAnchors: ${index.pages.size}")
                        appendLine("pipeTableRows: $tableRows")
                        appendLine("imageMarkers: $imageMarkers")
                        appendLine("embeddedObjects: $embeddedObjs")
                        appendLine("urlOccurrences: $urlRefs")
                        appendLine("contentChars: ${md.length}")
                        appendLine("firstSectionAnchors: ${index.sections.take(8).joinToString(" | ") { it.key }}")
                    }
                    Files.writeString(artDir.resolve("summary.txt"), summary)

                    report.appendLine(
                        "| $name | $ext | $bytes | ${artifact.meta.pageCount ?: "-"} | ${index.sections.size} | " +
                            "${index.pages.size} | $tableRows | $imageMarkers | $embeddedObjs | $urlRefs | ${md.length} | ok |"
                    )
                    println("[corpus-probe] $name: ${index.sections.size} sections, ${index.pages.size} pages, " +
                        "$tableRows tableRows, $imageMarkers images, $urlRefs urls, ${md.length} chars")
                }
            } catch (e: Throwable) {
                Files.createDirectories(artDir)
                Files.writeString(artDir.resolve("ERROR.txt"), "${e::class.simpleName}: ${e.message}\n")
                report.appendLine("| $name | $ext | $bytes | - | - | - | - | - | - | - | - | ERROR: ${e.message?.take(50)} |")
                println("[corpus-probe] $name FAILED: ${e::class.simpleName}: ${e.message}")
            }
        }

        val reportPath = outDir.resolve("REPORT.md")
        Files.writeString(reportPath, report.toString())
        println("\n[corpus-probe] report → $reportPath\n")
        println(report)
    }
}
