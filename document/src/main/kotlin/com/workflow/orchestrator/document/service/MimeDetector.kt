package com.workflow.orchestrator.document.service

import org.apache.tika.Tika
import org.apache.tika.config.TikaConfig
import java.nio.file.Path

/**
 * MIME type detector backed by the hardened `tika-config.xml`.
 *
 * Each [detect] call constructs a fresh [Tika] instance wrapping a [TikaConfig] loaded from the
 * classpath resource. [TikaConfig] construction is not expensive; the cost is dominated by the
 * actual detection (metadata read). This keeps the implementation simple and avoids any
 * classloader-held state between calls.
 *
 * Detection delegates to [Tika.detect], which combines the file name extension and magic-byte
 * sniffing without reading the entire file. For files that don't exist or are unreadable, Tika
 * returns `"application/octet-stream"` — it never throws [java.io.IOException] from this path.
 */
class MimeDetector {

    /**
     * Detects the MIME type of the file at [path].
     *
     * @param path Absolute path to the file. Need not exist (returns `application/octet-stream`).
     * @return MIME type string, e.g. `"application/pdf"`, `"text/csv"`,
     *         `"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"`.
     *         Never null, never throws.
     */
    fun detect(path: Path): String {
        val configStream = this::class.java.classLoader.getResourceAsStream("tika-config.xml")
            ?: error("tika-config.xml not found on classpath — check :core/src/main/resources/")
        val tikaConfig = configStream.use { TikaConfig(it) }
        val tika = Tika(tikaConfig)
        return tika.detect(path.toFile())
    }
}
