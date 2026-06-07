# Stable API Surface

This document is the **contract** for what company forks may build on. Symbols listed here are
**stable**: the base maintainer avoids source/binary-incompatible changes to them without a
deprecation cycle. Everything not listed is **internal** and may change without notice.

See [FORKING.md](../FORKING.md) for how to consume these (overlay module + `plugin.xml`), and
[docs/adr/0002-public-core-per-company-fork-model.md](adr/0002-public-core-per-company-fork-model.md)
for why the model exists. Stable types are (progressively) marked in code with
`@com.workflow.orchestrator.core.api.StableApi`.

## Stable extension points

All interfaces live in `:core` (package prefix `com.workflow.orchestrator.core.`). A fork implements
the interface in its overlay module and registers it in its `plugin.xml`; the consuming lookup is
`<Interface>.EP_NAME.extensionList` (single-impl EPs use `.firstOrNull()` / lowest-`order`).

### Forkability seams (added 0.86 — Phase 2)
| EP `qualifiedName` | Interface | Default impl | Purpose |
|---|---|---|---|
| `authProvider` | `core.auth.AuthProvider` | `DefaultAuthProvider` | Supply credentials per service (SSO/SAML/OAuth/Basic/custom) without editing `:core` |
| `workflowConfig` | `core.config.WorkflowConfig` | `DefaultWorkflowConfig` | Supply company-variable config (service URLs; env/file/LDAP/remote) |
| `featureRegistry` | `core.capability.FeatureRegistry` | `DefaultFeatureRegistry` | Centralize feature enablement (license server / LDAP group / admin policy) |

Supporting stable types: `core.auth.Credential` (sealed: Bearer/Token/Basic/Custom) and
`core.capability.PluginFeature` (enum).

### Pre-existing extension points (stable; predate 0.86)
| EP `qualifiedName` | Interface |
|---|---|
| `tabProvider` | `core.toolwindow.WorkflowTabProvider` |
| `branchNameAiGenerator` | `core.ai.BranchNameAiGenerator` |
| `jiraTicketProvider` | `core.workflow.JiraTicketProvider` |
| `textGenerationService` | `core.ai.TextGenerationService` |
| `agentChatRedirect` | `core.ai.AgentChatRedirect` |
| `sessionHistoryReader` | `core.services.SessionHistoryReader` |
| `createPrLauncher` | `core.bitbucket.CreatePrLauncher` |
| `openPrLister` | `core.workflow.OpenPrLister` |
| `latestBuildLookup` | `core.workflow.LatestBuildLookup` |
| `chainKeyResolver` | `core.workflow.ChainKeyResolver` |
| `plugin.sonarProjectPickerLauncher` | `core.services.SonarProjectPickerLauncher` |

> These 11 are documented stable here; in-code `@StableApi` annotation of the pre-existing
> interfaces is a pending follow-up (the three 0.86 seams are already annotated).

## Stable contracts (not EPs)
- **Service-architecture shape:** core interface → `ToolResult<T>` (`core.services.ToolResult`, with
  `T` in `core.model/`) → feature-module impl → agent tool wrapper. See
  [docs/adr/0004-agent-service-architecture.md](adr/0004-agent-service-architecture.md).
- **Module layering** (`api/ → service/ → ui/ → listeners/`; feature modules depend only on `:core`),
  enforced by `:konsist`. See [docs/adr/0001-module-layering.md](adr/0001-module-layering.md).
- **Credentials** via PasswordSafe (`core.auth.CredentialStore`), never XML.

## What is NOT stable
Everything else: feature-module internals, UI panels, the agent loop/controller, tool
implementations, persistence formats, and the god-classes slated for Phase 3 decomposition
(`AgentController`, `AgentService`, `AgentLoop`, `PrDetailPanel`, large tools). Do not build a fork
on these — request a seam upstream instead.
