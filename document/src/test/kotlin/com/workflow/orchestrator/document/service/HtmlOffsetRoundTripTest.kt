package com.workflow.orchestrator.document.service

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * End-to-end SF-6 + NAV-2 guard exercising the full persisted-artifact path
 * ([DocumentArtifactStore.extractAndPersist] → `content.md` + `index.json`).
 *
 * Proves two things at once:
 *  - SF-6: a UTF-8 HTML page with multibyte glyphs persists `Español`/`Türkçe`/`–`, no mojibake.
 *  - NAV-2: for every section anchor, `content.substring(anchor.offset)` begins at that
 *    heading — i.e. the offsets index into the EXACT string that became `content.md`.
 */
class HtmlOffsetRoundTripTest {

    private val store = DocumentArtifactStore(TikaDocumentExtractor())

    private val html = """
        <!DOCTYPE html>
        <html><head><meta charset="utf-8"><title>GDP</title></head>
        <body>
          <h1>List of countries by GDP (Español, Türkçe)</h1>
          <p>Lead paragraph mentioning São Tomé and a range of $10–20 trillion.</p>
          <h2>Table</h2>
          <p>The table initially ranks each country or territory.</p>
          <h2>Nominal GDP of regional groupings</h2>
          <p>Some prose. Português, Français, 中文.</p>
          <h2>References</h2>
          <p>Footnote-ish text with an en–dash.</p>
        </body></html>
    """.trimIndent()

    @Test
    fun `html artifact has correct glyphs and every section anchor round-trips`() {
        runBlocking {
            val tmp = Files.createTempDirectory("rd-roundtrip")
            try {
                val src = tmp.resolve("gdp.html")
                Files.write(src, html.toByteArray(StandardCharsets.UTF_8))

                val artDir = tmp.resolve("art")
                val hash = store.hashFile(src)
                val artifact = store.extractAndPersist(src, artDir, hash, extractTimeoutMs = 120_000L)

                val md = store.readContent(artifact)
                val index = store.loadIndex(artifact)

                // ── SF-6: glyphs intact, no mojibake ────────────────────────────
                assertTrue(md.contains("Español"), "missing 'Español' in:\n$md")
                assertTrue(md.contains("Türkçe"), "missing 'Türkçe' in:\n$md")
                assertTrue(md.contains("São Tomé"), "missing 'São Tomé' in:\n$md")
                assertTrue(md.contains("–"), "missing en-dash in:\n$md")
                assertFalse(md.contains("EspaÃ"), "mojibake 'EspaÃ' present")
                assertFalse(md.contains("Ã¼"), "mojibake 'Ã¼' present")
                assertFalse(md.contains("â€"), "mojibake 'â€' present")

                // ── NAV-2: anchors round-trip onto headings ─────────────────────
                assertTrue(index.sections.isNotEmpty(), "expected section anchors")
                for (anchor in index.sections) {
                    val tail = md.substring(anchor.offset)
                    val afterHashes = tail.dropWhile { it == '#' }.removePrefix(" ")
                    assertTrue(
                        tail.startsWith("#") && afterHashes.startsWith(anchor.key),
                        "Section '${anchor.key}' @${anchor.offset} did not land on its heading; got: ${tail.take(50)}",
                    )
                }

                // The "Table" / "References" headings specifically must resolve to themselves.
                listOf("Table", "References").forEach { key ->
                    val off = index.sections.firstOrNull { it.key == key }?.offset
                    assertTrue(off != null, "missing section anchor '$key'")
                    val tail = md.substring(off!!)
                    assertTrue(
                        tail.startsWith("## $key"),
                        "Anchor '$key' @$off resolved to: ${tail.take(40)}",
                    )
                }

                // contentLength meta equals the persisted char length (char-based invariant).
                assertEquals(md.length, artifact.meta.contentLength)
            } finally {
                deleteRecursively(tmp)
            }
        }
    }

    private fun deleteRecursively(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { s ->
            s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
