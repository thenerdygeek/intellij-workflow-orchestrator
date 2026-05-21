package com.workflow.orchestrator.core.services.link

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.pom.Navigatable
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.workflow.orchestrator.core.model.ChatLink
import com.workflow.orchestrator.core.model.LinkResolution
import com.workflow.orchestrator.core.notifications.WorkflowNotificationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ClassLinkResolver(private val project: Project) {

    fun resolve(link: ChatLink.ClassLink): LinkResolution {
        val simple = link.fqn.substringAfterLast('.')
        val label = if (link.method != null) "$simple#${link.method}" else simple
        val description = if (link.method != null)
            "Opens method ${link.fqn}#${link.method}"
        else
            "Opens class ${link.fqn}"
        return LinkResolution(
            kind = LinkResolution.Kind.CLASS,
            raw = link.raw,
            displayLabel = label,
            targetDescription = description,
        )
    }

    suspend fun open(link: ChatLink.ClassLink) {
        val target: PsiElement? = readAction {
            val cls = JavaPsiFacade.getInstance(project)
                .findClass(link.fqn, GlobalSearchScope.allScope(project)) ?: return@readAction null
            if (link.method != null) {
                cls.findMethodsByName(link.method, true).firstOrNull() ?: cls
            } else cls
        }
        if (target == null) {
            notifyMissing("Class not found: ${link.fqn}")
            return
        }
        withContext(Dispatchers.EDT) {
            (target as? Navigatable)?.navigate(true)
        }
    }

    private fun notifyMissing(message: String) {
        WorkflowNotificationService.getInstance(project)
            .notifyWarning(WorkflowNotificationService.GROUP_AGENT, "Link", message)
    }
}
