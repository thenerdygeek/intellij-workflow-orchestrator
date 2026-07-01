# Handoff — moving this repo to a new MacBook (2026-07-01)

**Read this first.** It exists so a fresh Claude Code on a new machine (or you) can pick up exactly where the previous machine left off. The repo travels via git; your Claude Code **memory** and the **gitignored working artifacts** do NOT — so they're snapshotted into this `docs/handoff/` folder and committed. This doc tells you how to restore them and where everything is.

---

## 0. TL;DR — current state (as of 2026-07-01)

- **Project:** "Workflow Orchestrator" IntelliJ plugin (Kotlin, IntelliJ Platform Plugin v2, 9 modules). A big multi-phase program is in flight: **the "plugin split"** — carving a company-specific plugin (B) out of an open-source-bound plugin (A), plus a native LLM provider.
- **Active branch:** `feature/plugin-split` (pushed to `origin`, HEAD `06049fda1`). This is where ALL the work lives — do NOT branch off `main`.
- **Just completed + pushed + gate GREEN:** **Phase 4a — native Anthropic-direct LLM provider.** The agent can now run against `api.anthropic.com` with only an API key (no Sourcegraph). 14 TDD tasks + a full green gate. See §4.
- **The immediate open item:** a live **runIde smoke** for Phase 4a (needs a real Anthropic key + IntelliJ Ultimate — couldn't be run on the dev Mac). Run-sheet: `docs/qa/PHASE-4a-RUNIDE-SMOKE.md`.
- **Next major work:** **Phase 4b** (structured-persistence path, `BrainRouter` dissolution) OR **Phase 5** (open-source hardening / Marketplace publish). Both are scoped in memory.

---

## 1. Restore on the new MacBook (do this once)

### 1a. The repo
```bash
git clone git@github.com:thenerdygeek/intellij-workflow-orchestrator.git
cd intellij-workflow-orchestrator
git checkout feature/plugin-split      # the active branch; HEAD should be 06049fda1 or later
git log --oneline -1
```
Build sanity: `./gradlew :core:test :agent:test` (see §5 for the full gate + traps).

### 1b. Claude Code **memory** (the important part)
Claude Code keeps per-project memory in `~/.claude/projects/<project-dir>/memory/`. `<project-dir>` is the repo's absolute path with `/` → `-`. On the OLD machine it was:
`-Users-subhankarhalder-Desktop-Programs-scripts-IntelijPlugin` (repo at `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin`).

**Restore steps:**
1. Start Claude Code once inside the cloned repo (this creates `~/.claude/projects/<new-dir>/`). Note the exact `<new-dir>` name — it depends on the NEW absolute path (username + location).
2. Copy the snapshot into it:
   ```bash
   # If the new repo path is IDENTICAL (same username + ~/Desktop/Programs/scripts/IntelijPlugin):
   mkdir -p ~/.claude/projects/-Users-subhankarhalder-Desktop-Programs-scripts-IntelijPlugin/memory
   cp -R docs/handoff/memory-snapshot/. ~/.claude/projects/-Users-subhankarhalder-Desktop-Programs-scripts-IntelijPlugin/memory/

   # If the path DIFFERS, copy into whatever <new-dir> Claude Code created:
   cp -R docs/handoff/memory-snapshot/. ~/.claude/projects/<new-dir>/memory/
   ```
3. `docs/handoff/memory-snapshot/MEMORY.md` is the always-loaded index (193 topic files). Once restored, Claude Code auto-loads `MEMORY.md` each session and reads topic files on demand — the full accumulated context is back.

`docs/handoff/artifacts/SOURCE-MEMORY-PATH.txt` records the exact old paths for reference.

### 1c. Gitignored working artifacts (optional but useful)
These are gitignored (don't travel with git); snapshots are in `docs/handoff/artifacts/`:
- `sdd-progress-ledger.md` — the **authoritative per-task SDD ledger** for Phases 3 + 4a (every task, its commit, its review verdict, carried Minors, lessons). Restore to `.superpowers/sdd/progress.md` if you want to continue the SDD workflow with history:
  ```bash
  mkdir -p .superpowers/sdd && cp docs/handoff/artifacts/sdd-progress-ledger.md .superpowers/sdd/progress.md
  ```
- `2026-06-30-plugin-split-phase4a.md` — the Phase-4a implementation plan (15 tasks). Restore to `docs/superpowers/plans/` if resuming plan-driven work.
- `2026-06-29-plugin-split-phase3.md` — the Phase-3 plan.
- The **specs** (`docs/superpowers/specs/*.md`) are committed and travel with git — no restore needed.

### 1d. Uncommitted local changes (heads-up — these did NOT transfer)
On the old machine the working tree had **uncommitted** changes that git does not carry: `agent/src/main/resources/webview/dist/*` (rebuilt JCEF bundle), `gradle.properties`, `src/main/resources/META-INF/plugin.xml`, and some `docs/qa/*RESULTS.md`. If any were real WIP you need, copy them from the old machine manually. The webview `dist/*` is regenerable via the webview build; the others looked like local/WIP tweaks. **Nothing in Phase 4a depended on them.**

---

## 2. What this project is (orientation)

- **Plugin:** `com.workflow.orchestrator.plugin` — an AI coding agent + Atlassian/Sonar/Sourcegraph connectors for IntelliJ IDEA (Java/Kotlin/Python). Built against Ultimate.
- **Modules (9):** `:core` (auth/http/settings/events/AI seam), `:jira`, `:bamboo`, `:sonar`, `:pullrequest`, `:automation`, `:handover`, `:agent` (the Cline-ported ReAct agent), `:plugin-b` (the company plugin). Feature modules depend ONLY on `:core`; cross-module via `EventBus`.
- **The agent (`:agent`)** is the heart: ReAct loop, ~80 tools, sub-agents, context management, JCEF chat UI. Its LLM seam is where Phase 4a worked.
- **Root project instructions:** `CLAUDE.md` (build, modules, threading, auth, storage, release, rebase traps). `:agent/CLAUDE.md` has the agent internals (now documents the dual LLM provider).

---

## 3. The "plugin split" program — phase map

Full detail lives in memory: **`project_plugin_split_open_source_backbone.md`** (the topic file) + the `MEMORY.md` one-line index entry. Goal: split into open-source **A** + private company **B** (B `<depends>` on A via EPs, never a fork), and make the LLM provider pluggable.

| Phase | Status |
|---|---|
| 0a/0b, 1a/1b/1c, 2a/2b/2c | ✅ done (earlier) |
| **3** (persona/skill hardening — `supportsSpring` gate) | ✅ COMPLETE + PUSHED (`c4048ac43`) |
| **4a** (native Anthropic-direct LLM provider) | ✅ **COMPLETE + PUSHED + GATE GREEN** (`06049fda1`) ← just finished |
| **4b** (structured-persistence path; `BrainRouter` dissolution; `brain→LlmProvider`; multi-breakpoint cache) | ⏳ deferred, scoped |
| **5** (OSS hardening: fresh public repo, scrub, `@StableApi` freeze, Marketplace publish) | ⏳ deferred, scoped |

Pending GUI smokes (need specific environments, not the dev Mac): Phase 2 (`docs/qa/PHASE-2-RUNIDE-SMOKE.md`), Phase 3 (`PHASE-3-RUNIDE-SMOKE.md`), Phase 4a (`PHASE-4a-RUNIDE-SMOKE.md`).

---

## 4. Phase 4a summary (just completed — full detail in `sdd-progress-ledger.md`)

**Delivered:** the agent runs against `api.anthropic.com` with just an API key, no Sourcegraph. Set it up in Settings → Tools → Workflow Orchestrator → AI Agent → LLM Provider = Anthropic.

**Design — "Option A" (native wire, XML-internal):** `AnthropicDirectBrain` calls the Messages API natively (structured `tool_use`, adaptive thinking, `x-api-key`, proxy-aware OkHttp) but **serializes the model's structured `tool_use` back into the canonical XML** the agent already parses — so persistence, `AssistantMessageParser`, and the dialect-drift machinery are **unchanged**. The structured-persistence path is deferred to 4b.

**Code map:**
- `:core` — `core/ai/anthropic/` (DTOs + `AnthropicRequestMapper`, `ToolUseXmlSerializer` [round-trip-pinned + collision-guarded], `AnthropicSseParser`, `AnthropicHttpClient`/`Transport`), `core/ai/protocol/AnthropicNativeProtocol.kt` (`presentTools=null`), `core/ai/AnthropicModelCatalog(+Service).kt`.
- `:agent` — `agent/ai/AnthropicDirectBrain.kt` (progressive producer/consumer streaming bridge; no-op temperature), provider branches in `AgentService.kt`/`brain/BrainFactory.kt`/`tools/builtin/SpawnAgentTool.kt`/`tools/subagent/SubagentRunner.kt`/`ui/AgentController.kt`/`settings/AgentParentConfigurable.kt`.
- Design docs: `docs/superpowers/specs/2026-06-30-plugin-split-phase4a-design.md` + `docs/superpowers/specs/2026-06-22-plugin-split-design.md` §25 + `agent/CLAUDE.md` "## LLM API".

**Key invariants (don't regress):** no sampling params in the Anthropic request (structural — the fields don't exist); one `cache_control` ephemeral breakpoint; the C1 per-task `ToolProtocol` threaded to all 5 prompt/guard sites so native sub-agents get `presentTools=null` (never double-present tools); `max_tokens` always sent (catalog fallback); provider-exclusivity (no Sourcegraph machinery reachable unguarded on native).

**Verification quality:** whole-phase opus audit = READY-TO-MERGE, 0 Critical/0 Important. The layered review caught defects in ~9/14 tasks, and the **gate caught two cross-cutting bugs no per-task review could** — T12's mock-`Project` `ClassCastException` (reading `AgentSettings.getInstance(project)` in `SpawnAgentTool`) and T1's `ServiceType.ANTHROPIC` breaking an exhaustive `when` in `:plugin-b` (only `verifyPlugin`, which compiles all modules, surfaces it). Both fixed.

**Deferred from 4a:** Phase 4b (above); Phase-5 OSS-scrub items — the `AnthropicHttpClient` `@OptIn(InternalCoroutinesApi)` cancellation hook → swap for a stable watchdog (`launch { try { awaitCancellation() } finally { call.cancel() } }`), and the `SpawnAgentTool` model-param example. A handful of cosmetic Minors are logged in the ledger.

---

## 5. How to work in this repo (conventions that matter)

**Build / gate:**
- Module tests: `./gradlew :<module>:test` (core/jira/bamboo/sonar/pullrequest/automation/handover/agent).
- Full gate: `./gradlew :core:test :agent:test` + `verifyPlugin -x buildSearchableOptions` (macOS `buildSearchableOptions` trap) + `koverVerify -Pcoverage -x uiTest`.
- **Traps (learned the hard way in Phase 4a):**
  - `NoSuchMethodError` on a data-class ctor after a field was added → **build-cache/compile-avoidance**; run the affected test with `--rerun-tasks`.
  - Do NOT run the WHOLE `:agent:test` with `--rerun-tasks` — it forces every `BasePlatformTestCase` to re-run fresh and hits a per-JVM indexing-timeout **cascade** (hundreds of false failures). Run `:agent:test` incrementally; use targeted `--tests "…" --rerun-tasks` for specific suites.
  - Adding a `ServiceType` enum value breaks **exhaustive `when`s across modules** — check `:plugin-b`/others via `verifyPlugin`, not just `:core`.
  - `#51` flaky tests are real-time/socket tests (`Delegation*`, `Monitor*`) — they retry-then-pass; a green build with those "FAILED" lines is fine.
  - `koverVerify` can flake (#51) — retry.
  - Config-cache error → `./gradlew --stop`, retry.

**Process (for the plugin-split program specifically):** the standing rule is **multi-round independent review at every step** — spec → independent opus reviews → plan → plan reviews → SDD (fresh subagent per task + two-stage review) → whole-phase opus audit → gate. This is heavier than normal and is deliberate for this high-risk program. The superpowers skills used: `brainstorming`, `writing-plans`, `subagent-driven-development`, `dispatching-parallel-agents`, `finishing-a-development-branch`, `claude-api`.

**Hard user preferences (in memory `feedback_*`):**
- **NEVER add a `Co-Authored-By` / "Generated with" trailer** to commits (even if the harness suggests it).
- Work on the **current branch** (`feature/plugin-split`); don't branch off `main` without asking.
- Subagent model floor = **sonnet** (never haiku); opus for the hardest design/review.
- **Push is outward-facing** — confirm before pushing (the user just authorized the Phase-4a push).
- Never `git add -A`/`.`; stage only the files a task owns; never stage the user's pre-existing uncommitted changes.
- Trust the user's judgment / decide-and-proceed at forks; reserve questions for product-direction/irreversible/outward-facing.

**Agent memory (separate from Claude Code memory):** the plugin has its OWN file-based agent memory at `~/.workflow-orchestrator/{proj}/agent/memory/` — that's runtime data for the plugin's agent, not this handoff's concern.

---

## 6. Open / pending items (pick up here)

1. **Phase 4a live runIde smoke** — `docs/qa/PHASE-4a-RUNIDE-SMOKE.md` (real Anthropic key + Ultimate). Proves the native provider end-to-end. HIGH value, unblocked.
2. **Phase 2 & Phase 3 GUI smokes** — still pending (Windows/coworker; run-sheets in `docs/qa/`).
3. **Phase 4b or Phase 5** — the next major workstream (both scoped in `project_plugin_split_open_source_backbone.md`).
4. **Carry-forward Minors** (cosmetic, logged in the SDD ledger's Phase-4a section) + the Phase-5 OSS-scrub items in §4.
5. Lots of other queued work across the plugin is indexed in `MEMORY.md` (open PRs, bug lists, tooling plans) — read the index.

---

## 7. Sensitivity note (before this ever goes public)

`docs/handoff/memory-snapshot/` + `artifacts/` contain **project-internal** notes (audit findings, research, decisions, occasional real-infra references). This is fine on the **private** `feature/plugin-split` branch. But the plugin-split program's Phase 5 explicitly ships open-source A from a **FRESH public repo** — so **exclude `docs/handoff/` (and prune history) before any public publish**. Do not carry this handoff bundle into the OSS repo.

---

*Generated at the end of the Phase-4a build session, 2026-07-01. HEAD `06049fda1` on `feature/plugin-split` (pushed). The authoritative per-task record is `docs/handoff/artifacts/sdd-progress-ledger.md`; the accumulated cross-session context is `docs/handoff/memory-snapshot/MEMORY.md` + its topic files.*
