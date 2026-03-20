# :cody Module

Standalone Cody CLI agent for AI-powered code fixes, commit messages, and context enrichment.

## JSON-RPC Protocol

Protocol: JSON-RPC 2.0 over stdin/stdout with Content-Length framing
Binary: `cody api jsonrpc-stdio` (from @sourcegraph/cody npm package)
On Windows: launched via `cmd.exe /c cody.cmd api jsonrpc-stdio`

### Key methods (Plugin -> Agent)
- `initialize` — auth + capabilities setup (params: ClientInfo)
- `initialized` — notification after init (params: **null required**)
- `chat/new` — create chat session (params: **null required**)
- `chat/submitMessage` — send message, get response (contextItems for file context)
- `textDocument/didOpen` / `textDocument/didChange` — file sync notifications
- `shutdown` — graceful shutdown (params: **null required**)

### Key methods (Agent -> Plugin)
- `workspace/edit` / `textDocument/edit` — agent sends file edits to apply
- `secrets/get` / `secrets/store` — agent requests/stores credentials
- `debug/message` — debug logging (silently handled)

**Critical:** Zero-arg methods (chat/new, initialized, shutdown, exit) MUST send `"params": null`. LSP4J achieves this with `Void?` parameter. Without this, the agent returns "chatID is undefined".

**Note:** `editCommands/code` is NOT supported by the CLI agent. Use chat-based fix approach instead (submit fix instructions via `chat/submitMessage`).

### ClientCapabilities
```
chat: "streaming", completions: "none", git: "none", progressBars: "none",
edit: "enabled", editWorkspace: "enabled", untitledDocuments: "none",
showDocument: "none", codeLenses: "none", showWindowMessage: "notification",
secrets: "client-managed"
```

## Architecture

- `CodyAgentManager` — lifecycle management (start, stop, restart)
- `StandaloneCodyAgentProvider` — process spawning + JSON-RPC transport setup
- `CodyAgentServer` / `CodyAgentClient` — LSP4J interfaces for RPC methods
- `CodyChatService` — chat session management
- `CodyEditService` — applies AI edits to files via `CodyEditApplier`
- `CodyTextGenerationService` — text generation (commit messages, PR descriptions)
- `CodyContextService` / `CodyContextServiceLogic` — builds context items with line ranges
- `PsiContextEnricher` — PSI-based code intelligence (class hierarchy, usages, imports)
- `SpringContextEnricher` / `SpringContextEnricherImpl` — Spring-aware context (beans, endpoints, @Transactional)

## UI

- `CodyGutterAction` — "Fix with Cody" gutter icon on Sonar issues
- `CodyIntentionAction` — Alt+Enter quick fix integration
- `CodyTestGenerator` — "Generate Test with Cody" gutter action
- `GenerateCommitMessageAction` — Cody-generated commit messages in VCS dialog
