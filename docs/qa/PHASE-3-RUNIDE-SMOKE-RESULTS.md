# Phase 3 runIde Verification — Results

**Run date:** 2026-06-30. Verifies that the Spring-gated persona advertisement (`security-auditor`, `performance-engineer`) keys on real runtime IDE detection, that the gate is advisory (still spawnable by name), and that the `git-workflow` skill reads neutrally.

## Summary

| Check | Source-level (deterministic) | Runtime (live IDE) |
|---|---|---|
| 1.1 SPRING-ON advertises both personas | ✅ confirmed in source | ⛔ blocked — agent needs Sourcegraph cred |
| 1.2 SPRING-OFF omits both, keeps `spring-boot-engineer` | ✅ confirmed in source | ⛔ blocked — needs Sourcegraph cred + Spring-disable + restart |
| 1.3 API-debug dump cross-check | n/a | ⛔ blocked — needs a live agent call to produce a fresh dump |
| 2.1 by-name spawn still works in SPRING-OFF | ✅ confirmed in source | ⛔ blocked — needs agent + (custom spawn scenario) |
| 3.1 `git-workflow` reads neutrally + still names tools | ✅ **PASS** (file) | ⛔ activation check blocked — needs agent |

> **Runtime blocker:** the running sandbox is a **fresh launch with credentials gone** (the Agent tab shows *"No Sourcegraph connection configured."*). Every Phase-3 runtime check requires the **agent to make an API call**, which needs **Sourcegraph** configured (`http://localhost:8088` + any token). Entering tokens is outside what I can do, so the live half needs the operator. The deterministic **source** confirmation below covers the exact predicates the runtime is meant to exercise.

---

## Source-level confirmation (the gating predicates)

### Persona gating — `AgentConfigLoader.filterByIdeContext` (`agent/.../tools/subagent/AgentConfigLoader.kt:229`)
```kotlin
fun filterByIdeContext(configs, ideContext) = configs.filter {
  when (config.name.lowercase()) {
    "spring-boot-engineer" -> ideContext.supportsJava      // Java-gated
    "security-auditor"     -> ideContext.supportsSpring     // Spring-gated
    "performance-engineer" -> ideContext.supportsSpring     // Spring-gated
    else -> true                                            // universal
  }
}
```
and `IdeContext.supportsSpring` (`agent/.../ide/IdeContext.kt:44`):
```kotlin
val supportsSpring get() = hasSpringPlugin && supportsJava
```
→ exactly matches the sheet: the two auditors are gated on **Spring plugin enabled AND Java/Kotlin**, while `spring-boot-engineer` keys on Java only. So in **SPRING-OFF** (Spring disabled, Java present) `supportsSpring=false` → the two are filtered out of the advertised list while `spring-boot-engineer` stays. (1.1/1.2 logic ✅)

### The advertised list vs. the spawn resolver (why hidden ≠ blocked)
- **Advertised** list (system prompt "# Subagent Delegation", `SystemPrompt.kt:949`) is built from `getFilteredConfigs(ideContext)` → **filtered**.
- **Spawn-by-name** (`SpawnAgentTool.kt:662`) resolves via `configLoader.getCachedConfig(agentType)` → `configCache[name.lowercase()]`, which is the **raw, UNFILTERED** cache. So naming `security-auditor` in SPRING-OFF still resolves its config and spawns it (degraded — its Spring tool simply absent); it is **not** rejected. The `Unknown agent type 'X'. Available: …` error (`:805`) only fires when the name is genuinely not in cache, and is not reached for a filtered-but-cached persona. (2.1 logic ✅ — escape hatch is real.)

### git-workflow skill (`agent/src/main/resources/skills/git-workflow/SKILL.md`) — 3.1 ✅ PASS (file level)
The "## PR & CI tasks **(when integrations are configured)**" section is framed **conditionally**: *"These tools are deferred and appear only when their service URL is set in settings — load them via `tool_search` first."* — and the concrete action names are **still present**: `bitbucket_pr(action="get_pr_diff")` / `get_pr_changes` / `get_pr_commits` / `create_pr`, `bamboo_builds(action="build_status")`, `bitbucket_repo(action="get_build_statuses")`. No always-on/"Enterprise" framing of the PR/CI tools. (The lone remaining "Enterprise" words are in the description trigger line and a branch-naming aside about "enterprise repos" — not the PR/CI tool framing the sheet flags.) Matches the PASS criteria.

---

## What the runtime checks still need (operator)
The source proves the *logic*; the sheet's point is that **real IDE detection drives it at runtime**. To close that:

1. **Configure Sourcegraph** in the sandbox (`http://localhost:8088` + any token) so the agent can make a call. (I can't enter tokens.)
2. **1.1 (SPRING-ON, current state — Kotlin project + Spring enabled):** enable *Settings → AI Agent → Advanced → "Write API debug dumps to disk"*, send one agent message, then I'll read the fresh `…/api-debug/call-001-request.txt` and confirm `# Subagent Delegation` **includes** `security-auditor` + `performance-engineer` (and `spring-boot-engineer`).
3. **1.2 (SPRING-OFF):** *Settings → Plugins → disable "Spring" (+ "Spring Boot") → restart the sandbox*, send a message, and I'll confirm the dump **omits** the two auditors while **keeping** `spring-boot-engineer`. (Re-enable Spring afterward.)
4. **2.1:** in SPRING-OFF, I'll author a custom mock scenario that emits an `agent` spawn tool call naming `security-auditor` (Chrome POST, same as the BUG scenarios), then confirm the IDE **starts a degraded sub-agent task card** rather than replying *"Unknown agent type 'security-auditor'"*.
5. **3.1 runtime:** activate `/git-workflow` in chat → confirm it loads without error.

Note: the old `agent/logs/api-debug/call-*.txt` dumps on disk are from a **March 26 JUnit run** (synthetic `/tmp/junit…/Config.kt` context, pre-Phase-3) — not usable for this verification; a fresh dump from the running sandbox is required.

---

## 2026-06-30 — clean sandbox attempt: dump setting enabled, but agent blocked on Sourcegraph

On the clean rebuilt A+B sandbox I **enabled** *Settings → AI Agent → Advanced → "Write API debug dumps to disk"* (confirmed checked) — ready to capture a fresh `…/api-debug/call-001-request.txt` on the next agent task.

**Still blocked on the agent:** despite the operator's note that creds persisted, **Sourcegraph specifically is not configured** — the Connections page's collapsible **Sourcegraph** group has an **empty Server URL**, and the Agent tab shows *"No Sourcegraph connection configured."* (Jira/Bamboo/Bitbucket persisted and work — Sprint populated, Bamboo connected — but Sourcegraph did not.) Without Sourcegraph the agent can't issue a request, so no dump is produced and the SPRING-ON/SPRING-OFF Part-1 checks can't run.

**To unblock (operator):** set **Sourcegraph** = `http://localhost:8088` + any token on the Connections page (I can't enter tokens), then send one agent message in the current Kotlin project (SPRING-ON). I'll read the fresh dump's `# Subagent Delegation` and give the verdict; then the SPRING-OFF dump after a no-Java folder is opened. The source-level confirmation above already pins the gating predicates.
