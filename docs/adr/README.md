# Architecture Decision Records

This directory captures significant, hard-to-reverse architectural decisions. Each ADR is
immutable once accepted; to change a decision, add a new ADR that supersedes the old one
(note it in both).

New decision? Copy [0000-template.md](0000-template.md), give it the next number, and submit
it in the same PR as the change it describes.

| ADR | Title | Status |
|---|---|---|
| [0001](0001-module-layering.md) | Module layering & feature-modules-depend-on-core-only | Accepted |
| [0002](0002-public-core-per-company-fork-model.md) | Public core + per-company fork distribution | Accepted |
| [0003](0003-credentials-in-passwordsafe.md) | Credentials in PasswordSafe, never XML | Accepted |
| [0004](0004-agent-service-architecture.md) | Agent service architecture (core interface → ToolResult) | Accepted |
| [0005](0005-dependency-verification-and-lockfiles.md) | Dependency verification & lockfiles | Accepted |
| [0006](0006-enforcement-foundation-ci-gates.md) | Enforcement foundation: CI + lint + arch + CVE gates | Accepted |
