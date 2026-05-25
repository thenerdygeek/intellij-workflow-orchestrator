# Incidentals queued during Phase 5c

## jira:F-6 scope cast

`project as Disposable` in `TimeTrackingCheckinHandler.checkinSuccessful()` and
`PostCommitTransitionHandler.checkinSuccessful()` works because IntelliJ `ProjectImpl`
implements `Disposable`, but the cast is technically unchecked. A cleaner approach is
to use a project-level service that exposes its coroutine scope, or adopt `cs: CoroutineScope`
injection when the module migrates to the Phase 4 service-injected-scope pattern.
Low risk for now; noted for the next threading sweep.

## jira:F-1 URI parsing

`getRawString` wraps `URI(url)` in `runCatching` — if the URL is malformed the guard
returns `FORBIDDEN` rather than `VALIDATION_ERROR`. Callers that feed server-supplied
`autoCompleteUrl` fields directly may benefit from an explicit URL validation step
upstream. Defer to the next JiraApiClient audit.

## bamboo:F-5 constant visibility

`LOG_SLICE_CHARS` and `ERROR_BODY_MAX_CHARS` are package-private top-level constants.
If tests in other packages need them, promote to `internal const val`. Currently only
used internally, so OK.

## verifyPlugin Q8 — EXPERIMENTAL_API_USAGES not in failureLevel (Phase 5d)

`runBlockingCancellable` (30+ sites across core/jira/sonar/agent) and `writeAction` (4 sites
in EditFileTool/CreateFileTool/DeleteFileTool) are marked `@Experimental` by the platform.
`@Suppress("UnstableApiUsage")` on the call-site functions silences IDE/Kotlin-compiler
warnings but NOT the plugin verifier — the verifier reads bytecode and ignores Kotlin
suppress annotations.

To enable `EXPERIMENTAL_API_USAGES` in `failureLevel` cleanly, an `ignoredProblemsFile`
listing all 34 accepted violations is needed. Defer to a dedicated pass that:
1. Generates the ignoredProblemsFile from the current verifier output
2. Adds `EXPERIMENTAL_API_USAGES` to failureLevel alongside the file
3. Plans migration of `runBlockingCancellable` to non-experimental `runBlockingCancellable`
   equivalents when IntelliJ Graduate it (or documents the accepted risk per site).

## verifyPlugin unresolved violations (Internal API) — Phase 5d

17 `@Internal` violations remain in the verifier output (not in failureLevel so non-failing).
Key ones: `ExecutionEnvironment.setCallback` (5 sites), `TestStateInfo.Magnitude` enum
(4 sites), `XBreakpointManagerImpl` (4 sites), `OasSerializationUtilsKt.generateOasDraft`,
`Module.getModuleFile`. These require design decisions (public API replacements or accepted
internal usage with `@Suppress`) — deferred.

## RESOLUTION (2026-05-25, Tier-D incidentals pass)

- **verifyPlugin Q8 — EXPERIMENTAL_API_USAGES gate** — WONTFIX BY DESIGN (not deferred). Evaluated
  and rejected: the experimental surface is self-resolving (IU-251 reports 34 usages, IU-252/253
  only 4 — runBlockingCancellable already graduated upstream), and the required ignoredProblems
  entries embed auto-generated lambda names + the plugin version, so an exact-match ignore file
  would go stale on any refactor/version bump and start failing the build on already-accepted code
  (net-negative). The exclusion is now documented as a decision in build.gradle.kts. These usages
  still surface as warnings in build/reports/pluginVerifier.
- **verifyPlugin @Internal violations (17)** — left as documented warnings (not in failureLevel);
  each needs a per-site platform-API design decision, out of scope for an incidentals pass.
- **jira:F-6 / jira:F-1 / bamboo:F-5** — left for their respective next-audit/threading sweeps as
  noted (low-risk, no behavior defect).
