package com.workflow.orchestrator.sonar.ui

import com.workflow.orchestrator.core.model.sonar.SecurityHotspotData
import com.workflow.orchestrator.sonar.model.IssueType
import com.workflow.orchestrator.sonar.model.MappedIssue

/**
 * Unified list item for the quality issues list — wraps both SonarQube issues
 * and security hotspots so they can be displayed, filtered, and sorted together.
 */
sealed interface QualityListItem {
    val displayMessage: String
    val displayFileName: String
    val displayLine: Int?

    /** Returns true if this item matches the given type filter string from the filter combo. */
    fun matchesTypeFilter(filter: String): Boolean

    /** Returns true if this item matches the given severity filter string from the severity combo. */
    fun matchesSeverityFilter(filter: String): Boolean

    data class IssueItem(val issue: MappedIssue) : QualityListItem {
        override val displayMessage: String get() = issue.message
        override val displayFileName: String get() = java.io.File(issue.filePath).name
        override val displayLine: Int get() = issue.startLine

        override fun matchesTypeFilter(filter: String): Boolean = when (filter) {
            "All" -> true
            "Bug" -> issue.type == IssueType.BUG
            "Vulnerability" -> issue.type == IssueType.VULNERABILITY
            "Code Smell" -> issue.type == IssueType.CODE_SMELL
            else -> false // "Security Hotspot" / "Hotspot" — issues don't match
        }

        override fun matchesSeverityFilter(filter: String): Boolean = when (filter) {
            "All" -> true
            else -> issue.severity.name.equals(filter, ignoreCase = true)
        }
    }

    data class HotspotItem(val hotspot: SecurityHotspotData) : QualityListItem {
        override val displayMessage: String get() = hotspot.message
        override val displayFileName: String
            get() = hotspot.component.substringAfterLast(':').substringAfterLast('/')
        override val displayLine: Int? get() = hotspot.line

        override fun matchesTypeFilter(filter: String): Boolean = when (filter) {
            "All", "Security Hotspot", "Hotspot" -> true
            else -> false // Bug, Vulnerability, Code Smell — hotspots don't match
        }

        override fun matchesSeverityFilter(filter: String): Boolean {
            // Hotspots use probability (HIGH/MEDIUM/LOW) not traditional severity,
            // so they pass through all severity filters to remain visible
            return true
        }
    }

    companion object {
        /** Merges issues and hotspots into a single unified list. */
        fun merge(issues: List<MappedIssue>, hotspots: List<SecurityHotspotData>): List<QualityListItem> {
            val result = ArrayList<QualityListItem>(issues.size + hotspots.size)
            issues.mapTo(result) { IssueItem(it) }
            hotspots.mapTo(result) { HotspotItem(it) }
            return result
        }
    }
}
