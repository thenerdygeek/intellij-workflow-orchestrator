package com.workflow.orchestrator.core.invariant

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Source-text invariant: no UI/action file should read repo-scoped settings from the scalar
 * `PluginSettings.state.<key>` fields without an accompanying `RepoConfig` fallback.
 *
 * The plugin supports multi-repo / multi-module projects via `PluginSettings.getRepos()`.
 * Each `RepoConfig` carries its own `bitbucketProjectKey`, `bitbucketRepoSlug`, `bambooPlanKey`,
 * `sonarProjectKey`, `dockerTagKey`, `localVcsRootPath`, etc. The scalar fields on
 * `PluginSettings.state` are project-default fallbacks — reading them directly inside a UI
 * action handler loses any per-repo selection the user made, producing "action ran against
 * the wrong module" bugs. v0.83.19–22 fixed a cluster of these (commit-msg, Start Work local
 * checkout, PR Open-in-Browser, PR AI Review tab, PR Comments tab, Build Refresh/Trigger/Rerun/
 * Manual Stage, Bamboo PrBar inline submit, Automation docker tag, Sonar line coverage).
 *
 * This test greps the repository for reads of those scalar fields in files matching
 * UI-action filename patterns and fails if any occur outside an allowlist of initialization /
 * fallback contexts. It's a structural guard — it doesn't prove correctness, but it forces
 * anyone adding a new such read to update the allowlist or the code, which in practice means
 * writing a justification the reviewer will see.
 */
class MultiRepoScopeInvariantTest {

    private val projectRoot = findProjectRoot()

    /** Filename patterns that must never read scalar repo-scoped settings directly. */
    private val uiActionFilenames = Regex(".*(Panel|Action|Handler|Button|Dashboard|Dialog)\\.kt$")

    /** Settings fields that are per-repo in `RepoConfig` and should not be read as scalars. */
    private val scalarScopedFields = listOf(
        "bitbucketProjectKey",
        "bitbucketRepoSlug",
        "bambooPlanKey",
        "sonarProjectKey",
        "dockerTagKey",
    )

    /**
     * Paths where scalar reads are legitimate — generally:
     *  - Settings UI (the scalar field IS the edited value)
     *  - Fallback-only callsites that already consult RepoConfig first (e.g. `?: settings.state.x`)
     *    which this pattern-level test can't distinguish from a standalone scalar read
     *  - Legacy inline-form paths explicitly flagged with a TODO
     *
     * Entries are substrings matched against the file's absolute path.
     */
    private val allowlistPathFragments = listOf(
        // Settings configurables — legitimate scalar edits
        "/settings/BuildsAndHealthChecksConfigurable.kt",
        "/settings/CodeQualityConfigurable.kt",
        "/settings/RepositoriesConfigurable.kt",
        "/settings/ConnectionsConfigurable.kt",
        "/settings/AutomationConfigurable.kt",
        // This test file itself
        "/invariant/MultiRepoScopeInvariantTest.kt",
    )

    @Test
    fun `no UI or action file reads scalar repo-scoped settings without a RepoConfig fallback`() {
        val offenders = mutableListOf<String>()

        projectRoot.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .filter { it.path.contains("/src/main/") }
            .filter { !it.path.contains("/build/") && !it.path.contains("/.worktrees/") }
            .filter { uiActionFilenames.matches(it.name) }
            .filter { file -> allowlistPathFragments.none { file.path.contains(it) } }
            .forEach { file ->
                val lines = file.readLines()
                lines.forEachIndexed { idx, line ->
                    for (field in scalarScopedFields) {
                        val needle = "settings.state.$field"
                        val alt = "settings.$field"
                        val hit = line.contains(needle) || line.contains(alt)
                        if (!hit) continue
                        // Allow the scalar read if it's the RHS of an Elvis operator (the
                        // previous term already resolved a per-repo value and fell through).
                        // Checking just this line's leading `?:` would miss multi-line
                        // expressions, so also accept a `?:` on the immediately-prior line.
                        val hasElvisOnThisLine = line.trimStart().startsWith("?:")
                        val prevTrimmed = if (idx > 0) lines[idx - 1].trimEnd() else ""
                        val hasElvisOnPrevLine = prevTrimmed.endsWith("?:")
                        // Also allow inside a block that assigns to a `val` whose preceding
                        // lines compute a per-repo value first — approximated by checking the
                        // 5 preceding lines for a reference to `RepoConfig`, `repoConfig`,
                        // `currentPr`, or a per-repo field name.
                        val contextStart = (idx - 5).coerceAtLeast(0)
                        val context = lines.subList(contextStart, idx).joinToString("\n")
                        val hasPerRepoContext = context.contains("RepoConfig") ||
                            context.contains("repoConfig") ||
                            context.contains("currentPr") ||
                            context.contains("selectedRepo") ||
                            context.contains("resolvedRepoConfig") ||
                            context.contains("activePlanKey") ||
                            context.contains("currentPlanKey()")
                        if (hasElvisOnThisLine || hasElvisOnPrevLine || hasPerRepoContext) continue
                        offenders.add("${file.path}:${idx + 1}: $line")
                    }
                }
            }

        assertTrue(
            offenders.isEmpty(),
            buildString {
                appendLine("Found ${offenders.size} UI/action file(s) that read scalar repo-scoped")
                appendLine("settings without a per-repo fallback. If this is intentional, either:")
                appendLine(" 1. Read from the selection's RepoConfig first with the scalar as Elvis fallback:")
                appendLine("      selectedRepo?.bambooPlanKey ?: settings.state.bambooPlanKey.orEmpty()")
                appendLine(" 2. Add the file to `allowlistPathFragments` with a justification.")
                appendLine()
                appendLine("Offending reads:")
                offenders.forEach { appendLine("  $it") }
            }
        )
    }

    private fun findProjectRoot(): File {
        // Tests run with cwd == the module's directory (e.g. core/). Walk up until we see
        // both `gradle.properties` and `settings.gradle.kts` — the project root markers.
        var dir = File(".").canonicalFile
        while (dir.parentFile != null) {
            if (File(dir, "gradle.properties").exists() && File(dir, "settings.gradle.kts").exists()) {
                return dir
            }
            dir = dir.parentFile
        }
        error("Could not locate project root from ${File(".").canonicalPath}")
    }
}
