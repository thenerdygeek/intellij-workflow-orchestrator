---
name: Faithful port from Cline — no custom architecture
description: ALWAYS port from Cline's actual TypeScript source code to Kotlin. Never create custom/hybrid architecture. Read the real source, translate faithfully.
type: feedback
---

When building the agent pipeline, ALWAYS faithfully port from Cline's actual TypeScript source code (github.com/cline/cline) to Kotlin. NEVER create a custom or hybrid architecture. NEVER be "inspired by" — actually translate the logic, prompt text, tool descriptions, algorithms, thresholds, error handling.

**Why:** The user tried custom architecture multiple times and the LLM kept hallucinating (empty responses, no tool calls, finish_reason stop after <10 iterations). The root cause was always custom over-engineering. Cline is battle-tested at scale. Copy it.

**How to apply:**
- Read Cline's actual TypeScript source files from GitHub before writing any agent code
- Translate TypeScript → Kotlin line by line where possible
- Adapt ONLY where technically required: VS Code → IntelliJ, async/await → coroutines, XML tools → function calling, Anthropic API → Sourcegraph API
- Keep same prompt text, same thresholds, same algorithms, same error messages
- If in doubt, match Cline. Do not innovate on the agent pipeline.

**Scope:** System prompt, tool descriptions, context management, loop detection, task progress, checkpoint system, plan mode, skill system, retry/error handling. Everything except the transport layer (API format) and IDE integration (IntelliJ vs VS Code).
