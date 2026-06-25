# How to Run the Tests — Beginner's Guide

This guide assumes **no prior experience** with this project, Gradle, or IntelliJ plugin
development. Follow it top to bottom. Every command is copy-paste-ready.

---

## 0. First, what does "tests" mean here?

There are **two completely different kinds of testing** in this project. Don't mix them up:

| Kind | What it is | How long | Needs a license? |
|---|---|---|---|
| **A. Automated tests** | Code that checks other code, run from the command line. **No app window opens.** Fast, repeatable. Answers *"is the logic correct?"* | minutes | No |
| **B. Interactive runIde smoke** | Launches a real sandbox IDE you **click through by hand**. Answers *"does it actually work inside IntelliJ?"* | manual | **Yes** (IntelliJ Ultimate) |

This guide is mostly about **(A) the automated tests** — sections 1–6.
For **(B) the interactive smoke**, section 7 hands you off to `WINDOWS-RUNIDE-CHECKLIST.md`.

> **Mental model:** Gradle is the project's "recipe runner." You give it a goal like
> `test` and it figures out everything that needs to happen first (download libraries,
> compile the code, then run the tests) and does it in order. You never call the
> compiler or the test engine yourself — you just ask Gradle for the goal.

---

## 1. One-time setup

You only do this once per machine.

### 1a. Install Git
Git is how you download the code.
- **Windows:** install from <https://git-scm.com/download/win> (accept the defaults).
- **macOS:** it's usually already there. Check with `git --version`. If missing, run `xcode-select --install`.

### 1b. Java (JDK 21)
The project compiles with **Java 21**.
- **Good news:** if you don't have it, Gradle will **download the right Java for you
  automatically** the first time you build (via a tool called "foojay toolchains").
  You don't strictly have to install anything.
- To check what you have, open a terminal and run:
  ```
  java -version
  ```
  If it prints `21.x.x` you're set. If it prints something else or "command not found",
  that's fine — Gradle handles it. (If you *want* to install it yourself, get a JDK 21
  from <https://adoptium.net>.)

### 1c. Get the code
Pick a folder where you keep projects, open a terminal there, and run:

```
git clone https://github.com/thenerdygeek/intellij-workflow-orchestrator.git
cd intellij-workflow-orchestrator
git checkout feature/plugin-split
git pull
```

> **Why this branch?** The plugin-split work (and the build fixes + the diagnostic log
> this guide relies on) live on `feature/plugin-split`, **not** on `main`.

**Sanity check you're on the right branch:** confirm a `plugin-b` folder exists at the
top level of the project:
- **Windows:** `dir plugin-b`
- **macOS:** `ls plugin-b`

If that folder is missing, you're on the wrong branch — re-run the `git checkout` above.

---

## 2. Open a terminal *in the project folder*

Every command below must be run from the project's **root folder** (the one containing
`gradlew`, `gradlew.bat`, and `settings.gradle.kts`).

- **Windows:** open the project folder in File Explorer, click the address bar, type
  `cmd`, and press Enter. (Or open PowerShell and `cd` into the folder.)
- **macOS:** open Terminal and `cd` into the folder, e.g.
  `cd ~/projects/intellij-workflow-orchestrator`.

**`gradlew` vs `gradlew.bat`:** this is the "Gradle wrapper" — a script bundled in the
repo that uses the exact right Gradle version, so you don't install Gradle yourself.
- On **Windows** you type `gradlew.bat ...`
- On **macOS/Linux** you type `./gradlew ...`

(The rest of this guide shows both.)

---

## 3. Run ALL the automated tests

This is the main command:

```
# Windows
gradlew.bat test

# macOS / Linux
./gradlew test
```

**What happens the first time:**
1. Gradle downloads itself + all the project's libraries (and Java 21 if needed). This
   can take **several minutes** and a few hundred MB — **this is normal and one-time.**
   Later runs are much faster.
2. It compiles every module.
3. It runs the test suite across all modules (`core`, `jira`, `bamboo`, `sonar`,
   `pullrequest`, `automation`, `handover`, `agent`, `web`, and the architecture-rule
   checks in `konsist`).

**What SUCCESS looks like** — the very last lines will say:
```
BUILD SUCCESSFUL in 3m 47s
```

**What FAILURE looks like:**
```
> There were failing tests. See the report at: .../build/reports/tests/test/index.html
BUILD FAILED in ...
```

> ⚠️ **Read the final `BUILD SUCCESSFUL` / `BUILD FAILED` line** — that is the source of
> truth. Don't judge success by anything else, and don't pipe the command through
> `| tail` or similar: that hides Gradle's real result behind the pipe's, and you can be
> fooled into thinking a failed build passed. (We got bitten by exactly this.)

**Tip — keep going after a failure:** by default Gradle stops at the first failing
module. To run *everything* and collect *all* failures in one pass, add `--continue`:
```
gradlew.bat test --continue
```

---

## 4. See the detailed results (the HTML report)

The console only summarizes. For a clickable, per-test breakdown, open the **HTML
report**. Each module writes its own:

```
<module>/build/reports/tests/test/index.html
```

For example, the core module's report:
- **Windows:** `start core\build\reports\tests\test\index.html`
- **macOS:** `open core/build/reports/tests/test/index.html`

Open it in any browser. Green = passed, red = failed; click a failed test to see the
exact assertion and stack trace.

---

## 5. Run a smaller subset (much faster)

You rarely need the whole suite while iterating. You can narrow it down:

**One module** (e.g. just `core`):
```
gradlew.bat :core:test
./gradlew :core:test
```

**One test class** (use `--tests` with the full class name):
```
gradlew.bat :core:test --tests "com.workflow.orchestrator.core.settings.SettingsMigrationTest"
./gradlew :core:test --tests "com.workflow.orchestrator.core.settings.SettingsMigrationTest"
```

**One test method** (class name + method, in quotes):
```
./gradlew :core:test --tests "com.workflow.orchestrator.core.settings.SettingsMigrationTest.v0 fresh install*"
```

> **Why is my test "UP-TO-DATE" / not running again?** Gradle is smart: if nothing
> changed since the last run, it skips re-running and reports the cached result (you'll
> see `> Task :core:test UP-TO-DATE`). `UP-TO-DATE` means *it passed last time on
> unchanged inputs* — that's still a pass. To force a real re-run, add `--rerun-tasks`.

---

## 6. The tests that matter for the plugin-split

If you specifically want to validate the plugin-split work, these are the key classes:

```
# The settings migration (the "develop" -> "main" de-convention) and its XML round-trip
gradlew.bat :core:test --tests "com.workflow.orchestrator.core.settings.SettingsMigrationTest" --tests "com.workflow.orchestrator.core.settings.SettingsMigrationSerializationTest" --tests "com.workflow.orchestrator.core.settings.RepoConfigDefaultBranchTest"

# The default-branch resolver helpers
gradlew.bat :core:test --tests "com.workflow.orchestrator.core.util.DefaultBranchResolverTest"

# The agent system-prompt integration-gating (1b) golden snapshots live in :agent
gradlew.bat :agent:test --tests "*EnvironmentDetailsBuilder*" --tests "*DefaultBranchCache*"

# The settings-anchor / Plugin-B nesting contract lives in :konsist
gradlew.bat :konsist:test
```

Each should end in `BUILD SUCCESSFUL`.

---

## 7. Running the interactive runIde smoke (the sandbox IDE)

This is the **(B)** kind — a real IDE window you click through. It is fully scripted,
**read-only**, step-by-step, in **`WINDOWS-RUNIDE-CHECKLIST.md`**. The short version:

```
# Plugin A only:
gradlew.bat runIde

# Plugin A + Plugin B together (needed for the split check):
gradlew.bat :plugin-b:runIde
```

Key things a first-timer must know (the checklist covers each in detail):
- The **first** `runIde` downloads IntelliJ IDEA Ultimate (~1.5 GB) — be patient.
- `runIde` launches **Ultimate**, which is **license-gated**. If you don't have a
  license, click **"Start trial"** at the dialog to get the free 30-day Ultimate trial —
  otherwise the sandbox just sits on the license screen.
- A **separate** IDE window opens — that's the sandbox, with the plugin pre-installed.
  Open any project in it to start the plugin.
- **Do not click any backend-write button** (create/merge/transition/trigger/etc.). The
  checklist marks every one with ⛔ SKIP.
- When something fails, you **don't** send the giant `idea.log`. You send the small
  plugin-only log: **`%USERPROFILE%\.workflow-orchestrator\diagnostics\plugin-0.log`**.

---

## 7.1 Automated UI tests (Remote Robot) — the sandbox, driven by code

There's a **third** kind of test: ones that launch a real sandbox IDE and drive it
(clicks, assertions, screenshots) **without a human**, via JetBrains' **Remote Robot**.
Today there's one proof test — open the Workflow tool window → assert its tabs →
screenshot.

Like the manual smoke, this needs a **licensed Ultimate + a display**, so it runs on your
licensed Windows box (or CI under Xvfb) — **not** a license-less headless machine. It's a
**two-terminal** flow (the IDE blocks in one terminal; the tests drive it from the other):

```
# Terminal 1 — launch the sandbox IDE with the Robot Server (this blocks):
gradlew.bat runIdeForUiTests

# Terminal 2 — once it's fully started, run the tests against it:
gradlew.bat uiTest
```

First-timer notes:
- The XPath component locators are **best-guesses** for 2025.1 — while the sandbox runs,
  open the live component tree at **http://127.0.0.1:8082** and tune them to match.
- The **Agent tab is a JCEF/Chromium webview** and can't be inspected by Remote Robot, so
  that part stays manual (see `WINDOWS-RUNIDE-CHECKLIST.md`).
- Screenshots land in `build/ui-test-screenshots/`.
- Full details live in the test's own header comment:
  `src/uiTest/kotlin/com/workflow/orchestrator/uitest/WorkflowToolWindowSmokeTest.kt`.

---

## 8. Troubleshooting (common first-timer snags)

| Symptom | What it means / fix |
|---|---|
| First build is very slow / downloads lots | Normal, one-time. Gradle is fetching itself, Java, and all libraries. Later runs are fast. |
| `'gradlew' is not recognized` / `command not found` | You're not in the project root, or you used the wrong form. Windows = `gradlew.bat`; macOS/Linux = `./gradlew` (note the `./`). |
| `Permission denied: ./gradlew` (macOS/Linux) | Run `chmod +x gradlew` once, then retry. |
| `JAVA_HOME is set to an invalid directory` | Either unset `JAVA_HOME` (let Gradle auto-provision Java 21), or point it at a real JDK 21. |
| `BUILD FAILED` mentioning `duplicate ... searchableOptions.jar` | An old build issue — already fixed on `feature/plugin-split`. Make sure you ran `git pull` on that branch. |
| `runIde` window never appears / stuck on a license screen | That's the Ultimate license wall — start the free trial, or use a machine with an activated Ultimate. (See section 7.) |
| Tests show `UP-TO-DATE` and don't re-run | They passed last time on unchanged code. Add `--rerun-tasks` to force them. |
| Want to see *why* a test failed | Open the HTML report (section 4) and click the red test. |

---

## Quick reference

```
# everything (automated)
gradlew.bat test                 # ./gradlew test
gradlew.bat test --continue      # run all modules even if one fails

# narrower
gradlew.bat :core:test
gradlew.bat :core:test --tests "com.workflow.orchestrator.core.settings.SettingsMigrationTest"

# the interactive sandbox IDE (see WINDOWS-RUNIDE-CHECKLIST.md)
gradlew.bat runIde
gradlew.bat :plugin-b:runIde
```

Reports: `<module>/build/reports/tests/test/index.html`
Plugin diagnostic log (share on smoke failure): `~/.workflow-orchestrator/diagnostics/plugin-0.log`
