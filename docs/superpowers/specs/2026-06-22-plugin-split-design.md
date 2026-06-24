# Plugin Split — Open-Source Configurable Backbone (A) + Private Workflow (B)

**Status:** Design — brainstorming complete; **Round-1 + Round-2 review folded in (rev 3)**; sequencing RESOLVED: internal-first (§10); **pending user spec review** before the Phase-0 implementation plan.
**Date:** 2026-06-22 (rev 3)
**Author:** Subhankar (with Claude)
**Relates to:** Phase 2 enterprise seams already landed (`AuthProvider`/`WorkflowConfig`/`FeatureRegistry`/`@StableApi`)

> **Rev-3 changelog (Round-2 review):** **(A)** dropped `internal` everywhere — A's EP surface is **`public` + `@InternalApi` + unfrozen-by-policy** (Kotlin `internal` is module-scoped; B can't implement `internal` interfaces across a plugin boundary), and §10 is honestly softened (internal-first defers the *external-consumer* burden, not the two-plugin **lockstep** burden). **(B)** cut Tier-1 native LLM to **Anthropic-direct only**; demoted Bedrock/Vertex to Tier-2 (SigV4/OAuth2 ≠ static-header auth; their SDKs bypass `IdeProxy`/`IdeTrust`). **(C)** re-scoped §6: the `ToolProtocol`/`LlmProvider` seam is **wider** than first drawn (system-prompt assembly + streaming segmentation + history conventions + model-catalog/capability/error-classification + `BrainRouter` absorption); added the **persisted-message-format migration** and **proxy-aware native client** as Phase-4 workstreams; "NativeProtocol deletes the drift machinery" reworded as 5-site conditional-bypass surgery. **(D)** corrected effort to **~4–6 months**, Phase 4 the critical path. **(E)** softened §4's "platform" rhetoric. **(F)** `IssueTrackerProvider` = rename **+ resolver upgrade**.

---

## 1. Goal

Split the current Workflow Orchestrator plugin into **two installable IntelliJ plugins**:

- **Plugin A (open-source eventually, configurable):** a **configurable engine** — a capable, standalone AI coding agent for IntelliJ IDEA, with a pluggable LLM backend and configurable enterprise integrations. Usable on its own; a team on the supported stack points it at *their* infra via configuration.
- **Plugin B (private):** plugin A **configured to this company** (a settings preset of our defaults) **plus the handful of company-only code modules** that A doesn't ship. `<depends>` on A, extends it via extension points, never forks it.

**Mental model:** A is a configurable engine; B is "A pre-configured for us + our bespoke modules." Install A alone → useful (you configure it). Install A + B → our team's turnkey cockpit.

**Why split / eventually open-source A:** primarily it's **clean modularization** — a generic engine cleanly separated from company specifics, so B overrides via well-defined seams instead of forks. Open-sourcing later lets teams *on the supported stack* adopt A by configuration, and lets the community contribute providers/connectors that flow back. We do **not** claim A is a broad platform yet — no second stack has validated that (internal-first concedes none today); §4 is modularization that keeps the door open, not a platform pitch.

---

## 2. Mechanism — two plugins, extension points, never a code fork

Plugin B does **not** copy or edit A's source. B is a **new, separate plugin project** that `<depends>` on A's plugin ID and **plugs into A's extension points**; A ships a generic default, B contributes an override/addition that wins via the existing `order` + `runCatching`-resolve pattern.

**Mechanism precision (Round-1):**
- **B is a NEW root plugin project.** Today the repo is **one composed plugin** — 12 Gradle modules, a *single* `plugin.xml`, all feature modules in one JAR via `intellijPlatformComposedJar`. B = a new root module applying `org.jetbrains.intellij.platform` (not `.module`), its **own** `plugin.xml` + `buildPlugin`, with the company-only modules' descriptor entries physically relocated out of A's descriptor into B's.
- **Two requirements:** (a) a **compile-time** Gradle dep on A — `localPlugin(project(...))` / `pluginModule()` in dev (A isn't published until the deferred Phase 5), `plugin("<A-id>", "<version>")` once A is on a repo; **and** (b) a **hard** `<depends>com.A.id</depends>` (NOT `optional="true"`) so B's classloader is a child of A's at runtime.
- **A's EP surface that B implements MUST be `public`** (Kotlin `internal` is compilation-module-scoped — B, a separate Gradle project consuming A as a JAR, cannot reference `internal` symbols at all). The existing seams are already `public` for exactly this reason (`AuthProvider.kt:13`, `WorkflowConfig.kt:16`, `FeatureRegistry.kt:16`). "Unfrozen" is achieved by **policy + `@InternalApi`**, not by `internal` (§8, §10).
- **Override EPs are winner-take-all; contribution EPs must be additive.** Override seams resolve to one winner (lowest `order`; default = `Int.MAX_VALUE`; B registers any `order < that`). The new **contribution** EPs (agent-tool, monitor-source) need **additive-merge** semantics — a different resolver; don't reuse the winner-take-all one.
- **Service impls are NOT EP-overridable.** `JiraServiceImpl`/… are `<projectService serviceInterface/serviceImplementation>`; the platform forbids two plugins registering a service for the same interface. Provider *swap* happens at the EP layer above the service.

Analogy: A is Chrome; B is the company's extension. New release of A → B bumps one version (B never contained a copy of A) **but must recompile in lockstep** — B's compile is the thing that pins A's signatures (see §10).

```
Marketplace plugin A  ◄── <depends> (hard) ── Private plugin B
(configurable engine)                          (our preset + bespoke code)
  :core (generic) + :agent                       :automation
  generic Atlassian/Sonar connectors             :handover
  LlmProvider + ToolProtocol seams               company default values (preset)
  EP surface = public + @InternalApi    proprietary personas
  ships sensible defaults                         company-only agent tools
User installs A (and configures it)             User installs A + B
```

---

## 3. Plugin A identity & scope — **Tier 1**

> An open-source, **configurable** AI coding agent for **IntelliJ IDEA** (Java/Kotlin **and** Python), with a **pluggable LLM backend** (Sourcegraph today; **Anthropic-direct** added in Tier 1; Bedrock/Vertex = Tier 2 — §6) and **configurable** reference connectors for the **Atlassian + SonarQube** stack.

**Hard scope boundaries (documented):**
- **IDE:** IntelliJ IDEA only — hard `com.intellij.modules.java` dep (`plugin.xml:9`); built against **IDEA Ultimate**.
- **Languages:** Java/Kotlin first-class; **Python** supported; others degrade.
- **Issue tracker:** Jira **Server/DC** (REST v2 + PAT). **Jira Cloud not supported.** **Git host:** Bitbucket **Server/DC**. **CI:** Bamboo (DC).

**Tier 1 (this project):** strip company *conventions* into configuration; keep the Atlassian connectors in A behind neutrally-named seams; **build the LLM pluggability seam** shipping **Sourcegraph (XML)** + **Anthropic-direct (native)**. **Tier 2 (deferred/community):** GitHub/GitLab/Jenkins connectors; **Bedrock/Vertex** LLM transports. **Tier 3 (out of scope):** drop the Java dep, all JetBrains IDEs. Seams are named/shaped for Tier 2 but their non-Atlassian / non-direct impls are not built now.

---

## 4. The configurable-engine principle

Plugin A is an engine, not a fixed product. The A/B test reduces to **"can it be expressed as configuration?"** — three tiers; only the third needs B:

| Tier | What | Example | Who supplies it |
|---|---|---|---|
| **1 — Plain settings** | A value in a Settings field/dropdown | LLM provider choice + key/endpoint; Jira/Bitbucket/Bamboo/Sonar URLs + tokens; branch pattern; default branch; status names; commit format; custom-field IDs | **A team on the supported stack** (configure A; no code) |
| **2 — Pluggable impl via config/EP** | Which implementation is active | which `LlmProvider`/`ToolProtocol`; which `WorkflowConfig`; which `IssueTrackerProvider` | Any team / community (EP; **not** a fork) |
| **3 — Genuinely new code** | A feature A doesn't ship | `:automation`, `:handover`, proprietary agent tools | **Plugin B only** |

**Consequence:** plugin **B = a configuration *preset* + the tier-3 code.** A team **already on the supported Atlassian+Sonar stack, using IDEA** configures A without writing code; a team on a *different* stack must contribute a **Tier-2 connector** (= code) — `VcsHostClient`/`CiService` are net-new and have no GitHub/GitLab/Jenkins impls today. So "configure-only" is the *supported-stack* path, not universal.

**Caveat (Round-2):** a Tier-1 *value* can still require Tier-2/3 *plumbing* before the engine honors it neutrally — anything the **system prompt** touches (it currently hardcodes Jira/Bamboo/Sonar/Bitbucket, `jira:` linkify, a Jira-transition teaching block) needs code to honor a setting neutrally. The clean 3-way partition is the target, not the current state.

Design rule: **push everything possible down to tier 1 or 2;** every hardcoded company convention becomes a setting (neutral default in A; our value via B's preset).

---

## 5. The A/B line

### Plugin A (open-source engine) gets
- **The agent:** full ReAct loop, ContextManager, sub-agents, plan mode, skills, monitor framework, JCEF chat UI, persistence/checkpoints — and **~all 88 tools**.
- **`:core`** (generic framework) **minus** company-default *values* (→ blank/neutral defaults + B preset, §7.2) and `WorkflowIntent`'s company status strings.
- **`:document`, `:web`, `:mock-server`** *(scrub Kotlin data factories — §9)*, **`:konsist`** *(restructure for two plugins — §7)*.
- **Reference connectors (de-conventioned, configurable):** Sonar (entire module, CLEAN); Jira; Bitbucket/PR; Bamboo (run config **minus `DockerTagsProvider`**).
- **Generic mechanisms (policy is config, not code):** AI PR review, AI PR descriptions, auto-transition-on-commit, **`BuildFailureBridge` (reclassified to A — Round-1: uses only `:core` APIs, no company variable)**, health-check *framework*, `CopyrightFixService` year-arithmetic.
- **The seams (§8):** `LlmProvider` + `ToolProtocol`, `IssueTrackerProvider`, `VcsHostClient`, `CiService`, agent-tool + (later) monitor-source contribution EPs, settings-section contribution.

### Plugin B (private) — *thin*
- **Configuration preset:** our defaults for every tier-1 setting (LLM=Sourcegraph + endpoint, branch pattern `feature/{ticketId}-{summary}`, default branch `develop`, status names, commit format, custom-field IDs, `bambooBuildVariableName="DockerTagsAsJSON"`, the `quickClipboardChips` docker entries).
- **Tier-3 code:** `:automation`; `:handover` (**except `CopyrightFixService`→A**); copyright *template content*; `DockerTagsProvider` adapter; the **`devops-engineer` persona**; proprietary personas; company-only agent tools.

---

## 6. LLM pluggability — first-class workstream (wider than first drawn)

The agent must work against whichever provider the user **configures** (tier-1 setting), and the loop must adapt to that provider's tool-calling paradigm. This is central to A's value and retires the Round-1 "visible Cody-dialect" + "audience" objections. **Round-2 correction: this is a deep core-loop + persistence + transport workstream, not a tidy 3-method seam — it is the program's critical path (§10).**

**Two tool-calling paradigms; the loop branches.**
- **XML-in-content (Sourcegraph/Cody, today):** tool defs injected as XML into the system prompt; model emits tool calls as XML in its text (`AssistantMessageParser`, re-run per accumulated SSE chunk at `AgentLoop.kt:1074`); results as plain-text `"TOOL RESULT:"`; `DialectDriftDetector` keeps the model on the dialect.
- **Native function-calling (Anthropic Messages API):** tools in `tools:[]`; structured `tool_use` blocks out; `tool_result` blocks back. No text parsing/drift.

**The seam is wider than three methods.** A `ToolProtocol` must own, at minimum: **(1) tool presentation** — but this is a *system-prompt-assembly* concern (`ToolPromptBuilder` → `SystemPrompt.build()`, two call sites), not a per-request field; **(2) response segmentation** — `parseToolCalls` doubles as the **per-SSE-chunk streaming-UI splitter** driving live text + `StreamingEditTracker`; native uses `content_block_delta`/`input_json_delta` and a different `StreamChunk` shape consumed up to the JCEF UI; **(3) tool-result formatting + history convention** — scattered across `MessageSanitizer` (`"TOOL RESULT:"`), `ContextManager` (dedup header it relies on for compaction), and `ApiMessage` (on-disk shape). Phase 0 must design this surface correctly or Phase 4 reopens it.

**`LlmProvider` must be wider too** — not just `toolProtocol` + `stream`. It must expose **model catalog / capability / context-window** (today `ModelCatalogService` + `ModelCache`, Sourcegraph-shaped `provider::apiVersion::modelId`, consumed by `ContextManager`'s compaction threshold and `AgentLoop` recovery) and **provider error classification** (today `GatewayErrorDetector` matches Cody `context deadline exceeded` → an `upstream_timeout` recovery branch). `BrainRouter`'s **per-message image routing** must be **absorbed into the provider** so the loop reads *one* provider, not a router that re-decides each turn.

**"NativeProtocol deletes the drift machinery" = 6-site conditional-bypass surgery** *(corrected 2026-06-23 by Phase-0b-1 code exploration — was "5-site")*, not a no-op impl: `DialectDriftDetector`/its flag is wired at **6** sites: (1) persistence write path `MessageStateHandler` (write-gate + redact), (2) prompt assembly `SystemPrompt`, (3) sub-agent prompt `SubagentSystemPromptBuilder`, (4) sub-agent loop `SubagentRunner` (separate file — passes the flag to the builder), (5) main-agent system-prompt lambda in `AgentService` (`executeTask` path), (6) resume redaction `ResumeHelper`/`AgentService`. Native must bypass these via a flag the seam exposes — an explicit task. `MessageSanitizer` fuses transport + protocol concerns and `ContextManager`'s L4 compaction depends on its role-merge — so splitting it into the XML protocol is non-transport-local.

**Transport: Anthropic-direct only in Tier 1.** The "Bedrock/Vertex are free, one implementation" claim is true only for the *tool-calling shape*, **false for transport**: Bedrock = AWS SigV4 (per-request body signing, IAM chain — not a static header), Vertex = Google OAuth2 (async token refresh — no hook in the current synchronous `Credential`/`AuthInterceptor.applyTo`), and their SDKs bring **their own** HTTP clients that **bypass `IdeProxy`/`IdeTrust`** (corporate-proxy + custom-truststore — the exact breakage prior `IdeProxy` work fixed). Also: the agent's current `SourcegraphChatClient` already builds its **own** OkHttp that does **not** route through `IdeProxy`/`IdeTrust` — so a proxy-aware native client is itself a workstream. Anthropic-**direct** (`x-api-key` bearer) fits the existing static-header `Credential` model + proxy-aware OkHttp → it's the clean Tier-1 target. **Bedrock/Vertex are Tier-2** (budget SigV4/OAuth2 + proxy re-routing when we do them).

**Persisted-message-format migration (Phase 4).** `ApiMessage` stores an assistant turn as **one `ContentBlock.Text` with the tool call inline as XML** (the structured `ToolUse` variant is legacy/pre-2026-05-13, `@Deprecated`, read-only). *(Corrected 2026-06-23 by Phase-0b-1 exploration:* tool results are **already** persisted as structured `ContentBlock.ToolResult(toolUseId, content="raw output")` under `ApiRole.USER` — **NOT** as `"TOOL RESULT:"` text; that prefix lives only at **wire-send** time in `MessageSanitizer`. So the persisted-format migration is **narrower than first drawn — it covers only the assistant turn** (XML-in-`Text` vs structured `ToolUse`), not tool results.*)* Native `tool_use` assistant blocks are a **different on-disk shape** → a **persisted-format migration + a per-message protocol discriminator** (nullable, default `"xml"`) so resume can replay a session mixing XML and native turns. The discriminator FIELD is reserved now (Phase 0b-1); the migration logic lands in Phase 4. Unbudgeted before Round-2; now an explicit Phase-4 line item.

*(Java-SDK client snippets, when we reach Bedrock/Vertex: Bedrock `AnthropicOkHttpClient.builder().backend(BedrockMantleBackend.fromEnv()).build()` (pkg `com.anthropic.bedrock.backends`), model `anthropic.claude-opus-4-8`; Vertex `…backend(VertexBackend.builder().region(…).project(…).build()).build()` (pkg `com.anthropic.vertex.backends`), model bare `claude-opus-4-8`. The `com.anthropic`/AWS/GCP SDKs are not yet dependencies.)*

**Tier-1 deliverable:** the `LlmProvider`+`ToolProtocol` seams (wide, per above), shipping **`SourcegraphProvider` (XML)** + **`AnthropicDirectProvider` (native)**; provider choice + keys are tier-1 settings. **Internal-first (§10): both are *built and shipped* in the private Phase 4; Bedrock/Vertex and the public open-sourcing come *after* the program once the native path is proven.**

---

## 7. The assumption inventory (the "threads") — with Round-1 corrections

### 7.1 Protocol-bound stack (seams → §8)
Jira DC → `IssueTrackerProvider`; Bitbucket DC → `VcsHostClient`; Bamboo → `CiService`; Sonar (generic ✓); LLM → `LlmProvider`+`ToolProtocol` (§6).

### 7.2 Company conventions → tier-1 settings (🔴 silently breaks other teams)
- 🔴 `defaultTargetBranch = "develop"` hardcoded fallback (`PluginSettings.kt:65`, `DefaultBranchResolver.kt:230`, `RepoConfig.kt:12`, `CreatePrPrefetch.kt:308,311`, `RepositoriesConfigurable.kt:114,392,511`) → configurable; neutral default `main`/detect `origin/HEAD`.
- 🟢 **`BranchNameValidator.isValidBranchName` is dead code** (zero prod callers; jira:F-15) → **delete it** (the earlier "can't branch without Jira 🔴" was wrong).
- 🔴 System prompt leaks the stack unconditionally (`SystemPrompt.kt:335,396-406,519,535,538,855-866`) → gate each on configured integrations. *(This is the Tier-1-value-needs-code caveat from §4.)*
- 🟠 Sprint tab 404s without Jira Software (`JiraApiClient.kt:46-79`) → feature-detect + hide. Conventional-Commits forced (`CommitMessagePromptBuilder.kt:28,85`); `{ticketId}` templates; time-tracking-on-commit → configurable/off.
- 🟠 More `:core` company defaults: `bambooBuildVariableName="DockerTagsAsJSON"` (`PluginSettings.kt:123`); `quickClipboardChips` docker entries (`PluginSettings.kt:516-521`) → neutral defaults in A, B preset supplies ours.
- 🟠 `PsiContextEnricher` Maven-only module detection (`:59-73`) → `ModuleManager` fallback.

### 7.3 IDE/language/framework scope (documented positioning)
Hard Java dep; Ultimate; Java/Kotlin+Python; Spring graceful-degrade. Personas: most generic; **`devops-engineer`→B**; `security-auditor`/`performance-engineer` → A with a `supportsSpring` gate. `git-workflow` skill → split generic-git (A) / Atlassian (tool-gated/B).

### 7.4 Company-only → B (§5). 7.5 LLM → §6. 7.6 Security → §9.

---

## 8. New seams to build (Phase-0 core)

All seams: declare an EP in A, ship a generic default, **expose `public` interfaces marked `@InternalApi` (unfrozen-by-policy — we may change them; B recompiles in lockstep)**, document, contract-test. **Do NOT make any B-implemented interface `internal`.** *(Annotation reality: the codebase has `@StableApi(since: String)` (fork-stable) and `@InternalApi` (public but not stable) in `core/.../core/api/ApiStability.kt` — there is no `CANDIDATE` level. During internal-first we mark B-facing EP interfaces `@InternalApi`; `@StableApi(since=…)` is reserved for the eventual external freeze in the deferred Phase 5.)*

1. **`LlmProvider` + `ToolProtocol`** (§6) — wide surface (tool presentation/segmentation/result-formatting + model-catalog/capability/error-classification + absorb `BrainRouter`); ship Sourcegraph(XML) + Anthropic-direct(native). The `DialectDriftDetector` 5-site bypass + the persisted-format discriminator are explicit tasks (discriminator lands in Phase 4 with the native provider).
2. **`IssueTrackerProvider`** — rename `JiraTicketProvider` **+ resolver upgrade**: the existing EP resolves via `firstOrNull()` (`core/.../workflow/JiraTicketProvider.kt:31-32`), not lowest-order-wins — upgrade it to the `minByOrNull{order}` pattern so B's override deterministically wins. Jira impl ships in A.
3. **`VcsHostClient`** — neutral branch/PR/default-branch/reviewers ops; Bitbucket DC impl in A. (Net-new — no other-host impl today.)
4. **`CiService`** — neutral CI vocabulary above `BambooService`; Bamboo impl in A. (Net-new.)
5. **Agent-tool contribution EP** — **narrow factory** (`name`/`description`/`factory(context)`); **A keeps owning** `AgentTool`/registration/schema/lifecycle (do NOT have B implement the fat `AgentTool` — it imports `AgentService`). Move the hardcoded `WRITE_TOOLS`/`HOOK_EXEMPT` sets in `AgentLoop` onto **self-declared `AgentTool` properties** (`isWriteTool`, `requiresApproval`); add a project-scoped `ToolRegistrationService` host (today session-scoped inside `AgentService`). **Until safety props land, B may not contribute write tools.**
6. **Monitor-source contribution EP** — **descoped from Phase 0** (deepest seam: `MonitorSource`/`MonitorEvent`/`MonitorSpec` live in `:agent`, must move to `:core`; needs resume/persistence-namespacing through `AgentMonitorCoordinator`; not needed day one).
7. **Settings-section contribution** — B contributes its settings pages + its config **preset** and overrides A's neutral defaults.
8. **Relocate `WorkflowIntent` + defaults out of `:core`** — risk emphasis inverted (Round-1): `WorkflowIntent` is **trivial** (1 referencer; leave it in A unchanged is fine). The **real danger** is blanking `PluginSettings.State` defaults — `SimplePersistentStateComponent`/`BaseState` **omits default-equal fields from XML**, so an existing user who never set a value **silently flips** to the new default on next launch. **No `@State` migration mechanism exists in this repo** (no `ConverterProvider`/`@State(version=…)`). Action: build a concrete migration (first-launch-after-upgrade seed / sentinel / version bump) **before any default is blanked**.

**Public-API tiering (`@InternalApi`):** stable-candidate = the EP interfaces + `ToolResult<T>` + the narrow tool/monitor factory DTOs; **not** stable = the broad `:core/model` surface (documented "depend at your own version-coordination risk"). **These are `public` and unfrozen-by-policy** — we are free to change them; B bumps and recompiles in lockstep. **Freezing** (committing to backward-compat for *external* consumers) happens only in the deferred Phase 5. Writing the konsist `StableApi` contract test is a Phase-0 deliverable; `konsist`'s `ModuleBoundaryTest`/`LayeringTest` also need restructuring for the two-plugin graph (A's tests must not see B; B asserts "B→A, not A→B").

---

## 9. Security & distribution gates

- ✅ **Shippable Kotlin/Java source is clean** — placeholders + RFC-5737 IPs only.
- 🔴 **GATE A — scrub bundled `agent/src/main/resources/api-docs/*.json`** (ship in the JAR; gitleaks won't catch — internal *intelligence*, not secrets): real server versions (**Bamboo DC 10.2.14, Bitbucket 9.4.16, Jira 10.3.16, Sourcegraph Enterprise**), org metrics (**"2292 plans", "300+ agents"**), real plan keys (**`PROJ-PLAN138`**), provenance strings, **`DockerTagsAsJSON`**. **`:mock-server`** generates synthetic data in **Kotlin source** (`BambooDataFactory.kt`, `JiraDataFactory.kt`) — audit those sources for the same data; there are no JSON fixtures.
- 🔴 **GATE B — A ships from a genuinely NEW repo.** `github.com/thenerdygeek/intellij-workflow-orchestrator` is already public under the author's real name with public releases, so its git history is already exposed; do not reuse it. Exclude `docs/`/`audit/`/`research/`; run `gitleaks`/`trufflehog` + the scrub-grep before first publish.
- 🟡 **GATE C — Apache-2.0 NOTICE** for Cline (the agent is a port) + Continue.dev/OpenHands patterns; none exists.
- 🟡 **GATE D — `:core` convention defaults blanked + the §8.8 migration shipped** before any public release.

*(Gates A–D execute in the deferred Phase 5; the migration (D) is also a Phase-1 prerequisite for blanking defaults internally — §11/§12.)*

---

## 10. Sequencing decision — RESOLVED: **internal-first, open-source later** (option 1)

**Resolution (2026-06-22):** build the full two-plugin architecture + de-convention + the `LlmProvider`/`ToolProtocol` seam (Sourcegraph + Anthropic-direct), and **ship A+B privately** for our team. A's EP surface is **`public` + `@InternalApi` + unfrozen-by-policy**. Open-sourcing A (freeze the API for external consumers, fresh repo, gates A–D, Marketplace publish, plus Bedrock/Vertex) is a **later, separate effort** once the native provider is proven.

**What internal-first does and does NOT buy (Round-2 honesty correction):**
- **Buys:** defers the **external-consumer** stability burden (no strangers depending on A yet → we can still change signatures), and the open-source distribution/marketing/maintenance load. Captures the modularization + de-convention + provider-agnostic value now; team uses it immediately.
- **Does NOT buy:** the **two-plugin lockstep** coordination tax. The moment B `<depends>` on A and compiles against its `public` surface, every signature B touches is **de-facto pinned by B itself** (break it → B fails to compile on the next A change). So "~90% of the benefit, **none** of the API burden" (an earlier rev) is **overstated** — you pay the API-*design* and lockstep-recompile burden regardless; you only defer the *external* burden. Internal-first is still the right call (it avoids freezing a not-yet-proven design for outsiders), but it relabels rather than removes the in-house coordination cost.

**Effort (corrected — Round-2):** the earlier "~2–3 months" is **stale** — rev-2 *added* the native provider as first-class (it was not absorbed). Realistic whole-program range: **~4–6 months**. The **critical path is Phase 4** (NativeProtocol-through-the-loop + the persisted-message-format migration + proxy-aware native client) — a core-loop change with resume/streaming regression risk — **not Phase 0**; but Phase 0 must shape the §6 seam correctly or Phase 4 reopens it. Net-new unbudgeted infra confirmed: the `@State` migration framework, the `AgentTool` safety-prop refactor + project-scoped `ToolRegistrationService`, the persisted-format migration, and (if/when Bedrock/Vertex) SigV4/OAuth2 + IDE-proxy rewiring.

---

## 11. Phased roadmap (all of Phases 0–4 ship A+B **privately**)

| Phase | What | Notes |
|---|---|---|
| **0 — Skeleton + seams** | New B root plugin project (hard `<depends>`, own descriptor); the §8 seams incl. the **wide** `LlmProvider`+`ToolProtocol` shape + the agent-tool factory + safety-prop refactor; the `@State` migration (§8.8); **`public` + `@InternalApi`** marking + konsist contract/restructure. **Do NOT freeze; do NOT make EP interfaces `internal`.** | Riskiest *architecturally*; must shape the §6 seam right |
| **1 — De-convention → settings** | §7.2: `develop`→config, delete the dead validator, gate the system prompt, hide Sprint tab w/o Software, configurable commit format, `PsiContextEnricher` fix, dynamic role text, blank the `bambooBuildVariableName`/`quickClipboardChips`/status defaults **(migration §8.8 first)** | Highest ROI |
| **2 — Carve company-only → B** | `:automation`, `:handover` (CopyrightFix→A), `DockerTagsProvider` adapter, copyright template, the config **preset**, `devops-engineer` persona; relocate descriptor entries into B | B becomes lean |
| **3 — Persona/skill/prompt hardening** | `supportsSpring` gate on `security-auditor`/`performance-engineer`; split `git-workflow` skill | |
| **4 — Native LLM (Anthropic-direct) — CRITICAL PATH** | `AnthropicDirectProvider` (native) on the §6 seam; **`NativeProtocol` through the loop** (segmentation, drift-bypass at 5 sites); **persisted-message-format migration + protocol discriminator**; **proxy-aware native HTTP client**; expose effort/thinking settings. *(Bedrock/Vertex = Tier-2, after.)* | ~3–4 wks even direct-only; resume/streaming regression risk |
| **5 — OSS hardening + distribution** *(DEFERRED — separate later effort)* | **Freeze** `@StableApi` for external consumers; Gates A–D (§9); fresh repo; license + honest README; Marketplace publish A. **Not part of this program**; do once the native provider is proven. | B stays private throughout |

Each phase = its own spec → plan → implementation, **each with multiple independent review rounds** (standing rule).

---

## 12. Phase 0 — detailed

**Objective:** a building, test-green two-plugin structure where B (even near-empty) installs on A and overrides one thing end-to-end; the wide provider/contribution seams exist (shaped right so Phase 4 doesn't reopen them); the `@State` migration is in place; the `public` `@InternalApi` contract test exists (**not** frozen).

- **8.1 Build/repo:** new B root project applying `intellijPlatform` with its own `plugin.xml`; B depends on A via `localPlugin(project(...))` + hard `<depends>`. EP interfaces B implements are **`public`** (never `internal`). B contains only B's files (clean-room; protects §9 GATE B).
- **8.2 Seams:** the **wide** `LlmProvider`+`ToolProtocol` (Sourcegraph-XML wraps current behavior; `NativeProtocol` interface shaped now, impl in Phase 4); `IssueTrackerProvider` (rename + resolver upgrade) / `VcsHostClient` / `CiService` (Atlassian impls ship); the **narrow agent-tool factory EP** + the `AgentTool` safety-prop refactor + project-scoped `ToolRegistrationService`; settings-section contribution. *(monitor-source EP descoped; persisted-format migration deferred to Phase 4.)*
- **8.3 `@State` migration (§8.8)** lands **before** any default is blanked.
- **8.4 `public` `@InternalApi` + konsist contract test** written; konsist restructured for two plugins. (Marking only — freezing is the deferred Phase 5.)
- **8.5 End-to-end proof (order: migration first, then this):** B overrides `WorkflowConfig`/`defaultTargetBranch` and the change is observed at runtime with both plugins loaded; one B-contributed agent-tool appears in the registry.
- **Done (verifiable gates):** A builds + tests green standalone; B builds + installs on A; one override + one B-contributed agent-tool work end-to-end; contract test green.
- **Build note (not a gate):** use `--no-build-cache` for any suspend-signature change (known stale-bytecode `NoSuchMethodError` trap).

---

## 13. Out of scope (this program)
Tier-2 connectors (GitHub/GitLab/Jenkins); **Bedrock/Vertex LLM transports**; Jira Cloud adapter; dropping the Java dep / non-IDEA IDEs; freezing the API for external consumers + Marketplace publish (deferred Phase 5); `run_gradle_task` IDE-native parity.

---

## 14. Open questions
1. ~~§10 sequencing~~ — **RESOLVED** (internal-first, 2026-06-22).
2. Plugin A name / plugin ID / vendor (for the eventual public release).
3. License (Apache-2.0 likely, given the Cline base).
4. Plugin B distribution inside the company (private plugin repo `updatePlugins.xml` vs ZIP).
5. The exact §6 seam method-set — finalize during the Phase-0 plan (it's wider than three methods; shape it so Phase 4's native impl + persisted-format migration drop in without reopening it).

---

## 15. Risks
- **Public-surface lockstep (not removed by internal-first):** B compiling against A's `public` EP surface pins those signatures de-facto; every A change forces a B recompile. Mitigated by `@InternalApi` discipline + keeping the surface narrow; **not** eliminated — §10.
- **`@State` default-blanking = silent behavior loss for existing installs** — the sharpest near-term risk; gated behind the §8.8 migration (Phase-0/1).
- **Native-LLM workstream is the critical path and was underestimated** — it's a core-loop + streaming + persistence + transport change (§6), now scoped as Phase 4 with explicit line items (NativeProtocol-through-loop, persisted-format migration, proxy-aware client). Effort ~4–6 months whole-program.
- **Contribution-EP safety hole** — a B write tool not plan-mode-gated; mitigated by the self-declared-safety-prop refactor before B contributes write tools.
- **Bedrock/Vertex transport** (SigV4/OAuth2 + IDE-proxy/truststore bypass) — explicitly Tier-2; do not promise it as "free."
- **Build-cache trap** on suspend-signature changes → `--no-build-cache`.
- **Scope creep into Tier 2** — hold the line at Tier 1 for v1.

---

## 16. Phase 0b-2 implementation note (2026-06-23)

**Resolved design: genuinely-neutral dual-implementation.** `VcsHostClient` and `CiService` are independent neutral interfaces (in `:core`) with their respective impls (`BitbucketServiceImpl` / `BambooServiceImpl`) implementing both the vendor interface and the neutral seam concurrently. No behavior changes in 0b-2; no consumer or EP registration yet — deferred to the phase that adds the first neutral consumer (same pattern as `NativeProtocol` pre-Phase-4). Both interfaces are `public` + `@InternalApi` (unfrozen-by-policy).

**Documented exclusions / deviations:**

- `getDefaultBranch` / default-reviewer resolution — NOT on `VcsHostClient` in 0b-2. These live on the lower `BitbucketBranchClient` / `DefaultBranchResolver`; threading them through a neutral op is a Phase-1 (de-convention) task.
- `getLinkedJiraIssues` / `getRequiredBuilds` — intentionally NOT on `VcsHostClient`. They are Atlassian-vendor-coupled (Bitbucket↔Jira link plugin; Bamboo plan-key-keyed required-builds conditions) and remain on `BitbucketService`.
- `BuildResultData`-family package relocation — still in `core/model/bamboo/`. Relocation to `core/model/ci/` is deferred; the neutral `PipelineData`/`CiGroupData` DTOs introduced in 0b-2 already live at `core/model/`.
- **No default values on any `VcsHostClient` parameter** — `BitbucketService` already declares the defaults; redeclaring them on `VcsHostClient` triggers Kotlin `MULTIPLE_DEFAULTS_INHERITED_FROM_SUPERTYPES` on `BitbucketServiceImpl`. Applied uniformly across all 39 methods on the interface. (Discovery: Task 4; applied to `VcsHostClient` in Task 5.)
- **No default for `maxResults` on `CiService.getRecentBuilds`** — same `MULTIPLE_DEFAULTS_INHERITED_FROM_SUPERTYPES` constraint applies where `BambooService` already declares `maxResults = 10`. Every existing call site passes both args explicitly, so behavior is unchanged. Default stays on `BambooService`; restore on `CiService` only if `BambooService` drops it first. (Discovery: Task 4.)

## 17. Phase 0b-3 implementation note (2026-06-24)

**Resolved: safety-props migration + thin ToolRegistrationService.** The hardcoded `AgentLoop.WRITE_TOOLS` / `HOOK_EXEMPT` sets and `ApprovalPolicy`'s `ALWAYS_PER_INVOCATION` / `SESSION_APPROVABLE` / `APPROVAL_TOOLS` sets were deleted. Every agent tool now self-declares:

- `isMutating: Boolean` — true if the tool mutates state and must be blocked in plan mode (consumed by the AgentLoop execution guard + schema filter). Equivalent to the spec's `isWriteTool`.
- `isHookExempt: Boolean` — true if PreToolUse/PostToolUse hooks should be skipped (replaces `AgentLoop.HOOK_EXEMPT`).
- `requiresApproval: Boolean` — true if the loop-level approval gate must be passed before execution.
- `allowSessionApproval: Boolean` — true if the user can grant "allow for this session" (false → per-invocation).

Consumed via `ApprovalPolicy.forTool(tool)` in `AgentLoop`. No behavior change for plugin A tools.

**Thin-host decision.** The `agentToolContributor` EP is hosted by a thin project-scoped `ToolRegistrationService` (`@Service(PROJECT)`). The EP iteration and per-contributor isolation logic live in the pure `ToolContributionRunner` (no IntelliJ deps — unit-testable). `ToolRegistrationService` only constructs `ToolContributionRunner` and calls `contribute(contributors, registry)`.

**Property names B uses to contribute a safe write tool.** A plugin B tool that mutates state and requires approval declares:
- `override val isMutating = true` — plan-mode blocked + sequential execution
- `override val requiresApproval = true` — shows approval card before execution
- `override val allowSessionApproval = true` (or `false` for per-invocation, like `run_command`)
- `override val isHookExempt = false` (default) — user hooks can observe/veto it

## 18. Phase 0b-4 resolved (2026-06-24)

**What shipped.** Settings-section contribution via the platform `parentId` anchor — no custom EP on Plugin A is needed. B nests its own settings pages under A's "Workflow Orchestrator" group by declaring `<projectConfigurable parentId="workflow.orchestrator" .../>` in B's own `plugin.xml`. The anchor id (`workflow.orchestrator`) is the same string already on A's `WorkflowSettingsConfigurable` registration. A demonstrative `CompanyBSettingsConfigurable` was added to `:plugin-b` and registered this way, proving the mechanism end-to-end at the compile / contract-test level.

**Decision: config-preset widening is DEFERRED to Phase 1.** B's `CompanyBSettingsConfigurable` is a placeholder; it does not yet write any defaults into A's `ConnectionSettings` / `PluginSettings`. The preset (§8.7 "B contributes … its config preset / overrides A's neutral defaults") cannot be implemented until Phase 1 blanks A's hardcoded defaults via the `@State` migration (§8.8) — there is nothing neutral to override yet. Widening B's configurable to apply defaults is a Phase-1 task that follows the migration.

**No custom A-side settings EP was needed.** The platform `parentId` mechanism is sufficient for B to contribute pages. A does not need a dedicated EP for settings-section contribution.

**CI coverage vs. runtime appearance.** Three things are CI-verified: (i) `CompanyBSettingsConfigurable` compiles and the class exists in `:plugin-b`; (ii) `WorkflowSettingsConfigurable` carries `id = "workflow.orchestrator"` in A's `plugin.xml` (pinned by `SettingsAnchorContractTest` in `:konsist`); (iii) B's `plugin.xml` declares `parentId="workflow.orchestrator"` with the matching FQN. CI does **not** verify runtime rendering — the platform resolves parent/child silently and falls back to "Other Settings" on a bad parentId without any error. **Runtime page-appearance under the "Workflow Orchestrator" group remains a runIde smoke (PENDING-USER).**


---

## 19. Phase 1a resolved (2026-06-24)

**De-convention migration mechanism + `defaultTargetBranch`.** `SettingsMigration` bumped v1→v2: upgraders (`settingsSchemaVersion >= 1`) whose `defaultTargetBranch` still equals the new neutral default `"main"` are seeded the legacy `"develop"` so behavior is preserved; fresh installs (`== 0`) keep `"main"`. Global `defaultTargetBranch` has no settings UI, so the "field == neutral default" seed-guard is unambiguous. Per-repo / UI / prefetch `"develop"` literals → `"main"` for new repos (existing repos carry explicit persisted values; no list-migration — the resolver never reads the per-repo field, the UI materializes it on save, and `CreatePrPrefetch` falls back to the literal not the persisted default). Cross-module `"develop"` fallbacks neutralized too: `SprintDashboardPanel` (`:jira`), `BuildDashboardPanel` (`:bamboo`), and `ProjectContextTool` (`:agent`, where the `!= "develop"` display-suppression now tracks the neutral default). `seedLegacyConventionDefaults` is the reusable hook for future de-conventions (add a `if (field == neutralDefault) field = legacyLiteral` line + bump `CURRENT_VERSION`).

**Deferred to Phase 2 (module-coupled):** `bambooBuildVariableName` (consumers in `:automation` already fall back to `"DockerTagsAsJSON"`; blanking the `:core` default is inert until `:automation` carves to B) and `quickClipboardChips` (resolved by `:handover`; always persisted via the `init` block, so no migration — only the init list changes). The B config preset (which would supply these to NEW company installs) was already deferred to Phase 2 (§18 + §11).

**Excluded from Phase 1 (USER-confirmed 2026-06-24):** `ticketTransitionDefault*StatusName` / `postCommitTransitionTriggerStatuses` (generic Jira statuses, not company conventions; their defaults exist for out-of-box UX — `postCommit…`'s own KDoc says so) and `branchPattern` / `jiraBoardType` (reasonable generic defaults, not in §7.2's settings list). `WorkflowIntent`'s enum status aliases stay (they drive status-matching, not defaults).

**Rollout precondition (accepted).** The migration treats `settingsSchemaVersion == 0` as a fresh install. This is correct because all internal builds ship from `feature/plugin-split`, which already contains the 0a sentinel-stamp — every real install is at `>= 1` before receiving 1a. A pre-0a→1a direct jump (the only mis-classified case) does not occur in the internal-first rollout, and there are no pre-existing open-source installs.

**Process:** plan 3-round opus-reviewed (platform/bytecode + completeness + skeptic); the bytecode review decompiled IntelliJ 2025.1.7 to confirm omit-default/serialize-on-seed AND verified `ProjectActivity` runs after `loadState`. Executed subagent-driven (per-task two-stage review); the Task-2 review caught a missed `else`-branch prefetch literal (green tests didn't — no test covers that path), fixed before merge.
