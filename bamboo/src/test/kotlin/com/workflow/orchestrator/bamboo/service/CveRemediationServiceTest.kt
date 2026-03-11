package com.workflow.orchestrator.bamboo.service

import com.workflow.orchestrator.bamboo.service.CveRemediationService.CveSeverity
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CveRemediationServiceTest {

    private fun fixture(name: String): String =
        javaClass.classLoader.getResource("fixtures/$name")!!.readText()

    @Test
    fun `parses CVE entries from OWASP dependency-check output`() {
        val log = fixture("cve-build-log.txt")
        val vulns = CveRemediationService.parseFromBuildLog(log)
        assertEquals(3, vulns.size)
    }

    @Test
    fun `extracts CVE ID, groupId, artifactId, version, severity`() {
        val log = fixture("cve-build-log.txt")
        val vulns = CveRemediationService.parseFromBuildLog(log)
        val nettyVuln = vulns.find { it.cveId == "CVE-2023-44487" }

        assertNotNull(nettyVuln)
        assertEquals("io.netty", nettyVuln!!.groupId)
        assertEquals("netty-codec-http2", nettyVuln.artifactId)
        assertEquals("4.1.93.Final", nettyVuln.currentVersion)
        assertEquals(CveSeverity.CRITICAL, nettyVuln.severity)
        assertTrue(nettyVuln.description.contains("HTTP/2"))
    }

    @Test
    fun `parses HIGH severity correctly`() {
        val log = fixture("cve-build-log.txt")
        val vulns = CveRemediationService.parseFromBuildLog(log)
        val jacksonVuln = vulns.find { it.artifactId == "jackson-databind" }

        assertNotNull(jacksonVuln)
        assertEquals(CveSeverity.HIGH, jacksonVuln!!.severity)
    }

    @Test
    fun `returns empty list for clean build log`() {
        val log = """
            [INFO] BUILD SUCCESS
            [INFO] Total time: 5 s
        """.trimIndent()
        val vulns = CveRemediationService.parseFromBuildLog(log)
        assertTrue(vulns.isEmpty())
    }

    @Test
    fun `handles build log with no dependency-check output`() {
        val log = """
            [ERROR] Failed to execute goal
            [WARNING] Some other warning
        """.trimIndent()
        val vulns = CveRemediationService.parseFromBuildLog(log)
        assertTrue(vulns.isEmpty())
    }
}
