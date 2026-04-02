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

}
