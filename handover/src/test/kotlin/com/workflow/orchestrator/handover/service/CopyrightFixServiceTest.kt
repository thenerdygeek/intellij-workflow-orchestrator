package com.workflow.orchestrator.handover.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CopyrightFixServiceTest {

    private val service = CopyrightFixService()

    // --- Year update logic ---

    @Test
    fun `updateYearInHeader updates single old year to range`() {
        val header = "Copyright (c) 2025 MyCompany Ltd."
        val result = service.updateYearInHeader(header, 2026)
        assertEquals("Copyright (c) 2025-2026 MyCompany Ltd.", result)
    }

    @Test
    fun `updateYearInHeader extends existing range`() {
        val header = "Copyright (c) 2019-2025 MyCompany Ltd."
        val result = service.updateYearInHeader(header, 2026)
        assertEquals("Copyright (c) 2019-2026 MyCompany Ltd.", result)
    }

    @Test
    fun `updateYearInHeader consolidates scattered years`() {
        val header = "Copyright (c) 2018, 2020-2023, 2025 MyCompany Ltd."
        val result = service.updateYearInHeader(header, 2026)
        assertEquals("Copyright (c) 2018-2026 MyCompany Ltd.", result)
    }

    @Test
    fun `updateYearInHeader leaves current year alone`() {
        val header = "Copyright (c) 2026 MyCompany Ltd."
        val result = service.updateYearInHeader(header, 2026)
        assertEquals("Copyright (c) 2026 MyCompany Ltd.", result)
    }

    @Test
    fun `updateYearInHeader leaves current range alone`() {
        val header = "Copyright (c) 2020-2026 MyCompany Ltd."
        val result = service.updateYearInHeader(header, 2026)
        assertEquals("Copyright (c) 2020-2026 MyCompany Ltd.", result)
    }

    @Test
    fun `updateYearInHeader handles no year in header`() {
        val header = "Copyright MyCompany Ltd."
        val result = service.updateYearInHeader(header, 2026)
        assertEquals("Copyright MyCompany Ltd.", result)
    }

    @Test
    fun `updateYearInHeader ignores non-year numbers`() {
        val header = "Copyright (c) 2025 MyCompany Ltd. v3.0"
        val result = service.updateYearInHeader(header, 2026)
        assertEquals("Copyright (c) 2025-2026 MyCompany Ltd. v3.0", result)
    }

    // --- Comment wrapping ---

    @Test
    fun `wrapForLanguage wraps Java with block comment`() {
        val template = "Copyright (c) 2026 MyCompany\nAll rights reserved."
        val result = service.wrapForLanguage(template, "java")
        assertTrue(result.startsWith("/*"))
        assertTrue(result.endsWith("*/"))
        assertTrue(result.contains(" * Copyright (c) 2026 MyCompany"))
    }

    @Test
    fun `wrapForLanguage wraps Kotlin with block comment`() {
        val result = service.wrapForLanguage("Copyright 2026", "kt")
        assertTrue(result.startsWith("/*"))
        assertTrue(result.endsWith("*/"))
    }

    @Test
    fun `wrapForLanguage wraps XML with HTML comment`() {
        val result = service.wrapForLanguage("Copyright 2026", "xml")
        assertTrue(result.startsWith("<!--"))
        assertTrue(result.endsWith("-->"))
    }

    @Test
    fun `wrapForLanguage wraps properties with hash comment`() {
        val result = service.wrapForLanguage("Copyright 2026\nAll rights.", "properties")
        assertTrue(result.contains("# Copyright 2026"))
        assertTrue(result.contains("# All rights."))
    }

    @Test
    fun `wrapForLanguage wraps yaml with hash comment`() {
        val result = service.wrapForLanguage("Copyright 2026", "yml")
        assertTrue(result.startsWith("# Copyright 2026"))
    }

    // --- Template loading ---

    @Test
    fun `prepareHeader replaces year placeholder`() {
        val template = "Copyright (c) {year} MyCompany Ltd.\nAll rights reserved."
        val result = service.prepareHeader(template, 2026)
        assertEquals("Copyright (c) 2026 MyCompany Ltd.\nAll rights reserved.", result)
    }

    // --- Header detection ---

    @Test
    fun `hasCopyrightHeader returns true when header present`() {
        val content = "/*\n * Copyright (c) 2025 MyCompany\n */\npackage com.example;"
        assertTrue(service.hasCopyrightHeader(content))
    }

    @Test
    fun `hasCopyrightHeader returns false when no header`() {
        val content = "package com.example;\n\nclass Foo {}"
        assertFalse(service.hasCopyrightHeader(content))
    }

    // --- Source file filtering ---

    @Test
    fun `isSourceFile returns true for supported extensions`() {
        assertTrue(service.isSourceFile("Foo.java"))
        assertTrue(service.isSourceFile("Bar.kt"))
        assertTrue(service.isSourceFile("build.gradle.kts"))
        assertTrue(service.isSourceFile("pom.xml"))
        assertTrue(service.isSourceFile("app.yml"))
        assertTrue(service.isSourceFile("app.yaml"))
        assertTrue(service.isSourceFile("config.properties"))
    }

    @Test
    fun `isGeneratedPath returns true for build output paths`() {
        assertTrue(service.isGeneratedPath("target/classes/Foo.java"))
        assertTrue(service.isGeneratedPath("build/generated/Source.kt"))
        assertTrue(service.isGeneratedPath(".gradle/caches/file.kt"))
        assertTrue(service.isGeneratedPath("node_modules/pkg/index.js"))
    }

    @Test
    fun `isGeneratedPath returns false for source paths`() {
        assertFalse(service.isGeneratedPath("src/main/java/Foo.java"))
        assertFalse(service.isGeneratedPath("src/test/kotlin/Bar.kt"))
    }

    @Test
    fun `isSourceFile returns false for non-source files`() {
        assertFalse(service.isSourceFile("image.png"))
        assertFalse(service.isSourceFile("data.csv"))
        assertFalse(service.isSourceFile("README.md"))
        assertFalse(service.isSourceFile("app.jar"))
    }
}
