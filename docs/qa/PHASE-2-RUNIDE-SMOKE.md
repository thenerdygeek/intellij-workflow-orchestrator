# Phase 2 runIde Smoke — coworker run sheet

**Why this exists:** Phase 2 carved the company modules `:automation` + `:handover` out of open-source Plugin **A** into private Plugin **B**, and added B's config-preset. The automated gate (tests, `verifyPlugin`, jar-content checks) all pass headless, but a few things are only observable in a running IDE: the tool-window tabs, the settings pages, and the **cross-plugin classloader calls** (B's code reaching A's classes at runtime). This sheet covers exactly those.

**Who runs it:** anyone with a desktop IntelliJ IDEA **Ultimate** (the build needs Ultimate). ~15 min.

**Prereqs**
```
git fetch && git checkout feature/plugin-split && git pull   # or: git reset --hard origin/feature/plugin-split
./gradlew --version   # JDK per gradle.properties javaVersion; Gradle 9.4
```
If a Gradle config-cache error appears, run `./gradlew --stop` then retry the command with `--rerun-tasks`. On macOS, if a step hangs on `buildSearchableOptions` while an IDE is open, add `-x buildSearchableOptions`.

There are **two** sandboxes to launch:
- **A alone** → `./gradlew runIde` (root project = Plugin A only)
- **A + B** → `./gradlew :plugin-b:runIde` (loads A *and* B together — this is the real two-plugin config)

In each launched sandbox IDE, open any Gradle/Maven project, then open the **"Workflow"** tool window (bottom dock) and **Settings → Tools → Workflow Orchestrator**.

---

## Part 1 — Plugin A ALONE (`./gradlew runIde`)

Goal: A is standalone-clean with the company modules gone.

| # | Check | PASS = |
|---|---|---|
| 1.1 | "Workflow" tool window tabs | **No "Automation" tab, no "Handover" tab** (you should see Sprint / PR / Build / Quality / Agent / Insights only). No error popup. |
| 1.2 | Settings → Workflow Orchestrator | **No "Automation" page, no "Handover" page** under the group. (Copyright-fix code exists in `:core` but has no UI in A alone — expected.) |
| 1.3 | `idea.log` (Help → Show Log in …) | grep for `LinkageError` / `NoClassDefFoundError` / `ClassNotFoundException` → **none** related to `automation`/`handover`. |
| 1.4 | (optional) Settings → … → commit-message / quick-clipboard defaults | Quick-clipboard chip defaults contain **no** `docker.*` / `automation.url`; the Bamboo build-variable field is **blank**; default target branch is **"main"**. |

❌ If an Automation/Handover tab or settings page appears in A-alone, or a `NoClassDefFoundError` mentions `automation`/`handover` → **STOP and report** (that's a carve leak).

---

## Part 2 — Plugin A + B (`./gradlew :plugin-b:runIde`)

Goal: B welds the company workflow back on, and the cross-plugin calls resolve.

| # | Check | PASS = |
|---|---|---|
| 2.1 | "Workflow" tool window | **Automation tab AND Handover tab are back** (appear after the default tabs). No error. |
| 2.2 | Settings → Workflow Orchestrator | **"Automation" and "Handover" pages nest under the group** (not under "Other Settings"). |
| 2.3 | **Automation → "Trigger Customized…"** (the per-stage trigger button) | The **`ManualStageDialog` opens**. ⭐ This is the highest-risk path — B's automation panel calls A's `:bamboo` UI class across the plugin classloader boundary; a `NoClassDefFoundError` would appear ONLY here. |
| 2.4 | **Handover → copyright "Fix All" / Rescan** | The copyright scan runs (reads changelist via the platform `ChangeListManager`). Confirms B needs no Git4Idea dependency. (If `copyrightTemplate` is blank it aborts gracefully with "set a template" — that's fine.) |
| 2.5 | A handover action | e.g. a quick-clipboard chip copies, or a Jira closure comment builds. |
| 2.6 | **Config preset (fresh project):** open a project that has never had this plugin configured | Quick-clipboard chips show the **company set incl. `docker.tag`/`docker.tagsJson`/`automation.url`**; the Bamboo build-variable field shows **"DockerTagsAsJSON"**; default target branch is **"develop"**. |
| 2.7 | **One-shot seed:** in 2.6's project, remove a company chip (e.g. `docker.tag`) in Settings, apply, **restart the sandbox** | The removed chip **stays removed** (it is NOT re-added). |
| 2.8 | `idea.log` | Contains `[PluginSplit] active WorkflowConfig impl: CompanyBWorkflowConfig` and `[PluginSplit] applied ConfigPreset company defaults (one-shot)`. No `LinkageError`/`NoClassDefFoundError`. |

❌ Report any `NoClassDefFoundError`/`LinkageError` (esp. at 2.3), a missing tab/page (2.1/2.2), or wrong preset values (2.6).

---

## What to report back
For each numbered check: ✅ / ❌ (+ a one-line note or the log excerpt on ❌). The two log greps (1.3, 2.8) and check 2.3 (`ManualStageDialog`) are the most important. If everything is ✅, Phase 2 is runtime-confirmed.
