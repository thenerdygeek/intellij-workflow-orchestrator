# Forking & Customization Guide

This plugin is distributed as a **public core** that companies **fork and customize**.
The design goal is that a fork is a **thin overlay**: company-specific behavior lives
behind extension points and configuration so that **upstream changes merge cleanly** and
the fork rarely (ideally never) edits core files.

This guide is the contract for that model: what is stable to build on, what is internal
and may change, how to overlay customizations, and how to merge upstream.

## The golden rule

> **Add, don't edit.** Implement the stable `:core` interfaces in your own overlay
> module and register them in your fork's `plugin.xml`. Editing files under `:core`,
> `:agent`, or the feature modules turns every upstream merge into a conflict.

## Stable surface (safe to build on)

The stable, fork-facing surface is the set of **extension points** declared in the root
`src/main/resources/META-INF/plugin.xml`. **Every interface lives in the `:core` module**,
so a fork depends only on `:core` to implement them.

| Extension point (`com.workflow.orchestrator.*`) | Interface (`com.workflow.orchestrator.*`) | Purpose |
|---|---|---|
| `tabProvider` | `core.toolwindow.WorkflowTabProvider` | Add a tool-window tab |
| `branchNameAiGenerator` | `core.ai.BranchNameAiGenerator` | Customize AI branch naming |
| `jiraTicketProvider` | `core.workflow.JiraTicketProvider` | Supply Jira ticket data |
| `textGenerationService` | `core.ai.TextGenerationService` | Plug in a text-generation backend |
| `agentChatRedirect` | `core.ai.AgentChatRedirect` | Redirect agent chat routing |
| `sessionHistoryReader` | `core.services.SessionHistoryReader` | Expose agent session history |
| `createPrLauncher` | `core.bitbucket.CreatePrLauncher` | Customize "create PR" flow |
| `openPrLister` | `core.workflow.OpenPrLister` | Supply open-PR listings |
| `latestBuildLookup` | `core.workflow.LatestBuildLookup` | Resolve the latest build |
| `chainKeyResolver` | `core.workflow.ChainKeyResolver` | Resolve Bamboo chain keys |
| `plugin.sonarProjectPickerLauncher` | `core.services.SonarProjectPickerLauncher` | Customize Sonar project picking |

Also stable:
- The **service architecture contract**: core interface → `ToolResult<T>` (with `T` in
  `core/model/`) → feature impl → agent tool wrapper. New fork-facing behavior the agent
  must reach follows this same shape (see [docs/adr/0004-agent-service-architecture.md](docs/adr/0004-agent-service-architecture.md)).
- The **module layering** rule (`api/ → service/ → ui/ → listeners/`) and "feature modules
  depend only on `:core`", enforced by the `:konsist` architecture tests
  (see [docs/adr/0001-module-layering.md](docs/adr/0001-module-layering.md)).
- **Configuration & credentials**: settings pages under *Tools → Workflow Orchestrator*;
  secrets via PasswordSafe (`CredentialStore`). Override **config, not code**.

## Internal surface (may change without notice)

- Anything not listed above: feature-module internals, UI panels, the agent loop/controller,
  tool implementations, persistence formats.
- The god-classes targeted by roadmap Phase 3 (`AgentController`, `AgentService`, `AgentLoop`,
  `PrDetailPanel`, large tools) **will be decomposed** — do not build a fork on their internals.

## How to overlay a customization

1. **Create an overlay module in your fork** that depends only on `:core`.
2. **Implement** the relevant `:core` interface(s) from the stable table above.
3. **Register** your implementation in your fork's `plugin.xml` `<extensions>` block, e.g.:
   ```xml
   <extensions defaultExtensionNs="com.workflow.orchestrator">
       <tabProvider implementation="com.acme.workflow.AcmeTabProvider"/>
   </extensions>
   ```
4. **Optional IDE dependencies** follow the existing pattern — the root `plugin.xml` already
   uses `<depends optional="true" config-file="plugin-withGit.xml">Git4Idea</depends>` (and
   `…withMaven`, `…withSpring`, `…withTasks`, `…withCoverage`). Add your own optional
   config-files the same way; never inline company-only deps into the base descriptor.
5. **Externalize company-variable values** (server URLs, auth schemes, feature toggles) as
   configuration, not hard-coded edits. (A typed config layer + capability/feature-flag
   framework + an `AuthProvider` seam are roadmap **Phase 2** — until then, prefer settings
   and existing EPs over editing core.)

## Merging upstream cleanly

```bash
# one-time
git remote add upstream git@github.com:thenerdygeek/intellij-workflow-orchestrator.git

# each sync
git fetch upstream
git merge upstream/main      # or: git rebase upstream/main
```

Because your changes live in overlay modules and `plugin.xml` extension registrations,
conflicts should be limited to `plugin.xml` extension blocks and `gradle.properties`
(version). If you find yourself resolving conflicts inside `:core`/`:agent`/feature
source files, that customization belongs behind an extension point — file an upstream
request for a seam rather than editing core.

## What belongs upstream vs. in your fork

| In your fork | Upstream (open a PR/issue) |
|---|---|
| Company SSO/SAML, licensing, entitlement | New extension points / seams |
| Company server URLs, auth schemes | Bug fixes, security fixes |
| Company-only tabs/tools via EPs | Generally useful features |

See [CONTRIBUTING.md](CONTRIBUTING.md) for upstream contribution mechanics.
