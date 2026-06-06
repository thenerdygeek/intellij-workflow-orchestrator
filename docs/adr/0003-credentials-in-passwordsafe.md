# ADR 0003: Credentials in PasswordSafe, never XML

- **Status:** Accepted
- **Date:** 2026-06-07 (backfilled)
- **Deciders:** Subhankar Halder

## Context

The plugin authenticates to Jira, Bamboo, Bitbucket, Sonar, and an LLM provider.
Storing tokens in plugin settings XML would leak them into VCS, backups, and exported
settings.

## Decision

All secrets are stored in IntelliJ **PasswordSafe** via
`core/.../auth/CredentialStore.kt` — never in XML or settings files. Auth schemes:
Bearer for Jira/Bamboo/Bitbucket/Sonar, `token` for Sourcegraph.

## Consequences

- Secrets are never serialized into project/IDE config files.
- Code that needs a token must go through `CredentialStore`; tests use seams rather than
  real secrets.

## Alternatives considered

- Settings XML with obfuscation: rejected — trivially reversible, leaks into VCS.
- Environment variables only: rejected — poor UX inside the IDE, easy to mis-scope.
