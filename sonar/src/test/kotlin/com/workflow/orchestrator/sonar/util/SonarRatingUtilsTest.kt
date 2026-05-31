package com.workflow.orchestrator.sonar.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * SONAR-COV-7: unit tests for [SonarRatingUtils.ratingLetter].
 *
 * Covers: all five valid ratings (1.0-5.0 → A-E), out-of-range input,
 * null input, non-numeric input, and the custom `unknown` fallback.
 */
class SonarRatingUtilsTest {

    @Test
    fun `ratingLetter maps 1 dot 0 to A`() {
        assertEquals("A", SonarRatingUtils.ratingLetter("1.0"))
    }

    @Test
    fun `ratingLetter maps 2 dot 0 to B`() {
        assertEquals("B", SonarRatingUtils.ratingLetter("2.0"))
    }

    @Test
    fun `ratingLetter maps 3 dot 0 to C`() {
        assertEquals("C", SonarRatingUtils.ratingLetter("3.0"))
    }

    @Test
    fun `ratingLetter maps 4 dot 0 to D`() {
        assertEquals("D", SonarRatingUtils.ratingLetter("4.0"))
    }

    @Test
    fun `ratingLetter maps 5 dot 0 to E`() {
        assertEquals("E", SonarRatingUtils.ratingLetter("5.0"))
    }

    @Test
    fun `ratingLetter returns empty string for out-of-range value 6 dot 0`() {
        assertEquals("", SonarRatingUtils.ratingLetter("6.0"))
    }

    @Test
    fun `ratingLetter returns empty string for out-of-range value 0`() {
        assertEquals("", SonarRatingUtils.ratingLetter("0.0"))
    }

    @Test
    fun `ratingLetter returns empty string for null input`() {
        assertEquals("", SonarRatingUtils.ratingLetter(null))
    }

    @Test
    fun `ratingLetter returns empty string for non-numeric input`() {
        assertEquals("", SonarRatingUtils.ratingLetter("abc"))
    }

    @Test
    fun `ratingLetter returns empty string for blank input`() {
        assertEquals("", SonarRatingUtils.ratingLetter(""))
    }

    @Test
    fun `ratingLetter uses custom unknown parameter when input is null`() {
        assertEquals("?", SonarRatingUtils.ratingLetter(null, unknown = "?"))
    }

    @Test
    fun `ratingLetter uses custom unknown parameter for out-of-range input`() {
        assertEquals("N/A", SonarRatingUtils.ratingLetter("99", unknown = "N/A"))
    }

    @Test
    fun `ratingLetter handles integer string representation`() {
        // SonarQube sometimes returns "1" instead of "1.0"
        assertEquals("A", SonarRatingUtils.ratingLetter("1"))
        assertEquals("E", SonarRatingUtils.ratingLetter("5"))
    }
}
