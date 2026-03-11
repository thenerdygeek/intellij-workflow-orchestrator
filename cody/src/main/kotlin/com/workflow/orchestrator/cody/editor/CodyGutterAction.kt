package com.workflow.orchestrator.cody.editor

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.psi.PsiElement
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.PluginSettings

class CodyGutterAction : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(
        elements: MutableList<out PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>
    ) {
        if (elements.isEmpty()) return
        val file = elements.firstOrNull()?.containingFile ?: return
        val project = file.project

        val settings = PluginSettings.getInstance(project)
        if (settings.state.sourcegraphUrl.isNullOrBlank()) return
        if (settings.state.codyEnabled == false) return
        if (!CredentialStore().hasToken(ServiceType.SOURCEGRAPH)) return

        // Phase 1E skeleton: Full implementation depends on Phase 1D's
        // SonarIssueAnnotator being active. When Sonar annotations are
        // available, this provider adds "Fix with Cody" markers on
        // lines with issues.
    }
}
