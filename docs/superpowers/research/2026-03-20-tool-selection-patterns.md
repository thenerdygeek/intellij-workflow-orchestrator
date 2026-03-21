# Tool Selection & Management Patterns in Production AI Coding Agents

**Date:** 2026-03-20
**Purpose:** Concrete patterns for how production agents handle tool selection, user control, and governance

---

## 1. Claude Code Tool Management

### Permission System (Allow / Ask / Deny)
Claude Code uses a three-tier permission rule system evaluated in strict order: **deny -> ask -> allow** (first match wins, deny always takes precedence).

**Mechanism:** JSON configuration in `.claude/settings.json` (project-level) or `~/.claude.json` (user-level), plus CLI flags.

**Configuration formats:**
```json
// .claude/settings.json (project-level)
{
  "permissions": {
    "deny": ["Bash(rm *)", "Bash(sudo *)", "WebFetch"],
    "ask": ["Edit"],
    "allow": ["Read", "Glob", "Grep"]
  }
}
```

**CLI override per session:**
```bash
claude --allowedTools "Read" "Edit" --disallowedTools "Bash(curl *)" "Bash(wget *)"
```

**Hierarchy enforcement:**
- If a tool is denied at any level, no other level can allow it
- `--disallowedTools` adds restrictions beyond managed settings
- Project-level deny overrides user-level allow
- Hooks that return "allow" are still subject to deny rules

### MCP Server-Level Control
- Users can disable entire MCP servers per session: `@filesystem disable`, `@playwright disable`
- Disabled servers don't load their schemas, saving context window
- Individual MCP tool disable is NOT yet supported (feature requested in Issue #7328 and #18383)
- Permission scopes can be configured per MCP server in settings

### Token Impact
- Tool definitions are sent in every request — disabling unneeded MCP servers directly reduces prompt tokens
- No dynamic per-request tool filtering based on query content (all enabled tools always sent)

**Best practice:** Use project-level `.claude/settings.json` to deny tools irrelevant to the project. Disable MCP servers not needed for the current session.

**Sources:**
- [Claude Code permissions docs](https://code.claude.com/docs/en/permissions)
- [Claude Code settings docs](https://code.claude.com/docs/en/settings)
- [MCP Tool Filtering feature request #7328](https://github.com/anthropics/claude-code/issues/7328)
- [Individual MCP tool disable request #18383](https://github.com/anthropics/claude-code/issues/18383)

---

## 2. Cursor Tool Management

### Tool Limits
- **Cursor enforces an 80-tool cap** (hard limit on tools sent per request)
- OpenAI API supports up to 128 tools; Claude API ~120
- Performance degrades well before hitting these limits

### Context Window Cost
Real-world example of the problem:
- Playwright MCP: 21 tools
- GitHub MCP: ~40 tools
- Harness MCP v1: ~175 tools
- **Total: ~236 tools** -- well past Cursor's 80-tool cap

EclipseSource analysis shows a standard set of MCP servers can consume **20% of the context window** before the user types a prompt.

### User Control
- Users configure which MCP servers are active in Cursor settings
- No per-tool enable/disable within an MCP server
- No embedding-based dynamic tool selection (tools are statically configured)

**Best practice:** Keep total tool count under 80 for Cursor. Use MCP servers with composite/registry-based designs (like Harness v2) to stay within limits.

**Sources:**
- [Cursor Tool Call Limits Explained](https://apidog.com/blog/cursor-tool-call-limit/)
- [How many tools can an AI Agent have?](https://achan2013.medium.com/how-many-tools-functions-can-an-ai-agent-has-21e0a82b7847)

---

## 3. Cline Tool Permissions

### Granular Auto-Approve Categories
Cline evaluates auto-approve **per tool call**. Five main categories, each independently toggleable:

| Category | What it covers | Default |
|---|---|---|
| **Read** | File and directory reads | Off |
| **Write** | File edits/creation | Off |
| **Execute** | Terminal commands | Off |
| **Browser** | Web automation actions | Off |
| **MCP** | External tool integration | Off |

### Command-Level Control
- "Execute safe commands" — auto-approves safe commands (ls, cat, etc.)
- "Execute all commands" — extends to potentially dangerous commands
- Safe vs. dangerous classification is built into Cline

### Workspace Scoping
- "Read all files" / "Edit all files" only extend the base toggle
- If base read toggle is off, "read all files" does nothing
- `.clineignore` file blocks access to sensitive files/directories entirely

### YOLO Mode
- Removes ALL per-action confirmations
- Auto-approves: file changes, terminal commands, browser actions, MCP tools, mode transitions

### Safety Limits
- `maxAutoApprovedRequests` — circuit breaker on consecutive auto-approved actions
- Once threshold crossed, auto-approval suspends regardless of category settings

**Best practice:** Enable read auto-approve, keep write/execute on ask. Use `.clineignore` for sensitive files. Never use YOLO in production codebases.

**Sources:**
- [Cline Auto Approve & YOLO Mode](https://docs.cline.bot/features/auto-approve)
- [Cline Auto Approve Discussion #368](https://github.com/cline/cline/discussions/368)
- [Cline Access Control](https://deepwiki.com/cline/cline/10.3-access-control-(.clineignore))

---

## 4. Roo Code (Cline Fork) Tool Permissions

Roo Code extends Cline's model with **seven categories** and additional safety guardrails:

| Category | Toggle |
|---|---|
| File reads | Independent |
| File writes | Independent |
| Command execution | Independent |
| MCP calls | Independent (requires per-tool "Always allow" too) |
| Mode switches | Independent |
| Subtask creation | Independent |
| Follow-up questions | Independent |

### Dual MCP Gate
MCP tool auto-approve requires **both**:
1. Global "Always approve MCP tools" toggle = on
2. Each individual tool's "Always allow" = on

### Cost Circuit Breaker
- `allowedMaxRequests` and `allowedMaxCost` act as hard limits
- Once cumulative request count OR API cost exceeds threshold, auto-approval suspends for the entire session

### Command Allow/Deny Lists
```
allowedCommands: string[]  // e.g., ["npm test", "git status"]
deniedCommands: string[]   // e.g., ["rm -rf", "sudo"]
```
Deny rules take precedence (longest-prefix wins).

**Sources:**
- [Roo Code Auto-Approving Actions](https://docs.roocode.com/features/auto-approving-actions)
- [Roo Code Auto-Approve Configuration](https://deepwiki.com/RooCodeInc/Roo-Code/11.3-auto-approve-configuration)

---

## 5. Google Gemini Code Assist Tool Control

### coreTools and excludeTools Settings
```json
{
  "coreTools": ["read_file", "edit_file", "run_command"],
  "excludeTools": ["browser_navigate", "delete_file"]
}
```

- `excludeTools` specifies built-in tools to exclude from agent use
- All Gemini CLI built-in tools are available to agent mode by default
- MCP servers configured separately in Gemini settings JSON
- Agent has access to file system and terminal unless explicitly excluded

### User Control
- Configuration via settings JSON file (VS Code or IntelliJ)
- Users can comment on, edit, and approve plans and tool use during execution
- Per-session override not documented — appears to be config-file driven

**Sources:**
- [Gemini Code Assist Agent Mode](https://developers.google.com/gemini-code-assist/docs/use-agentic-chat-pair-programmer)
- [Agent Mode Overview](https://developers.google.com/gemini-code-assist/docs/agent-mode)

---

## 6. MCP (Model Context Protocol) Tool Management

### Dynamic Tool Discovery
```
Client → Server: tools/list          → Get all available tools
Client → Server: tools/call          → Execute a tool
Server → Client: notifications/tools/list_changed  → Tool list updated
```

- `listChanged` capability flag tells clients the tool list may change during the session
- Clients subscribe to `notifications/tools/list_changed` for real-time updates

### Dynamic Registration Use Cases
- **Authentication state changes** — hide tools when credentials expire
- **Feature availability** — different tools based on user permissions or subscription tier
- **Resource availability** — hide tools when external services are down
- **Context-dependent** — show tools only relevant in certain contexts

### Tool Annotations (Metadata Hints)
MCP tools carry behavioral annotations that inform client auto-approve decisions:

```json
{
  "name": "delete_record",
  "annotations": {
    "title": "Delete Database Record",
    "readOnlyHint": false,
    "destructiveHint": true,
    "idempotentHint": false,
    "openWorldHint": false
  }
}
```

| Annotation | Purpose |
|---|---|
| `readOnlyHint` | Tool doesn't modify environment — candidate for auto-approve |
| `destructiveHint` | Tool may delete/overwrite data — requires confirmation |
| `idempotentHint` | Repeated calls with same args have no additional effect |
| `openWorldHint` | Tool interacts with external entities outside the system |

**Important:** Annotations are **hints, not security controls**. Clients should not rely on them for authorization decisions.

### OAuth Dynamic Client Registration
- Solves the M x N problem (M agents x N servers)
- Agents autonomously discover, register, and authenticate with MCP servers at runtime
- Removes need for manual client setup or hard-coded credentials

**Best practice:** Set `readOnlyHint: true` on read-only tools. Use `destructiveHint: true` on anything that modifies state. Implement `notifications/tools/list_changed` for dynamic environments.

**Sources:**
- [MCP Architecture Overview](https://modelcontextprotocol.io/docs/learn/architecture)
- [MCP Tool Annotations Blog](https://blog.modelcontextprotocol.io/posts/2026-03-16-tool-annotations/)
- [Spring AI Dynamic Tool Updates with MCP](https://spring.io/blog/2025/05/04/spring-ai-dynamic-tool-updates-with-mcp/)
- [Dynamic Tool Discovery in MCP](https://www.speakeasy.com/mcp/tool-design/dynamic-tool-discovery)

---

## 7. GitHub Copilot Embedding-Guided Tool Routing

### The Problem
Giving an agent 40+ tools doesn't make it smarter — it makes it slower and less accurate.

### The Solution: Two-Phase Approach

**Phase 1 — Embedding-Based Tool Clustering:**
1. Generate embeddings for each tool using Copilot's internal embedding model (optimized for semantic similarity)
2. Group tools using cosine similarity into clusters
3. Cache tool embeddings and cluster summaries locally (cheap to recompute)

**Phase 2 — Context-Aware Routing:**
1. Compare query embedding against tool/cluster vector representations
2. Pre-select most semantically relevant candidates before the LLM reasons
3. Expand tool groups only when the query matches their cluster

### Results
| Metric | Improvement |
|---|---|
| Tools sent per request | 40 -> 13 core tools |
| Response latency | -400ms average |
| Tool Use Coverage | 94.5% (embedding selection) |
| SWE-Lancer / SWEbench success | +2-5 percentage points |

### Key Insight
The reduced toolset outperforms the full toolset. Fewer, better-selected tools lead to better reasoning and lower latency.

**Sources:**
- [How we're making GitHub Copilot smarter with fewer tools](https://github.blog/ai-and-ml/github-copilot/how-were-making-github-copilot-smarter-with-fewer-tools/)

---

## 8. Harness MCP v2: Registry-Based Dispatch

### The Problem
Harness MCP v1 exposed ~175 tools, consuming ~26% of a 200K-token context window before the user typed anything.

### The Solution: Composite Tools with Registry Dispatch
Instead of one tool per API endpoint, v2 uses **11 composite tools** with a registry that routes to 125+ resource types.

| Metric | v1 | v2 |
|---|---|---|
| Tool count | ~175 | 11 |
| Context consumption | ~26% | ~1.6% |
| Resource types supported | ~175 | 125+ |

### Architecture
- The LLM reasons about **what** to do (high-level intent)
- The server handles **how** to do it (specific API calls via registry lookup)
- Registry-based dispatch means adding new resource types doesn't add new tools

**Sources:**
- [Designing MCP for the Age of AI Agents](https://www.harness.io/blog/harness-mcp-server-redesign)

---

## 9. Academic Research: Tool Selection Optimization

### AutoTool (Graph-Based, arxiv 2511.14650)
Exploits **tool usage inertia** — the tendency of tool invocations to follow predictable sequential patterns.

- Constructs a directed graph from historical agent trajectories
- Nodes = tools, edges = transition probabilities
- Integrates parameter-level information to refine tool input generation
- **Result:** Reduces inference costs by up to 30% while maintaining task completion rates

### AutoTool (Dynamic Selection, arxiv 2512.13278)
- 200K training dataset with explicit tool-selection rationales across 1,000+ tools
- Dual-phase optimization: supervised + RL-based trajectory stabilization, then KL-regularized Plackett-Luce ranking
- **Result:** +6.4% math/science, +4.5% search QA, +7.7% code gen, +6.9% multimodal

### JetBrains "Complexity Trap" Research (NeurIPS 2025)
Studied context management strategies for LLM agents:

| Strategy | Cost Reduction | Performance |
|---|---|---|
| Observation Masking | ~50% | No significant degradation |
| LLM Summarization | ~50% | No significant degradation |
| Hybrid (both) | ~57-61% | No significant degradation |

**Key finding:** Simple observation masking (truncating/hiding tool outputs) is as effective as LLM-based summarization for context management. The "complex" approach doesn't justify its cost.

### General Optimization Strategies
1. **Embedding-based pre-filtering** — match query against tool description embeddings
2. **Task-type pre-filtering** — route to tool subsets based on inferred task category
3. **Smaller LLM for tool selection** — use a cheap model to pick tools, expensive model to use them
4. **Tool description summarization** — compress verbose tool definitions
5. **Observation masking** — truncate/hide intermediate tool outputs to save context

**Sources:**
- [AutoTool: Efficient Tool Selection (arxiv 2511.14650)](https://arxiv.org/abs/2511.14650)
- [AutoTool: Dynamic Tool Selection (arxiv 2512.13278)](https://arxiv.org/abs/2512.13278)
- [JetBrains: Cutting Through the Noise](https://blog.jetbrains.com/research/2025/12/efficient-context-management/)
- [The Complexity Trap (GitHub)](https://github.com/JetBrains-Research/the-complexity-trap)

---

## 10. Enterprise Tool Governance

### GitHub Enterprise AI Controls (GA Feb 2026)
- **Consolidated AI administration panel** — single view for all AI-related policies
- **Decentralized management** — enterprise custom roles with fine-grained permissions
- **Agent activity visibility** — search/filter agentic sessions by agent, org, and time
- **Custom agent APIs** — programmatically apply enterprise-wide agent definitions
- **MCP governance** — planned (not yet shipped)

### Microsoft Agent 365 (GA May 2026, $99/user/month)
- AI, security, and agent governance in a single platform
- Includes Entra for identity, Purview for compliance, Defender for security
- Hierarchical controls at team, project, and customer level

### Entro Security AGA (March 2026)
- Inventory, ownership, least privilege, auditability, and enforcement for AI agents
- Governs AI agents and AI access across enterprise systems
- Focused on security and identity teams

### Common Enterprise Patterns
| Control Level | What's Governed |
|---|---|
| **Organization** | Which AI agents are allowed, which models, spending caps |
| **Team/Project** | Which tools/MCP servers are available, cost budgets |
| **User** | Personal tool preferences, auto-approve settings |
| **Session** | Runtime overrides, circuit breakers |

**Sources:**
- [GitHub Enterprise AI Controls GA](https://github.blog/changelog/2026-02-26-enterprise-ai-controls-agent-control-plane-now-generally-available/)
- [Entro Security AGA](https://www.helpnetsecurity.com/2026/03/19/entro-agentic-governance-administration/)
- [Microsoft Agent 365](https://www.aicerts.ai/news/microsoft-agent-365-the-enterprise-ai-governance-tool/)

---

## Summary: Pattern Comparison Matrix

| Feature | Claude Code | Cursor | Cline/Roo | Gemini | Copilot |
|---|---|---|---|---|---|
| Per-tool enable/disable | Yes (via rules) | No (server-level) | Yes (categories) | Yes (excludeTools) | N/A (auto-selected) |
| Per-session override | Yes (CLI flags) | No | No | No | No |
| Dynamic tool selection | No | No | No | No | Yes (embeddings) |
| MCP tool annotations | N/A | N/A | MCP "Always allow" | N/A | N/A |
| Deny-first precedence | Yes | N/A | Yes | N/A | N/A |
| Cost circuit breaker | No | No | Yes (Roo) | No | No |
| Enterprise governance | No | No | No | No | Yes (control plane) |
| Context savings mechanism | Disable MCP servers | N/A | N/A | excludeTools | Embedding routing |

---

## Recommendations for Workflow Orchestrator Plugin

Based on the research, these patterns are most relevant for the Phase 3 agentic AI implementation:

### 1. Tool Category System (from Cline/Roo)
Classify tools into categories with independent approval settings:
- **Read** (file reads, API queries) — safe to auto-approve
- **Write** (file edits, API mutations) — require confirmation
- **Execute** (terminal commands, builds) — require confirmation
- **External** (Jira transitions, PR creation, Bamboo triggers) — require confirmation

### 2. MCP Tool Annotations (from MCP spec)
Annotate every tool with `readOnlyHint`, `destructiveHint`, `idempotentHint`:
- Read-only tools can be auto-approved
- Destructive tools always require confirmation
- Idempotent tools can be retried safely

### 3. Embedding-Based Tool Selection (from GitHub Copilot)
For the plugin's tool catalog:
- Generate embeddings for each tool description
- Cluster tools by semantic similarity
- At request time, select only relevant tool clusters
- Target: send 10-15 tools per request, not all 30+

### 4. Registry-Based Dispatch (from Harness v2)
For Jira/Bamboo/Sonar/Bitbucket APIs:
- Don't expose one tool per API endpoint
- Use composite tools: `jira_query`, `jira_mutate`, `bamboo_query`, `bamboo_trigger`
- Registry maps intent to specific API call internally

### 5. Hierarchical Governance (from enterprise patterns)
Settings hierarchy for the plugin:
- **Organization** (managed settings) — admin locks tools on/off
- **Project** (`.workflow/settings.json`) — team-level defaults
- **User** (IDE settings) — personal preferences
- **Session** (runtime) — temporary overrides

### 6. Context Window Budget (from JetBrains research)
- Allocate a token budget for tool definitions (e.g., 15% max)
- Use observation masking for tool outputs (truncate long responses)
- Prefer masking over LLM summarization (same quality, lower cost)
