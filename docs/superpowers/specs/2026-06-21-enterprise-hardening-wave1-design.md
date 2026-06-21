# Enterprise Hardening — Wave 1 (design spec)

**Date:** 2026-06-21
**Branch:** `worktree-enterprise-hardening-wave1` (off `origin/main` @ `81c9d1b8a`)
**Scope decision:** Wave 1 — high-value, low-risk, mostly effort-S fixes across all four
enterprise-grade dimensions (security, reliability, supply-chain, audit/observability).
Independent of the in-flight `feature/run-command-auto-approve` PR (#65).

## Origin

A four-agent parallel read-only audit (security, reliability, supply-chain,
audit/observability) of `main`. The codebase is already mature — large areas verified clean
(SSRF defense, TLS, deserialization, JCEF disposal/bridge, EDT discipline, credential storage,
`PathValidator` on write tools, checksum-pinned deps). The remaining gaps cluster into the
tractable set below.

## Out of scope (deferred — recorded, not done in Wave 1)

| Deferred item | Why deferred |
|---|---|
| `find`/`tee` auto-approve guard | Depends on `CommandShape` (only on the auto-approve branch). Belongs on PR #65. |
| Approval-source in audit log (auto vs human) | Depends on `autoApproveReason` (auto-approve branch). Belongs on PR #65. |
| Transactional two-file session persistence | Effort-M, behavior-sensitive; needs its own characterization pass. |
| Per-session checkpoint pruning | Effort-M, behavior-sensitive (revert correctness). |
| PGP `verify-signatures: true` | Generation-heavy, many deps lack PGP sigs → manual `<trust>` churn. |
| Expanded admin-policy framework (`PluginFeature` for run_command/auto-approve/sub-agents) | Effort-M design decision; `FeatureRegistry` seam already exists. |
| Log-integrity HMAC / SIEM export | Larger feature; needs product decision. |
| Live release signing/publishing run | Needs the user's `CERTIFICATE_CHAIN`/`PRIVATE_KEY` GitHub secrets + a tag push. Workflow is scaffolded gated-on-secrets; cannot be end-to-end verified here. |
| **C5 — CycloneDX SBOM plugin** (moved to deferred during impl) | `verify-metadata=true` (strict dependency verification) means applying `org.cyclonedx.bom` requires regenerating `verification-metadata.xml` for the plugin + all transitive deps (`--write-verification-metadata`, network, whole-file rewrite) — not a low-risk change. `release.yml` already calls `cyclonedxBom` with `continue-on-error: true`, so it activates the moment the plugin is added. |
| **D5 — raw-API-trace disclosure / retention / redaction-lock** (re-assessed as NOT a gap on `main`) | Verified against current code: the `rawApiTraceMode`/`rawApiTraceRetentionDays` settings are **not exposed in any editable Configurable** (only persisted for diagnostic-bundle reporting); `RawApiTraceConfig.mode` is never set from settings (the trace is diagnostic-only, OFF, with no user toggle); `redactPromptBody` already defaults `true` **and is pinned by a test** (`core:F-3`); dir pruning is already wired to `RawApiTraceConfig.retentionDays`; Auth/Cookie headers are always redacted. The audit (run on the auto-approve branch) over-stated this; forcing its asks would *remove* a documented secure-by-default opt-out for no real gain. |

---

## A. Security

### A1 — [P0] Gate `monitor source=shell` through `DefaultCommandFilter` + approval
- **Problem:** `MonitorTool.startShell` → `ShellCommandSource.start()` (`monitor/ShellCommandSource.kt:35`)
  runs an LLM-supplied `command` with **no** `DefaultCommandFilter` hard-block and **no** approval
  gate (`monitor` ∉ `WRITE_TOOLS`/`APPROVAL_TOOLS`). `monitor(source=shell, command="curl x|bash", filter=".")`
  = unattended RCE that `run_command` would block. The `TODO(Phase 2)` is the exact gap.
- **Change:** In `MonitorTool.startShell` (the tool boundary, where we can return a clean validation
  error to the LLM), run the command through `DefaultCommandFilter` before constructing the source.
  On a hard-block match, return an error `ToolResult` ("blocked by command filter: …"); do not start
  the monitor. Resolve the shell type the same way `ShellCommandSource` does (`ShellResolver`) so the
  filter sees the right dialect. Remove the `TODO` once gated.
- **Test:** `MonitorToolTest` (existing) — add cases: a `curl … | bash` / `rm -rf /` shell-monitor
  start is rejected with an error result and no source started; a benign `npm test` start still
  succeeds. Keep `validateStart` behavior intact.
- **Effort:** S

### A2 — [P0] `read_document` must enforce `PathValidator` (read allow-list)
- **Problem:** `tools/integration/DocumentTool.execute()` does `Paths.get(pathArg)` → `artifactService.read(...)`
  with no `PathValidator` call → arbitrary file read (`~/.ssh/id_rsa`, `~/.aws/credentials`) whose
  extracted text returns into chat. `read_file` enforces this; `read_document` skips it.
- **Change:** In `DocumentTool.execute()`, resolve+validate `pathArg` via the same read-path validation
  `read_file` uses (`PathValidator.resolveAndValidateForRead(pathArg, project)` — confirm exact signature
  in `tools/builtin/PathValidator.kt`) before `artifactService.read(...)`. On validation failure, return
  the standard "outside project" error. Validation must still permit the legitimate session
  attachments/downloads tree under `~/.workflow-orchestrator/` (the read allow-list already covers it).
- **Test:** Pure/unit coverage of the validation branch — an out-of-tree absolute path and a `../`
  traversal are rejected; an in-project doc path and an in-`agentDir` path pass. Prefer a unit test on
  the validation seam over a `BasePlatformTestCase` (issue #51 indexing-timeout trap — one such class
  already exists; do not add a second).
- **Effort:** S

### A3 — [P1] Session-id path-traversal guard on read/resume/export bridges
- **Problem:** Write bridges validate session ids against `SAFE_SESSION_ID` (`^[a-zA-Z0-9_-]+$`,
  `MessageStateHandler`), but `AgentController.showSession`, `formatSessionAsMarkdown` (export), and
  `AgentService.resumeSession` build `File(sessionBaseDir, "sessions/$sessionId")` from the
  webview-supplied id with no guard → `../`-style cross-tree read/resume.
- **Change:** Add the `SAFE_SESSION_ID` check (reuse the existing constant; centralize a validated
  `sessionDirFor(baseDir, sessionId)` helper if clean) at the top of `showSession`, `resumeSession`
  (controller + `AgentService`), and `formatSessionAsMarkdown`. Reject early with a logged no-op.
- **Test:** Unit test the helper/guard — `../`, absolute, and empty ids rejected; a normal id passes.
- **Effort:** S

### A4 — [P1] API-debug / raw-trace dumps written owner-only (POSIX `rw-------`)
- **Problem:** `SourcegraphChatClient` (api-debug dumps), `RawApiTraceInterceptor`, `PreSanitizeDumper`
  use bare `File.writeText` / `FileOutputStream` → inherit umask (`644`, world-readable). Each dump is
  the full LLM request body (conversation + file contents). The rest of the tree already applies
  `applyOwnerOnlyPerms` (`AtomicFileWriter`). Default OFF, so P1.
- **Change:** Route these writers through `AtomicFileWriter`, or call the existing
  `applyOwnerOnlyPerms(...)` on each created file + its parent dir after `mkdirs()`. POSIX-only; on
  Windows it is a documented no-op (consistent with the rest of the codebase).
- **Test:** On a POSIX test host, assert the created dump file's permissions are owner-only; or, if
  cross-platform-flaky, a focused unit asserting the perms helper is invoked on the dump path.
- **Effort:** S

---

## B. Reliability

### B1 — [P1] Make `AutoWakeGuardState.decide()` atomic
- **Problem:** `tools/background/AutoWakeGuardState.kt:44-55` — KDoc claims "atomic", but the cap-read,
  cooldown-read, increment, and timestamp-set are separate ops on per-key atomics. Consulted from three
  concurrent paths (background completion, delegation delivery, monitor flush) → two events for the same
  idle session within the cooldown window can both return `PROCEED` → double-resume (`prepareForReplay`
  cancels the live job + resets chat twice — the double-delivery class already seen historically).
- **Change:** Make the decide body atomic per session — guard the cap/cooldown/increment/set sequence
  with a per-session lock (`ConcurrentHashMap<String, Any>` monitor or a striped `synchronized` on a
  per-session token). Keep the `now`-injection seam for deterministic tests. Behavior for the
  single-threaded path is unchanged.
- **Test:** Extend `AutoWakeGuardStateTest` — a concurrency case where N threads call `decide()` for the
  same session inside the cooldown window and exactly one `PROCEED` is observed.
- **Effort:** S

### B2 — [P1] Free `sessionQueues` map entry on session end
- **Problem:** `AgentService.sessionQueues.getOrPut(...)` grows one entry per distinct session id for the
  IDE-window lifetime; `releaseSessionState(sessionId)` removes `perSessionStates` but not `sessionQueues`.
- **Change:** Add `sessionQueues.remove(sessionId)` to `releaseSessionState` (the existing session-end hook).
- **Test:** Behavioral/source pin that `releaseSessionState` removes the queue entry.
- **Effort:** S

### B3 — [P1] Call `autoWakeGuards.resetSession(sessionId)` on session end (wire the dead API)
- **Problem:** `AutoWakeGuardState.reset()/resetSession()` exist but are never called → two CHM entries
  leak per session that ever triggered an idle-wake.
- **Change:** Call `autoWakeGuards.resetSession(sessionId)` from `releaseSessionState` (same site as B2).
- **Test:** Behavioral/source pin alongside B2.
- **Effort:** S

### B4 — [P1] Add logger + decode guard to `BackgroundPersistence` & `QueuePersistence`
- **Problem:** `tools/background/BackgroundPersistence.loadPendingCompletions` decodes with no try/catch
  and the class has no `Logger` → a corrupt file throws out of both append + consume, self-wedging
  silently. `loop/queue/QueuePersistence.load()` uses `runCatching{…}.getOrDefault(emptyList())` with **no
  log** → a corrupt `pending_queue.json` silently drops all durable queued work.
- **Change:** Wrap the `BackgroundPersistence` decode in `runCatching{…}.getOrElse { log.warn(...); emptyList() }`
  and add a `Logger`; add `.onFailure { log.warn(...) }` to `QueuePersistence.load()`. Match the sibling
  `MonitorPersistence` pattern.
- **Test:** Feed each loader a corrupt JSON file (`@TempDir`) → returns empty list, does not throw.
- **Effort:** S

### B5 — [P1] `EditFileTool` write tiers must rethrow `CancellationException` (+ log cause)
- **Problem:** `tools/builtin/EditFileTool` `writeViaDocument`/`writeViaVfs`/`writeViaFileIo` end
  `catch (_: Exception) { false }`. The first two are `suspend`; `CancellationException : Exception` →
  cooperative cancel is swallowed and converted to a benign-looking `false` (breaks structured
  concurrency); real failure causes are discarded with no log.
- **Change:** Add `catch (e: CancellationException) { throw e }` before the broad catch in each tier;
  log the swallowed cause at `warn`. Preserve the self-healing `writeViaDocument || writeViaVfs ||
  writeViaFileIo` chain semantics (the data-loss-#3 fix must remain intact — `writeViaDocument` still
  returns `false` on a no-op).
- **Test:** Unit — a tier that throws `CancellationException` propagates it (not `false`); a genuine
  `IOException` still yields `false` and logs. Guard the data-loss-#3 invariant stays green
  (`EditFilePersistenceFixtureTest` untouched).
- **Effort:** S

### B6 — [P1] `ModelCache` cross-thread fields `@Volatile`
- **Problem:** `core/ai/ModelCache` `models` / `lastFetchMs` read on EDT (`populateFromExternal`,
  settings) and IO (`getCached`, `fetchModels`) without volatile/lock on the bypassing accessors →
  no happens-before; stale reads / inconsistent `(models, lastFetchMs)` pair.
- **Change:** Mark both fields `@Volatile` (cheapest correct fix; reference writes are atomic), or route
  the three bypassing accessors through the existing `lock`. Prefer `@Volatile`.
- **Test:** Minimal — existing `ModelCache`/catalog tests stay green; no behavior change.
- **Effort:** S

---

## C. Supply chain & release (CI / build)

### C1 — [P0] Pin all GitHub Actions to commit SHAs
- **Problem:** `.github/workflows/ci.yml` pins every `uses:` to a floating major tag (`@v6`, `@v5`) →
  `tj-actions`-style supply-chain injection vector.
- **Change:** Replace each `uses: org/action@vN` with `uses: org/action@<full-sha> # vN`. Dependabot's
  `github-actions` ecosystem (already configured) keeps SHAs current.
- **Test:** CI must stay green (the pinned SHAs resolve to the same action versions). Verify YAML parses.
- **Effort:** S

### C2 — [P0] Validate the Gradle wrapper JAR in CI
- **Problem:** `gradle/wrapper/gradle-wrapper.jar` is a committed binary; no
  `gradle/actions/wrapper-validation` step → a swapped JAR runs arbitrary code on every `./gradlew`.
- **Change:** Add `gradle/actions/wrapper-validation@<sha>` as the **first** step of each job (or a shared
  setup), before any `./gradlew` invocation.
- **Test:** CI green; the step passes against the current (legitimate) wrapper JAR.
- **Effort:** S

### C3 — [P1] Explicit least-privilege `permissions:` per job
- **Problem:** Only a top-level `permissions: contents: read`. Future jobs could silently inherit broad
  perms.
- **Change:** Add an explicit minimal `permissions:` block to each job (`contents: read`, plus
  `id-token: write` only where attestation needs it in the release workflow).
- **Test:** CI green.
- **Effort:** S

### C4 — [P1] Commit a webview npm lockfile + add the npm Dependabot ecosystem
- **Problem:** `agent/webview/package.json` uses semver ranges with **no** `package-lock.json` →
  non-deterministic webview builds; Dependabot can't scan npm. `.github/dependabot.yml` has no `npm` entry.
- **Change:** Generate + commit `agent/webview/package-lock.json` (`npm install --package-lock-only` to
  avoid pulling `node_modules`). Add an `npm` ecosystem entry for `/agent/webview` to `dependabot.yml`.
  Verify the existing webview build/CI still uses the lockfile (`npm ci` where applicable).
- **Test:** `npm ci` resolves in `agent/webview`; webview vitest build unaffected.
- **Effort:** S

### C5 — [P1] Generate an SBOM (CycloneDX)
- **Problem:** No SBOM → fails most OSS procurement checklists (EO 14028 / EU CRA).
- **Change:** Apply the `org.cyclonedx.bom` Gradle plugin on the root project; produce `bom.json`. Wire it
  into the release workflow (C6) to attach as a release asset. Pin the plugin version; add its
  coordinates to `verification-metadata.xml` if dependency verification requires it.
- **Test:** `./gradlew cyclonedxBom` produces a valid `bom.json` locally.
- **Effort:** S–M (plugin add is S; verification-metadata reconciliation may add a little)

### C6 — [P1] Tag-triggered release workflow (signed + attested), gated on secrets
- **Problem:** Releases are fully manual (`gh release create`), unsigned, unattested. The Gradle
  `signing {}` block exists but no CI job calls `signPlugin`.
- **Change:** Add `.github/workflows/release.yml` on `push: tags: ['v*']`:
  checkout → wrapper-validation → setup-java/gradle (SHA-pinned) → `signPlugin` (secrets injected, **step
  skipped when secrets absent** so non-release runs/forks don't fail) → `buildPlugin` →
  `cyclonedxBom` → `actions/attest-build-provenance@<sha>` (job `permissions: id-token: write`) →
  `softprops/action-gh-release@<sha>` attaching the signed ZIP + `bom.json`. Document required secrets
  (`CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`, `PUBLISH_TOKEN`) in the workflow header.
- **Test:** Cannot end-to-end run without the user's secrets + a tag push. Verify: YAML parses; the
  signing step's `if:` correctly skips when secrets are empty; a dry `buildPlugin` succeeds. Flag the
  live-run gap to the user explicitly.
- **Effort:** M

### C7 — [P2] Reproducible-build flags on Jar/Zip tasks
- **Change:** In root `build.gradle.kts`, `tasks.withType<Jar>/<Zip>().configureEach {
  isPreserveFileTimestamps = false; isReproducibleFileOrder = true }`.
- **Test:** `buildPlugin` succeeds; two builds of an unchanged tree yield byte-identical archives (spot-check).
- **Effort:** S

### C8 — [P2] Pin the Gradle distribution SHA-256
- **Change:** Add `distributionSha256Sum=<sha256 of gradle-9.4.0-bin.zip>` to
  `gradle/wrapper/gradle-wrapper.properties` (value from gradle.org/release-checksums).
- **Test:** `./gradlew --version` succeeds (checksum verified before unpack).
- **Effort:** S

---

## D. Audit, observability & policy

### D1 — [P0] Wire `retentionDays` into `AgentFileLogger.cleanOldLogs`
- **Problem:** `TelemetryConfigurable` exposes a "Log retention (days)" field bound to
  `settings.state.retentionDays` (default 7), but `AgentFileLogger.cleanOldLogs` hardcodes
  `minusDays(7)` and never reads the setting → the field is dead.
- **Change:** Read `retentionDays` from `PluginSettings.state` and pass it into `AgentFileLogger` (ctor
  param or provider) so `cleanOldLogs` uses it. Keep 7 as the default. Clamp to a sane floor (≥1).
- **Test:** Unit — `cleanOldLogs` deletes files older than the configured N (parameterize N).
- **Effort:** S

### D2 — [P0] Resolve the dead `diagnosticJsonlEnabled` toggle (decision below)
- **Problem:** The "Enable diagnostic JSONL logging" checkbox (default true) is never read; the logger is
  always-on and its ctor comment says "no toggle to disable" — the UI contradicts the code.
- **Decision (recommend, confirm at review):** **Keep audit logging always-on** (a user-disableable audit
  trail is weak governance) but make the checkbox honest — relabel it to scope only the *verbose
  diagnostic* detail, OR remove it and document "audit logging is always-on by design" in
  `TelemetryConfigurable` + `THREAT_MODEL.md`. Default plan: **remove the misleading checkbox + document
  always-on**; the security value of a non-disableable trail outweighs the toggle.
- **Test:** Settings page renders without the dead control; `TelemetryConfigurableTest` (if present) updated.
- **Effort:** S

### D3 — [P0] Record the task in `session_start`
- **Problem:** `AgentFileLogger.logSessionStart(task, toolCount)` `@Suppress`-es both params and logs
  neither → the audit trail can't say what a session was asked to do without reading the conversation file.
- **Change:** Add `taskSummary: String?` to `LogEntry`; write `task.take(N)` (e.g. 100 chars) in
  `logSessionStart`. Drop the now-unnecessary `@Suppress`.
- **Test:** Unit — `logSessionStart` emits a `session_start` entry carrying the truncated task summary.
- **Effort:** S

### D4 — [P1] Wire `includeCommandOutputInLogs`
- **Problem:** The "Include run_command output in logs" checkbox (default false) is never read; command
  output never reaches the log regardless of the toggle.
- **Change:** At the `logToolCall` site, when `toolName == "run_command"` and the setting is true, include
  a bounded slice of `toolResult.content` (e.g. `take(2000)`) in a new `output` field on `LogEntry`.
  Default false → byte-identical to today.
- **Test:** Unit — with the flag on, a `run_command` tool_call entry carries the bounded output; with it
  off, it does not.
- **Effort:** S

### D5 — [P1] Disclose active API tracing + wire its retention
- **Problem:** `rawApiTraceMode != OFF` writes full LLM request bodies (source code) to disk with no
  in-product disclosure; `RawApiTraceConfig.redactPromptBody` is a runtime-mutable bypass; the raw-trace
  `retentionDays` UI field is not wired.
- **Change (minimal-viable):** (a) Fire a one-time IDE notification when `rawApiTraceMode` is switched to
  a non-OFF mode: "API tracing active — LLM request/response data including source code is written to
  disk at <path>." (b) Wire `rawApiTraceRetentionDays` from settings into `RawApiTraceConfig`. (c) Make
  `redactPromptBody` default-true and not user-bypassable from the settings surface (keep the field, drop
  any UI/public setter that flips it off). Skip the persistent banner (UI-heavy) for Wave 1.
- **Test:** Unit — retention wiring uses the configured value; redaction default is true. Notification
  firing is covered by a light source/behavioral pin.
- **Effort:** S

---

## Execution plan

- **Module grouping:** A1–A2, B1–B5, D1–D4 land in `:agent`; A4, B6, D5 (+ parts of D1/D2) touch `:core`;
  C1–C8 are CI/build config (no module tests). Group commits per dimension for a reviewable history.
- **TDD per fix:** red test → minimal change → green. Establish each module's baseline via the first
  red→green cycle (avoids a slow full-suite preflight in a fresh worktree).
- **Verification before completion:** `./gradlew :agent:test` and `:core:test` (with `--no-build-cache`
  on any data-class/ctor signature change — the documented stale-cache `NoSuchMethodError` trap); detekt
  green per touched module; `verifyPlugin`; for CI YAML, validate parse + (where possible) `act`/dry
  reasoning. The release-workflow live run is explicitly out of reach (needs secrets + tag).
- **Independence:** every fix is additive/defensive and independently revertible; no cross-fix coupling.

## Risks & notes

- detekt `ImportOrdering:AgentService.kt` baseline keys on the whole import block — any AgentService
  import change needs a surgical baseline update (recurring trap).
- Source-text "sentinel slice" contract tests: place any new functions OUTSIDE sliced ranges; run the
  FULL module test, not just `--tests`.
- `--no-build-cache` whenever a lambda/data-class/ctor signature changes (suspend-type traps).
- D2 and the C6 live-run carry small decisions/limits for the user — surfaced above.
