# Changelog

All notable changes to the Workflow Orchestrator plugin are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Governance documentation: `SECURITY.md`, `THREAT_MODEL.md`, `FORKING.md`,
  `CONTRIBUTING.md`, `CODEOWNERS`, and an `docs/adr/` Architecture Decision Record
  framework with backfilled records (roadmap Phase 1).
- Enforcement foundation (roadmap Phase 0): GitHub Actions CI (build, test, detekt lint,
  Konsist architecture tests), per-module detekt baselines, and Dependabot CVE scanning.
- The `security-auditor` and `performance-engineer` specialist agents are now offered only on Spring projects (Java + the IntelliJ Spring plugin); they remain spawnable explicitly by name on any project.

### Fixed

- Corrected `pluginRepositoryUrl` placeholder so changelog compare-links resolve.

## [0.86.0-phase3.1] - 2026-06-07

First release containing the Phase 3 `AgentService` decomposition (roadmap Phase 3, batch 1).
These are pure structural refactors — no behavior changes intended — extracting four clusters
out of the 4.2K-line `AgentService` into their own unit-testable units.

### Changed

- Extracted `AgentMonitorCoordinator` — the per-session monitor cluster (managers map,
  persistence, re-arm, bridge wiring) is now unit-instantiable; `AgentService` keeps thin
  same-named delegators.
- Extracted `BrainFactory` — per-task brain construction and model-selection precedence, with
  the precedence carved into a pure `resolveModel(...)` (caller override > saved model >
  auto-pick > fallback).
- Extracted `NetworkRecoveryPolicy` — first pure incision out of `executeTask`: the L2
  tier-escalation gating decision.
- Extracted `BackgroundCompletionCoordinator` — background-process completion routing and
  synthetic-message builders; the shared auto-wake substrate stays on `AgentService` and is
  injected.

### Fixed

- De-flaked `SessionDocumentArtifactServiceTest` — injected dispatcher plus a deterministic
  test scheduler; removes intermittent CI failures under constrained runners.

[Unreleased]: https://github.com/thenerdygeek/intellij-workflow-orchestrator/compare/v0.86.0-phase3.1...HEAD
[0.86.0-phase3.1]: https://github.com/thenerdygeek/intellij-workflow-orchestrator/compare/v0.86.0-token-ctx.8...v0.86.0-phase3.1
