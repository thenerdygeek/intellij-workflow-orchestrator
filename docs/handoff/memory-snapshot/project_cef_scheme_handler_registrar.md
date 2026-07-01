---
name: CEF scheme handler must go through WorkflowAgentSchemeRegistrar
description: Any new CEF surface (FileEditor, JBCefBrowser panel) registering an http://workflow-agent handler must call WorkflowAgentSchemeRegistrar.ensureRegistered() — not CefApp.registerSchemeHandlerFactory directly
type: project
originSessionId: 57564df1-ab5b-4dc2-97ba-8dd4cba1329c
---
`CefApp.getInstance().registerSchemeHandlerFactory(scheme, domain, factory)` is JVM-global and **silently replaces** any prior factory for the same `(scheme, domain)`. It does NOT throw on duplicate. The `// Already registered — OK` catch blocks that existed pre-2026-05-04 across `AgentPlanEditor` and `AgentVisualizationEditor` were misleading and load-bearing for an image-attachment regression — the editors' static-asset-only factories stomped on `AgentCefPanel`'s upload-aware factory and the `/upload/<sha256>` endpoint silently 404'd.

**Why:** This was a real production bug. User-paste + plus-button + drag-drop image attachment all broke as soon as the user opened a plan editor or `.agentviz` file in the same IDE process. Took a full diagnosis pass to find. Fixed in commit `13d0c0d0` by introducing `WorkflowAgentSchemeRegistrar` (`agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/WorkflowAgentSchemeRegistrar.kt`) — a singleton that registers the dispatching factory exactly once per JVM. The chat panel installs its session-bound upload handler factory via `setUploadHandlerFactory(closure)`; other CEF surfaces only call `ensureRegistered()`.

**How to apply:**
- Any new CEF surface (FileEditor, JBCefBrowser panel, dialog with embedded webview) that needs to load resources from `http://workflow-agent` MUST call `WorkflowAgentSchemeRegistrar.ensureRegistered()` instead of `CefApp.registerSchemeHandlerFactory(...)` directly. There is no exception to this rule — even if the new surface only needs static assets and "doesn't care about uploads," registering its own factory will break uploads in the chat panel.
- If a new surface needs URL-routed dispatch beyond `/upload/*` (e.g., `/api/something`), extend `DispatchingFactory.dispatch(url)` in the registrar — do NOT register a parallel factory.
- The fix is testable via `WorkflowAgentSchemeRegistrar.DispatchingFactory.dispatch(url: String?)`. Don't try to mock `CefRequest` or `CefResourceHandler` — JCEF's `org.cef.handler` and `org.cef.network` modules are not opened to unnamed modules and MockK cannot subclass them. Use the URL-only dispatch helper plus a hand-written `CefResourceHandler` stub for sentinel assertions (see `WorkflowAgentSchemeRegistrarTest`).
- `CefResourceHandler` instances carry per-request state (write-position counters). Always pass a `() -> CefResourceHandler` factory closure to `setUploadHandlerFactory`, NOT a single instance. Reusing one handler across concurrent requests would corrupt response bodies.
