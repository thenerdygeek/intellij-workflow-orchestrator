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

### Fixed

- Corrected `pluginRepositoryUrl` placeholder so changelog compare-links resolve.
