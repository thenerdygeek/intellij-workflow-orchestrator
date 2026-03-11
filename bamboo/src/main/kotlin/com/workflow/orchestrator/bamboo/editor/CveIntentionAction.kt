package com.workflow.orchestrator.bamboo.editor

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.workflow.orchestrator.bamboo.service.CveRemediationService
import com.workflow.orchestrator.core.notifications.WorkflowNotificationService

class CveIntentionAction : IntentionAction, PriorityAction {

    override fun getText() = "Bump to fix CVE vulnerability"
    override fun getFamilyName() = "Workflow Orchestrator CVE"
    override fun getPriority() = PriorityAction.Priority.HIGH
    override fun startInWriteAction() = false

    override fun isAvailable(project: Project, editor: Editor, file: PsiFile): Boolean {
        if (file !is XmlFile || file.name != "pom.xml") return false

        val element = file.findElementAt(editor.caretModel.offset) ?: return false
        val dependencyTag = findDependencyTag(element) ?: return false

        val groupId = dependencyTag.findFirstSubTag("groupId")?.value?.text ?: return false
        val artifactId = dependencyTag.findFirstSubTag("artifactId")?.value?.text ?: return false

        return CveRemediationService.getInstance(project)
            .vulnerabilities.value
            .any { it.groupId == groupId && it.artifactId == artifactId }
    }

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        val element = file.findElementAt(editor.caretModel.offset) ?: return
        val dependencyTag = findDependencyTag(element) ?: return
        val groupId = dependencyTag.findFirstSubTag("groupId")?.value?.text ?: return
        val artifactId = dependencyTag.findFirstSubTag("artifactId")?.value?.text ?: return

        val cve = CveRemediationService.getInstance(project)
            .vulnerabilities.value
            .find { it.groupId == groupId && it.artifactId == artifactId }
            ?: return

        if (cve.fixedVersion == null) {
            WorkflowNotificationService.getInstance(project).notifyWarning(
                "workflow.quality",
                "CVE: No Fix Version Known",
                "${cve.cveId} (${cve.severity}) affects $artifactId:${cve.currentVersion}. " +
                    "No automated fix version available — please check for updates manually."
            )
            return
        }

        val versionTag = findVersionTag(dependencyTag, file as XmlFile) ?: return

        WriteCommandAction.runWriteCommandAction(project, "Bump CVE Dependency", null, {
            versionTag.value.text = cve.fixedVersion
        })

        WorkflowNotificationService.getInstance(project).notifyInfo(
            "workflow.quality",
            "CVE Fixed",
            "Bumped $artifactId ${cve.currentVersion} → ${cve.fixedVersion} (fixes ${cve.cveId})"
        )
    }

    private fun findDependencyTag(element: com.intellij.psi.PsiElement): XmlTag? {
        var tag = PsiTreeUtil.getParentOfType(element, XmlTag::class.java)
        while (tag != null) {
            if (tag.name == "dependency") return tag
            tag = tag.parentTag
        }
        return null
    }

    private fun findVersionTag(dependencyTag: XmlTag, xmlFile: XmlFile): XmlTag? {
        val directVersion = dependencyTag.findFirstSubTag("version")
        if (directVersion != null) {
            val text = directVersion.value.text
            if (text.startsWith("\${") && text.endsWith("}")) {
                val propertyName = text.substring(2, text.length - 1)
                return findPropertyTag(xmlFile, propertyName)
            }
            return directVersion
        }

        val rootTag = xmlFile.rootTag ?: return null
        val depMgmt = rootTag.findFirstSubTag("dependencyManagement") ?: return null
        val deps = depMgmt.findFirstSubTag("dependencies") ?: return null
        val groupId = dependencyTag.findFirstSubTag("groupId")?.value?.text
        val artifactId = dependencyTag.findFirstSubTag("artifactId")?.value?.text

        for (dep in deps.findSubTags("dependency")) {
            if (dep.findFirstSubTag("groupId")?.value?.text == groupId &&
                dep.findFirstSubTag("artifactId")?.value?.text == artifactId) {
                return dep.findFirstSubTag("version")
            }
        }

        return null
    }

    private fun findPropertyTag(xmlFile: XmlFile, propertyName: String): XmlTag? {
        val properties = xmlFile.rootTag?.findFirstSubTag("properties") ?: return null
        return properties.findFirstSubTag(propertyName)
    }
}
