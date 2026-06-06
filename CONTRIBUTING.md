# Contributing

This is a solo-maintained project with a public-core/per-company-fork model, so this
guide is intentionally light. Most company-specific work belongs in a **fork** — see
[FORKING.md](FORKING.md). This document covers contributions to the **public core**.

## Before you start

- Read [CLAUDE.md](CLAUDE.md) for the module map, service architecture, threading rules,
  and auth conventions. It is the authoritative engineering reference.
- Architectural changes must update the relevant module `CLAUDE.md` and `docs/architecture/`
  **in the same commit**.

## Build & test

```bash
./gradlew :<module>:test       # core/jira/bamboo/sonar/pullrequest/automation/handover/agent/document/web
./gradlew verifyPlugin buildPlugin
```

CI (GitHub Actions) runs build-and-verify, the full test suite, detekt (lint), and the
`:konsist` architecture tests on every push/PR. Your change must keep all of them green.

## Conventions

- **Architecture:** feature modules depend only on `:core`; cross-module via `EventBus`;
  layering `api/ → service/ → ui/ → listeners/`. The `:konsist` tests enforce this.
- **Agent reachability:** to expose behavior to the agent, follow core interface →
  `ToolResult<T>` → feature impl → agent tool wrapper (see [CLAUDE.md](CLAUDE.md)).
- **Threading:** `Dispatchers.IO` for API, EDT for UI, `WriteCommandAction` for files.
  Never `runBlocking` in production code — use `runBlockingCancellable` instead.
- **Credentials:** always via PasswordSafe, never XML.
- **Style:** detekt + detekt-formatting; keep the per-module `detekt-baseline.xml` from
  growing — fix new findings rather than baselining them.

## Commits & PRs

- Use [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`,
  `docs:`, `refactor:`, `ci:`, `style:`, `test:`).
- Add a `CHANGELOG.md` entry under `[Unreleased]` for user-facing changes (see
  [CHANGELOG.md](CHANGELOG.md)).
- Keep PRs focused; ensure CI is green.

## Architecture Decision Records

Significant or hard-to-reverse decisions get an ADR under [docs/adr/](docs/adr/). Copy
[docs/adr/0000-template.md](docs/adr/0000-template.md), number it next in sequence, and
include it in the same PR.

## Reporting bugs / security

- Bugs: open a GitHub issue.
- Security weaknesses: **do not** open a public issue — see [SECURITY.md](SECURITY.md).
