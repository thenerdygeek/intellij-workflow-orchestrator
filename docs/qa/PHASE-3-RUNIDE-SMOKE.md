# Phase 3 runIde Verification — coworker run sheet

**Why this exists:** Phase 3 made Plugin A read *neutrally* on non-Spring projects. It gates two specialist agent personas — `security-auditor` and `performance-engineer` — so they are only **advertised** when the project is Spring-capable (`supportsSpring` = the IntelliJ **Spring plugin is enabled** AND the project has **Java/Kotlin**), and it neutralized the Atlassian framing in the `git-workflow` skill. The automated gate is GREEN (`:agent:test`, `verifyPlugin` Compatible, `koverVerify`) and the golden-snapshot tests pin the prompt text **per synthetic IDE context** — but they cannot confirm that *real* IDE detection (Spring plugin present/absent, Java present/absent) drives the gate at runtime. That is exactly what this sheet checks.

**Key design point you are verifying — the gate is ADVISORY, not a hard block.** The two personas are removed from what the agent is *offered/advertised*, but they remain **spawnable by exact name** (they run "degraded" — their Spring-specific `spring(...)` tool is simply absent). So this sheet checks **both** directions: hidden-when-not-advertised AND still-runnable-by-name. A result where naming the persona is *rejected* would be a FAIL, not a pass.

**Who runs it:** anyone with a desktop IntelliJ IDEA **Ultimate** (the build needs Ultimate). ~20 min. Only Plugin **A** is needed — `./gradlew runIde` (no `:plugin-b`).

---

## Prereqs

```
git fetch && git checkout feature/plugin-split && git pull     # origin is synced at c4048ac43
./gradlew --version                                            # JDK per gradle.properties javaVersion; Gradle 9.4
./gradlew runIde                                               # launches the sandbox IDE (Plugin A only)
```
If a Gradle config-cache error appears: `./gradlew --stop`, then retry. On macOS, if a step hangs on `buildSearchableOptions` while an IDE is open, add `-x buildSearchableOptions`. (This is the same trap that made the headless `verifyPlugin` "fail" locally — the plugin itself verified **Compatible**.)

**You will use ONE sandbox IDE and toggle one plugin.** Inside the launched sandbox, open the **Agent** chat (the "Workflow" tool window → **Agent** tab, or wherever the agent chat lives). You will send a few chat messages and read the replies.

**Two project states to compare (open each as a project in the sandbox):**

| State | How to get it | `supportsSpring` |
|---|---|---|
| **SPRING-ON** | Any **Java or Kotlin** Gradle/Maven project, Spring plugin **enabled** (the Ultimate default). | **true** |
| **SPRING-OFF** | The **same Java/Kotlin project**, but **disable the Spring plugin**: sandbox **Settings → Plugins → Installed → search "Spring" → disable "Spring" (and "Spring Boot") → restart the sandbox IDE**. | **false** |

> Why disable the plugin rather than just open a non-Java project? Because in Ultimate the Spring plugin is always installed, so `supportsSpring` only differs from plain `supportsJava` when the Spring plugin is **off**. Disabling it on a *Java* project is the one scenario that proves the gate keys on **Spring**, not merely on Java — `spring-boot-engineer` (Java-gated) must stay visible while the two Spring-auditor personas disappear. (Simpler fallback if you can't toggle the plugin: open a project with **no Java/Kotlin** at all — e.g. a folder with only a `notes.md` — which also yields `supportsSpring=false`. You lose the `spring-boot-engineer`-stays-visible contrast, but the two personas should still disappear.)

**Re-enable the Spring plugin when you're done.**

---

## Part 1 — Persona advertisement is gated (the core check)

In each project state, start a **fresh agent chat** and send this exact prompt:

> `List the specialist sub-agent types you can delegate to on this project. Output only the names, one per line.`

The agent answers by reading its own system prompt's "# Subagent Delegation" list, so this is a faithful read of what is advertised.

| # | State | Send the prompt above; PASS = the reply… |
|---|---|---|
| 1.1 | **SPRING-ON** (Java + Spring plugin on) | **INCLUDES** `security-auditor` **and** `performance-engineer` (alongside `code-reviewer`, `architect-reviewer`, `test-automator`, `spring-boot-engineer`, `refactoring-specialist`, `devops-engineer`, `explorer`, `research`, `general-purpose`). |
| 1.2 | **SPRING-OFF** (Java, Spring plugin disabled) | **OMITS** `security-auditor` **and** `performance-engineer`, while **`spring-boot-engineer` is STILL listed** (it gates on Java, not Spring). This is the decisive check that the gate keys on `supportsSpring`. |

❌ If `security-auditor`/`performance-engineer` still appear in 1.2 (Spring-off), or disappear in 1.1 (Spring-on), the runtime gate is wrong → **report with the full reply**.

> Note: the agent's `agent` tool documentation contains an illustrative example that *names* `security-auditor` (an intentional, out-of-scope schema string). If the agent mentions it only as "for example, you could name security-auditor explicitly," that is **not** the advertised list — judge by the enumerated specialist list it returns to the prompt above.

### 1.3 — Authoritative cross-check (optional, deterministic)

If you want a non-LLM confirmation of 1.1/1.2:

1. Sandbox **Settings → Tools → Workflow Orchestrator → AI Agent → Advanced →** enable **"Write API debug dumps to disk"**.
2. Send any one chat message in the project state you're checking.
3. Open the dumped request: `~/.workflow-orchestrator/<project-slug>-<hash>/agent/sessions/<newest-session-id>/api-debug/call-001-request.txt` (or use the in-IDE **API Debug** viewer).
4. Find the section header **`# Subagent Delegation`** and read its bullet list.

PASS = under `# Subagent Delegation`, the bullet lines `- "security-auditor" — …` and `- "performance-engineer" — …` are **present in SPRING-ON** and **absent in SPRING-OFF**. (Ignore any occurrence of `security-auditor` *outside* that section — the `agent` tool's usage example mentions it deliberately.) Disable the dump setting afterward.

---

## Part 2 — The advisory escape hatch (hidden ≠ blocked)

Stay in **SPRING-OFF** (where the two personas are now un-advertised). Start a fresh chat and send:

> `Use the security-auditor sub-agent to do a quick read-only look at this project and report what it sees.`

| # | PASS = |
|---|---|
| 2.1 | The agent **spawns a `security-auditor` sub-agent** — you see a sub-agent task card start and run (it may note it lacks Spring-specific tools; that's the expected "degraded" run). It must **NOT** reply `Unknown agent type 'security-auditor'`. |

❌ If the agent **rejects** it as an unknown/unavailable type, the gate was implemented as a **hard block** instead of advisory → **report** (this contradicts the design: by-name spawn is intentionally kept).

> Why this matters: a user on a plain Java project who explicitly wants the auditor should still get it. Phase 3 only stops *proactively offering* the Spring personas; it never removes the ability to invoke them by name.

---

## Part 3 — `git-workflow` skill reads neutrally

In any project state, in the agent chat, activate the skill by typing `/git-workflow` (or via the skills menu). Then send:

> `Summarize the git-workflow skill's guidance for pull-request and CI tasks.`

| # | PASS = |
|---|---|
| 3.1 | The skill activates without error, and the PR/CI guidance is framed **conditionally** — it says these tools are **deferred / appear only when the integration is configured** (e.g. "load via `tool_search` when their service URL is set"), rather than implying Bitbucket/Bamboo are always present. The concrete action names (`bitbucket_pr`, `bamboo_builds`, `bitbucket_repo`) are **still mentioned** as hints — they should NOT have been deleted. |

❌ If the skill errors on activation, or its PR/CI guidance reads as always-on/"Enterprise" (no "when configured" conditioning), report it. (The shipped file content is already verified by the test suite + commit `21c788ac8`; this is a "still loads + reads neutral at runtime" sanity check.)

---

## What to report back

For each numbered check (1.1, 1.2, 1.3 if run, 2.1, 3.1): **✅ / ❌** with a one-line note, and paste the agent's reply for 1.1/1.2 (the persona lists) and 2.1 (did it spawn or reject). The single most important result is **1.2 vs 2.1 together**: in Spring-OFF the two personas are *not advertised* (1.2) yet *still spawnable by name* (2.1). If all are ✅, Phase 3 is runtime-confirmed.

**Remember to re-enable the Spring plugin and disable the API-debug-dump setting when finished.**

---

*Context:* Phase 3 commits `31d3bd0f6` (Site A — runtime list), `7b1e1a83e` (Site B prose + Site C routing pointers + snapshots), `21c788ac8` (git-workflow), `c4048ac43` (docs). Design: `docs/superpowers/specs/2026-06-29-plugin-split-phase3-design.md`. This is a behavioral counterpart to the still-pending Phase-2 GUI smoke (`docs/qa/PHASE-2-RUNIDE-SMOKE.md`) — both can be run in the same `./gradlew runIde` / `:plugin-b:runIde` session.
