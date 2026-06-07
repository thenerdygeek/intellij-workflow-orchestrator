# Phase 2 — Forkability & Extensibility Seams: Design Inputs

Research captured 2026-06-07 (read-only codebase survey) to ground the Phase 2 plan.
This is design input, not a plan. Authoritative file refs below.

## 1. Auth architecture (the AuthProvider seam target)

- **`ServiceType`** — `core/src/main/kotlin/com/workflow/orchestrator/core/model/ServiceType.kt` (~L3–10).
  6 values: JIRA, BAMBOO, BITBUCKET, SONARQUBE, SOURCEGRAPH, WEB_SEARCH.
- **`AuthScheme`** — `core/.../core/http/AuthInterceptor.kt:7` → `enum class AuthScheme { BEARER, TOKEN }`.
  BEARER → `"Bearer $token"`, TOKEN → `"token $token"`.
- **Scheme selection is hard-wired** in `HttpClientFactory.clientFor` (`core/.../core/http/HttpClientFactory.kt` ~L57–69):
  `when (service) { SOURCEGRAPH -> TOKEN; else -> BEARER }`. No Basic/mTLS/OAuth2/SAML/API-key.
- **`AuthInterceptor`** (`AuthInterceptor.kt:9–36`) — ctor `(tokenProvider: () -> String?, scheme: AuthScheme = BEARER)`.
  `tokenProvider` lambda is already a seam; the **scheme** is the rigid part. `followRedirects(false)` to stop header leakage.
- **`HttpClientFactory`** ctor: `(tokenProvider: (ServiceType) -> String?, connectTimeoutSeconds=10, readTimeoutSeconds=30)`.
  NOT a platform service — constructed with `new` by each api client (`JiraApiClient`, `BambooApiClient`, `SonarApiClient`, `BitbucketBranchClient`…), each passing `{ credentialStore.getToken(it) }`.
- **`CredentialStore`** — `core/.../core/auth/CredentialStore.kt`. API: `storeToken(service,token)` (L91),
  `getToken(service): String?` (L100), `hasToken(service)` (L122), `companion.clearGlobalCache()` (L56).
  Backed by PasswordSafe; static in-memory cache keyed `(ServiceType, serverUrl)`, 1h TTL.
  NOT an IntelliJ service — instantiated `CredentialStore()` in **45+ places**. No multi-token/refresh/SAML concept.

**Blast radius to add a new auth flow today** (the thing AuthProvider must shrink): `AuthScheme` enum +
`AuthInterceptor` when + `HttpClientFactory.clientFor` when + `CredentialStore` storage +
`AuthTestService.buildAuthHeader`/`healthEndpoint` + `ConnectionSettings` fields + `ConnectionsConfigurable` UI +
every api client calling `getToken` directly.

**Design implication:** `AuthProvider` interface in `:core/auth/` with (at least) `tokenFor(service): Credential?`
and request decoration (replacing the AuthScheme `when`). Inject into `HttpClientFactory` in place of the bare
`(ServiceType)->String?` lambda. `CredentialStore` stays as the default token back-end. Consider a sealed
`Credential` (BearerToken / PersonalAccessToken / OAuthBearer / SamlAssertion…). Default impl = current
PAT/bearer behavior; forks register an SSO/SAML impl via the EP overlay.

## 2. Settings / config (typed config layer target)

- No class named `WorkflowSettings`. Split into:
  - **`ConnectionSettings`** — APP-level `@Service`, `@State("WorkflowOrchestratorConnections", workflowOrchestratorConnections.xml)`.
    `core/.../core/settings/ConnectionSettings.kt`. Holds base URLs `jiraUrl/bambooUrl/bitbucketUrl/sonarUrl/sourcegraphUrl`,
    `bitbucketUsername`, `ticketKeyRegex`, web-search config. `getInstance()`.
  - **`PluginSettings`** — PROJECT-level `@Service`, `@State("WorkflowOrchestratorSettings", workflowOrchestrator.xml)`.
    `core/.../core/settings/PluginSettings.kt`. ~80 fields incl. timeouts + all the feature toggles. `getInstance(project)`.
- All URLs default `""` (blank = unconfigured); none hard-coded. API clients read `ConnectionSettings.getInstance().state.<x>Url` at construction.
- Effectively hard-coded (code constants, not settings): auth scheme per service, health-endpoint paths
  (`AuthTestService.healthEndpoint`), credential namespace prefix `generateServiceName("WorkflowOrchestrator", service.name)`.
- Configurables are standard `PersistentStateComponent` + DialogPanel; XML only (no YAML/HOCON).

**Design implication:** a `WorkflowConfig` accessor (value object or interface) wrapping both state classes with
typed getters; forks override the impl without editing `:core`. Absorb the code-constant values (scheme, health
paths, namespace) into it.

## 3. Extension-point idiom (how to add `authProvider` etc.)

1. Declare in root `src/main/resources/META-INF/plugin.xml` `<extensionPoints>`:
   `<extensionPoint qualifiedName="com.workflow.orchestrator.authProvider" interface="...AuthProvider" dynamic="true"/>` (all 11 existing EPs use `dynamic="true"`).
2. Interface in `:core` with companion:
   `val EP_NAME = ExtensionPointName.create<AuthProvider>("com.workflow.orchestrator.authProvider")`.
   (Pattern from `core/.../core/toolwindow/WorkflowTabProvider.kt:13–16`.)
3. Consume: multi-impl → `EP_NAME.extensionList.sortedBy { it.order }`; single/override → `EP_NAME.extensionList.firstOrNull()`.
4. Register default impl in `<extensions defaultExtensionNs="com.workflow.orchestrator">`; fork overlays its own.

## 4. Feature-flag / capability framework (build from scratch)

- **No framework exists.** ~20 ad-hoc `Boolean` fields on `PluginSettings.State` (`enableWebFetch`, `enableResearchSubagent`,
  `enableImageInput`, `healthCheckEnabled`…), naming inconsistent (`enableXxx` vs `xxxEnabled`, `by property(...)` vs `= default`).
  Read inline at decision points (`if (pluginState.enableWebFetch) …`).
- Only prior art for an interface-based capability seam: **`TransportCapabilities`**
  (`core/.../core/api/TransportCapabilities.kt`) — `interface { val supportsNativePdfDocumentBlock }` + `DefaultTransportCapabilities` object.
  Documented as a "forward-compatibility seam."

**Design implication:** add a typed `PluginFeature` (enum/sealed) + a `FeatureRegistry` service in `:core` exposing
`isEnabled(feature): Boolean`, delegating to `PluginSettings` by default; forks supply an alternative impl
(LDAP/license/remote). Keep existing booleans as the default backing store to avoid a big-bang migration.

## 5. Service-architecture contract (new seams should follow it)

- `core/model/` holds DTOs + `ServiceType` + `ApiResult<T>` (lower-level HTTP result).
- **`ToolResult<T>`** is at `core/.../core/services/ToolResult.kt` (NOT core/model): `data class ToolResult<T>(data: T?,
  summary: String, isError=false, hint: String?=null, tokenEstimate=0, imageRefs=[], @Transient payload: Any?=null)`
  + `success()/error()`. Universal return type for service ops (UI uses `data`, agent uses `summary`).

**Design implication:** new auth/config seams that the agent or UI consume should return `ToolResult<…>` (use `payload`
for typed auth error detail like SsoRedirectRequired/TokenExpired) rather than nullable strings.
