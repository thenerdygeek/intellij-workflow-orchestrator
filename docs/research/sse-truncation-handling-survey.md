# SSE truncation handling — survey of leading agentic tools

Date: 2026-05-05
Scope: How Cline, Roo Code, Continue.dev, Aider, OpenCode, the Anthropic SDK, and Sourcegraph Cody handle the case where a streaming HTTP response is alive but the provider injects an error event into the stream (rate-limit, gateway-deadline, server cancellation) — particularly when the partial assistant message contains an unfinished tool call.

Repos surveyed (cloned shallow into `/tmp/sse-research/`):
- `cline` — `https://github.com/cline/cline` (default branch tip 2026-05)
- `roo` — `https://github.com/RooCodeInc/Roo-Code`
- `continue` — `https://github.com/continuedev/continue`
- `aider` — `https://github.com/Aider-AI/aider`
- `opencode` — `https://github.com/sst/opencode`
- `anthropic-sdk` — `https://github.com/anthropics/anthropic-sdk-typescript`
- `cody-jetbrains` — `https://github.com/sourcegraph/jetbrains` (Sourcegraph's IDE plugin; the public `sourcegraph/cody` monorepo is gone — 404)

Plus Anthropic's official documentation on `platform.claude.com`.

---

## 1. Executive summary

Five things are true across the field:

1. **There is no "resume from partial tool call" pattern in any production OSS agent.** Anthropic's own docs say it explicitly: *"Tool use and extended thinking blocks cannot be partially recovered. You can resume streaming from the most recent text block."* Everyone discards the partial tool call.
2. **The canonical pattern is "abort the assistant turn, save with an interruption marker, retry the entire turn."** Cline, Roo, OpenCode all do this. They differ only in *how* they retry (full task reinit vs. push-onto-stack vs. Effect retry policy with `retry-after` honour) and *what marker* they leave (`[Response interrupted by API Error]` is a literal that appears in Cline and Roo verbatim).
3. **One escape hatch exists for the related "max-tokens hit" case (`finishReason: length`):** Aider implements Anthropic's documented prefill-continuation pattern (push the partial assistant content back as `assistant` with `prefix=True`, recall). It is gated on `model.info.supports_assistant_prefill` and Anthropic's docs confirm Claude 4.6, 4.7, Sonnet 4.6, and Opus Mythos do **not** support prefill — so this path is dead for the models we use. (The Cline/Roo handling for `finishReason: length` is identical to mid-stream-error: discard partial, retry.)
4. **Continue.dev shows the cleanest user-facing repair pattern for our exact case:** when "Premature Close" happens during a tool-args stream, mark the in-flight tool call as failed with a synthetic tool-result that tells the model *"this tool call was aborted mid-stream because the arguments took too long to stream or there were network issues. Please re-attempt by breaking the operation into smaller chunks or trying something else."* The model sees a normal tool failure on the next turn and adapts. **This is what we should port** — it converts the truncation into the language the LLM already understands (a tool error) and steers the model toward smaller plans without regenerating the full plan from scratch.
5. **Our current "[TOOL_CALL_TRUNCATED]" sentinel is anti-pattern.** It pollutes the assistant's text content (which the LLM may then mimic), and crucially it does NOT lead to a structured retry — it falls into our generic empty-response loop. The sentinel should die; the canonical response is (a) drop the partial XML, (b) save the assistant message with an interruption marker, (c) inject a synthetic user message in the form of either a tool error (Continue's pattern, if we tracked that the tool was in-flight) or a "your previous response was interrupted, retry with smaller steps" nudge (Anthropic's documented Claude-4.6 pattern at `platform.claude.com/docs/en/api/messages-streaming` § "Error recovery → Claude 4.6").

---

## 2. Cline — full detail

### 2.1 Where the SSE error surfaces (provider layer)

`/tmp/sse-research/cline/src/core/api/providers/anthropic.ts:64-303`

The `createMessage` async generator iterates the Anthropic SDK stream with **no try/catch around the `for await` loop**. Mid-stream errors thrown by the SDK propagate straight out of the generator.

```typescript
// cline/src/core/api/providers/anthropic.ts:183-302
for await (const chunk of stream) {
    switch (chunk?.type) {
        case "message_start": …
        case "content_block_delta":
            switch (chunk.delta.type) {
                case "input_json_delta":
                    if (lastStartedToolCall.id && lastStartedToolCall.name && chunk.delta.partial_json) {
                        yield { type: "tool_calls", tool_call: { … } }
                    }
                …
```

The `@withRetry()` decorator at `cline/src/core/api/retry.ts:29-87` only retries `429` or `RetriableError`. Mid-stream `APIError` from the SDK (which is what `event: error` SSE frames become in the `@anthropic-ai/sdk` parser at `/tmp/sse-research/anthropic-sdk/src/core/streaming.ts:104-108`) is **not** retried at this layer.

```typescript
// anthropic-sdk/src/core/streaming.ts:104-108 — how event:error becomes APIError
if (sse.event === 'error') {
    const body = safeJSON(sse.data) ?? sse.data;
    const type = body?.error?.type as ErrorType | undefined;
    throw new APIError(undefined, body, undefined, response.headers, type);
}
```

### 2.2 First-chunk vs. mid-stream split

`/tmp/sse-research/cline/src/core/task/index.ts:1865-2210` — `attemptApiRequest` explicitly distinguishes the two cases. The first chunk is awaited inside a try/catch that handles auth/rate-limit/quota/context-window-exceeded with structured retry; **all subsequent chunks are yielded outside the try/catch on purpose**:

```typescript
// cline/src/core/task/index.ts:2025-2210
try {
    // awaiting first chunk to see if it will throw an error
    this.taskState.isWaitingForFirstChunk = true
    const firstChunk = await iterator.next()
    yield firstChunk.value
    this.taskState.isWaitingForFirstChunk = false
} catch (error) {
    // …structured first-chunk error handling: context-window check, auto-retry up to 3
    //    times with 2s/4s/8s backoff, ask user "api_req_failed" if exhausted, then
    //    `yield* this.attemptApiRequest(previousApiReqIndex)` recursive retry.
} finally { /* … */ }

// no error, so we can continue to yield all remaining chunks
// (needs to be placed outside of try/catch since we want caller to handle errors not
//  with api_req_failed as that is reserved for first chunk failures only)
yield* iterator
```

The reason for the split is documented in line 2042-2043:
> *"this api_req_failed ask is unique in that we only present this option if the api hasn't streamed any content yet (ie it fails on the first chunk due), as it would allow them to hit a retry button. However if the api failed mid-stream, it could be in any arbitrary state where some tools may have executed, so that error is handled differently and requires cancelling the task entirely."*

### 2.3 Mid-stream consumer — `StreamChunkCoordinator` + `recursivelyMakeClineRequests`

`/tmp/sse-research/cline/src/core/task/StreamChunkCoordinator.ts:67-118` — a background `pump` task drives `iterator.next()`; any thrown error is captured into `this.readError` and re-thrown the next time the consumer calls `nextChunk()`.

```typescript
// cline/src/core/task/StreamChunkCoordinator.ts:67-89
private startPump(): Promise<void> {
    return (async () => {
        try {
            while (!this.stopRequested) {
                const { value: chunk, done } = await this.iterator.next()
                if (done || !chunk) break
                if (chunk.type === "usage") { this.options.onUsageChunk(chunk); continue }
                this.queue.push(chunk)
                this.notifyWaiter()
            }
        } catch (error) {
            this.readError = error
        } finally {
            this.completed = true
            this.notifyWaiter()
        }
    })()
}
```

`/tmp/sse-research/cline/src/core/task/index.ts:3008-3061` — the consumer loop catches the re-throw and does the canonical mid-stream sequence:

```typescript
// cline/src/core/task/index.ts:3008-3061
} catch (error) {
    await streamCoordinator?.stop()
    if (!this.taskState.abandoned) {
        const clineError = ErrorService.get().toClineError(error, this.api.getModel().id)
        const errorMessage = clineError.serialize()
        const isStreamingSpendLimitError = clineError.isErrorType(ClineErrorType.SpendLimit)
        if (!isStreamingSpendLimitError && this.taskState.autoRetryAttempts < 3) {
            this.taskState.autoRetryAttempts++
            const delay = 2000 * 2 ** (this.taskState.autoRetryAttempts - 1)   // 2s, 4s, 8s
            await this.say("error_retry", JSON.stringify({ attempt, maxAttempts: 3, delaySeconds, errorMessage }))
            setTimeoutPromise(delay).then(async () => {
                if (this.controller.task) {
                    this.controller.task.taskState.autoRetryAttempts = this.taskState.autoRetryAttempts
                    await this.controller.task.handleWebviewAskResponse("yesButtonClicked", "", [])
                }
            })
        } else if (this.taskState.autoRetryAttempts >= 3) {
            await this.say("error_retry", JSON.stringify({ failed: true, errorMessage, … }))
        }

        this.abortTask()                              // <- KEY: full task abort
        await abortStream("streaming_failed", errorMessage)
        await this.reinitExistingTaskFromId(this.taskId)   // <- KEY: reload from disk
    }
}
```

### 2.4 What `abortStream("streaming_failed")` does to the partial assistant message

`/tmp/sse-research/cline/src/core/task/index.ts:2723-2782`:

```typescript
const abortStream = async (cancelReason: ClineApiReqCancelReason, streamingFailedMessage?: string) => {
    Session.get().finalizeRequest()
    if (this.diffViewProvider.isEditing) await this.diffViewProvider.revertChanges()

    // if last message is a partial we need to update and save it
    const lastMessage = this.messageStateHandler.getClineMessages().at(-1)
    if (lastMessage?.partial) lastMessage.partial = false

    await finalizeApiReqMsg(cancelReason, streamingFailedMessage)
    await this.messageStateHandler.saveClineMessagesAndUpdateHistory()

    // Let assistant know their response was interrupted for when task is resumed
    await this.messageStateHandler.addToApiConversationHistory({
        role: "assistant",
        content: [{
            type: "text",
            text: assistantMessage + `\n\n[${
                cancelReason === "streaming_failed"
                    ? "Response interrupted by API Error"
                    : "Response interrupted by user"
            }]`,
        }],
        modelInfo, metrics, ts: Date.now(),
    })
    this.taskState.didFinishAbortingStream = true
}
```

So the partial assistant text (which may end mid-tool-call XML) is persisted **with the literal `[Response interrupted by API Error]` suffix appended**. No attempt to repair the tool XML. The next round, when the resumed task replays this assistant turn, the model sees its own truncated XML followed by the explicit interruption marker and naturally regenerates a fresh tool call.

### 2.5 What the partial-tool-call parser sees (`parseAssistantMessageV2`)

`/tmp/sse-research/cline/src/core/assistant-message/parse-assistant-message.ts:212-237` — when the input string ends mid-block, the open block is added to the output with `partial: true`:

```typescript
// Finalize any open tool use (which might contain the finalized partial param)
if (currentToolUse) {
    // Tool use is partial because the loop finished before its closing tag
    contentBlocks.push(currentToolUse)
}
```

`/tmp/sse-research/cline/src/core/task/index.ts:3181-3184` — at end-of-stream finalization, all blocks still marked partial are flipped to non-partial (because the stream is over) **but no executor change is made**. Tool blocks marked partial-then-flipped will simply have missing required params and surface as a normal tool failure:

```typescript
const partialBlocks = this.taskState.assistantMessageContent.filter((block) => block.partial)
partialBlocks.forEach((block) => { block.partial = false })
```

The presenter at `cline/src/core/task/index.ts:2304-2317` calls `toolExecutor.executeTool(block)` on these final blocks; the executor's per-tool handler validates required params and produces a tool error if they're missing.

### 2.6 Retry policy on stream failure

- 3 auto-retries with 2s/4s/8s exponential backoff (`cline/src/core/task/index.ts:3016-3041`).
- Skips auto-retry for `SpendLimit`, `Auth`, `QuotaExceeded`, `Balance` (`cline/src/core/task/index.ts:2099-2105`).
- Same content is replayed (no `max_tokens` reduction, no model switch). Cline relies on Anthropic's own prompt cache to keep regeneration cheap.
- After auto-retries exhaust, surfaces `error_retry` to the user with `failed: true`; user must press a retry button to proceed.

### 2.7 The Anthropic "continuation" pattern in Cline

Cline does **not** implement Anthropic's documented prefill-continuation pattern. The closest analog is `attemptApiRequest`'s recursive call (which restarts the whole turn from the same conversation history with the partial assistant message saved + interruption marker — so the model is asked to "continue" via natural conversation flow, not via prefill).

---

## 3. Secondary targets

### 3.1 Roo Code

`/tmp/sse-research/roo/src/core/task/Task.ts:3244-3297`. Same pattern as Cline (Roo is a Cline fork) with **one important divergence**: instead of `reinitExistingTaskFromId` (full task instance recreation from disk), Roo pushes the same user content back onto a stack with an incremented `retryAttempt` counter and continues the existing instance:

```typescript
// roo/src/core/task/Task.ts:3267-3296
console.error(`[Task#${this.taskId}.${this.instanceId}] Stream failed, will retry: ${streamingFailedMessage}`)
const stateForBackoff = await this.providerRef.deref()?.getState()
if (stateForBackoff?.autoApprovalEnabled) {
    await this.backoffAndAnnounce(currentItem.retryAttempt ?? 0, error)
    if (this.abort) { /* … */ break }
}
stack.push({
    userContent: currentUserContent,
    includeFileDetails: false,
    retryAttempt: (currentItem.retryAttempt ?? 0) + 1,
})
continue
```

The `[Response interrupted by API Error]` suffix and the partial-tool discard are identical to Cline (lines 3055/3065).

### 3.2 Continue.dev — the "tool call was aborted mid-stream" pattern

`/tmp/sse-research/continue/gui/src/redux/thunks/streamNormalInput.ts:257-291` is the **most directly applicable** pattern for our problem. When the stream throws and there are tool calls currently being generated, Continue:

1. Marks each generating tool call as `errorToolCall` with a synthetic tool result.
2. The synthetic content explicitly tells the model how to recover.

```typescript
// continue/gui/src/redux/thunks/streamNormalInput.ts:257-291
} catch (e) {
    const toolCallsToCancel = selectCurrentToolCalls(getState());
    posthog.capture("stream_premature_close_error", { /* … */ });
    if (
        toolCallsToCancel.length > 0 &&
        e instanceof Error &&
        e.message.toLowerCase().includes("premature close")
    ) {
        for (const tc of toolCallsToCancel) {
            dispatch(
                errorToolCall({
                    toolCallId: tc.toolCallId,
                    output: [{
                        name: "Tool Call Error",
                        description: "Premature Close",
                        content: `"Premature Close" error: this tool call was aborted mid-stream because the arguments took too long to stream or there were network issues. Please re-attempt by breaking the operation into smaller chunks or trying something else`,
                        icon: "problems",
                    }],
                }),
            );
        }
    } else {
        throw e;
    }
}
```

Continue's provider layer (`continue/core/llm/streamChat.ts:117-160`) does no special handling — it just re-throws errors to the GUI, which is where the structured handling lives.

### 3.3 Aider — Anthropic prefill continuation

`/tmp/sse-research/aider/aider/coders/base_coder.py:1457-1505` — the textbook implementation of Anthropic's prefill-continuation pattern, but **gated on `model.info.get("supports_assistant_prefill")`**:

```python
except FinishReasonLength:
    # We hit the output limit!
    if not self.main_model.info.get("supports_assistant_prefill"):
        exhausted = True
        break

    self.multi_response_content = self.get_multi_response_content_in_progress()

    if messages[-1]["role"] == "assistant":
        messages[-1]["content"] = self.multi_response_content
    else:
        messages.append(
            dict(role="assistant", content=self.multi_response_content, prefix=True)
        )
```

This handles `finishReason: length` (max-tokens hit), not arbitrary mid-stream errors. For non-length errors Aider relies on litellm's retry decorator with `2 ** attempt` backoff up to `RETRY_TIMEOUT` (`aider/aider/coders/base_coder.py:1461-1488`) — the same content is replayed entirely.

The prefill path is **dead for the models the plugin targets**: Anthropic explicitly states Opus 4.7, 4.6, Sonnet 4.6 do not support prefill (https://platform.claude.com/docs/en/api/errors → "Common validation errors → Prefill not supported"). Sourcegraph's gateway proxies upstream Anthropic so it inherits the same restriction.

### 3.4 OpenCode — Vercel AI SDK + Effect retry

`/tmp/sse-research/opencode/packages/opencode/src/session/llm.ts:336-415` uses the Vercel AI SDK's `streamText` with `experimental_repairToolCall`. For *malformed* tool calls (missing parameters, bad JSON) it converts the tool call to an "invalid" tool that returns the parse error — letting the model see a normal tool failure on the next round. For *mid-stream* errors it relies on the SDK's `onError` callback + Effect's `Schedule.fromStepWithMetadata` retry policy at `/tmp/sse-research/opencode/packages/opencode/src/session/retry.ts:106-123`:

```typescript
// opencode/packages/opencode/src/session/retry.ts:54-77
export function retryable(error: Err) {
    if (MessageV2.ContextOverflowError.isInstance(error)) return undefined
    if (MessageV2.APIError.isInstance(error)) {
        const status = error.data.statusCode
        // 5xx errors are transient server failures and should always be retried
        if (!error.data.isRetryable && !(status !== undefined && status >= 500)) return undefined
        if (error.data.responseBody?.includes("FreeUsageLimitError")) return GO_UPSELL_MESSAGE
        return error.data.message.includes("Overloaded") ? "Provider is overloaded" : error.data.message
    }
    // …rate-limit text patterns, JSON-error parsing for too_many_requests…
}
```

`opencode/packages/opencode/src/session/retry.ts:21-52` honours `retry-after-ms` and `retry-after` headers; falls back to `2000 * 2^(attempt-1)` capped at 30s when no headers. The whole turn restarts from the same `messages` (no continuation).

### 3.5 Anthropic SDK — error chunk handling

`/tmp/sse-research/anthropic-sdk/src/core/streaming.ts:104-108` — `event: error` SSE frame is converted to a thrown `APIError` mid-iteration. There is no partial-content recovery hook in the SDK; consumers must implement it themselves. The `lib/MessageStream.ts:371-389` `#handleError` path is for SDK abort signals only; it does not attempt continuation.

### 3.6 OpenCode brief — same as `:agent` BrainRouter `event: error` handling

OpenCode's pattern matches what `:core/CodyStreamSseParser.kt:62-74` already does for the `event: error` frame — surface the message to the user. The gap is what happens *after* surfacing.

### 3.7 Sourcegraph Cody — public source unavailable

The `https://github.com/sourcegraph/cody` repo returns 404 (Sourcegraph closed-sourced or moved it). The remaining `https://github.com/sourcegraph/jetbrains` Kotlin plugin uses Cody's JSON-RPC agent subprocess and does not parse the SSE stream itself, so it has no `process_completion` handling. Public web search shows the error string as a recurring user complaint (https://github.com/sourcegraph/cody/issues/5858, https://community.sourcegraph.com/t/error-request-failed-context-deadline-exceeded-since-update-to-version-7-10-1-7-11-1/1888) with no documented retry pattern.

---

## 4. Sourcegraph-specific findings

The error string `{"message":"context deadline exceeded","type":"completion.process_completion"}` is emitted by Sourcegraph's Cody Gateway when its per-request deadline fires before the upstream Anthropic stream completes. It is **gateway-internal**; Anthropic itself does not emit this `type`. There is no known consumer code (Cline, Continue, OpenCode, Aider, opencode) with special handling for this string.

What exists in our plugin already (no new work needed for transport):
- `core/CodyStreamSseParser.kt:62-74` — `event: error` frames are surfaced as `ParseResult.Error(message)`. The "context deadline exceeded" payload would currently surface as a user-visible assistant message via `BrainRouter` (per `agent/CLAUDE.md` § BrainRouter description: *"Gateway-emitted `event: error` frames … surface as a user-visible assistant message: 'Sourcegraph rejected this attachment: …'"*).
- `core/SourcegraphChatClient.kt:548-553` — current handling is `[TOOL_CALL_TRUNCATED]` text-content sentinel. This is the anti-pattern called out in the executive summary.

What is missing:
- Detection that the SSE error frame fired **after** at least one `delta_tool_calls` arrived (i.e., the truncation specifically broke a tool call) vs. fired before any content (i.e., the request never got off the ground).
- A mechanism to convert the truncated tool call into a structured tool-result error for the next round (Continue's pattern).
- Retry budget that doesn't double-count: today the empty-response retry loop catches this case with the wrong nudge ("provider returned an empty response — check model/provider configuration"), which is misleading for the user.

---

## 5. Recommended port for our plugin

### 5.1 Pattern to copy

**Continue.dev's pattern (§3.2), with Cline's structural placement (§2.4).**

Why:
- Anthropic explicitly says tool-use blocks cannot be partially recovered (`platform.claude.com/docs/en/api/messages-streaming` § "Error recovery best practices"). So we accept the partial XML is dead.
- Of the surveyed agents, Continue is the only one that *uses the model's own language* (a tool result with an error description) to communicate the failure mode — every other agent either restarts the whole turn or relies on the user pressing retry. Tool errors are an interface the LLM already understands and adapts to without explicit prompting.
- Cline/Roo's "save with `[Response interrupted by API Error]` marker, then retry" is the right disk-side persistence shape. We should adopt the literal marker for compatibility with anyone reading the conversation log.
- Aider's prefill-continuation is the *theoretically correct* solution for max-tokens-length, but it's blocked on the models we use (Opus 4.7, 4.6, Sonnet 4.6) — Anthropic confirms prefill is unsupported. Skip.

### 5.2 Sketch — what the Kotlin shape looks like

> **Status (2026-05-05): IMPLEMENTED.** See `docs/superpowers/plans/2026-05-05-sse-gateway-timeout-handling.md` and commits on `feature/context-compaction`.

This is design only; do not implement until the plan is confirmed.

**Stage 1 — Detect that the truncation broke a tool call (in `:core`):**

`SourcegraphChatClient.kt` and `SourcegraphCompletionsStreamClient.kt` already accumulate tool-call deltas during streaming. When the SSE parser emits `ParseResult.Error(message)` AND there is at least one in-flight `delta_tool_calls` accumulator that has not received a corresponding stop (`finish_reason`), we know the truncation broke a tool call rather than just text.

Add a third finish reason to the existing `ChatCompletionResponse.Choice.finishReason` enum: `"interrupted_tool_call"` (alongside `"stop"`, `"length"`, `"tool_calls"`). Strip the `[TOOL_CALL_TRUNCATED]` sentinel — the finish-reason carries the same information but in a structured way that doesn't pollute `content`.

**Stage 2 — Surface as a tool-result error (in `:agent`):**

In `AgentLoop.kt` near the existing `finishReason == "length"` branch (~line 1144-1155), add:

```kotlin
if (choice.finishReason == "interrupted_tool_call") {
    // Persist the partial assistant turn with Cline's marker for the conversation log.
    contextManager.addAssistantMessage(
        ChatMessage(
            role = "assistant",
            content = (assistantMessage.content ?: "") + "\n\n[Response interrupted by API Error]"
        )
    )

    // For each in-flight tool call that didn't complete, synthesize a tool_result error so the
    // model sees a normal failure and adapts — Continue.dev's pattern.
    val truncatedToolUseId = assistantMessage.toolCalls?.firstOrNull()?.id
        ?: "interrupted_${System.currentTimeMillis()}"
    contextManager.addUserMessage(
        // Or: addToolResult(...) if we route through the tool-result content-block path.
        "Your previous tool call was interrupted by a gateway timeout before the arguments " +
        "finished streaming. Please re-attempt with a smaller / simpler operation — for example, " +
        "split a large plan into multiple shorter responses, or break a long file edit into " +
        "smaller SEARCH/REPLACE blocks."
    )
    continue
}
```

**Stage 3 — Retry budget:**

This counts against the existing `consecutiveMistakeCount` budget (or a new `consecutiveStreamInterruptions` if we want separate accounting). Stop after 3 consecutive interruptions on the same tool to avoid an infinite "gateway always times out on this tool call" loop — escalate to user with "Sourcegraph gateway keeps timing out on long tool calls. Try shortening your request or switching models."

**Stage 4 — Kill the existing anti-pattern:**

- Delete the `[TOOL_CALL_TRUNCATED]` sentinel construction in `SourcegraphChatClient.kt:548-553`.
- Remove the empty-response retry loop's claim on this case: `AgentLoop.kt`'s empty-response handling (around `MAX_CONSECUTIVE_EMPTIES`) should not trigger when `finishReason == "interrupted_tool_call"` — that's a different failure class with a different remediation.

### 5.3 Why NOT to implement Anthropic's "continuation" pattern

Anthropic documents two continuation flavors (`platform.claude.com/docs/en/api/messages-streaming` § "Error recovery"):

1. **Claude 4.5 and earlier — prefill continuation:** submit the partial assistant content as `messages[-1]` with `prefix=True`, model continues from there.
2. **Claude 4.6 and later — explicit user nudge:** add a user message saying *"Your previous response was interrupted and ended with [previous_response]. Continue from where you left off."*

Both are blocked or impractical for us:
- Sourcegraph's `/.api/llm/chat/completions` endpoint and `/.api/completions/stream` endpoint do **not** expose a `prefix=true` flag (Sourcegraph's `Internal OpenAPI full inventory` per memory `reference_sourcegraph_internal_api_full_inventory.md` shows no such field). Even if they did, Anthropic refuses prefill on Opus 4.7 / 4.6 / Sonnet 4.6.
- The Claude 4.6 user-nudge pattern *would* work, but it has a worse property than Continue's tool-result pattern: it asks the model to continue mid-XML-tool-call, where the partial closing tag is ambiguous (was the tool call complete? was a parameter half-written? did the content body have an unclosed tag?). Anthropic's own caveat — *"Tool use … blocks cannot be partially recovered"* — applies here too. The tool-result pattern sidesteps this by asking the model to start a fresh tool call entirely.

### 5.4 Out of scope (parking lot)

- **Detecting mid-stream `event: error` for non-tool failures:** if the stream truncates during *text* output (e.g., a long `plan_mode_respond` with no XML tool call), Continue's tool-result pattern doesn't apply. For that case the right port is Cline's: persist the partial text with `[Response interrupted by API Error]` and let the model re-generate via the normal turn loop. This already aligns with how our `BrainRouter` surfaces gateway errors today; only the sentinel-removal and the missing structured retry need attention.
- **`finishReason: length` (max-tokens hit):** `AgentLoop.kt:1144-1155` already handles this correctly with a "continue from where you left off, using smaller steps" nudge. No change needed there — that path is the Anthropic-4.6 user-nudge pattern, not the prefill pattern, and it's the right port.
- **Adopting Vercel AI SDK's `experimental_repairToolCall`:** would require changing transport from raw SSE to the AI SDK; non-trivial dependency. Not recommended; Continue's hand-rolled pattern fits our existing transport.

---

## Appendix A — Verbatim quotes from Anthropic docs (for reference)

From `https://platform.claude.com/docs/en/api/errors`:

> When receiving a streaming response via SSE, it's possible that an error can occur after returning a 200 response, in which case error handling wouldn't follow these standard mechanisms.

> 504 - `timeout_error`: The request timed out while processing. Consider using streaming for long-running requests.
> 529 - `overloaded_error`: The API is temporarily overloaded.

> Prefilling assistant messages is not supported for [Claude Mythos Preview, Claude Opus 4.7, Claude Opus 4.6, and Claude Sonnet 4.6]. Sending a request with a prefilled last assistant message to any of these models returns a 400 invalid_request_error.

From `https://platform.claude.com/docs/en/api/messages-streaming` § Error events:

> The API may occasionally send errors in the event stream. For example, during periods of high usage, you may receive an `overloaded_error`, which would normally correspond to an HTTP 529 in a non-streaming context:
> ```sse
> event: error
> data: {"type": "error", "error": {"type": "overloaded_error", "message": "Overloaded"}}
> ```

From `https://platform.claude.com/docs/en/api/messages-streaming` § Error recovery:

> ### Claude 4.5 and earlier
> For Claude 4.5 models and earlier, you can recover a streaming request that was interrupted due to network issues, timeouts, or other errors by resuming from where the stream was interrupted. … The basic recovery strategy involves:
> 1. Capture the partial response …
> 2. Construct a continuation request: Create a new API request that includes the partial assistant response as the beginning of a new assistant message
> 3. Resume streaming …
>
> ### Claude 4.6
> For Claude 4.6 models, you should add a user message that instructs the model to continue from where it left off. For example:
> ```text
> Your previous response was interrupted and ended with [previous_response]. Continue from where you left off.
> ```
>
> ### Error recovery best practices
> 1. Use SDK features: Leverage the SDK's built-in message accumulation and error handling capabilities
> 2. Handle content types: Be aware that messages can contain multiple content blocks (text, tool_use, thinking). **Tool use and extended thinking blocks cannot be partially recovered. You can resume streaming from the most recent text block.**
