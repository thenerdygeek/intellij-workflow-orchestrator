package com.workflow.orchestrator.core.api

/**
 * Marks a type or member as part of the **stable, fork-facing API surface** (see `docs/STABLE-API.md`
 * and `FORKING.md`). Company forks may depend on annotated symbols; the base maintainer avoids
 * source/binary-incompatible changes to them without a deprecation cycle.
 *
 * @property since the plugin version family in which the symbol became stable.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
annotation class StableApi(val since: String)

/**
 * Marks a type or member as **internal** — not part of the fork-facing surface. It may change or be
 * removed without notice; forks must not build on it. The absence of [StableApi] already implies
 * internal, but this annotation makes the intent explicit on symbols that are public for technical
 * reasons yet not meant for forks.
 *
 * `@Retention(RUNTIME)` so that reflection-based contract tests (e.g. SeamApiStabilityTest) and
 * any plugin-B tooling can detect the annotation at runtime via `Class.isAnnotationPresent`.
 * (Kotlin `BINARY` = Java `CLASS` = stripped at load time and invisible to reflection.)
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class InternalApi
