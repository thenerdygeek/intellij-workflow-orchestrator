package com.workflow.orchestrator.bamboo.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Service(Service.Level.PROJECT)
class CveRemediationService(private val project: Project) {

    private val log = Logger.getInstance(CveRemediationService::class.java)

    data class CveVulnerability(
        val cveId: String,
        val groupId: String,
        val artifactId: String,
        val currentVersion: String,
        val fixedVersion: String? = null,
        val severity: CveSeverity,
        val description: String
    )

    enum class CveSeverity { CRITICAL, HIGH, MEDIUM, LOW }

    private val _vulnerabilities = MutableStateFlow<List<CveVulnerability>>(emptyList())
    val vulnerabilities: StateFlow<List<CveVulnerability>> = _vulnerabilities.asStateFlow()

    fun updateFromBuildLog(buildLog: String) {
        val parsed = parseFromBuildLog(buildLog)
        log.info("[Bamboo:CVE] Scan complete: ${parsed.size} vulnerabilities found")
        if (parsed.isNotEmpty()) {
            val bySeverity = parsed.groupBy { it.severity }
            val summary = bySeverity.entries.joinToString { "${it.key}=${it.value.size}" }
            log.warn("[Bamboo:CVE] Vulnerability severities: $summary")
            parsed.forEach { cve ->
                log.warn("[Bamboo:CVE] ${cve.cveId} (${cve.severity}): ${cve.groupId}:${cve.artifactId}:${cve.currentVersion}${cve.fixedVersion?.let { " -> $it" } ?: ""}")
            }
        }
        _vulnerabilities.value = parsed
    }

    companion object {
        private val CVE_PATTERN = Regex(
            """\[WARNING]\s+([\w.]+):([\w\-]+):([\w.\-]+)\s+(CVE-\d{4}-\d+)\s+\((\w+)\)\s*-?\s*(.*)"""
        )

        fun parseFromBuildLog(log: String): List<CveVulnerability> {
            val vulnerabilities = mutableListOf<CveVulnerability>()

            for (line in log.lines()) {
                val match = CVE_PATTERN.find(line) ?: continue
                val (groupId, artifactId, version, cveId, severityStr, description) = match.destructured

                val severity = try {
                    CveSeverity.valueOf(severityStr.uppercase())
                } catch (_: IllegalArgumentException) {
                    CveSeverity.MEDIUM
                }

                vulnerabilities.add(
                    CveVulnerability(
                        cveId = cveId,
                        groupId = groupId,
                        artifactId = artifactId,
                        currentVersion = version,
                        severity = severity,
                        description = description.trim()
                    )
                )
            }

            return vulnerabilities
        }

        fun getInstance(project: Project): CveRemediationService =
            project.getService(CveRemediationService::class.java)
    }
}
