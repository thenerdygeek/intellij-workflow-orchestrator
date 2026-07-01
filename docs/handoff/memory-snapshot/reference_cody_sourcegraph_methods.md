---
name: Sourcegraph Cody Plugin Server Methods
description: Complete list of JSON-RPC methods exposed by the Sourcegraph Cody JetBrains plugin's server proxy (captured 2026-03-14)
type: reference
---

# Sourcegraph Cody Plugin — Exposed Server Proxy Methods

Captured from reflection logs on 2026-03-14 (Sourcegraph Cody JetBrains plugin).

## Key Architecture Notes

- **CodyAgentService** fields: `codyAgent: CompletableFuture`, `agentError: AtomicReference`, `clientCapabilities: ClientCapabilities`
- **CodyAgentService** key methods: `isConnected(Project) -> boolean` (static), `withAgent(Project, Consumer)`, `startAgent(long)`, `stopAgent()`, `restartAgent(long)`
- **CodyAgent** fields: `server: CodyAgentServer`, `client: CodyAgentClient`, `launcher: Launcher`, `connection: AgentConnection`, `listeningToJsonRpc: Future`
- **CodyAgent** key methods: `getServer() -> CodyAgentServer`, `getClient() -> CodyAgentClient`, `isConnected() -> boolean`

## Naming Convention

Sourcegraph uses **underscore naming** for methods: `chat_new`, `editTask_accept`, etc.
Our plugin uses **camelCase**: `chatNew`, `editTaskAccept`.
The `SourcegraphServerInvocationHandler` converts camelCase → underscore automatically.

## Server Proxy Methods (92 total)

### Chat
| Method | Params | Returns | Notes |
|--------|--------|---------|-------|
| `chat_new` | `Void` | `CompletableFuture` | Creates new chat session |
| `chat_web_new` | `Void` | `CompletableFuture` | Webview-based chat (JetBrains-specific) |
| `chat_sidebar_new` | `Void` | `CompletableFuture` | Sidebar chat |
| `chat_models` | `Chat_ModelsParams` | `CompletableFuture` | Available models |
| `chat_setModel` | `Chat_SetModelParams` | `CompletableFuture` | Set chat model |
| `chat_export` | `Chat_ExportParams` | `CompletableFuture` | Export transcript |
| `chat_import` | `Chat_ImportParams` | `CompletableFuture` | Import transcript |
| `chat_delete` | `Chat_DeleteParams` | `CompletableFuture` | Delete chat |

**NOTE:** `chat/submitMessage` is NOT exposed as a named method. Use the generic `request("chat/submitMessage", params)` fallback.

### Edit Tasks
| Method | Params | Returns |
|--------|--------|---------|
| `editTask_accept` | `String` | `CompletableFuture` |
| `editTask_cancel` | `String` | `CompletableFuture` |
| `editTask_undo` | `String` | `CompletableFuture` |
| `editTask_retry` | `String` | `CompletableFuture` |
| `editTask_getTaskDetails` | `String` | `CompletableFuture` |
| `editTask_getFoldingRanges` | `GetFoldingRangeParams` | `CompletableFuture` |
| `editTask_start` | `Void` | `CompletableFuture` |

### Code Actions
| Method | Params | Returns |
|--------|--------|---------|
| `codeActions_provide` | `CodeActions_ProvideParams` | `CompletableFuture` |
| `codeActions_trigger` | `String` | `CompletableFuture` |

### Commands
| Method | Params | Returns |
|--------|--------|---------|
| `command_execute` | `ExecuteCommandParams` | `CompletableFuture` |
| `commands_custom` | `Commands_CustomParams` | `CompletableFuture` |
| `commands_explain` | `Void` | `CompletableFuture` |
| `commands_smell` | `Void` | `CompletableFuture` |
| `customCommands_list` | `Void` | `CompletableFuture` |
| `editCommands_code` | — | `CompletableFuture` |

### Text Document
| Method | Params | Returns |
|--------|--------|---------|
| `textDocument_didOpen` | `ProtocolTextDocument` | `void` |
| `textDocument_didChange` | `ProtocolTextDocument` | `void` |
| `textDocument_didClose` | `ProtocolTextDocument` | `void` |
| `textDocument_didFocus` | `TextDocument_DidFocusParams` | `void` |
| `textDocument_didRename` | `TextDocument_DidRenameParams` | `void` |
| `textDocument_didSave` | `TextDocument_DidSaveParams` | `void` |
| `textDocument_change` | `ProtocolTextDocument` | `CompletableFuture` |

### Lifecycle
| Method | Params | Returns |
|--------|--------|---------|
| `initialize` | `ClientInfo` | `CompletableFuture` |
| `initialized` | `Void` | `void` |
| `shutdown` | `Void` | `CompletableFuture` |
| `exit` | `Void` | `void` |

### Generic
| Method | Params | Returns | Notes |
|--------|--------|---------|-------|
| `request` | `String, Object` | `CompletableFuture` | Generic JSON-RPC dispatch — use for methods not on the interface |

### Other
- `autocomplete_execute`, `autocomplete_clearLastCandidate`, `autocomplete_completionAccepted`, `autocomplete_completionSuggested`
- `attribution_search`
- `diagnostics_publish`
- `extensionConfiguration_change`, `extensionConfiguration_didChange`, `extensionConfiguration_getSettingsSchema`, `extensionConfiguration_status`
- `extension_reset`
- `featureFlags_getFeatureFlag`
- `git_codebaseName`
- `graphql_currentUserId`, `graphql_getRepoId`, `graphql_getRepoIdIfEmbeddingExists`, `graphql_getRepoIds`
- `internal_getAuthHeaders`
- `progress_cancel`
- `secrets_didChange`
- `webview_didDispose`, `webview_didDisposeNative`, `webview_receiveMessageStringEncoded`, `webview_resolveWebviewView`
- `window_didChangeFocus`
- `workspace_didCreateFiles`, `workspace_didDeleteFiles`, `workspace_didRenameFiles`
- `workspaceFolder_didChange`
- Various `testing_*` methods (18 total)
