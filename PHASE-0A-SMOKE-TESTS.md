# Phase 0a — Two-Plugin `runIde` Smoke Tests (Windows)

**What this verifies:** that private **plugin B** installs on top of **plugin A** and that the two
Phase-0a extension points work *at runtime in a real IDE* — the only checks that could not be run
headlessly (everything else — unit tests, `verifyPlugin`, konsist contracts — already passed in CI).

## 🔑 Tokens required: **NONE**

These smokes test **plugin loading + the extension-point mechanism only**. They do **NOT** call
Jira, Bitbucket, Bamboo, Sonar, or Sourcegraph, and they do **NOT** need an LLM/Anthropic token.
- The `WorkflowConfig` smoke checks *which config implementation wins* (a class-resolution check) — it never contacts Jira.
- The agent-tool smoke checks that a *no-op* tool (`companyb_noop`, returns `"ok"`) is *registered* — it never executes against any service.

(You would only need Jira/Bitbucket/Sonar/Sourcegraph/LLM tokens later to actually *use* those
features or run the agent against a live model — that is out of scope here.)

---

## PART A — Publish the branch (run ONCE, on the dev/Mac machine)

The Phase-0a commits are local. Push `feature/plugin-split` to GitHub so the Windows machine can clone it:

```bash
# from the repo root on the dev machine, on branch feature/plugin-split
git status                              # confirm: "On branch feature/plugin-split", clean
git log --oneline -1                    # tip should be the "Phase-0a two-plugin runIde smoke guide" commit
git push origin feature/plugin-split
```

Expected: a clean fast-forward push (origin already has the branch, just older commits).
- If it reports **"Everything up-to-date"**, it's already published — fine.
- If it's **rejected (non-fast-forward)**, someone else advanced the remote: run `git fetch origin` then `git log --oneline feature/plugin-split..origin/feature/plugin-split` to inspect, and reconcile before re-pushing. (Do **not** force-push a shared branch.)

Verify it landed — these two must print the SAME commit:
```bash
git rev-parse feature/plugin-split
git ls-remote origin feature/plugin-split
```

---

## PART B — On your Windows machine

### Prerequisites

- **JDK 21** (the Gradle toolchain auto-provisions it via the foojay resolver if missing).
- **First run downloads IntelliJ IDEA Ultimate 2025.1.7 (~1.5 GB)** into the Gradle cache — expect the first `runIde` to take several minutes. Subsequent runs are fast.
- Git installed.

### Step 0 — Clone the right branch (IMPORTANT)

Phase 0a lives on **`feature/plugin-split`**, *not* `main`. Plugin B does not exist on `main`.

```bat
git clone git@github.com:thenerdygeek/intellij-workflow-orchestrator.git
cd intellij-workflow-orchestrator
git checkout feature/plugin-split
```

Confirm you see the `plugin-b\` directory at the repo root. If not, you are on the wrong branch.

## Step 1 — Launch the IDE with BOTH plugins

> ⚠️ Use **`:plugin-b:runIde`** — it launches a sandbox IDE with **both A and B** installed
> (B's build pulls in A via `localPlugin`). The plain `gradlew.bat runIde` loads **only plugin A** —
> if you use it you'll wrongly conclude B is missing.

```bat
gradlew.bat :plugin-b:runIde
```

A second "sandbox" IntelliJ IDEA window opens. (This is a throwaway IDE; your real settings are untouched.)

## Step 2 — Smoke 1: both plugins load (no tokens)

In the sandbox IDE: **File → Settings → Plugins → Installed**. Confirm **both** are present, **enabled**, and show **no error banner**:
- **Workflow Orchestrator** (plugin A)
- **Workflow Orchestrator - Company B** (plugin B)

✅ **PASS** = both enabled, no "plugin failed to load" / "incompatible" error.
This alone proves B's hard `<depends>` on A resolved, B's classloader attached to A, and B's classes
(which compile against A's `:core`/`:agent`) loaded.

## Step 3 — Trigger the two extension points

The two diagnostics below are emitted by normal startup activity — you just need to trigger them:

1. **Open any project** in the sandbox IDE (e.g. open this cloned repo folder, or create an empty project).
   → fires the `WorkflowConfig` diagnostic (a project-startup activity).
2. **Open the Workflow tool window** (bottom dock, tab labelled **"Workflow"**) — or open the agent chat.
   → constructs `AgentService`, firing the agent-tool-contribution diagnostic.

## Step 4 — Read `idea.log` (no tokens)

Easiest: in the sandbox IDE, **Help → Show Log in Explorer** — this opens the folder containing `idea.log`.
(Direct path, relative to the repo: `.intellijPlatform\sandbox\IU-2025.1.7\log\idea.log`.)

Open `idea.log` and search (Ctrl+F) for these two lines:

- **Smoke 2 — B overrides A's `WorkflowConfig` EP:**
  ```
  [PluginSplit] active WorkflowConfig impl: CompanyBWorkflowConfig
  ```
  ✅ **PASS** = it says `CompanyBWorkflowConfig` (B's order=0 override won over A's `DefaultWorkflowConfig`).
  ❌ If it says `DefaultWorkflowConfig`, B's override did not win — report it.

- **Smoke 3 — B contributes an agent tool via the `agentToolContributor` EP:**
  ```
  [agentToolContributor] 1 contributor(s) [CompanyBToolContributor] contributed tools: [companyb_noop]
  ```
  ✅ **PASS** = the line lists `CompanyBToolContributor` and `companyb_noop`.
  ❌ If the line is absent, B's tool contributor did not run — report it.

## Pass/Fail summary

| Smoke | Where | Pass criterion |
|---|---|---|
| 1. Both plugins load | Settings → Plugins | A and B both enabled, no error |
| 2. WorkflowConfig override | idea.log | `active WorkflowConfig impl: CompanyBWorkflowConfig` |
| 3. agentToolContributor | idea.log | `[CompanyBToolContributor] contributed tools: [companyb_noop]` |

All three green ⇒ Phase-0a runtime gates satisfied.

## Troubleshooting

- **Only "Workflow Orchestrator" (A) shows in Plugins, not B** → you ran `gradlew.bat runIde` (root). Stop it and run `gradlew.bat :plugin-b:runIde`.
- **No `plugin-b\` directory / B classes missing** → you're on `main`. Run `git checkout feature/plugin-split`.
- **First `runIde` seems stuck** → it's downloading the ~1.5 GB IDE; watch the Gradle console.
- **`idea.log` has neither diagnostic line** → you didn't open a project / the Workflow tool window in Step 3; the activities are lazy.
- **Can't find `idea.log`** → use **Help → Show Log in Explorer** in the sandbox IDE.
