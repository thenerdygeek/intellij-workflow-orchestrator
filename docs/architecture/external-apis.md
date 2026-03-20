# External API Integration

## Service Overview

```mermaid
flowchart TD
    subgraph Plugin ["Workflow Orchestrator Plugin"]
        HCF["HttpClientFactory"]
        POOL["sharedPool<br/>(ConnectionPool: 5 connections, 3min keep-alive)"]
        CACHE["sharedCache<br/>(10MB HTTP response cache)"]
        AUTH_B["AuthInterceptor<br/>(Bearer)"]
        AUTH_BA["AuthInterceptor<br/>(Basic)"]
        AUTH_T["AuthInterceptor<br/>(Token)"]
        RETRY["RetryInterceptor"]
        CODY_PROC["Cody CLI Process<br/>(JSON-RPC stdio)"]
        AGENT_LLM["Agent LLM Client<br/>(HTTP POST)"]

        HCF --> POOL
        HCF --> CACHE
        POOL --> AUTH_B & AUTH_BA & AUTH_T
        AUTH_B --> RETRY
        AUTH_BA --> RETRY
        AUTH_T --> RETRY
    end

    subgraph External ["External Services (Self-Hosted)"]
        JIRA["Jira Server<br/><b>REST API v2</b><br/>Bearer PAT"]
        BAMBOO["Bamboo Server<br/><b>REST API</b><br/>Bearer PAT"]
        SONAR["SonarQube<br/><b>Web API</b><br/>Bearer User Token"]
        BB["Bitbucket Server<br/><b>REST API v1</b><br/>Bearer HTTP Access Token"]
        NEXUS["Nexus Registry<br/><b>Docker v2 API</b><br/>Basic Auth"]
        SG["Sourcegraph<br/><b>GraphQL + Cody</b><br/>Access Token"]
    end

    RETRY -->|HTTPS| JIRA
    RETRY -->|HTTPS| BAMBOO
    RETRY -->|HTTPS| SONAR
    RETRY -->|HTTPS| BB
    RETRY -->|HTTPS| NEXUS
    CODY_PROC -->|"stdio<br/>JSON-RPC"| SG
    AGENT_LLM -->|"HTTPS<br/>LLM Chat Completions"| SG

    style JIRA fill:#6b2027,stroke:#f48771,color:#d4d4d4
    style BAMBOO fill:#6b2027,stroke:#f48771,color:#d4d4d4
    style SONAR fill:#6b2027,stroke:#f48771,color:#d4d4d4
    style BB fill:#6b2027,stroke:#f48771,color:#d4d4d4
    style NEXUS fill:#6b2027,stroke:#f48771,color:#d4d4d4
    style SG fill:#6b2027,stroke:#f48771,color:#d4d4d4
    style HCF fill:#264f78,stroke:#569cd6,color:#d4d4d4
```

## Authentication Methods

| Service | Auth Type | Header / Mechanism | Token Storage |
|---|---|---|---|
| Jira Server | Bearer PAT | `Authorization: Bearer <PAT>` | PasswordSafe |
| Bamboo Server | Bearer PAT | `Authorization: Bearer <PAT>` | PasswordSafe |
| SonarQube | Bearer User Token | `Authorization: Bearer <user-token>` | PasswordSafe |
| Bitbucket Server | Bearer HTTP Access Token | `Authorization: Bearer <token>` | PasswordSafe |
| Nexus Registry | Basic Auth | `Authorization: Basic <base64(token:)>` | PasswordSafe |
| Sourcegraph / Cody | Access Token | Passed in `ExtensionConfiguration.accessToken` via JSON-RPC | PasswordSafe |

All tokens are stored in `PasswordSafe` (OS keychain on macOS, Credential Manager on Windows). They are **never** persisted in XML settings files.

## Connection Pooling

```mermaid
flowchart LR
    subgraph Factory ["HttpClientFactory"]
        BASE["Base OkHttpClient"]
        BASE --> JIRA_C["Jira Client<br/>(Bearer)"]
        BASE --> BAMBOO_C["Bamboo Client<br/>(Bearer)"]
        BASE --> SONAR_C["Sonar Client<br/>(Bearer)"]
        BASE --> BB_C["Bitbucket Client<br/>(Bearer)"]
        BASE --> NEXUS_C["Nexus Client<br/>(Basic)"]
    end

    subgraph Shared ["Shared Resources"]
        CP["ConnectionPool<br/>max 5 idle, 3min TTL"]
        HC["HTTP Cache<br/>10MB, ETag/304"]
    end

    BASE --> CP
    BASE --> HC

    style CP fill:#264f78,stroke:#569cd6,color:#d4d4d4
    style HC fill:#264f78,stroke:#569cd6,color:#d4d4d4
```

All per-service clients are created via `OkHttpClient.newBuilder()` from the shared base, inheriting the connection pool and cache. Only the `AuthInterceptor` differs (Bearer vs Basic).

## Key Endpoints by Service

### Jira Server (REST API v2)

| Method | Endpoint | Used For |
|---|---|---|
| `GET` | `/rest/api/2/myself` | Test connection |
| `GET` | `/rest/agile/1.0/board/{boardId}/sprint/{sprintId}/issue` | Sprint tickets |
| `GET` | `/rest/api/2/issue/{key}?expand=issuelinks` | Ticket details |
| `POST` | `/rest/api/2/issue/{key}/transitions` | Transition status |
| `POST` | `/rest/api/2/issue/{key}/comment` | Add comment |
| `POST` | `/rest/api/2/issue/{key}/worklog` | Log time |

### Bamboo Server (REST API)

| Method | Endpoint | Used For |
|---|---|---|
| `GET` | `/rest/api/latest/currentUser` | Test connection |
| `GET` | `/rest/api/latest/result/{planKey}/latest` | Latest build |
| `GET` | `/rest/api/latest/result/{buildKey}` | Specific build + stages |
| `GET` | `/rest/api/latest/result/{buildKey}/log` | Build log |
| `POST` | `/rest/api/latest/queue/{planKey}` | Trigger build |

### SonarQube (Web API)

| Method | Endpoint | Used For |
|---|---|---|
| `GET` | `/api/authentication/validate` | Test connection |
| `GET` | `/api/measures/component_tree?component={key}&metricKeys=...` | Coverage data |
| `GET` | `/api/measures/component?component={key}&metricKeys=...` | Project-level health metrics |
| `GET` | `/api/issues/search?componentKeys={key}&resolved=false` | Open issues |
| `GET` | `/api/qualitygates/project_status?projectKey={key}` | Quality gate |
| `GET` | `/api/components/search?qualifiers=TRK&q={query}` | Project search/picker |
| `GET` | `/api/project_branches/list?project={key}` | Branch listing |
| `GET` | `/api/ce/activity?component={key}` | Compute engine analysis tasks |
| `GET` | `/api/new_code_periods/show?project={key}` | New code period definition |
| `GET` | `/api/sources/lines?key={fileKey}&from={line}&to={line}` | Line-level coverage (on-demand) |

### Bitbucket Server (REST API v1)

| Method | Endpoint | Used For |
|---|---|---|
| `GET` | `/rest/api/1.0/users` | Test connection |
| `POST` | `/rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests` | Create PR |
| `GET` | `/rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests?role.1=AUTHOR&username.1={user}` | List user's PRs |
| `GET` | `/rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{id}` | PR detail |
| `PUT` | `/rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{id}` | Update PR (preserves title/reviewers) |
| `GET` | `/rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{id}/merge` | Merge preconditions |
| `POST` | `/rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{id}/merge` | Merge with strategy |
| `POST` | `/rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{id}/approve` | Approve PR |
| `DELETE` | `/rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{id}/approve` | Unapprove PR |
| `POST` | `/rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{id}/decline` | Decline PR |
| `GET` | `/rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{id}/activities` | Activities/comments |
| `GET` | `/rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{id}/diff` | PR diff |
| `GET` | `/rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{id}/changes` | Changed files |
| `GET` | `/rest/api/1.0/projects/{proj}/repos/{repo}/settings/pull-requests/git` | Merge strategies |
| `GET` | `/plugins/servlet/applinks/whoami` | Current username |

### Nexus Docker Registry (Docker v2 API)

| Method | Endpoint | Used For |
|---|---|---|
| `GET` | `/v2/` | Test connection |
| `GET` | `/v2/{name}/tags/list` | List docker tags |
| `HEAD` | `/v2/{name}/manifests/{tag}` | Validate tag exists |

### Cody Enterprise (JSON-RPC over stdio)

| Direction | Method | Used For |
|---|---|---|
| Plugin to Agent | `initialize` | Auth + capabilities setup |
| Plugin to Agent | `initialized` | Post-init notification |
| Plugin to Agent | `chat/new` | Create chat session |
| Plugin to Agent | `chat/submitMessage` | Send message (commit msg, PR desc) |
| Plugin to Agent | `editCommands/code` | "Fix with Cody" edits |
| Plugin to Agent | `editTask/accept` | Accept AI edit |
| Plugin to Agent | `editTask/undo` | Reject AI edit |
| Plugin to Agent | `shutdown` | Graceful shutdown |
| Agent to Plugin | `workspace/edit` | Apply file edits |
| Agent to Plugin | `secrets/get` | Request stored credentials |

### Sourcegraph Enterprise (LLM Chat Completions)

The `:agent` module communicates directly with the Sourcegraph Enterprise LLM API (not through the Cody CLI agent). This is an OpenAI-compatible chat completions endpoint.

| Method | Endpoint | Used For |
|---|---|---|
| `POST` | `/.api/llm/chat/completions` | ReAct loop LLM calls, LLM-powered context compression |

**Auth:** `Authorization: token <sourcegraph-access-token>`

**Constraints:**
- 190K input tokens (probed), output limit varies per model and instance — not hardcoded
- No `system` role -- converted to user messages with `<system_instructions>` tags
- No `tool_choice` parameter
- Strict user/assistant message alternation required
- Message sanitization performed in `SourcegraphChatClient.sanitizeMessages()`
- Token reconciliation: agent calibrates heuristic estimates with API's actual `usage.prompt_tokens`
- Used by: `:agent` module for ReAct loop and LLM-powered compression
