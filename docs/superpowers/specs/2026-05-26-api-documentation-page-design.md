# API Documentation Page — Design

**Date:** 2026-05-26
**Branch:** `feature/cross-ide-delegation`
**Status:** Approved, ready for implementation plan

## Goal

Add an in-IDE **API Documentation** page (modeled on the existing tool-documentation
page) that documents every external HTTP endpoint the plugin knows about across five
API families — **Jira, Bitbucket (Data Center), Bamboo, SonarQube, Sourcegraph** —
covering both endpoints that are **USED** by the plugin and endpoints that were
**probed or are known about but NOT used**.

**Primary success criterion: factual correctness.** Every documented endpoint must be
traceable to a real source (probe bundle raw JSON, client call-site, or research/audit
doc). No endpoint, parameter, response shape, or server version may be guessed or
invented. UI quality is secondary — it must simply be readable.

## Non-Goals (YAGNI)

- No live API calls from this page (that is the Tool Testing page's job).
- No in-IDE editing of the docs; the page is read-only.
- No automatic sync from client code — content is extracted once and committed.
- Nexus / Docker registry endpoints are out of scope (Nexus integration is parked).

## Key Decisions (from brainstorming)

1. **Surface:** a parallel, structured JCEF + React page (reuse the rendering shell of
   tool-docs, but with its own data model and payload path). *Not* shoehorned into the
   tool registry, because API endpoints are not `AgentTool`s.
2. **Scope:** the five named families only.
3. **Payload depth:** full — request shape *and* a redacted sample response excerpt
   pulled from the probe bundle for each endpoint where one exists.
4. **Audit verdicts:** yes — surface the research-doc classification (e.g. R-ADD /
   R-SWAP / R-INV, deprecated/removed-in-version) and its reasoning where present.
5. **Authoring format:** JSON resource files (one per family), not a Kotlin DSL.
   Rationale: API docs have no runtime binding (unlike tool-docs, whose DSL references
   live tool fields), and ~90 endpoints with embedded sample JSON are far more
   maintainable and diffable as data files than as Kotlin string literals.
6. **Layout:** a single page with one tab per family.
7. **Reviews:** correctness/content review is delegated to a **Sonnet subagent**.

## Architecture

A self-contained stack parallel to `tools/docs/`, with zero coupling to the tool
registry:

```
agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/apidocs/
  ApiDocModel.kt           # @Serializable data model
  ApiDocLoader.kt          # loads + validates the 5 JSON resources
  ApiDocPayloadBuilder.kt  # builds the combined wire-format JSON for the webview

agent/src/main/resources/api-docs/
  jira.json  bitbucket.json  bamboo.json  sonarqube.json  sourcegraph.json

agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/apidocs/
  OpenApiDocsAction.kt     # Tools ▸ View API Documentation
  ApiDocsEditor.kt         # JCEF FileEditor + VirtualFile + FileType + EditorProvider

agent/webview/
  api-docs.html
  src/api-docs.tsx                       # entry: window.renderApiDocs / window.applyTheme
  src/components/api-docs/ApiDocView.tsx # render component
```

Plus registration in `src/main/resources/META-INF/plugin.xml` (action +
fileEditorProvider). Static assets served by the existing `CefResourceSchemeHandler`.

## Data Model

```kotlin
@Serializable
data class ApiFamily(
    val id: String,                  // "jira", "bitbucket", ...
    val displayName: String,         // "Jira (Data Center)"
    val authScheme: String,          // "Authorization: Bearer <PAT>"
    val probedServerVersion: String, // "Jira DC 10.3.16"
    val description: String,
    val categories: List<ApiCategory>,
)

@Serializable
data class ApiCategory(
    val name: String,                // "Issues", "Sprints & Boards", "Dev-status", ...
    val endpoints: List<ApiEndpoint>,
)

enum class ApiEndpointStatus { USED, PROBED_UNUSED, KNOWN_UNUSED, DEPRECATED }
enum class ApiParamLocation { QUERY, PATH, BODY, HEADER }

@Serializable
data class ApiParam(
    val name: String,
    val location: ApiParamLocation,
    val type: String,                // "string", "integer", "boolean", ...
    val required: Boolean,
    val description: String,
    val example: String? = null,
)

@Serializable
data class ApiVerdict(
    val classification: String,      // "R-INV: inventoried, not adopted", "Removed in DC 9.0", ...
    val reasoning: String,
)

@Serializable
data class ApiEndpoint(
    val method: String,              // "GET", "POST", "PUT", "DELETE"
    val pathTemplate: String,        // "/rest/api/2/issue/{key}/transitions"
    val status: ApiEndpointStatus,
    val summary: String,
    val params: List<ApiParam> = emptyList(),
    val requestBody: String? = null, // body shape / form-encoding notes
    val sampleResponse: String? = null, // redacted excerpt from probe bundle
    val callSite: String? = null,    // "JiraApiClient.kt:142" — required when status == USED
    val provenance: String,          // REQUIRED — e.g. "probe Result_Jira/raw/myself.json"
    val verdict: ApiVerdict? = null,
    val gotchas: List<String> = emptyList(),
)
```

`provenance` is **non-nullable** — the structural enforcement of "no guessing." Every
endpoint must cite where its facts came from.

## Rendering & Data Flow

1. `OpenApiDocsAction` (Tools menu) opens `ApiDocsEditor` on a single deduplicated
   `ApiDocsVirtualFile`.
2. `ApiDocsEditor` builds a `JBCefBrowser`, loads `api-docs.html`, and on page-load
   injects the payload via `window.renderApiDocs(json)` plus theme via
   `window.applyTheme(vars)` (same pattern as `ToolDocsEditor`).
3. `ApiDocPayloadBuilder.buildAll()` calls `ApiDocLoader` to read and deserialize all
   five JSON resources, **skipping a family gracefully** (with a visible "failed to
   load" marker in the payload) if its file is missing or malformed, then serializes
   the combined `List<ApiFamily>` to JSON.
4. `ApiDocView.tsx` renders: family tabs → per-family header (auth scheme, probed
   server version, description) → category sections → endpoint cards. Each card shows
   the method+path, a status badge (USED / PROBED-UNUSED / KNOWN-UNUSED / DEPRECATED),
   a params table, an optional collapsible sample-response block, a verdict box when
   present, gotchas, and a footer line citing `callSite` and `provenance`.

Readable-but-minimal: reuse the tool-docs CSS variables / theme injection. No mermaid,
no tabs-within-cards. Status badges are the only color coding that matters.

## Content Sourcing — the "No Guessing" Guarantee

- The probe **result bundles** live in the **root repo**, not the worktree. They will
  be **temporarily copied into the worktree** (under a gitignored path) for extraction,
  and removed before commit. Only the derived JSON resources are committed.
- Every endpoint's facts come from exactly one of three source types:
  - **Probe bundle raw JSON** — sample response, probed server version, observed params.
    (e.g. `tools/atlassian-probe/Result_Jira/bundle.unpacked/raw/*.json`)
  - **Client code** — used endpoints and their call-site `file:line`.
    (`JiraApiClient`, `BitbucketBranchClient`, `BambooApiClient`, `SonarApiClient`,
    `SourcegraphChatClient`)
  - **Research / audit docs** — verdict classification + reasoning.
    (`docs/research/2026-05-*-{jira,bitbucket,bamboo,sonarqube}-recommendations.md`,
    `2026-04-08-sourcegraph-api-surfaces.md`)
- If a fact cannot be sourced, the endpoint is **omitted** rather than guessed.
- **Redaction gate:** the bundles already carry `redaction_report.json`, but every
  embedded `sampleResponse` is scanned a second time for residual tokens, emails, and
  internal hostnames before commit.

## Content Generation Strategy

Subagent-driven, foreground, sequential (same module / working tree):

1. Build the Kotlin model + loader + payload builder, the JCEF editor + action, and the
   React view + entry first (scaffold, with a small hand-written fixture so the page
   renders end-to-end before bulk content exists).
2. Then **one Sonnet subagent per family**, run sequentially, each extracting that
   family's endpoints from probe bundle + client code + research doc into its JSON
   resource, conforming exactly to the schema and citing provenance per endpoint.
3. A final **Sonnet correctness-review subagent** cross-checks each JSON resource
   against its sources (no invented endpoints, provenance resolves, USED endpoints have
   real call-sites, samples are redacted).

## Error Handling

- Missing or malformed JSON resource → that family is skipped with a visible marker;
  the editor never crashes.
- Endpoint with `status == USED` but no `callSite` → caught by the schema-invariant
  test (build-time), not shipped.

## Testing

- **Kotlin:** deserialize all five resources successfully; assert invariants — every
  endpoint has non-blank `method`, `pathTemplate`, and `provenance`; every `USED`
  endpoint has a `callSite`. `ApiDocPayloadBuilder.buildAll()` returns valid JSON for
  all families.
- **Webview:** a vitest render test for `ApiDocView` against a fixture payload (renders
  tabs, a card, a status badge, and the provenance footer).
- **Build:** `./gradlew :agent:test` and `verifyPlugin` green; webview vitest green.

## Open Risks

- The bundle `.unpacked` raw JSON may not cleanly cover every "known-but-unused"
  endpoint (some are known only from research docs, with no sample). Those endpoints get
  `sampleResponse = null` and cite the research doc as provenance — acceptable.
- Bitbucket has the largest surface (~32 used + several R-INV); its JSON file will be
  the biggest and warrants the most careful review pass.
