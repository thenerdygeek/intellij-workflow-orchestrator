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
    implementation("org.apache.tika:tika-core:3.2.3") {
        exclude(group = "org.apache.logging.log4j")
        exclude(group = "org.slf4j")
    }

    // Apache Tika 3.x for document parsing facade + bundled parsers
    implementation("org.apache.tika:tika-parsers-standard-package:3.2.3") {
        // Plugin already manages slf4j; bundling a second one causes LinkageError per existing build.gradle.kts comment.
        exclude(group = "org.apache.logging.log4j", module = "log4j-api")
        exclude(group = "org.apache.logging.log4j", module = "log4j-core")
        exclude(group = "org.slf4j")
    }

    // Apache POI direct for cell-perfect Office extraction
    implementation("org.apache.poi:poi-ooxml:5.4.1") {
        exclude(group = "org.apache.logging.log4j")
        exclude(group = "org.slf4j")
    }

    // Tabula-java for PDF table extraction. CRITICAL: 1.0.5 ships PDFBox 2.0.24
    // transitively which would conflict with Tika's 3.0.5. Strip and let constraint
    // below resolve PDFBox 3.0.5.
    implementation("technology.tabula:tabula:1.0.5") {
        exclude(group = "org.apache.pdfbox")
        exclude(group = "org.apache.logging.log4j")
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
    testRuntimeOnly(libs.junit5.engine)
    // JUnit Vintage engine bridges JUnit 4 (BasePlatformTestCase) onto the JUnit 5 platform.
    // The IntelliJ Platform classloader wires junit4-style Statement class references even
    // when no JUnit 4 tests exist; this JAR satisfies those wires.
    testRuntimeOnly(libs.junit5.vintage.engine)
    testRuntimeOnly(libs.junit5.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
