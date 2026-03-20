# Output Token Limit Handling in Agentic AI Tools

**Date:** 2026-03-21
**Type:** Research
**Status:** Complete

## The Problem

When an LLM hits its output token limit mid-response:
- Streaming stops abruptly (incomplete sentence, broken JSON, partial tool call)
- `finish_reason = "length"` (OpenAI) or `stop_reason = "max_tokens"` (Anthropic) instead of "stop"/"end_turn"
- If the LLM was generating a tool call, the JSON arguments may be truncated/invalid
- The agent's ReAct loop can't process the incomplete response
- Thinking/reasoning models consume output tokens for internal reasoning, leaving fewer for actual response

---

## 1. Claude API (Anthropic) — Official Patterns

### Stop Reason Values
The Claude Messages API returns these `stop_reason` values:
- **`end_turn`** — Natural completion
- **`max_tokens`** — Hit the `max_tokens` limit in the request
- **`tool_use`** — Claude wants to call a tool
- **`stop_sequence`** — Hit a custom stop sequence
- **`pause_turn`** — Server-side tool loop hit iteration limit (default 10)
- **`refusal`** — Safety refusal
- **`model_context_window_exceeded`** — Hit the model's context window limit (Sonnet 4.5+)

### Official Continuation Pattern
Anthropic's cookbook documents the "sampling past max tokens" technique:

```python
# 1. Make initial request
response = client.messages.create(
    model="claude-sonnet-4-6",
    max_tokens=4096,
    messages=[{"role": "user", "content": prompt}]
)

# 2. Check if truncated
if response.stop_reason == "max_tokens":
    # 3. Continue by prefilling assistant message with truncated response
    response2 = client.messages.create(
        model="claude-sonnet-4-6",
        max_tokens=4096,
        messages=[
            {"role": "user", "content": prompt},
            {"role": "assistant", "content": response.content[0].text}
            # Claude continues from where it left off
        ]
    )
    full_text = response.content[0].text + response2.content[0].text
```

### Looped Continuation Pattern (from official docs)
```python
def get_complete_response(client, prompt, max_attempts=3):
    messages = [{"role": "user", "content": prompt}]
    full_response = ""

    for _ in range(max_attempts):
        response = client.messages.create(
            model="claude-opus-4-6",
            messages=messages,
            max_tokens=4096
        )
        full_response += response.content[0].text

        if response.stop_reason != "max_tokens":
            break

        # Continue from where it left off
        messages = [
            {"role": "user", "content": prompt},
            {"role": "assistant", "content": full_response},
            {"role": "user", "content": "Please continue from where you left off."},
        ]
    return full_response
```

### Key Caveat: Prefill Deprecation
**Prefilling is deprecated and NOT supported on Claude Opus 4.6, Claude Sonnet 4.6, and Claude Sonnet 4.5.** The continuation pattern using assistant message prefill still works (adding the full assistant message to history), but the older prefill technique (starting an assistant turn with partial content) is no longer available on newer models.

### Token Cost Impact
Continuation requests are "double-charged" for input tokens — the original prompt tokens are counted again, plus the previous response tokens are counted as input. Output tokens are only charged once per generation.

### Output Token Limits by Model
- Claude Opus 4.6: **128K output tokens** (streaming), **64K** (non-streaming)
- Claude Sonnet 4: 64K output tokens
- Claude Haiku: 8K output tokens

---

## 2. Claude Code

### How It Handles Output Limits
- Claude Code sets output token limits via `CLAUDE_CODE_MAX_OUTPUT_TOKENS` environment variable
- When using Bedrock API, users frequently hit the 4096 max output token limit
- Claude Code has been observed to hit 32K output token maximum in some configurations

### Continuation Mechanism
Claude Code uses **context compaction + continuation prompts**:
1. When context window is exhausted (~80% usage), auto-compaction creates a summary
2. A continuation prompt is injected as a user message:
   > "Please continue the conversation from where we left off without asking the user any further questions. Continue with the last task that you were asked to work on."
3. This is primarily for **context window exhaustion**, not output token limits specifically

### Known Issues
- The continuation prompt overrides user CLAUDE.md safety instructions (issue #24906)
- Auto-summary loses nuance about where the conversation actually was
- After compaction, Claude may pick up the wrong thread and start building against incomplete specs
- Feature request exists for `autoContinue` setting to skip manual continuation prompts (issue #10253)

### Tool Call Truncation
No specific public information about how Claude Code handles truncated tool calls (partial JSON in tool arguments). The agent loop likely treats this as a malformed response.

---

## 3. Aider — The Gold Standard for Continuation

### "Infinite Output" via Prefill
Aider implements the most sophisticated continuation mechanism:

1. **Detection**: When collecting code edits, if the model hits the output token limit, aider detects `finish_reason = "length"` (or `FinishReasonLength` exception)
2. **Prefill continuation**: Initiates another LLM request with the partial response **prefilled as the assistant message**
3. **Boundary heuristics**: Joins text across output limit boundaries using heuristics (reported as "typically fairly reliable")
4. **Repeat**: This process can repeat multiple times for very long outputs

### Model Support
- Works with **150+ model variants** that support assistant message prefill
- Primarily: Anthropic Claude, DeepSeek, Mistral models
- Also works via Azure AI, AWS Bedrock, Vertex AI deployments

### Models Without Prefill Support
- If the model doesn't support prefill, aider throws an error and marks context as exhausted
- A **fallback mechanism** (PR #2631) allows falling back to a dedicated "infinite output" model

### Practical Advice from Aider
- Most models have **quite small output limits, often as low as 4K tokens**
- For large changes: ask for smaller changes per request, break code into smaller files
- Use stronger models (DeepSeek V3, Claude Sonnet) that can return diffs instead of full file rewrites
- Aider **never enforces token limits** — it only reports errors from the API provider

### Thinking Token Caveat
Known issue (#3820): When prefill is used for continuation, thinking tokens from the prior request are NOT included in the continuation, which can degrade reasoning quality across boundaries.

---

## 4. Cursor

### Architecture: Avoid the Problem Entirely
Cursor's primary strategy is to **avoid hitting output limits** through architectural choices:

- **Full file rewrites instead of diffs**: Models struggled with diff-formatted edits, so Cursor has the model rewrite entire files. This actually uses MORE output tokens but is more reliable.
- **Speculative edits**: A speculative decoding algorithm predicts larger chunks at once instead of token-by-token, achieving ~1000 tokens/sec on their 70B model (~13x speedup)
- **AST-based chunking**: Code is split based on Abstract Syntax Tree structure (depth-first traversal), merging sibling nodes into chunks under the token limit
- **Separate "Apply" model**: A specialized fast model (fine-tuned) applies the LLM's edit instructions to the actual file, decoupling generation from application

### Output Truncation Handling
- Terminal/command output is truncated at 20K characters
- No public information about specific continuation mechanisms for truncated LLM responses
- The "Apply" model architecture sidesteps the issue — the LLM generates edit *instructions*, not full code

---

## 5. Cline (VS Code Extension)

### Current State: No Continuation Mechanism
Cline currently **lacks a continuation mechanism** for truncated responses:

- **Incomplete files**: Truncation produces broken syntax
- **Infinite retry loops**: System repeatedly attempts the same request without progress
- **Broken XML tool calls**: Missing closing tags (e.g., `</write_to_file>`)
- **No recovery path**: Requires manual intervention

### Detection Infrastructure
Cline has some detection infrastructure:
- Identifies incomplete tool calls by checking for missing closing XML tags
- Has "extensive context management infrastructure" with auto-compaction
- Context auto-compaction triggers at ~80% usage

### Feature Request (#4370): Continue Generation Button
Proposed but **unimplemented**:
1. Detect truncated responses by identifying incomplete tool calls
2. Replace Save button with "Continue Generation" for partial files
3. Use partial response context to prompt resumption
4. Configurable automatic vs. manual continuation

### Current Workarounds
- Adjust model config for higher output limits
- Custom instructions redirecting to alternative formats
- Break large file writes into smaller chunks (100-150 lines per operation)

### Token Limits
- Anthropic provider: max output 8192 tokens
- AWS Bedrock: capped at 4096 tokens
- Some configs send max_tokens of 131,072 based on model capabilities

---

## 6. Gemini CLI / Google

### Current State: Silent Truncation (Recently Fixed)
- When the Gemini API reaches its output token limit, the CLI **silently stopped generating** with no feedback
- The codebase did NOT check the `finishReason` field in API responses
- When `finishReason` was `"MAX_TOKENS"` or `"LENGTH"`, the system proceeded without alerting users
- **Fixed in PR #2260**: Added detection logic and displays a warning message

### No Continuation Mechanism
- No auto-continuation or prefill mechanism exists
- Users must manually adjust requests after seeing the truncation warning

### Output Token Limits
- Gemini 3.1 Pro: 65,536 max output tokens, but **default is only 8,192**
- Developers must explicitly set `maxOutputTokens` to unlock full capacity
- This 8x gap is the root cause of most truncation issues

### Google ADK (Agent Development Kit)
- When max output is reached in ADK agents, the behavior is similar — no built-in continuation
- Streaming can help receive partial results but doesn't solve the truncation problem

---

## 7. SWE-Agent / OpenHands

### SWE-Agent
- Configurable via `--agent.model.max_output_tokens=64000`
- Default values can be wrong for some models (e.g., returned 128000 for Claude 3.7 Sonnet)
- Uses `get_model_requery_history()` to create temporary history for retries
- Retry strategy uses tenacity library but primarily for API errors, not output truncation
- **Long diffs can cause hangs** (issue #1269) — SWE-agent can hang in submit if very long diff is printed

### OpenHands
- `max_input_tokens` parameter is **not properly enforced** — input length still exceeds limits
- `enable_history_truncation = true` continues the session when hitting context length limit
- History truncation is for **input** context management, not output truncation recovery
- Uses LiteLLM under the hood, which provides token usage tracking per call
- No specific output truncation continuation mechanism documented

---

## 8. Vercel AI SDK — Best Framework-Level Support

The Vercel AI SDK 4.0 provides the most comprehensive framework-level solution:

### `experimental_continueSteps`
- **Automatic detection**: Detects when `finish_reason` is `"length"`
- **Auto-continuation**: Continues the response across multiple steps, combining into a single unified output
- **Clean word boundaries**: Only streams complete words during continuation
- **Trailing token trimming**: Both `generateText` and `streamText` trim trailing tokens to prevent whitespace issues
- Works across **all providers** (OpenAI, Anthropic, Google, etc.)
- Available in both `generateText` and `streamText`

### Tool Call Loop
- Agent loop continues until finish reason is NOT `"tool-calls"` or a stopping condition is met
- Handles the case where a tool call response is truncated

---

## 9. Patterns Summary

### Pattern 1: Prefill Continuation (Aider's approach)
**How it works**: Detect `finish_reason=length`, send another request with the partial response prefilled as the assistant message, model continues from where it left off.

**Pros**: Seamless continuation, works mid-sentence, can repeat for very long outputs
**Cons**: Double-charged for input tokens, boundary heuristics needed, thinking tokens lost across boundaries, prefill deprecated on newest Claude models

**Best for**: Code editing tools where output can be very long

### Pattern 2: User Message Continuation (Claude API official)
**How it works**: Detect `stop_reason=max_tokens`, append the assistant response to history, add a user message "Please continue from where you left off", make another request.

**Pros**: Works with all models (no prefill needed), official Anthropic pattern, preserves conversation structure
**Cons**: "Continue" instruction may cause repetition or context loss, double-charged for tokens

**Best for**: Chat-based agents, general-purpose continuation

### Pattern 3: Context Compaction + Continuation (Claude Code)
**How it works**: When context window is near-full, summarize the conversation and inject a continuation prompt.

**Pros**: Handles arbitrarily long conversations, reduces token costs long-term
**Cons**: Loses nuance, may override user instructions, can pick up wrong thread

**Best for**: Long-running agent sessions (hours of work)

### Pattern 4: Architectural Avoidance (Cursor)
**How it works**: Design the system so outputs stay small — use edit instructions instead of full code, use separate apply models, chunk operations.

**Pros**: No truncation to handle, faster, cheaper
**Cons**: Requires specialized models, more complex architecture

**Best for**: IDE-integrated code editing

### Pattern 5: Chunking / Pre-flight (General)
**How it works**: Before requesting, estimate if the output will be large. If so, break into smaller operations (e.g., edit one function at a time instead of a whole file).

**Pros**: Prevents the problem entirely, each chunk is complete and valid
**Cons**: Requires planning step, may lose cross-chunk context

**Best for**: Agents with planning capabilities

### Pattern 6: Framework Auto-Continue (Vercel AI SDK)
**How it works**: SDK automatically detects `finish_reason=length` and continues, stitching responses together transparently.

**Pros**: Zero application code needed, works across providers
**Cons**: Framework dependency, may not handle tool call truncation well

**Best for**: Web applications using Vercel stack

---

## 10. Handling Truncated Tool Calls

This is the **hardest sub-problem**. When a tool call's JSON arguments are truncated:

### What Happens
```json
// Complete tool call
{"name": "edit_file", "input": {"path": "/foo.kt", "content": "class Foo {\n  fun bar() {\n    return 42\n  }\n}"}}

// Truncated at output limit
{"name": "edit_file", "input": {"path": "/foo.kt", "content": "class Foo {\n  fun bar() {\n    retu
```
The JSON is **invalid** — it cannot be parsed.

### Current Approaches

| Tool | Approach |
|------|----------|
| **Claude API** | Returns `stop_reason: "max_tokens"` — but the `tool_use` content block may have truncated `input` JSON. No official guidance on recovery. |
| **Cline** | Detects missing XML closing tags (e.g., `</write_to_file>`), but has no recovery mechanism |
| **Aider** | Prefill continuation works because aider uses text-based edit formats (search/replace blocks), not JSON tool calls |
| **Vercel AI SDK** | `experimental_continueSteps` may handle this but unclear for tool calls specifically |
| **OpenHands** | No specific handling documented |

### Recommended Strategy for Truncated Tool Calls
1. **Detect**: Check `stop_reason == "max_tokens"` AND response contains a `tool_use` content block
2. **Don't parse**: The JSON is invalid, don't try to parse it
3. **Retry with guidance**: Send the truncated response back with a user message asking the model to retry with a smaller operation
4. **Or continue**: If the model supports it, continue generation to complete the JSON (only works with prefill-capable models)
5. **Fallback**: Ask the model to break the operation into smaller pieces

---

## 11. Recommendations for Our Agent (Sourcegraph Enterprise Cody)

### Our Constraints
- **Sourcegraph Enterprise API**: Standard output limit is **4,000 tokens** (~500-600 lines of code)
- **Enhanced context window** (feature flag): Up to **64K output tokens** for Claude models
- **Thinking models**: Consume output tokens for reasoning, leaving fewer for actual response
- **Tool calls can be large**: `edit_file` with big code blocks, `create_file` with full file contents

### Recommended Architecture

#### A. Detection Layer
```kotlin
sealed class LlmStopReason {
    object EndTurn : LlmStopReason()
    object MaxTokens : LlmStopReason()  // Output truncated
    object ToolUse : LlmStopReason()
    object Error : LlmStopReason()
}

// In the ReAct loop, after every LLM call:
when (response.stopReason) {
    MaxTokens -> handleTruncation(response)
    ToolUse -> executeToolCalls(response)
    EndTurn -> processCompletion(response)
    Error -> handleError(response)
}
```

#### B. Truncation Recovery Strategy (3-tier)

**Tier 1: Retry with smaller scope** (preferred)
- If truncated during a tool call, ask the model to break the operation into smaller pieces
- "Your previous response was truncated. Please break the edit into smaller changes, one function at a time."

**Tier 2: User message continuation**
- Append truncated response to history
- Add user message: "Your response was truncated at the output token limit. Please continue from where you left off."
- Parse combined response

**Tier 3: Reduce output demand**
- If repeated truncation, reduce `max_tokens` for thinking budget
- Switch to a more concise output format (diff instead of full file)
- Ask model to describe changes instead of writing full code

#### C. Pre-flight Estimation
Before requesting an edit, estimate if the output will be large:
- If the file being edited is > 200 lines, instruct the model to use diff/patch format
- If creating a new file > 300 lines, chunk into sections
- Set `max_tokens` based on expected output size + buffer

#### D. Tool Call Validation
After every tool call extraction:
1. Validate JSON is complete and parseable
2. If JSON is truncated (parse error + stop_reason was max_tokens):
   - Log the truncation event
   - Do NOT execute the partial tool call
   - Apply Tier 1 or Tier 2 recovery

#### E. Thinking Budget Management
For thinking/reasoning models with shared output token pool:
- Reserve a minimum token budget for the actual response (e.g., 2K tokens)
- If `thinking_tokens + response_tokens` approaches limit, consider:
  - Reducing thinking budget in retry
  - Using a non-thinking model for large code generation tasks

### Configuration
```kotlin
data class OutputTokenConfig(
    val maxOutputTokens: Int = 4000,           // Sourcegraph default
    val maxContinuationAttempts: Int = 3,       // Max retry/continue attempts
    val truncationRetryStrategy: Strategy = Strategy.SMALLER_SCOPE,
    val enablePreflightEstimation: Boolean = true,
    val thinkingBudgetReserve: Int = 2000       // Min tokens reserved for response
)
```

---

## 12. Sources

### Claude API / Anthropic
- [Handling stop reasons — Claude API Docs](https://platform.claude.com/docs/en/build-with-claude/handling-stop-reasons)
- [Sampling past max tokens — Claude Cookbook](https://platform.claude.com/cookbook/misc-sampling-past-max-tokens)
- [Handle stop reasons — Agent SDK](https://platform.claude.com/docs/en/agent-sdk/stop-reasons)
- [Claude Code max output token issue #6158](https://github.com/anthropics/claude-code/issues/6158)
- [Claude Code Bedrock 4096 limit issue #10041](https://github.com/anthropics/claude-code/issues/10041)
- [Continuation prompt override issue #24906](https://github.com/anthropics/claude-code/issues/24906)
- [Auto-continue feature request #10253](https://github.com/anthropics/claude-code/issues/10253)
- [Claude Code Token Limits Guide — Faros AI](https://www.faros.ai/blog/claude-code-token-limits)

### Aider
- [Infinite Output — Aider Docs](https://aider.chat/docs/more/infinite-output.html)
- [Token Limits — Aider Docs](https://aider.chat/docs/troubleshooting/token-limits.html)
- [Thinking tokens with prefill issue #3820](https://github.com/Aider-AI/aider/issues/3820)
- [Infinite output model fallback PR #2631](https://github.com/Aider-AI/aider/pull/2631)
- [Premature ending issue #2169](https://github.com/Aider-AI/aider/issues/2169)

### Cursor
- [How Cursor Implemented Instant Apply — Bind AI](https://blog.getbind.co/2024/10/02/how-cursor-ai-implemented-instant-apply-file-editing-at-1000-tokens-per-second/)
- [Instant Apply — Cursor Blog](https://cursor.com/blog/instant-apply)

### Cline
- [Continue Generation feature request #4370](https://github.com/cline/cline/issues/4370)
- [Code truncated issue #14](https://github.com/cline/cline/issues/14)
- [Truncated code can't resume #3772](https://github.com/cline/cline/issues/3772)
- [Context Management — Cline Docs](https://docs.cline.bot/prompting/understanding-context-management)

### Gemini / Google
- [Silent truncation in Gemini CLI #2104](https://github.com/google-gemini/gemini-cli/issues/2104)
- [Missing maxOutputTokens truncation #23081](https://github.com/google-gemini/gemini-cli/issues/23081)
- [Gemini 3.1 Pro 64K Output Guide](https://www.aifreeapi.com/en/posts/gemini-3-1-pro-output-limit)
- [ADK max output discussion #2579](https://github.com/google/adk-python/discussions/2579)

### SWE-Agent / OpenHands
- [SWE-agent max output tokens issue #1035](https://github.com/SWE-agent/SWE-agent/issues/1035)
- [OpenHands max_input_tokens bug #8606](https://github.com/OpenHands/OpenHands/issues/8606)
- [SWE-agent long diff hang #1269](https://github.com/SWE-agent/SWE-agent/issues/1269)

### Sourcegraph Cody
- [Cody Token Limits — Sourcegraph Docs](https://sourcegraph.com/docs/cody/core-concepts/token-limits)
- [Model Configuration — Sourcegraph Docs](https://sourcegraph.com/docs/cody/enterprise/model-configuration)

### Vercel AI SDK
- [AI SDK 4.0 — Vercel Blog](https://vercel.com/blog/ai-sdk-4-0)
- [Continue generation issue #8459](https://github.com/vercel/ai/issues/8459)
- [streamText reference — AI SDK](https://ai-sdk.dev/docs/reference/ai-sdk-core/stream-text)

### General
- [5 Approaches to Solve LLM Token Limits — Deepchecks](https://www.deepchecks.com/5-approaches-to-solve-llm-token-limits/)
- [Rearchitecting Letta's Agent Loop — Letta Blog](https://www.letta.com/blog/letta-v1-agent)
- [Overcoming Response Truncation in Azure OpenAI](https://medium.com/@ankitmarwaha18/overcoming-response-truncation-in-azure-openai-a-comprehensive-guide-cb85249cf007)
