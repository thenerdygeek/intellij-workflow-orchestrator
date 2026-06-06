# ADR 0005: Dependency verification & lockfiles

- **Status:** Accepted
- **Date:** 2026-06-07 (backfilled)
- **Deciders:** Subhankar Halder

## Context

A plugin that runs shell commands and sends code to an LLM must defend its own supply
chain. Unpinned transitive dependencies could be swapped or carry CVEs.

## Decision

Pin dependencies with Gradle `verification-metadata.xml` (SHA-256) plus per-component
lockfiles. Synthetic/platform-specific groups that cannot be pinned per-OS are explicitly
trusted: `idea`, `org.jetbrains.runtime`, `bundledPlugin`, `bundledModule`. Dependabot
(`.github/dependabot.yml`) provides CVE alerts and auto-fixes.

## Consequences

- Tampered or unexpected artifacts fail the build.
- Adding/upgrading a dependency requires regenerating verification metadata.
- Cross-platform CI required adding the trusted synthetic groups (Phase 0 finding).

## Alternatives considered

- No verification: rejected — leaves the supply chain unguarded.
- OWASP dependency-check only: deferred — Dependabot + pinning covers the need today.
