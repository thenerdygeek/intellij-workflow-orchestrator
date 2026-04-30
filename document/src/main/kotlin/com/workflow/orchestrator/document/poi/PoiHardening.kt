package com.workflow.orchestrator.document.poi

import org.apache.poi.openxml4j.util.ZipSecureFile
import org.apache.poi.util.IOUtils
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Global POI security hardening applied exactly once per JVM lifetime.
 *
 * Apache Tika's `tika-config.xml` does NOT cover POI's direct XmlBeans path — POI
 * invokes XmlBeans directly when `XSSFWorkbook` / `XWPFDocument` / `XMLSlideShow` are
 * constructed. These three global setters must be applied before any POI call is made.
 *
 * ## Settings applied
 *
 * | Setter | Value | Purpose |
 * |---|---|---|
 * | `IOUtils.setByteArrayMaxOverride` | 50 MB | Caps individual byte-array allocations inside XmlBeans; prevents OOM on malformed zip entries |
 * | `ZipSecureFile.setMaxEntrySize` | 100 MB | Prevents zip-bomb DOCX/XLSX/PPTX from filling heap |
 * | `ZipSecureFile.setMinInflateRatio` | 0.001 | Stops extreme compression ratios (zip-bomb pattern) |
 *
 * ## Idempotency
 *
 * The `applyOnce()` function uses an [AtomicBoolean] to ensure the setters are called at
 * most once, even when multiple extractor instances are constructed concurrently. Subsequent
 * calls are no-ops. The setters themselves are not atomic at the POI level, so the guard
 * prevents a race during JVM start-up where two threads both call `applyOnce()` at the
 * same time.
 *
 * ## Usage
 *
 * Call from the `init` block of any class that constructs a POI workbook/document:
 * ```kotlin
 * init {
 *     PoiHardening.applyOnce()
 * }
 * ```
 *
 * Phase 6's `TikaDocumentExtractor` is the canonical call site. Individual extractors
 * also call it so they remain safe when used outside the full pipeline in tests.
 */
object PoiHardening {

    private val applied = AtomicBoolean(false)

    /**
     * Applies global POI security settings. Idempotent — subsequent calls are no-ops.
     *
     * Safe to call from multiple threads concurrently: the [AtomicBoolean] compare-and-set
     * guarantees the setters run exactly once.
     */
    fun applyOnce() {
        if (applied.compareAndSet(false, true)) {
            // Caps XmlBeans byte-array allocations (e.g. per zip entry inflate).
            // Addresses CVE-related zip-bomb risk via IOUtils bound.
            IOUtils.setByteArrayMaxOverride(50_000_000)

            // Cap the uncompressed size of any single zip entry inside OOXML containers.
            ZipSecureFile.setMaxEntrySize(100L * 1024 * 1024)

            // Reject entries with compression ratios above 1000:1 (zip-bomb pattern).
            ZipSecureFile.setMinInflateRatio(0.001)
        }
    }
}
