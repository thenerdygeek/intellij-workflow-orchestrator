# Dependency Locking & Verification Metadata

**Phase 6 T6 — supply-chain hardening for the Gradle build.** Two complementary controls
protect the build from version drift and tampered downloads.

## What is dependency locking?

`dependencyLocking { lockAllConfigurations() }` (applied via `allprojects { … }` in the
root `build.gradle.kts`) makes Gradle write a deterministic record of every transitive
resolution into `<module>/gradle.lockfile`. On every subsequent build, Gradle refuses to
proceed if a configuration would resolve to a different set of versions than the
lockfile records.

**Why:** without locking, a Maven Central re-publish or a transitive dynamic version
(`1.2.+`) can silently introduce a different artifact set on the next CI build. With
locking, that becomes a hard build failure that requires an explicit lockfile refresh
(and code-review) to land.

Locked configurations include `compileClasspath`, `runtimeClasspath`,
`testCompileClasspath`, `testRuntimeClasspath`, plus the IntelliJ Platform-specific
configurations (`intellijPlatformDependency`, `intellijPlatformPluginVerifierIdes`,
etc.) — every configuration in every module.

## What is verification-metadata?

`gradle/verification-metadata.xml` records a SHA-256 of every artifact byte stream
Gradle downloads (jars, poms, modules). Gradle re-computes the hash on each download
and refuses to use an artifact whose hash doesn't match. This catches a tampered
mirror, a Maven Central account-takeover republish, or a corrupted CDN response at
download time — before the bytes ever reach the compiler.

The current baseline contains ~1100 SHA-256 entries across ~456 components.
`<verify-metadata>true</verify-metadata>` and `<verify-signatures>false</verify-signatures>`
in the configuration block — Gradle's defaults; we don't enforce PGP signatures yet
because Maven Central signing is uneven across our dependency surface.

## Lockfile-update protocol

When you bump a version in `gradle/libs.versions.toml` (or add a new dep, or the
IntelliJ Platform plugin pulls a new transitive), follow this sequence in one go:

```bash
# 1. Edit gradle/libs.versions.toml (or change a build.gradle.kts dependency)

# 2. Refresh lockfiles for every subproject + the root.
./gradlew :core:dependencies :jira:dependencies :bamboo:dependencies \
          :sonar:dependencies :pullrequest:dependencies :automation:dependencies \
          :handover:dependencies :agent:dependencies :mock-server:dependencies \
          dependencies --write-locks

# 3. Refresh verification-metadata against the full task graph.
#    --refresh-dependencies forces fresh download so the recorded SHA matches
#    bytes-on-the-wire, not just whatever's in your local cache.
./gradlew --write-verification-metadata sha256 --refresh-dependencies \
          verifyPlugin buildPlugin

# 4. Re-verify the clean build still passes end-to-end.
./gradlew clean verifyPlugin buildPlugin --refresh-dependencies

# 5. Commit `libs.versions.toml` + every changed `gradle.lockfile` +
#    `gradle/verification-metadata.xml` in the SAME commit as the version bump.
#    Splitting them across commits leaves the build broken at the boundary.
```

## `--refresh-dependencies` semantics

`--refresh-dependencies` tells Gradle to ignore the local artifact cache and re-fetch
every dependency from the network. Combined with locking + verification-metadata, this
is the gold-standard pre-merge check:

- forces a fresh resolution → catches lockfile drift,
- forces a fresh download → catches verification-metadata drift,
- runs the full build → catches downstream incompatibility.

If `./gradlew clean verifyPlugin buildPlugin --refresh-dependencies` is green on a
laptop with `~/.gradle/caches/` cleared, the lockfiles + metadata are self-consistent.
This is what to run after any version bump, before pushing.

## npm side

`agent/webview/package-lock.json` is the npm equivalent of a Gradle lockfile.
`agent/build.gradle.kts:npmInstallWebview` already uses `npm ci` (lockfile-strict, fails
on any version mismatch) — never `npm install` (which can mutate the lockfile silently).

When bumping a JS dependency in `agent/webview/package.json`:

```bash
cd agent/webview
npm install            # locally — updates package-lock.json
git add package.json package-lock.json
```

CI / fresh-checkout builds always go through `npm ci`, so the lockfile is the source of
truth.

## Operator notes

- **Don't hand-edit lockfiles or verification-metadata.** Both are generated.
- **Always pair `--write-*` with the full verify in step 4** — partial writes leave a
  broken commit boundary.
- **CI gating.** `./gradlew clean verifyPlugin buildPlugin` is the per-PR gate; the
  `--refresh-dependencies` variant is the pre-merge / nightly variant.
