# Sourcegraph Agentic AI Capability Probe v2.0

Tests whether your Sourcegraph Enterprise instance can support enterprise-grade agentic AI patterns — the same patterns used by Devin, Claude Code, OpenHands, and GitHub Copilot Agent Mode.

## Quick Start

```bash
pip install requests
python3 probe.py --url https://sourcegraph.yourcompany.com --token sgp_YOUR_TOKEN
```

For self-signed certs: add `--no-verify`
For faster run: add `--quick` (skips performance tests)
To specify model: add `--model anthropic/claude-3-sonnet`

## What It Tests (and Why)

### Section 1: Connectivity & Endpoint Discovery
Finds which APIs exist on your instance. Probes 9 different LLM endpoint paths, tests GraphQL, MCP, and native tool calling support.

### Section 2: Structured Output Reliability
**Why**: Agentic AI works by having the LLM output structured tool calls. If JSON output is unreliable, the entire agent breaks.

| Test | What It Proves |
|------|---------------|
| 2.1 Raw JSON | Can it output clean JSON without markdown fences? |
| 2.2 XML tool_call tags | Can it use `<tool_call>` delimiters? (THE critical pattern) |
| 2.3 Consistency (5 runs) | Is structured output reliable across multiple calls? |
| 2.4 Complex nested JSON | Can it handle real-world tool parameters? |
| 2.5 Task decomposition | Can it plan multi-step workflows as JSON arrays? |
| 2.6 Self-repair | Can it fix its own malformed JSON? (enables retry logic) |

### Section 3: Agentic Loop Patterns
**Why**: Tests the exact patterns that enterprise agents use.

| Test | Pattern From | What It Tests |
|------|-------------|--------------|
| 3.1 Tool result injection | OpenHands event stream | Can we fake tool results in conversation? |
| 3.2 Three-step agent loop | Devin's think->act->observe | Full 3-tool workflow with mock results |
| 3.3 Error recovery | All enterprise agents | What happens when a tool returns 503? |
| 3.4 Task planner | Copilot Workspace | Can it break tasks into steps with dependencies? |
| 3.5 Done detection | Claude Code stop_reason | Does it know when to stop calling tools? |
| 3.6 Role adherence | Security requirement | Does it resist prompt injection attempts? |

### Section 4: Context Window & Memory
**Why**: Enterprise agents run for hours. Context management determines how long before the agent "forgets."

| Test | Pattern From | What It Tests |
|------|-------------|--------------|
| 4.1 Conversation depth | All agents | Can it recall facts from 20 messages ago? |
| 4.2 Large input | Cursor code analysis | Can it process ~5000 words of code? |
| 4.3 State externalization | Devin's CHANGELOG.md | Can it produce checkpoint summaries? |
| 4.4 Max output length | All agents | How much can it output in one response? |

### Section 5: Performance & Concurrency
**Why**: An agent loop that takes 30s per step is unusable. Need to know real latency.

| Test | What It Measures |
|------|-----------------|
| 5.1 Latency baseline | Raw call speed |
| 5.2 Structured output latency | Per-step cost in agent loop |
| 5.3 Concurrent calls | Can we parallelize (Planner-Executor)? |
| 5.4 Streaming | For real-time UI feedback |
| 5.5 10-call rapid fire | Simulates a full agent session |

### Section 6: Enterprise Workflow Simulation
**Why**: The real test. Simulates a complete Jira -> Code -> Build -> Quality -> PR workflow.

Tests: workflow completeness (did it use all expected tools?), ordering intelligence (did it build before checking quality?), and cross-step context (did it use ticket info when creating the PR?).

### Section 7: IDE Context Integration
**Why**: Your plugin has IntelliJ PSI — richer than tree-sitter. Tests whether the LLM can work with structured code context instead of raw source files.

| Test | What It Tests |
|------|--------------|
| 7.1 PSI context understanding | Can LLM interpret structured class metadata? |
| 7.2 PSI-informed tool selection | Can it make correct tool calls based on PSI data? |
| 7.3 Module dependency reasoning | Can it determine build impact from module graph? |

## Output

Results printed with pass/fail and timing. Full report saved to `probe_results.json`.

The report includes a viability assessment:
- **ALL CRITICAL PASS** — agentic AI is fully viable
- **PARTIALLY VIABLE** — possible with workarounds
- **SIGNIFICANT LIMITATIONS** — need alternative approach

## Getting Your Token

1. Go to your Sourcegraph instance
2. Avatar > Settings > Access tokens
3. Create token with `user:all` scope
