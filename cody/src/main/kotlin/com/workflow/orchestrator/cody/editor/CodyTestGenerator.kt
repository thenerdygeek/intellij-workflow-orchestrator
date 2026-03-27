package com.workflow.orchestrator.cody.editor

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiMethod
import com.workflow.orchestrator.core.ai.LlmBrainFactory
import com.workflow.orchestrator.sonar.model.LineCoverageStatus
import com.workflow.orchestrator.sonar.service.SonarDataService

class CodyTestGenerator : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(
        elements: MutableList<out PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>
    ) {
        if (elements.isEmpty()) return
        val file = elements.firstOrNull()?.containingFile ?: return
        val project = file.project

        if (!LlmBrainFactory.isAvailable()) return

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
                { "Workflow: Cover with AI Agent" },
                { _, _ ->
                    val prompt = buildString {
                        appendLine("Generate unit tests for the uncovered method in this file.")
                        appendLine()
                        appendLine("**Source file:** $relativePath")
                        appendLine("**Method lines:** $methodStartLine-$methodEndLine")
                        appendLine()
                        appendLine("**Instructions:**")
                        appendLine("- Use JUnit 5 with standard assertions")
                        appendLine("- Read existing tests to match the project's test style and imports")
                        appendLine("- If an existing test file exists, add new test methods to it")
                        appendLine("- If no test file exists, create one with proper package and imports")
                        appendLine("- Run the tests after writing them to verify they pass")
                        appendLine("- If tests fail, read the error and fix them")
                    }

                    com.workflow.orchestrator.core.ai.AgentChatRedirect.getInstance()
                        ?.sendToAgent(project, prompt, listOf(virtualFile.path))
                },
                GutterIconRenderer.Alignment.RIGHT,
                { "Workflow: Cover with AI Agent" }
            )
            result.add(marker)
        }
    }
}
