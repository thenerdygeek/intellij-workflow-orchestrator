# Queued During Phase 5a (core security trio)

Incidental findings discovered while implementing F-3, F-4, F-6.

## I-1: `PreSanitizeDumper` stale bytecode trap (Kotlin default param)

**File**: `core/src/main/kotlin/com/workflow/orchestrator/core/http/PreSanitizeDumper.kt`

**Observation**: Adding a default parameter to `PreSanitizeDumper.dump()` (F-4) triggers
the CLAUDE.md build-cache trap — tests compiled against the old 3-arg signature produce
`NoSuchMethodError` at runtime when Gradle reuses cached test bytecode. Fixed by running
`--rerun-tasks` after the F-4 commit. Document: whenever a Kotlin function in `:core` gains
or loses a default parameter, run the affected module's tests with both `--no-build-cache`
and `--rerun-tasks`.

## I-2: `BitbucketBranchClient.fromConfiguredSettings` creates a fresh `CredentialStore` per call

**File**: `core/src/main/kotlin/com/workflow/orchestrator/core/bitbucket/BitbucketBranchClient.kt:776`

```kotlin
val credentialStore = com.workflow.orchestrator.core.auth.CredentialStore()
```

`CredentialStore` is cheap (wraps `PasswordSafe`) but allocating it per `fromConfiguredSettings()`
call is redundant. Could be extracted to a companion-level singleton. Low priority — `CredentialStore`
holds no state of its own; the cost is just the object allocation. Defer unless profiling
shows it on a hot path.

## I-4: `SonarApiClient.isHttpClientInitialized()` uses reflection on `httpClient$delegate`

**File**: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/api/SonarApiClient.kt`

`isHttpClientInitialized()` accesses the Kotlin lazy-delegate backing field by name
(`"httpClient\$delegate"`) via reflection to avoid calling `close()` on an un-initialized
`OkHttpClient`. This is safe under the current JBR 21 JVM (field name is stable for `by lazy`
at Kotlin 2.1), but is technically fragile if the Kotlin compiler changes the synthetic field
naming convention. A more robust alternative: change `httpClient` from `by lazy` to an
`AtomicReference<OkHttpClient?>` initialized to null, which makes "initialized?" a direct
null check. Deferred — the reflection approach works and the cost is only paid during
`dispose()` and URL-change; not on any hot path.

## I-5: `SonarServiceImpl.client` non-suspend getter returns `cachedApiClient` directly

**File**: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarServiceImpl.kt:57`

After F-4, `getSharedApiClient()` returns `cachedApiClient` (the volatile field) without
going through the mutex-guarded `resolveApiClient()`. This means `SonarServiceImpl` agent
tool calls that arrive before the first dashboard refresh get null and return an error — which
is correct behaviour (fail-fast rather than silent wrong data), but an agent operator using
Sonar tools right after IDE open (no panel visit yet) will see a confusing "client unavailable"
error. Fix: call `resolveApiClientForSharedUse()` (the new suspend variant) from
`SonarServiceImpl`'s suspend methods instead of the property getter. Deferred — the error path
is surfaced and recoverable; not P0.

## I-3: `SourcegraphChatClient.sanitizeForDebug` regex has a subtlety with `"auth"` key

The regex field list includes `"auth"` which matches the literal JSON key `"auth"` but NOT
`"Authorization"` (since regex is exact-key match). The F-4 fix adds `Authorization` explicitly.
However, field names like `"authenticated"` or `"author"` would also match because the regex
only anchors at `"` before and `"` (from the `\s*:\s*"` pattern) after — i.e., `"auth":\s*"`
would match the literal key `auth`, not `author`. Verified: pattern is `"auth"\s*:` — safe.
No action needed; logged for clarity.
