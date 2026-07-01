---
name: Skill/instruction injection patterns (7 tools)
description: How Claude Code, Cursor, Cline, Codex CLI, Windsurf, Continue.dev, Aider inject skills/rules into LLM context — injection point, message role, compression, patterns. Our system uses Aider-style dual injection (primacy+recency) which is the most robust.
type: reference
---

## Claude Code Skill Injection
- Discovery: skill names in Skill tool schema, NOT system prompt
- Activation: user-role messages (isMeta:true) at invocation point mid-conversation
- Temporary — ages out during compaction, not compression-proof
- Hook-based: SessionStart additionalContext as system-reminder (primacy bias)

## Comparison Table
| Tool | Where | Role | Compression-Proof |
|---|---|---|---|
| Claude Code | Mid-conversation at invocation | user (isMeta:true) | No |
| Codex CLI | Before user turn, re-injected | user + XML tags | Best — excluded from compaction |
| Aider | Dual: system(start) + reminder(end) | system | Strong — structural separation |
| Cline | System prompt last section, rebuilt each turn | system | Moderate |
| Cursor | System prompt end | system | Unknown |
| Windsurf | System prompt + memories | system | Good |
| Continue.dev | Appended to system message | system | Good |

## 5 Patterns
1. System prompt injection (all tools, universal baseline)
2. User-role XML messages with compaction exclusion (Codex CLI)
3. Dual primacy+recency reinforcement (Aider)
4. Dynamic on-demand skill loading (Claude Code, Cline)
5. Conditional glob/directory activation (Cline, Continue, Codex)

## Our System
- Dual injection: descriptions in system prompt primacy zone + active content as compression-proof anchor at end
- Most robust pattern across all tools studied
- Sourcegraph API converts system->user anyway, so role distinction less impactful
- Sources: leehanchung.github.io, Piebald-AI/claude-code-system-prompts, openai/codex, paul-gauthier/aider
