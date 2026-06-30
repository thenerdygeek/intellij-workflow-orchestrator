# Phase 4a runIde Smoke — Native Anthropic-Direct Provider (coworker run sheet)

**Why this exists:** Phase 4a adds a second LLM back-end so the plugin can run the agent against **`api.anthropic.com`** with a user-supplied API key — **no Sourcegraph required**. The automated gate is green (`:core:test` + `:agent:test`, `verifyPlugin` Compatible, `koverVerify`) and every layer is unit/contract-tested, but the tests can't exercise the *live* wire: real streaming, a real `tool_use` round-trip, native sub-agents, thinking, images, the ~1M context window, and Stop responsiveness. That's this sheet.

**Who runs it:** anyone with desktop IntelliJ IDEA **Ultimate** + a real **Anthropic API key** (`sk-ant-…`). ~25 min. Only Plugin **A** — `./gradlew runIde`.

**The headline thing you're proving:** the agent works end-to-end with the **Sourcegraph URL left blank** and **only an Anthropic key** configured. If anything demands a Sourcegraph URL/token, that's a provider-exclusivity leak → FAIL.

---

## Prereqs

```
git fetch && git checkout feature/plugin-split && git pull
./gradlew runIde
```
If a Gradle config-cache error appears: `./gradlew --stop`, retry. On macOS, if a step hangs on `buildSearchableOptions`, add `-x buildSearchableOptions`.

Inside the launched sandbox IDE, open any Java/Kotlin project, then open the **Agent** chat (the "Workflow" tool window → **Agent** tab).

---

## Setup — select the Anthropic provider (do this first)

**Settings → Tools → Workflow Orchestrator → AI Agent:**
1. **LLM Provider** dropdown → **Anthropic**.
2. **Anthropic API key** field → paste your `sk-ant-…` key. (Stored in the IDE Password Vault, never in XML.)
3. **Anthropic base URL** → leave default `https://api.anthropic.com`.
4. **Model** → `claude-opus-4-8`. **Effort** → `high`. **Thinking** → enabled.
5. **Apply.**

**Crucial — prove the no-Sourcegraph DoD:** go to the **Connections** settings and **blank out / leave empty the Sourcegraph URL** (and don't set a Sourcegraph token). Restart the sandbox if it was previously configured. Everything below must work with NO Sourcegraph configured.

> Re-enable your normal Sourcegraph config when finished if you use it.

---

## Part 1 — Core end-to-end (the must-pass set)

Start a **fresh agent chat** in the Anthropic-configured state.

| # | Step | PASS = |
|---|---|---|
| 1.1 | Send: `In one sentence, what does this project do?` | A normal answer streams back **progressively** — text appears token-by-token AS it generates, NOT all-at-once at the end. (Progressive streaming is the headline UX fix; a single dump-at-end is a FAIL.) |
| 1.2 | Send: `Read the file README.md (or any file you know exists) and quote its first line.` | The agent **calls the `read_file` tool** (you see a tool card execute) and quotes the line. This proves the native `tool_use` → canonical-XML round-trip works end-to-end. |
| 1.3 | Send: `Use a sub-agent (the explorer/general-purpose agent) to list the top-level source directories, then summarize.` | A **sub-agent task card** starts and runs to completion, and the parent summarizes its report. (Proves native sub-agents work — the C1 path.) |
| 1.4 | Watch any reasoning-heavy turn (e.g. ask it to plan a small refactor). | **Thinking renders live** — you see a "Thinking" section populate during generation, not a silent pause. (Proves `display:"summarized"` + `<thinking>` wrapping.) |
| 1.5 | Paste/attach an **image** (a screenshot) and ask `What's in this image?` | The image is **described correctly**. No "Sourcegraph rejected this attachment" or routing error — native images go inline as base64. |

❌ Any of these failing → capture the chat + the IDE log (`Help → Show Log in Finder`) and report.

---

## Part 2 — The provider-specific guarantees (the subtle ones)

| # | Step | PASS = |
|---|---|---|
| 2.1 | **Context window (C2).** After a couple of turns, look at the chat's token/usage indicator (the capacity strip / `UsageIndicator`). | It reflects **~1,000,000** tokens of capacity for `claude-opus-4-8` (compaction won't trigger until near that), **NOT ~90,000**. A 90K cap = the C2 wiring regressed (the catalog isn't feeding `ContextManager`). |
| 2.2 | **Stop responsiveness.** Ask for something long (`Write a 600-word essay about streams`). While it's actively streaming, click **Stop**. | Generation **stops promptly** (within ~1–2 s), not after the full essay finishes. (Proves the OkHttp call-cancel-on-cancelling fix.) |
| 2.3 | **Sub-agent has NO XML tool docs (C1 — authoritative check).** Settings → AI Agent → Advanced → enable **"Write API debug dumps to disk"**. Re-run a sub-agent spawn (step 1.3). Open the newest sub-agent request dump under `~/.workflow-orchestrator/<proj>-<hash>/agent/sessions/<sid>/api-debug/…request…` (or `.request.json`). | The sub-agent's request has tools in the **`tools:[]` API field** and its **`system` prompt does NOT contain the XML tool-usage block** (`<read_file><path>…` style teaching). Tools must appear in EXACTLY ONE place (the wire field), never also in the prompt. Both places = the double-presentation bug → FAIL. Disable the dump setting after. |
| 2.4 | **Model picker.** Open the in-chat top-bar model picker. | It lists the **Anthropic models** (`claude-opus-4-8`, `claude-sonnet-4-6`, `claude-haiku-4-5`, `claude-fable-5`), and selecting one drives the agent's model. It must NOT be empty or showing Sourcegraph model strings. |
| 2.5 | **No Sourcegraph dependency.** Throughout Parts 1–2 (with Sourcegraph URL blank). | At no point does the agent error asking for a Sourcegraph URL/token, nor does the IDE log show calls to `*.sourcegraph.*`/`/.api/llm/…` on the agent path. (Provider exclusivity.) |

---

## Part 3 — Advanced / optional (for the curious or for a dev build)

| # | Step | Note |
|---|---|---|
| 3.1 | **`temperature` 400 probe (spec §3/A3 — informational).** The native brain's `temperature` setter is a deliberate no-op because Anthropic *may* 400 on sampling params for Opus 4.8. This is hard to trigger from the UI (nothing sends temperature). If you have a dev build, temporarily make `AnthropicRequestMapper` emit `temperature` and send one request; record whether Opus 4.8 returns a 400 `invalid_request_error`. Result informs whether the no-op setter is strictly required or merely safe. **Skip unless you're probing the API surface.** |
| 3.2 | **Proxy / SSL-inspection network.** If you're on a corporate proxy or SSL-inspection network, simply that Parts 1–2 work AT ALL is the proof — the native client uses the `IdeProxy`/`IdeTrust` triad, so it should honor IDE proxy settings + corporate CAs with no `keytool` steps (a correctness win over the Sourcegraph clients). |
| 3.3 | **Network-error recovery (L2).** Hard to force, but if a transient `529 overloaded`/network error occurs mid-run, the agent should retry and, after recycles, fall back along `[claude-opus-4-8, claude-sonnet-4-6]` — NOT a Sourcegraph chain. |

---

## What to report back

For each numbered check (1.1–1.5, 2.1–2.5, plus any Part 3 you ran): **✅ / ❌** with a one-line note. Paste the chat for any ❌ + the IDE log. The single most important results:
- **1.1 progressive streaming**, **1.2 tool call executes**, **2.3 sub-agent prompt has no XML tool docs** (C1), **2.1 ~1M context** (C2), **2.5 zero Sourcegraph dependency**.

If all of Part 1 + Part 2 are ✅, Phase 4a is runtime-confirmed: the plugin runs the full agent against Anthropic directly, with no Sourcegraph, no double tool presentation, native streaming + thinking + images, and the right context window.

**Remember to disable the API-debug-dump setting and restore your normal provider/Sourcegraph config when finished.**

---

*Context:* Phase 4a = 14 SDD tasks on `feature/plugin-split` (the `:core` native stack `core/ai/anthropic/*` + `AnthropicNativeProtocol` + `AnthropicModelCatalog*` + the `:agent` `AnthropicDirectBrain` + provider branches in `BrainFactory`/`AgentService`/`SpawnAgentTool`/`SubagentRunner`/`AgentController`/`AgentParentConfigurable`). Design: `docs/superpowers/specs/2026-06-30-plugin-split-phase4a-design.md` + `docs/superpowers/specs/2026-06-22-plugin-split-design.md` §25. "Option A": the model's structured `tool_use` is serialized back to canonical XML at stream end, so persistence + the dialect machinery are unchanged. Counterpart to the still-pending Phase-2/Phase-3 GUI smokes — can be run in the same `runIde` session.
