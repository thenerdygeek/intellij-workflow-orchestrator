# Bug-Reproduction Scenarios (for Claude cowork)

Four real bugs the plugin owner has hit while using the Agent chat. Each was **root-caused from source** (file:line below) — your job is to *reproduce* each one precisely against the local mock so we can confirm the symptom, then confirm the fix lands. Where the default mock can't surface the condition, this doc gives you the **exact custom mock response** to author so the condition becomes deterministic.

> These are not "look around and see if it breaks" tasks. Each bug has a known mechanism. The scenario below is engineered to *trigger that mechanism*. If you don't see the symptom, the "Widen the window" notes tell you which knob to turn — don't conclude "can't reproduce" until you've tried them.

---

## 0. Prerequisites & how to drive the mock

**Environment (already running for you):**
- Mock server on 5 ports — Jira 8180, Bamboo 8280, Sonar 8380, Bitbucket 8480, **Sourcegraph 8088**.
- runIde sandbox with the plugin loaded, Connection settings pointed at the mock, SSRF dev-hatch on (`-Dworkflow.orchestrator.allowPrivateUrls=true`).
- If any of that is *not* true, say so — don't push through a half-wired environment.

**Constraints (do not violate):**
- **No backend writes.** Do not create/transition tickets, log time, create branches, merge, trigger builds, run Jira closure, or copyright-fix-all. The mock backends are safe to hit, but keep agent write-tools pointed at a throwaway scratch file only. All four scenarios below are **read-only** (read_file / list_files / attempt_completion) by design.

### Authoring a custom mock response

The Sourcegraph mock plays back a **scripted sequence of LLM turns**. You POST a turn-sequence; the mock registers it, makes it the active scenario, and resets turn indices:

```bash
curl -s -X POST http://localhost:8088/__admin/sourcegraph/scenario/custom \
  -H 'Content-Type: application/json' \
  -d @scenario.json
# → {"message":"registered+activated 'my-flow' (N turns)"}
```

**Turn schema** (the contract you author against):

```json
{
  "name": "my-flow",
  "turns": [
    {
      "thinking": "optional reasoning — rendered inside <thinking>…</thinking> on the wire",
      "text": "optional assistant prose (single chunk)",
      "textChunks": ["optional", "multi-chunk prose (overrides `text`)"],
      "toolCalls": [
        { "name": "read_file", "arguments": { "path": "build.gradle.kts" } }
      ],
      "finishReason": "tool_calls | stop | length",
      "usage": { "promptTokens": 30000, "completionTokens": 1500 },
      "error": "optional — emits a Cody event:error frame for this turn"
    }
  ]
}
```

Notes:
- Tool `name` must be a **real agent tool** (`read_file`, `list_files`, `glob_files`, `attempt_completion`, …) so the loop actually executes it and advances. Any name is *accepted* (no allow-list), but unknown names won't drive the loop forward.
- `finishReason` defaults to `tool_calls` when the turn has tool calls, else `stop`. `usage` defaults to 0/0.
- The mock advances **one turn per streamed request**. The conversation is keyed by the **first user message**, so a **new chat = a fresh turn-0 conversation**. Between repro runs, either start a new chat or re-POST the scenario (re-POST resets all indices).
- Reset turn indices without changing the scenario: `curl -X POST http://localhost:8088/__admin/reset`.

If a scenario you need can't be expressed in this schema, **say so in `docs/qa/MOCK-SERVER-REQUESTS.md`** with the gap — the mock can be extended.

---

## BUG-1 — History conversations: resume auto-iterates / can't continue

**Owner's words:** *"I'm unable to use any of the conversation from the history… when a conversation was stopped and then IDE restarted, in history it gives an option to resume; when clicked the iteration starts. But why? The iteration should not start automatically."*

**Root cause (verified):** `AgentService.resumeSession` short-circuits the loop **only** for completed sessions (`RESUME_COMPLETED_TASK`, `AgentService.kt:2863`); for any **interrupted** session it unconditionally calls `executeTask(finalPreamble)` (`AgentService.kt:3163`) — there is no "load-and-park" mode. There are two distinct "Resume" affordances: the history-card open path → `showSession` (`AgentController.kt:4341`, view-only) and the in-chat resume bar → `resumeSession` (`AgentController.kt:4446`, **runs the loop**). "Can't use history" is the same root: completed sessions expose no discoverable "continue" control, and the only resume path for interrupted ones auto-runs.

### Custom mock response — a long, interruptible flow

`bug1-interruptible.json`:

```json
{
  "name": "long-interruptible",
  "turns": [
    {"thinking":"Start with the root build script.","toolCalls":[{"name":"read_file","arguments":{"path":"build.gradle.kts"}}]},
    {"toolCalls":[{"name":"list_files","arguments":{"path":".","recursive":false}}]},
    {"toolCalls":[{"name":"read_file","arguments":{"path":"settings.gradle.kts"}}]},
    {"toolCalls":[{"name":"read_file","arguments":{"path":"gradle.properties"}}]},
    {"toolCalls":[{"name":"attempt_completion","arguments":{"kind":"done","result":"Reviewed the build configuration."}}]}
  ]
}
```

```bash
curl -s -X POST http://localhost:8088/__admin/sourcegraph/scenario/custom -d @bug1-interruptible.json
```

### Steps
1. New chat → send `review the build setup`.
2. Let it run **1–2 tool turns**, then click **Stop** (per-tool Stop or the session Stop). The session is now *interrupted* (not completed).
3. Simulate the restart: open the **History** tab (or restart the sandbox). The interrupted session shows a **Resume** affordance.
4. Open it from History → note whether it's view-only and whether continuing is discoverable.
5. Click the in-chat **Resume** bar.

### Expected vs actual
- **Expected:** opening/resuming a stopped session **loads the conversation and parks** — it waits for *your* next message; the loop starts only when you send one. A *completed* session offers an obvious "continue chatting" control.
- **Actual (BUG):** clicking Resume **immediately starts iterating** — the agent emits the next tool call with no user message in between. Completed sessions give no clear way to continue.

### Oracle / what to capture
- A new `agent.tool_use` / assistant turn appears in the transcript **with no preceding user turn** after you clicked Resume. Screenshot the transcript showing the auto-started turn, and note the exact button you clicked (card "Resume" vs in-chat resume bar — they behave differently).

### Widen the window
- If the flow completes before you can Stop, add more `read_file` turns. If you want a *completed* session to test the "can't continue" half, let it run to `attempt_completion`, then reopen from History and try to type a follow-up.

---

## BUG-2 — Long chat: scroll-to-bottom lands short, and view jumps

**Owner's words:** *"The chat UI when it becomes longer misbehaves a lot — scrolling down doesn't actually go all the way down, sometimes it jumps."*

**Root cause (verified):** `MessageList.scrollToBottom` uses Virtuoso's `scrollToIndex({index:'LAST', align:'end', behavior:'smooth'})` (`MessageList.tsx:61-66`), which positions from the **cached** item heights. Code blocks render first as ~60px Shiki skeletons and grow to 400–800px once highlighting resolves, so the cached bottom offset is **stale → the scroll stops short**. Virtuoso ships `autoscrollToBottom()` (reads the real DOM `scrollHeight`) for exactly this. The **jumps** come from `behavior:'smooth'` racing those height changes plus `ChatFooter`'s `scrollIntoView` (`ChatFooter.tsx:73-84`) fighting Virtuoso's `followOutput`.

### Custom mock response — tall content with many code blocks

The trigger is **async-growing code blocks**, so the turns must emit large fenced blocks. Keep the JSON readable by repeating a real code chunk; the *rendered height* is what matters, not the content. `bug2-codeblocks.json` (scale the blocks up until the conversation overflows several screens):

```json
{
  "name": "long-codeblocks",
  "turns": [
    {"textChunks":["Analysis of the build script:\n\n```kotlin\nplugins {\n    kotlin(\"jvm\") version \"2.1.10\"\n    id(\"org.jetbrains.intellij.platform\") version \"2.x\"\n}\nrepositories { mavenCentral(); intellijPlatform { defaultRepositories() } }\ndependencies {\n    // …repeat these lines ~40× to make the block tall…\n}\n```\n\nNow the settings file:\n"],"toolCalls":[{"name":"read_file","arguments":{"path":"build.gradle.kts"}}]},
    {"textChunks":["```kotlin\n// …another ~80-line block…\n```\n"],"toolCalls":[{"name":"read_file","arguments":{"path":"settings.gradle.kts"}}]},
    {"textChunks":["```kotlin\n// …another ~80-line block…\n```\n"],"toolCalls":[{"name":"read_file","arguments":{"path":"gradle.properties"}}]},
    {"textChunks":["```kotlin\n// …another ~80-line block…\n```\n"],"toolCalls":[{"name":"list_files","arguments":{"path":".","recursive":false}}]},
    {"textChunks":["Final summary with one more block:\n\n```kotlin\n// …another ~80-line block…\n```\n"],"toolCalls":[{"name":"attempt_completion","arguments":{"kind":"done","result":"Reviewed."}}]}
  ]
}
```

### Steps
1. POST the scenario. New chat → send a prompt; let **all** turns stream (lots of code → tall content).
2. **During streaming**, watch for **jumps** — the viewport yanking up/down as code blocks resolve from skeleton to full height.
3. After it finishes, scroll **up ~halfway**, then trigger scroll-to-bottom (the jump-to-bottom affordance, or send another short message).

### Expected vs actual
- **Expected:** scroll-to-bottom lands **flush** with the input bar (last line of the final message visible); no jumps during streaming.
- **Actual (BUG):** it lands **short** — the last code block / footer is cut off and you can still drag further down. During streaming the view jumps.

### Oracle / what to capture
- After clicking scroll-to-bottom, the bottom edge of the last message is **not** adjacent to the composer, and the scrollbar isn't at its end. Capture a screenshot + a short screen recording of the streaming jumps if you can.

### Widen the window
- Bigger code blocks (Shiki takes longer to resolve → larger stale-offset). Add a **tool-approval gate** mid-stream (the footer grows when approval UI appears → maximally fights the scroll). More turns = taller content = more obvious shortfall.

---

## BUG-3 — Opening a history conversation: the "thinking" block keeps appending

**Owner's words:** *"For the history conversation, the thinking collapsible UI component just keeps on appending."*

**Root cause (verified):** the thinking bridge (`onStreamChunk` → `appendToThinking` / `endThinking`, `AgentController.kt:2691`; batcher flush at `:518`) is the **only live push not session-gated** — every other live push checks `if (viewedSessionId != sessionId) return` (e.g. `:1513`), but the thinking path does not. Combined with `showSession` never calling `clearStream()`, opening a history session **while a loop is still streaming/tearing down** pours leftover `<thinking>` deltas into the just-hydrated history view → a thinking block that grows and never finalizes.

### Custom mock response — a long thinking block (widens the timing window)

This bug is timing-dependent: you must open a history session *while* a thinking block is still streaming. A **long** thinking block gives you a wide window. `bug3-long-thinking.json` (make the thinking string genuinely long — several paragraphs, ~2–3k chars; repeat sentences to bulk it up):

```json
{
  "name": "long-thinking",
  "turns": [
    {"thinking":"Let me reason carefully about the build configuration before touching anything. First I need to understand the module layout. … (repeat / expand this to ~2-3k characters so it streams for a noticeable beat) …","toolCalls":[{"name":"read_file","arguments":{"path":"build.gradle.kts"}}]},
    {"thinking":"Now reasoning about the settings file and how the submodules are wired together. … (another long block) …","toolCalls":[{"name":"read_file","arguments":{"path":"settings.gradle.kts"}}]},
    {"toolCalls":[{"name":"attempt_completion","arguments":{"kind":"done","result":"Done."}}]}
  ]
}
```

### Steps
1. **Pre-req:** have **≥1 prior session already in History** (run any quick chat first so History isn't empty).
2. POST `long-thinking`. New chat → send a prompt.
3. The moment the **thinking block starts streaming** (you see it growing), **quickly switch to History and open a *different* (older) session.**

### Expected vs actual
- **Expected:** opening a history session shows **only its persisted content** — no live deltas from the other session should ever append to it.
- **Actual (BUG):** the thinking collapsible in the **opened history conversation keeps appending / growing**, fed by the still-running session's leftover `<thinking>` deltas. It never finalizes (no end marker).

### Oracle / what to capture
- The thinking block in the *history* view grows **after** you opened it, and/or shows reasoning that belongs to the **other** (live) session. Capture the history session id you opened vs the live session id, and a recording of the block growing in the wrong view.

### Widen the window
- Longer thinking text (more paragraphs). Switch sessions within the first ~1s of the live thinking starting. If you keep missing it, chain two long-thinking turns so there are two streaming windows.

---

## BUG-4 — Cost reads ~$10 for one chat with <10 tool calls (Opus 4.5)

**Owner's words:** *"The cost/money which gets shown — I've seen with just one chat and less than 10 tool calls, the cost goes to $10 USD in Opus 4.5."*

**Root cause (verified — this is a data bug, not a math bug):** `core/src/main/resources/pricing.json` lists `claude-opus-4-5` at **$15 in / $75 out** per 1M tokens — the *old* Opus-4 / Opus-3 rate. The correct Opus-4.5+ rate is **$5 / $25** (confirmed authoritative: Opus 4.6/4.7/4.8 = $5/$25; 4.5 is the same pricing generation). That's a **3× overbill**, compounded by no cache discount (Sourcegraph strips cache tokens, so every context resend is billed at the full input rate). The formula (`ModelPricing.computeCost`, `ModelPricing.kt:46-58`) and the lookup (`ModelPricingRegistry.lookup(brain.modelId)`, `AgentLoop.kt:1607`) are correct — only the data is wrong. The same wrong $15/$75 rate is on the `4-6` / `4-7` entries and all their `-thinking` variants.

### Why the default mock can't show it — and the fix

The mock advertises one model id, which normalizes to `claude-sonnet-mock` (`ModelIdNormalizer` strips the `provider::apiVersion::` prefix). There is **no `claude-sonnet-mock` entry in pricing.json**, so `lookup` returns `null` and the cost **never accumulates** on the mock (you'll see a "No pricing entry for model" warning in the log). To reproduce the *exact rate magnitude* the owner saw, price the mock model the way Opus 4.5 is priced **today** via the hot-reloading user override (no mock change, no model-dropdown plumbing):

Write `~/.workflow-orchestrator/pricing.json` (the registry hot-reloads it within ~300ms):

```json
{ "claude-sonnet-mock": { "in": 15.00, "out": 75.00, "cacheRead": 1.50, "cacheWrite": 18.75 } }
```

This makes the mock model bill **identically to the shipped `claude-opus-4-5` entry**.

### Custom mock response — known, growing token usage

The agent resends the full context each turn, so input grows. Author explicit per-turn `usage` so the cost is computable. `bug4-cost.json`:

```json
{
  "name": "cost-usage",
  "turns": [
    {"toolCalls":[{"name":"read_file","arguments":{"path":"build.gradle.kts"}}],"usage":{"promptTokens":30000,"completionTokens":1500}},
    {"toolCalls":[{"name":"read_file","arguments":{"path":"settings.gradle.kts"}}],"usage":{"promptTokens":45000,"completionTokens":1500}},
    {"toolCalls":[{"name":"read_file","arguments":{"path":"gradle.properties"}}],"usage":{"promptTokens":60000,"completionTokens":1500}},
    {"toolCalls":[{"name":"list_files","arguments":{"path":".","recursive":false}}],"usage":{"promptTokens":75000,"completionTokens":1500}},
    {"toolCalls":[{"name":"read_file","arguments":{"path":"README.md"}}],"usage":{"promptTokens":90000,"completionTokens":1500}},
    {"toolCalls":[{"name":"attempt_completion","arguments":{"kind":"done","result":"Done."}}],"usage":{"promptTokens":100000,"completionTokens":2000}}
  ]
}
```

### Steps
1. Write the **buggy** override ($15/$75 above). POST `bug4-cost.json`. New chat → send a prompt; let all 6 turns run (5 tool calls + completion).
2. Read the **cost chip**.
3. Overwrite the override with the **correct** Opus-4.5 rate and re-run (new chat → same prompt):
   ```json
   { "claude-sonnet-mock": { "in": 5.00, "out": 25.00, "cacheRead": 0.50, "cacheWrite": 6.25 } }
   ```

### Expected vs actual (do the arithmetic — it's the oracle)
Σ promptTokens = 30k+45k+60k+75k+90k+100k = **400,000**; Σ completionTokens = 1500×5 + 2000 = **9,500**.
- **Buggy ($15/$75):** 400000×15/1e6 + 9500×75/1e6 = **$6.00 + $0.71 ≈ $6.71** for 6 calls.
- **Correct ($5/$25):** 400000×5/1e6 + 9500×25/1e6 = **$2.00 + $0.24 ≈ $2.24**.
- Ratio ≈ **3.0×** — that gap **is** the bug. To match the owner's "~$10 with <10 calls", scale the `usage` up (e.g. double the promptTokens, or add 2–3 more turns) — the magnitude is realistic for Opus-4.5 with full-context resends.

### Oracle / what to capture
- The displayed USD on the buggy run is **~3× the correct run** for identical usage. Capture both cost-chip values. (You don't strictly need the mock to *prove* this bug — the wrong rate is visible in `pricing.json` — but this gives a deterministic, observable before/after.)

---

## After you reproduce

For each bug, record in `docs/qa/behavioral-test-plan/RESULTS-*.md` (or a fresh `BUG-REPRO-RESULTS.md`): **Reproduced? (y/n)**, the scenario JSON you used, exact steps, the screenshot/recording/log line that is the oracle, and any deviation from "Expected." If a scenario couldn't trigger the mechanism even after "Widen the window," note which knobs you tried — that itself is signal.

If the mock needs a capability it doesn't have (e.g. you want the agent on a real `claude-opus-4-5` model id rather than the override trick, which would need the mock to advertise extra model ids), flag it in `docs/qa/MOCK-SERVER-REQUESTS.md`.
