package com.workflow.orchestrator.agent.tools.vcs

import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.ChangeListManagerImpl
import com.intellij.openapi.vcs.changes.shelf.ShelveChangesManager
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.docs.Relationship
import com.workflow.orchestrator.agent.tools.docs.SideEffectKind
import com.workflow.orchestrator.agent.tools.docs.ToolDocumentation
import com.workflow.orchestrator.agent.tools.docs.VerdictSeverity
import com.workflow.orchestrator.agent.tools.docs.toolDoc
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.*

class ChangelistShelveTool : AgentTool {
    override val name = "changelist_shelve"
    override val description = "Manage VCS changelists and shelve/unshelve changes. " +
        "Actions: list (changelists), list_shelves, create (changelist), shelve (current changes), unshelve (shelved changes). " +
        "Shelving saves your changes and reverts the working tree."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "The action to perform",
                enumValues = listOf("list", "list_shelves", "create", "shelve", "unshelve")
            ),
            "name" to ParameterProperty(
                type = "string",
                description = "Changelist name (for create action)"
            ),
            "comment" to ParameterProperty(
                type = "string",
                description = "Description (for create/shelve actions)"
            ),
            "shelf_index" to ParameterProperty(
                type = "integer",
                description = "0-based index of shelf to unshelve (from list_shelves output)"
            )
        ),
        required = listOf("action")
    )
    override val allowedWorkers = setOf(WorkerType.CODER)

    override fun documentation(): ToolDocumentation = toolDoc("changelist_shelve") {
        summary {
            technical("Dispatches across 5 actions on the IntelliJ ChangeListManager + ShelveChangesManager: list changelists, list shelves, create a named changelist, shelve all current working-tree changes (reverting them), and unshelve a previously-shelved entry by 0-based index. State lives in IntelliJ's shelf store, NOT git's stash list — the two are separate worlds.")
            plain("Like `git stash`, but the changes go into IntelliJ's own 'Shelf' drawer instead of the git stash pile. The agent can park work-in-progress, list what's parked, restore it later, or organise pending changes into named buckets (changelists). Anything saved here is invisible to plain `git stash list`.")
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.IDE_MUTATION)
        counterfactual(
            "Without changelist_shelve, the LLM falls back to `run_command git stash push -m 'msg'` / `git stash pop` — which uses a different storage system (`.git/refs/stash`) the IDE doesn't surface in its Shelf UI, and which can leave the working tree in a half-applied state on conflicts. The user loses the integrated 'restore via Shelf tool window' workflow. The fallback works, just less cleanly. Use case is rare enough that the loss is mostly aesthetic, not capability."
        )
        llmMistake("Assumes shelves and `git stash` are the same store — shelves the changes via this tool, then later runs `git stash list` and sees nothing, panics, and restores from a stale stash. The two stores are physically separate.")
        llmMistake("Calls `shelve` without first checking `list` for uncommitted changes — gets a 'no changes to shelve' response when the working tree is already clean.")
        llmMistake("Forgets `list_shelves` is required before `unshelve` — guesses an index and unshelves the wrong entry, or passes a stale index after a previous unshelve has shifted the list.")
        llmMistake("Uses 1-based indexing for `shelf_index` (because `list` shows '1.', '2.', '3.' for changelists) when shelf indices are 0-based — gets `Invalid shelf_index` or unshelves the entry one position off.")
        llmMistake("Treats `create` as 'create a shelf' — it actually creates an empty CHANGELIST (a sibling of the default changelist), not a shelf entry. The two concepts are visually similar in the IntelliJ UI but mechanically distinct.")
        actions {
            action("list") {
                description {
                    technical("Reads `ChangeListManager.changeLists` under a readAction; reports each changelist's name, default-marker, and modified-file count.")
                    plain("Shows the named buckets that group your uncommitted changes (the default bucket is just called 'Changes'). Like the labels on a stack of sticky notes — none of these are shelved yet, they're still in the working tree.")
                }
                whenLLMUses("Before shelving, to see what's currently uncommitted; or to confirm a `create` action took effect; or as a quick 'do I have uncommitted work?' probe.")
                rejectsParam("name", "`list` reads state; the changelist name is irrelevant.")
                rejectsParam("comment", "`list` reads state.")
                rejectsParam("shelf_index", "`list` reads changelists, not shelves — `shelf_index` is for `unshelve`.")
                onSuccess("Returns a numbered list of changelists with `[DEFAULT]` marker on the active one and `N files modified` per row. Empty result is `No changelists found.`")
                onFailure("readAction throws", "Caught by the outer try/catch; returns `Error executing changelist_shelve (list): <message>` with isError=true.")
                example("survey before shelving") {
                    param("action", "list")
                    outcome("Tool returns `Changelists (1):\\n  1. [DEFAULT] Changes — 3 files modified`. LLM now knows there's work to shelve.")
                }
                verdict {
                    keep("Cheap, read-only, useful precondition for any other action. Bundles cleanly with the other shelve operations.", VerdictSeverity.NORMAL)
                }
            }
            action("list_shelves") {
                description {
                    technical("Reads `ShelveChangesManager.shelvedChangeLists`; reports description, date (yyyy-MM-dd), and file count per shelf with 0-based positional index.")
                    plain("Shows what's currently parked in the IntelliJ Shelf — the 'I'll come back to this' drawer. Each entry has an index you'll need to restore it.")
                }
                whenLLMUses("Always before `unshelve` (so the LLM can pick the right index), and as a quick audit before deciding whether to `shelve` more.")
                rejectsParam("name", "Listing doesn't take parameters.")
                rejectsParam("comment", "Listing doesn't take parameters.")
                rejectsParam("shelf_index", "Listing doesn't take parameters.")
                onSuccess("Returns `Shelved changes (N):` followed by `  0. <description> (yyyy-MM-dd) — N files`, etc. Empty result is `No shelved changes found.`")
                onFailure("ShelveChangesManager unavailable", "Wrapped by outer try/catch; returns descriptive error with isError=true.")
                example("audit before unshelving") {
                    param("action", "list_shelves")
                    outcome("Tool returns `Shelved changes (2):\\n  0. WIP feature X (2026-05-08) — 4 files\\n  1. untitled (2026-05-09) — 2 files`. LLM picks index 0.")
                }
                verdict {
                    keep("Mandatory companion for `unshelve` — without it the LLM has no way to pick a valid index.", VerdictSeverity.NORMAL)
                }
            }
            action("create") {
                description {
                    technical("Casts ChangeListManager to ChangeListManagerImpl and calls `addChangeList(name, comment)`. Creates a new (empty) changelist alongside the default one. Does NOT move any changes into it; the user/LLM has to do that separately via the IntelliJ UI.")
                    plain("Creates a new empty named bucket for grouping uncommitted changes — like creating a new playlist before adding songs. Useful for organising work-in-progress before a partial commit.")
                }
                whenLLMUses("Rarely. Mostly when a user-facing workflow involves splitting one set of changes into multiple commits and the LLM wants to label each pile.")
                params {
                    required("name", "string") {
                        llmSeesIt("Changelist name (for create action)")
                        humanReadable("The label for the new changelist — shows up in the Local Changes tool window.")
                        whenPresent("A new empty changelist is created with this name. Duplicate names are silently allowed by IntelliJ (it appends a counter or returns the existing list — implementation-dependent).")
                        example("Refactor extraction")
                        example("WIP - skipping for review")
                    }
                    optional("comment", "string") {
                        llmSeesIt("Description (for create/shelve actions)")
                        humanReadable("An optional description shown next to the changelist name.")
                        whenPresent("Stored as the changelist's description.")
                        whenAbsent("Empty string — no description set.")
                    }
                }
                rejectsParam("shelf_index", "`create` operates on changelists, not shelves.")
                onSuccess("Returns `Created changelist '<name>'.` (plus comment if set).")
                onFailure("name missing", "Returns `Missing required parameter: name (for create action)` with isError=true.")
                onFailure("ChangeListManagerImpl cast fails on a non-default platform", "Caught by outer try/catch; returns the underlying ClassCastException message. Unlikely on stock IntelliJ but possible on stripped-down embeddings.")
                example("create a named bucket") {
                    param("action", "create")
                    param("name", "Refactor extraction")
                    param("comment", "Pre-extraction baseline")
                    outcome("Tool returns `Created changelist 'Refactor extraction'. Comment: Pre-extraction baseline`.")
                }
                verdict {
                    keep("Useful for organising multi-commit refactors. Cheap to keep alongside the other actions.", VerdictSeverity.WEAK)
                    drop("LLMs almost never reach for this. The agent rarely splits work into named changelists; it prefers separate branches or sequential commits. Could be dropped without losing capability.", VerdictSeverity.WEAK)
                }
            }
            action("shelve") {
                description {
                    technical("Collects `ChangeListManager.allChanges` and calls `ShelveChangesManager.shelveChanges(changes, comment, rollback=true)`. The `rollback=true` flag reverts the working tree for shelved files — same effect as `git stash push` on the working tree.")
                    plain("Saves your current uncommitted changes into the IntelliJ Shelf and then reverts the files in your editor. Like 'git stash' but stored in the IDE's drawer instead of git's stash pile.")
                }
                whenLLMUses("When the user wants to set aside in-progress work to try a different approach, or when the LLM needs a clean working tree to test a change against `main`. Rare in practice — the LLM more commonly suggests committing to a branch.")
                params {
                    optional("comment", "string") {
                        llmSeesIt("Description (for create/shelve actions)")
                        humanReadable("A label to attach to this shelf entry so you can recognise it later in `list_shelves`.")
                        whenPresent("Used as the shelf description.")
                        whenAbsent("Defaults to the literal string `Shelved changes` — generic, harder to disambiguate later.")
                        example("WIP - investigating null pointer")
                    }
                }
                rejectsParam("name", "`name` is for `create`; shelves don't take an explicit name (the description doubles as the label).")
                rejectsParam("shelf_index", "`shelve` creates a new shelf entry; index is for `unshelve`.")
                precondition("there must be at least one uncommitted change in the working tree — otherwise returns `No changes to shelve.`")
                onSuccess("Returns `Shelved N file(s) as '<description>'.\\nWorking tree has been reverted for shelved files.` The files in the editor are now reverted to their committed state.")
                onFailure("no changes", "Returns `No changes to shelve.` (informational, NOT isError — by design, the LLM should treat this as 'nothing to do').")
                onFailure("filesystem write fails (rare)", "Caught by outer try/catch; descriptive error with isError=true.")
                example("park WIP before testing main") {
                    param("action", "shelve")
                    param("comment", "WIP feature X")
                    outcome("Tool returns `Shelved 4 file(s) as 'WIP feature X'.` Working tree is clean; LLM can now switch branches or run tests against the committed state.")
                }
                verdict {
                    keep("The signature operation of this tool — without it `list_shelves`/`unshelve` are useless.", VerdictSeverity.NORMAL)
                    drop("The LLM almost always prefers `run_command git stash push -m '...'` because that's what coding-agent training data emphasises. In practice this action sees very few invocations.", VerdictSeverity.WEAK)
                }
            }
            action("unshelve") {
                description {
                    technical("Reads shelf at `shelf_index` from `ShelveChangesManager.shelvedChangeLists`, calls `unshelveChangeList(shelf, null, null, defaultChangeList, removeOriginal=true)` — restoring the changes into the default changelist and removing the shelf entry.")
                    plain("Restores a previously-shelved bunch of changes back into your working tree. The shelf entry is consumed (removed from the shelf list) once it's been restored.")
                }
                whenLLMUses("After `list_shelves` to bring a parked WIP back into the working tree — usually because the user is ready to continue that line of work or merge it.")
                params {
                    required("shelf_index", "integer") {
                        llmSeesIt("0-based index of shelf to unshelve (from list_shelves output)")
                        humanReadable("Which shelf entry to restore. The number from the leftmost column of `list_shelves` output — they start at 0, not 1.")
                        whenPresent("That shelf is unshelved into the default changelist.")
                        constraint("must be ≥ 0 and < the number of shelves; out-of-range returns an explicit `Invalid shelf_index N. Available range: 0..M` error")
                        constraint("indices shift after each unshelve — re-run `list_shelves` if doing multiple unshelves in sequence")
                        example("0")
                        example("2")
                    }
                }
                rejectsParam("name", "`unshelve` targets an existing shelf by index, not by name.")
                rejectsParam("comment", "`unshelve` doesn't take a comment.")
                precondition("at least one shelf entry must exist — `unshelve` on an empty shelf store returns an out-of-range error")
                precondition("there should be no working-tree conflict on the shelved files — IntelliJ surfaces conflicts in its merge UI but this tool does not currently relay that state through the result")
                onSuccess("Returns `Unshelved '<description>' into changelist '<defaultName>'.` The files reappear in the working tree; the shelf entry is consumed.")
                onFailure("missing shelf_index", "Returns `Missing required parameter: shelf_index (for unshelve action). Use list_shelves to see available indices.` with isError=true.")
                onFailure("index out of range", "Returns `Invalid shelf_index N. Available range: 0..M (M+1 shelves).` with isError=true.")
                onFailure("conflict on apply", "IntelliJ opens its merge dialog; tool may return success but leave the user staring at a modal. Subtle UX bug — the LLM doesn't see the conflict state via the result.")
                example("restore the most recent shelf") {
                    param("action", "unshelve")
                    param("shelf_index", "0")
                    outcome("Tool returns `Unshelved 'WIP feature X' into changelist 'Changes'.` The 4 files are back in the editor.")
                }
                verdict {
                    keep("The other half of the shelve workflow — without it `shelve` is a one-way trip. Cannot be removed independently of `shelve`.", VerdictSeverity.NORMAL)
                }
            }
        }
        verdict {
            keep("The only IDE-native VCS tool the agent has. Shelving via the IDE preserves the user's IntelliJ Shelf workflow (visible in the Shelf tool window, restorable via right-click). Tool is self-contained, ~220 lines, and consolidates 5 related operations behind one schema entry.", VerdictSeverity.NORMAL)
            drop("The LLM rarely reaches for it — `run_command git stash` covers the common case and is in the training distribution. Real-world telemetry (if collected) would likely show <1% of sessions invoke any action here. Could plausibly be moved out of the deferred catalog entirely with no capability loss; the schema cost is small but non-zero.", VerdictSeverity.WEAK)
        }
        observation("IDE-shelf vs git-stash divergence: the two stores are physically separate. Bytes shelved here are invisible to `git stash list` and vice versa. The plain-language description warns about this but the LLM still occasionally conflates them.")
        observation("`create` is structurally different from the other 4 actions — it operates on changelists (organisational buckets for uncommitted changes) rather than shelves (parked snapshots). Bundling it under `changelist_shelve` is technically correct (both go through ChangeListManager) but conceptually awkward. Could be split into a separate `changelist_create` tool, though that costs schema tokens for a rarely-used action.")
        mergeOpportunity("If `revert_file` ever grows a 'preserve before reverting' option, that option could absorb `shelve` — the unified narrative would be 'revert-with-snapshot'. Today they're cleanly separate (revert_file targets a single path; shelve takes the entire working tree).")
        related("revert_file", Relationship.SEE_ALSO, "Contrast: revert_file discards changes for a single file with no preservation; changelist_shelve(action=shelve) preserves all changes IDE-side before reverting.")
        related("edit_file", Relationship.COMPOSE_WITH, "Workflow: shelve current changes, edit_file to try a different approach, unshelve if the new approach fails. Useful for A/B-style experimentation.")
        related("run_command", Relationship.ALTERNATIVE, "`run_command git stash push -m '<msg>'` is the git-native alternative — different storage (git stash list vs IDE shelf), but covers the same 'park WIP' use case. LLM falls back to this when changelist_shelve isn't available.")
        downside("Storage divergence: shelved entries are NOT visible to git, GitHub Desktop, the command line, or any non-IntelliJ git client. A user who shelves in the IDE then runs `git stash list` from the terminal sees nothing — easy to lose track.")
        downside("Conflict-on-restore is silent: `unshelve` with conflicts opens IntelliJ's merge UI but the tool result reports success. The LLM doesn't get the signal that user attention is needed.")
        downside("`shelve` always shelves ALL working-tree changes — there's no per-file selection. If the user only wants to shelve part of the changes, the tool can't help and they need the IntelliJ UI directly.")
        downside("Indices in `unshelve` are unstable: each successful unshelve removes its entry, so the next entry's index drops by 1. The LLM has to re-run `list_shelves` between unshelves or it'll pick the wrong target.")
        downside("`create` cast to `ChangeListManagerImpl` could fail on non-stock IntelliJ Platform builds (e.g. stripped IDEs that ship a different impl). Unlikely in practice but the cast is unguarded.")
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val action = params["action"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(
                "Missing required parameter: action",
                "Error",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        return try {
            when (action) {
                "list" -> listChangelists(project)
                "list_shelves" -> listShelves(project)
                "create" -> createChangelist(project, params)
                "shelve" -> shelveChanges(project, params)
                "unshelve" -> unshelveChanges(project, params)
                else -> ToolResult(
                    "Invalid action '$action'. Must be one of: list, list_shelves, create, shelve, unshelve",
                    "Error",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
            }
        } catch (e: Exception) {
            ToolResult(
                "Error executing changelist_shelve ($action): ${e.message}",
                "Error",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }

    private suspend fun listChangelists(project: Project): ToolResult {
        val content = readAction {
            val clm = ChangeListManager.getInstance(project)
            val changeLists = clm.changeLists

            if (changeLists.isEmpty()) {
                return@readAction "No changelists found."
            }

            buildString {
                appendLine("Changelists (${changeLists.size}):")
                changeLists.forEachIndexed { index, cl ->
                    val defaultMarker = if (cl.isDefault) "[DEFAULT] " else ""
                    val fileCount = cl.changes.size
                    val filesDesc = if (fileCount == 1) "1 file modified" else "$fileCount files modified"
                    appendLine("  ${index + 1}. $defaultMarker${cl.name} — $filesDesc")
                }
            }
        }

        return ToolResult(
            content = content,
            summary = "Listed changelists",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }

    private fun listShelves(project: Project): ToolResult {
        val shelveManager = ShelveChangesManager.getInstance(project)
        val shelves = shelveManager.shelvedChangeLists

        if (shelves.isEmpty()) {
            return ToolResult("No shelved changes found.", "No shelves", 5)
        }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val content = buildString {
            appendLine("Shelved changes (${shelves.size}):")
            shelves.forEachIndexed { index, shelf ->
                val dateStr = shelf.date?.let { dateFormat.format(it) } ?: "unknown date"
                val description = shelf.description?.takeIf { it.isNotBlank() } ?: "untitled"
                @Suppress("DEPRECATION")
                val fileCount = shelf.getChanges(project)?.size ?: 0
                val filesDesc = if (fileCount == 1) "1 file" else "$fileCount files"
                appendLine("  $index. $description ($dateStr) — $filesDesc")
            }
        }

        return ToolResult(
            content = content,
            summary = "Listed ${shelves.size} shelved changelists",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }

    private fun createChangelist(project: Project, params: JsonObject): ToolResult {
        val name = params["name"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(
                "Missing required parameter: name (for create action)",
                "Error",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val comment = params["comment"]?.jsonPrimitive?.contentOrNull ?: ""

        val clm = ChangeListManager.getInstance(project) as ChangeListManagerImpl
        val newList = clm.addChangeList(name, comment)

        val content = "Created changelist '${newList.name}'." +
            if (comment.isNotBlank()) " Comment: $comment" else ""

        return ToolResult(
            content = content,
            summary = "Created changelist '$name'",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }

    private fun shelveChanges(project: Project, params: JsonObject): ToolResult {
        val clm = ChangeListManager.getInstance(project)
        val allChanges = clm.allChanges.toList()

        if (allChanges.isEmpty()) {
            return ToolResult("No changes to shelve.", "No changes", 5)
        }

        val comment = params["comment"]?.jsonPrimitive?.contentOrNull ?: "Shelved changes"

        val shelveManager = ShelveChangesManager.getInstance(project)
        val shelvedList = shelveManager.shelveChanges(allChanges, comment, true)

        val content = buildString {
            appendLine("Shelved ${allChanges.size} file(s) as '${shelvedList.description ?: comment}'.")
            appendLine("Working tree has been reverted for shelved files.")
        }

        return ToolResult(
            content = content,
            summary = "Shelved ${allChanges.size} files",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }

    private fun unshelveChanges(project: Project, params: JsonObject): ToolResult {
        val shelfIndex = params["shelf_index"]?.jsonPrimitive?.intOrNull
            ?: return ToolResult(
                "Missing required parameter: shelf_index (for unshelve action). Use list_shelves to see available indices.",
                "Error",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val shelveManager = ShelveChangesManager.getInstance(project)
        val shelves = shelveManager.shelvedChangeLists

        if (shelfIndex < 0 || shelfIndex >= shelves.size) {
            return ToolResult(
                "Invalid shelf_index $shelfIndex. Available range: 0..${shelves.size - 1} (${shelves.size} shelves).",
                "Error",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        val shelf = shelves[shelfIndex]
        val clm = ChangeListManager.getInstance(project)
        val targetChangeList = clm.defaultChangeList

        shelveManager.unshelveChangeList(shelf, null, null, targetChangeList, true)

        val description = shelf.description?.takeIf { it.isNotBlank() } ?: "untitled"
        val content = "Unshelved '$description' into changelist '${targetChangeList.name}'."

        return ToolResult(
            content = content,
            summary = "Unshelved '$description'",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }
}
