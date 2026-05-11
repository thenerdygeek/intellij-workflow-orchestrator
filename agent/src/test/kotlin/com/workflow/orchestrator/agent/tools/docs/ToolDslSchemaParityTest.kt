package com.workflow.orchestrator.agent.tools.docs

import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.AgentTool.Companion.OUTPUT_FILTERABLE_TOOLS
import com.workflow.orchestrator.agent.tools.builtin.AskQuestionsTool
import com.workflow.orchestrator.agent.tools.builtin.AttemptCompletionTool
import com.workflow.orchestrator.agent.tools.builtin.BackgroundProcessTool
import com.workflow.orchestrator.agent.tools.builtin.CreateFileTool
import com.workflow.orchestrator.agent.tools.builtin.CurrentTimeTool
import com.workflow.orchestrator.agent.tools.builtin.EditFileTool
import com.workflow.orchestrator.agent.tools.builtin.EnablePlanModeTool
import com.workflow.orchestrator.agent.tools.builtin.GlobFilesTool
import com.workflow.orchestrator.agent.tools.builtin.NewTaskTool
import com.workflow.orchestrator.agent.tools.builtin.PlanModeRespondTool
import com.workflow.orchestrator.agent.tools.builtin.ReadFileTool
import com.workflow.orchestrator.agent.tools.builtin.RenderArtifactTool
import com.workflow.orchestrator.agent.tools.builtin.RevertFileTool
import com.workflow.orchestrator.agent.tools.builtin.RunCommandTool
import com.workflow.orchestrator.agent.tools.builtin.SearchCodeTool
import com.workflow.orchestrator.agent.tools.builtin.SendStdinTool
import com.workflow.orchestrator.agent.tools.builtin.SpawnAgentTool
import com.workflow.orchestrator.agent.tools.builtin.TaskCreateTool
import com.workflow.orchestrator.agent.tools.builtin.TaskGetTool
import com.workflow.orchestrator.agent.tools.builtin.TaskReportTool
import com.workflow.orchestrator.agent.tools.builtin.TaskUpdateTool
import com.workflow.orchestrator.agent.tools.builtin.ThinkTool
import com.workflow.orchestrator.agent.tools.builtin.ToolSearchTool
import com.workflow.orchestrator.agent.tools.builtin.UseSkillTool
import com.workflow.orchestrator.agent.tools.database.DbExplainTool
import com.workflow.orchestrator.agent.tools.database.DbListDatabasesTool
import com.workflow.orchestrator.agent.tools.database.DbQueryTool
import com.workflow.orchestrator.agent.tools.database.DbSchemaTool
import com.workflow.orchestrator.agent.tools.debug.DebugBreakpointsTool
import com.workflow.orchestrator.agent.tools.debug.DebugInspectTool
import com.workflow.orchestrator.agent.tools.debug.DebugStepTool
import com.workflow.orchestrator.agent.tools.framework.BuildTool
import com.workflow.orchestrator.agent.tools.framework.DjangoTool
import com.workflow.orchestrator.agent.tools.framework.FastApiTool
import com.workflow.orchestrator.agent.tools.framework.FlaskTool
import com.workflow.orchestrator.agent.tools.framework.SpringTool
import com.workflow.orchestrator.agent.tools.ide.FormatCodeTool
import com.workflow.orchestrator.agent.tools.ide.OptimizeImportsTool
import com.workflow.orchestrator.agent.tools.ide.SemanticDiagnosticsTool
import com.workflow.orchestrator.agent.tools.integration.BambooBuildsTool
import com.workflow.orchestrator.agent.tools.integration.BambooPlansTool
import com.workflow.orchestrator.agent.tools.integration.BitbucketPrTool
import com.workflow.orchestrator.agent.tools.integration.BitbucketRepoTool
import com.workflow.orchestrator.agent.tools.integration.BitbucketReviewTool
import com.workflow.orchestrator.agent.tools.integration.JiraTool
import com.workflow.orchestrator.agent.tools.integration.SonarTool
import com.workflow.orchestrator.agent.tools.project.ProjectStructureTool
import com.workflow.orchestrator.agent.tools.psi.CallHierarchyTool
import com.workflow.orchestrator.agent.tools.psi.DataFlowAnalysisTool
import com.workflow.orchestrator.agent.tools.psi.FileStructureTool
import com.workflow.orchestrator.agent.tools.psi.FindDefinitionTool
import com.workflow.orchestrator.agent.tools.psi.FindImplementationsTool
import com.workflow.orchestrator.agent.tools.psi.FindReferencesTool
import com.workflow.orchestrator.agent.tools.psi.GetAnnotationsTool
import com.workflow.orchestrator.agent.tools.psi.GetMethodBodyTool
import com.workflow.orchestrator.agent.tools.psi.TestFinderTool
import com.workflow.orchestrator.agent.tools.psi.TypeHierarchyTool
import com.workflow.orchestrator.agent.tools.psi.TypeInferenceTool
import com.workflow.orchestrator.agent.tools.runtime.CoverageTool
import com.workflow.orchestrator.agent.tools.runtime.JavaRuntimeExecTool
import com.workflow.orchestrator.agent.tools.runtime.PythonRuntimeExecTool
import com.workflow.orchestrator.agent.tools.runtime.RuntimeConfigTool
import com.workflow.orchestrator.agent.tools.runtime.RuntimeExecTool
import com.workflow.orchestrator.agent.tools.vcs.ChangelistShelveTool
import com.workflow.orchestrator.agent.tools.ToolRegistry
import com.workflow.orchestrator.agent.tools.debug.AgentDebugController
import com.workflow.orchestrator.agent.session.TaskStore
import com.workflow.orchestrator.agent.ide.LanguageProviderRegistry
import com.workflow.orchestrator.core.ai.LlmBrain
import com.intellij.openapi.project.Project
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/**
 * Enforces the contract: for every tool that declares its parameters in BOTH places —
 * the [ToolDocumentation] DSL (consumed by the tool-docs editor) and the
 * [com.workflow.orchestrator.agent.api.dto.FunctionParameters] schema (consumed by the
 * LLM via [com.workflow.orchestrator.core.ai.ToolPromptBuilder]) — the two must agree
 * on:
 *
 *   1. The per-param description text ([ParamDoc.descriptionLLM] vs
 *      [com.workflow.orchestrator.agent.api.dto.ParameterProperty.description]).
 *   2. The per-param type.
 *   3. The enum value set AND order (LLMs are sensitive to first-listed-enum bias).
 *
 * History — see [RunCommandTool]: a documentation default ("120s") was bumped to
 * "300s" in the DSL but not in the schema, drifting silently until manually
 * inspected. This test would have caught it.
 *
 * Whitelisted intentional mismatches:
 *   - `grep_pattern` / `output_file` are framework-injected by `AgentLoop`
 *     for tools in [OUTPUT_FILTERABLE_TOOLS]; they appear in the DSL but
 *     not the tool's own schema.
 *   - `run_command.shell` schema description is built at runtime from the
 *     list of effective shells, so it legitimately differs from the static
 *     DSL `llmSeesIt`.
 *
 * Scope:
 *   - Single-action tools: compares [ToolDocumentation.singleActionParams].
 *   - Meta-tools (`action`-dispatched): unions every per-action param across
 *     [ActionDoc.requiredParams] and [ActionDoc.optionalParams], and also
 *     flags cross-action `descriptionLLM` disagreement on the same name.
 *
 * Failure mode: prints a single grouped report listing every violation by tool;
 * intended to fail loudly on first run so all drift is visible at once.
 */
class ToolDslSchemaParityTest {

    /** (toolName, paramName) pairs known to be framework-injected — DSL_ONLY is acceptable for these. */
    private val frameworkInjected: Set<Pair<String, String>> =
        OUTPUT_FILTERABLE_TOOLS.flatMap { name ->
            listOf(name to "grep_pattern", name to "output_file")
        }.toSet()

    /** (toolName, paramName) pairs with intentionally dynamic schema descriptions. */
    private val dynamicDescriptions: Set<Pair<String, String>> = setOf(
        "run_command" to "shell",
    )

    /**
     * Factory list — every tool that declares a `params { … }` DSL block.
     * Wrapped in lambdas so a single broken constructor doesn't take down the whole audit.
     */
    private fun toolFactories(): List<Pair<String, () -> AgentTool>> = listOf(
        // builtin (single-action)
        "ReadFileTool" to { ReadFileTool() },
        "EditFileTool" to { EditFileTool() },
        "CreateFileTool" to { CreateFileTool() },
        "SearchCodeTool" to { SearchCodeTool() },
        "GlobFilesTool" to { GlobFilesTool() },
        "RunCommandTool" to { RunCommandTool() },
        "RevertFileTool" to { RevertFileTool() },
        "SendStdinTool" to { SendStdinTool() },
        "AttemptCompletionTool" to { AttemptCompletionTool() },
        "ThinkTool" to { ThinkTool() },
        "AskQuestionsTool" to { AskQuestionsTool() },
        "PlanModeRespondTool" to { PlanModeRespondTool() },
        "EnablePlanModeTool" to { EnablePlanModeTool() },
        "UseSkillTool" to { UseSkillTool() },
        "NewTaskTool" to { NewTaskTool() },
        "RenderArtifactTool" to { RenderArtifactTool() },
        "ToolSearchTool" to { ToolSearchTool(mockk<ToolRegistry>(relaxed = true)) },
        "CurrentTimeTool" to { CurrentTimeTool() },
        "TaskCreateTool" to { TaskCreateTool { null as TaskStore? } },
        "TaskUpdateTool" to { TaskUpdateTool { null as TaskStore? } },
        "TaskGetTool" to { TaskGetTool { null as TaskStore? } },
        "TaskReportTool" to { TaskReportTool() },
        "SpawnAgentTool" to {
            SpawnAgentTool(
                brainProvider = { mockk<LlmBrain>(relaxed = true) },
                toolRegistry = mockk<ToolRegistry>(relaxed = true),
                project = mockk<Project>(relaxed = true),
            )
        },
        "BackgroundProcessTool" to { BackgroundProcessTool() },
        // ide
        "FormatCodeTool" to { FormatCodeTool() },
        "OptimizeImportsTool" to { OptimizeImportsTool() },
        "SemanticDiagnosticsTool" to { SemanticDiagnosticsTool(LanguageProviderRegistry()) },
        // database
        "DbListDatabasesTool" to { DbListDatabasesTool() },
        "DbQueryTool" to { DbQueryTool() },
        "DbSchemaTool" to { DbSchemaTool() },
        "DbExplainTool" to { DbExplainTool() },
        // psi (all take LanguageProviderRegistry)
        "GetAnnotationsTool" to { GetAnnotationsTool(LanguageProviderRegistry()) },
        "FindDefinitionTool" to { FindDefinitionTool(LanguageProviderRegistry()) },
        "TestFinderTool" to { TestFinderTool(LanguageProviderRegistry()) },
        "TypeHierarchyTool" to { TypeHierarchyTool(LanguageProviderRegistry()) },
        "CallHierarchyTool" to { CallHierarchyTool(LanguageProviderRegistry()) },
        "GetMethodBodyTool" to { GetMethodBodyTool(LanguageProviderRegistry()) },
        "FindReferencesTool" to { FindReferencesTool(LanguageProviderRegistry()) },
        "FileStructureTool" to { FileStructureTool(LanguageProviderRegistry()) },
        "FindImplementationsTool" to { FindImplementationsTool(LanguageProviderRegistry()) },
        "DataFlowAnalysisTool" to { DataFlowAnalysisTool(LanguageProviderRegistry()) },
        "TypeInferenceTool" to { TypeInferenceTool(LanguageProviderRegistry()) },
        // framework meta-tools
        "BuildTool" to { BuildTool() },
        "DjangoTool" to { DjangoTool() },
        "FastApiTool" to { FastApiTool() },
        "FlaskTool" to { FlaskTool() },
        "SpringTool" to { SpringTool() },
        // integration meta-tools
        "JiraTool" to { JiraTool() },
        "SonarTool" to { SonarTool() },
        "BambooBuildsTool" to { BambooBuildsTool() },
        "BambooPlansTool" to { BambooPlansTool() },
        "BitbucketPrTool" to { BitbucketPrTool() },
        "BitbucketReviewTool" to { BitbucketReviewTool() },
        "BitbucketRepoTool" to { BitbucketRepoTool() },
        // runtime meta-tools
        "CoverageTool" to { CoverageTool() },
        "RuntimeExecTool" to { RuntimeExecTool() },
        "RuntimeConfigTool" to { RuntimeConfigTool() },
        "JavaRuntimeExecTool" to { JavaRuntimeExecTool() },
        "PythonRuntimeExecTool" to { PythonRuntimeExecTool() },
        // debug meta-tools
        "DebugStepTool" to { DebugStepTool(mockk<AgentDebugController>(relaxed = true)) },
        "DebugInspectTool" to { DebugInspectTool(mockk<AgentDebugController>(relaxed = true)) },
        "DebugBreakpointsTool" to { DebugBreakpointsTool(mockk<AgentDebugController>(relaxed = true)) },
        // project / vcs
        "ProjectStructureTool" to { ProjectStructureTool() },
        "ChangelistShelveTool" to { ChangelistShelveTool() },
    )

    private enum class Kind { DRIFT, ENUM_ORDER, ENUM_VALUES, TYPE_MISMATCH, DSL_ONLY, SCHEMA_ONLY, CROSS_ACTION_DSL_DRIFT, CONSTRUCT_FAILURE, DOC_FAILURE, NO_DOCS }

    private data class Violation(
        val toolName: String,
        val paramName: String,
        val kind: Kind,
        val message: String,
    )

    private fun isWhitelisted(toolName: String, paramName: String, kind: Kind): Boolean = when (kind) {
        Kind.DSL_ONLY -> (toolName to paramName) in frameworkInjected
        Kind.DRIFT, Kind.ENUM_ORDER, Kind.ENUM_VALUES -> (toolName to paramName) in dynamicDescriptions
        else -> false
    }

    private fun audit(tool: AgentTool): List<Violation> {
        val violations = mutableListOf<Violation>()
        val schema = tool.parameters.properties

        val doc: ToolDocumentation = try {
            tool.documentation()
        } catch (e: Throwable) {
            return listOf(Violation(tool.name, "(N/A)", Kind.DOC_FAILURE,
                "documentation() threw ${e.javaClass.simpleName}: ${e.message}"))
        } ?: return listOf(Violation(tool.name, "(N/A)", Kind.NO_DOCS,
            "tool.documentation() returned null — no DSL block authored. Schema has ${schema.size} params unaudited."))

        // Collect DSL params from singleActionParams + every action's required/optional.
        // For meta-tools we also union enum values across per-action declarations, because
        // the schema flattens to the union (JSON Schema can't express per-action enum subsets).
        val dslParams = linkedMapOf<String, ParamDoc>()
        val dslEnumUnion = linkedMapOf<String, LinkedHashSet<String>>()
        doc.singleActionParams?.let { group ->
            (group.required + group.optional).forEach {
                dslParams[it.name] = it
                if (it.enumValues.isNotEmpty()) {
                    dslEnumUnion.getOrPut(it.name) { linkedSetOf() }.addAll(it.enumValues)
                }
            }
        }
        doc.actions?.forEach { action ->
            (action.requiredParams + action.optionalParams).forEach { p ->
                val existing = dslParams[p.name]
                if (existing != null && existing.descriptionLLM != p.descriptionLLM) {
                    violations.add(Violation(
                        tool.name, p.name, Kind.CROSS_ACTION_DSL_DRIFT,
                        "DSL has different descriptionLLM for '${p.name}' across actions"
                    ))
                }
                dslParams[p.name] = p  // last-write-wins for description-comparison
                if (p.enumValues.isNotEmpty()) {
                    dslEnumUnion.getOrPut(p.name) { linkedSetOf() }.addAll(p.enumValues)
                }
            }
        }

        // For each DSL param, check it exists in schema and matches.
        for ((name, paramDoc) in dslParams) {
            val schemaProp = schema[name]
            if (schemaProp == null) {
                if (!isWhitelisted(tool.name, name, Kind.DSL_ONLY)) {
                    violations.add(Violation(
                        tool.name, name, Kind.DSL_ONLY,
                        "DSL declares '$name' but schema does not. DSL: \"${paramDoc.descriptionLLM.take(120)}\""
                    ))
                }
                continue
            }

            if (paramDoc.descriptionLLM != schemaProp.description &&
                !isWhitelisted(tool.name, name, Kind.DRIFT)) {
                violations.add(Violation(
                    tool.name, name, Kind.DRIFT,
                    "Description drift for '$name':\n" +
                        "    Schema: \"${schemaProp.description}\"\n" +
                        "    DSL:    \"${paramDoc.descriptionLLM}\""
                ))
            }

            if (paramDoc.type != schemaProp.type) {
                violations.add(Violation(
                    tool.name, name, Kind.TYPE_MISMATCH,
                    "Type drift for '$name': schema=${schemaProp.type}, DSL=${paramDoc.type}"
                ))
            }

            val schemaEnum = schemaProp.enumValues ?: emptyList()
            // For meta-tools, schema flattens to the union of per-action DSL enums.
            // For single-action tools, the param appears exactly once, so union == single.
            val dslEnumForCompare = dslEnumUnion[name]?.toList() ?: paramDoc.enumValues
            if (schemaEnum.toSet() != dslEnumForCompare.toSet() &&
                !isWhitelisted(tool.name, name, Kind.ENUM_VALUES)) {
                violations.add(Violation(
                    tool.name, name, Kind.ENUM_VALUES,
                    "Enum value sets differ for '$name': schema=$schemaEnum, DSL(union)=$dslEnumForCompare"
                ))
            } else if (schemaEnum != dslEnumForCompare && schemaEnum.isNotEmpty() &&
                doc.actions.isNullOrEmpty() && // order check only meaningful for single-action tools
                !isWhitelisted(tool.name, name, Kind.ENUM_ORDER)) {
                violations.add(Violation(
                    tool.name, name, Kind.ENUM_ORDER,
                    "Enum order differs for '$name': schema=$schemaEnum, DSL=${paramDoc.enumValues}"
                ))
            }
        }

        // For each schema param, check it has DSL coverage.
        val isMetaTool = !doc.actions.isNullOrEmpty()
        for ((name, schemaProp) in schema) {
            if (name !in dslParams) {
                // Meta-tools' `action` dispatcher param is implicitly documented
                // by the per-action structure of doc.actions, not as a top-level
                // ParamDoc. Skip it for those.
                if (name == "action" && isMetaTool) continue
                violations.add(Violation(
                    tool.name, name, Kind.SCHEMA_ONLY,
                    "Schema declares '$name' but DSL does not. Schema: \"${schemaProp.description.take(120)}\""
                ))
            }
        }

        return violations
    }

    @Test
    fun `DSL and schema parameter declarations agree across every tool`() {
        val violations = mutableListOf<Violation>()
        var toolsAudited = 0

        for ((label, factory) in toolFactories()) {
            val tool = try {
                factory()
            } catch (e: Throwable) {
                violations.add(Violation(label, "(constructor)", Kind.CONSTRUCT_FAILURE,
                    "Could not instantiate $label: ${e.javaClass.simpleName}: ${e.message}"))
                continue
            }
            toolsAudited++
            violations.addAll(audit(tool))
        }

        if (violations.isEmpty()) return  // ✅ all clean

        val report = buildString {
            appendLine("== DSL/Schema Parity Violations ==")
            appendLine("Tools audited: $toolsAudited / ${toolFactories().size}")
            appendLine("Violations: ${violations.size}")
            appendLine()

            val byTool = violations.groupBy { it.toolName }.toSortedMap()
            for ((toolName, vs) in byTool) {
                appendLine("---- $toolName (${vs.size}) ----")
                for (v in vs.sortedBy { it.kind.ordinal }) {
                    appendLine("  [${v.kind}] ${v.message}")
                }
                appendLine()
            }

            appendLine("Resolutions:")
            appendLine("  DRIFT / TYPE_MISMATCH      → make the two strings agree (DSL llmSeesIt = schema description).")
            appendLine("  ENUM_ORDER / ENUM_VALUES   → align enumValue(...) in DSL with enumValues = listOf(...) in schema.")
            appendLine("  DSL_ONLY                   → either remove from DSL or whitelist if framework-injected.")
            appendLine("  SCHEMA_ONLY                → add a DSL ParamDoc entry so the docs editor reflects reality.")
            appendLine("  CROSS_ACTION_DSL_DRIFT     → meta-tool's DSL describes the same param differently across actions.")
            appendLine("  CONSTRUCT_FAILURE          → tool's constructor needs a richer test fixture (add a mock).")
            appendLine("  DOC_FAILURE                → tool.documentation() threw; likely needs a Project mock.")
        }

        fail<Unit>(report)
    }
}
