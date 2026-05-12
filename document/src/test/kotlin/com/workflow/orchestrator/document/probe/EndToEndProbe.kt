package com.workflow.orchestrator.document.probe

import com.workflow.orchestrator.core.model.ExtractOptions
import com.workflow.orchestrator.document.service.TikaDocumentExtractor
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * End-to-end self-test probe that runs TikaDocumentExtractor against every fixture
 * and writes the extracted Markdown to /tmp/read-document-probe/<fixture>.md for
 * manual inspection.
 *
 * NOT run as part of the regular test suite; opt-in via gradle property
 * `-PrunProbe=true`. Useful for sanity-checking the extractor on real spec PDFs.
 */
class EndToEndProbe {

    private val outputDir = Paths.get("/tmp/read-document-probe")
    private val fixtures = listOf(
        "data.csv",
        "report.html",
        "release-notes.rtf",
        "bug-tracker.xlsx",
        "design-doc.docx",
        "slides.pptx",
        "spec-with-tables.pdf",
        "multi-page-table.pdf",
        "tabula-eu-002.pdf",
        "tabula-multi-column.pdf",
        "ietf-rfc7230.pdf",
        "nist-cybersecurity-framework.pdf",
        "tabula-encrypted.pdf",
        "corrupt.pdf",
        "zero.pdf",
        "wrong-extension.pdf",
    )

    @Test
    fun `end-to-end probe — write all fixture extractions to disk`() {
        // Always runs when invoked by name filter (`--tests "*EndToEndProbe*"`).
        // Excluded from default ./gradlew :document:test runs because that test name
        // pattern won't match unless the user opts in.
        Files.createDirectories(outputDir)
        val extractor = TikaDocumentExtractor()

        val report = StringBuilder()
        report.appendLine("# read_document end-to-end probe")
        report.appendLine()
        report.appendLine("| Fixture | Result | MIME | Chars | Tables | Pages | First 80 chars |")
        report.appendLine("|---|---|---|---|---|---|---|")

        for (name in fixtures) {
            val fixturePath = fixturePath(name)
            if (!Files.exists(fixturePath)) {
                report.appendLine("| $name | MISSING | - | - | - | - | - |")
                continue
            }

            val result = runBlocking { extractor.extract(fixturePath, ExtractOptions()) }
            val outFile = outputDir.resolve("${name.replace('.', '_')}.md")

            if (result.isError) {
                Files.writeString(outFile, "# ERROR\n\n${result.summary}\n")
                report.appendLine("| $name | ERROR | - | - | - | - | ${result.summary.take(80)} |")
            } else {
                val content = requireNotNull(result.data) {
                    "Expected non-null content, got error: ${result.summary}"
                }
                Files.writeString(outFile, content.markdown)
                val tableCount = "\\| ---".toRegex().findAll(content.markdown).count()
                val firstChars = content.markdown.take(80).replace("\n", " ").replace("|", "\\|")
                report.appendLine(
                    "| $name | OK | ${content.mime} | ${content.markdown.length} | " +
                        "$tableCount | ${content.pageCount ?: "-"} | $firstChars |"
                )
            }
        }

        val reportFile = outputDir.resolve("README.md")
        Files.writeString(reportFile, report.toString())
        println("[probe] full report → $reportFile")
        println("[probe] per-fixture markdown → $outputDir/")
        println(report)
    }

    private fun fixturePath(name: String): Path {
        val resource = this::class.java.classLoader.getResource("fixtures/$name")
            ?: error("Fixture not found on classpath: fixtures/$name")
        return Paths.get(resource.toURI())
    }
}
