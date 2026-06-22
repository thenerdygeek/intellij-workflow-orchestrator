// document/build.gradle.kts — Submodule: binary document extraction (PDF, DOCX, XLSX, PPTX, RTF, ODT, …).
// Provides DocumentExtractor service implementations backed by Apache Tika 3.x, Tabula-java, and Apache POI.
// Depends ONLY on :core; never imported by other feature modules directly.

plugins {
    alias(libs.plugins.kotlin)
    id("org.jetbrains.intellij.platform.module")
}

kotlin {
    jvmToolchain(providers.gradleProperty("javaVersion").get().toInt())
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
        // No TestFrameworkType.Platform: MarkdownAssembler tests are pure Kotlin with
        // zero IntelliJ dependencies. The Platform test framework registers
        // JUnit5TestSessionListener as a ServiceLoader entry, which requires a live
        // IntelliJ Application to instantiate — crashing the test worker for unit tests.
        // Future tests that need BasePlatformTestCase should re-add it in a separate
        // test source set or after the IntelliJ-integration pipelines are implemented.
    }

    implementation(project(":core"))

    // Apache Tika 3.x core API (AutoDetectParser, TikaConfig, Metadata, ParseContext, …).
    // Must be declared explicitly — the slf4j excludes on tika-parsers-standard-package
    // strip transitive dependency resolution from the parent BOM, leaving tika-core absent
    // from the compile classpath without this explicit declaration.
    //
    // log4j-api is intentionally NOT excluded — POI 5.x and several Tika parser modules
    // call `org.apache.logging.log4j.LogManager.getLogger(...)` directly, so the API JAR
    // must be on the plugin classloader to satisfy bytecode verification at first POI/Tika
    // class load. We route Log4j 2 → SLF4J via `log4j-to-slf4j` declared at the bottom of
    // this dependencies block, so no log4j-core implementation is bundled.
    implementation("org.apache.tika:tika-core:3.3.1") {
        exclude(group = "org.slf4j")
    }

    // Apache Tika 3.x for document parsing facade + bundled parsers.
    //
    // The `tika-parsers-standard-package` aggregator pulls in EVERY parser Tika ships, which
    // bloats the plugin ZIP from ~33 MB to 87 MB (sqlite-jdbc alone is 13.5 MB). The exclude
    // list below trims it back to the parsers we actually drive from `TikaXhtmlPipeline` +
    // `PdfPipeline`: PDF, Office (microsoft + miscoffice), HTML/EPUB, and CSV/plain text.
    //
    // KEEP: tika-parser-pdf-module, tika-parser-microsoft-module, tika-parser-miscoffice-module,
    //       tika-parser-html-module, tika-parser-text-module (CSV/plain-text — see
    //       TikaXhtmlPipeline.CSV_LIKE_MIMES), tika-core.
    //
    // ServiceLoader / TIKA-1145: as long as the kept tika-parser-*-module JARs retain their
    // META-INF/services/org.apache.tika.parser.Parser entries, AutoDetectParser registry stays
    // non-empty and detection still works for the formats above.
    implementation("org.apache.tika:tika-parsers-standard-package:3.3.1") {
        // SLF4J is provided by IntelliJ Platform; bundling a second copy causes LinkageError.
        // log4j-api stays IN — POI 5.x and Tika modules below directly reference it for
        // class-level static `LogManager.getLogger(...)` initialisation. log4j-core is dropped
        // because we route to SLF4J via `log4j-to-slf4j` (declared below).
        exclude(group = "org.apache.logging.log4j", module = "log4j-core")
        exclude(group = "org.slf4j")

        // Embedded databases / external connectors (no document format we extract uses these).
        // NOTE: `com.healthmarketscience.jackcess` cannot be excluded — `tika-parser-microsoft-module`
        // (kept) bundles `JackcessParser` whose inner class `JackcessParser$IgnoreLinkResolver
        // implements com.healthmarketscience.jackcess.util.LinkResolver`. The JVM resolves the
        // supertype eagerly during class verification, so removing jackcess produces NoClassDefFoundError
        // at TikaConfig construction time, even with `parser-exclude` directives. Keeps ~1.3 MB.
        exclude(group = "org.xerial")                       // sqlite-jdbc — 13.5 MB
        exclude(group = "org.postgresql")                   // JDBC driver
        exclude(group = "org.locationtech.jts")             // JTS spatial / GIS

        // Specialty parsers we do not invoke.
        exclude(group = "com.github.junrar")                // RAR archives
        exclude(group = "org.netpreserve")                  // jwarc (web archive)
        exclude(group = "com.rometools")                    // RSS / Atom feeds
        exclude(group = "org.apache.james")                 // mime4j (email)

        // Image metadata / format adapters Tika uses for image-format parsers we don't load.
        exclude(group = "com.drewnoakes")                   // metadata-extractor
        exclude(group = "com.adobe.xmp")                    // xmpcore
        exclude(group = "org.jbig2")                        // jbig2-imageio
        exclude(group = "com.github.jai-imageio")           // jai-imageio-core / jai-imageio-jpeg2000
        exclude(group = "com.googlecode.juniversalchardet") // chardet — we have a known mime
        exclude(group = "com.uwyn")                         // jhighlight (source highlighter)

        // Tika CLI shipped via picocli — we only call AutoDetectParser programmatically.
        exclude(group = "info.picocli")

        // Tika parser modules for formats we do NOT extract. The kept set above covers our
        // documented surface (PDFs, Office, RTF, ODT, EPUB, HTML, CSV) per package-info.kt.
        exclude(group = "org.apache.tika", module = "tika-parser-mail-commons")
        exclude(group = "org.apache.tika", module = "tika-parser-mail-module")
        exclude(group = "org.apache.tika", module = "tika-parser-news-module")
        exclude(group = "org.apache.tika", module = "tika-parser-cad-module")
        exclude(group = "org.apache.tika", module = "tika-parser-code-module")
        exclude(group = "org.apache.tika", module = "tika-parser-audiovideo-module")
        exclude(group = "org.apache.tika", module = "tika-parser-apple-module")
        exclude(group = "org.apache.tika", module = "tika-parser-webarchive-module")
        // tika-parser-xml-module: KEPT — `tika-parser-miscoffice-module` (RTF/ODT, kept) uses
        // `org.apache.tika.parser.xml.XMLParser` as a base class and the JVM resolves it eagerly
        // during class verification. JAR is only ~36 KB so no real cost.
        exclude(group = "org.apache.tika", module = "tika-parser-image-module")
        exclude(group = "org.apache.tika", module = "tika-parser-ocr-module")
        exclude(group = "org.apache.tika", module = "tika-parser-pkg-module")
    }

    // Apache POI direct for cell-perfect Office extraction.
    // Note: `poi-scratchpad` (legacy .doc/.xls/.ppt) is unused by our extractors but must stay —
    // Tika's `tika-parser-microsoft-module` (kept) ships HSLFExtractor/ExcelExtractor.HSSFListener/
    // OfficeParser which hard-reference poi-scratchpad classes during ServiceLoader registration.
    // Excluding it triggers NoClassDefFoundError at TikaConfig construction (same trap as jackcess).
    implementation("org.apache.poi:poi-ooxml:5.5.1") {
        // Keep log4j-api — POI 5.x replaced POILogFactory with direct
        // `org.apache.logging.log4j.LogManager.getLogger(...)` calls (~599 references in
        // poi-5.4.1.jar alone). Excluding it produces NoClassDefFoundError at first POI
        // class load. log4j-core is dropped; routing to SLF4J via `log4j-to-slf4j` below.
        exclude(group = "org.apache.logging.log4j", module = "log4j-core")
        exclude(group = "org.slf4j")
    }

    // Tabula-java for PDF table extraction. CRITICAL: 1.0.5 ships PDFBox 2.0.24
    // transitively which would conflict with Tika's 3.0.5. Strip and let constraint
    // below resolve PDFBox 3.0.5.
    implementation("technology.tabula:tabula:1.0.5") {
        exclude(group = "org.apache.pdfbox")
        // log4j-api stays IN (POI/Tika need it). log4j-core dropped; routed via log4j-to-slf4j.
        exclude(group = "org.apache.logging.log4j", module = "log4j-core")
        exclude(group = "org.slf4j")
    }

    // Log4j 2 → SLF4J adapter. Brings `log4j-api` (~310 KB) onto the plugin classloader so
    // POI 5.x and Tika 3.x (which call `org.apache.logging.log4j.LogManager.getLogger(...)`
    // directly) can class-verify; routes every Log4j 2 logger call into the SLF4J binding
    // already provided by IntelliJ Platform. Costs ~25 KB on top of log4j-api. We deliberately
    // do NOT bundle log4j-core (~1.8 MB) — the adapter replaces it as the SPI provider. SLF4J
    // is excluded transitively because the IntelliJ Platform already provides it; bundling a
    // second copy would trigger LinkageError under the plugin classloader.
    implementation("org.apache.logging.log4j:log4j-to-slf4j:2.26.0") {
        exclude(group = "org.slf4j")
    }

    constraints {
        implementation("org.apache.pdfbox:pdfbox") { version { strictly("3.0.5") } }
        implementation("org.apache.pdfbox:fontbox") { version { strictly("3.0.5") } }
        implementation("org.apache.pdfbox:pdfbox-io") { version { strictly("3.0.5") } }
    }

    compileOnly(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit5.api)
    testImplementation(libs.junit5.params)
    testRuntimeOnly(libs.junit5.engine)
    // JUnit Vintage engine bridges JUnit 4 (BasePlatformTestCase) onto the JUnit 5 platform.
    // The IntelliJ Platform classloader wires junit4-style Statement class references even
    // when no JUnit 4 tests exist; this JAR satisfies those wires.
    testRuntimeOnly(libs.junit5.vintage.engine)
    testRuntimeOnly(libs.junit5.platform.launcher)
}

// BouncyCastle: Tika + PDFBox both pull in `bcprov-jdk18on` (current, JDK18+) AND the older
// `bcprov-jdk15on` (JDK15+). Both register the same `org.bouncycastle.*` packages, which is a
// duplicate-class hazard — depending on classloader order, encrypted-PDF AES-256 detection can
// fall through to the older provider and silently fail. Strip the legacy `jdk15on` variants
// project-wide; the modern `jdk18on` set stays.
//
// `bcmail-jdk15on` belongs to the same family — Tika's microsoft-module pulls it transitively
// alongside the modern `bcjmail-jdk18on`. All 63 of its `org.bouncycastle.mail.smime.*` classes
// collide with bcjmail-jdk18on, so it must be excluded for the same reason.
configurations.all {
    exclude(group = "org.bouncycastle", module = "bcprov-jdk15on")
    exclude(group = "org.bouncycastle", module = "bcpkix-jdk15on")
    exclude(group = "org.bouncycastle", module = "bcutil-jdk15on")
    exclude(group = "org.bouncycastle", module = "bcmail-jdk15on")
}

intellijPlatform {
    // Isolate this module's test sandbox into its own build dir so the aggregate `./gradlew test`
    // doesn't have several modules writing the shared root sandbox — Gradle 9.x then fails on the
    // resulting undeclared cross-task dependency. Matches :bamboo/:sonar/:pullrequest/:handover.
    sandboxContainer = layout.buildDirectory.dir("idea-sandbox")
}

tasks.test {
    useJUnitPlatform()
}

// Expose test helper classes (fixture factories) so :agent integration tests can consume them
// without the java-test-fixtures plugin overhead. :agent declares:
//   testImplementation(project(":document", "testOutput"))
val testOutput: Configuration by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}
// Register the Kotlin test classes dir as the single artifact on the testOutput variant.
// :agent tests use this to reach LargePdfFixtureFactory and EncryptedPdfFixtureFactory
// without a full java-test-fixtures setup.
val documentKotlinTestClasses = layout.buildDirectory.dir("classes/kotlin/test")
artifacts {
    add(testOutput.name, documentKotlinTestClasses) {
        builtBy(tasks.named("compileTestKotlin"))
    }
}
