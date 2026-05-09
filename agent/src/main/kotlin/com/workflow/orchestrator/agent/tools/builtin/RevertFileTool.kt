package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.docs.Relationship
import com.workflow.orchestrator.agent.tools.docs.SideEffectKind
import com.workflow.orchestrator.agent.tools.docs.ToolDocumentation
import com.workflow.orchestrator.agent.tools.docs.VerdictSeverity
import com.workflow.orchestrator.agent.tools.docs.toolDoc
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reverts a single file to its pre-edit state using git checkout.
 * Only available to CODER workers.
 */
class RevertFileTool : AgentTool {
    override val name = "revert_file"
    override val description = """Use to undo changes made to a file. Reverts a single file to its original state before the agent modified it (via git checkout). This is a surgical operation — only the specified file is reverted, all other changes remain intact. Use this when an edit introduced bugs, broke tests, or went in the wrong direction. This is safer than trying to manually reverse edits with edit_file, as it restores the exact original content."""

    override val parameters = FunctionParameters(
        properties = mapOf(
            "file_path" to ParameterProperty(
                type = "string",
                description = "Path to the file to revert (absolute or relative to the project root). The file must be tracked by git."
            ),
            "description" to ParameterProperty(
                type = "string",
                description = "Why you are reverting this file. This is recorded in the audit trail and shown to the user."
            )
        ),
        required = listOf("file_path", "description")
    )

    override val allowedWorkers = setOf(WorkerType.CODER)

    override fun documentation(): ToolDocumentation = toolDoc("revert_file") {
        summary {
            technical("Single-file revert via raw `git checkout -- <path>` shelled out from `ProcessBuilder` against the project root — restores the file to its committed HEAD state. Does NOT use IDE LocalHistory, file backups, or VFS refresh; uncommitted-but-not-yet-modified files (no diff vs HEAD) are no-ops, and the IDE editor view may show stale content until VFS picks up the on-disk change.")
            plain("Like `git checkout` for one file, but called from inside the IDE — wipes whatever the agent just did to a file and snaps it back to whatever git last remembered. Other files are left alone. If the file was never committed, there's nothing to go back to.")
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.FILE_WRITE)
        counterfactual(
            "Without revert_file, the LLM falls back to `run_command \"git checkout -- <path>\"` — which (a) is approval-gated as a generic shell command on every invocation (revert_file is session-approvable, so the user can grant once and stop being pestered), (b) routes through the wider command-safety analyzer rather than a single 'medium' classifier, and (c) goes through the same shell — so this tool is mostly a UX/approval-policy alias today, not a behavioural superset. Net cost of dropping: every revert becomes a per-invocation approval prompt and the destructive-git-policy hook (deferred per :agent CLAUDE.md) loses a chokepoint to wire through later."
        )
        llmMistake("Tries to revert a freshly-created file that was never committed — `git checkout` either no-ops (file is untracked) or errors, and the agent loops trying again instead of using `run_command rm <path>`.")
        llmMistake("Expects revert to undo IDE-side state — open editor tabs, breakpoints, run-config edits, or LocalHistory entries. It only touches the on-disk file via git; nothing else is rolled back.")
        llmMistake("Reverts a file with unsaved (Document-only) changes in the editor and assumes the editor buffer also reverts — the editor still shows the dirty buffer until the user reloads, because there's no `FileDocumentManager.reloadFiles` call after the checkout.")
        llmMistake("Passes a path outside the project root expecting the same path-traversal rejection that read_file/edit_file enforce — revert_file does NOT call PathValidator (see audit note), so the only protection is `git checkout`'s own pathspec validation.")
        llmMistake("Forgets the required `description` parameter and gets a missing-param error before any git work runs.")
        params {
            required("file_path", "string") {
                llmSeesIt("Path to the file to revert (absolute or relative to the project root). The file must be tracked by git.")
                humanReadable("Which file to undo edits on — same path you'd type at the terminal. Can be relative (`src/Foo.kt`) or absolute (`/Users/me/proj/src/Foo.kt`).")
                whenPresent("Path is naively resolved (absolute kept as-is, relative joined to `project.basePath`), canonicalised, then passed to `git checkout -- <canonicalPath>` running with the project root as cwd.")
                constraint("file must be tracked by git — untracked files produce a non-zero exit and a 'Revert failed' error")
                constraint("path is NOT validated by PathValidator — naive `startsWith(\"/\")` check. A path outside the project will be checked out by git if and only if git considers it part of the working tree (rare, but a documented gap)")
                example("src/main/kotlin/Foo.kt")
                example("/Users/me/proj/build.gradle")
            }
            required("description", "string") {
                llmSeesIt("Why you are reverting this file. This is recorded in the audit trail and shown to the user.")
                humanReadable("A short reason — appears in the chat result and the audit log so the user (and you, on review) know why the agent threw away its own edit.")
                whenPresent("Embedded into the success summary (`Reverted file <path>: <description>`) and the long-form result content.")
                example("edit broke the FooServiceTest assertions")
                example("wrong direction — switching to a different refactor approach")
            }
        }
        verdict {
            keep(
                "Safety valve for the LLM's own bad edits. Even though the underlying primitive is just `git checkout`, having a dedicated tool gives the user a session-approvable path (instead of a per-invocation approval gate on raw run_command), a 'medium' risk classification distinct from arbitrary shell, and a future hook point for the deferred destructive-git-policy layer (CLAUDE.md → 'Revert Architecture'). Removing it pushes every revert through run_command's stricter approval flow and erases that hook point.",
                VerdictSeverity.NORMAL,
            )
            drop(
                "Today this is a thin shell-out — same `git checkout` the LLM could call via run_command, no IDE integration (no VFS refresh, no LocalHistory, no editor reload, no PathValidator). If the destructive-git-policy layer never lands and run_command's approval UX is improved, this tool earns no behavioural keep — only a UX/approval-policy keep.",
                VerdictSeverity.WEAK,
            )
        }
        related("edit_file", Relationship.SEE_ALSO, "The inverse — edit_file is what produces the change that revert_file undoes.")
        related("read_file", Relationship.COMPLEMENT, "Verify the revert worked by reading the file again — confirms the on-disk content matches HEAD.")
        related("run_command", Relationship.ALTERNATIVE, "The fallback `git checkout -- <path>` does the same thing, but is approval-gated per-invocation rather than session-approvable, and routes through the broader command-safety analyzer.")
        related("changelist_shelve", Relationship.SEE_ALSO, "Also a 'save state' tool — shelves edits onto an IntelliJ changelist instead of discarding them. Use shelve when you might want the changes back; revert_file is for throwing them away.")
        downside("Revert source is git HEAD only — no LocalHistory, no per-session backup, no in-memory undo stack. If a file was never committed, the revert is a no-op or an error.")
        downside("Does NOT refresh IntelliJ's VFS or reload editor Documents after the checkout. Open editor tabs may show stale content until the IDE notices the file changed on disk; the agent's own next read_file may also see Document-cached pre-revert text.")
        downside("Does NOT call PathValidator — security-relevant audit gap. Read tools and other write tools enforce project-root containment via canonical-path comparison; revert_file relies entirely on git's pathspec semantics. In practice, git refuses paths outside the working tree, but this is defence-in-depth the tool isn't doing itself.")
        downside("Single file at a time — no glob, no list, no bulk revert. Reverting 12 bad edits costs 12 tool calls.")
        downside("Doesn't preserve unsaved buffer changes — if the user has the file open with unsaved edits, those edits remain in the editor buffer (because the on-disk file changed underneath, but the Document was not reloaded).")
        observation("Implementation does not use PathValidator (cf. read_file, edit_file, create_file). Either add `PathValidator.resolveAndValidateForWrite` for parity, or document the deliberate reliance on git's pathspec validation.")
        mergeOpportunity("If a future destructive-git-policy meta-tool (CLAUDE.md → 'Revert Architecture') lands, revert_file is a natural action of that tool rather than a standalone — same pattern as `runtime_exec`/`debug_step` consolidating related verbs behind one schema.")
        observation("The `description` parameter is required and embedded into the audit trail / user-visible summary — unusual for write tools (edit_file/create_file have no analogous required justification). Worth keeping as a model for future destructive tools.")
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val filePath = params["file_path"]?.jsonPrimitive?.content
            ?: return ToolResult(
                content = "Error: 'file_path' parameter is required.",
                summary = "Error: missing file_path",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val description = params["description"]?.jsonPrimitive?.content
            ?: return ToolResult(
                content = "Error: 'description' parameter is required.",
                summary = "Error: missing description",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val resolvedPath = java.io.File(
            if (filePath.startsWith("/")) filePath
            else "${project.basePath}/$filePath"
        ).canonicalPath

        // Use git checkout to revert
        return try {
            val process = ProcessBuilder("git", "checkout", "--", resolvedPath)
                .directory(java.io.File(project.basePath ?: "."))
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                ToolResult(
                    content = "Successfully reverted $filePath. Reason: $description\n\n" +
                        "The file has been restored to its pre-edit state. Other file changes are preserved.",
                    summary = "Reverted file $filePath: $description",
                    tokenEstimate = 20
                )
            } else {
                ToolResult(
                    content = "Error: Failed to revert '$filePath': $output",
                    summary = "Revert failed: exit code $exitCode",
                    tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
            }
        } catch (e: Exception) {
            ToolResult(
                content = "Error: Failed to revert '$filePath': ${e.message}",
                summary = "Revert failed: ${e.message}",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }
}
