package com.workflow.orchestrator.agent.tools.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.ide.DetailLevel
import com.workflow.orchestrator.agent.ide.LanguageProviderRegistry
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.builtin.PathValidator
import com.workflow.orchestrator.agent.tools.docs.Relationship
import com.workflow.orchestrator.agent.tools.docs.SideEffectKind
import com.workflow.orchestrator.agent.tools.docs.ToolDocumentation
import com.workflow.orchestrator.agent.tools.docs.VerdictSeverity
import com.workflow.orchestrator.agent.tools.docs.toolDoc
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class FileStructureTool(
    private val registry: LanguageProviderRegistry
) : AgentTool {
    override val name = "file_structure"
    override val description = "Get the structure of a file: class declarations, method signatures, fields. No method bodies — use read_file for full content."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "path" to ParameterProperty(type = "string", description = "Absolute or project-relative file path"),
            "detail" to ParameterProperty(
                type = "string",
                description = "Detail level: 'signatures' (default — class/method/field names only), 'full' (includes method bodies, annotations, field initializers), 'minimal' (class names + field/method counts only)",
                enumValues = listOf("signatures", "full", "minimal")
            )
        ),
        required = listOf("path")
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ORCHESTRATOR)

    override fun documentation(): ToolDocumentation = toolDoc("file_structure") {
        summary {
            technical(
                "PSI-backed file outline: given an absolute or project-relative path, resolves the PsiFile " +
                "via VFS+PsiManager, picks the per-language `LanguageIntelligenceProvider` from the registry " +
                "via `forFile(psiFile)`, and calls `getFileStructure(psiFile, detailLevel)`. Returns a " +
                "formatted string of class declarations, method signatures, and field declarations at one of " +
                "three granularity levels (MINIMAL / SIGNATURES / FULL). For files with no registered provider " +
                "(non-Java/Kotlin/Python languages today), falls back to the first 100 raw text lines."
            )
            plain(
                "The agent's Structure tool window, made queryable. You hand it a file path and it gives you " +
                "an outline — class names, method signatures, field types — without dumping the entire file into " +
                "context. Think of it like the IDE's left-panel Structure view: you scan the headings to understand " +
                "what's in the file, then drill into specific methods with `get_method_body` or `read_file` only " +
                "when you know exactly what you need."
            )
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.READ_ONLY)

        counterfactual(
            "Without `file_structure`, the LLM uses `read_file` on the entire file to understand its shape — " +
            "which works but costs 5-30x more tokens for large files (a 1 000-line service class bloats to ~4 000 " +
            "tokens even though the LLM only needed the method list). Alternatively, `search_code` with a regex " +
            "like `^\\s*(public|private|override|fun|class)` approximates the outline but loses nesting context " +
            "(which methods belong to which class), misses annotations, and returns unordered flat hits instead of a " +
            "structured declaration tree. `file_structure` is the token-efficient first step in any 'understand this " +
            "file' task; `read_file` or `get_method_body` is the second step once the LLM knows exactly what it needs."
        )

        llmMistake(
            "Calls `file_structure` on a file it has already fully read via `read_file` in the same session — " +
            "wastes a round-trip with no new information. The LLM should call `file_structure` FIRST to get the " +
            "outline, then `read_file` only if it needs a specific section it can't get from `get_method_body`."
        )
        llmMistake(
            "Uses `detail='full'` when it only needs to know which methods exist — `full` includes method bodies " +
            "and can produce the same token cost as `read_file`. The correct default is `signatures` for discovery, " +
            "`full` only when the body content is actually needed (in which case `get_method_body` is usually better " +
            "because it targets one method)."
        )
        llmMistake(
            "Passes a directory path instead of a file path — VFS resolves directories to virtual folder nodes " +
            "that PsiManager cannot parse, so `findFile` returns null and the tool returns 'Error: Cannot read file'. " +
            "The LLM should use `glob_files` or `project_structure` to enumerate files inside a directory."
        )
        llmMistake(
            "Calls `file_structure` during indexing — `PsiToolUtils.isDumb(project)` at entry returns a dumb-mode " +
            "error immediately; the `inSmartMode(project)` deferral only applies to the inner read action, not the " +
            "guard. The LLM should wait one cycle or switch to `read_file` (which doesn't require the index) as a " +
            "temporary fallback."
        )
        llmMistake(
            "Treats the `minimal` output (class names + counts only) as sufficient to locate a method by name — " +
            "`minimal` intentionally omits individual member names, reporting only aggregate counts like " +
            "'12 methods, 5 fields'. The LLM must use `signatures` (the default) to enumerate members by name."
        )
        llmMistake(
            "Assumes `file_structure` works on any file type and passes a JSON, YAML, or XML configuration file — " +
            "these languages have no registered `LanguageIntelligenceProvider`, so the tool falls back to the first " +
            "100 raw text lines. The fallback is not a structured outline; for config files, `read_file` with an " +
            "offset is clearer and more reliable."
        )

        params {
            required("path", "string") {
                llmSeesIt("Absolute or project-relative file path")
                humanReadable(
                    "Path to the file whose structure you want. Accepts absolute paths (e.g. " +
                    "`/home/user/project/src/main/kotlin/Foo.kt`) and project-relative paths " +
                    "(e.g. `src/main/kotlin/Foo.kt` resolved against `project.basePath`). " +
                    "Validated by `PathValidator.resolveAndValidate` — symlink-resolved and checked " +
                    "against the project root to prevent path-traversal."
                )
                whenPresent(
                    "Path is resolved and validated by `PathValidator`. Then `LocalFileSystem.findFileByPath` " +
                    "locates the VirtualFile. `PsiManager.findFile` parses it into a PSI tree. " +
                    "`LanguageProviderRegistry.forFile(psiFile)` selects the provider by language ID. " +
                    "For registered languages (Java/Kotlin, Python) `getFileStructure` returns a structured outline. " +
                    "For unregistered languages the raw first 100 lines are returned."
                )
                constraint("must be a file path, not a directory — directory paths cause a 'Cannot read file' error")
                constraint("must point to an existing, readable file — missing files return 'Error: File not found'")
                example("src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt")
                example("/Users/dev/project/src/main/java/com/example/UserRepository.java")
                example("src/main/python/app/services/auth_service.py")
            }
            optional("detail", "string") {
                llmSeesIt(
                    "Detail level: 'signatures' (default — class/method/field names only), " +
                    "'full' (includes method bodies, annotations, field initializers), " +
                    "'minimal' (class names + field/method counts only)"
                )
                humanReadable(
                    "Controls how much of the file's content is returned. Three levels: " +
                    "'minimal' = class/function names + counts (cheapest, overview only); " +
                    "'signatures' = all member names with types and modifiers, no bodies (the right default for " +
                    "discovery); 'full' = everything including method bodies, annotations, and initializers " +
                    "(expensive — prefer `get_method_body` to target one method instead)."
                )
                whenPresent(
                    "Mapped to the `DetailLevel` enum: `full` → `DetailLevel.FULL`, " +
                    "`minimal` → `DetailLevel.MINIMAL`, anything else (including invalid values) → " +
                    "`DetailLevel.SIGNATURES`. The provider's `getFileStructure(psiFile, detailLevel)` " +
                    "controls what the formatted output contains."
                )
                whenAbsent("Defaults to `DetailLevel.SIGNATURES` — class/method/field names and types, no bodies.")
                enumValue("signatures", "full", "minimal")
                example("signatures")
                example("minimal")
                example("full")
            }
        }

        verdict {
            keep(
                "Essential discovery tool. Every 'understand this codebase' workflow starts with 'what is in " +
                "this file?' — `file_structure` answers that in a fraction of the tokens that `read_file` " +
                "would cost. On a 1 000-line service class, `file_structure` with `detail=signatures` typically " +
                "returns 80-120 lines covering every public and private member; `read_file` returns all 1 000. " +
                "The 8-10x token saving compounds across every file the LLM explores in a multi-file task. " +
                "This tool earns its schema slot by acting as a gatekeeper that prevents the LLM from blowing " +
                "context on files that turn out to be irrelevant after seeing the outline.",
                VerdictSeverity.STRONG,
            )
        }

        related(
            "read_file",
            Relationship.COMPLEMENT,
            "Use `file_structure` first to get the outline; use `read_file` when you need the full source of " +
            "a section you couldn't get from `get_method_body`. Typical workflow: `file_structure` → spot the " +
            "relevant method → `get_method_body` for its body. `read_file` is the fallback when the file is " +
            "short enough that reading all of it is cheaper than two tool calls."
        )
        related(
            "get_method_body",
            Relationship.COMPLEMENT,
            "After `file_structure` identifies a method by name and signature, `get_method_body` fetches that " +
            "method's body in isolation — giving the LLM exactly what it needs without pulling in sibling methods. " +
            "These two tools form the canonical 'discover then drill' pair for any code-comprehension task."
        )
        related(
            "find_definition",
            Relationship.COMPLEMENT,
            "When you know a symbol name but not the file it lives in, use `find_definition` to get the file path " +
            "and line. Then pass that path to `file_structure` for the full outline, or jump straight to " +
            "`get_method_body`. `find_definition` is 'where is it'; `file_structure` is 'what else is in that file'."
        )
        related(
            "search_code",
            Relationship.FALLBACK,
            "Use when `file_structure` returns raw text (no registered provider for the language), when the file " +
            "is a config/YAML/JSON with no PSI structure, or when you need to grep across multiple files for a " +
            "pattern (search_code is multi-file; file_structure is single-file). search_code loses nesting context " +
            "and returns flat regex hits, so it's strictly worse than file_structure for outline discovery on " +
            "supported languages."
        )
        related(
            "glob_files",
            Relationship.COMPOSE_WITH,
            "Use `glob_files` to enumerate which files exist in a directory, then `file_structure` on each " +
            "candidate to understand its contents. This is the standard 'I need to understand a module' pipeline: " +
            "`glob_files('src/main/kotlin/com/foo/**/*.kt')` → pick the interesting files → `file_structure` on each."
        )

        downside(
            "For files with no registered `LanguageIntelligenceProvider` (JS/TS, Go, Rust, XML, JSON, YAML, " +
            "plain text today), the tool silently falls back to the first 100 raw text lines. The LLM gets no " +
            "signal that the structured outline was skipped — the output looks like a normal read, not an outline. " +
            "The 100-line cap can truncate large config files mid-key, producing incomplete data. The LLM should " +
            "use `read_file` with `limit`/`offset` for config files; it is more reliable and explicit."
        )
        downside(
            "Provider resolution uses `registry.forFile(psiFile)` — a single direct language-ID lookup. " +
            "If the VirtualFile has an unexpected language ID (e.g. the platform reports a Kotlin file as " +
            "language 'kotlin' vs 'Kotlin' depending on plugin state), the lookup returns null and the raw " +
            "100-line fallback fires silently. This is distinct from the hardcoded-JAVA/kotlin fallback bug " +
            "in `find_definition` (line 113) — `file_structure` does NOT hardcode any language ID; it simply " +
            "has no multi-provider iteration for the miss case."
        )
        downside(
            "Requires indexing to be complete — `PsiToolUtils.isDumb(project)` guard at entry returns a hard " +
            "dumb-mode error before the `inSmartMode(project)` deferral can help. The LLM has to retry, not wait. " +
            "A fallback to `read_file` (which doesn't need the index) is appropriate during indexing."
        )
        downside(
            "`detail='full'` can produce output that rivals or exceeds `read_file` in token cost: it includes " +
            "method bodies, field initializers, and all annotations — essentially the full source in a structured " +
            "wrapper. There is no output cap specific to `file_structure`, so a 2 000-line class with `full` " +
            "detail will return ~2 000 lines. Use `get_method_body` to target a single method when body content " +
            "is needed; reserve `full` for short files where whole-file structured output is genuinely useful."
        )
        downside(
            "100-line raw-text fallback for unregistered languages is head-biased: it always returns the first " +
            "100 lines and truncates with a '... (N more lines)' message. For files where the interesting " +
            "declarations appear after line 100 (e.g. large auto-generated files with boilerplate headers), the " +
            "useful content is silently cut. `read_file` with `offset` is the correct tool in these cases."
        )
        downside(
            "Output line count is used as the token estimate (via `TokenEstimator.estimate(content)` after the " +
            "call), not the actual output character count. For files with very long lines (e.g. minified or " +
            "generated code), the token estimate may be significantly lower than actual token consumption."
        )

        observation(
            "VERIFIED: `file_structure` does NOT have the hardcoded `JAVA`/`kotlin` provider-fallback bug " +
            "that affects `find_definition` (line 113 of FindDefinitionTool.kt) and `find_references`. " +
            "Reason: `file_structure` resolves its provider via `registry.forFile(psiFile)` — a file-context " +
            "lookup that uses the language ID of the actual PsiFile. Because it has a PsiFile in hand before " +
            "choosing a provider, it NEVER needs to fall back to a hardcoded language ID. The Python provider " +
            "is correctly selected for `.py` files; the Java/Kotlin provider for `.java`/`.kt` files. The " +
            "no-provider fallback (lines 65-69) is a raw-text fallback for truly unrecognised languages, NOT " +
            "a hardcoded language selection. The bug is ABSENT here — tool works correctly in pure-Python " +
            "projects where no Java plugin is loaded."
        )
        observation(
            "`allowedWorkers` includes ORCHESTRATOR (unlike `find_definition` and `find_references` which are " +
            "ANALYZER+REVIEWER only). This is correct — the orchestrator frequently needs to understand a file's " +
            "structure to route sub-tasks to the right methods, without needing to read the full file."
        )
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

        val rawPath = params["path"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'path' required", "Error: missing path", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val detail = params["detail"]?.jsonPrimitive?.content ?: "signatures"

        val (path, pathError) = PathValidator.resolveAndValidate(rawPath, project.basePath)
        if (pathError != null) return pathError

        val vFile = LocalFileSystem.getInstance().findFileByPath(path!!)
            ?: return ToolResult("Error: File not found: $path", "Error: file not found", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val detailLevel = when (detail) {
            "full" -> DetailLevel.FULL
            "minimal" -> DetailLevel.MINIMAL
            else -> DetailLevel.SIGNATURES
        }

        // Use nonBlocking read action — avoids blocking EDT and write actions
        val content = ReadAction.nonBlocking<String> {
            val psiFile = PsiManager.getInstance(project).findFile(vFile)
                ?: return@nonBlocking "Error: Cannot read file"

            val provider = registry.forFile(psiFile)
            if (provider != null) {
                provider.getFileStructure(psiFile, detailLevel).formatted
            } else {
                // Non-Java/Kotlin: return first 100 lines
                val text = psiFile.text
                val lines = text.lines()
                val shown = lines.take(100).joinToString("\n")
                if (lines.size > 100) "$shown\n... (${lines.size - 100} more lines)" else shown
            }
        }.inSmartMode(project).executeSynchronously()

        return ToolResult(
            content = content,
            summary = "Structure of $rawPath (${content.lines().size} lines)",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }
}
