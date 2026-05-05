package com.workflow.orchestrator.agent.tools.process

/**
 * Classifies a `run_command` invocation by the kind of disk mutation it is likely to cause.
 *
 * Used by [com.workflow.orchestrator.agent.tools.builtin.RunCommandTool] to decide:
 *  - how broad a VFS refresh is needed after the command exits (working-dir vs whole-project)
 *  - whether to drop JPS's incremental-build state (build cleans only)
 *  - whether to prepend a `[VFS NOTE]` to the tool result so the LLM re-reads cached files
 *    that may have been invalidated (git mutators only — they can roll back file content)
 *
 * Classification is best-effort and pattern-based. False-positives waste a refresh; false-
 * negatives fall back to the Layer A working-dir refresh which is usually enough.
 */
object CommandMutationClassifier {

    sealed interface Mutation {
        /** `git checkout|stash|reset|apply|rebase|merge|pull|cherry-pick|revert|clean` — content-altering */
        data object GitMutator : Mutation

        /** `mvn clean`, `gradle clean`, `gradlew clean`, `npm ci` — build outputs destroyed */
        data object BuildClean : Mutation

        /** `mvn …`, `gradle …`, `cargo build`, `make` — incremental build, no clean */
        data object BuildIncremental : Mutation

        /** `npm install`, `pnpm install`, `yarn`, `bun install` — dependency tree may have changed */
        data object PackageInstall : Mutation

        /** `sed -i`, `mv`, `rm`, `cp`, `chmod`, `find … -delete` — direct filesystem mutation */
        data object FsMutator : Mutation

        /** Anything else — assumed read-only or scoped enough that working-dir refresh suffices. */
        data object Generic : Mutation
    }

    private val GIT_MUTATOR_REGEX = Regex(
        """\bgit\s+(?:checkout|switch|stash|reset|apply|rebase|merge|pull|cherry-pick|revert|clean|restore)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val BUILD_CLEAN_REGEX = Regex(
        """\b(?:mvn(?:w)?|\.?/?gradlew?|cargo|sbt)\s+(?:[^&;|]*\s+)?clean\b|\bnpm\s+ci\b""",
        RegexOption.IGNORE_CASE,
    )

    private val BUILD_INCREMENTAL_REGEX = Regex(
        """\b(?:mvn(?:w)?|\.?/?gradlew?|cargo|sbt|make|bazel)\s+(?:build|compile|package|install|test|verify)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val PACKAGE_INSTALL_REGEX = Regex(
        """\b(?:npm|pnpm|yarn|bun)\s+(?:install|i|add)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val FS_MUTATOR_REGEX = Regex(
        """\b(?:sed\s+-i|mv\s+|rm\s+|cp\s+|chmod\s+|find\s+.*-delete\b)""",
        RegexOption.IGNORE_CASE,
    )

    fun classify(command: String): Mutation {
        // Order matters: BuildClean must beat BuildIncremental (a `mvn clean install` matches both).
        return when {
            GIT_MUTATOR_REGEX.containsMatchIn(command) -> Mutation.GitMutator
            BUILD_CLEAN_REGEX.containsMatchIn(command) -> Mutation.BuildClean
            PACKAGE_INSTALL_REGEX.containsMatchIn(command) -> Mutation.PackageInstall
            BUILD_INCREMENTAL_REGEX.containsMatchIn(command) -> Mutation.BuildIncremental
            FS_MUTATOR_REGEX.containsMatchIn(command) -> Mutation.FsMutator
            else -> Mutation.Generic
        }
    }
}
