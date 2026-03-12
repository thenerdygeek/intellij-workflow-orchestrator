package com.workflow.orchestrator.sonar.ui

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CoverageLineMarkerLogicTest {

    @Test
    fun `SVG icon resources exist on classpath`() {
        val coveredUrl = CoverageLineMarkerProvider::class.java.getResource("/icons/coverage-covered.svg")
        val uncoveredUrl = CoverageLineMarkerProvider::class.java.getResource("/icons/coverage-uncovered.svg")
        val partialUrl = CoverageLineMarkerProvider::class.java.getResource("/icons/coverage-partial.svg")
        assertNotNull(coveredUrl, "coverage-covered.svg should be on classpath")
        assertNotNull(uncoveredUrl, "coverage-uncovered.svg should be on classpath")
        assertNotNull(partialUrl, "coverage-partial.svg should be on classpath")
    }

    @Test
    fun `dark SVG icon resources exist on classpath`() {
        val coveredDarkUrl = CoverageLineMarkerProvider::class.java.getResource("/icons/coverage-covered_dark.svg")
        val uncoveredDarkUrl = CoverageLineMarkerProvider::class.java.getResource("/icons/coverage-uncovered_dark.svg")
        val partialDarkUrl = CoverageLineMarkerProvider::class.java.getResource("/icons/coverage-partial_dark.svg")
        assertNotNull(coveredDarkUrl, "coverage-covered_dark.svg should be on classpath")
        assertNotNull(uncoveredDarkUrl, "coverage-uncovered_dark.svg should be on classpath")
        assertNotNull(partialDarkUrl, "coverage-partial_dark.svg should be on classpath")
    }
}
