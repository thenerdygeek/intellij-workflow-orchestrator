package com.workflow.orchestrator.bamboo.editor

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.workflow.orchestrator.bamboo.service.CveRemediationService
import com.workflow.orchestrator.bamboo.service.CveRemediationService.CveSeverity
import com.workflow.orchestrator.bamboo.service.CveRemediationService.CveVulnerability
import com.workflow.orchestrator.core.settings.PluginSettings

class CveAnnotator : ExternalAnnotator<CveAnnotator.CollectionInfo, CveAnnotator.AnnotationResult>() {

    data class CollectionInfo(
        val file: PsiFile,
        val vulnerabilities: List<CveVulnerability>
    )

    data class AnnotationResult(
        val annotations: List<CveAnnotationEntry>
    )

    data class CveAnnotationEntry(
        val element: XmlTag,
        val cve: CveVulnerability
    )

    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): CollectionInfo? {
        if (file !is XmlFile || file.name != "pom.xml") return null
        val project = file.project
        val settings = PluginSettings.getInstance(project).state
        if (!settings.healthCheckCveEnabled) return null

        val vulns = CveRemediationService.getInstance(project).vulnerabilities.value
        if (vulns.isEmpty()) return null

        return CollectionInfo(file, vulns)
    }

    override fun doAnnotate(collectionInfo: CollectionInfo): AnnotationResult {
        val xmlFile = collectionInfo.file as XmlFile
        val entries = mutableListOf<CveAnnotationEntry>()
        val rootTag = xmlFile.rootTag ?: return AnnotationResult(emptyList())

        for (vuln in collectionInfo.vulnerabilities) {
            val depTag = findDependencyTag(rootTag, vuln.groupId, vuln.artifactId)
            if (depTag != null) {
                entries.add(CveAnnotationEntry(depTag, vuln))
            }
        }

        return AnnotationResult(entries)
    }

    override fun apply(file: PsiFile, result: AnnotationResult, holder: AnnotationHolder) {
        for (entry in result.annotations) {
            val severity = when (entry.cve.severity) {
                CveSeverity.CRITICAL, CveSeverity.HIGH -> HighlightSeverity.WARNING
                CveSeverity.MEDIUM -> HighlightSeverity.WEAK_WARNING
                CveSeverity.LOW -> HighlightSeverity.INFORMATION
            }

            val fixText = entry.cve.fixedVersion?.let { " — Fix: bump to $it" } ?: ""
            holder.newAnnotation(
                severity,
                "${entry.cve.cveId} (${entry.cve.severity})$fixText"
            )
                .range(entry.element.textRange)
                .create()
        }
    }

    private fun findDependencyTag(rootTag: XmlTag, groupId: String, artifactId: String): XmlTag? {
        val dependenciesSections = mutableListOf<XmlTag>()

        rootTag.findFirstSubTag("dependencies")?.let { dependenciesSections.add(it) }
        rootTag.findFirstSubTag("dependencyManagement")
            ?.findFirstSubTag("dependencies")
            ?.let { dependenciesSections.add(it) }

        for (depsTag in dependenciesSections) {
            for (dep in depsTag.findSubTags("dependency")) {
                if (dep.findFirstSubTag("groupId")?.value?.text == groupId &&
                    dep.findFirstSubTag("artifactId")?.value?.text == artifactId) {
                    return dep
                }
            }
        }

        return null
    }
}
