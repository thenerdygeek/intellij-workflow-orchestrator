package com.workflow.orchestrator.core.copyright

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

    // --- B3 regression: scan cap raised from 15 to 30 lines (audit finding handover:F-3) ---

    @Test
    fun `hasCopyrightHeader detects copyright keyword beyond line 15`() {
        // 16 blank/comment lines before the "copyright" keyword — was invisible at cap=15
        val preamble = (1..16).joinToString("\n") { "// line $it" }
        val content = "$preamble\n// Copyright (c) 2025 MyCompany\npackage com.example;"
        assertTrue(
            service.hasCopyrightHeader(content),
            "copyright keyword on line 17 must be detected within the 30-line scan window"
        )
    }

    @Test
    fun `hasCopyrightHeader SPDX-only header detected via year-regex fallback`() {
        // SPDX-style header has no "copyright" keyword; only "2025" on a comment line
        val content = "// SPDX-License-Identifier: Apache-2.0\n// SPDX-FileCopyrightText: 2025 MyCompany\npackage com.example;"
        assertTrue(
            service.hasCopyrightHeader(content),
            "SPDX header with year on comment line must be detected by year-regex fallback"
        )
    }

    @Test
    fun `analyzeFile scans 30 lines for year update`() {
        // Year is on line 20 — was missed when analyzeFile took only 15 lines
        val preamble = (1..19).joinToString("\n") { "// comment line $it" }
        val content = "$preamble\n// Copyright (c) 2023 MyCompany\npackage com.example;"
        val entry = service.analyzeFile("Foo.kt", content, currentYear = 2026)
        assertEquals(
            CopyrightStatus.YEAR_OUTDATED,
            entry.status,
            "year on line 20 must be detected and flagged as YEAR_OUTDATED within the 30-line window"
        )
    }

    // ── HANDOVER-COV-11: analyzeFile OK path and MISSING_HEADER path ──────────

    @Test
    fun `analyzeFile returns OK when header already has current year`() {
        val content = "// Copyright (c) 2026 Corp\npackage foo"
        val entry = service.analyzeFile("Foo.kt", content, currentYear = 2026)

        assertEquals(CopyrightStatus.OK, entry.status, "header with current year must be OK")
        assertNull(entry.oldYear, "oldYear must be null for an OK entry")
        assertNull(entry.newYear, "newYear must be null for an OK entry")
    }

    @Test
    fun `analyzeFile returns OK when header has current-year range`() {
        val content = "// Copyright (c) 2020-2026 Corp\npackage foo"
        val entry = service.analyzeFile("Foo.kt", content, currentYear = 2026)

        assertEquals(CopyrightStatus.OK, entry.status, "header ending in current year must be OK")
        assertNull(entry.oldYear)
        assertNull(entry.newYear)
    }

    @Test
    fun `analyzeFile returns MISSING_HEADER when no copyright keyword present`() {
        val content = "package foo\n\nclass Bar {}"
        val entry = service.analyzeFile("Bar.kt", content, currentYear = 2026)

        assertEquals(CopyrightStatus.MISSING_HEADER, entry.status)
    }

}
