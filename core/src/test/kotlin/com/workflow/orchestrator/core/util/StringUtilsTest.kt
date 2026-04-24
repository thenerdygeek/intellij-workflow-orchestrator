package com.workflow.orchestrator.core.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StringUtilsTest {

    @Test
    fun `truncate returns input unchanged when shorter than max`() {
        assertEquals("hello", StringUtils.truncate("hello", 10))
    }

    @Test
    fun `truncate returns input unchanged when exactly at max`() {
        assertEquals("hello", StringUtils.truncate("hello", 5))
    }

    @Test
    fun `truncate shortens input beyond max and appends single-char ellipsis`() {
        val result = StringUtils.truncate("hello world", 5)
        assertEquals("hell…", result)
        assertEquals(5, result.length, "result must not exceed maxLength")
    }

    @Test
    fun `truncate handles empty input`() {
        assertEquals("", StringUtils.truncate("", 5))
    }

    @Test
    fun `truncate at length 1 keeps single char unchanged`() {
        assertEquals("x", StringUtils.truncate("x", 1))
    }

    @Test
    fun `truncate at length 1 collapses longer input to ellipsis only`() {
        assertEquals("…", StringUtils.truncate("xy", 1))
    }

    @Test
    fun `truncate boundary case at length 2`() {
        assertEquals("x…", StringUtils.truncate("xyz", 2))
    }

    @Test
    fun `isEffectivelyBlank true for null`() {
        assertTrue(StringUtils.isEffectivelyBlank(null))
    }

    @Test
    fun `isEffectivelyBlank true for empty`() {
        assertTrue(StringUtils.isEffectivelyBlank(""))
    }

    @Test
    fun `isEffectivelyBlank true for whitespace only`() {
        assertTrue(StringUtils.isEffectivelyBlank("   \n\t  "))
    }

    @Test
    fun `isEffectivelyBlank true for zero-width space only`() {
        assertTrue(StringUtils.isEffectivelyBlank("​"))
    }

    @Test
    fun `isEffectivelyBlank true for mix of whitespace and format chars`() {
        assertTrue(StringUtils.isEffectivelyBlank("​  ﻿"))
    }

    @Test
    fun `isEffectivelyBlank false for real content`() {
        assertFalse(StringUtils.isEffectivelyBlank("hello"))
    }

    @Test
    fun `isEffectivelyBlank false when content has both real and format chars`() {
        assertFalse(StringUtils.isEffectivelyBlank("​hello"))
    }

    @Test
    fun `stripInvisibleFormatChars removes zero-width chars`() {
        assertEquals("hello", StringUtils.stripInvisibleFormatChars("​hel‌lo‍"))
    }

    @Test
    fun `stripInvisibleFormatChars removes byte-order mark`() {
        assertEquals("hi", StringUtils.stripInvisibleFormatChars("﻿hi"))
    }

    @Test
    fun `stripInvisibleFormatChars preserves normal text`() {
        assertEquals("hello world", StringUtils.stripInvisibleFormatChars("hello world"))
    }
}
