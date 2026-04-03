package com.workflow.orchestrator.sonar.ui

import com.workflow.orchestrator.core.model.sonar.SecurityHotspotData
import com.workflow.orchestrator.sonar.model.IssueSeverity
import com.workflow.orchestrator.sonar.model.IssueType
import com.workflow.orchestrator.sonar.model.MappedIssue
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class QualityListItemTest {

    private fun issue(
        type: IssueType = IssueType.BUG,
        severity: IssueSeverity = IssueSeverity.MAJOR,
        message: String = "Null pointer dereference",
        filePath: String = "src/main/java/Foo.java",
        startLine: Int = 42
    ) = MappedIssue(
        key = "issue-1",
        type = type,
        severity = severity,
        message = message,
        rule = "java:S2259",
        filePath = filePath,
        startLine = startLine,
        endLine = startLine,
        startOffset = 0,
        endOffset = 10,
        effort = "15min"
    )

    private fun hotspot(
        message: String = "Make sure this cookie is secure",
        component: String = "my-project:src/main/java/com/example/AuthController.java",
        line: Int? = 55,
        probability: String = "HIGH",
        status: String = "TO_REVIEW"
    ) = SecurityHotspotData(
        key = "hotspot-1",
        message = message,
        component = component,
        line = line,
        securityCategory = "insecure-cookie",
        probability = probability,
        status = status,
        resolution = null
    )

    // --- merge tests ---

    @Test
    fun `merge combines issues and hotspots`() {
        val issues = listOf(issue(), issue(type = IssueType.CODE_SMELL))
        val hotspots = listOf(hotspot(), hotspot(probability = "LOW"))
        val merged = QualityListItem.merge(issues, hotspots)
        assertEquals(4, merged.size)
        assertEquals(2, merged.count { it is QualityListItem.IssueItem })
        assertEquals(2, merged.count { it is QualityListItem.HotspotItem })
    }

    @Test
    fun `merge with empty hotspots returns only issues`() {
        val merged = QualityListItem.merge(listOf(issue()), emptyList())
        assertEquals(1, merged.size)
        assertTrue(merged[0] is QualityListItem.IssueItem)
    }

    @Test
    fun `merge with empty issues returns only hotspots`() {
        val merged = QualityListItem.merge(emptyList(), listOf(hotspot()))
        assertEquals(1, merged.size)
        assertTrue(merged[0] is QualityListItem.HotspotItem)
    }

    @Test
    fun `merge with both empty returns empty`() {
        val merged = QualityListItem.merge(emptyList(), emptyList())
        assertTrue(merged.isEmpty())
    }

    // --- type filter tests ---

    @Test
    fun `IssueItem matches All filter`() {
        val item = QualityListItem.IssueItem(issue(type = IssueType.BUG))
        assertTrue(item.matchesTypeFilter("All"))
    }

    @Test
    fun `IssueItem Bug matches Bug filter`() {
        val item = QualityListItem.IssueItem(issue(type = IssueType.BUG))
        assertTrue(item.matchesTypeFilter("Bug"))
    }

    @Test
    fun `IssueItem Vulnerability matches Vulnerability filter`() {
        val item = QualityListItem.IssueItem(issue(type = IssueType.VULNERABILITY))
        assertTrue(item.matchesTypeFilter("Vulnerability"))
    }

    @Test
    fun `IssueItem Code Smell matches Code Smell filter`() {
        val item = QualityListItem.IssueItem(issue(type = IssueType.CODE_SMELL))
        assertTrue(item.matchesTypeFilter("Code Smell"))
    }

    @Test
    fun `IssueItem does not match Security Hotspot filter`() {
        val item = QualityListItem.IssueItem(issue(type = IssueType.BUG))
        assertFalse(item.matchesTypeFilter("Security Hotspot"))
    }

    @Test
    fun `Bug filter excludes hotspots`() {
        val item = QualityListItem.HotspotItem(hotspot())
        assertFalse(item.matchesTypeFilter("Bug"))
    }

    @Test
    fun `HotspotItem matches All filter`() {
        val item = QualityListItem.HotspotItem(hotspot())
        assertTrue(item.matchesTypeFilter("All"))
    }

    @Test
    fun `HotspotItem matches Security Hotspot filter`() {
        val item = QualityListItem.HotspotItem(hotspot())
        assertTrue(item.matchesTypeFilter("Security Hotspot"))
    }

    @Test
    fun `HotspotItem matches Hotspot filter`() {
        val item = QualityListItem.HotspotItem(hotspot())
        assertTrue(item.matchesTypeFilter("Hotspot"))
    }

    @Test
    fun `Security Hotspot filter excludes issues`() {
        val item = QualityListItem.IssueItem(issue())
        assertFalse(item.matchesTypeFilter("Security Hotspot"))
        assertFalse(item.matchesTypeFilter("Hotspot"))
    }

    // --- severity filter tests ---

    @Test
    fun `IssueItem matches severity All`() {
        val item = QualityListItem.IssueItem(issue(severity = IssueSeverity.BLOCKER))
        assertTrue(item.matchesSeverityFilter("All"))
    }

    @Test
    fun `IssueItem matches own severity`() {
        val item = QualityListItem.IssueItem(issue(severity = IssueSeverity.CRITICAL))
        assertTrue(item.matchesSeverityFilter("Critical"))
    }

    @Test
    fun `IssueItem does not match different severity`() {
        val item = QualityListItem.IssueItem(issue(severity = IssueSeverity.MINOR))
        assertFalse(item.matchesSeverityFilter("Critical"))
    }

    @Test
    fun `HotspotItem always matches severity All`() {
        val item = QualityListItem.HotspotItem(hotspot())
        assertTrue(item.matchesSeverityFilter("All"))
    }

    @Test
    fun `HotspotItem matches any severity filter`() {
        // Hotspots don't have severity in the traditional sense, so they pass all severity filters
        val item = QualityListItem.HotspotItem(hotspot())
        assertTrue(item.matchesSeverityFilter("Critical"))
        assertTrue(item.matchesSeverityFilter("Major"))
    }

    // --- displayFileName tests ---

    @Test
    fun `IssueItem displayFileName extracts file name from path`() {
        val item = QualityListItem.IssueItem(issue(filePath = "src/main/java/com/example/Foo.java"))
        assertEquals("Foo.java", item.displayFileName)
    }

    @Test
    fun `HotspotItem displayFileName extracts from component field`() {
        val item = QualityListItem.HotspotItem(
            hotspot(component = "my-project:src/main/java/com/example/AuthController.java")
        )
        assertEquals("AuthController.java", item.displayFileName)
    }

    @Test
    fun `HotspotItem displayFileName handles component without colon`() {
        val item = QualityListItem.HotspotItem(
            hotspot(component = "src/main/java/Service.java")
        )
        assertEquals("Service.java", item.displayFileName)
    }

    @Test
    fun `HotspotItem displayFileName handles component without path separators`() {
        val item = QualityListItem.HotspotItem(
            hotspot(component = "my-project:Service.java")
        )
        assertEquals("Service.java", item.displayFileName)
    }

    // --- displayMessage / displayLine ---

    @Test
    fun `IssueItem displayMessage returns issue message`() {
        val item = QualityListItem.IssueItem(issue(message = "Fix this"))
        assertEquals("Fix this", item.displayMessage)
    }

    @Test
    fun `HotspotItem displayMessage returns hotspot message`() {
        val item = QualityListItem.HotspotItem(hotspot(message = "Insecure cookie"))
        assertEquals("Insecure cookie", item.displayMessage)
    }

    @Test
    fun `IssueItem displayLine returns startLine`() {
        val item = QualityListItem.IssueItem(issue(startLine = 99))
        assertEquals(99, item.displayLine)
    }

    @Test
    fun `HotspotItem displayLine returns line`() {
        val item = QualityListItem.HotspotItem(hotspot(line = 55))
        assertEquals(55, item.displayLine)
    }

    @Test
    fun `HotspotItem displayLine returns null when line is null`() {
        val item = QualityListItem.HotspotItem(hotspot(line = null))
        assertNull(item.displayLine)
    }
}
