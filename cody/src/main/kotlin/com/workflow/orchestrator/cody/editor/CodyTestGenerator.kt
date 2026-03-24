package com.workflow.orchestrator.cody.editor

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiMethod
import com.workflow.orchestrator.cody.protocol.Position
import com.workflow.orchestrator.cody.protocol.Range
import com.workflow.orchestrator.cody.service.CodyContextService
import com.workflow.orchestrator.cody.service.CodyEditService
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.sonar.model.LineCoverageStatus
import com.workflow.orchestrator.sonar.service.SonarDataService
import com.intellij.openapi.progress.runBackgroundableTask
import kotlinx.coroutines.runBlocking

class CodyTestGenerator : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(
        elements: MutableList<out PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>
    ) {
        if (elements.isEmpty()) return
        val file = elements.firstOrNull()?.containingFile ?: return
        val project = file.project

        val settings = PluginSettings.getInstance(project)
        if (settings.connections.sourcegraphUrl.isNullOrBlank()) return
        if (settings.state.codyEnabled == false) return
        if (!CredentialStore().hasToken(ServiceType.SOURCEGRAPH)) return

        val virtualFile = file.virtualFile ?: return
        val basePath = project.basePath ?: return
        val relativePath = virtualFile.path.removePrefix("$basePath/")

        val state = try {
            SonarDataService.getInstance(project).stateFlow.value
        } catch (_: Exception) { return }

        val fileCoverage = state.fileCoverage[relativePath] ?: return

        for (element in elements) {
            if (element !is PsiIdentifier) continue
            val method = element.parent as? PsiMethod ?: continue

            val doc = file.viewProvider.document ?: continue
            val methodStartLine = doc.getLineNumber(method.textRange.startOffset) + 1
            val methodEndLine = doc.getLineNumber(method.textRange.endOffset) + 1

            val hasUncoveredLines = (methodStartLine..methodEndLine).any { line ->
                fileCoverage.lineStatuses[line] == LineCoverageStatus.UNCOVERED
            }

            if (!hasUncoveredLines) continue

            val marker = LineMarkerInfo(
                element,
                element.textRange,
                AllIcons.RunConfigurations.TestState.Run,
                { "Workflow: Cover with Cody" },
                { _, _ ->
                    val range = Range(
                        start = Position(line = methodStartLine - 1, character = 0),
                        end = Position(line = methodEndLine, character = 0)
                    )
                    runBackgroundableTask("Generating test with Cody", project) {
                        runBlocking {
                            val contextService = project.service<CodyContextService>()
                            val testContext = contextService.gatherTestContext(
                                filePath = virtualFile.path,
                                targetRange = range
                            )
                            // Use enriched instruction (Spring-aware test patterns) via requestFix
                            // since requestTestGeneration doesn't accept custom instructions
                            CodyEditService(project).requestFix(
                                filePath = virtualFile.path,
                                range = range,
                                instruction = testContext.instruction,
                                contextFiles = testContext.contextFiles
                            )
                        }
                    }
                },
                GutterIconRenderer.Alignment.RIGHT,
                { "Workflow: Cover with Cody" }
            )
            result.add(marker)
        }
    }
}
