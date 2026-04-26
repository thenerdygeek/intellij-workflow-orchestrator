package com.workflow.orchestrator.core.workflow

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Project-open hydration for [WorkflowContextService].
 *
 * Seed order matters: the editor-derived slice (`activeRepo`, `activeBranch`,
 * `editorModule`, `projectModules`) is populated BEFORE the legacy event mirror
 * is wired. Otherwise the first mirrored `BranchChanged` re-fires the cascade
 * with a stale-null editor slice. See
 * `docs/architecture/multi-module-compliance-plan.md` Phase A.
 */
class WorkflowContextProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val service = WorkflowContextService.getInstance(project)
        service.recomputeFromEditor()
        WorkflowEventMirror(project, service).install()
    }
}
